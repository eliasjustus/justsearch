/**
 * cost-session.test.mjs — unit tests for `reconcileSessions` (tempdoc 886
 * §12 PR 2, `cost-session.mjs --reconcile`), the OTLP-priced vs
 * transcript-priced comparison (858 §9.1).
 *
 * Every input here is a synthetic, in-process pair of records — this test
 * NEVER reads `tmp/agent-telemetry/` or any real transcript/OTLP directory,
 * matching the injected-loader contract `reconcileSessions` was written to.
 *
 * Run with: `node scripts/agent-analytics/cost-session.test.mjs`
 */

import assert from 'node:assert/strict';
import { reconcileSessions } from './cost-session.mjs';

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

// --- sessions present in both sources -----------------------------------

run('a session in both sources gets otlp$, transcript$, and a computed delta%', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 10, model: 'claude-opus-5' }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 11, model: 'claude-opus-5' }];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.equal(common.length, 1);
  assert.equal(common[0].otlp_cost_usd, 10);
  assert.equal(common[0].transcript_cost_usd, 11);
  assert.equal(common[0].delta_pct, 10); // (11-10)/10 * 100
  assert.deepEqual(common[0].residue, []);
});

run('reconcileSessions accepts otlpRecords as a Map (loadCostsFromOtlp shape) or an array', () => {
  const otlpMap = new Map([['s1', { session_id: 's1', cost_usd: 5, model: 'claude-sonnet-5' }]]);
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 5, model: 'claude-sonnet-5' }];
  const { common } = reconcileSessions({ otlpRecords: otlpMap, transcriptRecords });
  assert.equal(common.length, 1);
  assert.equal(common[0].delta_pct, 0);
});

run('delta_pct is null when otlp cost is exactly 0 (division-by-zero avoided, not NaN)', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 0, model: 'claude-opus-5' }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 3, model: 'claude-opus-5' }];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.equal(common[0].delta_pct, null);
});

// --- residue causes ------------------------------------------------------

run('names transcript:unknown-model residue for a MISSING_MODEL_KEY transcript row', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 4, model: 'claude-opus-5' }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 0, model: '(missing-model)' }];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.deepEqual(common[0].residue, ['transcript:unknown-model']);
});

run('names transcript:unknown-model residue for a literal "<synthetic>" model row', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 4, model: 'claude-opus-5' }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 0, model: '<synthetic>' }];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.deepEqual(common[0].residue, ['transcript:unknown-model']);
});

run('names otlp:unknown-model residue for a null/absent OTLP model', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 0, model: null }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 5, model: 'claude-opus-5' }];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.deepEqual(common[0].residue, ['otlp:unknown-model']);
});

run('names both residue causes when both sides are unpriced', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 0, model: null }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: 0, model: '(missing-model)' }];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.deepEqual(common[0].residue.sort(), ['otlp:unknown-model', 'transcript:unknown-model']);
});

// --- sessions missing on one side ------------------------------------------

run('a session with OTLP cost but no transcript record lands in otlpOnly', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 3, model: 'claude-opus-5' }];
  const { common, otlpOnly } = reconcileSessions({ otlpRecords, transcriptRecords: [] });
  assert.equal(common.length, 0);
  assert.equal(otlpOnly.length, 1);
  assert.equal(otlpOnly[0].session_id, 's1');
});

run('a session with a transcript record whose total_cost_usd is null (no transcript found) also lands in otlpOnly', () => {
  const otlpRecords = [{ session_id: 's1', cost_usd: 3, model: 'claude-opus-5' }];
  const transcriptRecords = [{ session_id: 's1', total_cost_usd: null, model: null, reason: 'no_transcript_path' }];
  const { common, otlpOnly } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.equal(common.length, 0);
  assert.equal(otlpOnly.length, 1);
});

run('a session with a transcript cost but no OTLP record lands in transcriptOnly', () => {
  const transcriptRecords = [{ session_id: 's2', total_cost_usd: 7, model: 'claude-sonnet-5' }];
  const { common, transcriptOnly } = reconcileSessions({ otlpRecords: [], transcriptRecords });
  assert.equal(common.length, 0);
  assert.equal(transcriptOnly.length, 1);
  assert.equal(transcriptOnly[0].session_id, 's2');
});

run('a session missing on both sides (no cost anywhere) is not fabricated in any bucket', () => {
  const transcriptRecords = [{ session_id: 's3', total_cost_usd: null, model: null, reason: 'no_transcript_path' }];
  const { common, otlpOnly, transcriptOnly } = reconcileSessions({ otlpRecords: [], transcriptRecords });
  assert.equal(common.length, 0);
  assert.equal(otlpOnly.length, 0);
  assert.equal(transcriptOnly.length, 0);
});

// --- sorting -----------------------------------------------------------------

run('common rows are sorted by |delta_pct| descending', () => {
  const otlpRecords = [
    { session_id: 's1', cost_usd: 10, model: 'claude-opus-5' },
    { session_id: 's2', cost_usd: 10, model: 'claude-opus-5' },
  ];
  const transcriptRecords = [
    { session_id: 's1', total_cost_usd: 11, model: 'claude-opus-5' }, // +10%
    { session_id: 's2', total_cost_usd: 15, model: 'claude-opus-5' }, // +50%
  ];
  const { common } = reconcileSessions({ otlpRecords, transcriptRecords });
  assert.equal(common[0].session_id, 's2');
  assert.equal(common[1].session_id, 's1');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`cost-session.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`cost-session.test: ${passed} passed`);
