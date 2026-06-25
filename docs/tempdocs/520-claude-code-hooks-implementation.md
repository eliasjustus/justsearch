---
title: 520 — Claude Code Hooks Hardening
type: tempdoc
status: active
description: "Audit-driven hardening of the existing hook system at scripts/agent-analytics/hooks/. Originally drafted as 'implement missing hooks' on the false premise that hooks did not exist; corrected after verification that 16 hooks are wired via .claude/settings.local.json and firing as documented. Scope now: P0 fixes to defects surfaced in the critical-analysis pass."
---

# 520 — Claude Code Hooks Hardening

**Date**: 2026-05-18 (corrected 2026-05-18 after audit error); P0 + P1 implemented 2026-05-25
**Status**: active
**Related**: tempdoc 512 §F5 + Open Question 4 (which rules wouldn't be missed); `docs/explanation/21-agent-analytics-pipeline.md` (canonical hook-pipeline doc — accurate); `.claude/rules/hooks-reference.md` (agent-facing summary — accurate, restored after erroneous deletion in commit `263fc53f4`); `docs/observations.md` audit-lesson entry 2026-05-18.

---

## Original premise was wrong

The initial draft of this tempdoc proposed *implementing* `bash-guard.mjs`, `intervene.mjs`, `repeat-guard.mjs`, `build-counter.mjs`, `compact-save.mjs`, `compact-restore.mjs`, and the PostToolUse hint chain as new hooks. Verification (2026-05-18) showed all of these **already exist** at `scripts/agent-analytics/hooks/*.mjs` and are wired via `.claude/settings.local.json`. The audit subagent that informed the initial draft probed only `.claude/settings.json` and `~/.claude/`, missing the per-machine `settings.local.json` (which is checked into git for this repo).

This tempdoc is now reframed: **harden the existing hooks against the defects surfaced in the critical-analysis pass.** The pass found nine concrete issues across six hooks plus three cross-cutting code smells. P0 items are correctness or UX defects; P1 items are robustness investments.

## Inventory of the live hook system

Wired in `.claude/settings.local.json` → `hooks`:

| Event | Hooks wired |
|---|---|
| `SessionStart` | `export-session-env.mjs`, `dispatch.mjs` (async), `compact-restore.mjs` |
| `PreToolUse` (all) | `dispatch.mjs` (async), `repeat-guard.mjs` |
| `PreToolUse` (Read, Edit) | `intervene.mjs` |
| `PreToolUse` (Bash) | `bash-guard.mjs`, `build-counter.mjs` (if `./gradlew*`) |
| `PreToolUse` (mcp__justsearch-dev__*) | `mcp-session-inject.mjs` |
| `PostToolUse` (all) | `dispatch.mjs` (async) |
| `PostToolUse` (Edit, Write) | `docs-regen-hint.mjs`, `ssot-hint.mjs`, `ui-shot-hint.mjs`, `test-edit-hint.mjs`, `stress-test-hint.mjs` (if matching paths) |
| `PostToolUse` (Edit) | `lockfile-hint.mjs` (if matching `build.gradle.kts`) |
| `PostToolUseFailure` | `dispatch.mjs` (async) |
| `PreCompact` | `compact-save.mjs` |
| `SubagentStart` | `dispatch.mjs` (async), `subagent-guide.mjs` |
| `SubagentStop` | `dispatch.mjs` (async) |
| `UserPromptSubmit` | `dispatch.mjs` (async) |
| `InstructionsLoaded` | `dispatch.mjs` (async) |

`subagent-guide.mjs` is particularly load-bearing — it is the only project-aware context a subagent receives (CLAUDE.md and `.claude/rules/*.md` do not inherit).

## P0 — correctness / UX defects (highest leverage)

### 520-P0a. `bash-guard.mjs` cat/head/tail/grep regexes block flag-args

File: `scripts/agent-analytics/hooks/bash-guard.mjs:99-102`

The tool-hygiene patterns match commands ending in any `\S+` tokens, treating flag-shaped args like `-n`, `-A 3`, etc. as if they were file paths. Result: `cat -n file.txt` is blocked with a "use Read instead" message, even though `-n` is documented and the agent's intent was line-numbered output. Same for `head -n 50 file`, `grep -A 3 "pattern" file`.

**Fix**: skip the tool-hygiene block if any token starts with `-`. Cheaper alternative: allow if the leading `\S+` after the command is a flag.

### 520-P0b. `bash-guard.mjs` tool-hygiene loophole on chains

File: `scripts/agent-analytics/hooks/bash-guard.mjs:144`

`if (/[|><]|&&|\|\||;/.test(cmd)) return;` — any chained command bypasses Layer 3 entirely. So `cat file | head` slips through. If an agent learns this, every "use Read instead" rule becomes `cmd | cat`.

**Fix decision**: this may be intentional (chains are typically pipelines, not single-file reads). Document explicitly if so, or split the command on chain operators and apply the check per-segment.

### 520-P0c. `bash-guard.mjs` git-checkout regex blocks single-file restore

File: `scripts/agent-analytics/hooks/bash-guard.mjs:36-40`

`/\bgit\s+checkout\b/` matches `git checkout -- README.md` (single-file restore) and `git checkout main` (branch switch) identically. Only the latter conflicts with the main-stays-on-main invariant. The former is a legitimate need (restore one file to HEAD).

**Fix**: tighten the regex to match branch-switch shapes only (`git checkout <ref>` without `--`), OR drop this block since `git restore .` is already covered and `git checkout --` is rare and intentional.

### 520-P0d. `compact-restore.mjs` ghost-file lifecycle

File: `scripts/agent-analytics/hooks/compact-restore.mjs:42-44, 99-103`

The hook writes `.claude/rules/compaction-state.md` on `source === 'compact'` so the rules-file gets injected into the post-compaction system prompt. On the next non-compact SessionStart, `cleanupRulesFile()` deletes it. **But**: Claude Code's rules-file loading reads `.claude/rules/*.md` into context *before* SessionStart hooks fire (observed 2026-05-18 — a non-compact session start showed compaction-state.md content as a project rule despite the file being absent from disk by the time the session was probed).

**Effect**: one stale-orientation session every time a compact-completing session is followed by a non-compact restart.

**Fix candidates**: (a) write a sentinel that suppresses the file's content (e.g., write zero bytes), (b) gate the rules-file write on a different cleanup mechanism that runs pre-rules-load, (c) accept the one-session stale window and document.

### 520-P0e. `repeat-guard.mjs` parallel-call race

File: `scripts/agent-analytics/hooks/repeat-guard.mjs:104-108`

The buffer file isn't written atomically. Parallel hook invocations race. Acknowledged in code comments as "low practical impact" but it's a correctness defect under documented parallel-tool usage.

**Fix**: write-rename atomic pattern (write to `repeat-buffer-{sessionId}.tmp` then `rename` to final), or use an OS-level advisory lock.

### 520-P0f. `build-counter.mjs` race with `dispatch.mjs` async write

File: `scripts/agent-analytics/hooks/build-counter.mjs` + `dispatch.mjs:104-128`

`dispatch.mjs` is `async: true` on PostToolUse. The build's exit-code → `consecutiveFailures++` write may not complete before the next PreToolUse fires `build-counter.mjs`. Result: the *first* failure after the threshold can slip through.

**Fix candidates**: (a) make build-failure tracking synchronous inside `build-counter.mjs` itself, reading exit codes from a different source, (b) accept the one-call slip and document, (c) use a lockfile to coordinate.

## P1 — robustness investments

### 520-P1a. Add hook-base shared library

Every hook duplicates: stdin reading, JSON parsing, Windows path normalization (`SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')`), repoRoot resolution (`path.resolve(scriptDir, '..', '..', '..')`), telemetry-dir resolution. Extract to `scripts/agent-analytics/lib/hook-base.mjs`.

Affected files: all 16 hooks. The `event-writer.mjs` shared lib already exists; this would add to it.

### 520-P1b. Unit tests for critical-path hooks

`bash-guard`'s 8 regexes, `repeat-guard`'s 17-entry fingerprint switch, `build-counter`'s threshold logic, and `intervene`'s hot-file cap have no unit tests. A single typo in `repeat-guard.mjs:32-74` silently breaks consecutive-detection for the affected tool.

Minimum tests: positive + negative case per regex / per fingerprint case / per threshold transition.

### 520-P1c. Add `JUSTSEARCH_DISABLE_HOOKS=1` env-var bypass

If a hook misbehaves (e.g., parallel-write race wedges a buffer), recovery currently requires editing `settings.local.json` or deleting state files. An env-var bypass at the top of each hook (or in the shared `hook-base.mjs`) gives a fast kill switch for debugging.

### 520-P1d. Justify constants

`HOT_FILE_CAP = 10` (intervene), `CONSECUTIVE_THRESHOLD = 3` (repeat-guard), `CONSECUTIVE_FAIL_THRESHOLD = 3` (build-counter), `STALE_CACHE_MS = 24h`, `MEMORY_MAX_LINES = 100` (compact-save) — none have comments explaining the choice. Telemetry exists (dispatch.mjs writes NDJSON); could ground these constants in data.

## P2 — design questions (not defects)

- The auto-limit-Read injection in `intervene.mjs` is more permissive (200 lines, defaults to allow) than Claude Code's built-in Read truncation (varies by model). Verify they don't compose unexpectedly.
- `subagent-guide.mjs` (~85 lines compressing CLAUDE.md content) is the most context-effective document in the system. Worth asking: is this what CLAUDE.md should look like for the parent too? Currently the parent has 232 lines + Agent Discipline; the subagent gets the Reader's Digest version and still functions.
- The hint hooks (ssot/lockfile/ui-shot/docs-regen/test-edit/stress-test) all fire only on `Edit|Write`. Tool-call counting in `dispatch.mjs` could tell us how often each fires per month; if some fire 0 times, they're dead weight.

## Out of scope (deliberately)

- New blocking behaviors (e.g., gate `Edit` on `CLAUDE.md` itself with an accretion-check hook). Worth considering separately, but additive scope.
- Migrating any prose rule into a hook. The existing hook surface already mechanizes the highest-value rules (sleep block, destructive git, repeat detection, build thrashing). The remaining prose rules are design-time judgments (C-018, three-tier verification) that aren't hook-tractable.

## Acceptance gates

Per P0 item: a unit test demonstrating the defect (red), then the fix making it green. Per P1: green test suite + a sample run logged showing the new infrastructure firing.

## What this is *not*

A roadmap. A proposal. Needs P0/P1 prioritization sign-off before any implementation lands.

## Implementation log (P0 — 2026-05-25)

All six P0 items implemented on branch `worktree-520-hook-hardening`. Each
hook's decision logic was extracted into exported pure functions (thin
`main()` I/O wrappers retained behind an `import.meta`-direct-run guard so
the modules are importable by tests), then unit-tested per the acceptance
gate. **59 test checks pass** across four new `*.test.mjs` files; every fix
also smoke-tested as a live subprocess.

| Item | Fix | File(s) | Test |
|---|---|---|---|
| **P0a** flag-arg false positive | Layer 3 tool-hygiene now skips when any token starts with `-` (only *bare* reads redirect). `head`/`tail` regexes simplified to bare forms. | `bash-guard.mjs` (`evaluateBashCommand`) | `bash-guard.test.mjs` — `cat -n`/`head -n`/`grep -A` allowed; bare `cat`/`grep`/`head` still blocked |
| **P0b** chain bypass | Resolved as **intentional + documented** (chains are pipelines; Layer 3 is advisory; per-segment blocking would re-create P0a-class false positives). Git/sleep safety still scan the full command. | `bash-guard.mjs` | pinned: `cat f \| head` allowed; force-push/sleep in a chain still blocked |
| **P0c** checkout blocks single-file restore | Allow `git checkout [<ref>] -- <path>` (path ≠ `.`); still block branch switch + whole-tree restore (`git checkout .`, `git checkout -- .`). | `bash-guard.mjs` (`GIT_CHECKOUT_SINGLE_FILE_RESTORE` + `skipIf`) | `git checkout -- README.md` allowed; `git checkout main`/`-b`/`.`/`-- .` blocked |
| **P0e** repeat-guard race | Buffer write is now atomic (per-PID temp + `renameSync`) — no torn reads under parallel invocations. | `repeat-guard.mjs` (`atomicWriteFileSync`) | `repeat-guard.test.mjs` — content/no-residue/overwrite |
| **P0d** compact-restore ghost-file | Rules file is deleted at **SessionEnd** (wired in `settings.local.json`), so it never survives into the next session's pre-hook rules load. SessionStart cleanup retained as crash fallback; content is session-stamped + self-identifying. Residual: one stale session only if SessionEnd never fires (crash). | `compact-restore.mjs` (`decideAction`, session stamp), `settings.local.json` (SessionEnd wiring) | `compact-restore.test.mjs` + live SessionEnd-deletes-rules-file smoke |
| **P0f** build-counter race | Failure counting moved out of async `dispatch.mjs` into a **synchronous** PostToolUse path on `build-counter.mjs` (wired in `settings.local.json`). PostToolUse(count) + next PreToolUse(check) are both sync → the count is always fresh; advisory no longer fires one call late. | `build-counter.mjs` (`nextFailureState`/`shouldBlock`), `dispatch.mjs` (counting removed), `settings.local.json` (PostToolUse wiring) | `build-counter.test.mjs` + live 3-fail→block smoke (no off-by-one) |

**Settings wiring changes** (`.claude/settings.local.json`, JSON re-validated):
two additive entries — `compact-restore.mjs` on SessionEnd (sync); `build-counter.mjs`
on PostToolUse(Bash, `if: Bash(./gradlew*)`, sync). Both fail open on error.

**Still outstanding** (not in this session's scope — P0 only): P1a shared
`hook-base.mjs` lib, P1b broader unit coverage of the remaining hooks, P1c
`JUSTSEARCH_DISABLE_HOOKS` kill switch, P1d constant justification; and the
P2 design questions.

## Independent-review hardening (2026-05-25)

An independent reviewer (separate agent, per `independent-reviewer-required`)
found three real `bash-guard` bypasses the P0 work left open — including one
**introduced by P0c**. All fixed + covered by negative tests (bash-guard.test
33 → 45 checks):

- **C2 (regression from P0c):** the single-file-restore carve-out was a loose
  regex that also matched whole-tree restores — `git checkout -- ./`, `-- :/`,
  `-- *`, and multi-path `-- src .` slipped through as "allowed" in main.
  Replaced with a `isCheckoutPathRestore` predicate that rejects any whole-tree
  pathspec (`.`/`./`/`:/`/`*`/globs) after `--`.
- **C1:** `git restore .` block only matched a bare `.` immediately after
  `restore`; `git restore --worktree .` (and other flag arrangements, `./`,
  `:/`, `*`) bypassed it. Regex widened to catch whole-tree pathspecs through
  intervening flags.
- **H1:** the force-push block matched only `--force`/`-f`; `git push origin
  +HEAD:main` (the `+refspec` force form) bypassed the everywhere-rule. Added a
  `+refspec` pattern.

The reviewer also confirmed the build-counter race closure and the git/sleep
full-command scanning are correct, and flagged the pre-existing class-wide
limitation that subagents run with **no hooks at all** (force-push etc. are
only guarded in the parent session).

**Adjacent false-positive noticed, not fixed (out of P0 scope):** the
`DESTRUCTIVE_EVERYWHERE` force-push regex matches `git push --force` even
inside a quoted string (e.g. an `echo`/`printf` of that literal), because it
scans the full command without quote-awareness. Logged for a future pass.

## Implementation log (P1 — 2026-05-25)

Continued on the same branch after P0. **75 test checks pass** across 6
`*.test.mjs` files (incl. the new `lib/hook-base.test.mjs`); all migrated
hooks `node --check` clean and were live-smoke-tested as subprocesses.

- **P1a — shared `hook-base.mjs`.** New `scripts/agent-analytics/lib/hook-base.mjs`
  consolidating the boilerplate every hook duplicated: `readStdin` /
  `readJsonStdin`, `repoRoot` + `telemetryDir` (with Windows path
  normalization), `atomicWriteFileSync` (de-duping the two P0e/P0f copies),
  `isDirectRun`, and `runHook(importMetaUrl, main)` (direct-run guard +
  kill-switch + fail-open in one). **Seven hooks migrated** to it: bash-guard,
  repeat-guard, compact-restore, build-counter, intervene, compact-save (full
  migration), and dispatch (kill-switch only — it keeps its own telemetry
  I/O). Net effect: the per-hook `SCRIPT_DIR.replace(...)` / `path.resolve(..)`
  / inline `for await (chunk of stdin)` / inline atomic-write are gone from
  those hooks.
- **P1c — `JUSTSEARCH_DISABLE_HOOKS` kill switch.** Centralized in
  `hooksDisabled()` / `runHook`. Applied to **all session-affecting hooks**:
  the four blocking PreToolUse guards (bash-guard, repeat-guard, build-counter,
  intervene) + the stateful ones (compact-save, compact-restore, dispatch).
  Verified live: a normally-blocked payload is allowed with the env var set.
  **Scope decision (flagged for review):** the ~12 advisory-only hint hooks
  (`*-hint`, `mcp-session-inject`, `ui-shot-cleanup`, `subagent-guide`,
  `export-session-env`) are **deliberately out of kill-switch scope** — none
  can wedge a session or block a tool call (worst case is a spurious advisory
  line or a leftover dev server), so the fast-recovery rationale doesn't apply.
  Extending the switch to them is a trivial follow-up if desired.
- **P1d — justified constants.** Added grounding comments to
  `CONSECUTIVE_THRESHOLD` (repeat-guard), `CONSECUTIVE_FAIL_THRESHOLD`
  (build-counter), `HOT_FILE_CAP` + `DEFAULT_LIMIT` (intervene), and
  `MEMORY_MAX_LINES` (compact-save). `SIZE_THRESHOLD_BYTES` /
  `STALE_CACHE_MS` already carried explanatory comments.
- **P1b — extended coverage.** `lib/hook-base.test.mjs` (10 checks:
  atomic-write, kill-switch, direct-run, path resolution) and
  `intervene.test.mjs` (9 checks: the previously-untested **hot-file cap** +
  large-file limit injection). The atomic-write tests moved from
  repeat-guard.test.mjs to hook-base.test.mjs (single home).

**Still outstanding:** the P2 design questions; kill-switch on the advisory
hint hooks (scoped out above); migrating the remaining hint hooks' stdin/path
boilerplate onto hook-base (incremental, low-value).
