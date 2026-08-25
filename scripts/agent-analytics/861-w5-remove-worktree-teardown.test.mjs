/**
 * Tempdoc 861 W5 — `remove-worktree.cjs`'s `worktree-teardown` occasion, driven end-to-end as a
 * real subprocess against an ISOLATED fixture `agent-spawns/` register
 * (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT` override — never the real repo's register).
 *
 * Three things this file proves (861 W5 brief: "teardown blocked by a blocksProceed holder;
 * observed-tier fallback preserved"):
 *
 *   1. A registered, other-session, LIVE-leased holder (real disposable process, matching
 *      identity) makes `remove-worktree.cjs` REFUSE — non-zero exit, a named holder + remedy
 *      line on stderr, and the target directory LEFT INTACT. This is the §2-bis (c) fix: a
 *      proceed-anyway here is what leaves a half-deleted, `.git`-less worktree shell.
 *   2. Once that holder's record is gone, the SAME target directory is torn down successfully —
 *      proving the refusal above was about the holder, not a permanent block.
 *   3. With NO registered holder at all (a register with zero records for the path), teardown
 *      proceeds exactly as before this tempdoc — the observed-tier command-line fallback
 *      (`filterHolders`/`reportHolders`, unit-tested separately in `remove-worktree.test.mjs`)
 *      is untouched, and normal deletion still works.
 *
 * A plain directory under `.claude/worktrees/` stands in for a worktree — the safety gate only
 * checks the path prefix, and `recordMergeLink`'s git probe degrades gracefully (SKIPPED) when
 * the directory is not a real git worktree, so this does not need a real `git worktree add`.
 *
 * Run with: `node scripts/agent-analytics/861-w5-remove-worktree-teardown.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { buildAgentSpawnRecord, writeAgentSpawnRecord } = require('../dev/lib/agent-spawn-record.cjs');
const { readProcessTable, normalizeCreationTime } = require('../dev/lib/process-identity.cjs');

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const REMOVE_WORKTREE = path.join(REPO_ROOT, 'scripts', 'dev', 'remove-worktree.cjs');

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

function killIfAlive(pid) {
  try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
}

async function findOwnChildRow(pid) {
  for (let i = 0; i < 15; i += 1) {
    const table = readProcessTable();
    if (table.ok) {
      const row = table.table.find((r) => Number(r?.ProcessId) === pid);
      if (row) return row;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  return null;
}

/** remove-worktree.cjs writes ALL of its own reporting to stderr (console.error) even on
 *  success, so capture both streams explicitly (spawnSync, not execFileSync — which returns
 *  only stdout, discarding stderr on the non-throwing path). */
function runRemoveWorktree(targetDir, { env }) {
  const res = spawnSync('node', [REMOVE_WORKTREE, targetDir, '--session-id', 'caller-session'], {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
  return { status: res.status, output: `${res.stdout || ''}${res.stderr || ''}` };
}

async function main() {
  if (process.platform !== 'win32') {
    console.log('861-w5-remove-worktree-teardown.test: skipped (win32-only surfaces).');
    console.log('861-w5-remove-worktree-teardown.test: 0 passed');
    return;
  }

  const scratch = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-teardown-'));
  const stateRoot = path.join(scratch, 'state');
  // The safety gate requires the target path contain `.claude/worktrees/` — reproduce that shape
  // inside the scratch dir rather than touching a real worktree.
  const fixtureWorktree = path.join(scratch, '.claude', 'worktrees', 'w5-teardown-fixture');
  await fsp.mkdir(fixtureWorktree, { recursive: true });
  await fsp.writeFile(path.join(fixtureWorktree, 'marker.txt'), 'fixture worktree content');

  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });

  try {
    const row = await findOwnChildRow(child.pid);
    assert.ok(row, `spawned child pid ${child.pid} never appeared in the process table`);
    const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
    assert.ok(creationFileTimeUtc, 'child has no readable creation time');

    const dir = path.join(stateRoot, 'agent-spawns');
    const record = await buildAgentSpawnRecord({
      recordId: 'w5-teardown-holder',
      producer: 'w5-teardown-test-fixture',
      pid: child.pid,
      creationFileTimeUtc,
      cmdlineFingerprint: '-e',
      port: 40200,
      leaseDurationSec: 3600, // LIVE — other session, live lease -> CONTENTION -> blocksProceed
      sessionId: 'other-session',
      resourceRoots: { worktreeRoot: fixtureWorktree },
    });
    await writeAgentSpawnRecord({ dir, record });

    const env = { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot };

    await check('a registered, other-session, live-leased holder REFUSES the teardown (non-zero exit)', () => {
      const { status, output } = runRemoveWorktree(fixtureWorktree, { env });
      assert.equal(status, 1, `expected exit 1, got ${status}. Output:\n${output}`);
      assert.match(output, /refusing to remove/i);
      assert.match(output, /w5-teardown-test-fixture/, 'the holder\'s producer should be named');
      assert.match(output, /taskkill/i, 'a ready-to-run remedy line should be printed');
    });

    await check('the target directory is LEFT INTACT after a refusal (the §2-bis (c) fix)', async () => {
      const stillThere = await fsp.stat(fixtureWorktree).then((s) => s.isDirectory(), () => false);
      assert.equal(stillThere, true, 'a refused teardown must not leave a half-deleted directory');
      const markerStillThere = await fsp.stat(path.join(fixtureWorktree, 'marker.txt')).then(() => true, () => false);
      assert.equal(markerStillThere, true);
    });

    await check('once the holder\'s record is gone, the SAME directory tears down successfully', async () => {
      await fsp.rm(path.join(dir, 'w5-teardown-holder.json'), { force: true });
      const { status, output } = runRemoveWorktree(fixtureWorktree, { env });
      assert.equal(status, 0, `expected exit 0, got ${status}. Output:\n${output}`);
      assert.match(output, /deleted/i);
      const gone = await fsp.stat(fixtureWorktree).then(() => false, () => true);
      assert.equal(gone, true, 'the directory should be deleted once no holder remains');
    });
  } finally {
    killIfAlive(child.pid);
  }

  // ── Observed-tier fallback: with NO agent-spawns register at all, teardown proceeds exactly
  // as before this tempdoc — the new consult step must be a no-op when nothing is registered. ──
  await check('with an empty (no-record) register, teardown proceeds normally (no regression)', async () => {
    const emptyStateRoot = path.join(scratch, 'empty-state');
    const target = path.join(scratch, '.claude', 'worktrees', 'w5-teardown-no-register');
    await fsp.mkdir(target, { recursive: true });
    await fsp.writeFile(path.join(target, 'marker.txt'), 'x');

    const { status, output } = runRemoveWorktree(target, { env: { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: emptyStateRoot } });
    assert.equal(status, 0, `expected exit 0, got ${status}. Output:\n${output}`);
    assert.match(output, /deleted/i);
    assert.doesNotMatch(output, /agent-spawns register/i, 'no register consult output when nothing is registered');
    const gone = await fsp.stat(target).then(() => false, () => true);
    assert.equal(gone, true);
  });

  await fsp.rm(scratch, { recursive: true, force: true }).catch(() => {});

  if (failures.length) {
    console.error(`861-w5-remove-worktree-teardown.test: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exitCode = 1;
    return;
  }
  console.log(`861-w5-remove-worktree-teardown.test: ${passed} passed`);
}

main();
