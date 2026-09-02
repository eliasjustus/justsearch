#!/usr/bin/env node
/**
 * Regression pin for `divergentInFlightCollisions` + `orphanChangesetDeclarations`
 * (tempdoc 884 PR 2 review response; extended by the wave-1 kernel-hygiene residue PR).
 *
 * The rule has always meant to allow "a single worktree's own multi-file batch" and flag only
 * "two or more DISTINCT worktrees each introducing a different basename for the same number"
 * (the 553-canonical vs 553-code-duplication-audit case that motivated it).
 *
 * 884 fixed the label granularity (a changeset's label carries its gate, so one agent authoring
 * changesets for two gates read as two claimants). The residue pass finished the job: a CHANGESET
 * is not a claimant at all. Its number is dictated by its `tempdoc:` frontmatter, several per
 * tempdoc is the standing convention (`main` carries four `885-*`), and two worktrees each writing
 * one produces two different basenames that merge cleanly — there is nothing to renumber, and
 * renumbering would make the filename contradict the frontmatter the loader enforces.
 *
 * What that exemption trades for is `orphanChangesetDeclarations`: once a changeset cannot collide
 * by number, the check that matters is whether the tempdoc it points at exists.
 *
 * These checks pin BOTH directions, because the fix loosens a collision rule and the failure that
 * matters is the one that stops firing.
 *
 * Run: node scripts/ci/test-tempdoc-scan.mjs   (exit 0 = pass, 1 = fail)
 */

import assert from 'node:assert/strict';
import {
  divergentInFlightCollisions,
  orphanChangesetDeclarations,
  tempdocNumbers,
} from './lib/tempdoc-scan.mjs';

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

check('a basename already on origin is not an in-flight claim', () => {
  const c = claims([
    ['249', '249-findings-a.md', ['origin']],
    ['249', '249-findings-b.md', ['origin']],
  ]);
  assert.deepEqual(divergentInFlightCollisions(c), []);
});

check('a tempdoc collision is NOT masked by a changeset sharing the number', () => {
  // The changeset must be invisible to the rule without also hiding the two real tempdocs.
  const c = claims([
    ['553', '553-canonical-search-execution-record.md', ['worktree:lane-A']],
    ['553', '553-code-duplication-audit.md', ['worktree:548-followups']],
    ['553', '553-some-gate-declaration.md', ['worktree:lane-A:gates/ts-any']],
  ]);
  assert.equal(divergentInFlightCollisions(c).length, 1);
});

// --- changesets are not claimants (the residue fix) --------------------------------------------

check('two different worktrees, changesets for the SAME existing tempdoc -> NOT a collision', () => {
  // `main` already carries four `885-*` changesets; two lanes each adding one is the convention,
  // not a collision. Both files merge; there is nothing to renumber.
  const c = claims([
    ['885', '885-decision-review-lane-c.md', ['origin']],
    ['885', '885-nrt-cadence-keys.md', ['worktree:lane-C:gates/config-surface']],
    ['885', '885-extraction-pool-keys.md', ['worktree:resid-kernel:gates/config-surface']],
  ]);
  assert.deepEqual(divergentInFlightCollisions(c), []);
});

check('two different worktrees, changesets across DIFFERENT gates -> NOT a collision', () => {
  const c = claims([
    ['884', '884-decision-review-lane-b.md', ['origin']],
    ['884', '884-a.md', ['worktree:lane-A:gates/ts-any']],
    ['884', '884-b.md', ['worktree:lane-C:gates/dead-code']],
  ]);
  assert.deepEqual(divergentInFlightCollisions(c), []);
});

// --- what the exemption trades for -------------------------------------------------------------

check('tempdocNumbers counts only tempdoc-labelled claims', () => {
  const c = claims([
    ['885', '885-lane-c.md', ['origin']],
    ['999', '999-only-a-changeset.md', ['worktree:lane-A:gates/ts-any']],
  ]);
  const n = tempdocNumbers(c);
  assert.ok(n.has('885'), '885 has a real tempdoc');
  assert.ok(!n.has('999'), 'a number claimed only by a changeset is not a tempdoc number');
});

check('a changeset whose `tempdoc:` names no existing tempdoc -> reported as an orphan', () => {
  const c = claims([['885', '885-lane-c.md', ['origin']]]);
  const orphans = orphanChangesetDeclarations(
    [
      { number: '885', basename: '885-ok.md', label: 'worktree:x:gates/ts-any', path: 'gates/ts-any/.changesets/885-ok.md', declaredTempdoc: '885' },
      { number: '999', basename: '999-bad.md', label: 'worktree:x:gates/ts-any', path: 'gates/ts-any/.changesets/999-bad.md', declaredTempdoc: '999' },
    ],
    tempdocNumbers(c),
  );
  assert.equal(orphans.length, 1, 'exactly the changeset pointing at a nonexistent tempdoc');
  assert.equal(orphans[0].declaredTempdoc, '999');
  assert.equal(orphans[0].path, 'gates/ts-any/.changesets/999-bad.md');
});

check('a changeset declaring a tempdoc number different from its FILENAME is judged on the frontmatter', () => {
  // `563-retire-independent-review-gate.md` really does carry `tempdoc: 530`. The frontmatter is
  // the pointer; the filename is a label.
  const c = claims([['530', '530-class-size-ratchet-automation.md', ['origin']]]);
  const orphans = orphanChangesetDeclarations(
    [{ number: '563', basename: '563-retire.md', label: 'worktree:x:gates/prose-tier-register', path: 'p', declaredTempdoc: '530' }],
    tempdocNumbers(c),
  );
  assert.deepEqual(orphans, []);
});

check('a changeset carrying `adr:` instead of `tempdoc:` is not an orphan', () => {
  const orphans = orphanChangesetDeclarations(
    [{ number: '0', basename: 'vitest-drift.md', label: 'worktree:x:gates/npm-audit', path: 'p', declaredTempdoc: null }],
    new Set(),
  );
  assert.deepEqual(orphans, []);
});

if (failures > 0) {
  console.error(`\n${failures} failure(s).`);
  process.exit(1);
}
console.log('\nall tempdoc-scan checks passed.');
