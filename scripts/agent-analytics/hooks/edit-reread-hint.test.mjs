/**
 * Tempdoc 727 F-7a — unit tests for edit-reread-hint's decision logic.
 *
 * Exercises the pure `buildRereadContext(input)` function directly. Populates
 * intervene.mjs's real per-session read-tracking cache with a synthetic fixture (the same
 * approach intervene.test.mjs uses for `getOtherPathsWithSameBasename`) rather than mocking.
 *
 * Run with: `node scripts/agent-analytics/hooks/edit-reread-hint.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { buildRereadContext } from './edit-reread-hint.mjs';
import { telemetryDir } from '../lib/hook-base.mjs';

let passed = 0;
const failures = [];

function check(label, input, expectMatch) {
  try {
    const result = buildRereadContext(input);
    if (expectMatch) {
      assert.ok(result, `${label}: expected a non-null additionalContext`);
    } else {
      assert.equal(result, null, `${label}: expected null, got ${JSON.stringify(result)}`);
    }
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const sessionId = `edit-reread-hint-test-${process.pid}-${Date.now()}`;
const cacheFile = path.join(telemetryDir, `read-counts-${sessionId}.json`);
fs.mkdirSync(telemetryDir, { recursive: true });
fs.writeFileSync(cacheFile, JSON.stringify({
  'f:/repo/tempdoc.md': { total: 1, unbounded: 1 },
  _byBasename: { 'tempdoc.md': ['f:/repo/tempdoc.md'] },
}));

try {
  check('cross-root match: not-read error + same-basename read elsewhere → context emitted', {
    tool_name: 'Edit',
    session_id: sessionId,
    error: 'File has not been read yet. Read it first before writing to it.',
    tool_input: { file_path: 'f:/repo/.claude/worktrees/x/tempdoc.md' },
  }, true);

  check('cross-root match: "modified since read" phrasing also matches', {
    tool_name: 'Edit',
    session_id: sessionId,
    error: 'Error: File has been modified since read, either by the user or by a linter.',
    tool_input: { file_path: 'f:/repo/.claude/worktrees/x/tempdoc.md' },
  }, true);

  check('no cross-root match: same path as the one already read → silent', {
    tool_name: 'Edit',
    session_id: sessionId,
    error: 'File has not been read yet.',
    tool_input: { file_path: 'f:/repo/tempdoc.md' },
  }, false);

  check('no cross-root match: different basename entirely → silent', {
    tool_name: 'Edit',
    session_id: sessionId,
    error: 'File has not been read yet.',
    tool_input: { file_path: 'f:/repo/other-file.md' },
  }, false);

  check('not a reread-shaped error → silent even with a cross-root match available', {
    tool_name: 'Edit',
    session_id: sessionId,
    error: 'String to replace not found in file.',
    tool_input: { file_path: 'f:/repo/.claude/worktrees/x/tempdoc.md' },
  }, false);

  check('not an Edit failure → silent', {
    tool_name: 'Bash',
    session_id: sessionId,
    error: 'File has not been read yet.',
    tool_input: { file_path: 'f:/repo/.claude/worktrees/x/tempdoc.md' },
  }, false);

  check('unknown session → silent (no cache to match against)', {
    tool_name: 'Edit',
    session_id: 'no-such-session',
    error: 'File has not been read yet.',
    tool_input: { file_path: 'f:/repo/.claude/worktrees/x/tempdoc.md' },
  }, false);

  check('missing file_path → silent', {
    tool_name: 'Edit',
    session_id: sessionId,
    error: 'File has not been read yet.',
    tool_input: {},
  }, false);

  check('null input → silent', null, false);
} finally {
  fs.rmSync(cacheFile, { force: true });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`edit-reread-hint.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`edit-reread-hint.test: all ${passed} checks passed`);
