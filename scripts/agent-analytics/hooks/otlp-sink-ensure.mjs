#!/usr/bin/env node

/**
 * SessionStart hook (tempdoc 622 §S1) — idempotently ensure the local OTLP sink
 * is running, so native Claude Code telemetry capture is AUTOMATIC instead of a
 * manual `python scripts/agent-analytics/otlp-sink.py` every session.
 *
 * Emission is always-on (~/.claude/settings.json exports OTEL_* every session →
 * Claude Code POSTs OTLP to http://127.0.0.1:4318). But nothing RECEIVES it unless
 * the sink (otlp-sink.py) is listening. This hook closes that gap.
 *
 * Shape (deliberate):
 *  - Idempotent: probe 127.0.0.1:4318 first; if something is already listening
 *    (this session's prior run, OR a concurrent session's sink), no-op. The sink
 *    is a SHARED, persistent daemon — concurrent sessions all emit into the one
 *    receiver appending to tmp/agent-telemetry/otlp/.
 *  - Detached: spawn the sink unref'd with ignored stdio so it OUTLIVES this hook
 *    (a 5s SessionStart hook can't host a long-lived server) and survives across
 *    sessions. There is intentionally NO SessionEnd kill — killing it would drop
 *    capture for every other live session.
 *  - Fail-open: runHook() catches everything → exit 0. If python is absent or the
 *    port probe errors, the session proceeds; capture is best-effort.
 *  - CI-safe: runHook() returns early under JUSTSEARCH_DISABLE_HOOKS=1, which the
 *    hook-integrity load-test sets — so the gate never spawns a stray sink.
 */

import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { repoRoot, telemetryDir, runHook } from '../lib/hook-base.mjs';

const SINK_HOST = '127.0.0.1';
const SINK_PORT = 4318;
const SINK_SCRIPT = path.join(repoRoot, 'scripts', 'agent-analytics', 'otlp-sink.py');
const PYTHON = process.platform === 'win32' ? 'python' : 'python3';

/** Resolve true iff something is already listening on the sink port (probe). */
function isSinkListening() {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host: SINK_HOST, port: SINK_PORT });
    let settled = false;
    const done = (listening) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(listening);
    };
    socket.setTimeout(500);
    socket.once('connect', () => done(true));
    socket.once('timeout', () => done(false));
    socket.once('error', () => done(false)); // ECONNREFUSED → nothing listening
  });
}

/** Spawn the sink detached so it outlives this hook and the session. */
function startSink() {
  const child = spawn(PYTHON, [SINK_SCRIPT, '--port', String(SINK_PORT)], {
    cwd: repoRoot,
    detached: true,
    stdio: 'ignore',
    windowsHide: true,
  });
  child.unref();
}

async function main() {
  if (await isSinkListening()) return; // already up (this or a concurrent session)
  startSink();
}

runHook(import.meta.url, main);

export { isSinkListening, SINK_PORT, SINK_SCRIPT, telemetryDir };
