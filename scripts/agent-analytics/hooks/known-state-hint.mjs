#!/usr/bin/env node

/**
 * PreToolUse hook on Bash — known-state-hint (tempdoc 680, expected-state lane).
 *
 * The single largest observation class was "pre-existing red, not mine, verified
 * via stash": every session paid a stash-and-verify ritual to answer "is this
 * failure mine?", then wrote the answer somewhere the next session never read.
 * This hook delivers that answer at the moment of relevance instead: when a
 * verification command matches an entry in
 * `scripts/agent-analytics/expected-state.v1.json`, the entry's claim is
 * surfaced as advisory context BEFORE the command runs — so a red that matches
 * the pinned state is recognized instead of re-derived, and a red that does NOT
 * match is correctly treated as new.
 *
 * Same shape as pipe-mask-hint: advisory, never blocks, fail-open on any error,
 * honors JUSTSEARCH_DISABLE_HOOKS=1. Pin freshness is not this hook's job —
 * `observations-triage --probe` checks each entry's exitProbe and reports pins
 * whose exit condition fired (report-only; a human edits the baseline).
 */

import fs from 'node:fs';
import path from 'node:path';
import { readJsonStdin, hooksDisabled, isDirectRun, repoRoot } from '../lib/hook-base.mjs';

export const EXPECTED_STATE_FILE = 'scripts/agent-analytics/expected-state.v1.json';

/** Commands that are verification-shaped at all — cheap pre-filter before regex matching. */
const VERIFY_LEAD = /gradlew|gradle |npm |npx |vitest|pytest|jseval|scripts[\\/](ci|governance|docs)[\\/]|governance[\\/]run\.mjs|check-[a-z0-9-]+|tsc\b/i;

/**
 * Pure matcher: returns the entries whose `match` regexes hit the command.
 * Invalid regexes are skipped (fail-open per entry, not per hook).
 */
export function matchExpectedState(cmd, entries) {
  const c = String(cmd || '');
  if (!c || !VERIFY_LEAD.test(c)) return [];
  const out = [];
  for (const e of entries ?? []) {
    for (const pattern of e.match ?? []) {
      let re;
      try { re = new RegExp(pattern, 'i'); } catch { continue; }
      if (re.test(c)) { out.push(e); break; }
    }
  }
  return out;
}

/** Render the advisory text for matched entries (bounded). */
export function renderHint(matched) {
  const lines = [
    'Known expected state for this command (expected-state.v1.json — tempdoc 680):',
    ...matched.slice(0, 4).map((e) => `  • [${e.id}] ${e.claim}`),
    'A failure matching the above is pre-existing — do NOT stash-and-verify or re-log it',
    '(re-observation is fine but costs nothing new). A failure NOT matching it is yours.',
  ];
  return lines.join('\n');
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  const cmd = input.tool_input?.command;
  let entries;
  try {
    entries = JSON.parse(fs.readFileSync(path.join(repoRoot, EXPECTED_STATE_FILE), 'utf8')).entries;
  } catch {
    return; // no baseline, no hint — fail-open
  }
  const matched = matchExpectedState(cmd, entries);
  if (matched.length === 0) return;
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: renderHint(matched) },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
