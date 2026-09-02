---
classification: declared-growth
tempdoc: 911
---

`src/api/generated/schema-types/index.ts`: **pin advanced 51 → 53** to match the growth PR #614
declared in `911-failed-jobs-wire-projection.md`.

#614 declared the growth but did not advance the pin in the same commit. Changeset discovery is
PR-scoped (`scripts/governance/lib/changeset-loader.mjs` §"PR-scope discovery"): a changeset covers
only while it is in the diff against the baseline ref. Once #614 squash-merged, the next push to
`main` (#613, b6d0861e) and #615's merge-group run diffed against a base that already contained the
changeset, so the 51 → 53 growth read as `dead-code/silent-growth` and `main` went red. The
documented remedy (`docs/reference/contributing/discipline-gate-kernel.md`, "advance the baseline in
the same commit as the change so the gate sees nothing wrong") is applied here: the pin now equals the
live count, and this changeset covers the baseline raise (`silent-baseline-shift`) and the growth in
the one commit where both are visible.

No new dead code: the two symbols are the barrel re-exports `gen-wire-schema-types.mjs` emits for the
`failed-indexing-jobs-response` projection, as 911's changeset explains.
