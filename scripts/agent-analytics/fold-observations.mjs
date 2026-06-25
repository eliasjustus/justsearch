#!/usr/bin/env node

/**
 * Reconcile per-session observation shards into docs/observations.md `## Inbox`
 * (tempdoc 618 Seam C). The everyday write path is note-observation.mjs, which
 * appends to a per-session shard under docs/observations.d/ — contention-free by
 * construction. This tool folds those shards into the curated single-file inbox
 * at a boundary (run manually, or at merge next to record-merge.mjs).
 *
 *   node scripts/agent-analytics/fold-observations.mjs            # dry run (default)
 *   node scripts/agent-analytics/fold-observations.mjs --apply    # write + delete folded shards
 *
 * Properties:
 *  - Append-only into `## Inbox`; never rewrites existing entries.
 *  - Deduplicates against entries already present (so a surviving shard, e.g. a
 *    failed delete, cannot reintroduce a duplicate on a later run).
 *  - Writes observations.md FIRST, then deletes the consumed shards — so a crash
 *    between the two leaves shards intact (re-runnable, no loss).
 *  - Correctness of the data does NOT depend on this tool: every shard is a
 *    committed file in git; the fold is consolidation, not durability.
 */

import fs from 'node:fs';
import path from 'node:path';
import { repoRoot } from './lib/telemetry-io.mjs';
import { SHARD_DIR } from './note-observation.mjs';

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

/** Extract inbox entry lines (`- [ ] …`) from a shard's text. */
export function entriesFromShard(text) {
  return text.split(/\r?\n/).filter((l) => ENTRY_RE.test(l)).map((l) => l.replace(/\s+$/, ''));
}

/**
 * Insert entry lines at the END of the `## Inbox` section of observations.md
 * (immediately before the next sibling `## ` heading, or EOF). Returns the new
 * file text, or null if `## Inbox` is absent. Skips entries already present
 * (exact-line dedupe).
 */
export function insertIntoInbox(inboxText, entries) {
  const lines = inboxText.split(/\r?\n/);
  const inboxIdx = lines.findIndex((l) => /^##\s+Inbox\b/.test(l));
  if (inboxIdx === -1) return null;
  // Find the next sibling `## ` heading after Inbox → end of the Inbox section.
  let endIdx = lines.length;
  for (let i = inboxIdx + 1; i < lines.length; i++) {
    if (/^##\s+/.test(lines[i])) { endIdx = i; break; }
  }
  const existing = new Set(lines.map((l) => l.replace(/\s+$/, '')));
  const fresh = entries.filter((e) => !existing.has(e));
  if (fresh.length === 0) return inboxText; // nothing new — idempotent no-op
  // Trim trailing blank lines inside the section, then append fresh entries.
  let insertAt = endIdx;
  while (insertAt > inboxIdx + 1 && lines[insertAt - 1].trim() === '') insertAt--;
  const block = ['', ...fresh];
  const next = [...lines.slice(0, insertAt), ...block, ...lines.slice(insertAt)];
  return next.join('\n');
}

/**
 * Fold all shards into the inbox.
 * @returns {{folded: number, entries: number, shards: string[], changed: boolean}}
 */
export function foldShards({ root = repoRoot, apply = false } = {}) {
  const shards = listShards(root);
  const inboxPath = path.join(root, INBOX_FILE);
  const inboxText = fs.readFileSync(inboxPath, 'utf8');
  const allEntries = [];
  for (const s of shards) {
    allEntries.push(...entriesFromShard(fs.readFileSync(s, 'utf8')));
  }
  const result = { folded: shards.length, entries: allEntries.length, shards, changed: false };
  if (allEntries.length === 0) return result;

  const next = insertIntoInbox(inboxText, allEntries);
  if (next === null) throw new Error(`fold-observations: '## Inbox' not found in ${INBOX_FILE}`);
  result.changed = next !== inboxText;

  if (apply) {
    if (result.changed) fs.writeFileSync(inboxPath, next, 'utf8'); // write FIRST
    for (const s of shards) fs.rmSync(s, { force: true }); // then delete consumed shards
  }
  return result;
}

function main() {
  const apply = process.argv.includes('--apply');
  const r = foldShards({ apply });
  if (r.entries === 0) {
    console.log('fold-observations: no shard entries to fold.');
    return;
  }
  console.log(
    `fold-observations: ${apply ? 'folded' : 'would fold'} ${r.entries} entr${r.entries === 1 ? 'y' : 'ies'} ` +
      `from ${r.folded} shard(s)${r.changed ? '' : ' (all already present — no change)'}${apply ? '; shards removed.' : ' [dry run — pass --apply].'}`,
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  main();
}
