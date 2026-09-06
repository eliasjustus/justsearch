/**
 * lib/ledger/record.test.mjs — unit tests for the neutral Call/ToolEvent
 * constructors (tempdoc 886 §12 PR 1).
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/record.test.mjs`
 */

import assert from 'node:assert/strict';
import { makeCall, isCall, makeToolEvent, isToolEvent } from './record.mjs';

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

// --- makeCall ------------------------------------------------------------

run('makeCall throws on a missing harness', () => {
  assert.throws(() => makeCall({ sessionId: 's1' }), /harness is required/);
});

run('makeCall throws on an unknown harness', () => {
  assert.throws(() => makeCall({ harness: 'gemini-cli', sessionId: 's1' }), /unknown harness/);
});

run('makeCall throws on a missing sessionId', () => {
  assert.throws(() => makeCall({ harness: 'claude-code' }), /sessionId is required/);
});

run('makeCall throws on an unknown lineage.kind', () => {
  assert.throws(
    () => makeCall({ harness: 'claude-code', sessionId: 's1', lineage: { kind: 'bogus' } }),
    /unknown lineage\.kind/,
  );
});

run('makeCall defaults lineage.kind to "main" when omitted', () => {
  const c = makeCall({ harness: 'claude-code', sessionId: 's1' });
  assert.equal(c.lineage.kind, 'main');
});

run('makeCall leaves absent token axes as null, not 0 (886 contract)', () => {
  const c = makeCall({ harness: 'codex-cli', sessionId: 's1', tokens: { fresh: 10, output: 5 } });
  assert.equal(c.tokens.cacheWrite5m, null);
  assert.equal(c.tokens.cacheWrite1h, null);
  assert.equal(c.tokens.reasoning, null);
  // fresh/output are present axes for every harness, so they default to 0, not null
  assert.equal(c.tokens.fresh, 10);
  assert.equal(c.tokens.output, 5);
});

run('makeCall defaults synthetic to false, and sets it true when passed (NIT 6)', () => {
  const real = makeCall({ harness: 'codex-cli', sessionId: 's1' });
  assert.equal(real.synthetic, false);
  const fabricated = makeCall({ harness: 'codex-cli', sessionId: 's1', synthetic: true });
  assert.equal(fabricated.synthetic, true);
});

run('makeCall preserves compactMetadata when provided, omits the key when not', () => {
  const withMeta = makeCall({
    harness: 'claude-code', sessionId: 's1', compactionBoundary: true,
    compactMetadata: { trigger: 'auto', preTokens: 100, postTokens: 10, durationMs: 5 },
  });
  assert.deepEqual(withMeta.compactMetadata, { trigger: 'auto', preTokens: 100, postTokens: 10, durationMs: 5 });

  const withoutMeta = makeCall({ harness: 'claude-code', sessionId: 's1' });
  assert.equal('compactMetadata' in withoutMeta, false);
});

run('makeCall preserves actual reasoning effort when provided', () => {
  const c = makeCall({ harness: 'codex-cli', sessionId: 's1', reasoningEffort: 'high' });
  assert.equal(c.reasoningEffort, 'high');
  assert.equal(makeCall({ harness: 'codex-cli', sessionId: 's2' }).reasoningEffort, null);
});

run('makeCall round-trips a full Claude-shaped call', () => {
  const c = makeCall({
    harness: 'claude-code',
    provider: 'anthropic',
    project: 'F--fixture-project',
    sessionId: 's1',
    callId: 'msg-1',
    lineage: { parentSessionId: null, kind: 'main' },
    ts: '2026-08-01T00:00:00.000Z',
    model: 'claude-opus-5',
    tokens: { fresh: 100, cacheRead: 2000, cacheWrite5m: 500, cacheWrite1h: 0, output: 20, reasoning: null },
    contextTokens: 2600,
    compactionBoundary: false,
    speed: null,
  });
  assert.ok(isCall(c));
  assert.equal(c.contextTokens, 2600);
});

// --- isCall ----------------------------------------------------------------

run('isCall rejects a plain object with no harness', () => {
  assert.equal(isCall({ sessionId: 's1' }), false);
});

run('isCall rejects null/undefined', () => {
  assert.equal(isCall(null), false);
  assert.equal(isCall(undefined), false);
});

run('isCall accepts everything makeCall produces', () => {
  assert.ok(isCall(makeCall({ harness: 'codex-cli', sessionId: 's1' })));
});

// --- makeToolEvent / isToolEvent --------------------------------------------

run('makeToolEvent throws on a missing harness or sessionId', () => {
  assert.throws(() => makeToolEvent({ sessionId: 's1' }), /harness is required/);
  assert.throws(() => makeToolEvent({ harness: 'claude-code' }), /sessionId is required/);
});

run('makeToolEvent falls back an unrecognised role to "other"', () => {
  const e = makeToolEvent({ harness: 'claude-code', sessionId: 's1', role: 'not-a-real-role', name: 'X' });
  assert.equal(e.role, 'other');
});

run('makeToolEvent defaults inputChars/outputChars to 0 and isError to false', () => {
  const e = makeToolEvent({ harness: 'claude-code', sessionId: 's1', role: 'read', name: 'Read' });
  assert.equal(e.inputChars, 0);
  assert.equal(e.outputChars, 0);
  assert.equal(e.isError, false);
});

run('isToolEvent accepts everything makeToolEvent produces', () => {
  assert.ok(isToolEvent(makeToolEvent({ harness: 'codex-cli', sessionId: 's1', role: 'shell', name: 'shell' })));
});

run('isToolEvent rejects a call-shaped object (no role)', () => {
  assert.equal(isToolEvent({ harness: 'claude-code', sessionId: 's1' }), false);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`record.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`record.test: ${passed} passed`);
