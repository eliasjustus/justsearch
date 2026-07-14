/**
 * Tempdoc 727 F-7b — unit tests for taskcreate-guard's decision logic.
 *
 * Exercises the pure `evaluateTaskCreateInput(toolInput)` function (the I/O
 * wrapper `main()` is not invoked on import).
 *
 * Run with: `node scripts/agent-analytics/hooks/taskcreate-guard.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { evaluateTaskCreateInput } from './taskcreate-guard.mjs';

let passed = 0;
const failures = [];

/** Assert whether `toolInput` is blocked. */
function check(label, toolInput, expectBlock) {
  try {
    const verdict = evaluateTaskCreateInput(toolInput);
    assert.equal(
      verdict.block,
      expectBlock,
      `${label}: expected block=${expectBlock} for ${JSON.stringify(toolInput)}, ` +
        `got block=${verdict.block}`
    );
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

// --- The exact real malformed shape from historical transcript failures ---
check(
  'real malformed shape (tasks as JSON-stringified array) blocked',
  { tasks: '[{"description":"x"}]' },
  true
);

// --- Correct calls are never blocked ---
check('correct subject/description call allowed', { subject: 'x', description: 'y' }, false);
check('description-only call allowed', { description: 'y' }, false);

// --- Empty/edge-case input is not this guard's job (platform's own validation) ---
check('empty input allowed (not this guard\'s job)', {}, false);

// --- `tasks` key blocked regardless of value type ---
check('tasks as actual array blocked', { tasks: [{ description: 'x' }] }, true);
check('tasks as empty string blocked', { tasks: '' }, true);
check('tasks as null blocked (key present)', { tasks: null }, true);
check('tasks alongside subject/description still blocked', { subject: 'x', description: 'y', tasks: '[]' }, true);

// --- Defensive: no toolInput at all ---
check('undefined toolInput allowed', undefined, false);
check('null toolInput allowed', null, false);

// --- Report ---
if (failures.length > 0) {
  console.error(`taskcreate-guard.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`taskcreate-guard.test: all ${passed} checks passed`);
