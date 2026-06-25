#!/usr/bin/env node
/**
 * verdict-derivation gate — tempdoc 595 §4.2 (the observed-state verdict's
 * anti-drift teeth, mirroring check-search-issuance).
 *
 * 595 collapsed the system-health verdict (computed in ≥4 places over readiness:
 * the Health header `allGreen`, the footer `degraded` rollup, `readinessNotice`,
 * and — ignoring readiness entirely — the status-bar tier/label) into ONE derived
 * `SystemHealthVerdict` on aiStateStore. The header/footer/status-bar now CONSUME
 * it. This gate keeps it so:
 *
 *  1. SEAM integrity — the seam still exists and still holds the predicate:
 *     `verdict.ts` defines `computeVerdict` and reads `readiness.retrieval`.
 *     (A rename/delete of the seam fails here, so the gate cannot be defeated by
 *     removing what it guards.)
 *  2. SINGLE derivation — within the surface layer (`shell-v0`), the
 *     verdict-forming predicate (`retrieval === …` / `retrieval !== …`) appears
 *     ONLY in the allow-listed seam + the declared search-banner projection
 *     (`readinessNotice.ts`). Any other file forming a verdict from
 *     `readiness.retrieval` is the §1.1 split re-introduced → fail.
 *
 * A NEW consumer reads `aiStateStore.verdict`; it does not re-read
 * `readiness.retrieval`. Honest limit (same as the issuance / steering gates): a
 * verdict re-derived from a DIFFERENT field is import-invisible to this
 * substring scan — register + discipline, not absolute. Comments are stripped so
 * doc mentions do not match.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/verdict-derivation.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');

const stripComments = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const predicate = new RegExp(reg.predicatePattern);
const failures = [];

// 1. SEAM integrity — the seam exists and holds the verdict predicate.
{
  const seamFile = norm(reg.seam.file);
  const src = stripComments(readFileSync(seamFile, 'utf8'));
  if (!new RegExp(`\\b${reg.seam.symbol}\\b`).test(src)) {
    failures.push(
      `${seamFile}: the verdict seam \`${reg.seam.symbol}\` is missing — the ONE verdict ` +
        `derivation (595 §4.2) must exist here; do not remove what the gate guards.`,
    );
  }
  if (!predicate.test(src)) {
    failures.push(
      `${seamFile}: the seam no longer reads the readiness predicate (\`${reg.predicatePattern}\`) — ` +
        `the verdict's degradation axis must be derived here (595 §4.2).`,
    );
  }
}

// 2. SINGLE derivation — the predicate appears only in allow-listed files.
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
      `${f}: forms a verdict from \`readiness.retrieval\` directly — every surface must CONSUME ` +
        `the one \`${reg.seam.symbol}\` verdict (aiStateStore.verdict), not recompute it (595 §4.2; ` +
        `the §1.1 split). Read \`aiState.verdict\`, or (if a declared non-verdict projection) add the ` +
        `file to \`allowed\` in ${REGISTER}.`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ verdict-derivation gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ verdict-derivation gate OK — the system-health verdict is derived ONCE in \`${reg.seam.symbol}\` ` +
    `(595 §4.2); no second readiness-verdict site in ${reg.scope}.`,
);
