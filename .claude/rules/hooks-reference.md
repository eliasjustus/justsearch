<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Hooks — Behavioral Contract

Hooks fire automatically and deliver their own guidance at the moment they fire — each hint or
block message carries its remedy, so this file no longer catalogs them (tempdoc 681; the per-hook
catalog was redundant with fire-time delivery). The hook layer's authority is
`governance/agent-hooks.v1.json`, enforced by the `hook-integrity` gate (wiring, load, bite).

**The one rule: when a hook blocks or redirects you, adapt — don't retry verbatim.** The block
message names the allowed alternative; use it.

> **Kill switch (tempdoc 520 P1c):** `JUSTSEARCH_DISABLE_HOOKS=1` disables all session-affecting
> hooks for fast recovery if one misbehaves. Shared plumbing (stdin read, repoRoot, atomic write,
> kill switch): `scripts/agent-analytics/lib/hook-base.mjs`.

## Blocking guards (hard stops)

- **bash-guard** — in the MAIN worktree blocks: `git checkout <branch>` / `git switch`,
  `git reset --hard`, `git clean -f`, `git restore .`, and whole-tree `git checkout -- .`
  (single-file `git checkout -- <path>` is allowed). Everywhere: `git push --force` and
  unconditional `sleep >= 1s` (readiness-check condition-polls are allowed).
- **repeat-guard** — blocks 3+ consecutive identical tool calls; vary the approach.
- **build-counter** — blocks Gradle build/test after 3+ consecutive failures; diagnose the root
  cause before building again.
- **intervene** — caps an explicit offset/limit whose slice would blow Read's token ceiling; blocks
  unbounded re-reads of the same file after 10.
- **maintain-doc-hint** (Stop) — blocks once per governed region per session if you edited a
  `consult-register` governed region without updating its governing doc; stating why no doc
  change was needed satisfies it.

## Hint hooks (non-blocking; the message carries the full remedy)

- Edit/Write-triggered: ui-shot-hint, consult-doc-hint, docs-regen-hint, lockfile-hint,
  ssot-hint, test-edit-hint, stress-test-hint, governance-hint, seam-hint, search-engine-hint.
- Bash-triggered: pipe-mask-hint (build exit masked by trailing pipe),
  governance-precommit-hint (`git commit`), docs-granularity-hint (`git push`),
  dataset-cache-hint (corpus/dataset fetch — use the cache-backed `jseval corpus-fetch-*`, 709),
  exec-substrate-hint (scoop `&`-paste, `gh` wait-loops, piped py — use `run-gh`/`run-py.mjs`, 743).
- Read-triggered: tempdoc-age-hint.
- PostToolUse (886 PR 4): spawn-cost-hint (`Agent` return cost), context-ceiling-hint (300k/500k).

## Transparent (no action needed)

compact-save / compact-restore (restores post-compaction state, leading with current
worktree + branch), subagent-guide (injects the Hard-Invariants baseline brief into subagents),
mcp-session-inject, otlp-sink-ensure.
