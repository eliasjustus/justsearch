/**
 * Tempdoc 531 — unit tests for the consumer-drift verdict table + the
 * per-slot classification indexer. Covers every branch (the self-test
 * fixtures only exercise healthy/below-min).
 *
 * Run with: `node scripts/governance/gates/consumer-drift/truth-table.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { verdictForSlot, verdictForFloorChange, verdictForSlotRemoval } from './truth-table.mjs';
import { indexClassificationsBySlot } from './classifications.mjs';

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

const base = {
  slotId: 's',
  count: 0,
  expectedMin: 1,
  withinGrace: false,
  classification: null,
  declaredInExists: true,
};

run('healthy when count >= min', () => {
  const v = verdictForSlot({ ...base, count: 2, expectedMin: 1 });
  assert.equal(v.status, 'pass');
  assert.equal(v.ruleId, 'consumer-drift/healthy');
});

run('healthy at exactly min', () => {
  assert.equal(verdictForSlot({ ...base, count: 1, expectedMin: 1 }).status, 'pass');
});

run('within-grace is info (not fail) below min', () => {
  const v = verdictForSlot({ ...base, count: 0, withinGrace: true });
  assert.equal(v.status, 'info');
  assert.equal(v.ruleId, 'consumer-drift/within-grace');
});

run('slot-retraction with declaredIn gone → pass (retracted)', () => {
  const v = verdictForSlot({ ...base, classification: 'slot-retraction', declaredInExists: false });
  assert.equal(v.status, 'pass');
  assert.equal(v.ruleId, 'consumer-drift/retracted');
});

run('slot-retraction with declaredIn still present → fail (misclassified)', () => {
  const v = verdictForSlot({ ...base, classification: 'slot-retraction', declaredInExists: true });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'consumer-drift/misclassified-retraction');
});

run('grace-extension covers drift → pass', () => {
  assert.equal(verdictForSlot({ ...base, classification: 'grace-extension' }).status, 'pass');
});

run('emergency-override covers drift → pass', () => {
  assert.equal(verdictForSlot({ ...base, classification: 'emergency-override' }).status, 'pass');
});

run('below-min with no classification + expired grace → fail', () => {
  const v = verdictForSlot({ ...base });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'consumer-drift/below-min');
});

// --- indexClassificationsBySlot ---
run('indexes declarations by their slot frontmatter field', () => {
  const m = indexClassificationsBySlot([
    { classification: 'slot-retraction', frontmatter: { slot: 'a' } },
    { classification: 'grace-extension', frontmatter: { slot: 'b' } },
  ]);
  assert.equal(m.get('a'), 'slot-retraction');
  assert.equal(m.get('b'), 'grace-extension');
});

run('ignores declarations with no slot field', () => {
  const m = indexClassificationsBySlot([{ classification: 'emergency-override', frontmatter: {} }]);
  assert.equal(m.size, 0);
});

// --- verdictForFloorChange (baseline-tampering, B) ---
run('floor raised → info (tightening)', () => {
  const v = verdictForFloorChange({ slotId: 's', priorMin: 1, liveMin: 3, classification: null });
  assert.equal(v.status, 'info');
  assert.equal(v.ruleId, 'consumer-drift/floor-raised');
});
run('floor unchanged → pass', () => {
  assert.equal(verdictForFloorChange({ slotId: 's', priorMin: 2, liveMin: 2, classification: null }).ruleId, 'consumer-drift/floor-unchanged');
});
run('floor lowered without changeset → fail (silent-floor-drop)', () => {
  const v = verdictForFloorChange({ slotId: 's', priorMin: 3, liveMin: 1, classification: null });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'consumer-drift/silent-floor-drop');
});
run('floor lowered with grace-extension → pass (declared)', () => {
  const v = verdictForFloorChange({ slotId: 's', priorMin: 3, liveMin: 1, classification: 'grace-extension' });
  assert.equal(v.status, 'pass');
  assert.equal(v.ruleId, 'consumer-drift/declared-floor-drop');
});

// --- verdictForSlotRemoval (baseline-tampering, B) ---
run('slot removed without retraction → fail (silent-slot-removal)', () => {
  const v = verdictForSlotRemoval({ slotId: 's', classification: null, declaredInExists: true });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'consumer-drift/silent-slot-removal');
});
run('slot removed via retraction with declaredIn gone → pass', () => {
  const v = verdictForSlotRemoval({ slotId: 's', classification: 'slot-retraction', declaredInExists: false });
  assert.equal(v.status, 'pass');
  assert.equal(v.ruleId, 'consumer-drift/declared-slot-removal');
});
run('slot removed via retraction but declaredIn still present → fail', () => {
  const v = verdictForSlotRemoval({ slotId: 's', classification: 'slot-retraction', declaredInExists: true });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'consumer-drift/misclassified-retraction');
});

// --- Report ---
if (failures.length > 0) {
  console.error(`consumer-drift truth-table.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`consumer-drift truth-table.test: all ${passed} checks passed`);
