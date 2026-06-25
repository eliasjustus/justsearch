#!/usr/bin/env node

/**
 * Synchronous SessionStart hook — restores state after compaction.
 *
 * On SessionStart with source === 'compact':
 *   1. Reads tmp/agent-telemetry/compact-state-{sessionId}.json (written by compact-save.mjs)
 *   2. Writes a temporary .claude/rules/compaction-state.md file
 *      (rules files are loaded into the system prompt and persist across turns)
 *   3. Also outputs hookSpecificOutput with additionalContext as a fallback
 *   4. Deletes the compact-state file (one-time use)
 *
 * On SessionStart with source !== 'compact':
 *   1. Deletes .claude/rules/compaction-state.md if it exists (stale from previous session)
 *   2. Exits silently
 *
 * Why rules file over additionalContext alone:
 *   - additionalContext is injected as a system-reminder (conversation message)
 *   - It gets summarized away on next compaction and may not even inject reliably (#15174)
 *   - .claude/rules/ files are loaded into the system prompt and survive across turns
 *
 * - Synchronous (async: false) — injects context before agent's first turn
 * - Timeout: 5s
 * - Always exits 0 — never blocks session start
 */

import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { readJsonStdin, runHook, repoRoot, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

const RULES_FILE = path.join(repoRoot, '.claude', 'rules', 'compaction-state.md');

function compactStatePath(sessionId) {
  return path.join(TELEMETRY_DIR, `compact-state-${sessionId}.json`);
}

function cleanupRulesFile() {
  try { fs.unlinkSync(RULES_FILE); } catch {}
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

function formatModifiedFiles(modifiedFiles) {
  if (!modifiedFiles || modifiedFiles.length === 0) return '';
  const lines = modifiedFiles.map(f => `- ${f}`);
  return `\nFiles modified in this session (git diff):\n${lines.join('\n')}`;
}

/**
 * Worktree + branch, so the agent SEES its location after compaction instead of
 * being asked to run `pwd`/`git branch` (mechanizes the `after-compaction-verify`
 * rule — tempdoc 620 Part V). Fail-open: omitted if git/cwd isn't resolvable.
 */
function formatWorktree() {
  try {
    const opts = { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 3000 };
    const branch = execSync('git rev-parse --abbrev-ref HEAD', opts).trim();
    const top = execSync('git rev-parse --show-toplevel', opts).trim();
    if (!branch || !top) return '';
    return `\nCurrent worktree (verify this matches the work you expect — after-compaction-verify):\n- dir: ${top}\n- branch: ${branch}`;
  } catch {
    return '';
  }
}

/** Read the `session=<id>` stamp from a rules-file body, or null if absent. */
export function parseSessionStamp(content) {
  const m = /<!--\s*compaction-state\s+session=(\S+)/.exec(content || '');
  return m ? m[1] : null;
}

export function buildContext(state, sessionId) {
  const parts = [
    `<!-- compaction-state session=${sessionId ?? 'unknown'} generated=${new Date().toISOString()} -->`,
    '# Compaction State (auto-generated; deleted automatically at session end — safe to ignore if it does not match your current work)',
  ];
  parts.push('');
  parts.push('This file was written by compact-restore.mjs after a context compaction.');
  parts.push('It contains session state from before compaction to help with orientation.');

  const worktree = formatWorktree();
  if (worktree) parts.push(worktree);

  const modified = formatModifiedFiles(state.modified_files);
  if (modified) parts.push(modified);

  const reads = formatReadFiles(state.read_files || {});
  if (reads) parts.push(reads);

  const edits = formatEditedFiles(state.edited_files || {});
  if (edits) parts.push(edits);

  return parts.join('\n');
}

/**
 * Pure decision: given a hook payload, what should the hook do?
 *
 * Closes the ghost-file window (tempdoc 520 P0d): Claude Code loads
 * `.claude/rules/*.md` into the system prompt *before* SessionStart hooks
 * fire, so deleting the stale file at the next SessionStart is one session
 * too late. The fix is to delete it at SessionEnd of the session that wrote
 * it — then it never survives into the next session. SessionStart cleanup
 * remains as a crash fallback (if SessionEnd never fired), with one residual
 * stale session only in that crash case.
 *
 * @returns {{ action: 'cleanup' | 'restore' | 'noop', sessionId?: string }}
 */
export function decideAction(input) {
  // SessionEnd of the writing session: delete the rules file so it never
  // bleeds into the next session's pre-hook rules load.
  if (input.hook_event_name === 'SessionEnd') return { action: 'cleanup' };

  // SessionStart, non-compact: stale file from a prior session — crash fallback.
  if (input.source !== 'compact') return { action: 'cleanup' };

  if (!input.session_id) return { action: 'noop' };
  return { action: 'restore', sessionId: input.session_id };
}

async function main() {
  try {
    const input = await readJsonStdin();
    if (!input) return;
    const decision = decideAction(input);

    if (decision.action === 'cleanup') {
      cleanupRulesFile();
      return;
    }
    if (decision.action === 'noop') return;

    // decision.action === 'restore'
    const sessionId = decision.sessionId;
    const statePath = compactStatePath(sessionId);

    let state;
    try {
      state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
    } catch {
      // No saved state — this is fine (first compaction, or file was cleaned up)
      return;
    }

    const context = buildContext(state, sessionId);

    // Primary: write stamped rules file (persists in system prompt across turns).
    try {
      fs.mkdirSync(path.dirname(RULES_FILE), { recursive: true });
      fs.writeFileSync(RULES_FILE, context, 'utf8');
    } catch {
      // If rules file write fails, fall back to additionalContext only
    }

    // Fallback: also emit additionalContext (one-shot, may not persist, but immediate)
    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'SessionStart',
        additionalContext: context,
      },
    }));

    // Clean up compact-state — one-time use
    try { fs.unlinkSync(statePath); } catch {}
  } catch {
    // Never block session start
  }
}

runHook(import.meta.url, main);
