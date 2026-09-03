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
import fs from 'node:fs';
import os from 'node:os';
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

// --- selectFromCandidates (real existence gate, injected version probe) ---
const majorByHome = {
  jdk8: 8,
  jdk17: 17,
  jdk23: 23,
  jdk24: 24,
  jdk25: 25,
  broken: null, // exists on disk but java won't run
};
function withFakeHomes(names, run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-jdk-selection-'));
  const homes = Object.fromEntries(names.map((name) => {
    const home = path.join(root, name);
    const bin = path.join(home, 'bin');
    fs.mkdirSync(bin, { recursive: true });
    fs.writeFileSync(path.join(bin, process.platform === 'win32' ? 'java.exe' : 'java'), 'fixture');
    return [name, home];
  }));
  try {
    return run(homes);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}
const probe = (javaExe) => majorByHome[path.basename(path.dirname(path.dirname(javaExe)))] ?? null;

check(`MIN_MAJOR is ${MIN_MAJOR} (>= 24)`, () => {
  assert.ok(MIN_MAJOR >= 24, `MIN_MAJOR should be >= 24, got ${MIN_MAJOR}`);
});
check('selection: first >= 24 wins, older/lower skipped', () => {
  withFakeHomes(['jdk8', 'jdk17', 'jdk23', 'jdk24', 'jdk25'], (homes) => {
    assert.equal(selectFromCandidates(
      [homes.jdk8, homes.jdk17, homes.jdk23, homes.jdk25], probe), homes.jdk25);
    assert.equal(selectFromCandidates([homes.jdk24, homes.jdk25], probe), homes.jdk24);
  });
});
check('selection: all < 24 → null', () => {
  withFakeHomes(['jdk8', 'jdk17', 'jdk23'], (homes) => {
    assert.equal(selectFromCandidates([homes.jdk8, homes.jdk17, homes.jdk23], probe), null);
  });
});
check('selection: unprobeable (null) candidates skipped', () => {
  withFakeHomes(['broken', 'jdk8', 'jdk25'], (homes) => {
    assert.equal(selectFromCandidates([homes.broken, homes.jdk8, homes.jdk25], probe), homes.jdk25);
  });
});
check('selection: empty/falsey candidates skipped, empty list → null', () => {
  withFakeHomes(['jdk25'], (homes) => {
    assert.equal(selectFromCandidates([null, '', homes.jdk25], probe), homes.jdk25);
  });
  assert.equal(selectFromCandidates([], probe), null);
});

// Sanity: the real selectFromCandidates rejects a non-existent home without throwing.
check('selectFromCandidates: non-existent home → null (no throw)', () => {
  assert.equal(selectFromCandidates([path.join(__dirname, 'no-such-jdk-xyz')]), null);
});

// --- javaHomesFromPath ---
check('javaHomesFromPath: scans past an obsolete Windows shim to a JDK bin', () => {
  const pathValue = 'C:\\Oracle\\java8path;"D:\\tools\\jdk-25\\bin"';
  const existing = new Set([
    'C:\\Oracle\\java8path\\java.exe',
    'D:\\tools\\jdk-25\\bin\\java.exe',
  ]);
  assert.deepEqual(
    javaHomesFromPath(
      pathValue,
      'win32',
      (candidate) => existing.has(candidate),
      (candidate) => candidate,
    ),
    ['C:\\Oracle', 'D:\\tools\\jdk-25'],
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
