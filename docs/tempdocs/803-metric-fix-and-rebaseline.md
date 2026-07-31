---
title: "803 — Score the ranking the engine delivers, and re-baseline what that changes"
type: tempdocs
status: "IN PROGRESS (2026-07-31). The one-line harness fix is in and verified against a falsifiable prediction: tempdoc 802 computed offline that enron-qa's delivered ordering scores 0.7991; the fixed harness re-run returns 0.7990 (was 0.7807). Regression tests are mutation-checked. The re-baseline campaign (5 corpora x 4 modes, one cohort) and the README/floor updates are NOT done yet."
created: 2026-07-31
category: measurement-integrity / search-quality / eval-harness
related:
  - 800-eval-metric-ignores-delivered-order.md  # the defect
  - 802-release-artifact-provenance.md          # the measurement + provenance recording
  - 623-benchmark-release-object.md             # the release object being recomposed
---

## What this changes

`retriever.py` recorded each hit at `score=hit["score"]` — the engine's **pre-rerank fusion
score**. The engine reorders results into cross-encoder order without rewriting that field, so
`ir_measures` sorted the delivered list back into fusion order and evaluated a ranking the engine
never returned.

The fix scores by **delivered rank** (`len(hits) - idx`), which is strictly decreasing, so
ir_measures reconstructs exactly the order the API returned.

Three properties of the change worth stating, because each was a choice:

- **Strictly decreasing, not the engine's score.** Ties would let a sort break the delivered order
  arbitrarily — the mechanism behind `metric_order_ab.mjs` disagreeing with ir_measures on absolute
  values in 802.
- **A skipped unresolvable hit leaves a gap**, which is harmless: only the order carries meaning.
  Covered by its own test rather than left to inference.
- **The engine's score is not lost.** The full API response is still retained in `raw_responses`,
  so anything wanting the fusion score can still read it. Only the *metric input* changes.

`predictedDocIds` is unaffected — `_build_per_query_entries` builds it from `scored_docs` in
**append order**, never by score. That is why 802's offline A/B was possible at all, and it is why
this fix does not perturb the artifact it was validated against.

## Verification

**The acceptance test is a prediction, not an inspection.** 802 computed offline, with ir_measures,
that enron-qa's delivered ordering scores **0.7991** where the harness reported 0.7807. If the fix
does what it claims, a re-run must land on the former.

| | enron-qa nDCG@10 |
|---|---|
| harness before | 0.7807 |
| **harness after (fresh run)** | **0.7990** |
| predicted by 802's offline re-scoring | 0.7991 |

A fresh ingest and a fresh eval, landing within 0.0001 of a number computed days-independent from
different artifacts. That is the check that the fix changes what it was supposed to change and
nothing else.

Unit level:

| claim | evidence |
|---|---|
| Delivered order is what gets scored | `test_retrieve_scores_by_delivered_rank_not_engine_score` — response built so engine-score order is the exact REVERSE of delivered order |
| Scores are strictly decreasing (no tie ambiguity) | same test — asserts uniqueness and monotonicity |
| A skipped hit cannot corrupt the ranking | `test_retrieve_rank_scores_survive_unresolvable_hit` |
| Both would have caught the original defect | **Mutation check** — reverting to `hit["score"]` fails both; restored and re-run |
| No collateral damage | `test_retriever.py` + `test_release.py` + `test_scoring.py` → **85 passed** |

### One pre-existing test was changed deliberately

`test_retrieve_basic` asserted `scored[0].score == 1.5` — the engine's own value. That test was not
wrong: it faithfully described what the code did, which is precisely the defect. It now asserts the
rank-derived score, with the reason inline so the change is not mistaken for a convenience edit.

### Two environment traps hit on the way

Recorded because either would have produced a confidently wrong result:

- **`jseval` is editable-installed from the MAIN checkout.** Running from this worktree imported the
  *unfixed* harness. The first acceptance attempt would have measured old code and "shown" the fix
  changed nothing. jseval's own cross-checkout guard caught the follow-up attempt; the working form
  is `PYTHONPATH=<worktree>/scripts/jseval` with cwd in the same worktree, verified by inspecting
  `inspect.getsource(jseval.retriever.retrieve)` rather than assuming.
- **Datasets resolve from `REPO_ROOT/datasets`**, which a fresh worktree lacks. Junctioned to the
  main checkout; `datasets/` is gitignored so the diff stays clean.

## What is NOT done

- **The re-baseline campaign.** 5 corpora x 4 modes (hybrid measured + lexical/bm25_splade/vector
  ablations, matching the published release's shape), all at one commit so `compose()` accepts them
  as one `config_cohort_key`. Modes share an ingest, so the marginal cost of the ablations is query
  time, not another ingest.
- **A new release object**, composed with 802's `sources` provenance so this one can be re-scored
  later — the first release that will be able to.
- **README table and relevance-ratchet floors**, which project from the release and are gate-checked
  (`check-readme-benchmark-numbers`, `check-outward-number-citations`).

## Honest limits

- **The published numbers are not being "corrected" in place.** They were measured at
  `715-rebaseline-2026-07-16` on a config that has since moved — 802 showed today's measured values
  differ from the published ones on the same corpora (legal 0.6407 vs 0.598). This produces a NEW
  release measured today, not a restatement of the old one. Numbers will move for two reasons at
  once — the metric fix and config drift — and those are not separable from this side.
- **Direction is corpus-specific.** Per 802: legal −0.0418, enron +0.0184, miracl-fr +0.0128,
  scifact −0.0061, miracl-de −0.0026. The README will move in both directions.
- **Everything before this fix is on the old basis.** Register findings, ratchet floors, and prior
  tempdoc numbers were computed by an apparatus that discarded the cross-encoder's ranking. This
  change makes future numbers right; it does not retroactively repair the corpus of past ones.
