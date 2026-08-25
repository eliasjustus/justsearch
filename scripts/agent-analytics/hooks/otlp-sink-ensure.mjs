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
 *  - Detached: spawn the sink unref'd so it OUTLIVES this hook (a 5s SessionStart
 *    hook can't host a long-lived server) and survives across sessions. There is
 *    intentionally NO SessionEnd kill — killing it would drop capture for every
 *    other live session.
 *  - Fail-open for BUGS in this hook: runHook() catches everything → exit 0. If
 *    the port probe itself errors unexpectedly, the session proceeds; capture is
 *    best-effort. This is DIFFERENT from the fail-LOUD path below, which is a
 *    normal (non-exceptional) outcome of this hook doing its job and finding the
 *    sink dead — it deliberately does NOT go through the catch/exit(0) path.
 *  - Fail-LOUD on sink death (tempdoc 829 R8): a scoop/python bump silently wiped
 *    site-packages on ~2026-07-29, so every spawn crashed instantly on
 *    `ModuleNotFoundError: No module named 'opentelemetry'` — for ~2 weeks,
 *    invisibly, because this hook was fire-and-forget with no re-check. It now
 *    waits LIVENESS_RECHECK_MS after spawning and re-probes the port; if the sink
 *    is still down, it writes a loud one-line warning + exits 2. This binding is
 *    `asyncRewake: true` (not plain `async: true`) — per Claude Code's hook
 *    contract, a plain-async hook's exit code/output is discarded once the
 *    SessionStart event has already moved on (nobody polls it), so a background
 *    hook needs `asyncRewake` specifically to have Claude "woken" with the
 *    stderr shown as a system reminder when it later exits 2. SessionStart's
 *    exit-2 handling is NON-blocking ("Can block?" = No in the harness's own
 *    table) — the session is never held up by this, satisfying fail-open for
 *    session FLOW while still being fail-LOUD for the human/agent watching.
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
 *  - Captured launch stderr (tempdoc 829 R8): the child's stderr is redirected
 *    to a small truncate-on-write log file (not `stdio: 'ignore'` for stderr
 *    anymore) so a failed re-probe — or the NEXT session's cold start — can
 *    quote the actual crash traceback instead of a bare "still down". stdout
 *    stays ignored; only crash output matters here.
 *  - Stale-data watchdog: even when something IS listening on the port, if the
 *    newest file under the sink's output dir is >7 days old, that is a signal
 *    worth a (softer) notice too — e.g. a stray unrelated process bound the
 *    port, or the sink is stuck. Uses the same asyncRewake/exit-2 channel.
 *  - Tempdoc 861 W3 [A6] — the sink is the third `agent-spawns/` producer, and the
 *    only `ownerless-singleton` one: it is DECLARED rather than exempted, so the
 *    reaper's matrix (861 §6.3) reads "never reap" for a real reason instead of
 *    this daemon sitting in the observed tier next to a printed kill line (the
 *    exact mis-kill invitation [A6] closes). Registration is best-effort and
 *    NEVER disturbs the sink itself: whether this session just spawned it or
 *    found it already listening (the common case — the sink outlives every
 *    session), the record is written or its lease renewed, never the process
 *    restarted or signalled.
 */

import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { repoRoot, mainRepoRoot, runHook } from '../lib/hook-base.mjs';

// Tempdoc 861 §7.5 — the documented cross-format interop: an ESM hook pulls the shared `.cjs`
// dev-stack libs in via `createRequire`, exactly as `server.mjs:125-130` already does.
const require = createRequire(import.meta.url);
const { resolveListenerPidWindows } = require('../../dev/lib/port-owner.cjs');
const { readProcessTable, normalizeCreationTime } = require('../../dev/lib/process-identity.cjs');
const {
  resolveAgentSpawnsRegisterDir,
  buildAgentSpawnRecord,
  writeAgentSpawnRecord,
  renewAgentSpawnLease,
  OWNERSHIP_MODES,
  DEFAULT_MAX_RECORD_AGE_MS,
} = require('../../dev/lib/agent-spawn-record.cjs');

const SINK_HOST = '127.0.0.1';
const SINK_PORT = 4318;
const SINK_SCRIPT = path.join(repoRoot, 'scripts', 'agent-analytics', 'otlp-sink.py');
const PYTHON = process.platform === 'win32' ? 'python' : 'python3';
// Absolute, main-checkout-rooted output dir — never the relative default,
// which would resolve against a worktree's ephemeral `tmp/` (see header).
const SINK_OUT_DIR = path.join(mainRepoRoot, 'tmp', 'agent-telemetry', 'otlp');
// Truncate-on-write launch log — only the MOST RECENT spawn attempt's stderr
// matters; a re-probe or the next session's cold start reads its tail.
const LAUNCH_LOG = path.join(mainRepoRoot, 'tmp', 'agent-telemetry', 'otlp-sink-launch.log');
// How long to wait after spawning before re-probing the port. A crash-on-import
// (the ModuleNotFoundError class this closes) fails near-instantly; 1.2s is
// generous headroom without eating meaningfully into the 5s hook timeout.
const LIVENESS_RECHECK_MS = 1200;
const STALE_DATA_MS = 7 * 24 * 60 * 60 * 1000;

// Tempdoc 861 W3 [A6] — the sink is a singleton (one per machine, one port), so ONE fixed record
// id is correct — never a pid- or port-suffixed one, which would leave a stale record behind every
// time the sink is restarted under a new pid. The lease duration matches the register's own
// default retention window: a session-start renewal every few hours/days keeps the lease "live"
// so [A10]'s pruning (age AND no-live-lease) never mistakes a quiet stretch between sessions for
// abandonment — though per the §6.3 matrix, `ownerless-singleton` is never reaped regardless.
const SINK_AGENT_SPAWN_RECORD_ID = 'otlp-sink';
const SINK_AGENT_SPAWN_LEASE_DURATION_SEC = Math.floor(DEFAULT_MAX_RECORD_AGE_MS / 1000);
// Safe as the third identity conjunct (with an exact creation-time match as the second): every
// invocation's command line contains the sink script's own basename.
const SINK_CMDLINE_FINGERPRINT = path.basename(SINK_SCRIPT);

/**
 * 861 W3 [A6] — register (or renew) the sink's `ownerless-singleton` agent-spawn record.
 *
 * Lease-on-use first: if a record already exists (the overwhelmingly common case — the sink
 * outlives every session, so most SessionStart hits find it already registered), renew ONLY its
 * lease and touch nothing else. A fresh record is built from the process table only when no
 * record exists yet to renew — e.g. the very first session after this feature ships, or after
 * [A10]'s pruning finally ages one out following a long gap.
 *
 * Best-effort and NEVER throws: registration bookkeeping must not turn a healthy sink into a
 * fail-loud session-start warning, and must never signal or restart the process it describes.
 *
 * @param {number} pid
 * @param {object} [deps] - injectable for tests; never disturbs the real sink either way.
 */
async function registerSinkSpawn(pid, { table = readProcessTable, dir = resolveAgentSpawnsRegisterDir(mainRepoRoot) } = {}) {
  try {
    const renewed = await renewAgentSpawnLease({
      dir,
      recordId: SINK_AGENT_SPAWN_RECORD_ID,
      durationSec: SINK_AGENT_SPAWN_LEASE_DURATION_SEC,
    });
    if (renewed.renewed) return;

    const snapshot = table();
    if (!snapshot.ok) return; // no evidence, no record — never guess (861 [A2])
    const row = snapshot.table.find((r) => Number(r?.ProcessId) === pid);
    if (!row) return;
    const creationFileTimeUtc = normalizeCreationTime(row.CreationFileTimeUtc);
    if (creationFileTimeUtc === null) return;
    const cmdline = typeof row.CommandLine === 'string' ? row.CommandLine : '';
    if (!cmdline.includes(SINK_CMDLINE_FINGERPRINT)) return; // refuse an unverified identity

    const record = await buildAgentSpawnRecord({
      recordId: SINK_AGENT_SPAWN_RECORD_ID,
      producer: 'otlp-sink',
      pid,
      creationFileTimeUtc,
      cmdlineFingerprint: SINK_CMDLINE_FINGERPRINT,
      port: SINK_PORT,
      leaseDurationSec: SINK_AGENT_SPAWN_LEASE_DURATION_SEC,
      ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON,
    });
    await writeAgentSpawnRecord({ dir, record });
  } catch {
    // Registration is best-effort — see the function docstring's failure policy.
  }
}

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

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Open the launch log for a fresh (truncated) write; falls back to 'ignore' on any FS error. */
function openLaunchLogForWrite() {
  try {
    fs.mkdirSync(path.dirname(LAUNCH_LOG), { recursive: true });
    return fs.openSync(LAUNCH_LOG, 'w');
  } catch {
    return 'ignore';
  }
}

/** Spawn the sink detached so it outlives this hook and the session; stderr → LAUNCH_LOG. */
function startSink() {
  const stderrTarget = openLaunchLogForWrite();
  const child = spawn(
    PYTHON,
    [SINK_SCRIPT, '--port', String(SINK_PORT), '--out', SINK_OUT_DIR],
    {
      cwd: repoRoot,
      detached: true,
      stdio: ['ignore', 'ignore', stderrTarget],
      windowsHide: true,
    }
  );
  if (typeof stderrTarget === 'number') {
    try { fs.closeSync(stderrTarget); } catch { /* child already holds its own handle */ }
  }
  // A spawn failure (e.g. PYTHON not on PATH) emits an ASYNC 'error' event — previously
  // harmless because the hook exited near-instantly after spawn(), often before Node even
  // delivered it. Now that main() awaits LIVENESS_RECHECK_MS, the event loop stays alive
  // long enough for that error to actually fire — an unhandled EventEmitter 'error' throws,
  // which would crash this process with an uncaught exception instead of the clean fail-loud
  // exit 2 below. The post-spawn re-probe (not this event) is the source of truth for
  // "did it come up", so swallow it here.
  child.on('error', () => {});
  child.unref();
  // 861 W3 [A6]: no shell wrapper here (`spawn(PYTHON, [...])`, no `shell: true`), so `child.pid`
  // IS the real listener's pid directly — none of `serve-worktree-fe.cjs`'s [A3] cmd.exe-shim
  // problem applies to this producer.
  return child.pid;
}

/** Last `n` non-empty lines of `filePath`, reading at most `maxBytes` from the tail. Never throws. */
export function tailFileLines(filePath, { n = 3, maxBytes = 4096 } = {}) {
  try {
    const stat = fs.statSync(filePath);
    if (stat.size === 0) return [];
    const start = Math.max(0, stat.size - maxBytes);
    const fd = fs.openSync(filePath, 'r');
    try {
      const buf = Buffer.alloc(stat.size - start);
      fs.readSync(fd, buf, 0, buf.length, start);
      return buf.toString('utf8').split(/\r?\n/).filter(Boolean).slice(-n);
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return [];
  }
}

/** Newest mtime (ms) of any file directly under `dir`, or null if the dir is missing/empty. */
export function newestFileMtimeMs(dir) {
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    let newest = null;
    for (const entry of entries) {
      if (!entry.isFile()) continue;
      const stat = fs.statSync(path.join(dir, entry.name));
      if (newest === null || stat.mtimeMs > newest) newest = stat.mtimeMs;
    }
    return newest;
  } catch {
    return null;
  }
}

/** Pure message builder for the sink-death warning (tempdoc 829 R8), exported for unit testing. */
export function buildDeathWarning({ python, sinkScript, tailLines }) {
  const tailSuffix = tailLines && tailLines.length ? ` Last stderr: ${tailLines.join(' | ')}` : '';
  return (
    `[otlp-sink-ensure] OTel sink FAILED to start — telemetry is NOT being captured this session. ` +
    `Run \`${python} ${sinkScript}\` manually to see the error (likely missing python deps after a ` +
    `scoop/python bump; try \`pip install opentelemetry-proto\`).${tailSuffix}`
  );
}

/** Pure message builder for the stale-data notice, or null if not stale. Exported for unit testing. */
export function buildStalenessNotice({ newestMtimeMs, nowMs, outDir, staleMs }) {
  if (newestMtimeMs == null) return null; // no data yet — freshly empty, not "stale"
  const ageMs = nowMs - newestMtimeMs;
  if (ageMs < staleMs) return null;
  const ageDays = Math.floor(ageMs / (24 * 60 * 60 * 1000));
  return (
    `[otlp-sink-ensure] Something is listening on :${SINK_PORT} but the newest file under ${outDir} ` +
    `is ${ageDays}d old — possible wrong-port listener or a stalled sink. Verify with ` +
    `\`curl -sf http://${SINK_HOST}:${SINK_PORT}\` and check the directory directly.`
  );
}

async function main() {
  if (await isSinkListening()) {
    // Already up (this or a concurrent session) — no [re]spawn needed. Still worth a
    // (softer) notice if the data it's producing looks stale.
    // 861 W3 [A6]: this session did not spawn it, so its pid is resolved from the port —
    // read-only, never disturbs the running sink.
    const owner = resolveListenerPidWindows(SINK_PORT);
    if (owner.ok) await registerSinkSpawn(owner.pid);
    const notice = buildStalenessNotice({
      newestMtimeMs: newestFileMtimeMs(SINK_OUT_DIR),
      nowMs: Date.now(),
      outDir: SINK_OUT_DIR,
      staleMs: STALE_DATA_MS,
    });
    if (notice) {
      process.stderr.write(notice + '\n');
      process.exitCode = 2; // asyncRewake: shows this stderr to the agent as a system reminder
    }
    return;
  }

  const spawnedPid = startSink();
  await delay(LIVENESS_RECHECK_MS);
  if (await isSinkListening()) {
    // 861 W3 [A6]: `spawnedPid` IS the real listener (no shell shim for this producer) — register
    // directly, no port-owner resolution needed.
    if (spawnedPid) await registerSinkSpawn(spawnedPid);
    return; // came up cleanly — silent, exit 0
  }

  const warning = buildDeathWarning({
    python: PYTHON,
    sinkScript: SINK_SCRIPT,
    tailLines: tailFileLines(LAUNCH_LOG),
  });
  process.stderr.write(warning + '\n');
  process.exitCode = 2; // asyncRewake: shows this stderr to the agent as a system reminder
}

runHook(import.meta.url, main);

export {
  isSinkListening, SINK_PORT, SINK_SCRIPT, SINK_OUT_DIR, LAUNCH_LOG, STALE_DATA_MS,
  LIVENESS_RECHECK_MS, mainRepoRoot,
  registerSinkSpawn, SINK_AGENT_SPAWN_RECORD_ID, SINK_AGENT_SPAWN_LEASE_DURATION_SEC,
  SINK_CMDLINE_FINGERPRINT,
};
