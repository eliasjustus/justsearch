/**
 * Tempdoc 653 axis-2 — unit tests for docs-granularity-hint's pure classifiers.
 *
 * The branch-diff computation is git-dependent and fail-open; what is worth
 * pinning is the path-classification + push detection that decide whether the
 * non-blocking hint fires. A wrong glob here would either nag on legitimate
 * ride-along/canonical work or stay silent on the tempdoc-only case it exists
 * to catch.
 *
 * Run with: `node scripts/agent-analytics/hooks/docs-granularity-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { isGitPush, isArchaeologyOnly } from './docs-granularity-hint.mjs';

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

// --- isGitPush ---
run('plain git push is detected', () => {
  assert.equal(isGitPush('git push origin codex/foo'), true);
});
run('git -C <path> push is detected', () => {
  assert.equal(isGitPush('git -C .claude/worktrees/x push -u origin b'), true);
});
run('bare git push is detected', () => {
  assert.equal(isGitPush('git push'), true);
});
run('git push chained before gh pr create is detected', () => {
  assert.equal(isGitPush('git push origin b && gh pr create'), true);
});
run('git log mentioning push is NOT a push', () => {
  assert.equal(isGitPush('git log --grep=push'), false);
});
run('non-git / empty is not a push', () => {
  assert.equal(isGitPush('npm run push'), false);
  assert.equal(isGitPush(''), false);
  assert.equal(isGitPush(undefined), false);
});

// --- isArchaeologyOnly ---
run('tempdoc-only branch is archaeology-only', () => {
  assert.equal(isArchaeologyOnly(['docs/tempdocs/653-x.md']), true);
});
run('observations-only branch is archaeology-only', () => {
  assert.equal(
    isArchaeologyOnly(['docs/observations.d/abc.md', 'docs/observations.md']),
    true,
  );
});
run('tempdoc + code is NOT archaeology-only (ride-along)', () => {
  assert.equal(
    isArchaeologyOnly(['docs/tempdocs/653-x.md', 'modules/ui/src/Foo.java']),
    false,
  );
});
run('canonical-doc-only branch is NOT archaeology-only (durable standalone)', () => {
  assert.equal(isArchaeologyOnly(['docs/decisions/0045-x.md']), false);
  assert.equal(isArchaeologyOnly(['docs/explanation/27-x.md']), false);
});
run('empty / blank file list is not archaeology-only', () => {
  assert.equal(isArchaeologyOnly([]), false);
  assert.equal(isArchaeologyOnly(['', '  ']), false);
});
run('a path merely containing docs/tempdocs deeper down does not match', () => {
  // anchored at start: a code file that references the path is not a tempdoc
  assert.equal(isArchaeologyOnly(['scripts/x/docs/tempdocs/y.md']), false);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`docs-granularity-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`docs-granularity-hint.test: all ${passed} checks passed`);
