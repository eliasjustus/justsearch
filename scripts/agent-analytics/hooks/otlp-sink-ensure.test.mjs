/**
 * Tempdoc 743 — unit tests for otlp-sink-ensure's output-path resolution.
 *
 * The hook's only pure, spawn-free surface is the main-checkout-rooted output
 * path it computes at module load (`SINK_OUT_DIR`, derived from `mainRepoRoot`).
 * `startSink()`/`main()` shell out to `spawn()` and a live socket probe, so they
 * are deliberately NOT exercised here (would require a real child process /
 * port); this test instead proves the defect this fix closes: the sink's
 * `--out` argument must resolve to the MAIN checkout regardless of which
 * worktree the hook runs from, never a worktree-relative `tmp/` (tempdoc 743 —
 * a worktree's `tmp/` is ephemeral, deleted at teardown).
 *
 * Run with: `node scripts/agent-analytics/hooks/otlp-sink-ensure.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import path from 'node:path';
import { mainRepoRoot, SINK_OUT_DIR, SINK_SCRIPT, SINK_PORT } from './otlp-sink-ensure.mjs';

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

run('mainRepoRoot is absolute', () => {
  assert.ok(path.isAbsolute(mainRepoRoot), `expected absolute path, got ${mainRepoRoot}`);
});

run('SINK_OUT_DIR is absolute (never a relative default)', () => {
  assert.ok(path.isAbsolute(SINK_OUT_DIR), `expected absolute path, got ${SINK_OUT_DIR}`);
});

run('SINK_OUT_DIR is rooted under mainRepoRoot, not the invoking worktree', () => {
  const rel = path.relative(mainRepoRoot, SINK_OUT_DIR);
  assert.ok(
    !rel.startsWith('..') && !path.isAbsolute(rel),
    `expected SINK_OUT_DIR under mainRepoRoot; mainRepoRoot=${mainRepoRoot} SINK_OUT_DIR=${SINK_OUT_DIR}`
  );
});

run('SINK_OUT_DIR ends with tmp/agent-telemetry/otlp', () => {
  const expected = path.join('tmp', 'agent-telemetry', 'otlp');
  assert.ok(
    SINK_OUT_DIR.endsWith(expected),
    `expected SINK_OUT_DIR to end with ${expected}, got ${SINK_OUT_DIR}`
  );
});

run('SINK_OUT_DIR does not resolve against a worktree tmp/ dir', () => {
  // The defect this fix closes: `.claude/worktrees/<name>/tmp/...` must never
  // appear in the resolved sink output path.
  assert.ok(
    !SINK_OUT_DIR.includes(path.join('.claude', 'worktrees')),
    `SINK_OUT_DIR must not be worktree-rooted, got ${SINK_OUT_DIR}`
  );
});

run('SINK_SCRIPT and SINK_PORT are still defined (no accidental removal)', () => {
  assert.ok(SINK_SCRIPT && SINK_SCRIPT.endsWith('otlp-sink.py'));
  assert.equal(SINK_PORT, 4318);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`otlp-sink-ensure.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`otlp-sink-ensure.test: all ${passed} checks passed`);
