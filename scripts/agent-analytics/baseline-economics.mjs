#!/usr/bin/env node

/**
 * Tempdoc 743 Phase 1 — "baseline economics" instrument.
 *
 * Per-session token cost computed directly from Claude Code transcript JSONL
 * files (transcript-first — NOT via the events store, which is broken for
 * worktree sessions), joined to the git session->merge link
 * (tmp/agent-telemetry/session-merges.ndjson), reported over a date window.
 *
 * Discovery scans `<projectsRoot>/<projectDirSlug>/<sessionId>.jsonl` across
 * every project dir whose slug matches /justsearch/i (main checkout +
 * worktrees + jseval subdirs all show up as distinct project dirs under
 * Claude Code's project-path slugging). Subagent transcripts live at
 * `<projectDir>/<sessionId>/subagents/agent-*.jsonl`.
 *
 * Usage:
 *   node baseline-economics.mjs --md
 *   node baseline-economics.mjs --json
 *   node baseline-economics.mjs --since 2026-07-01 --until 2026-07-15 --md
 *   node baseline-economics.mjs --merges <path> --projects-root <path>
 *
 * Design law (tempdoc 743): telemetry survives only if a workflow moment
 * re-runs it. See record-merge.mjs's best-effort costs.ndjson upsert, which
 * reuses computeSessionCost() from this module.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parseSessionTokens, isKnownModel, mergeByModel, round } from './lib/transcript-cost.mjs';
import { loadExclusionKeys, loadExclusionMatcher, fmtScopeExclusion } from './lib/telemetry-io.mjs';
import {
  DEFAULT_PROJECTS_ROOT,
  discoverProjectDirs,
  listSubagentPaths,
  firstTranscriptTimestamp,
} from './lib/transcript-store.mjs';

export const DEFAULT_SINCE = '2026-06-18';
// Re-exported (886 §12 PR 5a: discovery migrated onto lib/transcript-store.mjs)
// — record-merge.mjs imports DEFAULT_PROJECTS_ROOT/findSessionTranscript from
// THIS module, so both names must keep resolving here.
export { DEFAULT_PROJECTS_ROOT, firstTranscriptTimestamp };
const MAIN_CHECKOUT_FALLBACK = 'F:\\justsearch-public';
const MERGES_RELATIVE = path.join('tmp', 'agent-telemetry', 'session-merges.ndjson');
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));

export const CAVEATS = [
  'Structural break 2026-07-14: tempdoc 727 friction hooks merged (bash-guard/repeat-guard/build-counter tuning) — pre/post cost comparisons across this date are not apples-to-apples.',
  'Structural break 2026-07-15: model-routing change (explicit `model: "sonnet"` on implementation subagents) — pre/post token/cost mix across this date reflects a deliberate policy change, not drift.',
  'Data-limited left edge: session-merges.ndjson starts 2026-06-30 — sessions with a transcript start before that date can show cost with zero attributed merges purely because the merge-link store did not exist yet, not because no merge happened.',
  'Window-edge attribution: cross-session dedup assigns a re-carried turn to the EARLIEST session that carries it, but sessions starting before --since are never costed, so their keys are not in scope. A resumed session at the left edge therefore keeps history that originated outside the window, and totals are mildly sensitive to --since (a wider window moves those tokens to the origin session, it does not create or destroy them). Inherent to windowing; compare like-for-like windows, and do not read a --since change as a real cost movement.',
  'RESTATEMENT, not improvement (tempdoc 856 §7): merge rows are now deduplicated on (session_id, merge_commit) and filtered to commits that are ancestors of origin/main. Measured over the window since 2026-06-20, and independently re-measured as identical over the DEFAULT_SINCE of 2026-06-18 that this script uses when no window is given (both windows contain the same merge rows), this moves 400 raw rows to 238 (-40.5%), and the `other` class over raw rows collapses from 103 to 13. The ATTRIBUTED merge count — the denominator of cost_per_merge_attributed — moves less, 171 to 165 (-3.5%), because most rejected rows were already unattributable and already outside that denominator; cost/merge therefore rises ~3.6% on unchanged spend. Both are a bookkeeping correction to the ledger, NOT a change in delivery rate or in cost — do not read either step as a trend, and do not compare a post-filter number against a pre-filter one.',
];

// --- CLI arg parsing ---------------------------------------------------

export function parseArgs(argv) {
  const opts = {
    since: DEFAULT_SINCE,
    until: null,
    merges: null,
    json: false,
    md: false,
    projectsRoot: DEFAULT_PROJECTS_ROOT,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--merges') opts.merges = argv[++i];
    else if (a === '--projects-root') opts.projectsRoot = argv[++i];
    else if (a === '--json') opts.json = true;
    else if (a === '--md') opts.md = true;
  }
  return opts;
}

/**
 * Resolve the default session-merges.ndjson path relative to the git common
 * dir (works from any worktree, since the merges store lives in the main
 * checkout only). Fails open to the literal known main-checkout path if git
 * can't be invoked (e.g. running outside a repo).
 */
export function resolveDefaultMergesPath({ cwd = SCRIPT_DIR } = {}) {
  try {
    const commonDir = execFileSync(
      'git', ['rev-parse', '--path-format=absolute', '--git-common-dir'],
      { cwd, encoding: 'utf8' },
    ).trim();
    const mainCheckout = path.dirname(commonDir); // parent of `.git`
    return path.join(mainCheckout, MERGES_RELATIVE);
  } catch {
    return path.join(MAIN_CHECKOUT_FALLBACK, MERGES_RELATIVE);
  }
}

// --- Exclusion list ------------------------------------------------------
// Matcher + key loader live in lib/telemetry-io.mjs (tempdoc 858 §7): four
// scripts read friction-excluded-sessions.json and three had forked their own
// copy, with two different match rules. Re-exported here because this module's
// callers and tests already reach for them by this name.
export { loadExclusionKeys, loadExclusionMatcher } from './lib/telemetry-io.mjs';

// --- Merges ----------------------------------------------------------------

/**
 * Read the raw ledger. Deliberately unvalidated beyond JSON parseability —
 * dedup and the origin/main ancestry filter live in buildReport (tempdoc 856
 * §7) so they stay pure and unit-testable, and so their rejects can be
 * REPORTED rather than dropped here where nothing could see them.
 */
export function loadMerges(filePath) {
  let content;
  try {
    content = fs.readFileSync(filePath, 'utf8');
  } catch {
    return [];
  }
  const rows = [];
  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    try { rows.push(JSON.parse(line)); } catch { /* skip malformed */ }
  }
  return rows;
}

/** How many rejected rows to name in the report per reject class. */
const REJECT_SAMPLE_SIZE = 5;

/**
 * Build a cached `commit -> 'ancestor' | 'off-main' | 'unresolvable'` oracle
 * backed by `git merge-base --is-ancestor <commit> <ref>` (exit 0 = ancestor,
 * 1 = not an ancestor, 128 = commit does not resolve).
 *
 * Returns NULL when `ref` itself does not resolve, rather than classifying
 * every row as off-main: a missing ref is "we could not check", not "these
 * commits are not on main" (tempdoc 856 §9.2 — absent evidence is not
 * negative evidence). buildReport then reports the filter as not applied.
 */
export function makeMergeCommitStatus({ cwd = SCRIPT_DIR, ref = 'origin/main' } = {}) {
  try {
    execFileSync('git', ['rev-parse', '--verify', `${ref}^{commit}`], { cwd, stdio: 'ignore' });
  } catch {
    return null;
  }
  const cache = new Map();
  return (commit) => {
    if (typeof commit !== 'string' || !/^[0-9a-f]{7,40}$/i.test(commit)) return 'unresolvable';
    const hit = cache.get(commit);
    if (hit) return hit;
    let status;
    try {
      execFileSync('git', ['merge-base', '--is-ancestor', commit, ref], { cwd, stdio: 'ignore' });
      status = 'ancestor';
    } catch (e) {
      status = e.status === 1 ? 'off-main' : 'unresolvable';
    }
    cache.set(commit, status);
    return status;
  };
}

/**
 * Collapse exact duplicate `(session_id, merge_commit)` pairs. Two DIFFERENT
 * sessions citing ONE commit is not a duplicate — co-authored work exists (15
 * such commits in the current window) and both sessions legitimately claim it.
 */
function dedupeMergeRows(rows) {
  const seen = new Set();
  const kept = [];
  const duplicates = [];
  for (const r of rows) {
    const key = `${r.session_id}|${r.merge_commit}`;
    if (seen.has(key)) { duplicates.push(r); continue; }
    seen.add(key);
    kept.push(r);
  }
  return { kept, duplicates };
}

function rejectSample(rows) {
  return rows.slice(0, REJECT_SAMPLE_SIZE).map((r) => ({
    session_id: r.session_id,
    merge_commit: typeof r.merge_commit === 'string' ? r.merge_commit : null,
  }));
}

function fmtRejectSample(sample) {
  return sample
    .map((s) => `${String(s.session_id).slice(0, 8)}→${s.merge_commit ? s.merge_commit.slice(0, 8) : '(no commit)'}`)
    .join(', ');
}

const CONVENTIONAL_TYPES = new Set([
  'feat', 'fix', 'docs', 'refactor', 'chore', 'test', 'perf', 'build', 'ci', 'style', 'revert',
]);

/** Classify a merge subject by its conventional-commit type prefix (e.g. `feat(x): ...`). */
export function classifyMerge(subject) {
  const m = /^(\w+)(\([^)]*\))?!?:\s/.exec(subject || '');
  if (!m) return 'other';
  const type = m[1].toLowerCase();
  return CONVENTIONAL_TYPES.has(type) ? type : 'other';
}

// --- ISO week rollup ---------------------------------------------------

/** ISO-8601 week key (`YYYY-Www`, Monday-start, week containing the year's first Thursday). */
export function isoWeekKey(date) {
  const d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const dayNum = (d.getUTCDay() + 6) % 7; // Mon=0..Sun=6
  d.setUTCDate(d.getUTCDate() - dayNum + 3); // nearest Thursday
  const firstThursday = new Date(Date.UTC(d.getUTCFullYear(), 0, 4));
  const firstDayNum = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDayNum + 3);
  const weekNum = 1 + Math.round((d - firstThursday) / (7 * 24 * 3600 * 1000));
  return `${d.getUTCFullYear()}-W${String(weekNum).padStart(2, '0')}`;
}

// --- Transcript discovery ------------------------------------------------
// Directory walk + subagent-path listing now come from lib/transcript-store.mjs
// (886 §12 PR 5a — this was the last hand-rolled `.claude/projects` discovery
// outside the ledger). `firstTranscriptTimestamp` moved there too (imported +
// re-exported above) because it is the one piece of this module's discovery
// that is NOT a duplicate of transcript-store's own walk: `discoverProjectDirs`
// only lists directories and `listSessions` only offers mtime, but this
// reader's whole point is `--since`/`--until` filtered on a session's
// DEFINITIONAL start time, which needs the per-file timestamp scan.

/**
 * Scan every /justsearch/i-matching project dir under `projectsRoot` for
 * `<sessionId>.jsonl` main transcripts whose first timestamped line falls in
 * [sinceMs, untilMs]. Applies `isExcluded` to in-window sessions and reports
 * how many were dropped by it (the scope-filter count for the report).
 */
export async function discoverSessions({ projectsRoot, sinceMs, untilMs, isExcluded }) {
  const included = [];
  let excludedCount = 0;

  const dirEntries = discoverProjectDirs(projectsRoot);
  if (dirEntries.length === 0) return { sessions: [], excludedCount: 0 };

  for (const dirEntry of dirEntries) {
    const projectDir = dirEntry.path;
    let files;
    try {
      files = fs.readdirSync(projectDir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const f of files) {
      if (!f.isFile() || !f.name.endsWith('.jsonl')) continue;
      const sessionId = f.name.slice(0, -'.jsonl'.length);
      const mainPath = path.join(projectDir, f.name);

      // eslint-disable-next-line no-await-in-loop -- streaming scan must stay sequential per file
      const startDate = await firstTranscriptTimestamp(mainPath);
      if (!startDate) continue;
      const startMs = startDate.getTime();
      if (startMs < sinceMs) continue;
      if (untilMs != null && startMs > untilMs) continue;

      if (isExcluded(sessionId)) {
        excludedCount += 1;
        continue;
      }

      const subagentPaths = listSubagentPaths(projectDir, sessionId);

      included.push({
        sessionId,
        projectDir: dirEntry.name,
        mainPath,
        subagentPaths,
        startTs: startDate.toISOString(),
      });
    }
  }
  return { sessions: included, excludedCount };
}

/**
 * Locate a single known session's transcripts by id (no timestamp scan needed —
 * the caller already knows the id, e.g. record-merge.mjs costing the session
 * that just produced a merge). Returns null if no `<sessionId>.jsonl` exists
 * under any /justsearch/i-matching project dir.
 */
export function findSessionTranscript(sessionId, projectsRoot = DEFAULT_PROJECTS_ROOT) {
  for (const dirEntry of discoverProjectDirs(projectsRoot)) {
    const projectDir = dirEntry.path;
    const mainPath = path.join(projectDir, `${sessionId}.jsonl`);
    if (!fs.existsSync(mainPath)) continue;
    const subagentPaths = listSubagentPaths(projectDir, sessionId);
    return { sessionId, projectDir: dirEntry.name, mainPath, subagentPaths };
  }
  return null;
}

// --- Per-session cost computation --------------------------------------

function tokensSum(t) {
  return t.input_tokens + t.output_tokens + t.cache_write_tokens + t.cache_read_tokens;
}

/**
 * Cost one session: main transcript + all subagent transcripts, per-model,
 * with an orchestrator (main-file) vs worker (subagents/) token split and an
 * unknown-model bucket (tokens unpriced at $0 but flagged, per
 * transcript-cost.mjs's isKnownModel).
 *
 * `seen` (optional) widens the dedup scope beyond this session — pass the same
 * map across a chronologically-ordered corpus (see costSessionsChronologically)
 * so a resumed session's re-carried history is counted once, in the session that
 * originated it. Omitted, the session is its own dedup scope.
 */
export function computeSessionCost({ sessionId, projectDir, mainPath, subagentPaths, startTs, seen }) {
  const { main, subagents } = parseSessionTokens({ mainPath, subagentPaths, seen });
  const subFound = subagents.found;
  const subMissing = subagents.missing;
  const subTotals = subagents.totals;

  const modelMix = mergeByModel({}, main.by_model);
  mergeByModel(modelMix, subagents.by_model);

  const unknownModelTokens = {};
  for (const [model, bucket] of Object.entries(modelMix)) {
    if (!isKnownModel(model)) unknownModelTokens[model] = tokensSum(bucket);
  }

  const mainTokens = {
    input: main.input_tokens, output: main.output_tokens,
    cache_write: main.cache_write_tokens, cache_read: main.cache_read_tokens,
  };
  const subTokens = {
    input: subTotals.input_tokens, output: subTotals.output_tokens,
    cache_write: subTotals.cache_write_tokens, cache_read: subTotals.cache_read_tokens,
  };
  const orchestratorTokensTotal = tokensSum(main);
  const workerTokensTotal = tokensSum(subTotals);
  const totalCostUsd = round(main.cost_usd + subTotals.cost_usd);

  return {
    session_id: sessionId,
    project_dir: projectDir,
    start_ts: startTs,
    main: { cost_usd: round(main.cost_usd), tokens: mainTokens, turns: main.turns, model: main.model },
    subagents: {
      count: (subagentPaths || []).length, found: subFound, missing: subMissing,
      cost_usd: round(subTotals.cost_usd), tokens: subTokens, turns: subTotals.turns,
    },
    total_cost_usd: totalCostUsd,
    total_tokens: {
      input: mainTokens.input + subTokens.input,
      output: mainTokens.output + subTokens.output,
      cache_write: mainTokens.cache_write + subTokens.cache_write,
      cache_read: mainTokens.cache_read + subTokens.cache_read,
    },
    orchestrator_tokens_total: orchestratorTokensTotal,
    worker_tokens_total: workerTokensTotal,
    model_mix: modelMix,
    unknown_model_tokens: unknownModelTokens,
  };
}

/**
 * Cost a whole corpus of discovered sessions under ONE dedup scope, oldest
 * first (tempdoc 745 item B, D3). Claude Code re-carries a resumed session's
 * history into a NEW session id, so the same (message.id, requestId) turns
 * appear in two session files — measured at 3.42% of corpus tokens across ~11
 * resumed sessions. Deduping oldest-first makes the ORIGIN session keep those
 * tokens and the later re-carrying session not double-count them; the scope
 * cannot be a single session, because the duplication is ACROSS sessions.
 *
 * Accepted + documented: a per-session costs.ndjson row written at teardown by
 * record-merge.mjs still includes re-carried history (it costs one session in
 * isolation and cannot see the corpus). The window report this function feeds is
 * the authority; the row is a teardown convenience.
 */
export function costSessionsChronologically(discovered) {
  const seen = new Map();
  return [...discovered]
    .sort((a, b) => String(a.startTs).localeCompare(String(b.startTs)))
    .map((s) => computeSessionCost({ ...s, seen }));
}

// --- Report assembly -----------------------------------------------------

/** Filter merge rows to those whose own `ts` falls in [sinceMs, untilMs]. */
function filterMergesToWindow(merges, sinceMs, untilMs) {
  return merges.filter((m) => {
    if (!m.session_id || !m.ts) return false;
    const t = new Date(m.ts).getTime();
    if (Number.isNaN(t)) return false;
    if (t < sinceMs) return false;
    if (untilMs != null && t > untilMs) return false;
    return true;
  });
}

/**
 * Join costed sessions to the merge-link store and assemble the full report
 * (window totals, weekly rollup, per-session table, zero-merge sessions,
 * unknown-model warnings). Pure w.r.t. its inputs — no filesystem access —
 * so it's directly unit-testable with inline fixtures.
 *
 * `isExcludedSessionId` (tempdoc 743 Finding 2) lets a merge row whose session
 * isn't in `sessions` be classified as "deliberately excluded by the scope
 * filter" rather than lumped into the same "unattributable" bucket as a merge
 * whose session transcript genuinely can't be found — those are different
 * situations and were previously both silently dropped from the merge count.
 *
 * `mergeCommitStatus` (tempdoc 856 §7) is the optional ancestry oracle from
 * makeMergeCommitStatus. Omitted (or null), the ancestry filter is reported as
 * NOT applied rather than silently assumed to have passed. Ledger rejects —
 * duplicates, off-main, unresolvable — follow the same split-don't-drop rule
 * as the scope/unattributable split above: each is counted and sampled.
 */
export function buildReport({
  sessions, merges, since, until, excludedCount,
  isExcludedSessionId = () => false, mergeCommitStatus = null,
  exclusionKeyCount = 0,
}) {
  const sinceMs = new Date(since).getTime();
  const untilMs = until ? new Date(until).getTime() : null;
  const rawInWindowMerges = filterMergesToWindow(merges, sinceMs, untilMs);

  const { kept: dedupedMerges, duplicates } = dedupeMergeRows(rawInWindowMerges);

  const offMain = [];
  const unresolvable = [];
  const inWindowMerges = [];
  for (const m of dedupedMerges) {
    const status = mergeCommitStatus ? mergeCommitStatus(m.merge_commit) : 'ancestor';
    if (status === 'ancestor') inWindowMerges.push(m);
    else if (status === 'off-main') offMain.push(m);
    else unresolvable.push(m);
  }

  const costedSessionIds = new Set(sessions.map((s) => s.session_id));

  const mergesBySession = new Map();
  for (const m of inWindowMerges) {
    if (!mergesBySession.has(m.session_id)) mergesBySession.set(m.session_id, []);
    mergesBySession.get(m.session_id).push(m);
  }

  // Merges whose session never made it into `sessions` (no in-window costed
  // session record) would otherwise vanish from every downstream total with
  // no trace. Split by reason instead of dropping them.
  let mergesExcludedByScope = 0;
  let mergesUnattributable = 0;
  const unattributableSessionIds = new Set();
  for (const [sid, rows] of mergesBySession) {
    if (costedSessionIds.has(sid)) continue;
    if (isExcludedSessionId(sid)) {
      mergesExcludedByScope += rows.length;
    } else {
      mergesUnattributable += rows.length;
      unattributableSessionIds.add(sid);
    }
  }

  const sessionRows = [];
  const zeroMergeSessions = [];
  const byClass = new Map();
  const unknownModels = new Map();
  let totalCost = 0;
  let totalMerges = 0;
  let orchestratorTokens = 0;
  let workerTokens = 0;
  const weekly = new Map();

  for (const s of sessions) {
    totalCost += s.total_cost_usd;
    orchestratorTokens += s.orchestrator_tokens_total;
    workerTokens += s.worker_tokens_total;

    for (const [model, tokens] of Object.entries(s.unknown_model_tokens)) {
      const prev = unknownModels.get(model) || { tokens: 0, sessions: 0 };
      prev.tokens += tokens;
      prev.sessions += 1;
      unknownModels.set(model, prev);
    }

    const sessionMerges = mergesBySession.get(s.session_id) || [];
    const mergeCount = sessionMerges.length;
    const costPerMerge = mergeCount > 0 ? round(s.total_cost_usd / mergeCount) : null;

    const classifiedMerges = sessionMerges.map((m) => ({
      merge_commit: m.merge_commit, subject: m.subject, ts: m.ts,
      class: classifyMerge(m.subject), cost_usd: costPerMerge,
    }));

    for (const cm of classifiedMerges) {
      const prev = byClass.get(cm.class) || { count: 0, cost_usd: 0 };
      prev.count += 1;
      prev.cost_usd += cm.cost_usd ?? 0;
      byClass.set(cm.class, prev);
    }

    totalMerges += mergeCount;

    const row = {
      session_id: s.session_id,
      project_dir: s.project_dir,
      start_ts: s.start_ts,
      total_cost_usd: s.total_cost_usd,
      orchestrator_tokens_total: s.orchestrator_tokens_total,
      worker_tokens_total: s.worker_tokens_total,
      subagent_count: s.subagents.count,
      model_mix: s.model_mix,
      merge_count: mergeCount,
      cost_per_merge: costPerMerge,
      merges: classifiedMerges,
    };
    sessionRows.push(row);
    if (mergeCount === 0) zeroMergeSessions.push({ session_id: s.session_id, start_ts: s.start_ts, total_cost_usd: s.total_cost_usd });

    const week = isoWeekKey(new Date(s.start_ts));
    if (!weekly.has(week)) weekly.set(week, { week, sessions: 0, cost_usd: 0, merges: 0 });
    const w = weekly.get(week);
    w.sessions += 1;
    w.cost_usd += s.total_cost_usd;
    w.merges += mergeCount;
  }

  sessionRows.sort((a, b) => b.total_cost_usd - a.total_cost_usd);

  const byClassObj = {};
  for (const [cls, v] of byClass.entries()) {
    byClassObj[cls] = { count: v.count, cost_usd: round(v.cost_usd), cost_per_merge: v.count > 0 ? round(v.cost_usd / v.count) : null };
  }

  const weeklyArr = [...weekly.values()]
    .sort((a, b) => a.week.localeCompare(b.week))
    .map((w) => ({ ...w, cost_usd: round(w.cost_usd), cost_per_merge: w.merges > 0 ? round(w.cost_usd / w.merges) : null }));

  const unknownModelsObj = {};
  for (const [model, v] of unknownModels.entries()) unknownModelsObj[model] = v;

  const totalTokens = orchestratorTokens + workerTokens;

  const dynamicCaveats = [];
  if (mergesUnattributable > 0) {
    const ids = [...unattributableSessionIds].map((id) => id.slice(0, 8)).join(', ');
    dynamicCaveats.push(
      `${mergesUnattributable} merge(s) in this window have no discoverable session transcript ` +
      `(unattributable — excluded from cost/merge): session ids ${ids}`,
    );
  }
  if (mergesExcludedByScope > 0) {
    dynamicCaveats.push(
      `${mergesExcludedByScope} merge(s) in this window belong to a session excluded by the ` +
      `friction-excluded-sessions.json scope filter — deliberately not costed, excluded from cost/merge.`,
    );
  }
  // Do not let a zero read as an observation (tempdoc 858 §7). The scope filter
  // is a dated capture whose ids rotate out of ~/.claude/projects; when it
  // matches nothing, the honest statement is that it performed no exclusion —
  // NOT that nothing in this window needed excluding, which this script never
  // checked.
  if (exclusionKeyCount > 0 && excludedCount === 0 && mergesExcludedByScope === 0) {
    dynamicCaveats.push(
      `Scope filter INERT for this window: none of the ${exclusionKeyCount} session ids listed in ` +
      `friction-excluded-sessions.json matched a session here, so no exclusion was performed. ` +
      `Those ids are a hand classification captured on 2026-07-14 and Claude Code rotates ` +
      `transcripts away, so a zero here means the filter could not act — it is NOT evidence that ` +
      `every session in this window is in-scope developer-agent work. Nothing re-classifies new ` +
      `sessions; see the file's _reasoning for the three exclusion classes if a re-run is needed.`,
    );
  }
  const duplicateSample = rejectSample(duplicates);
  const offMainSample = rejectSample(offMain);
  const unresolvableSample = rejectSample(unresolvable);
  if (duplicates.length > 0) {
    dynamicCaveats.push(
      `${duplicates.length} ledger row(s) in this window were exact duplicate (session_id, merge_commit) ` +
      `pairs and were collapsed to one — sample: ${fmtRejectSample(duplicateSample)}. ` +
      `Two DIFFERENT sessions citing one commit is co-authorship, not a duplicate, and is kept.`,
    );
  }
  if (offMain.length > 0) {
    dynamicCaveats.push(
      `${offMain.length} ledger row(s) in this window cite a commit that is not an ancestor of origin/main ` +
      `(typically a local \`Merge branch 'main'\` commit recorded as a merge) and were excluded — ` +
      `sample: ${fmtRejectSample(offMainSample)}. Judged against the LOCAL origin/main ref: a stale ref ` +
      `over-reports this class.`,
    );
  }
  if (unresolvable.length > 0) {
    dynamicCaveats.push(
      `${unresolvable.length} ledger row(s) in this window cite a commit that does not resolve in this ` +
      `repository (missing or garbage merge_commit, or a commit since garbage-collected) and were ` +
      `excluded — sample: ${fmtRejectSample(unresolvableSample)}.`,
    );
  }
  if (!mergeCommitStatus) {
    dynamicCaveats.push(
      'Ancestry filter NOT applied in this run (no origin/main oracle available): rows citing a commit ' +
      'off origin/main are still counted. Merge counts here are NOT comparable to a run where the filter ' +
      'was applied.',
    );
  }

  return {
    window: { since, until },
    generated_at: new Date().toISOString(),
    caveats: [...CAVEATS, ...dynamicCaveats],
    totals: {
      sessions_in_window: sessions.length,
      sessions_excluded_by_scope: excludedCount,
      // Denominator for the line above: how many ids the scope filter even had
      // to match with. `excluded: 0` out of `listed: 31` is an inert filter,
      // not a clean window — a consumer cannot tell those apart without this.
      scope_filter_ids_listed: exclusionKeyCount,
      scope_filter_performed_exclusion: excludedCount > 0,
      sessions_with_zero_merges: zeroMergeSessions.length,
      total_cost_usd: round(totalCost),
      // Raw in-window ledger rows, BEFORE dedup/ancestry. The identity is
      //   merge_rows_in_window = attributed + excluded_by_scope + unattributable
      //                          + duplicate_rows + off_main + unresolvable_commit
      merge_rows_in_window: rawInWindowMerges.length,
      merges_eligible: inWindowMerges.length,
      merges_attributed: totalMerges,
      merges_excluded_by_scope: mergesExcludedByScope,
      merges_unattributable: mergesUnattributable,
      unattributable_session_ids: [...unattributableSessionIds],
      // Ledger rejects (tempdoc 856 §7) — reported, never silently dropped.
      merges_duplicate_rows: duplicates.length,
      merges_off_main: offMain.length,
      merges_unresolvable_commit: unresolvable.length,
      duplicate_row_sample: duplicateSample,
      off_main_row_sample: offMainSample,
      unresolvable_row_sample: unresolvableSample,
      ancestry_filter_applied: Boolean(mergeCommitStatus),
      // Labeled explicitly (tempdoc 743 Finding 2): computed over ATTRIBUTED
      // merges only — a merge with no costed session contributes no cost and
      // is excluded from both numerator and denominator here.
      cost_per_merge_attributed: totalMerges > 0 ? round(totalCost / totalMerges) : null,
      by_merge_class: byClassObj,
      token_split: {
        orchestrator_tokens: orchestratorTokens,
        worker_tokens: workerTokens,
        orchestrator_pct: totalTokens > 0 ? round((orchestratorTokens / totalTokens) * 100, 1) : null,
        worker_pct: totalTokens > 0 ? round((workerTokens / totalTokens) * 100, 1) : null,
      },
      unknown_models: unknownModelsObj,
    },
    weekly: weeklyArr,
    sessions: sessionRows,
    zero_merge_sessions: zeroMergeSessions,
  };
}

// --- Markdown rendering ----------------------------------------------------

function fmtUsd(n) {
  return n == null ? 'n/a' : `$${n.toFixed(4)}`;
}
function fmtK(n) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

/**
 * Thin adapter onto lib/telemetry-io.mjs's fmtScopeExclusion, mapping this
 * report's totals shape onto the shared renderer. `merges_excluded_by_scope` is
 * absent at the discovery-time call site, where merges are not loaded yet; the
 * shared wording is session-scoped so it stays true there either way.
 */
export function fmtScopeFilter(t) {
  return fmtScopeExclusion({
    excluded: t.sessions_excluded_by_scope,
    listed: t.scope_filter_ids_listed ?? 0,
    mergesExcluded: t.merges_excluded_by_scope ?? 0,
  });
}

export function formatMarkdown(report) {
  const lines = [];
  lines.push('# Baseline Economics Report (tempdoc 743 Phase 1)');
  lines.push('');
  lines.push(`Window: ${report.window.since} .. ${report.window.until ?? 'now'} (generated ${report.generated_at})`);
  lines.push('');
  lines.push('## Caveats');
  for (const c of report.caveats) lines.push(`- ${c}`);
  lines.push('');

  const t = report.totals;
  lines.push('## Window totals');
  lines.push(`- Sessions in window: ${t.sessions_in_window} (${fmtScopeFilter(t)})`);
  lines.push(`- Total cost (attributed sessions): ${fmtUsd(t.total_cost_usd)}`);
  lines.push(`- Merge rows in window (raw ledger): ${t.merge_rows_in_window} = attributed ${t.merges_attributed} + excluded-by-scope ${t.merges_excluded_by_scope} + unattributable ${t.merges_unattributable} + duplicate ${t.merges_duplicate_rows} + off-main ${t.merges_off_main} + unresolvable ${t.merges_unresolvable_commit}`);
  if (t.merges_unattributable > 0) {
    lines.push(`  - Unattributable session ids (no discoverable transcript): ${t.unattributable_session_ids.map((id) => id.slice(0, 8)).join(', ')}`);
  }
  lines.push(`- Ledger rows surviving dedup + ancestry (eligible): ${t.merges_eligible}${t.ancestry_filter_applied ? '' : ' (ancestry filter NOT applied — no origin/main oracle)'}`);
  if (t.merges_duplicate_rows > 0) {
    lines.push(`  - Duplicate (session_id, merge_commit) rows collapsed: ${t.merges_duplicate_rows} — sample: ${fmtRejectSample(t.duplicate_row_sample)}`);
  }
  if (t.merges_off_main > 0) {
    lines.push(`  - Rows citing a commit not on origin/main: ${t.merges_off_main} — sample: ${fmtRejectSample(t.off_main_row_sample)}`);
  }
  if (t.merges_unresolvable_commit > 0) {
    lines.push(`  - Rows citing a commit that does not resolve: ${t.merges_unresolvable_commit} — sample: ${fmtRejectSample(t.unresolvable_row_sample)}`);
  }
  lines.push(`- Cost/merge (attributed only): ${fmtUsd(t.cost_per_merge_attributed)}`);
  lines.push(`- Sessions with zero merges: ${t.sessions_with_zero_merges}`);
  lines.push(`- Orchestrator/worker token split: ${fmtK(t.token_split.orchestrator_tokens)} orchestrator (${t.token_split.orchestrator_pct ?? 'n/a'}%) / ${fmtK(t.token_split.worker_tokens)} worker (${t.token_split.worker_pct ?? 'n/a'}%)`);
  lines.push('');

  if (Object.keys(t.unknown_models).length > 0) {
    lines.push('## ⚠ Unknown-model token warnings');
    lines.push('');
    lines.push('| Model | Tokens | Sessions |');
    lines.push('|---|---|---|');
    for (const [model, v] of Object.entries(t.unknown_models)) {
      lines.push(`| ${model} | ${fmtK(v.tokens)} | ${v.sessions} |`);
    }
    lines.push('');
  }

  lines.push('## Cost/merge by conventional-commit class');
  lines.push('');
  lines.push('| Class | Merges | Cost | Cost/merge |');
  lines.push('|---|---|---|---|');
  for (const [cls, v] of Object.entries(t.by_merge_class).sort((a, b) => b[1].cost_usd - a[1].cost_usd)) {
    lines.push(`| ${cls} | ${v.count} | ${fmtUsd(v.cost_usd)} | ${fmtUsd(v.cost_per_merge)} |`);
  }
  lines.push('');

  lines.push('## Weekly rollup');
  lines.push('');
  lines.push('| Week | Sessions | Cost | Merges | Cost/merge |');
  lines.push('|---|---|---|---|---|');
  for (const w of report.weekly) {
    lines.push(`| ${w.week} | ${w.sessions} | ${fmtUsd(w.cost_usd)} | ${w.merges} | ${fmtUsd(w.cost_per_merge)} |`);
  }
  lines.push('');

  lines.push('## Per-session (sorted by cost)');
  lines.push('');
  lines.push('| Session | Start | Cost | Orchestrator tok | Worker tok | Subagents | Merges | Cost/merge |');
  lines.push('|---|---|---|---|---|---|---|---|');
  for (const s of report.sessions) {
    lines.push(`| ${s.session_id.slice(0, 8)} | ${s.start_ts} | ${fmtUsd(s.total_cost_usd)} | ${fmtK(s.orchestrator_tokens_total)} | ${fmtK(s.worker_tokens_total)} | ${s.subagent_count} | ${s.merge_count} | ${fmtUsd(s.cost_per_merge)} |`);
  }
  lines.push('');

  lines.push('## Sessions with zero merges');
  lines.push('');
  if (report.zero_merge_sessions.length === 0) {
    lines.push('(none)');
  } else {
    lines.push('| Session | Start | Cost |');
    lines.push('|---|---|---|');
    for (const s of report.zero_merge_sessions) {
      lines.push(`| ${s.session_id.slice(0, 8)} | ${s.start_ts} | ${fmtUsd(s.total_cost_usd)} |`);
    }
  }
  lines.push('');

  return lines.join('\n');
}

// --- Main ------------------------------------------------------------------

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const mergesPath = opts.merges || resolveDefaultMergesPath();
  const excludedPath = path.join(SCRIPT_DIR, 'friction-excluded-sessions.json');

  const sinceMs = new Date(opts.since).getTime();
  const untilMs = opts.until ? new Date(opts.until).getTime() : null;
  const exclusionKeyCount = loadExclusionKeys(excludedPath).length;
  const isExcluded = loadExclusionMatcher(excludedPath);

  console.error(`baseline-economics: scanning ${opts.projectsRoot} for sessions since ${opts.since}${opts.until ? ` until ${opts.until}` : ''}...`);
  const { sessions: discovered, excludedCount } = await discoverSessions({
    projectsRoot: opts.projectsRoot, sinceMs, untilMs, isExcluded,
  });
  console.error(`baseline-economics: ${discovered.length} sessions in window (${fmtScopeFilter({ sessions_excluded_by_scope: excludedCount, scope_filter_ids_listed: exclusionKeyCount })})`);

  const costedSessions = costSessionsChronologically(discovered);
  const merges = loadMerges(mergesPath);
  const mergeCommitStatus = makeMergeCommitStatus({ cwd: SCRIPT_DIR });
  if (!mergeCommitStatus) {
    console.error('baseline-economics: origin/main does not resolve — ancestry filter NOT applied (reported in caveats)');
  }

  const report = buildReport({
    sessions: costedSessions, merges, since: opts.since, until: opts.until, excludedCount,
    isExcludedSessionId: isExcluded, mergeCommitStatus, exclusionKeyCount,
  });

  if (opts.json) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  } else {
    process.stdout.write(formatMarkdown(report) + '\n');
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
