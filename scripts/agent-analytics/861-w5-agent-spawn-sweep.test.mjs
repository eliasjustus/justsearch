/**
 * Tempdoc 861 W5 — the shared assembly (`scripts/dev/lib/agent-spawn-sweep.cjs`) that wires
 * each of §6.4's six reap occasions onto the Phase 4 reaper (`agent-spawn-reaper.cjs`).
 *
 * What this file proves, occasion by occasion, against injected records/table (861 W5 brief):
 *
 *   - `session-start` (via `runAgentSpawnSweep`) reaches a REAL kill for a lapsed-and-stale
 *     other-session record — not just an eligibility list nobody spends. A real disposable
 *     child process is spawned, killed through the full occasion pipeline, and its death is
 *     independently confirmed (never the running dev stack's own process).
 *   - `session-end` (`ownSessionOnly: true`) reaps only the calling session's OWN records and
 *     leaves an equally-stale OTHER session's record alone — the narrower scope 861 §6.4
 *     describes ("this session's own spawns"), proven by a same-fixture-set A/B.
 *   - `before-a-build` (`findBuildHolders`) NEVER mints a `reap` disposition, even against a
 *     fixture that is reap-eligible on every other axis (same-session, matching identity) — the
 *     build-hook-never-kills proof [A4] requires. Also proves the path pre-filter: a record that
 *     does not hold the target path is invisible to it.
 *   - `orientation` (`gatherAgentSpawnOrientation`) likewise mints no `reap`, and folds in the
 *     observed-tier derivation for an unregistered known-fingerprint process.
 *   - `worktree-teardown` (`consultAgentSpawnsForTeardown`) sets `blocksProceed` for a
 *     contention/refuse holder and clears it once that holder is gone — remove-worktree.cjs's
 *     actual decision input.
 *
 * Real-process cleanup: every spawned child is force-killed in a `finally` regardless of
 * whether the sweep already reaped it, so a failing assertion never leaks a process.
 *
 * Run with: `node scripts/agent-analytics/861-w5-agent-spawn-sweep.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  runAgentSpawnSweep,
  consultAgentSpawnsForTeardown,
  gatherAgentSpawnOrientation,
  findBuildHolders,
  deriveObservedRows,
  KNOWN_AGENT_SPAWN_FINGERPRINTS,
} = require('../dev/lib/agent-spawn-sweep.cjs');
const { buildAgentSpawnRecord, writeAgentSpawnRecord, OWNERSHIP_MODES } = require('../dev/lib/agent-spawn-record.cjs');
const { readProcessTable, normalizeCreationTime } = require('../dev/lib/process-identity.cjs');

let passed = 0;
const failures = [];
const spawnedPids = [];
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

/** Spawn a real, disposable, long-lived child this test owns — never the dev stack's own Vite. */
function spawnDisposableChild() {
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    stdio: 'ignore',
    windowsHide: true,
  });
  spawnedPids.push(child.pid);
  return child;
}

/** Poll the real process table until our own child appears (mirrors 861-w4-reaper-kill.test.mjs
 *  — it is not there the instant it spawns). */
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

/** Resolve the identity triple for a real child from a fresh process-table read. */
async function identityFor(pid) {
  const row = await findOwnChildRow(pid);
  assert.ok(row, `spawned child pid ${pid} never appeared in the process table`);
  const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
  assert.ok(creationFileTimeUtc, `child pid ${pid} has no readable creation time`);
  return { creationFileTimeUtc, cmdlineFingerprint: '-e', cmdline: row.CommandLine };
}

async function withTmpRegister(fn) {
  const tmp = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w5-sweep-'));
  const env = { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: tmp };
  try {
    await fn({ tmp, env, mainRepoRoot: tmp });
  } finally {
    await fsp.rm(tmp, { recursive: true, force: true }).catch(() => {});
  }
}

/** A fresh, injectable process table asserting the given rows are alive right now — matches
 *  `readProcessTable()`'s tri-state result shape without spawning PowerShell. Identity
 *  verification (pid AND creation time AND fingerprint) runs BEFORE the same-session/lease
 *  matrix rules in `classifySpawnRecord` — a fixture record must therefore verify as MATCH
 *  before its ownership/lease shape can be exercised at all; a non-existent pid reads as
 *  MISMATCH ("the process is gone"), not as evidence the matrix skips past. */
function fakeTable(now, rows) {
  return { ok: true, readAt: now, table: rows };
}

const NOW = () => Date.now();
const LAPSED_LEASE = (now) => ({
  durationSec: 1,
  renewedAt: new Date(now - 5000).toISOString(),
  expiresAt: new Date(now - 4000).toISOString(), // lapsed 4s ago
});

const skipped = [];

async function main() {
  // ── session-start: a real kill through the full occasion pipeline ──────────────────────────
  // Win32-only: `readProcessTable`/`taskkill` are implemented for win32 only
  // (`process-identity.cjs`), so this is skipped rather than silently passing on CI's Linux
  // runner — the other checks below inject a fake table and are platform-independent.
  if (process.platform !== 'win32') {
    skipped.push('session-start reaps a real, lapsed-and-stale OTHER-session record (real kill): win32-only (readProcessTable/taskkill)');
  } else {
  await check('session-start reaps a real, lapsed-and-stale OTHER-session record (real kill)', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const child = spawnDisposableChild();
      try {
        const identity = await identityFor(child.pid);
        const now = NOW();
        const record = await buildAgentSpawnRecord({
          recordId: 'w5-session-start-real-kill',
          producer: 'test',
          pid: child.pid,
          creationFileTimeUtc: identity.creationFileTimeUtc,
          cmdlineFingerprint: identity.cmdlineFingerprint,
          port: 39999,
          leaseDurationSec: 1,
          sessionId: 'other-session-aaaa',
          now,
        });
        // buildAgentSpawnRecord always mints a FUTURE lease — overwrite with a lapsed one after.
        const dir = path.join(tmp, 'agent-spawns');
        await writeAgentSpawnRecord({ dir, record: { ...record, lease: LAPSED_LEASE(now) } });

        // No activity stamp for 'other-session-aaaa' at all -> classifyActivity returns
        // known:false -> UNKNOWN -> CONTENTION (leave), not reap. Write a STALE one instead so
        // the matrix's only reaping cell (LAPSED_OWNER_STALE) is the one actually exercised.
        const sessionsDir = path.join(tmp, 'sessions');
        await fsp.mkdir(sessionsDir, { recursive: true });
        await fsp.writeFile(
          path.join(sessionsDir, 'other-session-aaaa.json'),
          JSON.stringify({ lastActivityAt: new Date(now - 20 * 60_000).toISOString() }),
        );

        const result = await runAgentSpawnSweep({
          occasion: 'session-start',
          mainRepoRoot,
          callerSessionId: 'this-session-bbbb',
          env,
          now,
          // Small thresholds so a 20-minute-old activity stamp reads as STALE without touching
          // the module-load-time DEFAULT_THRESHOLDS (env vars read there are captured once at
          // require-time, so passing an explicit thresholds object is the only reliable seam).
          thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
        });

        assert.equal(result.buckets.reap.length, 1, 'expected exactly one reap-bucket entry');
        assert.equal(result.buckets.reap[0].cell, 'other-session/lease-lapsed/owner-stale');
        assert.equal(result.kills.length, 1, 'executeReap should have been invoked once');
        assert.equal(result.kills[0].killed, true, `kill did not report success: ${JSON.stringify(result.kills[0])}`);
        assert.equal(result.kills[0].confirmed, true, 'kill was not confirmed against a fresh table');

        // Independently confirm the real process is actually gone (not just self-reported).
        await new Promise((r) => setTimeout(r, 200));
        const after = readProcessTable();
        assert.ok(after.ok);
        assert.ok(!after.table.some((r) => Number(r?.ProcessId) === child.pid), 'child pid still present in a fresh table after the reap');

        // The record file itself is removed on a confirmed kill (861 §6.3's disposal contract).
        const stillThere = await fsp.stat(path.join(dir, 'w5-session-start-real-kill.json')).then(() => true, () => false);
        assert.equal(stillThere, false, 'record file should be removed after a confirmed kill');
      } finally {
        killIfAlive(child.pid);
      }
    });
  });
  }

  // ── session-end: scoped to the caller's OWN records only ────────────────────────────────────
  await check('session-end reaps the caller\'s own stale record but leaves an equally-stale OTHER session\'s alone', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const sessionsDir = path.join(tmp, 'sessions');
      await fsp.mkdir(sessionsDir, { recursive: true });

      // Two fixture records, identical shape, different owning session. Identity verification
      // runs before the same-session/lease matrix, so a fake pid needs a matching fake table row
      // to verify MATCH — this test asserts on BUCKET MEMBERSHIP, not on a real kill (the
      // previous case already proved that end-to-end against a real process).
      const mine = await buildAgentSpawnRecord({
        recordId: 'w5-mine', producer: 'test', pid: 999901,
        creationFileTimeUtc: '134320479841300350', cmdlineFingerprint: 'vite --port 1',
        port: 40001, leaseDurationSec: 1, sessionId: 'caller-session', now,
      });
      const theirs = await buildAgentSpawnRecord({
        recordId: 'w5-theirs', producer: 'test', pid: 999902,
        creationFileTimeUtc: '134320479841300351', cmdlineFingerprint: 'vite --port 2',
        port: 40002, leaseDurationSec: 1, sessionId: 'other-session', now,
      });
      await writeAgentSpawnRecord({ dir, record: { ...mine, lease: LAPSED_LEASE(now) } });
      await writeAgentSpawnRecord({ dir, record: { ...theirs, lease: LAPSED_LEASE(now) } });
      await fsp.writeFile(path.join(sessionsDir, 'other-session.json'), JSON.stringify({ lastActivityAt: new Date(now - 20 * 60_000).toISOString() }));

      const result = await runAgentSpawnSweep({
        occasion: 'session-end',
        mainRepoRoot,
        callerSessionId: 'caller-session',
        ownSessionOnly: true,
        env,
        now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999901, CreationFileTimeUtc: '134320479841300350', CommandLine: 'vite --port 1' },
          { ProcessId: 999902, CreationFileTimeUtc: '134320479841300351', CommandLine: 'vite --port 2' },
        ]),
        thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
      });

      const evaluatedRecordIds = result.buckets.all.map((e) => e.recordId);
      assert.deepEqual(evaluatedRecordIds, ['w5-mine'], 'session-end must only evaluate the caller\'s own records, not the other session\'s');
      assert.equal(result.buckets.reap[0].cell, 'same-session');
    });
  });

  // ── before-a-build: structurally cannot mint a reap, even against a reap-eligible fixture ──
  await check('before-a-build (findBuildHolders) never returns a reap disposition, even for a same-session identity-matched holder', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'worktree-under-build');
      await fsp.mkdir(worktreeRoot, { recursive: true });

      const record = await buildAgentSpawnRecord({
        recordId: 'w5-build-holder', producer: 'test', pid: 999903,
        creationFileTimeUtc: '134320479841300352', cmdlineFingerprint: 'vite --port 3',
        port: 40003, leaseDurationSec: 3600, sessionId: 'caller-session',
        resourceRoots: { worktreeRoot }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      const result = await findBuildHolders({
        mainRepoRoot,
        targetPath: worktreeRoot,
        callerSessionId: 'caller-session', // SAME session + live lease -> would be `reap` on any EXECUTE occasion
        env,
        now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999903, CreationFileTimeUtc: '134320479841300352', CommandLine: 'vite --port 3' },
        ]),
      });

      assert.equal(result.holders.length, 1, 'the path pre-filter should have found the one holder');
      assert.equal(result.holders[0].cell, 'same-session', 'sanity: this fixture IS reap-eligible on the matrix');
      // THE PROOF: [A4] structural downgrade. `capability:'advisory'` means no entry here may
      // ever carry disposition 'reap' — this is not a policy this hook applies, it is a
      // pairing the reaper's frozen OCCASIONS map makes unreachable.
      for (const e of result.holders) {
        assert.notEqual(e.disposition, 'reap', 'before-a-build must NEVER produce a spendable reap disposition');
      }
      assert.equal(result.holders[0].disposition, 'report');
      assert.equal(result.holders[0].ceiling, 'reap');
      assert.equal(result.holders[0].downgraded, true);
    });

    // Path pre-filter: a record that does NOT hold the target path is invisible to this occasion.
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-unrelated', producer: 'test', pid: 999904,
        creationFileTimeUtc: '134320479841300353', cmdlineFingerprint: 'vite --port 4',
        port: 40004, leaseDurationSec: 3600, sessionId: 'caller-session',
        resourceRoots: { worktreeRoot: path.join(tmp, 'somewhere-else') }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      const result = await findBuildHolders({
        mainRepoRoot,
        targetPath: path.join(tmp, 'the-build-target'),
        callerSessionId: 'caller-session',
        env,
        now,
      });
      assert.deepEqual(result.holders, [], 'a record holding an unrelated path must not surface as a build holder');
    });
  });

  // ── orientation: never mints a reap either, and folds in the observed tier ──────────────────
  await check('orientation (gatherAgentSpawnOrientation) never returns a reap disposition', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-orientation', producer: 'test', pid: 999905,
        creationFileTimeUtc: '134320479841300354', cmdlineFingerprint: 'vite --port 5',
        port: 40005, leaseDurationSec: 1, sessionId: 'caller-session', now,
      });
      await writeAgentSpawnRecord({ dir, record: { ...record, lease: LAPSED_LEASE(now) } });
      const sessionsDir = path.join(tmp, 'sessions');
      await fsp.mkdir(sessionsDir, { recursive: true });
      await fsp.writeFile(path.join(sessionsDir, 'caller-session.json'), JSON.stringify({ lastActivityAt: new Date(now - 20 * 60_000).toISOString() }));

      const result = await gatherAgentSpawnOrientation({
        mainRepoRoot, env, now,
        readTable: () => fakeTable(now, [
          { ProcessId: 999905, CreationFileTimeUtc: '134320479841300354', CommandLine: 'vite --port 5' },
        ]),
        thresholds: { abandonedAfterMs: 60_000, idleAfterMs: 15 * 60_000 },
      });
      assert.equal(result.available, true);
      const own = result.buckets.all.find((e) => e.recordId === 'w5-orientation');
      assert.ok(own, 'the registered record should be evaluated');
      // Orientation has no caller session (it is a read-only, unattributed glance) — the lapsed
      // lease + stale owner activity is what makes this fixture reap-eligible on the matrix.
      assert.equal(own.cell, 'other-session/lease-lapsed/owner-stale', 'sanity: this fixture IS reap-eligible on the matrix');
      // The matrix would reap this on an EXECUTE occasion; orientation must downgrade it instead.
      for (const e of result.buckets.all) assert.notEqual(e.disposition, 'reap');
      assert.equal(own.disposition, 'report');
      assert.equal(own.ceiling, 'reap');
      assert.equal(own.downgraded, true);
    });
  });

  await check('deriveObservedRows: a known-fingerprint process with no covering record is observed; a registered pid is not', () => {
    const table = [
      { ProcessId: 111, CommandLine: 'node vite --port 9999' },
      { ProcessId: 222, CommandLine: 'python scripts/agent-analytics/otlp-sink.py --port 4318' },
      { ProcessId: 333, CommandLine: 'notepad.exe' },
    ];
    const observed = deriveObservedRows(table, new Set([111]));
    const pids = observed.map((r) => r.ProcessId).sort();
    assert.deepEqual(pids, [222], 'pid 111 is registered (excluded); pid 222 matches a known fingerprint and is unregistered; pid 333 matches no fingerprint');
    assert.ok(KNOWN_AGENT_SPAWN_FINGERPRINTS.includes('vite'));
  });

  // ── worktree-teardown: blocksProceed set/cleared based on the holder's fate ─────────────────
  await check('worktree-teardown (consultAgentSpawnsForTeardown) sets blocksProceed for a contention holder and clears it once resolved', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const now = NOW();
      const dir = path.join(tmp, 'agent-spawns');
      const worktreeRoot = path.join(tmp, 'worktree-under-teardown');
      await fsp.mkdir(worktreeRoot, { recursive: true });

      // Other session, LIVE lease -> CONTENTION -> blocksProceed (this occasion is EXECUTE,
      // so a live-leased other-session holder is a real refusal-worthy contention, not garbage).
      const record = await buildAgentSpawnRecord({
        recordId: 'w5-teardown-holder', producer: 'test', pid: 999906,
        creationFileTimeUtc: '134320479841300355', cmdlineFingerprint: 'vite --port 6',
        port: 40006, leaseDurationSec: 3600, sessionId: 'other-session',
        resourceRoots: { worktreeRoot }, now,
      });
      await writeAgentSpawnRecord({ dir, record });

      const readFakeTable = () => fakeTable(now, [
        { ProcessId: 999906, CreationFileTimeUtc: '134320479841300355', CommandLine: 'vite --port 6' },
      ]);
      const blocked = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerSessionId: 'caller-session', env, now, readTable: readFakeTable,
      });
      assert.equal(blocked.buckets.blocksProceed, true, 'a live-leased other-session holder must block teardown');
      assert.equal(blocked.buckets.contention.length, 1);
      assert.equal(blocked.buckets.reap.length, 0);

      // Now the holder is gone (its record removed, e.g. the producer cleaned up) — nothing left
      // under the path, so the fast path returns immediately and blocksProceed clears.
      await fsp.rm(path.join(dir, 'w5-teardown-holder.json'), { force: true });
      const clear = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: worktreeRoot, callerSessionId: 'caller-session', env, now, readTable: readFakeTable,
      });
      assert.equal(clear.buckets.blocksProceed, false, 'no remaining holder must not block teardown');
      assert.deepEqual(clear.buckets.all, []);
    });
  });

  await check('worktree-teardown: a path pre-filter miss produces the empty fast-path shape (no PowerShell spawn needed)', async () => {
    await withTmpRegister(async ({ tmp, env, mainRepoRoot }) => {
      const result = await consultAgentSpawnsForTeardown({
        mainRepoRoot, targetPath: path.join(tmp, 'nonexistent-worktree'), callerSessionId: 'x', env,
      });
      assert.equal(result.buckets.blocksProceed, false);
      assert.deepEqual(result.buckets.all, []);
      assert.deepEqual(result.kills, []);
    });
  });

  for (const s of skipped) console.log(`  (skipped) ${s}`);

  if (failures.length) {
    console.error(`861-w5-agent-spawn-sweep.test: ${failures.length} FAILED / ${passed} passed / ${skipped.length} skipped`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exitCode = 1;
    return;
  }
  console.log(`861-w5-agent-spawn-sweep.test: ${passed} passed / ${skipped.length} skipped`);
}

main().finally(() => {
  for (const pid of spawnedPids) killIfAlive(pid);
});
