#!/usr/bin/env node
/**
 * capability-availability gate — tempdoc 613 §10.
 *
 * A surface that DISPLAYS an AI affordance's availability (its reason + remedy) must PROJECT it from the
 * ONE authority `projectAvailability` (state/availability.ts) — whose wording/remedy come from the shared
 * `readinessNotice` vocabulary — rather than reading raw `aiState.capabilities.*` booleans and hardcoding
 * the wording. That hardcoding is the fork-class (the prior CapabilityPills' "Chat unavailable" /
 * "Embedding blocked" diverged from the Health map's canonical reason).
 *
 * Positive coverage against `governance/capability-availability-surfaces.v1.json`: every REGISTERED
 * capability-availability display surface must call `projectAvailability` (after comment-stripping, so a
 * doc mention doesn't satisfy it). Seam integrity: the authority file still exports the symbol.
 *
 * Honest limit (as with verdict-derivation / declared-surfaces): positive coverage guards the registered
 * surfaces' projection seam — it does not detect a brand-new unregistered fork surface (a new such surface
 * is a discovery-step register row), nor adjudicate free-text wording (prose-tier, 613 §8).
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/capability-availability-surfaces.v1.json';

/** Strip // and /* *​/ comments so a doc mention of the symbol doesn't satisfy/trip the scan. */
export function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((l) => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** Does the (comment-stripped) source CALL the authority symbol (a real consumer, not a re-export)? */
export function consumesAuthority(src, symbol) {
  const code = stripComments(src);
  // A call site: `projectAvailability(` somewhere in code (import + invocation both contain it; either
  // proves the surface routes through the authority rather than hand-rolling capability wording).
  return new RegExp(`\\b${symbol}\\s*\\(`).test(code);
}

/** Pure check. Returns failure strings (empty = pass). */
export function checkCoverage({ surfaces, symbol, readFile }) {
  const failures = [];
  for (const file of surfaces) {
    let src;
    try {
      src = readFile(file);
    } catch {
      failures.push(`unresolved: registered surface \`${file}\` does not exist — fix the path in ${REGISTER}.`);
      continue;
    }
    if (!consumesAuthority(src, symbol)) {
      failures.push(
        `fork: registered capability-availability surface \`${file}\` does not call \`${symbol}(...)\` — ` +
          `it must PROJECT the affordance's reason/remedy from the one availability authority (not raw ` +
          `\`capabilities.*\` booleans + hardcoded wording), so it cannot disagree with CapabilityMap/the ` +
          `controls. Project via \`${symbol}\`, or (if it no longer displays availability) drop it from ${REGISTER}.`,
      );
    }
  }
  return failures;
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
  const symbol = reg.authority.symbol;
  // Seam integrity — the authority still exports the symbol.
  const authoritySrc = readFileSync(reg.authority.file, 'utf8');
  if (!new RegExp(`export function ${symbol}\\b`).test(authoritySrc)) {
    console.error(
      `✗ capability-availability gate FAILED: authority \`${symbol}\` not exported by ${reg.authority.file} — ` +
        `the seam moved; update ${REGISTER}.`,
    );
    process.exit(1);
  }
  const failures = checkCoverage({
    surfaces: reg.surfaces,
    symbol,
    readFile: (f) => readFileSync(f, 'utf8'),
  });
  if (failures.length > 0) {
    console.error(
      '✗ capability-availability gate FAILED (tempdoc 613 §10):\n' +
        failures.map((x) => '  - ' + x).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ capability-availability gate OK — ${reg.surfaces.length} registered surface(s) project capability ` +
      `availability via \`${symbol}\`; none can re-fork the reason vocabulary.`,
  );
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-capability-availability.mjs')) {
  main();
}
