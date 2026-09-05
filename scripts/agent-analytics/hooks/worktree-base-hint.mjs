#!/usr/bin/env node

/**
 * PostToolUse hook for EnterWorktree.
 *
 * Mechanizes `verify-worktree-base` (.claude/rules/branch-safety.md, previously prose-only).
 * `worktree.baseRef: "head"` (.claude/settings.json) should make a new worktree's HEAD
 * equal the main checkout's HEAD by construction, but a harness-version quirk or a
 * manual `git worktree add` can silently violate that (tempdoc 618 §1 — local main can
 * be dozens of commits ahead of origin, and an agent builds for hours on a stale base
 * without noticing). This hook compares the two HEADs right after creation and surfaces
 * a mismatch immediately instead of relying on the agent to remember to check.
 *
 * Tempdoc 727 F-3: the HEAD-equality check above is silent in a *different* real failure
 * mode — the new worktree and main can share the same commit while main has UNCOMMITTED
 * changes at the moment of branching that a worktree created from a commit can never see
 * (the triggering incident: a worktree missed an uncommitted "Direction note" resolving the
 * task's core question, discovered only mid-session). This hook now also checks for
 * uncommitted changes in the main checkout at the same moment, worded as a neutral FYI, not
 * an alarm — branch-safety.md already documents shared-main WIP from other agents as a normal
 * condition, so a blanket "main has uncommitted changes" warning would mostly be noise;
 * naming what's uncommitted lets the agent judge relevance itself.
 *
 * - Synchronous (blocks until return, <5s via spawnSync)
 * - Git subprocess calls: rev-parse HEAD in each checkout + status --porcelain in main
 * - Outputs hookSpecificOutput.additionalContext only on mismatch/uncommitted-changes; silent otherwise
 */

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { hooksDisabled } from '../lib/hook-base.mjs';

// repoRoot resolution: the script's own location is
// always under the MAIN checkout's scripts/ dir regardless of the session's current
// worktree cwd, so this reliably resolves to main even when invoked from a worktree.
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
const repoRoot = path.resolve(scriptDir, '..', '..', '..');

function headOf(cwd) {
  const result = spawnSync('git', ['rev-parse', 'HEAD'], { cwd, encoding: 'utf8', timeout: 5000 });
  if (result.status !== 0) return null;
  return result.stdout.trim();
}

/** Tempdoc 727 F-3: short-status lines for uncommitted changes in `cwd` (empty array if clean). */
function uncommittedChangesIn(cwd) {
  const result = spawnSync('git', ['status', '--porcelain'], { cwd, encoding: 'utf8', timeout: 5000 });
  if (result.status !== 0) return [];
  return result.stdout.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
}

/**
 * Pure decision function: given the two HEADs and main's uncommitted-change lines, build the
 * additionalContext string, or null if there's nothing worth surfacing. Exported for direct
 * unit testing (no subprocess/stdin involved).
 */
export function buildWorktreeBaseNotes({ worktreeHead, mainHead, changes }) {
  const notes = [];

  if (worktreeHead && mainHead && worktreeHead !== mainHead) {
    notes.push(
      `Worktree base mismatch: this worktree's HEAD (${worktreeHead.slice(0, 12)}) differs from ` +
      `the main checkout's HEAD (${mainHead.slice(0, 12)}). worktree.baseRef:"head" should make these ` +
      `equal — verify this worktree actually contains the work you expect before coding ` +
      `(.claude/rules/branch-safety.md, verify-worktree-base).`
    );
  }

  // Tempdoc 727 F-3: even when HEADs match, main may hold uncommitted work this
  // worktree (branched from a commit, not a working tree) cannot see.
  if (changes && changes.length > 0) {
    const shown = changes.slice(0, 8).join('; ');
    const more = changes.length > 8 ? ` (+${changes.length - 8} more)` : '';
    notes.push(
      `FYI: the main checkout has ${changes.length} uncommitted change(s) not visible in this ` +
      `worktree (it was branched from a commit, not from main's working tree): ${shown}${more}. ` +
      `If any of these are relevant to your task, check main directly before assuming this ` +
      `worktree has everything.`
    );
  }

  return notes.length > 0 ? notes.join('\n\n') : null;
}

async function main() {
  // Tempdoc 727 review Finding C: this hook now shells out to `git` twice per EnterWorktree
  // (rev-parse x2 + status --porcelain), unlike its original HEAD-only-compare form — honor
  // the kill switch like the other hooks added/touched in this pass, rather than the older
  // hand-rolled hint hooks that predate this convention.
  if (hooksDisabled()) return;

  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    if (input.tool_name !== 'EnterWorktree') return;

    const worktreePath = input.tool_response?.worktreePath;
    if (!worktreePath) return; // creation failed or switched into an existing worktree via `path`

    const additionalContext = buildWorktreeBaseNotes({
      worktreeHead: headOf(worktreePath),
      mainHead: headOf(repoRoot),
      changes: uncommittedChangesIn(repoRoot),
    });
    if (!additionalContext) return;

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext,
      },
    }));
  } catch {
    // Parse failure — no output, don't block
  }
}

main().catch(() => process.exit(0));
