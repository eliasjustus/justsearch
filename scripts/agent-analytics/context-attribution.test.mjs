/**
 * Tempdoc 858 §6 — insufficiency is a first-class field in context-attribution.
 *
 * Two floors, both properties of the estimator (see the derivations on
 * `MIN_ATTRIBUTION_SESSIONS` / `MIN_ATTRIBUTION_COVERAGE`): N >= 6 usable sessions, because that is where
 * a distribution-free interval for a median first reaches 95% coverage, and
 * coverage > 0.5, because below half the population observed the median is not
 * identified at all under arbitrary missingness. The observed starved run —
 * N=7 usable of 20 — clears the first and fails the second.
 *
 * Pure functions only — no telemetry directory is read or written.
 *
 * Run with: `node scripts/agent-analytics/context-attribution.test.mjs`
 */

import assert from 'node:assert/strict';
import {
  aggregateResults, formatAggregate,
  MIN_ATTRIBUTION_SESSIONS, MIN_ATTRIBUTION_COVERAGE,
  attributeFromArrays,
} from './context-attribution.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

/** A per-session attribution result, as attributeSession() returns it. */
function session(i, { tool = 0.6, assistant = 0.1, thinking = 0.1, user = 0.1, system = 0.1 } = {}) {
  const total = 100_000;
  return {
    session_id: `s-${i}`,
    total_chars: total,
    estimated_tokens: total / 4,
    categories: {
      tool_outputs:   { chars: tool * total,      pct: tool },
      assistant_text: { chars: assistant * total, pct: assistant },
      thinking:       { chars: thinking * total,  pct: thinking },
      user_messages:  { chars: user * total,      pct: user },
      system:         { chars: system * total,    pct: system },
    },
    top_tools: [{ tool: 'Read', calls: 10, chars: tool * total, pct: tool }],
    subagent_transcripts: 0,
  };
}
const unusable = (i) => ({ session_id: `x-${i}`, error: 'no_transcript' });

const counts = (agg) => ({ count: agg.count, skipped: agg.skipped, coverage: agg.coverage });

try {
  run('below the session floor: insufficient set, conclusion withheld, counts still emitted', () => {
    const agg = aggregateResults([session(1), session(2), session(3), session(4), session(5)]);
    assert.equal(agg.count, 5);
    assert.equal(agg.insufficient, true);
    assert.equal(agg.dominant_category, null);
    assert.deepEqual(counts(agg), { count: 5, skipped: 0, coverage: 1 });
    // The measurements are NOT suppressed — only the conclusion is.
    assert.equal(agg.category_medians.tool_outputs, 0.6);
    assert.equal(agg.top_tools.length, 1);
    assert.equal(agg.total_chars, 500_000);
  });

  run('at the session floor with full coverage: sufficient, conclusion drawn', () => {
    const agg = aggregateResults(Array.from({ length: MIN_ATTRIBUTION_SESSIONS }, (_, i) => session(i)));
    assert.equal(agg.count, MIN_ATTRIBUTION_SESSIONS);
    assert.equal(agg.insufficient, false);
    assert.equal(agg.dominant_category, 'tool_outputs');
  });

  run('the dominant category is the largest median, not the first key', () => {
    const thinkers = Array.from({ length: 8 }, (_, i) =>
      session(i, { tool: 0.1, assistant: 0.1, thinking: 0.6, user: 0.1, system: 0.1 }));
    assert.equal(aggregateResults(thinkers).dominant_category, 'thinking');
  });

  run('the observed starved run (7 usable of 20) is insufficient on coverage despite clearing N', () => {
    const results = [
      ...Array.from({ length: 7 }, (_, i) => session(i)),
      ...Array.from({ length: 13 }, (_, i) => unusable(i)),
    ];
    const agg = aggregateResults(results);
    assert.equal(agg.count, 7);
    assert.ok(agg.count >= MIN_ATTRIBUTION_SESSIONS, 'N alone would have passed');
    assert.equal(agg.coverage, 0.35);
    assert.equal(agg.insufficient, true);
    assert.equal(agg.dominant_category, null);
    assert.equal(agg.skipped, 13);
  });

  run('coverage of exactly one half is insufficient — half observed does not identify a median', () => {
    const half = [
      ...Array.from({ length: 8 }, (_, i) => session(i)),
      ...Array.from({ length: 8 }, (_, i) => unusable(i)),
    ];
    const agg = aggregateResults(half);
    assert.equal(agg.coverage, MIN_ATTRIBUTION_COVERAGE);
    assert.equal(agg.insufficient, true);
    assert.equal(agg.dominant_category, null);
  });

  run('coverage just over one half, with N met, is sufficient', () => {
    const most = [
      ...Array.from({ length: 9 }, (_, i) => session(i)),
      ...Array.from({ length: 8 }, (_, i) => unusable(i)),
    ];
    const agg = aggregateResults(most);
    assert.ok(agg.coverage > MIN_ATTRIBUTION_COVERAGE);
    assert.equal(agg.insufficient, false);
    assert.equal(agg.dominant_category, 'tool_outputs');
  });

  run('the human report leads with the insufficiency notice and prints no dominant category', () => {
    const md = formatAggregate(aggregateResults([session(1), session(2)]), 10);
    assert.match(md, /Insufficient sample: 2 usable of 2/);
    assert.doesNotMatch(md, /Dominant context consumer/);
    // Numbers survive.
    assert.match(md, /Category Distribution/);
    assert.match(md, /60\.0%/);
  });

  run('the human report states the conclusion once the sample supports it', () => {
    const md = formatAggregate(
      aggregateResults(Array.from({ length: MIN_ATTRIBUTION_SESSIONS }, (_, i) => session(i))), 10);
    assert.match(md, /Dominant context consumer: \*\*tool outputs\*\* \(median 60\.0%\)/);
    assert.doesNotMatch(md, /Insufficient sample/);
  });

  run('an all-unusable input still returns the no_valid_sessions error rather than a conclusion', () => {
    const agg = aggregateResults([unusable(1), unusable(2)]);
    assert.equal(agg.count, 0);
    assert.equal(agg.error, 'no_valid_sessions');
  });

  run('matching names/chars zip normally, one entry per tool', () => {
    const byTool = attributeFromArrays(['Read', 'Read', 'Bash'], [100, 200, 50]);
    assert.deepEqual(byTool.get('Read'), { count: 2, chars: 300 });
    assert.deepEqual(byTool.get('Bash'), { count: 1, chars: 50 });
    assert.equal(byTool.size, 2);
  });

  run('886 §12 PR 5b fix: a zip-mismatch attributes one count PER folded tool_result, not one carrying the summed total', () => {
    // A length mismatch (3 local char-scans vs 2 ledger-resolved names) forces the fallback path.
    const byTool = attributeFromArrays(['Read', 'Bash'], [100, 200, 50]);
    const entry = byTool.get('(zip-mismatch)');
    assert.equal(entry.count, 3, 'count must equal the number of folded tool_results, not 1');
    assert.equal(entry.chars, 350, 'chars still sums the full total');
    assert.equal(byTool.size, 1);
  });

  run('a zero-length zip-mismatch (0 vs 0 is not a mismatch, but a real 0-vs-N one) adds nothing when chars is empty', () => {
    const byTool = attributeFromArrays(['Read'], []);
    assert.equal(byTool.has('(zip-mismatch)'), false);
    assert.equal(byTool.size, 0);
  });
} finally {
  if (failures.length) {
    console.error(`context-attribution.test: ${failures.length} failure(s)`);
    for (const f of failures) console.error(`  ${f}`);
    process.exit(1);
  }
  console.log(`context-attribution.test: ${passed} passed`);
}
