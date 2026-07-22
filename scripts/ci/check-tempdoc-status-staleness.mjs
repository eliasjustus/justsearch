#!/usr/bin/env node
/**
 * Tempdoc status-vs-merged staleness check (tempdoc 787 item 1).
 *
 * Tempdoc frontmatter `status:` fields go stale: a tempdoc says "IMPLEMENTED on branch, NOT merged —
 * awaiting owner" long after its PR squash-merged into `main`. A stale merge-pending marker actively
 * misleads a later agent into re-doing or re-verifying already-shipped work. This linter scans every
 * `docs/tempdocs/*.md` frontmatter `status:` for merge-pending phrasing and, for each hit, asks the
 * LOCAL git log whether a merged PR references that tempdoc number (squash-merge titles carry the
 * conventional-commit scope, e.g. `feat(770): … (#268)`). If one does, the marker is stale.
 *
 * OFFLINE by construction: reads only the local git history of the current checkout — never the
 * GitHub API — so it runs in CI without network. Squash-merge titles/bodies land in `git log`.
 *
 * REPORT-MODE ONLY: prints findings and ALWAYS exits 0. It does not (yet) gate the build — the
 * false-positive rate on the current tree is the measurement that decides whether it ever should
 * (tempdoc 787 item 1: "do not tune silently").
 *
 *   node scripts/ci/check-tempdoc-status-staleness.mjs          # human-readable report
 *   node scripts/ci/check-tempdoc-status-staleness.mjs --json    # machine-readable JSON
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const TEMPDOCS = resolve(REPO_ROOT, 'docs', 'tempdocs');

const JSON_OUT = process.argv.includes('--json');

/**
 * Case-insensitive merge-pending phrases. A tempdoc whose `status:` contains any of these is
 * asserting the work has NOT reached `main` — the claim this linter cross-checks against history.
 */
const MERGE_PENDING_MARKERS = [
  'not merged',
  'awaiting owner',
  'awaiting orchestrator',
  'on branch',
  'unmerged',
];

/** Extract the frontmatter `status:` value (handles double-/single-quoted and bare, incl. multiline quoted). */
function extractStatus(text) {
  const fm = text.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!fm) return null;
  const block = fm[1];
  let m;
  if ((m = block.match(/(?:^|\n)status:[ \t]*"([\s\S]*?)"/))) return m[1];
  if ((m = block.match(/(?:^|\n)status:[ \t]*'([\s\S]*?)'/))) return m[1];
  if ((m = block.match(/(?:^|\n)status:[ \t]*([^\n]*)/))) return m[1].trim();
  return null;
}

/** Leading integer of a tempdoc filename, or null. */
function tempdocNumber(file) {
  const m = basename(file).match(/^(\d+)/);
  return m ? m[1] : null;
}

/** First git ref that resolves, in preference order. */
function resolveRef() {
  for (const ref of ['main', 'origin/main', 'HEAD']) {
    try {
      execFileSync('git', ['rev-parse', '--verify', '--quiet', ref], {
        cwd: REPO_ROOT,
        stdio: ['ignore', 'ignore', 'ignore'],
      });
      return ref;
    } catch {
      /* try next */
    }
  }
  return 'HEAD';
}

/**
 * Merged commits (short-sha + subject) whose message carries a conventional-commit scope for this
 * tempdoc number, e.g. `feat(770): …`, `docs(770): …`, `fix(770): …`. Precise on purpose: a bare
 * `(770)` in a commit body would over-report "merged". Returns [] on any git error (fail-open).
 */
function mergedCommitsForNumber(ref, num) {
  try {
    const out = execFileSync(
      'git',
      ['log', '--no-color', '--pretty=format:%h %s', '-E', `--grep=[a-z]+\\(${num}\\)`, ref],
      { cwd: REPO_ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    ).trim();
    return out ? out.split(/\r?\n/).filter(Boolean) : [];
  } catch {
    return [];
  }
}

function listTempdocs() {
  let entries;
  try {
    entries = readdirSync(TEMPDOCS, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((e) => e.isFile() && e.name.endsWith('.md'))
    .map((e) => join(TEMPDOCS, e.name))
    .sort();
}

const ref = resolveRef();
const stale = []; // marker present AND a merged PR references it → misleading
const pending = []; // marker present, no merged PR found → consistent (genuinely awaiting)

for (const file of listTempdocs()) {
  const text = readFileSync(file, 'utf8');
  const status = extractStatus(text);
  if (!status) continue;
  const lower = status.toLowerCase();
  const hitMarkers = MERGE_PENDING_MARKERS.filter((mk) => lower.includes(mk));
  if (hitMarkers.length === 0) continue;
  const num = tempdocNumber(file);
  if (!num) continue;
  const merged = mergedCommitsForNumber(ref, num);
  const record = {
    tempdoc: num,
    file: `docs/tempdocs/${basename(file)}`,
    markers: hitMarkers,
    status: status.length > 240 ? `${status.slice(0, 240)}…` : status,
    mergedCommits: merged,
  };
  if (merged.length > 0) stale.push(record);
  else pending.push(record);
}

if (JSON_OUT) {
  process.stdout.write(
    `${JSON.stringify({ ref, staleCount: stale.length, pendingCount: pending.length, stale, pending }, null, 2)}\n`,
  );
  process.exit(0);
}

console.log(`[tempdoc-status-staleness] scanned docs/tempdocs against ref '${ref}' (report-mode; always exits 0).`);
console.log('');

if (stale.length === 0) {
  console.log('No stale merge-pending markers found — every merge-pending status lacks a merged PR reference.');
} else {
  console.log(`STALE — ${stale.length} tempdoc(s) claim merge-pending but a merged PR references the number:`);
  for (const r of stale) {
    console.log(`  #${r.tempdoc} (${r.file})`);
    console.log(`    markers: ${r.markers.join(', ')}`);
    console.log(`    merged commit(s):`);
    for (const c of r.mergedCommits) console.log(`      ${c}`);
  }
  console.log('');
  console.log('Update these frontmatter statuses to reflect that the work merged.');
}

console.log('');
console.log(
  `Informational — ${pending.length} tempdoc(s) carry a merge-pending marker with NO merged PR reference (consistent / genuinely awaiting):`,
);
for (const r of pending) console.log(`  #${r.tempdoc} (${r.file}) — ${r.markers.join(', ')}`);

process.exit(0);
