/**
 * Tests for the shared no-silent-downgrade substrate (tempdoc 576 §5 / §4.4):
 *   - detectBaselineTamper  — the covered→note vs silent→error+fail decision
 *   - enumerateAllGateChangesets — cross-gate changeset enumeration (validate:false path)
 *
 * Run: `node scripts/governance/lib/baseline-tamper-detector.test.mjs` (exits non-zero on failure)
 */

import assert from 'node:assert/strict';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { detectBaselineTamper } from './baseline-tamper-detector.mjs';
import { enumerateAllGateChangesets } from './changeset-loader.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

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

// --- detectBaselineTamper ---
const covered = detectBaselineTamper([
  { raised: true, covered: true, silentRuleId: 'g/silent', silentMessage: 's', declaredRuleId: 'g/declared', declaredMessage: 'd', uri: 'u' },
]);
ok('covered relaxation does not fail', covered.fail === false);
ok('covered relaxation emits a declared note', covered.findings.length === 1 && covered.findings[0].ruleId === 'g/declared' && covered.findings[0].level === 'note');

const silent = detectBaselineTamper([
  { raised: true, covered: false, silentRuleId: 'g/silent', silentMessage: 's', declaredRuleId: 'g/declared', declaredMessage: 'd', uri: 'u' },
]);
ok('uncovered relaxation FAILS', silent.fail === true);
ok('uncovered relaxation emits a silent error', silent.findings.length === 1 && silent.findings[0].ruleId === 'g/silent' && silent.findings[0].level === 'error');

const ignored = detectBaselineTamper([
  { raised: false, covered: false, silentRuleId: 'g/silent', silentMessage: 's', declaredRuleId: 'g/declared', declaredMessage: 'd' },
]);
ok('non-raised event is ignored', ignored.fail === false && ignored.findings.length === 0);

const mixed = detectBaselineTamper([
  { raised: true, covered: true, silentRuleId: 'g/s', silentMessage: 's1', declaredRuleId: 'g/d', declaredMessage: 'd1' },
  { raised: true, covered: false, silentRuleId: 'g/s', silentMessage: 's2', declaredRuleId: 'g/d', declaredMessage: 'd2' },
  { raised: false, covered: true, silentRuleId: 'g/s', silentMessage: 's3', declaredRuleId: 'g/d', declaredMessage: 'd3' },
]);
ok('mixed: one fail present', mixed.fail === true);
ok('mixed: only raised events produce findings (2 of 3)', mixed.findings.length === 2);
ok('empty event list is a no-op', detectBaselineTamper([]).fail === false && detectBaselineTamper([]).findings.length === 0);

// --- enumerateAllGateChangesets: validate:false loads heterogeneous classifications without throwing ---
// ui-bundle's real changesets carry classifications (declared-growth, etc.) that are NOT in an empty
// allowed-set — so a load that does NOT throw proves the validate:false enumeration path works.
let enumResult = null;
let threw = false;
try {
  enumResult = enumerateAllGateChangesets({
    repoRoot: REPO_ROOT,
    gates: [{ id: 'ui-bundle', changesetsDir: 'gates/ui-bundle/.changesets' }],
    baselineRef: null,
  });
} catch (e) {
  threw = true;
  failures.push(`enumerateAllGateChangesets threw: ${e.message}`);
}
ok('enumerate does not throw on heterogeneous classifications', threw === false);
ok('enumerate returns an array', Array.isArray(enumResult));
ok('enumerate tags each result with its gateId', (enumResult ?? []).every((d) => d.gateId === 'ui-bundle' && typeof d.classification === 'string'));
ok('enumerate skips a gate with no changesetsDir', enumerateAllGateChangesets({ repoRoot: REPO_ROOT, gates: [{ id: 'x' }] }).length === 0);

if (failures.length > 0) {
  console.error(`baseline-tamper-detector.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`baseline-tamper-detector.test: all ${passed} checks passed`);
