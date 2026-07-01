// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, beforeEach } from 'vitest';
import {
  bumpChannelOpened,
  bumpChannelClosed,
  getCurrentOpenChannelCount,
  getPeakOpenChannelCount,
  __resetLiveChannelBudgetForTest,
} from './liveChannelBudget.js';

describe('liveChannelBudget', () => {
  beforeEach(() => {
    __resetLiveChannelBudgetForTest();
  });

  it('starts at zero', () => {
    expect(getCurrentOpenChannelCount()).toBe(0);
    expect(getPeakOpenChannelCount()).toBe(0);
  });

  it('tracks the current open count across opens and closes', () => {
    bumpChannelOpened();
    bumpChannelOpened();
    expect(getCurrentOpenChannelCount()).toBe(2);
    bumpChannelClosed();
    expect(getCurrentOpenChannelCount()).toBe(1);
    bumpChannelClosed();
    expect(getCurrentOpenChannelCount()).toBe(0);
  });

  it('records the high-water-mark, which does not drop when channels close', () => {
    bumpChannelOpened();
    bumpChannelOpened();
    bumpChannelOpened();
    expect(getPeakOpenChannelCount()).toBe(3);
    bumpChannelClosed();
    bumpChannelClosed();
    expect(getCurrentOpenChannelCount()).toBe(1);
    expect(getPeakOpenChannelCount()).toBe(3); // peak survives the closes
  });

  it('floors the current count at zero on a mismatched close (defensive)', () => {
    bumpChannelClosed();
    bumpChannelClosed();
    expect(getCurrentOpenChannelCount()).toBe(0);
  });

  it('reset clears both counters', () => {
    bumpChannelOpened();
    bumpChannelOpened();
    __resetLiveChannelBudgetForTest();
    expect(getCurrentOpenChannelCount()).toBe(0);
    expect(getPeakOpenChannelCount()).toBe(0);
  });
});
