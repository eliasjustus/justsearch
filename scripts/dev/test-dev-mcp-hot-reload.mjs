#!/usr/bin/env node
//
// Tempdoc 844 §4.2 — unit tests for the hot-reload repair, under §12.2's criterion:
// "a dev tool must not report state it did not verify, and must not report success it did not
// confirm; where it cannot verify something, it says so."
//
// Covered:
//   R1  resolveReloadTarget        — the compile root comes from the RUN RECORD, not the caller's
//                                    cwd; an unresolvable run tree FAILS instead of falling back.
//   R2  checkRunMutationOwnership  — a non-owner is refused with OWNER_CONFLICT in the existing
//                                    verdict vocabulary; the owner proceeds.
//   R3  classifyHotSwapOutcome     — an identity refusal, and an unverified push, are not success.
//   R5  classifyHotSwapOutcome     — exit 0 with zero classes redefined is NOT success; "nothing
//                                    changed" and "changed but nothing landed" are distinct.
//   R5  signal gating              — source-structural (labelled): the reload signal write is
//                                    gated on hotSwapOk, and every non-REDEFINED outcome is
//                                    hotSwapOk:false, so a failed push cannot reconstruct services.
//
// Pure unit tests: no dev stack, no JVM, no network. The two async helpers touch only a tmp
// fixture tree.
//
// Run: node scripts/dev/test-dev-mcp-hot-reload.mjs
//

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  checkRunMutationOwnership,
  classifyHotSwapOutcome,
  resolveReloadTarget,
} from './justsearch-dev-mcp/server.mjs';

// server.mjs installs process-level handlers that LOG rather than exit, so a top-level abort here
// would otherwise leave exit code 0 — a green that ran nothing. Fail closed until the runner clears it.
process.exitCode = 1;

const HERE = import.meta.dirname;

/* ── fixture: a main repo with two worktrees, the shape 125 of 162 starts ran in ───────────── */

async function makeFixture() {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), 'jsdev-844-hr-'));
  const main = path.join(root, 'main');
  for (const dir of [main, path.join(main, '.claude', 'worktrees', 'tree-a'), path.join(main, '.claude', 'worktrees', 'tree-b')]) {
    await fsp.mkdir(path.join(dir, 'scripts', 'dev'), { recursive: true });
    await fsp.writeFile(path.join(dir, 'scripts', 'dev', 'dev-runner.cjs'), '// fixture\n', 'utf8');
  }
  return { root, main, treeA: path.join(main, '.claude', 'worktrees', 'tree-a'), treeB: path.join(main, '.claude', 'worktrees', 'tree-b') };
}

const fx = await makeFixture();
const posix = (p) => p.replace(/\\/g, '/');

/** A run record as dev-runner.cjs writes it, launched from `tree`. */
const runRecord = (tree, over = {}) => ({
  schemaVersion: 1,
  runId: 'run-1',
  repoRoot: posix(tree),
  dataDir: posix(path.join(fx.root, 'data')),
  pids: { runnerPid: process.pid },
  hotReload: {
    enabled: true,
    debugPort: 5011,
    classesDir: posix(path.join(tree, 'modules', 'worker-services', 'build', 'classes', 'java', 'main')),
  },
  ...over,
});

/* ── R1: the compile root is the run's tree, not the caller's ──────────────────────────────── */

const targetTests = [
  ['the compile root is the RUN RECORD\'s tree, not the caller\'s cwd (§5.6 case (c))', async () => {
    // The caller (this process) is nowhere near tree-a; the run was launched from tree-a.
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(fx.treeA) });
    assert.equal(r.ok, true);
    assert.equal(r.runRoot, fx.treeA);
    assert.notEqual(r.runRoot, process.cwd());
    assert.notEqual(r.runRoot, fx.treeB);
  }],
  ['the per-run JDWP port and identity token come from the record, not a hardcoded 5005', async () => {
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(fx.treeA) });
    assert.equal(r.debugPort, 5011);
    assert.match(r.identityClassesDir, /tree-a/);
    assert.match(r.identityClassesDir, /worker-services/);
  }],
  ['a run launched from the main repo resolves to the main repo', async () => {
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(fx.main) });
    assert.equal(r.ok, true);
    assert.equal(r.runRoot, fx.main);
  }],
  ['a run record with no repoRoot FAILS — it does not fall back to the caller\'s tree', async () => {
    const rec = runRecord(fx.treeA);
    delete rec.repoRoot;
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: rec });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'RUN_ROOT_UNRESOLVED');
    assert.match(r.error.message, /Refusing to guess/);
  }],
  ['a run whose tree no longer exists FAILS, naming the tree', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(path.join(fx.main, '.claude', 'worktrees', 'removed-tree')),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'RUN_ROOT_UNRESOLVED');
    assert.match(r.error.message, /removed-tree/);
  }],
  ['a run record pointing outside the repo is refused (not silently accepted)', async () => {
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: runRecord(path.join(fx.root, 'elsewhere')) });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'RUN_ROOT_UNRESOLVED');
  }],
  ['hotReload: false is a recorded fact, and reload says so instead of pushing at 5005', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(fx.treeA, { hotReload: { enabled: false, debugPort: null, classesDir: null } }),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
    assert.match(r.error.message, /no JDWP listener/);
  }],
  ['a pre-844 run record (no hotReload block) is an explicit unknown, not an assumed 5005', async () => {
    const rec = runRecord(fx.treeA);
    delete rec.hotReload;
    const r = await resolveReloadTarget({ mainRepoRoot: fx.main, runJson: rec });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
    assert.match(r.error.message, /5005/);
  }],
  ['hot reload on but no recorded port is refused rather than defaulted', async () => {
    const r = await resolveReloadTarget({
      mainRepoRoot: fx.main,
      runJson: runRecord(fx.treeA, { hotReload: { enabled: true, debugPort: null, classesDir: null } }),
    });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, 'HOT_RELOAD_NOT_ENABLED');
  }],
];

/* ── R2: reload mutates a run, so it is ownership-gated like start/stop ────────────────────── */

const OWNER = 'session-owner-A';
const activeRecord = (over = {}) => ({
  kind: 'backend-shared-lease.v1',
  runId: 'run-1',
  holder: { source: 'test', agentSessionId: OWNER },
  takeoverPolicy: 'warn',
  ownershipEpoch: 3,
  lease: { durationSec: 60, expiresAt: new Date(Date.now() + 60_000).toISOString(), sequence: 1 },
  ...over,
});

const ownershipArgs = (over = {}) => ({
  mainRepoRoot: fx.main,
  callerRepoRoot: fx.treeB,
  callerSessionId: 'session-intruder-B',
  takeover: 'deny',
  active: activeRecord(),
  runJson: runRecord(fx.treeA),
  tool: 'reload',
  ...over,
});

const ownershipTests = [
  ['a NON-OWNER is refused — bytecode cannot be pushed into a peer\'s stack silently', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs());
    assert.equal(g.allowed, false);
    assert.equal(g.refusal.error.code, 'OWNER_CONFLICT');
    assert.equal(g.decision.verdict, 'CONTENTION');
    assert.match(g.refusal.error.message, /reload mutates the running stack/);
    assert.equal(g.refusal.ownership.holder.agentSessionId, OWNER);
  }],
  ['the refusal says the takeover does not transfer the lease', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs());
    assert.match(g.refusal.actionRequired, /does not transfer the lease/);
    assert.match(g.refusal.actionRequired, /takeover/);
  }],
  ['the OWNER proceeds', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({ callerSessionId: OWNER }));
    assert.equal(g.allowed, true);
    assert.equal(g.decision.verdict, 'USE');
  }],
  ['an authorized takeover:"force" proceeds — the vocabulary is the existing one', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({ takeover: 'force' }));
    assert.equal(g.allowed, true);
    assert.equal(g.decision.verdict, 'CONTENTION');
  }],
  ['a dead supervisor is reclaimable, not a permanent refusal', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({
      runJson: runRecord(fx.treeA, { pids: { runnerPid: 0 } }),
    }));
    assert.equal(g.allowed, true);
    assert.equal(g.decision.verdict, 'RECLAIM_DEAD');
  }],
  ['no holder at all → no refusal to invent', async () => {
    const g = await checkRunMutationOwnership(ownershipArgs({ active: { runId: 'run-1' } }));
    assert.equal(g.allowed, true);
    assert.equal(g.ownership, null);
  }],
];

/* ── R3/R5: what the push actually did ─────────────────────────────────────────────────────── */

const REDEFINED_OK = { exitCode: 0, stdout: 'CHANGED 2\nIDENTITY_OK F:/t/classes\nREDEFINED 2\nNOT_LOADED 0\n' };

const outcomeTests = [
  ['a confirmed push of 2 classes is success, with the counts reported', () => {
    const o = classifyHotSwapOutcome(REDEFINED_OK);
    assert.equal(o.hotSwapOk, true);
    assert.equal(o.outcome, 'REDEFINED');
    assert.equal(o.classesRedefined, 2);
    assert.equal(o.classesChanged, 2);
    assert.equal(o.identityVerified, true);
  }],
  ['§5.6 #4: exit 0 with ZERO classes redefined is NOT success', () => {
    const o = classifyHotSwapOutcome({ exitCode: 0, stdout: 'CHANGED 3\nIDENTITY_OK F:/t/classes\nREDEFINED 0\nNOT_LOADED 3\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.outcome, 'NO_CLASSES_REDEFINED');
    assert.equal(o.error.code, 'NO_CLASSES_REDEFINED');
    assert.equal(o.classesRedefined, 0);
  }],
  ['the pusher\'s own exit 4 ("none loaded") is a failure, and names the count', () => {
    const o = classifyHotSwapOutcome({ exitCode: 4, stdout: 'CHANGED 3\nIDENTITY_OK F:/t/classes\nREDEFINED 0\nNOT_LOADED 3\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'NO_CLASSES_REDEFINED');
    assert.match(o.error.message, /3 changed class file\(s\)/);
    assert.match(o.error.message, /services were NOT reconstructed/);
  }],
  ['"no class changed since the last push" is a no-op, distinct from a failed push', () => {
    const o = classifyHotSwapOutcome({ exitCode: 3, stdout: 'CHANGED 0\nNo changed classes since last push.\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.noOp, true);
    assert.equal(o.outcome, 'NOTHING_CHANGED');
    assert.equal(o.error, undefined);
  }],
  ['R3: an identity refusal reports the cross-tree case, not a generic failure', () => {
    const o = classifyHotSwapOutcome({ exitCode: 5, stderr: 'IDENTITY_REFUSED the VM on port 5011 was NOT launched from the tree this push comes from.' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'TARGET_IDENTITY_MISMATCH');
    assert.equal(o.identityVerified, false);
    assert.match(o.error.message, /cross-tree/);
  }],
  ['R3: exit 0 without an IDENTITY_OK line is unverified, not success (guards an OLD pusher copy)', () => {
    const o = classifyHotSwapOutcome({ exitCode: 0, stdout: 'CHANGED 2\nRedefined 2 class(es):\nREDEFINED 2\n' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.outcome, 'IDENTITY_UNVERIFIED');
    assert.equal(o.error.code, 'TARGET_IDENTITY_UNVERIFIED');
  }],
  ['with no identity token to check, the same output is accepted (and identity is not claimed)', () => {
    const o = classifyHotSwapOutcome({ exitCode: 0, stdout: 'CHANGED 2\nREDEFINED 2\n', identityRequired: false });
    assert.equal(o.hotSwapOk, true);
    assert.equal(o.identityVerified, false);
  }],
  ['a structural change is reported as such (R7: reported, never staged)', () => {
    const o = classifyHotSwapOutcome({ exitCode: 1, stderr: 'HotSwap failed: ...\nIf you added/removed methods or fields, standard HotSwap cannot apply them' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'STRUCTURAL_CHANGE');
    assert.equal(o.structuralChangeDetected, true);
  }],
  ['a spawn failure (no numeric exit code) is a failure, not an unknown-shaped success', () => {
    const o = classifyHotSwapOutcome({ exitCode: -1, stderr: 'ENOENT' });
    assert.equal(o.hotSwapOk, false);
    assert.equal(o.error.code, 'HOTSWAP_FAILED');
  }],
  ['every non-REDEFINED outcome is hotSwapOk:false — the property the signal gate rests on', () => {
    const cases = [
      { exitCode: 3 }, { exitCode: 4 }, { exitCode: 5 }, { exitCode: 1 }, { exitCode: -1 },
      { exitCode: 0, stdout: 'REDEFINED 0\nIDENTITY_OK x\n' },
      { exitCode: 0, stdout: 'REDEFINED 2\n' },
    ];
    for (const c of cases) {
      assert.equal(classifyHotSwapOutcome(c).hotSwapOk, false, `expected not-ok for ${JSON.stringify(c)}`);
    }
    assert.equal(classifyHotSwapOutcome(REDEFINED_OK).hotSwapOk, true);
  }],
];

/* ── R5: the signal gate itself (source-structural — labelled, not disguised) ───────────────
 * The write is three lines inside the tool handler, so this asserts on the handler's SOURCE
 * rather than pretending to exercise it. What IS exercised is the property it depends on: the
 * outcome tests above prove hotSwapOk is false for every outcome except a confirmed redefinition.
 */
const signalGateTests = [
  ['the reload signal write is gated on hotSwapOk (was: gated only on signalFile)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /if \(result\.hotSwapOk && signalFile\) \{/);
    assert.doesNotMatch(src, /Continue to signal write so service reconstruction still happens/);
  }],
  ['a skipped signal is stated, not silent', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /signalSkippedReason/);
    assert.match(src, /services were NOT reconstructed/);
  }],
  ['the build stamp is copied from the RUN\'s tree, never the caller\'s (§5.6 #2)', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /const stampPath = path\.join\(runRoot,/);
  }],
  ['the pusher is invoked with the recorded identity token when the run has one', async () => {
    const src = await fsp.readFile(path.join(HERE, 'justsearch-dev-mcp', 'server.mjs'), 'utf8');
    assert.match(src, /if \(identityClassesDir\) hsArgs\.push\(identityClassesDir\)/);
  }],
  ['HotSwapPush refuses a VM whose classpath does not carry the identity entry', async () => {
    const src = await fsp.readFile(path.join(HERE, 'HotSwapPush.java'), 'utf8');
    assert.match(src, /PathSearchingVirtualMachine psvm/);
    assert.match(src, /IDENTITY_REFUSED/);
    // …and the marker is only touched when bytecode actually moved (§5.6 #4).
    assert.match(src, /if \(exitCode == 0\) \{\s*\n\s*Files\.writeString\(markerFile/);
  }],
];

/* ── runner ────────────────────────────────────────────────────────────────────────────────── */

let passed = 0;
let failed = 0;
for (const [name, fn] of [...targetTests, ...ownershipTests, ...outcomeTests, ...signalGateTests]) {
  try {
    await fn();
    passed += 1;
    console.log(`  ok   ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`  FAIL ${name}\n       ${err.message}`);
  }
}
await fsp.rm(fx.root, { recursive: true, force: true });
console.log(`\n${passed} passed, ${failed} failed`);
process.exitCode = failed === 0 ? 0 : 1;
