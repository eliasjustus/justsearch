---
title: Backend Tech Debt Issues
type: reference
status: stable
updated: 2026-06-15
description: "Legacy code, deprecated APIs, internal consistency."
---

# Backend Tech Debt Issues

Issues related to legacy code, deprecated APIs, and internal consistency in backend modules.

**Key Files:**
- `modules/indexer-worker/`
- `modules/adapters-lucene/`
- `modules/ai-backend/`
- `modules/ui/`
- `modules/app-inference/`

---

## Issues

---

### BKD-002: No deprecation telemetry for legacy API usage
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-30
- **Component:** `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/` (the `ReadPathOps`/`WritePathOps`/`CommitOps` family; formerly the `LuceneIndexRuntime` facade), `modules/indexer-worker/`

**Description:** No telemetry tracks which deprecated APIs or fields are still being called. This makes it difficult to determine when it's safe to remove legacy code paths.

**Impact:** Legacy code removal decisions are based on guesswork rather than usage data.

**Recommendation:** Create a `DeprecationMetrics` class with counters for deprecated method invocations. Instrument `GrpcIngestService` and `LuceneIndexRuntime` deprecated paths.

---

### BKD-005: Missing config validation before inference mode transition
- **Severity:** P3
- **Status:** open
- **Found:** 2026-01-23
- **Component:** `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java`

**Description:** Mode transitions (e.g., CPU -> GPU, model swap) proceed without validating that the new configuration is consistent. Invalid configurations are only caught at runtime when the inference server fails to start.

**Impact:** Users may trigger a mode transition that fails late, leaving the inference subsystem in a degraded state.

**Recommendation:** Add a validation step before mode transition that checks model file existence, VRAM sufficiency (for GPU modes), and configuration consistency.

---

### BKD-006: Production source files above 1,000 LOC ceiling
- **Severity:** P3
- **Status:** open
- **Found:** 2026-02-09
- **Updated:** 2026-02-11
- **Component:** multiple modules

**Description:** Full repo scan found 12 production Java source files at or above the 1,000 LOC ceiling. LocalApiServer (1,433 → 591), RuntimeConfig (1,766 → 954), FullCoverageSummarizer (1,237 → 853), and SqliteJobQueue (1,127 → 865) have been decomposed. Organized by action category:

**Decompose (recommended):**

| File | LOC | Module | Nature | Seams |
|------|-----|--------|--------|-------|
| `GrpcIngestService.java` | 1,086 | indexer-worker | gRPC service (5 ops classes already extracted) | Second-wave: endpoint-specific handlers |
| `KnowledgeServer.java` | 1,034 | indexer-worker | Worker orchestration (3 collaborators already extracted) | Embedding ops extraction (~80 lines) puts it under ceiling |
| `AiInstallService.java` | 1,007 | ui | Download orchestration + hash verification (~50 methods) | Download ops, verification, state persistence |
| `RuntimeActivationService.java` | 1,002 | ui | Variant activation + self-test + rollback (~40 methods) | Variant management, self-test ops, health checks |

**Exception (no decomposition recommended):**

| File | LOC | Module | Reason |
|------|-----|--------|--------|
| ~~`LuceneIndexRuntime`~~ | ~~1,653~~ | adapters-lucene | Deleted (tempdoc 320): facade removed, logic absorbed into ops (`ReadPathOps`/`WritePathOps`/`CommitOps`/`RunningRuntime`). |
| ~~`NativeLlamaBinding.java`~~ | ~~1,189~~ | ~~ai-backend~~ | Removed with the former in-process FFM llama binding path; current online runtime lifecycle is `modules/app-inference`. |
| ~~`DefaultPipelineEngine.java`~~ | ~~1,099~~ | ~~pipeline-engine~~ | Removed 2026-02-19 with module `pipeline-engine` (see ADR 0007). |
| `LocalIntentTranslatorConfig.java` | 1,007 | ai-backend | Immutable config with 50+ fields + builder pattern. ~110 getter/setter methods. Config objects are inherently field-heavy; splitting would scatter related configuration. |

(The `class-size` gate and its standard were removed for go-public — tempdoc 634; the table above is now advisory tech-debt tracking, not an enforced ceiling.)

**Impact:** The 4 remaining decomposable files are orchestration chokepoints with broad defect/regression blast radius. `AiInstallService` and `RuntimeActivationService` are the highest-value targets (no prior decomposition, clear seams).

**Recommendation:** Prioritize by value:
1. **P4 — `AiInstallService`**: Extract download orchestration and verification ops.
2. **P4 — `RuntimeActivationService`**: Extract self-test and variant management ops.
3. **P4 — `GrpcIngestService`**: Second-wave endpoint handler extraction.
4. **P4 — `KnowledgeServer`**: Minor embedding ops extraction (~80 lines) puts it under ceiling.

---

### BKD-009: gRPC retry policy gap in 3 non-RKC clients
- **Severity:** P2
- **Status:** resolved (implemented)
- **Found:** 2026-02-10
- **Resolved:** 2026-02-19
- **Component:** `modules/app-search/`

**Description:** The non-RKC gRPC client retry/circuit gap has been implemented using shared resilience primitives:
- `GrpcAiTranslatorService` now uses `GrpcRetryServiceConfig` + shared `GrpcCircuitBreaker`

**Resolution Notes (2026-02-19):**
- The original immediate-failure path for transient `UNAVAILABLE` errors is closed.
- Retry/circuit behavior is wired for the translator surface.
- `GrpcAnnSearchClient` and `GrpcEmbeddingClient` were deleted in tempdoc 325 (dead code — never wired into production).

**Residual Scope (tracked in tempdoc 219):**
1. Ongoing calibration/policy tuning and short-smoke evidence updates (`RR-010`).
2. Release-governance and conformance evolution (`RR-013`, `RR-011`).

---

### BKD-010: Health check coverage gap — AI readiness not checked
- **Severity:** P2
- **Status:** resolved (implemented)
- **Found:** 2026-02-10
- **Resolved:** 2026-02-19
- **Component:** `modules/app-services/`, `modules/indexer-worker/`

**Description:** Health readiness signal propagation has been implemented:
- Worker `HealthCheckResponse` now carries `ai_ready` and `embedding_ready`
- Worker `GrpcHealthService` reports readiness fields
- Head-side status mapping exposes these fields in `/api/status`

**Resolution Notes (2026-02-19):**
- The original "single boolean only" health gap is closed.
- AI/embedding readiness now propagate from worker health checks to UI-facing status.

**Residual Scope (tracked in tempdoc 219):**
1. Typed readiness semantics contract and reason taxonomy (`RR-005`).
2. Cross-surface parity hardening (`/api/health` lifecycle vs `/api/status` readiness envelope).

---
### BKD-008: Burst detection non-atomic check-then-act in RemoteKnowledgeClient
- **Severity:** P4
- **Status:** open
- **Found:** 2026-02-06
- **Component:** `modules/app-services/src/main/java/io/justsearch/app/services/worker/BurstDetector.java`

**Description:** `checkBurstAndScheduleSync()` has a non-atomic check-then-act pattern with 3 race windows when multiple watched roots trigger events concurrently on `ForkJoinPool.commonPool()`. All races are **safe** (data integrity maintained, operations are idempotent, 60s periodic sync provides safety net) but can cause redundant sync scheduling.

**Threading model:** Events are sequential within a single root but concurrent across roots. The race only manifests with 2+ watched roots.

**Impact:** Performance degradation only — redundant sync RPCs. No data loss or corruption.

**Recommendation:** P4 priority. Fix with `AtomicBoolean` CAS or synchronized block if sync overhead becomes measurable.

---

### BKD-011: Unbounded vduExecutor in OnlineModeOps
- **Severity:** P3
- **Status:** open
- **Found:** 2026-02-10
- **Component:** `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java`

**Description:** Vision completion requests use an unbounded `ExecutorService` (`vduExecutor`). Under rapid-fire vision requests, tasks queue without bound. The executor is a `newCachedThreadPool` — each request spawns a new thread with no upper limit.

**Impact:** Memory pressure under pathological vision request bursts. Unlikely in the desktop app (vision requests are user-initiated one at a time), but there is no safety net if a caller loops.

**Recommendation:** Replace with a bounded thread pool (e.g., `newFixedThreadPool(2)`) or add a `CallerRunsPolicy` rejection handler. Defer unless memory pressure evidence appears in production.

---

### BKD-012: Stream cancellation not plumbed through inference layers
- **Severity:** P4
- **Status:** open
- **Found:** 2026-02-10
- **Component:** `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java`, `InferenceLifecycleManager.java`

**Description:** `streamChat` is fire-and-forget (`CompletableFuture.runAsync`). No cancellation token flows from the caller to the HTTP request. If the user navigates away mid-stream, the HTTP connection runs to completion server-side.

**Impact:** Wasted CPU/network for abandoned streams. Minor on a local desktop app where llama-server is localhost.

**Recommendation:** Requires plumbing a cancellation token through OnlineAiService → ILM → OnlineModeOps → HTTP request (interface change across 3 layers). Low ROI for a local desktop app where client disconnect is rare. Revisit if remote inference is added.

---

### BKD-013: Exclude patterns not applied during file walking
- **Severity:** P4
- **Status:** open (deprioritized)
- **Found:** 2026-02-06
- **Component:** `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/grpc/GrpcIngestService.java`

**Description:** User-configured exclude patterns (`build/`, `dist/`, `vendor/`) are applied during post-indexing cleanup via `/api/indexing/excludes/apply`, not during `syncDirectory()` file walking. Excluded directory trees are fully traversed and files enqueued before being filtered at processing time by `shouldSkip()`.

**Impact:** Wasted I/O during initial sync — seconds, not minutes, because the largest directories (`node_modules`, `.git`, `$Recycle.Bin`) are already in the hardcoded `WALK_SKIP_DIRS` list. Remaining user-configured excludes target smaller directories (10-100x smaller).

**Recommendation:** Deprioritized. Requires gRPC schema change to pass exclude patterns to the Worker's walk phase. Fix only if users report slow initial indexing of large collections with many excluded directories.

---

### BKD-014: Junction points cause duplicate indexing
- **Severity:** P4
- **Status:** open (deprioritized)
- **Found:** 2026-02-06
- **Component:** `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/grpc/GrpcIngestService.java`

**Description:** Windows junction points (e.g., `Documents and Settings` → `Users`) are transparent to Java — `isSymbolicLink()` returns false, so `walkFileTree` traverses them. Same files may be indexed via different paths with different doc IDs. Path normalization uses lowercase + separator replacement, not `toRealPath()`.

**Impact:** Partially mitigated — default Windows junctions (`Documents and Settings`, etc.) are ACL-blocked, so `visitFileFailed` already handles them. Custom junctions are uncommon on desktop systems. Fix is trivial (`attrs.isOther()` in `preVisitDirectory`) but low urgency.

**Recommendation:** Deprioritized. Fix when junction-related duplicate reports appear in dogfooding.

---

### BKD-015: No user-visible feedback for unindexable files
- **Severity:** P4
- **Status:** open (deprioritized)
- **Found:** 2026-02-06
- **Component:** `modules/indexer-worker/`

**Description:** Encrypted, corrupt, or unsupported files are indexed with placeholder content `"File: [filename]"`. Users have no way to discover which files couldn't be properly indexed until they search for something specific and don't find it.

**Impact:** Quality-of-life issue, not a bug. User forum research confirms users don't notice missing files until they search for specific content. Most unindexable files (executables, binaries) aren't expected to be searchable.

**Recommendation:** Deprioritized. Consider a `/api/status` field reporting unindexable file count, or a "file health" section in the UI. Low ROI for current user base.

---

### BKD-016: SQLite job queue write contention under 10k+ files
- **Severity:** P4
- **Status:** open
- **Found:** 2026-02-03
- **Component:** `modules/indexer-worker/`

**Description:** SQLite gives zero-dependency persistence and simplicity for the job queue, but has write contention under high-throughput bulk ingestion. WAL checkpoint stalls are possible under sustained write pressure.

**Impact:** Ingestion throughput degrades with large file collections (10k+ files). The recent decomposition (SqliteJobQueue into ops classes) improved structure but didn't change the underlying SQLite bottleneck.

**Reassess when:** Ingestion benchmarks show >2x throughput regression vs. direct queue, or users report slow bulk imports exceeding 30 minutes for typical collections.

---

### BKD-017: Manual Panama FFM bindings maintenance burden
- **Severity:** P4
- **Status:** retired
- **Found:** 2026-02-03
- **Component:** Former in-process `NativeLlamaBinding.java` path; no live production class exists in the current codebase.

**Description:** Historical issue for the removed in-process Panama FFM llama binding. Full control over struct layouts and function descriptors carried manual maintenance risk on each llama.cpp upgrade (~15-20 functions, complex structs). Missed changes could cause silent memory corruption or crashes.

**Impact:** No current runtime impact while the in-process binding remains removed. If this binding path is reintroduced, open a new issue tied to the new owner and current source path.

**Notes:** See [ADR-0005](../../decisions/0005-manual-ffm-bindings.md). Chosen over JNI (no C wrapper build step needed) and jextract (poor struct layout generation for llama.cpp headers).

**Reassess when:** a production in-process llama binding is reintroduced.

---

### BKD-018: No backup/restore for Lucene index
- **Severity:** P3
- **Status:** open
- **Found:** 2026-02-03
- **Component:** `modules/indexer-worker/`, `modules/adapters-lucene/`

**Description:** No backup infrastructure exists. Users can't recover from index corruption without full reindex from source files. Source files remain the authority — the Lucene index is derived data — but reindex time is the user-facing cost.

**Impact:** Index corruption (power loss, disk error, failed migration) requires full reindex. Reindex time scales with collection size and is unbounded.

**Recommendation:** A Lucene snapshot-based backup would be the minimal viable solution.

**Reassess when:** Users report data loss from index corruption, or index sizes grow large enough that reindexing becomes impractical (>50GB index, >4 hours reindex time).

---

### BKD-019: InferenceLifecycleManager constructor complexity
- **Severity:** P4
- **Status:** mitigated
- **Found:** 2026-02-03
- **Component:** `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java`

**Description:** The ILM to Ops decomposition moved method bodies into collaborator classes (`LlamaServerOps`, `OnlineModeOps`, `TokenEndpointOps`, `ServerPropsOps`) but retained all shared state, lock ownership, and mode transition logic in ILM. The 14 constructor lambdas create a circular dependency: Ops classes read/write ILM state through callbacks, while ILM owns the lock guarding that state.

**Impact:** Modifying crash recovery or mode transitions requires tracing lambda flows across ILM + LlamaServerOps. The forwarding tax (13 pure delegation methods in ILM) remains but is harmless.

**Mitigation:** Partially mitigated by `ModeStateMachine` extraction (2026-02-10), which formalized 20 raw `currentMode` assignments into validated operations. See also BKD-009 and BKD-010 for specific improvements within this subsystem.

**Reassess when:** ILM grows beyond 1000 lines again, or a new feature (multi-model, remote inference) requires a cleaner component boundary between process management and mode management.

---

### BKD-020: TypeScript settings type duplication
- **Severity:** P4
- **Status:** open
- **Found:** 2026-03-29
- **Component:** `modules/ui-web/src/api/domains/settings.ts`, `modules/ui-web/src/stores/systemTypes.ts`

**Description:** `UISettings`, `LLMSettings`, and `AppSettings` types are duplicated between `api/domains/settings.ts` (canonical) and `stores/systemTypes.ts` (stale copy), bridged by a cast in `useSettings.ts` (line 111). The two copies can drift, causing subtle type errors or silent field loss.

**Impact:** Maintenance burden and risk of type drift between the two definitions. Changes to settings fields must be mirrored in both locations.

**Recommendation:** Delete the duplicate types from `stores/systemTypes.ts` and import from `api/domains/settings.ts`. Fix the cast in `useSettings.ts` to use proper type mapping. (368)

---

### BKD-021: IndexGenerationManager builds generation IDs at second granularity (collision on rapid migration)
- **Severity:** P4
- **Status:** open
- **Found:** 2026-06-15
- **Component:** `modules/worker-core/src/main/java/io/justsearch/indexerworker/index/IndexGenerationManager.java`

**Description:** `startMigration()` derives a new generation id as `"g-" + TS.format(Instant.now())` where `TS` is second-granularity (`g-yyyyMMdd-HHmmss`). Two generations created within the same wall-clock second resolve to the same id, and `startMigration` then throws `IOException("Refusing to start migration: generation already exists: …")`. Verified directly — a sequence of `initializeOrLoad()` then `startMigration()` within one second trips it.

**Impact:** A rapid second migration (or a migration in the same second as the initial generation) fails with an IOException rather than allocating a distinct generation. Low real-world likelihood (migrations are infrequent and slow), but a latent sharp-edge under automation/tests.

**Recommendation:** Use a higher-resolution or monotonic suffix for generation ids (millis, or a short sequence/random suffix), or detect the collision and retry with a bumped suffix. (Found during tempdoc 589.)

---

### BKD-022: SqliteJobQueue legacy write paths swallow SQLException and return success-shaped values
- **Severity:** P3
- **Status:** open
- **Found:** 2026-06-15
- **Component:** `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java`

**Description:** The class carries two error contracts. The outcome-aware variants `throw OutcomeWriteException` on `SQLException` (`:411`, `:512`, `:614`). The legacy paths instead catch + `log.error` + return a **success-shaped** value: `enqueue` returns `0` (`:295-297`, indistinguishable from "no paths"), `pollPending` returns `List.of()` (`:345-347`, indistinguishable from "no work"), and `markDoneBatch`/`markFailed` return void (`:455-456`, `:585-586`). Verified from source.

**Impact:** On a transient SQLite error (busy-timeout exhausted, disk pressure), a caller of the legacy paths gets no signal that the write was lost — `markDoneBatch` silently marks nothing (jobs stay `PROCESSING`, relying on `recoverStuckJobs()`/the reaper), and `enqueue` returning `0` reads as "nothing to enqueue." Robustness/observability gap (mitigated for markDone by stuck-job recovery) and an inconsistent contract within one class.

**Recommendation:** Align the legacy paths with the throwing contract (or have callers check row counts) so a persistence failure is observable rather than masked. (Found during the tempdoc 588/589 backend audit.)

---

### BKD-023: StructuredContentHandler silently drops an in-progress table/list when the content cap is hit mid-element
- **Severity:** P3
- **Status:** open
- **Found:** 2026-06-15
- **Component:** `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/StructuredContentHandler.java`

**Description:** Once the extracted-content cap is reached, `limitReached` is set and **every** `endElement` early-returns (`if (limitReached) return;`, `:137`). A `</table>`/`</ol>`/`</ul>` arriving after the cap therefore never reaches its element-emitting branch (the `Table` is built+added on the `</table>` branch, `:158-166`), and the final flush rescues only the plain text buffer — not the pending table/list state. Verified from source.

**Impact:** For a document whose cap falls inside a large table or list, the entire partially-built table/list is dropped from the structured extraction rather than being truncated-but-present — silent partial data loss, distinct from the intended "cut at cap" behavior.

**Recommendation:** On final flush (`getDocument()`), emit any pending table/list buffer the same way the text buffer is flushed. (Found during the tempdoc 588/589 backend audit.)

---

### BKD-024: IndexingJobsChangeStream delivers deltas synchronously to subscribers (head-of-line-blocking risk)
- **Severity:** P4
- **Status:** open
- **Found:** 2026-06-15
- **Component:** `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStream.java`

**Description:** `flushPending()` iterates `subscribers` and calls `sub.accept(delta)` **synchronously** in-line (`:242-246`). The synchronous in-line delivery is confirmed from source; the audit additionally reports this runs on the commit path while the `SqliteJobQueue` write-lock is held (verify the exact call-chain before acting). Subscriber `RuntimeException`s are caught, but a *slow/blocking* subscriber is not bounded.

**Impact:** A slow or backpressured subscriber (e.g. a stalled gRPC stream consumer for `core.indexing-jobs`) can stall delta delivery and — if delivery is under the write-lock — every queue mutation for that duration. Latent head-of-line-blocking hazard; severity depends on subscriber implementations.

**Recommendation:** Hand deltas to a bounded async dispatch queue, or document+enforce that subscribers must be non-blocking. (Found during the tempdoc 588/589 backend audit.)

---

### BKD-025: Worker auto-restart recovery gaps (unverified static-audit findings)
- **Severity:** P3
- **Status:** open (needs source verification)
- **Found:** 2026-06-15
- **Component:** `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java`, `bootstrap/KnowledgeServerBootstrap.java`

**Description:** Three related lifecycle-audit findings, **not yet verified against source** (recorded so they aren't lost; confirm the exact call-chains before acting): (a) after a fast Worker crash-restart, the gRPC circuit breaker may stay OPEN for its ~10s cooldown with no explicit `resetCircuitBreaker()`/`reconnect()` from the restart path; (b) the `WorkerSpawner` restart path may not force the non-READY→READY edge that `KnowledgeServerHealthMonitor` needs to re-run catch-up init (`reindexPersistedRoots()`/`startPeriodicSync()`), risking an empty index after a fast restart; (c) `KnowledgeServerBootstrap.close()` may not close its `KnowledgeServerHealthMonitor`, leaking a ticking thread on rapid restart-close cycles.

**Impact:** If real — blocked RPCs for ~10s after an otherwise-healthy fast restart (a), an empty/stale index after restart (b), and a thread leak on restart-close churn (c).

**Recommendation:** Verify each against source before acting (these are static-audit hypotheses — tempdoc 588's refuted finding F-2 shows some dissolve under scrutiny); if confirmed, reset/reconnect the client and force the health-monitor recovery edge on respawn, and close the monitor in bootstrap teardown. (Found during the tempdoc 588/589 backend audit; logged as a candidate slice.)

---
