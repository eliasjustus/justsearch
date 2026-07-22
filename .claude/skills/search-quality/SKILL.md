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

**Hop-count vocabulary note (tempdoc 731 §3.3, issue 14).** For the procedurally-generated
`golden/*` chain corpora (`scripts/jseval/jseval/corpus_generate.py`; `golden/battlefield-en-v1`,
`golden/battlefield-de-v1` below, and the `hops=N` generator parameter cited in the provenance
notes under Findings) and the 707 fabricated-chain-injection family built on the same generator:
the `hops=N` parameter and the emitted `question_type: "N_hop"` label count planted-chain
relation *edges*, not behavioral retrieval hops. An N-entity chain has `hops = N-1` edges but
requires **N retrievals** to answer (one gold doc per entity) — behavioral hops = edges + 1.
Both vocabularies are internally consistent within their own contexts; the collision is only at
the reader. Do not relabel `question_type` on committed corpora — `queries.json` bytes are
digest-bound (`query_gold_sha256`, `corpus_certify.py:107,274,292`) and a relabel invalidates
every committed cell signature. The behavioral count is already derivable without a relabel:
`retrieval_hops = len(evidence_ids)`.

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
| golden/needle-burial-v1 | synthetic/buried-signal | en | 280 | 20 | zero-overlap paraphrase | 2026-06-23 | 636 | Buried-signal regression guard (F-023). Source `scripts/jseval/635-corpora/needle-burial-v1`; s30/s60 scales regenerable via seed=636/ratio in `meta.json`. **Content regenerated 2026-07-01 (tempdoc 664)** — see Corpus provenance note under Findings. **LEAKY — id-shape enumeration (776 item 3):** gold occupies `trailing_int(id)` 1..40, distractors 41..280; `trailing_int(id)<=40` selects gold at P/R 1.0 (native base 0.29) via materialized `<doc_id>.txt` filenames. `_FILLER` uniform (not gold-selective here). See the 767/776 Corpus provenance note. |
| golden/battlefield-en-v1 | synthetic/2-hop chains | en | 390 | 26 | 2-hop chain | 2026-07-11 | 711 | Certified in-band 624 (hybrid 0.4143 "hard", pre-F-031). **Out of band at HEAD defaults post-F-031** (711 re-measure: hybrid 0.9517, vector 1.0000 — saturated in BOTH modes) — no longer a difficulty discriminator in any mode; still valid for throughput profiling (691). Difficulty successor: 704 Pillar 1. Source `scripts/jseval/624-corpora/battlefield-en-v1`; re-measure: `jseval corpus-fidelity --dataset battlefield-en-v1 --modes hybrid,vector --embedding --start-backend --clean`. **LEAKY — id-shape enumeration (776 item 3):** gold occupies `trailing_int(id)` 1..78, distractors 79..390; `trailing_int(id)<=78` selects gold at P/R 1.0 (native base 0.20) via materialized filenames. `_FILLER` uniform (not gold-selective here). See the 767/776 Corpus provenance note. |
| golden/battlefield-de-v1 | synthetic/2-hop chains | de | 390 | 26 | 2-hop chain | 2026-07-11 | 711 | In-band at HEAD defaults (711 re-measure: hybrid 0.5924 — exact match to the 624 certification — vector 0.58, "moderate") — remains a valid difficulty corpus in both modes. Source `scripts/jseval/624-corpora/battlefield-de-v1`; same re-measure command shape as en-v1. **LEAKY — id-shape enumeration (776 item 3):** gold occupies `trailing_int(id)` 1..78, distractors 79..390; `trailing_int(id)<=78` selects gold at P/R 1.0 (native base 0.20) via materialized filenames. `_FILLER` uniform (not gold-selective here); minor query-overlap elevation (0/26 zero-overlap queries, median Jaccard 0.072). See the 767/776 Corpus provenance note. |
| mixed/en-legal-clerc-{1k,10k}-{verbose,short-natural} | legal (real CLERC hosts + fabricated injected gold) | en | 1000/10000 | 20/cell | verbose + short-natural strata | 2026-07-16 | 707 | **707 U0 member, FULLY CERTIFIED** (16/16 gates under the ACTIVE pre-run policy `scripts/jseval/707-corpus-certification-policy.v1.json`; closed-book 0.000 ×4). Hybrid 0.5051 / 0.4685 / 0.3238 / 0.2806 (1k-v / 1k-sn / 10k-v / 10k-sn) — **pre-rebuild / leak-inflated** (`_FILLER` gold-only feature, ~1/3 of measured retrieval; 767 §Q). **Certified leak-free (2026-07-22, PR #273): hybrid 0.33 / 0.25 / 0.06 / 0.08** (767 §R.1). See the 767/776 Corpus provenance note. Commitments + recipes: `scripts/jseval/707-corpora/en-legal-clerc/`. |
| mixed/en-email-enron-raw-{1k,10k}-{verbose,short-natural} | email (raw public-domain Enron distractors + fabricated injected gold) | en | 1000/10000 | 20/cell | verbose + short-natural strata | 2026-07-16 | 707 | **707 U0 member, FULLY CERTIFIED** (16/16 gates, same ACTIVE policy; closed-book 0.000 ×4; license `LicenseRef-Enron-FERC-public-record`). Hybrid 0.8043 / 0.7699 / 0.7052 / 0.6627 — strongest member; graceful scale decay — **pre-rebuild / leak-inflated** (`_FILLER` gold-only feature; 767 §Q). **Certified leak-free (2026-07-22, PR #273): hybrid 0.66 / 0.61 / 0.49 / 0.44** (767 §R.1). See the 767/776 Corpus provenance note. Commitments + recipes: `scripts/jseval/707-corpora/en-email-enron-raw/`. |
| mixed/de-miracl-{1k,10k}-{verbose,short-natural} | wikipedia-de distractors + fabricated injected gold (v2, hops=1) | de | 1000/10000 | 20/cell | verbose + short-natural strata | 2026-07-16 | 707 | **707 secondary stratum — NOT claim-bearing** (deliberately absent from the ACTIVE policy). 1k out-of-band (hybrid 0.2053 / 0.2660), 10k semantically collapsed (0.0431 / 0.0428, union recall 0.10); lexical 0.0 everywhere (pre-registered German grep-collapse, confirmed). The 10k collapse is chartered as tempdoc 748 → Q-018. **LEAKY / pre-rebuild — NOT rebuilt (776 item 3):** fabricated gold (gold source `635-corpora/synth-multiling-de-v1`) carries the identical *English* `_FILLER` block in 240/240 docs per cell among real German MIRACL hosts — the same gold-selective leak as legal, cross-language; the quoted hybrid numbers are leak-inflated and Q-018 needs re-verification on a defillered rebuild. See the 767/776 Corpus provenance note. Commitments: `scripts/jseval/707-corpora/de-miracl/`. |

---

## Canonical Baselines

One block per dataset. Within each block, one row per measured (config × mode).
All values are nDCG@10 unless noted. `—` = not yet measured.

**Columns:** `encoder` — sparse+dense encoder. `ce` — cross-encoder at eval
time. `cc` — CC fusion weights. `mode` — jseval mode. `legs` — retrieval
legs confirmed active (from pipeline_tracking.observed). `conf` — confidence
tier. `git` — git_sha from summary.json. `src` — tempdoc citation.

**Cross-run noise vs signal** — before flagging a nDCG@10 change as a
regression, consult the cohort envelope: `scripts/jseval/tmp/
cohort_baselines/<hash>/envelope.json` — the jseval-owned data root where
calibration state is filed since tempdoc 716 (readers fall back to a
pre-716 backend data dir with a WARN) — gives σ per metric (tempdoc 400
LR1-b). Deltas
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

**Release:** `715-rebaseline-2026-07-16` · default mode `hybrid` · NVIDIA GeForce RTX 4070 · driver 610.62 · ORT 1.24.3

**Coverage:** retrieval ranking quality (per-corpus metrics above) — **does NOT measure** document extraction / OCR / VDU routing quality (see tempdoc 623 §F — extraction-quality sibling).

| Corpus | Ours (mode) | nDCG@10 | Published baselines (cited, side-by-side) |
|---|---|---|---|
| beir/scifact | hybrid | 0.760 | BM25 (multifield) 0.665, SPLADE++ EnsembleDistil 0.710, ColBERTv2 0.693 |
| mixed/enron-qa | hybrid | 0.736 | — |
| mixed/legal-clerc-200 | hybrid | 0.598 | BM25 0.054, Contriever-MSMarco (zero-shot dense) 0.042 |
| mixed/miracl-de-2k | hybrid | 0.862 | BGE-M3 Dense 0.567 (dev) |
| mixed/miracl-fr-2k | hybrid | 0.873 | BM25 0.183 (dev), mDPR (zero-shot) 0.435 (dev), Hybrid (BM25+mDPR) 0.523 (dev) |

**Engine performance** (relative-ratchet guarded — tempdoc 640):

| Corpus | CE p50 (ms) | Index docs/s | Enrich docs/s | Resident (GB) |
|---|---|---|---|---|
| beir/scifact | 171 | 89.8 | 20.5 | 2.02 |
| mixed/enron-qa | 161 | 64.5 | 6.2 | 2.02 |
| mixed/legal-clerc-200 | 226 | 29.3 | 1.0 | 2.02 |
| mixed/miracl-de-2k | 175 | 97.2 | 33.4 | 2.02 |
| mixed/miracl-fr-2k | 174 | 60.0 | 23.3 | 2.02 |

<!-- generated:end -->

> **Reading the two numbers (tempdoc 623 ④ / C-4).** The **Release Scorecard** above is the
> *production-default* (`hybrid`) result — a **projection** of one cohort-identical release, the number a
> user actually gets and the one the ratchet floors against. The per-corpus **Best known:** lines below
> are a *hand-authored research log of best-achievable-config ablations* (often `full`-mode), kept for
> engineering history — **not** the production headline. They differ **by design**: e.g. legal is
> `full`-mode **0.925** best-achievable vs `hybrid` **0.598** production-default (corpus×config optimality,
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
| (HEAD default re-verify, 774 same-session OFF arm) | (default) | (default) | hybrid | 0.7445 | 0.600 | 0.867 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification | A | 5f45022b | 774 §K.2 |
| (774 Stage-1 chunk-lever A/B set, flags non-default) | (default) | (default) | hybrid | 0.7476 | 0.597 | 0.877 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification | A | 5f45022b | 774 §K.2 |
| (**`search.evidence_preview.enabled=true`**, default-off flag) | (default) | (default) | hybrid | **0.7882** | 0.643 | 0.913 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification | A | 5f45022b | 774 §K.2 / F-041 |

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
| (HEAD default, late-chunking ON = new default) | (default) | (default) | vector | **0.2967** | 0.220 | 0.365 | dense | A | e83653a | 691 §N |
| (HEAD default, late-chunking ON) | (default) | (default) | lexical | 0.6888 | — | 0.855 | bm25 | A | e83653a | 691 §N |
| (HEAD default, late-chunking ON) | (default) | (default) | splade | 0.0591 | — | 0.150 | splade | A | e83653a | 691 §N |
| (HEAD default, late-chunking ON) | (default) | (default) | hybrid | **0.5497** | 0.415 | 0.695 | cross_encoder+dense+hybrid+query_classification | A | e83653a | 691 §N |
| (late-chunking ON + `JUSTSEARCH_EMBED_GPU_MEM_MB=6144`) | (default) | (default) | vector | **0.3401** | 0.240 | 0.430 | dense | A | e83653a | 691 §N |
| (HEAD default, RMW preservation = F-032) | (default) | (default) | lexical | 0.6891 | 0.510 | — | bm25+chunk_merge | A | b88e76e | 711 |
| (HEAD default, RMW preservation) | (default) | (default) | splade | 0.0591 | 0.005 | — | splade+chunk_merge | A | b88e76e | 711 |
| (HEAD default, RMW preservation) | (default) | (default) | vector | **0.6184** | 0.410 | — | dense+chunk_merge | A | b88e76e | 711 |
| (HEAD default, RMW preservation) | (default) | (default) | hybrid | **0.5609** | 0.425 | — | cross_encoder+dense+hybrid+chunk_merge | A | b88e76e | 711 |
| (HEAD default re-verify, FRESH build, chunk vectors probe-verified 4293/4293) | (default) | (default) | vector | 0.6185 | 0.410 | 0.825 | branch_fusion+chunk_merge+dense | A | bc4bcd8 | 713 §M-5 |
| (HEAD default re-verify, FRESH build) | (default) | (default) | hybrid | 0.5588 | 0.425 | 0.700 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid | A | bc4bcd8 | 713 §M-5 |
| (single-pass OFF: `late_chunking_enabled=false`, chunks ON — the F-031-less cell) | (default) | (default) | vector | **0.4147** | 0.150 | 0.725 | branch_fusion+chunk_merge+dense | B | 7b7c485 | 713 |
| (single-pass OFF, chunks ON) | (default) | (default) | hybrid | 0.5339 | 0.375 | 0.690 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid | B | 7b7c485 | 713 |
| (HEAD default re-verify, 774 same-session OFF arm) | (default) | (default) | hybrid | 0.5557 | 0.425 | 0.690 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification | A | 5f45022b | 774 §K.2 |
| (774 Stage-1 chunk-lever A/B set, flags non-default) | (default) | (default) | hybrid | 0.5448 | 0.420 | 0.685 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification | A | 5f45022b | 774 §K.2 |
| (**`search.evidence_preview.enabled=true`**, default-off flag) | (default) | (default) | hybrid | **0.6388** | 0.465 | 0.810 | branch_fusion+chunk_merge+cross_encoder+dense+hybrid+query_classification | A | 5f45022b | 774 §K.2 / F-041 |

**Best known:** (HEAD default, RMW preservation) / hybrid = **0.5609**; vector = **0.6184** (711,
2026-07-11 — supersedes 691 §N's 0.5497/0.3401: those were measured against an index whose 4,293
chunk vectors were ALL silently destroyed post-write, F-032; all pre-711 vector/hybrid rows above
are now dead-chunk-vector ablations). Union recall 0.925 (> 0.87 baseline); relevance + leak gates
green at b88e76e.
**Note:** BM25-dominant on this corpus too (lexical 0.686 vs vector/splade ~0.06) — consistent with the
retired courtlistener-200's own BM25-dominance-on-long-legal-docs finding, though this is a fresh
observation on the new corpus, not an inherited assumption (the new corpus has its own citation-style query
form, `queries/test.single-removed.direct.tsv`, distinct from the old known-item task — see Corpus
provenance note above). No cc/encoder ablation pass has been run yet.
**Note (691, 2026-07-11):** the long-doc single-pass VECTOR path (default-on since 691 Phase 4)
revives the dense leg 5.0× at shipped defaults (0.060→0.2967; 0.3401 with a 6144MB embed arena —
the residual gap is ~20 near-8k-token docs OOM-falling-back to windowed at the 3072 default). See
F-031. Union-recall 0.890 (> the 0.87 pin) on the same run; leak + relevance gates green.
**Note (712, 2026-07-11):** the `splade` rows (0.059/0.0591) are substantially a 512-token
truncation artifact — offline, per-chunk SPLADE covering the whole doc revives the sparse leg to
0.327 (max-pool merge) / 0.545 (chunk-MaxP), the sparse sibling of F-031/F-032. See F-033 + Q-017.
No engine-integrated chunk-splade row exists yet.
**Note (713, 2026-07-11):** the single-pass-OFF rows are the missing (chunks-alive × no-F-031)
cell — see F-035: the parent single-pass is NOT redundant (−0.204 vector without it). Conf B: the
arm reused the prior run's index via jseval `--clean`'s then-protected set (retired by 716) —
incremental rebuild, cell content probe-verified (4293/4293 chunk vectors live, `singlePass=0` in
all 94 batches). The fresh-build re-verify rows confirm the 711 defaults pin (0.6185 ≈ 0.6184)
with a direct on-disk vector-count probe. A separate same-session fresh-build defaults run scored
0.3403/chunks-dead (the F-032-control signature, with the fix present) — unreproduced on the
immediate probe-instrumented re-run, quarantined as a C-confidence anomaly in tempdoc 713 §M-3/M-5.

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

<!-- tempdoc 687 (Search Thread, 2026-07-07): retrieval BEHAVIOR note, not a quality lever —
     agent-run searches can now be user-scoped (`docIds` from scope chips flow AgentRequest →
     AgentToolDispatcher.scopeToolCall → SearchTool's PATH filter, overriding tool args; absent →
     unchanged). No analyzer/ranking change; utility-gate relevance: a scoped condition narrows the
     candidate pool the agent judges, which future agent-utility runs should account for.
     ALSO (687 R3d residue): boot warmup removed the ~870ms first-query cold start, but the
     STEADY-STATE hybrid query on a 6-doc corpus measures ~350ms end-to-end - unprofiled.
     Worth a stage-breakdown profile (searchTrace timings) before any design conclusion. -->

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

### Corpus provenance note (2026-07-22, tempdoc 767 §Q + 776 items 3-4 — leak-inflated 707/golden substrate)

The fabricated-chain corpora built by `scripts/jseval/jseval/corpus_generate.py` carried a gold-only
feature that inflated measured retrieval: a `_FILLER` boilerplate paragraph ("The surrounding district
is known for long winters and quiet markets ...") appeared in 100% of the fabricated gold documents. In
the **707 injection family** (fabricated gold interleaved among real host distractors) that paragraph
appeared in ~0% of the real hosts, making it a perfect gold-identifying signal. Tempdoc 767 §Q measured
its effect directly on `en-legal-clerc-1k-verbose`: filler ON→OFF, all else held constant, dropped
hybrid nDCG@10 by −0.164 (0.492→0.328) and union recall by −0.20 — **the filler was inflating measured
retrieval by roughly a third**.

- **`mixed/en-legal-clerc-*` and `mixed/en-email-enron-raw-*` were rebuilt leak-free** (real host
  carrier sentences replacing `_FILLER`; document IDs and length signatures also neutralized — 767 §I)
  and re-certified 2026-07-22 (PR #273), thresholds re-derived per cell from fresh n=50 measurements
  (767 §R). The **certified leak-free** hybrid nDCG@10 (fidelity, `--embedding`, 1k-verbose /
  1k-short-natural / 10k-verbose / 10k-short-natural) are: **legal 0.33 / 0.25 / 0.06 / 0.08** and
  **enron 0.66 / 0.61 / 0.49 / 0.44** (767 §R.1). The pre-rebuild hybrid values quoted in the Dataset
  Catalog rows for these members (legal 0.5051/0.4685/0.3238/0.2806; enron 0.8043/0.7699/0.7052/0.6627)
  were measured on the leak-inflated substrate.
- **`mixed/de-miracl-*` was NOT rebuilt** (776 item 3, verdict **LEAKY**). Its gold source
  (`635-corpora/synth-multiling-de-v1`) and every fabricated cell still carry the identical *English*
  `_FILLER` block (240/240 gold docs per cell), which cannot appear in the real German MIRACL hosts —
  the same gold-selective leak as legal, made more glaring by being cross-language. Its numbers remain
  leak-inflated and are not reproducible against a defillered rebuild.
- **The pure-synthetic `golden/*` agent-utility corpora** (`needle-burial-v1`, `battlefield-en-v1`,
  `battlefield-de-v1`; 776 item 3) carry a *different* gold-only feature: an **id-shape enumeration
  leak**. Gold documents occupy trailing-integer 1..N and distractors N+1..M with zero overlap, so
  `trailing_int(id) <= N` selects the entire gold set at precision/recall 1.0 (Youden J 1.0) versus a
  native base rate of 0.20-0.29 — visible to any agent listing the materialized `<doc_id>.txt`
  filenames without reading a document body (the 767 defect #3 class). Their `_FILLER` is uniform
  across gold *and* distractors (both 100%) and is therefore **not** a gold-selective leak in these
  corpora. Instrument outputs: `tmp/analysis-624/776/leak-audit/`.

**Findings measured against the pre-rebuild 707 substrate or the id-leaked `golden/*` corpora are
historical and not reproducible against the corpora as they will stand once rebuilt** — this is a fact
about reproducibility, not a retraction; the measurements genuinely happened and record what was found
*then*. **Already-shipped decisions are unaffected** (F-039 was re-measured leak-free before its
resolution — #273; F-027's records are an A-vs-A noise-floor measurement whose Δ is symmetric to any
corpus leak). Re-derivation of any leak-inflated number should use the rebuilt/defillered corpus and
re-cite this note.

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

### F-031: long-doc whole-doc dense death is substantially WINDOW-MEAN DILUTION — one long-context pass revives the vector leg 5-6×; SHIPPED default-on (tempdoc 691 Phases J-N, 2026-07-11; settles the 691 Q-016 draft; refines F-030(678)'s scope)

- **Answer:** the production whole-doc `VECTOR` for chunked (>2000-char) docs was a mean of
  512-token-window CLS vectors; embedding the whole doc in ONE batch-1 pass (up to 8192 tokens,
  `OnnxEmbeddingEncoder.embedWithSpans`, `justsearch.embed.late_chunking_*`) lifts legal-clerc-200
  `vector` nDCG@10 **0.060 → 0.2967 at shipped defaults (5.0×) / 0.3401 with a 6144MB embed arena
  (5.7×)**, hybrid 0.523→0.5497, union-recall 0.890 (> the 0.87 pin), leak + relevance gates green.
  Controls: enron-qa +7% vector / +1.3% hybrid (no BM25-dominant regression); scifact neutral.
  **Default ON since 691 Phase 4** (D-004 template completed). Measured cost: background enrichment
  slower on long-doc corpora (enron 7.7→4.5 docs/s, measured at a 3072MB arena). **Update
  2026-07-11 (founder decision): the embed arena default is now 6144MB** — shipped defaults
  deliver the full 0.3401 ceiling with zero OOM-fallbacks; the 0.2967 row documents the
  3072-arena ablation.
- **Scope reconciliation with F-030(678):** 678's elimination campaign tested gating, query shape,
  granularity, and naturalness — context length was NOT a tested axis. This finding does not
  overturn the encoder-domain-mismatch verdict; it SPLITS the deficit: roughly half the dense death
  was the window-mean representation (recovered here: 0.06→0.34), the remainder (vector 0.34 vs
  lexical 0.69) stays with the encoder-domain question (708's lane). Supporting offline datapoint
  (691 §Phase M): pure chunk-CLS exact-NN MaxP reaches nDCG@10 0.64 / R@10 0.85 on this corpus —
  the encoder separates legal content at chunk granularity far better than any whole-doc
  representation, evidence 708 should weigh.
- **What did NOT ship (measured against):** deriving `CHUNK_VECTOR`s from the same pass (canonical
  late chunking, arXiv:2409.04701) — on this CLS-pooled model, span-mean chunk vectors regress
  hard (offline: nDCG@10 0.640→0.407; the method's authors state CLS models are incompatible).
  Chunk docs keep their per-chunk CLS path.
- **Structural caveat (for any future enrichment pass):** `KnnFloatVectorField` is non-stored and
  silently destroyed by any later read-modify-write; the combined pass's one-RMW-per-doc bundling
  is the invariant that keeps vectors alive (691 §N-5 — a separate VECTOR-writing pass gets erased
  by the next stage's RMW with status still COMPLETED). Logged for tempdoc 710.
- **Evidence:** tempdoc 691 §Phase J/M/N (arm tables, five-defect forensic chain, gate reports);
  artifacts `tmp/691-ab2/` (per-arm summary.json + worker.log copies); reproduction commands in
  691 §K-5.

### F-032: ALL chunk vectors were silently destroyed post-write at shipped HEAD — catalog-declared RMW preservation recovers them, legal vector 0.3401 → 0.6180 (tempdoc 711 Item 1, 2026-07-11; supersedes F-031's "structural caveat" with the structural fix)

- **Answer:** `WritePathOps.readModifyWrite` rebuilt docs from stored fields only, so every
  non-stored field absent from an update map was destroyed on rewrite. Live probe over the
  on-disk index at base `f12ded5` after a defaults pipeline run on legal-clerc-200: parent
  `vector` = 198 present, **`chunk_vector` = 0 of 4,293 present** — the `chunk_merge` leg of
  vector mode had zero chunk vectors to merge. A second confirmed loss: SPLADE FeatureField
  data destroyed while `splade_status` stayed COMPLETED (`preserveSplade=true` preserved the
  status of data it could not preserve). Fix (711 Item 1): every non-stored/non-docValues
  data-bearing field declares an `rmwPolicy` in `fields.v1.json` (`preserve-reread` for
  vector/chunk_vector via Lucene ordinal read-back at the held searcher snapshot;
  `reset-status:splade_status` with COMPLETED→PENDING downgrade for splade), enforced once
  inside `readModifyWrite` with startup fail-fast for undeclared fragile fields;
  `preserveSplade` threading deleted (36 sites).
- **Measured (same-day A/B, byte-identical corpus sha256 630f5376…, shipped defaults):**
  CONTROL `f12ded5` vector nDCG@10 **0.3401** (reproduces the F-031 pin to 4 decimals) /
  hybrid 0.5446, chunk_vector docs 0; ENGINE vector **0.6180** / hybrid **0.5592**,
  chunk_vector docs 4,293/4,293; wall 141.2 s vs 130.8 s (no throughput cost). New best-known
  legal-clerc defaults: vector 0.6180, hybrid 0.5592.
- **Reframes F-031:** the "0.3401 ceiling" was measured against an index with all chunk
  vectors dead; §J-B's offline parent-only replication (0.3403) agreed with it precisely
  *because* chunks contributed nothing. The remaining vector-vs-lexical gap for 708's
  encoder-domain question is now 0.618 vs 0.686, not 0.34 vs 0.69.
- **Gates (full-mode run at b88e76e, publish step):** lexical 0.6891 / splade 0.0591 / vector
  0.6184 / hybrid 0.5609; union recall **0.925** (baseline 0.87, floor 0.82), relevance gate
  (floor 0.4964) and leak gate (ceiling 0.255) both green. Baseline rows updated in the
  legal-clerc block above.
- **Evidence:** tempdoc 711 (§Item 1 implementation log + §live verification: A/B tables,
  vector-count probe, Step-0 characterization tests); branch `worktree-711-rmw`.

### F-035: the parent single-pass VECTOR is NOT redundant post-F-032 — removing it costs legal vector −0.204 even with all chunk vectors alive; KEEP BOTH representations (tempdoc 713, 2026-07-11; answers the F-032-opened missing cell; F-031's lever survives the landscape change)

- **Answer:** the never-measured (chunks-alive × parent-without-single-pass) cell on
  `mixed/legal-clerc-200` (byte-identical corpus sha256 `630f5376…`, zero-code arm
  `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED=false`, chunk vectors on): **vector nDCG@10 0.4147 /
  hybrid 0.5339**, vs same-session fresh-build defaults **0.6185 / 0.5588** (which re-verifies the
  711 pin 0.6184/0.5609 with a direct on-disk probe — 4293/4293 chunk vectors live). Delta:
  **vector −0.204 (−33%), hybrid −0.025**. Chunk-MaxP + `chunk_merge` does NOT substitute for a
  good parent vector. **Mechanism:** the whole-doc branch participates in branch fusion regardless
  of its quality — with the single-pass OFF, window-mean parents inject 0.060-quality noise that
  dilutes the healthy chunk branch (the cell sits between window-mean-only 0.060 and the offline
  chunk ceiling ~0.64). Verdict: **keep both** — the dense parent/chunk pair is a governed
  dual-representation (parent = whole-doc gist consumed by branch fusion; chunks = best-passage
  evidence), not a redundant fork. F-031's machinery stays; no config/default change.
- **Cell validity:** arm probe `live_chunk_vector=4293/4293`, `singlePass=0` in all 94
  combined-pass batches (flag took effect), `fail=0` (enrichment complete), 200 queries.
  Conf B: the arm was an incremental rebuild over the prior run's index (jseval `--clean`
  protected set, retired by 716) — content probe-verified, build-shape deviation noted.
- **Honest framing:** this *confirms* (not reverses) 691's shipped F-031 lever under the F-032
  landscape change — the charter's consolidation hypothesis is measurement-rejected.
- **Anomaly quarantined (713 §M-3/M-5):** one same-session fresh-build defaults run scored
  0.3403/chunks-dead (the exact F-032-control signature, with #139 verifiably in the running
  code); the immediate probe-instrumented fresh re-run reproduced 0.6185/chunks-alive instead.
  Unresolved one-off (C confidence, quarantined); the fresh-ingest path is NOT implicated —
  re-open only if a chunks-dead signature (vector ≈0.34 + missing `chunk_merge` leg) recurs.
- **Feeds 712/Q-017:** the structural argument (branch fusion always consumes the parent leg)
  applies to the SPLADE parent too, but 712 should measure, not inherit.
- **Evidence:** tempdoc 713 (§Takeover T-1..T-5, §Measurement M-1..M-5, probe outputs, counter
  tables); runs `tmp/eval-results/20260711T135127/T135536/T161445_mixed_legal-clerc-200`
  (713-dense worktree); reproduction commands in 713 §Measurement.

### F-034: offline encoder bake-off on legal-clerc-200 — NO MODEL SWAP; the incumbent was never domain-limited, and no eligible multilingual candidate significantly beats it (tempdoc 708, 2026-07-11; closes the encoder-choice question F-030(678) spawned)

- **Answer:** a Gate-0-anchored offline exact-NN bake-off (42 runs, byte-identical register corpus
  `90d4300d…baf1`, harness `scripts/jseval/experiments/encoder_bakeoff_708.py`, run JSONs with
  per-query gold ranks in the 708 worktree's `tmp/eval-results/708-bakeoff/`) screened 6 eligible
  general-multilingual encoders (D-003-screened, Apache/MIT: Qwen3-Embedding-0.6B,
  arctic-embed-{m,l}-v2.0, bge-m3, multilingual-e5-large, granite-278m) against the incumbent
  `gte-multilingual-base` across three doc-side constructions (W1 production-mirror window-mean /
  W2 single-pass long-context / C chunk-MaxP 500-50) × two query shapes (verbose, kw). **Gate 0
  PASSED** — the harness reproduces the engine's F-030(678) dense numbers to Δ0.005 (W1 0.105/0.150
  vs engine 0.100/0.145; nDCG 0.061 ≈ vector-mode 0.060), so deltas are engine-meaningful.
  **Verdict: NO MODEL SWAP.** The incumbent itself clears the pre-registered FIX band once the
  production construction is bypassed (verbose R@10: W2@2048 0.655, W2@8192 0.745, chunk-MaxP
  **0.855** = BM25-verbose parity, nDCG 0.643); at chunk granularity no candidate is significantly
  better (paired sign tests vs anchor-C on per-query gold ranks: arctic-l 0.865/0.671, p=0.085;
  arctic-m 0.860/0.659, p=0.488; bge-m3 0.860/0.639, p=1.0; me5 0.770; granite 0.715; arctic-l's
  +0.028 nDCG costs ~2× footprint + ~1.7× encode slowdown). Branch FIX was realized by the
  691/711 construction fixes (F-031 + F-032) — shipped legal vector nDCG@10 0.6180 captures ~96%
  of the incumbent's offline chunk-granularity ceiling (0.643).
- **Attribution completed (the anchor-fav control):** production W1's 0.105 is dominated by
  **CLS-pooling raw id-slice windows without a [CLS] token** (windows 2+ in
  `OnnxEmbeddingEncoder.createChunks`) — the same model, same window-mean construction, with proper
  per-window special tokens scores **0.745** (= W2@8192). Mean-pool dilution and granularity account
  for the rest (0.745 → 0.855). Logged for any residual >8192-token window-mean path.
- **Secondary findings:** (1) **kw-shape queries are weak for dense across every model and
  condition** (max R@10 0.400) — dense needs sentence-shaped queries; the dense-side mirror of
  F-030(678)'s BM25-verbosity monotonicity. (2) SPLADE on legal remains unrecovered (0.0591 at
  b88e76e) and was deliberately not reopened — the eligible multilingual learned-sparse field is
  effectively one model deep. (3) License/eligibility record: jina-v3/v4 (CC-BY-NC/Qwen-Research),
  voyage-law-2 + Isaacus Kanon 2 (legal-specialized, proprietary API) named-and-excluded per D-003.
- **Conditions/caveats:** offline torch fp16 exact-NN screen, not engine runs (Gate 0 is the
  bridge); 198-doc corpus (no 4k stress — Phase 5 re-scoped to nothing since no swap ships);
  qwen3-0.6b W2/C skipped (founder-ratified: dominated at W1 0.530, ~6× slower, 60+ min W2 run
  killed). Candidate C/W-conditions used per-model-favorable recipes (own prefixes/pooling, proper
  special tokens) — biased FOR candidates, strengthening the no-swap conclusion.
- **Evidence:** tempdoc 708 (final table, protocol application, sign tests, run pointers); F-030(678)
  refinement note below; tempdoc 678 §E5-D correction annotation (its "+3.0 pts at chunk granularity"
  was an F-032 artifact — the probe's chunk-hybrid arm had zero chunk vectors).

### F-041: the Head cross-encoder was judging doc-head previews, not evidence — feeding it the winning passage lifts legal hybrid +15% and FLIPS the CE from harmful to helpful on email; shipped default-off (tempdoc 774 Stages 1-2, 2026-07-22; answers Q-001's mechanism)

- **Answer:** the Head CE's per-candidate input was `title + a ~1500-char query-focused
  snippet cut from content_preview` — the first ~4KB of the document, centered on LEXICAL
  match spans; chunk-branch-only hits carried no preview at all and reached the CE
  title-only (774 §F.1-5, live-confirmed). A default-off flag
  `search.evidence_preview.enabled` makes chunk-sourced hits deliver the winning chunk's
  text (≤4096 chars) as `content_preview` — one change that makes the CE input, the
  delivered preview, and highlight offsets evidence-coherent (no proto change). Measured
  (same-session A/B, hybrid, full enrichment, CE-on): **mixed/legal-clerc-200 0.5557 →
  0.6388 (+15%)**, P@1 0.425→0.465, R@10 0.690→0.810 (evidence-aware CE pulls gold from
  the 11-20 window into top-10); **mixed/enron-qa 0.7445 → 0.7882 (+5.9%)**, P@1
  0.600→0.643 — on the corpus where the CE was measured HARMFUL (F-002, −2-5%), i.e. the
  CE-hurts-email mechanism was substantially preview-blindness, not a reranking defect;
  **beir/scifact 0.7603 = baseline** (structural no-op: no chunk branch legs on the short
  corpus). Stage-1 chunk-branch guarantee levers (independent weights/zero-exclude,
  collapse-cap, PRE-collapse chunk-side recall-complete, base-results gate lever — all
  default-equivalent) measured regression-free on both sentinels (leak-class guarantees
  for free; no k=10 nDCG effect, consistent with 774 §J.1).
- **Adjacent defect found + fixed (same lane):** the CE `DOCS_TOO_LONG` auto-disable read
  a Head-side worker-session-average cache populated ONLY by `GET /api/knowledge/status`
  — evals (which poll `/api/status`) always measured CE-on while production clients
  polling `/api/knowledge/status` silently lost the CE on long-doc corpora (live-proved
  both directions, run 96da7851). Default `max_avg_doc_length_chars` flipped 16000 → 0
  (gate off = the measured configuration; operator override kept; teardown tombstoned).
- **Conditions/caveats:** flags DEFAULT-OFF pending founder default-flip decision
  (baseline re-pins + hero-cohort timing); eval-env CE ran on CPU (both arms equally —
  A/B-valid, latency claims unmeasured on GPU); enron/legal numbers are same-session A/B
  arms, not multi-seed. Live UI verification: deep-document evidence passage rendered
  with aligned highlights (774 §K.3).
- **Evidence:** tempdoc 774 §K; run dirs 20260722T135408/T140105/T140425 (legal),
  T141501/T142555/T143646 (enron), T144058 (scifact) in the 774 worktree;
  branch `worktree-774-passage-first`.

### F-040: passage granularity does NOT bridge camouflaged paraphrase — the legal-10k floor is representational at every granularity, the engine already beats its own offline passage ceiling there, and the recoverable share is CONTEXT, not architecture (tempdoc 774 §J.5/§J.7 probes, 2026-07-22; re-scopes the 774 charter; Stage-3 primacy inversion shelved)

- **Answer:** a Gate-0-anchored offline exact-NN passage probe (chunk-MaxP, 500/50
  production parity, incumbent encoder; anchor reproduced F-034 on legal-clerc-200 to
  Δ≤0.011 AND F-030's doc-level 0.100/0.060 exactly) measured the certified camouflaged
  strata: **en-legal-clerc-10k-verbose gold-parent MaxP rank >100/not-found for 80% of
  queries** (median gold-chunk rank 2,506 of 109,061; R@100 0.20), 1k: 50% beyond 100.
  Decisive inversion: the **shipped engine hybrid (0.54/0.16 recall@10 at 1k/10k) BEATS
  the offline passage exact-NN ceiling (0.20/0.04)** — isolated passage vectors are worse
  than what ships, because the engine's edge is the F-031 whole-doc (context-bearing)
  representation + CE. F-034's 0.855 passage ceiling was a citation-task property, not a
  granularity property. **Consequence: 774's Stage-3 (passage-primacy inversion) is
  measurement-rejected** — no headroom on the real task (engine already captures ~96-98%
  of its own passage ceiling, F-034) and representation-bound on the synthetic floor.
- **The context half (H.4 A/B, leak-controlled):** a uniform, leak-free 150-char doc-lead
  prefix on every chunk embed lifts the camouflaged floor **10k R@100 0.20 → 0.42 (2.1×),
  median rank 887 → 188**; 1k R@100 0.50 → 0.78 — but costs −0.04 R@10 on the real-task
  Gate-0 control (fails the pre-registered tolerance) → mechanism confirmed (the floor is
  substantially CONTEXT STARVATION of isolated chunk embeddings), recipe not shippable
  as-is; engine-side contextualization recipe = chartered follow-up. A title-prepend
  variant measured larger gains and was **INVALIDATED as a leak**: the 767 strata's
  `title` field is gold-only (100/10,000 docs titled, all 50 golds among them, titles =
  the structure descriptors) — reported to the 776/767 lane; invisible to the engine
  (lexical 0.00 → titles never indexed), poisonous to offline probes/baseline-arms.
- **Conditions/caveats:** offline exact-NN (no ANN/fusion) — deltas are the load-bearing
  result per the F-033/F-034 treatment; n=50 queries/cell; corpus signatures recorded in
  the artifacts. Engine-integrated depth effects were separately bounded by the 771 cert
  data (774 §J.1: vector ≈ hybrid at k=10, so chunk-branch weight dilution is not the
  binding constraint).
- **Evidence:** tempdoc 774 §J.5/§J.7/§F.3; artifacts
  `tmp/analysis-624/774/probe/` (774 worktree: per-cell summaries + per-query ranks +
  `probe_774.py`/`h4_ab.py`, hashes + reproduction commands inside).

### F-039: bridge-entity retrieval miss on legal agent-utility strata — structure-descriptive queries never reach designer-keyed gold; near-duplicate synthetic decoys outrank it, worsening 6%→28% of with-tool failures from 1k→10k (tempdoc 763 replay census, 2026-07-21)

- **Answer:** in the 624 v5 campaign's with-tool (B-arm) failure census (127 cells, all replayed
  against the exact cached indexes via the 751 adopt path), the engine owns **17/127 = 13.4%**
  of failures (15 B2-hard: gold absent from top-20 for every issued AND reformulated query;
  2 B2-marginal: reachable only at rank ~14) — **100% concentrated in legal, zero in email**,
  and scaling with corpus size (legal-1k 3/48 = 6% → legal-10k 14/50 = 28%). Mechanism: the
  `1_hop` "value associated with the designer of ⟨structure⟩" questions carry *structure*
  vocabulary while the gold docs are keyed on the *bridge entity* (the designer), so a
  structure-descriptive query retrieves near-duplicate synthetic decoys (e.g. `wendcrag32`,
  `kancrag5`) and high-scoring real CLERC hard negatives instead of gold. Reproductions (query,
  expected doc, observed top-5) in `tmp/analysis-624/763/replay/classification_summary.json`;
  replay harness `replay_stratum.py` re-adopts the campaign index in ~16s.
- **Context that scopes it:** retrieval is otherwise exonerated in that census — B1 bad-query = 0
  (agents' own reformulations were adequate), B3 result-returned-unused = 39 (engine returned
  gold within the agent-visible k; agent/model failed downstream), B4 synthesis = 70. So this is
  the *only* engine-owned failure class in the agent-utility eval, and it matters because it
  grows with scale — the direction the product's scale story depends on.
- **Conditions/caveats:** measured on the 707 fabricated-chain corpora (synthetic gold payload,
  uncamouflaged — see tempdoc 766's rebuild); the near-duplicate-decoy crowding is partly a
  corpus artifact (40 template-identical gold/decoy docs), so the fix lane (tempdoc 769) must
  verify against both the existing reproductions AND the rebuilt corpora (tempdoc 767). **Provenance
  (776 item 4):** the original replay-census magnitudes (13.4%; 6%→28% 1k→10k) were measured on the
  pre-767 `_FILLER`-leaked substrate; the RESOLVED-DECOMPOSED re-measurement below was run leak-free
  (#273), so the resolution stands on clean numbers while the headline census figures are historical.
  See the 767/776 Corpus provenance note. Related:
  F-025's legal leg-miss profile (leg_miss 0.28) and F-037's pack-curation disagreement — same
  neighborhood, different stages.
- **Owner / fix lane:** tempdoc 769 (engine lane, chartered 2026-07-21). Acceptance: the 17 B2
  cells' issued queries reach agent-visible top-k on replay, no regression on register baselines.
- **DIAGNOSIS UPDATE (2026-07-21, tempdoc 769 §E — M1/M2/M3, ~$0 on pinned campaign indexes):** the
  "near-duplicate decoys outrank gold" mechanism in this finding's headline did NOT survive the depth
  probe — burial is by genuine CLERC hard negatives; the root causes decompose as: (a) DOMINANT: a
  missing two-hop JOIN KEY — the answer doc is reachable ONLY via the designer name (oracle: rank 1–2
  in hybrid AND lexical for every qid once the name is queried), which structure-phrased queries never
  carry → routes to the tool-surface two-step affordance (tempdoc 770), not engine ranking; (b) a
  bounded, real fusion-leak residue — in 3/17 cells a leg had gold ≤20 (q3: vector both golds ≤10)
  while hybrid buried it to 23–24 (D-005 "fusion is a ranking step, not a recall gate"), and 10/17
  more had the bridge doc at rank 21–100 in a semantic leg; (c) 4/17 cells are representation-floor
  (no leg, any depth — F-030 territory, paraphrase barrier × weak-dense-on-legal), pending re-test on
  the 767 camouflaged rebuild (corpus-artifact modifier). Lane restructuring pending founder decision;
  the (b) residue is the surviving engine-owned item.
- **ROUTING CORRECTION (2026-07-21, tempdoc 770 §E.4):** (a) above routes to *"the tool-surface
  two-step affordance (tempdoc 770)"* — **770 will not deliver that affordance.** 770 measured that
  the MCP surface's `hit.id` **is the filesystem path** (byte-identical in 14,617/14,617 hits), so the
  proposed `justsearch_fetch(id)` reduces to `fetch(path)` — a tool returning a file the agent can
  already `Read`, in a channel it already has. `fetch` is **withdrawn**, not deferred. **What (a)
  actually needs is unowned:** the second hop requires the *bridge entity's name* to reach the agent
  so it can re-query. That is a retrieval/evidence-content question (does the returned excerpt carry
  the designer name?), not a tool-shape one — and 770's own §A.2 measurement points the same way:
  **50.3% of post-search Reads (N=862) targeted documents search never returned**, i.e. the dominant
  agent-side failure is retrieval coverage, not surface ergonomics. Do not wait on 770 for (a); it
  needs an owner.
- **OWNER ASSIGNED (2026-07-21, founder-approved routing):** tempdoc **771** (post-rebuild retrieval
  residue) now owns (a) the join-key/evidence-content item, (b) the fusion-burial residue, and the
  read-amplification item 770 routed out — all HARD-GATED on 767's rebuilt strata (every magnitude
  above is a property of the uncamouflaged corpus; re-measure before design). Tempdoc 769's original
  acceptance criteria are retired; its M5 floor-cell retest moves into 771 §C.1. F-039's resolution
  note lands when 771's re-measurement pass rules each component fixed/shrunk/representation-bound.
- **RESOLVED-DECOMPOSED (2026-07-22, tempdoc 771 §E — re-measured on the leak-free certified strata,
  PR #273):** F-039's "engine owns 13–28% of with-tool failures, growing with scale" decomposes on
  the rebuild into four components, none of which is a single fixable ranking bug: **(a) join-key
  affordance — CONFIRMED, not a defect**: the bridge entity name is a globally-unique token (df=1)
  and puts the answer doc at rank 1–2 (hybrid + lexical, 50/50) once queried, while structure-
  phrased queries structurally cannot; this *defines* the required two-step, it is not a burial.
  **(b) evidence-content — the surviving small engine/surface item**: the delivered excerpt carries
  the join entity in 93% of successful enron retrievals but only 45% of legal ones (long CLERC docs
  bury the bridge sentence past the ~4 KB preview), so on the F-039 domain even successful retrieval
  often can't seed hop-2 → scoped excerpt/evidence-content design (couples to F-038 passage
  machinery + read-amplification). **(c) fusion-burial — bounded, guarded**: 5.0% of queries / 9.8%
  of hybrid-misses (leg-visible-hybrid-buried @10), same order as the 769 census, did not grow,
  already ratcheted by the leak-gate → no new machinery. **(d) representation floor — DOMINANT at
  scale, NOT engine-fixable**: legal-10k gold is unreachable by any leg for 82–90% of queries
  (lexical dead post-camouflage, dense/splade can't bridge the legal paraphrase barrier —
  F-030(678), 708 *no model swap*). **Net:** the durable engine-owned residue is (b) the long-doc
  evidence-content excerpt gap (small, design-here) plus (d) the known encoder-domain floor (not
  new); F-039 is marked **resolved-decomposed**. No F-030 successor is opened (708 closed the
  encoder question); no new engine charter is licensed.

### F-038: RAG chunk retrieval was blind to chunkless (sub-2000-char) docs — a doc-level union leg into the PRIMARY RAG candidate set fixes it with no re-index; interactive hybrid on-baseline (tempdoc 749, 2026-07-18)

- **Answer:** `/api/chat/ask` (RAG) silently missed documents whose best answer lives in a short doc.
  `IndexingDocumentOps.indexChunks`/`ChunkDocumentWriter` write ZERO chunk docs for content
  < `CHUNK_THRESHOLD_CHARS` (2000) or that split into ≤1 chunk, and every `ChunkSearchOps` RAG query
  filters `IS_CHUNK:true` with no whole-doc union — so short docs (measured ~70% of the scifact
  corpus: 1557 chunk docs from 5189) were invisible to RAG chunk retrieval and fell back to
  whole-doc BM25 (`FULLTEXT_FALLBACK`/`NO_CHUNKS_FOUND`), which unscoped missed the best short doc.
  The interactive `/api/knowledge/search` path was unaffected (doc-level entry + embedding present).
  **Fix (option B, founder-ratified):** `RagContextOps.searchChunksWithMeta` now unions doc-level
  hits for chunkless parents into the PRIMARY candidate set (synthesized whole-doc chunk-shaped hits,
  flowing through the same rerank/diversify/budget/citation pipeline), before rerank — removing the
  RAG path's recall-gate character (D-005: "fusion is a ranking step, not a recall gate"). No
  re-index (fixes all existing indices at query time); no proto/FE change; interactive search
  untouched. Chunked-doc behavior unchanged (union restricted to parents with zero chunk docs, via a
  per-candidate existence probe; excluded/hidden parents dropped for fallback parity).
- **Evidence:**
  - **Live R9 (2026-07-18, worktree dist, scifact 5184-doc repro, Qwen3.5-9B, full enrichment):** the
    unscoped ask on a chunkless target (`1631583.txt`, ~1500 chars, doc-level hybrid #1 @score 1.0)
    → `retrieval_mode: CHUNK_HYBRID` (not FULLTEXT_FALLBACK), coverage 0.33, cites `1631583.txt`;
    worker log `bestScore=1.0` = the synthesized hit's native doc-level score (only the union leg can
    produce a CHUNK_HYBRID citation for a chunkless doc). Browser click-to-verify confirmed the
    citation resolves to the target's whole-doc source.
  - **Interactive no-regression (mandatory falsifier):** `jseval run --dataset scifact --modes hybrid
    --start-backend --clean --pipeline`, full enrichment (embed/splade 99.9%), observed legs
    `cross_encoder+dense+hybrid+query_classification`: **nDCG@10 0.7603** (P@1 0.637, R@10 0.888) vs
    register scorecard 0.760 / baseline 0.758 — Δ+0.002, within the ±2σ noise envelope. (The run's
    `summary.json` stamps `git_sha 5e195fe6`, the *parent* commit — the code changes were uncommitted
    in the working tree at eval time, so `runHeadlessEval` compiled them but jseval records HEAD; the
    number is the working-tree code, not the parent's.) **The load-bearing no-regression argument is
    structural, not the eval number alone:** the entire interactive-hybrid ranking path
    (`HybridSearchOps.java`, `TextQueryOps.java`, `ReadPathOps.java`, fusion) is **absent from the
    diff** (`git diff 5e195fe6 b59ef3a2 --stat`), so interactive ranking is byte-identical — the
    union leg lives only in `RagContextOps.searchChunksWithMeta` (the RAG path), and the ChunkSplitter
    tail-fix only changes new-build chunk counts. The 0.7603 = scorecard-0.760 result confirms the
    chunk-count change had no measurable interactive effect.
- **Conditions/caveats:** a first eval run scored 0.680 with `requested_dense_but_not_observed` — a
  **confounder, not a regression**: it omitted `--pipeline`, so jseval queried at
  `embeddingCoveragePercent 59.9%` (dense/splade only ~60% enriched → BM25+CE only). The 0.7603 run
  with full-enrichment wait is the valid comparison. The `buildFallbackWithVirtualChunks` path stays
  as the genuine last resort (both legs empty / FULL_DOCUMENT mode), firing rate expected to drop
  sharply.
- **RAG-path validation + `rag.union.enabled` flag (2026-07-18):** a default-ON flag
  `rag.union.enabled` now gates the union leg (operational off-switch + the tempdoc's own
  gating-remedy). Live RAG-path demonstration on a mixed index (280 long chunked + 24 short chunkless
  docs, union ON): `rag_reachability_probe` verdict **`ok`, 10/10 chunkless short docs reachable via
  CHUNK_HYBRID** — the reachability guard fired and passed on a real corpus. Running the probe live
  caught **3 real bugs in the W3 probe** (scoping to a corpus-id the backend keys by path; exact
  path-vs-id match; over-strict `top_k=5`) — it was non-functional for real corpus-dir corpora, a
  `static-green ≠ live-working` catch; all fixed + tests rewritten. Long-path non-disturbance is
  structural (the union's `withChunks` filter drops all chunked docs → long-doc retrieval is
  union-independent by construction). **Residual (named, not measured):** the pure adversarial
  same-query short-vs-long displacement needs a purpose-built topically-competing corpus that
  doesn't exist locally; low-probability under B (query-conditional injection + F-014 length-asymmetry
  handling + CE-not-in-CHUNK_HYBRID) and now mitigated by the off-switch.
- **Adjacent finds (rode along):** the ChunkSplitter emitted a redundant overlap-tail chunk for
  stripped lengths 385–1923 and for long docs' final segment (live on the virtual-chunk fallback);
  fixed. `modules/indexing` test runtime lacked `net.jqwik:jqwik-engine`, so the tempdoc-554
  `ChunkTilingPropertyTest` `@Property`s were silently never collected (inert-green) — fixed, and the
  now-live property test caught a second splitter corner (a trimmed all-whitespace tail whose
  adjusted span lands inside its predecessor).

### F-037: the MCP evidence pack's document universe came from a DEPRECATED sparse-only pre-search while `justsearch_search` ran hybrid — a two-retrievals fork at the pack's first stage; universe fixed, curation-stage disagreement remains open (tempdoc 731, 2026-07-14)

- **Answer:** `RemoteDocumentService.preSearchForDocIds` (the stage that selects the evidence
  pack's candidate documents for `justsearch_answer`) sent a bare `SearchRequest`, which the
  worker resolved via the deprecated-mode fallback to sparse-only+expansion, while
  `justsearch_search` runs the hybrid preset — so a document that hybrid ranks #1 could be
  structurally absent from the pack's 20-doc universe (observed live: the same verbatim query's
  search-#1 missing from its pack). Rank order was additionally discarded into a `HashSet`.
  **Fixed (731 I1):** the pre-search now single-sources the same hybrid `PipelineConfig` the MCP
  search path builds (`SearchPipelinePresets.expandPreset(SEARCH_MODE_HYBRID, …)`), order
  preserved end-to-end (`LinkedHashSet` + an order-preserving `RetrieveContextParams`), pinned by
  a wire-shape regression test.
- **Post-fix live measurement (honest):** top1-in-pack agreement on the 7 hop-doc probe queries
  (mixed/en-legal-clerc-1k-verbose) was still **0/7** — the residual disagreement is the
  **chunk-level curation stage** (chunk fusion selecting different parents within the aligned
  universe), plus hybrid rank instability across index rebuilds (same corpus, rebuilt index: a
  doc moved rank 1 → outside top-10). Universe alignment was necessary, not sufficient — the
  curation half is 731 I6(b)'s pre-registered $0 eval (agreement metric + gold-in-context), open.
- **Ranking-instability piece (issue 4):** two gratuitous nondeterminism sources fixed
  (job-claim order tie-break; SQLite RETURNING-order dependence removed); HNSW rebuild variance
  is inherent; the H4-B hypothesis (low-signal gating threshold amplifying small ANN variance
  into large rank swings) has a committed instrument
  (`scripts/jseval/experiments/determinism_instrument_731.py`) and awaits its two-pass live run.
- **Evidence:** tempdoc 731 (DESIGN 3.1/3.2 with file:line, PLAN); live probes in
  `scripts/jseval/tmp/725-ab/725-forensics/lease-probes-agreement-p0-p1.v1.json`; commits
  `b7c2fc2`, `fd2c98e` (branch `worktree-725-response-legibility`).

### F-036: live chunk-SPLADE revives the sparse leg 4.4× in isolation but is hybrid-NEUTRAL at +108% enrichment cost on legal-clerc-200 → default stays OFF (tempdoc 712, 2026-07-11; the live-tier answer to F-033/Q-017)

- **Answer:** the `rag.chunk_splade.enabled` flag realizes F-033's offline revival on the live
  engine's *isolated* sparse leg but the gain does not survive fusion. Clean same-session A/B on
  legal-clerc-200, both arms health-verified (`chunk_merge` leg active, dense identical 0.6187 vs
  0.6184 confirming comparability): `splade` mode 0.0591 → **0.2588** (4.4×); **`hybrid` (production)
  0.5625 → 0.5592** — flat, within noise, marginally negative; enrichment wall 132 s → 275 s
  (**+108%**). Short-doc control (battlefield-en-v1) flag-on hybrid 0.9517 = baseline, no regression.
- **Mechanism:** on this corpus the dense + cross-encoder signals already rank the relevant long
  docs, so the revived sparse leg overlaps rather than adds at the fusion stage — the same
  isolated-win-doesn't-fuse pattern as F-004 (mode optimality is corpus-dependent) and CE-hurts-enron.
- **Decision:** default **OFF**; the flag is a corpus-specific lever for a future sparse-dominant
  workload. The 712 foundation (the flag + the fix for chunk docs being silently marked
  splade-COMPLETED without encoding) shipped in #145 regardless of the default.
- **Evidence:** tempdoc 712 §Step-4 live A/B (two runs — one confounded by the tempdoc-717
  anomaly, one clean); reproduction commands + per-arm summaries/worker-logs archived. First-tier
  offline result is F-033; this is its live-tier resolution.

### F-033: the SPLADE (sparse) leg's ~0.059 on legal-clerc-200 is substantially a 512-token TRUNCATION artifact — per-chunk SPLADE revives it 6–10× offline; the sparse sibling of F-031/F-032 (tempdoc 712, 2026-07-11; refines F-030(678) for the sparse leg)

- **Answer:** production SPLADE hard-truncates every document to `maxSeqLen=512` tokens
  (`SpladeEncoder.encode`/batch paths: `seqLen = min(len, maxSeqLen)`; the `SpladeTruncationEvidence`
  sidecar only *records* the loss, never windows). On legal-clerc-200, 194/198 docs exceed 512
  tokens (median 6,615 → the encoder sees ~7.7% of the median case doc). An offline A/B on the
  **byte-identical** corpus (corpus.jsonl sha256 `630f5376…`, same as F-032) using the
  production-shipped ONNX model (`models/splade/naver-splade-v3` = opensearch-neural-sparse-encoding-
  multilingual-v1) measured: **A truncated whole-doc nDCG@10 0.0539** (reproduces the shipped
  splade-mode 0.0591 — fidelity anchor), **B per-term max-pool chunk-merge 0.3274 (6.1×)**,
  **B chunk-level MaxP 0.5445 (10.1×)**. Recall drives it: R@10 0.14→0.775, R@100 0.69→0.945.
  Anti-dilution signature: `sum`-merge (0.089) ≪ `max`-merge — the relevant terms are in *some*
  chunk, not diluted across all. Per-query B beats A 104–15 (max) / 154–6 (MaxP).
- **Refines F-030(678):** 678's granularity arm was a product-RAG A/B on dense+BM25 chunks and
  never measured chunk-level SPLADE, so its "encoder-domain mismatch at any granularity" verdict did
  not cover this. The multilingual SPLADE encoder *does* separate legal content at chunk
  granularity; the deadness was representation (truncation). Consistent with the dense sibling
  (chunk-CLS MaxP 0.64, 691 §M). The residual gap (sparse chunk-MaxP 0.545 vs lexical 0.686) stays
  with the encoder-domain question (708's lane).
- **Caveat (offline ceiling):** B numbers are offline exact-retrieval ceilings (no ANN / Lucene
  saturation / fusion), same treatment as the dense 0.64 datapoint; the load-bearing result is the
  **A/B delta**. The engine-integrated number is unmeasured (see Q-017).
- **Reproduction:** `PYTHONUTF8=1 python scripts/jseval/experiments/splade_chunk_truncation_check_712.py
  --dataset-dir datasets/mixed/legal-clerc-200 --model-dir <models>/splade/naver-splade-v3
  --out tmp/712-splade-check --device cuda --batch-size 8`; artifact `tmp/712-splade-check/results.json`.
  Evidence: tempdoc 712 §Takeover experiment.

### F-030(706): scanned-PDF OCR execution engine replaced (tempdoc 706, 2026-07-10) — extraction-content comparability boundary

- **Finding:** Tika-internal serial per-page tesseract OCR was replaced by an owned parallel engine
  (`PdfOcrEngine`: 300 DPI GRAY, bounded pool, one spawn/page, per-document budget, forceful child
  kill). Measured on 686-corpus scans: 77p stall doc 113.9s → 16.8s (6.8×) with **100% of the
  before-run's unique word vocabulary retained** (strict superset — the new path also merges the
  baseline text layer where the old primary path replaced it, and recovers documents the old
  Tika-internal pass silently failed on). Config defect fixed alongside: absent OCR config
  (eval/headless + likely packaged installs) previously meant NO page cap or image guards; now
  absent = safe defaults (30s/50p), yaml+code unified.
- **Measurement relevance:** extraction-quality or agent-utility measurements over scanned/mixed
  PDFs are **not content-comparable across this commit boundary** — OCR'd documents can gain
  substantial content (text-layer merge + recovered pages). Retrieval-side analysis and
  `extraction_method`/reason-code semantics are unchanged (671 classifiers green unmodified).
- **Evidence:** tempdoc 706 §Execution log (before/after harness, word-overlap parity).

### F-030(678): dense/SPLADE death on legal-shaped retrieval is an ENCODER-DOMAIN MISMATCH — not gating, not query length, not granularity, not query naturalness (tempdoc 678 §Pillar-5 campaign, 2026-07-10; answers Q-015)

- **REFINEMENT (2026-07-11, tempdoc 708 closure — original text below kept intact; annotate-don't-rewrite):**
  the "encoder-domain mismatch" verdict is **superseded in mechanism** by F-031 + F-032 + F-034: the
  encoder (gte-multilingual-base) was never domain-limited on legal text. The dense death this finding
  attributed to the representation decomposes into (a) **window-mean whole-doc construction dilution**
  (F-031: one long-context pass lifts legal vector 5-6×, shipped default-on) and (b) **chunk vectors
  silently destroyed post-write** (F-032: `chunk_vector` 0/4,293 present at the HEAD every pillar-5 probe
  ran against; RMW-policy fix ships legal vector nDCG@10 0.6180). In particular, this finding's "chunk
  granularity adds only +3.0 pts" clause is an **artifact of F-032** (the chunk-hybrid arm had zero chunk
  vectors) — the corrected measurements are offline chunk-MaxP **R@10 0.855 / nDCG@10 0.643** with the
  same incumbent encoder (708 bake-off, Gate-0-anchored) and shipped post-fix vector **0.6180** (F-032).
  A dated correction annotation sits on tempdoc 678 §E5-D itself. What SURVIVES of this finding: the raw
  measurements (accurate for that HEAD), the gate/fusion exoneration (702), the BM25-verbosity
  monotonicity, the SPLADE profile (still unrecovered — splade 0.0591 at b88e76e), and the RAG-surface
  product finding. The encoder-choice question it spawned (708) closed **NO MODEL SWAP** — see F-034.
- **Answer:** a four-stage elimination campaign on `mixed/legal-clerc-200` (198 docs / 200 queries,
  fixed qrels throughout, all runs `comparable=True`, staged-recall reconciliation 0 mismatches)
  attributed F-029's dead semantic legs. Raw pre-fusion R@10 by query shape: dense **0.100**
  (verbose citing sentences) / **0.145** (deterministic keyword top-8) / **0.145** (LLM-reduced
  natural short phrases, Qwen3.5-9B temp=0, 0 fallbacks); SPLADE ≤0.165 at every shape. Chunk
  granularity adds only **+3.0 pts** (product RAG surface A/B via `JUSTSEARCH_RAG_RETRIEVE_MODE`:
  chunk-hybrid 0.710 vs chunk-bm25 0.680 gold-in-context, probe validated against jseval's own
  hybrid number first). Gate/fusion was exonerated first: tempdoc 702's EUCLIDEAN/COSINE threshold
  recalibration (bytecode-confirmed real, shipped PR #121) changed NOTHING measurable on four
  corpora — the miscalibration was latent (dense top-1 clears even the wrong gate where dense
  works), and post-fix legal hybrid is unchanged (0.517 ≈ 0.521). **What remains is the
  representation: gte-multilingual (and the multilingual SPLADE encoder, same profile) does not
  separate legal case documents by citation-relevant content, at any query shape or granularity.**
- **Secondary findings:** (1) BM25 is **monotonic in query verbosity** on CLERC — R@10 0.630
  (keyword) → 0.780 (natural short) → 0.855 (verbose): the citing sentence's context is
  load-bearing for the lexical leg, so query reduction is NOT a free lever; any query-side
  mechanism must be per-leg (constrains 678's lever design and 707's query construction).
  (2) Positive product finding: the chunk-first RAG surface reaches **0.68 gold-in-context within
  ~2.9 documents** on legal text via lexical chunks alone — the ICP shape is served today, riding
  BM25. (3) CLERC docs are extreme-length (median 28.5k chars, 97% chunked) — maximal whole-doc
  mean-pool dilution conditions, yet granularity still wasn't the lever.
- **Conditions/caveats:** one corpus family (CLERC citation-retrieval); one encoder family tested.
  The verdict routes to a model/representation investigation (704 names it a new unowned piece;
  636/580-adjacent) — NOT to corpus design (707 proceeds on its Branch B: measure the engine
  as-is, no design flatters dense), NOT to 678's query lever (which remains live for corpora where
  dense works), NOT to 639/ANN (ruled out, 701 E2). Evidence: tempdoc 678 §Pillar-5 (E5-A..E5-C-v2
  results tables + run-dir pointers), tempdoc 702 §B.7, tempdoc 707.
- **Reusable instrument:** `jseval corpus-query-variant` (deterministic `keyword` + recorded-params
  `llm-reduced` transforms) — query-shape sweeps on any local dataset are now a one-command
  operation (PRs #123/#125).

### F-029: size-robustness is CORPUS-DEPENDENT — repetitive-real legal text degrades where diverse Wikipedia is flat; dense+SPLADE near-dead on CLERC at every size (tempdoc 701 probe, 2026-07-10)

- **Finding:** an E4-style fixed-query volume sweep on REAL legal text (CLERC, byte-identical 200
  queries, 198 → 4,000 docs via the new `corpus-fetch-clerc --n-docs` distractor sampling) measured
  `leg_union_recall` **0.875 → 0.705** (LEG_MISS 0.10 → 0.295), final_recall 0.865 → 0.685, full nDCG
  0.681 → 0.507. This **scopes F-028's provenance claim**: "size-robust on realistic corpora" holds for
  *diverse* text (MIRACL flat 3k→10k) but NOT for repetitive/domain boilerplate — the paying-ICP shape.
  Mechanism differs from both synthetic mechanisms: fusion holds (leak ≤0.035); the loss is
  **completeness decay via BM25 dilution**, because on this corpus the engine de facto rides the lexical
  leg alone — **dense R@10 0.10→0.03, SPLADE 0.15→0.005 at BOTH sizes** ("hybrid" is effectively
  BM25-only on CLERC-shaped legal retrieval). Working hypothesis for the dead semantic legs: CLERC's
  long citing-sentence queries = FW-003/678's verbose-query dilution at its extreme, compounded by very
  long case docs — needs its own attribution pass (678-adjacent; deliberately not opened in 701).
  Caveat: the 4k full-mode number is `comparable=False` (`ann_proof` dense-evidence 0.455), but the drop
  is carried by the lexical leg (no vectors involved) and the 198-doc point is fully comparable.
- **Gate significance:** legal-clerc-200 pins the union floor at 0.87; the same family at 4k measures
  0.705 — live proof of the completeness-floor's sensitivity design (F-028).
- **Bycatch (fixed on the 701 branch):** first contact with this corpus at 4k live-reproduced a worker
  enrichment **crash-loop** — `EmbeddingBackfillOps` + 4 sibling batch paths trusted batch-result
  length; an empty result (backfill racing provider init after a worker restart) threw AIOOBE before
  any failure-marking → eternal batch refetch, 199/199 doc embeddings starved. Guarded (null-or-mismatch
  → per-item fallback), 5 sites, new test fixture, module suite green.
- **Runs:** `tmp/eval-results/20260709T235522_mixed_legal-clerc-200` + `20260710T001438_mixed_legal-clerc-4k`
  (regenerable; recipes committed). Probe design + full table: tempdoc 701 §Repetitive-real probe.

### F-028: recall-survival's completeness half now has a FLOOR gate — the guard triad is complete (tempdoc 701, 2026-07-08)

- **Finding:** F-025 gated the recall funnel's *leak* half (`leak-gate`, a ceiling on cascade-leak). Its
  first stage — **representation completeness** (`leg_union_recall`: did ANY retrieval leg surface the gold
  before fusion/ranking; the LEG_MISS bucket) — was measured by `staged_recall_accounting` and profiled by
  `recall-profile`, but **gated by nothing**; `leak-gate` structurally cannot catch it (fewer golds retrieved
  does not *raise* `leak_rate` — it can lower it). Tempdoc 701 added **`jseval union-recall-gate`**: the
  floor-shaped sibling of `leak-gate` (fails when `leg_union_recall < pinned floor − tolerance`), reading the
  same projection. This completes the **recall-survival guard triad** — quality floor (relevance/nDCG) ·
  **completeness floor (union-recall)** · leak ceiling — and makes union the **fourth** engine ratchet on the
  `search-engine-hint`. Floors are measured-derived (`union-recall-gate-baselines.v1.json`, pointer+fallback
  like leak's), pinned on reproducible corpora **mixed/legal-clerc-200 0.87 + beir/scifact 0.96 +
  golden/needle-burial-v1 1.0** (tol 0.05).
  Non-redundant with nDCG: on hard corpora a completeness collapse compresses into nDCG's near-zero range
  (~14× sensitivity gap, tempdoc 701 §U1), so `relevance-gate` can miss what `union-recall-gate` catches.
- **Provenance / context:** the 624 "retrieval collapses at scale" signal was investigated and resolved as a
  **synthetic-corpus artifact** — the engine is *measured* size-robust on realistic corpora 3k→10k (MIRACL/de
  recall flat, `final_recall` 0.967→0.967) while only the adversarial near-identical synthetic corpus collapses
  — so no size-dependent product defect exists in the tested range; the completeness gate is the durable
  standing-guard deliverable, not a fix. ANN recall decay was empirically ruled out as the mechanism (§E2).
- **Deferred (documented in 701):** growing the pin set (legal-clerc / enron-qa) via further
  `union-recall-gate-derive` runs; release-projection compose plumbing exists but is inert until a deliberate
  release recompose; a **user-visible low-confidence signal** and a **large-N (10⁵–10⁶) standing guard** are
  parked (the latter is impractical as a routine ratchet — a 639-owned periodic one-off).

### F-027: ARM-INVALIDATED (2026-07-03) — the "certified null" was an A-vs-A replication: condition B never received the MCP tools (dead config, silently dropped by the CLI); the true U0 question is REOPENED

- **INVALIDATION (2026-07-03, 624 twenty-third pass — read first):** a five-agent mechanism
  investigation over the records' own per-cell tool-call traces found **zero MCP invocations in all
  260 B cells**: the harness `mcp.json` lacked `"type":"http"` and the Claude CLI silently drops such
  entries (A/B-probe proven; independently verified five ways). Condition B was behaviorally condition
  A; condition C (in the July-2 records) had no tools at all — its "significantly harmful" finding is
  likewise arm-invalidated. The records below remain valid *as a governed A-vs-A noise-floor
  measurement* (pooled Δ−0.027, p=0.476 between two identical arms = the methodology's empirical seed
  noise), and every mechanically-governed property (comparability, loss-accounting, judge, panel)
  stands — but **no agent-utility conclusion may be drawn from them**. Standing mechanism findings
  that survive: the task is pure retrieval (oracle ceiling 95-99%); the engine wins the decisive hop-1
  paraphrase step at 90-96% top-3 with one reformulation (69% verbatim — the gap is tempdoc 678),
  language-invariantly, while the file-tools agent wins it 78% EN / 51% DE stochastically. Honest
  projection for a REAL with-tool arm: EN parity-to-modest-gain, DE +0.2 to +0.4 — to be measured, not
  assumed. Records annotated via `revision.reason=arm_invalidation`; harness now fail-fasts on the
  config shape and asserts the offered tool surface per cell.
- **Original answer (superseded interpretation, numbers accurate as an A-vs-A measurement):** The first fully comparable agent-utility records (tempdoc 624, 2026-07-03; conditions
  A vs B, 5 seeds × 26 queries per corpus, haiku, calibrated + leak-scan-excluded + judge-scored):
  **pooled across `golden/battlefield-{en,de}-v1`, n=260 paired, B accuracy Δ −0.027 (McNemar
  p=0.476); tokens a wash** (median Δ ≈ +449 mean, CI crosses 0). Per-stratum: EN Δ −0.069 (p=0.200),
  DE Δ +0.015 (p=0.860) — both n.s., opposite directions. **Neither benefit nor harm is demonstrated**
  for adding JustSearch MCP to a haiku agent that already has file tools, on this battlefield. The
  prior borderline-negative (Δ −0.094, p=0.055, leak-free 3-seed reanalysis) washed out on the clean
  5-seed harness.
- **Evidence:** `scripts/jseval/624-run-2026-07-03/out-{en,de}-judged/utility-comparison.v1.json` +
  `out-cross-corpus/` — `comparable=True`, 0 excluded cells any arm, `tool_call_assertions` 520/520
  cells with tool data / 0 disallowed violations / 0 leak suspects, judge `hybrid-em-llm` with 0
  verdict flips (169 misses judged), public git SHA `4dcf510`, populated MCP tool-surface hash.
- **Conditions/caveats:** haiku-only (binding cost policy); EN+DE text battlefield only — the
  degraded-scan member is still pending its post-672 fidelity re-verify AND a corpus-dir pollution
  cleanup (agent-authored solver artifacts, see tempdoc 624 twenty-first pass — the same pollution
  class inflated DE's pre-cleanup baseline 0.82→0.56). **Corpus-leak provenance (776 item 3):**
  `golden/battlefield-{en,de}-v1` carry an id-shape enumeration leak (gold docs occupy
  `trailing_int(id)` 1..78, distractors 79..390; `trailing_int(id)<=78` selects gold at P/R 1.0 via
  materialized `<doc_id>.txt` filenames). This did not bias the A-vs-A Δ above (both arms held file
  tools symmetrically, and B reached no MCP), but **any future real-with-tool arm on this battlefield
  is confounded** — an agent can enumerate gold by filename numeric threshold; the honest projection
  (EN parity-to-modest-gain, DE +0.2 to +0.4) must be measured on a de-leaked corpus. See the 767/776
  Corpus provenance note. §M.8 items outstanding: the cross-family
  grader-panel calibration (item 3) and the claim text (item 6, must use the §M.7a-3 null framing).
  No number publishes until the full bar clears (624 boundary (a)).
- **Update (2026-07-03, 624 twenty-second pass):** §M.8 item 3 is CLOSED — the judge is calibrated
  against a fully-local two-model cross-family panel (Mistral-7B + Gemma-2-9B via 674's serial-swap
  seam, run live on the dev stack): EN judge-vs-panel kappa=1.0 / panel-mutual 1.0; DE 1.0 / 0.944
  (n=36+4 abstained each, non-degenerate, labeled "cross-family-llm, NOT human"). The scan member is
  now measured **unbuildable at its shipped degradation band** — the band defeats the product's own
  extraction stack too (the VLM hallucinates plausible text on unreadable scans and that text is
  INDEXED as real content — a separate production quality finding), so fidelity is honestly 0.0 and no
  run spend applies until a pipeline-readable/agent-unreadable band is found (a research question).
  Only §M.8 item 6 (founder sign-off on the null claim text, drafted in the tempdoc) remains.

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

### Q-001: Why does CE hurt on personal email? → MECHANISM SUBSTANTIALLY ANSWERED → F-041

- **Disposition (2026-07-22, tempdoc 774):** the dominant mechanism is **preview-blindness**
  — the CE judged `title + doc-head preview snippet`, not the matching content; with the
  winning passage as input (`search.evidence_preview.enabled`, default-off) the CE flips
  to +5.9% on enron-qa (0.7445 → 0.7882, F-041). The original per-query-analysis question
  is retired; any residual CE-hurts effect should be re-measured only under evidence-
  coherent input.
- **Question (historical):** What is the mechanism by which cross-encoder reranking degrades nDCG on EnronQA by ~2%?
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

### Q-015: Why do the dense and SPLADE legs collapse on the legal corpus (nDCG@10 ≈ 0.06), and what engine change recovers them? → ANSWERED → F-030(678)

- **Answered (2026-07-10, tempdoc 678 §Pillar-5, campaign E5-A..E5-C-v2):** the collapse is an
  **encoder-domain mismatch** — see F-030(678). Eliminated in order: gate/fusion capping (E5-A/B, with
  tempdoc 702's threshold recalibration eval-gated and shipped as correctness-only, PR #121), query
  length (E5-C keyword control), doc granularity (E5-D: dense adds only +3.0 pts at chunk level),
  query naturalness (E5-C-v2 LLM-reduced). No engine change recovers the legs short of the encoder/
  representation itself — a model-level question, flagged in 704 as a new unowned piece. ANN was
  already ruled out separately (701 E2).
- **Original question (retained):** On `mixed/legal-clerc-200`, `vector` scores 0.060 and `splade` 0.059 while `lexical`
  scores 0.686 (register baselines, 666) — two of three legs are effectively dead on a real,
  citable legal-retrieval benchmark, and the production `hybrid` (0.516-0.521) underperforms plain
  BM25 there. Is this an embedding-truncation/long-document representation failure, a
  citation-style-query mismatch, a chunking/whole-doc granularity issue, or an ANN/recall problem?
- **Why it matters:** a whole leg family failing on a domain is the largest single scorecard gap the
  register currently records, and legal/long-document content is a core personal-files domain. Any
  fix also feeds the published benchmark numbers directly (grant-claims relevant, 624 lineage).
- **Prior art / adjacency:** the retired courtlistener corpus showed the same BM25-dominance shape;
  639 (candidate-set integrity / ANN recall, stub) owns the ANN-recall sub-question and should not be
  forked — a diagnosis pass here should attribute the collapse before 639 or a new doc claims the fix.
  F-023/F-025's staged-recall instrumentation (leg-recall decomposition) is the ready-made diagnostic.
- **Suggested approach:** run the staged-recall/leg-recall decomposition on legal-clerc; inspect
  whether gold docs are even embedded/indexed at useful granularity (doc length vs encoder window);
  compare chunk-granularity retrieval; then route the fix to its owner (639 for ANN/dedup, a new doc
  for representation/granularity if that's the finding).
- **Refinement (2026-07-11, tempdoc 712 → F-033):** the SPLADE half of F-030(678)'s "encoder-domain
  mismatch at any granularity" is narrowed — 678 never measured chunk-level SPLADE, and per-chunk
  SPLADE revives the sparse leg 6–10× offline. The sparse deadness is substantially truncation, not
  domain. The dense half stands as F-031/F-032 scoped it.

### Q-017: Does the offline chunk-SPLADE revival (F-033) hold once integrated into the live engine (ANN + Lucene FeatureField saturation + fusion), and at what enrichment cost? → ANSWERED (tempdoc 712, F-036; verdict: keep default-OFF)

- **Question:** F-033 measured, offline, that per-chunk SPLADE lifts legal-clerc-200 sparse nDCG@10
  from 0.054 (production-mirror truncated) to 0.327 (max-pool doc-merge) / 0.545 (chunk-MaxP). Those
  are exact-retrieval ceilings. Does the engine-integrated chunk-sparse sub-leg realize a comparable
  gain in `splade` and `hybrid` mode against live gates, and what is the enrichment-throughput cost
  of the ~19× SPLADE forward-pass multiplier once amortized over already-enriched chunk docs?
  As-built shape (712 steps 1–3, 2026-07-11): the search side already existed
  (`searchChunksSplade` over the existing `splade` FeatureField on chunk docs, fused via
  `chunk_merge`); the fix is producer enrollment — the combined pass now encodes chunk docs'
  `chunk_content` instead of silently marking them COMPLETED — behind
  `rag.chunk_splade.enabled` / `JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED`, **default OFF**.
- **Why it matters:** it is the truth-tier confirmation of F-033 (`static-green ≠ live-working`) and
  the go/no-go for the default-on flip of the chunk-sparse flag. It also feeds (with tempdoc 713's
  parent-representation verdict) whether the parent whole-doc `splade` encode on chunked docs still
  earns its place.
- **713 verdict delivered (2026-07-11, F-035):** the dense parent earns its place — branch fusion
  always consumes the whole-doc branch, so a degraded parent representation actively dilutes the
  chunk branch (−0.204 vector without the single-pass). The same structural argument plausibly
  applies to the sparse parent, but 712 should measure it, not inherit the dense result.
- **ANSWER (2026-07-11, live A/B on legal-clerc-200, both arms health-verified chunk_merge-active
  → F-036):** the flag revives the sparse leg in isolation but does NOT improve production hybrid,
  at more than double the enrichment cost. Clean same-session arms (both healthy): `splade`
  0.0591 → **0.2588** (4.4×, real); `vector` 0.6187 → 0.6184 (unchanged — flag doesn't touch dense,
  which cross-validates arm comparability); **`hybrid` 0.5625 → 0.5592 (flat/noise-negative)**;
  enrichment wall 132 s → 275 s (**+108%**). Short-doc control (battlefield-en-v1, flag-on) hybrid
  0.9517 — matches its baseline, no regression. **Verdict: keep default-OFF.** The isolated 4.4×
  sparse-leg gain is fully absorbed by the dense+CE signals in fusion on this corpus (the
  F-004/CE-hurts-enron pattern); the flag stays an available corpus-specific lever. The 712
  foundation (flag + the silent data-less-COMPLETED fix) is already shipped (#145).
- **Caveat surfaced:** the first (confounded) A/B arm and 713's control both hit an INTERMITTENT
  fresh-build anomaly where the whole `chunk_merge` leg is silently absent (vector 0.34 not 0.62).
  Chartered separately as **tempdoc 717** — it is a defect on the shipped default path, unrelated to
  this flag. **717 resolution (live probe, 2026-07-11) — NOT vector loss:** the probe reproduced the
  degenerate build and proved the chunks + `chunk_vector`s are 100% healthy; the `chunk_merge` leg is
  skipped at query time via `SKIPPED_SHORT_CORPUS` because a SPLADE-load race at index time leaves
  `parent_token_count` unpopulated on a small fast-indexed corpus → `CorpusProfile` sees median 0 →
  a long corpus is mis-classified "short". Fixed by always-populating `parent_token_count` (an
  index-time estimate fallback so it never depends on the SPLADE-load race) + making `isShortCorpus`
  fail-open for chunks on unreliable token data. Live-validated: 3/3 fresh builds healthy (vector
  ≈0.62, `chunk_merge` present). This retires the 712/713 hand-checked `chunk_merge`-leg convention
  (the anomaly can no longer arise). The `chunk_vector` presence-truthful readiness/coverage/serve
  gates that shipped alongside are **complementary F-032-class hardening** (they close a latent
  "status lies" gap), NOT the fix for this bug — they read 100% vector coverage on the degenerate
  build and cannot catch a short-corpus leg-skip.
- **Design/plan:** tempdoc 712 (§Mechanism correction + §Implementation log; steps 1–3 landed
  flag-gated default-off on branch `worktree-712-sparse`).

### Q-014: Does any procedurally-generated `golden/` corpus clear the descriptor-collision gate, and is any of them suitable for an agent-utility (not just retrieval-quality) measurement?

- **Question:** 664's twelfth pass measured the descriptor-collision check **FAILING on all 5 procedurally-generated corpora** (`needle-burial-v1`, `synth-tabular-v1`, `synth-multiling-de-v1`, `synth-multihop-prose-v2`, `synth-code-v1`; 17–27 colliding groups each, `needle-burial-v1` specifically has 7 of its 20 gold chains affected) and left the fix out of scope ("a deeper generator-logic change"). For a retrieval-quality profile this is a tolerable caveat; for an **agent-utility accuracy** measurement (tempdoc 624) it directly corrupts the paired metric (a hostile reviewer can point to a distractor indistinguishable from a "gold" query). Also open: 635's own suite found 3 of its 4 members (code/tabular/German) are **grep-trivial by construction** (verbatim entity-name queries) and unusable for an agent-utility delta at any scale.
- **Why it matters:** Tempdoc 624's 2026-07-02 methodology plan (§M.2/§M.3) promotes the collision fix from "known, deferred" to a **blocking prerequisite** for any future agent-utility spend, and separately calls for a **real-scale** (hundreds–low-thousands of docs) + **OCR-only-accessible** battlefield the current suite doesn't have at all — see 624's own §U0 framing (does the realistic file-tools-plus-JustSearch arm's near-null effect survive a harder, more representative battlefield, or does a small/clean/easy corpus just hide a genuine null).
- **Status: ANSWERED (2026-07-02, tempdoc 624 §As-built #5-#8).** Both halves resolved by construction,
  not detection: the generator now excludes gold-reserved descriptors from the distractor draw and draws
  from a combinatorial 6,240-combination descriptor space (up from 312) — measured 0 gold-involved
  collisions across 20 seeds (§T.1 design, shipped). Two purpose-built agent-utility battlefield corpora
  exist and are certified (closed-book ≈0.000, fidelity in-band): `golden/battlefield-en-v1` (390 docs,
  nDCG@10 0.4143 "hard") and `golden/battlefield-de-v1` (390 docs, 0.5924 "moderate"); a degraded-scan
  member (`golden/synth-scan-v1`, 360 docs) exists but is gated on a fidelity re-verification through the
  tempdoc-672-fixed VDU extraction path before any use. The regeneration-determinism verifier itself was
  found unreliable (an ambient stale editable install shadowing worktree code in its subprocess probe —
  624 twentieth pass, fixed) — corpus signatures verified intact (recorded == on-disk == regenerated).
  Interim agent-utility numbers from these corpora exist but every record is `comparable=False`
  (internal only, per 624 §M.8); the certified 5-seed run is the pending step, not a corpus question.

### Q-018: Why does German semantic bridging collapse at 10⁴ docs (de-miracl 10k hybrid 0.043, union recall 0.40 → 0.10) on post-F-031/F-032 code, while EN legal/email stay in-band at the same scale?

- **Question:** On the 707 DE member (real German Wikipedia distractor mass + fabricated v2 gold at
  hops=1, pure zero-lexical-overlap synonym descriptors), the semantic leg bridges at ~half CLERC
  strength at 1k (hybrid 0.2053/0.2660) and goes dark at 10k (0.0431/0.0428) — measured on one
  engine cohort ≡ origin/main post-#201, i.e. WITH the F-031/F-032 construction fixes shipped, and
  with the EN members healthy on the same cohort as positive control. Is it (a) the incumbent
  encoders' German representation quality, (b) a scale/candidate-depth interaction (ANN/fusion
  starving a weak-but-nonzero leg), (c) the DE v2 gold design (zero-lexical-overlap descriptors =
  a strictly harder task than CLERC/email gold — corpus artifact), or (d) German text mechanics
  (compounding/tokenization) in chunking/indexing?
- **Why it matters:** it caps the multilingual story U0 wants (DE is 1k-only, never claim-bearing
  until resolved) and decides whether the honest scoped claim for German at scale is an engine fix,
  a knob, or a gold-design revision. The lexical zero is the *pre-registered, confirmed*
  grep-collapse prediction — not the finding; the semantic-leg collapse is.
- **Already eliminated (do not re-test):** chain length (v2 hops=1 parity moved 1k only +0.02–0.04,
  10k not at all); query shape (≤0.06 delta at 1k, ≈0 at 10k); F-031/F-032 (fixed pre-cohort);
  708's EN-legal bake-off verdict is untouched (708 never measured German at 10⁴).
- **Owner / approach:** tempdoc 748 (charter, 2026-07-16) — four ~$0 experiments, cheapest first:
  an EN gold stratum with the identical zero-lexical-overlap construction (decides (c) without
  touching the encoder question), staged-recall decomposition on the existing 10k run artifacts,
  candidate-depth sweep, DE chunk-granularity probe.
- **PROVENANCE CAVEAT (2026-07-22, 776 item 3-4):** the de-miracl collapse figures above (10k hybrid
  0.043, union recall 0.40→0.10; 1k 0.2053/0.2660) were measured on a **leak-inflated substrate** —
  unlike the two English members, de-miracl was **not** rebuilt, and its fabricated gold still carries
  the English `_FILLER` block in 240/240 docs per cell among real German hosts (a gold-selective leak,
  verdict LEAKY). Since the analogous legal-1k filler inflated measured retrieval ~1/3 (767 §Q), the
  *true* semantic-bridging numbers are likely even weaker than quoted — the collapse is real, its
  magnitude is not reproducible. **748's charter should re-verify every experiment on a defillered
  de-miracl rebuild** before drawing (a)/(b)/(c)/(d). See the 767/776 Corpus provenance note.

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
| 6   | **Dense Embedding**      | `EmbeddingService` (ONNX Runtime, `OnnxEmbeddingEncoder`) | gte-multilingual-base, 768-dim; `vector` (whole-doc) + `chunk_vector` (per-chunk). Chunked (>2,000-char) docs get `vector` from a single long-context pass (≤8,192 tokens, batch-1, default-on — tempdoc 691/F-031; window-mean fallback for over-limit/arena-OOM docs and when `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED=false`) |
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
| 6   | **BM25 Search** ‖                     | Lucene `Query`-based retrieval; fetches 10× limit for over-retrieval (capped at `candidate_limit_max`, default 100)                                                  |
| 7   | **Dense KNN Search** ‖                | `KnnFloatVectorQuery`; fetches 10× limit (capped at 100); pre-filtered by runtime filters                                                                           |
| 8   | **SPLADE Search** ‖                   | `FeatureField` query with learned sparse weights                                                                                                                    |
| 9   | **CC / RRF Fusion**                   | **CC** (default): min-max normalized convex combination with per-leg weights; **RRF** (alternative): `score = Σ(weight / (K + rank)) + bm25_boost × raw_score` (K=60, vectorWeight=0.75). 3-way variant (`fuseWithCC3`) available when SPLADE is active |
| 10  | **Low-Signal Gating**                 | Caps vector-only results (default 3) when vector top score <0.40; prevents semantic hijack                                                                          |
| 11  | **Stop-Word Short-Circuit**           | Skips vector search for trivial queries (<4 chars or single stop words)                                                                                             |
| 12  | **Fuzzy Correction**                  | Two-stage on SIMPLE queries: (a) full Levenshtein fuzzy retry fires when the query returns **zero** hits; (b) per-term augmentation of zero-`docFreq` terms fires when the query returns **nonzero** hits (can still change which document ranks first) |

‖ = parallel execution

### Post-Retrieval (Worker — `SearchOrchestrator`)

Stages 13a–13c implement **two-branch fusion**: a whole-document branch
(stages 6–9 above) and a chunk branch (13a–13b) are independently scored,
collapsed, and then merged in 13c. This replaces the earlier single-pass
RRF chunk merge.

| #    | Stage                                      | What It Does                                                                                                                                                                                                                                                |
| ---- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 13a  | **Chunk Branch Retrieval** (Stage 3a)      | Parallel chunk-level BM25 (`searchChunksText`), KNN (`searchChunkVector`), and SPLADE (`searchChunksSplade`) within `executeChunkBranchFusion`. Budget starts at `limit × CHUNK_INITIAL_CANDIDATE_MULTIPLIER`; retries at higher budget if any leg saturates |
| 13b  | **Chunk 3-Way CC Fusion + Parent Collapse** | `fuseWithCC3` combines the three chunk legs via min-max normalized convex combination. SPLADE weight is modulated by `parent_token_count` (full weight ≤1,024 tokens, zero ≥4,096 tokens — compensates for SPLADE's 512-token truncation). Results are collapsed to parent doc ID: best chunk score wins; evidence debug scores (`chunk_sparse`, `chunk_vector`, `chunk_splade`) aggregate by max across sibling chunks |
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
arbitration). Optionally applies MMR diversification (opt-in via
`rag.diversify.mode=mmr`; positional diversification is the default) to reduce
redundant passages, then assembles context within a token budget. Falls back to full-document
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

> **Note on "stemming":** the "stemming fallback" row above is a legacy label for the SIMPLE-syntax BM25 text-query path, **not** a linguistic stemmer. The analyzer chain is `ICUTokenizer → ICUNormalizer2Filter → LowerCaseFilter` with no stemming step (ADR-0043: locale-invariant analysis — no per-language stemmer exists in the codebase).

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
