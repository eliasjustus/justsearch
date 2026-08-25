// @vitest-environment happy-dom
/**
 * Tempdoc 860 §6.4 (D3) — the latch's two halves, each pinned by the case that can fail alone:
 * the wedge release (a page that never delivers a frame drains anyway) and its horizon (a page that
 * DOES deliver frames never takes the fallback, so the coalescer still means one pass per frame).
 *
 * Every case here stubs `globalThis.requestAnimationFrame`. That is not stylistic: happy-dom
 * PROVIDES rAF (`setImmediate`-backed) and it always fires, so a test that merely runs under
 * happy-dom cannot construct a non-rendering page and would pass without exercising anything.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { FrameLatch, FRAME_LATCH_FALLBACK_MS, type FrameScheduler } from './frameLatch.js';

/** A page that hands out frames only when this test says so — and can withhold them entirely. */
function stubFrames(): { fire: () => void; pending: () => number; restore: () => void } {
  const realRequest = globalThis.requestAnimationFrame;
  const realCancel = globalThis.cancelAnimationFrame;
  const queued = new Map<number, FrameRequestCallback>();
  let next = 1;
  globalThis.requestAnimationFrame = ((cb: FrameRequestCallback): number => {
    const id = next++;
    queued.set(id, cb);
    return id;
  }) as typeof requestAnimationFrame;
  globalThis.cancelAnimationFrame = ((id: number): void => {
    queued.delete(id);
  }) as typeof cancelAnimationFrame;
  return {
    fire: () => {
      const due = [...queued.values()];
      queued.clear();
      for (const cb of due) cb(0);
    },
    pending: () => queued.size,
    restore: () => {
      globalThis.requestAnimationFrame = realRequest;
      globalThis.cancelAnimationFrame = realCancel;
    },
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('FrameLatch — the coalescing half', () => {
  it('runs one pass per frame however many requests arrive', () => {
    const frames = stubFrames();
    try {
      const latch = new FrameLatch();
      let passes = 0;
      const run = (): void => {
        passes++;
      };
      expect(latch.request(run), 'the first caller takes the latch').toBe(true);
      expect(latch.request(run), 'the second is coalesced into the scheduled pass').toBe(false);
      expect(latch.request(run)).toBe(false);
      expect(passes).toBe(0);

      frames.fire();
      expect(passes).toBe(1);
      expect(latch.latched, 'and the latch is open again for the next frame').toBe(false);
      expect(latch.request(run)).toBe(true);
    } finally {
      frames.restore();
    }
  });

  it('lets the pass itself take the latch again — the trailing re-entry callers own', () => {
    const frames = stubFrames();
    try {
      const latch = new FrameLatch();
      const seen: number[] = [];
      let round = 0;
      const run = (): void => {
        seen.push(++round);
        if (round < 3) latch.request(run);
      };
      latch.request(run);
      frames.fire();
      frames.fire();
      frames.fire();
      expect(seen).toEqual([1, 2, 3]);
    } finally {
      frames.restore();
    }
  });

  it('drops a scheduled pass on cancel, from BOTH clocks', () => {
    vi.useFakeTimers();
    const frames = stubFrames();
    try {
      const latch = new FrameLatch();
      let passes = 0;
      latch.request(() => {
        passes++;
      });
      latch.cancel();
      expect(latch.latched).toBe(false);
      expect(frames.pending(), 'the frame was withdrawn').toBe(0);
      frames.fire();
      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS * 5);
      expect(passes, 'neither clock can still deliver it').toBe(0);
    } finally {
      frames.restore();
    }
  });
});

describe('FrameLatch — the wedge release (860 §6.4)', () => {
  it('releases on the time fallback when the page never delivers a frame', () => {
    // THE DISCRIMINATING CASE. Frames are requested and never fired — a backgrounded tab, a
    // suspended ViewTransition callback. Before D3 the latch stayed set here forever and every
    // later request was coalesced into a pass that could not run.
    vi.useFakeTimers();
    const frames = stubFrames();
    try {
      const latch = new FrameLatch();
      let passes = 0;
      latch.request(() => {
        passes++;
      });
      expect(latch.request(() => passes++), 'still latched while the frame is outstanding').toBe(false);

      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS - 1);
      expect(passes, 'nothing released early').toBe(0);

      vi.advanceTimersByTime(1);
      expect(passes, 'the fallback drained it — exactly once').toBe(1);
      expect(latch.latched, 'and released the latch, so the site is not wedged').toBe(false);
      expect(frames.pending(), 'the outstanding frame was withdrawn, not left to fire a second pass').toBe(0);

      // The site keeps working after the release: the next request schedules a fresh pass.
      expect(latch.request(() => passes++)).toBe(true);
      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS);
      expect(passes).toBe(2);
    } finally {
      frames.restore();
    }
  });

  it('a RENDERING page never takes the fallback path — one pass per frame, not two', () => {
    // The inverse, and the reason the horizon is a second rather than a frame budget: a fallback at
    // frame cadence would fire INSIDE one rAF interval whenever a long task stretched past the
    // budget, permitting a second synchronous pass — the write→read→write forced layout the
    // coalescers exist to prevent (860 §6.4, correcting rev 1's mechanism).
    expect(FRAME_LATCH_FALLBACK_MS, 'the horizon is far past any frame budget').toBeGreaterThanOrEqual(1000);
    vi.useFakeTimers();
    const frames = stubFrames();
    try {
      const latch = new FrameLatch();
      let passes = 0;
      const run = (): void => {
        passes++;
      };
      latch.request(run);
      // A long task inside one frame: 100ms is ~6 frames at 60Hz and ~12 at 120Hz, so a
      // budget-sized horizon would already have run a pass here. The real horizon has not.
      vi.advanceTimersByTime(100);
      expect(passes, 'no pass without a frame — the fallback is not a second scheduler at frame cadence').toBe(0);
      expect(latch.request(run), 'and the latch is still held, so nothing double-measures').toBe(false);

      frames.fire();
      expect(passes, 'the frame delivers exactly one pass').toBe(1);
      // Both clocks were armed for this pass; the loser can never land a second one.
      vi.advanceTimersByTime(FRAME_LATCH_FALLBACK_MS * 5);
      expect(passes, 'the frame released it, and nothing runs it again').toBe(1);
    } finally {
      frames.restore();
    }
  });
});

describe('FrameLatch — the injected scheduler', () => {
  it('drives both clocks through the injected scheduler, at the declared horizon', () => {
    let frameRun: (() => void) | null = null;
    let fallbackRun: (() => void) | null = null;
    let fallbackMs = 0;
    let cancelledFrames = 0;
    const scheduler: FrameScheduler = {
      requestFrame: (run) => {
        frameRun = run;
        return 7;
      },
      cancelFrame: (handle) => {
        expect(handle).toBe(7);
        cancelledFrames++;
        frameRun = null;
      },
      requestFallback: (run, ms) => {
        fallbackRun = run;
        fallbackMs = ms;
        return 9;
      },
      cancelFallback: (handle) => {
        expect(handle).toBe(9);
        fallbackRun = null;
      },
    };
    const latch = new FrameLatch(scheduler);
    let passes = 0;
    latch.request(() => {
      passes++;
    });
    expect(fallbackMs).toBe(FRAME_LATCH_FALLBACK_MS);

    // The fallback wins this race; the frame it beat is withdrawn so the pass cannot run twice.
    fallbackRun?.();
    expect(passes).toBe(1);
    expect(cancelledFrames).toBe(1);
    expect(latch.latched).toBe(false);
  });
});
