#!/usr/bin/env node

/**
 * PreToolUse hook on Bash — redirect onto the P-K exec substrate (tempdoc 743 second wave).
 *
 * `scripts/dev/run-gh.mjs` and `scripts/dev/run-py.mjs` fix two deterministic platform defects
 * (agent-lessons.md, tempdoc 743 R3/R4 derisk): scoop shim junctions have been observed
 * unreachable from an agent Bash session — the birthplace of the hand-typed
 * `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe"` quoting class — and redirected/piped Python stdout
 * on Windows silently falls back to cp1252, mangling non-ASCII output. Both wrappers exist;
 * a wrapper nobody is redirected to is a suggestion, not a paved path (mirrors bash-guard's
 * cat→Read and dataset-cache-hint's corpus-fetch redirect — the established pattern for this
 * repo, per `.claude/rules/tier-register.md` row 2).
 *
 * Three trigger classes (see `classifyExecSubstrate` + exec-substrate-hint.test.mjs's precision
 * corpus):
 *   - `call-operator`: a PowerShell call-operator (`& "..."` / `& '...'`) pasted at a command
 *     position inside a Bash command — the classic quoted-scoop-path paste error.
 *   - `wait-shaped`: `gh pr checks` or `gh run watch` used in a shape that waits (piped to
 *     tail/grep/head, inside a while loop, with `--watch`, or backgrounded) — the hand-rolled
 *     poll loop `run-gh checks-wait` mechanizes (cli/cli#7401 pre-poll + cli/cli#7866 bitwise
 *     exit decode).
 *   - `python-risk`: an inline `python -c "..."` whose code contains non-ASCII text or a
 *     Windows-path-shaped backslash sequence, AND whose output is piped/redirected — the shape
 *     that hits the cp1252 fallback or a shell-quoting backslash trap.
 * Deliberately narrow on all three: a plain `gh pr view`, a plain `python -c "print(1)"`, or an
 * unrelated backgrounded dev command (`npm run watch &`) must stay silent — see the test
 * corpus's must-NOT-fire half.
 *
 * De-duped ONCE per class per session (mirrors consult-doc-hint.mjs): the redirect doesn't
 * change between the 1st and Nth matching command in a session, so re-pushing it is waste.
 *
 * Advisory: never blocks, fail-open on any error, honors `JUSTSEARCH_DISABLE_HOOKS=1`.
 */

import fs from 'node:fs';
import path from 'node:path';
import { readJsonStdin, hooksDisabled, isDirectRun, repoRoot } from '../lib/hook-base.mjs';

/** A PowerShell call-operator at a command position: start-of-string, or after `;`/`&`/`|`. */
const CALL_OPERATOR = /(^|[;&|]\s*)&\s*["']/;

/** `gh run watch`, requiring an explicit `gh`/`$GH*`-shaped prefix so `npm run watch` never matches. */
const GH_RUN_WATCH = /\b(?:gh|\$\{?[A-Za-z_]*GH[A-Za-z_]*\}?)\s+run\s+watch\b/i;

/** `pr checks` subcommand — distinctive enough on its own not to need a literal `gh` prefix
 *  (covers `"$GH" pr checks ...` where the binary is a variable). */
const PR_CHECKS = /\bpr\s+checks\b/i;

/** A shape that makes a `pr checks` call a WAIT rather than a one-shot status read. */
const WAIT_SHAPE = /\|\s*(?:tail|grep|head)\b|\bwhile\b|--watch\b|(?<!&)&\s*$/i;

/** Already routed through the paved path — never re-hint on the wrapper's own invocation. */
const IS_WRAPPER_INVOCATION = /\brun-(?:gh|py)\.mjs\b/;

/** Extract a `python(3)? -c "<code>"` (or `'...'`) argument. Returns { code, after } or null. */
function extractPyDashC(cmd) {
  const m = /(?:^|[\s;&|(])python3?\s+-c\s+(["'])([\s\S]*?)\1/i.exec(cmd);
  if (!m) return null;
  return { code: m[2], after: cmd.slice(m.index + m[0].length) };
}

const NON_ASCII = /[^\x00-\x7F]/;
/** A Windows-path-shaped backslash sequence inside inline code — the shell-quoting trap class. */
const BACKSLASH_PATH_RISK = /[A-Za-z]:\\|\\\\/;
const PIPED_OR_REDIRECTED = /[|>]/;

/**
 * Classify a Bash command: `'call-operator'` | `'wait-shaped'` | `'python-risk'` | `null`.
 * Pure; unit-tested.
 */
export function classifyExecSubstrate(cmd) {
  const c = String(cmd || '');
  if (!c.trim()) return null;
  if (IS_WRAPPER_INVOCATION.test(c)) return null; // already on the paved path

  if (CALL_OPERATOR.test(c)) return 'call-operator';

  if (GH_RUN_WATCH.test(c)) return 'wait-shaped';
  if (PR_CHECKS.test(c) && WAIT_SHAPE.test(c)) return 'wait-shaped';

  const py = extractPyDashC(c);
  if (py) {
    const risky = NON_ASCII.test(py.code) || BACKSLASH_PATH_RISK.test(py.code);
    if (risky && PIPED_OR_REDIRECTED.test(py.after)) return 'python-risk';
  }

  return null;
}

const HINTS = {
  'call-operator': [
    'This looks like a PowerShell call-operator paste (`& "..."`) — the classic hand-typed',
    'quoted-scoop-path error. `node scripts/dev/run-gh.mjs <gh args...>` resolves the gh binary',
    'itself (scoop `current` symlink, falling back to `gh` on PATH) and passes args as a vector,',
    'so no quoting is needed on either side (tempdoc 743 P-K).',
  ].join('\n'),
  'wait-shaped': [
    'This looks like a hand-rolled wait on `gh pr checks`/`gh run watch`. Prefer',
    '`node scripts/dev/run-gh.mjs checks-wait <pr-number>`: it pre-polls until checks register',
    '(cli/cli#7401) then decodes the documented 0=pass/1=fail/8=pending bitwise exit contract',
    '(cli/cli#7866) instead of a hand-rolled poll loop (tempdoc 743 P-K).',
  ].join('\n'),
  'python-risk': [
    'Inline Python with non-ASCII/backslash-path content and piped or redirected output can hit',
    "Windows' cp1252 stdout fallback (verified: bare piped `python -c \"print('ä')\"` mangles).",
    'Prefer `node scripts/dev/run-py.mjs -c "..."`: it scopes `PYTHONIOENCODING=utf-8` +',
    '`PYTHONUTF8=1` to the child only and forwards args as a vector (tempdoc 743 P-K).',
  ].join('\n'),
};

function markerPath(sessionId) {
  return path.join(repoRoot, 'tmp', 'agent-telemetry', `exec-substrate-nudged-${sessionId || 'unknown'}.json`);
}

function alreadyNudged(sessionId, kind) {
  try {
    const seen = JSON.parse(fs.readFileSync(markerPath(sessionId), 'utf8'));
    return Array.isArray(seen) && seen.includes(kind);
  } catch {
    return false;
  }
}

function recordNudged(sessionId, kind) {
  try {
    const file = markerPath(sessionId);
    let seen = [];
    try {
      seen = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      /* none recorded yet */
    }
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify([...new Set([...(Array.isArray(seen) ? seen : []), kind])]));
  } catch {
    /* best-effort — a missed write just means this class re-hints next time, not silence */
  }
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  const kind = classifyExecSubstrate(input.tool_input?.command);
  if (!kind) return;

  const sessionId = input.session_id;
  if (alreadyNudged(sessionId, kind)) return;
  recordNudged(sessionId, kind);

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        additionalContext: HINTS[kind],
      },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
