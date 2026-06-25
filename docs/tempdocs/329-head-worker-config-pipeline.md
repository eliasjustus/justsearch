---
title: "329: Head→Worker Config Pipeline"
type: tempdoc
status: done
created: 2026-03-21
updated: 2026-03-21
---

> NOTE: Noncanonical doc (architecture). May drift.

# 329: Head→Worker Config Pipeline

## Purpose

Eliminate a class of recurring silent misconfiguration bugs where config
values set by the user (env vars, system properties) reach the Head
process but not the Worker subprocess. During tempdoc 312 (item 20),
four separate forwarding bugs were found through live testing — none
caught by existing tests, none producing any error.

## Origin

See tempdoc 312 §Retrospective for the specific failures.

## Problem

The path from "user sets a config value" to "Worker subprocess reads it"
has four indirection layers with independently maintained forwarding
lists:

1. Gradle task (`environment()` / `systemProperty()` / `jvmArgs("-D...")`)
2. Head JVM (`System.getProperty()` or `System.getenv()`)
3. `WorkerSpawner.forwardPropIfSet()` (reads `System.getProperty()`)
4. Worker subprocess (`System.getProperty()` → `EnvRegistry`)

Adding a new config key requires manual edits in 2-3 locations.
Omitting one produces silent misconfiguration: the Worker auto-discovers
defaults and operates with the wrong values. No error, no warning.

The `runHeadless` and `runHeadlessEval` Gradle tasks have independently
maintained forwarding lists that can silently diverge.

### Existing forwarding mechanisms (code audit, 2026-03-21)

There are actually **four** forwarding sites (not three), each using
structurally different mechanisms:

**`WorkerSpawner.buildCommand()`** — four co-mingled mechanisms:
- 14 explicit `forwardPropIfSet()` calls for system properties (lines 337-383)
- Blanket `JUSTSEARCH_*` env var forwarding via `ProcessBuilder.environment()`
  (lines 258-267, excludes `JUSTSEARCH_DATA_DIR`)
- Bespoke ORT native path derivation logic (lines 362-381)
- Dev hot-reload flags read from `EnvRegistry` directly (lines 628-643)

**`runHeadless` Gradle task** (modules/ui/build.gradle.kts lines 1739-1817):
- `jvmArgs("-D...")` for api.port, data.dir, embed model path, schema policy
- `environment()` for 12 AI-related env vars
- Inline CUDA auto-detection block (ORT DLL sniffing + SPLADE GPU auto-enable)
- Reads `System.getenv()` directly (not Gradle providers)

**`applyHeadlessEvalContract()`** (lines 1585-1737, used by `runHeadlessEval`):
- Gradle `providers.environmentVariable()` for 8 core paths
- `systemProperty()` for 11 config keys
- `environment()` for GPU/model env vars
- Its own parallel CUDA auto-detection block
- Eval-specific flags (`disable_breath_holding`)

**`runHeadlessWithProfiling`** (lines 1830-1868) — most stale:
- `jvmArgs` for api.port only (no data.dir)
- `environment()` for only 8 env vars
- Uses deprecated `JUSTSEARCH_NATIVE_PATH` (not `ONNXRUNTIME_NATIVE_PATH`)
- No CUDA auto-detection
- No embed model path or schema policy forwarding

### Existing safety nets

Two mechanisms already cover **most** propagation:

1. **Blanket env var forwarding** — all `JUSTSEARCH_*` env vars are
   forwarded to the Worker via `ProcessBuilder.environment()`. Any env
   var the user sets reaches the Worker. The failure class is specifically
   about **system properties** (`-D` flags) set in the Head JVM.

2. **Config snapshot** — `HeadlessApp` serializes fully resolved config
   to `worker-config-snapshot.json`, forwards the path via
   `-Djustsearch.worker.config_snapshot`. Worker loads at ordinal 450
   (above env vars at 400, below JVM args at 500). This handles most
   config propagation.

### Critical finding: Worker has zero direct System.getProperty calls

Code audit of `modules/indexer-worker`, `modules/worker-services`, and
`modules/adapters-lucene` production source found **zero** direct
`System.getProperty` calls. All config reads go through
`ResolvedConfigBuilder` / `ConfigStore` / `EnvRegistry`.

**However**, `modules/configuration` runs in both processes and uses
`EnvRegistry.get()` (raw sysprop + env var reads) — notably
`RepoRootLocator.findRepoRoot()` reads `REPO_ROOT` and `SSOT_PATH`
via `EnvRegistry.get()`. Properties read via this path need forwarding
if they might be set only as a sysprop in the Head (with no
corresponding env var).

The forwarding set was consolidated (not pruned) into
`WorkerSpawner.WORKER_FORWARDED_PROPS` — a single declared set that
replaces the 14 scattered `forwardPropIfSet` calls.

### gRPC contract (code audit, 2026-03-21)

Three gRPC services defined in `indexing.proto`:
- `SearchService` — 9 RPCs (search, suggest, fetch, context, citations, folders)
- `IngestService` — 18 RPCs (submit, status, delete, migration, sync)
- `HealthService` — 1 RPC: `Check(HealthCheckRequest) → HealthCheckResponse`

`HealthCheckResponse` carries: `serving`, `version`, `pid`, `worker_state`,
`embedding_ready`, `onnx_models`. No config values.

`StatusResponse` (from `IngestService.IndexStatus`) is richer — carries
resolved model paths at fields 107-110 (`embed_backend`, `splade_model_path`,
`reranker_model_path`, `embed_gpu_layers`) but these are runtime state, not
forwarded config values.

**No existing mechanism for Worker to report received config.** Adding
divergence detection requires new fields or a new RPC.

Post-startup handshake in `KnowledgeServerBootstrap` (lines 98-161):
1. `spawner.start()` → blocks until port written to MMF
2. `client.connect(port)` → opens gRPC channel
3. `validateWorkerPid()` → calls `HealthService.Check`, verifies PID match
4. `client.isHealthy()` → final liveness check
5. State → `READY`

The divergence check hooks naturally **after step 3** (PID validated,
gRPC channel confirmed working, before declaring READY).

## Work Items

### 1. Consolidate forwarding + extract shared Gradle infra

**1a. Consolidate `WorkerSpawner.forwardPropIfSet` into declared set.** ✅
Replaced 14 scattered `forwardPropIfSet()` calls with a single
`WORKER_FORWARDED_PROPS` EnumSet (9 `EnvRegistry` entries) + 4 explicit
non-EnvRegistry keys. Adding a new worker-relevant property means one
edit to the set. ORT derivation logic stays as special-case.

**1b. Extract shared CUDA auto-detection function in Gradle.** ✅
Extracted `detectOrtCudaPath(projectRoot)` function. Both `runHeadless`
and `applyHeadlessEvalContract()` now call it instead of duplicating
the 20-line inline block.

**1c. Fix `runHeadlessWithProfiling`.** ✅
Aligned with `runHeadless`: added 4 missing env vars, CUDA auto-detection,
sysprop forwarding for embed model path and schema policy. Replaced
deprecated `JUSTSEARCH_NATIVE_PATH` with `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`
(kept deprecated key as fallback).

**1d. Document the forwarding architecture.** ✅
Added "Head→Worker Config Propagation" section to
`docs/explanation/01-system-overview.md` explaining the three channels
(config snapshot, blanket env var, explicit sysprop forwarding),
when to use each, and how to add new keys.

**1e. Extract shared Gradle env var lists.** ✅
Extracted `HEADLESS_AI_ENV_VARS` (12 vars) and `HEADLESS_GPU_ENV_VARS`
(11 vars) as shared constants. All three tasks (`runHeadless`,
`applyHeadlessEvalContract`, `runHeadlessWithProfiling`) now reference
these instead of independent inline `listOf(...)` declarations.

**1f. Use `EnvRegistry.get()` in WorkerSpawner forwarding loop.** ✅
Changed the `WORKER_FORWARDED_PROPS` loop from `forwardPropIfSet(cmd,
key.sysProp())` (sysprop-only) to `key.get().ifPresent(...)` (sysprop
THEN env var). Removed the env→sysprop bridges from `runHeadless` and
`runHeadlessWithProfiling` that existed solely to make values visible
to `forwardPropIfSet`.

### 2. Warn on config divergence between Head and Worker ✅

Extended `HealthCheckResponse` with `map<string, string> effective_config`
(proto field 8). Worker populates from `EnvRegistry.get()` for keys in
`EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS`. Head compares at startup
via `KnowledgeServerBootstrap.checkConfigDivergence()`, called after PID
validation and before declaring READY.

**Curated comparison set** (`CONFIG_DIVERGENCE_CHECK_KEYS`):
`DATA_DIR`, `CONFIG_PATH`, `REPO_ROOT`, `SSOT_PATH`,
`EMBED_ONNX_MODEL_PATH`, `ORT_NATIVE_PATH`, `INDEX_BASE_PATH`.

Mismatches produce WARN logs; no match produces INFO confirmation.
Failures are non-fatal (logged at DEBUG, do not block startup).

**2a. Narrow check set to forwarded keys only.** ✅
Removed `EMBED_BACKEND`, `EMBED_GPU_ENABLED`, `SPLADE_GPU_ENABLED`
from `CONFIG_DIVERGENCE_CHECK_KEYS`. These reached the Worker via
snapshot or blanket env var forwarding, not as `-D` sysprops. Comparing
them via raw `EnvRegistry.get()` produced false WARNs. The check now
only validates keys that are explicitly forwarded as `-D` args.
Consistency enforced by structural unit test (3a).

### 3. Integration test for config propagation ✅ (smoke test)

Added `ConfigPropagationTest` in
`modules/system-tests/src/systemTest/java/.../process/`. Two tests:

- `workerReportsEffectiveConfig` — spawns Worker, verifies `effective_config`
  map is non-empty and contains `justsearch.data.dir`.
- `forwardedSyspropReachesWorker` — verifies the provisioner's `data.dir`
  value matches the Worker's reported value exactly.

Uses existing infra: `WorkerProcessManager`, `MmfTestHarness`,
`GrpcTestClient`, `TestEnvironmentProvisioner`.

**3a. Structural consistency test (unit test).** ✅
Added `WorkerSpawnerConfigForwardingTest` in `app-services` tests.
Asserts every key in `CONFIG_DIVERGENCE_CHECK_KEYS` is in
`WORKER_FORWARDED_PROPS` or `HARDCODED_FORWARDED`. Runs in
milliseconds. Catches "added to check set but forgot to forward"
at CI time.

## Dependencies

- **312 (Primary Indexing Throughput):** Origin of the findings.
- **Item 3 depends on item 2** (test needs gRPC config reporting).

## Non-Goals

- Changing the Head/Worker process architecture.
- Adding scope markers to all 100+ `EnvRegistry` entries — unnecessary
  given the finding that the config snapshot handles propagation.

## Long-term: migrate `EnvRegistry.get()` callers to `ConfigStore`

The entire `WORKER_FORWARDED_PROPS` mechanism exists because some code
reads `EnvRegistry.get()` (raw `System.getProperty` / `System.getenv`)
instead of `ConfigStore` (which receives the snapshot at ordinal 450).
If all Worker-side code used `ConfigStore`, the forwarding set would
shrink to ~3 bootstrap entries and the divergence check would compare
always-correct `ConfigStore` values instead of channel-dependent raw
reads.

**Not a big-bang migration.** When touching a subsystem config class,
migrate it from `EnvRegistry.get()` to `ConfigStore`. Key targets:

1. **`RepoRootLocator.findRepoRoot()`** — reads `REPO_ROOT` and
   `SSOT_PATH` via `EnvRegistry.get()`. Should check
   `ConfigStore.globalOrNull()` first, fall back to `EnvRegistry.get()`
   only during early bootstrap.

2. **`KnowledgeServerConfig.envOrProperty()` callers** — Head-side, but
   the pattern propagates (Head reads via envOrProperty → sets sysprop →
   WorkerSpawner forwards sysprop). If Head reads from `ConfigStore`,
   the sysprop-setting step disappears.

3. **Subsystem `fromEnv()` factory methods** — e.g., `EmbeddingConfig
   .fromEnv()`. Should take a `ResolvedConfig` parameter or read from
   `ConfigStore`.

4. **`ResolvedConfigBuilder.loadWorkerSnapshotFromSysprop()`** — reads
   `justsearch.worker.config_snapshot` via `System.getProperty()`. This
   is the one property that genuinely must be a `-D` arg forever (the
   bootstrap pointer).

**End state:** `WORKER_FORWARDED_PROPS` contains only
`WORKER_CONFIG_SNAPSHOT` + `DATA_DIR` (PlatformPaths bootstrap) +
legacy ORT key. Everything else flows through the snapshot.
