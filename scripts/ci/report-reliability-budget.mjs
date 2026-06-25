#!/usr/bin/env node
/**
 * Generate a reliability budget report from local gate summaries.
 *
 * Default mode is warn-only and always exits 0.
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir =
  process.platform === 'win32' ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..');

const DEFAULT_SUMMARIES_DIR = path.join(repoRoot, 'tmp', 'agent-evidence', '_summaries');
const DEFAULT_OUT_JSON = path.join(repoRoot, 'tmp', 'reliability-budget', 'latest.json');
const DEFAULT_OUT_MD = path.join(repoRoot, 'tmp', 'reliability-budget', 'latest.md');
const SUMMARY_FILE_PATTERN = /^local-agent-gate-\d{8}-\d{6}\.json$/;
const CLASS_FALLBACK_BLOCKLIST = new Set([
  'BUILD',
  'FAILURE',
  'Task',
  'Execution',
  'Deprecated',
  'Could',
  'What',
  'Try',
]);
const ANSI_ESCAPE_REGEX = /\u001b\[[0-9;]*m/g;

const LANE_DEFS = [
  {
    key: 'gradle_gate',
    label: 'gradle_gate',
    failed: (lane) => lane?.ran === true && asInt(lane.exit_code) !== 0,
  },
  {
    key: 'playwright',
    label: 'playwright',
    failed: (lane) => lane?.ran === true && asInt(lane.exit_code) !== 0,
  },
  {
    key: 'ui_unit',
    label: 'ui_unit',
    failed: (lane) => lane?.ran === true && asInt(lane.exit_code) !== 0,
  },
  {
    key: 'cargo_check',
    label: 'cargo_check',
    failed: (lane) => lane?.ran === true && asInt(lane.exit_code) !== 0,
  },
  {
    key: 'cargo_audit',
    label: 'cargo_audit',
    failed: (lane) => lane?.ran === true && asInt(lane.exit_code) !== 0,
  },
  {
    key: 'lock_skew',
    label: 'lock_skew',
    failed: (lane) => lane?.ran === true && lane?.enforce === true && asInt(lane.exit_code) !== 0,
  },
];

function isEarlyGateAbort(run) {
  const summary = run.summary ?? {};
  const devRunner = summary.dev_runner ?? {};
  if (devRunner.started !== true) return false;
  if (typeof devRunner.run_id === 'string' && devRunner.run_id.trim().length > 0) return false;

  const anyLaneRan = LANE_DEFS.some((def) => summary?.[def.key]?.ran === true);
  return !anyLaneRan;
}

function usage(code = 1) {
  // eslint-disable-next-line no-console
  console.error(
    [
      'Usage:',
      '  node scripts/ci/report-reliability-budget.mjs [options]',
      '',
      'Options:',
      `  --summaries-dir <path>   Local gate summaries dir (default: ${DEFAULT_SUMMARIES_DIR})`,
      '  --lookback-runs <n>      Number of latest runs to analyze (default: 30)',
      `  --out-json <path>        JSON report path (default: ${DEFAULT_OUT_JSON})`,
      `  --out-md <path>          Markdown report path (default: ${DEFAULT_OUT_MD})`,
      '  --fail-on-threshold      Optional strict mode (default: warn-only)',
      '  --threshold <0..1>       Strict mode threshold for lane fail rate (default: 1)',
      '  -h, --help',
    ].join('\n'),
  );
  process.exit(code);
}

function parseArgs(argv) {
  const out = {
    summariesDir: DEFAULT_SUMMARIES_DIR,
    lookbackRuns: 30,
    outJson: DEFAULT_OUT_JSON,
    outMd: DEFAULT_OUT_MD,
    failOnThreshold: false,
    threshold: 1,
  };

  const args = [...argv];
  const getValue = (idx) => {
    const token = args[idx];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[idx + 1] ?? null;
  };
  const consumeNext = new Set();

  for (let i = 0; i < args.length; i += 1) {
    if (consumeNext.has(i)) continue;
    const token = args[i];

    if (token === '-h' || token === '--help') usage(0);
    if (!token.startsWith('--')) continue;

    const key = token.split('=')[0];
    const hasInlineValue = token.includes('=');
    const value = getValue(i);
    if (!hasInlineValue) consumeNext.add(i + 1);

    switch (key) {
      case '--summaries-dir':
        out.summariesDir = value || out.summariesDir;
        break;
      case '--lookback-runs':
        out.lookbackRuns = Number.parseInt(value || '', 10);
        break;
      case '--out-json':
        out.outJson = value || out.outJson;
        break;
      case '--out-md':
        out.outMd = value || out.outMd;
        break;
      case '--threshold':
        out.threshold = Number.parseFloat(value || '');
        break;
      case '--fail-on-threshold':
        out.failOnThreshold = true;
        if (!hasInlineValue) consumeNext.delete(i + 1);
        break;
      default:
        throw new Error(`Unknown option: ${token}`);
    }
  }

  if (!Number.isInteger(out.lookbackRuns) || out.lookbackRuns <= 0) {
    throw new Error('--lookback-runs must be a positive integer');
  }
  if (!Number.isFinite(out.threshold) || out.threshold < 0 || out.threshold > 1) {
    throw new Error('--threshold must be between 0 and 1');
  }

  out.summariesDir = path.resolve(out.summariesDir);
  out.outJson = path.resolve(out.outJson);
  out.outMd = path.resolve(out.outMd);

  return out;
}

function asInt(value) {
  if (value === null || value === undefined) return null;
  const parsed = Number.parseInt(String(value), 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function round3(value) {
  return Math.round(value * 1000) / 1000;
}

function formatPct(value) {
  return `${(value * 100).toFixed(1)}%`;
}

function toIsoOrNull(ts) {
  if (!Number.isFinite(ts)) return null;
  return new Date(ts).toISOString();
}

function sortByFailuresThenRecency(a, b, lastFailedKey) {
  if (b.failures !== a.failures) return b.failures - a.failures;
  const aTs = a[lastFailedKey] ? Date.parse(a[lastFailedKey]) : 0;
  const bTs = b[lastFailedKey] ? Date.parse(b[lastFailedKey]) : 0;
  if (bTs !== aTs) return bTs - aTs;
  return a.name.localeCompare(b.name);
}

function normalizeMethodFailure(className, testNameRaw) {
  const testName = String(testNameRaw || '').trim().replace(/\(\)\s*$/, '');
  const safeTest = testName.length > 0 ? testName : '<unknown-test>';
  return `${className}#${safeTest}`;
}

function normalizeClassFailure(className) {
  return `${className}#<class-level-failure>`;
}

function collectFailedTestsFromLog(logText) {
  const normalizedText = String(logText || '').replace(ANSI_ESCAPE_REGEX, '');
  const failed = new Set();

  const methodRegex = /^\s*([A-Za-z0-9_.$]+)\s*>\s*([^\r\n]+?)\s+FAILED\b/gm;
  let match = null;
  while ((match = methodRegex.exec(normalizedText)) !== null) {
    const className = match[1].trim();
    const testName = match[2];
    failed.add(normalizeMethodFailure(className, testName));
  }

  const classRegex = /^\s*([A-Za-z0-9_.$]+)\s+FAILED\b/gm;
  while ((match = classRegex.exec(normalizedText)) !== null) {
    const className = match[1].trim();
    if (className.includes(':')) continue;
    if (CLASS_FALLBACK_BLOCKLIST.has(className)) continue;
    failed.add(normalizeClassFailure(className));
  }

  return failed;
}

async function readJsonSafe(filePath, warnings) {
  try {
    const raw = await fs.readFile(filePath, 'utf8');
    const clean = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
    return JSON.parse(clean);
  } catch (err) {
    warnings.push(`Failed to parse summary JSON: ${filePath} (${err.message})`);
    return null;
  }
}

async function readLogSafe(filePath, warnings) {
  try {
    return await fs.readFile(filePath, 'utf8');
  } catch (err) {
    warnings.push(`Failed to read Gradle log: ${filePath} (${err.message})`);
    return null;
  }
}

async function loadSummaries(summariesDir, warnings) {
  let entries = [];
  try {
    entries = await fs.readdir(summariesDir, { withFileTypes: true });
  } catch (err) {
    warnings.push(`Could not read summaries dir: ${summariesDir} (${err.message})`);
    return {
      filesScanned: 0,
      filesParsed: 0,
      filesCorrupt: 0,
      runs: [],
    };
  }

  const candidateFiles = entries
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .filter((name) => SUMMARY_FILE_PATTERN.test(name))
    .sort();

  const runs = [];
  let filesParsed = 0;
  let filesCorrupt = 0;

  for (const fileName of candidateFiles) {
    const fullPath = path.join(summariesDir, fileName);
    const parsed = await readJsonSafe(fullPath, warnings);
    if (!parsed) {
      filesCorrupt += 1;
      continue;
    }
    if (parsed.schema !== 'local-agent-gate.v1') {
      warnings.push(`Ignoring non-gate summary: ${fullPath}`);
      continue;
    }
    filesParsed += 1;
    const startedAtMs = Date.parse(parsed.started_at ?? '');
    const fileStamp = fileName.match(/^local-agent-gate-(\d{8})-(\d{6})\.json$/);
    const fallbackIso = fileStamp
      ? `${fileStamp[1].slice(0, 4)}-${fileStamp[1].slice(4, 6)}-${fileStamp[1].slice(
          6,
          8,
        )}T${fileStamp[2].slice(0, 2)}:${fileStamp[2].slice(2, 4)}:${fileStamp[2].slice(
          4,
          6,
        )}Z`
      : null;
    const fallbackMs = fallbackIso ? Date.parse(fallbackIso) : Number.NaN;
    runs.push({
      file: fileName,
      summaryPath: fullPath,
      summary: parsed,
      startedAtMs: Number.isFinite(startedAtMs) ? startedAtMs : fallbackMs,
    });
  }

  runs.sort((a, b) => {
    const aMs = Number.isFinite(a.startedAtMs) ? a.startedAtMs : 0;
    const bMs = Number.isFinite(b.startedAtMs) ? b.startedAtMs : 0;
    if (bMs !== aMs) return bMs - aMs;
    return b.file.localeCompare(a.file);
  });

  return {
    filesScanned: candidateFiles.length,
    filesParsed,
    filesCorrupt,
    runs,
  };
}

function buildLaneRows(selectedRuns) {
  const rows = LANE_DEFS.map((def) => ({
    name: def.label,
    key: def.key,
    ran_count: 0,
    failures: 0,
    fail_rate: 0,
    last_failed_at: null,
  }));
  const byKey = new Map(rows.map((row) => [row.key, row]));

  for (const run of selectedRuns) {
    for (const def of LANE_DEFS) {
      const lane = run.summary?.[def.key];
      if (!lane || lane.ran !== true) continue;
      const row = byKey.get(def.key);
      row.ran_count += 1;
      if (def.failed(lane)) {
        row.failures += 1;
        const runStartedAt = toIsoOrNull(run.startedAtMs);
        if (!row.last_failed_at) {
          row.last_failed_at = runStartedAt;
        } else {
          const current = Date.parse(row.last_failed_at);
          const candidate = Date.parse(runStartedAt ?? '');
          if (Number.isFinite(candidate) && (!Number.isFinite(current) || candidate > current)) {
            row.last_failed_at = runStartedAt;
          }
        }
      }
    }
  }

  for (const row of rows) {
    row.fail_rate = row.ran_count > 0 ? round3(row.failures / row.ran_count) : 0;
  }

  rows.sort((a, b) => sortByFailuresThenRecency(a, b, 'last_failed_at'));
  return rows;
}

async function buildFailedTests(selectedRuns, warnings) {
  const map = new Map();

  for (let runIndex = 0; runIndex < selectedRuns.length; runIndex += 1) {
    const run = selectedRuns[runIndex];
    const gradle = run.summary?.gradle_gate;
    if (!gradle || gradle.ran !== true) continue;

    const logPaths = [];
    if (typeof gradle.stdout === 'string' && gradle.stdout.trim().length > 0) {
      logPaths.push(gradle.stdout.trim());
    }
    if (typeof gradle.stderr === 'string' && gradle.stderr.trim().length > 0) {
      logPaths.push(gradle.stderr.trim());
    }

    const uniquePaths = new Set();
    const runFailedTests = new Set();

    for (const rawPath of logPaths) {
      const resolved = path.isAbsolute(rawPath) ? rawPath : path.resolve(repoRoot, rawPath);
      if (uniquePaths.has(resolved)) continue;
      uniquePaths.add(resolved);
      const logText = await readLogSafe(resolved, warnings);
      if (!logText) continue;
      const fromFile = collectFailedTestsFromLog(logText);
      for (const testName of fromFile) runFailedTests.add(testName);
    }

    for (const testName of runFailedTests) {
      if (!map.has(testName)) {
        map.set(testName, {
          name: testName,
          failures: 0,
          last_seen: null,
          failed_run_indexes: [],
        });
      }
      const row = map.get(testName);
      row.failures += 1;
      row.failed_run_indexes.push(runIndex);
      const runIso = toIsoOrNull(run.startedAtMs);
      if (!row.last_seen) {
        row.last_seen = runIso;
      } else {
        const current = Date.parse(row.last_seen);
        const candidate = Date.parse(runIso ?? '');
        if (Number.isFinite(candidate) && (!Number.isFinite(current) || candidate > current)) {
          row.last_seen = runIso;
        }
      }
    }
  }

  const rows = [...map.values()].map((row) => {
    const sorted = [...row.failed_run_indexes].sort((a, b) => a - b);
    let nonConsecutive = false;
    for (let i = 1; i < sorted.length; i += 1) {
      if (sorted[i] - sorted[i - 1] > 1) {
        nonConsecutive = true;
        break;
      }
    }
    return {
      name: row.name,
      failures: row.failures,
      last_seen: row.last_seen,
      flaky_candidate: row.failures >= 2 && nonConsecutive,
    };
  });

  rows.sort((a, b) => sortByFailuresThenRecency(a, b, 'last_seen'));
  return rows;
}

function buildTopIssues(laneRows, failedTests) {
  const laneIssues = laneRows
    .filter((row) => row.failures > 0)
    .map((row) => ({
      type: 'lane',
      key: row.name,
      failures: row.failures,
      last_seen: row.last_failed_at,
      detail: `fail_rate=${formatPct(row.fail_rate)} (${row.failures}/${row.ran_count})`,
    }));

  const testIssues = failedTests
    .filter((row) => row.failures > 0)
    .map((row) => ({
      type: 'test',
      key: row.name,
      failures: row.failures,
      last_seen: row.last_seen,
      detail: row.flaky_candidate ? 'flaky_candidate=true' : 'flaky_candidate=false',
    }));

  const all = [...laneIssues, ...testIssues];
  all.sort((a, b) => {
    if (b.failures !== a.failures) return b.failures - a.failures;
    const aTs = a.last_seen ? Date.parse(a.last_seen) : 0;
    const bTs = b.last_seen ? Date.parse(b.last_seen) : 0;
    if (bTs !== aTs) return bTs - aTs;
    if (a.type !== b.type) return a.type.localeCompare(b.type);
    return a.key.localeCompare(b.key);
  });
  return all.slice(0, 10);
}

function renderMarkdown(report) {
  const lines = [];
  const line = (value = '') => lines.push(value);

  line('# Reliability Budget Report');
  line();
  line(`Generated: ${report.generated_at}`);
  line();
  line('## Run Window');
  line();
  line(`- Summaries dir: \`${report.run_window.summaries_dir}\``);
  line(`- Files scanned: ${report.run_window.files_scanned}`);
  line(`- Files parsed: ${report.run_window.files_parsed}`);
  line(`- Corrupt/invalid files: ${report.run_window.files_corrupt}`);
  line(`- Lookback runs: ${report.run_window.lookback_runs}`);
  line(`- Runs considered: ${report.run_window.runs_considered}`);
  line(`- Early abort runs: ${report.run_window.early_abort_runs}`);
  line(`- Newest run: ${report.run_window.newest_started_at ?? 'n/a'}`);
  line(`- Oldest run: ${report.run_window.oldest_started_at ?? 'n/a'}`);
  line();

  line('## Lane Failures');
  line();
  line('| Lane | Failures | Ran | Fail rate | Last failure |');
  line('|------|----------|-----|-----------|--------------|');
  for (const lane of report.lane_failures) {
    line(
      `| ${lane.name} | ${lane.failures} | ${lane.ran_count} | ${formatPct(lane.fail_rate)} | ${
        lane.last_failed_at ?? 'n/a'
      } |`,
    );
  }
  line();

  line('## Failed Tests');
  line();
  if (report.failed_tests.length === 0) {
    line('No failed tests were detected in Gradle logs for the selected run window.');
  } else {
    line('| Test | Failures | Last seen | Flaky candidate |');
    line('|------|----------|-----------|-----------------|');
    for (const test of report.failed_tests) {
      line(
        `| ${test.name} | ${test.failures} | ${test.last_seen ?? 'n/a'} | ${
          test.flaky_candidate ? 'true' : 'false'
        } |`,
      );
    }
  }
  line();

  line('## Top Issues');
  line();
  if (report.top_issues.length === 0) {
    line('No gate-impacting failures found in the selected window.');
  } else {
    line('| Rank | Type | Key | Failures | Last seen | Detail |');
    line('|------|------|-----|----------|-----------|--------|');
    report.top_issues.forEach((issue, index) => {
      line(
        `| ${index + 1} | ${issue.type} | ${issue.key} | ${issue.failures} | ${
          issue.last_seen ?? 'n/a'
        } | ${issue.detail} |`,
      );
    });
  }
  line();

  if (report.warnings.length > 0) {
    line('## Warnings');
    line();
    for (const warning of report.warnings) {
      line(`- ${warning}`);
    }
    line();
  }

  return `${lines.join('\n')}\n`;
}

async function writeReport(outJson, outMd, report) {
  await fs.mkdir(path.dirname(outJson), { recursive: true });
  await fs.mkdir(path.dirname(outMd), { recursive: true });
  await fs.writeFile(outJson, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await fs.writeFile(outMd, renderMarkdown(report), 'utf8');
}

function printCompactSummary(report, outJson, outMd) {
  const topLanes = report.lane_failures.filter((row) => row.failures > 0).slice(0, 5);
  const topTests = report.failed_tests.filter((row) => row.failures > 0).slice(0, 5);
  const totalLaneFailures = report.lane_failures.reduce((sum, lane) => sum + lane.failures, 0);

  // eslint-disable-next-line no-console
  console.log(
    `reliability-budget: runs=${report.run_window.runs_considered}, lane_failures=${totalLaneFailures}, failed_tests=${report.failed_tests.length}`,
  );
  // eslint-disable-next-line no-console
  console.log('top_lane_hotspots:');
  if (topLanes.length === 0) {
    // eslint-disable-next-line no-console
    console.log('  (none)');
  } else {
    for (const lane of topLanes) {
      // eslint-disable-next-line no-console
      console.log(
        `  ${lane.name}: failures=${lane.failures}, fail_rate=${formatPct(lane.fail_rate)} (${lane.failures}/${lane.ran_count})`,
      );
    }
  }
  // eslint-disable-next-line no-console
  console.log('top_test_hotspots:');
  if (topTests.length === 0) {
    // eslint-disable-next-line no-console
    console.log('  (none)');
  } else {
    for (const test of topTests) {
      // eslint-disable-next-line no-console
      console.log(
        `  ${test.name}: failures=${test.failures}, flaky_candidate=${test.flaky_candidate ? 'true' : 'false'}`,
      );
    }
  }
  // eslint-disable-next-line no-console
  console.log(`json_report=${outJson}`);
  // eslint-disable-next-line no-console
  console.log(`markdown_report=${outMd}`);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const warnings = [];
  const loaded = await loadSummaries(options.summariesDir, warnings);
  const selectedRuns = loaded.runs.slice(0, options.lookbackRuns);
  const earlyAbortRuns = selectedRuns.filter((run) => isEarlyGateAbort(run));
  const laneRows = buildLaneRows(selectedRuns);
  const failedTests = await buildFailedTests(selectedRuns, warnings);
  const topIssues = buildTopIssues(laneRows, failedTests);

  const newestStartedAt = selectedRuns.length > 0 ? toIsoOrNull(selectedRuns[0].startedAtMs) : null;
  const oldestStartedAt =
    selectedRuns.length > 0 ? toIsoOrNull(selectedRuns[selectedRuns.length - 1].startedAtMs) : null;

  if (earlyAbortRuns.length > 0) {
    const latestEarlyAbort = toIsoOrNull(earlyAbortRuns[0].startedAtMs);
    warnings.push(
      `Detected ${earlyAbortRuns.length} early gate-abort run(s) before lane execution (typically dev-runner startup failures). Latest=${latestEarlyAbort ?? 'n/a'}.`,
    );
  }

  const report = {
    schema: 'reliability-budget.v1',
    generated_at: new Date().toISOString(),
    run_window: {
      summaries_dir: options.summariesDir,
      files_scanned: loaded.filesScanned,
      files_parsed: loaded.filesParsed,
      files_corrupt: loaded.filesCorrupt,
      lookback_runs: options.lookbackRuns,
      runs_considered: selectedRuns.length,
      early_abort_runs: earlyAbortRuns.length,
      newest_started_at: newestStartedAt,
      oldest_started_at: oldestStartedAt,
    },
    lane_failures: laneRows,
    failed_tests: failedTests,
    top_issues: topIssues,
    warnings,
  };

  await writeReport(options.outJson, options.outMd, report);
  printCompactSummary(report, options.outJson, options.outMd);

  if (options.failOnThreshold) {
    const thresholdHit = laneRows.some((lane) => lane.ran_count > 0 && lane.fail_rate >= options.threshold);
    if (thresholdHit) {
      // eslint-disable-next-line no-console
      console.error(
        `Reliability threshold breached: at least one lane fail rate >= ${options.threshold}.`,
      );
      process.exit(1);
    }
  }
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error(err?.stack || String(err));
  process.exit(1);
});
