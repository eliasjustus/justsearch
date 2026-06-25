#!/usr/bin/env node

/**
 * Cross-session trend analyzer for agent telemetry.
 *
 * Reads session reports from tmp/agent-telemetry/sessions/ and computes
 * aggregate patterns across sessions. Outputs JSON + Markdown reports.
 *
 * Usage:
 *   node analyze-trends.mjs                        # Generate trend report
 *   node analyze-trends.mjs --json                 # Print JSON to stdout (no file write)
 *   node analyze-trends.mjs --cutoff <ISO-date>    # Before/after comparison
 */

import fs from 'node:fs';
import path from 'node:path';

const TELEMETRY_DIR = 'tmp/agent-telemetry';
const SESSIONS_DIR = 'sessions';
const REPORTS_DIR = 'reports';
const MIN_TOOL_CALLS = 10; // Skip ephemeral sessions

// Resolve repo root
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..');

function loadSessionReports() {
  const dir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
  if (!fs.existsSync(dir)) {
    console.error(`No sessions directory: ${dir}`);
    process.exit(1);
  }

  const files = fs.readdirSync(dir).filter(f => f.endsWith('.json'));
  const reports = [];
  const skipped = [];

  for (const file of files) {
    try {
      const content = fs.readFileSync(path.join(dir, file), 'utf8');
      const report = JSON.parse(content);
      if (report.schema !== 'agent-session-report.v1') continue;
      if ((report.tool_calls?.total ?? 0) < MIN_TOOL_CALLS) {
        skipped.push(report);
        continue;
      }
      reports.push(report);
    } catch {
      // Skip unparseable files
    }
  }

  return { reports, skipped };
}

// --- Detector 1: Top re-read files ---

function detectHotFiles(reports) {
  const fileMap = new Map(); // file -> { main_reads, subagent_reads, total_unbounded, sessions }

  for (const report of reports) {
    const seen = new Set();

    // Main session reads
    for (const entry of report.file_reads?.by_file ?? []) {
      const file = sanitizePath(entry.file);
      if (!fileMap.has(file)) {
        fileMap.set(file, { main_reads: 0, subagent_reads: 0, total_unbounded: 0, sessions: 0 });
      }
      const agg = fileMap.get(file);
      agg.main_reads += entry.count;
      agg.total_unbounded += entry.unbounded;
      seen.add(file);
    }

    // Subagent reads (from transcript parsing)
    for (const entry of report.subagent_tool_calls?.file_reads?.by_file ?? []) {
      const file = sanitizePath(entry.file);
      if (!fileMap.has(file)) {
        fileMap.set(file, { main_reads: 0, subagent_reads: 0, total_unbounded: 0, sessions: 0 });
      }
      const agg = fileMap.get(file);
      agg.subagent_reads += entry.count;
      agg.total_unbounded += entry.unbounded;
      seen.add(file);
    }

    // Count sessions per file (once per file per report)
    for (const file of seen) {
      fileMap.get(file).sessions++;
    }
  }

  return [...fileMap.entries()]
    .map(([file, data]) => ({
      file,
      total_reads: data.main_reads + data.subagent_reads,
      main_reads: data.main_reads,
      subagent_reads: data.subagent_reads,
      sessions: data.sessions,
      unbounded: data.total_unbounded,
    }))
    .filter(e => e.total_reads >= 10)
    .sort((a, b) => b.total_reads - a.total_reads)
    .slice(0, 20);
}

/** Strip absolute paths to repo-relative or ~/relative for paths outside repo. */
function sanitizePath(filePath) {
  if (!filePath) return filePath;
  // Already relative
  if (!path.isAbsolute(filePath)) return filePath;
  // Inside repo — strip to relative
  const normalized = filePath.replace(/\\/g, '/');
  const repoNorm = repoRoot.replace(/\\/g, '/');
  if (normalized.startsWith(repoNorm + '/')) {
    return normalized.slice(repoNorm.length + 1);
  }
  // Outside repo — use ~/ to avoid leaking username
  const home = (process.env.USERPROFILE ?? process.env.HOME ?? '').replace(/\\/g, '/');
  if (home && normalized.startsWith(home + '/')) {
    return '~/' + normalized.slice(home.length + 1);
  }
  return filePath;
}

// --- Detector 2: Unbounded read rate ---

function detectUnboundedRate(reports) {
  const mainRates = [];
  const combinedRates = [];

  for (const report of reports) {
    const mainTotal = report.file_reads?.total ?? 0;
    const mainUnbounded = report.file_reads?.unbounded_large_count ?? report.file_reads?.unbounded_count ?? 0;
    const subTotal = report.subagent_tool_calls?.file_reads?.total ?? 0;
    const subUnbounded = report.subagent_tool_calls?.file_reads?.unbounded_large_count ?? report.subagent_tool_calls?.file_reads?.unbounded_count ?? 0;

    if (mainTotal > 0) {
      mainRates.push(mainUnbounded / mainTotal);
    }
    const allTotal = mainTotal + subTotal;
    if (allTotal > 0) {
      combinedRates.push((mainUnbounded + subUnbounded) / allTotal);
    }
  }

  function stats(rates) {
    if (rates.length === 0) return { mean: 0, min: 0, max: 0, samples: 0 };
    const mean = rates.reduce((s, r) => s + r, 0) / rates.length;
    return {
      mean: round(mean),
      min: round(Math.min(...rates)),
      max: round(Math.max(...rates)),
      samples: rates.length,
    };
  }

  return {
    ...stats(combinedRates),
    main_only: stats(mainRates),
  };
}

// --- Detector 3: Rapid re-edit clusters ---

function detectRapidEdits(reports) {
  const CLUSTER_WINDOW_MS = 120_000; // 2 minutes
  const MIN_EDITS_IN_CLUSTER = 3;
  const results = [];

  for (const report of reports) {
    for (const entry of report.file_edits?.by_file ?? []) {
      // Tempdocs are investigation logs designed for frequent updates — not thrashing
      if (entry.file?.includes('docs/tempdocs/')) continue;
      if (entry.timestamps.length < MIN_EDITS_IN_CLUSTER) continue;

      const times = entry.timestamps.map(t => new Date(t).getTime()).sort((a, b) => a - b);

      // Sliding window: for each start index, find the longest run within the window.
      // Then advance start past the cluster to find non-overlapping clusters.
      let clusters = 0;
      let maxInCluster = 0;
      let i = 0;

      while (i < times.length) {
        // Find rightmost element within window starting at i
        let j = i + 1;
        while (j < times.length && times[j] - times[i] <= CLUSTER_WINDOW_MS) {
          j++;
        }
        // j is now first element outside window; cluster is [i, j)
        const clusterSize = j - i;
        if (clusterSize >= MIN_EDITS_IN_CLUSTER) {
          clusters++;
          maxInCluster = Math.max(maxInCluster, clusterSize);
          i = j; // Advance past this cluster (non-overlapping)
        } else {
          i++; // No cluster starting here, advance by one
        }
      }

      if (clusters > 0) {
        results.push({
          file: sanitizePath(entry.file),
          session: report.session_id.substring(0, 8),
          clusters,
          max_edits_in_cluster: maxInCluster,
          total_edits: entry.count,
        });
      }
    }
  }

  return results.sort((a, b) => b.max_edits_in_cluster - a.max_edits_in_cluster).slice(0, 15);
}

// --- Detector 4: Bash file-op rate ---

function detectBashFileOps(reports) {
  const rates = [];
  for (const report of reports) {
    const total = report.bash_commands?.total ?? 0;
    if (total === 0) continue;
    const fileOps = report.bash_commands?.file_op_count ?? 0;
    rates.push(fileOps / total);
  }

  if (rates.length === 0) return { mean: 0, min: 0, max: 0, samples: 0 };

  const mean = rates.reduce((s, r) => s + r, 0) / rates.length;
  return {
    mean: round(mean),
    min: round(Math.min(...rates)),
    max: round(Math.max(...rates)),
    samples: rates.length,
  };
}

// --- Detector 5: Subagent/compaction correlation ---

function detectSubagentCompaction(reports) {
  return reports
    .filter(r => (r.subagents?.count ?? 0) > 0 || (r.compactions?.count ?? 0) > 0)
    .map(r => ({
      session: r.session_id.substring(0, 8),
      subagents: r.subagents?.count ?? 0,
      compactions: r.compactions?.count ?? 0,
      duration_hours: r.duration_seconds ? round(r.duration_seconds / 3600) : null,
    }))
    .sort((a, b) => b.subagents - a.subagents);
}

// --- Detector 6: Tool failure hotspots ---

function detectFailures(reports) {
  let totalFailures = 0;
  let totalCalls = 0;

  for (const report of reports) {
    totalFailures += report.tool_calls?.failure_count ?? 0;
    totalCalls += report.tool_calls?.total ?? 0;
  }

  return {
    total_failures: totalFailures,
    total_calls: totalCalls,
    rate: totalCalls > 0 ? round(totalFailures / totalCalls) : 0,
  };
}

function detectBuildFailureRate(reports) {
  let totalBuilds = 0;
  let totalFailed = 0;

  for (const report of reports) {
    totalBuilds += report.bash_commands?.build_count ?? 0;
    totalFailed += report.bash_commands?.failed_build_count ?? 0;
  }

  return {
    total_builds: totalBuilds,
    total_failed: totalFailed,
    rate: totalBuilds > 0 ? round(totalFailed / totalBuilds) : 0,
  };
}

// --- Helpers ---

function round(n) {
  return Math.round(n * 1000) / 1000;
}

function computeTrends(reports, sessionsSkipped = 0) {
  return {
    schema: 'agent-trend-report.v1',
    generated_at: new Date().toISOString(),
    sessions_analyzed: reports.length,
    sessions_skipped: sessionsSkipped,

    hot_files: detectHotFiles(reports),
    unbounded_read_rate: detectUnboundedRate(reports),
    rapid_edit_clusters: detectRapidEdits(reports),
    bash_file_op_rate: detectBashFileOps(reports),
    subagent_compaction: detectSubagentCompaction(reports),
    failure_rate: detectFailures(reports),
    build_failure_rate: detectBuildFailureRate(reports),
  };
}

function formatMarkdown(trends) {
  const lines = [];
  const line = (s = '') => lines.push(s);

  line(`# Agent Efficiency Trend Report`);
  line();
  line(`Generated: ${trends.generated_at}`);
  line(`Sessions analyzed: ${trends.sessions_analyzed}`);
  line();

  // Hot files
  line(`## Hot Files (read ≥10× across sessions)`);
  line();
  if (trends.hot_files.length === 0) {
    line(`No files exceed the threshold.`);
  } else {
    line(`| File | Total | Main | Subagent | Sessions | Unbounded |`);
    line(`|------|-------|------|----------|----------|-----------|`);
    for (const f of trends.hot_files) {
      line(`| ${f.file} | ${f.total_reads} | ${f.main_reads} | ${f.subagent_reads} | ${f.sessions} | ${f.unbounded} |`);
    }
  }
  line();

  // Unbounded read rate
  line(`## Unbounded Read Rate`);
  line();
  const ur = trends.unbounded_read_rate;
  line(`Reads without offset/limit as a fraction of all Read calls (main + subagent combined).`);
  line();
  line(`- **Mean (combined):** ${pct(ur.mean)}`);
  line(`- **Range:** ${pct(ur.min)} – ${pct(ur.max)}`);
  if (ur.main_only) {
    line(`- **Mean (main only):** ${pct(ur.main_only.mean)}`);
  }
  line(`- **Samples:** ${ur.samples} sessions`);
  if (ur.mean > 0.3) {
    line();
    line(`⚠ Mean exceeds 30% threshold. Many reads are loading entire files.`);
  }
  line();

  // Rapid re-edit clusters
  line(`## Rapid Re-Edit Clusters (≥3 edits within 2 min)`);
  line();
  if (trends.rapid_edit_clusters.length === 0) {
    line(`No rapid re-edit clusters detected.`);
  } else {
    line(`| File | Session | Clusters | Max Edits/Cluster | Total Edits |`);
    line(`|------|---------|----------|-------------------|-------------|`);
    for (const c of trends.rapid_edit_clusters) {
      line(`| ${c.file} | ${c.session} | ${c.clusters} | ${c.max_edits_in_cluster} | ${c.total_edits} |`);
    }
  }
  line();

  // Bash file-op rate
  line(`## Bash File-Op Misuse Rate`);
  line();
  const bf = trends.bash_file_op_rate;
  line(`Bash commands using cat/grep/head/tail/etc. instead of dedicated tools.`);
  line();
  line(`- **Mean:** ${pct(bf.mean)}`);
  line(`- **Range:** ${pct(bf.min)} – ${pct(bf.max)}`);
  line(`- **Samples:** ${bf.samples} sessions`);
  if (bf.mean > 0.4) {
    line();
    line(`⚠ Mean exceeds 40% threshold. Agents frequently use Bash for file operations.`);
  }
  line();

  // Subagent/compaction correlation
  line(`## Subagent / Compaction Correlation`);
  line();
  if (trends.subagent_compaction.length === 0) {
    line(`No sessions with subagent or compaction activity.`);
  } else {
    line(`| Session | Subagents | Compactions | Duration (hrs) |`);
    line(`|---------|-----------|-------------|----------------|`);
    for (const s of trends.subagent_compaction) {
      line(`| ${s.session} | ${s.subagents} | ${s.compactions} | ${s.duration_hours ?? '?'} |`);
    }
  }
  line();

  // Failure rate
  line(`## Tool Failure Rate`);
  line();
  const fr = trends.failure_rate;
  line(`- **Failures:** ${fr.total_failures} / ${fr.total_calls} calls (${pct(fr.rate)})`);
  line();

  line(`## Build Failure Rate`);
  line();
  const bfr = trends.build_failure_rate;
  line(`- **Failed builds:** ${bfr.total_failed} / ${bfr.total_builds} builds (${pct(bfr.rate)})`);
  line();

  return lines.join('\n');
}

function pct(n) {
  return `${(n * 100).toFixed(1)}%`;
}

function delta(before, after) {
  const d = after - before;
  const sign = d > 0 ? '+' : '';
  return `${sign}${(d * 100).toFixed(1)}pp`;
}

function formatComparisonMarkdown(before, after, cutoffDate) {
  const lines = [];
  const line = (s = '') => lines.push(s);

  line(`# Agent Efficiency Comparison Report`);
  line();
  line(`Cutoff: ${cutoffDate}`);
  line(`Before: ${before.sessions_analyzed} sessions | After: ${after.sessions_analyzed} sessions`);
  line();

  // Unbounded read rate
  line(`## Unbounded Read Rate`);
  line();
  const urB = before.unbounded_read_rate;
  const urA = after.unbounded_read_rate;
  line(`| Metric | Before | After | Delta |`);
  line(`|--------|--------|-------|-------|`);
  line(`| Mean (combined) | ${pct(urB.mean)} | ${pct(urA.mean)} | ${delta(urB.mean, urA.mean)} |`);
  line(`| Mean (main only) | ${pct(urB.main_only?.mean ?? 0)} | ${pct(urA.main_only?.mean ?? 0)} | ${delta(urB.main_only?.mean ?? 0, urA.main_only?.mean ?? 0)} |`);
  line(`| Range | ${pct(urB.min)}–${pct(urB.max)} | ${pct(urA.min)}–${pct(urA.max)} | — |`);
  line();

  // Bash file-op rate
  line(`## Bash File-Op Misuse Rate`);
  line();
  const bfB = before.bash_file_op_rate;
  const bfA = after.bash_file_op_rate;
  line(`| Metric | Before | After | Delta |`);
  line(`|--------|--------|-------|-------|`);
  line(`| Mean | ${pct(bfB.mean)} | ${pct(bfA.mean)} | ${delta(bfB.mean, bfA.mean)} |`);
  line();

  // Failure rate
  line(`## Tool Failure Rate`);
  line();
  const frB = before.failure_rate;
  const frA = after.failure_rate;
  line(`| Metric | Before | After | Delta |`);
  line(`|--------|--------|-------|-------|`);
  line(`| Rate | ${pct(frB.rate)} | ${pct(frA.rate)} | ${delta(frB.rate, frA.rate)} |`);
  line(`| Failures | ${frB.total_failures}/${frB.total_calls} | ${frA.total_failures}/${frA.total_calls} | — |`);
  line();

  // Build failure rate
  line(`## Build Failure Rate`);
  line();
  const bfrB = before.build_failure_rate;
  const bfrA = after.build_failure_rate;
  line(`| Metric | Before | After | Delta |`);
  line(`|--------|--------|-------|-------|`);
  line(`| Rate | ${pct(bfrB.rate)} | ${pct(bfrA.rate)} | ${delta(bfrB.rate, bfrA.rate)} |`);
  line(`| Failed | ${bfrB.total_failed}/${bfrB.total_builds} | ${bfrA.total_failed}/${bfrA.total_builds} | — |`);
  line();

  // Hot files comparison
  line(`## Hot Files (Before vs After)`);
  line();
  const allFiles = new Map();
  for (const f of before.hot_files) {
    allFiles.set(f.file, { before_reads: f.total_reads, after_reads: 0, before_sub: f.subagent_reads ?? 0, after_sub: 0 });
  }
  for (const f of after.hot_files) {
    if (allFiles.has(f.file)) {
      const entry = allFiles.get(f.file);
      entry.after_reads = f.total_reads;
      entry.after_sub = f.subagent_reads ?? 0;
    } else {
      allFiles.set(f.file, { before_reads: 0, after_reads: f.total_reads, before_sub: 0, after_sub: f.subagent_reads ?? 0 });
    }
  }
  const mergedFiles = [...allFiles.entries()]
    .sort((a, b) => (b[1].before_reads + b[1].after_reads) - (a[1].before_reads + a[1].after_reads))
    .slice(0, 15);

  if (mergedFiles.length > 0) {
    line(`| File | Before | After | Delta | Subagent (B/A) |`);
    line(`|------|--------|-------|-------|----------------|`);
    for (const [file, data] of mergedFiles) {
      const d = data.after_reads - data.before_reads;
      const sign = d > 0 ? '+' : '';
      line(`| ${file} | ${data.before_reads} | ${data.after_reads} | ${sign}${d} | ${data.before_sub}/${data.after_sub} |`);
    }
  } else {
    line(`No hot files in either period.`);
  }
  line();

  // Rapid re-edit clusters
  line(`## Rapid Re-Edit Clusters`);
  line();
  const recB = before.rapid_edit_clusters.length;
  const recA = after.rapid_edit_clusters.length;
  line(`- **Before:** ${recB} clusters across ${before.sessions_analyzed} sessions`);
  line(`- **After:** ${recA} clusters across ${after.sessions_analyzed} sessions`);
  if (before.sessions_analyzed > 0 && after.sessions_analyzed > 0) {
    const rateB = round(recB / before.sessions_analyzed);
    const rateA = round(recA / after.sessions_analyzed);
    line(`- **Per session:** ${rateB} → ${rateA}`);
  }
  line();

  // Subagent / compaction
  line(`## Subagent / Compaction`);
  line();
  const scB = before.subagent_compaction;
  const scA = after.subagent_compaction;
  const avgSubB = scB.length > 0 ? round(scB.reduce((s, e) => s + e.subagents, 0) / scB.length) : 0;
  const avgSubA = scA.length > 0 ? round(scA.reduce((s, e) => s + e.subagents, 0) / scA.length) : 0;
  const avgCompB = scB.length > 0 ? round(scB.reduce((s, e) => s + e.compactions, 0) / scB.length) : 0;
  const avgCompA = scA.length > 0 ? round(scA.reduce((s, e) => s + e.compactions, 0) / scA.length) : 0;
  line(`| Metric | Before | After |`);
  line(`|--------|--------|-------|`);
  line(`| Avg subagents/session | ${avgSubB} | ${avgSubA} |`);
  line(`| Avg compactions/session | ${avgCompB} | ${avgCompA} |`);
  line();

  return lines.join('\n');
}

function main() {
  const jsonOnly = process.argv.includes('--json');
  // Use stderr for progress so --json stdout stays clean
  const log = jsonOnly ? console.error.bind(console) : console.log.bind(console);

  // Parse --cutoff flag
  const cutoffIdx = process.argv.indexOf('--cutoff');
  let cutoffDate = null;
  if (cutoffIdx !== -1) {
    const raw = process.argv[cutoffIdx + 1];
    if (!raw || isNaN(Date.parse(raw))) {
      console.error('Invalid --cutoff date. Use ISO format: --cutoff 2026-02-04T00:00:00Z');
      process.exit(1);
    }
    cutoffDate = raw;
  }

  log('Loading session reports...');
  const { reports, skipped } = loadSessionReports();
  log(`Loaded ${reports.length} substantive sessions (skipped ${skipped.length} ephemeral)`);

  if (reports.length === 0) {
    console.error('No substantive sessions to analyze.');
    process.exit(1);
  }

  // --- Comparison mode (--cutoff) ---
  if (cutoffDate) {
    const cutoffMs = Date.parse(cutoffDate);
    const before = reports.filter(r => Date.parse(r.started_at) < cutoffMs);
    const after = reports.filter(r => Date.parse(r.started_at) >= cutoffMs);

    if (before.length === 0 || after.length === 0) {
      console.error(`Cutoff ${cutoffDate} splits into ${before.length} before / ${after.length} after — need both non-empty.`);
      process.exit(1);
    }

    const skippedBefore = skipped.filter(r => Date.parse(r.started_at) < cutoffMs).length;
    const skippedAfter = skipped.filter(r => Date.parse(r.started_at) >= cutoffMs).length;
    log(`Comparison: ${before.length} sessions before cutoff (${skippedBefore} skipped), ${after.length} after (${skippedAfter} skipped)`);

    const trendsBefore = computeTrends(before, skippedBefore);
    const trendsAfter = computeTrends(after, skippedAfter);

    if (jsonOnly) {
      process.stdout.write(JSON.stringify({ cutoff: cutoffDate, before: trendsBefore, after: trendsAfter }, null, 2) + '\n');
      return;
    }

    const markdown = formatComparisonMarkdown(trendsBefore, trendsAfter, cutoffDate);

    const reportsDir = path.join(repoRoot, TELEMETRY_DIR, REPORTS_DIR);
    fs.mkdirSync(reportsDir, { recursive: true });

    const ts = new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19);
    const jsonPath = path.join(reportsDir, `comparison-${ts}.json`);
    const mdPath = path.join(reportsDir, `comparison-${ts}.md`);

    fs.writeFileSync(jsonPath, JSON.stringify({ cutoff: cutoffDate, before: trendsBefore, after: trendsAfter }, null, 2) + '\n', 'utf8');
    fs.writeFileSync(mdPath, markdown + '\n', 'utf8');

    console.log(`\nComparison reports written to:`);
    console.log(`  ${jsonPath}`);
    console.log(`  ${mdPath}`);
    console.log();
    console.log(markdown);
    return;
  }

  // --- Standard trend mode ---
  const trends = computeTrends(reports, skipped.length);
  const markdown = formatMarkdown(trends);

  if (jsonOnly) {
    process.stdout.write(JSON.stringify(trends, null, 2) + '\n');
    return;
  }

  // Write outputs
  const reportsDir = path.join(repoRoot, TELEMETRY_DIR, REPORTS_DIR);
  fs.mkdirSync(reportsDir, { recursive: true });

  const ts = new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19);
  const jsonPath = path.join(reportsDir, `trend-${ts}.json`);
  const mdPath = path.join(reportsDir, `trend-${ts}.md`);

  fs.writeFileSync(jsonPath, JSON.stringify(trends, null, 2) + '\n', 'utf8');
  fs.writeFileSync(mdPath, markdown + '\n', 'utf8');

  console.log(`\nReports written to:`);
  console.log(`  ${jsonPath}`);
  console.log(`  ${mdPath}`);
  console.log();
  console.log(markdown);
}

main();
