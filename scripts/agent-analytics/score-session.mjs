#!/usr/bin/env node

/**
 * Process Hygiene Index (PHI) — per-session behavioral scoring.
 *
 * Reads session reports, extracts 7 behavioral signals, applies boolean
 * classification rules, and computes a weighted 0–100 process hygiene score.
 * PHI measures tool discipline and process patterns — it does NOT predict
 * task completion or outcome quality (r=0.064 at N=116, see tempdoc 277 C4).
 *
 * Emits wide events (one NDJSON line per session) to scores.ndjson.
 *
 * Usage:
 *   node score-session.mjs --session-id <id>       # Score one session
 *   node score-session.mjs --all                    # Score all sessions
 *   node score-session.mjs --all --json             # JSON array to stdout
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadNdjsonMap, OUTCOMES_FILE } from './lib/telemetry-io.mjs';

const TELEMETRY_DIR = 'tmp/agent-telemetry';
const SESSIONS_DIR = 'sessions';
const SCORES_FILE = 'scores.ndjson';
const MIN_TOOL_CALLS = 10;

// Resolve repo root
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..');

// --- Classification rules (Datadog composite monitor pattern) ---

// Thresholds calibrated from N=60 sessions (Feb 2026).
// Recalibrate after significant population growth using percentile-based boundaries.
const RULES = [
  {
    name: 'WASTEFUL',
    // Recalibrated N=60: unbounded p75=0.125, bash p75=0.172.
    // The AND conjunction of two independent signals keeps the fire rate low (1-4%).
    // C4 finding (tempdoc 277): bash_fileop_pct positively correlates with completion
    // for feature sessions (r=+0.51, d=+1.11) — flag fires on 22% of completed features,
    // 0% of partial. Suppressed for feature type.
    description: 'Elevated unbounded large-file reads + elevated bash file-ops (both signals above p75)',
    test: (s) => s.unbounded_read_pct > 0.10 && s.bash_fileop_pct > 0.30,
    suppressForTypes: ['feature'],
  },
  {
    name: 'THRASHING',
    // C4 finding (tempdoc 277): fires on 33% of completed implementation sessions,
    // 0% of partial — inverted signal. Heavy edit-build cycles are productive for
    // implementation work. Suppressed for implementation type.
    description: 'Many rapid re-edits + concentrated file access',
    test: (s) => s.rapid_reedit_count > 10 && s.hot_file_concentration > 0.20,
    suppressForTypes: ['implementation'],
  },
];

// --- Signal extraction ---

function extractSignals(report) {
  // 1. Unbounded read rate on large files (main + subagent combined)
  // Uses unbounded_large_count (files >12KB) instead of unbounded_count,
  // since unbounded reads on small files have negligible context cost.
  // Falls back to unbounded_count for reports generated before the field existed.
  const mainReads = report.file_reads?.total ?? 0;
  const mainUnbounded = report.file_reads?.unbounded_large_count ?? report.file_reads?.unbounded_count ?? 0;
  const subReads = report.subagent_tool_calls?.file_reads?.total ?? 0;
  const subUnbounded = report.subagent_tool_calls?.file_reads?.unbounded_large_count ?? report.subagent_tool_calls?.file_reads?.unbounded_count ?? 0;
  const totalReads = mainReads + subReads;
  const unbounded_read_pct = totalReads > 0 ? (mainUnbounded + subUnbounded) / totalReads : 0;

  // 2. Bash file-op rate
  const bashTotal = report.bash_commands?.total ?? 0;
  const bashFileOps = report.bash_commands?.file_op_count ?? 0;
  const bash_fileop_pct = bashTotal > 0 ? bashFileOps / bashTotal : 0;

  // 3. Rapid re-edit count (count clusters using same sliding window as trend detector)
  //    Tempdocs (docs/tempdocs/) are excluded — they're investigation logs designed
  //    for frequent updates during a session, not trial-and-error editing.
  let rapid_reedit_count = 0;
  for (const entry of report.file_edits?.by_file ?? []) {
    if (entry.file?.includes('docs/tempdocs/')) continue;
    if ((entry.timestamps?.length ?? 0) < 3) continue;
    const times = entry.timestamps.map(t => new Date(t).getTime()).sort((a, b) => a - b);
    let i = 0;
    while (i < times.length) {
      let j = i + 1;
      while (j < times.length && times[j] - times[i] <= 120_000) j++;
      if (j - i >= 3) {
        rapid_reedit_count++;
        i = j;
      } else {
        i++;
      }
    }
  }

  // 4. Hot file concentration (fraction of total reads going to top 3 files)
  const byFile = [...(report.file_reads?.by_file ?? [])];
  // Merge in subagent reads
  const fileMap = new Map();
  for (const f of byFile) {
    fileMap.set(f.file, (fileMap.get(f.file) ?? 0) + f.count);
  }
  for (const f of report.subagent_tool_calls?.file_reads?.by_file ?? []) {
    fileMap.set(f.file, (fileMap.get(f.file) ?? 0) + f.count);
  }
  const sortedCounts = [...fileMap.values()].sort((a, b) => b - a);
  const top3 = sortedCounts.slice(0, 3).reduce((s, c) => s + c, 0);
  const hot_file_concentration = totalReads > 0 ? top3 / totalReads : 0;

  // 5. Tool failure rate
  const totalCalls = report.tool_calls?.total ?? 0;
  const failures = report.tool_calls?.failure_count ?? 0;
  const tool_failure_rate = totalCalls > 0 ? failures / totalCalls : 0;

  // 6. Subagent density (subagents per hour)
  const durationHours = (report.duration_seconds ?? 0) / 3600;
  const subagentCount = report.subagents?.count ?? 0;
  const subagent_density = durationHours > 0 ? subagentCount / durationHours : 0;

  // 7. Build cycle rate (FAILED builds per edit — constraint-discovery cycling)
  // Uses failed builds only: successful verification builds are not waste.
  // Tempdoc edits excluded from denominator — only code edits drive build cycles.
  // Fall back to total when by_file is empty (e.g. reports without per-file breakdown).
  const byFileEdits = report.file_edits?.by_file ?? [];
  const codeEdits = byFileEdits.length > 0
    ? byFileEdits.filter(e => !e.file?.includes('docs/tempdocs/')).reduce((sum, e) => sum + e.count, 0)
    : (report.file_edits?.total ?? 0);
  const failedBuildCount = report.bash_commands?.failed_build_count ?? 0;
  const build_cycle_rate = failedBuildCount > 0 && codeEdits > 0 ? failedBuildCount / codeEdits : 0;

  // 8. Failed build percentage (failed builds / total builds)
  // Complements build_cycle_rate: this measures build success rate regardless of edit count.
  const buildCount = report.bash_commands?.build_count ?? 0;
  const failed_build_pct = buildCount > 0 ? failedBuildCount / buildCount : 0;

  // 9. Re-edit per edit (rapid re-edit clusters / total code edits)
  // Normalizes rapid_reedit_count by session size — a session with 2 clusters in 10 edits
  // is more concerning than 2 clusters in 200 edits.
  const reedit_per_edit = codeEdits > 0 ? rapid_reedit_count / codeEdits : 0;

  return {
    unbounded_read_pct: round(unbounded_read_pct),
    bash_fileop_pct: round(bash_fileop_pct),
    rapid_reedit_count,
    hot_file_concentration: round(hot_file_concentration),
    tool_failure_rate: round(tool_failure_rate),
    subagent_density: round(subagent_density),
    build_cycle_rate: round(build_cycle_rate),
    failed_build_pct: round(failed_build_pct),
    reedit_per_edit: round(reedit_per_edit),
    // 10. Subagent failure rate (placeholder — step 3 adds upstream data)
    // Falls back to 0 until analyze-session.mjs populates outcomes field.
    subagent_failure_rate: round((() => {
      const subOutcomes = report.subagent_tool_calls?.outcomes;
      const subTotal = (subOutcomes?.success ?? 0) + (subOutcomes?.failure ?? 0) + (subOutcomes?.partial ?? 0);
      return subTotal > 0 ? (subOutcomes?.failure ?? 0) / subTotal : 0;
    })()),
  };
}

// --- Scoring ---

/** Normalize a signal value to 0–1 where 1 = worst. */
function normalize(value, { floor = 0, ceiling }) {
  if (value <= floor) return 0;
  if (value >= ceiling) return 1;
  return (value - floor) / (ceiling - floor);
}

// Signal normalization ceilings — values at or above these are "maximally bad"
// Recalibrated from 60 scored sessions (Feb 2026). See tempdoc 118 §Rescoring.
const SIGNAL_CEILINGS = {
  unbounded_read_pct: 0.30,     // 30% unbounded large-file reads (p95=0.19, max meaningful=0.22)
  bash_fileop_pct: 0.6,         // 60% bash file-ops (was 0.8; p95=0.42, old ceiling ~2x above p95)
  rapid_reedit_count: 40,       // 40 clusters (p95=20, max=42)
  hot_file_concentration: 0.8,  // 80% in top 3 files (p95=0.80, 3 sessions exceed)
  tool_failure_rate: 0.10,      // 10% failure rate (p95=0.04, max=0.14)
  subagent_density: 50,         // 50 subagents/hour (p95=32, max=85)
  build_cycle_rate: 0.25,       // 0.25 failed builds/edit (was 1.0; max observed=0.19, old ceiling 5x above max)
  failed_build_pct: 0.30,       // 30% build failure rate (p95=0.145, max=0.733, ceiling≈2×p95)
  reedit_per_edit: 0.35,        // 35% re-edit ratio (p95=0.174, max=0.205, ceiling≈2×p95)
  subagent_failure_rate: 0.50,  // 50% subagent failure rate (low variance in current data, N=1 non-zero)
};

// Default weights — higher-impact signals get more weight.
// Recalibrated from C4 outcome-aware validation (tempdoc 277, N=116). Sum = 1.0.
// C4 findings: tool_failure_rate is the most consistent predictor across types
// (r=-0.19 to -0.43); subagent_density predicts nothing (all |r|<0.12).
// Rebalanced for 10 signals (tempdoc 285, N=116). Sum = 1.00.
// Existing strong signals keep most weight. New signals get small initial weights (0.07-0.08).
const DEFAULT_WEIGHTS = {
  unbounded_read_pct: 0.16,     // high context cost, but mixed signal across types (C4)
  rapid_reedit_count: 0.16,     // good dynamic range, predictive for refactor (r=-0.37)
  bash_fileop_pct: 0.12,        // mixed signal — anti-pattern globally but positive for feature (C4)
  tool_failure_rate: 0.12,      // most consistent negative predictor across all types (C4)
  subagent_density: 0.08,       // drives compaction cascade but no outcome signal (C4)
  build_cycle_rate: 0.07,       // constraint-discovery cycling
  hot_file_concentration: 0.07, // type-dependent: helps investigation, hurts feature (C4)
  failed_build_pct: 0.07,       // build success rate independent of edit count (tempdoc 285)
  reedit_per_edit: 0.07,        // normalized re-edit intensity (tempdoc 285)
  subagent_failure_rate: 0.08,  // subagent outcome quality (tempdoc 285, placeholder until step 3)
};

function computeScore(signals, weights = null, ceilings = null) {
  const signalNames = Object.keys(SIGNAL_CEILINGS);

  let weightedSum = 0;
  for (const name of signalNames) {
    const w = weights?.[name] ?? DEFAULT_WEIGHTS[name];
    const norm = normalize(signals[name], { ceiling: ceilings?.[name] ?? SIGNAL_CEILINGS[name] });
    weightedSum += w * norm;
  }

  // Score: 100 = perfect (no waste), 0 = maximally wasteful
  // Clamp to [0, 100] — custom weights that don't sum to 1 can overshoot
  return Math.max(0, Math.min(100, Math.round(100 * (1 - weightedSum))));
}

// --- Per-type ceiling computation (hierarchical partial pooling) ---

/** Sessions per type needed for fully type-specific ceilings. Below this, shrink toward global. */
const N_STABLE = 20;

/**
 * Compute per-type signal ceilings using hierarchical partial pooling.
 *
 * For each task type, computes p75 of each signal across sessions of that type,
 * then shrinks toward the global ceiling proportionally to sample size:
 *   type_ceiling = global + (observed_p75 - global) * min(1, n_type / N_STABLE)
 *
 * @param {Array<{signals: Object, taskType: string|null}>} entries
 * @returns {Map<string, Object>} Map from task_type to ceilings object
 */
function computeTypeCeilings(entries) {
  const byType = new Map();
  for (const { signals, taskType } of entries) {
    if (!taskType) continue;
    if (!byType.has(taskType)) byType.set(taskType, []);
    byType.get(taskType).push(signals);
  }

  const signalNames = Object.keys(SIGNAL_CEILINGS);
  const result = new Map();

  for (const [type, signalsList] of byType) {
    const nType = signalsList.length;
    const shrinkage = Math.min(1, nType / N_STABLE);
    const typeCeilings = {};
    for (const name of signalNames) {
      const values = signalsList.map(s => s[name] ?? 0).sort((a, b) => a - b);
      const observedP75 = percentile(values, 75);
      const global = SIGNAL_CEILINGS[name];
      typeCeilings[name] = global + (observedP75 - global) * shrinkage;
    }
    result.set(type, typeCeilings);
  }

  return result;
}

function classifySession(signals, taskType = null) {
  return RULES
    .filter(rule => {
      if (!rule.test(signals)) return false;
      if (taskType && rule.suppressForTypes?.includes(taskType)) return false;
      return true;
    })
    .map(rule => rule.name);
}

function scoreReport(report, weights = null, taskType = null) {
  const signals = extractSignals(report);
  const score = computeScore(signals, weights);
  const flags = classifySession(signals, taskType);
  const durationHours = (report.duration_seconds ?? 0) / 3600;

  return {
    ts: new Date().toISOString(),
    session_id: report.session_id,
    score,
    signals,
    flags,
    duration_hours: round(durationHours),
    tool_calls: report.tool_calls?.total ?? 0,
  };
}

// --- I/O ---

function loadReport(sessionId) {
  const filePath = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR, `${sessionId}.json`);
  if (!fs.existsSync(filePath)) {
    console.error(`Session report not found: ${filePath}`);
    console.error(`Run analyze-session.mjs first to generate it.`);
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function loadAllReports() {
  const dir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
  if (!fs.existsSync(dir)) {
    console.error(`No sessions directory: ${dir}`);
    process.exit(1);
  }

  const reports = [];
  for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.json'))) {
    try {
      const report = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8'));
      if (report.schema !== 'agent-session-report.v1') continue;
      if ((report.tool_calls?.total ?? 0) < MIN_TOOL_CALLS) continue;
      reports.push(report);
    } catch {
      // Skip unparseable
    }
  }
  return reports;
}

function writeScores(scoreRecords) {
  const scoresPath = path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE);
  fs.mkdirSync(path.dirname(scoresPath), { recursive: true });
  const content = scoreRecords.map(r => JSON.stringify(r)).join('\n') + '\n';
  fs.writeFileSync(scoresPath, content, 'utf8');
}

function upsertScore(scoreRecord) {
  const scoresPath = path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE);
  fs.mkdirSync(path.dirname(scoresPath), { recursive: true });

  // Read existing, replace matching session_id, append if new
  let existing = [];
  try {
    existing = fs.readFileSync(scoresPath, 'utf8')
      .split('\n')
      .filter(line => line.trim())
      .map(line => JSON.parse(line));
  } catch {
    // No existing file or parse error — start fresh
  }

  const idx = existing.findIndex(r => r.session_id === scoreRecord.session_id);
  if (idx !== -1) {
    existing[idx] = scoreRecord;
  } else {
    existing.push(scoreRecord);
  }
  writeScores(existing);
}

function formatHuman(scoreRecord) {
  const lines = [];
  const line = (s = '') => lines.push(s);

  line(`Session: ${scoreRecord.session_id.substring(0, 8)} (${scoreRecord.duration_hours}h, ${scoreRecord.tool_calls} calls)`);
  line(`PHI:     ${scoreRecord.score}/100`);
  if (scoreRecord.task_type) line(`Type:    ${scoreRecord.task_type}`);
  line();

  line(`Signals:`);
  const s = scoreRecord.signals;
  line(`  Unbounded reads:     ${pct(s.unbounded_read_pct)}`);
  line(`  Bash file-ops:       ${pct(s.bash_fileop_pct)}`);
  line(`  Rapid re-edits:      ${s.rapid_reedit_count} clusters`);
  line(`  Hot file focus:      ${pct(s.hot_file_concentration)}`);
  line(`  Tool failure rate:   ${pct(s.tool_failure_rate)}`);
  line(`  Subagent density:    ${s.subagent_density}/hr`);
  line();

  if (scoreRecord.flags.length > 0) {
    line(`Flags: ${scoreRecord.flags.join(', ')}`);
  } else {
    line(`Flags: none`);
  }

  if (scoreRecord.anomalies?.length > 0) {
    line();
    line(`Anomalies:`);
    for (const a of scoreRecord.anomalies) {
      line(`  ${a.signal}: ${a.value} (threshold: ${a.threshold}, Z=${a.deviation.toFixed(1)})`);
    }
  }

  return lines.join('\n');
}

function round(n) {
  return Math.round(n * 1000) / 1000;
}

function pct(n) {
  return `${(n * 100).toFixed(1)}%`;
}

// --- Anomaly detection (MAD-based modified Z-score) ---
// Replaces IQR-based detection (tempdoc 285, step 5).
// MAD has 50% breakdown point vs 25% for IQR — more robust to outliers.
// Modified Z-score: Z = 0.6745 * (x - median) / MAD, flagged at |Z| > 3.

/** Compute a percentile from a sorted array using linear interpolation. */
function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = (p / 100) * (sorted.length - 1);
  const lo = Math.floor(idx);
  const hi = Math.ceil(idx);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
}

/**
 * Detect anomalous sessions using MAD-based modified Z-scores.
 * Mutates scoreRecords in-place: adds `anomalies` array to each record.
 * Requires N >= 4 for meaningful MAD computation.
 */
function detectAnomalies(scoreRecords) {
  const MIN_SESSIONS = 4;
  if (scoreRecords.length < MIN_SESSIONS) {
    for (const r of scoreRecords) r.anomalies = [];
    return;
  }

  const signalNames = Object.keys(SIGNAL_CEILINGS);
  const stats = {};

  for (const name of signalNames) {
    const values = scoreRecords.map(r => r.signals[name]).sort((a, b) => a - b);
    const median = percentile(values, 50);
    const deviations = values.map(v => Math.abs(v - median)).sort((a, b) => a - b);
    const mad = percentile(deviations, 50);
    stats[name] = { median, mad };
  }

  for (const r of scoreRecords) {
    r.anomalies = [];
    for (const name of signalNames) {
      const { median, mad } = stats[name];
      if (mad === 0) continue; // all values identical, skip
      const value = r.signals[name];
      const z = 0.6745 * (value - median) / mad;
      if (Math.abs(z) > 3) {
        r.anomalies.push({
          signal: name,
          value: round(value),
          threshold: round(median + 3 * mad / 0.6745),
          deviation: round(z),
        });
      }
    }
  }
}

// --- Main ---

function main() {
  const args = process.argv.slice(2);
  const jsonOnly = args.includes('--json');
  const all = args.includes('--all');
  const sessionIdx = args.indexOf('--session-id');
  const sessionId = sessionIdx !== -1 ? args[sessionIdx + 1] : null;

  // Parse optional --weights JSON
  const weightsIdx = args.indexOf('--weights');
  let weights = null;
  if (weightsIdx !== -1 && args[weightsIdx + 1]) {
    try {
      weights = JSON.parse(args[weightsIdx + 1]);
    } catch {
      console.error('Invalid --weights JSON');
      process.exit(1);
    }
  }

  if (!all && !sessionId) {
    console.error('Usage: node score-session.mjs --session-id <id> | --all [--json] [--weights <json>]');
    process.exit(1);
  }

  if (sessionId) {
    const report = loadReport(sessionId);
    const outcomesMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE));
    const outcome = outcomesMap.get(sessionId);
    const taskType = outcome?.task_type ?? null;
    const result = scoreReport(report, weights, taskType);
    if (taskType) result.task_type = taskType;
    upsertScore(result);

    if (jsonOnly) {
      process.stdout.write(JSON.stringify(result, null, 2) + '\n');
    } else {
      console.log(formatHuman(result));
      console.log(`\nScore appended to ${path.join(TELEMETRY_DIR, SCORES_FILE)}`);
    }
    return;
  }

  // --all mode
  const reports = loadAllReports();
  if (reports.length === 0) {
    console.error('No substantive sessions to score.');
    process.exit(1);
  }

  // Load outcomes for task_type lookup and per-type ceiling computation
  const outcomesMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE));

  // Pass 1: Extract signals and resolve task types
  const entries = [];
  for (const report of reports) {
    const signals = extractSignals(report);
    const outcome = outcomesMap.get(report.session_id);
    const taskType = outcome?.task_type ?? null;
    entries.push({ report, signals, taskType });
  }

  // Compute per-type ceilings via hierarchical partial pooling
  const typeCeilingsMap = computeTypeCeilings(entries);

  // Pass 2: Score each session with type-specific ceilings
  const results = [];
  for (const { report, signals, taskType } of entries) {
    const ceilings = taskType ? (typeCeilingsMap.get(taskType) ?? null) : null;
    const score = computeScore(signals, weights, ceilings);
    const flags = classifySession(signals, taskType);
    const durationHours = (report.duration_seconds ?? 0) / 3600;
    const result = {
      ts: new Date().toISOString(),
      session_id: report.session_id,
      score,
      signals,
      flags,
      duration_hours: round(durationHours),
      tool_calls: report.tool_calls?.total ?? 0,
    };
    if (taskType) result.task_type = taskType;
    results.push(result);
  }

  // Anomaly detection (MAD-based modified Z-score) — runs across all scored sessions
  detectAnomalies(results);

  writeScores(results);

  // Sort by score ascending (worst first)
  results.sort((a, b) => a.score - b.score);

  // Compute per-type means for context
  const typeMeans = new Map();
  for (const r of results) {
    if (!r.task_type) continue;
    if (!typeMeans.has(r.task_type)) typeMeans.set(r.task_type, { sum: 0, n: 0 });
    const t = typeMeans.get(r.task_type);
    t.sum += r.score;
    t.n++;
  }

  if (jsonOnly) {
    process.stdout.write(JSON.stringify(results, null, 2) + '\n');
  } else {
    // Per-type summary header
    console.log(`PHI scored ${results.length} sessions (Process Hygiene Index — behavioral, not predictive):\n`);
    if (typeMeans.size > 0) {
      const sorted = [...typeMeans.entries()]
        .map(([type, { sum, n }]) => ({ type, mean: sum / n, n }))
        .sort((a, b) => a.mean - b.mean);
      console.log('  Type baselines:');
      for (const { type, mean, n } of sorted) {
        console.log(`    ${type.padEnd(16)} ${mean.toFixed(0)}/100  (N=${n})`);
      }
      console.log('');
    }

    for (const r of results) {
      const type = r.task_type ? ` [${r.task_type}]` : '';
      const flags = r.flags.length > 0 ? ` [${r.flags.join(', ')}]` : '';
      const anomalyCount = r.anomalies?.length ?? 0;
      const anomalyTag = anomalyCount > 0 ? ` {${anomalyCount} anomalies}` : '';
      // Show type-relative context if available
      let typeCtx = '';
      if (r.task_type && typeMeans.has(r.task_type)) {
        const mean = typeMeans.get(r.task_type).sum / typeMeans.get(r.task_type).n;
        const delta = r.score - mean;
        typeCtx = delta >= 0 ? ` (+${delta.toFixed(0)} vs type)` : ` (${delta.toFixed(0)} vs type)`;
      }
      console.log(`  ${r.session_id.substring(0, 8)}  ${r.score}/100  (${r.duration_hours}h)${type}${typeCtx}${flags}${anomalyTag}`);
    }
    console.log(`\nScores appended to ${path.join(TELEMETRY_DIR, SCORES_FILE)}`);
  }
}

const __filename = fileURLToPath(import.meta.url);
if (process.argv[1] === __filename) main();

export { computeScore, extractSignals, computeTypeCeilings, SIGNAL_CEILINGS, N_STABLE, percentile };
