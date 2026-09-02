/**
 * lib/ledger/index.test.mjs — unit tests for the harness merge point
 * (tempdoc 886 §12 PR 1), run against both fixture trees.
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/index.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { listCalls } from './index.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES_CLAUDE = path.join(HERE, '..', '..', 'fixtures', 'claude');
const FIXTURES_CODEX = path.join(HERE, '..', '..', 'fixtures', 'codex');
// A Claude-slug-shaped filter (`F--fixture-project`) would NOT match Codex's
// raw-cwd project string (`F:\FixtureProject`) -- a real, correct asymmetry
// between the two harnesses' project axis, not a bug. `discoverProjectDirs`'s
// OWN default filter (/justsearch/i) also would not match this fixture dir
// name, so a projectFilter is required for Claude discovery either way; /fixture/i
// matches both harnesses' differently-shaped project strings for the merge test.
const CLAUDE_PROJECT_FILTER = /F--fixture-project/;
const BOTH_HARNESS_PROJECT_FILTER = /fixture/i;

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

// --- both harnesses merge -----------------------------------------------

run('listCalls with both harnesses merges Claude + Codex fixture results', () => {
  const r = listCalls({
    harnesses: ['claude-code', 'codex-cli'],
    projectsRoot: FIXTURES_CLAUDE,
    projectFilter: BOTH_HARNESS_PROJECT_FILTER,
    codexHome: FIXTURES_CODEX,
  });
  const claudeCalls = r.calls.filter((c) => c.harness === 'claude-code');
  const codexCalls = r.calls.filter((c) => c.harness === 'codex-cli');
  assert.ok(claudeCalls.length > 0, 'expected at least one claude-code call');
  assert.ok(codexCalls.length > 0, 'expected at least one codex-cli call');
  assert.equal(r.calls.length, claudeCalls.length + codexCalls.length);
});

// --- harness filter is respected ------------------------------------------

run('listCalls({harnesses: ["claude-code"]}) excludes codex-cli entirely', () => {
  const r = listCalls({
    harnesses: ['claude-code'],
    projectsRoot: FIXTURES_CLAUDE,
    projectFilter: CLAUDE_PROJECT_FILTER,
    codexHome: FIXTURES_CODEX,
  });
  assert.ok(r.calls.every((c) => c.harness === 'claude-code'));
  assert.ok(r.sessions.every((s) => s.harness === 'claude-code'));
});

run('listCalls({harnesses: ["codex-cli"]}) excludes claude-code entirely', () => {
  const r = listCalls({
    harnesses: ['codex-cli'],
    projectsRoot: FIXTURES_CLAUDE,
    codexHome: FIXTURES_CODEX,
  });
  assert.ok(r.calls.every((c) => c.harness === 'codex-cli'));
  assert.ok(r.sessions.every((s) => s.harness === 'codex-cli'));
});

// --- resilience --------------------------------------------------------------

run('listCalls never throws when both roots are missing', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const r = listCalls({
    projectsRoot: path.join(tmp, 'no-claude'),
    codexHome: path.join(tmp, 'no-codex'),
  });
  assert.deepEqual(r, { calls: [], toolEvents: [], sessions: [], skipped: [] });
});

run('listCalls merges skipped Codex files through from the adapter', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '08', '08');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'rollout-2026-08-08T00-00-00-noid.jsonl'),
    JSON.stringify({ timestamp: '2026-08-08T00:00:00.000Z', type: 'session_meta', payload: { cwd: 'F:\\NoId', model_provider: 'openai' } }) + '\n',
    'utf8',
  );
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex') });
  assert.equal(r.skipped.length, 1);
});

// --- window semantics: per-call ts filter, not just file mtime (886 §12 PR 2 fix) --

/**
 * A single rollout FILE (one mtime) whose three calls straddle the window:
 * one dated well BEFORE `sinceMs` (the exact leak the reviewer measured —
 * a long-lived session's early calls surviving a mtime-only prefilter
 * because the FILE was touched again later), one dated INSIDE the window,
 * and one with NO `timestamp` on its `event_msg` line at all (unjudgeable).
 */
function writeStraddlingRollout(dir, sessionId) {
  fs.mkdirSync(dir, { recursive: true });
  const usage = (input) => ({
    last_token_usage: { input_tokens: input, cached_input_tokens: 0, output_tokens: 5, reasoning_output_tokens: 0, total_tokens: input + 5 },
    total_token_usage: { input_tokens: input, cached_input_tokens: 0, output_tokens: 5, reasoning_output_tokens: 0, total_tokens: input + 5 },
  });
  const lines = [
    { timestamp: '2026-08-01T00:00:00.000Z', type: 'session_meta', payload: { id: sessionId, cwd: 'F:\\Straddle', model_provider: 'openai' } },
    { timestamp: '2026-08-01T00:00:01.000Z', type: 'turn_context', payload: { turn_id: 't1', model: 'gpt-5.6' } },
    // before the window (2026-07-01) -- must be dropped by the ts filter
    { timestamp: '2026-07-01T00:00:00.000Z', type: 'event_msg', payload: { type: 'token_count', info: usage(100) } },
    // inside the window
    { timestamp: '2026-08-15T00:00:00.000Z', type: 'event_msg', payload: { type: 'token_count', info: usage(300) } },
    // total_tokens must differ from the previous kept snapshot (A2 dedup) --
    // this one has NO timestamp at all: unjudgeable, must be KEPT
    { type: 'event_msg', payload: { type: 'token_count', info: usage(500) } },
  ];
  fs.writeFileSync(path.join(dir, `rollout-${sessionId}.jsonl`), lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
}

run('listCalls (windowBy default "ts") drops a call dated before --since even though its FILE mtime is recent', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '08', '15');
  writeStraddlingRollout(dir, 'straddle-session-1');
  const sinceMs = Date.parse('2026-08-01T00:00:00.000Z');
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex'), sinceMs });

  const tooEarly = r.calls.find((c) => c.tokens.fresh === 100);
  assert.equal(tooEarly, undefined, 'a call timestamped before --since must be filtered out by ts, not just kept because its file mtime is recent');

  const inWindow = r.calls.find((c) => c.tokens.fresh === 300);
  assert.ok(inWindow, 'a call timestamped inside the window must be kept');
});

run('listCalls keeps a call with no parseable ts (unjudgeable) and counts it in unfilterableTs', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '08', '15');
  writeStraddlingRollout(dir, 'straddle-session-2');
  const sinceMs = Date.parse('2026-08-01T00:00:00.000Z');
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex'), sinceMs });

  const noTs = r.calls.find((c) => c.tokens.fresh === 500);
  assert.ok(noTs, 'a call with no timestamp at all must be kept, never silently dropped');
  assert.equal(noTs.ts, null);
  assert.equal(r.unfilterableTs, 1);
});

run('listCalls with windowBy:"mtime" keeps every call regardless of its own ts (opt-out semantics)', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '08', '15');
  writeStraddlingRollout(dir, 'straddle-session-3');
  const sinceMs = Date.parse('2026-08-01T00:00:00.000Z');
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex'), sinceMs, windowBy: 'mtime' });

  assert.ok(r.calls.some((c) => c.tokens.fresh === 100), 'mtime windowing must NOT drop the pre-window call -- the whole point of the opt-out');
  assert.equal(r.unfilterableTs, undefined, 'the unfilterableTs key is only present under ts-windowed filtering');
});

run('listCalls with no sinceMs/untilMs applies no per-call filtering and omits unfilterableTs (unchanged shape)', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '08', '15');
  writeStraddlingRollout(dir, 'straddle-session-4');
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex') });

  assert.equal(r.calls.length, 3, 'all three calls survive when no window is given at all');
  assert.equal(r.unfilterableTs, undefined);
});

run('listCalls drops a session summary entirely when none of its calls survive the ts window', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ledger-index-test-'));
  const dir = path.join(tmp, 'codex', 'sessions', '2026', '07', '01');
  fs.mkdirSync(dir, { recursive: true });
  const oldOnly = [
    { timestamp: '2026-07-01T00:00:00.000Z', type: 'session_meta', payload: { id: 'all-old-session', cwd: 'F:\\AllOld', model_provider: 'openai' } },
    { timestamp: '2026-07-01T00:00:01.000Z', type: 'turn_context', payload: { turn_id: 't1', model: 'gpt-5.6' } },
    {
      timestamp: '2026-07-01T00:00:02.000Z',
      type: 'event_msg',
      payload: {
        type: 'token_count',
        info: {
          last_token_usage: { input_tokens: 10, cached_input_tokens: 0, output_tokens: 1, reasoning_output_tokens: 0, total_tokens: 11 },
          total_token_usage: { input_tokens: 10, cached_input_tokens: 0, output_tokens: 1, reasoning_output_tokens: 0, total_tokens: 11 },
        },
      },
    },
  ];
  fs.writeFileSync(path.join(dir, 'rollout-all-old.jsonl'), oldOnly.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
  const sinceMs = Date.parse('2026-08-01T00:00:00.000Z');
  const r = listCalls({ harnesses: ['codex-cli'], codexHome: path.join(tmp, 'codex'), sinceMs });
  assert.equal(r.calls.length, 0);
  assert.equal(r.sessions.length, 0, 'a session with zero in-window calls must not appear in the sessions summary');
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`index.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`index.test: ${passed} passed`);
