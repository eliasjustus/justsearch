/**
 * Tempdoc 861 W1 — the shared process-record grammar and reader.
 *
 * Phase 1 extracted `probeForeignRuns` / `readForeignRegister` / `resolveForeignRegisterDir` (and
 * their `httpGetStatusCode` / `pidAlive` / `FOREIGN_*` dependents) out of
 * `scripts/dev/justsearch-dev-mcp/server.mjs` into `scripts/dev/lib/process-record.cjs`, re-pointing
 * `foreign/` at the shared module with NO behaviour change. This is the [A7] acceptance test for
 * that phase:
 *
 *   - the negative fixture: an agent-spawn-SHAPED record (portless, no `ports.api`) placed in
 *     `foreign/` must resolve to `state: 'unreadable'`, never be silently accepted as `ok: true` —
 *     proof that record VALIDATION stayed per-scope (`validateForeignRecord`, injected into the
 *     shared reader) rather than migrating into the shared envelope (`readRegister` itself accepts
 *     any parseable JSON when no validator is supplied — asserted directly below);
 *   - a byte-identical fixture test over well-formed `foreign/` records, matching the shape
 *     `scripts/dev/test-dev-mcp-surface-honesty.mjs`'s own `foreign/` fixtures already assert, so a
 *     future edit to either file cannot let the two silently diverge;
 *   - `resolveRegisterDir`/`resolveForeignRegisterDir` honour `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`
 *     ([A9]) via the ONE generic resolution helper a sibling scope (861 Phase 2's `agent-spawns/`)
 *     will reuse;
 *   - a require-and-call smoke over `server.mjs`'s own re-exports, proving the re-point did not
 *     diverge from the shared module it delegates to (`server.mjs`'s own pure-unit coverage,
 *     `scripts/dev/test-dev-mcp-surface-honesty.mjs`, is NOT CI-discovered — 861 §7.6 — so this file,
 *     which IS discovered by `scripts/agent-analytics/run-all-tests.mjs`, is the one that runs in CI).
 *
 * Run with: `node scripts/agent-analytics/861-w1-process-record.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  // Generic layer.
  resolveRegisterDir,
  readRegister,
  pidAlive,
  httpGetStatusCode,
  deriveLivenessState,
  // Foreign scope.
  FOREIGN_BACKEND_PORTS,
  FOREIGN_REGISTER_RELPOSIX,
  FOREIGN_REGISTER_DIRNAME,
  FOREIGN_RECORD_SCHEMA_VERSION,
  resolveForeignRegisterDir,
  readForeignRegister,
  validateForeignRecord,
  probeForeignRuns,
} = require('../dev/lib/process-record.cjs');

// server.mjs's own re-exports of the same names — must delegate to the identical implementation
// (not a fork). This is the "exercise server.mjs's consumption" leg (861 W1 brief), run from a
// location `run-all-tests.mjs` actually discovers, since `test-dev-mcp-surface-honesty.mjs` is not
// CI-discovered (861 §7.6).
const serverMjs = await import('../dev/justsearch-dev-mcp/server.mjs');

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

/* ── fixtures ─────────────────────────────────────────────────────────────────────────────── */

/** A well-formed `foreign/` record, matching the shape `run_register.py` writes. */
const FOREIGN_REC = (over = {}) => ({
  schemaVersion: 1,
  producer: 'jseval',
  recordId: 'jseval-4242',
  pid: 4242,
  ports: { api: 33221 },
  repoRoot: 'F:\\justsearch-public\\.claude\\worktrees\\round14',
  dataDir: 'F:\\justsearch-public\\tmp\\headless-eval-data',
  workload: 'eval-backend',
  inferenceRequested: false,
  gpuBound: 'unverified',
  sessionId: 'b73007cd-907a-48da-8d74-2379da83be8f',
  startedAt: '2026-08-19T09:00:00Z',
  ...over,
});

/**
 * An agent-spawn-SHAPED record — portless, the shape 861 §6.2 describes for the sibling scope
 * (pid, resolved resource root, a lease, no `ports` object at all). This is deliberately NOT a
 * `foreign/` record: it has no `ports.api`, which is exactly what `foreign/`'s reader requires.
 */
const AGENT_SPAWN_SHAPED_REC = (over = {}) => ({
  schemaVersion: 1,
  producer: 'ui-shot',
  recordId: 'ui-shot-9001',
  pid: 9001,
  root: 'F:\\justsearch-public\\.claude\\worktrees\\round14\\modules\\ui-web',
  ownershipMode: 'session-owned',
  lease: { durationSec: 600, renewedAt: '2026-08-25T09:00:00Z', expiresAt: '2026-08-25T09:10:00Z' },
  sessionId: 'b73007cd-907a-48da-8d74-2379da83be8f',
  startedAt: '2026-08-25T09:00:00Z',
  ...over,
});

/** Write `files` (name → object) into a fresh register directory; returns its path. */
const dirsToClean = [];
async function makeRegisterDir(files) {
  const dir = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-861-w1-'));
  for (const [name, body] of Object.entries(files)) {
    await fsp.writeFile(path.join(dir, name), JSON.stringify(body, null, 2), 'utf8');
  }
  dirsToClean.push(dir);
  return dir;
}

const listening = (...ports) => async (url) => {
  const port = Number(new URL(url).port);
  return ports.includes(port) ? 200 : null;
};
const alivePid = () => true;
const deadPid = () => false;

/* ── [A7] the negative fixture: agent-spawn-shaped record in foreign/ → unreadable ─────────── */

await check('an agent-spawn-shaped (portless) record in foreign/ is read as ok:false, not accepted', async () => {
  const dir = await makeRegisterDir({ 'ui-shot-9001.json': AGENT_SPAWN_SHAPED_REC() });
  const entries = await readForeignRegister({ dir });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].ok, false, 'a portless, wrong-scope-shaped record must not read as ok:true');
  assert.match(entries[0].reason, /ports\.api/, 'the rejection must name what is missing, not just fail silently');
});

await check('probeForeignRuns resolves the same agent-spawn-shaped record to state:"unreadable"', async () => {
  const registerDir = await makeRegisterDir({ 'ui-shot-9001.json': AGENT_SPAWN_SHAPED_REC() });
  const runs = await probeForeignRuns({
    enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(),
  });
  assert.equal(runs.length, 1);
  assert.equal(runs[0].state, 'unreadable', 'an agent-spawn-shaped record must never resolve to a live/unreachable/stale verdict');
  assert.notEqual(runs[0].state, 'live');
  assert.equal(runs[0].source, 'registered');
  assert.equal(runs[0].port, null);
  assert.ok(runs[0].reason, 'the unreadable verdict must say why');
});

await check('a second, wrong-schema-version agent-spawn record beside a healthy one is unreadable without suppressing its neighbour', async () => {
  const registerDir = await makeRegisterDir({
    'ui-shot-9001.json': AGENT_SPAWN_SHAPED_REC(),
    'jseval-4242.json': FOREIGN_REC(),
  });
  const runs = await probeForeignRuns({
    enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
  });
  assert.equal(runs.length, 2);
  assert.equal(runs.filter((r) => r.state === 'unreadable').length, 1);
  assert.equal(runs.filter((r) => r.state === 'live').length, 1);
});

/* ── validation stayed per-scope, not in the shared envelope ────────────────────────────────── */
//
// The strongest form of the [A7] proof: the GENERIC reader, with NO validator injected, accepts
// the exact same agent-spawn-shaped record that `foreign/`'s OWN reader rejects. If this were not
// true — if the shared envelope itself understood "ports.api is required" — the sibling-scope
// decision (861 §6.1) would already be undone in the implementation while the design still reads
// as if the two scopes were separate.

await check('the GENERIC readRegister (no validator) accepts a portless record foreign/\'s reader rejects', async () => {
  const dir = await makeRegisterDir({ 'ui-shot-9001.json': AGENT_SPAWN_SHAPED_REC() });
  const generic = await readRegister({ dir });
  assert.equal(generic.length, 1);
  assert.equal(generic[0].ok, true, 'the shared envelope must not itself know what a foreign/ record requires');
  assert.equal(generic[0].record.producer, 'ui-shot');

  const scoped = await readForeignRegister({ dir });
  assert.equal(scoped.length, 1);
  assert.equal(scoped[0].ok, false, 'the SAME file, read by foreign/\'s scoped reader, is rejected');
});

await check('a symlink-shaped *.json entry is surfaced as unreadable instead of silently skipped', async () => {
  const dir = await makeRegisterDir({});
  const linkTarget = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-861-w1-link-target-'));
  dirsToClean.push(linkTarget);
  await fsp.symlink(linkTarget, path.join(dir, 'redirect.json'), process.platform === 'win32' ? 'junction' : 'dir');
  const entries = await readRegister({ dir });
  assert.equal(entries.length, 1, 'a *.json symlink must remain visible to safety callers');
  assert.equal(entries[0].ok, false);
  assert.equal(entries[0].recordId, 'redirect');
  assert.match(entries[0].reason, /symlink/);
});

await check('validateForeignRecord is the sole source of foreign/\'s ports.api requirement', () => {
  assert.equal(validateForeignRecord(AGENT_SPAWN_SHAPED_REC()).ok, false);
  assert.equal(validateForeignRecord(FOREIGN_REC()).ok, true);
  assert.equal(validateForeignRecord({ schemaVersion: 1, ports: { api: 4242 } }).ok, true);
  assert.equal(validateForeignRecord({ schemaVersion: 2, ports: { api: 4242 } }).ok, false, 'a future schema version is rejected, not guessed at');
});

/* ── byte-identical proof: today's well-formed foreign/ records, unchanged ──────────────────── */

await check('a well-formed foreign/ record still resolves byte-identically through the extracted reader', async () => {
  const registerDir = await makeRegisterDir({ 'jseval-4242.json': FOREIGN_REC() });
  const runs = await probeForeignRuns({
    enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
  });
  assert.equal(runs.length, 1);
  assert.deepEqual(runs[0], {
    port: 33221,
    kind: 'backend',
    probePath: '/api/status',
    attribution: 'unowned',
    source: 'registered',
    state: 'live',
    liveness: { portAnswered: true, pidAlive: true },
    recordId: 'jseval-4242',
    recordFile: 'tmp/dev-runner/foreign/jseval-4242.json',
    producer: 'jseval',
    pid: 4242,
    repoRoot: 'F:\\justsearch-public\\.claude\\worktrees\\round14',
    dataDir: 'F:\\justsearch-public\\tmp\\headless-eval-data',
    workload: 'eval-backend',
    inferenceRequested: false,
    gpuBound: 'unverified',
    sessionId: 'b73007cd-907a-48da-8d74-2379da83be8f',
    startedAt: '2026-08-19T09:00:00Z',
  });
});

await check('a stale foreign/ record (dead pid, silent port) is still "stale", never "live", and still reported', async () => {
  const registerDir = await makeRegisterDir({ 'jseval-4242.json': FOREIGN_REC() });
  const runs = await probeForeignRuns({
    enabled: true, hasActiveRun: false, registerDir, isPidAlive: deadPid, probe: listening(),
  });
  assert.equal(runs[0].state, 'stale');
  assert.deepEqual(runs[0].liveness, { portAnswered: false, pidAlive: false });
  const stillOnDisk = await fsp.readdir(registerDir);
  assert.deepEqual(stillOnDisk, ['jseval-4242.json'], 'reading must never delete another lifecycle\'s record');
});

await check('an unregistered but reachable port still surfaces as observed, unchanged', async () => {
  const runs = await probeForeignRuns({ enabled: true, hasActiveRun: false, probe: listening(33221) });
  assert.deepEqual(runs, [{ port: 33221, kind: 'backend', probePath: '/api/status', attribution: 'unowned', source: 'observed' }]);
});

await check('probing off still yields null (did not look), never [] — the tri-state survived extraction', async () => {
  const registerDir = await makeRegisterDir({ 'jseval-4242.json': FOREIGN_REC() });
  const runs = await probeForeignRuns({ enabled: false, hasActiveRun: false, registerDir, probe: listening(33221) });
  assert.equal(runs, null);
});

/* ── deriveLivenessState — the extracted state-vocabulary helper, in isolation ──────────────── */

await check('deriveLivenessState: live when the port answers, regardless of pid', () => {
  assert.deepEqual(deriveLivenessState({ portAnswered: true, pidAlive: true }), { state: 'live' });
});

await check('deriveLivenessState: a live port with a dead recorded pid is flagged identityStale', () => {
  const r = deriveLivenessState({ portAnswered: true, pidAlive: false });
  assert.equal(r.state, 'live');
  assert.equal(r.identityStale, true);
});

await check('deriveLivenessState: unreachable when the port is silent but the pid lives', () => {
  assert.deepEqual(deriveLivenessState({ portAnswered: false, pidAlive: true }), { state: 'unreachable' });
});

await check('deriveLivenessState: stale when the port is silent and the pid is dead', () => {
  assert.deepEqual(deriveLivenessState({ portAnswered: false, pidAlive: false }), { state: 'stale' });
});

/* ── [A9] directory resolution honours JUSTSEARCH_DEV_RUNNER_STATE_ROOT ─────────────────────── */

await check('resolveRegisterDir (generic) honours the state-root override', () => {
  const isolated = path.join(os.tmpdir(), 'jsdev-861-w1-stateroot');
  assert.equal(
    resolveRegisterDir('F:/main', 'foreign', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: isolated }),
    path.join(path.resolve(isolated), 'foreign'),
  );
  assert.equal(
    resolveRegisterDir('F:/main', 'foreign', {}),
    path.join('F:/main', 'tmp', 'dev-runner', 'foreign'),
  );
});

await check('resolveForeignRegisterDir delegates to the generic resolver with dirName="foreign"', () => {
  const isolated = path.join(os.tmpdir(), 'jsdev-861-w1-stateroot2');
  assert.equal(
    resolveForeignRegisterDir('F:/main', { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: isolated }),
    resolveRegisterDir('F:/main', FOREIGN_REGISTER_DIRNAME, { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: isolated }),
  );
  assert.equal(resolveForeignRegisterDir('F:/main', {}), path.join('F:/main', ...FOREIGN_REGISTER_RELPOSIX.split('/')));
});

/* ── generic primitives, sanity ───────────────────────────────────────────────────────────── */

await check('pidAlive is false for pid 0 / non-numeric input, without throwing', () => {
  assert.equal(pidAlive(0), false);
  assert.equal(pidAlive(null), false);
  assert.equal(pidAlive('4242'), false);
});

await check('httpGetStatusCode resolves null (not a throw) for an unparsable URL', async () => {
  assert.equal(await httpGetStatusCode('not a url', 200), null);
});

await check('FOREIGN_RECORD_SCHEMA_VERSION stays 1 and FOREIGN_BACKEND_PORTS still names 33221 ([A8])', () => {
  assert.equal(FOREIGN_RECORD_SCHEMA_VERSION, 1);
  assert.ok(FOREIGN_BACKEND_PORTS.includes(33221));
});

/* ── server.mjs's own consumption of the shared module ──────────────────────────────────────── */

await check('server.mjs re-exports the SAME probeForeignRuns behaviour as the shared module directly', async () => {
  const registerDir = await makeRegisterDir({ 'jseval-4242.json': FOREIGN_REC() });
  const viaShared = await probeForeignRuns({
    enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
  });
  const viaServer = await serverMjs.probeForeignRuns({
    enabled: true, hasActiveRun: false, registerDir, isPidAlive: alivePid, probe: listening(33221),
  });
  assert.deepEqual(viaServer, viaShared, 'server.mjs must delegate to the shared module, not a fork of it');
});

await check('server.mjs re-exports readForeignRegister, and it rejects the same negative fixture', async () => {
  const dir = await makeRegisterDir({ 'ui-shot-9001.json': AGENT_SPAWN_SHAPED_REC() });
  const entries = await serverMjs.readForeignRegister({ dir });
  assert.equal(entries[0].ok, false);
});

await check('server.mjs re-exports resolveForeignRegisterDir, FOREIGN_BACKEND_PORTS, FOREIGN_REGISTER_RELPOSIX', () => {
  assert.equal(typeof serverMjs.resolveForeignRegisterDir, 'function');
  assert.deepEqual(serverMjs.FOREIGN_BACKEND_PORTS, FOREIGN_BACKEND_PORTS);
  assert.equal(serverMjs.FOREIGN_REGISTER_RELPOSIX, FOREIGN_REGISTER_RELPOSIX);
  assert.equal(serverMjs.resolveForeignRegisterDir('F:/main', {}), resolveForeignRegisterDir('F:/main', {}));
});

/* ── cleanup + report ─────────────────────────────────────────────────────────────────────── */

for (const dir of dirsToClean) await fsp.rm(dir, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`861-w1-process-record.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w1-process-record.test: all ${passed} checks passed`);
