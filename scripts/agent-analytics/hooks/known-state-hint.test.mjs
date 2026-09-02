/**
 * Tempdoc 680 — precision tests for known-state-hint.mjs (expected-state delivery).
 * Modeled on pipe-mask-hint.test.mjs: a firing/non-firing corpus over the pure
 * matcher, plus a sanity load of the real baseline file.
 * Run: `node scripts/agent-analytics/hooks/known-state-hint.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { matchExpectedState, renderHint, EXPECTED_STATE_FILE } from './known-state-hint.mjs';
import { repoRoot } from '../lib/hook-base.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const ENTRIES = [
  { id: 'typecheck-pin', match: ['npm .*typecheck'], claim: 'typecheck is red (TS5101)' },
  { id: 'gate-pin', match: ['check-theme-token-closure'], claim: 'gate red on main' },
  { id: 'pytest-pin', match: ['pytest\\b'], claim: 'two known-red tests' },
  { id: 'broken-regex-pin', match: ['[unclosed'], claim: 'must not crash the matcher' },
];

// --- firing corpus ---
run('fires on npm run typecheck', () => {
  assert.equal(matchExpectedState('npm run typecheck', ENTRIES)[0].id, 'typecheck-pin');
});
run('fires on npm --prefix modules/ui-web run typecheck', () => {
  assert.equal(matchExpectedState('npm --prefix modules/ui-web run typecheck', ENTRIES)[0].id, 'typecheck-pin');
});
run('fires on node scripts/ci/check-theme-token-closure.mjs', () => {
  assert.equal(matchExpectedState('node scripts/ci/check-theme-token-closure.mjs', ENTRIES)[0].id, 'gate-pin');
});
run('fires on the agent-analytics suite and a single *.test.mjs (886 PR 5b: CI runs run-all-tests.mjs)', () => {
  const suiteEntries = [{ id: 'suite-pin', match: ['run-all-tests\\.mjs', '861-w5-agent-spawn-sweep'] }];
  assert.equal(matchExpectedState('node scripts/agent-analytics/run-all-tests.mjs', suiteEntries)[0].id, 'suite-pin');
  assert.equal(matchExpectedState('node scripts/agent-analytics/861-w5-agent-spawn-sweep.test.mjs', suiteEntries)[0].id, 'suite-pin');
  assert.deepEqual(matchExpectedState('node scripts/agent-analytics/cache-efficiency.mjs', suiteEntries), []);
});
run('fires on python -m pytest scripts/jseval', () => {
  assert.equal(matchExpectedState('python -m pytest scripts/jseval/tests', ENTRIES)[0].id, 'pytest-pin');
});

// --- non-firing corpus (precision) ---
run('silent on git status', () => {
  assert.equal(matchExpectedState('git status', ENTRIES).length, 0);
});
run('silent on npm install', () => {
  assert.equal(matchExpectedState('npm install --prefix modules/ui-web', ENTRIES).length, 0);
});
run('silent on an unrelated node script', () => {
  assert.equal(matchExpectedState('node scripts/dev/prepare-worktree.cjs', ENTRIES).length, 0);
});
run('silent on empty/undefined command', () => {
  assert.equal(matchExpectedState('', ENTRIES).length, 0);
  assert.equal(matchExpectedState(undefined, ENTRIES).length, 0);
});
run('an invalid regex in one entry never crashes or matches', () => {
  const r = matchExpectedState('npm run typecheck', ENTRIES);
  assert.ok(!r.some((e) => e.id === 'broken-regex-pin'));
});

// --- rendering ---
// 872: no silent cap — a hidden fifth pin is a hint that lies by omission exactly when
// the baseline has grown enough to matter. reviewBy is rendered so the reader sees the exit.
run('renderHint lists EVERY matched claim and shows reviewBy', () => {
  const many = Array.from({ length: 6 }, (_, i) => ({ id: `p${i}`, claim: `claim ${i}`, reviewBy: i === 5 ? '2026-09-30' : undefined }));
  const text = renderHint(many);
  assert.match(text, /\[p0\] claim 0/);
  assert.match(text, /\[p4\] claim 4/);
  assert.match(text, /\[p5\] claim 5 \(review by 2026-09-30\)/);
});

// --- the real baseline file loads and its regexes compile ---
run('real expected-state.v1.json parses and all match regexes compile', () => {
  const data = JSON.parse(fs.readFileSync(path.join(repoRoot, EXPECTED_STATE_FILE), 'utf8'));
  assert.ok(Array.isArray(data.entries) && data.entries.length > 0);
  for (const e of data.entries) {
    assert.ok(e.id && e.claim && Array.isArray(e.match), `entry ${e.id} shape`);
    assert.ok(e.exitProbe || e.reviewBy, `entry ${e.id} carries an exit discipline (exitProbe or reviewBy)`);
    for (const p of e.match) new RegExp(p, 'i');
  }
});

// Nothing reads an unrecognised key, so a typo in one is invisible: `exitProbeRetired` misspelled
// reads as a pin that simply has no note, and — worse — `exitProbe` misspelled reads as a pin with
// no probe, which silently downgrades its exit discipline to reviewBy alone.
const ALLOWED_PIN_KEYS = new Set([
  'id', 'match', 'claim', 'evidence', 'added', 'reviewBy', 'exitProbe',
  // Prose keys recording why a probe was removed or reshaped (PR #604).
  'exitProbeRetired', 'exitProbeNote',
  // `exitProbeOmitted`: why this pin never had an exitProbe, for a red no probe can OBSERVE -- one
  // needing a served frontend, or one that passes in isolation so a probe could only report a false
  // GONE. `fixOwner`: the lane or owner whose fix retires the pin, so `reviewBy` is a backstop
  // rather than the only exit. Both tempdoc 910.
  'exitProbeOmitted', 'fixOwner',
]);
run('real baseline: no entry carries an unrecognised key', () => {
  const data = JSON.parse(fs.readFileSync(path.join(repoRoot, EXPECTED_STATE_FILE), 'utf8'));
  for (const e of data.entries) {
    for (const key of Object.keys(e)) {
      assert.ok(
        ALLOWED_PIN_KEYS.has(key),
        `entry ${e.id} has unrecognised key \`${key}\` — add it to ALLOWED_PIN_KEYS if intended, `
          + 'or fix the typo; an unread key is a silently ignored one',
      );
    }
  }
});

run('the unrecognised-key check actually fires', () => {
  // Pinning the pin: a schema check that cannot fail is the shape this whole PR is about.
  const typo = { id: 'x', match: ['y'], claim: 'z', exitProbeRetried: 'typo' };
  const bad = Object.keys(typo).filter((k) => !ALLOWED_PIN_KEYS.has(k));
  assert.deepEqual(bad, ['exitProbeRetried']);
});
run('real baseline: every entry is reachable by its own exitProbe, and unambiguously so', () => {
  // Derived from the live entries rather than naming specific pins. Naming them made this test
  // fail the moment a pin was legitimately RETIRED (its red got fixed) — a green-blocking tripwire
  // on exactly the outcome the pins exist to reach. The property that actually matters survives
  // retirement: a pin whose own exitProbe does not route back to it can never fire for the command
  // it describes, and two pins matching one command means the hint reports an ambiguous claim.
  const { entries } = JSON.parse(fs.readFileSync(path.join(repoRoot, EXPECTED_STATE_FILE), 'utf8'));
  assert.ok(entries.length > 0, 'baseline has no entries — this test would assert nothing');
  for (const e of entries) {
    if (!e.exitProbe) continue; // reviewBy-only pins have no command to route
    const probe = e.exitProbe.replace(/^slow:\s*/, '');
    const hits = matchExpectedState(probe, entries);
    assert.deepEqual(hits.map((h) => h.id), [e.id], `exitProbe for ${e.id} must match exactly ${e.id}`);
  }
  assert.equal(matchExpectedState('git commit -m x', entries).length, 0);
});

if (failures.length) {
  console.error(`known-state-hint.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`known-state-hint.test: ${passed} passed`);
