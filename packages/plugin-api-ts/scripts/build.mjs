#!/usr/bin/env node
/**
 * Slice 477 H3.1 — minimum-viable build script for
 * @justsearch/plugin-api.
 *
 * V1.5.1 alpha: runs `tsc` to emit .js + .d.ts under dist/. V1.5.2
 * adds dual ESM/CJS via tsup or rollup; V1.5.1 ships ESM-only.
 */

import { execSync } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKG_ROOT = resolve(__dirname, '..');
const DIST = resolve(PKG_ROOT, 'dist');

if (existsSync(DIST)) {
  rmSync(DIST, { recursive: true, force: true });
  console.log('cleaned dist/');
}

execSync('npx tsc -p tsconfig.json', {
  cwd: PKG_ROOT,
  stdio: 'inherit',
});

console.log('build complete.');
