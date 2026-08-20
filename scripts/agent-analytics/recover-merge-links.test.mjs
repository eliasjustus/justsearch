/**
 * Tempdoc 856 D — unit tests for recover-merge-links.mjs.
 *
 * The load-bearing properties here are RESTRICTIONS, not features: single-shard
 * only (856 §4 measured 8.9% vs 55.6% false positives), squash-PR subjects only,
 * inference tier only, dry-run by default, and skip-what-is-already-linked. Each
 * gets its own case, because loosening any of them silently is exactly how a
 * measured error rate turns back into an unmeasured one.
 *
 * Ledger writes go to temp dirs only — never the real tmp/agent-telemetry.
 *
 * Run with: `node scripts/agent-analytics/recover-merge-links.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  parseShardAddLog,
  classifyShardCommits,
  planRecovery,
  applyRecovery,
  loadLedgerPairs,
  parseArgs,
  SHARD_PREFIX,
  MEASURED,
} from './recover-merge-links.mjs';
import { TELEMETRY_DIR, SESSION_MERGES_FILE, buildMergeLinkRow, repoRoot } from './lib/telemetry-io.mjs';
import { resolveDefaultMergesPath } from './baseline-economics.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const REC = String.fromCharCode(0x00);
const FIELD = String.fromCharCode(0x1f);
const HERE = path.dirname(fileURLToPath(import.meta.url));

const SESS = {
  a: '0a20e5bf-572d-4b02-99f0-3e3696207fca',
  b: 'a1c1e6ee-ebd3-4ca0-9cfb-c12edd395142',
  c: 'bccfc163-7b8f-4b1a-b9e4-0c011632d8a1',
};

/** Compose one `git log --diff-filter=A --name-only --format=%x00%H%x1f%s%x1f%cI` record. */
function logRecord(commit, subject, committedAt, sessionIds, extraFiles = []) {
  const files = [...sessionIds.map((s) => `${SHARD_PREFIX}${s}.md`), ...extraFiles];
  return REC + commit + FIELD + subject + FIELD + committedAt + '\n\n' + files.join('\n') + '\n';
}

function ledgerIn(dir) {
  return path.join(dir, TELEMETRY_DIR, SESSION_MERGES_FILE);
}

function writeLedger(root, rows) {
  const file = path.join(root, TELEMETRY_DIR, SESSION_MERGES_FILE);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, rows.map((r) => JSON.stringify(r)).join('\n') + '\n', 'utf8');
  return file;
}

function readLedger(root) {
  const file = path.join(root, TELEMETRY_DIR, SESSION_MERGES_FILE);
  if (!fs.existsSync(file)) return null;
  return fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'recover-merge-links-test-'));

try {
  // --- parseShardAddLog ---------------------------------------------------

  run('parseShardAddLog extracts commit, subject, committer date and added shard ids', () => {
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T10:00:00+02:00', [SESS.a]);
    const [rec] = parseShardAddLog(raw);
    assert.equal(rec.commit, 'a'.repeat(40));
    assert.equal(rec.subject, 'feat: thing (#100)');
    assert.equal(rec.committedAt, '2026-08-01T10:00:00+02:00');
    assert.deepEqual(rec.shards, [SESS.a]);
  });

  run('parseShardAddLog ignores non-shard paths that ride in the same commit', () => {
    const raw = logRecord('b'.repeat(40), 'feat: thing (#101)', '2026-08-01T10:00:00Z', [SESS.a],
      ['docs/observations.md', 'scripts/agent-analytics/foo.mjs', 'docs/observations.d/README.txt']);
    const [rec] = parseShardAddLog(raw);
    assert.deepEqual(rec.shards, [SESS.a]);
  });

  // --- classifyShardCommits: the restrictions -----------------------------

  run('classifyShardCommits accepts a single-shard squash-PR commit', () => {
    const commits = parseShardAddLog(logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a]));
    const { candidates, rejected } = classifyShardCommits(commits);
    assert.equal(candidates.length, 1);
    assert.equal(candidates[0].sessionId, SESS.a);
    assert.equal(rejected.length, 0);
  });

  run('classifyShardCommits REJECTS a multi-shard commit (856 §4: 55.6% false positives)', () => {
    const commits = parseShardAddLog(logRecord('b'.repeat(40), 'feat: thing (#101)', '2026-08-01T00:00:00Z', [SESS.a, SESS.b]));
    const { candidates, rejected } = classifyShardCommits(commits);
    assert.equal(candidates.length, 0);
    assert.equal(rejected.length, 1);
    assert.match(rejected[0].reason, /^multi-shard \(2\)/);
    assert.match(rejected[0].reason, /55\.6% false-positive/); // the measurement travels with the rejection
  });

  run('classifyShardCommits REJECTS a commit whose subject is not a squash PR commit', () => {
    const commits = parseShardAddLog(logRecord('c'.repeat(40), 'chore: local commit, never a PR', '2026-08-01T00:00:00Z', [SESS.a]));
    const { candidates, rejected } = classifyShardCommits(commits);
    assert.equal(candidates.length, 0);
    assert.match(rejected[0].reason, /not a squash PR commit/);
  });

  run('classifyShardCommits REJECTS a shard filename that is not a plausible session id', () => {
    const commits = parseShardAddLog(logRecord('d'.repeat(40), 'feat: thing (#102)', '2026-08-01T00:00:00Z', ['x']));
    const { candidates, rejected } = classifyShardCommits(commits);
    assert.equal(candidates.length, 0);
    assert.match(rejected[0].reason, /not a plausible session id/);
  });

  run('classifyShardCommits reports rejects rather than dropping them (856 §7)', () => {
    const raw = logRecord('a'.repeat(40), 'feat: ok (#100)', '2026-08-01T00:00:00Z', [SESS.a])
      + logRecord('b'.repeat(40), 'feat: multi (#101)', '2026-08-01T00:00:00Z', [SESS.b, SESS.c])
      + logRecord('c'.repeat(40), 'chore: not a pr', '2026-08-01T00:00:00Z', [SESS.c]);
    const { candidates, rejected } = classifyShardCommits(parseShardAddLog(raw));
    assert.equal(candidates.length + rejected.length, 3); // nothing vanishes
    assert.equal(candidates.length, 1);
    assert.equal(rejected.length, 2);
  });

  // --- planRecovery: dry-run writes nothing -------------------------------

  run('planRecovery writes NOTHING — it only plans (dry-run is the default path)', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a]);
    const plan = planRecovery({ ledgerPath: ledgerIn(root), raw });
    assert.equal(plan.toWrite.length, 1);
    assert.equal(readLedger(root), null); // no file created at all
    assert.equal(fs.existsSync(path.join(root, TELEMETRY_DIR)), false);
  });

  run('planRecovery emits shard-inference/inference rows back-dated to the commit', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T10:00:00Z', [SESS.a]);
    const [row] = planRecovery({ ledgerPath: ledgerIn(root), raw }).toWrite;
    assert.equal(row.source, 'shard-inference');
    assert.equal(row.kind, 'inference'); // NEVER fact tier
    assert.equal(row.session_id, SESS.a);
    assert.equal(row.merge_commit, 'a'.repeat(40));
    assert.equal(row.ts, '2026-08-01T10:00:00Z');
  });

  run('planRecovery SKIPS a (session_id, merge_commit) pair already in the ledger', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    writeLedger(root, [buildMergeLinkRow({
      sessionId: SESS.a, mergeCommit: 'a'.repeat(40), subject: 'feat: thing (#100)', ts: '2026-08-01T00:00:00Z',
    })]);
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a])
      + logRecord('b'.repeat(40), 'feat: other (#101)', '2026-08-02T00:00:00Z', [SESS.b]);
    const plan = planRecovery({ ledgerPath: ledgerIn(root), raw });
    assert.equal(plan.skippedAlreadyLinked.length, 1);
    assert.equal(plan.skippedAlreadyLinked[0].sessionId, SESS.a);
    assert.equal(plan.toWrite.length, 1);
    assert.equal(plan.toWrite[0].session_id, SESS.b);
  });

  run('planRecovery skips a pair already present as a LEGACY row lacking source/kind', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    // Exactly the on-disk shape of every pre-856 row: no source, no kind.
    writeLedger(root, [{ session_id: SESS.a, merge_commit: 'a'.repeat(40), subject: 'feat: thing (#100)', ts: '2026-08-01T00:00:00Z' }]);
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a]);
    const plan = planRecovery({ ledgerPath: ledgerIn(root), raw });
    assert.equal(plan.toWrite.length, 0);
    assert.equal(plan.skippedAlreadyLinked.length, 1);
  });

  run('planRecovery does not emit the same pair twice within one batch', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a])
      + logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a]);
    const plan = planRecovery({ ledgerPath: ledgerIn(root), raw });
    assert.equal(plan.toWrite.length, 1);
  });

  run('loadLedgerPairs normalizes legacy rows so a missing source still counts as linked', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    writeLedger(root, [{ session_id: SESS.a, merge_commit: 'a'.repeat(40) }]);
    const { rows, pairs } = loadLedgerPairs({ ledgerPath: ledgerIn(root) });
    assert.equal(rows[0].source, 'teardown');
    assert.equal(rows[0].kind, 'fact');
    assert.equal(pairs.size, 1);
  });

  // --- applyRecovery ------------------------------------------------------

  run('applyRecovery appends the planned rows and is idempotent on a second plan', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a])
      + logRecord('b'.repeat(40), 'feat: other (#101)', '2026-08-02T00:00:00Z', [SESS.b]);
    assert.equal(applyRecovery(planRecovery({ ledgerPath: ledgerIn(root), raw })), 2);
    const rows = readLedger(root);
    assert.equal(rows.length, 2);
    assert(rows.every((r) => r.kind === 'inference' && r.source === 'shard-inference'));
    // Re-running plans nothing new: every pair is now in the ledger.
    const second = planRecovery({ ledgerPath: ledgerIn(root), raw });
    assert.equal(second.toWrite.length, 0);
    assert.equal(applyRecovery(second), 0);
    assert.equal(readLedger(root).length, 2);
  });

  run('applyRecovery preserves pre-existing ledger rows byte-for-byte (append-only)', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    const legacy = { session_id: SESS.c, merge_commit: 'z'.repeat(40), subject: 'old (#1)', ts: '2026-07-01T00:00:00Z' };
    const file = writeLedger(root, [legacy]);
    const before = fs.readFileSync(file, 'utf8');
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a]);
    applyRecovery(planRecovery({ ledgerPath: ledgerIn(root), raw }));
    const after = fs.readFileSync(file, 'utf8');
    assert(after.startsWith(before)); // legacy rows never rewritten
    assert.deepEqual(readLedger(root)[0], legacy); // still has no source/kind on disk
  });

  // --- CLI: dry-run by default -------------------------------------------

  run('CLI without --apply writes nothing and reports DRY RUN + the measured error rate', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'cli-'));
    const script = path.join(HERE, 'recover-merge-links.mjs');
    const res = spawnSync(process.execPath, [script, '--ledger', ledgerIn(root), '--limit', '1'], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    assert.match(res.stdout, /DRY RUN \(nothing written/);
    assert.match(res.stdout, /INFERENCE, NOT FACT/);
    assert.match(res.stdout, new RegExp(String(MEASURED.singleShardErrorPct).replace('.', '\\.') + '%'));
    assert.match(res.stdout, /UNVERIFIABLE/);
    assert.equal(readLedger(root), null); // the whole point
  });

  run('CLI --json reports applied:false and every row at inference tier', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'cli-json-'));
    const script = path.join(HERE, 'recover-merge-links.mjs');
    const res = spawnSync(process.execPath, [script, '--ledger', ledgerIn(root), '--json'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    assert.equal(res.status, 0, res.stderr);
    const report = JSON.parse(res.stdout);
    assert.equal(report.kind, 'justsearch-recover-merge-links.v1');
    assert.equal(report.applied, false);
    assert(report.toWrite.length > 0, 'expected real repo history to yield candidates');
    assert(report.toWrite.every((r) => r.kind === 'inference' && r.source === 'shard-inference'));
    assert.equal(report.measured.singleShardErrorPct, 8.9);
    assert.equal(readLedger(root), null);
  });

  run('CLI rejects an unknown flag rather than silently ignoring it', () => {
    const script = path.join(HERE, 'recover-merge-links.mjs');
    const res = spawnSync(process.execPath, [script, '--aply'], { encoding: 'utf8' });
    assert.equal(res.status, 2);
    assert.match(res.stderr, /Unknown or incomplete argument/);
  });

  // --- default ledger path: the second-ledger defect -----------------------

  run('the DEFAULT ledger is the main checkout\'s, resolved by the shared resolver', () => {
    // The defect this guards: defaulting to the WORKTREE's tmp/agent-telemetry
    // (which is empty) made the dry run report a full recovery, and --apply
    // would have created a SECOND ledger duplicating rows that already exist in
    // the main one — a fork of the derived cache 856 exists to remove.
    const raw = logRecord('a'.repeat(40), 'feat: thing (#100)', '2026-08-01T00:00:00Z', [SESS.a]);
    assert.equal(planRecovery({ raw }).ledgerFile, resolveDefaultMergesPath());
  });

  run('run from a linked worktree, the default ledger resolves OUTSIDE that worktree', () => {
    // `.git` is a FILE in a linked worktree and a DIRECTORY in the main
    // checkout — so this asserts the real behaviour where it matters and stays
    // correct when the suite runs from main.
    const dotGit = path.join(repoRoot, '.git');
    const inWorktree = fs.existsSync(dotGit) && fs.statSync(dotGit).isFile();
    const resolved = resolveDefaultMergesPath();
    const worktreeLedger = path.join(repoRoot, TELEMETRY_DIR, SESSION_MERGES_FILE);
    if (inWorktree) assert.notEqual(path.resolve(resolved), path.resolve(worktreeLedger));
    else assert.equal(path.resolve(resolved), path.resolve(worktreeLedger));
  });

  // --- argument validation -------------------------------------------------

  run('parseArgs errors on a flag whose value is missing instead of using the default', () => {
    assert.throws(() => parseArgs(['--ledger']), /--ledger needs a value/);
    assert.throws(() => parseArgs(['--limit']), /--limit needs a value/);
    // A following flag is not a value either — `--ledger --apply` must not
    // silently consume `--apply` as a path.
    assert.throws(() => parseArgs(['--ledger', '--apply']), /--ledger needs a value/);
  });

  run('parseArgs rejects a non-numeric --limit rather than printing an empty listing', () => {
    // Number('abc') is NaN, and slice(0, NaN) prints nothing — which reads as
    // "no links found" rather than "you typed a bad flag".
    assert.throws(() => parseArgs(['--limit', 'abc']), /non-negative integer/);
    assert.throws(() => parseArgs(['--limit', '-1']), /non-negative integer/);
    assert.throws(() => parseArgs(['--limit', '1.5']), /non-negative integer/);
    assert.equal(parseArgs(['--limit', '0']).limit, 0);
    assert.equal(parseArgs(['--limit', '7']).limit, 7);
  });

  run('CLI exits 2 on a missing flag value', () => {
    const script = path.join(HERE, 'recover-merge-links.mjs');
    const res = spawnSync(process.execPath, [script, '--limit'], { encoding: 'utf8' });
    assert.equal(res.status, 2);
    assert.match(res.stderr, /--limit needs a value/);
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`recover-merge-links.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  x ' + f);
  process.exit(1);
}
console.log(`recover-merge-links.test: ${passed} passed`);
