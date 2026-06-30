// @vitest-environment happy-dom

/**
 * Tests for MultiplexedStream — tempdoc 662's cross-channel SSE fan-out: demux by streamId,
 * ref-counted subscribe, the per-channel resume bundle, and shared connection-state fan-out.
 */

import { describe, expect, it, beforeEach } from 'vitest';
import { MultiplexedStream } from './MultiplexedStream.js';
import type { SseEnvelope } from './envelope-types.js';
import {
  getLastOriginContactMs,
  __resetOriginContactForTest,
} from '../state/originContact.js';
import {
  getCurrentOpenChannelCount,
  getPeakOpenChannelCount,
  __resetLiveChannelBudgetForTest,
} from '../state/liveChannelBudget.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  readyState = 0;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(envelope) }));
  }
  emitOpen(): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  emitError(closed = false): void {
    this.readyState = closed ? 2 : 0;
    this.dispatchEvent(new Event('error'));
  }
  close(): void {
    this.closed = true;
    this.readyState = 2;
  }
}

interface CounterState {
  count: number;
  lastPayloadKind: string | null;
}
const COUNTER_INITIAL: CounterState = { count: 0, lastPayloadKind: null };
const counterReducer = (state: CounterState, envelope: SseEnvelope): CounterState => {
  const payload = envelope.payload as { kind: string } | null;
  if (envelope.frameKind === 'LIFECYCLE') {
    return { ...state, lastPayloadKind: payload?.kind ?? null };
  }
  return { count: state.count + 1, lastPayloadKind: payload?.kind ?? null };
};

function updateFrame(streamId: string, seq: number, resumeToken: string): SseEnvelope {
  return {
    streamId,
    frameKind: 'UPDATE',
    seq,
    ts: '2026-07-01T00:00:00Z',
    payload: { kind: 'delta' },
    resumeToken,
  };
}

function lifecycleFrame(streamId: string, kind: string, seq: number, resumeToken: string): SseEnvelope {
  return {
    streamId,
    frameKind: 'LIFECYCLE',
    seq,
    ts: '2026-07-01T00:00:00Z',
    payload: { kind },
    resumeToken,
  };
}

describe('MultiplexedStream', () => {
  beforeEach(() => {
    __resetOriginContactForTest();
    __resetLiveChannelBudgetForTest();
  });

  it('tempdoc 662 §D2 — opening the ONE multiplexed connection bumps the runtime peak signal exactly once, well under the budget', () => {
    // The whole point of 662: the boot baseline is ONE physical multiplexed socket replacing
    // 5 always-on EventSources — assert that baseline against the declared budget
    // (governance/live-channels.v1.json budget.maxAlwaysOnPhysical = 3) so a regression that
    // silently reopens per-stream sockets is caught by a live measurement, not just the
    // static register (Reach §"declare claimants statically; measure the peak at runtime").
    const BUDGET_MAX_ALWAYS_ON_PHYSICAL = 3;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => new FakeEventSource(url) as unknown as EventSource,
    });
    mux.start();
    expect(getCurrentOpenChannelCount()).toBe(1);
    expect(getPeakOpenChannelCount()).toBe(1);
    expect(getPeakOpenChannelCount()).toBeLessThanOrEqual(BUDGET_MAX_ALWAYS_ON_PHYSICAL);
    mux.stop();
    expect(getCurrentOpenChannelCount()).toBe(0);
    expect(getPeakOpenChannelCount()).toBe(1); // peak survives the close
  });

  it('demuxes frames to the matching streamId subscriber only', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    source!.emitOpen(); // realistic EventSource ordering: 'open' always precedes any 'frame'

    const aSnapshots: CounterState[] = [];
    const bSnapshots: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      aSnapshots.push(s.payload),
    );
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      bSnapshots.push(s.payload),
    );

    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    source!.emitFrame(updateFrame('system:test-b', 1, 'tok-b-1'));
    source!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));

    expect(aSnapshots).toHaveLength(2);
    expect(aSnapshots[1]!.count).toBe(2);
    expect(bSnapshots).toHaveLength(1);
    expect(bSnapshots[0]!.count).toBe(1);
  });

  it('silently drops a frame for an unregistered streamId (e.g. the heartbeat pseudo-channel)', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    source!.emitOpen(); // realistic EventSource ordering: 'open' always precedes any 'frame'
    const seen: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seen.push(s.payload),
    );

    expect(() =>
      source!.emitFrame(lifecycleFrame('system:shell-events-heartbeat', 'heartbeat', 1, 'tok-hb-1')),
    ).not.toThrow();
    expect(seen).toHaveLength(0);
  });

  it('ref-counts subscriptions: last unsubscribe removes the entry, a fresh subscribe re-seeds initialState', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    source!.emitOpen(); // realistic EventSource ordering: 'open' always precedes any 'frame'

    const seen1: CounterState[] = [];
    const unsub1 = mux.subscribe(
      'system:test-a',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      (s) => seen1.push(s.payload),
    );
    const seen2: CounterState[] = [];
    const unsub2 = mux.subscribe(
      'system:test-a',
      () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }),
      (s) => seen2.push(s.payload),
    );

    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    expect(seen1).toHaveLength(1);
    expect(seen2).toHaveLength(1);

    unsub1();
    // Still one ref left — the entry survives, the second subscriber keeps receiving frames.
    source!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));
    expect(seen1).toHaveLength(1); // unsubscribed — no further updates
    expect(seen2).toHaveLength(2);

    unsub2();
    expect(mux.getSnapshot('system:test-a')).toBeNull();

    // Fresh subscribe after full unsubscribe re-seeds from createConfig (count resets to 0 baseline).
    const seen3: CounterState[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      seen3.push(s.payload),
    );
    source!.emitFrame(updateFrame('system:test-a', 3, 'tok-a-3'));
    expect(seen3[0]!.count).toBe(1); // fresh entry, not 3 — confirms re-seed, not stale accumulation
  });

  it('getSnapshot returns null for an unregistered streamId and the current state for a registered one', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    expect(mux.getSnapshot('system:never-registered')).toBeNull();

    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    const snap = mux.getSnapshot<CounterState>('system:test-a');
    expect(snap).not.toBeNull();
    expect(snap!.payload.count).toBe(1);
    expect(snap!.resumeToken).toBe('tok-a-1');
  });

  it('builds the ?since= bundle as a comma-joined set of every registered stream\'s last token, reconnecting with it', async () => {
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
      watchdogStaleMs: 40,
      reconnectBaseMs: 5,
      reconnectCapMs: 20,
    });
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.start();

    expect(urls[0]).toBe('http://test/api/shell-events/stream'); // no tokens yet — no ?since=

    sources[0]!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    sources[0]!.emitFrame(updateFrame('system:test-b', 1, 'tok-b-1'));
    sources[0]!.emitError(true); // CLOSED — forces FE-owned reconnect

    await new Promise((r) => setTimeout(r, 60));

    expect(urls.length).toBeGreaterThanOrEqual(2);
    const reconnectUrl = urls[1]!;
    // Order is registration order (Map iteration) — assert both tokens present, comma-joined.
    expect(reconnectUrl).toBe(
      'http://test/api/shell-events/stream?since=' + encodeURIComponent('tok-a-1,tok-b-1'),
    );
    mux.stop();
  });

  it('omits ?since= entirely when no registered stream has received a frame yet', () => {
    let capturedUrl = '';
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        capturedUrl = url;
        return new FakeEventSource(url) as unknown as EventSource;
      },
    });
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    mux.start();
    expect(capturedUrl).toBe('http://test/api/shell-events/stream');
  });

  it('propagates the shared physical connection state to every registered stream without double-firing on a normal frame', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    const aCalls: boolean[] = [];
    const bCalls: boolean[] = [];
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      aCalls.push(s.isConnected),
    );
    mux.subscribe('system:test-b', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), (s) =>
      bCalls.push(s.isConnected),
    );

    // First frame transitions the shared connection false -> true; both streams observe it,
    // but stream B (which received no frame) must ALSO see the flip via the connection broadcast.
    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    expect(aCalls.some((c) => c === true)).toBe(true);
    expect(bCalls.some((c) => c === true)).toBe(true);

    const aCallsBeforeSecondFrame = aCalls.length;
    const bCallsBeforeSecondFrame = bCalls.length;
    // A second frame on an ALREADY-connected channel must not re-trigger a connection broadcast
    // for the uninvolved stream B (only A's own listener fires).
    source!.emitFrame(updateFrame('system:test-a', 2, 'tok-a-2'));
    expect(aCalls.length).toBe(aCallsBeforeSecondFrame + 1);
    expect(bCalls.length).toBe(bCallsBeforeSecondFrame); // unchanged — no spurious broadcast

    // An error (CLOSED) flips isConnected false for the shared connection — both streams see it.
    source!.emitError(true);
    expect(aCalls[aCalls.length - 1]).toBe(false);
    expect(bCalls[bCalls.length - 1]).toBe(false);
  });

  it('a received frame bumps the shared originContact stamp (649 reachability authority unaffected by demux)', () => {
    let source: FakeEventSource | undefined;
    const mux = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new FakeEventSource(url);
        return source as unknown as EventSource;
      },
    });
    mux.start();
    mux.subscribe('system:test-a', () => ({ initialState: COUNTER_INITIAL, reducer: counterReducer }), () => {});
    expect(getLastOriginContactMs()).toBeNull();
    source!.emitFrame(updateFrame('system:test-a', 1, 'tok-a-1'));
    expect(getLastOriginContactMs()).not.toBeNull();
  });
});
