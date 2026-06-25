/**
 * Tempdoc 520 P0f — unit tests for build-counter's failure-state logic.
 * The synchronous PostToolUse counting + PreToolUse blocking close the
 * async-write/sync-read race that fired the advisory one call late.
 *
 * Run with: `node scripts/agent-analytics/hooks/build-counter.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { isBuildCommand, nextFailureState, shouldBlock } from './build-counter.mjs';

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

// --- isBuildCommand ---
run('detects gradlew', () => assert.equal(isBuildCommand('./gradlew.bat build'), true));
run('detects gradlew anywhere', () => assert.equal(isBuildCommand('cd x && gradlew test'), true));
run('ignores non-build', () => assert.equal(isBuildCommand('npm run build'), false));

// --- nextFailureState: counting transitions ---
run('failure increments', () => {
  assert.deepEqual(nextFailureState({ consecutiveFailures: 1, advisoryShown: true }, 1), {
    consecutiveFailures: 2,
    advisoryShown: false,
  });
});
run('success resets', () => {
  assert.deepEqual(nextFailureState({ consecutiveFailures: 5, advisoryShown: true }, 0), {
    consecutiveFailures: 0,
    advisoryShown: false,
  });
});
run('null exit code is neutral (no change)', () => {
  assert.deepEqual(nextFailureState({ consecutiveFailures: 2, advisoryShown: true }, null), {
    consecutiveFailures: 2,
    advisoryShown: true,
  });
});
run('from empty prev', () => {
  assert.deepEqual(nextFailureState(undefined, 1), { consecutiveFailures: 1, advisoryShown: false });
});

// --- shouldBlock: one-shot gate at threshold ---
run('does not block below threshold', () => {
  assert.equal(shouldBlock({ consecutiveFailures: 2, advisoryShown: false }), false);
});
run('blocks at threshold when advisory not yet shown', () => {
  assert.equal(shouldBlock({ consecutiveFailures: 3, advisoryShown: false }), true);
});
run('does not re-block once advisory shown', () => {
  assert.equal(shouldBlock({ consecutiveFailures: 3, advisoryShown: true }), false);
});

// --- Race-closure scenario: 3 consecutive failures, then a 4th build is blocked ---
run('3 failures → 4th PreToolUse blocks (no off-by-one)', () => {
  let s = { consecutiveFailures: 0, advisoryShown: false };
  s = nextFailureState(s, 1); // build 1 fails
  s = nextFailureState(s, 1); // build 2 fails
  s = nextFailureState(s, 1); // build 3 fails
  assert.equal(s.consecutiveFailures, 3);
  // Next PreToolUse sees the synchronously-written count and blocks immediately.
  assert.equal(shouldBlock(s), true);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`build-counter.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`build-counter.test: all ${passed} checks passed`);
