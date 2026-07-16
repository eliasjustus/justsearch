/**
 * Tempdoc 746 item 5 — regression test for the remove-worktree.cjs holder-scan self-match bug.
 *
 * Bug: the previous `reportHolders()` excluded only its own PowerShell query process
 * (`-ne $PID`), so it still named the invoking Node process and its shell/bash ancestor as
 * "holders" of the worktree path — because both have that path in argv (that's how they invoked
 * this very script). An agent that ran the printed `taskkill` suggestion against that self-match
 * killed its own invoking process chain mid-run, leaving a half-deleted worktree (reproduced 2x,
 * docs/observations.md 2026-07-16).
 *
 * These tests exercise the pure exclusion logic (`ancestorPids`, `looksLikeOwnInvocation`,
 * `filterHolders`) against fixture process tables only — no real process is queried or killed.
 *
 * Run with: `node --test scripts/dev/remove-worktree.test.mjs`
 */

import assert from 'node:assert/strict';
import { ancestorPids, looksLikeOwnInvocation, filterHolders } from './remove-worktree.cjs';

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

const WT_PATH = 'F:\\justsearch-public\\.claude\\worktrees\\obs-cleanup';

// Fixture mirrors the live-observed shape (docs/observations.md, 2026-07-07/2026-07-16):
// bash (a shell) -> node (running remove-worktree.cjs) is the invoking chain, plus an unrelated
// editor process that also happens to name the path (a legitimate holder), plus an unrelated
// system process that shares no relationship to the target at all.
function fixtureTable({ ownPid = 200, parentBash = 100, grandparentConsole = 1 } = {}) {
  return [
    { ProcessId: grandparentConsole, ParentProcessId: 0, Name: 'conhost.exe', CommandLine: 'conhost.exe' },
    { ProcessId: parentBash, ParentProcessId: grandparentConsole, Name: 'bash.exe', CommandLine: `bash -c "node scripts/dev/remove-worktree.cjs ${WT_PATH}"` },
    { ProcessId: ownPid, ParentProcessId: parentBash, Name: 'node.exe', CommandLine: `node scripts/dev/remove-worktree.cjs ${WT_PATH}` },
    { ProcessId: 300, ParentProcessId: 1, Name: 'Code.exe', CommandLine: `"C:\\Code.exe" ${WT_PATH}` },
    { ProcessId: 400, ParentProcessId: 1, Name: 'svchost.exe', CommandLine: 'svchost.exe -k netsvcs' },
  ];
}

run('ancestorPids walks the parent chain up from the script PID', () => {
  const table = fixtureTable();
  const ancestors = ancestorPids(table, 200);
  // Walks node(200) -> bash(100) -> conhost(1) -> conhost's declared parent (0, a sentinel with
  // no table row, where the walk stops because byPid.get(0) finds nothing).
  assert.deepEqual([...ancestors].sort((a, b) => a - b), [0, 1, 100]);
});

run('ancestorPids excludes the starting PID itself', () => {
  const table = fixtureTable();
  const ancestors = ancestorPids(table, 200);
  assert.equal(ancestors.has(200), false);
});

run('ancestorPids stops at a depth cap / cycle instead of looping forever on a corrupt table', () => {
  // Self-referential row: PID 5's parent is PID 5.
  const cyclic = [{ ProcessId: 5, ParentProcessId: 5, Name: 'x.exe', CommandLine: 'x' }];
  const ancestors = ancestorPids(cyclic, 5, 8);
  assert.equal(ancestors.has(5), false);
});

run('ancestorPids returns empty set for a PID missing from the table', () => {
  const table = fixtureTable();
  const ancestors = ancestorPids(table, 9999);
  assert.equal(ancestors.size, 0);
});

run('looksLikeOwnInvocation matches an entry naming both the script and the target path', () => {
  const entry = { CommandLine: `node scripts/dev/remove-worktree.cjs ${WT_PATH}` };
  assert.equal(looksLikeOwnInvocation(entry, 'obs-cleanup'), true);
});

run('looksLikeOwnInvocation does not match a holder that only names the path (e.g. an editor)', () => {
  const entry = { CommandLine: `"C:\\Code.exe" ${WT_PATH}` };
  assert.equal(looksLikeOwnInvocation(entry, 'obs-cleanup'), false);
});

run('filterHolders excludes the script\'s own PID and its ancestor chain (the core bug fix)', () => {
  const table = fixtureTable();
  const holders = filterHolders(table, WT_PATH, 200);
  const holderPids = holders.map((h) => h.ProcessId).sort((a, b) => a - b);
  // Only the legitimate holder (the editor, PID 300) should remain — own PID (200) and its
  // ancestors (100 bash, 1 conhost) must NOT appear, even though their command lines contain
  // the worktree path.
  assert.deepEqual(holderPids, [300]);
});

run('filterHolders never returns the invoking process even when ancestor resolution is incomplete', () => {
  // Ancestor walk breaks if an intermediate PID is missing from the table (e.g. a WMI snapshot
  // gap) — looksLikeOwnInvocation is the belt-and-braces net for exactly this case.
  const table = fixtureTable().filter((p) => p.ProcessId !== 100); // drop the bash ancestor row
  const holders = filterHolders(table, WT_PATH, 200);
  assert.equal(holders.some((h) => h.ProcessId === 200), false);
});

run('filterHolders excludes entries whose CommandLine is null/undefined without throwing', () => {
  const table = [
    { ProcessId: 1, ParentProcessId: 0, Name: 'System Idle Process', CommandLine: null },
    { ProcessId: 200, ParentProcessId: 1, Name: 'node.exe', CommandLine: `node scripts/dev/remove-worktree.cjs ${WT_PATH}` },
  ];
  const holders = filterHolders(table, WT_PATH, 200);
  assert.deepEqual(holders, []);
});

run('filterHolders returns [] when no process names the target path', () => {
  const table = [
    { ProcessId: 1, ParentProcessId: 0, Name: 'conhost.exe', CommandLine: 'conhost.exe' },
    { ProcessId: 200, ParentProcessId: 1, Name: 'node.exe', CommandLine: 'node other-script.cjs' },
  ];
  const holders = filterHolders(table, WT_PATH, 200);
  assert.deepEqual(holders, []);
});

if (failures.length) {
  console.error(`remove-worktree.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`remove-worktree.test: ${passed} passed`);
