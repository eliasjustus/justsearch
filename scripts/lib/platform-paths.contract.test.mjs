/**
 * Tempdoc 501 §3.6: Node side of the cross-language platform-paths contract.
 *
 * Runs every fixture from `contracts/platform-paths/spec.v1.json` through
 * `scripts/lib/platform-paths.mjs::resolveDataDir` and asserts the resolved
 * path matches `expected`. The Java twin lives at
 * `modules/configuration/.../PlatformPathsContractTest.java`.
 *
 * Run with: `node scripts/lib/platform-paths.contract.test.mjs`
 * Exits non-zero on any fixture mismatch.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {resolveDataDir} from './platform-paths.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SPEC_PATH = path.resolve(__dirname, '..', '..', 'contracts', 'platform-paths', 'spec.v1.json');

const spec = JSON.parse(fs.readFileSync(SPEC_PATH, 'utf8'));

let passed = 0;
let failed = 0;
const failures = [];

for (const fixture of spec.fixtures) {
  const {name, platform, userHome, env, expected, runners} = fixture;
  if (Array.isArray(runners) && runners.length > 0 && !runners.includes('node')) {
    continue;
  }
  let actual;
  try {
    actual = resolveDataDir({env, userHome, platform});
  } catch (e) {
    failed += 1;
    failures.push({name, error: `threw: ${e.message}`});
    continue;
  }
  // Normalize separators for cross-platform comparison.
  const normalizedActual = actual.replace(/\\/g, '/');
  const normalizedExpected = expected.replace(/\\/g, '/');
  if (normalizedActual === normalizedExpected) {
    passed += 1;
  } else {
    failed += 1;
    failures.push({name, expected: normalizedExpected, actual: normalizedActual});
  }
}

const total = passed + failed;
console.log(`platform-paths contract: ${passed}/${total} fixtures passed`);
if (failed > 0) {
  for (const f of failures) {
    console.error(`  FAIL ${f.name}`);
    if (f.error) console.error(`    ${f.error}`);
    else console.error(`    expected: ${f.expected}\n    actual:   ${f.actual}`);
  }
  process.exit(1);
}
process.exit(0);
