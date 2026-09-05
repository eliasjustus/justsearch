#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  aggregateFlakeTrend,
  buildLaneArtifactMap,
  buildSuggestedObservationText,
  filterRunsInWindow,
  parseArgs,
  renderMarkdown,
  WIRING_BOUNDARY_ISO,
} from './report-flake-trend.mjs';

const scriptPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'report-flake-trend.mjs');
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function withTempRoot(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-flake-trend-'));
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function flakyTest(overrides = {}) {
  return {
    classname: 'io.justsearch.systemtests.api.IngestionDiagnosticsContractTest',
    name: 'initializationError',
    module: 'modules/system-tests',
    attempts: 2,
    firstFailureMessage: 'java.util.concurrent.TimeoutException: boot stalled',
    ...overrides,
  };
}

function laneData(overrides = {}) {
  return { hasData: true, flakyTests: [], ...overrides };
}

function record(overrides = {}) {
  return {
    runId: 1,
    createdAt: '2026-08-15T00:00:00Z',
    branch: 'main',
    url: 'https://github.com/example/example/actions/runs/1',
    lanes: {
      'app-ui': laneData(),
      'search-worker': laneData(),
      'platform-contracts': laneData(),
      'integration-tests': laneData(),
    },
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// (a) Two runs with the same flaky test -> occurrences=2, rate over runs-with-data.
// ---------------------------------------------------------------------------
{
  const flaky = flakyTest();
  const records = [
    record({ runId: 1, createdAt: '2026-08-15T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
    record({ runId: 2, createdAt: '2026-08-16T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
  ];
  const report = aggregateFlakeTrend(records, { minOccurrences: 1, days: 7 });
  assert.equal(report.tests.length, 1);
  const [test] = report.tests;
  assert.equal(test.occurrences, 2, 'two runs with the same flaky test must accumulate occurrences=2');
  assert.equal(test.runsWithData, 2, 'both runs supplied data for the integration-tests lane');
  assert.equal(test.rate, 1, 'occurrences/runsWithData = 2/2 = 1');
  assert.equal(test.firstSeen, '2026-08-15T00:00:00Z');
  assert.equal(test.lastSeen, '2026-08-16T00:00:00Z');
  assert.equal(test.maxAttempts, 2);
  assert.deepEqual(test.branches, ['main']);
  console.log('flake-trend (a) two-run occurrence accumulation: PASS');
}

// ---------------------------------------------------------------------------
// (b) A run with flakyTests:[] counts as data; an absent artifact (hasData:false) does not.
// ---------------------------------------------------------------------------
{
  const flaky = flakyTest();
  const records = [
    // Run 1: flaked.
    record({ runId: 1, createdAt: '2026-08-15T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
    // Run 2: attribution present, but genuinely no flakes this run — still counts toward the denominator.
    record({ runId: 2, createdAt: '2026-08-16T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [] }) } }),
    // Run 3: pre-#447, no artifact at all for this lane — must NOT count toward runsWithData.
    record({ runId: 3, createdAt: '2026-08-13T00:00:00Z', lanes: { 'integration-tests': { hasData: false, flakyTests: null } } }),
  ];
  const report = aggregateFlakeTrend(records, { minOccurrences: 1, days: 7 });
  assert.equal(report.window.totalRuns, 3);
  assert.equal(report.window.runsWithData, 2, 'no-data run must not count toward runsWithData');
  assert.equal(report.window.preWiringRuns, 1, 'run 3 predates the 2026-08-14 wiring boundary');
  const [test] = report.tests;
  assert.equal(test.occurrences, 1);
  assert.equal(test.runsWithData, 2, 'denominator is runs WITH data (2), not total runs (3) or flaking runs (1)');
  assert.equal(test.rate, 0.5, '1 occurrence / 2 runs-with-data, never divided by total runs');
  console.log('flake-trend (b) no-data vs zero-flakes distinction: PASS');
}

// ---------------------------------------------------------------------------
// (c) --min-occurrences filter.
// ---------------------------------------------------------------------------
{
  const flakyA = flakyTest({ name: 'testA' });
  const flakyB = flakyTest({ name: 'testB' });
  const records = [
    record({ runId: 1, createdAt: '2026-08-15T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flakyA, flakyB] }) } }),
    record({ runId: 2, createdAt: '2026-08-16T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flakyA] }) } }),
  ];
  const unfiltered = aggregateFlakeTrend(records, { minOccurrences: 1, days: 7 });
  assert.equal(unfiltered.tests.length, 2, 'both testA (2x) and testB (1x) appear with min-occurrences=1');
  assert.equal(unfiltered.allTestCount, 2, 'allTestCount is stable regardless of the filter');

  const filtered = aggregateFlakeTrend(records, { minOccurrences: 2, days: 7 });
  assert.equal(filtered.tests.length, 1, 'only testA (occurrences=2) survives min-occurrences=2');
  assert.equal(filtered.tests[0].name, 'testA');
  assert.equal(filtered.allTestCount, 2, 'allTestCount is unaffected by the report-row filter');
  console.log('flake-trend (c) min-occurrences filter: PASS');
}

// ---------------------------------------------------------------------------
// (d) >=3-occurrence WARN threshold.
// ---------------------------------------------------------------------------
{
  const flaky = flakyTest({ name: 'chronicFlake' });
  const twoRuns = [
    record({ runId: 1, createdAt: '2026-08-15T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
    record({ runId: 2, createdAt: '2026-08-16T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
  ];
  const belowThreshold = aggregateFlakeTrend(twoRuns, { minOccurrences: 1, days: 7 });
  assert.equal(belowThreshold.warnings.length, 0, 'occurrences=2 must not cross the >=3 WARN threshold');

  const threeRuns = [
    ...twoRuns,
    record({ runId: 3, createdAt: '2026-08-17T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
  ];
  const atThreshold = aggregateFlakeTrend(threeRuns, { minOccurrences: 1, days: 7 });
  assert.equal(atThreshold.warnings.length, 1, 'occurrences=3 must cross the >=3 WARN threshold');
  assert.match(atThreshold.warnings[0], /quarantine it in its own runner/, 'warning suggests the fix/quarantine remedy (930), does not write itself');
  assert.match(atThreshold.warnings[0], /chronicFlake/);
  console.log('flake-trend (d) >=3 WARN threshold: PASS');
}

// buildSuggestedObservationText is a pure formatter — spot-check independently of aggregation.
{
  const text = buildSuggestedObservationText(
    { classname: 'pkg.FooTest', name: 'testBar', lane: 'app-ui', occurrences: 4, runsWithData: 5 },
    7,
  );
  assert.match(text, /^fix the flake — /);
  assert.match(text, /pkg\.FooTest\.testBar/);
  assert.match(text, /4\/5 runs/);
  console.log('flake-trend buildSuggestedObservationText formatting: PASS');
}

// renderMarkdown smoke test: headline summary, wiring-boundary note, warnings section.
{
  const records = [
    record({ runId: 1, createdAt: '2026-08-13T00:00:00Z', lanes: { 'integration-tests': { hasData: false, flakyTests: null } } }),
    record({ runId: 2, createdAt: '2026-08-15T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flakyTest()] }) } }),
  ];
  const report = aggregateFlakeTrend(records, { minOccurrences: 1, days: 7, branch: 'main' });
  const md = renderMarkdown(report);
  assert.match(md, /Flake trend — last 7d \(branch: main\)/);
  assert.match(md, /1 flaky tests across 1 runs with data, 1 runs pre-dated the wiring\./);
  assert.match(md, /spans the 2026-08-14 flake-trend wiring boundary/);
  assert.match(md, /initializationError/);
  console.log('flake-trend renderMarkdown smoke test: PASS');
}

// filterRunsInWindow: completed-only, branch filter, days cutoff, maxRuns cap, newest-first sort.
{
  const now = new Date('2026-08-20T00:00:00Z');
  const rawRuns = [
    { id: 1, status: 'completed', head_branch: 'main', created_at: '2026-08-19T00:00:00Z' },
    { id: 2, status: 'in_progress', head_branch: 'main', created_at: '2026-08-19T00:00:00Z' }, // not completed
    { id: 3, status: 'completed', head_branch: 'feature-x', created_at: '2026-08-18T00:00:00Z' },
    { id: 4, status: 'completed', head_branch: 'main', created_at: '2026-08-01T00:00:00Z' }, // outside 7d window
    { id: 5, status: 'completed', head_branch: 'main', created_at: '2026-08-17T00:00:00Z' },
  ];
  const allBranches = filterRunsInWindow(rawRuns, { days: 7, maxRuns: 10, branch: null, now });
  assert.deepEqual(allBranches.map((r) => r.id), [1, 3, 5], 'completed + in-window, newest-first, no branch filter');

  const mainOnly = filterRunsInWindow(rawRuns, { days: 7, maxRuns: 10, branch: 'main', now });
  assert.deepEqual(mainOnly.map((r) => r.id), [1, 5]);

  const capped = filterRunsInWindow(rawRuns, { days: 7, maxRuns: 1, branch: null, now });
  assert.deepEqual(capped.map((r) => r.id), [1], 'maxRuns caps the result after sorting newest-first');
  console.log('flake-trend filterRunsInWindow: PASS');
}

// parseArgs: defaults, cap clamping, validation.
{
  const defaults = parseArgs([]);
  assert.equal(defaults.days, 7);
  assert.equal(defaults.maxRuns, 30);
  assert.equal(defaults.minOccurrences, 1);
  assert.equal(defaults.branch, null);

  const clamped = parseArgs(['--max-runs', '500']);
  assert.equal(clamped.maxRuns, 100, '--max-runs above the cap clamps to 100');

  assert.throws(() => parseArgs(['--days', '0']), /--days must be a positive integer/);
  assert.throws(() => parseArgs(['--min-occurrences', '-1']), /--min-occurrences must be a positive integer/);
  console.log('flake-trend parseArgs: PASS');
}

// buildLaneArtifactMap: reads the real unit-test-shard-policy register (avoids a second,
// drifting copy of lane/artifact names) and wires integration-tests on top.
{
  const map = buildLaneArtifactMap(repoRoot);
  assert.equal(map['app-ui'].artifact, 'unit-test-attribution-app-ui');
  assert.equal(map['search-worker'].artifact, 'unit-test-attribution-search-worker');
  assert.equal(map['platform-contracts'].artifact, 'unit-test-attribution-platform-contracts');
  assert.equal(map['integration-tests'].artifact, 'integration-test-results');
  assert.equal(map['integration-tests'].file, 'integration-test-attribution.json');
  console.log('flake-trend buildLaneArtifactMap: PASS');
}

assert.ok(new Date(WIRING_BOUNDARY_ISO).getTime() > 0, 'WIRING_BOUNDARY_ISO must be a valid ISO timestamp');

// ---------------------------------------------------------------------------
// End-to-end CLI: --records-json replays fixture records with no network, exercising the
// same collection/aggregation/render/output-writing path the live tool uses.
// ---------------------------------------------------------------------------
withTempRoot((root) => {
  const recordsPath = path.join(root, 'records.json');
  const flaky = flakyTest({ name: 'cliFlake' });
  const records = [
    record({ runId: 1, createdAt: '2026-08-15T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
    record({ runId: 2, createdAt: '2026-08-16T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
    record({ runId: 3, createdAt: '2026-08-17T00:00:00Z', lanes: { 'integration-tests': laneData({ flakyTests: [flaky] }) } }),
  ];
  fs.writeFileSync(recordsPath, JSON.stringify(records), 'utf8');

  const outJson = path.join(root, 'out/report.json');
  const outMd = path.join(root, 'out/report.md');
  const res = spawnSync(process.execPath, [
    scriptPath,
    '--records-json', recordsPath,
    '--out-json', outJson,
    '--out-md', outMd,
    '--json',
  ], { encoding: 'utf8' });

  assert.equal(res.status, 0, res.stderr);
  const parsed = JSON.parse(res.stdout);
  assert.equal(parsed.kind, 'justsearch-flake-trend.v1');
  assert.equal(parsed.tests[0].occurrences, 3);
  assert.match(res.stderr, /WARN:.*cliFlake/, 'the >=3 threshold prints a WARN with the suggested observation text to stderr');
  assert.equal(JSON.parse(fs.readFileSync(outJson, 'utf8')).tests[0].occurrences, 3);
  assert.match(fs.readFileSync(outMd, 'utf8'), /cliFlake/);
  console.log('flake-trend CLI --records-json end-to-end: PASS');
});

// --help exits cleanly without requiring gh/network.
{
  const res = spawnSync(process.execPath, [scriptPath, '--help'], { encoding: 'utf8' });
  assert.equal(res.status, 0, res.stderr);
  assert.match(res.stdout, /Usage: node scripts\/ci\/report-flake-trend\.mjs/);
  console.log('flake-trend CLI --help: PASS');
}

console.log('test-report-flake-trend: PASS');
