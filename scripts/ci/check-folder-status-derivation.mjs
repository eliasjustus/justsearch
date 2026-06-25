#!/usr/bin/env node
/**
 * folder-status-derivation gate — tempdoc 599 §9.1/§9.5 (the per-folder status anti-drift teeth,
 * mirroring check-verdict-derivation / check-search-issuance).
 *
 * The §8.1 defect: a folder showed "✓ indexed" derived from the walk-completion timestamp, NOT from
 * job drain. 599 collapses the folder's truthful state into ONE derivation — `folderStatus` — that
 * decides `ready` from `inFlight === 0 && failed === 0`. The Library row glyph + meta line CONSUME it.
 * This gate keeps it so:
 *
 *  1. SEAM integrity — `folderStatus.ts` defines `folderStatus` and reads the per-folder count fields
 *     (`inFlightCount`/`failedCount`). A rename/delete of the seam fails here, so the gate cannot be
 *     defeated by removing what it guards.
 *  2. SINGLE derivation — within `shell-v0`, the raw count fields appear ONLY in the allow-listed seam.
 *     Any other file reading `inFlightCount`/`failedCount` is forming a second folder verdict (the
 *     false-terminal split re-introduced) → fail. Consumers read the `folderStatus(...)` result.
 *
 * Honest limit (same as the sibling gates): a verdict re-derived from a DIFFERENT field is
 * import-invisible to this substring scan — register + discipline, not absolute. Comments stripped.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/folder-status-derivation.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');

const stripComments = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const predicate = new RegExp(reg.predicatePattern);
const failures = [];

// 1. SEAM integrity — the seam exists and reads the per-folder count fields.
{
  const seamFile = norm(reg.seam.file);
  const src = stripComments(readFileSync(seamFile, 'utf8'));
  if (!new RegExp(`\\b${reg.seam.symbol}\\b`).test(src)) {
    failures.push(
      `${seamFile}: the folder-status seam \`${reg.seam.symbol}\` is missing — the ONE per-folder ` +
        `status derivation (599 §9.1) must exist here; do not remove what the gate guards.`,
    );
  }
  if (!predicate.test(src)) {
    failures.push(
      `${seamFile}: the seam no longer reads the per-folder count fields (\`${reg.predicatePattern}\`) — ` +
        `\`ready\` must be derived from job drain here (599 §9.1), not the walk timestamp.`,
    );
  }
}

// 2. SINGLE derivation — the count fields appear only in allow-listed files.
const allowed = new Set(reg.allowed.map(norm));
const walk = (dir, acc) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, acc);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) acc.push(norm(p));
  }
  return acc;
};
for (const f of walk(reg.scope, [])) {
  if (allowed.has(f)) continue;
  const src = stripComments(readFileSync(f, 'utf8'));
  if (predicate.test(src)) {
    failures.push(
      `${f}: reads the raw per-folder count fields (\`inFlightCount\`/\`failedCount\`) directly — ` +
        `every surface must CONSUME the one \`${reg.seam.symbol}\` derivation, not recompute a folder ` +
        `state (599 §9.1; the §8.1 false-terminal split). Call \`folderStatus(row, …)\`, or (if a ` +
        `declared non-status reader) add the file to \`allowed\` in ${REGISTER}.`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ folder-status-derivation gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ folder-status-derivation gate OK — folder status is derived ONCE in \`${reg.seam.symbol}\` ` +
    `(599 §9.1); no second per-folder verdict site in ${reg.scope}.`,
);
