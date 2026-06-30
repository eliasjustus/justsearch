#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildReport, renderMarkdown } from './report-unit-test-attribution.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-unit-test-attribution.mjs');

function write(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function withTempRoot(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-unit-attribution-'));
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function suiteXml(attrs) {
  const pairs = Object.entries(attrs)
    .map(([key, value]) => `${key}="${String(value)}"`)
    .join(' ');
  return `<?xml version="1.0" encoding="UTF-8"?><testsuite ${pairs}><testcase classname="${attrs.name}" name="case"/></testsuite>\n`;
}

withTempRoot((root) => {
  write(
    path.join(root, 'modules/alpha/build/test-results/test/TEST-example.AlphaTest.xml'),
    suiteXml({ name: 'example.AlphaTest', tests: 4, skipped: 1, failures: 0, errors: 0, time: 2.5 }),
  );
  write(
    path.join(root, 'modules/beta/build/test-results/test/TEST-example.BetaTest.xml'),
    suiteXml({ name: 'example.BetaTest', tests: 2, skipped: 0, failures: 1, errors: 0, time: 5.25 }),
  );
  write(
    path.join(root, 'modules/alpha/build/test-results/test/TEST-example.FastTest.xml'),
    suiteXml({ name: 'example.FastTest', tests: 1, skipped: 0, failures: 0, errors: 0, time: 0.125 }),
  );

  const report = buildReport({
    root,
    lane: 'alpha-lane',
    top: 2,
    runner: {
      runnerLabel: 'windows-latest',
      runnerOs: 'Windows',
      runnerArch: 'X64',
      runnerName: 'GitHub Actions 1',
      imageOs: 'windows-2025-vs2026',
      imageVersion: '20260622.153.1',
    },
  });

  assert.equal(report.kind, 'justsearch-unit-test-attribution.v1');
  assert.equal(report.lane, 'alpha-lane');
  assert.deepEqual(report.totals, {
    suites: 3,
    tests: 7,
    skipped: 1,
    failures: 1,
    errors: 0,
    timeSeconds: 7.875,
  });
  assert.equal(report.modules[0].module, 'modules/beta');
  assert.equal(report.modules[1].module, 'modules/alpha');
  assert.equal(report.modules[1].tests, 5);
  assert.equal(report.slowestSuites[0].name, 'example.BetaTest');
  assert.equal(report.slowestSuites.length, 2);

  const md = renderMarkdown(report);
  assert.match(md, /windows-latest/);
  assert.match(md, /Lane: alpha-lane/);
  assert.match(md, /example\.BetaTest/);
});

withTempRoot((root) => {
  const outJson = path.join(root, 'out/report.json');
  const outMd = path.join(root, 'out/report.md');
  const summary = path.join(root, 'summary.md');
  write(
    path.join(root, 'modules/gamma/build/test-results/test/TEST-example.GammaTest.xml'),
    suiteXml({ name: 'example.GammaTest', tests: 3, skipped: 2, failures: 0, errors: 1, time: 1.75 }),
  );
  const res = spawnSync(process.execPath, [
    scriptPath,
    '--results-root', root,
    '--lane', 'gamma-lane',
    '--runner-label', 'windows-latest',
    '--out-json', outJson,
    '--out-md', outMd,
    '--json',
  ], {
    encoding: 'utf8',
    env: { ...process.env, GITHUB_STEP_SUMMARY: summary, RUNNER_OS: 'Windows', ImageVersion: 'v-test' },
  });
  assert.equal(res.status, 0, res.stderr);
  assert.equal(JSON.parse(fs.readFileSync(outJson, 'utf8')).totals.errors, 1);
  assert.match(fs.readFileSync(outMd, 'utf8'), /GammaTest/);
  assert.match(fs.readFileSync(summary, 'utf8'), /Unit test attribution/);
  assert.equal(JSON.parse(res.stdout).lane, 'gamma-lane');
  assert.equal(JSON.parse(res.stdout).runner.imageVersion, 'v-test');
});

withTempRoot((root) => {
  const fail = spawnSync(process.execPath, [scriptPath, '--results-root', root], { encoding: 'utf8' });
  assert.notEqual(fail.status, 0);
  assert.match(fail.stderr, /no JUnit XML files found/);

  const pass = spawnSync(process.execPath, [scriptPath, '--results-root', root, '--allow-empty', '--json'], { encoding: 'utf8' });
  assert.equal(pass.status, 0, pass.stderr);
  assert.equal(JSON.parse(pass.stdout).totals.suites, 0);
});

withTempRoot((root) => {
  write(
    path.join(root, 'modules/real/build/test-results/test/TEST-example.RealTest.xml'),
    suiteXml({ name: 'example.RealTest', tests: 1, skipped: 0, failures: 0, errors: 0, time: 1 }),
  );
  write(
    path.join(root, 'tmp/gha-unit-copy/modules/copied/build/test-results/test/TEST-example.CopiedTest.xml'),
    suiteXml({ name: 'example.CopiedTest', tests: 99, skipped: 0, failures: 0, errors: 0, time: 99 }),
  );
  const report = buildReport({ root, top: 5 });
  assert.equal(report.totals.tests, 1);
  assert.equal(report.modules[0].module, 'modules/real');
});

console.log('test-report-unit-test-attribution: PASS');
