/**
 * Region->governing-doc/recipe loader for the tempdoc 579 behavioral-protocol hooks.
 *
 * The data now lives in governance/consult-register.v1.json (the promotion this
 * file's predecessor anticipated: "promote to governance/consult-register.v1.json
 * only if it grows" — tempdoc 620 Move 2). This module loads that register and
 * compiles each row's path matcher into the { region, docs, match } shape the two
 * consumers expect, so consult-doc-hint.mjs / maintain-doc-hint.mjs are unchanged.
 *
 * Consumed by:
 *  - consult-doc-hint.mjs (PreToolUse) — pushes the governing doc(s) + recipe when you ENTER a region (Consult).
 *  - maintain-doc-hint.mjs (Stop)      — blocks once if you FINISH having edited a region without
 *                                        touching its GOVERNING doc (Maintain). A row with docs:[]
 *                                        is consult-only (a recipe) and never blocks.
 *
 * Fail-open: a missing or unparseable register yields zero regions. A hook must
 * never crash on bad data — this mirrors the surrounding hooks' fail-open posture.
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const REGISTER_PATH = resolve(REPO_ROOT, 'governance', 'consult-register.v1.json');

/** Compile a row's declarative path matcher into a predicate over a normalized path. */
function compileMatch(row) {
  const subs = Array.isArray(row.pathIncludes) ? row.pathIncludes : [];
  return (p) => subs.some((s) => p.includes(s));
}

function loadRegions() {
  try {
    const reg = JSON.parse(readFileSync(REGISTER_PATH, 'utf8'));
    const rows = Array.isArray(reg.regions) ? reg.regions : [];
    return rows.map((row) => ({
      ...row,
      docs: Array.isArray(row.docs) ? row.docs : [],
      match: compileMatch(row),
    }));
  } catch {
    return []; // fail-open — never crash a hook on a bad/absent register
  }
}

export const GOVERNED_REGIONS = loadRegions();

export function normalizePath(p) {
  return p.replace(/\\/g, '/');
}

/** The governed region for a file path, or null. */
export function regionFor(filePath) {
  if (!filePath) return null;
  const norm = normalizePath(filePath);
  return GOVERNED_REGIONS.find((e) => e.match(norm)) ?? null;
}
