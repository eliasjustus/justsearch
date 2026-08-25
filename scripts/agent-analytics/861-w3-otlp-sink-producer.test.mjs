/**
 * Tempdoc 861 W3 [A6] — the OTel sink as the THIRD `agent-spawns/` producer, and the first
 * `ownerless-singleton` one.
 *
 * What this holds in place:
 *   - a fresh registration is refused (not written half-formed) when the process-table evidence
 *     is missing, unavailable, or does not verify — never a guess (861 [A2]/[A6]);
 *   - once a record exists, registration RENEWS the lease and does not re-derive identity —
 *     the write-if-absent/renew semantics the brief requires, so a live dev-stack session's
 *     already-running sink is never re-probed via the process table on every hook fire;
 *   - the written record declares `ownership: 'ownerless-singleton'` — a DECLARED mode, so the
 *     §6.3 matrix's "never reap" row applies for a real reason, not an accidental omission;
 *   - registration never throws, regardless of which piece of evidence is missing.
 *
 * Nothing here spawns the real sink or touches the real dev-runner state root — every call passes
 * an explicit isolated `dir` and an injected `table`, so this can never observe (or disturb) the
 * live dev stack's actual OTel sink.
 *
 * Run with: `node scripts/agent-analytics/861-w3-otlp-sink-producer.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

import {
  registerSinkSpawn,
  SINK_AGENT_SPAWN_RECORD_ID,
  SINK_AGENT_SPAWN_LEASE_DURATION_SEC,
  SINK_CMDLINE_FINGERPRINT,
  SINK_PORT,
} from './hooks/otlp-sink-ensure.mjs';

const require = createRequire(import.meta.url);
const { OWNERSHIP_MODES } = require('../dev/lib/agent-spawn-record.cjs');

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const dirsToClean = [];
async function makeDir() {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w3-sink-'));
  dirsToClean.push(dir);
  return dir;
}

const T = '134320479841300350';
const okTable = () => ({
  ok: true,
  table: [{ ProcessId: 9001, CreationFileTimeUtc: T, CommandLine: 'python otlp-sink.py --port 4318' }],
});

await check('a fresh registration writes an ownerless-singleton record', async () => {
  const dir = await makeDir();
  await registerSinkSpawn(9001, { dir, table: okTable });
  const rec = JSON.parse(await fsp.readFile(path.join(dir, `${SINK_AGENT_SPAWN_RECORD_ID}.json`), 'utf8'));
  assert.equal(rec.producer, 'otlp-sink');
  assert.equal(rec.pid, 9001);
  assert.equal(rec.ownership, OWNERSHIP_MODES.OWNERLESS_SINGLETON);
  assert.equal(rec.cmdlineFingerprint, SINK_CMDLINE_FINGERPRINT);
  assert.equal(rec.probe.port, SINK_PORT);
  assert.equal(rec.lease.durationSec, SINK_AGENT_SPAWN_LEASE_DURATION_SEC);
});

await check('an unavailable process table refuses — no record, no throw', async () => {
  const dir = await makeDir();
  await registerSinkSpawn(9001, { dir, table: () => ({ ok: false, reason: 'no evidence' }) });
  const entries = await fsp.readdir(dir).catch(() => []);
  assert.deepEqual(entries, []);
});

await check('a pid absent from the table refuses — no record, no throw', async () => {
  const dir = await makeDir();
  await registerSinkSpawn(4242, { dir, table: okTable }); // table only has pid 9001
  const entries = await fsp.readdir(dir).catch(() => []);
  assert.deepEqual(entries, []);
});

await check('an unreadable creation time refuses — no record, no throw', async () => {
  const dir = await makeDir();
  const table = () => ({ ok: true, table: [{ ProcessId: 9001, CreationFileTimeUtc: null, CommandLine: 'python otlp-sink.py' }] });
  await registerSinkSpawn(9001, { dir, table });
  const entries = await fsp.readdir(dir).catch(() => []);
  assert.deepEqual(entries, []);
});

await check('a command line missing the fingerprint refuses an unverified identity', async () => {
  const dir = await makeDir();
  const table = () => ({ ok: true, table: [{ ProcessId: 9001, CreationFileTimeUtc: T, CommandLine: 'python some-other-script.py' }] });
  await registerSinkSpawn(9001, { dir, table });
  const entries = await fsp.readdir(dir).catch(() => []);
  assert.deepEqual(entries, []);
});

await check('once registered, a second call RENEWS the lease and never re-reads the table', async () => {
  const dir = await makeDir();
  await registerSinkSpawn(9001, { dir, table: okTable });
  const first = JSON.parse(await fsp.readFile(path.join(dir, `${SINK_AGENT_SPAWN_RECORD_ID}.json`), 'utf8'));

  let tableCalled = false;
  // A table that would REFUSE if it were ever consulted — proving the renew-first branch short-
  // circuits before touching the process table at all.
  const poisoned = () => { tableCalled = true; return { ok: false, reason: 'must not be called' }; };
  await new Promise((resolve) => setTimeout(resolve, 5)); // ensure a distinguishable renewedAt
  await registerSinkSpawn(9001, { dir, table: poisoned });

  assert.equal(tableCalled, false, 'a renew must not re-derive identity from the process table');
  const second = JSON.parse(await fsp.readFile(path.join(dir, `${SINK_AGENT_SPAWN_RECORD_ID}.json`), 'utf8'));
  assert.ok(second.lease.expiresAt >= first.lease.expiresAt);
  assert.equal(second.pid, first.pid); // renewal touches the lease only
});

await check('registration never throws even when the write path itself fails', async () => {
  const dir = path.join(os.tmpdir(), '861-w3-sink-does-not-exist', 'nested', 'deep');
  // A dir under a non-existent tree still succeeds via mkdir recursive in the writer, so force a
  // real failure: point `dir` at a FILE path instead of a directory.
  const fileAsDir = await makeDir();
  const filePath = path.join(fileAsDir, 'not-a-directory');
  await fsp.writeFile(filePath, 'x');
  let threw = false;
  try {
    await registerSinkSpawn(9001, { dir: filePath, table: okTable });
  } catch {
    threw = true;
  }
  assert.equal(threw, false, 'registerSinkSpawn must never throw — bookkeeping is best-effort');
});

for (const dir of dirsToClean) {
  await fsp.rm(dir, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`861-w3-otlp-sink-producer: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`861-w3-otlp-sink-producer: all ${passed} checks passed`);
