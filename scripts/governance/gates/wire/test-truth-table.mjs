#!/usr/bin/env node
/**
 * Truth-table unit tests — slice 3a-1-8f §A.10.
 *
 * One test per truth-table row + a few aggregation cases.
 *
 * Run: node scripts/governance/gates/wire/test-truth-table.mjs
 * Exit: 0 on all-pass, 1 on any failure.
 */

import { computeVerdict, computeVersionDelta } from './protobuf-truth-table.mjs';
import { aggregateClassifications } from './protobuf-changeset-parser.mjs';

let pass = 0;
let fail = 0;

function assert(name, cond, detail) {
  if (cond) {
    pass++;
  } else {
    fail++;
    console.error(`  FAIL: ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

function assertVerdict(name, input, expectedStatus, expectedRuleId) {
  const v = computeVerdict(input);
  const ok = v.status === expectedStatus && v.ruleId === expectedRuleId;
  assert(name, ok, `got status=${v.status}, ruleId=${v.ruleId}, reason="${v.reason}"`);
}

console.log('§A.10 truth-table unit tests');
console.log('============================');

// computeVersionDelta correctness.
assert('delta: 0.1.0 → 0.1.0 = none', computeVersionDelta('0.1.0', '0.1.0') === 'none');
assert('delta: 0.1.0 → 0.1.1 = patch', computeVersionDelta('0.1.1', '0.1.0') === 'patch');
assert('delta: 0.1.0 → 0.2.0 = minor', computeVersionDelta('0.2.0', '0.1.0') === 'minor');
assert('delta: 0.1.0 → 1.0.0 = major', computeVersionDelta('1.0.0', '0.1.0') === 'major');
assert('delta: 1.0.0 → 0.1.0 = downgrade', computeVersionDelta('0.1.0', '1.0.0') === 'downgrade');
assert('delta: null/null = none', computeVersionDelta(null, null) === 'none');

// Row 1: no classification, no diff, no bump → noop-pr.
assertVerdict(
  'row 1: noop PR',
  { classification: { rule: null, requiredBump: null }, breaks: false, currentVersion: '0.1.0', baselineVersion: '0.1.0' },
  'pass',
  'noop-pr',
);

// Row 2: no classification, no diff, bump present → phantom-version.
assertVerdict(
  'row 2: phantom version (no diff but bumped)',
  { classification: { rule: null, requiredBump: null }, breaks: false, currentVersion: '0.2.0', baselineVersion: '0.1.0' },
  'fail',
  'phantom-version',
);

// Row 3: no classification, breaks present → undeclared-break.
assertVerdict(
  'row 3: undeclared break',
  { classification: { rule: null, requiredBump: null }, breaks: true, currentVersion: '0.1.0', baselineVersion: '0.1.0' },
  'fail',
  'undeclared-break',
);
assertVerdict(
  'row 3 variant: undeclared break with major bump (still fail; declaration missing)',
  { classification: { rule: null, requiredBump: null }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'fail',
  'undeclared-break',
);

// Row 4: additive-optional, no breaks, patch bump → pass.
assertVerdict(
  'row 4: additive-optional + patch bump',
  { classification: { rule: 'additive-optional', requiredBump: 'patch' }, breaks: false, currentVersion: '0.1.1', baselineVersion: '0.1.0' },
  'pass',
  'declared-additive',
);

// Row 5: additive-required, no breaks, minor bump → pass.
assertVerdict(
  'row 5: additive-required + minor bump',
  { classification: { rule: 'additive-required', requiredBump: 'minor' }, breaks: false, currentVersion: '0.2.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-additive',
);

// Insufficient bump for additive-required (declared minor but only patch shipped).
assertVerdict(
  'insufficient bump: additive-required + patch-only',
  { classification: { rule: 'additive-required', requiredBump: 'minor' }, breaks: false, currentVersion: '0.1.1', baselineVersion: '0.1.0' },
  'fail',
  'insufficient-bump',
);

// Row 6: additive-optional declared, breaks observed → misclassification.
assertVerdict(
  'row 6: additive-optional + breaks → misclassification',
  { classification: { rule: 'additive-optional', requiredBump: 'patch' }, breaks: true, currentVersion: '0.1.1', baselineVersion: '0.1.0' },
  'fail',
  'misclassification',
);

// Row 7: additive-required declared, breaks observed → misclassification.
assertVerdict(
  'row 7: additive-required + breaks → misclassification',
  { classification: { rule: 'additive-required', requiredBump: 'minor' }, breaks: true, currentVersion: '0.2.0', baselineVersion: '0.1.0' },
  'fail',
  'misclassification',
);

// Row 8: rename declared, breaks observed, major bump → pass.
assertVerdict(
  'row 8: rename + breaks + major bump',
  { classification: { rule: 'rename', requiredBump: 'major' }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-breaking',
);

// remove + breaks + major → pass.
assertVerdict(
  'remove + breaks + major bump',
  { classification: { rule: 'remove', requiredBump: 'major' }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-breaking',
);

// Row 9: rename declared, breaks observed, patch bump → insufficient.
assertVerdict(
  'row 9: rename + breaks + patch only → insufficient bump',
  { classification: { rule: 'rename', requiredBump: 'major' }, breaks: true, currentVersion: '0.1.1', baselineVersion: '0.1.0' },
  'fail',
  'insufficient-bump',
);

// Row 10: rename declared, NO breaks observed → declared-without-diff.
assertVerdict(
  'row 10: rename declared but no break observed',
  { classification: { rule: 'rename', requiredBump: 'major' }, breaks: false, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'fail',
  'declared-without-diff',
);

// Row 11: enum-value-added, no breaks, minor bump → pass.
assertVerdict(
  'row 11: enum-value-added + minor bump',
  { classification: { rule: 'enum-value-added', requiredBump: 'minor' }, breaks: false, currentVersion: '0.2.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-additive',
);

// Row 12: enum-value-removed, breaks observed, major bump → pass.
assertVerdict(
  'row 12: enum-value-removed + major bump',
  { classification: { rule: 'enum-value-removed', requiredBump: 'major' }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-breaking',
);

// Row 13: enum-value-renamed, breaks observed, major bump → pass.
assertVerdict(
  'row 13: enum-value-renamed + major bump',
  { classification: { rule: 'enum-value-renamed', requiredBump: 'major' }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-breaking',
);

// Row 14: type-change + major → pass.
assertVerdict(
  'row 14: type-change + major bump',
  { classification: { rule: 'type-change', requiredBump: 'major' }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-breaking',
);

// Row 15: type-change + breaks + patch only → insufficient.
assertVerdict(
  'row 15: type-change + breaks + patch only → insufficient bump',
  { classification: { rule: 'type-change', requiredBump: 'major' }, breaks: true, currentVersion: '0.1.1', baselineVersion: '0.1.0' },
  'fail',
  'insufficient-bump',
);

// Row 16: package-rename + major → pass.
assertVerdict(
  'row 16: package-rename + major bump',
  { classification: { rule: 'package-rename', requiredBump: 'major' }, breaks: true, currentVersion: '1.0.0', baselineVersion: '0.1.0' },
  'pass',
  'declared-breaking',
);

// Row 17: package-rename + breaks + minor only → insufficient.
assertVerdict(
  'row 17: package-rename + breaks + minor only → insufficient bump',
  { classification: { rule: 'package-rename', requiredBump: 'major' }, breaks: true, currentVersion: '0.2.0', baselineVersion: '0.1.0' },
  'fail',
  'insufficient-bump',
);

// V1.5 amendment (§B.11, 2026-05-12): unknown / downgrade delta rows.
console.log('\n§B.11 unknown / downgrade delta branches');
console.log('========================================');

// baseline-introduction: rule=null, !breaks, delta=unknown (one side null).
assertVerdict(
  'baseline-introduction: first commit introducing VERSION (current set, baseline null)',
  { classification: { rule: null, requiredBump: null }, breaks: false, currentVersion: '0.1.0', baselineVersion: null },
  'pass',
  'baseline-introduction',
);
assertVerdict(
  'baseline-introduction: VERSION removed (current null, baseline set)',
  { classification: { rule: null, requiredBump: null }, breaks: false, currentVersion: null, baselineVersion: '0.1.0' },
  'pass',
  'baseline-introduction',
);

// downgrade-with-breaks: any rule, breaks=true, delta=downgrade.
assertVerdict(
  'downgrade-with-breaks: revert PR with no classification',
  { classification: { rule: null, requiredBump: null }, breaks: true, currentVersion: '0.1.0', baselineVersion: '1.0.0' },
  'fail',
  'downgrade-with-breaks',
);
assertVerdict(
  'downgrade-with-breaks: revert PR with prior classification declared',
  { classification: { rule: 'rename', requiredBump: 'major' }, breaks: true, currentVersion: '0.1.0', baselineVersion: '1.0.0' },
  'fail',
  'downgrade-with-breaks',
);

// phantom-downgrade: rule=null, !breaks, delta=downgrade.
assertVerdict(
  'phantom-downgrade: VERSION lowered with no spec change and no classification',
  { classification: { rule: null, requiredBump: null }, breaks: false, currentVersion: '0.1.0', baselineVersion: '0.2.0' },
  'fail',
  'phantom-downgrade',
);

// Declared classification + downgrade + no breaks → falls through to
// insufficient-bump (existing row 4/5 logic; documented in
// truth-table.mjs comments). Verifying this still holds.
assertVerdict(
  'declared + downgrade + no breaks → insufficient-bump (existing logic)',
  { classification: { rule: 'additive-optional', requiredBump: 'patch' }, breaks: false, currentVersion: '0.1.0', baselineVersion: '0.2.0' },
  'fail',
  'insufficient-bump',
);

// Aggregation: highest-bump-wins across multiple declarations.
console.log('\n§A.14 highest-bump-wins aggregation');
console.log('====================================');

const agg1 = aggregateClassifications([
  { rule: 'additive-optional' },
  { rule: 'additive-required' },
]);
assert(
  'agg: optional + required → required wins',
  agg1.rule === 'additive-required' && agg1.requiredBump === 'minor',
  `got ${JSON.stringify(agg1)}`,
);

const agg2 = aggregateClassifications([
  { rule: 'additive-optional' },
  { rule: 'rename' },
  { rule: 'additive-required' },
]);
assert(
  'agg: optional + rename + required → rename wins (major)',
  agg2.rule === 'rename' && agg2.requiredBump === 'major',
  `got ${JSON.stringify(agg2)}`,
);

const agg3 = aggregateClassifications([]);
assert(
  'agg: empty → null rule',
  agg3.rule === null && agg3.declarations === 0,
);

const agg4 = aggregateClassifications([{ rule: 'package-rename' }]);
assert(
  'agg: single package-rename → major',
  agg4.rule === 'package-rename' && agg4.requiredBump === 'major',
);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
