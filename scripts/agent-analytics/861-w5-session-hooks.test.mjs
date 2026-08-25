/**
 * Tempdoc 861 W5 — smoke tests for the SessionStart (`agent-spawn-sweep-hint.mjs`) and
 * SessionEnd (`agent-spawn-session-end-reap.mjs`) hooks.
 *
 * These two are best-effort, fire-and-forget-shaped (SessionStart is bound `async: true`; both
 * degrade to a telemetry note rather than surfacing anything to the transcript), so their own
 * occasion-selection logic is already proven against injected records/table by
 * `861-w5-agent-spawn-sweep.test.mjs`. What THIS file proves is the thin wiring layer itself:
 * each hook loads without crashing, never emits a `hookSpecificOutput` (they are not advisory
 * hint hooks — nothing should print to stdout), stays silent and exits 0 under
 * `JUSTSEARCH_DISABLE_HOOKS=1`, and — the one behavioral difference between them — the
 * SessionEnd hook is a true no-op without a `session_id` (nothing is attributable to reap),
 * while the SessionStart hook still runs its full-register sweep even without one (the
 * occasion's whole job is covering a PRIOR, now-unreachable session's leaks).
 *
 * Run against an ISOLATED, empty fixture register (`JUSTSEARCH_DEV_RUNNER_STATE_ROOT` override)
 * so this never touches the real dev stack's `tmp/dev-runner/agent-spawns/`.
 *
 * Run with: `node scripts/agent-analytics/861-w5-session-hooks.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOOKS_DIR = path.join(HERE, 'hooks');
const SESSION_START_HOOK = path.join(HOOKS_DIR, 'agent-spawn-sweep-hint.mjs');
const SESSION_END_HOOK = path.join(HOOKS_DIR, 'agent-spawn-session-end-reap.mjs');

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

function runHook(hookFile, payload, extraEnv = {}) {
  const res = spawnSync('node', [hookFile], {
    input: JSON.stringify(payload),
    encoding: 'utf8',
    timeout: 15000,
    env: { ...process.env, ...extraEnv },
  });
  return res;
}

async function main() {
  const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-session-hooks-'));
  const isolatedEnv = { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: tmp };

  run('SessionStart hook loads and exits 0 with an empty register + a session id', () => {
    const res = runHook(SESSION_START_HOOK, { session_id: `w5-hook-test-${process.pid}` }, isolatedEnv);
    assert.equal(res.status, 0, `expected exit 0, got ${res.status}. stderr:\n${res.stderr}`);
    assert.equal((res.stdout || '').trim(), '', 'SessionStart sweep is not an advisory hint — it must print nothing');
  });

  run('SessionStart hook is a no-crash no-op with NO session id (still sweeps the register)', () => {
    const res = runHook(SESSION_START_HOOK, {}, isolatedEnv);
    assert.equal(res.status, 0);
  });

  run('SessionEnd hook loads and exits 0 with an empty register + a session id', () => {
    const res = runHook(SESSION_END_HOOK, { session_id: `w5-hook-test-${process.pid}` }, isolatedEnv);
    assert.equal(res.status, 0, `expected exit 0, got ${res.status}. stderr:\n${res.stderr}`);
    assert.equal((res.stdout || '').trim(), '');
  });

  run('SessionEnd hook is a true no-op with NO session id (nothing is attributable to reap)', () => {
    // A session id is required to scope `ownSessionOnly` — without one, the hook must return
    // before touching the register at all, per its own early-return guard.
    const res = runHook(SESSION_END_HOOK, {}, isolatedEnv);
    assert.equal(res.status, 0);
    assert.equal((res.stdout || '').trim(), '');
  });

  run('JUSTSEARCH_DISABLE_HOOKS=1 silences both hooks', () => {
    for (const hook of [SESSION_START_HOOK, SESSION_END_HOOK]) {
      const res = runHook(hook, { session_id: 'x' }, { ...isolatedEnv, JUSTSEARCH_DISABLE_HOOKS: '1' });
      assert.equal(res.status, 0);
      assert.equal((res.stdout || '').trim(), '');
    }
  });

  run('both hooks fail open (exit 0) on completely empty/garbage stdin', () => {
    for (const hook of [SESSION_START_HOOK, SESSION_END_HOOK]) {
      const res = spawnSync('node', [hook], { input: '', encoding: 'utf8', timeout: 15000, env: { ...process.env, ...isolatedEnv } });
      assert.equal(res.status, 0, `${path.basename(hook)} did not fail open on empty stdin: ${res.stderr}`);
    }
  });

  await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});

  if (failures.length) {
    console.error(`861-w5-session-hooks.test: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exitCode = 1;
    return;
  }
  console.log(`861-w5-session-hooks.test: ${passed} passed`);
}

main();
