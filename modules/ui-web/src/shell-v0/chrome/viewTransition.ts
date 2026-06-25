// SPDX-License-Identifier: Apache-2.0
/**
 * View Transitions for surface switching — tempdoc 609 §R (T1.1 / NEW-3).
 *
 * Instance-retention keeps each surface's DOM node alive across navigation, so a surface switch is a pure
 * node swap in the Stage. Wrapping that swap in the same-document View Transitions API animates the visual
 * delta for free (browser-default cross-fade on `::view-transition-old/new(root)`), with no animation
 * library.
 *
 * Render-safety: we do NOT defer the state change into the transition callback (that would change when
 * `activeId` becomes observable and could desync callers + the RetainedScroll controller, which depends on
 * each surface's `updateComplete` timing). Instead the caller sets state SYNCHRONOUSLY as before, then calls
 * {@link startSurfaceTransition} — Lit's resulting update is still microtask-pending, so the API captures
 * the "before" snapshot now and the callback simply awaits the flush (host updateComplete + one animation
 * frame so the child Stage's own update paints) to capture "after". Pure progressive enhancement: a no-op
 * where the API is unavailable or the user prefers reduced motion, and a failed/garbage-collected
 * transition never breaks the app (worst case: no animation).
 */
interface ViewTransitionDocument {
  startViewTransition?: (cb: () => Promise<void> | void) => unknown;
}

/** True iff same-document View Transitions are available AND the user has not requested reduced motion. */
export function surfaceTransitionsEnabled(): boolean {
  const doc = document as unknown as ViewTransitionDocument;
  if (typeof doc.startViewTransition !== 'function') return false;
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ?? false;
  return !reduce;
}

/**
 * Animate the surface swap that the host's pending Lit update is about to perform. Call AFTER the
 * synchronous state change that drives the swap. No-op (returns false) when transitions are disabled.
 */
export function startSurfaceTransition(host: { updateComplete: Promise<unknown> }): boolean {
  if (!surfaceTransitionsEnabled()) return false;
  const doc = document as unknown as Required<ViewTransitionDocument>;
  doc.startViewTransition(async () => {
    await host.updateComplete; // shell render → sets the Stage's surface prop
    // One frame so the child Stage's own (separately-scheduled) update flushes + lays out before the
    // API captures the "after" snapshot.
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  });
  return true;
}
