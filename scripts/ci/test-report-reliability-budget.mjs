#!/usr/bin/env node
/**
 * Tests for scripts/ci/report-reliability-budget.mjs
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir =
  process.platform === 'win32' ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR;
const reportScript = path.join(scriptDir, 'report-reliability-budget.mjs');

function runNode(args) {
  return spawnSync('node', args, {
    encoding: 'utf8',
    timeout: 10000,
  });
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function runMainScenario(rootDir) {
  const summariesDir = path.join(rootDir, 'summaries');
  const logsDir = path.join(rootDir, 'logs');
  const outJson = path.join(rootDir, 'out', 'latest.json');
  const outMd = path.join(rootDir, 'out', 'latest.md');

  fs.mkdirSync(summariesDir, { recursive: true });
  fs.mkdirSync(logsDir, { recursive: true });

  const run0Stdout = path.join(logsDir, 'run0.stdout.log');
  const run0Stderr = path.join(logsDir, 'run0.stderr.log');
  const run1Stdout = path.join(logsDir, 'run1.stdout.log');
  const run1Stderr = path.join(logsDir, 'run1.stderr.log');
  const run2Stdout = path.join(logsDir, 'run2.stdout.log');
  const run2Stderr = path.join(logsDir, 'run2.stderr.log');

  fs.writeFileSync(run0Stdout, 'AlphaTest > flakeCase FAILED\n', 'utf8');
  fs.writeFileSync(run0Stderr, '', 'utf8');
  fs.writeFileSync(run1Stdout, '', 'utf8');
  fs.writeFileSync(run1Stderr, '', 'utf8');
  fs.writeFileSync(
    run2Stdout,
    'AlphaTest > flakeCase FAILED\nBetaTest FAILED\n\u001b[31mGammaTest > redCase FAILED\u001b[0m\n',
    'utf8',
  );
  fs.writeFileSync(run2Stderr, '', 'utf8');

  const baseSummary = {
    schema: 'local-agent-gate.v1',
    gradle_gate: { ran: true, exit_code: 0, stdout: '', stderr: '' },
    playwright: { ran: true, exit_code: 0 },
    ui_unit: { ran: true, exit_code: 0 },
    cargo_check: { ran: false, exit_code: null },
    cargo_audit: { ran: false, exit_code: null },
    lock_skew: { ran: true, enforce: true, exit_code: 0 },
  };

  writeJson(path.join(summariesDir, 'local-agent-gate-20260209-010000.json'), {
    ...baseSummary,
    started_at: '2026-02-09T01:00:00Z',
    gradle_gate: { ran: true, exit_code: 1, stdout: run0Stdout, stderr: run0Stderr },
    lock_skew: { ran: true, enforce: true, exit_code: 1 },
  });
  writeJson(path.join(summariesDir, 'local-agent-gate-20260209-005000.json'), {
    ...baseSummary,
    started_at: '2026-02-09T00:50:00Z',
    gradle_gate: { ran: true, exit_code: 0, stdout: run1Stdout, stderr: run1Stderr },
    lock_skew: { ran: true, enforce: true, exit_code: 0 },
  });
  writeJson(path.join(summariesDir, 'local-agent-gate-20260209-004000.json'), {
    ...baseSummary,
    started_at: '2026-02-09T00:40:00Z',
    gradle_gate: { ran: true, exit_code: 1, stdout: run2Stdout, stderr: run2Stderr },
    lock_skew: { ran: true, enforce: false, exit_code: 1 },
  });
  writeJson(path.join(summariesDir, 'local-agent-gate-20260209-003000.json'), {
    ...baseSummary,
    started_at: '2026-02-09T00:30:00Z',
    gradle_gate: { ran: false, exit_code: null, stdout: '', stderr: '' },
    lock_skew: { ran: false, enforce: true, exit_code: null },
  });

  const result = runNode([
    reportScript,
    '--summaries-dir',
    summariesDir,
    '--lookback-runs',
    '3',
    '--out-json',
    outJson,
    '--out-md',
    outMd,
  ]);
  assert.equal(result.status, 0, `main scenario failed: ${result.stderr}`);
  assert.equal(fs.existsSync(outJson), true, 'JSON report should exist');
  assert.equal(fs.existsSync(outMd), true, 'Markdown report should exist');

  const report = JSON.parse(fs.readFileSync(outJson, 'utf8'));
  assert.equal(report.schema, 'reliability-budget.v1');
  assert.equal(report.run_window.runs_considered, 3);

  const gradleLane = report.lane_failures.find((lane) => lane.name === 'gradle_gate');
  assert.ok(gradleLane, 'gradle lane should exist');
  assert.equal(gradleLane.failures, 2);
  assert.equal(gradleLane.ran_count, 3);
  assert.equal(gradleLane.fail_rate, 0.667);

  const lockSkewLane = report.lane_failures.find((lane) => lane.name === 'lock_skew');
  assert.ok(lockSkewLane, 'lock_skew lane should exist');
  assert.equal(lockSkewLane.failures, 1, 'lock_skew should only fail when enforce=true');

  const alpha = report.failed_tests.find((row) => row.name === 'AlphaTest#flakeCase');
  assert.ok(alpha, 'AlphaTest#flakeCase should be extracted');
  assert.equal(alpha.failures, 2);
  assert.equal(alpha.flaky_candidate, true, 'non-consecutive failures should flag flaky candidate');

  const beta = report.failed_tests.find((row) => row.name === 'BetaTest#<class-level-failure>');
  assert.ok(beta, 'Class-level fallback failure should be extracted');

  const gamma = report.failed_tests.find((row) => row.name === 'GammaTest#redCase');
  assert.ok(gamma, 'ANSI-colored method failure should be extracted');
}

function runCorruptScenario(rootDir) {
  const summariesDir = path.join(rootDir, 'summaries-corrupt');
  const outJson = path.join(rootDir, 'out-corrupt', 'latest.json');
  const outMd = path.join(rootDir, 'out-corrupt', 'latest.md');

  fs.mkdirSync(summariesDir, { recursive: true });
  fs.writeFileSync(path.join(summariesDir, 'local-agent-gate-20260209-020000.json'), '{bad-json', 'utf8');
  writeJson(path.join(summariesDir, 'local-agent-gate-20260209-021000.json'), {
    schema: 'local-agent-gate.v1',
    started_at: '2026-02-09T02:10:00Z',
    dev_runner: { started: true, run_id: null },
    gradle_gate: { ran: false, exit_code: null, stdout: '', stderr: '' },
    playwright: { ran: false, exit_code: null },
    ui_unit: { ran: false, exit_code: null },
    cargo_check: { ran: false, exit_code: null },
    cargo_audit: { ran: false, exit_code: null },
    lock_skew: { ran: false, enforce: true, exit_code: null },
  });

  const result = runNode([
    reportScript,
    '--summaries-dir',
    summariesDir,
    '--out-json',
    outJson,
    '--out-md',
    outMd,
  ]);
  assert.equal(result.status, 0, `corrupt scenario failed: ${result.stderr}`);
  const report = JSON.parse(fs.readFileSync(outJson, 'utf8'));
  assert.ok(report.warnings.length > 0, 'corrupt scenario should record warnings');
  assert.equal(report.run_window.early_abort_runs, 1, 'early abort run should be counted');
  assert.ok(
    report.warnings.some((w) => w.includes('early gate-abort run(s)')),
    'early abort warning should be present',
  );
}

function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-reliability-budget-'));
  try {
    runMainScenario(tempRoot);
    runCorruptScenario(tempRoot);
    // eslint-disable-next-line no-console
    console.log('report-reliability-budget tests: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main();
