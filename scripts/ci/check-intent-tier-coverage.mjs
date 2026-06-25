#!/usr/bin/env node
/**
 * intent-tier-coverage gate — tempdoc 577 Goal 3 §3.7 (the full 570 Move H).
 *
 * The sibling of `check-search-issuance` (single-issuer seam) and `interaction-surface`
 * (one visible window). This gate is the THIRD §3.7 leg: intent-tier coverage projecting
 * FROM the shape catalog. The one window (UnifiedChatView) escalates one input across
 * intent tiers (retrieve → documents/Ask, extract, agent/Delegate); every LLM tier maps to
 * exactly one registered conversation shape, and the `retrieve` base tier maps to NO shape
 * (§3.3 — pure retrieval via searchState, not a ConversationShape).
 *
 *  1. COVERAGE — the register's declared tier→shape map covers EXACTLY the Java authority
 *     `CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES`. A core shape with no tier
 *     (an unreachable mode) or a tier naming a non-core shape fails. (Projection from the catalog.)
 *  2. ROUTING — the window's `presetByShape` literal (shapeId → affordance) equals the inverse
 *     of the register's declared map. A silent re-route in code (documents → free-chat) or a
 *     register/code drift fails.
 *  3. BASE TIER — `retrieve` is declared shape-less; no shape routes to it; and its non-LLM
 *     seam (`submitSearch`) is referenced in the window. So a `retrieve` wired to an LLM
 *     dispatch, or a tier rendered without a shape, is unrepresentable.
 *
 * Honest limit: regex-parses the `presetByShape` object literal + the Java `Set.of(...)`; a
 * refactor to a computed table is import-invisible (register + discipline, the same ceiling as
 * the search-issuance / steering-arbitration gates). The 'shape rendered outside the window'
 * half is the `interaction-surface` second-view backstop, not re-checked here.
 *
 * Lighter scripts/ci tier; wired as a ci.yml step + the CLAUDE.md pre-merge list.
 */
import { readFileSync } from 'node:fs';

const REGISTER = 'governance/intent-tier-coverage.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const stripComments = (s) =>
  s
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\*.*$/gm, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

const failures = [];

// --- Parse the Java authority: CORE_USER_INTERACTION_SHAPES = Set.of("core.a", ...) ---
const javaSrc = readFileSync(reg.shapeCatalog, 'utf8');
const javaSet = (() => {
  const m = javaSrc.match(new RegExp(`${reg.coreShapesSymbol}\\s*=\\s*Set\\.of\\(([\\s\\S]*?)\\)`));
  return m ? new Set([...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])) : new Set();
})();
if (javaSet.size === 0) {
  failures.push(
    `could not parse ${reg.coreShapesSymbol} from ${reg.shapeCatalog} — the shape authority moved or ` +
      `the Set.of(...) shape changed; update the gate (it cannot be defeated by hiding the authority).`,
  );
}

// --- Build the declared tier→shape map + its inverse (shape→affordance) ---
const tiers = reg.tiers;
const declaredShapes = new Set();
const inverse = new Map(); // shapeId -> affordance
for (const [affordance, shapes] of Object.entries(tiers)) {
  for (const shape of shapes) {
    if (declaredShapes.has(shape)) {
      failures.push(`register tier map: shape "${shape}" is claimed by more than one tier.`);
    }
    declaredShapes.add(shape);
    inverse.set(shape, affordance);
  }
}

// 1. COVERAGE — declared shapes === Java authority set.
if (javaSet.size > 0) {
  for (const shape of javaSet) {
    if (!declaredShapes.has(shape)) {
      failures.push(
        `coverage: core interaction shape "${shape}" (in ${reg.coreShapesSymbol}) maps to NO intent ` +
          `tier in ${REGISTER} — an unreachable mode. Add it to the right tier's shape list (§3.7).`,
      );
    }
  }
  for (const shape of declaredShapes) {
    if (!javaSet.has(shape)) {
      failures.push(
        `coverage: register tier map names "${shape}", which is NOT a core user-interaction shape ` +
          `(${reg.coreShapesSymbol}). The tiers project FROM the catalog — remove it or fix the catalog.`,
      );
    }
  }
}

// --- Parse the window's presetByShape literal (shapeId -> affordance) ---
const winSrc = stripComments(readFileSync(reg.window, 'utf8'));
const presetM = winSrc.match(
  new RegExp(`${reg.presetTable}\\s*:\\s*Record<[^>]*>\\s*=\\s*\\{([\\s\\S]*?)\\}`),
);
if (!presetM) {
  failures.push(
    `could not parse the \`${reg.presetTable}\` routing table in ${reg.window} — the seam moved or ` +
      `its literal shape changed; update the gate (it cannot be defeated by hiding the table).`,
  );
}
const preset = new Map(); // shapeId -> affordance
if (presetM) {
  for (const m of presetM[1].matchAll(/['"]([^'"]+)['"]\s*:\s*['"]([^'"]+)['"]/g)) {
    preset.set(m[1], m[2]);
  }
}

// 2. ROUTING — presetByShape equals the inverse of the declared map.
if (presetM && javaSet.size > 0) {
  for (const [shape, affordance] of inverse) {
    if (!preset.has(shape)) {
      failures.push(
        `routing: shape "${shape}" should preset the "${affordance}" tier but is missing from ` +
          `\`${reg.presetTable}\` in ${reg.window}.`,
      );
    } else if (preset.get(shape) !== affordance) {
      failures.push(
        `routing: shape "${shape}" presets "${preset.get(shape)}" in ${reg.window} but the register ` +
          `declares tier "${affordance}" — a silent re-route. Align ${reg.window} or ${REGISTER}.`,
      );
    }
  }
  for (const [shape, affordance] of preset) {
    if (!inverse.has(shape)) {
      failures.push(
        `routing: \`${reg.presetTable}\` routes "${shape}" → "${affordance}" but the register declares ` +
          `no such tier mapping — a shape with an undeclared tier. Add it to ${REGISTER}.`,
      );
    }
  }
}

// 3. BASE TIER — retrieve is shape-less; no shape routes to it; its non-LLM seam is present.
const baseTier = reg.baseTier;
if ((tiers[baseTier] ?? []).length !== 0) {
  failures.push(
    `base tier: "${baseTier}" must map to NO shape (§3.3 — pure retrieval is not a ConversationShape), ` +
      `but ${REGISTER} lists ${JSON.stringify(tiers[baseTier])}.`,
  );
}
for (const [shape, affordance] of preset) {
  if (affordance === baseTier) {
    failures.push(
      `base tier: shape "${shape}" presets the "${baseTier}" base tier in ${reg.window} — the base tier ` +
        `is reached by user toggle, never by a shape deeplink (§3.3). Remove it from \`${reg.presetTable}\`.`,
    );
  }
}
if (presetM && !new RegExp(`\\b${reg.baseTierSeam}\\b`).test(winSrc)) {
  failures.push(
    `base tier: the non-LLM seam \`${reg.baseTierSeam}\` is not referenced in ${reg.window} — the ` +
      `"${baseTier}" tier must dispatch through searchState, not an LLM shape (§3.3).`,
  );
}

if (failures.length > 0) {
  console.error('✗ intent-tier-coverage gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ intent-tier-coverage gate OK — ${javaSet.size} core interaction shapes each map to exactly one ` +
    `intent tier; the window's ${reg.presetTable} routing matches the declared map; the "${baseTier}" ` +
    `base tier is shape-less and dispatches through ${reg.baseTierSeam} (577 §3.7 / Move H).`,
);
