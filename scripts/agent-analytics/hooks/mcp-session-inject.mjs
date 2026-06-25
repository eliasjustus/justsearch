#!/usr/bin/env node
/**
 * PreToolUse hook: injects Claude Code session_id into all justsearch-dev MCP tool calls.
 *
 * Matcher: mcp__justsearch-dev__.*
 *
 * The hook reads session_id from the PreToolUse stdin JSON and injects it as
 * `sessionId` into the tool's input via `updatedInput`. This ensures the MCP
 * server receives the real per-session identity, closing the same-CWD gap where
 * multiple agents sharing a working directory would otherwise resolve to the
 * same file-based session ID.
 *
 * If session_id is missing or empty, the hook outputs nothing (no modification).
 *
 * Tempdoc 606 1a: this hook fires on exactly the justsearch-dev tool calls, so it
 * is also the natural place to stamp `lastDevStackTouchAt` — the "owner is using
 * the stack" signal that separates IDLE_HOLD from CONTENTION in the ownership
 * verdict. Best-effort; never affects the injection output.
 */
import { stdin } from 'node:process';
import { stampSessionActivity } from '../lib/hook-base.mjs';

async function readStdin() {
  const chunks = [];
  for await (const chunk of stdin) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

const raw = await readStdin();
const input = JSON.parse(raw);

const sessionId = input.session_id;
if (sessionId) {
  const nowIso = new Date().toISOString();
  // Record both stamps: a dev-stack touch is also general activity.
  stampSessionActivity(sessionId, { lastActivityAt: nowIso, lastDevStackTouchAt: nowIso });
  const output = {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'allow',
      updatedInput: {
        ...input.tool_input,
        sessionId,
      },
    },
  };
  process.stdout.write(JSON.stringify(output) + '\n');
}
