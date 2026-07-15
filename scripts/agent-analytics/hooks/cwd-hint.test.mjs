/**
 * Tempdoc 727 (finding F-6a) — unit tests for cwd-hint's decision logic.
 *
 * Exercises the pure `buildCwdContext(input)` function directly (the I/O
 * wrapper `main()` is not invoked on import).
 *
 * Run with: `node scripts/agent-analytics/hooks/cwd-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { buildCwdContext } from './cwd-hint.mjs';

let passed = 0;
const failures = [];

/** Assert the additionalContext string produced for a given hook payload. */
function check(label, input, expected) {
  try {
    const result = buildCwdContext(input);
    assert.equal(result, expected, `${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(result)}`);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

// --- happy path: a real cwd produces the one-line context message ---
check(
  'absolute windows path',
  { cwd: 'F:\\justsearch-public\\.claude\\worktrees\\727-friction-fixes' },
  'Working directory changed to: F:\\justsearch-public\\.claude\\worktrees\\727-friction-fixes',
);
check(
  'absolute posix path',
  { cwd: '/home/user/project' },
  'Working directory changed to: /home/user/project',
);
check(
  'other fields on the payload are ignored',
  { hook_event_name: 'CwdChanged', session_id: 'abc123', cwd: '/tmp/foo' },
  'Working directory changed to: /tmp/foo',
);

// --- defensive no-op cases: missing/falsy cwd never throws, returns null ---
check('missing cwd field', {}, null);
check('null cwd', { cwd: null }, null);
check('empty-string cwd', { cwd: '' }, null);
check('undefined cwd', { cwd: undefined }, null);
check('null input', null, null);
check('undefined input', undefined, null);

// --- Report ---
if (failures.length > 0) {
  console.error(`cwd-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`cwd-hint.test: all ${passed} checks passed`);
