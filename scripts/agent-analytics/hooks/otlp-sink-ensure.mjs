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
 *  - Main-checkout-rooted output (fix, tempdoc 743): otlp-sink.py's `--out`
 *    default is the RELATIVE path `tmp/agent-telemetry/otlp`, resolved against
 *    its spawn `cwd`. A session running from a git worktree has `repoRoot`
 *    pointed at the WORKTREE (whose `tmp/` is deleted at teardown), so a sink
 *    spawned with no `--out` writes into an ephemeral directory — and because
 *    the port probe treats ANY listener on 4318 as "already up", one
 *    wrong-directory sink silently poisons every later session sharing the
 *    port. We resolve the MAIN checkout root (`resolveMainRepoRoot`, already
 *    shared with the dev-runner's supervisor-state root) and pass an explicit
 *    absolute `--out` under it, so telemetry always lands in one durable place
 *    regardless of which worktree happened to start the sink first.
 */

import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { repoRoot, mainRepoRoot, runHook } from '../lib/hook-base.mjs';

const SINK_HOST = '127.0.0.1';
const SINK_PORT = 4318;
const SINK_SCRIPT = path.join(repoRoot, 'scripts', 'agent-analytics', 'otlp-sink.py');
const PYTHON = process.platform === 'win32' ? 'python' : 'python3';
// Absolute, main-checkout-rooted output dir — never the relative default,
// which would resolve against a worktree's ephemeral `tmp/` (see header).
const SINK_OUT_DIR = path.join(mainRepoRoot, 'tmp', 'agent-telemetry', 'otlp');

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
  const child = spawn(
    PYTHON,
    [SINK_SCRIPT, '--port', String(SINK_PORT), '--out', SINK_OUT_DIR],
    {
      cwd: repoRoot,
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
    }
  );
  child.unref();
}

async function main() {
  if (await isSinkListening()) return; // already up (this or a concurrent session)
  startSink();
}

runHook(import.meta.url, main);

export { isSinkListening, SINK_PORT, SINK_SCRIPT, SINK_OUT_DIR, mainRepoRoot };
