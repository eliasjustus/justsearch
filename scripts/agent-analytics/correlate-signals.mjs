#!/usr/bin/env node

/**
 * Signal-outcome correlation analysis.
 *
 * Joins scores.ndjson with outcomes.ndjson by session_id and computes
 * Pearson r for each behavioral signal vs. task_completion (encoded as
 * complete=1.0, partial=0.5, failed/abandoned=0).
 *
 * Also computes:
 * - Per-type stratified correlations (tempdoc 285, step 6)
 * - Signal vs. cost and signal vs. duration correlations (tempdoc 285, step 7)
 *
 * Usage: node scripts/agent-analytics/correlate-signals.mjs
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  loadNdjsonMap,
  loadSessionReports,
  TELEMETRY_DIR,
  SCORES_FILE,
  COSTS_FILE,
  repoRoot,
} from './lib/telemetry-io.mjs';
import { loadJoinInputs, outcomeForSession } from './outcome-session.mjs';

const COMPLETION_WEIGHT = { complete: 1.0, partial: 0.5, failed: 0, abandoned: 0 };

/** The effect size this instrument's own legend calls a meaningful predictor. */
const MEANINGFUL_R = 0.30;

/**
 * Joined pairs needed before a correlation here may be CALLED a predictor
 * (tempdoc 858 §6), modelled on check-agent-quality-trend.mjs:53,70,76,103 —
 * an `insufficient` boolean from the sample size, surfaced prominently, gating
 * the conclusion while every r still prints.
 *
 * Derived from what this instrument concludes, not chosen: it prints
 * "|r| > 0.30 = meaningful predictor", so the honest floor is the N at which
 * |r| = 0.30 first becomes distinguishable from zero at two-tailed α = 0.05 —
 * the smallest N whose critical value r_crit(N) ≤ 0.30. Computed from the exact
 * null density f(r) ∝ (1-r²)^((N-4)/2): N=42 → 0.3044 (matches the standard
 * df=40 table value), N=43 → 0.3008, N=44 → 0.2973. So N = 44. Below it the
 * table still prints, but no signal is named a predictor: an r of 0.35 over 6
 * pairs is inside the noise band of a coin flip, and the legend read against it
 * is what turns that into a finding.
 *
 * A correlation needs a far larger sample than a sum or a median, which is why
 * this floor is 44 where analyze-trends' is 5 and context-attribution's is 6.
 * Declared here rather than in a data file: it is a fixed property of Pearson's
 * null distribution at this instrument's stated threshold, so it does not move
 * as the population grows and must not be recalibrated like a baseline.
 */
const MIN_CORRELATION_PAIRS = 44;

const SIGNALS = [
  'unbounded_read_pct',
  'bash_fileop_pct',
  'rapid_reedit_count',
  'hot_file_concentration',
  'tool_failure_rate',
  'subagent_density',
  'build_cycle_rate',
  'failed_build_pct',
  'reedit_per_edit',
  'subagent_failure_rate',
];

function pearson(xs, ys) {
  const n = xs.length;
  if (n < 2) return 0;
  const mx = xs.reduce((a, b) => a + b, 0) / n;
  const my = ys.reduce((a, b) => a + b, 0) / n;
  const num = xs.reduce((s, x, i) => s + (x - mx) * (ys[i] - my), 0);
  const dx = Math.sqrt(xs.reduce((s, x) => s + (x - mx) ** 2, 0));
  const dy = Math.sqrt(ys.reduce((s, y) => s + (y - my) ** 2, 0));
  return dx === 0 || dy === 0 ? 0 : num / (dx * dy);
}

/**
 * Correlate every signal against `y` over the joined pairs.
 *
 * `correlations` is the measurement and is always populated; `predictors` is
 * the CONCLUSION and requires !insufficient — the counts and r values stay
 * visible either way (check-agent-quality-trend.mjs:103's shape).
 */
function correlate(pairs, ys = pairs.map(p => p.y)) {
  const correlations = SIGNALS.map(name => {
    const xs = pairs.map(p => p.signals?.[name] ?? 0);
    return { name, r: pearson(xs, ys) };
  }).sort((a, b) => Math.abs(b.r) - Math.abs(a.r));

  const insufficient = pairs.length < MIN_CORRELATION_PAIRS;
  return {
    n: pairs.length,
    min_pairs: MIN_CORRELATION_PAIRS,
    insufficient,
    correlations,
    predictors: insufficient
      ? []
      : correlations.filter(c => Math.abs(c.r) >= MEANINGFUL_R).map(c => c.name),
  };
}

/**
 * Join scores against outcomes on session_id, keeping only sessions with both a
 * score and a judged task_completion.
 */
function joinPairs(scoresMap, outcomesMap) {
  const pairs = [];
  for (const [sid, score] of scoresMap) {
    const outcome = outcomesMap.get(sid);
    // tempdoc 622 §6.3: judge fields live under `inference` (outcomes.ndjson is now
    // the fact-authoritative {facts, inference} record). task_completion is residual.
    const completion = outcome?.inference?.task_completion;
    if (completion === null || completion === undefined) continue;
    const y = COMPLETION_WEIGHT[completion];
    if (y === undefined) continue; // unknown enum value
    pairs.push({ sid, signals: score.signals, y });
  }
  return pairs;
}

/**
 * Recompute one outcome record per session id (tempdoc 858 §4.2 item 2).
 *
 * Outcomes are a view now (§3), so `outcomes.ndjson` is opt-in and normally
 * absent — reading it yielded an empty map, `joinPairs` returned nothing, and
 * this instrument hard-exited at the <2 refusal before printing anything. It
 * was structurally dead, not merely starved.
 *
 * `inputs` is REQUIRED and unguarded-absence is a throw, not a fallback:
 * `outcomeForSession` self-loads when given nothing, so an omission here would
 * silently re-parse the whole event store once per session — an N× slowdown
 * that no test would notice. Loading is ~0.5s over the corpus against
 * microseconds for the join itself (see `loadJoinInputs` in outcome-session.mjs),
 * so the hoist has to be enforced rather than documented.
 */
function recomputeOutcomes(sessionIds, inputs) {
  if (!inputs) {
    throw new TypeError(
      'recomputeOutcomes: `inputs` is required — pass loadJoinInputs() hoisted out of the loop; '
      + 'omitting it makes outcomeForSession reload the event store per session.');
  }
  // One generation timestamp for the batch, so the recomputed rows agree with each other.
  const nowMs = Date.now();
  return new Map(sessionIds.map(id => [id, outcomeForSession(id, { inputs, nowMs })]));
}

function printCorrelationTable(title, res) {
  console.log(title);
  console.log('| Signal                  |      r |');
  console.log('|-------------------------|--------|');
  for (const { name, r } of res.correlations) {
    const label = name.padEnd(23);
    const val = r.toFixed(3).padStart(6);
    console.log(`| ${label} | ${val} |`);
  }
  console.log();
}

/** Print the insufficiency notice, or the interpretation legend it replaces. */
function printVerdict(res) {
  if (res.insufficient) {
    console.log(`> ⚠️ Insufficient pairs (${res.n} < ${res.min_pairs}); r values above are`
      + ` reported, but none is called a predictor — at N=${res.n} an |r| of ${MEANINGFUL_R}`
      + ` is not distinguishable from zero at α=0.05.`);
  } else {
    console.log(`Interpretation: |r| > ${MEANINGFUL_R} = meaningful predictor, |r| < 0.10 = weak/noise.`);
    console.log(`Predictors: ${res.predictors.length ? res.predictors.join(', ') : 'none'}.`);
  }
  console.log();
}

function main() {
  const scoresMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE));
  // Consumers recompute rather than read (§3): only the scored sessions need a
  // record, and loadJoinInputs() is hoisted here so the store is parsed once.
  const outcomesMap = recomputeOutcomes([...scoresMap.keys()], loadJoinInputs());

  // Join: only sessions where both score and non-null task_completion exist
  const pairs = joinPairs(scoresMap, outcomesMap);

  // Below 2, Pearson r is undefined and pearson() returns 0 for every signal —
  // a table of zeros is worse than no table, so this stays a hard refusal.
  // MIN_CORRELATION_PAIRS governs the range above it, where r is defined but
  // not yet interpretable.
  if (pairs.length < 2) {
    console.error(`Not enough joined pairs (${pairs.length}) — need at least 2 with scored + judged sessions.`);
    process.exit(1);
  }

  // --- Global correlation ---
  const global = correlate(pairs);
  printCorrelationTable(
    `Signal-outcome correlation (N=${global.n} pairs, complete=1.0 partial=0.5 failed/abandoned=0)\n`,
    global
  );
  printVerdict(global);

  // --- Per-type stratified correlation (tempdoc 285, step 6) ---
  // MIN_TYPE_N is only the floor for printing a group's table at all; a group
  // at or above it but below MIN_CORRELATION_PAIRS gets its numbers shown and
  // its conclusion withheld, same as the global table.
  const MIN_TYPE_N = 5;
  const typeGroups = new Map();
  for (const p of pairs) {
    const type = outcomesMap.get(p.sid)?.inference?.task_type;
    if (!type) continue;
    if (!typeGroups.has(type)) typeGroups.set(type, []);
    typeGroups.get(type).push(p);
  }

  const sortedTypes = [...typeGroups.entries()]
    .filter(([, group]) => group.length >= MIN_TYPE_N)
    .sort((a, b) => b[1].length - a[1].length);

  if (sortedTypes.length > 0) {
    console.log('--- Per-type stratified correlations ---');
    console.log(`(Types with N < ${MIN_TYPE_N} omitted)\n`);
    for (const [type, group] of sortedTypes) {
      const res = correlate(group);
      printCorrelationTable(`${type} (N=${res.n}):\n`, res);
      printVerdict(res);
    }
  }

  // --- Signal vs. cost correlation (tempdoc 285, step 7) ---
  const costsMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, COSTS_FILE));

  const costPairs = [];
  for (const [sid, score] of scoresMap) {
    const cost = costsMap.get(sid);
    if (!cost || cost.total_cost == null) continue;
    costPairs.push({ signals: score.signals, y: cost.total_cost });
  }

  if (costPairs.length >= 2) {
    const res = correlate(costPairs);
    console.log('--- Signal vs. Cost ---\n');
    printCorrelationTable(`Signal-cost correlation (N=${res.n} pairs, y=total_cost USD)\n`, res);
    printVerdict(res);
  }

  // --- Signal vs. duration correlation (tempdoc 285, step 7) ---
  const reportsMap = loadSessionReports();

  const durationPairs = [];
  for (const [sid, score] of scoresMap) {
    const report = reportsMap.get(sid);
    if (!report || !report.duration_seconds) continue;
    const hours = report.duration_seconds / 3600;
    durationPairs.push({ signals: score.signals, y: hours });
  }

  if (durationPairs.length >= 2) {
    const res = correlate(durationPairs);
    console.log('--- Signal vs. Duration ---\n');
    printCorrelationTable(`Signal-duration correlation (N=${res.n} pairs, y=duration hours)\n`, res);
    printVerdict(res);
  }
}

const __filename = fileURLToPath(import.meta.url);
if (process.argv[1] && path.resolve(process.argv[1]) === __filename) main();

export { correlate, joinPairs, recomputeOutcomes, pearson, MIN_CORRELATION_PAIRS, MEANINGFUL_R };
