#!/usr/bin/env node

/**
 * Stop hint hook — reminds an agent to commit its own observation shard
 * (tempdoc 665, closing a durability gap left open by tempdoc 618 Seam C).
 *
 * `note-observation.mjs` writes a finding to a per-session shard file under
 * `docs/observations.d/<session>.md`. Tempdoc 618's durability argument was
 * that the shard "rides the agent's own commit" — but nothing enforces that,
 * and `branch-safety.md` separately tells agents to stage files explicitly
 * (not `git add -A`), so a shard is easy to leave out of a commit entirely. A
 * live instance of exactly this was found during tempdoc 665's implementation:
 * an untracked shard from a prior session had already been lost (deleted
 * without ever being folded) by the time this hook was written.
 *
 * Anchored at `Stop`, not `SessionEnd`: this repo's own existing `SessionEnd`
 * hook (`compact-restore.mjs`) only ever does silent file cleanup and never
 * emits `additionalContext`, because by the time `SessionEnd` fires the
 * session is already over and nothing reads hook output anymore. A hint the
 * agent needs to act on (commit a file) has to fire while the session is
 * still live — `maintain-doc-hint.mjs` already solves exactly this shape
 * (a once-per-session, non-blocking nudge via `Stop` + a per-session marker
 * file), and this hook follows the same pattern.
 *
 * Scope: only ever checks the CURRENT session's own shard (via the same
 * `resolveSessionId` `note-observation.mjs` uses), never another session's —
 * consistent with the rule against touching files another agent created.
 *
 * Advisory only: never blocks (no `decision: 'block'`) — an informal,
 * noncanonical inbox note is low-stakes compared to the governed-region case
 * `maintain-doc-hint` guards. Fail-open on any error; honors
 * `JUSTSEARCH_DISABLE_HOOKS=1` (via `runHook`).
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { runHook, repoRoot } from '../lib/hook-base.mjs';
import { resolveSessionId, shardPathFor } from '../note-observation.mjs';

/**
 * Pure decision core (unit-testable without git/fs).
 * @returns {{action: 'noop' | 'nudge'}}
 */
export function decideAction({ stopHookActive, shardExists, shardDirty, alreadyNudged }) {
  if (stopHookActive) return { action: 'noop' }; // avoid re-firing within one forced continuation
  if (!shardExists) return { action: 'noop' }; // nothing written this session, or already folded/committed
  if (!shardDirty) return { action: 'noop' }; // shard is already committed — durable
  if (alreadyNudged) return { action: 'noop' }; // don't nag every turn
  return { action: 'nudge' };
}

export const HINT = (shardRelPath) =>
  [
    `Your observation shard (\`${shardRelPath}\`) is uncommitted. Stage and commit it before this session`,
    'ends, or it can be silently lost (tempdoc 618 Seam C durability relies on the shard riding your own',
    'commit — nothing else guarantees it). `git add` the file explicitly (not `git add -A`), per',
    '`.claude/rules/branch-safety.md`. (observation-shard-hint — tempdoc 665; nudges once per session.)',
  ].join('\n');

function isShardDirty(shardPath, root) {
  try {
    const out = execFileSync('git', ['status', '--porcelain', '--', shardPath], {
      cwd: root,
      encoding: 'utf8',
    });
    return out.trim().length > 0;
  } catch {
    return false; // not a git repo / no git available — fail-open (no nudge)
  }
}

function nudgeMarkerPath(sessionId) {
  return path.join(repoRoot, 'tmp', 'agent-telemetry', `observation-shard-nudged-${sessionId || 'unknown'}.json`);
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  let input;
  try {
    input = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    return;
  }

  const sessionId = resolveSessionId({ root: repoRoot, env: process.env });
  const shardPath = shardPathFor(sessionId, repoRoot);
  const shardExists = fs.existsSync(shardPath);
  const shardDirty = shardExists && isShardDirty(shardPath, repoRoot);

  const markerFile = nudgeMarkerPath(input.session_id || sessionId);
  let alreadyNudged = false;
  try {
    alreadyNudged = fs.existsSync(markerFile);
  } catch {
    /* fail-open: treat as not-yet-nudged */
  }

  const decision = decideAction({
    stopHookActive: !!input.stop_hook_active,
    shardExists,
    shardDirty,
    alreadyNudged,
  });
  if (decision.action !== 'nudge') return;

  try {
    fs.mkdirSync(path.dirname(markerFile), { recursive: true });
    fs.writeFileSync(markerFile, JSON.stringify({ nudged: true }));
  } catch {
    return; // couldn't persist the dedupe marker — skip nudging rather than risk re-firing every turn
  }

  const relPath = path.relative(repoRoot, shardPath).replace(/\\/g, '/');
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'Stop', additionalContext: HINT(relPath) },
    }),
  );
}

runHook(import.meta.url, main);
