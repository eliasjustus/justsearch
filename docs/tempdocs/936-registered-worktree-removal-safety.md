---
title: Registered worktree removal safety
status: active
---

# 936: Registered worktree removal safety

## Objective and scope

Make the existing scripts/dev/remove-worktree.cjs usable for this repository's
registered linked worktrees regardless of their parent directory or branch naming.
The user requested the plan skill followed by implementation delegated to Sol or
below. Implement the tool, its regression tests, and the documentation it supersedes.
Do not clean up existing user worktrees, reconcile main, publish, or open a PR.

Base: public origin/main b96cd999875e6aabb5f0d8903c79f023c92c6738.
Parent worktree: F:/justsearch-public-worktrees/936-worktree-removal.
Local main is deliberately not the implementation base: the preceding audit found
297 local-only commits and uncommitted work. No changes to that checkout are needed.

## Evidence and existing authorities

- remove-worktree.cjs:367 admits a pathname substring rather than Git membership;
  :441 guesses worktree-<basename> for branch deletion. Current registrations include
  external sibling directories, codex/* branches, and detached HEADs.
- Existing removeJunctions/deleteTree preserve junction targets and handle long paths.
  Retain these tested mechanisms rather than replacing filesystem deletion blindly.
- scripts/dev/lib/agent-spawn-sweep.cjs owns registered helper teardown, main-root
  resolution, resource containment, and caller identity. Reuse its authority.
- scripts/dev/lib/process-record.cjs owns state-root resolution and foreign-run records;
  process-identity.cjs supplies tri-state process identity/liveness evidence.
- worktree-collision-preflight.cjs and world-state.mjs parse worktree listings but
  project incomplete fields. Inspect them; do not expand a lossy projection into a
  new global registry. A local NUL-delimited Git parser may be appropriate.
- 861-w5-remove-worktree-teardown.test.mjs currently substitutes plain directories
  for worktrees. Replace that obsolete fixture assumption with real isolated Git
  repositories while preserving its live-holder/refusal/same-session assertions.
- Canonical worktree mechanics and branch-safety guidance describe this CLI and must
  reflect the final behavior. Read docs-maintenance; inspect corresponding manual
  .agents and .claude skill references when shared delivery changes.

## Design decisions

Git's registered worktrees and existing runtime registers remain the authorities.
No daemon, lifecycle database, classifier overhaul, or automatically inferred merge
verdict is introduced. Content/squash verification remains an operator responsibility.

Resolve the candidate to an exact registered linked worktree of the tool's repository;
reject main, bare repos, arbitrary directories, descendants/ancestors, and aliases
that could redirect deletion outside the validated path. Protect registered nested
worktrees from recursive deletion. Parse names losslessly, including spaces, Unicode,
and detached/locked/prunable fields. Establish common-directory membership and the
absolute target before any filesystem mutation.

Admission refuses locks and tracked/staged/untracked changes. Include a read-only
--dry-run mode that reports target, branch or detached HEAD, blockers, and ignored
paths without Git ref changes, telemetry writes, process reaping, or filesystem edits.
Ignored files are not disposable by inference: print their inventory and require an
explicit --allow-ignored option to include them in a real removal. No generic --force
bypass for membership, lock, dirtiness, or runtime safety.

Inspect existing shared/backend and foreign-run provenance for references to the target
(or distributions sourced from it), using existing readers/path identity where possible.
Do not equate expired ownership with stopped processes. Relevant active/unknown runtime
state blocks removal; absent state permits proceeding. Retain the existing sanctioned
helper reaper in execution mode; preview must use advisory inspection only. Failure to
establish required safety state is an actionable error, not permission to proceed.
Document honest limits of task/editor discovery instead of inventing a task registry.

Capture the actual branch and HEAD before deletion; detached HEAD skips branch deletion.
With --delete-branch, act only on the captured branch after checking it has not moved or
become checked out elsewhere. Keep the explicit deletion semantics required for squash
history; do not guess merge state or delete a similarly named branch. Clean only the
selected worktree's Git registration; avoid sweeping unrelated stale registrations.
Revalidate admission immediately before destructive actions after asynchronous checks.
Preserve identity-attributed merge recording without writing it during a refused preview.

## Implementation and delegation plan

- [ ] A. Sol worker implements admission/preview/runtime checks, exact branch handling,
  and scoped registration cleanup in the existing tool (small helper only if justified).
- [ ] B. Same worker adds real-Git regression fixtures, updates existing holder fixtures,
  and retains junction/long-path/self-holder regressions. No weakening test intent.
- [ ] C. Same worker updates canonical worktree mechanics and directly stale CLI guidance;
  remove superseded substring/naming assumptions in code, tests, and current docs.
- [ ] D. Parent integrates explicit paths and performs a critical pass; independent Sol
  reviewer attempts to refute path/branch/runtime safety and the tests' causal validity.
- [ ] E. Fix findings through Sol worker, run appropriate checks, record evidence, and
  commit the completed result locally. No PR/push/actual cleanup without further request.

## Acceptance and required verification

- [ ] Exact registered external path with spaces and codex branch is removable in a
  scratch repository; guessed similarly named branch survives; detached HEAD works.
- [ ] Main, arbitrary/misleading .claude/worktrees substring paths, wrong repository,
  target aliases, nested registered trees, locked and dirty trees are refused intact.
- [ ] Ignored evidence appears in preview; preview changes nothing; removal needs
  --allow-ignored when applicable; ignored junction targets remain intact.
- [ ] A live/unknown target-referencing shared/foreign backend blocks removal even when
  ownership lease is stale; unrelated live state does not block a proven unrelated tree.
- [ ] Existing registered-other-session holder refusal and authorized same-session
  helper teardown still work through real Git fixtures with isolated state roots.
- [ ] Git/runtime probes failing cannot silently admit removal; selected registration
  cleanup leaves unrelated stale registrations/branches intact.
- [ ] Run targeted new CLI integration tests, scripts/dev/remove-worktree.test.mjs,
  scripts/dev/test-remove-worktree.cjs, and
  scripts/agent-analytics/861-w5-remove-worktree-teardown.test.mjs; nearby tests for any
  modified shared helpers. Falsify representative membership/runtime/branch guards
  against isolated fixtures to prove tests fail for the intended reason.
- [ ] Run lint for changed JS; relevant docs regeneration/checks, git diff --check,
  and agent-analytics suite where this integration is discovered. Known wallclock
  failures require the documented isolated rerun before attribution.

No JVM/frontend/model behavior changes are planned, so Gradle, UI browser verification,
and model evaluations are not appropriate verification for this CLI-only change.
All destructive verification occurs only in newly created disposable fixture roots.

## Execution evidence and remaining work

Planning completed after current world-state refresh (45 registered checkouts; #936
next free). Implementation and verification remain unchecked above.

### Parent design probes (2026-09-06)

- A disposable real Git repository with two external worktrees (paths containing
  spaces) verified that after controlled filesystem deletion,
  `git worktree remove --force -- <target>` removes only that registration and
  retains an unrelated missing/prunable registration. This can replace global prune.
- Read of agent-spawn-sweep.cjs found gatherAgentSpawnOrientation writes refusal
  markings despite being advisory. Dry-run must use the raw reader and pure evaluator
  or another verified zero-write path, not this assembly. Worker notified.
- Existing process-record.readRegister is bounded and surfaces most errors, but its
  dirent filtering deserves care for symlinks. Reusing a reader does not transfer a
  stronger safety claim than its actual behavior supports.
- The independent Sol exploration found a documented self-removal incident in
  .claude/rules/agent-lessons.md:26. Admission must also refuse when the target contains
  process.cwd() or the executing script's own checkout. The existing holder scanner
  deliberately excludes the invoking chain and cannot establish cwd-only ownership.
- Preview Git probes must disable optional index locks/refresh writes; filesystem
  snapshots in regression fixtures should cover index/refs/runtime metadata as well
  as target content. New tests must be discovered by an existing suite.
