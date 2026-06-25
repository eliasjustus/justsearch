<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Parallel Agent Worktree Guide

## Worktree Lifecycle

Multiple agent sessions run in parallel, each in its own **git worktree**.
The main checkout (`F:\JustSearch`) stays on `main` and is never switched.

### Creating a worktree

**Within a session** — use the `EnterWorktree` tool:
```
EnterWorktree { name: "feature-name" }
```
This creates a worktree at `.claude/worktrees/<name>/` with a branch
`worktree-<name>` based on local `HEAD` — the project `.claude/settings.json`
sets `worktree.baseRef: "head"`, so worktrees (and `isolation:"worktree"`
subagents) carry your unpushed/just-merged commits instead of branching from a
stale `origin` (tempdoc 618 §1). Config files (`.claude/settings.local.json`,
`.mcp.json`) are tracked, so every worktree already has them.

**Make a worktree dev-ready** — from inside the worktree run:
```
node scripts/dev/prepare-worktree.cjs          # npm ci + installDist
node scripts/dev/prepare-worktree.cjs --no-dist # FE-only (skip the Java dists)
```

**Shared models / runtime**: the dev-runner now resolves `JUSTSEARCH_MODELS_DIR`
from the **main** checkout automatically and auto-stages a CPU llama-server
baseline into an empty `native-bin` (tempdoc 618 §2/§3) — so models and
`default`-variant `ai_activate` work from a worktree with no manual step. Only
if you run a backend outside the dev-runner do you still need to export it:
```
JUSTSEARCH_MODELS_DIR=F:\JustSearch\models
```
GPU `cuda12` stays "Install AI"'s domain; dev-staging leaves it untouched.

**New terminal session** — launch Claude with the `--worktree` flag:
```bash
claude --worktree feature-name
```
Same mechanics as `EnterWorktree` but starts a fresh session in the worktree.

**Subagent isolation** — use `isolation: "worktree"` on the Agent tool:
```
Agent { prompt: "...", isolation: "worktree" }
```
The subagent gets its own temporary worktree. Auto-cleaned if no changes;
preserved (with path/branch returned) if changes were made.

### Leaving a worktree

**Within a session** — use the `ExitWorktree` tool to return to the main
checkout without ending the session. Useful when worktree work is done but
the session continues (e.g., merging from main).

### Cleanup

- **`EnterWorktree` / `--worktree`**: On session exit, Claude prompts whether
  to keep or remove the worktree.
- **`ExitWorktree`**: Returns to main checkout; worktree is preserved for
  later re-entry or manual cleanup.
- **Subagent worktrees**: Auto-cleaned if unchanged; returned path if changed.
- **After merge**: `git branch -d worktree-<name>` from main checkout.

## Hard Rules

1. **Never `git checkout` in the main worktree.** It stays on `main`. All
   feature work happens in worktrees. <!-- rule:never-checkout-in-main -->
2. **Never share a worktree** between two agent sessions. <!-- rule:never-share-worktree -->
3. **One branch per worktree.** Git enforces this, but don't work around it. <!-- rule:one-branch-per-worktree -->
4. **After compaction**, verify your worktree and branch. <!-- rule:after-compaction-verify -->
   The `compact-restore` hook now writes a **Current worktree** block (dir + branch) into the
   restored state (tempdoc 620) — confirm it matches; on a non-compaction session start, check
   directly:
   ```bash
   pwd
   git branch --show-current
   ```
   If either doesn't match expectations, investigate before editing.
5. **Never run destructive git commands in the main worktree.** The main
   checkout may contain uncommitted work from other agents. Destructive
   commands destroy that work silently. <!-- rule:never-destructive-git-in-main -->
6. **Never delete, move, or restore files in the main worktree that you
   didn't create.** Untracked or modified files may belong to another
   agent's in-progress work. If they block your build, ask the user —
   do not remove them unless the user explicitly approves. <!-- rule:never-delete-untracked-in-main -->
7. **Always verify a new worktree's base contains the work you expect**
   before writing code. `worktree.baseRef:"head"` (in `.claude/settings.json`)
   makes `EnterWorktree`/`--worktree`/subagent worktrees branch from local
   `HEAD` by construction, but a manual `git worktree add` ignores it and the
   setting has had harness-version bugs — so assert the base directly:
   `git log -1 --oneline -- <a file your task depends on>` or grep a known
   symbol. This converts the silent "building on a stale base" trap (tempdoc
   618 §1 — local `main` can be dozens of commits ahead of `origin`) into an
   immediate, legible failure. <!-- rule:verify-worktree-base -->

## Enforced by `bash-guard.mjs`

The following commands are **blocked by the PreToolUse hook** at
`scripts/agent-analytics/hooks/bash-guard.mjs` (wired via
`.claude/settings.local.json`) and will fail with an error message.
This is not advisory — the hook prevents execution.

**Blocked in the main worktree** (where `.git` is a directory):
| Command | Why |
|---------|-----|
| `git checkout <branch>` / `git checkout -- .` | Main stays on `main`. Use worktrees. **Single-file restore `git checkout -- <path>` is allowed** (tempdoc 520 P0c). |
| `git switch` | Same as checkout. |
| `git reset --hard` | Destroys uncommitted tracked changes from other agents. |
| `git clean -f` | Deletes untracked files (tempdocs, new code) from other agents. |
| `git restore .` | Discards all uncommitted modifications. |

**Blocked everywhere** (main and worktrees):
| Command | Why |
|---------|-----|
| `git push --force` / `-f` | Rewrites shared remote history. <!-- rule:never-force-push --> |

**Allowed in the main worktree:** `git status`, `git log`, `git diff`,
`git add`, `git commit`, `git push`, `git merge`, `git worktree`,
`git fetch`, `git pull`, `git stash`.

**Warning — `git stash` with staged changes:** Never use `git stash` (especially
`--keep-index`) to inspect the staging area. Use `git diff --cached --stat`
instead. Stash + pop silently drops unstaged modifications when combined with
staged renames. To inspect staged vs unstaged state, use read-only commands:
- `git diff --cached --stat` — what's staged
- `git diff --stat` — what's unstaged
- `git status` — overview of both

**Allowed in worktrees:** All git commands except force-push. Worktrees are
isolated — destructive operations only affect the agent's own work.

## Shared Dev Stack

Only one dev stack runs at a time (memory/port). **Multi-agent safety:** before starting, call
`quick_health`; if another session holds it, get user approval before starting your own or taking it
over (`OWNER_CONFLICT` / `ownership.verdict: CONTENTION`). A `force` takeover requires explicit user
direction. The tools return `ownership.verdict` + `recommendedAction` telling you what to do; stop the
stack when you finish so other agents can use it.

The full model — verdict states (`TAKEOVER_ABANDONED` / `IDLE_HOLD` / `CONTENTION`), the start-tool
error codes, `acquire_when_free`, the takeover/handshake flow, and the read-only `serve-worktree-fe.cjs`
FE live-validate — was relocated to the `/dev-stack` skill (tempdoc 620 Phase 3), since it is needed only
when you run the live backend.

## Merge Workflow

1. **Pre-merge verification (required):** From the main worktree, run <!-- rule:pre-merge-gradle-build -->
   `./gradlew.bat build -x test` **before** merging. If it fails, fix the
   issue in your worktree first. Do not merge broken code into `main` —
   other agents and the CI pipeline depend on `main` compiling.
2. Feature branch merges into `main` (merge commit, not rebase).
3. **Post-merge verification:** Run `./gradlew.bat build -x test` again to
   confirm the merge didn't introduce conflicts. If it fails, fix immediately.
4. After merge: remove worktree, delete branch. On Windows, prefer
   `node scripts/dev/remove-worktree.cjs <path> [--delete-branch]` over
   `git worktree remove` — it survives long `node_modules` paths and unlinks
   `node_modules` junctions link-only, so a junction is removed without
   deleting through into main's real `node_modules` (tempdoc 618 §2).
   This teardown step now also records the `session_id → merge_commit` link for
   agent-telemetry outcome attribution (tempdoc 622 Layer B): it invokes
   `record-merge.mjs` against the merge HEAD. A merge done without running the
   teardown can be backfilled with `node scripts/agent-analytics/record-merge.mjs`.

### Working on shared `main` safely (multi-agent)

The main checkout routinely holds other agents' uncommitted WIP. To keep your
merge clean and avoid sweeping a neighbour's edits (tempdoc 618 §4):

- Resolve the merge **inside your worktree**, then fast-forward `main`, so only
  your files move and the neighbour's WIP stays untouched.
- Stage your own files explicitly (`git add <paths>`), not `git add -A`.
- For inbox notes, use `node scripts/agent-analytics/note-observation.mjs "…"`
  (618 Seam C) — it writes to *your* per-session shard under `docs/observations.d/`,
  not the shared `observations.md`, so a neighbour's commit can no longer reset out
  an un-committed append (618 §9/§12 reproduced that as data loss). Reconcile the
  shards into `observations.md` `## Inbox` with
  `node scripts/agent-analytics/fold-observations.mjs --apply` (run at merge,
  next to `record-merge.mjs`).

## Recovery

If you find yourself on the wrong branch or in the wrong directory:
- Run `git branch --show-current` and `pwd` to orient.
- If commits landed on the wrong branch, cherry-pick them to the correct one.
- If uncommitted work is in a stash, verify which branch it belongs to before
  popping (`git stash show stash@{N}`).
