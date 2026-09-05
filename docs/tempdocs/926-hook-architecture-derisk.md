---
title: "Agent-hook architecture: compaction integrity and publication reconciliation"
type: tempdocs
status: implemented; publication candidate
created: 2026-09-04
updated: 2026-09-05
lane: agent tooling / hook semantics / context integrity
related:
  - 618-agent-developer-velocity-friction
  - 620-always-loaded-agent-doc-audit-and-prose-to-infrastructure
  - 861-agent-process-registry
  - 930-replace-bounded-areas-with-maintained-oss
---

# 926 — Agent-hook architecture: compaction integrity and publication reconciliation

## Goal

Investigate the hook that told agents they had edited governed code without
updating its documentation, determine what an ideal hook architecture should
look like, and implement the evidence-backed part without damaging summaries or
silently weakening safety.

The initiating failure was not only the documentation reminder. After a long
session compacted, recovery context could point at a different worktree, label
that worktree's dirty files as changes made by the current session, and persist
the result as an always-loaded rule. That is a context-integrity defect: an
advisory mechanism can become false system-level evidence.

## Investigation conclusion

The external research proposal was directionally useful but too broad. A
five-layer event platform, long-lived advisory broker, generic CloudEvents
envelope, fixed context quota, and process-reaper redesign were not justified by
the repository evidence. The existing manifest, generators, short-lived hook
processes, and ownership-aware reaper are the right substrate.

The durable design principles are:

1. Hook output must not claim causal attribution unless the event proves it.
2. Workspace observations must carry session, worktree, branch, and time
   provenance and must be omitted when that provenance cannot be verified.
3. Recovery context is one-shot continuation input, not a generated instruction
   file that survives beyond the event that justified it.
4. Deterministic invariants belong in native permissions, explicit checks, CI,
   or remote policy where possible. Hooks are appropriate only when the host
   event is the actual enforcement or context-delivery boundary.
5. A hook's declared effect should eventually bound what its result may do, but
   runtime effect typing must preserve every currently intentional control path.
6. Process cleanup must keep the existing spawn-time ownership, lease, PID
   creation-time, fingerprint, and fail-safe refusal checks.

## Main catch-up reconciliation

The original implementation branch was built before tempdoc 930 landed. Current
`main` deliberately retired the governed-code reminder, the Bash guard, the
expected-state hint/pins, and most advisory hints. It also replaced force-push
protection with native Claude permissions plus a stateless Codex refusal.
Those retirements are newer project truth and are not resurrected here.

Tempdoc 930 also explicitly retained `repeat-guard`, `build-counter`,
`intervene`, and `subagent-model-guard` based on measured use. The earlier 926
implementation removed or softened three of them. Publication does not reverse
that later decision because 926 has no newer measurement that falsifies it.

The original typed-effect/Codex-lane implementation is also deferred from this
publication. The current `intervene` PreToolUse path can both deny a hot read
and rewrite an oversized read. The earlier single-effect contract cannot
represent both without splitting the handler or changing behavior. Current
Codex force-push refusal also sits outside the manifest and must be preserved in
any future lane design. Re-authoring those decisions during publication would
be a new design, not conflict resolution.

## Published implementation scope

### Compaction state capture

- [x] Resolve Git state from the hook event `cwd`, never from the repository
      that happens to contain the hook script.
- [x] Record a timestamped `workspace_snapshot` with resolved worktree, branch,
      and staged, unstaged, and untracked paths.
- [x] Describe those paths as files observed in that workspace, never as files
      edited by the current session.
- [x] Preserve session read/edit caches as orientation data.
- [x] Preserve the current repeat-buffer reset because current `main` retains
      `repeat-guard`.
- [x] Remove stale consult/maintain marker cleanup for hooks already retired by
      tempdoc 930.

### One-shot restore

- [x] Atomically rename and consume the session state so concurrent or repeated
      restore attempts cannot replay it.
- [x] Verify saved session id, normalized worktree, and branch before displaying
      a Git snapshot.
- [x] Continue to deliver session-keyed read/edit orientation while omitting an
      unproven workspace snapshot.
- [x] Emit only `SessionStart` `additionalContext`.
- [x] Never create or read `.claude/rules/compaction-state.md`; retain only
      delete-only SessionStart cleanup for an ignored file left by older code.
- [x] Remove the obsolete SessionEnd binding, ignore rule, and always-loaded
      budget exemption.

### Projection ownership

- [x] Keep the focused Claude hook generator added by the latest `main` catch-up
      as the `regen-all` owner for tracked `.claude/settings.json` and
      `.claude/settings.local.json.example`.
- [x] Make its `--check` path validate both tracked projections without creating
      ignored local state.
- [x] Preserve the public native force-push deny rules and exclude founder-local
      analytics from the public projection.
- [x] Preserve the new focused `gen-agent-hooks.mjs` wrapper from `main`; do not
      replace it with the older cutover-package projection path.
- [x] Replace the nonexistent hook-generator command in canonical contributor
      guidance with the exact `regen-all` invocation.

## Explicit exclusions

- No governed-code reminder or other retired hint is restored.
- No expected-state pin/probe workflow is restored.
- No doc-impact, prose-tier, tier-register, or deleted analytics surface is
  restored.
- No current guard behavior changes.
- No typed-effect schema, Claude effect preload, or Codex lane split ships in
  this publication.
- No reaper implementation changes.
- No release, deployment, or destructive shared-worktree cleanup.

## Acceptance evidence

The implementation carries focused regression coverage for:

- two worktrees with distinct dirty paths and an untracked file;
- missing/non-Git event cwd;
- matching and mismatching session, worktree, and branch provenance;
- honest workspace-snapshot labels and omission of legacy unproven paths;
- atomic one-shot consumption;
- no generated rule file and delete-only migration cleanup; and
- public/local Claude projection separation with native deny preservation.

Publication verification must additionally include the current force-push
corpus, all four retained guard suites, exact projection drift checks,
hook-integrity, always-loaded budget, complete agent-analytics/governance tests,
the repository publish preflight, Gradle build/test, and frontend checks.

### Publication verification record

- Focused compaction suites pass: compact-save 24 checks and compact-restore
  17 checks, including production-path serialization, cross-worktree
  provenance, one-shot consumption, and legacy-rule cleanup.
- Agent analytics pass 50/50 test files; governance tests pass 30/30; the
  hook-integrity, projection drift, Codex parity, always-loaded budget, and
  canonical-document checks pass.
- The retained build-counter, intervene, repeat-guard, and subagent-model-guard
  suites pass. The Codex adapter passes its force-push corpus, and the generated
  public Claude projection preserves native force-push denies.
- Gradle compilation, the full test graph, the exact no-model assemble lane,
  and all three locally reproducible CI test partitions pass.
- Frontend typecheck and 6,269 unit tests pass.
- The locked jseval suite passes under an isolated supported Python 3.12
  environment: 2,808 passed and 95 skipped. The host's default Python 3.13
  install cannot build `pytrec-eval-terrier` without MSVC; this is an
  environment limitation, not a source failure.
- The consolidated publish-preflight runner completes all public-claim checks
  but its Windows shell cannot execute manifest entries spelled
  `./gradlew.bat`. Those license, assemble, unit-test, secret-scan, and jseval
  local subsets were therefore run directly. Hosted Windows-native, Rust, and
  CLA checks remain PR-CI responsibilities by manifest design.
- An independent refute-first review found and verified fixes for production
  compact-save integration coverage, cross-worktree legacy cleanup, and the
  canonical public/full-local Bash-chain description, then returned GO with no
  remaining actionable findings.

## Remaining work

The typed-effect idea remains a valid design direction, not a ready follow-up
task. Before implementation it needs a fresh current-main design that either
splits mixed-effect hooks into single-capability entry points or defines a
strict composite-control contract, includes the native Codex force-push refusal,
remeasures the smaller surviving hook set, and proves that concurrent stateful
handlers do not lose updates. No follow-up tempdoc is opened merely to preserve
the earlier checklist.
