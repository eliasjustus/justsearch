/**
 * Tests for the realized-capability gate (tempdoc 644): registered surfaces must project per-engine
 * realized state from `computeRealized` by reading `aiState.realized`, so they cannot re-fork the read.
 *
 * Run: `node scripts/ci/check-realized-capability.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { stripComments, consumesField, checkCoverage } from './check-realized-capability.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

// --- comment stripping + field-consumption detection ---
ok(
  'a doc-comment mention of the field does NOT count as consuming it',
  !consumesField('// reads aiState.realized for engines\nconst a = 1;', 'realized'),
);
ok(
  'a real field read counts as consuming the projection',
  consumesField('const r = this.aiState?.realized;', 'realized'),
);
ok(
  'stripComments removes // and /* */ comments',
  (() => {
    const s = stripComments('a /* .realized */ b // .realized\nc');
    return !s.includes('.realized') && s.includes('a') && s.includes('c');
  })(),
);

// --- coverage check ---
const reader = (map) => (f) => {
  if (!(f in map)) throw new Error('missing');
  return map[f];
};
ok(
  'PASS: every registered surface reads the projected field',
  checkCoverage({
    surfaces: ['a.ts', 'b.ts'],
    field: 'realized',
    readFile: reader({
      'a.ts': 'const r = s.realized;\nif (r.reranker.loaded) {}',
      'b.ts': 'render() { return this.aiState?.realized?.embed; }',
    }),
  }).length === 0,
);
ok(
  'FORK fail: a registered surface that re-reads raw worker.gpu instead of the projection',
  (() => {
    const f = checkCoverage({
      surfaces: ['fork.ts'],
      field: 'realized',
      readFile: reader({ 'fork.ts': 'const gpu = status.worker.gpu.rerankerOrtCuda.available;' }),
    });
    return f.length === 1 && f[0].startsWith('fork:') && f[0].includes('fork.ts');
  })(),
);
ok(
  'unresolved fail: a registered surface path that does not exist',
  (() => {
    const f = checkCoverage({
      surfaces: ['gone.ts'],
      field: 'realized',
      readFile: reader({}),
    });
    return f.length === 1 && f[0].startsWith('unresolved:');
  })(),
);

if (failures.length > 0) {
  console.error(`✗ check-realized-capability.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-realized-capability.test OK (${passed} assertions)`);
