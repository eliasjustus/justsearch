#!/usr/bin/env node

/**
 * Synchronous PreToolUse hook (matcher: "TaskCreate").
 *
 * Confirmed from 4 real historical failures in this repo's own session
 * transcripts (docs/tempdocs/727-session-transcript-friction-mining.md,
 * finding F-7b): agents repeatedly call TaskCreate with a malformed "batch"
 * shape — a single call carrying a `tasks` key whose value is a
 * JSON-STRINGIFIED array (e.g. `{"tasks":"[{\"description\":\"...\"}]"}`),
 * instead of the correct shape: one TaskCreate call per task, each with
 * `subject`/`description` fields. TaskCreate never legitimately accepts a
 * `tasks` key, so its mere presence (any type) is blocked before dispatch —
 * there is no legitimate call shape this would false-positive on.
 *
 * Exit codes:
 *   0 = allow (no output)
 *   2 = block (stderr message shown to Claude as feedback)
 */

import { readJsonStdin, runHook } from '../lib/hook-base.mjs';

const MESSAGE =
  'TaskCreate was called with a `tasks` key — this is the known malformed ' +
  '"batch" shape (a JSON-stringified array of task objects) and is blocked ' +
  'before dispatch. TaskCreate does not accept a `tasks` key. Call TaskCreate ' +
  'once per task instead, each with `subject` and `description` fields, e.g. ' +
  '{"subject": "...", "description": "..."}.';

// --- Decision logic (pure; unit-tested via taskcreate-guard.test.mjs) ---

/**
 * Decide whether a TaskCreate tool_input should be blocked.
 *
 * @param {object} toolInput  the tool_input payload for a TaskCreate call
 * @returns {{ block: boolean, message?: string }}
 */
export function evaluateTaskCreateInput(toolInput) {
  if (toolInput && Object.prototype.hasOwnProperty.call(toolInput, 'tasks')) {
    return { block: true, message: MESSAGE };
  }
  return { block: false };
}

// --- Main (thin I/O wrapper) ---

async function main() {
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'TaskCreate') return;

  const verdict = evaluateTaskCreateInput(input.tool_input);
  if (verdict.block) {
    process.stderr.write(verdict.message);
    process.exit(2);
  }
}

runHook(import.meta.url, main);
