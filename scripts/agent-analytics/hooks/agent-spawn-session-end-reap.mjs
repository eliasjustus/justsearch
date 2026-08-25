#!/usr/bin/env node

/**
 * SessionEnd hook (tempdoc 861 §6.4 / §7.1 Phase 5) — best-effort reap of THIS session's own
 * agent-spawn records.
 *
 * "Session end — best-effort reap of this session's own spawns. The fast path when it works;
 * no longer the only path." (861 §6.4) Scoped to `ownSessionOnly: true` deliberately, narrower
 * than the SessionStart sweep's full-register pass: the matrix's `same-session` rule ("a
 * session may always reap its own registered spawns", 861 §6.3) is unambiguous for a record
 * this exact session started, but reaping OTHER sessions' lapsed-and-stale records on every
 * SessionEnd (which fires far more often than a crash) would duplicate the abandonment sweep's
 * job at a cadence the tempdoc reserves for session-start. Records with no attributable
 * `sessionId` (unreadable, or written before 861) are excluded here for the same reason — the
 * session-start sweep is what covers those.
 *
 * <!-- rule:agent-spawn-session-end-reap -->
 *
 * Best-effort and non-blocking: SessionEnd hooks cannot hold up teardown, and a missed reap
 * here is caught by the next session's SessionStart sweep regardless.
 */

import { createRequire } from 'node:module';
import { mainRepoRoot, runHook, readJsonStdin, appendTelemetryEvent } from '../lib/hook-base.mjs';

const require = createRequire(import.meta.url);
const { runAgentSpawnSweep } = require('../../dev/lib/agent-spawn-sweep.cjs');

async function main() {
  const input = await readJsonStdin();
  const sessionId = input?.session_id ?? null;
  if (!sessionId) return; // nothing attributable to reap without a session id

  let result;
  try {
    result = await runAgentSpawnSweep({
      occasion: 'session-end',
      mainRepoRoot,
      callerSessionId: sessionId,
      ownSessionOnly: true,
      actorSource: 'agent-spawn-session-end-reap',
    });
  } catch (err) {
    appendTelemetryEvent({
      event: 'agent_spawn_sweep_failed',
      occasion: 'session-end',
      message: String(err?.message || err).slice(0, 200),
      ts: new Date().toISOString(),
    });
    return;
  }

  if (result.buckets.all.length > 0) {
    appendTelemetryEvent({
      event: 'agent_spawn_sweep',
      occasion: 'session-end',
      reaped: result.kills.filter((k) => k.killed).length,
      reapAttempted: result.buckets.reap.length,
      ts: new Date().toISOString(),
    });
  }
}

runHook(import.meta.url, main);
