/**
 * Tempdoc 926 P1 — compact-save must observe the hook event's Git worktree,
 * not whichever repository happens to host the hook script.
 *
 * Run with: `node scripts/agent-analytics/hooks/compact-save.test.mjs`
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { captureWorkspaceSnapshot, saveCompactState } from './compact-save.mjs';

function git(cwd, ...args) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function normalPath(value) {
  const resolved = fs.realpathSync.native(value);
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'compact-save-test-'));
const mainWorktree = path.join(fixture, 'main');
const secondWorktree = path.join(fixture, 'second');

try {
  fs.mkdirSync(mainWorktree);
  git(mainWorktree, 'init', '-b', 'compact-main');
  git(mainWorktree, 'config', 'user.email', 'compact-test@example.invalid');
  git(mainWorktree, 'config', 'user.name', 'Compact Test');
  fs.writeFileSync(path.join(mainWorktree, 'main.txt'), 'initial\n');
  fs.writeFileSync(path.join(mainWorktree, 'second.txt'), 'initial\n');
  git(mainWorktree, 'add', 'main.txt', 'second.txt');
  git(mainWorktree, 'commit', '-m', 'fixture');
  git(mainWorktree, 'worktree', 'add', '-b', 'compact-second', secondWorktree);

  fs.writeFileSync(path.join(mainWorktree, 'main.txt'), 'changed in main\n');
  fs.writeFileSync(path.join(mainWorktree, 'untracked.txt'), 'not yet tracked\n');
  fs.writeFileSync(path.join(secondWorktree, 'second.txt'), 'changed in second\n');

  const mainSnapshot = captureWorkspaceSnapshot(mainWorktree, '2026-09-04T10:00:00.000Z');
  const secondSnapshot = captureWorkspaceSnapshot(secondWorktree, '2026-09-04T10:01:00.000Z');

  assert.deepEqual(mainSnapshot.modified_files, ['main.txt', 'untracked.txt']);
  assert.equal(mainSnapshot.observed_at, '2026-09-04T10:00:00.000Z');
  assert.equal(mainSnapshot.branch, 'compact-main');
  assert.equal(normalPath(mainSnapshot.worktree), normalPath(mainWorktree));

  assert.deepEqual(secondSnapshot.modified_files, ['second.txt']);
  assert.equal(secondSnapshot.observed_at, '2026-09-04T10:01:00.000Z');
  assert.equal(secondSnapshot.branch, 'compact-second');
  assert.equal(normalPath(secondSnapshot.worktree), normalPath(secondWorktree));
  assert.notEqual(normalPath(mainSnapshot.worktree), normalPath(secondSnapshot.worktree));

  assert.equal(captureWorkspaceSnapshot(undefined), null);
  assert.equal(captureWorkspaceSnapshot(fixture), null);

  const telemetryDir = path.join(fixture, 'telemetry');
  const sessionId = 'compact-save-integration';
  fs.mkdirSync(telemetryDir);
  fs.writeFileSync(
    path.join(telemetryDir, `read-counts-${sessionId}.json`),
    JSON.stringify({ 'second.txt': { total: 2 } }),
  );
  fs.writeFileSync(
    path.join(telemetryDir, `edit-counts-${sessionId}.json`),
    JSON.stringify({ 'second.txt': ['2026-09-04T10:00:00.000Z'] }),
  );
  const repeatBuffer = path.join(telemetryDir, `repeat-buffer-${sessionId}.json`);
  fs.writeFileSync(repeatBuffer, '{}');

  const saved = saveCompactState({
    session_id: sessionId,
    trigger: 'auto',
    cwd: secondWorktree,
  }, {
    telemetryDir,
    observedAt: '2026-09-04T10:02:00.000Z',
    memorySummary: 'memory fixture',
  });
  const stored = JSON.parse(fs.readFileSync(saved.statePath, 'utf8'));
  assert.equal(saved.action, 'saved');
  assert.equal(stored.session_id, sessionId);
  assert.equal(stored.memory_summary, 'memory fixture');
  assert.equal(stored.workspace_snapshot.observed_at, '2026-09-04T10:02:00.000Z');
  assert.equal(normalPath(stored.workspace_snapshot.worktree), normalPath(secondWorktree));
  assert.equal(stored.workspace_snapshot.branch, 'compact-second');
  assert.deepEqual(stored.workspace_snapshot.modified_files, ['second.txt']);
  assert.deepEqual(stored.read_files, { 'second.txt': { total: 2 } });
  assert.deepEqual(stored.edited_files, { 'second.txt': ['2026-09-04T10:00:00.000Z'] });
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(telemetryDir, `read-counts-${sessionId}.json`), 'utf8')), {});
  assert.equal(fs.existsSync(repeatBuffer), false);
  assert.deepEqual(saveCompactState({}, { telemetryDir }), { action: 'noop' });

  console.log('compact-save.test: all 24 checks passed');
} finally {
  fs.rmSync(fixture, { recursive: true, force: true });
}
