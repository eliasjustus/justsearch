---
title: "campaign-chain hardening: banked-calibration SHA invalidation, pinned-harness cohort window (DISABLE_AUTOUPDATER), guard mean-extrapolation — the three launch-incident classes from the 624 confirmatory night, converted from lessons to mechanisms"
type: tempdocs
status: "open — charter (2026-07-18). Fully delegable; no founder input needed."
created: 2026-07-18
author: agent (Fable orchestration), chartered after the 624 confirmatory campaign's six-incident night; founder-directed handoff (2026-07-18)
category: eval-infrastructure / campaign-tooling
related:
  - 624-agentic-retrieval-eval-rebuild   # incident ledger (§Confirmatory RESULTS + evidence-dir README)
  - 751-content-addressed-eval-index-cache  # sibling infra lane (warm bug filed there separately)
---

> Charter. Every item traces to a live incident from 2026-07-17/18; none is speculative.

# 758 — campaign chain hardening

## §A. Banked calibration must be SHA-bound (incident #5)

`utility-calibrate` writes `calibration.json` without recording the git state it pinned;
chains skip recalibration when the file exists, so a leftover from an aborted launch attempt
silently imports a stale `config_cohort_key` into a later run (v4 confirmatory: 23:33
calibration at `92ec2e6d` adopted into the `079e63e5` chain → recompose refused; $12.92 run
voided).

Fix: `utility_calibrate.py` stamps `git_sha` (full) + `created_at` into the calibration output
(`scripts/jseval/jseval/utility_calibrate.py:252` neighborhood already computes the manifest);
`utility-run --calibration` fails closed with a "recalibrate: banked calibration pinned at
<sha>, checkout is <sha>" message when the stamp mismatches HEAD. Chains need no change — the
run-side check is the enforcement point.

## §B. Pinned-harness cohort window (incident #6)

Claude Code auto-updated 2.1.212→2.1.214 mid-night, splitting `agent_cohort_key`
(`cli_version` is hashed — `scripts/jseval/jseval/agent_manifest.py:160-179`) between the
main campaign and the same-night stratum rerun; a rerun could not rejoin the cohort without
downgrading the founder's shared global CLI.

Fix: campaign chains (`chain-confirm.bat` env block and successors) set
`DISABLE_AUTOUPDATER=1` for the whole chain lifetime, and the run preflight RECORDS the CLI
version at chain start + asserts it unchanged before each utility-run step (cheap `claude
--version` compare; fail closed with the version pair in the message). Verify the env var
against current Claude Code docs before relying on it; if unsupported, the preflight assert
alone still converts a silent cohort tear into a legible pre-spend failure.

## §C. Budget-guard extrapolation (incidents #2 — fired twice across Step-2 + confirmatory)

`step2-budget-guard.py` extrapolates missing calibrations at max(known); with expensive-first
ordering it over-projects ($31.77 → $127.08 vs ~$90 true) and aborts spuriously. Cheapest-first
ordering is now convention, but the formula should also print the mean-based projection
alongside max-based, and the abort message should name the ordering sensitivity so a future
chain author doesn't rediscover it.

## §D. Acceptance

- §A: unit test — mismatched-SHA calibration → utility-run refuses with the remedy message;
  matched → proceeds. One smoke calibrate→run cycle green.
- §B: preflight assert covered by a test that fakes a version change between chain start and
  run step; env-knob presence documented in the chain template comment. NOTE (751 review
  carry-over): if an env knob becomes load-bearing for identity, it belongs in the env-knob
  register/gate discussion opened by the 751 review — coordinate, don't fork.
- §C: guard unit test for both projections; message text asserted.
- All three: `python -m pytest scripts/jseval/tests -q` green (2 known-RED correction-probe
  tests excepted per expected-state).
