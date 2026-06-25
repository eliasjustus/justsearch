/**
 * Unit tests for the ssot-catalog-sync verdict table.
 *
 * Run with: `node scripts/governance/gates/ssot-catalog-sync/truth-table.test.mjs`
 */

import assert from 'node:assert/strict';
import { verdictForMirror, verdictForMirrorRemoval } from './truth-table.mjs';
import { indexClassificationsByMirror } from './classifications.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const base = { id: 'm', rootExists: true, copyExists: true, equal: true, classification: null };

run('in sync → pass', () => assert.equal(verdictForMirror(base).ruleId, 'ssot-catalog-sync/in-sync'));
run('drift → fail', () => {
  const v = verdictForMirror({ ...base, equal: false });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'ssot-catalog-sync/drift');
});
run('drift with intentional-divergence → pass', () => {
  assert.equal(verdictForMirror({ ...base, equal: false, classification: 'intentional-divergence' }).status, 'pass');
});
run('copy missing → fail', () => {
  const v = verdictForMirror({ ...base, copyExists: false, equal: false });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'ssot-catalog-sync/copy-missing');
});
run('root missing → fail', () => {
  assert.equal(verdictForMirror({ ...base, rootExists: false, equal: false }).ruleId, 'ssot-catalog-sync/copy-missing');
});
run('missing with emergency-override → pass', () => {
  assert.equal(verdictForMirror({ ...base, copyExists: false, equal: false, classification: 'emergency-override' }).status, 'pass');
});

run('mirror removed without changeset → fail', () => {
  const v = verdictForMirrorRemoval({ id: 'm', classification: null });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'ssot-catalog-sync/silent-mirror-removal');
});
run('mirror removed via mirror-retirement → pass', () => {
  assert.equal(verdictForMirrorRemoval({ id: 'm', classification: 'mirror-retirement' }).status, 'pass');
});

run('indexClassificationsByMirror maps by mirror frontmatter', () => {
  const m = indexClassificationsByMirror([{ classification: 'intentional-divergence', frontmatter: { mirror: 'fields' } }]);
  assert.equal(m.get('fields'), 'intentional-divergence');
});
run('ignores declarations with no mirror field', () => {
  assert.equal(indexClassificationsByMirror([{ classification: 'emergency-override', frontmatter: {} }]).size, 0);
});

if (failures.length > 0) {
  console.error(`ssot-catalog-sync truth-table.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`ssot-catalog-sync truth-table.test: all ${passed} checks passed`);
