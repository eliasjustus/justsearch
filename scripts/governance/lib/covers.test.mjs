/**
 * Tests for the kernel covers protocol (tempdoc 576 §4) — especially the gate-supplied value
 * comparator that generalizes the bound direction (bytes lower-is-better vs strength higher-is-better).
 *
 * Run: `node scripts/governance/lib/covers.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { parseCovers, persistentlyCovers, coversBoundFor } from './covers.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

// parseCovers
ok('parseCovers null when absent', parseCovers({}) === null);
ok('parseCovers parses multi', JSON.stringify(parseCovers({ covers: 'a=10, b=20' })) === JSON.stringify({ a: 10, b: 20 }));

// Default direction (lower-is-better, e.g. bytes): covered iff current <= ceiling.
const lowerDecls = [{ classification: 'declared-growth', frontmatter: { covers: 'app_main_bytes=1000' } }];
ok('lower: at/under ceiling covered', persistentlyCovers(lowerDecls, 'app_main_bytes', 1000) === true);
ok('lower: over ceiling NOT covered', persistentlyCovers(lowerDecls, 'app_main_bytes', 1001) === false);
ok('lower: non-covering classification ignored', persistentlyCovers([{ classification: 'bundle-shrink', frontmatter: { covers: 'app_main_bytes=9999' } }], 'app_main_bytes', 10) === false);

// Higher-is-better direction (e.g. mutation strength): covered iff current >= floor.
const higherDecls = [{ classification: 'strength-regression', frontmatter: { covers: 'seamX=80' } }];
const higherOpts = { coveringClassifications: ['strength-regression'], withinBound: (cur, floor) => cur >= floor };
ok('higher: at/above floor covered', persistentlyCovers(higherDecls, 'seamX', 80, higherOpts) === true);
ok('higher: below floor NOT covered', persistentlyCovers(higherDecls, 'seamX', 79, higherOpts) === false);

// coversBoundFor (for the loud, self-explaining revoke message)
ok('coversBoundFor returns the declared bound', coversBoundFor(lowerDecls, 'app_main_bytes') === 1000);
ok('coversBoundFor null when no covers', coversBoundFor([{ classification: 'declared-growth', frontmatter: {} }], 'x') === null);

if (failures.length > 0) {
  console.error(`covers.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`covers.test: all ${passed} checks passed`);
