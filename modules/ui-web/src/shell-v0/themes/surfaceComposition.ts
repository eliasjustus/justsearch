// SPDX-License-Identifier: Apache-2.0
/**
 * 569 — the surface-composition DSL tier (the new authored tier beyond seeds/roles/body).
 *
 * Projects an authored {@link PresentationLayout} into the ordered, visible set of regions
 * for a given data context: regions whose `visibleWhen` binding evaluates true (or is absent)
 * are kept, ordered by `order` (ascending, stable). Section order + conditional show/hide are
 * thus authored as DATA — distinct from content projectors (the leaves) and `composeGridStyles`
 * (the grid frame). The binding is evaluated by the one non-Turing-complete evaluator.
 */
import { evaluateBinding } from './bindingExpr.js';
import type { PresentationLayout, LayoutRegion } from './presentationDeclaration.js';

/** Resolve the visible, ordered regions for a data context. */
export function resolveVisibleRegions(
  layout: PresentationLayout,
  ctx: Record<string, unknown>,
): readonly LayoutRegion[] {
  return layout.regions
    .filter((r) => r.visibleWhen === undefined || evaluateBinding(r.visibleWhen, ctx))
    .map((r, idx) => ({ r, idx }))
    .sort((a, b) => {
      const oa = a.r.order ?? 0;
      const ob = b.r.order ?? 0;
      if (oa !== ob) return oa - ob;
      return a.idx - b.idx; // stable for equal order
    })
    .map(({ r }) => r);
}
