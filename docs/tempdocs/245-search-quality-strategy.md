---
title: "245: Search & Index Quality Strategy — External Validation"
type: tempdoc
status: done
created: 2026-02-27
updated: 2026-03-02
---

> NOTE: Noncanonical doc (strategy + opinion). May drift.

# 245: Search & Index Quality Strategy — External Validation

## Purpose

Produce a component isolation matrix and published-baseline comparison that shows
the quality contribution of each retrieval component, and how the full stack
compares to published results for the same models on standard IR benchmarks.
BEIR is the starting point for external validation, not the end goal — the
strategy now includes benchmark diversification toward workloads that better
represent desktop file search (see "Evaluation Strategy" section).

---

## Current State

### What's built

JustSearch has a genuinely sophisticated retrieval pipeline that competes with
commercial search products. Most personal file search tools do BM25 only.

| Layer | Component | Status |
|-------|-----------|--------|
| Sparse retrieval | SPLADE-v3 (BERT-base, 256-token encoding) | Production (in hybrid mode) |
| Dense retrieval | nomic-embed-text-v1.5 (768-dim, HNSW M=16) | Production |
| Fusion | RRF (K=60, vectorWeight=0.75) with low-signal gating | Production |
| Learned reranking | LambdaMART (2-feature: sparse + vector scores) | Trained, wired |
| Cross-encoder | MiniLM-L6-v2 (ONNX, CPU/GPU) | Wired but requires manual model install |
| Passage retrieval | Chunk-aware search, post-RRF parent-doc collapsing | Production |
| Training data | GPL (8B LLM synthetic queries + cross-encoder scoring) | Verified e2e |
| QPP signals | MaxIDF, AvgICTF, QueryScope per query | Computed, on the wire, unused |
| LLM expansion | Async query expansion via local LLM | Wired, TEXT mode only |
| Fuzzy correction | Zero-hit retry + per-term correction chains | Production |

### What's measured

**Component isolation (4 datasets, 2026-02-28 to 2026-03-02)** — See "Results"
section below for the full isolation matrix. Key finding: SPLADE hybrid retrieval
is the biggest quality lever on scientific/biomedical text (+4.3 SciFact, +2.7
NFCorpus), but dense-only dominates on argument and financial text (Arguana,
FiQA). Cross-encoder consistently helps BM25. LambdaMART consistently hurts.

**Multi-dataset baselines** — Six BEIR datasets have prior runs. Four are valid
(SciFact, Arguana, NFCorpus, FiQA); two are broken (touche2020, mldr-en).

| Dataset | Queries | BM25 nDCG@10 | Hybrid nDCG@10 | Delta | Notes |
|---------|---------|--------------|----------------|-------|-------|
| SciFact | 300 | 0.663 | 0.706 | **+0.043** | Q8_0 model. CC sweep confirms fusion-invariant. |
| NFCorpus | 323 | 0.309 | 0.335 | +0.026 | Q8_0 model. CC sweep confirms fusion-invariant. |
| Arguana | 1,406 | 0.337 | 0.352 | +0.015 | Q4 model (not re-run with Q8). |
| FiQA | 648 | 0.220 | 0.317 | **+0.097** | Q8_0 model. Dense dominates; SPLADE adds zero lift. |
| webis-touche2020 | 49 | 0.292 | 0.292 | 0.000 | **Broken.** Vector contributed nothing. |
| mldr-en | 50 | 0.392 | 0.392 | 0.000 | **Broken.** Vector contributed nothing. |

**Comparison to published SOTA (nDCG@10):**

| System | SciFact | NFCorpus | FiQA | Source |
|--------|---------|----------|------|--------|
| **JustSearch hybrid (Q8_0)** | **0.706** | **0.335** | **0.317** | This work |
| nomic-embed-v1.5 fp32 | 0.704 | 0.338 | 0.375 | Cathedral-BEIR |
| SPLADE-v3 standalone | 0.710 | 0.357 | 0.374 | NAVER 2024 |
| BGE-base (open SOTA) | 0.741 | 0.373 | — | FlagEmbedding |
| GTE-base | 0.755 | 0.353 | — | Alibaba DAMO |
| Cohere embed-v3 | ~0.74 | 0.386 | — | Commercial |
| voyage-3-large | ~0.77 | ~0.39 | Commercial |

**Assessment:** JustSearch is at the quality ceiling of the nomic-embed-v1.5
model (~0.706 SciFact, ~0.335 NFCorpus). Q8_0 quantization introduces no
measurable loss vs fp32. The gap to open SOTA (BGE/GTE: -0.035 to -0.049 on
SciFact) and commercial systems (Cohere/Voyage: -0.05 to -0.06) is entirely
attributable to the embedding model, not fusion, quantization, or pipeline
architecture. **The bottleneck is single-leg retrieval quality.**

### What's now answered (from isolation)

1. **BM25 matches published baseline.** JustSearch BM25-only = 0.660 (Q4
   isolation) / 0.663 (Q8 CC sweep) vs published 0.665. No integration loss.

2. **SPLADE is the biggest quality lever.** SPLADE+Dense hybrid (0.704 Q4 /
   0.706 Q8) is +4.3 nDCG points over BM25-only and +3.8 points over BM25+Dense
   RRF. SPLADE replaces BM25 as the sparse leg in hybrid search, providing
   substantially better term matching for scientific vocabulary.

3. **Dense vectors ARE being used (ANN is already active).** The Lucene KNN
   vector search (`KnnFloatVectorQuery` on `SchemaFields.VECTOR`) runs in both
   HYBRID and VECTOR modes without the external ANN gRPC service. The
   `ann_service.enabled: false` config controls an unrelated external gRPC ANN
   server — NOT Lucene's built-in KNN search.
   - `denseVectorEvidenceAvailableRate: 0` is a **metrics bug**: the chunk merge
     RRF (`mergeChunkResults()`) overwrites original per-leg debug scores. After
     chunk merge, `debugScores.vector` reflects the chunk result score (0 for
     parent docs), not the original KNN leg contribution. See long-term issues.
   - Dense-only nDCG (0.671 Q4 / 0.694 Q8) > BM25-only (0.660) confirms vectors contribute.

4. **Cross-encoder adds value on top of BM25.** BM25+cross-encoder (0.673) is
   +1.3 nDCG points over BM25-only. However, SPLADE hybrid (0.704) outperforms
   cross-encoder without needing the inference cost.

5. **LambdaMART has no measurable impact without a trained model.** SPLADE+
   LambdaMART (0.7042) vs SPLADE+RRF (0.7041) = +0.0001, within noise. The
   LambdaMART reranker needs GPL training data (synthetic queries + cross-encoder
   scores) to learn meaningful feature weights.

6. **SPLADE 512-token provides no benefit on SciFact.** SPLADE-512 (0.7042) ≈
   SPLADE-256 (0.7041). SciFact docs are short abstracts (<256 tokens), so
   longer sequence length has no material to capture.

7. **Previous isolation had SPLADE silently disabled.** Three bugs invalidated
   the first run: SPLADE dev-path `autoDiscovered=false`, session ordering built
   index without SPLADE FeatureField data, and missing explicit env vars. All
   fixed — re-run produced the corrected results above.

### What's now answered (from multi-dataset investigation)

8. **Multi-dataset generalization.** 4-dataset isolation complete (SciFact,
   Arguana, NFCorpus, FiQA). SPLADE is dataset-dependent: huge win on scientific
   text, zero on argument and financial text. Dense retrieval universally
   competitive. RRF fusion dataset-dependent: helps SciFact, hurts Arguana
   and FiQA (dilutes strong dense signal when BM25 is weak).

9. **LambdaMART with GPL training.** Tested on all 3 datasets. **Consistently
   hurts** (-0.009 to -0.10). GPL synthetic queries overfit to training
   distribution. Not viable without real user query data.

10. **touche2020/mldr-en broken baselines.** Root cause: old script didn't wait
    for `chunkVectorsReady`. Not re-run (too large). 3-dataset matrix validates
    the fix.

11. **Dense retrieval gap vs published.** Initially identified as missing
    nomic-embed task prefixes. **Disproven by experiment** — prefixes have
    zero impact on Q4_K_M quality (Step 7). Root cause is **Q4 quantization**
    (confirmed: Q8_0 recovers +0.025 dense nDCG). Chunk-merge and 2048 context
    are NOT factors on these short-doc datasets (findings 13-14 below).

12. **Task prefix impact (measured).** Prefixes (`search_document:` /
    `search_query:`) produce **no measurable quality change** with Q4_K_M GGUF
    on SciFact: dense nDCG identical (0.669 vs 0.669), hybrid within noise
    (0.700 vs 0.704). The quantized model appears insensitive to prefixes.

13. **Q4 quantization impact (measured).** Q4_K_M→Q8_0 recovers **+0.025 dense
    nDCG** (0.669→0.694) and **+0.006 hybrid nDCG** (0.700→0.706). Q8_0→F16
    adds only +0.002 dense. **Q8_0 is the optimal quantization level** — near-
    lossless quality with only 60 MiB more than Q4_K_M.

14. **Remaining dense gap after Q8_0.** Published fp32 nomic-embed scores 0.704
    on SciFact. Q8_0 achieves 0.694 — a residual gap of -0.010. F16 achieves
    0.696, still -0.008 short. The remaining gap is from JustSearch's HNSW
    approximation (M=16, approximate nearest neighbor) vs exact exhaustive
    search used in MTEB benchmarks, plus minor Q8_0 residual quantization
    noise. **Not a context issue** — all SciFact docs fit in 2048 tokens.

### What's still unknown

1. ~~**CC alpha calibration.**~~ **ANSWERED (F13).** CC fusion is equivalent to
   RRF on SciFact/NFCorpus. Alpha has ≤0.001 nDCG effect. The bottleneck is
   single-leg quality, not fusion strategy.

2. ~~**SPLADE + cross-encoder combination.**~~ **Moved to tempdoc 250 P1/1a.**
   Mode gating is a pipeline architecture issue, not a quality measurement gap.

3. **Long-document embedding capability (2048 vs 8192 context).** Irrelevant
   for current benchmarks (all 4 BEIR datasets <2048 tokens). This is a
   capability gap for real desktop files (PDFs, long emails), not a quality
   gap explanation for measured short-doc deficits. See item 6.

4. **Whether chunking adds value vs truncation.** LoCoV1 (ICML 2024) found
   naive chunking strategies showed minimal or negative performance gains over
   whole-document approaches. JustSearch's chunk-merge-RRF pipeline has never
   been validated on long documents against a truncation baseline.

5. **MRR and Precision@1 scores.** All existing measurements report only
   nDCG@10 and Recall@10. Desktop search is primarily known-item refinding
   where MRR (rank of the one right document) is more meaningful.

6. **Quality on keyword-style queries — NO FEASIBLE BENCHMARK.** All BEIR
   datasets ≤100K docs use NL questions. MS MARCO has keyword queries but
   8.8M passages (~512 hours to index). No public benchmark exists for
   terse keyword fragments that real desktop users type.

---

## Results: SciFact Component Isolation (2026-02-28)

### nDCG@10 isolation matrix (Q4_K_M embedding model)

| Configuration | What it tests | lexical | hybrid | vector |
|---------------|---------------|---------|--------|--------|
| BM25 only | Baseline sparse retrieval | **0.660** | — | — |
| Dense only (nomic-embed) | Embedding quality alone | — | — | **0.671** |
| BM25 + Dense RRF | Vector contribution to BM25 | — | **0.666** | — |
| SPLADE + Dense RRF | Production default | 0.660 | **0.704** | — |
| SPLADE + Dense + LambdaMART (trained) | GPL-trained fusion | 0.642 | **0.693** | — |
| BM25 + cross-encoder | Cross-encoder reranking value | **0.673** | — | — |
| SPLADE 512-token | Truncation cost (256→512) | 0.660 | **0.704** | — |

### Comparison to published baselines and SOTA

| Tier | Source | SciFact nDCG@10 | Type |
|------|--------|-----------------|------|
| SOTA | RaCT (LLM reranking) | ~0.79 | LLM listwise reranker (multi-B params) |
| SOTA | RankLlama | ~0.756 | LLM listwise reranker |
| SOTA | RankZephyr | ~0.747 | LLM listwise reranker |
| Strong | E5-PT_base | 0.737 | Dense bi-encoder (contrastive pretrained) |
| Strong | E5-PT_large | 0.723 | Dense bi-encoder |
| Good | SPLADE-v3 (NAVER, standalone) | 0.710 | Learned sparse (standalone, no fusion) |
| | | | |
| **JustSearch** | **SPLADE+Dense RRF (Q8_0)** | **0.706** | SPLADE sparse + nomic-embed Q8_0 dense (**current best**) |
| **JustSearch** | **SPLADE+Dense RRF (Q4)** | **0.704** | SPLADE sparse + nomic-embed Q4 dense (isolation matrix) |
| Good | nomic-embed-text-v1.5 (fp32, 8192ctx) | 0.704 | Dense bi-encoder (full precision, with task prefixes) |
| | | | |
| **JustSearch** | **SPLADE+Dense+LambdaMART (trained)** | **0.693** | GPL-trained learned fusion (hurts; see multi-dataset analysis) |
| Good | BM25+CE reranker (published) | 0.688 | BM25 + cross-encoder |
| | | | |
| **JustSearch** | **Dense-only (Q8_0)** | **0.694** | nomic-embed Q8_0 vectors |
| **JustSearch** | **BM25+cross-encoder** | **0.673** | BM25 + MiniLM-L6-v2 CE (Q4) |
| **JustSearch** | **Dense-only (Q4)** | **0.671** | nomic-embed Q4 vectors |
| **JustSearch** | **BM25+Dense RRF** | **0.666** | BM25 + dense RRF fusion |
| Baseline | BM25 (BEIR paper) | 0.665 | Lexical |
| **JustSearch** | **BM25-only** | **0.660** | Lucene BM25 |

**Where JustSearch sits:** SPLADE hybrid with Q8_0 (0.706 on SciFact) exceeds
the published nomic-embed standalone fp32 (0.704) and reaches 99.4% of
published SPLADE-v3 (0.710). On SciFact this is competitive — the gap to open
SOTA (BGE-base 0.741) is entirely from the embedding model. Arguana and NFCorpus
were measured with Q4 only: Arguana dense gap -0.110 (0.370 vs 0.480), NFCorpus
dense gap -0.014 (0.324 vs 0.338). See the root cause investigation below.

**What would close the remaining gaps (priority order):**
1. **Ship Q8_0 embedding model** — **DONE.** Q8_0 is now the default. Recovers
   +0.025 dense nDCG on SciFact (0.669→0.694). Residual gap to published: -0.010.
2. ~~**Add nomic-embed task prefixes**~~ — **Implemented but null result.** Zero
   measurable impact on Q4_K_M. Kept in codebase since it's technically correct.
3. ~~**Train LambdaMART with GPL**~~ — **tested, hurts performance**. GPL-trained
   LambdaMART degrades nDCG by 0.009-0.10 across all 3 datasets. GPL synthetic
   queries overfit to the training distribution. Not a viable path without
   real user query data.
4. **Add standalone SPLADE eval mode** — **Already exists** (`SEARCH_MODE_SPLADE`).
   However, true 3-leg BM25+SPLADE+dense fusion does not exist — in HYBRID mode,
   SPLADE *replaces* BM25 as the sparse leg. See item 21 prerequisites.
5. **Replace RRF with Convex Combination (CC)** — **DONE.** `fuseWithCC()` with
   min-max normalization, routing in `HybridSearchOps`, config via
   `JUSTSEARCH_HYBRID_FUSION_STRATEGY=cc` + `JUSTSEARCH_HYBRID_CC_ALPHA`. Alpha
   calibration needs eval runs. See investigation + external validation sections.
6. **Combine SPLADE + cross-encoder** — currently impossible (SPLADE fires in
   HYBRID mode, cross-encoder in TEXT mode). **Now tracked as tempdoc 250 P1/1a.**
   No major production system gates rerankers by retrieval mode. CC calibration
   condition is moot (F13: all alphas produce identical results).
7. **Increase embedding context to 8192 tokens** — MTEB evaluations use 8192;
   JustSearch uses 2048. Minor impact for short-doc datasets but would help
   with long documents.
8. **SPLADE beta query-term pruning** — Prune lowest-weight 50% of SPLADE query
   terms before issuing FeatureField queries. BMP paper (SIGIR 2024):
   **+0.16 MRR@10 AND 1.8x latency** at β=0.5. ~30 lines in
   `LuceneIndexRuntime.java`. See tempdoc 249 external validation.
9. **Fix nDCG computation variant** — 1-line fix in `beir-eval-win.ps1`. Required
   for NFCorpus (graded relevance) comparability with published baselines.

**What's out of scope:** LLM listwise rerankers (0.75-0.79) require
multi-billion parameter models at inference time — not practical for a
local desktop search app.

### Recall@10

| Configuration | lexical | hybrid | vector |
|---------------|---------|--------|--------|
| BM25 only | 0.776 | — | — |
| Dense only | — | — | **0.807** |
| BM25 + Dense RRF | — | 0.777 | — |
| SPLADE + Dense RRF | 0.776 | **0.805** | — |
| SPLADE + Dense + LambdaMART | 0.776 | **0.805** | — |
| BM25 + cross-encoder | 0.786 | — | — |
| SPLADE 512-token | 0.776 | **0.805** | — |

### Key observations

1. **BM25 matches published baseline.** JustSearch BM25-only (0.660) is within
   0.005 of the BEIR paper BM25 (0.665). The integration is not lossy for BM25.

2. **SPLADE is the biggest quality lever.** SPLADE+Dense hybrid (0.704) is
   +4.4 nDCG points over BM25-only (0.660) and +3.8 over BM25+Dense RRF
   (0.666). This is the single most impactful component. SPLADE replaces BM25
   as the sparse retrieval leg in hybrid mode, providing superior term matching
   for scientific vocabulary.

3. **Dense retrieval outperforms BM25.** Dense-only (0.671 nDCG, 0.807 recall)
   outperforms BM25 alone (0.660, 0.776). The nomic-embed-v1.5 Q4 quantized
   model produces meaningful vector representations.

4. **BM25+Dense RRF provides a marginal boost.** BM25+Dense RRF (0.666) beats
   BM25-only (0.660) by only +0.006. BM25+Dense fusion without SPLADE is not
   significantly better than either leg alone on SciFact.

5. **Cross-encoder adds value over BM25.** BM25+cross-encoder (0.673) is +1.3
   over BM25-only. Still below SPLADE hybrid (0.704) without cross-encoder.

6. **LambdaMART with GPL training hurts.** GPL-trained LambdaMART degrades
   performance across all 3 datasets: SciFact -0.009, Arguana -0.10, NFCorpus
   -0.021 (hybrid nDCG vs RRF). GPL synthetic queries overfit to the training
   distribution — training eval nDCG (0.76-0.88) doesn't transfer to real
   BEIR queries. The lexical leg also drops, suggesting learned weights
   disrupt BM25/SPLADE scoring even without dense features.

7. **SPLADE 512-token = SPLADE 256-token on SciFact.** Both produce 0.704.
   SciFact abstracts are short enough that 256 tokens captures the full
   document. Longer-document datasets may show a difference.

8. **Recall tells the same story.** SPLADE hybrid recall@10 (0.805) > BM25
   (0.776). SPLADE retrieves more relevant documents, not just ranking them
   better.

### Caveats

- **Three datasets, different domains.** SciFact (scientific claims), Arguana
  (argument retrieval), NFCorpus (biomedical). Results vary significantly by
  dataset — no single config dominates everywhere. ArguAna has known label
  quality issues (96 duplicate document pairs where only one is labeled
  relevant) and tests a niche task (counter-argument matching) not
  representative of desktop search. See Evaluation Strategy section.
- **No repeated runs.** Each config was run once per dataset. Small differences
  (±0.003) are within noise for 300-query evals. Arguana (1,406 queries) has
  tighter confidence intervals.
- **LambdaMART trained with GPL hurts.** This was tested across all 3 datasets.
  GPL synthetic queries don't transfer to real BEIR queries. LambdaMART needs
  real user query data to be useful.
- **Quantization penalty.** Dense-only Q4 (0.669 SciFact) vs published nomic
  fp32 (0.704) is a -0.035 gap. Q8_0 recovers most of it (0.694, gap -0.010).
  See Root Cause Investigation §Gap 1 for full quantization comparison.
- **Large datasets not re-verified.** touche2020 (382K docs) and mldr-en (200K
  docs) old baselines had broken vectors but were not re-run due to corpus size.

### Multi-dataset observations (2026-03-01)

1. **SPLADE is dataset-dependent.** Huge win on SciFact (+0.04 over BM25),
   meaningful on NFCorpus (+0.03), zero impact on Arguana and FiQA. SPLADE excels
   on specialized vocabulary (scientific/biomedical) but adds nothing for
   argument-style or financial text.

2. **Dense retrieval is universally competitive.** Dense-only beats BM25 on all
   4 datasets (SciFact +0.009, Arguana +0.041, NFCorpus +0.014, FiQA +0.097).
   The strongest single-leg retriever on Arguana and FiQA.

3. **RRF fusion is dataset-dependent.** Helps on SciFact (0.666 vs 0.660 BM25),
   catastrophically hurts on Arguana (0.289 vs 0.329 BM25, -0.04) and FiQA
   (0.241 vs 0.317 dense-only, -0.076). Marginal on NFCorpus. When BM25 is weak,
   RRF dilutes the strong dense signal.

4. **GPL-trained LambdaMART consistently hurts.** Tested on 3 datasets:
   SciFact -0.009, Arguana -0.10, NFCorpus -0.021. The GPL→cross-encoder→LambdaMART
   pipeline produces models that overfit on synthetic queries. Training eval nDCG
   (0.76-0.88) does not predict real eval nDCG. This pipeline should not be
   enabled in production without real user query data.

5. **Cross-encoder consistently helps BM25.** SciFact +0.015, Arguana -0.005
   (marginal), NFCorpus +0.015. A reliable reranking signal that improves or
   maintains BM25 quality.

6. **SPLADE 512-token ≈ SPLADE 256-token.** Identical on SciFact, +0.003 on
   Arguana, -0.001 on NFCorpus. Longer context encoding provides negligible
   benefit for these document lengths. Worth testing on long-document datasets.

7. **Best config varies by dataset type:**
   - Scientific/specialized text (SciFact): SPLADE+Dense RRF (0.704)
   - Argument retrieval (Arguana): Dense-only (0.370)
   - Biomedical (NFCorpus): SPLADE+Dense RRF (0.337)
   - Financial Q&A (FiQA): Dense-only (0.317)
   - Production default (SPLADE+Dense RRF) is best or near-best on 2 of 4.

8. **Q8_0 quantization gap is domain-dependent.** SciFact gap to published fp32
   is small (-0.6%), but FiQA gap is large (-15.5%). All FiQA docs fit in
   2048 tokens (avg 779 chars) — context is not a factor. The gap is Q8_0
   quantization + HNSW approximation on a harder query distribution where
   financial jargon requires more precise embedding geometry.

---

## Goal

Run a component isolation matrix across BEIR datasets, then compare the results
to published baselines for the same models. The output is a single table showing
nDCG@10 per configuration per dataset, with published numbers alongside for
external reference.

### Status

- [x] SciFact isolation matrix (7 configs, 5 server sessions) — **complete**
  All 7 configs valid. SPLADE fix applied and re-run verified.
- [x] Re-run SciFact isolation with SPLADE fix — **done (2026-02-28)**
  SPLADE hybrid NDCG jumped from 0.669 (invalid) to 0.704 (valid).
- [x] Re-run with real embedding model — **done, dense vectors ARE working**
  (Lucene KNN active; `denseVectorEvidenceAvailableRate: 0` is a metrics bug
  from chunk merge overwriting debug scores — see long-term issues)
- [x] Script GPL training for LambdaMART isolation — **done (2026-02-28)**
  Session 3 now activates AI runtime, waits for GPL + LambdaMART training.
- [x] Run SciFact isolation with GPL training — **done (2026-03-01)**
  GPL trained (62,467 triples). LambdaMART trained but hurts eval (-0.009 hybrid).
- [x] Arguana isolation matrix — **done (2026-03-01)**
  Dense-only (0.370) best. RRF hurts. SPLADE adds nothing. LambdaMART catastrophic.
- [x] NFCorpus isolation matrix — **done (2026-03-01)**
  SPLADE+Dense RRF hybrid (0.337) best. Crossencoder helps (0.325 vs 0.310).
  LambdaMART hurts again (-0.021 hybrid). Closest to published baselines.
- [x] Fix broken touche2020/mldr-en baselines (vector leg contributed nothing) — **diagnosed (2026-03-01)**
  Root cause: old script didn't wait for chunkVectorsReady. Corpus too large
  (382K/200K docs) for re-run. 3-dataset matrix proves vector pipeline works.
- [x] FiQA isolation (Sessions 1-2) — **done (2026-03-02)**
  Dense-only (0.317) dominates. SPLADE+Dense RRF (0.317) adds zero lift.
  BM25+Dense RRF (0.241) hurts. Q8_0 gap to published fp32 is -15.5%.

### Isolation matrix design

Each row is a BEIR run on at least SciFact, Arguana, NFCorpus, and FiQA.

| Configuration | What it tests | Toggle |
|---------------|---------------|--------|
| BM25 only | Baseline sparse retrieval | `SPLADE_ENABLED=false`, mode=lexical |
| Dense only | Embedding quality alone | mode=vector |
| BM25 + Dense RRF | Vector contribution to BM25 | `SPLADE_ENABLED=false`, mode=hybrid |
| SPLADE + Dense RRF | Current production config | default, mode=hybrid+lexical |
| SPLADE + Dense + LambdaMART | Learned fusion vs fixed RRF | `LAMBDAMART_ENABLED=true`, mode=hybrid+lexical |
| BM25 + cross-encoder | Cross-encoder reranking value | `RERANK_ENABLED=true`, mode=lexical |
| SPLADE 512 tokens | Truncation cost (256→512) | `SPLADE_MAX_SEQ_LEN=512`, mode=hybrid+lexical |

Note: SPLADE+cross-encoder is not feasible with the current architecture —
cross-encoder only fires in TEXT mode. This constraint is tracked as tempdoc
250 P1 for removal. SPLADE-only (without dense) has a dedicated mode
(`SEARCH_MODE_SPLADE`) since the standalone SPLADE implementation.

### Published comparison (verified 2026-03-01)

| Source | SciFact | Arguana | NFCorpus | FiQA | Ref |
|--------|---------|---------|----------|------|-----|
| BM25 (BEIR paper, Anserini k1=0.9, b=0.4) | 0.665 | 0.315 | 0.325 | 0.236 | [1] |
| SPLADE-v3 (NAVER, standalone) | 0.710 | 0.509 | 0.357 | 0.374 | [2] |
| nomic-embed-text-v1.5 (MTEB, fp32, 8192 ctx) | 0.704 | 0.480 | 0.338 | 0.375 | [3] |
| JustSearch BM25-only | **0.660** | **0.329** | **0.310** | **0.220** | Q4 (SF/AR/NF), Q8 (FiQA) |
| JustSearch dense-only | **0.669** | **0.370** | **0.324** | **0.317** | Q4 (SF/AR/NF), Q8 (FiQA) |
| JustSearch BM25+Dense RRF | **0.666** | **0.289** | **0.313** | **0.241** | Q4 (SF/AR/NF), Q8 (FiQA) |
| JustSearch SPLADE+Dense RRF | **0.704** | **0.315** | **0.337** | **0.317** | Q4 (SF/AR/NF), Q8 (FiQA) |
| JustSearch BM25+reranker (Q4) | **0.675** | **0.324** | **0.325** | — | Q4 isolation |
| JustSearch SPLADE+LambdaMART (Q4) | **0.693** | **0.213** | **0.316** | — | Q4 isolation |
| JustSearch hybrid Q8_0 | **0.706** | — | **0.335** | — | CC sweep (SciFact + NFCorpus) |
| JustSearch dense Q8_0 | **0.694** | — | — | — | SciFact quant experiment |
| JustSearch dense F16 | **0.696** | — | — | — | SciFact quant experiment |
| JustSearch hybrid F16 | **0.706** | — | — | — | SciFact quant experiment |
| JustSearch dense+prefix A/B (Q4) | **0.669** | — | — | — | SciFact only, null result |
| JustSearch hybrid+prefix A/B (Q4) | **0.700** | — | — | — | SciFact only, null result |

**References:**
- [1] Thakur et al., "BEIR: A Heterogeneous Benchmark for Zero-shot Evaluation of IR Models," NeurIPS 2021
- [2] Lassance et al., "SPLADE-v3," arXiv:2403.06789, Table 2
- [3] nomic-ai/nomic-embed-text-v1.5, MTEB leaderboard + HuggingFace model card

---

## Long-term issues found during investigation

0. **~~Missing nomic-embed task prefixes~~ → NULL RESULT (P3).** A/B tested
   on SciFact: zero quality impact on Q4_K_M. Fix kept in codebase (technically
   correct per model card). Revert vs keep deferred.

1. **Chunk merge RRF overwrites per-leg debug scores.** `mergeChunkResults()`
   overwrites `debugScores.sparse`/`vector` with fused values. Original per-leg
   contributions are lost. **Impact:** `denseVectorEvidenceAvailableRate` always
   reports 0%. **Now tracked as tempdoc 250 P2 (items 1b, 2a-2c).** Files:
   `SearchOrchestrator.java:816-818`, `HybridFusionUtils.java:165-167`

2. **`Wait-ForIndexIdle` doesn't check `pendingEmbeddingCount`.** The function
   waits for `indexState == IDLE` and `queueDepth == 0`, but embedding backfill
   for parent docs is tracked separately via `pendingEmbeddingCount` in
   `IndexStatusOps`. Eval could start before all parent doc embeddings complete.
   **Mitigated by:** `chunkVectorsReady` check (when `requireChunkVectors=true`)
   which waits for chunk embedding coverage, but doesn't directly check parent
   doc embedding status.
   **Files:** `beir-eval-win.ps1:420-428`, `IndexStatusOps.java:117-128`

3. **SPLADE dev-path model doesn't auto-enable.** `SpladeModelDiscovery.resolve()`
   returns `autoDiscovered=false` for the dev fallback path
   (`models/splade/naver-splade-v3/`). Developers must explicitly set
   `JUSTSEARCH_SPLADE_ENABLED=true`. This is an instance of the broader
   index-query mismatch problem (tempdoc 250 P3) — no query-time warning
   that SPLADE fields are missing from the index.
   **Files:** `SpladeModelDiscovery.java:79-83`

4. **Debug scores in VECTOR-only mode are confusing.** Results show
   `matchedFields: ["vector"]` but `debugScores: {vector: 0, sparse: 0.57}`.
   Caused by chunk merge overwriting scores (issue #1 above). **Tracked as
   part of tempdoc 250 P2.** Files: `SearchOrchestrator.java:631-641`

---

## Evaluation Strategy (2026-03-01)

### Why BEIR alone is insufficient

BEIR is the standard zero-shot IR benchmark and remains useful for component
isolation and published-baseline comparison. However, it is a poor proxy for
desktop file search quality:

- **Short documents (<2000 chars).** All SciFact, Arguana, and NFCorpus docs
  fall below `CHUNK_THRESHOLD_CHARS`. Zero chunk documents are created, so the
  chunking pipeline — a core JustSearch feature — is never exercised.
- **Well-formed NL queries.** BEIR queries are natural-language questions.
  Desktop search queries are keyword fragments ("that pdf about taxes 2024"),
  vague refinding attempts, and mixed-format strings.
- **Noisy labels.** Elasticsearch found that 57.6% of unlabeled MS MARCO
  documents in the top-10 were actually relevant. ArguAna has 96 duplicate
  document pairs where only one copy is labeled relevant. Our scores are
  measured against incomplete ground truth.
- **No public desktop search benchmark exists.** Personal file collections
  cannot be shared (privacy), so no standard desktop IR benchmark has ever
  been built. Custom evaluation is unavoidable.

### Recommended benchmark portfolio

| Tier | Benchmark | Corpus | Queries | What it tests | Status |
|------|-----------|--------|---------|---------------|--------|
| Core | BEIR SciFact | 5,183 | 300 | Zero-shot scientific retrieval | Done |
| Core | BEIR FiQA | 57,638 | 648 | Financial Q&A, different domain | Not started |
| Core | LoCoV1 courtlistener | 2,000 | 2,000 | Long legal docs (avg 48K tokens), chunking | Not started |
| Core | LoCoV1 stackoverflow | 7,741 | 400 | Medium docs (avg 4.5K tokens), disjoint corpus | Not started |
| Extended | LoCoV1 legal_case_reports | 770 | 770 | Very long docs (avg 47K tokens), chunking stress | Not started |

**Dropped from original portfolio (infeasible corpus sizes):**
- MS MARCO (8.8M passages, ~512 hours to index), NQ (2.7M, ~155 hours),
  HotpotQA (5.2M, ~303 hours). All exceed desktop indexing capacity at
  ~4.8 docs/sec. No subset strategy exists for these — BEIR evaluates
  against the full corpus.
- BIRCO WhatsThatBook — dropped pending feasibility check.

All retained benchmarks have published baselines. FiQA is a standard BEIR
dataset (same download/eval harness). LoCoV1 tasks require a format
conversion script (~50 lines) to produce BEIR-compatible files.

**Keyword-style query gap:** No feasible public benchmark has terse keyword
queries. All BEIR datasets ≤100K docs use NL questions. This gap is
acknowledged but unresolvable with current public benchmarks.

ArguAna is deprioritized: it tests counter-argument matching (not a desktop
search pattern) and has known label quality issues. Existing ArguAna results
remain valid measurements but future eval effort is better spent elsewhere.

### Implementation readiness per benchmark

**FiQA (item 8) — ready to run, zero harness changes.**
Standard BEIR dataset. Run `beir-eval-win.ps1 -Dataset fiqa`. The harness
already lists it as a recognized dataset. ~3.3 hours indexing (57K docs at
4.8 docs/sec). No chunks created (FiQA docs are short financial Q&A).

**LoCoV1 courtlistener + stackoverflow (item 9) — needs conversion script + time estimate.**

Format conversion (~50 lines PowerShell or Node.js):
- Download JSONL from HuggingFace: `hazyresearch/LoCoV1-{Documents,Queries}`
- Map `pid`→`_id`, `document`→`text`, empty title (LoCoV1 has no title field)
- Map `qid`→`_id`, `query`→`text`
- Extract inline `answer_pids` into `qrels/test.tsv` (binary relevance)
- Place in `tmp/beir-cache/<task>/raw/<task>/` structure, run with `-SkipDownload`

Time estimates (chunk size = 500 tokens, overlap = 50, stride = 450):

| Task | Docs | Avg tokens | Chunks/doc | Total chunks | Est. embed time |
|------|------|-----------|------------|--------------|-----------------|
| courtlistener | 2,000 | 48,000 | ~107 | ~214,000 | ~12 hours |
| stackoverflow | 7,741 | 4,500 | ~10 | ~77,000 | ~4.5 hours |
| legal_case_reports | 770 | 47,500 | ~106 | ~82,000 | ~4.7 hours |

Embedding time dominates. courtlistener is the heaviest (214K chunks). Run
stackoverflow first as a faster smoke test of the chunking pipeline.

**P@1 addition (item 7) — trivial, ~10 lines of PowerShell.**
MRR is already computed in `beir-eval-win.ps1`. P@1 = check if first result
has `rel > 0`. Add alongside existing MRR computation. No uncertainty.

**Haiku judge full run (item 7) — two scripts + manual execution.**
300 SciFact queries × 10 docs = 3,000 pairs = 100 Task calls (3 queries/call).
Each call takes ~30 seconds → ~50 minutes per session. Concrete steps:
1. Prompt builder script: read per-query results + corpus.jsonl, emit 100 prompts
2. Manual execution: 100 Task calls with `model: "haiku"` (not automatable)
3. Response parser script: extract scores from Haiku's markdown tables into
   `llm-qrels.tsv` (qid, did, llm_score)
4. Metric recomputation: existing harness accepts alternative qrels file

### Metrics beyond nDCG@10

Desktop search is primarily a **known-item refinding** task ("find that one
document I remember"). nDCG@10 measures general ranking quality but doesn't
capture whether the user's one target document appears first.

| Metric | Role | Why it matters |
|--------|------|---------------|
| **MRR** | Primary for known-item search | "On average, the right document is at position X" |
| **Precision@1** | User satisfaction proxy | Users usually click only the first result |
| **nDCG@10** | Ranking quality (current) | Standard metric, enables published-baseline comparison |
| **Recall@10** | Pipeline completeness | Did we surface all relevant documents in the top 10? |

**Recommendation:** Report all four. Use MRR as the headline metric for
known-item scenarios and nDCG@10 for exploratory search.

**External validation:** SentenceTransformers' NanoBEIREvaluator reports the
same metric families (nDCG@10, MRR@10, MAP@100, Recall@k, Accuracy@k) across
13 downsized BEIR datasets as the standard evaluation portfolio. JustSearch's
current 3 datasets vs NanoBEIR's 13 is a coverage gap — FiQA (item 8) and
LoCoV1 tasks partially close it. Source: tempdoc 249 SentenceTransformers
finding 6.

**Significance testing gap:** Neither JustSearch nor SentenceTransformers'
evaluation framework includes statistical significance testing. JustSearch's
nDCG differences of <0.01-0.02 on 300-query evals are likely within noise.
Per-query scores (not just aggregates) should be stored to enable bootstrap
resampling or paired t-tests in future.

### LLM-as-judge for label quality validation

BEIR labels are noisy (57.6% of unlabeled MS MARCO top-10 docs are actually
relevant). Rather than building a custom benchmark, we can improve label
quality on existing public benchmarks using an LLM judge.

**Tool:** Haiku 4.5 subagent via Claude Code Task tool (`model: "haiku"`).
Consumes ~2-3% of Max 20x weekly budget for 3,000 judgments — 5x cheaper
than Opus, 3x cheaper than Sonnet. LLM-as-judge achieves ~80% agreement
with human judgments (Elasticsearch study using Phi-3-mini; Haiku is well
above that capability threshold).

**Workflow:**
1. Take top-10 results from existing BEIR eval runs
2. Spawn Haiku subagents to judge query-document relevance (0-3 scale),
   batching 20-30 pairs per call to reduce overhead
3. Compute MRR, Precision@1, nDCG@10 from LLM labels alongside BEIR qrels
4. Compare LLM-labeled metrics to qrel-labeled metrics to quantify label noise

**Constraints:**
- Not automatable — subscription-based (not API), must be spawned manually
  per session. Best for spot-checking and validation, not CI-style runs.
- Pairwise comparison outperforms pointwise scoring for accuracy.

**Validation step:** Judge 50 pairs with both Haiku and Sonnet, check
inter-model agreement before committing to full 3,000-pair runs.

**What this enables:**
- MRR and Precision@1 with higher-quality labels
- Quantify how noisy BEIR qrels are for our result sets
- Validate whether nDCG@10 differences between configs are real vs artifacts

### LLM-judge validation results (2026-03-01)

Validated the approach on SciFact query-document pairs across four probes,
scaling from 13 to 60 pairs. All probes used 0-3 relevance scoring with
binary thresholding at ≥2.

| Probe | Pairs | Model | Agreement | Recall | FN | Key finding |
|-------|-------|-------|-----------|--------|----|-------------|
| Small-scale | 13 | Haiku 4.5 | 92% | 100% | 0 | Validates approach |
| Small-scale | 13 | Sonnet 4.6 | 100% | 100% | 0 | Stricter, 3x cost |
| Batch | 50 | Haiku 4.5 | 92% | 100% | 0 | Reverses Sonnet advantage at scale |
| Batch | 50 | Sonnet 4.6 | 88% | 75% | 1 | Strict reading causes FN |
| 3-query batch | 30 | Haiku 4.5 | 93% | 100% | 0 | Optimal batch size |
| 6-query batch | 60 | Haiku 4.5 | 97% | 75% | 1 | Score drift: 20% of overlapping pairs changed |

**Recommended defaults:**
- **Model:** Haiku 4.5 — 100% recall across all probes, zero false negatives,
  1/3 the cost of Sonnet. Sonnet's stricter reading causes false negatives on
  borderline-relevant docs that BEIR labels as relevant.
- **Batch size:** 3 queries x 10 docs (30 pairs) per call — highest recall, no
  score drift. At 6 queries, 20% of overlapping pairs changed scores between
  runs, including one TP→FN flip.
- **Threshold:** Binary at ≥2 — consistently 92-93% agreement with BEIR qrels.
  Score 1 is noise (topic overlap without actual relevance).
- **Escalation:** Sonnet spot-check only when Haiku ≥2 and BEIR=0, to distinguish
  LLM error from BEIR label noise.

**On BEIR label quality:** Most disagreements are BEIR label noise, not LLM
errors. False positives concentrate on queries with topic-adjacent documents
(e.g., TCR signaling docs labeled 0 despite discussing the query's exact
molecular mechanisms). The primary value of LLM-as-judge is quantifying this
noise — when both models agree a doc is relevant but BEIR says 0, the BEIR
label is likely wrong.

**Sources:**
- Elasticsearch, "Evaluating Search Relevance" Parts 1-2 (BEIR label quality)
- Saad-Falcon et al., "Benchmarking Long-Context Retrieval" (LoCoV1), ICML 2024, arXiv:2402.07440
- Parry et al., "BIRCO: Complex Objectives IR Benchmark," arXiv:2402.14151
- MS MARCO: microsoft.github.io/msmarco
- MTEB v2: huggingface.co/blog/isaacchung/mteb-v2

---

## Root Cause Investigation (2026-03-01)

### Methodology

After completing the 3-dataset isolation matrix, an in-depth investigation was
conducted to identify the root causes of quality gaps between JustSearch and
published baselines. The investigation combined:
1. Per-query statistical analysis of all isolation results
2. Codebase investigation (BM25 params, embedding pipeline, chunking, RRF config)
3. Internet research (verified published baselines, quantization studies, RRF literature)

### Corrected published baselines

The tempdoc's original "~" approximations were significantly inaccurate. Verified
published numbers (with sources):

| Model | Dataset | Previous (approx) | Verified | Source |
|-------|---------|-------------------|----------|--------|
| nomic-embed-v1.5 | SciFact | ~0.69 | **0.704** | MTEB leaderboard |
| nomic-embed-v1.5 | Arguana | ~0.43 | **0.480** | MTEB leaderboard |
| nomic-embed-v1.5 | NFCorpus | ~0.34 | **0.338** | MTEB leaderboard |
| SPLADE-v3 | SciFact | ~0.72 | **0.710** | arXiv:2403.06789 Table 2 |
| SPLADE-v3 | Arguana | ~0.50 | **0.509** | arXiv:2403.06789 Table 2 |
| SPLADE-v3 | NFCorpus | ~0.34 | **0.357** | arXiv:2403.06789 Table 2 |

Key: nomic-embed published numbers are fp32 precision, 768-dim, 8192-token
context, with proper task prefixes (`search_document:` / `search_query:`).

### Gap 1: Dense retrieval (JustSearch vs published nomic-embed)

| Dataset | JS Dense Q4 | JS Dense Q8_0 | JS Dense F16 | Published fp32 | Q4 Gap | Q8 Gap |
|---------|-------------|---------------|--------------|----------------|--------|--------|
| SciFact | 0.669 | **0.694** | **0.696** | 0.704 | -0.035 | **-0.010** |
| Arguana | 0.370 | — | — | 0.480 | -0.110 | — |
| NFCorpus | 0.324 | — | — | 0.338 | -0.014 | — |

**Root cause 1 (NEGLIGIBLE for Q4 — confirmed via experiment): Missing nomic-embed task prefixes.**

JustSearch does not prepend the task instruction prefixes (`"search_document: "`
/ `"search_query: "`) that the model was trained with. However, **A/B testing
on SciFact showed zero measurable impact** (dense-only nDCG@10: 0.669 with and
without prefixes). The Q4_K_M GGUF quantization appears insensitive to these
prefixes — the quantized embedding space doesn't differentiate between prefixed
and unprefixed inputs.

The prefix fix was implemented and tested (Step 7) but does not explain the
quality gap. This finding eliminates what was thought to be the #1 quality
lever, shifting focus to other root causes (quantization, HNSW approximation,
chunk-merge confound).

**Why Q4 kills prefix sensitivity:** nomic-embed-text-v1.5 was trained with
MNRL (scale=20.0, temperature=0.05), which creates fine-grained angular
geometry in embedding space. Task prefixes shift embeddings within this
geometry. Q4_K_M quantization adds enough noise to the weight matrices
that these subtle prefix-induced shifts are swamped, while larger semantic
differences survive. This is consistent with arXiv 2601.14277 (up to 20%
IFEval loss from quantization = instruction-following degradation). Q8_0
preserves enough precision that prefix sensitivity may partially recover —
**untested**. Source: tempdoc 249 SentenceTransformers finding 1.

**Status:** Fix implemented but produces no quality gain at Q4. Decision on
whether to keep/revert deferred to user. Worth re-testing at Q8_0.

**Root cause 2 (HIGH impact — MEASURED via experiment): Q4 GGUF weight quantization.**

JustSearch uses `nomic-embed-text-v1.5.Q4_K_M.gguf` (81 MiB) while published
results use fp32 (262 MiB). **Now directly measured** (Step 8):

| Quant | Size | Dense nDCG | Hybrid nDCG | vs Q4 delta |
|-------|------|-----------|-------------|-------------|
| Q4_K_M | 81 MiB | 0.669 | 0.700 | baseline |
| Q8_0 | 139 MiB | **0.694** | **0.706** | **+0.025 / +0.006** |
| F16 | 261 MiB | **0.696** | **0.706** | **+0.027 / +0.006** |

Key findings:
- Q4→Q8_0 recovers **+0.025 dense nDCG** (3.7% relative) — the bulk of the gap
- Q8_0→F16 adds only +0.002 dense — **diminishing returns past Q8_0**
- Hybrid improvements are smaller (+0.006) because SPLADE+BM25 legs are unchanged
- Residual gap to published fp32 (0.704): Q8_0 is -0.010, F16 is -0.008
- Residual gap from HNSW approximation (M=16, approximate vs exact exhaustive)
  plus minor residual quantization noise. Not a context issue (all docs <2048).
- Indexing throughput identical across quant levels (~4.8 docs/sec GPU)

**Recommendation: Ship Q8_0.** 60 MiB larger, negligible speed impact, recovers
72% of the Q4-to-published gap. F16 at 3x the size of Q4 recovers only 77%.

**Sources:** arxiv 2505.00105 (embedding quantization), arxiv 2601.14277
(unified llama.cpp quantization eval), nomic-embed-text-v1.5-GGUF model card.

**Root cause 3 (NOT a factor for these datasets): Chunk-merge RRF.**

Previously suspected as a confound, but codebase investigation (Step 7) revealed
that SciFact, Arguana, and NFCorpus documents are all under 2000 chars (the
`CHUNK_THRESHOLD_CHARS` in `ChunkDocumentWriter`). For docs below this threshold:
- **Zero chunk documents are created** (`indexChunks()` returns 0)
- No chunk search results exist to merge
- The chunk-merge RRF has nothing to fuse — results are pure whole-doc search
- The pipeline is **functionally equivalent** to standard BEIR eval

This root cause is only relevant for long-document corpora (>2000 chars).

**Root cause 4 (NOT a factor for these datasets): 2048-token embedding context.**

JustSearch uses 2048-token context (`EmbeddingService.java:161`) while MTEB
evaluations use the model's full 8192-token context. All documents in SciFact,
Arguana, and NFCorpus fit within 2048 tokens (avg 300-400 tokens), so this has
zero impact on these datasets. Only relevant for long-document corpora.

### Gap 2: SPLADE (JustSearch vs published SPLADE-v3)

| Dataset | JustSearch SPLADE+Dense | Published SPLADE-v3 | Gap |
|---------|------------------------|--------------------|----|
| SciFact | 0.704 (hybrid) | 0.710 | -0.006 |
| Arguana | 0.315 (hybrid) | 0.509 | **-0.194** |
| NFCorpus | 0.337 (hybrid) | 0.357 | -0.020 |

**Primary cause: Unfair comparison.** Published SPLADE-v3 is standalone sparse
retrieval. JustSearch's isolation eval uses SPLADE in HYBRID mode (fused with
dense via RRF). On Arguana, RRF fusion actively hurts. Standalone SPLADE mode
(`SEARCH_MODE_SPLADE`) now exists but wasn't used in the isolation matrix.

**Verification:** Per-query analysis confirmed SPLADE IS firing in hybrid mode
(different sparse scores, only 7.6/10 top-10 overlap with BM25 hybrid).
SPLADE lexical mode is pure BM25 (100% identical scores across all 1,406
Arguana queries) — this is expected since TEXT mode bypasses SPLADE.

### Gap 3: RRF fusion hurting on Arguana

**Per-query analysis (1,406 Arguana queries):**

| Metric | BM25 | Dense | BM25+Dense RRF |
|--------|------|-------|----------------|
| Mean nDCG@10 | 0.329 | 0.370 | 0.289 |
| Zero-nDCG rate | 30.5% | 25.8% | **40.0%** |

RRF improves only **21 queries** while hurting **434 queries** (vs BM25).
An oracle per-query selector (BM25 vs Dense) would score **0.426** vs RRF's
0.289 — a gap of +0.137 nDCG points.

**Why RRF hurts:** When BM25 and Dense disagree on rankings (moderate
correlation: Pearson r=0.557), RRF averages their rank positions. If a relevant
document is ranked 3rd by Dense but 50th by BM25, RRF pushes it down. With
Arguana's single-relevant-doc-per-query structure, even small rank changes
can flip nDCG from 0.5 to 0.0. RRF's zero-nDCG rate (40%) is worse than
either leg alone because fusion pushes relevant docs out of top-10.

**Literature:** Bruch et al. (ACM TOIS 2024) found convex combination (CC)
outperforms RRF in both in-domain and out-of-domain settings. RRF discards
score magnitude information, which is particularly harmful when one retriever
strongly dominates. The "Balancing the Blend" paper (arXiv 2508.01405, 2025)
independently confirms this as the **weakest-link phenomenon**: adding a weak
retriever via RRF can hurt performance relative to the strong retriever alone.
Examples from that paper: Touche-2020 BM25-only=0.650, hybrid RRF=0.604
(-0.046); TREC-COVID BM25-only=0.839, all hybrid configs score lower.
JustSearch's Arguana result (dense-only 0.370, RRF 0.289) is a textbook
instance of this documented property. CC mitigates this because near-zero α
suppresses the weak leg rather than mixing rank positions.

### Gap 4: BM25 quality

| Dataset | JustSearch BM25 | Published BM25 | Gap |
|---------|----------------|---------------|-----|
| SciFact | 0.660 | 0.665 | -0.005 |
| Arguana | 0.329 | 0.315 | **+0.014** |
| NFCorpus | 0.310 | 0.325 | **-0.015** |

JustSearch BM25 uses k1=0.9, b=0.4 (`ComponentsFactory.java:216-217`),
matching the BEIR paper's Anserini defaults. SciFact and Arguana are within
noise. NFCorpus gap (-0.015) may be due to title field handling: BEIR paper
uses Anserini which indexes title as a separate boosted field; JustSearch
concatenates title into body text (`beir-eval-win.ps1:588`).

### Gap 5: GPL-trained LambdaMART

Consistently hurts across all 3 datasets. Root cause: GPL generates synthetic
queries from an LLM, scores with cross-encoder, trains LambdaMART. The
synthetic queries don't match real BEIR query distribution. Training eval nDCG
(0.76-0.88) doesn't transfer to test queries. Additionally, learned feature
weights disrupt the sparse/dense score balance even for lexical-only queries
(SciFact lexical drops from 0.660 to 0.642 with LambdaMART, despite LambdaMART
theoretically not affecting pure BM25 scoring).

**Mechanism (SentenceTransformers v4 documentation):** This maps to a documented
anti-pattern — training with hard negatives only. GPL's synthetic query generation
produces a narrow distribution of hard negatives (documents the cross-encoder
scores highly but aren't the gold answer) with no random/easy negatives to
calibrate the discrimination threshold. SentenceTransformers' `mine_hard_negatives()`
utility recommends mixing 60-70% hard + 30-40% random negatives with `range_min`
to skip top matches (likely false negatives). GPL cannot produce this mix because
it has no real user queries. **Verdict: GPL-LambdaMART is not recoverable without
real user query data.** Source: tempdoc 249 SentenceTransformers finding 2.

### Data integrity note

The `isolation-embed-scifact` backup directory contains Arguana data (not
SciFact). The backup was created before the first Arguana attempt (which used
the same output directory), accidentally capturing Arguana data. Per-query
SciFact data is lost, but all aggregate metrics were recorded in this tempdoc
before being overwritten.

### Investigation conclusion (2026-03-01)

The quality gap between JustSearch and published baselines is now fully explained:

**Dense retrieval gap (Q4: 0.669 vs published 0.704 on SciFact = 5%):**
- **Q4_K_M quantization: ~5% (confirmed as entire gap)** — consistent with
  published weight quantization literature. The quantization also destroys the
  model's sensitivity to task instruction prefixes, explaining the prefix null
  result. **Resolved:** Q8_0 recovers to 0.694 (-0.010 residual). See finding 13.
- **Task prefixes: 0% (disproven)** — A/B experiment showed zero impact.
- **Chunk-merge RRF: 0% (eliminated)** — not applicable for short docs.
- **2048 context limit: 0% (eliminated)** — all test docs fit in 2048 tokens.

**Arguana dense gap (0.370 vs 0.480 = 23%):**
- Q4 quantization + task-specific sensitivity. Argument-matching requires more
  precise embeddings than fact verification. Q4 noise disproportionately affects
  nuanced semantic relationships.
- Training data composition factor: nomic-embed-text-v1.5's MNRL training uses
  in-batch negatives. Argument text has high lexical overlap between relevant and
  irrelevant pairs, making it harder for in-batch negatives to provide clean signal.
  Models trained with GISTEmbedLoss (false-negative suppression) may perform better
  on argument-style corpora. Source: tempdoc 249 SentenceTransformers findings 1, 4.

**RRF fusion gap (Arguana):**
- RRF discards score magnitude, hurting when retrievers disagree. 40% zero-nDCG
  rate. Convex combination (CC) would address this. This is a documented
  phenomenon: "Balancing the Blend" (arXiv 2508.01405) shows adding a weak
  retriever via RRF hurts vs the strong retriever alone (weakest-link effect).
  CC mitigates because near-zero α suppresses the weak leg.

### Active items

*Priorities revised by critical analysis (see §Critical Analysis below).
Items marked ⚠ have findings that change their assessment.*

| # | Finding | Impact | Effort | Priority |
|---|---------|--------|--------|----------|
| 2f | ~~CC alpha calibration sweep~~ | **DONE.** Results: All alpha values (0.30–0.70) produce identical nDCG on both datasets. CC ≈ RRF on SciFact/NFCorpus. See Completed items for full data. | ZERO | **DONE** |
| 8 | ~~Add FiQA to eval harness~~ | **DONE.** FiQA Sessions 1-2 complete. Dense-only (0.317) dominates; SPLADE adds zero lift; BM25+Dense RRF hurts (0.241). Q8_0 gap to published fp32 is -15.5% (larger than SciFact -0.6%). See execution log Step 9. | LOW | **DONE** |
| 11 | SPLADE beta query-term pruning | Keep top 50% of query terms by weight. ~30 lines in `LuceneIndexRuntime.java`. **⚠ F1: BMP paper's "+0.16 MRR" is +0.16 percentage points (0–100 scale) = +0.0016 on 0–1 scale. Quality-neutral; real value is 1.8x latency. Not bottlenecked at desktop scale. Conditional on Phase 2 data.** | LOW | **P2** ⬇ |
| 2b | ~~Grid-search RRF k~~ | **⚠ F13: Drop.** CC sweep showed all fusion parameters are irrelevant — SPLADE+dense result sets overlap near-completely. Changing k cannot improve nDCG when the bottleneck is single-leg retrieval quality. | ZERO | **DROP** |
| 2e | ~~Unsupervised TF-IDF or RSD weighting~~ | **⚠ F13: Drop.** Adaptive fusion is unnecessary — static CC at any alpha equals RRF. The problem isn't fusion; it's single-leg quality. | MEDIUM | **DROP** |
| 6 | Embedding context 2048→8192 (long-doc capability) | **Not a config-only change.** Current GGUF metadata reports `context_length=2048`; override requires llama-server flags `--ctx-size 8192 --rope-scaling yarn --rope-freq-scale 0.75`. RoPE×Q8_0 interaction untested — could regress short-doc quality. VRAM ~550 MB at 8192 (fits RTX 4070). Latency: O(n²) attention, 4x context ≈ up to 16x compute per embed. **Prerequisite:** short-doc regression test (SciFact at 8192, confirm nDCG doesn't drop). **Not a quality lever for current benchmarks** — all 4 BEIR datasets are <2048 tokens. This is a long-document capability gap, not a quality gap explanation. | LOW | P3 |
| 20 | Matryoshka 256-dim embedding truncation | -1.24 MTEB (-2%), 67% storage, ~3x KNN speed. **Research findings:** llama.cpp applies layer norm but also L2-normalizes full 768-dim output; need `--embd-normalize -1` for raw post-layer-norm output, then truncate to 256-dim and re-normalize in Java. Quantization × Matryoshka interaction untested — quality loss may compound. **Migration scope:** schema change (768→256 for `SchemaFields.VECTOR` and chunk vector), full reindex required, runtime dimension validation in `LuceneIndexRuntime`. Not a post-processing patch — this is a cross-cutting schema + index + runtime migration. | MEDIUM | P3 |
| 21 | N-way weighted CC for 3-leg fusion | **Prerequisites (all missing):** (a) Independent BM25 and SPLADE retrieval legs — currently SPLADE *replaces* BM25 as the sparse leg in HYBRID mode, not additive. (b) Chunk-level SPLADE parity — even if doc-level fusion becomes 3-leg, chunk merge path still does 2-leg fusion, creating inconsistent scoring. (c) N-way weighted fusion API — current `fuseWithRRF`/`fuseWithCC` are 2-leg only. Requires tempdoc 250 Phase 4 (component activation model) as architectural prerequisite. | MEDIUM | P3 |

### Dropped items

| # | Reason |
|---|--------|
| 17 | **⚠ F6: Drop.** No evidence of reranker misbehavior. Cross-encoder only fires in TEXT mode (not HYBRID default). Source (qmd) has different architecture. Three strikes. |
| 13 | **⚠ F8: Drop.** lucene-misc NOT on classpath. Latency-only, not bottlenecked at desktop scale. |
| 14 | Drop. Latency-only, not bottlenecked at desktop scale. |
| 16 | **⚠ F11: Drop.** No production system clamps SPLADE weights (Anserini, OpenSearch, Vespa, Qdrant, Pinecone — none). log1p activation already compresses (practical max ~3.0). FLOPS regularization controls magnitude at training time. "Wacky weights" (Mackenzie 2021) are an efficiency problem, not quality. Clamping destroys discrimination. |
| 15a | **⚠ F12: Drop.** Lucene 10.3.1 (our version) has built-in filtered ANN fallback: auto exact-search when `filter.cost() <= k`, auto-fallback when HNSW visits too many nodes, ACORN-1 (merged 10.2.0) for 5x faster filtered search. Existing `vectorEfSearchOverride` provides oversampling. Manual safety net is redundant. |
| 15b | Drop. Prerequisite 15a dropped — Lucene handles filtered ANN internally. |
| 2b | **⚠ F13: Drop.** CC sweep null result — all fusion parameters irrelevant on SciFact/NFCorpus. |
| 2e | **⚠ F13: Drop.** Adaptive fusion unnecessary — static CC equals RRF. Bottleneck is single-leg quality. |

### Separate investigation (own tempdoc)

| # | Why |
|---|-----|
| 18 | **⚠ F9: Highest-impact deferred item.** Inference-free SPLADE eliminates ONNX query-time inference + improves quality (+2.9 nDCG@10). Architecturally transformative — needs own tempdoc for model compatibility investigation. |

### Completed items

> Items 1 (prefixes — null result), 2a (SPLADE eval mode), 2c (CC fusion),
> 2d (QPP move), 3 (Q8_0 model), 5 (disable LambdaMART), 7 (P@1 + Haiku
> judge), 9 (LoCoV1 converter), **10 (nDCG gain → linear)**, **19 (CC
> zero-exclusion toggle)** are done. 12 (ATAN — dominated by min-max CC) is
> dropped. See git history for details.
>
> **Item 10 details:** Changed `beir-eval-win.ps1` `Compute-Dcg` from
> `gain = 2^rel - 1` to `gain = rel` (linear, matches trec_eval/BEIR).
> Binary datasets (SciFact, ArguAna) unaffected (`2^1-1 = 1 = rel(1)`).
> NFCorpus (graded rel 0/1/2) changes: grade-2 docs weighted 2x (was 3x)
> vs grade-1. Numbers now comparable to published BEIR baselines.
>
> **Item 19 details:** Added `ccZeroExclude` to `ResolvedConfig.HybridSearch`
> (env: `JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE`, default false). When true,
> `fuseWithCC()` gives single-leg docs their full normalized score instead of
> penalizing with 0.0 for the missing leg. Uses `containsKey()` check before
> normalization to avoid the OpenSearch arithmetic_mean bug (absent and lowest-
> scoring both map to 0.0 after min-max). Files: `ResolvedConfig.java`,
> `ResolvedConfigBuilder.java`, `HybridFusionUtils.java`, `HybridSearchOps.java`,
> `HybridFusionUtilsTest.java` (3 new tests).
>
> **Item 2f details: CC alpha calibration sweep — null result.**
> Ran 19 configs per dataset: 9 alpha values (0.30–0.70, step 0.05) × 2
> variants (penalty/zero-exclusion) + RRF baseline. Q8_0 embedding model.
> Script: `run-ranking-experiments.ps1 -Step cc-sweep`.
>
> SciFact results (300 queries, 5184 docs):
> | Alpha range | Penalty nDCG@10 | ZeroExcl nDCG@10 | RRF nDCG@10 | BM25 |
> |-------------|-----------------|------------------|-------------|------|
> | 0.30–0.70   | 0.7054–0.7055   | 0.7055           | 0.7055      | 0.6625 |
>
> NFCorpus results (323 queries, 3633 docs):
> | Alpha range | Penalty nDCG@10 | ZeroExcl nDCG@10 | RRF nDCG@10 | BM25 |
> |-------------|-----------------|------------------|-------------|------|
> | 0.30–0.70   | 0.3341–0.3351   | 0.3351           | 0.3351      | 0.3088 |
>
> **Conclusion (F13):** CC fusion and RRF produce equivalent rankings on both
> BEIR datasets. Alpha has negligible effect (≤0.001 nDCG). Zero-exclusion
> has zero effect (both legs cover same documents — SPLADE+dense result set
> overlap is near-complete). The reason: on these small academic corpora, both
> retrieval legs find essentially the same documents in their top results,
> so the fusion strategy is irrelevant — only the individual leg quality
> matters (SPLADE+dense > BM25+dense by the +0.04 margin from adding SPLADE).
> Practical implication: **keep RRF as production default** — it's simpler,
> rank-based (no normalization needed), and produces identical results. CC
> fusion is a valid alternative but provides no quality uplift on these
> datasets. Items 2b (RRF k grid-search) and 2e (unsupervised weighting)
> are wasted effort — changing fusion parameters cannot improve nDCG when
> the bottleneck is single-leg retrieval quality, not fusion.

### Next actions (revised — eval before code, see §Critical Analysis)

**Phase 1 — Measurement foundations: DONE.**
- ~~**Item 10**: Fix nDCG gain → linear~~ — Done. 1-line fix in `beir-eval-win.ps1`.
- ~~**Item 19**: CC zero-exclusion toggle~~ — Done. ~30 lines across 4 files +
  3 new tests. `JUSTSEARCH_HYBRID_CC_ZERO_EXCLUDE=true` to enable.
- ~~**Item 16**: Dropped (F11).~~ ~~**Item 15a**: Dropped (F12).~~

**Phase 2 — Calibration: DONE (CC sweep null result — see F13).**
- ~~**Item 2f**: CC alpha sweep~~ — Done. 19 configs × 2 datasets. Result: CC ≈
  RRF on SciFact and NFCorpus. Alpha and zero-exclusion are irrelevant.
  Items 2b and 2e dropped as a consequence.
- **Item 8**: FiQA dataset run (~3.3h indexing). Still pending — provides
  a different-domain baseline but won't change the CC conclusion.

**Phase 3 — Data-informed decisions (after Phase 2 data):**
- **Item 11**: SPLADE beta pruning — latency-only benefit (+0.0016 MRR quality),
  not bottlenecked at desktop scale. **Recommend skip.**
- ~~**Item 2b**: RRF k optimization~~ — **Dropped (F13).** Fusion params irrelevant.
- ~~**Item 2e**: Unsupervised weighting~~ — **Dropped (F13).** Fusion params irrelevant.

**Strategic conclusion (Phase 2 data):** The CC sweep's null result resolves
the central question of tempdoc 245: **the bottleneck is single-leg retrieval
quality, not fusion**. Five independent sources predicted CC > RRF, but that
effect requires datasets where the retrieval legs have low overlap (i.e., one
leg finds relevant documents the other misses). On SciFact and NFCorpus,
SPLADE+dense overlap is near-complete, so any weighted combination produces
the same ranking. The productive path forward is **improving individual leg
quality** (inference-free SPLADE [item 18], better embedding models), not
tuning fusion parameters.

---

## Investigation: Standalone SPLADE Eval Mode (item 2a)

### Current state

SPLADE search is fully functional — `LuceneIndexRuntime.searchSplade()` exists
and works as a standalone sparse retrieval method. It's called via
`searchHybridSplade()` which passes it as the sparse leg to `executeHybrid()`.
The **only** barrier is mode gating: SPLADE only fires inside
`case SEARCH_MODE_HYBRID` in `SearchOrchestrator.execute()`.

### What exists

- `SpladeEncoder.encode(String text)` → `Map<String, Float>` — query encoding
  via ONNX (`modules/indexer-worker/.../splade/SpladeEncoder.java`)
- `LuceneIndexRuntime.searchSplade(queryWeights, limit, filters)` — builds
  `BooleanQuery` of `FeatureField.newLinearQuery` SHOULD clauses, returns ranked
  results (`modules/adapters-lucene/.../runtime/LuceneIndexRuntime.java:1520-1546`)
- SPLADE FeatureField data is indexed on both parent docs and chunks
  (`SpladeBackfillOps` processes `CHUNK_CONTENT` and `CONTENT`)
- `SpladeModelDiscovery.resolve()` — model auto-discovery (requires explicit
  `JUSTSEARCH_SPLADE_ENABLED=true` for dev path)

### Changes needed

| # | File | Change |
|---|------|--------|
| 1 | `ipc-common/.../indexing.proto:11-15` | Add `SEARCH_MODE_SPLADE = 3;` to enum |
| 2 | `SearchOrchestrator.java:167` | Add `case SEARCH_MODE_SPLADE` — encode with SPLADE, call `searchSplade()` |
| 3 | `KnowledgeHttpApiAdapter.java:809-818` | Add `case "splade"` to `parseModeOrDefault()` |
| 4 | `SearchOrchestrator.java:152-163` | Add `case SEARCH_MODE_SPLADE -> "SPLADE"` to effectiveMode |
| 5 | `SearchOrchestrator.mergeChunkResults()` | Add chunk-level SPLADE if chunks exist |
| 6 | `beir-eval-win.ps1` | Add `"splade"` to `-Modes` param validation |

### Open question

Chunk-merge for SPLADE mode: should standalone SPLADE also search chunks with
SPLADE, or fall back to BM25 for chunks? SPLADE FeatureField data IS indexed
on chunk docs, so SPLADE chunk search is feasible. Recommend: yes, use SPLADE
for chunks too — this gives a true SPLADE isolation result.

### What this enables

- True SPLADE isolation eval — compare to published SPLADE-v3 (0.710 SciFact)
- Better component attribution in the hybrid pipeline
- Prerequisite for CC calibration (need standalone SPLADE + standalone dense scores)

---

## Investigation: QPP-Adaptive Fusion (items 2b–2e)

### Current state in codebase

QPP signals are **fully computed** but **unused**:

| Signal | What it measures | Computed in | Consumed by |
|--------|-----------------|-------------|-------------|
| MaxIDF | Max IDF across query terms (rare term detection) | `SearchOrchestrator.computeQpp():554` | Nothing — set on gRPC response, dropped at REST layer |
| AvgICTF | Avg inverse collection term freq (overall rarity) | `SearchOrchestrator.computeQpp():562` | Nothing — same |
| QueryScope | Fraction of corpus matching ≥1 query term (breadth) | `SearchOrchestrator.computeQpp():569` | Nothing — same |

Raw stats come from `DocumentQueryOps.getQppSignals()` which reads Lucene
`IndexReader` stats (docFreq, totalTermFreq, numDocs). QPP is currently computed
**after** fusion (line 471 of SearchOrchestrator), too late to influence it.

The existing adaptive behavior is **score-magnitude low-signal gating** in
`HybridSearchOps.computeLowSignalGating()` — when vector top score < 0.40,
vectorWeight drops 0.75→0.30. Not QPP-based.

### Research findings (literature 2022–2026)

**Key negative finding:** Classic pre-retrieval QPP signals (MaxIDF, AvgICTF,
QueryScope) were designed for BM25. Their correlation with retrieval
effectiveness **drops ~10 percentage points** for neural rankers (SPLADE, dense).
Hard mode switching using QPP achieves only 0.5121 nDCG vs 0.5106 BM25-only —
not statistically significant (arXiv:2504.01101).

**Key positive findings:**

1. **Static Convex Combination (CC) consistently outperforms RRF** across BEIR
   datasets (Bruch et al., ACM TOIS 2024, arXiv:2210.11934). Formula:
   `s_hybrid = α·s_dense_norm + (1-α)·s_sparse_norm` with min-max normalization.
   Requires only ~10-50 labeled queries to calibrate α. RRF's k=60 is arbitrary
   and performance-sensitive. **Note:** Paper reports nDCG@**1000**, not @10.
   CC vs RRF delta at @10 may differ from the reported +0.029 at @1000.
   Normalization choice is theoretically irrelevant — Bruch et al. prove any
   linear transform produces rank-equivalent results with appropriate α.

2. **Post-retrieval score variance (RSD/NQC) is more reliable than pre-retrieval
   QPP** for adaptive fusion weighting (~4.5% AP gain on TREC DL, arXiv:2601.17339).
   Unsupervised — no labeled data needed.

3. **Avg-TF-IDF weighted RRF** is an unsupervised heuristic: high query
   specificity → more sparse weight, low specificity → more dense weight.
   Promising on HaluBench but unvalidated on standard BEIR (arXiv:2504.05324).

4. **LLM-judge per-query α (DAT)** shows largest gains (+7.5pp on hybrid-sensitive
   queries) but requires 2 LLM calls per query — too expensive for local search
   (arXiv:2503.23013).

**Sources:**
- Bruch et al., "An Analysis of Fusion Functions for Hybrid Retrieval," ACM TOIS 42(1) 2023 (arXiv:2210.11934)
- "Uncovering the Limitations of QPP," ACM TOIS 2025 (arXiv:2504.01101)
- "Beyond Correlations: Downstream QPP Eval," arXiv:2601.17339
- "Dynamic Weighted RRF for Hybrid Retrieval," arXiv:2504.05324
- "DAT: Dynamic Alpha Tuning for Hybrid Retrieval in RAG," arXiv:2503.23013

### Implementation plan (priority order)

**Step 2b: Grid-search RRF k (zero effort, free gains)**

RRF k=60 is the standard default but Bruch 2023 proved it's sensitive. Test
k ∈ {10, 20, 30, 60, 100} on existing SciFact/Arguana/NFCorpus qrels using
`run-ranking-experiments.ps1`. Config knob: `ResolvedConfig.HybridSearch.rrfK`.
No code changes needed — just eval runs with different config values.

**Step 2c: Static CC with min-max normalization (main improvement)**

Replace RRF with CC as the fusion algorithm. Implementation:

| File | Change |
|------|--------|
| `HybridFusionUtils.java` | Add `fuseWithCC(sparseResult, denseResult, alpha)` — min-max normalize both legs over top-K, interpolate with α |
| `HybridSearchOps.executeHybrid()` | Accept fusion strategy parameter, route to `fuseWithCC()` or `fuseWithRRF()` |
| `ResolvedConfig.HybridSearch` | Add `fusionStrategy` (rrf/cc), `ccAlpha` (default 0.5) |
| Eval harness | Add α grid-search mode: sweep α ∈ {0.0, 0.1, …, 1.0} on existing qrels |

CC formula: `s(d) = α · (s_dense(d) - min_dense) / (max_dense - min_dense) + (1-α) · (s_sparse(d) - min_sparse) / (max_sparse - min_sparse)`

Prerequisite: standalone SPLADE mode (2a) enables calibrating α for
SPLADE+Dense separately from BM25+Dense.

**Step 2d: Move QPP before fusion (low risk)**

Relocate `computeQpp()` from line 471 (after fusion) to before the hybrid
dispatch. Pass QPP metrics into `executeHybrid()`. No behavioral change yet —
just makes QPP available for future adaptive weighting. Also expose QPP in the
REST API response for observability.

**Step 2e: Unsupervised adaptive weighting (experimental)**

Two options, neither requiring labeled data:

- **Avg-TF-IDF weighted RRF/CC:** Use corpus IDF from Lucene IndexReader
  (already available via `DocumentQueryOps.getQppSignals()`). High query
  specificity → more sparse weight. Formula:
  `w_sparse = min(1.0, avg_idf / median_corpus_idf)`.
  Compute median IDF at index build time, cache.

- **RSD-weighted fusion:** After retrieving results from both legs, compute
  std-dev of top-K scores per leg. Weight each leg proportionally to its score
  variance. Higher variance → more confident ranker → more weight. Requires
  both legs to return before fusion (already the case in `executeHybrid()`).

### Integration points in codebase

| Integration point | File:line | What it does today | What QPP/CC would change |
|-------------------|-----------|--------------------|--------------------------|
| Mode dispatch | `SearchOrchestrator:167` | Hard switch on client-sent mode | Could add QPP-gated soft routing |
| Low-signal gating | `HybridSearchOps:163-229` | Score-threshold vectorWeight adjustment | Could incorporate QPP signals |
| RRF fusion | `HybridFusionUtils:48-188` | Fixed k=60, vectorWeight=0.75 | Add CC alternative, adaptive α |
| QPP computation | `SearchOrchestrator:471` | Computed after fusion, set on response | Move before fusion, pass to executeHybrid |
| Config knobs | `ResolvedConfig.HybridSearch` | rrfK, vectorRrfWeight, low-signal thresholds | Add fusionStrategy, ccAlpha, adaptiveMode |

---

## External Validation: Open-Source Investigation (tempdoc 249)

Ten of eleven open-source projects were investigated (RAGFlow deferred —
primarily a RAG orchestrator, Docling covers its PDF parsing niche better).
Projects: four search engines (Anserini, OpenSearch, Vespa, Infinity), two
search/vector databases (Qdrant, Pyserini), one vector database (Milvus), two
domain tools (SentenceTransformers, Docling), and one desktop search app (qmd).
Full findings in `docs/tempdocs/249-*-findings.md`. This section summarizes
findings relevant to search quality strategy.

### Validated (no action needed)

1. **FeatureField 8-bit mantissa is industry-accepted.** All four investigations
   confirm this is not a quality bottleneck:
   - Anserini: Lucene committer recommends FeatureField; "fake-words" alternative
     has comparable or worse precision for small weights. FeatureField also
     structurally eliminates the "wacky weights" latency problem (arXiv
     2110.11540) — 1 posting per doc-term pair regardless of weight, vs N
     repeated postings in fake-words
   - OpenSearch: "acceptable for now" — operates in production at scale
   - Vespa: default `tensor<bfloat16>` has the **same** 8-bit mantissa
   - Infinity: BMP uses float32, but the 11.5x gap over MaxScore is algorithmic
     (block-max pruning), not precision
   - SPLADE-v3 `log1p` activation naturally limits weights to [0, ~5], well within
     FeatureField's (0, 64] range

2. **JustSearch's filtered ANN strategy is correct.** Filter passed directly to
   `KnnFloatVectorQuery`, Lucene handles adaptive strategy (exact when P≤k, HNSW
   with skip otherwise, fallback if too many visits). OpenSearch confirms at
   production scale. ACORN-1 (Lucene ~9.9+) provides additional speedup at medium
   selectivity — verify bundled Lucene version. Milvus/Knowhere source confirms:
   Dual-Pool HNSW traversal separates valid/invalid candidates, brute-force
   fallback at 93% filtered out (7% remaining). arXiv 2602.11443: "algorithmic
   adaptations within the engine often override raw index performance."
   Note: Lucene's filtered KNN lacks Milvus's Dual-Pool priority queue and
   `kAlpha` scaling — the strategy is correct but not optimal for narrow
   filters. Item 15a (post-search safety net, P1) is the cheapest first step —
   retry brute-force when HNSW returns < k results. Item 15b (pre-check
   threshold, P2) proactively avoids the wasted HNSW traversal.

3. **SPLADE+Dense without separate BM25 leg is a reasonable design.** "Balancing
   the Blend" (arXiv 2508.01405) found three-way RRF does NOT consistently beat
   two-way. On SciFact: FTS+DVS RRF (0.748) beats FTS+SVS+DVS RRF (0.739).

4. **CC fusion was the right choice.** All four investigations converge: score-based
   fusion (min-max + weighted sum / CC) outperforms RRF. OpenSearch: +4.5-7.8%
   nDCG@10. Vespa: ATAN 0.3410 vs RRF 0.3195. Bruch et al.: CC wins all 9 datasets.
   Infinity weighted-sum outperforms RRF on long documents. Already implemented
   (`fuseWithCC()` in HybridFusionUtils).

5. **GPL-LambdaMART failure is a documented anti-pattern.** SentenceTransformers v4
   train-reranker blog documents that training on only synthetic hard negatives
   without mixing easier negatives causes rerankers to become overly strict. Mix
   60-70% hard + 30-40% random negatives. GPL cannot provide random negatives
   (synthetic queries only), so GPL-LambdaMART may be fundamentally unrecoverable
   without real user query data. Already disabled (item 5).

6. **CC implementation is more principled than Pyserini's.** Pyserini's
   HybridSearcher uses midpoint normalization to [-0.5, +0.5] with non-convex
   weighting (`0.1 * sparse + 1.0 * dense`). Has a division-by-zero bug when all
   scores are equal. JustSearch's min-max [0,1] with convex alpha is cleaner.
   Pyserini's offline `interpolation` method (raw score CC) is rank-equivalent per
   Bruch et al.

7. **JustSearch's alpha=0.5 default is well-calibrated.** Pyserini defaults to
   alpha=0.1 (calibrated for weak-BM25 + strong-dense). JustSearch's balanced 0.5
   is more appropriate for its strong-sparse (SPLADE) + dense configuration.

8. **MiniLM-L6-v2 cross-encoder is the optimal desktop choice.** L6 matches L12
   quality at 2x throughput. Domain finetuning beats model size (150M finetuned >
   1.54B general-purpose). JustSearch's existing choice validated.

9. **Qdrant sparse WAND is functionally equivalent to Lucene FeatureField.** Inline
   `max_next_weight` per posting entry enables WAND-style pruning. Both achieve
   similar throughput at desktop scale. No changes needed.

10. **Min-max normalization correctly prevents BM25 self-match bias.** Pyserini test
    data shows raw interpolation ranks self-match doc #1 (score 149.46), normalized
    CC ranks it #4 (score 1.0), RRF ranks it #10 (score 0.016). JustSearch's
    min-max normalization is a feature, not a limitation — it prevents documents with
    extreme single-leg scores from dominating fused rankings purely on magnitude.

11. **FeatureField precision matches or exceeds Pyserini quantization.** Pyserini
    quantizes SPLADE weights via `round(weight / 5 * 256)` (~0.78% step size at
    typical weights). FeatureField's 8-bit mantissa gives ~0.39% relative
    precision — equal or better. The "is our encoding lossy?" question is closed.
    Source: tempdoc 249 Anserini finding U2 resolution.

### New actionable findings

6. **SPLADE beta query-term pruning (items 11).** BMP paper (SIGIR 2024) Table 4:
   pruning the lowest-weight 50% of SPLADE query terms **improves** MRR@10 by +0.16
   while nearly halving latency. Quality cliff at 40% kept; 50% is safe. The tail
   terms add noise, not signal. OpenSearch independently validates this via two-phase
   token pruning (28-60% speedup, <0.04% nDCG loss). Without BMP's block-max pruning,
   beta pruning is JustSearch's **only** SPLADE query optimization lever — potentially
   even more impactful on FeatureField than in BMP.

7. **nDCG computation variant mismatch (item 10).** JustSearch `beir-eval-win.ps1`
   uses exponential gain `2^rel - 1`; trec_eval and all published baselines use
   linear `rel`. Identical for binary relevance (SciFact, ArguAna), but NFCorpus
   (graded 0/1/2) numbers are incomparable. One-line fix.

8. **Bruch et al. citation correction.** Previously cited as "Zhuang et al. (TOIS
   2023)" — actually Bruch, Gai, Ingber (ACM TOIS 2024, DOI:10.1145/3596512). Paper
   reports nDCG@**1000**, not @10. CC vs RRF delta at @10 may differ. However,
   five independent sources (Bruch et al., OpenSearch, Vespa, Infinity,
   "Balancing the Blend") all confirm CC > RRF — the convergence makes the
   @1000 vs @10 caveat academic. Normalization choice is theoretically irrelevant
   (proven: any linear transform produces rank-equivalent results with
   appropriate α).

9. **Weakest-link phenomenon explains Arguana RRF regression.** "Balancing the Blend"
   (arXiv 2508.01405) documents that adding a weak retriever via RRF can hurt vs the
   strong retriever alone. JustSearch Arguana: dense-only 0.370, RRF 0.289. CC
   mitigates this because near-zero α suppresses the weak leg.

10. **Filtered ANN safety net and threshold fallback (items 15a, 15b).** Two
    complementary mechanisms, recommended in sequence:

    **Item 15a (P1): Post-search safety net.** If `KnnFloatVectorQuery` returns
    fewer than `limit` results when a filter is active, retry with exact kNN over
    the filtered doc set. This is zero-cost on the happy path — it only fires when
    HNSW has already failed to find enough candidates, catching the failure without
    needing to predict selectivity up front. ~10 lines in ReadPathOps/ChunkSearchOps.
    Milvus/Knowhere implements the same pattern as a post-search fallback lambda.
    Source: tempdoc 249 Milvus filtered ANN, `knowhere/src/index/hnsw/faiss_hnsw.cc`.

    **Item 15b (P2): Pre-check threshold.** Check `filter.cardinality()`: if below
    ~3000-5000 for 768-dim, skip HNSW and go directly to brute-force. Proactive
    optimization — avoids wasted HNSW traversal on narrow filters. Percolation
    theory basis: p_c = 1/m = 6.25% for M=16 — below this the filtered HNSW
    subgraph fragments. Milvus/Knowhere uses 93% filtered out (7% remaining);
    secondary trigger: k >= 50% of valid vectors. ~40 lines in
    ReadPathOps/ChunkSearchOps. Source: tempdoc 249 Qdrant finding 4 + Milvus
    filtered ANN.

11. **SPLADE weight clamping safety net (item 16).** Clamp SPLADE weights to
    FeatureField's (0, 64] range before indexing. SPLADE-v3 `log1p` regularization
    normally keeps weights in [0, ~5], but the "wacky weights" problem is documented
    (arXiv 2110.11540): without sufficient regularization, extreme weights to
    stopwords can exceed the range. ~5 lines in LuceneIndexRuntime. Source: tempdoc
    249 SentenceTransformers finding 4.

12. **Position-aware reranker score blending (item 17).** Instead of replacing
    retrieval order entirely when cross-encoder fires, blend retrieval and
    reranker scores by position: top-3 results protect retrieval signal (75%
    retrieval + 25% reranker), ranks 4-10 balanced (60/40), ranks 11+ trust
    reranker (40/60). Protects against reranker disagreements with strong
    retrieval signals. ~30 lines in `KnowledgeHttpApiAdapter`. Source: tempdoc
    249 qmd finding 2.

### Deferred findings

10. **ATAN normalization (item 12).** Maps unbounded BM25 to ~(0,1) via
    `2*atan(x/C)/π` without cross-document statistics. Vespa: +0.020 nDCG@10 over
    baseline on NFCorpus. But C parameter needs corpus-specific calibration — Vespa
    provides zero guidance. **Warning:** Milvus GitHub #40836 documents **ranking inversions** from arctan's
    concavity — verified from `reScorer.go` source. Arctan's derivative `1/(1+x²)`
    decreases as x grows, compressing high-score gaps more than low-score gaps. By
    Jensen's inequality, items with high score variance across legs (one strong +
    one weak) get lower fused scores than items with uniform moderate scores, even
    when raw weighted sums favor the first item. Concrete example: A(0.1, 0.9)=0.500
    raw → 0.6325 arctan; B(0.39, 0.6)=0.495 raw → 0.6452 arctan — inverted. Min-max
    normalization avoids this (linear transform preserves relative gaps).
    **Dropped.** Min-max CC (already implemented) dominates ATAN: simpler, no
    C-parameter tuning, no ranking inversions. Four independent investigations
    validated CC. No reason to pursue ATAN as an alternative.

11. **BPIndexReorderer (item 13).** Lucene `lucene-misc` ships Recursive Graph
    Bisection. 10-30% BM25 latency from literature. Impact on JustSearch's corpus
    unknown. Not a quality improvement — latency only.

12. **max_token_score WAND (item 14).** ~4x SPLADE latency from OpenSearch RFC #284.
    Not latency-critical at desktop scale (100K-1M docs, exact search is fast).

13. **ColBERT storage revised.** EMVB PQ compression (ECIR 2024) reduces overhead to
    2-4x (not 10-50x as §5.4 claimed). At 20 bytes/token (OPQ m=16, 8-bit
    centroids), a 128-token doc costs ~2.5 KB — manageable at desktop scale.
    ColBERT MaxSim reranking is sub-ms vs 373ms cross-encoder. But cross-encoder
    still wins by 5-10 nDCG quality; ColBERT is a future option when sub-ms
    reranking latency matters more than peak quality. Future trade-off evaluation,
    not immediate action.

14. **Cross-encoder after hybrid fusion.** OpenSearch confirms structurally sound.
    JustSearch's exclusion is a quality judgment, not architectural. **Now tracked as
    tempdoc 250 item 1a** — industry research confirms no major system gates rerankers
    by retrieval mode. Naver Labs (ECIR 2024) shows cross-encoder after SPLADE yields
    gains on out-of-domain benchmarks. CC sweep null result (F13) makes the original
    "re-evaluate after CC calibration" condition moot.

15. **Matryoshka 256-dim embeddings.** nomic-embed-text-v1.5 natively supports
    truncation: -1.24 MTEB at 256-dim (-2.0%). 3x KNN speed, 67% vector storage
    reduction. Post-processing: layer norm → truncate → L2 normalize. **Primary
    uncertainty:** GGUF layer norm behavior — does llama.cpp embedding output
    include layer norm or not? Needs A/B eval. Source: tempdoc 249
    SentenceTransformers finding 3.

16. **Inference-free SPLADE model swap.** Now tracked as **active item 18** (P2).
    OpenSearch's `neural-sparse-encoding-doc-v3-gte`: 54.6 BEIR-13 nDCG@10 (+2.9
    over SPLADE-v3 51.7) with zero query-time neural inference. Source: tempdoc 249
    OpenSearch Q5 + SentenceTransformers finding 4 additional A.

17. **Weighted RRF.** Per-leg weights: `sum(weight_i / (k + rank_i))`. ~10 lines
    to add to `fuseWithRRF()`. Addresses "which leg to trust" without switching to
    CC. Source: tempdoc 249 Qdrant A2.

18. **DBSF (Distribution-Based Score Fusion).** z-score normalization:
    `(score - (mean - 3σ)) / (6σ)`. More robust to outliers than min-max.
    **Low priority:** CC is validated by 5 independent sources and already
    implemented. DBSF's advantage (outlier robustness) is unlikely to matter at
    desktop scale (<1M docs, well-behaved score distributions). Adding a third
    fusion strategy increases configuration surface without a clear use case where
    DBSF beats CC. Revisit only if CC alpha calibration reveals outlier sensitivity
    problems in score normalization. Source: tempdoc 249 Qdrant A1.

19. **N-way weighted CC for 3-leg fusion.** Anserini's `RunsFuser.weighted()`
    generalizes CC to N runs with per-run weights. Natural extension if BM25 and
    SPLADE legs are ever separated into independent retrieval runs. Source: tempdoc
    249 Pyserini finding 4.

20. **Relevance feedback via average_vector.** Qdrant's `average_vector` strategy
    directly implementable in Lucene: `2*avg(pos) - avg(neg)` → KnnFloatVectorQuery.
    Sparse leg via MoreLikeThisQuery. This is a **new feature** enabling "find
    similar" UX, not a quality fix for existing search — it doesn't change current
    hybrid retrieval behavior. The +5.6 nDCG@20 figure is from pseudo-relevance
    feedback (auto-expanding from top results), a different use case than user-
    initiated "more like this." ~500 lines across gRPC, API adapter, search
    orchestrator. **Highest-value new feature in the backlog** after P1/P2 quality
    items are resolved. Source: tempdoc 249 Qdrant finding 1.

21. **Docling PDF enhancer sidecar.** Layout-aware PDF parsing (RT-DETR 78% mAP,
    TableFormer 96.8% TEDS) + structure-aware chunking (HybridChunker with triplet
    table serialization). CPU feasible but slow (0.6-2.2 pages/sec). docling-java
    v0.4.7 pre-1.0 client. Document processing improvement (not search ranking),
    but also a **chat/RAG quality improvement** — structured extraction (proper
    tables, heading hierarchy) fed to the chat VLM as context produces better
    answers than raw pixels or flat Tika text. Three aspects:
    - **Sidecar path:** docling-serve (Docker, 4.4 GB CPU image) + docling-java
      client. Highest quality, highest adoption cost (Docker dependency).
    - **Granite-Docling-258M GGUF path:** 178 MB VLM runnable via llama.cpp,
      bypassing the sidecar entirely. JustSearch already has llama.cpp FFM
      bindings. Not viable today (15-40 sec/page CPU, GPU conflicts with
      single-tenant policy), but worth tracking as a future zero-dependency
      integration path.
    - **Triplet table serialization (sidecar-independent):** The
      `row_header, col_header = cell_value` chunking pattern is transferable
      whenever JustSearch gains table structure from any source — not a Docling
      dependency.
    Source: tempdoc 249 Docling findings 1-4.

22. **Strong-signal gating for search enhancements.** Skip expensive processing
    (LLM expansion, cross-encoder reranking) when BM25 top-1 is decisive:
    score >= threshold AND large gap to rank 2. Pure latency optimization —
    avoids LLM/reranker latency on navigational queries. **Assessment:** Only
    applicable to Head-side enhancements (LLM expansion, cross-encoder). Cannot
    gate SPLADE — it fires inside the Worker during hybrid retrieval before
    Head sees BM25 results. Threshold needs calibration: qmd normalizes BM25 to
    [0,1) first; JustSearch's raw Lucene scores are unbounded. Low urgency —
    cross-encoder already has 200ms deadline. Source: tempdoc 249 qmd A1.

23. **Typed LLM query expansion (lex/vec/hyde routing).** LLM generates typed
    expansions: `lex` variants → BM25, `vec` paraphrases → KNN, `hyde`
    hypothetical documents → KNN. Requires protocol changes (expansion variants
    in gRPC), generative model at search time, GBNF grammar wiring. No published
    before/after metrics — "can it be built" answered but "does it improve recall"
    not. **Assessment:** Over-engineered for current needs. JustSearch already
    disables expansion for HYBRID — the same rationale (dense leg covers recall
    gaps) applies to vec/hyde expansions. High adoption cost (proto changes,
    generative model at search time, GBNF wiring) for uncertain gain. HyDE is
    the most novel piece but requires ~1B+ model loaded at search time,
    conflicting with single-tenant GPU policy. Prerequisite: GBNF wiring
    (item 25). Park until CC calibration (2f) reveals whether HYBRID mode still
    has recall gaps. Source: tempdoc 249 qmd finding 1.

24. **GGUF reranker via llama.cpp (Qwen3-Reranker-0.6B).** 600M decoder-only
    reranker via llama.cpp ranking API. 27x MiniLM-L6 params, potentially higher
    quality but 500ms+ latency. Requires new FFM bindings, GPU scheduling
    (competes with embedding + chat for VRAM). **Assessment:** Not worth
    pursuing. MiniLM-L6-v2 already validated as optimal desktop choice (item
    validated-8: "L6 matches L12 at 2x throughput, domain finetuning beats model
    size"). GGUF adds 27x params, GPU contention, and speculative quality gain
    with no published latency data. Only revisit if JustSearch unifies all
    inference through llama.cpp (architectural simplification, not quality).
    Source: tempdoc 249 qmd finding 2.

25. **GBNF grammar wiring for structured LLM output.** qmd validates that
    `llama_sampler_init_grammar()` with a 4-line GBNF grammar guarantees
    structured expansion output. Relevant beyond expansion — chat citations,
    settings inference, any structured LLM task. JustSearch's current
    `JsonGrammarGuard` is a regex character filter that doesn't enforce schema.
    **Assessment:** Broadest utility of any qmd item, but it's a
    plumbing/inference task, not search quality. Should be tracked in a separate
    inference capabilities tempdoc. 245 references it as a prerequisite for
    item 23 only. Source: tempdoc 249 qmd A2.

26. **Chunk-first reranking improvement.** Systematic chunking + keyword-overlap
    selection before reranking, vs JustSearch's current first-match snippet.
    **Assessment:** Marginal. Current snippet approach (`title + query_focused_snippet
    ~1500 chars`) already targets the relevant document portion. qmd's approach
    (chunk → pick best by keyword overlap) is more systematic but solves the same
    problem. Edge case where it helps: documents where the relevant section isn't
    near the first keyword match. ChunkSplitter exists in indexing pipeline and
    could be reused at rerank time. Bottom of priority list. Source: tempdoc 249
    qmd finding 2.

### Competitive positioning (from Infinity "Balancing the Blend" comparison)

| Configuration | BGE-M3 paper | JustSearch | Gap |
|---------------|-------------|------------|-----|
| BM25-only | 0.704 | 0.663 | -0.041 |
| Dense-only | 0.715 | 0.694 (Q8) | -0.021 |
| Sparse+Dense RRF | 0.716 | 0.706 (Q8) | -0.010 |
| Best hybrid (no reranker) | 0.748 (FTS+DVS RRF) | 0.706 | -0.042 |

Note: BGE-M3 is a different (larger) model than nomic-embed. Gaps reflect
model quality differences, not necessarily JustSearch implementation deficiencies.
The sparse+dense RRF gap (0.010) is within model quality differences
(nomic-embed Q8 vs BGE-M3 full precision). The BM25 gap (0.041) is larger —
possibly tokenization/preprocessing differences.

---

## Critical Analysis: Item Interactions and Revised Ordering (2026-03-02)

The 21 active/deferred items in this tempdoc originated from 10 independent
open-source investigations (tempdoc 249). Each was evaluated in isolation.
This section evaluates them as a coherent set — checking interaction effects,
claim validity, measurement feasibility, and dependency ordering.

**Methodology:** Codebase investigation (SPLADE pipeline, fusion pipeline,
filtered ANN, reranker integration, eval script) + internet research
(trec_eval source, BMP paper verification, BEIR evaluation.py, Lucene
FeatureField behavior).

### F1: The "+0.16 MRR@10" claim for beta pruning (item 11) is misleading

BMP paper reports RR@10 on a 0–100 scale. The improvement from β=1.0 (all
terms) to β=0.5 (top 50%) is ≈+0.16 **percentage points** = +0.0016 on a
0–1 scale. Within noise for 300-query evals. Quality peaks at β=0.5 and
slightly degrades at β=1.0, confirming tail terms add minor noise — but the
quality gain is negligible. Real value is latency (1.8x), which isn't
bottlenecked at desktop scale. **Item 11 reframed:** quality-neutral latency
optimization, not quality improvement.

Sources: [BMP SIGIR 2024](https://arxiv.org/html/2405.01117),
[trec_eval m_ndcg_cut.c](https://github.com/usnistgov/trec_eval/blob/main/m_ndcg_cut.c).

### F2: nDCG gain function mismatch (item 10) is confirmed

trec_eval's `m_ndcg.c` and `m_ndcg_cut.c` both use `gain = rel` (linear).
JustSearch uses `2^rel - 1` (exponential). BEIR's `evaluation.py` calls
pytrec_eval with no gain override → inherits linear. For binary relevance
(SciFact, ArguAna): identical. For NFCorpus graded 0/1/2: JustSearch
over-weights grade-2 by 50% (gain=3 vs gain=2). **NFCorpus numbers are
incomparable to all published baselines.** Nuance: verify both formulas
against published 0.338 NFCorpus baseline to confirm direction.

### F3: Item 2b (RRF k optimization) is likely wasted work

Five independent sources confirm CC > RRF. CC is already implemented. If CC
replaces RRF as default, optimizing RRF k=60 is effort spent on a superseded
strategy. **Deprioritize.** Do CC calibration (2f) first; only revisit 2b if
CC has pathological cases.

### F4: Dependency chain should be inverted (eval before code)

Current ordering: code 11 → code 19 → eval 2f → eval 2b → eval 8.
Item 11's quality impact is negligible (F1), so it doesn't meaningfully shift
SPLADE score distributions. Alpha calibrated without beta pruning ≈ alpha
calibrated with it. This breaks the 11→2f dependency.
**Revised:** code 10 → code 19 → eval 2f → (eval 8 parallel) → then decide
11 and 2b based on data. Lower risk: calibration data before uncertain code.

### F5: Items 15a/15b solve an unobserved problem (confirmed real gap)

Codebase confirmed: NO fallback logic when filtered KNN returns < k results.
`ReadPathOps` and `ChunkSearchOps` return whatever HNSW finds. But no BEIR
benchmark uses filters — unmeasurable with current eval. Still worth fixing
(15a is zero happy-path cost), but prioritize as correctness fix, not quality
improvement.

### F6: Item 17 (reranker blending) should be dropped

Cross-encoder only fires in TEXT mode (not HYBRID default). No evidence of
reranker misbehavior. Source (qmd) has different architecture. Three strikes:
no evidence, wrong mode, different architecture. **Drop.**

### F7: Item 19 (CC zero-exclusion) is more important than ranked

CC zero-exclusion changes fundamental fusion behavior. Alpha calibration (2f)
depends on this choice — calibrating with penalty semantics then switching to
zero-exclusion invalidates the alpha. **Must be decided and implemented BEFORE
alpha calibration.** Upgrade from P2 to Phase 1.

### F8: Item 13 (BPIndexReorderer) harder than described

lucene-misc NOT on classpath (`adapters-lucene/build.gradle.kts` only has
lucene-core, analysis-common, analysis-icu, queryparser). Requires new
dependency + index build pipeline wiring. Latency-only. **Drop or keep P3.**

### F9: Item 18 (inference-free SPLADE) is highest-impact deferred item

Eliminates ONNX query-time inference while improving quality (+2.9 nDCG@10).
Architecturally transformative. But requires model compatibility investigation,
IDF table shipping, tokenizer-based query encoder. **Track in own tempdoc.**

### F10: Interaction matrix

| Items | Interaction |
|-------|-------------|
| 11 ↔ 2f | Beta pruning shifts SPLADE scores → shifts optimal alpha |
| 19 ↔ 2f | Zero-exclusion changes single-leg scoring → shifts optimal alpha |
| 11 ↔ 2b | Beta pruning shifts SPLADE leg → shifts optimal RRF k |
| 2b ↔ 2f | If CC replaces RRF, 2b is obsolete |

**Non-interacting** (safe independently): items 10, 8.

### F11: Item 16 (SPLADE weight clamping) should be dropped (2026-03-02)

Internet research across 6 production systems (Anserini, OpenSearch, Vespa,
Qdrant, Pinecone, Milvus): **no production system clamps SPLADE weights**.

- **log1p already compresses**: `log1p(ReLU(logit))` maps logit=20 → 3.04,
  logit=50 → 3.93. Practical max ~3.0–3.2 for standard SPLADE models.
- **FLOPS regularization** controls weight magnitude at training time, not
  inference time. This is the SPLADE team's own solution.
- **"Wacky weights" (Mackenzie 2021)** are a retrieval *efficiency* problem
  (WAND skipping inefficiency), not a quality problem. Solution is different
  query evaluation strategies, not clamping.
- **Clamping destroys discrimination**: if the model assigns 3.0 to a rare
  domain term, clamping to 2.5 loses the signal.
- **Lucene FeatureField.newLinearQuery** constrains weight to (0, 64] — well
  above the ~3.0 practical SPLADE max.
- **OpenSearch's approach** for SPLADE efficiency: token pruning (drop tokens
  below 40% of max query weight), not weight clamping. This is item 11 (beta
  pruning), which is already tracked separately.

Sources: Pinecone SPLADE tutorial, Pyserini SPLADEv2 experiments, OpenSearch
neural sparse RFC #646, Qdrant sparse vectors docs, Lucene FeatureField
javadoc, Mackenzie et al. 2021 (arXiv 2110.11540).

### F12: Item 15a (filtered ANN safety net) is already solved by Lucene (2026-03-02)

Lucene 10.3.1 (JustSearch's version) has a built-in three-tier fallback for
filtered KNN in `AbstractKnnVectorQuery`:

1. **Auto exact-search**: if `filter.cost() <= k`, short-circuits to brute-
   force immediately (HNSW must visit that many docs anyway).
2. **Visit-limit fallback**: if HNSW visits too many nodes without completing,
   stops and falls back to exact search over filtered set.
3. **ACORN-1 (merged PR #14160, Lucene 10.2.0)**: two-hop neighborhood
   exploration when >10% of HNSW neighbors are filtered out. Up to 5x faster
   filtered search with minimal recall loss.

Additionally, JustSearch's existing `vectorEfSearchOverride` in
`ReadPathOps.resolveVectorQueryK()` already provides oversampling (request
larger k from HNSW, truncate results).

Industry confirmation: Elasticsearch (ACORN-1 in 9.1+), OpenSearch (auto
exact/approximate decision at ~5% selectivity), Vespa (auto exact search
below ~2-5% selectivity, configurable `approximate-threshold`).

A manual retry-with-larger-k safety net is redundant with Lucene's internal
fallback. **Drop.**

Sources: Lucene KnnFloatVectorQuery javadoc (10.3.0), Lucene PR #14160,
Lucene issue #13611, OpenSearch efficient k-NN filtering docs, Vespa HNSW
docs, Elasticsearch filtered HNSW blog.

### F13: CC fusion is equivalent to RRF on SciFact/NFCorpus — null result (2026-03-02)

Ran the full CC alpha calibration sweep (item 2f): 19 configs per dataset
(9 alpha values × 2 variants + RRF baseline), using Q8_0 embedding model.
Script: `run-ranking-experiments.ps1 -Step cc-sweep`.

**Results:** All CC alpha values (0.30–0.70) and both modes (penalty,
zero-exclusion) produce identical nDCG@10 within ±0.001 on both datasets:
- SciFact: 0.7054–0.7055 (RRF = 0.7055)
- NFCorpus: 0.3341–0.3351 (RRF = 0.3351)

**Root cause:** On these small academic corpora, SPLADE and dense retrieval
find essentially the same documents in their top results. When both legs
return the same document set, any weighted combination (CC at any alpha)
produces the same ranking as any rank-based combination (RRF at any k).
The zero-exclusion toggle has zero effect because there are no single-leg
documents — every relevant document appears in both SPLADE and dense results.

**Why the literature prediction (CC > RRF) didn't hold:** The Bruch et al.
(2024) TOIS study measured CC vs RRF at nDCG@1000, where deep-ranking
effects matter. At nDCG@10, only the top documents matter, and those are
dominated by documents both legs agree on. The CC advantage requires
datasets where one leg finds relevant documents the other misses —
a property absent in SciFact/NFCorpus.

**Implications:**
1. **Keep RRF as production default** — simpler (no min-max normalization),
   equivalent quality, well-understood failure modes.
2. **Drop items 2b and 2e** — changing fusion parameters cannot improve
   nDCG when the bottleneck is single-leg retrieval quality, not fusion.
3. **The productive quality path is improving individual legs**: inference-free
   SPLADE (item 18, +2.9 nDCG@10 from better SPLADE model), better embedding
   models, and larger evaluation corpora where leg overlap is lower.

---

## Infrastructure (implemented)

### Isolation automation

The isolation matrix is fully automated in `run-ranking-experiments.ps1 -Step isolation`.

**Session grouping** — Configs with identical startup env vars share one server
session, varying only the query-time `mode` parameter. 7 configs run in 5
server sessions instead of 7, saving ~2 startup/shutdown cycles.

**installDist fast-launch** — Uses `modules/ui/build/install/ui/bin/ui` directly
via bash instead of `./gradlew.bat :modules:ui:run --no-daemon`. Server startup
drops from 3-5 minutes (Gradle cold-start) to ~7 seconds. Full 7-config
isolation on SciFact completes in ~15 minutes.

**LambdaMART toggle** — Added `JUSTSEARCH_LAMBDAMART_ENABLED` env var
(`EnvRegistry.LAMBDAMART_ENABLED` + `AppFacadeBootstrap` wiring). Default true
(preserves existing behavior). When false, `LambdaMartReranker` is not
registered, cleanly disabling learned fusion without dataDir manipulation.

**Aggregation** — The isolation step writes `isolation-summary.json` with
nDCG@10 and recall@10 per config per mode, plus published baselines. A console
table is printed at the end for quick comparison.

### CC alpha sweep automation

Added `run-ranking-experiments.ps1 -Step cc-sweep` for automated CC alpha
calibration. Builds session 1 with SPLADE+dense index, then runs 18 query-only
sessions with different alpha/mode combos (SkipIndex for fast reuse). Includes
RRF baseline and lexical baselines. Outputs `cc-sweep-summary.json` and
console markdown table. ~22s per config after Session 1 indexing.

### Component toggles (all working)

| Component | Toggle |
|-----------|--------|
| SPLADE | `JUSTSEARCH_SPLADE_ENABLED=false` |
| Dense/vector | `-Modes @("lexical")` or `@("vector")` |
| Cross-encoder | `JUSTSEARCH_RERANK_ENABLED=true/false` + model path |
| LambdaMART | `JUSTSEARCH_LAMBDAMART_ENABLED=false` (**new**) |
| SPLADE token length | `JUSTSEARCH_SPLADE_MAX_SEQ_LEN=256/512` |
| RRF parameters | `JUSTSEARCH_INDEX_VECTOR_RRF_WEIGHT` etc. |
| CC fusion | `JUSTSEARCH_HYBRID_FUSION_STRATEGY=cc` + `_CC_ALPHA` + `_CC_ZERO_EXCLUDE` (**new**) |

### Fixes applied during implementation

1. **Data directory override** — `UiSettingsStore.load()` always reads from
   disk and sets `justsearch.index.base_path` from the user's settings.json.
   Fix: pass `-Djustsearch.index.base_path=...` via `UI_OPTS` to pre-set
   the system property (HeadlessApp's `setSysPropIfBlankWithSource` skips
   already-set properties).

2. **Status API mismatch** — `beir-eval-win.ps1` was calling
   `/api/knowledge/status` but accessing `readiness.composites.retrieval`
   (which only exists on `/api/status`). Fixed to use `/api/status` with
   correct field names (`pendingJobs`, `buildingIndexedDocuments`, etc.).

3. **Start-Process env var inheritance** — `[System.Environment]::SetEnvironmentVariable()`
   vars don't reliably propagate to bash child processes via `Start-Process`.
   Fix: pass env vars inline in the bash command string.

### Output structure

```
tmp/beir-eval/isolation/
├── bm25-only/metrics.json, metrics.v2.json, per-query-lexical.json
├── dense-only/...
├── bm25-dense-rrf/...
├── splade-dense-rrf/...
├── splade-dense-lambdamart/...
├── bm25-crossencoder/...
├── splade-512/...
└── isolation-summary.json    ← aggregated nDCG@10 + recall@10 + published baselines
```

---

## Agent Execution Log

Extracted to `245-execution-log.md`. Contains:
- Operating instructions for autonomous agent sessions
- Decision log (12 entries) with failure modes and fixes
- Step-by-step execution details for Steps 1-8
- Results accumulation table and monitoring commands

### CURRENT STEP: 17 (Item 8 FiQA complete. 4-dataset isolation matrix done. Code/model constraint verification for items 6/20/21 complete — see updated item descriptions. Remaining active: 11, 6, 20, 21. Pipeline routing issues moved to tempdoc 250.)

---

## Appendix: Sources Consulted

### Tempdocs (quality-relevant)
- **234** — Retrieval architecture: GPL→LambdaMART→SPLADE pipeline design
- **235** — Eval audit: A5 isolation data, ~400 scripts for 1 catch
- **222** — Passage retrieval: chunk-aware search, BM25 failure modes
- **135** — Core quality doc: RRF K=60, cross-encoder discovery bug
- **223** — Stemming: no stemmer, breaks fuzzy correction, LLM expansion path
- **229** — Unified search strategy: 12 blockers, all theoretical
- **232** — Structural issues: QPP routing concept, all theoretical
- **230** — Eval closure: debug evidence fields, 5 BEIR datasets
- **227** — Agent quality: 73.3% pass rate, capped by model
- **225** — Corpus strategy: governed multi-corpus, no representative corpus

### Tempdocs (infrastructure/context)
- **233, 236, 237, 238, 240, 242, 243, 216, 220, 226, 228, 231**

### Code investigated
- `KnowledgeHttpApiAdapter.java` (929 lines) — Head-side search orchestration
- `SearchOrchestrator.java` (984 lines) — Worker-side mode routing, QPP, fusion
- `HybridSearchOps.java` (450 lines) — RRF fusion, low-signal gating
- `HybridFusionUtils.java` (188 lines) — RRF algorithm
- `LambdaMartReranker.java` (317 lines) — 2-feature LightGBM inference
- `LambdaMartFeatureSchema.java` (73 lines) — V1 schema: sparse + vector only
- `CrossEncoderReranker.java` (473 lines) — ONNX inference, GPU arbitration
- `GrpcEmbeddingClient.java` (173 lines) — Embedding + fallback behavior
- `OnnxModelDiscovery.java` (116 lines) — Model auto-discovery paths
- `RuntimeHybridSearchConfigFactory.java` (189 lines) — All fusion parameters
- `beir-eval-win.ps1` (1,309 lines) — BEIR harness, mode support, artifacts
- `run-ranking-experiments.ps1` (~450 lines) — Multi-config automation, session grouping
- `scripts/bench/baselines/*.json` — All measured BEIR baselines
- `scorecard-ratchet-policy.v1.json` — Maturity levels, lane targets

### Benchmark & evaluation research (2026-03-01)
- Elasticsearch, "Evaluating Search Relevance" Parts 1-2 — BEIR label quality findings (57.6% unlabeled relevant, ArguAna duplicates)
- Saad-Falcon et al., "Benchmarking Long-Context Retrieval and Reasoning" (LoCoV1), ICML 2024, arXiv:2402.07440
- Parry et al., "BIRCO: A Benchmark of Information Retrieval Tasks with Complex Objectives," arXiv:2402.14151
- MS MARCO: microsoft.github.io/msmarco — real Bing query logs
- MTEB v2: huggingface.co/blog/isaacchung/mteb-v2 — subsumes BEIR retrieval tasks
- TREC Deep Learning Track: microsoft.github.io/msmarco/TREC-Deep-Learning.html — multi-graded relevance
- BRIGHT: brightbenchmark.github.io — reasoning-intensive retrieval (ICLR 2025)
- AIR-Bench: github.com/AIR-Bench — automated heterogeneous IR benchmark (ACL 2025)

### External validation (tempdoc 249, 2026-03-01)
- Bruch, Gai, Ingber, "An Analysis of Fusion Functions for Hybrid Retrieval," ACM TOIS 2024, arXiv:2210.11934 (CC > RRF, nDCG@1000)
- Mallia et al., "Block-Max Posting (BMP) for SPLADE," SIGIR 2024, arXiv:2405.01117 (beta query-term pruning, block-max latency)
- "Balancing the Blend: Optimizing Multi-Way Hybrid Search," arXiv:2508.01405, 2025 (weakest-link phenomenon, three-way vs two-way RRF)
- EMVB: arXiv:2404.02805, ECIR 2024 (ColBERT OPQ compression: 2-4x overhead)
- Vespa hybrid tutorial + blog part 2 (ATAN normalization, min-max convex combination, multi-phase ranking)
- OpenSearch neural sparse RFC #284 (max_token_score WAND optimization), Search Pipelines docs, AWS Big Data Blog (min_max + arithmetic_mean)
- Anserini BEIR regressions (published SPLADE-v3 baselines, ImpactSimilarity, fake-words vs FeatureField)
- Infinity benchmarks + blog (four-way fusion, BMP/BPIndexReorderer, EMVB ColBERT)
- Pyserini HybridSearcher + fusion module: CC equivalence proof, N-way weighted CC, missing-doc imputation, division-by-zero bug
- SentenceTransformers v4 train-reranker blog: hard-negative anti-pattern, MiniLM-L6 validation, Matryoshka viability, inference-free SPLADE
- Qdrant docs + source: DBSF, weighted RRF, filtered ANN threshold fallback, relevance feedback (recommend/discovery APIs), sparse WAND
- Docling: layout-aware PDF (RT-DETR + TableFormer), HybridChunker, triplet table serialization, docling-java, Granite-Docling-258M
- qmd: LLM query expansion (typed lex/vec/hyde routing, strong-signal gating, GRPO fine-tuning), GGUF reranker (Qwen3-Reranker-0.6B, position-aware blending), GBNF grammar
- Milvus/Knowhere: Dual-Pool HNSW traversal (`NeighborSetDoublePopList` in Neighbor.h, verified), brute-force fallback (93% threshold from `IndexConditionalWrapper.h`, verified), post-search BF safety net (`faiss_hnsw.cc`), arctan ranking inversion (#40836, concavity/Jensen's verified from `reScorer.go`), BM25 normalization `2*arctan(d)/PI` (distinct from IP formula), DiskANN/AISAQ (not applicable at desktop scale), clustering compaction (not transferable), BM25-as-sparse-vectors. arXiv 2602.11443 (filtered ANN systematic analysis)
- Full findings: `docs/tempdocs/249-{anserini,opensearch,vespa,infinity,pyserini,sentencetransformers,qdrant,docling,qmd,milvus}-findings.md`

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

External-validation research across 10 retrieval systems (Anserini, Pyserini, Vespa, Infinity, Qdrant, Milvus, Docling, qmd, SentenceTransformers, OpenSearch). The validation phase concluded; the findings are the artifact and are also cross-referenced from the 249-* findings cluster.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

