#!/usr/bin/env node
/**
 * Runs every `*.test.mjs` under scripts/agent-analytics/ and aggregates the results.
 *
 * Why this exists (tempdoc 745, D6): the analytics tests are ~26 standalone scripts
 * with no test framework — each is a `node <file>` that exits non-zero on failure
 * (the `run(label, fn)` + `node:assert/strict` pattern). There is no `node --test`
 * entry point for them because they don't use the `node:test` API, and no
 * `unittest discover` equivalent exists for Node. This is that entry point, so CI
 * can run them as one step.
 *
 * DISCOVERY IS DELIBERATE, NOT A CONVENIENCE. A hardcoded list would rot: the next
 * agent adds `foo.test.mjs`, forgets the list, and the test silently never runs —
 * which is precisely the failure mode tempdoc 745 exists to name (a layer nothing
 * invokes is dead regardless of its quality). Globbing means a new test file is in
 * CI the moment it lands, with nothing to remember.
 *
 * Usage: node scripts/agent-analytics/run-all-tests.mjs [--verbose]
 * Exit code: 0 iff every discovered test file exited 0.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const VERBOSE = process.argv.includes('--verbose');

/** Recursively collect *.test.mjs, skipping node_modules. */
function discover(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...discover(full));
    else if (entry.name.endsWith('.test.mjs')) out.push(full);
  }
  return out;
}

const files = discover(HERE).sort();
if (files.length === 0) {
  // An empty discovery means the glob broke, not that everything passes.
  console.error('run-all-tests: discovered 0 test files — that is a bug, not a pass.');
  process.exit(1);
}

let passed = 0;
const failures = [];

for (const file of files) {
  const rel = path.relative(process.cwd(), file);
  const res = spawnSync(process.execPath, [file], { encoding: 'utf8' });
  if (res.status === 0) {
    passed++;
    if (VERBOSE) console.log(`PASS  ${rel}`);
  } else {
    failures.push({ rel, res });
    console.log(`FAIL  ${rel}`);
    const output = `${res.stdout || ''}${res.stderr || ''}`.trimEnd();
    if (output) console.log(output.split('\n').map((l) => `      ${l}`).join('\n'));
    if (res.status === null) console.log(`      (killed by signal ${res.signal})`);
  }
}

console.log(`\nagent-analytics: ${passed}/${files.length} test files passed`);
if (failures.length) {
  console.log(`failed: ${failures.map((f) => f.rel).join(', ')}`);
  process.exit(1);
}
