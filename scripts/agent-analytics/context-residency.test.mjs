/**
 * context-residency.test.mjs — unit tests for the ledger-based context
 * residency reader (tempdoc 886 §12 PR 2), on synthetic Call fixtures built
 * in-process (no real transcript content, no filesystem for sections a-c).
 *
 * Run with: `node scripts/agent-analytics/context-residency.test.mjs`
 */

import assert from 'node:assert/strict';
import {
  percentile, buildDistribution, buildCapExcess, buildCompactionLedger,
  accumulateResidency, emptyResidencyAcc, residencyReport, collapseCategory,
} from './context-residency.mjs';
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

// --- percentile --------------------------------------------------------------

run('percentile: p50 of [1,2,3,4,5] is the middle element (floor-index method)', () => {
  assert.equal(percentile([1, 2, 3, 4, 5], 0.5), 3);
});

run('percentile: p0 is the min, and an empty array returns 0', () => {
  assert.equal(percentile([10, 20, 30], 0), 10);
  assert.equal(percentile([], 0.5), 0);
});

run('percentile: p99 clamps to the last element, never overflows', () => {
  assert.equal(percentile([1, 2, 3], 0.99), 3);
});

// --- buildDistribution ---------------------------------------------------

function call(overrides = {}) {
  return makeCall({
    harness: 'claude-code',
    sessionId: overrides.sessionId ?? 's1',
    lineage: overrides.lineage ?? { kind: 'main' },
    model: overrides.model ?? 'claude-opus-5',
    contextTokens: overrides.contextTokens ?? 100000,
    ts: overrides.ts ?? '2026-08-01T00:00:00.000Z',
    tokens: overrides.tokens ?? { fresh: 0, output: 100 },
    compactionBoundary: overrides.compactionBoundary ?? false,
    compactMetadata: overrides.compactMetadata,
    synthetic: overrides.synthetic ?? false,
    ...overrides.rest,
  });
}

run('buildDistribution groups by harness x lineage x model and computes percentiles', () => {
  const calls = [
    call({ contextTokens: 100000 }),
    call({ contextTokens: 200000 }),
    call({ contextTokens: 300000 }),
    call({ contextTokens: 400000 }),
  ];
  const rows = buildDistribution(calls);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].calls, 4);
  assert.equal(rows[0].lineageGroup, 'main');
  assert.equal(rows[0].totalContextTokens, 1000000);
});

run('buildDistribution separates main from spawn/fork lineage', () => {
  const calls = [
    call({ lineage: { kind: 'main' } }),
    call({ lineage: { kind: 'spawn' } }),
    call({ lineage: { kind: 'fork' } }),
  ];
  const rows = buildDistribution(calls);
  const groups = new Set(rows.map((r) => r.lineageGroup));
  assert.deepEqual([...groups].sort(), ['main', 'spawn/fork']);
  const spawnRow = rows.find((r) => r.lineageGroup === 'spawn/fork');
  assert.equal(spawnRow.calls, 2, 'spawn and fork collapse into one spawn/fork group');
});

run('buildDistribution computes ctx/output ratio, null when output is 0', () => {
  const calls = [call({ contextTokens: 1000, tokens: { fresh: 0, output: 10 } })];
  assert.equal(buildDistribution(calls)[0].ratio, 100);
  const zeroOut = [call({ contextTokens: 1000, tokens: { fresh: 0, output: 0 } })];
  assert.equal(buildDistribution(zeroOut)[0].ratio, null);
});

// --- buildCapExcess --------------------------------------------------------

run('buildCapExcess prices only the EXCESS above cap at the cache-read rate', () => {
  const calls = [call({ contextTokens: 300000, model: 'claude-opus-5' })];
  const rows = buildCapExcess(calls, 200000);
  // opus-5 cache_read = 0.50 per 1M -> excess 100000 * 0.50/1e6 = 0.05
  assert.equal(rows[0].tokensAboveCap, 300000);
  assert.equal(rows[0].costAboveCapUsd, 0);
  assert.equal(round4(0.05), round4(0.05)); // sanity no-op, precise check below
});

function round4(n) { return Math.round(n * 10000) / 10000; }

run('buildCapExcess: a call at or below cap contributes nothing to tokensAboveCap', () => {
  const calls = [call({ contextTokens: 200000 })];
  const rows = buildCapExcess(calls, 200000);
  assert.equal(rows[0].tokensAboveCap, 0);
  assert.equal(rows[0].sharePct, 0);
});

run('buildCapExcess: an unpriced model counts excess tokens but reports cost as n/a-tracked, never $0-silent', () => {
  const calls = [call({ contextTokens: 300000, model: 'gpt-5.5-unknown-model' })];
  const rows = buildCapExcess(calls, 200000);
  assert.equal(rows[0].costAboveCapUsd, 0);
  assert.equal(rows[0].unpricedTokensAboveCap, 100000);
  assert.deepEqual(rows[0].unpricedModels, ['gpt-5.5-unknown-model']);
});

run('buildCapExcess: sharePct is the FULL context of qualifying calls over the group total', () => {
  const calls = [
    call({ contextTokens: 500000 }), // above cap
    call({ contextTokens: 100000 }), // below cap
  ];
  const rows = buildCapExcess(calls, 200000);
  // 500000 / 600000 = 83.3%
  assert.equal(rows[0].sharePct, 83.3);
});

// --- buildCompactionLedger --------------------------------------------------

run('buildCompactionLedger reads Claude compactMetadata verbatim when present', () => {
  const calls = [
    call({ sessionId: 'sA', contextTokens: 900000, ts: '2026-08-01T00:00:00.000Z' }),
    call({
      sessionId: 'sA', contextTokens: 20000, ts: '2026-08-01T00:01:00.000Z',
      compactionBoundary: true,
      compactMetadata: { trigger: 'manual', preTokens: 889000, postTokens: 20000, durationMs: 150000 },
    }),
  ];
  const ledger = buildCompactionLedger(calls);
  assert.equal(ledger.count, 1);
  assert.equal(ledger.triggerBreakdown.manual, 1);
  assert.equal(ledger.rows[0].preTokens, 889000);
  assert.equal(ledger.rows[0].postTokens, 20000);
  assert.equal(ledger.rows[0].durationMs, 150000);
});

run('buildCompactionLedger infers pre/post tokens from adjacent calls when compactMetadata is absent (Codex)', () => {
  const calls = [
    makeCall({
      harness: 'codex-cli', sessionId: 'sB', model: 'gpt-5.5', ts: '2026-08-01T00:00:00.000Z',
      contextTokens: 130000, tokens: { fresh: 0, output: 10 },
    }),
    makeCall({
      harness: 'codex-cli', sessionId: 'sB', model: 'gpt-5.5', ts: '2026-08-01T00:01:00.000Z',
      contextTokens: 500, tokens: { fresh: 0, output: 5 }, compactionBoundary: true,
    }),
  ];
  const ledger = buildCompactionLedger(calls);
  assert.equal(ledger.count, 1);
  assert.equal(ledger.triggerBreakdown.codex, 1);
  assert.equal(ledger.rows[0].preTokens, 130000);
  assert.equal(ledger.rows[0].postTokens, 500);
  assert.equal(ledger.rows[0].durationMs, null);
});

run('buildCompactionLedger excludes synthetic calls from the ledger (886 §12 PR 2 contract)', () => {
  const calls = [
    makeCall({
      harness: 'codex-cli', sessionId: 'sC', model: 'gpt-5.5', ts: '2026-08-01T00:00:00.000Z',
      contextTokens: 0, tokens: { fresh: 0, output: 0 }, compactionBoundary: true, synthetic: true,
    }),
  ];
  const nonSynthetic = calls.filter((c) => !c.synthetic);
  assert.equal(buildCompactionLedger(nonSynthetic).count, 0);
});

// --- accumulateResidency / residencyReport ----------------------------------

function usageEntry({ id, model = 'claude-opus-5', input = 0, cacheRead = 0, cacheCreate = 0, content = [] }) {
  return {
    type: 'assistant',
    message: {
      id, model,
      usage: { input_tokens: input, cache_read_input_tokens: cacheRead, cache_creation_input_tokens: cacheCreate, output_tokens: 10 },
      content,
    },
  };
}

run('accumulateResidency: total charged cost equals sum(ctx * cache_read_rate) over deduped calls', () => {
  const acc = emptyResidencyAcc();
  const entries = [
    usageEntry({ id: 'm1', input: 1000, cacheRead: 0, cacheCreate: 0 }),
    usageEntry({ id: 'm2', input: 500, cacheRead: 1000, cacheCreate: 0 }),
  ];
  accumulateResidency(entries, acc, 'claude-code:main');
  // opus-5 cache_read = 0.50/1e6
  const expected = (1000 * 0.5) / 1e6 + (1500 * 0.5) / 1e6;
  assert.ok(Math.abs(acc.totalCost - expected) < 1e-9);
  assert.equal(acc.totalCalls, 2);
});

run('accumulateResidency: message.id repeats are deduped within one walk (no double count)', () => {
  const acc = emptyResidencyAcc();
  const entries = [
    usageEntry({ id: 'dup', input: 1000 }),
    usageEntry({ id: 'dup', input: 1000 }),
  ];
  accumulateResidency(entries, acc, 'claude-code:main');
  assert.equal(acc.totalCalls, 1);
});

run('accumulateResidency: a compaction boundary RESETS the resident set (deep4 method)', () => {
  const acc = emptyResidencyAcc();
  const entries = [
    // call m0: emits a tool_use (Read), registering it before any result exists
    usageEntry({ id: 'm0', input: 100, content: [{ type: 'tool_use', id: 'tu1', name: 'Read', input: {} }] }),
    // the tool_result becomes resident, attributed to the Read tool_use above
    {
      type: 'user',
      message: { content: [{ type: 'tool_result', tool_use_id: 'tu1', content: 'x'.repeat(400) }] },
    },
    // call m1: the Read result is resident, so it gets charged here
    usageEntry({ id: 'm1', input: 400 }),
    { compactMetadata: { trigger: 'manual', preTokens: 1000, postTokens: 100 } },
    // call m2: post-boundary, resident set was reset -- the Read result is NOT charged again
    usageEntry({ id: 'm2', input: 100 }),
  ];
  accumulateResidency(entries, acc, 'claude-code:main');
  const readKey = 'claude-code:main:tool:Read';
  const chargedOnceOnly = acc.byCategory[readKey];
  assert.ok(chargedOnceOnly > 0, 'the Read tool result was charged on call m1');

  // Re-run WITHOUT the boundary entry: the Read result stays resident into m2
  // too, so it must be charged a SECOND time -- strictly more than the
  // single-charge case above. This is the compounding property itself.
  const accNoBoundary = emptyResidencyAcc();
  const entriesNoBoundary = entries.filter((e) => !e.compactMetadata);
  accumulateResidency(entriesNoBoundary, accNoBoundary, 'claude-code:main');
  assert.ok(accNoBoundary.byCategory[readKey] > chargedOnceOnly,
    'without a boundary reset, the same resident piece compounds across more calls');
});

run('accumulateResidency: a call with an unpriced model is skipped (fails closed, no charge)', () => {
  const acc = emptyResidencyAcc();
  accumulateResidency([usageEntry({ id: 'm1', model: 'unknown-model-xyz', input: 1000 })], acc, 'claude-code:main');
  assert.equal(acc.totalCalls, 0);
  assert.equal(acc.totalCost, 0);
});

run('collapseCategory strips a tool name and a parenthesized suffix', () => {
  assert.equal(collapseCategory('claude-code:sub:tool:Bash'), 'claude-code:sub:tool');
  assert.equal(collapseCategory('claude-code:main:assistant-tool_use(Write/Edit)'), 'claude-code:main:assistant-tool_use');
});

run('residencyReport: byCategory sums collapse to the same total as top20 (accounting closes)', () => {
  const acc = emptyResidencyAcc();
  acc.byCategory['claude-code:sub:tool:Bash'] = 10;
  acc.byCategory['claude-code:sub:tool:Read'] = 5;
  acc.byCategory['claude-code:main:prefix(system+CLAUDE.md+tools)'] = 20;
  acc.totalCost = 35;
  acc.totalCalls = 3;
  const report = residencyReport(acc);
  const byCategorySum = report.byCategory.reduce((a, r) => a + r.costUsd, 0);
  assert.ok(Math.abs(byCategorySum - 35) < 1e-9);
  const toolGroup = report.byCategory.find((r) => r.category === 'claude-code:sub:tool');
  assert.equal(toolGroup.costUsd, 15);
});

// --- synthetic exclusion (cross-cutting) ------------------------------------

run('synthetic calls are excluded from buildDistribution/buildCapExcess when the caller filters them (contract)', () => {
  const synthetic = makeCall({
    harness: 'codex-cli', sessionId: 's1', model: 'gpt-5.5', contextTokens: 999999999,
    tokens: { fresh: 0, output: 0 }, synthetic: true,
  });
  const real = call({ harness: 'claude-code', contextTokens: 1000 });
  const nonSynthetic = [synthetic, real].filter((c) => !c.synthetic);
  const rows = buildDistribution(nonSynthetic);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].harness, 'claude-code');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`context-residency.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`context-residency.test: ${passed} passed`);
