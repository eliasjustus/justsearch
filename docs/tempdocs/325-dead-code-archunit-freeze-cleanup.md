---
title: "Dead Code Cleanup — Eliminate ArchUnit Freeze Store"
status: done
created: 2026-03-19
completed: 2026-03-19
---

# 325 — Dead Code Cleanup — Eliminate ArchUnit Freeze Store

## Motivation

`UnreferencedCodeTest` uses ArchUnit's freeze store to baseline 44 methods flagged as
unreferenced. The freeze store is an opaque binary file that breaks on every structural
refactor (rename, extract interface, move method) — requiring a manual unlock-run-lock
dance to update. This happened during the tempdoc 324 merge (EmbeddingProvider rename)
and will happen again with every future refactor.

**Goal:** Empty the freeze store by resolving every entry. Then remove the freeze
mechanism entirely — the test becomes a clean gate that catches future dead code without
opaque committed state.

## Outcome

All 44 freeze store entries resolved. Freeze mechanism removed. Comprehensive dead code
sweep across 13 phases using 12 techniques. Three new ArchUnit CI gates and one PMD rule
added for ongoing detection.

| Metric | Before | After |
|--------|--------|-------|
| Freeze store entries | 44 | 0 (file deleted) |
| Dead methods deleted | — | 15 (+2 from cascade in Phases 12-13) |
| Dead classes/interfaces deleted | — | 14 (EmbeddingSchemaGuard, 10 ANN stack, ShardCoordinator pair, SearchHitMetadata) |
| Dead fields removed | — | 2 (RuntimeContext.ephemeralPath, .vectorEfSearch) |
| Dead constructor params removed | — | 3 (EmbedPipeline.assets/.telemetry, TranslatorAssets.pipelineLoader) |
| Dead proto file deleted | — | 1 (pipeline_indexing_types.proto, 8 messages) |
| Dead proto service removed | — | 1 (AnnService, 3 messages) |
| Dead test files deleted | — | 11 |
| Dead test fixtures deleted | — | 3 (+1 duplicate dir) |
| Dead config removed | — | AnnService record, builder, YAML blocks |
| Methods in exclusion list | 44 (freeze store) | 27 (transparent map, verified live) |
| New CI gates | 0 | 4 (3 ArchUnit rules + PMD UnusedFormalParameter) |
| Files changed | — | 265 |
| Lines removed (net) | — | -4,012 |
| Freeze mechanism | Active (opaque binary state) | Removed (transparent `KNOWN_UNREFERENCED` map) |

### Phase 1: Categorize all 44 entries — DONE

Investigation with parallel agents confirmed actual callers for each method.

### Phase 2: Delete dead methods — DONE (13 methods)

- [x] `backendDebugName` in TranslatorSession — 0 callers
- [x] `telemetry` in SummaryWorkflowDefinition — 0 callers
- [x] `telemetry` in Stage (inner class) — 0 callers
- [x] `searchFullDocsWithMeta` + `FullDocContextResult` in RagContextOps — 0 callers
- [x] `embedPipeline` in TranslatorAssets — unused getter
- [x] `embeddingSchemaGuard` in TranslatorAssets — unused getter
- [x] `classifyTags` in TranslatorTelemetry — 0 callers
- [x] `embedTags` in TranslatorTelemetry — 0 callers
- [x] `getProcess` in LlamaServerOps — 0 callers
- [x] `getLogDir` in FileOperationLog — 0 callers
- [x] `sessionId` in AgentSession — 0 callers (not a record, manual accessor)
- [x] `markDbHealthy` in SqliteJobQueue — 0 callers (future placeholder)
- [x] `resolveBaseDir` in AppFacadeBootstrap — 0 callers (consolidated elsewhere)

Tempdoc estimates that were wrong (methods had test callers, not dead):
- `buildIndexBatch` (FileIngestorTest), `collapseByParent` (SearchOrchestratorCollapseTest),
  `summarize` (SummaryWorkflowCoordinator production caller), `isLambdaMartEligible`
  (HarmfulCombinationsTest), `postProcess` (SpladePostProcessTest)

### Phase 3: Annotate justified survivors — DONE (31 methods)

All annotated with `@SuppressWarnings("unused")` and a comment naming the caller.
Three categories of survivors:

**Test-only callers (24):** ArchUnit excludes test sources, so these appear unreferenced.
- InferenceLifecycleManager delegates (9): test stubs for ExternalServerTest, PropsInsightsTest, etc.
- LauncherEnvironment accessors (3): LauncherEnvironmentCloseTest, SmokeDriverTest
- Remaining (12): RetryDecision.action, FileIngestor.buildIndexBatch, SearchOrchestrator.collapseByParent,
  SpladeEncoder.postProcess, KnowledgeHttpApiAdapter.isLambdaMartEligible,
  VersionHandshakeClient.mismatchReason, ConfigStore.clearGlobal,
  JvmRuntimeGauges.getJvmGaugeErrorCount, WorkerHealthClient.state, AiClientConfig.of,
  PagingCursorManager.ensureCursor, IntentSchemaValidator.validateOrThrow

**Reflection contracts (3):** Called via getDeclaredMethod in tests.
- IndexingLoop.handleEmbeddingFailure, IndexingLoop.handleChunkEmbeddingFailure (already had annotation)
- AppFacadeBootstrap.chooseFirstNonBlank (already had annotation)

**Production callers invisible to ArchUnit (4):** Inheritance/overload resolution limitations.
- SummaryPipeline.summarize (SummaryWorkflowCoordinator), AbstractSchemaGuard.validate
  (IntentPipeline, ClassifyPipeline), AbstractSchemaGuard.resolveSchema (subclass constructors),
  AbstractSchemaGuard.locateRepoRoot (subclass constructors, TranslatorAssets)

### Phase 4: Remove freeze mechanism — DONE

- [x] Deleted freeze store directory (`archunit_store/`)
- [x] Removed freeze config from `archunit.properties`
- [x] Replaced `FreezingArchRule` with plain `ArchRule`
- [x] Added transparent `KNOWN_UNREFERENCED` map in test class (keyed by `ClassName.methodName`,
      value documents the caller/reason)
- [x] Added `isNotKnownUnreferenced()` predicate to exclude listed methods

**Design note:** `@SuppressWarnings` has SOURCE retention — invisible in bytecode, so ArchUnit
cannot detect it. The `KNOWN_UNREFERENCED` map serves as the exclusion mechanism. The
`@SuppressWarnings("unused")` annotations remain on source for IDE support.

### Phase 5: Verify — DONE

- [x] `UnreferencedCodeTest` passes (0 violations)
- [x] All 18 app-launcher tests pass
- [x] All changed modules compile and pass tests
- [x] Pre-existing `worker-services` test failures (unrelated `buildDocument` reflection) confirmed independent

### Phase 6: Cascade cleanup — DONE

Deleting the `embeddingSchemaGuard()` and `embedPipeline()` getters left dead fields in
`TranslatorAssets`. Investigation revealed:

- [x] `TranslatorAssets.embeddingSchemaGuard` field — stored but never read after getter deletion
- [x] `TranslatorAssets.embedPipeline` field — stored and warmed but never read
- [x] `EmbeddingSchemaGuard` class — only instantiated to fill the dead field → **deleted entire class**
- [x] `EmbedPipeline` constructor `assets` + `telemetry` params — accepted but never stored
- [x] Updated `BridgedTranslator`, `IntentPipelineTest` call sites
- [x] All ai-bridge tests pass

### Phase 7: Dead class detection rule — DONE

Implemented a new ArchUnit rule `no_unreferenced_package_private_classes` that detects
package-private classes with zero incoming dependencies from any other class. Uses
`JavaClass.getDirectDependenciesFromSelf()` which covers field types, method params/return
types, constructor calls, method calls, annotations, superclasses, and interfaces.

**Excludes:** public/protected classes, inner/nested classes, enums, annotations, gRPC
service implementations, main-class entry points, and entries in `KNOWN_UNREFERENCED_CLASSES`.

**Results: 7 dead classes found, 0 false positives.**

Deleted classes (+ their tests):

| Dead class | Module | LOC | What it was |
|---|---|---|---|
| `FileIngestor` | app-indexing | 313 | Batch indexer, never wired into production |
| `InstrumentedIndexApi` | app-indexing | 21 | Telemetry decorator, never instantiated |
| `TelemetryConfigs` | app-indexing | 20 | Histogram configs, never referenced |
| `GrpcAnnSearchClient` | app-search | 113 | Remote ANN gRPC client |
| `GrpcEmbeddingClient` | app-search | 173 | Remote embedding gRPC client |
| `LocalEmbeddingClient` | app-search | 95 | Local embedding client fallback |
| `LuceneAnnSearchClient` | app-search | 37 | Lucene KNN client |
| `IntentSchemaValidator` | app-search | 53 | JSON schema validator |
| `AnnSearchClient` (interface) | app-search | 17 | Interface for dead ANN implementations |
| `EmbeddingClient` (interface) | app-search | 8 | Interface for dead embedding implementations |

Also deleted 6 test files (FileIngestorTest, GrpcAnnSearchClientTest, GrpcEmbeddingClientTest,
LuceneAnnSearchClientTest, IntentSchemaValidatorTest, EmbeddingClientTest).

Cascade: `IndexSearcherProvider.annSearch` method deleted (only caller was `LuceneAnnSearchClient`),
`LuceneSearchClient.annSearch` annotated as test-only.

**Context:** The `app-search` dead classes were a **HEAD-side hybrid search abstraction**
(Phase 6, Nov 2025) designed to let the Head process embed queries and call an external ANN
service (Qdrant/Milvus) or Lucene directly. This violated the "Head never touches Lucene"
invariant and was superseded by the Worker-side implementation (`SearchOrchestrator` →
`ReadPathOps.searchVector()` → `KnnFloatVectorQuery`). The external ANN service proto
(`AnnService` in `ai.proto`) has no server implementation — the entire concept was abandoned.

### Phase 8: ANN service remnant cleanup — DONE

Removed all surviving artifacts of the abandoned external ANN service design:

- [x] Proto: `AnnSearchRequest`, `AnnSearchHit`, `AnnSearchResponse`, `service AnnService` from `ai.proto`
- [x] Config: `ResolvedConfig.HybridSearch.AnnService` record + `buildAnnService()` builder
- [x] YAML: `ann_service` blocks from `application.yaml` and `smoke.yaml`
- [x] Scripts: AnnService removed from `validate-rpc-retry-ownership.mjs` allowlist,
      `rpc-retry-ownership-matrix.v1.json` (entry + method count 36→35),
      `grpc-fault-scenarios.v1.json` (scenario GF-004), `dag-runner-grpc-soak.mjs` (deleted step)
- [x] Tests: `ann_service: {}` removed from 2 inline YAML fixtures, `GrpcRetryServiceConfigTest`
      updated to use `HealthService` instead of deleted `AnnService`
- [x] Golden fixture: `ann_auto_embed.json` deleted (orphaned, no test consumer)

### Phase 9: Dead field detection rule — DONE

Implemented `no_unreferenced_package_private_fields` ArchUnit rule that checks for
package-private fields with zero GET (read) accesses — i.e., write-only fields. Excludes
private fields (PMD covers those), public/protected fields, synthetic fields, loggers,
constants (static final), and serialization-annotated fields.

**Results: 2 genuinely dead fields found, 0 false positives.**

- [x] `RuntimeContext.ephemeralPath` — set 3 times, never read; `LifecycleSnapshot` gets
      value from `Components.ephemeralPath()` directly. Stale comment claimed it was read
      during startup — refactored away without updating comment.
- [x] `RuntimeContext.vectorEfSearch` — set 1 time, never read in production; runtime uses
      `vectorEfSearchOverrideOrNull` (computed from `ResolvedConfig`). Test accessor updated
      to use the live field.
- [x] Updated `ConfigWiringTest` assertions: default ef_search → `assertNull(override)`,
      explicit 450 → `assertEquals(450, override)`.

### Phase 10: Remaining quick wins — DONE

- [x] Deleted `pipeline_indexing_types.proto` (8 dead messages, 0 Java consumers)
- [x] Deleted 3 dead test fixtures (`MockWorkerSignalBus`, `InMemoryJobQueue`, `JobState`)
- [x] Deleted duplicate `testFixtures/testFixtures/` directory (invalid Gradle path)
- [x] Fixed `TranslatorAssets.pipelineLoader` regression from Phase 6 cascade cleanup
- [x] Enabled PMD `UnusedFormalParameter` rule; suppressed 10 pre-existing violations

### Phase 11: ProGuard reachability audit — BLOCKED

Attempted one-time deep audit using ProGuard's `-printusage` report mode. ProGuard 7.9
(latest) can read Java 25 bytecode, but requires extracted JDK runtime classes for
hierarchy resolution. Adoptium JDK 25 ships `lib/modules` (binary jimage format) instead
of `jmods/`, and ProGuard cannot resolve `java.lang.Object` without them.

**Workaround possible but not worth the effort:** `jimage extract` could produce the
needed class files, but the setup is fragile and the diminishing returns are real —
3 ArchUnit CI gates now catch new dead code automatically. ProGuard would only find
deeper unreachable subgraphs via full call-graph reachability, which are increasingly
rare after ~3,100 lines of cleanup in this session.

### Phase 12: KNOWN_UNREFERENCED audit — DONE

Audited all 29 entries in the method exclusion list. Most are test-delegation stubs for
live production features. Two deletable entries found:

- [x] `SearchOrchestrator.collapseByParent` — superseded by `collapseChunkHitsToParents`,
      zero production callers. Deleted method + `mergeCollapsedHit` helper + test.
- [x] `LuceneSearchClient.annSearch` — only production callers (`IndexSearcherProvider`,
      `LuceneAnnSearchClient`) were already deleted in Phase 7. Deleted method + test.
      Cascade: `materialize`, `emptyResult`, `readSorted`, `readNumeric`, `safeString`,
      `commitId`, `HitBundle`, `SearchResult` all dead inside `LuceneSearchClient`.
      Stripped class to constructor + `close()`.

Remaining 27 entries are verified live features (test-delegation stubs for actively-used
production methods in InferenceLifecycleManager, LauncherEnvironment, etc.).

### Phase 13: Cross-module public class analysis — DONE

Script-based analysis: for each public class in a module, check if any OTHER module imports
it. Classes with zero cross-module importers are candidates. Then filter for classes also
unused within their own module.

Found 2 dead public classes (invisible to ArchUnit which only checks package-private):

- [x] `ShardCoordinator` (interface) — zero importers, zero callers
- [x] `SingleShardCoordinator` (implementation) — only instantiated by dead factory method
- [x] `IndexRuntimeFactory.createSingleShardCoordinator` — dead factory method (sole
      reference to both classes)
- [x] Deleted test: `SingleShardCoordinatorTest`

Also confirmed alive: `SearchResultFormatter` (7 intra-module callers),
`PromptTemplateLintCommand` (invoked by Gradle `promptTemplateLint` task in `:check`).

### Techniques exhausted

All systematic dead code detection techniques have been applied to completion:

| Technique | Yield | Status |
|-----------|-------|--------|
| ArchUnit dead methods (private/pkg-private) | 13 methods | 0 violations remaining |
| ArchUnit dead classes (pkg-private) | 10 classes + 2 interfaces | 0 violations remaining |
| ArchUnit dead fields (pkg-private write-only) | 2 fields | 0 violations remaining |
| Cascade analysis (delete → re-run → repeat) | 5 cascade rounds | No more cascades |
| KNOWN_UNREFERENCED audit | 2 dead methods + cascade | 27 entries verified live |
| Cross-module public class analysis | 2 dead public classes | Remaining candidates alive |
| ANN remnant cleanup | Proto, config, YAML, scripts | Complete |
| Dead proto/fixtures | 1 proto file, 3 fixtures, 1 dup dir | Complete |
| PMD UnusedFormalParameter | Rule enabled | 0 violations (10 suppressed) |
| ProGuard reachability | — | Blocked on JDK 25 |
| DAGP buildHealth | 16 modules fixed, 0 violations | Complete |
| Knip frontend | Handled by tempdoc 326 | Complete |

### Phase 14: DAGP buildHealth — unused dependency cleanup — DONE

Fixed pre-existing `vectorSearchOps()` compilation error in `system-tests` (5 call sites
→ `readPathOps().searchVector()`, mechanical rename from tempdoc 320 R8). This unblocked
DAGP `buildHealth` which produced a comprehensive report.

**Batch 1 (our cleanup consequences):** app-search, app-indexing, app-launcher — removed
deps that became unused from our dead code deletions (grpc.stub, json-schema-validator,
modules:indexing, modules:ipc-common, modules:search, grpc.inprocess, opentelemetry.api,
grpc.protobuf; downgraded jackson.databind to runtimeOnly; added jackson.core transitives).

**Batch 2 (pre-existing dep hygiene):** 16 modules edited — removed unused deps, corrected
api/impl configurations, added missing transitives, downgraded runtime-only deps.

- [x] `adapters-lucene`: removed `infra-core`
- [x] `app-ai`: removed `opentelemetry.api`, downgraded `configuration` api→impl
- [x] `app-config`: downgraded `configuration` api→impl
- [x] `app-observability`: removed `search`, `ipc-common` (test), moved `slf4j.api` to test
- [x] `configuration`: removed `infra-core`, `json-schema-validator`; downgraded `slf4j.api`
      api→impl; downgraded testFixtures jackson to runtimeOnly
- [x] `indexer-worker`: removed 9 unused deps; downgraded lucene-core/tika-core/ai-bridge
      to runtimeOnly; added test transitives
- [x] `infra-core`: downgraded `jackson.databind` api→impl
- [x] `ssot-tools`: removed `slf4j.api`; downgraded jackson.core to runtimeOnly
- [x] `system-tests`: removed `worker-core` test dep; added transitives
- [x] `test-support`: removed `configuration`; downgraded jackson.core to runtimeOnly
- [x] `worker-core`: removed `ipc-common`, mockito; promoted djl/onnxruntime to api;
      downgraded configuration/indexing api→impl
- [x] `worker-services`: removed onnxruntime-gpu + test fixture deps; promoted 8 deps
      impl→api; added `configuration` api + `ai-bridge` test
- [x] `ai-bridge`, `app-agent`, `app-services`, `ui`: added jackson.core transitives

**DAGP exclusion added:** `io.grpc:grpc-stub` in `onUnusedDependencies` — worker-core
uses `io.grpc.Context`/`Metadata`/`ServerInterceptor` from the transitive `grpc-api`;
`grpc-api` is not in the version catalog so `grpc-stub` is the declared dependency.

**Result:** `./gradlew.bat buildHealth` passes with **zero violations** across all modules.

**Final net result: 265 files changed, -6,296 / +2,284 lines (net -4,012).**

## Dead code detection tooling audit

Audit of all dead code detection infrastructure in the codebase, with coverage gaps.

### Current tooling

| Tool | Config | Scope | What it catches |
|------|--------|-------|-----------------|
| **UnreferencedCodeTest** (ArchUnit) | `app-launcher` test | All `io.justsearch`, prod only | Private + package-private methods with 0 bytecode callers |
| **PMD** | `config/pmd/ruleset.xml` | All modules, main sources | `UnusedPrivateMethod`, `UnusedPrivateField`, `UnusedLocalVariable`, `UnusedAssignment` |
| **DAGP** (dependency-analysis) | Root `build.gradle.kts` | All modules | Unused library dependencies (`buildHealth`, manual dispatch only) |
| **SpotBugs + FindSecBugs** | `config/spotbugs/exclude-filter.xml` | All modules, main sources | Bug patterns + security — no dead code |
| **Error Prone** | `config/errorprone/suppressions.txt` | Compile-time | Bug patterns — no dead code |
| **ESLint** | `modules/ui-web/eslint.config.js` | Frontend TS/TSX | `no-unused-vars` — **warn only**, not a gate |

### Other ArchUnit tests (not dead-code related)

- `ArchUnitSanityTest` (core): No AWT/Swing imports, layering rules, no System.getenv()
- `ArchitectureRulesTest` (app-api): API module layering, no System.getenv()
- `ArchUnitEgressTest` (ai-bridge): No HTTP imports in local/backend packages
- `ArchUnitEgressTest` (ai-worker): No HTTP imports outside gRPC/service packages
- `ArchUnitConfigAccessTest` (ai-worker): No System.getenv()/getProperty()

### Coverage gaps

| Dead code category | Current coverage | Gap |
|---|---|---|
| Private methods | PMD + ArchUnit | Overlapping; covered |
| Package-private methods | ArchUnit | Covered |
| **Public/protected methods** | — | **Entirely uncovered** |
| Private fields | PMD | Covered |
| Package-private fields | ArchUnit (new rule) | Covered (write-only detection) |
| **Public/protected fields** | — | **Uncovered** |
| Package-private classes | ArchUnit (new rule) | Covered |
| **Public/protected classes** | — | **Uncovered** |
| Unused local variables | PMD | Covered |
| **Unused frontend exports** | — | **No Knip/ts-prune; `noUnusedLocals` off** |
| Unused library deps | DAGP | Covered (manual dispatch only) |

### Potential extensions (prioritized)

Research conducted 2026-03-19 across current tooling landscape.

#### Tier 1 — High value, low effort

1. ~~**Extend ArchUnit for dead classes**~~ — **DONE** (Phase 7). Found and removed 10
   dead classes/interfaces (-850 LOC production, -613 LOC tests).

2. **Add [Knip](https://knip.dev) for frontend** — Discovery run completed (npx, not installed).
   Findings: **53 unused files**, **46 unused exports**, **31 duplicate exports**,
   1 unused dep (`tailwind-merge`), 3 unused devDeps (`@lingui/cli`, `pixelmatch`, `pngjs`).
   Many "unused files" are scripts/evidence tooling — need triage. The unused exports include
   API schemas, component re-exports, and store types. Recommend a dedicated tempdoc to triage
   and fix these systematically.

#### Tier 2 — Valuable, moderate effort

3. **[ProGuard](https://github.com/Guardsquare/proguard) report mode** (free, standalone).
   Run with `-dontshrink -printusage` for a one-time deep audit listing ALL unreachable
   classes/methods/fields from declared entry points (`HeadlessApp`, `IndexerWorker`, gRPC
   services). More comprehensive than ArchUnit because it follows the full call graph, not
   just direct bytecode references. Not Android-only — works on any JVM bytecode.

4. **[OpenRewrite](https://docs.openrewrite.org/recipes/staticanalysis/removeunusedprivatemethods)
   cleanup recipes** (free, Gradle plugin). `RemoveUnusedPrivateMethods`,
   `RemoveUnusedDependencies` recipes. Unlike detection-only tools, OpenRewrite modifies
   code to remove dead code. Could run as a periodic cleanup pass.

#### Tier 3 — Enterprise / future consideration

5. **[SonarQube](https://www.sonarsource.com/) Community Edition** — detects unused
   private/protected methods, dead stores. Does NOT detect unused public methods/classes
   (same gap as PMD). Main value: unified dashboard. Heavy overlap with PMD+ArchUnit.

6. **[Azul Code Inventory](https://www.azul.com/products/components/code-inventory/) +
   OpenRewrite** (commercial) — instruments JVM at runtime to record which classes/methods
   actually execute in production. Zero overhead. Combined with OpenRewrite for auto-removal.
   Overkill for a local desktop app.

7. **JaCoCo production coverage** (free, already in build) — attach JaCoCo agent to a
   running JustSearch instance to identify methods never hit during real usage. Manual,
   not CI-automatable, but useful for one-off audits.

#### Not recommended

- **bye-bye-dead-code** Gradle plugin — Kotlin/Android only (uses R8), not applicable.
- **SootUp / WALA** — academic call-graph frameworks, too heavy for CI integration.
- **Promoting `noUnusedLocals`/`noUnusedParameters` in tsconfig** — Knip subsumes this.

## Collateral issues discovered during this session

Non-dead-code issues found during investigation and test runs:

### Issue 1: worker-services reflection test failures (3 tests) — PRE-EXISTING

`ContentExtractorTest`, `OfficeMarkerSearchabilityTest`, `VduEligibilityPdfFixturesTest`
fail with `NoSuchMethodException: IndexingLoop.buildDocument(Path, ExtractionResult,
String, ParentIndexMetadata)`. Same pattern as the `vectorSearchOps()` issue fixed in
Phase 14 — a method signature was changed (likely tempdoc 324 IndexingLoop cleanup) but
reflection-based tests weren't updated.

### Issue 2: worker-core NER model discovery test (1 test) — PRE-EXISTING

`NerModelDiscoveryTest` — environment leakage. Test expects model in temp fixture dir
but discovers the real NER model at `D:\code\JustSearch\models\onnx\ner`. Test doesn't
isolate from the host machine's model directory.

### Issue 3: dead config key STAGE_PLUGIN_MANIFEST

`EnvRegistry.STAGE_PLUGIN_MANIFEST` (`justsearch.plugins.manifest`) — constant defined
but never read by any Java consumer. Passed as raw system property in `WorkerSpawner`
but Worker process doesn't consume it.

### Issue 4: hollow app-search module

After dead code removal, `app-search` contains only `PagingCursorManager`,
`LuceneSearchClient` (constructor + close), and `IndexSearcherProvider` (holds client +
clock). Candidate for merging into `app-services` to reduce module count.
