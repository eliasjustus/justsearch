/**
 * spawn-economics.test.mjs — unit tests for the spawn/lineage cost reader
 * (tempdoc 886 §12 PR 2), on synthetic Call fixtures built in-process.
 *
 * Run with: `node scripts/agent-analytics/spawn-economics.test.mjs`
 */

import assert from 'node:assert/strict';
import {
  costOfCall, buildSpawnRows, buildMultiAgentSessionRows,
  groupRequestedToActual, groupByAgentType, groupByRoleModelEffort,
  runLengthBuckets, topByCost,
  firstUserMessageCharsPercentile,
} from './spawn-economics.mjs';
import { makeCall } from './lib/ledger/record.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

function spawnCall(overrides = {}) {
  return makeCall({
    harness: 'claude-code',
    sessionId: overrides.sessionId ?? 'main-1:agent-a',
    model: overrides.model ?? 'claude-opus-5',
    ts: overrides.ts ?? '2026-08-01T00:00:00.000Z',
    contextTokens: overrides.contextTokens ?? 100000,
    tokens: overrides.tokens ?? { fresh: 1000, cacheRead: 90000, cacheWrite5m: 9000, cacheWrite1h: 0, output: 500 },
    lineage: {
      parentSessionId: overrides.parentSessionId ?? 'main-1',
      kind: overrides.kind ?? 'spawn',
      agentType: overrides.agentType ?? 'general-purpose',
      requestedModel: overrides.requestedModel ?? 'opus',
      description: overrides.description ?? 'test spawn',
    },
    synthetic: overrides.synthetic ?? false,
    reasoningEffort: overrides.reasoningEffort ?? null,
  });
}

// --- costOfCall --------------------------------------------------------------

run('costOfCall prices a known model using its own token axes', () => {
  const c = spawnCall({ tokens: { fresh: 1_000_000, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, output: 0 } });
  const { usd: cost, priced } = costOfCall(c);
  assert.equal(priced, true);
  // claude-opus-5 input rate = 5.0 per 1M
  assert.equal(cost, 5.0);
});

run('costOfCall returns priced:false for an unknown model, never a silent $0', () => {
  const c = spawnCall({ model: 'gpt-nonexistent' });
  const { usd: cost, priced } = costOfCall(c);
  assert.equal(priced, false);
  assert.equal(cost, 0);
});

// --- buildSpawnRows ------------------------------------------------------

run('buildSpawnRows groups by sessionId, sums cost, tracks peak context and last-seen model', () => {
  const calls = [
    spawnCall({ sessionId: 'main-1:agent-a', ts: '2026-08-01T00:00:00.000Z', contextTokens: 100000, model: 'claude-opus-5' }),
    spawnCall({ sessionId: 'main-1:agent-a', ts: '2026-08-01T00:01:00.000Z', contextTokens: 300000, model: 'claude-sonnet-5' }),
  ];
  const rows = buildSpawnRows(calls);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].calls, 2);
  assert.equal(rows[0].peakContextTokens, 300000);
  assert.equal(rows[0].actualModel, 'claude-sonnet-5', 'last-seen model in ts order wins');
  assert.equal(rows[0].actualEffort, '(missing-effort)');
  assert.equal(rows[0].requestedModel, 'opus');
  assert.equal(rows[0].agentType, 'general-purpose');
  assert.equal(rows[0].parentSessionId, 'main-1');
  assert.ok(rows[0].costUsd > 0);
});

run('buildSpawnRows records the last-seen actual reasoning effort', () => {
  const calls = [spawnCall({ reasoningEffort: 'high' })];
  assert.equal(buildSpawnRows(calls)[0].actualEffort, 'high');
});

run('buildSpawnRows: an unpriced model contributes to unpricedTokens, not a silent-$0 cost', () => {
  const calls = [spawnCall({ model: 'unknown-model', contextTokens: 50000, tokens: { fresh: 50000, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, output: 100 } })];
  const rows = buildSpawnRows(calls);
  assert.equal(rows[0].costUsd, 0);
  assert.equal(rows[0].unpricedTokens, 50000);
});

run('buildSpawnRows: distinct sessionIds produce distinct rows, sorted by cost descending', () => {
  const calls = [
    spawnCall({ sessionId: 'main-1:agent-a', tokens: { fresh: 100, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, output: 10 } }),
    spawnCall({ sessionId: 'main-1:agent-b', tokens: { fresh: 1_000_000, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, output: 0 } }),
  ];
  const rows = buildSpawnRows(calls);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].sessionId, 'main-1:agent-b', 'higher-cost row sorts first');
});

run('buildSpawnRows: firstUserMessageChars comes from the injected map, null when absent', () => {
  const calls = [spawnCall({ sessionId: 'main-1:agent-a' })];
  const map = new Map([['main-1:agent-a', 3900]]);
  assert.equal(buildSpawnRows(calls, map)[0].firstUserMessageChars, 3900);
  assert.equal(buildSpawnRows(calls)[0].firstUserMessageChars, null);
});

// --- buildMultiAgentSessionRows ----------------------------------------------

run('buildMultiAgentSessionRows only includes Codex sessions with multiAgent:true', () => {
  const calls = [
    makeCall({ harness: 'codex-cli', sessionId: 'codex-1', model: 'gpt-5.6', ts: '2026-08-01T00:00:00.000Z', contextTokens: 50000, tokens: { fresh: 50000, output: 100 } }),
    makeCall({ harness: 'codex-cli', sessionId: 'codex-2', model: 'gpt-5.6', ts: '2026-08-01T00:00:00.000Z', contextTokens: 60000, tokens: { fresh: 60000, output: 100 } }),
  ];
  const sessions = [
    { harness: 'codex-cli', sessionId: 'codex-1', multiAgent: true },
    { harness: 'codex-cli', sessionId: 'codex-2', multiAgent: false },
  ];
  const rows = buildMultiAgentSessionRows(calls, sessions);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].sessionId, 'codex-1');
});

run('buildMultiAgentSessionRows returns [] when no session is multiAgent', () => {
  const sessions = [{ harness: 'codex-cli', sessionId: 'codex-1', multiAgent: false }];
  assert.deepEqual(buildMultiAgentSessionRows([], sessions), []);
});

run('buildMultiAgentSessionRows excludes attributed Codex child calls', () => {
  const calls = [makeCall({
    harness: 'codex-cli', sessionId: 'codex-child', model: 'gpt-5.6-luna',
    lineage: { kind: 'spawn', parentSessionId: 'codex-parent', agentType: 'worker' },
    contextTokens: 10, tokens: { fresh: 10, output: 1 },
  })];
  const sessions = [{ harness: 'codex-cli', sessionId: 'codex-child', multiAgent: true }];
  assert.deepEqual(buildMultiAgentSessionRows(calls, sessions), []);
});

// --- grouping tables -----------------------------------------------------

run('groupRequestedToActual keys by "requested -> actual" and sums spawns/cost/calls', () => {
  const rows = [
    { requestedModel: 'opus', actualModel: 'claude-opus-5', costUsd: 10, calls: 5 },
    { requestedModel: 'opus', actualModel: 'claude-opus-5', costUsd: 20, calls: 7 },
    { requestedModel: 'sonnet', actualModel: 'claude-sonnet-5', costUsd: 1, calls: 3 },
  ];
  const g = groupRequestedToActual(rows);
  const opusRow = g.find((r) => r.key === 'opus -> claude-opus-5');
  assert.equal(opusRow.spawns, 2);
  assert.equal(opusRow.costUsd, 30);
  assert.equal(opusRow.calls, 12);
  assert.equal(g[0].key, 'opus -> claude-opus-5', 'sorted by cost descending');
});

run('groupByAgentType sums per agentType', () => {
  const rows = [
    { agentType: 'general-purpose', costUsd: 10, calls: 5 },
    { agentType: 'Explore', costUsd: 1, calls: 2 },
    { agentType: 'general-purpose', costUsd: 5, calls: 3 },
  ];
  const g = groupByAgentType(rows);
  const gp = g.find((r) => r.agentType === 'general-purpose');
  assert.equal(gp.spawns, 2);
  assert.equal(gp.costUsd, 15);
});

run('groupByRoleModelEffort exposes the Codex routing outcome', () => {
  const rows = [
    { agentType: 'worker', actualModel: 'gpt-5.6-luna', actualEffort: 'high', costUsd: 0, calls: 7 },
    { agentType: 'worker', actualModel: 'gpt-5.6-luna', actualEffort: 'high', costUsd: 0, calls: 5 },
    { agentType: 'complex_worker', actualModel: 'gpt-5.6-sol', actualEffort: 'medium', costUsd: 0, calls: 3 },
  ];
  const groups = groupByRoleModelEffort(rows);
  assert.deepEqual(groups[0], {
    key: 'worker | gpt-5.6-luna | high', agentType: 'worker',
    actualModel: 'gpt-5.6-luna', actualEffort: 'high',
    spawns: 2, costUsd: 0, calls: 12,
  });
});

// --- run-length buckets ------------------------------------------------------

run('runLengthBuckets assigns spawns to the correct [lo,hi) bucket and computes cost share', () => {
  const rows = [
    { calls: 5, costUsd: 10 },
    { calls: 150, costUsd: 90 },
  ];
  const buckets = runLengthBuckets(rows);
  const b0 = buckets.find((b) => b.bucket === '0-10');
  const b120 = buckets.find((b) => b.bucket === '120-250');
  assert.equal(b0.spawns, 1);
  assert.equal(b120.spawns, 1);
  assert.equal(b120.costSharePct, 90);
});

run('runLengthBuckets: the last bucket is open-ended (500+)', () => {
  const rows = [{ calls: 1000, costUsd: 5 }];
  const buckets = runLengthBuckets(rows);
  const last = buckets.find((b) => b.bucket === '500+');
  assert.equal(last.spawns, 1);
});

// --- topByCost / firstUserMessageCharsPercentile -----------------------------

run('topByCost returns the first N rows (rows are pre-sorted by buildSpawnRows)', () => {
  const rows = [{ costUsd: 30 }, { costUsd: 20 }, { costUsd: 10 }];
  assert.equal(topByCost(rows, 2).length, 2);
});

run('firstUserMessageCharsPercentile ignores null values', () => {
  const rows = [{ firstUserMessageChars: 100 }, { firstUserMessageChars: null }, { firstUserMessageChars: 200 }, { firstUserMessageChars: 300 }];
  assert.equal(firstUserMessageCharsPercentile(rows, 0.5), 200);
  assert.equal(firstUserMessageCharsPercentile([{ firstUserMessageChars: null }], 0.5), null);
});

// --- synthetic exclusion (contract, cross-cutting) ---------------------------

run('a synthetic call filtered by the caller never reaches buildSpawnRows', () => {
  const synthetic = makeCall({
    harness: 'claude-code', sessionId: 'main-1:agent-z', model: 'claude-opus-5',
    contextTokens: 999999, tokens: { fresh: 0, output: 0 }, synthetic: true,
    lineage: { kind: 'spawn', parentSessionId: 'main-1' },
  });
  const nonSynthetic = [synthetic].filter((c) => !c.synthetic);
  assert.deepEqual(buildSpawnRows(nonSynthetic), []);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`spawn-economics.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`spawn-economics.test: ${passed} passed`);
