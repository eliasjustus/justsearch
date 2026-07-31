// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  isFeedStalled,
  startIndexingJobsBridge,
  subscribeFeedStalled,
  __resetFeedHealthForTest,
  __setResolveHashForTest,
  __resetResolvedLabelsForTest,
} from './indexingJobsBridge.js';
import { __resetTasksForTest } from './index.js';
import { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../../streaming/shellEventStreamIds.js';

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

/**
 * Tempdoc 798 B4 — the stall must ACTUATE, not just annotate.
 *
 * The defect these tests exist for: `isFeedStalled` worked, the panel rendered "Live updates
 * paused — reconnecting…", and nothing anywhere called back into the stream layer. Measured in
 * Windows-Sandbox validation round 7 — API `lifecycle=READY / indexState=IDLE / queueDepth=0`, the
 * Tasks panel frozen at "5184 QUEUED / reconnecting…", the status bar in the SAME window reading
 * "queue: 0", persisting ~25 minutes until the app was restarted.
 *
 * It shipped because the cosmetic boolean was the only thing under test. So these tests reproduce
 * the field condition — frames flowing for a DIFFERENT streamId on the same physical connection
 * while `surface:indexing-jobs` is withheld — and assert the ACTION (a new EventSource is opened),
 * never the flag alone.
 */

// Minimal EventSource double, mirroring indexingJobsBridge.test.ts's: EnvelopeStream attaches a
// 'frame' listener and reads event.data (JSON).
class FakeEventSource {
  listeners: Record<string, Array<(e: unknown) => void>> = {};
  closed = false;
  readyState = 0;
  constructor(public url: string) {}
  addEventListener(type: string, fn: (e: unknown) => void): void {
    (this.listeners[type] ??= []).push(fn);
  }
  removeEventListener(type: string, fn: (e: unknown) => void): void {
    this.listeners[type] = (this.listeners[type] ?? []).filter((f) => f !== fn);
  }
  close(): void {
    this.closed = true;
    this.readyState = 2;
  }
  emitFrame(envelope: unknown): void {
    for (const fn of this.listeners['frame'] ?? []) fn({ data: JSON.stringify(envelope) });
  }
  emitOpen(): void {
    this.readyState = 1;
    for (const fn of this.listeners['open'] ?? []) fn(new Event('open'));
  }
}

/** A `surface:indexing-jobs` snapshot carrying one in-flight job — the last frame the feed sends. */
const jobsSnapshot = (seq: number, state: string, lastUpdatedMs: number) => ({
  streamId: SHELL_EVENT_STREAM_IDS.INDEXING_JOBS,
  frameKind: 'LIFECYCLE',
  seq,
  ts: '2026-07-01T00:00:00Z',
  resumeToken: `jobs-tok-${seq}`,
  payload: {
    kind: 'snapshot',
    items: [{ pathHash: 'wedged1', state, collection: 'default', lastUpdatedMs }],
  },
});

/** A frame on a DIFFERENT channel of the SAME physical connection — what keeps the shared
 * EnvelopeStream's heartbeat-absence watchdog re-armed while indexing-jobs is wedged. */
const otherChannelFrame = (seq: number) => ({
  streamId: SHELL_EVENT_STREAM_IDS.ACTION_LEDGER,
  frameKind: 'UPDATE',
  seq,
  ts: '2026-07-01T00:00:00Z',
  resumeToken: `ledger-tok-${seq}`,
  payload: { kind: 'insert', row: { id: `e${seq}` } },
});

const RE_EVAL_TICK_MS = 15_000; // indexingJobsBridge.RE_EVAL_INTERVAL_MS
const NUDGE_MIN_INTERVAL_MS = 60_000; // indexingJobsBridge.FEED_STALL_NUDGE_MIN_INTERVAL_MS

describe('798 B4 — a stalled indexing-jobs feed ACTUATES a reconnect (multiplexed transport)', () => {
  beforeEach(() => {
    __resetTasksForTest();
    __resetFeedHealthForTest();
    __resetResolvedLabelsForTest();
    // Keep the lazy pathHash→label resolve off the network; it never resolves a label here.
    __setResolveHashForTest(async () => null);
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-01T00:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
    __setResolveHashForTest(null);
    __resetFeedHealthForTest();
    __resetResolvedLabelsForTest();
    __resetTasksForTest();
  });

  /**
   * Builds the field condition: ONE physical connection, TWO registered channels (indexing-jobs
   * via the bridge + a ledger channel standing in for advisories/intent), both registered BEFORE
   * `start()` so the pre-existing late-subscribe reconnect never fires and can't be mistaken for
   * the stall actuator.
   */
  function fieldConditionHarness() {
    const sources: FakeEventSource[] = [];
    const urls: string[] = [];
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        const fake = new FakeEventSource(url);
        sources.push(fake);
        urls.push(url);
        return fake as unknown as EventSource;
      },
      // Production value: the shared connection's watchdog window (40s) is SHORTER than the
      // bridge's stall window (45s) — which is exactly why a wedged channel can never be
      // recovered by the watchdog: the other channels re-arm it first.
      watchdogStaleMs: 40_000,
    });
    // The "other" channel — a real registered consumer, so its frames are demuxed normally.
    mux.subscribe(
      SHELL_EVENT_STREAM_IDS.ACTION_LEDGER,
      () => ({ initialState: null, reducer: () => null }),
      () => {},
    );
    const stopBridge = startIndexingJobsBridge('http://test', { multiplex: mux });
    mux.start();
    sources[0]!.emitOpen();
    // The feed's LAST frame: one PROCESSING job. After this, indexing-jobs goes silent.
    sources[0]!.emitFrame(jobsSnapshot(1, 'PROCESSING', Date.now()));

    /** Advance `ms` of wall clock while the OTHER channel keeps delivering frames every 10s. */
    const advanceWithOtherChannelTraffic = async (ms: number, seqBase: number): Promise<void> => {
      const steps = ms / 10_000;
      for (let i = 0; i < steps; i += 1) {
        await vi.advanceTimersByTimeAsync(10_000);
        sources[sources.length - 1]!.emitFrame(otherChannelFrame(seqBase + i));
      }
    };

    return { mux, sources, urls, stopBridge, advanceWithOtherChannelTraffic };
  }

  it('THE FIELD CONDITION — indexing-jobs wedged while another channel flows: the stall is detected AND a reconnect actually fires', async () => {
    const stalls: boolean[] = [];
    const unsubStall = subscribeFeedStalled((s) => stalls.push(s));
    const { mux, sources, urls, stopBridge, advanceWithOtherChannelTraffic } =
      fieldConditionHarness();

    expect(sources).toHaveLength(1);

    // 50s of other-channel traffic: past the shared watchdog window (40s), inside the bridge's
    // stall window (45s > elapsed-at-last-tick). No reconnect from ANY layer yet.
    await advanceWithOtherChannelTraffic(50_000, 100);
    expect(sources).toHaveLength(1); // the physical watchdog is alive and well — and blind
    expect(stalls[stalls.length - 1]).toBe(false);

    // Cross the stall window. The tick at t=60s observes it.
    await advanceWithOtherChannelTraffic(10_000, 200);

    // The flag flipped...
    expect(stalls[stalls.length - 1]).toBe(true);
    // ...AND the actuator fired: a genuinely new EventSource was opened. THIS is the assertion
    // the pre-798 code fails.
    expect(sources).toHaveLength(2);
    // Resume position survives: the reconnect's ?since= bundle carries the wedged channel's own
    // last token plus the flowing channel's, so the server re-attaches both from where they were.
    expect(urls[1]).toContain(encodeURIComponent('jobs-tok-1'));
    expect(urls[1]).toContain(encodeURIComponent('ledger-tok-'));

    unsubStall();
    stopBridge();
    mux.stop();
  });

  it('the actuator is debounced — repeated stalled ticks do NOT produce unbounded reconnects, but it does retry after the window', async () => {
    const { mux, sources, stopBridge, advanceWithOtherChannelTraffic } = fieldConditionHarness();

    await advanceWithOtherChannelTraffic(60_000, 100);
    expect(sources).toHaveLength(2); // first nudge at t=60s

    // t=60s..110s: the stall NEVER clears (indexing-jobs is still wedged), so the 15s tick
    // observes `stalled === true` at t=75/90/105 — three more actuator opportunities, all inside
    // the 60s rate-limit window. A hot-loop here is exactly what would hammer an offline backend.
    await advanceWithOtherChannelTraffic(50_000, 200);
    expect(sources).toHaveLength(2); // still 2 — bounded

    // ...but the actuator is not one-shot either: past the rate-limit window it retries, so a
    // reconnect that failed to revive the channel is followed by another attempt.
    await advanceWithOtherChannelTraffic(20_000, 300);
    expect(sources).toHaveLength(3);

    // Sanity on the arithmetic this test depends on: the tick is finer-grained than the rate
    // limit, so suppression is genuinely doing the work (not an artefact of a coarse tick).
    expect(RE_EVAL_TICK_MS).toBeLessThan(NUDGE_MIN_INTERVAL_MS);

    stopBridge();
    mux.stop();
  });

  it("the reconnect's own connection-state broadcast does NOT count as a frame — the stall stays observed until real job data arrives", async () => {
    // Found by the field-condition test above. Both transports notify a stream's listeners on
    // connection-state transitions with the entry's UNCHANGED seq, and the actuator's own
    // stop()+start() produces exactly that. Before the seq guard the nudge stamped its own
    // liveness: the panel reported "recovered" with zero job data received, then re-stalled 45s
    // later — an oscillator built out of the fix.
    const stalls: boolean[] = [];
    const unsubStall = subscribeFeedStalled((s) => stalls.push(s));
    const { mux, sources, stopBridge, advanceWithOtherChannelTraffic } = fieldConditionHarness();

    await advanceWithOtherChannelTraffic(60_000, 100);
    expect(sources).toHaveLength(2);
    expect(stalls[stalls.length - 1]).toBe(true);

    sources[1]!.emitOpen(); // the reconnected transport announces itself — but says nothing about jobs
    await vi.advanceTimersByTimeAsync(RE_EVAL_TICK_MS);
    expect(stalls[stalls.length - 1]).toBe(true); // still stalled, correctly

    // A REAL indexing-jobs frame — and only that — clears it.
    sources[1]!.emitFrame(jobsSnapshot(2, 'PROCESSING', Date.now()));
    expect(stalls[stalls.length - 1]).toBe(false);

    unsubStall();
    stopBridge();
    mux.stop();
  });

  it('a healthy feed never nudges — an arriving indexing-jobs frame clears the stall and no reconnect happens', async () => {
    const { mux, sources, stopBridge } = fieldConditionHarness();

    // Frames on the indexing-jobs channel itself, every 20s — the normal case.
    for (let i = 2; i <= 7; i += 1) {
      await vi.advanceTimersByTimeAsync(20_000);
      sources[sources.length - 1]!.emitFrame(jobsSnapshot(i, 'PROCESSING', Date.now()));
    }

    // 120s elapsed with in-flight work throughout, and not a single reconnect: the actuator
    // fires on a stall, not on the mere presence of queued work.
    expect(sources).toHaveLength(1);

    stopBridge();
    mux.stop();
  });

  it('the actuator stops with the bridge — no nudge after the bridge is torn down', async () => {
    const { mux, sources, stopBridge, advanceWithOtherChannelTraffic } = fieldConditionHarness();

    stopBridge(); // clears the re-eval tick
    await advanceWithOtherChannelTraffic(90_000, 100);
    expect(sources).toHaveLength(1);

    mux.stop();
  });
});
