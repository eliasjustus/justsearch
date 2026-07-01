#!/usr/bin/env node
/**
 * ai-verdict-derivation gate — tempdoc 663 (the AI-engine lifecycle anti-drift teeth, mirroring
 * check-verdict-derivation / check-folder-status-derivation).
 *
 * The defect this closes: `BrainSurface.deriveAiState()` used to reconcile ~5 overlapping state
 * representations (installStatus, busy flags, runtimeStatus.onnxFeatures, the unified runtime, a raw
 * inference read) by a hand-ordered precedence ladder. `computeAiEngineVerdict` (state/aiVerdict.ts)
 * collapsed that into ONE derivation. This gate keeps it so:
 *
 *  1. SEAM integrity — `aiVerdict.ts` defines `computeAiEngineVerdict` and reads the raw install/onnx
 *     fields. A rename/delete of the seam fails here, so the gate cannot be defeated by removing what
 *     it guards.
 *  2. SINGLE derivation — within `shell-v0`, the raw fields (`installStatus?.state`/`onnxFeatures`)
 *     appear ONLY in the allow-listed seam. Any other file reading them directly is re-forming the
 *     5-source ladder this doc exists to prevent → fail. Consumers read
 *     `computeAiEngineVerdict(...)`'s result (or the `aiState` field it is derived from).
 *
 * Honest limit (same as the sibling gates): a verdict re-derived from a DIFFERENT field is
 * import-invisible to this substring scan — register + discipline, not absolute. Comments stripped.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REGISTER = 'governance/ai-verdict-derivation.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');

const stripComments = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const predicate = new RegExp(reg.predicatePattern);
const failures = [];

// 1. SEAM integrity — the seam exists and reads the raw install/onnx fields.
{
  const seamFile = norm(reg.seam.file);
  const src = stripComments(readFileSync(seamFile, 'utf8'));
  if (!new RegExp(`\\b${reg.seam.symbol}\\b`).test(src)) {
    failures.push(
      `${seamFile}: the AI-verdict seam \`${reg.seam.symbol}\` is missing — the ONE AI-engine ` +
        `lifecycle derivation (tempdoc 663) must exist here; do not remove what the gate guards.`,
    );
  }
  if (!predicate.test(src)) {
    failures.push(
      `${seamFile}: the seam no longer reads the raw install/onnx fields (\`${reg.predicatePattern}\`) — ` +
        `the AI-engine lifecycle must be derived here (tempdoc 663), not elsewhere.`,
    );
  }
}

// 2. SINGLE derivation — the raw fields appear only in allow-listed files.
const allowed = new Set(reg.allowed.map(norm));
const walk = (dir, acc) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, acc);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) acc.push(norm(p));
  }
  return acc;
};
for (const f of walk(reg.scope, [])) {
  if (allowed.has(f)) continue;
  const src = stripComments(readFileSync(f, 'utf8'));
  if (predicate.test(src)) {
    failures.push(
      `${f}: reads the raw install/onnx fields directly — every surface must CONSUME the one ` +
        `\`${reg.seam.symbol}\` derivation, not recompute the AI-engine lifecycle (tempdoc 663; the ` +
        `5-source ladder re-introduced). Call \`computeAiEngineVerdict(...)\`, or (if a declared ` +
        `non-lifecycle reader) add the file to \`allowed\` in ${REGISTER}.`,
    );
  }
}

if (failures.length > 0) {
  console.error('✗ ai-verdict-derivation gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ ai-verdict-derivation gate OK — the AI-engine lifecycle is derived ONCE in \`${reg.seam.symbol}\` ` +
    `(tempdoc 663); no second lifecycle-derivation site in ${reg.scope}.`,
);
