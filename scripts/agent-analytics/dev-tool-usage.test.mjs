/**
 * Tempdoc 844 P7 — unit tests for the dev MCP tool usage reader.
 *
 * Precision matters in both directions here, same as cache-efficiency.test.mjs:
 * the tool_use/tool_result join must not double- or under-count (a naive grep
 * is the bug this reader exists to avoid), the ok-false false-positive must be
 * PINNED rather than "fixed" (844's own §3 numbers assume this exact
 * behaviour), and the first-call success rate must exclude retries from both
 * numerator and denominator rather than silently double-penalizing one
 * incident.
 *
 * Run with: `node scripts/agent-analytics/dev-tool-usage.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  splitServerAndTool,
  extractToolUseBlocks,
  extractToolResultBlocks,
  resultBodyText,
  resultByteSize,
  classifyResultBlock,
  markRetries,
  analyzeFile,
  buildReport,
} from './dev-tool-usage.mjs';

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

function writeTranscript(lines) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-tool-usage-'));
  const file = path.join(dir, 'session.jsonl');
  fs.writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n');
  return { dir, file };
}

function cleanup(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

function toolUseEntry({ ts, id, name }) {
  return {
    type: 'assistant',
    timestamp: ts,
    message: { content: [{ type: 'tool_use', id, name, input: {} }] },
  };
}

function toolResultEntry({ ts, toolUseId, isError = false, text = '{"ok":true}' }) {
  return {
    type: 'user',
    timestamp: ts,
    message: { content: [{ type: 'tool_result', tool_use_id: toolUseId, is_error: isError, content: [{ type: 'text', text }] }] },
  };
}

// --- splitServerAndTool -------------------------------------------------------

run('splits mcp__<server>__<tool> at the first double-underscore', () => {
  assert.deepEqual(
    splitServerAndTool('mcp__justsearch-dev__justsearch_dev_start'),
    { server: 'justsearch-dev', tool: 'justsearch_dev_start' },
  );
});

run('a server name containing underscores still splits at the FIRST __', () => {
  assert.deepEqual(
    splitServerAndTool('mcp__claude_ai_Gmail__send_message'),
    { server: 'claude_ai_Gmail', tool: 'send_message' },
  );
});

run('a non-mcp tool name returns null', () => {
  assert.equal(splitServerAndTool('Bash'), null);
});

// --- extractToolUseBlocks / extractToolResultBlocks --------------------------

run('extractToolUseBlocks ignores non-tool_use content blocks', () => {
  const entry = { message: { content: [{ type: 'text', text: 'hi' }, { type: 'tool_use', id: 't1', name: 'mcp__x__y' }] } };
  assert.deepEqual(extractToolUseBlocks(entry), [{ id: 't1', name: 'mcp__x__y' }]);
});

run('extractToolResultBlocks ignores blocks without a tool_use_id', () => {
  const entry = { message: { content: [{ type: 'tool_result', is_error: false }, { type: 'tool_result', tool_use_id: 'a' }] } };
  assert.equal(extractToolResultBlocks(entry).length, 1);
});

// --- resultBodyText / resultByteSize ------------------------------------------

run('resultByteSize sums text length for a string content', () => {
  assert.equal(resultByteSize({ content: 'hello' }), 5);
});

run('resultByteSize sums text blocks plus image source.data length', () => {
  const block = {
    content: [
      { type: 'text', text: 'abcde' },
      { type: 'image', source: { data: '0123456789' } },
      { type: 'text', text: 'xy' },
    ],
  };
  assert.equal(resultByteSize(block), 5 + 10 + 2);
});

run('resultBodyText joins only text blocks, ignoring image blocks', () => {
  const block = { content: [{ type: 'text', text: 'a' }, { type: 'image', source: { data: 'zz' } }, { type: 'text', text: 'b' }] };
  assert.equal(resultBodyText(block), 'a\nb');
});

// --- classifyResultBlock -------------------------------------------------------

run('is_error:true is an error regardless of body text', () => {
  const c = classifyResultBlock({ is_error: true, content: [{ type: 'text', text: 'fine' }] });
  assert.equal(c.isError, true);
});

run('a body matching "ok":false is classified as an error', () => {
  const c = classifyResultBlock({ is_error: false, content: [{ type: 'text', text: '{"ok":false,"code":"OWNER_CONFLICT"}' }] });
  assert.equal(c.isError, true);
  assert.equal(c.code, 'OWNER_CONFLICT');
});

run('a body matching "error":{ is classified as an error', () => {
  const c = classifyResultBlock({ content: [{ type: 'text', text: '{"error":{"message":"boom"}}' }] });
  assert.equal(c.isError, true);
});

run('KNOWN FALSE POSITIVE (844 1, pinned): a successful result whose TEXT BODY happens to '
  + 'contain the ok-false marker is still classified as an error', () => {
  // e.g. tail_log returning worker-log content that itself contains the literal
  // string — the tool call succeeded, but the reader cannot distinguish a
  // JSON envelope's own field from quoted log content. 844's own numbers
  // assume this exact behaviour ("error counts are an upper bound, +/-1").
  const c = classifyResultBlock({
    is_error: false,
    content: [{ type: 'text', text: 'worker log tail:\n2026-08-18 WARN payload was {"ok":false} per client request\n' }],
  });
  assert.equal(c.isError, true);
});

run('a clean success body with no markers is not an error', () => {
  const c = classifyResultBlock({ is_error: false, content: [{ type: 'text', text: '{"ok":true,"port":33221}' }] });
  assert.equal(c.isError, false);
  assert.equal(c.code, null);
});

// --- markRetries ---------------------------------------------------------------

run('a call after a success is not a retry', () => {
  const out = markRetries([{ isError: false }, { isError: false }]);
  assert.deepEqual(out.map((o) => o.isRetry), [false, false]);
});

run('the call immediately after an error is a retry; the one after that is not', () => {
  const out = markRetries([{ isError: true }, { isError: false }, { isError: false }]);
  assert.deepEqual(out.map((o) => o.isRetry), [false, true, false]);
});

run('a run of errors marks every follower a retry', () => {
  const out = markRetries([{ isError: true }, { isError: true }, { isError: true }, { isError: false }]);
  assert.deepEqual(out.map((o) => o.isRetry), [false, true, true, true]);
});

// --- analyzeFile: the tool_use/tool_result join --------------------------------

run('analyzeFile joins tool_result to tool_use by id, not by adjacency or count', () => {
  const { dir, file } = writeTranscript([
    toolUseEntry({ ts: '2026-08-18T00:00:00.000Z', id: 'u1', name: 'mcp__justsearch-dev__justsearch_dev_start' }),
    toolUseEntry({ ts: '2026-08-18T00:00:01.000Z', id: 'u2', name: 'mcp__justsearch-dev__justsearch_dev_stop' }),
    // results arrive out of order and interleaved — join must use the id, not position
    toolResultEntry({ ts: '2026-08-18T00:00:02.000Z', toolUseId: 'u2', text: '{"ok":true}' }),
    toolResultEntry({ ts: '2026-08-18T00:00:03.000Z', toolUseId: 'u1', isError: true, text: '{"ok":false,"code":"UNHANDLED"}' }),
  ]);
  const records = [];
  analyzeFile(file, 'sess-1', records, { n: 0 });
  cleanup(dir);

  assert.equal(records.length, 2);
  const start = records.find((r) => r.toolFullName === 'mcp__justsearch-dev__justsearch_dev_start');
  const stop = records.find((r) => r.toolFullName === 'mcp__justsearch-dev__justsearch_dev_stop');
  assert.equal(start.isError, true);
  assert.equal(start.code, 'UNHANDLED');
  assert.equal(stop.isError, false);
});

run('analyzeFile counts invocations from tool_use blocks only — a repeated name in '
  + 'result text does not inflate the count', () => {
  const { dir, file } = writeTranscript([
    toolUseEntry({ ts: '2026-08-18T00:00:00.000Z', id: 'u1', name: 'mcp__justsearch-dev__justsearch_dev_quick_health' }),
    toolResultEntry({
      ts: '2026-08-18T00:00:01.000Z', toolUseId: 'u1',
      // the result body mentions the tool's own name several times, the way a
      // naive grep-based counter would double-count; the join must not.
      text: '{"ok":true,"tool":"mcp__justsearch-dev__justsearch_dev_quick_health","notes":"mcp__justsearch-dev__justsearch_dev_quick_health ran fine"}',
    }),
  ]);
  const records = [];
  analyzeFile(file, 'sess-1', records, { n: 0 });
  cleanup(dir);
  assert.equal(records.length, 1);
});

run('a tool_use with no joined result is not counted as an error', () => {
  const { dir, file } = writeTranscript([
    toolUseEntry({ ts: '2026-08-18T00:00:00.000Z', id: 'u1', name: 'mcp__justsearch-dev__justsearch_dev_start' }),
  ]);
  const records = [];
  analyzeFile(file, 'sess-1', records, { n: 0 });
  cleanup(dir);
  assert.equal(records.length, 1);
  assert.equal(records[0].isError, false);
  assert.equal(records[0].hasResult, false);
});

// --- buildReport: first-call success rate --------------------------------------

run('first-call success rate excludes retries from both numerator and denominator', () => {
  // one session, one tool, four calls: fail, retry-success, ordinary success, ordinary success.
  // non-retry calls are #1 (fail) and #3/#4 (both success), so 3 non-retry calls, 2 succeed => 2/3.
  const records = [
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_api_call', sessionKey: 's1', ts: 1, seq: 0, isError: true, code: null, bytes: 10, hasResult: true },
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_api_call', sessionKey: 's1', ts: 2, seq: 1, isError: false, code: null, bytes: 10, hasResult: true },
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_api_call', sessionKey: 's1', ts: 3, seq: 2, isError: false, code: null, bytes: 10, hasResult: true },
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_api_call', sessionKey: 's1', ts: 4, seq: 3, isError: false, code: null, bytes: 10, hasResult: true },
  ];
  const report = buildReport(records, { server: 'justsearch-dev' });
  const row = report.perTool.find((r) => r.shortName === 'api_call');
  assert.equal(row.calls, 4);
  assert.equal(row.errors, 1);
  assert.equal(row.nonRetry, 3);
  assert.equal(row.nonRetrySuccess, 2);
  assert.equal(row.firstCallSuccessRate, 2 / 3);
});

run('retries only count within the SAME session — two sessions each get their own first call', () => {
  const records = [
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_ingest', sessionKey: 's1', ts: 1, seq: 0, isError: true, code: null, bytes: 0, hasResult: true },
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_ingest', sessionKey: 's2', ts: 1, seq: 1, isError: false, code: null, bytes: 0, hasResult: true },
  ];
  const report = buildReport(records, { server: 'justsearch-dev' });
  const row = report.perTool.find((r) => r.shortName === 'ingest');
  // both are non-retries (each is the first call of its own session)
  assert.equal(row.nonRetry, 2);
  assert.equal(row.nonRetrySuccess, 1);
});

run('a tool with zero calls has a null (n/a) first-call success rate, not 0 or 1', () => {
  const report = buildReport([], { server: 'justsearch-dev', registeredFull: ['mcp__justsearch-dev__justsearch_dev_reload'] });
  const row = report.perTool.find((r) => r.shortName === 'reload');
  assert.equal(row.calls, 0);
  assert.equal(row.firstCallSuccessRate, null);
});

// --- buildReport: never-invoked registered tool appears with 0 -----------------

run('a registered-but-never-invoked tool appears in perTool with 0 calls, not omitted', () => {
  const records = [
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_start', sessionKey: 's1', ts: 1, seq: 0, isError: false, code: null, bytes: 100, hasResult: true },
  ];
  const registeredFull = [
    'mcp__justsearch-dev__justsearch_dev_start',
    'mcp__justsearch-dev__justsearch_dev_reload',
    'mcp__justsearch-dev__justsearch_dev_agent_chat',
  ];
  const report = buildReport(records, { server: 'justsearch-dev', registeredFull });
  const names = report.perTool.map((r) => r.shortName).sort();
  assert.deepEqual(names, ['agent_chat', 'reload', 'start']);
  const reload = report.perTool.find((r) => r.shortName === 'reload');
  assert.equal(reload.calls, 0);
  assert.equal(reload.errors, 0);
  assert.equal(reload.sessions, 0);
});

run('totals aggregate across all rows, including zero-call registered tools', () => {
  const records = [
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_start', sessionKey: 's1', ts: 1, seq: 0, isError: false, code: null, bytes: 100, hasResult: true },
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_start', sessionKey: 's2', ts: 1, seq: 1, isError: true, code: 'UNHANDLED', bytes: 50, hasResult: true },
  ];
  const report = buildReport(records, { server: 'justsearch-dev', registeredFull: ['mcp__justsearch-dev__justsearch_dev_reload'] });
  assert.equal(report.totals.calls, 2);
  assert.equal(report.totals.errors, 1);
  assert.equal(report.totals.errorRate, 0.5);
});

// --- buildReport: all-MCP-servers proportion section ----------------------------

run('perServer rolls up calls and bytes across every server, unaffected by the --server filter', () => {
  const records = [
    { toolFullName: 'mcp__justsearch-dev__justsearch_dev_start', sessionKey: 's1', ts: 1, seq: 0, isError: false, code: null, bytes: 100, hasResult: true },
    { toolFullName: 'mcp__claude-in-chrome__navigate', sessionKey: 's1', ts: 2, seq: 1, isError: false, code: null, bytes: 5000, hasResult: true },
    { toolFullName: 'mcp__claude-in-chrome__navigate', sessionKey: 's1', ts: 3, seq: 2, isError: false, code: null, bytes: 5000, hasResult: true },
  ];
  const report = buildReport(records, { server: 'justsearch-dev' });
  const byName = Object.fromEntries(report.perServer.map((s) => [s.server, s]));
  assert.equal(byName['justsearch-dev'].calls, 1);
  assert.equal(byName['justsearch-dev'].bytes, 100);
  assert.equal(byName['claude-in-chrome'].calls, 2);
  assert.equal(byName['claude-in-chrome'].bytes, 10000);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`dev-tool-usage.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`dev-tool-usage.test: ${passed} passed`);
