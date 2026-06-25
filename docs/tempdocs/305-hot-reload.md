---
title: "305: Hot-Reload for Dev Iteration"
type: tempdoc
status: done
created: 2026-03-14
updated: 2026-03-16
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 305: Hot-Reload for Dev Iteration

## Implementation status

- **Phase 0** (standard HotSwap on Temurin): **Done.** All 4 items complete and verified.
- **Phase 1** (JBR + enhanced HotSwap): **Done.** All 8 items complete and verified.
  JBR accepts structural changes. HotswapAgent auto-reload not working (classes from
  JARs); `HotSwapPush.java` is the trigger for both phases.
- **Phase 2** (service restart on signal): **Done.** Full auto-reload chain:
  Gradle compile → HotSwapPush (bytecode update via JDWP) → MMF signal → sentinel
  detect → service reconstruction (15ms). JDWP auto-enabled on port 5005.
- **MCP integration**: **Done.** `justsearch_dev_reload` tool for on-demand reload.
  `start` accepts `hotReload: true` to enable JDWP + DevReloadManager.
  Usage: `start(hotReload: true)` → edit code → `reload()` → live in ~2-3s.

**Post-merge verification on main (2026-03-16):**
- [x] Build from main: `spotlessApply` + `assemble` pass
- [x] `start(hotReload: true)`: DevReloadManager initialized, JDWP on 5005
- [x] Baseline search: 23 hits, cross-encoder applied, TEXT mode
- [x] `reload(skipCompile: true)` via MCP: 44 classes redefined, signal written,
  service restart (59ms first reload). Marker `HOTRELOAD-MAIN-VERIFY` confirmed
  live in Worker log on next search request.
- [x] Post-reload search: 6 hits, cross-encoder still working — no regressions
- [x] `reload` Gradle compile: requires MCP server restart for `gradleCmd` full-path
  fix. Workaround: manual compile + `reload(skipCompile: true)`.

**Merged to main** (`d76304e3b`, 2026-03-16):
- `72ce63cc2` refactor: split indexer-worker into worker-core + worker-services (tempdoc 308)
- `decd4510f` feat(dev): add hot-reload Phase 2 — service restart on signal (tempdoc 305)
- `dd8380e4a` chore: regenerate lock files for worker-core and worker-services

## Problem

The dev iteration loop is: edit → build → restart → verify. Even with tempdoc 302's
optimizations (2066ms → 894ms Worker, -57%), every code change requires a full process
restart. The restart destroys all runtime state: loaded ONNX models (~1.5s to reload),
open Lucene index (~250ms), gRPC server (~180ms), JVM warmth (JIT profiles, class
resolution caches).

Hot-reload eliminates the restart step entirely for the common case: changing pure Java
business logic (search algorithms, ranking formulas, indexing pipeline stages, API
handlers) while preserving JNI-bound native state (ORT sessions, Lucene handles, SQLite
connections).

## Estimated impact

- **Current dev iteration**: edit → build (35s) → restart (894ms Worker + 460ms Head)
- **With hot-reload**: edit → incremental compile (**~1.9s measured**) → class swap (~100ms)
- **Net dev iteration: ~2s** (vs 35s full build or ~1.4s restart-only)
- **Restart eliminated for**: search orchestration, ranking, API handlers, pipeline
  stages, config processing, UI backend endpoints
- **Restart still required for**: ORT model changes, Lucene schema changes, gRPC
  proto changes, native library updates

## JVM hot-reload landscape (2026)

### Standard HotSwap (JDK built-in)

- Supports: method body replacement only (same signature)
- Does NOT support: adding/removing methods, fields, constructors, changing class
  hierarchy, changing interfaces
- Available in all JDKs via debug agent (`-agentlib:jdwp`)
- **Limitation for this app**: Most code changes during development involve adding
  methods, changing signatures, or modifying record structures — standard HotSwap
  rejects these.

### DCEVM / JetBrains Runtime (JBR)

- Original DCEVM project (dcevm/dcevm) is dead — only covers JDK 7-11
- **JBR 25 is the active successor**: JetBrains Runtime integrates DCEVM-like enhanced
  HotSwap directly. Latest: JBR 25.0.2-b329.72 (March 2026).
- Supports structural class changes: add/remove/modify fields, methods, constructors
- Enabled via `-XX:+AllowEnhancedClassRedefinition`
- **Limitation**: Requires switching to JBR for dev mode (Temurin doesn't have this).
  Cannot reload classes with JNI/FFM native bindings.

### JRebel (commercial)

- Supports full structural changes without DCEVM
- Works via a Java agent that rewrites class loading
- Maintains state across reloads (field values preserved)
- **Limitation**: Commercial license. Adds agent startup overhead (~2-3s). May
  conflict with ORT JNI and Lucene's use of Unsafe.

### Spring DevTools / Quarkus Dev Mode

- Framework-specific — restart the application classloader, not the JVM
- Fast because the JVM stays warm (JIT profiles, loaded native libs preserved)
- Spring DevTools restarts in ~1-2s for Spring apps
- **Not applicable**: This app is not Spring/Quarkus-based. But the pattern
  (application classloader restart) could be implemented manually.

### Custom Classloader Restart (most relevant)

- Split the classpath into "platform" (immutable: Lucene, ORT, gRPC, Netty) and
  "application" (mutable: JustSearch modules)
- On code change: create a new classloader for application classes, re-instantiate
  services, preserve platform-level state (ORT sessions, Lucene index, gRPC server)
- This is what Spring DevTools and Quarkus Dev Mode do internally

## Architecture assessment

### What can be hot-reloaded (pure Java, no native state)

| Component | Package | Reloadable? |
|-----------|---------|-------------|
| `SearchOrchestrator` | `indexer-worker` | Yes — stateless per-request |
| `GrpcSearchService` | `indexer-worker` | Yes — delegates to orchestrator |
| `GrpcIngestService` | `indexer-worker` | Yes — delegates to job queue |
| `IndexingLoop` logic | `indexer-worker` | Partially — loop thread must be restarted |
| `ContentExtractor` | `indexer-worker` | Yes — stateless per-extraction |
| API controllers | `ui` | Yes — stateless per-request |
| `AppFacadeBootstrap` | `app-services` | Yes — service wiring |
| Pipeline stages | `indexing` | Yes — stateless transforms |
| Config processing | `configuration` | Yes — pure parsing |

### What cannot be hot-reloaded (JNI / native state)

| Component | Why |
|-----------|-----|
| `OrtSession` (SPLADE, embedding) | Native ORT C++ object, JNI pointers |
| `OrtEnvironment` | Singleton native object |
| `LuceneIndexRuntime` | `IndexWriter` holds native file handles via `MMapDirectory` |
| `SqliteJobQueue` | Native SQLite connection via JNI |
| `MmfWorkerSignalBus` | Memory-mapped file via Java FFM |
| `WindowsJobObject` | Win32 handle via Java FFM |
| `HuggingFaceTokenizer` | DJL native tokenizer |
| gRPC `Server` | Netty native channel |

### The key insight

The components that change most frequently during development (search logic, ranking,
indexing pipeline, API handlers) are all pure Java and reloadable. The components that
hold native state (ORT, Lucene, SQLite, Netty) rarely change — they're infrastructure.

### Scope of current implementation (Phase 0/1)

The table above shows what is *theoretically* reloadable. The current Phase 0/1
implementation only auto-reloads **`indexer-worker` module classes** (Worker process).
This covers the primary dev loop: `SearchOrchestrator`, `GrpcSearchService`,
`GrpcIngestService`, `IndexingLoop`, `ContentExtractor`.

**Not auto-reloaded by Phase 0/1** (still require restart):
- `API controllers` (`ui` module) — Head process, not Worker. Mitigated by tempdoc 304
  (Worker Persistence): Head restarts are fast since Worker state is preserved.
- `Pipeline stages` (`indexing` module) — in a separate JAR, not on the watched
  classpath. Could be added by prepending additional class directories.
- `Config processing` (`configuration` module) — same.
- `AppFacadeBootstrap` (`app-services` module) — Head-side wiring.

Phase 2 (custom classloader restart) would cover the full surface if needed.

## Investigation results (2026-03-14)

### Q1: Can the Worker's service layer be separated via classloader?

**Answer: Yes, but at class-level within `indexer-worker`, not at module-level.**

The `indexer-worker` module contains both infrastructure classes (holding native state)
and application classes (pure Java logic). A clean module-level split is not possible.

**Platform classloader** (immutable across reloads):
- `adapters-lucene` — `LuceneIndexRuntime` (holds Lucene IndexWriter, MMap handles)
- `ipc-common` — gRPC generated stubs, proto types
- `indexing` — `IndexRuntime` interface, `SchemaFields`, `IndexDocument` (shared types)
- `configuration` — config parsing
- `indexer-worker` classes with native state:
  - `EmbeddingService` — holds `OrtSession` (JNI)
  - `SpladeEncoder` — holds `OrtSession` (JNI)
  - `NerService` — holds `OrtSession` (JNI)
  - `MmfWorkerSignalBus` — holds `MemorySegment` (FFM)
  - `SqliteJobQueue` — holds native SQLite connection
- Third-party: Lucene, ONNX Runtime, gRPC, Netty, DJL, SLF4J/Logback

**Application classloader** (reloadable):
- `SearchOrchestrator` — stateless per-request, delegates to infrastructure
- `GrpcSearchService` — delegates to orchestrator
- `GrpcIngestService` — delegates to job queue, index runtime
- `IndexingLoop` — loop thread must be stopped and re-created
- `ContentExtractor` — stateless per-extraction
- Pipeline stages, config processing helpers

**Key architectural observation**: The `volatile` setter pattern already in use for
deferred model wiring (tempdoc 302) is the exact same pattern needed for hot-reload.
Infrastructure objects are injected into reloadable application code via constructor
or volatile setters. All critical boundaries already use this pattern.

**No JPMS modules** — the project uses classpath (not module path), so there are no
module system restrictions on class visibility across classloaders.

### Q2: Does SearchOrchestrator hold state that can't be reconstructed?

**Answer: No. Fully reconstructable.**

`SearchOrchestrator` fields:
- `final LuceneIndexRuntime indexRuntime` — infrastructure (survives reload)
- `final EmbeddingService embeddingService` — infrastructure (survives reload)
- `volatile Supplier<EntityClusterSnapshot> clusterSnapshotSupplier` — set via setter
- `volatile SpladeEncoder spladeEncoder` — set via setter
- `volatile SpladeIdfQueryEncoder spladeIdfQueryEncoder` — set via setter

Constructor takes infrastructure objects. All volatile fields are wired via setters
after construction. Stateless per-request — no accumulated runtime state. Can be
reconstructed trivially from config + infrastructure references.

### Q3: Can gRPC service implementations be swapped at runtime?

**Answer: Not natively. Need delegating service pattern.**

- `GrpcSearchService extends SearchServiceGrpc.SearchServiceImplBase` — registered via
  `ServerBuilder.addService()` in `KnowledgeServerGrpcWiring.java` (lines 50-101).
  `builder.build()` produces an immutable `Server` instance.
- gRPC-Java does not support runtime service replacement after `server.start()`.
- **Solution**: A thin delegating service in the platform classloader that holds a
  `volatile` reference to the actual application-layer implementation. All RPC methods
  forward to the current implementation. On reload, swap the volatile reference.
- The delegating service would extend `SearchServiceGrpc.SearchServiceImplBase` (platform
  class), registered once at server start. The actual logic lives in the application
  classloader behind an interface.
- In-flight RPC calls complete with the old implementation. New RPCs immediately see
  the new implementation. Graceful transition with no request drops.
- Estimated code change: ~200 lines for SearchService wrapper (~9 RPCs), ~250 lines for
  IngestService wrapper (~20+ RPCs). Mechanical delegation, no logic changes.
  ~800-1000 LOC total including tests.

### Q4: Classpath split — platform vs application JARs

**Answer: 168 JARs total (489 MB). Application layer is tiny (3.1 MB, 0.6%).**

| Category | Count | Size | % of Total |
|----------|-------|------|-----------|
| Platform (immutable) | 108 | ~411 MB | 84% |
| Application (reloadable) | 10 | ~3.1 MB | 0.6% |
| Ambiguous (safer in platform) | 50 | ~75 MB | 15% |

**Platform breakdown** (major groups):
- ONNX Runtime GPU: 354 MB (single JAR — 72% of total, embedded CUDA natives)
- Lucene: 7-8 JARs, ~22 MB (with ICU)
- gRPC/Netty: 12 JARs, ~12 MB
- Tika + document processing: 30+ JARs, ~30 MB
- SQLite JDBC: 14 MB (embedded native)
- BouncyCastle crypto: ~10 MB
- Jackson/serialization: ~12 MB
- Logging/telemetry: ~3 MB
- Commons/utilities: ~8 MB

**Application JARs** (JustSearch modules, all `io.justsearch.*`):
- `indexer-worker-2.0.0-SNAPSHOT.jar` (479K) — entry point, main logic
- `ipc-common-2.0.0-SNAPSHOT.jar` (1.4M) — gRPC proto types
- `adapters-lucene-2.0.0-SNAPSHOT.jar` (237K) — Lucene adapter
- `ai-bridge-2.0.0-SNAPSHOT.jar` (229K) — ORT bridge
- `configuration-2.0.0-SNAPSHOT.jar` (151K) — config management
- 8 smaller modules (<150K each)

**Key finding**: No JustSearch modules contain JNI code directly. Native state is
held via third-party library calls (`OrtSession`, `IndexWriter`, etc.). The classloader
boundary is clean: application modules use abstract APIs (gRPC stubs, Lucene
`SearcherManager`, configuration interfaces) that exist in the platform classloader.

**Practical split**: Keep all 108+ third-party JARs + ambiguous JARs in platform. Only
the 10 JustSearch modules (3.1 MB) go in the application classloader. The child
classloader is extremely lightweight.

### Q5: Is DCEVM available for JDK 25?

**Answer: Yes, via JetBrains Runtime (JBR). Original DCEVM is dead.**

| Tool | JDK 25 | Windows | JNI/FFM Safe | Cost |
|------|--------|---------|--------------|------|
| **JBR 25 + HotswapAgent** | Yes | Yes | No (JNI reload broken) | Free |
| JRebel | Yes (v2025.4.0) | Yes | No (same JNI issue) | ~$500/yr |
| CRaC | Yes | **No** (Linux only, CRIU) | N/A | Free |
| Project Leyden AOT | Yes | Yes | N/A | Free |

**JetBrains Runtime (JBR) 25**: Active, latest release JBR 25.0.2-b329.72 (March 2026).
Supports structural class changes via `-XX:+AllowEnhancedClassRedefinition`. The
`jbr25` branch is actively maintained. DCEVM-like enhanced HotSwap is integrated
directly into JBR — no separate DCEVM patch needed.

**HotswapAgent 2.0.3**: Open-source hot-reload agent, supports JDK 25 with JBR.
Setup: download JBR 25, copy `hotswap-agent.jar` to `lib/hotswap/`, launch with
`-XX:+AllowEnhancedClassRedefinition -XX:HotswapAgent=fatjar`.

**Original DCEVM** (dcevm/dcevm, TravaOpenJDK): Dead. Only covers JDK 7-11. No JDK 25.

**CRaC**: Azul ships CRaC builds for JDK 25, but it depends on CRIU (Linux kernel
feature). **Not viable for this project** (Windows desktop app via Tauri).

**JNI limitation — largely inapplicable to this project**: The JNI classloader
limitation (reloading a class that defines `native` methods or loaded a native library)
does not apply here because:
1. JustSearch code has **zero JNI `native` method declarations** (verified via grep)
2. All native interaction is via **FFM** (`java.lang.foreign.*`) or by calling
   Java APIs on third-party objects (`OrtSession.run()`, `IndexWriter.addDocument()`)
3. FFM handles (`MethodHandle`, `MemorySegment`) are Java objects that survive
   class redefinition
4. Third-party JARs (ORT, SQLite, DJL) that use JNI internally are never modified
   during dev — their native bindings are untouched
5. Lucene 10.x uses Panama/FFM for MMap, not `sun.misc.Unsafe`

**Implication**: The JNI risk previously assessed as a blocker for Approach B is
effectively zero for this project's hot-reload surface. Both approaches can safely
reload all application-layer classes.

### Q6: Gradle incremental compile time (measured)

**Answer: Fast enough for hot-reload workflow.**

| Operation | Time | Notes |
|-----------|------|-------|
| Compile only (`classes`) | **~1.9s** | Single-file change in `indexer-worker` |
| Compile + JAR + installDist | **~10.4s** | Full distribution update |
| Gradle continuous build (`-t`) | Works | Watches for file changes, recompiles |
| Full `build` (all modules + tests) | ~35s | Not needed for hot-reload |

For hot-reload, the relevant path is compile-only (~1.9s) + class swap (~100ms) =
**~2s total dev iteration time** (down from ~1.4s restart or ~35s full build).

### Additional infrastructure findings

- **MMF signal bus**: 35 reserved bytes (offsets 29-63). Offsets 25-28 are occupied
  by `MmfWorkerSignalHeaderV1` (magic/version/flags). A reload signal byte at
  offset 29 (`OFFSET_RELOAD_SIGNAL = 29`) fits naturally. Both `MainSignalBus`
  (Head-side) and `MmfWorkerSignalBus` (Worker-side) have established patterns for
  adding new signal types.
- **Sentinel thread**: Polls every 1 second in `KnowledgeServer.startSentinelThread()`.
  Natural place to check for reload signal alongside `shouldDie()`.
- **directory-watcher**: `io.methvin:directory-watcher` (v0.19.1) already in the project
  (`app-indexing` module). Could be leveraged for file-change detection.
- **IndexerWorker.main()**: Creates a single `KnowledgeServer`, calls `start()`, blocks
  on `blockUntilShutdown()`. For hot-reload, this structure is favorable — the JVM stays
  alive, only application-layer components are reconstructed.
- **IndexingLoop lifecycle**: `start()` creates a daemon thread. `close()` sets
  `running=false` and interrupts the thread. Clean stop/restart cycle is built-in.

## Implementation approaches (revised after investigation)

### Approach A: Custom classloader restart (Medium complexity, most viable)

**Updated assessment**: The existing volatile-setter wiring pattern from tempdoc 302
makes this approach more feasible than initially estimated.

1. **Classpath split**: Platform JARs (Lucene, ORT, gRPC, Netty, SLF4J, plus
   `EmbeddingService`, `SpladeEncoder`, `NerService` from `indexer-worker`) stay in
   system classloader. Application classes (`SearchOrchestrator`, `GrpcSearchService`,
   `GrpcIngestService`, `IndexingLoop` logic) load in a child classloader.
2. **gRPC delegating services**: Thin wrappers in platform classloader extend
   `SearchServiceGrpc.SearchServiceImplBase` and `IngestServiceGrpc.IngestServiceImplBase`.
   Hold `volatile` references to application-layer implementations. Registered once at
   server start, never replaced. ~100 lines per service (mechanical delegation).
3. **On code change**: Quiesce IndexingLoop (set `running=false`, join thread). Discard
   child classloader. Create new child classloader from updated `classes` directory.
   Instantiate new application services via reflection, inject infrastructure objects.
   Swap delegating service volatile refs. Start new IndexingLoop thread.
4. **Signal mechanism**: Add `OFFSET_RELOAD_SIGNAL = 29` to `MmfWorkerSignalLayoutV1`
   (offset 25-28 occupied by `MmfWorkerSignalHeaderV1` magic/version/flags).
   Sentinel thread checks every 1s alongside `shouldDie()`. Gradle continuous build or
   external script sets the signal after successful compile.
5. **Estimated reload time**: ~100-200ms (classloader creation + service instantiation +
   thread start). No model loading, no Lucene open, no gRPC server restart.

**Complexity**: ~500-700 lines of new code. Delegating services (~200 lines), classloader
management (~150 lines), reload orchestration (~150 lines), MMF signal extension (~50 lines),
Gradle task or script (~50 lines).

### Approach B: JBR 25 + HotswapAgent (Low complexity, viable)

1. Run Worker with JBR 25 and `-XX:+AllowEnhancedClassRedefinition -XX:HotswapAgent=fatjar`
2. `./gradlew -t :modules:indexer-worker:classes` recompiles on file changes (~1.9s)
3. HotswapAgent detects class changes, triggers enhanced HotSwap via JBR
4. Structural changes (new methods, fields) handled transparently by JBR
5. **No application code changes needed** for pure-Java business logic
6. **JNI risk is effectively zero**: JustSearch code has no `native` method
   declarations. All native interaction is via FFM or third-party Java APIs. The
   reloadable classes don't load native libraries or define native methods.
7. **Advantage over A**: Zero application code changes. No delegating services, no
   classloader management, no reload orchestration.
8. **Disadvantage vs A**: Requires JBR instead of Temurin for dev mode.
   IntelliJ's built-in HotSwap works automatically with JBR.

### Approach C: Gradle continuous build + compile-only reload (Minimal complexity)

1. `./gradlew -t :modules:indexer-worker:classes` — Gradle watches for changes,
   recompiles incrementally (~1.9s measured)
2. On successful compile, signal the Worker via MMF (byte at offset 29)
3. Worker detects signal in sentinel thread, triggers classloader restart (Approach A)
4. **This is Approach A with Gradle as the file-change trigger.** Approaches A and C
   are not separate — C is the "how to detect changes" half of A.

## Risks (updated after investigation)

- **Class identity**: Objects created by the old classloader are not `instanceof` the
  new classloader's classes. **Mitigated**: Infrastructure objects (`LuceneIndexRuntime`,
  `EmbeddingService`, etc.) stay in the platform classloader and never cross the boundary
  as application types. Application objects are fully reconstructed on reload — no old
  references survive.
- **Thread safety**: Reloading while the IndexingLoop is mid-batch could corrupt state.
  **Mitigated**: `IndexingLoop.close()` sets `running=false` and interrupts the thread.
  The loop checks `running.get()` at the top of each iteration. Quiesce is built-in.
  In-flight gRPC requests complete with the old implementation (gRPC request lifecycle
  is independent of the delegating service swap).
- **Memory leaks**: Old classloaders and their classes aren't GC'd if any reference
  from the platform layer holds them. **Risk is real**: The delegating service pattern
  uses `volatile` refs — old implementation becomes unreachable after swap. But if any
  platform-layer callback (e.g., gRPC completion handlers) holds a reference to old
  application objects, the old classloader leaks. Dev-only feature — acceptable for
  short-lived dev sessions.
- **Debugging**: Stack traces mix old and new class versions. Breakpoints may not
  survive reload. **Acceptable for dev-only feature.**
- **JNI/FFM interactions**: JustSearch code has zero `native` method declarations.
  All native interaction is via FFM or third-party Java APIs. **Not a risk** — the
  reloadable classes don't define native methods, don't load native libraries, and
  don't hold FFM handles directly. FFM handles are Java objects held by infrastructure
  classes that persist across reloads.
- **Intra-module split** (Approach A only): `EmbeddingService`, `SpladeEncoder`,
  `NerService` are in `indexer-worker` but hold ORT sessions. For Approach A, they
  must stay in the platform classloader (class-level split, not module-level). Not
  relevant for Approach B (JBR redefines classes in-place, no classloader change).

## Relationship to tempdoc 304 (Worker Persistence)

These approaches are complementary:
- **304 (Worker Persistence)**: Worker survives Head restart. Eliminates Worker startup
  for Head-only changes. Does NOT help when Worker code changes.
- **305 (Hot-Reload)**: Worker stays running and reloads changed classes. Eliminates
  Worker restart for Worker code changes. More complex but broader coverage.

The ideal combination: Worker persists across Head restarts (304) AND hot-reloads
application-layer classes when Worker code changes (305). Full restart only needed
for infrastructure changes (ORT version upgrade, Lucene version bump, gRPC proto
change).

## Overall assessment (2026-03-15, updated)

**Three phases**, each building on the previous:

- **Phase 0 (standard HotSwap on Temurin)**: Zero JDK switch. Limited to method-body
  changes. `JUSTSEARCH_DEV_DEBUG_PORT` enables JDWP; `HotSwapPush.java` pushes changed
  classes without an IDE. **Complete and verified (2026-03-15).**
- **Phase 1 / Approach B (JBR + enhanced HotSwap)**: Zero application code changes,
  full structural change support. Requires JBR as dev JDK.
  `JUSTSEARCH_DEV_JDK` + `JUSTSEARCH_DEV_HOTSWAP=enhanced` wires all flags
  and prepends `build/classes/java/main` to classpath. JNI risk is effectively zero.
  HotswapAgent auto-reload does NOT work (classes load from JARs, not classes dir);
  `HotSwapPush.java` is the required trigger. **Complete and verified (2026-03-15).
  The value of JBR is accepting structural changes — not auto-detection.**
- **Phase 2 (service restart on signal)**: **Complete and verified (2026-03-16).**
  `DevReloadManager` reconstructs `DefaultWorkerAppServices`, re-wires models, swaps
  gRPC delegates. **15ms reload time**, ~2–3s total dev iteration.

### Phase 2 implementation results (2026-03-16)

**Design evolution:** The original plan called for a child-first `URLClassLoader` to
load updated `worker-services` classes, shadowing the stale JAR on the system classpath.
Implementation revealed a JVM `LinkageError`: the system CL already defined the same
classes from the JAR, and the JVM's loader constraint mechanism rejects duplicate
definitions in the same constraint chain. Excluding the JAR from the system classpath
was impractical (Gradle's `runHeadless` always includes all modules).

**Simplified design:** Phase 2 reconstructs services from the same classloader instead.
Class bytecode updates come from Phase 1 (JBR + HotSwapPush). Phase 2 handles lifecycle
restart — constructors, static initializers, and field defaults are re-evaluated with
updated code. This works because HotSwap updates method definitions in-place, and the
service reconstruction creates fresh objects that pick up updated code paths.

**Prerequisites (tempdoc 308, all done):**
- Three-module split: `worker-core` / `worker-services` / `indexer-worker`
- `WorkerAppServices` interface + `InfraContext` record + `DefaultWorkerAppServices`
- Delegating gRPC wrappers (delegate types changed to `*ImplBase` for safety)

**Phase 2 code changes:**
- `DevReloadManager.java` (new, ~120 LOC): CAS-guarded reload sequence
- `KnowledgeServer.java`: sentinel check, DevReloadManager init, package-private fields
- `MmfWorkerSignalLayoutV1.java`: `OFFSET_RELOAD_SIGNAL = 29`
- `WorkerSignalBus.java` + implementations: `isReloadRequested()`, `clearReloadSignal()`
- `EnvRegistry.java`: `DEV_HOTRELOAD`, `DEV_HOTRELOAD_CLASSES_DIR`
- `WorkerSpawner.java`: `addDevHotReloadFlags()` via EnvRegistry
- `worker-services/build.gradle.kts`: `compileJava doLast` signal task

**Measured performance:**
| Metric | Value |
|--------|-------|
| Service reconstruction | **15ms** |
| Gradle incremental compile | 0.9–1.9s |
| Signal detection latency | ≤1s (sentinel polls) |
| **Total dev iteration** | **~2–3s** |

**Verification (2026-03-16):**

1. **Full test suite**: 1263 tests across 6 changed modules — all pass. 2 pre-existing
   model-discovery failures (unrelated: `SpladeModelDiscoveryTest`,
   `EmbeddingOnnxModelDiscoveryTest` — path resolution against stale repo root).
2. **Normal-mode dev stack**: Started via MCP `justsearch_dev_start` without hot-reload.
   Head READY, Worker READY. 5194 indexed documents, 100% embedding/SPLADE coverage.
   Search pipeline fully functional (cross-encoder 494ms, branch fusion CC, chunk merge,
   query classification INFORMATIONAL). No regressions vs baseline.
3. **Hot-reload dev stack**: Started with `JUSTSEARCH_DEV_HOTRELOAD=true`. JDWP agent
   auto-enabled on port 5005. `DevReloadManager initialized` in Worker log.
4. **End-to-end reload chain**: Edited `SearchOrchestrator`, ran Gradle compile (1s).
   `HotSwapPush` found 9 changed files, redefined 2 classes. MMF signal written.
   Sentinel detected within 1s. Services reconstructed in 16–20ms. Two reload cycles
   confirmed repeatable.
5. **No broken functionality**: Delegating wrapper simplification (non-RPC forwards
   removed) has no callers — all model wiring goes through `WorkerAppServices`. Sentinel
   GPU callback routed through `appServices.onMainClaimedGpu()` instead of wrapper.

### Phase 2 bytecode push: automatic HotSwapPush chaining — done

The Gradle `compileJava doLast` now chains: (1) `HotSwapPush.java` via `ProcessBuilder`
to push updated bytecode to the running Worker's JDWP agent, then (2) MMF signal write
to trigger service reconstruction. `WorkerSpawner.addDevHotReloadFlags()` auto-enables
the JDWP agent (default port 5005, configurable via `JUSTSEARCH_DEV_DEBUG_PORT`).

Full auto-reload chain: `./gradlew -t :modules:worker-services:classes` → compile →
HotSwapPush (bytecode update) → MMF signal → sentinel detect → service restart.

**Classloader approach abandoned:** Child-first `URLClassLoader` hit JVM `LinkageError`
— the system CL already loaded `worker-services` classes from the JAR, and the JVM's
loader constraint mechanism rejects duplicate definitions. Excluding the JAR from the
system classpath was impractical (`runHeadless` always includes all modules).
HotSwap-based bytecode push is simpler and more robust.

### Favorable architectural properties (still valid)

1. **Volatile setter pattern already exists** — tempdoc 302 established the pattern of
   constructing services with null ML references, then wiring them via volatile setters.
2. **All application classes are stateless per-request** — no accumulated runtime state
   to preserve across reloads.
3. **IndexingLoop has clean lifecycle** — `close()` + constructor + `start()` cycle.
4. **No JPMS modules** — classpath-based loading, no module boundaries to work around.
5. **MMF signal bus has reserved space** — reload signal fits naturally at offset 29.
6. **Gradle incremental compile is fast** — 1.9s for a single-file change.
7. **Lucene Codec/Analyzer constraint** — `adapters-lucene` must stay in platform
   classloader (Codec SPI + Analyzer cross-classloader refs — see Q7).
8. **All 29 Worker-side gRPC RPCs are unary** — delegating wrappers are trivial
   one-line forwards.

## Remaining questions

- [x] ~~Full classpath split analysis (Q4) — exact JAR categorization~~ (answered: 168 JARs,
  10 application / 108 platform / 50 ambiguous)
- [x] ~~DCEVM / JBR / HotswapAgent availability for JDK 25 (Q5)~~ (answered: JBR 25
  available, HotswapAgent 2.0.3 supports JDK 25, CRaC Linux-only)
- [x] ~~Does JBR + HotswapAgent work with ONNX Runtime JNI + Lucene MMap?~~ (answered:
  JNI risk is effectively zero — JustSearch code has no `native` methods, all native
  interaction is via FFM or third-party Java APIs that are never modified during dev)
- [x] ~~How does `Analyzer` and `Codec` classloading interact with the classloader split?~~
  (answered: two hard problems — see Q7 below. `adapters-lucene` must stay in platform
  classloader. Only relevant for Approach A.)
- [x] ~~Can the delegating gRPC service pattern be generated from the proto definitions
  instead of hand-written?~~ (answered: feasible via Gradle source-gen task, but for 29
  Worker-side RPC methods across 3 services, hand-writing ~150 lines is simpler. See Q8.)
- [x] ~~Should the reload signal be in MMF (cross-process) or a simpler mechanism like
  a file watcher on the `classes` output directory?~~ (answered: MMF at offset 29, not
  file-watcher. Offset 25 is taken by `MmfWorkerSignalHeaderV1`. See Q9.)

### Q7: Analyzer and Codec classloading interaction with classloader split

**Answer: Two hard problems. `adapters-lucene` must stay in the platform classloader.**

**Problem 1 — Codec SPI breaks on reload.** `JustSearchCodec` (in `adapters-lucene`)
is registered via `META-INF/services/org.apache.lucene.codecs.Codec`. Lucene's
`NamedSPILoader` scans this during class initialization in the platform classloader.
After an application classloader reload, Lucene can't find `JustSearchCodec` when
reading existing segments — throws `IllegalArgumentException`. The codebase already
documents this pain point in `VectorFormatDetector.java` (lines 15-18, 95-98).

**Problem 2 — Analyzer cross-classloader reference.** `SsotAnalyzerRegistry` (in
`adapters-lucene`) creates anonymous `Analyzer` subclasses (line 155-166) injected
into `IndexWriterConfig` (platform class) via `ComponentsFactory.java` line 163. The
`IndexWriter` holds a stale classloader reference after reload.

**Implication**: `adapters-lucene` must stay in the platform classloader. This is
consistent with the original Q1/Q4 classification — the reloadable surface is limited
to business logic classes (`SearchOrchestrator`, `GrpcSearchService`, `GrpcIngestService`,
`IndexingLoop` logic, pipeline stages), not Lucene infrastructure. No classloader
boundary exists between Lucene JARs and `adapters-lucene`.

**Other SPI usages** (not problematic):
- `ai-bridge/BackendRegistry.java`: `ServiceLoader.load(BackendProvider.class)` — internal
  SPI, both sides stay in platform classloader
- `ai-bridge/EmbeddingProviderRegistry.java`: `ServiceLoader.load(EmbeddingProvider.class)`
  — same, internal SPI
- No thread context classloader manipulation in Lucene-related code paths

### Q8: gRPC delegation — generation vs hand-written

**Answer: Hand-writing is simpler for the current scale. Generation is feasible if
needed later.**

Worker-side gRPC services requiring delegation:
- `SearchService` — 9 RPC methods (all unary)
- `IngestService` — 19 RPC methods (all unary)
- `HealthService` — 1 RPC method (unary)
- Total: **29 methods across 3 services**

All methods follow a uniform signature: `void name(ReqType, StreamObserver<RespType>)`.
Delegation is a single-line forward: `delegate.name(request, responseObserver)`.

**Generation options assessed**:
- **Gradle source-gen task (A1)**: Parse compiled `*ImplBase` bytecode or generated
  `*Grpc.java` source. Feasible — predictable structure, existing `com.google.protobuf`
  plugin in `ipc-common/build.gradle.kts`. Adds build complexity.
- **Custom protoc plugin (A2)**: Cleanest long-term but adds a binary artifact.
- **Java dynamic proxy**: Won't work — `ImplBase` is an abstract class, not an interface.
- **Byte Buddy runtime subclassing**: Possible but the delegating class must live in the
  platform classloader, creating a classloader hierarchy problem.

**Recommendation**: Hand-write the 3 delegating services (~150 lines total of mechanical
code). 29 one-line delegation methods are straightforward and rarely change (proto
evolution is infrequent). Revisit generation if the proto surface grows significantly.

### Q9: Reload signal mechanism — MMF vs file-watcher

**Answer: MMF signal at offset 29. Not file-watcher.**

**Critical correction**: Offset 25 is **not free**. `MmfWorkerSignalHeaderV1` occupies
offsets 25-28 (magic=25-26, version=27, flags=28). First free byte: **offset 29**.

| Factor | MMF (offset 29) | File-watcher |
|--------|-----------------|--------------|
| New dependencies | None | `directory-watcher` in `indexer-worker` |
| Debounce needed | No (single atomic byte) | Yes (Gradle writes many `.class` files) |
| Dir must exist at start | N/A | Yes (`build/classes/` after first compile) |
| Latency | ≤1s (sentinel poll) | ~500ms debounce + watcher |
| Code complexity | ~20 lines | ~80 lines (watcher, debounce, lazy init) |
| Trigger mechanism | Gradle `exec` task writes byte | Worker self-detects |

**MMF workflow**: `./gradlew -t :modules:indexer-worker:classes` → on success, a
`finalizedBy` task writes `(byte) 1` to offset 29 of the MMF → sentinel reads within
1s → triggers reload → Worker clears the byte back to 0.

**Why not file-watcher**:
- Adds `directory-watcher` to production `indexer-worker` classpath for a dev-only feature
- Debouncing is non-trivial: Gradle writes dozens of `.class` files per compile; a
  300-500ms quiet period is needed to avoid mid-compile reload (class version mismatch)
- `build/classes/java/main/` only exists after first compile — requires lazy init
- Windows `ReadDirectoryChangesW` buffer can overflow on large compiles (`OVERFLOW` event)

**Note on `MainSignalBus`**: Adding `writeReload()` to `MainSignalBus` is optional. An
external script (Gradle `exec` task) can write the byte directly to the MMF file via
`RandomAccessFile` without Head-side code changes. Head-side wiring only needed if a
REST endpoint (`/api/dev/reload`) is desired.

## Implementation items

### Phase 0: Validate standard HotSwap on Temurin (~30 min)

Test whether built-in JDK HotSwap (method-body replacement only) covers the common
case. If 80% of dev changes are method body edits, this may be sufficient with zero
setup and no JDK switch.

- [x] 1. ~~Start Worker with JDWP debug agent~~ — Wired via `JUSTSEARCH_DEV_DEBUG_PORT`
  env var / `justsearch.dev.debug.port` sysprop in `WorkerSpawner.addDevHotReloadFlags()`.
  Binds to `127.0.0.1:<port>` (loopback only). Port is validated (1-65535).
  Usage: `set JUSTSEARCH_DEV_DEBUG_PORT=5005` before starting the dev stack.
- [x] 2. ~~CLI hot-swap trigger tool~~ — `scripts/dev/HotSwapPush.java` (JDI-based,
  single-file source program). Connects to the JDWP agent, finds changed `.class` files
  since last push (via marker file), and redefines them. No IDE required.
  Usage: `java --add-modules jdk.jdi scripts/dev/HotSwapPush.java 5005
  modules/indexer-worker/build/classes/java/main`
- [x] 3. ~~Verified (2026-03-15)~~: Added `log.info("HOTSWAP-VERIFY-V1")` to
  `SearchOrchestrator.execute()`, recompiled, pushed via `HotSwapPush.java` (94 classes
  redefined), triggered search — marker appeared in Worker log. Full chain works.
  **Note**: `gradlew.bat` env var propagation requires `export` or `--no-daemon` on
  Windows (Gradle Daemon caches env vars from first launch).
- [x] 4. Phase 0 works for method-body changes. Phase 1 needed for structural changes
  (new methods/fields) which are common during development.

### Phase 1: JBR + enhanced HotSwap (~1-2 hours)

If Phase 0 is too limiting (rejects structural changes like new methods/fields):

- [x] 1. ~~Download JBR 25 Windows build~~ — Downloaded and verified (items 4-6 below
  confirm JBR structural HotSwap works). Source: `jbr-25.0.2-windows-x64-b329.72.zip`
  from `github.com/JetBrains/JetBrainsRuntime/releases`.
- [x] 1b. ~~Download HotswapAgent 2.0.3~~ — Downloaded and placed at
  `<JBR_HOME>/lib/hotswap/hotswap-agent.jar`. Auto-reload not working (see item 6),
  but agent loads successfully.
- [x] 2. ~~Configure dev-runner to use JBR when launching the Worker~~ — Wired via
  `JUSTSEARCH_DEV_JDK` env var in `WorkerSpawner.buildCommand()`. AOT cache is
  automatically skipped when a dev JDK override is set.
- [x] 3. ~~Add enhanced HotSwap + HotswapAgent JVM flags~~ — Wired via
  `JUSTSEARCH_DEV_HOTSWAP=enhanced` in `WorkerSpawner.addDevHotReloadFlags()`.
  Adds both `-XX:+AllowEnhancedClassRedefinition` and `-XX:HotswapAgent=fatjar`.
  Also prepends `build/classes/java/main` to Worker classpath so HotswapAgent
  detects file changes from `./gradlew -t classes`. Warns if `JUSTSEARCH_DEV_JDK`
  is not set (Temurin doesn't support these flags).
- [x] 4. ~~Verified (2026-03-15)~~: Added new method `hotswapVerifyMarker()` to
  `SearchOrchestrator` + call from `execute()` (structural change: new method + new
  call site). `HotSwapPush.java` redefined 76 classes including the structural change.
  Search request returned `marker=HOTSWAP-STRUCTURAL-V1` in Worker log. **JBR accepts
  structural changes that Temurin rejects.**
- [x] 5. ~~Verified (2026-03-15)~~: Hot-swapped `IndexingLoop.extractJob()` while the
  indexing loop was actively processing documents. The new code (`HOTSWAP-EXTRACT-V1`
  marker) appeared in the Worker log for the next documents processed.
  **Important nuance**: Methods *called by* the loop (`extractJob()`, pipeline stages)
  pick up hot-swapped changes immediately on next invocation. Code directly inside the
  `run()` loop body does NOT update because the `run()` method frame is already on the
  call stack and never returns. This is standard JVM behavior — hot-swap replaces method
  definitions but not active stack frames.
- [x] 6. ~~HotswapAgent auto-reload: NOT working (2026-03-15)~~. The Worker loads classes
  from JARs (`lib/*`), not from the prepended classes directory. HotswapAgent only
  watches the classloader source that originally loaded each class. Since classes come
  from JARs, changes in the classes directory are not detected. **`HotSwapPush.java` is
  the required trigger for both Phase 0 and Phase 1.** The value of Phase 1 (JBR) is
  accepting structural changes — not auto-detection.
  Future improvement: configure HotswapAgent's `watchResources`/`extraClasspath` in
  `hotswap-agent.properties`, or investigate JBR's `autoHotswap` flag.

### Phase 2: Service restart on signal — done

Prerequisites resolved by tempdoc 308. Implementation simplified from the original
classloader restart design: JVM `LinkageError` prevents loading `worker-services`
classes from both the system CL (JAR) and a child CL simultaneously. Instead, Phase 2
uses direct service reconstruction — `new DefaultWorkerAppServices(infraCtx)` — from
the same classloader. Class bytecode updates come from Phase 1 (JBR + HotSwapPush).
Phase 2 handles the lifecycle restart: stopping/reconstructing services so constructors,
static initializers, and field defaults are re-evaluated with updated code.

- [x] 1. ~~Add `OFFSET_RELOAD_SIGNAL = 29`~~ to `MmfWorkerSignalLayoutV1`.
- [x] 2. ~~Add `isReloadRequested()` + `clearReloadSignal()`~~ to `WorkerSignalBus`
  interface + `MmfWorkerSignalBus` + `MockWorkerSignalBus`.
- [x] 3. ~~Delegating gRPC wrappers~~ — Done by tempdoc 308. Delegate types changed
  to `*ImplBase` for cross-classloader safety. Non-RPC forwards removed (routed
  through `WorkerAppServices` instead).
- [x] 4. ~~`DevReloadManager`~~ in `indexer-worker` (~120 LOC). On reload signal:
  CAS guard → clear signal → await deferred model init → close old `appServices`
  (stops IndexingLoop) → construct new `DefaultWorkerAppServices(infraCtx)` → re-wire
  models → swap delegating wrapper delegates → start new IndexingLoop. **15ms measured.**
- [x] 5. ~~Sentinel thread reload check~~ after `shouldDie()` in KnowledgeServer.
- [x] 6. ~~Gradle signal task~~ — `compileJava doLast` in `worker-services/build.gradle.kts`.
  Writes `(byte) 1` to offset 29 of MMF file. Gated by `JUSTSEARCH_DEV_HOTRELOAD=true`.
- [x] 7. ~~System property gate~~ — `EnvRegistry.DEV_HOTRELOAD` + `DEV_HOTRELOAD_CLASSES_DIR`.
  `WorkerSpawner.addDevHotReloadFlags()` passes sysprops to Worker.
- [x] 8. ~~Measured (2026-03-16)~~: reload completes in **15ms**. Full cycle: Gradle
  compile (0.9–1.9s) + signal write (instant) + sentinel detect (≤1s) + service
  reconstruction (15ms) = **~2–3s total**.
- [x] 9. ~~Edge cases~~: concurrent reloads handled by `AtomicBoolean` CAS guard.
  Deferred init awaited via `deferredModelInit.get(30s)`. Old IndexingLoop thread
  exits on next `running.get()` check — shared infrastructure is thread-safe.

## Prior art

- JetBrains Runtime (JBR) — DCEVM-like enhanced HotSwap built into JDK distribution
- HotswapAgent — open-source hot-reload agent, works with JBR
- JRebel — commercial hot-reload agent (supports JDK 25 since v2025.4.0)
- Spring DevTools — application classloader restart (~1-2s)
- Quarkus Dev Mode — live coding with background compilation
- Play Framework — automatic recompilation and classloader swap
- Gradle Daemon — JVM persistence across builds (not class reload)
- Compose Hot Reload 1.0.0 (January 2026) — JBR-based, Compose Multiplatform-specific
- CRaC (Coordinated Restore at Checkpoint) — fast restart via snapshot/restore (Linux only)
- Project Leyden — AOT cache for production startup optimization (JEPs 514, 515)
