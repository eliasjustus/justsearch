/**
 * Tempdoc 520 P1b — unit tests for intervene's decision logic: the hot-file
 * cap (previously untested) and large-file limit injection.
 *
 * Run with: `node scripts/agent-analytics/hooks/intervene.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { shouldBlockHotFile, shouldInjectLimit } from './intervene.mjs';

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

// --- shouldBlockHotFile: unbounded re-reads gated at the cap ---
run('below cap → allow', () => assert.equal(shouldBlockHotFile(9, true), false));
run('at cap → block', () => assert.equal(shouldBlockHotFile(10, true), true));
run('over cap → block', () => assert.equal(shouldBlockHotFile(15, true), true));
run('targeted read never blocks, even over cap', () => assert.equal(shouldBlockHotFile(50, false), false));
run('custom cap respected', () => assert.equal(shouldBlockHotFile(3, true, 3), true));

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'intervene-test-'));
try {
  // --- shouldInjectLimit: large unbounded reads get a limit; small/targeted don't ---
  run('large file with no offset/limit → inject limit 200', () => {
    const big = path.join(tmpDir, 'big.txt');
    fs.writeFileSync(big, 'x'.repeat(20_000));
    const r = shouldInjectLimit({ file_path: big });
    assert.ok(r && r.updatedInput.limit === 200, 'expected injection with limit 200');
  });
  run('small file → no injection', () => {
    const small = path.join(tmpDir, 'small.txt');
    fs.writeFileSync(small, 'tiny');
    assert.equal(shouldInjectLimit({ file_path: small }), null);
  });
  run('large file but caller already set limit → no injection', () => {
    const big = path.join(tmpDir, 'big2.txt');
    fs.writeFileSync(big, 'x'.repeat(20_000));
    assert.equal(shouldInjectLimit({ file_path: big, limit: 50 }), null);
  });
  run('missing file_path → no injection', () => {
    assert.equal(shouldInjectLimit({}), null);
  });
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`intervene.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`intervene.test: all ${passed} checks passed`);
