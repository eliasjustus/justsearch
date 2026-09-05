#!/usr/bin/env node

/**
 * Synchronous SessionStart hook — emits one-shot orientation context after
 * compaction.
 *
 * On SessionStart with source === 'compact':
 *   1. Reads tmp/agent-telemetry/compact-state-{sessionId}.json (written by compact-save.mjs)
 *   2. Deletes the compact-state file (one-time use)
 *   3. Verifies the saved session/worktree against the SessionStart event
 *   4. Outputs hookSpecificOutput.additionalContext when provenance matches
 *
 * Every SessionStart also deletes the legacy
 * .claude/rules/compaction-state.md file. It is a migration tombstone only:
 * this hook never writes or reads that rule file.
 *
 * - Synchronous (async: false) — injects context before the continuation
 * - Timeout: 5s
 * - Always exits 0 — never blocks session start
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { readJsonStdin, runHook, repoRoot, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

const LEGACY_RULE_PARTS = ['.claude', 'rules', 'compaction-state.md'];

function compactStatePath(sessionId, telemetryDir = TELEMETRY_DIR) {
  return path.join(telemetryDir, `compact-state-${sessionId}.json`);
}

function cleanupLegacyRulesFile(worktree) {
  try { fs.unlinkSync(path.join(worktree, ...LEGACY_RULE_PARTS)); } catch {}
}

function consumeCompactState(statePath) {
  const claimedPath = `${statePath}.${process.pid}.consumed`;
  try {
    fs.renameSync(statePath, claimedPath);
  } catch {
    return null;
  }
  try {
    return fs.readFileSync(claimedPath, 'utf8');
  } catch {
    return null;
  } finally {
    try { fs.unlinkSync(claimedPath); } catch {}
  }
}

function formatReadFiles(readFiles) {
  // read-counts entries are either a number (old format) or {total, unbounded} (new format)
  const entries = Object.entries(readFiles)
    .map(([file, v]) => [file, typeof v === 'object' && v !== null ? (v.total ?? 0) : (v ?? 0)])
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10);
  if (entries.length === 0) return '';
  const lines = entries.map(([file, count]) => `- ${file} (${count} reads)`);
  return `\nFiles most read this session (may need re-reading):\n${lines.join('\n')}`;
}

function formatEditedFiles(editedFiles) {
  const entries = Object.entries(editedFiles)
    .map(([file, timestamps]) => [file, Array.isArray(timestamps) ? timestamps.length : 0])
    .filter(([, count]) => count > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10);
  if (entries.length === 0) return '';
  const lines = entries.map(([file, count]) => `- ${file} (${count} edits)`);
  return `\nFiles edited this session (check for incomplete changes):\n${lines.join('\n')}`;
}

function formatWorkspaceSnapshot(snapshot) {
  const parts = [
    '',
    `Workspace snapshot observed at ${snapshot.observed_at} (Git status observation only):`,
    `- worktree: ${snapshot.worktree}`,
    `- branch: ${snapshot.branch}`,
  ];
  if (snapshot.modified_files.length > 0) {
    parts.push('Modified files observed in that workspace:');
    parts.push(...snapshot.modified_files.map(file => `- ${file}`));
  }
  return parts.join('\n');
}

/**
 * Resolve worktree + branch from the event cwd. There is deliberately no
 * repository-root fallback: an unproven current worktree must not be joined to
 * a saved snapshot from somewhere else.
 */
export function resolveGitWorkspace(eventCwd) {
  try {
    if (typeof eventCwd !== 'string' || !eventCwd.trim() || !fs.statSync(eventCwd).isDirectory()) {
      return null;
    }
    const opts = {
      cwd: eventCwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
      timeout: 3000,
    };
    const branch = execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], opts).trim();
    const worktree = execFileSync('git', ['rev-parse', '--show-toplevel'], opts).trim();
    if (!branch || !worktree) return null;
    return { worktree, branch };
  } catch {
    return null;
  }
}

function normalizedWorktree(worktree) {
  let resolved = path.resolve(worktree);
  try { resolved = fs.realpathSync.native(resolved); } catch {}
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

export function stateMatchesCurrentWorkspace(state, sessionId, currentWorkspace) {
  const snapshot = state?.workspace_snapshot;
  return state?.session_id === sessionId
    && typeof snapshot?.observed_at === 'string' && snapshot.observed_at.length > 0
    && typeof snapshot?.worktree === 'string' && snapshot.worktree.length > 0
    && typeof snapshot?.branch === 'string' && snapshot.branch.length > 0
    && Array.isArray(snapshot?.modified_files)
    && snapshot.modified_files.every(file => typeof file === 'string')
    && !!currentWorkspace
    && normalizedWorktree(snapshot.worktree) === normalizedWorktree(currentWorkspace.worktree)
    && snapshot.branch === currentWorkspace.branch;
}

export function buildContext(state, currentWorkspace) {
  const parts = [
    `<!-- compaction-context session=${state.session_id} -->`,
    '# Compaction Recovery Context (one-shot)',
  ];
  parts.push('');
  parts.push('This context was captured immediately before compaction to help with orientation.');

  if (currentWorkspace) {
    parts.push(
      `\nCurrent worktree (verified against the saved snapshot):\n- dir: ${currentWorkspace.worktree}\n- branch: ${currentWorkspace.branch}`
    );
    parts.push(formatWorkspaceSnapshot(state.workspace_snapshot));
  } else {
    parts.push('\nWorkspace snapshot omitted because its session/worktree/branch provenance could not be verified.');
  }

  const reads = formatReadFiles(state.read_files || {});
  if (reads) parts.push(reads);

  const edits = formatEditedFiles(state.edited_files || {});
  if (edits) parts.push(edits);

  return parts.join('\n');
}

/**
 * Pure decision: given a hook payload, what should the hook do?
 *
 * @returns {{ action: 'cleanup' | 'restore' | 'noop', sessionId?: string }}
 */
export function decideAction(input) {
  if (input.hook_event_name !== 'SessionStart') return { action: 'noop' };

  if (input.source !== 'compact') return { action: 'cleanup' };

  if (!input.session_id) return { action: 'cleanup' };
  return { action: 'restore', sessionId: input.session_id };
}

export function handleSessionStart(input, options = {}) {
  const telemetryDir = options.telemetryDir ?? TELEMETRY_DIR;
  const fallbackRepoRoot = options.repoRoot ?? repoRoot;
  const writeOutput = options.writeOutput ?? (text => process.stdout.write(text));
  const decision = decideAction(input);
  if (decision.action === 'noop') return { action: 'noop' };

  const currentWorkspace = resolveGitWorkspace(input.cwd);
  // Older versions wrote the ignored rule beside the hook script, while a
  // current event may belong to another worktree. Clean both proven locations
  // so the cross-worktree incident cannot leave the old instruction behind.
  cleanupLegacyRulesFile(fallbackRepoRoot);
  if (currentWorkspace
      && normalizedWorktree(currentWorkspace.worktree) !== normalizedWorktree(fallbackRepoRoot)) {
    cleanupLegacyRulesFile(currentWorkspace.worktree);
  }
  if (decision.action === 'cleanup') return { action: 'cleanup' };

  const statePath = compactStatePath(decision.sessionId, telemetryDir);
  const rawState = consumeCompactState(statePath);
  if (rawState == null) return { action: 'missing' };

  let state;
  try { state = JSON.parse(rawState); } catch { return { action: 'discarded' }; }
  if (state?.session_id !== decision.sessionId) {
    return { action: 'discarded' };
  }

  const verifiedWorkspace = stateMatchesCurrentWorkspace(state, decision.sessionId, currentWorkspace)
    ? currentWorkspace
    : null;
  const context = buildContext(state, verifiedWorkspace);
  writeOutput(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'SessionStart',
      additionalContext: context,
    },
  }));
  return { action: 'restored', context };
}

async function main() {
  try {
    const input = await readJsonStdin();
    if (!input) return;
    handleSessionStart(input);
  } catch {
    // Never block session start
  }
}

runHook(import.meta.url, main);
