/**
 * Tests for the accent-as-text lint (tempdoc 576 §6 rung-1): it FAILS accent FILLs used as text and
 * ignores the legitimate on-grade / text-grade / border uses.
 *
 * Run: `node scripts/ci/check-accent-as-text.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { countAccentAsText } from './check-accent-as-text.mjs';

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

ok('FAILS: accent fill as text', countAccentAsText('a { color: var(--accent-danger); }') === 1);
ok('FAILS: alpha accent fill as text', countAccentAsText('a { color: var(--accent-tint-45); }') === 1);
ok('FAILS: counts multiple', countAccentAsText('a{color:var(--accent-success)} b{color: var(--accent-chat)}') === 2);
ok('OK: on-grade is not flagged', countAccentAsText('a { color: var(--accent-on-danger); }') === 0);
ok('OK: text-grade is not flagged', countAccentAsText('a { color: var(--text-danger); }') === 0);
ok('OK: border-color is not flagged', countAccentAsText('a { border-color: var(--accent-danger); }') === 0);
ok('OK: background-color is not flagged', countAccentAsText('a { background-color: var(--accent-danger); }') === 0);

if (failures.length > 0) {
  console.error(`check-accent-as-text.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`check-accent-as-text.test: all ${passed} checks passed`);
