// SPDX-License-Identifier: Apache-2.0
/**
 * BoundaryReconcilerController — the RESIZE caller of the reconciliation seam (tempdoc 818 §6g C3).
 *
 * The window's boundaries were clamped only inside a gesture, so a window resized after the fact
 * kept whatever widths the previous window justified (§6c finding 7). This controller supplies the
 * missing trigger and nothing else: it owns a `ResizeObserver` and asks the host to reconcile. Every
 * decision lives in `boundaryReconciler.ts`, which is pure.
 *
 * Deliberately the same construction as `adaptiveBar`'s `OverflowController` and `adaptiveDensity`'s
 * `DensityController` — observe in `hostConnected` behind a `queueMicrotask` (the shadow root has to
 * exist before there is a box to observe), disconnect in `hostDisconnected`, and coalesce on a frame
 * so measurement never happens during an update cycle, where box metrics are unreliable. Three
 * near-identical copies of that shape now exist; extracting a shared base is a real option, but the
 * three share ~15 lines of boilerplate and no policy at all, so the duplication is cheaper than the
 * abstraction until something makes them share a reason to change.
 *
 * One measured caveat, recorded because it changes how this is tested rather than how it is written:
 * happy-dom DEFINES `ResizeObserver` but never delivers a callback (818 §6f(f)). A `typeof
 * ResizeObserver === 'undefined'` guard — which the two sibling controllers use — therefore does not
 * trip in unit tests, and an observer is constructed that never fires. Nothing here compensates for
 * that, because nothing should: the reconciliation entry point is callable directly, and the unit
 * tier drives it that way rather than waiting on a frame that will not come.
 */

import type { ReactiveController, ReactiveControllerHost } from 'lit';

/** What the host must expose: a way to be told "the box changed, resolve your boundaries". */
export interface BoundaryReconcilerHost extends ReactiveControllerHost {
  /** Measure the current box and apply `reconcileBoundaries` to it. */
  reconcileBoundaries(): void;
  /** The element whose size decides every boundary — the horizontal track. */
  boundaryBoxElement(): HTMLElement | null;
}

export class BoundaryReconcilerController implements ReactiveController {
  private readonly host: BoundaryReconcilerHost;
  private ro: ResizeObserver | null = null;
  private rafPending = false;
  private observed: HTMLElement | null = null;

  constructor(host: BoundaryReconcilerHost) {
    this.host = host;
    host.addController(this);
  }

  hostConnected(): void {
    if (typeof ResizeObserver === 'undefined') return;
    this.ro = new ResizeObserver(() => this.schedule());
    queueMicrotask(() => this.observe());
  }

  hostDisconnected(): void {
    this.ro?.disconnect();
    this.ro = null;
    this.observed = null;
  }

  hostUpdated(): void {
    // The track is re-created by a render only when the surface's shape changes (the document region
    // mounting, say), so re-point the observer rather than re-creating it — the same move
    // `UnifiedChatView`'s zone observer makes. Cheap when the element is unchanged, which is most
    // renders.
    this.observe();
  }

  /** Point the observer at the current track element, if it moved. */
  private observe(): void {
    const el = this.host.boundaryBoxElement();
    if (!this.ro || el === this.observed) return;
    if (this.observed) this.ro.unobserve(this.observed);
    this.observed = el;
    if (el) {
      this.ro.observe(el);
      this.schedule();
    }
  }

  /** Coalesce to one reconcile per frame: a resize drag fires the observer continuously. */
  private schedule(): void {
    if (this.rafPending) return;
    this.rafPending = true;
    const raf =
      typeof requestAnimationFrame !== 'undefined'
        ? requestAnimationFrame
        : (cb: FrameRequestCallback) => queueMicrotask(() => cb(0));
    raf(() => {
      this.rafPending = false;
      this.host.reconcileBoundaries();
    });
  }
}
