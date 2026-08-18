#!/usr/bin/env node

/**
 * Synchronous PreToolUse hook (matcher: "Bash").
 *
 * Two layers of protection:
 *   Layer 1 — Git safety: blocks destructive git operations.
 *             Some commands are blocked everywhere (force-push).
 *             Others are blocked only in the main worktree (checkout, reset --hard, clean).
 *   Layer 2 — Sleep hygiene: blocks unconditional `sleep >= 1s` (use a condition-poll).
 *
 * A third layer — tool hygiene, redirecting bare `cat`/`head`/`tail`/`grep` to the
 * Read/Grep tools — was REMOVED 2026-08-18; see the note at its former site below.
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

  // Layer 2: Sleep hygiene — scans full command including chained commands. Tests
  // against `unquoted` (quoted string CONTENTS stripped, same as Layer 1) so text
  // that merely mentions "sleep N" inside an echoed string, comment, or commit
  // message isn't mistaken for a real sleep invocation.
  if (SLEEP_PATTERN.regex.test(unquoted)) {
    return { block: true, reason: SLEEP_PATTERN.reason, layer: 'sleep' };
  }

  // REMOVED 2026-08-18 (owner decision): the tool-hygiene layer, which blocked bare
  // `cat`/`head`/`tail`/`grep`/`rg` and redirected them to the Read/Grep tools.
  // Claude Code's own bypassPermissions-mode guidance now instructs the opposite —
  // "read files with cat, head, or sed -n, search with grep and find" — so the layer
  // was contradicting the harness it runs inside, and cost a blocked turn each time
  // it fired. The safety layers (destructive git, force-push, sleep) are untouched:
  // those encode facts the harness does NOT provide.

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
