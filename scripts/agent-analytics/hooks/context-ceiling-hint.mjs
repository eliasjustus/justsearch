#!/usr/bin/env node

/**
 * PostToolUse hook (matcher: "" — every tool) — context-ceiling-hint
 * (tempdoc 886 §12 PR 4 / §2.2).
 *
 * 886 §2.2 found the unmeasured variable: main-loop context per call runs p50
 * 415k, p75 627k — sessions grow from a 66k cold start to 500k+ and STAY there,
 * with cache-read cost above a 200k cap at ~29% of window spend. Nothing surfaces
 * that residency to the agent mid-session; the only signals are 9 compactions in
 * a 5-week window (8 manual, 1 auto), which means the ceiling is mostly crossed
 * silently. This hook delivers the finding at its moment of relevance: after every
 * tool call, read the LAST assistant turn's usage off `transcript_path` and, once
 * per session per threshold, remind the agent that every subsequent call now
 * re-reads this context and name the two remedies (`/compact`, `/rewind`).
 *
 * Reads only the tail of the transcript (last ~256KB, retried once at ~2MB if
 * no assistant usage line turns up — a single trailing tool_result can exceed
 * 256KB and push the last assistant line out of the first tail read), never
 * the whole file — transcripts reach hundreds of MB, and only the LAST
 * assistant usage snapshot matters (Claude Code repeats it cumulatively per
 * turn, cache-efficiency.mjs's precedent). Once-per-threshold state is a
 * small per-session JSON file under hook-base's telemetryDir, the same shape
 * build-counter.mjs/repeat-guard.mjs use — and, like build-counter.mjs's
 * per-session state, it is NOT swept on SessionEnd (dispatch.mjs's cleanup
 * list covers turn-count/repeat-buffer/build-fails only), so
 * `context-ceiling-state/` is a known small pile of one-file-per-session
 * state that outlives its session; harmless (a few hundred bytes each,
 * session_id-keyed, never read cross-session) but not swept today.
 *
 * RE-ARM (independent review fix, 886 §12 PR 4): a threshold flag, once set,
 * used to never clear — so a session that crossed 300k/500k, then dropped back
 * below 300k via `/compact`, then climbed past 300k/500k again got NO second
 * hint (reproduced: 310k fires, 520k fires, a post-compact 20k call is
 * silent as expected, but so were 340k and 610k afterward — silently wrong).
 * Fixed: every call below the LOWEST threshold clears BOTH notified flags for
 * that session (an explicit re-arm, not just "no threshold matched this
 * call"), and `lastCtx` is stored on every write so the transition is visible
 * in the state file, not just inferred from absence.
 *
 * Advisory: never blocks, fail-open on any error, honors JUSTSEARCH_DISABLE_HOOKS.
 */

import fs from 'node:fs';
import path from 'node:path';
import { readJsonStdin, hooksDisabled, isDirectRun, atomicWriteFileSync, telemetryDir } from '../lib/hook-base.mjs';

const TAIL_BYTES = 256 * 1024;
const RETRY_TAIL_BYTES = 2 * 1024 * 1024;
const THRESHOLDS = [
  { key: 'notified500', tokens: 500_000, label: '500k' },
  { key: 'notified300', tokens: 300_000, label: '300k' },
]; // checked in descending order: the highest crossed threshold wins for this call.
const LOW_THRESHOLD_TOKENS = THRESHOLDS[THRESHOLDS.length - 1].tokens; // 300_000 — below this, re-arm.

const STATE_DIR = path.join(telemetryDir, 'context-ceiling-state');

/** Same tiered-cache-write fallback the claude-adapter/transcript-cost modules use. */
function splitCacheWrite(usage) {
  const cc = usage.cache_creation;
  if (cc && typeof cc === 'object') {
    const w5 = cc.ephemeral_5m_input_tokens ?? 0;
    const w1 = cc.ephemeral_1h_input_tokens ?? 0;
    if (w5 || w1) return { w5, w1 };
  }
  return { w5: usage.cache_creation_input_tokens ?? 0, w1: 0 };
}

/** contextTokens = input + cache_read + cache_creation, the 886 §12 rule. */
export function contextTokensOf(usage) {
  const { w5, w1 } = splitCacheWrite(usage);
  return (usage.input_tokens ?? 0) + (usage.cache_read_input_tokens ?? 0) + w5 + w1;
}

/** One tail read + backwards scan for the last assistant `message.usage`; null if none found. */
function readTailUsage(transcriptPath, tailBytes) {
  let stat;
  try {
    stat = fs.statSync(transcriptPath);
  } catch {
    return null;
  }
  const start = Math.max(0, stat.size - tailBytes);
  const len = stat.size - start;
  if (len <= 0) return null;
  const fd = fs.openSync(transcriptPath, 'r');
  let buf;
  try {
    buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, start);
  } finally {
    fs.closeSync(fd);
  }
  let text = buf.toString('utf8');
  if (start > 0) {
    // We started mid-file: the first line is likely a truncated partial JSON line — drop it.
    const nl = text.indexOf('\n');
    text = nl === -1 ? '' : text.slice(nl + 1);
  }
  const lines = text.split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (!line) continue;
    let entry;
    try {
      entry = JSON.parse(line);
    } catch {
      continue;
    }
    if (entry.type !== 'assistant') continue;
    const usage = entry.message?.usage;
    if (usage) return usage;
  }
  return null;
}

/**
 * Read the LAST assistant `message.usage` off a transcript by reading only its
 * tail (`tailBytes`) and scanning lines backwards — never the whole file. If
 * nothing turns up (a single trailing tool_result can exceed the tail size and
 * push every assistant line out of view), retry once with `retryTailBytes`
 * before giving up. Pure over injected sizes for testability without huge
 * fixture files.
 */
export function lastAssistantUsage(transcriptPath, { tailBytes = TAIL_BYTES, retryTailBytes = RETRY_TAIL_BYTES } = {}) {
  const usage = readTailUsage(transcriptPath, tailBytes);
  if (usage) return usage;
  if (retryTailBytes > tailBytes) return readTailUsage(transcriptPath, retryTailBytes);
  return null;
}

/** Last Codex token_count snapshot from a rollout tail. */
function readTailCodexContext(transcriptPath, tailBytes) {
  let stat;
  try { stat = fs.statSync(transcriptPath); } catch { return null; }
  const start = Math.max(0, stat.size - tailBytes);
  const len = stat.size - start;
  if (len <= 0) return null;
  const fd = fs.openSync(transcriptPath, 'r');
  let buf;
  try {
    buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, start);
  } finally {
    fs.closeSync(fd);
  }
  let text = buf.toString('utf8');
  if (start > 0) {
    const nl = text.indexOf('\n');
    text = nl === -1 ? '' : text.slice(nl + 1);
  }
  const lines = text.split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    let entry;
    try { entry = JSON.parse(lines[i]); } catch { continue; }
    if (entry?.type !== 'event_msg' || entry?.payload?.type !== 'token_count') continue;
    const info = entry.payload.info;
    const contextTokens = info?.last_token_usage?.input_tokens;
    const contextWindow = info?.model_context_window;
    if (Number.isFinite(contextTokens) && Number.isFinite(contextWindow) && contextWindow > 0) {
      return { contextTokens, contextWindow };
    }
  }
  return null;
}

export function lastCodexContext(transcriptPath, { tailBytes = TAIL_BYTES, retryTailBytes = RETRY_TAIL_BYTES } = {}) {
  return readTailCodexContext(transcriptPath, tailBytes)
    ?? (retryTailBytes > tailBytes ? readTailCodexContext(transcriptPath, retryTailBytes) : null);
}

export function nextCodexThreshold({ contextTokens, contextWindow }, state) {
  const ratio = contextTokens / contextWindow;
  if (ratio >= 0.9 && !state?.codexNotified90) return { key: 'codexNotified90', ratio, label: '90%' };
  if (ratio >= 0.75 && !state?.codexNotified75) return { key: 'codexNotified75', ratio, label: '75%' };
  return null;
}

function advanceCodexState(snapshot, prevState) {
  const ratio = snapshot.contextTokens / snapshot.contextWindow;
  const carried = ratio < 0.7
    ? { ...(prevState ?? {}), codexNotified75: false, codexNotified90: false }
    : { ...(prevState ?? {}) };
  return { ...carried, lastCtx: snapshot.contextTokens, lastWindow: snapshot.contextWindow };
}

function stateFile(sessionId) {
  return path.join(STATE_DIR, `${sessionId}.json`);
}

function loadState(sessionId) {
  try {
    return JSON.parse(fs.readFileSync(stateFile(sessionId), 'utf8'));
  } catch {
    return {};
  }
}

function saveState(sessionId, state) {
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    atomicWriteFileSync(stateFile(sessionId), JSON.stringify(state));
  } catch {
    // best-effort — never block the hook on a write failure
  }
}

/** Pure decision: which threshold (if any) newly fires for this contextTokens + prior state. */
export function nextThreshold(contextTokens, state) {
  for (const t of THRESHOLDS) {
    if (contextTokens >= t.tokens && !state?.[t.key]) return t;
  }
  return null;
}

/**
 * Re-arm: below the lowest threshold, both notified flags are cleared (a real
 * transition, not just "nothing crossed this call") so the next climb past
 * 300k/500k fires again. At/above it, prior flags carry forward unchanged.
 * `lastCtx` is always refreshed so the state file makes the transition explicit.
 */
export function advanceState(contextTokens, prevState) {
  const carried = contextTokens < LOW_THRESHOLD_TOKENS ? {} : (prevState ?? {});
  return { ...carried, lastCtx: contextTokens };
}

export function renderCeilingLine(contextTokens, threshold) {
  const nk = Math.round(contextTokens / 1000);
  const suffix = threshold.label === '500k' ? ' (past 500k)' : '';
  return (
    `context-ceiling: ${nk}k tokens in context${suffix} — every call now re-reads this; ` +
    `/compact <hint> at the next task boundary, or /rewind if abandoning a path (886 §2.2)`
  );
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || !input.session_id || !input.transcript_path) return;

  const prevState = loadState(input.session_id);
  if (process.env.JUSTSEARCH_AGENT_HARNESS === 'codex-cli') {
    const snapshot = lastCodexContext(input.transcript_path);
    if (!snapshot) return;
    const armedState = advanceCodexState(snapshot, prevState);
    const threshold = nextCodexThreshold(snapshot, armedState);
    if (!threshold) {
      saveState(input.session_id, armedState);
      return;
    }
    const nextState = { ...armedState, codexNotified75: true };
    if (threshold.key === 'codexNotified90') nextState.codexNotified90 = true;
    saveState(input.session_id, nextState);
    const percent = Math.round(threshold.ratio * 100);
    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext: `context-ceiling: ${percent}% of the Codex context window is resident — use /compact <hint> at the next task boundary`,
      },
    }));
    return;
  }

  const usage = lastAssistantUsage(input.transcript_path);
  if (!usage) return;
  const contextTokens = contextTokensOf(usage);
  const armedState = advanceState(contextTokens, prevState);
  const threshold = nextThreshold(contextTokens, armedState);
  if (!threshold) {
    saveState(input.session_id, armedState); // persist the re-arm / lastCtx even when silent
    return;
  }

  // Crossing 500k implies 300k was already crossed — mark both so 300k never
  // fires stale after a 500k call.
  const nextState = { ...armedState, notified300: true };
  if (threshold.label === '500k') nextState.notified500 = true;
  saveState(input.session_id, nextState);

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: renderCeilingLine(contextTokens, threshold) },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
