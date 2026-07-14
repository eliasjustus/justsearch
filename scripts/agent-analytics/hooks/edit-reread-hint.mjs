#!/usr/bin/env node

/**
 * PostToolUseFailure hint hook for Edit (tempdoc 727 F-7a).
 *
 * The single largest sub-cluster of `tool-error-loop` friction in tempdoc 727's mining pass:
 * `Edit` fails with "File has not been read yet" / "modified since read" because a
 * worktree-copy and a main-checkout copy of the "same" logical file don't share read state —
 * they are different files on disk even though the content and logical identity are the same.
 * Already diagnosed once (tempdoc 618 §11e), flagged for promotion to agent-lessons.md, but
 * that promotion stalled; see agent-lessons.md's `edit-reread-cross-root` rule for the durable
 * fact this hook complements.
 *
 * The platform's own error text is already self-explanatory in the generic case, so repeating
 * it here would add nothing. This hook only emits when it can add information the platform
 * can't know: whether a *different* full path sharing this file's basename was already read
 * this session (via intervene.mjs's basename index) — i.e. "you already read this file, just
 * under a different root." Silent otherwise.
 *
 * - Async (fire-and-forget is fine for a hint; matches other *-hint.mjs hooks)
 * - Reads intervene.mjs's existing per-session read-tracking cache (read-only from here)
 * - Outputs hookSpecificOutput.additionalContext only when a cross-root match exists
 */

import path from 'node:path';
import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';
import { getOtherPathsWithSameBasename } from './intervene.mjs';

const REREAD_ERROR_RE = /has not been read|modified since read/i;

/**
 * Pure decision function: given the hook input, return the additionalContext string to emit,
 * or null to stay silent. Exported for direct unit testing (no process/stdin involved).
 */
export function buildRereadContext(input) {
  if (!input || input.tool_name !== 'Edit') return null;

  const errorText = typeof input.error === 'string' ? input.error : '';
  if (!REREAD_ERROR_RE.test(errorText)) return null;

  const filePath = input.tool_input?.file_path;
  if (!filePath || !input.session_id) return null;

  const others = getOtherPathsWithSameBasename(input.session_id, filePath);
  if (others.length === 0) return null;

  const shortOthers = others.map(p => p.split('/').slice(-3).join('/'));
  return (
    `Note: you read a different path with the same filename earlier this session ` +
    `(${shortOthers.join(', ')}) — that read doesn't cover ${path.basename(filePath)} at ` +
    `its current path. Re-read this exact path before editing it.`
  );
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input) return;

  const additionalContext = buildRereadContext(input);
  if (!additionalContext) return; // no cross-root match — stay silent, don't restate the platform error

  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PostToolUseFailure',
      additionalContext,
    },
  }));
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
