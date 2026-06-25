#!/usr/bin/env node

/**
 * Synchronous PreToolUse intervention hook (matcher: "Read", "Edit").
 *
 * Two behaviors:
 * 1. Auto-injects limit for Read calls on large files (>8KB) without offset/limit.
 * 2. Tracks per-session read and edit counts for compact-save.mjs (no warnings).
 *
 * - Synchronous (async: false) — blocks until it returns
 * - File I/O: reads/writes tiny per-session count caches (~1-2KB each)
 * - Outputs hookSpecificOutput with updatedInput when limit injection is active
 *
 * Note: permissionDecision: 'allow' auto-approves the Read call without user
 * confirmation when limit injection is active.
 */

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync, readStdin, runHook, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

// Dynamic file size threshold for auto-injecting limit.
// ~200 lines at ~40 bytes/line. Any file larger gets limit: 200.
// Note: analytics (dispatch.mjs) captures pre-intervention state, so the
// unbounded-read rate in session reports overcounts — reads that get limit
// injected here still appear as "unbounded" in the events stream.
const SIZE_THRESHOLD_BYTES = 8_000;
// Lines to read when auto-limiting a large file. Matches Claude Code's own
// large-file Read default — enough for orientation; re-read with offset for more.
const DEFAULT_LIMIT = 200;
// Unbounded re-reads of one file per session before the hook blocks and asks
// for offset/limit. 10 tolerates legitimate iterative work while catching the
// "keep re-reading the whole file" context-waste pattern; compaction resets it.
const HOT_FILE_CAP = 10;

function normalizePath(p) {
  return p.replace(/\\/g, '/');
}

/** Block an unbounded read once it has been re-read `cap` times this session. */
export function shouldBlockHotFile(unboundedCount, isUnbounded, cap = HOT_FILE_CAP) {
  return !!isUnbounded && unboundedCount >= cap;
}

export function shouldInjectLimit(toolInput) {
  if (!toolInput?.file_path) return null;
  if (toolInput.offset != null || toolInput.limit != null) return null;
  try {
    const stat = fs.statSync(toolInput.file_path);
    if (stat.size > SIZE_THRESHOLD_BYTES) {
      return {
        updatedInput: { ...toolInput, limit: DEFAULT_LIMIT },
        sizeBytes: stat.size,
      };
    }
  } catch {
    // File doesn't exist or can't be accessed — let Read handle the error
  }
  return null;
}

// --- Read-count tracking ---

function readCountFilePath(sessionId) {
  return path.join(TELEMETRY_DIR, `read-counts-${sessionId}.json`);
}

function loadReadCounts(sessionId) {
  try {
    const data = fs.readFileSync(readCountFilePath(sessionId), 'utf8');
    return JSON.parse(data);
  } catch {
    return {};
  }
}

function saveReadCounts(sessionId, counts) {
  try {
    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    atomicWriteFileSync(readCountFilePath(sessionId), JSON.stringify(counts));
  } catch {
    // Best-effort — don't block the hook
  }
}

// --- Edit-count tracking ---

function editCountFilePath(sessionId) {
  return path.join(TELEMETRY_DIR, `edit-counts-${sessionId}.json`);
}

function loadEditCounts(sessionId) {
  try {
    const data = fs.readFileSync(editCountFilePath(sessionId), 'utf8');
    return JSON.parse(data);
  } catch {
    return {};
  }
}

function saveEditCounts(sessionId, counts) {
  try {
    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    atomicWriteFileSync(editCountFilePath(sessionId), JSON.stringify(counts));
  } catch {
    // Best-effort — don't block the hook
  }
}

// --- Cache cleanup ---

const STALE_CACHE_MS = 24 * 60 * 60 * 1000; // 24 hours

function pruneStaleCountFiles() {
  try {
    const files = fs.readdirSync(TELEMETRY_DIR)
      .filter(f =>
        f.startsWith('read-counts-') || f.startsWith('edit-counts-') ||
        f.startsWith('repeat-buffer-') || f.startsWith('build-fails-') ||
        f.startsWith('turn-count-')
      );
    const now = Date.now();
    for (const file of files) {
      const filePath = path.join(TELEMETRY_DIR, file);
      const stat = fs.statSync(filePath);
      if (now - stat.mtimeMs > STALE_CACHE_MS) {
        fs.unlinkSync(filePath);
      }
    }
  } catch {
    // Best-effort — don't block the hook
  }
}

function trackRead(sessionId, filePath, isUnbounded) {
  if (!sessionId || !filePath) return { total: 0, unbounded: 0 };
  const counts = loadReadCounts(sessionId);
  const norm = normalizePath(filePath);
  const isFirst = Object.keys(counts).length === 0;

  // Backward compat: old format stored just a number per file
  if (typeof counts[norm] === 'number') {
    counts[norm] = { total: counts[norm], unbounded: counts[norm] };
  }
  if (!counts[norm]) counts[norm] = { total: 0, unbounded: 0 };

  counts[norm].total += 1;
  if (isUnbounded) counts[norm].unbounded += 1;
  saveReadCounts(sessionId, counts);

  // Prune stale cache files on first read of a new session
  if (isFirst) pruneStaleCountFiles();

  return counts[norm];
}

function trackEdit(sessionId, filePath) {
  if (!sessionId || !filePath) return 0;
  const counts = loadEditCounts(sessionId);
  const norm = normalizePath(filePath);
  if (!counts[norm]) counts[norm] = [];
  counts[norm].push(Date.now());
  saveEditCounts(sessionId, counts);
  return counts[norm].length;
}

// --- Main ---

async function main() {
  const raw = await readStdin();

  try {
    const input = JSON.parse(raw);
    const toolInput = input.tool_input;
    const sessionId = input.session_id;

    // --- Edit handling (track only, no warning) ---
    if (input.tool_name === 'Edit') {
      trackEdit(sessionId, toolInput?.file_path);
      return;
    }

    // --- Read handling ---
    if (input.tool_name !== 'Read') return;

    // Track read count (for compact-save.mjs and hot-file cap)
    const isUnbounded = toolInput?.offset == null && toolInput?.limit == null;
    const readCounts = trackRead(sessionId, toolInput?.file_path, isUnbounded);

    // Hot-file cap: block unbounded reads after threshold (unbounded count only).
    // Targeted reads (with offset/limit) don't count toward the cap and always pass.
    // Compaction resets read counts (compact-save.mjs:114), giving a fresh budget.
    if (shouldBlockHotFile(readCounts.unbounded, isUnbounded)) {
      const shortPath = (toolInput.file_path || '').split(/[/\\]/).slice(-2).join('/');
      let totalLines = null;
      try {
        const content = fs.readFileSync(toolInput.file_path, 'utf8');
        totalLines = content.split('\n').length;
      } catch { /* best-effort */ }
      const sizeHint = totalLines != null ? ` (${totalLines} total lines)` : '';
      process.stderr.write(
        `This file (${shortPath}) has had ${readCounts.unbounded} unbounded reads this session${sizeHint}. ` +
        `Use offset and limit to read only the section you need.`
      );
      process.exit(2);
    }

    // Check if we need to inject a limit for large files
    const injection = shouldInjectLimit(toolInput);

    // Only emit output if we're injecting a limit
    if (injection) {
      const shortPath = (toolInput.file_path || '').split(/[/\\]/).slice(-2).join('/');
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: {
          hookEventName: 'PreToolUse',
          permissionDecision: 'allow',
          updatedInput: injection.updatedInput,
          additionalContext:
            `Note: Read on ${shortPath} (${injection.sizeBytes} bytes) was auto-limited to ${DEFAULT_LIMIT} lines. ` +
            `Re-read with offset to access lines beyond ${DEFAULT_LIMIT} if needed.`,
        },
      }));
    }
    // No output = no modification
  } catch {
    // Parse failure — no modification
  }
}

runHook(import.meta.url, main);
