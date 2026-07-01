// @vitest-environment happy-dom

/**
 * BackendStreamSource tests — slice 492 tier-1 substrate (third source).
 *
 * The dedup + replay + lifecycle semantics are owned by
 * `bootIntentStreamBridge` (slice 487) and tested in
 * `modules/ui-web/src/api/intent/bootIntentStreamBridge.test.ts`. This
 * file verifies only the source-shape contract: backend-broadcast
 * envelopes received from `/api/intent/stream` are forwarded into the
 * SourceDispatch handed to `start(...)`.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  BACKEND_STREAM_SOURCE_REF,
  createBackendStreamSource,
} from './BackendStreamSource.js';
import { stopIntentStreamBridge } from '../../../api/intent/bootIntentStreamBridge.js';
import { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import type { Intent } from '../types.js';
import type { DispatchOptions } from '../intentRouter.js';
import type { SseEnvelope } from '../../streaming/envelope-types.js';

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

/** Builds a MultiplexedStream wired to `fakeEs`, past the realistic 'open'-before-'frame'
 * EventSource ordering (see MultiplexedStream.test.ts). */
function multiplexOn(fakeEs: FakeEventSource): MultiplexedStream {
  const mux = new MultiplexedStream({
    url: 'http://test/api/shell-events/stream',
    eventSourceFactory: () => fakeEs as unknown as EventSource,
  });
  mux.start();
  fakeEs.emitOpen();
  return mux;
}

function updateFrame(seq: number, id: string, intent: Intent): SseEnvelope {
  return {
    streamId: 'system:intent-envelopes',
    frameKind: 'UPDATE',
    seq,
    resumeToken: `t${seq}`,
    payload: {
      kind: 'intent.envelope',
      id,
      intent,
    },
    timestamp: '2026-05-13T00:00:00Z',
  } as unknown as SseEnvelope;
}

beforeEach(() => {
  stopIntentStreamBridge();
});

afterEach(() => {
  stopIntentStreamBridge();
});

describe('BackendStreamSource — ref + dispatch wiring', () => {
  it('has the canonical Manifest-tier ref', () => {
    const source = createBackendStreamSource({ multiplex: multiplexOn(new FakeEventSource('http://test')) });
    expect(source.ref).toBe(BACKEND_STREAM_SOURCE_REF);
    expect(BACKEND_STREAM_SOURCE_REF).toBe('core.backend-stream');
  });

  it('forwards SSE intent envelopes into the SourceDispatch', () => {
    const fakeEs = new FakeEventSource('http://test');
    const source = createBackendStreamSource({ multiplex: multiplexOn(fakeEs) });
    const dispatched: Array<{ intent: Intent; options?: DispatchOptions }> = [];
    const teardown = source.start((intent, options) => {
      dispatched.push({ intent, options });
    });

    const intent: Intent = {
      address: { kind: 'navigate', target: 'core.search-surface', state: { query: 'foo' } },
      transport: 'LLM_EMISSION',
    };
    fakeEs.emitFrame(updateFrame(1, 'env-1', intent));

    expect(dispatched).toHaveLength(1);
    expect(dispatched[0]!.intent).toEqual(intent);
    // BackendStreamSource omits DispatchOptions (backend-broadcast intents
    // always push history — they are remote arrivals, not popstate replays).
    expect(dispatched[0]!.options).toBeUndefined();

    if (typeof teardown === 'function') teardown();
  });

  it('teardown stops the underlying bridge', () => {
    const fakeEs = new FakeEventSource('http://test');
    const source = createBackendStreamSource({ multiplex: multiplexOn(fakeEs) });
    const dispatched: Intent[] = [];
    const teardown = source.start((intent) => {
      dispatched.push(intent);
    });
    if (typeof teardown === 'function') teardown();

    // After teardown, frames emitted by a stale EventSource don't reach
    // SourceDispatch (bridge subscription is detached).
    const intent: Intent = {
      address: { kind: 'navigate', target: 'core.x', state: {} },
      transport: 'LLM_EMISSION',
    };
    fakeEs.emitFrame(updateFrame(2, 'env-2', intent));
    expect(dispatched).toEqual([]);
  });
});
