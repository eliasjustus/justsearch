// @vitest-environment happy-dom

/**
 * Post-implementation fix A5 — FE side: LRU dedup verification for the
 * always-on intent stream bridge.
 *
 * The slice 487 §4.3 dedup story keys on the stable `payload.id` field. The
 * server-side ring-buffer retention is tested in
 * `modules/app-services/.../intent/IntentEnvelopeChangeRegistryReplayTest.java`.
 * This file tests the FE half: when the same envelope id is delivered twice
 * (the realistic replay scenario after a network blip + reconnect with stale
 * resume-token), the bridge dispatches the underlying Intent exactly once.
 *
 * Also verifies that the `reset` lifecycle frame clears the LRU so the FE
 * accepts subsequent re-emissions after a true ring-buffer-out-of-window event.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bootIntentStreamBridge,
  stopIntentStreamBridge,
  __isRunningForTest,
} from './bootIntentStreamBridge.js';
import type { IntentRouter } from '../../shell-v0/router/intentRouter.js';
import type { Intent } from '../../shell-v0/router/types.js';
import type { SseEnvelope } from '../../shell-v0/streaming/envelope-types.js';
import { MultiplexedStream } from '../../shell-v0/streaming/MultiplexedStream.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  readyState = 0;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(
      new MessageEvent('frame', { data: JSON.stringify(envelope) }),
    );
  }
  emitOpen(): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  close(): void {
    this.closed = true;
  }
}

/** Builds a MultiplexedStream wired to `fakeEs` and already past the realistic
 * 'open'-before-'frame' EventSource ordering (see MultiplexedStream.test.ts). */
function multiplexOn(fakeEs: FakeEventSource): MultiplexedStream {
  const mux = new MultiplexedStream({
    url: 'http://test/api/shell-events/stream',
    eventSourceFactory: () => fakeEs as unknown as EventSource,
  });
  mux.start();
  fakeEs.emitOpen();
  return mux;
}

function navigationIntent(target: string): Intent {
  return {
    address: { kind: 'navigate', target, state: {} },
    transport: 'LLM_EMISSION',
  };
}

function updateFrame(
  seq: number,
  id: string,
  intent: Intent | null = null,
): SseEnvelope {
  return {
    streamId: 'system:intent-envelopes',
    frameKind: 'UPDATE',
    seq,
    ts: '2026-05-13T10:00:00Z',
    payload: {
      kind: 'intent.envelope',
      id,
      intent: intent ?? navigationIntent('core.library'),
    },
    resumeToken: `rt-${seq}`,
  };
}

function lifecycleFrame(seq: number, kind: string): SseEnvelope {
  return {
    streamId: 'system:intent-envelopes',
    frameKind: 'LIFECYCLE',
    seq,
    ts: '2026-05-13T10:00:00Z',
    payload: { kind },
    resumeToken: `rt-${seq}`,
  };
}

describe('bootIntentStreamBridge — LRU dedup (slice 487 §4.3 / post-impl A5)', () => {
  let fakeEs: FakeEventSource;
  let router: IntentRouter;
  let dispatchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fakeEs = new FakeEventSource('http://test/api/intent/stream');
    dispatchSpy = vi.fn().mockResolvedValue(undefined);
    router = {
      // Slice 492 follow-up: IntentRouter.dispatch returns Promise<unknown>
      // (was Promise<void>). The spy resolves to undefined which is a valid
      // unknown; the cast keeps strict-mode satisfied for this mock.
      dispatch: dispatchSpy as unknown as IntentRouter['dispatch'],
      subscribe: vi.fn().mockReturnValue(() => {}),
    };
  });

  afterEach(() => {
    stopIntentStreamBridge();
  });

  it('dispatches a single intent envelope into intentRouter.dispatch', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    fakeEs.emitFrame(updateFrame(1, 'ie-001'));
    expect(dispatchSpy).toHaveBeenCalledTimes(1);
    expect(dispatchSpy.mock.calls[0]?.[0]).toEqual(navigationIntent('core.library'));
  });

  it('dedups replays of the same envelope id (the load-bearing case)', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    // Same id delivered twice — simulates ring-buffer replay after reconnect
    // with stale resume-token.
    fakeEs.emitFrame(updateFrame(1, 'ie-dup-001'));
    fakeEs.emitFrame(updateFrame(2, 'ie-dup-001'));
    expect(dispatchSpy).toHaveBeenCalledTimes(1);
  });

  it('dispatches distinct envelope ids independently', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    fakeEs.emitFrame(updateFrame(1, 'ie-001'));
    fakeEs.emitFrame(updateFrame(2, 'ie-002'));
    fakeEs.emitFrame(updateFrame(3, 'ie-003'));
    expect(dispatchSpy).toHaveBeenCalledTimes(3);
  });

  it('reset lifecycle clears the LRU so re-emitted ids dispatch again', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    fakeEs.emitFrame(updateFrame(1, 'ie-001'));
    expect(dispatchSpy).toHaveBeenCalledTimes(1);

    // Reset lifecycle (the server's signal that the resume-token was
    // out-of-window — ring buffer state from before is no longer relevant).
    // Our LRU clears, so the FE accepts subsequent envelopes fresh.
    fakeEs.emitFrame(lifecycleFrame(2, 'reset'));

    // After reset, the same id is treated as new — the server is asserting
    // a fresh state. (In practice the server would assign a new id, but the
    // LRU semantic is "clear and accept" not "preserve and reject.")
    fakeEs.emitFrame(updateFrame(3, 'ie-001'));
    expect(dispatchSpy).toHaveBeenCalledTimes(2);
  });

  it('ignores payloads without the intent.envelope kind discriminator', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    const envelope: SseEnvelope = {
      streamId: 'system:intent-envelopes',
      frameKind: 'UPDATE',
      seq: 1,
      ts: '2026-05-13T10:00:00Z',
      payload: { kind: 'something-else', id: 'ie-001' },
      resumeToken: 'rt-1',
    };
    fakeEs.emitFrame(envelope);
    expect(dispatchSpy).not.toHaveBeenCalled();
  });

  it('ignores payloads without a stable id', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    const envelope: SseEnvelope = {
      streamId: 'system:intent-envelopes',
      frameKind: 'UPDATE',
      seq: 1,
      ts: '2026-05-13T10:00:00Z',
      payload: {
        kind: 'intent.envelope',
        intent: navigationIntent('core.library'),
        // no `id` — should be dropped (defensive: id is server-assigned and
        // required for dedup correctness).
      },
      resumeToken: 'rt-1',
    };
    fakeEs.emitFrame(envelope);
    expect(dispatchSpy).not.toHaveBeenCalled();
  });

  it('teardown stops the bridge and a second boot becomes possible', () => {
    bootIntentStreamBridge(multiplexOn(fakeEs), router);
    expect(__isRunningForTest()).toBe(true);
    stopIntentStreamBridge();
    expect(__isRunningForTest()).toBe(false);

    // Booting again should construct a fresh stream.
    const fakeEs2 = new FakeEventSource('http://test/api/intent/stream');
    bootIntentStreamBridge(multiplexOn(fakeEs2), router);
    expect(__isRunningForTest()).toBe(true);
  });
});
