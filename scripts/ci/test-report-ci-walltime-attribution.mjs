#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildWalltimeReport, renderMarkdown } from './report-ci-walltime-attribution.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-ci-walltime-attribution.mjs');

// Compact fixture matching the real `gh api .../runs/<id>/jobs` shape
// (name/status/conclusion/started_at/completed_at/run_attempt + steps[]).
function job(name, startedAt, completedAt, steps = [], overrides = {}) {
  return {
    name,
    status: 'completed',
    conclusion: 'success',
    run_attempt: 1,
    started_at: startedAt,
    completed_at: completedAt,
    steps,
    ...overrides,
  };
}

function step(name, startedAt, completedAt) {
  return { name, status: 'completed', conclusion: 'success', number: 1, started_at: startedAt, completed_at: completedAt };
}

function sampleJobs() {
  return [
    // 600s wall: 15s checkout (fixed) + 585s work
    job('Unit tests (app-ui)', '2026-07-01T00:00:00Z', '2026-07-01T00:10:00Z', [
      step('Set up job', '2026-07-01T00:00:00Z', '2026-07-01T00:00:05Z'),
      step('Checkout', '2026-07-01T00:00:05Z', '2026-07-01T00:00:15Z'),
      step('Unit tests', '2026-07-01T00:00:15Z', '2026-07-01T00:10:00Z'),
    ]),
    // 300s wall
    job('License and notices', '2026-07-01T00:00:00Z', '2026-07-01T00:05:00Z', [
      step('Checkout', '2026-07-01T00:00:00Z', '2026-07-01T00:00:10Z'),
      step('Enforce the license allowlist', '2026-07-01T00:00:10Z', '2026-07-01T00:05:00Z'),
    ]),
    // in-progress (the attribution job itself) — no completed_at → excluded
    job('CI wall-clock attribution', '2026-07-01T00:10:00Z', null, [], { status: 'in_progress', conclusion: null }),
  ];
}

// buildWalltimeReport: critical path, fixed-tax split, in-progress exclusion.
{
  const report = buildWalltimeReport({
    run: { runId: '123', runAttempt: 1, repository: 'o/r', workflow: 'CI' },
    jobs: sampleJobs(),
    selfJob: 'CI wall-clock attribution',
  });
  assert.equal(report.kind, 'justsearch-ci-walltime-attribution.v1');
  assert.equal(report.totals.jobs, 2, 'in-progress + self job excluded');
  assert.equal(report.criticalPath.job, 'Unit tests (app-ui)');
  assert.equal(report.criticalPath.wallSeconds, 600);
  assert.equal(report.totals.criticalPathSeconds, 600);
  const appui = report.jobs.find((j) => j.name === 'Unit tests (app-ui)');
  assert.equal(appui.wallSeconds, 600);
  assert.equal(appui.fixedTaxSeconds, 15, 'set up job + checkout are fixed tax');
  assert.equal(appui.workSeconds, 585);
  assert.equal(report.rerunCaveat, false);
  assert.match(renderMarkdown(report), /Critical path: \*\*Unit tests \(app-ui\)\*\*/);
}

// Rerun caveat surfaces when run_attempt > 1.
{
  const report = buildWalltimeReport({ run: { runId: '9', runAttempt: 2 }, jobs: sampleJobs(), selfJob: null });
  assert.equal(report.rerunCaveat, true);
  assert.match(renderMarkdown(report), /Rerun \(attempt > 1\)/);
}

// Empty / all-in-progress input yields a null critical path, no throw.
{
  const report = buildWalltimeReport({ run: {}, jobs: [job('x', '2026-07-01T00:00:00Z', null)] });
  assert.equal(report.criticalPath, null);
  assert.equal(report.totals.jobs, 0);
  assert.match(renderMarkdown(report), /Critical path: none/);
}

// CLI path: reads --jobs-json {jobs:[...]}, writes JSON + MD.
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-ci-walltime-'));
  try {
    const jobsPath = path.join(root, 'run-jobs.json');
    const outJson = path.join(root, 'out.json');
    const outMd = path.join(root, 'out.md');
    fs.writeFileSync(jobsPath, JSON.stringify({ jobs: sampleJobs() }), 'utf8');
    const res = spawnSync(process.execPath, [
      scriptPath,
      '--jobs-json', jobsPath,
      '--run-id', '123',
      '--repository', 'o/r',
      '--workflow', 'CI',
      '--self-job', 'CI wall-clock attribution',
      '--out-json', outJson,
      '--out-md', outMd,
      '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(JSON.parse(res.stdout).criticalPath.job, 'Unit tests (app-ui)');
    assert.equal(JSON.parse(fs.readFileSync(outJson, 'utf8')).totals.jobs, 2);
    assert.match(fs.readFileSync(outMd, 'utf8'), /CI wall-clock attribution/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

console.log('test-report-ci-walltime-attribution: PASS');
