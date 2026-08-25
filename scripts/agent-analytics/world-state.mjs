#!/usr/bin/env node
/**
 * World-state query (tempdoc 743 P-J) — one entry point answering the "queryable world state"
 * deficit named in tempdoc 743's second-wave theorization: worktree staleness, live sessions,
 * tempdoc-number allocation, and (read-only, best-effort) dev-stack ownership. A pure function of
 * disk + local git state + best-effort `claude agents --json`; no daemon, no stored state, no
 * network calls (offline by design — no `gh` in v1, keep it fast).
 *
 * Sections:
 *   (a) WORKTREES     — per registered worktree: branch, dirty count, ahead/behind vs
 *                        origin/main, pushed?, last-commit age, VERDICT.
 *   (b) LIVE SESSIONS  — `claude agents --json` (best-effort; degrades to "unavailable").
 *   (c) TEMPDOC NUMBERS — highest claimed + next free (scripts/ci/lib/tempdoc-scan.mjs, the same
 *                        scanner `check-tempdoc-numbers.mjs` uses for the merge gate — "one
 *                        scanner, two consumers") + informational pick-time conflicts.
 *   (d) STACK          — best-effort read of the MAIN checkout's tmp/dev-runner/active.json +
 *                        linked run.json for runId/ports; "not running or unknown" otherwise.
 *                        Authoritative ownership state still comes from quick_health — this is a
 *                        read-only orientation glance, not a replacement.
 *   (e) AGENT SPAWNS   — tempdoc 861 §6.4 `orientation` occasion: registered + observed
 *                        agent-spawned helper processes (ui-shot's Vite, `serve-worktree-fe`, the
 *                        OTel sink) with their §6.3 verdicts. Read-only: this occasion binds to
 *                        `capability: 'advisory'` in the reaper's frozen `OCCASIONS` map, so it
 *                        can never obtain a kill list — a ready-to-run kill line is printed for
 *                        the observed tier, never auto-run.
 *
 * Every external probe (git, claude CLI, dev-runner state files) is wrapped in try/catch and
 * degrades to an explicit "unavailable"/"unknown" line — this tool must never crash the orienting
 * agent, and must stay well under 10s.
 *
 * Usage: node scripts/agent-analytics/world-state.mjs [--json]
 */

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { collectClaims, divergentInFlightCollisions, nextFreeNumber } from '../ci/lib/tempdoc-scan.mjs';

// Tempdoc 861 §7.5 — the documented cross-format interop: an ESM tool pulls the shared `.cjs`
// dev-stack libs in via `createRequire`, exactly as `otlp-sink-ensure.mjs` already does.
const require = createRequire(import.meta.url);
const { gatherAgentSpawnOrientation, describeEntry } = require('../dev/lib/agent-spawn-sweep.cjs');

const STALE_DAYS_THRESHOLD = 3;

// ---------------------------------------------------------------------------------------------
// Pure functions (exported for tests — no I/O, no process access beyond their arguments).
// ---------------------------------------------------------------------------------------------

/**
 * Verdict for a single worktree row. Pure — takes already-gathered facts, no git/fs access.
 *
 * - STRANDED-FINISHED: clean, has commits ahead of origin/main, none of them pushed anywhere,
 *   and the last commit is older than the staleness threshold — work that looks finished but was
 *   never sent anywhere.
 * - STALE-CANDIDATE: clean and zero commits ahead of origin/main — nothing unique left to lose.
 * - DIRTY-IDLE: uncommitted changes sitting untouched past the staleness threshold.
 * - ACTIVE: everything else (the default — recent activity, or state we can't yet classify as
 *   one of the above three).
 *
 * `null` for `aheadCount`/`pushed`/`lastCommitAgeDays` means "unknown" (a probe failed) and is
 * treated conservatively — it never *causes* a STRANDED-FINISHED/STALE-CANDIDATE/DIRTY-IDLE
 * verdict, only ACTIVE (the safe default when the evidence is incomplete).
 *
 * @param {{dirty: boolean|null, aheadCount: number|null, pushed: boolean|null, lastCommitAgeDays: number|null}} row
 * @returns {'ACTIVE'|'STRANDED-FINISHED'|'STALE-CANDIDATE'|'DIRTY-IDLE'}
 */
export function computeVerdict({ dirty, aheadCount, pushed, lastCommitAgeDays }) {
  const isStale = typeof lastCommitAgeDays === 'number' && lastCommitAgeDays > STALE_DAYS_THRESHOLD;

  if (dirty === true) {
    return isStale ? 'DIRTY-IDLE' : 'ACTIVE';
  }
  if (dirty === false) {
    if (aheadCount != null && aheadCount > 0 && pushed === false && isStale) {
      return 'STRANDED-FINISHED';
    }
    if (aheadCount === 0) {
      return 'STALE-CANDIDATE';
    }
  }
  return 'ACTIVE';
}

/** Numbers claimed under 2+ distinct basenames anywhere (origin or any worktree) — informational
 * only; broader than `divergentInFlightCollisions` (which ignores origin-present basenames to
 * avoid flagging legitimate on-origin multi-file batches). Surfaces the same pre-existing #720/
 * #729 in-flight collisions plus anything already reused on origin, for situational awareness at
 * pick time — not a pass/fail gate. */
export function pickTimeConflicts(claims) {
  const out = [];
  for (const [number, byName] of claims) {
    if (byName.size >= 2) {
      out.push({ number, basenames: [...byName.keys()].sort() });
    }
  }
  return out.sort((a, b) => Number(a.number) - Number(b.number));
}

function formatAge(days) {
  if (days == null) return '?';
  if (days < 1) return '<1d';
  return `${Math.floor(days)}d`;
}
function formatBool(v) {
  if (v === null) return '?';
  return v ? 'yes' : 'no';
}
function formatCount(v) {
  return v == null ? '?' : String(v);
}

/** Render the full report as markdown. Pure — takes the already-gathered `data` object. */
export function renderMarkdown(data) {
  const lines = [];
  lines.push('# World state', '');
  lines.push(`_generated ${data.generatedAt}_`, '');

  lines.push('## Worktrees', '');
  lines.push('| WORKTREE | BRANCH | DIRTY | AHEAD | BEHIND | PUSHED | AGE | VERDICT |');
  lines.push('|---|---|---|---|---|---|---|---|');
  for (const w of data.worktrees) {
    lines.push(
      `| ${w.name}${w.isMain ? ' [main]' : ''} | ${w.branch} | ${formatCount(w.dirtyCount)} | ${formatCount(w.aheadCount)} | ${formatCount(w.behindCount)} | ${formatBool(w.pushed)} | ${formatAge(w.lastCommitAgeDays)} | ${w.verdict} |`,
    );
  }
  lines.push('');

  lines.push('## Live sessions', '');
  if (data.sessions.available) {
    if (data.sessions.rows.length === 0) {
      lines.push('_no live interactive sessions_');
    } else {
      lines.push('| PID | NAME | STATUS | CWD |');
      lines.push('|---|---|---|---|');
      for (const s of data.sessions.rows) {
        lines.push(`| ${s.pid} | ${s.name || '?'} | ${s.status || '?'} | ${s.cwd} |`);
      }
    }
  } else {
    lines.push(`unavailable — ${data.sessions.reason}`);
  }
  lines.push('');

  lines.push('## Tempdoc numbers', '');
  lines.push(`highest claimed: **#${data.tempdocNumbers.highestClaimed}**  |  next free: **#${data.tempdocNumbers.nextFree}**  |  ${data.tempdocNumbers.distinctNumbers} distinct numbers across ${data.tempdocNumbers.worktreeCount} worktree(s) + origin/${data.tempdocNumbers.defaultBranch}`);
  if (data.tempdocNumbers.mergeGateCollisions.length > 0) {
    lines.push('', `merge-gate collisions (check-tempdoc-numbers would fail): ${data.tempdocNumbers.mergeGateCollisions.length}`);
    for (const c of data.tempdocNumbers.mergeGateCollisions) lines.push(`  - #${c.number}: ${c.detail}`);
  }
  if (data.tempdocNumbers.pickTimeConflicts.length > 0) {
    lines.push('', `pick-time conflicts (informational — 2+ distinct basenames anywhere): ${data.tempdocNumbers.pickTimeConflicts.length}`);
    for (const c of data.tempdocNumbers.pickTimeConflicts) lines.push(`  - #${c.number}: ${c.basenames.join(', ')}`);
  }
  lines.push('');

  lines.push('## Stack', '');
  lines.push(data.stack.available ? data.stack.summary : `not running or unknown — use quick_health (${data.stack.reason})`);
  lines.push('');

  lines.push('## Agent spawns', '');
  if (data.agentSpawns.available) {
    const { registered, observed } = data.agentSpawns;
    if (registered.length === 0 && observed.length === 0) {
      lines.push('_no registered or observed agent-spawned processes_');
    } else {
      for (const e of registered) lines.push(`- REGISTERED ${describeEntry(e).replace(/\n\s*/g, ' ')}`);
      for (const e of observed) lines.push(`- OBSERVED ${describeEntry(e).replace(/\n\s*/g, ' ')}`);
    }
  } else {
    lines.push(`unavailable — ${data.agentSpawns.reason}`);
  }
  lines.push('');

  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------
// I/O-touching gatherers. Every git/process/fs call is individually try/catch-guarded so one
// failing probe degrades that one field to null/"unavailable" rather than crashing the report.
// ---------------------------------------------------------------------------------------------

function gitOrNull(args, opts = {}) {
  try {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 5000, ...opts }).trim();
  } catch {
    return null;
  }
}

function listWorktreePaths() {
  const out = gitOrNull(['worktree', 'list', '--porcelain']);
  if (!out) return [process.cwd()];
  const paths = out
    .split('\n')
    .filter((l) => l.startsWith('worktree '))
    .map((l) => l.slice('worktree '.length).trim())
    .filter(Boolean);
  return paths.length ? paths : [process.cwd()];
}

/** Main checkout root, derived from the shared `.git` common dir — robust regardless of worktree
 * listing order (tempdoc 743 P-J: don't assume "first worktree list entry is main"). */
function mainCheckoutRoot() {
  const commonDir = gitOrNull(['rev-parse', '--git-common-dir']);
  if (!commonDir) return null;
  const abs = path.resolve(commonDir);
  return abs.replace(/[\\/]\.git[\\/]?$/, '');
}

function gatherWorktreeRow(wtPath, mainRoot) {
  const name = wtPath.replace(/\\/g, '/').split('/').pop();
  const isMain = mainRoot != null && path.resolve(wtPath) === path.resolve(mainRoot);
  const branch = gitOrNull(['-C', wtPath, 'branch', '--show-current']) || gitOrNull(['-C', wtPath, 'rev-parse', '--short', 'HEAD']) || null;

  const statusOut = gitOrNull(['-C', wtPath, 'status', '--porcelain']);
  const dirty = statusOut === null ? null : statusOut.length > 0;
  const dirtyCount = statusOut === null ? null : statusOut.length === 0 ? 0 : statusOut.split('\n').filter(Boolean).length;

  let aheadCount = null;
  let behindCount = null;
  const leftRight = gitOrNull(['-C', wtPath, 'rev-list', '--left-right', '--count', 'origin/main...HEAD']);
  if (leftRight) {
    const [left, right] = leftRight.split(/\s+/);
    if (left != null && right != null && left !== '' && right !== '') {
      behindCount = Number(left);
      aheadCount = Number(right);
    }
  }

  let pushed = null;
  if (branch) {
    pushed = gitOrNull(['-C', wtPath, 'rev-parse', '--verify', '--quiet', `refs/remotes/origin/${branch}`]) !== null;
  }

  let lastCommitAgeDays = null;
  const ts = gitOrNull(['-C', wtPath, 'log', '-1', '--format=%ct']);
  if (ts) {
    const seconds = Number(ts);
    if (Number.isFinite(seconds)) lastCommitAgeDays = (Date.now() / 1000 - seconds) / 86400;
  }

  const row = { name, path: wtPath, isMain, branch: branch ?? 'unknown', dirty, dirtyCount, aheadCount, behindCount, pushed, lastCommitAgeDays };
  return { ...row, verdict: computeVerdict(row) };
}

function gatherWorktrees() {
  const mainRoot = mainCheckoutRoot();
  return listWorktreePaths().map((p) => gatherWorktreeRow(p, mainRoot));
}

function gatherSessions() {
  try {
    const res = spawnSync('claude', ['agents', '--json'], { encoding: 'utf8', timeout: 5000 });
    if (res.error) return { available: false, reason: res.error.message, rows: [] };
    if (res.status !== 0) return { available: false, reason: `exit ${res.status}`, rows: [] };
    const parsed = JSON.parse(res.stdout);
    if (!Array.isArray(parsed)) return { available: false, reason: 'unexpected output shape', rows: [] };
    return { available: true, rows: parsed };
  } catch (e) {
    return { available: false, reason: e.message, rows: [] };
  }
}

function gatherTempdocNumbers() {
  const { claims, worktreeCount, defaultBranch } = collectClaims({ cwd: process.cwd() });
  const mergeGateCollisions = divergentInFlightCollisions(claims);
  const conflicts = pickTimeConflicts(claims);
  const nextFree = nextFreeNumber(claims);
  return {
    distinctNumbers: claims.size,
    worktreeCount,
    defaultBranch,
    highestClaimed: nextFree - 1,
    nextFree,
    mergeGateCollisions,
    pickTimeConflicts: conflicts,
  };
}

function gatherStack() {
  const mainRoot = mainCheckoutRoot();
  if (!mainRoot) return { available: false, reason: 'could not resolve main checkout root' };
  try {
    const activePath = path.join(mainRoot, 'tmp', 'dev-runner', 'active.json');
    if (!fs.existsSync(activePath)) return { available: false, reason: 'no active.json' };
    const active = JSON.parse(fs.readFileSync(activePath, 'utf8'));
    if (!active?.runId) return { available: false, reason: 'active.json has no runId' };
    const runPath = path.join(mainRoot, 'tmp', 'dev-runner', 'runs', active.runId, 'run.json');
    let ports = 'ports unknown';
    if (fs.existsSync(runPath)) {
      const run = JSON.parse(fs.readFileSync(runPath, 'utf8'));
      ports = `apiPort=${run.apiPortActual ?? '?'} uiPort=${run.uiPortActual ?? '?'}`;
    }
    const holder = active.holder?.agentSessionId || active.holder?.source || 'unknown';
    return { available: true, summary: `runId=${active.runId} ${ports} holder=${holder}` };
  } catch (e) {
    return { available: false, reason: e.message };
  }
}

/** Tempdoc 861 §6.4 `orientation` occasion — read-only, never kills (enforced in the reaper's
 * frozen `OCCASIONS` map, not here: `capability: 'advisory'` mints no `reap` entry regardless of
 * what this gatherer passes in). `recordId === null` is how `reapEligible` marks an observed-tier
 * entry (no record behind it) — the same distinction `probeForeignRuns` makes with `source`. */
async function gatherAgentSpawns() {
  const mainRoot = mainCheckoutRoot();
  if (!mainRoot) return { available: false, reason: 'could not resolve main checkout root' };
  const result = await gatherAgentSpawnOrientation({ mainRepoRoot: mainRoot });
  if (!result.available) return result;
  const all = result.buckets.all;
  return {
    available: true,
    registered: all.filter((e) => e.recordId !== null),
    observed: all.filter((e) => e.recordId === null),
  };
}

// ---------------------------------------------------------------------------------------------
// Report assembly + CLI.
// ---------------------------------------------------------------------------------------------

export async function buildReport() {
  return {
    generatedAt: new Date().toISOString(),
    worktrees: gatherWorktrees(),
    sessions: gatherSessions(),
    tempdocNumbers: gatherTempdocNumbers(),
    stack: gatherStack(),
    agentSpawns: await gatherAgentSpawns(),
  };
}

async function main() {
  const jsonMode = process.argv.includes('--json');
  const data = await buildReport();
  if (jsonMode) {
    console.log(JSON.stringify(data, null, 2));
  } else {
    console.log(renderMarkdown(data));
  }
}

const isMainModule = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isMainModule) {
  main().catch((err) => {
    console.error(`world-state: ERROR: ${err && err.message ? err.message : err}`);
    process.exit(1);
  });
}
