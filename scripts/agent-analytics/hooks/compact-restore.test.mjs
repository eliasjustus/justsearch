/**
 * Tempdoc 520 P0d — unit tests for compact-restore's decision logic + the
 * session-stamp that closes the ghost-file window.
 *
 * Run with: `node scripts/agent-analytics/hooks/compact-restore.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { decideAction, buildContext, parseSessionStamp } from './compact-restore.mjs';

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

// --- P0d: SessionEnd deletes the rules file (closes the cross-session window) ---
run('SessionEnd → cleanup', () => {
  assert.deepEqual(decideAction({ hook_event_name: 'SessionEnd', reason: 'exit' }), { action: 'cleanup' });
});

// --- SessionStart, non-compact → crash-fallback cleanup ---
run('SessionStart startup → cleanup', () => {
  assert.deepEqual(decideAction({ hook_event_name: 'SessionStart', source: 'startup' }), { action: 'cleanup' });
});
run('SessionStart resume → cleanup', () => {
  assert.deepEqual(decideAction({ hook_event_name: 'SessionStart', source: 'resume' }), { action: 'cleanup' });
});

// --- SessionStart, compact → restore (or noop without a session id) ---
run('SessionStart compact with id → restore', () => {
  assert.deepEqual(
    decideAction({ hook_event_name: 'SessionStart', source: 'compact', session_id: 'sess-1' }),
    { action: 'restore', sessionId: 'sess-1' }
  );
});
run('SessionStart compact without id → noop', () => {
  assert.deepEqual(decideAction({ hook_event_name: 'SessionStart', source: 'compact' }), { action: 'noop' });
});

// --- Stamp round-trips so a lingering file is self-identifying ---
run('buildContext stamps the writing session id', () => {
  const ctx = buildContext({ modified_files: ['a.ts'] }, 'sess-XYZ');
  assert.equal(parseSessionStamp(ctx), 'sess-XYZ');
});
run('buildContext includes modified files', () => {
  const ctx = buildContext({ modified_files: ['a.ts', 'b.ts'] }, 'sess-1');
  assert.ok(ctx.includes('a.ts') && ctx.includes('b.ts'), 'expected modified files in context');
});
run('parseSessionStamp returns null when absent', () => {
  assert.equal(parseSessionStamp('# Some unrelated rules file\n'), null);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`compact-restore.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`compact-restore.test: all ${passed} checks passed`);
