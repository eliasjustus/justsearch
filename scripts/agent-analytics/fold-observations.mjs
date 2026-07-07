#!/usr/bin/env node

/**
 * Fold per-session observation shards into the GROUPED conditions store
 * (tempdoc 618 Seam C write path + tempdoc 680 identity-at-the-fold).
 *
 * Writers stay blind and flat: note-observation.mjs appends one-liners to a
 * per-session shard under docs/observations.d/ — contention-free by construction,
 * unchanged. This tool resolves IDENTITY at the store: each shard entry either
 * merges into an existing condition in docs/observations.md `## Conditions`
 * (occurrence appended, `seen` incremented — recurrence is the ranking signal,
 * not a rule violation) or opens a new condition with a PROPOSED kind
 * (trailing `?`) for the triage pass to confirm.
 *
 *   node scripts/agent-analytics/fold-observations.mjs            # dry run (default)
 *   node scripts/agent-analytics/fold-observations.mjs --apply    # write + delete folded shards
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
 * Fold all shards into the conditions store.
 * @returns {{folded:number, entries:number, merged:number, opened:number,
 *            unchangedDupes:number, proposedKinds:number, shards:string[], changed:boolean}}
 */
export function foldShards({ root = repoRoot, apply = false } = {}) {
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
  const r = foldShards({ apply });
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
