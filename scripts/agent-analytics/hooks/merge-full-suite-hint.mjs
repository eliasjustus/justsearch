#!/usr/bin/env node

/**
 * PostToolUse hook on Bash `git merge` — tempdoc 734 (`subset-isnt-the-suite`
 * hook-hint promotion).
 *
 * The motivating incident (this branch's merge of `origin/main`): git
 * reported FOUR textual conflicts. There was a FIFTH it never marked. Our
 * side (tempdoc 726 F1) made `tryFinalizeRebuild()` require TWO consecutive
 * `pending==0` reads (a mid-flush-race guard); main's side (tempdoc 730) made
 * `finalizeShutdownCommit()` call `tryFinalizeRebuild()` EXACTLY ONCE, at
 * shutdown. Each side is correct alone. Merged, a worker stopping right after
 * a rebuild completes has a zero streak, the single call declines, the
 * fingerprint stamp never persists, and the next boot silently re-flags the
 * index as needing a rebuild — reopening the exact hole main's fix existed to
 * close. Git auto-merged this file clean: no conflict marker, no signal at
 * merge time. It was caught only because someone ran the FULL test suite
 * instead of the affected modules.
 *
 * That is the `subset-isnt-the-suite` principle (`.claude/rules/agent-lessons.md`
 * Named substrate-discipline principles; `docs/reference/contributing/agent-postmortems.md`
 * §13, tempdoc 618 §10c), promoted here from prose-only to hook-hint delivery
 * (tempdoc 734, tier-register row 42): a merge is mechanically detectable, so
 * the reminder is delivered at the moment of relevance instead of relying on
 * always-loaded prose recall alone.
 *
 * Fires on Bash `git merge` (any form except `--abort`, which undoes a merge
 * rather than completing one) — PostToolUse, so it fires once the merge
 * attempt has actually run (clean, conflicted, or `--continue`-finalized),
 * matching "an agent completes a git merge." It fires REGARDLESS of the
 * command's exit code: a merge with textual conflicts still needs the full
 * suite once conflicts are resolved (possibly several tool calls later), and
 * the fifth-conflict class this hook warns about can hide inside an
 * otherwise-conflicted invocation — four conflicts marked, a fifth
 * auto-merged clean, in the SAME `git merge` call.
 *
 * Deliberately non-blocking: a hook cannot know whether tests actually ran
 * afterward, and blocking a merge outright would be wrong (conflict
 * resolution is often iterative across multiple tool calls, and the
 * verification step this hook nudges toward happens in a later, separate
 * tool call this hook has no visibility into). This is an advisory push
 * toward the correct verification, not a gate — mirrors
 * `docs-granularity-hint.mjs` / `pipe-mask-hint.mjs`.
 *
 * Advisory: never blocks, fail-open on any error, honors
 * `JUSTSEARCH_DISABLE_HOOKS=1`. Delivers the rule `subset-isnt-the-suite`
 * (tier-register row 42) at its moment of relevance.
 */

import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';

/**
 * `git [ -C <path> ] merge [...]` — not `--abort`, which undoes a merge
 * rather than completing one (no full-suite verification is warranted for an
 * aborted merge). `--continue` (finalizing after manual conflict resolution)
 * and any other merge form DO match — those are exactly the "completes a
 * merge" moments this hook targets.
 */
export function isGitMerge(cmd) {
  if (!cmd) return false;
  if (!/\bgit\b(?:\s+-C\s+\S+)?\s+merge(?:\s|$)/i.test(cmd)) return false;
  if (/--abort\b/i.test(cmd)) return false;
  return true;
}

export const HINT = [
  'Post-merge verification (subset-isnt-the-suite, tempdoc 734): a merge can auto-resolve a',
  'SEMANTIC conflict with no marker even when git reports OTHER conflicts in the same merge.',
  'Real instance (this branch): our side made a rebuild-finalize guard require two consecutive',
  'zero-pending reads; main made the shutdown path call that guard exactly once. Each was',
  'correct alone. Merged, a worker stopping right after a rebuild has a zero streak, the single',
  "call declines, the fingerprint stamp never persists, and the next boot silently re-flags the",
  "index for a rebuild — reopening the exact hole the other side's fix existed to close. Git",
  'auto-merged that file clean; nothing marked it. Only the FULL test suite caught it — a',
  'hand-picked subset (affected modules only) would not have.',
  'Before declaring this merge done: run the FULL suite',
  '(`./gradlew.bat build -x test` then `./gradlew.bat test`; add',
  '`npm run typecheck && npm run test:unit:run` if `modules/ui-web` was touched by either side),',
  'not just the modules you touched — run it now, not deferred to a later review pass.',
].join('\n');

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  if (!isGitMerge(input.tool_input?.command)) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: HINT },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
