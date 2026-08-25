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
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { buildAgentSpawnRecord, writeAgentSpawnRecord } = require('../dev/lib/agent-spawn-record.cjs');

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
async function check(label, fn) {
  try {
    await fn();
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

  // [861 W5 review F-7b] The original version of this test asserted only "exit 0, empty
  // stdout" for both hooks under JUSTSEARCH_DISABLE_HOOKS=1 — but that is ALSO true of the
  // ENABLED hook against an empty register (both are `async`/non-advisory, so neither prints
  // to stdout regardless). It could not tell "disabled" apart from "enabled, nothing to do".
  //
  // The SessionStart hook now discriminates for real: F-1 wired `runAgentSpawnSweep`'s prune
  // step into it, and pruning is a plain file-age check — no process table needed, so it is
  // deterministic and platform-independent. Seed an aged (>7d), non-live-lease record and
  // compare enabled vs disabled runs by the one thing that actually differs: whether the file
  // survives.
  async function agedRecordDir(stateRoot) {
    const dir = path.join(stateRoot, 'agent-spawns');
    const eightDaysAgo = Date.now() - 8 * 24 * 60 * 60 * 1000;
    const record = await buildAgentSpawnRecord({
      recordId: 'w5-kill-switch-aged', producer: 'test', pid: 999980,
      creationFileTimeUtc: '134320479841300380', cmdlineFingerprint: 'vite --port 80',
      port: 40080, leaseDurationSec: 60, sessionId: 'other-session', now: eightDaysAgo,
    });
    await writeAgentSpawnRecord({
      dir,
      record: {
        ...record,
        lease: { durationSec: 60, renewedAt: new Date(eightDaysAgo).toISOString(), expiresAt: new Date(eightDaysAgo + 60_000).toISOString() },
      },
    });
    return path.join(dir, 'w5-kill-switch-aged.json');
  }

  await check('JUSTSEARCH_DISABLE_HOOKS=1 discriminates for SessionStart: enabled prunes the aged record (F-1), disabled leaves it untouched', async () => {
    const enabledRoot = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-kill-switch-enabled-'));
    const disabledRoot = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-kill-switch-disabled-'));
    try {
      const enabledFile = await agedRecordDir(enabledRoot);
      const resEnabled = runHook(SESSION_START_HOOK, { session_id: 'x' }, { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: enabledRoot });
      assert.equal(resEnabled.status, 0);
      const survivedEnabled = await fsp.stat(enabledFile).then(() => true, () => false);
      assert.equal(survivedEnabled, false, 'enabled: the SessionStart sweep must prune the aged record');

      const disabledFile = await agedRecordDir(disabledRoot);
      const resDisabled = runHook(SESSION_START_HOOK, { session_id: 'x' }, { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: disabledRoot, JUSTSEARCH_DISABLE_HOOKS: '1' });
      assert.equal(resDisabled.status, 0);
      const survivedDisabled = await fsp.stat(disabledFile).then(() => true, () => false);
      assert.equal(survivedDisabled, true, 'disabled: JUSTSEARCH_DISABLE_HOOKS=1 must leave the register untouched — this is the discriminating signal the two runs differ on');
    } finally {
      await fsp.rm(enabledRoot, { recursive: true, force: true }).catch(() => {});
      await fsp.rm(disabledRoot, { recursive: true, force: true }).catch(() => {});
    }
  });

  // Honest limit (F-7b): SessionEnd does NOT prune by default (861 §6.4 scopes it to "this
  // session's own spawns", narrower than the abandonment sweep) and has no other observable
  // side effect against an empty/foreign-session register, so this remains a non-discriminating
  // smoke check — it proves the hook fails open under the kill switch, not that the kill switch
  // suppressed a mutation it would otherwise have made. The SessionStart check above is where
  // this file's actual discriminating evidence lives.
  run('JUSTSEARCH_DISABLE_HOOKS=1 silences SessionEnd (non-discriminating smoke — see comment above)', () => {
    const res = runHook(SESSION_END_HOOK, { session_id: 'x' }, { ...isolatedEnv, JUSTSEARCH_DISABLE_HOOKS: '1' });
    assert.equal(res.status, 0);
    assert.equal((res.stdout || '').trim(), '');
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
