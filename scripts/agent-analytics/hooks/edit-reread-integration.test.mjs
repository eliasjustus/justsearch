/**
 * Tempdoc 727 review (Finding: claim-4 test coverage) — end-to-end integration test for the
 * F-7a cross-root re-read mechanism.
 *
 * intervene.test.mjs and edit-reread-hint.test.mjs each test their own pure function against a
 * hand-written synthetic cache fixture — neither proves the real WRITER (`trackRead`, only
 * reachable through intervene.mjs's `main()`) actually produces a cache the READER
 * (`getOtherPathsWithSameBasename`) can correctly consume. This test spawns both hooks as real
 * subprocesses (matching how Claude Code itself invokes them) to prove the full round trip:
 * a real `Read` of file A, a real `Read` of file B (same basename, different directory), then
 * a real Edit-failure on file B correctly names file A as the earlier cross-root read.
 *
 * Run with: `node scripts/agent-analytics/hooks/edit-reread-integration.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { telemetryDir } from '../lib/hook-base.mjs';

const HOOKS_DIR = path.dirname(fileURLToPath(import.meta.url));

function runHookSubprocess(hookFile, payload) {
  const res = spawnSync(process.execPath, [path.join(HOOKS_DIR, hookFile)], {
    input: JSON.stringify(payload),
    encoding: 'utf8',
  });
  return res;
}

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

const rootA = fs.mkdtempSync(path.join(os.tmpdir(), 'edit-reread-e2e-a-'));
const rootB = fs.mkdtempSync(path.join(os.tmpdir(), 'edit-reread-e2e-b-'));
const fileA = path.join(rootA, 'same-name.md').replace(/\\/g, '/');
const fileB = path.join(rootB, 'same-name.md').replace(/\\/g, '/');
fs.writeFileSync(fileA, '# copy under root A\n');
fs.writeFileSync(fileB, '# copy under root B\n');

const sessionId = `edit-reread-e2e-${process.pid}-${Date.now()}`;
const cacheFile = path.join(telemetryDir, `read-counts-${sessionId}.json`);

try {
  run('real Read of file A through the actual intervene.mjs subprocess succeeds', () => {
    const res = runHookSubprocess('intervene.mjs', {
      tool_name: 'Read',
      session_id: sessionId,
      tool_input: { file_path: fileA },
    });
    assert.equal(res.status, 0, `intervene.mjs exited ${res.status}: ${res.stderr}`);
  });

  run('real Read of file B (same basename, different root) through the actual subprocess succeeds', () => {
    const res = runHookSubprocess('intervene.mjs', {
      tool_name: 'Read',
      session_id: sessionId,
      tool_input: { file_path: fileB },
    });
    assert.equal(res.status, 0, `intervene.mjs exited ${res.status}: ${res.stderr}`);
  });

  run('the real cache file now has a _byBasename entry linking both paths', () => {
    const cache = JSON.parse(fs.readFileSync(cacheFile, 'utf8'));
    const linked = cache._byBasename?.['same-name.md'];
    assert.ok(Array.isArray(linked) && linked.includes(fileA) && linked.includes(fileB),
      `expected both paths linked under 'same-name.md', got ${JSON.stringify(linked)}`);
  });

  run('a real Edit-failure on file B, through the actual edit-reread-hint.mjs subprocess, names file A', () => {
    const res = runHookSubprocess('edit-reread-hint.mjs', {
      tool_name: 'Edit',
      session_id: sessionId,
      error: 'File has not been read yet. Read it first before writing to it.',
      tool_input: { file_path: fileB },
    });
    assert.equal(res.status, 0, `edit-reread-hint.mjs exited ${res.status}: ${res.stderr}`);
    assert.ok(res.stdout.length > 0, 'expected non-empty stdout (a cross-root match exists)');
    const parsed = JSON.parse(res.stdout);
    const context = parsed.hookSpecificOutput.additionalContext;
    assert.ok(context.includes('same-name.md'), `expected the note to mention the filename, got: ${context}`);
  });

  run('conversely, a real Edit-failure on file A names file B (symmetry)', () => {
    const res = runHookSubprocess('edit-reread-hint.mjs', {
      tool_name: 'Edit',
      session_id: sessionId,
      error: 'File has not been read yet.',
      tool_input: { file_path: fileA },
    });
    assert.equal(res.status, 0);
    assert.ok(res.stdout.length > 0, 'expected a match in the other direction too');
  });

  run('a real Edit-failure on an UNRELATED path (never read) stays silent', () => {
    const unrelated = path.join(rootA, 'never-read.md').replace(/\\/g, '/');
    const res = runHookSubprocess('edit-reread-hint.mjs', {
      tool_name: 'Edit',
      session_id: sessionId,
      error: 'File has not been read yet.',
      tool_input: { file_path: unrelated },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '', `expected silence, got: ${res.stdout}`);
  });
} finally {
  fs.rmSync(cacheFile, { force: true });
  fs.rmSync(rootA, { recursive: true, force: true });
  fs.rmSync(rootB, { recursive: true, force: true });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`edit-reread-integration.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`edit-reread-integration.test: all ${passed} checks passed`);
