/**
 * Tempdoc 575 §17 Face A — unit tests for the liveness-window SELECTION law.
 *
 * Regression guard for the Face C ↔ Face A collision: Face C added a `liveness.model` facet (with NO
 * window) to the polled-state concepts (ai-install / pack-import), so the register now holds MORE than
 * one `liveness` block. The generator must select the ONE window-BEARING block (a numeric heartbeatMs),
 * not "any liveness block" — the latter threw `expected exactly ONE concept with a liveness block,
 * found 3`. Neither gate fixture had >1 liveness block, so nothing caught it; this is that coverage.
 *
 * `readLivenessWindow(register)` is injectable, so the law is tested without touching the real file.
 *
 * Run with: `node scripts/codegen/gen-liveness-constants.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { readLivenessWindow } from './gen-liveness-constants.mjs';

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

const WINDOW = { model: 'heartbeat-lease', heartbeatMs: 30000, displayStaleMs: 90000, reaperStaleMs: 300000 };
const POLLED = { model: 'polled-state', _doc: 'no window — owner certifies its own death on read' };

run('the real Face C shape: 1 window-bearing + 2 polled-state → selects the window-bearing concept', () => {
  const register = {
    concepts: [
      { id: 'head-log' }, // no liveness block at all
      { id: 'ai-install', stateful: true, liveness: POLLED },
      { id: 'action-lifecycle', stateful: true, liveness: WINDOW },
      { id: 'pack-import', stateful: true, liveness: POLLED },
    ],
  };
  const w = readLivenessWindow(register);
  assert.equal(w.conceptId, 'action-lifecycle');
  assert.equal(w.heartbeatMs, 30000);
  assert.equal(w.displayStaleMs, 90000);
  assert.equal(w.reaperStaleMs, 300000);
});

run('zero window-bearing blocks (only polled-state) → throws', () => {
  const register = { concepts: [{ id: 'ai-install', liveness: POLLED }, { id: 'pack-import', liveness: POLLED }] };
  assert.throws(() => readLivenessWindow(register), /exactly ONE concept with a liveness WINDOW/);
});

run('two window-bearing blocks → throws (a genuine ambiguity, not a polled-state false positive)', () => {
  const register = {
    concepts: [
      { id: 'a', liveness: WINDOW },
      { id: 'b', liveness: { ...WINDOW, heartbeatMs: 31000 } },
    ],
  };
  assert.throws(() => readLivenessWindow(register), /found 2/);
});

run('an incoherent window (heartbeat >= displayStale) still throws the ordering law', () => {
  const register = {
    concepts: [{ id: 'a', liveness: { ...WINDOW, heartbeatMs: 90000, displayStaleMs: 90000 } }],
  };
  assert.throws(() => readLivenessWindow(register), /ordering violated/);
});

run('the 3x-heartbeat margin law still fires', () => {
  const register = {
    concepts: [{ id: 'a', liveness: { ...WINDOW, heartbeatMs: 40000, displayStaleMs: 90000 } }],
  };
  assert.throws(() => readLivenessWindow(register), /margin violated/);
});

if (failures.length > 0) {
  console.error(`gen-liveness-constants.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`gen-liveness-constants.test: all ${passed} checks passed`);
