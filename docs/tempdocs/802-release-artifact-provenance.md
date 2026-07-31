---
title: "802 — A release recorded the config it was measured under, but not the artifacts it was measured from"
type: tempdocs
status: "IMPLEMENTED, step 1 of 3 (2026-07-31). `compose()` now records a `sources` section — per-member run_dir + summary sha256 — so a published release can be re-scored later. Verified by a mutation check (removing the wiring turns the new CLI test red with `KeyError: 'sources'`), 48/48 in `test_release.py`, and the file is now CI-wired where it previously ran nowhere. Steps 2 and 3 (sizing pilot, then the harness-fix + re-baseline decision) are NOT done and are described below."
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

This is step 1 of a three-step sequence. Steps 2 and 3 are not started.

**Step 2 — sizing pilot.** Before committing GPU hours to a full re-run, measure the actual delta
on *one* corpus at release config. Enron-QA is the right choice: tempdoc 800's Enron cells ran
mostly negative, and the published Enron-QA figure (0.736) is therefore the one most likely to be
**overstated** — the direction that cannot be left sitting. This needs no harness change: run the
eval, then score the artifacts both ways offline with `scripts/jseval/metric_order_ab.mjs`.

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
- **Directional prediction, not measurement.** The claim that Enron-QA is likely overstated rests on
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
