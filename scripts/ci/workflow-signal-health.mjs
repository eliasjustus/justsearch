#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RUN_FIELDS = [
  'conclusion',
  'createdAt',
  'databaseId',
  'displayTitle',
  'event',
  'headBranch',
  'headSha',
  'name',
  'number',
  'startedAt',
  'status',
  'updatedAt',
  'url',
  'workflowName',
].join(',');

const JOB_FIELDS = 'jobs';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function parseArgs(argv) {
  const out = {
    repo: null,
    limit: 100,
    days: null,
    json: false,
    md: false,
    runsJson: null,
    jobsJsonDir: null,
    policy: null,
    now: null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--repo' && argv[i + 1]) out.repo = argv[++i];
    else if (arg === '--limit' && argv[i + 1]) out.limit = Number.parseInt(argv[++i], 10);
    else if ((arg === '--days' || arg === '--lookback-days') && argv[i + 1]) {
      out.days = Number.parseInt(argv[++i], 10);
    } else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--runs-json' && argv[i + 1]) out.runsJson = argv[++i];
    else if (arg === '--jobs-json-dir' && argv[i + 1]) out.jobsJsonDir = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--now' && argv[i + 1]) out.now = argv[++i];
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  return out;
}

function usage() {
  return [
    'Usage: node scripts/ci/workflow-signal-health.mjs [--repo owner/repo] [--limit 100] [--days N] [--json|--md]',
    '',
    'Summarizes the latest GitHub Actions run per workflow and classifies each signal using',
    'scripts/ci/workflow-signal-policy.v1.json. Uses gh run list/view as the v1 data source.',
  ].join('\n');
}

function loadJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function execGh(args) {
  return execFileSync('gh', args, {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

function detectRepoSlug() {
  try {
    const url = execFileSync('git', ['remote', 'get-url', 'origin'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      windowsHide: true,
    }).trim();
    const ssh = url.match(/github\.com[:/](.+?)\/(.+?)(?:\.git)?$/i);
    if (ssh) return `${ssh[1]}/${ssh[2]}`;
  } catch {
    // Fall through to repository default.
  }
  return 'eliasjustus/justsearch';
}

function workflowName(run) {
  return run.workflowName || run.name || run.displayTitle || 'unknown';
}

function runTime(run) {
  return run.updatedAt || run.createdAt || run.startedAt || null;
}

function loadRuns(opts) {
  if (opts.runsJson) return loadJson(opts.runsJson);
  const args = ['run', 'list', '--limit', String(opts.limit), '--json', RUN_FIELDS];
  if (opts.repo) args.push('--repo', opts.repo);
  return JSON.parse(execGh(args));
}

function loadJobsForRun(run, opts) {
  if (opts.jobsJsonDir) {
    const filePath = path.join(opts.jobsJsonDir, `${run.databaseId}.json`);
    if (!fs.existsSync(filePath)) return null;
    return loadJson(filePath);
  }
  if (!run.databaseId) return null;
  const args = ['run', 'view', String(run.databaseId), '--json', JOB_FIELDS];
  if (opts.repo) args.push('--repo', opts.repo);
  try {
    return JSON.parse(execGh(args));
  } catch {
    return null;
  }
}

function failedStepNames(jobsDoc) {
  const jobs = Array.isArray(jobsDoc?.jobs) ? jobsDoc.jobs : [];
  const names = [];
  for (const job of jobs) {
    for (const step of job.steps || []) {
      const conclusion = String(step.conclusion || '').toLowerCase();
      const status = String(step.status || '').toLowerCase();
      if (conclusion === 'failure' || conclusion === 'cancelled' || status === 'failure') {
        names.push(step.name || step.number || 'unknown step');
      }
    }
  }
  return [...new Set(names.map(String))];
}

function containsAny(haystack, needles) {
  const text = haystack.join(' | ').toLowerCase();
  return needles.some((needle) => text.includes(needle.toLowerCase()));
}

function classifyFailure(policy, run, steps, now) {
  if (!run) return 'stale-or-zombie';
  if (run.status && run.status !== 'completed') return 'in-progress';
  if (run.conclusion === 'success') return 'passed';

  const name = policy.name.toLowerCase();
  if (policy.class === 'release-gate') return 'release-blocking-failure';

  if (name === 'ci') {
    if (containsAny(steps, ['stress suite coverage gate'])) return 'workflow-assumption-drift';
    if (containsAny(steps, ['run stress tests'])) return 'product-regression';
  }

  if (containsAny(steps, ['checkout', 'setup', 'install', 'npm ci', 'pip install', 'gradle wrapper'])) {
    return 'infra-drift';
  }

  if (name.includes('phase 3 observability')) {
    if (containsAny(steps, ['jseval gate', 'gate observability drift'])) return 'product-regression';
    return 'workflow-assumption-drift';
  }

  if (name.includes('agent live eval')) {
    if (containsAny(steps, ['run live battery'])) return 'product-regression';
    return 'workflow-assumption-drift';
  }

  if (policy.class === 'scheduled-governance-signal') {
    return policy.failureDefault || 'expected-advisory-failure';
  }

  if (isStale(run, policy, now)) return 'stale-or-zombie';
  return policy.failureDefault || 'product-regression';
}

function isStale(run, policy, now) {
  if (!run) return true;
  const t = runTime(run);
  if (!t) return true;
  const staleDays = policy.staleDays ?? 14;
  const ageMs = now.getTime() - new Date(t).getTime();
  return ageMs > staleDays * 24 * 60 * 60 * 1000;
}

function ageDays(run, now) {
  const t = runTime(run);
  if (!t) return null;
  return Math.max(0, Math.round((now.getTime() - new Date(t).getTime()) / (24 * 60 * 60 * 1000)));
}

function latestRunsByWorkflow(runs, days, now) {
  const cutoff = days == null ? null : now.getTime() - days * 24 * 60 * 60 * 1000;
  const latest = new Map();
  for (const run of runs) {
    const t = runTime(run);
    if (cutoff != null && t && new Date(t).getTime() < cutoff) continue;
    const key = workflowName(run);
    const prev = latest.get(key);
    if (!prev || new Date(runTime(run) || 0) > new Date(runTime(prev) || 0)) latest.set(key, run);
  }
  return latest;
}

function lastSuccessByWorkflow(runs) {
  const latest = new Map();
  for (const run of runs.filter((r) => r.conclusion === 'success')) {
    const key = workflowName(run);
    const prev = latest.get(key);
    if (!prev || new Date(runTime(run) || 0) > new Date(runTime(prev) || 0)) latest.set(key, run);
  }
  return latest;
}

export function buildHealthReport({ policy, runs, jobsByRunId = new Map(), now = new Date(), days = null }) {
  const latest = latestRunsByWorkflow(runs, days, now);
  const lastSuccess = lastSuccessByWorkflow(runs);
  const workflows = policy.workflows.map((entry) => {
    const run = latest.get(entry.name) || null;
    const successRun = lastSuccess.get(entry.name) || null;
    const jobsDoc = run ? jobsByRunId.get(String(run.databaseId)) : null;
    const steps = failedStepNames(jobsDoc);
    const stale = isStale(run, entry, now);
    return {
      name: entry.name,
      path: entry.path,
      class: entry.class,
      owner: entry.owner,
      blocking: Boolean(entry.blocking),
      advisory: Boolean(entry.advisory),
      latestRun: run
        ? {
            databaseId: run.databaseId,
            conclusion: run.conclusion || null,
            status: run.status || null,
            event: run.event || null,
            branch: run.headBranch || null,
            sha: run.headSha || null,
            title: run.displayTitle || null,
            updatedAt: runTime(run),
            url: run.url || null,
            ageDays: ageDays(run, now),
          }
        : null,
      lastSuccess: successRun
        ? {
            databaseId: successRun.databaseId,
            updatedAt: runTime(successRun),
            url: successRun.url || null,
            ageDays: ageDays(successRun, now),
          }
        : null,
      stale,
      failedSteps: steps,
      failureClass: run && run.conclusion === 'success' && !stale
        ? 'passed'
        : stale && (!run || run.conclusion === 'success')
          ? 'stale-or-zombie'
          : classifyFailure(entry, run, steps, now),
    };
  });
  return {
    kind: 'justsearch-workflow-signal-health.v1',
    generatedAt: now.toISOString(),
    lookbackDays: days,
    workflowCount: workflows.length,
    workflows,
  };
}

export function renderMarkdown(report) {
  const lines = [
    '# Workflow Signal Health',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    '| Workflow | Class | Latest | Last Success | Failure Class | Owner | Failed Steps |',
    '|---|---|---|---|---|---|---|',
  ];
  for (const wf of report.workflows) {
    const latest = wf.latestRun
      ? `${wf.latestRun.conclusion || wf.latestRun.status || 'unknown'} (${wf.latestRun.event || 'unknown'}, ${wf.latestRun.ageDays}d)`
      : 'no run';
    const success = wf.lastSuccess ? `${wf.lastSuccess.ageDays}d` : 'none';
    const steps = wf.failedSteps.length > 0 ? wf.failedSteps.join('<br>') : '';
    lines.push(`| ${wf.name} | ${wf.class} | ${latest} | ${success} | ${wf.failureClass} | ${wf.owner} | ${steps} |`);
  }
  lines.push('');
  return `${lines.join('\n')}\n`;
}

function main() {
  const repoRoot = repoRootFromCwd();
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(usage());
    return;
  }
  opts.repo ??= detectRepoSlug();
  opts.policy ??= path.join(repoRoot, 'scripts', 'ci', 'workflow-signal-policy.v1.json');
  const now = opts.now ? new Date(opts.now) : new Date();
  const policy = loadJson(opts.policy);
  const runs = loadRuns(opts);
  const latest = latestRunsByWorkflow(runs, opts.days, now);
  const jobsByRunId = new Map();
  for (const run of latest.values()) {
    if (run.conclusion && run.conclusion !== 'success') {
      const jobsDoc = loadJobsForRun(run, opts);
      if (jobsDoc) jobsByRunId.set(String(run.databaseId), jobsDoc);
    }
  }
  const report = buildHealthReport({ policy, runs, jobsByRunId, now, days: opts.days });
  if (opts.json) console.log(JSON.stringify(report, null, 2));
  else console.log(renderMarkdown(report));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
