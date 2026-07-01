---
title: Benchmark Methodology
type: reference
status: stable
description: "How JustSearch measures retrieval quality: the reproducible single-config release, honest comparison classes, and how to reproduce it."
---

# Benchmark Methodology

This doc answers: *"What do JustSearch's retrieval-quality numbers mean, how were they produced, and how
do I reproduce them?"* It is the standalone methodology behind the README's benchmark table. The numbers
are a **projection** of one canonical, reproducible release object (`scripts/jseval/release.v1.json`),
never hand-transcribed — so they cannot silently drift (tempdoc 623; 633).

## What we measure (and what we don't)

We measure **retrieval ranking quality** — nDCG@10 over a fixed set of IR corpora. We do **not** measure
document-extraction/OCR quality or end-to-end answer correctness here. All numbers are nDCG@10 unless noted.

## The release: one config, all corpora, one commit

Every number below comes from a **single sweep** of the **production-default `hybrid` pipeline** across
the full corpus set, at one published `git_sha`, on stated hardware — the reproducibility bar a hostile
reviewer asks for. No per-corpus config cherry-picking: where a non-default config is shown (e.g. `full`
for long legal documents), it is labelled as an **ablation alongside** the default, never *as* the
headline. The release object publishes the hardware (GPU, VRAM, driver, ORT version), the model identity,
and per-corpus confidence tiers + caveats. See the cohort/hardware block in `release.v1.json`.

## How to read the comparison: system vs. component

The external baselines below are **cited published numbers**, not results we re-ran (`self_reproduced:false`
everywhere) — and they are **not directly apples-to-apples**. Read them honestly:

- **System vs. component.** Our SciFact number is a **full hybrid (BM25 + dense + learned-sparse) +
  cross-encoder rerank pipeline**. The cited ColBERTv2 / SPLADE++ baselines are **single-model
  retrievers**. A hybrid+rerank *system* is *expected* to exceed a single *component* — so the right
  reading is "a reproducible offline hybrid system lands in the range of strong published retrievers,"
  not "we beat ColBERT." The per-row caveats carry this comparison class.
- **Split mismatch.** MIRACL published baselines are on the **dev** split; our axis is **test**. German
  is a MIRACL *surprise* language with no canonical baseline row (the only published number is BGE-M3's
  own non-canonical reproduction). These rows are footnoted accordingly — do not mix splits.
- **No test-set tuning.** Fusion weights / thresholds were not fit on the eval queries.

## Results

<!-- generated:start — do not edit between markers; run: node scripts/docs/gen-public-benchmark.mjs -->

*Default mode `hybrid`, commit `84b305b2b`, NVIDIA GeForce RTX 4070, 13 GB VRAM, ORT 1.24.3. nDCG@10. External baselines are cited published numbers (not re-run by us) — see the comparison-class note above.*

| Corpus | Ours (mode) | nDCG@10 | Ablation | Published baselines (cited) |
|---|---|---|---|---|
| beir/scifact | hybrid | **0.756** | — | — |
| mixed/enron-qa | hybrid | **0.719** | — | — |
| mixed/legal-clerc-200 | hybrid | **0.516** | — | — |
| mixed/miracl-de-2k | hybrid | **0.852** | — | — |
| mixed/miracl-fr-2k | hybrid | **0.866** | — | — |

**Engine performance** (relative-ratchet guarded — tempdoc 640; lower latency / higher throughput / lower footprint better):

| Corpus | CE p50 (ms) | Index docs/s | Enrich docs/s | Resident (GB) |
|---|---|---|---|---|
| beir/scifact | 167 | 111.1 | 25.0 | 1.75 |
| mixed/enron-qa | 157 | 96.4 | 7.9 | 2.02 |
| mixed/legal-clerc-200 | 214 | 11.0 | 1.3 | 1.75 |
| mixed/miracl-de-2k | 168 | 73.7 | 36.7 | 1.75 |
| mixed/miracl-fr-2k | 169 | 124.6 | 50.0 | 1.75 |

<!-- generated:end -->

Per-corpus nDCG@10 floors are projected from this release and regression-gated in CI
(`scripts/jseval/relevance-ratchet-baselines.v1.json` + the relevance gate). The internal
search-quality register carries the full per-config ablation log (`docs/reference/search-quality-register.md`).

## Reproduce it

From `scripts/jseval` (Windows; a CUDA GPU accelerates inference, CPU also works):

```bash
python -m jseval run --start-backend --dataset beir/scifact --modes hybrid
python -m jseval relevance-gate --dataset beir/scifact
```

Dataset slugs: `beir/scifact`, `mixed/enron-qa`, `mixed/legal-clerc-200`, `mixed/miracl-de-2k`,
`mixed/miracl-fr-2k`. Corpora are fetched from their canonical sources (pointer + checksum), not
redistributed here. A third party on equivalent hardware should land within the cohort's ±2σ envelope.

## See also
- `scripts/jseval/release.v1.json` — the canonical release object (the source of truth for every number above).
- `scripts/jseval/external-baselines.v1.json` — the cited external baselines (source + version + caveat per row).
- [`search-quality-register.md`](../search-quality-register.md) — the internal per-config research log.
