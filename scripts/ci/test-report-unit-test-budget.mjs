#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildBudgetReport, renderMarkdown } from './report-unit-test-budget.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-unit-test-budget.mjs');

function lanePolicy(overrides = {}) {
  return {
    kind: 'justsearch-unit-test-shard-policy.v1',
    version: 1,
    budgetsAreAdvisory: true,
    lanes: [
      {
        lane: 'alpha',
        requiredCheck: 'Unit tests (alpha)',
        artifact: 'unit-test-attribution-alpha',
        runnerLabel: 'windows-latest/alpha',
        owner: 'alpha owner',
        platformClass: 'mixed/needs targeted experiment',
        platformRiskNotes: ['risk'],
        gradleTasks: [':modules:alpha:test'],
        localCommand: './gradlew.bat :modules:alpha:test -PskipWebBuild=true --console=plain',
        advisoryBudgets: {
          maxSummedSuiteSeconds: 10,
          slowSuiteWarnSeconds: 5,
          maxSkipped: 2,
        },
        ...overrides,
      },
    ],
  };
}

function attribution(overrides = {}) {
  return {
    kind: 'justsearch-unit-test-attribution.v1',
    generatedAt: '2026-06-29T00:00:00.000Z',
    lane: 'alpha',
    runner: {
      runnerLabel: 'windows-latest/alpha',
      runnerOs: 'Windows',
    },
    totals: {
      suites: 2,
      tests: 7,
      skipped: 1,
      failures: 0,
      errors: 0,
      timeSeconds: 7,
    },
    modules: [],
    slowestSuites: [
      {
        name: 'example.SlowTest',
        module: 'modules/alpha',
        tests: 1,
        skipped: 0,
        failures: 0,
        errors: 0,
        timeSeconds: 4,
      },
    ],
    ...overrides,
  };
}

function withTempRoot(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-unit-budget-'));
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

{
  const report = buildBudgetReport({ lane: 'alpha', attribution: attribution(), policy: lanePolicy() });
  assert.equal(report.kind, 'justsearch-unit-test-budget.v1');
  assert.equal(report.warningCount, 0);
  assert.match(renderMarkdown(report), /No advisory budget warnings/);
}

{
  const report = buildBudgetReport({
    lane: 'alpha',
    attribution: attribution({
      totals: { suites: 2, tests: 7, skipped: 3, failures: 0, errors: 0, timeSeconds: 11 },
      slowestSuites: [{ name: 'example.TooSlowTest', module: 'modules/alpha', timeSeconds: 6 }],
    }),
    policy: lanePolicy(),
  });
  assert.deepEqual(report.warnings.map((warning) => warning.code), [
    'summed-suite-time-over-budget',
    'skipped-tests-over-budget',
    'slow-suite-over-budget',
  ]);
}

{
  const report = buildBudgetReport({
    lane: 'alpha',
    attribution: attribution({ lane: 'beta', runner: { runnerLabel: 'windows-latest/beta' } }),
    policy: lanePolicy(),
  });
  assert.deepEqual(report.warnings.map((warning) => warning.code), [
    'lane-mismatch',
    'runner-label-mismatch',
  ]);
}

{
  const report = buildBudgetReport({
    lane: 'alpha',
    attribution: attribution({ totals: { suites: 0, tests: 0, skipped: 0, failures: 0, errors: 0, timeSeconds: 0 } }),
    policy: lanePolicy(),
  });
  assert.equal(report.warnings[0].code, 'empty-attribution');
}

assert.throws(
  () => buildBudgetReport({ lane: 'missing', attribution: attribution(), policy: lanePolicy() }),
  /lane not found/,
);

withTempRoot((root) => {
  fs.writeFileSync(path.join(root, 'settings.gradle.kts'), 'rootProject.name = "test"\n', 'utf8');
  const policyPath = path.join(root, 'scripts/ci/unit-test-shard-policy.v1.json');
  const attributionPath = path.join(root, 'build/ci/unit-test-attribution.json');
  const outJson = path.join(root, 'out/unit-test-budget.json');
  const outMd = path.join(root, 'out/unit-test-budget.md');
  writeJson(policyPath, lanePolicy());
  writeJson(attributionPath, attribution());

  const res = spawnSync(process.execPath, [
    scriptPath,
    '--lane', 'alpha',
    '--policy', policyPath,
    '--attribution', attributionPath,
    '--out-json', outJson,
    '--out-md', outMd,
    '--json',
  ], { encoding: 'utf8' });

  assert.equal(res.status, 0, res.stderr);
  assert.equal(JSON.parse(res.stdout).warningCount, 0);
  assert.equal(JSON.parse(fs.readFileSync(outJson, 'utf8')).lane, 'alpha');
  assert.match(fs.readFileSync(outMd, 'utf8'), /Unit test budget/);
});

console.log('test-report-unit-test-budget: PASS');
