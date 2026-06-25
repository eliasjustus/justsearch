// SPDX-License-Identifier: Apache-2.0
/**
 * RetainedScroll — tempdoc 609 §R (P4): the one reusable scroll save/restore for retained surfaces.
 *
 * Instance-retention (chrome/Shell.ts `_surfaceElCache`) keeps a surface's element + `@state` across
 * navigation, but DOM scroll offset is the one thing it does NOT preserve: detaching/re-attaching a node
 * resets `scrollTop`. SearchSurface solved this bespoke with a `savedScrollTop` instance field captured in
 * `disconnectedCallback` and restored in `connectedCallback`; this controller generalizes that pattern so
 * any retained surface gets eviction-safe scroll restoration with one line, instead of re-implementing it.
 *
 * Usage (in a surface that extends JfElement):
 *   private scroll = new RetainedScroll(this, () => this.shadowRoot?.querySelector('.body') ?? null);
 *
 * Lifecycle (via Lit's ReactiveController hooks):
 *  - `hostDisconnected` (navigate away) → capture the scroller's current `scrollTop`.
 *  - `hostConnected` (navigate back) → after the next render settles, restore it. On first mount the saved
 *    value is 0, so restoring is a harmless no-op.
 *
 * The scroller is resolved lazily via the `getScroller` callback (not captured once) because the element
 * may not exist at construction time and is re-rendered across reconnects.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

export class RetainedScroll implements ReactiveController {
  private readonly host: ReactiveControllerHost;
  private readonly getScroller: () => HTMLElement | null;
  private saved = 0;

  constructor(host: ReactiveControllerHost, getScroller: () => HTMLElement | null) {
    this.host = host;
    this.getScroller = getScroller;
    host.addController(this);
  }

  hostDisconnected(): void {
    const el = this.getScroller();
    if (el) this.saved = el.scrollTop;
  }

  hostConnected(): void {
    // Restore after the surface's content has re-rendered, else the scroll target may not exist / be the
    // wrong height yet. updateComplete resolves once the reconnected render has flushed.
    void this.host.updateComplete.then(() => {
      const el = this.getScroller();
      if (el) el.scrollTop = this.saved;
    });
  }

  /**
   * Reset the retained offset to the top — for an intent-driven clear (e.g. emptying the search box) so a
   * stale scroll position doesn't resurrect against a fresh/empty result set. Scrolls the live scroller now
   * (the surface is connected when the user clears) and zeroes the saved value for the next reconnect.
   */
  reset(): void {
    this.saved = 0;
    const el = this.getScroller();
    if (el) el.scrollTop = 0;
  }
}
