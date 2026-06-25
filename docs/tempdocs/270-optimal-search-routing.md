---
title: "270: Optimal Search Routing — Theoretical Design"
type: tempdoc
status: done
created: 2026-03-09
depends-on: [251, 256, 258, 260]
synthesizes: [229, 234, 250, 251, 256, 258, 260]
---

> NOTE: Noncanonical doc (theoretical investigation). May drift.

> **Errata (2026-03-16)**: Tempdoc 309 identifies 6 structural corrections and 4
> significant refinements to the consolidated final design below, based on post-270
> internet research and codebase investigation. Key corrections:
>
> 1. **Dedup placement** (Stage 3): Move after chunk merge, not before fusion. §309-7
> 2. **SimHash threshold**: k=2 not k=3 for passage-length text. §309-7
> 3. **WIG/NQC CE gating** (Stage 6): Drop — unreliable at desktop scale. §309-6
> 4. **Dense weight modulation** (`dw` in fusion formula): Remove — no evidence. §309-5
> 5. **Freshness formula** (Stage 7): Switch additive → multiplicative. §309-9
> 6. **Agent CE skip** (Gap 6): Wrong — keep CE in agentic path. §309-10
>
> See tempdoc 309 §21-§22 for the full status map and revised priority ordering.

# 270: Optimal Search Routing — Theoretical Design

## Purpose

Determine the best theoretically possible routing logic for JustSearch's
search pipeline, unconstrained by implementation feasibility or complexity.
This is a pure design investigation: what would the optimal system look like
if we could build anything?

The investigation synthesizes existing empirical evidence (tempdoc 251),
infrastructure (256), bandit theory (229/234), QPP signals (260), and
current routing policy (258 D2) with state-of-the-art literature on
query-adaptive retrieval routing.

## Constraints (product-level, not implementation)

- Single-user desktop app (no A/B testing infrastructure)
- Heterogeneous personal corpus (PDFs, emails, code, notes — variable length)
- Latency-sensitive interactive search (target <500ms)
- No cloud backend — all inference runs locally on consumer GPU
- Sparse implicit feedback (document-open events, no explicit relevance)

## Existing Evidence Summary

### Empirical routing signals (tempdoc 251)

| Signal | Evidence strength | Source |
|--------|------------------|--------|
| Document length > 4K tokens → disable SPLADE | Very strong | courtlistener 0.083 vs 0.734 nDCG |
| Document length > 2K tokens → disable cross-encoder | Very strong | MiniLM-L6-v2 512-token limit, -0.606 nDCG |
| Short docs (<2K) → SPLADE-first | Strong | 5/6 datasets, 3 corpus sizes |
| Long-doc routing is task-dependent | Medium | BM25 wins topical, Hybrid wins refinding |
| LambdaMART zero effect on long docs | Strong | Phase 5 factorial |
| Cross-corpus dilution is modest | Medium | worst -0.051 nDCG |

### Available infrastructure (tempdoc 256)

- `PipelineConfig`: independent boolean flags per component
- `expandPreset()`: Head-side preset → config translation layer
- Agent `SearchTool`: per-query component flags
- QPP signals computed but unused: `maxIdf`, `avgIctf`, `queryScope`

### Prior theoretical work (tempdocs 229, 234)

- Thompson Sampling bandit designed but deferred (needs live users)
- LinUCB contextual bandit researched
- Gate D cold-start rules: static baseline → adaptive after ≥300 queries
- Runtime-readiness fallback order specified

---

## Investigation Log

### Round 1: Codebase Infrastructure Audit (2026-03-09)

**QPP signal computation** (`SearchOrchestrator.java:607-653`, `DocumentQueryOps.java:122-162`):
- `maxIdf`: `log((numDocs + 1) / (df + 1))` — rarity of rarest query term
- `avgIctf`: `log((sumTotalTermFreq + 1) / (termCollFreq + 1))` — average term specificity
- `queryScope`: `1 - product(1 - df_i/N)` — fraction of corpus matching ≥1 term
- Underlying stats available: `numDocs`, per-term `docFreq`, per-term `totalTermFreq`,
  `sumTotalTermFreq` per field
- **Only computed when sparse or SPLADE enabled** (line 191)

**What the Worker knows at dispatch time:**
- Full PipelineConfig (8 boolean/string flags)
- Query text (analyzed terms)
- Index term statistics (via QPP)
- Document count (`indexRuntime.docCount()`)
- Chunk existence (`queryDocIdsByField(IS_CHUNK, "true", 1)`)
- Embedding compatibility status
- **NOT available**: average document length, document length distribution,
  per-document metadata, field-level aggregate stats

**expandPreset() design** (`KnowledgeHttpApiAdapter.java:1034-1043`):
- Pure function: `(SearchMode, RerankerConfig) → PipelineConfig`
- No access to index statistics, corpus metadata, or query features
- Head-side only — cannot see Worker state

**Feedback infrastructure**: Effectively absent. `UiTelemetryPublisher` exists
as a generic envelope but no document-open, click, dwell-time, or satisfaction
events are defined or collected. `OperationalMetrics` tracks aggregate counters
(searches performed, zero-result queries) but no per-query engagement data.

### Round 1: Literature Survey (2026-03-09)

**Key papers and approaches surveyed:**

| Approach | Key work | Signal type | Reported gain | Desktop fit |
|----------|----------|-------------|---------------|-------------|
| Dynamic Alpha Tuning (DAT) | arXiv:2503.23013 (Mar 2025) | LLM judges top-1 from each retriever | Beats fixed hybrid consistently | Low (LLM per query) |
| RouterRetriever | AAAI 2025, arXiv:2409.02685 | Embedding similarity routes to domain experts | +2.1-3.2 nDCG over single models | Low (multi-model) |
| QPP-GenRE | arXiv:2404.01012 | LLM per-doc pseudo-judgments → reconstruct metrics | SOTA QPP quality | Low (LLM per query) |
| QPP for routing (TOIS 2025) | ACM TOIS 2025 10.1145/3774427 | Classic QPP (IDF, clarity, score dist) | Context-dependent; collection > predictor | High |
| MC-indexing | EMNLP Findings 2024 | Content-aware multi-view chunks | +16-43% recall | High |
| Neural bandit pipeline selection | arXiv:2508.09958 | Contextual bandit over pipeline configs | Outperforms static cascades | Medium |
| LTRR (Learning to Rank Retrievers) | arXiv:2506.13743 | Train classifier on query features → rank retrievers | Optimizes for downstream LLM perf | Medium |
| SEE (early-exit reranking) | SIGIR 2025 | Intermediate layer confidence | Saves compute, maintains quality | Medium |
| E2Rank (layer-wise reranking) | ECIR 2025 (Pinecone) | Per-layer ranking decisions | Efficient variable-depth reranking | Medium |
| Vespa phased ranking | docs.vespa.ai | Cheap first phase → expensive second phase | Sublinear retrieval + quality reranking | High |
| Vespa graceful degradation | docs.vespa.ai | Coverage-based partial results under timeout | Returns best-effort within budget | High |

**Key literature insights:**

1. **QPP predictor accuracy is highly context-dependent** (TOIS 2025): Collection
   and ranker characteristics explain more variance than predictor choice. Dense-based
   QPP shows limited correlation in hybrid/neural contexts. Implication: pre-retrieval
   QPP alone is insufficient for routing; post-retrieval signals (score distributions)
   are needed.

2. **No single retrieval strategy dominates**: The BEIR benchmark heterogeneity
   (arXiv:2104.08663) plus our own 251 data confirm this universally. The question
   is not "which retriever is best" but "which retriever is best for this query
   on this corpus."

3. **Cold-start is the central challenge for single-user systems**: Bandits need
   feedback volume that single users can't provide quickly. Warm-starting from
   heuristic rules with strong priors, then slowly adapting via implicit signals,
   is the consensus approach.

4. **Multi-stage gating is universally adopted**: Every production system (Google,
   Bing, Vespa) and recent research uses conditional execution of expensive stages.
   The theoretical optimum always includes adaptive gating, not fixed pipelines.

5. **Document length is the strongest routing signal for mixed-length corpora**:
   Confirmed by both our data (251) and the long-document retrieval survey
   (arXiv:2509.07759). No single approach handles both short and long documents
   optimally.

---

### Round 2: Deep Research (2026-03-09)

**Post-retrieval QPP for adaptive gating:**

Two complementary signals characterize first-stage result confidence:
- **WIG (Weighted Information Gain)**: Mean score of top-k minus corpus score.
  High WIG = strong retrieval; reranking unlikely to help much.
- **NQC (Normalized Query Commitment)**: Std dev of top-k scores, normalized by
  corpus score. Low NQC = confident/narrow distribution; high NQC = uncertain,
  reranking likely helps.

Together they predict when second-stage reranking will change the outcome.
Cascade ranking with adaptive computation (AcuRank, NeurIPS 2025) and
ranked list truncation for reranker gating (Meng et al., SIGIR 2024)
formalize this as a gating decision.

Key limitation: QPP predictor effectiveness varies across rankers because score
distributions differ fundamentally between BM25, dense, and neural retrievers.
No single predictor dominates universally (Datta & Ganguly, ECIR).

**Query type classification without training data:**

Rule-based classification achieves ~74% accuracy on web queries (classical
query intent taxonomy). Modern RAG-era intent classification uses retrieval-
augmented approaches (REIC, EMNLP 2025) and learned query routing (RAGRouter,
NeurIPS 2025). For desktop search, the signal space is richer:

| Signal | Hint |
|--------|------|
| File extension present (.pdf, .docx) | Navigational (known-item) |
| Quoted phrase | Exact-match / navigational |
| Very short (1-2 terms) | Navigational or broad |
| Question words (how, what, why) | Informational |
| Path fragments / folder names | Navigational |
| Length > 5 terms | Informational / complex |

Misclassification cost is low (slightly suboptimal but functional retrieval),
so simple rules are sufficient — no LLM or trained classifier needed.

**Index-time corpus statistics (what Lucene can provide):**

Lucene's `CollectionStatistics` gives per-field: `maxDoc`, `docCount`,
`sumTotalTermFreq`, `sumDocFreq`. From these:
- `avgFieldLength = sumTotalTermFreq / docCount` (document length proxy)
- Vocabulary richness = `sumDocFreq / sumTotalTermFreq` (type-token ratio)
- Field coverage = `docCount(field) / maxDoc`

**Not natively available but computable**: document length percentiles (p10,
p50, p90), term frequency distribution shape. These require a one-time scan
at index commit but are cacheable.

JustSearch currently does NOT expose average field length, document length
distribution, or field coverage ratios. The QPP signals are term-level only.

**Feedback-free adaptive routing:**

Four approaches that work without user clicks:
1. **QPP-as-intrinsic-reward**: Run retrieval, compute WIG/NQC on results.
   High QPP = chosen path was effective. Creates a self-supervised feedback
   loop without user interaction.
2. **Corpus-conditioned static policies**: Index-time statistics select a
   routing policy offline. Short-doc corpus → weight SPLADE. Long-doc →
   weight BM25. No runtime feedback needed.
3. **Uncertainty-based routing**: Entropy of score distribution, margin
   between top-1 and top-2 scores as intrinsic signals (arXiv:2501.12835).
4. **Pseudo-relevance feedback**: Top-k BM25 results as pseudo-relevant;
   cross-encoder scores on a sample as soft labels. Evaluate which retriever
   performed better per query type.

> **Historical** — This table recommends RRF as default, which was superseded
> by convex combination (CC) in Round 2 §5. See "Consolidated Final Design."

**Score calibration:**

| Method | Best for |
|--------|----------|
| RRF (rank-based) | Default when score distributions unknown. Safest. |
| ZMUV (z-score) + CombMNZ | Best when calibrated. Top performer in fusion studies. |
| Min-Max | Strong but outlier-sensitive |
| Cross-encoder reranking | Implicitly calibrates by re-scoring all candidates on one scale |

RRF is the correct default. ZMUV is the upgrade path if running statistics
are maintained per retriever.

---

> **Historical** — This section describes the original 4-layer architecture
> from Rounds 1-2. It was superseded by the run-all-then-gate revision in
> Round 4 and the 7-stage pipeline in Round 3. See "Consolidated Final Design"
> for the authoritative architecture.

## Theoretical Optimal Routing Architecture

### Design Principles

1. **Multi-layer, not single-point**: Routing decisions happen at multiple
   stages (index-time, pre-retrieval, post-retrieval, post-reranking), each
   using signals available at that point.
2. **Corpus-first, query-second**: The strongest routing signal is corpus
   composition (document length distribution), not query features. Query
   features refine within a corpus regime.
3. **Intrinsic reward over extrinsic**: Use retrieval confidence (QPP) as
   the primary adaptation signal, not user clicks. Clicks are too sparse
   for a single-user system to converge.
4. **Gating over selection**: Instead of choosing ONE retriever, run the
   cheap retriever always, then gate expensive stages conditionally.
5. **Graceful degradation**: Every component failure should produce a
   worse-but-functional result, never an error.

### Layer Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Layer 0: Corpus Profile (index-time, cached)           │
│  Signals: avgDocLength, docLengthPercentiles,           │
│           vocabularyRichness, fieldCoverage,             │
│           chunkRate, spladeFeatureCoverage               │
│  Output:  CorpusRegime {SHORT, MIXED, LONG}             │
│           + per-field statistics                         │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  Layer 1: Query Classification (pre-retrieval, <1ms)    │
│  Signals: queryLength, hasFileExtension, hasQuotes,     │
│           hasQuestionWords, hasPathFragments,            │
│           termCount, maxIdf, queryScope                  │
│  Output:  QueryType {NAVIGATIONAL, INFORMATIONAL,       │
│                      EXACT_MATCH, EXPLORATORY}           │
│           + QPP feature vector                           │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  Layer 2: Component Selection (pre-retrieval)           │
│  Input:   CorpusRegime × QueryType × QPP features       │
│  Logic:   Policy table mapping regime+type to           │
│           PipelineConfig (which legs to run,             │
│           which rerankers to enable, fusion weights)     │
│  Output:  PipelineConfig + gating thresholds             │
└──────────────────────┬──────────────────────────────────┘
                       │
              ┌────────▼────────┐
              │   RETRIEVAL     │
              │  (selected legs)│
              └────────┬────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  Layer 3: Post-Retrieval Gating (after first stage)     │
│  Signals: WIG, NQC, scoreGap(rank1-rank2),              │
│           resultCount, chunkMergeRate                    │
│  Decisions:                                              │
│   - Skip cross-encoder if WIG high + NQC low            │
│   - Skip LambdaMART if resultCount < threshold          │
│   - Trigger SPLADE fallback if scoreGap near zero       │
│   - Adjust RRF k dynamically based on score spread      │
│  Output:  Gated reranking decisions                      │
└──────────────────────┬──────────────────────────────────┘
                       │
              ┌────────▼────────┐
              │   RERANKING     │
              │  (if not gated) │
              └────────┬────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  Layer 4: Adaptive Refinement (background, async)       │
│  Signals: QPP-as-reward (WIG/NQC of final results),     │
│           implicit feedback (doc-open, dwell time),      │
│           query reformulation rate                       │
│  Method:  Thompson Sampling with strong priors from      │
│           Layer 2 policy table. Updates weights slowly.  │
│  Constraint: ≥300 queries before adaptation activates    │
│              (cold-start rule from tempdoc 229 Gate D)   │
│  Output:  Updated policy table weights                   │
└─────────────────────────────────────────────────────────┘
```

### Layer 0: Corpus Profile

Computed at index commit time. Cached as index metadata.

**Signals:**

| Signal | Computation | Routing use |
|--------|-------------|-------------|
| `medianDocLength` | Scan all docs, take p50 of token count | Primary regime classifier |
| `docLengthP10, P90` | 10th/90th percentile | Detect bimodal corpora |
| `bimodalityCoeff` | `(skewness² + 1) / kurtosis` on length dist | >0.555 = bimodal → needs per-doc routing |
| `vocabularyRichness` | `sumDocFreq / sumTotalTermFreq` | Low = repetitive → favor exact match |
| `avgFieldLength` | `sumTotalTermFreq / docCount` per field | Field-level length proxy |
| `chunkRate` | `chunkDocs / totalDocs` | High = long-doc-heavy corpus |
| `spladeFeatureCoverage` | `spladeIndexed / totalDocs` | Whether SPLADE is available |
| `embeddingCoverage` | `embeddedDocs / totalDocs` | Whether dense is available |

**Regime classification:**

```
if medianDocLength < 1024 tokens:
    regime = SHORT
elif medianDocLength > 4096 tokens:
    regime = LONG
elif bimodalityCoeff > 0.555:
    regime = MIXED       # bimodal — needs per-document routing
else:
    regime = MEDIUM      # unimodal moderate length
```

The MIXED regime is the hardest case. It means the corpus has both short
and long documents, and a single pipeline config will be suboptimal for
some fraction. This is where per-query or per-document routing matters most.

### Layer 1: Query Classification

Rule-based, <1ms, no model inference.

**Classification rules (priority order):**

```
1. hasFileExtension OR hasPathFragment → NAVIGATIONAL
2. isQuotedPhrase → EXACT_MATCH
3. hasQuestionWord AND termCount > 3 → INFORMATIONAL
4. termCount == 1 AND maxIdf > 8.0 → NAVIGATIONAL (rare specific term)
5. termCount == 1 AND maxIdf < 3.0 → EXPLORATORY (common broad term)
6. queryScope > 0.5 → EXPLORATORY (matches most of corpus)
7. queryScope < 0.01 → INFORMATIONAL (very specific)
8. default → INFORMATIONAL
```

The query type modulates component selection within a corpus regime.

> **Historical** — These policy tables specify pre-retrieval leg selection,
> which was superseded by "always run all legs" in Round 4. The per-regime
> weight patterns informed the per-document fusion weights in the final design.

### Layer 2: Component Selection Policy Table

The core routing decision. Maps (CorpusRegime × QueryType) to PipelineConfig.

**SHORT regime** (medianDocLength < 1024 tokens):

| QueryType | sparse | dense | splade | CE | LM | fusion | expansion |
|-----------|--------|-------|--------|----|----|--------|-----------|
| NAVIGATIONAL | Y | N | N | N | N | none | N |
| EXACT_MATCH | Y | N | N | N | N | none | N |
| INFORMATIONAL | Y | Y | Y | Y | N | rrf | N |
| EXPLORATORY | N | Y | Y | N | N | rrf | Y |

Rationale:
- Navigational/exact: BM25 excels at exact term matching. Dense adds latency
  for no gain. SPLADE's learned expansion could hurt precision on known-item.
- Informational: Full pipeline. SPLADE dominates short-doc nDCG (251 data).
  Cross-encoder improves ranking when docs fit within its 512-token window.
- Exploratory: Skip BM25 (common terms saturate); rely on semantic retrieval.
  LLM expansion helps broaden recall for vague queries.

**LONG regime** (medianDocLength > 4096 tokens):

| QueryType | sparse | dense | splade | CE | LM | fusion | expansion |
|-----------|--------|-------|--------|----|----|--------|-----------|
| NAVIGATIONAL | Y | N | N | N | N | none | N |
| EXACT_MATCH | Y | N | N | N | N | none | N |
| INFORMATIONAL | Y | Y | N | N | N | rrf | N |
| EXPLORATORY | Y | Y | N | N | N | rrf | Y |

Rationale:
- SPLADE disabled across the board (512-token truncation catastrophe).
- Cross-encoder disabled (MiniLM-L6-v2 512-token limit destroys ranking).
- LambdaMART disabled (zero effect on long docs, 251 Phase 5).
- Hybrid for informational/exploratory: chunk-merge RRF captures content
  that BM25 term saturation misses (courtlistener 0.827 vs 0.734).
- Navigational: BM25-only (fastest path, highest precision for known-item).

**MEDIUM regime** (unimodal 1024-4096 tokens):

| QueryType | sparse | dense | splade | CE | LM | fusion | expansion |
|-----------|--------|-------|--------|----|----|--------|-----------|
| NAVIGATIONAL | Y | N | N | N | N | none | N |
| EXACT_MATCH | Y | N | N | N | N | none | N |
| INFORMATIONAL | Y | Y | Y | gated | N | rrf | N |
| EXPLORATORY | Y | Y | Y | gated | N | rrf | Y |

Rationale:
- SPLADE included but at reduced confidence — documents are near the
  512-token boundary. Truncation loses some content but not catastrophically.
- Cross-encoder is **gated** (Layer 3 decides based on score distribution).
  At this length, CE may or may not help — let post-retrieval signals decide.

**MIXED regime** (bimodal — both short and long docs):

This is the most interesting case. A single policy table entry is wrong
for some fraction of the corpus. The theoretical optimum uses **per-document
awareness** at query time:

| QueryType | sparse | dense | splade | CE | LM | fusion | expansion | special |
|-----------|--------|-------|--------|----|----|--------|-----------|---------|
| NAVIGATIONAL | Y | N | N | N | N | none | N | — |
| EXACT_MATCH | Y | N | N | N | N | none | N | — |
| INFORMATIONAL | Y | Y | Y | gated | N | rrf | N | post-retrieval re-score |
| EXPLORATORY | Y | Y | Y | gated | N | rrf | Y | post-retrieval re-score |

The "post-retrieval re-score" means: after first-stage retrieval, examine
which result documents are short vs long. Apply SPLADE scores only for
short-doc results. Apply chunk-aware BM25 boosting for long-doc results.
Gate cross-encoder to only score documents within its context window.

This requires **per-document length metadata** stored in the index and
accessible at scoring time — a stored field or doc value containing the
original document's token count.

### Layer 3: Post-Retrieval Gating

After first-stage retrieval returns ranked results, compute:

```
wig = mean(topK_scores) - corpus_avg_score
nqc = stddev(topK_scores) / corpus_avg_score
scoreGap = score[0] - score[1]
resultCount = |results|
chunkMergeRate = merged_chunks / total_results
```

**Gating rules:**

| Condition | Action | Rationale |
|-----------|--------|-----------|
| WIG > θ_high AND NQC < θ_low | Skip reranking | First stage is confident |
| scoreGap > θ_gap | Skip reranking | Clear winner, reranking won't change rank 1 |
| resultCount < 5 | Skip LambdaMART | Too few items for learned ranking |
| chunkMergeRate > 0.8 | Suppress SPLADE scores in fusion | Long-doc results; SPLADE scores unreliable |
| NQC > θ_uncertain | Add expansion retry | Retrieval is uncertain; broaden recall |
| All top-K from chunks of same parent | Boost parent, skip CE | Single-doc dominance; CE adds nothing |

Thresholds (θ values) are initially set from literature defaults, then
refined by Layer 4's adaptive mechanism.

**Cross-encoder gating (for MEDIUM and MIXED regimes):**

```
for each candidate document in top-K:
    if doc.tokenCount > 512:
        skip CE for this document (preserve first-stage score)
    else:
        apply CE reranking
```

This is per-document gating, not per-query. In a MIXED corpus, some results
are short (CE helps) and some are long (CE hurts). The optimal approach
applies CE selectively.

### Layer 4: Adaptive Refinement

Background process that slowly updates Layer 2 policy table weights.

**Phase 1: QPP-as-reward (no user feedback needed)**

After each query, compute WIG and NQC on the final result set. Compare
against what the alternative routing would have produced:

```
actual_wig = WIG(results from chosen pipeline)
counterfactual_wig = WIG(results from alternative pipeline)  // shadow query
reward = actual_wig - counterfactual_wig
```

Shadow queries are expensive (run retrieval twice) but can be done
asynchronously, sampled at 10-20% of queries, with results cached.

**Phase 2: Implicit feedback (after user interaction exists)**

Track:
- Document-open events (strongest signal)
- Dwell time on opened documents
- Query reformulation (negative signal — first results weren't good enough)
- Search abandonment (no click, no reformulation — ambiguous)

> **Historical** — TS was demoted in Round 2 §9 and Round 3 in favor of
> deterministic fusion weight tuning via shadow evaluation. See "Consolidated
> Final Design" for the revised adaptive refinement approach.

**Phase 3: Thompson Sampling with strong priors**

Each (CorpusRegime × QueryType) cell in the policy table has a Beta
distribution over each component's inclusion probability:

```
P(sparse=true | SHORT, INFORMATIONAL) ~ Beta(α_sparse, β_sparse)
```

Priors are set from the static policy table (strong: α=50, β=5 for
components that should be on; α=5, β=50 for components that should be off).
This ensures convergence takes hundreds of observations to meaningfully
shift routing — protecting against noise from sparse feedback.

**Cold-start rule** (from tempdoc 229 Gate D):
- Adaptive refinement activates only after ≥300 queries in corpus context
- Static policy table governs until then
- Two consecutive evaluation windows must agree on direction before
  a policy change is promoted

> **Historical** — The pseudocode below uses RRF, which was replaced by
> convex combination (CC) in Round 2 §5. The per-document routing concept
> survives in the final design's per-document fusion with CC re-normalization.

### Handling the Hardest Case: MIXED Regime with Per-Document Routing

The theoretical optimum for a bimodal corpus (e.g., a user with both
research papers and chat logs) requires routing decisions at **document
granularity**, not just query or corpus granularity.

**Index-time**: Store `docTokenCount` as a Lucene DocValues field for every
document. Compute and cache `docLengthPercentiles` as index metadata.

**Query-time**: Run all enabled retrieval legs. In the fusion stage:

```
for each candidate document in merged result list:
    length = docValues.get(docId, "docTokenCount")
    if length < 1024:
        // Short-doc regime: trust SPLADE score, apply CE
        finalScore = rrf(bm25, dense, splade, k=60)
        if CE enabled: apply cross-encoder reranking
    elif length > 4096:
        // Long-doc regime: suppress SPLADE, skip CE
        finalScore = rrf(bm25, dense, k=60)  // no SPLADE contribution
        // CE skipped (would damage ranking)
    else:
        // Medium: include SPLADE at reduced weight, gate CE
        finalScore = rrf(bm25, dense, splade * 0.5, k=60)
        if CE enabled AND scoreGap < θ: apply CE
```

This is per-document adaptive fusion — the fusion weights vary by document
length within a single query's result set. No production system does this
today, but it's the theoretically optimal approach for heterogeneous corpora.

### Graceful Degradation Cascade

When components are unavailable (model not loaded, GPU busy, timeout),
fall through this hierarchy:

```
Level 0: Full pipeline (all legs + gated reranking)     [optimal]
Level 1: Hybrid without reranking                        [fast, good recall]
Level 2: BM25 + SPLADE without dense                    [no embedding needed]
Level 3: BM25 + expansion                               [lexical + LLM broadening]
Level 4: BM25-only                                       [always available]
Level 5: BM25-only with reduced result count             [under memory pressure]
```

Each degradation step is logged with a reason code in the execution report.
The Agent path can detect degradation and retry with different parameters.

---

## Novelty Assessment (Round 3 validation, 2026-03-09)

Three claims in this design were validated against published literature:

| Claim | Verdict | Detail |
|-------|---------|--------|
| **Per-document adaptive fusion** (varying RRF weights by doc length within a single query) | **Novel** | Literature covers per-query (DAT, arXiv:2503.23013) and per-retriever weighting, but not per-document within a result set. Closest is DAPR (ACL 2024) which is document-aware passage retrieval, not document-aware fusion. |
| **Bimodality detection on doc-length for regime classification** | **Novel application** | Bimodality coefficient and Hartigan's Dip Test are well-established statistics. Applying them to document-length distributions for retrieval routing is unstudied. Standard BC threshold: >0.555. |
| **Selective cross-encoder gating by document length** (skip CE for long docs, preserve first-stage score) | **Novel framing** | Everyone either truncates or chunks. The explicit gating strategy "this document is too long → keep its BM25/dense score as-is while reranking shorter candidates" is intuitive but unpublished. Chunking is the standard alternative but adds latency proportional to document length. |

**Bimodality coefficient correction**: The precise formula with sample-size
correction is `BC = (skewness² + 1) / (kurtosis + 3(n-1)² / ((n-2)(n-3)))`.
BC > 0.555 suggests bimodality. For robustness, combine with Hartigan's Dip
Test (p < 0.05 rejects unimodality).

---

## Revised Architecture: Latency Data Changes Everything (Round 4, 2026-03-10)

### The key finding: retrieval is free, reranking is expensive

Literature and benchmarks confirm that on a desktop corpus (10K-1M docs),
ALL retrieval legs are cheap enough to run on every query:

| Component | Latency | Source |
|-----------|---------|--------|
| BM25 (Lucene, 100K docs) | 1-5 ms | ES benchmarks, Lucene direct |
| Dense KNN/HNSW (100K, 768d) | 1-5 ms | Lucene HNSW, ann-benchmarks |
| SPLADE retrieval (FeatureField) | 1-5 ms | ~1.1x BM25 (OpenSearch deep dive) |
| SPLADE query encoding (IDF lookup) | <0.1 ms | Inference-free, dict lookup |
| SPLADE query encoding (BERT-base CPU) | ~43 ms | **This is the bottleneck if neural** |
| RRF fusion | <0.1 ms | Pure arithmetic on rank lists |
| **Cross-encoder rerank (top-20, ONNX)** | **60-100 ms** | Metarank benchmarks, ~3 ms/doc |
| **Cross-encoder rerank (top-50, ONNX)** | **150-250 ms** | Batching efficiency plateaus |

**Parallel retrieval (all 3 legs)**: ~3-5 ms wall-clock = max(BM25, KNN, SPLADE).
**Reranking top-20**: 60-100 ms = 10-20x the cost of ALL retrieval combined.

### Architectural revision

The original 4-layer design had Layers 0-2 deciding which retrieval legs
to skip. The latency data shows **this is unnecessary optimization**:

- Running all three retrievers in parallel costs ~5 ms total
- The only expensive component is the cross-encoder reranker (60-250 ms)
- Pre-retrieval component selection (Layers 0-2) was solving a problem
  that doesn't exist — retrieval is already cheap enough to run always

**The revised optimal architecture has two layers, not four:**

```
┌─────────────────────────────────────────────────────────┐
│  ALWAYS RUN: All retrieval legs in parallel              │
│  BM25 + Dense KNN + SPLADE (IDF) → RRF fusion           │
│  Cost: ~5 ms. No routing decision needed.                │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  THE ROUTER: Post-retrieval, per-document decisions      │
│                                                          │
│  Input: fused result list + per-doc metadata + scores    │
│                                                          │
│  Decision 1: Per-document score trust                    │
│    For each result document:                             │
│    - docLength < 1024 → trust all leg scores equally     │
│    - docLength > 4096 → suppress SPLADE contribution     │
│    - else → reduce SPLADE weight proportionally          │
│                                                          │
│  Decision 2: Per-document reranker gating                │
│    For each candidate in top-K:                          │
│    - docLength ≤ 512 tokens → apply cross-encoder        │
│    - docLength > 512 tokens → preserve first-stage score │
│                                                          │
│  Decision 3: Query-level reranker budget                 │
│    Based on score distribution (WIG, NQC, scoreGap):     │
│    - High WIG + low NQC → skip reranking entirely        │
│    - Large scoreGap → skip (clear winner)                │
│    - else → rerank eligible (short) documents            │
│                                                          │
│  Cost: 0 ms (gating) + 0-100 ms (conditional reranking) │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  BACKGROUND: Adaptive refinement (optional, async)       │
│  QPP-as-reward + implicit feedback → Thompson Sampling   │
│  Tunes: SPLADE suppression thresholds, reranker gating   │
│         thresholds, per-query-type routing overrides      │
│  Cold-start: ≥300 queries before adaptation activates    │
└─────────────────────────────────────────────────────────┘
```

### How pre-retrieval layers relate to post-retrieval gating

The latency data shows retrieval legs don't need pre-retrieval gating —
all three run for ~5 ms in parallel. But this does NOT make the
pre-retrieval layers irrelevant. It **demotes** them from independent
routing stages to **supporting inputs** to the post-retrieval router.

| Layer | Original role | Revised role |
|-------|--------------|-------------|
| **Corpus Profile** | Gate retrieval legs by corpus regime | **Demoted to metadata provider.** Per-document `docTokenCount` is essential for fusion and CE gating. Corpus-level stats (median length) provide sensible defaults — e.g., if 95% of docs are short, enable CE by default without per-doc gating overhead. |
| **Query Classification** | Gate retrieval legs by query type | **Demoted to reranker/expansion input.** Navigational queries skip CE reranking (saves 60-100 ms). Exploratory queries trigger LLM expansion. Not needed for retrieval leg selection. |
| **Component Selection** | Pre-retrieval leg selection | **Eliminated for retrieval.** Survives only as fusion weight hints — if query is navigational, weight BM25 higher in RRF. But post-retrieval scores are strictly more informative, so this is a minor optimization. |
| **Post-Retrieval Gating** | Gate reranker | **Promoted to primary router.** All routing authority flows through here. Per-document fusion, per-document CE gating, query-level reranker budget. |
| **Adaptive Refinement** | Tune component selection | **Scope narrowed.** Tunes gating thresholds and fusion weights, not component on/off decisions. Smaller action space → faster convergence. |

The architecture is still multi-layered, but **authority shifted**: post-
retrieval gating is the primary decision-maker. Pre-retrieval layers are
cheap signal providers that feed into it, not independent routing stages.

### Literature confirmation

This aligns with the emerging "gate only the reranker" pattern:

- **"Balancing the Blend"** (arXiv:2508.01405, Aug 2025): Integrating
  four retrieval paradigms shows fusion increases diversity but gains are
  sometimes neutralized by downstream pipeline constraints. The bottleneck
  is downstream, not retrieval.
- **AcuRank** (NeurIPS 2025, arXiv:2505.18512): Uncertainty-aware adaptive
  computation — dynamically adjusts reranking budget per query. The gating
  boundary belongs at the cost discontinuity (retrieval ~5 ms vs reranking
  ~60-250 ms).
- **"Reranker-Guided Search"** (arXiv:2509.07163, 2025): Given a fixed
  reranker budget, which documents should you rerank? Achieves +3.5-5.1
  nDCG by optimizing document selection for reranking.
- **Bruch, Gai, Ingber** (ACM TOIS 2024): Score distributions from
  heterogeneous retrievers; convex combination outperforms RRF.
- **RAG Fusion at scale** (arXiv:2603.02153, 2026): Reports hybrid fusion
  adds ~201 ms total but search+fusion is <7% of that. Expensive part is
  downstream (generation/reranking).
- **IR Architecture Survey** (arXiv:2502.14822, Feb 2025): Parallel
  retrieval patterns in modern multi-stage pipelines — run all legs,
  gate downstream.

### Core innovations (survive from original design)

1. **Per-document adaptive fusion** (novel): Vary SPLADE weight contribution
   based on document length within the fused result set. Confirmed novel —
   literature covers per-query (DAT) and per-retriever, not per-document.
   Closest: MMMORRF for multimodal video retrieval.

2. **Per-document cross-encoder gating** (novel framing): Apply CE only to
   documents within its context window. Confirmed novel — standard approach
   is truncation or chunking, not selective gating with score preservation.

3. **Score-distribution-based reranker budget** (well-established): WIG/NQC
   determine whether reranking is worth the cost. Grounded in ranked list
   truncation (Meng et al., SIGIR 2024) and QPP survey (SIGIR-AP 2024).

4. **Query classification as reranker/expansion input**: Navigational queries
   skip CE (saves 60-100 ms). Exploratory queries trigger expansion. Cheap
   rule-based classification (<1 ms), high value for the reranker decision.

5. **Thompson Sampling for threshold tuning** (background, long-term): The
   adaptive layer tunes gating thresholds rather than component selection.
   Smaller action space → faster convergence.

6. **Graceful degradation cascade**: When components are unavailable, degrade
   through: full pipeline → hybrid no rerank → BM25+SPLADE → BM25-only.

---

## Key Theoretical Insights (revised)

1. **Retrieval is free; reranking is the only gating decision.** On a
   desktop corpus, all three retrieval legs run in parallel for ~5 ms.
   The cross-encoder costs 60-250 ms. The optimal architecture runs all
   retrievers always and gates only the reranker.

2. **Per-document fusion is the core innovation.** Within a single query's
   result set, vary the contribution of each retrieval leg based on
   document length. Suppress SPLADE for long docs, trust it for short docs.
   This is novel and handles mixed-length corpora without corpus-level
   classification.

3. **Per-document reranker gating is the highest-value decision.** Apply
   cross-encoder only to documents within its context window. Skip it for
   long documents (preserve first-stage score) and for queries where the
   first stage is already confident (high WIG, low NQC).

4. **Post-retrieval score analysis replaces pre-retrieval prediction.**
   Instead of predicting which retriever will work (QPP-based routing),
   observe which retriever actually worked (score distribution analysis).
   This is strictly more informative and costs nothing extra since all
   legs already ran.

5. **Adaptive refinement has a smaller job.** Instead of learning which
   components to enable (large action space), it only tunes thresholds
   for SPLADE suppression and reranker gating (small, continuous action
   space). Converges faster with less data.

6. **Query classification is demoted, not eliminated.** It's a cheap
   input to the reranker gating decision (navigational → skip CE, saves
   60-100 ms; exploratory → trigger expansion), not an independent layer.

7. **The architecture is simpler but still multi-layered.** Authority
   shifted to post-retrieval gating as the primary router. Pre-retrieval
   signals (corpus stats, query type, QPP) are supporting inputs, not
   independent decision points. The layers are complementary — they feed
   the router, not compete with it.

---

## Critical Analysis: What the Design Missed (2026-03-10)

A pipeline audit against the actual codebase revealed six aspects the
routing design overlooked or under-specified.

### Gap 1: LLM Query Expansion is a routing decision, not just a flag

The design mentions expansion as a per-query-type toggle (exploratory →
enable, navigational → disable). But the actual expansion path is more
nuanced:

- Expansion fires **asynchronously** during the gRPC round-trip to Worker,
  with a hard 1500 ms budget (`EXPANSION_BUDGET_MS`).
- It's currently **preset-gated**: enabled for TEXT and SPLADE modes,
  disabled for VECTOR and HYBRID.
- The expanded query generates **morphological variants**, not semantic
  alternatives — so it helps BM25/SPLADE recall, not dense retrieval.

**What the router should decide**: Whether to **wait for** expansion results
before returning. If the first-stage WIG is high (confident retrieval),
expansion results can be discarded even if they arrive. If WIG is low
(uncertain), the router should wait up to the budget for expansion to
complete. This is a **latency-quality tradeoff** the current design doesn't
address — it only treats expansion as on/off.

### Gap 2: LambdaMART is a pre-CE reranking stage with routing implications

The design mentions LambdaMART briefly ("zero effect on long docs") but
doesn't model it as a routing decision. In the actual pipeline:

- LambdaMART fires **before** cross-encoder (2-stage cascade)
- It uses only 2 features: normalized sparse score + normalized vector score
- It's extremely fast (<10 ms) and always runs on CPU
- It's trained on synthetic GPL triples, not real relevance data

**Routing implication**: LambdaMART's output could serve as a **CE gating
signal**. If LambdaMART's top-ranked document has high confidence (large
score gap to rank-2), the CE can be skipped — LambdaMART already found the
answer. This is a form of cascade early-exit (SEE, SIGIR 2025) using the
fast reranker as a confidence estimator for the slow one. The design's
WIG/NQC gating operates on first-stage scores, but LambdaMART scores are
strictly more informative since they've already done learned reranking.

### Gap 3: Chunk-aware merge interacts with per-document fusion

The per-document fusion logic varies SPLADE weight based on document length.
But the chunk merge step **runs in the Worker after retrieval** and collapses
chunk results back to parent documents. This creates an ordering problem:

- Chunks are typically short (<512 tokens) — SPLADE should be trusted
- Their parent documents may be long (>4096 tokens) — SPLADE should be
  suppressed
- The per-document fusion sees chunks, not parents, at fusion time
- After chunk merge collapses to parents, the document-length-based
  weighting should use the **parent's** length, not the chunk's length

**The design must specify**: Per-document fusion operates on the **parent
document token count**, not the chunk token count. The `docTokenCount`
DocValues field must store the parent document's length, even for chunk
documents. Otherwise every chunk looks "short" and gets full SPLADE
weight, defeating the purpose.

### Gap 4: Zero-result retry creates a second routing opportunity

When the first search returns zero results, the pipeline has a fuzzy
correction retry path. The current design doesn't account for this:

- The corrected query may have very different QPP characteristics
- The routing decision (which legs, which reranker) may be different for
  the corrected query
- Currently, the retry uses the same pipeline config as the original query

**Optimal behavior**: On zero-result retry, re-evaluate the routing decision
with the corrected query. A fuzzy-corrected query likely has lower
precision needs (the user misspelled), so dense retrieval and expansion
become more valuable.

### Gap 5: Deadline budgeting constrains the router

The cross-encoder has a configurable deadline budget (default 200 ms,
`JUSTSEARCH_RERANK_DEADLINE_MS`). The reranker self-gates at 70% of
deadline for pre-inference prep and 50% for tokenization.

The design's per-document CE gating must respect this deadline:

- If 15 of 20 results are short (eligible for CE), reranking all 15 may
  exceed the deadline budget
- The router should **prioritize** which eligible documents to rerank
  within the deadline, not just gate by length
- Optimal: rank CE-eligible documents by expected reranking value
  (e.g., those with smallest first-stage score gap benefit most) and
  process in that order until deadline

This connects to "Reranker-Guided Search" (arXiv:2509.07163) — selecting
which documents to rerank within a fixed budget.

### Gap 6: Agent path has different routing needs

The agent search path uses the same pipeline but has different goals:

- Agent searches are **intermediate** (feeding an LLM context window), not
  **terminal** (showing results to the user)
- Context quality (evidence coverage) matters more than ranking precision
- The agent can issue **multiple searches** with different configs
- The agent already accepts custom pipeline JSON via `SearchTool`

**Routing implication**: The agent should use a different routing policy.
Higher recall is more valuable (the LLM can filter irrelevant results).
Cross-encoder reranking has less value (the LLM does its own relevance
assessment). The optimal agent routing might be: always run all retrieval
legs, skip all reranking, return more results (top-20 instead of top-10).

This is essentially two routing profiles: **interactive** (user-facing,
precision-focused, reranker-gated) and **agentic** (LLM-facing,
recall-focused, reranker-skipped).

### Gap research results (2026-03-10)

Literature search for each gap:

**Gap 1 — Expansion timing:** The async-race pattern (fire expansion, discard
if retrieval is confident) is a **novel synthesis** of three established ideas:
training-free adaptive retrieval gating (TARG, arXiv:2511.09803 — top-1/top-2
logit gap as gating signal) and per-query RAG configuration (METIS, SOSP 2025 —
1.64-2.54x latency reduction by adapting per-query). TARG (arXiv:2511.09803)
uses top-1/top-2 logit gap as a retrieval gating signal — structurally
identical to score gap for expansion gating. The specific "speculative async
execution with confidence-based discard" framing is not named in literature.

**Gap 2 — LM-gates-CE cascade:** Well-established. SEE (SIGIR 2025) and
AcuRank (NeurIPS 2025) formalize cascade early exit with confidence-based
gating. LCRON (ICML 2025, arXiv:2503.09492) trains the entire cascade as
one network. Our BM25/RRF → LambdaMART score gap → cross-encoder pattern
is a direct instantiation of cascade early exit, not novel.

**Gap 3 — Parent metadata on chunks:** Well-covered by production systems.
Vespa's `import field` from global parent documents is the cleanest: parent
type with `global=true` is replicated to all nodes, chunks import parent
fields (including length) for use in ranking expressions. Vespa's
`elementwise(bm25(...))` calculates per-chunk BM25 without the array-level
`dl` distortion that Elasticsearch suffers from. Elasticsearch does it via
nested objects or join fields. LlamaIndex reweights chunks by parent-document
similarity. HRR (arXiv:2503.02401) preserves parent context through
hierarchical chunk structure. **No standalone academic formalization** of
"parent document length as a chunk-level scoring feature" — treated as
engineering concern.

**Gap 4 — Zero-result re-routing:** CRAG (arXiv:2401.15884) is the closest:
a retrieval evaluator grades results and triggers different strategies
(keep/discard/supplement + web search). ReZero (arXiv:2504.11001) trains
the LLM itself to recognize retrieval failure and reformulate. Higress-RAG
combines dual hybrid retrieval with adaptive re-routing. **Gap in literature**:
no work on adjusting fusion weights per-retriever based on failure signals
(e.g., "dense returned zero → shift RRF weight toward BM25").

**Gap 5 — Budget-aware reranking:** Active research area. RGS
(arXiv:2509.07163) selects which docs to rerank within budget via graph
traversal (+3.5-5.1 nDCG). EcoRank (ACL 2024, arXiv:2402.10866) jointly
optimizes prompt strategy + API + budget split. Critically, "Drowning in
Documents" (arXiv:2411.11767) shows rerankers **degrade** past a threshold —
scoring more docs is not always better. This directly motivates our
selective per-document CE gating.

**Gap 6 — Agent vs interactive routing:** Emerging consensus, limited formal
study. "Agentic Information Retrieval" (Zhang et al., arXiv:2410.09713)
contrasts agentic IR with traditional IR as fundamentally different paradigms.
Azure AI Search and Elastic both publish distinct agent-oriented retrieval
configurations. "Evaluating Precision and Recall at Retrieval Time in RAG
Systems" (2025) confirms recall is more critical for RAG because missing
content → incomplete answers, while mild noise is LLM-tolerable. METIS
(SOSP 2025) shows RAG-specific configuration adaptation yields measurable
gains. **No landmark paper** defining optimal retrieval config for RAG vs
interactive — more engineering consensus than formalized research.

### Revised summary of gaps

| Gap | Impact | Literature status | Resolution |
|-----|--------|------------------|------------|
| 1. Expansion timing | Medium | Novel synthesis (TARG + METIS) | Add expansion-wait to post-retrieval gating via score confidence |
| 2. LM-gates-CE cascade | Medium | Well-established (SEE 2025, AcuRank 2025, LCRON 2025) | Use LM rank-1/rank-2 score gap as CE gating signal |
| 3. Chunk vs parent length | High | Engineering pattern (Vespa import field) | Per-doc fusion uses parent `docTokenCount`, stored on chunks |
| 4. Zero-result re-routing | Low | Partially covered (CRAG); fusion-weight adjustment novel | Re-evaluate routing on corrected query |
| 5. Budget-aware reranking | Medium | Active research (RGS, EcoRank, Drowning in Docs) | Prioritize CE docs by expected reranking value within deadline |
| 6. Agent vs interactive | Medium | Emerging consensus (Agentic IR, METIS) | Two routing profiles: interactive (precision) vs agentic (recall) |

---

## Extended Research: Missing Perspectives (2026-03-10)

A critical self-analysis identified 10 underexplored perspectives. Each was
researched against current literature (2024-2026). This section summarizes
findings and their impact on the architecture.

### 1. Result Diversity — Post-Fusion, Not Routing

**Verdict: Add as post-fusion step. Does not change routing architecture.**

Literature unanimously treats diversity as post-retrieval processing, not
pre-retrieval routing. MMR (Maximal Marginal Relevance) and its successors
operate on an already-fused candidate set.

Key findings:
- **SMMR** (Anan'eva et al., SIGIR 2025): Sampling-based MMR outperforms
  greedy MMR — better diversity-relevance Pareto frontier, lower latency.
  Drop-in replacement for traditional MMR.
- **Cross-encoder hurts diversity** (indirect evidence, AGH IR/LongEval
  CEUR-WS 2024): CE optimizes pointwise relevance, not listwise diversity.
  Mitigation: apply MMR *after* CE scoring, using CE scores as the relevance
  input to the diversity reranker.
- **DIVERGE** (arXiv:2602.00238, 2026): RAG-specific diversity framework
  shows ~1.6x semantic diversity improvement. Identifies "diversity collapse"
  as a real failure mode in LLM-backed systems.

**Design addition**: After per-document fusion + optional CE reranking, apply
SMMR as a final reranking step. Lambda (relevance vs diversity tradeoff)
driven by query intent: exploratory queries get more diversity, navigational
queries get none. Pipeline becomes: retrieve → fuse → CE → SMMR → return.

### 2. Dense Retrieval Blind Spots — Per-Document Fusion Signal

**Verdict: Extends per-document fusion with new signals. Reinforces architecture.**

Dense embeddings have documented, systematic failure modes:

- **Granularity dilemma** (Li et al., EMNLP Findings 2025, arXiv:2506.08592):
  Embeddings cannot simultaneously capture semantic similarity AND fine-grained
  entity distinctions. Fundamental limitation regardless of model scale.
- **Theoretical impossibility** (Weller et al., DeepMind, arXiv:2508.21038,
  Aug 2025): For embedding dimension d, certain top-k subsets *cannot be
  returned by any query*. Mathematical ceiling, not a training gap.
- **Numeracy gap** (arXiv:2509.05691, Sep 2025): Embeddings cannot distinguish
  numerical values. "Market share grew by 2%" ≈ "grew by 20%" in embedding
  space. Affects finance, measurements, version numbers, code.
- **BEIR domain sensitivity** (Kamalloo et al., SIGIR 2024): Specialized
  terminology (biomedical, legal) causes dense to underperform BM25 by
  7-17 nDCG points.

**Per-document signals for dense score trust** (analogous to doc-length for
SPLADE):
- **Vocabulary rarity**: High proportion of rare/domain-specific terms →
  downweight dense. BM25's IDF handles these naturally.
- **Numerical density**: Documents heavy in numbers → downweight dense.
  Detectable via simple regex scan at index time.
- **Document length**: Very short docs (<20 words) → insufficient embedding
  signal. Very long docs → chunking artifacts.

**Design refinement**: Per-document fusion already varies SPLADE weight by
document length. Extend to also vary dense weight by content characteristics:

```
for each candidate document in merged result list:
    length = docValues.get(docId, "docTokenCount")
    numDensity = docValues.get(docId, "numericalDensity")  // optional

    // SPLADE trust (existing)
    spladeWeight = length < 1024 ? 1.0 : length > 4096 ? 0.0 : 0.5

    // Dense trust (new)
    denseWeight = 1.0
    if numDensity > 0.3: denseWeight *= 0.5   // high-number doc
    if length < 50: denseWeight *= 0.5         // too short for embedding

    finalScore = convexCombine(bm25, dense * denseWeight, splade * spladeWeight)
```

This is a natural extension of per-document fusion, not a new layer.

### 3. Session Context — Not a Routing Signal for Desktop

**Verdict: Dismissed. No architecture change.**

Research consensus: routing decisions are driven by per-query features
(complexity, intent), not session history.

Key findings:
- **MTRAG** (IBM, ACL 2025): Multi-turn RAG struggles, but the gap is in
  query understanding (anaphora, coreference), not routing strategy.
- **RAGRouter** (arXiv:2505.23052), **FAIR-RAG** (arXiv:2510.22344),
  **Self-Routing RAG** (arXiv:2504.01018): All route based on per-query
  complexity classification, not session history.
- **Desktop search sessions are short** (typically 1-3 queries). Insufficient
  signal for session-aware adaptation.

If session context is ever added, it belongs in query expansion (pre-retrieval
enrichment), not in retrieval routing.

> **Historical** — Round 4 research revised the placement: recency should be
> applied AFTER reranking (not in fusion), as an additive signal. CE has
> inherent recency bias; applying before doubles the effect. See "Consolidated
> Final Design" for the authoritative approach.

### 4. Freshness/Recency — Scoring Signal in Fusion

**Verdict: Add as fusion feature. Does not change routing architecture.**

Universal consensus: recency is a **scoring signal** applied during fusion,
not a routing signal that selects retrievers.

Key findings:
- **LLM rerankers have uncontrolled recency bias** (Fang & Tao, SIGIR-AP
  2025, arXiv:2509.11353): Up to 95 rank positions shift and 25% preference
  reversal from injected dates. If using LLM/CE reranking, recency is already
  baked in uncontrollably.
- **Time-decay functions** (Elasticsearch, OpenSearch): Exponential, Gaussian,
  linear decay with configurable origin/scale/offset. Exponential is most
  common for recency.
- **Re3** (arXiv:2509.01306, Sep 2025): Query-aware temporal gating —
  some queries are time-sensitive ("latest release notes"), some are not
  ("how to configure SSL"). Fixed decay is suboptimal; detect temporal intent.

**Design addition**: Add recency as a per-document feature in the fusion
score. Exponential decay on `lastModified` timestamp with generous offset
(30 days at full score). Apply *before* CE reranking to avoid double-counting
the CE's inherent recency bias. Document-type-aware decay (emails decay
faster than reference docs) is desirable but can be deferred.

> **Historical** — The CC-replaces-RRF finding is correct, but the pseudocode
> below uses multiplicative recency (superseded by additive post-rerank in
> Round 4) and lacks per-document re-normalization. See "Consolidated Final
> Design" for the authoritative fusion formula.

### 5. Convex Combination Replaces RRF

**Verdict: Change default fusion method. Updates architecture.**

Bruch et al. (ACM TOIS 2024) is confirmed as the definitive study:

- **CC consistently outperforms RRF** across all tested datasets, in-domain
  and out-of-domain.
- **Normalization-agnostic**: Min-max, z-score, or ZMUV all work. Min-max
  to [0,1] is simplest and what Elasticsearch uses.
- **Single parameter** (alpha): More robust than RRF's k parameter. Easier
  to tune with limited data.
- **CombMNZ with ZMUV** is the strongest alternative: rewards multi-retriever
  consensus. Naturally gives higher weight to documents returned by multiple
  methods — a form of implicit per-document gating.

**Interaction with per-document weight variation**: CC applies a single alpha
globally. For per-document variation (varying SPLADE/dense contribution by
doc characteristics), treat the per-document weights as adjustments to
individual retriever scores *before* the global CC fusion. Alternatively,
CombMNZ naturally handles per-document variation through its consensus
mechanism.

**Fusion matters less with CE downstream**: If CE reranks top-N, the fusion
method mainly affects the recall ceiling (which documents enter top-N).
CC produces better ordering → better recall ceiling for the reranker.

**Updated fusion pseudocode**:
```
// Normalize each retriever's scores to [0,1] via min-max
bm25_norm = minMaxNormalize(bm25_scores)
dense_norm = minMaxNormalize(dense_scores)
splade_norm = minMaxNormalize(splade_scores)

for each candidate document:
    // Per-document weight adjustments
    sw = spladeWeight(docLength)    // 0.0-1.0 based on doc length
    dw = denseWeight(docFeatures)   // 0.0-1.0 based on content type

    // Convex combination (alpha tuned per corpus, default 0.4/0.3/0.3)
    score = alpha_bm25 * bm25_norm
          + alpha_dense * dense_norm * dw
          + alpha_splade * splade_norm * sw

    // Optional: recency decay
    score *= recencyDecay(doc.lastModified)
```

### 6. Decision Composition — Strict Funnel, No Conflicts

**Verdict: Validates architecture. No change needed.**

Production systems (Vespa, Elasticsearch) universally use strict funnel
architectures where each phase fully determines input to the next. There
is no "conflict resolution" mechanism because conflicts cannot arise in
a strict funnel.

Key findings:
- **Vespa three-phase ranking** (docs.vespa.ai): first-phase → second-phase
  → global-phase. Each is a strict filter. Conflicting signals resolved by
  GBDT models, not priority rules.
- **Full Stage Learning to Rank** (ACM Web 2024, arXiv:2405.04844): GPRP
  formalizes that each stage should optimize for downstream selection bias,
  not just local relevance. Fusion weights should account for what CE tends
  to promote.
- **No formal framework exists** for parallel decision composition with
  conflict resolution. The literature avoids the problem by using sequential
  funnels.

**The tempdoc 270 architecture is already a strict funnel**:
retrieve-all → fuse (per-doc weights) → gate/rerank → return. The three
"decisions" in the post-retrieval router (per-doc fusion, per-doc CE, query-
level budget) execute sequentially, not in parallel. No conflicts arise
because:
1. Per-document fusion produces a score (no gating decision)
2. Per-document CE gating filters which docs enter CE
3. Query-level budget caps total CE computation

If query-level budget says "skip all reranking" (high WIG), per-doc CE
gating is moot. If per-doc CE says "this doc is too long," the query-level
budget's total decreases. Sequential, not conflicting.

### 7. Evaluation — QPP as Both Reward and Evaluation Is Circular

**Verdict: Adds evaluation methodology. Important design constraint.**

Using QPP (WIG/NQC) as both the routing reward signal AND the evaluation
metric creates a self-grading problem.

Key findings:
- **QPP has fundamental limitations** (Faggioli et al., ACM TOIS 2025,
  arXiv:2504.01101): QPP-driven selective processing achieves only ~4% NDCG
  improvement with unreliable cross-setting robustness.
- **LLM-as-judge for pairwise comparison** (Thomas et al., Microsoft SIGIR
  2024; Turnbull 2025): Local LLMs can compare "results with CE" vs "results
  without CE" via pairwise preference. More reliable than pointwise grading.
- **Run-all-then-gate has a hidden evaluation advantage**: Because both paths
  execute, you get free counterfactual data on every query. This is equivalent
  to a permanent A/B test with 100% overlap — impossible at web scale but
  trivial for a single-user desktop system.
- **Counterfactual/off-policy evaluation** (Buchholz et al., SIGIR 2024;
  Bibaut et al., ACM/IMS JDS 2024): Theoretically sound but impractical
  for single-user systems due to variance from sparse data.

**Evaluation methodology for the routing architecture**:
1. **Primary**: Shadow comparison. Since all retrieval legs always run, log
   both "with gating" and "without gating" result sets. Periodically compare
   via pairwise LLM-judge on a random sample.
2. **Secondary**: Temporal hold-out. Track whether QPP predictions correlate
   with observable quality differences over time.
3. **Tertiary**: Implicit feedback alignment. When click/dwell data exists,
   check if gating decisions correlate with user engagement.

### 8. Error Cost Asymmetry — Conservative Gating Default

**Verdict: Changes gating threshold philosophy. CE should almost never be skipped.**

The cost of a false negative (skipping CE when it would have helped) is
permanently degraded results the user sees. The cost of a false positive
(running CE unnecessarily) is ~100ms wasted latency.

Key findings:
- **"Rerank Before You Reason"** (arXiv:2601.14224, 2026): Moderate reranking
  yields larger gains than increasing search-time reasoning budget.
- **Desktop latency tolerance** (Arapakis et al., SIGIR 2014; Arapakis &
  Park 2021): 100-200ms CE latency is below perceptibility for desktop
  search where baseline is already 200-500ms.
- **Adaptive computation > binary gating** (Busolin et al., SIGIR 2025;
  AcuRank, NeurIPS 2025): Instead of "run CE or not?", adjust CE computation
  depth per-query. Early-exit when confidence is high. Eliminates binary
  gating entirely.
- **Production systems default to always reranking** (Anthropic Contextual
  Retrieval 2024; Pinecone; Cohere): Optimize N (how many docs to rerank),
  not whether to rerank at all. Sweet spot: 50-75 documents.
- **No formal cost-sensitive gating framework exists** in the retrieval
  literature. This is a gap.

**Design revision**: The WIG/NQC-based "skip reranking entirely" gate
should have an extremely high threshold — fire only when first-stage results
are overwhelmingly confident. In practice, on a desktop system with one
user, CE latency is negligible. The gating optimization is premature.

Prefer adaptive CE (early-exit) over binary gating when technically feasible.
This converts the binary "run or skip" decision into a continuous "how much
computation" decision, which is strictly better.

### 9. Thompson Sampling Risks — Demoted Further

**Verdict: TS is risky for single-user desktop. Run-all-then-fuse sidesteps the problem.**

Key findings:
- **TS degenerates faster than UCB** with sparse feedback (Mansoury et al.,
  AAAI/ACM AIES 2019, arXiv:1902.10730): With limited observations, TS
  converges prematurely to whatever arm appeared best early.
- **QPP-as-reward is a self-evaluating oracle** (QPP++ 2025 Workshop; SIGIR
  ICTIR 2025): If the QPP predictor overestimates dense retrieval quality,
  TS routes more queries to dense AND reports that routing as successful.
  Circular dependency with no escape mechanism.
- **Sliding-window TS** (Baudry et al., JAIR 2024, arXiv:2409.05181):
  Forgetting old observations prevents stale convergence after corpus
  changes. Window of ~100-200 queries.
- **Sparse feedback severely limits TS** (arXiv:2501.03999, 2025): With
  <50 observations per arm, TS shows "little or no advantage" over uniform
  random.
- **Run-all-then-fuse eliminates the worst failure modes**: Because all
  retrieval strategies execute on every query, you always have counterfactual
  data. This converts arm-selection (high risk) into weight-learning (low
  risk). Much better behaved with sparse feedback.

**Revised adaptive refinement design**:
- Primary mechanism: **tune fusion weights** (alpha_bm25, alpha_dense,
  alpha_splade, gating thresholds), not component on/off decisions.
- If TS is used at all: sliding-window with gamma=0.95-0.99 per query.
  Never hard-reset; gradually forget old observations.
- Exploration budget: 10-20% forced random variation in first 300 queries,
  decaying over time.
- **Preferred alternative**: Deterministic run-all-then-fuse with quality
  improvements from better fusion weights (tuned via shadow evaluation),
  not learned routing.

### 10. Multi-Field Routing — Internal to BM25F

**Verdict: Field boosting is a BM25F parameter, not a top-level routing decision.**

Key findings:
- **BM25F is the standard** for multi-field sparse retrieval (Weaviate, ES,
  Solr, Lucene). Field weights are baked into the scoring function during
  retrieval, not a post-hoc adjustment.
- **Title boosting of 5-10x** over body is industry standard (Elastic App
  Search guide 2024, SearchBlox).
- **Vespa supports dynamic per-query field weights** via ranking expressions
  with query-time parameters (docs.vespa.ai).
- **Dynamic field boosting outperforms static** when training data exists.
  Without training data, query-type-based heuristics (navigational → boost
  title/path) are the practical middle ground.
- **Dense/SPLADE should NOT be per-field** — cost of separate per-field
  retrievals is not justified. Let BM25F handle field discrimination.

**Design addition**: Add a lightweight pre-retrieval step that adjusts BM25F
field weights based on query type classification:

| Query type | title | path | metadata | body |
|------------|-------|------|----------|------|
| NAVIGATIONAL | 20 | 15 | 5 | 1 |
| EXACT_MATCH | 15 | 10 | 3 | 1 |
| INFORMATIONAL | 5 | 1 | 2 | 3 |
| EXPLORATORY | 3 | 1 | 2 | 5 |

This is a parameter to BM25F, executed within the sparse retrieval leg.
It does not affect the top-level routing architecture.

### Summary: What changed

| Finding | Architecture change |
|---------|--------------------|
| Result diversity | Add: SMMR post-fusion step |
| Dense blind spots | Extend: per-doc fusion includes dense weight |
| Session context | None (dismissed for desktop) |
| Recency | Add: decay function in fusion score |
| Convex combination | **Change: CC replaces RRF as default** |
| Decision composition | None (validates strict funnel) |
| Evaluation | Add: shadow evaluation methodology |
| Error asymmetry | **Change: CE gating threshold → very conservative** |
| TS risks | **Change: TS demoted; prefer deterministic fusion tuning** |
| Field routing | Add: BM25F field weights by query type |

---

## Operational Constraints & Interactions (Round 3, 2026-03-10)

A third analysis round resolved 17 remaining uncertainties via codebase
investigation and internet research. This section documents findings that
affect the architecture or constrain implementation.

### GPU Scheduling — Signal Bus Is Correct

**Codebase finding**: CE reranker (`CrossEncoderReranker.java:81-313`) and
SPLADE encoder (`SpladeEncoder.java:79-439`) both use lazy GPU sessions
with a `shouldUseGpu` callback tied to `WorkerSignalBus.isMainGpuActive()`.
When the LLM (Brain process) claims GPU, both yield and fall back to CPU.
No explicit queue between them.

**Internet research**: ONNX Runtime sessions on the same GPU are effectively
serialized at the CUDA stream level on consumer GPUs (single compute queue).
Memory arenas don't release between calls without explicit
`memory.enable_memory_arena_shrinkage` configuration. A 7B LLM at Q4
needs ~4-5GB VRAM; CE + SPLADE need ~700MB-1GB; no room for all three
concurrently on an 8GB card.

**Verdict**: Current signal-bus yield pattern is the right approach.
No architecture change. Consider enabling arena shrinkage for sessions
that yield to the LLM.

### Index Scaling — "Free Retrieval" Valid to ~500K Documents

**Codebase finding**: HNSW config is M=16, efConstruction=200, efSearch=100,
768-dim cosine similarity (`JustSearchCodec.java:31-35`,
`LuceneIndexRuntime.java:108`). With Float32: 768 × 4 = 3,072 bytes/doc;
Int8 quantization available.

**Internet research**: At 100K docs (768d), ~5ms is accurate. At 1M docs,
expect ~8-15ms. Memory is the binding constraint: 1M vectors × 768 × 4B
= ~2.9GB vectors + ~1GB HNSW graph = ~4GB. On a 16GB desktop this is
tight at 1M+. "Retrieval is free" breaks down at ~500K-1M documents.

**Verdict**: Architecture valid as designed for corpora up to ~500K docs.
At larger scales, consider Int8 quantization and adaptive efSearch. Add
a monitoring note: if corpus exceeds 500K docs, evaluate retrieval latency.
No architecture change at current scale.

### Partial Index — Fusion Handles It, With Known Bias

**Codebase finding**: `HybridFusionUtils.java:252-363` implements CC fusion
with a `zeroExclude` flag. Documents with only BM25 get only the BM25
component; documents with only dense get only dense. Per-document graceful
degradation is already implemented.

**Internet research**: CC creates systematic bias against embedding-missing
docs (their fused score is ~1/alpha_bm25 of what it would be with all
legs). RRF is more tolerant. However, CE reranking downstream mitigates
this since it re-scores from text regardless of fusion scores.

**Verdict**: Current behavior is acceptable. The bias is temporary (resolves
once embeddings complete) and CE mitigates it. For large batch ingestion,
consider boosting BM25-only docs by `1/alpha_bm25` to compensate. Not
a priority.

### Threshold Empirical Basis

**Codebase finding** (tempdoc 251 data):

| Threshold | Value | Empirical basis | Confidence |
|-----------|-------|-----------------|------------|
| SPLADE off | > 4096 tokens | courtlistener: 0.083 vs 0.889 nDCG | Very strong |
| SPLADE full | < 1024 tokens | 5/6 BEIR datasets; 512-token truncation limit | Strong |
| SPLADE 0.5 | 1024-4096 | Linear interpolation, no data in range | Medium (intuited) |
| CE skip | > 512 tokens | courtlistener: -0.606 nDCG delta | Very strong |
| Dense reduction | < 50 words | No data <200 tokens in Phase 5 | Weak (intuited) |

**Key gaps**: No per-document CE gating validation (Phase 5 tested uniform
CE on/off, not selective). No SPLADE measurements at 1K/2K/3K granularity.

**Internet research**: QPP is unreliable for auto-tuning thresholds (Faggioli
et al., TOIS 2025 — only ~4% NDCG improvement). **Synthetic query bootstrap**
is feasible: generate queries from doc chunks via local LLM, run through
pipeline with varying thresholds, select the threshold maximizing rank
correlation across nearby values. REFINE (arXiv:2410.12890) validates
this approach.

**Verdict**: Hard thresholds (512 CE, >4096 SPLADE) are data-backed and
should remain. Soft thresholds (1024 SPLADE, 50-word dense) need empirical
validation via synthetic-query sweep. Make all thresholds configurable.

### Alpha Calibration — Flat Optimum, Shadow Eval for Tuning

**Internet research**: Bruch et al. (TOIS 2024) confirms CC's optimum is
**broad/flat** — ±10% error on alpha values causes no significant quality
degradation. This is good for a zero-label desktop system: a reasonable
default won't fall off a cliff.

Industry defaults for 2-way hybrid: 0.5-0.7 dense weight (Weaviate).
For 3-way, no industry standard. Default of 0.4/0.3/0.3 (BM25/dense/
SPLADE) is reasonable for desktop search where exact-match matters.

**Tuning methodology for single-user**: Shadow logging. Log alternative-
config results alongside served results. After ~50-100 queries with
file-open signals, compute preference and auto-adjust. Interleaving
(Team Draft) is feasible for single-user (Airbnb, arXiv:2508.00751).
Synthetic query bootstrap at first-index time for initial calibration.

**Verdict**: No architecture change. Default alphas are sufficient. Add
shadow logging infrastructure for eventual self-tuning.

### Expansion Changes ALL Retrieval Legs

**Codebase finding**: Expansion runs as a **second search** with expanded
query (`KnowledgeHttpApiAdapter.java:501-512`). The original query goes to
the first search; the expanded query goes to a new gRPC request that runs
**all enabled legs** (BM25, dense, SPLADE) with the expanded terms. Expanded
terms are morphological variants, truncated to 3× original token count.

**Internet research**: BM25 scores shift dramatically with expanded queries
(additive across terms; rare variants get disproportionate IDF). Static
alpha weights calibrated on non-expanded queries will be miscalibrated.
DAT (arXiv:2503.23013) shows per-query alpha helps by ~2-3% P@1.

**Verdict**: **Architecture refinement needed**. Use separate alpha presets
for expanded vs non-expanded queries. The expanded-query alpha should give
slightly less weight to BM25 (expansion inflates BM25 scores with noisy
morphological variants). Alternative: down-weight expansion terms in the
BM25 query itself (boosted query syntax).

### Diversity + Expansion Are Complementary

**Internet research**: Expansion diversifies the **retrieval vocabulary**
(lexical broadening). SMMR diversifies the **result content** (topical
de-duplication). They operate on different dimensions and are complementary.

**Over-diversification risk**: Real for non-ambiguous queries. When both
are active on a clear query, precision drops. For genuinely exploratory
queries, both can coexist but SMMR lambda should soften when expansion
is already active (higher lambda = more relevance, less diversity).

**Verdict**: Keep both active for EXPLORATORY queries, but make SMMR
lambda expansion-aware: lambda = 0.6-0.7 when expansion active (vs
0.3-0.5 without expansion). Minor parameter tuning, not architectural.

### CE Sees Text, Not Scores — Per-Doc Fusion Is Transparent to CE

**Codebase finding**: CE receives `query + title + snippet` as raw text
(`KnowledgeHttpApiAdapter.java:606-623`, `CrossEncoderReranker.java:340-
417`). It has zero knowledge of fusion scores, per-document weights, or
which retrieval leg contributed. CE re-scores purely from text.

**Verdict**: The concern about per-doc fusion biasing CE input is
**unfounded**. CE operates independently. No architecture change.

### Near-Duplicates Need Dedup Before Fusion

**Codebase finding**: JustSearch has chunk consolidation (collapse by parent
doc ID, `SearchOrchestrator.java:834-905`) but **no cross-document
deduplication**. File copies, email thread duplicates, and versioned
drafts all appear as separate results.

**Internet research**: Near-duplicates inflate WIG/NQC — TREC analysis
found 16.6% of relevant docs were content-equivalent, causing 20% MAP
score decrease when de-duplicated (PMC:7148013). Given QPP already
achieves only ~4% marginal improvement, duplicate-inflated scores easily
flip gating decisions incorrectly.

**Verdict**: **Architecture addition needed**. Add SimHash fingerprints
computed at ingest time (64-bit, stored as DocValues). After retrieval
and before fusion, collapse documents with Hamming distance ≤ 3 into a
single representative. Cost: negligible at desktop scale. WIG/NQC gating
logic must operate on deduplicated result lists only.

### Degenerate Queries Handled Gracefully

**Codebase finding**: `SearchOrchestrator.java:214-224` returns early on
blank queries. `SearchOrchestrator.java:619-643` returns `QppMetrics.ZERO`
for blank, stopword-only, or zero-doc queries. Long queries are truncated
at SPLADE tokenization (`SpladeEncoder.java:233-238`).

**Verdict**: Already handled. The query classification (NAVIGATIONAL,
INFORMATIONAL, EXACT_MATCH, EXPLORATORY) will default to INFORMATIONAL
for edge cases, which triggers the full pipeline — safe but suboptimal.
No architecture change needed.

### Snippet Quality Unaffected by SPLADE Suppression

**Codebase finding**: Snippets are driven by Lucene query semantics via
`HighlightingOps.java:54-230`. Highlights come from BM25 query terms
matched against stored content using Lucene's Matches API. SPLADE
produces sparse weight vectors, not term-level matches — it has **no
role in highlighting**. Dense retrieval also has no highlight capability.

**Verdict**: Suppressing SPLADE for long docs has **zero impact** on
snippet quality. BM25 query terms drive all highlighting regardless of
which retrieval legs are active. Non-issue.

### Homogeneous Corpus — Skip Per-Doc Routing

**Internet research**: No literature-backed threshold for corpus
homogeneity exists. Adaptive retrieval research focuses on per-query
adaptation, not per-corpus. Static weights work well when properly tuned
(BM42 thesis, diva-portal.org).

**Verdict**: Minor optimization. Compute length distribution stats at
ingest time (mean, stddev, CV). If CV < 0.3 (std dev < 30% of mean),
corpus is homogeneous enough for static fusion weights — skip per-doc
weight computation. Add an `isHomogeneous()` flag to corpus metadata.

### Dynamic Reranker N by Confidence

**Internet research**: "Drowning in Documents" (arXiv:2411.11767, updated
Jul 2025) confirms rerankers degrade past a threshold. Production systems
rerank 20-50 candidates, output 5-10. Optimal N is **query-dependent**:
high-confidence queries need fewer candidates, uncertain queries benefit
from more. "From Ranking to Selection" (arXiv:2508.09497) shows optimal
K varies by dataset (K=3-5 for output).

**Verdict**: **Architecture refinement**. Replace fixed N with configurable
range:
- Interactive: N_default=20, reduce to 10-15 for high WIG (after dedup)
- Agent/RAG: N_default=30-50, reduce to 20 for high WIG
- Output count: 10 for interactive, 20+ for agent

### Caching — Low Priority at 5ms Retrieval

**Internet research**: At sub-10ms retrieval, caching provides negligible
user-perceived latency benefit. The only scenario: rapid-fire as-you-type
queries where CPU savings accumulate. Cache hit rates in personal search
are modest (~20-30% for semantic similarity). Cache invalidation on
evolving corpora adds complexity.

**Codebase finding**: Query cache explicitly disabled in
`ComponentsFactory.java:366`. Only embedding cache exists (5s TTL,
`EmbeddingService.java:74`).

**Verdict**: Not worth implementing now. 5ms retrieval is already below
perception threshold. Can be added later as a pure optimization layer
without structural changes.

### Multi-Model Retrieval — Impractical for Desktop

**Internet research**: RouterRetriever (AAAI 2025) shows domain-specific
experts outperform single models by +2-3 nDCG. CodeXEmbed (COLM 2025)
shows code-specific models outperform general by >20%. However, running
multiple embedding models on a consumer GPU with 8-12GB VRAM alongside
an LLM is impractical. Industry trend is toward unified multimodal
models (Jina v4), not multi-model systems.

**Verdict**: Stick with single embedding model. When a better unified model
(handling code + prose + tables) becomes available for local inference,
swap it in. The embedding model is already a swappable component. No
architecture change.

### User-Facing Modes Should Be Deprecated

**Codebase finding**: `expandPreset()` maps 4 modes (TEXT, VECTOR, HYBRID,
SPLADE) to `PipelineConfig`. Custom `PipelineConfig` override is already
supported for API consumers (`KnowledgeHttpApiAdapter.java:393-394`).

**Internet research**: No production desktop search product (Windows Search,
Spotlight, Everything, Google Desktop) exposes retrieval mode selection.
Enterprise search (Glean, Google Agentspace) uses automatic routing.
Research on algorithm transparency: "appropriate transparency" (showing
what happened, not asking what to do) builds trust.

**Verdict**: **Product-level change**. User-selectable modes should be
removed from the primary search UI. The routing architecture becomes the
sole decision-maker. Modes retained as API-level hints for developers and
debugging. Post-search, show a small indicator ("matched via keyword +
semantic") for transparency.

### Execution Metadata Already Comprehensive

**Codebase finding**: `KnowledgeSearchResponse.java:13-133` already tracks:
`spladeExecuted`, `crossEncoderApplied`, `lambdaMartApplied`,
`expansionApplied`, `chunkMergeApplied`, `correctionApplied`, per-hit
`HitProvenance` (BM25/SPLADE/dense ranks+scores, fusion method+score,
CE score), and `PipelineExecution` (per-stage latency, component status).

**Verdict**: Routing observability is **already partially implemented**.
The existing metadata is sufficient to explain routing decisions. To
support shadow evaluation, add: alternative (ungated) result ordering
alongside the gated result. No structural change needed.

### Summary: Round 3 Changes

| Finding | Architecture impact | Priority |
|---------|-------------------|----------|
| GPU scheduling | None (signal bus correct) | — |
| Index scaling | Valid to ~500K docs; note for future | Low |
| Partial index | Acceptable; CE mitigates bias | Low |
| Threshold basis | 512/4096 data-backed; 1024/50 need validation | Medium |
| Alpha calibration | Flat optimum; shadow eval for tuning | Low |
| Expansion + fusion | **Separate alphas for expanded queries** | Medium |
| Diversity + expansion | Soften SMMR lambda when expansion active | Low |
| CE sees text only | Non-issue (CE independent of fusion) | — |
| **Near-duplicate dedup** | **Add SimHash dedup before fusion** | **High** |
| Degenerate queries | Already handled | — |
| Snippet quality | Non-issue (BM25 drives highlights) | — |
| Homogeneous corpus | Optional skip for per-doc routing | Low |
| **Dynamic reranker N** | **Variable candidate count by confidence** | **Medium** |
| Caching | Low priority at 5ms | Low |
| Multi-model retrieval | Impractical for desktop | — |
| **User modes** | **Deprecate from primary UI** | **Medium** |
| Execution metadata | Already comprehensive | — |

### Resolved Theoretical Questions (Round 4, 2026-03-10)

Four remaining theoretical questions were researched against current literature.
All four resolved without requiring empirical evaluation.

#### CC Parameterization for 3 Retrievers

**Question**: How to extend Bruch et al.'s 2-retriever CC to 3 retrievers
with per-document weight modulation?

**Answer**: Use the **constrained simplex** (`alpha_1 + alpha_2 + alpha_3 = 1`).
This is the standard in both literature and production systems (Elasticsearch
linear retriever 8.18+, InfiniFlow Infinity, OpenSearch). With 3 retrievers,
the simplex has 2 free parameters (since alpha_3 = 1 - alpha_1 - alpha_2),
making grid search at 0.05 granularity (~200 configurations) tractable.

**Per-document modulation**: When applying document-specific weight adjustments
(e.g., suppressing SPLADE for long docs), re-normalize to `sum = 1`
**per-document**. This follows the Query-Document-Dependent Fusion (QDDF)
literature (Wistuba & Narasimhan): the constraint preserves score
interpretability and comparability across documents.

```
for each candidate document d:
    sw = spladeWeight(d.parentTokenCount)   // 0.0-1.0
    dw = denseWeight(d.contentFeatures)     // 0.0-1.0
    raw_bm25  = base_alpha_bm25
    raw_dense = base_alpha_dense * dw
    raw_splade = base_alpha_splade * sw
    total = raw_bm25 + raw_dense + raw_splade
    // Re-normalize to constrained simplex
    score = (raw_bm25/total) * bm25_norm
          + (raw_dense/total) * dense_norm
          + (raw_splade/total) * splade_norm
```

**Flat optimum**: InfiniFlow's 3-way evaluation found "negligible difference
between Weighted Sum and RRF for 3-way retrieval," suggesting the flat-optimum
finding from Bruch et al. partially extends. The BM25-SPLADE axis is especially
forgiving (high correlation between lexical signals). The dense-vs-sparse
balance may be more sensitive. Start with equal weights (0.33/0.33/0.33) and
coarse grid search.

**Tuning without labels**: Bayesian Optimization on the probability simplex
(Candelieri et al., Annals of Math & AI 2023) is formally developed and
outperforms standard BO with increasing dimensionality. Alternatively, Zendel
et al. (ACM Web 2024) demonstrate unsupervised search configuration using QPP
scores as a proxy for retrieval quality.

#### Recency as Post-Rerank Additive Signal

**Question**: Should recency be a multiplicative decay on the fusion score
or a separate fusion leg? When should it be applied?

**Answer**: Treat recency as an **additive signal, applied AFTER reranking**.

1. **Additive, not multiplicative**: Multiplicative decay (`score *= decay`)
   breaks CC normalization, makes the recency effect impossible to tune
   independently, and prevents "turning off" recency for non-temporal queries.
   Vespa, Re3 (arXiv:2509.01306), and the RAG freshness paper
   (arXiv:2509.19376) all treat freshness as an additive fusion leg.

2. **After reranking, not before**: Cross-encoder rerankers exhibit inherent
   recency bias — up to 95 rank positions shift and 25% preference reversal
   from injected dates (Fang & Tao, SIGIR-AP 2025). Applying freshness before
   CE would double-count. Apply it as a post-rerank score adjustment.

3. **Decay function**: Exponential decay `freshness(age) = 0.5^(age_days / halflife)`.
   Output naturally in [0,1]. Default halflife = 30 days. Offset = 7 days
   (documents younger than offset get freshness = 1.0).

4. **Weight**: `alpha_fresh = 0.05-0.10` (conservative start). The other three
   retriever alphas lose proportionally.

5. **Post-rerank formula**:
   ```
   final_score = (1 - alpha_fresh) * reranked_score + alpha_fresh * freshness(age)
   ```

6. **Query-level gating** (future): Detect time-sensitive queries ("latest",
   "recent", date references) and increase alpha_fresh (0.05 default → 0.20
   for temporal queries). Re3 shows fixed weights are suboptimal; adaptive
   gating is the optimum.

#### SMMR Lambda Calibration — Confirmed

**Question**: Are the proposed SMMR lambda values (0.3-0.5 exploratory,
0.6-0.7 with expansion, 1.0 navigational) well-calibrated?

**Answer**: **Yes — values match both academic literature and production defaults.**

| Setting | Proposed lambda | Literature support |
|---------|----------------|-------------------|
| Exploratory, no expansion | 0.3-0.5 | ES Labs "product discovery" range; Santos et al. SIGIR 2011 |
| Exploratory, with expansion | 0.6-0.7 | Justified: SPLADE expansion is aspect-broadening |
| Navigational/exact | 1.0 | Santos et al.: minimal diversity for navigational intent |

Production systems universally default to 0.5 (LangChain, Qdrant, Elasticsearch).
ES Labs recommends starting at 0.7 and tuning down for more diversity.
Sensitivity is moderate — the 0.3-0.7 range produces reasonable results with
no quality cliff. Less forgiving than CC alpha, but no sharp optimum.

The expansion-raises-lambda logic is **conditionally correct**: aspect-broadening
expansion (which SPLADE is — it adds synonyms and morphological variants across
semantic aspects) justifies softer diversity. Narrow expansion (staying in the
same semantic neighborhood) would not.

#### Synthetic Query Bootstrap Methodology

**Question**: How to calibrate fusion weights and routing thresholds without
relevance labels?

**Answer**: Generate synthetic queries from document chunks via the local 7B
LLM. InPars-v1 (SIGIR 2022) used 6B GPT-J; a 7B model is fully adequate.
Rahmani et al. (SIGIR 2024) demonstrated synthetic test collections achieving
Kendall's tau = 0.86 against human-judged rankings on nDCG@10.

**5-step methodology**:

1. **Generate**: Sample 200-500 document chunks stratified across topics.
   Prompt: "Given the following passage, generate a short keyword search query
   that someone would type to find this information." Use 2-3 few-shot examples
   matching desktop search style. Generate 3-5 candidates per chunk.

2. **Filter**: Run round-trip retrieval for each query. Keep only queries where
   the source chunk appears in top-10 results (round-trip consistency). Target:
   100-200 filtered queries minimum (50 is the statistical floor).

3. **Sweep**: Define a grid of fusion weights and routing thresholds. For
   3-retriever CC on the simplex at 0.05 granularity: ~200 configurations.
   Run all filtered queries through each configuration. Record full ranked
   lists.

4. **Select**: Use MRR (Mean Reciprocal Rank) with binary relevance (source
   chunk = relevant). Compute Kendall's tau between rankings at adjacent grid
   points. Select the **plateau center** — the region where MRR is near-maximal
   AND rank correlation with neighbors is highest (stable, not overfitting).

5. **Validate**: Hold out 20% of synthetic queries. Verify the selected
   configuration performs comparably on the held-out set. When real user queries
   become available (even 10-20), spot-check the selected thresholds.

**Key caution**: Synthetic queries are more specific and well-formed than real
user queries (Apple SIGIR 2024). Use keyword-style prompts (not natural-language
questions) to match desktop search patterns. Include some deliberately
ambiguous/partial queries to mitigate distribution mismatch. For **relative**
threshold calibration (comparing configurations), distribution mismatch matters
less than for absolute metric estimation.

#### Chunk Merge Placement — Fuse Before Collapse

**Question**: Where does chunk merge (collapsing chunk-level results to parent
documents) belong in the 8-stage pipeline? Before or after fusion?

**Answer**: **After fusion, before reranking.** The evidence is unanimous across
production systems and research.

**Production systems all fuse at chunk level before collapsing:**
- **Vespa**: `elementwise(bm25(...))` computes per-chunk BM25, fuses with
  per-chunk dense similarity, then aggregates to documents via layered ranking.
  No mode for pre-collapse fusion.
- **Elasticsearch**: RRF/linear combination runs first, `field_collapsing` runs
  after. ES|QL 9.2+ documents this explicitly: "top 10 results of the rrf
  retriever are computed and then collapsed."
- **LlamaIndex/LangChain**: Both retrieve and fuse at chunk level before
  mapping back to parent documents.

**Research confirmation:**
- **PARADE** (Li et al., ACM TOIS 2023, arXiv:2008.09093): Later aggregation
  consistently outperforms earlier aggregation. Preserving passage-level
  granularity through scoring yields better document ranking. Representation
  aggregation > score aggregation (MaxP/SumP) > early document-level.
- **DAPR** (Keshav et al., ACL 2024): 53.5% of retrieval errors from missing
  document context — but the solution is informing chunk scoring with parent
  metadata, not collapsing early. Early collapse prevents correct passage
  identification.
- **MaxP/SumP** (Zhang et al., ECIR 2021): Passage scores should be computed
  independently first, then aggregated — not the reverse.
- **HRR** (arXiv:2503.02401): Maintains chunk-level granularity through
  retrieval and reranking before expanding to parents.

**Why this matters for per-document fusion specifically:**
1. Per-doc weight modulation (suppressing SPLADE for chunks from long parents)
   is a chunk-level operation informed by parent metadata. It needs chunk-level
   scores to modulate. Collapsing first loses this granularity.
2. When BM25 finds chunk 3 and KNN finds chunk 7 of the same document, chunk-
   level fusion preserves all evidence before selecting the best representative.
3. After fusion, chunk merge uses **MaxP** (best fused chunk score per parent)
   to select the winning chunk — this is the standard aggregation strategy.

**Revised pipeline**: 8 stages (chunk merge was previously implicit):
```
PRE-RETRIEVAL → RETRIEVAL → DEDUP → FUSION (chunk-level) →
CHUNK MERGE (MaxP) → RERANKING → POST-RERANKING → BACKGROUND
```

Note: JustSearch's current pipeline does chunk merge BEFORE reranking but in
a different position (after retrieval, before fusion). The change moves fusion
upstream of chunk merge, which requires the fusion stage to operate on chunk-
level results with parent metadata accessible via DocValues.

### Final Architecture (incorporating all four rounds)

```
┌─────────────────────────────────────────────────────────┐
│  PRE-RETRIEVAL: Query classification + BM25F weights     │
│  - Classify: NAVIGATIONAL / INFORMATIONAL / EXACT /      │
│    EXPLORATORY (rule-based, <1 ms)                       │
│  - Adjust BM25F field weights by query type              │
│  - Detect expansion state for alpha preset selection     │
│  Cost: <1 ms                                             │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  ALWAYS RUN: All retrieval legs in parallel               │
│  BM25F + Dense KNN + SPLADE (IDF)                        │
│  Valid: ≤500K docs (~5 ms). Monitor at larger scale.     │
│  Cost: ~5 ms                                             │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  DEDUP: Collapse near-duplicates via SimHash              │
│  - 64-bit SimHash fingerprints computed at ingest time   │
│  - Hamming distance ≤ 3 → keep highest-scoring copy      │
│  - Operates per-retriever before fusion                  │
│  Cost: <0.1 ms (bitwise ops on top-100 results)          │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  FUSION: Chunk-level convex combination (CC)               │
│  - Operates on chunk-level results (not collapsed yet)   │
│  - 3-leg CC, constrained simplex (alphas sum to 1)       │
│  - Min-max normalize each retriever's scores to [0,1]    │
│  - Per-chunk SPLADE weight (by parent doc length via DV) │
│  - Per-chunk dense weight (by content characteristics)   │
│  - Re-normalize per-chunk to maintain sum=1 (QDDF)       │
│  - Alpha preset: standard vs expanded-query variant      │
│  - Skip per-doc weights if corpus is homogeneous (CV<0.3)│
│  Cost: <1 ms                                             │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  CHUNK MERGE: Collapse chunks to parent documents         │
│  - MaxP aggregation: best fused chunk score per parent   │
│  - Preserves winning chunk's text for CE input           │
│  - Parent metadata (title, path) from parent doc         │
│  - Non-chunked documents pass through unchanged          │
│  Cost: <0.1 ms                                           │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  RERANKING: Almost always run, adaptive computation       │
│  - Dynamic N: 10-20 (interactive) / 30-50 (agent)       │
│    adjusted by WIG confidence on deduplicated scores     │
│  - Per-document CE gating (skip docs > 512 tokens)       │
│  - Query-level: skip only if overwhelmingly confident    │
│  - Binary per-doc skip (early-exit infeasible in ONNX RT) │
│  - Optional cascade: small L-6 CE → full L-12 if needed  │
│  - LambdaMART score gap as secondary gating signal       │
│  Cost: 0-200 ms (typically 60-100 ms)                    │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  POST-RERANKING: Freshness + Diversity                     │
│  1. Freshness decay (applied AFTER CE to avoid            │
│     double-counting CE's inherent recency bias):          │
│     final = (1-α_fresh) * CE_score + α_fresh * freshness  │
│     freshness = 0.5^(age_days / 30), offset 7d            │
│     α_fresh = 0.05-0.10 default; 0.20 for temporal queries│
│  2. Greedy MMR diversity (literature-confirmed lambdas):  │
│     Exploratory: lambda 0.3-0.5 (no expansion)            │
│       or lambda 0.6-0.7 (with expansion — softer)         │
│     Navigational/exact: lambda 1.0 (no diversity)         │
│  Cost: <1 ms                                              │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  BACKGROUND: Calibration + evaluation + adaptation         │
│  A. Synthetic query bootstrap (first-index time):         │
│     Generate 500-1K queries → filter 100-200 via          │
│     round-trip → grid sweep on simplex → select plateau   │
│     center via MRR + rank correlation stability           │
│  B. Shadow logging: alternative-config results alongside  │
│     served results for counterfactual comparison           │
│  C. LLM-as-judge pairwise comparison (periodic sample)    │
│  D. Fusion weight tuning via shadow eval (~50-100 queries)│
│  E. TS only if implicit feedback exists + sliding window  │
└─────────────────────────────────────────────────────────┘
```

---

## Consolidated Final Design

> This section is the **single authoritative reference** for the optimal
> routing architecture. It supersedes all earlier architecture descriptions,
> policy tables, and pseudocode in the investigation log above.

### Pipeline Overview

The architecture has 8 stages. Total latency: 6-110 ms interactive,
6-260 ms agent (dominated by CE reranking).

1. **PRE-RETRIEVAL** (<1 ms): Rule-based query classification into
   NAVIGATIONAL / INFORMATIONAL / EXACT / EXPLORATORY. Adjusts BM25F field
   weights by query type (navigational → boost title/path; exploratory →
   boost body). Detects expansion state for alpha preset selection.

2. **RETRIEVAL** (~5 ms): All three legs run in parallel on every query —
   BM25F, Dense KNN (HNSW), SPLADE (IDF). No pre-retrieval leg selection.
   Valid for corpora ≤500K docs; monitor HNSW latency at larger scale.
   Returns chunk-level results (both whole-doc and chunk hits).

3. **DEDUP** (<0.1 ms): Collapse near-duplicates via 64-bit SimHash
   fingerprints (computed at ingest). Hamming distance ≤ 3 → keep highest-
   scoring copy. Operates per-retriever before fusion. Prevents QPP
   distortion from duplicate clusters.

4. **FUSION** (<1 ms): Chunk-level convex combination (CC) on the
   constrained simplex. Operates on chunks before collapse — preserves
   fine-grained scoring granularity (PARADE principle: later aggregation
   outperforms earlier aggregation). Min-max normalize each retriever's
   scores to [0,1]. Apply per-chunk weight modulation (SPLADE by parent
   doc length via DocValues, dense by content characteristics), then
   re-normalize to maintain sum=1. Expansion-aware alpha preset.
   See "Fusion Formula" below.

5. **CHUNK MERGE** (<0.1 ms): Collapse chunk-level fused results to parent
   documents via MaxP aggregation (best fused chunk score per parent).
   Preserves the winning chunk's text for CE input. Non-chunked documents
   pass through unchanged. This ordering (fuse then collapse) matches
   Vespa, Elasticsearch, and the DAPR/PARADE literature.

6. **RERANKING** (0-200 ms, typically 60-100 ms): Almost always runs.
   Dynamic candidate count N by WIG confidence: 10-20 interactive, 30-50
   agent. Per-document CE gating (skip docs >512 tokens, preserve fusion
   score). CE input is the winning chunk's text (title + snippet), which
   is at the optimal ~512-token granularity for MiniLM (HRR finding).
   Binary per-document skip (not early-exit — ONNX Runtime cannot stop
   mid-graph; see U1 resolution). Optional cascade: small L-6 CE first,
   full L-12 only for ambiguous scores. LambdaMART score gap as secondary
   CE gating signal. Skip CE entirely only when first-stage is
   overwhelmingly confident (very high WIG + very low NQC).

7. **POST-RERANKING** (<1 ms): Two steps applied sequentially:
   a. **Freshness**: Additive recency signal applied AFTER reranking to
      avoid double-counting CE's inherent recency bias. Formula:
      `final = (1 - alpha_fresh) * reranked_score + alpha_fresh * freshness(age)`.
   b. **Diversity**: Standard greedy MMR with query-intent-driven lambda
      (SMMR not needed at N < 50; see U2 resolution). Exploratory queries
      get diversity; navigational/exact queries get none. Reuse existing
      MMR implementation from `RagContextOps.java`.

8. **BACKGROUND** (async): Built on the existing GPL pipeline
   (`GplJobCoordinator`), which already generates synthetic queries via the
   local LLM, scores them with the cross-encoder, and stores per-doc
   sparse/vector/QPP features in `gpl-training-triples.ndjson`. Extensions:
   a. Synthetic query bootstrap for fusion weight calibration — post-process
      GPL triples to sweep alpha values on the simplex.
   b. Shadow logging of alternative configurations alongside served results.
   c. LLM-as-judge periodic pairwise comparison — Haiku 4.5 validated at
      92-93% agreement with BEIR qrels (tempdoc 245), 30-pair batch optimal.
   d. Fusion weight tuning via shadow evaluation.
   e. Thompson Sampling only if implicit feedback exists, with sliding window
      (gamma=0.95-0.99).

### Fusion Formula

```
// Input: per-retriever scores for chunk c (or whole-doc hit),
// already min-max normalized to [0,1] per-query
// Base alphas: constrained simplex (sum = 1), e.g., 0.35 / 0.35 / 0.30
// Expansion preset: separate base alphas for expanded queries
// parentTokenCount accessed via DocValues (stored at ingest time)

sw = spladeWeight(c.parentTokenCount)
    // < 1024 tokens → 1.0; > 4096 → 0.0; else linear interpolation
dw = denseWeight(c.contentFeatures)
    // numDensity > 0.3 → 0.5; length < 50 words → 0.5; else 1.0

raw_bm25   = base_alpha_bm25
raw_dense  = base_alpha_dense * dw
raw_splade = base_alpha_splade * sw
total      = raw_bm25 + raw_dense + raw_splade

// Re-normalize to constrained simplex (QDDF pattern)
fusion_score = (raw_bm25 / total) * bm25_norm[c]
             + (raw_dense / total) * dense_norm[c]
             + (raw_splade / total) * splade_norm[c]

// After fusion: CHUNK MERGE collapses to parents via MaxP
// chunk_merge_score[parent] = max(fusion_score[c] for c in parent.chunks)
// The winning chunk's text is preserved for CE input

// Freshness applied AFTER reranking (see stage 7a)
// final_score = (1 - alpha_fresh) * reranked_score + alpha_fresh * freshness(d.age)
```

Fusion operates on **chunk-level results** before collapse (PARADE principle).
Each chunk's weight modulation uses its parent document's token count via
DocValues — a chunk from a long parent gets SPLADE suppressed even though the
chunk itself is short, because the SPLADE score reflects only a fraction of the
parent's content.

For chunks with missing retriever scores (partial index), `zeroExclude`
omits the missing leg and re-normalizes over the remaining legs (existing
`HybridFusionUtils.java` behavior). CE reranking downstream mitigates the
systematic bias against embedding-missing documents.

### Threshold Table

| Parameter | Value | Confidence | Data Source |
|-----------|-------|------------|-------------|
| SPLADE off | parent > 4096 tokens | Very strong | tempdoc 251: 0.083 vs 0.889 nDCG |
| SPLADE full | parent < 1024 tokens | Strong | 5/6 BEIR datasets; 512-token truncation |
| SPLADE 0.5 | 1024-4096 tokens | Medium (intuited) | Linear interpolation, needs validation |
| CE skip | doc > 512 tokens | Very strong | tempdoc 251: -0.606 nDCG delta |
| Dense reduction | < 50 words | Weak (intuited) | No data <200 tokens; validate via bootstrap |
| WIG high (skip CE) | TBD | — | Calibrate via synthetic query bootstrap |
| NQC low (skip CE) | TBD | — | Calibrate via synthetic query bootstrap |
| SimHash collapse | Hamming ≤ 3 | Very strong | Manku et al. (Google WWW 2007, 8B docs); k=2-4 plateau, no cliff (Round 5 confirmed) |
| Corpus homogeneous | CV < 0.3 | Medium | No literature standard; reasonable heuristic |
| Freshness halflife | 30 days | Medium | RAG freshness paper; tune per corpus type |
| Freshness alpha | 0.05-0.10 | Medium | Re3; conservative start |

### MMR Lambda Table

| Query type | Expansion | Lambda | Literature support |
|------------|-----------|--------|-------------------|
| NAVIGATIONAL | any | 1.0 | Santos et al. SIGIR 2011 |
| EXACT | any | 1.0 | Santos et al. SIGIR 2011 |
| INFORMATIONAL | off | 0.5 | ES Labs, LangChain default |
| INFORMATIONAL | on | 0.6-0.7 | SPLADE is aspect-broadening |
| EXPLORATORY | off | 0.3-0.5 | ES Labs "product discovery" |
| EXPLORATORY | on | 0.6-0.7 | Softer diversity when expansion active |

### Calibration Methodology

Initial calibration uses synthetic query bootstrap (no relevance labels needed).
The GPL pipeline (`GplJobCoordinator`) already implements steps 1-2 and stores
the results in `gpl-training-triples.ndjson` with sparse/vector/QPP features
per (query, doc) pair. The calibration extension adds steps 3-5:

1. **Generate** synthetic queries from document chunks via local LLM
   *(existing: GPL few-shot prompt, 2 queries/doc, temp=0.4, keyword-style)*
2. **Filter** via round-trip consistency (source doc in top-10 results)
   *(existing: GPL writes positive/negative triples with CE scores)*
3. **Sweep** fusion weights on 2D simplex (0.05 granularity, ~200 configs)
   *(new: post-process GPL triples, re-score with varying alpha)*
4. **Select** plateau center via MRR + Kendall's tau stability *(new)*
5. **Validate** on 20% held-out synthetic queries *(new; GPL already does
   80/20 split by query_id for LambdaMART — same split logic)*

**Gap**: GPL currently queries with `PipelineConfigs.TEXT` (BM25 only). The
`vector` score in triples comes from debug output of a sparse-only query, and
SPLADE scores are not captured. For 3-way calibration, the GPL job needs to
either run queries in 3-way mode or issue parallel SPLADE queries to capture
all three retriever scores per synthetic query.

Ongoing calibration via shadow evaluation: log both gated and ungated
results on every query (free counterfactual data from run-all-then-gate).
Periodic LLM-as-judge pairwise comparison on random sample (Haiku 4.5,
30-pair batch, binary threshold ≥2 on 0-3 scale — validated in tempdoc 245).

### Current System → Optimal Architecture Delta

Mapping the 7 routing rules from tempdoc 258 D2 to the new architecture:

| 258 D2 Rule | Status in New Architecture |
|-------------|--------------------------|
| 1. CE gated by doc length; disable when median >4K tokens | **Refined**: Per-document CE gating (skip >512 tokens per doc), not corpus-level median. More granular. |
| 2. Do not promote universal BM25-first long-doc rule | **Preserved**: Run-all-then-gate eliminates BM25-first vs hybrid-first choice entirely. All legs always run. |
| 3. BM25-first for topical, Hybrid for refinding (long docs) | **Superseded**: Per-document fusion handles this automatically. Long-doc results get suppressed SPLADE weight; BM25 dominates naturally without explicit routing. |
| 4. Extreme-length routing is objective-sensitive | **Superseded**: Per-document fusion + per-doc CE gating handles extreme-length documents within any query type. No separate extreme-length routing needed. |
| 5. SPLADE-first for short docs; disable SPLADE >4K tokens | **Refined**: SPLADE weight varies continuously by parent doc length (1.0 → 0.0 over 1024-4096 range) instead of binary on/off. Short-doc SPLADE dominance emerges naturally from high weight. |
| 6. Context-quality as first-class routing signal | **Preserved**: WIG/NQC on deduplicated scores drive reranker gating. Shadow evaluation provides ongoing context-quality measurement. |
| 7. LambdaMART should not ship until trained on non-synthetic data | **Evolved**: LambdaMART serves as a CE gating signal (score gap → skip CE). Its absolute ranking quality is less critical in this role. Still recommended to retrain on better data when available. |

**Net change**: The 7 discrete routing rules are replaced by continuous
per-document weight functions + a single post-retrieval gating decision.
The system moves from rule-based routing to score-based routing. Rules 2-5
are subsumed by per-document fusion; Rule 1 is refined to per-document
granularity; Rules 6-7 are preserved/evolved.

### Eval Strategy: Validating Optimal Routing

#### Principle

Validate the design incrementally: each stage isolates one variable, diffs
against the previous stage as baseline. Use `diff-search-eval-suite.mjs` with
0.98 regression threshold across all BEIR datasets. Metrics: nDCG@K, Recall@K,
MRR@K (existing metric contract). The mixed corpus gate validates that
run-all-legs never underperforms the best single mode per source corpus.

#### Stage 1: RRF → CC Swap — Zero Code Changes

**What**: Switch fusion algorithm from RRF to CC on 2-way hybrid-mode BEIR runs.
**How**: Pipeline config `{ fusionAlgorithm: "cc" }` — already supported by
`HybridSearchOps.java` (line 355) and `HybridFusionUtils.fuseWithCC()`.
**Baseline**: Existing `*-embedding-baseline.metrics.v2.json` in `scripts/bench/baselines/`.
These are RRF results — no need to rerun the baseline side.
**Datasets**: scifact, nfcorpus, arguana, webis-touche2020, mldr-en (all with embedding baselines).
**Expected**: CC ≥ RRF (Bruch et al. ACM TOIS 2024: CC outperforms RRF).
**Prerequisite**: Dev stack running with embedding model loaded; BEIR datasets
cached in `tmp/beir-cache/`. Cold start is 3-5 min (MCP `start` timeout too
short — start manually or use `--manage-backend` with the workflow script).
**Runs needed**: 1 new CC candidate run per dataset; diff against existing baselines.
**Infra gap**: None.
**Command sketch**:
```
node scripts/search/run-search-workflow.mjs beir-eval \
  --config-file configs/stage1-cc.json --manage-backend --run-id stage1-cc
node scripts/bench/diff-search-eval-suite.mjs \
  --baseline scripts/bench/baselines/search-eval-beir-scifact-embedding-baseline.metrics.v2.json \
  --candidate tmp/stage1-cc/metrics.v2.json --min-ndcg-ratio 0.98
```

#### Stage 2: Run-All-Legs (3-Way Retrieval) — Requires Code Changes

> **Correction (Round 5 investigation)**: Originally described as
> "config-only." Codebase investigation (U18) revealed that
> `searchHybridSplade()` is **2-way only** (SPLADE + dense, silently drops
> BM25). Passing `{sparse: true, dense: true, splade: true}` does NOT
> produce true 3-way retrieval.

**What**: Enable BM25 + Dense + SPLADE simultaneously with CC fusion.
**Requires code changes before eval can run**:
1. **New orchestrator branch** in `SearchOrchestrator`: when all 3 legs are
   enabled, run BM25, dense KNN, and SPLADE as 3 separate Lucene queries
   (instead of delegating to `searchHybridSplade` which drops BM25).
2. **New `fuseWithCC3` method** in `HybridFusionUtils`: 3-way CC taking a
   weight vector `double[] weights` (summing to 1.0), 3 `SearchResult`
   inputs, min-max normalization per leg, and 7-case `zeroExclude` logic.
3. **PipelineConfig**: expose CC alpha values (extend proto + app-api record).
   Currently `alpha` is a single `double` for 2-way; need a weight vector.

**Baseline**: Stage 1 results.
**Expected**: SPLADE adds recall on short-document corpora (scifact, nfcorpus).
Potential neutral-to-negative on long-doc corpora (mldr-en) — per-document
weight modulation (Stage 3) addresses this.
**Runs needed**: 1 new 3-way candidate run per dataset; diff against Stage 1.
**Also run**: Mixed corpus gate (`dag-runner-mixed-corpus-gate.mjs`) to verify
run-all-legs ≥ best single-mode result per source corpus.

#### Stage 3: Per-Document Weight Modulation (QDDF)

**What**: Apply per-chunk SPLADE/dense weight functions with simplex
re-normalization during CC fusion.
**Requires new code** (in addition to Stage 2 code):
- `HybridFusionUtils`: per-chunk weight modulation (SPLADE by parent token
  count via DocValues, dense by content characteristics), re-normalization
  to maintain sum=1 (QDDF pattern).
- `HybridSearchOps`: chunk-level fusion before MaxP collapse (architectural
  change — currently fuses at document level after chunk merge).
- Schema: add `parent_token_count` as `long` DocValues field
  (`SchemaFields.java` + `fields.v1.json` + `IndexingDocumentOps`).
  Requires reindex of corpus.
**Requires calibration**: GPL extension to capture SPLADE scores (~15 lines
across `GplJobCoordinator` + `GplTrainingTripleStore`), then alpha sweep
post-processing script on GPL triples. See "Calibration Methodology" above.
**Baseline**: Stage 2 results.
**Expected**: Improvement on heterogeneous corpora (mixed datasets) where
document characteristics vary. Flat on homogeneous corpora.
**Runs needed**: 1 candidate run per dataset after code changes; diff against Stage 2.

#### Stage 4: Post-Reranking Signals (Freshness + MMR Diversity)

**What**: Add freshness decay after CE reranking, then greedy MMR diversity.
**Freshness**: `modified_at` is already stored as DocValues (epoch-ms).
Formula: `age_days = (now - modified_at) / 86400000`. Only meaningful for
queries where recency matters — standard BEIR datasets lack temporal qrels.
courtlistener has filing dates but needs time-sensitive qrels.
**MMR diversity**: Reuse `RagContextOps.diversifyByMmr()` — operates on
`List<SearchHit>`, compatible interface. Needs field-name adapter:
document-level hits carry `CONTENT` not `CHUNK_CONTENT` (U14 finding).
Not currently measured by BEIR harness — would need diversity metrics
(alpha-nDCG, ERR-IA) added to `BeirEval.Metrics.psm1`.
**Baseline**: Stage 3 results.
**Expected**: Freshness lifts MRR on temporal queries. MMR improves
alpha-nDCG on exploratory queries, neutral on navigational.
**Runs needed**: 1 candidate run per dataset; diff against Stage 3.
**Infra gaps**:
- No diversity metrics in BEIR harness → add to `BeirEval.Metrics.psm1`
- No temporal eval corpus with time-sensitive qrels → build from courtlistener
  or another dated corpus
- MMR field adapter: `CHUNK_CONTENT` → `CONTENT` for doc-level hits

#### Existing Infrastructure (from prior tempdocs)

The following components are already implemented and can be reused:

| Component | Source | Status | Reuse for 270 |
|-----------|--------|--------|---------------|
| GPL synthetic query generation | `GplJobCoordinator` (tempdoc 234) | Implemented | Steps 1-2 of calibration methodology |
| GPL triple store with sparse/vector/QPP | `GplTrainingTripleStore` | Implemented | Raw data for alpha sweep (step 3) |
| GPL auto-trigger on corpus change | `GplRevalidationTrigger` | Implemented | Triggers recalibration automatically |
| Cross-encoder relevance scoring | `CrossEncoderReranker` (ONNX) | Implemented | Already scores GPL triples |
| LLM-as-judge validation (Haiku 4.5) | tempdoc 245 | Validated | 92-93% BEIR agreement, 30-pair batch |
| Agent-as-judge rubric runner | `judge-agent-run.mjs` (tempdoc 230) | Implemented | Periodic deep analysis |
| Cross-encoder faithfulness judge | `CitationScorer` (tempdoc 198) | Implemented | RAG path only, not search eval |
| QPP computation (WIG/NQC) | `SearchOrchestrator` | Implemented | Dynamic N for reranker gating |
| CC fusion (2-way) | `HybridFusionUtils` | Implemented | Extend to 3-way |
| Chunk merge (MaxP collapse) | `SearchOrchestrator.mergeChunkResults()` | Implemented | Reuse collapse logic |
| MMR diversification | `RagContextOps` | Implemented (RAG only) | Adapt for search path; needs CONTENT field adapter |

**Key finding**: The GPL pipeline already generates synthetic queries, scores
them with the cross-encoder, and stores per-doc sparse/vector features. The
calibration methodology's steps 1-2 are not new work — only the alpha sweep
(step 3), plateau selection (step 4), and validation (step 5) need to be built
as a post-processing script on existing GPL output.

**Gap in GPL**: Currently queries with `PipelineConfigs.TEXT` (BM25 only).
SPLADE scores are not captured. For 3-way fusion calibration, the GPL job
needs to also capture SPLADE scores per synthetic query.

#### Infra Gap Summary

| Gap | Stage | Effort | Notes |
|-----|-------|--------|-------|
| **3-way orchestrator branch** | **2** | **Medium-High** | **New code: run 3 separate Lucene queries, new `fuseWithCC3` in HybridFusionUtils. Blocks Stage 2 eval.** |
| CC weight vector in PipelineConfig | 2-3 | Medium | Extend proto + app-api record from single alpha to weight vector |
| `parent_token_count` DocValues | 3 | Low | Schema add + ingest write; requires reindex |
| Chunk-level fusion before MaxP | 3 | High | Architectural change in HybridSearchOps |
| Per-document weight modulation | 3 | Medium-High | New HybridFusionUtils code |
| GPL 3-way query mode | 3 | Low-Medium | ~15 lines: config line + SPLADE score capture |
| Alpha sweep script | 3 | Low-Medium | Post-process existing GPL NDJSON triples |
| Shadow logging | 3-4 | Medium | Log alternative-config results per query |
| MMR field adapter | 4 | Low | `CHUNK_CONTENT` → `CONTENT` lookup in diversifyByMmr |
| Diversity metrics (alpha-nDCG) | 4 | Medium | Add to BeirEval.Metrics.psm1 |
| Temporal eval corpus | 4 | High | Build from courtlistener or similar |

*Correction from Round 5 investigation: Stage 2 was originally described as
"config-only" but `searchHybridSplade()` is 2-way (SPLADE+dense, drops BM25).
True 3-way requires a new orchestrator branch and 3-way CC fuser — this is
the primary blocking gap for eval progression.*

#### Key Eval Files

| Purpose | Path |
|---------|------|
| Eval entry point | `scripts/search/run-search-workflow.mjs` |
| BEIR harness | `scripts/search/beir-eval-win.ps1` |
| Metric computation | `scripts/search/lib/BeirEval.Metrics.psm1` |
| Baseline diff | `scripts/bench/diff-search-eval-suite.mjs` |
| Baselines | `scripts/bench/baselines/*.v2.json` |
| Mixed corpus gate | `scripts/ci/dag-runner-mixed-corpus-gate.mjs` |
| Routing diagnostics | `scripts/bench/build-routing-diagnostics-report.mjs` |
| Fusion code (RRF + CC) | `modules/adapters-lucene/.../HybridFusionUtils.java` |
| Pipeline config | `modules/app-api/.../PipelineConfig.java` |
| 3-way retrieval path | `modules/indexer-worker/.../SearchOrchestrator.java` |
| GPL coordinator | `modules/app-services/.../gpl/GplJobCoordinator.java` |
| GPL triple store | `modules/app-services/.../gpl/GplTrainingTripleStore.java` |
| GPL auto-trigger | `modules/app-services/.../gpl/GplRevalidationTrigger.java` |
| Agent judge runner | `scripts/bench/judge-agent-run.mjs` |

### Remaining Uncertainties

Every design element in the consolidated final design has been assessed for
confidence. The following uncertainties remain unresolved and each has a
specific resolution path.

#### Resolved by Internet Research (Round 5)

| ID | Uncertainty | Resolution | Design change |
|----|-------------|------------|---------------|
| U1 | Early-exit CE feasibility with ONNX Runtime | **Not feasible.** ONNX Runtime always runs the full model graph — no partial execution, no dynamic early termination. Confirmed by GitHub issues #4484, #3128, #16483. DeeBERT/PABEE rely on Python-level control flow that doesn't survive ONNX export. SEE (SIGIR 2025) is PyTorch-only. | **Change design**: Drop "prefer early-exit CE over binary skip." Replace with **cascade CE** (small L-6 model first, full L-12 only for ambiguous scores) or **binary skip** (per-document gating by token count). Cascade is the pragmatic middle ground — works with ONNX Runtime today, no model surgery. |
| U2 | SMMR vs standard MMR | **Standard MMR sufficient.** SMMR's benefits (escaping greedy local optima, logarithmic speedup) only materialize at N > 1000. At N < 50 (desktop search), greedy MMR is near-optimal. SMMR also introduces non-determinism, undesirable for reproducible search results. All production systems (Elastic, Qdrant, OpenSearch) use greedy MMR. | **Change design**: Replace SMMR with standard greedy MMR (already implemented in `RagContextOps.java`). Keep the lambda table as-is — lambda tuning is the primary quality lever, not the selection algorithm. |
| U3 | SimHash Hamming threshold sensitivity | **Confirmed safe.** k=3 is the canonical threshold from Manku et al. (Google, WWW 2007, 8B web pages). Precision-recall break-even at ~0.75 each. The curve is a smooth slope (k=2-4 is a safe plateau), not a cliff. Random false positive rate at desktop scale (<500K docs) is mathematically negligible (~2.4 × 10⁻¹⁵ per pair). | **No change needed.** k=3 is the correct threshold. |

#### Resolvable by Eval (Stages 1-2 of Eval Strategy)

| ID | Uncertainty | Design assumption | Eval method |
|----|-------------|-------------------|-------------|
| U4 | CC vs RRF on JustSearch corpora | CC ≥ RRF | Stage 1: `fusionAlgorithm: "cc"` on BEIR datasets |
| U5 | 3-way retrieval lift | SPLADE adds recall on short-doc corpora | Stage 2: all legs, diff per dataset |
| U6 | SPLADE interpolation zone | Linear 1.0→0.0 over 1024-4096 tokens | Stage 2 data: nDCG contribution by parent length |

#### Resolvable by GPL Post-Processing

| ID | Uncertainty | Design assumption | Method |
|----|-------------|-------------------|--------|
| U7 | Optimal base alpha values | 0.35/0.35/0.30 (example only) | Extend GPL to 3-way → sweep simplex → plateau center |
| U8 | Dense reduction threshold | <50 words → 0.5 weight | GPL triples bucketed by doc length → dense contribution |

#### Resolved by Codebase Investigation

| ID | Uncertainty | Finding | Design impact |
|----|-------------|---------|---------------|
| U9 | Chunk-level fusion data flow | **Per-retriever scores survive.** `SearchHit.debugScores` always carries `sparse`, `vector`, `sparse_rank`, `vector_rank` (plus `chunk_` prefixed variants). Chunk results from `mergeChunkResults` go through `fuseWithRRF` with `"chunk_"` score key prefix to preserve per-leg provenance. However, `searchHybridSplade()` is **2-way only** (SPLADE + dense, no BM25). True 3-way requires running BM25 + SPLADE + dense as 3 separate legs and fusing all 3. The current `fuseLegs` fallback does sequential pairwise RRF, not simultaneous 3-way CC. | **3-way CC needs a new `fuseWithCC3` method** in `HybridFusionUtils`. The existing 2-way `fuseWithCC` takes `(sparseResult, denseResult, limit, alpha, debug, zeroExclude)` — extend to accept a weight vector + 3 results. `zeroExclude` logic needs 7 in/out combinations (vs current 3). |
| U10 | parentTokenCount as DocValues | **Does not exist.** No `TOKEN_COUNT`, `WORD_COUNT`, or `CONTENT_LENGTH` field in schema. Closest proxy: `size_bytes` (file size, DocValues-backed) or `chunk_total` (indirect). Adding `parent_token_count` follows the existing pattern: add to `SchemaFields.java` + `fields.v1.json` (type `long`, `docValues: true`) + compute in `IndexingDocumentOps.buildDocument`. `FieldMapper` auto-emits `NumericDocValuesField`. **Low-effort schema change, but requires reindex.** |
| U14 | MMR interface compatibility | **Compatible.** `RagContextOps.diversifyByMmr()` operates on `List<SearchHit>` — same type as search results. Computes cosine similarity via embeddings. **One gap**: it reads `SchemaFields.CHUNK_CONTENT` from `hit.fields()`, but document-level hits carry `CONTENT` not `CHUNK_CONTENT`. Needs a field-name adapter or dual lookup. |
| U15 | File age for freshness | **Available.** `modified_at` is stored as `long` epoch-ms with `docValues: true`. Available in `SearchHit.fields` as a string. Can compute `age_days = (now - modified_at) / 86400000` at post-reranking time. No schema change needed. |
| U16 | Query classification signals | **Minimal.** No query classifier exists. QPP computes `maxIdf`, `avgIctf`, `queryScope` but no structural features (quotes, field operators, term count). `TextAnalysisUtils.normalizedQueryTerms` tokenizes and lowercases but doesn't expose phrase detection. Query classification would be greenfield — but the signals needed (term count, has-quotes, has-field-operator) are trivially extractable from the raw query string. |
| U17 | GPL 3-way coupling | **Loosely coupled.** Single line change: `PipelineConfigs.TEXT` → a 3-way config at `GplJobCoordinator.java:406`. Then add `"splade"` key to `debugScoresMap` read (line 428-429) and add a `splade` parameter to `GplTrainingTripleStore.appendWithFeatures`. ~15 lines changed across 2 files. |
| U18 | 3-way retrieval architecture | **`searchHybridSplade` is 2-way only** (SPLADE + dense, drops BM25). True 3-way requires: (a) run BM25, dense, SPLADE as 3 separate Lucene queries, (b) pass all 3 `SearchResult` objects to a new 3-way CC fuser, (c) new orchestrator branch in `SearchOrchestrator`. The existing `fuseLegs` fallback does sequential pairwise RRF but is not used for the primary path. |

#### Explicitly Deferred (pre-launch)

| ID | Uncertainty | Rationale for deferral |
|----|-------------|----------------------|
| U11 | Freshness halflife (30 days) | No temporal eval corpus; tune per corpus type with real user data |
| U12 | Thompson Sampling gamma (0.95-0.99) | No implicit feedback signal pre-launch; TS is demoted to "only if feedback exists" |
| U13 | Query classifier accuracy | Rule-based classifier untested; needs real query logs to validate distribution. Deploy with classification logging, tune post-launch. |

#### Dependency Map

```
U1 (early-exit CE) ──→ RESOLVED: cascade CE or binary skip
U2 (SMMR vs MMR) ──→ RESOLVED: standard greedy MMR
U3 (SimHash threshold) ──→ RESOLVED: k=3 confirmed safe
U9 (chunk data flow) ──→ RESOLVED: per-retriever scores survive; need 3-way CC fuser
U10 (DocValues) ──→ RESOLVED: no token count field; low-effort schema add
U14 (MMR interface) ──→ RESOLVED: compatible, needs CHUNK_CONTENT→CONTENT adapter
U15 (file age) ──→ RESOLVED: modified_at available as DocValues
U16 (query features) ──→ RESOLVED: greenfield, but trivial signal extraction
U17 (GPL coupling) ──→ RESOLVED: ~15 lines across 2 files
U18 (3-way retrieval) ──→ RESOLVED: searchHybridSplade is 2-way; need new orchestrator branch

Remaining eval-dependent chain:
U4 (CC vs RRF) ──→ U5 (3-way) ──→ U6 (SPLADE zone) ──→ U7 (alpha values)
                                                         U8 (dense threshold)
Deferred: U11 (freshness halflife), U12 (TS gamma), U13 (classifier accuracy)
```

**Status**: 10 of 13 uncertainties resolved. U4-U8 require running eval stages
1-2 and GPL post-processing. U11-U13 are deferred to post-launch.

---

## Critical Analysis & Recommended Next Steps (2026-03-11)

### Document status

This tempdoc has completed its theoretical investigation. Five rounds of
research (80+ papers cited), 18 uncertainties tracked (10 resolved, 3 eval-
dependent, 2 GPL-dependent, 3 deferred), and iterative design revision have
produced a consolidated architecture that is internally consistent and
well-grounded in literature.

**The remaining value is in implementation and empirical validation, not
further theoretical work.** Additional research rounds would yield diminishing
returns — the design's open questions (U4-U8) can only be resolved by running
code, not reading papers.

### Design confidence summary

| Element | Confidence | Rationale |
|---------|-----------|-----------|
| Run-all-legs (~5ms parallel) | High | Latency data solid; validated to 500K docs |
| CC replaces RRF | Medium-High | Literature strong (Bruch et al.); untested on this codebase (U4) |
| Per-document SPLADE suppression | Medium | Hard thresholds (512, 4096) data-backed; interpolation zone intuited (U6) |
| Per-document dense weighting | Low | numericalDensity and <50-word signals are speculative (U8) |
| SimHash dedup (k=3) | High | Well-established (Manku et al., 8B docs); smooth plateau |
| Chunk-level fusion before MaxP | High | Strong literature consensus (PARADE, Vespa, ES) |
| CE gating by doc length (>512) | High | Strong empirical data (tempdoc 251: -0.606 nDCG delta) |
| MMR diversity post-rerank | Medium | Reusing existing code; lambda values literature-backed |
| Freshness scoring | Medium | No temporal eval corpus to validate against (U11) |
| Background calibration (GPL) | Medium | Pipeline exists but needs 3-way extension |
| Query classification | Low | Greenfield, rule-based, no validation data (U13) |

### Critical path

The eval stages form a strict dependency chain:

```
Stage 1 (CC swap)        → zero code changes, runnable today
Stage 2 (3-way retrieval) → BLOCKS on new orchestrator branch + fuseWithCC3
Stage 3 (QDDF)           → BLOCKS on Stage 2 + schema add + reindex
Stage 4 (freshness + MMR) → BLOCKS on Stage 3 + temporal eval corpus
```

**Stage 1 is the single most important next step.** It tests CC vs RRF on
2-way hybrid with zero code changes. If CC doesn't beat RRF on JustSearch
corpora, the entire design's foundation (CC as default fusion) needs
revisiting before investing in Stages 2-4.

**Stage 2 is the primary implementation blocker.** The 3-way orchestrator
branch + `fuseWithCC3` is medium-high effort and gates everything after it.
The `searchHybridSplade()` 2-way limitation (Round 5 finding) makes this
unavoidable.

### Recommended next steps

1. **Run Stage 1 eval** (CC vs RRF, zero code) — validates the core design
   thesis with existing infrastructure. Single session with dev stack.
2. **Close tempdoc 270** as "investigation complete" after Stage 1 results.
3. **Extract a focused implementation tempdoc** covering Stage 2 (3-way
   orchestrator + fuseWithCC3) and optionally Stage 3 (QDDF). Keep scope
   narrow — this tempdoc's 2500+ lines of investigation log should not be
   the working document for implementation.
4. **Defer Stages 3-4** to a follow-up tempdoc, contingent on Stage 2
   results proving the architecture.

### Structural note

The investigation log (Rounds 1-4, lines 63-1647) is ~65% of this document
and is mostly historical — superseded by the consolidated design. Multiple
"Historical" banners mark superseded sections. The authoritative design lives
in "Consolidated Final Design" (line 1940+). Future readers should start
there; the investigation log serves as an audit trail, not a reference.

---

## References (2024-2026 only)

### Empirical (JustSearch-specific)
- tempdoc 251: courtlistener nDCG data, Phase 5 factorial, mixed-corpus results
- tempdoc 258 D2: current routing policy (7 rules)
- tempdoc 256: PipelineConfig infrastructure
- tempdoc 229, 234: Thompson Sampling bandit design, GPL pipeline, cold-start rules
- tempdoc 245: LLM-as-judge validation (Haiku 4.5, 92-93% BEIR agreement, batch sizing)
- tempdoc 198: Cross-encoder faithfulness judge (CitationScorer, ONNX MiniLM)
- tempdoc 230: Agent-as-judge rubric runner (judge-agent-run.mjs)
- tempdoc 216: LLM-as-judge eval harness research (Promptfoo, Claude CLI judge validation)

### Post-retrieval QPP and reranker gating
- Meng et al. "Ranked List Truncation for LLM-based Re-Ranking" (SIGIR 2024) — ~20% of queries need no reranking; QPP for reranker gating
- Arabzadeh, Meng, Aliannejadi, Bagheri. "QPP: Techniques and Applications" (SIGIR-AP 2024 tutorial) — comprehensive modern QPP survey
- QPP++ 2025 Workshop at ECIR 2025, Lucca — QPP in the LLM era
- ACM TOIS 2025 10.1145/3774427 — QPP predictor accuracy is context-dependent

### Cascade ranking and early exit
- Veneres et al. "SEE: Efficient Re-ranking via Early Exit" (SIGIR 2025) — PyTorch-only, not ONNX-compatible
- E2Rank: "Efficient and Effective Layer-Wise Reranking" (Pinecone, ECIR 2025)
- Yoon et al. "AcuRank: Uncertainty-Aware Adaptive Computation for Listwise Reranking" (NeurIPS 2025, arXiv:2505.18512)
- Wang et al. "Learning Cascade Ranking as One Network (LCRON)" (ICML 2025, arXiv:2503.09492)
- ONNX Runtime GitHub Issues #4484, #3128, #16483 — confirm no partial graph execution / early termination support (Round 5)

### Score fusion and normalization
- Bruch, Gai, Ingber. "Analysis of Fusion Functions for Hybrid Retrieval" (ACM TOIS 2024) — convex combination outperforms RRF; normalization-agnostic
- Hsu & Tzeng. "DAT: Dynamic Alpha Tuning for Hybrid Retrieval" (arXiv:2503.23013, Mar 2025) — per-query adaptive weights via LLM-judged effectiveness
- arXiv:2502.04645 — Cross-Encoder Rediscovers Semantic BM25 (2025)

### Query routing and intent
- Zhang et al. "RAGRouter: Learning to Route Queries" (NeurIPS 2025, arXiv:2505.23052)
- Zhang et al. "REIC: RAG-Enhanced Intent Classification" (EMNLP 2025 Industry, arXiv:2506.00210)

### Selective expansion and adaptive retrieval
- TARG: Training-Free Adaptive Retrieval Gating (arXiv:2511.09803, Nov 2025)
- METIS: Fast Quality-Aware RAG Configuration Adaptation (SOSP 2025, arXiv:2412.10543) — 1.64-2.54x latency reduction
- arXiv:2409.02685 — RouterRetriever (AAAI 2025) — domain-expert routing
- arXiv:2501.12835 — Adaptive Retrieval Without Self-Knowledge (2025)
- arXiv:2508.09958 — Neural Bandit Pipeline Selection (2025)
- arXiv:2506.13743 — LTRR: Learning to Rank Retrievers (2025)

### Chunk-parent retrieval
- Vespa: layered ranking for RAG (blog.vespa.ai 2024), import field (docs.vespa.ai)
- HRR: Hierarchical Re-ranker Retriever (arXiv:2503.02401, 2025)

### Budget-aware reranking
- arXiv:2509.07163 — Reranker-Guided Search (RGS, 2025) — +3.5-5.1 nDCG within budget
- arXiv:2402.10866 — EcoRank: Budget-Constrained Re-ranking (ACL 2024 Findings)
- arXiv:2411.11767 — "Drowning in Documents" (ReNeuIR 2025) — rerankers degrade past threshold
- arXiv:2601.14224 — "Rerank Before You Reason" (2026) — effective token cost

### Zero-result re-routing
- arXiv:2401.15884 — CRAG: Corrective Retrieval Augmented Generation (2024)
- arXiv:2504.11001 — ReZero: Trying One-More-Time (2025)
- arXiv:2602.23374 — Higress-RAG (2026)

### Agentic vs interactive retrieval
- Zhang et al. "Agentic Information Retrieval" (arXiv:2410.09713, Oct 2024)
- Azure AI Search: Agentic Retrieval (learn.microsoft.com, 2025)
- "Evaluating Precision and Recall at Retrieval Time in RAG Systems" (2025)

### Run-all-then-gate and hybrid search
- arXiv:2508.01405 — "Balancing the Blend" (Aug 2025) — 4 retrieval paradigms
- arXiv:2603.02153 — RAG Fusion at scale (2026) — fusion <7% of total latency
- Xu et al. "Survey of Model Architectures in IR" (arXiv:2502.14822, Feb 2025) — parallel retrieval patterns

### Per-document fusion (novelty validation)
- DAPR: Document-Aware Passage Retrieval (ACL 2024)

### Cross-encoder research
- Pradeep, Nogueira, Lin. "Cross-Encoders and LLMs for Reranking SPLADE" (arXiv:2403.10407, 2024)
- Jina AI Reranker v2 — auto-chunking (HuggingFace, 2024)

### Long-document retrieval
- EMNLP Findings 2024: MC-indexing multi-view chunks (+16-43% recall)
- arXiv:2509.07759 — Long-document retrieval survey (2025)

### Result diversity
- Anan'eva et al. "SMMR: Sampling-Based MMR Reranking" (SIGIR 2025) — faster at large N, but standard greedy MMR sufficient at N<50 (Round 5 finding)
- Carbonell & Goldstein. "The Use of MMR for Diversity-Based Reranking" (SIGIR 1998) — canonical greedy MMR reference
- "DIVERGE: Diversity-Enhanced RAG" (arXiv:2602.00238, Feb 2026) — ~1.6x diversity, diversity collapse problem
- AGH IR at LongEval (CEUR-WS Vol-4038, 2024) — CE reranking reduces diversity (indirect)

### Dense retrieval limitations
- Li et al. "Dense Retrievers Can Fail on Simple Queries: The Granularity Dilemma" (EMNLP Findings 2025, arXiv:2506.08592)
- Weller et al. "On the Theoretical Limitations of Embedding-Based Retrieval" (DeepMind, arXiv:2508.21038, Aug 2025)
- "Revealing the Numeracy Gap" (arXiv:2509.05691, Sep 2025) — embeddings cannot distinguish numbers
- Kamalloo et al. "Resources for Brewing BEIR" (SIGIR 2024) — domain sensitivity, length bias

### Temporal relevance
- Fang & Tao. "Recency Bias in LLM-Based Reranking" (SIGIR-AP 2025, arXiv:2509.11353) — up to 25% preference reversal
- "Re3: Balancing Relevance & Recency" (arXiv:2509.01306, Sep 2025) — query-aware temporal gating
- "It's High Time: Survey of Temporal IR" (arXiv:2505.20243, May 2025) — comprehensive survey

### QPP evaluation and limitations
- Faggioli et al. "Uncovering Limitations of QPP" (ACM TOIS 2025, arXiv:2504.01101) — QPP unreliable across settings
- "Robustness Assessment of QPP Methods" (SIGIR ICTIR 2025, ACM 10.1145/3731120.3744611)
- Thomas et al. "LLMs Can Predict Searcher Preferences" (Microsoft SIGIR 2024) — LLM-as-judge
- TRUE framework (arXiv:2509.25602, 2025) — reproducible LLM-driven relevance judgment
- Turnbull. "Local LLMs as Search Relevance Judges" (softwaredoug.com, 2025)

### Counterfactual and offline evaluation
- Buchholz et al. "Counterfactual Ranking Evaluation" (SIGIR 2024, Cornell)
- Bibaut et al. "Anytime-valid Off-Policy Inference for Contextual Bandits" (ACM/IMS JDS 2024)
- Castells et al. "Offline Recommender System Evaluation" (AI Magazine 2022)

### Bandit feedback loop risks
- Mansoury et al. "Degenerate Feedback Loops in Recommender Systems" (AAAI/ACM AIES, arXiv:1902.10730)
- Baudry et al. "Sliding-Window Thompson Sampling" (JAIR 2024, arXiv:2409.05181) — non-stationary settings
- "Adaptive Experiments Under Data Sparse Settings" (arXiv:2501.03999, 2025) — TS fails with few observations

### Multi-stage pipeline composition
- "Full Stage Learning to Rank" (ACM Web 2024, arXiv:2405.04844) — GPRP, selection bias
- Vespa phased ranking docs (docs.vespa.ai, 2025)
- Elasticsearch linear retriever (elastic.co/search-labs, 2025)

### Session-aware search
- MTRAG benchmark (IBM, ACL 2025) — multi-turn RAG evaluation
- "A Survey of Conversational Search" (arXiv:2410.15576, Oct 2024)
- FAIR-RAG (arXiv:2510.22344, Oct 2025) — query complexity routing
- Self-Routing RAG (arXiv:2504.01018, Mar 2025)
- SearchLab Session Collection (SIGIR CHIIR 2025)

### Latency benchmarks (2024-2025)
- Elasticsearch benchmarks: blunders.io, elasticsearch-benchmarks.elastic.co
- Qdrant single-node: qdrant.tech/benchmarks (2024)
- Weaviate ANN: docs.weaviate.io/benchmarks/ann (2024)
- Metarank cross-encoder: docs.metarank.ai (2024)
- OpenSearch neural sparse deep dive (2024)
- Arapakis et al. "Impact of Response Latency on User Behavior" (SIGIR 2014) — 100ms matters at web scale
- Arapakis & Park. "Mobile Web Search Latency" (2021) — desktop users more tolerant

### GPU scheduling and ONNX Runtime
- ONNX Runtime Performance Tuning: "EP Context Cache" and "Concurrent Session Execution" (onnxruntime.ai docs, 2024-2025) — sessions serialize on single GPU
- NVIDIA TensorRT-LLM: Concurrent Model Execution (docs.nvidia.com, 2025)

### HNSW scaling and index performance
- Lucene 9/10 HNSW Implementation Notes (apache.org/lucene, 2024-2025)
- Qdrant Benchmarks: ANN at scale (qdrant.tech/benchmarks, 2024) — latency vs dataset size
- Weaviate ANN Benchmarks: HNSW scaling behavior (docs.weaviate.io, 2024) — >500K vectors, latency >10ms at efSearch=100

### Near-duplicate detection
- Manku, Jain, Sarma. "Detecting Near-Duplicates for Web Crawling" (WWW 2007) — SimHash canonical reference
- Sood et al. "SimHash-Based Duplicate Detection in Digital Libraries" (PMC:7148013, 2020) — SimHash in document retrieval
- Charikar. "Similarity Estimation Techniques from Rounding Algorithms" (STOC 2002) — locality-sensitive hashing theory

### Dynamic passage selection and reranker input
- arXiv:2508.09497 — "From Ranking to Selection: Dynamic Passage Selection" (2025) — optimal K varies per query (K=3-5 output)
- arXiv:2411.11767 — "Drowning in Documents" (updated Jul 2025) — reranker degradation past threshold (already cited above)

### Multi-model and code-specific retrieval
- arXiv:2411.12644 — CodeXEmbed (COLM 2025) — code-specific embedding models outperform general by >20%
- arXiv:2409.02685 — RouterRetriever (AAAI 2025) — domain-expert routing +2-3 nDCG (already cited above)

### Synthetic query calibration
- arXiv:2410.12890 — REFINE: "Relevance Feedback from LLM Judges" (2024) — synthetic query generation for evaluation
- arXiv:2508.00751 — Airbnb Search: "Interleaving-Based Online Evaluation" (KDD 2025) — counterfactual evaluation without labels

### Algorithm transparency and user modes
- Shin. "Role of Appropriate Transparency in Search Systems" (JASIST 2024) — showing what happened builds trust
- SearchLab Session Collection (SIGIR CHIIR 2025) — user mode interaction patterns (already cited above)

### Partial index and fusion bias
- BM42 Thesis (diva-portal.org, 2024) — static vs adaptive weights for homogeneous corpora
- arXiv:2405.04844 — "Full Stage Learning to Rank" (ACM Web 2024) — selection bias in multi-stage pipelines (already cited above)

### Multi-retriever fusion parameterization (Round 4)
- Wistuba & Narasimhan. "Query-Document-Dependent Fusion (QDDF)" (ResearchGate) — per-document fusion weights, dual-phase QDDF
- Candelieri et al. "Bayesian Optimization over the Probability Simplex" (Annals of Math & AI, 2023) — BO on simplex outperforms standard BO
- Zendel et al. "Unsupervised Search Algorithm Configuration using QPP" (ACM Web 2024, 10.1145/3589335.3651579) — tuning without labels
- InfiniFlow. "Multi-way Retrieval Evaluations on Infinity" (infiniflow.org, 2024) — 3-way weighted sum ~= RRF
- Elasticsearch Linear Retriever (elastic.co/search-labs, 2025) — production 3-way CC with MinMax normalization

### Temporal relevance in fusion (Round 4)
- Re3: "Learning to Balance Relevance & Recency" (arXiv:2509.01306, Sep 2025) — sigmoid-gated adaptive fusion, 4 strategies compared
- "Solving Freshness in RAG" (arXiv:2509.19376, Sep 2025) — additive exponential decay, performance cliff at alpha >= 0.9
- "Still Fresh? Evaluating Temporal Drift in Retrieval Benchmarks" (arXiv:2603.04532, 2026)

### Intent-aware diversification (Round 4)
- Santos et al. "Intent-Aware Search Result Diversification" (SIGIR 2011) — navigational vs informational diversity allocation
- Fang et al. "A Comparative Study of Search Result Diversification Methods" — lambda sensitivity analysis
- Bouchoucha. "Diversified Query Expansion" (U. Montreal) — DQE outperforms post-hoc MMR

### Synthetic query generation (Round 4)
- InPars+ (arXiv:2508.13930, 2025) — CPO + DSPy for improved synthetic query generation with open-source models
- Rahmani et al. "Synthetic Test Collections for Retrieval Evaluation" (SIGIR 2024, arXiv:2405.07767) — Kendall's tau = 0.86 vs human judgments
- Apple. "Synthetic Query Generation for Virtual Assistants" (SIGIR 2024, 10.1145/3626772.3661355)
- "Data Fusion of Synthetic Query Variants" (arXiv:2411.03881, 2024) — diverse variant generation + rank fusion
- LaQuA. "Latent Query Alignment" (ICIC 2025) — bridging synthetic-real distribution gap

### Chunk-to-document aggregation ordering (Round 4)
- Li et al. "PARADE: Passage Representation Aggregation for Document Reranking" (ACM TOIS 2023, arXiv:2008.09093) — later aggregation consistently outperforms earlier; representation > score > document-level
- Zhang et al. "Comparing Score Aggregation Approaches for Pretrained Transformers" (ECIR 2021) — MaxP/SumP passage-first scoring
- Keshav et al. "DAPR: Document-Aware Passage Retrieval" (ACL 2024) — 53.5% retrieval errors from missing document context; early collapse prevents passage identification
- Singh & Mohapatra. "HRR: Hierarchical Re-ranker Retriever" (arXiv:2503.02401, 2025) — chunk granularity through retrieval+reranking before parent expansion
- Kuo et al. "MMLF: Multi-query Multi-passage Late Fusion Retrieval" (NAACL 2025 Findings) — late fusion at passage level

## Closure Note (2026-03-11)

**Investigation validated empirically.** Stage 1 eval (CC vs RRF, 2-way
BM25+dense) confirms the core design thesis:

| Dataset | Queries | RRF nDCG@10 | CC nDCG@10 | Δ |
|---------|---------|-------------|------------|---|
| scifact | 300 | 0.6653 | 0.7109 | +6.9% |
| nfcorpus | 323 | 0.3108 | 0.3444 | +10.8% |
| arguana | 1406 | 0.2912 | 0.3134 | +7.6% |

CC wins on all 3 datasets at α=0.50, consistent with Bruch et al. predictions.

**Stage 2 validated (2026-03-11).** 3-way retrieval (BM25 + dense + SPLADE)
with CC fusion confirms the "run-all-legs" architecture thesis:

| Dataset  | 2-way CC nDCG@10 | 3-way CC nDCG@10 | Δ     |
|----------|------------------|------------------|-------|
| scifact  | 0.7109           | 0.7305           | +2.8% |
| nfcorpus | 0.3444           | 0.3517           | +2.1% |

Implementation of Stages 1-2 is in tempdoc 274 (closed). Stages 3+ (QDDF, freshness,
diversity, Thompson Sampling) remain as future work — see tempdoc 274's
"Out of Scope" section for the deferred items.
Deferred Stage 3+ work now continues in tempdoc 280.

**Post-closure note (2026-03-12).** Later implementation work in tempdocs 273,
278, and 280 refines how future readers should interpret this design:

- the **Stage 3 structure still stands**:
  - chunk-level fusion before MaxP
  - parent-aware SPLADE modulation via `parent_token_count`
  - GPL-backed calibration for heuristic thresholds
- however, later code audit also clarifies a scoping boundary that is easy to
  misread from the main body:
  - extending GPL for 3-way calibration is still part of the Stage 3 path
  - but a real LambdaMART feature-schema V2 is **not** part of that first
    Stage 3 slice under current code
  - current LambdaMART remains a hard V1 contract (2-feature schema, 2-array
    reranker API, feature-count validation on model load), so any V2 should be
    treated as an explicit follow-on architecture migration rather than a small
    add-on to calibration work
- however, one motivating dense-retrieval rationale in this doc is now stale
  under current code:
  - tempdoc 278 fixed long parent-document dense embeddings to use chunking +
    mean-pooling rather than first-window truncation
  - so any argument here that very long docs should reduce dense trust because
    of old embedding truncation / "chunking artifacts" should not be treated as
    current evidence
- the dense-weighting part of Stage 3 remains low-confidence and should be
  calibrated on post-278 code before shipping

Freshness, diversity, and later adaptive work remain deferred beyond the
first Stage 3 slice.
