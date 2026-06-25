/**
 * Integration test for the §5 no-silent-downgrade path through the register-guard-resolution gate
 * (tempdoc 576 §5 / G4). The pure detector is unit-tested in lib/guard-resolver.test.mjs; this proves
 * the GATE actually bites: the negative self-test fixture downgrades a `test:` guard to `exempt:` vs its
 * `_baseline/`, and the gate must emit `silent-guard-downgrade` and fail. Closes the audit-without-test
 * gap (a fixture introducing a downgrade fails).
 *
 * Run: `node scripts/governance/gates/register-guard-resolution/enforcer.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { enforceRegisterGuardResolution } from './enforcer.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');

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

const reg = JSON.parse(readFileSync(resolve(REPO_ROOT, 'governance/registry.v1.json'), 'utf8'));
const gate = reg.gates.find((g) => g.id === 'register-guard-resolution');
const base = resolve(REPO_ROOT, 'scripts/governance/_fixtures/register-guard-resolution');

const neg = await enforceRegisterGuardResolution({
  repoRoot: REPO_ROOT,
  gate,
  fixtureMode: true,
  fixtureRoot: resolve(base, 'negative'),
});
ok('negative fixture fails', neg.verdict === 'fail');
ok(
  'negative fixture emits silent-guard-downgrade (the §5 detector bites in fixtureMode)',
  neg.findings.some((f) => f.ruleId === 'register-guard-resolution/silent-guard-downgrade'),
);

const pos = await enforceRegisterGuardResolution({
  repoRoot: REPO_ROOT,
  gate,
  fixtureMode: true,
  fixtureRoot: resolve(base, 'positive'),
});
ok('positive fixture passes', pos.verdict === 'pass');
ok(
  'positive fixture emits NO downgrade finding (no _baseline / no weakening)',
  !pos.findings.some((f) => f.ruleId === 'register-guard-resolution/silent-guard-downgrade'),
);

if (failures.length > 0) {
  console.error(`register-guard-resolution enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`register-guard-resolution enforcer.test: all ${passed} checks passed`);
