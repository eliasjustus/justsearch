#!/usr/bin/env node

/**
 * PreToolUse hook on Bash — tempdoc 618 §10a (pipe-masked exit).
 *
 * A command whose exit code is load-bearing (a build/test run) piped into
 * `tail`/`grep`/`head` reports the *pipe's* exit code, not its own: the shell
 * surfaces only the last pipeline stage's status. So `./gradlew build | tail -25`
 * can report exit 0 while the build FAILED — one step from fast-forwarding `main`
 * on a red build (the exact failure recurred in the 2026-07-01 session *despite*
 * §10a already living in `agent-lessons.md`, which is what motivated moving it
 * from an always-loaded prose bullet to a moment-of-relevance delivery hook:
 * "residence is not delivery" — 618 settlement / 620 Part V hook-hint tier).
 *
 * This hook fires at the moment the masking command is issued and emits a
 * non-blocking reminder to run the command bare, add `set -o pipefail`, or assert
 * on the output text. It CANNOT live inside `bash-guard`: that hook is
 * block-or-silent (no advisory channel) and structurally short-circuits on any
 * pipe (`bash-guard.mjs:217`), so it can neither emit an advisory nor even see a
 * piped command. Hence a separate advisory hook — same shape as
 * `docs-granularity-hint.mjs`.
 *
 * Precision (see pipe-mask-hint.test.mjs corpus): it fires ONLY when the LAST
 * pipeline stage is a masking filter AND an earlier stage's leading executable is
 * a build/test command, and NOT when the author genuinely preserved the exit
 * (`set -o pipefail`, `${PIPESTATUS[...]}` — the only two that recover a
 * first-stage exit through a pipe). It deliberately DOES fire on `… | tail; echo $?`
 * and `… | tail && echo done`: after a pipe, `$?` and `&&` both key off the pipe's
 * last stage (tail's exit), NOT the build's — so those are masked, and telling the
 * agent so is the point (this exact `| tail; echo $?` shape caused the §10a
 * recurrence). Legitimate `log | tail` / `… | grep` reads stay un-hinted because
 * their leading stage isn't a build/test command.
 *
 * Advisory: never blocks, fail-open on any error, honors `JUSTSEARCH_DISABLE_HOOKS=1`.
 * Delivers the rule `piped-exit-masked` (tier-register row 37) at its moment of relevance.
 */

import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';

/** Split a command into pipeline stages on a single `|` — never on `||` (OR), trimmed. */
export function pipelineStages(cmd) {
  return String(cmd || '')
    .split(/(?<!\|)\|(?!\|)/)
    .map((s) => s.trim());
}

/** Drop leading `VAR=val ` env prefixes and `2>&1`-style redirects so the leading executable is visible. */
function leadingExecutable(stage) {
  return String(stage || '')
    .replace(/^(?:\w+=\S+\s+)+/, '')
    .replace(/\s*\d?>&?\S+/g, '')
    .trim();
}

/** Masking sinks: filters that replace the upstream command's exit code with their own. */
const SINK_LEAD = /^(?:tail|head|grep|rg)\b/i;

/** Leading-executable patterns for commands whose exit code is load-bearing (a red-vs-green signal). */
const BUILD_LEAD = [
  /^(?:\.?\/)?gradlew(?:\.bat)?\s+.*\b(?:build|test|check|verify|assemble|installDist)\b/i,
  /^gradle\s+.*\b(?:build|test|check|verify|assemble)\b/i,
  /^npm\s+(?:run\s+)?(?:test|build|typecheck|check)\b/i,
  /^(?:npx\s+)?vitest\b/i,
  /^(?:python\s+-m\s+)?pytest\b/i,
  /^cargo\s+(?:test|build)\b/i,
  /^go\s+test\b/i,
  /^mvn\s+(?:test|verify|install|package)\b/i,
  /^ctest\b/i,
  /^jseval\s+(?:run|build)\b/i,
];

/**
 * Author genuinely preserved the first-stage exit → do NOT hint. Only `pipefail`
 * and `PIPESTATUS` recover a build's exit through a pipe; `$?`/`&&` after a pipe
 * read the pipe's last-stage exit (the masked value), so they are NOT preservers.
 */
const PRESERVES_EXIT = /set\s+-o\s+pipefail|PIPESTATUS/i;

/**
 * True when a build/test command's exit is masked by a trailing pipe into a
 * filter, and the author hasn't preserved the exit. Pure; unit-tested.
 */
export function detectPipeMaskedExit(cmd) {
  if (!cmd) return false;
  if (PRESERVES_EXIT.test(cmd)) return false;
  const stages = pipelineStages(cmd);
  if (stages.length < 2) return false; // no pipe → nothing to mask
  const last = leadingExecutable(stages[stages.length - 1]);
  if (!SINK_LEAD.test(last)) return false; // last stage isn't a masking filter
  for (let i = 0; i < stages.length - 1; i += 1) {
    const lead = leadingExecutable(stages[i]);
    if (BUILD_LEAD.some((re) => re.test(lead))) return true;
  }
  return false;
}

export const HINT = [
  'Exit-masking (tempdoc 618 §10a): this pipes a build/test command into',
  '`tail`/`grep`/`head`, so the shell reports the FILTER’s exit code, not the',
  'build’s — a FAILED build can look like exit 0 (one step from fast-forwarding',
  '`main` on red). If the exit matters, either:',
  '  - run the command bare and read `BUILD SUCCESSFUL`/`BUILD FAILED` in output, or',
  '  - `set -o pipefail` before it, or assert on the output text.',
  'The harness surfaces only the last pipe stage’s exit code.',
].join('\n');

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  if (!detectPipeMaskedExit(input.tool_input?.command)) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: HINT },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
