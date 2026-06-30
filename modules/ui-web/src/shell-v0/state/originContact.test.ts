// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest';
import {
  bumpOriginContact,
  getLastOriginContactMs,
  isOriginReachable,
  __resetOriginContactForTest,
} from './originContact.js';
import { STREAM_WATCHDOG_STALE_MS } from '../../api/generated/stream-liveness-constants.js';

describe('originContact — the reachability authority (tempdoc 649)', () => {
  beforeEach(() => __resetOriginContactForTest());

  it('starts with no contact ⇒ not reachable', () => {
    expect(getLastOriginContactMs()).toBeNull();
    expect(isOriginReachable(null, 1_000)).toBe(false);
  });

  it('bumpOriginContact records the stamp', () => {
    bumpOriginContact(12_345);
    expect(getLastOriginContactMs()).toBe(12_345);
  });

  it('isOriginReachable: fresh contact within the window is reachable; aged-out is not', () => {
    const now = 1_000_000;
    // Just inside the watchdog window.
    expect(isOriginReachable(now - (STREAM_WATCHDOG_STALE_MS - 1), now)).toBe(true);
    // Exactly at / past the window ⇒ unreachable (boundary is exclusive).
    expect(isOriginReachable(now - STREAM_WATCHDOG_STALE_MS, now)).toBe(false);
    expect(isOriginReachable(now - (STREAM_WATCHDOG_STALE_MS + 1), now)).toBe(false);
  });

  it('respects an explicit window override', () => {
    const now = 1_000_000;
    expect(isOriginReachable(now - 5_000, now, 10_000)).toBe(true);
    expect(isOriginReachable(now - 15_000, now, 10_000)).toBe(false);
  });
});
