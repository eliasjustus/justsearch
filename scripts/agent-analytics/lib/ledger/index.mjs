/**
 * lib/ledger/index.mjs — merge point for every harness adapter (tempdoc 886
 * §12 PR 1/PR 2). `listCalls` is the one entry point a reader should import;
 * it never throws (each adapter is already tolerant of a missing root, and
 * this module double-guards with try/catch so an adapter regression
 * degrades to "that harness contributed nothing" instead of crashing the
 * merge).
 *
 * WINDOW SEMANTICS (886 §12 PR 2 fix, independent review). `sinceMs`/
 * `untilMs` are used TWICE, for two different purposes:
 *
 *   1. A cheap DISCOVERY prefilter passed straight through to each adapter,
 *      which skips a whole transcript FILE by mtime before parsing a byte
 *      of it (`claude-adapter.mjs`/`codex-adapter.mjs`'s own `fs.statSync`
 *      checks). This is unavoidably mtime-based — there is no cheaper way
 *      to skip a file — and stays exactly as before.
 *   2. A per-CALL filter on `windowBy: 'ts'` (the default): after merging,
 *      every `Call`/`ToolEvent` whose OWN `ts` falls outside
 *      `[sinceMs, untilMs]` is dropped. This is the fix — a file's mtime is
 *      its LAST write, so a `--since 2026-08-01` request previously kept
 *      every call in a file that happened to be touched on-or-after that
 *      date, including calls from WEEKS earlier in a long-lived session
 *      (measured: 5,541 calls dated before `--since` leaked into a
 *      2026-08-01 query via this exact gap). A call whose `ts` is null or
 *      unparsable cannot be judged against the window, so it is KEPT
 *      (never silently dropped) and counted in the returned
 *      `unfilterableTs` — the honest "we don't know" count, not a $0-style
 *      silent exclusion.
 *
 * Pass `windowBy: 'mtime'` to opt back into file-mtime-only semantics (the
 * pre-fix behaviour) — e.g. a caller that already dedupes/reasons about
 * whole sessions rather than individual calls. When neither `sinceMs` nor
 * `untilMs` is given, no per-call filtering runs regardless of `windowBy`
 * (nothing to filter against), and the result shape is unchanged from
 * before this fix (no `unfilterableTs` key) — existing no-window callers
 * see byte-identical output.
 */

import { listClaudeCalls } from './claude-adapter.mjs';
import { listCodexCalls } from './codex-adapter.mjs';

const EMPTY = { calls: [], toolEvents: [], sessions: [], skipped: [] };

function parseTs(ts) {
  if (ts == null) return null;
  const t = typeof ts === 'number' ? ts : Date.parse(ts);
  return Number.isNaN(t) ? null : t;
}

/** True when `ts` is unparsable/absent (kept, unjudgeable) or falls inside `[sinceMs, untilMs]`. */
function inTsWindow(ts, sinceMs, untilMs) {
  const t = parseTs(ts);
  if (t == null) return true;
  if (sinceMs != null && t < sinceMs) return false;
  if (untilMs != null && t > untilMs) return false;
  return true;
}

/**
 * `listCalls({harnesses, sinceMs, untilMs, projectFilter, projectsRoot,
 * codexHome, windowBy})` — merges every requested harness's `Call`s,
 * `ToolEvent`s, per-session summaries, and `skipped` file records into one
 * result. `harnesses` defaults to both. `projectsRoot`/`codexHome` are
 * per-harness root overrides (mainly for tests, which point them at a
 * fixture tree); `sinceMs`/`untilMs`/`projectFilter` apply to both harnesses
 * identically. `windowBy` (`'ts'` default, `'mtime'` opt-out) is the
 * per-call-vs-per-file window semantics documented above.
 */
export function listCalls({
  harnesses = ['claude-code', 'codex-cli'],
  sinceMs,
  untilMs,
  projectFilter,
  projectsRoot,
  codexHome,
  windowBy = 'ts',
} = {}) {
  const calls = [];
  const toolEvents = [];
  const sessions = [];
  const skipped = [];

  if (harnesses.includes('claude-code')) {
    let r;
    try {
      r = listClaudeCalls({ projectsRoot, sinceMs, untilMs, projectFilter });
    } catch {
      r = EMPTY;
    }
    calls.push(...r.calls);
    toolEvents.push(...r.toolEvents);
    sessions.push(...r.sessions);
    skipped.push(...(r.skipped || []));
  }

  if (harnesses.includes('codex-cli')) {
    let r;
    try {
      r = listCodexCalls({ codexHome, sinceMs, untilMs, projectFilter });
    } catch {
      r = EMPTY;
    }
    calls.push(...r.calls);
    toolEvents.push(...r.toolEvents);
    sessions.push(...r.sessions);
    skipped.push(...(r.skipped || []));
  }

  if (windowBy !== 'ts' || (sinceMs == null && untilMs == null)) {
    return { calls, toolEvents, sessions, skipped };
  }

  let unfilterableTs = 0;
  const filteredCalls = [];
  for (const c of calls) {
    if (parseTs(c.ts) == null) unfilterableTs += 1;
    if (inTsWindow(c.ts, sinceMs, untilMs)) filteredCalls.push(c);
  }
  const filteredToolEvents = toolEvents.filter((e) => inTsWindow(e.ts, sinceMs, untilMs));
  const keepSessionIds = new Set(filteredCalls.map((c) => c.sessionId));
  const filteredSessions = sessions.filter((s) => keepSessionIds.has(s.sessionId));

  return { calls: filteredCalls, toolEvents: filteredToolEvents, sessions: filteredSessions, skipped, unfilterableTs };
}
