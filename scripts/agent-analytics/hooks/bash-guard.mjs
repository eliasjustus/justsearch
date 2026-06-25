#!/usr/bin/env node

/**
 * Synchronous PreToolUse hook (matcher: "Bash").
 *
 * Two layers of protection:
 *   Layer 1 — Git safety: blocks destructive git operations.
 *             Some commands are blocked everywhere (force-push).
 *             Others are blocked only in the main worktree (checkout, reset --hard, clean).
 *   Layer 2 — Tool hygiene: blocks bare file-reading commands that should use dedicated tools.
 *
 * Main worktree detection: .git is a real directory in the main checkout,
 * but a file (gitdir pointer) in worktrees. This is a fast, no-subprocess check.
 *
 * Exit codes:
 *   0 = allow (no output)
 *   2 = block (stderr message shown to Claude as feedback)
 */

import { statSync } from 'node:fs';
import { join } from 'node:path';
import { readJsonStdin, runHook } from '../lib/hook-base.mjs';

// --- Git safety patterns ---

/** Blocked everywhere, regardless of worktree. */
const DESTRUCTIVE_EVERYWHERE = [
  {
    regex: /\bgit\s+push\b[^"']*(?:--force\b|-f\b)/,
    reason: 'Force push is blocked. Use regular git push.',
  },
  {
    // +refspec is a force push, e.g. `git push origin +HEAD:main` (review H1).
    regex: /\bgit\s+push\b[^|;&]*\s\+\S/,
    reason: 'Force push via +refspec (git push ... +<ref>) is blocked. Use regular git push.',
  },
];

/**
 * Whole-tree pathspecs — a `git checkout -- <these>` discards the entire
 * working tree, so it is NOT a "single-file restore" and stays blocked.
 */
const WHOLE_TREE_PATHSPECS = new Set(['.', './', '..', '../', '*', ':/', ':', '...']);

/**
 * True only for a genuine specific-path restore:
 *   `git checkout [<ref>] -- <path> [<path>...]`
 * where every pathspec is a concrete path (none is a whole-tree spec like
 * `.`, `./`, `:/`, `*`, or a glob). This is the legitimate "restore one file
 * to HEAD" case that must NOT be blocked (P0c). Branch switches
 * (`git checkout main`, `git checkout -b x`) and whole-tree restores
 * (`git checkout .`, `git checkout -- .`, `git checkout -- ./`,
 * `git checkout -- :/`, `git checkout -- *`, `git checkout -- src .`) are
 * still blocked (review C2 — the prior regex carve-out matched these).
 */
function isCheckoutPathRestore(cmd) {
  const m = /\bgit\s+checkout\b([^|;&]*)/.exec(cmd);
  if (!m) return false;
  const seg = m[1];
  const sep = seg.search(/\s--(?:\s|$)/);
  if (sep === -1) return false; // no `--` pathspec separator → not a path restore
  const after = seg.slice(sep).replace(/^\s--\s*/, '').trim();
  if (!after) return false; // `git checkout --` with no pathspec
  for (const spec of after.split(/\s+/)) {
    if (WHOLE_TREE_PATHSPECS.has(spec)) return false;
    if (spec.includes('*')) return false; // globs may match the whole tree
  }
  return true;
}

/** Blocked only in the main worktree (where .git is a directory). */
const DESTRUCTIVE_IN_MAIN = [
  {
    regex: /\bgit\s+checkout\b/,
    // P0c: skip when the command is a specific-path restore (`... -- <path>`),
    // which is legitimate in the main worktree. `skipIf` is a predicate.
    skipIf: isCheckoutPathRestore,
    reason:
      'git checkout is blocked in the main worktree — it stays on main. ' +
      'Create a worktree instead: git worktree add ../JustSearch-wt/<name> -b <branch> main. ' +
      '(Specific-path restore `git checkout -- <path>` is allowed; whole-tree restore is not.)',
  },
  {
    regex: /\bgit\s+switch\b/,
    reason:
      'git switch is blocked in the main worktree. Create a worktree instead.',
  },
  {
    regex: /\bgit\s+reset\s+--hard\b/,
    reason:
      'git reset --hard is blocked in the main worktree. ' +
      'It destroys uncommitted work from other agents sharing this checkout.',
  },
  {
    regex: /\bgit\s+clean\b[^"']*-[a-zA-Z]*f/,
    reason:
      'git clean -f is blocked in the main worktree. ' +
      'It removes untracked files that may belong to other agents.',
  },
  {
    // Whole-tree restore in any flag arrangement (review C1): `git restore .`,
    // `git restore --worktree .`, `git restore ./`, `:/`, `*`. Specific-path
    // restores (`git restore src/a.ts`) carry no whole-tree token → allowed.
    regex: /\bgit\s+restore\b[^|;&]*?\s(?:\.|\.\/|\*|:\/)(?:\s|$)/,
    reason:
      'git restore of the whole tree is blocked in the main worktree. ' +
      'It discards all uncommitted changes. Restore specific files instead.',
  },
];

/** True when .git is a real directory (main checkout). */
function isMainWorktree() {
  try {
    return statSync(join(process.cwd(), '.git')).isDirectory();
  } catch {
    return false;
  }
}

// --- Sleep / polling hygiene ---

/**
 * Blocks `sleep` with duration >= 1 second. Short sleeps (< 1s) are allowed
 * as backoff intervals inside condition-based polling loops (e.g.,
 * `while ! curl ...; do sleep 0.5; done`). Long sleeps indicate unconditional
 * delays between commands — the bad pattern.
 *
 * Matches: sleep 1, sleep 3, sleep 10, sleep 60
 * Allows:  sleep 0.2, sleep 0.5 (polling backoff)
 */
const SLEEP_PATTERN = {
  regex: /\bsleep\s+([1-9]\d*|0*[1-9]\d*\.)\b/,
  reason:
    'sleep >= 1s is blocked. Use jseval for backend lifecycle and pipeline profiling:\n' +
    '  cd scripts/jseval && python -m jseval run --dataset scifact --max-queries 0 \\\n' +
    '    --pipeline --start-backend --clean --json\n' +
    'For condition-based polling, use short backoff: while ! curl ...; do sleep 0.5; done\n' +
    'Do not use arbitrary sleep delays between commands.',
};

// --- Tool hygiene patterns ---
//
// Layer 3 only targets *bare* file-reading commands (no flags, no chains).
// Flagged invocations (`cat -n`, `head -n 50`, `grep -A 3`) are deliberate
// terminal-oriented output and are allowed (P0a). Chained commands are
// pipelines, not single-file reads, and bypass Layer 3 by design (P0b).

const FILE_READING_PATTERNS = [
  { regex: /^\s*cat\s+\S+(?:\s+\S+)*\s*$/, tool: 'Read', alt: 'Use the Read tool to read file contents.' },
  { regex: /^\s*head\s+\S+\s*$/, tool: 'Read (with limit)', alt: 'Use Read with the `limit` parameter.' },
  { regex: /^\s*tail\s+\S+\s*$/, tool: 'Read (with offset)', alt: 'Use Read with the `offset` parameter.' },
  { regex: /^\s*(?:grep|rg)\s+(?:"[^"]*"|'[^']*'|\S+)\s+\S+(?:\s+\S+)*\s*$/, tool: 'Grep', alt: 'Use the Grep tool to search file contents.' },
];

const CHAIN_OPERATOR = /[|><]|&&|\|\||;/;

/** True when any whitespace-delimited token starts with `-` (a flag). P0a. */
function hasFlagToken(cmd) {
  return cmd.split(/\s+/).some((tok) => tok.startsWith('-'));
}

/**
 * Replace the CONTENTS of quoted strings with empty, so a dangerous literal that
 * appears only as quoted DATA (e.g. `echo "git push --force"`, a commit message) is
 * not mistaken for the command itself (observation #32). A real force-push is never
 * wholly inside quotes — the shell would treat it as a string, not run it.
 *
 * Honest limit: an explicitly evaluated quoted command (`bash -c "git push --force"`)
 * is NOT caught — intentional-evasion territory, out of scope for a guard against
 * ACCIDENTAL destructive actions.
 */
function stripQuotedLiterals(cmd) {
  // Single quotes are literal in shell (no escapes); double quotes process `\"`, so a
  // double-quoted run must consume escaped chars or an embedded `\"git push --force\"`
  // would leak out (caught dogfooding: this fix's own commit message tripped it).
  return cmd.replace(/'[^']*'|"(?:\\.|[^"\\])*"/g, '');
}

// --- Decision logic (pure; unit-tested via bash-guard.test.mjs) ---

/**
 * Decide whether a Bash command should be blocked.
 *
 * @param {string} cmd  the (trimmed) command string
 * @param {{ isMain?: boolean }} [opts]  isMain = running in the main worktree
 * @returns {{ block: boolean, reason?: string, layer?: string }}
 */
export function evaluateBashCommand(cmd, { isMain = false } = {}) {
  if (!cmd) return { block: false };

  // Layer 1: Git safety — scans full command including chained commands. Quoted
  // string CONTENTS are stripped first (observation #32) so a force-push literal that
  // is only quoted DATA (`echo "git push --force"`, a commit message) is not mistaken
  // for the command. Real force-pushes are unquoted, so they still match.
  const unquoted = stripQuotedLiterals(cmd);
  for (const { regex, reason } of DESTRUCTIVE_EVERYWHERE) {
    if (regex.test(unquoted)) return { block: true, reason, layer: 'git-everywhere' };
  }

  if (isMain) {
    for (const { regex, skipIf, reason } of DESTRUCTIVE_IN_MAIN) {
      if (regex.test(cmd) && !(skipIf && skipIf(cmd))) {
        return { block: true, reason, layer: 'git-main' };
      }
    }
  }

  // Layer 2: Sleep hygiene — scans full command including chained commands.
  if (SLEEP_PATTERN.regex.test(cmd)) {
    return { block: true, reason: SLEEP_PATTERN.reason, layer: 'sleep' };
  }

  // Layer 3: Tool hygiene — advisory redirect for *bare* file reads only.
  // P0b: chained commands are pipelines, not single-file reads; Layer 3 is
  // advisory (not a safety block), and per-segment blocking would create
  // false positives on legitimate pipelines. Chains are intentionally exempt.
  if (CHAIN_OPERATOR.test(cmd)) return { block: false };

  // Allow non-destructive git commands (status, log, diff, add, commit, ...).
  if (/^\s*git\b/.test(cmd)) return { block: false };

  // P0a: flagged invocations are deliberate terminal output, not bare reads.
  if (hasFlagToken(cmd)) return { block: false };

  for (const { regex, tool, alt } of FILE_READING_PATTERNS) {
    if (regex.test(cmd)) {
      return {
        block: true,
        layer: 'tool-hygiene',
        reason:
          `${alt} The ${tool} tool provides line numbers, supports offset/limit, ` +
          `and avoids wasting context on raw terminal output.`,
      };
    }
  }

  return { block: false };
}

// --- Main (thin I/O wrapper) ---

async function main() {
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;

  const cmd = input.tool_input?.command?.trim();
  if (!cmd) return;

  const verdict = evaluateBashCommand(cmd, { isMain: isMainWorktree() });
  if (verdict.block) {
    process.stderr.write(verdict.reason);
    process.exit(2);
  }
}

runHook(import.meta.url, main);
