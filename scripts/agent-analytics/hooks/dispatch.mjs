#!/usr/bin/env node

/**
 * Single dispatcher for all Claude Code hook events.
 * Reads JSON from stdin, branches on hook_event_name, appends one NDJSON line.
 *
 * - Always exits 0 (never blocks Claude).
 * - No stdout output (avoid polluting agent context).
 * - Errors go to tmp/agent-telemetry/errors.log.
 */

import fs from 'node:fs';
import path from 'node:path';
import { appendEventSync, logErrorSync } from '../lib/event-writer.mjs';
import { summarizeInput, summarizeResponse } from '../lib/input-summarizer.mjs';
import { hooksDisabled, dispatchSkeletonOnly, stampSessionActivity, clearSessionActivity } from '../lib/hook-base.mjs';

// Tempdoc 622 Phase 5: in skeleton-only mode, dispatch emits the irreducible
// lifecycle/skeleton (unique carriers + blocking-hook needs) and skips the rich
// tool-summarizing that native OTel logs supersede. Default keeps full capture.
const SKELETON = dispatchSkeletonOnly();

// Resolve repo root: walk up from this script's location to find the repo.
// Script is at: <repo>/scripts/agent-analytics/hooks/dispatch.mjs
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
// On Windows, URL pathname starts with /C:/... — strip the leading slash.
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..', '..');

// In-memory per-session tool-call counter (persists across hook invocations within same process).
// dispatch.mjs is spawned per-event, so we use a file-based counter for cross-invocation tracking.
const COUNTER_DIR = path.join(repoRoot, 'tmp', 'agent-telemetry');

function readToolCount(sessionId) {
  try {
    const file = path.join(COUNTER_DIR, `turn-count-${sessionId}.txt`);
    return parseInt(fs.readFileSync(file, 'utf8').trim(), 10) || 0;
  } catch { return 0; }
}

function writeToolCount(sessionId, count) {
  try {
    const file = path.join(COUNTER_DIR, `turn-count-${sessionId}.txt`);
    fs.writeFileSync(file, String(count), 'utf8');
  } catch { /* best-effort */ }
}

function nowIso() {
  return new Date().toISOString();
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function buildEvent(eventType, sessionId, extra) {
  return {
    ...extra,
    schema_version: 1,
    ts: nowIso(),
    event: eventType,
    session_id: sessionId,
  };
}

function handleSessionStart(input) {
  return buildEvent('session_start', input.session_id, {
    source: input.source,
    model: input.model ?? null,
    agent_type: input.agent_type ?? null,
    transcript_path: input.transcript_path ?? null,
    cwd: input.cwd ?? null,
  });
}

function handleSessionEnd(input) {
  const finalCount = readToolCount(input.session_id);
  // Clean up per-session state files
  const filesToClean = [
    `turn-count-${input.session_id}.txt`,
    `repeat-buffer-${input.session_id}.json`,
    `build-fails-${input.session_id}.json`,
  ];
  for (const f of filesToClean) {
    try { fs.unlinkSync(path.join(COUNTER_DIR, f)); } catch { /* may not exist */ }
  }
  // Tempdoc 606 1a: clear the shared dev-stack activity stamp on clean exit
  // (instant abandonment signal; staleness remains the authority if SessionEnd
  // is dropped — it is documented as best-effort).
  clearSessionActivity(input.session_id);
  return buildEvent('session_end', input.session_id, {
    reason: input.reason,
    total_tool_calls: finalCount || null,
  });
}

function handlePreToolUse(input) {
  const count = readToolCount(input.session_id) + 1;
  writeToolCount(input.session_id, count);
  // Tempdoc 606 1a: general-activity heartbeat under the SHARED dev-stack state
  // root (every tool call → this session is alive and doing something). The
  // dev-stack ownership verdict joins against this to tell abandoned from active.
  stampSessionActivity(input.session_id, { lastActivityAt: nowIso() });
  return buildEvent('pre_tool_use', input.session_id, {
    tool_name: input.tool_name,
    tool_use_id: input.tool_use_id,
    tool_call_number: count,
    // Rich field (OTel `tool_decision`+`tool_result` supersede in skeleton mode).
    ...(SKELETON ? {} : { input_summary: summarizeInput(input.tool_name, input.tool_input) }),
  });
}

function handlePostToolUse(input) {
  // Build-failure counting moved to build-counter.mjs's synchronous PostToolUse
  // path (tempdoc 520 P0f). dispatch.mjs is async, so writing the counter here
  // raced the next synchronous PreToolUse read — the advisory could fire one
  // call late. build-counter now owns the count synchronously; dispatch only
  // emits telemetry.
  return buildEvent('post_tool_use', input.session_id, {
    tool_name: input.tool_name,
    tool_use_id: input.tool_use_id,
    duration_ms: input.duration_ms ?? null,
    // Rich fields (OTel `tool_result` log supersedes in skeleton mode).
    ...(SKELETON ? {} : {
      input_summary: summarizeInput(input.tool_name, input.tool_input),
      response_summary: summarizeResponse(input.tool_name, input.tool_response),
    }),
  });
}

function handlePostToolUseFailure(input) {
  const errorStr = typeof input.error === 'string' ? input.error.substring(0, 200) : null;
  return buildEvent('post_tool_use_failure', input.session_id, {
    tool_name: input.tool_name,
    tool_use_id: input.tool_use_id,
    is_interrupt: input.is_interrupt ?? false,
    // Rich fields (OTel `tool_result` success=false carries error in skeleton mode).
    ...(SKELETON ? {} : {
      input_summary: summarizeInput(input.tool_name, input.tool_input),
      error_summary: errorStr,
    }),
  });
}

function handlePreCompact(input) {
  return buildEvent('pre_compact', input.session_id, {
    trigger: input.trigger,
  });
}

function handleSubagentStart(input) {
  return buildEvent('subagent_start', input.session_id, {
    agent_id: input.agent_id,
    agent_type: input.agent_type,
  });
}

function handleSubagentStop(input) {
  return buildEvent('subagent_stop', input.session_id, {
    agent_id: input.agent_id,
    agent_type: input.agent_type,
    agent_transcript_path: input.agent_transcript_path ?? null,
  });
}

function handleUserPromptSubmit(input) {
  const prompt = typeof input.user_prompt === 'string' ? input.user_prompt : null;
  return buildEvent('user_prompt_submit', input.session_id, {
    // Keep the lifecycle event (turn counting) + length (a number, not content);
    // the prompt-text excerpt is content OTel's `user_prompt` log supersedes.
    prompt_length: prompt?.length ?? null,
    ...(SKELETON ? {} : { prompt_excerpt: prompt ? prompt.substring(0, 500) : null }),
  });
}

function handleStop(input) {
  return buildEvent('stop', input.session_id, {
    stop_hook_active: input.stop_hook_active ?? null,
  });
}

function handleInstructionsLoaded(input) {
  // Fires when CLAUDE.md / .claude/rules/*.md files are loaded into context.
  // Per https://code.claude.com/docs/en/hooks: useful for audit logging and
  // detecting drift mid-session. Field shape is platform-defined; we record
  // whatever input arrives so events.ndjson preserves the raw signal.
  // Skeleton mode: low-value audit signal OTel's hook_* events cover — skip.
  if (SKELETON) return null;
  return buildEvent('instructions_loaded', input.session_id, {
    files: input.files ?? null,
    trigger: input.trigger ?? null,
    source: input.source ?? null,
  });
}

const handlers = {
  SessionStart: handleSessionStart,
  SessionEnd: handleSessionEnd,
  PreToolUse: handlePreToolUse,
  PostToolUse: handlePostToolUse,
  PostToolUseFailure: handlePostToolUseFailure,
  PreCompact: handlePreCompact,
  SubagentStart: handleSubagentStart,
  SubagentStop: handleSubagentStop,
  UserPromptSubmit: handleUserPromptSubmit,
  Stop: handleStop,
  InstructionsLoaded: handleInstructionsLoaded,
};

async function main() {
  try {
    const raw = await readStdin();
    if (!raw.trim()) return;

    const input = JSON.parse(raw);
    const eventName = input.hook_event_name;

    if (!eventName || !handlers[eventName]) {
      logErrorSync(repoRoot, `Unknown hook_event_name: ${eventName}`);
      return;
    }

    if (typeof input.session_id !== 'string' || !input.session_id) {
      logErrorSync(repoRoot, `Missing or invalid session_id for ${eventName}`);
      return;
    }

    const handler = handlers[eventName];
    let event;
    try {
      event = handler(input);
    } catch (err) {
      logErrorSync(repoRoot, `Handler error for ${eventName}: ${err.message}`);
      return;
    }

    if (!event) return; // handler chose to emit nothing (e.g. skeleton-mode skip)
    appendEventSync(repoRoot, event);
  } catch (err) {
    // Top-level catch: log and exit cleanly.
    try {
      logErrorSync(repoRoot, `dispatch error: ${err.message}`);
    } catch {
      // Nothing left to do.
    }
  }
}

// Kill switch (tempdoc 520 P1c): JUSTSEARCH_DISABLE_HOOKS=1 disables telemetry too.
if (!hooksDisabled()) main().catch(() => process.exit(0));
