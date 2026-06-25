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
 * It collects every `docs/tempdocs/<N>-*.md` and `gates/<gate>/.changesets/<N>-*.md` number across:
 *   - the working tree,
 *   - every registered git worktree (`git worktree list`),
 *   - `origin/<default-branch>` (best-effort; skipped if unavailable),
 * and fails if any single number maps to two or more DISTINCT basenames (i.e. the same number was
 * claimed for different docs). Identical basenames across worktrees (the same doc) are fine.
 *
 * Usage: node scripts/ci/check-tempdoc-numbers.mjs   (exit 0 = no collision, 1 = collision, 2 = error)
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';

// A top-level tempdoc is `docs/tempdocs/<N>-<name>.md` OR a `docs/tempdocs/<N>-<name>/` directory
// (a nested-numbered draft folder). Its nested files have their own local numbering and
// are NOT tempdoc numbers — only the top-level segment counts.
const TOPLEVEL_RE = /^(\d+)-/;
const CHANGESET_RE = /^(\d+)-.*\.md$/;

function git(args, opts = {}) {
  try {
    return execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], ...opts }).trim();
  } catch {
    return '';
  }
}

/** number -> Map<basename, Set<origin label>> */
const claims = new Map();
function record(number, basename, origin) {
  if (!claims.has(number)) claims.set(number, new Map());
  const byName = claims.get(number);
  if (!byName.has(basename)) byName.set(basename, new Set());
  byName.get(basename).add(origin);
}

/** Scan a worktree dir's tempdocs (top-level file/dir) + changesets on disk. */
function scanDir(rootDir, label) {
  const tempdocs = join(rootDir, 'docs', 'tempdocs');
  if (existsSync(tempdocs)) {
    for (const e of readdirSync(tempdocs, { withFileTypes: true })) {
      const m = TOPLEVEL_RE.exec(e.name); // matches both `<N>-name.md` files and `<N>-name/` dirs
      if (m) record(m[1], e.name, label);
    }
  }
  const gatesDir = join(rootDir, 'gates');
  if (existsSync(gatesDir)) {
    for (const g of readdirSync(gatesDir, { withFileTypes: true })) {
      if (!g.isDirectory()) continue;
      const cs = join(gatesDir, g.name, '.changesets');
      if (!existsSync(cs)) continue;
      for (const e of readdirSync(cs, { withFileTypes: true })) {
        const m = e.isFile() ? CHANGESET_RE.exec(e.name) : null;
        if (m) record(m[1], e.name, `${label}:gates/${g.name}`);
      }
    }
  }
}

// 1. All registered worktrees (incl. the current one + the main checkout).
const wtPaths = git(['worktree', 'list', '--porcelain'])
  .split('\n')
  .filter((l) => l.startsWith('worktree '))
  .map((l) => l.slice('worktree '.length).trim())
  .filter(Boolean);
const seen = new Set();
for (const p of wtPaths.length ? wtPaths : [process.cwd()]) {
  if (seen.has(p)) continue;
  seen.add(p);
  scanDir(p, `worktree:${p.replace(/\\/g, '/').split('/').pop()}`);
}

// 2. origin/<default-branch> (best-effort).
let defaultBranch = git(['symbolic-ref', '--quiet', '--short', 'refs/remotes/origin/HEAD']).replace(/^origin\//, '');
if (!defaultBranch) defaultBranch = 'main';
const originList = git(['ls-tree', '-r', '--name-only', `origin/${defaultBranch}`]);
const originTempdocSegs = new Set(); // dedup nested files down to their top-level segment
for (const path of originList.split('\n')) {
  const p = path.trim();
  if (!p) continue;
  if (p.startsWith('docs/tempdocs/')) {
    const seg = p.slice('docs/tempdocs/'.length).split('/')[0]; // top-level file or dir name
    const m = TOPLEVEL_RE.exec(seg);
    if (m && !originTempdocSegs.has(seg)) {
      originTempdocSegs.add(seg);
      record(m[1], seg, 'origin');
    }
  } else if (/^gates\/[^/]+\/\.changesets\/[^/]+$/.test(p)) {
    const name = p.split('/').pop();
    const m = CHANGESET_RE.exec(name);
    if (m) record(m[1], name, `origin:${p.split('/')[1]}`);
  }
}

// 3. Report collisions. NOTE: this repo's convention legitimately reuses one number for a
// multi-file batch (e.g. the 249-*-findings research set) — all committed together on origin.
// A naive "one number, one basename" rule floods with false positives on those. The REAL,
// merge-breaking collision is narrow: TWO OR MORE distinct worktrees each introduce a DIFFERENT
// basename for the same number that is NOT yet on origin — i.e. independent in-flight claims that
// will collide on merge (exactly the 553-canonical vs 553-code-duplication-audit case). On-origin
// reuse and a single worktree's own multi-file batch are both fine.
const isOrigin = (label) => label === 'origin' || label.startsWith('origin:');
const collisions = [];
for (const [n, byName] of [...claims.entries()].sort((a, b) => Number(a[0]) - Number(b[0]))) {
  // basenames for N that are NOT present on origin (in-flight additions only).
  const newBasenames = [...byName.entries()].filter(([, labels]) => ![...labels].some(isOrigin));
  if (newBasenames.length < 2) continue; // 0/1 distinct in-flight basename → no divergent claim.
  const worktrees = new Set();
  for (const [, labels] of newBasenames) for (const l of labels) worktrees.add(l);
  if (worktrees.size < 2) continue; // all from one worktree → an intentional single-author batch.
  const detail = newBasenames
    .map(([name, labels]) => `${name} [${[...labels].join(', ')}]`)
    .join('  vs  ');
  collisions.push(`  #${n}: ${detail}`);
}

if (collisions.length > 0) {
  console.error('tempdoc/changeset NUMBER COLLISION — the same number is claimed by different docs:');
  console.error(collisions.join('\n'));
  console.error('\nRenumber one of them (pick the next free number) before merge. Parallel worktrees');
  console.error("can't see each other's in-flight numbers; this check is the cross-worktree guard.");
  process.exit(1);
}

console.log(`tempdoc-numbers: OK — ${claims.size} distinct numbers, no collisions across ${seen.size} worktree(s) + origin/${defaultBranch}.`);
