/**
 * --explain coverage (tempdoc 576 §15 / G5): every registered gate rule resolves via `--explain`.
 *
 * For each gate in the registry that declares a `ruleDescriptions` module, this imports it, collects
 * every declared ruleId, and asserts `explainRule` resolves each (prints "Rule: <id>", never "No
 * description registered"). Guarantees `--explain <ruleId>` is useful for every registered rule — the
 * Layer-3 legibility promise, made complete rather than asserted.
 *
 * Run: `node scripts/governance/lib/explain-coverage.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { explainRule } from './explain.mjs';

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

const registry = JSON.parse(readFileSync(resolve(REPO_ROOT, 'governance/registry.v1.json'), 'utf8'));
const gates = Array.isArray(registry.gates) ? registry.gates : [];

/** Collect every declared ruleId from a gate's ruleDescriptions module (any exported plain object). */
async function declaredRuleIds(gate) {
  const path = resolve(REPO_ROOT, gate.ruleDescriptions);
  if (!existsSync(path)) return null;
  const mod = await import(pathToFileURL(path).href);
  const ids = new Set();
  for (const value of Object.values(mod)) {
    if (value && typeof value === 'object') for (const k of Object.keys(value)) ids.add(k);
  }
  return ids;
}

/** Run explainRule, capturing stdout; return the combined output. */
async function runExplain(ruleId) {
  const orig = console.log;
  let out = '';
  console.log = (...a) => {
    out += a.join(' ') + '\n';
  };
  try {
    await explainRule({ ruleId, gates, repoRoot: REPO_ROOT });
  } finally {
    console.log = orig;
  }
  return out;
}

let totalRules = 0;
let gatesWithDescriptions = 0;
for (const gate of gates) {
  if (!gate.ruleDescriptions) continue;
  const ids = await declaredRuleIds(gate);
  ok(`${gate.id}: ruleDescriptions module imports + exports rules`, ids !== null && ids.size > 0);
  if (!ids) continue;
  gatesWithDescriptions += 1;
  for (const ruleId of ids) {
    totalRules += 1;
    const out = await runExplain(ruleId);
    ok(`--explain ${ruleId} resolves`, /^Rule: /m.test(out) && !/No description registered/.test(out));
  }
}

ok('at least 10 gates declare rule descriptions', gatesWithDescriptions >= 10);
ok('many rules covered', totalRules >= 50);

if (failures.length > 0) {
  console.error(`explain-coverage.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures.slice(0, 20)) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`explain-coverage.test: all ${passed} checks passed (${totalRules} rules across ${gatesWithDescriptions} gates resolve via --explain)`);
