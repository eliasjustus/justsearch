import { describe, it, expect } from 'vitest';
import { isFeedStalled } from './indexingJobsBridge.js';

interface Row {
  pathHash: string;
  state: string;
  collection: string;
  lastUpdatedMs: number;
}
const row = (state: string): Row => ({ pathHash: 'h', state, collection: 'c', lastUpdatedMs: 0 });
const items = (...states: string[]) =>
  new Map(states.map((s, i) => [`h${i}`, { ...row(s), pathHash: `h${i}` }]));

const NOW = 1_000_000;
const STALE = 60_000; // > the 45s window

describe('isFeedStalled (595 §4.4) — observable job-feed stall', () => {
  it('a feed that never delivered a frame is NOT stalled (it is idle/not-started)', () => {
    expect(isFeedStalled(0, NOW, items('PROCESSING'))).toBe(false);
  });

  it('in-flight work + no frame within the window ⇒ stalled', () => {
    expect(isFeedStalled(NOW - STALE, NOW, items('PROCESSING'))).toBe(true);
    expect(isFeedStalled(NOW - STALE, NOW, items('PENDING'))).toBe(true);
  });

  it('a recent frame ⇒ not stalled even with in-flight work', () => {
    expect(isFeedStalled(NOW - 1_000, NOW, items('PROCESSING'))).toBe(false);
  });

  it('no in-flight work ⇒ never stalled (a quiet idle feed is fine)', () => {
    expect(isFeedStalled(NOW - STALE, NOW, items('DONE'))).toBe(false);
    expect(isFeedStalled(NOW - STALE, NOW, items())).toBe(false);
  });
});
