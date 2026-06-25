#!/usr/bin/env node

/**
 * Synchronous PreCompact hook — captures session state before compaction.
 *
 * Writes a structured JSON file to tmp/agent-telemetry/compact-state-{sessionId}.json
 * containing:
 *   - First 100 lines of MEMORY.md (auto-memory)
 *   - git diff --name-only (modified files)
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
import { execSync } from 'node:child_process';
import { atomicWriteFileSync, readStdin, runHook, repoRoot, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

// Auto-memory path — derived from repo path
const MEMORY_DIR = path.join(
  process.env.HOME || process.env.USERPROFILE || '',
  '.claude', 'projects',
  repoRoot.replace(/[:/\\]/g, '-').replace(/^-+/, ''),
  'memory'
);
const MEMORY_FILE = path.join(MEMORY_DIR, 'MEMORY.md');
// Cap the MEMORY.md excerpt carried into the post-compaction summary. 100 lines
// is enough to preserve the durable head of MEMORY.md (the most-recently-curated
// facts) without re-bloating the freshly-compacted context window.
const MEMORY_MAX_LINES = 100;

function compactStatePath(sessionId) {
  return path.join(TELEMETRY_DIR, `compact-state-${sessionId}.json`);
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

function getModifiedFiles() {
  try {
    // Capture both unstaged and staged changes
    const unstaged = execSync('git diff --name-only', {
      cwd: repoRoot,
      encoding: 'utf8',
      timeout: 5000,
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    const staged = execSync('git diff --cached --name-only', {
      cwd: repoRoot,
      encoding: 'utf8',
      timeout: 5000,
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    const all = new Set([
      ...unstaged.split('\n').filter(Boolean),
      ...staged.split('\n').filter(Boolean),
    ]);
    return [...all];
  } catch {
    return [];
  }
}

function loadJsonCache(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return {};
  }
}

async function main() {
  try {
    const input = await readStdin().then((raw) => JSON.parse(raw));
    const sessionId = input.session_id;
    if (!sessionId) return;

    const state = {
      ts: new Date().toISOString(),
      session_id: sessionId,
      trigger: input.trigger ?? 'unknown',
      memory_summary: readMemorySummary(),
      modified_files: getModifiedFiles(),
      read_files: loadJsonCache(path.join(TELEMETRY_DIR, `read-counts-${sessionId}.json`)),
      edited_files: loadJsonCache(path.join(TELEMETRY_DIR, `edit-counts-${sessionId}.json`)),
    };

    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    atomicWriteFileSync(compactStatePath(sessionId), JSON.stringify(state, null, 2));

    // Reset read counts so post-compaction re-reads don't trigger warnings.
    // The counts are preserved in compact-state above for analytics.
    const readCountsPath = path.join(TELEMETRY_DIR, `read-counts-${sessionId}.json`);
    try { atomicWriteFileSync(readCountsPath, '{}'); } catch { /* best-effort */ }

    // Reset repeat-buffer so pre-compaction fingerprints don't cause false consecutive-repeat
    // blocks on the first post-compaction tool calls (agent re-orienting after compact).
    const repeatBufferPath = path.join(TELEMETRY_DIR, `repeat-buffer-${sessionId}.json`);
    try { fs.unlinkSync(repeatBufferPath); } catch { /* best-effort */ }

    // Tempdoc 579 — re-arm the Consult/Maintain doc hooks after compaction. Both dedupe
    // once-per-region-per-session via a marker file; but compaction may evict the
    // governing-doc pointer they already delivered, so clearing the markers lets them
    // re-deliver on the next edit in a region (the same "don't penalize the re-orienting
    // agent" rationale as the read-count / repeat-buffer resets above).
    for (const marker of [`consult-nudged-${sessionId}.json`, `maintain-nudged-${sessionId}.json`]) {
      try { fs.unlinkSync(path.join(TELEMETRY_DIR, marker)); } catch { /* best-effort */ }
    }
  } catch {
    // Never block compaction
  }
}

runHook(import.meta.url, main);
