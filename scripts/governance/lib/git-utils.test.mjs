/**
 * Tests for `git-base` baseline resolution (tempdoc 884 §F row 11).
 *
 * The defect these pin: `resolveBaselineRef({strategy:'git-base'})` documented
 * "PR base ref with HEAD~1 fallback" but only ever returned the fallback, so
 * every `diffStrategy: "git-base"` gate diffed a ONE-COMMIT window. A changeset
 * committed earlier in a branch fell out of scope the moment another commit
 * landed, and the gate flipped red mid-branch with no change to its findings.
 *
 * Each case builds a throwaway git repo on disk rather than mocking git, because
 * the thing under test IS git's ref topology.
 *
 * Run: `node scripts/governance/lib/git-utils.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

import { resolveBaselineRef, resolveGitBase } from './git-utils.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

const git = (cwd, ...argv) =>
  execFileSync('git', argv, { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();

const sha = (cwd, ref) => git(cwd, 'rev-parse', '--verify', `${ref}^{commit}`);

/** Create a temp repo with an initial commit and no remote. */
function makeRepo() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'jsgit-'));
  git(dir, 'init', '--initial-branch=main');
  git(dir, 'config', 'user.email', 'test@example.invalid');
  git(dir, 'config', 'user.name', 'git-utils test');
  git(dir, 'config', 'commit.gpgsign', 'false');
  return dir;
}

function commit(dir, name, body) {
  fs.writeFileSync(path.join(dir, name), body, 'utf8');
  git(dir, 'add', name);
  git(dir, 'commit', '-m', `add ${name}`);
  return sha(dir, 'HEAD');
}

const created = [];
const repo = () => {
  const d = makeRepo();
  created.push(d);
  return d;
};

// Environment hygiene: GITHUB_BASE_REF leaks into every case if a real CI shell
// set it, which would make these tests pass or fail for the wrong reason.
const savedBaseRef = process.env.GITHUB_BASE_REF;
delete process.env.GITHUB_BASE_REF;

// ---------------------------------------------------------------------------
// 1. THE BITE. Branch with two commits ahead of the default branch: the base is
//    the merge-base, NOT HEAD~1. Under the old implementation this returned the
//    HEAD~1 sha and the first branch commit was invisible.
// ---------------------------------------------------------------------------
{
  const dir = repo();
  const baseSha = commit(dir, 'a.txt', 'base');
  // Give it an `origin/main` without a network: a remote-tracking ref is just a ref.
  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);
  git(dir, 'checkout', '-q', '-b', 'feature');
  const firstSha = commit(dir, 'changeset.md', 'declared');
  const tipSha = commit(dir, 'code.txt', 'growth');

  const resolved = resolveBaselineRef({ strategy: 'git-base', fallback: 'HEAD~1' }, dir);
  ok('two-commit branch resolves the merge-base, not HEAD~1', resolved.ref === baseSha);
  ok('two-commit branch does NOT resolve HEAD~1', resolved.ref !== firstSha);
  ok('strategy names the merge-base rung', resolved.strategy === 'git-base-merge-base');
  ok('base names the branch it resolved against', resolved.base === 'origin/main');

  // The consequence the gates actually see: the earlier commit's file is in scope.
  const inScope = git(dir, 'diff', '--name-only', `${resolved.ref}...HEAD`).split(/\r?\n/);
  ok('changeset from the earlier commit stays in the diff window', inScope.includes('changeset.md'));
  ok('the tip commit is in the window too', inScope.includes('code.txt'));
  ok('HEAD is the tip', sha(dir, 'HEAD') === tipSha);

  // And the one-commit window the old code produced would have dropped it.
  const oldWindow = git(dir, 'diff', '--name-only', 'HEAD~1...HEAD').split(/\r?\n/);
  ok('the old HEAD~1 window dropped the changeset (regression proof)', !oldWindow.includes('changeset.md'));
}

// ---------------------------------------------------------------------------
// 2. Squash-merged single commit: behaviour must be IDENTICAL to before. HEAD is
//    the default-branch tip, so merge-base(HEAD, origin/main) === HEAD, which is
//    an empty window — that must fall through to HEAD~1, not silently disable
//    every diff-scoped check at the one moment it bites CI.
// ---------------------------------------------------------------------------
{
  const dir = repo();
  commit(dir, 'a.txt', 'base');
  const squashed = commit(dir, 'squashed.txt', 'the whole PR as one commit');
  git(dir, 'update-ref', 'refs/remotes/origin/main', squashed);

  const resolved = resolveBaselineRef({ strategy: 'git-base', fallback: 'HEAD~1' }, dir);
  ok('squash-merged tip falls back to HEAD~1', resolved.ref === 'HEAD~1');
  ok('squash-merged tip is flagged as the fallback rung', resolved.fallback === true);
  const window = git(dir, 'diff', '--name-only', `${resolved.ref}...HEAD`).split(/\r?\n/);
  ok('squash-merged window still contains the squashed commit', window.includes('squashed.txt'));
}

// ---------------------------------------------------------------------------
// 3. GITHUB_BASE_REF (a CI pull_request run) outranks the conventional names.
// ---------------------------------------------------------------------------
{
  const dir = repo();
  const rootSha = commit(dir, 'a.txt', 'root');
  git(dir, 'update-ref', 'refs/remotes/origin/main', rootSha);
  git(dir, 'checkout', '-q', '-b', 'release');
  const releaseSha = commit(dir, 'release.txt', 'release-only');
  git(dir, 'update-ref', 'refs/remotes/origin/release-1.x', releaseSha);
  git(dir, 'checkout', '-q', '-b', 'feature');
  commit(dir, 'feature.txt', 'work');

  const withoutEnv = resolveGitBase({ fallback: 'HEAD~1' }, dir);
  ok('without GITHUB_BASE_REF the base is origin/main', withoutEnv.ref === rootSha);

  process.env.GITHUB_BASE_REF = 'release-1.x';
  try {
    const withEnv = resolveGitBase({ fallback: 'HEAD~1' }, dir);
    ok('GITHUB_BASE_REF wins over origin/main', withEnv.ref === releaseSha);
    ok('GITHUB_BASE_REF names the branch it used', withEnv.base === 'origin/release-1.x');
  } finally {
    delete process.env.GITHUB_BASE_REF;
  }
}

// ---------------------------------------------------------------------------
// 4. No remote at all (a local scratch repo): the genuine last resort applies.
// ---------------------------------------------------------------------------
{
  const dir = repo();
  commit(dir, 'a.txt', 'one');
  commit(dir, 'b.txt', 'two');
  git(dir, 'checkout', '-q', '-b', 'detached-work');
  commit(dir, 'c.txt', 'three');
  // `main` still exists locally and is 1 behind, so the ladder finds it.
  const resolved = resolveGitBase({ fallback: 'HEAD~1' }, dir);
  ok('a local default branch is a valid rung', resolved.strategy === 'git-base-merge-base');

  // Delete every default-branch name and the ladder has nothing left.
  git(dir, 'branch', '-D', 'main');
  const bare = resolveGitBase({ fallback: 'HEAD~1' }, dir);
  ok('no default branch anywhere falls back to HEAD~1', bare.ref === 'HEAD~1' && bare.fallback === true);
}

// ---------------------------------------------------------------------------
// 5. An explicit ref (`--preflight <ref>`) is an instruction, not a guess.
// ---------------------------------------------------------------------------
{
  const dir = repo();
  const rootSha = commit(dir, 'a.txt', 'root');
  git(dir, 'update-ref', 'refs/remotes/origin/main', rootSha);
  git(dir, 'checkout', '-q', '-b', 'feature');
  commit(dir, 'b.txt', 'one');
  commit(dir, 'c.txt', 'two');

  const explicit = resolveBaselineRef({ strategy: 'git-base', explicit: 'HEAD~1' }, dir);
  ok('explicit ref is returned verbatim', explicit.ref === 'HEAD~1');
  ok('explicit ref is labelled', explicit.strategy === 'git-base-explicit');

  let threw = false;
  try {
    resolveBaselineRef({ strategy: 'git-base', explicit: 'refs/heads/nope' }, dir);
  } catch {
    threw = true;
  }
  ok('an unreachable explicit ref throws rather than silently degrading', threw);
}

// ---------------------------------------------------------------------------
// 6. A single-commit repo has no HEAD~1: the throw must survive, because the
//    runner catches it to disable pin-bump detection with a warning.
// ---------------------------------------------------------------------------
{
  const dir = repo();
  commit(dir, 'a.txt', 'only');
  let threw = false;
  try {
    resolveGitBase({ fallback: 'HEAD~1' }, dir);
  } catch (e) {
    threw = /unreachable/.test(e.message);
  }
  ok('single-commit repo throws the unreachable-baseline error', threw);
}

if (savedBaseRef !== undefined) process.env.GITHUB_BASE_REF = savedBaseRef;
for (const dir of created) {
  try {
    fs.rmSync(dir, { recursive: true, force: true });
  } catch {
    /* Windows can hold a git handle briefly; a temp dir left behind is not a failure */
  }
}

if (failures.length > 0) {
  console.error(`git-utils.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`git-utils.test: all ${passed} checks passed`);
