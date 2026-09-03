#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  buildResponse,
  extractExitCode,
  matcherMatches,
  patchTargets,
  toolAliases,
} from './codex-hook-adapter.mjs';
import { editedFiles } from './maintain-doc-hint.mjs';
import { lastCodexContext, nextCodexThreshold } from './context-ceiling-hint.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..', '..');
const ADAPTER = path.join(HERE, 'codex-hook-adapter.mjs');
let passed = 0;
const failures = [];

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`ok   ${name}`);
  } catch (error) {
    failures.push(`${name}: ${error.stack ?? error.message}`);
    console.error(`FAIL ${name}: ${error.message}`);
  }
}

test('extracts Add/Update/Delete targets and maps Add to Write', () => {
  assert.deepEqual(
    patchTargets('*** Begin Patch\n*** Add File: a.txt\n+x\n*** Update File: b.md\n*** Move to: moved/b.md\n@@\n*** Delete File: c.json\n*** End Patch'),
    [
      { filePath: 'a.txt', toolName: 'Write' },
      { filePath: 'b.md', toolName: 'Edit' },
      { filePath: 'moved/b.md', toolName: 'Edit' },
      { filePath: 'c.json', toolName: 'Edit' },
    ],
  );
});

test('Codex tool aliases match Claude manifest matchers', () => {
  assert.deepEqual(toolAliases('apply_patch'), ['apply_patch', 'Edit', 'Write']);
  assert.equal(matcherMatches('Edit|Write', 'apply_patch'), true);
  assert.equal(matcherMatches('Agent', 'spawn_agent'), true);
  assert.equal(matcherMatches('Bash', 'exec_command'), true);
  assert.equal(matcherMatches('Read', 'apply_patch'), false);
});

test('normalizes structured and model-facing exit codes', () => {
  assert.equal(extractExitCode({ exit_code: 7 }), 7);
  assert.equal(extractExitCode({ metadata: { exitCode: 3 } }), 3);
  assert.equal(extractExitCode('Process exited with code 2'), 2);
  assert.equal(extractExitCode('no exit information'), null);
});

test('combines context and rewrite fields into one Codex response', () => {
  const response = buildResponse('PreToolUse', {
    blocks: [],
    context: ['one', 'one', 'two'],
    systemMessages: [],
    permissionDecision: 'allow',
    permissionDecisionReason: null,
    updatedInput: { sessionId: 's1' },
    continue: true,
  });
  assert.equal(response.hookSpecificOutput.additionalContext, 'one\n\ntwo');
  assert.deepEqual(response.hookSpecificOutput.updatedInput, { sessionId: 's1' });
});

test('maintain-doc transcript reader accepts Codex FileChange items', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-codex-hook-'));
  try {
    const transcript = path.join(dir, 'rollout.jsonl');
    fs.writeFileSync(transcript, JSON.stringify({
      type: 'event_msg',
      payload: {
        type: 'item_completed',
        item: { type: 'FileChange', changes: { 'modules/ui-web/src/shell-v0/probe.ts': { type: 'add' } } },
      },
    }) + '\n');
    assert.deepEqual([...editedFiles(transcript)], ['modules/ui-web/src/shell-v0/probe.ts']);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('context ceiling reads Codex token snapshots and applies ratio thresholds', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-codex-context-'));
  try {
    const transcript = path.join(dir, 'rollout.jsonl');
    fs.writeFileSync(transcript, JSON.stringify({
      type: 'event_msg',
      payload: {
        type: 'token_count',
        info: { last_token_usage: { input_tokens: 230_000 }, model_context_window: 250_000 },
      },
    }) + '\n');
    const snapshot = lastCodexContext(transcript);
    assert.deepEqual(snapshot, { contextTokens: 230_000, contextWindow: 250_000 });
    assert.equal(nextCodexThreshold(snapshot, {}).key, 'codexNotified90');
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('end-to-end adapter preserves the force-push guard', () => {
  const result = spawnSync(process.execPath, [ADAPTER], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    input: JSON.stringify({
      hook_event_name: 'PreToolUse',
      session_id: `codex-adapter-test-${process.pid}`,
      tool_name: 'Bash',
      tool_input: { command: 'git push --force origin main' },
    }),
  });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /Force push is blocked/);
});

test('end-to-end adapter injects Codex session id into justsearch-dev calls', () => {
  const sessionId = `codex-adapter-mcp-test-${process.pid}`;
  const result = spawnSync(process.execPath, [ADAPTER], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    input: JSON.stringify({
      hook_event_name: 'PreToolUse',
      session_id: sessionId,
      tool_name: 'mcp__justsearch-dev__quick_health',
      tool_input: {},
    }),
  });
  assert.equal(result.status, 0);
  const output = JSON.parse(result.stdout);
  assert.equal(output.hookSpecificOutput.permissionDecision, 'allow');
  assert.equal(output.hookSpecificOutput.updatedInput.sessionId, sessionId);
});

if (failures.length > 0) {
  console.error(`\n${failures.length} failure(s):\n${failures.join('\n\n')}`);
  process.exit(1);
}
console.log(`\ncodex-hook-adapter.test: ${passed} passed`);
