---
description: "TRIGGER only for deep search-quality or retrieval work: modifying search orchestration, fusion weights, reranking, SPLADE encoding, BM25 tuning, eval code, or updating quality baselines. Do not load for ordinary search API usage, generic docs work, or incidental mentions of search; use narrower skills or canonical docs instead."
user-invocable: true
---

# Search Quality Context

Read this before starting any search quality work. Do not re-run
experiments already recorded in the Baselines or Findings sections.

This is intentionally a heavy register-backed skill. Load it when the task
depends on retrieval-quality history or baselines, not for general search
workflow orientation.

<!-- generated:start — do not edit between markers; run: node scripts/docs/skills-sync.mjs -->

<!-- source: docs/reference/search-quality-register.md -->

# Search Quality Register

Coordination register for search quality work. Every search-quality
tempdoc agent must read this before starting and update it before closing.

**Rules:**
- Do not re-run an experiment listed under Baselines or Findings without
  justification (e.g., pipeline change that invalidates prior results).
- When your work settles a question from Open Questions, move it to
  Findings with your tempdoc citation.
- When your work opens a new question, add it to Open Questions.
- Keep entries terse. Evidence lives in tempdocs; this file is the index.

**How to add a baseline row:**
- Copy nDCG@10, P@1, R@10 from `summary.json` → `per_mode.<mode>.aggregate_metrics`
- Fill `legs` from `summary.json` → `per_mode.<mode>.pipeline_tracking.observed`
- Fill `git` from `summary.json` → `git_sha`
- Assign confidence: **A** (≥200 queries, no issues), **B** (<200 queries
  or known measurement issue), **C** (structural problem — broken legs,
  not-comparable run; do not use as baseline)
- Update the **Best known** line if your result beats the current best
- Remove the row from Measurement Gaps if you filled it
- Update the Dataset Catalog's **Last Validated** and **Validated By** columns
  if you re-ran or validated a dataset

**Replaces:** the former `search-quality.md` (SRQ-) and `retrieval-quality.md` (RAG-)
issue files, which have been retired. Remaining open items from those files were
triaged into this register's sections or retired to `decisions.md`.

---

## Dataset Catalog

Reference metadata. Every slug is a valid `dataset_name` argument to jseval.
Query variants of the same corpus get distinct slugs.

| Slug | Domain | Lang | Docs | Queries | Query Form | Last Validated | Validated By | Notes |
|------|--------|------|------|---------|------------|---------------|-------------|-------|
| beir/scifact | academic | en | 5183 | 300 | factoid | 2026-06-13 | 580 | BEIR standard; 580 revalidated hybrid on-baseline at HEAD |
| mixed/enron-qa | email | en | 5485 | 300 | verbose QA | 2026-03-28 | 343 D | single-user inbox (dasovich-j) |
| mixed/enron-qa-nav | email | en | 5485 | ~100 | navigational | — | — | not yet created; see Q-002 |
| mixed/courtlistener-200 | legal | en | 200 | 200 | known-item | 2026-03-18 | 309 §35 | **RETIRED 2026-07-01 (tempdoc 666)** — replaced by `mixed/legal-clerc-200`; see Corpus provenance note under Findings. |
| mixed/legal-clerc-200 | legal (case-law citation) | en | 198 | 200 | citation-retrieval | 2026-07-01 | 666 | Real academic benchmark (CLERC, built on the Caselaw Access Project), not a bespoke curation — see Corpus provenance note. Source recipe `scripts/jseval/666-corpora/legal-clerc-200/recipe.json`; regenerable via `jseval corpus-fetch-clerc --name legal-clerc-200 --seed 666 --n-queries 200`. |
| mixed/miracl-de-2k | wikipedia | de | 3103 | 305 | factoid | 2026-07-01 | 666 | **Content regenerated 2026-07-01 (tempdoc 666)** — see Corpus provenance note. Source recipe `scripts/jseval/666-corpora/miracl-de-2k/recipe.json`; regenerable via `jseval corpus-fetch-miracl --name miracl-de-2k --lang de --seed 666 --n-docs 3103`. |
| mixed/miracl-fr-2k | wikipedia | fr | 5407 | 343 | factoid | 2026-07-01 | 666 | **Content regenerated 2026-07-01 (tempdoc 666)** — see Corpus provenance note. Source recipe `scripts/jseval/666-corpora/miracl-fr-2k/recipe.json`; regenerable via `jseval corpus-fetch-miracl --name miracl-fr-2k --lang fr --seed 666 --n-docs 5407`. Query count corrected from 316 to 343 (full dev-split qrelled query count — the prior 316 had no recorded sampling method). |
| mixed/miracl-zh-2k | wikipedia | zh | 5786 | 393 | factoid | 2026-03-18 | 309 §37 | |
| mixed/cord19-qddf | biomedical | en | 1000 | 48 | factoid | 2026-03-18 | 309 §35 | 48 queries = low statistical power |
| mixed/desktop-mixed-v1 | mixed | en+de+fr+zh | 2286 | 250 | mixed | 2026-03-18 | 309 §38 | 5 sources × 4 langs. 7% SciFact qrel coverage (data issue). |
| mixed/ohr-bench-clean | multi-domain | en | 1000 | 962 | extractive | 2026-03-19 | 252 | OHR-Bench ground-truth text (7 domains). |
| mixed/ohr-bench-got-moderate | multi-domain | en | 1000 | 962 | extractive | 2026-03-19 | 252 | OHR-Bench GOT OCR extraction (moderate noise). |
| mixed/ohr-bench-mineru-moderate | multi-domain | en | 1000 | 962 | extractive | 2026-03-19 | 252 | OHR-Bench MinerU extraction (moderate noise). |
| mixed/ohr-bench-tika-pdf | multi-domain | en | 999 | 962 | extractive | 2026-03-20 | 252 | OHR-Bench original PDFs through Tika StructuredContentExtractor. |
| mixed/multihop-rag-2556 | news/multi-hop | en | 609 | 2556 | multi-hop inference/comparison/temporal/null | 2026-04-07 | 366 §9d | Retrieval eval, filter-bearing |
| golden/needle-burial-v1 | synthetic/buried-signal | en | 280 | 20 | zero-overlap paraphrase | 2026-06-23 | 636 | Buried-signal regression guard (F-023). Source `scripts/jseval/635-corpora/needle-burial-v1`; s30/s60 scales regenerable via seed=636/ratio in `meta.json`. **Content regenerated 2026-07-01 (tempdoc 664)** — see Corpus provenance note under Findings. |

---

## Canonical Baselines

One block per dataset. Within each block, one row per measured (config × mode).
All values are nDCG@10 unless noted. `—` = not yet measured.

**Columns:** `encoder` — sparse+dense encoder. `ce` — cross-encoder at eval
time. `cc` — CC fusion weights. `mode` — jseval mode. `legs` — retrieval
legs confirmed active (from pipeline_tracking.observed). `conf` — confidence
tier. `git` — git_sha from summary.json. `src` — tempdoc citation.

**Cross-run noise vs signal** — before flagging a nDCG@10 change as a
regression, consult the cohort envelope: `<data_dir>/cohort_baselines/
<hash>/envelope.json` gives σ per metric (tempdoc 400 LR1-b). Deltas
inside ±2σ are noise. For encoder-level latency distribution drift
(different question — "did ORT session.run() durations shift even
without a nDCG change?"), use `jseval calibrate-drift-baseline` + the
nightly `jseval gate`: PSI > 0.2 on any `encoder.ort_run` duration
distribution flags a drift signal independent of aggregate quality.
See `docs/explanation/08-observability.md` §Contract Tiers + §Run
Manifest and `docs/how-to/triage-psi-drift.md`.

<!-- generated:start — do not edit between markers; run: node scripts/docs/register-headline-sync.mjs -->

### Release Scorecard (projected — do not hand-edit)

> Generated from `scripts/jseval/release.v1.json` (tempdoc 623). Each per-corpus number below is a
> **projection** of one cohort-identical release (same config/commit/hardware), not a hand-typed value.
> The (config × mode) ablation tables in each corpus block stay hand-authored. Reproduction tolerance
> is the within-machine ±2σ envelope, scoped to equivalent hardware/setup (tempdoc 623 F-α).

**Release:** `84b305b2be` · default mode `hybrid` · NVIDIA GeForce RTX 4070 · driver 610.62 · ORT 1.24.3

**Coverage:** retrieval ranking quality (per-corpus metrics above) — **does NOT measure** document extraction / OCR / VDU routing quality (see tempdoc 623 §F — extraction-quality sibling).

| Corpus | Ours (mode) | nDCG@10 | Published baselines (cited, side-by-side) |
|---|---|---|---|
| beir/scifact | hybrid | 0.756 | — |
| mixed/enron-qa | hybrid | 0.719 | — |
| mixed/legal-clerc-200 | hybrid | 0.516 | — |
| mixed/miracl-de-2k | hybrid | 0.852 | — |
| mixed/miracl-fr-2k | hybrid | 0.866 | — |

**Engine performance** (relative-ratchet guarded — tempdoc 640):

| Corpus | CE p50 (ms) | Index docs/s | Enrich docs/s | Resident (GB) |
|---|---|---|---|---|
| beir/scifact | 167 | 111.1 | 25.0 | 1.75 |
| mixed/enron-qa | 157 | 96.4 | 7.9 | 2.02 |
| mixed/legal-clerc-200 | 214 | 11.0 | 1.3 | 1.75 |
| mixed/miracl-de-2k | 168 | 73.7 | 36.7 | 1.75 |
| mixed/miracl-fr-2k | 169 | 124.6 | 50.0 | 1.75 |

<!-- generated:end -->

> **Reading the two numbers (tempdoc 623 ④ / C-4).** The **Release Scorecard** above is the
> *production-default* (`hybrid`) result — a **projection** of one cohort-identical release, the number a
> user actually gets and the one the ratchet floors against. The per-corpus **Best known:** lines below
> are a *hand-authored research log of best-achievable-config ablations* (often `full`-mode), kept for
> engineering history — **not** the production headline. They differ **by design**: e.g. legal is
> `full`-mode **0.925** best-achievable vs `hybrid` **0.620** production-default (corpus×config optimality,
> F-004). When a Scorecard value is present for a corpus, it — not the "Best known" line — is the
> canonical production number; the "Best known" line is its best-config ablation.

### beir/scifact

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| bge-m3 | minilm-512 | balanced | lexical | 0.661 | 0.537 | 0.783 | bm25 | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | balanced | splade | 0.627 | 0.520 | 0.716 | splade | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | balanced | bm25_splade | 0.679 | 0.540 | 0.801 | bm25+splade | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | balanced | full | **0.723** | 0.543 | 0.801 | bm25+sparse+dense | A | dc4f79a | 309 §35 |
| bge-m3 | gte-8192 | balanced | full | 0.722 | — | — | bm25+sparse+dense | A | dc4f79a | 309 §41 |
| splade-v3+nomic | minilm-512 | bm25-dom | lexical | 0.662 | — | — | bm25 | A | — | 309 §30 |
| splade-v3+nomic | minilm-512 | bm25-dom | splade | 0.625 | — | — | splade | B | — | 309 §30 |
| splade-v3+nomic | minilm-512 | bm25-dom | bm25_splade | 0.676 | — | — | bm25+splade | A | — | 309 §30 |
| splade-v3+nomic | minilm-512 | bm25-dom | full | 0.684 | — | — | bm25+splade (dense broken) | C | — | 309 §30 |
| splade-v3+gemma | gte-8192 | bm25-dom | lexical | 0.661 | 0.537 | 0.779 | bm25 | A | 68782549f | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | splade | 0.501 | 0.397 | 0.622 | splade | A | 68782549f | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | bm25_splade | 0.668 | 0.540 | 0.799 | bm25+splade | A | 68782549f | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | full | 0.714 | 0.587 | 0.839 | bm25+splade+dense (CE off) | A | 68782549f | 343 |
| splade-ml+gte | gte-ml-reranker | bm25-dom | lexical | 0.680 | 0.537 | 0.819 | bm25+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | splade | 0.510 | 0.390 | 0.645 | splade+CE | B | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | bm25_splade | 0.681 | 0.533 | 0.819 | bm25+splade+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | full | 0.736 | 0.600 | 0.878 | bm25+splade+dense+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | default-hybrid | hybrid | **0.754** | 0.633 | 0.884 | cross_encoder + dense | A | 3af6773cc | 391 |
| (HEAD default) | (default) | default-hybrid | hybrid | 0.758 | 0.627 | 0.896 | cross_encoder + dense + splade + query_classification | A | f91e269bc | 580 |
| (HEAD default) | CE-off | (default) | full | 0.708 | 0.577 | 0.833 | dense + splade + query_classification (CE off) | B | f91e269bc | 580 |

**Best known:** splade-ml+gte / gte-ml-reranker / default-hybrid / **hybrid** = **0.754** (391, 6-run median across two 3-run sets on 2026-04-18 and 2026-04-19; range 0.7527–0.7571, CV 0.1–0.3%). Full mode best known remains splade-ml+gte / gte-ml-reranker / bm25-dom / full = **0.736** (343 Phase D).
**Note:** GTE-ModernBERT CE produces identical result (0.722 — noise). Mode breakdown now complete.
**Note:** splade-v3+gemma `full` row is C — dense leg broken (F-012). splade-v3+nomic `full` row downgraded to C for same reason. `splade` mode legs corrected from `bm25+splade` to `splade` (jseval sends `sparseEnabled=false`). SPLADE-v3 sparse quality is 20% below BGE-M3 sparse on SciFact (F-013).
**Note:** `splade-ml+gte` = opensearch-neural-sparse-multilingual-v1 + gte-multilingual-base. `gte-ml-reranker` = gte-multilingual-reranker-base (FP16 GPU). Phase D: all 5 model swaps complete, CE ON.
**Note:** `hybrid` mode row (2026-04-19, 391): server-resolved preset, dense+CE in `observed` legs (BM25 presumably active in fusion but not reported in pipeline_tracking). git_sha in summary.json is `3b19076eb` (pre-arena-bump — runtime had `DEFAULT_GPU_MEM_MB=3072` via uncommitted edit; `3af6773cc` committed the bump and is the reproducible SHA). Hybrid beats full (0.754 vs 0.736, +2.4%) with a narrower leg set, consistent with F-004 (mode optimality is corpus-dependent) and F-006 (CE model upgrade irrelevant when retrieval is strong). Worth investigating whether SPLADE leg is actively hurting `full` on scifact post-358 model swap.

### mixed/enron-qa

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| bge-m3 | minilm-512 | balanced | lexical | 0.810 | 0.697 | 0.910 | bm25 | A | 72c6e9a | 309 §42 |
| bge-m3 | minilm-512 | balanced | splade | 0.711 | 0.550 | 0.867 | splade | A | 72c6e9a | 309 §42 |
| bge-m3 | minilm-512 | balanced | bm25_splade | **0.830** | 0.720 | 0.927 | bm25+splade | A | 72c6e9a | 309 §42 |
| bge-m3 | minilm-512 | balanced | full | 0.810 | 0.673 | 0.927 | bm25+sparse+dense | A | 72c6e9a | 309 §42 |
| bge-m3 | gte-8192 | balanced | lexical | 0.812 | 0.700 | 0.913 | bm25 | A | 0d4b3b1 | 309 §43 |
| bge-m3 | gte-8192 | balanced | splade | 0.712 | 0.550 | 0.867 | splade | A | 0d4b3b1 | 309 §43 |
| bge-m3 | gte-8192 | balanced | bm25_splade | 0.828 | 0.717 | 0.923 | bm25+splade | A | 0d4b3b1 | 309 §43 |
| bge-m3 | gte-8192 | balanced | full | 0.808 | 0.667 | 0.927 | bm25+sparse+dense | A | 0d4b3b1 | 309 §43 |

| splade-v3+gemma | gte-8192 | bm25-dom | lexical | 0.827 | 0.717 | 0.927 | bm25+chunk_merge | A | 68782549f | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | bm25_splade | 0.813 | 0.700 | 0.913 | bm25+splade+chunk_merge | A | 68782549f | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | full | 0.822 | 0.703 | 0.923 | bm25+splade+dense+chunk_merge (CE off) | A | 68782549f | 343 |
| splade-ml+gte | gte-ml-reranker | bm25-dom | lexical | 0.827 | 0.717 | 0.927 | bm25+chunk_merge (CE off) | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | bm25_splade | 0.813 | 0.700 | 0.913 | bm25+splade+chunk_merge (CE off) | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | full | 0.822 | 0.703 | 0.930 | bm25+splade+dense+chunk_merge (CE off) | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | lexical | 0.799 | 0.697 | 0.887 | bm25+chunk_merge+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | bm25_splade | 0.787 | 0.680 | 0.880 | bm25+splade+chunk_merge+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | full | 0.777 | 0.667 | 0.863 | bm25+splade+dense+chunk_merge+CE | A | 5d19ff2c1 | 343 D |

**Best known:** bge-m3 / minilm-512 / balanced / bm25_splade = **0.830**
**Note:** CE hurts EnronQA by 3-5% across all modes (CE-on vs CE-off isolation). Model swaps are quality-neutral on English email (CE-off post-swap matches pre-swap exactly). Confirms FW-001: corpus-adaptive CE gating needed.
**Note:** CE model makes no difference (F-001). With BGE-M3, CE hurts vs bm25_splade by ~2% (F-002).
**Note:** splade-v3+gemma rows use chunk merge (active on EnronQA long emails). Chunk merge provides +1.3% nDCG on lexical (p=0.04, statistically significant). See 343 Phase 2.2.
**Note:** All splade-v3+gemma `full` rows have CE OFF but dense ON (F-012 corrected — dense was working via gte-multilingual-base all along, tracking bug fixed). The full vs bm25_splade delta is the dense retrieval contribution. CE impact with splade-v3+gemma is unmeasured (jseval `--ce` flag needed).

### mixed/courtlistener-200 (RETIRED 2026-07-01, tempdoc 666 — replaced by mixed/legal-clerc-200)

*(all numbers below predate the retirement and are not reproducible against any corpus currently in this
catalog — see Corpus provenance note above)*

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| bge-m3 | minilm-512 | bm25-dom | lexical | 0.960 | 0.925 | 0.990 | bm25 | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | bm25-dom | splade | 0.647 | 0.485 | 0.825 | splade | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | bm25-dom | bm25_splade | 0.912 | 0.855 | 0.985 | bm25+splade | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | bm25-dom | full | **0.925** | 0.855 | 0.980 | bm25+sparse+dense | A | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | balanced | full | 0.816 | — | — | bm25+sparse+dense | A | dc4f79a | 309 §35 |
| bge-m3 | gte-8192 | balanced | full | 0.813 | — | — | bm25+sparse+dense | A | dc4f79a | 309 §41 |

**Best known:** bge-m3 / minilm-512 / bm25-dom / full = **0.925**
**Note:** BM25-dominant (0.925) is 11.8% better than balanced (0.816) on long legal docs. CE upgrade neutral (0.813 ≈ 0.816).

### mixed/legal-clerc-200 (new, tempdoc 666 — replaces mixed/courtlistener-200)

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| (HEAD default) | (default) | (default) | vector | 0.060 | — | — | dense | A | 84b305b | 666 |
| (HEAD default) | (default) | (default) | lexical | 0.686 | — | — | bm25 | A | 84b305b | 666 |
| (HEAD default) | (default) | (default) | splade | 0.059 | — | — | splade | A | 84b305b | 666 |
| (HEAD default) | (default) | (default) | hybrid | **0.521** | — | — | cross_encoder+dense+hybrid+query_classification | A | 84b305b | 666 |

**Best known:** (HEAD default) / hybrid = **0.521** (first measurement — no ablations run yet).
**Note:** BM25-dominant on this corpus too (lexical 0.686 vs vector/splade ~0.06) — consistent with the
retired courtlistener-200's own BM25-dominance-on-long-legal-docs finding, though this is a fresh
observation on the new corpus, not an inherited assumption (the new corpus has its own citation-style query
form, `queries/test.single-removed.direct.tsv`, distinct from the old known-item task — see Corpus
provenance note above). No cc/encoder ablation pass has been run yet.

### mixed/miracl-de-2k

*(ablation rows below predate the 2026-07-01 corpus regeneration — see Corpus provenance note above; the
search-engine behavior they document remains informative, but exact numbers are not reproducible against the
corpus as currently committed)*

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| (HEAD default) | (default) | (default) | hybrid | **0.852** | — | — | cross_encoder+dense+hybrid+query_classification | A | 84b305b | 666 |
| bge-m3 | minilm-512 | bm25-dom | lexical | 0.511 | — | — | bm25 | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | bm25-dom | splade | 0.669 | — | — | splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | bm25-dom | bm25_splade | 0.553 | — | — | bm25+splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | bm25-dom | full | 0.639 | — | — | bm25+sparse+dense | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | splade | 0.669 | — | — | splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | full | **0.734** | — | — | bm25+sparse+dense | A | dc4f79a | 309 §37 |
| bge-m3 | gte-8192 | balanced | full | 0.735 | — | — | bm25+sparse+dense | A | dc4f79a | 309 §41 |
| splade-v3+gemma | gte-8192 | bm25-dom | lexical | 0.513 | 0.328 | 0.749 | bm25 | A | 2681da09b | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | splade | 0.485 | 0.311 | 0.684 | splade | A | 2681da09b | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | bm25_splade | 0.540 | 0.354 | 0.780 | bm25+splade | A | 2681da09b | 343 |
| splade-v3+gemma | gte-8192 | bm25-dom | full | 0.619 | 0.403 | 0.875 | bm25+splade+dense (CE off) | A | 2681da09b | 343 |
| splade-ml+gte | gte-ml-reranker | bm25-dom | lexical | 0.559 | 0.367 | 0.797 | bm25+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | splade | 0.733 | 0.530 | 0.910 | splade+CE | B | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | bm25_splade | 0.582 | 0.384 | 0.816 | bm25+splade+CE | A | 5d19ff2c1 | 343 D |
| splade-ml+gte | gte-ml-reranker | bm25-dom | full | 0.696 | 0.469 | 0.908 | bm25+splade+dense+CE | A | 5d19ff2c1 | 343 D |

**Best known:** bge-m3 / minilm-512 / balanced / full = **0.734**
**Note:** SPLADE multilingual (0.733) nearly matches BGE-M3 sparse (0.669→0.733 = +9.6%). Massive improvement over SPLADE-v3 English-only (0.485→0.733 = +51.1%). Full mode 0.696 vs pre-swap 0.619 (+12.4%).
**Note:** splade-v3+gemma `full` mode (0.619) is +14.7% over bm25_splade (0.540) on MIRACL/de — this is the dense retrieval contribution (F-012 corrected: dense was working all along). Dense provides the largest uplift on multilingual content where BM25 is weakest.
**Note:** Balanced weights (+14.9% over bm25-dom on full mode). CE has zero impact on German (0.734 ≈ 0.735). CE ablation confirmed zero effect (309 §37).

### mixed/miracl-fr-2k

*(ablation rows below predate the 2026-07-01 corpus regeneration — see Corpus provenance note above; the
search-engine behavior they document remains informative, but exact numbers are not reproducible against the
corpus as currently committed)*

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| (HEAD default) | (default) | (default) | hybrid | **0.866** | — | — | cross_encoder+dense+hybrid+query_classification | A | 84b305b | 666 |
| bge-m3 | minilm-512 | balanced | lexical | 0.476 | — | — | bm25 | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | splade | 0.660 | — | — | splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | bm25_splade | 0.515 | — | — | bm25+splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | full | **0.706** | — | — | bm25+sparse+dense | A | dc4f79a | 309 §37 |

**Best known:** bge-m3 / minilm-512 / balanced / full = **0.706**
**Note:** Same pattern as German — balanced weights, `splade` (0.660) strongest single retriever for non-English.

### mixed/miracl-zh-2k

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| bge-m3 | minilm-512 | balanced | lexical | 0.495 | — | — | bm25 | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | splade | 0.604 | — | — | splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | bm25_splade | 0.533 | — | — | bm25+splade | A | dc4f79a | 309 §37 |
| bge-m3 | minilm-512 | balanced | full | **0.691** | — | — | bm25+sparse+dense | A | dc4f79a | 309 §37 |

**Best known:** bge-m3 / minilm-512 / balanced / full = **0.691**
**Note:** Chinese. Same multilingual pattern: balanced weights, `splade` strongest single retriever.

### mixed/cord19-qddf

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| bge-m3 | minilm-512 | bm25-dom | lexical | 0.340 | — | — | bm25 | B | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | bm25-dom | splade | 0.202 | — | — | splade | B | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | bm25-dom | bm25_splade | 0.346 | — | — | bm25+splade | B | dc4f79a | 309 §35 |
| bge-m3 | minilm-512 | bm25-dom | full | 0.383 | — | — | bm25+splade (dense broken) | C | dc4f79a | 309 §33,§35 |

**Best known (valid):** bge-m3 / minilm-512 / bm25-dom / bm25_splade = **0.346** (B confidence)
**Note:** `full` row is C — dense was broken (§33). 48 queries gives low statistical power. CORD-19 is a pathological corpus (homogeneous biomedical content).

### mixed/desktop-mixed-v1

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| bge-m3 | minilm-512 | balanced | lexical | 0.479 | — | — | bm25 | B | dc4f79a | 309 §38 |
| bge-m3 | minilm-512 | balanced | splade | 0.516 | — | — | splade | B | dc4f79a | 309 §38 |
| bge-m3 | minilm-512 | balanced | full | **0.578** | — | — | bm25+sparse+dense | B | dc4f79a | 309 §38 |

**Best known:** bge-m3 / minilm-512 / balanced / full = **0.578** (B — aggregate across 5 sources)
**Per-source nDCG@10 (full mode):** en_sci=0.070 (7% qrel coverage), de=0.665, fr=0.699, zh=0.710, en_legal=0.746. Cross-language degradation <10% for DE/FR/ZH vs isolated eval (F-007).

### mixed/ohr-bench-clean

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| — | — | — | lexical | **0.9487** | 0.9044 | 0.9865 | bm25 | A | 0d4b3b1 | 252 |

**Best known:** AI-disabled / lexical = **0.9487**
**Note:** Ground-truth text. Serves as ceiling for ingestion tax measurement.

### mixed/ohr-bench-got-moderate

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| — | — | — | lexical | **0.8090** | 0.7505 | 0.8617 | bm25 | A | 0d4b3b1 | 252 |

**Best known:** AI-disabled / lexical = **0.8090**
**Ingestion tax vs clean:** -0.1397 nDCG (-14.7%). Exceeds >5% decision gate.

### mixed/ohr-bench-mineru-moderate

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| — | — | — | lexical | **0.6382** | 0.5644 | 0.7131 | bm25 | A | 0d4b3b1 | 252 |

**Best known:** AI-disabled / lexical = **0.6382**
**Ingestion tax vs clean:** -0.3105 nDCG (-32.7%). 9.8% of docs have empty/trivial extracted text.

### mixed/ohr-bench-tika-pdf

| encoder | ce | cc | mode | nDCG@10 | P@1 | R@10 | legs | conf | git | src |
|---------|----|----|------|---------|-----|------|------|------|-----|-----|
| — | — | — | lexical | **0.7947** | 0.7484 | 0.8326 | bm25 | A | b13afdc | 252 |

**Best known:** AI-disabled / lexical = **0.7947**
**Ingestion tax vs clean:** -0.1540 nDCG (-16.2%). Original OHR-Bench PDFs through Tika StructuredContentExtractor with extractMarkedContent=true. Most PDFs untagged — structured extraction captures page boundaries but not tables/headings. Comparable to GOT pre-extracted text (-14.7%).

---

## Measurement Gaps

What's worth measuring next. Remove rows when filled.

| Dataset | encoder | ce | cc | Modes needed | Why |
|---------|---------|----|----|-------------|-----|
| mixed/cord19-qddf | bge-m3 | minilm-512 | bm25-dom | full (with working dense) | Re-run after dense fix; upgrade C→A |
| mixed/desktop-mixed-v1 | bge-m3 | minilm-512 | balanced | full (with SciFact qrel fix) | 7% SciFact qrel coverage inflates en_sci degradation. Rebuild with qrel-aware SciFact sampling. |

---

## Key Comparisons

A/B experiments on the same dataset, same queries.

### CE upgrade: minilm-512 → gte-8192 on EnronQA

| Mode | minilm-512 | gte-8192 | Delta | Significant? |
|------|-----------|----------|-------|-------------|
| lexical | 0.810 | 0.812 | +0.3% | No |
| splade | 0.711 | 0.712 | +0.1% | No |
| bm25_splade | 0.830 | 0.828 | -0.2% | No |
| full | 0.810 | 0.808 | -0.3% | No |

**Conclusion:** CE model doesn't matter on email (F-001). CE itself hurts vs bm25_splade by ~2% (F-002).

### Encoder upgrade: splade-v3+nomic → bge-m3 on SciFact

| Mode | splade-v3+nomic / bm25-dom | bge-m3 / balanced | Delta |
|------|--------------------------|-------------------|-------|
| full | 0.684 | 0.723 | +5.7% |

**Conclusion:** BGE-M3 + balanced weights is a major improvement on academic text.

### CE upgrade: minilm-512 → gte-8192 across all tested corpora

| Corpus | Mode | minilm-512 | gte-8192 | Delta |
|--------|------|-----------|----------|-------|
| beir/scifact | full | 0.723 | 0.722 | -0.1% |
| mixed/courtlistener-200 | full | 0.816 | 0.813 | -0.4% |
| mixed/miracl-de-2k | full | 0.734 | 0.735 | +0.1% |
| mixed/enron-qa | full | 0.810 | 0.808 | -0.3% |

**Conclusion:** CE model upgrade produces zero measurable difference on ANY corpus (F-006). BGE-M3 retrieval quality makes the CE marginal.

### CC weights: balanced vs bm25-dom across corpora (full mode)

| Corpus | Lang | bm25-dom (0.60/0.20/0.20) | balanced (0.34/0.33/0.33) | Better |
|--------|------|--------------------------|--------------------------|--------|
| beir/scifact | en | 0.709 | **0.723** | balanced (+1.9%) |
| mixed/courtlistener-200 | en | **0.925** | 0.816 | bm25-dom (+13.4%) |
| mixed/miracl-de-2k | de | 0.639 | **0.734** | balanced (+14.9%) |
| mixed/cord19-qddf | en | 0.383 | 0.390 | balanced (+1.6%) |
| mixed/enron-qa | en | — | **0.810** | — |

**Conclusion:** Balanced wins on short/mixed/multilingual. BM25-dominant wins on long English legal docs. Corpus-adaptive weight selection (FW-001) would optimize both.

### Ingestion quality tax: OHR-Bench clean vs extracted text

| Variant | nDCG@10 | P@1 | R@10 | Delta vs clean |
|---------|---------|-----|------|---------------|
| Clean (gt_text) | **0.9487** | 0.9044 | 0.9865 | — |
| **Tika Structured PDF** | **0.7947** | 0.7484 | 0.8326 | **-16.2%** |
| GOT moderate | 0.8090 | 0.7505 | 0.8617 | **-14.7%** |
| MinerU moderate | 0.6382 | 0.5644 | 0.7131 | **-32.7%** |

**Conclusion:** Extraction quality is the single largest quality bottleneck (F-009). Tika on real PDFs loses 16% nDCG — comparable to GOT because most PDFs are untagged (no structural SAX events). Exceeds the >5% decision gate. **VLM extraction via existing chat model (Qwen 3.5) is the chosen path (252). Docling integration cancelled.**

---

## Pipeline Configuration History

Legacy named configs from the original register. New baselines use
`encoder`/`ce`/`cc` columns instead. Retained for cross-reference with
tempdoc citations that use P0/P1/P2 names.

| Legacy ID | encoder | ce | cc | Notes |
|-----------|---------|----|----|-------|
| P0 | splade-v3+nomic | minilm-512 | bm25-dom | superseded |
| P1 | bge-m3 | minilm-512 | balanced | current default |
| P2 | bge-m3 | gte-8192 | balanced | tested on EnronQA only |
| P3 | splade-ml+gte | gte-ml-reranker | bm25-dom | Current production default (343 Phase D). encoder: opensearch-neural-sparse-encoding-multilingual-v1 + gte-multilingual-base, ce: gte-multilingual-reranker-base |

---

## Findings

Settled empirical facts. Each was an open question that got answered.

### Corpus provenance note (2026-07-01, tempdoc 664 twelfth pass)

`golden/needle-burial-v1`'s corpus content was **regenerated** on this date: the original generator had a
non-determinism bug (per-process `hash()` randomization) and lacked a positional interleave the twelfth pass
added. Regenerating with the same recorded parameters (280 docs, 20 gold chains, seed=636, hops=1,
distractor_ratio=6, semantic=True) produces the same corpus *shape* but different exact entity names/text —
exact byte-reproduction of the pre-fix corpus was confirmed impossible (generator drift), so this is new
content, not a restored original.

**Findings below measured against the pre-regeneration content are historical and not reproducible against
the corpus as currently committed**: F-023, F-024, F-025, D-004's shared-index A/B evidence, and Q-011's
evidence. This is a fact about reproducibility, not a retraction — those measurements genuinely happened and
the cited numbers accurately record what was found *then*. **Already-shipped decisions based on these
numbers are unaffected** (e.g. D-004's leg-arbitration shipping default-off, F-024's recall-complete-pool /
leg-arbitration shipping default-on) — those decisions used real measurements at the time; the regeneration
does not retroactively invalidate a decision already made and shipped.

Current corpus signature (`jseval.corpus_identity.corpus_signature()`, `sha256(corpus.jsonl + qrels/test.tsv)`
for golden/mixed corpora — the same verified-binding mechanism already shared by run manifests and release
records; the function also accepts an explicit `files=` list for non-golden/mixed reference corpora, e.g. the
demo corpus under `examples/demo-corpus/`, tempdoc 669):
`1ade35791b1db58b9a7e1ff21246278d8e588e1705cbeda36d8529ceab6699ec`. Anyone re-deriving or re-verifying the
findings below should check this signature against the corpus they're measuring against, rather than
assuming it matches what's described.

### Corpus provenance note (2026-07-01, tempdoc 666)

Neither `mixed/miracl-de-2k`/`mixed/miracl-fr-2k` nor `mixed/courtlistener-200` ever had a reproducible
construction path anywhere in this project's history (confirmed via the private archive's full,
un-squashed 6563-commit history — tempdoc 666 first pass). This pass fixed both:

- **`mixed/miracl-de-2k` and `mixed/miracl-fr-2k` were regenerated** from the real MIRACL dataset via
  `ir_datasets` (Apache 2.0), with a small, committed, seeded recipe (`scripts/jseval/666-corpora/<name>/
  recipe.json`) recording exactly what to re-fetch and how to sample it deterministically — the corpus
  content itself is never committed (`datasets/` is gitignored for every corpus, by this project's existing,
  universal policy). The new sample targets the same original scale (all dev-split queries + a
  deterministically-sampled distractor pool to the original doc count) but is **new content**, not a
  byte-restoration of the unreproducible original — matching the same "accept new content, verified
  reproducible" resolution tempdoc 664 already reached for `needle-burial-v1`. `mixed/miracl-fr-2k`'s query
  count is corrected from 316 to 343 (all real dev-split queries with a qrel — the prior 316 had no recorded
  sampling method to reproduce).
- **`mixed/courtlistener-200` is retired and replaced by `mixed/legal-clerc-200`.** The original corpus's
  human-authored relevance judgments were a one-off manual curation with no recoverable construction path;
  CourtListener itself does not ship a retrieval benchmark (queries + qrels) to rebuild against. Replaced
  with a corpus built from [CLERC](https://arxiv.org/pdf/2406.17186) (a real, citable NAACL 2025 academic
  legal-case-retrieval benchmark, `jhu-clsp/CLERC` on HuggingFace, built on the Caselaw Access Project — the
  same underlying data family as CourtListener, from the same organization, the Free Law Project), fetched
  fresh via plain HTTP and sampled deterministically (`scripts/jseval/666-corpora/legal-clerc-200/
  recipe.json`). CLERC's own added structure (query construction, citation pairing) has no stated license
  anywhere — checked exhaustively across five channels (GitHub API file listing, GitHub's own license
  detector, the HuggingFace Hub API's dataset-card metadata, and a full-text search of the paper's Ethical
  Considerations/Data Availability sections) — but nothing from CLERC is ever committed to this repo (same
  gitignored-`datasets/` policy as above), so this repo never redistributes it; only the underlying CC0
  Caselaw Access Project text is ever fetched, and only transiently.

**Findings below measured against `mixed/courtlistener-200` are historical and not reproducible against any
corpus currently in this catalog** — that corpus no longer exists in any committed or regenerable form. The
measurements genuinely happened and the cited numbers accurately record what was found *then*; this is a
fact about reproducibility, not a retraction. **Already-shipped decisions based on these numbers are
unaffected** (e.g. the BM25-dominance-on-long-legal-docs finding below) — those decisions used real
measurements at the time. `mixed/legal-clerc-200` has no BM25-dominance ablation yet — a genuinely new corpus
needs its own ablation pass, not an inherited assumption from the retired corpus's shape.

Corpus signatures (`jseval.corpus_identity.corpus_signature()`, `sha256(corpus.jsonl + qrels/test.tsv)`):
- `mixed/miracl-de-2k`: `d6f4026b4b25ac0d117353b830022d77ef3b863b15187907d512d645fae607a1`
- `mixed/miracl-fr-2k`: `a145edfa38d5a783cea52710f256fcee1c0cb33dc100f094d10175eb49ed3297`
- `mixed/legal-clerc-200`: `90d4300d1435c6af00950b6095100fc6b29260385b294dc76896d54308bcfaf1`

### F-024: buried-fact retrieval is a fusion/recall-gating problem, not a query-expansion one

*(needle numbers below predate the 2026-07-01 corpus regeneration — see Corpus provenance note above)*

- **Answer:** Graded the three tempdoc-636 buried-signal levers via `jseval --start-backend --llm`
  through the full `hybrid`+CE pipeline, on `golden/needle-burial-v1` (synthetic buried-fact target)
  and `mixed/enron-qa` (real email regression guard). **Recall-complete pool** (each leg's top-N
  guaranteed into the CE window): needle 0.2716→**0.539 (+98%)**, enron **−0.04% (neutral)** →
  **default-on**. **Leg-arbitration** (per-query CC-alpha raise): needle 0.2716→**0.6105 (+125%)** but
  enron **−1.4%** → **default-on** (user decision 2026-06-24, accepting the real-email cost). **Synonym expansion**
  (LLM query-side synonyms): needle **0% twice**, incl. an always-fire isolation run → **deleted** —
  in the full pipeline the dense leg already supplies the semantic bridging, so it is redundant.
- **Evidence:** tempdoc 636 §GRADED + DECIDED (2026-06-24). Baselines: needle hybrid 0.2716
  (`comparable:False` — small synthetic corpus); enron hybrid 0.7379 (`comparable:True`, ≈ register
  0.740).
- **Conditions/caveats:** needle is synthetic and built to favour these levers — weight enron (real)
  more for ship decisions. Leg-arbitration's −1.4% is small but consistent across two independent runs.
  **Combined production default (both levers on, 2026-06-24):** needle **0.8012 (+195%)**, enron
  **0.7142 (−3.22%)** — the levers interact non-additively (synergy on the target, *super-additive*
  regression on email; leg-arbitration over-fires when the recall-complete pool is active). Tightening
  leg-arbitration's trigger to be pool-aware is the open follow-up (router Item-1).

### F-025: recall-survival is a measurable, regime-blind funnel — and it tracks the shipped fix

*(needle-burial-v1 numbers below predate the 2026-07-01 corpus regeneration — see Corpus provenance note
above)*

- **Answer:** The **Staged Recall Accounting** instrument (tempdoc 636 / D-005) decomposes every judged query
  into **leg-recall / cascade-leak / judge-rank** as a pure `jseval` projection over existing run artifacts
  (`{mode}_per_query.json` presence + score-ranked `{mode}_run.trec`), auto-run at end-of-run, with a focused
  **`jseval leak-gate`** ratchet on `leak_rate`. It measures whether each pipeline stage *kept the correct
  document*, not just an aggregate score — and it **demonstrably tracks the shipped levers**: on
  `golden/needle-burial-v1`, same corpus, the cascade-leak fell from **11/20 (leak_rate 0.55, CE-off,
  `vector`+`hybrid`)** to **2/20 (0.10, both levers default-on + CE-on, all 3 legs)**, with final_recall
  0.45→0.90 and final nDCG 0.318→0.801. **0 reconciliation mismatches** (the projection's presence call vs the
  harness's recorded recall) on both runs — confirming doc-ID alignment.
- **Evidence:** tempdoc 636 §IMPLEMENTED + validated (2026-06-24). 877 jseval unit tests green (no regressions)
  + 22 new tests. The AI-free **`judge_headroom_ceiling`** (`leg_union_recall − final_ndcg` = what a *perfect*
  judge over the current pool could add) = 0.68 (CE-off) then 0.20 (production default) — a clean
  "judge/cascade is the bottleneck, not the legs" prioritization signal (legs find the needle 100% throughout).
- **Conditions/caveats:** validated on the synthetic needle corpus (the design's regression guard — a clean
  CE-on, both-levers run) **and on a non-synthetic register corpus (courtlistener-200, 200 judged, 0
  reconciliation mismatches)** — where it reports a *different* failure regime: **LEG_MISS-dominated**
  (`leg_miss_rate 0.28`, `leg_union_recall 0.685`, `leak_rate 0.07`; per-leg `lexical 0.64 / vector 0.25 /
  splade 0.175`), i.e. a component/representation bottleneck, vs the needle's fusion-leak regime. The
  **leak-gate fires/passes/skips correctly on this real data**. The optional LLM-*realistic* probe ran live
  (`jseval judge-ceiling`, GPU `Qwen3.5-9B`): **`capture_fraction ≈ 0.11`** of the 0.199 ceiling with
  `top1_agreement 0.20` (highly position-sensitive) — empirically confirming the **AI-free
  `judge_headroom_ceiling` is the decision-relevant figure** and the live judge is a coarse, biased signal.
  (The first attempts at these two were blocked by multi-agent contention on the shared default port 33221 +
  `tmp/headless-eval-data` — root-caused in 636 §Root-cause, *not* a code defect; a quiet-window re-run
  completed immediately.) The instrument is **eval-only** (recall-survival needs qrels). Layer-3 deep
  intra-fusion attribution stays deferred (only-if-warranted).
- **Guard ACTIVE + cross-corpus profile (2026-06-24 follow-up, 636 §guard-activated):** the leak-gate is now
  **pinned** (`leak-gate-baselines.v1.json`, measured-derived via `leak-gate-derive`: needle 0.100 /
  courtlistener 0.070 / scifact 0.013 / enron-qa 0.047, tol 0.05) and **wired** into the `search-engine-hint`
  hook as the third engine ratchet (relevance + perf + leak). Across **four diverse corpora** (synthetic/legal/
  academic/email, 0 reconciliation mismatches each) the instrument distinguishes regimes — legal is
  **leg-recall-bound** (leg_miss 0.28), academic + email are **judge-rank-bound** (judge_low 0.25, legs find it
  ≥0.89) — and the regime-blind headline is decisive: **cascade-leak is small everywhere (0.013–0.100, mean
  ≈0.06)** so v3's fix holds and **Layer 3 stays deferred**; the cross-corpus **headroom is the judge
  (judge-rank, the largest bucket) and the legs (leg-miss)**, pointing the next regime-blind lever at a sharper
  judge (§2-C / §5 probe) and/or component quality (F-009), *not* another anti-leak fix. This is the §0/D-005
  reframe — *capability = guarantees + leaks + component quality* — now **measured**, not asserted.
  - **Owners of the two pointed-at levers (2026-06-24 triage):** the **judge-rank** side (the dominant bucket)
    is now tempdoc **643** (judge-stage ranking quality — measurement exists via the §5 probe, lever design
    deferred); the **leg-recall / candidate-set** side is tempdoc **639** (ANN recall + dedup, measurement
    deferred). The one-command cross-corpus profile that produced this finding is `jseval recall-profile`
    (tempdoc 636 §IMPLEMENTED — **note: uncommitted at time of writing, working-tree only**).

### F-026: judge-rank-low is real and substantively spread (not near-ceiling) on a real corpus, but the obvious judge levers are dead/harmful — the surviving lever is a confidence-bounded floor, not a sharper judge

- **Answer:** Tempdoc 643 picked up the judge-rank-low bucket F-025 pointed at. Three corrections to the
  original framing, then a design: **(1)** `JUDGE_RANK_LOW` means *gold is in the returned top-10 but not
  rank-1* — the **opposite** of "ranked below the cutoff" (that is `CASCADE_LEAK`); the FP2 annotation had
  this backwards and is now corrected (`staged_recall_accounting.py` `FP_MAPPING`, `recall_profile.py`
  `_RECOMMENDATION`). **(2)** On the two named real corpora where the bucket dominates, the stub's named
  levers are dead-on-arrival: a sharper CE is measurement-rejected on academic (F-006: model swaps ≈0 nDCG)
  and actively harmful on email (F-002/F-008: CE demotes/ejects the gold); a judge-guided recall loop targets
  `LEG_MISS`/`CASCADE_LEAK`, not an already-in-window rank — it is 639's lever, not 643's. **(3)** **The bucket
  is not near-ceiling** — a real measurement (`scifact`, 300 queries, CE-on) shows the in-bucket rank
  distribution spread across the window, not bunched at rank-2: `{rank_2: 28, rank_3_5: 31, rank_6_10: 21}` (80
  judge-low queries total; corrected 2026-07-01, see the methodology-correction bullet below — originally
  published as `{rank_2: 28, rank_3_5: 39, rank_6_10: 14}`, 81 queries), i.e. a genuine, substantive
  mis-ranking, not "one slot off." The shipped design is a
  **relative-confidence-gated refinement floor**: blend the CE's reorder with the pre-rerank (fusion/LambdaMART)
  order (min-max normalized within the CE window) instead of letting the CE replace it outright, keyed on a
  *relative, label-free* signal (CE score-margin + Head-reconstructed leg-agreement) rather than a *fitted*
  calibration (literature-rejected for a cold-start, cross-corpus engine — calibration does not transfer across
  corpora). Shipped **default-off** behind `JUSTSEARCH_RERANK_JUDGE_BLEND_ENABLED` /
  `JUSTSEARCH_RERANK_JUDGE_BLEND_ALPHA` (D-004 template: default-off → measure → default-on).
- **Evidence:** Live, worktree-isolated eval (`643-judge-arbitration`, GPU RTX 4070, **reranker realized on CPU**
  — see caveat below): `beir/scifact`, CE-on, hybrid mode, 300 queries. **Floor OFF** (today's behavior):
  `final_ndcg=0.7512`, `judge_low_rate=0.267`, histogram `{rank_2:28, rank_3_5:31, rank_6_10:21}`. **Floor ON**
  (`alpha=0.5`): `final_ndcg=0.7490` (Δ −0.0022, within this corpus's observed run-to-run wobble — see caveat),
  `judge_low_rate=0.273`, histogram `{rank_2:33, rank_3_5:32, rank_6_10:17}` (all four numbers corrected
  2026-07-01 from a trec-based computation to the true final-response-order computation — see the
  methodology-correction bullet below). The floor's real per-query effect, measured correctly, is substantial:
  **58/300 queries** shift bucket between OFF and ON — but the *aggregate* judge_low_rate move (0.267→0.273) is
  not distinguishable from this corpus's own documented run-to-run wobble (caveat (b) below), so this single-run
  comparison cannot establish whether the floor's net aggregate effect on judge_low_rate is positive, negative,
  or neutral — only that it is real and large at the per-query level. (The originally-published claim — "shifted
  5 queries rank_3_5→rank_6_10... confirming... the gate fires" — described noise between the two eval runs, not
  the floor's effect; trec-based rank is structurally blind to the floor's reordering, since the floor never
  rewrites a hit's `score` field, only its list order.) The config chain (`EnvRegistry`→`ResolvedConfigBuilder`→
  `ResolvedConfig`→`RerankerConfig`) is still confirmed to propagate correctly end-to-end (Head **and** Worker
  config-snapshot logs both confirmed `judge_blend_enabled=true`, `judge_blend_alpha=0.5`) — that conclusion does
  not depend on the bucket-shift number. **Signal-separation probe (U2, the crux):** per-query CE-on vs
  CE-off gold-rank delta vs {CE top1−top2 margin, Head-reconstructed leg top-10 BM25/dense Jaccard} on the same
  300 queries: only **9 non-neutral queries** (1 helped, 8 hurt by CE; 261 neutral, 30 no-gold-either) — both
  signals point the *right direction* (margin AUC 0.75, Jaccard AUC 0.69 — "helped" cases have higher
  margin/agreement than "hurt" cases) but **n=9 is too thin for a confident conclusion**.
- **Conditions/caveats (important):** **(a)** the cross-encoder ran on **CPU, not GPU**, in every run this
  finding's evidence comes from (`Capability warning: reranker_cpu_only`) — same model/weights, but not the
  production GPU path; not expected to flip direction, unrecorded magnitude effect. **(b)** Run-to-run wobble on
  this corpus/config was non-trivial across the 3 runs taken (hybrid nDCG 0.7512 → 0.7584 (CE-off) → 0.7490
  (floor-on), a ~1.25% range) — single runs, not multi-seed; the floor's −0.3% delta is not distinguishable from
  this noise. **(c)** **`mixed/enron-qa` — the corpus where F-002/F-008 predict the largest CE-hurts signal, and
  the most decisive test of the floor's actual rescue effect — was UNAVAILABLE in the eval environment**
  (`datasets/mixed/enron-qa/corpus.jsonl` not present; real email data, not BEIR-auto-downloadable, not
  worktree/main-resolvable like `models/`). **Decision (2026-07-01): ship the floor implementation now
  (default-off, fully tested, config-verified live); defer flipping the default and building the active-promotion
  half (confidence-gated skip + promote-when-CE-confident) until the email-corpus measurement is actually run** —
  the evidence is directionally encouraging but not sufficient to justify an active behavioral change.
- **Post-implementation critical-analysis pass found + fixed a pre-rerank-signal bug (2026-07-01, same session).**
  The floor originally read only the `"fusion"` HitStage as the pre-rerank score. That signal is **absent** for
  single-leg presets (BM25-only/`text`, dense-only/`vector`, SPLADE-only/`splade` — `HitProvenanceProjector.
  attachSingleLeg` passes `fusionMethod=null`) and **stale** on hybrid queries where chunk-branch fusion ran (the
  true final score there lives on a separate `"branch-fusion"` stage) — exactly the EnronQA case, per F-014's
  "chunk merge fires on all 300 queries." Fixed via `SearchTraceMapper.protoStageScoreAny` (priority-ordered,
  presence-based fallback: `branch-fusion` → `fusion` → the single active leg) and the matching fallback in
  jseval's `extract_judge_signals`. **The scifact numbers recorded above are unaffected** (that run used
  unchunked hybrid mode, where `"fusion"` was present and correct) — but had this shipped uncorrected, it would
  have silently biased the `mixed/enron-qa` follow-up recommended below. A second, lower-severity finding (a
  missing CE score defaulting to `0f`, which reads as artificially high against typically-negative real CE
  logits) was also fixed (defaults to the worst *observed* CE score instead). See tempdoc 643 §Post-implementation
  critical-analysis pass for full detail.
- **Follow-up closed (2026-07-01, same worktree): E1 (confidence signal) + E2 (confidence-driven blend) +
  perf-skip built, and the §9-4 A/B regression-rate test run on both corpora, then re-run with a wider
  window after a critical-review finding (see below).** `mixed/enron-qa` is no longer unavailable (acquired
  via `scripts/search/convert-enronqa-to-beir.py`). Per-query regression rate (final gold rank worse than
  the reconstructed pre-rerank/fusion gold rank), hybrid CE-on, 300 queries each,
  `judge_arbitration_enabled=false` (static floor) vs `=true` (confidence-driven, `alpha_diverge=0.85`),
  **final numbers (`--top-k 20`, matching the CE window default — supersedes an initial `--top-k 10`
  measurement that undercounted regressions falling outside the display page)**: scifact 5.67%→4.00%
  (fixed 5 queries, caused 0 new — net −5, a clean, one-directional win); enron-qa 8.33%→8.67% (fixed 1,
  caused 2 — net +1, a small, thin net *negative*, not a wash — consistent with the borderline pooled AUC
  already measured; NOT the CU5 chunk-merge bailout, `chunkMergeApplied=false` on all enron-qa queries in
  this run, so the gate genuinely evaluated real signal and it wasn't discriminative enough to net a
  benefit there). **Net-positive on scifact; a small net-negative on enron-qa** — not "never net-harmful on
  either corpus" as an earlier draft of this entry stated before the wider-window re-run. This does not
  change the shipping decision: per D-004's default-off → measure → default-on template and this tempdoc's
  own non-goals, defaults stay off regardless. Full detail: tempdoc 643 §E1/E2/perf-skip implementation +
  §9-4 acceptance test (original and re-measured).
- **Methodology finding surfaced by §9-4, and used to correct this finding's own numbers above (2026-07-01
  critical-analysis pass; root cause logged to observations, out of scope for 643 to fix in full):** jseval's own
  `{mode}_run.trec` — and `staged_recall_accounting.py`'s `_ranked_by_qid`, which *prefers* that same trec file
  over the true response-order `predictedDocIds` — are blind to CE/judge-blend list-reordering. The CE/blend
  stage only ever reorders the result list, never rewrites a hit's top-level `score` field (true even in the
  pre-tempdoc-643 baseline), and `_write_trec_run`'s re-sort, `ir_measures`' internal ranking, *and*
  `staged_recall_accounting`'s rank buckets all key off that same unrewritten score. Only per-query
  `predictedDocIds` reflects the true post-rerank order.
  This does not invalidate cross-config comparisons that differ via *other* pipeline effects (different
  corpora, models, eligibility gating, leg composition) — most of this register's historical findings are
  unaffected. It DOES invalidate same-config, reorder-only comparisons: this finding's own "Floor OFF vs Floor
  ON" evidence above is exactly that case, which is why it was recomputed and corrected here. Directly
  quantified on the archived run pair that produced this finding's original numbers
  (`scripts/jseval/tmp/eval-results/643_scifact_ce_on/20260630T232234_scifact` and
  `.../643_scifact_ce_on_floor/20260630T234714_scifact`): 83/300 queries land in a different judge-rank bucket
  between trec-order and true order for the Floor-OFF run alone (aggregate `judge_low_rate` barely moves,
  0.270→0.267, but individual bucket assignment is materially wrong 27% of the time); comparing Floor OFF vs ON
  via trec shows only 12 queries shift bucket, vs 58 via true order — a ~5x undercount of the floor's real
  effect. My own §9-4 script above was built using `predictedDocIds` from the start (I hit this exact issue
  while building it and fixed my own script before publishing those numbers), so the 5.00%→3.67% / 7.33%→7.33%
  figures are unaffected. Any *future* per-query rank-based analysis of a stage that only reorders without
  rescoring should use `predictedDocIds`, not the trec file or `staged_recall_accounting`'s buckets as-is.
  **Scope decision:** `staged_recall_accounting.py`'s root cause (the trec-preference in `_ranked_by_qid`) is
  NOT fixed in this pass — that's a register-wide change affecting every other finding that relies on its
  per-query rank buckets, well beyond tempdoc 643's scope. Left as a well-evidenced observation for a future
  dedicated tempdoc.
- **U1 (the live LLM judge-ceiling probe, tempdoc 636 §5) is now measured (2026-07-01, corrected later the
  same day) — a real, credible, decision-relevant *positive* result, closing this tempdoc's last open
  measurement gap.** With explicit user authorization, downloaded the packaged-default chat model
  (`Qwen_Qwen3.5-9B-Q4_K_M.gguf`, 5.5GB, SHA-256 verified) and ran `jseval judge-ceiling` on a 40-query scifact
  sample, GPU-accelerated (RTX 4070, 33/33 layers offloaded), with real document text (a text-light first
  attempt gave `top1_agreement=0.0` — a self-evident confound, not a finding — recomputed with real text,
  giving a credible, non-degenerate measurement). **Correction:** the first-reported result
  (`llm_ndcg=0.111`, `capture_fraction=-6.06`, described as "dramatically worse than the pipeline") was
  **wrong** — a dilution bug in the shared `_score_ranking` nDCG helper silently scored every one of the
  ~260 un-judged corpus queries in this capped 40-query run as a zero-relevance miss and folded that into the
  mean. Found via an unrelated AI-free cross-check (`ce_replay_report`) producing an equally implausible
  number, root-caused, and fixed at the shared function (regression test added). **Corrected result:**
  `final_ndcg=0.831` (current pipeline) vs `llm_ndcg=0.874` — `headroom_realized=+0.042`,
  `capture_fraction=+0.357` (`top1_agreement=0.658`, still a credible, non-degenerate measurement). This local
  model, used via the same single structured-JSON listwise reranking call, **outperforms** the current
  pipeline on this sample and captures a real 36% of the AI-free ceiling. **This changes the evidence base
  D-2's exclusion of a stronger/heavier judge model rested on — it does not by itself overturn that decision
  (one 40-query sample on one corpus), but whether to revisit it is now a live, open question, not a settled
  one.** Along the way, also found and fixed two real, previously-unexercised bugs in
  `scripts/jseval/jseval/judge_ceiling.py`: `max_tokens=512` was too small for realistic candidate-pool sizes
  (truncated JSON responses), and a single query's malformed response aborted the *entire* probe rather than
  degrading gracefully per-query (both fixed, covered by tests in `test_judge_ceiling.py`). Full detail:
  tempdoc 643 `## U1: live judge-ceiling probe result` (including its correction note).
- **E3 (the decision instrument, D-1's third structural element) is now built (2026-07-01), completing the
  design.** A `judge_low_cost_weight` field (`[0,1]`, weighted by rank-2 = near-free vs rank-6-10 = full
  cost) now sits alongside the existing rank histogram in `staged_recall_accounting`'s output, registered as
  its own metric family. A new `jseval judge-arbitration-report` command
  (`scripts/jseval/jseval/judge_arbitration_report.py`) replaces the one-off scripts used for the §9-4
  acceptance test and the confidence-building passes — and in doing so caught a real bug in the earlier ad
  hoc measurement: enron-qa's perf-skip firing rate is **17/300 (5.7%)**, not 15/300 as first reported,
  because the old script incorrectly gated the perf-skip check on a condition that only applies to the
  unrelated alpha-branch calculation. Full detail: tempdoc 643 `## E3 implementation`.
- **A named principle this surfaced (recorded, not built generally):** the *refinement floor* is an instance of
  a broader **stage non-regression** invariant — generalizing D-005's recall-survival ("a stage must not drop a
  *correct candidate*") to *property-survival* ("a stage claiming to improve a property must not leave it worse
  than its input, with improvement gated on evidence"). Candidate further scope (not built): LambdaMART (latent
  violation — a GPL-trained model was measured to *degrade* real queries, F-021), VLM extraction (possible
  violation — extracted text can be worse than the baseline, F-009), branch/chunk fusion (unverified). Build only
  the next instance when its own evidence demands it (`structural-defects-no-repeat`, applied to *avoid* premature
  generalization here, not to force it).

### F-001: CE model quality is irrelevant on personal email

- **Answer:** Upgrading from MiniLM-L6-v2 to GTE-ModernBERT produces zero measurable difference on EnronQA (±0.3% nDCG, noise level).
- **Evidence:** tempdoc 309 §43
- **Conditions/caveats:** Tested on EnronQA (verbose QA questions, single-user inbox). CE may still matter on academic/legal corpora (SciFact, CourtListener).

### F-002: CE actively hurts on personal email

- **Answer:** `full` mode (includes CE) scores 0.810 vs `bm25_splade` (excludes CE) at 0.830 — CE degrades ranking by ~2%.
- **Evidence:** tempdoc 309 §42, §43 (confirmed with both MiniLM and GTE-ModernBERT). **343 Phase D:** CE-on vs CE-off isolation with multilingual stack confirms CE hurts EnronQA by 3-5% across all modes (lexical 0.827→0.799, full 0.822→0.777). CE helps SciFact (+3.2%) and MIRACL/de (+4.8%).
- **Conditions/caveats:** EnronQA only. CE helps on academic/multilingual. Confirms FW-001: corpus-adaptive CE gating needed.

### F-003: BM25 dominates on entity-heavy personal content

- **Answer:** BM25 alone achieves 0.810 nDCG@10 on EnronQA. Sparse adds +2.5% (bm25_splade 0.830). Dense adds nothing measurable.
- **Evidence:** tempdoc 309 §42
- **Conditions/caveats:** Verbose QA queries, not short navigational queries. Pattern may differ with real search queries.

### F-004: Optimal fusion mode is corpus-dependent

- **Answer:** `full` wins on academic/multilingual (SciFact 0.723, MIRACL/de 0.639). `bm25_splade` wins on personal email (0.830). BM25-dominant weights win on long legal docs (CourtListener 0.925 at 0.60/0.20/0.20).
- **Evidence:** tempdoc 309 §42, §35, §37
- **Conditions/caveats:** Only 5 corpora tested. Generalization uncertain.

### F-005: bge-reranker-v2-m3 has ONNX GPU regression (5.7x slower)

- **Answer:** ONNX Runtime CUDA provider is 5.7x slower than PyTorch for XLM-RoBERTa-based models. Makes GPU acceleration counterproductive.
- **Evidence:** tempdoc 309 §39, FlagEmbedding issue #987
- **Conditions/caveats:** May be fixed in future ONNX Runtime releases.

### F-006: CE model upgrade irrelevant when retrieval is strong (generalized)

- **Answer:** GTE-ModernBERT (149M, 8192 tokens) produces identical nDCG@10 to MiniLM-L6-v2 (22.7M, 512 tokens) on ALL tested corpora: SciFact (-0.1%), CourtListener (-0.4%), MIRACL/de (+0.1%), EnronQA (-0.3%). All within noise.
- **Evidence:** tempdoc 309 §41 (SciFact, CL-200, MIRACL/de), §43 (EnronQA)
- **Conditions/caveats:** Generalizes F-001 beyond email. Root cause: BGE-M3 produces strong enough top-K rankings that CE reranking is marginal — the `bm25_splade` → `full` gap is only 1-4%, which is the maximum possible CE contribution regardless of CE model quality.

### F-007: Cross-language noise is minimal in mixed multilingual corpus

- **Answer:** German, French, and Chinese retain 90%+ of their isolated retrieval quality when mixed into a single index with English content. Per-language degradation: DE -9.4%, FR -1.0%, ZH +2.7%.
- **Evidence:** tempdoc 309 §38 (Phase 8 mixed desktop corpus eval)
- **Conditions/caveats:** Subsample-based eval (2286 docs, 250 queries). English scientific component has only 7% qrel coverage (data issue, not retrieval failure). BGE-M3's XLM-RoBERTa backbone handles language separation.

### F-008: CE impact is corpus-dependent — helps academic/multilingual, hurts email

- **Answer:** Per-query analysis across 4 corpora shows CE helps 183/305 queries on German (+0.086 net nDCG), 55/300 on SciFact (+0.031), but hurts 45/300 on EnronQA (-0.020). On email, CE demotes the relevant doc in 28 cases and pushes it out of top-10 entirely in 7 cases.
- **Evidence:** tempdoc 309 §42 (per-query failure analysis on existing artifacts)
- **Conditions/caveats:** MIRACL/de's CE "help" is likely the dense leg (active in `full` but not `bm25_splade`), not CE itself — Phase 7 CE ablation showed zero CE effect on German. Strongest evidence for corpus-adaptive CE gating.

### F-009: Extraction noise is the single largest quality bottleneck

- **Answer:** On OHR-Bench (1000 pages, 962 queries, 7 domains), OCR-extracted text loses 15-33% nDCG@10 vs ground-truth text. GOT moderate: -14.7% (0.949→0.809). MinerU moderate: -32.7% (0.949→0.638). Both far exceed the >5% decision gate.
- **Evidence:** tempdoc 252 (Experiment A, 2026-03-19). All runs lexical/BM25 only, `JUSTSEARCH_AI_DISABLED=true`.
- **Updated (343/252, 2026-03-28):** Full pipeline remeasurement with multilingual model stack + CE. Tika/PDFBox: −15.1% (0.952→0.808 full mode). Docling: −7.2% (0.952→0.884). VLM (Qwen 3.5 vision): 81.9% word overlap on 50-page sample (vs Docling 71.5%, Tika 66.3%). VLM is the best extractor with zero new dependencies. Full pipeline (dense/SPLADE) barely compensates for extraction noise (+0.4% for Tika, +4.0% for MinerU over lexical).
- **Conditions/caveats:** MinerU's 9.8% empty-doc rate inflates its penalty. Dominant failure: extraction producing empty or wrong text (178/232 degraded queries). SPLADE is neutral or negative on all extraction variants.
- **Current recommendation:** VLM extraction via existing chat model (Qwen 3.5 + mmproj). Expected to reduce extraction tax from 15.1% to ~5%. Production integration items in tempdoc 346.
- **Post-cutoff candidate (2026-06-15, 580 §14.4):** **PaddleOCR-VL-1.6** (2026-05-28, Apache-2.0, ~1B, ships GGUF/llama.cpp quants, claims 96.33 OmniDocBench v1.6 — *self-reported*) is a verified, low-integration-risk drop-in over the Qwen-VL path; MinerU2.5-Pro (1.2B, Apache) the table-heavy A/B. **Gate the pilot on a retrieval-aware eval, NOT the OCR leaderboard:** *InduOCRBench* / "When Good OCR Is Not Enough" (arXiv 2605.00911, 2026-04-29) verified that high OCR char-accuracy does **not** translate to downstream RAG quality (structural/formatting-semantic errors cause retrieval failures at low WER/CER). Extraction is still hardcoded to llama-server VLM with no pluggable-extractor abstraction (real swap cost).

### F-010: Entity-boosted BM25 does not improve search quality

- **Answer:** Entity text fields (populated by NER backfill) contain the same tokens as the content field. DMQ entity boost at 2.0 hurts nDCG by 4.3%; at 0.5 hurts bm25_splade by 2.2%; at 0.0 is neutral. No positive signal at any boost level.
- **Evidence:** tempdoc 326 Phase 7 (A/B isolation on EnronQA, filtered entities + multiple boost values).
- **Conditions/caveats:** Entity boost would add value if entity fields contained variant tokens NOT in the content field — this requires Phase 4 cluster expansion ("Jim" → "James"). Entity filtering (MIN_ENTITY_LENGTH=2) eliminates the catastrophic regression from noisy single-char entities. Default disabled (0.0).

### F-011: NER model quality is sound (F1=0.91 on CoNLL-2003 validation)

- **Answer:** dslim/distilbert-NER ONNX model + BioTagDecoder subword aggregation achieves F1=0.908 on CoNLL-2003 validation (within 1.4 points of published 0.922). PER F1=0.953, ORG F1=0.839, LOC F1=0.942. Published F1 was almost certainly evaluated on validation set, not test set.
- **Evidence:** tempdoc 326 Phase 6 (jseval ner-eval subcommand). Test set F1=0.863 (harder split).
- **Conditions/caveats:** Eval requires `is_pretokenized=True` for correct word→subword mapping on CoNLL-2003. Production code processes free-form text and is unaffected.

### F-012: Dense retrieval tracking bug — CORRECTED (2026-03-27)

- **Original claim:** Dense retrieval broken for non-BGE-M3 configs. `prepareQueryVector()` falls through to `NO_EMBEDDING_SERVICE`.
- **Correction:** Dense retrieval WAS working with gte-multilingual-base all along. Two separate issues were conflated: (1) EmbeddingGemma's FP16 NaN (head_dim=256, model-specific, resolved by 358 model change), (2) `KnowledgeHttpApiAdapter.buildPipelineExecution()` never emitted `dense: executed` component status on success — only reported `dense: skipped` on failure. jseval's pipeline tracking saw no `dense` in components and reported `requested_dense_but_not_observed`. Fixed: added `dense: executed` reporting when `pipelineConfig.denseEnabled()` and `!vectorBlocked && !hybridFallback`.
- **Impact:** All splade-v3+gemma `full` mode baselines (with gte-multilingual-base auto-discovered) were true 3-way fusion (bm25+splade+dense). Confidence upgraded from C to A. The full vs bm25_splade quality gap IS the dense contribution.

### F-013: SPLADE-v3 sparse quality is 20% below BGE-M3 sparse on SciFact

- **Answer:** SPLADE-v3 achieves nDCG@10=0.501 on SciFact in SPLADE-only mode. BGE-M3 sparse achieves 0.627 — a 20% gap. This is a model quality difference, not a pipeline regression. Confirmed deterministic across 3 independent runs (2 at HEAD, 1 at dc4f79a) with GPU on/off.
- **Evidence:** tempdoc 343 bisect. `tmp/eval-results/20260325T124146_scifact/` (HEAD), `20260325T133143_scifact/` (dc4f79a). Identical nDCG@10=0.5012 at both commits.
- **Conditions/caveats:** Gap may be smaller on other corpora. `bm25_splade` mode (BM25+SPLADE fusion) closes the gap substantially (0.668 vs 0.679) because BM25 dominates. The register's `splade-v3+nomic` baseline of 0.625 (309 §30) could not be reproduced and likely reflects different conditions (cached index or different jseval version). `splade` mode legs corrected from `bm25+splade` to `splade` — jseval sends `sparseEnabled=false`.

### F-014: Chunk merge is a net positive on long-doc corpora; `isShortCorpus()` gate is correct

- **Answer:** On EnronQA (long emails, chunk merge fires on all 300 queries), disabling chunk merge drops lexical nDCG by 1.3% (p=0.04, Cohen's d=-0.12, statistically significant). On SciFact (short abstracts), `isShortCorpus()` correctly gates chunk merge off — all 300 queries show `chunkMergeReason=SKIPPED_SHORT_CORPUS`. The current chunk branch defaults (50/50 CC with parent-length modulation) work well despite the 6-field whole-doc vs 1-field chunk asymmetry introduced by 306/326.
- **Evidence:** tempdoc 343 Phase 2.2. Chunk-ON: `tmp/eval-results/20260324T131341_mixed_enron-qa/`. Chunk-OFF: `tmp/eval-results/20260324T140216_mixed_enron-qa/`. Same index, same config except `JUSTSEARCH_SEARCH_CHUNK_AWARE_ENABLED`.
- **Conditions/caveats:** Only tested on EnronQA (long) and SciFact (short). `bm25_splade` mode shows same direction (-1.4%) but not statistically significant (p=0.191). No adjustment to branch weights needed at current defaults.

### ~~F-015: CE impact depends on retrieval strength~~ — RETRACTED

- **Retracted (2026-03-27):** The evidence was invalid. jseval's `FULL_PIPELINE` had
  `crossEncoderEnabled: false` — CE never ran in any jseval-measured run. The
  full vs bm25_splade delta (0.822 vs 0.813 on EnronQA) was CC3 fusion path
  overhead (3-way vs 2-way fusion with broken dense leg), not CE impact.
  CE impact with splade-v3+gemma remains unmeasured. Fixed: jseval `--ce` flag
  added to enable CE in eval runs.

### F-016: Schema complexity degrades small model performance

- **Answer:** 16.1% average degradation when schema complexity increases (arXiv:2504.19277). Confirmed in 366 Phase 6 bloat experiment: adding 2 optional parameters (`doc_ids`, `return_full_documents`) dropped accuracy from 92% to 71%. Removing them restored 86–94% accuracy.
- **Evidence:** tempdoc 366 Phase 6 (3 eval rounds: bloated 71%, lean 90%, final 94%)
- **Conditions/caveats:** Tested with Haiku-class agents. Opus or larger models may be less sensitive. The safe pattern: implement in backend, document in description text, keep schema minimal.

### F-017: Tool consolidation impact — 7 to 4 MCP tools

- **Answer:** Consolidating 7 capability-oriented MCP tools to 4 task-oriented tools produced +20pp accuracy on 50q Haiku eval (72%→92%). Validated across Phase 5 (92%) and Phase 6 (86–94%).
- **Evidence:** tempdoc 366 Phase 4 (4a–4e). Literature: Block Engineering (30+ tools → 2), "MCP Tool Descriptions Are Smelly" (arXiv:2602.14878).
- **Conditions/caveats:** The improvement includes tool description rewrites and position bias optimization (answer-first), not just count reduction.

### F-018: Soft-boost QU is safe and neutral-to-positive

- **Answer:** QU-extracted `boostFilters` applied as `ConstantScoreQuery` SHOULD clauses: nDCG +0.12%, RR@10 +0.82%, no metric degrades on MultiHop-RAG (50 queries, 611 docs). Hard filters are strongly net-negative (-20.1% nDCG, 7/50 zero-result). Weight=20 with `ConstantScoreQuery` produces +3.2% nDCG on the boost weight sweep.
- **Evidence:** tempdoc 363 V3 eval (4-condition comparison) and V3.3 real Lucene boost weight sweep.
- **Conditions/caveats:** Gains are modest on MultiHop-RAG because queries require multi-source evidence. Single-source filtering queries would show larger improvement. QU currently disabled by default (`JUSTSEARCH_QU_ENABLED`) due to LLM scheduling contention.

### F-019: QPP closed — signals cannot separate null from answerable queries

- **Answer:** QPP signals (`query_scope`, `max_idf`, `avg_ictf`) cannot separate null from answerable queries. Null query top scores: 0.70–1.0; inference query top scores: 0.82–1.0 — complete signal overlap. QPP++ (ECIR 2025, arXiv 2504.01101) confirms lack of robustness across settings.
- **Evidence:** tempdoc 363 §Context sufficiency detection, tempdoc 366 Phase 2f.
- **Conditions/caveats:** Post-retrieval context sufficiency checking (Google ICLR 2025) is the alternative path. QPP should not be used for query routing.

### F-020: Hybrid deterministic+LLM filter normalization

- **Answer:** Deterministic tier (prefix/contains matching) handles 80%+ of filter mismatches at 0 ms. LLM grammar-constrained enum handles semantic gaps (~400–1200 ms GPU). Filter mismatch complaints dropped from 19 (Phase 4) to 1 (Phase 5+).
- **Evidence:** tempdoc 366 Phase 5 (5a–5j). Hybrid validation: 6/8 cases at 0 ms. 50q eval: accuracy maintained (91.8% vs 92% baseline), cost -4%, turns -4%, duration -25%.
- **Conditions/caveats:** Requires facet vocabulary snapshot (gRPC facet query). Empty vocabulary degrades to LLM-only (still works, just slower). CBS Sports semantic gap needs a more capable model.

### F-021: GPL-trained LambdaMART reranking HURTS / is non-viable without real user-feedback labels

- **Answer:** The GPL→LambdaMART learned-fusion reranker (2-feature: sparse+vector, trained on GPL **synthetic** queries) does NOT improve ranking and **consistently degrades** nDCG on real queries. It is non-viable in our cold-start (no real-feedback) situation. **Do not re-propose "activate/enrich GPL-LambdaMART" as a quality lever without real user-click data first.**
- **Evidence:** tempdoc 245 measured it across three BEIR datasets — **SciFact −0.009, Arguana −0.10, NFCorpus −0.021** (`245-execution-log.md:61`); root cause *"GPL synthetic queries don't transfer to real BEIR queries"* (`245:332`); verdict *"not viable without real user query data," "may be fundamentally unrecoverable"* (`245:1263`). **Re-confirmed live 2026-06-15** (tempdoc 580 §12.8–12.9): on cord19 the GPL-trained model was a **degenerate no-op** — `hybrid_run.trec` byte-identical with vs without the model (LightGBM "no meaningful features" training warning); the reranker executes (`KnowledgeSearchEngine.java:531-574`) but changes nothing.
- **Conditions/caveats:** 234 *predicted* LambdaMART beats fixed fusion *"when ≥500 labelled queries are available"* — the failure is specifically the **GPL synthetic substitute** for those labels, not learning-to-rank per se. The substrate (GPL pipeline + LambdaMartReranker, 2-feature) is built+wired and the bootstrap first-model bug was fixed (580 §12.7); what's missing is **real implicit-feedback capture** (clicks/opens — confirmed absent, 580 §12.1). So this is a *ship-then-learn* dependency, not a code/feature change. (D-002's corpus-adaptive CC-weight idea / FW-001 is a separate, also-superseded lever — see 580 §10.) **Real labels are necessary but NOT sufficient (580 §13.7, code-verified):** the V1 feature vector `[sparse, vector]` IS fusion's own leg scores (`LambdaMartFeatureSchema`; reranker runs on the *already-fused* list reading the same `sparse-/dense-retrieval` stage scores, `KnowledgeSearchEngine.java:544-560`) and even collapses BM25+SPLADE into one "sparse" slot — so the model is informationally *poorer* than the fusion it post-processes and is **structurally capped below fusion regardless of label quality**. Any real lift needs BOTH rich features beyond fusion's own scores (234 V2 schema) AND real labels. **Do not "capture feedback → re-activate the existing 2-feature model"** — that satisfies labels and still loses to fusion. (The §13.3 *additive-feature, label-free* fusion-weight selector is the cheaper sibling lever this analysis points to.) **Refinement (580 §16, code-verified):** "real labels confirmed absent" is too strong — *user-click/explicit* feedback is absent (a build away), but the **agentic path already persists a graded real-query signal** (retrieved ⊃ grounding ⊃ cited, with `parentDocId`/`chunkIndex` + similarity; `AgentCitationResolver`/`AgentInteractionMapper`). It's real-query (unlike GPL synthetic → sidesteps 245's failure mode) and a **harvest, not a build** — but it is **reorder-only** (recall-blind: the agent can only grade within retrieved top-k, with a circularity risk) and LLM-judged, not user behavior. The cheapest first-real-label experiment: assemble the persisted citation tuples, train on them, A/B on held-out real queries.

### F-022: CC beats RRF for chunk-branch fusion (CC shipped as default)

- **Answer:** Convex-combination (CC) branch fusion outperforms RRF on the whole-doc ⊕ chunk-parent merge. The `branchFusionStrategy` rrf-vs-cc switch is fully wired (`SearchExecutor.java:692,704`); **CC is the default, chosen by measurement, not assumption.**
- **Evidence:** tempdoc 280 §GPU-backed verification — CC nDCG@10 **0.7593** vs RRF **0.6062** on a short cord19-qddf smoke (directional; gates skipped, not acceptance-grade, but decisive for the default).
- **Conditions/caveats:** Single short smoke run, not re-validated at HEAD. The RRF path stays selectable for A/B; no acceptance-grade comparison exists.

### F-023: Whole-doc dense dilution is real and scales; lexical+CE legs suppress dense on paraphrase queries

*(numbers below predate the 2026-07-01 corpus regeneration — see Corpus provenance note above)*

- **Answer:** On a purpose-built buried-signal corpus (`golden/needle-burial-v1` — long generic-filler docs, one buried distinctive head per chain, **zero-lexical-overlap paraphrase** queries, head-only qrels), the **whole-doc (`vector`) dense nDCG@10 collapses monotonically with distractor scale: 0.820 (280 docs) → 0.526 (1240) → 0.429 (2440)** — the maximally-diluted mean-of-means vector progressively loses the needle to near-identical filler twins. Separately, **`vector` ≫ `hybrid` on these paraphrase queries** (0.820 vs 0.318 at 280 docs; head@rank-1 12/20 vs 5/20, hybrid *misses* the needle on 9/20) — adding the lexical (BM25/SPLADE) + cross-encoder legs **actively demotes/drops the dense-found needle** on grep-defeating queries.
- **Evidence:** tempdoc 636 §Phase-1 eval (2026-06-23). `jseval run --dataset golden/needle-burial-{s6,s30,s60} --modes vector,hybrid --start-backend --clean --embedding`; `vector` `comparable=True`.
- **Conditions/caveats:** Synthetic extreme (100% zero-overlap paraphrase, 20 queries) — exaggerates the `vector≫hybrid` inversion vs real mixed queries. **jseval `hybrid` reported `chunkMergeApplied=null` on all queries** (the chunk-passage branch did not apply), which **contradicts the live interactive probe** (636 §Pre-impl pass: production hybrid fired `branch-fusion: executed` and ranked a needle decisively) — so jseval-`hybrid` is **not representative of the production default path** and its low numbers must not be read as "production hybrid collapses." See Q-011.

---

## Decisions

Design choices in the current production pipeline, with rationale.

### D-001: Ship GTE-ModernBERT as default CE — SHIPPED

- **Choice:** Replace MiniLM-L6-v2 with GTE-ModernBERT-base
- **Status:** Shipped (session aeb47d37). `models/onnx/reranker/` now contains GTE-ModernBERT. MiniLM backed up to `reranker-minilm-backup/`. Default `maxSequenceLength` changed from 512 to 8192.
- **Rationale:** 8192-token context eliminates truncation damage on long docs. Neutral on all tested corpora (F-006). Low effort (model swap + config change).
- **Evidence:** tempdoc 309 §41 (confirmed neutral on SciFact, CL-200, MIRACL/de, EnronQA)
- **Revisit when:** settled. CE model is no longer a quality lever — gains come from retrieval (BGE-M3) and fusion (balanced weights).

### D-002: Balanced CC weights (0.34/0.33/0.33) for BGE-M3

- **Choice:** Equal weights across BM25/dense/sparse when BGE-M3 is active
- **Rationale:** BGE-M3 produces strong dense AND sparse (unlike old SPLADE+nomic where both were weak)
- **Evidence:** tempdoc 309 §35 Phase 5. Cross-corpus validated: balanced wins on SciFact (+1.9%), MIRACL/de (+14.9%), CORD-19 (+1.6%). BM25-dominant wins on CL-200 (+13.4%).
- **Revisit when:** corpus-adaptive weight selection is implemented (CorpusProfile-driven). Implementation path: `CorpusProfile.isLongCorpus()` → bm25-dom, else → balanced.

### D-003: Native multilingual, no per-language levers — REJECT per-language components

- **Choice:** The engine stays multilingual *by construction*. No per-language artifact a contributor must author or maintain — no language-specific stemmer/analyzer field, stopword list, spelling dictionary, or hand-curated synonym set — is added. Detected *language as a signal* to one uniform policy is allowed (bucket B); language-agnostic levers are evaluated on their own merits (bucket C).
- **Rationale:** Per-language components cost O(languages) maintenance forever and degrade silently. The multilingual model stack (gte-multilingual-base + opensearch-neural-sparse-multilingual + gte-multilingual-reranker) already delivers 90%+ cross-language retention (F-007) and strong MIRACL de/fr/zh through one uniform pipeline. The per-language scaffolding that existed (the `content_{en,de}` fields + `en`/`de` analyzers + empty synonym files) was verified inert and removed (tempdoc 581 §13); analysis is now locale-invariant (ICU + NFC + lowercase). Full reasoning + the three-bucket classifier: **ADR-0043** / tempdoc 581.
- **Enforcement:** the analyzer-provider `enum` in `SSOT/schemas/indexing/analyzers-catalog.schema.json` (rung 1, a per-language provider is unrepresentable) + the `language-agnostic-analysis` CI gate (rung 2, `scripts/ci/check-language-agnostic-analysis.mjs`).
- **Closes:** FW-006 (stemming), Q-004 (locale-aware BM25 routing), per-language synonym programs — all **won't-do**. Leaves FW-002 (spell correction; index-term-based, no per-language dict) and language-agnostic levers (FW-008, recipe weights) open on their own merits.
- **Revisit when:** a *measured large* monolingual gap appears that a uniform mechanism (a better single multilingual model, or a per-deployment model choice) cannot close — never an O(languages) program (581 §5).

### D-004: Query-adaptive leg arbitration on the 2-way CC alpha — SHIPPED (default off)

- **Choice:** In the default 2-way `hybrid` path (`HybridSearchOps.executeHybrid` → `fuseWithCC`), make `ccAlpha`
  **per-query adaptive**: raise alpha toward dense (`max(ccAlpha, alphaDiverge)`) — down-weighting the lexical leg —
  **only when all three hold**: (a) dense clears a weak sanity floor (top ≥ 0.5), (b) the legs diverge (top-K
  doc-id Jaccard < 0.1), and (c) **BM25 is incoherent** (its own `top2/top1` ratio ≥ `bm25IncoherenceMin`, i.e. a
  flat top / no clear lexical winner). Condition (c) is the discriminator that protects BM25-dominant corpora
  (legal/email), where BM25 returns a *peaked* winner and is usually right. All signals are rank/ratio-based
  (score-incomparability). Gated by `JUSTSEARCH_HYBRID_LEG_ARBITRATION_ENABLED` (**default false**) +
  `…_ALPHA_DIVERGE` (0.7) + `…_BM25_INCOHERENCE_MIN` (0.9), all env-tunable.
- **Status:** Shipped behind a **default-off** flag (tempdoc 636 §Review fix #2). A specialized, opt-in lever — see
  the honest limitation below. The concrete instance of the recipe-weight function 580 §10/§13 named; principle
  "symmetric per-query leg arbitration".
- **Evidence (rigorous shared-index A/B — build once, OFF vs ON on the *same* index, noise-free; the
  needle-burial-v1 figure predates the 2026-07-01 corpus regeneration — see Corpus provenance note above):**
  `golden/needle-burial-v1` (paraphrase) **0.241 → 0.712 (+195%)**; `scifact` (academic) **0.7599 → 0.7641**
  (neutral); **`mixed/enron-qa` (personal email) 0.7422 → 0.7268 (−2.1%, REAL regression)**;
  `mixed/courtlistener-200` (legal) **0.6054 → 0.5893 (−2.7%)**.
- **Honest limitation (important):** the feature is a **net win only for paraphrase/semantic queries** and a
  **net loss (~2–3%) on keyword/entity-heavy corpora — including personal email**, which (F-003) is the
  BM25-dominant shape of JustSearch's *primary* use case (personal files). So it **hurts the product's core corpus
  type** when on; **default-off is necessary and default-on is not recommended** without removing the regression.
  (An earlier single-build A/B mis-reported enron-qa as "neutral −0.5%" — confounded by ~0.8–2.4% embedding-rebuild
  noise; the shared-index measurement is the correction. A dense score-*gap* refinement was measurement-rejected.)
- **Revisit when:** removing the BM25-dominant regression needs a signal that tells *dense-found-the-answer* from
  *dense-confidently-wrong* — which available fusion-site signals (BM25 flatness, dense-top-absent-from-BM25, maxIdf)
  **cannot** do (all key on leg disagreement, not on which leg is right). This is an **open research problem**
  (label-bearing / learned signal), not a threshold tweak — the gate is **not** to be curve-fit further. The
  CE-confidence gate (cross-process) + the recall-stage embedding-seam (the deep buried-signal case) remain future.
- **The actual goal stays in 636 (now narrowed by evidence):** D-004 is a *paraphrase* lever and **regresses
  personal files**, so it does NOT serve the buried-signal-in-personal-files use case. A **direction investigation**
  (636 §Direction investigation, 2026-06-23) then challenged the presumed successor (Design v1, the embedding seam):
  the chunk-dense path **already fires** and whole-doc dense **already retrieves** the buried fact (`vector` 0.82),
  so the measured bottleneck is **fusion, not chunk-vector quality** — Design v1 targets a non-bottleneck. Net: the
  buried-signal-via-dense problem is **real but narrow and fusion-shaped** at measurable scale; the very-long-doc
  regime is untested and would need its **own eval first** before any seam. Do not build Design v1 speculatively,
  and do not curve-fit this fusion gate further.
- **Correctly-aimed successor → 636 Design v3 (CE-arbitrated rerank pool):** the demonstrated defect is that the
  cross-encoder reranks a **fusion-ranked** prefix (`KnowledgeSearchEngine.java:288-291`), so a correct dense answer
  fusion buries never reaches the relevance model (CE present yet `hybrid` 0.24 ≪ `vector` 0.82). Fix = feed the CE
  the **union of each leg's top-N** (recall-complete per leg) and let it arbitrate per-candidate — **keyword-neutral
  by construction** (never down-weights a leg), so unlike D-004 it can be default-on, and it is **eval-testable on
  existing corpora** (needle recovery + enron/courtlistener no-regression). Principle: *"fusion is a ranking step,
  not a recall gate."* This is the build-worthy remaining work, not Design v1 or a richer D-004 heuristic.

### D-005: Regime-blind engine development — capability over corpus-fit, intelligence in the judge not a router

- **Choice (two standing rules + the design stance they imply, user decision 2026-06-24):** Because JustSearch
  has **no users yet**, all further engine work obeys: **(1) do not reason about the types of corpus or queries
  users *might* run, and (2) do not design code around such an assumption.** "Improve the engine" therefore means
  improving **capability for *any* workload**, not raising nDCG on a *presumed* one. Capability is defined as
  three corpus-agnostic things — **guarantees + leak-freeness + component quality**: (a) stronger
  invariants/guarantees true by construction for every corpus; (b) fewer **leaks** (a correct candidate silently
  dropped by a weaker stage before a stronger one can judge it); (c) strictly-better fixed components (encoder /
  reranker / extractor). The architectural stance that follows is the **funnel-and-judge invariant**: *keep the
  upstream funnel dumb, broad, and lossless; put the intelligence in fixed strong judges (the cross-encoder, the
  LLM-as-judge) and in the legs' representation/extraction quality; make every truncation judge-aligned and
  **auditable**; spend the "cleverness budget" on the judge and the legs, **never on a per-corpus router.*** Its
  observability half is a distinct, reusable principle: **a funnel must be observable by recall-survival, not
  just cardinality** — every candidate-dropping stage must be accountable for whether it dropped a *correct*
  candidate (the engine already observes the *count* funnel via `TraceStage.cardinality`; the *recall* funnel is
  the gap 636's Staged Recall Accounting fills).
- **Rationale:** Speculating about an unknown workload is unfounded and bakes a guess into the code that O(forever)
  maintenance and silent mis-fit must carry; it is the retrieval-quality form of the engine's existing
  "verify, don't guess". A fixed strong judge over a broad lossless funnel needs **no per-corpus tuning**, so it
  cannot mis-fit a corpus we did not foresee; a learned/heuristic *combiner* or *router* (F-021, FW-001) can and
  does. Measuring **leaks and guarantees** (not a corpus's headline score) is the only honest definition of
  "better" when the workload is undefined.
- **Enforcement:** **prose-tier (design discipline), not a CI gate** — unlike D-003 there is no single mechanical
  predicate that catches a violation, so this is reviewer/agent judgment at design time. The *partial*
  mechanization is **BUILT** (636 §IMPLEMENTED, 2026-06-24): the **Staged Recall Accounting** projection
  (`jseval/projections/staged_recall_accounting.py`, auto-run at end-of-run) decomposes every judged query into
  leg-recall / cascade-leak / judge-rank, and the **`jseval leak-gate`** ratchet (`jseval/leak_gate.py`,
  mirroring `relevance_gate.py`) fails a build when a corpus's pinned `leak_rate` ceiling is exceeded — making a
  *newly-introduced leak* fail loudly. (A focused gate over the cross-mode projection, not the per-mode
  cohort-envelope/nDCG-locked ratchet — confidence-pass finding.) The instrument is an **eval projection of the
  run artifacts** (the 553 §1 projection class); it stays eval-only (recall-survival needs qrels) — never a
  parallel production record. **Measurement caveat
  (literature-backed, 636 §External research pass):** automated / LLM-generated relevance judgments are reliable
  only for *coarse* recall/presence trends, not for fine top-system discrimination or significance-stability
  ([arXiv 2411.13212](https://arxiv.org/pdf/2411.13212)) — so the robust no-users signal is **recall-survival**,
  not graded nDCG on auto-labeled corpora; keep curated human qrels as the ship-gate and treat any LLM-generated
  qrels on new corpora as trend-only. (The leak class itself is the literature's **"bounded recall problem"**,
  [arXiv 2501.09186](https://arxiv.org/abs/2501.09186) — conform to that term.)
- **Closes / implies:** the **corpus/query-adaptive router** ("Item-1", the FW-001 successor) is **retired as a
  forward direction** — it is, by definition, code that detects the regime and routes (forbidden by rule 2);
  FW-001's `CorpusProfile`/`isLongCorpus` regime switch stays **won't-do** as a *router* (it remains a dangling
  zero-consumer seam). The "regime-matched levers" framing (636 v4) and the "weight the *real* corpus first"
  ship-rule are superseded: **treat every eval as a capability measure, privilege none as "the use case."**
  Distinguishes the **forbidden** (adaptivity keyed on an *assumption about the user's corpus*) from the
  **allowed** (a fixed rule reacting to *runtime signals from the actual query + its own results* — e.g. D-004's
  per-query BM25-incoherence gate, which assumes nothing about the corpus).
- **Revisit when:** real usage data exists (then a *measured* workload, not a guess, may inform tuning); or the
  Staged Recall Accounting profile *proves* a second leak whose runtime localization earns the deferred
  general recall-funnel structure (candidate scope: RAG context-budget, the agent citation funnel
  (`AgentCitationResolver`, harvest-not-build), the runtime truncation sites — **recorded, not built**).
- **Evidence / source:** tempdoc 636 §"New development rules" + §Theorization + §"Long-term design — Staged
  Recall Accounting" + §"Reach & principle" (2026-06-24, user decision). Sibling stance to **D-003** (a named
  engine-development invariant); conforms to the **549/553** SearchTrace-projection seam and the **F-021** /
  **D-004** lessons (intelligence in a *combiner/router* loses; intelligence in a *judge* + better *legs* wins).

---

## Open Questions

Unanswered questions that need investigation. Agents should prefer
picking up items here over inventing new experiments.

### Q-001: Why does CE hurt on personal email?

- **Question:** What is the mechanism by which cross-encoder reranking degrades nDCG on EnronQA by ~2%?
- **Why it matters:** If understood, we could gate CE off for corpus types where it hurts, improving quality automatically.
- **Prior art:** F-002 measured the effect. No per-query analysis yet.
- **Suggested approach:** Per-query failure analysis on EnronQA `full` vs `bm25_splade` — identify which queries CE helps vs hurts, categorize by query type.

### Q-002: Does BM25 dominance hold on short navigational queries?

- **Question:** EnronQA uses verbose QA questions. Would BM25 still dominate with realistic 2-5 word search queries ("Ameren termination", "budget email John")?
- **Why it matters:** If short queries shift the balance toward semantic retrieval, the corpus-adaptive mode selection strategy changes.
- **Prior art:** No short-query eval exists for EnronQA.
- **Suggested approach:** Use the local LLM to rephrase 50-100 EnronQA questions into short navigational queries. Re-run eval.

### Q-003: What is JustSearch's ingestion quality tax? → ANSWERED → F-009

### Q-004: Does locale-aware BM25 improve multilingual retrieval? → WON'T-DO → D-003

- **Disposition (2026-06-15, D-003 / ADR-0043 / tempdoc 581):** **WON'T-DO.** Locale-aware BM25 routing means a per-language analyzer field (`content_de` with German-specific analysis) — exactly the per-language maintenance the language-diversity invariant (D-003) forbids (bucket A). The earlier "cheap win" framing was wrong under this stance: making it a real win requires *authoring* per-language analysis. The inert `content_{en,de}` scaffolding was removed in the 581 §13 collapse, and the `language-agnostic-analysis` gate now forbids reintroducing it. Multilingual gains come from the multilingual model stack (F-007; MIRACL de/fr/zh through one uniform pipeline), not per-language routing.

### Q-005: EmbeddingGemma-300M quality baselines needed → ANSWERED → F-012, F-013

- **Answered (343):** SciFact, EnronQA, and MIRACL/de baselines measured with splade-v3+gemma, then re-baselined with full multilingual model stack (Phase D). F-012 corrected (dense was working). CE measured via `--ce` flag. All 5 model swaps validated and shipped. Phase D baselines are the current production baseline.

### Q-006: Does chunk merge help or hurt overall quality? → ANSWERED → F-014

### Q-007: Sufficiency calibration dataset needed

- **Question:** What is the precision/recall of the `context_sufficient` classifier? The prompt was tuned by flipping rule 5 ("when uncertain, respond false" → "when uncertain, respond true") but no labeled answerability dataset exists to measure false positive/negative rates.
- **Why it matters:** If the model says "sufficient" incorrectly, agents stop searching too early. If it says "insufficient" incorrectly, agents waste turns on unnecessary refinement.
- **Prior art:** Google ICLR 2025 "Sufficient Context" (arXiv 2411.06037) achieved 93% accuracy, 0.94 F1 with 115 human-labeled examples. Tempdoc 366 Phase 6 reverted the prompt flip due to unknown false positive rate.
- **Suggested approach:** Build labeled dataset from 50q eval: (query, context) → answerable? Measure classifier precision/recall before adjusting prompt.

### Q-008: What fraction of real JustSearch user queries contain extractable filters?

- **Question:** Estimate is 40–65%, but no empirical data exists. The available query sets are either synthetic benchmarks (MultiHop-RAG: 100% filterable, BEIR: ~0% filterable) or illustrative examples.
- **Why it matters:** Determines the real-world impact ceiling of query understanding. At 40%, QU fires on nearly half of queries with +15–29% retrieval precision (literature). At 10%, the feature is marginally useful.
- **Prior art:** tempdoc 363 §Query distribution analysis. `meta_source` is the most common extractable field, followed by date/temporal, then person entities.
- **Suggested approach:** Collect and analyze real user queries once usage data is available.

### Q-009: Is there a validated, user-facing retrieval-confidence calibration?

- **Question:** The RAG `QualitySignals` (`best_chunk_score`, `score_gap`) are emitted to the FE but were unused. `computeQualitySignals` (`RagContextOps`) sets them to either raw cross-encoder scores OR raw BM25/fusion scores — scheme-dependent and unbounded. Can these (or another signal) be normalized into a validated confidence a user can read ("how well-supported is this answer")?
- **Why it matters:** The 561 answer-plane wants a claim-level calibration ("what the sources do/don't support"). Presenting an uncalibrated raw score as a "%" repeats the live-audit's "unlabeled 100%" anti-pattern and misleads.
- **Prior art:** FW-009 (citation-scorer 0.5 threshold unvalidated), Q-007 (sufficiency classifier precision/recall unmeasured), F-019 (QPP cannot separate null from answerable). All point to "no validated user-facing confidence exists yet."
- **Status (561 P-A4):** surfaced the signals only as an explicitly RELATIVE, UNCALIBRATED transparency tooltip (`retrievalSignals.ts`) — deliberately NOT a confidence verdict — pending this validation. A validated calibration would be a producer-owned field (the Worker owns the score scheme), not an FE re-derivation.
- **Suggested approach:** Build a small labeled (query, context, answer-supported?) set as in Q-007; measure whether `best_chunk_score`/`score_gap` (CE branch only) separate well-grounded from weak answers before exposing any absolute confidence.

### Q-010: Should the engine have a relevance ratchet to match the presentation gates?

- **Question:** Presentation (`ui-web`) is continuously serviced because every edit trips a discipline gate; relevance quality is gated only by an opt-in `jseval` run a human must remember. Should an engine-edit-triggered (or nightly) `jseval gate` fail the build when nDCG@10 drops beyond tolerance vs a pinned baseline, giving retrieval the same continuous-servicing pressure the UI has?
- **Why it matters:** Under attention scarcity the gated surface crowds out the ungated one. Tempdoc 580 §1 measured the result: ~46k lines of presentation+governance churn over a window in which the retrieval engine moved 0 lines, baselines unrevalidated since 2026-04-19. A relevance ratchet would make silent stagnation/regression *fail loudly* instead of coasting invisibly.
- **Prior art:** `jseval gate` + `calibrate-drift-baseline` already exist (tempdoc 400 LR4-g) but are manual-CI-only; the cohort envelope (`envelope.json`, ±2σ) already separates signal from noise. The missing piece is wiring, baseline-pinning, and the asymmetry argument — not new measurement tech.
- **Status:** Named in tempdoc 580 §4c; deliberately NOT built. **§4a (2026-06-13) resolved the trigger negatively** — HEAD hybrid nDCG@10=0.758 is on-baseline (vs 0.754), no silent regression found, so there is no proof-by-example endorsement. Q-010 now rests only on the stagnation+asymmetry argument; awaits a user decision rather than self-endorsing.
- **Partially operationalized (2026-06-24, 636 §IMPLEMENTED / D-005):** the nDCG-mean ratchet question is now *complemented* by a recall-survival ratchet — **`jseval leak-gate`** fails a build when a corpus's pinned `leak_rate` ceiling (from the Staged Recall Accounting projection) is exceeded. It is the engine-quality "fail loudly" gate Q-010 asked for, on a **leak** metric rather than nDCG mean (and on the cross-mode projection, not the per-mode envelope). Pinning per-corpus ceilings is the deliberate governance step that still awaits a user decision (like the nDCG ratchet — un-pinned corpora do not gate).
- **Suggested approach:** Pin a per-corpus baseline from a green HEAD run; add `jseval gate` to the engine-module-edit path (PostToolUse hint or a discipline-gate kernel rule); tolerance from the cohort envelope.

### Q-011: Does the production hybrid (chunk-passage) path also collapse on buried-signal at scale, and should paraphrase queries route away from lexical+CE?

*(needle-burial-v1 evidence below predates the 2026-07-01 corpus regeneration — see Corpus provenance note
above)*

- **Question:** Two sub-questions opened by F-023's buried-signal eval: **(a)** jseval `hybrid` shows `chunkMergeApplied=null` (chunk branch not applying) and collapses, but the live interactive probe showed production hybrid *does* fire the chunk branch and ranks a needle well — so **does the *production* default path actually degrade on buried-signal at scale, or does the chunk-passage path hold?** The eval must be made to **isolate/exercise the chunk-dense path** before this is answerable (and before tempdoc 636's chunk-embedding seam P1a/P2 can be gated). **(b)** On grep-defeating paraphrase queries, `vector ≫ hybrid` (the lexical+CE legs *suppress* dense): should the engine **route toward dense / down-weight lexical+CE when a query is lexically poor against the corpus** (an FW-001 / low-signal-gating-in-reverse lever)?
- **Why it matters:** (a) gates whether tempdoc 636's embedding seam is even the right fix (the Phase-1 eval measured the whole-doc vector, not the chunk vector the seam improves). (b) is plausibly the **higher-impact** lever for buried/paraphrase retrieval — the largest gap in the 636 experiment (0.82 vs 0.32) was fusion/routing, not embedding context.
- **Prior art:** tempdoc 636 §Phase-1 eval + §Pre-impl pass (F-023); FW-001 (corpus/query-adaptive recipe, superseded as a binary switch but live as a general policy); low-signal gating (`HybridSearchOps`, caps vector on *weak* dense — here the opposite case).
- **Suggested approach:** First reconcile jseval-`hybrid` vs production-`hybrid` (why `chunkMergeApplied=null` under jseval — a preset/`chunkAware` gap or a corpus-profile gate?); add a chunk-dense-isolating eval mode; then A/B a paraphrase-aware routing/weight policy on `golden/needle-burial-v1`.
- **Disposition (2026-06-23):** **(a) RESOLVED as a reporting artifact** — `chunkMergeApplied=null` was jseval reading a *retired* response field (`artifacts.py`), not the chunk branch being off; jseval `hybrid` *does* exercise the production path, which genuinely degrades on paraphrase. **(b) PARTIALLY ANSWERED → D-004** — a paraphrase-aware policy was built (per-query adaptive `ccAlpha` + BM25-incoherence) and **rigorously validated**: +195% on the paraphrase target and neutral on academic, **but a real −2.1% / −2.7% regression on the BM25-dominant corpora (personal email / legal)** — so it ships **default-off** as a specialized opt-in lever, *not* a universal quality win (D-004 honest limitation). Removing the BM25-dominant regression is an **open research problem** (no available signal separates dense-right from dense-confidently-wrong), not a threshold tweak — that, plus the recall-stage embedding-seam (the deep buried-signal case the title implies but this fusion fix does not solve), remain open.
- **(a) chunk-dense/leg isolation now MEASURABLE (2026-06-24, 636 §IMPLEMENTED):** the Staged Recall Accounting projection reports **per-leg union recall** (`vector`/`lexical`/`splade` isolated) + the cascade-leak share, so "did a leg find the buried fact, and did the fused/final path keep it?" is now a standing per-run measurement rather than a one-off reconstruction. On `needle-burial-v1` (production default, both levers): leg-union recall **1.0**, final_recall **0.90**, cascade-leak **2/20** — i.e. the legs find the needle every time; the shipped levers cut the fusion leak from 11/20 (CE-off) to 2/20.

### Q-012: Should the engine have a performance ratchet (latency/throughput/footprint) to match the relevance ratchet?

- **Question:** Q-010 gave *relevance* a "fail loudly" guard; *performance* (query latency, indexing throughput, resident footprint) is measured on every eval run but un-ratcheted — a latency or footprint regression coasts invisibly the same way relevance did (the same enforcement asymmetry, on the perf axis). Should an engine-edit-triggered `jseval` gate fail when a perf metric regresses past a pinned baseline?
- **Why it matters:** The cross-encoder is ~82% of query latency (tempdoc 640 §C-2) and the default-on 636 levers feed its candidate pool, so a latency regression there is plausible *and currently unguarded*. For a local-first desktop product latency/footprint are co-equal with relevance.
- **Prior art:** `relevance_gate.py` (the mirrored gate pattern), `diff_gate.compare_ratio` (the lower/higher-is-better ratio primitive), `calibrate.py` (the within-machine envelope that measured the perf-metric CVs).
- **Status — IMPLEMENTED (2026-06-24, tempdoc 640):** shipped as **`jseval perf-gate`**, the perf-metric-family sibling of `relevance-gate`. A **relative** ratchet (ratio bands via `diff_gate.compare_ratio`, **no absolute SLO** — the no-users rule). Gate-able metrics, chosen by their measured within-machine CV (640 §confidence pass): **cross-encoder STAGE p50** latency (CV 1–10%; the dominant cost), **primary + enrichment throughput**, and **resident model footprint incl. the LLM** (best-effort — reads the active gguf named in the captured non-hashed `inference_status_snapshot`; ONNX-only on AI-offline runs). Deliberately **excluded as too noisy**: total latency p50 (CV 35–112%, cold-start), `index_size_bytes` (CV 11–62%).
- **Now a first-class metric family in the canonical record** (a `metric_families` registry — the single source of truth; per-mode CE latency in `aggregate_metrics`, per-run throughput/footprint in `run_metrics`), so the floor **projects from the canonical release** (`perf_gate.project_release_to_perf_baselines`), closing the per-run fork the v1 baseline had. The noise floor is **envelope-aware** (a data-driven `1±k·CV` band from the `calibrate` envelope, with a graceful fixed-band fallback), perf is **trended** in the history DB (`jseval trend --metric`, direction-aware), and rendered in the published benchmark + register scorecard. **Source-class distinction:** per-mode and per-run families live *in* the record; **leak** (Q/D-005) is a cross-mode **projection** metric — registered in the same registry to unify the family concept, but kept projection-sourced, *not* migrated. **Advisory tier:** the `search-engine-hint` hook nudges it (with relevance + leak), not CI-blocking — inherits the relevance ratchet's tier. Conforms to the canonical-record + governed-projection seam (623).
- **Reach — now BUILT (2026-06-24, tempdoc 640 reach + residuals):** the former reach shipped. (a) The per-run **fork is fully closed** — `release.v1.json` recomposed from a 5-corpus cohort (`scifact` + `courtlistener-200` + `enron-qa` + `miracl-de-2k` + `miracl-fr-2k`) at one commit; the perf baseline is now a `current_release` pointer, so floors project from the same canonical release relevance uses. (b) The **shared ratchet kernel** (`jseval/ratchet_kernel.py`) unifies the relevance / perf / leak / llm-gen gate orchestration. (c) The combined **engine-quality scorecard** (`scripts/docs/gen-scorecard.mjs` → `docs/reference/benchmarks/scorecard.md`) co-locates all axes as one delta-vs-guard table. (d) The **LLM-generation-latency** sibling axis shipped as a `bench`-sourced `llm-gen` family + `jseval llm-gate` (TTFT / e2e / **tokens-sec**, the last now captured — see the inference-runtime register's llm-gen finding) — the inference-path subject, nudged by `search-engine-hint`. **Reconciled (realized vs designed):** footprint is the **resident-during-eval** metric (ONNX during retrieval eval; configured-stack-incl-LLM deferred); the noise floor is the **fixed ratio band + envelope fallback** (the measured CE-stage CV superseded "median ± envelope"). **Still deferred:** 625's *generalized* projection-provenance framework (its own tempdoc).

### Q-013: Candidate-set integrity (639) — extend Staged Recall Accounting, or fork a parallel recall instrument?

- **Question:** Tempdoc 639 (candidate-set integrity — ANN recall at scale + near-duplicate collapse), a stub spawned by 636's coverage analysis, will need to *measure* candidate-set completeness (did retrieval return the relevant docs) and non-redundancy. Should that measurement **extend** 636's **Staged Recall Accounting** — whose `leg-recall` layer is already "did each leg surface the gold doc", a governed projection of the run artifacts with a self-reconciliation oracle — or build a **separate** recall instrument?
- **Why it matters:** A parallel recall instrument is the exact one-authority **fork** that 553 (one canonical record; every surface a governed projection) and 636 §Reach (the *layer-invariant* observe-by-survival / one-canonical-authority principle) warn against — two un-coordinated answers to "did retrieval keep the right doc", guaranteed to drift. ANN-recall is a *refinement* of leg-recall (it asks whether the ANN index returned the true neighbours a leg *should* have surfaced), so it composes as a sub-measure of the same projection rather than a rival.
- **Recommendation (636 §Adjacent-work-coordination, not yet a decision):** 639's design should **extend** `staged_recall_accounting` (a per-leg ANN-recall sub-measure + a dedup/redundancy measure over the same returned set), reusing the projection + reconciliation seam; 636's dropped `ann_proof FAIL` comparability flag is the natural input. **Status:** 639 is a no-implementation stub — flagged here so its design phase conforms rather than forks.
- **Coupling with 643 found during the 643 investigation (2026-07-01):** the "symmetric siblings" framing (639 = candidate-set, 643 = judge) under-states a real coupling — a doc that out-ranks the gold in the `JUDGE_RANK_LOW` bucket is often a **near-duplicate distractor**, which is 639's dedup half, not a judge defect. 639's design should attribute how much of `judge_low` is near-dup-driven (→ fixed by 639's dedup, for free) vs genuine mis-rank (→ 643's territory) before either stub commits further design effort on an assumed split.

### Q-014: Does any procedurally-generated `golden/` corpus clear the descriptor-collision gate, and is any of them suitable for an agent-utility (not just retrieval-quality) measurement?

- **Question:** 664's twelfth pass measured the descriptor-collision check **FAILING on all 5 procedurally-generated corpora** (`needle-burial-v1`, `synth-tabular-v1`, `synth-multiling-de-v1`, `synth-multihop-prose-v2`, `synth-code-v1`; 17–27 colliding groups each, `needle-burial-v1` specifically has 7 of its 20 gold chains affected) and left the fix out of scope ("a deeper generator-logic change"). For a retrieval-quality profile this is a tolerable caveat; for an **agent-utility accuracy** measurement (tempdoc 624) it directly corrupts the paired metric (a hostile reviewer can point to a distractor indistinguishable from a "gold" query). Also open: 635's own suite found 3 of its 4 members (code/tabular/German) are **grep-trivial by construction** (verbatim entity-name queries) and unusable for an agent-utility delta at any scale.
- **Why it matters:** Tempdoc 624's 2026-07-02 methodology plan (§M.2/§M.3) promotes the collision fix from "known, deferred" to a **blocking prerequisite** for any future agent-utility spend, and separately calls for a **real-scale** (hundreds–low-thousands of docs) + **OCR-only-accessible** battlefield the current suite doesn't have at all — see 624's own §U0 framing (does the realistic file-tools-plus-JustSearch arm's near-null effect survive a harder, more representative battlefield, or does a small/clean/easy corpus just hide a genuine null).
- **Status:** Not resolved here — flagged so a future corpus-generator or agent-eval pass doesn't have to re-derive it. See tempdoc 624 §Methodology plan (2026-07-02) for the full design; tempdoc 664 for the collision measurement; tempdoc 635 for the grep-trivial-member finding.

---

## Future Work

Identified improvements not yet started. Lower priority than Open
Questions — these are "we should eventually" not "we need to know."

- **FW-001: Corpus-adaptive mode selection** — Gate CE and select CC weights based on CorpusProfile regime (email→skip CE, academic→full pipeline). Source: tempdoc 309 §43. **SUPERSEDED (user decision, 580 §10, 2026-06-13):** premise validated (optimal recipe flips by corpus — 580 §9.3) but the binary `isLongCorpus()` switch is too crude; the target is a general recipe-weight policy, not a two-bucket lookup. `isLongCorpus()` remains a dangling seam (zero production consumers, verified 2026-06-15).
- **FW-002: Pre-retrieval spell correction** — DirectSpellChecker for typo queries (~100 lines). Source: tempdoc 260 Gap 1. **Still unbuilt (verified 2026-06-15:** no `DirectSpellChecker`/`SpellChecker` in code); only the post-retrieval zero-hit fuzzy retry exists.
- **FW-003: EnronQA per-query failure analysis** — 22 R@10=0 failures (net unchanged from 309). Of original 22: 14 recovered (title boost fix + chunk merge), 8 persistent (verbose query dilution). 14 new failures from chunk merge regressions (`chunkMergeApplied=True` on all). CE adds 12 more failures when enabled (16 killed, 4 recovered). Model swaps are zero-impact on EnronQA. Actionable: FW-001 (CE gating), chunk merge tuning, query reduction. Source: tempdoc 326 Phase D reanalysis (2026-03-28).
- **FW-004: Short navigational query eval** — Rephrase EnronQA verbose questions to realistic 2-5 word queries. Source: Q-002.
- **FW-005: Tika-specific ingestion tax** — ~~Answered.~~ Tika structured extraction on OHR-Bench PDFs: -16.2% nDCG. Comparable to GOT pre-extracted (-14.7%). **VLM extraction via existing chat model (Qwen 3.5) is the chosen path. Docling integration cancelled.** Source: tempdoc 252 verification (2026-03-20), F-009 updated recommendation.
- **FW-006: English stemming evaluation** — **WON'T-DO (D-003 / ADR-0043 / tempdoc 581).** A per-language (English) stemmer is a per-language component the language-diversity invariant rejects. Also separately blocked: per tempdoc 223, analyzer-level content stemming breaks the fuzzy zero-hit correction (the analyzed query token diverges in edit distance from the stemmed index term). Distinct from the existing query-side SIMPLE-syntax "stemming" path, which is unaffected.
- **FW-007: Token estimation calibration** — Hybrid char+word heuristic is intentionally conservative but lacks calibration across content types (URLs, code, JSON, minified JS). Source: RAG-002 (retired from issues/).
- **FW-008: Vector quantization cross-machine evidence** — Codec wiring implemented (default off). Needs cross-machine benchmark evidence before enabling by default. Source: RAG-004 (retired from issues/). **Still open (verified 2026-06-15):** default remains Float32 (`JustSearchCodec.java:43`); only storage (~75%) is measured — the **nDCG quality cost of Int8 is unmeasured** (single-machine only, RAG-003/235). **Post-cutoff capability note (580 §14.1, Lucene-10.4.0-verified):** `Lucene104(Hnsw)ScalarQuantizedVectorsFormat` exposes **1/2/4/7/8-bit** + **asymmetric 2-bit-store/4-bit-query** ("2-bit recall-competitive with old 4-bit") — so a lower-bit path than Int8 is config-only (no new dep) but reindex-required and recall is corpus-dependent; an **efficiency** lever (memory), not a quality one — eval-gate recall before adopting.
- **FW-009: Citation scorer threshold calibration** — Default 0.5 threshold works in tests but not validated across real-world content types. Source: RAG-006 (retired from issues/).
- **FW-010: 1M+ vector scale benchmarks** — No runs at 1M+ vectors or cross-machine. Current evidence limited to smaller datasets on single machine. Source: RAG-003 (retired from issues/).

---

<!-- source: docs/explanation/23-search-pipeline-overview.md -->

# 23. Search Pipeline Overview

JustSearch's search pipeline spans two processes (Head and Body) and is
split into ingestion-time (offline, index-building) and query-time (online,
search-serving) stages. This document traces the full path end-to-end.

> **Decision register:** For settled findings, canonical baselines, and open questions about search quality, see `docs/reference/search-quality-register.md`.

For subsystem deep-dives, see:
- [04-storage-engine.md](04-storage-engine.md) — Lucene schema, fields, commit strategy
- [18-adapters-lucene-deep-dive.md](18-adapters-lucene-deep-dive.md) — BM25, HNSW, hybrid fusion internals
- [05-ai-architecture.md](05-ai-architecture.md) — Embedding backend, VRAM management
- [03-knowledge-server.md](03-knowledge-server.md) — Indexing loop, Tika extraction, job queue

---

## How a Search Request Works

When a user types a query, the default `hybrid` preset activates BM25 +
Dense KNN retrieval (with optional SPLADE), fused via CC (convex
combination). The pipeline executes across two processes:

1. **Head** (Main process, `KnowledgeHttpApiAdapter`) resolves the
   `PipelineConfig` from a named preset or explicit flags, and optionally
   starts an async LLM expansion call. It then sends a gRPC request to the
   Worker.

2. **Worker** (Body process, `SearchOrchestrator`) runs retrieval. The
   enabled legs (BM25, Dense KNN, SPLADE) execute in parallel via virtual
   threads. Their results are fused (RRF by default). If the query yields
   zero hits, fuzzy correction retries. If chunks exist, a parallel chunk
   search is fused and collapsed by parent document. Match spans, excerpt
   regions, and facets are computed. The response flows back over gRPC.

3. **Head** (post-retrieval) merges any completed LLM expansion, then runs
   a reranking cascade: LambdaMART (fast, ~5 ms) followed by cross-encoder
   (deep, 200–500 ms). Results are trimmed to the requested limit and
   per-hit provenance metadata is assembled.

The diagram below shows the complete flow. The three retrieval legs fan out
in parallel from the dispatch stage. Dashed lines indicate the cross-process
gRPC boundary.

![Search Pipeline Overview](23-search-pipeline-overview.svg)

---

## Pipeline Configuration

Pipeline behavior is controlled by `PipelineConfig` — a set of independent
boolean flags that replaced the legacy `SearchMode` enum.

Each flag independently enables a pipeline component. Named **presets**
provide backwards-compatible aliases:

| Preset     | `sparse` | `dense` | `splade` | `expansion` | `crossEncoder` | Notes                                            |
| ---------- | -------- | ------- | -------- | ----------- | -------------- | ------------------------------------------------ |
| **text**   | ✓        | —       | —        | ✓           | ✓*             | Sort, cursor, facets, fuzzy correction available |
| **hybrid** | ✓        | ✓       | opt      | —           | ✓*             | Default for interactive search                   |
| **vector** | —        | ✓       | —        | —           | ✓*             | Pure semantic similarity                         |
| **splade** | —        | —       | ✓        | ✓           | ✓*             | Learned sparse retrieval                         |

\* Cross-encoder and LambdaMART are enabled when their models are loaded.

Custom `PipelineConfig` objects can combine flags freely — e.g.,
`{sparse, splade}` or `{dense, splade}` produce valid results through
composable dispatch.

### TEXT-Only Features

Sort modes, cursor pagination, facets, and fuzzy correction require Lucene's
`Query`-based collector API. They are available only when sparse retrieval
is the **sole** active leg. This is a Lucene API constraint, not a mode gate.

---

## Ingestion-Time Stages (Body Process)

These stages run in the Worker (`indexer-worker`). They build the index that
query-time stages search against.

| #   | Stage                    | Owner                          | What It Does                                                                                  |
| --- | ------------------------ | ------------------------------ | --------------------------------------------------------------------------------------------- |
| 1   | **File Discovery**       | `IndexingLoop`                 | Polls job queue, checks timestamps, respects breath holding                                   |
| 2   | **Content Extraction**   | `StructuredContentExtractor`   | Uses `AutoDetectParser.parse()` + `StructuredContentHandler`; preserves headings, tables, page breaks from 1,400+ formats; sandboxed with timeout + memory guards. PDFs with low quality scores (< 0.3) are routed to VLM extraction via the Brain process when `JUSTSEARCH_LAYOUT_ENABLED=true` — see [ADR-0018](../decisions/0018-vlm-pdf-extraction-via-chat-model.md) |
| 3   | **Text Analysis**        | `SsotAnalyzerRegistry`         | `ICUTokenizer → NFC → LowerCase` — locale-invariant, no per-language analyzer ([ADR-0043](../decisions/0043-multilingual-by-construction-no-per-language-levers.md))  |
| 4   | **Chunking**             | `ChunkDocumentWriter`          | Splits docs >2,000 chars into 500-token chunks (50-token overlap); linked via `parent_doc_id` |
| 5   | **BM25 Indexing**        | `FieldMapper` / `WritePathOps` | `content` as analyzed text; `content_preview` (first ~4 KB) for snippets                      |
| 6   | **Dense Embedding**      | `EmbeddingService` (llama.cpp) | gte-multilingual-base, 768-dim; `vector` (whole-doc) + `chunk_vector` (per-chunk)             |
| 7   | **SPLADE Encoding**      | `SpladeEncoder`                | opensearch-neural-sparse-encoding-multilingual-v1 (12L BERT-multilingual, 105K vocab) → Lucene `FeatureField` entries |
| 8a  | **NER Backfill**         | `NerBackfillOps`               | Writes `entity_persons_raw`, `entity_organizations_raw`, `entity_locations_raw` (keyword) and `entity_persons_text`, `entity_organizations_text`, `entity_locations_text` (ICU-analyzed) fields (326) |
| 8   | **HNSW Vector Indexing** | `JustSearchCodec`              | M=16, efConstruction=200; Int8 quantization optional (~75% storage reduction)                 |
| 9   | **Commit**               | `CommitOps`                    | On time (>10 s), size (>1,000 docs), or shutdown; NRT refresh                                 |

---

## Query-Time Stages

### Retrieval Legs

Three retrieval models can run in any combination:

| Leg                         | Model / Engine                  | Index Field                 | Key Parameters                                  |
| --------------------------- | ------------------------------- | --------------------------- | ----------------------------------------------- |
| **BM25** (sparse)           | Lucene BM25                     | `content` / `chunk_content` | k1=0.9, b=0.4; SIMPLE prefix expansion ≥3 chars; `combineMultiField()` builds `DisjunctionMaxQuery` with up to 6 disjuncts: `content` + `title`×3.0 + 3 entity text fields×2.0 (326). Entity boost configurable via `ResolvedConfig.Search.entityBoost()`, default 0.0 (disabled per F-010) |
| **Dense** (KNN)             | gte-multilingual-base (768-dim)                                          | `vector` / `chunk_vector`   | ef_search=100; HNSW M=16                        |
| **SPLADE** (learned sparse) | opensearch-neural-sparse-encoding-multilingual-v1 (12L BERT-multilingual, 105K vocab) | `FeatureField` entries      | Optional IDF-weighted query encoding            |

### Pre-Retrieval (Head — `KnowledgeHttpApiAdapter`)

| #   | Stage                           | What It Does                                                                                                                                                |
| --- | ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **Pipeline Config Resolution**  | Expands preset or accepts explicit `PipelineConfig` with flags: `sparseEnabled`, `denseEnabled`, `spladeEnabled`, `expansionEnabled`, `crossEncoderEnabled` |
| 2   | **LLM Query Expansion** (async) | 1,500 ms budget; morphological variants; gated by `expansionEnabled` flag (true for text/splade presets); falls back to base results on timeout             |
| 2a  | **Filter Value Normalization** (async) | Two-tier: deterministic prefix/contains matching (0 ms); LLM grammar-constrained enum fallback (~400–1200 ms GPU). Gated by `JUSTSEARCH_FILTER_NORM_ENABLED`. Fires on both search and answer paths (366) |
| 2b  | **Query Understanding** (async) | LLM extracts `boostFilters` from natural language queries; applied as `BooleanClause.SHOULD` + `BoostQuery(ConstantScoreQuery, weight=20)`. Gated by `JUSTSEARCH_QU_ENABLED`. Bypassed when explicit filters present (363) |

### Retrieval (Worker — `SearchOrchestrator`)

Stages 3–8 are **not sequential** — stages 6, 7, and 8 execute in parallel
via virtual threads, then converge at the fusion stage.

| #   | Stage                                 | What It Does                                                                                                                                                        |
| --- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 3   | **QPP Computation**                   | `maxIdf`, `avgIctf`, `queryScope` per query term; O(1) via IndexReader; forwarded but not yet used for routing                                                      |
| 4   | **Filter Parsing + Entity Expansion** | gRPC filters → Lucene queries; entity facet filters expanded via disambiguation cluster snapshot                                                                    |
| 5   | **Staged Retrieval Dispatch**         | Dispatches to enabled legs; standard combos use optimized methods (`searchHybrid`, `searchHybridSplade`); novel combos use pairwise RRF fusion via `fuseLegs()`     |
| 6   | **BM25 Search** ‖                     | Lucene `Query`-based retrieval; fetches 3× limit for over-retrieval                                                                                                 |
| 7   | **Dense KNN Search** ‖                | `KnnFloatVectorQuery`; fetches 2× limit; pre-filtered by runtime filters                                                                                            |
| 8   | **SPLADE Search** ‖                   | `FeatureField` query with learned sparse weights                                                                                                                    |
| 9   | **CC / RRF Fusion**                   | **CC** (default): min-max normalized convex combination with per-leg weights; **RRF** (alternative): `score = Σ(weight / (K + rank)) + bm25_boost × raw_score` (K=60, vectorWeight=0.75). 3-way variant (`fuseWithCC3`) available when SPLADE is active |
| 10  | **Low-Signal Gating**                 | Caps vector-only results (default 3) when vector top score <0.40; prevents semantic hijack                                                                          |
| 11  | **Stop-Word Short-Circuit**           | Skips vector search for trivial queries (<4 chars or single stop words)                                                                                             |
| 12  | **Fuzzy Correction** (zero-hit retry) | Two-stage: (a) full Levenshtein fuzzy retry, (b) per-term replacement of zero-docFreq terms; only when zero hits on SIMPLE queries                                  |

‖ = parallel execution

### Post-Retrieval (Worker — `SearchOrchestrator`)

Stages 13a–13c implement **two-branch fusion**: a whole-document branch
(stages 6–9 above) and a chunk branch (13a–13b) are independently scored,
collapsed, and then merged in 13c. This replaces the earlier single-pass
RRF chunk merge.

| #    | Stage                                      | What It Does                                                                                                                                                                                                                                                |
| ---- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 13a  | **Chunk Branch Retrieval** (Stage 3a)      | Parallel chunk-level BM25 (`searchChunksText`), KNN (`searchChunkVector`), and SPLADE (`searchChunksSplade`) within `executeChunkBranchFusion`. Budget starts at `limit × CHUNK_INITIAL_CANDIDATE_MULTIPLIER`; retries at higher budget if any leg saturates |
| 13b  | **Chunk 3-Way CC Fusion + Parent Collapse** | `fuseWithCC3` combines the three chunk legs via min-max normalized convex combination. SPLADE weight is modulated by `parent_token_count` (full weight ≤1,024 tokens, zero ≥4,096 tokens — compensates for SPLADE's 256-token truncation). Results are collapsed to parent doc ID: best chunk score wins; evidence debug scores (`chunk_sparse`, `chunk_vector`, `chunk_splade`) aggregate by max across sibling chunks |
| 13c  | **Branch Fusion** (Stage 3b)               | Merges whole-doc branch with collapsed chunk-parent branch. Default strategy is CC (`fuseWithCCNamed`): chunk branch weight is modulated by parent length (short docs trust whole branch, long docs trust chunk branch; `chunkMinMultiplier` default 0.25). Alternative: RRF (`fuseWithRRFNamed`) when `branchFusionStrategy=rrf` |
| 14   | **Match Spans + Excerpts + Facets**        | Character-offset spans for UI highlighting; IDF-weighted excerpt regions (top 3); DocValues facets first page only; entity canonical merge                                                                                                                 |

### Post-Retrieval (Head — `KnowledgeHttpApiAdapter`)

| #   | Stage                                 | What It Does                                                                                                       |
| --- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 15  | **Expansion Merge**                   | If LLM expansion completed in budget, re-searches with expanded query (LUCENE syntax); otherwise uses base results |
| 16  | **LambdaMART Reranking**              | 2 features (sparse + vector debug scores); fast (~5 ms); runs first in cascade. **Off by default** (requires a GPL-trained model). ⚠️ **GPL-trained LambdaMART is measured non-viable on real queries** (synthetic GPL training queries don't transfer) — see register **F-021**. Treat as present-but-inert substrate pending real user-feedback labels, *not* a current quality lever |
| 17  | **Cross-Encoder Reranking**           | gte-multilingual-reranker-base (FP16 GPU, 306M params); Head sends `Rerank` gRPC RPC to Worker with query-focused snippets; deadline-budgeted; runs on LambdaMART's output (360) |
| 18  | **Result Trim + Provenance Assembly** | Trim to requested limit; structured provenance per hit (which legs contributed, fusion scores, CE scores)          |

---

## Alternate Entry Points

The interactive search pipeline above is the primary path. Two other paths
share the same Lucene index but have their own orchestration:

**RAG Retrieval** (`RagContextOps`, `indexer-worker`): Used by AI chat.
Retrieves chunks using BM25 or hybrid (BM25 + vector) search, then
optionally reranks with a cross-encoder (GPU-aware, with deadline and VRAM
arbitration). Applies MMR diversification to avoid redundant passages, then
assembles context within a token budget. Falls back to full-document
retrieval with virtual chunking when no indexed chunks exist. Unlike the
interactive pipeline, RAG retrieval is chunk-first (optimized for passage
extraction) and runs entirely in the Worker process.

**Autocomplete / Suggest** (`SuggestOps`, `adapters-lucene`): Prefix and
infix autocomplete on document titles and content. Builds a disjunctive
BM25 query across title and content fields (title boosted 4×), deduplicates
by filename. Independent of the full search pipeline — no fusion, no
reranking, no chunking.

---

## Degradation and Fallback

The pipeline degrades gracefully when components are unavailable:

| Condition                      | Behavior                  | Signaled via              |
| ------------------------------ | ------------------------- | ------------------------- |
| Dense embeddings unavailable   | HYBRID falls back to TEXT | `hybridFallback` + reason |
| Dense embeddings blocked       | VECTOR returns empty      | `vectorBlocked` + reason  |
| SPLADE encoder absent          | SPLADE leg skipped        | `spladeSkipReason`        |
| Cross-encoder model not loaded | CE step skipped           | `crossEncoderSkipReason`  |
| LambdaMART model not loaded    | LambdaMART step skipped   | `lambdaMartSkipReason`    |
| LLM unavailable                | Expansion skipped         | `expansionSkipReason`     |
| QU unavailable (Brain offline) | QU skipped, no boostFilters applied | `queryUnderstanding` absent from response |
| FilterNormalization timeout    | Fallback to case-only normalization | `filterNormalization.source = "timeout"` |

Every search response includes the unified `searchTrace` (tempdoc 549) — the single
stage-keyed artifact carrying per-stage status (`executed` / `skipped` / `disabled` / `failed`
+ reason) and timing (`ms`), plus query-level `effectiveMode` / `decisionKind` / `qpp` /
`degradation`. It replaced the former `pipelineExecution` / `introspection` / per-hit
`debugScores` / `provenance` representations. See
[Search & RAG Reason Codes](../reference/contracts/search-and-rag-reason-codes.md)
for the full degradation contract.

---

## Constraints

- **Cross-encoder** is eligible for all presets. It runs on the fused
  candidate list regardless of which retrieval legs produced it.
- **LLM Expansion** is gated by the `expansionEnabled` flag. The `hybrid`
  preset defaults to `false` (dense retrieval already provides semantic
  recall), but custom configs can override.
- **LambdaMART + Cross-Encoder** co-execute in a 2-stage cascade.
  LambdaMART runs first (fast), cross-encoder on LambdaMART's top-K (deep).
- **Stemming + Fuzzy** are sequential, never simultaneous, to avoid
  double-counting variant terms.

See [Search Pipeline Invariants](../reference/contracts/search-pipeline-invariants.md)
for the full contract and test references.

---

## Source Code Map

| Component                     | Primary File                   | Module            |
| ----------------------------- | ------------------------------ | ----------------- |
| Search orchestration (Worker) | `SearchOrchestrator.java`      | `indexer-worker`  |
| Head-side adapter + reranking | `KnowledgeHttpApiAdapter.java` | `app-services`    |
| Lucene runtime ops (read/write/lifecycle) | `ReadPathOps` / `WritePathOps` / `RunningRuntime` | `adapters-lucene` |
| Hybrid fusion (RRF + CC)      | `HybridFusionUtils.java`       | `adapters-lucene` |
| BM25 query building           | `TextQueryOps.java`            | `adapters-lucene` |
| Chunk retrieval               | `ChunkSearchOps.java`          | `adapters-lucene` |
| Cross-encoder reranker        | `CrossEncoderReranker.java`    | `reranker`        |
| SPLADE encoder                | `SpladeEncoder.java`           | `indexer-worker`  |
| Dense embedding               | `EmbeddingService.java`        | `indexer-worker`  |
| Field mapping                 | `FieldMapper.java`             | `adapters-lucene` |
| Analyzer pipeline             | `SsotAnalyzerRegistry.java`    | `adapters-lucene` |
| Pipeline presets              | `PipelineConfigs.java`         | `ipc-common`      |
| RAG context retrieval         | `RagContextOps.java`           | `indexer-worker`  |
| Autocomplete / suggest        | `SuggestOps.java`              | `adapters-lucene` |
| Query understanding           | `QueryUnderstandingService.java` | `app-services`  |
| Context sufficiency           | `ContextSufficiencyService.java` | `app-services`  |
| Filter normalization          | `FilterNormalizationService.java` | `app-services` |
| NER backfill                  | `NerBackfillOps.java`          | `worker-services` |
| BIO tag decoding              | `BioTagDecoder.java`           | `worker-services` |

For tuning parameters (RRF K, vector weights, BM25 k1/b, HNSW M, ef_search),
see [18-adapters-lucene-deep-dive.md § Configuration](18-adapters-lucene-deep-dive.md).

---

<!-- source: docs/reference/contracts/search-pipeline-invariants.md -->

# Search Pipeline Invariants

Invariants and blocked combinations that the search pipeline enforces.
Contract tests live in `KnowledgeHttpApiAdapterHarmfulCombinationsTest.java`.

## Blocked Combinations

| Feature A | Feature B | Behaviour | Enforcement point |
|-----------|-----------|-----------|-------------------|
| HYBRID mode | Query expansion (AI translation) | Expansion silently skipped | `KnowledgeHttpApiAdapter.isExpansionEligible()` — returns false when mode ≠ TEXT |
| Stemming fallback | Fuzzy fallback | Applied sequentially, never simultaneously | Both are TEXT-mode SIMPLE-syntax fallbacks; fuzzy only fires when stemmed result set is empty |
| Explicit filters | QU-extracted boostFilters | QU boost not applied when caller provides filters | `KnowledgeHttpApiAdapter.doSearch()` checks `hasExplicitFilters` before QU dispatch (363) |
| Soft boost (SHOULD) | Zero results | Soft boost never produces zero results | `QueryFilterBuilder.applyBoostFilters()` uses `BooleanClause.Occur.SHOULD` — additive, always falls back to content-score-only (363) |
| FilterNormalization | Search vs answer path | Fires on both paths when enabled | `FilterNormalizationService` runs async on both `KnowledgeHttpApiAdapter` (search) and `RetrieveContextController` (answer) when `JUSTSEARCH_FILTER_NORM_ENABLED=true` (366) |
| Entity/metadata filters | Chunk documents | Never applied to chunks directly | Two-stage pre-filter: parent-doc ID lookup first, then chunk search scoped to matching parent IDs. `buildChunkFilterQuery()` excludes these; `buildFilterQueryOnly()` includes them (362) |
| Entity facet keys | MCP layer | `_raw` suffix stripped before agent response | Backend uses `_raw`-suffixed field names; MCP server strips `_raw` suffix before returning to agents (366) |

### Why HYBRID blocks expansion

Query expansion (AI translation) generates morphological variants for BM25.
In HYBRID mode, dense retrieval already provides semantic recall, making
expansion redundant and potentially harmful (expanded terms can dilute BM25
precision without improving fusion quality).

Note: Reranking (LambdaMART + cross-encoder cascade) is **not** blocked in
HYBRID mode — it fires for all presets when enabled (256-F1). The cross-encoder
operates on the fused candidate list regardless of which retrieval legs
produced it.

### Why stemming and fuzzy are sequential

Stemming is applied as a first-pass TEXT fallback (expand query to include stemmed variants).
Fuzzy is applied only when the stemmed query returns zero results.
Running both simultaneously would double-count variant terms and produce unpredictable recall.

### Why QU is bypassed when explicit filters are present

When a caller provides `filters` or `boostFilters` in the request, the caller
has already decided what to filter on. Applying QU-extracted boostFilters on
top would create unpredictable interactions (e.g., QU detects "The Verge" and
adds a soft boost, but the caller already has an explicit `metaSource` filter).
The `hasExplicitFilters` check in `doSearch()` prevents this overlap (363).

### Why soft boost (SHOULD) never produces zero results

Hard filters (`FILTER` clauses) caused 7/50 zero-result queries in the 362
agent eval when filter values didn't match the index vocabulary exactly. Soft
boosts use `BooleanClause.Occur.SHOULD` — they promote matching documents but
don't exclude non-matching ones. The system always falls back to content-score-
only results when no documents match the boost criteria (363).

### Why FilterNormalization fires on both search and answer paths

Filter value normalization (case folding + deterministic substring matching +
optional LLM fallback) must apply consistently regardless of whether the caller
uses `POST /api/knowledge/search` or `POST /api/knowledge/retrieve-context`.
Both paths accept the same filter fields and route through the same index.
Inconsistent normalization would cause the answer path to miss documents that
the search path finds (or vice versa). The service runs async with a deadline
to avoid blocking either path (366).

### Why metadata/entity filters are never applied to chunk docs directly

Entity and metadata fields exist on parent documents only (extracted from
frontmatter and content at index time). Chunk documents don't carry these
fields. Applying a `metaSource` filter directly on a chunk query would return
zero results. The two-stage pre-filter pattern solves this: first, find parent
document IDs matching the filter (`buildFilterQueryOnly`), then search chunks
scoped to those parent IDs. `buildChunkFilterQuery` intentionally excludes
entity and metadata filter fields to prevent accidental direct application (362).

### Why entity facet keys are stripped in the MCP layer

The Lucene index stores entity values in `_raw`-suffixed fields (e.g.,
`entity_persons_raw`) to preserve original casing for facet aggregation
(separate from the analyzed/lowercased fields used for search). Agents don't
need to know about this implementation detail. The MCP server strips the
`_raw` suffix so agents see clean field names (`entity_persons`) that match
the filter parameter names they use (366).

## Enforcement Pattern

Guard conditions are extracted as static package-private methods to enable unit testing:

```java
// KnowledgeHttpApiAdapter.java
static boolean isExpansionEligible(
    SearchMode mode, SearchQuerySyntax syntax, String query, String cursor, boolean aiAvailable)

static boolean isRerankerEligible(
    PipelineConfig pipeline, RerankerConfig config, int resultCount,
    long avgContentLengthChars, QueryType queryType)
```

`isRerankerEligible` checks `pipeline.crossEncoderEnabled()`, min hits
threshold, and average document length (258-B1: auto-disables cross-encoder
when documents exceed the reranker's 512-token input — active model is
`gte-multilingual-reranker-base` (343)). It does **not** gate on search mode.

Tests assert both the positive case (eligible config → feature applied) and
negative cases (expansion blocked in HYBRID). See
`KnowledgeHttpApiAdapterHarmfulCombinationsTest.java` for the full contract.

<!-- generated:end -->
