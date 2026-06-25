#!/usr/bin/env node
/**
 * Tests for scripts/ci/check-npm-audit-ratchet.mjs
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir =
  process.platform === 'win32' ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..');
const gateScript = path.join(scriptDir, 'check-npm-audit-ratchet.mjs');

function runNode(args) {
  return spawnSync('node', args, {
    cwd: repoRoot,
    encoding: 'utf8',
    timeout: 20000,
  });
}

function writeJson(filePath, payload) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function makeAuditReport({ rootHigh = 10, uiHigh = 9, toolsHigh = 0, rootCritical = 0, uiCritical = 0, toolsCritical = 0 }) {
  return {
    schema: 'npm-audit-report.v1',
    generated_at: '2026-02-20T00:00:00.000Z',
    policy: 'warn-only',
    targets: [
      {
        target_id: 'root',
        vulnerabilities: {
          info: 0,
          low: 2,
          moderate: 1,
          high: rootHigh,
          critical: rootCritical,
          total: rootHigh + rootCritical + 3,
        },
      },
      {
        target_id: 'ui-web',
        vulnerabilities: {
          info: 0,
          low: 0,
          moderate: 1,
          high: uiHigh,
          critical: uiCritical,
          total: uiHigh + uiCritical + 1,
        },
      },
      {
        target_id: 'ssot-tools',
        vulnerabilities: {
          info: 0,
          low: 0,
          moderate: 0,
          high: toolsHigh,
          critical: toolsCritical,
          total: toolsHigh + toolsCritical,
        },
      },
    ],
  };
}

function makeBaseline() {
  return {
    schema: 'npm-audit-ratchet-baseline.v1',
    generated_at: '2026-02-20T00:00:00.000Z',
    source_report: 'tmp/npm-audit-report.json',
    targets: {
      root: { info: 0, low: 2, moderate: 1, high: 10, critical: 0, total: 13 },
      'ui-web': { info: 0, low: 0, moderate: 1, high: 9, critical: 0, total: 10 },
      'ssot-tools': { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 },
    },
    aggregate: { info: 0, low: 2, moderate: 2, high: 19, critical: 0, total: 23 },
  };
}

function testNoIncreaseGatePass(tempRoot) {
  const reportPath = path.join(tempRoot, 'pass', 'audit-report.json');
  const baselinePath = path.join(tempRoot, 'pass', 'baseline.json');
  const outPath = path.join(tempRoot, 'pass', 'ratchet-report.json');
  writeJson(reportPath, makeAuditReport({}));
  writeJson(baselinePath, makeBaseline());

  const result = runNode([
    gateScript,
    '--report',
    reportPath,
    '--baseline',
    baselinePath,
    '--severities',
    'high,critical',
    '--mode',
    'gate',
    '--out',
    outPath,
  ]);
  assert.equal(result.status, 0, `ratchet pass should succeed: ${result.stderr}`);
  const report = readJson(outPath);
  assert.equal(report.schema, 'npm-audit-ratchet-report.v1');
  assert.equal(report.verdict, 'pass');
}

function testIncreaseGateFail(tempRoot) {
  const reportPath = path.join(tempRoot, 'fail-gate', 'audit-report.json');
  const baselinePath = path.join(tempRoot, 'fail-gate', 'baseline.json');
  const outPath = path.join(tempRoot, 'fail-gate', 'ratchet-report.json');
  writeJson(reportPath, makeAuditReport({ rootHigh: 12 }));
  writeJson(baselinePath, makeBaseline());

  const result = runNode([
    gateScript,
    '--report',
    reportPath,
    '--baseline',
    baselinePath,
    '--severities',
    'high,critical',
    '--mode',
    'gate',
    '--out',
    outPath,
  ]);
  assert.equal(result.status, 1, 'high severity increase should fail gate mode');
  const report = readJson(outPath);
  assert.equal(report.schema, 'npm-audit-ratchet-report.v1');
  assert.equal(report.verdict, 'fail');
  assert.ok(report.violations.some((v) => v.severity === 'high'));
}

function testIncreaseWarnPass(tempRoot) {
  const reportPath = path.join(tempRoot, 'fail-warn', 'audit-report.json');
  const baselinePath = path.join(tempRoot, 'fail-warn', 'baseline.json');
  const outPath = path.join(tempRoot, 'fail-warn', 'ratchet-report.json');
  writeJson(reportPath, makeAuditReport({ uiHigh: 11 }));
  writeJson(baselinePath, makeBaseline());

  const result = runNode([
    gateScript,
    '--report',
    reportPath,
    '--baseline',
    baselinePath,
    '--severities',
    'high,critical',
    '--mode',
    'warn',
    '--out',
    outPath,
  ]);
  assert.equal(result.status, 0, 'warn mode should not fail on increases');
  const report = readJson(outPath);
  assert.equal(report.schema, 'npm-audit-ratchet-report.v1');
  assert.equal(report.verdict, 'fail');
}

function testWriteBaseline(tempRoot) {
  const reportPath = path.join(tempRoot, 'write-baseline', 'audit-report.json');
  const baselinePath = path.join(tempRoot, 'write-baseline', 'baseline.json');
  writeJson(reportPath, makeAuditReport({ rootHigh: 7, uiHigh: 3, toolsHigh: 2 }));

  const result = runNode([
    gateScript,
    '--report',
    reportPath,
    '--baseline',
    baselinePath,
    '--write-baseline',
    '--mode',
    'warn',
  ]);
  assert.equal(result.status, 0, `write-baseline should pass: ${result.stderr}`);
  const baseline = readJson(baselinePath);
  assert.equal(baseline.schema, 'npm-audit-ratchet-baseline.v1');
  assert.equal(baseline.targets.root.high, 7);
  assert.equal(baseline.targets['ui-web'].high, 3);
  assert.equal(baseline.targets['ssot-tools'].high, 2);
}

function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-npm-audit-ratchet-'));
  try {
    testNoIncreaseGatePass(tempRoot);
    testIncreaseGateFail(tempRoot);
    testIncreaseWarnPass(tempRoot);
    testWriteBaseline(tempRoot);
    // eslint-disable-next-line no-console
    console.log('check-npm-audit-ratchet tests: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main();

