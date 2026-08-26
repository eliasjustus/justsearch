#!/usr/bin/env node
/**
 * Run the ui-web pre-merge gate set (tempdoc 872).
 *
 * The set's authority is the `ui-web-gates` recipe in governance/consult-register.v1.json —
 * this runner PARSES that recipe rather than carrying its own list, so there is one list, not
 * two that drift. Until 872 the set was advisory only (pushed by the consult hook at edit time,
 * run in CI nowhere), which is how `gen-token-names --check` and `gen-component-vocabulary
 * --check` sat RED on main for weeks while twelve sessions each re-discovered it and wrote it
 * down. A check that main can violate silently is a memory generator; running it here makes the
 * state impossible instead of merely noted.
 *
 *   node scripts/ci/run-ui-web-gates.mjs            # run everything, non-zero on first failure set
 *   node scripts/ci/run-ui-web-gates.mjs --list     # print the parsed commands and exit
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const REGISTER = path.join(ROOT, 'governance', 'consult-register.v1.json');

/** Parse the recipe prose into `node <script> [args]` commands. Pure; the test seam. */
export function parseUiWebGateCommands(register) {
  const entry = (register.entries ?? register.regions ?? []).find((e) => e.id === 'ui-web-gates');
  if (!entry) throw new Error('consult-register: no `ui-web-gates` entry');
  const cmds = [];
  for (const line of entry.recipe) {
    if (/^Plus the kernel gates:/.test(line)) {
      // `run.mjs --gate` takes ONE id; the recipe lists several — one invocation per id.
      const m = line.match(/--gate\s+([a-z0-9,-]+)/);
      for (const id of (m?.[1] ?? '').split(',').filter(Boolean)) {
        cmds.push(['node', 'scripts/governance/run.mjs', '--gate', id, '--mode', 'gate']);
      }
      continue;
    }
    const idx = line.indexOf(': ');
    if (idx < 0 || !/scripts\/ci\/<name>\.mjs\)|additionally:/.test(line)) continue;
    const list = line.slice(idx + 2).replace(/\s*\([^)]*\)/g, '').replace(/\.\s*$/, '');
    for (const item of list.split(',').map((s) => s.trim()).filter(Boolean)) {
      const [name, ...args] = item.split(/\s+/);
      cmds.push(['node', `scripts/ci/${name}.mjs`, ...args]);
    }
  }
  return cmds;
}

function main() {
  const register = JSON.parse(fs.readFileSync(REGISTER, 'utf8'));
  const cmds = parseUiWebGateCommands(register);
  if (process.argv.includes('--list')) {
    for (const c of cmds) console.log(c.join(' '));
    return;
  }
  const failed = [];
  for (const c of cmds) {
    const res = spawnSync(c[0], c.slice(1), { cwd: ROOT, encoding: 'utf8', shell: process.platform === 'win32' });
    const label = c.slice(1).join(' ');
    if (res.status === 0) {
      console.log(`ok    ${label}`);
    } else {
      failed.push(label);
      console.log(`FAIL  ${label} (exit ${res.status})`);
      process.stdout.write((res.stdout || '') + (res.stderr || ''));
    }
  }
  console.log(`\nui-web gates: ${cmds.length - failed.length}/${cmds.length} passed`);
  if (failed.length) {
    console.log('failed: ' + failed.join(' | '));
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
