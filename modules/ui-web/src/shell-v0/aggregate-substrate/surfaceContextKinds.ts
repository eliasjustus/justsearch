// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — SurfaceContextKind enumeration.
 *
 * Named substrate for where a wire aggregate can be rendered. Each
 * context defines what the strategy's `ctx` parameter looks like —
 * see `SurfaceContextOfMap` below.
 *
 * Population history:
 *  - 511 (initial): 10 declared kinds (button, palette-row,
 *    history-entry, activity-row, notification-toast,
 *    inline-mention, inspector-tab, list-item, table-cell,
 *    badge-strip).
 *  - 511-followup Track D: trimmed to 2 (button, list-item). The
 *    other 8 were forward-declared substrate with no production
 *    consumer surface.
 *  - 511-followup-A: `activity-row` reintroduced WITH consumer
 *    (HealthSurface.renderEvents mounts <jf-health-event
 *    context="activity-row">).
 *
 * Adding a new context is a substrate change: declare it here, add
 * its prop shape to `SurfaceContextOfMap`, register at least one
 * canonical strategy, AND ensure at least one production surface
 * mounts an aggregate in the context. Plugins may extend the set
 * via TypeScript module augmentation.
 */

export interface SurfaceContextOfMap {
  /** Primary affordance — a clickable Operation. */
  button: { density?: 'compact' | 'comfortable' };
  /** Generic list row (wraps the existing Resource-view dispatch). */
  'list-item': { density?: 'compact' | 'comfortable' };
  /** HealthEvent / event-stream row. */
  'activity-row': { showTime?: boolean };
  /**
   * Tempdoc 525: SearchIntrospection explain panel — per-search decision
   * + timing + degradation summary rendered alongside the result list.
   * Structurally distinct from item-in-list contexts (button / list-item /
   * activity-row); the panel renders a single per-request aggregate.
   */
  'search-explain': { density?: 'compact' | 'comfortable' };
  /**
   * Tempdoc 543 §12.3 #5 + Slice 8: Hover-preview popover body.
   * Strategies contribute body content; the kernel-rendered
   * <jf-hover-preview-host> owns the lifecycle (debounce, dismissal,
   * focus restoration). Multiple strategies stack via the 'merge'
   * dispatch policy (Slice 6) flipped at bootstrap. The strategy
   * receives the trigger element for optional layout decisions and
   * renders a content fragment only.
   */
  'hover-preview': { triggerEl?: HTMLElement };
}

export type SurfaceContextKind = keyof SurfaceContextOfMap;

export type SurfaceContextOf<C extends SurfaceContextKind> =
  SurfaceContextOfMap[C];

export const SURFACE_CONTEXT_KINDS: readonly SurfaceContextKind[] = [
  'button',
  'list-item',
  'activity-row',
  'search-explain',
  'hover-preview',
] as const;

export function isSurfaceContextKind(value: string): value is SurfaceContextKind {
  return (SURFACE_CONTEXT_KINDS as readonly string[]).includes(value);
}
