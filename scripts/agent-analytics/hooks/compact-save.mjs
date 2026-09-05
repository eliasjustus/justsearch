#!/usr/bin/env node

/**
 * Synchronous PreCompact hook — captures session state before compaction.
 *
 * Writes a structured JSON file to tmp/agent-telemetry/compact-state-{sessionId}.json
 * containing:
 *   - First 100 lines of MEMORY.md (auto-memory)
 *   - A provenance-bearing Git workspace snapshot from the hook event cwd
 *   - Read-counts cache (files read this session)
 *   - Edit-counts cache (files edited this session)
 *
 * The companion compact-restore.mjs hook reads this file on SessionStart
 * after compaction and injects it as additionalContext.
 *
 * - Synchronous (async: false) — must complete before compaction
 * - Timeout: 10s (git diff can be slow)
 * - Always exits 0 — never blocks compaction
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { atomicWriteFileSync, readStdin, runHook, repoRoot, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';
import { DEFAULT_PROJECTS_ROOT } from '../lib/transcript-store.mjs';

// Auto-memory path — derived from repo path. The projects root comes from
// lib/transcript-store.mjs's DEFAULT_PROJECTS_ROOT (886 §12 PR 5b — this was
// the last hand-rolled `'.claude', 'projects'` join outside that module); the
// per-repo slug computation below is NOT a transcript-store concern (it
// locates a `memory/MEMORY.md` artifact, not a session transcript), so it
// stays local rather than pretending discoverProjectDirs' fuzzy multi-dir
// scan is the same operation as "the one dir for THIS repoRoot".
const MEMORY_DIR = path.join(
  DEFAULT_PROJECTS_ROOT,
  repoRoot.replace(/[:/\\]/g, '-').replace(/^-+/, ''),
  'memory'
);
const MEMORY_FILE = path.join(MEMORY_DIR, 'MEMORY.md');
// Cap the MEMORY.md excerpt carried into the post-compaction summary. 100 lines
// is enough to preserve the durable head of MEMORY.md (the most-recently-curated
// facts) without re-bloating the freshly-compacted context window.
const MEMORY_MAX_LINES = 100;

function compactStatePath(sessionId, telemetryDir = TELEMETRY_DIR) {
  return path.join(telemetryDir, `compact-state-${sessionId}.json`);
}

function readMemorySummary() {
  try {
    const content = fs.readFileSync(MEMORY_FILE, 'utf8');
    const lines = content.split('\n').slice(0, MEMORY_MAX_LINES);
    return lines.join('\n');
  } catch {
    return '';
  }
}

function runGit(args, cwd) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    timeout: 5000,
    stdio: ['ignore', 'pipe', 'ignore'],
  }).trim();
}

/**
 * Capture an observed Git workspace, never an attribution of changes to the
 * current session. The event cwd is the authority: falling back to the hook's
 * repository root would make another worktree's diff look relevant.
 */
export function captureWorkspaceSnapshot(eventCwd, observedAt = new Date().toISOString()) {
  try {
    if (typeof eventCwd !== 'string' || !eventCwd.trim() || !fs.statSync(eventCwd).isDirectory()) {
      return null;
    }

    const worktree = runGit(['rev-parse', '--show-toplevel'], eventCwd);
    const branch = runGit(['rev-parse', '--abbrev-ref', 'HEAD'], eventCwd);
    if (!worktree || !branch) return null;

    const unstaged = runGit(['diff', '--name-only'], eventCwd);
    const staged = runGit(['diff', '--cached', '--name-only'], eventCwd);
    const untracked = runGit(['ls-files', '--others', '--exclude-standard'], eventCwd);
    const modifiedFiles = [...new Set([
      ...unstaged.split('\n').filter(Boolean),
      ...staged.split('\n').filter(Boolean),
      ...untracked.split('\n').filter(Boolean),
    ])];

    return {
      observed_at: observedAt,
      worktree,
      branch,
      modified_files: modifiedFiles,
    };
  } catch {
    return null;
  }
}

function loadJsonCache(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return {};
  }
}

export function saveCompactState(input, options = {}) {
  const sessionId = input?.session_id;
  if (!sessionId) return { action: 'noop' };

  const telemetryDir = options.telemetryDir ?? TELEMETRY_DIR;
  const observedAt = options.observedAt ?? new Date().toISOString();
  const workspaceSnapshot = captureWorkspaceSnapshot(input.cwd, observedAt);
  const state = {
    ts: observedAt,
    session_id: sessionId,
    trigger: input.trigger ?? 'unknown',
    memory_summary: options.memorySummary ?? readMemorySummary(),
    read_files: loadJsonCache(path.join(telemetryDir, `read-counts-${sessionId}.json`)),
    edited_files: loadJsonCache(path.join(telemetryDir, `edit-counts-${sessionId}.json`)),
  };
  if (workspaceSnapshot) state.workspace_snapshot = workspaceSnapshot;

  fs.mkdirSync(telemetryDir, { recursive: true });
  const statePath = compactStatePath(sessionId, telemetryDir);
  atomicWriteFileSync(statePath, JSON.stringify(state, null, 2));

  // Reset read counts so the next orientation window starts fresh. The counts
  // remain in the consumed compact state for immediate orientation.
  const readCountsPath = path.join(telemetryDir, `read-counts-${sessionId}.json`);
  try { atomicWriteFileSync(readCountsPath, '{}'); } catch { /* best-effort */ }

  // Reset repeat-buffer so pre-compaction fingerprints do not block the first
  // post-compaction orientation calls.
  const repeatBufferPath = path.join(telemetryDir, `repeat-buffer-${sessionId}.json`);
  try { fs.unlinkSync(repeatBufferPath); } catch { /* best-effort */ }

  return { action: 'saved', state, statePath };
}

async function main() {
  try {
    const input = await readStdin().then((raw) => JSON.parse(raw));
    saveCompactState(input);
  } catch {
    // Never block compaction
  }
}

runHook(import.meta.url, main);
