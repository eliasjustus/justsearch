/**
 * Tempdoc 886 §12 PR 4 — unit tests for context-ceiling-hint.
 *
 * Run with: `node scripts/agent-analytics/hooks/context-ceiling-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import {
  advanceState,
  contextTokensOf,
  lastAssistantUsage,
  nextThreshold,
  renderCeilingLine,
} from './context-ceiling-hint.mjs';

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

const HOOK = resolve(dirname(fileURLToPath(import.meta.url)), 'context-ceiling-hint.mjs');
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const STATE_DIR = path.join(REPO_ROOT, 'tmp', 'agent-telemetry', 'context-ceiling-state');

function writeTranscriptAt(p, usageTokens, turnId = 'm1') {
  const lines = [
    { type: 'user', timestamp: '2026-09-01T00:00:00.000Z', message: { content: 'hi' } },
    {
      type: 'assistant',
      timestamp: '2026-09-01T00:00:01.000Z',
      message: {
        id: turnId,
        model: 'claude-sonnet-5',
        content: [{ type: 'text', text: 'ok' }],
        usage: {
          input_tokens: usageTokens,
          cache_read_input_tokens: 0,
          cache_creation_input_tokens: 0,
          output_tokens: 10,
        },
      },
    },
  ];
  fs.writeFileSync(p, lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
  return p;
}

function writeTranscript(usageTokens) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'context-ceiling-hint-'));
  return writeTranscriptAt(path.join(dir, 'transcript.jsonl'), usageTokens);
}

function cleanState(sessionId) {
  try {
    fs.unlinkSync(path.join(STATE_DIR, `${sessionId}.json`));
  } catch {
    /* none */
  }
}

function runHook(stdin, env = {}) {
  return execFileSync('node', [HOOK], {
    input: JSON.stringify(stdin),
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
}

// --- pure helpers ---
run('contextTokensOf sums input + cache_read + cache_creation (flat)', () => {
  assert.equal(
    contextTokensOf({ input_tokens: 100, cache_read_input_tokens: 200, cache_creation_input_tokens: 50 }),
    350,
  );
});
run('contextTokensOf prefers the tiered cache_creation object', () => {
  assert.equal(
    contextTokensOf({
      input_tokens: 100,
      cache_read_input_tokens: 0,
      cache_creation: { ephemeral_5m_input_tokens: 10, ephemeral_1h_input_tokens: 20 },
    }),
    130,
  );
});

run('lastAssistantUsage reads the LAST assistant usage, not the first', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'context-ceiling-hint-'));
  const p = path.join(dir, 't.jsonl');
  const l1 = { type: 'assistant', message: { usage: { input_tokens: 111 } } };
  const l2 = { type: 'assistant', message: { usage: { input_tokens: 999 } } };
  fs.writeFileSync(p, `${JSON.stringify(l1)}\n${JSON.stringify(l2)}\n`);
  const usage = lastAssistantUsage(p);
  assert.equal(usage.input_tokens, 999);
});

run('lastAssistantUsage returns null for a missing file', () => {
  assert.equal(lastAssistantUsage('/nope/does/not/exist.jsonl'), null);
});

run('lastAssistantUsage handles a tail read that starts mid-line (drops the partial first line)', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'context-ceiling-hint-'));
  const p = path.join(dir, 't.jsonl');
  const real = { type: 'assistant', message: { usage: { input_tokens: 777 } } };
  const filler = 'x'.repeat(2000);
  fs.writeFileSync(p, `${filler}\n${JSON.stringify(real)}\n`);
  const usage = lastAssistantUsage(p, { tailBytes: 100 }); // tail starts inside `filler`
  assert.equal(usage.input_tokens, 777);
});

// --- nextThreshold ---
run('nextThreshold: below 300k fires nothing', () => {
  assert.equal(nextThreshold(100_000, {}), null);
});
run('nextThreshold: at/above 300k (fresh state) fires 300k', () => {
  assert.equal(nextThreshold(320_000, {}).label, '300k');
});
run('nextThreshold: 300k already notified does not re-fire', () => {
  assert.equal(nextThreshold(320_000, { notified300: true }), null);
});
run('nextThreshold: at/above 500k (fresh state) fires 500k directly, not 300k', () => {
  assert.equal(nextThreshold(520_000, {}).label, '500k');
});
run('nextThreshold: 500k already notified does not re-fire', () => {
  assert.equal(nextThreshold(520_000, { notified300: true, notified500: true }), null);
});

run('lastAssistantUsage retries with a larger tail when a huge trailing tool_result pushes the assistant line out of the first tail', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'context-ceiling-hint-'));
  const p = path.join(dir, 't.jsonl');
  const real = { type: 'assistant', message: { usage: { input_tokens: 555 } } };
  const bigToolResult = {
    type: 'user',
    message: { content: [{ type: 'tool_result', content: 'x'.repeat(400 * 1024) }] },
  };
  fs.writeFileSync(p, `${JSON.stringify(real)}\n${JSON.stringify(bigToolResult)}\n`);
  // default tailBytes (256KB) lands entirely inside bigToolResult's content — no assistant line visible.
  const usage = lastAssistantUsage(p);
  assert.equal(usage.input_tokens, 555);
});
run('lastAssistantUsage does not retry when retryTailBytes is not larger than tailBytes', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'context-ceiling-hint-'));
  const p = path.join(dir, 't.jsonl');
  const real = { type: 'assistant', message: { usage: { input_tokens: 555 } } };
  const bigToolResult = {
    type: 'user',
    message: { content: [{ type: 'tool_result', content: 'x'.repeat(400 * 1024) }] },
  };
  fs.writeFileSync(p, `${JSON.stringify(real)}\n${JSON.stringify(bigToolResult)}\n`);
  const usage = lastAssistantUsage(p, { tailBytes: 1000, retryTailBytes: 1000 });
  assert.equal(usage, null);
});

// --- advanceState (re-arm) ---
run('advanceState below the lowest threshold clears prior flags and stamps lastCtx', () => {
  assert.deepEqual(advanceState(20_000, { notified300: true, notified500: true, lastCtx: 520_000 }), {
    lastCtx: 20_000,
  });
});
run('advanceState at/above the lowest threshold carries prior flags forward', () => {
  assert.deepEqual(advanceState(340_000, { notified300: true, lastCtx: 310_000 }), {
    notified300: true,
    lastCtx: 340_000,
  });
});
run('advanceState with no prior state and a fresh climb carries nothing but lastCtx', () => {
  assert.deepEqual(advanceState(310_000, {}), { lastCtx: 310_000 });
});

// --- renderCeilingLine ---
run('renders the 300k line', () => {
  const line = renderCeilingLine(320_000, { label: '300k' });
  assert.ok(line.startsWith('context-ceiling: 320k tokens in context —'));
  assert.ok(line.includes('886 §2.2'));
  assert.ok(line.includes('/compact'));
  assert.ok(line.includes('/rewind'));
});
run('renders the 500k line with the 500k wording', () => {
  const line = renderCeilingLine(520_000, { label: '500k' });
  assert.ok(line.includes('520k tokens in context (past 500k)'));
});

// --- end-to-end hook process ---
run('no output below 300k', () => {
  const sessionId = `test-below-${process.pid}`;
  cleanState(sessionId);
  const p = writeTranscript(100_000);
  const out = runHook({ session_id: sessionId, transcript_path: p });
  assert.equal(out.trim(), '');
  cleanState(sessionId);
});

run('one line at 320k, silent on the second identical call (once-per-threshold)', () => {
  const sessionId = `test-320k-${process.pid}`;
  cleanState(sessionId);
  const p = writeTranscript(320_000);
  const first = runHook({ session_id: sessionId, transcript_path: p });
  const parsed = JSON.parse(first);
  assert.ok(parsed.hookSpecificOutput.additionalContext.startsWith('context-ceiling: 320k'));

  const second = runHook({ session_id: sessionId, transcript_path: p });
  assert.equal(second.trim(), '');
  cleanState(sessionId);
});

run('a fresh 520k call fires the 500k line', () => {
  const sessionId = `test-520k-${process.pid}`;
  cleanState(sessionId);
  const p = writeTranscript(520_000);
  const out = runHook({ session_id: sessionId, transcript_path: p });
  const parsed = JSON.parse(out);
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('past 500k'));
  cleanState(sessionId);
});

run('re-arm sequence: 310k fires, 520k fires, 20k silent (re-arms), 340k fires again, 610k fires again', () => {
  const sessionId = `test-rearm-${process.pid}`;
  cleanState(sessionId);
  const p = writeTranscript(0);

  writeTranscriptAt(p, 310_000, 'm1');
  const r1 = JSON.parse(runHook({ session_id: sessionId, transcript_path: p }));
  assert.ok(r1.hookSpecificOutput.additionalContext.startsWith('context-ceiling: 310k'));

  writeTranscriptAt(p, 520_000, 'm2');
  const r2 = JSON.parse(runHook({ session_id: sessionId, transcript_path: p }));
  assert.ok(r2.hookSpecificOutput.additionalContext.includes('past 500k'));

  writeTranscriptAt(p, 20_000, 'm3');
  const r3 = runHook({ session_id: sessionId, transcript_path: p });
  assert.equal(r3.trim(), '');

  writeTranscriptAt(p, 340_000, 'm4');
  const r4 = JSON.parse(runHook({ session_id: sessionId, transcript_path: p }));
  assert.ok(r4.hookSpecificOutput.additionalContext.startsWith('context-ceiling: 340k'));

  writeTranscriptAt(p, 610_000, 'm5');
  const r5 = JSON.parse(runHook({ session_id: sessionId, transcript_path: p }));
  assert.ok(r5.hookSpecificOutput.additionalContext.includes('past 500k'));

  cleanState(sessionId);
});

run('a session hovering at 320k -> 330k -> 340k (never dropping below 300k) fires exactly once', () => {
  const sessionId = `test-hover-${process.pid}`;
  cleanState(sessionId);
  const p = writeTranscript(0);

  writeTranscriptAt(p, 320_000, 'm1');
  const r1 = runHook({ session_id: sessionId, transcript_path: p });
  assert.ok(JSON.parse(r1).hookSpecificOutput.additionalContext.startsWith('context-ceiling: 320k'));

  writeTranscriptAt(p, 330_000, 'm2');
  const r2 = runHook({ session_id: sessionId, transcript_path: p });
  assert.equal(r2.trim(), '');

  writeTranscriptAt(p, 340_000, 'm3');
  const r3 = runHook({ session_id: sessionId, transcript_path: p });
  assert.equal(r3.trim(), '');

  cleanState(sessionId);
});

run('kill switch: JUSTSEARCH_DISABLE_HOOKS=1 produces no output', () => {
  const sessionId = `test-killswitch-${process.pid}`;
  cleanState(sessionId);
  const p = writeTranscript(600_000);
  const out = runHook({ session_id: sessionId, transcript_path: p }, { JUSTSEARCH_DISABLE_HOOKS: '1' });
  assert.equal(out.trim(), '');
  cleanState(sessionId);
});

// --- Report ---
if (failures.length > 0) {
  console.error(`context-ceiling-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`context-ceiling-hint.test: all ${passed} checks passed`);
