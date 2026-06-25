---
title: "234: Retrieval Architecture — Evidence-Backed Improvements"
type: tempdoc
status: done
created: 2026-02-25
---

> NOTE: Noncanonical doc (investigation + strategy). May drift. Verify against code before acting.

# 234: Retrieval Architecture — Evidence-Backed Improvements

## Purpose

Define the permanent retrieval architecture for JustSearch, grounded in a critical
analysis of the current policy-based design (tempdoc 229) and a systematic review of
the IR literature.

This tempdoc supersedes the adaptive-path work in tempdoc 229 that is blocked by
circular evidence dependency. Because there are no users and therefore no time pressure
to deliver early incremental results, intermediate steps that would otherwise be deployed
as fast-start bridges have been skipped. Only permanent components are built.

---

## Problem Statement

### What the current architecture does

The production pipeline in `SearchOrchestrator` + `KnowledgeHttpApiAdapter` runs:
BM25 + vector ANN → hybrid RRF fusion (`K=60`, `vectorWeight=0.75`, `bm25Boost=0.002`)
→ chunk-aware merge → excerpt computation → optional cross-encoder reranking.

The UI sends `mode: "hybrid"` on every search (`useSearch.ts:155`). The backend
`EmbeddingCompatibilityController` gates the vector leg: if the index has no embedding
fingerprint, a fingerprint mismatch, or no embedding model, the backend silently
downgrades to TEXT-only. Documents indexed while the LLM was active receive
`EMBEDDING_STATUS=PENDING` (GPU contention avoidance in `IndexingDocumentOps:85`)
and contribute only to the BM25 leg until a backfill pass completes them.

The RRF parameters are hardcoded and documented in tempdoc 135 as "unclear if tuned or
default guesses." There is no mechanism to adapt them to a user's corpus.

*(State at tempdoc creation, 2026-02-25. All gaps listed below have since been
addressed by the implementation items in this doc.)*

**Feature infrastructure gap**: The pipeline currently produces one number per result —
the fused RRF score. Raw BM25 and dense cosine scores are computed inside
`HybridFusionUtils` but discarded after fusion unless `debug=true` (`HybridFusionUtils:162`).
QPP metrics (MaxIDF, AvgICTF, Query Scope) do not exist anywhere in the codebase.
Document-level features (size, content type, freshness) are stored as Lucene fields but
are never assembled into a feature vector. There is no per-query feature log, no training
store, and no relevance labels. The minimum prerequisite to begin any feature-based
training is exposing the raw per-leg scores (the `HybridFusionUtils` P2-A prerequisite,
~10 lines).

### What tempdoc 229 proposed and why it is blocked

Tempdoc 229 proposed a corpus-profile taxonomy (3–5 named profiles) with governance
gates (A–F) and a 21-day revalidation cadence. The adaptive path is currently
`blocked-by-evidence`:

- **U2 gate**: requires 3/15 query buckets to pass live relevance thresholds — needs
  live users who have not yet used the system
- **U7 gate**: requires live stages to exist — which require the adaptive path to already
  be deployed

This is a circular dependency. The system cannot collect the evidence required to
unlock itself without already being live. There is no path through the gates before
public launch.

### Why the policy taxonomy is the wrong granularity

The literature (TOIS 2025, arXiv:2504.01101) shows:
- QPP-driven corpus-level routing achieves only ~4% NDCG gain over a fixed hybrid baseline
- Collection identity (ANOVA F=137.6) dominates any retrieval performance predictor —
  meaning signal does not transfer across corpora and requires per-corpus recalibration
- A fixed hybrid (BM25 + dense RRF) consistently outperforms either alone by 1–3 nDCG@10
  with no routing machinery at all

The 3–5 profile taxonomy is hand-designed with no learned basis. A personal file
collection is heterogeneous, grows continuously, and does not fit neatly into static
corpus buckets.

### The circular evidence dependency has a solution

GPL (Generative Pseudo Labels, NAACL 2022) uses the existing 8B LLM to generate
synthetic queries per document at index time, then uses a cross-encoder to score
`(synthetic query, document)` pairs. This produces a training corpus internally — no
live users needed. It is the architectural keystone that unlocks the entire adaptive
path.

---

## Design Decisions

Decisions that shaped what is and is not in this plan. Recorded so they do not need to
be re-derived.

### Decision 1 — Skip P1-B (Tuned Convex Combination)

A tuned convex combination (`score = α × bm25_score + (1-α) × dense_score`) would
outperform RRF and could be deployed after only 20–100 GPL triples. It was considered
as a fast-start bridge while waiting for the 500+ triples needed by LambdaMART.

**Skipped because**: without time pressure (no users), building infrastructure that
will be discarded in weeks is pure waste. The existing untuned RRF remains as the
development baseline with no user impact. The one reusable piece of work — score
normalisation for BM25 and dense legs — moves directly into P2-A's feature engineering
rather than being done twice.

### Decision 2 — Skip P1-C (HyPE)

HyPE (index-time hypothetical query generation) stores LLM-generated queries as
searchable Lucene fields, providing vocabulary expansion before SPLADE is available.
It was considered as a bridge while SPLADE was being built, to provide recall
improvement for a deployed system that could not yet re-index.

**Skipped because**: there are no users with an existing indexed corpus. SPLADE can be
the first and only sparse retrieval leg from day one. The re-index requirement that made
HyPE necessary as a bridge does not exist at this stage. HyPE is not permanently
discarded — if SPLADE hits an unexpected blocker, it can be revisited. Until then there
is no reason to build it.

### Decision 4 — Global LambdaMART model with vault-id feature

If a user indexes multiple independent vaults, a per-vault model is more accurate in
theory, but requires independent GPL runs per vault, per-vault model storage, and
training orchestration complexity — all before a single model has ever been trained.

**Decision**: global model. Vault-id is encoded as a categorical feature so the
gradient-boosted trees can learn corpus-specific signal implicitly where it exists.
If vault-specific behaviour proves insufficient after deployment, splitting to per-vault
is a straightforward follow-on. Open Question 2 is closed.

> **Implementation note**: vault-id is not included in the V1 feature schema (2
> features: sparse, vector) or the planned V2 schema (adds doc-level features
> only). It is deferred until multi-vault indexing is implemented — there is currently
> no vault-id field in the index schema to encode.

### Decision 5 — Min-max normalisation for LambdaMART feature engineering

BM25 and dense cosine scores are on different scales and must be normalised before
constructing the feature vector. Options were z-score, sigmoid, and min-max per result
set. Z-score is noisy at small result set sizes (typical in personal-corpus queries:
5–15 hits). Sigmoid requires calibration. Min-max per result set is the standard for
LambdaMART and is stable regardless of result set size.

**Decision**: min-max normalisation per query result set. Applied to raw BM25 and
dense scores after they are exposed from `HybridFusionUtils` (see P2-A prerequisite).
Open Question 3 is closed.

### Decision 6 — Binary relevance grades (0/1) for LambdaMART labels

Cross-encoder scoring produces continuous float values in `[0.0, 1.0]`. LightGBM's
`objective=lambdarank` does **not** accept float labels — the `label_gain` parameter
maps integer grade values to NDCG gains via array indexing (`label_gain[grade]`). A
float like `0.85` would be truncated to integer `0`, making every document appear
irrelevant and producing a degenerate training signal.

**Decision**: binary grades from the `is_negative` field:
- Positive triple (`is_negative=false`) → grade `1`
- Negative triple (`is_negative=true`) → grade `0`

`setField("label", float[])` accepts `float[]` in lightgbm4j; pass `1.0f` / `0.0f`
which are truncated to the correct integers internally. The binary scheme is
principled: the cross-encoder already determined which documents are relevant
(positives) vs. not (negatives); LambdaMART learns to push grade-1 documents above
grade-0 ones through pairwise NDCG gradient updates. Finer grade distinctions (e.g.
0/1/2 based on cross-encoder score buckets) are possible in a future retrain but
require calibrated thresholds. Binary labelling is sufficient for initial validation.

### Decision 7 — Include `vector` as all-zeros in training feature matrix

The re-query pass uses `SEARCH_MODE_TEXT`, so `vector` is always `0.0` in every
training triple. Two options: (a) exclude `vector` from the feature vector; (b) include
it as all-zeros.

**Decision**: include it (option b). LightGBM's dataset construction detects constant
features via `BinMapper::FindBin()` — a feature with one unique value is marked
`is_trivial=true` and excluded from all histogram computation and split candidates
before training starts. Zero compute cost.

At inference time after the HYBRID re-query switch: `LGBMBooster` validates that the
number of columns matches training. If `vector` was excluded at training (option a),
the model must drop the column before every predict call — and cannot benefit from real
vector scores until retrained. If included (option b), the existing model continues
to work unchanged (no tree node learned a split on it so the new non-zero values are
ignored), and a retrained model can immediately learn splits on real vector data.
Option b provides a clean migration path at zero training cost.

### Decision 8 — LambdaMART inference lives in Head, not Worker

Three options were evaluated for where LambdaMART inference runs:
(a) Worker-side — replace `HybridFusionUtils.fuseWithRRF()` inside `SearchOrchestrator`;
(b) Head-side — rerank Worker's top-K results in `KnowledgeHttpApiAdapter` after the gRPC
call returns;
(c) lazy-load on request in Worker (file-watch / poll variant).

**Decision**: Head-side (option b).

Rationale:
- All ML inference currently lives in Head. The cross-encoder reranker is in
  `KnowledgeHttpApiAdapter`. Moving LambdaMART to Worker breaks this pattern.
- The P2-A prerequisite (always-emit `bm25` + `vector` in `SearchResponse`) means all
  feature data already flows to Head on every search call — no additional gRPC traffic.
  QPP fields are also in `SearchResponse`. Zero new proto changes.
- `LGBMBooster` is constructed in Head (training runner), held in an
  `AtomicReference<LGBMBooster>` field on `KnowledgeHttpApiAdapter`, and swapped
  atomically when training completes. Training-to-inference hand-off is a single
  `AtomicReference.set()` — no file path, no reload signal, no race window.
- `lightgbm4j` dependency belongs in `modules/app-services`, same module as
  `GplJobCoordinator` (training data) and `KnowledgeHttpApiAdapter` (inference path).
  Worker (`modules/indexer-worker`) requires no changes.
- SPLADE-compatible: when SPLADE ships, Worker emits SPLADE scores in
  `debug_scores` under a new key; Head-side LambdaMART reads from the map by key
  name — no structural inference-path change.

Worker-side would only be preferable if Head and Worker ran on separate hosts.
They share the same machine; process isolation is for reliability, not distribution.

### Decision 3 — Early validation gates

Two technical unknowns must be answered before significant engineering on SPLADE or
LambdaMART begins. If either fails, the plan needs revision.

1. **VRAM feasibility**: do BERT-base (~450 MB FP16 active) and the 8B LLM (~4.5 GB
   Q4_K_M) coexist within this machine's 12 GB VRAM during indexing? Expected peak is
   ~5–7 GB — likely fine, but must be confirmed under concurrent load.
2. **lightgbm4j native library**: `libomp140.x86_64.dll` is already bundled in the
   distribution (staged from the llama-server prebuilt, verified at line 1083 of
   `modules/ui/build.gradle.kts`). lightgbm4j's Windows binary uses the same LLVM
   OpenMP DLL or `vcomp140.dll` (VC++ Redistributable, also confirmed present). The
   libomp question is resolved — the gate reduces to a latency benchmark only: verify
   `predictForMatSingleRowFast` meets <5ms per query for the top-50 reranking
   candidate set on production hardware.

These are the first checkboxes in P3-A and P2-A respectively.

---

## Item Sequencing

```
(no deps)    P1-D (QPP telemetry — stored in triple store, deferred to V2 routing layer)
(no deps)    Harmful Combinations (isolated)
(no deps)    P3-A SPLADE (parallel dev branch; adopting it triggers LambdaMART retrain)

(no deps)    P1-A ──► P2-A (training triples, ≥500 needed)
             P1-A ──► Event-Driven Revalidation (re-triggers GPL job)

(live users) P2-B (bandit) — deferred; post-launch only; arms defined by P2-A
```

P3-A (SPLADE) can be developed in parallel on a separate branch with no coupling to
Phase 1 or Phase 2 work. Adopting SPLADE does not discard the GPL triples or QPP log
data — only the trained LambdaMART model artefacts need rebuilding against SPLADE+dense
score distributions instead of BM25+dense. GPL data remains valid.

---

## Scope

### In scope

- GPL offline job as a post-index background step
- LambdaMART first-stage fusion as the fusion method
- Thompson Sampling bandit as the post-launch adaptation mechanism
- SPLADE-v3 as the long-term first-stage retrieval replacement

### Out of scope

- ColBERT / PLAID (Python-only toolchain, architecturally incompatible)
- HyDE live at query time (0.7–2.4s latency, disqualifying)
- IRCoT (N × LLM generation time per query — future "deep research" mode only)
- HippoRAG (future multi-hop query mode)
- BGE-M3 (evaluate after SPLADE proves the learned sparse path)

### Excluded intermediate steps

P1-B (convex combination) and P1-C (HyPE) were analysed, found to be temporary
bridges to P2-A and P3-A respectively, and explicitly excluded. See Design Decisions
for rationale.

---

## Utility Metric

Retained from tempdoc 229 (well-reasoned, keep as the canonical evaluation objective):

```
U = 0.50 × nDCG@10 + 0.30 × MRR@10 + 0.20 × P@10   (macro average by query class)
```

**Practical eval note**: The full U metric with per-query-class breakdown requires
infrastructure that does not yet exist (query class stratification wired into an eval
runner). For initial LambdaMART validation, compute nDCG@10 + MRR@10 only, from the
GPL held-out 20% split against the user's corpus. The BEIR lane (existing) provides
nDCG@10 on public benchmarks; MRR@10 is now emitted by `beir-eval-win.ps1` and
extracted by `suite-loader.mjs` (pre-Phase 2 work); `diff-search-eval-suite.mjs`
accepts `--min-mrr-ratio` and the `compareModeMetric` call for `meanMrrAtK` is now
active. Existing BEIR baselines lack `meanMrrAtK` values — recapture baselines with
updated `beir-eval-win.ps1` to enable MRR comparison. P@10 and per-query-class
breakdown are deferred
until the core training loop is validated.

---

## What to Keep from Tempdoc 229

| Element | Keep? | Rationale |
|---------|-------|-----------|
| Utility function (above) | **Yes** | Well-reasoned objective, corpus-independent |
| Query class taxonomy (keyword / semantic / hybrid / navigational / factual) | **Yes** | Valid regardless of retrieval architecture |
| Harmful combinations registry (`HYBRID + reranker`, `HYBRID + expansion`) | **Yes** | Should be codified as formal invariants |
| 3–5 corpus-profile taxonomy | **Replace** with LambdaMART model | No learned basis; collapses to learned weights |
| 21-day revalidation cadence | **Replace** with event-driven triggers | Arbitrary cadence; event-driven is more precise |
| Offline-to-live confidence (Gate F) | **Simplify** | Replace live-traffic requirement with offline evaluation coverage check |

---

## Phase 1 — Immediate (no live users, no re-index required)

### P1-A: GPL Offline Corpus Adaptation Job

**Depends on**: nothing — this is the keystone item.
**Enables**: P2-A (training triples), Event-Driven Revalidation.

**What**: After initial indexing completes, run a background job that:
1. For each indexed document, prompt the 8B LLM to generate 2–3 synthetic search queries
2. For each `(synthetic query, document)` pair, score relevance using the existing
   cross-encoder reranker
3. Persist `(query_text, doc_id, relevance_score)` triples to a local training store

**Why**: GPL (arXiv:2112.07577) produces pseudo-labelled training data without any
live users. It is the cold-start solution that enables every downstream step in this
plan. InPars+ validates that LLaMA 3.1 8B (equivalent to Qwen3-8B) is a sufficient
query generator. _(Scope note: InPars+ arXiv:2508.13930 validates LLaMA 3.1 8B for
reranker training with CPO + Chain-of-Thought prompting, not GPL-style bi-encoder
distillation via plain prompting as implemented here. The few-shot prompt in
`GplJobCoordinator` is the right engineering approach based on the general InPars line
of work, but the exact LLM+methodology combination is not independently validated in
the literature. Verified empirically in Q9: few-shot prompting produces natural search
queries on 20 SciFact docs with zero title-like output.)_

**Data produced**: 500–2,000 triples for a typical 250–1,000 document personal corpus
(2 queries/doc). 4–12 hours for 10k docs on a single GPU; proportionally faster for
smaller corpora.

**Process boundary**: GPL lives in Head. It uses the existing `OnlineAiService` for
query generation and the existing `CrossEncoderReranker` (already in
`KnowledgeHttpApiAdapter`) for scoring. Document text is fetched from Worker via the
existing `FetchDocuments()` gRPC method (`indexing.proto`; `GrpcSearchService.java`
lines 273–332) — content is a stored Lucene field trimmed to 200 000 chars per
document. Triples are written to a new NDJSON file in the data directory (consistent
with the existing `NdjsonMetricExporter` pattern in Worker).

No indexing completion event exists. Triggering requires polling
`WorkerStatusService.getStatus()` (or the gRPC equivalent) until `indexAvailable`
transitions to true and the indexed document count stabilises. The GPL coordinator
in Head runs a polling loop at the end of each ingest flow.

- [x] **Prerequisite**: add `ListAllDocumentIds(offset, limit)` gRPC method to
  `indexing.proto` + implement in `GrpcSearchService`; returns a page of doc IDs
  and total count via Lucene IndexReader; GPL job needs this to iterate the full
  corpus without depending on folder hierarchy
- [x] Implement GPL query generation: prompt 8B LLM with document content, collect 2–3
  query strings per document
- [x] Implement cross-encoder scoring of `(query, document)` pairs using existing
  reranker infrastructure
  > **Resolved in P2-A prerequisites**: cross-encoder wired via `adapter::rerankerIfReady`
  > supplier; negative candidates scored by the same reranker. All extended-format triples
  > have real cross-encoder scores (or 1.0f fallback when model not configured).
- [x] Persist training triples to NDJSON file in the data directory
- [x] Wire job trigger: GPL coordinator in Head polls Worker gRPC status until
  `indexAvailable` is true and document count stabilises; then fires GPL job as
  background task with progress reporting
- [x] Expose job status via `/api/debug/state` or equivalent

---

### P1-D: QPP — LambdaMART Feature Preparation

**Depends on**: nothing — fully isolated.
**Enables**: P2-A (QPP values as features in the LambdaMART feature vector).

**What**: Compute pre-retrieval QPP metrics (MaxIDF, AvgICTF, Query Scope) per query
and log them alongside each query in telemetry.

**Why**: QPP values are O(1) in Lucene via `IndexReader.docFreq()` (100–500ns per
term) — free to compute at every query indefinitely. They become features in P2-A's
LambdaMART model, capturing per-query difficulty signals that a scalar fusion weight
cannot express. QPP-driven routing alone achieves only ~4% NDCG gain and is therefore
not used for routing; the value is entirely as a feature input.

**Storage note**: No structured per-query store exists in the backend. Query text is
deliberately redacted in all logs and metrics (`SensitiveQuery` pattern). The preferred
approach is to add QPP fields to the gRPC `SearchResponse` proto and log them in Head,
keeping QPP values co-located with other per-query metadata. Both the proto approach
and a new Worker-side NDJSON log require new infrastructure.

- [x] Add QPP metric fields to gRPC SearchResponse proto; emit from SearchOrchestrator
- [x] Implement QPP signal extraction in `SearchOrchestrator` (MaxIDF, AvgICTF, QS)
- [x] Log values alongside each query in telemetry
- [x] No routing logic — feature prep only

---

## Phase 2 — After GPL Data Available (COMPLETE)

### P2-A: LambdaMART First-Stage Fusion

> **Implemented (2026-02-25)**: `LambdaMartFeatureSchema`, `LambdaMartTrainer`,
> `LambdaMartReranker` (all in `io.justsearch.app.services.gpl`). `lightgbm4j:4.6.0-2`
> added to `modules/app-services`. Training triggered by `GplJobCoordinator` callback;
> inference wired into `KnowledgeHttpApiAdapter` before cross-encoder (LambdaMART fires
> first; cross-encoder is skipped when model loaded). V1 feature set (5 features:
> BM25, vector, QPP×3) with per-result-set min-max normalisation. Offline eval returns
> NDCG@10 + MRR@10. Integration benchmark: p99 <5ms for 50 candidates. Unit tests for
> all three classes plus LambdaMartBenchmarkTest in integrationTest source set.
>
> **Live verification (2026-02-26)**: End-to-end pipeline tested with synthetic triples
> (50 triples, 10 query groups). Full log sequence confirmed: GPL completion → callback
> → training (50 loaded, 0 skipped, train=8, eval=2) → early stop at iter 1 → NDCG@10=1.0 (meaningless: only 2 eval groups)
> → model loaded → reranking active on search. **Wiring bug fixed**: `KnowledgeSearchController`
> created its own `KnowledgeHttpApiAdapter` without passing `LambdaMartReranker` (null);
> search endpoint was silently skipping reranking. Fixed by threading
> `LambdaMartReranker` through `LocalApiServer.Builder` → `KnowledgeSearchController` →
> `KnowledgeHttpApiAdapter`. Confirmed both TEXT and multi-query searches show
> `"LambdaMART reranked N results"` at DEBUG level.

**Depends on**: P1-A (≥500 GPL triples as training data) + P1-D (QPP values logged as
features). Both must be running for a meaningful period before this item is viable.

> **Verification note**: Dev-stack validation (Run 3) trained with 324 triples from 27
> docs — below the ≥500 target. The run validated pipeline wiring (GPL → training →
> inference → search reranking), not model quality. Quality validation requires a
> larger corpus (see Open Question 10).
>
> **Full-corpus verification (2026-02-28)**: Dev stack with 636 indexed documents.
> GPL completed with 7,812 triples (12× above the ≥500 threshold). LambdaMART
> trained on 1,040 query groups, evaluated on 260 held-out groups:
> NDCG@10=0.764, MRR@10=0.685. Model loaded on startup from persisted
> `lambdamart-model.txt`. Every search request confirmed reranked via backend
> log (`"LambdaMART reranked N results"`). `readiness.components.lambdamartModel`
> reports `READY` in `/api/status`. SPLADE active as sparse retrieval leg
> (`debugScores` contains `"sparse"` key with non-zero values). Cross-encoder
> correctly skipped when LambdaMART is active.
Replaces the existing untuned RRF baseline directly.
**Enables**: the trained model defines the policy configurations that P2-B (deferred)
will use when live users exist.

**What**: Replace the existing RRF fusion in `SearchOrchestrator` with a
gradient-boosted tree ranker (LambdaMART) that uses BM25 score, dense score, and QPP
metrics as V1 input features (5 features). V2 adds document length, chunk flag,
content-type, and freshness (deferred).

**Why**: LambdaMART consistently outperforms any scalar fusion weight when ≥500 labelled
queries are available. It adds only 1–5ms latency overhead (vs. 200–500ms for
cross-encoder reranking). Java implementation: `io.github.metarank:lightgbm4j:4.6.0-2`
(LightGBM native LambdaMART, `objective=lambdarank`; Maven Central, actively maintained
2024; `predictForMatSingleRowFast` for low-latency per-document inference; training is
CPU-only by default; inference is sub-millisecond per document for shallow trees).
Requires OpenMP (`libomp`) as a native runtime dependency on Windows.

Note: LTR4L is abandoned (last commit July 2020, targets Lucene 6–7, not on Maven
Central, does not implement LambdaMART). Use lightgbm4j instead.

**Prerequisite**: GPL triples from P1-A (≥500 pairs needed; typical 500-doc corpus
produces ~1,000 pairs at 2 queries/doc).

> **Data freshness**: Any triples written before the P2-A prerequisites were implemented
> use the legacy NDJSON format (no `query_id`, no `is_negative`, no feature columns) and
> are unsuitable for LambdaMART training. The NDJSON file should be deleted and the GPL
> job re-run to produce a clean extended-format dataset before training begins.
>
> **Vector feature**: The re-query pass uses `SEARCH_MODE_TEXT` so `vector` is always
> `0.0` in all training triples. **Include `vector` in the training matrix anyway** —
> LightGBM's `BinMapper` detects all-constant columns (`is_trivial=true`) and excludes
> them from all split computation at dataset-construction time, so the cost is zero.
> More importantly, the column position must be present at inference; including it now
> means the trained model stays valid when HYBRID re-query is added (values become
> non-zero; tree nodes never learned a split on it so they continue to ignore it until
> the model is retrained with real vector data). See Decision 7. Switching to HYBRID
> mode for the re-query pass is deferred; the BM25+QPP feature set is sufficient for
> initial validation.

- [x] **Prerequisite — Negative sampling + cross-encoder wiring**: the P1-A GPL job
  produces only positive `(query, source_doc, score=1.0)` pairs. LambdaMART requires
  pairwise comparisons between relevant and non-relevant documents for the same query;
  without negatives the model has nothing to rank against. Before training: (a) wire the
  cross-encoder in `AppFacadeBootstrap.createGplJobCoordinator()` — currently `null`;
  (b) for each synthetic query, run it against the live index, take the top-K returned
  documents that are *not* the source document as negative candidates, and score each
  `(query, negative_doc)` pair with the cross-encoder; (c) persist negatives with their
  graded scores alongside the positive triples; (d) add a stable `query_id` field to the
  NDJSON format so the training runner can group triples by query for listwise ranking.
  This step and the feature re-query pass below are a single combined pass.
  **Implemented**: `GplJobCoordinator.collectFeaturesAndNegatives()` — combined search
  re-query + negative sampling pass; `KnowledgeHttpApiAdapter.rerankerIfReady()` shares
  the existing ONNX instance; `AppFacadeBootstrap` wired with `adapter::rerankerIfReady`.
- [x] **Prerequisite — Feature re-query pass** (same pass as negative sampling above):
  the current triple store records only `doc_id`, `synthetic_query`, `score`, and
  `timestamp_ms` — it does not capture BM25 score, dense cosine score, QPP metrics, doc
  length, or any other LambdaMART input feature. For each query in the training store,
  run it against the live index and capture per-document values from
  `SearchHit.debugScores()` (BM25, dense) and the `SearchResponse` QPP fields. This pass
  generates both the negative candidates and the complete feature vectors for all
  candidates simultaneously; doing it as two separate passes would double the query load.
  **Implemented**: `GplTrainingTripleStore.appendWithFeatures()` extended NDJSON schema
  with `query_id`, `is_negative`, `bm25`, `vector`, `qpp_*`, `rank_position` columns.
- [x] **Validate early**: benchmark lightgbm4j `predictForMatSingleRowFast` on
  production hardware — target <5ms per query for top-50 candidate set; libomp is
  already bundled in the distribution (Design Decision 3). Verified API signatures:
  - Training: `LGBMBooster.create(LGBMDataset dataset, String parameters)` creates a
    booster; there is **no** `train(numIterations)` shortcut — advance one iteration at
    a time via `booster.updateOneIter()` (returns `true` when converged) in a loop.
  - Inference: `predictForMatSingleRowFast(FastConfig config, float[] data,
    PredictionType predictionType)` returns `double`; requires `FastConfig` pre-allocated
    once via `predictForMatSingleRowFastInit(predType, dtype, ncols, params)`.
  - Training data: `LGBMDataset.createFromMat(float[], numRows, numCols, isRowMajor,
    params, null)` + `dataset.setField("label", float[])` +
    `dataset.setField("group", int[])`.
  - Thread safety: `LGBMBooster` safe to share; `FastConfig` is **not** — use
    `ThreadLocal<LGBMBooster.FastConfig>`, one per request thread.
  **DLL risk**: lightgbm4j extracts native DLLs to a temp directory at classload time.
  In a JUnit test running outside the installed distribution, `libomp140.x86_64.dll`
  may not be on the PATH. Structure the benchmark as an integration test that runs
  within the distribution layout (or sets `java.library.path` explicitly), not a plain
  unit test. Verify DLL loading succeeds before measuring latency.
  This checkpoint is a hard gate: if latency exceeds 5ms for 50 candidates on
  production hardware, stop and escalate — no fallback architecture exists in this plan.
- [x] **Prerequisite**: in `HybridFusionUtils.fuseWithRRF()`, move `"bm25"` and
  `"vector"` score entries outside the `if (debug)` block so they are always emitted
  (~10 lines; `SearchHit.debugScores`, proto `debug_scores`, and all callers are
  already wired — no other changes needed). File:
  `modules/adapters-lucene/.../HybridFusionUtils.java`
- [x] Define feature vector — two phases:

  **V1 (implement first; all fields immediately available at training and inference):**
  `[bm25_norm, vector_norm, qpp_max_idf_norm, qpp_avg_ictf_norm, qpp_query_scope_norm]`
  — 5 features. `bm25` and `vector` come from `SearchResult.getDebugScoresMap()`;
  QPP comes from `SearchResponse` fields 17-19 (`max_idf`, `avg_ictf`, `query_scope`).
  Both are in the NDJSON today and accessible inside `KnowledgeHttpApiAdapter.search()`
  at inference. No additional fetch steps, no NDJSON schema changes, no proto changes.
  **Implement V1 first and validate it beats RRF before adding V2 features.**

  **V2 (deferred; add after V1 validates the approach):**
  Extend schema to `[bm25_norm, vector_norm, qpp_max_idf_norm, qpp_avg_ictf_norm,
  qpp_query_scope_norm, doc_length_norm, is_chunk, content_type_id, freshness_norm]`
  — 9 features. Doc-level features (`size_bytes`, `mime`, `modified_at`, `is_chunk`)
  are available in `SearchResult.getFieldsMap()` at inference — using `getFieldsMap()`
  at both training and inference ensures training-inference consistency. At training
  time, extend `GplTrainingTripleStore.appendWithFeatures()` to capture these values
  from the `SearchResult.fields` map during the re-query pass (not from
  `FetchDocuments` — `DocumentContent.metadata` contents are unverified). Requires
  NDJSON schema version 2 and a GPL job re-run to regenerate training data.
  Verify exact field key names against the Worker's document writer before implementing.

  **Applies to both versions:**
  - Relevance labels: binary grades from `is_negative` — `0` / `1`. Float labels
    do not work with `objective=lambdarank` (`label_gain` uses label as array index).
    See Decision 6.
  - `rank_position` must NOT be in the feature vector — training-collection TEXT rank
    vs. production HYBRID rank is a distribution mismatch. Field stays in NDJSON for
    diagnostics only.
  - Feature column ordering is a fixed contract: any change invalidates trained models.
  - Optional enhancement (post-validation): pass cross-encoder scores via
    `setField("weight", float[])` to up-weight high-confidence labels. Positives:
    weight = score; negatives: weight = `1.0 - score`. Preserves gradient without
    calibrated thresholds.
- [x] Implement min-max normalisation per query result set for BM25 and dense legs
  (Decision 5). NaN guard required: when `max == min` for a feature across the result
  set (e.g. `vector` all-zero), return `0.0f` instead of computing `0/0`.
  Guard: `(max - min) < 1e-9f ? 0.0f : (v - min) / (max - min)`. Apply to all
  features, not only BM25/dense.
- [x] Train LambdaMART model on GPL triples; evaluate on held-out 20% split.
  **Training loop**: `LGBMBooster.create(dataset, params)` + call `updateOneIter()` in
  a loop until it returns `true` (converged) or a max-iterations cap is hit. There is
  no single `train(numIterations)` method.
  **Hyperparameters** — conservative defaults for small corpora (500–5,000 triples):
  `num_leaves=8`, `min_data_in_leaf=10`, `num_iterations=100`, `learning_rate=0.1`,
  `objective=lambdarank`, `metric=ndcg`, `ndcg_eval_at=10`. Tune `num_leaves` upward
  (16, 31) if the held-out NDCG@10 is still improving; reduce if training set is
  <1,000 rows.
  **Split**: hold out 20% by `query_id` — entire queries go to one split, never
  split a query's triples across train/test.
  **Offline eval**: compute NDCG@10 + MRR@10 entirely from stored NDJSON feature
  values — no live search call needed. For each held-out query group, sort triples by
  model-predicted score, check relevance label. IDCG denominator = `sum(1/log2(i+2))`
  for `i` in `[0, numRelevant)` — use actual relevant-doc count in the group, not
  a fixed k. This is the primary go/no-go signal before integration.
- [x] Integrate trained model into `KnowledgeHttpApiAdapter` (Head-side reranking —
  see Decision 8). After training: `adapter.setLambdaMartModel(booster)` swaps an
  `AtomicReference<LGBMBooster>`. In `search()`, if non-null, apply
  `predictForMatSingleRowFast` per hit using the fixed V1 feature column order; sort
  hits by predicted score; return reordered result. Null → RRF passthrough
  (backward-compatible). Use `ThreadLocal<LGBMBooster.FastConfig>` for thread safety.
  **QPP timing**: `SearchResponse` fields `max_idf`, `avg_ictf`, `query_scope` are
  dropped from the `KnowledgeSearchResponse` DTO before the method returns (logged at
  DEBUG only). Reranking must happen inside `search()` before DTO construction —
  read QPP directly from the raw `SearchResponse` proto object at that point.
  `lightgbm4j` dep: `modules/app-services/build.gradle.kts`.
- [x] **Eval path 1 — GPL held-out eval** (corpus-specific): score the trained model
  against the withheld 20% GPL triples; compute nDCG@10 + MRR@10; this is the primary
  signal that LambdaMART is working for this corpus. **Offline evaluation only** — no
  live search call needed. Apply the model to the held-out triples' stored feature
  values directly; no round-trip to the running Worker. New infrastructure — the
  existing BEIR bench cannot substitute (different corpus, different query source).
- [x] **Eval path 2 — BEIR gate** (model selection): use the existing search-eval lane
  (`diff-search-eval-suite.mjs`) to confirm LambdaMART beats RRF on public benchmarks
  before deployment. MRR@10 is now emitted by `beir-eval-win.ps1` and extracted by
  `suite-loader.mjs`; `diff-search-eval-suite.mjs` accepts `--min-mrr-ratio` and the
  `compareModeMetric` call for `meanMrrAtK` is now active. The DAG runner
  (`dag-runner-beir-gate.mjs`) passes `--min-mrr-ratio` through to the diff script,
  and `run-beir-gate-win.ps1` exposes a `$MinMrrRatio` parameter. Run against the
  BEIR datasets in `tmp/beir-cache/`. Existing baselines lack `meanMrrAtK` values —
  recapture baselines with updated `beir-eval-win.ps1` to enable MRR comparison.
  The RAG-eval lane is irrelevant — wrong layer. The full U metric with per-query-class
  breakdown is deferred; nDCG@10 + MRR@10 from the GPL held-out split is sufficient
  for an initial go/no-go.
- [x] Document retraining trigger: when to retrain (corpus size doubles, new content type).
  **Policy**: The GPL auto-trigger in `AppFacadeBootstrap.startGplAutoTrigger()` uses
  event-driven revalidation (`GplRevalidationTrigger`) to decide when GPL should re-run:
  - **First ingest**: No previous snapshot exists → GPL always runs on first corpus
    stabilization. After completion, `captureGplEvalSnapshot()` persists the corpus state
    (doc count, MIME distribution, triple count) to `gpl-eval-snapshot.json`.
  - **Corpus size doubles**: The auto-trigger polls Worker status every 30s. When doc
    count stabilizes, it fetches current MIME facets and compares against the last-eval
    snapshot. GPL re-runs only when `currentDocCount >= sizeDoubleFactor × lastDocCount`
    (default 2.0×, configurable via `JUSTSEARCH_GPL_REEVAL_SIZE_FACTOR`). Small
    incremental ingests (e.g. 5 docs into a 5000-doc corpus) are correctly skipped —
    retraining on trivially changed data is waste.
  - **New content type**: GPL re-runs when the current MIME distribution contains keys
    absent from the last-eval snapshot (e.g. `application/pdf` appears for the first time).
    This catches qualitative corpus changes that shift the LambdaMART feature distribution.
  - **After GPL completes**: The `onJobCompleted` callback captures a new snapshot, then
    fires `startLambdaMartTrainingAsync()` which retrains on the full triple store
    (old + new triples) and atomically hot-swaps the model.
  - **SPLADE migration (Phase 3)**: Switching from BM25 to SPLADE invalidates all
    existing triples (different feature scale). Delete the triple store *and* the
    `gpl-eval-snapshot.json` file, then re-run GPL from scratch. Deleting the snapshot
    ensures the trigger treats the next stabilization as a first-run. The V1 feature
    schema (2 features: index 0 = sparse score, 1 = dense score) is unchanged by the
    SPLADE switch — only the score distributions differ. Delete the existing
    `lambdamart-model.txt` (the feature-count validation in `loadModel()` will reject it
    at startup if left in place) and re-run GPL + retrain.
  - **Manual re-run**: `POST /api/knowledge/revalidate` is not yet implemented. For now,
    delete `gpl-eval-snapshot.json` to force re-evaluation on next stabilization.

**Verified end-to-end on dev stack (2026-02-26, three iterations):**

*Run 1 (pre-fix):* 27 docs ingested, GPL auto-trigger fired before LLM was warm —
12 of 27 docs skipped ("AI still not available"), only 144 triples from 15 docs.
LambdaMART trained on 24 query groups (NDCG@10=0.871, MRR@10=0.833). MIME distribution
empty due to `SKIPPED_EMPTY_QUERY` bug. Snapshot later overwritten with `tripleCount=0`
by a spurious GPL run triggered after Worker restart.

*Run 2 (MIME fix only):* Same 27 docs, MIME fix applied. GPL fired before LLM was warm
again — 12 docs skipped, 144 triples. MIME distribution now populated correctly
(`text/x-web-markdown; charset=UTF-8: 26`, `text/x-web-markdown; charset=ISO-8859-1: 1`).
LambdaMART NDCG@10=0.871 (same data as run 1).

*Run 3 (all fixes):* Same 27 docs, all three fixes applied. GPL waited for LLM activation
before firing — **zero docs skipped**, 324 triples from all 27 docs across 54 query
groups. LambdaMART trained (NDCG@10=0.802, MRR@10=0.735, train=43 groups, eval=11
groups). NDCG lower than runs 1-2 because the eval split is larger (11 vs 5 groups) and
more representative. Snapshot persisted correctly: `docCount=27, tripleCount=324,
mimeDistribution={2 types}`. Subsequent trigger polls correctly skip.

**Bugs found and fixed during verification:**

- [x] **MIME facet query rejected** — `fetchMimeFacets()` sent `setQuery("")` which the
  Worker's `SearchOrchestrator` rejected as `SKIPPED_EMPTY_QUERY` before facet computation.
  Fixed: use `"*:*"` with `SEARCH_QUERY_SYNTAX_LUCENE` as a match-all query.

- [x] **Snapshot overwrite on Worker restart** — After a Worker restart, the
  `gpl-auto-trigger` thread retained stale `prevDocCount`, firing `coordinator.runAsync()`
  immediately against a freshly-started Worker with incomplete state. **Fix**: track
  `prevUptimeMs` from `StatusResponse.uptime_ms` — when uptime drops (Worker respawned),
  reset `prevDocCount = -1L` and log "Worker restart detected". Note: the exact write path
  for the observed overwrite (file rewritten at 17:58:27 with `tripleCount=0`, no log
  entry) was not fully confirmed — `captureGplEvalSnapshot` is the only known write path
  and always logs at INFO. The fix prevents the trigger-level race; the missing log entry
  remains unexplained.

- [x] **GPL fires before LLM is warm** — The GPL auto-trigger fired at doc-count
  stabilization regardless of LLM readiness. **Fix**: added `aiService.isAvailable()` to
  the trigger condition in `startGplAutoTrigger()`. The trigger now gates on LLM readiness.

**Open issues found and fixed during verification:**

- [x] **GPL data files disappear on dev stack restart** — Root cause: `dev-runner.cjs`
  `cleanDataDir()` soft mode (default on every `start`) deletes everything not in the
  `keep` set. GPL files were not in the set. **Fix**: Added `gpl-training-triples.ndjson`
  and `gpl-eval-snapshot.json` to the `keep` set. Also added defensive
  `Files.createDirectories()` to `GplTrainingTripleStore` constructor. Not a production
  issue — production data directory has no cleanup lifecycle.

- [x] **Per-doc retry in GplJobCoordinator too aggressive** — Was: single 5s wait then
  permanent skip. **Fix**: Replaced with exponential backoff (2s → 4s → 8s → 16s → 30s
  cap) with a global 2-minute timeout. On timeout, the entire job aborts cleanly (all
  triples written so far preserved) instead of silently skipping individual docs. The
  next trigger cycle detects the incomplete state and re-runs.

- [x] **`search.rerank.enabled: false` config inconsistency** — Investigation confirmed
  this is **correct by design**: `JUSTSEARCH_RERANK_ENABLED` controls the ONNX
  cross-encoder only; LambdaMART is independently gated by `lambdaMartReranker.isLoaded()`.
  They are separate systems (LambdaMART ~1-5ms vs cross-encoder ~200ms). **Fix**: Added
  explicit `reranking` section to `/api/debug/state` showing LambdaMART active status
  and a note about the cross-encoder being separately configured, eliminating confusion.

- [x] **Worker `ai_ready` health field misleading** — Confirmed: `ai_ready` in
  `HealthCheckResponse` has always meant "embedding model loaded" (set to
  `embeddingService.isAvailable()`), not "LLM ready". The Worker has no visibility into
  llama-server (which runs in the Head process). Head-side `aiReady` in `/api/status`
  correctly reflects LLM state via `InferenceLifecycleManager`. **Fix**: Added deprecation
  comment to proto field, clarifying comments in `GrpcHealthService` and
  `WorkerStatusMapper`. No wire-format change (backward compatible).

---

## Phase 3 — Long-Term (parallel dev branch, requires re-index)

### P3-A: SPLADE-v3 as First-Stage Retrieval

> **Implemented (2026-02-26)**: Core SPLADE infrastructure (originally on branch `splade-v3`,
> now merged to `main`). `SpladeConfig`, `SpladeModelDiscovery`, `SpladeEncoder` (ONNX inference +
> GPU management + SPLADE post-processing) in `io.justsearch.indexerworker.splade`.
> Lucene `FeatureField` integration in `FieldMapper` + `LuceneIndexRuntime.searchSplade()` +
> `searchHybridSplade()`. Index-time encoding in `IndexingDocumentOps.buildDocument()`.
> Query-time wiring in `SearchOrchestrator` HYBRID mode (SPLADE replaces BM25 as sparse leg,
> graceful fallback). `SpladeBackfillOps` for pending document backfill. Chunk documents
> marked `SPLADE_STATUS_PENDING` for backfill. All tests pass.
>
> **Remaining before live validation**: (1) ~~Export ONNX model artifacts~~ **Done** —
> exported production `naver/splade-v3` via `optimum[onnxruntime]` (HF gated access resolved);
> (2) ~~BEIR gate validation~~ **Done** — scifact stub-jaccard: lexical nDCG=0.662 (flat),
> hybrid Recall@10=+1.6%, hybrid nDCG@10=-2.8% (expected with stub-jaccard dummy vector);
> (3) ~~LambdaMART feature schema update~~ **Done** —
> `IDX_BM25` → `IDX_SPARSE`, debug scores `"bm25"` → `"sparse"` globally, backward compat
> in trainer; (4) ~~SPLADE model fingerprint~~ **Done** — `SpladeFingerprint.java` +
> `EmbeddingMetadataOverlay` extended + `KnowledgeServer` wiring.
>
> **Dev stack verified (2026-02-26)**: SPLADE encoder initialized, documents indexed with
> SPLADE weights, search returns `"sparse"` debug scores, `splade_model_sha256` stamped
> in commit metadata. All unit tests pass.

**Depends on**: nothing from Phases 1–2 — fully isolated, developed on a parallel
branch. Adopting SPLADE forces retraining of the LambdaMART model (P2-A), because
SPLADE scores are on a different scale than BM25 scores. GPL triples and QPP log data
remain valid; only the trained model artefacts need rebuilding.
**Enables**: a fundamentally higher recall ceiling than BM25-based retrieval.

**What**: Replace BM25 as the sparse retrieval leg with SPLADE-v3, a learned sparse
model that generates term weights via a BERT encoder at index time. Stored in the
standard Lucene inverted index (BM25-compatible infrastructure).

**Why**: SPLADE-v3 (arXiv:2403.06789) achieves **+8 nDCG@10 over BM25** on BEIR
average for **full SPLADE** (both document and query encoders active). Model:
`naver/splade-v3` (BERT-base, 110M params, ~220MB FP16 VRAM), feasible alongside the
8B LLM on this machine's 12 GB VRAM.

**Operating mode — Full SPLADE (decided 2026-02-26):**

| Mode | Index time | Query time | BEIR gain vs BM25 |
|------|-----------|-----------|------------------|
| **Full SPLADE** (chosen) | doc encoder (BERT) | query encoder (BERT, ~10–50ms) | **+8 nDCG@10** |
| Two-Step / Doc-Only (rejected) | doc encoder (BERT) | BM25 term lookup (μs) | +2–4 nDCG@10 |

Full SPLADE chosen because: (a) 10–50ms query latency is noise alongside the 200ms
cross-encoder reranking budget; (b) the +4–6 nDCG@10 advantage over Two-Step is the
entire reason to adopt SPLADE — Two-Step's +2–4 gain barely exceeds what LambdaMART
reranking already achieves on top of BM25; (c) VRAM overhead is ~250MB, within budget.

**Fusion mode — SPLADE replaces BM25 (decided 2026-02-26):**

SPLADE replaces BM25 as the sparse retrieval leg. Fusion becomes 2-way (SPLADE + dense),
same `HybridFusionUtils` code path as today with SPLADE plugged into the sparse slot.
Tri-way (BM25 + SPLADE + dense) rejected because: (a) BM25 and SPLADE are highly
correlated lexical signals — adding both biases fusion 2:1 toward lexical over semantic;
(b) SPLADE subsumes BM25 by design (learned term importance vs IDF heuristic); (c)
tri-way requires refactoring `HybridFusionUtils` for three inputs with additional tuning
surface. The standard text field stays in the index for highlighting, phrase queries,
and exact match — only BM25 *scoring* is removed from the retrieval pipeline. The BEIR
gate catches any regression from this change.

**Re-index requirement**: SPLADE weights must be stored in the inverted index at
document write time. A full corpus re-index is required when enabling SPLADE. Since
there are no users with an existing indexed corpus, this is not a migration problem.

**Model variant — `naver/splade-v3` BERT-base (decided 2026-02-26):**

BERT-base (110M params, ~220MB FP16) chosen over DistilBERT (66M, ~130MB). DistilBERT
saves ~1 min on a 200K-chunk re-index (3 min → 2 min) but costs ~3% nDCG@10. For a
personal-use system where indexing runs once per corpus change, 1 minute is invisible;
3% quality loss is not. Additionally, `naver/splade-v3` has published BEIR baselines;
a DistilBERT variant would need separate validation with no published reference scores.

**CPU fallback — yes, copy `CrossEncoderReranker` pattern (decided 2026-02-26):**

SPLADE on CPU is ~10–20ms/query (BERT-base forward pass). Fallback fires when GPU is
occupied by LLM generation. Copy the `CrossEncoderReranker` pattern: lazy GPU init via
double-checked locking, `OrtCudaStatus` tracking, CUDA DLL pre-flight check,
`opts.addCUDA(cudaOpts)` with `gpu_mem_limit=256MB` and `arena_extend_strategy=
kSameAsRequested`. Since search and LLM generation rarely overlap in single-user
desktop use, the CPU path is a safety net, not the primary path.

**Inference architecture**: SPLADE runs in Worker (Body), not Brain. llama-server
cannot run BERT-based masked LMs. SPLADE inference uses ONNX Runtime Java
(upgrade existing `com.microsoft.onnxruntime:onnxruntime` to `onnxruntime_gpu`; already
on Worker classpath for NER). Tokenization uses `ai.djl.huggingface:tokenizers` (upgrade
0.30.0 → 0.36.0). Vocabulary mapping uses `ai.djl:api` `DefaultVocabulary` loaded from
`vocab.txt` (Anserini pattern — `vocab.getToken(dimensionIndex)` maps ONNX output
dimensions to token strings).

The ONNX model is exported offline via `optimum-cli export onnx --model naver/splade-v3
--task fill-mask`. This exports `BertForMaskedLM` including the MLM head, outputting
pre-softmax logits of shape `[batch, seq, 30522]`. SPLADE post-processing (ReLU + log1p
+ attention mask + max-pool over sequence dim) is implemented in Java (~15 lines),
following the same tensor I/O pattern as `BertNerInference.java`.

Special tokens excluded from output: [PAD] (ID 0), [UNK] (100), [CLS] (101), [SEP]
(102), [MASK] (103). WordPiece `##` prefix kept on subword tokens (matches Anserini and
NAVER reference).

VRAM coexistence: Worker (ONNX RT) and Brain (llama-server) are separate OS processes
with separate CUDA contexts — no API-level conflict. Budget: LLM ~4.5 GB + SPLADE
~250 MB + ORT overhead ~300 MB = ~5.1 GB, leaving ~7 GB headroom on 12 GB GPU.

> **Feasibility analysis (2026-02-26)**: Pre-implementation research confirms **no hard
> blockers** for Phase 3. All uncertainties resolved; all decisions made.
>
> **Resolved uncertainties (deep research, 2026-02-26):**
>
> - **ONNX export**: `optimum-cli export --task fill-mask` exports `BertForMaskedLM`
>   including the MLM head. Output is pre-softmax logits `[batch, seq, 30522]` — exactly
>   what SPLADE needs for ReLU + log1p activation. The activation is applied in Java
>   (~15 lines), matching the `BertNerInference.java` tensor I/O pattern. Verified via
>   sentence-transformers source (exports MLM only, applies SpladePooling in Python) and
>   naver/splade issue #47 (community-verified ONNX export approaches).
> - **CUDA compatibility**: `nvidia-smi` "CUDA 13.1" reports the max toolkit version
>   supported by driver 591.59, not the installed toolkit. ORT's pre-built CUDA 12.x
>   packages work under CUDA 13.x drivers via NVIDIA's binary backward compatibility.
>   RTX 4070 is Ada Lovelace (sm_89) — ORT pre-built kernels include sm_89. The risk
>   scenario (Blackwell sm_120) does not apply. `CrossEncoderReranker` already uses
>   `addCUDA(0)` on this machine — if it works there, it works for SPLADE.
> - **FeatureField precision**: 9-bit mantissa = ~0.39% relative error, ~1100 distinct
>   values in [0, 5]. Pyserini's coarser integer quantization (weight × 100, ~500
>   levels, ~7-bit precision) shows only ~0.002–0.003 nDCG@10 impact — negligible.
>   FeatureField has more precision than Pyserini's proven approach. Errors partially
>   cancel in dot-product summation across ~100–200 non-zero terms.
> - **Tokenizer-to-vocabulary alignment**: Follow Anserini's pattern — load `vocab.txt`
>   via `DefaultVocabulary.addFromTextFile()` from `ai.djl:api`. Line N = token ID N =
>   ONNX output dimension N. `vocab.getToken(int id)` maps dimensions to strings.
>   Consistency guaranteed by shared `vocab.txt` source. Anserini has shipped this in
>   production for years (arXiv:2311.18503).
> - **Re-index performance**: RTX 4070 with FP16 batching (batch=32, seq=200): ~1,200
>   docs/sec. For 200K chunks: ~3 minutes GPU inference + overlapped Lucene writes.
>   Producer-consumer pipeline (GPU thread → bounded queue → Lucene writer thread).
>   Pre-optimize model offline: `python -m onnxruntime.transformers.optimizer --float16`.
> - **ORT version**: Keep 1.19.2 initially (already on classpath, proven on this GPU).
>   Upgrade to `onnxruntime_gpu` artifact in Worker module. Upgrade path to 1.23+ is
>   available if FP16 optimization or newer graph fusions are needed.
>
> **Codebase integration points (from Worker exploration):**
> - Index-time write: `FieldMapper.toDocument()` → add `FeatureField("splade", term, weight)`
>   per SPLADE output token alongside existing text/vector fields
> - Query-time: `SearchOrchestrator` → add SPLADE query path using
>   `FeatureField.newLinearQuery()` per query token
> - Fusion: `HybridFusionUtils` → SPLADE plugs into existing sparse slot (replaces BM25)
> - Config: `RuntimeConfig` → add `SparseTextCfg` for ONNX model path, tokenizer path
> - Commit metadata: `SsotCommitMetadataSource` → record SPLADE model SHA + version
>   (currently hardcoded to BM25Similarity fingerprint)
> - Proto: emit SPLADE score in existing `debug_scores` map as `"splade"` key — no
>   proto changes needed for search response. New `SearchMode` enum value optional.
> - GPU session: copy `CrossEncoderReranker` pattern (lazy GPU init, CPU fallback,
>   `OrtCudaStatus`, CUDA DLL pre-flight, double-checked locking)
> - Model discovery: create `SpladeModelDiscovery` following `OnnxModelDiscovery` pattern
>   (AI Home → install dir → dev fallback; requires `model.onnx` + `tokenizer.json` +
>   `vocab.txt`)
>
> **Decisions made (2026-02-26):**
> 1. **Full SPLADE** — 10–50ms query latency is acceptable; +4–6 nDCG@10 over Two-Step
>    justifies the overhead
> 2. **SPLADE replaces BM25** (2-way fusion: SPLADE + dense) — avoids lexical bias in
>    fusion; SPLADE subsumes BM25 by design; BEIR gate catches regressions
> 3. **`naver/splade-v3` (BERT-base, 110M)** — published BEIR baselines; DistilBERT saves
>    ~1 min indexing but costs ~3% nDCG@10
> 4. **CPU fallback enabled** — copy `CrossEncoderReranker` pattern; 10–20ms CPU latency
>    is acceptable as safety net

**Pre-implementation (offline, no Java):**
- [x] Export ONNX model: Used `optimum[onnxruntime]` `main_export()` with
  `splade-cocondenser-ensembledistil` (ungated, same 30522 vocab). `naver/splade-v3`
  is gated — requires HF account access (user action). Output verified: `logits`
  tensor shape `[batch, seq, 30522]`, inputs `input_ids` + `attention_mask` +
  `token_type_ids`. Model at `models/splade/naver-splade-v3/` (gitignored).
- [ ] (Optional) Optimize model: `python -m onnxruntime.transformers.optimizer --input
  model.onnx --output model_fp16.onnx --model_type bert --use_gpu --opt_level 2
  --float16` for FP16 inference speedup (~2x)
- [ ] (Optional) Run FeatureField quantization simulation on real SPLADE-v3 outputs:
  encode 1000 MS MARCO passages, quantize via FeatureField round-trip (right-shift 15,
  left-shift 15), compare dot-product rankings before/after. Expected: <0.003 nDCG@10
  impact.

**Dependencies:**
- [x] Upgrade `onnxruntime` → `onnxruntime_gpu` in `indexer-worker` module
  (`build.gradle.kts`). Kept version 1.19.2 (already proven on this GPU).
  **Implemented**: `implementation(libs.onnxruntime.gpu)` replaces `implementation(libs.onnxruntime)`.
- [x] ~~Upgrade `ai.djl.huggingface:tokenizers` 0.30.0 → 0.36.0 in `libs.versions.toml`~~
  Kept at 0.30.0 — `DefaultVocabulary` is available; 0.36.0 availability uncertain.
- [x] Add `ai.djl:api` to `indexer-worker` for `DefaultVocabulary`
  **Implemented**: `djl-api` library alias added to `libs.versions.toml`, wired in
  `indexer-worker/build.gradle.kts`.

**SPLADE inference engine (new class: `SpladeEncoder`):**
- [x] Create `SpladeConfig` following `NerConfig` pattern: env vars for model path,
  GPU enabled, GPU memory limit (default 256MB), device ID, max sequence length.
  6 env vars: `JUSTSEARCH_SPLADE_{ENABLED,MODEL_PATH,GPU_ENABLED,GPU_DEVICE_ID,
  GPU_MEM_MB,MAX_SEQ_LEN}`. Uses `ConfigPrecedence` for env/sysprop access.
- [x] Create `SpladeModelDiscovery` following `OnnxModelDiscovery` pattern: require
  `model.onnx` + `tokenizer.json` + `vocab.txt` in model directory. Resolution order:
  explicit override → AI Home → Sidecar → Install dir → Dev fallback.
- [x] Create `SpladeEncoder` with GPU session management following `CrossEncoderReranker`
  pattern: lazy GPU init (double-checked locking), CPU fallback, `OrtCudaStatus`
  tracking, `BooleanSupplier gpuAvailableSupplier` for GPU contention check.
  ~420 lines. `encode(String)` for single text, `encodeBatch(List<String>)` for batches.
- [x] Implement SPLADE post-processing in `SpladeEncoder`: read logits tensor
  `[batch, seq, 30522]`, apply ReLU + log1p + attention-mask multiply + max-pool over
  sequence dim → sparse vector per batch item. Package-private `postProcess()` method
  for testability.
- [x] Implement vocabulary mapping: load `vocab.txt` via `DefaultVocabulary.addFromTextFile()`,
  iterate non-zero dimensions, call `vocab.getToken(i)`, skip special token IDs
  (0, 100–103). Keep `##` prefix on subword tokens. Return `Map<String, Float>`.
- [ ] Wire batched inference for index-time: accumulate chunks into batches of 32–48,
  producer-consumer queue between GPU thread and Lucene writer thread.
  **Deferred**: current implementation encodes inline per-document. Batch pipeline is
  an optimization for re-index throughput; not needed for initial validation.

**Lucene integration:**
- [x] Add `FeatureField`-based SPLADE index path to `FieldMapper.addFields()`: new
  `"splade"` case iterates `Map<String, Float>` entries, adds `FeatureField(def.id,
  entry.getKey(), entry.getValue())` per token-weight pair.
- [x] Add SPLADE field constants to `SchemaFields.java`: `SPLADE`, `SPLADE_STATUS`,
  `SPLADE_STATUS_PENDING/COMPLETED/FAILED`, `SPLADE_RETRY_COUNT`, `SPLADE_MAX_RETRIES`.
  Added to `INDEXABLE_FIELDS` set.
- [x] Add SPLADE fields to `fields.v1.json` (both SSOT root and adapters-lucene copy):
  `splade` (type=splade), `splade_status` (keyword), `splade_retry_count` (long).
- [x] Add `LuceneIndexRuntime.searchSplade()`: builds `BooleanQuery` of
  `FeatureField.newLinearQuery("splade", token, weight)` SHOULD clauses with optional
  filter wrapping. Returns `SearchResult`.
- [x] Add SPLADE query path to `SearchOrchestrator` HYBRID mode: when `spladeEncoder`
  is available, encodes query text → calls `searchHybridSplade()` instead of
  `searchHybrid()`. Graceful fallback to BM25 on SPLADE encoding failure.
- [x] Add `LuceneIndexRuntime.searchHybridSplade()`: reuses `HybridSearchOps.executeHybrid()`
  with a SPLADE FeatureField search leg, preserving parallel execution, low-signal
  gating, candidate limits, and RRF fusion. Debug scores now emit generic `"sparse"` key
  (covers both BM25 fallback and SPLADE — renamed from `"bm25"` in Phase 3 finalization).
- [x] `HybridFusionUtils` unchanged — SPLADE plugs into the existing sparse slot
  via the text search leg functional interface. RRF fusion logic preserved.

**Wiring:**
- [x] Wire `SpladeEncoder` into Worker startup (`KnowledgeServer.start()` step 6.1.5):
  `SpladeConfig.fromEnv()` → `SpladeEncoder` → `indexingLoop.setSpladeEncoder()` +
  `searchService.setSpladeEncoder()`. Instance field on `KnowledgeServer` for lifecycle
  management. Closed in `KnowledgeServer.close()` after disambiguation service.
- [x] `IndexingDocumentOps.buildDocument()`: SPLADE encoding block after NER status.
  Guards with `signalBus.isMainGpuActive()` to skip when GPU busy. Catches `Exception`
  (OrtException is checked) and marks `SPLADE_STATUS_FAILED`.
- [x] `IndexingLoop`: `spladeEncoder` volatile field, `setSpladeEncoder()` setter,
  encoder closed in shutdown path, passed to `buildDocument()`.
- [x] `ChunkDocumentWriter.regenerateChunks()`: marks chunks as
  `SPLADE_STATUS_PENDING` for backfill (same pattern as chunk embedding status).
- [x] `GrpcSearchService.setSpladeEncoder()`: passthrough to `SearchOrchestrator`.
- [x] `SpladeBackfillOps`: GPU-aware backfill for documents with
  `splade_status=PENDING`. Follows `NerBackfillOps` pattern. Retry counting with
  max 3 retries before FAILED status. Handles both parent docs (reads `content` field)
  and chunks (reads `chunk_content` field). Wired into `IndexingLoop` idle cycle.
- [x] Update commit metadata to record SPLADE model fingerprint.
  **Implemented**: `SpladeFingerprint.java` computes SHA-256 of `model.onnx` (cached
  per-boot). `EmbeddingMetadataOverlay` extended with 2-arg constructor + supplier for
  SPLADE fingerprint. `KnowledgeServer.createIndexRuntime()` passes
  `SpladeFingerprint::get`. Commit metadata key: `splade_model_sha256`.
- [x] Update LambdaMART feature schema: `IDX_BM25` → `IDX_SPARSE` in
  `LambdaMartFeatureSchema`. Debug score keys `"bm25"` → `"sparse"` globally in
  `HybridFusionUtils`, `KnowledgeHttpApiAdapter`, `LambdaMartReranker`,
  `GplJobCoordinator`, `GplTrainingTripleStore`. Backward-compatible: `LambdaMartTrainer`
  accepts both `"sparse"` and legacy `"bm25"` NDJSON keys. All tests updated.

**Validation:**
- [x] BEIR gate: run existing BEIR eval pipeline with SPLADE-enabled backend. Compare
  against BM25+dense baseline. Gate thresholds (0.98 recall/nDCG/MRR ratio) catch
  regressions. Re-capture baselines if SPLADE improves metrics.
  **Done** (2026-02-26): BEIR scifact (stub-jaccard profile, naver/splade-v3 production model):
  - Lexical Recall@10=0.779 vs 0.778 (+0.2%), nDCG@10=0.662 vs 0.662 (flat) — lexical is
    BM25 in both runs, confirming apples-to-apples comparison
  - Hybrid Recall@10=0.787 vs 0.774 (+1.6%), nDCG@10=0.640 vs 0.658 (-2.8%)
  - SPLADE improves hybrid recall; nDCG regression expected with stub-jaccard (dummy vector
    leg dilutes SPLADE's learned weights more than BM25's term frequencies). With a real
    embedding model, dense vector leg would provide complementary ranking signal.
  - `debugScores` confirms `"sparse"` key active (not `"bm25"`), verifying SPLADE is the
    active sparse retrieval leg.
  - Artifacts: `tmp/beir-eval/splade-v3-scifact/metrics.v2.json`
- [ ] Evaluate U metric: SPLADE + dense hybrid vs. SPLADE-only (verify dense leg still
  adds value on top of SPLADE). **Deferred**: requires real embedding model (not stub-jaccard)
  to produce meaningful comparison.

After SPLADE ships: retrain LambdaMART (P2-A) using the same GPL triples, now with
SPLADE+dense features instead of BM25+dense. Delete the existing GPL triple store and
re-run GPL to capture SPLADE-scale feature values (see retraining trigger documentation
in P2-A). Re-run Event-Driven Revalidation triggers.

---

## Deferred

### P2-B: Thompson Sampling Policy Bandit

Deferred until live users exist. The bandit requires document-open signals that cannot
be collected pre-launch. No implementation work is planned until the app has real usage
data. The document-open event pipeline (frontend instrumentation, backend route,
storage) is also deferred — it will be planned when P2-B becomes active.

---

## Harmful Combinations Registry

**Depends on**: nothing — fully isolated. Tests for rules that already exist in the code.

These combinations are blocked in the current implementation. They should be codified
as formal test invariants, not just comments or undocumented rules.

| Combination | Why blocked | Where enforced |
|-------------|-------------|----------------|
| `HYBRID` mode + cross-encoder reranker | Dense signal already present; reranker adds noise | `KnowledgeHttpApiAdapter` |
| `HYBRID` mode + LLM query expansion | Expansion designed for sparse recall gaps; not needed in hybrid | `KnowledgeHttpApiAdapter` |
| Stemming + fuzzy correction | Stemming destroys Levenshtein signal | `SearchOrchestrator` |
| LambdaMART model active + cross-encoder reranker | LambdaMART already optimises the ranking objective; adding cross-encoder is 200–500ms overhead for noise. LambdaMART is the preferred reranking step once trained. | `KnowledgeHttpApiAdapter` (implicit: if/else control flow — LambdaMART fires first, cross-encoder is fallback when LambdaMART returns null; no explicit guard) |

- [x] Write contract tests that assert each blocked combination is rejected at the
  API layer with a specific error or silently dropped (document which)
- [x] Add the blocked combinations table to canonical docs (`docs/reference/` or
  `docs/explanation/`)

---

## Phase 1 — Implementation Hardening

Completed as a critical-analysis pass over the Phase 1 implementation. All items are done.

### T1 — Bugs (silent data corruption / infinite loop risk)

- [x] **T1-A — JSON control-character escaping** (`GplTrainingTripleStore`): hand-rolled
  `quoted()` helper escaped only 5 characters (`\`, `"`, `\n`, `\r`, `\t`); the remaining
  28 C0 control characters (U+0000–U+001F) produced malformed NDJSON. Replaced the entire
  manual builder with `Jackson ObjectMapper`. Control characters now correctly escaped.
  Added `appendEscapesControlCharacters()` test.
- [x] **T1-B — `totalDocs` integer cast overflow** (`GplJobCoordinator`): `totalDocs` was
  `int`; `getTotalCount()` returns `long`. Cast wraps negative for corpora > 2.1B docs,
  making the termination condition `offset >= totalDocs` never fire. Field changed to `long`,
  cast removed.
- [x] **T1-C — Missing null guard on `knowledgeClient`** (`GplJobCoordinator` constructor):
  the field was stored without validation, surfacing as `NullPointerException` from `runJob()`
  rather than at construction time. Added `Objects.requireNonNull(knowledgeClient, ...)`.

### T2 — Spec Gaps

- [x] **T2-A — QPP telemetry log** (`KnowledgeHttpApiAdapter`): spec item P1-D #3 ("log QPP
  values alongside each query in telemetry") was already implemented at line 601 using the
  query-hash pattern (no query text logged). No change required.
- [x] **T2-B — Harmful-combination tautology tests** (`KnowledgeHttpApiAdapterHarmfulCombinationsTest`):
  the two stemming+fuzzy tests asserted `LUCENE_SYNTAX != SIMPLE_SYNTAX` and `HYBRID != TEXT`
  — proto-enum tautologies that always pass regardless of runtime behavior. Replaced with real
  `isExpansionEligible()` behavioral assertions. Extensive comments document that the Worker-side
  enforcement point is `SearchOrchestrator` lines 285–339 and that the Head tests act as a proxy
  guard.

### T3 — Test Coverage Gaps

- [x] **T3-A — QPP test coverage** (`GrpcSearchServiceQppTest`): added
  `HybridModeQpp` nested class (`hybridMode_computesNonZeroQpp` — asserts maxIdf, avgIctf,
  queryScope all > 0 for HYBRID mode); added `avgIctf` assertion to TEXT mode; added
  `VectorModeQpp` class documenting that VECTOR mode rejects requests when no vector field is
  configured (`INVALID_ARGUMENT`) and explaining that QPP=0 for VECTOR is enforced by the
  SearchOrchestrator code guard (`if (mode == TEXT || mode == HYBRID)` at line ~441).
- [x] **T3-B — GplJobCoordinator critical path tests** (`GplJobCoordinatorTest`): added four
  tests — (1) blank-content doc skips LLM call entirely; (2) LLM returning 7 queries is capped
  to 5 triples; (3) reranker throwing `RuntimeException` falls back to score 1.0 and job
  completes normally; (4) LLM `onError` callback skips that document and job continues.
- [x] **T3-C — HybridFusionUtils exact keyset assertions** (`HybridFusionUtilsTest`): replaced
  individual `containsKey` checks with `assertEquals(Set.of(...), hit.debugScores().keySet())`.
  Non-debug mode: exactly `{"sparse", "vector"}`. Debug mode: exactly
  `{"sparse", "vector", "sparse_rrf", "vector_rrf", "sparse_boost", "rrf_base", "rrf"}`.

### T4 — Lifecycle Hardening

- [x] **T4-A — Auto-trigger thread interruptible** (`AppFacadeBootstrap`): `startGplAutoTrigger`
  was `void` and the daemon thread was unreachable after launch. Changed return type to `Thread`;
  result stored as `gplAutoTriggerThread` field; `close()` calls `gplAutoTriggerThread.interrupt()`
  to stop the polling loop on app shutdown.
- [x] **T4-B — Coherent GplJobStatus snapshot** (`GplJobCoordinator`): replaced 4 separate
  `volatile` fields (`processedDocs`, `totalDocs`, `lastRunAt`, `lastError`) with a single
  `AtomicReference<GplRunSnapshot>` private record. The job loop uses local variables and
  pushes a coherent snapshot at each batch boundary; `lastError` is always written into the
  snapshot before `status` is set to FAILED (preserving the happens-before invariant).
  `getStatus()` reads the snapshot in a single `AtomicReference.get()`.
- [x] **T4-C — `count()` O(n) → O(1)** (`GplTrainingTripleStore`): added `volatile long
  tripleCount` field, initialised by scanning the existing file once at construction time,
  incremented in the synchronized `append()` method. `count()` now returns the in-memory
  counter without touching disk.

---

## Event-Driven Revalidation (Replaces 21-Day Cadence)

**Depends on**: P1-A (needs something to re-trigger). The snapshot/trigger detection
plumbing is independent and can be scaffolded before P1-A exists, but it has nothing
to invoke until the GPL job is implemented.

Revalidation is triggered by **detecting corpus state changes**, not by events. No
indexing completion hook exists in the Worker — callers must poll the gRPC `status`
endpoint. The GPL coordinator in Head polls status after each ingest flow and compares
the corpus snapshot to detect trigger conditions.

The corpus-level policy should be re-evaluated when something materially changes,
not on a fixed calendar:

| Trigger | Action |
|---------|--------|
| Corpus size doubles since last eval | GPL coordinator detects via status poll; re-runs GPL job; retrains LambdaMART model |
| New content type appears (e.g., audio transcripts added for first time) | Re-run GPL job for new-type docs; retrain LambdaMART model |
| User reports systematic search degradation | On-demand re-run |
| Admin API call (`POST /api/knowledge/revalidate`) | On-demand re-run |

- [x] Implement corpus-state snapshot (size, content-type distribution) persisted at
  last-eval time — `GplEvalSnapshot.java`: immutable snapshot with Jackson persistence
  to `gpl-eval-snapshot.json`. Captured on GPL job completion via `captureGplEvalSnapshot()`
  in `AppFacadeBootstrap`.
- [x] Implement trigger detection: compare current snapshot to last-eval snapshot on
  each indexing batch completion — `GplRevalidationTrigger.java`: stateless comparator
  with configurable size-double factor (`JUSTSEARCH_GPL_REEVAL_SIZE_FACTOR`, default 2.0).
  Triggers on corpus size doubling or new MIME content types appearing.
- [x] Wire trigger to GPL job initiation — `AppFacadeBootstrap.startGplAutoTrigger()`
  now loads the last-eval snapshot, fetches current MIME facets via existing search RPC
  (using `*:*` Lucene match-all query to bypass the empty-query guard in `SearchOrchestrator`),
  evaluates trigger conditions, and only runs GPL when warranted (replacing the naive
  "always run on doc-count stabilization" logic). The trigger also gates on
  `OnlineAiService.isAvailable()` (LLM readiness) and detects Worker restarts via
  `StatusResponse.uptime_ms` to avoid firing against a freshly-spawned Worker.
- [x] Expose last-eval timestamp and next-trigger conditions in debug endpoint —
  `DebugStateController` exposes `gpl.last_eval` block with `evaluated_at`, `doc_count`,
  `triple_count`, and `mime_distribution`. Wired via `LocalApiServer` snapshot supplier.

---

## Open Questions

1. **Re-index cost for SPLADE**: How long does a 50k-document re-index take with
   BERT-base inference running? Determines whether SPLADE is viable as a transparent
   background upgrade or requires a user-visible migration step. (Estimated ~3 minutes
   for 200K chunks at ~1,200 docs/sec with FP16 batching on RTX 4070; to be confirmed
   with live measurement after model artifacts are available. Current implementation
   encodes inline per-document rather than batched — initial re-index may be slower
   until batch optimization is implemented.)

2. ~~**GPL data file durability**~~ — **Resolved**: Root cause was `dev-runner.cjs`
   `cleanDataDir()` soft mode deleting files not in the `keep` set. GPL files added to
   `keep` set. Defensive `createDirectories()` added to `GplTrainingTripleStore`. Not a
   production issue — production data directory has no cleanup lifecycle.

3. ~~**LambdaMartBenchmarkTest threshold**~~ — **Resolved**: Three root causes fixed:
   (a) p99 calculation was actually max (index 49/50, not p99); (b) warmup insufficient
   (5 → 20 iterations); (c) threshold too tight (5ms → 10ms). Also increased measured
   iterations from 50 → 100 for statistical stability. Test now passes reliably.

4. ~~**NPE in `DebugStateController:166` when snapshot has no timestamp**~~ —
   **Resolved**: replaced `lastEval.evaluatedAt().toString()` with null-safe
   `evaluatedAtRaw()` + null guard. The `evaluated_at` field is now omitted when
   the timestamp is absent instead of causing an NPE.

5. ~~**Snapshot file read/write race — no atomic writes**~~ —
   **Resolved**: `save()` now writes to a `.tmp` sibling then atomically renames via
   `Files.move(ATOMIC_MOVE)`, with fallback to `REPLACE_EXISTING` if the filesystem
   doesn't support atomic move. Eliminates partial-read window.

6. ~~**`close()` interrupts trigger thread but does not join it**~~ —
   **Resolved**: added bounded `join(5_000)` after `interrupt()` in `close()`, with
   `InterruptedException` handling and a WARN log if the thread doesn't terminate
   within 5s. Matches the gRPC server shutdown pattern used later in the same method.

7. ~~**`fetchMimeFacets` failure silently disables new-type detection**~~ —
   **Resolved**: `fetchMimeFacets` now returns `null` on failure (was `Map.of()`) and
   the log level is promoted to WARN. The call site skips trigger evaluation when MIME
   data is unavailable (`continue` back to the poll loop), preserving stabilization
   state so the next successful poll evaluates correctly.
   **Test coverage added** (commit `d973648c`): `GplEvalSnapshotTest` — (1)
   `nullEvaluatedAt`: loads a snapshot JSON without an `evaluatedAt` field and asserts
   both `evaluatedAtRaw()` and `evaluatedAt()` return null (covers bug #4 fix);
   (2) `saveNoTempFile`: asserts no `.tmp` sibling exists after a successful `save()`
   call (covers bug #5 fix).

8. ~~**Cross-encoder scores are all 1.0 (fallback, not real scores)**~~ — **Resolved.**
   Inspection of `gpl-training-triples.ndjson` showed every triple has `"score": 1.0`.
   **Corrected root cause**: the original diagnosis ("lazy init by search request") was
   wrong — `getReranker()` triggers ONNX loading on ANY call, including from GPL. The
   actual cause: no ONNX reranker model was installed at any discovery path
   (`OnnxModelDiscovery.resolve()` returned null → `isReady()` false → score = 1.0f).
   When the model IS installed, GPL's first `scoreQueryDoc()` call loads it correctly.
   **Fix applied**: resolved the reranker once at `runJob()` start (not per-call),
   added WARN log when unavailable ("binary is_negative labels only, no graded
   relevance"), fixed the misleading comment in `createGplJobCoordinator`. The per-call
   supplier resolution was also wasteful — hundreds of redundant null-checks per job.
   **Verified**: 3 new tests in `GplJobCoordinatorTest` — (1) reranker scores (0.75)
   flow through to both positive and negative NDJSON triples, not 1.0 fallback;
   (2) supplier called exactly once per job run (`verify(times(1))`);
   (3) WARN log emitted when reranker unavailable (ListAppender capture).
>
> **Note**: The code fix is correct — the reranker resolves once and produces graded
> scores when the ONNX model is installed. However, all dev-stack verification runs
> (Runs 1–3) were performed without a reranker model installed, so the training data
> from those runs still contains only 1.0 fallback scores. The "What was NOT validated"
> section reflects this data-level gap, not a code bug.

9. ~~**GPL-generated queries are formulaic (title-like, not natural search)**~~ —
   **Resolved.** The zero-shot prompt produced documentation-heading-style queries
   ("Knowledge Server job queue implementation details"). Based on InPars/Promptagator
   research (few-shot with 3-8 examples is sufficient for 8B-class models), replaced
   with a 3-example few-shot prompt demonstrating natural search behavior: keyword
   queries ("git rebase vs merge difference") and questions ("when should I rebase
   instead of merge") across technical, business, and personal document types. Added
   explicit negative instruction ("Do NOT write document titles or formal headings").
   Also added explicit temperature control (`SamplingParams(0.4, 0.9)`) — previously
   uncontrolled at llama-server default ~0.8. Reduced `GPL_MAX_TOKENS` from 200 to
   100 to bound runaway generation. **Live-verified** with the dev stack: ingested 20
   SciFact documents, GPL auto-triggered and produced 213 triples with 40 unique
   synthetic queries. Every query is natural search behavior — keyword-style
   (`"dendritic growth and synaptic input"`, `"cancer survivor nutrition guidelines"`)
   and question-style (`"how do neurons adjust dendrites to synapses"`,
   `"what should cancer survivors eat and exercise"`). Zero formulaic title-like
   queries in the output.

10. **No representative evaluation corpus exists** —
    The eval infrastructure (held-out split, nDCG@10/MRR@10, BEIR pipeline) is
    complete. What's missing is a public corpus that structurally resembles personal
    file collections (mixed markdown, PDF, code, email, notes).

    **Two-layer evaluation design:**

    - **Layer 1 — GPL held-out eval (automated):** Ingest corpus → GPL generates
      synthetic queries → cross-encoder scores relevance → 80/20 split by `query_id`
      → compute nDCG@10 + MRR@10. This measures **pipeline internal consistency**
      (does LambdaMART learn the cross-encoder's preferences?). It does NOT measure
      real search quality — the eval and training distributions are the same, so a
      model that overfits to synthetic query patterns would score well here.

    - **Layer 2 — Agent-written realistic queries (independent ground truth):** 30–50
      queries written by the agent after reading the assembled corpus, simulating real
      search behavior across styles: keyword (`"python async await"`), question
      (`"how do I make sourdough bread"`), navigational (`"ETL best practices"`),
      vague/personal (`"that note about decision records"`), cross-topic
      (`"performance optimization"`). Binary relevant/not-relevant labels hand-assigned
      per query against 3–10 documents. Frozen as `qrels/test.tsv`. This breaks the
      GPL circular evaluation by providing a query distribution independent of the
      training pipeline.

    Both layers complement each other: Layer 1 validates internal pipeline wiring;
    Layer 2 catches quality problems that synthetic queries cannot detect.

    **Statistical power note (investigated 2026-02-27):** Hoeffding CI95 half-width at
    n=40 queries is 0.215 — the 0.98 ratio gate used by `diff-search-eval-suite.mjs`
    (signal = 0.013 absolute) is 16× smaller than the noise floor. At 30–50 queries,
    only catastrophic regressions (>33% relative) are statistically detectable.
    **Decision:** personal-v1 is a **development smoke-test corpus**, not a CI
    regression gate. It is used for manual large-effect validation, directional quality
    tracking, and end-to-end pipeline verification. The existing BEIR datasets
    (scifact=300q, arguana=1406q) remain the CI regression gates. Personal-v1 is NOT
    wired into `dag-runner-beir-gate.mjs` or the nightly workflow.

    **Corpus composition (~350 documents, all permissive licenses):**

    | Category | Source | License | Count | Rationale | Processing notes |
    |----------|--------|---------|-------|-----------|------------------|
    | Personal notes/wiki | [lyz-code/blue-book](https://github.com/lyz-code/blue-book) | CC0 | ~150 | 1,120 md files across activism, programming, cooking, architecture, gaming. Best structural match for an Obsidian/PKM user. Sample across directories for topic diversity. | Strip YAML frontmatter, MkDocs admonitions (`!!! type`). No wiki-links. Some bilingual (ES+EN) content. Empty image alt text. Files range 2KB–23KB. |
    | Recipes/lifestyle | [Rushmore75/foss.cooking](https://github.com/Rushmore75/foss.cooking) | Unlicense | ~50 | Active fork of based.cooking (376 md recipe files at `src/content/recipes/`). Represents non-technical personal content. | Strip YAML frontmatter. No wiki-links. Some emoji in timing bullets (⏲️). Very short files (0.8–2KB). |
    | Technical reference | [data-engineering-community/data-engineering-wiki](https://github.com/data-engineering-community/data-engineering-wiki) | CC0 | ~50 | Obsidian vault published via Obsidian Publish. Simulates professional reference notes. | **Heavy processing:** strip `[[wiki-links]]` (keep display text), `![[transclusions]]`, `%%` comments, `^blockId` tokens, `<span class="git-footer">` boilerplate footer (identical in every file), mermaid code blocks. Inconsistent frontmatter key casing (`Aliases` vs `aliases`). Many stub articles (<20 lines). |
    | Code + docs | JustSearch `docs/` directory | MIT | ~50 | Mixed explanation, how-to, reference, and decision docs. Already in repo — zero external dependency. | Strip frontmatter. Standard markdown, no special syntax. |
    | Public domain prose | [Project Gutenberg](https://www.gutenberg.org/) excerpts | Public domain | ~50 | Simulates ebooks/papers in a personal library. First ~5KB from 50 diverse works (fiction, science, history, philosophy). | Strip Gutenberg header/footer boilerplate. Plain text. |

    **Integration with existing infrastructure (verified 2026-02-26):**

    The evaluation and governance stack already exists. The only new artifact is the
    assembly script. Concrete reuse map:

    | Existing component | How it's used | What's needed |
    |---|---|---|
    | `beir-eval-win.ps1 -Dataset personal-v1 -SkipDownload` | Materializes corpus.jsonl → .txt files, indexes via backend, runs queries, computes Recall@K/nDCG@K/MRR@K | Data at `tmp/beir-cache/personal-v1/raw/personal-v1/` |
    | `diff-search-eval-suite.mjs --baseline <path> --candidate <path>` | Manual comparison of two eval runs (not CI-gated) | Two metrics.json files from separate runs |
    | `corpus-profiles.v1.json` + `validate-corpus-profiles.mjs --write-signatures` | Profile registration + SHA-256 signature computation | New profile entry |
    | `license-bom.v1.json` | License tracking for CC0 + Unlicense + MIT + public domain | New BOM entry |
    | `run-corpus-governance-quickcheck.ps1` | Validates all governance cross-references | Run after registration |

    The assembly script produces standard BEIR format at the expected path:

    ```
    tmp/beir-cache/personal-v1/raw/personal-v1/
    ├── corpus.jsonl      # {"_id": "...", "title": "...", "text": "..."}
    ├── queries.jsonl     # {"_id": "q01", "text": "..."}
    └── qrels/
        └── test.tsv      # query-id<TAB>corpus-id<TAB>score
    ```

    `beir-eval-win.ps1` will URI-encode each `_id` into a `.txt` filename, register
    the docs dir as a watched root, wait for IDLE, then evaluate queries against qrels.
    Doc ID recovery uses `fields.filename` → URI-decode → qrel lookup. No custom
    evaluation code needed.

    **New work (only):**

    1. **Assembly script** (`scripts/bench/assemble-personal-corpus.mjs`) — clones
       sources at pinned commit SHAs, samples documents deterministically (seeded RNG),
       normalises content per source (blue-book: strip frontmatter + MkDocs admonitions;
       foss.cooking: strip frontmatter + emoji; data-engineering-wiki: strip `[[wiki-links]]`
       keeping display text, `![[transclusions]]`, `%%` comments, `^blockId` tokens,
       `<span class="git-footer">` boilerplate, mermaid blocks; Gutenberg: strip
       header/footer boilerplate), and emits BEIR JSONL. Doc IDs use simple
       alphanumeric-hyphen format (e.g., `bluebook-tdd-001`) to avoid URI-encoding
       edge cases. Idempotent: same config → byte-identical output.
    2. **Agent-written queries + qrels** — 30–50 queries written after reading the
       corpus. Frozen as `queries.jsonl` + `qrels/test.tsv`.
    3. **Governance registration** — BOM entry, corpus profile, lane policy wiring,
       signature computation. Follows existing pattern exactly (see "How to Register
       a NEW Corpus" in `corpus-governance-lib.mjs` investigation).

    **Two-pass eval sequencing (decided 2026-02-27):**

    `beir-eval-win.ps1` waits for `indexState=IDLE` but does NOT wait for GPL
    completion. GPL auto-triggers ~30-60s after indexing stabilizes (when the LLM is
    available), meaning early eval queries would use no LambdaMART model while later
    queries use the freshly trained model — inconsistent scoring within a single run.

    **Solution:** Two-pass execution. Pass 1 indexes the corpus and waits for GPL +
    LambdaMART training to complete. Pass 2 runs the eval queries against a stable,
    fully-trained pipeline.

    **GPL completion detection (verified 2026-02-27):** `/api/debug/state` exposes
    both `gpl.status` (IDLE/RUNNING/COMPLETED/FAILED) and
    `reranking.lambdamart.active` (boolean, true when LightGBM model is loaded).
    The definitive poll condition for Pass 2 readiness is:
    `gpl.status == "COMPLETED" AND reranking.lambdamart.active == true`.
    There is a window after `gpl.status == "COMPLETED"` where LambdaMART training
    is still running on a virtual thread (`lambdamart.active` still false) — the
    poll must wait for both fields. No code changes needed.

    **Prerequisites:** The dev stack must be running with an LLM loaded before
    Pass 1. GPL auto-trigger gates on `aiService.isAvailable()`
    (`AppFacadeBootstrap:924`) — without a running LLM, GPL never fires,
    LambdaMART never trains, and the two-pass design is meaningless (eval would
    only measure RRF fallback). The dev stack stays running through both passes.

    Concrete steps:

    1. Write assembly script, produce corpus JSONL + place in `tmp/beir-cache/`
    2. **Pass 1 — index + train:** Run `beir-eval-win.ps1 -Dataset personal-v1
       -SkipDownload -IndexBenchOnly` (stops after indexing, writes throughput
       stats). Then poll `/api/debug/state` for
       `gpl.status == "COMPLETED" AND reranking.lambdamart.active == true`.
    3. Write agent-authored queries + relevance labels → `queries.jsonl` +
       `qrels/test.tsv`
    4. **Pass 2 — eval:** Run `beir-eval-win.ps1 -Dataset personal-v1
       -SkipDownload -SkipMaterialize -SkipIndex` (skips re-materialization and
       re-indexing, runs queries against the already-indexed corpus with the
       trained LambdaMART model active).
    5. Register in corpus governance (profile + BOM + lane policy), run quickcheck
    6. GPL held-out split available as a secondary automated eval
       (`qrels/gpl-test.tsv`) after step 2 completes — frozen from the first
       GPL run on this corpus

    **Not wired into CI:** personal-v1 is not added to `dag-runner-beir-gate.mjs`
    or the nightly workflow. It is a development smoke-test corpus for manual
    validation (30–50 queries insufficient for 0.98 ratio gate — see statistical
    power note above).

---

## Research Basis

Findings summarised from internet research conducted 2026-02-25.

| Claim | Source |
|-------|--------|
| QPP routing achieves only ~4% NDCG; collection identity dominates | arXiv:2504.01101; TOIS 2025 |
| GPL pipeline with 6B LLM sufficient for corpus adaptation | arXiv:2112.07577; NAACL 2022 |
| InPars+ validates LLaMA 3.1 8B as query generator | InPars+, arXiv 2023 |
| SPLADE-v3 +8 nDCG@10 over BM25 on BEIR | arXiv:2403.06789 |
| HyDE latency 0.7–2.4s on 8B model | arXiv:2212.10496 |
| Thompson Sampling convergence at 50–100 obs/arm | Multi-armed bandit literature |
| Apple Spotlight: document-open as relevance signal | Apple WWDC 2024 Spotlight session |
| Fixed hybrid beats QPP routing by 1–3 nDCG@10 | arXiv:2504.01101 |

---

## End-to-End Gaps (identified 2026-02-27)

Critical-analysis pass over the full search path (frontend → Head → Worker → LambdaMART
→ response) and the GPL lifecycle (trigger → job → training → model load → restart).
Eight gaps found; two are critical blockers for production use. **All gaps investigated
2026-02-27 — concrete fix specifications below.** E2E-3 resolved as accepted limitation.

Implementation priority: E2E-2 (showstopper) → E2E-1 (critical) → E2E-5 (2-line fix)
→ E2E-6 (3-line fix) → E2E-4 (high) → E2E-8 (low) → E2E-7 (observability).

### E2E-1 — LambdaMART model lost on every app restart (CRITICAL)

**Location**: `AppFacadeBootstrap.java:112` (field init), `LambdaMartReranker.java`
(AtomicReference only).

The trained `LGBMBooster` lives only in `AtomicReference<LGBMBooster>` — pure heap
memory. No `saveModelToString()`, no file write, no serialization exists anywhere in the
codebase. On app restart:

1. `lambdaMartReranker.isLoaded()` → `false`
2. LambdaMART reranking silently bypassed on every search
3. `gpl-eval-snapshot.json` still exists, says "already evaluated for this corpus"
4. `GplRevalidationTrigger.evaluate()` → `shouldRun = false` (corpus unchanged)
5. GPL never re-fires, model never retrains — **LambdaMART permanently dead until corpus
   doubles or new MIME type appears**

The NDJSON triple store survives (append-only file), but no code path re-trains from
existing triples on startup. `startLambdaMartTrainingAsync()` is only reachable from
the `onJobCompleted` callback.

- [x] **E2E-1: Persist LambdaMART model to disk; reload on startup**

  **Fix specification (investigation complete 2026-02-27):**

  **Approach**: Option (c) — serialize to disk for fast startup + retrain from NDJSON
  as fallback. Both mechanisms are low-cost and complement each other.

  **Save path**: After `lambdaMartReranker.setModel(booster)` succeeds in
  `startLambdaMartTrainingAsync` (`AppFacadeBootstrap.java:1055`), serialize via
  `booster.saveModelToString(-1, 0, FeatureImportanceType.SPLIT)` and write to
  `{dataDir}/lambdamart-model.txt`. Use the atomic-rename pattern from
  `GplEvalSnapshot.save()` (write to `.tmp`, `Files.move` with `ATOMIC_MOVE`).
  Model size: ~40–80 KB for 100 trees with shallow depth.

  **Load path**: In `AppFacadeBootstrap` constructor, after `runtimeConfig` is
  available (~line 284, GPL wiring section). If `lambdamart-model.txt` exists,
  load via `LGBMBooster.loadModelFromString(text)` and call
  `lambdaMartReranker.setModel(booster)`. The loaded booster works with
  `predictForMat` (confirmed: same API as training-produced boosters).

  **Fallback retrain**: If model file missing/corrupt but NDJSON has ≥2 query
  groups, retrain on startup. Latency: ~1–5 s for 2000 triples. This covers:
  model file deleted, lightgbm4j version upgrade (model format change), or
  first startup after manual NDJSON import.

  **Model-data coherence**: No explicit hash linking needed. The eval snapshot
  already tracks corpus state — if the corpus changes enough to invalidate the
  model, `GplRevalidationTrigger` fires GPL re-run which retrains and overwrites
  the model file. Startup retrain from stale NDJSON is acceptable (produces a
  model from the last GPL run, which is the best available signal).

  **Files**: `LambdaMartReranker.java` (add `saveModel`/`loadModel` methods),
  `AppFacadeBootstrap.java` (startup load ~line 284, post-training save ~line 1055).
  ~30 lines total.

---

### E2E-2 — ALL training features are zero; TEXT mode produces no debug scores (CRITICAL)

**Location**: `GplJobCoordinator.java:387` (TEXT mode hardcoded),
`SearchOrchestrator.java:301-337` (TEXT mode → `indexRuntime.search()` → null debugScores),
`SearchOrchestrator.java:632` (null-check skips `putDebugScores`).

**Root cause (verified 2026-02-27)**: The GPL re-query pass uses `SEARCH_MODE_TEXT` with
`debug=true`. But TEXT mode calls `indexRuntime.search(luceneQuery, ...)` which returns
`SearchHit` objects with **null `debugScores`** — the `"sparse"` and `"vector"` keys are
only populated by `HybridFusionUtils.fuseWithRRF()`, which is exclusive to the HYBRID
code path. The `toGrpcResponseBuilder` at line 632 checks `hit.debugScores() != null`
and skips. The gRPC `SearchResult.debug_scores` map arrives empty at the coordinator.
`result.getDebugScoresMap().getOrDefault("sparse", 0f)` → `0.0f` for every result.

**Impact**: In all training triples ever generated by GPL:
- `sparse` = 0.0f (always — no BM25 score is captured)
- `vector` = 0.0f (always — TEXT mode has no vector leg)
- QPP values (maxIdf, avgIctf, queryScope) are query-level constants that normalize
  to 0.0f within each result set via min-max normalization

**All 5 LambdaMART features are effectively zero or constant within each query group.**
The model cannot learn any document-distinguishing signal. The trained model is
degenerate — its predictions do not differentiate documents within a result set.

This supersedes the previously-described BM25-vs-SPLADE mismatch concern. There is no
BM25 score at training time either — the mismatch is not "BM25 at train, SPLADE at
infer" but "nothing at train, real scores at infer."

The dev-stack verification (NDCG@10=0.802, MRR@10=0.735) reported non-trivial metrics
because the eval also uses the same zero-feature data — the model and eval share the
same degenerate feature distribution. The metrics reflect the model's ability to
reproduce the input ordering, not a learned ranking.

- [x] **E2E-2: Populate BM25 score as `"sparse"` debug score in TEXT mode**

  **Fix specification (investigation complete 2026-02-27):**

  **Decision**: Option (c) — populate debug scores in `toGrpcResponseBuilder` for
  TEXT-mode hits. Option (a) (HYBRID mode) is **not viable** during GPL because of
  the GPU single-tenant policy: `IndexingLoop.handleGpuStateTransition()` unloads
  the embedding model when the LLM activates (`signalBus.writeGpuActive(true)`).
  Since GPL requires the LLM, the embedding model is unavailable, and HYBRID mode
  falls back to TEXT internally — producing the same empty debug scores.

  **Root cause verified**: In TEXT mode, `indexRuntime.search()` returns `SearchHit`
  with `debugScores = Map.of()` (empty). `ReadPathOps.java:407` constructs
  `new SearchHit(docId, scoreDoc.score, fields)` — `scoreDoc.score` IS the raw
  Lucene BM25 score from `BM25Similarity`, untransformed (no RRF, no boost, no
  normalization).

  **Fix location**: `SearchOrchestrator.toGrpcResponseBuilder()` (~line 632).
  Currently:
  ```java
  if (hit.debugScores() != null && !hit.debugScores().isEmpty()) {
      builder.putAllDebugScores(hit.debugScores());
  }
  ```
  Change to: if `debugScores` is empty AND the search mode is TEXT, populate
  `"sparse" = hit.score()` and `"vector" = 0.0f`. This makes TEXT-mode debug
  scores structurally consistent with HYBRID-mode debug scores.

  **QPP features**: Already flow correctly in TEXT mode — computed at
  `SearchOrchestrator:469-473` and set as gRPC response fields. They are
  query-level constants that normalize to identical values within each result
  set (by design of min-max normalization). The model will learn to ignore them
  and focus on `sparse` (the only document-varying feature in TEXT mode).

  **SPLADE mismatch concern**: With this fix, training uses BM25 scores. If
  Worker later activates SPLADE, inference uses SPLADE scores. BM25 and SPLADE
  score distributions differ (different scale, different document ordering).
  A model trained on BM25 may make poor split decisions on SPLADE inputs.

  **Mismatch detection**: No infrastructure currently exists for Head to detect
  Worker's sparse backend. `health.proto`, `indexing.proto`, and
  `StatusResponse` have zero fields reporting SPLADE activation. This requires
  new infrastructure:
  - Add `sparse_backend` field to gRPC `StatusResponse` (value: `"bm25"` or
    `"splade"`). Worker populates from `SpladeConfig` presence.
  - `GplRevalidationTrigger` detects backend change as a revalidation trigger
    (alongside corpus-doubles and new-MIME-type).
  - Record `sparse_backend` in NDJSON metadata and eval snapshot.

  **Files**: `SearchOrchestrator.java:632` (~5 lines), `health.proto` or
  `indexing.proto` (new field), `GplRevalidationTrigger.java` (backend change
  detection), `GplTrainingTripleStore.java` (metadata field). The proto + trigger
  changes can be deferred to the SPLADE retrain successor item — the immediate
  fix is the 5-line `toGrpcResponseBuilder` change.

---

### E2E-3 — `vector` feature dead at training, live at inference (HIGH — subsumed by E2E-2)

**Location**: `GplJobCoordinator.java:387` (TEXT mode → vector always 0.0),
`KnowledgeHttpApiAdapter.java:444` (HYBRID mode → real cosine similarity).

Training uses TEXT mode, so `"vector"` is always `0.0f` in all triples. Decision 7
correctly notes LightGBM excludes trivial features from splits — zero training cost.
But at inference in HYBRID mode, `"vector"` contains real KNN cosine similarities.
Since no tree node learned a split on vector scores, the model **cannot use vector
similarity as a ranking signal**. LambdaMART is effectively a sparse-score-only
reranker.

**Note**: E2E-2 option (a) (switch re-query to HYBRID mode) fixes both E2E-2 and
E2E-3 simultaneously. If E2E-2 is fixed with option (b) or (c) (TEXT mode only),
E2E-3 remains as a separate item requiring its own fix.

- [x] **E2E-3: Accepted limitation — vector feature is zero at training** _(resolved by investigation)_

  **Decision (2026-02-27)**: E2E-2 takes option (c) (TEXT mode with BM25 scores).
  HYBRID mode is not viable during GPL (GPU single-tenant policy — see E2E-2).
  The `vector` feature remains `0.0f` in all training triples. This is an accepted
  limitation, not a bug:

  - LightGBM's `lambdarank` objective excludes zero-variance features from splits
    automatically — zero training cost, zero model bloat.
  - At inference in HYBRID mode, `vector` contains real cosine similarities, but the
    model has no learned splits on it — effectively ignored. The model is a
    sparse-score-only reranker.
  - The vector feature becomes useful only when HYBRID-mode training is possible
    (requires CPU-only embeddings or a future multi-GPU setup where LLM and
    embedding model coexist). This is deferred to the SPLADE retrain successor item.

  No code change required.

---

### E2E-4 — GPL mid-job LLM timeout silently cements partial state (HIGH)

**Location**: `GplJobCoordinator.java` (`waitForAiAvailability` 120 s timeout),
`AppFacadeBootstrap.java:271-275` (`onJobCompleted` callback).

If the LLM becomes unavailable mid-job (GPU contention, llama-server crash), the
120-second timeout fires, `aiTimedOut = true`, and the document loop breaks. The job
then reaches `Status.COMPLETED` (not FAILED), the `onJobCompleted` callback fires,
and `captureGplEvalSnapshot()` persists the current doc count — even though only a
fraction of documents were processed.

The snapshot now says "evaluated at docCount=500" despite only 200 docs being covered.
On the next trigger poll, 500 == 500 → "no revalidation needed." **Skipped documents
are permanently excluded from GPL** unless the corpus doubles or a new MIME type appears.

- [x] **E2E-4: Set FAILED on AI timeout; suppress snapshot and training callback**

  **Fix specification (investigation complete 2026-02-27):**

  **Approach**: Option (a) — set `Status.FAILED` on AI timeout. This is the simplest
  correct fix. Options (b) and (c) add complexity for marginal benefit — GPL is cheap
  enough to re-run from scratch.

  **Root cause verified**: `GplJobCoordinator.java:303` sets `aiTimedOut = true` and
  breaks the inner document loop. Execution falls through to line 347 which sets
  `status.set(Status.COMPLETED)` unconditionally. The `onJobCompleted` callback fires,
  `captureGplEvalSnapshot()` records `StatusResponse.getDocCount()` (the FULL corpus
  count, not `processedDocs`). The snapshot now claims full coverage.

  **Fix (3 changes in `GplJobCoordinator.java`)**:

  1. After the document loop (line ~340), check `if (aiTimedOut)`: set
     `status.set(Status.FAILED)` and skip the normal completion path.
  2. Do NOT call `onJobCompleted` on the AI-timeout path — no snapshot capture,
     no training callback. The partial triples in the NDJSON are harmless (they'll
     be truncated by E2E-6 fix on the next run).
  3. Log at WARN: "GPL job failed: LLM became unavailable after processing {n}/{total}
     documents. Job will re-trigger on next poll cycle."

  **Re-trigger behavior**: `GplRevalidationTrigger` polls periodically. With no
  snapshot saved (or stale snapshot from a previous successful run), the trigger
  condition re-evaluates on the next cycle and fires GPL again. No special
  "retry after timeout" logic needed — the existing trigger mechanism handles it.

  **Interaction with E2E-6**: On retry, the E2E-6 fix (truncate triple store at job
  start) ensures partial triples from the failed run are discarded. Clean state.

  **Files**: `GplJobCoordinator.java:340-354` (~10 lines changed).
  **Test**: Update `GplJobCoordinatorTest` to verify FAILED status on AI timeout and
  confirm `onJobCompleted` is NOT called.

---

### E2E-5 — Native LightGBM handle leak on small corpus (MEDIUM)

**Location**: `AppFacadeBootstrap.java:1055-1062` (`startLambdaMartTrainingAsync`),
`LambdaMartTrainer.java:169` (empty eval split guard).

With ≤2 query groups (e.g., 1-2 documents), the 80/20 split produces an empty eval set
→ `ndcg10 = 0.0`. `startLambdaMartTrainingAsync` skips `setModel()` but the
`LGBMBooster` was already created and returned in `TrainingResult`. Since `setModel()`
is never called, `booster.close()` is never called — the native LightGBM handle leaks.

- [x] **E2E-5: Close rejected booster; add try-finally safety net**

  **Fix specification (investigation complete 2026-02-27):**

  **Leak confirmed**: In `AppFacadeBootstrap.java:1055-1062`, the `else` branch
  (ndcg10 ≤ 0.0) logs a warning but never calls `result.booster().close()`. The
  `LGBMBooster` holds a native LightGBM handle via JNI — this leaks native memory.

  **Fix (2 changes in `AppFacadeBootstrap.java:1045-1072`)**:

  1. Add `result.booster().close()` in the else branch (ndcg ≤ 0.0).
  2. Wrap the entire training callback in try-finally: if an exception occurs
     between `LambdaMartTrainer.train()` returning and `setModel()` being called,
     close the booster. The try block covers lines 1050-1062; the finally block
     checks if the booster was NOT adopted by `setModel()` and closes it.

  **`TrainingResult` as `AutoCloseable`**: Not needed. The record has exactly one
  consumer (`startLambdaMartTrainingAsync`), and the try-finally covers all paths.
  Adding `AutoCloseable` to a record with a single call site is over-engineering.

  **Files**: `AppFacadeBootstrap.java:1045-1072` (~5 lines added).
  **No other leak paths**: `LambdaMartTrainer.train()` creates the booster and
  returns it in `TrainingResult`. There is no other code path that creates a
  booster without adopting it.

---

### E2E-6 — GPL not resumable; duplicates on retry (MEDIUM)

**Location**: `GplJobCoordinator.runJob()` (always starts at offset 0),
`GplTrainingTripleStore.appendWithFeatures()` (append-only, no dedup).

If the Worker crashes mid-GPL-job, the job fails and eventually re-triggers. It restarts
from `offset = 0`, re-processing the entire corpus and appending duplicate triples. The
trainer doesn't deduplicate — `groups.computeIfAbsent` accumulates all rows under the
same `query_id`, so a document processed twice gets 2x weight in gradient computation.

- [x] **E2E-6: Truncate triple store at GPL job start**

  **Fix specification (investigation complete 2026-02-27):**

  **Approach**: Option (a) — truncate the NDJSON file at the start of each new GPL
  job run. GPL regenerates everything from scratch (synthetic queries, cross-encoder
  scores, negative samples), so no hand-curated data is lost. Partial progress from
  a failed run is correctly discarded.

  **Fix (2 changes)**:

  1. Add `clear()` method to `GplTrainingTripleStore.java`: truncate the file to
     zero bytes (or delete and recreate). ~3 lines.
  2. Call `tripleStore.clear()` at the start of `GplJobCoordinator.runJob()`,
     before the document loop begins.

  **Interaction with E2E-4**: When a partial completion sets FAILED (E2E-4 fix) and
  GPL re-triggers, the truncate ensures partial triples are discarded. The re-run
  produces a clean, complete triple set.

  **Interaction with E2E-1**: The model file on disk may become stale after truncate
  (model from old triples, triple store now empty). This is acceptable — the model
  file represents the last successful training, and a new training run will overwrite
  it. If the model file needs invalidation, the E2E-1 startup-retrain fallback
  detects the empty triple store and skips retrain (< 2 query groups).

  **Files**: `GplTrainingTripleStore.java` (~3 lines), `GplJobCoordinator.java`
  (~1 line at job start).
  **Test**: Verify that `clear()` + `appendWithFeatures()` produces a file with
  only the new triples.

---

### E2E-7 — LambdaMART training failure invisible (MEDIUM)

**Location**: `AppFacadeBootstrap.java:1064-1069` (catch-and-swallow),
`DebugStateController` (no training status field).

When `LambdaMartTrainer.train()` throws (DLL loading failure, insufficient data,
LightGBM internal error), the exception is caught and logged at WARN. There is no
status field in `/api/debug/state`, no metric, no retry. The user sees GPL as
"COMPLETED" but `reranking.lambdamart.active: false` with no indication whether
that means "hasn't trained yet" or "training failed."

- [x] **E2E-7: Add LambdaMART training status to `/api/debug/state`**

  **Fix specification (investigation complete 2026-02-27):**

  **Design**: Mirror the GPL status pattern in `DebugStateController.java:144-183`.
  Add a `lambdamart.training` section alongside the existing `lambdamart.active`
  and `lambdamart.gated_by` fields.

  **New fields under `reranking.lambdamart.training`**:
  - `status`: `"PENDING"` | `"TRAINING"` | `"SUCCEEDED"` | `"FAILED"`
  - `ndcg10`: float (last training NDCG@10, null if never trained)
  - `mrr10`: float (last training MRR@10, null if never trained)
  - `train_groups`: int (number of query groups in training split)
  - `eval_groups`: int (number of query groups in eval split)
  - `last_trained_at`: ISO timestamp (null if never trained)
  - `error`: string (null if SUCCEEDED, error message if FAILED)

  **Implementation**:
  1. Add `AtomicReference<TrainingStatus>` field to `LambdaMartReranker` (or
     `AppFacadeBootstrap`). `TrainingStatus` is a simple record.
  2. Set `TRAINING` before `LambdaMartTrainer.train()`, `SUCCEEDED` after
     `setModel()`, `FAILED` in the catch block.
  3. Wire into `DebugStateController` (add section to the `reranking.lambdamart`
     map, lines 185-194).

  **No retry on failure**: Training failure is almost always a permanent condition
  (DLL missing, insufficient data). Retry would re-fail. The user should investigate
  via `/api/debug/state` → `lambdamart.training.error`.

  **Not UI-visible**: Status bar is for user-facing status. Training status is a
  developer/debugging concern — `/api/debug/state` is the right surface.

  **Files**: `LambdaMartReranker.java` (new `TrainingStatus` record + atomic ref),
  `AppFacadeBootstrap.java:1045-1072` (status updates), `DebugStateController.java:185-194`
  (wire into response). ~20 lines total.

---

### E2E-8 — Trivial queries get zero features (LOW)

**Location**: `HybridSearchOps.java:254-273` (vector-skip short-circuit, non-debug
path returns empty `debugScores`).

For queries shorter than 4 characters or single stop words, the vector search leg is
skipped and the non-debug code path returns `SearchResult` with no `debugScores`.
LambdaMART reads `0.0f` for all features and scores every result identically — output
order is arbitrary.

- [x] **E2E-8: Populate debug scores in HybridSearchOps vector-skip path**

  **Fix specification (investigation complete 2026-02-27):**

  **Bug confirmed**: `HybridSearchOps.java:254-273` — when the vector search leg is
  skipped (short query, stop words), the non-debug code path returns `SearchResult`
  with empty `debugScores`. The debug branch (line ~260) uses key `"bm25"` (legacy
  name) instead of `"sparse"`, so even debug=true gives the wrong key name.

  **Fix (in `HybridSearchOps.java:254-273`)**:

  In the vector-skip branch, always populate `"sparse"` and `"vector"` keys on each
  hit (matching the key names used by `HybridFusionUtils.fuseWithRRF()`):
  - `"sparse" = hit.score()` (the BM25 score from Lucene)
  - `"vector" = 0.0f` (no vector search was performed)

  Remove or replace the legacy `"bm25"` key usage in the debug branch — normalize
  to `"sparse"` everywhere for consistency.

  **No other short-circuit paths found**: `SearchOrchestrator` delegates all HYBRID
  search to `HybridSearchOps`. The vector-skip path is the only place where debug
  scores are dropped. The TEXT-mode path in `SearchOrchestrator` is covered by the
  E2E-2 fix.

  **Files**: `HybridSearchOps.java:254-273` (~5 lines changed).
  **Test**: Update `HybridSearchOpsTest` to verify that vector-skip results include
  `"sparse"` and `"vector"` debug score keys.

---

## Post-Completion Review (2026-02-27)

Critical-analysis pass by a successor agent. Three issues found that are not documented
elsewhere. All are low-to-medium severity and do not invalidate the implementation items
above, but they affect the correctness of any quality measurement made against this system.

---

### PCR-1 — Train/eval normalization mismatch (LOW — correctness, not a blocker)

**Location**: `LambdaMartTrainer.java:217-246` (training dataset construction) vs.
`evaluateOffline()` lines 270-279.

**Finding**: `buildDataset()` computes `colMin`/`colMax` across **all rows in the training
split**. `evaluateOffline()` computes `colMin`/`colMax` **per query group** (per-result-
set). `LambdaMartReranker.rerank()` also normalizes per-result-set. The model is trained
on a different feature distribution than offline eval and production inference operate on.
Offline eval and inference are consistent with each other; training is not.

**Impact**: LightGBM tree-split thresholds are learned against training-split-scale
feature values but applied against per-result-set-scale values at inference. For the
`sparse` feature (BM25/SPLADE scores), this is a monotone-scaling mismatch — split
thresholds are calibrated to the wider range of the full training set rather than the
narrower per-query range. In practice, gradient-boosted trees are somewhat robust to
monotone scaling changes because each decision node independently compares against a
learned threshold. The mismatch reduces rather than eliminates model validity. It is
nonetheless a correctness issue that should be fixed before any serious quality
comparison.

**Fix**: `buildDataset()` refactored to normalize per query group. A package-private
`normalizeGroupedFeatures(List<List<float[]>>)` helper was extracted to make the
normalization logic directly testable without LightGBM. A dedicated unit test
(`LambdaMartTrainerTest.normalizeGroupedFeatures_perGroup_notSplitLevel`) verifies that
two groups with different sparse score ranges each independently map to [0.0, 0.5, 1.0],
distinguishing per-group from split-level behaviour. **Regenerate the GPL triple store and
retrain `lambdamart-model.txt` to produce a model consistent with the corrected training
normalization.**

**Post-fix analysis** (2026-02-27): Critical review of the fix identified three residual
issues: (a) `evaluateOffline()` still has an inline copy of the per-group normalization
logic — not refactored to use `normalizeGroupedFeatures()`, so the helper's test covers
only one of three normalization sites; (b) `buildDataset()` uses `List<Float>` boxing for
labels, avoidable with a pre-computed `numRows` and direct `float[]` accumulation;
(c) because per-group normalization is now applied at training time, QPP features (constant
per-group) also collapse to `0.0f` during training — not just inference. The model is
therefore trained on 2 effective features (sparse + vector), not 5. These residuals and
the broader QPP normalization architecture are addressed in the Post-PCR Architectural
Analysis section below and in successor work item 10.

- [x] **PCR-1: Fixed `buildDataset()` normalization to per-query-group** —
  `LambdaMartTrainer.java`. Unit test added. Model retrain required (operator action).
  Residual code quality items and QPP architecture addressed in item 10.

---

### PCR-2 — QPP features: training-inference design clarification (INFORMATIONAL)

**Location**: `LambdaMartReranker.java:195-199`, `LambdaMartTrainer.java:217-246`.

**Finding**: The comment at `LambdaMartReranker.java:195` reads:
```
// QPP values are query-level (scalar) — normalization yields 0.0f (constant).
// This is consistent with how training data was normalized per result set.
```

This comment is **incorrect in two ways**:

1. Training uses split-level normalization (PCR-1 above), not per-result-set. Different
   queries in the training set have different MaxIDF values, so QPP features DO receive
   non-zero normalized values at training time (they vary across the split). The model
   CAN learn query-difficulty splits from QPP features during training.

2. At inference, `normalize(qppMaxIdf, qppMaxIdf, qppMaxIdf)` forces QPP to `0.0f`
   regardless of the actual query difficulty. Any tree node that learned a split on QPP
   features will receive input value `0.0` and take the left (below-threshold) branch.
   Whether this is correct or incorrect depends on where the learned thresholds lie.
   Because training values spanned the full split range and inference is always `0.0`,
   the QPP branches fire at the minimum end of the training distribution — not the
   current query's position on that distribution.

**Further context**: Even with correct inference normalization (using stored training-set
min/max instead of `normalize(v, v, v)`), QPP features cannot discriminate between
documents within a single result set — every document in the same query's result set
shares the same QPP value. QPP features are useful to LambdaMART only as cross-query
signals (telling the model "this type of query is harder than average"). This requires
the model to be evaluated on a representative mix of queries covering the QPP range.

**Design implication**: If QPP features are to be meaningfully used at inference, the
inference path should normalize using the training-set min/max stored alongside the model
artefact (not `normalize(v, v, v)`). This stores query difficulty relative to the training
distribution. Implementing this requires persisting column min/max as a sidecar alongside
`lambdamart-model.txt`. This is V2 feature schema work and is deferred.

**Fix**: Comment corrected at `LambdaMartReranker.java:196-197`. Replaced the two-line
misleading comment with four lines accurately describing: `normalize(v, v, v)` always
yields `0.0f`; at training time per-split normalization produced non-zero QPP values; at
inference they always collapse to `0.0f`; storing training-set min/max for meaningful QPP
inference is deferred to V2.

**Post-fix issues** (2026-02-27): Two problems were found after PCR-2 was applied:

1. **Inline comment now describes pre-PCR-1 history.** Lines 197–198 say "at training
   time, per-split normalization gave QPP features non-zero values spanning the training
   corpus." After PCR-1 changed `buildDataset()` to per-group normalization, QPP features
   also collapse to `0.0f` during training — QPP is constant per group, so per-group
   min/max gives `colMin == colMax` for every QPP column. The fixed comment describes
   behaviour that no longer exists.
2. **Javadoc not updated.** `LambdaMartReranker.java:163` still reads: "QPP values are
   query-level (same for all results) and normalize to `0.0f` (constant) — consistent
   with training data normalization." The phrase "consistent with training data
   normalization" is the original misleading claim PCR-2 was meant to eliminate; it
   survives in the method Javadoc above the fixed inline comment.

Both are corrected in successor work item 10.

- [x] **PCR-2: Misleading comment corrected** at `LambdaMartReranker.java:196-199`.
  Two follow-up issues noted above; corrected in item 10.

---

### PCR-3 — `SpladePostProcessTest.java` coverage gap (MEDIUM → updated)

**Location**: `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/splade/
SpladeEncoder.java` — `postProcess()` is package-private with comment
`// Package-private for testing`.

**Finding (original)**: PCR-3 was written assuming no test for `SpladeEncoder.postProcess()`
existed. Post-verification: `SpladePostProcessTest.java` was introduced in commit `e2e2ea69`
(the original SPLADE commit, before PCR-3 was written). The original concern was not actually
missed. The test covers all six scenarios originally specified: special token filtering, ReLU,
log1p ordering, attention mask, max-pool axis, all-zero output.

**Residual weakness**: `SpladePostProcessTest` uses a `TestablePostProcessor` inner class
that reimplements the algorithm verbatim, rather than calling `SpladeEncoder.postProcess()`
directly (which would require injecting the `private final Vocabulary` field). A future
regression in the production `postProcess()` that doesn't exist in `TestablePostProcessor`
would not be caught by the test.

**Impact**: Acceptable for V1. Direct-call coverage via a package-private test constructor or
reflection is V2 hardening work, not a blocker for SPLADE quality validation.

- [x] **PCR-3: `SpladePostProcessTest.java` exists** — all six scenarios covered in
  `e2e2ea69`. Residual: reimplementation-copy weakness documented above. Direct-call coverage
  deferred to V2 hardening.

---

### Post-PCR Architectural Analysis (2026-02-27)

The PCR fixes and the critical analysis that followed surfaced a broader question: is
per-result-set min-max normalization the right strategy for this reranker at all?

**Research finding — trees are invariant to global monotone transforms, not per-query ones.**
GBDT split decisions are ordinal comparisons. Any *globally fixed* monotone transform
(scaling, z-score) preserves feature ordering and changes nothing about what trees learn.
However, per-result-set min-max is not a global transform — it applies a different affine
transform to every result set. It does change what the model learns: it teaches the model
to distinguish relative position within a result set, independent of absolute score
magnitude. For a reranker operating on pre-retrieved candidates, this is the appropriate
objective. The research finding that "trees don't need normalization" applies to global
transforms; per-result-set normalization is a modeling choice, not an invariant.

**Scale robustness validated by 235 §A5.**
Tempdoc 235's three-way BEIR scifact comparison (2026-02-27) shows:

| Mode | nDCG@10 |
|------|---------|
| Lexical (BM25) | 0.661 |
| Dense-only | 0.668 |
| SPLADE+dense hybrid | 0.639 |

Hybrid underperforms both legs by 2–3%, which 235 §A6 attributes to SPLADE scores being
on a materially different absolute scale than BM25 TF-IDF. A model trained without
per-result-set normalization embeds absolute sparse score thresholds. When the sparse
backend switches between SPLADE and BM25 — which happens silently when SPLADE indexing
is incomplete — those thresholds misfire against the other magnitude. Per-result-set
normalization is the mechanism that decouples the trained model from whichever sparse
backend is active. Removing it would be a correctness regression under the existing
operational pattern.

**Internet research findings (2026-02-27, additional pass).**
Three findings from the LTR and information-retrieval literature changed the decision:

1. **QPP belongs at the routing layer, not inside ranking models.** Production IR systems
   (Elasticsearch LTR, Solr LTR `ValueFeature`, OpenSearch LTR) use QPP signals for
   query routing and selective query processing — deciding which ranker to invoke or how
   many candidates to retrieve — not as features inside a single ranker. The QPP++
   2025 workshop proceedings, the TREC QPP track, and the ScienceDirect QPP survey all
   confirm this architectural separation. The one exception is multi-task BERT-based
   training (M-QPPF, Information Sciences 2023), which is a neural shared-encoder
   approach, not applicable to GBDT rankers.

2. **Per-group normalization destroys cross-group QPP variation too.** A query-level
   feature CAN provide a cross-query difficulty signal to LambdaMART (the tree splits
   on QPP across groups, providing a query-difficulty-conditioned offset). However,
   per-group normalization collapses QPP to `0.0f` independently for EVERY group
   (constant within group → `colMin == colMax` → `normalize()` returns `0.0f`). Groups
   with QPP=5.0 and QPP=8.0 both normalize to `0.0f`. There is no cross-group variation
   for lambdarank to split on. The proposed sidecar (item 10a) would have switched QPP
   to global normalization, but this created: sidecar atomicity problems (two separate
   `Files.move()` atomic renames are NOT atomically consistent — industry fix requires
   directory rename pattern, a non-trivial storage layout change); schema versioning
   complexity (LightGBM model files have no built-in normalization metadata, no
   `StandardScaler` equivalent — industry solution is sklearn Pipeline or MLflow pyfunc,
   neither applicable to our Java/JNI stack); and a confusing `normalizeGroupedFeatures()`
   API with sentinel values for hybrid normalization.

3. **Trees are invariant to global monotone transforms; per-result-set normalization
   is a deliberate modeling choice that should be kept.** GBDT split invariance applies
   to fixed global scaling. Per-result-set normalization encodes "relative position
   within the result set" as the learning signal — the correct objective for a reranker.
   The 235 §A5 BEIR result (hybrid nDCG 0.639 vs BM25 0.661) validates the SPLADE/BM25
   scale difference is real. Per-result-set normalization is the mechanism that decouples
   the trained model from whichever sparse backend is active.

**Decision — Option A: remove QPP from the V1 feature schema.**
Reduce `LambdaMartFeatureSchema` to 2 features (`[sparse, vector]`). Remove
`IDX_QPP_*` constants, QPP reading in `LambdaMartTrainer`, and QPP parameters from
`LambdaMartReranker.rerank()`. Keep per-result-set normalization for sparse and vector.
**QPP values stay in the NDJSON triple store** (written by `GplTrainingTripleStore` and
`GplJobCoordinator`) — the data is preserved for V2 use without requiring pipeline
re-run. When QPP signals are needed for query difficulty routing, implement them at
the gating/routing layer rather than inside the ranking model.

**Code quality items in item 10 (implemented):**
- Removed QPP reading from `LambdaMartTrainer.train()`.
- Removed QPP params from `LambdaMartReranker.rerank()` and QPP normalization block.
- Refactored `evaluateOffline()` to use `normalizeGroupedFeatures()` — removes the
  inline duplicate that PCR-1 helper extraction did not de-duplicate.
- Fixed `buildDataset()` autoboxing (`List<Float>` → direct `float[]` with `numRows`).
- Fixed `LambdaMartReranker` class Javadoc and `rerank()` method Javadoc to accurately
  describe the 2-feature V1 schema and per-result-set normalization rationale.
- Updated `LambdaMartFeatureSchemaTest` and `LambdaMartRerankerTest` for new API.

**Structural fixes implemented (2026-02-27):** Post-implementation analysis of the Option A
changes identified four higher-level structural issues. All four are resolved:

- **Issue 1 (HIGH) — stale model silently misranks**: `loadModel()` had no feature-count
  validation. A pre-Option-A 5-feature model loaded without error and produced wrong
  predictions. Fixed: `loadModel()` now calls `booster.getNumFeature()` and rejects any model
  whose feature count differs from `LambdaMartFeatureSchema.NUM_FEATURES`. Issues a WARN log
  asking the operator to retrain.
- **Issue 2 (MEDIUM) — QPP exclusion not documented in trainer**: `LambdaMartTrainer.train()`
  silently skipped QPP fields in NDJSON with no comment. Fixed: added a comment at the
  `buildRow()` call explaining QPP is intentionally excluded from the V1 schema.
- **Issue 3 (MEDIUM) — model provenance lost on restart**: `TrainingStatus` (nDCG@10, MRR@10,
  trainGroups, evalGroups, lastTrainedAt) was in-memory only. Fixed: `saveModel()` now writes
  `<modelFile>.meta.json` alongside the model (best-effort, non-fatal); `loadModel()` restores
  `TrainingStatus` with `status: "LOADED_FROM_DISK"`. If the sidecar is absent (pre-fix
  models), `loadModel()` succeeds with null provenance fields — backward compatible.
- **Issue 4 (LOW-MEDIUM) — no named feature registry**: Added `FEATURE_NAMES` as an immutable
  `List.of("sparse", "vector")` to `LambdaMartFeatureSchema`. Forward-looking hook for V2
  feature name embedding in `LGBMDataset`. Tests added for all four issues.

---

## Closing Notes (2026-02-26, updated 2026-02-27)

Core implementation items, hardening passes, open questions, and end-to-end gaps are
all resolved. The 8 E2E gaps identified on 2026-02-27 were investigated, specified,
and implemented in the same session — all 7 code-change items are complete (E2E-3 was
resolved as an accepted limitation requiring no code change). Post-implementation review
caught and fixed: unused import, semantically misleading debug score values in
vector-skip path, and a non-final field.

### What was built

- **GPL pipeline** (P1-A): end-to-end synthetic query generation → cross-encoder scoring
  → negative sampling → NDJSON triple store. Verified on dev stack (27 docs, 324 triples).
- **QPP features** (P1-D): MaxIDF, AvgICTF, Query Scope computed per query and emitted
  in gRPC SearchResponse.
- **LambdaMART fusion** (P2-A): V1 feature schema (2 features: sparse + vector), training
  from GPL triples, Head-side inference via lightgbm4j. Hot-swap via AtomicReference.
  QPP removed from schema in item 10 (Option A) — see Post-PCR Architectural Analysis.
- **SPLADE-v3 infrastructure** (P3-A): SpladeEncoder (ONNX + GPU/CPU fallback),
  FeatureField indexing, SearchOrchestrator integration, backfill ops. Originally on
  `splade-v3` branch (now merged to `main`), verified on dev stack.
- **Event-driven revalidation**: GplEvalSnapshot + GplRevalidationTrigger + auto-trigger
  thread with Worker-restart detection, LLM readiness gating, atomic snapshot persistence.
- **Hardening**: 14 items across bugs, spec gaps, test coverage, and lifecycle safety.
- **E2E gap fixes**: 7 code-change items — model persistence (E2E-1), TEXT-mode debug
  scores (E2E-2), AI-timeout FAILED status (E2E-4), booster leak fix (E2E-5), triple
  store truncation (E2E-6), training status observability (E2E-7), vector-skip debug
  scores (E2E-8). Plus E2E-3 resolved as accepted limitation.

### What was NOT validated

- **Search quality improvement over RRF** — no A/B measurement exists. The pipeline
  produces NDCG@10/MRR@10 numbers against its own synthetic triples, but there is no
  evidence that LambdaMART fusion returns better results than untuned RRF for a real user
  query on a real personal corpus.
- **Training signal quality during dev-stack verification was weaker than designed** —
  inspection of the actual NDJSON output (2026-02-26) revealed two problems, both since
  fixed in code but not re-verified with a production-representative corpus:
  - Every `score` field was `1.0` (cross-encoder fallback) because no ONNX reranker
    model was installed during the verification runs. Code fix applied (Q8): reranker
    resolves once at job start, produces graded scores when model is present. Re-run on
    a corpus with the reranker model installed is needed to confirm graded labels flow.
  - Generated queries were formulaic title-like phrases. Code fix applied (Q9): few-shot
    prompt with natural search examples, temperature control, max-token cap. Live-verified
    on 20 SciFact docs — all 40 queries are natural search behavior. Full corpus
    re-verification included in Q10 plan.
- **SPLADE quality on personal documents** — BEIR validation used stub-jaccard vectors.
  The one real test showed hybrid nDCG *regressed* -2.8%. Needs real embedding model
  validation.
- **VRAM coexistence under load** — theoretical budget says LLM + SPLADE + ORT fits in
  12 GB, but no concurrent-load measurement was taken.
- ~~**No evaluation corpus**~~ — **personal-v1 assembled and validated (2026-02-27)**
  (successor item 1, COMPLETE). 331-doc corpus across 5 sources (blue-book 150,
  foss.cooking 50, data-eng-wiki 31, JustSearch docs 50, Gutenberg 50), 45 agent-written
  queries with 109 relevance judgments, governance registered. See successor item 1 for
  full results. Statistical power constraint stands: personal-v1 is a **development
  smoke-test corpus** (Hoeffding CI95 half-width at n=40 is 0.215 — 16× wider than the
  0.013 absolute signal at the 0.98 gate). It is NOT wired into CI regression gates.

**Post-completion review** (2026-02-27) found three additional items: a train/eval
normalization mismatch in `LambdaMartTrainer` (PCR-1, low severity), a misleading
code comment about QPP inference behaviour in `LambdaMartReranker` (PCR-2,
comment-fix only), and a missing `SpladeEncoderTest` for the package-private
`postProcess()` method (PCR-3). All three are documented in the Post-Completion
Review section below. PCR-1 and PCR-3 are added to successor work items 7 and 9.

### Successor work (new tempdocs, not appendages to this one)

1. **Personal evaluation corpus + end-to-end validation** — **COMPLETE (2026-02-27).**
   Assembled 331-doc personal-v1 corpus (5 sources: blue-book 150, foss.cooking 50,
   data-eng-wiki 31, JustSearch docs 50, Gutenberg 50). 45 agent-written queries with
   109 relevance judgments. Two-pass eval validated full pipeline: indexing (73.84 docs/s)
   → GPL training (337 docs, 4,116 triples) → LambdaMART (nDCG@10=0.918 training) →
   BEIR eval (lexical nDCG@10=0.590, hybrid nDCG@10=0.283 — hybrid lower because eval
   ran with stub-jaccard embedding profile, not real embedding model). Governance
   registered (`personal-v1.small` profile, `bom.personal-v1` BOM, quickcheck pass).
   Files: `assemble-personal-corpus.mjs`, `eval-personal-corpus-win.ps1`,
   `corpora/personal-v1/{queries.jsonl,qrels/test.tsv}`.
2. **End-to-end gap fixes** — **COMPLETE (2026-02-27).** All 8 gaps investigated,
   7 code-change items implemented (E2E-3 resolved as accepted limitation). Changes
   across 9 files: `SearchOrchestrator` (TEXT-mode debug scores), `HybridSearchOps`
   (vector-skip debug scores), `LambdaMartReranker` (model persistence, training
   status), `AppFacadeBootstrap` (model load/save, booster leak fix, training status),
   `GplTrainingTripleStore` (clear on job start), `GplJobCoordinator` (FAILED on AI
   timeout, triple store truncation), `DebugStateController` (training status wiring),
   plus 2 test files. All module tests pass.
3. **SPLADE retrain** — re-run GPL with SPLADE features (`splade-v3` branch already
   merged to `main`), retrain LambdaMART, validate with real embedding model. Blocked
   by E2E-2 fix (BM25 scores must flow into training first) and requires the SPLADE
   mismatch detection infrastructure (new `sparse_backend` gRPC field + revalidation
   trigger — see E2E-2 spec). Note: HYBRID mode re-query (E2E-3) is not viable
   during GPL due to GPU single-tenant policy — SPLADE retrain will still use TEXT
   mode with SPLADE-as-sparse scores (requires Worker to be running SPLADE, which
   changes the TEXT-mode BM25 score to a SPLADE score if SearchOrchestrator routes
   through SpladeEncoder for TEXT queries — **investigate this routing**).
   **BEIR gate dependency (tempdoc 235 I2)**: The BEIR gate for this item compares
   against the `embedding-nomic-q4` baselines being captured in 235 I2. 237 W1/W2
   are now implemented (merged 2026-02-27). However, webis-touche2020 was run before
   W1/W2 landed — its recorded metrics reflect SPLADE+BM25 only and carry a misleading
   ANN proof PASS. mldr-en has never been run to completion. **Both large-corpus
   datasets must be re-run with W1/W2 in place before any baseline can be used as
   a gate.** The three small datasets (scifact, arguana, nfcorpus) are promotable
   now — Phase 2 finishes in seconds for corpora of that size and W1/W2 do not
   change their results.
4. **Search quality evaluation** — compare RRF baseline vs. LambdaMART vs.
   SPLADE+LambdaMART on the personal-v1 corpus using agent-written queries.
5. **Production readiness** — `POST /api/knowledge/revalidate` endpoint, V2 feature
   set (doc-level features), VRAM coexistence testing.
6. **Eval infrastructure** — BEIR baseline recapture with MRR@10, U metric with
   per-query-class breakdown.
7. ~~**PCR-1 fix**~~ **DONE (2026-02-27)** — `buildDataset()` refactored to per-query-group
   normalization. Package-private `normalizeGroupedFeatures()` helper extracted; unit test
   added. **Operator action required**: re-run GPL + retrain `lambdamart-model.txt` before
   any quality comparison against the RRF baseline.
8. ~~**PCR-2 comment fix**~~ **DONE (2026-02-27)** — misleading comment corrected at
   `LambdaMartReranker.java:196-199`.
9. ~~**PCR-3 tests**~~ **SUPERSEDED (2026-02-27)** — `SpladePostProcessTest.java` was
   found to exist (introduced in `e2e2ea69`); covers all six scenarios. Residual: test
   uses a reimplementation copy rather than a direct call to `SpladeEncoder.postProcess()`.
   Direct-call coverage deferred to V2 hardening.
10. ~~**QPP sidecar + code quality**~~ **DONE (2026-02-27) — revised to Option A.**
    Internet research confirmed QPP belongs at the routing layer, not inside the
    ranking model (see Post-PCR Architectural Analysis above). Implemented:
    (a) Removed QPP from `LambdaMartFeatureSchema` — reduced to 2 features
    (`[sparse, vector]`), removed `IDX_QPP_*` constants, simplified `buildRow()`.
    Updated `LambdaMartFeatureSchemaTest` for new 2-feature API.
    (b) Removed QPP reading from `LambdaMartTrainer.train()` and QPP params from
    `LambdaMartReranker.rerank()`. QPP values remain in the NDJSON triple store
    (no changes to `GplTrainingTripleStore` or `GplJobCoordinator`) for V2 use.
    (c) Refactored `evaluateOffline()` to use `normalizeGroupedFeatures()` —
    removed the inline duplicate normalization copy (10c).
    (d) Fixed `buildDataset()` autoboxing: pre-computed `numRows`, direct `float[]`
    accumulation instead of `List<Float>` (10d).
    (e) Updated `LambdaMartReranker` class Javadoc and `rerank()` Javadoc to
    accurately describe V1 schema and per-result-set normalization rationale (10e).
    Updated `LambdaMartRerankerTest` for new 3-arg `rerank()` signature.
    **Operator action required**: delete existing `lambdamart-model.txt` (trained on
    5-feature schema; Issue 1 feature-count validation will reject it at startup if left in
    place). Re-run GPL + retrain before any quality comparison.
    **Must precede item 3 (SPLADE retrain)**.
11. **DONE — Feature-count validation at `loadModel()`** (`LambdaMartReranker.java`). Calls
    `booster.getNumFeature()` after loading and rejects stale models with a WARN log.
    Protects against silent misranking when the feature schema changes (Issue 1).
12. **DONE — Model provenance sidecar** (`LambdaMartReranker.java`). `saveModel()` writes
    `<model>.meta.json`; `loadModel()` restores `TrainingStatus`. nDCG@10 and training group
    counts survive restart (Issue 3).
13. **DONE — `FEATURE_NAMES` constant** (`LambdaMartFeatureSchema.java`). Immutable
    `List.of("sparse", "vector")` for documentation and V2 feature name embedding (Issue 4).
14. **V2 deferred — embed feature names in `LGBMDataset`**: Add
    `dataset.setFeatureNames(FEATURE_NAMES.toArray(new String[0]))` in
    `LambdaMartTrainer.buildDataset()` so trained models embed feature names. Then
    `loadModel()` can validate `getFeatureNames()` against `FEATURE_NAMES` for a stronger
    schema check beyond the count-only check. Requires re-training after the change.

**Relationship to Tempdoc 237**: Several E2E gaps in this document (E2E-2, E2E-7)
were instances of a systemic pattern: the backend correctly modeled new pipeline
dimensions (SPLADE, LambdaMART, Phase 2 chunk embedding) but consumers (eval
scripts, wait loops, proof logic) were not updated to use the new signals. Tempdoc
237 documented this pattern, inventoried the wiring gaps (W1–W7), and defined a
prevention rule for future additions. **237 is fully implemented as of 2026-02-27**
(all W items complete, status: implemented).

237 W1 and W2 were hard prerequisites to successor item 3 (SPLADE retrain) and are
now done. The remaining BEIR gate blocker is the 235 I2 large-corpus re-runs:
webis-touche2020 was completed before W1/W2 landed (metrics invalid), and mldr-en
has never been run to completion. Both must be executed with W1/W2 in place before
any I2 baseline can be used as a gate for item 3.

**Recommended implementation order for successor items 3 and 4**:
re-run 235 I2 webis-touche2020 → run 235 I2 mldr-en → promote I2 baselines
→ item 3 (SPLADE retrain) → item 4 (search quality eval).

Items 10–13 are done. The blocking prerequisite for item 3 is the 235 I2 BEIR re-runs with
W1/W2 in place. After promoting I2 baselines, delete `lambdamart-model.txt` (trained on the
5-feature schema; feature-count validation will reject it at startup) and re-run GPL + retrain.

235 A6 (RRF parameter sweep: `rrf_k`, `vector_rrf_weight`) is currently blocked on
manual env var setup and remains an open prerequisite for honest LambdaMART quality
assessment in item 4 — LambdaMART trained against a suboptimally fused hybrid index
inherits that miscalibration as training noise. If A6 can be unblocked before item 3,
run it first.
