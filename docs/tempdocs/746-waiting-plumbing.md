---
title: "746 — Waiting-plumbing slice: Monitor-based CI waits + worktree-teardown cleanup (743 P-A1 + P-I)"
type: tempdocs
status: "COMPLETE 2026-07-17 — all work items shipped: P-A1/P-I via PR #213 (CI-wait pattern, remove-worktree self-match fix + junction evaluation, .worktreeinclude), Monitor bug closed DO-NOT-FILE via PR #229. Successor guidance: 743's second wave (session a6d2af56) mechanized the CI-wait prose as scripts/dev/run-gh.mjs checks-wait and added scripts/dev/run-watcher.mjs for long-run supervision; the publish skill now points at both. Nothing remains here."
created: 2026-07-16
author: agent session f7580e17 (Fable 5)
category: agent-process / plumbing
related:
  - 743 (parent program — P-A1/P-I evidence, dispositions; WAITING = 12.5% of baseline window tokens, T1)
  - 727 (friction mining — several fixed hooks live in the same teardown path)
---

# 746 — Waiting-plumbing slice (P-A1 + P-I)

## Charter

Implement 743's two founder-approved plumbing proposals. Pure plumbing per principle 6 — no
behavioral/safety tradeoff, no pilot required. Evidence: T1 (WAITING 12.5% of all window
tokens; worst sessions 29-50%), R4 (Monitor is the documented long-wait pattern; junction
handling native since v2.1.205), plus live specimens from session f7580e17 itself
(base-moved-mid-CI double merge refusal; `gh pr checks --watch` exiting on "no checks
reported"; remove-worktree self-match kill, reproduced twice).

## Work items

### P-A1 — CI-wait migration
1. **Publish skill** (`.claude/skills/publish/SKILL.md`): prescribe the robust CI-wait
   pattern — Monitor tool as primary; where a shell watcher is used instead, it MUST
   condition-poll until checks REGISTER before watching (the "no checks reported"
   immediate-exit trap), and never chain merge-after-watch blindly (base can move mid-CI —
   re-check mergeability, catch up via `git merge`, re-watch).
2. **Sweep other guidance** mentioning `gh pr checks --watch` / `gh run watch` patterns
   (ci-triage skill, agent-guide, common-workflows) — align, don't duplicate.
3. **Upstream bug draft** — investigated and closed **DO-NOT-FILE** (see the updated section
   below; NOT-REPRODUCED on 2.1.212, and the original anecdote is most likely the known
   #72171/#75438 restart class). Kept as a record of the disambiguation, not an action item.

### P-I — teardown cleanup
4. **`scripts/dev/remove-worktree.cjs`**: delete the custom junction-unlink logic (native in
   Claude Code ≥2.1.205 — but NOTE: this script also runs OUTSIDE the harness where native
   handling doesn't apply; verify what the script's own `git worktree remove` call does with
   junctions before deleting the belt-and-braces — if plain git deletes through junctions on
   this platform, KEEP the logic and record why, per fix-root-causes).
5. **Self-match fix** (inbox condition, reproduced 2×): the holder-scan kills its own
   invoking process chain when the command line contains the worktree path. Exclude self,
   ancestors, and the script's own PID from the kill list. Regression test.
6. **`.worktreeinclude`**: evaluate copying the gitignored `.mcp.json` / `settings.local.json`
   directly into new worktrees (currently seeded from `.example` templates by
   prepare-worktree.cjs). Adopt only if it composes with the existing seeding (fallback
   preserved); otherwise record why not.

## Upstream Monitor bug — investigated, DO NOT FILE (updated 2026-07-17)

The original plan here was to file a "Monitor streams silently stop firing" issue against
anthropics/claude-code. Two investigations (2026-07-17) closed that out as **do-not-file**:

1. **Issue-landscape survey** (read-only, GitHub + changelog): no existing issue matches our
   exact symptom, BUT it's a crowded, recurring problem class (~9 related issues since Jan
   2026). The nearest cases rule themselves out — #76508 is the same tool but a *delay* not
   silence (Linux only); #77300/#77578 are subagent/teammate-scoped and state the main
   session is immune. Critically, **#72171 / #75438 (cross-restart / `--resume` background
   orphaning) explain our strongest anecdote almost verbatim** — the 707 session (109145ac)
   had documented PC restarts and produced the literal "No completion record was found for
   this background shell command from the previous session" notice, which is *their*
   signature, not a novel single-session bug. Verdict was a CONDITIONAL file-new, gated on a
   clean single-session repro.

2. **Controlled reproduction** (fresh single-session, `claude --version` = 2.1.212): the
   condition FAILED. **7/7 Monitor arms fired**, all within 1-3s, weighted toward long
   watches (10/15/20-min), in one unbroken session, each corroborated by an independent
   wall-clock oracle. NOT-REPRODUCED.

**Conclusion:** the novel single-session silent-death does not reproduce on current
(2.1.212); the observed pain is most consistent with the already-reported cross-restart
class (#72171/#75438) plus an older CLI (heavy background-task plumbing churned across
2.1.20x → 2.1.212). Filing would have been a duplicate or non-repro — the pre-file
disambiguation is exactly what prevented that. **Keep the ScheduleWakeup belt-and-braces
workaround** (cheap insurance; the CI-wait pattern in `.claude/skills/publish/SKILL.md`
already carries the caveat). **Re-open only** if a real repro surfaces with the differentiators
the repro test did NOT exercise: heavy concurrent Monitor/task load, a long idle gap between
arm and next turn, a heavy watched command (real build/eval, not a bash loop), or very long
total session elapsed time — and first re-check it isn't #72171/#75438 or the #76508 delay.
Repro harness + raw trial table preserved in the repro session's scratchpad for reuse.

## Acceptance

- All existing hook/script tests green + new regression tests for items 4-5.
- Full publish protocol (this touches shared teardown infrastructure → refute-first review
  before merge).
- Each artifact names its workflow moment (survival law) — these are all already wired
  (skills load at invocation; remove-worktree runs at teardown).
