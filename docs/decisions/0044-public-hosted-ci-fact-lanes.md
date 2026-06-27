---
title: "Public hosted CI fact lanes"
type: decision
status: accepted
description: "The public repository runs standard GitHub-hosted CI on push and pull requests, split into stable fact lanes; self-hosted and specialty workflows remain manually dispatched unless separately amended."
date: 2026-06-27
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

The CI workflow is organized as stable **fact lanes**:

- contributor provenance remains split across CLA and DCO checks;
- secret scanning stays independently visible;
- public claim projections run as their own fast lane;
- license and notice closure run as their own lane with fresh generated inputs;
- no-model build and unit tests are separate Windows-hosted lanes.

Each lane name should describe the fact it proves. Future branch protection can require these
stable checks directly, but this decision does not itself configure branch protection.

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

- Branch protection remains a repository-settings decision outside this ADR.
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
