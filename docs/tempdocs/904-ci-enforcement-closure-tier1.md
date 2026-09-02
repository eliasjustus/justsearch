---
title: "CI enforcement closure, tier 1: a self-hosted Windows/GPU runner on the owner's machine for the perf ratchet, mutation ratchet, soak suite, full jseval pytest, and installer-over-release qualification"
type: tempdocs
status: CHARTERED (2026-09-02) — decision made by the orchestrating session under founder delegation; BLOCKED on one owner action (install the runner, §O) before any agent work
created: 2026-09-02
updated: 2026-09-02
lane: 887 L2
model: opus (takeover) after §O
parent: 887-improvement-landscape-register
related:
  - 888-ci-enforcement-closure-tier0   # the hosted half; this lane is the rest of 887 §X1
  - 640 / 647 (perf-gate)  · 555 / 576 (test-efficacy)  · 09-testing-strategy §soak
  - 802-release-artifact-provenance    # step 3 "wire the other 131 pytest files" = owner decision, now made
  - 734-0.2.0-sandbox-convergence / 617 §9  # upgrade-from-release rounds, GUI-gated
  - ADR-0044 public hosted CI fact lanes (self-hosted/specialty stay manual or scheduled)
  - 651 / 668 (CI cost and walltime attribution)
---

# 904 — CI enforcement closure, tier 1

## Briefing for the agent picking this up

Fresh start. Read this file, 887 §X1, and 888 (the hosted half; assume it has landed or is
landing — do not duplicate). This lane exists because five built mechanisms need a GPU, a real
Windows box, or hours of walltime that hosted `ubuntu-latest` cannot give. **Do not start until
§O's runner is registered** (`gh api repos/:owner/:repo/actions/runners` shows it online with the
labels below). Load `/ci-triage` for workflow work and `/jseval` for the perf/soak commands.
Every new job is `workflow_dispatch` + a `schedule`, never on PR (ADR-0044; a self-hosted runner
must not run untrusted PR code). One PR per item.

## Decision (2026-09-02)

The runner is the owner's development machine (the one every jseval/perf number in the register
already came from — `MachineFingerprint` makes runs comparable), registered as a GitHub
self-hosted runner with labels `self-hosted, windows, gpu, justsearch-dev`, running only
scheduled and dispatched jobs, and only from `main`. No cloud GPU runner: the cost is recurring
and the numbers would not be comparable with the register's baselines. Scheduled windows are
nightly (fast items) and weekly (soak), at hours the owner sets in §O.

## §O. Owner action (one-time, ~15 minutes)

1. Repository → Settings → Actions → Runners → New self-hosted runner (Windows x64); run the
   printed `config.cmd` with `--labels self-hosted,windows,gpu,justsearch-dev --runasservice`.
2. Restrict: Settings → Actions → General → "Require approval for all outside collaborators";
   keep fork PRs off self-hosted by never adding `pull_request` triggers to these workflows
   (`check-workflow-triggers` enforces the trigger policy).
3. Choose the nightly hour and the weekly soak day; write them into §Schedule below.
4. Confirm the dev stack is not leased during those windows (the jobs use `justsearch-dev`
   tooling and will refuse under `OWNER_CONFLICT`).

## Scope

| # | mechanism | today | job |
|---|---|---|---|
| 1 | **Perf ratchet** `jseval perf-gate` (640/647) | advisory hook nudge only; `ci.yml` never runs it | nightly: clean lifecycle run on the standard strata → `perf-gate --mode gate`; red opens a GitHub issue via the workflow (labels `perf-regression`), never auto-rebaselines |
| 2 | **Mutation ratchet** `test-efficacy` (555) | fully built; nothing produces `pit-strength-report.v1.json` | nightly: `./gradlew.bat pitest` over the 18 seams → `report-pit-strength.mjs` → `run.mjs --gate test-efficacy --mode gate`; strength regression opens an issue |
| 3 | **Soak suite** (`SoakSuiteTest`, 4 h) | opt-in flag, no runner | weekly: `-PincludeSoakTests=true`; extend with the two disk-growth assertions 895 measures (index generations, log bytes) once 895 reports |
| 4 | **Full jseval pytest** (131 files unrun; 802 step 3) | one file in hosted CI | nightly: `pytest scripts/jseval/tests -q` on the runner (Python + models present); pin known-red files in `expected-state.v1.json` with exits, then burn them down |
| 5 | **Installer-over-release qualification** (617 §9, 734 rounds) | human-run in Windows Sandbox | dispatch-only: build candidate → `verify-installer-nsis-win.ps1` → `sandbox-guest-silent-test.ps1 -Mode upgrade-from-release`; Windows Sandbox needs a GUI session, so this job runs only when the owner dispatches it from an unlocked desktop — document that in the workflow header instead of pretending it is unattended |
| 6 | **Flake census** `report-flake-trend.mjs` | unwired | rides job 2's Gradle run; report artifact, advisory |

## Acceptance criteria

- Each job: one green run on the self-hosted runner linked in §Status, with duration; the
  next scheduled run also green (two consecutive runs before declaring wired).
- Job 1 and 2 red paths tested once by an injected regression on a throwaway branch (documented,
  reverted): the issue is opened with the gate's output.
- `node scripts/ci/check-workflow-triggers.mjs` green; no `pull_request` trigger on any
  self-hosted job.
- `docs/reference/contributing/agent-guide.md` CI section lists the tier-1 jobs and how to read
  their artifacts; `09-testing-strategy.md` soak paragraph updated.

## Constraints

- Never `git push` from the runner; never rebaseline automatically; never auto-fix.
- Dev-stack lease rules apply to the runner exactly as to an agent (`/dev-stack`).
- Non-goals: hosted-CI changes (888), cloud runners, perf floor values (lane E / 647 recompose).

## §Schedule

nightly: (owner fills) · weekly soak: (owner fills)

## §Status

Chartered; blocked on §O.
