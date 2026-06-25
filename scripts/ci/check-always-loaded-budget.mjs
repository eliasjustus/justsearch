#!/usr/bin/env node
/**
 * Always-loaded agent-doc budget ratchet (tempdoc 620 Move 3).
 *
 * CLAUDE.md + every .claude/rules/*.md is injected into EVERY agent session
 * unconditionally — it taxes every task regardless of relevance. The Part I.E
 * finding: the existing bloat guard (prose-tier-register's sentence-scan) only
 * governs ANCHORED rule sentences, so the largest bloat (the Pre-merge list,
 * common-workflows procedures) grew invisible to it. This ratchet closes that
 * hole by bounding the always-loaded BYTE budget, the same shape as the
 * class-size / ui-bundle / npm-audit ratchets: a per-file ceiling that only
 * shrinks.
 *
 *   node scripts/ci/check-always-loaded-budget.mjs            # default == --check
 *   node scripts/ci/check-always-loaded-budget.mjs --check    # fail (exit 1) if any file over ceiling
 *   node scripts/ci/check-always-loaded-budget.mjs --rebalance # shrink ceilings to current sizes
 *   node scripts/ci/check-always-loaded-budget.mjs --bump <file> --reason "<why>" # declare a justified growth
 *
 * The ratchet is one-directional: --rebalance only LOWERS a ceiling (a file that
 * shrank). A file that grew past its ceiling is trimmed or migrated out
 * (Moves 1/2) by default — OR, when the growth is genuinely justified (a real new
 * mechanism that must live in an always-loaded rule), declared with --bump, which
 * raises that one ceiling to current size and records the reason in
 * `baseline.bumps` (tempdoc 618 §13: an explicit, auditable raise instead of
 * silent ceiling-JSON surgery). ~tokens reported as bytes/4.
 *
 * 582 R3 ("freeze the meta-tier count") note: this is a standalone CI lint, the
 * same tier as the doc-gen `--check` family — not a new kernel discipline-gate.
 * The design's R3-clean home is folding the same check into the existing
 * prose-tier-register enforcer; that wiring is a follow-up once the doc set
 * stabilizes (see the baseline `$comment`).
 */

import { readFileSync, writeFileSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const BASELINE = resolve(REPO_ROOT, 'scripts/ci/always-loaded-budget.v1.json');

const mode = process.argv.includes('--bump')
  ? 'bump'
  : process.argv.includes('--rebalance')
    ? 'rebalance'
    : 'check';

/** Value following a flag (`--bump <v>` / `--reason <v>`), or null. */
function argValue(flag) {
  const i = process.argv.indexOf(flag);
  const v = i >= 0 ? process.argv[i + 1] : null;
  return v && !v.startsWith('--') ? v : null;
}

function sizeOf(rel) {
  try {
    return statSync(resolve(REPO_ROOT, rel)).size;
  } catch {
    return null; // missing file — reported below
  }
}

const baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));
const ceilings = baseline.ceilings ?? {};
const tok = (b) => Math.round(b / 4);

const rows = Object.keys(ceilings).map((rel) => {
  const ceiling = ceilings[rel];
  const actual = sizeOf(rel);
  return { rel, ceiling, actual };
});

if (mode === 'rebalance') {
  let shrunk = 0;
  for (const r of rows) {
    if (r.actual != null && r.actual < r.ceiling) {
      ceilings[r.rel] = r.actual;
      shrunk++;
    }
  }
  baseline.ceilings = ceilings;
  baseline.totalCeiling = Object.values(ceilings).reduce((a, b) => a + b, 0);
  writeFileSync(BASELINE, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`[always-loaded-budget] rebalanced: ${shrunk} ceiling(s) lowered; total ceiling now ${baseline.totalCeiling} bytes (~${tok(baseline.totalCeiling)} tok).`);
  process.exit(0);
}

if (mode === 'bump') {
  // Declared-growth path (tempdoc 618 §13): a JUSTIFIED always-loaded growth (a
  // real new mechanism that must be documented in an always-loaded rule) raises
  // ONE file's ceiling to its current size and records WHY in `baseline.bumps`.
  // This replaces silent hand-editing of the ceiling JSON with an explicit,
  // auditable raise — while `--rebalance` stays shrink-only and `--check` still
  // fails undeclared growth. Never lowers a ceiling (that is --rebalance's job).
  const file = argValue('--bump');
  const reason = argValue('--reason');
  if (!file) {
    console.error('--bump requires a file: --bump <path> [--reason "<why the growth is justified>"]');
    process.exit(2);
  }
  if (!(file in ceilings)) {
    console.error(`--bump: "${file}" is not an always-loaded file in the baseline.`);
    process.exit(2);
  }
  const actual = sizeOf(file);
  if (actual == null) {
    console.error(`--bump: "${file}" not found on disk.`);
    process.exit(2);
  }
  if (actual <= ceilings[file]) {
    console.log(`[always-loaded-budget] "${file}" (${actual} B) already within its ceiling ${ceilings[file]} B — no bump needed (use --rebalance to shrink).`);
    process.exit(0);
  }
  const from = ceilings[file];
  ceilings[file] = actual;
  baseline.ceilings = ceilings;
  baseline.totalCeiling = Object.values(ceilings).reduce((a, b) => a + b, 0);
  baseline.bumps = baseline.bumps ?? [];
  baseline.bumps.push({ file, from, to: actual, reason: reason ?? null, ts: new Date().toISOString() });
  writeFileSync(BASELINE, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`[always-loaded-budget] bumped "${file}": ${from} → ${actual} B (+${actual - from}); total ceiling now ${baseline.totalCeiling} B (~${tok(baseline.totalCeiling)} tok). Recorded in baseline.bumps.`);
  if (!reason) {
    console.error('WARN: no --reason given — a bump should record WHY this always-loaded growth is justified (it taxes every session).');
  }
  process.exit(0);
}

// check mode
const over = [];
let total = 0;
for (const r of rows) {
  if (r.actual == null) {
    over.push(`  MISSING  ${r.rel} (in baseline, not on disk)`);
    continue;
  }
  total += r.actual;
  const slack = r.ceiling - r.actual;
  const flag = slack < 0 ? 'OVER ' : 'ok   ';
  if (slack < 0) {
    over.push(`  OVER     ${r.rel}: ${r.actual} B (~${tok(r.actual)} tok) > ceiling ${r.ceiling} B by ${-slack} B`);
  }
  console.log(`  ${flag} ${r.rel.padEnd(34)} ${String(r.actual).padStart(6)} / ${r.ceiling} B`);
}
console.log(`  ---- total ${total} B (~${tok(total)} tok) / ceiling ${baseline.totalCeiling} B`);

if (over.length) {
  console.error('\n[always-loaded-budget] FAIL — always-loaded docs grew past their ratchet ceiling:');
  console.error(over.join('\n'));
  console.error('\nThe always-loaded set taxes every session. Trim the file, or migrate the addition out');
  console.error('(a skill, the consult-register, a generated projection — tempdoc 620 Moves 1/2). Only run');
  console.error('--rebalance when a file genuinely SHRANK; the ceiling never ratchets up.');
  process.exit(1);
}
console.log('\n[always-loaded-budget] pass — every always-loaded file within its ratchet ceiling.');
