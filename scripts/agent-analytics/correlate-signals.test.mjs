/**
 * Tempdoc 858 §6 — insufficiency is a first-class field in correlate-signals.
 *
 * This instrument's conclusion is its own legend: "|r| > 0.30 = meaningful
 * predictor". The floor is therefore the N at which |r| = 0.30 first separates
 * from zero at two-tailed α = 0.05, which is 44 (derivation and the numeric
 * check on `MIN_CORRELATION_PAIRS`). Below it every r still prints and
 * nothing is named a predictor — an r of 0.9 over 3 pairs is not a finding.
 *
 * Pure functions only — no telemetry directory is read or written.
 *
 * Run with: `node scripts/agent-analytics/correlate-signals.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {
  correlate, joinPairs, recomputeOutcomes, pearson, MIN_CORRELATION_PAIRS, MEANINGFUL_R,
} from './correlate-signals.mjs';
import { repoRoot, TELEMETRY_DIR, OUTCOMES_FILE } from './lib/telemetry-io.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

/**
 * n pairs in which tool_failure_rate tracks the outcome exactly (r = -1) and
 * every other signal is constant (r = 0), so the predictor list is unambiguous.
 */
function pairs(n) {
  return Array.from({ length: n }, (_, i) => ({
    sid: `s-${i}`,
    signals: { tool_failure_rate: i / n, bash_fileop_pct: 0.25 },
    y: 1 - i / n,
  }));
}

try {
  run('below the floor: insufficient set, r values still computed, predictor list empty', () => {
    const res = correlate(pairs(10));
    assert.equal(res.n, 10);
    assert.equal(res.min_pairs, MIN_CORRELATION_PAIRS);
    assert.equal(res.insufficient, true);
    // The measurement survives — a perfect -1 is still reported.
    const measured = res.correlations.find(c => c.name === 'tool_failure_rate').r;
    assert.ok(Math.abs(measured - -1) < 1e-9, `expected r ≈ -1, got ${measured}`);
    assert.equal(res.correlations.length, 10);
    // The conclusion does not.
    assert.deepEqual(res.predictors, []);
  });

  run('at the floor: sufficient, and the predictor is named', () => {
    const res = correlate(pairs(MIN_CORRELATION_PAIRS));
    assert.equal(res.n, MIN_CORRELATION_PAIRS);
    assert.equal(res.insufficient, false);
    assert.deepEqual(res.predictors, ['tool_failure_rate']);
  });

  run('one pair below the floor is still insufficient', () => {
    assert.equal(correlate(pairs(MIN_CORRELATION_PAIRS - 1)).insufficient, true);
  });

  run('a sufficient sample with no strong signal names no predictor — the gate is N, not the verdict', () => {
    // Precision guard: `predictors` must key off |r| as well as sufficiency.
    const flat = Array.from({ length: MIN_CORRELATION_PAIRS }, (_, i) => ({
      sid: `s-${i}`, signals: { tool_failure_rate: 0.1, bash_fileop_pct: 0.25 }, y: i % 2,
    }));
    const res = correlate(flat);
    assert.equal(res.insufficient, false);
    assert.deepEqual(res.predictors, []);
  });

  run('a signal exactly at the meaningful threshold counts once the sample supports it', () => {
    const at = correlate(pairs(MIN_CORRELATION_PAIRS));
    assert.ok(Math.abs(at.correlations[0].r) >= MEANINGFUL_R);
  });

  run('correlations are ranked by |r|, so the strongest signal leads regardless of sign', () => {
    const res = correlate(pairs(MIN_CORRELATION_PAIRS));
    assert.equal(res.correlations[0].name, 'tool_failure_rate');
    assert.ok(res.correlations[0].r < 0 && Math.abs(res.correlations[0].r - -1) < 1e-9);
  });

  run('joinPairs reads task_completion from the post-622 inference block and skips unjudged rows', () => {
    const scores = new Map([
      ['a', { session_id: 'a', signals: { tool_failure_rate: 0.1 } }],
      ['b', { session_id: 'b', signals: { tool_failure_rate: 0.2 } }],
      ['c', { session_id: 'c', signals: { tool_failure_rate: 0.3 } }],
      ['d', { session_id: 'd', signals: { tool_failure_rate: 0.4 } }],
    ]);
    const outcomes = new Map([
      ['a', { session_id: 'a', inference: { task_completion: 'complete' } }],
      ['b', { session_id: 'b', inference: { task_completion: 'partial' } }],
      ['c', { session_id: 'c', inference: null }],                                  // unjudged
      ['d', { session_id: 'd', inference: { task_completion: 'not-an-enum-value' } }],
    ]);
    assert.deepEqual(joinPairs(scores, outcomes).map(p => [p.sid, p.y]), [['a', 1.0], ['b', 0.5]]);
  });

  run('pearson is undefined below 2 points and returns 0 rather than a fabricated r', () => {
    // The <2 case stays a hard refusal in main(); this pins why.
    assert.equal(pearson([1], [1]), 0);
    assert.equal(pearson([], []), 0);
  });

  run('MIN_CORRELATION_PAIRS is the smallest N whose critical r is at or below the legend threshold', () => {
    // Re-derives the constant from the exact null density f(r) ∝ (1-r²)^((N-4)/2)
    // rather than trusting the comment: N=43 -> 0.3008 (above), N=44 -> 0.2973.
    const criticalR = (n) => {
      const a = (n - 4) / 2;
      const f = (x) => Math.pow(1 - x * x, a);
      const simpson = (lo, hi, m) => {
        const h = (hi - lo) / m; let s = f(lo) + f(hi);
        for (let i = 1; i < m; i++) s += f(lo + i * h) * (i % 2 ? 4 : 2);
        return s * h / 3;
      };
      const total = simpson(-1 + 1e-12, 1 - 1e-12, 20000);
      let lo = 0, hi = 0.999;
      for (let i = 0; i < 80; i++) {
        const mid = (lo + hi) / 2;
        if (2 * simpson(mid, 1 - 1e-12, 20000) / total > 0.05) lo = mid; else hi = mid;
      }
      return (lo + hi) / 2;
    };
    // Anchor against the published table value for df=40 (N=42): r_crit = 0.3044.
    assert.ok(Math.abs(criticalR(42) - 0.3044) < 0.001, `criticalR(42)=${criticalR(42)}`);
    assert.ok(criticalR(MIN_CORRELATION_PAIRS) <= MEANINGFUL_R);
    assert.ok(criticalR(MIN_CORRELATION_PAIRS - 1) > MEANINGFUL_R);
  });
  // --- The recompute path (tempdoc 858 §4.2 item 2 / §3) ---
  //
  // Reading outcomes.ndjson made this instrument structurally dead once §3 made
  // outcomes a view: the file is opt-in and normally absent, so the join yielded
  // nothing and main() hard-exited before printing a single table. The floor
  // derived above was unreachable in practice.

  const OUTCOMES_PATH = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);

  /** Synthetic join inputs — the shape loadJoinInputs() returns, no disk needed. */
  const joinInputs = () => ({
    sessions: new Map([
      ['scored-judged', [{ event: 'post_tool_use', tool_name: 'Edit', ts: '2026-08-19T00:00:00Z' }]],
      ['scored-unjudged', [{ event: 'post_tool_use', tool_name: 'Edit', ts: '2026-08-19T01:00:00Z' }]],
    ]),
    mergeRecords: [],
    judgeMap: new Map([
      ['scored-judged', { ts: '2026-08-01T12:00:00.000Z', task_completion: 'complete', task_type: 'feature' }],
    ]),
  });
  const scores = () => new Map([
    ['scored-judged', { session_id: 'scored-judged', signals: { tool_failure_rate: 0.1 } }],
    ['scored-unjudged', { session_id: 'scored-unjudged', signals: { tool_failure_rate: 0.2 } }],
  ]);

  run('the join is fed by recomputation, so a pair survives with no outcomes.ndjson on disk', () => {
    const existedBefore = fs.existsSync(OUTCOMES_PATH);
    const outcomes = recomputeOutcomes([...scores().keys()], joinInputs());
    const pairs = joinPairs(scores(), outcomes);
    // The judged session joins; the unjudged one is correctly dropped.
    assert.deepEqual(pairs.map(p => [p.sid, p.y]), [['scored-judged', 1.0]]);
    // Nothing was read from or written to the view's opt-in report file.
    assert.equal(fs.existsSync(OUTCOMES_PATH), existedBefore);
  });

  run('recomputed records expose task_type for the per-type stratification', () => {
    const outcomes = recomputeOutcomes(['scored-judged', 'scored-unjudged'], joinInputs());
    assert.equal(outcomes.get('scored-judged').inference.task_type, 'feature');
    assert.equal(outcomes.get('scored-unjudged').inference, null);
  });

  run('reading an absent outcomes store yields no pairs — the failure the recompute replaces', () => {
    // Pins WHY the wiring changed: an empty map is what loadNdjsonMap returns
    // for a missing file, and it starves the join to zero.
    assert.deepEqual(joinPairs(scores(), new Map()), []);
  });

  run('the module reads no outcomes store at all — the structural invariant §3 sets', () => {
    // A source-text assertion on purpose. The defect lived on ONE line inside
    // main(), which cannot be unit-tested without disk I/O and a process.exit,
    // and reverting that line breaks nothing behaviourally testable — so the
    // invariant "this consumer does not read the view's opt-in report" is
    // pinned directly. This is the assertion that would have caught the
    // original dead-consumer bug.
    const src = fs.readFileSync(new URL('./correlate-signals.mjs', import.meta.url), 'utf8');
    const code = src.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '');   // strip comments
    assert.doesNotMatch(code, /OUTCOMES_FILE|outcomes\.ndjson/,
      'correlate-signals must not read the outcomes report; recompute via outcomeForSession');
    assert.match(code, /recomputeOutcomes\(\[\.\.\.scoresMap\.keys\(\)\], loadJoinInputs\(\)\)/,
      'the join must be sourced from a hoisted recomputation');
  });

  run('recomputeOutcomes throws when `inputs` is omitted rather than silently reloading per session', () => {
    // outcomeForSession self-loads on a missing `inputs`, so an unguarded
    // omission would be an invisible N-times-the-corpus reparse.
    assert.throws(() => recomputeOutcomes(['scored-judged']), /`inputs` is required/);
    assert.throws(() => recomputeOutcomes(['scored-judged'], null), /hoisted out of the loop/);
  });
} finally {
  if (failures.length) {
    console.error(`correlate-signals.test: ${failures.length} failure(s)`);
    for (const f of failures) console.error(`  ${f}`);
    process.exit(1);
  }
  console.log(`correlate-signals.test: ${passed} passed`);
}
