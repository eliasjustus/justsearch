#!/usr/bin/env node
/**
 * Suppression-annotation ratchet (tempdoc 620 Part V — mechanizes the
 * detectable subset of `fix-root-causes-not-symptoms`).
 *
 * The rule forbids silencing a build/test failure by disabling the test or
 * suppressing the warning. Two of its anti-patterns are deterministically
 * detectable in a diff: `@Disabled` (a disabled test) and `// noinspection`
 * (a suppressed inspection). This ratchet bounds them per file: the count may
 * only shrink. A NEW `@Disabled`/`// noinspection` beyond the baseline fails —
 * forcing the agent to fix the root cause or justify + re-baseline.
 *
 * Deliberately EXCLUDES `@SuppressWarnings` (411 live uses — frequently legit in
 * production for unavoidable unchecked casts; a ratchet there would be noise,
 * 582 R1). The semantic anti-patterns (assertEquals->assertNotNull, catch
 * broadening, deleting failing code) stay prose — not reliably detectable.
 *
 *   node scripts/ci/check-suppression-ratchet.mjs           # == --check
 *   node scripts/ci/check-suppression-ratchet.mjs --check   # fail (exit 1) if any file over baseline
 *   node scripts/ci/check-suppression-ratchet.mjs --init     # establish baseline = current counts
 *   node scripts/ci/check-suppression-ratchet.mjs --rebalance # lower baseline to current (shrink only)
 *
 * Scope: `modules/<m>/src/**` *.java / *.ts / *.tsx. Standalone CI lint (the
 * ratchet-lint tier — not a kernel meta-gate; 582 R3-clean).
 */

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const BASELINE = resolve(REPO_ROOT, 'scripts/ci/suppression-ratchet-baseline.v1.json');
const MODULES = resolve(REPO_ROOT, 'modules');

// Anchored to line-start so it counts a real `@Disabled` / own-line `// noinspection`
// and EXCLUDES (a) conditional disables `@DisabledOnOs`/`@DisabledIf...` (legit env
// guards — the `\b` after "Disabled" rejects them) and (b) Javadoc/string references
// like `{@link Disabled @Disabled}` or `fail("see @Disabled")` (not line-start).
const SUPPRESSION = /^\s*(@Disabled\b|\/\/\s*noinspection\b)/i;

const mode = process.argv.includes('--init')
  ? 'init'
  : process.argv.includes('--rebalance')
    ? 'rebalance'
    : 'check';

/** Recursively collect *.java/*.ts/*.tsx under a dir. */
function collect(dir, out) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    const full = join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'build' || e.name === 'node_modules') continue;
      collect(full, out);
    } else if (/\.(java|ts|tsx)$/.test(e.name)) {
      out.push(full);
    }
  }
}

/** Per-file suppression counts, keyed by repo-relative POSIX path. */
function scan() {
  const counts = {};
  let moduleDirs = [];
  try {
    moduleDirs = readdirSync(MODULES, { withFileTypes: true })
      .filter((d) => d.isDirectory())
      .map((d) => join(MODULES, d.name, 'src'))
      .filter((p) => existsSync(p) && statSync(p).isDirectory());
  } catch {
    /* no modules dir */
  }
  const files = [];
  for (const src of moduleDirs) collect(src, files);
  for (const f of files) {
    let n = 0;
    try {
      for (const line of readFileSync(f, 'utf8').split(/\r?\n/)) {
        if (SUPPRESSION.test(line)) n++;
      }
    } catch {
      continue;
    }
    if (n > 0) {
      const rel = f.slice(REPO_ROOT.length + 1).replace(/\\/g, '/');
      counts[rel] = n;
    }
  }
  return counts;
}

const current = scan();
const total = Object.values(current).reduce((a, b) => a + b, 0);

if (mode === 'init' || mode === 'rebalance') {
  let baseline = { version: 1, files: {} };
  if (existsSync(BASELINE)) {
    try {
      baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));
    } catch {
      /* start fresh */
    }
  }
  const prev = baseline.files ?? {};
  let next;
  if (mode === 'init') {
    next = { ...current };
  } else {
    // rebalance: lower toward current, never raise; drop files that hit 0.
    next = {};
    for (const [f, c] of Object.entries(prev)) {
      const cur = current[f] ?? 0;
      if (cur > 0) next[f] = Math.min(c, cur);
    }
  }
  baseline.version = 1;
  baseline.files = next;
  baseline.$comment =
    'Suppression-annotation ratchet (tempdoc 620 Part V). Per-file @Disabled + // noinspection counts; may only shrink. A NEW one beyond baseline fails check-suppression-ratchet.mjs. Excludes @SuppressWarnings by design (noisy/legit in prod).';
  writeFileSync(BASELINE, JSON.stringify(baseline, null, 2) + '\n');
  console.log(
    `[suppression-ratchet] ${mode}: ${Object.keys(next).length} files, ${Object.values(next).reduce((a, b) => a + b, 0)} suppressions baselined.`,
  );
  process.exit(0);
}

// check mode
if (!existsSync(BASELINE)) {
  console.error('[suppression-ratchet] no baseline — run --init first.');
  process.exit(1);
}
const baseline = JSON.parse(readFileSync(BASELINE, 'utf8')).files ?? {};
const over = [];
for (const [f, c] of Object.entries(current)) {
  const ceiling = baseline[f] ?? 0;
  if (c > ceiling) over.push(`  ${f}: ${c} > baseline ${ceiling}`);
}
console.log(`[suppression-ratchet] scanned: ${Object.keys(current).length} files, ${total} suppressions (@Disabled + // noinspection).`);
if (over.length) {
  console.error('\n[suppression-ratchet] FAIL — new test-disable / inspection-suppression beyond baseline:');
  console.error(over.join('\n'));
  console.error('\nFix the root cause (do not silence the failure — fix-root-causes-not-symptoms). If the');
  console.error('suppression is genuinely justified, document why, then `--rebalance` is NOT enough (it only');
  console.error('shrinks) — bump intentionally via `--init` with the rationale in your commit.');
  process.exit(1);
}
console.log('[suppression-ratchet] pass — no new suppressions beyond baseline.');
