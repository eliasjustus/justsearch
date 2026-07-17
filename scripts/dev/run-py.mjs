#!/usr/bin/env node
/**
 * Python runner with UTF-8-safe I/O (tempdoc 743 second wave, P-K exec substrate).
 *
 * Redirected/piped stdout on Windows falls back to the console codepage (cp1252 here)
 * instead of UTF-8 — Python's own `PEP 528`/`bpo-27179` gap: Python 3 defaults console I/O
 * to UTF-8 only when it detects an actual interactive console, and silently falls back to
 * the legacy ANSI codepage the moment stdout is redirected or piped. Verified live on this
 * box (tempdoc 743 R4 derisk): `python -c "print('ä')" | cat` mangles the umlaut into a
 * replacement byte; the same command with `PYTHONIOENCODING=utf-8` set on the child does not.
 *
 * `PYTHONIOENCODING` is scoped to THIS child process only, never exported machine-wide —
 * that follows the Python docs' own recommendation (the env var is meant to be set
 * per-invocation, not globally) and avoids leaking an encoding override into every other
 * process on the machine (unrelated tools that expect the default codepage behavior).
 * `PYTHONUTF8=1` is included as a belt-and-suspenders companion (forces UTF-8 mode
 * generally, not just for I/O encoding); both are merged over `process.env`, so nothing
 * else in the child's environment is disturbed.
 *
 * Usage: `node scripts/dev/run-py.mjs [-c code | script.py] [args...]` — every argument is
 * forwarded to `python` as an argument VECTOR (no shell interpolation, so inline code
 * containing quotes/backslashes/non-ASCII never needs hand-escaping for a shell). Exits with
 * the child's real exit code.
 */

import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

/** Resolve the python binary: explicit override, else plain `python` on PATH. */
export function resolvePyBin(env = process.env) {
  return env.JUSTSEARCH_PY_BIN || 'python';
}

/** Build the child env: process.env with UTF-8 I/O forced, scoped to this call only. */
export function buildUtf8Env(baseEnv = process.env) {
  return { ...baseEnv, PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1' };
}

function main() {
  const argv = process.argv.slice(2);
  const bin = resolvePyBin();
  const result = spawnSync(bin, argv, { stdio: 'inherit', env: buildUtf8Env() });
  if (result.error) {
    process.stderr.write(`run-py: failed to spawn \`${bin}\`: ${result.error.message}\n`);
    process.exit(1);
  }
  process.exit(result.status ?? 1);
}

function isDirectRun() {
  return !!process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
}

if (isDirectRun()) {
  main();
}
