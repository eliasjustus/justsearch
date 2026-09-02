/**
 * efficiency-trend.test.mjs — unit tests for the trend reader (tempdoc 908 §5.1),
 * on synthetic Call fixtures built in-process (no real transcript content).
 *
 * Run with: `node scripts/agent-analytics/efficiency-trend.test.mjs`
 */

import assert from 'node:assert/strict';
import os from 'node:os';
import {
  percentile, isoWeekKey, dayKey, bucketKey, weekBounds, dayBounds, bucketBounds,
  buildLeadingIndicators, buildBucketedSpawnRows, buildSpawnTail,
  classifyPath, buildDeliveryRows, deliveryPowerWarning,
  classifyBucket, resolveGitRef, mergeLiveAndSnapshot,
} from './efficiency-trend.mjs';
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

// --- percentile (shared with context-residency.mjs's convention) -----------

run('percentile: p50 of [1,2,3,4,5] is the middle element (floor-index method)', () => {
  assert.equal(percentile([1, 2, 3, 4, 5], 0.5), 3);
});

run('percentile: the two conventions named in 908 §5.1/§6.1 are the SAME value', () => {
  // sorted[floor(p*len)] (p=0.5) vs sorted[floor(len/2)] -- verified identical
  // for every integer len (0.5 is exact in IEEE-754 double), not merely assumed.
  for (const len of [1, 2, 3, 4, 5, 7, 10, 50, 131, 257]) {
    const sorted = Array.from({ length: len }, (_, i) => i);
    assert.equal(percentile(sorted, 0.5), sorted[Math.floor(len / 2)]);
  }
});

// --- ISO-week / day bucketing (boundary alignment against known dates) -----

run('isoWeekKey: Monday 2026-08-03 through Sunday 2026-08-09 all fall in 2026-W32', () => {
  assert.equal(isoWeekKey(Date.parse('2026-08-03T00:00:00.000Z')), '2026-W32');
  assert.equal(isoWeekKey(Date.parse('2026-08-09T23:59:59.999Z')), '2026-W32');
});

run('isoWeekKey: the Monday boundary itself rolls to the next week', () => {
  assert.equal(isoWeekKey(Date.parse('2026-08-10T00:00:00.000Z')), '2026-W33');
});

run('isoWeekKey: a late-December Monday can belong to the FOLLOWING ISO year (year-boundary correctness)', () => {
  assert.equal(isoWeekKey(Date.parse('2025-12-29T00:00:00.000Z')), '2026-W01');
});

run('dayKey: UTC calendar day, independent of time-of-day', () => {
  assert.equal(dayKey(Date.parse('2026-08-20T23:59:59.999Z')), '2026-08-20');
  assert.equal(dayKey(Date.parse('2026-08-20T00:00:00.000Z')), '2026-08-20');
});

run('bucketKey dispatches on `by`', () => {
  const ts = Date.parse('2026-08-20T12:00:00.000Z');
  assert.equal(bucketKey(ts, 'week'), isoWeekKey(ts));
  assert.equal(bucketKey(ts, 'day'), dayKey(ts));
});

run('weekBounds/dayBounds round-trip: bucketing a bound\'s own start ms returns the same key', () => {
  const wb = weekBounds('2026-W32');
  assert.equal(isoWeekKey(wb.startMs), '2026-W32');
  assert.equal(wb.endMs - wb.startMs, 7 * 24 * 60 * 60 * 1000);
  const db = dayBounds('2026-08-20');
  assert.equal(dayKey(db.startMs), '2026-08-20');
  assert.equal(db.endMs - db.startMs, 24 * 60 * 60 * 1000);
});

run('bucketBounds dispatches on `by` the same way bucketKey does', () => {
  assert.deepEqual(bucketBounds('2026-W32', 'week'), weekBounds('2026-W32'));
  assert.deepEqual(bucketBounds('2026-08-20', 'day'), dayBounds('2026-08-20'));
});

// --- buildLeadingIndicators ---------------------------------------------------

function call(overrides = {}) {
  return makeCall({
    harness: overrides.harness ?? 'claude-code',
    sessionId: overrides.sessionId ?? 's1',
    lineage: overrides.lineage ?? { kind: 'main' },
    model: overrides.model ?? 'claude-opus-5',
    contextTokens: overrides.contextTokens ?? 100000,
    ts: overrides.ts ?? '2026-08-03T00:00:00.000Z',
    tokens: overrides.tokens ?? { fresh: 0, output: 100 },
    synthetic: overrides.synthetic ?? false,
  });
}

run('buildLeadingIndicators: one row per bucket, calls/cost/ctxOut accumulate', () => {
  const calls = [
    call({ ts: '2026-08-03T00:00:00.000Z', contextTokens: 100000, tokens: { fresh: 0, output: 100 } }),
    call({ ts: '2026-08-04T00:00:00.000Z', contextTokens: 200000, tokens: { fresh: 0, output: 100 } }),
  ];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].bucket, '2026-W32');
  assert.equal(rows[0].calls, 2);
  assert.equal(rows[0].ctxOut, 1500); // (100000+200000)/200
});

run('buildLeadingIndicators: main and spawn/fork contextTokens percentiles are tracked separately', () => {
  const calls = [
    call({ lineage: { kind: 'main' }, contextTokens: 400000 }),
    call({ lineage: { kind: 'spawn' }, contextTokens: 100000 }),
    call({ lineage: { kind: 'fork' }, contextTokens: 200000 }),
  ];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows[0].mainP50Ctx, 400000);
  // spawn+fork collapse into one "sub" percentile group -- p50 of [100000,200000] floor(0.5*2)=idx1
  assert.equal(rows[0].subP50Ctx, 200000);
});

run('buildLeadingIndicators: unpriced calls are counted, never silently summed as $0', () => {
  const calls = [call({ model: 'gpt-5.5-unknown-model', tokens: { fresh: 0, output: 100 } })];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows[0].unpricedCalls, 1);
  assert.equal(rows[0].costUsd, 0);
});

run('buildLeadingIndicators: a call with no parsable ts is excluded, not folded into a bucket', () => {
  // makeCall's own `ts: partial.ts ?? null` means an EXPLICIT null stays null;
  // bypass the `call()` helper's default-ts fallback (which also treats null
  // as "use the default" via `??`) by constructing directly.
  const calls = [
    makeCall({ harness: 'claude-code', sessionId: 's1', model: 'claude-opus-5', contextTokens: 1000, ts: null, tokens: { fresh: 0, output: 1 } }),
    makeCall({ harness: 'claude-code', sessionId: 's1', model: 'claude-opus-5', contextTokens: 1000, ts: 'not-a-date', tokens: { fresh: 0, output: 1 } }),
  ];
  const { rows, excludedUnbucketable } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows.length, 0);
  assert.equal(excludedUnbucketable, 2);
});

run('buildLeadingIndicators: subCostSharePct is share of PRICED cost only', () => {
  const calls = [
    call({ lineage: { kind: 'main' }, model: 'claude-opus-5', tokens: { fresh: 0, output: 100 } }),
    call({ lineage: { kind: 'spawn' }, model: 'claude-opus-5', tokens: { fresh: 0, output: 100 } }),
  ];
  const { rows } = buildLeadingIndicators(calls, 'week');
  assert.equal(rows[0].subCostSharePct, 50);
});

// --- buildBucketedSpawnRows: spawn-bucket-by-first-call ------------------------

run('buildBucketedSpawnRows: a spawn crossing a bucket boundary is assigned ONE bucket -- the FIRST call\'s', () => {
  const calls = [
    // one spawn (sessionId s1) whose first call is in W32, second call drifts into W33
    call({ sessionId: 's1', lineage: { kind: 'spawn' }, ts: '2026-08-09T23:00:00.000Z', contextTokens: 50000 }),
    call({ sessionId: 's1', lineage: { kind: 'spawn' }, ts: '2026-08-10T01:00:00.000Z', contextTokens: 300000 }),
  ];
  const rows = buildBucketedSpawnRows(calls, 'week');
  assert.equal(rows.length, 1, 'one spawn == one row, not smeared across two buckets');
  assert.equal(rows[0].bucket, '2026-W32', 'bucketed by the FIRST call, even though the 2nd call is in W33');
  assert.equal(rows[0].calls, 2);
  assert.equal(rows[0].peakContextTokens, 300000, 'peak still looks at every call in the spawn');
});

run('buildBucketedSpawnRows: a spawn with no dateable first call is excluded (not bucketed as "now")', () => {
  const calls = [makeCall({
    harness: 'claude-code', sessionId: 's2', lineage: { kind: 'spawn' }, model: 'claude-opus-5',
    contextTokens: 1000, ts: null, tokens: { fresh: 0, output: 1 },
  })];
  const rows = buildBucketedSpawnRows(calls, 'week');
  assert.equal(rows.length, 0);
});

run('buildBucketedSpawnRows: unpriced calls inside a spawn are counted, not zero-priced silently', () => {
  const calls = [
    call({ sessionId: 's3', lineage: { kind: 'spawn' }, model: 'claude-opus-5', ts: '2026-08-03T00:00:00.000Z' }),
    call({ sessionId: 's3', lineage: { kind: 'spawn' }, model: 'unknown-xyz', ts: '2026-08-03T00:01:00.000Z' }),
  ];
  const rows = buildBucketedSpawnRows(calls, 'week');
  assert.equal(rows[0].unpricedCalls, 1);
});

// --- buildSpawnTail ------------------------------------------------------------

run('buildSpawnTail: long-spawn bucket threshold is >= (inclusive)', () => {
  const spawnRows = [
    { bucket: 'w', calls: 120, peakContextTokens: 100, costUsd: 10, unpricedCalls: 0 },
    { bucket: 'w', calls: 119, peakContextTokens: 100, costUsd: 5, unpricedCalls: 0 },
  ];
  const rows = buildSpawnTail(spawnRows, 120);
  assert.equal(rows[0].longSpawns, 1);
  assert.equal(rows[0].longCostSharePct, round1(10 / 15 * 100));
});
function round1(n) { return Math.round(n); }

run('buildSpawnTail: $/spawn is MEAN cost per spawn (total bucket cost / spawn count)', () => {
  const spawnRows = [
    { bucket: 'w', calls: 10, peakContextTokens: 100, costUsd: 10, unpricedCalls: 0 },
    { bucket: 'w', calls: 20, peakContextTokens: 200, costUsd: 20, unpricedCalls: 0 },
  ];
  const rows = buildSpawnTail(spawnRows, 120);
  assert.equal(rows[0].costPerSpawn, 15); // (10+20)/2
});

// --- classifyPath / buildDeliveryRows ------------------------------------------

run('classifyPath: code/docs/noise/other classification', () => {
  assert.equal(classifyPath('scripts/agent-analytics/foo.mjs'), 'code');
  assert.equal(classifyPath('modules/ui/src/main/java/Foo.java'), 'code');
  assert.equal(classifyPath('gates/foo/thing.json'), 'code');
  assert.equal(classifyPath('contracts/foo.proto'), 'code');
  assert.equal(classifyPath('docs/tempdocs/908-foo.md'), 'docs');
  assert.equal(classifyPath('package-lock.json'), 'noise');
  assert.equal(classifyPath('Cargo.lock'), 'noise');
  assert.equal(classifyPath('modules/ui-web/node_modules/foo/index.js'), 'noise');
  assert.equal(classifyPath('assets/logo.svg'), 'noise');
  assert.equal(classifyPath('README.md'), 'other');
});

run('buildDeliveryRows: median churn/PR and code-only churn split (908 §1.2b size control)', () => {
  const commits = [
    { hash: 'a', bucket: '2026-W32', files: [{ added: 100, deleted: 0, path: 'scripts/foo.mjs' }, { added: 5, deleted: 0, path: 'docs/bar.md' }] },
    { hash: 'b', bucket: '2026-W32', files: [{ added: 10, deleted: 10, path: 'package-lock.json' }] },
  ];
  const rows = buildDeliveryRows(commits, new Map([['2026-W32', 100]]));
  assert.equal(rows[0].prCount, 2);
  assert.equal(rows[0].codeChurn, 100, 'only the scripts/ file counts as code; docs and lockfile do not');
  assert.equal(rows[0].costPerLandedPR, 50); // 100/2
  assert.equal(rows[0].costPerKCodeLines, 1000); // 100*1000/100
});

run('buildDeliveryRows: a binary file\'s "-" numstat entry counts as 0, never NaN', () => {
  const commits = [{ hash: 'a', bucket: '2026-W32', files: [{ added: 0, deleted: 0, path: 'scripts/foo.bin' }] }];
  const rows = buildDeliveryRows(commits);
  assert.equal(rows[0].codeChurn, 0);
  assert.ok(!Number.isNaN(rows[0].medChurnPerPR));
});

run('buildDeliveryRows: a bucket absent from costByBucket reports null cost, never a fabricated $0', () => {
  const commits = [{ hash: 'a', bucket: '2026-W99', files: [{ added: 10, deleted: 0, path: 'scripts/foo.mjs' }] }];
  const rows = buildDeliveryRows(commits, new Map());
  assert.equal(rows[0].costUsd, null);
  assert.equal(rows[0].costPerLandedPR, null);
  assert.equal(rows[0].costPerKCodeLines, null);
});

run('deliveryPowerWarning: excludes TRUNCATED/PARTIAL buckets from the swing computation', () => {
  const rows = [
    { bucket: 'w1', costPerKCodeLines: 100 },
    { bucket: 'w2', costPerKCodeLines: 10 }, // huge swing, but excluded
    { bucket: 'w3', costPerKCodeLines: 90 },
  ];
  const msg = deliveryPowerWarning(rows, new Set(['w2']));
  assert.match(msg, /swings 1\.1x/); // 100/90, not 100/10 -- w2 excluded
});

run('deliveryPowerWarning: fewer than 2 eligible buckets reports "no swing computable", not a fabricated ratio', () => {
  const msg = deliveryPowerWarning([{ bucket: 'w1', costPerKCodeLines: 100 }], new Set());
  assert.match(msg, /no swing computable/);
});

// --- classifyBucket: truncated/partial marking ---------------------------------

run('classifyBucket: a bucket starting before floor+1day is TRUNCATED', () => {
  const bounds = { startMs: Date.parse('2026-08-03T00:00:00.000Z'), endMs: Date.parse('2026-08-10T00:00:00.000Z') };
  const floorMs = Date.parse('2026-08-04T12:00:00.000Z');
  const nowMs = Date.parse('2026-09-01T00:00:00.000Z');
  assert.deepEqual(classifyBucket(bounds, floorMs, nowMs), { truncated: true, partial: false });
});

run('classifyBucket: a bucket starting on-or-after floor+1day is NOT truncated', () => {
  const bounds = { startMs: Date.parse('2026-08-10T00:00:00.000Z'), endMs: Date.parse('2026-08-17T00:00:00.000Z') };
  const floorMs = Date.parse('2026-08-04T12:00:00.000Z');
  const nowMs = Date.parse('2026-09-01T00:00:00.000Z');
  assert.equal(classifyBucket(bounds, floorMs, nowMs).truncated, false);
});

run('classifyBucket: a bucket extending past now is PARTIAL', () => {
  const bounds = { startMs: Date.parse('2026-08-31T00:00:00.000Z'), endMs: Date.parse('2026-09-07T00:00:00.000Z') };
  const nowMs = Date.parse('2026-09-02T12:00:00.000Z');
  assert.equal(classifyBucket(bounds, null, nowMs).partial, true);
});

run('classifyBucket: a null floor (no transcripts discovered) never fabricates TRUNCATED', () => {
  const bounds = { startMs: Date.parse('2026-08-03T00:00:00.000Z'), endMs: Date.parse('2026-08-10T00:00:00.000Z') };
  assert.equal(classifyBucket(bounds, null, Date.parse('2026-09-01T00:00:00.000Z')).truncated, false);
});

// --- resolveGitRef: --no-git / git-absent behavior -----------------------------

run('resolveGitRef: an unresolvable ref AND an unresolvable "main" fallback returns null (never fabricates a ref)', () => {
  // a directory that is not inside a git repo at all -- both rev-parse calls fail
  const result = resolveGitRef({ cwd: os.tmpdir(), ref: 'origin/definitely-not-a-real-ref-xyz' });
  assert.equal(result, null);
});

run('resolveGitRef: the current repo\'s "main" resolves even when the requested ref does not (fallback: true)', () => {
  const result = resolveGitRef({ cwd: process.cwd(), ref: 'definitely-not-a-real-ref-xyz-908' });
  // main exists in this repo (branch-safety.md: main checkout stays on main)
  if (result) {
    assert.equal(result.ref, 'main');
    assert.equal(result.fallback, true);
  }
});

// --- mergeLiveAndSnapshot -------------------------------------------------------

run('mergeLiveAndSnapshot: live wins on a shared bucket key; snapshot fills gaps; both are labelled', () => {
  const live = [{ bucket: '2026-W35', calls: 100 }];
  const snapshot = [{ bucket: '2026-W35', calls: 999 }, { bucket: '2026-W30', calls: 50 }];
  const merged = mergeLiveAndSnapshot(live, snapshot);
  assert.equal(merged.length, 2);
  const w35 = merged.find((r) => r.bucket === '2026-W35');
  const w30 = merged.find((r) => r.bucket === '2026-W30');
  assert.equal(w35.calls, 100, 'live value wins, not the stale snapshot value');
  assert.equal(w35.source, 'live');
  assert.equal(w30.calls, 50, 'snapshot fills a bucket with no live data');
  assert.equal(w30.source, 'snapshot');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`efficiency-trend.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`efficiency-trend.test: ${passed} passed`);
