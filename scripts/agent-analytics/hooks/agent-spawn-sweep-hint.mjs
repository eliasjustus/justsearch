#!/usr/bin/env node

/**
 * SessionStart hook (tempdoc 861 §6.4 / §7.1 Phase 5) — the abandonment sweep.
 *
 * "Session start — sweep lapsed records. This is the principle made concrete: the remedy for a
 * session that died without a turn runs in the *next* session's opening, not in the dead one's
 * last moments. It is the only trigger that works for a crash, a 60-minute task kill, or a
 * power loss." (861 §6.4) The occasion is `session-start` — EXECUTE capability, bound in the
 * reaper's frozen `OCCASIONS` map — so a lapsed-AND-stale registered spawn from a dead session
 * (861 §6.3's `LAPSED_OWNER_STALE` cell) is the only case this ever reaps; a quiet-but-working
 * owner, an `ownerless-singleton`, and the dev-runner's own active run are all left alone by the
 * matrix itself, not by anything this hook adds.
 *
 * Async and best-effort by design (mirrors `otlp-sink-ensure.mjs`'s SessionStart binding): a
 * 5s hook budget cannot wait on a PowerShell process-table query plus N identity
 * re-verifications, and a missed sweep this session is caught by the NEXT session's opening —
 * there is no single point of failure. Unlike `otlp-sink-ensure.mjs` this hook has no fail-loud
 * path: a missed reap leaks a Vite server, not a silently-broken telemetry pipeline, so a
 * best-effort telemetry note (never a loud stderr line) is the right level of alarm.
 *
 * Never kills outside `executeReap`'s own re-verification (861 §6.2/§6.3): this hook does not
 * decide who gets reaped, it only wires the SessionStart occasion onto the shared assembly in
 * `scripts/dev/lib/agent-spawn-sweep.cjs`.
 */

import { createRequire } from 'node:module';
import { mainRepoRoot, runHook, readJsonStdin, appendTelemetryEvent } from '../lib/hook-base.mjs';

// Tempdoc 861 §7.5 — the documented cross-format interop: an ESM hook pulls the shared `.cjs`
// dev-stack libs in via `createRequire`, exactly as `otlp-sink-ensure.mjs` already does.
const require = createRequire(import.meta.url);
const { runAgentSpawnSweep } = require('../../dev/lib/agent-spawn-sweep.cjs');

async function main() {
  const input = await readJsonStdin();
  const sessionId = input?.session_id ?? null;

  let result;
  try {
    result = await runAgentSpawnSweep({
      occasion: 'session-start',
      mainRepoRoot,
      callerSessionId: sessionId,
      actorSource: 'agent-spawn-sweep-hint',
    });
  } catch (err) {
    // Best-effort: a sweep failure is a missed cleanup opportunity, not a session-blocking
    // event, and the NEXT session's own sweep gets another chance at the same records.
    appendTelemetryEvent({
      event: 'agent_spawn_sweep_failed',
      occasion: 'session-start',
      message: String(err?.message || err).slice(0, 200),
      ts: new Date().toISOString(),
    });
    return;
  }

  if (result.buckets.all.length > 0) {
    appendTelemetryEvent({
      event: 'agent_spawn_sweep',
      occasion: 'session-start',
      reaped: result.kills.filter((k) => k.killed).length,
      reapAttempted: result.buckets.reap.length,
      contention: result.buckets.contention.length,
      refused: result.buckets.refuse.length,
      reported: result.buckets.report.length,
      ts: new Date().toISOString(),
    });
  }
}

runHook(import.meta.url, main);
