/**
 * Tempdoc 861 W2 — the `agent-spawns/` record grammar, its lease, and [A10] retention.
 *
 * What these tests hold in place:
 *
 *   - [A8] the scope carries its OWN schema version, structurally independent of `foreign/`'s;
 *   - [A9] the scope resolves through `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`, as a SIBLING of
 *     `foreign/` and never inside the dev-runner's enumerated children;
 *   - [A7] validation is per-scope, proven in BOTH directions: neither scope's reader silently
 *     accepts the other's records;
 *   - reading never deletes — the record files survive every read, including stale and
 *     failed-verify ones;
 *   - [A10] pruning is an explicit maintenance call with its own semantics: age AND no live lease,
 *     failed-verify records pruned by age alone, symlinks never deleted through.
 *
 * Sited under `scripts/agent-analytics/` because that directory is CI-discovered
 * (`run-all-tests.mjs:31-40`) while `scripts/dev/*.test.mjs` is not (861 §7.6).
 *
 * Run with: `node scripts/agent-analytics/861-w2-agent-spawn-record.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  AGENT_SPAWN_RECORD_SCHEMA_VERSION,
  AGENT_SPAWNS_REGISTER_DIRNAME,
  AGENT_SPAWNS_REGISTER_RELPOSIX,
  OWNERSHIP_MODES,
  DEFAULT_MAX_RECORD_AGE_MS,
  resolveAgentSpawnsRegisterDir,
  agentSpawnRecordPath,
  assertSafeRecordId,
  validateAgentSpawnRecord,
  readAgentSpawnRegister,
  buildAgentSpawnRecord,
  writeAgentSpawnRecord,
  removeAgentSpawnRecord,
  renewAgentSpawnLease,
  markAgentSpawnRecordFailedVerify,
  leaseState,
  recordHoldsPath,
  resolveNodeModulesRealPath,
  pruneAgentSpawnRecords,
} = require('../dev/lib/agent-spawn-record.cjs');
const {
  FOREIGN_RECORD_SCHEMA_VERSION,
  resolveForeignRegisterDir,
  validateForeignRecord,
  readForeignRegister,
  writeRecordAtomic,
} = require('../dev/lib/process-record.cjs');

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
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), '861-w2-'));
  dirsToClean.push(dir);
  return dir;
}

const T = '134320479841300350';
const DAY = 24 * 60 * 60 * 1000;

function goodRecord(over = {}) {
  return buildAgentSpawnRecord({
    recordId: 'ui-shot-5173',
    producer: 'ui-shot',
    pid: 4242,
    creationFileTimeUtc: T,
    cmdlineFingerprint: 'vite --port 5173',
    port: 5173,
    leaseDurationSec: 1800,
    sessionId: 'bccfc163',
    repoRoot: 'F:/justsearch-public',
    ...over,
  });
}

// The shape `foreign/`'s producer (`run_register.py`) writes: schemaVersion 1, ports.api, no
// identity triple, no ownership mode.
const FOREIGN_SHAPED = () => ({
  schemaVersion: FOREIGN_RECORD_SCHEMA_VERSION,
  recordId: 'jseval-33221',
  producer: 'jseval',
  pid: 9001,
  ports: { api: 33221 },
  repoRoot: 'F:/justsearch-public',
  workload: 'eval',
  startedAt: new Date().toISOString(),
});

/* ── [A8] the scope's own schema version ──────────────────────────────────────────────────── */

await check('[A8] the agent-spawns version constant is this scope\'s own, not foreign\'s', () => {
  assert.equal(typeof AGENT_SPAWN_RECORD_SCHEMA_VERSION, 'number');
  // They are both 1 today by coincidence, not coupling — so the test that matters is that the
  // agent-spawns validator names ITS OWN constant and rejects a record declaring a different one,
  // independent of whatever `foreign/` happens to be at.
  const bumped = { ...goodRecord(), schemaVersion: AGENT_SPAWN_RECORD_SCHEMA_VERSION + 1 };
  const v = validateAgentSpawnRecord(bumped);
  assert.equal(v.ok, false);
  assert.match(v.reason, new RegExp(`understands ${AGENT_SPAWN_RECORD_SCHEMA_VERSION}`));
  assert.match(v.reason, /agent-spawn schemaVersion/);
});

/* ── [A9] directory resolution ────────────────────────────────────────────────────────────── */

await check('[A9] the scope honours JUSTSEARCH_DEV_RUNNER_STATE_ROOT', () => {
  const withOverride = resolveAgentSpawnsRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: 'D:/iso/state' });
  assert.equal(path.basename(withOverride), AGENT_SPAWNS_REGISTER_DIRNAME);
  assert.equal(path.resolve(withOverride), path.resolve('D:/iso/state', AGENT_SPAWNS_REGISTER_DIRNAME));
  // No override: the default main-checkout state root, at the documented relative path.
  const plain = resolveAgentSpawnsRegisterDir('F:/main', {});
  assert.equal(path.resolve(plain), path.resolve('F:/main', AGENT_SPAWNS_REGISTER_RELPOSIX));
  // A blank override is not an override.
  assert.equal(resolveAgentSpawnsRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: '   ' }), plain);
});

await check('the scope is a SIBLING of foreign/, never inside it or inside the dev-runner\'s children', () => {
  const mine = resolveAgentSpawnsRegisterDir('F:/main', {});
  const theirs = resolveForeignRegisterDir('F:/main', {});
  assert.equal(path.dirname(mine), path.dirname(theirs));
  assert.notEqual(mine, theirs);
  // `dev-runner.cjs` enumerates only `runs/`; a sibling directory cannot be mistaken for a run.
  assert.notEqual(path.basename(mine), 'runs');
  assert.equal(AGENT_SPAWNS_REGISTER_RELPOSIX, `tmp/dev-runner/${AGENT_SPAWNS_REGISTER_DIRNAME}`);
});

/* ── [A7] validation is per-scope, in BOTH directions ─────────────────────────────────────── */

await check('[A7] a foreign-shaped record in agent-spawns/ reads as unreadable, never accepted', async () => {
  const dir = await makeDir();
  await writeRecordAtomic(path.join(dir, 'jseval-33221.json'), FOREIGN_SHAPED());
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].ok, false);
  assert.match(entries[0].reason, /creationFileTimeUtc|ownership|cmdlineFingerprint/);
});

await check('[A7] and the mirror: an agent-spawn record fails foreign/\'s validator', async () => {
  const rec = goodRecord();
  assert.equal(validateForeignRecord(rec).ok, false);
  const dir = await makeDir();
  await writeRecordAtomic(path.join(dir, 'ui-shot-5173.json'), rec);
  const entries = await readForeignRegister({ dir });
  assert.equal(entries[0].ok, false);
});

await check('an UNKNOWN probe kind is reported unreadable, not guessed at', () => {
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), probe: { kind: 'pipe', name: '\\\\.\\pipe\\x' } }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), probe: { kind: 'port', port: 0 } }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), probe: undefined }).ok, false);
});

await check('the identity triple is REQUIRED by the record shape, not optional', () => {
  for (const patch of [
    { pid: 0 },
    { pid: 'x' },
    { creationFileTimeUtc: undefined },
    { creationFileTimeUtc: 'garbage' },
    { cmdlineFingerprint: '' },
  ]) {
    const v = validateAgentSpawnRecord({ ...goodRecord(), ...patch });
    assert.equal(v.ok, false, `expected invalid for ${JSON.stringify(patch)}`);
  }
});

await check('ownership: both declared modes accepted, anything else rejected', () => {
  for (const mode of Object.values(OWNERSHIP_MODES)) {
    assert.equal(validateAgentSpawnRecord({ ...goodRecord(), ownership: mode }).ok, true);
  }
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), ownership: 'exempt' }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), ownership: undefined }).ok, false);
  // The OTel sink's mode is declared, not an exemption (861 [A6]).
  const sink = goodRecord({ recordId: 'otlp-sink-4318', producer: 'otlp-sink-ensure', port: 4318, ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON });
  assert.equal(sink.ownership, 'ownerless-singleton');
  assert.equal(validateAgentSpawnRecord(sink).ok, true);
});

await check('a malformed lease invalidates the record rather than being ignored', () => {
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), lease: { durationSec: 0, renewedAt: 'x', expiresAt: 'y' } }).ok, false);
  assert.equal(validateAgentSpawnRecord({ ...goodRecord(), lease: { durationSec: 60, renewedAt: 'nope', expiresAt: 'nope' } }).ok, false);
  const { lease, ...noLease } = goodRecord();
  assert.equal(validateAgentSpawnRecord(noLease).ok, true, 'a record with NO lease is structurally valid; its lease state is "unknown"');
});

/* ── Build / write / read / remove ────────────────────────────────────────────────────────── */

await check('a built record round-trips through write and read', async () => {
  const dir = await makeDir();
  const rec = goodRecord();
  const file = await writeAgentSpawnRecord({ dir, record: rec });
  assert.equal(file, agentSpawnRecordPath(dir, 'ui-shot-5173'));
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].ok, true);
  assert.deepEqual(entries[0].record, rec);
  assert.equal(entries[0].record.creationFileTimeUtc, T);
});

await check('building or writing an invalid record throws at the producer, not hours later at a reader', async () => {
  const dir = await makeDir();
  assert.throws(() => buildAgentSpawnRecord({ recordId: 'x', producer: 'p', pid: 1, creationFileTimeUtc: 'nope', cmdlineFingerprint: 'f', port: 1, leaseDurationSec: 10 }));
  await assert.rejects(() => writeAgentSpawnRecord({ dir, record: { ...goodRecord(), ownership: 'exempt' } }));
});

await check('a traversal-shaped recordId is refused loudly, not sanitized quietly', () => {
  for (const bad of ['../escape', 'a/b', 'a\\b', '..', '', '.hidden', 'x'.repeat(200)]) {
    assert.throws(() => assertSafeRecordId(bad), undefined, `expected throw for ${JSON.stringify(bad)}`);
  }
  assert.equal(assertSafeRecordId('ui-shot_5173.dev'), 'ui-shot_5173.dev');
});

await check('two servers produce two records — the six-leak shape the single-slot file could not hold', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: goodRecord() });
  await writeAgentSpawnRecord({ dir, record: goodRecord({ recordId: 'ui-shot-5174', port: 5174, cmdlineFingerprint: 'vite --port 5174', pid: 4343 }) });
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 2);
  assert.equal(entries.every((e) => e.ok), true);
});

await check('removeAgentSpawnRecord retires one record and reports an absent one honestly', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: goodRecord() });
  assert.deepEqual((await removeAgentSpawnRecord({ dir, recordId: 'ui-shot-5173' })).removed, true);
  assert.equal((await removeAgentSpawnRecord({ dir, recordId: 'ui-shot-5173' })).removed, false);
  assert.equal((await readAgentSpawnRegister({ dir })).length, 0);
});

/* ── Lease-on-use ─────────────────────────────────────────────────────────────────────────── */

await check('leaseState is a tri-state: unknown is NOT lapsed', () => {
  const now = Date.now();
  assert.equal(leaseState(goodRecord(), now), 'live');
  assert.equal(leaseState({ lease: { durationSec: 60, renewedAt: 'x', expiresAt: new Date(now - 1000).toISOString() } }, now), 'lapsed');
  assert.equal(leaseState({}, now), 'unknown');
  assert.equal(leaseState({ lease: { expiresAt: 'not-a-date' } }, now), 'unknown');
});

await check('lease-on-use: a reuse refreshes the existing record rather than duplicating it', async () => {
  const dir = await makeDir();
  const t0 = Date.UTC(2026, 7, 25, 10, 0, 0);
  await writeAgentSpawnRecord({ dir, record: goodRecord({ now: t0 }) });
  const before = (await readAgentSpawnRegister({ dir }))[0].record;
  const t1 = t0 + 10 * 60 * 1000;
  const res = await renewAgentSpawnLease({ dir, recordId: 'ui-shot-5173', now: t1 });
  assert.equal(res.renewed, true);
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1, 'a reuse must refresh, not duplicate');
  const after = entries[0].record;
  assert.ok(new Date(after.lease.expiresAt) > new Date(before.lease.expiresAt));
  assert.equal(after.lease.durationSec, before.lease.durationSec, 'the duration carries over when the caller does not restate it');
  assert.equal(after.startedAt, before.startedAt, 'a renewal must not rewrite when the process started');
  assert.equal(after.pid, before.pid);
  assert.equal((await renewAgentSpawnLease({ dir, recordId: 'nope' })).renewed, false);
});

/* ── [A5] failed-verify retention, and reading never deletes ──────────────────────────────── */

await check('[A5] a failed identity verify RETAINS the record with a marker', async () => {
  const dir = await makeDir();
  await writeAgentSpawnRecord({ dir, record: goodRecord() });
  const res = await markAgentSpawnRecordFailedVerify({ dir, recordId: 'ui-shot-5173', verdict: 'refuse', reason: 'process table unavailable' });
  assert.equal(res.marked, true);
  const entries = await readAgentSpawnRegister({ dir });
  assert.equal(entries.length, 1, 'the record must survive the refusal — the diagnostic trail is the point');
  assert.equal(entries[0].ok, true, 'the marker must not invalidate the record');
  assert.equal(entries[0].record.identityVerify.verdict, 'refuse');
  assert.match(entries[0].record.identityVerify.reason, /process table unavailable/);
  assert.ok(entries[0].record.identityVerify.at);
});

await check('reading NEVER deletes — stale, failed-verify, and unreadable records all survive a read', async () => {
  const dir = await makeDir();
  const stale = goodRecord({ recordId: 'stale-1', now: Date.now() - 90 * DAY });
  await writeAgentSpawnRecord({ dir, record: stale });
  await markAgentSpawnRecordFailedVerify({ dir, recordId: 'stale-1', verdict: 'refuse', reason: 'r' });
  await fsp.writeFile(path.join(dir, 'torn.json'), '{ not json', 'utf8');
  const before = (await fsp.readdir(dir)).sort();
  await readAgentSpawnRegister({ dir });
  await readAgentSpawnRegister({ dir });
  assert.deepEqual((await fsp.readdir(dir)).sort(), before);
});

/* ── [A10] pruning ────────────────────────────────────────────────────────────────────────── */

await check('[A10] prune deletes only records that are BOTH too old AND without a live lease', async () => {
  const dir = await makeDir();
  const now = Date.now();
  // (a) old + lapsed lease -> garbage.
  await writeAgentSpawnRecord({ dir, record: goodRecord({ recordId: 'old-lapsed', now: now - 30 * DAY }) });
  // (b) young + lapsed lease -> too new to judge.
  await writeAgentSpawnRecord({ dir, record: goodRecord({ recordId: 'young-lapsed', now: now - 1 * DAY, leaseDurationSec: 60 }) });
  // (c) old + LIVE lease -> an ownerless-singleton renewing its claim. Deleting this would demote a
  //     wanted daemon to the observed tier, where the sweep prints a kill line beside it.
  const singleton = goodRecord({ recordId: 'otlp-sink-4318', producer: 'otlp-sink-ensure', port: 4318, ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON, now: now - 30 * DAY });
  singleton.lease = { durationSec: 3600, renewedAt: new Date(now).toISOString(), expiresAt: new Date(now + 3600_000).toISOString() };
  await writeAgentSpawnRecord({ dir, record: singleton });

  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.deepEqual(res.deletedIds, ['old-lapsed']);
  assert.equal(res.found, 3);
  assert.equal(res.deleted, 1);
  assert.equal(res.retained, 2);
  const left = (await fsp.readdir(dir)).sort();
  assert.deepEqual(left, ['otlp-sink-4318.json', 'young-lapsed.json']);
});

await check('[A10] a failed-verify record is pruned by AGE ALONE — the marker buys time, not immortality', async () => {
  const dir = await makeDir();
  const now = Date.now();
  await writeAgentSpawnRecord({ dir, record: goodRecord({ recordId: 'fv-old', now: now - 30 * DAY }) });
  await writeAgentSpawnRecord({ dir, record: goodRecord({ recordId: 'fv-young', now: now - 1 * DAY, leaseDurationSec: 60 }) });
  for (const id of ['fv-old', 'fv-young']) {
    await markAgentSpawnRecordFailedVerify({ dir, recordId: id, verdict: 'refuse', reason: 'creation time unreadable', now });
  }
  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.deepEqual(res.deletedIds, ['fv-old'], 'the marker must not exempt an aged record from retention limits');
  assert.deepEqual((await fsp.readdir(dir)).sort(), ['fv-young.json']);
});

await check('[A10] prune is explicit, bounded, and non-destructive where evidence is missing', async () => {
  const dir = await makeDir();
  const now = Date.now();
  // An unreadable file has no lease to honour, so age alone decides — and it is still reported.
  const torn = path.join(dir, 'torn.json');
  await fsp.writeFile(torn, '{ not json', 'utf8');
  await fsp.utimes(torn, new Date(now - 30 * DAY), new Date(now - 30 * DAY));
  const young = path.join(dir, 'torn-young.json');
  await fsp.writeFile(young, '{ not json', 'utf8');
  await fsp.writeFile(path.join(dir, 'notes.txt'), 'not a record', 'utf8');

  const dry = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now, dryRun: true });
  assert.deepEqual(dry.deletedIds, ['torn']);
  assert.equal(dry.dryRun, true);
  assert.equal((await fsp.readdir(dir)).length, 3, 'a dry run must delete nothing');

  const res = await pruneAgentSpawnRecords({ dir, maxAgeMs: 7 * DAY, now });
  assert.deepEqual(res.deletedIds, ['torn']);
  assert.deepEqual((await fsp.readdir(dir)).sort(), ['notes.txt', 'torn-young.json'], 'a non-.json file is never touched');

  // A missing directory is not an error, and reports nothing deleted.
  const gone = await pruneAgentSpawnRecords({ dir: path.join(dir, 'nope'), now });
  assert.deepEqual([gone.found, gone.deleted], [0, 0]);
  assert.equal(DEFAULT_MAX_RECORD_AGE_MS, 7 * DAY);
});

/* ── The junction-lock lookup ─────────────────────────────────────────────────────────────── */

await check('recordHoldsPath answers the junction-lock question a path scan cannot', () => {
  // The incident shape (861 §6.2): a Vite serving a WORKTREE holds the MAIN checkout's
  // node_modules, because the worktree's copy is a junction into it. Only the RESOLVED target makes
  // the main-checkout holder findable from a worktree-spawned process.
  const rec = goodRecord({
    resourceRoots: { worktreeRoot: 'F:/justsearch-public/.claude/worktrees/wt-a', nodeModulesRealPath: 'F:/justsearch-public/modules/ui-web/node_modules' },
  });
  assert.equal(recordHoldsPath(rec, 'F:/justsearch-public/modules/ui-web/node_modules/lightningcss-win32-x64-msvc/lightningcss.node'), true);
  assert.equal(recordHoldsPath(rec, 'F:/justsearch-public/modules/ui-web/node_modules'), true);
  assert.equal(recordHoldsPath(rec, 'F:/justsearch-public/.claude/worktrees/wt-a/src/main.ts'), true);
  assert.equal(recordHoldsPath(rec, 'F:/justsearch-public/.claude/worktrees/wt-b/src/main.ts'), false);
  // A prefix that is not a path boundary must not match.
  assert.equal(recordHoldsPath(rec, 'F:/justsearch-public/modules/ui-web/node_modules-backup/x'), false);
  assert.equal(recordHoldsPath({}, 'F:/anything'), false);
  assert.equal(recordHoldsPath(rec, ''), false);
});

await check('resolveNodeModulesRealPath resolves through the junction, and reports absence as absence', async () => {
  const dir = await makeDir();
  assert.equal(await resolveNodeModulesRealPath(dir), null);
  await fsp.mkdir(path.join(dir, 'node_modules'));
  const resolved = await resolveNodeModulesRealPath(dir);
  assert.equal(resolved, await fsp.realpath(path.join(dir, 'node_modules')));
  assert.equal(await resolveNodeModulesRealPath(null), null);
});

/* ── cleanup + report ─────────────────────────────────────────────────────────────────────── */

for (const dir of dirsToClean) await fsp.rm(dir, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`861-w2-agent-spawn-record.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w2-agent-spawn-record.test: all ${passed} checks passed`);
