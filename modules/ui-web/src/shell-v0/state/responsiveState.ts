// SPDX-License-Identifier: Apache-2.0
/**
 * responsiveState — tempdoc 574 §17 F1: the ONE wide-layout breakpoint authority.
 *
 * §16/F1: each component used to re-create its own `window.matchMedia(...)` listener (a per-instance
 * wiring of a global concern — and the split-stage second instance makes the duplication real). This is
 * the single breakpoint decision, fanned out to subscribers (the `inferencePoll`/`statusPoll` pattern).
 *
 * 798 round 8 — the decision is made against the width the READING SURFACE actually gets, not the raw
 * viewport. The viewport is not the surface: the Shell rail (11rem expanded) and the surface's own 1rem
 * padding come off the top, so a 1040px window leaves the conversation zone 832px while a
 * `(min-width: 64rem)` MEDIA query still reported "wide" and committed the layout to tracks needing
 * 888px — a permanent 56px overflow measured against the wrong box. The surface that owns the multi-zone
 * grid reports its measured content-box inline size through {@link reportLayoutWidth}; the CSS half of
 * the same decision is a `@container` query on that same box (see `unifiedChatStyles.ts`'s
 * `container-type: inline-size` host), so mount gates and layout can no longer disagree.
 *
 * Until a width is reported (SSR, unit tests, no surface mounted) this falls back to the viewport media
 * query — the pre-798 behaviour, and WIDE when `matchMedia` is unavailable.
 */
const WIDE_QUERY = '(min-width: 64rem)';

/** The wide-layout breakpoint, in rem — the same 64rem the generated `@container` query uses. */
const WIDE_LAYOUT_REM = 64;

type Listener = (wide: boolean) => void;
const listeners = new Set<Listener>();
let mql: MediaQueryList | null = null;
let initialized = false;

/**
 * The measured content-box inline size of each mounted reading surface. Keyed by reporter because the
 * split stage can mount two surfaces at once (the §16/F1 duplication this module exists for): the
 * NARROWEST one governs, so a half-width pane can never talk the shared decision into a layout that
 * does not fit it, and a surface unmounting cannot clear a still-mounted neighbour's measurement.
 */
const reported = new Map<object, number>();

/** The root font size in px (the unit `64rem` is resolved against), defaulting to the CSS initial 16. */
function rootFontPx(): number {
  if (typeof document === 'undefined' || typeof getComputedStyle !== 'function') return 16;
  const px = Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
  return Number.isFinite(px) && px > 0 ? px : 16;
}

/** The narrowest reported surface width in px, or null when nothing has reported a real measurement. */
function layoutWidthPx(): number | null {
  let min: number | null = null;
  for (const w of reported.values()) min = min === null ? w : Math.min(min, w);
  return min;
}

/**
 * Is the reading surface wide enough for the multi-zone layout? Reads the measured surface width when
 * one is available, else the viewport media query (true when `matchMedia` is unavailable).
 */
export function isWideLayout(): boolean {
  const measured = layoutWidthPx();
  if (measured !== null) return measured >= WIDE_LAYOUT_REM * rootFontPx();
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    return window.matchMedia(WIDE_QUERY).matches;
  }
  return true;
}

function notify(): void {
  const wide = isWideLayout();
  for (const l of listeners) l(wide);
}

/**
 * Report a reading surface's measured content-box inline size (px), or `null` to withdraw it on unmount.
 * A non-positive width is not a measurement (a detached or not-yet-laid-out element) and is withdrawn
 * rather than believed. Subscribers are notified only when the decision actually flips.
 */
export function reportLayoutWidth(reporter: object, px: number | null): void {
  const before = isWideLayout();
  if (px === null || !(px > 0)) reported.delete(reporter);
  else reported.set(reporter, px);
  if (isWideLayout() !== before) notify();
}

function ensure(): void {
  if (initialized) return;
  initialized = true;
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    mql = window.matchMedia(WIDE_QUERY);
    // Report the AUTHORITY's answer, not the raw `e.matches` — a measured surface width overrides the
    // viewport, so forwarding the media query's own boolean would contradict the container query.
    mql.addEventListener('change', () => notify());
  }
}

/** Subscribe to wide-breakpoint changes. Fires once immediately with the current value. */
export function subscribeWide(listener: Listener): () => void {
  ensure();
  listeners.add(listener);
  listener(isWideLayout());
  return () => {
    listeners.delete(listener);
  };
}
