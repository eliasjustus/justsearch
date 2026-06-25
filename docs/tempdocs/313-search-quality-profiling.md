---
title: "313: Search Quality Profiling"
type: tempdoc
status: done
created: 2026-03-16
---

> NOTE: Noncanonical doc (investigation + measurement). May drift.

# 313: Search Quality Profiling

## Purpose

Systematically profile search quality across all component combinations to
identify the biggest quality issues and the contribution of each component.
Analogous to how tempdoc 310 profiled throughput by measuring per-phase
costs, this tempdoc profiles quality by measuring per-component nDCG/MRR
contribution.

## Approach

Use the `jseval` eval toolkit to run BEIR evaluations with different search
pipeline configurations. The `--modes` flag isolates individual components;
custom pipeline dicts enable arbitrary combinations. Compare results using
`jseval compare` (paired t-test, Cohen's d_z, bootstrap CI).

### Component matrix

The search pipeline has these toggleable components:

| Component | Pipeline key | What it does |
|-----------|-------------|--------------|
| BM25 (sparse) | `sparseEnabled` | Lexical term matching |
| Dense vector | `denseEnabled` | Embedding similarity (nomic-embed) |
| SPLADE | `spladeEnabled` | Learned sparse expansion |
| Reranker (CE) | N/A (server-side) | Cross-encoder reranking |

Built-in jseval modes: `lexical` (BM25), `hybrid` (BM25+dense), `vector`
(dense only), `splade` (SPLADE only).

### Missing combinations to test

| Combination | Components | jseval support |
|-------------|-----------|----------------|
| BM25 only | sparse | `--modes lexical` (built-in) |
| Dense only | dense | `--modes vector` (built-in) |
| SPLADE only | splade | `--modes splade` (built-in) |
| BM25 + dense (hybrid) | sparse + dense | `--modes hybrid` (built-in) |
| BM25 + SPLADE | sparse + splade | **needs custom mode** |
| Dense + SPLADE | dense + splade | **needs custom mode** |
| BM25 + dense + SPLADE (full) | all three | **needs custom mode** |
| Any + reranker | + CE reranking | **needs --lambdamart or custom** |

### Implementation needed

1. Add custom modes to jseval CLI for the missing combinations
2. Run all combinations on SciFact (full 300 queries, full 5.2K corpus)
3. Collect nDCG@10, AP@10, MRR@10, Recall@10 per combination
4. Compare all pairs to identify which components help/hurt
5. Run on additional datasets (NFCorpus, ArguAna) for validation

## Implementation

### Step 1: Add custom modes to jseval

Add named modes for missing combinations in `jseval/retriever.py`:
- `bm25_splade` — BM25 + SPLADE (no dense)
- `dense_splade` — dense + SPLADE (no BM25)
- `full` — BM25 + dense + SPLADE (all components)

### Step 2: Full corpus eval on SciFact

Materialize full SciFact (5,183 docs), ingest, wait for all components
ready (embedding + SPLADE), then run all 7 combinations.

### Step 3: Results analysis

Build a component contribution matrix showing how each component affects
quality in different combinations.

## Results — SciFact (300 queries, 5,189 docs, GPU)

### Component nDCG@10 matrix (SciFact, 300 queries, 5,189 docs)

Run 2 (explicit pipeline dicts, with and without cross-encoder reranker):

| Mode | Components | nDCG@10 | AP@10 | MRR@10 | R@10 | P@1 |
|------|-----------|---------|-------|--------|------|-----|
| **lexical** | BM25 | 0.6342 | 0.5887 | 0.6112 | 0.7554 | 0.5033 |
| **lexical+CE** | BM25 + reranker | 0.6403 | 0.5931 | 0.6111 | 0.7690 | 0.5100 |
| **splade** | SPLADE | 0.5469 | 0.4962 | 0.3958 | 0.6856 | 0.4300 |
| **splade+CE** | SPLADE + reranker | 0.5501 | 0.4979 | 0.4021 | 0.6927 | 0.4267 |
| **bm25_splade** | BM25 + SPLADE | 0.6216 | 0.5680 | 0.5194 | 0.7703 | 0.4733 |
| **bm25_splade+CE** | BM25 + SPLADE + CE | 0.6385 | 0.5898 | 0.5700 | 0.7729 | 0.5067 |
| **dense_splade** | dense + SPLADE | 0.5469 | 0.4962 | 0.3958 | 0.6856 | 0.4300 |
| **full** | BM25 + dense + SPLADE | 0.6216 | 0.5680 | 0.5194 | 0.7703 | 0.4733 |
| **full+CE** | all + reranker | 0.6385 | 0.5898 | 0.5700 | 0.7729 | 0.5067 |

### Key findings

1. **BM25 alone is the strongest single component** — 0.6342 nDCG@10.
   Beats SPLADE alone by +16%. On SciFact (scientific claims with
   precise vocabulary), lexical matching captures most signal.

2. **Cross-encoder reranker adds +1-3%** across all configurations.
   lexical+CE (0.6403) is the best single-retriever + reranker combo.
   The CE helps MRR most when SPLADE is involved (0.5194→0.5700 for
   bm25_splade), suggesting it corrects SPLADE's ranking errors.

3. **SPLADE alone is weak** — 0.5469 nDCG@10, −13% vs BM25. MRR is
   particularly poor (0.3958 vs 0.6112) — SPLADE ranks the top relevant
   doc much lower. This is below BEIR leaderboard expectations (~0.67
   for SPLADE-v2).

4. **Adding SPLADE to BM25 HURTS quality** — bm25_splade (0.6216) is
   WORSE than lexical alone (0.6342), a −2% regression. SPLADE's fusion
   weight (0.30) dilutes BM25's strong signal. The cross-encoder
   partially recovers this (bm25_splade+CE: 0.6385).

5. **Dense vectors contribute nothing measurable** — dense_splade
   (0.5469) equals splade alone. full (0.6216) equals bm25_splade.
   The dense component has zero marginal contribution in every pairing.

6. **The best configuration is lexical+CE** (0.6403) — just BM25 with
   cross-encoder reranking. Adding SPLADE or dense vectors doesn't
   help on this dataset.

### Component contribution analysis

| Component added | To baseline | nDCG@10 delta | Verdict |
|----------------|-------------|---------------|---------|
| Dense | SPLADE | +0.000 | **No effect** |
| Dense | BM25+SPLADE | +0.000 | **No effect** |
| SPLADE | BM25 | −0.013 (−2%) | **Hurts** |
| SPLADE | Dense | +0.000 | **No effect** |
| CE reranker | BM25 | +0.006 (+1%) | Helps slightly |
| CE reranker | SPLADE | +0.003 (+0.6%) | Helps slightly |
| CE reranker | BM25+SPLADE | +0.017 (+2.7%) | **Helps most here** |

### Root cause: `NO_EMBEDDING_SERVICE` at query time (CONFIRMED)

Dense vectors are never used because the search path returns
`hybridFallbackReason: NO_EMBEDDING_SERVICE`. Confirmed via debug API:

```
POST /api/knowledge/search {"query":"biomaterials","debug":true,
  "pipeline":{"sparseEnabled":true,"denseEnabled":true}}
→ hybridFallback: true, hybridFallbackReason: NO_EMBEDDING_SERVICE
```

The Worker's embedding service loads for indexing (`Embedding service
ready`) and embeds all 5,184 docs. But `SearchOrchestrator.prepareQueryVector()`
can't find it at query time → `canDense=false` → all dense code paths
skip → results identical to non-dense baselines.

**This is the highest-priority quality bug.** Every hybrid/dense search
silently degrades to BM25-only. The CC fusion algorithm is correct; the
bug is in the service wiring between indexing and search paths.

### Remaining issues

1. **SPLADE quality gap** — 0.5375 vs BEIR ~0.67 is a 20% shortfall.
   Possible causes: O3+INT8 quantization, different SPLADE variant
   (v3 vs v2), scoring implementation, or chunk-level vs doc-level
   indexing differences.

2. **SPLADE hurts BM25 fusion** — adding SPLADE to BM25 degrades
   quality by −2%. The CC fusion weights (0.35/0.35/0.30) may need
   tuning, or the fusion algorithm itself may have issues.

3. **Dense contribution unknown** — until the NO_EMBEDDING_SERVICE bug
   is fixed, we can't measure dense vector quality contribution.

### NFCorpus Results (323 queries, 3,634 docs, graded relevance)

| Mode | Components | nDCG@10 | AP@10 | MRR@10 | R@10 | P@1 |
|------|-----------|---------|-------|--------|------|-----|
| **lexical** | BM25 | 0.3007 | 0.1125 | 0.5165 | 0.1458 | 0.4458 |
| **lexical+CE** | BM25 + CE | 0.3151 | 0.1178 | 0.5250 | 0.1494 | 0.4520 |
| **splade** | SPLADE | 0.2861 | 0.1018 | 0.4336 | 0.1409 | 0.4241 |
| **splade+CE** | SPLADE + CE | 0.3156 | 0.1119 | 0.4755 | 0.1557 | 0.4396 |
| **bm25_splade** | BM25 + SPLADE | 0.3012 | 0.1097 | 0.4618 | 0.1529 | 0.4272 |
| **bm25_splade+CE** | BM25+SPLADE+CE | **0.3213** | **0.1178** | 0.4889 | **0.1614** | 0.4489 |
| **dense_splade** | dense + SPLADE | 0.2861 | 0.1018 | 0.4336 | 0.1409 | 0.4241 |
| **full** | all three | 0.3012 | 0.1097 | 0.4618 | 0.1529 | 0.4272 |
| **full+CE** | all + CE | **0.3213** | **0.1178** | 0.4889 | **0.1614** | 0.4489 |

NFCorpus key findings:

1. **Cross-encoder has the biggest impact** — CE adds +5-10% nDCG across
   all configurations. splade+CE (0.3156) beats lexical alone (0.3007).
2. **BM25+SPLADE+CE is the best config** — 0.3213 nDCG@10.
3. **Dense vectors still contribute nothing** — dense_splade = splade,
   full = bm25_splade. The dense component is invisible.
4. **SPLADE is weaker than BM25 but closer** — 0.2861 vs 0.3007 (−5%,
   vs −13% on SciFact). NFCorpus medical vocabulary may benefit slightly
   from SPLADE's expansion.
5. **Adding SPLADE to BM25 is neutral/slightly positive** — bm25_splade
   (0.3012) ≈ lexical (0.3007). Unlike SciFact, SPLADE doesn't hurt here.

Note: NFCorpus has graded relevance (0/1/2), making nDCG more sensitive
to ranking quality. A case-sensitivity fix was needed for doc ID matching
(Windows lowercases filenames; qrels have uppercase MED-xxxx IDs).

## Cross-dataset summary

| Component | SciFact delta | NFCorpus delta | Verdict |
|-----------|-------------|----------------|---------|
| **BM25 (baseline)** | 0.6342 | 0.3007 | Strong baseline |
| + dense vectors | +0.000 | +0.000 | **Zero contribution everywhere** |
| + SPLADE | −0.013 (−2%) | +0.001 (+0.2%) | Neutral to slightly harmful |
| + CE reranker | +0.006 (+1%) | +0.014 (+5%) | **Consistently helps** |
| Best config | lexical+CE (0.6403) | bm25_splade+CE (0.3213) | CE is the key |

### Dense Vector Investigation

**Root cause identified: `hybridFallbackReason: NO_EMBEDDING_SERVICE`**

Even with all documents fully embedded, dense search falls back because
the **query-time embedding service** is not available to the search
handler. The `SearchOrchestrator.prepareQueryVector()` checks
`embeddingService.isAvailable()` and returns null when it fails,
setting `canDense=false`. All dense code paths then fall back to
non-dense equivalents.

Confirmed by re-running SciFact with embedding service active:
- All dense-including modes show `[FALLBACK]` in response
- `hybridFallbackReason: NO_EMBEDDING_SERVICE`
- Debug scores DO show `vector` scores (11.4, 10.9) — some vector
  retrieval happens via a secondary path, but CC fusion is skipped

This means **we have never actually tested dense vector contribution
to search quality**. The embedding service may need explicit
configuration or initialization to be available at query time.

### FINAL RESULTS — Dense vectors active (post-wiring-fix)

| Mode | Components | nDCG@10 | AP@10 | MRR@10 | R@10 | P@1 |
|------|-----------|---------|-------|--------|------|-----|
| **lexical** | BM25 | 0.6380 | 0.5922 | 0.6152 | 0.7621 | 0.5100 |
| **lexical+CE** | BM25+CE | **0.6445** | **0.5980** | **0.6166** | 0.7723 | **0.5167** |
| **vector** | dense only | 0.0000 | — | — | — | — |
| **hybrid** | BM25+dense | 0.6158 | 0.5673 | 0.5898 | 0.7490 | 0.4833 |
| **hybrid+CE** | BM25+dense+CE | 0.6197 | 0.5719 | 0.5837 | 0.7513 | 0.4933 |
| **splade** | SPLADE | 0.5465 | 0.4918 | 0.4159 | 0.6957 | 0.4067 |
| **splade+CE** | SPLADE+CE | 0.5447 | 0.4887 | 0.4143 | 0.6971 | 0.4067 |
| **bm25_splade** | BM25+SPLADE | 0.6180 | 0.5588 | 0.5335 | **0.7820** | 0.4467 |
| **bm25_splade+CE** | BM25+SPLADE+CE | 0.6315 | 0.5798 | 0.5827 | 0.7731 | 0.4867 |
| **dense_splade** | dense+SPLADE | 0.5963 | 0.5147 | 0.5934 | **0.8244** | 0.3333 |
| **full** | all three | 0.6159 | 0.5527 | 0.5761 | 0.7923 | 0.4133 |
| **full+CE** | all+CE | 0.6181 | 0.5565 | 0.5672 | 0.7928 | 0.4267 |

### Key findings (with dense working)

1. **BM25 alone is STILL the best single retriever** — 0.6380 nDCG@10.
   lexical+CE (0.6445) is the overall best configuration.

2. **Dense vectors HURT hybrid search** — hybrid (BM25+dense) 0.6158
   is WORSE than lexical alone (0.6380), a −3.5% regression. Adding
   dense vectors to BM25 degrades quality, not improves it.

3. **Vector-only returns nothing** — the API returns 0 results for
   dense-only search. This is likely a pipeline configuration issue
   (requires sparse as a base retriever).

4. **Dense has the HIGHEST recall** — dense_splade achieves R@10=0.8244,
   the highest recall of any configuration. Dense finds different relevant
   docs than BM25/SPLADE, but ranks them poorly (nDCG=0.5963).

5. **SPLADE still underperforms BM25** — 0.5465 vs 0.6380 (−14%).
   Consistent with pre-fix results.

6. **Adding any component to BM25 hurts nDCG** on SciFact:
   - BM25 alone: 0.6380
   - + dense: 0.6158 (−3.5%)
   - + SPLADE: 0.6180 (−3.1%)
   - + both: 0.6159 (−3.5%)

7. **CE reranker helps most configurations** but can't recover the
   fusion degradation: lexical+CE (0.6445) > full+CE (0.6181).

### Component contribution analysis (with dense active)

| Component added | To baseline | nDCG@10 delta | R@10 delta | Verdict |
|----------------|-------------|---------------|------------|---------|
| Dense | BM25 | **−0.022 (−3.5%)** | −0.013 | **Hurts nDCG** |
| Dense | SPLADE | +0.050 (+9.1%) | +0.129 | Helps SPLADE |
| SPLADE | BM25 | −0.020 (−3.1%) | +0.020 | **Hurts nDCG**, helps recall |
| SPLADE | Dense | N/A (vector-only fails) | — | — |
| Dense+SPLADE | BM25 | −0.022 (−3.5%) | +0.030 | **Hurts nDCG** |
| CE reranker | BM25 | +0.007 (+1.0%) | +0.010 | Helps |
| CE reranker | full | +0.002 (+0.4%) | +0.001 | Marginal |

### Interpretation

The CC fusion weights (sparse=0.35, dense=0.35, splade=0.30) dilute
BM25's strong signal with weaker dense and SPLADE scores. On SciFact
(precise scientific vocabulary), BM25 is the best single signal.
Adding dense vectors or SPLADE introduces noise that the current
fusion weights can't overcome.

The dense vectors DO find different relevant documents (highest R@10
at 0.8244), but rank them poorly. A better fusion strategy might
benefit from dense recall while preserving BM25 precision — e.g.,
using dense for candidate expansion with BM25 for final ranking,
or tuning CC weights to heavily favor BM25.

## Comparison vs BEIR Reference Scores (2026-03-16 research)

| Component | Our Score | Reference | Gap | Source |
|-----------|----------|-----------|-----|--------|
| **BM25** | 0.6380 | **0.679** | **−6.0%** | Pyserini BM25-flat |
| **SPLADE-v3** | 0.5465 | **0.710** | **−23%** | SPLADE-v3 paper (Table 2) |
| **nomic-embed dense** | 0.0000 (fails) | **0.704** | N/A | nomic-embed v1.5 HF thread |
| **NFCorpus BM25** | 0.3007 | **0.322** | **−7%** | Pyserini regression |
| **NFCorpus SPLADE** | 0.2861 | **0.357** | **−20%** | SPLADE-v3 paper |

Sources:
- [Pyserini BEIR regressions](https://castorini.github.io/pyserini/2cr/beir.html)
- [SPLADE-v3 paper (Table 2)](https://arxiv.org/html/2403.06789v1)
- [nomic-embed-text-v1.5 BEIR thread](https://discuss.huggingface.co/t/sota-pure-dense-retrieval-on-beir-beating-hybrid-methods-with-nomic-embed-v1-5/170918)
- [Fusion weight sensitivity](https://www.semanticscholar.org/paper/e7260add8f44fc59a11e64df6fdf074957674100)

### Assessment

**BM25 −6% gap**: Partially expected — implementation differences
(Tika text extraction vs raw text, Lucene analyzer chain, field
boosting) explain some gap. But 6% is on the high side. Worth
investigating analyzer/tokenization differences.

**SPLADE −23% gap: CRITICAL.** The official SPLADE-v3 paper reports
0.710 on SciFact. Our 0.547 is dramatically below. This is NOT
explained by quantization (validated delta was −0.004). Likely causes:
- Chunk-level indexing (we index SPLADE on chunks, BEIR is doc-level)
- FeatureField scoring (`newLinearQuery` linear vs dot product)
- Post-processing differences in activation or token filtering
- Score normalization in the search pipeline

**Dense vector-only returns 0**: The API doesn't support pure kNN
without sparse retrieval as a base. nomic-embed should achieve 0.704
on SciFact with pure dense retrieval.

**Hybrid fusion hurts BM25**: Research confirms this is a known issue
with untuned convex combination weights. Equal-ish weights
(0.35/0.35/0.30) don't work when component quality differs
significantly. Per-dataset calibration or RRF is recommended.

## Prioritized issue list

### P0: Chunk-aware merge destroys quality — CONFIRMED

**Disabling chunk-aware merge improves ALL components dramatically:**

| Mode | Chunk ON | Chunk OFF | Delta |
|------|---------|----------|-------|
| lexical | 0.6380 | **0.6588** | **+3.3%** |
| splade | 0.5465 | **0.6144** | **+12.4%** |
| bm25_splade | 0.6180 | **0.6525** | **+5.6%** |
| full | 0.6159 | **0.6636** | **+7.7%** |
| **full+CE** | 0.6181 | **0.6796** | **+10.0%** |

**`full+CE` is now the BEST configuration** (0.6796) — multi-component
fusion WORKS when chunk-aware merge is disabled. The previous finding
that "every component hurts BM25" was caused by the chunk merge, not
the components themselves.

The chunk-aware branch fusion (50/50 CC between whole-doc and collapsed
chunk scores, with parent-length modulation) introduces noise that
overwhelms the primary retrieval signal. On SciFact, where documents
are short scientific abstracts (~200 tokens), the chunk branch adds
no useful signal — chunks are often the full document or near-
identical fragments.

**Gap vs BEIR reference (chunk OFF):**
- BM25: 0.659 vs 0.679 expected = **−3%** (acceptable implementation gap)
- SPLADE: 0.614 vs 0.710 expected = **−14%** (reduced from −23%, still significant)
- full+CE: 0.680 = nearly matches Pyserini BM25 reference

**Remaining SPLADE gap (−14%)**: With chunk merge disabled, SPLADE
improved from 0.547 to 0.614, but still 14% below the paper's 0.710.
This remaining gap may be from:
- O3+INT8 quantization effects at the scoring level
- FeatureField linear scoring vs dot product
- Our `log1p` activation vs paper's post-processing
- Token weight capping at 64.0f

### Beta query pruning experiment (beta=1.0 vs 0.5)

Tested with chunk-aware OFF and beta=1.0 (no query-term pruning):

| Mode | beta=0.5 | beta=1.0 | Delta |
|------|---------|---------|-------|
| splade | 0.614 | 0.614 | +0.000 |
| bm25_splade | 0.653 | 0.658 | +0.005 |
| full+CE | 0.680 | 0.671 | −0.009 |

**Beta pruning has negligible impact** on SPLADE-only quality. The 50%
query pruning is NOT the cause of the remaining −14% gap.

### Remaining SPLADE gap analysis (0.614 vs 0.710 = −14%)

Ruled out:
- [x] Chunk-aware merge (fixed: +12%)
- [x] Beta query pruning (no effect)
- [x] Activation function (log1p confirmed correct)
- [x] Query mode (ONNX confirmed, not IDF)
- [x] Weight cap at 64.0f (never triggered for real SPLADE weights)

Still possible:
- **FeatureField 9-bit precision** — 0.4% relative error per weight,
  accumulates over 100+ token dot product. May distort rankings for
  close-scoring documents.
- **Tika text extraction vs raw text** — our documents go through Tika
  extraction which may alter text content vs raw BEIR corpus.
- **O3+INT8 quantization** — validated as −0.004 delta in tempdoc 278,
  but that used a different eval methodology.
- **BM25 parameter interaction** — our k1=0.9, b=0.4 tuning affects
  the indexed content statistics which SPLADE queries against.

### P1: BM25 −6% gap (moderate impact)

Investigate analyzer/tokenization differences between our Lucene
setup and Pyserini's reference. Check:
- StandardAnalyzer vs WhitespaceAnalyzer
- Stop word handling
- Stemming/lowercasing
- BM25 parameters (k1=0.9, b=0.4 — matches Pyserini)

### P2: CC fusion weight tuning (moderate impact, blocked by P0)

Once SPLADE and BM25 are at expected quality, recalibrate CC weights.
Research shows per-dataset Bayesian optimization is recommended.
Alternatively, switch to RRF which is less sensitive to calibration.

### P3: Dense vector-only mode (low priority)

Fix API to support pure kNN search without sparse base. Useful for
evaluation and comparison but not critical for production quality.

## Remaining experiments

- [ ] Run on ArguAna (argument retrieval — different domain)
- [ ] Test SPLADE parent-doc-only vs chunk-level
- [ ] CC weight sweep experiment
- [ ] Compare analyzer output against Pyserini

## Datasets

- **SciFact** (primary): 5,183 docs, 300 queries, scientific claims
- **NFCorpus** (validation): medical/nutrition, graded relevance
- **ArguAna** (validation): argument retrieval

## Dependencies

- `jseval` package (installed from `scripts/jseval/`)
- Running dev stack with full corpus indexed
- SPLADE + embedding backfill complete before eval

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Profiling tempdoc with eval-dataset selections (NFCorpus, ArguAna) and dependency list. Profiling-phase artifact; downstream tempdocs (e.g., 343 baseline refresh) consumed its outputs.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

