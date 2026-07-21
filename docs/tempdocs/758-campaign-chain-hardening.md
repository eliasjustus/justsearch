---
title: "campaign-chain hardening: banked-calibration SHA invalidation, pinned-harness cohort window (DISABLE_AUTOUPDATER), guard mean-extrapolation — the three launch-incident classes from the 624 confirmatory night, converted from lessons to mechanisms"
type: tempdocs
status: "implemented (2026-07-21). All of §A/§B/§C landed with tests; see §G implementation log."
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

## §E. Design (2026-07-21, implementing agent)

**Seam reuse, not new structure.** §A and §B are two instances of one shape: a *banked
pre-computation carries the identity of the environment it pinned, and its consumer fails
closed when that identity has drifted before spending money.* This conforms to the seam
already established in `utility_calibrate.py` — `assert_watched_roots_scoped` /
`assert_mcp_config_http_typed`: a named-error, fail-closed, message-carries-remedy assert
guarding a run before spend. I add two peer asserts in the same module rather than a new
mechanism:

- `assert_calibration_git_sha(calib, current_git_sha=…)` — §A. Missing stamp → refuse
  ("legacy calibration without git_sha stamp — recalibrate"); mismatch → refuse
  ("recalibrate: banked calibration pinned at <sha>, checkout is <sha>").
- `assert_calibration_cli_version(calib, current_cli_version=…)` — §B. Missing stamp →
  refuse; mismatch → refuse (names DISABLE_AUTOUPDATER + incident #6 + recalibrate).

Both stamps (`git_sha`, `cli_version`, `created_at`) are written by `calibrate()` into the
returned dict (persisted verbatim by `cmd_utility_calibrate`). Run side calls both asserts
inside `cmd_utility_run`'s existing `if calibration:` block, before `run_utility_eval`, reusing
the already-computed `cli_version = aur.claude_cli_version()` and `manifest._git_sha_full()`.
No new file needed — the §B "harness_pin.py" suggestion is redundant with the existing module.

**Orphans:** none — all three items are fail-closed additions; §C is messaging-only over an
unchanged decision.

**§B env-var mechanism:** verified against Claude Code docs (see implementation log). The
`.bat` env-block knob is the belt; the jseval-side `cli_version` stamp+assert is the
suspenders — the assert converts a silent cohort tear into a legible pre-spend failure even if
the env knob is unsupported/ineffective.

## §F. Reach (principle, candidate scope, retirement)

**Principle — "pin-carries-provenance, consumer-fails-closed-on-drift":** any artifact that
banks a pre-computation for later reuse (across a resume, a chain step, or an aborted-relaunch
gap) must record the identity of the inputs it pinned, and every consumer must fail closed when
that identity no longer matches the live environment. Candidate scope beyond calibration: the
751 content-addressed index-cache (adopting a published entry), `707` corpus certifications,
any `--resume`-shaped jseval flow. `check_watched_roots_scoped` and the cert-signature checks
are earlier instances — this is not a new invariant, it is the same one applied to the
calibration artifact, which was the one banked artifact still trusting position over identity.

**Evidence it earns its keep:** a prevented stale-adoption (a refused run naming a SHA/version
pair, as incidents #5/#6 would have been). **Retirement condition:** if calibration files ever
become strictly single-use (written and consumed within one un-resumable process, never banked
on disk across attempts), the drift window closes and these asserts become dead weight — retire
them then.

**Env-knob register (751 carry-over, per §D NOTE):** `DISABLE_AUTOUPDATER` is currently a
chain-template convenience knob, NOT load-bearing for identity — the load-bearing control is the
`cli_version` stamp+assert. If a future change makes the env knob itself the identity guarantee,
it belongs in the env-knob register/gate discussion opened by the 751 review; coordinate there,
do not fork a second register here.

## §G. Implementation log (2026-07-21)

All file:line references are in this worktree checkout.

**§A — banked calibration SHA-binding.**
- Stamp written: `scripts/jseval/jseval/utility_calibrate.py:calibrate()` captures
  `git_sha = mf._git_sha_full()` and adds `git_sha` + `created_at` (UTC isoformat) to the
  returned calibration dict (return block near the `config_cohort_key` field). Persisted
  verbatim by `cmd_utility_calibrate` (`commands/utility.py:460`).
- Run-side fail-closed: `assert_calibration_git_sha(calib, current_git_sha=…)` (new, in
  `utility_calibrate.py`) raises `StaleCalibrationError` on mismatch (message names BOTH SHAs +
  "recalibrate"), on missing stamp ("legacy calibration without git_sha stamp — recalibrate"),
  and on unresolvable HEAD. Wired into `commands/utility.py` `cmd_utility_run`'s `if calibration:`
  block, before `run_utility_eval`, using `manifest._git_sha_full()`.

**§B — pinned-harness cohort window.**
- Docs verification (REQUIRED): `DISABLE_AUTOUPDATER` **IS an officially supported env var** —
  https://code.claude.com/docs/en/settings ("Disable auto-updates entirely … Set this
  environment variable to prevent Claude Code from automatically updating"); the related
  settings key is `autoUpdatesChannel` (`latest`/`stable`). So the env knob is used as
  designed, not a workaround.
- `.bat` knob: `scripts/jseval/chain-confirm.bat` env block now sets `DISABLE_AUTOUPDATER=1`
  with a comment citing incident #6 + the docs URL.
- jseval-side guard (the load-bearing half): `calibrate()` stamps `cli_version` (the live
  `claude --version` via `aur.claude_cli_version()`, captured once and reused for the pilot
  call). Run side: `assert_calibration_cli_version(calib, current_cli_version=cli_version)`
  raises `HarnessVersionDriftError` on drift (names the version pair + `DISABLE_AUTOUPDATER=1`
  + recalibrate) and on missing stamp. No new `harness_pin.py` — redundant with the existing
  fail-closed-assert seam in `utility_calibrate.py`.

**§C — budget-guard extrapolation legibility.**
- `scripts/jseval/step2-budget-guard.py`: prints the max-based projection labelled
  `[max-based, authoritative]` (unchanged decision) AND a mean-based projection labelled
  informational; the abort message now names the ordering sensitivity ("max-extrapolation
  over-projects when the most expensive dataset calibrates first — cheapest-first ordering
  recommended") and cites the mean figure for reference. The `if projected > a.cap` decision
  still uses the max-based `projected` — verified unchanged by
  `test_abort_decision_stays_max_based_even_when_mean_is_under_cap`.

**Tests** (`scripts/jseval/tests/`):
- `test_utility_calibrate.py` — `TestAssertCalibrationGitSha` (mismatch→refuse+both SHAs,
  match→proceeds, missing→refuse, unresolvable→refuse) and `TestAssertCalibrationCliVersion`
  (changed→refuse+pair+env knob, unchanged→proceeds, missing→refuse).
- `test_step2_budget_guard.py` (new) — both projections printed, abort message text asserted,
  max-based decision precision guard, zero-missing case. Loads the hyphenated script via
  `importlib.util.spec_from_file_location` (test_delivery_tier_735 pattern).

**Behavior-change discipline:** §A/§B are fail-closed additions (no existing check weakened);
no existing test invoked `cmd_utility_run --calibration`, so the new asserts break nothing. §C
is messaging-only over an unchanged abort decision.
