---
title: "279 — Code-First Codebase Assessment Experiment"
---

# 279 — Code-First Codebase Assessment Experiment

**Status:** Complete
**Created:** 2026-03-12
**Session:** 9bb39944-758a-45e0-af48-7a58146a23b6

## Hypothesis

Every agent session in this repo begins by reading tempdocs, which chain into more tempdocs. No agent has ever formed an independent opinion by examining the actual source code, build system, test suite, and structural signals. This experiment tests whether a "code-first" assessment — ignoring tempdocs, git history, and issue backlogs — surfaces different priorities than the doc-driven workflow.

## Approach (Draft)

### What to examine
1. [x] **Module structure** — dependency graph, coupling between modules
2. [x] **Build output** — warnings, suppressions, dependency conflicts
3. [x] **Test suite** — what's tested vs. what isn't, test quality
4. [x] **Code complexity** — large files, deep nesting, god classes
5. [x] **Error handling** — patterns, swallowed exceptions, inconsistencies
6. [x] **API surface** — consistency, completeness, error contracts

### What to ignore (deliberately)
- Tempdocs (all 278 of them)
- Git commit history and blame
- GitHub issues and PRs
- Any "what to do next" documentation

### What to produce
- An independent prioritized list of "what matters most right now"
- Comparison notes: does this align with or diverge from tempdoc-driven priorities?

## Internet Research

**This is a genuinely novel framing.** No established name, pattern, or dedicated implementation exists for an AI agent that reads source code to independently prioritize work without being given a task.

**Closest existing tools:**
- **CodeScene + MCP Server** — computes code health scores from source analysis, identifies hotspots, calculates refactoring ROI. An agent *could* query it, but no published closed-loop where the agent acts on the assessment.
- **Kiro (AWS)** — autonomous agent that works for hours/days, learns from PR feedback, but still primarily operates from assigned tasks.
- **Diffblue Cover** — autonomously analyzes Java codebases for testability and suggests refactorings. Genuinely proactive but narrowly scoped.

**Closest academic work:**
- **SWE-RL** (arXiv 2512.18552) — LLMs self-improve through interaction with raw codebases, but the task itself is still externally provided.
- **"Codified Context"** (arXiv 2602.20478) — infrastructure for agents with deep context in complex codebases, but agents still operate on assigned tasks.
- **Martin Fowler et al.** (Jan 2026) — tested fully autonomous code generation; the agent self-directed but this was treated as a *failure mode*, not a feature.

**The gap:** No one has published a deliberate workflow where an agent reads code structure → forms its own prioritized assessment → begins working autonomously. The pieces exist (code health scoring, autonomous execution, codebase understanding) but the composition is absent from literature and tooling.

## Findings

### 1. Module Structure (33 modules)

**Critical finding: `app-services` is a monolithic aggregator.**
It depends on 17 internal modules and re-exports most via `api()`. This is effectively the "leftover monolith" — the fine-grained `app-agent`, `app-ai`, `app-search`, `app-indexing`, `app-inference` modules exist but are all reassembled in `app-services`, defeating the purpose of the split. Consumers (`ui`, `app-launcher`) inherit everything transitively.

**Red flags:**
- **`app-services`** (17 outbound deps) — mega-aggregator, any change anywhere recompiles it
- **`ui` re-declares deps** already provided by `app-services` — redundant, suggests unclear ownership
- **`app-observability` pulls domain modules** (`ai-bridge`, `search`, `pipeline-schema`) — an observability module shouldn't need business logic
- **`adapters-lucene` leaks via `api()`** — exposes `configuration`, `indexing`, `infra-core`, `core`, Lucene, Jackson to all 8 consumers
- **`ai-worker` depends on `adapters-lucene`** — AI inference worker shouldn't need Lucene (possible arch violation)
- **`ipc-common` re-exports `app-api`** — wire protocol layer tied to REST API contract types (layering concern)

**Merge candidates:** `search` + `indexing` (thin wrappers), `app-secrets` into `app-config`, `app-agent-api` into `app-api`
**Split candidates:** `app-services` (into thin wiring + let consumers depend on specific sub-services)

**Most depended-on:** `configuration` (15), `app-api` (11), `ipc-common` (9), `adapters-lucene` (8), `telemetry` (8)

### 2. Build Output

**Build is clean.** Zero compiler warnings, zero deprecation notices, zero dependency conflicts (just the standard Guava listenablefuture shim). PMD and SpotBugs are wired in and passing. Spotless formatting is enforced. `enforceDependencyVersions` and `checkNoDirectJustsearchSysProp` custom gates pass.

**`@SuppressWarnings` hotspots** (main checkout only, ~210 occurrences across ~80 files):
- `IndexingLoop.java` — 18 suppressions (highest in codebase)
- `GplJobCoordinatorTest.java` — 16 suppressions
- `KnowledgeServer.java` — 10 suppressions
- `FileOperationLog.java` — 7, `FullCoverageSummarizer.java` — 7
- `KnowledgeSearchController.java` — 6, `GrpcRetryServiceConfigTest.java` — 6

The high suppression counts in `IndexingLoop` and `KnowledgeServer` correlate with the module structure finding — these are likely the largest, most complex classes in the codebase.

**Verdict:** Build hygiene is excellent. No action needed on warnings/conflicts. The suppression hotspots are symptoms of complexity (investigated further in item 4).

### 3. Test Suite (~446 test files across ~580 prod files)

**Overall ratio:** ~0.77 test files per prod file. Healthy for a project this size.

**Zero-test modules:**
- `pipeline-schema` (6 prod files) — contains `PipelineValidator` with real DAG cycle-detection logic, completely untested
- `search` (2 prod files) — trivially small but zero coverage

**Critically undertested given complexity:**
- `ai-bridge` (ratio 0.32) — 76 prod files, 24 tests. Contains the most complex inference plumbing (`TranslatorSessionPool`, `IntentPipeline`, `PromptPipelineRunner`) with concurrent logic
- `app-agent-api` (ratio 0.14) — 14 prod files defining the entire agent API surface, only 2 tests
- `app-api` (ratio 0.20) — 49 prod files, 10 tests. Partially justified since most are interfaces/records
- `configuration` (ratio 0.34) — 32 prod files, 11 tests

**Test quality signals:**
- No `@Disabled` tests (conditional CI disabling via `@DisabledIfEnvironmentVariable` is appropriate)
- 45 files use `Thread.sleep` — mostly legitimate (timer tests, concurrency), one fragile case in `FileOperationLogTest` (50ms mtime hack)
- `IntegrationSmokeTest` is a pure placeholder (`assertTrue(true)` only)
- `IndexRuntimeFactoryTest` does real work but ends with `assertTrue(true)` — asserts nothing about state
- Only 7 files use Mockito — project strongly prefers integration-style tests with real implementations and scripted stubs

**God tests (flat structure, no `@Nested`):**
- `AgentLoopServiceTest` — 60 `@Test` methods, no nesting (worst offender)
- `ResolvedConfigBuilderTest` — 50 flat methods
- `ContentExtractorTest` — 35, `DocumentTypeDetectorTest` — 35, `FieldMapperTest` — 33

**Underused patterns:**
- `@ParameterizedTest` in only 8 files — many repetitive single-input tests would benefit
- AssertJ in only 3 files — not a concern, just a style choice

**Positive signals:** Strong JUnit 5 adoption (75 files use `@Nested`, 150 use `@DisplayName`). Consistent `<Subject>Test.java` naming. Healthy "scene-per-file" splitting pattern for complex subjects (e.g., `SummaryController` has 6 focused test files).

### 4. Code Complexity (598 prod files, ~66K lines)

**48 files exceed 500 lines** (8% of codebase). Average file size is ~110 lines — healthy overall, but the tail is heavy.

**God classes (>1000 lines):**

| File | Lines | Methods | Module | Concern |
|------|-------|---------|--------|---------|
| `LuceneIndexRuntime.java` | 2097 | 95 | adapters-lucene | Owns all Lucene index operations — search, indexing, schema migration, vector ops, soft deletes. Does everything. |
| `AgentLoopService.java` | 1989 | 42 | app-agent | Full agent loop — tool dispatch, conversation history, streaming, cancellation, tracing |
| `KnowledgeHttpApiAdapter.java` | 1381 | 23 | app-services | Maps HTTP API to gRPC calls to the Knowledge Server |
| `SearchOrchestrator.java` | 1278 | 18 | indexer-worker | Multi-stage search pipeline with hybrid fusion, SPLADE, reranking |
| `KnowledgeServer.java` | 1266 | 34 | indexer-worker | gRPC server setup + service registration (also has 10 `@SuppressWarnings`) |
| `GrpcIngestService.java` | 1186 | — | indexer-worker | Ingestion gRPC service |
| `AppFacadeBootstrap.java` | 1164 | 40 | app-services | Wires together all sub-services at startup |
| `RagContextOps.java` | 1126 | — | indexer-worker | RAG context retrieval operations |
| `IndexingLoop.java` | 1121 | 45 | indexer-worker | Main indexing loop (also has 18 `@SuppressWarnings` — highest in codebase) |
| `RemoteKnowledgeClient.java` | 1088 | — | app-services | gRPC client for Knowledge Server |
| `ResolvedConfigBuilder.java` | 1073 | 61 | configuration | Builds resolved config from multiple sources |
| `InferenceLifecycleManager.java` | 1053 | — | app-inference | Manages llama-server process lifecycle |
| `AiInstallService.java` | 1007 | — | ui | AI model download and installation |
| `LocalIntentTranslatorConfig.java` | 1007 | — | ai-bridge | Intent translator configuration (likely a large config/builder) |
| `RuntimeActivationService.java` | 1006 | — | ui | Runtime activation orchestration |

**Concentration:** `indexer-worker` has 6 of the top 15 largest files. This module is the complexity center of the codebase.

**`LuceneIndexRuntime` is the single most critical hotspot** — 2097 lines, 95 methods. It handles search queries, document indexing, schema migration, vector operations, soft deletes, and more. This is a clear god class that should be decomposed.

**Correlation with suppression counts:** The files with the most `@SuppressWarnings` (`IndexingLoop` 18, `KnowledgeServer` 10, `FullCoverageSummarizer` 7) are all in the >800-line range, confirming that complexity drives suppression needs.

### 5. Error Handling

**Scale:** 791 `catch (Exception)`, 16 `catch (Throwable)`, 384 specific catches, 303 `throws Exception` declarations, ~128 `catch (Exception ignored)` blocks.

**The `ui` module is the outlier:** 269 broad catches vs. 46 specific (6:1 ratio). Every other module is under 2:1. This is an accepted tradeoff at REST boundaries — broad catch → log → 500 response.

**The gRPC layer is the cleanest:** Both `GrpcSearchService` and `AiServiceImpl` follow a principled hierarchy: specific typed first, broad `catch (Exception)` last with `Status.INTERNAL`, always call `responseObserver.onError()`, always record span exception.

**`adapters-lucene` is also well-structured:** 71 specific vs. 21 broad. Exemplary cleanup patterns in `ComponentsFactory`.

**Genuinely concerning cases:**
- **`FallbackPolicy.java`** — when the fallback translator fails, exception is silently swallowed (no log), returning `{}` as intent translation. Double-failure path is completely invisible for diagnosis.
- **`SsotCommitMetadataSource.java:196`** — YAML load failure silently skipped (no log). Produces incorrect commit fingerprints used for schema compatibility → silent data-correctness risk.
- **`RuntimeActivationService.java:967`** — path resolution returns null with no log, cascading into callers that may silently pick no server variant.

**Systemic patterns:**
- 303 `throws Exception` declarations — widespread anti-pattern forcing callers to over-broad catches, concentrated in `Launcher`, `EmbeddingClient`, `AnnSearchClient`
- `catch (Throwable)` in pure Java code (`SummaryController`, `DocumentFetcher`) — overly broad, `Exception` would suffice
- Broad `catch (Exception)` in streaming paths doesn't restore `InterruptedException` thread interrupt flag

**Positive:** `InterruptedException` handled correctly in 100+ explicit catch blocks (restores interrupt flag). The `KnowledgeServerSafeMetrics` class is a clean example of intentional suppression with clear naming convention.

### 6. API Surface (65 endpoints + CORS)

**Well-designed overall.** Consistent `kebab-case` naming, clear resource-group prefixes (`/api/ai/`, `/api/indexing/`, `/api/knowledge/`, etc.), strong security model (session token + loopback-only CORS).

**Error contract is solid:** `ApiErrorHandler.buildTypedResponse()` produces structured JSON with `error`, `errorCode`, `errorClass`, `retryable`, and optional `requestId`. Path/class sanitization prevents info leakage. Global exception handler catches anything that escapes per-controller try-catch.

**Two error format deviations:**
- `TimeSeriesController` uses a custom error format (missing `errorClass`, `retryable`, uses freeform string error codes instead of `ApiErrorCode` enum)
- `handleDebugDashboard` returns raw text on error (all other endpoints return JSON)

**Input validation is good but has silent defaults:**
- `Boolean.parseBoolean()` on query params like `force`, `dryRun` — input of `"yes"` silently becomes `false`
- Numeric limits (`limit`, `keepLatest`) silently fall back to defaults on invalid input
- `DELETE /api/indexing/roots` accepts body JSON — some proxies/clients strip DELETE bodies

**One structural gap:** Knowledge routes (`/api/knowledge/*`) are late-bound after Worker startup. During that window, requests get Javalin's default 404 HTML, not a structured JSON 503. Clients can't distinguish "Worker not ready (retry)" from "endpoint doesn't exist."

**No incomplete endpoints.** All 65 registered endpoints have complete implementations. No TODO/FIXME/stubs in any controller.

---

## Revised Priority Synthesis — After Deep Investigation

The initial code-first scan identified 12 items across 4 tiers. Deep investigation revealed that the top 3 "structural risks" were **already addressed by prior decomposition work**, and 2 of 3 "correctness risks" are **not real risks in practice**. The priority order has fundamentally changed.

### Tier 1 — The one real structural issue

1. **`app-services` monolithic aggregator** (18 first-party deps, 9 as `api()`) — the only Tier 1 structural issue that hasn't been addressed. Organic growth produced a leaky aggregator with 3 domain packages (`worker/`, `gpl/`, `vdu/`) that don't belong together. The `worker/` package alone (20 classes) is used by 8 classes in `ui` and should be its own module. **Resolution:** Extract `app-worker-client`, then `app-gpl` and `app-vdu`. Switch remaining deps from `api()` to `implementation()`.

### Tier 2 — Incremental improvements with clear value

2. **`IndexingLoop` reflection shims** — 16 `@SuppressWarnings("unused")` private methods that exist solely for test reflection compatibility. Removing them requires updating `IndexingLoopTest` to call `IndexingDocumentOps` directly. **~100 lines saved, eliminates the #1 suppression hotspot.**

3. **`GrpcIngestService` `instanceof` downcasts** — 6 places downcast `JobQueue` to `SqliteJobQueue`. Either promote operations to the interface or extract `SqliteIngestOps`.

4. **4 remaining `LuceneIndexRuntime` extractions** — `searchSplade()` → `HybridSearchOps`, `applyComponents()` → `ComponentsFactory`, `start()` recovery → `StartupPolicyHandler`, `updateDocument*` → `DocumentUpdateOps`. **~270 lines saved.**

5. **`SsotCommitMetadataSource` YAML catch** — add `LOG.warn` at lines 196 and 217. The parity guard catches mismatches at startup, but a silent commit-time log would close the diagnostic gap. **2-line fix.**

6. **`RagContextOps` → `ChunkRerankerOps` + `ChunkDiversificationOps`** — GPU arbitration and MMR diversification are self-contained clusters. **~300 lines extracted.**

7. **`GrpcIngestService` → `VduLifecycleOps`** — 4 VDU RPC methods form a cohesive state machine. **~300 lines extracted.**

### Tier 3 — Test and quality gaps

8. **`ai-bridge` module undertested** (0.32 ratio, 76 prod files) — complex concurrent logic in `TranslatorSessionPool`, `IntentPipeline`.

9. **`AgentLoopService` god class + god test** — 1989 lines / 42 methods, 60 flat `@Test` methods. Not yet decomposed like LuceneIndexRuntime/indexer-worker.

10. **Knowledge routes late-bind gap** — pre-Worker-startup requests get HTML 404 instead of JSON 503.

### Tier 4 — Design debt (lower urgency, correct approach documented)

11. **303 `throws Exception` declarations** — resolution requires a domain exception hierarchy + incremental leaf-to-root narrowing. Infrastructure for the fault barrier already exists (`ApiErrorHandler` + `ApiErrorCode`).

12. **`adapters-lucene` `api()` leakage** — 4 project deps + Lucene + Jackson exposed to 8 consumers. Switch to `implementation()` and let consumers declare direct deps.

### Dropped from priority list (not real risks)

- ~~`FallbackPolicy.java` silent double-failure~~ — `{}` is dropped at IPC boundary, never reaches Worker. Near-impossible trigger condition.
- ~~`PipelineValidator` zero tests~~ — only called from `simulate` CLI, not any live path. A bug produces wrong CLI output, not data corruption.
- ~~`LuceneIndexRuntime` god class~~ — already decomposed into 13 collaborator classes. It's a wide facade, not a logic concentrator.
- ~~`indexer-worker` complexity~~ — already extensively decomposed via `*Ops.java` pattern (16+ companion classes, ~7300 lines extracted).

---

## Deep Investigation — Root Causes and Resolution Strategies

### Issue 1: `LuceneIndexRuntime` — Revised Assessment

**Initial assessment: 2097-line god class, 95 methods. Decomposition urgently needed.**
**Revised assessment: Already heavily decomposed. It's a wide facade, not a logic concentrator.**

The runtime package has undergone significant decomposition. 13 collaborator classes have been extracted:

| Extracted Class | Lines | Responsibility |
|----------------|-------|---------------|
| `WritePathOps` | ~150 | Single-doc write, batch, soft-delete |
| `ReadPathOps` | ~300 | Searcher lifecycle, projection, DocValues |
| `CommitOps` | ~80 | Lucene commit + metadata stamping |
| `PruneOps` | ~100 | Orphan-doc pruning |
| `IndexingCoordinator` | ~150 | Validation, backpressure, batch routing |
| `ComponentsFactory` | ~300 | Directory/Writer/SearcherManager assembly |
| `TextQueryOps` | 395 | BM25 query parsing, fuzzy, filters |
| `ChunkSearchOps` | 557 | Chunk search variants (BM25, vector, hybrid, SPLADE) |
| `HybridSearchOps` | 464 | RRF fusion, parallel execution |
| `DocumentQueryOps` | 527 | Doc counts, field fetch, embedding status |
| `SuggestOps` | 142 | Prefix/autocomplete |
| `FacetingEngine` | 208 | DocValues-based facet counting |
| `FolderBrowseEngine` | 458 | Folder enumeration, corpus iteration |

**What remains in the 2097-line facade:**
- ~60 public methods that delegate to collaborators (the API surface — 45 callers depend on this single type)
- 17 volatile fields + 6 atomic counters (concurrent lifecycle state)
- 11 private snapshot-accessor methods (null-guard pattern for volatile collaborators)
- 17 package-private test accessors
- ~4 non-trivial method bodies that haven't been extracted yet:
  - `searchSplade()` (40 lines) — inline SPLADE query building, should move to `HybridSearchOps`
  - `applyComponents()` (140 lines) — lambda-wiring boilerplate, could extend `ComponentsFactory`
  - `start()` recovery policies (90 lines) — corrupt-index recovery, could be a `StartupPolicyHandler`
  - `updateDocument`/`updateDocumentPaths` — cross-cutting read-modify-write ops

**Root cause of the size:** The class is large because it is the **sole entry point** for 45 callers. It's a wide facade (many methods) not a deep god class (concentrated logic). The mutable state management (17 volatile fields nulled on close, concurrent access guards) adds significant ceremony.

**Correct resolution:** Not a full decomposition (already done), but:
1. Extract the 4 remaining non-trivial method bodies (~270 lines savings)
2. Consider splitting the facade into read-facing and write-facing interfaces to reduce the API width visible to any single caller (SearchOrchestrator only needs search methods, IndexingLoop only needs write methods)
3. The 17 test accessors could move to a test-support companion if the test infrastructure supports it

**Priority downgrade:** This was initially Tier 1 item #1. With the facade insight, it drops to Tier 3 — the remaining extractions are incremental polish, not structural risk.

### Issue 2: `app-services` Monolithic Aggregator

**Root cause: organic growth, not deliberate design.** `AppFacadeBootstrap` is the single composition root for the entire Head process, wiring together all services in a specific initialization order across 1164 lines. Three domain packages (`worker/`, `gpl/`, `vdu/`) were added to this module over time because the bootstrap needed to instantiate them — not because they belong together architecturally.

**Why 18 first-party deps (not 17):** `ai-bridge` was missed in the initial count. The module also pulls `lightgbm4j`, `pdfbox`, `lucene-*` runtimeOnly, `grpc-netty-shaded`, and `jackson-databind` as external deps.

**The real classpath pollution driver:** 9 modules are `api()` scope (not `implementation()`), meaning every consumer of `app-services` gets the entire transitive graph on their compile classpath. This compensates for the lack of proper module boundaries — consumers need certain types, so rather than giving them direct deps, `app-services` re-exports everything.

**Consumer analysis reveals the actual coupling:**
- `ui` uses: `AppFacadeBootstrap`, `KnowledgeServerBootstrap`, `KnowledgeHttpApiAdapter`, `WorkerFeatureCache`, `OnnxModelStatus` — 8 classes in `ui` reference the `worker/` package directly
- `app-launcher` uses: only `AppFacadeBootstrap`

**Recommended decomposition (3 new modules):**

| New Module | Contents | Key Dependencies | Consumer |
|-----------|----------|-----------------|----------|
| `app-worker-client` | `worker/` package (20 classes: `RemoteKnowledgeClient`, `KnowledgeServerBootstrap`, `WorkerSpawner`, `GrpcCircuitBreaker`, etc.) | `ipc-common`, `app-api`, `configuration`, `reranker`, `ai-bridge`, gRPC | `ui` directly |
| `app-gpl` | `gpl/` package (6 classes: `GplJobCoordinator`, `LambdaMartReranker`, etc.) | `app-worker-client`, `app-api`, `lightgbm4j`, `reranker` | `app-services` |
| `app-vdu` | `vdu/` package (5 classes: `OfflineCoordinator`, `VduBatchProcessor`, etc.) | `app-inference`, `ai-bridge`, `app-worker-client`, `pdfbox` | `app-services` |

**`app-services` after split:** retains only `AppFacadeBootstrap`, `DefaultAppFacade`, and `bootstrap/` helpers — becomes a thin wiring module with ~6 deps instead of 18.

**Lowest-cost first step:** Extract `worker/` into `app-worker-client`. This alone eliminates `pdfbox`, `lightgbm4j`, ONNX reranker, and several sub-modules from the `ui` compile classpath, and breaks the coupling that forces `ui` to pull all inference infrastructure just to reference `KnowledgeServerBootstrap`.

### Issue 3: `indexer-worker` Complexity Concentration — Revised Assessment

**Initial assessment: 6 of the top 15 largest files. Module doing too much.**
**Revised assessment: Extensively decomposed via `*Ops.java` pattern. Remaining size is structural, not logic concentration.**

The module already has 16+ extracted companion classes totaling ~7300 lines across `server/ops/`, `loop/ops/`, `queue/`, and `services/` ops files. The large files remain large because of:

1. **Stateful orchestration** — `KnowledgeServer.start()` (340-line boot sequence wiring 12+ subsystems) and `IndexingLoop` (main loop thread with 5-phase backfill scheduling) genuinely need single-owner coordination.

2. **Protocol-bound groupings** — `GrpcIngestService` has 20 gRPC override methods it cannot split without defining a new protobuf service. `SearchOrchestrator` is the single-dispatch pipeline for all search variants.

3. **Reflection-coupling debt** — `IndexingLoop` has 16 `@SuppressWarnings("unused")` private shim methods (lines 743–831) that exist solely because tests access them via `getDeclaredMethod`. These are dead weight from a pre-extraction API.

**Actionable extractions (highest value, lowest risk):**

| Extraction | From | Lines Saved | Reason |
|-----------|------|-------------|--------|
| Kill reflection shims | `IndexingLoop` | ~100 | Update tests to call `IndexingDocumentOps` directly. Removes 16+ suppressions. |
| `ChunkRerankerOps` | `RagContextOps` | ~150 | GPU arbitration + lazy reranker init is fully self-contained |
| `VduLifecycleOps` | `GrpcIngestService` | ~300 | 4 VDU RPC methods + helpers form a cohesive state machine |
| `SqliteQueueHealthOps` | `SqliteJobQueue` | ~100 | Backup, integrity check, health snapshot |
| `QueryPerformancePrediction` | `SearchOrchestrator` | ~50 | Pure function of query + index stats |

**`GrpcIngestService` `instanceof` smell:** 6 places downcast `jobQueue instanceof SqliteJobQueue`. The `JobQueue` interface is incomplete — either promote these operations to the interface or wrap them in `SqliteIngestOps`.

**Priority downgrade:** Like LuceneIndexRuntime, this was initially Tier 1. With the existing decomposition revealed, it drops to Tier 3 — incremental extraction opportunities, not structural risk. The reflection shim cleanup is the highest-value item (eliminates the #1 `@SuppressWarnings` hotspot).

### Issues 4–6: Silent Failure Paths — Revised Assessment

#### `FallbackPolicy.java` — NOT a real risk

**Initial assessment:** Silent double-failure, `{}` as intent translation, data-correctness risk.
**Revised assessment:** The `{}` is dropped at the IPC boundary and never reaches the Worker.

The call chain: `FallbackPolicy.fallbackIntent()` → returns `TranslationResult("{}", ...)` → stored in `Query.context` → `RemoteKnowledgeClient.search()` **completely ignores** `Query.context`. It only extracts raw query text and builds the gRPC `SearchRequest` without any context field. The search falls through to standard BM25/hybrid behavior.

Furthermore, `StubLocalLlmTranslator` is a trivial JSON builder — the only way it throws is if Jackson's `writeValueAsString` fails on a plain object, which is near-impossible. The double-failure path requires a JVM-level pathological state.

Telemetry correctly records `reason_code=fallback_failed` and `llm.fallback_total`, so observability is intact.

**Priority: Drop entirely.** Not a real correctness risk.

#### `SsotCommitMetadataSource.java:196` — Narrow but real

**Initial assessment:** Silent YAML skip → wrong fingerprints → silent schema mismatch.
**Revised assessment:** The failure IS silent at commit time, but becomes a **loud failure** at the next startup.

If YAML fails during commit, the index gets stamped with default `similarity_fp` and `boosts_fp = SHA-256("{}")`. On next startup, `IndexMetadataParityGuard.checkOnOpen()` compares stored fingerprints against freshly computed ones. If the real config has custom values, this triggers `IllegalStateException("Shard is read-only due to parity mismatch")` — a hard, visible failure.

The risk materializes in two narrow scenarios:
1. First-ever commit in a test/CI environment without YAML, then opening that index artifact in a real environment
2. Transient YAML unreadability during a commit that resolves before next startup

The comment `// Fallback for tests` signals the authors know this is a test accommodation. The parity guard catches it at startup — so it's silent fingerprint corruption that becomes a loud failure later, not silent data corruption.

**Priority: Low.** The parity guard provides a safety net. Adding a `LOG.warn` at the catch site would close the diagnostic gap.

#### `PipelineValidator` zero tests — NOT a real risk

**Initial assessment:** Real DAG validation logic, completely untested, bug would silently produce invalid pipelines.
**Revised assessment:** The validator is only called from the `simulate` CLI command, not any live path.

`PipelineValidator` is called in exactly one place: `Launcher.validatePipeline()` → `Launcher.runSimulate()`. It is NOT wired into startup, indexing, or search. The Worker does not validate pipeline structure at runtime via this class. The actual search execution path is governed by `PipelineConfig` from the gRPC proto, set at call sites (`PipelineConfigs.HYBRID` / `PipelineConfigs.TEXT`), not derived from SSOT pipeline JSON.

A bug in the validator would produce incorrect `simulate` CLI output — not index corruption or incorrect search behavior.

**Priority: Drop from correctness tier.** Move to "nice to have" — if the validator ever gets wired into a startup guard, tests become necessary. Currently it's dead-weight infrastructure.

### Resolution Strategies (from Internet Research)

#### Decomposing `app-services`: Composition Root vs. Leaky Aggregator

The industry distinction is clear:
- A **composition root** depends on everything but nothing depends on it — it's the app entry point. Uses `implementation()` only.
- A **leaky aggregator** sits mid-graph and re-exports via `api()` "for convenience." This is the anti-pattern `app-services` exhibits.

**Migration strategy (from Gradle best practices + Spring Boot starter patterns):**
1. Audit actual usage: for each consumer, determine which of the 18 modules it actually imports
2. Switch aggregator from `api()` to `implementation()` — this breaks compilation in consumers relying on transitives (which is exactly what you want to find)
3. Each consumer adds only the modules it actually needs as direct dependencies
4. The aggregator either becomes the true composition root (if it's the app entry point) or gets split

This aligns with the `app-worker-client` / `app-gpl` / `app-vdu` extraction plan from the code investigation.

#### `throws Exception` Proliferation: The Exception Barrier Pattern

Oracle's Effective Java Exceptions framework defines the correct model:
- **Contingencies** (checked): expected alternate outcomes callers must handle → narrow, domain-specific checked exceptions
- **Faults** (unchecked): unexpected failures → propagate to a **fault barrier** (global handler)

**Incremental narrowing strategy:**
1. Define a domain exception hierarchy first (`JustSearchException`, `IndexFaultException`, `QueryParseException`, etc.)
2. Start at the leaves — methods that don't call other `throws Exception` methods
3. Wrap at boundaries — when a leaf calls a library that throws `IOException`, wrap into domain exception right there
4. Work upward — once leaves are narrowed, callers can be narrowed too
5. Convert faults to unchecked — many `throws Exception` are actually faults. Convert to `RuntimeException` subclasses caught only at the fault barrier

**This codebase already has the fault barrier:** `ApiErrorHandler.buildTypedResponse()` with the `ApiErrorCode` enum. The infrastructure is in place; the missing piece is the domain exception hierarchy and incremental signature narrowing.

#### Broad `catch (Exception)` at REST Boundaries: Accepted Pattern

Every major framework (Javalin, Spring Boot, Micronaut) documents `catch (Exception)` at the **outermost API boundary** as the recommended pattern. This IS the fault barrier.

**Verdict:** The 269 broad catches in the `ui` module are NOT a problem if they're at the REST handler boundary. The gRPC layer's principled hierarchy (specific first, broad last with `Status.INTERNAL`) is the gold standard. The real problems are broad catches **inside** business logic — which maps to the `FallbackPolicy` and `SsotCommitMetadataSource` silent failures, not the REST handlers.

#### LuceneIndexRuntime: Already a Facade

The research confirms that Elasticsearch's `IndexShard` follows the same pattern this codebase has already adopted: the shard is a coordinator that delegates to purpose-built components (`Engine`, `SearcherManager`, various `*Service` classes). `LuceneIndexRuntime` with its 13 extracted `*Ops` collaborators matches this architecture. The remaining 2097 lines are the facade's API surface + lifecycle state management — which is inherent to being the single entry point for 45 callers.

---

## Observations (Meta — About This Experiment)

### What the experiment proved

1. **Surface-level code signals are misleading.** The initial scan flagged `LuceneIndexRuntime` (2097 lines, 95 methods) as the #1 priority. Deep investigation revealed it's already a facade with 13 extracted collaborators — the size is inherent to its role, not a sign of neglect. Similarly, `indexer-worker`'s 6 large files have 16+ companion `*Ops` classes totaling ~7300 lines. **Line count alone is a poor proxy for structural risk.** The real signal is whether decomposition has already happened.

2. **"Correctness risks" require call-chain tracing, not pattern matching.** Grep found `catch (Exception ignored)` with `"{}"` in `FallbackPolicy` and flagged it as a data-corruption risk. Tracing the actual call chain revealed that `RemoteKnowledgeClient` drops the context entirely at the IPC boundary — the `{}` never reaches the Worker. Similarly, `PipelineValidator` with zero tests looked dangerous until we discovered it's only called from a CLI diagnostic command. **Pattern-matching for anti-patterns produces false positives; only call-chain analysis reveals actual impact.**

3. **The tempdoc backlog and the code-first scan converge on the same issues — but tempdocs got there first.** Tempdocs 150, 157, 158 already covered `LuceneIndexRuntime` decomposition. Tempdoc 163 covered class size standards. Tempdoc 69 covered exception swallowing. The code-first approach independently validated these priorities but didn't surface anything the tempdoc process had missed — except for `app-services` as a leaky aggregator, which is a cross-cutting structural pattern that feature-driven work tends not to flag.

4. **The experiment's unique contribution: `app-services` module decomposition.** This is the one genuinely new finding. The leaky aggregator pattern (18 deps, 9 as `api()`, 3 unrelated domain packages) was not surfaced by any of the 278 existing tempdocs. It's the kind of issue that only appears when you look at the dependency graph as a whole — not when you're working on any single feature.

5. **Two-phase assessment is the correct approach.** Phase 1 (surface scan: line counts, grep patterns, dependency counts) takes ~20 minutes and produces a candidate list. Phase 2 (deep investigation: reading actual code, tracing call chains, checking for prior work) takes ~45 minutes and typically eliminates 50–70% of the candidates. Skipping Phase 2 would have produced a misleading priority list.

### Critical self-correction: `app-services` was already analyzed

6. **Tempdoc 57 (modularity-dependency-analysis) explicitly analyzed `app-services` and concluded: "NOT a God Module — PROPERLY STRUCTURED ORCHESTRATION FACADE."** The 21-dependency count was judged as "justified and documented," with each dependency serving a clear purpose, no circular dependencies, and well-encapsulated subsystems. The prior analysis rated the coupling risk as LOW with "documented facade pattern, well-structured." **My "one genuinely new finding" was not new at all — a prior agent reached the opposite conclusion with deeper architectural context.**

7. **This is the experiment's most important meta-finding.** The code-first approach, lacking architectural intent, misidentified a deliberate design choice as a structural problem. The `app-services` module is a composition root by design — the wide dependency set IS the point. A prior agent with access to architecture docs and design rationale understood this; my code-only analysis could not.

### Recommendations for this repo

8. **The experiment's value is methodological, not in its specific findings.** Every actionable item it surfaced was already known. Its contribution is demonstrating that a two-phase code-first scan (surface → deep investigation) converges with tempdoc-driven analysis — validating both approaches independently.

9. **The experiment proves tempdocs carry essential context.** Code signals alone produce false positives (LuceneIndexRuntime "god class"), false alarms (FallbackPolicy "data corruption"), and misframed findings (app-services "leaky aggregator"). Tempdocs encode the WHY that code cannot.

### On the novelty

8. **Web research confirmed this is genuinely novel.** No published workflow exists where an AI agent reads code to independently form priorities. CodeScene's MCP server comes closest (code health scores an agent could query), but no one has published a closed-loop where the agent autonomously decides priorities from code signals. The two-phase approach (surface scan → deep investigation → revised priorities) is the methodological contribution.
