#!/usr/bin/env node
/**
 * Tempdoc 618 §2: junction-safe, long-path-safe worktree teardown.
 *
 * `git worktree remove` fails on Windows with "Filename too long" on deep node_modules paths and
 * leaves an orphan directory behind (reproduced across sessions; e.g. a stale
 * `.claude/worktrees/587-…` that is no longer a registered worktree). Worse, `rm -rf` / some tools
 * can delete *through* a node_modules **junction** into the main checkout's real node_modules
 * (silent data loss — §9). This script removes a worktree safely:
 *   1. unlinks directory junctions (reparse points) link-only, so each junction's target survives;
 *   2. deletes the remaining tree with long-path support (Node fs, then a `\\?\` .NET fallback —
 *      both verified in the 618 de-risk pass: .NET Directory.Delete is junction-safe and handles
 *      >260-char paths);
 *   3. prunes git's worktree registry so no stale admin entry remains.
 *
 * Usage:
 *   node scripts/dev/remove-worktree.cjs <worktree-path> [--delete-branch]
 *
 * Safety: refuses any path that is not under `.claude/worktrees/` so it can never touch the main
 * checkout or an arbitrary directory.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

function fail(msg) {
  console.error(`[remove-worktree] ERROR: ${msg}`);
  process.exit(1);
}

const target = process.argv[2];
const deleteBranch = process.argv.includes('--delete-branch');
if (!target || target.startsWith('--')) {
  fail('usage: node scripts/dev/remove-worktree.cjs <worktree-path> [--delete-branch]');
}

const repoRoot = path.resolve(__dirname, '..', '..');
const abs = path.resolve(target);

// Safety gate: only operate on worktrees under .claude/worktrees/.
const wtMarker = path.join('.claude', 'worktrees') + path.sep;
if (!abs.includes(wtMarker)) {
  fail(`refusing: ${abs} is not under ${wtMarker} (only worktrees may be removed by this script)`);
}

// Tempdoc 622 Layer B (§11 U2): record the session -> merge-commit link before
// teardown. This is the merge-time step that closes the weak join key — at this
// point HEAD on main is the just-created merge commit and the merging agent's
// current-session-id is still set. Best-effort: never blocks teardown.
try {
  const rec = spawnSync('node', [path.join(repoRoot, 'scripts', 'agent-analytics', 'record-merge.mjs')],
    { cwd: repoRoot, encoding: 'utf8' });
  const out = (rec.stdout || rec.stderr || '').trim();
  if (out) console.error(`[remove-worktree] ${out}`);
} catch (err) {
  console.error(`[remove-worktree] WARN record-merge: ${err.message}`);
}

// 1. Unlink directory junctions link-only so we never recurse through them into shared targets.
function removeJunctions(dir) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    let st;
    try {
      st = fs.lstatSync(p);
    } catch {
      continue;
    }
    if (st.isSymbolicLink()) {
      // Directory junction / symlink: remove the LINK only. rmdir does not follow the reparse point.
      try {
        fs.rmdirSync(p);
        console.error(`[remove-worktree] unlinked junction: ${p}`);
      } catch (err) {
        try {
          fs.unlinkSync(p);
          console.error(`[remove-worktree] unlinked: ${p}`);
        } catch {
          console.error(`[remove-worktree] WARN could not unlink ${p}: ${err.message}`);
        }
      }
    } else if (st.isDirectory()) {
      removeJunctions(p);
    }
  }
}

// 2. Delete the remaining tree, long-path aware.
function deleteTree(p) {
  if (!fs.existsSync(p)) return true;
  try {
    fs.rmSync(p, { recursive: true, force: true, maxRetries: 3 });
  } catch (err) {
    console.error(`[remove-worktree] node delete failed (${err.message}); trying \\\\?\\ long-path delete`);
    if (process.platform === 'win32') {
      const psCmd = `[System.IO.Directory]::Delete('\\\\?\\' + ${JSON.stringify(p)}, $true)`;
      const ps = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psCmd], {
        encoding: 'utf8',
      });
      if (ps.status !== 0) {
        console.error(ps.stderr || ps.stdout || '(no output)');
        return false;
      }
    } else {
      return false;
    }
  }
  return !fs.existsSync(p);
}

if (fs.existsSync(abs)) {
  removeJunctions(abs);
  if (!deleteTree(abs)) fail(`failed to delete ${abs}`);
  console.error(`[remove-worktree] deleted ${abs}`);
} else {
  console.error(`[remove-worktree] ${abs} already gone; pruning registry only.`);
}

// 3. Prune git's worktree registry (drops the stale admin entry for the deleted directory).
const prune = spawnSync('git', ['worktree', 'prune'], { cwd: repoRoot, encoding: 'utf8' });
if (prune.status !== 0) {
  console.error(`[remove-worktree] WARN git worktree prune: ${prune.stderr || prune.stdout}`);
}

if (deleteBranch) {
  const branch = 'worktree-' + path.basename(abs);
  const del = spawnSync('git', ['branch', '-D', branch], { cwd: repoRoot, encoding: 'utf8' });
  console.error(
    del.status === 0
      ? `[remove-worktree] deleted branch ${branch}`
      : `[remove-worktree] WARN branch ${branch}: ${(del.stderr || '').trim()}`,
  );
}

console.error('[remove-worktree] done.');
