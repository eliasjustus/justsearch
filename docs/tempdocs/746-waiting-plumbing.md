---
title: "746 — Waiting-plumbing slice: Monitor-based CI waits + worktree-teardown cleanup (743 P-A1 + P-I)"
type: tempdocs
status: "open — implementation started 2026-07-16 (session f7580e17); founder-approved via 743's disposition record"
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
3. **Upstream bug draft** (Monitor streams silently never firing — the founder-observed
   "almost all agents relying on monitors never get woken" failure): draft the report with
   local reproduction pointers into this tempdoc. **Filing is founder-gated** (public,
   outward-facing).

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

## Upstream bug draft (item 3 — FILING IS FOUNDER-GATED, not yet filed)

Proposed issue for anthropics/claude-code, drafted 2026-07-16:

> **Title:** Long-running Monitor streams can silently stop firing (no completion, no error)
>
> **Body sketch:** On Windows 11 (CLI ≥2.1.20x era), Monitor streams watching long-running
> local processes (multi-hour eval pipelines) frequently end without ever delivering a
> completion event — the watched process exits normally, but the session is never woken;
> no timeout, no error, no transcript marker. Observed by multiple independent sessions on
> one machine over several weeks ("almost all agents that rely on monitors end up never
> being woken up" — maintainer). Workaround in production here: belt-and-braces
> ScheduleWakeup polling ticks alongside every Monitor. A related-but-distinct case: a
> previous session received a "No completion record was found for this background shell
> command from the previous session" notice, suggesting teardown/restart loses monitor
> state. Reproduction pointers available: session transcripts with Monitor-armed +
> never-fired sequences (2026-07-1x, sessions 109145ac, 50ad1b65). Expected: either the
> event fires, a timeout fires, or a failure is surfaced — silence is the only wrong
> behavior. Happy to provide sanitized transcript excerpts.

Founder: say "file it" and a session opens this issue verbatim (minus this note).

## Acceptance

- All existing hook/script tests green + new regression tests for items 4-5.
- Full publish protocol (this touches shared teardown infrastructure → refute-first review
  before merge).
- Each artifact names its workflow moment (survival law) — these are all already wired
  (skills load at invocation; remove-worktree runs at teardown).
