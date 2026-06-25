---
title: Process Coordination
type: explanation
status: stable
description: 'MMF signaling, "Suicide Pact", and "Breath Holding".'
---

# 02. Process Coordination & The "Nervous System"

In a multi-process architecture, coordination is the hardest problem. JustSearch uses a custom "Nervous System" built on **Memory-Mapped Files (MMF)** and **gRPC** to ensure sub-millisecond coordination between the Main Process ("Head") and the Knowledge Server ("Body").

## Why Not Just REST?

We use a hybrid approach:
1.  **gRPC:** For rich data transfer (search results, file submission). It's strongly typed and fast.
2.  **MMF (Memory Mapped Files):** For *signals* (heartbeats, port discovery, interrupts). It's effectively instant and works even if the JVM is garbage collecting.

## The Signal Bus (`WorkerSignalBus`)

The `WorkerSignalBus` uses a tiny 64-byte shared memory segment specifically structured for atomic reads/writes.
*   **Impl:** `MmfWorkerSignalBus.java` (Worker Side) / `MainSignalBus.java` (Main Side)
*   **Safety:** Little-endian byte order is enforced to ensure cross-process compatibility.
*   **Schema ownership (important)**: the MMF layout constants are single-owned in
    `modules/ipc-common/src/main/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1.java` to prevent silent drift.
    A cross-compat test (`MmfSignalBusCompatibilityTest`) ensures Head/Worker agree on offsets/sizes.

| Offset | Size | Purpose | Writer | Reader |
| :--- | :--- | :--- | :--- | :--- |
| `0-7` | 8 bytes | **Last Activity (ms)** | Main | Worker |
| `8-15` | 8 bytes | **Main Heartbeat (ms)** | Main | Worker |
| `16` | 1 byte | **Shutdown Signal** | Main | Worker |
| `17-19` | 3 bytes | *reserved* | - | - |
| `20-23` | 4 bytes | **gRPC Port** | Worker | Main |
| `24` | 1 byte | **GPU Active** | Main | Worker |
| `25-26` | 2 bytes | **Magic Bytes** (`0x534A` = "JS") | Writer at open | Validator at open |
| `27` | 1 byte | **Format Version** (currently `1`) | Writer at open | Validator at open |
| `28` | 1 byte | **Compat Flags** (reserved) | - | - |
| `29` | 1 byte | **Reload Signal** (dev hot-reload; `1`=reload requested) | Gradle task | Worker |
| `30-63` | 34 bytes | *reserved* | - | - |

**Header validation**: On `open()`, both Main and Worker validate the magic bytes and version. Files with all-zero header bytes (pre-Sprint 3A) are treated as legacy v1 for backward compatibility. See `MmfWorkerSignalHeaderV1.java`.

**Version bump policy:** Increment `FORMAT_VERSION` when adding, removing, or reordering fields in `MmfWorkerSignalLayoutV1`. Expanding into the 34-byte reserved region at the current format version does *not* require a version bump, as long as old readers safely ignore the new bytes (which they do — they simply don't read them). (Offset `29` is the dev-only `OFFSET_RELOAD_SIGNAL`, written by the Gradle hot-reload task and read by the Worker.) When incrementing, the legacy detection path in `MmfWorkerSignalHeaderV1` must be updated to accept the new version.

### 1. Port Discovery
Since we cannot guarantee which ports are free on a user's machine, the Knowledge Server starts its gRPC server on **Port 0** (ephemeral).
1.  **Main:** Calls `MainSignalBus.zeroPort()` to clear any stale data at offset 20.
2.  **Worker:** Starts gRPC server, OS assigns port `54321`.
3.  **Worker:** Writes `54321` to `worker_grpc_port` (MMF offset `20`, `MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT`).
4.  **Main:** Spins (polls) `worker_grpc_port` (interval: 100ms) until it sees a non-zero value.
5.  **Main:** Connects `RemoteKnowledgeClient` to `localhost:54321`.

### 2. The "Suicide Pact" (Reliability)
A common issue in desktop development is "zombie processes" staying alive after the app closes. JustSearch implements a "Suicide Pact":
*   **Main Process:** Updates `heartbeat_epoch_ms` (MMF offset `8`, `MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS`) every 1 second via `WorkerSpawner`.
*   **Worker Process:** Has a watchdog inside `MmfWorkerSignalBus.shouldDie()`.
*   **Rule:** After a startup grace period (~15s), if `CurrentTime - Heartbeat > 5000ms`, the Worker invokes `server.close()` and exits.
*   **Result:** If Main crashes (blue screen, SIGKILL), Worker cleans itself up within 5 seconds.

### 3. Breath Holding (Responsiveness)
To ensure the UI never stutters during heavy indexing:
*   **Main Process:** Detects user activity from the UI (mouse/keyboard) **and** from interactive foreground API routes (e.g., search/preview/AI streaming).
*   **Main Process:** Updates `activity_epoch_ms` (MMF offset `0`, `MmfWorkerSignalLayoutV1.OFFSET_ACTIVITY_EPOCH_MS`).
*   **Worker Process:** Checks this timestamp before processing every file in `IndexingLoop`.
*   **Rule:** If `CurrentTime - LastActivity < 2000ms`, the Worker yields.
*   **Result:** The user gets 100% CPU/Disk priority. Indexing only happens in micro-idle moments.

Benchmarking/automation note (important):
- Do **not** use `POST /api/knowledge/search` in a tight polling loop to wait for indexing to finish: it can signal user activity and keep the Worker in breath-hold.
- Prefer `GET /api/status` or `GET /api/knowledge/status` to wait for quiescence, then do a single sentinel search to validate searchability.

### 4. GPU Arbitration (VRAM)
We support running on cards with only 8GB VRAM, which creates a conflict between:
1.  **Embedding Model (`nomic-embed-text` GGUF via llama.cpp):** Can use GPU when configured (but defaults to CPU-only).
2.  **Generative LLM (served by `llama-server.exe`):** Uses most VRAM when Online Mode is active.

**The Protocol:**
1.  **User** opens "Chat".
2.  **Main** writes `1` to `main_gpu_active` (MMF offset `24`, `MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE`).
3.  **Worker** reads this flag in `IndexingLoop`.
4.  **Worker** unloads the embedding backend to free VRAM (and skips embedding work while the flag is set).
5.  **Main** launches `llama-server.exe` (or adopts an already-running instance on the configured port).

## OS-Specific Details (Windows)
File locking on Windows is aggressive. A Memory-Mapped File is technically "open" by the OS kernel, which prevents deletion.
*   **Problem:** We can't delete the `app-state` folder if the MMF is mapped.
*   **Solution:** Both `MainSignalBus` and `MmfWorkerSignalBus` use Java FFM (`Arena.ofShared()` + `MemorySegment`) instead of legacy `MappedByteBuffer`. Cleanup is handled via `arena.close()`, which reliably releases the kernel file mapping.

    Shutdown paths are structured to avoid closing the arena while other threads are still reading/writing the segment (stop scheduled writers first, then close).

## Coordination via locks (exclusive ownership)

MMF + gRPC handle *signals and data*. Separately, we use OS file locks to enforce “one owner” safety invariants:

- **App instance lock**: `AppInstanceLock` acquires an exclusive lock on `<dataDir>/app.lock` (Head-side) so two app instances can’t share the same data directory (logs, `jobs.db`, and index roots).
- **Index root lock**: `IndexRootLock` acquires an exclusive lock on the **effective** index root, using a sibling lock file:
  - `<indexBasePath>.index.lock` (note: **not inside** `indexBasePath`)
  - This matters on Windows because legacy import/migration may rename `indexBasePath`, and Windows can refuse to rename a directory that contains a locked file handle.

## Resilience Patterns

### Supervised Restart (death **and** hang) with Exponential Backoff
`WorkerSpawner` supervises the Worker against two fault classes, both routed through one budgeted
decision (`SupervisionDecision`, the recovery-contract authority — tempdoc 627):
*   **Clean death** (process exit/segfault/OS-kill): the 1s death monitor detects `!isAlive()` and respawns.
*   **Hang** (process alive but gRPC-unresponsive): the health monitor forwards each poll via
    `recordHealthResult`; after `hangUnhealthyThreshold` (default 3) consecutive unhealthy polls on a
    live process — the Worker's *liveness* signal, distinct from the *readiness* DEGRADED flag — it
    triggers a **graceful** restart. This closes the Worker's observation→actuation loop (previously a
    hung Worker was marked DEGRADED but never recovered).
*   **Shared budget:** death-restarts and hang-restarts draw from one cap (`MAX_RESTART_ATTEMPTS=3`),
    serialized under one lock so the two paths cannot double-spend it.
*   **Backoff formula:** `min(1000 * 2^(attempts-1), 30000)` ms.
*   **Stability window:** after stable operation, the restart counter resets (prevents "restart limit = app dead").
*   **Graceful stop before force:** a restart of a *live* worker first writes the signal-bus shutdown so
    the Worker commits+closes its Lucene index, forcing termination only on timeout. `Process.destroy()`
    is a hard `TerminateProcess` on Windows (no flush), so the supervised path and `restart()` both use
    the graceful path; an abrupt force-kill's durability is owned by the index-durability work (tempdoc 628).
*   **Terminal give-up:** once the budget is exhausted the supervisor stops and transitions the worker
    capability to a terminal, reason-coded state (`DEGRADED` + `worker.restart_exhausted`), surfaced on
    `/api/health` and worded in the System Health readiness notice — distinct from the transient
    `worker.unavailable` (which retries).

### Circuit Breaker (gRPC)
`GrpcCircuitBreaker` wraps gRPC methods to prevent cascading failures when the Worker is unhealthy:
*   **States:** CLOSED → OPEN → HALF_OPEN → CLOSED
*   **Trigger:** Consecutive `UNAVAILABLE` or `DEADLINE_EXCEEDED` failures
*   **Reset:** Automatic on worker restart

### gRPC keepAlive

`RemoteKnowledgeClient` configures keepAlive on the ManagedChannel to detect Worker hangs (e.g., GC pauses, deadlocks) without waiting for the next RPC timeout:
*   `keepAliveTime`: 30 seconds (interval between pings when idle)
*   `keepAliveTimeout`: 5 seconds (time to wait for ping ACK)
*   `keepAliveWithoutCalls`: true (ping even when no RPCs are in flight)

### PID Validation
After port discovery, `KnowledgeServerBootstrap.validateWorkerPid()` verifies the gRPC port belongs to the spawned worker PID. This prevents connecting to stale/zombie processes that wrote their port before being killed.

### Deadline Categories
`RemoteKnowledgeClient` uses categorized deadlines to handle different operation profiles:

| Category | Multiplier | Use Case |
| :--- | :--- | :--- |
| STANDARD | 1x | Search, health, status, submitBatch |
| CONTENT_FETCH | 2x | FetchDocuments, FetchDocumentSlice, RetrieveContext |
| VDU_OPERATION | 2x | UpdateVduResult, RecoverVduProcessing |
| INDEX_GC | 6x | RunIndexGc |
| LONG_RUNNING | 60x | PruneMissing, SyncDirectory (large indexes)

### Crash Reporting

`Thread.setDefaultUncaughtExceptionHandler` is installed as the first statement in `main()` for both Head (`HeadlessApp.java`) and Worker (`IndexerWorker.java`). It catches uncaught exceptions on any thread (including virtual threads) and writes a structured crash report before `System.exit(1)`.

`CrashReporter` (`modules/telemetry/`) writes JSON to `<dataDir>/crashes/crash-<role>-<pid>-<epochMs>.json`:
- Manual JSON via `StringBuilder` (no Jackson — must not itself crash during crash handling)
- Content: schema version, timestamp, process role, PID, thread name/id, exception type/message/full stack trace, JVM version, OS, heap used/max, uptime
- `defaultCrashDir()` reads `justsearch.data.dir` system property — used by Worker to satisfy ArchUnit guardrail (`IndexerWorkerGuardrailsTest` forbids `System.getProperty` in the `indexerworker` package)
- Entire `writeCrashReport()` wrapped in try-catch — prints to stderr as last resort

### JVM Crash Flags

Both Head and Worker JVMs receive native crash flags:

| Flag | Purpose |
| :--- | :--- |
| `-XX:ErrorFile=<dataDir>/crashes/hs_err_pid%p.log` | Native JVM crash logs (segfaults from FFM/JNI) |
| `-XX:+HeapDumpOnOutOfMemoryError` | Heap dump on OOM |
| `-XX:HeapDumpPath=<dataDir>/crashes/` | Heap dump output directory |

- Worker flags: added in `WorkerSpawner.buildCommand()`. Path separators normalized to `/` for Windows `-XX:` flag compatibility.
- Head flags: added in Tauri `spawn_headless_backend()` (`lib.rs`).

## Configuration Constants & Thresholds

### Timeout Values
| Constant | Value | Purpose |
| :--- | :--- | :--- |
| `STARTUP_GRACE_MS` | 15,000 ms | Grace period before suicide pact enforcement |
| `HEARTBEAT_STALE_MS` | 5,000 ms | Heartbeat stale threshold |
| `PORT_DISCOVERY_TIMEOUT_MS` | 60,000 ms | Default port discovery timeout (configurable) |
| `WORKER_SHUTDOWN_TIMEOUT_MS` | 5,000 ms | Default graceful shutdown wait (configurable) |

### Restart Policy
| Constant | Value | Purpose |
| :--- | :--- | :--- |
| `MAX_RESTART_ATTEMPTS` | 3 | Attempts before giving up |
| `BASE_COOLDOWN_MS` | 1,000 ms | Initial backoff |
| `MAX_COOLDOWN_MS` | 30,000 ms | Maximum backoff cap |

### Batch Processing
| Constant | Value | Purpose |
| :--- | :--- | :--- |
| `DEFAULT_BATCH_SIZE` | 5,000 files | Default chunk size for submitBatch |
| `MAX_BATCH_SIZE` | 10,000 files | Upper bound (Worker limit) |

### gRPC Retry Policy
- **Enabled for:** SearchService (all methods), HealthService (all methods), and idempotent IngestService methods: `IndexStatus`, `QueryPendingVdu`, `PruneMissing`, `RecoverVduProcessing`, `SyncDirectory`
- **Disabled for:** Non-idempotent IngestService methods (`SubmitBatch`, `DeleteByPath`, `DeleteById`, `MarkVduProcessing`, `UpdateVduResult`, migration control RPCs)
- **Backoff:** 0.1s initial, 2.0x multiplier, 2s max

### Resource Cleanup Patterns

Standard graceful shutdown pattern for thread pools and gRPC channels:

```java
// ThreadPoolExecutor
executor.shutdown();
try {
  if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
    executor.shutdownNow();
  }
} catch (InterruptedException e) {
  Thread.currentThread().interrupt();
  executor.shutdownNow();
}

// gRPC ManagedChannel
channel.shutdown();
try {
  if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
    channel.shutdownNow();
  }
} catch (InterruptedException e) {
  Thread.currentThread().interrupt();
  channel.shutdownNow();
}
```

Files using this pattern:
- `TranslatorExecutorPool.java` (ThreadPoolExecutor)

## Hot-Reload (Developer Workflow)

The `reload` command (MCP: `justsearch.dev.reload`) supports iterative development without full restart. It compiles the changed module, pushes bytecode to the running Worker via JDWP, writes an MMF signal to trigger service reconstruction, and returns a structured response.

### Reload Response Fields

| Field | Type | Description |
| :--- | :--- | :--- |
| `hotSwapOk` | boolean | Whether JDWP class redefinition succeeded |
| `structuralChangeDetected` | boolean | True when HotSwapPush output contains "added/removed methods or fields" |
| `restartRequired` | string | Present when `hotSwapOk === false` and structural changes detected. Message: `"RESTART REQUIRED: structural changes detected"` |

When `hotSwapOk === false` and structural changes are detected, the Worker continues running with old bytecode. A full dev stack restart is required to pick up the new code.

### Build Stamp Propagation

After a successful reload (`hotSwapOk === true`), the MCP reload tool propagates the build stamp:

1. Reads `build-stamp.txt` from the distribution root (`build/install/indexer-worker/build-stamp.txt`).
2. Writes the stamp to `<dataDir>/reload-build-stamp.txt` **before** the MMF signal, to avoid a race with the Worker's sentinel thread that triggers `performReload()`.
3. After service reconstruction, `DevReloadManager.updateBuildStampFromReloadFile()` reads the reload stamp file and calls `System.setProperty()` to update the running system property.

On structural-change failure (`hotSwapOk === false`), the stamp file is NOT written -- the Worker remains correctly marked as stale relative to the on-disk distribution.

See [ADR-0021](../decisions/0021-build-stamp-content-hash.md) for the design rationale (content hash vs timestamp vs git SHA).

## Deprecations

| Item | Status | Replacement | Notes |
| :--- | :--- | :--- | :--- |
| `vdu_status` (string field) | Deprecated | `outcome` enum | Back-compat shim in `computeEffectiveOutcome()` |
| `PruneMissing` RPC | Deprecated | `SyncDirectory` | Deletion-only vs. bidirectional sync |
| `Cursor.legacy(String)` | Active bridge | - | gRPC wire format returns raw string token |
| `justsearch.data_dir` (underscore) | Alias | `justsearch.data.dir` | Back-compat for historical inconsistency |

## Known Edge Cases & Mitigations

### Port Discovery Race Condition
**Risk:** Stale/zombie worker can write port between `zeroPort()` and new worker publishing.
**Mitigation:** PID validation in `validateWorkerPid()` detects mismatch and retries.
**Residual risk:** Narrow window; no production issues observed.

### GPU Arbitration Advisory Nature
**Risk:** GPU exclusion flag (`main_gpu_active`) is advisory only. Worker honors it via `IndexingLoop` checks, but no enforcement mechanism exists.
**Mitigation:** Honor system is sufficient for single-user desktop app.

### Atomicity Assumptions
Aligned 4/8-byte fields are "effectively atomic" on 64-bit x86/ARM platforms. This is NOT guaranteed by Java specification. If stronger guarantees needed (e.g., embedded/exotic platforms), explicit sequencing protocol would be required.

### Heartbeat Flush Semantics
`MappedByteBuffer.force()` is for **disk durability**, NOT cross-process visibility. Sub-millisecond visibility on mapped pages is automatic. Calling `force()` every 1s would add I/O and potentially cause false stale-heartbeat events.

## Design Decisions (Intentionally Not Implemented)

| Proposal | Decision | Reasoning |
| :--- | :--- | :--- |
| Split StatusResponse | DEFERRED | No telemetry proving bottleneck. Add metrics first. |
| Port discovery optimization | ALREADY EXISTS | `reconnect()` skips if port unchanged. |
| Streaming batch ingest | NOT PLANNED | Client-side 5000-file chunking sufficient for desktop. |
| Fallback search on Worker failure | NOT PLANNED | Existing circuit breaker + auto-restart is correct. |
| Multi-worker support | NOT PLANNED | Desktop app with single data directory; complexity not justified. |

## Appendix: gRPC Service Reference

### SearchService Methods
| Method | Deadline | Description |
| :--- | :--- | :--- |
| Search | STANDARD | Execute search query |
| Suggest | STANDARD | Get search suggestions |
| FetchDocuments | CONTENT_FETCH | Retrieve document content |
| FetchDocumentSlice | CONTENT_FETCH | Retrieve document slice |
| RetrieveContext | CONTENT_FETCH | RAG context retrieval |
| MatchCitations | CONTENT_FETCH | Post-hoc cross-encoder citation scoring |

### IngestService Methods
| Method | Deadline | Description |
| :--- | :--- | :--- |
| SubmitBatch | STANDARD | Submit file paths for indexing |
| IndexStatus | STANDARD | Get queue/index status |
| DeleteByPath | STANDARD | Remove document by path |
| DeleteById | STANDARD | Remove document by ID |
| SyncDirectory | LONG_RUNNING | Full directory reconciliation |
| PruneMissing | LONG_RUNNING | (Deprecated) Use SyncDirectory |
| StartMigration | STANDARD | Begin index migration |
| RequestCutover | STANDARD | Request generation cutover |
| PauseMigration | STANDARD | Pause migration |
| ResumeMigration | STANDARD | Resume migration |
| RollbackMigration | STANDARD | Abort migration |
| RunIndexGc | INDEX_GC | Trigger index garbage collection |
| UpdateVduResult | VDU_OPERATION | Store VDU analysis result |
| QueryPendingVdu | STANDARD | Query pending VDU items |
| MarkVduProcessing | STANDARD | Mark VDU item in-progress |
| RecoverVduProcessing | VDU_OPERATION | Recover stuck VDU items |

### HealthService (Dual Namespace)
| Namespace | Methods | Notes |
| :--- | :--- | :--- |
| `io.justsearch.ipc` | `Check` (returns `worker_state` enum) | Primary surface |
| `io.justsearch.ipc.v1` | `Liveness`, `Readiness`, `Version` | Cleaner design, AI Worker surface |

**Note:** Dual namespace is historical; not recommended to merge (high cost, breaking change).
