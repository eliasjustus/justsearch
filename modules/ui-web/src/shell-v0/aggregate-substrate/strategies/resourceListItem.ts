// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — Canonical (Resource, list-item) strategy.
 *
 * Renders a Resource as a list-item using `<jf-resource-view>` (slice
 * 3a.1.9 §A.6 — the existing Resource-view substrate consumer that
 * dispatches via resourceRegistry to the appropriate renderer for the
 * Resource's Category). The strategy layers on:
 *   - audience gate (resourceVisibleTo) — returns nothing on deny
 *   - data attributes for every Resource field so future styling
 *     passes and the retroactive Pass-8 sweep have full coverage
 *
 * The "wraps" framing from the tempdoc: this strategy does NOT
 * replace the existing Resource-view dispatch (category/hint/density
 * → tag). It places the existing dispatch in the (Resource, list-
 * item) cell of the aggregate matrix, and the surrounding
 * <jf-resource> aggregate component is the new sanctioned
 * consumption point.
 */

import { html, nothing } from 'lit';
import type { Audience, Resource } from '../../../api/types/registry.js';
import type { AggregateStrategy } from '../aggregateRegistry.js';
import { registerAggregateStrategy } from '../aggregateRegistry.js';
import { assertFieldRoles, type FieldRoles } from '../assertExhaustive.js';
import { resourceVisibleTo } from '../queryPrimitives.js';
import '../../components/ResourceView.js';

/**
 * Field-role classification — drives the behavioral Pass-8 test.
 * Every Resource key must be listed; TypeScript fails the literal
 * if a key is missing.
 *
 * After 511-followup Track D's data-* attribute removal, this
 * strategy reads `res.id` directly and `res.audience` via the
 * gate. All other fields are routed via the catalog lookup that
 * `<jf-resource-view>` performs internally given the resource-id —
 * mutation must NOT change the strategy-level output.
 */
export const RESOURCE_LIST_ITEM_ROLES: FieldRoles<Resource> = {
  id: 'visual',                 // resource-id attr on jf-resource-view
  audience: 'gate',             // resourceVisibleTo
  // Routed via id to ResourceView's catalog lookup + the existing
  // category/hint/density dispatch (slice 3a.1.9).
  presentation: 'routing',
  schema: 'routing',
  category: 'routing',
  subscriptionMode: 'routing',
  endpoint: 'routing',
  kind: 'routing',
  history: 'routing',
  recovery: 'routing',
  provenance: 'routing',
  privacy: 'routing',
  itemOperations: 'routing',
  collectionOperations: 'routing',
  primaryKey: 'routing',
  consumers: 'routing',
  emissionPolicy: 'routing',
  // Tempdoc 571 §4c: the altitude role is a derivation input read backend-side; the FE list item routes
  // it through the catalog lookup like the other metadata fields (no visual / gate role of its own).
  role: 'routing',
};
assertFieldRoles<Resource>(RESOURCE_LIST_ITEM_ROLES);

export interface ResourceListItemHostExtensions {
  viewerAudience?: Audience;
}

const DEFAULT_VIEWER_AUDIENCE: Audience = 'USER';

export const resourceListItemStrategy: AggregateStrategy<
  'Resource',
  'list-item'
> = (res, _ctx, host) => {
  const viewerAudience =
    (host as typeof host & ResourceListItemHostExtensions).viewerAudience ??
    DEFAULT_VIEWER_AUDIENCE;
  if (!resourceVisibleTo(res, viewerAudience)) {
    return nothing;
  }

  // The audience gate is the only behavioral consumption today.
  // Field-classification annotations live in RESOURCE_LIST_ITEM_CONSUMED
  // (above); the behavioral Pass-8 test verifies actual consumption
  // by mutating each field and asserting output diff.
  return html`<jf-resource-view resource-id=${res.id}></jf-resource-view>`;
};

export function registerResourceListItemStrategy(): () => void {
  return registerAggregateStrategy({
    aggregate: 'Resource',
    context: 'list-item',
    rank: 0,
    strategy: resourceListItemStrategy,
    source: 'core',
  });
}

void resourceListItemStrategy;
