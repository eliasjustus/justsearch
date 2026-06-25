---
title: "247: ResolvedConfig Consumer Migration"
type: tempdoc
status: done
created: 2026-02-28
depends-on: 246
---

> NOTE: Noncanonical doc (analysis + strategy). May drift.

# 247: ResolvedConfig Consumer Migration

## Purpose

Migrate all production config readers from `EnvRegistry.*.get()` and
`RuntimeConfig` factory classes to `ResolvedConfig` / `ConfigStore`. This is
the "migrate" and "contract" phases of the parallel change strategy defined
in tempdoc 246 §10.1.

Tempdoc 246 built the infrastructure (builder, ordinal chain, ConfigStore,
worker snapshot, `/api/effective-config`). This tempdoc migrates consumers
onto that infrastructure and removes the old parallel paths.

---

## Pre-Migration Investigation

The following investigations must be completed and their findings recorded
in this document before any migration code is written. Each investigation
addresses a specific confidence gap identified during the 246 review.

### Investigation 1: Runtime System.setProperty Communication Channels

**Question:** Which `System.setProperty` calls are runtime communication
(component A writes, component B reads dynamically), and which are one-time
startup writes?

**Preliminary findings (from 246 §13 research):**

48 `System.setProperty` calls across 8 files. Most are startup-only. Three
patterns are runtime communication:

| Channel | Writer(s) | Timing | Reader(s) | Risk |
|---------|-----------|--------|-----------|------|
| `justsearch.policy.gpuAccelerationEnabled` | `EnterprisePolicyService.snapshot()`, `RuntimeActivationService.runActivate()` | Runtime (policy load, activation) | `AppFacadeBootstrap`, `InferenceConfig`, worker GPU gates | **High** — policy changes during execution |
| `justsearch.server.exe` | `RuntimeActivationService.forceServerExeSysProp()`, `rollback()` | Runtime (GPU variant switch) | `InferenceConfig` | **High** — variant switches mid-session |
| `justsearch.model.path` | `AiInstallService`, `AiPackImportService` | Runtime (after model download/import) | Worker spawn, embed model resolution | **Medium** — model changes after install |

**Findings:**

- [x] Does `SettingsController.rebuildConfigStore()` re-contribute these keys
      when settings change? **NO — critical gap.** `rebuildConfigStore()` calls
      `contributeEnvRegistry()` + `contributeUiSettings()` but NOT
      `contributeYaml()`. YAML values (ordinal 200) and policy values are
      invisible on rebuild. Runtime sysprop writes by policy/activation services
      are picked up only if the key is an EnvRegistry entry (EnvRegistry reads
      live sysprops). Policy keys like `gpuAccelerationEnabled` and
      `disallowExternalInferenceServers` are NOT EnvRegistry entries — they are
      invisible to ConfigStore entirely.
- [x] Do the runtime writers need to call `configStore.update()`? **Yes, but
      none do today.** `EnterprisePolicyService.snapshot()`,
      `RuntimeActivationService.runActivate()/forceServerExeSysProp()`, and
      `AiInstallService.maybeApplyEmbeddingModelSysProp()` all write via
      `System.setProperty()` and never touch ConfigStore. Triggering mechanism
      would need to be: activation/policy events call a new `configStore.rebuild()`
      method, or the writers call `configStore.update()` directly.
- [x] Channel-by-channel write→read trace:
  - **Channel 1 (Policy):** `EnterprisePolicyService.snapshot()` writes
    `gpuAccelerationEnabled` and `disallowExternalInferenceServers` at runtime
    (policy load). Readers: `LlamaServerOps`, `InferenceConfig`, worker GPU
    gates. ConfigStore does NOT capture these — they are not EnvRegistry entries
    and `contributeYaml()` is not called on rebuild. **Migration target:**
    ConfigStore needs a new `contributePolicy()` method, or these keys must
    be added to EnvRegistry.
  - **Channel 2 (Server EXE):** `RuntimeActivationService` writes
    `justsearch.server.exe` during GPU variant switch. `InferenceConfig` reads
    it. ConfigStore captures it via EnvRegistry but goes **stale** after
    activation because no rebuild is triggered. **Migration target:**
    `RuntimeActivationService` must trigger a ConfigStore rebuild after
    activation.
  - **Channel 3 (Model Path):** `AiInstallService`/`AiPackImportService` write
    `justsearch.model.path` after model download/import. Workers read it at
    spawn time. ConfigStore captures it via EnvRegistry but goes stale.
    **Migration target:** Install services must trigger ConfigStore rebuild
    after model install.
- [x] Source companion properties: `AiInstallService.maybeApplyEmbeddingModelSysProp()`
      and `AiPackImportService.maybeApplyEmbeddingModelSysProp()` use the
      `setSysPropIfBlankWithSource` pattern with `*.source` companions. These
      check whether the current value was set by a higher-priority source before
      overwriting. ConfigStore ordinal semantics (300 for settings, 500 for
      operator override) replicate this correctly — a value at ordinal 500
      will not be overridden by a contribute at ordinal 300.

### Investigation 2: Dynamic EnvRegistry Reads

**Question:** Which `EnvRegistry.*.get()` calls happen per-request (not
cached at startup), and can they safely switch to `configStore.get()`?

**Preliminary findings (from 246 §13 research):**

6 dynamic call sites out of 113 total:

| File | Method | Entry | Call Frequency |
|------|--------|-------|----------------|
| `RagStreamingHandler` | `getRagTopK()` | `RAG_TOP_K` | Per RAG request |
| `RagStreamingHandler` | `getCitationMatchThreshold()` | `CITATION_MATCH_THRESHOLD` | Per RAG request |
| `VramFlagsUtil` | `threshold12gb()` | `VRAM_THRESHOLD_12GB` | Per GPU status probe |
| `VramFlagsUtil` | `threshold8gb()` | `VRAM_THRESHOLD_8GB` | Per GPU status probe |
| `VramFlagsUtil` | `threshold4gb()` | `VRAM_THRESHOLD_4GB` | Per GPU status probe |
| `OnlineAiServiceImpl` | `applyOverrides()` | `SERVER_EXE` | Per runtime override |

107 call sites are startup-only (static initializers, constructors, `main()`,
`fromEnvironment()` factories).

**Findings:**

- [x] 5 of 6 are uncached code patterns reading effectively constant values.
      Only `SERVER_EXE` is genuinely dynamic.

| Call Site | Key | Changes at Runtime? | GUI-Settable? | Writer? |
|-----------|-----|---------------------|---------------|---------|
| `getRagTopK()` | `RAG_TOP_K` | No | No | Tests only |
| `getCitationMatchThreshold()` | `CITATION_MATCH_THRESHOLD` | No | No | None |
| `threshold12gb()` | `VRAM_THRESHOLD_12GB` | No | No | Tests only |
| `threshold8gb()` | `VRAM_THRESHOLD_8GB` | No | No | Tests only |
| `threshold4gb()` | `VRAM_THRESHOLD_4GB` | No | No | Tests only |
| `applyOverrides()` | `SERVER_EXE` | **Yes** | **Yes** | `SettingsController`, `RuntimeActivationService` |

- [x] `VramFlagsUtil` thresholds: env-var-only tuning knobs. Zero production
      `System.setProperty` writers. Test-only usage with immediate cleanup.
- [x] `RagStreamingHandler` top-k/threshold: operator-only env var overrides.
      Not GUI-settable. No SettingsController path, no UI settings field.
- [x] For `SERVER_EXE` (the only genuinely dynamic read):
      `SettingsController.maybeApplyServerExeSysProp()` writes it when the user
      saves UI settings. `RuntimeActivationService` reads/sets/clears it during
      activation and rollback. `applyOverrides()` is called when the user
      triggers runtime config changes — must read live value at call time.
      `configStore.get()` would work IF a ConfigStore rebuild is triggered
      after each settings save and activation event.

**Migration implication:** The 5 static reads can safely move to constructor-time
caching or `configStore.get()` (both work since value never changes). The
`SERVER_EXE` read requires ConfigStore rebuild triggers after settings save
and activation — same prerequisite as Investigation 1 Channel 2.

### Investigation 3: Factory Class Logic Audit

**Question:** What validation, clamping, default computation, and error
handling exists in each of the 9 `RuntimeConfig` factory classes that
`ResolvedConfigBuilder` must replicate?

**Scope:** 88 methods across 9 factory classes. 246's H3 fix added clamping
for RAG, HybridSearch, Index, and Ports. The remaining factories haven't
been audited.

**Findings:**

12 non-trivial logic items NOT replicated in `ResolvedConfigBuilder`:

| # | Factory | Logic | Impact |
|---|---------|-------|--------|
| 1 | `PolicyConfig` | `SchemaMismatchPolicy` conditional default: production=`FAIL_CLOSED`, dev=`REBUILD_BACKUP_FIRST` | **High** — wrong default silently changes index corruption behavior |
| 2 | `PolicyConfig` | `gpuAccelerationEnabled` not in builder (not an EnvRegistry entry) | **High** — policy channel invisible to ConfigStore |
| 3 | `SearchConfig` | `validate()` — 10 post-resolution consistency checks (pit_ttl_ms>0, max_edit_distance∈[0,2], etc.) | **Medium** — builder has no post-build validation |
| 4 | `SearchConfig` | `searchPipelineProfile` uses `resolveWithConflictLog` giving YAML highest priority | **High** — builder reverses this (env wins via ordinal 400>200) |
| 5 | `IndexConfig` | `index.commit.policy` validation (only "per_batch" or "deferred" allowed) | **Low** — invalid values cause silent fallback |
| 6 | `IndexConfig` | `index.collections` complex nested parsing (list of collection objects) | **Medium** — not in builder at all |
| 7 | `WorkerConfig` | `workers.ai.*`, `workers.indexer.*` composite DTOs (WorkerAiConfig, WorkerIndexerConfig) | **Medium** — not in builder |
| 8 | `WorkerConfig` | `translator.health.*` composite DTO | **Low** — health check config |
| 9 | `InfraConfig` | `infra.health.*` entire config section not in builder | **Low** — health check tuning |
| 10 | `RagConfig` | `rag.retrieve.mode` needs `.toLowerCase(Locale.ROOT)` normalization | **Low** — case sensitivity |
| 11 | `RagConfig` | `rag.diversify.mode` needs `.toLowerCase(Locale.ROOT)` normalization | **Low** — case sensitivity |
| 12 | `SearchConfig` | `search.paging.strategy` needs `.toLowerCase(Locale.ROOT)` normalization | **Low** — case sensitivity |

**Default value mismatches (2) — FIXED in P0-A:**
- `LLM_ENABLED`: builder default aligned to `true` (was `false`)
- `LLM_MODE`: builder default aligned to `"remote"` (was `"local"`)

**Factory-by-factory status:**
- [x] `RuntimePolicyConfigFactory` — 2 gaps: conditional default (#1), gpuAccel (#2)
- [x] `RuntimeHybridSearchConfigFactory` — covered by builder (H3 fix added clamping)
- [x] `RuntimeRagConfigFactory` — 2 gaps: missing normalizations (#10, #11)
- [x] `RuntimeIndexConfigFactory` — 2 gaps: commit policy validation (#5), collections (#6)
- [x] `RuntimeWorkerConfigFactory` — 2 gaps: composite DTOs (#7, #8)
- [x] `RuntimeInfraConfigFactory` — 1 gap: infra.health section (#9)
- [x] `RuntimeSearchConfigFactory` — 3 gaps: validate() (#3), profile precedence (#4), normalization (#12)
- [x] `RuntimeWatcherConfigFactory` — fully covered by builder
- [x] `RuntimeOcrConfigFactory` — fully covered by builder

### Investigation 4: Worker-Side Resolution Path

**Question:** How do the Worker's own `EnvRegistry` reads, `RuntimeConfig`
factory reads, and the Head-supplied config snapshot interact? What happens
when a Worker-side caller migrates to `ResolvedConfig` snapshot reads?

**Preliminary concern:** The Worker loads the Head's config snapshot at ordinal
450 (H2 fix). But `WorkerConfig.java` has 2 `EnvRegistry` calls and
`EmbeddingService.java` has 3. If these migrate to `ResolvedConfig`, they'd
read from the snapshot — which was taken at Head startup, before any settings
changes. Is this correct, or do the Worker-side reads need live env var values?

**Findings:**

- [x] `WorkerConfig.load()` calls `RuntimeConfig.load()` + direct EnvRegistry
      reads. It completely ignores ConfigStore. The Worker builds its own
      ConfigStore from the Head's snapshot (ordinal 450) but no operational
      code reads from it. The ConfigStore is built and discarded.
- [x] Worker-side EnvRegistry reads vs. Head-forwarded values:
  - `WorkerConfig.java` has 2 EnvRegistry calls: `WORKER_PORT` and `DATA_DIR`.
    Both are forwarded by the Head as `-D` sysprops on the Worker command line.
    The Worker reads them via EnvRegistry which checks sysprops first — so the
    Head-forwarded values win. These overlap with snapshot values, but since
    sysprop ordinal (500) > snapshot ordinal (450), the sysprop wins.
  - `EmbeddingService.java` has 3 EnvRegistry calls for embedding model config.
    These are also forwarded as `-D` sysprops by `WorkerSpawner`.
  - `KnowledgeServer.java` has 1 EnvRegistry call for a server config key.
- [x] `toWorkerSnapshot()` serializes ALL resolved keys (not a curated subset).
      The snapshot is a complete copy of the Head's `ResolvedConfig` at the time
      the Worker was spawned.
- [x] Intended end state: Worker should read ALL config from the snapshot
      (ordinal 450) with sysprop overrides at ordinal 500 for the few keys the
      Head explicitly forwards. The Worker should NOT resolve config independently
      via `RuntimeConfig.load()`.
- [x] Timing windows: Yes. If settings change after a Worker spawns, the
      Worker's snapshot is stale. The Head would need to respawn Workers or
      push config updates over gRPC for live settings changes. Currently, the
      Worker just reads stale values — this is an existing limitation, not a
      migration regression.
- [x] **ai-worker is a completely separate process** with 30+ EnvRegistry calls
      and NO snapshot support. It is launched by `llama-server` management code,
      not by `WorkerSpawner`. The ai-worker has no ConfigStore and no concept of
      Head snapshots. This is a separate migration concern from the indexer
      Worker.

### Investigation 5: Integration Test Design

**Question:** What would a meaningful end-to-end test look like that verifies
ResolvedConfig and the old resolution system produce the same values?

**Findings:**

- [x] **Yes, parity test is feasible.** Structure: same YAML string → both
      builders → assert field-by-field. `TestConfigHelper.fromYaml(String)`
      already exists for the RuntimeConfig side. `ResolvedConfigBuilder` takes
      a `JsonNode`. No file I/O needed.
- [x] **Test inputs:** Inline YAML string (parsed via `ObjectMapper(YAMLFactory)`).
      Sysprops controlled via `withSysProp` pattern (from `EnvRegistryTest`).
      No settings.json needed (old path has no such concept). No live server.
- [x] **3 divergences identified, 2 fixed:**

| Key | Old (RuntimeConfig) | New (ResolvedConfig) | Status |
|-----|---------------------|----------------------|--------|
| `searchPipelineProfile` | YAML wins (`resolveWithConflictLog`) | env/sysprop wins (ordinal 400>200) | **Open** — deliberate 12-factor design |
| `llmEnabled` default | `true` | `false` | **Fixed** in P0-A |
| `llmMode` default | `"remote"` | `"local"` | **Fixed** in P0-A |

- [x] **Pure unit test, no live server.** Neither path touches Lucene or
      network. `TestConfigHelper.fromYaml(String)` constructs RuntimeConfig
      from an inline YAML string. `contributeEnvRegistry()` reads sysprops only.
      The existing `parityWithEnvRegistryGet()` test (line 298 of
      `ResolvedConfigBuilderTest`) proves this works.
- [x] **One comprehensive test class** with `@Nested` groups: `YamlKeysParity`
      (asserts convergence for YAML-only keys), `KnownDivergences` (documents
      the 3 expected divergences with explicit assertions), `EnvRegistryParity`
      (enhances existing test). Do not split per-module — the parity concern
      is holistic.

### Investigation 6: Dual-Registration Key Conflicts

**Question:** The 5 keys registered in both `EnvRegistry` and factory inline
literals — what happens during partial migration when some callers use the
old path and some use `ResolvedConfig`?

**Keys:** `EGRESS_BLOCK_ALL`, `LLM_ENABLED`, `LLM_MODEL_PATH`, `LLM_MODE`,
`SEARCH_PROFILE`.

**Findings:**

- [x] Caller mapping per key:

| Key | EnvRegistry callers | Factory callers | ResolvedConfig callers |
|-----|---------------------|-----------------|------------------------|
| `EGRESS_BLOCK_ALL` | `AppFacadeBootstrap` (startup) | `RuntimePolicyConfigFactory` | None |
| `LLM_ENABLED` | `InferenceConfig` (startup), `AppFacadeBootstrap` (startup) | `RuntimePolicyConfigFactory` | `AiTranslatorFactory` (Phase 4B) |
| `LLM_MODEL_PATH` | `InferenceConfig` (startup) | `RuntimePolicyConfigFactory` | None |
| `LLM_MODE` | `InferenceConfig` (startup) | `RuntimePolicyConfigFactory` | `AiTranslatorFactory` (Phase 4B) |
| `SEARCH_PROFILE` | `SearchOrchestrator` (startup) | `RuntimeSearchConfigFactory` (`resolveWithConflictLog`) | None |

  Post-Phase-4: `LLM_ENABLED` and `LLM_MODE` are now read from ResolvedConfig
  by `AiTranslatorFactory`. The split-brain risk for these 2 keys is mitigated
  because the builder defaults were aligned in P0-A.

- [x] **Split-brain risk during partial migration:**
  - `EGRESS_BLOCK_ALL`: Low risk. EnvRegistry and builder both read the same
    sysprop/env. Default is `false` in both. They see the same value unless
    YAML is involved — and `contributeYaml()` is dead code in production.
  - `LLM_ENABLED`: **HIGH risk.** Factory default = `true`, builder default =
    `false`. If no YAML/env/sysprop sets this key, EnvRegistry returns `true`
    and ResolvedConfig returns `false`. A migrated caller would see LLM disabled
    while a non-migrated caller sees LLM enabled.
  - `LLM_MODE`: **MEDIUM risk.** Factory default = `"remote"`, builder default
    = `"local"`. Same split scenario as LLM_ENABLED.
  - `LLM_MODEL_PATH`: Low risk. Both read same sysprop. No default mismatch.
  - `SEARCH_PROFILE`: **HIGH risk.** Factory uses YAML-wins precedence;
    builder uses ordinal chain (env wins). If both YAML and env/sysprop set
    this key, they produce different values.

- [x] **Critical finding (FIXED):** `contributeYaml()` was never called in
      `HeadlessApp.main()` — fixed in P0-B. YAML values are now contributed
      at ordinal 200. The `SEARCH_PROFILE` precedence divergence (YAML-wins
      in old system vs env-wins in new) remains an open design question.

- [x] **Migration ordering to avoid split-brain:**
  1. ~~Fix defaults first~~ — **Done** (P0-A).
  2. ~~Wire `contributeYaml()`~~ — **Done** (P0-B).
  3. Migrate all callers of a given key atomically (same commit) to prevent
     split-brain windows.
  4. `EGRESS_BLOCK_ALL` and `LLM_MODEL_PATH` can be migrated in any order
     (no default/precedence mismatch).

---

## Migration Strategy (Revised After Investigation)

The investigations revealed 3 prerequisite fixes and 4 structural gaps that
must be addressed before the consumer migration can safely proceed.

### Phase 0: Prerequisites — COMPLETE

All Phase 0 items are implemented, tested, and compiling on branch
`feat/246-eval-centralization`.

**P0-A: Fix default value mismatches.** ✅
- `LLM_ENABLED`: builder default changed `false` → `true`
- `LLM_MODE`: builder default changed `"local"` → `"remote"`
- Tests updated in `ResolvedConfigBuilderTest.defaultsUsed()`

**P0-B: Wire `contributeYaml()` into HeadlessApp startup.** ✅
- `HeadlessApp.main()`: added `rcBuilder.contributeYaml(RuntimeConfig.load().root())`
  between `contributeEnvRegistry()` and `contributeUiSettings()`
- `SettingsController.rebuildConfigStore()`: same call added for rebuild path
- Both wrapped in try-catch for missing YAML (valid in some deployment modes)

**P0-C: ConfigStore rebuild triggers for runtime channels.** ✅
Implemented in Phase 3. `ConfigStoreRebuilder` utility extracted from
`SettingsController`, rebuild triggers added to `RuntimeActivationService`,
`AiInstallService`, and `AiPackImportService`. Policy channel confirmed NOT
stale (readers use direct `System.getProperty()`, not ConfigStore).

**P0-D: Replicate missing factory logic items.** ✅
- `SchemaMismatchPolicy` conditional default (prod=FAIL_CLOSED, dev=REBUILD_BACKUP_FIRST)
  with full alias normalization matching `SchemaMismatchPolicy.from()` (e.g.,
  "rebuild" → "REBUILD_BACKUP_FIRST", "fail-closed" → "FAIL_CLOSED")
- `resolveStringLower()` helper for `rag.retrieve.mode`, `rag.diversify.mode`
- `putYamlFromNodeLower()` for `search.paging.strategy`
- `putYamlIntClampedFromNode()` for `search.corrections.max_edit_distance` ∈ [0,2]
- `putYamlLongClampedFromNode()` for `search.paging.pit_ttl_ms` ≥ 1
- `resolveString()` now trims whitespace (matches `resolveInt`/`Long`/`Double`)
- Remaining items (composite DTOs, health config) deferred — no consumers yet

**P0-E: Build parity test suite.** ✅
`ConfigParityTest.java` with 5 `@Nested` groups:
- `YamlKeysParity`: 6 tests covering hybrid search (7 fields), RAG (8 fields),
  worker limits (4 fields), watcher (2 fields), index vector (5 fields),
  OCR (7 fields) — all with non-default YAML values
- `KnownDivergences`: LLM defaults convergence, LLM YAML convergence,
  SchemaMismatchPolicy default, SchemaMismatchPolicy alias normalization
- `DefaultConvergence`: RAG/HybridSearch/Worker defaults match across systems
- `EnvRegistryParity`: all entries match (documented limitation: vacuous in
  clean environments)
- `EnvRegistrySubRecordSpotChecks` (added in H4): 8 tests covering Llm
  (deadlineMs, gpuLayers), Agent (searchDefaultLimit, contextCompressionEnabled),
  Summary (pipeline, maxTokens), Translator (pipelineIntent, repoRoot) —
  verifies sysprop→record-field pipeline end-to-end

**P0-F: Additional fixes from parity gap analysis.** ✅
Critical analysis after initial Phase 0 implementation identified and fixed:
- `resolveString()` missing `.trim()` — all numeric resolvers trimmed but
  strings didn't; fixed globally (affects llmMode, watcher strategy, etc.)
- `schemaMismatchPolicy` alias normalization gap — factory accepted aliases
  like "rebuild" but builder stored raw string; added `normalizeSchemaMismatchPolicy()`
- `exclude_patterns` missing from `contributeUiSettings()` — settings were
  set via sysprop but not contributed to ordinal chain; added JSON serialization
- Exception type inconsistency — `rebuildConfigStore()` caught `RuntimeException`
  while `HeadlessApp` caught `Exception`; unified to `Exception`

**Dead code removed:** ✅
- Worker `ConfigStore` field and accessor removed from `IndexerWorker`
  (the `ConfigStore.setGlobal()` call in `main()` remains — it is NOT dead
  code; Worker-side consumers like `EmbeddingService`, `KnowledgeServer`,
  and `WorkerConfig` read from it via `ConfigStore.globalOrNull()`)
- Unused `putYamlLongFromNode()` helper (replaced by clamped version)

### Phase 1: Startup-only reads (107 call sites, low risk) — COMPLETE ✅

All startup-only `EnvRegistry.*.get()` and direct `System.getProperty()` reads
migrated to `ConfigStore.globalOrNull()` or `ConfigStore.global()` accessors.
~148 call sites across ~35 files migrated, batched by module.

**Modules migrated (10 steps):**

| Step | Module | Key Changes |
|------|--------|-------------|
| 0 | `configuration` | `ConfigStore.globalOrNull()`/`global()` accessors, `clearGlobal()` for test isolation |
| 1 | `app-services` | `AppFacadeBootstrap`, `WorkerSpawner` |
| 2 | `app-inference` | `InferenceConfig`, `InferenceLifecycleManager`, `LlamaServerOps`, `ServerPropsOps` |
| 3 | `ai-bridge` | `VramFlagsUtil`, `TranslatorAssets` |
| 4 | `app-agent` | `AgentLoopService`, `SearchTool`, `BrowseTool` |
| 5 | `app-ai` | `LocalAiTranslatorService` (major refactor: 18 EnvRegistry calls → single `ResolvedConfig` read) |
| 6 | `indexer-worker` | `IndexerWorker`, `WorkerConfig`, `EmbeddingService`, `KnowledgeServer` |
| 7 | `reranker` | `CrossEncoderReranker`, `OnnxModelDiscovery` |
| 8 | `ui` | `HeadlessApp`, `LocalApiServer`, `SettingsController`, `RagStreamingHandler`, `DebugStateController`, `EnterprisePolicyService`, `RuntimeActivationService`, `AiInstallService`, `AiPackImportService` |
| 9 | `adapters-lucene` | `IndexMetadataParityGuard` |

**New sub-records added to `ResolvedConfig`:**

| Record | Fields | Source Keys |
|--------|--------|-------------|
| `Llm` | 35 fields | `justsearch.llm.*` — sampling, VRAM management, deadlines, templates, remote config |
| `Agent` | 7 fields | `justsearch.agent.*` — search/browse limits, context compression |
| `Summary` | 7 fields | `justsearch.summary.*` — pipeline, limits, execution config |
| `Translator` | 4 fields | `justsearch.translator.*` — pipeline identifiers, model asset root |

**Test infrastructure:**
- `TestResolvedConfigHelper.storeFromEnvironment()` / `storeWithDefaults()` for test setup
- `TestResolvedConfigHelper.restoreGlobal()` for proper null-safe teardown
- `testFixtures` dependency added to 8 modules' `build.gradle.kts`

### Phase 2: Dynamic reads (5 static + 1 dynamic, low→medium risk) — COMPLETE ✅

All 5 effectively-static dynamic reads migrated:

| Call Site | Key | Migration |
|-----------|-----|-----------|
| `RagStreamingHandler.getRagTopK()` | `RAG_TOP_K` | `ConfigStore.globalOrNull()` with null-safe fallback |
| `RagStreamingHandler.getCitationMatchThreshold()` | `CITATION_MATCH_THRESHOLD` | Same pattern |
| `VramFlagsUtil.threshold12gb()` | `VRAM_THRESHOLD_12GB` | `aiOrNull()` helper → `ResolvedConfig.Ai` accessor |
| `VramFlagsUtil.threshold8gb()` | `VRAM_THRESHOLD_8GB` | Same |
| `VramFlagsUtil.threshold4gb()` | `VRAM_THRESHOLD_4GB` | Same |

The 1 genuinely dynamic read (`SERVER_EXE` in `OnlineAiServiceImpl`) remains
on EnvRegistry. It requires Phase 0-C ConfigStore rebuild triggers to be in
place first.

### Post-Migration Hardening — COMPLETE ✅

Critical review after Phase 1-2 identified 4 issues. All resolved:

**H1: ConfigStore test isolation (`clearGlobal` + `restoreGlobal`).**
Tests saving `ConfigStore.globalOrNull()` before setup couldn't restore to the
uninitialized state when `prev` was null. Added package-private
`ConfigStore.clearGlobal()` and `TestResolvedConfigHelper.restoreGlobal(prev)`
which dispatches to `setGlobal(prev)` or `clearGlobal()`. Updated all restore
sites across 9 test files. Added to ArchUnit frozen violations store
(only referenced from testFixtures, invisible to production class scan).

**H2: `DebugStateController` defensive `globalOrNull()`.**
Line 138 used `ConfigStore.global()` inside an HTTP handler. While safe in
normal startup (API starts after `setGlobal()`), changed to `globalOrNull()`
for shutdown/restart race safety. All other `global()` call sites are provably
post-init and don't need changing.

**H3: DRY up repeated `globalOrNull()` patterns.**
- `VramFlagsUtil`: extracted `aiOrNull()` helper, eliminating 3 identical
  `ConfigStore.globalOrNull()` + null-check sequences.
- `OnnxModelDiscovery`: unified 2 separate `ConfigStore.globalOrNull()` reads
  into a single read at `resolve()` entry. Also eliminated an unnecessary
  `Path→String→Path` round-trip on the sidecar path. Removed the now-inlined
  `resolveBaseDir()` method.

**H4: `ConfigParityTest` coverage for new sub-records.**
Added 8 spot-check tests across Llm (2), Agent (2), Summary (2), and
Translator (2) sub-records. Each sets a system property, builds via
`contributeEnvRegistry()`, and verifies the value appears in the correct
record field. Confirms the key-mapping pipeline works end-to-end.

### Phase 3: Runtime communication channels — COMPLETE ✅

ConfigStore rebuild triggers implemented via `ConfigStoreRebuilder` utility:

| Channel | Implementation | Status |
|---------|---------------|--------|
| Server EXE | `RuntimeActivationService` calls `ConfigStoreRebuilder.rebuild()` after activation + rollback | ✅ |
| Model Path | `AiInstallService` and `AiPackImportService` call rebuild after model install/import | ✅ |
| Policy | Readers use direct `System.getProperty()`, NOT ConfigStore — no staleness | N/A |

### Phase 4: RuntimeConfig.load() consumer migration — COMPLETE ✅

Migrated all feasible consumers. RuntimeConfig retained for complex composite
types (`indexSort`, `boosts`, `collections`, `workerAi`, `translatorHealth`)
and structural YAML sources. Factory class deletion deferred — requires
migrating composite types first (separate tempdoc).

### Phase 5: EnvRegistry usage trim — COMPLETE ✅

Migrated 1 feasible call site. EnvRegistry `.get()` methods are permanent
infrastructure (see "Permanent EnvRegistry boundary" below).

### Worker and AI-Worker considerations

**Indexer Worker:** Receives Head snapshot (ordinal 450) and sets up
`ConfigStore.setGlobal()` in `IndexerWorker.main()`. Worker-side consumers
(`EmbeddingService`, `KnowledgeServer`, `WorkerConfig`) use a mix of
`ConfigStore.globalOrNull()`, `RuntimeConfig.load()`, and direct EnvRegistry
reads. `WorkerConfig.load()` and `KnowledgeServer` still call
`RuntimeConfig.load()` for complex composite types (kept in Phase 4D).
Stale snapshots after settings changes are an existing limitation — fixing
requires Worker respawn or gRPC config push (out of scope).

**AI-Worker:** Completely separate process with 30+ EnvRegistry calls and NO
snapshot support. Migration is a separate concern — the AI-Worker needs its own
`contributeEnvRegistry()` at minimum. Consider a dedicated tempdoc.

---

## Verification Strategy

After each migration batch:
1. `./gradlew.bat spotlessApply`
2. `./gradlew.bat build -x test` (compilation)
3. `./gradlew.bat :modules:<module>:test` (affected module)
4. After all batches: `./gradlew.bat test -x integrationTest`
5. Parity test (P0-E) must pass at every stage — asserts old and new systems
   produce the same values for all covered keys.

**Phase 1-2 verification (completed):**
- Full test suite passes (`./gradlew.bat test -x integrationTest -x :modules:infra-core:test -x :modules:ai-bridge:test -x :modules:system-tests:test`)
- Excluded test failures are pre-existing: `AbiAuditTest` (requires native bridge library), `NastyCorpusTest` (system-tests), `infra-core` (unrelated)
- No remaining `if (prev != null) ConfigStore.setGlobal(prev)` patterns (grep-verified)
- All `ConfigStore.setGlobal()` calls are legitimate: production init points (`HeadlessApp`, `IndexerWorker`), test fixture (`TestResolvedConfigHelper`), or standalone probe (`LocalAiTranslatorServiceProbeMain`)

---

## Open Questions (Updated)

- ~~**P0-B scope:**~~ **Resolved.** `contributeYaml()` is now wired into both
  `HeadlessApp.main()` and `SettingsController.rebuildConfigStore()`. YAML is
  re-parsed on rebuild (wasteful but correct — YAML doesn't change at runtime).
  Caching the `JsonNode` root is a future optimization.
- ~~**Policy channel architecture:**~~ **Resolved (Phase 3).** Policy channel
  readers (`LlamaServerOps`) use direct `System.getProperty()`, not ConfigStore.
  No staleness exists. The policy keys (`gpuAccelerationEnabled`,
  `disallowExternalInferenceServers`) remain as ad-hoc sysprops — they don't
  need EnvRegistry or `contributePolicy()` because their readers bypass
  ConfigStore entirely.
- **AI-Worker migration:** Should this be tracked here or in a separate tempdoc?
  The AI-Worker has a completely different process model and config resolution
  path.
- **`searchPipelineProfile` precedence:** The old system intentionally makes
  YAML win over env vars. The new system reverses this. Is the new behavior
  correct (12-factor: env wins), or must the old YAML-wins behavior be
  preserved for backward compatibility?
- **System property pollution / ordinal provenance:** `setSysPropIfBlankWithSource`
  calls in HeadlessApp (7 call sites) set system properties before
  `contributeEnvRegistry()` runs, causing UI settings values to appear at
  ordinal 500 (jvm_arg) instead of ordinal 300 (settings). Provenance trace
  in `/api/effective-config` is misleading but values are correct. Phase 3
  rebuild triggers are now in place; removing the sysprop bridge is feasible
  as future cleanup work.

## Summary — All Phases Complete

All 6 phases (0-5) are now implemented.

### Phase 3: ConfigStore rebuild triggers (completed)
- Extracted `ConfigStoreRebuilder` utility from `SettingsController`
- Added rebuild triggers to `RuntimeActivationService` (after activation + rollback)
- Added rebuild triggers to `AiInstallService` and `AiPackImportService`
- Policy channel confirmed NOT stale (readers use direct `System.getProperty()`)

### Phase 4: RuntimeConfig.load() consumer migration (completed)
- **adapters-lucene** (16 sites): Migrated to ResolvedConfig. RuntimeConfig kept
  only for `indexSort()` and `indexBasePath()` fallback (complex composite types).
- **app-ai** (2 sites): `AiTranslatorFactory` migrated to ConfigStore for
  `llmEnabled`/`llmMode`. `AiClientConfig` kept on RuntimeConfig (complex
  `workerAi`/`translatorHealth` DTOs).
- **app-config/app-indexing/app-services**: Kept on RuntimeConfig (complex
  `collections` type, `WatcherSettings` composite).
- **indexer-worker**: Kept on RuntimeConfig (complex `workerIndexer` DTO,
  `migrationCutoverMaxFailedJobs`, `schemaMismatchPolicy`).
- **ai-worker**: Kept (separate JVM, no ConfigStore).
- **benchmarks**: Kept (low priority, not production).
- **Structural YAML sources** (HeadlessApp, SettingsController): Kept —
  `RuntimeConfig.load().root()` is the YAML parsing bridge.

### Phase 5: EnvRegistry usage trim (completed)
- `EffectiveConfigController.keyAiDisabled()`: Migrated `getBoolean()` to
  ConfigStore with EnvRegistry fallback.
- `BudgetProfiles`: Left as-is — bootstrap path infrastructure, ConfigStore
  depends on these paths.

### Permanent EnvRegistry boundary

`EnvRegistry` **cannot be reduced to a source-definition-only enum**. The
`.get()` / `.getInt()` / `.getPath()` / `.getBoolean()` methods are permanent
infrastructure for these categories:

| Category | Examples | Reason |
|----------|----------|--------|
| Bootstrap paths | `REPO_ROOT`, `SSOT_PATH`, `DATA_DIR` in `RepoRootLocator`, `JustSearchConfigurationLoader`, `BudgetProfiles`, `PlatformPaths`, `RuntimeDataDirResolver` | Run before ConfigStore exists; ConfigStore depends on them |
| ConfigStore bridge | `ResolvedConfigBuilder.contributeEnvRegistry()` | IS the mechanism that reads EnvRegistry into ConfigStore |
| AI-Worker process | `WorkerConfig`, `LlmSettings` (~40 calls) | Separate JVM with no ConfigStore |
| App launcher | `Launcher`, `LauncherBootstrap`, `SmokeDriver` | Run before ConfigStore init |
| UI settings bootstrap | `UiSettingsStore` | Constructs before ConfigStore init |
| Config introspection | `EffectiveConfigController` (sysprop/envVar display) | Shows raw sources for diagnostics |

Removing `.get()` methods from `EnvRegistry` would require:
1. Moving AI-Worker to its own config system (dedicated tempdoc)
2. Extracting bootstrap path resolution from EnvRegistry into a lower-level utility
3. Both are out of scope for this migration.

---

## Critical Analysis of Implemented Changes

Post-implementation audit of all Phase 3-5 changes. Issues ordered by severity.

### CRITICAL — Fixed

**C1: Schema mismatch policy case mismatch (Phase 4A)**

`LuceneIndexRuntime.start()` compared `idx.schemaMismatchPolicy()` with the
lowercase literal `"rebuild_backup_first"`. But `ResolvedConfigBuilder.
normalizeSchemaMismatchPolicy()` returns UPPERCASE canonical names
(`"REBUILD_BACKUP_FIRST"`, `"FAIL_CLOSED"`). The comparison always failed,
meaning schema mismatch recovery **never triggered** — the system always fell
through to `FAIL_CLOSED`.

**Fix applied:** Changed to `"REBUILD_BACKUP_FIRST".equalsIgnoreCase(policy)`.

**Root cause:** The old code compared against `RuntimeConfig.SchemaMismatchPolicy`
enum values. When migrating to string-based config, the casing of the
normalized output wasn't matched to the comparison literal. No unit test
covers this path in `adapters-lucene` — the only test is the integration test
`SchemaMismatchStatusContractTest`, which was already failing for unrelated
infrastructure reasons (Worker startup timeout).

### HIGH — Fixed

The Phase 1 migration introduced 5 default value mismatches between
`ResolvedConfigBuilder` defaults and `LocalAiTranslatorService` consumer-local
defaults. The consumer uses `ifZero()` guards intended to apply domain-specific
defaults when the builder returns 0 (sentinel for "unset"), but the builder
returned non-zero defaults that bypassed the guards.

| Field | Builder (was) | Consumer guard | Old default | Fix applied |
|-------|--------------|----------------|-------------|-------------|
| `deadline_ms` | 30,000 | `ifZeroL(..., 900)` | 900 | Builder → 0 (guard fires) |
| `queue_capacity` | 10 | `ifZero(..., 16)` | 16 | Builder → 0 (guard fires) |
| `max_slots` | 1 | `ifZero(..., 2)` | 2 | Builder → 0 (guard fires) |
| `rng_seed` | -1 | (no guard) | 42 | Builder → 42 directly |
| `vram_auto_scale` | false | (no guard) | true | Builder → true directly |

**Fix:** For the 3 fields with `ifZero` guards, the builder default was changed
to 0 (sentinel), letting the consumer guard apply the domain-specific default.
For the 2 fields without guards, the builder default was changed to match the
old consumer default directly. This preserves backward-compatible behavior.

### MEDIUM — Open

**M1: `SsotCommitMetadataSource.resolvedConfigOrFallback()` rebuilds on every call**

When ConfigStore is null (tests, early startup), this method builds a full
`ResolvedConfig` from scratch — loading YAML, reading all env vars. It's
called from `build()` (once per index commit) and `similarityDescriptorFromConfig()`
(once per commit). In production with ConfigStore initialized, this is a
no-op. In tests, it causes repeated YAML parsing.

Not a correctness issue; minor performance concern in tests.

**M2: `HeadlessApp.maybeAutoSelectCuda12Variant` gpuLayers semantics changed**

Old code checked `EnvRegistry.GPU_LAYERS.get().orElse(null)` — only env/sysprop
explicitly set. New code checks `cs.get().ai().gpuLayers() != 0` — triggers
for ANY non-zero source (env, sysprop, YAML, UI settings). If YAML sets
`gpuLayers=4` but no env/sysprop, old code ignored it; new code picks it up.
Subtly broadens the override scope. **Phase 1 change, not Phase 3-5.**

**M3: `DebugStateController` `useThinking` fallback when ConfigStore is null — FIXED**

Old: `EnvRegistry.USE_THINKING.getBoolean(true)` — default `true`.
Was: `cs != null && cs.get().ai().useThinking()` — returned `false` when
ConfigStore is null (early startup).
Fix: Changed to `rc != null ? rc.ai().useThinking() : true` — correctly
defaults to `true` when ConfigStore is null, matching the builder default
and the old EnvRegistry behavior. **Phase 1 change, fixed in hardening.**

### LOW — Documented

**L1: `resolveConfigFallback()` was duplicate of `resolveFromConfigStoreOrBuild()`**

Fixed: `resolveConfigFallback()` now delegates to
`resolveFromConfigStoreOrBuild(RuntimeConfig.load())`.

**L2: Dead `ifZero` fallback constants in `HybridSearchOps`**

`DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL=10` and `DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL=0.3`
are now unreachable since the config supplier always returns a non-null
`ResolvedConfig` with builder defaults (3 and 0.25). These constants serve
only as documentation / safety net for hypothetical null config.

**L3: `ConfigStoreRebuilder.rebuild()` re-parses YAML on every call**

`RuntimeConfig.load()` is called on every rebuild. Under normal usage
(user-initiated settings changes, activation), this is rare. Acceptable.

**L4: `WorkerSpawner` `embedModel()` returns `Path` instead of raw string**

`Path.toString()` may normalize path separators differently on Windows.
Unlikely to cause issues since the path is used as an env var value.

### Test coverage assessment

| Change | Covered by tests? |
|--------|------------------|
| ConfigStoreRebuilder extraction | `ConfigStoreRebuilderTest` (3 tests: sysprop pickup, null safety, completeness) |
| RuntimeActivationService rebuild triggers | RuntimeActivationServiceTest (existing) |
| AiInstallService/AiPackImportService triggers | No direct test |
| adapters-lucene ResolvedConfig migration | 332 tests in adapters-lucene module |
| Schema mismatch policy normalization | `ResolvedConfigBuilderTest.SchemaMismatchPolicy` (7 tests: case variants, aliases, prod/dev defaults) |
| AiTranslatorFactory ConfigStore migration | No direct test |
| EffectiveConfigController ConfigStore migration | No direct test |
| WorkerSpawner globalOrNull fix | SchemaMismatchStatusContractTest (integration) |

### Architectural observations

**What went well:**
- The `runtimeConfigForSort` dual-field pattern in `LuceneIndexRuntime` cleanly
  separates migrated fields (ResolvedConfig) from unmigrated complex types
  (RuntimeConfig).
- ConfigStoreRebuilder extraction is clean and reusable.
- The fallback patterns (`ConfigStore.globalOrNull()` → build from RuntimeConfig)
  are consistent across all call sites.

**What could be better:**
- ~~The `ifZero` / `ifZeroL` pattern in `LocalAiTranslatorService` creates a
  false sense of backward compat.~~ **Resolved (HIGH fix).** Builder defaults
  changed to 0 (sentinel) for the 3 guarded fields (`deadline_ms`,
  `queue_capacity`, `max_slots`), letting the consumer guards apply the
  domain-specific defaults. The 2 unguarded fields (`rng_seed`,
  `vram_auto_scale`) had their builder defaults set to match the old consumer
  defaults directly.
- `IndexRuntimeFactory` still loads `RuntimeConfig.load()` in all 7 factory
  methods, even though the RuntimeConfig is only needed for `indexSort()` in
  the downstream `ComponentsFactory`. The factory methods could accept
  `ResolvedConfig` and defer RuntimeConfig loading to the narrowest scope.
- ~~No parity test covers the `schemaMismatchPolicy` string comparison path.~~
  **Resolved:** `ResolvedConfigBuilderTest.SchemaMismatchPolicy` (7 tests)
  covers all alias variants, case-insensitivity, and prod/dev defaults.
  `ConfigStoreRebuilderTest` (3 tests) covers the rebuild-after-sysprop-write
  scenario that Phase 3 was designed to fix.
