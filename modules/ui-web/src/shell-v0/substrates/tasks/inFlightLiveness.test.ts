// @vitest-environment happy-dom

/**
 * Tempdoc 575 §15 — the single in-flight liveness authority. Pins the derivation that every
 * in-flight RUNNING projection must consume (the inflight-liveness-projections gate enforces
 * the "must consume" half).
 */

import { describe, it, expect } from 'vitest';
import {
  isInFlightLive,
  staleWindowMs,
  IN_FLIGHT_STALE_MS,
} from './inFlightLiveness.js';

describe('575 §15 — inFlightLiveness (the single liveness authority)', () => {
  it('a fresh heartbeat (within the window) → live', () => {
    expect(isInFlightLive(Date.now())).toBe(true);
    expect(isInFlightLive(Date.now() - (IN_FLIGHT_STALE_MS - 1000))).toBe(true);
  });

  it('a stale heartbeat (past the window) → not live', () => {
    expect(isInFlightLive(Date.now() - (IN_FLIGHT_STALE_MS + 1000))).toBe(false);
    expect(isInFlightLive(Date.now() - 300_000)).toBe(false);
  });

  it('accepts an explicit now (clock injection — skew-free, deterministic)', () => {
    const now = 1_000_000;
    expect(isInFlightLive(now - 1000, now)).toBe(true);
    expect(isInFlightLive(now - (IN_FLIGHT_STALE_MS + 1), now)).toBe(false);
  });

  it('the dev-only __JF_STALE_MS__ override shrinks the window (and is restored)', () => {
    const g = globalThis as { __JF_STALE_MS__?: unknown };
    try {
      g.__JF_STALE_MS__ = 2_000;
      expect(staleWindowMs()).toBe(2_000);
      expect(isInFlightLive(Date.now() - 5_000)).toBe(false); // stale under the tiny window
      expect(isInFlightLive(Date.now())).toBe(true);
    } finally {
      delete g.__JF_STALE_MS__;
    }
    expect(staleWindowMs()).toBe(IN_FLIGHT_STALE_MS);
  });
});
