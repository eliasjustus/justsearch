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
 * `scripts/ci/lib/tempdoc-scan.mjs`. This file is a thin CLI over that lib and its behavior is
 * UNCHANGED: it fails if any single number maps to two or more DISTINCT basenames NOT yet on
 * origin, across two or more distinct worktrees (identical basenames across worktrees, or a
 * single worktree's own multi-file batch, are fine).
 *
 * Usage: node scripts/ci/check-tempdoc-numbers.mjs   (exit 0 = no collision, 1 = collision, 2 = error)
 */

import { collectClaims, divergentInFlightCollisions } from './lib/tempdoc-scan.mjs';

const { claims, worktreeCount, defaultBranch } = collectClaims({ cwd: process.cwd() });
const collisions = divergentInFlightCollisions(claims);

if (collisions.length > 0) {
  console.error('tempdoc/changeset NUMBER COLLISION — the same number is claimed by different docs:');
  console.error(collisions.map((c) => `  #${c.number}: ${c.detail}`).join('\n'));
  console.error('\nRenumber one of them (pick the next free number) before merge. Parallel worktrees');
  console.error("can't see each other's in-flight numbers; this check is the cross-worktree guard.");
  process.exit(1);
}

console.log(`tempdoc-numbers: OK — ${claims.size} distinct numbers, no collisions across ${worktreeCount} worktree(s) + origin/${defaultBranch}.`);
