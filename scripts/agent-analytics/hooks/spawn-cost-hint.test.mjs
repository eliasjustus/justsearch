/**
 * Tempdoc 886 §12 PR 4 — unit tests for spawn-cost-hint's resolution + rendering.
 *
 * Run with: `node scripts/agent-analytics/hooks/spawn-cost-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { resolveAgentFile, costOfCalls, renderSpawnCostLine } from './spawn-cost-hint.mjs';

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

const HOOK = resolve(dirname(fileURLToPath(import.meta.url)), 'spawn-cost-hint.mjs');

function makeFixtureTree() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'spawn-cost-hint-'));
  const projectDir = path.join(root, 'F--fixture-project');
  const sessionId = 'main-session-1';
  const transcriptPath = path.join(projectDir, `${sessionId}.jsonl`);
  const subagentsDir = path.join(projectDir, sessionId, 'subagents');
  fs.mkdirSync(subagentsDir, { recursive: true });
  fs.writeFileSync(transcriptPath, '');

  fs.writeFileSync(
    path.join(subagentsDir, 'agent-x.meta.json'),
    JSON.stringify({
      agentType: 'general-purpose',
      description: 'fixture spawn',
      model: 'sonnet',
      toolUseId: 'toolu_test1',
      spawnDepth: 1,
    }),
  );
  const lines = [
    { type: 'user', timestamp: '2026-09-01T00:00:00.000Z', message: { content: 'go' } },
    {
      type: 'assistant',
      timestamp: '2026-09-01T00:00:01.000Z',
      message: {
        id: 'sub-msg-1',
        model: 'claude-sonnet-5',
        content: [{ type: 'text', text: 'ok' }],
        usage: { input_tokens: 1000, cache_read_input_tokens: 2000, cache_creation_input_tokens: 0, output_tokens: 500 },
      },
    },
  ];
  fs.writeFileSync(path.join(subagentsDir, 'agent-x.jsonl'), lines.map((l) => JSON.stringify(l)).join('\n') + '\n');

  return { root, projectDir, sessionId, transcriptPath, subagentsDir };
}

function runHook(stdin) {
  return execFileSync('node', [HOOK], { input: JSON.stringify(stdin), encoding: 'utf8' });
}

// --- resolveAgentFile: primary toolUseId join ---
run('resolves via matching toolUseId', () => {
  const metaFiles = [{ name: 'agent-x.meta.json', meta: { toolUseId: 'toolu_1', model: 'sonnet' } }];
  const r = resolveAgentFile('/subagents', metaFiles, { tool_use_id: 'toolu_1' });
  assert.equal(r.file, path.join('/subagents', 'agent-x.jsonl'));
  assert.equal(r.meta.model, 'sonnet');
});

run('no toolUseId match and no agentId in response -> null', () => {
  const metaFiles = [{ name: 'agent-x.meta.json', meta: { toolUseId: 'toolu_other' } }];
  const r = resolveAgentFile('/subagents', metaFiles, { tool_use_id: 'toolu_1', tool_response: 'plain text' });
  assert.equal(r, null);
});

// --- resolveAgentFile: fallback agentId-in-text join ---
run('resolves via agentId fallback when toolUseId absent', () => {
  const metaFiles = [{ name: 'agent-abc123.meta.json', meta: { description: 'async fixture' } }];
  const r = resolveAgentFile('/subagents', metaFiles, {
    tool_response: 'Async agent launched.\nagentId: abc123 (internal ID)',
  });
  assert.equal(r.file, path.join('/subagents', 'agent-abc123.jsonl'));
  assert.equal(r.meta.description, 'async fixture');
});

run('agentId fallback works from a tool_response content-block array', () => {
  const r = resolveAgentFile('/subagents', [], {
    tool_response: [{ type: 'text', text: 'launched\nagentId: deadbeef\n' }],
  });
  assert.equal(r.file, path.join('/subagents', 'agent-deadbeef.jsonl'));
});

// --- costOfCalls ---
run('costOfCalls sums priced calls', () => {
  const calls = [
    { model: 'claude-sonnet-5', ts: '2026-09-01T00:00:00.000Z', tokens: { fresh: 1_000_000, output: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0 } },
  ];
  // sonnet-5 input rate is $2/1M -> 1M fresh tokens costs $2.
  assert.deepEqual(costOfCalls(calls), { total: 2, priced: 1, unpriced: 0 });
});
run('costOfCalls reports priced:0 (not null) on an unknown model', () => {
  assert.deepEqual(costOfCalls([{ model: 'totally-unknown-model-xyz', tokens: {} }]), {
    total: 0,
    priced: 0,
    unpriced: 1,
  });
});
run('costOfCalls sums the priced calls and counts an unpriced one alongside them, not voiding the total', () => {
  const calls = [
    { model: 'claude-sonnet-5', ts: '2026-09-01T00:00:00.000Z', tokens: { fresh: 1_000_000, output: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0 } },
    { model: '<synthetic>', ts: '2026-09-01T00:00:01.000Z', tokens: { fresh: 500_000, output: 0 } },
    { model: 'claude-sonnet-5', ts: '2026-09-01T00:00:02.000Z', tokens: { fresh: 1_000_000, output: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0 } },
  ];
  assert.deepEqual(costOfCalls(calls), { total: 4, priced: 2, unpriced: 1 });
});

// --- renderSpawnCostLine ---
run('renders a one-line summary with all fields', () => {
  const calls = [
    { model: 'claude-sonnet-5', ts: '2026-09-01T00:00:00.000Z', contextTokens: 300000, tokens: { fresh: 0, output: 500000, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0 } },
  ];
  const line = renderSpawnCostLine(calls, { model: 'sonnet', description: 'a fixture task' });
  assert.ok(line.startsWith('spawn-cost: 1 calls, peak ctx 300k, out 500k, model claude-sonnet-5 (requested sonnet)'));
  assert.ok(line.includes('a fixture task'));
});
run('renders null for empty calls', () => {
  assert.equal(renderSpawnCostLine([], {}), null);
});
run('renders (+N unpriced) alongside a priced total when one call among several is unpriced (e.g. <synthetic>)', () => {
  const calls = [
    { model: 'claude-sonnet-5', ts: '2026-09-01T00:00:00.000Z', contextTokens: 100000, tokens: { fresh: 1_000_000, output: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0 } },
    { model: '<synthetic>', ts: '2026-09-01T00:00:01.000Z', contextTokens: 50000, tokens: { fresh: 0, output: 0 } },
  ];
  const line = renderSpawnCostLine(calls, { model: 'sonnet', description: 'partly-synthetic spawn' });
  assert.ok(line.includes('~$2.00 (+1 unpriced)'));
  assert.ok(!line.includes('n/a'));
});
run('renders n/a only when ZERO calls are priceable', () => {
  const calls = [{ model: '<synthetic>', ts: '2026-09-01T00:00:00.000Z', contextTokens: 1000, tokens: {} }];
  const line = renderSpawnCostLine(calls, {});
  assert.ok(line.includes(', n/a — '));
});

// --- end-to-end hook process ---
run('positive: emits additionalContext when the spawn resolves via toolUseId', () => {
  const { transcriptPath, sessionId } = makeFixtureTree();
  const out = runHook({
    hook_event_name: 'PostToolUse',
    session_id: sessionId,
    transcript_path: transcriptPath,
    tool_name: 'Agent',
    tool_use_id: 'toolu_test1',
    tool_input: { model: 'sonnet', description: 'fixture spawn' },
    tool_response: 'done',
  });
  const parsed = JSON.parse(out);
  assert.equal(parsed.hookSpecificOutput.hookEventName, 'PostToolUse');
  assert.ok(parsed.hookSpecificOutput.additionalContext.startsWith('spawn-cost:'));
  assert.ok(parsed.hookSpecificOutput.additionalContext.includes('fixture spawn'));
});

run('negative: silent (empty stdout, exit 0) when no subagents dir exists', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'spawn-cost-hint-empty-'));
  const transcriptPath = path.join(root, 'proj', 'sess.jsonl');
  const out = runHook({
    hook_event_name: 'PostToolUse',
    session_id: 'sess',
    transcript_path: transcriptPath,
    tool_name: 'Agent',
    tool_use_id: 'toolu_nope',
  });
  assert.equal(out.trim(), '');
});

run('negative: silent when tool_name is not Agent', () => {
  const { transcriptPath, sessionId } = makeFixtureTree();
  const out = runHook({
    hook_event_name: 'PostToolUse',
    session_id: sessionId,
    transcript_path: transcriptPath,
    tool_name: 'Bash',
    tool_use_id: 'toolu_test1',
  });
  assert.equal(out.trim(), '');
});

run('kill switch: JUSTSEARCH_DISABLE_HOOKS=1 produces no output', () => {
  const { transcriptPath, sessionId } = makeFixtureTree();
  const out = execFileSync('node', [HOOK], {
    input: JSON.stringify({
      hook_event_name: 'PostToolUse',
      session_id: sessionId,
      transcript_path: transcriptPath,
      tool_name: 'Agent',
      tool_use_id: 'toolu_test1',
    }),
    encoding: 'utf8',
    env: { ...process.env, JUSTSEARCH_DISABLE_HOOKS: '1' },
  });
  assert.equal(out.trim(), '');
});

// --- Report ---
if (failures.length > 0) {
  console.error(`spawn-cost-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`spawn-cost-hint.test: all ${passed} checks passed`);
