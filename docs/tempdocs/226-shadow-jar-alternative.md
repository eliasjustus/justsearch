---
title: "Shadow JAR Alternative Evaluation"
type: tempdoc
status: done
created: 2026-02-20
updated: 2026-02-22
parent: null
---

# 226. Shadow JAR Alternative Evaluation

The Worker process (`indexer-worker`) is packaged as a 220 MB custom fat JAR via the
`conventions.fat-jar` convention plugin. The Head process spawns it with `java -jar worker.jar`. This tempdoc
evaluates whether an alternative packaging strategy would reduce build times, eliminate fat JAR
pain points, and simplify the build.

## Current state

### How it works today

| Aspect | Detail |
|--------|--------|
| **Plugin** | `conventions.fat-jar` convention plugin (`build-logic/.../FatJarConventionsPlugin.kt`) |
| **Implementation** | Custom Gradle `Jar` task (NOT `com.gradleup.shadow` — no third-party plugin involved) |
| **Output** | `indexer-worker-2.0.0-SNAPSHOT-all.jar` (220 MB) |
| **Entry point** | `io.justsearch.indexerworker.IndexerWorker` |
| **Service merging** | Custom `MergeServicesTask` for `META-INF/services/**` |
| **Relocations** | None |
| **Exclusions** | `META-INF/*.SF`, `*.DSA`, `*.RSA` (certificate files) |
| **Duplicates** | `EXCLUDE` strategy (first occurrence wins) |
| **Build wiring** | `shadowJar` task registered by plugin and wired into `assemble`; `runHeadless` depends on `shadowJar` |
| **Production bundle** | Copied to `lib/worker.jar` by Tauri sidecar bundle task |

> **Important correction from original tempdoc:** The fat JAR is produced by a custom
> `FatJarConventionsPlugin.kt` using Gradle's built-in `Jar` task. No third-party shadow plugin
> (e.g. `com.gradleup.shadow`) is a dependency of `build-logic`. The task is named `shadowJar`
> for historical reasons, but the implementation is entirely internal.

### How the Worker is launched

- **`WorkerSpawner.java`** (`app-services`): Builds a `ProcessBuilder` command with `java -jar <path>`
- **`KnowledgeServerConfig.java`** (`app-services`): Resolves JAR path via record field `workerJarPath`
  - Production: `libDir.resolve("worker.jar")`
  - Development: scans `modules/indexer-worker/build/libs/` for `-all.jar`
- **`WorkerProcessManager.java`** (`system-tests`): Already implements **two** launch modes:
  - JAR mode: `WorkerProcessManager(Path workerJarPath, …)` — current production path used by most system tests
  - Distribution mode: `fromDistribution()` / `fromDistributionNoConfig()` — already used by `WindowsTortureTest`, `ReadWhileWriteTest`, `SoakSuiteTest`
  - Distribution mode uses `createJavaWithArgfileProcessBuilder()` which builds `-cp <all jars in lib/>` via an argfile to avoid Windows command-line length limits

### Known pain points (historical and current)

1. **Stale JAR** (resolved): Previously required manual rebuild. Now wired to `assemble`.
2. **Build time**: Fat JAR repacking (unzip + merge + rezip 220 MB) adds significant time to every incremental build.
3. **220 MB artifact**: Entire dependency tree re-packed into a single file on every change.
4. **`META-INF/services` merging**: Custom `MergeServicesTask` needed because merging all input JARs into one file collapses service descriptor entries. With `installDist`, each JAR retains its own services entries — ServiceLoader's `ClassLoader.getResources()` discovers all of them naturally.
5. **Duplicate class risk**: `EXCLUDE` strategy silently drops duplicates — no visibility into conflicts.
6. **No class relocations**: If Worker and Head ever share a classloader (tests, future embedding), version conflicts are possible.

---

## Full touch-point inventory

Codebase investigation reveals **25+ files** need updating — significantly more than the original
7-item plan. All items are listed here so Phases 2 and 3 can be executed completely.

### Build system (Phase 2)

| File | Current reference | Required change |
|------|-------------------|-----------------|
| `modules/indexer-worker/build.gradle.kts` | `id("conventions.fat-jar")` | Remove plugin application |
| `modules/ui/build.gradle.kts` | `bundleSidecarResources` depends on `shadowJar` | Depend on `installDist`; copy `lib/` dir to `lib/worker/` |
| `modules/ui/build.gradle.kts` | `smokeSidecarBundle` checks `lib/worker.jar` | Check `lib/worker/` directory instead |
| `modules/ui/build.gradle.kts` | `runHeadless` depends on `shadowJar` | Depend on `installDist` |
| `modules/ui/build.gradle.kts` | `runHeadlessWithProfiling` depends on `shadowJar` | Depend on `installDist` |
| `build.gradle.kts` (root) | `prepareTests` depends on `shadowJar` | Remove `shadowJar` dep (already also depends on `installDist`) |
| `build.gradle.kts` (root) | AppCDS tasks (`createWorkerRuntime`, `generateWorkerAppCDS`, `buildWorkerDist`) | Migrate to Leyden AOT cache in Phase 4 |
| `modules/system-tests/build.gradle.kts` | `systemProperty("justsearch.worker.jar", …"-all.jar")` ×2 | Replace with `systemProperty("justsearch.worker.dist.dir", …"install/indexer-worker")` |
| `modules/app-services/build.gradle.kts` | Already uses `installDist` for integrationTest | **No change needed** |

### Application code (Phase 2)

| File | Current reference | Required change |
|------|-------------------|-----------------|
| `WorkerSpawner.java` | `buildCommand()`: `-jar <workerJarPath>` | `-cp <workerLibDir>/* io.justsearch.indexerworker.IndexerWorker` |
| `KnowledgeServerConfig.java` | Record field `workerJarPath`; `resolveWorkerJar()` | Rename to `workerLibDir`; `resolveWorkerLib()` — prod: `libDir.resolve("worker")`, dev: `build/install/indexer-worker/lib/` |
| `HeadlessApp.java` | Error message: "gradlew :modules:indexer-worker:shadowJar" | Update to reference `installDist` |

### Integration tests (Phase 2)

| File | Current reference | Required change |
|------|-------------------|-----------------|
| `KnowledgeServerIntegrationTest.java` | `config.workerJarPath()` ×7; existence guard | Update to `config.workerLibDir()` |
| `RichDocumentIntegrationTest.java` | `config.workerJarPath()` ×3; existence guard | Update to `config.workerLibDir()` |
| `SchemaMismatchStatusContractTest.java` | `Files.exists(config.workerJarPath())` | Update to check `config.workerLibDir()` dir |

### System tests (Phase 2)

| File | Current reference | Required change |
|------|-------------------|-----------------|
| `TestEnvironmentProvisioner.java` | `workerJarPath` field; `getWorkerJarPath()`; reads `justsearch.worker.jar` sysprop | Rename to `workerDistDir`; `getWorkerDistDir()`; reads `justsearch.worker.dist.dir` |
| `VduBatchProcessorE2ETest.java` | `new WorkerProcessManager(env.getWorkerJarPath(), …)` | `WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), …)` |
| `VduRecoverySystemTest.java` | Same | Same |
| `WorkerSpawnTest.java` | Same | Same |
| `SyncDirectoryIntegrationTest.java` | Same | Same |
| `SwitchingFenceBufferingE2ETest.java` | Same | Same |
| `RollbackE2ETest.java` | Same | Same |
| `PauseResumeMigrationE2ETest.java` | Same | Same |
| `MigrationControlE2ETest.java` | Same | Same |
| `IndexBasePathLockE2ETest.java` | Same | Same |
| `GrpcDataIntegrationTest.java` | Same | Same |
| `GrpcCommunicationTest.java` | Same | Same |
| `CompleteIndexingWorkflowE2ETest.java` | Same | Same |
| `SummarizationPipelineE2ETest.java` | Same | Same |

### Documentation and config (Phase 3)

| File | Reference | Change |
|------|-----------|--------|
| `docs/reference/contributing/agent-guide.md` | `shadowJar` ×2 (build commands) | Update to `installDist` |
| `docs/reference/contributing/mcp-dev-tools.md` | `shadowJar` ×3 | Update to `installDist` |
| `docs/how-to/use-ui.md` | `shadowJar` ×2 | Update to `installDist` |
| `docs/explanation/12-desktop-installer-and-sandbox-setup.md` | Bundle layout description | Update for `lib/worker/` directory |
| `CLAUDE.md` | "Stale Worker JAR" pitfall row (references `shadowJar`) | Update wording |

### Build-logic cleanup (Phase 3)

| File | Action |
|------|--------|
| `build-logic/src/main/kotlin/conventions/FatJarConventionsPlugin.kt` | Delete |
| `build-logic/src/main/kotlin/conventions/MergeServicesTask.kt` | Delete |
| `build-logic/build.gradle.kts` | Remove `fatJarConventions` plugin registration block (no third-party dep — just the registration block) |

---

## Alternatives to evaluate

### A. Application plugin distribution (`installDist`) — RECOMMENDED

Replace fat JAR with Gradle's built-in `application` plugin, which produces a `lib/` directory
with individual JARs and a launch script.

**How it would work:**
- Worker's `build.gradle.kts` already applies `application` (for `mainClass`) and has
  `distributions { main { distributionBaseName.set("indexer-worker") } }`. `installDist` already
  works today — it just isn't wired as the primary launch mechanism.
- `installDist` produces `build/install/indexer-worker/lib/*.jar`.
- `WorkerSpawner` changes from `java -jar worker.jar` to:
  ```
  java -cp "<workerLibDir>/*" io.justsearch.indexerworker.IndexerWorker
  ```
- The JVM launcher (`java.exe`) expands the `*` wildcard itself — NOT the shell. This works
  correctly with `ProcessBuilder` since Java 8+. Only `.jar` files directly in the named directory
  are matched; subdirectories are not traversed.

**Proposed production bundle layout:**
```
lib/
  ui-headless.jar          ← Head process JAR (on Head's -cp)
  <head deps>/*.jar        ← Head dependencies (matched by lib/*)
  worker/                  ← Worker distribution (separate subdirectory — NOT matched by lib/*)
    indexer-worker-*.jar
    grpc-netty-shaded-*.jar
    lucene-core-*.jar
    … (all Worker runtime JARs)
```
The Head process is launched with `-cp ui-headless.jar;lib\*`. Because `lib\*` only matches JARs
**directly** in `lib/`, the `lib/worker/` subdirectory is invisible to the Head's classloader.
Worker JARs are cleanly isolated — no classloader conflict is possible even if Head and Worker
carry the same version of a shared library.

**ServiceLoader behavior (research-confirmed):**
`installDist` works *better* than the fat JAR for ServiceLoader. Each JAR retains its own
`META-INF/services/` files. `ClassLoader.getResources("META-INF/services/…")` discovers all of
them across all JARs in the classpath. The fat JAR is the pathological case — merging is required
only because all `META-INF/services/` entries must coexist in a single archive entry. gRPC, SLF4J,
and Tika all use ServiceLoader and are confirmed to work correctly with `installDist`.

**Incremental build behavior (research-confirmed):**
`installDist` uses a `Sync` task that re-syncs all JARs when any input changes. It is not a true
delta-copier. However, it eliminates the expensive 220 MB repack computation (unzip all input JARs
→ merge → rezip). Net build time improvement is expected to be substantial even without true
incremental copying.

**AppCDS implication:**
The existing `base.jsa` (if present) is silently ignored under `-Xshare:auto` when the launch
form changes from `-jar` to `-cp`. The JSA must be regenerated after migration, or superseded by
Project Leyden AOT cache (see Option E). This is not a blocker for Phase 2.

**Pros:**
- No repacking step — eliminates the 220 MB repack computation on every build.
- `META-INF/services` works natively — `MergeServicesTask` can be deleted.
- No duplicate class ambiguity.
- Standard Gradle — no third-party plugin.
- `WorkerProcessManager` in system tests already implements distribution mode via `fromDistribution()` — only test provisioning infrastructure needs updating.
- Classpath isolation: `lib/worker/*.jar` is invisible to the Head's `-cp lib/*`.

**Cons:**
- Production bundle is a directory tree instead of a single file.
- Tauri sidecar bundle task needs to copy `lib/worker/` directory instead of one `lib/worker.jar`.
- More files to manage in distribution.
- `KnowledgeServerConfig` resolution logic changes (record field rename + path update).
- 25+ files need updating across build system, application code, tests, and docs.

**Evaluation checklist:**
- [ ] Measure current fat JAR build time (clean and incremental) — *skipped; decision made via code inspection*
- [ ] Measure `installDist` build time for comparison — *skipped; decision made via code inspection*
- [ ] Compare output sizes (single JAR vs `lib/` directory total) — *skipped; decision made via code inspection*
- [x] Prototype `WorkerSpawner` change: `-cp lib/worker/*` launch — *done as part of Phase 2*
- [x] Verify `META-INF/services` works correctly (gRPC, SLF4J, Tika parsers) — *confirmed by research*
- [x] Verify Tauri sidecar bundle handles directory-based Worker — *done; `bundleSidecarResources` updated*
- [x] Verify `KnowledgeServerConfig` resolution works in dev and production — *done; record field renamed + paths updated*
- [x] Verify `runHeadless` dev workflow still works — *done; `runHeadless` wired to `installDist`*
- [ ] Run full system test suite with the change — *not yet run*
- [x] Verify AppCDS `base.jsa` behavior (silently ignored — regeneration or Leyden migration needed) — *confirmed by research; no code change needed*

---

### B. Classpath launch (no packaging)

Head spawns Worker with an explicit classpath constructed from Gradle's runtime dependency
resolution. No packaging step at all.

**How it would work:**
- At build time, write a classpath manifest file listing all Worker runtime JARs.
- `WorkerSpawner` reads the manifest and constructs `-cp <jar1>:<jar2>:...`.

**Pros:**
- Zero packaging overhead.
- Instant incremental rebuilds (just recompile changed classes).

**Cons:**
- Only works in development — production still needs packaging.
- Classpath can exceed Windows command-line length limit (8191 chars) with many JARs.
- Two code paths (dev vs prod) adds complexity.

**Verdict:** Too complex for marginal gain over Option A. Skip unless A fails.

---

### C. jlink custom runtime image

Use Java's `jlink` tool to create a minimal JVM + application image.

**Pros:**
- Smallest possible distribution (only required JVM modules).
- Fast startup.

**Cons:**
- Hard-blocked by `io.grpc:grpc-netty-shaded` (automatic module — no `module-info.java`).
- Hard-blocked by Apache Tika (no `module-info.java`).
- Full JPMS modularization of Worker and all dependencies would be required.
- Enormous migration effort for marginal packaging benefit.

**Verdict (research-confirmed):** Not worth the effort. The gRPC shaded Netty dependency alone
makes this infeasible without forking the library. Revisit only if distribution size becomes a
critical constraint AND upstream libraries gain proper `module-info.java`.

---

### D. GraalVM native image

Compile Worker to a native binary.

**Pros:**
- Instant startup, low memory footprint, single binary.

**Cons:**
- Hard-blocked by `--add-modules=jdk.incubator.vector` (incubator modules not supported in native image).
- Tika's dynamic `ServiceLoader`-based parser architecture requires extensive reflection configuration.
- gRPC + Netty native image support is fragile (known upstream issues).
- Long compile times (minutes per build).
- Platform-specific binaries (separate builds per OS).
- Debugging is significantly harder.

**Verdict (research-confirmed):** Not viable for this codebase. The Vector API incubator module
dependency is a hard blocker independent of all other concerns. Revisit when incubator modules are
finalized and GraalVM ecosystem support matures.

---

### E. Project Leyden AOT cache (additive post-migration step)

Use JDK 25's AOT class loading and linking cache (JEP 483 / JEP 514 / JEP 515) as an additive
improvement after migrating to `installDist`. This supersedes the existing `base.jsa` AppCDS workflow.

**How it would work:**
- After `installDist` migration, add a training run step during the build:
  ```
  java -XX:AOTCacheOutput=worker.aotcache -cp "lib/worker/*" io.justsearch.indexerworker.IndexerWorker --train
  ```
- Production launch adds `-XX:AOTCache=worker.aotcache` to the JVM args in `WorkerSpawner`.
- The AOT cache uses an open-world model — the full JVM is present, dynamic class loading works.

**Pros:**
- No reflection configuration required (unlike GraalVM).
- Works with dynamic class loading (Tika, gRPC).
- Additive — does not change launch semantics or introduce new packaging constraints.
- JDK 25 (already targeted) qualifies.
- Superior to existing `base.jsa` AppCDS: open-world model, no `-jar` vs `-cp` invalidation issue.

**Cons:**
- Requires a training run step in the build/install workflow.
- Cache is platform-specific (per-OS build artifact).
- JEP 483/514/515 are in preview/incubating in JDK 24; expected stable in JDK 25.

**Verdict:** Viable additive step. Implement in Phase 4 after Phase 2 migration is stable and
validated. Does not block Phase 2.

---

## Recommendation

**Option A (`installDist`)** is the correct migration path. It solves the core problems (build
time, service merging, duplicate ambiguity) with minimal risk and no third-party dependencies.

**Option E (Project Leyden)** is the recommended follow-on, replacing the existing `base.jsa`
AppCDS workflow once Option A is stable.

The migration is more extensive than originally anticipated (25+ files vs. the original 7-item
list), but the logic is mechanical: rename `workerJarPath` → `workerLibDir` throughout, update
build task dependencies, and switch 12 system test constructors to the already-implemented
`fromDistribution()` factory method on `WorkerProcessManager`.

---

## Implementation plan

### Phase 1: Measurement and prototype (low risk)

> **Status**: Skipped — the 2026-02-20 investigation established sufficient confidence to proceed
> directly to Phase 2. Formal build-time measurements were not taken; the decision rests on
> structural analysis (220 MB repack vs. `Sync` task copying individual JARs).

- [ ] Measure current fat JAR build time (clean and incremental) — *skipped*
- [ ] Measure `installDist` build time for comparison — *skipped*
- [ ] Compare output sizes (single JAR vs `lib/` directory total) — *skipped*
- [x] Prototype `WorkerSpawner` change on a branch — *done as Phase 2 on branch `226-install-dist`*
- [ ] Run system tests with prototype — *not yet run*

### Phase 2: Migration (medium risk)

> **Implementation notes** (from deep codebase investigation, 2026-02-21):
>
> 1. **WorkerSpawner Windows wildcard**: `buildCommand()` uses `List<String>` — ProcessBuilder handles
>    quoting, no shell involved. The `-cp` wildcard **must be a plain `String`**, not a `Path` object
>    (Windows throws `InvalidPathException` on `Path.of("lib/*")`). Safe pattern:
>    `config.workerLibDir().toAbsolutePath().toString() + File.separator + "*"`. JVM launcher expands
>    `*` itself — documented Java 6+ behavior, works correctly with spaces in path.
>
> 2. **`fromDistribution()` uses scripts, not direct `-cp`**: `WorkerProcessManager.fromDistribution()`
>    resolves `distDir/bin/indexer-worker.bat` (Windows) and uses `createJavaWithArgfileProcessBuilder()`
>    which writes all JVM args to a `@argfile` then launches `java @argfile`. This is the test path.
>    `WorkerSpawner.java` (production path) uses `-cp` directly — these are separate code paths.
>
> 3. **`soakTest` uses plain JAR (not `-all.jar`)**: `system-tests/build.gradle.kts` has TWO different
>    sysprop values — `systemTest` uses `indexer-worker-<version>-all.jar`, `soakTest` uses
>    `indexer-worker-<version>.jar`. Both must be replaced with `justsearch.worker.dist.dir`.
>
> 4. **Env/sysprop override rename**: `KnowledgeServerConfig.resolveWorkerJar()` reads
>    `JUSTSEARCH_WORKER_JAR` / `justsearch.worker.jar` as an explicit override. Rename to
>    `JUSTSEARCH_WORKER_LIB_DIR` / `justsearch.worker.lib.dir` (value is now a directory, not a JAR).
>
> 5. **Integration test existence guards**: `Files.exists(config.workerJarPath())` →
>    `Files.isDirectory(config.workerLibDir())`.
>
> 6. **`bundleSidecarResources` is a `Sync::class` task**: current copy does
>    `from(workerShadowJar) { into("lib"); rename { "worker.jar" } }`. New logic: get `installDist`
>    task output, `from(<installDist lib dir>) { into("lib/worker") }`.
>
> 7. **AppCDS `base.jsa` needs no code change**: `buildCommand()` already adds `-XX:SharedArchiveFile=`
>    only if the file exists. After `-jar`→`-cp` migration it becomes a silent no-op until Phase 4.
>
> 8. **`applicationDefaultJvmArgs` duplication is harmless**: `--add-modules jdk.incubator.vector` and
>    `--sun-misc-unsafe-memory-access=warn` are in `applicationDefaultJvmArgs` (baked into
>    `bin/indexer-worker.bat`) and also added manually in `WorkerSpawner.java` — duplication is fine
>    for the `-cp` production launch path.

**Build system:**
- [x] `modules/indexer-worker/build.gradle.kts`: remove `id("conventions.fat-jar")`
- [x] `build.gradle.kts` (root): remove `shadowJar` dependency from `prepareTests` (keep `installDist` dep already present)
- [x] `modules/system-tests/build.gradle.kts`: replace both `justsearch.worker.jar` sysprops with `justsearch.worker.dist.dir` pointing to `build/install/indexer-worker`
- [x] `modules/ui/build.gradle.kts`: update `bundleSidecarResources` — depend on `installDist`, copy `lib/` to `lib/worker/`
- [x] `modules/ui/build.gradle.kts`: update `smokeSidecarBundle` — check `lib/worker/` directory existence
- [x] `modules/ui/build.gradle.kts`: update `runHeadless` and `runHeadlessWithProfiling` to depend on `installDist`

**Application code:**
- [x] `KnowledgeServerConfig.java`: rename record field `workerJarPath` → `workerLibDir`; update resolution — prod: `libDir.resolve("worker")`, dev: `build/install/indexer-worker/lib/`; env/sysprop renamed to `JUSTSEARCH_WORKER_LIB_DIR` / `justsearch.worker.lib.dir`
- [x] `WorkerSpawner.java`: update `buildCommand()` — `-cp <workerLibDir>/* io.justsearch.indexerworker.IndexerWorker`
- [x] `HeadlessApp.java`: update error message referencing `shadowJar` → `installDist`

**Integration tests:**
- [x] `KnowledgeServerIntegrationTest.java`: update `config.workerJarPath()` ×7 → `config.workerLibDir()`; update existence guard
- [x] `RichDocumentIntegrationTest.java`: update `config.workerJarPath()` ×3 → `config.workerLibDir()`; update existence guard
- [x] `SchemaMismatchStatusContractTest.java`: update `Files.exists(config.workerJarPath())` → check `config.workerLibDir()` directory

**System tests:**
- [x] `TestEnvironmentProvisioner.java`: rename `workerJarPath` → `workerDistDir`; expose `getWorkerDistDir()`; read `justsearch.worker.dist.dir` sysprop
- [x] Update all 13 system test files: `new WorkerProcessManager(env.getWorkerJarPath(), …)` → `WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), …)`
  - `VduBatchProcessorE2ETest.java`
  - `VduRecoverySystemTest.java`
  - `WorkerSpawnTest.java`
  - `SyncDirectoryIntegrationTest.java`
  - `SwitchingFenceBufferingE2ETest.java`
  - `RollbackE2ETest.java`
  - `PauseResumeMigrationE2ETest.java`
  - `MigrationControlE2ETest.java`
  - `IndexBasePathLockE2ETest.java`
  - `GrpcDataIntegrationTest.java`
  - `GrpcCommunicationTest.java`
  - `CompleteIndexingWorkflowE2ETest.java`
  - `SummarizationPipelineE2ETest.java`

### Phase 3: Cleanup

- [x] Delete `build-logic/src/main/kotlin/conventions/FatJarConventionsPlugin.kt`
- [x] Delete `build-logic/src/main/kotlin/conventions/MergeServicesTask.kt`
- [x] `build-logic/build.gradle.kts`: remove `fatJarConventions` plugin registration block (no third-party dep — just the registration block itself)
- [x] `docs/reference/contributing/agent-guide.md`: update `shadowJar` references ×2
- [x] `docs/reference/contributing/mcp-dev-tools.md`: update `shadowJar` references ×3
- [x] `docs/how-to/use-ui.md`: update `shadowJar` references ×2
- [x] `docs/explanation/12-desktop-installer-and-sandbox-setup.md`: update bundle layout description for `lib/worker/` directory
- [x] `CLAUDE.md`: update "Stale Worker JAR" pitfall row

### Phase 4: Project Leyden AOT cache — EVALUATED, NOT VIABLE

> **Status**: Closed. Experimental measurements on JDK 25.0.1 (Temurin) show no startup
> improvement. The Worker's warm startup is already ~1.5s, and the AOT cache slightly degrades
> it (~1.8s) due to 65 MB cache I/O overhead and incomplete class archiving.

**Measurements (2026-02-21, Windows 11, JDK 25.0.1 Temurin, NVMe SSD):**

| Scenario | gRPC ready (avg of 3 runs) |
|----------|---------------------------|
| Cold start (no OS file cache) | ~14.3s |
| Warm start, no AOT cache | **~1.6s** |
| Warm start, with AOT cache (65 MB) | **~1.8s** (slower) |

**Why it doesn't help:**
1. Worker startup is dominated by real initialization (SQLite, Lucene, Tika discovery, gRPC Netty),
   not class loading or JIT warmup.
2. The 65 MB AOT cache adds I/O that offsets any class-loading savings.
3. `--add-modules jdk.incubator.vector` causes `archivedBootLayer` to be disabled, removing the
   most valuable part of the AOT cache (full module graph).
4. ~40 classes skipped (Tika/gRPC-epoll/Jinjava — unlinked or in error state).

**Training workflow that was tested:**
- `java -XX:AOTCacheOutput=worker.aot` with a wrapper class that starts the Worker, waits 15s,
  then calls `System.exit(0)`. The JVM automatically records loaded classes and spawns a child
  process to assemble the cache.
- Constraint: classpath must be all JARs, no exploded directories (the AOT system rejects
  non-empty directories on the classpath).

**Conclusion:** Revisit only if (a) `jdk.incubator.vector` graduates to a standard module
(restoring `archivedBootLayer`), or (b) the Worker's startup workload grows significantly.

- [x] ~~Measure startup time improvement vs. baseline~~ — measured; no improvement

---

## Decision log

| Date | Decision |
|------|----------|
| 2026-02-20 | Tempdoc opened. Option A (`installDist`) recommended pending measurement. |
| 2026-02-20 | Codebase investigation complete. Scope corrected: 25+ files (vs. original 7). Key findings: (1) No third-party shadow plugin — `FatJarConventionsPlugin.kt` uses Gradle's built-in `Jar` task. (2) `WorkerProcessManager` already implements distribution mode via `fromDistribution()`, used by 3 existing test classes. (3) `prepareTests` in root `build.gradle.kts` already depends on `installDist` alongside `shadowJar`. (4) `modules/app-services` integration tests already depend on `installDist`. (5) `modules/dist` does not exist — dev-mode `libDir` path is dead code. Research confirms: ServiceLoader works correctly (and better) with `installDist`; ProcessBuilder `-cp lib/*` wildcard expansion is handled by the JVM launcher on Windows; existing `base.jsa` is silently ignored after launch form change; `installDist` `Sync` task eliminates 220 MB repack even without true incremental copying. Option E (Project Leyden AOT cache) added as additive Phase 4 to supersede `base.jsa` AppCDS. |
| 2026-02-21 | Deep codebase investigation complete before implementation. Additional findings recorded as implementation notes above Phase 2: (1) `-cp` wildcard must be a plain `String` (not `Path`) to avoid `InvalidPathException` on Windows. (2) `fromDistribution()` uses argfile-based launch via `bin/indexer-worker.bat` — separate from `WorkerSpawner.java` `-cp` path. (3) `soakTest` uses `indexer-worker-<version>.jar` (non-fat), not `-all.jar` — both sysprops need migration. (4) `JUSTSEARCH_WORKER_JAR` / `justsearch.worker.jar` env/sysprop must be renamed to `JUSTSEARCH_WORKER_LIB_DIR` / `justsearch.worker.lib.dir`. (5) Integration test existence guards must change from `Files.exists()` to `Files.isDirectory()`. Implementation begins on branch `226-install-dist`. |
| 2026-02-21 | **Phase 2 + Phase 3 complete** (commit `25092ab3`). All 25+ files updated: fat JAR plugin deleted, `WorkerSpawner` migrated to `-cp lib/worker/*`, `KnowledgeServerConfig` record field renamed, 13 system test files switched to `fromDistribution()`, all docs updated. Build passes; unit + integration tests pass. Remaining open items: (1) full system test suite not yet run; (2) Phase 4 (Project Leyden AOT cache) not started. |
| 2026-02-21 | **Post-implementation fixes** (commit `b2a8b958`): (1) Wired `installDist` into `assemble` in `indexer-worker/build.gradle.kts` — `FatJarConventionsPlugin` deletion had created a regression where `./gradlew build` no longer produced the worker distribution. (2) Fixed `bundleSidecarResources` `from()` to use typed `Sync::class` task output mapping instead of raw `Provider<Directory>`. (3) Renamed local variable `workerJar` → `workerLibDir` in `KnowledgeServerConfig.load()`. |
| 2026-02-21 | **Phase 4 evaluated, not viable.** Experimental measurements on JDK 25.0.1 Temurin show the AOT cache (65 MB, `-XX:AOTCacheOutput`) provides no startup improvement: warm start is ~1.6s without AOT vs. ~1.8s with AOT. Root causes: (1) startup is dominated by real init (SQLite/Lucene/Tika/gRPC), not class loading; (2) `--add-modules jdk.incubator.vector` disables `archivedBootLayer`; (3) ~40 classes skipped as unlinked. Phase 4 closed. Tempdoc complete — all actionable phases implemented. |
| 2026-02-22 | **Merged to `main`** (commit `852c3032`). Post-merge verification: `spotlessApply` clean, `build -x test` passes, unit tests pass for all 3 affected modules (`app-services`, `indexer-worker`, `ui`). `installDist` output confirmed: 164 JARs in `build/install/indexer-worker/lib/`. Worktree and branch cleaned up. |
