#!/usr/bin/env node
/**
 * Regression pin for `divergentInFlightCollisions` (tempdoc 884 PR 2 review response).
 *
 * The rule has always meant to allow "a single worktree's own multi-file batch" and flag only
 * "two or more DISTINCT worktrees each introducing a different basename for the same number"
 * (the 553-canonical vs 553-code-duplication-audit case that motivated it).
 *
 * A changeset's label carries its gate — `worktree:lane-B:gates/ts-any` — so one agent authoring
 * changesets for two gates under one tempdoc number read as two claimants and tripped the rule.
 * That number is not a free choice: the changeset frontmatter REQUIRES `tempdoc: N`, and the
 * filename convention is `<N>-<slug>.md`, so two gates touched by one tempdoc must share it.
 *
 * These checks pin BOTH directions, because the fix loosens a collision rule and the failure that
 * matters is the one that stops firing.
 *
 * Run: node scripts/ci/test-tempdoc-scan.mjs   (exit 0 = pass, 1 = fail)
 */

import assert from 'node:assert/strict';
import { divergentInFlightCollisions } from './lib/tempdoc-scan.mjs';

let failures = 0;
function check(name, fn) {
  try {
    fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    failures++;
    console.error(`FAIL ${name}: ${e.message}`);
  }
}

/** Build the scanner's `number -> Map<basename, Set<label>>` shape. */
function claims(entries) {
  const m = new Map();
  for (const [number, basename, labels] of entries) {
    if (!m.has(number)) m.set(number, new Map());
    m.get(number).set(basename, new Set(labels));
  }
  return m;
}

// --- the case the fix allows -------------------------------------------------------------------

check('one worktree, two gates, one tempdoc number -> NOT a collision', () => {
  const c = claims([
    ['884', '884-ts-any-prose.md', ['worktree:lane-B:gates/ts-any']],
    ['884', '884-dead-code-drift.md', ['worktree:lane-B:gates/dead-code']],
  ]);
  assert.deepEqual(divergentInFlightCollisions(c), []);
});

check('one worktree, a tempdoc plus its own changeset -> NOT a collision', () => {
  const c = claims([
    ['884', '884-decision-review-lane-b.md', ['worktree:lane-B']],
    ['884', '884-dead-code-drift.md', ['worktree:lane-B:gates/dead-code']],
  ]);
  assert.deepEqual(divergentInFlightCollisions(c), []);
});

// --- the cases the fix must NOT stop catching --------------------------------------------------

check('two DIFFERENT worktrees, different basenames -> still a collision (the 553 case)', () => {
  const c = claims([
    ['553', '553-canonical-search-execution-record.md', ['worktree:lane-A']],
    ['553', '553-code-duplication-audit.md', ['worktree:548-followups']],
  ]);
  const got = divergentInFlightCollisions(c);
  assert.equal(got.length, 1, 'a genuine cross-worktree collision must still fire');
  assert.equal(got[0].number, '553');
});

check('two different worktrees colliding via CHANGESETS in the same gate -> still a collision', () => {
  const c = claims([
    ['600', '600-a.md', ['worktree:lane-A:gates/ts-any']],
    ['600', '600-b.md', ['worktree:lane-C:gates/ts-any']],
  ]);
  assert.equal(divergentInFlightCollisions(c).length, 1);
});

check('two different worktrees colliding across DIFFERENT gates -> still a collision', () => {
  // The stripped label must not collapse distinct worktrees into one.
  const c = claims([
    ['601', '601-a.md', ['worktree:lane-A:gates/ts-any']],
    ['601', '601-b.md', ['worktree:lane-C:gates/dead-code']],
  ]);
  assert.equal(divergentInFlightCollisions(c).length, 1);
});

check('a basename already on origin is not an in-flight claim', () => {
  const c = claims([
    ['249', '249-findings-a.md', ['origin']],
    ['249', '249-findings-b.md', ['origin']],
  ]);
  assert.deepEqual(divergentInFlightCollisions(c), []);
});

if (failures > 0) {
  console.error(`\n${failures} failure(s).`);
  process.exit(1);
}
console.log('\nall tempdoc-scan checks passed.');
