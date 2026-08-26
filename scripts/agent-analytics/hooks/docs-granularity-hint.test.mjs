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
import { isAbsolute } from 'node:path';
import {
  isGitPush,
  isArchaeologyOnly,
  isStandaloneNote,
  gitPushCwd,
} from './docs-granularity-hint.mjs';

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

// --- isGitPush: argument-position mentions are NOT pushes ---
// Regression cases mined verbatim from session transcripts, where the pre-fix
// whole-string regex fired the hint on commands that were not pushes at all.
// A hint that cries wolf gets discounted — these keep its signal true.
run('a "git push" inside a quoted --reason argument is NOT a push', () => {
  assert.equal(
    isGitPush(
      'node scripts/ci/check-always-loaded-budget.mjs --bump .claude/rules/branch-safety.md ' +
        '--reason "tempdoc 695: correct a stale claim that git push is unconditionally allowed on main"',
    ),
    false,
  );
});
run('a "git push" inside a commit message is NOT a push', () => {
  assert.equal(isGitPush('git commit -m "correct the stale git push allowed on main claim"'), false);
});
run('a "git push" inside a heredoc commit body is NOT a push', () => {
  const cmd = [
    "git commit -m \"$(cat <<'EOF'",
    'docs(rules): correct a stale claim',
    '',
    'git push to main is not unconditionally allowed — branch protection rejects it.',
    'EOF',
    ')"',
  ].join('\n');
  assert.equal(isGitPush(cmd), false);
});

// --- isGitPush: branch deletions publish no content ---
run('a branch-delete push is NOT a content push', () => {
  assert.equal(isGitPush('git push origin --delete worktree-662-mux docs/653-axis2-record'), false);
  assert.equal(isGitPush('git push origin :old-branch'), false);
  assert.equal(isGitPush('git push -d origin br'), false);
});
// The delete test keys on a colon in ARGUMENT position (` :branch`). A colon is
// also common in content pushes and in Windows paths, so pin that the deletion
// check does not swallow them — a false negative here would silence the hint on
// exactly the worktree pushes it exists to catch.
run('a colon in a refspec or Windows path is still a content push', () => {
  assert.equal(isGitPush('git push origin HEAD:refs/heads/foo'), true);
  assert.equal(isGitPush('git -C C:/Users/x/wt push -u origin br'), true);
});
run('a real push after a heredoc body is still detected', () => {
  const cmd = ["gh pr create --body \"$(cat <<'EOF'", 'body text', 'EOF', ')" && git push -u origin b'].join('\n');
  assert.equal(isGitPush(cmd), true);
});

// --- gitPushCwd: diff the tree being PUSHED ---
// The dominant real-world misfire: `git -C <worktree> push` diffed the main
// checkout (whatever `input.cwd` was) instead of the worktree being pushed, so
// the hint's claim contradicted the actual push content.
run('gitPushCwd extracts an absolute -C target', () => {
  assert.equal(
    gitPushCwd('git -C /f/js/.claude/worktrees/x push -u origin b', '/f/js'),
    '/f/js/.claude/worktrees/x',
  );
});
run('gitPushCwd resolves a relative -C target against the fallback', () => {
  // Platform-agnostic: assert the SEMANTIC (a relative -C becomes an absolute
  // path under the fallback), not a hardcoded path shape — on Windows a
  // POSIX-style fixture root would be drive-relative and resolve differently.
  const fallback = process.cwd();
  const out = gitPushCwd('git -C .claude/worktrees/x push -u origin b', fallback);
  assert.ok(isAbsolute(out), `expected an absolute path, got ${out}`);
  assert.ok(out.startsWith(fallback), `expected a path under ${fallback}, got ${out}`);
  assert.ok(
    out.replace(/\\/g, '/').endsWith('/.claude/worktrees/x'),
    `expected the -C target suffix, got ${out}`,
  );
});
run('gitPushCwd handles a quoted -C path with spaces', () => {
  assert.equal(
    gitPushCwd('git -C "/f/my repo/wt" push origin b', '/f/js'),
    '/f/my repo/wt',
  );
});
run('gitPushCwd follows a `cd <path> && git push` chain', () => {
  assert.equal(
    gitPushCwd('cd /f/js/.claude/worktrees/y && git push -u origin b', '/f/js'),
    '/f/js/.claude/worktrees/y',
  );
});
run('gitPushCwd falls back when the push names no directory', () => {
  assert.equal(gitPushCwd('git push -u origin b', '/f/js'), '/f/js');
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

// --- isStandaloneNote: the single-file threshold (tempdoc 739 §6) ---
// isArchaeologyOnly is necessary but not sufficient — the rule permits batches,
// and a batch is archaeology-only too. Only a LONE note is forbidden.
run('one tempdoc file alone IS a standalone note', () => {
  assert.equal(isStandaloneNote(['docs/tempdocs/739-x.md']), true);
});
run('one observation shard alone IS a standalone note', () => {
  assert.equal(isStandaloneNote(['docs/observations.d/abc.md']), true);
});
run('several tempdoc edits are a BATCH — the rule permits it, so no hint', () => {
  assert.equal(
    isStandaloneNote(['docs/tempdocs/712-x.md', 'docs/tempdocs/713-y.md']),
    false,
  );
});
run('a multi-file working-history batch is a BATCH, not a standalone note', () => {
  // Historical shape (the retired observations fold, tempdoc 872): several working-history
  // files in one push. The ARCHAEOLOGY prefix still classifies these paths, so the case stands.
  assert.equal(
    isStandaloneNote([
      'docs/observations.md',
      'docs/observations.d/4bd6a45f.md',
      'docs/observations.d/54d5b430.md',
      'docs/observations.d/a37ec555.md',
    ]),
    false,
  );
});
run('a lone note riding along with code is NOT standalone', () => {
  assert.equal(
    isStandaloneNote(['docs/tempdocs/739-x.md', 'scripts/x.mjs']),
    false,
  );
});
run('one canonical doc alone is NOT a working-history note (durable unit)', () => {
  assert.equal(isStandaloneNote(['docs/decisions/0045-x.md']), false);
  assert.equal(isStandaloneNote(['docs/explanation/27-x.md']), false);
});
run('empty / blank is not a standalone note', () => {
  assert.equal(isStandaloneNote([]), false);
  assert.equal(isStandaloneNote(['', '  ']), false);
});
run('blank entries do not inflate the count past the threshold', () => {
  // A trailing '' from splitting git output must not make a lone note look
  // like a 2-file batch — that would silently un-fire the whole rule.
  assert.equal(isStandaloneNote(['docs/tempdocs/739-x.md', '']), true);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`docs-granularity-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`docs-granularity-hint.test: all ${passed} checks passed`);
