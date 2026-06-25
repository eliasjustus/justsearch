// SPDX-License-Identifier: Apache-2.0
/**
 * adaptationProfile — the ONE adaptation/accessibility authority (569 §19 Seam 4).
 *
 * Generalises `themeState.applyAppearance`'s "write global DOM state once, let the cascade re-project
 * every surface" pattern to the user-selectable adaptation axes: density, contrast, and motion. Because
 * the safety facets are co-projected, an adaptation axis costs O(1) at the projection layer with
 * structural totality — one switch re-projects every present AND future surface, and the conformance
 * gate refuses any surface that escapes it. This replaces the "add another global toggle" trajectory.
 *
 * Storage: the axes persist per-profile on `userConfig` (already persisted + signal-projected) — density
 * in `userConfig.density` (which threads to the renderers via the DensityController), contrast/motion in
 * `userConfig.accessibilityProfile`. This module is the single writer + projector.
 *
 * NOTE (cognitive-simplification): deferred — it needs render-level node omission (rung-2), coupled to
 * the dropped Seam 3; not a CSS-cascade axis. Only density/contrast/motion are projected here.
 */
import { getDocument, mutateDocument } from './UserStateDocument.js';
import type { DensityVariant } from '../renderers/userConfig.js';

export interface AdaptationProfile {
  readonly density?: DensityVariant;
  readonly contrast?: 'normal' | 'high';
  readonly motion?: 'full' | 'reduced';
}

/** The current adaptation profile, read from the persisted `userConfig`. */
export function getAdaptationProfile(): AdaptationProfile {
  const cfg = getDocument().userConfig;
  return {
    ...(cfg.density !== undefined ? { density: cfg.density } : {}),
    ...(cfg.accessibilityProfile?.contrast !== undefined
      ? { contrast: cfg.accessibilityProfile.contrast }
      : {}),
    ...(cfg.accessibilityProfile?.motion !== undefined
      ? { motion: cfg.accessibilityProfile.motion }
      : {}),
  };
}

/**
 * Project the profile to global DOM state — the cascade re-projects every surface. Each axis is only
 * touched when explicitly set, so a fresh profile does not fight the legacy `applyAppearance` contrast.
 * Density is read by the renderers via `userConfig` (the DensityController thread), not a global class.
 */
function projectToDom(p: AdaptationProfile): void {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  if (p.contrast !== undefined) root.classList.toggle('high-contrast', p.contrast === 'high');
  if (p.motion !== undefined) root.classList.toggle('motion-reduced', p.motion === 'reduced');
}

/**
 * THE single writer: merge `partial` into the persisted profile (per-profile `userConfig`), then project
 * the merged result to global DOM state. Omitted axes are untouched (like `applyAppearance`).
 */
export function applyAdaptationProfile(partial: AdaptationProfile): void {
  mutateDocument((doc) => {
    const cfg = doc.userConfig;
    const nextAccessibility = {
      ...cfg.accessibilityProfile,
      ...(partial.contrast !== undefined ? { contrast: partial.contrast } : {}),
      ...(partial.motion !== undefined ? { motion: partial.motion } : {}),
    };
    return {
      ...doc,
      userConfig: {
        ...cfg,
        ...(partial.density !== undefined ? { density: partial.density } : {}),
        accessibilityProfile: nextAccessibility,
      },
    };
  });
  projectToDom(getAdaptationProfile());
}

/**
 * Boot restore: project the persisted profile to global DOM. The persisted density already threads via
 * `userConfig`; this re-asserts the contrast/motion classes. Call AFTER `restoreAppearanceOnBoot` so an
 * explicitly-set profile axis wins over the legacy appearance value.
 */
export function restoreAdaptationProfileOnBoot(): void {
  projectToDom(getAdaptationProfile());
}
