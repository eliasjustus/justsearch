---
title: "802 — A release recorded the config it was measured under, but not the artifacts it was measured from"
type: tempdocs
status: "STEPS 1-2 DONE, STEP 3 IS AN OWNER DECISION (2026-07-31). Step 1 (merged, 09c7afee): `compose()` records a `sources` section — per-member run_dir + summary sha256 — so a release can be re-scored later; verified by a mutation check (removing the wiring turns the new CLI test red with `KeyError: 'sources'`), 50/50 in `test_release.py`, now CI-wired where 132 pytest files previously ran nowhere. Step 2 (sizing pilot, mixed/enron-qa, 300 queries, the REAL published corpus): the ordering channel is worth **+0.0184 nDCG@10 by ir_measures** — POSITIVE, i.e. the published Enron-QA figure is UNDERSTATED, which REFUTES this document's own prediction that it was overstated; 800's `en-email-enron-raw-*` cells are a different benchmark from the published `mixed/enron-qa` despite the shared name. Per the pre-registered decision rule that is the calm case: the harness fix is correct and non-urgent, and can ride the next release. Step 3 (harness fix + re-run + re-baseline) is NOT done. Four of five published corpora remain unmeasured and no claim is made about them."
created: 2026-07-31
category: measurement-integrity / provenance / eval-harness
related:
  - 800-eval-metric-ignores-delivered-order.md  # the defect whose correction this unblocks
  - 799-structural-health-theorization.md       # §O: a claim that outlived its mechanism
  - 623-benchmark-release-object.md             # where the release object was designed
---

## The gap

`release.v1.json` carries a genuinely rich provenance block. `cohort` records the git SHA, the
config-cohort key, the eval-protocol hash, the policy hash, the hardware tier, and per-model
fingerprints. By any normal standard the release knows where it came from.

It records **nothing about the artifacts**. No run directory, no run id, no artifact digest —
`grep` for `run_dir`, `run_id`, `artifact`, `trec` across the published document returns zero hits
in every case.

The difference matters because those are two different questions:

- *Under what configuration was this measured?* — answered, thoroughly.
- *Which runs is this a projection of?* — **not answered at all.**

The second question is the one you need to re-score a published number. When the run directories
for `715-rebaseline-2026-07-16` were cleaned up, the README's nDCG@10 table became impossible to
re-derive, verify, or correct. The README describes that run as "one reproducible release run" —
a claim that is not currently backed, independent of any metric defect.

This was found while scoping tempdoc 800's fix. It is not a consequence of that defect; it would
block *any* correction to a published number, and it will recur on the next release unless the
composer records the pointer.

## Why the composer, and not somewhere else

`compose()` receives parsed `summary.json` dicts. It never sees a path. The CLI holds `run_dirs`
and the code already knew this — an existing comment at `commands/release.py` reads:

> compose() only sees summaries; the run dirs — and thus projections/ — are only known here

That comment was written for the leak/union-recall sourcing (tempdocs 683/701), which reaches into
the run dirs for projections and then discards the paths. The information was in hand at exactly
the right moment and was thrown away. So the fix belongs in the same loop, not in a new mechanism.

## What shipped (step 1)

A `sources` section: one entry per composed member, in compose order.

```json
"sources": [
  {"dataset": "beir/scifact", "run_dir": "tmp/eval/run1", "summary_sha256": "…"}
]
```

Four decisions worth recording, because each had a losing alternative:

- **A list, not a dict keyed by dataset.** `compose()`'s own docstring says "one or more per
  corpus". A dataset-keyed map would silently drop the second run for a corpus.
- **`compose()` supplies the dataset slug, the CLI supplies path and digest.** Slug
  canonicalization (`scifact` → `beir/scifact`) already lives in the composer; letting the CLI
  compute it independently would create two namers that can disagree. Test
  `test_compose_records_run_sources` pins that the recorded slug is the canonical one.
- **A misaligned `run_sources` list is refused, not truncated or zipped short.** A pointer to the
  *wrong* run is worse than no pointer, because it looks authoritative — the failure class 742
  names. `zip()` would have silently dropped the tail.
- **`run_dir` is repo-relative.** The document is committed and published; `cohort.model_identity`
  already bakes in absolute `F:\…` paths, and this is the half that does not have to.

**`summary_sha256` is the load-bearing field, not `run_dir`.** Paths rot — that is the whole
premise of this tempdoc. The digest still answers "is this candidate directory the one this release
was built from?" after the path stops resolving.

## Verification

| claim | evidence |
|---|---|
| Provenance is recorded end-to-end through the real CLI | `test_cmd_release_records_artifact_provenance` — asserts the recorded digest equals `hashlib.sha256` of the actual `summary.json` bytes, not merely that a string is present |
| The test would have caught the original gap | **Mutation check:** removing `run_sources=run_sources` from the CLI call makes it fail with `KeyError: 'sources'`; wiring restored, suite re-run |
| Pre-802 releases stay valid | `test_compose_omits_sources_when_not_supplied` — no empty section is emitted |
| Misalignment fails closed | `test_compose_refuses_misaligned_run_sources` |
| Full file green | `python -m pytest tests/test_release.py -q` → **48 passed** |
| Schema/consumer checks | `check-release-baseline-sync` OK · `check-jseval-lock` OK · `check-workflow-triggers` OK |

### The test would not have run

`scripts/jseval/tests/` — **132 test files** — is invoked nowhere in CI. `grep` for `pytest` in
`.github/workflows/` returns only unrelated Python steps. This is the same orphaned-layer class
tempdoc 745 §D6 fixed for the node instrument, and the same class tempdoc 799 found in the CI-lint
tier. A test added here would have been a *claim*, not a guarantee.

`test_release.py` is now CI-wired. **Deliberately just that one file**: the other 131 have never
run under CI's locked environment, and enabling them wholesale would turn `main` red on failures
this change does not own. The broader gap is logged for its own pass — see Follow-ups.

## What is NOT done

Step 1 shipped; step 2 ran and is reported below; step 3 is an owner decision.

**Step 2 — sizing pilot.** *Done — see "Step 2 — the sizing pilot" below.* The reasoning recorded
here before the run was: Enron-QA is the right corpus because 800's Enron cells ran mostly negative,
so the published figure is the one most likely **overstated** — the direction that cannot be left
sitting. **The pilot refuted that.** The prediction is left standing above rather than edited away,
because the correction is the useful part.

**Step 3 — the harness fix and re-baseline.** Scoring by delivered rank in `retriever.py` is one
line, but it re-bases every future number against every past one, and the relevance-ratchet floors
would then compare new-basis runs against old-basis baselines. It must land together with a re-run
and a re-baseline of the README table and the floors. Step 2 sizes whether that is urgent or can
ride the next release.

## Honest limits

- **The published cohort is not recovered.** This change makes provenance recordable going forward;
  it cannot reconstruct what `715-rebaseline-2026-07-16` was built from. Those runs are gone. Only
  a re-run restores a re-scoreable basis for the README numbers.
- **`run_dir` is best-effort by construction.** A recorded path that no longer resolves is expected,
  not a bug; that is why the digest carries the identity.
- **Repo-relative resolution uses this checkout's root.** A run composed from inside a worktree
  records a path relative to that worktree. Acceptable — the digest disambiguates — but it means
  `run_dir` is a convenience, not a contract.
- **Directional prediction, not measurement — and it was wrong.** The claim that Enron-QA is likely
  overstated rested on 800's 781-certification cells being representative of the release cohort's
  Enron corpus. Step 2 measured it: they are not, and the sign flips. Kept here because the caveat
  correctly located the weak joint before the measurement existed.
  Original wording follows: the claim rests on
  tempdoc 800's 781-certification cells being representative of the release cohort's Enron corpus.
  That is an inference; the release runs themselves no longer exist. Step 2 is what would settle it.

## Follow-ups

- **`scripts/jseval/tests/` (132 files) runs nowhere in CI.** Wiring it needs a pass that first
  establishes which files are green under the locked environment. Worth its own tempdoc.
- **`release.v1.schema.json` lagged the composer** before this change: `union_recall` has been
  emitted by `compose()` since tempdoc 701 but was never declared as a property. Logged, not fixed
  here — `additionalProperties: true` means it validates either way, so this is documentation
  accuracy rather than a live defect.
- **`cohort.model_identity` publishes absolute local paths** (`F:\justsearch-public\models\…`) into
  a committed, public document. Pre-existing and harmless in content, but it is machine-specific
  detail in a file meant to be portable.

## Step 2 — the sizing pilot (2026-07-31)

Run: `mixed/enron-qa`, 300 queries, 5,485 docs, hybrid mode, full enrichment pipeline, clean
data dir, eval-mode backend. Artifacts: `tmp/802-pilot/20260731T131151_mixed_enron-qa`.

**This is the corpus behind the published Enron-QA figure** — `release.v1.json`'s `measured` keys
include `mixed/enron-qa` — so the pilot is on the real published corpus, not a proxy.

### Result

Scored with **ir_measures itself**, not this repo's A/B helper, so the numbers are in the same
terms the harness and the README use:

| ordering | nDCG@10 |
|---|---|
| measured — fusion order, what the harness scores today | **0.7807** |
| delivered — cross-encoder order, what the engine returns | **0.7991** |
| **delta** | **+0.0184** |

The measured value reproduces jseval's own reported `hybrid: nDCG@10=0.7807` exactly, which is the
check that the re-scoring is reading the same run the harness did.

### The prediction was wrong, and in the direction that matters

Tempdoc 800 predicted Enron would move **down** — its four Enron cells ran mostly negative, so the
published Enron-QA figure was called "the one most likely **overstated** … the direction that
cannot be left sitting". The pilot says the opposite: the ordering channel is worth **+0.018**, so
the published number is **understated**.

The error is now legible. 800's cells are `mixed/en-email-enron-raw-{1k,10k}-{short-natural,verbose}`
— raw email with synthesized queries. The published cohort is `mixed/enron-qa` — the EnronQA paper's
grounded question/answer pairs. Both are "Enron"; they are not the same benchmark, and they behave
oppositely. **The shared word did the reasoning.** 802's own "Honest limits" flagged exactly this
dependency ("rests on 800's cells being representative of the release cohort's Enron corpus") — the
flag was right, and the pilot is what converted it from a caveat into a finding.

### Validity checks

Both were run because a null result here would have been easy to misread as "the defect does not
bite".

- **The cross-encoder actually ran.** `per_mode.hybrid.stage_timing_stats.cross_encoder_ms` =
  `{mean: 148.9, p50: 147, share: 0.7668}` — 77% of stage time — and
  `status_snapshot.worker.enrichment.encoderProfiles.reranker.calls` = 284. A skipped CE would have
  produced a 0.0000 delta that looked like a clean negative.
- **It ran on GPU, not CPU.** The startup log emits `Capability warning: reranker_cpu_only`, but the
  final manifest records `reranker_gpu: true` and
  `gpu.rerankerOrtCuda = {configured, attempted, available: true}`. The warning fires before GPU
  realization completes and is misleading at that point. Worth knowing, because F-026's evidence
  carries a "reranker ran on CPU" caveat sourced from the same warning — that caveat may be wrong
  too, and it is recorded here rather than acted on.
- **Same document set both ways.** The A/B guard reported zero set mismatches across all 300
  queries, so this is a pure reorder.

Scale of the reordering: **287 of 300** queries have a different top-10, and **117 of 300** a
different top-1.

### A discrepancy in this repo's own A/B helper

`metric_order_ab.mjs` scored the same run at `measured=0.7844` where ir_measures says `0.7807`
(delta `+0.0147` vs the true `+0.0184`). The delivered-order figure agrees exactly (0.7991).

That asymmetry identifies the cause: the delivered ordering is written with strictly distinct
synthetic scores, so no ties exist and both scorers agree; the trec file's stale fusion scores
**contain ties**, and the helper's `sort()` breaks them differently from ir_measures. The helper is
therefore sound for detecting and ranking the effect, but **not** for quoting an absolute figure or
a precise delta. Any number that reaches a public claim must come from ir_measures.

### What the pilot decides

Per the decision rule set before the run — "~0.005 → schedule calmly; ~0.05 → correct now" —
**+0.018 and positive is the calm case.** Understatement is not a claims-integrity emergency: the
README's Enron-QA figure is conservative, not inflated. The harness fix remains correct and worth
doing; it is not urgent, and it can ride the next release rather than justify a special campaign.

### What is still unmeasured

Four of the five published corpora: `beir/scifact` (0.760 — the headline, and the one compared
against ColBERTv2/SPLADE++ in the README), `mixed/legal-clerc-200`, `mixed/miracl-de-2k`,
`mixed/miracl-fr-2k`. 800's CLERC cells ran positive, which now carries less weight as evidence
given the corpus-identity error above. **No claim is made about them here.**

Also note the pilot ran at **current `main` config, not the release cohort's** — its measured 0.7807
against the published 0.736 is itself evidence the config has moved since `715-rebaseline-2026-07-16`.
So `+0.0184` is the ordering delta *today*, not a correction that can be added to the published
number on paper. That remains what step 3's re-run is for.
