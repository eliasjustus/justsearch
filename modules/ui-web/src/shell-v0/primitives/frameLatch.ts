/**
 * Tempdoc 860 §6.4 (D3) — the one frame latch every rAF-coalescing site in the shell shares.
 *
 * The shape it replaces was written six times: "set a flag; clear it only from a
 * `requestAnimationFrame` callback". In a page the browser is not rendering — a backgrounded tab, a
 * suspended `ViewTransition` callback, a throttled window — that frame never arrives, the flag stays
 * set, and every later pass is coalesced into a trailing frame that will never run. The site wedges
 * until the page is foregrounded again.
 *
 * The release is therefore **rAF-primary with a time-based fallback**, and the horizon is
 * deliberately far past any real frame ({@link FRAME_LATCH_FALLBACK_MS}). A budget-sized fallback
 * would be a different, worse mechanism: it would let a long task release the latch *inside* one rAF
 * interval and permit a second synchronous pass — exactly the write→read→write forced layout the
 * coalescers exist to prevent. At a one-second horizon the fallback cannot fire in a rendering page,
 * and whichever release arrives first cancels the other, so the callback runs exactly once per latch.
 *
 * This owns the **latch only**. Scheduling policy stays with the caller: `navigation`'s trailing
 * re-entry pass and its deliberate `freshenIfStale` bypass, `adaptiveBar`'s bounded retry, the
 * streaming coalescers' pending-value handling — none of that belongs to a shape the other five
 * do not share.
 */

/** The two clocks a latch runs on, injectable so a test can drive them without real time. */
export interface FrameScheduler {
  requestFrame(run: () => void): number | null;
  cancelFrame(handle: number): void;
  requestFallback(run: () => void, ms: number): number;
  cancelFallback(handle: number): void;
}

/**
 * The fallback horizon. Far beyond any frame budget on purpose — see the mechanism note above; the
 * fallback is a wedge release, not a second scheduler racing the first at frame cadence.
 */
export const FRAME_LATCH_FALLBACK_MS = 1000;

/**
 * `globalThis.requestAnimationFrame` is read at CALL time, not captured at module load. That is what
 * lets a test stub a non-rendering page: happy-dom *provides* rAF (backed by `setImmediate`) and it
 * always fires, so the wedge cannot be constructed unless the stub is visible to the latch when it
 * schedules (tempdoc 860 §6.4).
 */
const browserScheduler: FrameScheduler = {
  requestFrame: (run) =>
    typeof globalThis.requestAnimationFrame === 'function'
      ? globalThis.requestAnimationFrame(() => run())
      : null,
  cancelFrame: (handle) => {
    if (typeof globalThis.cancelAnimationFrame === 'function') globalThis.cancelAnimationFrame(handle);
  },
  requestFallback: (run, ms) => setTimeout(run, ms) as unknown as number,
  cancelFallback: (handle) => clearTimeout(handle),
};

/** At most one pass per animation frame, with a guaranteed release even when no frame arrives. */
export class FrameLatch {
  private frame: number | null = null;
  private fallback: number | null = null;
  private readonly scheduler: FrameScheduler;

  constructor(scheduler: FrameScheduler = browserScheduler) {
    this.scheduler = scheduler;
  }

  /** Whether a pass is already scheduled — i.e. whether the next {@link request} coalesces. */
  get latched(): boolean {
    return this.frame !== null || this.fallback !== null;
  }

  /**
   * Leading-edge: `false` means a pass is already scheduled and this call was coalesced into it, so
   * the caller can record whatever it needs to carry into that pass. `true` means this call took the
   * latch and `run` will fire exactly once — from the frame, or from the fallback if none arrives.
   */
  request(run: () => void): boolean {
    if (this.latched) return false;
    let released = false;
    const release = (): void => {
      if (released) return;
      released = true;
      // Clear BEFORE running: the clock that lost the race is withdrawn here (the `released` guard
      // makes the pass single-shot; this keeps the loser from sitting armed for a second of wall
      // clock), and `run` is free to take the latch again for a trailing pass.
      this.cancel();
      run();
    };
    const frame = this.scheduler.requestFrame(release);
    if (released) return true; // a synchronous scheduler already ran the pass
    this.frame = frame;
    const fallback = this.scheduler.requestFallback(release, FRAME_LATCH_FALLBACK_MS);
    if (!released) this.fallback = fallback;
    return true;
  }

  /** Drop a scheduled pass and release the latch — for teardown, and for a gesture that ends. */
  cancel(): void {
    if (this.frame !== null) {
      this.scheduler.cancelFrame(this.frame);
      this.frame = null;
    }
    if (this.fallback !== null) {
      this.scheduler.cancelFallback(this.fallback);
      this.fallback = null;
    }
  }
}
