// SPDX-License-Identifier: Apache-2.0
/**
 * responsiveState — tempdoc 574 §17 F1: the ONE viewport-breakpoint authority.
 *
 * §16/F1: the viewport is a globally-singular thing, yet each component re-created its own
 * `window.matchMedia(...)` listener (a per-instance wiring of a global concern — and the split-stage
 * second instance makes the duplication real). This is the single `matchMedia` per breakpoint, fanned
 * out to subscribers (the `inferencePoll`/`statusPoll` pattern), so every surface shares one listener.
 *
 * Default when `matchMedia` is unavailable (tests/SSR): WIDE (`true`) — matching the prior per-component
 * `mql ? mql.matches : true` fallback.
 */
const WIDE_QUERY = '(min-width: 64rem)';

type Listener = (wide: boolean) => void;
const listeners = new Set<Listener>();
let mql: MediaQueryList | null = null;
let initialized = false;

function ensure(): void {
  if (initialized) return;
  initialized = true;
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    mql = window.matchMedia(WIDE_QUERY);
    mql.addEventListener('change', (e) => {
      for (const l of listeners) l(e.matches);
    });
  }
}

/**
 * Is the viewport at or above the wide breakpoint? (true when matchMedia is unavailable.)
 * Queried fresh each call so it always reflects the current viewport — the cached `mql` is used
 * only for the change listener (a stale cache would also break test matchMedia mocks).
 */
export function isWideViewport(): boolean {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    return window.matchMedia(WIDE_QUERY).matches;
  }
  return true;
}

/** Subscribe to wide-breakpoint changes. Fires once immediately with the current value. */
export function subscribeWide(listener: Listener): () => void {
  ensure();
  listeners.add(listener);
  listener(isWideViewport());
  return () => {
    listeners.delete(listener);
  };
}
