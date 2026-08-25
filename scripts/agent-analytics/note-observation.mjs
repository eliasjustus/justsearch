#!/usr/bin/env node

/**
 * Append an out-of-scope finding to THIS session's observations shard for THIS
 * tree (tempdoc 618 Seam C — shared-agent-state isolation; tempdoc 862 — the
 * shard key).
 *
 * The `## Inbox` of docs/observations.md is a single shared file that every
 * parallel agent used to `echo >>`. On a contended multi-agent `main` a
 * neighbour's commit/reset silently wiped an un-committed append (618 §4/§9/§12,
 * reproduced as data loss 3×). This helper conforms the inbox to the repo's
 * existing per-shard pattern (governance `.changesets/`, agent-telemetry
 * session files): no two writers ever touch the same bytes — clobber is
 * impossible by construction. fold-observations.mjs reconciles shards into the
 * curated store at a boundary; correctness does not depend on the fold firing,
 * because the shard is committed in the agent's own worktree.
 *
 * The shard is keyed by session AND writing tree (tempdoc 862). 618 keyed it by
 * session alone, on the invariant "one session = one writer". The delegate model
 * broke that: a subagent inherits its parent's CLAUDE_CODE_SESSION_ID (see
 * resolveSessionId below), so an orchestrator and every worker it spawns resolved
 * the SAME shard path in DIFFERENT worktrees — nine writers on one file in the 859
 * wave, and a hand-resolved conflict on every catch-up pull. Contention is not a
 * property of the actor, it is a property of the tree that merges: session is the
 * right ATTRIBUTION key (kept, inside the file and in the name) and the wrong
 * ISOLATION key. See resolveWriterSuffix.
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

/**
 * Make a writer discriminator safe as a filename component. Deliberately
 * STRICTER than sanitizeId: dots are stripped too, because the shard name is
 * `<sessionId>.<writer>` and recover-merge-links.mjs recovers the session id by
 * removing the LAST dot-segment (tempdoc 862 §D.4). A dot inside the writer half
 * would make that strip ambiguous and feed a session id that never existed into
 * the measurement ledger. Exactly one dot in the name is the invariant.
 */
function sanitizeWriter(name) {
  return String(name).trim().replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 40);
}

/**
 * Resolve the WRITER discriminator for the shard name — the tree these bytes are
 * written in (tempdoc 862 §D.1).
 *
 *   - home checkout (`--git-dir` === `--git-common-dir`) → `''`, i.e. the bare
 *     `<sessionId>.md` this has always written. No shard in flight is renamed.
 *   - linked worktree (`--git-dir` !== `--git-common-dir`) → the sanitized
 *     basename of the worktree toplevel (`agent-af06f4a…`), the name git already
 *     gives the tree, so a worker's finding is self-labelling.
 *   - indeterminate (no git, tmp roots, the unit tests) → `''`. Fail open to
 *     today's behaviour; a note is never dropped for lack of an identity, matching
 *     resolveSessionId's fallback discipline above.
 *
 * `--git-common-dir` is not a new probe: resolveDefaultMergesPath
 * (baseline-economics.mjs:80-91) already distinguishes main checkout from worktree
 * exactly this way. This conforms to that seam rather than inventing a second test.
 */
export function resolveWriterSuffix({ root = repoRoot } = {}) {
  try {
    // stderr ignored: "not a git repository" is an EXPECTED outcome here (tmp
    // roots, the unit tests), not an error to report — it is the indeterminate
    // branch. execFileSync would otherwise echo it to the caller's stderr.
    const quiet = { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] };
    const [gitDir, commonDir] = execFileSync(
      'git', ['rev-parse', '--path-format=absolute', '--git-dir', '--git-common-dir'], quiet,
    ).trim().split(/\r?\n/).map((s) => s.trim());
    if (!gitDir || !commonDir) return '';
    if (path.resolve(gitDir) === path.resolve(commonDir)) return '';
    const top = execFileSync('git', ['rev-parse', '--show-toplevel'], quiet).trim();
    return top ? sanitizeWriter(path.basename(top)) : '';
  } catch {
    return '';
  }
}

/**
 * The ONE place a shard path is composed (tempdoc 862 §D.3). Every consumer —
 * appendObservation, hooks/observation-shard-hint.mjs — imports this rather than
 * building the name itself, so the writer discriminator lands in all of them by
 * construction. `writer` is injectable only so the tests can drive both branches
 * without a git fixture, mirroring resolveSessionId's `{ root, env }`.
 */
export function shardPathFor(sessionId, root = repoRoot, writer = resolveWriterSuffix({ root })) {
  const sid = sanitizeId(sessionId);
  const w = writer ? sanitizeWriter(writer) : '';
  return path.join(root, SHARD_DIR, `${w ? `${sid}.${w}` : sid}.md`);
}

/**
 * Append one observation entry to the session's shard, creating the shard with
 * a header if absent. Append-only; returns the shard path.
 */
export function appendObservation({ description, root = repoRoot, sessionId, date, writer } = {}) {
  const sid = sessionId ?? resolveSessionId({ root });
  const w = writer ?? resolveWriterSuffix({ root });
  const shard = shardPathFor(sid, root, w);
  fs.mkdirSync(path.dirname(shard), { recursive: true });
  if (!fs.existsSync(shard)) {
    fs.writeFileSync(
      shard,
      `# Observations shard — session ${sid}${w ? ` (tree ${w})` : ''}\n\n` +
        `> Per-session-per-tree inbox shard (tempdoc 618 Seam C, keyed by the writing\n` +
        `> tree per tempdoc 862). Append-only; one writer, one file — do not append to\n` +
        `> another tree's or session's shard. Folded into docs/observations.md by\n` +
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
  // The session id comes from the resolver, never from the shard basename: that
  // basename now carries a writer suffix, and parsing it back is the exact
  // mis-attribution tempdoc 862 §D.4 removes from recover-merge-links.mjs.
  const sid = resolveSessionId();
  const shard = appendObservation({ description, sessionId: sid });
  console.log(`note-observation: logged to ${path.relative(repoRoot, shard)} (session ${sid.slice(0, 12)})`);
}

// CLI entry only when run directly (not when imported by the test).
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  main();
}
