#!/usr/bin/env node

/**
 * Append an out-of-scope finding to THIS session's observations shard
 * (tempdoc 618 Seam C — shared-agent-state isolation).
 *
 * The `## Inbox` of docs/observations.md is a single shared file that every
 * parallel agent used to `echo >>`. On a contended multi-agent `main` a
 * neighbour's commit/reset silently wiped an un-committed append (618 §4/§9/§12,
 * reproduced as data loss 3×). This helper conforms the inbox to the repo's
 * existing per-session-shard pattern (governance `.changesets/`, agent-telemetry
 * session files): each session writes ONLY its own file under docs/observations.d/,
 * so two writers never touch the same bytes — clobber is impossible by
 * construction. fold-observations.mjs reconciles shards into the curated
 * `## Inbox` at a boundary; correctness does not depend on the fold firing,
 * because the shard is committed in the agent's own worktree.
 *
 *   node scripts/agent-analytics/note-observation.mjs "<description>"
 *   node scripts/agent-analytics/note-observation.mjs "<description> — `file:line`"
 *
 * Session id is resolved the same way record-merge.mjs does (the
 * current-session-id pointer written by export-session-env.mjs), with env and
 * worktree-hash fallbacks so a note is never dropped for lack of a session id.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { TELEMETRY_DIR, repoRoot } from './lib/telemetry-io.mjs';

export const SHARD_DIR = 'docs/observations.d';

/** Make a session id safe as a filename component. */
function sanitizeId(id) {
  return String(id).trim().replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80) || 'unknown';
}

/**
 * Resolve the current session id for shard naming. ENV-FIRST (tempdoc 684):
 *   1. $CLAUDE_CODE_SESSION_ID                 (harness-native — safest primary)
 *   2. $JUSTSEARCH_AGENT_SESSION_ID            (repo export)
 *   3. tmp/agent-telemetry/current-session-id  (export-session-env.mjs, cross-platform)
 *   4. short hash of the worktree toplevel     (stable per checkout, never empty)
 *
 * The pointer file (#3) records whatever session last STARTED in that
 * checkout — in the shared main checkout that is routinely a FOREIGN
 * session's id, not the caller's, so it must not win over env. Env vars are
 * always the calling process's own identity, including in a subagent-spawned
 * shell: the child inherits the PARENT session's env, and attributing the
 * note/link to the parent is the desired behavior there too.
 */
export function resolveSessionId({ root = repoRoot, env = process.env } = {}) {
  if (env.CLAUDE_CODE_SESSION_ID) return sanitizeId(env.CLAUDE_CODE_SESSION_ID);
  if (env.JUSTSEARCH_AGENT_SESSION_ID) return sanitizeId(env.JUSTSEARCH_AGENT_SESSION_ID);
  try {
    const fromFile = fs.readFileSync(path.join(root, TELEMETRY_DIR, 'current-session-id'), 'utf8').trim();
    if (fromFile) return sanitizeId(fromFile);
  } catch { /* fall through */ }
  try {
    const top = execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd: root, encoding: 'utf8' }).trim();
    return 'wt-' + createHash('sha1').update(top).digest('hex').slice(0, 12);
  } catch {
    return 'unknown';
  }
}

/** Today's date as YYYY-MM-DD (local). */
export function today(d = new Date()) {
  return d.toISOString().slice(0, 10);
}

/**
 * Format an inbox entry matching docs/observations.md §"Entry format":
 *   - [ ] <description> (YYYY-MM-DD)
 * If the description already ends with a parenthesised date, it is left as-is.
 */
export function formatEntry(description, date = today()) {
  const text = String(description).trim();
  if (!text) throw new Error('note-observation: empty description');
  if (/\(\d{4}-\d{2}-\d{2}\)\s*$/.test(text)) return `- [ ] ${text}`;
  return `- [ ] ${text} (${date})`;
}

export function shardPathFor(sessionId, root = repoRoot) {
  return path.join(root, SHARD_DIR, `${sanitizeId(sessionId)}.md`);
}

/**
 * Append one observation entry to the session's shard, creating the shard with
 * a header if absent. Append-only; returns the shard path.
 */
export function appendObservation({ description, root = repoRoot, sessionId, date } = {}) {
  const sid = sessionId ?? resolveSessionId({ root });
  const shard = shardPathFor(sid, root);
  fs.mkdirSync(path.dirname(shard), { recursive: true });
  if (!fs.existsSync(shard)) {
    fs.writeFileSync(
      shard,
      `# Observations shard — session ${sid}\n\n` +
        `> Per-session inbox shard (tempdoc 618 Seam C). Append-only; do not share with\n` +
        `> other sessions. Folded into docs/observations.md \`## Inbox\` by\n` +
        `> \`node scripts/agent-analytics/fold-observations.mjs\`.\n\n`,
      'utf8',
    );
  }
  fs.appendFileSync(shard, formatEntry(description, date) + '\n', 'utf8');
  return shard;
}

function main() {
  const description = process.argv.slice(2).join(' ').trim();
  if (!description) {
    console.error('usage: node scripts/agent-analytics/note-observation.mjs "<description>"');
    process.exit(2);
  }
  const shard = appendObservation({ description });
  const sid = path.basename(shard, '.md');
  console.log(`note-observation: logged to ${path.relative(repoRoot, shard)} (session ${sid.slice(0, 12)})`);
}

// CLI entry only when run directly (not when imported by the test).
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  main();
}
