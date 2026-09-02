/**
 * lib/ledger/codex-adapter.mjs — OpenAI Codex CLI rollout transcripts
 * projected onto the neutral `Call`/`ToolEvent` shape (tempdoc 886 §12 PR 1,
 * independent-review fix-up).
 *
 * Source: `${codexHome}/sessions/**\/rollout-*.jsonl` (recursing the
 * `YYYY/MM/DD` layout). `archived_sessions` is skipped wherever it appears in
 * the walk (it lives as a SIBLING of `sessions/` on this machine, but the
 * skip is defensive against a layout that nests it).
 *
 * Every rule below is from tempdoc 886 §11's derisk pass (A1/A2/A7), verified
 * against the real corpus (51,740 `token_count` events, 289 sessions) before
 * this adapter was written — not re-derived here:
 *
 *   A1  `last_token_usage.input_tokens` INCLUDES `cached_input_tokens` (the
 *       OpenAI convention, confirmed: 0 of 51,740 events have cached > input).
 *       So `contextTokens = input_tokens`, `fresh = input_tokens - cached_input_tokens`.
 *   A2  `last_token_usage` is a per-call DELTA, not a running total — but a
 *       `token_count` event is a REPEAT (not a new call) when its
 *       `total_token_usage.total_tokens` exactly equals the previous kept
 *       event's; 1,482 such repeats exist corpus-wide and must be dropped, or
 *       every reader downstream double-counts a call that never happened.
 *       NEVER read `total_token_usage` as if it were per-call — a resumed
 *       thread's cumulative counter carries prior history forward, so it
 *       diverges from the sum of deltas by construction, not by bug (see
 *       `selfCheck` below).
 *   A7  `function_call`/`custom_tool_call` (+ their `_output` siblings) and
 *       `inter_agent_communication_metadata` are present and richer than
 *       assumed pre-derisk — tool outputs run up to 751k chars, hence the
 *       64k-char cap here (`OUTPUT_CHAR_CAP`).
 *
 * A `rate_limits`-only `token_count` event (`info: null`) carries no usage at
 * all and is skipped outright, not counted as a repeat.
 *
 * LINEAGE (independent review fix, 886 §12 PR 1): every Codex `Call` has
 * `lineage.kind = 'main'`. `inter_agent_communication_metadata` was
 * originally read as a per-call `'thread'` lineage kind; on real corpus
 * payloads (`{trigger_turn: false}`, no parent id) it names no PARENT, so no
 * per-call edge is derivable from it — asserting `'thread'` from it was
 * fabricating lineage evidence that was not there. What IS real is a
 * SESSION-level fact ("this session had multi-agent communication at some
 * point"), surfaced as `session.multiAgent`, not as a per-call kind. See
 * `record.mjs`'s `VALID_LINEAGE_KINDS` doc for what would need to be true
 * for a future adapter to legitimately produce `'thread'`.
 *
 * TOOL EVENTS (independent review fix): `agent_message` payloads are plain
 * assistant reply text, not tool activity, so this adapter no longer creates
 * a `ToolEvent` for them (`lib/ledger/tool-roles.mjs` still maps the NAME to
 * a role, for table completeness, independent of whether this adapter emits
 * it).
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { makeCall, makeToolEvent } from './record.mjs';
import { roleFor } from './tool-roles.mjs';

export const DEFAULT_CODEX_HOME = path.join(os.homedir(), '.codex');
const OUTPUT_CHAR_CAP = 65536;
const ARCHIVED_DIR_NAME = 'archived_sessions';

function walkRolloutFiles(dir, out) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    if (e.name === ARCHIVED_DIR_NAME) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      walkRolloutFiles(p, out);
    } else if (e.isFile() && e.name.startsWith('rollout-') && e.name.endsWith('.jsonl')) {
      out.push(p);
    }
  }
}

function parseLines(file) {
  let content;
  try {
    content = fs.readFileSync(file, 'utf8');
  } catch {
    return [];
  }
  const out = [];
  for (const raw of content.split('\n')) {
    if (!raw.trim()) continue;
    try {
      out.push(JSON.parse(raw));
    } catch {
      // truncated/corrupt line — skip it, matching every other reader's per-line tolerance
    }
  }
  return out;
}

function outputStringOf(payload) {
  const out = payload.output;
  if (typeof out === 'string') return out;
  try {
    return JSON.stringify(out ?? '');
  } catch {
    return '';
  }
}

function inputStringOf(payload) {
  if (payload.type === 'function_call') return payload.arguments ?? '';
  const input = payload.input;
  return typeof input === 'string' ? input : JSON.stringify(input ?? '');
}

/**
 * The ONE documented, deliberately-handled failure mode: a rollout file with
 * no usable `sessionId`. `makeCall`/`makeToolEvent` both require one, so a
 * file with no `session_meta` line (or a `session_meta` whose `payload.id`
 * is missing/empty) cannot produce a valid record — this is detected UP
 * FRONT, before any `Call` is attempted, and returned as an explicit skip
 * signal rather than caught after the fact. This is the only skip path;
 * `listCodexCalls` does not wrap the rest of parsing in a try/catch, so any
 * OTHER exception (a genuine bug) propagates instead of being silently
 * swallowed (independent review, 886 §12 PR 1 fix-up — the previous version
 * caught everything here, which also hid real bugs).
 */
function findSessionId(entries) {
  const metaEntry = entries.find((e) => e.type === 'session_meta');
  return metaEntry?.payload?.id || null;
}

/**
 * Build `{calls, toolEvents, session}` from an already-parsed entries array.
 * Exported (not just `processCodexFile`) so a test can feed it a crafted
 * entries array directly — including one designed to throw — without going
 * through `JSON.parse`, which can never produce the shapes needed to
 * exercise a genuine propagation path (no getters/proxies survive a JSON
 * round-trip). `file` is carried through only for the skip-reason record.
 */
export function processCodexEntries(entries, { file } = {}) {
  const sessionId = findSessionId(entries);
  if (!sessionId) {
    return { calls: [], toolEvents: [], session: null, skip: { file, reason: 'no usable sessionId (missing or empty session_meta.payload.id)' } };
  }

  // `inter_agent_communication_metadata` marks the SESSION, not a lineage
  // edge for any one call (see module doc) — pre-scanned so a line appearing
  // late in the file still sets the session-level flag correctly regardless
  // of where in a single sequential pass it is encountered.
  const multiAgent = entries.some((e) => e.type === 'inter_agent_communication_metadata');

  const calls = [];
  const toolEvents = [];
  const pendingByCallId = new Map();

  let project = null;
  let provider = 'openai';
  let currentModel = null;
  let compactionPending = false;
  let compactedTs = null;
  let prevCumulativeTotal = null;
  let prevCumulativeInput = null;
  let index = 0;
  let firstTs = null;
  let lastTs = null;
  let deltaInputSum = 0;
  let maxCumulativeInput = 0;
  let resets = 0;
  let repeatsDropped = 0;

  const lineage = { parentSessionId: null, kind: 'main' };

  for (const entry of entries) {
    const ts = entry.timestamp ?? null;
    if (ts) {
      if (!firstTs) firstTs = ts;
      lastTs = ts;
    }

    if (entry.type === 'session_meta') {
      project = entry.payload?.cwd ?? project;
      provider = entry.payload?.model_provider ?? provider;
      continue;
    }

    if (entry.type === 'turn_context') {
      currentModel = entry.payload?.model ?? currentModel;
      continue;
    }

    if (entry.type === 'inter_agent_communication_metadata') {
      continue; // pre-scanned above; nothing to do per-line
    }

    if (entry.type === 'compacted') {
      compactionPending = true;
      compactedTs = ts;
      continue;
    }

    if (entry.type === 'event_msg') {
      const p = entry.payload;

      if (p?.type === 'token_count') {
        if (!p.info) continue; // rate_limits-only event — no usage at all
        const L = p.info.last_token_usage;
        const T = p.info.total_token_usage;
        if (!L || !T) continue;

        const curInput = T.input_tokens ?? 0;
        if (curInput > maxCumulativeInput) maxCumulativeInput = curInput;
        if (prevCumulativeInput != null && curInput < prevCumulativeInput) resets += 1;
        prevCumulativeInput = curInput;

        if (prevCumulativeTotal != null && T.total_tokens === prevCumulativeTotal) {
          repeatsDropped += 1;
          prevCumulativeTotal = T.total_tokens;
          continue; // A2: exact repeat of the previous cumulative — not a new call
        }
        prevCumulativeTotal = T.total_tokens;
        deltaInputSum += L.input_tokens ?? 0;

        const fresh = Math.max(0, (L.input_tokens ?? 0) - (L.cached_input_tokens ?? 0));
        const boundary = compactionPending;
        if (boundary) compactionPending = false;

        calls.push(makeCall({
          harness: 'codex-cli',
          provider,
          project,
          sessionId,
          callId: `${sessionId}:${index}`,
          lineage,
          ts,
          model: currentModel,
          tokens: {
            fresh,
            cacheRead: L.cached_input_tokens ?? 0,
            cacheWrite5m: null,
            cacheWrite1h: null,
            output: L.output_tokens ?? 0,
            reasoning: L.reasoning_output_tokens ?? 0,
          },
          contextTokens: L.input_tokens ?? 0, // A1: input_tokens already includes cached
          compactionBoundary: boundary,
        }));
        index += 1;
        continue;
      }

      // agent_message: deliberately NOT turned into a ToolEvent (see module doc).
      continue;
    }

    if (entry.type === 'response_item') {
      const p = entry.payload;
      if (!p) continue;

      if (p.type === 'function_call' || p.type === 'custom_tool_call') {
        const inputStr = inputStringOf(p);
        pendingByCallId.set(p.call_id, {
          name: p.name,
          role: roleFor('codex-cli', p.name),
          inputChars: inputStr.length,
        });
        continue;
      }

      if (p.type === 'function_call_output' || p.type === 'custom_tool_call_output') {
        const outStr = outputStringOf(p);
        const truncated = outStr.length > OUTPUT_CHAR_CAP;
        const pending = pendingByCallId.get(p.call_id);
        toolEvents.push(makeToolEvent({
          harness: 'codex-cli',
          sessionId,
          callRef: null,
          role: pending?.role ?? 'other',
          name: pending?.name ?? '(unknown)',
          inputChars: pending?.inputChars ?? 0,
          outputChars: Math.min(outStr.length, OUTPUT_CHAR_CAP),
          isError: false,
          ts,
          truncated: truncated || undefined,
        }));
        if (pending) pendingByCallId.delete(p.call_id);
        continue;
      }
    }
  }

  // A `compacted` line with no following token_count call still marks a real
  // boundary. Rather than drop it silently, emit a synthetic zero-token Call
  // carrying `compactionBoundary: true` AND `synthetic: true` — the
  // documented choice from the brief's either/or (886 §12 PR 1): a boundary
  // the ledger never saw a call for is still a boundary a reader
  // (context-residency.mjs, PR 2) needs to see, and a synthetic zero-cost
  // Call, clearly flagged as such, is cheaper for readers to handle than a
  // second "orphan boundary" concept.
  if (compactionPending) {
    calls.push(makeCall({
      harness: 'codex-cli',
      provider,
      project,
      sessionId,
      callId: `${sessionId}:${index}`,
      lineage,
      ts: compactedTs,
      model: currentModel,
      tokens: { fresh: 0, cacheRead: 0, cacheWrite5m: null, cacheWrite1h: null, output: 0, reasoning: 0 },
      contextTokens: 0,
      compactionBoundary: true,
      synthetic: true,
    }));
    index += 1;
  }

  return {
    calls,
    toolEvents,
    session: {
      harness: 'codex-cli',
      sessionId,
      project,
      firstTs,
      lastTs,
      calls: calls.length,
      multiAgent,
      selfCheck: { deltaInputSum, maxCumulativeInput, resets, repeatsDropped },
    },
    skip: null,
  };
}

/** Parse one rollout file and build its `{calls, toolEvents, session, skip}`. */
function processCodexFile(file) {
  const entries = parseLines(file);
  return processCodexEntries(entries, { file });
}

/**
 * Every Codex CLI `Call`/`ToolEvent` this machine holds, across every
 * session under `${codexHome}/sessions`, in the `sinceMs`/`untilMs` mtime
 * window (file mtime — same posture as the Claude adapter/transcript-store,
 * not a per-line timestamp scan). Never throws on a missing `~/.codex`
 * root, and never throws on the ONE documented per-file skip condition
 * (no usable sessionId) — those are collected into `skipped`. Any OTHER
 * exception during parsing (a genuine bug, not a documented degrade case)
 * PROPAGATES — this function does not swallow it.
 */
export function listCodexCalls({ codexHome, sinceMs = null, untilMs = null, projectFilter = null } = {}) {
  const home = codexHome ?? DEFAULT_CODEX_HOME;
  const sessionsRoot = path.join(home, 'sessions');

  const files = [];
  walkRolloutFiles(sessionsRoot, files);

  const calls = [];
  const toolEvents = [];
  const sessions = [];
  const skipped = [];

  for (const file of files) {
    let stat;
    try {
      stat = fs.statSync(file);
    } catch {
      continue;
    }
    if (sinceMs != null && stat.mtimeMs < sinceMs) continue;
    if (untilMs != null && stat.mtimeMs > untilMs) continue;

    const result = processCodexFile(file);
    if (result.skip) {
      skipped.push(result.skip);
      continue;
    }
    if (projectFilter && !(result.session.project && projectFilter.test(result.session.project))) continue;

    calls.push(...result.calls);
    toolEvents.push(...result.toolEvents);
    sessions.push(result.session);
  }

  return { calls, toolEvents, sessions, skipped };
}
