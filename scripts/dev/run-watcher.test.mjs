/**
 * Tempdoc 743 second wave, P-M(b) — unit + CLI-integration tests for run-watcher.mjs.
 *
 * Run with: `node scripts/dev/run-watcher.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { parseRunArgs, parseCheckArgs, computeVerdict } from './run-watcher.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

const CLI = resolve(dirname(fileURLToPath(import.meta.url)), 'run-watcher.mjs');
const BASE = fs.mkdtempSync(path.join(os.tmpdir(), 'run-watcher-test-'));
function tmpDir(name) {
  return path.join(BASE, name);
}

// --- parseRunArgs / parseCheckArgs (pure) ---

run('parseRunArgs splits flags from the command vector at "--"', () => {
  const parsed = parseRunArgs(['--dir', 'C:\\state', '--', 'node', '-e', '1']);
  assert.deepEqual(parsed, { dir: 'C:\\state', markerOnExit: null, command: ['node', '-e', '1'] });
});
run('parseRunArgs reads --marker-on-exit', () => {
  const parsed = parseRunArgs(['--dir', 'd', '--marker-on-exit', 'DONE', '--', 'echo', 'hi']);
  assert.equal(parsed.markerOnExit, 'DONE');
});
run('parseRunArgs throws without "--"', () => {
  assert.throws(() => parseRunArgs(['--dir', 'd']), /missing "--"/);
});
run('parseRunArgs throws without --dir', () => {
  assert.throws(() => parseRunArgs(['--', 'echo']), /--dir is required/);
});
run('parseRunArgs throws on empty command vector', () => {
  assert.throws(() => parseRunArgs(['--dir', 'd', '--']), /no command given/);
});

run('parseCheckArgs defaults --stale-sec to 60', () => {
  const parsed = parseCheckArgs(['--dir', 'd']);
  assert.equal(parsed.staleSec, 60);
});
run('parseCheckArgs reads --stale-sec override', () => {
  const parsed = parseCheckArgs(['--dir', 'd', '--stale-sec', '120']);
  assert.equal(parsed.staleSec, 120);
});
run('parseCheckArgs throws without --dir', () => {
  assert.throws(() => parseCheckArgs([]), /--dir is required/);
});

// --- computeVerdict (pure, synthetic filesystem state) ---

run('computeVerdict: missing dir -> NO-RUN, exit 3', () => {
  const v = computeVerdict({ dir: tmpDir('does-not-exist') });
  assert.equal(v.message, 'NO-RUN(no state dir or no heartbeat ever)');
  assert.equal(v.exitCode, 3);
});
run('computeVerdict: dir exists, no heartbeat -> NO-RUN, exit 3', () => {
  const dir = tmpDir('empty-dir');
  fs.mkdirSync(dir, { recursive: true });
  const v = computeVerdict({ dir });
  assert.equal(v.message, 'NO-RUN(no state dir or no heartbeat ever)');
  assert.equal(v.exitCode, 3);
});
run('computeVerdict: fresh heartbeat, no verdict.json -> PROGRESSING, exit 0', () => {
  const dir = tmpDir('progressing');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'heartbeat'), '');
  const v = computeVerdict({ dir, staleSec: 60, now: Date.now() });
  assert.match(v.message, /^PROGRESSING\(heartbeat \d+s ago\)$/);
  assert.equal(v.exitCode, 0);
});
run('computeVerdict: stale heartbeat, no verdict.json -> STALLED-OR-DEAD, exit 2', () => {
  const dir = tmpDir('stalled');
  fs.mkdirSync(dir, { recursive: true });
  const hbPath = path.join(dir, 'heartbeat');
  fs.writeFileSync(hbPath, '');
  const old = new Date(Date.now() - 120_000);
  fs.utimesSync(hbPath, old, old);
  const v = computeVerdict({ dir, staleSec: 60, now: Date.now() });
  assert.match(v.message, /^STALLED-OR-DEAD\(heartbeat \d+s ago, exceeds stale threshold\)$/);
  assert.ok(/heartbeat (1[0-9][0-9])s ago/.test(v.message), `expected ~120s, got: ${v.message}`);
  assert.equal(v.exitCode, 2);
});
run('computeVerdict: verdict.json code 0 -> DONE-OK regardless of stale heartbeat', () => {
  const dir = tmpDir('done-ok-stale-hb');
  fs.mkdirSync(dir, { recursive: true });
  const hbPath = path.join(dir, 'heartbeat');
  fs.writeFileSync(hbPath, '');
  const old = new Date(Date.now() - 999_000);
  fs.utimesSync(hbPath, old, old);
  fs.writeFileSync(path.join(dir, 'verdict.json'), JSON.stringify({ code: 0, endedAt: 'x', durationSec: 1 }));
  const v = computeVerdict({ dir, staleSec: 60, now: Date.now() });
  assert.equal(v.message, 'DONE-OK');
  assert.equal(v.exitCode, 0);
});
run('computeVerdict: verdict.json non-zero code -> DONE-FAILED(code), exit 1', () => {
  const dir = tmpDir('done-failed');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'heartbeat'), '');
  fs.writeFileSync(path.join(dir, 'verdict.json'), JSON.stringify({ code: 5, endedAt: 'x', durationSec: 1 }));
  const v = computeVerdict({ dir, staleSec: 60, now: Date.now() });
  assert.equal(v.message, 'DONE-FAILED(5)');
  assert.equal(v.exitCode, 1);
});

// --- CLI integration: run mode drives a real child, check mode reads its state back ---

run('CLI: successful child -> heartbeat updates, verdict.json code 0, check prints DONE-OK', () => {
  const dir = tmpDir('cli-done-ok');
  const env = { ...process.env, JUSTSEARCH_WATCHER_HEARTBEAT_MS: '250' };
  const out = execFileSync(
    'node',
    [CLI, 'run', '--dir', dir, '--marker-on-exit', 'DONE.marker', '--', 'node', '-e', 'setTimeout(() => process.exit(0), 900)'],
    { encoding: 'utf8', env },
  );
  void out;

  const verdict = JSON.parse(fs.readFileSync(path.join(dir, 'verdict.json'), 'utf8'));
  assert.equal(verdict.code, 0);
  assert.ok(typeof verdict.endedAt === 'string' && verdict.endedAt.length > 0);
  assert.ok(typeof verdict.durationSec === 'number');

  assert.ok(fs.existsSync(path.join(dir, 'heartbeat')), 'heartbeat file should exist');
  assert.ok(fs.existsSync(path.join(dir, 'DONE.marker')), 'marker-on-exit file should be touched');
  assert.ok(fs.existsSync(path.join(dir, 'watched.log')), 'watched.log should exist');

  const events = fs
    .readFileSync(path.join(dir, 'events.ndjson'), 'utf8')
    .trim()
    .split('\n')
    .map((l) => JSON.parse(l));
  assert.equal(events[0].event, 'start');
  assert.equal(events.at(-1).event, 'exit');
  assert.equal(events.at(-1).code, 0);
  const heartbeatNotes = events.filter((e) => e.event === 'heartbeat-note');
  assert.ok(heartbeatNotes.length >= 1, `expected at least one heartbeat-note tick, got ${heartbeatNotes.length}`);

  const checkOut = execFileSync('node', [CLI, 'check', '--dir', dir], { encoding: 'utf8' });
  assert.equal(checkOut.trim(), 'DONE-OK');
});

run('CLI: failing child -> exit code mirrors child, verdict.json carries the code, check prints DONE-FAILED(code) exit 1', () => {
  const dir = tmpDir('cli-done-failed');
  let threw = false;
  try {
    execFileSync('node', [CLI, 'run', '--dir', dir, '--', 'node', '-e', 'process.exit(7)'], { encoding: 'utf8' });
  } catch (e) {
    threw = true;
    assert.equal(e.status, 7);
  }
  assert.ok(threw, 'expected run mode to propagate the child exit code as its own');

  const verdict = JSON.parse(fs.readFileSync(path.join(dir, 'verdict.json'), 'utf8'));
  assert.equal(verdict.code, 7);

  let checkThrew = false;
  let checkOut = '';
  try {
    checkOut = execFileSync('node', [CLI, 'check', '--dir', dir], { encoding: 'utf8' });
  } catch (e) {
    checkThrew = true;
    assert.equal(e.status, 1);
    checkOut = e.stdout;
  }
  assert.ok(checkThrew, 'expected check mode to exit 1 on a failed run');
  assert.equal(checkOut.trim(), 'DONE-FAILED(7)');
});

run('CLI: missing --dir entirely -> NO-RUN, exit 3', () => {
  const dir = tmpDir('never-created-' + Date.now());
  let threw = false;
  let out = '';
  try {
    out = execFileSync('node', [CLI, 'check', '--dir', dir], { encoding: 'utf8' });
  } catch (e) {
    threw = true;
    assert.equal(e.status, 3);
    out = e.stdout;
  }
  assert.ok(threw, 'expected check mode to exit 3 for a never-created state dir');
  assert.equal(out.trim(), 'NO-RUN(no state dir or no heartbeat ever)');
});

// Note: PROGRESSING is exercised as a pure `computeVerdict` unit test above (fresh synthetic
// heartbeat, no verdict.json) rather than against a live concurrently-running CLI child —
// waiting on a still-running background child from this synchronous test harness would need
// the async child_process 'exit' event, which a busy-wait loop cannot observe without an
// async test runner. The synthetic-filesystem-state unit test exercises the identical
// `computeVerdict` code path that a live "check-mid-run" invocation would hit.

// --- Report ---
if (failures.length > 0) {
  console.error(`run-watcher.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`run-watcher.test: all ${passed} checks passed`);
