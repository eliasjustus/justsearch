/**
 * Tempdoc 841 — unit tests for the cache-efficiency reader's pure classifiers.
 *
 * Precision matters in both directions. Calling ordinary per-turn EXTENSION an
 * "invalidation" invents a crisis (extension is 61.5k turns; invalidation is
 * ~556 events — confusing them buries the finding under noise). Calling a real
 * invalidation "extension" hides the one class that costs more than every
 * normal turn combined. The `in-ttl-undetermined` residual is load-bearing too:
 * it must never swallow a case we can positively name, or the reader will
 * quietly under-report causes it actually knows.
 *
 * Run with: `node scripts/agent-analytics/cache-efficiency.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  classifyWrite,
  invalidationCause,
  dedupeUsageTurns,
  analyseTranscript,
  emptyReport,
  TTL_SEC_BY_KIND,
  INVALIDATION_READ_DROP_RATIO,
} from './cache-efficiency.mjs';

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

// --- classifyWrite -----------------------------------------------------------

run('zero cache_read is a cold prefix', () => {
  assert.equal(classifyWrite(null, { read: 0, write: 120_000 }), 'cold');
});

run('zero cache_read is cold even when a previous turn read a lot', () => {
  // a genuinely cold turn mid-file (new subagent appended to the same store)
  assert.equal(classifyWrite({ read: 500_000, write: 0 }, { read: 0, write: 90_000 }), 'cold');
});

run('growing prefix is extension, not invalidation', () => {
  assert.equal(classifyWrite({ read: 100_000, write: 2_000 }, { read: 104_000, write: 2_500 }), 'extension');
});

run('flat prefix is extension', () => {
  assert.equal(classifyWrite({ read: 100_000, write: 0 }, { read: 100_000, write: 1_200 }), 'extension');
});

run('small downward jitter stays extension (does not trip the ratio)', () => {
  // 96% of prior read — real transcripts jitter without losing the prefix
  assert.equal(classifyWrite({ read: 100_000, write: 0 }, { read: 96_000, write: 3_000 }), 'extension');
});

run('collapse to a small floor is an invalidation', () => {
  // the shape tempdoc 841 4 found 293/305 times: body lost, falls back to ~30k
  assert.equal(classifyWrite({ read: 273_000, write: 0 }, { read: 31_000, write: 96_000 }), 'invalidation');
});

run('first turn with a non-zero read is extension, not invalidation', () => {
  // a resumed session legitimately reads an existing prefix with no prev turn
  assert.equal(classifyWrite(null, { read: 250_000, write: 4_000 }), 'extension');
});

run('the drop ratio boundary is exclusive', () => {
  const prev = { read: 100_000, write: 0 };
  const atBoundary = { read: 100_000 * INVALIDATION_READ_DROP_RATIO, write: 60_000 };
  assert.equal(classifyWrite(prev, atBoundary), 'extension');
  assert.equal(classifyWrite(prev, { read: atBoundary.read - 1, write: 60_000 }), 'invalidation');
});

// --- invalidationCause -------------------------------------------------------

const M = 'claude-opus-5';

run('compaction wins over everything else', () => {
  // positive evidence must beat the circumstantial TTL test, even on a long gap
  const why = invalidationCause({
    prev: { read: 800_000, model: M }, cur: { read: 20_000, model: M },
    gapSec: 99_999, kind: 'main', compactSeen: true,
  });
  assert.equal(why, 'compaction');
});

run('model switch is named even when the gap also exceeds TTL', () => {
  const why = invalidationCause({
    prev: { read: 400_000, model: 'claude-fable-5' }, cur: { read: 30_000, model: M },
    gapSec: 7_200, kind: 'main', compactSeen: false,
  });
  assert.equal(why, 'model-switch');
});

run('main session past the 1h tier is ttl-expiry', () => {
  const why = invalidationCause({
    prev: { read: 400_000, model: M }, cur: { read: 30_000, model: M },
    gapSec: TTL_SEC_BY_KIND.main + 1, kind: 'main', compactSeen: false,
  });
  assert.equal(why, 'ttl-expiry');
});

run('the SAME gap is ttl-expiry for a subagent but undetermined for main', () => {
  // this asymmetry is the whole 5m-vs-1h finding; if it ever collapses to one
  // threshold the delegation numbers silently become wrong
  const args = {
    prev: { read: 300_000, model: M }, cur: { read: 25_000, model: M },
    gapSec: 600, compactSeen: false,
  };
  assert.equal(invalidationCause({ ...args, kind: 'subagent' }), 'ttl-expiry');
  assert.equal(invalidationCause({ ...args, kind: 'main' }), 'in-ttl-undetermined');
});

run('within TTL with no other signal is undetermined, not attributed', () => {
  const why = invalidationCause({
    prev: { read: 273_000, model: M }, cur: { read: 31_000, model: M },
    gapSec: 8, kind: 'main', compactSeen: false,
  });
  assert.equal(why, 'in-ttl-undetermined');
});

run('a missing timestamp does not fabricate a ttl-expiry', () => {
  const why = invalidationCause({
    prev: { read: 200_000, model: M }, cur: { read: 30_000, model: M },
    gapSec: null, kind: 'subagent', compactSeen: false,
  });
  assert.equal(why, 'in-ttl-undetermined');
});

run('a null model on either side is not counted as a model switch', () => {
  const why = invalidationCause({
    prev: { read: 200_000, model: null }, cur: { read: 30_000, model: M },
    gapSec: 10, kind: 'main', compactSeen: false,
  });
  assert.equal(why, 'in-ttl-undetermined');
});

// --- dedupeUsageTurns --------------------------------------------------------

run('streaming partials collapse to the LAST snapshot', () => {
  // tempdoc 745 item B bug 2: partials grow, so first-wins undercounts output
  const out = dedupeUsageTurns([
    { messageId: 'a', requestId: 'r1', output: 5 },
    { messageId: 'a', requestId: 'r1', output: 5 },
    { messageId: 'a', requestId: 'r1', output: 291 },
  ]);
  assert.equal(out.length, 1);
  assert.equal(out[0].output, 291);
});

run('first-occurrence ORDER is preserved when a later turn is deduped', () => {
  // every classifier here is sequential, so re-ordering would corrupt the
  // read-drop comparison even if the totals stayed right
  const out = dedupeUsageTurns([
    { messageId: 'a', requestId: 'r1', read: 10 },
    { messageId: 'b', requestId: 'r2', read: 20 },
    { messageId: 'a', requestId: 'r1', read: 11 },
  ]);
  assert.deepEqual(out.map((t) => t.messageId), ['a', 'b']);
  assert.equal(out[0].read, 11);
});

run('distinct requestIds on the same messageId stay separate', () => {
  const out = dedupeUsageTurns([
    { messageId: 'a', requestId: 'r1', read: 1 },
    { messageId: 'a', requestId: 'r2', read: 2 },
  ]);
  assert.equal(out.length, 2);
});

// --- analyseTranscript: compaction survives the partial-dedup ----------------

/**
 * Regression test for a real bug. The compaction flag rides the FIRST streaming
 * partial of the post-compaction message; `dedupeUsageTurns` keeps the LAST
 * partial. A per-line flag was therefore silently dropped, and the compaction
 * counter read 0 across a corpus holding 16 genuine boundaries. The pure
 * classifiers could not catch this — the defect lived in the assembly step, so
 * the test has to go through `analyseTranscript`.
 */
run('a compaction boundary is still attributed after partial-dedup', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cache-eff-'));
  const file = path.join(dir, 'session.jsonl');
  const lines = [
    // a normal turn with a large readable prefix
    { type: 'assistant', timestamp: '2026-08-18T00:00:00.000Z', requestId: 'r1',
      message: { id: 'm1', model: 'claude-opus-5', usage: { cache_read_input_tokens: 400_000, cache_creation_input_tokens: 2_000, output_tokens: 10 } } },
    // the compaction boundary itself
    { type: 'system', subtype: 'compact_boundary', timestamp: '2026-08-18T00:00:05.000Z' },
    // post-compaction message, streamed as two partials; only the FIRST is
    // adjacent to the boundary, and the SECOND is what dedup keeps
    { type: 'assistant', timestamp: '2026-08-18T00:00:06.000Z', requestId: 'r2',
      message: { id: 'm2', model: 'claude-opus-5', usage: { cache_read_input_tokens: 30_000, cache_creation_input_tokens: 90_000, output_tokens: 5 } } },
    { type: 'assistant', timestamp: '2026-08-18T00:00:07.000Z', requestId: 'r2',
      message: { id: 'm2', model: 'claude-opus-5', usage: { cache_read_input_tokens: 30_000, cache_creation_input_tokens: 90_000, output_tokens: 240 } } },
  ];
  fs.writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n');

  const report = emptyReport();
  analyseTranscript(file, report);
  fs.rmSync(dir, { recursive: true, force: true });

  assert.equal(report.compaction.boundaries, 1, 'boundary should be attributed');
  assert.equal(report.invalidationCause.compaction?.n, 1, 'invalidation should be caused by compaction');
  assert.ok(!report.invalidationCause['in-ttl-undetermined'], 'must not fall through to the residual');
  // last-wins dedup must also have kept the grown output count
  assert.equal(report.tokens.output, 250);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`cache-efficiency.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`cache-efficiency.test: ${passed} passed`);
