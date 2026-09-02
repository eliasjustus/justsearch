/**
 * lib/ledger/claude-adapter.test.mjs — unit tests for the Claude Code ledger
 * adapter (tempdoc 886 §12 PR 1), run against the synthetic fixture tree at
 * `scripts/agent-analytics/fixtures/claude/` (no real transcript content).
 *
 * Run with: `node scripts/agent-analytics/lib/ledger/claude-adapter.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { listClaudeCalls, listClaudeTranscriptFiles } from './claude-adapter.mjs';
import { isCall, isToolEvent } from './record.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES_ROOT = path.join(HERE, '..', '..', 'fixtures', 'claude');
const FIXTURE_PROJECT_FILTER = /F--fixture-project/;

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

const { calls, toolEvents, sessions } = listClaudeCalls({
  projectsRoot: FIXTURES_ROOT,
  projectFilter: FIXTURE_PROJECT_FILTER,
});

const mainCalls = calls.filter((c) => c.lineage.kind === 'main');
const spawnCalls = calls.filter((c) => c.lineage.kind === 'spawn');
const forkCalls = calls.filter((c) => c.lineage.kind === 'fork');

// --- discovery -------------------------------------------------------------

run('discovers the fixture project via projectFilter', () => {
  assert.ok(sessions.length >= 3, `expected at least 3 sessions (main + 2 subagents), got ${sessions.length}`);
});

run('every returned Call/ToolEvent passes its own shape check', () => {
  for (const c of calls) assert.ok(isCall(c), `not a valid Call: ${JSON.stringify(c)}`);
  for (const e of toolEvents) assert.ok(isToolEvent(e), `not a valid ToolEvent: ${JSON.stringify(e)}`);
});

// --- message.id dedup (886 §11 A10) -----------------------------------------

run('two JSONL lines sharing one message.id produce exactly one Call', () => {
  const msg1Calls = mainCalls.filter((c) => c.callId === 'msg-1');
  assert.equal(msg1Calls.length, 1, 'msg-1 must be counted once, not twice');
});

run('the main session yields exactly 3 Calls (msg-1, msg-2, msg-3)', () => {
  assert.equal(mainCalls.length, 3);
  assert.deepEqual(mainCalls.map((c) => c.callId), ['msg-1', 'msg-2', 'msg-3']);
});

// --- tool_use -> tool_result join, registered before the dedup skip --------

run('a tool_use on the SECOND line of a deduped message.id still resolves a name', () => {
  // tu-2 (Edit) sits on msg-1's second line, after usage was already counted
  // once from the first line -- the name must still resolve.
  const editEvent = toolEvents.find((e) => e.name === 'Edit' && e.sessionId === 'session-fixture-1');
  assert.ok(editEvent, 'Edit tool event must be present');
  assert.equal(editEvent.role, 'edit');
  assert.equal(editEvent.callRef, 'msg-1');
  assert.equal(editEvent.outputChars, 'applied'.length);
});

run('tool_use inputChars comes from the tool_use block, outputChars from tool_result', () => {
  const bashEvent = toolEvents.find((e) => e.name === 'Bash' && e.sessionId === 'session-fixture-1');
  assert.ok(bashEvent);
  assert.equal(bashEvent.role, 'shell');
  assert.equal(bashEvent.inputChars, JSON.stringify({ command: 'ls' }).length);
  assert.equal(bashEvent.outputChars, 'file1\nfile2'.length);
  assert.equal(bashEvent.isError, false);
});

// --- compaction boundary + compactMetadata ----------------------------------

run('the compact_boundary line attaches compactionBoundary to the NEXT call (msg-2)', () => {
  const msg2 = mainCalls.find((c) => c.callId === 'msg-2');
  assert.ok(msg2);
  assert.equal(msg2.compactionBoundary, true);
  assert.deepEqual(msg2.compactMetadata, { trigger: 'auto', preTokens: 180000, postTokens: 20000, durationMs: 1500 });

  const msg1 = mainCalls.find((c) => c.callId === 'msg-1');
  const msg3 = mainCalls.find((c) => c.callId === 'msg-3');
  assert.equal(msg1.compactionBoundary, false);
  assert.equal(msg3.compactionBoundary, false);
});

// --- 1h cache-write split ----------------------------------------------------

run('a tiered 1h cache_creation split resolves to cacheWrite1h, not cacheWrite5m', () => {
  const msg2 = mainCalls.find((c) => c.callId === 'msg-2');
  assert.equal(msg2.tokens.cacheWrite1h, 90000);
  assert.equal(msg2.tokens.cacheWrite5m, 0);
  assert.equal(msg2.contextTokens, 50 + 0 + 90000);
});

run('an untiered cache_creation_input_tokens falls back to the 5m tier', () => {
  const msg1 = mainCalls.find((c) => c.callId === 'msg-1');
  assert.equal(msg1.tokens.cacheWrite5m, 500);
  assert.equal(msg1.tokens.cacheWrite1h, 0);
  assert.equal(msg1.contextTokens, 100 + 2000 + 500);
});

// --- task-notification wait event -------------------------------------------

run('a user turn carrying <task-notification> emits a wait ToolEvent', () => {
  const waitEvent = toolEvents.find((e) => e.role === 'wait' && e.sessionId === 'session-fixture-1');
  assert.ok(waitEvent);
  assert.equal(waitEvent.name, 'task-notification');
});

// --- subagent lineage: spawn vs fork -----------------------------------------

run('a non-fork subagent (agentType: general-purpose) gets lineage.kind = spawn', () => {
  assert.equal(spawnCalls.length, 1);
  const c = spawnCalls[0];
  assert.equal(c.lineage.parentSessionId, 'session-fixture-1');
  assert.equal(c.lineage.agentType, 'general-purpose');
  assert.equal(c.lineage.requestedModel, 'claude-sonnet-5');
  assert.equal(c.lineage.description, 'fixture subagent for claude-adapter tests');
});

run('a subagent with agentType "fork" gets lineage.kind = fork', () => {
  assert.equal(forkCalls.length, 1);
  const c = forkCalls[0];
  assert.equal(c.lineage.parentSessionId, 'session-fixture-1');
  assert.equal(c.lineage.agentType, 'fork');
});

run('subagent Calls carry the subagent provider/harness, not the parent\'s literal sessionId', () => {
  for (const c of [...spawnCalls, ...forkCalls]) {
    assert.equal(c.harness, 'claude-code');
    assert.notEqual(c.sessionId, 'session-fixture-1');
    assert.ok(c.sessionId.startsWith('session-fixture-1:'));
  }
});

// --- discovery tolerates a missing root -------------------------------------

run('listClaudeCalls returns an empty result for a nonexistent projectsRoot, never throws', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-adapter-test-'));
  const missing = path.join(tmp, 'does-not-exist');
  const r = listClaudeCalls({ projectsRoot: missing });
  assert.deepEqual(r, { calls: [], toolEvents: [], sessions: [] });
});

// --- listClaudeTranscriptFiles (cache-efficiency.mjs's discovery) -----------

run('listClaudeTranscriptFiles finds the main file and both subagent files, tagged by kind', () => {
  const files = listClaudeTranscriptFiles({ projectsRoot: FIXTURES_ROOT, projectFilter: FIXTURE_PROJECT_FILTER });
  const main = files.filter((f) => f.kind === 'main');
  const sub = files.filter((f) => f.kind === 'subagent');
  assert.equal(main.length, 1);
  assert.equal(sub.length, 2);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`claude-adapter.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`claude-adapter.test: ${passed} passed`);
