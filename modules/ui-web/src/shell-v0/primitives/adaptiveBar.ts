// SPDX-License-Identifier: Apache-2.0
/**
 * adaptiveBar — tempdoc 559 Part II, Authority VI (Adaptivity): the one
 * adaptive-region primitive.
 *
 * Completes I Layout over the available-space condition. A horizontal bar that,
 * when space runs out, **overflows its LOWEST-priority items into a single "…"
 * control instead of clipping** (the status bar's `overflow:hidden` silent clip
 * at ≤720px). The caller renders its items in PRIORITY order (most-important
 * first — the `StatusBarItem` registry already sorts by `priority`); the
 * controller measures item widths and tells the host how many leading items fit.
 * Clipping is therefore not a state the bar renders.
 *
 * Two pieces:
 *   - {@link computeVisibleCount} — the pure cut decision (testable without
 *     layout; degrades to "show all" when widths are unmeasured, e.g. in jsdom).
 *   - {@link OverflowController} — a Lit ReactiveController that owns the
 *     `ResizeObserver`, caches intrinsic item widths (so hiding items never
 *     oscillates the cut), and recomputes `visibleCount` on resize. The host
 *     keeps rendering its own items (its CSS stays intact) + a "…" trigger.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

/**
 * How many leading items fit in `available` px. If they ALL fit, no "…" is
 * needed (return all). Otherwise reserve `ellipsisWidth` for the "…" control and
 * fit as many leading (highest-priority) items as possible. With unmeasured
 * widths (all 0, e.g. jsdom) or `available<=0`, returns all (no clip in tests).
 *
 * 559 Authority VI per-item policy: `pinned[i] === true` marks an item that must
 * never be hidden. Because the bar renders in priority order (most-important
 * first) and overflow trims only the trailing tail, honoring "pinned" means the
 * cut can never fall before the last pinned index — the tail that overflows is
 * drawn only from the `normal` items past it. (Pinning a low-priority item is a
 * misconfiguration that simply shows more; pinned is for the small, always-on
 * health signals.)
 */
export function computeVisibleCount(
  widths: readonly number[],
  available: number,
  ellipsisWidth: number,
  pinned?: readonly boolean[],
): number {
  const floor = pinnedFloor(pinned);
  if (available <= 0) return widths.length;
  const total = widths.reduce((a, b) => a + b, 0);
  if (total <= available) return widths.length;
  let used = ellipsisWidth;
  let count = 0;
  for (const w of widths) {
    if (used + w > available) break;
    used += w;
    count++;
  }
  // A pinned item is never hidden: raise the cut to include the last pinned index.
  if (floor > count) count = floor;
  return Math.min(count, widths.length);
}

/** Smallest leading count that keeps every pinned item visible (lastPinnedIndex + 1). */
function pinnedFloor(pinned?: readonly boolean[]): number {
  if (!pinned) return 0;
  let last = -1;
  for (let i = 0; i < pinned.length; i++) if (pinned[i]) last = i;
  return last + 1;
}

interface OverflowOptions {
  /** The measurable item elements, in priority order (most-important first). */
  readonly items: () => HTMLElement[];
  /** The element whose inline-size bounds the items (the bar track). */
  readonly container: () => HTMLElement | null;
  /** A string that changes when the item set / their content changes (→ re-measure). */
  readonly signature: () => string;
  /** Width to reserve for the "…" control (px). */
  readonly reserve?: number;
  /**
   * 559 Authority VI — per-item overflow policy: `true` at index i marks a pinned
   * item that must never be hidden, in the SAME order as `items()`. Omitted →
   * all items are `normal` (priority-only overflow).
   */
  readonly pinned?: () => readonly boolean[];
}

export class OverflowController implements ReactiveController {
  /** Number of leading items to show; the rest go behind "…". Infinity = all. */
  visibleCount: number = Number.POSITIVE_INFINITY;

  private readonly host: ReactiveControllerHost & HTMLElement;
  private readonly opts: OverflowOptions;
  private widths: number[] = [];
  private sig = '';
  private ro: ResizeObserver | null = null;
  private rafPending = false;
  private retries = 0;

  constructor(host: ReactiveControllerHost & HTMLElement, opts: OverflowOptions) {
    this.host = host;
    this.opts = opts;
    host.addController(this);
  }

  hostConnected(): void {
    if (typeof ResizeObserver === 'undefined') return;
    this.ro = new ResizeObserver(() => this.schedule());
    queueMicrotask(() => {
      const c = this.opts.container();
      if (c && this.ro) this.ro.observe(c);
      this.schedule();
    });
  }

  hostDisconnected(): void {
    this.ro?.disconnect();
    this.ro = null;
  }

  hostUpdated(): void {
    const sig = this.opts.signature();
    if (sig === this.sig) return;
    // Content/item set changed → drop cached widths so the next frame re-measures
    // (after layout). Don't measure here: offsetWidth is unreliable inside the
    // host's update cycle, before the browser has laid the items out.
    this.sig = sig;
    this.widths = [];
    this.retries = 0;
    this.schedule();
  }

  /** Defer all measurement/recompute to after layout (rAF), never during update. */
  private schedule(): void {
    if (this.rafPending) return;
    this.rafPending = true;
    const raf =
      typeof requestAnimationFrame !== 'undefined'
        ? requestAnimationFrame
        : (cb: FrameRequestCallback) => queueMicrotask(() => cb(0));
    raf(() => {
      this.rafPending = false;
      this.recompute();
    });
  }

  private recompute(): void {
    const items = this.opts.items();
    if (this.widths.length !== items.length) {
      // Need to (re)measure — but offsetWidth is 0 for hidden items, so show ALL
      // first, then measure on the next frame once laid out.
      if (this.visibleCount !== Number.POSITIVE_INFINITY) {
        this.visibleCount = Number.POSITIVE_INFINITY;
        this.host.requestUpdate();
        this.schedule();
        return;
      }
      const measured = items.map((el) => el.offsetWidth);
      if (measured.length === 0 || measured.some((w) => w === 0)) {
        // Layout not ready yet (first paint) — retry a bounded number of frames.
        if (this.retries++ < 12) this.schedule();
        return;
      }
      this.retries = 0;
      this.widths = measured;
    }
    const c = this.opts.container();
    const available = c ? c.clientWidth : 0;
    const next = computeVisibleCount(
      this.widths,
      available,
      this.opts.reserve ?? 44,
      this.opts.pinned?.(),
    );
    if (next !== this.visibleCount) {
      this.visibleCount = next;
      this.host.requestUpdate();
    }
  }
}
