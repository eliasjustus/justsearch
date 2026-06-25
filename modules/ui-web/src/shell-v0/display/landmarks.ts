// SPDX-License-Identifier: Apache-2.0
/**
 * Landmark projection — tempdoc 559 Authority II (accessibility as a projection).
 *
 * THE single mapping from a surface/region's declared `Placement` (the catalog
 * authority, api/types/surface.ts) to its ARIA landmark role. The shell applies
 * the projected role to each grid region by the placement it hosts — so landmark
 * roles are *derived from the catalog*, never hand-stamped per region, and a new
 * Placement kind is landmarked the moment it is mapped here (the `a11y-closure`
 * gate enumerates this map against the Placement union).
 */
import type { Placement } from '../../api/types/surface.js';

/** ARIA landmark role for a placement, or null if it is not a landmark region. */
export function placementToLandmarkRole(placement: Placement): string | null {
  switch (placement) {
    case 'RAIL':
      return 'navigation';
    case 'STAGE':
      return 'main';
    case 'STATUS':
      return 'contentinfo';
    case 'DRAWER':
      return 'complementary';
    case 'MODAL':
      // Modal surfaces own their own dialog semantics; not a page landmark.
      return null;
    case 'HUD':
    case 'COMMAND':
    case 'DEEPLINK':
    case 'HEADLESS_AGENT_TOOL':
      return null;
  }
}

/**
 * Landmark role for a region rendered NESTED inside a host that already carries the page landmark
 * (the 569 `DeclaredSurface` engine renders inside the shell STAGE, which is itself `role="main"`).
 * A nested region must never re-emit `main` — that produces axe `landmark-no-duplicate-main` +
 * `landmark-main-is-top-level`. `main` becomes a generic `region` (a repeatable landmark, exposed
 * only when given an accessible name); every other derived role passes through unchanged. The
 * top-level `placementToLandmarkRole` stays the authority for the shell's own grid regions.
 */
export function nestedLandmarkRole(placement: Placement): string | null {
  const role = placementToLandmarkRole(placement);
  return role === 'main' ? 'region' : role;
}
