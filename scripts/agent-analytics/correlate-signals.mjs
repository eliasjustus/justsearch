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
import {
  loadNdjsonMap,
  loadSessionReports,
  TELEMETRY_DIR,
  SCORES_FILE,
  COSTS_FILE,
  OUTCOMES_FILE,
  repoRoot,
} from './lib/telemetry-io.mjs';

const COMPLETION_WEIGHT = { complete: 1.0, partial: 0.5, failed: 0, abandoned: 0 };

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

function printCorrelationTable(title, pairs, ys) {
  const results = SIGNALS.map(name => {
    const xs = pairs.map(p => p.signals?.[name] ?? 0);
    return { name, r: pearson(xs, ys) };
  }).sort((a, b) => Math.abs(b.r) - Math.abs(a.r));

  console.log(title);
  console.log('| Signal                  |      r |');
  console.log('|-------------------------|--------|');
  for (const { name, r } of results) {
    const label = name.padEnd(23);
    const val = r.toFixed(3).padStart(6);
    console.log(`| ${label} | ${val} |`);
  }
  console.log();
}

const scoresMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE));
const outcomesMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE));

// Join: only sessions where both score and non-null task_completion exist
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

if (pairs.length < 2) {
  console.error(`Not enough joined pairs (${pairs.length}) — need at least 2 with scored + judged sessions.`);
  process.exit(1);
}

// --- Global correlation ---
const ys = pairs.map(p => p.y);
printCorrelationTable(
  `Signal-outcome correlation (N=${pairs.length} pairs, complete=1.0 partial=0.5 failed/abandoned=0)\n`,
  pairs, ys
);
console.log('Interpretation: |r| > 0.30 = meaningful predictor, |r| < 0.10 = weak/noise.');
console.log();

// --- Per-type stratified correlation (tempdoc 285, step 6) ---
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
    const typeYs = group.map(p => p.y);
    printCorrelationTable(`${type} (N=${group.length}):\n`, group, typeYs);
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
  const costYs = costPairs.map(p => p.y);
  console.log('--- Signal vs. Cost ---\n');
  printCorrelationTable(
    `Signal-cost correlation (N=${costPairs.length} pairs, y=total_cost USD)\n`,
    costPairs, costYs
  );
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
  const durYs = durationPairs.map(p => p.y);
  console.log('--- Signal vs. Duration ---\n');
  printCorrelationTable(
    `Signal-duration correlation (N=${durationPairs.length} pairs, y=duration hours)\n`,
    durationPairs, durYs
  );
}
