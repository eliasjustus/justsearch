#!/usr/bin/env node

/**
 * Generate a self-contained HTML dashboard for agent analytics.
 *
 * Reads scores.ndjson, costs.ndjson, and session reports. Produces a single
 * HTML file with embedded Chart.js for score trend visualization, a signal
 * heatmap, and a sortable session table.
 *
 * Usage:
 *   node generate-dashboard.mjs            # Generate dashboard.html
 */

import fs from 'node:fs';
import path from 'node:path';
import {
  TELEMETRY_DIR, SCORES_FILE, COSTS_FILE, OUTCOMES_FILE,
  repoRoot, loadNdjsonArray, loadSessionReports, round,
} from './lib/telemetry-io.mjs';

const OUTPUT_FILE = 'dashboard.html';

function buildDashboardData() {
  const scores = loadNdjsonArray(path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE));
  const costs = loadNdjsonArray(path.join(repoRoot, TELEMETRY_DIR, COSTS_FILE));
  const reports = loadSessionReports();

  const costMap = new Map();
  for (const c of costs) if (c.session_id) costMap.set(c.session_id, c);

  const outcomes = loadNdjsonArray(path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE));
  const outcomeMap = new Map();
  // tempdoc 622 §6.3: outcomes.ndjson is now {facts, inference}; the judge verdict
  // is a residual string under inference.task_completion (not an object with .verdict).
  for (const o of outcomes) if (o.session_id && o.inference?.task_completion) outcomeMap.set(o.session_id, o);

  // Only include sessions with scores (substantive sessions)
  const sessions = [];
  for (const score of scores) {
    const report = reports.get(score.session_id);
    const cost = costMap.get(score.session_id);

    sessions.push({
      id: score.session_id,
      id_short: score.session_id.substring(0, 8),
      date: report?.started_at ?? score.ts,
      duration_hours: score.duration_hours,
      score: score.score,
      flags: score.flags,
      anomalies: score.anomalies ?? [],
      signals: score.signals,
      tool_calls: score.tool_calls,
      cost_usd: cost?.total_cost_usd ?? null,
      turns: cost?.turns ?? null,
      verdict: outcomeMap.get(score.session_id)?.inference?.task_completion ?? null,
      task_type: outcomeMap.get(score.session_id)?.inference?.task_type ?? null,
      model: report?.model ?? cost?.model ?? null,
      compaction_rereads: report?.compaction_rereads?.total_rereads ?? null,
      failure_cascades: report?.failure_cascades?.count ?? null,
      context_efficiency: report?.context_efficiency?.score_informational ?? null,
    });
  }

  // Sort by date ascending for trend chart
  sessions.sort((a, b) => (a.date ?? '').localeCompare(b.date ?? ''));

  // Summary stats
  const avgScore = sessions.length > 0
    ? round(sessions.reduce((s, x) => s + x.score, 0) / sessions.length, 1)
    : 0;
  const totalCost = sessions.reduce((s, x) => s + (x.cost_usd ?? 0), 0);
  const avgDuration = sessions.length > 0
    ? round(sessions.reduce((s, x) => s + x.duration_hours, 0) / sessions.length, 1)
    : 0;
  const flaggedCount = sessions.filter(s => s.flags && s.flags.length > 0).length;

  return {
    sessions,
    summary: {
      count: sessions.length,
      avg_score: avgScore,
      total_cost: round(totalCost, 2),
      avg_duration: avgDuration,
      flagged_count: flaggedCount,
    },
    signal_names: [
      'unbounded_read_pct', 'rapid_reedit_count', 'bash_fileop_pct',
      'subagent_density', 'build_cycle_rate', 'hot_file_concentration', 'tool_failure_rate',
      'failed_build_pct', 'reedit_per_edit', 'subagent_failure_rate',
    ],
    signal_ceilings: {
      unbounded_read_pct: 0.30,
      bash_fileop_pct: 0.6,
      rapid_reedit_count: 40,
      hot_file_concentration: 0.8,
      tool_failure_rate: 0.10,
      subagent_density: 50,
      build_cycle_rate: 0.25,
      failed_build_pct: 0.30,
      reedit_per_edit: 0.35,
      subagent_failure_rate: 0.50,
    },
  };
}

function generateHTML(data) {
  const dataJson = JSON.stringify(data);

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Agent Analytics Dashboard</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<style>
  :root {
    --bg: #1a1a2e; --bg2: #16213e; --bg3: #0f3460;
    --text: #e0e0e0; --text2: #a0a0b0; --accent: #e94560;
    --green: #4ade80; --yellow: #fbbf24; --red: #ef4444;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: 'Segoe UI', system-ui, sans-serif; background: var(--bg); color: var(--text); padding: 24px; }
  h1 { color: var(--text); margin-bottom: 8px; font-size: 1.5rem; }
  .subtitle { color: var(--text2); margin-bottom: 24px; font-size: 0.85rem; }
  .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin-bottom: 24px; }
  .card { background: var(--bg2); border-radius: 8px; padding: 16px; }
  .card-label { color: var(--text2); font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.05em; }
  .card-value { font-size: 1.8rem; font-weight: 700; margin-top: 4px; }
  .chart-container { background: var(--bg2); border-radius: 8px; padding: 16px; margin-bottom: 24px; }
  .chart-title { color: var(--text2); font-size: 0.85rem; margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.05em; }
  canvas { max-height: 300px; }

  /* Heatmap */
  .heatmap { width: 100%; border-collapse: collapse; margin-bottom: 24px; font-size: 0.8rem; }
  .heatmap th { background: var(--bg2); color: var(--text2); padding: 6px 8px; text-align: center; font-weight: 500;
    position: sticky; top: 0; }
  .heatmap td { padding: 4px 6px; text-align: center; border: 1px solid var(--bg); min-width: 50px; }
  .heatmap td.session-id { text-align: left; font-family: monospace; color: var(--text2); white-space: nowrap; }

  /* Session table */
  .session-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
  .session-table th { background: var(--bg3); color: var(--text2); padding: 8px; text-align: left; cursor: pointer;
    user-select: none; position: sticky; top: 0; }
  .session-table th:hover { color: var(--text); }
  .session-table td { padding: 6px 8px; border-bottom: 1px solid var(--bg2); }
  .session-table tr:hover td { background: var(--bg2); }
  .flag { display: inline-block; background: var(--accent); color: white; font-size: 0.7rem;
    padding: 1px 6px; border-radius: 3px; margin: 1px; }
  .anomaly-badge { display: inline-block; background: var(--yellow); color: #000; font-size: 0.7rem;
    padding: 1px 6px; border-radius: 3px; }
  .score-bar { display: inline-block; height: 8px; border-radius: 4px; min-width: 4px; }
  .sort-arrow { font-size: 0.6rem; margin-left: 4px; }
</style>
</head>
<body>
<h1>Agent Analytics Dashboard</h1>
<p class="subtitle">Generated ${new Date().toISOString().split('T')[0]} &mdash; Behavioral tracking pipeline (tempdoc 118)</p>

<div class="cards">
  <div class="card">
    <div class="card-label">Sessions</div>
    <div class="card-value" id="stat-count"></div>
  </div>
  <div class="card">
    <div class="card-label">Flagged Sessions</div>
    <div class="card-value" id="stat-flagged"></div>
  </div>
  <div class="card">
    <div class="card-label">Total Cost</div>
    <div class="card-value" id="stat-cost"></div>
  </div>
  <div class="card">
    <div class="card-label">Avg Duration</div>
    <div class="card-value" id="stat-duration"></div>
  </div>
</div>

<div class="chart-container">
  <div class="chart-title">Flag Trend (WASTEFUL / THRASHING)</div>
  <canvas id="flagTrend"></canvas>
</div>

<div class="chart-container" style="overflow-x: auto;">
  <div class="chart-title">Signal Heatmap (normalized: green=good, red=bad)</div>
  <table class="heatmap" id="heatmapTable"></table>
</div>

<div class="chart-container">
  <div class="chart-title">Process Hygiene Index (informational &mdash; not predictive of outcomes)</div>
  <canvas id="scoreTrend"></canvas>
</div>

<div class="chart-container" style="overflow-x: auto;">
  <div class="chart-title">Session Details</div>
  <table class="session-table" id="sessionTable"></table>
</div>

<script>
const DATA = ${dataJson};

// Summary cards
document.getElementById('stat-count').textContent = DATA.summary.count;
document.getElementById('stat-flagged').textContent = DATA.summary.flagged_count + '/' + DATA.summary.count;
document.getElementById('stat-cost').textContent = '$' + DATA.summary.total_cost.toFixed(2);
document.getElementById('stat-duration').textContent = DATA.summary.avg_duration + 'h';

// Flag trend chart — primary visualization
const FLAG_NAMES = ['WASTEFUL', 'THRASHING'];
const FLAG_COLORS = { WASTEFUL: '#ef4444', THRASHING: '#fbbf24' };

const trendLabels = DATA.sessions.map(s => {
  const d = new Date(s.date);
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
});
const trendScores = DATA.sessions.map(s => s.score);

if (typeof Chart !== 'undefined') {
  // Flag trend chart (primary)
  const flagCtx = document.getElementById('flagTrend').getContext('2d');
  new Chart(flagCtx, {
    type: 'bar',
    data: {
      labels: trendLabels,
      datasets: FLAG_NAMES.map(flag => ({
        label: flag,
        data: DATA.sessions.map(s => (s.flags || []).includes(flag) ? 1 : 0),
        backgroundColor: FLAG_COLORS[flag],
        borderWidth: 0,
        barPercentage: 0.9,
        categoryPercentage: 0.9,
      })),
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { labels: { color: '#a0a0b0' } },
        tooltip: {
          callbacks: {
            title: (items) => {
              const idx = items[0].dataIndex;
              return DATA.sessions[idx].id_short + ' — ' + trendLabels[idx];
            },
            label: (item) => item.raw ? item.dataset.label : '',
            filter: (item) => item.raw > 0,
          }
        }
      },
      scales: {
        y: { display: false, stacked: true },
        x: { stacked: true, grid: { display: false }, ticks: { color: '#a0a0b0', maxRotation: 45 } },
      }
    }
  });

  // Score trend chart (secondary — informational)
  const trendCtx = document.getElementById('scoreTrend').getContext('2d');
  new Chart(trendCtx, {
    type: 'line',
    data: {
      labels: trendLabels,
      datasets: [{
        label: 'Process Hygiene Index',
        data: trendScores,
        borderColor: '#6b7280',
        backgroundColor: 'rgba(107, 114, 128, 0.1)',
        fill: true,
        tension: 0.3,
        pointRadius: 3,
        pointBackgroundColor: '#6b7280',
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            title: (items) => {
              const idx = items[0].dataIndex;
              return DATA.sessions[idx].id_short + ' — ' + trendLabels[idx];
            },
            label: (item) => 'Index: ' + item.raw + '/100',
          }
        }
      },
      scales: {
        y: { min: 0, max: 100, grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#a0a0b0' } },
        x: { grid: { display: false }, ticks: { color: '#a0a0b0', maxRotation: 45 } },
      }
    }
  });
} else {
  for (const id of ['flagTrend', 'scoreTrend']) {
    const canvas = document.getElementById(id);
    canvas.style.display = 'none';
    canvas.parentElement.insertAdjacentHTML('beforeend',
      '<p style="color:var(--text2);padding:16px 0">Chart unavailable (Chart.js CDN unreachable).</p>');
  }
}

// Signal heatmap
function normalizeSignal(name, value) {
  const ceiling = DATA.signal_ceilings[name] || 1;
  return Math.min(1, Math.max(0, value / ceiling));
}

function heatColor(norm) {
  // Green (0) → Yellow (0.5) → Red (1)
  if (norm <= 0.5) {
    const r = Math.round(74 + (251 - 74) * (norm * 2));
    const g = Math.round(222 + (191 - 222) * (norm * 2));
    const b = Math.round(128 + (36 - 128) * (norm * 2));
    return 'rgb(' + r + ',' + g + ',' + b + ')';
  }
  const t = (norm - 0.5) * 2;
  const r = Math.round(251 + (239 - 251) * t);
  const g = Math.round(191 + (68 - 191) * t);
  const b = Math.round(36 + (68 - 36) * t);
  return 'rgb(' + r + ',' + g + ',' + b + ')';
}

const SIGNAL_LABELS = {
  unbounded_read_pct: 'Unbounded',
  rapid_reedit_count: 'Re-edits',
  bash_fileop_pct: 'Bash Ops',
  subagent_density: 'Sub/hr',
  build_cycle_rate: 'Build Cycle',
  hot_file_concentration: 'Hot Files',
  tool_failure_rate: 'Failures',
};

const heatmapTable = document.getElementById('heatmapTable');
let heatHtml = '<thead><tr><th>Session</th><th>Score</th>';
for (const name of DATA.signal_names) {
  heatHtml += '<th>' + (SIGNAL_LABELS[name] || name) + '</th>';
}
heatHtml += '</tr></thead><tbody>';

// Show most recent first
const reversedSessions = [...DATA.sessions].reverse();
for (const s of reversedSessions) {
  heatHtml += '<tr><td class="session-id">' + s.id_short + '</td>';
  heatHtml += '<td>' + s.score + '</td>';
  for (const name of DATA.signal_names) {
    const val = s.signals[name] || 0;
    const norm = normalizeSignal(name, val);
    const bg = heatColor(norm);
    const textColor = norm > 0.6 ? '#fff' : '#000';
    let display = typeof val === 'number' && val < 1 ? (val * 100).toFixed(0) + '%' : val;
    heatHtml += '<td style="background:' + bg + ';color:' + textColor + '">' + display + '</td>';
  }
  heatHtml += '</tr>';
}
heatHtml += '</tbody>';
heatmapTable.innerHTML = heatHtml;

// Session details table
const TABLE_COLS = [
  { key: 'date', label: 'Date', fmt: v => v ? new Date(v).toLocaleDateString() : '' },
  { key: 'id_short', label: 'Session', fmt: v => '<code>' + v + '</code>' },
  { key: 'flags', label: 'Flags', fmt: (v, s) => {
    let html = (v || []).map(f => '<span class="flag">' + f + '</span>').join(' ');
    if (s.anomalies?.length > 0) html += ' <span class="anomaly-badge">' + s.anomalies.length + ' anomaly</span>';
    return html;
  }},
  { key: 'verdict', label: 'Verdict', fmt: v => v || '' },
  { key: 'task_type', label: 'Type', fmt: v => v || '' },
  { key: 'duration_hours', label: 'Hours', fmt: v => v != null ? v.toFixed(1) : '' },
  { key: 'cost_usd', label: 'Cost', fmt: v => v != null ? '$' + v.toFixed(2) : '' },
  { key: 'tool_calls', label: 'Tools', fmt: v => v || '' },
  { key: 'score', label: 'Hygiene', fmt: (v, s) => {
    const color = '#6b7280';
    const width = Math.max(4, Math.round(v * 0.6));
    return '<span class="score-bar" style="width:' + width + 'px;background:' + color + '"></span> ' + v;
  }},
  { key: 'context_efficiency', label: 'Ctx Eff', fmt: v => v != null ? (v * 100).toFixed(0) + '%' : '' },
  { key: 'compaction_rereads', label: 'Cmpct Rereads', fmt: v => v != null ? v : '' },
  { key: 'failure_cascades', label: 'Cascades', fmt: v => v != null ? v : '' },
];

let sortCol = 'date';
let sortAsc = false;

function renderTable() {
  const sorted = [...DATA.sessions].sort((a, b) => {
    let va = a[sortCol], vb = b[sortCol];
    if (va == null) va = sortAsc ? Infinity : -Infinity;
    if (vb == null) vb = sortAsc ? Infinity : -Infinity;
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });

  let html = '<thead><tr>';
  for (const col of TABLE_COLS) {
    const arrow = sortCol === col.key ? (sortAsc ? ' \\u25B2' : ' \\u25BC') : '';
    html += '<th data-col="' + col.key + '">' + col.label + '<span class="sort-arrow">' + arrow + '</span></th>';
  }
  html += '</tr></thead><tbody>';
  for (const s of sorted) {
    html += '<tr>';
    for (const col of TABLE_COLS) {
      html += '<td>' + col.fmt(s[col.key], s) + '</td>';
    }
    html += '</tr>';
  }
  html += '</tbody>';
  document.getElementById('sessionTable').innerHTML = html;

  // Re-attach click handlers
  for (const th of document.querySelectorAll('#sessionTable th')) {
    th.addEventListener('click', () => {
      const col = th.dataset.col;
      if (sortCol === col) { sortAsc = !sortAsc; }
      else { sortCol = col; sortAsc = false; }
      renderTable();
    });
  }
}

renderTable();
</script>
</body>
</html>`;
}

function main() {
  const data = buildDashboardData();

  if (data.sessions.length === 0) {
    console.error('No scored sessions found. Run score-session.mjs --all first.');
    process.exit(1);
  }

  const html = generateHTML(data);
  const outputPath = path.join(repoRoot, TELEMETRY_DIR, OUTPUT_FILE);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, html, 'utf8');

  console.log(`Dashboard generated: ${path.join(TELEMETRY_DIR, OUTPUT_FILE)}`);
  console.log(`Sessions: ${data.summary.count}, Avg score: ${data.summary.avg_score}/100, Total cost: $${data.summary.total_cost}`);
}

main();
