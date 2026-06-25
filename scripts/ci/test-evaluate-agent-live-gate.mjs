#!/usr/bin/env node
/**
 * Tests for scripts/ci/evaluate-agent-live-gate.mjs
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir =
  process.platform === 'win32' ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR;
const gateScript = path.join(scriptDir, 'evaluate-agent-live-gate.mjs');

function runNode(args) {
  return spawnSync('node', args, {
    encoding: 'utf8',
    timeout: 10_000,
  });
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function scorecardPass() {
  return {
    kind: 'agent-live-scorecard.v1',
    schemaVersion: 2,
    generatedAt: '2026-02-18T00:00:00.000Z',
    window: { targetRuns: 14, actualRuns: 14 },
    thresholds: {
      infraFailureRateMax: 0.15,
      passRateStdDevMax: 0.08,
      scenarioInstabilityMax: 0.2,
      runsRequired: 14,
    },
    metrics: {
      infraFailureRate: 0,
      passRateStdDev: 0.01,
      scenarioInstabilityRate: 0.01,
    },
    gates: {
      infraFailureRate: true,
      passRateStdDev: true,
      scenarioInstability: true,
      runsRequired: true,
    },
  };
}

function manifest({ infraFailure = false, teardownFailure = false, errors = [] }) {
  return {
    kind: 'agent-live-battery-manifest.v1',
    version: 1,
    generatedAt: '2026-02-18T00:01:00.000Z',
    aggregate: {
      total: 1,
      passed: 1,
      failed: 0,
      passRate: 1,
      infraFailure,
      teardownFailure,
    },
    scenarios: [],
    errors,
  };
}

function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-gate-test-'));
  try {
    const scorePath = path.join(tempRoot, 'scorecard.json');
    writeJson(scorePath, scorecardPass());

    const healthyManifestPath = path.join(tempRoot, 'healthy.json');
    writeJson(healthyManifestPath, manifest({}));
    const healthy = runNode([
      gateScript,
      '--scorecard',
      scorePath,
      '--mode',
      'B',
      '--current-manifest',
      healthyManifestPath,
    ]);
    assert.equal(healthy.status, 0, `healthy manifest should pass: ${healthy.stderr}`);

    const teardownManifestPath = path.join(tempRoot, 'teardown.json');
    writeJson(teardownManifestPath, manifest({ teardownFailure: true }));
    const teardown = runNode([
      gateScript,
      '--scorecard',
      scorePath,
      '--mode',
      'B',
      '--current-manifest',
      teardownManifestPath,
    ]);
    assert.equal(teardown.status, 1, 'teardownFailure=true must fail gate');

    const stopFailedManifestPath = path.join(tempRoot, 'stop-failed.json');
    writeJson(stopFailedManifestPath, manifest({ errors: ['stop_failed:runner_not_found'] }));
    const stopFailed = runNode([
      gateScript,
      '--scorecard',
      scorePath,
      '--mode',
      'B',
      '--current-manifest',
      stopFailedManifestPath,
    ]);
    assert.equal(stopFailed.status, 1, 'stop_failed:* error must fail gate');

    // eslint-disable-next-line no-console
    console.log('evaluate-agent-live-gate tests: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main();
