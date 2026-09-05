#!/usr/bin/env node
/* eslint-disable no-warning-comments -- this file's SUBJECT is the marker vocabulary; it has to
   name the terms it enforces, and spelling them around the rule would make the doc worse. The
   exemption is deliberately file-scoped: a genuine marker added here is on the author. */
/**
 * TODO/FIXME/XXX/HACK coverage for `*.ps1` — the PowerShell half of the retired `todo-fixme`
 * kernel gate's successor (tempdoc 930 §22.2 follow-up 3). The JS half is ESLint
 * `no-warning-comments` (root `eslint.config.mjs`); this mirrors its terms and its
 * suppression-list shape, since ESLint cannot parse PowerShell.
 *
 * Existing markers live in `ps1-warning-comments-suppressions.json`
 * (`{ "<path>": { "no-warning-comments": { "count": N } } }`) — a NEW marker fails.
 * Regenerate after removing one: `node scripts/ci/check-ps1-warning-comments.mjs --suppress-all`.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(HERE, '..', '..');
export const SUPPRESSIONS = path.join(HERE, 'ps1-warning-comments-suppressions.json');
const TERMS = /\b(todo|fixme|xxx|hack)\b/i;

/** Marker hits per file, keyed by repo-relative POSIX path. */
export function scan(root = REPO_ROOT, files = null) {
  const list =
    files ??
    execFileSync('git', ['ls-files', '*.ps1'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
  const found = {};
  for (const rel of list) {
    let block = false;
    fs.readFileSync(path.join(root, rel), 'utf8').split(/\r?\n/).forEach((line, i) => {
      const hash = line.indexOf('#');
      const comment = block ? line : hash === -1 ? '' : line.slice(hash);
      if (TERMS.test(comment)) (found[rel] ??= []).push({ line: i + 1, text: line.trim().slice(0, 110) });
      if (line.includes('<#')) block = true;
      if (line.includes('#>')) block = false;
    });
  }
  return found;
}

/** Hits minus the suppressed count per file; a file over its pinned count reports the excess. */
export function unsuppressed(found, suppressions) {
  return Object.entries(found)
    .map(([rel, hits]) => [rel, hits.slice(suppressions[rel]?.['no-warning-comments']?.count ?? 0)])
    .filter(([, hits]) => hits.length > 0);
}

function main() {
  const found = scan();
  if (process.argv.includes('--suppress-all')) {
    const pinned = Object.fromEntries(
      Object.entries(found).map(([rel, hits]) => [rel, { 'no-warning-comments': { count: hits.length } }]),
    );
    fs.writeFileSync(SUPPRESSIONS, `${JSON.stringify(pinned, null, 2)}\n`, 'utf8');
    console.log(`check-ps1-warning-comments: wrote ${Object.keys(pinned).length} suppression entries`);
    return;
  }
  const suppressions = fs.existsSync(SUPPRESSIONS) ? JSON.parse(fs.readFileSync(SUPPRESSIONS, 'utf8')) : {};
  const excess = unsuppressed(found, suppressions);
  if (excess.length === 0) {
    console.log('check-ps1-warning-comments: OK');
    return;
  }
  console.error('check-ps1-warning-comments: FAIL');
  for (const [rel, hits] of excess) {
    for (const hit of hits) console.error(`  ${rel}:${hit.line}  ${hit.text}`);
  }
  console.error('Resolve the marker, or pin it with --suppress-all if it is tracked work.');
  process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
