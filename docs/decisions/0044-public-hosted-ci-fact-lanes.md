---
title: "Public hosted CI fact lanes"
type: decision
status: accepted
description: "The public repository runs standard GitHub-hosted CI on push and pull requests, split into stable fact lanes; self-hosted and specialty workflows remain manually dispatched unless separately amended."
date: 2026-06-27
probes:
  - adr-0044-fact-lane-triggers-checked
  - adr-0044-advisory-fact-lane-bounded
last_reviewed: 2026-09-05
---

# ADR-0044: Public hosted CI fact lanes

## Status

Accepted. Narrows ADR-0026 for the public repository's standard GitHub-hosted CI lane.

## Context

ADR-0026 made all workflows manual-only when the active repository depended on a local
self-hosted Windows runner. That avoided queued or surprise work on a developer workstation,
but it also accepted that remote regression signals were discipline-dependent.

The repository is now public. Standard GitHub-hosted runners are available for public pull
requests and push builds, while GPU/perf/self-hosted workflows still have the safety and
resource constraints ADR-0026 described. The current public `CI` workflow already runs on
`pull_request` and `push`, but the old trigger guard and workflow-signal policy still
described the manual-only world.

The first public CI shape also bundled too many unrelated facts into one Windows job:
assemble, unit tests, license checks, notice projection, and README benchmark projection.
Recent public PR runs showed fast policy signals completing in seconds while unrelated late
failures inside the omnibus job took about 17-18 minutes to become actionable.

## Decision

The public repository's standard GitHub-hosted CI lane runs automatically on `pull_request`
and `push` to `main`, and it may also be dispatched manually with `workflow_dispatch`.

Every workflow that produces a required status check also declares `merge_group`, so the
check still reports if a GitHub merge queue is ever enabled. A required check that never
reports on a merge-group ref stalls the queue until it times out, so the trigger is declared
ahead of the queue rather than after the first stall. It changes nothing until the repository
is organization-owned and a queue is configured, because no `merge_group` event can be
delivered before then.

The CI workflow is organized as stable **fact lanes**:

- contributor provenance is enforced by the CLA Assistant check;
- secret scanning stays independently visible;
- public claim projections run as their own fast lane;
- license and notice closure run as their own lane with fresh generated inputs;
- no-model build and unit tests are separate Windows-hosted lanes.

Each lane name describes the fact it proves. Main branch protection requires the current stable
check names declared in `scripts/ci/workflow-signal-policy.v1.json`. The same policy file declares
whether GitHub must require each pull-request branch to be up to date before merging. That setting
is `false` while the native merge queue is active: the queue creates and tests a merge group
against current `main`, so forcing every PR branch through a separate update-and-retest cycle
would duplicate the queue's integration check and restore the serial re-CI tax.

Self-hosted, benchmark, installer, and other specialty workflows remain manually dispatched
unless a later ADR explicitly changes their trigger posture.

CI speedups must stay within the free public-runner design:

- use standard GitHub-hosted runners by default;
- do not rely on paid larger runners;
- keep caches as accelerators, not correctness dependencies;
- keep cache contents public-safe and bounded below the included cache allowance.

`pull_request_target` remains acceptable only for metadata/provenance workflows that do not
checkout or execute untrusted pull-request code.

## Consequences

**Positive:**

- Public contributors get automatic feedback on normal pull requests.
- A red check points closer to the fact that failed instead of hiding behind one `build-test`
  bucket.
- Platform-neutral public-claim and policy checks no longer wait behind Windows unit tests.
- The workflow trigger guard can validate the declared policy instead of hard-coding a single
  repository-wide manual-only rule.

**Negative:**

- More checks appear on a pull request, so check names must remain stable and meaningful.
- Standard-hosted CI can consume more parallel runner capacity than a single omnibus job.
- The public claim lane can expose pre-existing documentation drift sooner; that is intentional,
  but it requires maintaining those projection checks as first-class public signals.

**Neutral:**

- Branch protection remains a repository setting, but its required check names are now recorded
  in `scripts/ci/workflow-signal-policy.v1.json` and verified by the maintainer-run
  `scripts/ci/check-branch-protection.mjs` script. The default pull-request token cannot read
  branch-protection settings.
- Stress, GPU, live-eval, installer, and other resource-sensitive workflows remain opt-in
  specialty signals.

## Alternatives Considered

### Keep ADR-0026 manual-only for every workflow

Rejected. The original self-hosted-runner resource argument does not apply to standard
GitHub-hosted public CI. Keeping the guard manual-only would make the repo fight its own
public CI reality.

### Keep one automatic omnibus CI job

Rejected. It preserves the safety envelope but gives poor diagnostics and poor contributor
latency. A late failure in license projection or a parser test should not look like one
generic `build-test` failure.

### Use paid larger runners to reduce latency

Rejected. The design goal is free public CI. Larger runners are not part of the default
budget and would not fix the underlying signal-shape problem.

### Make path-aware skipping the primary optimization

Rejected for the first implementation. The repo has projection-heavy checks whose inputs are
not always obvious from changed paths, and required-check behavior around skipped workflows is
easy to misconfigure. Path-aware skipping can be revisited later as advisory acceleration.

## Related

- [ADR-0026: Manual-Only CI Triggering](0026-manual-ci-triggering.md) - still governs
  self-hosted and specialty workflows unless separately amended.
- [Testing Strategy](../explanation/09-testing-strategy.md) - current test and CI signal
  overview.
- [Agent Guide](../reference/contributing/agent-guide.md) - contributor workflow commands.

## Amendment: bounded advisory identity evidence (2026-09-04)

Publication of tempdoc 921 proved npm's bulk-advisory POST could accept both root and
`ui-web` request bodies yet return no bytes until the CLI's five-minute fetch timeout. It
also exposed an older parseable transport-error response that the count producer had
normalized to zero vulnerabilities. A required fact lane cannot use job timeout as its
transport verdict or compare mutable defect counts that allow one disappearance to cancel
one new advisory.

The `Public claims` lane therefore preserves its stable `npm-audit` kernel id while sourcing
exact-lockfile evidence from the read-only GitHub Global Security Advisories API. Requests
are URL-length-batched, paginated, retryable only because they are GETs, and individually
bounded. The gate accepts explicit high/critical GHSA identities and their severities;
unavailable evidence, a new identity, or an upward severity change fails closed. The
covered `npm ci` calls disable their duplicate install-time audit. GitHub vulnerability
alerts and automated security updates are the ambient monitoring layer, not a replacement
for the reproducible PR fact.

This narrows the fact-lane rule: a stable check name is insufficient unless the evidence
transport itself terminates with an explicit result and the comparison unit cannot hide a
defect swap. If GitHub supplies a repository-native dependency-review signal with the same
lockfile coverage and identity-baseline behavior, it may replace this producer in place.

## Amendment: `jseval Python suite` becomes a required fact lane (2026-09-05)

The `jseval Python suite` job added by tempdoc 930 chunk D runs `scripts/jseval/tests` on a
hosted Linux runner. It landed advisory: the job reported on every push, pull request, and
merge group, but it was absent from `scripts/ci/workflow-signal-policy.v1.json`, so nothing
blocked a merge on it. A measurement harness that stands behind public claims is exactly the
kind of fact a lane is for, and an advisory lane proves only that someone chose to read it.

The lane is promoted to a required status check. The evidence for promotion is stability, not
intent: across the 13 completed `main` push and `merge_group` runs from the lane's landing
commit `18e2833f` (2026-09-05T06:46Z) through `3dc054e3` (2026-09-05T08:23Z) it passed 13 times
and failed zero times, with a median wall-clock of 384s and an observed max of 432s under a
20-minute job timeout. The one non-success observation is a whole-run concurrency cancellation
of superseded push run `33952759665`, in which two other jobs were cancelled alongside it.
Promotion converts any future flake into a merge blocker, so the zero-failure record is the
precondition, not a nicety.

Required-lane bookkeeping follows the same rule as every other lane: the check gains a
`local-subset` entry in `scripts/ci/public-ci-local-repro.v1.json` and a lane in
`scripts/ci/ci-walltime-policy.v1.json` with its hard timeout and measured budget. The local
subset is an approximation in one specific way — the hosted lane deliberately runs without the
`agent`, `ui`, and `scan` extras so their `pytest.importorskip` guards are exercised, while a
developer workstation usually has them installed and therefore runs more tests, not fewer.

Promotion also makes the lane's wall-clock a measured quantity, which requires the advisory
`ci-walltime` job to depend on it. `report-ci-walltime-attribution.mjs` drops any job that has
no end time when the snapshot is taken, so a required lane missing from `ci-walltime`'s `needs:`
would be absent from every attribution and would warn `missing-required-lane` forever. Adding a
required lane therefore means adding it to that `needs:` list in the same change.

`main`'s required status checks are a repository setting outside this repo's diff, held in
classic branch protection (`repos/<owner>/<repo>/branches/main/protection`) rather than in the
`main-merge-queue` ruleset, whose only rule is `merge_queue`. A maintainer updates that setting
after this change lands; `scripts/ci/check-branch-protection.mjs` reports the declared-versus-
live gap in the interval and is the only guard that can see it, because the default pull-request
token cannot read branch-protection settings.
