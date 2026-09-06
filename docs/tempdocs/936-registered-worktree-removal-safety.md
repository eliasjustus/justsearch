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

- [x] A. Sol worker implements admission/preview/runtime checks, exact branch handling,
  and scoped registration cleanup in the existing tool (small helper only if justified).
- [x] B. Same worker adds real-Git regression fixtures, updates existing holder fixtures,
  and retains junction/long-path/self-holder regressions. No weakening test intent.
- [x] C. Same worker updates canonical worktree mechanics and directly stale CLI guidance;
  remove superseded substring/naming assumptions in code, tests, and current docs.
- [x] D. Parent integrates explicit paths and performs a critical pass; independent Sol
  reviewer attempts to refute path/branch/runtime safety and the tests' causal validity.
- [x] E. Fix findings through Sol worker, run appropriate checks, record evidence, and
  commit the completed result locally. No PR/push/actual cleanup without further request.

## Acceptance and required verification

- [x] Exact registered external path with spaces and codex branch is removable in a
  scratch repository; guessed similarly named branch survives; detached HEAD works.
- [x] Main, arbitrary/misleading .claude/worktrees substring paths, wrong repository,
  target aliases, nested registered trees, locked and dirty trees are refused intact.
- [x] Ignored evidence appears in preview; preview changes nothing; removal needs
  --allow-ignored when applicable; ignored junction targets remain intact.
- [x] A live/unknown target-referencing shared/foreign backend blocks removal even when
  ownership lease is stale; unrelated live state does not block a proven unrelated tree.
- [x] Existing registered-other-session holder refusal and authorized same-session
  helper teardown still work through real Git fixtures with isolated state roots.
- [x] Git/runtime probes failing cannot silently admit removal; selected registration
  cleanup leaves unrelated stale registrations/branches intact.
- [x] Run targeted new CLI integration tests, scripts/dev/remove-worktree.test.mjs,
  scripts/dev/test-remove-worktree.cjs, and
  scripts/agent-analytics/861-w5-remove-worktree-teardown.test.mjs; nearby tests for any
  modified shared helpers. Falsify representative membership/runtime/branch guards
  against isolated fixtures to prove tests fail for the intended reason.
- [x] Run lint for changed JS; relevant docs regeneration/checks, git diff --check,
  and agent-analytics suite where this integration is discovered. Known wallclock
  failures require the documented isolated rerun before attribution.

No JVM/frontend/model behavior changes are planned, so Gradle, UI browser verification,
and model evaluations are not appropriate verification for this CLI-only change.
All destructive verification occurs only in newly created disposable fixture roots.

## Execution evidence and remaining work

Planning completed after the initial world-state refresh (45 registered checkouts; #936
next free). The Sol implementation ran in the assigned isolated branch from
9d5fe89ba61a726948bda414a99823c3d9f75fcf; no existing worktree, shared runtime, or
main-checkout state was removed or modified.

The removal CLI now derives admission from a NUL-delimited exact Git worktree listing and
revalidates target Git root/common directory/HEAD/branch, main identity, aliases, locks,
nested registrations, changes, and ignored inventory. It reads shared and foreign runtime
state and helper registrations fail closed, including visible pending atomic writes,
runtime-owned path overlap in either containment direction, complete process identity for
stale proof, and typed listener outcomes. Preview uses query-only Git and pure helper
classification. Actual removal retains link-only junction handling, removes only the selected
registration, and deletes only a captured unchanged local branch that no worktree still uses.

Verification evidence:

- `node scripts/agent-analytics/936-remove-worktree-cli.test.mjs` — **20 passed**. Real scratch
  Git fixtures cover arbitrary external paths/spaces/codex branches, detached HEAD, main and
  separate-Git-dir layouts, arbitrary/wrong-repository/alias/nested/locked/dirty refusal,
  ignored junction survival, exact stale-registration cleanup, shared/foreign runtime
  provenance and owned roots, pending records, mixed invalid PIDs, typed timeout, and dry-run
  zero-write fingerprints. The zero-write snapshot hashes content and records mtimes for the
  target, isolated state/telemetry, both Git indexes, refs, worktree list, and worktree admin.
- The same test contains bounded copied-CLI mutants under disposable roots. Removing the two
  main-identity guards erased only the fixture main tree before Git refused registration
  cleanup; suppressing the live shared-runtime blocker erased only its held fixture; restoring
  basename branch guessing deleted the wrong fixture branch and left the actual branch. These
  outcomes falsify the representative membership, runtime, and branch safeguards causally.
- `node scripts/agent-analytics/861-w5-remove-worktree-teardown.test.mjs` — **5 passed** with
  real Git worktrees; `861-w5-agent-spawn-sweep.test.mjs` — **19 passed / 0 skipped**;
  `861-w1-process-record.test.mjs` — **22 passed**. These include execution-time reap refusal,
  pending helper writes, strict relation errors, and symlink/nonregular records.
- `node --test scripts/dev/remove-worktree.test.mjs` — **10 passed**; and
  `node scripts/dev/test-remove-worktree.cjs` — **5 passed** with the expected held-handle
  diagnostic from the long-path fallback fixture.
- `node scripts/agent-analytics/run-all-tests.mjs` — **52/53 files passed** under concurrent
  load. The sole `861-w5-remove-worktree-teardown.test.mjs` failure was the documented
  wall-clock/process-table timestamp budget (`readAt` observed about 2.4 seconds in the future);
  its immediate isolated rerun passed **5/5**. No assertion or threshold was weakened.
- Focused ESLint over all seven changed JS files passed with zero warnings. Documentation
  regeneration/checks passed: llmstxt generation/check, skills sync/check, canonical link check,
  canonical module-dependency check, runtime-config-matrix check, targeted docs validation, and
  prompt-surface inventory (130 surfaces, zero suspicious tokens). `git diff --check` passed.
- Parent read-only real-repository smoke ran
  `node scripts/dev/remove-worktree.cjs F:/justsearch-public-worktrees/899-project-operations-publish --dry-run --allow-ignored`.
  It identified `codex/899-publication-closeout` at
  `049637b9cd09f2c2cf1d29bb68160d0c5792716e`, inventoried ignored paths, classified the live
  shared run `b72df35c-00e9-4609-a8df-c5455d8f7c97` as proven unrelated, exited zero, and left
  the checkout intact.

One bounded discovery limit remains: a process can start before even its temporary registration
entry becomes visible. Closing that interval requires a broader lifecycle lock/database and is
outside this change. Visible pending writes, malformed records, permission/I/O uncertainty, and
unreachable relevant processes all block removal. Editor and task discovery likewise cannot prove
the absence of unregistered holders, so canonical guidance tells operators to close target-scoped
tools and run from outside the target. No Gradle, frontend, live-model, publication, push, or real
worktree-removal action was appropriate or performed for this CLI-only change.

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
