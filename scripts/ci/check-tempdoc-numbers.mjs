#!/usr/bin/env node
/**
 * Tempdoc / changeset number-collision check (tempdoc 553 Phase 2a).
 *
 * Two parallel agents in separate worktrees both picked tempdoc number 553 (this repo's
 * `553-canonical-search-execution-record.md` vs the `548-followups` worktree's
 * `553-code-duplication-audit.md`) because neither could see the other's in-flight work — a pure
 * workflow-isolation failure no prompt can prevent. This is the micro-mechanism that converts that
 * prose intent ("check the number isn't taken") into a build-time check.
 *
 * The scanner (collect every worktree + origin's claimed numbers) is now shared with the
 * pick-time query surface (tempdoc 743 P-J, "one scanner, two consumers") — see
 * `scripts/ci/lib/tempdoc-scan.mjs`. This file is a thin CLI over that lib. It enforces two rules:
 *
 *  1. **Divergent in-flight TEMPDOC numbers** — one number, two or more DISTINCT basenames not yet
 *     on origin, across two or more distinct worktrees. Changesets are excluded: their number is
 *     dictated by their `tempdoc:` frontmatter, several per tempdoc is the convention, and distinct
 *     basenames merge cleanly, so "renumber one" would be advice that breaks the file.
 *  2. **Orphan changeset declarations** — a changeset whose `tempdoc:` names a number no tempdoc
 *     file claims. This is what rule 1's changeset exemption trades for: the number can no longer
 *     collide, so the check that matters becomes "does the tempdoc it points at exist".
 *
 * Usage: node scripts/ci/check-tempdoc-numbers.mjs   (exit 0 = clean, 1 = violation, 2 = error)
 */

import {
  collectClaims,
  divergentInFlightCollisions,
  orphanChangesetDeclarations,
  tempdocNumbers,
} from './lib/tempdoc-scan.mjs';

const { claims, changesets, worktreeCount, defaultBranch } = collectClaims({ cwd: process.cwd() });
const collisions = divergentInFlightCollisions(claims);
const orphans = orphanChangesetDeclarations(changesets, tempdocNumbers(claims));

let failed = false;

if (collisions.length > 0) {
  failed = true;
  console.error('tempdoc NUMBER COLLISION — the same number is claimed by different tempdocs:');
  console.error(collisions.map((c) => `  #${c.number}: ${c.detail}`).join('\n'));
  console.error('\nRenumber one of them (pick the next free number) before merge. Parallel worktrees');
  console.error("can't see each other's in-flight numbers; this check is the cross-worktree guard.");
}

if (orphans.length > 0) {
  failed = true;
  if (collisions.length > 0) console.error('');
  console.error('ORPHAN CHANGESET — `tempdoc:` names a number no tempdoc file claims:');
  for (const o of orphans) console.error(`  ${o.path} [${o.label}] declares tempdoc: ${o.declaredTempdoc}`);
  console.error('\nPoint it at the tempdoc that actually authorises the declaration, or write that');
  console.error('tempdoc. A changeset is a pointer to a rationale; a dangling pointer is not one.');
}

if (failed) process.exit(1);

console.log(
  // "scanned", not "in-flight": this counts every changeset on disk across every worktree, most of
  // which have long since merged. Calling them in-flight overstated what the number means.
  `tempdoc-numbers: OK — ${claims.size} distinct numbers, ${changesets.length} changeset(s) scanned, ` +
    `no collisions across ${worktreeCount} worktree(s) + origin/${defaultBranch}.`,
);
