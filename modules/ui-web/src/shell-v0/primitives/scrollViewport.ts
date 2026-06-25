// SPDX-License-Identifier: Apache-2.0
/**
 * scrollViewport — tempdoc 565 §13/§19: the spatial "reading position" half of the run-spine minimap.
 *
 * §13 specified a spine "dual-bound to the reading position", but only the active-ITEM ring was built;
 * the spatial binding — a viewport WINDOW (like a code-editor minimap / scrollbar thumb) showing which
 * slice of the conversation is currently on screen — was never built. Without it the spine is just
 * scattered markers: a long answer body (no markers) is un-navigable and the full track height is
 * meaningless. This is the pure scroll-window math (the reusable core any minimap needs): map a scroll
 * container's metrics to the [top, bottom] fractions of its content that are currently visible.
 */
export interface ViewportWindow {
  /** Fraction (0..1) of the content scrolled past the top of the viewport. */
  readonly topFrac: number;
  /** Fraction (0..1) of the content at the bottom of the viewport. */
  readonly botFrac: number;
}

/**
 * The visible window of a scroll container as content fractions, or `null` when the content is not
 * scrollable (it all fits → no "you are here" box to draw). Clamped to [0,1].
 */
export function viewportWindow(
  scrollTop: number,
  clientHeight: number,
  scrollHeight: number,
): ViewportWindow | null {
  if (!(scrollHeight > 0) || !(clientHeight > 0) || clientHeight >= scrollHeight) {
    return null; // everything fits (or unmeasured) — nothing to indicate
  }
  const clamp = (v: number) => Math.min(1, Math.max(0, v));
  return {
    topFrac: clamp(scrollTop / scrollHeight),
    botFrac: clamp((scrollTop + clientHeight) / scrollHeight),
  };
}
