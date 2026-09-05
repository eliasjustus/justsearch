---
title: "CI enforcement closure, tier 1: a self-hosted Windows/GPU runner on the owner's machine for the perf ratchet, mutation ratchet, soak suite, full jseval pytest, and installer-over-release qualification"
type: tempdocs
status: CHARTERED, UNBLOCKED (2026-09-02) — decision revised same day: use the EXISTING self-hosted runner (verified online: justsearch-gpu-runner, labels self-hosted/Windows/X64/gpu), threat evaluated in §Decision (fork-PR RCE already gated by check-workflow-triggers, tempdoc 747 P-D); schedule proposed in §Schedule (owner may veto); item 0 amends ADR-0044, SHA-pins Actions, scopes secrets to an Environment, fixes the docs-lint label mismatch
created: 2026-09-02
updated: 2026-09-03
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

## Decision (2026-09-02, revised the same day after the founder's challenge)

**Use the self-hosted runner that already exists** — verified online 2026-09-02 as
`justsearch-gpu-runner` (labels `self-hosted, Windows, X64, gpu`) on the owner's workstation,
the machine every jseval/perf number in the register came from, so `MachineFingerprint` keeps
runs comparable. `docs-lint.yml:20` already targets it, though with a stale label (see §O).
No new runner, no cloud GPU runner. Jobs are `workflow_dispatch` plus a `schedule`, from `main`
only. Adding `schedule` is the one step beyond ADR-0044 ("self-hosted and specialty workflows
remain manually dispatched unless separately amended") — so item 0 below amends the ADR.

**Threat evaluation (public repository + runner on the maintainer's machine).** The classic
vector — a fork pull request whose workflow executes on the self-hosted runner — is already
closed mechanically, not by convention: `scripts/ci/check-workflow-triggers.mjs:15-25,120-124,
290-299` carries a **hard, fail-closed invariant** (tempdoc 747 P-D) that any job whose
`runs-on` is not a known hosted label may not use `pull_request`, `pull_request_target`,
`pull_request_review`, `pull_request_review_comment` or the other externally triggerable events,
with unknown labels treated as self-hosted. The realistic residuals are:

| residual | likelihood | handling |
|---|---|---|
| code the owner merged to `main` runs on the owner's machine on a schedule | certain, by design | same trust as the owner's daily local builds; branch protection + merge queue (829) already gate `main` |
| a third-party Action referenced by a self-hosted workflow is compromised (tag-pinned today: `actions/checkout@v*`, `setup-java@v*`, `gradle/actions@v*`, `dtolnay/rust-toolchain@stable`) | low but real (the 2025 `tj-actions` class) | **SHA-pin every Action used by a self-hosted workflow** (item 0) and let Dependabot's `github-actions` ecosystem bump them |
| a job on the runner reads repository secrets | avoidable | these jobs need none; verify the signing secrets are scoped to an Environment used only by the hosted `build-installer` workflow (item 0) |
| persistence on a non-ephemeral runner after a compromise | only reachable via the two rows above | accepted: `--ephemeral` would discard the warm Gradle/model caches these jobs exist to use; the runner already runs as a service account |
| surprise load on the workstation (ADR-0044's actual concern) | certain without care | owner-chosen nightly hour + weekly soak day; jobs refuse under a live dev-stack lease (`OWNER_CONFLICT`) |

Net: proceed. The one vector that would make this a bad idea is the one the repo already forbids
and gates.

## §O. Owner action (none blocking)

1. Runner confirmed online 2026-09-02 (see §Decision). Nothing to do.
2. Schedule proposed below; veto by editing §Schedule.
3. Optional: Settings → Actions → General: "Require approval for all outside collaborators"
   (belt and braces; the trigger gate is the real control).

**Routed defect (found while verifying):** `.github/workflows/docs-lint.yml:20` targets
`runs-on: [self-hosted, Windows, X64, justsearch-perf]`, but the only runner carries the label
`gpu`, not `justsearch-perf` — a dispatch of docs-lint queues forever. Fix in item 0: align the
label (`gpu`), and make `check-workflow-triggers.mjs` or a sibling assert every self-hosted
label set is satisfiable by a registered runner label set (read from a committed
`governance/runners.v1.json`, since CI cannot query the API).

## Scope

| # | mechanism | today | job |
|---|---|---|---|
| 0 | **Preconditions** | ADR-0044 says manual-only for self-hosted; Actions tag-pinned; **no GitHub Environments existed when verified 2026-09-02, so the four repo secrets incl. the signing command and both private keys were readable by any workflow that runs on the runner**; docs-lint label mismatch | fix the docs-lint label; amend ADR-0044 with a "scheduled self-hosted lanes" section (probes via `adr-coverage`); SHA-pin every `uses:` in workflows that target the runner; confirm `check-workflow-triggers.mjs` permits `schedule` on self-hosted jobs (if its policy forbids, stay dispatch-only and record that); use the protected `release-signing` Environment named by tempdoc 905 for the two signing-capable hosted workflows, with owner-controlled secret migration |
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

## §Schedule (proposed by the orchestrating session; owner vetoes by editing)

nightly: 03:00 local (`cron: '0 1 * * *'` UTC in summer; the agent converts) · weekly soak:
Sunday 02:00 local. Both refuse to start under a live dev-stack lease.

## §Status

Chartered, unblocked. First draft wrongly asked the owner to install a new runner and ignored
the existing trigger invariant; corrected 2026-09-02 after the founder's challenge; runner and
secret facts verified read-only the same day. Item 0 first (it also closes the secret-scoping
exposure and the docs-lint label defect).
