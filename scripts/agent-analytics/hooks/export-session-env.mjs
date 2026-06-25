#!/usr/bin/env node

/**
 * Synchronous SessionStart hook that persists JUSTSEARCH_AGENT_SESSION_ID
 * for later Bash/workflow commands to inherit the parent Claude session.
 *
 * Two persistence mechanisms:
 * 1. Appends an `export` line to CLAUDE_ENV_FILE (sourced by Claude Code on
 *    Linux/macOS; broken on Windows — see anthropics/claude-code#27987).
 * 2. Writes the raw session ID to tmp/agent-telemetry/current-session-id
 *    as a file-based fallback read by resolveWorkflowSessionId().
 *
 * - Always exits 0
 * - No stdout output
 * - Errors are logged to tmp/agent-telemetry/errors.log
 */

import fs from 'node:fs';
import path from 'node:path';
import { logErrorSync } from '../lib/event-writer.mjs';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..', '..');

function normalizeString(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\"'\"'`)}'`;
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function main() {
  try {
    const raw = await readStdin();
    if (!raw.trim()) return;
    const input = JSON.parse(raw);
    if (input.hook_event_name && input.hook_event_name !== 'SessionStart') {
      return;
    }

    const sessionId = normalizeString(input.session_id);
    if (!sessionId) {
      logErrorSync(repoRoot, 'export-session-env: missing session_id');
      return;
    }

    // --- file-based fallback (works on all platforms, including Windows) ---
    // Write raw session ID so resolveWorkflowSessionId() can read it even when
    // CLAUDE_ENV_FILE sourcing is broken (anthropics/claude-code#27987).
    const sessionIdFile = 'current-session-id';
    const writeTargets = new Set([
      path.join(repoRoot, 'tmp', 'agent-telemetry', sessionIdFile),
      path.join(process.cwd(), 'tmp', 'agent-telemetry', sessionIdFile),
    ]);
    for (const target of writeTargets) {
      try {
        fs.mkdirSync(path.dirname(target), { recursive: true });
        fs.writeFileSync(target, sessionId, 'utf8');
      } catch (e) {
        logErrorSync(repoRoot,
          `export-session-env: failed to write ${target}: ${e.message}`);
      }
    }

    // --- CLAUDE_ENV_FILE append (works on Linux/macOS, no-op on Windows) ---
    const envFile = normalizeString(process.env.CLAUDE_ENV_FILE);
    if (!envFile) return;

    let envFileStat = null;
    try {
      envFileStat = fs.statSync(envFile);
    } catch { /* does not exist yet — expected for fresh file */ }

    if (envFileStat && envFileStat.isDirectory()) {
      logErrorSync(repoRoot,
        `export-session-env: CLAUDE_ENV_FILE is a directory, not a file: ${envFile}`);
      return;
    }

    fs.appendFileSync(
      envFile,
      `export JUSTSEARCH_AGENT_SESSION_ID=${shellQuote(sessionId)}\n`,
      'utf8',
    );
  } catch (err) {
    logErrorSync(repoRoot, `export-session-env error: ${err.message}`);
  }
}

main().catch(() => process.exit(0));
