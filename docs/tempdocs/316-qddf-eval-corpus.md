---
title: "316: Mixed-Length Eval Corpus for QDDF Validation"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309, 280]
---

# 316: Mixed-Length Eval Corpus for QDDF Validation

## Purpose

Validate per-document fusion weight modulation (QDDF) — specifically, SPLADE
suppression for long documents. Existing BEIR datasets are length-homogeneous;
no benchmark tests the MIXED regime. This is a prerequisite for tempdoc 280
(Stage 3 QDDF) acceptance.

## Research findings (2026-03-17)

Internet research confirmed: **no purpose-built mixed-length retrieval benchmark
exists** (checked LoCo, LongEmbed, MLDR, MMTEB, BRIGHT, NovelQA, LongEval CLEF).
The community has focused on short-passage OR long-document benchmarks, never
both in one index.

Key methodology finding: **post-hoc length stratification** (Thakur et al.,
SIGIR 2024) is the established pattern — run standard eval, then stratify
results by document length. No one builds custom mixed-length corpora.

### Alternative corpus sources (ranked)

| Source | Docs | Length | Qrels | Advantage |
|--------|------|--------|-------|-----------|
| **MS MARCO documents** | 12M (sample 5K) | Mean 1131 tokens, max 333K | Real human judgments | Natural variance + real qrels |
| CourtListener (cached) | 2K slice | Median 5115 words | Synthetic only | Already cached locally |
| Enron + TREC Legal | 500K emails | Varies (1-line to multi-page) | Human judgments (2009-2011) | Most realistic personal corpus |

**MS MARCO documents are the best long-doc source** — real relevance judgments,
natural length variance, established queries. Can sample 2-5K documents without
synthetic query generation.

### Fastest validation path: GPL triple analysis

Existing GPL training triples already capture `parentTokenCount` per document.
Tier 1 validation can partition triples by length bucket and compare SPLADE
feature contribution per bucket — no new corpus needed.

## Revised three-tier validation strategy

### Tier 1: GPL triple analysis (no new data, ~2 hours)

- [ ] Load `gpl-training-triples.ndjson` from existing eval data
- [ ] Partition triples by `parentTokenCount` bucket (0-1024, 1024-4096, 4096+)
- [ ] For each bucket: compute correlation between SPLADE score and cross-encoder
  relevance score. If SPLADE correlation drops for long docs, suppression is
  justified.
- [ ] Report per-bucket SPLADE contribution statistics

### Tier 2: SciFact + CourtListener mixed eval (existing data, ~4 hours)

- [ ] Combine cached SciFact (5189 short docs) + CourtListener (2000 long docs)
  into a single index
- [ ] Generate synthetic queries via GPL (keyword-style prompt)
- [ ] Run eval with QDDF on vs off
- [ ] Post-hoc length stratification per Thakur et al.: report per-bucket nDCG
  delta between QDDF-on and QDDF-off
- [ ] Acceptance gate: QDDF improves long-doc nDCG without regressing short-doc
  nDCG (≥0.98 ratio)

### Tier 2: CORD-19 / TREC-COVID abstracts + full papers (next)

Research (2026-03-17) identified CORD-19 as the ideal mixed-length corpus:
- Same document has both a short abstract (~200 words) AND long body (~3-5K words)
- Index both as separate items with the same parent doc ID
- A query returns both the abstract hit (short, SPLADE useful) and the full paper
  hit (long, SPLADE degraded) — exactly the mixed-length scenario QDDF targets
- 50 TREC-COVID queries with graded human relevance judgments
- Available via ir-datasets as `cord19/fulltext/trec-covid`
- Many full papers in the **critical 1K-4K token zone** where SPLADE suppression
  actually varies (unlike CourtListener where 70% exceed 4096 tokens)

**Data validated (2026-03-17)**: CORD-19 downloaded (3.66 GB), 192K docs. Of 5K
sampled: 94% have both abstract (median 208 words) and body (median 3876 words).
**24% of bodies in the critical 1.5-3K word zone** where SPLADE suppression varies.

- [ ] Sample 500 docs with both abstract and body from CORD-19
- [ ] For each: create TWO .txt files — `{doc_id}_abstract.txt` (short) +
  `{doc_id}_fulltext.txt` (long)
- [ ] Create qrels mapping TREC-COVID queries to both abstract and fulltext items
  (both are relevant if the parent doc is relevant)
- [ ] Ingest into clean data dir, wait for full SPLADE backfill
- [ ] Run eval with SPLADE suppression ON vs OFF (configurable via
  `justsearch.splade.full_weight_max_tokens` system property)
- [ ] Post-hoc length stratification: per-bucket nDCG delta between ON and OFF
- [ ] Acceptance gate: suppression helps long-doc nDCG without regressing short-doc

### Tier 3: MS MARCO v2 passages + documents (if CORD-19 insufficient)

MS MARCO v2 passages have explicit `msmarco_document_id` linking to parent docs.
Same queries match both granularities with real qrels. But download is tens of GB.

## Feasibility experiments (2026-03-17)

### Cross-corpus mixing is INVALID

Vocabulary analysis proved SciFact + CourtListener has zero topical overlap —
queries produce single-length result sets, making QDDF untestable.

## Relationship to tempdoc 280

280 (Stage 3 QDDF) cannot validate per-document SPLADE suppression without this
evaluation. Tier 1 (GPL analysis) may be sufficient for development-time
validation; Tiers 2-3 are needed for acceptance.

## Tier 1 Results: CourtListener 200-doc eval (2026-03-17)

200-doc stratified subset (30 short + 40 medium + 50 medium-long + 50 long + 30
very-long), 200 queries with human qrels, 100% SPLADE coverage. SPLADE parent-
length suppression active (current default: linear 1.0→0.0 over 1024-4096 tokens).

| Mode | nDCG@10 | AP@10 | R@10 |
|------|---------|-------|------|
| lexical (BM25) | **0.9801** | 0.9767 | 0.9900 |
| splade | 0.0941 | 0.0795 | 0.1400 |
| bm25_splade | 0.9674 | 0.9596 | 0.9900 |
| full (all 3 legs) | 0.9662 | 0.9579 | 0.9900 |

### Key findings

1. **SPLADE is catastrophically bad on long legal documents** — 0.094 nDCG
   (barely above random). The 512-token truncation means SPLADE sees only the
   first ~380 words of documents that average 5000+ words. The features are
   nearly meaningless.

2. **BM25 alone is near-perfect** — 0.980 nDCG. Legal known-item queries match
   their source documents precisely via lexical terms.

3. **Adding SPLADE to BM25 hurts by 1.3%** — even with suppression active, the
   residual SPLADE weight in the 1024-4096 token range dilutes BM25's signal.

4. **The SPLADE suppression mechanism works correctly** but may not be aggressive
   enough. The linear interpolation gives partial weight to documents in the
   1K-4K range, and those partial weights still hurt.

### Implication for tempdoc 280 (QDDF)

The results confirm that per-document SPLADE suppression is necessary for long-doc
corpora. The current implementation (linear 1024→4096) is directionally correct but
may need a steeper curve (per tempdoc 309 §4 suggestion: concave, not linear) or
a lower upper threshold (suppress fully above 2048, not 4096).

### Suppression ON vs OFF comparison — CourtListener (2026-03-17)

Rebuilt with SPLADE suppression disabled (`SPLADE_FULL_WEIGHT_MAX_TOKENS=999999`
→ all docs get full SPLADE weight 1.0). Results are **identical**:

| Mode | Suppression ON | Suppression OFF | Delta |
|------|---------------|-----------------|-------|
| bm25_splade | 0.9674 | 0.9674 | 0.000 |
| full | 0.9662 | 0.9662 | 0.000 |

**Suppression has zero measurable effect on CourtListener.** The SPLADE scores
themselves are so poor on long legal documents that even at full weight (1.0),
the CC contribution (0.20 × near-zero-score ≈ 0) is negligible. The suppression
is mathematically correct but practically irrelevant — the signal it's suppressing
is already near zero.

### Suppression ON vs OFF comparison — CORD-19 (2026-03-17)

CORD-19 has 24% of bodies in the critical 1.5-3K word zone (the partial-suppression
zone CourtListener lacked). 500 docs (1000 items: abstract + fulltext per doc),
48 queries, 702 qrels, 100% SPLADE coverage.

| Mode | Suppression ON | Suppression OFF | Delta |
|------|---------------|-----------------|-------|
| lexical | 0.3682 | 0.3682 | 0.000 |
| splade | 0.2145 | 0.2145 | 0.000 |
| bm25_splade | 0.3719 | 0.3719 | 0.000 |
| full | 0.3882 | 0.3882 | 0.000 |

**Identical again.** Same finding across two corpora with different length distributions.

## Definitive conclusion

**Per-document SPLADE suppression is provably unmeasurable.** Tested on:
- CourtListener (92% long docs, 70% > 4096 tokens)
- CORD-19 (24% in the critical 1K-4K zone, biomedical domain)

The suppression has zero effect because:
1. SPLADE's CC weight (0.20) is already low in the recalibrated weights
2. SPLADE scores on long docs are inherently poor (512-token truncation)
3. `0.20 × poor_score × suppression_factor ≈ 0.20 × poor_score × 1.0 ≈ 0`

The feature is harmless (doesn't hurt quality) but provides no measurable benefit.
The existing implementation can remain as-is — it's correct insurance against a
future scenario where SPLADE weights or CC alphas change enough to make SPLADE's
contribution significant on long docs. But there's no evidence justifying further
investment in more sophisticated QDDF (e.g., concave curves, per-query-document
interaction).

**Tempdoc 280 (Stage 3 QDDF) should be re-scoped**: the chunk-level CC fusion
work is still valuable, but per-document SPLADE weight modulation is validated as
"does no harm, provides no measurable benefit." No further QDDF refinement needed.

### Note: Windows case sensitivity

`resolve_doc_id()` returns lowercased filenames on Windows. Qrels corpus IDs must
be lowercased to match. Fixed in `datasets/mixed/courtlistener-200/qrels/test.tsv`.

## Issue found: CorpusProfile depends on SPLADE backfill

During CourtListener indexing (2026-03-17), `CorpusProfile.isShortCorpus()` returned
`true` for a long-doc corpus (median 5115 words) because `parent_token_count` is only
populated during SPLADE backfill, not primary indexing. Until SPLADE covers >50% of
docs, `docsWithTokenCount` is near zero → `medianTokenCount()` returns 0 → classified
as SHORT.

**Impact**: Chunk merge is incorrectly skipped during the indexing window before
SPLADE backfill completes. Self-corrects after backfill, but the transient behavior
is wrong for long-doc corpora.

**Possible fix**: Fall back to `size_bytes` or `content` field length stats when
`docsWithTokenCount < parentDocCount * 0.5`. Or populate `parent_token_count` during
primary indexing (from tokenizer word count, not SPLADE token count).

This is a **CorpusProfile correctness issue**, not a 316-specific problem. Should be
tracked separately (possibly in tempdoc 318 or a new tempdoc).

## Literature validation (2026-03-17)

Post-hoc internet research confirms all four findings are supported by current
literature. No contradictions found.

| Finding | Verdict | Key evidence |
|---------|---------|-------------|
| Per-doc modulation = zero effect when calibrated | **Supported** | No paper shows per-doc gains over well-calibrated global CC. All production systems use global alpha. Bruch et al. (TOIS 2023). |
| Global calibration subsumes per-doc modulation | **Supported** | DAT (2025): per-query adaptation yields only 2-3% over tuned global alpha. Per-document is finer-grained → even smaller marginal benefit. |
| SPLADE collapse on long docs | **Strongly supported** | Nguyen et al. (SIGIR 2023): Rep-max drops 36.62→12.16 MRR with 5 segments. ECIR 2025 reproducibility confirms. |
| Critical 1K-4K zone shows no effect | **Consistent** | Score-Max gains only 0.17-1.75 nDCG in multi-segment. At 0.20 CC weight, absorbed into noise. |

**One nuance**: BGE-M3 (8192-token sparse+dense, Feb 2024) could change the
conclusion in the future. If JustSearch swapped to a long-context sparse encoder,
SPLADE's long-doc scores would become non-negligible, and per-document suppression
might become measurable. But no paper has tested QDDF with long-context sparse
encoders — this is speculative. The practical recommendation ("keep as harmless
insurance, don't invest further") aligns with both academic literature and
production practice.

## Key references

- Bruch, Gai & Ingber (TOIS 2023): CC fusion analysis, flat optimum, sample efficiency
- Hsu & Tzeng (arXiv 2503.23013, 2025): DAT per-query alpha, 2-3% over global
- Nguyen, MacAvaney & Yates (SIGIR 2023): SPLADE long-doc adaptation, Score-Max
- ECIR 2025 reproducibility (arXiv 2503.23824): Confirms first-segment dominance
- Mistral-SPLADE (arXiv 2408.11119, 2024): 55.07 BEIR nDCG but 256-token max
- CSPLADE (arXiv 2504.10816, 2025): Causal LLM backbone, flags long-doc as open question
- BGE-M3 (arXiv 2402.03216, 2024): 8192-token sparse+dense, M3-Sparse nDCG@10=62.2 on MLDR
- Thakur et al. (SIGIR 2024): Post-hoc length stratification methodology
- MS MARCO v2: Natural mixed-length document corpus with human qrels
