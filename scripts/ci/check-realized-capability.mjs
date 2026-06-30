#!/usr/bin/env node
/**
 * realized-capability gate — tempdoc 644.
 *
 * A surface that DISPLAYS per-engine realized state (which retrieval engine loaded + on which device
 * + failure reason) must PROJECT it from the ONE authority `computeRealized` (state/aiStateStore.ts)
 * by reading the projected `aiState.realized.*` field — rather than re-reading raw `worker.gpu.*OrtCuda`
 * ad-hoc in the component. That ad-hoc read is the fork-class: a second reader drifts from the projector
 * and from the eval-side realized-engine guard/cohort identity (the representation-drift class, 553).
 *
 * Positive coverage against `governance/realized-capability-surfaces.v1.json`: every REGISTERED surface
 * must reference the projected field (after comment-stripping, so a doc mention doesn't satisfy it).
 * Seam integrity: the authority file still EXPORTS the projector function.
 *
 * Honest limit (as with capability-availability / declared-surfaces): positive coverage guards the
 * registered surfaces' projection seam — it does not detect a brand-new unregistered fork surface (a new
 * such surface is a discovery-step register row). A local pre-merge check, not a kernel gate.
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/realized-capability-surfaces.v1.json';

/** Strip // and block comments so a doc mention of the field doesn't satisfy/trip the scan. */
export function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** Does the (comment-stripped) source CONSUME the projected field (e.g. `.realized`)? */
export function consumesField(src, field) {
  const code = stripComments(src);
  // A read site: `.realized` somewhere in code (e.g. `this.aiState?.realized` / `s.realized`). The
  // surface routes through the projector's output rather than re-reading raw `worker.gpu.*OrtCuda`.
  return new RegExp(`\\.${field}\\b`).test(code);
}

/** Pure check. Returns failure strings (empty = pass). */
export function checkCoverage({ surfaces, field, readFile }) {
  const failures = [];
  for (const file of surfaces) {
    let src;
    try {
      src = readFile(file);
    } catch {
      failures.push(`unresolved: registered surface \`${file}\` does not exist — fix the path in ${REGISTER}.`);
      continue;
    }
    if (!consumesField(src, field)) {
      failures.push(
        `fork: registered realized-capability surface \`${file}\` does not read \`aiState.${field}\` — ` +
          `it must PROJECT per-engine realized state from the one authority (not re-read raw ` +
          `\`worker.gpu.*OrtCuda\`), so it cannot drift from the projector + the eval-side guard. Read ` +
          `\`aiState.${field}\`, or (if it no longer displays realized state) drop it from ${REGISTER}.`,
      );
    }
  }
  return failures;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const symbol = reg.authority.symbol;
  const field = reg.consumedField;
  // Seam integrity — the authority still exports the projector.
  const authoritySrc = readFileSync(reg.authority.file, 'utf8');
  if (!new RegExp(`export function ${symbol}\\b`).test(authoritySrc)) {
    console.error(
      `✗ realized-capability gate FAILED: authority \`${symbol}\` not exported by ${reg.authority.file} — ` +
        `the seam moved; update ${REGISTER}.`,
    );
    process.exit(1);
  }
  const failures = checkCoverage({
    surfaces: reg.surfaces,
    field,
    readFile: (f) => readFileSync(f, 'utf8'),
  });
  if (failures.length > 0) {
    console.error(
      '✗ realized-capability gate FAILED (tempdoc 644):\n' + failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ realized-capability gate OK — ${reg.surfaces.length} registered surface(s) project per-engine ` +
      `realized state via \`aiState.${field}\` (authority \`${symbol}\`); none can re-fork the read.`,
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-realized-capability.mjs')) {
  main();
}
