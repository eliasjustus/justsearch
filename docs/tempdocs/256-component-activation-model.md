---
title: "256: Component Activation Model"
type: tempdoc
status: done
created: 2026-03-04
parent: 250
---

> NOTE: Noncanonical doc (architecture + implementation plan). May drift.

# 256: Component Activation Model

## Purpose

Replace JustSearch's monolithic `SearchMode` enum with a component activation
model where each pipeline stage is independently togglable. "Mode" becomes a
named preset that suggests a component set, not a hard gate that prevents
combinations.

Extracted from [tempdoc 250](250-pipeline-routing-architecture.md) Phase 4.
Tempdoc 250 identified the root cause (mode = monolithic routing decision) and
its Phases 1-3 solved the symptoms (wrong gates, missing debug scores, no
coverage info). This tempdoc addresses the structural coupling itself.

---

## Background

### The Problem

The `SearchMode` enum (`TEXT`, `VECTOR`, `HYBRID`, `SPLADE`) in
`indexing.proto` controls which retrievers run, which rerankers are eligible,
and which debug scores are populated. This creates a coupling matrix where
adding a new combination (e.g., HYBRID + cross-encoder) requires modifying
mode gates in multiple files across two processes.

Four problems emerged from tempdoc 250's analysis:

| Problem | Status after 250 Phases 1-3 |
|---------|---------------------------|
| **P1:** Cross-encoder blocked for HYBRID | Guard kept (empirically validated), env var added for re-evaluation |
| **P2:** Debug scores overwritten in chunk merge | Fixed — prefix-based key separation |
| **P3:** No index capability awareness | Fixed — coverage counting + search response + UI banner |
| **P4:** No structured component execution reporting | Partially addressed — per-component flags added (1c), provenance added (Phase 2) |

Phases 1-3 were additive instrumentation. This tempdoc is a structural
refactor of the mode routing itself.

### Why Now vs. Later

Arguments for:
- Every new component (ColBERT, learned sparse, query rewriting) will require
  touching the mode switch in `SearchOrchestrator` — the coupling tax grows
- The 487-line `execute()` method is the largest function in the codebase
- Industry consensus is unanimous: no production search system uses a mode enum

Arguments against:
- The current system works — no user-visible bugs from mode coupling
- Phases 1-3 solved the acute symptoms without architectural surgery
- The refactor touches the hottest code path (every search query)

This tempdoc scopes the work so it can be evaluated and scheduled on merit.
Implementation is not assumed.

---

## Industry State of the Art

Every major search system studied uses independently configurable pipeline
stages rather than a mode enum. The patterns converge on two models.

### Linear Stage Chains

**Vespa** — Searcher chain model. Each `Searcher` implements a bidirectional
interceptor pattern (`search()` → pass downstream → post-process). Ordering is
declarative (`@After("AuthenticationSearcher")`). Retrieval mode is implicit in
the YQL query structure (which operators appear), not a flag. Phased ranking
(first-phase → second-phase → global-phase) with independent `rerank-count`
gating per phase.

**OpenSearch** — Named search pipelines registered server-side. Ordered
processor lists with `phase_results_processors` (between query and fetch) and
`response_processors` (after fetch). Reranking is a processor in the list —
add it to activate, remove it to deactivate. Query references pipeline by name.

**scikit-learn** — `Pipeline` with `'passthrough'` sentinel to bypass a stage
at runtime. Simplest form of component activation: named stage list where each
can be replaced with a no-op.

### Compositional Trees / DAGs

**Elasticsearch 8.16** — Retriever tree. `standard`, `knn`, `rrf`, `linear`,
`text_similarity_reranker` are composable nodes. The query structure IS the
pipeline definition. No mode enum; presence/absence of a node activates it.

**Qdrant 1.10** — Universal Query API. `prefetch` array defines retrieval legs,
`query.fusion` defines merge, nesting adds reranking. Each component is a
structural element in the request JSON.

**Haystack** — Explicit DAG with `add_component()` / `connect()`. Independent
branches run in parallel (BM25 and embedding retrieval). `DocumentJoiner`
merges, `TransformersSimilarityRanker` reranks.

### Key Consensus

| Principle | Every system studied |
|-----------|---------------------|
| No mode enum | Retrieval, fusion, and reranking are orthogonal concerns |
| Presence = activation | A component runs because it's in the pipeline definition, not because a flag says so |
| Stage isolation | Each stage sees candidates + scores from the previous stage, nothing else |
| Named presets | Convenience aliases for common component sets (Vespa rank profiles, OpenSearch named pipelines) |

### Applicability to JustSearch

JustSearch is a single-process desktop app, not a distributed cluster. The
linear stage chain (OpenSearch/Vespa pattern) is the right fit:

- Current pipeline is already linear: retrieval → fusion → LambdaMART → cross-encoder
- No need for parallel DAG branches (BM25 and dense run inside a single Lucene call)
- Named presets map naturally to the existing mode concept (backwards compat)
- `passthrough`-style bypass is trivial to implement in Java

The DAG model (Haystack/Elasticsearch) would be over-engineered for this use
case. The retriever tree (Qdrant/Elasticsearch) is a good API design reference
but the internal execution model should stay linear.

---

## Current Architecture

### SearchMode Enum

**Definition:** `modules/ipc-common/src/main/proto/indexing.proto` lines 11-16

```protobuf
enum SearchMode {
  SEARCH_MODE_TEXT = 0;    // BM25 text search (default)
  SEARCH_MODE_VECTOR = 1;  // KNN vector similarity search
  SEARCH_MODE_HYBRID = 2;  // Combined BM25 + KNN with RRF fusion
  SEARCH_MODE_SPLADE = 3;  // Standalone SPLADE sparse retrieval
}
```

Used in `SearchRequest.mode` (field 3) and `SearchResponse.effective_mode`
(field 7, as string).

### Component Activation Matrix

What each mode enables today:

| Component | TEXT | VECTOR | HYBRID | SPLADE | Gate type |
|-----------|------|--------|--------|--------|-----------|
| Query text parsing | Y | - | Y | Y | Hard |
| Query vector encoding | - | Y | Y | - | Hard |
| BM25 search | Y | - | Y | - | Hard |
| SPLADE sparse encoding | - | - | Y (opt) | Y | Hard |
| KNN vector search | - | Y | Y | - | Hard |
| RRF fusion | - | - | Y | - | Hard |
| Sort modes | Y | - | - | - | Hard |
| Cursor pagination | Y | - | - | - | Hard |
| Facet computation | Y | - | - | - | Hard |
| Fuzzy correction | Y | - | - | - | Hard |
| QPP computation | Y | - | Y | Y | Hard |
| LLM query expansion | Y | - | - | - | Hard |
| Cross-encoder rerank | Y | - | Y (opt) | - | Hard |
| LambdaMART rerank | Y | Y | Y | Y | None (always) |
| Chunk merge | Y | Y | Y | Y | Soft (config) |
| Term highlighting | Y | - | Y | Y | Soft |

### Mode-Based Branching Locations

**Hard gates (block component execution):**

| File | Location | What it gates |
|------|----------|---------------|
| `SearchOrchestrator.execute()` | Lines 187-487 (4-case switch) | Which `indexRuntime.*` method is called |
| `SearchOrchestrator.execute()` | Lines 849-890 (chunk merge switch) | Which chunk search variant runs |
| `KnowledgeHttpApiAdapter.isExpansionEligible()` | Lines 93-100 | LLM expansion (TEXT only) |
| `KnowledgeHttpApiAdapter.isRerankerEligible()` | Lines 114-120 | Cross-encoder (TEXT + HYBRID opt) |

**Soft consumers (metadata/logging, not execution gates):**

| File | Location | What it reads |
|------|----------|---------------|
| `SearchOrchestrator` | Lines 161-168 | `effectiveMode` string for response |
| `SearchOrchestrator` | Lines 709-712 | Debug score population (BM25 "sparse" key) |
| `TextAnalysisUtils.computeMatchedFields()` | Line 87 | Returns `"vector"` for VECTOR mode |
| `HighlightingOps.computeQueryTerms()` | Line 386 | Skips term extraction for VECTOR mode |
| `GplJobCoordinator.search()` | Line 406 | Forces TEXT mode for GPL eval |
| `SearchTool` (agent) | Line 53 | Default mode from env var |
| `useSearch.ts` (frontend) | Line 157 | Hardcoded `"hybrid"` |

### The Core Difficulty

The 4 modes call 4 different `indexRuntime` methods with different signatures:

```
TEXT    → indexRuntime.search(Query luceneQuery, Sort sort, int limit, ...)
VECTOR  → indexRuntime.searchVector(float[] queryVector, int limit, ...)
HYBRID  → indexRuntime.searchHybrid(Query, float[], int limit, ...)
         or searchHybridSplade(Query, float[], Map<String,Float>, int limit, ...)
SPLADE  → indexRuntime.searchSplade(Map<String,Float>, int limit, ...)
```

A component activation model needs an abstraction over these divergent
signatures. This is the hardest part of the refactor.

Additionally, TEXT mode supports features that no other mode does: cursor
pagination, sort modes, facets, fuzzy correction. These are tied to Lucene's
sorted collector API. They aren't "retrieval components" — they're query
execution features that only work with BM25's `Query`-based API.

### Data Flow

```
Frontend (useSearch.ts, hardcoded "hybrid")
  → KnowledgeSearchController (extracts mode string)
    → KnowledgeHttpApiAdapter.search() (parses to enum, gates expansion/reranking)
      → gRPC SearchRequest (mode field)
        → SearchOrchestrator.execute() (main switch, chunk merge switch)
          → LuceneIndexRuntime.search*() (mode-specific method)
```

---

## Architectural Direction

### Target: PipelineConfig Replaces Mode Enum

```
Current:  mode=HYBRID → hardcoded set of components fire
Target:   config={sparse:true, dense:true, fusion:"rrf"} → each component independently activated
          preset="hybrid" → convenience alias that expands to the config above
```

### Pipeline Stages

The search pipeline becomes 4 explicit stages. Each stage operates on the
output of the previous stage. No stage inspects "which mode was requested."

```
Stage 1 — Retrieval:   BM25 ∪ SPLADE ∪ Dense KNN  →  candidate lists
Stage 2 — Fusion:      RRF / CC / passthrough       →  merged ranking
Stage 3 — Fast rerank: LambdaMART                    →  re-scored top-N
Stage 4 — Deep rerank: Cross-encoder                  →  re-scored top-K
```

Chunk merge is a variant of Stage 1+2: parallel chunk retrieval + secondary
RRF fusion, applied when chunks exist and relevance sort is active.

### Named Presets (Backwards Compatibility)

Presets are convenience aliases. They map to a `PipelineConfig`:

| Preset | sparse | dense | splade | fusion | lambdamart | cross_encoder |
|--------|--------|-------|--------|--------|------------|---------------|
| `text` | Y | - | - | - | Y (if loaded) | Y (if loaded) |
| `vector` | - | Y | - | - | - | - |
| `hybrid` | Y | Y | opt | `rrf` | Y (if loaded) | opt |
| `splade` | - | - | Y | - | - | - |

The old `mode` field in proto maps to the corresponding preset. New clients can
send `PipelineConfig` directly for precise control.

### TEXT-Only Features

Cursor pagination, sort modes, facets, and fuzzy correction are not pipeline
stages — they're query execution features. They remain gated by whether a
Lucene `Query` object is available (i.e., sparse retrieval is active). This is
a natural capability constraint, not a mode gate:

- Sort/pagination require a Lucene `Sort` collector → only possible with BM25
- Facets require a Lucene `FacetsCollector` → only possible with BM25
- Fuzzy correction requires re-parsing the query → only possible with text input

These constraints are inherent to the Lucene API, not arbitrary mode gates.
The refactor makes this explicit: "sort requires sparse retrieval" instead of
"sort requires TEXT mode."

### Proto Migration Path

Standard backwards-compatible pattern (no `oneof`):

```protobuf
// New message (added alongside existing SearchMode enum)
message PipelineConfig {
  bool sparse_enabled = 1;
  bool dense_enabled = 2;
  bool splade_enabled = 3;
  string fusion_algorithm = 4;  // "rrf", "cc", "none"
  bool lambdamart_enabled = 5;
  bool cross_encoder_enabled = 6;
  int32 cross_encoder_window = 7;  // top-K for reranking
}

message SearchRequest {
  // ... existing fields ...
  SearchMode mode = 3 [deprecated = true];  // kept for wire compat
  PipelineConfig pipeline = <next_field_number>;  // new field
}
```

Server-side translation: if `pipeline` is set, use it. If only `mode` is set,
expand it to the corresponding preset `PipelineConfig`. If both are set,
`pipeline` wins.

---

## Work Items

### Phase A: API & Proto Design

**Goal:** Define the `PipelineConfig` message and wire it through the request
path without changing any execution behavior. Old `mode` field continues to
work identically.

- [x] **A1.** Add `PipelineConfig` message to `indexing.proto`. Add
  `pipeline` field to `SearchRequest`. Mark `mode` as `[deprecated = true]`.
- [x] **A2.** Add `PipelineConfig` Java record in `app-api` module (mirrors
  proto, used in `KnowledgeSearchRequest`).
- [x] **A3.** Add `expandPreset()` method in `KnowledgeHttpApiAdapter` that
  maps `SearchMode` enum → `PipelineConfig`. All existing searches go through
  this translation, producing identical behavior.
- [x] **A4.** Add `pipeline` field to frontend `SearchOptions` type. Default
  to null (uses preset expansion). Wire through `search.ts` → controller →
  adapter.
- [x] **A5.** Unit tests: preset expansion produces correct configs for all 4
  modes. Round-trip: mode → preset → config → same execution path.

**Exit criteria:** All existing tests pass. No behavioral change. `PipelineConfig`
is available on the request path but not yet consumed by execution logic.

**Status:** Done (commit `ecc19999`).

### Phase B: Head-Side Refactor

**Goal:** Replace mode gates in `KnowledgeHttpApiAdapter` with component
activation checks from `PipelineConfig`.

- [x] **B1.** Replace `isExpansionEligible()` mode check with
  `!config.denseEnabled()` (expansion is for sparse-only queries, not
  "TEXT mode" specifically).
- [x] **B2.** Replace `isRerankerEligible()` mode check with
  `config.crossEncoderEnabled()`. The per-mode policy becomes a preset
  concern (preset "hybrid" sets `crossEncoderEnabled` based on
  `JUSTSEARCH_RERANK_HYBRID_ENABLED`).
- [x] **B3.** Update `assembleProvenance()` to use config flags instead of
  `spladeExecuted` response flag for BM25-vs-SPLADE routing (or keep as-is
  if the response flag is more accurate — investigate). **No-op:** already
  uses response-side `spladeExecuted` flag, not request-side mode.
- [x] **B4.** Unit tests: expansion/reranking eligibility with various
  `PipelineConfig` combinations.

**Exit criteria:** `KnowledgeHttpApiAdapter` has zero references to `SearchMode`
enum (except in `expandPreset()`). All existing tests pass.

**Status:** Done (commit `ecc19999`, fix `b3ee1e24`). Note: `PipelineConfig.TEXT`
constant fixed to conservative default (`crossEncoderEnabled=false`); runtime
cross-encoder enablement is handled in `expandPreset()` based on `RerankerConfig`.

### Phase C: Worker-Side Refactor

**Goal:** Decompose `SearchOrchestrator.execute()` from a 4-case mode switch
into staged execution driven by `PipelineConfig`.

This is the largest phase. Sub-items are ordered by dependency.

- [x] **C1. Pipeline config reading + helpers.** `execute()` reads
  `pipeline` from proto request, falling back to `modeToDefaultPipeline(mode)`
  for old clients. `deriveEffectiveMode()` replaces the effectiveMode switch.
  QPP gating uses `pipeline.getSparseEnabled() || getSpladeEnabled()`.
  **Decided against `RetrievalPlan` record** — proto `PipelineConfig` fields
  are sufficient; no new abstractions needed.

- [x] **C2. Config-based retrieval dispatch.** Replaced the 4-case
  `switch(mode)` with `if/else if/else` chain on `pipeline.getDenseEnabled()`,
  `getSparseEnabled()`, `getSpladeEnabled()`. Existing `search*()` methods
  kept as-is (40+ external callers — Q1 resolved as Option B). Labeled block
  `retrieval:` preserves `break` semantics for fallback early-exits.

- [x] **C3. Response builder updates.** `toGrpcResponseBuilder()` takes
  `PipelineConfig pipeline` alongside `SearchMode mode` (mode kept for
  Phase D soft consumers: `computeMatchedFields`, `computeMatchSpans`).
  Inline mode checks replaced: `mode == TEXT` → sparse-only config check,
  `mode != VECTOR` → `hasLexicalTerms` boolean.

- [x] **C4. TEXT-only features.** Cursor pagination, sort, facets, and fuzzy
  correction naturally remain in the `else` (TEXT) branch of the if/else
  chain — gated by being in the sparse-only path, not by `mode == TEXT`.

- [x] **C5. Chunk merge alignment.** `mergeChunkResults()` takes
  `PipelineConfig` instead of `SearchMode`. Switch replaced with if/else on
  config flags. SPLADE and TEXT chunk paths unified (both use BM25 for chunks).

- [x] **C6. Fallback logic preservation.** All three fallback chains preserved
  identically via `break retrieval;` inside the labeled block:
  - HYBRID + blocked embeddings → TEXT (4 fallback paths)
  - SPLADE + missing encoder → TEXT (1 fallback path)
  - TEXT + null luceneQuery → empty result (1 early exit)

- [x] **C7. Dispatch tests.** New `SearchOrchestratorPipelineDispatchTest`
  (11 tests): `modeToDefaultPipeline` for all 4 modes + UNRECOGNIZED,
  `deriveEffectiveMode` for all config combinations, round-trip verification.
  Existing 692 GrpcSearchService tests pass unchanged (tests use `.setMode()`
  → Worker-side `modeToDefaultPipeline()` fallback handles expansion).

**Exit criteria:** `SearchOrchestrator.execute()` has zero `switch (mode)`
blocks. All existing tests pass. Performance: no measurable regression on
search latency (within noise on 1000-query benchmark).

**Status:** Done. `SearchMode` references remaining in `SearchOrchestrator`:
import, `request.getMode()` read (for fallback + soft consumer pass-through),
`toGrpcResponseBuilder` mode param (passed to Phase D methods), and
`modeToDefaultPipeline` helper. Zero switch blocks on mode.

**Design decisions:**
- Q1 → Option B (keep `search*()` methods, 40+ callers)
- Q2 → Option A (TEXT features gated by being in sparse-only branch)
- Q3 → Option A (same config for chunks, strict subset)
- Q4 → Non-issue (boolean checks, sub-microsecond)
- Skipped `RetrievalPlan`/`RetrievalPlanner` — proto fields sufficient

### Phase D: Soft Consumer Cleanup

**Goal:** Remove `SearchMode` from Worker internal helpers and Head-side
callers. After Phase D, `SearchMode` only appears in backwards-compat
bridges (`modeToDefaultPipeline`, `parseModeOrDefault`, `expandPreset`)
and the proto definition.

- [x] **D1.** `TextAnalysisUtils.computeMatchedFields()` — replace
  `SearchMode mode` parameter with `boolean hasLexicalQuery`. The check
  `mode == VECTOR` becomes `!hasLexicalQuery`. Caller in
  `toGrpcResponseBuilder` passes existing `hasLexicalTerms` boolean.
- [x] **D2.** `HighlightingOps.computeMatchSpans()` — remove `SearchMode
  mode` parameter entirely. The `mode == VECTOR` guard is redundant: the
  caller already wraps this in `if (hasLexicalTerms)`.
- [x] **D3.** Remove `SearchMode mode` from `toGrpcResponseBuilder()`
  signature. After D1+D2, no internal consumer reads it. Call site drops
  `mode` argument.
- [x] **D4.** Update tests for changed signatures. Same assertions.
- [x] **D5.** `GrpcSearchService` javadoc cleanup — remove `SearchMode`
  references, remove unused import.
- [x] **D6.** `SearchRpcOps` — replace `SearchMode mode` params with
  `PipelineConfig`. gRPC builder uses `.setPipeline()`.
- [x] **D7.** `RemoteKnowledgeClient` — replace `SearchMode mode` param.
  Cursor handling uses TEXT pipeline config constant.
- [x] **D8.** `GplJobCoordinator` — replace `.setMode(SEARCH_MODE_TEXT)`
  with `.setPipeline(textPipeline)`.
- [x] **D9.** `GrpcTestClient` (system-tests) — same pattern as
  `RemoteKnowledgeClient`.

**Exit criteria:** Zero `SearchMode` references outside of backwards-compat
bridges and proto definition. All tests pass.

**Status:** Done (commits `551cce94`, `c7b9195b`). Pipeline preset constants
deduplicated into `PipelineConfigs` utility in `ipc-common`. Verified:
zero `SearchMode` references remain outside bridges/proto.

### Phase E: Independent Component Activation

**Goal:** Decompose the 4 monolithic mode-shaped retrieval branches in
`SearchOrchestrator.execute()` into independently composable retrieval
stages. After Phase E, sending `{sparse: true, splade: true, dense: false}`
produces a meaningful result (SPLADE + BM25 fused) instead of silently
falling through to the TEXT branch.

**Key enabler:** `HybridSearchOps.executeHybrid()` already takes pluggable
`TextSearchLeg` and `VectorSearchLeg` lambdas. `searchHybridSplade` is
literally `executeHybrid(spladeLeg, vectorLeg, ...)`. The composable
primitives exist one layer below the orchestrator:

| Primitive | Method | What it does |
|-----------|--------|-------------|
| BM25 leg | `indexRuntime.searchText()` | Text retrieval |
| KNN leg | `indexRuntime.searchVector()` | Dense retrieval |
| SPLADE leg | `indexRuntime.searchSplade()` | Sparse learned retrieval |
| Fusion | `HybridFusionUtils.fuseWithRRF()` | Merge N candidate lists |
| Parallel exec | `HybridSearchOps.executeHybrid()` | Run 2 legs + fuse |

The orchestrator currently calls pre-composed convenience methods
(`searchHybrid`, `searchHybridSplade`). Phase E replaces these with direct
composition of the primitives based on config flags.

**Risk assessment:** This is the hottest code path (every search query).
The HYBRID branch alone is 100+ lines with 4 fallback paths and SPLADE
variant logic. No caller sends non-preset configs today. The main benefit
is structural (fallback logic becomes per-component, vector prep code
deduplicated, TEXT-only gate becomes explicit). The main risk is subtle
regression in degradation signaling (effectiveMode, vectorBlocked,
hybridFallback fields depend on which branch executed).

**Constraint: TEXT-only features.** Sort/pagination/cursor/facets require
`indexRuntime.search(Query, limit, projection, sort, cursor)` — the
`Query`-based overload. This only exists for BM25. When dense or SPLADE
legs are also active, fusion produces a merged result that can't be
re-sorted/paginated via Lucene collectors. These features remain gated
on "sparse is the sole active retrieval leg" — a Lucene API constraint,
not a mode gate.

**Structure of the decomposed dispatch:**

```
Stage 1: Prepare inputs (parse query, embed vector, encode SPLADE)
  - Each preparation is gated by its config flag
  - Fallbacks degrade gracefully (embedding fails → skip dense leg)

Stage 2: Execute enabled retrieval legs
  - if (wantSparse && sparseOnly) → use Query-based search (sort/cursor/facets)
  - if (wantSparse && !sparseOnly) → bm25Result = searchText(query, limit, filters)
  - if (wantSplade)                → spladeResult = searchSplade(weights, limit, filters)
  - if (wantDense)                 → denseResult = searchVector(vector, limit, filters)

Stage 3: Fuse if multiple legs produced results
  - 0 legs → empty result
  - 1 leg  → use directly (no fusion needed)
  - 2+ legs → fuseWithRRF(results, fusionAlgorithm)

Stage 4: Post-processing (only when sparse is the sole active leg)
  - Sort, pagination, cursor, facets, fuzzy correction
  - These require Lucene Query API — inherent constraint, not a mode gate
```

**Work items:**

- [x] **E1. Input preparation extraction.** `VectorPrepResult` and
  `SpladePrepResult` records + `prepareQueryVector()` / `prepareSpladeWeights()`
  helpers. Eliminates 3x vector embedding duplication and 2x SPLADE encoding
  duplication.
- [x] **E2. Independent retrieval execution.** Staged dispatch replaces
  4-branch if/else: sparse-only path (sort/cursor/facets) stays dedicated;
  composable path dispatches to optimized methods for standard combos
  (`searchHybrid`, `searchHybridSplade`) and primitive composition for
  novel combos (sparse+splade, dense+splade via `fuseLegs()`).
- [x] **E3. Generic N-way fusion.** `fuseLegs()` method: nested pairwise
  RRF for 2+ legs. Identical to direct `fuseWithRRF` for 2-leg cases.
- [x] **E4. Degradation signaling alignment.** `deriveActualMode()` computes
  effectiveMode from which legs actually ran. Centralized signaling block
  after retrieval sets hybridFallback/vectorBlocked/spladeSkipReason.
  OTEL span `search.mode` attribute updated post-degradation.
- [x] **E5. Chunk merge alignment.** Component-based gating: `chunkBm25`
  (sparse or splade) + `chunkKnn` (dense with vector available). Null-vector
  guard prevents KNN attempts during hybrid fallback.
- [x] **E6. New combination tests.** `SearchOrchestratorComposablePathTest`
  (7 integration tests) + 11 unit tests in `SearchOrchestratorPipelineDispatchTest`
  (`deriveActualMode` + multi-component `deriveEffectiveMode`).
- [x] **E7. Existing test verification.** 711 tests pass (1 pre-existing
  failure: `IndexerWorkerGuardrailsTest`).

**Exit criteria:** Met. All items verified.

**Status:** Done (commits `2b73de13`, `c90b33cf`, `a1d69ea2`). Live
verification against dev stack with 2006 docs confirmed all 7 pipeline
configurations produce correct results:

| Pipeline | totalHits | effectiveMode | Degradation |
|----------|-----------|---------------|-------------|
| `{sparse}` | 1034 | TEXT | — |
| `{dense}` | 5 | VECTOR | — |
| `{sparse, dense, rrf}` | 80 | HYBRID | — |
| `{splade}` | 1126 | SPLADE | — |
| `{sparse, splade}` (novel) | 10 | HYBRID | spladeExecuted=true |
| `{dense, splade}` (novel) | 10 | HYBRID | spladeExecuted=true |
| `{sparse, dense, splade}` | 93 | HYBRID | spladeExecuted=true |

Novel combinations (previously impossible with mode enum) now work through
the composable dispatch path. Agent chat verified end-to-end.

### Scope Boundaries

**Not in scope for this tempdoc:**

- **`LuceneIndexRuntime` refactor.** The 22 `search*()` convenience methods
  stay as-is — the orchestrator composes the underlying primitives directly.
  The convenience methods remain for external callers (tests, benchmarks).

---

## Remaining Phases (Planned)

Phases A–E delivered a composable retrieval layer. The following phases
complete the vision: making all 4 pipeline stages independently composable,
removing the deprecated mode enum, and activating novel combinations in
production paths.

### Phase F: Reranking Stage Composition

**Goal:** Make cross-encoder and LambdaMART independently composable,
controlled by PipelineConfig flags rather than hardcoded per-mode policy
in `expandPreset()`.

**Current state:** Cross-encoder eligibility is determined in the Head
process by `KnowledgeHttpApiAdapter.isRerankerEligible()` (line 142).
This checks `pipeline.crossEncoderEnabled()` — already pipeline-based.
But the flag itself is set by `expandPreset()` using hardcoded per-mode
policy:

| Mode | crossEncoderEnabled | Policy |
|------|---------------------|--------|
| TEXT | `rerankConfig.enabled()` | Always eligible |
| VECTOR | `false` | Never eligible |
| HYBRID | `rerankConfig.enabled() && hybridModeEnabled` | Opt-in env var |
| SPLADE | `false` | Never eligible |

The actual reranking pipeline in `doSearch()` (lines 498-602) runs
LambdaMART first, then skips cross-encoder if LambdaMART applied. This
ordering is hardcoded, not configurable.

**Industry context:** Modern search systems use multi-stage reranking
where each stage operates on the output of the previous stage. The
standard pattern is: retrieval → fast reranker (LambdaMART) → deep
reranker (cross-encoder). These stages should be orthogonal — enabling
cross-encoder shouldn't require disabling LambdaMART, and the ordering
should be explicit rather than implicit.

Reference implementations:
- OpenSearch: search pipelines with ordered `response_processors` — each
  reranker is a processor in the list, applied sequentially
- Vespa: phased ranking (first-phase → second-phase → global-phase) with
  independent `rerank-count` gating per phase
- Elasticsearch: retriever tree with `text_similarity_reranker` as a
  composable node

**Investigation findings (pre-implementation):**

- **KNN results carry text fields.** `searchVector()` passes
  `projectionFields=null`, so all stored fields (including `title`,
  `content_preview`) are returned. Cross-encoder on VECTOR-only results
  is safe — `extractQueryFocusedSnippet` degrades gracefully when no
  lexical match spans exist (uses document start).
- **`hybridModeEnabled` is dead after F1.** The `RerankerConfig` field
  and its env var (`JUSTSEARCH_RERANK_HYBRID_ENABLED`) are only consumed
  in `expandPreset()` line 995. With uniform cross-encoder eligibility,
  the field, env var parsing, and all constructor references can be
  removed.
- **LambdaMART reporting already exists but is incomplete.** The
  `buildPipelineExecution()` method (line 1200) already reports
  `lambdamart` as a component status, but uses hardcoded `"NOT_APPLIED"`
  instead of a structured skip reason.

**Work items:**

- [x] **F1. Uniform cross-encoder eligibility in `expandPreset()`.**
  All presets get `crossEncoderEnabled = rerankConfig.enabled()`. The
  VECTOR/SPLADE hard blocks become documented recommendations, not gates.
  `isRerankerEligible()` is unchanged — it already checks
  `pipeline.crossEncoderEnabled()`.

  Dead code removal: remove `hybridModeEnabled` from `RerankerConfig`
  record, its env var parsing in `fromEnv()`, and all constructor
  references. `PipelineConfig` static constants (`VECTOR`, `SPLADE`)
  keep `crossEncoderEnabled=false` as conservative compile-time defaults.

  Test updates:
  - `PipelineConfigPresetExpansionTest` — update VECTOR/SPLADE/HYBRID
    assertions (all `crossEncoderEnabled=true` when reranker enabled),
    remove `hybridModeEnabled` tests (lines 111-130)
  - `KnowledgeHttpApiAdapterHarmfulCombinationsTest` — replace
    `hybridMode_silentlySkipsReranker()` with test confirming HYBRID IS
    reranker-eligible
  - Update all `RerankerConfig` constructor calls (remove last boolean)

- [x] **F2. Allow LambdaMART + cross-encoder co-execution.** Remove the
  `else if (lambdaMartApplied)` branch at `doSearch()` line 544 (skip
  reason `LAMBDAMART_APPLIED`). After removal, the existing sequential
  flow runs LambdaMART first (~5ms), then cross-encoder on LambdaMART's
  top-K output (200-500ms). This is the standard 2-stage cascaded
  reranking pattern — the fast reranker surfaces better candidates into
  the deep reranker's window.

  No new code needed — just remove the skip branch. The existing
  `isRerankerEligible()` check and model-loaded check are sufficient
  gates.

- [ ] **F3. Explicit reranking stage ordering — deferred.** There are
  exactly 2 rerankers. The ordering is always LambdaMART → cross-encoder
  (fast → slow). A `repeated string rerank_stages` proto field would
  invite nonsensical configs (running the slow model first defeats the
  purpose) and adds API surface for zero practical benefit. Two
  sequential `if` blocks is the correct abstraction for 2 rerankers.
  Revisit if a third reranker is added.

- [x] **F4. Cross-encoder window from PipelineConfig.** The
  `crossEncoderWindow` field already exists in PipelineConfig but is
  ignored — `doSearch()` uses `rerankConfig.topK()` (default 20). Wire
  as override: `pipelineConfig.crossEncoderWindow() > 0` overrides
  `rerankConfig.topK()`. Three-line change.

- [x] **F5. LambdaMART skip reason reporting.** Track
  `lambdaMartSkipReason` in `doSearch()` with structured reasons:
  `NO_MODEL` (reranker null), `MODEL_NOT_LOADED`, `NO_RESULTS`,
  `INFERENCE_FAILED` (rerank returned null). Passed to
  `buildPipelineExecution()` replacing hardcoded `"NOT_APPLIED"`.

**Key files:**
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` — `doSearch()`, `expandPreset()`, `isRerankerEligible()`, `buildPipelineExecution()`
- `modules/reranker/src/main/java/io/justsearch/reranker/RerankerConfig.java` — remove `hybridModeEnabled`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/PipelineConfig.java` — no changes needed
- `modules/ipc-common/src/main/proto/indexing.proto` — no changes needed

**Test files impacted:**
- `modules/app-services/src/test/.../PipelineConfigPresetExpansionTest.java` — F1 preset assertions
- `modules/app-services/src/test/.../KnowledgeHttpApiAdapterHarmfulCombinationsTest.java` — F1 HYBRID guard, F4 window
- `modules/app-services/src/test/.../KnowledgeHttpApiAdapterProvenanceTest.java` — minimal (CE scores unchanged)
- `modules/app-services/src/test/.../LambdaMartRerankerTest.java` — unaffected (unit-level)
- `modules/reranker/src/integrationTest/.../CrossEncoderRerankerIntegrationTest.java` — unaffected

**Exit criteria:** Cross-encoder and LambdaMART are independently togglable
via PipelineConfig. Co-execution works (LambdaMART → cross-encoder
sequentially). `hybridModeEnabled` removed. LambdaMART reports structured
skip reasons.

**Status:** Done (F1, F2, F4, F5 implemented; F3 deferred). Changes:
- `RerankerConfig`: removed `hybridModeEnabled` field and
  `JUSTSEARCH_RERANK_HYBRID_ENABLED` env var
- `expandPreset()`: all 4 mode presets now get
  `crossEncoderEnabled = rerankConfig.enabled()`
- `doSearch()`: removed `LAMBDAMART_APPLIED` skip branch, wired
  `crossEncoderWindow` from PipelineConfig, added structured
  `lambdaMartSkipReason` tracking
- `buildPipelineExecution()`: accepts `lambdaMartSkipReason` parameter
- Tests: `PipelineConfigPresetExpansionTest`,
  `KnowledgeHttpApiAdapterHarmfulCombinationsTest`, `RerankerConfigTest`
  updated — all pass
- Post-review fixes: stale "lexical mode only" comment corrected;
  `MODE_NOT_ELIGIBLE` skip reason renamed to `PIPELINE_NOT_ELIGIBLE`

**Pre-existing issue (not Phase F):** `IndexerWorkerGuardrailsTest`
fails due to `EmbeddingService.resolveEmbedContextLength()` calling
`System.getenv()` — introduced during tempdoc 251, tracked separately.

---

### Phase G: SearchMode Enum Deprecation

**Goal:** Complete the migration from `SearchMode` enum to `PipelineConfig`
as the sole pipeline control mechanism. Remove bridge code that translates
between the two representations.

**Current state:** The `SearchMode` enum is marked `[deprecated = true]`
on the `SearchRequest.mode` field (field 3). Three translation layers
exist:

1. **Frontend → Head:** `useSearch.ts` sends `mode: "hybrid"` string →
   `KnowledgeHttpApiAdapter.parseModeOrDefault()` → `SearchMode` enum →
   `expandPreset()` → `PipelineConfig`
2. **Head → Worker:** Both `mode` and `pipeline` fields sent on wire.
   Worker ignores `mode` when `pipeline` is present.
3. **Worker fallback:** `SearchOrchestrator.modeToDefaultPipeline()` for
   requests with only deprecated `mode` field.

**Protobuf deprecation best practice** (from protobuf.dev): mark as
deprecated → wait for all clients to migrate → reserve the field number
and name. Never remove enum values — old serialized messages would break.
The timeline should be 6-12 months for internal APIs.

**Work items:**

- [x] **G1. Frontend sends `pipeline` instead of `mode`.** Change
  `useSearch.ts` `buildOptions()` to send `pipeline: { sparseEnabled: true,
  denseEnabled: true, fusionAlgorithm: "rrf", lambdamartEnabled: true }`
  instead of `mode: "hybrid"`. The `SearchOptions.pipeline` type already
  exists (search.ts lines 63-72). The Head-side controller already parses
  it (KnowledgeSearchController lines 200-207).

- [ ] **G1b. Frontend pipeline preset selector.** Add a UI control that
  lets users switch between named presets (Text, Hybrid, Vector, SPLADE)
  and optionally toggle individual components. Minimal viable version: a
  dropdown that maps to preset PipelineConfig objects. Advanced version:
  an "Advanced" panel exposing individual toggles. The `SearchOptions`
  type already supports `pipeline?: PipelineConfig` — the UI just needs
  to populate it. This is the user-facing surface for the component
  activation model.

- [x] **G2. Agent SearchTool sends `pipeline`.** Update `SearchTool.java`
  to construct a `PipelineConfig` directly instead of passing a mode
  string. The tool's `mode` parameter becomes a convenience alias that
  maps to a preset pipeline config on the tool side, not the adapter side.
  This removes one consumer of `parseModeOrDefault()`.

- [x] **G3. Head sends only `pipeline` on gRPC wire.** Stop setting
  `SearchRequest.mode` in `KnowledgeHttpApiAdapter`. The Worker already
  prefers `pipeline` when present. After this, the deprecated `mode` field
  is never set by the Head process.

- [x] **G4. Worker-side `modeToDefaultPipeline()` fallback warning.** Added
  log warning when pipeline is absent (old client). Kept
  `modeToDefaultPipeline()` as safety-net fallback rather than removing it
  (no confirmation that external gRPC callers don't exist).

- [x] **G5. Slim down Head-side bridge.** Kept `parseModeOrDefault()` +
  `expandPreset()` as thin compat layer for REST API callers that still
  send `mode` string. Fixed stale javadoc on `isRerankerEligible()` —
  removed reference to deleted `JUSTSEARCH_RERANK_HYBRID_ENABLED` env var.

- [ ] **G6. Reserve proto field — deferred.** After all callers migrated, change the
  proto from `SearchMode mode = 3 [deprecated = true]` to
  `reserved 3; reserved "mode";`. Keep the `SearchMode` enum definition
  for documentation but remove it from `SearchRequest`. This prevents
  accidental reuse of field number 3.

**Ordering constraint:** G1 and G2 can be done in parallel (independent
callers). G3 requires G1+G2 (all callers must send `pipeline`). G4+G5
require G3. G6 is the final step after all callers are confirmed
migrated. G1b can be done any time after G1.

**Key files:**
- `modules/ui-web/src/hooks/useSearch.ts` — frontend search call (G1)
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/SearchTool.java` — agent tool (G2)
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` — bridge code (G3, G5)
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java` — fallback (G4)
- `modules/ipc-common/src/main/proto/indexing.proto` — proto definition (G6)

**Exit criteria:** No runtime code references `SearchMode` enum.
`PipelineConfig` is the sole pipeline control mechanism. Proto field 3 is
reserved. Users can select pipeline presets from the UI.

**Status:** Done (G1-G5 implemented; G1b deferred, G6 deferred). Changes:
- Frontend sends `pipeline` object instead of `mode: "hybrid"` (useSearch.ts)
- Agent SearchTool translates `mode` to `PipelineConfig` via `modeToPreset()`
  helper; new `pipeline` parameter allows fine-grained control (H1)
- Head no longer sets `SearchRequest.mode` on gRPC wire
- Worker logs warning when pipeline absent, falls back to `modeToDefaultPipeline()`
- Head-side bridge (`parseModeOrDefault` + `expandPreset`) kept as thin
  REST API compat layer
- Stale javadoc on `isRerankerEligible()` fixed
- `SearchToolTest` updated: asserts null mode + correct pipeline flags
- Post-review fixes: added unit tests for `modeToPreset()` (7 cases) and
  `parsePipelineArg()` (3 cases: empty, full, partial object) in
  `SearchToolTest`

**Deferred items:**
- G1b (UI preset selector): deferred — UI design decision
- G6 (reserve proto field): deferred — need confirmation no external gRPC callers

---

### Phase H: Production Activation of Novel Combinations

**Goal:** Make the novel pipeline combinations (enabled by Phase E) usable
in production paths — the agent, the default search preset, and eval
tooling.

**Current state:** No production path sends non-preset configs. The
frontend hardcodes `"hybrid"`, the agent defaults to `"text"`, GPL eval
forces TEXT. The composable dispatch is proven but dormant.

**Work items:**

- [x] **H1. Agent SearchTool pipeline parameter.** Expose a `pipeline`
  parameter on the SearchTool that accepts component flags directly. The
  agent can then choose retrieval strategy based on query characteristics
  (e.g., short keyword query → sparse only; conceptual question → hybrid;
  domain-specific term → splade+sparse). Document the available
  combinations and their tradeoffs in the tool description.

- [ ] **H2. Agent search mode heuristic — deferred.** Add query-adaptive mode
  selection to the agent. Use QPP signals (maxIdf, queryScope) from the
  first search to decide whether a follow-up search with a different
  pipeline would improve recall. Example: if `queryScope < 0.01` (very
  rare terms), retry with `{sparse, splade}` to leverage SPLADE's
  learned term expansion. This is a form of automatic pipeline routing
  — the agent acts as an intelligent dispatcher.
  **Tempdoc 251 Phase 5 input (2026-03-07):** For long-doc collections
  (>2K tokens), BM25 is optimal (nDCG=0.924 courtlistener, 0.570 legal).
  Hybrid adds recall (+0.394 on legal) but hurts nDCG. Cross-encoder must
  be disabled (MiniLM-L6-v2 512-token limit causes -0.606 nDCG damage).
  LambdaMART has zero effect. Routing heuristic should use document
  length to select BM25 for long-doc queries, hybrid for short-doc queries.

- [x] **H3. Eval framework pipeline support.** Update `beir-eval-win.ps1`
  and related eval scripts to accept arbitrary pipeline configs, not just
  mode names. This enables measuring the quality impact of novel
  combinations (e.g., does sparse+splade beat hybrid for domain-specific
  corpora?). Cross-reference with tempdoc 251 (eval framework).

- [ ] **H4. SPLADE + Dense production preset — deferred.** If eval data (H3) shows
  that `{splade, dense, rrf}` outperforms `{sparse, dense, rrf}` (standard
  hybrid) on the production corpus, consider adding it as a named preset.
  SPLADE's learned term weighting may provide better sparse signal than
  raw BM25 for certain document types. This is the strategic payoff of
  the composable architecture.
  **Tempdoc 251 Phase 5 input:** SPLADE configs (8-11) deferred — require
  full re-index with SPLADE enabled. Previous multi-dataset results
  (SciFact: SPLADE+Dense RRF=0.702 best) suggest SPLADE may help short-doc
  collections but needs long-doc validation.

**Key files:**
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/SearchTool.java` — tool definition (H1, H2)
- `scripts/eval/beir-eval-win.ps1` — eval framework (H3)
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/PipelineConfig.java` — preset constants (H4)

**Exit criteria:** At least one production path (agent or default preset)
uses a non-trivial pipeline config informed by eval data.

**Status:** Done (H1, H3 implemented; H2, H4 deferred). Changes:
- Agent SearchTool: `pipeline` JSON parameter with component flags
  (`sparseEnabled`, `denseEnabled`, `spladeEnabled`, `fusionAlgorithm`,
  `lambdamartEnabled`, `crossEncoderEnabled`, `expansionEnabled`).
  Overrides `mode` when provided. `parsePipelineArg()` + `boolField()`
  helpers.
- Eval script: `-Pipeline` parameter (JSON string) on `beir-eval-win.ps1`.
  When set, sends `pipeline` in request body instead of `mode`. Backward
  compat: `-Modes` still works for preset-based evaluation.

**Deferred items:**
- H2 (agent search mode heuristic): requires eval data from H3
- H4 (SPLADE + Dense preset): requires eval data to justify

---

### Phase I: LLM Expansion Stage Composition

**Goal:** Make LLM query expansion independently composable as a pipeline
stage, decoupled from mode-based gating.

**Current state:** `isExpansionEligible()` in `KnowledgeHttpApiAdapter`
(line 129) checks `!config.denseEnabled()` — expansion only fires when
dense retrieval is disabled. The rationale is that dense retrieval already
provides semantic recall, making lexical expansion redundant. This is
reasonable but rigid: there are cases where expansion + hybrid could
improve recall (rare domain terms where the embedding model lacks coverage).

The expansion implementation is sound (async LLM call overlapped with
base search, morphological variants only, hallucination guards, 1.5s
budget). The gating just needs to become a PipelineConfig flag rather
than an inferred property of other flags.

**Work items:**

- [x] **I1. Add `expansionEnabled` flag to PipelineConfig.** New boolean
  field on the proto and app-api record. Presets: TEXT → true (current
  behavior), HYBRID → false (current behavior), VECTOR → false, SPLADE →
  true (currently eligible since `denseEnabled=false`). The flag makes the
  policy explicit rather than derived.

- [x] **I2. Update `isExpansionEligible()`.** Replace
  `!config.denseEnabled()` with `config.expansionEnabled()`. All other
  gates (syntax, blank query, cursor, AI available) stay as-is.

- [ ] **I3. Expansion + Hybrid evaluation — deferred.** Measure whether
  `{sparse, dense, rrf, expansionEnabled}` improves recall over standard
  hybrid on the eval corpus. If expansion adds morphological variants that
  the embedding model misses (e.g., "constitutionality" as variant of
  "constitutional"), this combination could outperform both TEXT+expansion
  and plain HYBRID. If not, keep the default as expansion=false for hybrid.

- [x] **I4. Expansion stage reporting.** Add `expansion` to
  `pipelineExecution.components` with status (executed/skipped), reason,
  expanded terms count, and latency. Currently expansion is invisible in
  the pipeline execution report.

**Key files:**
- `modules/ipc-common/src/main/proto/indexing.proto` — PipelineConfig message (I1)
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/PipelineConfig.java` — record (I1)
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` — `isExpansionEligible()`, `doSearch()` (I2, I4)

**Exit criteria:** LLM expansion is a first-class pipeline stage with its
own PipelineConfig flag, independent of retrieval mode.

**Status:** Done (I1, I2, I4 implemented; I3 deferred). Changes:
- Proto: `bool expansion_enabled = 8` in `PipelineConfig` message
- Java record: 8th field `boolean expansionEnabled`. Presets: TEXT=true,
  VECTOR=false, HYBRID=false, SPLADE=true
- `PipelineConfigs.java`: TEXT preset builder gets `.setExpansionEnabled(true)`
- `isExpansionEligible()`: replaced `!config.denseEnabled()` with
  `config.expansionEnabled()` — explicit flag instead of derived inference
- `doSearch()`: structured `expansionSkipReason` tracking with reasons:
  DISABLED, AI_UNAVAILABLE, LUCENE_SYNTAX, BLANK_QUERY, PAGINATED,
  TIMEOUT, FAILED
- `buildPipelineExecution()`: expansion component reports "skipped" with
  reason when not executed
- `expandPreset()`: all 4 cases include 8th arg
- `toProtoPipelineConfig()`: wires `expansionEnabled`
- Frontend `PipelineConfig` type: added `expansionEnabled?: boolean`
- `KnowledgeSearchController`: pipeline parsing includes `expansionEnabled`
- All `new PipelineConfig(...)` call sites updated across 6+ files
- `KnowledgeHttpApiAdapterHarmfulCombinationsTest`: expansion eligibility
  tests renamed to reflect flag-based gating
- Post-review fixes: stale `isExpansionEligible()` javadoc rewritten
  (described old `!denseEnabled()` gate, now documents `expansionEnabled`
  flag); `HarmfulCombinationsTest` display name updated — "HYBRID mode
  disables" → "HYBRID preset has expansionEnabled=false"

**Deferred items:**
- I3 (Expansion + Hybrid evaluation): requires eval infrastructure

---

### Phase Summary

| Phase | Scope | Key change | Risk |
|-------|-------|------------|------|
| **A–E** | Retrieval | Composable staged dispatch | **Done** |
| **F** | Reranking | Independent cross-encoder + LambdaMART composition (F3 deferred) | **Done** |
| **G** | Cleanup | SearchMode enum deprecation + bridge removal (G1b, G6 deferred) | **Done** |
| **H** | Activation | Novel combos in agent + eval (H2, H4 deferred) | **Done** |
| **I** | Expansion | LLM expansion as composable stage (I3 deferred) | **Done** |

**Recommended ordering:** F → G → H → I.
F is highest value (unlocks 2-stage reranking). F3 (explicit stage
ordering proto field) deferred until a third reranker is added. G is
housekeeping. H requires eval data. I is lowest priority (expansion
already works, just needs explicit flag).

---

## Open Questions (Resolved)

All questions resolved during Phase C implementation.

| Question | Resolution |
|----------|-----------|
| Q1: Unified entry point vs plan-based dispatch? | **Option B.** 40+ external callers of `search*()` methods (tests, benchmarks, RemoteKnowledgeClient, CitationMatchOps). Orchestrator dispatches via config fields. |
| Q2: TEXT-only features? | **Option A.** Gated by being in the sparse-only branch of the if/else chain. Natural Lucene constraint, not a toggle. |
| Q3: Chunk merge plan? | **Option A.** Same `PipelineConfig` for chunks. Chunk constraints are a strict subset (BM25 + KNN only, no standalone SPLADE chunks). |
| Q4: Performance overhead? | **Non-issue.** Boolean field checks on proto object, sub-microsecond. No object allocation. |
| Q5: Gradual rollout? | **Option A.** Phases A→B→C merged independently. Tests use `.setMode()` with Worker-side fallback. |

---

## Dependencies

| This tempdoc | Depends on | Status |
|-------------|-----------|--------|
| Phase A (proto) | 250 Phase 1c (execution flags) | Done |
| Phase B (head-side) | Phase A | Done |
| Phase C (worker-side) | Phase A | Done |
| Phase D (soft consumer cleanup) | Phase C | Done |
| Phase E (independent activation) | Phase D | Done |
| Phase F (reranking composition) | Phase E | Done |
| Phase G (enum deprecation) | Phase E | Done (G1b, G6 deferred) |
| Phase H (production activation) | Phase E, tempdoc 251 (eval) | Done (H2, H4 deferred) |
| Phase I (expansion composition) | Phase F | Done (I3 deferred) |

250 Phase 5 (Observability) was implemented concurrently. Pipeline execution
reports (`pipelineExecution` in search response) already use PipelineConfig-based
component reporting.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 72 days at audit time.

