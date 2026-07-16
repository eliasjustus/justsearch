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

/** Synchronous sleep without shelling out to `sleep` (blocked by bash-guard). */
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/**
 * Long-path delete via .NET Directory.Delete, junction-safe and >260-char-safe.
 * Builds the `\\?\` extended-length path from an ABSOLUTE, backslash-normalized
 * path — a raw `p` (possibly forward-slashed / relative) makes .NET throw
 * "The filename, directory name, or volume label syntax is incorrect."
 */
function longPathDelete(p) {
  const extended = '\\\\?\\' + path.resolve(p).replace(/\//g, '\\');
  // PowerShell single-quoted strings are literal (no backslash-escaping) —
  // only a literal single quote needs doubling. JSON.stringify would be wrong
  // here: it escapes `\` for JS/JSON semantics, which PowerShell's
  // double-quoted string then reads back as DOUBLED literal backslashes,
  // producing an illegal path (".NET Directory.Delete: Illegal characters in
  // path" — reproduced while writing this fix).
  const psLiteral = "'" + extended.replace(/'/g, "''") + "'";
  const psCmd = `[System.IO.Directory]::Delete(${psLiteral}, $true)`;
  return spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psCmd], {
    encoding: 'utf8',
  });
}

/** Best-effort report of processes whose command line names the held path, to help the operator kill it. */
function reportHolders(p) {
  if (process.platform !== 'win32') return;
  const base = path.basename(p);
  // `-and $_.ProcessId -ne $PID` excludes THIS query's own powershell process,
  // whose command line contains `base` (in the -like literal) and would otherwise
  // always self-match. Honest limit: Win32_Process exposes CommandLine but NOT the
  // working directory, so a cwd-only holder (the common case — a shell whose cwd is
  // inside the worktree) still won't appear; this catches only holders that NAME the
  // path in argv (e.g. `node serve-worktree-fe <path>`, an editor opened on it).
  const psCmd =
    `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*${base}*' -and $_.ProcessId -ne $PID } | ` +
    `ForEach-Object { "PID $($_.ProcessId): $($_.Name) — $($_.CommandLine)\`n  kill: taskkill /F /PID $($_.ProcessId)" }`;
  const res = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psCmd], {
    encoding: 'utf8',
  });
  const out = (res.stdout || '').trim();
  if (out) {
    // Tempdoc 727 F-2: alongside the existing description, print a ready-to-run kill
    // command per holder — turns manual recovery into "copy this line if you're sure
    // it's safe" instead of hand-constructing the right taskkill invocation. Never
    // executed automatically: an unconditional auto-kill risks a legitimate process
    // (an open editor, another agent's session) that merely happens to name this path.
    console.error(`[remove-worktree] possible holder(s) of ${p}:`);
    for (const line of out.split(/\r?\n/)) console.error(`[remove-worktree]   ${line}`);
  } else {
    console.error(
      `[remove-worktree] no holder found by command line for ${p}; a process whose CWD is inside ` +
        `it (but doesn't name it on the command line) will not show up here.`,
    );
  }
}

// 2. Delete the remaining tree, long-path aware, with a bounded retry for held handles.
function deleteTree(p, { attempts = 5, retryDelayMs = 300 } = {}) {
  if (!fs.existsSync(p)) return true;
  let lastErr = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      fs.rmSync(p, { recursive: true, force: true, maxRetries: 3 });
      if (!fs.existsSync(p)) return true;
      lastErr = new Error('path still exists after rmSync');
    } catch (err) {
      lastErr = err;
    }

    const retryable = /EPERM|EBUSY|ENOTEMPTY/.test(String(lastErr && lastErr.code));
    if (attempt < attempts && (retryable || lastErr)) {
      sleepSync(retryDelayMs);
      if (!fs.existsSync(p)) return true;
    }
  }

  console.error(`[remove-worktree] node delete failed (${lastErr && lastErr.message}); trying \\\\?\\ long-path delete`);
  if (process.platform === 'win32') {
    const ps = longPathDelete(p);
    if (ps.status !== 0) {
      console.error(ps.stderr || ps.stdout || '(no output)');
      if (!fs.existsSync(p)) return true;
      reportHolders(p);
      return false;
    }
  } else {
    return false;
  }
  return !fs.existsSync(p);
}

/** Read a `--flag <value>` / `--flag=<value>` pair out of argv. */
function flagValue(argv, name) {
  const i = argv.indexOf(`--${name}`);
  if (i !== -1 && argv[i + 1] && !argv[i + 1].startsWith('--')) return argv[i + 1].trim();
  const eq = argv.find((a) => a.startsWith(`--${name}=`));
  return eq ? eq.slice(`--${name}=`.length).trim() : null;
}

/** The branch checked out in the worktree we are about to delete. */
function worktreeBranch(abs) {
  const r = spawnSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: abs, encoding: 'utf8' });
  if (r.status !== 0) return null;
  const b = (r.stdout || '').trim();
  return b && b !== 'HEAD' ? b : null;
}

/**
 * Ask GitHub for the squash commit its merged PR produced. This is the only
 * cheap source of truth: ADR-0045 squash-merges every PR, so the branch's own
 * commits never appear in main and ancestry cannot answer this
 * (`squash-merge-verify-content-not-ancestry`).
 */
function mergeCommitFromPr(repoRoot, branch) {
  const r = spawnSync(
    'gh',
    ['pr', 'list', '--head', branch, '--state', 'merged', '--json', 'mergeCommit', '--limit', '1'],
    { cwd: repoRoot, encoding: 'utf8' },
  );
  if (r.status !== 0) return null;
  try {
    const rows = JSON.parse(r.stdout || '[]');
    return rows[0]?.mergeCommit?.oid || null;
  } catch {
    return null;
  }
}

function recordMergeLink({ repoRoot, abs, mergeCommitArg, sessionIdArg }) {
  const branch = worktreeBranch(abs);
  const commit = mergeCommitArg || (branch ? mergeCommitFromPr(repoRoot, branch) : null);

  if (!commit) {
    // Deliberately NOT falling back to repoRoot HEAD — that is the bug.
    console.error(
      '[remove-worktree] record-merge SKIPPED: could not establish this branch\'s merge commit' +
        (branch ? ` (branch ${branch}: no merged PR found via gh)` : ' (worktree has no branch)') +
        '.\n[remove-worktree]   The session -> merge link is a FACT-tier row; guessing it would' +
        ' write a wrong one that cannot be retracted.\n[remove-worktree]   Backfill once you know' +
        ' the commit:\n[remove-worktree]     node scripts/agent-analytics/record-merge.mjs <merge-commit>' +
        ' --session-id <id>\n[remove-worktree]   Or re-run with --merge-commit <sha>.',
    );
    return;
  }

  const args = [path.join(repoRoot, 'scripts', 'agent-analytics', 'record-merge.mjs'), commit];
  if (sessionIdArg) args.push('--session-id', sessionIdArg);
  try {
    const rec = spawnSync('node', args, { cwd: repoRoot, encoding: 'utf8' });
    const out = (rec.stdout || rec.stderr || '').trim();
    if (out) console.error(`[remove-worktree] ${out}`);
  } catch (err) {
    console.error(`[remove-worktree] WARN record-merge: ${err.message}`);
  }
}

function main() {
  const target = process.argv[2];
  const deleteBranch = process.argv.includes('--delete-branch');
  const mergeCommitArg = flagValue(process.argv, 'merge-commit');
  const sessionIdArg = flagValue(process.argv, 'session-id');
  if (!target || target.startsWith('--')) {
    fail(
      'usage: node scripts/dev/remove-worktree.cjs <worktree-path> [--delete-branch]' +
        ' [--merge-commit <sha>] [--session-id <id>]',
    );
  }

  const repoRoot = path.resolve(__dirname, '..', '..');
  const abs = path.resolve(target);

  // Safety gate: only operate on worktrees under .claude/worktrees/.
  const wtMarker = path.join('.claude', 'worktrees') + path.sep;
  if (!abs.includes(wtMarker)) {
    fail(`refusing: ${abs} is not under ${wtMarker} (only worktrees may be removed by this script)`);
  }

  // Tempdoc 622 Layer B (§11 U2): record the session -> merge-commit link before
  // teardown. Best-effort: never blocks teardown.
  //
  // This used to invoke record-merge.mjs with NO commit, so it defaulted to
  // `HEAD` resolved in repoRoot, on the assumption "HEAD on main is the
  // just-created merge commit". That assumption fails routinely, and silently:
  // the main checkout is often parked on another branch (observed 4x — e.g.
  // `session d1af1a27 -> 60f4e9d6`, an unrelated branch's tip), and even when
  // it is on main, a GitHub squash-merge means local main is stale until pulled.
  // The result was a WRONG row written into outcomes' fact tier — tagged
  // kind:'fact', indistinguishable downstream from a correct one, outranking the
  // LLM-judge inference it is designed to override, and unretractable (a
  // backfill appends the right row but cannot remove the wrong one).
  //
  // So: establish the commit, never infer it. `--merge-commit` wins; else ask
  // GitHub for the branch's merged PR (squash-proof: content, not ancestry);
  // else SKIP and say so. A legible skip beats a confident wrong fact.
  recordMergeLink({ repoRoot, abs, mergeCommitArg, sessionIdArg });

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
}

if (require.main === module) {
  main();
}

module.exports = { deleteTree, longPathDelete, sleepSync, reportHolders, removeJunctions, main };
