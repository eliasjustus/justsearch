#!/usr/bin/env node
/**
 * Pre-merge table drift check (tempdoc 620 Phase 5 / rec #2).
 *
 * CLAUDE.md's "Pre-merge script checks" table maps an edited subject to the
 * check(s) that gate it. The table is hand-maintained: the ui-web `check-*.mjs`
 * are standalone `ci.yml` steps, NOT registered in `governance/registry.v1.json`,
 * so a clean *generated* projection of the table is not available without first
 * registering them (a large refactor — see tempdoc 620 Move 1). This validator is
 * the proportionate guard: it parses the table and asserts every referenced check
 * SCRIPT and every `--gate <id>` still resolves, so a renamed/removed/typo'd check
 * fails loudly instead of the table silently rotting.
 *
 * Standalone CI lint (the doc-gen `--check` family) — NOT a new kernel
 * discipline-gate (582 R3-clean).
 *
 *   node scripts/ci/check-premerge-table.mjs   # exit 1 on any dangling reference
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CLAUDE_MD = resolve(REPO_ROOT, 'CLAUDE.md');
const REGISTRY = resolve(REPO_ROOT, 'governance/registry.v1.json');
const SCRIPTS = resolve(REPO_ROOT, 'scripts');

/** Set of every script basename (sans extension) under scripts/. */
function allScriptBasenames() {
  const out = new Set();
  (function walk(d) {
    let entries;
    try {
      entries = readdirSync(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = join(d, e.name);
      if (e.isDirectory()) {
        if (e.name !== 'node_modules') walk(full);
      } else {
        const m = e.name.match(/^(.*)\.(mjs|cjs|js)$/);
        if (m) out.add(m[1]);
      }
    }
  })(SCRIPTS);
  return out;
}

/** Set of gate ids in governance/registry.v1.json. */
function gateIds() {
  try {
    const reg = JSON.parse(readFileSync(REGISTRY, 'utf8'));
    return new Set((reg.gates ?? []).map((g) => g.id));
  } catch {
    return new Set();
  }
}

/** Extract the Pre-merge table's "Check(s)" cells from CLAUDE.md. */
function checkCells() {
  const lines = readFileSync(CLAUDE_MD, 'utf8').split(/\r?\n/);
  const header = lines.findIndex((l) => /^\|\s*Edited subject\s*\|\s*Check\(s\)\s*\|/.test(l));
  if (header < 0) return null; // table not found — a structural drift in itself
  const cells = [];
  for (let i = header + 2; i < lines.length; i++) {
    const line = lines[i];
    if (!line.startsWith('|')) break; // table ended
    const cols = line.split('|'); // ['', subject, checks, '']
    if (cols.length >= 3) cells.push(cols[2]);
  }
  return cells;
}

const scripts = allScriptBasenames();
const gates = gateIds();
const cells = checkCells();

if (cells == null) {
  console.error('[premerge-table] FAIL — could not locate the "| Edited subject | Check(s) |" table in CLAUDE.md.');
  process.exit(1);
}

const missingScripts = new Set();
const missingGates = new Set();
let scriptRefs = 0;
let gateRefs = 0;

for (const cell of cells) {
  // each ref is backtick-wrapped: `check-foo`, `gen-token-names --check`, `--gate a,b`
  for (const m of cell.matchAll(/`([^`]+)`/g)) {
    const inner = m[1].trim();
    // gate references (may be a comma list): `--gate a,b,c`
    const gm = inner.match(/--gate\s+([a-z0-9,\-]+)/i);
    if (gm) {
      for (const id of gm[1].split(',').map((s) => s.trim()).filter(Boolean)) {
        gateRefs++;
        if (!gates.has(id)) missingGates.add(id);
      }
      continue;
    }
    // script references: first word, shaped like a check/gen/strip script
    const first = inner.split(/\s+/)[0];
    if (/^(check|gen|strip)-[a-z0-9-]+$/i.test(first)) {
      scriptRefs++;
      if (!scripts.has(first)) missingScripts.add(first);
    }
  }
}

console.log(`[premerge-table] scanned ${cells.length} rows: ${scriptRefs} script refs, ${gateRefs} gate refs.`);

const fail = missingScripts.size || missingGates.size;
if (fail) {
  console.error('\n[premerge-table] FAIL — the Pre-merge table references that no longer resolve:');
  if (missingScripts.size) console.error(`  missing scripts (no scripts/**/<name>.{mjs,cjs,js}): ${[...missingScripts].join(', ')}`);
  if (missingGates.size) console.error(`  missing gate ids (not in governance/registry.v1.json): ${[...missingGates].join(', ')}`);
  console.error('\nA check was renamed/removed, or the table has a typo. Fix the CLAUDE.md table (or restore the');
  console.error('check). The table is hand-maintained because the ui-web checks are not registry gates (620 Move 1).');
  process.exit(1);
}
console.log('[premerge-table] pass — every referenced check script and gate id resolves.');
