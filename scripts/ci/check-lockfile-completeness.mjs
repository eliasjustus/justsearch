#!/usr/bin/env node
/**
 * `npm ci` refuses a lockfile that does not name a resolution for every declared
 * dependency edge in the tree ("Missing: X@V from lock file", code EUSAGE). Nothing
 * local catches that today: the failure only appears on a CI runner, and only if its
 * npm is strict enough to demand the edge that the lock-writing npm pruned.
 *
 * Observed 2026-09-05 (tempdoc 930 §22.2 follow-up 1): `npm install --package-lock-only`
 * under npm 11.6.2 on win32-x64 drops the transitive dependencies of optional
 * `cpu: ["wasm32"]` platform packages (`@tailwindcss/oxide-wasm32-wasi` ->
 * `@emnapi/core` / `@emnapi/runtime`). The package stays in the lock, its dependency
 * edges do not, and `npm ci` on the runner fails on a lockfile that installs fine
 * locally. Three CI jobs went red on it.
 *
 * This gate re-implements the resolution npm validates: for every package entry, walk
 * the node_modules chain upward for each declared dependency and require a lock entry.
 * It is pure file reading — no network, no install, no resolver — so it is cheap enough
 * to run on every lockfile change.
 *
 * Two things this deliberately does NOT check, so a green is not read as more than it is.
 * Whether the resolved version satisfies the declared range: `overrides` violate ranges by
 * design, so range satisfaction is not a drift signal here — presence is. And edges other
 * than `dependencies`: an `optionalDependencies` entry that does not apply to the host is
 * legitimately absent, and an optional peer likewise, so scanning those would fire on every
 * platform-specific tree. The observed failure class lives in `dependencies`.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const LOCKFILES = [
  'package-lock.json',
  'modules/ui-web/package-lock.json',
  'modules/shell/package-lock.json',
  'packages/runtime-client/package-lock.json',
  'scripts/wire-contract/package-lock.json',
];

const REGEN_HINT = `
Regenerate the lockfile so every declared edge has an entry, then re-run this check:

  rm <lockfile> && npm install --package-lock-only --audit=false   # in that package's directory

An incremental \`npm install\` / \`npm update\` can re-prune the same edges depending on the
npm version and host platform, so re-run this check after ANY command that rewrites a
lockfile. Verify with the strict resolver too:

  npx --yes npm@latest ci --dry-run --audit=false
`;

export function repoRootFrom(start) {
  for (let dir = path.resolve(start); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) throw new Error(`repository root not found from ${start}`);
  }
}

/** Node resolution: walk `node_modules` chains upward from `fromPath` looking for `name`. */
export function resolveEntry(packages, fromPath, name) {
  for (let base = fromPath; ; ) {
    const candidate = base === '' ? `node_modules/${name}` : `${base}/node_modules/${name}`;
    if (packages[candidate]) return candidate;
    if (base === '') return null;
    const index = base.lastIndexOf('/node_modules/');
    base = index < 0 ? '' : base.slice(0, index);
  }
}

export function findMissingEdges(lockText, lockPath = '<lockfile>') {
  const lock = JSON.parse(lockText);
  const packages = lock?.packages;
  if (!packages || typeof packages !== 'object') {
    throw new Error(`${lockPath} has no packages object (lockfileVersion 2/3 required)`);
  }
  const missing = [];
  for (const [entryPath, row] of Object.entries(packages)) {
    if (!row || typeof row !== 'object' || row.link) continue;
    for (const [name, range] of Object.entries(row.dependencies ?? {})) {
      if (resolveEntry(packages, entryPath, name)) continue;
      missing.push({ from: entryPath || '<root>', name, range });
    }
  }
  return missing;
}

function main() {
  const repoRoot = repoRootFrom(path.dirname(fileURLToPath(import.meta.url)));
  const findings = [];
  let checked = 0;
  for (const rel of LOCKFILES) {
    const absolute = path.join(repoRoot, rel);
    if (!fs.existsSync(absolute)) continue;
    checked += 1;
    for (const edge of findMissingEdges(fs.readFileSync(absolute, 'utf8'), rel)) {
      findings.push(`${rel}: ${edge.from} -> ${edge.name}@${edge.range} has no lock entry`);
    }
  }
  if (findings.length === 0) {
    console.log(`lockfile-completeness: OK — ${checked} lockfile(s), every declared edge resolves`);
    return;
  }
  console.error('lockfile-completeness: FAIL — npm ci will refuse these lockfiles:');
  for (const finding of findings) console.error(`- ${finding}`);
  console.error(REGEN_HINT);
  process.exitCode = 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) main();
