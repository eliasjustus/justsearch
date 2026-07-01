#!/usr/bin/env node
/**
 * Summarize GitHub Actions per-job / per-step wall-clock for a public CI run.
 *
 * This is attribution only: it reports where a run's wall-clock time goes (which
 * lane is the critical path; how much of each lane is fixed runner tax vs work)
 * without changing any check result. It is the wall-clock sibling of
 * report-unit-test-attribution.mjs — that script reads JUnit XML (test-suite
 * time); this one reads the Actions jobs JSON (job wall-clock), a different and
 * complementary layer (job wall-clock is ~2x summed-suite time; the gap is JVM
 * cold start + Gradle configuration + fork serialization). Tempdoc 667.
 *
 * Input is the parsed JSON from
 *   gh api /repos/{owner}/{repo}/actions/runs/{run_id}/jobs --paginate
 * supplied via --jobs-json <file> so the network/auth fetch stays out of the
 * pure logic (mirrors how the unit script consumes XML produced by a prior step).
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-ci-walltime-attribution.v1';

// Steps that are runner overhead rather than the job's actual work. Everything
// else is counted as "work". Kept deliberately small and name-based; the Actions
// API gives no machine classification, so this is a documented heuristic.
const FIXED_TAX_STEP = /^(Set up job|Checkout|Setup |Install |Post |Complete job|Upload |Report )/;

function usage() {
  return [
    'Usage: node scripts/ci/report-ci-walltime-attribution.mjs --jobs-json <file> [options]',
    '',
    'Options:',
    '  --jobs-json <path>        Parsed `gh api .../runs/<id>/jobs` JSON (required)',
    '  --unit-attribution-dir <path>  Dir of downloaded unit-test-attribution artifacts',
    '                            (enables the per-unit-lane test-vs-overhead split)',
    '  --run-id <id>             Run id for the report header',
    '  --repository <owner/repo> Repository slug for the report header',
    '  --workflow <name>         Workflow name for the report header',
    '  --self-job <name>         Job name to exclude (the attribution job itself)',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    jobsJson: null,
    unitAttributionDir: null,
    runId: null,
    repository: null,
    workflow: null,
    selfJob: null,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--jobs-json' && argv[i + 1]) out.jobsJson = argv[++i];
    else if (arg === '--unit-attribution-dir' && argv[i + 1]) out.unitAttributionDir = argv[++i];
    else if (arg === '--run-id' && argv[i + 1]) out.runId = argv[++i];
    else if (arg === '--repository' && argv[i + 1]) out.repository = argv[++i];
    else if (arg === '--workflow' && argv[i + 1]) out.workflow = argv[++i];
    else if (arg === '--self-job' && argv[i + 1]) out.selfJob = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!out.help && !out.jobsJson) throw new Error('--jobs-json is required');
  return out;
}

function elapsedSeconds(startedAt, completedAt) {
  if (!startedAt || !completedAt) return null;
  const start = Date.parse(startedAt);
  const end = Date.parse(completedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
  return Math.round((end - start) / 1000);
}

function classifyStep(step) {
  const seconds = elapsedSeconds(step.started_at, step.completed_at) ?? 0;
  const fixed = FIXED_TAX_STEP.test(String(step.name || ''));
  return { name: step.name || '<step>', seconds, fixed };
}

// A unit-test lane job is named "Unit tests (<lane>)"; the capture group is the
// lane key used by the unit-test-attribution artifacts (e.g. "app-ui").
const UNIT_LANE_JOB = /^Unit tests \((.+)\)$/;

export function buildWalltimeReport({ run = {}, jobs = [], selfJob = null, suiteTimes = {} }) {
  const jobReports = [];
  for (const job of Array.isArray(jobs) ? jobs : []) {
    if (selfJob && job.name === selfJob) continue;
    const wallSeconds = elapsedSeconds(job.started_at, job.completed_at);
    // In-progress jobs (e.g. the attribution job itself) have no end time yet.
    if (wallSeconds === null) continue;
    const steps = (Array.isArray(job.steps) ? job.steps : []).map(classifyStep);
    const fixedTaxSeconds = steps.filter((s) => s.fixed).reduce((a, s) => a + s.seconds, 0);
    const workSeconds = steps.filter((s) => !s.fixed).reduce((a, s) => a + s.seconds, 0);
    const report = {
      name: job.name || '<job>',
      conclusion: job.conclusion || null,
      wallSeconds,
      fixedTaxSeconds,
      workSeconds,
      startedAt: job.started_at || null,
      completedAt: job.completed_at || null,
      steps,
    };
    // For unit lanes, split the opaque "Unit tests" step (which `workSeconds`
    // captures) into actual test CPU vs framework overhead by correlating the
    // summed suite time from that lane's already-uploaded unit-test-attribution
    // artifact. This is a DERIVED projection over two existing records, never a
    // re-measurement (tempdoc 667 design). `testCpu` is summed suite time — CPU,
    // not wall, when a module runs parallel forks — so overhead is a conservative
    // floor and is clamped at 0 to stay non-negative under that overcount.
    const laneMatch = UNIT_LANE_JOB.exec(report.name);
    const suite = laneMatch ? suiteTimes[laneMatch[1]] : undefined;
    if (Number.isFinite(suite)) {
      report.components = {
        testCpuSeconds: Math.round(suite),
        frameworkOverheadSeconds: Math.max(0, Math.round(workSeconds - suite)),
        fixedTaxSeconds,
      };
    }
    jobReports.push(report);
  }
  jobReports.sort((a, b) => b.wallSeconds - a.wallSeconds || a.name.localeCompare(b.name));

  const critical = jobReports[0] || null;
  const totals = {
    jobs: jobReports.length,
    // Jobs run in parallel, so the run's wall-clock is bounded by the slowest job.
    criticalPathSeconds: critical ? critical.wallSeconds : 0,
    summedJobSeconds: jobReports.reduce((a, j) => a + j.wallSeconds, 0),
    fixedTaxSeconds: jobReports.reduce((a, j) => a + j.fixedTaxSeconds, 0),
    workSeconds: jobReports.reduce((a, j) => a + j.workSeconds, 0),
  };

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    run: {
      runId: run.runId || null,
      runAttempt: run.runAttempt || null,
      repository: run.repository || null,
      workflow: run.workflow || null,
    },
    // The jobs API reuses the ORIGINAL attempt's step timestamps on unchanged
    // reruns (run_attempt > 1); surface it so consumers can discount stale timings.
    rerunCaveat: Number(run.runAttempt) > 1,
    criticalPath: critical ? { job: critical.name, wallSeconds: critical.wallSeconds } : null,
    totals,
    jobs: jobReports,
  };
}

function fmtSeconds(n) {
  if (n === null || n === undefined) return 'n/a';
  if (n >= 60) return `${Math.floor(n / 60)}m ${Math.round(n % 60)}s`;
  return `${n}s`;
}

export function renderMarkdown(report) {
  const lines = [
    '### CI wall-clock attribution',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Run: ${report.run.repository || 'unknown'} #${report.run.runId || 'unknown'} (attempt ${report.run.runAttempt || '1'}), workflow ${report.run.workflow || 'unknown'}`,
    '',
    report.rerunCaveat
      ? '> Rerun (attempt > 1): the Actions jobs API reuses original-attempt step timestamps for unchanged jobs, so durations may be stale.'
      : '',
    report.criticalPath
      ? `Critical path: **${report.criticalPath.job}** at ${fmtSeconds(report.criticalPath.wallSeconds)} (jobs run in parallel, so this bounds the run).`
      : 'Critical path: none (no completed jobs).',
    '',
    `Totals: ${report.totals.jobs} jobs, fixed runner tax ${fmtSeconds(report.totals.fixedTaxSeconds)}, work ${fmtSeconds(report.totals.workSeconds)} (summed across jobs).`,
    '',
    '| Job | Wall clock | Fixed tax | Work | Conclusion |',
    '|---|---:|---:|---:|---|',
  ].filter((line) => line !== '');
  for (const job of report.jobs) {
    lines.push(`| ${job.name} | ${fmtSeconds(job.wallSeconds)} | ${fmtSeconds(job.fixedTaxSeconds)} | ${fmtSeconds(job.workSeconds)} | ${job.conclusion || 'unknown'} |`);
  }
  lines.push('');

  const decomposed = report.jobs.filter((job) => job.components);
  if (decomposed.length > 0) {
    lines.push(
      '#### Unit-lane cost breakdown',
      '',
      'Where a unit lane\'s wall-clock actually goes: how much is real test work vs framework overhead (Gradle configuration + JVM startup + fork serialization + inter-module gaps). Derived by correlating each lane\'s summed suite time with its test-step wall-clock — no re-measurement.',
      '',
      '| Lane | Wall clock | Fixed tax | Framework overhead | Test CPU | Overhead % |',
      '|---|---:|---:|---:|---:|---:|',
    );
    for (const job of decomposed) {
      const c = job.components;
      const denom = c.frameworkOverheadSeconds + c.testCpuSeconds;
      const pct = denom > 0 ? Math.round((100 * c.frameworkOverheadSeconds) / denom) : 0;
      lines.push(`| ${job.name} | ${fmtSeconds(job.wallSeconds)} | ${fmtSeconds(c.fixedTaxSeconds)} | ${fmtSeconds(c.frameworkOverheadSeconds)} | ${fmtSeconds(c.testCpuSeconds)} | ${pct}% |`);
    }
    lines.push(
      '',
      '> Test CPU is summed suite time — when a module runs parallel forks it is CPU, not wall-clock, so the reported framework overhead is a conservative floor.',
      '',
    );
  }
  return `${lines.join('\n')}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

// Read every `unit-test-attribution.json` under `dir` (the downloaded
// unit-test-attribution-* artifacts, each in its own subdir) and build a
// { lane: summedSuiteSeconds } map from each report's `lane` + `totals.timeSeconds`.
// Fail-soft: a missing dir or unreadable/foreign file contributes nothing, so the
// lane simply gets no cost breakdown rather than failing the advisory job.
function readSuiteTimes(dir) {
  const out = {};
  if (!dir || !fs.existsSync(dir)) return out;
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const abs = path.join(current, entry.name);
      if (entry.isDirectory()) stack.push(abs);
      else if (entry.isFile() && entry.name === 'unit-test-attribution.json') {
        try {
          const data = JSON.parse(fs.readFileSync(abs, 'utf8'));
          if (data && typeof data.lane === 'string' && Number.isFinite(data.totals?.timeSeconds)) {
            out[data.lane] = data.totals.timeSeconds;
          }
        } catch {
          // Skip unreadable/foreign files.
        }
      }
    }
  }
  return out;
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const parsed = JSON.parse(fs.readFileSync(path.resolve(opts.jobsJson), 'utf8'));
    const jobs = Array.isArray(parsed) ? parsed : parsed.jobs || [];
    const runAttempt = opts.runId ? (jobs.find((j) => j.run_attempt)?.run_attempt ?? null) : jobs[0]?.run_attempt ?? null;
    const report = buildWalltimeReport({
      run: {
        runId: opts.runId,
        runAttempt,
        repository: opts.repository,
        workflow: opts.workflow,
      },
      jobs,
      selfJob: opts.selfJob,
      suiteTimes: readSuiteTimes(opts.unitAttributionDir && path.resolve(opts.unitAttributionDir)),
    });
    const json = `${JSON.stringify(report, null, 2)}\n`;
    const md = renderMarkdown(report);
    if (opts.outJson) writeText(path.resolve(opts.outJson), json);
    if (opts.outMd) writeText(path.resolve(opts.outMd), md);
    if (process.env.GITHUB_STEP_SUMMARY) {
      try {
        fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md, 'utf8');
      } catch {
        // Best effort only.
      }
    }
    if (opts.json) process.stdout.write(json);
    else if (opts.md || (!opts.outJson && !opts.outMd)) process.stdout.write(md);
  } catch (error) {
    console.error(`report-ci-walltime-attribution: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
