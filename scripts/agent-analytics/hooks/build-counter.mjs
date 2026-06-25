#!/usr/bin/env node

/**
 * Synchronous PreToolUse intervention hook (matcher: Bash).
 *
 * Blocks build commands after 3+ consecutive build failures to prevent
 * build-fail thrashing. State tracked by dispatch.mjs (async PostToolUse)
 * in build-fails-{sessionId}.json.
 *
 * One-shot advisory pattern: blocks once, sets advisoryShown = true.
 * dispatch.mjs resets advisoryShown on next build result, so the advisory
 * re-triggers if failures continue.
 *
 * This produces 50% throughput for persistent thrashing — intentional, not a bug.
 * Escalation to permanent blocking creates a deadlock: the agent can never run
 * a successful build to reset the counter, since builds are blocked. The one-shot
 * pattern delivers the advisory message on every other attempt while preserving
 * the agent's ability to verify fixes.
 *
 * - Synchronous (async: false) — blocks until it returns
 * - File I/O: reads/writes per-session state (~50 bytes)
 * - Exit 2 + stderr = block with advisory message
 * - No stdout output = allow
 */

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync, readStdin, runHook, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

// Block after 3 consecutive build failures: enough to confirm a persistent
// failure (not a one-off flake) while curbing thrashing early. Paired with the
// one-shot advisory (advisoryShown) so the agent can always retry to verify a fix.
const CONSECUTIVE_FAIL_THRESHOLD = 3;
const BUILD_COMMAND_RE = /gradlew/i;

// --- State ---

function stateFilePath(sessionId) {
  return path.join(TELEMETRY_DIR, `build-fails-${sessionId}.json`);
}

function loadState(sessionId) {
  try {
    return JSON.parse(fs.readFileSync(stateFilePath(sessionId), 'utf8'));
  } catch {
    return { consecutiveFailures: 0, advisoryShown: false };
  }
}

function saveState(sessionId, state) {
  try {
    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    // Atomic write (hook-base) so the synchronous PostToolUse count is never
    // read torn by the next synchronous PreToolUse check (tempdoc 520 P0f).
    atomicWriteFileSync(stateFilePath(sessionId), JSON.stringify(state));
  } catch {
    // Best-effort — don't block the hook
  }
}

// --- Decision logic (pure; unit-tested via build-counter.test.mjs) ---

export function isBuildCommand(command) {
  return BUILD_COMMAND_RE.test(command || '');
}

/**
 * Compute the next failure state from the previous one and a build exit code.
 * Mirrors the prior dispatch.mjs logic, now owned synchronously here (P0f):
 *   - non-zero exit → increment consecutiveFailures, reset advisoryShown
 *   - exit 0        → reset counter (explicit success)
 *   - null/missing  → neutral (counter unchanged; don't reset on unknown)
 */
export function nextFailureState(prev, exitCode) {
  const state = {
    consecutiveFailures: prev?.consecutiveFailures || 0,
    advisoryShown: prev?.advisoryShown || false,
  };
  if (exitCode != null && exitCode !== 0) {
    state.consecutiveFailures += 1;
    state.advisoryShown = false;
  } else if (exitCode === 0) {
    state.consecutiveFailures = 0;
    state.advisoryShown = false;
  }
  return state;
}

/** One-shot advisory gate: block once at/over threshold, until reset. */
export function shouldBlock(state, threshold = CONSECUTIVE_FAIL_THRESHOLD) {
  return (state?.consecutiveFailures || 0) >= threshold && !state?.advisoryShown;
}

// --- Main (thin I/O wrapper) ---
//
// P0f: counting now happens synchronously here on PostToolUse, replacing the
// async dispatch.mjs write. Because PostToolUse(count) and the next
// PreToolUse(check) are both synchronous, the count is always fresh — the
// previous async-write/sync-read race (advisory firing one call late) is gone.

async function main() {
  const raw = await readStdin();

  try {
    const input = JSON.parse(raw);
    const sessionId = input.session_id;
    const command = (input.tool_input?.command || '').substring(0, 200);

    if (!sessionId) return;
    if (!isBuildCommand(command)) return;

    // PostToolUse: record the build's pass/fail synchronously.
    if (input.hook_event_name === 'PostToolUse') {
      const prev = loadState(sessionId);
      saveState(sessionId, nextFailureState(prev, input.tool_response?.exitCode));
      return;
    }

    // PreToolUse (default): block once when over the failure threshold.
    const state = loadState(sessionId);
    if (shouldBlock(state)) {
      // Mark advisory as shown so the agent can retry once
      state.advisoryShown = true;
      saveState(sessionId, state);

      process.stderr.write(
        `Build has failed ${state.consecutiveFailures} consecutive times. ` +
        `Investigate the root cause before running another build.`
      );
      process.exit(2);
    }

    // No output = allow
  } catch {
    // Parse failure — allow (don't block on hook errors)
  }
}

runHook(import.meta.url, main);
