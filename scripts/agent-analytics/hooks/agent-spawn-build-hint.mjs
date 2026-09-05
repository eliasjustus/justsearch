#!/usr/bin/env node

/**
 * PreToolUse hook on Bash (tempdoc 861 §6.4 / §7.1 Phase 5) — the `before-a-build` occasion.
 *
 * "Before a build — advisory only; it never kills [A4], with per-session marker de-dup.
 * A registered spawn holding paths under the tree a
 * `gradlew`/`npm` invocation is about to write turns a mystifying `EPERM`/`-4048` into a named
 * cause and a one-line remedy. This is the trigger the 2026-07-15 observation itself asked for,
 * and the only occasion covering the owner-alive case — at advisory tier by deliberate choice,
 * per §6.3." (861 §6.4)
 *
 * <!-- rule:agent-spawn-build-hint -->
 *
 * [A4] structural, not trusted to this hook: `occasion: 'before-a-build'` binds to
 * `capability: 'advisory'` in the reaper's frozen `OCCASIONS` map, so `reapEligible` mints NO
 * `reap` entry for this occasion no matter what evidence this hook hands it — every would-be
 * reap downgrades to a `report` carrying `ceiling: 'reap'`. This hook never calls `executeReap`
 * and holds no kill list to spend even if it wanted to.
 *
 * Per-session marker de-dup: once a given holder
 * (recordId) has been named for a session, it is not re-named on every subsequent
 * gradlew/npm invocation that session — the holder's identity doesn't change between build
 * attempts, so re-printing the same line would be the exact "residence, not delivery" waste
 * that idiom exists to avoid. A DIFFERENT holder (a new recordId) still gets its own hint.
 *
 * Advisory: never blocks, fail-open on any error, honors `JUSTSEARCH_DISABLE_HOOKS=1`.
 */

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { repoRoot, mainRepoRoot, hooksDisabled, readJsonStdin, isDirectRun } from '../lib/hook-base.mjs';

const require = createRequire(import.meta.url);
const { findBuildHolders, describeEntry } = require('../../dev/lib/agent-spawn-sweep.cjs');

/** Any `gradlew`/`npm` invocation shaped to WRITE under the tree (build/install/run), not a
 *  read-only query. Deliberately broad on gradlew (nearly every task writes under `build/`);
 *  narrower on npm so `npm ls`/`npm view`/`npm outdated` stay silent. */
const GRADLEW = /(^|[\s;&|])\.?\/?gradlew(\.bat)?\b/i;
const NPM_WRITE = /\bnpm\s+(run\s+\S+|install|ci|update|link|rebuild)\b/i;

export function classifyBuildCommand(cmd) {
  const c = String(cmd || '');
  if (!c.trim()) return false;
  return GRADLEW.test(c) || NPM_WRITE.test(c);
}

function markerPath(sessionId) {
  return path.join(repoRoot, 'tmp', 'agent-telemetry', `agent-spawn-build-nudged-${sessionId || 'unknown'}.json`);
}

function alreadyNudged(sessionId, recordId) {
  try {
    const seen = JSON.parse(fs.readFileSync(markerPath(sessionId), 'utf8'));
    return Array.isArray(seen) && seen.includes(recordId);
  } catch {
    return false;
  }
}

function recordNudged(sessionId, recordIds) {
  try {
    const file = markerPath(sessionId);
    let seen = [];
    try {
      seen = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch { /* none recorded yet */ }
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify([...new Set([...(Array.isArray(seen) ? seen : []), ...recordIds])]));
  } catch { /* best-effort — a missed write just means this holder re-hints next time */ }
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  if (!classifyBuildCommand(input.tool_input?.command)) return;

  const sessionId = input.session_id;
  let result;
  try {
    result = await findBuildHolders({
      mainRepoRoot,
      targetPath: repoRoot,
      callerSessionId: sessionId,
      // [861 W5 review F-5] Applied BEFORE the process-table read (inside `findBuildHolders`),
      // not after: an already-nudged holder is excluded from the candidate set before it can
      // trigger a PowerShell spawn — check before work, not after.
      // When every path-matched record is already-nudged, `findBuildHolders` never reads
      // the process table at all.
      recordFilter: (e) => !e.recordId || !alreadyNudged(sessionId, e.recordId),
    });
  } catch {
    return; // advisory — a lookup failure is silence, never a block
  }

  const holders = result.holders || [];
  if (holders.length === 0) return;

  recordNudged(sessionId, holders.map((e) => e.recordId));

  const lines = [
    'A registered agent-spawned process holds a path under this tree — if `gradlew`/`npm` hits an',
    'unexplained EPERM/-4048, this is the likely cause (tempdoc 861):',
    '',
    ...holders.map((e) => describeEntry(e)),
  ];

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        additionalContext: lines.join('\n'),
      },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
