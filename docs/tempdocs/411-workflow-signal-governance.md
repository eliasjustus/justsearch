---
title: Workflow Signal Governance
type: tempdocs
status: open
---

# 411 - Workflow Signal Governance

## Status

**IMPLEMENTED V1; OPEN FOR FUTURE GOVERNANCE AUTOMATION.** Created
2026-04-24 after reviewing the current tempdoc frontier, ADR-0026, and
live GitHub Actions evidence. Production-Reality Verification continues
to own installer/tag-release failures; this tempdoc owns the broader
question of how agents interpret GitHub Actions signals after primary CI
became manual-only.

The central finding is simple:

**GitHub Actions already solves workflow mechanics. JustSearch needs a
small interpretation layer, not a new scheduler, telemetry store, or
governance service.**

## Diagnosis

ADR-0026 intentionally makes `.github/workflows/ci.yml` manual-only.
That means "green main" is now local-discipline plus explicit dispatch,
not automatic CI. The repo has no push, pull-request, or scheduled
primary CI trap.

Specialty workflows still auto-run and are now more valuable as evidence,
but their failure semantics are unclear. A scheduled red run can mean
product regression, stale workflow assumptions, missing dependency setup,
abandoned schedule, expected advisory drift, or a release-blocking tag
failure. Those are different operational facts, but GitHub exposes them
as the same red conclusion.

Current live evidence on 2026-04-24:

- `Agent Live Eval Nightly` failed at `Run live battery`.
- `Phase 3 Observability Nightly` failed at `Install jseval Python deps`.
- `Build Installer` failed on `v2.0.0-alpha.1`, `v2.0.0-alpha.2`, and
  `v2.0.0-alpha.3`; those failures are already owned by
  Production-Reality Verification and tempdoc 374.
- `RR219 Resilience Governance Nightly` succeeded, so the runner fleet is
  not globally dead.

The failure mode to prevent is agents treating all red scheduled runs as
the same kind of evidence. A dependency-install failure before a
benchmark/eval gate is workflow or infrastructure drift; it is not a
search-quality or observability regression yet.

## Official Sources Reviewed

The relevant GitHub primitives are documented by official sources:

- [GitHub REST workflow runs API](https://docs.github.com/v3/actions/workflow-runs/)
- [GitHub REST workflows API](https://docs.github.com/en/rest/actions/workflows)
- [GitHub CLI `gh run list`](https://cli.github.com/manual/gh_run_list)
- [GitHub CLI `gh run view`](https://cli.github.com/manual/gh_run_view)
- [Workflow artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts)
- [Job summaries via `GITHUB_STEP_SUMMARY`](https://docs.github.com/en/actions/reference/workflow-commands-for-github-actions)
- [Workflow status badges](https://docs.github.com/en/actions/how-tos/monitoring-and-troubleshooting-workflows/monitoring-workflows/adding-a-workflow-status-badge)
- [Workflow notifications](https://docs.github.com/en/actions/concepts/workflows-and-actions/notifications-for-workflow-runs)
- [Disable/enable workflows](https://docs.github.com/actions/managing-workflow-runs/disabling-and-enabling-a-workflow)

## What GitHub Already Solves

GitHub already gives the project the raw workflow control plane:

- Use `gh run list`, `gh run view`, or the REST APIs for workflow/run
  inventory. JustSearch should not build a custom GitHub scraper.
- Use artifacts for logs, scorecards, screenshots, manifests, and failure
  bundles. The project should improve artifact naming and retention where
  needed, not create a parallel artifact store for workflow results.
- Use `GITHUB_STEP_SUMMARY` for human-readable triage summaries attached
  to the run. The summary should be the first stop for humans, while
  machine-readable artifacts remain the first stop for scripts.
- Use status badges only as public-facing "last run" hints. Badges are
  not the governance source of truth because they collapse too much
  context.
- Use workflow disable/enable for abandoned or noisy schedules. A
  workflow that repeatedly fails before producing meaningful evidence
  should be fixed, disabled, or converted to manual-only rather than left
  as a zombie red run.
- Do not rely on GitHub notifications for governance. Scheduled workflow
  notification ownership follows the cron editor or re-enabler, which is
  not a stable project policy.

## What JustSearch Must Define

JustSearch needs policy over the raw GitHub signals.

### Workflow Classification

| Class | Workflows | Interpretation |
|-------|-----------|----------------|
| `primary-manual-gate` | `ci.yml` | Manual ADR-0026 verification; absence of a run is not automatic failure, but stale manual verification is a known risk. |
| `release-gate` | `build-installer.yml` | Release/tag packaging signal; tag failures are release-blocking and route to tempdoc 374 / Production-Reality Verification. |
| `docs-fast-gate` | `docs-lint.yml` | Fast canonical-doc drift and Markdown/link signal. |
| `scheduled-quality-signal` | `agent-live-eval-nightly.yml`, `phase-3-observability-nightly.yml` | Scheduled evidence runs; failure class depends on which step failed. |
| `scheduled-governance-signal` | RR219 workflows | Governance/advisory evidence; setup failures are workflow drift, gate failures route to resilience governance. |
| `benchmark-evidence` | Claim A / Track G workflows | Manual benchmark evidence; not a general CI substitute. |
| `dependency-automation` | Dependabot Updates / Dependency Graph | Dependency-maintenance automation; interpret separately from product gates. |

### Failure Classes

| Failure class | Meaning |
|---------------|---------|
| `product-regression` | The workflow reached the meaningful product/evidence gate and that gate failed. |
| `infra-drift` | The run failed before meaningful evidence because runner setup, dependency installation, checkout, or tool bootstrap failed. |
| `workflow-assumption-drift` | The workflow's scripted assumptions are stale, such as a missing scorecard script or downstream summary expecting an absent artifact. |
| `stale-or-zombie` | No recent useful run exists, or a scheduled workflow repeatedly runs red/cancelled without ownership. |
| `expected-advisory-failure` | An advisory/governance lane failed in a way that records useful pressure but does not block release by itself. |
| `release-blocking-failure` | A release/tag/package lane failed and should block release publication until resolved. |

`in-progress` is a non-failure state used by the health report when the
latest run has not completed yet.

### Triage Routing

- Installer/tag failures route to tempdoc 374 and Production-Reality
  Verification.
- Phase 3 observability failures route to tempdocs 400/404 only if the
  failing step reaches `jseval gate` or the equivalent observability
  evidence gate. Dependency installation failures are infra/workflow
  drift.
- Agent live eval failures route to agent-quality only if the live battery
  runs and produces the expected manifest. Setup failures and downstream
  scorecard/summary failures without a manifest are workflow drift.
- RR219 failures route to resilience governance unless the failure is
  setup or dependency installation.

## First Implementation Slice

The first slice is intentionally small and repo-owned:

- `scripts/ci/workflow-signal-policy.v1.json` records workflow name/path,
  expected trigger type, owner, default failure class, stale threshold,
  and blocking/advisory status.
- `scripts/ci/workflow-signal-health.mjs` reads recent GitHub Actions
  runs via `gh run list --json ...`, inspects failed latest runs with
  `gh run view --json jobs`, and emits either Markdown or JSON.
- The command reports latest run per workflow, last successful run age,
  current conclusion/status, event, branch/tag, failed step names when
  available, stale status, and computed failure class.
- `scripts/ci/stress-suite-policy.v1.json` records the explicit Gradle
  test tasks and expected source files for every policy-covered
  `@Tag("stress")` suite.
- `scripts/ci/verify-stress-suite-policy.mjs --check` scans
  `modules/**/src/test/**/*.java` for real `@Tag("stress")`
  annotations, fails when discovered stress suites and policy diverge,
  and provides `--tasks` for the CI stress lane.
- REST API use remains a fallback for a future version. The GitHub CLI is
  the correct v1 source because agents already use it for CI triage.

Example:

```text
node scripts/ci/workflow-signal-health.mjs --repo eliasjustus/JustSearch --md
node scripts/ci/workflow-signal-health.mjs --repo eliasjustus/JustSearch --json
node scripts/ci/verify-stress-suite-policy.mjs --check
```

## Agent Guidance Updates

`CLAUDE.md` and `/ci-triage` should tell agents to run the health command
before interpreting scheduled-workflow failures. Agents should read the
computed failure class before deciding whether to investigate product
code, workflow setup, packaging, or governance artifacts.

`/inference-runtime` should remind agents to dispatch
`gh workflow run ci.yml -f runStress=true` after ORT/session concurrency
changes, matching ADR-0026. That reminder belongs in the inference
runtime register because concurrency-sensitive runtime work is where the
stress suite matters most.

## 406 Worktree Impact

Reviewing the 406 lifecycle-refactor worktree after this tempdoc landed
changed one assumption: stress coverage is no longer ORT-only.

Tempdoc 406 adds `LifecycleStressTest` in `modules/adapters-lucene` with
`@Tag("stress")`. The current `ci.yml` `stress_tests` job says
`runStress=true` runs `@Tag("stress")` tests, but the actual command only
runs:

```text
./gradlew.bat :modules:ort-common:test -PincludeStress=true --rerun-tasks
```

After 406 landed, that command missed the Lucene lifecycle stress test.
That was not a product regression in 406; it was
`workflow-assumption-drift` in the primary manual gate's opt-in stress
lane. V1 resolves this by adding an explicit stress-suite policy, a
coverage gate, and a policy-derived Gradle task list for `runStress=true`.

Design consequence: guidance now generalizes from "after ORT/session
concurrency changes" to "after any concurrency-sensitive change that
adds or touches an `@Tag("stress")` subject." The inference runtime
reminder remains useful, but it is not the only stress-lane trigger.

## Schedule Policy

Schedules should be explicit rather than decorative:

- Keep schedules only for workflows with an owner and useful artifacts.
- Disable or convert to manual-only any workflow that repeatedly fails
  before its meaningful evidence-producing step.
- A scheduled workflow that cannot explain its red runs through artifacts,
  summaries, or a policy row should not remain scheduled.

## Test Plan

Implemented tests should cover policy parsing and classification with
synthetic `gh run list` / `gh run view`-style JSON.

Snapshot-style Markdown coverage should include:

- all-green workflows,
- failed setup step,
- failed evidence gate step,
- no recent runs,
- tag-triggered release failure.
- CI stress coverage-gate failure (`workflow-assumption-drift`),
- CI stress test execution failure (`product-regression`).

Stress-suite policy coverage should include:

- all policy modules match discovered stress tags,
- discovered stress module missing from policy,
- policy module with no stress tests,
- non-stress tags and comments or strings containing `@Tag("stress")`.

A live dry-run should be used for operational confidence:

```text
node scripts/ci/workflow-signal-health.mjs --repo eliasjustus/JustSearch --md
```

Expected current classification on 2026-04-24:

- `Phase 3 Observability Nightly`: workflow/infra drift when failing at
  `Install jseval Python deps`.
- `Agent Live Eval Nightly`: live-battery lane failure when `Run live
  battery` fails, with downstream summary failures treated as secondary
  symptoms.
- `Build Installer`: release-gate failure owned by tempdoc 374 /
  Production-Reality Verification.

## Open Follow-Ups

- Decide whether repeated `workflow-assumption-drift` should
  automatically recommend disabling a schedule after N consecutive runs.
- Add REST API fallback only if GitHub CLI availability becomes a real
  blocker for agents.
- Consider publishing the Markdown health report as a run summary or
  artifact from a future governance workflow; do not add that until the
  local command has proven useful.
