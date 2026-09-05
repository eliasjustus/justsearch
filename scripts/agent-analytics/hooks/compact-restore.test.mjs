/**
 * Tempdoc 926 P1 — provenance, one-shot delivery, and delete-only legacy
 * cleanup for compact-restore.
 *
 * Run with: `node scripts/agent-analytics/hooks/compact-restore.test.mjs`
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  buildContext,
  decideAction,
  handleSessionStart,
  resolveGitWorkspace,
  stateMatchesCurrentWorkspace,
} from './compact-restore.mjs';

let passed = 0;
const failures = [];

function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

function git(cwd, ...args) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function writeState(telemetryDir, sessionId, state) {
  fs.mkdirSync(telemetryDir, { recursive: true });
  const file = path.join(telemetryDir, `compact-state-${sessionId}.json`);
  fs.writeFileSync(file, JSON.stringify(state), 'utf8');
  return file;
}

function savedState(sessionId, workspace, modifiedFiles = ['changed.txt']) {
  return {
    session_id: sessionId,
    trigger: 'auto',
    workspace_snapshot: {
      observed_at: '2026-09-04T10:00:00.000Z',
      worktree: workspace.worktree,
      branch: workspace.branch,
      modified_files: modifiedFiles,
    },
    read_files: { 'read.txt': { total: 3, unbounded: 1 } },
    edited_files: { 'edited.txt': [1, 2] },
  };
}

run('SessionEnd is a no-op', () => {
  assert.deepEqual(decideAction({ hook_event_name: 'SessionEnd' }), { action: 'noop' });
});
run('SessionStart startup performs legacy cleanup only', () => {
  assert.deepEqual(
    decideAction({ hook_event_name: 'SessionStart', source: 'startup' }),
    { action: 'cleanup' }
  );
});
run('SessionStart compact with id restores', () => {
  assert.deepEqual(
    decideAction({ hook_event_name: 'SessionStart', source: 'compact', session_id: 'sess-1' }),
    { action: 'restore', sessionId: 'sess-1' }
  );
});
run('SessionStart compact without id performs legacy cleanup only', () => {
  assert.deepEqual(
    decideAction({ hook_event_name: 'SessionStart', source: 'compact' }),
    { action: 'cleanup' }
  );
});

const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'compact-restore-test-'));
const mainWorktree = path.join(fixture, 'main');
const secondWorktree = path.join(fixture, 'second');
const telemetryDir = path.join(fixture, 'telemetry');

try {
  fs.mkdirSync(mainWorktree);
  git(mainWorktree, 'init', '-b', 'compact-main');
  git(mainWorktree, 'config', 'user.email', 'compact-test@example.invalid');
  git(mainWorktree, 'config', 'user.name', 'Compact Test');
  fs.writeFileSync(path.join(mainWorktree, 'changed.txt'), 'initial\n');
  git(mainWorktree, 'add', 'changed.txt');
  git(mainWorktree, 'commit', '-m', 'fixture');
  git(mainWorktree, 'worktree', 'add', '-b', 'compact-second', secondWorktree);

  const mainWorkspace = resolveGitWorkspace(mainWorktree);
  const secondWorkspace = resolveGitWorkspace(secondWorktree);

  run('matching session and workspace are accepted', () => {
    assert.equal(
      stateMatchesCurrentWorkspace(savedState('sess-1', mainWorkspace), 'sess-1', mainWorkspace),
      true
    );
  });
  run('saved session id mismatch is rejected', () => {
    assert.equal(
      stateMatchesCurrentWorkspace(savedState('saved-session', mainWorkspace), 'current-session', mainWorkspace),
      false
    );
  });
  run('saved worktree mismatch is rejected', () => {
    assert.equal(
      stateMatchesCurrentWorkspace(savedState('sess-1', mainWorkspace), 'sess-1', secondWorkspace),
      false
    );
  });
  run('saved branch mismatch is rejected', () => {
    const state = savedState('sess-1', mainWorkspace);
    state.workspace_snapshot.branch = 'some-other-branch';
    assert.equal(stateMatchesCurrentWorkspace(state, 'sess-1', mainWorkspace), false);
  });

  run('context labels Git data as a workspace snapshot, not session modifications', () => {
    const context = buildContext(savedState('sess-1', mainWorkspace), mainWorkspace);
    assert.match(context, /Workspace snapshot observed at/);
    assert.match(context, /Modified files observed in that workspace/);
    assert.doesNotMatch(context, /Files modified in this session/i);
  });

  run('unproven legacy modified_files are omitted while session caches survive', () => {
    const state = savedState('legacy-session', mainWorkspace);
    delete state.workspace_snapshot;
    state.modified_files = ['wrong-worktree.txt'];
    const context = buildContext(state, null);
    assert.match(context, /Workspace snapshot omitted/);
    assert.match(context, /read\.txt/);
    assert.doesNotMatch(context, /wrong-worktree\.txt/);
  });

  run('matching state emits one additionalContext, writes no rule, and is consumed once', () => {
    const sessionId = 'matching-session';
    const stateFile = writeState(telemetryDir, sessionId, savedState(sessionId, mainWorkspace));
    const outputs = [];
    const options = { telemetryDir, repoRoot: mainWorktree, writeOutput: value => outputs.push(value) };
    const input = {
      hook_event_name: 'SessionStart',
      source: 'compact',
      session_id: sessionId,
      cwd: mainWorktree,
    };

    assert.equal(handleSessionStart(input, options).action, 'restored');
    assert.equal(outputs.length, 1);
    const output = JSON.parse(outputs[0]);
    assert.equal(output.hookSpecificOutput.hookEventName, 'SessionStart');
    assert.match(output.hookSpecificOutput.additionalContext, /Workspace snapshot observed at/);
    assert.equal(fs.existsSync(path.join(mainWorktree, '.claude', 'rules', 'compaction-state.md')), false);
    assert.equal(fs.existsSync(stateFile), false);

    assert.equal(handleSessionStart(input, options).action, 'missing');
    assert.equal(outputs.length, 1);
  });

  run('session-id mismatch emits nothing and consumes the state', () => {
    const currentSession = 'current-session';
    const stateFile = writeState(
      telemetryDir,
      currentSession,
      savedState('different-saved-session', mainWorkspace)
    );
    const outputs = [];
    const result = handleSessionStart({
      hook_event_name: 'SessionStart',
      source: 'compact',
      session_id: currentSession,
      cwd: mainWorktree,
    }, { telemetryDir, repoRoot: mainWorktree, writeOutput: value => outputs.push(value) });

    assert.equal(result.action, 'discarded');
    assert.deepEqual(outputs, []);
    assert.equal(fs.existsSync(stateFile), false);
  });

  run('two-worktree mismatch omits Git snapshot but keeps session-keyed orientation', () => {
    const sessionId = 'worktree-mismatch';
    const stateFile = writeState(telemetryDir, sessionId, savedState(sessionId, mainWorkspace));
    const outputs = [];
    const result = handleSessionStart({
      hook_event_name: 'SessionStart',
      source: 'compact',
      session_id: sessionId,
      cwd: secondWorktree,
    }, { telemetryDir, repoRoot: mainWorktree, writeOutput: value => outputs.push(value) });

    assert.equal(result.action, 'restored');
    assert.equal(outputs.length, 1);
    const context = JSON.parse(outputs[0]).hookSpecificOutput.additionalContext;
    assert.match(context, /Workspace snapshot omitted/);
    assert.doesNotMatch(context, /Modified files observed in that workspace/);
    assert.match(context, /read\.txt/);
    assert.equal(fs.existsSync(stateFile), false);
  });

  run('non-Git cwd omits Git snapshot but keeps session-keyed orientation', () => {
    const sessionId = 'nongit-cwd';
    const stateFile = writeState(telemetryDir, sessionId, savedState(sessionId, mainWorkspace));
    const outputs = [];
    const result = handleSessionStart({
      hook_event_name: 'SessionStart', source: 'compact', session_id: sessionId, cwd: fixture,
    }, { telemetryDir, repoRoot: mainWorktree, writeOutput: value => outputs.push(value) });
    assert.equal(result.action, 'restored');
    const context = JSON.parse(outputs[0]).hookSpecificOutput.additionalContext;
    assert.match(context, /Workspace snapshot omitted/);
    assert.match(context, /read\.txt/);
    assert.equal(fs.existsSync(stateFile), false);
  });

  run('SessionStart deletes the legacy rule without creating a replacement', () => {
    const legacyRule = path.join(mainWorktree, '.claude', 'rules', 'compaction-state.md');
    fs.mkdirSync(path.dirname(legacyRule), { recursive: true });
    fs.writeFileSync(legacyRule, 'legacy state', 'utf8');

    const result = handleSessionStart({
      hook_event_name: 'SessionStart',
      source: 'startup',
      cwd: mainWorktree,
    }, { telemetryDir, repoRoot: mainWorktree, writeOutput: () => assert.fail('unexpected output') });

    assert.equal(result.action, 'cleanup');
    assert.equal(fs.existsSync(legacyRule), false);
  });

  run('cross-worktree startup deletes legacy rules from both possible old locations', () => {
    const mainLegacyRule = path.join(mainWorktree, '.claude', 'rules', 'compaction-state.md');
    const secondLegacyRule = path.join(secondWorktree, '.claude', 'rules', 'compaction-state.md');
    fs.mkdirSync(path.dirname(mainLegacyRule), { recursive: true });
    fs.mkdirSync(path.dirname(secondLegacyRule), { recursive: true });
    fs.writeFileSync(mainLegacyRule, 'legacy main state', 'utf8');
    fs.writeFileSync(secondLegacyRule, 'legacy second state', 'utf8');

    const result = handleSessionStart({
      hook_event_name: 'SessionStart',
      source: 'startup',
      cwd: secondWorktree,
    }, { telemetryDir, repoRoot: mainWorktree, writeOutput: () => assert.fail('unexpected output') });

    assert.equal(result.action, 'cleanup');
    assert.equal(fs.existsSync(mainLegacyRule), false);
    assert.equal(fs.existsSync(secondLegacyRule), false);
  });

  run('SessionEnd neither deletes nor writes the legacy rule', () => {
    const legacyRule = path.join(mainWorktree, '.claude', 'rules', 'compaction-state.md');
    fs.mkdirSync(path.dirname(legacyRule), { recursive: true });
    fs.writeFileSync(legacyRule, 'legacy state', 'utf8');

    const result = handleSessionStart({
      hook_event_name: 'SessionEnd',
      cwd: mainWorktree,
    }, { telemetryDir, repoRoot: mainWorktree, writeOutput: () => assert.fail('unexpected output') });

    assert.equal(result.action, 'noop');
    assert.equal(fs.readFileSync(legacyRule, 'utf8'), 'legacy state');
    fs.unlinkSync(legacyRule);
  });
} finally {
  fs.rmSync(fixture, { recursive: true, force: true });
}

if (failures.length > 0) {
  console.error(`compact-restore.test: ${failures.length} FAILED, ${passed} passed`);
  for (const failure of failures) console.error(`  x ${failure}`);
  process.exit(1);
}
console.log(`compact-restore.test: all ${passed} checks passed`);
