#!/usr/bin/env node
/**
 * check-accent-as-text — tempdoc 576 §6 rung-1 anti-growth lint.
 *
 * The accent `--accent-<role>` tokens are FILLS, not text — using one as `color:` (text) is the #3
 * amber-as-text bug-class. The proper text-grade is `--text-<role>` (legible on the surface, gate-
 * verified by check-contrast-matrix). A blanket ban would require migrating the existing ~scores of
 * legacy sites at once (disproportionate, and many are accent-on-dark which read fine); instead this
 * RATCHETS the per-file count: it may only DECREASE. New accent-as-text fails; migrating an existing
 * site to `--text-<role>` shrinks the baseline. `--rebalance` writes the (lower) baseline.
 *
 * Only standalone `color:` (text) is counted — `border-color`/`background-color` (a border/fill using
 * an accent) are legitimate and excluded; `--accent-on-*` (the on-grade) is excluded.
 *
 * Usage: node scripts/ci/check-accent-as-text.mjs [--rebalance]
 */
import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { extname, join, resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SRC = resolve(REPO_ROOT, 'modules/ui-web/src');
const BASELINE = resolve(REPO_ROOT, 'scripts/ci/accent-as-text-baseline.v1.json');

// standalone `color:` (not -color/word-color) set to an accent FILL var (not --accent-on-*).
const ACCENT_AS_TEXT = /(?<![-a-z])color:\s*var\(\s*--accent-(?!on-)/g;

/** Count accent-fill-as-text occurrences in a source string. Exported for the unit test. */
export function countAccentAsText(content) {
  const m = String(content).match(ACCENT_AS_TEXT);
  return m ? m.length : 0;
}

function srcFiles() {
  const out = [];
  (function walk(d) {
    for (const e of readdirSync(d)) {
      const p = join(d, e);
      const s = statSync(p);
      if (s.isDirectory()) {
        if (e !== 'node_modules' && e !== 'generated') walk(p);
      } else if (['.ts', '.tsx'].includes(extname(p)) && !/\.(test|spec)\.tsx?$/.test(p)) {
        out.push(p);
      }
    }
  })(SRC);
  return out;
}

function main() {
  const rebalance = process.argv.includes('--rebalance');
  const live = {};
  for (const f of srcFiles()) {
    const n = countAccentAsText(readFileSync(f, 'utf8'));
    if (n > 0) live[relative(REPO_ROOT, f).replace(/\\/g, '/')] = n;
  }

  if (rebalance) {
    writeFileSync(BASELINE, JSON.stringify(live, null, 2) + '\n');
    const total = Object.values(live).reduce((a, b) => a + b, 0);
    console.log(`check-accent-as-text: wrote baseline (${total} occurrences across ${Object.keys(live).length} files).`);
    return;
  }

  const baseline = existsSync(BASELINE) ? JSON.parse(readFileSync(BASELINE, 'utf8')) : {};
  const violations = [];
  for (const [file, n] of Object.entries(live)) {
    const allowed = baseline[file] ?? 0;
    if (n > allowed) {
      violations.push(`${file}: ${n} accent-fill-as-text uses > baseline ${allowed} — use --text-<role> instead.`);
    }
  }
  if (violations.length > 0) {
    console.error(`check-accent-as-text FAIL — accent FILLs used as text grew (tempdoc 576 §6 rung-1):`);
    for (const v of violations) console.error(`  ✗ ${v}`);
    console.error(`  Fix: replace color: var(--accent-<role>) with color: var(--text-<role>). Then --rebalance.`);
    process.exit(1);
  }
  const total = Object.values(live).reduce((a, b) => a + b, 0);
  console.log(`check-accent-as-text OK — no new accent-fill-as-text (${total} legacy occurrences, baselined, ratcheting down).`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
