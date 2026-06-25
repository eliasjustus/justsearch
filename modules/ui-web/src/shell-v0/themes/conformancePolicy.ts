// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 6 / Fix C — the conformance gate's coverage, PROJECTED from the role + layout authorities
 * (not a hardcoded list). The 557 §5.2 rider: "the gate's coverage projects from the catalog, so a
 * future surface/role is covered automatically — the prevention is not 'remember to register'."
 *
 * - Contrast pairs project from {@link COLOR_ROLES} (every semantic colour role's derived on-colour vs
 *   its background) PLUS the fixed text-on-surface readability floor. Add a role to the authority and
 *   the gate checks it with no edit here.
 * - Required regions project from the layout authority ({@link REQUIRED_REGION_IDS} ← LayoutManifest).
 * - The perf budget bounds an authored body's complexity so a schema-valid but pathological
 *   composition is rejected at the gate (rung-4 floor), per Move 6.
 */
import { COLOR_ROLES } from './roleForegrounds.js';
import { REQUIRED_REGION_IDS } from './requiredRegions.js';

export interface ContrastPair {
  readonly fg: string;
  readonly bg: string;
}

/** The fixed text-on-surface readability floor (always checked). */
const BASE_READABILITY_PAIRS: readonly ContrastPair[] = [
  { fg: 'text-primary', bg: 'surface-1' },
  { fg: 'text-primary', bg: 'surface-2' },
  { fg: 'text-secondary', bg: 'surface-1' },
];

/**
 * Every (fg,bg) token pair the gate checks — the readability floor PLUS one pair per semantic colour
 * role (its derived on-colour against its background), projected from {@link COLOR_ROLES}. A new role
 * is covered automatically.
 */
export const CONTRAST_PAIRS: readonly ContrastPair[] = [
  ...BASE_READABILITY_PAIRS,
  ...COLOR_ROLES.map((r) => ({ fg: r.on, bg: r.bg })),
];

/** Required regions — re-exported from the layout authority (projection, not a second list). */
export const REQUIRED_REGIONS: readonly string[] = REQUIRED_REGION_IDS;

/** Complexity ceiling for one authored body's uischema (rung-4 perf floor). */
export const PERF_BUDGET = { maxNodes: 600, maxDepth: 14 } as const;

/** Count uischema nodes + max nesting depth (a cheap proxy for render cost). */
export function measureUiSchema(node: unknown, depth = 0): { nodes: number; depth: number } {
  if (node === null || typeof node !== 'object') return { nodes: 0, depth };
  let nodes = 1;
  let maxDepth = depth;
  const elements = (node as { elements?: unknown[] }).elements;
  if (Array.isArray(elements)) {
    for (const child of elements) {
      const m = measureUiSchema(child, depth + 1);
      nodes += m.nodes;
      if (m.depth > maxDepth) maxDepth = m.depth;
    }
  }
  return { nodes, depth: maxDepth };
}
