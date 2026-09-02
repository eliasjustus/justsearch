/**
 * lib/ledger/index.mjs — merge point for every harness adapter (tempdoc 886
 * §12 PR 1). `listCalls` is the one entry point a reader should import; it
 * never throws (each adapter is already tolerant of a missing root, and this
 * module double-guards with try/catch so an adapter regression degrades to
 * "that harness contributed nothing" instead of crashing the merge).
 */

import { listClaudeCalls } from './claude-adapter.mjs';
import { listCodexCalls } from './codex-adapter.mjs';

const EMPTY = { calls: [], toolEvents: [], sessions: [], skipped: [] };

/**
 * `listCalls({harnesses, sinceMs, untilMs, projectFilter, projectsRoot,
 * codexHome})` — merges every requested harness's `Call`s, `ToolEvent`s,
 * per-session summaries, and `skipped` file records into one result.
 * `harnesses` defaults to both. `projectsRoot`/`codexHome` are per-harness
 * root overrides (mainly for tests, which point them at a fixture tree);
 * `sinceMs`/`untilMs`/`projectFilter` apply to both harnesses identically.
 */
export function listCalls({
  harnesses = ['claude-code', 'codex-cli'],
  sinceMs,
  untilMs,
  projectFilter,
  projectsRoot,
  codexHome,
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

  return { calls, toolEvents, sessions, skipped };
}
