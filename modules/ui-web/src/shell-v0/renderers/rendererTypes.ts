// SPDX-License-Identifier: Apache-2.0
/**
 * Leaf types + rank constants for the Shell V0 Lit JSON Forms renderer
 * registry.
 *
 * Extracted from `registry.ts` to break the renderer import cycle
 * (tempdoc 530 UI-cycle gate). Previously every control/layout module
 * imported `RendererTester` / `RANK_*` from `registry.ts`, while
 * `registry.ts` imported each control/layout for its tester — a
 * bidirectional edge madge flags as circular. These declarations have
 * no dependency on the renderer graph, so housing them in this leaf
 * (which imports only `@jsonforms/core` types) lets controls/layouts
 * depend on the types without depending on the aggregator.
 */

import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

/**
 * Tester rank. Per JSON Forms convention, higher wins; -1 = no match.
 */
export type RendererRank = number;

/** Tester signature: returns a rank for the given uischema + schema. */
export type RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
) => RendererRank;

/**
 * Registered renderer — a tester paired with the custom-element tag
 * to instantiate when the tester wins.
 */
export interface RendererEntry {
  tester: RendererTester;
  tag: string;
}

/**
 * Default rank for an exact-type-match tester. Higher than the
 * fallback (none) but below specialized renderers (e.g., a date
 * picker that recognizes `format: "date"`).
 */
export const RANK_BASIC_CONTROL = 1;

/** Rank for layout renderers (matches JSON Forms convention). */
export const RANK_LAYOUT = 1;

/**
 * Rank for specialized controls that match on schema metadata
 * beyond `type` (e.g., `format`, `enum`).
 */
export const RANK_SPECIALIZED_CONTROL = 2;

/**
 * Rank for structural controls (object / array) whose tester
 * matches on `type` alone but the renderer recurses into children.
 * Same effective rank as basic; structural renderers don't compete
 * with primitive renderers (different `type` field).
 */
export const RANK_STRUCTURAL_CONTROL = 1;
