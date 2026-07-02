#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildWalltimeBudgetReport, renderMarkdown } from './report-ci-walltime-budget.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-ci-walltime-budget.mjs');

function policy(overrides = {}) {
  return {
    kind: 'justsearch-ci-walltime-policy.v1',
    version: 1,
    budgetsAreAdvisory: true,
    lanes: [
      { job: 'Unit tests (app-ui)', requiredCheck: 'Unit tests (app-ui)', owner: 'app', advisoryBudgets: { maxWallSeconds: 750 } },
      { job: 'License and notices', requiredCheck: 'License and notices', owner: 'license', advisoryBudgets: { maxWallSeconds: 405 } },
    ],
    ...overrides,
  };
}

function attribution(jobs) {
  return {
    kind: 'justsearch-ci-walltime-attribution.v1',
    generatedAt: '2026-07-01T00:00:00.000Z',
    run: { runId: '1', repository: 'o/r' },
    criticalPath: jobs[0] ? { job: jobs[0].name, wallSeconds: jobs[0].wallSeconds } : null,
    totals: { jobs: jobs.length },
    jobs,
  };
}

// Within budget → no warnings.
{
  const report = buildWalltimeBudgetReport({
    attribution: attribution([
      { name: 'Unit tests (app-ui)', wallSeconds: 600 },
      { name: 'License and notices', wallSeconds: 300 },
    ]),
    policy: policy(),
  });
  assert.equal(report.kind, 'justsearch-ci-walltime-budget.v1');
  assert.equal(report.advisory, true);
  assert.equal(report.warningCount, 0);
  assert.match(renderMarkdown(report), /No advisory budget warnings/);
}

// Over budget → walltime-over-budget warning + ⚠️ marker in MD.
{
  const report = buildWalltimeBudgetReport({
    attribution: attribution([{ name: 'Unit tests (app-ui)', wallSeconds: 800 }]),
    policy: policy(),
  });
  assert.deepEqual(report.warnings.map((w) => w.code), ['walltime-over-budget']);
  assert.equal(report.warnings[0].actualSeconds, 800);
  assert.equal(report.warnings[0].budgetSeconds, 750);
  assert.match(renderMarkdown(report), /⚠️/);
}

// A job with no policy entry is reported but never warns (ceiling n/a).
{
  const report = buildWalltimeBudgetReport({
    attribution: attribution([{ name: 'Secret scan', wallSeconds: 9 }]),
    policy: policy(),
  });
  assert.equal(report.warningCount, 0);
  assert.equal(report.lanes[0].ceilingSeconds, null);
  assert.equal(report.lanes[0].overBudget, false);
}

// Empty attribution → empty-attribution warning.
{
  const report = buildWalltimeBudgetReport({ attribution: attribution([]), policy: policy() });
  assert.equal(report.warnings[0].code, 'empty-attribution');
}

// CLI path.
{
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-ci-walltime-budget-'));
  try {
    fs.writeFileSync(path.join(root, 'settings.gradle.kts'), 'rootProject.name = "test"\n', 'utf8');
    const policyPath = path.join(root, 'scripts/ci/ci-walltime-policy.v1.json');
    const attrPath = path.join(root, 'build/ci/ci-walltime-attribution.json');
    const outJson = path.join(root, 'out/budget.json');
    fs.mkdirSync(path.dirname(policyPath), { recursive: true });
    fs.mkdirSync(path.dirname(attrPath), { recursive: true });
    fs.writeFileSync(policyPath, `${JSON.stringify(policy(), null, 2)}\n`, 'utf8');
    fs.writeFileSync(attrPath, `${JSON.stringify(attribution([{ name: 'Unit tests (app-ui)', wallSeconds: 900 }]), null, 2)}\n`, 'utf8');
    const res = spawnSync(process.execPath, [
      scriptPath,
      '--attribution', attrPath,
      '--policy', policyPath,
      '--out-json', outJson,
      '--json',
    ], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(JSON.parse(res.stdout).warningCount, 1);
    assert.equal(JSON.parse(fs.readFileSync(outJson, 'utf8')).warnings[0].code, 'walltime-over-budget');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

console.log('test-report-ci-walltime-budget: PASS');
