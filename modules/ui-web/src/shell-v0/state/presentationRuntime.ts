// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 1/3/6 — the active-presentation runtime store + the BODY/LAYOUT apply path.
 *
 * THE single writer for the body + layout tiers of the active Presentation Declaration.
 * (The THEME tier is owned by themeState / `host.theme` — a single-responsibility split:
 * themeState owns colour/appearance, this store owns which surface bodies + region layout
 * are active. A full declaration's apply composes both — see the authoring surface, Move 7.)
 *
 * A surface reads {@link activeBodyFor}(regionId); if a body is present it renders the region
 * through `<jf-declared-surface>` (the Move-3 engine), else its built-in render — so an absent
 * or quarantined body silently degrades to the default (degrade-never-fail, Move 6). The
 * apply path certifies (Move 6) and quarantines failing bodies BEFORE publishing, so a body
 * the gate rejects never reaches a surface.
 *
 * Mirror of the minimal observable-store pattern (uiModeState): module-level state + a
 * listener Set + getter/subscribe/reset — no framework, no singleton ceremony.
 */
import type { SurfaceBodyDeclaration } from '../components/DeclaredSurface.js';
import type {
  PresentationDeclaration,
  PresentationLayout,
} from '../themes/presentationDeclaration.js';
import type { InteractionStatechart } from '../substrates/interaction/index.js';
import {
  certifyPresentation,
  quarantineSurfaces,
  quarantineLayout,
  type ConformanceError,
} from '../themes/conformanceGate.js';

/** The currently-applied body + layout tiers (the theme tier lives in themeState). */
export interface ActivePresentation {
  /** Declaration id, or null when none is applied (built-in default everywhere). */
  readonly id: string | null;
  /** Region/surface id → the authored body the engine renders. */
  readonly bodies: Readonly<Record<string, SurfaceBodyDeclaration>>;
  /** The surface-composition layout tier, or null. */
  readonly layout: PresentationLayout | null;
  /**
   * 569 §14 (Move 8 made operative) — the active behaviour tier: statechart id → the
   * declared interaction statechart. A surface reads {@link activeInteractionFor}(id) to
   * RUN its behaviour from the declaration (createMachine + the gated dispatcher) instead
   * of hand-wiring it. Validated + certified before publish, exactly like bodies.
   */
  readonly interaction: Readonly<Record<string, InteractionStatechart>>;
}

const EMPTY: ActivePresentation = { id: null, bodies: {}, layout: null, interaction: {} };

let active: ActivePresentation = EMPTY;
const listeners = new Set<(a: ActivePresentation) => void>();

/** The active body/layout tiers. */
export function getActivePresentation(): ActivePresentation {
  return active;
}

/** The authored body for a region/surface id, or undefined (→ the surface renders built-in). */
export function activeBodyFor(regionId: string): SurfaceBodyDeclaration | undefined {
  return active.bodies[regionId];
}

/**
 * 569 §14 — the declared interaction statechart for an id, or undefined (→ the surface uses
 * its built-in behaviour). A surface runs this through `createMachine` + the gated dispatcher,
 * so a user re-authoring the interaction tier changes the surface's behaviour by declaration.
 */
export function activeInteractionFor(id: string): InteractionStatechart | undefined {
  return active.interaction[id];
}

/** Subscribe to active-presentation changes; fires immediately with the current value. */
export function subscribePresentation(
  listener: (a: ActivePresentation) => void,
): () => void {
  listeners.add(listener);
  listener(active);
  return () => {
    listeners.delete(listener);
  };
}

function publish(next: ActivePresentation): void {
  active = next;
  for (const l of listeners) l(active);
}

/** The outcome of an apply: gate verdict + which surfaces were quarantined to the default. */
export interface ApplyResult {
  readonly ok: boolean;
  readonly errors: readonly ConformanceError[];
  readonly quarantined: readonly string[];
}

/**
 * Certify a candidate declaration (Move 6), quarantine any failing bodies to the default,
 * and publish the surviving body + layout tiers. A hard-invalid declaration (fails
 * validation) is NOT applied — nothing changes and the errors are returned. A schema-valid
 * declaration with individually-failing bodies applies its passing bodies and reports the
 * quarantined ones (degrade-never-fail).
 */
export function applyPresentationBodies(candidate: unknown): ApplyResult {
  const { verdict, declaration } = certifyPresentation(candidate);
  if (!declaration) {
    return { ok: false, errors: verdict.errors, quarantined: [] };
  }
  let safe: PresentationDeclaration = quarantineSurfaces(
    declaration,
    verdict.quarantinedSurfaces,
  );
  // Required-presence (Move 4/6): an authored layout omitting a required region falls back to
  // the default layout — the user skeleton cannot orphan a trust/operability-critical region.
  if (verdict.quarantinedLayout) safe = quarantineLayout(safe);
  publish({
    id: safe.id,
    bodies: safe.body ?? {},
    layout: safe.layout ?? null,
    interaction: safe.interaction ?? {},
  });
  return {
    ok: verdict.ok,
    errors: verdict.errors,
    quarantined: verdict.quarantinedSurfaces,
  };
}

/**
 * Runtime quarantine (Move 6): drop one active body so its region reverts to the built-in render.
 * Used by the apply-time runtime gate when a rendered surface fails the live conformance audit
 * (e.g. computed-contrast failure a static check could not resolve) — degrade-never-fail. No-op
 * if the region is not active.
 */
export function quarantineActiveSurface(regionId: string): boolean {
  if (!(regionId in active.bodies)) return false;
  const bodies = { ...active.bodies };
  delete bodies[regionId];
  publish({ ...active, bodies });
  return true;
}

/** Clear the active presentation — every surface reverts to its built-in render. */
export function clearPresentation(): void {
  publish(EMPTY);
}

/** Test-only reset. */
export function __resetPresentationForTest(): void {
  active = EMPTY;
  listeners.clear();
}
