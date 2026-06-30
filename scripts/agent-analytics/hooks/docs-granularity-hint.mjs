#!/usr/bin/env node

/**
 * PreToolUse hook on Bash `git push` — tempdoc 653 axis-2 (PR/commit
 * granularity).
 *
 * ADR-0045 made public `main` a squash-projected history surface. That solves
 * "axis 1": collapse a noisy branch transcript into one curated commit at
 * merge. It is silent on "axis 2": whether a trivial / docs-only change should
 * be its OWN standalone public PR / commit at all. The established convention
 * (Microsoft eng playbook; Kubernetes PR guide — see
 * `docs/reference/contributing/agent-guide.md` §History publication) is that
 * docs ride along with the code they document, and trivial working-note edits
 * batch — they do not each become a separate mainline commit.
 *
 * This hook fires at the publish boundary (`git push`). If the WHOLE branch
 * diff vs `origin/main` is dated working history only (`docs/tempdocs/**` or
 * `docs/observations*`), it emits a non-blocking ride-along/batch reminder.
 * Two intentional non-triggers keep false positives near zero:
 *   - a branch mixing docs with code is already a ride-along → no hint;
 *   - a canonical-doc-only branch (`docs/{explanation,reference,how-to,
 *     decisions}`) is a durable standalone unit → no hint.
 *
 * Advisory: never blocks, fail-open on any error (no `origin/main`, detached
 * HEAD, non-repo cwd), honors `JUSTSEARCH_DISABLE_HOOKS=1`. Delivers the rule
 * `docs-ride-along` (tier-register row 36) at its moment of relevance.
 */

import { execFileSync } from 'node:child_process';
import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';

/** `git [ -C <path> ] push [...]` — not `git log --grep=push` etc. */
export function isGitPush(cmd) {
  if (!cmd) return false;
  return /\bgit\b(?:\s+-C\s+\S+)?\s+push(?:\s|$)/i.test(cmd);
}

/** Dated working history that should ride along / batch, not stand alone. */
const ARCHAEOLOGY = /^docs\/(?:tempdocs\/|observations)/;

/**
 * True only when the branch changes at least one archaeology file and NO
 * other file. Any canonical-doc or code path anywhere → false (ride-along or
 * durable standalone — both fine, both intentionally un-hinted).
 */
export function isArchaeologyOnly(files) {
  const real = (files || []).map((f) => f.trim()).filter(Boolean);
  if (real.length === 0) return false;
  return real.every((f) => ARCHAEOLOGY.test(f));
}

function branchChangedFiles(cwd) {
  const base = execFileSync('git', ['merge-base', 'origin/main', 'HEAD'], {
    cwd,
    encoding: 'utf8',
  }).trim();
  if (!base) return [];
  const out = execFileSync('git', ['diff', '--name-only', `${base}..HEAD`], {
    cwd,
    encoding: 'utf8',
  });
  return out.split('\n');
}

export const HINT = [
  'History hygiene (ADR-0045 axis-2, tempdoc 653): this branch changes ONLY',
  'dated working history (docs/tempdocs/** or docs/observations*). Public `main`',
  'is a curated narrative, so a tempdoc-only change should not become its own',
  'standalone PR/commit. Prefer to either:',
  '  - ride it along in the same PR as the code it documents, or',
  '  - batch tempdoc edits into one periodic `docs(tempdocs): …` PR.',
  'Canonical-doc updates (docs/{explanation,reference,how-to,decisions}) are',
  'durable units and may stand alone. Rationale:',
  'docs/reference/contributing/agent-guide.md (History publication).',
].join('\n');

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  if (!isGitPush(input.tool_input?.command)) return;

  let files;
  try {
    files = branchChangedFiles(input.cwd || process.cwd());
  } catch {
    return; // fail-open
  }
  if (!isArchaeologyOnly(files)) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: HINT },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
