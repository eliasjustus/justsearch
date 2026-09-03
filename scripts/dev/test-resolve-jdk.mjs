#!/usr/bin/env node
/**
 * Tempdoc 696 — unit test for the dev JDK resolver (scripts/dev/lib/resolve-jdk.cjs).
 *
 * Pins the pure logic that must not silently regress: `java -version` major-version
 * parsing (legacy 1.8 and modern 25 formats), PATH candidate discovery, and the
 * "pick the first candidate whose java is >= MIN_MAJOR" selection. The unit cases
 * do not launch a real JDK — filesystem and version probes are injected.
 */
import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { parseJavaMajor, selectFromCandidates, javaHomesFromPath, MIN_MAJOR } = require(
  path.join(__dirname, 'lib', 'resolve-jdk.cjs'),
).__test;

let passed = 0;
const check = (name, fn) => {
  fn();
  passed += 1;
  console.log(`  ok  ${name}`);
};

// --- parseJavaMajor ---
check('parseJavaMajor: modern 25.0.2 → 25', () => {
  assert.equal(parseJavaMajor('openjdk version "25.0.2" 2025-10-21\nOpenJDK Runtime ...'), 25);
});
check('parseJavaMajor: legacy 1.8.0_492 → 8', () => {
  assert.equal(parseJavaMajor('java version "1.8.0_492"\nJava(TM) SE Runtime ...'), 8);
});
check('parseJavaMajor: bare major "21" → 21', () => {
  assert.equal(parseJavaMajor('openjdk version "21" 2023-09-19'), 21);
});
check('parseJavaMajor: 24.0.1 → 24', () => {
  assert.equal(parseJavaMajor('openjdk version "24.0.1"'), 24);
});
check('parseJavaMajor: garbage/empty → null', () => {
  assert.equal(parseJavaMajor('no version here'), null);
  assert.equal(parseJavaMajor(''), null);
  assert.equal(parseJavaMajor(null), null);
});

// --- selectFromCandidates (inject a probe keyed on the home name) ---
const majorByHome = {
  jdk8: 8,
  jdk17: 17,
  jdk23: 23,
  jdk24: 24,
  jdk25: 25,
  broken: null, // exists on disk but java won't run
};
// The candidates below use names that also exist as real dirs? No — selectFromCandidates
// checks fs.existsSync(javaExeIn(home)) BEFORE probing, so to unit-test selection purely we
// bypass existence by using a probe that also asserts existence-independence. Instead we test
// the probe-driven ranking via a wrapper that treats every candidate as existing.
function selectAssumingExist(candidates, probe) {
  // mirror selectFromCandidates' semantics but skip the fs.existsSync gate (pure ranking test)
  for (const home of candidates) {
    if (!home) continue;
    const major = probe(home);
    if (major != null && major >= MIN_MAJOR) return home;
  }
  return null;
}
const probe = (home) => majorByHome[path.basename(home)] ?? null;

check(`MIN_MAJOR is ${MIN_MAJOR} (>= 24)`, () => {
  assert.ok(MIN_MAJOR >= 24, `MIN_MAJOR should be >= 24, got ${MIN_MAJOR}`);
});
check('selection: first >= 24 wins, older/lower skipped', () => {
  assert.equal(selectAssumingExist(['jdk8', 'jdk17', 'jdk23', 'jdk25'], probe), 'jdk25');
  assert.equal(selectAssumingExist(['jdk24', 'jdk25'], probe), 'jdk24'); // first acceptable wins
});
check('selection: all < 24 → null', () => {
  assert.equal(selectAssumingExist(['jdk8', 'jdk17', 'jdk23'], probe), null);
});
check('selection: unprobeable (null) candidates skipped', () => {
  assert.equal(selectAssumingExist(['broken', 'jdk8', 'jdk25'], probe), 'jdk25');
});
check('selection: empty/falsey candidates skipped, empty list → null', () => {
  assert.equal(selectAssumingExist([null, '', 'jdk25'], probe), 'jdk25');
  assert.equal(selectAssumingExist([], probe), null);
});

// Sanity: the real selectFromCandidates rejects a non-existent home without throwing.
check('selectFromCandidates: non-existent home → null (no throw)', () => {
  assert.equal(selectFromCandidates([path.join(__dirname, 'no-such-jdk-xyz')]), null);
});

// --- javaHomesFromPath ---
check('javaHomesFromPath: scans past an obsolete Windows shim to a JDK bin', () => {
  const pathValue = 'C:\\Oracle\\java8path;"F:\\scoop\\apps\\temurin25-jdk\\current\\bin"';
  const existing = new Set([
    'C:\\Oracle\\java8path\\java.exe',
    'F:\\scoop\\apps\\temurin25-jdk\\current\\bin\\java.exe',
  ]);
  assert.deepEqual(
    javaHomesFromPath(
      pathValue,
      'win32',
      (candidate) => existing.has(candidate),
      (candidate) => candidate,
    ),
    ['C:\\Oracle', 'F:\\scoop\\apps\\temurin25-jdk\\current'],
  );
});

check('javaHomesFromPath: resolves a launcher symlink to its real JDK home', () => {
  assert.deepEqual(
    javaHomesFromPath(
      '/usr/bin:/missing',
      'linux',
      (candidate) => candidate === '/usr/bin/java',
      () => '/opt/jdk-25/bin/java',
    ),
    ['/opt/jdk-25'],
  );
});

check('javaHomesFromPath: empty PATH → no candidates', () => {
  assert.deepEqual(javaHomesFromPath('', 'win32'), []);
});

console.log(`\nresolve-jdk: ${passed} checks passed`);
