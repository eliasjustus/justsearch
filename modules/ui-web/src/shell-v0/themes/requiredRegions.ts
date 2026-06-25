// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 4/6 — the operability floor: regions that MUST survive any authored layout.
 *
 * A user authors the layout (the surface-composition tier), but certain regions are operability-
 * or trust-critical and must be present under ANY composition — the trusted approval anchor
 * (Move 4) and the primary results region. This team-owned set is the single authority for that
 * floor: the mirror of {@link RESERVED_COMPONENTS} (what a layout may NOT mount) for what a layout
 * must INCLUDE. The conformance gate (Move 6) quarantines a layout that omits any of these to the
 * default layout (degrade-never-fail), so a user skeleton can never silently orphan a required
 * region — the "operability invariance / required-presence" guarantee the design left undesigned.
 *
 * Forward: this set projects from the surface catalog's required-region declaration; today it is
 * an explicit team-owned floor, exactly as {@link RESERVED_COMPONENTS} is a team-owned denylist.
 */
import type { PresentationLayout } from './presentationDeclaration.js';
import { DEFAULT_LAYOUT } from '../layout/LayoutManifest.js';

/**
 * The single content zone that anchors EVERY built-in layout (`core.default`/`focus`/`zen`/`split`
 * all keep `stage`; `rail`/`statusBar` are legitimately hidden in focus/zen, so they are NOT floor).
 */
const CONTENT_ZONE = 'stage';

/**
 * Region ids any authored layout MUST include (the operability floor) — PROJECTED from the real
 * layout authority ({@link LayoutManifest} zones), not invented ids. If `stage` is ever renamed in
 * the authority this list empties (drift-fails open rather than guarding a phantom), so the floor
 * can never reference an id that does not exist. (Earlier this hardcoded `core.approval`/`core.results`
 * — ids nothing declared; that was a phantom-id defect, now removed.)
 */
export const REQUIRED_REGION_IDS: readonly string[] =
  CONTENT_ZONE in DEFAULT_LAYOUT.zones ? [CONTENT_ZONE] : [];

/** The required region ids the authored layout omits (empty = the floor is satisfied). */
export function missingRequiredRegions(layout: PresentationLayout): readonly string[] {
  const present = new Set(layout.regions.map((r) => r.id));
  return REQUIRED_REGION_IDS.filter((id) => !present.has(id));
}

/**
 * 569 §14 — the present-but-hidden loophole. Presence alone is not enough: an author could
 * INCLUDE a required region and then make it conditional via `visibleWhen`, so a binding that
 * evaluates false orphans the trust/operability-critical region while passing the presence check.
 * A required region is **unconditionally** present by definition, so it may carry NO `visibleWhen`
 * — the author has no field to gate it. This returns the required region ids that carry one (a
 * visibility violation → quarantine to the default, same as omission). Occlusion-by-overlap is not
 * a separate vector: the layout basis is a closed set of non-overlapping zones (composition, not
 * free positioning), so a region cannot be authored to cover another.
 */
export function hiddenRequiredRegions(layout: PresentationLayout): readonly string[] {
  const required = new Set(REQUIRED_REGION_IDS);
  return layout.regions
    .filter((r) => required.has(r.id) && r.visibleWhen !== undefined)
    .map((r) => r.id);
}
