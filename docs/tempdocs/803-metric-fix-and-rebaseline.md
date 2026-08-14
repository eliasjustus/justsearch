---
title: "803 — Score the ranking the engine delivers, and re-baseline what that changes"
type: tempdocs
status: "HARNESS FIX SHIPPED; RE-BASELINE DELIBERATELY NOT DONE (2026-07-31). The fix scores by delivered rank instead of the engine's stale pre-rerank score, and is verified as a PREDICTION rather than an inspection: tempdoc 802 computed each corpus's delivered-order score offline from separate artifacts before this harness existed, and the re-runs hit all four testable predictions within 0.0001 (enron 0.7992 vs 0.7991, scifact 0.7543 vs 0.7544, miracl-de and miracl-fr exact). Regression tests mutation-checked. DECISION: the README and relevance-ratchet floors are UNCHANGED and no published number moved. Re-baselining today would have swapped a coherent old table for a coherent new one MINUS FRENCH, because miracl-fr cannot produce a certifiable run (see below) — and French backs a stated public claim, so withdrawing it over an unrelated enrichment bug would be worse than leaving the old numbers with the register riders that already flag their basis. The eval-path SPLADE enrichment failure is parked as its own bug, on its own merit: if #339 made it visible rather than caused it, the PUBLISHED French number was computed over an index with a silently-missing SPLADE artifact, which is a data-integrity question independent of any metric. The re-baseline should happen once, cleanly, across all five corpora, after that is fixed."
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

## Re-baseline campaign — results and two blockers (2026-07-31)

All five corpora re-run at one commit (`40841ad9`), 4 modes each, clean index, full enrichment.

| corpus | hybrid | lexical | bm25_splade | vector | comparable |
|---|---:|---:|---:|---:|---|
| `mixed/enron-qa` | **0.7992** | 0.8250 | 0.8145 | 0.5802 | yes |
| `beir/scifact` | **0.7543** | 0.6610 | 0.6686 | 0.7271 | yes |
| `mixed/legal-clerc-200` | **0.5783** | 0.6878 | 0.6827 | 0.6220 | yes |
| `mixed/miracl-de-2k` | **0.8575** | 0.7024 | 0.7449 | 0.8510 | yes |
| `mixed/miracl-fr-2k` | **0.8844** | 0.7010 | 0.7613 | 0.8900 | **NO** |

### The fix is confirmed on four corpora independently

802 computed each corpus's delivered-order score offline, from different artifacts, before this
harness existed. The re-runs land on those numbers:

| corpus | 802 predicted | measured | Δ |
|---|---:|---:|---:|
| `enron-qa` | 0.7991 | 0.7992 | +0.0001 |
| `scifact` | 0.7544 | 0.7543 | −0.0001 |
| `miracl-de-2k` | 0.8575 | 0.8575 | 0.0000 |
| `miracl-fr-2k` | 0.8844 | 0.8844 | 0.0000 |

Four independent confirmations at ≤0.0001. The fix does what it claimed and nothing else.

### Blocker 1 — `miracl-fr-2k` is not comparable, reproducibly

`comparability_reasons: ["readiness_failed: splade_requested_but_splade_features_not_ready"]` on
all four modes. Cause found in the run manifest:

| corpus | splade docs | completed | **failed** |
|---|---:|---:|---:|
| `miracl-de-2k` | 3104 | 3104 | 0 |
| `miracl-fr-2k` | 5408 | 5407 | **1** |

**One document of 5,408 fails SPLADE enrichment**, and the readiness gate requires complete
coverage, so a single failure marks the whole run incomparable. It is not a flake — a second full
run reproduced it exactly (same reasons, nDCG identical to 4 decimal places). The published release
records `comparable: true` for this corpus, so this is new since `715-rebaseline-2026-07-16`.

`compose()` refuses a non-comparable default-mode run. **`--allow-incomparable` exists and is not
being used**: overriding a conservative gate in order to publish is the exact move this tempdoc's
lineage exists to prevent. The options are to identify and fix the failing document, to publish a
four-corpus release and say French was dropped, or to leave the release uncomposed — and which of
those is acceptable is a claims decision, not a mechanical one.

### Blocker 2 — `legal-clerc-200` moved between sessions and I do not know why

Its measured hybrid (0.5783) is 0.021 below 802's predicted 0.5989. **The first explanation
recorded here was wrong** — an observation note claimed run-to-run nondeterminism, from a two-run
comparison that confounded *session* with *commit*. Measuring properly:

| comparison | identical top-10 doc sets | Jaccard |
|---|---:|---:|
| 803 runs vs each other (3 runs) | 160–175 / 200 | **0.964–0.977** |
| 802 run vs any 803 run | 6–7 / 200 | **0.54** |

Within a session, legal is as stable as every other corpus, and its three runs cluster tightly
(0.5783 / 0.5811 / 0.5800). Across the two sessions it is barely the same ranking. Corpus signature
is **identical** (`90d4300d…`, 198 docs), so it is not a data difference. The sessions differ by
commit (`f3f6909e` → `40841ad9`, with #350 in between) — but #350 is app-update work with no
obvious retrieval path, and the other four corpora reproduce to 0.0001 across the same gap.

**Cause unidentified.** Recorded as an open question rather than guessed at; the note is corrected
to say so.

### Status

The harness fix is verified and stands. The re-baseline **cannot be honestly completed** until
blocker 1 is resolved, because the release object would either omit a published corpus or contain a
member the harness itself says is not comparable.

## Root cause of both anomalies: 802 measured a stale engine (2026-07-31)

Both "blockers" above trace to one fact, found by checking rather than assuming.

**Tempdoc 802's measurement runs were made on a checkout that lacked ten merged commits**,
including `967f94bf` — *"fix(798): ingest livelock — writers stop claiming COMPLETED without an
artifact, and the backfill loop can no longer starve ingest"* (#339).

```
git merge-base --is-ancestor 967f94bf f3f6909e  →  false
```

802's runs were launched from the main checkout, whose local `main` was behind `origin/main` at the
time (world-state had been reporting it as such all session). `f3f6909e` is that stale tree. The 803
runs are at `40841ad9`, current `origin/main` plus this fix — **15 commits apart, not the "same
engine, different session" the earlier analysis assumed.**

That explains both anomalies with one mechanism, and inverts what they mean:

- **`miracl-fr-2k`'s SPLADE failure is probably not new — it is newly VISIBLE.** #339 stops writers
  claiming COMPLETED without an artifact. A document whose SPLADE artifact never materialised would
  previously have been counted complete and silently included; now it is marked FAILED and the
  readiness gate refuses the run. If that is what happened, the published release's
  `comparable: true` for this corpus was computed over an index with a silently-missing artifact,
  and the gate is now telling the truth where it previously did not.
- **`legal-clerc-200`'s retrieval shift** sits in the same window and has the same candidate cause:
  enrichment completion semantics changed, so the features present at query time changed, so the
  rankings changed. This supersedes the "cause unidentified" note above.

**Not yet proven** — #339 is the strongest candidate by subject matter and timing, not a bisected
result. Confirming it means identifying the specific failing document, which is one more ingest run.

### What this does to 802's numbers

Less than it might appear, and the reason is worth stating.

802's per-corpus deltas were computed **within a single run** — one retrieved set, scored two ways.
They never depended on cross-run or cross-commit stability, so they remain valid measurements of the
ordering channel *on the engine build that produced them*. The 803 re-runs, on an engine 15 commits
newer, reproduce four of the five predictions to ≤0.0001 — which is independent evidence that the
ordering-channel effect is stable across that engine change.

What is **not** safe is 802's "measured today" column as a statement about current `main`: those
absolute figures are from the stale build. The register riders quote deltas rather than absolutes,
so they stand; but any future reader comparing 802's absolutes against a fresh run will see the
15-commit gap, not a metric effect.

### Consequence for this tempdoc

The re-baseline is **not** blocked on a claims decision, which is how it was first written up here.
It is blocked on a concrete, ordinary engineering question — *which document fails SPLADE
enrichment, and why* — that is answerable with one more ingest run and was escalated prematurely.

## Investigating the `miracl-fr-2k` blocker (2026-07-31)

Escalating this as a claims decision was premature — it is an engineering question. Investigated.
Two of my own intermediate conclusions were wrong and are corrected below.

### What was tested

| # | probe | result |
|---|---|---|
| 1 | Gate logic — is it misbehaving? | **No.** `readiness.py:474-476` fires on `spladeFailedCount > 0` **or** coverage < 99.9. Coverage is 99.9815 and passes; the *failed-count* clause fires. Zero-tolerance by construction, and asymmetric with dense, which allows 0.1% missing. |
| 2 | Corpus content — a pathological document? | **No evidence.** 5,408 files, smallest 14 B, largest 4.4 KB, no empty files, contents are ordinary short French text. |
| 3 | Same corpus via the **dev-stack** ingest path | **0 failures, 100% coverage.** So it is not the documents. |
| 4 | Third eval-mode run | **Failed again**, `spladeFailedCount=1`, identical reasons. **3/3 in eval mode.** |
| 5 | Can the dev-stack path produce a release-grade run instead? | **No.** A clean dev stack indexes **5 documents before any ingest** — the app's built-in help files (`SSOT/docs/help/*.md`). Using it would put 5 English help docs inside a French corpus. |

### Two corrections to my own earlier calls

- **"A flake."** Wrong — it reproduces 3/3 in eval mode.
- **"Reproducible, therefore one bad document."** Also wrong, and the opposite error: probe 3 shows the
  identical corpus enriches cleanly through a different ingest path. Reproducible ≠ content-caused.

### Where it actually lives

The failure is **specific to the eval ingest path** (`runHeadlessEval` + `syncDirectory` on a watched
root), reproducibly, on one document in 5,408. The dev-stack API ingest path does not produce it.

The likely reason it appeared only now is #339 (see the stale-engine section above): writers no
longer claim COMPLETED without an artifact, so an enrichment failure that was previously counted as
complete is now correctly marked FAILED. On that reading the published release's `comparable: true`
for this corpus was computed over an index with a silently-missing SPLADE artifact — the gate is not
newly broken, it is newly honest.

### Why this is now genuinely a decision, not more work

Unblocking requires making eval-path SPLADE enrichment succeed on that document — ingest/enrichment
reliability work in the worker, not measurement work. Three ways forward, and the choice is a
claims/scope call rather than a technical one:

1. **Fix the eval-path enrichment failure**, then compose all five corpora. Correct, unbounded scope.
2. **Compose a four-corpus release** and state plainly that French was withheld because its run could
   not be certified comparable. Honest, but changes the public table's shape.
3. **Leave the release uncomposed.** The harness fix still lands on its own merit; published numbers
   stay as they are, on the old basis, with the register riders already flagging that.

**`--allow-incomparable` remains unused.** It would produce a five-corpus release by overriding the
only mechanism that noticed the problem.

## Decision and disposition (2026-07-31)

**The harness fix ships alone. The re-baseline does not happen yet.**

Of the three options recorded above, the middle one — publish four corpora and drop French — looked
viable until its cost was stated plainly: the README's *"Competitive nDCG on German **and French**"*
would lose its French evidence. That withdraws support for a claim because of an **enrichment bug
that has nothing to do with whether the claim is true**. Worse than either alternative.

So:

- **Ship the harness fix.** It is independently verified, blocks nothing, and every future eval now
  measures the ranking the engine actually delivers. Pure gain.
- **Leave the README and the ratchet floors alone.** Not because the current numbers are fine —
  `legal-clerc-200` is overstated by roughly 0.04 — but because the alternative available *today* is
  a table missing a corpus. The overstatement is recorded in the register riders, not hidden.
- **Park the eval-path SPLADE failure as its own bug.** It deserves investigation on its merit
  rather than as a release chore, and it may be the most consequential thing this thread surfaced:
  if #339 made it visible rather than caused it, the **published** French number was computed over
  an index with a silently-missing SPLADE artifact. That is a data-integrity question that outlives
  any metric decision.

The re-baseline then happens **once**, cleanly, across all five corpora, when that is fixed — rather
than twice, badly, with a hole in it.

### What a successor needs

- Everything measured here is in the tables above; the run artifacts are under `tmp/803-rebaseline`,
  `tmp/803-fr-attempt3` and `tmp/803-variance` (gitignored, so they will not survive a clean).
- The fr failure is characterised, not fixed: reproducible 3/3 via the eval ingest path, 0/1 via the
  dev-stack API path, on one document of 5,408. Start from `readiness.py:474` and the difference
  between those two ingest paths.
- **Do not reach for `--allow-incomparable`.** It composes a five-corpus release by overriding the
  only mechanism that noticed the problem.

## RESOLVED (2026-08-14, tempdoc 832 lane B — the parked fr blocker is root-caused and fixed)

The failing document is `1455689%2310.txt` — 272 bytes of ordinary French prose beginning
**"P4 Jean Mérieux"**. `P4` + whitespace is the NetPBM binary-bitmap magic number; Tika's
`MimeTypes.detect` prefers the magic hit over the `.txt` name hint (text/plain is not a
specialization of image/x-portable-bitmap), so `AutoDetectParser` handed French prose to an image
parser, which emitted nothing — the document indexed as a content-less shell and terminally failed
embed + SPLADE + NER (`CombinedEnrichmentBackfillOps` escalation), which SPLADE's zero-tolerance
readiness clause turned into the run-level failure. **The eval-vs-API asymmetry recorded above was
a false lead** — nothing is path-specific; the earlier API-path non-repro measured a different
directory, and a dev stack's AI/VDU tier can paper over an empty extraction where the AI-off eval
backend cannot. The data-integrity question this section raised is answered: every prior index of
this corpus carried the doc content-less; at one doc in 5,408 the aggregate effect is below noise
(hybrid 0.8844 before and after the fix), so no published number moves.

Fix: `TextNameMagicConflictDetector` (worker-services extract package) — promotes to the name-based
type only when the delegate's answer is non-textual AND the name says `text/*` AND the leading 8 KB
strict-decode as UTF-8 text; wired into all three Tika sites. Deliberately NOT routed through the
790 dropout chain (its tiers are image-recovery; this text was lost to routing, not rendering).
Plus: terminal SPLADE FAILED escalations now log `docId(reason)` — the observability gap that made
this a two-investigation bug. Live verification on the eval path: **5408/5408 all three stages,
failed 0, run `comparable: true`, reasons `[]`**; hybrid 0.8844 reproduces this document's §
measurement and 802's prediction to four decimals, now certifiable. The re-baseline this document
deferred is unblocked; it proceeds under tempdoc 832.
