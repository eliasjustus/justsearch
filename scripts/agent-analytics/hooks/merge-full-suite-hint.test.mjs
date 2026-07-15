/**
 * Tempdoc 734 — unit tests for merge-full-suite-hint's pure `isGitMerge`
 * classifier.
 *
 * Precision matters both ways: missing a real `git merge` invocation means
 * the fifth-conflict class this hook exists to warn about goes un-nudged
 * again; firing on non-merge commands (or on `--abort`, which undoes a merge)
 * makes the hint noise an agent learns to ignore.
 *
 * Run with: `node scripts/agent-analytics/hooks/merge-full-suite-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { isGitMerge, HINT } from './merge-full-suite-hint.mjs';

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

// --- isGitMerge: positive cases ---
run('plain git merge is detected', () => {
  assert.equal(isGitMerge('git merge origin/main'), true);
});
run('git merge with --no-ff is detected', () => {
  assert.equal(isGitMerge('git merge --no-ff worktree-release-asset-set'), true);
});
run('git -C <path> merge is detected', () => {
  assert.equal(isGitMerge('git -C .claude/worktrees/x merge origin/main'), true);
});
run('git merge --continue (finalizing after manual resolution) is detected', () => {
  assert.equal(isGitMerge('git merge --continue'), true);
});
run('bare git merge is detected', () => {
  assert.equal(isGitMerge('git merge'), true);
});
run('git merge chained with a follow-up command is detected', () => {
  assert.equal(isGitMerge('git merge origin/main && git push'), true);
});

// --- isGitMerge: negative cases ---
run('git merge --abort is NOT detected (undoes, does not complete, a merge)', () => {
  assert.equal(isGitMerge('git merge --abort'), false);
});
run('git log mentioning merge is NOT a merge', () => {
  assert.equal(isGitMerge('git log --grep=merge'), false);
});
run('git merge-base is NOT a merge (different subcommand)', () => {
  assert.equal(isGitMerge('git merge-base origin/main HEAD'), false);
});
run('non-git / empty is not a merge', () => {
  assert.equal(isGitMerge('npm run merge'), false);
  assert.equal(isGitMerge(''), false);
  assert.equal(isGitMerge(undefined), false);
});

// --- HINT content sanity (the bite spec's stdoutIncludes token must survive) ---
run('HINT names the enforced principle', () => {
  assert.ok(HINT.includes('subset-isnt-the-suite'));
});
run('HINT tells the agent to run the FULL suite, not a subset', () => {
  assert.ok(HINT.includes('FULL suite') || HINT.includes('FULL test suite'));
});

// --- Report ---
if (failures.length > 0) {
  console.error(`merge-full-suite-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`merge-full-suite-hint.test: all ${passed} checks passed`);
