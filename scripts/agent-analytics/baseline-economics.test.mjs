/**
 * Tempdoc 743 Phase 1 — unit tests for baseline-economics.mjs (transcript-first
 * per-session cost -> merge-link join -> windowed report).
 *
 * Run with: `node --test scripts/agent-analytics/baseline-economics.test.mjs`
 * Exits non-zero on any failure (same manual-runner style as fold-observations.test.mjs
 * and note-observation.test.mjs in this directory).
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  parseArgs,
  loadExclusionMatcher,
  loadMerges,
  classifyMerge,
  isoWeekKey,
  discoverSessions,
  computeSessionCost,
  costSessionsChronologically,
  buildReport,
  formatMarkdown,
  DEFAULT_SINCE,
} from './baseline-economics.mjs';
import { parseTranscriptTokens, parseSessionTokens, findPricing, isKnownModel, isFastPricedCorrectly, MISSING_MODEL_KEY } from './lib/transcript-cost.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}
async function runAsync(label, fn) {
  try { await fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'baseline-econ-test-'));

function writeTranscript(dir, sessionId, lines) {
  const file = path.join(dir, `${sessionId}.jsonl`);
  fs.writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
  return file;
}

function assistantLine(model, usage) {
  return { type: 'assistant', message: { model, usage } };
}
function assistantLineWithId(id, model, usage) {
  return { type: 'assistant', message: { id, model, usage } };
}
// Full-fidelity assistant entry: `requestId` and `timestamp` live at ENTRY level
// (verified against real transcripts, tempdoc 745 item B), not inside `message`.
function assistantEntry({ id, requestId, model, usage, timestamp }) {
  return { type: 'assistant', requestId, timestamp, message: { id, model, usage } };
}

async function main() {
  // --- parseArgs ---
  run('parseArgs applies defaults', () => {
    const o = parseArgs([]);
    assert.equal(o.since, DEFAULT_SINCE);
    assert.equal(o.until, null);
    assert.equal(o.json, false);
    assert.equal(o.md, false);
  });
  run('parseArgs reads flags', () => {
    const o = parseArgs(['--since', '2026-07-01', '--until', '2026-07-10', '--merges', '/x/y', '--json', '--projects-root', '/p']);
    assert.equal(o.since, '2026-07-01');
    assert.equal(o.until, '2026-07-10');
    assert.equal(o.merges, '/x/y');
    assert.equal(o.json, true);
    assert.equal(o.projectsRoot, '/p');
  });

  // --- pricing math incl. cache tiers (via shared lib) ---
  run('parseTranscriptTokens computes cost across all four token tiers for a known model', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'pricing-'));
    const file = writeTranscript(dir, 'sess-pricing', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-sonnet-5', {
        input_tokens: 100000, output_tokens: 20000,
        cache_creation_input_tokens: 50000, cache_read_input_tokens: 200000,
      }),
    ]);
    const r = parseTranscriptTokens(file);
    // Sonnet-5 flat $2/$10 (tempdoc 841 — was $3/$15 here while the cancelled
    // 2026-09-01 cliff made undated turns resolve to the "enduring standard" row):
    // (0.1*2.0)+(0.02*10.0)+(0.05*2.5)+(0.2*0.20) = 0.2+0.2+0.125+0.04 = 0.565
    assert.equal(r.cost_usd.toFixed(4), '0.5650');
    assert.equal(r.turns, 1);
    assert.equal(r.model, 'claude-sonnet-5');
    assert.ok(r.by_model['claude-sonnet-5']);
    assert.equal(r.by_model['claude-sonnet-5'].cost_usd.toFixed(4), '0.5650');
  });
  run('parseTranscriptTokens sums multiple turns and skips unparseable/non-assistant lines', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'pricing2-'));
    const file = writeTranscript(dir, 'sess-multi', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-haiku-4-5', { input_tokens: 1000, output_tokens: 1000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
      assistantLine('claude-haiku-4-5', { input_tokens: 1000, output_tokens: 1000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
      { type: 'user', message: { content: 'not assistant, ignored' } },
    ]);
    fs.appendFileSync(file, 'not even json\n');
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 2);
    assert.equal(r.input_tokens, 2000);
    assert.equal(r.output_tokens, 2000);
    // haiku: input 1.0, output 5.0 per 1M -> per turn (0.001*1.0)+(0.001*5.0)=0.006; x2 = 0.012
    assert.equal(r.cost_usd.toFixed(4), '0.0120');
  });

  // --- Finding 1 regression: multi-content-block lines sharing one message.id ---
  run('parseTranscriptTokens counts one message.id exactly once despite N content-block lines sharing identical usage', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'dedup-'));
    const usage = { input_tokens: 1000, output_tokens: 200, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const file = writeTranscript(dir, 'sess-dedup', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLineWithId('msg-A', 'claude-sonnet-5', usage), // content block 1 (e.g. tool_use)
      assistantLineWithId('msg-A', 'claude-sonnet-5', usage), // content block 2 — SAME turn, duplicate usage snapshot
      assistantLineWithId('msg-A', 'claude-sonnet-5', usage), // content block 3
      assistantLineWithId('msg-B', 'claude-sonnet-5', usage), // a genuinely separate turn
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 2); // msg-A counted once, msg-B counted once — NOT 4
    assert.equal(r.input_tokens, 2000); // 1000 x 2 unique turns, not x4
    assert.equal(r.output_tokens, 400);
    assert.equal(r.by_model['claude-sonnet-5'].turns, 2);
  });
  run('parseTranscriptTokens falls back to counting individually when message.id is absent', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'noid-'));
    const usage = { input_tokens: 100, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const file = writeTranscript(dir, 'sess-noid', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      { type: 'assistant', message: { model: 'claude-sonnet-5', usage } },
      { type: 'assistant', message: { model: 'claude-sonnet-5', usage } },
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 2); // no id to dedup on — counted individually (documented fallback)
  });

  // --- 745 item B bug 2: repeated snapshots are streaming partials — LAST wins ---
  run('parseTranscriptTokens takes the LAST usage snapshot of a repeated turn, not the first', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'lastsnap-'));
    // Reproduced verbatim from a real subagent transcript: one message id whose
    // persisted snapshots GROW as the response streams (5,5,5,5,5,291).
    const partials = [5, 5, 5, 5, 5, 291].map((out) => assistantEntry({
      id: 'msg_011CcyTURQe2GYCGo9SgEiYt', requestId: 'req_011CcmQpv7hzPv5HVU23u2Dj',
      model: 'claude-haiku-4-5', timestamp: '2026-07-10T00:00:00.000Z',
      usage: { input_tokens: 10, output_tokens: out, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
    }));
    const file = writeTranscript(dir, 'sess-lastsnap', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      ...partials,
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 1);
    assert.equal(r.output_tokens, 291); // NOT 5 (first-wins) and NOT 311 (sum)
    assert.equal(r.input_tokens, 10);
    // The snapshot is taken WHOLESALE, and for a monotonic stream last == max —
    // pinning both halves of the D4 decision (no per-field max, which would
    // fabricate a snapshot that never existed).
    assert.equal(r.output_tokens, Math.max(...[5, 5, 5, 5, 5, 291]));
    // haiku: (10/1M)*1.0 + (291/1M)*5.0 = 0.00001 + 0.001455
    assert.equal(r.cost_usd.toFixed(6), '0.001465');
  });

  // --- 745 F-11: an all-zero snapshot must NOT displace a real one ---
  // The counterexample to D4's "last == max for monotonic streams" premise. Shape
  // reproduced verbatim from the 126-session corpus (610 keys, 529.8M cache_read
  // at risk). ccusage has this bug too, so the differential CANNOT catch it — this
  // test is the only thing standing between us and a silent 2% loss.
  run('an all-zero trailing snapshot does not displace the real usage (F-11)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'zerotail-'));
    const real = { input_tokens: 2, output_tokens: 760, cache_creation_input_tokens: 290, cache_read_input_tokens: 804035 };
    const zero = { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const mk = (usage) => assistantEntry({
      id: 'msg_01Qfxz8e8Z542YgWfyDhfxyo', requestId: 'req_011CcbU',
      model: 'claude-haiku-4-5', timestamp: '2026-07-10T00:00:00.000Z', usage,
    });
    const file = writeTranscript(dir, 'sess-zerotail', [
      mk(real), mk(real), mk(real),   // the turn's real usage
      mk(zero), mk(zero), mk(zero),   // re-carried placeholders — naive last-wins takes these
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 1);
    assert.equal(r.cache_read_tokens, 804035); // NOT 0 — the bug this pins
    assert.equal(r.output_tokens, 760);
    assert.equal(r.cache_write_tokens, 290);
    assert.equal(r.input_tokens, 2);
  });

  // --- 745 F-14: fast mode is recorded per-turn and must not silently underprice ---
  // `message.usage.speed` is "standard" | "fast" | null. Verified corpus-wide
  // 2026-07-16: 59,332 turns, all "standard", zero "fast" — so this prices nothing
  // today and is purely forward-looking. Without it one /fast toggle understates
  // Opus 4.8 by 2x with no symptom.
  run('a fast-mode Opus turn is priced at the fast rate, not the standard one (F-14)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'fast-'));
    const usage = (speed) => ({
      input_tokens: 1_000_000, output_tokens: 1_000_000,
      cache_creation_input_tokens: 0, cache_read_input_tokens: 0, speed,
    });
    const mk = (id, speed) => assistantEntry({
      id, requestId: 'req_' + id, model: 'claude-opus-4-8',
      timestamp: '2026-07-10T00:00:00.000Z', usage: usage(speed),
    });
    const std = parseTranscriptTokens(writeTranscript(dir, 'sess-std', [mk('m1', 'standard')]));
    const fast = parseTranscriptTokens(writeTranscript(dir, 'sess-fast', [mk('m2', 'fast')]));
    // opus-4-8 standard $5/$25 -> $30 ; fast $10/$50 -> $60
    assert.equal(std.cost_usd.toFixed(2), '30.00');
    assert.equal(fast.cost_usd.toFixed(2), '60.00', 'fast mode must bill at the premium, not standard');
  });

  run('speed=null / "standard" / an unknown speed all resolve to standard pricing (F-14)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'speed-fallback-'));
    const mk = (id, speed) => assistantEntry({
      id, requestId: 'req_' + id, model: 'claude-opus-4-8', timestamp: '2026-07-10T00:00:00.000Z',
      usage: {
        input_tokens: 1_000_000, output_tokens: 0,
        cache_creation_input_tokens: 0, cache_read_input_tokens: 0,
        ...(speed === undefined ? {} : { speed }),
      },
    });
    for (const [label, speed] of [['absent', undefined], ['null', null], ['standard', 'standard'], ['unknown', 'turbo']]) {
      const r = parseTranscriptTokens(writeTranscript(dir, 'sess-' + label, [mk('m-' + label, speed)]));
      assert.equal(r.cost_usd.toFixed(2), '5.00', `speed=${label} must price as standard`);
    }
  });

  run('a fast turn on a model with no fast row falls back to standard but is surfaced, not silent (F-14)', () => {
    // Opus 4.6 withdrew fast mode (2026-06-29): a "fast" turn there is not billed
    // at a premium, so standard is correct — but the caller must still be able to
    // SEE the mismatch rather than trust a plausible number.
    assert.equal(isFastPricedCorrectly('claude-opus-4-8', 'fast'), true);
    assert.equal(isFastPricedCorrectly('claude-opus-4-6', 'fast'), false, 'no fast row => must be surfaceable');
    assert.equal(isFastPricedCorrectly('claude-opus-4-6', 'standard'), true);
    assert.equal(isFastPricedCorrectly('claude-sonnet-5', null), true);
    // tempdoc 841: Opus 5 supports fast mode ($10/$50); Opus 4.7 does NOT — a
    // "fast" 4.7 request errors, so the old $30/$150 row priced an impossible
    // state at 3x the real premium. Both directions are asserted so neither
    // silently comes back.
    assert.equal(isFastPricedCorrectly('claude-opus-5', 'fast'), true);
    assert.equal(findPricing('claude-opus-5', null, 'fast').input, 10.0);
    assert.equal(findPricing('claude-opus-5', null, 'fast').output, 50.0);
    assert.equal(isFastPricedCorrectly('claude-opus-4-7', 'fast'), false, 'fast mode is unavailable on 4.7');
    assert.equal(findPricing('claude-opus-4-7', null, 'fast').input, 5.0, 'must fall back to standard, not $30');
    // and the fallback itself must not invent a premium
    assert.equal(findPricing('claude-opus-4-6', null, 'fast').input, 5.0);
  });

  // --- 745 F-13: the flat cache field is NOT authoritative ---
  // Measured: 1,313 snapshots carry tiered writes with flat == 0, hiding 16,992,717
  // cache-write tokens from a flat-only reader. So a tiered-only snapshot is REAL and
  // must never read as "all-zero" — otherwise a true placeholder displaces it, and it
  // goes unclaimed in the cross-file scope and gets double-counted. Pins usageIsAllZero
  // to splitCacheWrite against a "simplify it back to the flat field" revert.
  run('a tiered-only snapshot (flat==0) is real usage, not an all-zero placeholder (F-13)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'tiered-only-'));
    // flat==0 but the tiered object carries real 1h writes — the shape of all 1,313.
    const tieredOnly = {
      input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0,
      cache_creation_input_tokens: 0,
      cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 5000 },
    };
    const zero = {
      input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0,
      cache_creation_input_tokens: 0,
      cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 0 },
    };
    const mk = (usage) => assistantEntry({
      id: 'msg_tiered', requestId: 'req_tiered',
      model: 'claude-haiku-4-5', timestamp: '2026-07-10T00:00:00.000Z', usage,
    });
    const file = writeTranscript(dir, 'sess-tiered', [mk(tieredOnly), mk(zero)]);
    const r = parseTranscriptTokens(file);
    // The placeholder must NOT displace the tiered-only snapshot.
    assert.equal(r.cache_write_tokens, 5000, 'tiered-only writes must survive a trailing placeholder');
    // ...and it must be priced at the 1h rate (haiku 1h = 2.0x input = $2/MTok).
    assert.equal(r.cost_usd.toFixed(6), (5000 / 1_000_000 * 2.0).toFixed(6));
  });

  // --- 745 F-11 review ship-blocker: the guard must hold ACROSS files, not just within one ---
  // Found by the independent reviewer. `seen` was marked for every key, including
  // zero-only ones, so a placeholder in file B claimed the key and suppressed the
  // REAL turn in file C — and the result depended on file-visit order. The two
  // single-file F-11 tests above cannot reach this; order-independence is the
  // property that pins it.
  run('a zero-only copy in one file does not suppress the real turn in another (F-11 cross-file)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'xfile-zero-'));
    const real = { input_tokens: 2, output_tokens: 760, cache_creation_input_tokens: 290, cache_read_input_tokens: 804035 };
    const zero = { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const mk = (usage) => assistantEntry({
      id: 'msg_xfile', requestId: 'req_xfile',
      model: 'claude-haiku-4-5', timestamp: '2026-07-10T00:00:00.000Z', usage,
    });
    const zeroFile = writeTranscript(dir, 'sess-zero-only', [mk(zero)]);
    const realFile = writeTranscript(dir, 'sess-real', [mk(real)]);

    // parseSessionTokens returns { main, subagents: { totals } } — sum both sides.
    const sum = (r) => ({
      input: r.main.input_tokens + r.subagents.totals.input_tokens,
      output: r.main.output_tokens + r.subagents.totals.output_tokens,
      cache_read: r.main.cache_read_tokens + r.subagents.totals.cache_read_tokens,
      cache_write: r.main.cache_write_tokens + r.subagents.totals.cache_write_tokens,
    });

    // Same corpus, both visit orders — the totals must not depend on order.
    const zeroFirst = sum(parseSessionTokens({ mainPath: zeroFile, subagentPaths: [realFile], seen: new Map() }));
    const realFirst = sum(parseSessionTokens({ mainPath: realFile, subagentPaths: [zeroFile], seen: new Map() }));

    for (const [label, r] of [['zero-first', zeroFirst], ['real-first', realFirst]]) {
      assert.equal(r.cache_read, 804035, `${label}: real usage must survive`);
      assert.equal(r.output, 760, `${label}: output must survive`);
      assert.equal(r.input, 2, `${label}: input must survive`);
    }
    assert.deepEqual(zeroFirst, realFirst, 'totals must be order-independent');
  });

  // The rule is directional: a REAL snapshot must still displace an earlier zero,
  // otherwise "ignore zeros" would silently become "first-wins" for a turn whose
  // first line is a placeholder — trading one bug for another.
  run('a real snapshot DOES displace an earlier all-zero one (F-11 is directional)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'zerohead-'));
    const zero = { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const real = { input_tokens: 7, output_tokens: 99, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const mk = (usage) => assistantEntry({
      id: 'msg_zerohead', requestId: 'req_zerohead',
      model: 'claude-haiku-4-5', timestamp: '2026-07-10T00:00:00.000Z', usage,
    });
    const file = writeTranscript(dir, 'sess-zerohead', [mk(zero), mk(real)]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 1);
    assert.equal(r.output_tokens, 99); // the real one wins over the leading zero
    assert.equal(r.input_tokens, 7);
  });

  // --- 745 item B bug 1 + D3: dedup scope is the caller's, and spans sessions ---
  run('parseTranscriptTokens dedups across FILES when given a shared seen map, and not without one', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'crossfile-'));
    const entry = assistantEntry({
      id: 'msg-shared', requestId: 'req-shared', model: 'claude-haiku-4-5',
      timestamp: '2026-07-10T00:00:00.000Z',
      usage: { input_tokens: 1000, output_tokens: 100, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
    });
    const fileA = writeTranscript(dir, 'sess-A', [entry]);
    const fileB = writeTranscript(dir, 'sess-B', [entry]); // resumed session re-carries the same turn

    const perFileA = parseTranscriptTokens(fileA);
    const perFileB = parseTranscriptTokens(fileB);
    assert.equal(perFileA.turns + perFileB.turns, 2); // per-file scope double-counts (the pre-745 behaviour)

    const seen = new Map();
    const sharedA = parseTranscriptTokens(fileA, { seen });
    const sharedB = parseTranscriptTokens(fileB, { seen });
    assert.equal(sharedA.turns, 1);
    assert.equal(sharedB.turns, 0); // counted once, in the file that got there first
    assert.equal(sharedA.input_tokens + sharedB.input_tokens, 1000);
  });
  run('costSessionsChronologically counts a re-carried turn in the ORIGIN session, not the resumed one', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'recarry-'));
    const shared = assistantEntry({
      id: 'msg-origin', requestId: 'req-origin', model: 'claude-haiku-4-5',
      timestamp: '2026-07-10T00:00:00.000Z',
      usage: { input_tokens: 2000, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
    });
    const ownTurn = assistantEntry({
      id: 'msg-resumed-own', requestId: 'req-resumed-own', model: 'claude-haiku-4-5',
      timestamp: '2026-07-11T00:00:00.000Z',
      usage: { input_tokens: 500, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
    });
    const originPath = writeTranscript(dir, 'sess-origin', [shared]);
    const resumedPath = writeTranscript(dir, 'sess-resumed', [shared, ownTurn]);

    // Fed newest-first on purpose — the function must order by startTs itself.
    const costed = costSessionsChronologically([
      { sessionId: 'sess-resumed', projectDir: 'p', mainPath: resumedPath, subagentPaths: [], startTs: '2026-07-11T00:00:00.000Z' },
      { sessionId: 'sess-origin', projectDir: 'p', mainPath: originPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z' },
    ]);
    const byId = Object.fromEntries(costed.map((c) => [c.session_id, c]));
    assert.equal(byId['sess-origin'].total_tokens.input, 2000); // origin keeps the re-carried turn
    assert.equal(byId['sess-resumed'].total_tokens.input, 500); // resumed keeps only its own
    const corpusInput = costed.reduce((s, c) => s + c.total_tokens.input, 0);
    assert.equal(corpusInput, 2500); // NOT 4500 — the shared turn is counted exactly once
  });
  run('computeSessionCost dedups a main transcript against its own subagent files', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'sessionscope-'));
    const dup = assistantEntry({
      id: 'msg-dup', requestId: 'req-dup', model: 'claude-haiku-4-5',
      timestamp: '2026-07-10T00:00:00.000Z',
      usage: { input_tokens: 100, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
    });
    const mainPath = writeTranscript(dir, 'sess-scope', [dup]);
    const subPath = writeTranscript(dir, 'agent-dup', [dup]);
    const rec = computeSessionCost({
      sessionId: 'sess-scope', projectDir: 'p', mainPath, subagentPaths: [subPath], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.equal(rec.total_tokens.input, 100); // not 200
    assert.equal(rec.orchestrator_tokens_total, 100);
    assert.equal(rec.worker_tokens_total, 0); // main got there first; the split definition is unchanged (by file path)
    assert.equal(rec.subagents.found, 1); // a fully-deduped subagent file is still FOUND, not missing
  });

  // --- 745 item B bug 3: transcripts DO distinguish ephemeral cache tiers ---
  run('parseTranscriptTokens prices a 1h cache write at 2.0x input, not the 5m 1.25x', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'cache1h-'));
    const file = writeTranscript(dir, 'sess-1h', [
      assistantEntry({
        id: 'msg-1h', requestId: 'req-1h', model: 'claude-sonnet-4-6',
        timestamp: '2026-07-10T00:00:00.000Z',
        usage: {
          input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0,
          cache_creation_input_tokens: 100000, // flat form: the SUM, and what the old code priced at 5m
          cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 100000 },
        },
      }),
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.cache_write_tokens, 100000);
    // sonnet-4-6 input 3.0 -> 1h write 6.0/1M: 0.1 * 6.0 = 0.60 (the old 5m rate gave 0.375)
    assert.equal(r.cost_usd.toFixed(4), '0.6000');
  });
  run('parseTranscriptTokens prices a 5m cache write at 1.25x input, and a mixed-tier turn per tier', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'cache5m-'));
    const file = writeTranscript(dir, 'sess-5m', [
      assistantEntry({
        id: 'msg-5m', requestId: 'req-5m', model: 'claude-sonnet-4-6',
        timestamp: '2026-07-10T00:00:00.000Z',
        usage: {
          input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0,
          cache_creation_input_tokens: 100000,
          cache_creation: { ephemeral_5m_input_tokens: 100000, ephemeral_1h_input_tokens: 0 },
        },
      }),
      assistantEntry({
        id: 'msg-mixed', requestId: 'req-mixed', model: 'claude-sonnet-4-6',
        timestamp: '2026-07-10T00:00:01.000Z',
        usage: {
          input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0,
          cache_creation_input_tokens: 200000,
          cache_creation: { ephemeral_5m_input_tokens: 100000, ephemeral_1h_input_tokens: 100000 },
        },
      }),
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.cache_write_tokens, 300000);
    // 5m turn: 0.1*3.75 = 0.375; mixed turn: 0.1*3.75 + 0.1*6.0 = 0.975 -> 1.35
    assert.equal(r.cost_usd.toFixed(4), '1.3500');
  });
  run('parseTranscriptTokens falls back to the flat cache_creation_input_tokens at the 5m rate when no tier object is present', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'cacheflat-'));
    const file = writeTranscript(dir, 'sess-flat', [
      assistantLine('claude-sonnet-4-6', {
        input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 100000, cache_read_input_tokens: 0,
      }),
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.cache_write_tokens, 100000);
    assert.equal(r.cost_usd.toFixed(4), '0.3750'); // 0.1 * 3.75
  });

  // --- tempdoc 841: Sonnet-5's dated price cliff was CANCELLED ---
  // These assertions previously encoded the opposite ($2/$10 before 2026-09-01,
  // $3/$15 after) per 745 item B bug 4. The external fact changed: the pricing
  // page now states the introductory rate "is now the standard price" and the
  // scheduled increase "will not occur". The test is inverted to lock the cliff
  // OUT — a date-dependent assertion is exactly what would have let the stale
  // schedule activate silently on 2026-09-01.
  run('Sonnet-5 is priced flat regardless of date — the cancelled cliff must not fire', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'cliff-'));
    const usage = { input_tokens: 1_000_000, output_tokens: 1_000_000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const before = writeTranscript(dir, 'sess-early', [
      assistantEntry({ id: 'm1', requestId: 'r1', model: 'claude-sonnet-5', timestamp: '2026-07-16T00:00:00.000Z', usage }),
    ]);
    const after = writeTranscript(dir, 'sess-late', [
      assistantEntry({ id: 'm2', requestId: 'r2', model: 'claude-sonnet-5', timestamp: '2026-09-15T00:00:00.000Z', usage }),
    ]);
    assert.equal(parseTranscriptTokens(before).cost_usd.toFixed(2), '12.00'); // $2 + $10
    assert.equal(parseTranscriptTokens(after).cost_usd.toFixed(2), '12.00');  // SAME — no cliff
  });
  run('findPricing gives Sonnet-5 the same rate before, after, and without a date', () => {
    assert.equal(findPricing('claude-sonnet-5', Date.parse('2026-08-31T23:59:59.000Z')).input, 2.0);
    assert.equal(findPricing('claude-sonnet-5', Date.parse('2026-09-01T00:00:00.000Z')).input, 2.0);
    assert.equal(findPricing('claude-sonnet-5', Date.parse('2027-06-01T00:00:00.000Z')).input, 2.0);
    assert.equal(findPricing('claude-sonnet-5', null).input, 2.0);
    assert.equal(findPricing('claude-opus-4-8[1m]').input, 5.0); // suffixed id via longest-prefix match
    assert.equal(findPricing('claude-opus-4-8[1m]').cache_write_1h, 10.0);
  });

  // --- tempdoc 841: Opus 5 was absent and silently priced at $0 ---
  run('claude-opus-5 resolves to a real pricing row, bare and suffixed', () => {
    // The defect this guards: findPricing fails closed, so a missing model does
    // not mis-price — it prices at $0 and vanishes from every total. 51.9% of
    // the local corpus's cache-read went missing this way.
    assert.equal(isKnownModel('claude-opus-5'), true);
    assert.equal(findPricing('claude-opus-5').input, 5.0);
    assert.equal(findPricing('claude-opus-5').cache_read, 0.50);
    assert.equal(findPricing('claude-opus-5').cache_write_1h, 10.0);
    assert.equal(findPricing('claude-opus-5[1m]').input, 5.0); // the id Claude Code actually records
    assert.equal(findPricing('claude-opus-5[1m]').output, 25.0);
  });
  run('claude-opus-5 does not shadow, and is not shadowed by, the Opus 4.x rows', () => {
    assert.equal(findPricing('claude-opus-4-8').input, 5.0);
    assert.equal(findPricing('claude-opus-4-1').input, 15.0); // legacy row still distinct
  });

  // --- 745 item B bug 4 (second half) + D5: unknown models fail CLOSED ---
  run('findPricing returns null for an unknown model instead of silently falling back to Sonnet', () => {
    assert.equal(findPricing('claude-made-up-9'), null);
    assert.equal(findPricing(null), null);
  });

  // --- Finding 4 regression: model-less usage turns must not be priced at DEFAULT ---
  run('parseTranscriptTokens routes a model-less usage turn into MISSING_MODEL_KEY at $0, not DEFAULT_PRICING', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'missingmodel-'));
    const file = writeTranscript(dir, 'sess-missingmodel', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      { type: 'assistant', message: { id: 'msg-nomodel', usage: { input_tokens: 5000, output_tokens: 1000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 } } },
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.cost_usd, 0); // NOT priced at DEFAULT_PRICING
    assert.equal(r.turns, 1);
    assert.equal(r.input_tokens, 5000);
    assert.ok(r.by_model[MISSING_MODEL_KEY]);
    assert.equal(r.by_model[MISSING_MODEL_KEY].cost_usd, 0);
    assert.equal(r.by_model[MISSING_MODEL_KEY].input_tokens, 5000);
    assert.equal(isKnownModel(MISSING_MODEL_KEY), false);
  });
  run('computeSessionCost surfaces model-less turns via unknown_model_tokens[MISSING_MODEL_KEY]', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'missingmodel-session-'));
    const mainPath = writeTranscript(dir, 'sess-mm', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      { type: 'assistant', message: { id: 'msg-x', usage: { input_tokens: 100, output_tokens: 50, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 } } },
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-mm', projectDir: 'p', mainPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.deepEqual(rec.unknown_model_tokens, { [MISSING_MODEL_KEY]: 150 });
    assert.equal(rec.total_cost_usd, 0);
  });

  // --- unknown-model bucketing ---
  run('isKnownModel is false for an unlisted model id and true for listed/prefix-matched ones', () => {
    assert.equal(isKnownModel('claude-made-up-9'), false);
    assert.equal(isKnownModel('claude-sonnet-5'), true);
    assert.equal(isKnownModel('claude-opus-4-6-20260101'), true); // prefix match
    assert.equal(isKnownModel(null), true); // absent model is a different failure mode
  });
  run('computeSessionCost fails CLOSED on an unknown model: $0, bucketed, surfaced (745 item B, D5)', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'unknown-'));
    const mainPath = writeTranscript(dir, 'sess-unknown', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-made-up-9', { input_tokens: 1000, output_tokens: 500, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-unknown', projectDir: 'p', mainPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.deepEqual(rec.unknown_model_tokens, { 'claude-made-up-9': 1500 });
    // NOT priced at a Sonnet-shaped default — an unpriceable model contributes $0
    // and is surfaced, rather than hiding behind a plausible-looking figure.
    assert.equal(rec.total_cost_usd, 0);
    assert.equal(rec.model_mix['claude-made-up-9'].cost_usd, 0);
    assert.equal(rec.model_mix['claude-made-up-9'].input_tokens, 1000);
    assert.equal(rec.main.model, null); // no priced model was seen
  });
  run('computeSessionCost does not flag a known model as unknown', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'known-'));
    const mainPath = writeTranscript(dir, 'sess-known', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-sonnet-5', { input_tokens: 100, output_tokens: 100, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-known', projectDir: 'p', mainPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.deepEqual(rec.unknown_model_tokens, {});
  });

  // --- orchestrator/worker split via subagents ---
  run('computeSessionCost splits orchestrator (main) vs worker (subagent) tokens', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'split-'));
    const mainPath = writeTranscript(dir, 'sess-split', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-sonnet-5', { input_tokens: 1000, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const subDir = fs.mkdtempSync(path.join(tmp, 'subs-'));
    const subPath = writeTranscript(subDir, 'agent-a1', [
      { timestamp: '2026-07-10T00:00:01.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-haiku-4-5', { input_tokens: 3000, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-split', projectDir: 'p', mainPath, subagentPaths: [subPath], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.equal(rec.orchestrator_tokens_total, 1000);
    assert.equal(rec.worker_tokens_total, 3000);
    assert.equal(rec.subagents.found, 1);
    assert.equal(rec.subagents.missing, 0);
  });
  run('computeSessionCost counts a missing subagent transcript without throwing', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'missing-sub-'));
    const mainPath = writeTranscript(dir, 'sess-missingsub', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-missingsub', projectDir: 'p', mainPath,
      subagentPaths: [path.join(dir, 'does-not-exist.jsonl')], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.equal(rec.subagents.missing, 1);
    assert.equal(rec.subagents.found, 0);
  });

  // --- classifyMerge ---
  run('classifyMerge recognizes conventional-commit prefixes', () => {
    assert.equal(classifyMerge('feat(x): add thing'), 'feat');
    assert.equal(classifyMerge('fix: bug'), 'fix');
    assert.equal(classifyMerge('docs(743): baseline economics'), 'docs');
    assert.equal(classifyMerge('chore!: breaking cleanup'), 'chore');
    assert.equal(classifyMerge('some random merge subject'), 'other');
    assert.equal(classifyMerge(''), 'other');
    assert.equal(classifyMerge(undefined), 'other');
  });

  // --- isoWeekKey ---
  run('isoWeekKey matches known ISO week numbers', () => {
    assert.equal(isoWeekKey(new Date('2026-07-16T00:00:00.000Z')), '2026-W29');
    assert.equal(isoWeekKey(new Date('2026-06-30T00:00:00.000Z')), '2026-W27');
    assert.equal(isoWeekKey(new Date('2026-01-01T00:00:00.000Z')), '2026-W01');
  });

  // --- merge join + per-merge split ---
  run('buildReport splits session cost evenly across its merges and classifies each', () => {
    const sessions = [{
      session_id: 's1', project_dir: 'p', start_ts: '2026-07-01T00:00:00.000Z',
      total_cost_usd: 10, orchestrator_tokens_total: 100, worker_tokens_total: 50,
      subagents: { count: 1 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const merges = [
      { session_id: 's1', merge_commit: 'aaa', subject: 'feat(x): thing', ts: '2026-07-01T01:00:00.000Z' },
      { session_id: 's1', merge_commit: 'bbb', subject: 'fix(y): thing2', ts: '2026-07-01T02:00:00.000Z' },
    ];
    const report = buildReport({ sessions, merges, since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.merges_attributed, 2);
    assert.equal(report.totals.merge_rows_in_window, 2);
    assert.equal(report.totals.merges_unattributable, 0);
    assert.equal(report.totals.merges_excluded_by_scope, 0);
    assert.equal(report.totals.total_cost_usd, 10);
    assert.equal(report.totals.cost_per_merge_attributed, 5);
    assert.equal(report.sessions[0].cost_per_merge, 5);
    assert.equal(report.sessions[0].merges.length, 2);
    assert.equal(report.totals.by_merge_class.feat.count, 1);
    assert.equal(report.totals.by_merge_class.feat.cost_usd, 5);
    assert.equal(report.totals.by_merge_class.fix.count, 1);
    assert.equal(report.totals.sessions_with_zero_merges, 0);
  });
  run('buildReport lists a merge-less session separately with its cost', () => {
    const sessions = [{
      session_id: 's-nomrg', project_dir: 'p', start_ts: '2026-07-02T00:00:00.000Z',
      total_cost_usd: 3.5, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const report = buildReport({ sessions, merges: [], since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.merges_attributed, 0);
    assert.equal(report.totals.cost_per_merge_attributed, null);
    assert.equal(report.totals.sessions_with_zero_merges, 1);
    assert.equal(report.zero_merge_sessions[0].session_id, 's-nomrg');
    assert.equal(report.zero_merge_sessions[0].total_cost_usd, 3.5);
    assert.equal(report.sessions[0].cost_per_merge, null);
  });
  run('buildReport (Finding 2 regression) splits an unmatched merge into "unattributable" and lists its session id', () => {
    const sessions = [{
      session_id: 's-known', project_dir: 'p', start_ts: '2026-07-01T00:00:00.000Z',
      total_cost_usd: 4, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const merges = [
      { session_id: 's-known', merge_commit: 'aaa', subject: 'feat: known', ts: '2026-07-01T01:00:00.000Z' },
      { session_id: 's-ghost', merge_commit: 'bbb', subject: 'fix: no transcript for this session', ts: '2026-07-01T02:00:00.000Z' },
    ];
    const report = buildReport({ sessions, merges, since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.merge_rows_in_window, 2);
    assert.equal(report.totals.merges_attributed, 1);
    assert.equal(report.totals.merges_unattributable, 1);
    assert.deepEqual(report.totals.unattributable_session_ids, ['s-ghost']);
    assert.equal(report.totals.cost_per_merge_attributed, 4); // computed over attributed merges only
    assert.match(report.caveats.join('\n'), /unattributable/);
    assert.match(report.caveats.join('\n'), /s-ghost/);
  });
  run('buildReport (Finding 2 regression) classifies a merge whose session was excluded by scope separately from unattributable', () => {
    const sessions = []; // the excluded session never made it into the costed set
    const merges = [
      { session_id: 'excluded-session', merge_commit: 'ccc', subject: 'chore: excluded work', ts: '2026-07-01T01:00:00.000Z' },
    ];
    const report = buildReport({
      sessions, merges, since: '2026-07-01', until: null, excludedCount: 1,
      isExcludedSessionId: (id) => id === 'excluded-session',
    });
    assert.equal(report.totals.merge_rows_in_window, 1);
    assert.equal(report.totals.merges_attributed, 0);
    assert.equal(report.totals.merges_excluded_by_scope, 1);
    assert.equal(report.totals.merges_unattributable, 0);
    assert.deepEqual(report.totals.unattributable_session_ids, []);
    assert.match(report.caveats.join('\n'), /excluded by the/);
  });
  run('buildReport (Finding 2 regression) excludes a merge outside [since,until] from merge_rows_in_window', () => {
    const sessions = [{
      session_id: 's1', project_dir: 'p', start_ts: '2026-07-01T00:00:00.000Z',
      total_cost_usd: 2, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const merges = [
      { session_id: 's1', merge_commit: 'in', subject: 'feat: in window', ts: '2026-07-05T00:00:00.000Z' },
      { session_id: 's1', merge_commit: 'out', subject: 'feat: out of window', ts: '2026-08-01T00:00:00.000Z' },
    ];
    const report = buildReport({ sessions, merges, since: '2026-07-01', until: '2026-07-31', excludedCount: 0 });
    assert.equal(report.totals.merge_rows_in_window, 1);
    assert.equal(report.totals.merges_attributed, 1);
  });
  run('buildReport surfaces unknown-model tokens at the window-totals level', () => {
    const sessions = [{
      session_id: 's-unk', project_dir: 'p', start_ts: '2026-07-02T00:00:00.000Z',
      total_cost_usd: 1, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: { 'claude-made-up-9': 1500 },
    }];
    const report = buildReport({ sessions, merges: [], since: '2026-07-01', until: null, excludedCount: 0 });
    assert.deepEqual(report.totals.unknown_models, { 'claude-made-up-9': { tokens: 1500, sessions: 1 } });
  });
  run('buildReport computes orchestrator/worker token split percentages', () => {
    const sessions = [{
      session_id: 's-split', project_dir: 'p', start_ts: '2026-07-02T00:00:00.000Z',
      total_cost_usd: 1, orchestrator_tokens_total: 75, worker_tokens_total: 25,
      subagents: { count: 1 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const report = buildReport({ sessions, merges: [], since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.token_split.orchestrator_pct, 75);
    assert.equal(report.totals.token_split.worker_pct, 25);
  });
  run('buildReport rolls sessions up by ISO week', () => {
    const sessions = [
      { session_id: 'a', project_dir: 'p', start_ts: '2026-06-30T00:00:00.000Z', total_cost_usd: 1, orchestrator_tokens_total: 1, worker_tokens_total: 0, subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {} },
      { session_id: 'b', project_dir: 'p', start_ts: '2026-07-16T00:00:00.000Z', total_cost_usd: 2, orchestrator_tokens_total: 1, worker_tokens_total: 0, subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {} },
    ];
    const report = buildReport({ sessions, merges: [], since: '2026-06-01', until: null, excludedCount: 0 });
    const weeks = report.weekly.map((w) => w.week);
    assert.deepEqual(weeks, ['2026-W27', '2026-W29']);
  });
  run('formatMarkdown renders without throwing and includes the structural-break caveats', () => {
    const report = buildReport({ sessions: [], merges: [], since: '2026-06-18', until: null, excludedCount: 2 });
    const md = formatMarkdown(report);
    assert.match(md, /Baseline Economics Report/);
    assert.match(md, /2026-07-14/);
    assert.match(md, /2026-07-15/);
    assert.match(md, /2026-06-30/);
    assert.match(md, /excluded by scope filter: 2/);
  });

  // --- exclusion filtering ---
  run('loadExclusionMatcher matches both full-UUID and 8-char-prefix keys', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'excl-'));
    const file = path.join(dir, 'excluded.json');
    fs.writeFileSync(file, JSON.stringify({
      excluded: {
        '11111111-1111-1111-1111-111111111111': 'full match',
        'abcdef12': 'prefix match',
      },
    }));
    const isExcluded = loadExclusionMatcher(file);
    assert.equal(isExcluded('11111111-1111-1111-1111-111111111111'), true);
    assert.equal(isExcluded('abcdef12-3456-7890-abcd-ef1234567890'), true);
    assert.equal(isExcluded('22222222-2222-2222-2222-222222222222'), false);
  });
  run('loadExclusionMatcher returns an always-false matcher for a missing file', () => {
    const isExcluded = loadExclusionMatcher(path.join(tmp, 'does-not-exist.json'));
    assert.equal(isExcluded('anything'), false);
  });

  // --- window filtering + discovery (real small fixture dirs) ---
  await runAsync('discoverSessions includes only sessions whose first timestamp falls in the window, and honors exclusion', async () => {
    const projectsRoot = fs.mkdtempSync(path.join(tmp, 'projects-'));
    const projectDir = path.join(projectsRoot, 'F--justsearch-public-fixture');
    fs.mkdirSync(projectDir, { recursive: true });

    writeTranscript(projectDir, 'in-window', [
      { timestamp: '2026-07-05T00:00:00.000Z', type: 'mode' },
      assistantLine('claude-sonnet-5', { input_tokens: 10, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    writeTranscript(projectDir, 'before-window', [
      { timestamp: '2026-01-01T00:00:00.000Z', type: 'mode' },
    ]);
    writeTranscript(projectDir, 'after-window', [
      { timestamp: '2026-12-01T00:00:00.000Z', type: 'mode' },
    ]);
    writeTranscript(projectDir, 'excluded-in-window', [
      { timestamp: '2026-07-06T00:00:00.000Z', type: 'mode' },
    ]);
    // non-matching project dir (no "justsearch" in slug) must be ignored entirely
    const otherDir = path.join(projectsRoot, 'some-other-project');
    fs.mkdirSync(otherDir, { recursive: true });
    writeTranscript(otherDir, 'unrelated', [{ timestamp: '2026-07-05T00:00:00.000Z', type: 'mode' }]);

    // subagents dir for in-window session
    const subDir = path.join(projectDir, 'in-window', 'subagents');
    fs.mkdirSync(subDir, { recursive: true });
    fs.writeFileSync(path.join(subDir, 'agent-x1.jsonl'), JSON.stringify(assistantLine('claude-haiku-4-5', { input_tokens: 5, output_tokens: 5, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 })) + '\n', 'utf8');

    const sinceMs = new Date('2026-07-01T00:00:00.000Z').getTime();
    const untilMs = new Date('2026-07-31T23:59:59.000Z').getTime();
    const isExcluded = (id) => id === 'excluded-in-window';

    const { sessions, excludedCount } = await discoverSessions({ projectsRoot, sinceMs, untilMs, isExcluded });

    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].sessionId, 'in-window');
    assert.equal(sessions[0].subagentPaths.length, 1);
    assert.equal(excludedCount, 1);
  });

  // --- loadMerges ---
  run('loadMerges parses NDJSON and skips malformed lines', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'merges-'));
    const file = path.join(dir, 'session-merges.ndjson');
    fs.writeFileSync(file, [
      JSON.stringify({ session_id: 's1', merge_commit: 'a', subject: 'feat: x', ts: '2026-07-01T00:00:00.000Z' }),
      'not json',
      JSON.stringify({ session_id: 's2', merge_commit: 'b', subject: 'fix: y', ts: '2026-07-02T00:00:00.000Z' }),
      '',
    ].join('\n'));
    const rows = loadMerges(file);
    assert.equal(rows.length, 2);
    assert.equal(rows[0].session_id, 's1');
    assert.equal(rows[1].session_id, 's2');
  });
  run('loadMerges returns an empty array for a missing file', () => {
    assert.deepEqual(loadMerges(path.join(tmp, 'no-such-merges.ndjson')), []);
  });
}

main().finally(() => {
  fs.rmSync(tmp, { recursive: true, force: true });
  if (failures.length) {
    console.error(`baseline-economics.test: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exit(1);
  }
  console.log(`baseline-economics.test: ${passed} passed`);
});
