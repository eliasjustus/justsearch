<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Parallel Agent Worktree Guide

## Worktree Lifecycle

Multiple agent sessions run in parallel, each in its own **git worktree**.
The main checkout (`F:\JustSearch`) stays on `main` and is never switched.

### Creating a worktree

**Within a session** — `EnterWorktree { name: "feature-name" }` creates
`.claude/worktrees/<name>/` on branch `worktree-<name>` based on local `HEAD`
(`worktree.baseRef: "head"` — carries your unpushed/just-merged commits,
tempdoc 618 §1). Make it dev-ready from inside:
`node scripts/dev/prepare-worktree.cjs` (`--no-dist` for FE-only) — it also
seeds the gitignored `.mcp.json` / `settings.local.json` from their `.example`
files. Shared models and the shared cuda12 llama-server resolve from the
**main** checkout automatically (GPU-only by design — no CPU fallback;
inference fails CLOSED without cuda12). Full mechanics — config-seeding
caveats, cuda staging, env vars for backends started OUTSIDE the dev-runner —
in `docs/reference/contributing/common-workflows.md` §Worktree mechanics
(relocated, tempdoc 681).

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
- **After merge**: GitHub deletes merged source branches; delete local
  branches only after verifying they were merged.

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
`git fetch`, `git pull`, `git stash`. Branch protection can reject a direct
`git push` to `main` (confirmed 2026-07, tempdoc 695) — route the change
through a worktree + PR instead. `git commit` on `main` stays possible
regardless, so a rejected push can strand local commits ahead of `origin`.

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
stack when you finish so other agents can use it. Long measurement campaigns should declare
`leaseDurationSec` (30-7200s) at start (tempdoc 735 G6) so the shared lease holds through minutes of
busy-but-session-silent work instead of lapsing on the default 30s passive-expiry window. Convention:
one Gradle build runs at a time across agents — concurrent Gradle invocations can corrupt shared
caches (observed 2026-07-14).

The full dev-stack contention model moved to `/dev-stack`; load it before live
backend work.

## Merge Workflow

**Never merge or publish a PR without an explicit, per-action go-ahead.** <!-- rule:no-merge-without-authorization -->
Authorization to *do the work* (implement a design, open a PR) is not authorization
to merge it — merging is a separate, consequential action. Take the PR to
green-and-ready, then stop and wait for an explicit "merge it"; the one exception is
when the user names the merge in the same instruction. Predictable evasion: treating
an upstream "do X" as covering the whole downstream merge/publish chain.

1. **Branch verification (required):** In your worktree, run <!-- rule:pre-merge-gradle-build -->
   `./gradlew.bat build -x test` before marking a PR ready.
2. Open/update a PR; title/body, review, CI are the durable record.
3. Squash after required checks pass. Use the PR title/body; keep checkpoint,
   investigation, and retry commits off `main`.
   If `origin/main` moves after push, catch up with `git merge`, not
   `git rebase` (agent-lessons.md; tempdoc 695).
4. After merge, update local `main` and run `./gradlew.bat build -x test`.
   Also fold any pending observation shards: `node scripts/agent-analytics/fold-observations.mjs --apply`
   (tempdoc 618 §P1.2's proposed boundary, tempdoc 665 wires it — the natural point since the agent is
   already back on `main` doing post-merge maintenance).
5. Remove the worktree. GitHub deletes merged remote branches; delete local
   branches after verifying the merge. On Windows, prefer
   `node scripts/dev/remove-worktree.cjs <path> [--delete-branch]` over
   `git worktree remove` — it survives long `node_modules` paths and unlinks
   `node_modules` junctions link-only, so a junction is removed without
   deleting through into main's real `node_modules` (tempdoc 618 §2).
   This teardown also records the `session_id → merge_commit` link; backfill
   with `node scripts/agent-analytics/record-merge.mjs` if needed.

### Publishing docs-only changes (history granularity) <!-- rule:docs-ride-along -->

Public `main` is a curated narrative, not a working log. ADR-0045 already makes
the merge *squash* a branch into one commit; this rule governs the complementary
question of whether a change should be its **own** public PR at all (tempdoc 653
"axis 2").

- A **tempdoc / observations** edit (`docs/tempdocs/**`, `docs/observations*`) is
  dated working history. Do not open a standalone PR for a tempdoc-only change.
  Ride it along in the same PR as the code it documents, or batch several tempdoc
  edits into one periodic `docs(tempdocs): …` PR.
- A **canonical-doc** update (`docs/{explanation,reference,how-to,decisions}`) is
  durable current truth and may stand alone as its own PR/commit.
- A branch mixing docs with code is already a ride-along — publish it normally.
- The **step-4 post-merge fold** *is* that periodic batch (many sessions' shards at
  once) and publishes as its own `chore(observations): fold …` PR. One shard alone
  is not — let it wait for the next fold.
- **A prior standalone tempdoc PR is not a precedent.** Predictable evasion (653
  follow-up): citing an earlier single-file `docs(NNN)` PR as licence chains one
  non-ideal PR into a series. Re-qualify each push on this rule's own terms.

The `docs-granularity-hint` hook surfaces this at `git push` when a branch
changes only working history; it never blocks. Rationale and the worked example
live in `docs/reference/contributing/agent-guide.md` (History publication).

### Verifying whether squash-merged work already landed <!-- rule:squash-merge-verify-content-not-ancestry -->

Since ADR-0045 squash-merges every PR into one commit, a squashed branch's original commits
never appear in `main`'s history. **Do not conclude a branch's work is "unmerged" from
`git log` / branch-ancestry alone** — that is exactly the signal squash-merging invalidates.
The reliable check is content, not ancestry: `git diff <branch> main -- <paths>` (empty output
= that content already landed under some other, differently-titled squashed commit). Verify
this way before a conclusion like "X isn't on main yet" feeds a user-facing decision.

### Working on shared `main` safely (multi-agent)

The main checkout routinely holds other agents' uncommitted WIP. Keep PR
publication and cleanup scoped to your branch:

- Do not use local merge/fast-forward as the normal public path; publish by PR
  squash, then update `main`.
- Stage your own files explicitly (`git add <paths>`), not `git add -A`.
- The four orchestration skills tracked on public `main`
  (`.claude/skills/{design,plan,takeover,theorize}`) were leaked once by a
  `git add -A` and the owner has since **accepted them as tracked** — never open a
  PR to remove them (repeated removal PRs, e.g. #151, are unwanted). <!-- rule:accepted-tracked-skills-no-removal -->
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
