// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — Core canonical strategy registration.
 *
 * Called once at app boot (before plugin install). Each core strategy
 * registers at rank 0; plugin overrides land at rank >= 1 to win.
 *
 * This is the single point where every (aggregate, context) cell the
 * framework supports out of the box is declared. The retroactive
 * Pass-8 sweep walks the registry to enumerate which cells have
 * coverage and which don't.
 */

import { registerOperationButtonStrategy } from './strategies/operationButton.js';
import { registerResourceListItemStrategy } from './strategies/resourceListItem.js';
import { registerHealthEventActivityRowStrategy } from './strategies/healthEventActivityRow.js';
// Tempdoc 549 — canonical (SearchTrace, search-explain) strategy (the SearchIntrospection
// aggregate + searchIntrospectionExplain were retired in Phase E4).
import { registerSearchTraceExplainStrategy } from './strategies/searchTraceExplain.js';
// Tempdoc 543 §12.3 #5 — canonical (Operation, hover-preview) strategy.
import { registerOperationHoverPreviewStrategy } from './strategies/operationHoverPreview.js';
// Tempdoc 543 §13.3.2 — flip 'hover-preview' SurfaceContextKind to
// 'merge' dispatch so multiple strategies stack into one popover.
import { setDispatchPolicy } from './aggregateRegistry.js';
// Side-effect imports: register `<jf-operation>` + `<jf-resource>` +
// `<jf-health-event>` + `<jf-search-introspection>` custom elements.
import './components/JfOperation.js';
import './components/JfResource.js';
import './components/JfHealthEvent.js';
import './components/JfSearchTrace.js';

let bootstrapped = false;

export function bootstrapAggregateSubstrate(): void {
  if (bootstrapped) return;
  bootstrapped = true;
  registerOperationButtonStrategy();
  registerResourceListItemStrategy();
  registerHealthEventActivityRowStrategy();
  registerSearchTraceExplainStrategy();
  registerOperationHoverPreviewStrategy();
  // 'hover-preview' uses 'merge' policy so multiple contributors stack.
  setDispatchPolicy('hover-preview', 'merge');
}

/** Test-only reset. */
export function __resetBootstrap(): void {
  bootstrapped = false;
}
