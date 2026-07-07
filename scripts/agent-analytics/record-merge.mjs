#!/usr/bin/env node

/**
 * Record a session -> merge-commit link (tempdoc 622 Layer B keying prerequisite,
 * §11 U2). Appends one line to tmp/agent-telemetry/session-merges.ndjson so the
 * outcome join can attribute a merge commit to the session that produced it —
 * the weak link that git history itself does not carry (merge messages cite
 * tempdoc numbers, not session ids).
 *
 * Run at merge time (documented in .claude/rules/branch-safety.md merge step):
 *   node scripts/agent-analytics/record-merge.mjs                       # links HEAD merge
 *   node scripts/agent-analytics/record-merge.mjs <commit>              # links a specific commit
 *   node scripts/agent-analytics/record-merge.mjs <commit> --session-id <id>  # escape hatch
 *
 * Session id resolution is shared with note-observation.mjs's resolveSessionId
 * (tempdoc 684): env-first (CLAUDE_CODE_SESSION_ID / JUSTSEARCH_AGENT_SESSION_ID),
 * falling back to the current-session-id pointer file, then a worktree hash.
 * `--session-id` is an escape hatch for headless/cron contexts where neither
 * env var is set.
 *
 * Read-only w.r.t. git (rev-parse + log); append-only telemetry. bash-guard safe.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { TELEMETRY_DIR, repoRoot } from './lib/telemetry-io.mjs';
import { resolveSessionId } from './note-observation.mjs';

const MERGES_FILE = 'session-merges.ndjson';

function git(args) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
}

function parseArgs(argv) {
  let commitArg = null;
  let sessionIdArg = null;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--session-id') {
      sessionIdArg = argv[i + 1];
      i += 1;
    } else if (arg.startsWith('--session-id=')) {
      sessionIdArg = arg.slice('--session-id='.length);
    } else if (commitArg === null) {
      commitArg = arg;
    }
  }
  return { commitArg: commitArg || 'HEAD', sessionIdArg };
}

function main() {
  const { commitArg, sessionIdArg } = parseArgs(process.argv.slice(2));
  let hash, subject;
  try {
    hash = git(['rev-parse', commitArg]);
    subject = git(['log', '-1', '--format=%s', hash]);
  } catch (e) {
    console.error(`record-merge: cannot resolve commit ${commitArg}: ${e.message}`);
    process.exit(1);
  }

  const sessionId = sessionIdArg ? sessionIdArg.trim() : resolveSessionId({ root: repoRoot });
  if (!sessionId || sessionId === 'unknown') {
    console.error('record-merge: no session id resolvable; link skipped (merge not attributed).');
    process.exit(0); // non-fatal: never block a merge over telemetry
  }

  const record = {
    session_id: sessionId,
    merge_commit: hash,
    subject,
    ts: new Date().toISOString(),
  };
  const file = path.join(repoRoot, TELEMETRY_DIR, MERGES_FILE);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.appendFileSync(file, JSON.stringify(record) + '\n', 'utf8');
  console.log(`record-merge: linked session ${sessionId.slice(0, 8)} -> ${hash.slice(0, 8)} (${subject.slice(0, 60)})`);
}

main();
