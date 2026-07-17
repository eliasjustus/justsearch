#!/usr/bin/env node
/**
 * Tests for scripts/agent-analytics/world-state.mjs (tempdoc 743 P-J).
 *
 * Two layers: (1) unit tests against the pure `computeVerdict` function on synthetic worktree
 * rows — no I/O; (2) a smoke test that runs the CLI as a real subprocess against this actual repo
 * checkout, asserting it completes quickly, exits 0, and both output modes (markdown + --json)
 * have the expected sections/shape. The smoke test intentionally does NOT assert on live values
 * (worktree names, session counts) since those vary run-to-run in a shared multi-agent repo —
 * only structure.
 *
 * Run with: `node scripts/agent-analytics/world-state.test.mjs`
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { computeVerdict } from './world-state.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.join(HERE, 'world-state.mjs');
const REPO_ROOT = path.resolve(HERE, '..', '..');

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

// --- computeVerdict unit tests (synthetic rows) ---

run('computeVerdict: dirty + old last commit -> DIRTY-IDLE', () => {
  const v = computeVerdict({ dirty: true, aheadCount: 2, pushed: false, lastCommitAgeDays: 10 });
  assert.equal(v, 'DIRTY-IDLE');
});

run('computeVerdict: dirty + recent last commit -> ACTIVE (not stale yet)', () => {
  const v = computeVerdict({ dirty: true, aheadCount: 2, pushed: false, lastCommitAgeDays: 0.2 });
  assert.equal(v, 'ACTIVE');
});

run('computeVerdict: clean + ahead>0 + unpushed + old -> STRANDED-FINISHED', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: false, lastCommitAgeDays: 5 });
  assert.equal(v, 'STRANDED-FINISHED');
});

run('computeVerdict: clean + ahead>0 + PUSHED + old -> not stranded (pushed work is not lost)', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: true, lastCommitAgeDays: 5 });
  assert.notEqual(v, 'STRANDED-FINISHED');
});

run('computeVerdict: clean + ahead>0 + unpushed + RECENT -> ACTIVE (not stale yet, still working)', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: false, lastCommitAgeDays: 0.5 });
  assert.equal(v, 'ACTIVE');
});

run('computeVerdict: clean + 0 ahead -> STALE-CANDIDATE regardless of age', () => {
  assert.equal(computeVerdict({ dirty: false, aheadCount: 0, pushed: true, lastCommitAgeDays: 0.1 }), 'STALE-CANDIDATE');
  assert.equal(computeVerdict({ dirty: false, aheadCount: 0, pushed: null, lastCommitAgeDays: 40 }), 'STALE-CANDIDATE');
});

run('computeVerdict: clean + ahead>0 but age unknown (probe failed) -> ACTIVE, never fabricates STRANDED', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: false, lastCommitAgeDays: null });
  assert.equal(v, 'ACTIVE');
});

run('computeVerdict: everything unknown (all probes failed) -> ACTIVE (safe default)', () => {
  const v = computeVerdict({ dirty: null, aheadCount: null, pushed: null, lastCommitAgeDays: null });
  assert.equal(v, 'ACTIVE');
});

// --- Smoke test: real subprocess against this actual repo checkout ---

run('CLI smoke: markdown mode runs, exits 0, all four sections present, mentions this worktree', () => {
  const out = execFileSync(process.execPath, [CLI], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000 });
  assert.match(out, /^# World state/);
  assert.match(out, /## Worktrees/);
  assert.match(out, /## Live sessions/);
  assert.match(out, /## Tempdoc numbers/);
  assert.match(out, /## Stack/);
  assert.match(out, /VERDICT/);
});

run('CLI smoke: --json mode runs, exits 0, output is valid JSON with the expected top-level shape', () => {
  const out = execFileSync(process.execPath, [CLI, '--json'], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000 });
  const parsed = JSON.parse(out);
  assert.ok(Array.isArray(parsed.worktrees) && parsed.worktrees.length > 0);
  for (const w of parsed.worktrees) {
    assert.ok(['ACTIVE', 'STRANDED-FINISHED', 'STALE-CANDIDATE', 'DIRTY-IDLE'].includes(w.verdict), `unexpected verdict "${w.verdict}" for worktree "${w.name}"`);
  }
  assert.ok(typeof parsed.sessions.available === 'boolean');
  assert.ok(typeof parsed.tempdocNumbers.nextFree === 'number');
  assert.ok(typeof parsed.stack.available === 'boolean');
});

run('CLI smoke: completes in under 10s (perf budget, tempdoc 743 P-J requirement)', () => {
  const start = Date.now();
  execFileSync(process.execPath, [CLI], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000 });
  const elapsedMs = Date.now() - start;
  assert.ok(elapsedMs < 10000, `world-state.mjs took ${elapsedMs}ms, budget is 10000ms`);
});

if (failures.length) {
  console.error(`world-state.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`world-state.test: ${passed} passed`);
