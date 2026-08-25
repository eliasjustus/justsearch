#!/usr/bin/env node

/**
 * Fold per-session observation shards into the GROUPED conditions store
 * (tempdoc 618 Seam C write path + tempdoc 680 identity-at-the-fold).
 *
 * Writers stay blind and flat: note-observation.mjs appends one-liners to a
 * per-writer shard under docs/observations.d/ (keyed by session AND writing tree
 * since tempdoc 862) — contention-free by construction, unchanged. The fold needs
 * no knowledge of that key: listShards GLOBS the directory and never parses a
 * shard's name. This tool resolves IDENTITY at the store: each shard entry either
 * merges into an existing condition in docs/observations.md `## Conditions`
 * (occurrence appended, `seen` incremented — recurrence is the ranking signal,
 * not a rule violation) or opens a new condition with a PROPOSED kind
 * (trailing `?`) for the triage pass to confirm.
 *
 *   node scripts/agent-analytics/fold-observations.mjs               # dry run (default)
 *   node scripts/agent-analytics/fold-observations.mjs --apply       # write + delete folded shards
 *   node scripts/agent-analytics/fold-observations.mjs --allow-stale # skip the base-freshness guard
 *
 * Properties (unchanged from the 618/665 fold):
 *  - Writes observations.md FIRST, then deletes the consumed shards — a crash
 *    between the two leaves shards intact (re-runnable, no loss).
 *  - Idempotent: exact-occurrence dedupe inside each condition means a surviving
 *    shard (e.g. a failed delete) cannot double-count on a later run.
 *  - Correctness of the data does NOT depend on this tool: every shard is a
 *    committed file in git; the fold is consolidation, not durability.
 *
 * Run at the documented merge-teardown boundary (next to record-merge.mjs).
 * Retirement of conditions is NOT this tool's job — observations-triage.mjs
 * proposes retirements (probe-derived); a human applies deletions.
 */

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { repoRoot } from './lib/telemetry-io.mjs';
import { SHARD_DIR } from './note-observation.mjs';
import {
  parseStore, serializeStore, matchGroup, mergeOccurrence, newGroupFrom,
} from './lib/observations-store.mjs';

export const INBOX_FILE = 'docs/observations.md';
const ENTRY_RE = /^- \[[ xX]\] /;

/** List shard files (full paths) under docs/observations.d/, excluding README/.gitkeep. */
export function listShards(root = repoRoot) {
  const dir = path.join(root, SHARD_DIR);
  let names;
  try {
    names = fs.readdirSync(dir);
  } catch {
    return [];
  }
  return names
    .filter((n) => n.endsWith('.md') && n.toLowerCase() !== 'readme.md')
    .map((n) => path.join(dir, n))
    .sort();
}

/** Extract flat entry lines (`- [ ] …`) from a shard's text. */
export function entriesFromShard(text) {
  return text.split(/\r?\n/).filter((l) => ENTRY_RE.test(l)).map((l) => l.replace(/\s+$/, ''));
}

/**
 * Is the store's checkout a DESCENDANT of `origin/main`?  (tempdoc 814 §D8.4 / §R.2)
 *
 * The fold REWRITES the shared conditions store. Run from a checkout that is behind
 * `origin/main`, it serializes a store built from a stale parse — every condition another
 * session added since then is silently dropped from the written file, and the consumed shards
 * are deleted right after, so the loss is not recoverable from the shards either. The
 * precondition belongs here, beside the malformed-store refusal, for the same reason: both are
 * "this checkout cannot safely produce the next store".
 *
 * Tri-state, deliberately: `git merge-base --is-ancestor` exits 0 (ancestor → fresh) or 1 (NOT an
 * ancestor → stale). ANY other outcome (not a git repo — which is what the unit tests' tmp roots
 * are; no `origin/main` ref; git missing) is INDETERMINATE, and an indeterminate check must not
 * invent a refusal: `null` means "no opinion" and the fold proceeds. This tool never fetches —
 * refreshing the remote is the caller's decision, not a side effect of folding.
 *
 * @returns {boolean|null} true = fresh, false = stale, null = undeterminable
 */
export function isBaseFresh(root = repoRoot) {
  try {
    execFileSync('git', ['rev-parse', '--verify', '--quiet', 'origin/main'], {
      cwd: root, stdio: 'ignore',
    });
  } catch {
    return null; // no origin/main ref (or not a repo) — nothing to compare against
  }
  try {
    execFileSync('git', ['merge-base', '--is-ancestor', 'origin/main', 'HEAD'], {
      cwd: root, stdio: 'ignore',
    });
    return true;
  } catch (e) {
    return e && e.status === 1 ? false : null;
  }
}

/**
 * Fold all shards into the conditions store.
 * @returns {{folded:number, entries:number, merged:number, opened:number,
 *            unchangedDupes:number, proposedKinds:number, shards:string[], changed:boolean}}
 */
export function foldShards({ root = repoRoot, apply = false, allowStale = false } = {}) {
  const shards = listShards(root);
  const storePath = path.join(root, INBOX_FILE);
  const storeText = fs.readFileSync(storePath, 'utf8');
  const store = parseStore(storeText);
  if (store === null) {
    throw new Error(
      `fold-observations: ${INBOX_FILE} has no '## Conditions' section — the store predates the ` +
        'tempdoc-680 grouped format. Migrate it first (see tempdoc 680) or update this checkout.',
    );
  }
  if (!allowStale && isBaseFresh(root) === false) {
    throw new Error(
      'fold-observations: this checkout is NOT a descendant of origin/main, so folding would ' +
        `rewrite ${INBOX_FILE} from a stale parse and drop every condition landed since — then ` +
        'delete the shards that carried them. Remedy: update the checkout first (`git fetch ' +
        'origin && git merge origin/main`, or run the fold from an up-to-date main), then re-run. ' +
        'Pass --allow-stale (or allowStale: true) only when folding a deliberately old base.',
    );
  }

  const allEntries = [];
  for (const s of shards) allEntries.push(...entriesFromShard(fs.readFileSync(s, 'utf8')));

  const result = {
    folded: shards.length, entries: allEntries.length,
    merged: 0, opened: 0, unchangedDupes: 0, proposedKinds: 0,
    shards, changed: false,
  };
  if (allEntries.length === 0) return result;

  const slugs = new Set(store.groups.map((g) => g.slug));
  for (const entry of allEntries) {
    const hit = matchGroup(store.groups, entry);
    if (hit) {
      if (mergeOccurrence(hit, entry)) result.merged += 1;
      else result.unchangedDupes += 1;
    } else {
      const g = newGroupFrom(entry, slugs);
      slugs.add(g.slug);
      store.groups.push(g);
      result.opened += 1;
    }
  }
  result.proposedKinds = store.groups.filter((g) => /\?$/.test(g.fields.kind || '')).length;

  const next = serializeStore(store);
  result.changed = next !== storeText;
  if (apply) {
    if (result.changed) fs.writeFileSync(storePath, next, 'utf8'); // write FIRST
    for (const s of shards) fs.rmSync(s, { force: true }); // then delete consumed shards
  }
  return result;
}

function main() {
  const apply = process.argv.includes('--apply');
  const allowStale = process.argv.includes('--allow-stale');
  const r = foldShards({ apply, allowStale });
  if (r.entries === 0) {
    console.log('fold-observations: no shard entries to fold.');
  } else {
    console.log(
      `fold-observations: ${apply ? 'folded' : 'would fold'} ${r.entries} entr${r.entries === 1 ? 'y' : 'ies'} ` +
        `from ${r.folded} shard(s) — ${r.merged} merged into existing conditions, ${r.opened} new condition(s) opened` +
        `${r.unchangedDupes ? `, ${r.unchangedDupes} exact duplicate(s) skipped` : ''}` +
        `${apply ? '; shards removed.' : ' [dry run — pass --apply].'}`,
    );
  }
  if (r.proposedKinds > 0) {
    console.log(
      `fold-observations: ${r.proposedKinds} condition(s) carry a proposed kind ('kind: …?') — ` +
        'confirm them at the next triage pass (node scripts/agent-analytics/observations-triage.mjs).',
    );
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  main();
}
