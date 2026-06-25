---
title: "371: Stale JVM Detection"
status: superseded
superseded_by: "ADR-0021 (build-stamp), docs/explanation/02-process-coordination.md, docs/reference/contributing/mcp-dev-tools.md"
created: 2026-03-30
---

# 371: Stale JVM Detection

## Problem

After rebuilding the Java distribution (`./gradlew.bat assemble`),
a running backend JVM continues serving old classes. There is no
mechanism to detect that the running process doesn't match the
built distribution. The developer discovers the mismatch only
when new code doesn't behave as expected — which looks like a
code bug, not a deployment issue.

The `reload` command supports hot-reload via JDWP for method-body
changes, but structural changes (new record fields, new methods,
new classes) require a full restart. Nothing warns that a restart
is needed.

In the 366 tempdoc, this caused multiple debugging cycles where
correctly implemented code appeared broken because the running
JVM was serving stale classes.

## Scope

Investigate and research approaches to detect or prevent
stale-JVM issues. Determine the best solution for the JustSearch
dev workflow.

## Research findings

### Industry state (Nov 2025 -- Apr 2026)

Stale-JVM detection is an **unsolved gap** in the Java ecosystem.
No tool, framework, or JDK feature addresses "tell me whether the
running JVM matches what's on disk."

**Hot-reload landscape (structural changes):**
- Standard JDK (25/26): No progress. JEP 159 dormant since 2012.
- JetBrains Runtime (JBR 25): 25.0.2-b329.72 (Mar 2026). DCEVM
  maintained, `-XX:+AllowEnhancedClassRedefinition`. Only option
  for structural hot-swap on modern JDK.
- HotswapAgent 2.0.4-SNAPSHOT (Feb 2026): tracks JBR 25.
- GraalVM Espresso: `EnableAdvancedRedefinition` — requires
  non-standard runtime. Impractical as drop-in.
- JRebel 2026.1.1: commercial, ~$50/mo. Most complete but doesn't
  solve detection.
- DebugTools (IntelliJ plugin): supports JDK 25 (via JBR).
  Structural changes still need JBR.
- Compose Hot Reload 1.0.0 (Jan 2026): most interesting adjacent
  art — bytecode hash per composable group + dependency graph for
  dirty resolution. Technique is transferable as inspiration but
  Kotlin/Compose-specific.

**Detection landscape:**
- Build-info plugins (`gradle-git-properties`, Spring `build-info`):
  embed git SHA / timestamp in artifacts. Passive building blocks
  only — no comparison logic.
- Spring DevTools: fast classloader restart, not detection.
- Quarkus Dev Mode: per-request source check, deeply coupled to
  Quarkus augmentation. Not extractable.
- IntelliJ 2024.3: auto-detects code changes during debug, shows
  reload button. IDE-only, not for headless/CLI workflows.
- No published library or pattern for "compare running build
  identity against on-disk distribution" exists anywhere.

**Conclusion:** The solution must be custom-built. The pattern is
well-understood (stamp, expose, compare) but no off-the-shelf
implementation exists.

### Codebase analysis

**Current state — no build identity exists:**
- `ArchivingReproduciblePlugin` strips timestamps from JARs.
  Manifests contain only static strings (`Created-By: Gradle`,
  `Built-By: justsearch`). `Implementation-Version` is
  `2.0.0-SNAPSHOT` (static from `gradle.properties`).
- No `/api/debug/build-info` endpoint. `/api/debug/state` shows
  uptime/memory/config but no distribution identity.

**`reload` command (MCP `justsearch.dev.reload`):**
- 4 steps: compile -> JDWP redefineClasses -> MMF signal ->
  service reconstruction.
- Structural changes: JDWP throws, `HotSwapPush.java` exits 1
  with "use Phase 1 (JBR + HotswapAgent)" message. MCP tool
  sets `hotSwapOk: false` but still writes MMF signal. Service
  reconstruction happens with old bytecode.
- No "restart required" warning surfaced clearly.

**`jseval` backend integration:**
- `--start-backend`: spawns Gradle `runHeadlessEval` which
  triggers `installDist` — always fresh.
- `--reset`: no freshness check. Trusts whatever is running.
- `_get_git_sha()` records commit in output but never compares.

**`installDist` -> `runHeadless` pipeline:**
- `indexer-worker/build.gradle.kts`: `assemble` depends on
  `installDist`. Gradle always produces fresh distribution.
- `runHeadless` depends on `:modules:indexer-worker:installDist`.
- Staleness only arises when JVM is already running and developer
  rebuilds without restarting.

## Experiment results

### 1. `installDist` UP-TO-DATE behavior

Second run without changes: **UP-TO-DATE** (619ms, 38 tasks
all up-to-date). This means a timestamp-based stamp would need
a task that's never up-to-date (breaks Gradle caching) or we
use a **content hash** (deterministic — same inputs produce same
hash, so UP-TO-DATE is correct).

**Decision: Use a content hash, not a timestamp.** The hash only
changes when JARs change, which is exactly when staleness matters.

### 2. Distribution directory structure

```
build/install/indexer-worker/
  bin/indexer-worker      (Unix start script)
  bin/indexer-worker.bat  (Windows start script)
  lib/                    (175 JARs, wildcard classpath)
```

The start script sets `APP_HOME=%DIRNAME%..` (the distribution
root). However, WorkerSpawner does NOT use the start scripts
(see experiment 3). A stamp file could live at the distribution
root or in `lib/` — but since WorkerSpawner only knows `lib/`,
placing it in `lib/` is more natural.

### 3. WorkerSpawner launch mechanism

WorkerSpawner builds `java -cp <workerLibDir>/* io.justsearch.indexerworker.IndexerWorker`
directly — it does NOT use `bin/indexer-worker.bat`.

`workerLibDir` resolves to (dev):
`<repoRoot>/modules/indexer-worker/build/install/indexer-worker/lib/`

The Worker's working directory is the repo root. No system
property or env var carries the distribution root path — it's
computed internally by `KnowledgeServerConfig.resolveWorkerLibDir()`.

**Implication:** The Worker can read files from `lib/` (it's the
classpath root), or from the distribution root by going up one
level from `lib/`. A classpath resource inside a JAR is the
cleanest approach — the Worker already uses
`Class.getResourceAsStream()` extensively.

### 4. Head vs Worker staleness scope

- **Head**: runs from `sourceSets["main"].runtimeClasspath`
  (line 1811 of `ui/build.gradle.kts`). Class files are on disk
  at `build/classes/java/main/`. After recompile, the running
  Head JVM still has old classes loaded. No reload mechanism
  exists for the Head. **Head always requires full restart.**
- **Worker**: runs from `installDist` output. The `reload`
  command pushes bytecode via JDWP. Structural changes require
  restart.
- **Scope decision:** Focus detection on the Worker distribution
  (the most common staleness case). Head staleness is rarer
  (most dev iteration is on Worker-side search/indexing code)
  and always requires restart anyway.

### 5. gRPC service for Worker build info

`StatusResponse` (field 12 next available) already contains
`commit_user_data map<string, string>` for index metadata.

**Best option:** Add a `string build_stamp = 12` field to
`StatusResponse`. Zero new RPCs — the existing `IndexStatus`
RPC already returns this message. On the Head side,
`RemoteKnowledgeClient.getStatus()` already fetches it.

### 6. Worker classpath resource reading patterns

Established pattern: `ClassName.class.getResourceAsStream("/path")`
in static initializers. Used in QueryUnderstandingService,
FilterNormalizationService, ContextSufficiencyService,
JustSearchConfigurationLoader. No JAR manifest reading exists.

**Decision:** Follow the established pattern — generate a
`build-stamp.txt` classpath resource in the Worker JAR.

### 7. On-disk stamp path for jseval/reload comparison

jseval already knows `REPO_ROOT` via `_paths.py`. The stamp
file would be at a predictable path:
`REPO_ROOT / modules/indexer-worker/build/install/indexer-worker/lib/indexer-worker-2.0.0-SNAPSHOT.jar`
— but reading inside a JAR is complex.

**Better:** Write a standalone `build-stamp.txt` to the
distribution root during `installDist`. Path:
`<repoRoot>/modules/indexer-worker/build/install/indexer-worker/build-stamp.txt`

Both jseval and the MCP reload tool can read this plain file.
The Worker reads the same value from its classpath resource
(baked into the JAR at compile time).

### 8. Reload tool failure output parsing

HotSwapPush.java has two failure modes, both exit code 1:
- `UnsupportedOperationException`: "HotSwap not supported by
  target VM" (line 132) — VM doesn't support HotSwap at all.
- Generic `Exception`: "HotSwap failed: ..." + "If you
  added/removed methods or fields..." (lines 136-137) —
  structural change rejected.

The MCP tool captures combined stdout+stderr in `hotSwapOutput`.
The string "added/removed methods or fields" is a reliable
signal for the structural case. Parsing it is safe.

**Decision:** Add `structuralChangeDetected: true` to the reload
response when output contains "added/removed". Add a clear
`"RESTART REQUIRED: structural changes detected"` message.

### 9. jseval pre-run flow for freshness check

Natural insertion point: between line 151 (`_reset_index`) and
line 153 (`IngestConfig`) in `cli.py`. Backend is confirmed up,
`base_url` is known, no corpus data pushed yet.

`preflight.py` already has `_fetch_endpoint()` for HTTP calls.
jseval knows `REPO_ROOT` for reading on-disk stamp.

**Decision:** Add `_check_build_freshness(base_url)` at this
point. It fetches the running stamp from `/api/status` (which
already includes the new `build_stamp` field via gRPC), reads
the on-disk stamp file, and warns on mismatch. Warning only
(not abort) — user may intentionally test old code.

### 10. What to use as stamp content

Options considered:
- **Timestamp**: breaks `installDist` UP-TO-DATE. Rejected.
- **Git SHA**: doesn't capture uncommitted changes. Rejected
  as sole identifier.
- **Content hash of JARs**: deterministic, captures exactly
  what matters. The hash changes iff the distribution changes.
  Gradle can compute it as a `processResources` output.

**Decision:** SHA-256 of the sorted list of (JAR-filename,
JAR-file-size) pairs in the `lib/` directory. Lightweight to
compute (no full file hashing), changes when any JAR changes.
Alternatively: use a Gradle-computed hash of `compileJava`
output files. Final approach to be determined during
implementation — the key property is determinism.

## Answers to original questions

1. **Build info via API?** Yes. Add `build_stamp` field to the
   existing `StatusResponse` proto message. Expose via the
   existing `/api/status` endpoint (already fetches
   `IndexStatus` RPC). Also write a standalone
   `build-stamp.txt` to the distribution root for off-JVM
   comparison.

2. **Reload structural change warning?** Yes. Parse
   HotSwapPush.java output for "added/removed methods or
   fields". Add `structuralChangeDetected: true` and a clear
   `"RESTART REQUIRED"` message to the MCP reload response.

3. **jseval freshness check?** Yes. Add
   `_check_build_freshness()` between reset and ingestion.
   Compare running backend's `build_stamp` against on-disk
   stamp file. Warn on mismatch, don't abort.

4. **Industry approaches?** Researched extensively. No
   off-the-shelf solution. Spring's `build-info.properties`
   and `gradle-git-properties` are passive building blocks.
   Custom stamp+expose+compare is the standard pattern.

5. **`installDist` → `runHeadless` staleness?** No —
   Gradle's dependency chain guarantees freshness when
   launching via Gradle tasks. Staleness only occurs with
   already-running JVMs.

## Implementation plan

### Phase 1: Build stamp generation (Gradle)

- [x] `generateBuildStamp` task in `indexer-worker/build.gradle.kts`.
  Computes SHA-256 of lib/ contents: project JARs (SNAPSHOT) are
  content-hashed for exact change detection; third-party JARs use
  name+size (immutable, avoids reading 371MB onnxruntime_gpu).
  Truncated to 16 hex chars. Written to
  `build/install/indexer-worker/build-stamp.txt`.
- [x] Wired into `assemble` via `dependsOn(generateBuildStamp)`.
  Deterministic (UP-TO-DATE safe), compatible with
  `ArchivingReproduciblePlugin`.

### Phase 2: Worker exposes build stamp via gRPC

- [x] `string build_stamp = 12` added to `StatusResponse` in
  `indexing.proto`.
- [x] `WorkerSpawner` reads `build-stamp.txt` from distribution
  root and passes `-Djustsearch.build.stamp=<hash>` to the
  Worker process.
- [x] `EnvRegistry.BUILD_STAMP` registered for the system
  property.
- [x] `IndexStatusOps.buildStamp()` reads the property and
  populates `StatusResponse.build_stamp`.

### Phase 3: Head exposes build stamp via REST

- [x] `WorkerOperationalView` record extended with `buildStamp`
  field (flattened into `/api/status` JSON via `@JsonUnwrapped`).
- [x] `WorkerStatusMapper.toUiStatusMap()` maps proto
  `getBuildStamp()` to the view field.
- [x] All test callers updated (3 files).

### Phase 4: MCP reload — structural change detection

- [x] `server.mjs` reload handler parses `hotSwapOutput` for
  "added/removed methods or fields".
- [x] `structuralChangeDetected: boolean` added to reload
  response.
- [x] `restartRequired: string` message included when structural
  change is detected.

### Phase 5: jseval freshness check

- [x] `_check_build_freshness(base_url)` in `jseval/cli.py`.
- [x] Fetches running stamp from `/api/status` `buildStamp`
  field.
- [x] Reads on-disk stamp from
  `<repo_root>/modules/indexer-worker/build/install/indexer-worker/build-stamp.txt`.
- [x] On mismatch: prints warning to stderr with both stamps and
  remediation advice.
- [x] Called between `_reset_index()` and `IngestConfig`
  construction. Skipped when `--start-backend` (always fresh).

### Phase 6: Stamp propagation after successful reload

- [x] MCP reload tool: after `hotSwapOk === true`, reads
  `build-stamp.txt` from distribution root and writes the stamp
  to `<dataDir>/reload-build-stamp.txt`. Written BEFORE the MMF
  signal to avoid race with Worker's sentinel thread.
- [x] `DevReloadManager.updateBuildStampFromReloadFile()`: reads
  `reload-build-stamp.txt` after service reconstruction and calls
  `System.setProperty()` to update the running stamp.
- [x] On structural-change failure (`hotSwapOk === false`): stamp
  file is NOT written — the Worker remains correctly marked stale.
- [x] ArchUnit exemptions added for `DevReloadManager` (setProperty)
  and `IndexStatusOps` (getProperty via EnvRegistry).

### Phase 7: Bug fixes found during critical analysis

- [x] **Content hashing**: Changed from filename:filesize to actual
  content hashing for SNAPSHOT JARs. Filename:filesize could miss
  changes that don't alter JAR size (experimentally confirmed with
  a `.equals()` receiver swap producing identical 292,465-byte JARs).
  Third-party JARs still use name+size (immutable, avoids reading
  371MB onnxruntime_gpu).
- [x] **Race condition**: Stamp file write moved BEFORE MMF signal
  write. Previously the Worker's sentinel could trigger
  `performReload()` before the stamp file was written.
- [x] **`runHeadless` coverage**: `generateBuildStamp` wired via
  `finalizedBy` on `installDist` (not just `assemble`). Ensures
  stamp is generated for all launch paths: `runHeadless`,
  `runHeadlessEval`, `assemble`, and `build`.

## Known limitations

- **Head process staleness is not detected.** The Head runs from
  Gradle's source classpath (not `installDist`), has no JDWP agent,
  and has no reload mechanism. Any Head-side code change requires a
  full dev stack restart. This is by design — the `reload` workflow
  only targets the Worker.

- **Reload compiles but doesn't rebuild the distribution.** The
  `reload` command runs `compileJava` for one module, not
  `installDist`. After reload, the on-disk distribution and stamp
  are stale, but the running JVM has updated bytecode. The stamp
  propagation (Phase 6) prevents false positives by forwarding the
  distribution stamp to the Worker — both the running JVM and the
  on-disk stamp agree (both reflect the pre-reload distribution).
  A subsequent `assemble` updates the distribution stamp; if the
  developer doesn't restart, jseval will correctly warn of
  staleness.
