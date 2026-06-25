---
title: "347: Unified Configuration Resolution"
type: tempdoc
status: done
created: 2026-03-23
updated: 2026-03-25
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 347: Unified Configuration Resolution

## Goal

Complete the unification of configuration resolution that tempdocs
246→247→300→301 started. Those tempdocs built the ordinal-chain
infrastructure (`ResolvedConfigBuilder`, `ConfigStore`) and migrated
consumers onto it. But the migration left behind three categories of
debt:

1. **43 ad-hoc keys** outside `EnvRegistry` (`putAdHocEnvSysprop`)
2. **Dead dual-path fallback code** in 5 `fromEnv()` methods
3. **A Gradle workaround** (`gpuSyspropMap`) made unnecessary by
   `contributeEnvRegistry()`

None of these cause active production bugs — the original 334 bug
(silently ignored env var) is already fixed. This is technical debt
reduction that prevents the *next* config bug and simplifies the
codebase for future config additions.

## Final State (post-implementation)

### Key design change: `configKey` field

`EnvRegistry` entries now have an optional `configKey` field that separates the
ordinal chain key from the operator-facing system property name. Most entries
have `configKey == sysProp` (the common case). Entries migrated from
`putAdHocEnvSysprop` have distinct values:

- **`configKey()`** — the key used in the `ResolvedConfigBuilder` ordinal chain
  (e.g., `index.vector.hnsw.m`). This is what `resolveInt(...)` looks up.
- **`sysProp()`** — the operator-facing JVM system property name
  (e.g., `justsearch.index.vector.hnsw.m`). This is what `-D` overrides set.
- **`envVar()`** — the operator-facing environment variable name
  (e.g., `JUSTSEARCH_INDEX_VECTOR_HNSW_M`).

### How the ordinal chain works now

`contributeEnvRegistry()` iterates every `EnvRegistry` entry and registers:

| Ordinal | Source | Keyed by | Read via |
|---------|--------|----------|----------|
| 500 | JVM `-D` argument | `entry.configKey()` | `System.getProperty(entry.sysProp())` |
| 400 | Environment variable | `entry.configKey()` | `System.getenv(entry.envVar())` |
| 100 | `EnvRegistry` default | `entry.configKey()` | `entry.defaultValue()` |

YAML values at ordinal 200. Worker snapshots at 450. Settings.json at 300.

### What was resolved

1. **All config keys in `EnvRegistry`.** ~90 pre-existing + 42 migrated from
   `putAdHocEnvSysprop` + ~50 YAML-only keys = ~180 total entries. The
   `putAdHocEnvSysprop` method and all call sites were deleted.

2. **Single read path.** All 5 `fromEnv()` methods now call
   `ConfigStore.global()` directly (no fallback). Config values flow through
   one path: `EnvRegistry` → `contributeEnvRegistry()` → ordinal chain →
   `ResolvedConfig` sub-records → `fromEnv()`.

3. **Env→sysprop bridges deleted.** The `gpuSyspropMap` was already removed
   (tempdoc 329). Two remaining bridges in `applyHeadlessEvalContract()`
   for `EMBED_ONNX_MODEL_PATH` and `INDEX_SCHEMA_MISMATCH_POLICY` were
   also removed.

4. **Architectural tests prevent regression.** Six uniqueness/coverage
   tests in `ResolvedConfigBuilderTest` + two forwarding tests in
   `WorkerSpawnerConfigForwardingTest` enforce the invariant.

## Historical Root Cause (now fully resolved)

Config resolution originally had **two parallel truth sources:**

- `EnvRegistry`: reads sysprop → env var → default. Used directly
  by `SpladeConfig.fromEnv()`, `NerConfig.fromEnv()`, etc.
- `ResolvedConfigBuilder`: reads sysprop → YAML → hardcoded default.
  No env var awareness.

This caused the tempdoc 334 bug: `JUSTSEARCH_SPLADE_GPU_ENABLED=true`
was silently ignored. The progression of fixes:

1. `contributeEnvRegistry()` fixed the core bug for ~90 `EnvRegistry` keys.
2. This tempdoc migrated the remaining 42 ad-hoc keys into `EnvRegistry`,
   removed the dual-path fallback in all 5 `fromEnv()` consumers, and
   added architectural tests to prevent regression.

## Motivation

Tempdoc 334 Phase 7 lost significant time debugging why
`JUSTSEARCH_SPLADE_GPU_ENABLED=true` was silently ignored. The
original bug was fixed, but structural debt remained until this
tempdoc completed the unification.

## Work Items (ordered by risk/reward) — all implemented

### Phase 1: low-risk cleanup

3. [x] **Remove the `gpuSyspropMap` workaround** in `build.gradle.kts`.
   The `gpuSyspropMap` was already removed (tempdoc 329 step 1f). Two
   remaining env→sysprop bridges for `EMBED_ONNX_MODEL_PATH` and
   `INDEX_SCHEMA_MISMATCH_POLICY` were also removed since they're
   covered by `HEADLESS_GPU_ENV_VARS` + `contributeEnvRegistry()`.

7. [x] **Verify `/api/debug/config` endpoint.** Already implemented.
   `EffectiveConfigController` serves `/api/debug/effective-config`
   with full `ConfigResolution` traces for every key in the ordinal
   chain via the `resolvedConfig` section.

### Phase 2: structural guardrails

5. [x] **Architectural test.** Added to `ResolvedConfigBuilderTest`:
   - `configKeysAreUnique` — no two entries share a configKey
   - `syspropsAreUnique` — no two entries share a sysProp
   - `envVarsAreUnique` — no two entries share an envVar
   - `noConfigKeySysPropCollision` — configKey doesn't clash with
     another entry's sysProp
   - `contributeEnvRegistryRegistersAll` — every entry appears in
     resolved keys after build()
   - `buildSucceedsWithEnvRegistryOnly` — build doesn't crash

6. [x] **`WorkerSpawner.WORKER_FORWARDED_PROPS` completeness test.**
   Added to `WorkerSpawnerConfigForwardingTest`:
   - `allEnvVarsCoveredByBlanketForwarding` — every env var starts
     with `JUSTSEARCH_` (covered by blanket forwarding)
   - `forwardedPropsNoDuplicates` — no duplicates in the set

### Phase 3: migrate ad-hoc keys

1. [x] **Migrate `putAdHocEnvSysprop` keys to `EnvRegistry`.** Added
   `configKey` field to `EnvRegistry` (separate from `sysProp`, used
   as ordinal chain key). Created 42 new entries for ad-hoc keys +
   ~50 YAML-only entries. Updated `contributeEnvRegistry()` to use
   `configKey()`. Removed all `putAdHocEnvSysprop()` calls and the
   method itself.

2. [x] **Audit all resolve calls.** All resolve keys now have
   `EnvRegistry` entries (verified by architectural tests passing).

### Phase 4: remove dead fallback code

4. [x] **Remove the env-var-only fallback path** in all five dual-path
   consumers. Each `fromEnv()` now calls `ConfigStore.global()` directly
   (throws `IllegalStateException` if not initialized). Updated 3 test
   classes that needed `ConfigStore` setup:
   - `LifecycleContractTest` (ui) — added `@BeforeEach`/`@AfterEach`
   - `EmbeddingConfigTest` (worker-core) — added `storeFromEnvironment()`
     after sysprop setup in 3 tests
   - `EmbeddingCompatibilityControllerTest` (worker-core) — added
     `storeWithDefaults()` setup + `EmbeddingFingerprint.setForTesting()`
     to inject fake fingerprint (fixed pre-existing failure)

## Remaining Gaps (resolved)

### Gap 1: `EnvRegistry.get()` direct callers — enforced

Enforced by ArchUnit rule `EnvRegistryDirectReadTest` (bytecode-level,
in `app-launcher`). Exempt classes: bootstrap (`Launcher`,
`LauncherBootstrap`, `JustSearchConfigurationLoader`, `PlatformPaths`,
`RepoRootLocator`), early-init (`UiSettingsStore`), forwarding
(`WorkerSpawner`), Brain process (`WorkerConfig`), diagnostics
(`EffectiveConfigController`), static-init (`IndexingDocumentOps`),
ORT JNI (`OrtCudaHelper`), model discovery
(`EmbeddingOnnxModelDiscovery`).

The 13 TODO callers (BgeM3Config, KnowledgeServer, AppFacadeBootstrap,
OnlineAiServiceImpl) were migrated in D6 — zero TODO callers remain.

### Gap 2: Reverse-direction architectural test — enforced

`ResolvedConfigBuilder` now records every key that `resolve()` is called
with during `build()` in `resolvedKeys()`. The test
`everyResolvedKeyHasEnvRegistryEntry` in `ResolvedConfigBuilderTest`
asserts that every resolved key has a matching `EnvRegistry.configKey()`.
New `resolveString("new.key", ...)` calls without an `EnvRegistry` entry
will fail this test.

## Architectural Debt

This tempdoc solved the unification problem but introduced structural
debt. D7, D4, D3 were resolved in this tempdoc. D1, D2, D5, D6
are follow-up work. Ordered by impact.

### D1: `EnvRegistry` is now a god enum — RESOLVED

`EnvRegistry` was originally "operator-facing environment variables and
system properties" (~90 entries). It now holds ~90 YAML-only internal
tuning knobs (`INDEX_WRITER_RAM_BUFFER_MB`, `SEARCH_PAGING_STRATEGY`,
etc.) that operators would never set via env vars. But
`contributeEnvRegistry()` registers them at ordinal 400/500, making
them silently overridable via phantom env vars.

**Risk:** Accidental overrides. A CI script setting
`JUSTSEARCH_INDEX_MERGE_SEGS_PER_TIER=5` would silently override the
YAML default. Before 347, that env var was ignored.

Split into `EnvRegistry` (233 operator-facing entries) and `ConfigKey`
(52 YAML-only entries). `contributeEnvRegistry()` only iterates
`EnvRegistry`. The architectural test checks both. 52 phantom env
vars removed.

### D2: `configKey` is a workaround, not a solution — RESOLVED

42 entries have `configKey != sysProp` because YAML paths
(`index.vector.hnsw.m`) differ from sysprop names
(`justsearch.index.vector.hnsw.m`). This three-name system
(configKey, sysProp, envVar) adds cognitive load.

**Fix:** Standardize all ordinal chain keys on the YAML-style
convention. The 42 migrated entries would drop their explicit
`configKey` and use the YAML path as `sysProp` directly (matching
what the YAML-only entries already do). Depends on D1 being done
first. All 42 entries standardized to YAML-style sysprops. Old `justsearch.*`
`-D` sysprop overrides no longer work; env var names (`JUSTSEARCH_*`)
are unchanged. `configKey` field deleted; `configKey()` now returns
`sysProp()` directly. Docs updated. Tests updated.

### D3: `fromEnv()` couples subsystems to global singleton — RESOLVED

Added `from(SubRecord)` methods to all 6 config classes. `fromEnv()`
retained as convenience wrapper. Tests and new callers pass the typed
sub-record directly without `ConfigStore` setup.

### D4: `GpuConfigHelper.isPolicyAllowed()` is a dual-path holdout — RESOLVED

Policy gate now applied in `ResolvedConfigBuilder.resolveModelGpuEnabled()`
and `resolveEmbedGpuEnabled()` at resolution time via
`resolvePolicyGpuAllowed()`. `GpuConfigHelper` deleted (zero production
callers remain after D4+D6).

**Semantic note:** The policy gate is now evaluated at config build time
(startup), not on each `fromEnv()` call. If `EnterprisePolicyService`
changes the policy mid-session via `System.setProperty()`, the resolved
`gpuEnabled` values in `ConfigStore` are stale. In practice this is
harmless — GPU sessions can't be reconfigured without restart — but
callers that previously re-read the policy on every call now get the
startup-time value.

**Worker forwarding fix:** `POLICY_GPU_ACCELERATION_ENABLED` removed
from `WORKER_FORWARDED_PROPS` and `CONFIG_DIVERGENCE_CHECK_KEYS`.
Live verification found that `EnterprisePolicyService.snapshot()`
writes `System.setProperty()` with its computed policy value (from
policy files, ignoring env vars). `WorkerSpawner` then forwarded this
sysprop at ordinal 500, overriding the operator's env var at ordinal
400 and the config snapshot at ordinal 450. Without the explicit `-D`
forwarding, the Worker correctly reads the value from the snapshot
and blanket env var forwarding.

### D5: String-based Gradle enforcement is fragile — RESOLVED

Replaced `CheckEnvRegistryDirectReadsTask` with ArchUnit rule
`EnvRegistryDirectReadTest` that inspects bytecode for
`EnvRegistry.get/getString/getInt/etc.` calls. Exemptions as
class name patterns (not source comments). All 26
`ENVREGISTRY-DIRECT-READ` tags removed from production code.

### D6: 13 `TODO migrate` callers need ResolvedConfig sub-records — RESOLVED

Added `ResolvedConfig.Ai.BgeM3`, `Ai.sparseModel`, `Ai.devHotReload`,
`Search.lambdamartEnabled`. Migrated all 13 callers. Zero TODO-tagged
direct reads remain.

### D7: `resolvedKeys()` tracking has lifecycle ambiguity — RESOLVED

`buildPhaseActive` flag gates `resolvedKeys` population. Only keys
resolved during `build()` (initial sweep + `build*()` methods) are
tracked. Pre-build `resolve()` calls (e.g., in `contributeYaml*()`)
are excluded.

## Related Tempdocs

### Direct predecessors (built the infrastructure this tempdoc unifies)

| Tempdoc | Status | Relationship to 347 |
|---------|--------|---------------------|
| **246 (Eval Infrastructure Centralization)** | complete | Built `ResolvedConfigBuilder`, ordinal chain, `ConfigStore`, worker snapshot, `/api/effective-config`. Identified the dual-path problem but deferred consumer migration. |
| **247 (ResolvedConfig Consumer Migration)** | complete | Migrated production config readers from `EnvRegistry.get()` to `ConfigStore`. Documented the critical nuance that `EnvRegistry.get()` is a lateral move, not a fix (same bypass). Per-key migration pattern (Register → Promote → Resolve → Consume) is the template for item 1. |
| **300 (ConfigPrecedence to ConfigStore Migration)** | implemented | Completed the consumer migration for all 5 subsystem configs (`SpladeConfig`, `NerConfig`, `RerankerConfig`, `CitationScorerConfig`, `EmbeddingConfig`). Introduced the `ConfigStore.globalOrNull()` + `EnvRegistry` fallback pattern that item 4 removes. |
| **301 (ResolvedConfig.Ai Sub-Record Restructuring)** | implemented | Restructured flat `Ai` record into nested sub-records (`Ai.Splade`, `Ai.Ner`, etc.). The typed accessors (e.g., `ai.splade().gpuEnabled()`) that item 4 consumers read were created here. |

### Direct dependencies (mechanisms this tempdoc relies on)

| Tempdoc | Status | What 347 depends on |
|---------|--------|---------------------|
| **329 (Head→Worker Config Pipeline)** | done | Config snapshot mechanism (ordinal 450), blanket `JUSTSEARCH_*` env var forwarding, `WORKER_FORWARDED_PROPS`. Item 6 tests the completeness of this pipeline. |
| **337 (Unified GPU Policy)** | done | Three-priority GPU resolution (`per-model → master → false`) + policy gate. `GpuConfigHelper.resolveGpuEnabled()` and `resolveModelGpuEnabled()` in `ResolvedConfigBuilder` implement this. Item 4 must preserve this resolution when removing the fallback path. |

### Origin (discovered the bug)

| Tempdoc | Status | What happened |
|---------|--------|---------------|
| **334 (Single-Pass Enrichment)** | active | Phase 7 lost significant time debugging silently ignored `JUSTSEARCH_SPLADE_GPU_ENABLED=true`. Root cause: env var reached `EnvRegistry` but not `ResolvedConfigBuilder` (before `contributeEnvRegistry()` existed). Led to the `gpuSyspropMap` workaround that item 3 removes. |

### Lateral (overlapping config-infrastructure work)

| Tempdoc | Status | Overlap |
|---------|--------|---------|
| **243 (EnvRegistry Enforcement)** | complete | Consolidated `System.getProperty("justsearch.*")` calls to `EnvRegistry`. Added `checkNoDirectJustsearchSysProp` Gradle task. Item 5's architectural test complements this — 243 prevents raw sysprop reads, 347 ensures `EnvRegistry` ↔ `ResolvedConfigBuilder` coverage. |
| **283 (Worktree GPU Runtime Path Unification)** | complete | Added `ORT_NATIVE_PATH` to `EnvRegistry`/`ResolvedConfig`, per-subsystem GPU diagnostics. First instance of the pattern item 1 generalizes. |
| **286 (Runtime Config and Diagnostics Audit)** | active | Audits all worker-side subsystems for config completeness, discovery consistency, and diagnostic observability. Broader scope than 347 but overlaps on config completeness. Items discovered by 286 that are config-related would be inputs to item 2's audit. |
| **327 (Embedding Config Pattern Alignment)** | complete | Created `EmbeddingConfig.fromEnv()` with the same dual-path pattern. One of the 5 consumers item 4 cleans up. |
| **293 (Configuration Documentation Sweep)** | — | Config documentation. Would need updating after 347 changes the resolution model. |
