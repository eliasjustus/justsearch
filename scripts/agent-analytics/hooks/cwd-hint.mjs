#!/usr/bin/env node

/**
 * CwdChanged hook — surfaces the new working directory to the agent.
 *
 * Tempdoc 727 (finding F-6a, session-transcript friction mining): cwd drift is a
 * measured, dataset-wide source of agent friction — the agent silently `cd`s
 * somewhere, then a later command fails because it no longer remembers where it
 * is. Claude Code's `CwdChanged` hook event fires whenever the working directory
 * actually changes (confirmed at code.claude.com/docs/en/hooks), so this hook
 * only needs to restate the new cwd via `additionalContext` — no "previous cwd"
 * tracking is needed, since the harness itself gates when the event fires.
 *
 * Same shape as the other hint hooks: advisory only, never blocks, fail-open on
 * any parse error or missing field (silent no-op), honors JUSTSEARCH_DISABLE_HOOKS=1.
 */

import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';

/**
 * Pure core: given the CwdChanged hook payload, return the additionalContext
 * string to emit, or `null` when there's nothing worth saying (missing/falsy
 * `cwd`). No I/O — unit-tested directly.
 */
export function buildCwdContext(input) {
  const cwd = input?.cwd;
  if (!cwd) return null;
  return `Working directory changed to: ${cwd}`;
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input) return;
  const context = buildCwdContext(input);
  if (!context) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'CwdChanged', additionalContext: context },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
