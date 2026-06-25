// SPDX-License-Identifier: Apache-2.0
/**
 * 569 §19 Phase 6 — the hint→starter-node catalog for the visual presentation editor.
 *
 * The editor's PALETTE is derived from the renderer registry (`listXUiRenderers()` ∪
 * `listLazyHints()`) INTERSECT this catalog's keys — so the editor can only offer LEGAL,
 * registered, AUTHORABLE elements (the same closed-vocabulary guarantee the conformance gate
 * enforces, surfaced at compose time). Each authorable hint maps to a starter node: a JSON-Schema
 * property fragment (carrying the `x-ui-renderer` hint — that is what the dispatcher's tester reads)
 * and the matching `Control` uischema element that scopes to it.
 *
 * This is the discovery step that keeps the palette honest: a renderer that is registered but NOT in
 * this catalog (the test hints `alpha`/`h`/`zeta`, or the surface-coupled `search-results` /
 * `source-chips` that only make sense inside their own surface) is deliberately not an authoring
 * primitive, so it never appears in the palette. Adding a new authoring primitive is a one-row edit
 * here — the deliberate, reviewable seam.
 */
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

/** A starter node a palette insertion contributes: one schema property + one uischema Control. */
export interface HintStarterNode {
  /** The JSON-Schema property fragment (carries the `x-ui-renderer` hint). */
  readonly schemaProperty: Record<string, unknown>;
  /** Builds the uischema Control that scopes to the property at `key`. */
  uischemaControl(key: string): UISchemaElement;
  /** Human label shown on the palette button. */
  readonly paletteLabel: string;
}

/** A Control scoping to `#/properties/<key>` — the canonical uischema leaf for an x-ui-renderer. */
function control(key: string): UISchemaElement {
  return { type: 'Control', scope: `#/properties/${key}` } as UISchemaElement;
}

/**
 * The authorable renderer primitives. Each maps to a schema-kind that the renderer expects:
 * `option-button-group` → a string enum; `toggle-switch` → a boolean; the bespoke surface
 * renderers (`list-items` / `folder-card` / `shortcuts-table`) → arrays; `metric-card` → a number.
 * The defaults are deliberately minimal-but-valid so an inserted node certifies immediately.
 */
const CATALOG: Readonly<Record<string, HintStarterNode>> = {
  'option-button-group': {
    paletteLabel: 'Option buttons',
    schemaProperty: {
      type: 'string',
      enum: ['one', 'two'],
      title: 'Choice',
      'x-ui-renderer': 'option-button-group',
      'x-enum-labels': { one: 'One', two: 'Two' },
    },
    uischemaControl: control,
  },
  'toggle-switch': {
    paletteLabel: 'Toggle',
    schemaProperty: {
      type: 'boolean',
      title: 'Toggle',
      description: 'A boolean switch',
      'x-ui-renderer': 'toggle-switch',
    },
    uischemaControl: control,
  },
  'list-items': {
    paletteLabel: 'List',
    schemaProperty: {
      type: 'array',
      title: 'Items',
      items: { type: 'string' },
      'x-ui-renderer': 'list-items',
    },
    uischemaControl: control,
  },
  'metric-card': {
    paletteLabel: 'Metric',
    schemaProperty: {
      type: 'number',
      title: 'Metric',
      'x-ui-renderer': 'metric-card',
    },
    uischemaControl: control,
  },
  'folder-card': {
    paletteLabel: 'Folder cards',
    schemaProperty: {
      type: 'array',
      title: 'Folders',
      items: { type: 'object' },
      'x-ui-renderer': 'folder-card',
    },
    uischemaControl: control,
  },
  'shortcuts-table': {
    paletteLabel: 'Shortcuts table',
    schemaProperty: {
      type: 'array',
      title: 'Shortcuts',
      items: { type: 'object' },
      'x-ui-renderer': 'shortcuts-table',
    },
    uischemaControl: control,
  },
};

/** Is `hint` an authorable primitive (has a starter node)? */
export function isAuthorableHint(hint: string): boolean {
  return Object.prototype.hasOwnProperty.call(CATALOG, hint);
}

/** The starter node for an authorable hint, or undefined. */
export function starterNodeForHint(hint: string): HintStarterNode | undefined {
  return CATALOG[hint];
}

/** The authorable hints, sorted — the palette's universe before it intersects the live registry. */
export function listAuthorableHints(): readonly string[] {
  return Object.keys(CATALOG).sort();
}

/** A fresh, unique property key for an inserted node of `hint` given the already-used keys. */
export function freshKeyForHint(hint: string, used: ReadonlySet<string>): string {
  const base = hint.replace(/-/g, '_');
  if (!used.has(base)) return base;
  for (let i = 2; ; i++) {
    const candidate = `${base}_${i}`;
    if (!used.has(candidate)) return candidate;
  }
}

/** A re-export of the JSON-Schema type so callers don't import @jsonforms directly. */
export type { JsonSchema, UISchemaElement };
