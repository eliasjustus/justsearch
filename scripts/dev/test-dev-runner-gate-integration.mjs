#!/usr/bin/env node
//
// Tempdoc 606 — hermetic integration test for the admission GATE (acquireAdmission).
// Drives the REAL acquireAdmission end-to-end against an ISOLATED temp state root
// (JUSTSEARCH_DEV_RUNNER_STATE_ROOT) with fast thresholds — exercising the full
// fact-gathering (lease + run.json supervisor liveness + session activity stamps),
// the verdict mapping, and the stopRun side effect — WITHOUT a backend and WITHOUT
// touching the shared lease. Complements the pure verdict unit tests by proving the
// gate wiring (audit-driven fixes need a runnable test, slice-execution discipline).
//
// Env MUST be set before requiring dev-runner.cjs (its state paths are module-load consts).

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Temp state MUST be on the same drive as the repo: acquireAdmission resolves run.json via
// path.join(mainRepoRoot, runPath), and a cross-drive path.relative yields an absolute path
// that path.join garbles. Use the worktree's gitignored tmp/ (same F: drive as mainRepoRoot).
const TMPBASE = path.join(__dirname, '..', '..', 'tmp');
fs.mkdirSync(TMPBASE, { recursive: true });
const STATE = fs.mkdtempSync(path.join(TMPBASE, 'devrunner-gate-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = STATE;
process.env.JUSTSEARCH_DEV_ABANDONED_MS = '2000';
process.env.JUSTSEARCH_DEV_IDLE_MS = '3000';

const require = createRequire(import.meta.url);
const { __test } = require(path.join(__dirname, 'dev-runner.cjs'));
const { acquireAdmission, mainRepoRoot } = __test;

const activePath = path.join(STATE, 'active.json');
const sessionsDir = path.join(STATE, 'sessions');
const runsDir = path.join(STATE, 'runs');
const HOLDER = 'sess-OTHER';
const sleepers = [];

function spawnAliveProc() {
  // A harmless long-lived child whose pid is "alive" for supervisor-liveness, and which
  // stopRun may safely kill on a proceed (it is NOT this test process).
  const p = spawn(process.execPath, ['-e', 'setTimeout(()=>{}, 600000)'], { stdio: 'ignore' });
  sleepers.push(p);
  return p.pid;
}

function craft({ runnerPid, leaseMsAhead = 30_000, activity }) {
  fs.mkdirSync(sessionsDir, { recursive: true });
  const runId = 'run-TEST';
  const runDir = path.join(runsDir, runId);
  fs.mkdirSync(runDir, { recursive: true });
  const runFile = path.join(runDir, 'run.json');
  fs.writeFileSync(runFile, JSON.stringify({ runId, pids: { runnerPid, backend: 999999, frontend: 999998 } }));
  fs.writeFileSync(activePath, JSON.stringify({
    kind: 'backend-shared-lease.v1', schemaVersion: 1, runId,
    // runPath is resolved by acquireAdmission via path.join(mainRepoRoot, runPath).
    runPath: path.relative(mainRepoRoot, runFile).split(path.sep).join('/'),
    holder: { source: 'claude', agentSessionId: HOLDER },
    takeoverPolicy: 'warn', ownershipEpoch: 3,
    lease: { durationSec: 30, renewedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + leaseMsAhead).toISOString(), sequence: 9 },
  }));
  if (activity) fs.writeFileSync(path.join(sessionsDir, `${HOLDER}.json`), JSON.stringify(activity));
  else { try { fs.unlinkSync(path.join(sessionsDir, `${HOLDER}.json`)); } catch {} }
}

function clearActive() { try { fs.unlinkSync(activePath); } catch {} }
const ago = (ms) => new Date(Date.now() - ms).toISOString();

const tests = [
  ['NO_OWNER → proceed (no disposition)', async () => {
    clearActive();
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, undefined);
  }],

  ['dead supervisor → proceed stale_reclaim', async () => {
    craft({ runnerPid: 999999, activity: { lastActivityAt: ago(500) } }); // dead pid
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'stale_reclaim');
    assert.ok(!fs.existsSync(activePath), 'active.json cleared on reclaim');
  }],

  ['ABANDONED (stale activity) + deny → proceed abandoned_reclaim (KEY: deny proceeds)', async () => {
    craft({ runnerPid: spawnAliveProc(), activity: { lastActivityAt: ago(10_000) } });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'abandoned_reclaim');
    assert.ok(!fs.existsSync(activePath), 'active.json cleared on takeover');
  }],

  ['IDLE (fresh general, stale dev) + deny → conflict idle_owner', async () => {
    craft({ runnerPid: spawnAliveProc(), activity: { lastActivityAt: ago(500), lastDevStackTouchAt: ago(10_000) } });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.reason, 'idle_owner');
    assert.equal(r.verdict, 'IDLE_HOLD');
  }],

  ['IDLE + warn → proceed idle_takeover', async () => {
    craft({ runnerPid: spawnAliveProc(), activity: { lastActivityAt: ago(500), lastDevStackTouchAt: ago(10_000) } });
    const r = await acquireAdmission({ takeover: 'warn' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'idle_takeover');
  }],

  ['ACTIVE (both fresh) + deny → conflict fresh_owner CONTENTION', async () => {
    craft({ runnerPid: spawnAliveProc(), activity: { lastActivityAt: ago(300), lastDevStackTouchAt: ago(300) } });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.reason, 'fresh_owner');
    assert.equal(r.verdict, 'CONTENTION');
  }],

  ['ACTIVE + warn → proceed warned_takeover', async () => {
    craft({ runnerPid: spawnAliveProc(), activity: { lastActivityAt: ago(300), lastDevStackTouchAt: ago(300) } });
    const r = await acquireAdmission({ takeover: 'warn' });
    assert.equal(r.action, 'proceed');
    assert.equal(r.disposition, 'warned_takeover');
  }],

  ['UNKNOWN activity (no stamp) + deny → conflict CONTENTION (conservative, NOT abandoned)', async () => {
    craft({ runnerPid: spawnAliveProc(), activity: null });
    const r = await acquireAdmission({ takeover: 'deny' });
    assert.equal(r.action, 'conflict');
    assert.equal(r.verdict, 'CONTENTION');
    assert.equal(r.reason, 'fresh_owner');
  }],
];

let pass = 0, fail = 0;
for (const [name, fn] of tests) {
  try { await fn(); console.log(`  PASS  ${name}`); pass++; }
  catch (e) { console.error(`  FAIL  ${name}: ${e.message}`); fail++; }
}
for (const p of sleepers) { try { process.kill(p.pid); } catch {} }
try { fs.rmSync(STATE, { recursive: true, force: true }); } catch {}
console.log(`test-dev-runner-gate-integration: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
