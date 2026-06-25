/**
 * Tempdoc 520 — unit tests for repeat-guard's fingerprint logic.
 *
 * The atomic buffer write (P0e) now lives in `lib/hook-base.mjs` and is
 * covered by `lib/hook-base.test.mjs`; this file covers the fingerprint
 * identity used for consecutive-call detection.
 *
 * Run with: `node scripts/agent-analytics/hooks/repeat-guard.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { fingerprint } from './repeat-guard.mjs';

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

run('identical Read calls fingerprint identically', () => {
  const a = fingerprint('Read', { file_path: '/x.ts', offset: 10, limit: 50 });
  const b = fingerprint('Read', { file_path: '/x.ts', offset: 10, limit: 50 });
  assert.equal(a, b);
});

run('different Read offsets fingerprint differently', () => {
  const a = fingerprint('Read', { file_path: '/x.ts', offset: 10 });
  const b = fingerprint('Read', { file_path: '/x.ts', offset: 200 });
  assert.notEqual(a, b);
});

run('different Bash commands fingerprint differently', () => {
  assert.notEqual(
    fingerprint('Bash', { command: 'ls' }),
    fingerprint('Bash', { command: 'pwd' })
  );
});

run('singleton tools fingerprint to the tool name', () => {
  assert.equal(fingerprint('Skill', { skill: 'x' }), 'Skill');
});

// --- Report ---
if (failures.length > 0) {
  console.error(`repeat-guard.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`repeat-guard.test: all ${passed} checks passed`);
