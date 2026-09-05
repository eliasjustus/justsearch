# Hooks — Behavioral Contract

Hooks fire automatically and deliver their own guidance at the moment they fire — each block
message carries its remedy, so this file does not catalog them. The hook layer's authority is
`governance/agent-hooks.v1.json`, enforced by the `hook-integrity` gate (wiring, load, bite).

Tempdoc 930 row 4 retired the Bash guard and 21 advisory hint hooks. What replaced them:
force-push is refused by **native `permissions.deny`** in `.claude/settings.json`
(`Bash(git push --force*)`, `Bash(git push -f*)` — prefix match, per compound-command segment;
the refspec form `git push origin +main:main` is not expressible and is not covered there).
Codex has no `permissions.deny`, so `codex-hook-adapter.mjs` carries the equivalent stateless
refusal for its own shell events — token-exact, per segment, quoted spans stripped, and it does
cover the `+refspec` form. Everything else those hooks pushed at you is a rule you read:
`.claude/rules/`, `CLAUDE.md`, and `governance/consult-register.v1.json` (consult a row when you
edit a path it covers).

**The one rule: when a hook blocks or redirects you, adapt — don't retry verbatim.** The block
message names the allowed alternative; use it.

> **Kill switch:** `JUSTSEARCH_DISABLE_HOOKS=1` disables all session-affecting hooks for fast
> recovery if one misbehaves. Shared plumbing (stdin read, repoRoot, atomic write, kill switch):
> `scripts/agent-analytics/lib/hook-base.mjs`.

## Blocking guards (hard stops)

- **repeat-guard** — blocks 3+ consecutive identical tool calls; vary the approach.
- **build-counter** — blocks Gradle build/test after 3+ consecutive failures; diagnose the root
  cause before building again.
- **intervene** — caps an explicit offset/limit whose slice would blow Read's token ceiling;
  blocks unbounded re-reads of the same file after 10; flags an over-cap tempdoc write.
- **subagent-model-guard** — blocks an `Agent` spawn with no explicit `model`.

## Path-scoped regen pointers (non-blocking)

`docs-regen-hint`, `ssot-hint`, `lockfile-hint`, `mcpb-repack-hint` — each names the regeneration
step to run after you edit its paths.

## Agent-spawn reaper (non-blocking)

`agent-spawn-sweep-hint` (SessionStart), `agent-spawn-build-hint` (a registered spawn holding a
path your build is about to write), `agent-spawn-session-end-reap` (SessionEnd).

## Transparent (no action needed)

compact-save / compact-restore (restores post-compaction state, leading with current worktree +
branch), subagent-guide (injects the Hard-Invariants baseline brief into Explore/Plan subagents),
worktree-base-hint, mcp-session-inject, export-session-env, otlp-sink-ensure, dispatch,
codex-hook-adapter (the Codex projection of the same manifest).
