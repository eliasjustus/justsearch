/**
 * Tempdoc 743 second wave, P-K — unit tests for run-py.mjs's pure logic + a live spawn check.
 *
 * Run with: `node scripts/dev/run-py.test.mjs`
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { resolvePyBin, buildUtf8Env } from './run-py.mjs';

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

run('resolvePyBin defaults to plain "python"', () => {
  assert.equal(resolvePyBin({}), 'python');
});
run('resolvePyBin honors JUSTSEARCH_PY_BIN override', () => {
  assert.equal(resolvePyBin({ JUSTSEARCH_PY_BIN: 'C:\\custom\\python.exe' }), 'C:\\custom\\python.exe');
});

run('buildUtf8Env sets PYTHONIOENCODING and PYTHONUTF8 scoped over the base env', () => {
  const env = buildUtf8Env({ PATH: '/usr/bin', SOME_OTHER_VAR: 'x' });
  assert.equal(env.PYTHONIOENCODING, 'utf-8');
  assert.equal(env.PYTHONUTF8, '1');
  assert.equal(env.PATH, '/usr/bin'); // base env preserved, not clobbered
  assert.equal(env.SOME_OTHER_VAR, 'x');
});
run('buildUtf8Env does not mutate the base env object', () => {
  const base = { FOO: 'bar' };
  buildUtf8Env(base);
  assert.deepEqual(base, { FOO: 'bar' });
});

// --- emit-shape contract: the CLI forwards argv as a vector and exits with the child's code ---
const CLI = resolve(dirname(fileURLToPath(import.meta.url)), 'run-py.mjs');
run('CLI forwards -c code to python and exits 0 on success', () => {
  const out = execFileSync('node', [CLI, '-c', 'print(1 + 1)'], { encoding: 'utf8' });
  assert.equal(out.trim(), '2');
});
run('CLI propagates a non-zero python exit code', () => {
  let threw = false;
  try {
    execFileSync('node', [CLI, '-c', 'import sys; sys.exit(7)'], { encoding: 'utf8' });
  } catch (e) {
    threw = true;
    assert.equal(e.status, 7);
  }
  assert.ok(threw, 'expected the CLI to exit non-zero');
});

// --- Report ---
if (failures.length > 0) {
  console.error(`run-py.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`run-py.test: all ${passed} checks passed`);
