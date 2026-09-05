---
title: Platform Lifecycle Evidence Policy
type: reference
status: stable
description: "Typed lifecycle evidence, live-pin extraction, and advisory review policy for pinned build and runtime platforms."
---

# Platform Lifecycle Evidence Policy

`governance/platform-lifecycle.v1.json` records lifecycle evidence for the
build and runtime platforms JustSearch pins. It does not copy the live version:
each row names a repository source and one closed extraction adapter, and
`scripts/ci/check-platform-lifecycle.mjs` requires that adapter to resolve
exactly one pin.

## Policy shapes

| Kind | Use when |
|---|---|
| `fixed-date` | The vendor publishes an absolute support horizon, at day or month precision. |
| `release-relative` | Support ends when a successor release appears. |
| `rolling` | The vendor supports only the continuously updated/current line. |
| `compatibility-matrix` | Compatibility depends on an intersection of versions rather than one EOL date. |
| `no-published-eol` | The upstream project publishes releases but no support horizon. |

Every row declares an evidence owner, primary source URL, evidence-check date,
review deadline, live pin source, and one policy shape. Missing, malformed,
ambiguous, stale, or unsupported evidence is a finding; unknown policy and
adapter kinds are invalid input and fail closed.

`fixed-date.supportUntil` preserves the vendor's published precision: `YYYY-MM-DD`
for a stated day or `YYYY-MM` for a stated month. Month-precision horizons are
evaluated from the first day of that month. This deliberately warns early rather
than inventing an unsupported last-day claim.

## Running the check

Use advisory report mode for routine CI review:

```text
node scripts/ci/check-platform-lifecycle.mjs --mode report
```

Report mode still fails on an invalid register or unreadable/non-singular live
pin, but lifecycle findings remain visible without making unrelated changes
fail. A failure-level finding is labelled `ATTENTION REQUIRED`; on GitHub it
also produces a warning annotation and a job-summary entry, so advisory does
not look like success. Gate mode exits nonzero for failure findings and is
appropriate when an owner is actively enforcing an upgrade deadline:

```text
node scripts/ci/check-platform-lifecycle.mjs --mode gate
```

Changing a dependency version does not authorize changing its lifecycle
evidence. Re-check the linked primary source, update `sourceCheckedOn` and
`reviewBy`, and record any changed policy fact in the same review.
