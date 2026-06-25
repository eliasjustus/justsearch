/**
 * Tempdoc 520 P1a/P1c — unit tests for the shared hook-base utilities.
 *
 * Run with: `node scripts/agent-analytics/lib/hook-base.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  atomicWriteFileSync,
  describeHookFailure,
  hookIdFromUrl,
  hookRoleFromManifest,
  hooksDisabled,
  isDirectRun,
  repoRoot,
  telemetryDir,
} from './hook-base.mjs';

let passed = 0;
const failures = [];

function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hook-base-test-'));

try {
  // --- atomicWriteFileSync ---
  run('atomic write produces final file with exact content', () => {
    const target = path.join(tmpDir, 'a.json');
    atomicWriteFileSync(target, 'hello');
    assert.equal(fs.readFileSync(target, 'utf8'), 'hello');
  });
  run('atomic write leaves no .tmp residue', () => {
    const target = path.join(tmpDir, 'b.json');
    atomicWriteFileSync(target, 'x');
    const residue = fs.readdirSync(tmpDir).filter((f) => f.includes('b.json') && f.endsWith('.tmp'));
    assert.deepEqual(residue, []);
  });
  run('atomic write overwrites existing', () => {
    const target = path.join(tmpDir, 'c.json');
    atomicWriteFileSync(target, '1');
    atomicWriteFileSync(target, '2');
    assert.equal(fs.readFileSync(target, 'utf8'), '2');
  });

  // --- hooksDisabled (P1c) ---
  run('hooksDisabled false by default', () => {
    delete process.env.JUSTSEARCH_DISABLE_HOOKS;
    assert.equal(hooksDisabled(), false);
  });
  run('hooksDisabled true when env=1', () => {
    process.env.JUSTSEARCH_DISABLE_HOOKS = '1';
    assert.equal(hooksDisabled(), true);
    delete process.env.JUSTSEARCH_DISABLE_HOOKS;
  });
  run('hooksDisabled false for other env values', () => {
    process.env.JUSTSEARCH_DISABLE_HOOKS = 'true';
    assert.equal(hooksDisabled(), false);
    delete process.env.JUSTSEARCH_DISABLE_HOOKS;
  });

  // --- isDirectRun ---
  run('isDirectRun false for a url that is not argv[1] (imported module)', () => {
    assert.equal(isDirectRun('file:///some/other/module.mjs'), false);
  });
  run('isDirectRun true for the directly-run file', () => {
    // This test file is the directly-run entry (argv[1]).
    assert.equal(isDirectRun(import.meta.url), true);
  });

  // --- path resolution ---
  run('repoRoot points at a real repo (has scripts/agent-analytics)', () => {
    assert.ok(fs.existsSync(path.join(repoRoot, 'scripts', 'agent-analytics', 'lib', 'hook-base.mjs')));
  });
  run('telemetryDir is repoRoot/tmp/agent-telemetry', () => {
    assert.equal(telemetryDir, path.join(repoRoot, 'tmp', 'agent-telemetry'));
  });

  // --- 592: no-silent-downgrade failure contract ---
  run('hookIdFromUrl extracts the hook id from a module url', () => {
    assert.equal(hookIdFromUrl('file:///x/scripts/agent-analytics/hooks/bash-guard.mjs'), 'bash-guard');
  });
  run('describeHookFailure: blocking hook gets a loud, attributed line', () => {
    const { event, loud } = describeHookFailure({ id: 'bash-guard', role: 'blocking', message: 'boom' });
    assert.equal(event.event, 'hook_failure');
    assert.equal(event.hookId, 'bash-guard');
    assert.equal(event.role, 'blocking');
    assert.equal(event.phase, 'run');
    assert.ok(typeof loud === 'string' && loud.includes('bash-guard') && loud.toLowerCase().includes('off'));
  });
  run('describeHookFailure: advisory hook stays quiet (loud=null) but still records telemetry', () => {
    const { event, loud } = describeHookFailure({ id: 'ssot-hint', role: 'advisory', message: 'boom' });
    assert.equal(loud, null);
    assert.equal(event.event, 'hook_failure');
    assert.equal(event.role, 'advisory');
  });
  run('hookRoleFromManifest reads role from an injected manifest; defaults to advisory', () => {
    const mp = path.join(tmpDir, 'manifest.json');
    fs.writeFileSync(mp, JSON.stringify({ hooks: { 'bash-guard': { role: 'blocking' } } }));
    assert.equal(hookRoleFromManifest('bash-guard', mp), 'blocking');
    assert.equal(hookRoleFromManifest('not-present', mp), 'advisory');
    assert.equal(hookRoleFromManifest('x', path.join(tmpDir, 'nope.json')), 'advisory');
  });
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`hook-base.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`hook-base.test: all ${passed} checks passed`);
