#!/usr/bin/env node
'use strict';
/**
 * Cross-worktree collision preflight.
 *
 * Up to 3-4 agent sessions work in parallel git worktrees under
 * `.claude/worktrees/`, all branching from `main`. This reports, for the
 * CURRENT worktree's branch, which other LIVE worktree branches (and
 * `origin/main`) touch the same files, and which would textually conflict
 * on merge -- using `git merge-tree --write-tree --messages <a> <b>`
 * (Git >= 2.38), which predicts conflicts in seconds against the object
 * database, touching nothing: no checkout, no merge, no fetch, no mutation
 * of any ref. Read-only by construction.
 *
 * ============================================================================
 * WHY THIS MUST STAY A REPORT, NEVER A GATE (read before "improving" this)
 * ============================================================================
 * Two agents once independently diagnosed the same bug and wrote competing,
 * conflicting fixes, neither aware the other existed -- it cost roughly a day
 * and nearly shipped a defect. It is tempting to conclude the fix is "detect
 * this earlier and stop one of the agents." Don't. The SAME duplication that
 * cost the day is also what CAUGHT the bug: the two independent diagnoses
 * disagreed with each other, and that disagreement is what exposed that one
 * of the fixes fabricated data. Had a tool at that point said "someone's
 * already on this, skip it" or "defer to the other worktree," the fabricating
 * fix would have shipped uncontested. This script may only ever INFORM a
 * human of overlap; it must never allocate work, suggest one agent stop or
 * defer, or block/gate anything (no non-zero exit for "conflicts found" --
 * see the exit-code contract below). If a future edit adds a "recommended
 * action," a blocking exit code, or a CI wiring for this file's findings,
 * that edit has broken the reason this script exists.
 *
 * ============================================================================
 * WHAT THIS TOOL CANNOT SEE (read the banner it prints -- it repeats this)
 * ============================================================================
 * `git merge-tree` only detects TEXTUAL conflicts (overlapping/incompatible
 * line-level edits). The single most dangerous conflict in the incident this
 * script responds to was SEMANTIC: two individually-correct changes that
 * auto-merged with zero conflict markers, and were caught only by the full
 * test suite. "No textual conflicts reported below" is NOT "safe to merge"
 * and is NOT a substitute for running the full suite. This is a real,
 * structural blind spot of the underlying git primitive, not a bug fixable
 * by this script -- so it is stated in the output itself (every run), not
 * just here.
 *
 * Usage (run from inside the worktree whose branch you want to check):
 *   node scripts/dev/worktree-collision-preflight.cjs
 *
 * Exit-code contract: 0 on every successful analysis run, REGARDLESS of
 * whether overlap or conflicts were found -- this is a report, not a gate.
 * Non-zero only on a genuine execution failure (git unavailable, current
 * HEAD is detached with nothing comparable, etc.), never on a finding.
 */
const { execFileSync, spawnSync } = require('child_process');
const path = require('path');

const SCRIPT_NAME = 'worktree-collision-preflight';
const repoRoot = path.resolve(__dirname, '..', '..');

function fail(msg) {
  console.error(`[${SCRIPT_NAME}] ERROR: ${msg}`);
  process.exit(1);
}

function git(args) {
  try {
    return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return '';
  }
}

function normPath(p) {
  return p.replace(/\\/g, '/').toLowerCase().replace(/\/+$/, '');
}

/** Parse `git worktree list --porcelain` into { path, branch|null, detached, bare }[]. */
function listWorktrees() {
  const raw = git(['worktree', 'list', '--porcelain']);
  if (!raw) return [];
  const blocks = raw.split(/\r?\n\r?\n/);
  const entries = [];
  for (const block of blocks) {
    const lines = block.split(/\r?\n/).filter(Boolean);
    if (lines.length === 0) continue;
    const entry = { path: null, branch: null, detached: false, bare: false };
    for (const line of lines) {
      if (line.startsWith('worktree ')) entry.path = line.slice('worktree '.length).trim();
      else if (line.startsWith('branch ')) entry.branch = line.slice('branch '.length).trim().replace(/^refs\/heads\//, '');
      else if (line === 'detached') entry.detached = true;
      else if (line === 'bare') entry.bare = true;
    }
    if (entry.path) entries.push(entry);
  }
  return entries;
}

/**
 * Compare the current branch against one target ref. Returns a result
 * object; never throws -- a git failure surfaces as `.error` so one bad
 * target doesn't abort the whole report.
 */
function compareAgainst(currentRef, target) {
  const mb = git(['merge-base', currentRef, target.ref]);
  if (!mb) {
    return { ...target, error: 'no common ancestor found (unrelated histories?) -- comparison skipped' };
  }

  const currentSha = git(['rev-parse', currentRef]);
  const targetSha = git(['rev-parse', target.ref]);
  if (currentSha && currentSha === targetSha) {
    return { ...target, identical: true, mergeBase: mb.slice(0, 7) };
  }

  const filesCurrent = new Set(git(['diff', '--name-only', mb, currentRef]).split('\n').filter(Boolean));
  const filesTarget = new Set(git(['diff', '--name-only', mb, target.ref]).split('\n').filter(Boolean));
  const overlap = [...filesCurrent].filter((f) => filesTarget.has(f)).sort();

  // spawnSync (not execFileSync) so a non-zero exit is inspectable rather than thrown --
  // git merge-tree exits 1 both for "real conflicts" and for "bad ref"; distinguish
  // by whether the output actually contains a CONFLICT line.
  const mt = spawnSync('git', ['merge-tree', '--write-tree', '--messages', currentRef, target.ref], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  const stdout = mt.stdout || '';
  const conflictLines = stdout.split(/\r?\n/).filter((l) => l.startsWith('CONFLICT'));
  const autoMergeLines = stdout.split(/\r?\n/).filter((l) => l.startsWith('Auto-merging'));

  if (mt.status !== 0 && conflictLines.length === 0) {
    return { ...target, error: `merge-tree comparison failed: ${(mt.stderr || stdout || '(no output)').trim()}` };
  }

  return {
    ...target,
    mergeBase: mb.slice(0, 7),
    overlap,
    clean: conflictLines.length === 0,
    conflictLines,
    autoMergeLines,
  };
}

function printBanner() {
  console.log(
    'BLIND SPOT: this tool only sees TEXTUAL conflicts (via `git merge-tree`\'s three-way\n' +
      'merge). It cannot see SEMANTIC conflicts -- two changes that touch different lines,\n' +
      'or even auto-merge with zero conflict markers, yet are individually correct and\n' +
      'JOINTLY wrong. "No textual conflicts" below is NOT "safe to merge" and is NOT a\n' +
      'substitute for running the full test suite. This is a report for a human to weigh,\n' +
      'not a verdict -- overlap or conflict here is never a signal to stop, defer, or hand\n' +
      'off work (see this script\'s file-header docstring for why).'
  );
}

function main() {
  const currentBranch = git(['symbolic-ref', '--quiet', '--short', 'HEAD']);
  const headSha = git(['rev-parse', '--short', 'HEAD']);
  if (!currentBranch) {
    console.log(`[${SCRIPT_NAME}] HEAD is detached (at ${headSha || '?'}) -- no named branch to report collisions for. Nothing to do.`);
    process.exit(0);
  }

  const worktrees = listWorktrees();
  if (worktrees.length === 0) {
    fail("`git worktree list --porcelain` returned nothing -- can't discover other live worktrees. Is this a git repo?");
  }

  const selfNorm = normPath(repoRoot);
  const targets = [];
  for (const wt of worktrees) {
    if (wt.bare || !wt.path) continue;
    if (normPath(wt.path) === selfNorm) continue; // skip self
    const label = path.basename(wt.path.replace(/\\/g, '/'));
    if (wt.detached || !wt.branch) {
      console.log(`[${SCRIPT_NAME}] note: worktree ${label} (${wt.path}) is on a detached HEAD -- skipped (no branch to compare).`);
      continue;
    }
    targets.push({ kind: 'worktree', label, ref: wt.branch, path: wt.path });
  }

  // origin/<default-branch>, best-effort, no fetch (read-only by design -- see docstring).
  let defaultBranch = git(['symbolic-ref', '--quiet', '--short', 'refs/remotes/origin/HEAD']).replace(/^origin\//, '');
  if (!defaultBranch) defaultBranch = 'main';
  const originRef = `origin/${defaultBranch}`;
  if (git(['rev-parse', '--verify', '--quiet', originRef])) {
    targets.push({ kind: 'origin', label: originRef, ref: originRef, path: null });
  } else {
    console.log(`[${SCRIPT_NAME}] note: ${originRef} not resolvable locally -- skipped. (This tool never fetches; run \`git fetch\` yourself first if you want a current comparison.)`);
  }

  console.log(`Cross-worktree collision preflight -- current branch \`${currentBranch}\` (${headSha})`);
  console.log(`Repo: ${repoRoot}`);
  console.log('');
  printBanner();
  console.log('');

  if (targets.length === 0) {
    console.log('No other live worktree branches or resolvable origin/main to compare against.');
    process.exit(0);
  }

  console.log(`Comparing against ${targets.length} target(s):`);
  console.log('');

  let overlapCount = 0;
  let conflictCount = 0;
  for (const target of targets) {
    const r = compareAgainst('HEAD', target);
    const header = target.path ? `${target.label}  (${target.ref} @ ${target.path})` : `${target.label}`;
    console.log(`== ${header} ==`);

    if (r.error) {
      console.log(`  SKIPPED: ${r.error}`);
      console.log('');
      continue;
    }
    if (r.identical) {
      console.log('  identical to current HEAD -- nothing to compare yet.');
      console.log('');
      continue;
    }

    console.log(`  common ancestor: ${r.mergeBase}`);
    if (r.overlap.length > 0) {
      overlapCount += 1;
      console.log(`  files touched by BOTH branches since the common ancestor (${r.overlap.length}):`);
      for (const f of r.overlap) console.log(`    - ${f}`);
    } else {
      console.log('  files touched by both branches: (none)');
    }

    if (r.clean) {
      console.log('  git merge-tree: no textual conflicts' + (r.autoMergeLines.length ? ` (${r.autoMergeLines.length} file(s) auto-merged cleanly)` : ''));
    } else {
      conflictCount += 1;
      console.log(`  git merge-tree: TEXTUAL CONFLICT (${r.conflictLines.length} file(s)) --`);
      for (const line of r.conflictLines) console.log(`    ${line}`);
    }
    console.log('');
  }

  console.log(
    `Summary: ${overlapCount} of ${targets.length} target(s) touch file(s) you also touched; ` +
      `${conflictCount} of those would textually conflict via merge-tree right now.`
  );
  console.log('');
  console.log('This is INFORMATION, not a verdict -- see the blind-spot note above. Nothing here means stop, defer, or hand off; it means "go look and decide."');

  process.exit(0);
}

main();
