#!/usr/bin/env node
/**
 * Tests for scripts/ci/check-ui-cycles.mjs
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
const gateScript = path.join(scriptDir, 'check-ui-cycles.mjs');
const fixturesRoot = path.join(scriptDir, 'fixtures');

function runNode(args) {
  return spawnSync('node', args, {
    cwd: repoRoot,
    encoding: 'utf8',
    timeout: 20000,
  });
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function testAcyclicGate(tempRoot) {
  const outJson = path.join(tempRoot, 'acyclic-gate.json');
  const result = runNode([
    gateScript,
    '--root',
    path.join(fixturesRoot, 'ui-cycles-acyclic'),
    '--mode',
    'gate',
    '--out',
    outJson,
  ]);
  assert.equal(result.status, 0, `acyclic gate should pass: ${result.stderr}`);
  const report = readJson(outJson);
  assert.equal(report.schema, 'ui-cycles-report.v1');
  assert.equal(report.cycle_count, 0);
  assert.equal(report.verdict, 'pass');
}

function testCyclicWarn(tempRoot) {
  const outJson = path.join(tempRoot, 'cyclic-warn.json');
  const result = runNode([
    gateScript,
    '--root',
    path.join(fixturesRoot, 'ui-cycles-cyclic'),
    '--mode',
    'warn',
    '--out',
    outJson,
  ]);
  assert.equal(result.status, 0, `cyclic warn should not fail: ${result.stderr}`);
  const report = readJson(outJson);
  assert.equal(report.schema, 'ui-cycles-report.v1');
  assert.ok(report.cycle_count > 0, 'cyclic fixture should report cycles');
  assert.equal(report.verdict, 'fail');
}

function testCyclicGate(tempRoot) {
  const outJson = path.join(tempRoot, 'cyclic-gate.json');
  const result = runNode([
    gateScript,
    '--root',
    path.join(fixturesRoot, 'ui-cycles-cyclic'),
    '--mode',
    'gate',
    '--out',
    outJson,
  ]);
  assert.equal(result.status, 1, 'cyclic gate should fail');
  const report = readJson(outJson);
  assert.equal(report.schema, 'ui-cycles-report.v1');
  assert.ok(report.cycle_count > 0, 'cyclic fixture should report cycles');
  assert.equal(report.verdict, 'fail');
}

function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-ui-cycles-'));
  try {
    testAcyclicGate(tempRoot);
    testCyclicWarn(tempRoot);
    testCyclicGate(tempRoot);
    // eslint-disable-next-line no-console
    console.log('check-ui-cycles tests: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main();

