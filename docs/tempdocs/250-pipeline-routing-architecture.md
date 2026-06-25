---
title: "250: Pipeline Routing & Structural Architecture"
type: tempdoc
status: done
created: 2026-03-02
---

> NOTE: Noncanonical doc (strategy + architecture direction). May drift.

# 250: Pipeline Routing & Structural Architecture

## Purpose

Capture the structural issues in JustSearch's search pipeline that limit
component composability, observability, and debuggability. These emerged from
the 245 component isolation work and a mechanism-level code review. This
tempdoc defines the problems, documents the industry state of the art, and
lays out the architectural direction for resolution.

This is a **strategic direction** document — not all items have concrete
implementation scope yet. Items graduate to implementation when they're
scoped tightly enough to have clear exit criteria.

### Completion Summary (2026-03-04)

| Phase | Items | Status |
|-------|-------|--------|
| Phase 1: Low-Hanging Fruit | 7/7 | **Complete** |
| Phase 2: Score Provenance | 10/10 | **Complete** |
| Phase 3: Index Capability Awareness | 3/3 | **Complete** |
| Phase 4: Component Activation Model | 0/3 | **Extracted** → [tempdoc 256](256-component-activation-model.md) |
| Phase 5: Observability | 3/3 | **Complete** |

Phases 1-3 addressed all four original problems (P1-P4) through additive
instrumentation without structural refactoring. Phase 4 (structural refactor
of the mode routing itself) was extracted to its own tempdoc after scoping
revealed it is larger than Phases 1-3 combined.

---

## Problems

### P1: Mode Gating Prevents Useful Combinations

**What:** Cross-encoder reranking is guarded by `mode == SEARCH_MODE_TEXT` in
`KnowledgeHttpApiAdapter.isRerankerEligible()`. This means HYBRID retrieval
(BM25 + dense, or SPLADE + dense) can never benefit from cross-encoder
reranking, even though the reranker operates on the fused candidate list and
is retrieval-mode-agnostic.

LambdaMART has no mode guard and fires for all modes — an inconsistency.

**Impact:** On our 4-dataset BEIR eval, cross-encoder adds +1.5 nDCG points
to BM25 on SciFact/NFCorpus. That lift is unavailable to HYBRID mode users,
who are the majority. **Update (2026-03-02):** Empirical eval shows cross-
encoder is neutral-to-slightly-negative for HYBRID on SciFact (nDCG -0.002,
Recall -0.007). The original rationale is validated — the dense retrieval
signal already captures most of what the cross-encoder provides.

**Industry context:** No major production search system (Vespa, Elasticsearch,
OpenSearch, Qdrant, Weaviate) gates the reranker by retrieval mode. The
universal pattern is: reranking operates on the fused candidate list
regardless of how it was produced.

### P2: Chunk Merge Destroys Per-Component Attribution

**What:** `SearchOrchestrator.mergeChunkResults()` calls `fuseWithRRF()` treating
whole-doc results as the "BM25 leg" and chunk results as the "vector leg".
This overwrites the original per-leg `sparse` and `vector` debug scores,
making it impossible to determine which retrieval component contributed to a
given hit's ranking.

The visible symptom: `denseVectorEvidenceAvailableRate: 0` in metrics — a
false signal caused by debug score overwriting, not by missing vectors.

**Impact:** Cannot answer "did vector retrieval help for this query?" at
runtime. Makes A/B testing and quality monitoring of individual components
impossible without full component isolation experiments (which take hours).

**Industry context:** No production system fully solves per-component
attribution through multi-stage fusion. The best approaches:
- **Vespa `match-features`:** Publish all intermediate scores as named
  features on every hit. Fusion produces a final score but never overwrites
  component scores.
- **OpenSearch `hybrid_score_explanation`:** Returns raw + normalized scores
  per sub-query in a structured `_explanation` object.
- **Retriever source tagging:** Tag each candidate pre-fusion with which
  retriever(s) found it and at what rank. Survives fusion as metadata.
- **Shapley attribution (research):** RankSHAP (ICLR 2025), ShaRP (VLDB
  2025) — theoretically beautiful, computationally impractical for real-time.

### P3: Index-Time Prerequisites Leak Into Query-Time Behavior

**What:** SPLADE feature fields and embedding vectors must exist in the index
for their retrieval legs to be meaningful. If the index was built without
SPLADE enabled, SPLADE queries return zero results — silently. If embedding
backfill is incomplete, dense retrieval misses un-vectorized documents. There
is no query-time check or warning that the index lacks capabilities the
requested mode requires.

**Impact:** During our isolation experiments, we lost hours to this:
- Arguana Session 1 returned 0.000 on all metrics due to a race condition
  where queries ran before embeddings were indexed.
- SPLADE requires `JUSTSEARCH_SPLADE_ENABLED=true` at index time — if
  missed, SPLADE search returns zero results with no warning.

**Industry context:** Most systems either hard-error (Elasticsearch pre-8.14)
or silently degrade (Lucene segments without vectors return 0 results). No
system stores a first-class "index capability manifest" that the query
planner inspects. The closest is Elasticsearch's `_field_caps` API (pre-query
check, not integrated into the planner). Vespa is honest about the gap:
"you will reduce recall until reindexing is completed."

### P4: Silent Fallback Hides Misconfiguration

**What:** When a component is unavailable, the pipeline falls back gracefully:
hybrid → text (when vectors blocked), SPLADE → text (when encoder absent),
reranking → skip (when model not loaded). This is good UX but makes it easy
to believe a component is active when it isn't.

JustSearch already emits flags: `effectiveMode`, `vectorBlocked`,
`hybridFallback`, `chunkMergeApplied`. But there is no component-level
execution map — you can't see at a glance which components fired, which were
skipped, and why.

**Impact:** During isolation experiments, `retrieval=DEGRADED` due to
`lambdamart.not_configured` looked like a real problem but was harmless.
Diagnosing this required reading the `chunkEmbedding` component status
directly. No metric tracks fallback frequency.

**Industry context:** Vespa has the best answer — every response includes
`coverage.degraded` with boolean flags for *why* results are incomplete.
The emerging consensus (AWS Well-Architected, New Relic): "tracking how
often fallback paths are used is as important as tracking success." OTel
OpenInference defines span-per-stage tracing where absent spans indicate
skipped components.

---

## Root Cause

The four problems share a root cause: **the pipeline treats "mode" as a
monolithic routing decision rather than as independent component activations.**

The `SearchMode` enum (`TEXT`, `VECTOR`, `HYBRID`, `SPLADE`) determines which
retrievers run, which rerankers are eligible, and which debug scores are
populated. This creates a coupling matrix where adding a new combination
(e.g., HYBRID + cross-encoder) requires modifying mode gates in multiple
files across multiple processes.

---

## Architectural Direction

### Target: Component Activation Model

Replace the mode enum with a component activation vector. Each component
(BM25, SPLADE, dense, cross-encoder, LambdaMART, chunk merge) has its own
`enabled/disabled` state. "Mode" becomes a *preset* that configures which
components are active, not a hard gate that prevents combinations.

```
Current:  mode=HYBRID → hardcoded set of components fire
Target:   preset=HYBRID → suggests component set, but each is independently toggleable
```

Retrieval, fusion, and reranking become orthogonal pipeline stages:

```
Stage 1 (Retrieval):   BM25 ∪ SPLADE ∪ Dense KNN  →  candidate pool
Stage 2 (Fusion):      RRF / CC / learned weights   →  merged ranking
Stage 3 (Fast rerank): LambdaMART / ColBERT          →  re-scored top-N
Stage 4 (Deep rerank): Cross-encoder                  →  re-scored top-K
```

Each stage operates on the output of the previous stage. No stage inspects
"which mode was requested" — it only sees candidates with scores.

### Target: Score Provenance Through Pipeline

Every hit carries a provenance map from retrieval through final ranking:

```json
{
  "provenance": {
    "bm25":          {"rank": 3, "raw_score": 14.2},
    "splade":        {"rank": 7, "raw_score": 8.1},
    "dense":         {"rank": 1, "raw_score": 0.91},
    "rrf_fusion":    {"score": 0.047},
    "chunk_merge":   {"applied": true, "chunk_rank": 2},
    "cross_encoder": {"score": 0.88}
  }
}
```

No stage overwrites prior scores — it appends its own. The final response
includes all intermediate scores for every hit.

### Target: Index Capability Manifest

When an index is built, record what features are present:

```json
{
  "capabilities": {
    "bm25":         {"coverage": 1.0},
    "dense_vector": {"coverage": 0.87, "model": "nomic-v1.5-Q8", "dim": 768},
    "splade":       {"coverage": 0.87, "model": "splade-v3"},
    "chunks":       {"coverage": 0.92, "threshold": 2000}
  }
}
```

The query planner reads this and: (a) warns if a requested component can't
function, (b) adjusts fusion weights based on coverage, (c) reports gaps in
the response.

### Target: Structured Execution Report

Every search response includes a component execution map:

```json
{
  "pipeline_execution": {
    "requested_mode": "HYBRID",
    "effective_mode": "HYBRID",
    "components": {
      "bm25":           {"status": "executed", "hits": 47, "took_ms": 12},
      "dense":          {"status": "executed", "hits": 50, "took_ms": 34},
      "splade":         {"status": "skipped", "reason": "encoder_not_configured"},
      "rrf_fusion":     {"status": "executed", "took_ms": 2},
      "chunk_merge":    {"status": "executed", "chunks_found": 12},
      "lambdamart":     {"status": "skipped", "reason": "model_not_loaded"},
      "cross_encoder":  {"status": "skipped", "reason": "below_min_hits"}
    },
    "fallbacks_triggered": [],
    "index_capabilities": {"dense_coverage": 0.87, "splade_coverage": 0.87}
  }
}
```

JustSearch already has the data for most of this — `effectiveMode`,
`vectorBlocked`, `hybridFallback`, `chunkMergeApplied` are on the wire.
The gap is the component-level breakdown and the `skipped + reason` structure.

---

## Research References

### P1: Mode-Agnostic Reranking

- **Vespa phased ranking:** Retrieval-agnostic global-phase cross-encoder.
  No mode gating by platform design.
- **Elasticsearch Elastic Rerank (8.17, Nov 2024):** Cross-encoder after
  hybrid RRF in a single API call. 40% avg improvement over BM25.
- **OpenSearch rerank processor (2.12, Feb 2024):** Cross-encoder sits after
  normalization processor in search pipeline. Mode-agnostic.
- **Naver Labs (arXiv 2403.10407, ECIR 2024):** Cross-encoders after SPLADE
  yield gains on out-of-domain benchmarks. In-domain, gains are small.
- **"Drowning in Documents" (arXiv 2411.11767, SIGIR 2025):** Cross-encoders
  degrade at large K ("phantom hits"). Bounded K=50-200 is optimal.
- **MICE (arXiv 2602.16299, 2025):** 4x faster cross-encoder matching
  ColBERT quality. Makes "too slow for hybrid" argument weaker.
- **ColBERT as intermediate reranker:** 100x faster than cross-encoder,
  53 nDCG@10 on BEIR. Vespa ships native ColBERT embedder (2024).
- **jina-reranker-v3 (arXiv 2509.25085):** "Last but not late" interaction,
  61.9 nDCG@10 on BEIR — current SOTA reranker.

### P2: Score Provenance

- **Vespa `match-features`:** Publishes intermediate scores per hit at
  query time. Most flexible online attribution.
- **OpenSearch `hybrid_score_explanation` (2024):** Structured raw + normalized
  scores per sub-query.
- **ACM TOIS 2023 (Fusion Functions):** RRF destroys score magnitude; CC is
  more analytically tractable for attribution.
- **RankSHAP (ICLR 2025, arXiv 2405.01848):** Shapley values for rankings.
- **ShaRP (VLDB 2025, arXiv 2401.16744):** Shapley for rankings/preferences.

### P3: Index Capability Detection

- **Elasticsearch `_field_caps` API:** Programmatic index capability check.
- **Elasticsearch `sparse_vector` exists queries (2024):** Detect which docs
  lack sparse vector enrichment.
- **Vespa reindexing:** Background reindex with honest degradation warning.
- **Lucene `FeatureField`:** Documents without features score zero naturally.

### P4: Fallback Observability

- **Vespa `coverage.degraded`:** Structured degradation flags per response.
- **OTel OpenInference:** Span-per-stage, absent spans = skipped components.
- **OTel GenAI semantic conventions (2024):** `RETRIEVER` and `RERANKER`
  span kinds with structured attributes.
- **Evaluation-driven development (arXiv 2411.13768):** Continuous golden-set
  eval with LLM-as-judge scoring.

---

## Work Items

Items are ordered by dependency and impact. Items marked "ready" have clear
implementation scope. Items marked "needs scoping" require further design.

### Phase 1: Low-Hanging Fruit (P1 + P2 partial)

- [x] **1a.** ~~Remove Cross-Encoder TEXT-only guard.~~ **EVALUATED — guard
  kept.** BEIR experiment on SciFact (Q8_0 embeddings, SPLADE + Dense RRF):
  HYBRID nDCG@10 baseline=0.7055, with cross-encoder=0.7033 (delta=-0.0022).
  Recall@10 also decreased (0.8068→0.8001). Cross-encoder adds no value to
  HYBRID mode on this dataset — the guard's rationale is empirically valid.
  Added `JUSTSEARCH_RERANK_HYBRID_ENABLED` env var to `RerankerConfig` for
  future re-evaluation on additional datasets.
- [x] **1b.** ~~Fix debug score overwriting in chunk merge.~~ **DONE.**
  `fuseWithRRF` now accepts `scoreKeyPrefix` parameter. Chunk merge passes
  `"chunk_"` prefix, writing `chunk_sparse`/`chunk_vector` instead of
  overwriting `sparse`/`vector`. Existing debug scores from input hits are
  carried forward through fusion. 4 new tests in `HybridFusionUtilsTest`.
- [x] **1c.** ~~Add per-component execution flags to `SearchResponse` proto.~~
  **DONE.** Added `spladeExecuted`/`spladeSkipReason` (proto, Worker-side),
  `lambdaMartApplied`, `crossEncoderApplied`/`crossEncoderSkipReason`
  (Head-side). Follows existing `bool` + reason string pattern.

### Phase 1 Polish: Debug/Observability Fixes

Follow-up corrections to Phase 1 skip-reason accuracy (2026-03-03):

- [x] **1d.** Fix cross-encoder skip reason misattributing `LAMBDAMART_APPLIED`
  inside the ineligible branch. Removed — when `isRerankerEligible()` returns
  false, only `DISABLED` or `MODE_NOT_ELIGIBLE` are valid reasons.
- [x] **1e.** Add `BELOW_MIN_THRESHOLD` skip reason when result count is below
  `minHitsThreshold`. Previously fell through to `MODE_NOT_ELIGIBLE`.
- [x] **1f.** Set `spladeSkipReason = "ENCODER_NOT_AVAILABLE"` in the HYBRID
  branch when `spladeEncoder` is null. Previously left `spladeSkipReason` as
  null, giving no indication SPLADE was unavailable.
- [x] **1g.** Include dataset name in eval isolation output directory
  (`isolation-embed-$Dataset` instead of `isolation-embed`). Prevents silent
  cross-dataset result overwrites.

### Phase 2: Score Provenance (P2 full)

- [x] **2a.** ~~Design provenance map schema for `SearchResponse` hits.~~ **DONE.**
  Added nested `HitProvenance` record in `KnowledgeSearchResponse.Hit` with
  sub-records: `RetrieverScore` (rank + rawScore for BM25, SPLADE, dense),
  `FusionScore` (score + method for RRF/CC), `ChunkMergeScore` (per-leg
  ranks and scores from chunk-merge second fusion), `RerankerScore` (cross-
  encoder logit). Null sub-records omitted from JSON via `@JsonInclude(NON_NULL)`.
- [x] **2b.** ~~Implement provenance in `HybridFusionUtils` and chunk merge.~~
  **DONE.** Added `sparse_rank`/`vector_rank` keys in `fuseWithRRF` (with
  scoreKeyPrefix support → `chunk_sparse_rank`/`chunk_vector_rank` for chunk
  merge) and `fuseWithCC`. Trivial-query short-circuit path in
  `HybridSearchOps` also emits rank keys. Ranks flow through proto
  `debug_scores` map (no proto changes needed). 7 new tests added to
  `HybridFusionUtilsTest` (5 RRF + 2 CC rank tests), existing keyset
  assertions updated across `HybridFusionUtilsTest` and `HybridSearchOpsTest`.
- [x] **2c.** ~~Expose provenance in REST API response.~~ **DONE.** Head-side
  `assembleProvenance()` in `KnowledgeHttpApiAdapter` reconstructs structured
  `HitProvenance` from flat debug score keys + cross-encoder scores captured
  from `RerankedResult.scores()`. Routes sparse component to `bm25` or
  `splade` field based on `spladeExecuted` flag (from Phase 1c). Jackson
  serializes via existing `results` field — no controller changes needed.

#### Phase 2 follow-up: known issues from critical review

Root cause: provenance is reconstructed Head-side from an untyped flat
`Map<String, Float>` (proto `debug_scores`). Four of seven issues trace to
this. Investigation concluded the flat-map transport is adequate — see
findings below.

- [x] **2d.** ~~Add unit tests for `assembleProvenance()`.~~ **DONE.**
  `KnowledgeHttpApiAdapterProvenanceTest.java` — 15 test cases covering
  null/empty guard, BM25/SPLADE routing, dense component, zero-rank absence,
  RRF/CC fusion scores, chunk-merge (applied/not-applied/both-ranks-zero),
  and CE score (present/null-map/doc-missing). Full suite passes.
- [x] **2e.** ~~Lazy-init `ceScoresByDocId`.~~ **DONE.** Changed eager
  `new HashMap<>()` to `null` init; added `new HashMap<>()` inside
  `if (!reranked.skipped())` block. Two-line change.
- [x] **2g.** ~~Suppress `provenance: null` serialization.~~ **DONE.** Added
  `@JsonInclude(NON_NULL)` to `Hit` record. Investigation confirmed no
  Java-side changes needed: HybridFusionUtils null checks operate on
  `LuceneRuntimeTypes.SearchHit` (different type), SearchToolTest constructs
  Java objects not JSON. Full suite passes.
- [x] **2f.** ~~Guard float-to-int rank cast.~~ **CLOSED — not needed.** Fusion
  code always writes ranks as `(float) rank` where rank is a small positive
  int. NaN/negative cannot occur from the producing code. A guard would be
  dead code.
- [x] **2h.** ~~Populate FusionScore in non-debug mode.~~ **CLOSED — by design.**
  Investigation found the gating was intentionally introduced in commit
  467b2a69 alongside LambdaMART. `sparse`/`vector` are always-on because
  LambdaMART reads them for production reranking (KnowledgeHttpApiAdapter
  lines 442–443). The fusion intermediates (`sparse_rrf`, `rrf`, `cc`, etc.)
  have zero production consumers — they exist solely for diagnostics.
  `FusionScore` being debug-only is correct behavior, not a limitation.
- [x] **2i.** ~~Eliminate all-null HitProvenance.~~ **FIXED.** Original analysis
  was wrong — TEXT mode hits DO have debug scores (`sparse`/`vector` from
  `SearchOrchestrator.toGrpcResponseBuilder()` lines 709–714) but NO rank keys.
  `assembleProvenance()` passed the non-empty guard but produced an all-null
  `HitProvenance` (`{}`). Added all-null guard: if every sub-record is null,
  return null instead. Test: `returnsNullWhenOnlyRawScoresPresent`.
- [x] **2j.** ~~Proto migration.~~ **CLOSED — not worth it.** Investigation
  found: (1) `adapters-lucene` has no proto dep and shouldn't gain one — it's
  a leaf module with only Lucene/config deps; (2) moving assembly to Worker
  (`SearchOrchestrator`) doesn't help because CE scores are Head-only (cross-
  encoder runs in Head process), so provenance can never be fully assembled
  Worker-side; (3) Head-side assembly is the correct location, proto types
  would add type safety for the Worker→Head transport but the assembly logic
  stays identical; (4) flat `debug_scores` must coexist anyway for LambdaMART
  (`sparse`/`vector` consumption at lines 442–443). Net: marginal type safety
  gain doesn't justify proto schema change + dual-transport complexity.

#### Phase 2 verification: dev stack smoke test (2026-03-04)

Unit tests: 363+ in adapters-lucene, 16 in app-services provenance test, full
suite passes.

Dev stack E2E verification (manual gradle start with env vars for embedding +
reranker, curl against `POST /api/knowledge/search` with `debug: true`):

| What | Result |
|------|--------|
| BM25 provenance (`bm25` sub-object) | **PASS** — HYBRID mode: `rank: 88, rawScore: 4.467` correctly populated |
| Dense provenance (`dense` sub-object) | **PASS** — HYBRID mode (50 results): `rank: 5, rawScore: 0.546` for hits found by KNN |
| Fusion provenance (`fusion` sub-object) | **PASS** — `score: 0.027, method: "rrf"` in HYBRID mode |
| Chunk-merge provenance (`chunkMerge` sub-object) | **PASS** — `sparseRank`, `denseRank`, `sparseScore`, `denseScore` correctly populated |
| CE provenance (`crossEncoder` sub-object) | **PASS** — TEXT mode with `JUSTSEARCH_RERANK_ENABLED=true` + ONNX model: `score: 0.996` |
| NON_NULL on HitProvenance | **PASS** — null sub-records (`splade`, `dense`, `fusion`) absent from JSON, not `null` |
| NON_NULL on Hit | **PASS** — `provenance` field absent when null (all-null guard) |
| All-null guard (2i fix) | **PASS** — TEXT mode hits without chunk-merge/CE → provenance null, not `{}` |
| `debugScores` present when non-empty | **PASS** — field appears with appropriate keys per mode |

Env vars required for full verification:
- `JUSTSEARCH_AI_EMBED_ENABLED=true` + `JUSTSEARCH_MODEL_PATH=<repo>/models/nomic-embed-text-v1.5.Q8_0.gguf` + `JUSTSEARCH_EMBED_GPU_LAYERS=99`
- `JUSTSEARCH_RERANK_ENABLED=true` + `JUSTSEARCH_RERANK_MODEL_PATH=<repo>/models/reranker/ms-marco-MiniLM-L6-v2`

Not verified: `splade` provenance (requires SPLADE-enabled index build).

### Phase 3: Index Capability Awareness (P3)

- [x] **3a.** Doc-level embedding and SPLADE coverage counting. Uses existing
  per-doc status fields (`EMBEDDING_STATUS`, `SPLADE_STATUS`) with
  `searcher.count()` queries. New `EmbeddingCounts` and `SpladeFeatureCounts`
  records in `LuceneRuntimeTypes`. Wired through proto → `IndexStatusOps` →
  `WorkerStatusMapper` → `WorkerOperationalView` → `/api/status` JSON.
- [x] **3b.** `IndexCapabilities` record in `KnowledgeSearchResponse` with
  `embeddingCoverage`, `spladeCoverage`, `chunkEmbeddingCoverage` (0.0–1.0),
  `crossEncoderAvailable`. Built from cached `WorkerOperationalView` (updated
  as a side-effect of status polling, no per-search gRPC call). Also wired into
  `KnowledgeSearchController.handleSearch()` manual map construction (the
  controller doesn't serialize the record directly — it builds a Map).
- [x] **3c.** Coverage warning banner in search results UI. Appears above
  results when any coverage < 100%. Shows "Embedding coverage: 87% · Chunk
  embedding coverage: 92% — some documents may not appear in vector results".

### Phase 4: Component Activation Model (P1 full refactor)

**Extracted to [tempdoc 256](256-component-activation-model.md).** Phase 4 is
a structural refactor larger than Phases 1-3 combined. It has its own phased
implementation plan (Phases A-E) with sub-items and open questions.

Summary: Replace `SearchMode` enum with `PipelineConfig` component activation
model. Mode becomes a named preset. Each pipeline stage independently togglable.
5 phases: A (proto/API), B (head-side), C (worker-side, largest), D (soft
consumers), E (frontend, optional).

### Phase 5: Observability (P4)

- [x] **5a.** Add fallback frequency metrics. Worker: 3 LongAdder counters
  (`hybridFallbackTotal`, `vectorBlockedTotal`, `spladeSkippedTotal`) in
  `OperationalMetrics` + OTel callbacks in `KnowledgeServer`. Head: 2 lazy-init
  `LongCounter` fields (`search.reranker_skipped.total`,
  `search.lambdamart_skipped.total`) in `KnowledgeHttpApiAdapter`.
- [x] **5b.** Structured execution report. Proto `ComponentTiming` (retrieval,
  chunk merge, chunk count) on `SearchResponse`. `PipelineExecution` and
  `ComponentStatus` records on `KnowledgeSearchResponse`. Worker-side timing in
  `SearchOrchestrator`, head-side timing for LambdaMART/CE in adapter.
  `buildPipelineExecution()` assembles from proto + local timing + boolean flags.
  Controller and frontend passthrough added.
- [x] **5c.** OTel span-per-stage tracing. Root `search` span in adapter,
  child `search/lambdamart` and `search/cross_encoder` spans in adapter.
  `TraceClientInterceptor` added to `RemoteKnowledgeClient` gRPC channel for
  Head→Worker propagation. Worker-side `search/retrieval` and `search/chunk_merge`
  child spans in `SearchOrchestrator`. Span attribute allowlist extended in
  `NdjsonSpanExporter` with `search.mode`, `search.query_length`,
  `search.total_hits`, `search.took_ms`.

---

## Phase 1 Implementation Plan

### Implementation Order: 1c → 1b → 1a

**Rationale:** 1c (execution flags) is zero-risk additive instrumentation that
makes all future debugging easier. 1b (debug score fix) has a clear root cause
and localized fix with existing test infrastructure. 1a (cross-encoder guard
removal) requires empirical validation before code change — the existing guard
has documented quality rationale backed by a contract test.

---

### 1c: Per-Component Execution Flags

**Goal:** Every search response reports which components executed, which were
skipped, and why — without changing any ranking behavior.

#### Architecture Decision: Worker-Side vs Head-Side Flags

Components execute in two processes:

| Component | Process | Where flag is set |
|-----------|---------|-------------------|
| SPLADE retrieval | Worker (`SearchOrchestrator`) | Proto field (Worker → Head) |
| Chunk merge | Worker (`SearchOrchestrator`) | **Already exists** (`chunk_merge_applied`) |
| LambdaMART | Head (`KnowledgeHttpApiAdapter`) | Directly on `KnowledgeSearchResponse` |
| Cross-encoder | Head (`KnowledgeHttpApiAdapter`) | Directly on `KnowledgeSearchResponse` |

SPLADE is the only new Worker-side flag that needs proto transport. LambdaMART
and cross-encoder execute in the Head process after gRPC deserialization, so
they bypass the proto entirely and are set directly on the response record.

#### Step 1: Proto change (`indexing.proto`)

Add one new field to `SearchResponse` (after field 19):

```protobuf
// Component execution flags (250: Pipeline Routing)
bool splade_executed = 20;         // True if SPLADE encoder ran for this query
string splade_skip_reason = 21;    // Reason code when SPLADE was skipped
```

Only SPLADE needs a proto field. Cross-encoder and LambdaMART execute in the
Head process and never cross gRPC.

**File:** `modules/ipc-common/src/main/proto/indexing.proto:85` (after
`query_scope = 19`).

#### Step 2: Worker-side flag setting (`SearchOrchestrator.java`)

Add tracking variables at line ~165 (alongside existing flag declarations):

```java
boolean spladeExecuted = false;
String spladeSkipReason = null;
```

Set them in the HYBRID branch (lines 285-308):
- `spladeExecuted = true` after successful `encoder.encode()` at line 289
- `spladeSkipReason = "ENCODER_NOT_AVAILABLE"` when `encoder == null` (line 301)
- `spladeSkipReason = "ENCODING_FAILED"` in the catch block (line 294)

Set them in the SPLADE branch (lines 311-338):
- `spladeExecuted = true` after successful `encoder.encode()` at line 331
- `spladeSkipReason = "ENCODER_NOT_AVAILABLE"` when `encoder == null` (line 324)
- `spladeSkipReason = "ENCODING_FAILED"` in the catch block (line 333)

Populate the response builder (after line 518):

```java
responseBuilder.setSpladeExecuted(spladeExecuted);
if (spladeSkipReason != null) {
  responseBuilder.setSpladeSkipReason(spladeSkipReason);
}
```

#### Step 3: Head-side response record (`KnowledgeSearchResponse.java`)

Add three new fields to the record (after `expansionApplied`, before
`entityFacetVariants`):

```java
Boolean spladeExecuted,
String spladeSkipReason,
Boolean lambdaMartApplied,
Boolean crossEncoderApplied,
String crossEncoderSkipReason
```

These use `Boolean` (boxed) to distinguish true/false/null in JSON
serialization, matching the existing pattern.

**File:** `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchResponse.java:12-29`.

#### Step 4: Head-side mapping (`KnowledgeHttpApiAdapter.java`)

Extract SPLADE flag from proto response (after line 645):

```java
String spladeSkipReason = resp.getSpladeSkipReason();
if (spladeSkipReason != null && spladeSkipReason.isBlank()) {
  spladeSkipReason = null;
}
```

LambdaMART and cross-encoder flags come from existing local variables:
- `lambdaMartApplied` already exists at line 430/445
- Cross-encoder: derive from the existing flow at lines 450-486

Update the constructor call at line 660 to pass the new fields.

#### Step 5: REST JSON serialization (`KnowledgeSearchController.java`)

Add conditional serialization after the `chunkMergeReason` block (line 232):

```java
if (response.spladeExecuted() != null) {
  out.put("spladeExecuted", response.spladeExecuted());
}
if (response.spladeSkipReason() != null && !response.spladeSkipReason().isBlank()) {
  out.put("spladeSkipReason", response.spladeSkipReason());
}
if (response.lambdaMartApplied() != null) {
  out.put("lambdaMartApplied", response.lambdaMartApplied());
}
if (response.crossEncoderApplied() != null) {
  out.put("crossEncoderApplied", response.crossEncoderApplied());
}
if (response.crossEncoderSkipReason() != null
    && !response.crossEncoderSkipReason().isBlank()) {
  out.put("crossEncoderSkipReason", response.crossEncoderSkipReason());
}
```

#### Files Modified (1c)

| File | Module | Change |
|------|--------|--------|
| `indexing.proto` | ipc-common | +2 proto fields (20, 21) |
| `SearchOrchestrator.java` | indexer-worker | +2 tracking vars, set in HYBRID/SPLADE branches, populate builder |
| `KnowledgeSearchResponse.java` | app-api | +5 record fields |
| `KnowledgeHttpApiAdapter.java` | app-services | Extract SPLADE from proto, set LambdaMART/CE flags, pass to constructor |
| `KnowledgeSearchController.java` | ui | +5 conditional JSON fields |

#### Verification (1c)

1. `./gradlew.bat spotlessApply`
2. `./gradlew.bat build -x test` — proto codegen + compilation across all modules
3. `./gradlew.bat :modules:adapters-lucene:test` — existing fusion tests still pass
4. `./gradlew.bat :modules:app-services:test` — KnowledgeHttpApiAdapter tests
5. `./gradlew.bat :modules:indexer-worker:test` — SearchOrchestrator tests
6. `./gradlew.bat :modules:ui:test` — controller tests
7. Full: `./gradlew.bat test`

#### Test additions (1c)

- `KnowledgeHttpApiAdapterTest`: verify `lambdaMartApplied=true` when LambdaMART
  fires, `crossEncoderApplied=true` when cross-encoder fires, SPLADE flags pass
  through from proto response
- Verify existing `KnowledgeHttpApiAdapterHarmfulCombinationsTest` still passes
  (cross-encoder blocked for HYBRID should now show
  `crossEncoderSkipReason = "MODE_NOT_ELIGIBLE"`)

---

### 1b: Fix Debug Score Overwriting in Chunk Merge

**Goal:** Original per-retriever `sparse` and `vector` debug scores survive
chunk merge intact. LambdaMART receives the original scores, not the merged
values.

#### Root Cause

`SearchOrchestrator.mergeChunkResults()` at line 857 calls:
```java
HybridFusionUtils.fuseWithRRF(
    wholeDocResult, chunkResult, limit * 2, false, Integer.MAX_VALUE, 1.0, rrfConfig);
```

This creates new `SearchHit` records where:
- `sparse` = RRF fusion of (wholeDoc + chunk) treated as "BM25 leg"
- `vector` = RRF fusion of (wholeDoc + chunk) treated as "KNN leg"

The original per-retriever scores from the initial HYBRID fusion are
overwritten.

#### Fix: Score Key Prefix in fuseWithRRF

Add a `scoreKeyPrefix` parameter to `fuseWithRRF()`. When non-empty, debug
score keys are prefixed (e.g., `"chunk_"` → `chunk_sparse`, `chunk_vector`).
The default empty prefix preserves backward compatibility.

**Step 1: Modify `HybridFusionUtils.fuseWithRRF()` signature**

```java
public static SearchResult fuseWithRRF(
    SearchResult result1,
    SearchResult result2,
    int limit,
    boolean debug,
    int vectorOnlyCap,
    double vectorWeightRaw,
    ResolvedConfig resolvedConfig) {
  return fuseWithRRF(result1, result2, limit, debug,
      vectorOnlyCap, vectorWeightRaw, resolvedConfig, "");
}

public static SearchResult fuseWithRRF(
    SearchResult result1,
    SearchResult result2,
    int limit,
    boolean debug,
    int vectorOnlyCap,
    double vectorWeightRaw,
    ResolvedConfig resolvedConfig,
    String scoreKeyPrefix) {
```

At lines 164-168 (score key creation), use the prefix:

```java
String sparseKey = scoreKeyPrefix + "sparse";
String vectorKey = scoreKeyPrefix + "vector";
hitScores.put(sparseKey, bm25Scores.getOrDefault(docId, 0.0f));
hitScores.put(vectorKey, vectorScores.getOrDefault(docId, 0.0f));
```

And similarly for the debug-only keys (lines 170-178):
```java
hitScores.put(scoreKeyPrefix + "sparse_rrf", ...);
hitScores.put(scoreKeyPrefix + "vector_rrf", ...);
// etc.
```

**Step 2: Preserve original scores through chunk merge**

In `SearchOrchestrator.mergeChunkResults()` at line 857, change to:

```java
HybridFusionUtils.fuseWithRRF(
    wholeDocResult, chunkResult, limit * 2, false,
    Integer.MAX_VALUE, 1.0, rrfConfig, "chunk_");
```

This writes `chunk_sparse` and `chunk_vector` instead of overwriting `sparse`
and `vector`. But `mergeChunkResults()` creates entirely new `SearchHit`
records — the original scores from `wholeDocResult` hits are not carried
forward.

**Step 3: Carry original scores through the merge**

The key issue: `fuseWithRRF()` only transfers `fields` from input hits, not
`debugScores`. We need the original `sparse` and `vector` from
`wholeDocResult`'s hits to survive into the merged result.

Approach: Before calling the merge `fuseWithRRF`, copy original debug scores
into the `fields` map as string values (the only metadata `fuseWithRRF`
preserves), then extract them back after fusion.

**Simpler approach:** Modify `fuseWithRRF()` to also carry forward
`debugScores` from input hits (merge maps, first-writer-wins). This is a
minimal change to the fusion method:

At lines 87-88 (BM25 loop) and 118 (KNN loop), after `fieldsByDoc.putIfAbsent`,
add:

```java
// Carry forward any existing debug scores from input hits
if (hit.debugScores() != null && !hit.debugScores().isEmpty()) {
  existingDebugScores.computeIfAbsent(docId, k -> new HashMap<>())
      .putAll(hit.debugScores());
}
```

Then at line 166, merge:

```java
Map<String, Float> hitScores = new HashMap<>();
// Carry forward pre-existing debug scores (e.g., original sparse/vector from
// the retrieval-level fusion, before chunk merge overwrites them)
Map<String, Float> existing = existingDebugScores.getOrDefault(docId, Map.of());
hitScores.putAll(existing);
// Now write this fusion's scores (with prefix to avoid collision)
hitScores.put(sparseKey, bm25Scores.getOrDefault(docId, 0.0f));
hitScores.put(vectorKey, vectorScores.getOrDefault(docId, 0.0f));
```

This means after chunk merge, each hit carries:
- `sparse` — original per-retriever BM25 score (from retrieval-level fusion)
- `vector` — original per-retriever KNN score (from retrieval-level fusion)
- `chunk_sparse` — chunk merge BM25 leg (new)
- `chunk_vector` — chunk merge KNN leg (new)

LambdaMART at `KnowledgeHttpApiAdapter:437-438` still reads `sparse` and
`vector` — which now correctly contain the original per-retriever scores.

#### Files Modified (1b)

| File | Module | Change |
|------|--------|--------|
| `HybridFusionUtils.java` | adapters-lucene | Add `scoreKeyPrefix` overload, carry forward `debugScores`, prefix keys |
| `SearchOrchestrator.java` | indexer-worker | Pass `"chunk_"` prefix in `mergeChunkResults()` call |

#### Verification (1b)

1. `./gradlew.bat spotlessApply`
2. `./gradlew.bat build -x test`
3. `./gradlew.bat :modules:adapters-lucene:test` — existing fusion tests + new
4. `./gradlew.bat :modules:indexer-worker:test`
5. Full: `./gradlew.bat test`

#### Test Additions (1b)

In `HybridFusionUtilsTest`:

- **Test: score key prefix writes prefixed keys.** Call `fuseWithRRF` with
  prefix `"chunk_"`. Assert result hits have `chunk_sparse` and `chunk_vector`
  keys, NOT `sparse` and `vector`.

- **Test: debug scores carried forward from input hits.** Create input
  `SearchHit` with `debugScores = {sparse: 5.0, vector: 0.8}`. Pass through
  `fuseWithRRF` with prefix `"chunk_"`. Assert output has BOTH `sparse: 5.0`
  (carried) AND `chunk_sparse` (new fusion score).

- **Test: carried scores don't overwrite fusion scores.** Ensure the prefix
  prevents collisions — if an input hit already has `chunk_sparse`, the fusion
  overwrites it (fusion wins for its own keys).

- **Test: backward compatibility.** Existing calls with no prefix (or empty
  prefix) produce identical results to current behavior.

---

### 1a: Cross-Encoder Guard — Eval-First Approach

**Goal:** Determine empirically whether cross-encoder reranking helps, hurts,
or is neutral for HYBRID mode results. Only remove the guard if the evidence
supports it.

#### Why Eval First

The existing guard is **intentional and documented**:
- Javadoc on `isRerankerEligible()`: "HYBRID mode already has semantic ranking
  from embeddings; reranker sees less context and hurts quality."
- Contract test (`KnowledgeHttpApiAdapterHarmfulCombinationsTest`):
  `hybridMode_silentlySkipsReranker()` — explicitly tests that HYBRID blocks
  the cross-encoder.
- The cross-encoder builds snippets via `extractQueryFocusedSnippet()` using
  match spans. When no spans exist, the fallback uses the start of
  `content_preview` — functional but suboptimal for snippet quality.

The tempdoc 250 P1 argument ("cross-encoder is retrieval-mode-agnostic") is
architecturally correct, but the existing code has a quality-based rationale
that must be validated before removal.

#### Experiment Design

**Dataset:** SciFact (strongest cross-encoder signal from 245 isolation:
+1.5 nDCG on BM25-only). Use the existing BEIR eval harness.

**Configs to compare (same index, Q8_0 embeddings):**

| Config | Retrieval | Cross-Encoder | Mode |
|--------|-----------|---------------|------|
| A (baseline) | SPLADE + Dense RRF | OFF | HYBRID |
| B (treatment) | SPLADE + Dense RRF | ON | HYBRID |

**How to enable cross-encoder for HYBRID:** Temporarily modify
`isRerankerEligible()` to accept HYBRID. This is a one-line change for the
experiment, not a permanent code change:

```java
return config.enabled()
    && (mode == SearchMode.SEARCH_MODE_TEXT
        || mode == SearchMode.SEARCH_MODE_HYBRID)
    && resultCount >= config.minHitsThreshold();
```

**Success criteria:**
- If B > A by ≥0.005 nDCG: remove the guard permanently.
- If |B - A| < 0.005: remove the guard (no harm, enables future flexibility).
- If B < A by ≥0.005 nDCG: keep the guard, update tempdoc 250 with evidence.

**Multi-dataset confirmation:** If SciFact shows positive or neutral, repeat
on NFCorpus (cross-encoder had good BM25 signal there too) to confirm.

#### Experiment Results (2026-03-02)

Ran via `run-ranking-experiments.ps1 -Step isolation -Dataset scifact -MaxSessions 2`
with Q8_0 embeddings and `ms-marco-MiniLM-L6-v2` reranker.

| Metric | HYBRID baseline | HYBRID + cross-encoder | Delta |
|--------|-----------------|------------------------|-------|
| nDCG@10 | 0.7055 | 0.7033 | -0.0022 |
| Recall@10 | 0.8068 | 0.8001 | -0.0067 |
| MRR@10 | 0.6837 | 0.6828 | -0.0009 |
| P@1 | 0.6200 | 0.6200 | 0.0000 |

**Decision:** Delta is -0.0022 (within ±0.005 threshold). However, cross-
encoder decreases both nDCG and recall while adding latency. The guard's
rationale is empirically validated — **keep the guard**.

**Implementation shipped:** Added `hybridModeEnabled` field to `RerankerConfig`
controlled by `JUSTSEARCH_RERANK_HYBRID_ENABLED` env var (defaults to false).
This enables future re-evaluation without code changes. The env var is also
wired into the eval harness (Session 2 in `run-ranking-experiments.ps1`).

**Future re-evaluation triggers:** Try again if (a) a better reranker model is
available (GTE-ModernBERT is available in `models/onnx/reranker/`), or (b) the
cross-encoder snippet extraction improves, or (c) we add ColBERT as a fast
intermediate reranker (100x cheaper than cross-encoder).

#### Implementation (not needed — guard kept)

~These steps were planned but not executed since the experiment showed the
guard is correct:~

1. ~~Modify `isRerankerEligible()` at `KnowledgeHttpApiAdapter.java:113`~~
2. ~~Update Javadoc at line 105-108~~
3. ~~Update or replace the `hybridMode_silentlySkipsReranker` contract test~~
4. ~~Set `crossEncoderSkipReason` appropriately for the remaining skip cases~~

#### Files Modified (1a — if guard is removed)

| File | Module | Change |
|------|--------|--------|
| `KnowledgeHttpApiAdapter.java` | app-services | Widen mode check in `isRerankerEligible()` |
| `KnowledgeHttpApiAdapterHarmfulCombinationsTest.java` | app-services (test) | Update contract test to reflect new behavior |

#### Verification (1a)

1. `./gradlew.bat :modules:app-services:test` — contract tests pass
2. BEIR eval: SciFact HYBRID + cross-encoder vs HYBRID without
3. If positive: repeat on NFCorpus
4. Full: `./gradlew.bat test`

---

### Overall Verification Sequence

After all three items are implemented:

1. `./gradlew.bat spotlessApply`
2. `./gradlew.bat build -x test` — full compilation
3. `./gradlew.bat test` — full unit test suite
4. Dev stack smoke test: start backend, run a HYBRID search, verify JSON
   response contains the new execution flags (`spladeExecuted`,
   `lambdaMartApplied`, `crossEncoderApplied`)
5. Verify debug scores: run a search with debug=true on a dataset with chunks,
   confirm `sparse`, `vector`, `chunk_sparse`, `chunk_vector` all present and
   distinct

### Risk Assessment

| Item | Risk | Mitigation |
|------|------|------------|
| 1c | LOW — additive only, no behavior change | Existing tests catch regressions |
| 1b | MEDIUM — changes score propagation in fusion | Existing `HybridFusionUtilsTest` covers RRF correctness; new tests cover prefix + carry-forward; LambdaMART reads same keys as before |
| 1a | HIGH if done without eval — intentional guard with quality rationale | Eval-first approach: only remove if evidence supports it |

---

## Additional Research Items (from 249 Investigations)

Items below were extracted from the 10 research investigations in tempdoc
249. They are grouped by theme and mapped to existing phases where
applicable. Items are included regardless of feasibility — the intent is
to capture all architecturally relevant findings.

### Score Fusion Alternatives (feeds Phase 2/3)

**DBSF — Distribution-Based Score Fusion (Qdrant v1.11+):**
Normalizes each leg with `(score - (mean - 3σ)) / (6σ)`, then sums.
Unlike RRF (rank-only), preserves score magnitude. Straightforward to
implement in `HybridFusionUtils`. (Qdrant A1)

**Weighted RRF — Per-Leg Weights (Qdrant v1.17, qmd):**
`score = sum(weight_i / (k + rank_i))`. Setting weight=3.0 for dense
and 1.0 for sparse lets the stronger retriever dominate. ~10 lines in
`HybridFusionUtils`. qmd adds top-rank bonuses (+0.05 rank 0, +0.02
ranks 1-2) and original-query 2x weight. (Qdrant A2, qmd F1b)

**Arctan Normalization (Milvus WeightedRanker):**
Query-independent normalization (same mapping regardless of result set).
Outlier-resistant but has a documented ranking inversion risk from
Jensen's inequality on concave arctan. ~20 lines in `HybridFusionUtils`.
Worth A/B benchmarking against min-max CC. (Milvus F4)

**Min-Max + Zero-Exclusion (OpenSearch, Milvus, Pyserini):**
Three independent sources confirm min-max + arithmetic mean outperforms
RRF by +4.5-7.8% nDCG@10. The key differentiator is zero-exclusion:
documents appearing in only one leg contribute 0 to numerator but don't
reduce the denominator. JustSearch's CC mode lacks zero-exclusion.
(OpenSearch 3.2, Milvus F6, Pyserini)

**Alpha Calibration > Normalization Choice (Bruch et al. TOIS 2024):**
Academic proof that the alpha weight in convex combination matters more
than the normalization formula. Alpha sweep on BEIR is the highest-value
next step regardless of normalization method. (Milvus F5)

**CC Min-Max Validated (SentenceTransformers):**
Independent confirmation that per-query min-max normalization before
fusion is architecturally correct. (SentenceTransformers KQ2)

**Position-Aware Reranker Score Blending (qmd F2a):**
Blend retrieval (RRF) and reranker scores with position-dependent
weights: ranks 1-3 use 75% retrieval + 25% reranker; ranks 4-10 use
60/40; ranks 11+ use 40/60. Prevents reranker from demoting strong
retrieval signals. ~30 lines in `KnowledgeHttpApiAdapter`. (qmd F2a)

### Pipeline Stage Gating (feeds Phase 4)

**BM25 Strong-Signal Gating (qmd F1a):**
Before invoking any LLM or optional component, run a BM25 probe. Skip
expansion/reranking entirely when top-1 score >= 0.85 and gap to rank
2 >= 0.15. Saves full expansion/reranking latency on navigational
queries. ~20 lines in `KnowledgeHttpApiAdapter`. (qmd F1a)

**Generalized Stage Gating (qmd A1):**
Extend gating pattern to any optional enhancement: cross-encoder,
SPLADE, LambdaMART. When BM25 confidence is high, all optional stages
can be safely skipped. (qmd A1)

**Configurable Rerank Count (Vespa F21):**
Expose the number of candidates sent to each reranking stage as a
configurable parameter. Currently hardcoded in JustSearch. (Vespa F21)

### Query Expansion Architecture (new topic)

**Typed Expansion Routing (qmd F1):**
Three-type expansion where `lex` variants go to BM25, `vec`/`hyde`
paraphrases go to KNN. Prevents cross-contamination (lexical expansions
don't dilute vector search and vice versa). Requires proto changes to
carry typed query variants through gRPC. (qmd F1)

**Expansion Result Caching (qmd F1c):**
Cache LLM expansion results in SQLite keyed by `(query, model_hash)`.
Repeated queries skip the LLM entirely. Warm-path turns 1500ms
expansion budget into sub-millisecond. (qmd F1c)

**Entity Preservation Guard (qmd F1d):**
After expansion, verify at least one original query term appears in
each expansion line; discard lines that fail. Stronger than the current
alphabetic-only hallucination guard. Graceful fallback on total failure.
(qmd F1d)

**GBNF Grammar for Structured LLM Output (qmd A2):**
Use llama.cpp's native `llama_sampler_init_grammar()` C API for
guaranteed structured output from expansion LLM. 4-line GBNF grammar,
production-validated in qmd. Stronger than current `JsonGrammarGuard`.
Needs FFM binding in `modules/ai-bridge`. (qmd A2, §7Q7b)

### Filtered Search Optimization (new topic)

**Brute-Force Fallback on Narrow Filters (Qdrant F4, Milvus F1/F3):**
When a filter eliminates >93% of candidates (below HNSW percolation
threshold `p_c = 1/m ≈ 6.25%` for M=16), brute-force exhaustive scan
is both faster and higher recall than HNSW graph traversal. Second
trigger: `k >= 50% of remaining valid docs`. ~50 lines in
`ReadPathOps.java` and `ChunkSearchOps.java`. (Qdrant F4, Milvus F1/F3)

**Dynamic Oversampling for Moderate Filters (Milvus F7):**
For 10-50% selectivity, dynamically increase query K based on estimated
filter selectivity. Already supported via `vectorEfSearchOverride` —
needs a formula-level rationale for computing the override. (Milvus F7)

**Clustering Compaction / SortingMergePolicy (Milvus F9):**
Redistribute Lucene segments by folder path hash via `SortingMergePolicy`,
enabling query-time segment pruning. 24x QPS improvement on large
datasets in Milvus benchmarks. Modest benefit at current desktop scale
(<1M docs). (Milvus F9)

### Observability Enhancements (feeds Phase 5)

**Per-Phase Timing (Vespa F23, OpenSearch 3.3):**
Emit per-component wall-clock timing (`took_ms`) in the structured
execution report. Vespa publishes this in `match-features`; OpenSearch
exposes it via `verbose_pipeline`. (Vespa F23, OpenSearch 3.3)

**Trace-Level Per-Leg Score Logging (Infinity F7):**
At `TRACE` log level, emit per-leg scores for every hit before and after
fusion. Useful for offline debugging without the overhead of a full debug
mode. (Infinity F7)

**Node-Level Pipeline Stats (OpenSearch 3.4):**
Aggregate pipeline execution statistics (component hit rates, fallback
frequency, average latency per component) as time-series metrics.
(OpenSearch 3.4)

### Caching (new topic)

**Reranker Score Caching (qmd F2d):**
Cache cross-encoder scores in SQLite keyed by `(query, file, model,
chunk_text)`. Chunk text in the key is a non-obvious correctness
requirement for chunk-sensitive rerankers. Saves 200ms deadline on
repeated queries. (qmd F2d)

**Result Caching (Infinity F27):**
Cache full search results for identical queries. Common in desktop search
where users re-run similar queries. (Infinity F27)

### Model Lifecycle (feeds Phase 4)

**Per-Model Inactivity Lifecycle (qmd A4):**
Lazy-load models on first use, dispose after 5 minutes of inactivity,
independently per model (embedding, generation, ranking). JustSearch's
Brain manages at process granularity; per-model lifecycle needed if GGUF
reranker is added. (qmd A4)

### Multi-Stage Retrieval (feeds Phase 4)

**Three-Phase Pipeline Model (OpenSearch 3.1):**
Explicit pipeline model with retrieval → normalization → fusion as
distinct processor stages. Each processor is pluggable and independent.
JustSearch's current pipeline folds these stages into a single
`fuseWithRRF` call. (OpenSearch 3.1)

**Matryoshka Two-Stage Retrieval (SentenceTransformers C):**
Use 256-dim truncated embeddings for first-stage KNN recall, then rescore
with full 768-dim embeddings before cross-encoder: 256-dim fast-KNN →
768-dim exact → cross-encoder. Wider recall pool at lower latency.
Requires Matryoshka truncation (253 item 20) first. (SentenceTransformers C)

**Pluggable Fusion Processor (OpenSearch 3.2):**
Fusion method (RRF, arithmetic mean, harmonic mean, geometric mean) is a
configurable processor, not hardcoded. JustSearch could expose fusion
method as a per-query parameter or config setting. (OpenSearch 3.2)

---

## Non-Goals

- Full Vespa-style phased ranking engine. We're not building a generic
  ranking framework — we're removing unnecessary constraints from the
  existing pipeline.
- Real-time Shapley attribution. Research-only; offline debugging at most.
- Automatic fusion weight adaptation based on index capabilities. Future
  work if 3b proves valuable.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 75 days at audit time.

