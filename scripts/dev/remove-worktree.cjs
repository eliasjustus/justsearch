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
 *
 * Tempdoc 746 item 4 — junction-unlink KEPT (not superseded by Claude Code >=2.1.205's native
 * junction handling), for two independent reasons:
 *   1. Native handling covers harness-DRIVEN worktree removal only. This script also runs
 *      standalone from a shell (its documented usage above), a path native handling never sees.
 *   2. Deletion here never goes through `git worktree remove` (junction-safety of which would be
 *      moot anyway) — `main()` deletes the tree itself via `deleteTree` (fs.rmSync, falling back
 *      to a `\\?\`-prefixed .NET `Directory.Delete`) and only runs `git worktree prune` afterward,
 *      to clear the now-stale registry entry. Empirically probed both of `deleteTree`'s own
 *      methods against a scratch junction (temp-dir fixture, Node v24.12.0 / Windows 11):
 *      fs.rmSync(recursive) turned out to already be junction-safe on its own (unlinks the
 *      reparse point, leaves the target untouched) — but the `.NET Directory.Delete` FALLBACK is
 *      NOT: it throws `UnauthorizedAccessException: Access to the path 'node_modules' is denied`
 *      on a tree containing an un-unlinked junction, and does not complete the delete at all. That
 *      fallback exists precisely for the long-path/held-handle cases this script was written to
 *      solve (§ above), so `removeJunctions` running first is what keeps the fallback able to
 *      finish, not merely "safe" in the no-data-loss sense.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { PROCESS_TABLE_PS_COMMAND, describeJsonParseFailure } = require('./lib/process-identity.cjs');
const {
  consultAgentSpawnsForTeardown,
  describeEntry,
  resolveMainRepoRoot,
  resolveCallerSessionId,
} = require('./lib/agent-spawn-sweep.cjs');

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

/**
 * Fetch the full local process table via WMI/CIM (ProcessId, ParentProcessId, Name, CommandLine,
 * CreationFileTimeUtc).
 * Returns [] on any failure so `reportHolders` degrades to "no holder found" instead of throwing
 * mid-teardown. Deliberately unfiltered (not a `Where-Object -like` query): the ancestor walk in
 * `ancestorPids` needs every process's ParentProcessId to climb the chain, including ancestors
 * whose own command line does not name the worktree path (e.g. an intermediate console-host).
 *
 * Tempdoc 861 [A2]: the projection previously dropped `CreationDate`, so the process table this
 * scan already collects could NOT answer "is this still the same process, or a recycled pid?".
 * The projection now comes from ONE shared constant (`process-identity.cjs`), which adds the
 * creation time normalized via `.ToFileTimeUtc()`. This function's own `[]`-on-failure contract is
 * unchanged and correct for a best-effort holder report — but it is exactly why an identity check
 * must NOT read this function's result: `[]` here means "I could not look", and
 * `readProcessTable` in `process-identity.cjs` is the tri-state an identity check uses instead.
 */
function getProcessTable() {
  if (process.platform !== 'win32') return [];
  const psCmd = PROCESS_TABLE_PS_COMMAND;
  const res = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', psCmd], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (res.status !== 0 || !res.stdout) return [];
  try {
    const parsed = JSON.parse(res.stdout);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch (err) {
    // `[]`-on-failure is unchanged by design (a best-effort holder report, not an identity check —
    // see the docstring above), but a silent `[]` left the next operator blind to WHY the scan came
    // up empty (861 production sweep, 2026-08-25). Log the diagnostic; do not change the return.
    console.error(`[remove-worktree] process-table query returned unparseable JSON: ${describeJsonParseFailure(res.stdout, err)}`);
    return [];
  }
}

/**
 * Walk `table`'s ParentProcessId chain upward from `pid`, returning every ancestor PID (NOT
 * including `pid` itself). A depth cap + cycle guard protects against a corrupt/partial table
 * (real WMI snapshots can have stale ParentProcessId rows pointing at a reused PID).
 */
function ancestorPids(table, pid, maxDepth = 64) {
  const byPid = new Map();
  for (const proc of table) {
    const id = Number(proc && proc.ProcessId);
    if (Number.isFinite(id)) byPid.set(id, proc);
  }
  const ancestors = new Set();
  let currentPid = Number(pid);
  for (let i = 0; i < maxDepth; i += 1) {
    const proc = byPid.get(currentPid);
    if (!proc) break;
    const parentPid = Number(proc.ParentProcessId);
    if (!Number.isFinite(parentPid) || parentPid === currentPid || ancestors.has(parentPid)) break;
    ancestors.add(parentPid);
    currentPid = parentPid;
  }
  return ancestors;
}

/**
 * Belt-and-braces: true when `entry`'s command line names both this script and the target path —
 * i.e. it looks like remove-worktree.cjs's own invocation, independent of whether the PID/ancestor
 * walk resolved it. Catches an intermediate wrapper the CIM parent chain didn't capture (e.g. a
 * PID the WMI snapshot missed a generation of).
 */
function looksLikeOwnInvocation(entry, targetBase) {
  const cmd = String((entry && entry.CommandLine) || '');
  return cmd.includes('remove-worktree.cjs') && cmd.includes(targetBase);
}

/**
 * Self-match fix (tempdoc 746 item 5): the previous holder-scan excluded only its OWN PowerShell
 * query process (`-ne $PID`) — it still named the invoking Node process and its shell/bash
 * ancestor as "holders", because BOTH have the worktree path in argv (that's how they invoked this
 * very script). An agent that ran the printed `taskkill` suggestion against that self-match killed
 * its own invoking process chain mid-run, leaving a half-deleted worktree — reproduced twice
 * (docs/observations.md, 2026-07-16).
 *
 * Excludes from the holder set: this script's own PID, every ancestor PID up the parent chain (the
 * shell/node chain that launched it), and any entry that looks like this script's own invocation by
 * command line (belt-and-braces for an ancestor the CIM walk didn't resolve).
 */
function filterHolders(table, targetPath, ownPid) {
  const base = path.basename(targetPath);
  const excludePids = new Set([Number(ownPid), ...ancestorPids(table, ownPid)]);
  return table.filter((entry) => {
    const cmd = String((entry && entry.CommandLine) || '');
    if (!cmd.includes(base)) return false;
    if (excludePids.has(Number(entry && entry.ProcessId))) return false;
    if (looksLikeOwnInvocation(entry, base)) return false;
    return true;
  });
}

function formatHolderLines(entry) {
  return [
    `PID ${entry.ProcessId}: ${entry.Name} — ${entry.CommandLine}`,
    `  kill: taskkill /F /PID ${entry.ProcessId}`,
  ];
}

/** Best-effort report of processes whose command line names the held path, to help the operator kill it. */
function reportHolders(p) {
  if (process.platform !== 'win32') return;
  const table = getProcessTable();
  const holders = filterHolders(table, p, process.pid);
  if (holders.length) {
    // Tempdoc 727 F-2: alongside the existing description, print a ready-to-run kill
    // command per holder — turns manual recovery into "copy this line if you're sure
    // it's safe" instead of hand-constructing the right taskkill invocation. Never
    // executed automatically: an unconditional auto-kill risks a legitimate process
    // (an open editor, another agent's session) that merely happens to name this path.
    // `filterHolders` (tempdoc 746 item 5) has already excluded this script's own PID,
    // its ancestor chain, and anything matching its own invocation signature, so this
    // list can no longer suggest killing the very process performing the teardown.
    console.error(`[remove-worktree] possible holder(s) of ${p}:`);
    for (const h of holders) {
      for (const line of formatHolderLines(h)) console.error(`[remove-worktree]   ${line}`);
    }
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

async function main() {
  const target = process.argv[2];
  const deleteBranch = process.argv.includes('--delete-branch');
  const mergeCommitArg = flagValue(process.argv, 'merge-commit');
  const sessionIdArg = flagValue(process.argv, 'session-id');
  if (!target || target.startsWith('--')) {
    fail(
      'usage: node scripts/dev/remove-worktree.cjs <worktree-path> [--delete-branch]' +
        ' [--merge-commit <sha>] [--session-id <id>]' +
        ' (--session-id overrides; otherwise resolved from CLAUDE_CODE_SESSION_ID /' +
        ' JUSTSEARCH_AGENT_SESSION_ID / tmp/agent-telemetry/current-session-id)',
    );
  }

  const repoRoot = path.resolve(__dirname, '..', '..');
  // [F-8] The register lives under the MAIN checkout (861 [A9]), not wherever THIS copy of the
  // script happens to run from — a worktree-local copy of remove-worktree.cjs would otherwise
  // consult its own worktree's (nonexistent, or stale) tmp/dev-runner/agent-spawns/ instead of
  // the shared one.
  const mainRepoRoot = resolveMainRepoRoot(repoRoot);
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
    // Tempdoc 861 §6.4 `worktree-teardown` occasion: consult the `agent-spawns/` register
    // BEFORE unlinking junctions. Reaps what it is authorized to (this session's own spawns,
    // or another session's lapsed-and-stale one); refuses to proceed while an unreapable
    // holder remains, rather than proceeding into the half-deleted, `.git`-less worktree shell
    // §2-bis (c) documents. The OBSERVED-tier fallback below (`reportHolders`, driven by
    // `filterHolders`'s own command-line scan) is UNCHANGED — it still runs regardless, for
    // whatever this registry does not cover (no-regression constraint, 861 §7.1 Phase 5).
    //
    // [F-2a] `callerSessionId` is resolved via the standard chain, not `--session-id` alone: the
    // documented invocation never passes that flag, so without this a caller's OWN live spawn on
    // this tree read as an unattributed CONTENTION instead of a same-session reap — the ONE case
    // §6.3 says is unambiguous ("a session may always reap its own registered spawns").
    const callerSessionId = resolveCallerSessionId({ explicit: sessionIdArg, env: process.env, repoRoot });
    let consult;
    try {
      consult = await consultAgentSpawnsForTeardown({ mainRepoRoot, targetPath: abs, callerSessionId });
    } catch (err) {
      console.error(`[remove-worktree] WARN agent-spawns register consult failed (proceeding on the observed-tier fallback only): ${err && err.message ? err.message : err}`);
      consult = null;
    }
    if (consult && consult.buckets.all.length > 0) {
      console.error(`[remove-worktree] agent-spawns register: ${consult.buckets.all.length} record(s) hold a path under ${abs}:`);
      for (const e of consult.buckets.all) {
        console.error(`[remove-worktree]   ${describeEntry(e).replace(/\n/g, '\n[remove-worktree]   ')}`);
      }
    }
    if (consult && consult.buckets.blocksProceed) {
      fail(
        `refusing to remove ${abs}: a registered agent-spawn holder could not be reaped (see the ` +
          `agent-spawns register lines above) — clear it (or wait for it to become reapable), then retry. ` +
          `This refusal is the fix for the half-deleted, .git-less worktree shell a proceed-anyway would leave.`,
      );
    }

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
  main().catch((err) => {
    console.error(`[remove-worktree] ERROR: ${err && err.message ? err.message : err}`);
    process.exit(1);
  });
}

module.exports = {
  deleteTree,
  longPathDelete,
  sleepSync,
  reportHolders,
  removeJunctions,
  main,
  getProcessTable,
  ancestorPids,
  looksLikeOwnInvocation,
  filterHolders,
};
