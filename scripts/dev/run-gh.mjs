#!/usr/bin/env node
/**
 * `gh` CLI runner (tempdoc 743 second wave, P-K exec substrate).
 *
 * Two jobs:
 *
 *  1. **Resolved-path invocation.** Scoop shim junctions have been observed unreachable
 *     from an agent Bash session (symptom: `Shim: Could not create process …`), which is
 *     the birthplace of the hand-typed `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe"` quoting
 *     class (agent-lessons.md). This wrapper resolves the scoop-installed binary itself —
 *     via the `current` symlink so it survives a `gh` version bump — and falls back to
 *     plain `gh` on PATH for portability to a machine without this scoop layout. Args are
 *     passed as a vector (`spawnSync(bin, argv, ...)`), never shell-interpolated, so no
 *     quoting trap exists on either path.
 *
 *  2. **`checks-wait` mode** — mechanizes the prose guidance tempdoc 746 shipped
 *     (`.claude/skills/publish/SKILL.md` "Registration race" bullet) as a runnable command
 *     instead of a hand-rolled poll loop:
 *
 *     `node scripts/dev/run-gh.mjs checks-wait <pr-number> [--timeout-sec N]`
 *
 *     `gh pr checks <pr>` exit-code contract (cli/cli#7866, verified live on gh 2.90.0 —
 *     tempdoc 743 R3 derisk): the exit code is a BITWISE combination —
 *       - bit 0 (1) set  → at least one check is FAILING
 *       - bit 3 (8) set  → at least one check is PENDING (no failures yet)
 *       - 0              → every check reported and none are failing/pending (PASS)
 *     A fail bit is terminal (a failed check does not un-fail while polling), so any code
 *     with bit 0 set is treated as FAIL even if bit 3 is also set.
 *
 *     The overload that causes cli/cli#7401's spurious failure: right after a push, before
 *     CI has registered any checks, `gh pr checks <pr>` ALSO exits 1 with a distinct
 *     "no checks reported" message — indistinguishable from a real failure by exit code
 *     alone. This wrapper pre-polls (every 15s) until the output stops looking like the
 *     no-checks-yet case before it starts trusting the bitwise contract, which is the fix
 *     #7401 itself never shipped.
 *
 *     Exit codes of `checks-wait` itself: 0 = all checks passed, 1 = a check failed,
 *     3 = TIMEOUT (bounded by --timeout-sec, default 1800), 2 = an unexpected `gh` error
 *     (e.g. auth failure) surfaced verbatim on stderr.
 */

import path from 'node:path';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const POLL_INTERVAL_MS = 15_000;
const DEFAULT_TIMEOUT_SEC = 1800;

/** Resolve the `gh` binary: scoop's `current` symlink if present, else plain `gh` on PATH. */
export function resolveGhBin(env = process.env) {
  if (env.JUSTSEARCH_GH_BIN) return env.JUSTSEARCH_GH_BIN;
  if (process.platform === 'win32') {
    const scoopRoot = env.SCOOP || 'F:\\scoop';
    const candidate = path.join(scoopRoot, 'apps', 'gh', 'current', 'bin', 'gh.exe');
    try {
      if (fs.existsSync(candidate)) return candidate;
    } catch {
      /* fall through to PATH */
    }
  }
  return 'gh';
}

/** Run `gh` with an argument vector (no shell interpolation), stdio inherited. */
function runGh(bin, args) {
  return spawnSync(bin, args, { stdio: 'inherit' });
}

/** Run `gh` capturing output (for checks-wait's internal polling, not user-facing stdio). */
function runGhCaptured(bin, args) {
  return spawnSync(bin, args, { encoding: 'utf8' });
}

/**
 * True when a `gh pr checks` result looks like "no checks have registered yet" rather than
 * a real failure or a real pass/pending state — the cli/cli#7401 ambiguity. Pure; unit-tested.
 */
export function isUnregistered(result) {
  const text = `${result.stdout || ''}${result.stderr || ''}`;
  if (result.status === 1 && /no checks reported/i.test(text)) return true;
  // A completely empty result with a non-zero/non-standard status also reads as "not up yet"
  // rather than a decodable bitwise verdict.
  if (!text.trim() && result.status !== 0) return true;
  return false;
}

/**
 * Decode the cli/cli#7866 bitwise exit contract into a verdict. Pure; unit-tested.
 * Returns one of: 'pass' | 'fail' | 'pending' | 'unknown'.
 */
export function decodeChecksExit(status) {
  if (status === 0) return 'pass';
  if (typeof status !== 'number') return 'unknown';
  if ((status & 1) !== 0) return 'fail'; // fail bit wins even if pending bit also set
  if ((status & 8) !== 0) return 'pending';
  return 'unknown';
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function checksWait(bin, prNumber, timeoutSec) {
  const deadline = Date.now() + timeoutSec * 1000;
  const checksArgs = ['pr', 'checks', String(prNumber)];

  // Phase 1: pre-poll until checks register (cli/cli#7401 mitigation).
  let last = runGhCaptured(bin, checksArgs);
  if (last.error) {
    // Spawn failure (ENOENT etc.) must fail fast and legibly — never poll a binary that
    // isn't there until timeout and then report a wrong-cause TIMEOUT (refute-first review
    // finding 2a, 743 second wave).
    process.stderr.write(`run-gh checks-wait: failed to spawn \`${bin}\`: ${last.error.message}\n`);
    return 2;
  }
  while (isUnregistered(last)) {
    if (Date.now() >= deadline) {
      process.stderr.write(
        `run-gh checks-wait: TIMEOUT waiting for PR #${prNumber} checks to register after ${timeoutSec}s\n`,
      );
      return 3;
    }
    await sleep(POLL_INTERVAL_MS);
    last = runGhCaptured(bin, checksArgs);
    if (last.error) {
      process.stderr.write(`run-gh checks-wait: failed to spawn \`${bin}\`: ${last.error.message}\n`);
      return 2;
    }
  }

  // Phase 2: trust the bitwise contract until it resolves to pass/fail.
  for (;;) {
    const verdict = decodeChecksExit(last.status);
    if (verdict === 'pass') {
      process.stderr.write(`run-gh checks-wait: PASS — PR #${prNumber} all checks green\n`);
      return 0;
    }
    if (verdict === 'fail') {
      process.stderr.write(`run-gh checks-wait: FAIL — PR #${prNumber} has a failing check\n`);
      return 1;
    }
    if (verdict === 'pending') {
      if (Date.now() >= deadline) {
        process.stderr.write(
          `run-gh checks-wait: TIMEOUT — PR #${prNumber} still pending after ${timeoutSec}s\n`,
        );
        return 3;
      }
      await sleep(POLL_INTERVAL_MS);
      last = runGhCaptured(bin, checksArgs);
      continue;
    }
    // 'unknown': an unexpected gh error (auth, network, ...) — surface verbatim, don't loop forever.
    process.stderr.write(
      `run-gh checks-wait: unexpected \`gh pr checks\` result (exit ${last.status}) — surfacing verbatim:\n${last.stdout || ''}${last.stderr || ''}\n`,
    );
    return 2;
  }
}

function parseTimeoutSec(args) {
  const i = args.indexOf('--timeout-sec');
  if (i === -1) return { timeoutSec: DEFAULT_TIMEOUT_SEC, rest: args };
  const value = Number(args[i + 1]);
  const rest = [...args.slice(0, i), ...args.slice(i + 2)];
  return { timeoutSec: Number.isFinite(value) && value > 0 ? value : DEFAULT_TIMEOUT_SEC, rest };
}

async function main() {
  const argv = process.argv.slice(2);
  const bin = resolveGhBin();

  if (argv[0] === 'checks-wait') {
    const { timeoutSec, rest } = parseTimeoutSec(argv.slice(1));
    const prNumber = rest[0];
    if (!prNumber) {
      process.stderr.write('run-gh checks-wait: missing <pr-number>\n');
      process.exit(2);
    }
    const code = await checksWait(bin, prNumber, timeoutSec);
    process.exit(code);
  }

  const result = runGh(bin, argv);
  if (result.error) {
    // Mirror run-py.mjs's spawn-error guard: with stdio 'inherit', ENOENT is otherwise a
    // silent exit 1 with zero output (refute-first review finding 2b, 743 second wave).
    process.stderr.write(`run-gh: failed to spawn \`${bin}\`: ${result.error.message}\n`);
    process.exit(127);
  }
  process.exit(result.status ?? 1);
}

function isDirectRun() {
  return !!process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
}

if (isDirectRun()) {
  main();
}
