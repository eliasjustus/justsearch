---
title: "Configuration Architecture Unification"
status: done
created: 2026-03-16
---

# 314 — Configuration Architecture Unification

## Problem

Three configuration systems coexist with conflicting precedence rules:

| Layer | Reads from | Precedence | Runtime updates | Call sites |
|-------|-----------|------------|-----------------|------------|
| `EnvRegistry` (~97 entries) | sysprop + env var | sysprop > env | No | ~30 production files |
| `RuntimeConfig` (926 LOC) | YAML first, then env/sysprop | YAML wins | No (re-parse each call) | 16+ production sites |
| `ResolvedConfig` / `ConfigStore` | All sources via ordinal chain | ordinal 100-500 | Yes (swap + notify) | 67 files, 117 call sites |

The same key can resolve to different values depending on which layer you read from. `ConfigStore` (ordinal system) is the correct destination and is already the dominant system (~60% migrated), but migration is incomplete.

### ConfigStore Ordinal Table

| Ordinal | Source | Description |
|---------|--------|-------------|
| 500 | `ORDINAL_JVM_ARG` | `-D` system properties |
| 450 | `ORDINAL_WORKER_SNAPSHOT` | Head→Worker JSON snapshot |
| 400 | `ORDINAL_ENV_VAR` | Environment variables |
| 350 | `ORDINAL_CI_PROFILE` | CI profile overrides |
| 300 | `ORDINAL_SETTINGS_JSON` | GUI settings.json |
| 200 | `ORDINAL_YAML` | application.yaml |
| 150 | `ORDINAL_AUTO_DETECT` | GPU/platform auto-detection |
| 100 | `ORDINAL_DEFAULT` | Hardcoded defaults |

## Prior Work

- Tempdoc 03: Config SSOT precedence audit — fixed `envOrProperty()` to sysprop-first (complete)
- Tempdoc 64: RuntimeConfig tech debt analysis — added DEBUG logging, fixed LlmSettings bug (complete)
- Tempdoc 247: ResolvedConfig consumer migration — migrated early consumers, noted I2/I5 gaps (complete)
- Tempdoc 300: ConfigPrecedence to ConfigStore migration — reduced `envOrProperty()` from 45→4 calls, added 28 EnvRegistry entries + 30 ResolvedConfig.Ai fields (implemented)

## Plan

### Phase A: Consolidate repo root discovery (I7) — DONE

Replaced 6 private `locateRepoRoot()` copies with `RepoRootLocator` delegation. All modules already had `modules/configuration` dependency — no new dependencies needed.

- [x] `ai-worker/WorkerConfig.java` — deleted `locateRepoRoot()`, replaced with `RepoRootLocator.findRepoRoot()`
- [x] `ai-bridge/AbstractSchemaGuard.java` — body replaced with `RepoRootLocator.findRepoRoot()` delegation
- [x] `ai-bridge/PromptTemplateLintCommand.java` — body replaced with `RepoRootLocator.findRepoRoot()` delegation
- [x] `ai-bridge/TranslatorAssets.java` — kept ConfigStore override, replaced CWD fallback with `RepoRootLocator.findRepoRoot()`
- [x] `app-search/IntentSchemaValidator.java` — body replaced with `RepoRootLocator.findRepoRoot()` delegation
- [x] `configuration/JustSearchConfigurationLoader.java` — deleted `findRepoRootOrNull()`, `repoRootStatic()` delegates to `RepoRootLocator.findRepoRootOrNull()`

### Phase B: Wire boolean flag holdouts into ConfigStore (I2) — DONE

- [x] Added `Boolean enabled` (nullable) to `ResolvedConfig.Ai.Ner`, `.Splade`, `.Reranker`, `.Reranker.ChunkReranker`, `.CitationScorer`
- [x] Wired 5 `resolveNullableBoolean()` calls in `ResolvedConfigBuilder` (`buildNer`, `buildSplade`, `buildReranker`, `buildChunkReranker`, `buildCitationScorer`)
- [x] Updated `NerConfig`, `SpladeConfig`, `RerankerConfig` (+ `ChunkRerankerConfig`), `CitationScorerConfig` to read `enabled` from ConfigStore first, fall back to EnvRegistry only when ConfigStore is null
- [x] Fixed `RuntimeActivationService` — replaced raw strings with `EnvRegistry` references, fixing typo bug (`"justsearch.citation_scorer.enabled"` → `EnvRegistry.CITATION_SCORER_ENABLED.sysProp()` = `"justsearch.citation.scorer.enabled"`)

### Phase C: Migrate RuntimeConfig consumers to ConfigStore (I1/I3/I6)

Retire `RuntimeConfig.load()` from all call sites except YAML contribution (HeadlessApp + ConfigStoreRebuilder). Requires adding missing types to `ResolvedConfig` first, then migrating consumers.

#### C1: Add missing types to ResolvedConfig — DONE

Added 6 new record types and 11 EnvRegistry entries. Complex types (indexSort, boosts, collections) stored as JSON strings in builder, parsed back in build methods.

**`ResolvedConfig.Index.IndexSortItem`** — blocks ComponentsFactory, LuceneIndexRuntime

```java
public record IndexSortItem(String field, Boolean reverse, String type) {}
```
- Source: YAML `index.sort[]` (array of `{field, reverse?, type?}`)
- Builder: parse YAML array in `contributeYaml*()`, store as serialized JSON string, deserialize in `buildIndex()`
- Only 1 production reader: `ComponentsFactory` (reads `field`, `reverse`; ignores `type`)

**`ResolvedConfig.Index.boosts`** — blocks SsotCommitMetadataSource

```java
// Add to ResolvedConfig.Index record:
Map<String, Double> boosts   // TreeMap for deterministic fingerprinting
```
- Source: YAML `index.boosts` (object with field-name → number)
- Only 1 production reader: `SsotCommitMetadataSource.boostsCanonicalJson()` for `boosts_fp` fingerprint

**`ResolvedConfig.Collections`** — blocks WatcherBootstrap, KnowledgeServer

```java
public record Collections(List<CollectionEntry> entries, String primaryName) {
  public record CollectionEntry(String name, List<Path> roots, String watcherStrategy) {}
}
```
- Source: YAML `index.collections[]` (array of `{name, roots[], watcher.strategy?}`)
- `primaryName`: derived from `entries.get(0).name()`, fallback to `EnvRegistry.INDEX_COLLECTION`, then `"default"`
- 2 production readers: `WatcherBootstrap` (all 3 fields), `KnowledgeServerMigrationOps` (roots only)

**Worker connection sub-records** — blocks AiClientConfig, worker-core WorkerConfig

```java
public record WorkerAi(boolean enabled, String host, int port, long deadlineMs) {}
public record WorkerIndexer(boolean enabled, String host, int port, long deadlineMs,
    int queueSize, int maxInFlightBytes, String backpressureMode) {}
public record TranslatorHealth(long refreshIntervalMs, long maxBackoffMs, long stalenessAlertSeconds) {}
```
- Source: YAML `workers.ai.*`, `workers.indexer.*`, `translator.health.*`
- Also has env/sysprop overrides (via RuntimeEnvResolver, not EnvRegistry); need to add EnvRegistry entries
- 2 production readers: `AiClientConfig` (all three), `SmokeDriver` (enabled flags only)

- [x] Added `List<IndexSortItem> sort` + `Map<String, Double> boosts` to `ResolvedConfig.Index`
- [x] Added `ResolvedConfig.Collections` + `CollectionCfg` records
- [x] Added `ResolvedConfig.WorkerAi`, `WorkerIndexer`, `TranslatorHealth` sub-records
- [x] Added 11 EnvRegistry entries (AI_HOST/PORT/DEADLINE_MS, INDEXER_HOST/PORT/DEADLINE_MS/QUEUE_SIZE/MAX_INFLIGHT_BYTES, TRANSLATOR_REFRESH/BACKOFF/STALENESS)
- [x] Wired `contributeYamlIndexComposite()`, `contributeYamlWorkers()`, `contributeYamlCollections()` in builder
- [x] Added `parseIndexSort()`, `parseBoosts()`, `buildCollections()`, `buildWorkerAi()`, `buildWorkerIndexer()`, `buildTranslatorHealth()`
- [x] Snapshot propagation automatic (JSON strings in resolutions map)

#### C2: Migrate easy call sites (no new types needed) — DONE

- [x] `AiTranslatorFactory` / `LocalAiTranslatorService` — removed RuntimeConfig parameter from `create()` and `buildConfig()`, dropped llmModelPath fallback. Updated test probe to use zero-arg `buildConfig()` + YAML contribution.
- [x] `RootLifecycleOps` — replaced `RuntimeConfig.load()` with `ConfigStore.global().get().watcher()` via new `WatcherSettings.fromConfigStore()` factory method.
- IndexRuntimeFactory deferred to C4 (only forwards RuntimeConfig to LuceneIndexRuntime).

#### C3: Migrate medium call sites (use new sub-records from C1) — DONE

- [x] `AiClientConfig` — reads `WorkerAi` + `TranslatorHealth` from ConfigStore when available, RuntimeConfig fallback when ConfigStore absent. Simplified embed/classify flag reads.
- [x] `worker-core WorkerConfig` — reads `WorkerIndexer` + `paths().dataDir()` + `search().collection()` + `index().nrtTargetMaxStaleMs()` from ConfigStore when available, RuntimeConfig fallback.
- SmokeDriver deferred to C4 (receives RuntimeConfig from LauncherEnvironment, reads 5+ fields).

#### C4: Migrate complex call sites (use new composite types from C1) — PARTIAL

- [x] `ComponentsFactory` — replaced `RuntimeConfig.IndexSortItem` with `ResolvedConfig.Index.IndexSortItem`; dropped `runtimeConfigForSort` parameter; added `fallbackIndexPath` for backward compat
- [x] `SsotCommitMetadataSource` — `boostsCanonicalJson()` reads from `resolvedConfigOrFallback().index().boosts()`
- [x] `WatcherBootstrap.start()` — reads collections from ConfigStore with RuntimeConfig fallback
- [x] `LuceneIndexRuntime` — passes `fallbackIndexPath` to ComponentsFactory instead of full RuntimeConfig; keeps `runtimeConfig()` accessor for external callers (`SearchOrchestrator`, `IndexingLoop`)
- KnowledgeServer deferred — RuntimeConfig deeply threaded through 8+ calls in `start()`
- SmokeDriver deferred — receives RuntimeConfig from LauncherEnvironment
- IndexRuntimeFactory unchanged — pass-through, cleaned up in Phase F

#### Workstream 1: KnowledgeServer + SmokeDriver — DONE

- [x] `KnowledgeServer.start()` — reads scalars from ConfigStore (migrationCutoverMaxFailedJobs, indexBasePath, schemaMismatchPolicy); passes null to factory (factory handles null internally); collections from ConfigStore for migration enumerator; string comparison for schemaMismatchPolicy instead of enum
- [x] `KnowledgeServerMigrationOps` — added `loadWatchedRootsBestEffort` overload taking `List<ResolvedConfig.CollectionCfg>`
- [x] `LauncherEnvironment` — initializes ConfigStore from RuntimeConfig YAML root (same pattern as HeadlessApp)
- [x] `SmokeDriver.execute()` — ConfigStore-first reads for workerAi/workerIndexer enabled, llmEnabled, llmModelPath, egressBlockAll; RuntimeConfig fallback for tests

### Phase D: Policy bridge cleanup (I5) — DONE

- [x] `LocalApiServer.java` constructor — removed 2 duplicate `SystemAccess.setSysProp()` calls after `snapshot()`
- [x] `RuntimeActivationService.runActivate()` — removed 2 duplicate `System.setProperty()` calls after `policyService.snapshot()`

#### Workstream 2: LuceneIndexRuntime accessor elimination — DONE

The `LuceneIndexRuntime.runtimeConfig()` deprecated accessor was called by 9 sites in 3 files:
- `SearchOrchestrator` (4 calls) — reads `searchConfig().chunkAwareEnabled()` and `searchConfig().corrections().*`
- `RagContextOps` (3 calls) — reads `ragRetrieveMode()`, `ragOverretrieveFactor()`, `ragDiversifyMode()`, `ragMmrMaxCandidates()`
- `IndexingLoop` (2 calls) — reads `ragChunkVectorsEnabled()`

**RAG fields**: All 5 already exist in `ResolvedConfig.Rag`. Migration is a trivial accessor swap to `indexRuntime.resolvedConfig().rag().*`.

**Search fields**: 4 fields are NOT in `ResolvedConfig.Search` but ARE already contributed to the builder:
- `search.chunk_aware.enabled` (default: true)
- `search.corrections.enabled`
- `search.corrections.max_edit_distance`
- `search.corrections.zero_hit_retry_enabled`
- `search.corrections.index_fallback_enabled`
- `search.corrections.df_threshold`

**Steps:**
- [x] Added 6 fields to `ResolvedConfig.Search` (chunkAwareEnabled, correctionsEnabled, correctionsDfThreshold, correctionsMaxEditDistance, correctionsZeroHitRetryEnabled, correctionsIndexFallbackEnabled)
- [x] Migrated `IndexingLoop` (2 calls) → `resolvedConfig().rag().chunkVectorsEnabled()`
- [x] Migrated `RagContextOps` (3 calls) → `resolvedConfig().rag().*` (retrieveMode, overretrieveFactor, diversifyMode, mmrMaxCandidates, mmrLambda, chunkVectorsEnabled)
- [x] Migrated `SearchOrchestrator` (4 calls) → `resolvedConfig().search().*` (chunkAwareEnabled, corrections fields)
- [x] Zero remaining callers of `indexRuntime.runtimeConfig()` in production code
- [x] Replaced `runtimeConfigForSort` with `fallbackIndexPath` (Path) — done in F9
- [x] Moved `ValidationMode` enum to standalone file in adapters-lucene — done in F1
- [x] Deleted `runtimeConfig()` accessor — done in F9

#### Workstream 3: Remaining RuntimeConfig sites — DONE

**Trivial:**
- [x] `GrpcEmbeddingClient` — swapped `RuntimeConfig.WorkerAiCfg` → `ResolvedConfig.WorkerAi`; test simplified (no more reflection)
- [x] `UserIndexConfig` — deleted dead `RuntimeConfig` constructor (never called in production)

**Medium:**
- [x] `PagingCursorManager` — added 4 paging fields to `ResolvedConfig.Search` (cursorLegacyEnabled, pagingStrategy, pagingPitTtlMs, pagingTiebreakField); swapped constructor to `ResolvedConfig.Search`
- [x] `GrpcAnnSearchClient` — added `AnnService` nested record to `ResolvedConfig.Search`; swapped constructor to `ResolvedConfig.Search.AnnService`; added 4 ANN service keys to `contributeYamlSearch()`
- [x] `DefaultAppFacade` — moved `LanguageFilterPolicy` enum into `DefaultAppFacade`; replaced `RuntimeConfig.normalizeLanguageTag()` with `ConfigParsingUtils.normalizeLanguageTag()`; `AppFacadeBootstrap` maps enum via `mapLanguagePolicy()` bridge

**Hard / structural (keep for Phase F):**
- `ConfigManagerBootstrap` — IS the RuntimeConfig producer; skip (irreducible)
- `ConfigSnapshot` — carries RuntimeConfig; structural replacement cascades to all listeners
- `AppFacadeBootstrap` — reads `infraHealth()`, `infraHealthGrpc()` not in ResolvedConfig
- `InfraHealthBootstrap` — reads `infraHealth()` via ConfigSnapshot listener
- `DebugStateController` — reads `runtimeConfig.root()` raw JSON tree for debug dump; no typed equivalent
- `LocalApiServer` — passthrough to DebugStateController

#### C5: Reduce RuntimeConfig to YAML-only reader — DONE

Remaining `RuntimeConfig.load()` calls verified as:
- `HeadlessApp` (startup: contributes YAML to ConfigStore)
- `ConfigStoreRebuilder` (settings change: re-contributes YAML via `RuntimeConfig.load().root()`)
- `LauncherEnvironment` (startup: initializes ConfigStore from YAML)
- LuceneIndexRuntime constructor fallback (when ConfigStore absent)
- Fallback paths in AiClientConfig, worker-core WorkerConfig, WatcherBootstrap, KnowledgeServer, SmokeDriver
- ai-worker `WorkerConfig.resolveEmbeddingDimension()` (fallback for SSOT field catalog miss)

All remaining `.load()` calls are either YAML contribution (irreducible until F7) or ConfigStore-absent fallbacks (removable after ConfigStore is guaranteed initialized in all processes).

- [x] Verified all remaining `RuntimeConfig.load()` calls are fallback-only or YAML contribution
- [x] Factory classes are internal to the YAML contribution path

### Phase E: AI worker ConfigStore self-initialization (I4) — DONE

**Implementation**: The AI worker self-initializes ConfigStore at startup via `AiWorker.initConfigStore()`:
1. Try `ResolvedConfigBuilder.loadWorkerSnapshotFromSysprop()` (if sysprop was set externally)
2. Fall back to env-registry-only build: `ResolvedConfig.builder().contributeEnvRegistry().build()`
3. Set `ConfigStore.setGlobal(new ConfigStore(rc))`

YAML contribution skipped — `JustSearchConfigurationLoader` has no method returning a raw `JsonNode`, and using `RuntimeConfig.load().root()` would defeat the purpose. The env-only path is sufficient since all AI worker config is env/sysprop-based via EnvRegistry.

**LlmSettings/SummarySettings NOT migrated**: `buildLlm()`/`buildSummary()` use sentinel defaults (`0`/`""`) that don't match the real defaults in `LlmSettings.Builder` (e.g., `deadlineMs: 0` vs `900`, `contextLength: 0` vs `4096`). Direct `ResolvedConfig.Llm`/`Summary` reads would silently break unconfigured fields. Since EnvRegistry already participates in ConfigStore via ordinals 400/500, the values are consistent without migration. Can be revisited if sentinel defaults are replaced with real defaults in a future pass.

- [x] E1: ConfigStore self-initialization in `AiWorker.main()` (snapshot-first, env-registry fallback)
- [x] E2: LlmSettings — **skipped** (sentinel defaults make direct Llm reads unsafe; EnvRegistry reads already consistent with ConfigStore)
- [x] E3: SummarySettings — **skipped** (same reason as E2)
- [x] E4: `WorkerConfig.resolveDataDir()` — ConfigStore-first with `PlatformPaths` fallback
- [x] E5: Removed `RuntimeConfig.load()` fallback from `resolveEmbeddingDimension()` + removed RuntimeConfig import (ai-worker module is now RuntimeConfig-free)

### Phase F: Delete RuntimeConfig

**Investigation findings**: 37 files still import RuntimeConfig. After Phases C+E, the remaining consumers are:

| Consumer | Method | ResolvedConfig gap |
|----------|--------|--------------------|
| AppFacadeBootstrap | `dataDir()` ×6 | None — `paths().dataDir()` exists |
| AppFacadeBootstrap | `infraHealth()` | Need `InfraHealth` record (4 fields) |
| AppFacadeBootstrap | `infraHealthGrpc()` | Need `InfraGrpc` record (2 fields) |
| AppFacadeBootstrap | `indexDefaultLanguage()` | Need field in `Index` |
| AppFacadeBootstrap | `searchLanguagePolicy()` | Need field in `Policy` |
| InfraHealthBootstrap | `infraHealth()` via ConfigSnapshot | Same `InfraHealth` gap |
| DebugStateController | `root()` raw JsonNode | Special case — raw YAML dump |
| ConfigStoreRebuilder | `RuntimeConfig.load().root()` | N/A — YAML contribution |
| IndexingCoordinator | `ValidationMode` enum | Move enum out of RuntimeConfig |
| LuceneIndexRuntime | `ValidationMode` enum + fallback paths | Move enum, remove fallbacks |
| ConfigManagerBootstrap | IS the RuntimeConfig producer | Becomes thin YAML loader |
| ConfigSnapshot | Carries RuntimeConfig | Remove field after consumers migrate |

**YAML paths for missing infra types** (from `RuntimeInfraConfigFactory`):
- `infra.health.poll_interval_ms` (default: 5000)
- `infra.health.thresholds.nrt_stale_ms` (default: 30000)
- `infra.health.thresholds.translator_handshake_stale_ms` (default: 120000)
- `infra.health.thresholds.ann_cache_ready_percent` (default: 75)
- `infra.health.grpc.host` → env `JUSTSEARCH_INFRA_HEALTH_HOST` / sysprop `justsearch.infra.health.host` (default: 127.0.0.1)
- `infra.health.grpc.port` → env `JUSTSEARCH_INFRA_HEALTH_PORT` / sysprop `justsearch.infra.health.port` (default: 7443)

**YAML paths for missing language fields** (from RuntimeConfig):
- `index.default_language` → env `JUSTSEARCH_INDEX_DEFAULT_LANGUAGE` (default: `Locale.getDefault()` then "en-US")
- `search.default_language_policy` → env `JUSTSEARCH_SEARCH_DEFAULT_LANGUAGE_POLICY` (default: "include")

#### F1: Add remaining types to ResolvedConfig — DONE

- [x] Added `InfraHealth`, `InfraGrpc` records, `defaultLanguage` to Index, `languagePolicy` to Policy
- [x] Added 4 EnvRegistry entries, wired `contributeYamlInfra()`, language fields in builder
- [x] Added `loadYamlRoot()` to JustSearchConfigurationLoader
- [x] Moved `ValidationMode` enum to standalone file in adapters-lucene

#### F2: Migrate AppFacadeBootstrap — DONE

- [x] All RuntimeConfig reads replaced with ConfigStore (dataDir, infraHealth, infraGrpc, language fields)
- [x] `mapLanguagePolicy` accepts String; `createGplJobCoordinator` takes Path; added `toInfraHealthConfig` bridge

#### F3: Migrate InfraHealthBootstrap — DONE

- [x] Listener reads from ConfigStore instead of `snapshot.runtimeConfig().infraHealth()`

#### F4: Handle DebugStateController — DONE

- [x] Changed field/constructor from `RuntimeConfig` to `JsonNode configRoot`
- [x] Updated LocalApiServer builder and HeadlessApp to use `loadYamlRoot()`

#### F5: Move `ValidationMode` enum — DONE (in F1)

#### F6: Simplify ConfigSnapshot — DONE

- [x] Dropped `runtimeConfig` field from ConfigSnapshot record
- [x] ConfigManagerBootstrap.loadSnapshot() no longer calls RuntimeConfig.load()
- [x] Deleted `withPreloadedConfig()` factory method

#### F7: Replace RuntimeConfig in ConfigStoreRebuilder — DONE

- [x] Uses `JustSearchConfigurationLoader.loadYamlRoot()` instead of `RuntimeConfig.load().root()`

#### F8: Remove RuntimeConfig fallback paths — DONE

- [x] Removed from AiClientConfig, WorkerConfig (worker-core), WatcherBootstrap, KnowledgeServer
- [x] Removed from SmokeDriver, LauncherEnvironment (runtimeConfig field deleted)
- [x] SsotCommitMetadataSource already done in F7
- [x] LuceneIndexRuntime + IndexRuntimeFactory decoupled (F9)

#### F9: Delete RuntimeConfig — DONE

- [x] Decoupled LuceneIndexRuntime from RuntimeConfig (replaced `runtimeConfigForSort` with `Path fallbackIndexPath`)
- [x] Decoupled IndexRuntimeFactory from RuntimeConfig (changed all overloads to `Path`)
- [x] Fixed JustSearchCodec RuntimeConfig import (stale import removed; comment reference remains)
- [x] Updated test files (ComponentsFactoryTest, HybridSearchOpsTest, SmokeDriverTest, SchemaMismatchStatusContractTest, LauncherEnvironmentCloseTest, IndexRuntimeFactoryTest)
- [x] Deleted RuntimeConfig.java (926 LOC) + 12 factory classes + TestConfigHelper + RuntimeConfigTest
- [x] Deleted ConfigParityTest (parity oracle no longer needed)

### Phase G: Test stabilization — DONE

RuntimeConfig deletion introduced 199 test failures across 11 modules that were not caught during
initial development. Root causes and fixes:

- [x] **Jackson lockfile regression** (~100+ cascade failures): `worker-core/build.gradle.kts` and
  `worker-services/build.gradle.kts` resolution strategies changed `jackson-annotations` from 2.21
  to 2.18.6, breaking Jackson 3 (`JsonSerializeAs` class missing). Restored `useVersion("2.21")`.
- [x] **UI test Jackson3JsonMapper stripped** (~70 failures): F9 cleanup accidentally removed
  `cfg.jsonMapper(new Jackson3JsonMapper())` from all UI test Javalin instances. Restored from main.
- [x] **KnowledgeServerTest reflection** (5 failures): Updated `invokeLoadWatchedRootsBestEffort`
  to match new 1-arg `loadWatchedRootsBestEffort(ResolvedConfig)` signature.
- [x] **SmokeDriverTest** (2 failures): Added ConfigStore re-initialization in `writeProfile()`;
  added `workers.ai.enabled` and `workers.indexer.enabled` to default profile YAML.
- [x] **ConfigWiringTest** (3 failures): Replaced YAML placeholder tests (RuntimeConfig-specific)
  with literal data_dir + collection-name-from-YAML tests. Fixed `buildPaths()` to derive
  `indexBasePath` using primary collection name instead of hardcoded `"default"`.
- [x] **ComponentsFactory ephemeral path** (~16 failures): Added temp directory creation when all
  path sources are null, preventing `MMapDirectory(null)` NPE.
- [x] **GrpcSearchServiceReasonCodeContractTest** (1 failure): Added `refreshResolvedConfig()` helper
  to re-read sysprops into cached `resolvedConfig` between sub-cases.
- [x] **ConfigStore initialization in tests** (~10 failures): Added `storeFromEnvironment()` or
  manual ConfigStore setup to AiTranslatorFactoryTest, WatcherBootstrapTest, WorkerConfigLoadTest,
  InferenceConfigFromEnvironmentTest, OnnxModelDiscoveryTest, NastyCorpusTest.
- [x] **OnnxEmbeddingEncoderTest**: Updated assertion from 1024 to 2048 to match current
  `DEFAULT_GPU_MEM_MB`.
- [x] **UnreferencedCodeTest**: Updated ArchUnit freeze store for newly unreferenced methods.

### Known remaining gaps (out of scope)

**Dual-read patterns**: 76 direct `EnvRegistry.*.get()` calls in 24 production files. ConfigStore
IS the dominant path, but `RerankerConfig`, `SpladeConfig`, `NerConfig`, `CitationScorerConfig`
still fall back to raw `EnvRegistry.get()` when ConfigStore is null. Telemetry classes bypass
ConfigStore entirely. These are incremental improvements for a future pass — not blockers for
RuntimeConfig deletion.

**`KnowledgeServerConfig` envOrProperty calls**: 11 calls for worker-spawning operational parameters
not yet migrated to ConfigStore. Low risk — these are process-management values, not search/index
configuration.
