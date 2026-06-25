#!/usr/bin/env node
/**
 * Tempdoc 632 — SPDX header tool. Prepends `SPDX-License-Identifier: Apache-2.0` to first-party Java
 * (modules/.../src/main) and ui-web TS (non-test, non-generated). Walks ONLY `modules/` so it cannot
 * touch governance test fixtures under `scripts/governance/_fixtures`. Idempotent.
 *
 * Apply mode stamps missing headers; `--check` mode (CI) lists any eligible file that LACKS one and
 * exits non-zero — forward-enforcement without touching the shared Spotless plugin (mirrors the
 * repo's gen-then-check codegen convention).
 *   node scripts/codegen/add-spdx-headers.mjs [--dry-run | --check]
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const MODULES = join(REPO_ROOT, 'modules');
const DRY = process.argv.includes('--dry-run');
const CHECK = process.argv.includes('--check');

const JAVA_HEADER = '/* SPDX-License-Identifier: Apache-2.0 */\n';
const TS_HEADER = '// SPDX-License-Identifier: Apache-2.0\n';
const SKIP_DIR = /(^|[\\/])(build|node_modules|\.git|generated|dist|coverage)([\\/]|$)/;

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (SKIP_DIR.test(p)) continue;
    const st = statSync(p);
    if (st.isDirectory()) yield* walk(p);
    else yield p;
  }
}

function eligible(p) {
  const n = p.replace(/\\/g, '/');
  if (extname(p) === '.java') return /\/modules\/[^/]+\/src\/main\/.*\.java$/.test(n);
  if (extname(p) === '.ts') {
    if (!n.includes('/modules/ui-web/src/')) return false;
    if (/\.(test|spec)\.ts$/.test(n) || /\.generated\.ts$/.test(n) || n.includes('/generated/')) return false;
    return true;
  }
  return false;
}

let stamped = 0;
let skipped = 0;
const missing = [];
for (const p of walk(MODULES)) {
  if (!eligible(p)) continue;
  const content = readFileSync(p, 'utf8');
  // Position-aware: the header must be the file's FIRST line. (An earlier `first-400-chars` test would
  // wrongly skip a file that merely *mentions* the SPDX string — e.g. license-parsing code — leaving it
  // un-stamped AND un-flagged by --check, since both used the same predicate.)
  const expectedHeader = extname(p) === '.java' ? JAVA_HEADER : TS_HEADER;
  if (content.startsWith(expectedHeader)) {
    skipped += 1;
    continue;
  }
  if (CHECK) {
    missing.push(p.replace(REPO_ROOT + '\\', '').replace(REPO_ROOT + '/', '').replace(/\\/g, '/'));
    continue;
  }
  if (!DRY) writeFileSync(p, (extname(p) === '.java' ? JAVA_HEADER : TS_HEADER) + content, 'utf8');
  stamped += 1;
}
if (CHECK) {
  if (missing.length) {
    console.error(`SPDX header MISSING on ${missing.length} file(s) — run: node scripts/codegen/add-spdx-headers.mjs`);
    for (const m of missing) console.error(`  - ${m}`);
    process.exit(1);
  }
  console.log(`add-spdx-headers --check: all eligible sources carry the header (${skipped} checked).`);
} else {
  console.log(`${DRY ? '[dry-run] would stamp' : 'stamped'} ${stamped}; skipped ${skipped}`);
}
