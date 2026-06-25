#!/usr/bin/env node

/**
 * Synchronous PreToolUse intervention hook (matcher: all tools).
 *
 * Blocks 3+ consecutive identical tool calls to prevent overthinking loops
 * (arXiv:2602.14798). Maintains a fingerprint buffer per session.
 *
 * - Synchronous (async: false) — blocks until it returns
 * - File I/O: reads/writes per-session buffer (~1KB)
 * - Exit 2 + stderr = block with advisory message
 * - No stdout output = allow
 * - Build commands (/gradlew/i) are excluded — deferred to build-counter.mjs
 */

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync, readStdin, runHook, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

const CONSECUTIVE_THRESHOLD = 3; // 3 consecutive identical calls = an overthinking loop (arXiv:2602.14798); small enough to catch loops fast, large enough to allow legitimate retries.
const BUFFER_SIZE = CONSECUTIVE_THRESHOLD; // Only consecutive detection is implemented

// --- Fingerprint ---

export function fingerprint(toolName, toolInput) {
  if (!toolInput || typeof toolInput !== 'object') return toolName;

  switch (toolName) {
    case 'Read':
      return `Read|${toolInput.file_path}|${toolInput.offset ?? ''}|${toolInput.limit ?? ''}`;
    case 'Edit':
      return `Edit|${toolInput.file_path}|${(toolInput.old_string || '').substring(0, 100)}`;
    case 'Write':
      return `Write|${toolInput.file_path}`;
    case 'Bash':
      return `Bash|${(toolInput.command || '').substring(0, 200)}`;
    case 'Grep':
      return `Grep|${toolInput.pattern}|${toolInput.path ?? ''}`;
    case 'Glob':
      return `Glob|${toolInput.pattern}|${toolInput.path ?? ''}`;
    case 'Task':
      return `Task|${toolInput.subagent_type}|${(toolInput.prompt || '').substring(0, 100)}`;
    case 'WebSearch':
      return `WebSearch|${toolInput.query}`;
    case 'WebFetch':
      return `WebFetch|${toolInput.url}`;
    case 'NotebookEdit':
      return `NotebookEdit|${toolInput.notebook_path ?? ''}|${toolInput.cell_id ?? ''}`;
    case 'TaskCreate':
      return `TaskCreate|${toolInput.subject ?? ''}`;
    case 'TaskUpdate':
      return `TaskUpdate|${toolInput.taskId ?? ''}|${toolInput.status ?? ''}`;
    case 'TaskGet':
    case 'TaskList':
    case 'TaskOutput':
      return `${toolName}|${toolInput.taskId ?? toolInput.task_id ?? ''}`;
    case 'AskUserQuestion':
      return `AskUserQuestion|${(JSON.stringify(toolInput.questions) || '').substring(0, 150)}`;
    case 'EnterPlanMode':
    case 'ExitPlanMode':
    case 'Skill':
    case 'EnterWorktree':
      return toolName; // singletons — consecutive blocking is correct
    default:
      // MCP tools and unknown tools: include truncated input to avoid false positives
      return `${toolName}|${JSON.stringify(toolInput).substring(0, 200)}`;
  }
}

// --- Buffer state ---

function bufferFilePath(sessionId) {
  return path.join(TELEMETRY_DIR, `repeat-buffer-${sessionId}.json`);
}

function loadBuffer(sessionId) {
  try {
    const data = fs.readFileSync(bufferFilePath(sessionId), 'utf8');
    const parsed = JSON.parse(data);
    return Array.isArray(parsed.buffer) ? parsed.buffer : [];
  } catch {
    return [];
  }
}

// atomicWriteFileSync (tempdoc 520 P0e) is shared from hook-base.mjs (P1a).

function saveBuffer(sessionId, buffer) {
  try {
    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    atomicWriteFileSync(bufferFilePath(sessionId), JSON.stringify({ buffer }));
  } catch {
    // Best-effort — don't block the hook
  }
}

// --- Main ---

async function main() {
  // Known limitation: parallel tool calls race on the buffer file.
  // Each hook process reads/writes independently — last writer wins.
  // Practical impact is low: parallel calls are typically different tools.
  // The guard is best-effort for parallel identical calls.
  const raw = await readStdin();

  try {
    const input = JSON.parse(raw);
    const sessionId = input.session_id;
    const toolName = input.tool_name;
    const toolInput = input.tool_input;

    if (!sessionId || !toolName) return;

    // Defer to build-counter.mjs for build commands — it has purpose-built
    // one-shot advisory logic. Without this exclusion, repeat-guard blocks
    // the 3rd consecutive build before build-counter reaches its failure threshold.
    if (toolName === 'Bash' && /gradlew/i.test((toolInput?.command || ''))) return;

    const fp = fingerprint(toolName, toolInput);
    const buffer = loadBuffer(sessionId);

    // Append and trim to rolling window
    buffer.push(fp);
    if (buffer.length > BUFFER_SIZE) {
      buffer.splice(0, buffer.length - BUFFER_SIZE);
    }

    // Save before checking — the current call is part of the history
    saveBuffer(sessionId, buffer);

    // Check consecutive identical calls
    if (buffer.length >= CONSECUTIVE_THRESHOLD) {
      const last = buffer.slice(-CONSECUTIVE_THRESHOLD);
      if (last.every(f => f === last[0])) {
        // Extract a short detail for the advisory message
        const detail = fp.length > 60 ? fp.substring(0, 57) + '...' : fp;
        process.stderr.write(
          `Blocked: ${CONSECUTIVE_THRESHOLD} consecutive identical ${toolName} calls (${detail}). ` +
          `Try a different approach.`
        );
        process.exit(2);
      }
    }

    // No output = allow
  } catch {
    // Parse failure — allow (don't block on hook errors)
  }
}

runHook(import.meta.url, main);
