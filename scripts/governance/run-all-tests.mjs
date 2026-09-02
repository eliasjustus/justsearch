#!/usr/bin/env node
/**
 * Runs every `*.test.mjs` under scripts/governance/ and aggregates the results.
 *
 * Why this exists (tempdoc 884 review B1): the kernel's gate tests follow the
 * `run(label, fn)` + `node:assert/strict` pattern — each is a standalone `node <file>`
 * that exits non-zero on failure — but nothing invoked them. Seventeen test files ran
 * only when someone remembered, which is the failure mode tempdoc 745 D6 names: a layer
 * nothing invokes is dead regardless of its quality. This is the entry point, so CI and
 * a pre-merge check can run them as one step.
 *
 * DISCOVERY IS DELIBERATE. A hardcoded list would rot the moment the next agent adds a
 * test file and forgets to register it — the same silent-death shape this file fixes.
 *
 * Usage: node scripts/governance/run-all-tests.mjs [--verbose]
 * Exit code: 0 iff every discovered test file exited 0.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const VERBOSE = process.argv.includes('--verbose');

/** Recursively collect *.test.mjs, skipping node_modules and fixture trees. */
function discover(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '_fixtures') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...discover(full));
    else if (entry.name.endsWith('.test.mjs')) out.push(full);
  }
  return out;
}

const files = discover(HERE).sort();
if (files.length === 0) {
  // An empty discovery means the glob broke, not that everything passes.
  console.error('governance run-all-tests: discovered 0 test files — that is a bug, not a pass.');
  process.exit(1);
}

let passed = 0;
const failures = [];

for (const file of files) {
  const rel = path.relative(process.cwd(), file).replaceAll('\\', '/');
  const res = spawnSync(process.execPath, [file], { encoding: 'utf8' });
  if (res.status === 0) {
    passed += 1;
    if (VERBOSE) console.log(`PASS  ${rel}`);
  } else {
    failures.push({ rel, res });
    console.log(`FAIL  ${rel}`);
  }
}

if (failures.length > 0) {
  for (const { rel, res } of failures) {
    console.error(`\n--- ${rel} ---`);
    if (res.stdout) console.error(res.stdout.trimEnd());
    if (res.stderr) console.error(res.stderr.trimEnd());
  }
  console.error(`\ngovernance run-all-tests: ${failures.length} FAILED, ${passed} passed`);
  process.exit(1);
}
console.log(`governance run-all-tests: all ${passed} test files passed`);
