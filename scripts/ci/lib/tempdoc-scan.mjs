/**
 * Tempdoc / changeset number-claim scanner (tempdoc 553 Phase 2a origin; extracted to this
 * shared lib by tempdoc 743 P-J — "one scanner, two consumers").
 *
 * The scanner itself (`collectClaims`) is a pure function of disk + git state: it collects every
 * `docs/tempdocs/<N>-*.md` and `gates/<gate>/.changesets/<N>-*.md` number across every registered
 * git worktree plus `origin/<default-branch>` (best-effort; empty on failure), into
 * `number -> Map<basename, Set<originLabel>>`.
 *
 * Two different predicates consume that same scan, because "is this a merge-breaking collision?"
 * and "is this number free to pick?" are different questions over the same data:
 *
 *   - `divergentInFlightCollisions` — the pre-743 merge-gate rule (scripts/ci/check-tempdoc-numbers.mjs),
 *     UNCHANGED behavior. Two parallel agents independently authoring DIFFERENT basenames for the
 *     same number is a real merge-time collision; the same basename reused across worktrees (one
 *     doc, checked out in several places) or reused after landing on origin is not. Deliberately
 *     ignores any basename already present on origin (see the R1 derisk note below) — correct for
 *     "will this collide with in-flight work," blind for "is N actually free."
 *
 *   - `isNumberFree` / `nextFreeNumber` — the tempdoc-743 P-J pick-time query. A number claimed by
 *     ANY origin, ANY worktree — in-flight or already merged — is not free. This closes the R1
 *     blind spot: a single in-flight worktree colliding with a basename already merged to origin is
 *     invisible to `divergentInFlightCollisions` (only one non-origin claimant, so it never reaches
 *     the "2+ distinct in-flight basenames" threshold) but IS caught here, because origin claims
 *     count too (tempdoc 743 derisk record, R1: "the in-flight-vs-merged blind spot ... correct for
 *     the merge-gate's divergent-claim rule, blind for 'is N free?' at pick time").
 *
 * Usage (both consumers): `collectClaims({ cwd })` once per process, then apply whichever
 * predicate(s) the caller needs over the returned claims map. No process.exit, no console output —
 * callers own presentation.
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

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

function record(claims, number, basename, origin) {
  if (!claims.has(number)) claims.set(number, new Map());
  const byName = claims.get(number);
  if (!byName.has(basename)) byName.set(basename, new Set());
  byName.get(basename).add(origin);
}

/**
 * The `tempdoc:` number a changeset's frontmatter declares, or null.
 *
 * A changeset's number is NOT a free choice — the changeset loader throws without a `tempdoc:`
 * or `adr:` field, and the filename convention is `<N>-<slug>.md`. Reading it here is what lets
 * the collision rule distinguish "this changeset belongs to a tempdoc that exists" from
 * "this number was invented".
 *
 * @param {string} file
 * @returns {string|null}
 */
function declaredTempdocOf(file) {
  let head;
  try {
    head = readFileSync(file, 'utf8').slice(0, 2000);
  } catch {
    return null;
  }
  if (!head.startsWith('---')) return null;
  const end = head.indexOf('\n---', 3);
  const front = end === -1 ? head : head.slice(0, end);
  const m = /^tempdoc:\s*(\d+)/m.exec(front);
  return m ? m[1] : null;
}

/** Scan a worktree dir's tempdocs (top-level file/dir) + changesets on disk. */
function scanDir(claims, changesets, rootDir, label) {
  const tempdocs = join(rootDir, 'docs', 'tempdocs');
  if (existsSync(tempdocs)) {
    for (const e of readdirSync(tempdocs, { withFileTypes: true })) {
      const m = TOPLEVEL_RE.exec(e.name); // matches both `<N>-name.md` files and `<N>-name/` dirs
      if (m) record(claims, m[1], e.name, label);
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
        if (!m) continue;
        const csLabel = `${label}:gates/${g.name}`;
        record(claims, m[1], e.name, csLabel);
        changesets.push({
          number: m[1],
          basename: e.name,
          label: csLabel,
          path: `gates/${g.name}/.changesets/${e.name}`,
          declaredTempdoc: declaredTempdocOf(join(cs, e.name)),
        });
      }
    }
  }
}

/**
 * Collects tempdoc/changeset number claims across every registered git worktree (`git worktree
 * list --porcelain`, executed from `cwd`) plus `origin/<default-branch>` (best-effort — an
 * unreachable/absent origin degrades to no origin claims, never throws).
 *
 * Pure function: no process.exit, no console output, no mutation outside its own return value.
 *
 * @param {{cwd?: string}} opts - `cwd` defaults to `process.cwd()`; every git invocation runs with
 *   this as its working directory, so the result is reproducible for a given cwd/git-state pair.
 * @returns {{claims: Map<string, Map<string, Set<string>>>, worktreeCount: number, defaultBranch: string}}
 */
export function collectClaims({ cwd = process.cwd() } = {}) {
  const claims = new Map();
  /** @type {Array<{number: string, basename: string, label: string, path: string, declaredTempdoc: string|null}>} */
  const changesets = [];

  // 1. All registered worktrees (incl. the current one + the main checkout).
  const wtPaths = git(['worktree', 'list', '--porcelain'], { cwd })
    .split('\n')
    .filter((l) => l.startsWith('worktree '))
    .map((l) => l.slice('worktree '.length).trim())
    .filter(Boolean);
  const seen = new Set();
  for (const p of wtPaths.length ? wtPaths : [cwd]) {
    if (seen.has(p)) continue;
    seen.add(p);
    scanDir(claims, changesets, p, `worktree:${p.replace(/\\/g, '/').split('/').pop()}`);
  }

  // 2. origin/<default-branch> (best-effort).
  let defaultBranch = git(['symbolic-ref', '--quiet', '--short', 'refs/remotes/origin/HEAD'], { cwd }).replace(/^origin\//, '');
  if (!defaultBranch) defaultBranch = 'main';
  const originList = git(['ls-tree', '-r', '--name-only', `origin/${defaultBranch}`], { cwd });
  const originTempdocSegs = new Set(); // dedup nested files down to their top-level segment
  for (const path of originList.split('\n')) {
    const p = path.trim();
    if (!p) continue;
    if (p.startsWith('docs/tempdocs/')) {
      const seg = p.slice('docs/tempdocs/'.length).split('/')[0]; // top-level file or dir name
      const m = TOPLEVEL_RE.exec(seg);
      if (m && !originTempdocSegs.has(seg)) {
        originTempdocSegs.add(seg);
        record(claims, m[1], seg, 'origin');
      }
    } else if (/^gates\/[^/]+\/\.changesets\/[^/]+$/.test(p)) {
      const name = p.split('/').pop();
      const m = CHANGESET_RE.exec(name);
      if (m) record(claims, m[1], name, `origin:gates/${p.split('/')[1]}`);
    }
  }

  return { claims, changesets, worktreeCount: seen.size, defaultBranch };
}

const isOrigin = (label) => label === 'origin' || label.startsWith('origin:');
/** The worktree a claim came from, dropping the `:gates/<id>` suffix a changeset label carries. */
const worktreeOf = (label) => label.replace(/:gates\/[^:]+$/, '');
/** A changeset's label always carries the gate it lives under; a tempdoc's never does. */
const isChangesetLabel = (label) => /(?:^|:)gates\//.test(label);

/**
 * The set of numbers claimed by an actual `docs/tempdocs/<N>-*` entry, anywhere (worktree or
 * origin). A changeset's `tempdoc:` must name one of these.
 *
 * @param {Map<string, Map<string, Set<string>>>} claims
 * @returns {Set<string>}
 */
export function tempdocNumbers(claims) {
  const out = new Set();
  for (const [n, byName] of claims) {
    for (const [, labels] of byName) {
      if ([...labels].some((l) => !isChangesetLabel(l))) {
        out.add(n);
        break;
      }
    }
  }
  return out;
}

/**
 * Changesets whose `tempdoc:` frontmatter names a number no tempdoc file claims.
 *
 * This is the check that pays for exempting changesets from the divergence rule below. Once a
 * changeset can no longer collide by number, the remaining risk is a changeset pointing at a
 * tempdoc that does not exist — an invented number, or a typo that quietly detaches the
 * declaration from its rationale. That is what this catches.
 *
 * A changeset carrying `adr:` instead of `tempdoc:` is legitimate (the changeset loader accepts
 * either), so a missing `tempdoc:` is not reported here.
 *
 * @param {Array<{number: string, basename: string, label: string, path: string, declaredTempdoc: string|null}>} changesets
 * @param {Set<string>} numbers - from `tempdocNumbers`
 * @returns {Array<{path: string, label: string, declaredTempdoc: string}>}
 */
export function orphanChangesetDeclarations(changesets, numbers) {
  const out = [];
  for (const cs of changesets) {
    if (!cs.declaredTempdoc) continue;
    if (numbers.has(cs.declaredTempdoc)) continue;
    out.push({ path: cs.path, label: cs.label, declaredTempdoc: cs.declaredTempdoc });
  }
  return out;
}

/**
 * The merge-gate collision rule, UNCHANGED from pre-743 `check-tempdoc-numbers.mjs`: this repo's
 * convention legitimately reuses one number for a multi-file batch (e.g. the 249-*-findings
 * research set) — all committed together on origin. A naive "one number, one basename" rule floods
 * with false positives on those. The REAL, merge-breaking collision is narrow: TWO OR MORE distinct
 * worktrees each introduce a DIFFERENT basename for the same number that is NOT yet on origin —
 * i.e. independent in-flight claims that will collide on merge (the 553-canonical vs
 * 553-code-duplication-audit case). On-origin reuse and a single worktree's own multi-file batch
 * are both fine.
 *
 * **Changesets are not claimants** (residue R1 of the 883/884/885 wave). A changeset number is not
 * a free choice the way a tempdoc number is: the frontmatter REQUIRES `tempdoc: N` and the filename
 * convention is `<N>-<slug>.md`, so every changeset for tempdoc N is *obliged* to be named `N-*`.
 * Several changesets per tempdoc is the standing convention — `main` alone carries four `885-*` and
 * three `563-*` — and there is nothing to renumber when two worktrees each write one, because the
 * basenames differ and the files co-exist on merge. Telling an agent to "renumber one of them"
 * would make the filename contradict its own frontmatter. The risk that exemption creates —
 * a changeset naming a tempdoc that does not exist — is caught by `orphanChangesetDeclarations`,
 * not by pretending it is a number collision.
 *
 * @param {Map<string, Map<string, Set<string>>>} claims
 * @returns {Array<{number: string, detail: string}>} sorted ascending by number
 */
export function divergentInFlightCollisions(claims) {
  const collisions = [];
  for (const [n, byName] of [...claims.entries()].sort((a, b) => Number(a[0]) - Number(b[0]))) {
    // basenames for N that are NOT present on origin (in-flight additions only), and that are
    // TEMPDOCS — a basename claimed only by `:gates/<id>` labels is a changeset (see above).
    const newBasenames = [...byName.entries()].filter(
      ([, labels]) => ![...labels].some(isOrigin) && [...labels].some((l) => !isChangesetLabel(l)),
    );
    if (newBasenames.length < 2) continue; // 0/1 distinct in-flight basename -> no divergent claim.
    // Compare by WORKTREE, not by label: the same tempdoc checked out in several worktrees is one
    // claim, and a single agent's own multi-file batch is intentional.
    const worktrees = new Set();
    for (const [, labels] of newBasenames) for (const l of labels) worktrees.add(worktreeOf(l));
    if (worktrees.size < 2) continue; // all from one worktree -> an intentional single-author batch.
    const detail = newBasenames
      .map(([name, labels]) => `${name} [${[...labels].join(', ')}]`)
      .join('  vs  ');
    collisions.push({ number: n, detail });
  }
  return collisions;
}

/**
 * True iff NO claim exists for `n` anywhere — no worktree (in-flight or not) and no origin. This is
 * strictly stricter than `divergentInFlightCollisions`'s in-flight-only view: a single worktree's
 * claim, or an origin-only claim, both make a number NOT free (closing the R1 blind spot for
 * pick-time queries — see module doc).
 *
 * @param {Map<string, Map<string, Set<string>>>} claims
 * @param {string|number} n
 * @returns {boolean}
 */
export function isNumberFree(claims, n) {
  return !claims.has(String(Number(n)));
}

/**
 * The next unclaimed number: one past the highest number claimed anywhere (any worktree or
 * origin). Not a reservation — just a pure suggestion at query time; still race-prone against a
 * concurrent claim, same as picking any number today.
 *
 * @param {Map<string, Map<string, Set<string>>>} claims
 * @returns {number}
 */
export function nextFreeNumber(claims) {
  let max = 0;
  for (const key of claims.keys()) {
    const n = Number(key);
    if (Number.isFinite(n) && n > max) max = n;
  }
  return max + 1;
}
