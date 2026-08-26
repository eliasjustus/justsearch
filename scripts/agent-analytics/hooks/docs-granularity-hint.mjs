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

import { resolve as resolvePath, isAbsolute } from 'node:path';
import { execFileSync } from 'node:child_process';
import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';

/**
 * Strip heredoc bodies (`-m "$(cat <<'EOF' … EOF)"` — this repo's commit-message
 * style). A heredoc body is prose: a `git push` inside it is a mention, not a
 * command, and `^`-anchored lines inside it would otherwise read as command
 * position. Tempdoc 653 follow-up (transcript-mined false positive).
 */
function stripHeredocs(cmd) {
  return cmd.replace(/<<-?\s*(['"]?)(\w+)\1[\s\S]*?^\s*\2\s*$/gm, ' ');
}

/**
 * A real push has `git … push` in COMMAND position — at the start, or after a
 * shell separator. Testing the raw string instead (the pre-fix behaviour) also
 * matched `push` in ARGUMENT position, so
 * `git commit -m "fix the git push claim"` and
 * `node check.mjs --reason "… git push is allowed …"` both fired the hint.
 */
const PUSH_AT_COMMAND_POSITION =
  /(?:^|[\n;|]|&&|\|\|)\s*git\b(?:\s+-C\s+(?:"[^"]*"|'[^']*'|\S+))?\s+push(?:\s|$)/i;

/**
 * A branch deletion (`--delete`/`-d`, or a `:branch` refspec) publishes no
 * content, so granularity guidance is meaningless for it.
 */
const IS_BRANCH_DELETE = /\s(?:--delete\b|-d\b|:\S+)/i;

/** `git [ -C <path> ] push [...]` — not `git log --grep=push`, not a mention. */
export function isGitPush(cmd) {
  if (!cmd) return false;
  const bare = stripHeredocs(cmd);
  if (!PUSH_AT_COMMAND_POSITION.test(bare)) return false;
  return !IS_BRANCH_DELETE.test(bare);
}

/**
 * Resolve the repo the push actually targets.
 *
 * The hint reads the branch diff, so it must diff the tree being PUSHED. The
 * pre-fix code trusted `input.cwd` alone and never parsed `-C` — so every
 * `git -C <worktree> push` issued from elsewhere diffed the WRONG repo (usually
 * the main checkout, sitting on unrelated commits), producing hints whose claim
 * contradicted the actual push. This codebase pushes from worktrees constantly,
 * so that was the dominant misfire. Handles `-C <path>` and `cd <path> && …`;
 * relative paths resolve against `fallback`.
 */
export function gitPushCwd(cmd, fallback) {
  const bare = stripHeredocs(cmd || '');
  const dashC =
    /(?:^|[\n;|]|&&|\|\|)\s*git\s+-C\s+(?:"([^"]+)"|'([^']+)'|(\S+))\s+push\b/i.exec(bare);
  const cd = /(?:^|[\n;]|&&|\|\|)\s*cd\s+(?:"([^"]+)"|'([^']+)'|(\S+))\s*(?:&&|;)/i.exec(bare);
  const hit = dashC || cd;
  if (!hit) return fallback;
  const p = hit[1] || hit[2] || hit[3];
  if (!p) return fallback;
  return isAbsolute(p) ? p : resolvePath(fallback || process.cwd(), p);
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

/**
 * The case the rule actually forbids: ONE working-history file, standing alone.
 *
 * `isArchaeologyOnly` is necessary but not sufficient. The rule permits two
 * shapes — ride along with code, or batch several edits into one periodic PR —
 * and a batch is archaeology-only too. Firing on every archaeology-only branch
 * therefore nagged correct work: measured on `main` at 193 commits, 54 branches
 * were archaeology-only but only 22 were single-file, so the hint was right 41%
 * of the time. Agents responded rationally, by discounting it ("#160 is exactly
 * the endorsed batched pattern, so no change needed" — verbatim, 2026-07-14),
 * which is how a ~85% hook-hint decays toward prose.
 *
 * Restricting to exactly one file makes the signal true: a lone note cannot be
 * a batch and is not riding along with anything. Multi-file archaeology (the
 * step-4 shard fold, a periodic `docs(tempdocs):` batch) is what the rule asks
 * for, so it stays silent. Owner decision, 2026-07-15 (tempdoc 739 §6).
 */
export function isStandaloneNote(files) {
  const real = (files || []).map((f) => f.trim()).filter(Boolean);
  return real.length === 1 && isArchaeologyOnly(real);
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
  'History hygiene (ADR-0045 axis-2, tempdoc 653): this branch changes exactly ONE',
  'working-history file (docs/tempdocs/**) and nothing else.',
  'Public `main` is a curated narrative, so a lone working-history note should not',
  'become its own standalone PR/commit. Prefer to either:',
  '  - ride it along in the same PR as the code it documents, or',
  '  - batch it into the next periodic `docs(tempdocs): …` PR.',
  'A prior standalone tempdoc PR is not a precedent — re-qualify on the rule, not',
  'on what an earlier PR did. Multi-file batches (several tempdoc edits) and',
  'canonical-doc updates are durable units and do NOT trigger this.',
  'Rationale: docs/reference/contributing/agent-guide.md (History publication).',
].join('\n');

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  const command = input.tool_input?.command;
  if (!isGitPush(command)) return;

  let files;
  try {
    // Diff the tree being PUSHED, not the tree we happen to be standing in.
    files = branchChangedFiles(gitPushCwd(command, input.cwd || process.cwd()));
  } catch {
    return; // fail-open
  }
  if (!isStandaloneNote(files)) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: HINT },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
