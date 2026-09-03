---
name: session-closeout
description: >-
  Before ending the session, commit outstanding work and verify the
  tempdoc/evidence/remaining-work notes are sufficient for another agent to
  continue.
---
<!-- generated from .claude/skills by scripts/docs/codex-skills-projection.mjs; do not edit -->

> Codex projection: `$skill-name` is the equivalent of a Claude `/skill-name` invocation. When this workflow names a Claude-only tool, use the available Codex capability that preserves the same policy and acceptance criteria.

Before ending this agent session, first check git status in your worktree and in main if you touched it — don't leave anything uncommitted for someone else to discover hours later; commit it. Then run `node scripts/agent-analytics/world-state.mjs` and check your own worktree's row — if it would read as STRANDED-FINISHED (committed but unpushed, about to go idle) or DIRTY-IDLE, push or note it before you stop. Then run `node scripts/dev/agent-spawn-sweep.cjs --occasion session-closeout --session-id <your session id>` (tempdoc 861 §6.4's `session-closeout` occasion — a human-visible sweep covering what automation missed: any registered helper process, e.g. a ui-shot Vite or `serve-worktree-fe`, that a dead prior turn left behind) and report what it found — reaped, left as contention, refused, or reported; a non-empty result is worth a line in your summary even when nothing needed reaping. Then critically check whether the tempdoc, implementation state, verification evidence, and remaining-work notes are good enough for another agent to continue without reading this chat. Update the tempdoc and relevant documentation if needed. Verification claims recorded in the tempdoc must each carry their evidence pointer (test name, command output, screenshot, evidence-bundle run-id); a claim without one gets listed under unverified assumptions instead. Identify any unverified assumptions, deferred checks, stale docs discovered, or follow-up work that should not be forgotten. Since this tempdoc may become public history once merged, make sure it doesn't depend on private/internal-only context that wouldn't make sense to an outside reader. Then summarise the session state for me in simple terms.

If anything in this session is waiting on me, end the summary with an explicit two-list state (743 P-N, founder-approved 2026-07-17): **BLOCKED ON YOU:** each item with what exactly you need and why; **PROCEEDING / DONE:** what continues or is finished regardless. Never let one pending decision read as "everything is waiting" — that mislabeling has cost silent idle time before (743 finding D).
