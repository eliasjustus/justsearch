/**
 * Tests for the §5 vacuous-pass guard (tempdoc 576 §5):
 *   - verdictForVacuousScan — detected < floor → fail (vacuous-scan); detected >= floor → pass.
 *
 * Run: `node scripts/governance/lib/population-floor.test.mjs` (exits non-zero on failure)
 */

import assert from 'node:assert/strict';

import { verdictForVacuousScan } from './population-floor.mjs';

let passed = 0;
const failures = [];
function ok(label, cond) {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

// Collapse to zero → fail with the vacuous-scan rule.
const collapsed = verdictForVacuousScan({ rulePrefix: 'execution-surface', detected: 0, min: 12 });
ok('zero detected fails', collapsed.status === 'fail');
ok('zero detected uses the vacuous-scan rule', collapsed.ruleId === 'execution-surface/vacuous-scan');
ok('message reports the detected count + floor', /found 0\b/.test(collapsed.reason) && />= 12/.test(collapsed.reason));

// Below floor (partial collapse) → fail.
ok('below floor fails', verdictForVacuousScan({ rulePrefix: 'g', detected: 5, min: 12 }).status === 'fail');

// At / above floor → pass with the live rule.
const live = verdictForVacuousScan({ rulePrefix: 'execution-surface', detected: 26, min: 12 });
ok('at-or-above floor passes', live.status === 'pass');
ok('healthy uses the scan-population-live rule', live.ruleId === 'execution-surface/scan-population-live');
ok('exactly-at-floor passes', verdictForVacuousScan({ rulePrefix: 'g', detected: 12, min: 12 }).status === 'pass');

// Default floor is 1 (the doc's expectedMinPopulation >= 1).
ok('default floor is 1 — zero fails', verdictForVacuousScan({ rulePrefix: 'g', detected: 0 }).status === 'fail');
ok('default floor is 1 — one passes', verdictForVacuousScan({ rulePrefix: 'g', detected: 1 }).status === 'pass');

// A non-positive / non-finite min is clamped to 1 (never a floor of 0 that can't bite).
ok('min<=0 clamps to 1 (zero still fails)', verdictForVacuousScan({ rulePrefix: 'g', detected: 0, min: 0 }).status === 'fail');
ok('min=NaN clamps to 1 (one passes)', verdictForVacuousScan({ rulePrefix: 'g', detected: 1, min: Number.NaN }).status === 'pass');

if (failures.length > 0) {
  console.error(`population-floor.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`population-floor.test: all ${passed} checks passed`);
