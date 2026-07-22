---
title: "benchmark v2: multi-schema questions, baseline-arm characterization on the camouflaged corpora, the golden-corpora leak audit, and the register provenance sweep — everything the eval-design owner owes before the hero numbers go public"
type: tempdocs
status: "items 3+4 DONE (2026-07-22, §D — golden-corpora leak audit + register provenance sweep committed); items 1+2 in flight elsewhere. Evidence-driven; no heavy theorize needed — the instruments and patterns all exist."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: eval-design / agent-utility
related:
  - 766-eval-content-rebuild-program     # D4 multi-schema debt; hero pre-registration consumer
  - 767-camouflaged-injection-corpus-lane  # the generator + certification gates this extends
  - 764-eval-validity-lane               # power tables; per-qid matrix method
---

> Charter. Four work items, one owner (eval design). Load `/search-quality`;
> the register provenance sweep is item 4's deliverable.

# 776 — benchmark v2

## §A. Work items

1. **Multi-schema questions (the 766 D4 debt).** The rebuilt corpora are
   bridge-schema only (verified: 50× `1_hop` per cell). Add single-fact
   lookup and multi-doc aggregation generators on the 767 camouflaged
   payload substrate (entity banks, carrier sentences, determinism proofs
   all reusable); per-schema difficulty bands via the reachability-anchored
   method (767 §F); per-schema `gold_kind` comparators (767 §F registry);
   schema-stratified certification cells. Temporal/negation stay candidates.
2. **Baseline-arm characterization (the unmeasured uplift denominator).**
   Nothing is known about grep/Read performance on the camouflaged corpora —
   and df=1 bridge tokens may grep BETTER than the old alien tokens, which
   would compress the hero campaign's uplift. Measure cheaply BEFORE the
   hero pre-registration: a small haiku baseline-arm probe (~10-15 cells,
   order $5) per viable stratum, giving the pre-registration an honest
   expected-delta band instead of a guess.
3. **Golden-corpora leak audit.** The `_FILLER` leak (767 §Q — a third of
   measured retrieval) proves the gold-only-feature class; the same
   generator family built `golden/needle-burial-v1` and
   `golden/battlefield-{en,de}-v1`. Run the now-existing instruments
   (distractor-flood index, shape-indistinguishability, filler ON/OFF
   ablation where cheap) over the back catalog; classify each corpus
   clean/leaky with measurements.
4. **Register provenance sweep.** Every register finding measured on the
   pre-767 707-corpora (F-039 census numbers, the 707 catalog rows'
   hybrid fidelity values, Q-018's DE numbers if the DE member shares the
   filler) gets the corpus-provenance-note treatment the register already
   uses for regenerated corpora: dated annotation that the measurement was
   made on a leak-inflated substrate, numbers historical-not-reproducible,
   shipped decisions unaffected. Catalog rows for the rebuilt strata added;
   old strata rows annotated superseded-for-claims.

## §B. Optional scope (explicitly severable, founder-priced)

Third stratum (US gov docs, 17 USC §105 — 766 D8); FRAMES public anchor
(Frame D, 762 §X.6.5); tier-scaling curve as a hero-campaign deliverable.

## §C. Acceptance

- New schema cells pass ALL certification gates incl. the 767 five;
  closed-book ≈ 0 per schema; cross-interpreter determinism green.
- Baseline probe: per-stratum baseline band with n stated, folded into the
  766 pre-registration's expected-numbers table.
- Leak audit: per-corpus verdict table with instrument outputs committed;
  any leaky corpus gets a register provenance note + a retirement/rebuild
  recommendation (decision stays founder's).
- Register sweep complete before any hero number publishes (hard ordering).

## §D. Items 3+4 results (2026-07-22)

Offline audit (no backend, no paid API), reusing the 767 instruments
(`scripts/jseval/jseval/corpus_leak.py`). Artifacts:
`tmp/analysis-624/776/leak-audit/{needle-burial-v1,battlefield-en-v1,battlefield-de-v1,de-miracl}.json`
+ `run_audit.py`.

### D.1 Leak-audit verdict table

| corpus | filler (gold/native cov) | id-shape (best rule, sep vs base) | rare-token df≤5 | length sep | verdict |
|---|---|---|---|---|---|
| `golden/needle-burial-v1` | 1.0 / 1.0 (uniform, not a leak) | `trailing_int<=40` P/R 1.0, J **1.0 vs 0.29** | 2/20 (0.10) | 0.025 | **LEAKY** — id-shape enumeration |
| `golden/battlefield-en-v1` | 1.0 / 1.0 (uniform) | `trailing_int<=78` P/R 1.0, J **1.0 vs 0.20** | 3/26 (0.115) | 0.026 | **LEAKY** — id-shape enumeration |
| `golden/battlefield-de-v1` | 1.0 / 1.0 (uniform) | `trailing_int<=78` P/R 1.0, J **1.0 vs 0.20** | 1/26 (0.038) | 0.0 | **LEAKY** — id-shape enumeration (+ minor query-overlap: 0/26 zero-overlap, median Jaccard 0.072) |
| `707-corpora/de-miracl` | English `_FILLER` 240/240 gold; ~0 real DE hosts (by construction) | minted lower-alnum gold vs host int ids (likely; not measured offline) | — (mixed corpus not materialized offline) | — | **LEAKY** — `_FILLER` gold-selective; NOT rebuilt |

Key distinctions established with measured numbers:

1. **`_FILLER` is only a leak in the injection family, not the pure-synthetic
   `golden/*` corpora.** In needle/battlefield the filler sits in 100% of BOTH
   gold and distractors (both procedurally generated) → gold coverage =
   native coverage → not gold-selective. In the 707 injection corpora the gold
   is fabricated (filler-bearing) and the distractors are real hosts (no
   filler) → gold-selective, the 767 §Q mechanism.
2. **The `golden/*` leak is id-shape enumeration** (767 defect #3 class): gold
   occupies `trailing_int(id)` 1..N and distractors N+1..M with **zero
   overlap** (verified directly), so a numeric filename threshold selects the
   whole gold set at precision/recall 1.0 without reading a body. Bites the
   agent-utility grep/Read baseline (materialized `<doc_id>.txt` filenames);
   does **not** affect dense retrieval (IDs aren't searchable text).
3. **de-miracl shares the `_FILLER` paragraph — Q-018's dependency confirmed.**
   Its gold source `635-corpora/synth-multiling-de-v1` and every fabricated
   cell carry the identical *English* filler among real German hosts; the DE
   member was **not** part of the 767 English-members rebuild.

### D.2 Register provenance sweep (item 4)

Annotated in `docs/reference/search-quality-register.md` (verdicts unchanged;
provenance only):

- **New note:** "Corpus provenance note (2026-07-22, tempdoc 767 §Q + 776
  items 3-4)" — the anchor the rows/findings point to; mirrors the 664/666 tone.
- **Dataset Catalog rows:** `needle-burial-v1`, `battlefield-en-v1`,
  `battlefield-de-v1` (id-shape leak notes); `en-legal-clerc` + `en-email-enron-raw`
  (pre-rebuild hybrid marked leak-inflated; certified leak-free hybrid added —
  legal 0.33/0.25/0.06/0.08, enron 0.66/0.61/0.49/0.44, #273); `de-miracl`
  (LEAKY / pre-rebuild / not-rebuilt).
- **Findings:** F-039 (one provenance sentence — census magnitudes pre-767,
  resolution re-measured leak-free #273); Q-018 (provenance caveat + explicit
  "748 should re-verify on a defillered de-miracl rebuild"); F-027 (corpus-leak
  provenance — battlefield id-shape confounds any future real-with-tool arm,
  did not bias the A-vs-A Δ).
- Regen: `skills-sync.mjs` + `llmstxt-generate.mjs` run.

### D.3 Flagged follow-ups (need a live eval — out of offline scope)

- **de-miracl filler ON/OFF retrieval-inflation ablation** (mirror 767 §Q on
  legal-1k) to quantify how much of Q-018's collapse is leak-inflation vs. real
  German representation floor — needs a materialized mixed cell + GPU retrieval
  run. Route to **748**.
- **Full gold-vs-native `corpus_leak` run on materialized de-miracl mixed cells**
  (id-shape/ngram/rare-token vs real host distractors) — needs a host-pool
  fetch (network) + inject.
- **Retirement/rebuild decision for the three `golden/*` id-leaked corpora and
  de-miracl** stays founder's (per §C).
