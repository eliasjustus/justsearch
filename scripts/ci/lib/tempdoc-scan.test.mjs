#!/usr/bin/env node
/**
 * Unit tests for scripts/ci/lib/tempdoc-scan.mjs (tempdoc 743 P-J extraction).
 *
 * Exercises collectClaims/divergentInFlightCollisions/isNumberFree/nextFreeNumber against
 * disposable temp-dir fixtures standing in for worktrees — never the real repo's worktrees.
 * `collectClaims` shells out to `git worktree list --porcelain` and `git ls-tree origin/<branch>`
 * from the given `cwd`; a bare/uninitialized fixture dir makes both no-ops (git exits non-zero,
 * `git()` swallows it and returns ''), so `scanDir`-equivalent behavior is exercised directly by
 * building claims maps by hand for the predicate tests, and `collectClaims` itself is exercised
 * end-to-end against this real repo checkout (whatever it currently claims) plus a single-worktree
 * sanity check.
 *
 * Run with: `node scripts/ci/lib/tempdoc-scan.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  collectClaims,
  divergentInFlightCollisions,
  isNumberFree,
  nextFreeNumber,
} from './tempdoc-scan.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..', '..');

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

/** Build a claims map directly (number -> Map<basename, Set<label>>) without touching disk/git. */
function buildClaims(entries) {
  const claims = new Map();
  for (const [number, basename, ...labels] of entries) {
    if (!claims.has(number)) claims.set(number, new Map());
    const byName = claims.get(number);
    if (!byName.has(basename)) byName.set(basename, new Set());
    for (const l of labels) byName.get(basename).add(l);
  }
  return claims;
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'tempdoc-scan-test-'));

try {
  // --- divergentInFlightCollisions: the R1 blind-spot fixture ---
  // Number 700 has basename A claimed ONLY on origin, and a DIFFERENT basename B claimed by a
  // single in-flight worktree. The merge-gate rule stays silent (only one non-origin claimant —
  // never reaches the "2+ distinct in-flight basenames" threshold) but isNumberFree must say
  // false: the number is not actually free, origin already claimed it under a different name.
  run('R1 blind-spot fixture: divergentInFlightCollisions silent, isNumberFree(N)=false', () => {
    const claims = buildClaims([
      ['700', '700-origin-doc.md', 'origin'],
      ['700', '700-inflight-doc.md', 'worktree:solo'],
    ]);
    const collisions = divergentInFlightCollisions(claims);
    assert.deepEqual(collisions, [], 'merge-gate rule must stay silent on this fixture (documented blind spot)');
    assert.equal(isNumberFree(claims, 700), false, 'a number claimed by origin under a different basename is NOT free');
    assert.equal(isNumberFree(claims, '700'), false, 'string form of the same number must also read as not-free');
  });

  // --- divergentInFlightCollisions: genuine divergent in-flight case ---
  run('divergent in-flight collision: two worktrees, two different basenames, neither on origin -> flagged', () => {
    const claims = buildClaims([
      ['553', '553-canonical-search-execution-record.md', 'worktree:548-followups'],
      ['553', '553-code-duplication-audit.md', 'worktree:other-agent'],
    ]);
    const collisions = divergentInFlightCollisions(claims);
    assert.equal(collisions.length, 1);
    assert.equal(collisions[0].number, '553');
    assert.match(collisions[0].detail, /553-canonical-search-execution-record\.md/);
    assert.match(collisions[0].detail, /553-code-duplication-audit\.md/);
  });

  // --- single-worktree batch: no collision ---
  run('single-worktree multi-file batch (same number, two basenames, one worktree) -> no collision', () => {
    const claims = buildClaims([
      ['249', '249-findings-a.md', 'worktree:solo'],
      ['249', '249-findings-b.md', 'worktree:solo'],
    ]);
    assert.deepEqual(divergentInFlightCollisions(claims), []);
  });

  run('identical basename across worktrees (same doc checked out in several places) -> no collision', () => {
    const claims = buildClaims([
      ['600', '600-shared-doc.md', 'worktree:a', 'worktree:b'],
    ]);
    assert.deepEqual(divergentInFlightCollisions(claims), []);
  });

  run('a basename already on origin plus the SAME basename in-flight elsewhere -> no collision (not divergent)', () => {
    const claims = buildClaims([
      ['680', '680-doc.md', 'origin'],
      ['680', '680-doc.md', 'worktree:a'],
    ]);
    assert.deepEqual(divergentInFlightCollisions(claims), []);
  });

  // --- isNumberFree ---
  run('isNumberFree: true for a number with no claim anywhere', () => {
    const claims = buildClaims([['700', '700-doc.md', 'origin']]);
    assert.equal(isNumberFree(claims, 701), true);
    assert.equal(isNumberFree(claims, '999999'), true);
  });
  run('isNumberFree: false for any claimed number, in-flight-only or origin-only', () => {
    const claims = buildClaims([
      ['700', '700-a.md', 'origin'],
      ['701', '701-b.md', 'worktree:solo'],
    ]);
    assert.equal(isNumberFree(claims, 700), false);
    assert.equal(isNumberFree(claims, 701), false);
  });
  run('isNumberFree: true against an empty claims map', () => {
    assert.equal(isNumberFree(new Map(), 1), true);
  });

  // --- nextFreeNumber ---
  run('nextFreeNumber: one past the highest claimed number across all sources', () => {
    const claims = buildClaims([
      ['700', '700-a.md', 'origin'],
      ['750', '750-b.md', 'worktree:solo'],
      ['12', '12-old.md', 'origin'],
    ]);
    assert.equal(nextFreeNumber(claims), 751);
  });
  run('nextFreeNumber: 1 for an empty claims map', () => {
    assert.equal(nextFreeNumber(new Map()), 1);
  });

  // --- collectClaims: end-to-end against a real (but tiny, disposable) worktree-like fixture ---
  run('collectClaims: single fixture "worktree" (no git repo) scans its own docs/tempdocs and reports worktreeCount=1', () => {
    const fixtureRoot = fs.mkdtempSync(path.join(tmp, 'fixture-'));
    fs.mkdirSync(path.join(fixtureRoot, 'docs', 'tempdocs'), { recursive: true });
    fs.writeFileSync(path.join(fixtureRoot, 'docs', 'tempdocs', '900-fixture-doc.md'), '# fixture\n', 'utf8');
    // No `.git` here, so `git worktree list --porcelain` run with this cwd fails (git() swallows
    // the error and returns ''), and collectClaims falls back to treating `cwd` itself as the sole
    // worktree to scan — exercising the real fallback path, not a mock.
    const { claims, worktreeCount } = collectClaims({ cwd: fixtureRoot });
    assert.equal(worktreeCount, 1);
    assert.ok(claims.has('900'), 'the fixture doc\'s number must be recorded');
    const byName = claims.get('900');
    assert.ok(byName.has('900-fixture-doc.md'));
  });

  // --- collectClaims: end-to-end against the real repo checkout (sanity, not golden-parity —
  // that's covered by the separate CLI parity check the orchestrator runs against the golden
  // capture). Just asserts the shape is sane and doesn't throw. ---
  run('collectClaims: real repo checkout returns a non-empty, well-shaped claims map', () => {
    const { claims, worktreeCount, defaultBranch } = collectClaims({ cwd: REPO_ROOT });
    assert.ok(claims.size > 0, 'the real repo has tempdocs; claims must be non-empty');
    assert.ok(worktreeCount >= 1);
    assert.equal(typeof defaultBranch, 'string');
    for (const [number, byName] of claims) {
      assert.match(number, /^\d+$/, `claim key "${number}" must be a bare digit string`);
      assert.ok(byName instanceof Map);
      for (const labels of byName.values()) assert.ok(labels instanceof Set);
    }
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`tempdoc-scan.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`tempdoc-scan.test: ${passed} passed`);
