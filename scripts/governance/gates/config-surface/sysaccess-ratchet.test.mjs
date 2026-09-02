/**
 * Unit tests for the system-access allowlist ratchet verdict (tempdoc 883 decision 5).
 *
 * `SystemAccessFunnelTest` (Java, modules/dead-code-audit) fails when a call site is missing from
 * `gates/config-surface/sysaccess-allowlist.txt`. The one-line way to make that test green without
 * routing the value through `io.justsearch.configuration` is to append the site to the file — so
 * the allowlist itself needs a ratchet, and this is the verdict that supplies it.
 *
 * Run: `node scripts/governance/gates/config-surface/sysaccess-ratchet.test.mjs`
 */

import assert from 'node:assert/strict';

import { verdictForSysaccessGrowth } from './truth-table.mjs';

let passed = 0;
const failures = [];
const check = (name, fn) => {
  try {
    fn();
    passed += 1;
    console.log(`  ok  ${name}`);
  } catch (e) {
    failures.push(`${name}: ${e.message}`);
    console.log(`  FAIL ${name}: ${e.message}`);
  }
};

console.log('[test-config-surface-sysaccess-ratchet]');

check('an undeclared new entry FAILS — this is the ratchet biting', () => {
  const v = verdictForSysaccessGrowth({
    added: ['io.justsearch.ui.Thing#doStuff'],
    classification: 'silent-growth',
  });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'config-surface/sysaccess-allowlist-growth');
  assert.match(v.reason, /only shrinks/);
  assert.match(v.reason, /io\.justsearch\.ui\.Thing#doStuff/);
});

check('a declared new entry passes with the classification named', () => {
  const v = verdictForSysaccessGrowth({
    added: ['io.justsearch.ui.Thing#doStuff'],
    classification: 'declared-growth',
  });
  assert.notEqual(v.status, 'fail');
  assert.equal(v.ruleId, 'config-surface/declared-growth');
});

check('an unchanged allowlist passes', () => {
  const v = verdictForSysaccessGrowth({ added: [], classification: 'silent-growth' });
  assert.equal(v.status, 'pass');
  assert.equal(v.ruleId, 'config-surface/within-baseline');
});

check('a SHRINKING allowlist passes — removal is the point, not a regression', () => {
  // Removal shows up as an empty `added` set: the enforcer computes live-minus-prior, so entries
  // that disappeared never reach this verdict. Asserting it explicitly pins the asymmetry, which
  // is the whole design (adding fails, removing is free).
  const v = verdictForSysaccessGrowth({ added: [], classification: 'silent-growth' });
  assert.equal(v.status, 'pass');
});

check('the message truncates a large addition rather than dumping the file', () => {
  const added = Array.from({ length: 12 }, (_, i) => `io.justsearch.X${i}#m`);
  const v = verdictForSysaccessGrowth({ added, classification: 'silent-growth' });
  assert.equal(v.status, 'fail');
  assert.match(v.reason, /gained 12 entries/);
  assert.match(v.reason, /…/);
});

console.log(`[test-config-surface-sysaccess-ratchet] ${passed} passed, ${failures.length} failed`);
if (failures.length > 0) {
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
