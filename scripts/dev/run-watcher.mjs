#!/usr/bin/env node
/**
 * Watcher/heartbeat helper for long-running commands (tempdoc 743 second wave, P-M(b)).
 *
 * ADAPTER-CLASS, 90-DAY ARTIFACT — retirement condition (tempdoc 743 §P-M(b)/reach-judgment
 * principle 3): delete this once the platform restores background/Monitor tasks across
 * `--resume` and emits failure events for them (anthropics/claude-code#75438 is the open
 * upstream issue this stands in for). Until then, a background watcher can die silently with
 * no signal distinguishing "still working" from "dead" — this file makes that silence legible
 * (tempdoc 743 §Deficit 2 direction D2-a: "make silence legible instead of making watchers
 * perfect") instead of building a perfectly reliable watcher, which is unattainable from
 * userspace. One reusable helper replaces per-session hand-rolled watcher scripts so a coarse
 * wakeup tick (a 20-30 min campaign check-in) can branch on a definite verdict without
 * re-deriving "is this run alive" logic each time.
 *
 * Two modes, one state directory:
 *
 *   `node run-watcher.mjs run --dir <stateDir> [--marker-on-exit <name>] -- <command...>`
 *     Spawns <command...> as an argument vector (never shell-interpolated). Child stdout+stderr
 *     are redirected into `<stateDir>/watched.log` (append mode); child stdin is ignored. While
 *     the child runs, `<stateDir>/heartbeat` is (re)written every 10s (override via
 *     `JUSTSEARCH_WATCHER_HEARTBEAT_MS`, intended for tests only — production default is 10s
 *     exactly as designed) and NDJSON events are appended to `<stateDir>/events.ndjson`
 *     (`{ts, event: "start"|"heartbeat-note"|"exit", code}`). On child exit, writes
 *     `<stateDir>/verdict.json` (`{code, endedAt, durationSec}`) and, if `--marker-on-exit
 *     <name>` was given, touches an empty `<stateDir>/<name>` marker file (the overnight-chain
 *     "a file appearing means done" convention). This process's own exit code mirrors the
 *     child's.
 *
 *   `node run-watcher.mjs check --dir <stateDir> [--stale-sec 60]`
 *     Prints exactly one line and exits with a code a wakeup tick can branch on without parsing:
 *       DONE-OK                                                       exit 0
 *       DONE-FAILED(<code>)                                           exit 1
 *       PROGRESSING(heartbeat <N>s ago)                               exit 0
 *       STALLED-OR-DEAD(heartbeat <N>s ago, exceeds stale threshold)  exit 2
 *       NO-RUN(no state dir or no heartbeat ever)                     exit 3
 *     A finished run (verdict.json present) always reports DONE-OK/DONE-FAILED regardless of
 *     heartbeat age — a stale heartbeat on a completed run is expected, not a fault.
 *
 * Not a task scheduler, not a process supervisor, not a log tailer — deliberately narrow: one
 * child, one state directory, one verdict line. Coordinate with tempdoc 750 (release-loop
 * scheduling/diagnostics) before either side grows a second watcher (743 R7 derisk).
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const DEFAULT_STALE_SEC = 60;
const DEFAULT_HEARTBEAT_MS = 10000;

/** Parse argv for `run` mode (everything after the `run` token). Throws on malformed input. */
export function parseRunArgs(argv) {
  const dashIdx = argv.indexOf('--');
  if (dashIdx === -1) {
    throw new Error('run: missing "--" separator before the command vector');
  }
  const flags = argv.slice(0, dashIdx);
  const command = argv.slice(dashIdx + 1);
  if (command.length === 0) {
    throw new Error('run: no command given after "--"');
  }
  let dir = null;
  let markerOnExit = null;
  for (let i = 0; i < flags.length; i++) {
    if (flags[i] === '--dir') {
      dir = flags[++i];
    } else if (flags[i] === '--marker-on-exit') {
      markerOnExit = flags[++i];
    } else {
      throw new Error(`run: unknown flag "${flags[i]}"`);
    }
  }
  if (!dir) {
    throw new Error('run: --dir is required');
  }
  return { dir, markerOnExit, command };
}

/** Parse argv for `check` mode (everything after the `check` token). Throws on malformed input. */
export function parseCheckArgs(argv) {
  let dir = null;
  let staleSec = DEFAULT_STALE_SEC;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--dir') {
      dir = argv[++i];
    } else if (argv[i] === '--stale-sec') {
      staleSec = Number(argv[++i]);
      if (!Number.isFinite(staleSec) || staleSec < 0) {
        throw new Error(`check: invalid --stale-sec "${argv[i]}"`);
      }
    } else {
      throw new Error(`check: unknown flag "${argv[i]}"`);
    }
  }
  if (!dir) {
    throw new Error('check: --dir is required');
  }
  return { dir, staleSec };
}

/**
 * Compute the verdict for a state directory as of `now`. Pure function of filesystem state —
 * no process spawning, no mutation — so it is directly unit-testable against a hand-built
 * directory (including a synthetically backdated heartbeat mtime).
 */
export function computeVerdict({ dir, staleSec = DEFAULT_STALE_SEC, now = Date.now() }) {
  if (!fs.existsSync(dir)) {
    return { status: 'NO-RUN', message: 'NO-RUN(no state dir or no heartbeat ever)', exitCode: 3 };
  }
  const heartbeatPath = path.join(dir, 'heartbeat');
  if (!fs.existsSync(heartbeatPath)) {
    return { status: 'NO-RUN', message: 'NO-RUN(no state dir or no heartbeat ever)', exitCode: 3 };
  }

  const verdictPath = path.join(dir, 'verdict.json');
  if (fs.existsSync(verdictPath)) {
    const verdict = JSON.parse(fs.readFileSync(verdictPath, 'utf8'));
    if (verdict.code === 0) {
      return { status: 'DONE-OK', message: 'DONE-OK', exitCode: 0 };
    }
    return { status: 'DONE-FAILED', message: `DONE-FAILED(${verdict.code})`, exitCode: 1 };
  }

  const ageSec = Math.max(0, Math.round((now - fs.statSync(heartbeatPath).mtimeMs) / 1000));
  if (ageSec <= staleSec) {
    return { status: 'PROGRESSING', message: `PROGRESSING(heartbeat ${ageSec}s ago)`, exitCode: 0 };
  }
  return {
    status: 'STALLED-OR-DEAD',
    message: `STALLED-OR-DEAD(heartbeat ${ageSec}s ago, exceeds stale threshold)`,
    exitCode: 2,
  };
}

function nowIso() {
  return new Date().toISOString();
}

function touchHeartbeat(heartbeatPath) {
  fs.writeFileSync(heartbeatPath, nowIso());
}

function appendEvent(eventsPath, event) {
  fs.appendFileSync(eventsPath, `${JSON.stringify({ ts: nowIso(), code: null, ...event })}\n`);
}

/** `run` mode: spawn the command, maintain the heartbeat/events/verdict state, exit with the
 * child's own exit code. Side-effecting (spawns a process, writes files, calls process.exit). */
export function runMode(argv) {
  const { dir, markerOnExit, command } = parseRunArgs(argv);
  fs.mkdirSync(dir, { recursive: true });

  const heartbeatPath = path.join(dir, 'heartbeat');
  const eventsPath = path.join(dir, 'events.ndjson');
  const logPath = path.join(dir, 'watched.log');
  const logFd = fs.openSync(logPath, 'a');

  const heartbeatMs = Number(process.env.JUSTSEARCH_WATCHER_HEARTBEAT_MS) || DEFAULT_HEARTBEAT_MS;
  const startMs = Date.now();

  touchHeartbeat(heartbeatPath);
  appendEvent(eventsPath, { event: 'start' });

  const [cmd, ...cmdArgs] = command;
  const child = spawn(cmd, cmdArgs, { stdio: ['ignore', logFd, logFd] });

  const timer = setInterval(() => {
    touchHeartbeat(heartbeatPath);
    appendEvent(eventsPath, { event: 'heartbeat-note' });
  }, heartbeatMs);

  let finished = false;
  const finish = (exitCode) => {
    if (finished) return;
    finished = true;
    clearInterval(timer);
    try {
      fs.closeSync(logFd);
    } catch {
      // already closed
    }
    appendEvent(eventsPath, { event: 'exit', code: exitCode });
    const verdict = {
      code: exitCode,
      endedAt: nowIso(),
      durationSec: Math.round(((Date.now() - startMs) / 1000) * 1000) / 1000,
    };
    fs.writeFileSync(path.join(dir, 'verdict.json'), JSON.stringify(verdict, null, 2));
    if (markerOnExit) {
      fs.writeFileSync(path.join(dir, markerOnExit), '');
    }
    process.exit(exitCode);
  };

  child.on('error', (err) => {
    process.stderr.write(`run-watcher: failed to spawn "${cmd}": ${err.message}\n`);
    finish(1);
  });
  child.on('exit', (code, signal) => {
    finish(code !== null ? code : signal ? 1 : 1);
  });
}

/** `check` mode: print the verdict line and exit with its code. */
export function checkMode(argv) {
  const { dir, staleSec } = parseCheckArgs(argv);
  const verdict = computeVerdict({ dir, staleSec, now: Date.now() });
  process.stdout.write(`${verdict.message}\n`);
  process.exit(verdict.exitCode);
}

function usage() {
  return [
    'usage: run-watcher.mjs run --dir <stateDir> [--marker-on-exit <name>] -- <command...>',
    '       run-watcher.mjs check --dir <stateDir> [--stale-sec <n>]',
  ].join('\n');
}

function main() {
  const [mode, ...rest] = process.argv.slice(2);
  try {
    if (mode === 'run') {
      runMode(rest);
      return;
    }
    if (mode === 'check') {
      checkMode(rest);
      return;
    }
  } catch (e) {
    process.stderr.write(`run-watcher: ${e.message}\n${usage()}\n`);
    process.exit(64);
  }
  process.stderr.write(`${usage()}\n`);
  process.exit(64);
}

function isDirectRun() {
  return !!process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
}

if (isDirectRun()) {
  main();
}
