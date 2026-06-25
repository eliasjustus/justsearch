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
import type { Intent } from '../types.js';
import type { DispatchOptions } from '../intentRouter.js';
import type { SseEnvelope } from '../../streaming/envelope-types.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(
      new MessageEvent('frame', { data: JSON.stringify(envelope) }),
    );
  }
  close(): void {
    this.closed = true;
  }
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
    const source = createBackendStreamSource({ apiBase: '' });
    expect(source.ref).toBe(BACKEND_STREAM_SOURCE_REF);
    expect(BACKEND_STREAM_SOURCE_REF).toBe('core.backend-stream');
  });

  it('forwards SSE intent envelopes into the SourceDispatch', () => {
    let fakeEs: FakeEventSource | null = null;
    const source = createBackendStreamSource({
      apiBase: 'http://test',
      eventSourceFactory: (url) => {
        fakeEs = new FakeEventSource(url);
        return fakeEs as unknown as EventSource;
      },
    });
    const dispatched: Array<{ intent: Intent; options?: DispatchOptions }> = [];
    const teardown = source.start((intent, options) => {
      dispatched.push({ intent, options });
    });

    expect(fakeEs).not.toBeNull();
    const intent: Intent = {
      address: { kind: 'navigate', target: 'core.search-surface', state: { query: 'foo' } },
      transport: 'LLM_EMISSION',
    };
    (fakeEs as unknown as FakeEventSource).emitFrame(updateFrame(1, 'env-1', intent));

    expect(dispatched).toHaveLength(1);
    expect(dispatched[0]!.intent).toEqual(intent);
    // BackendStreamSource omits DispatchOptions (backend-broadcast intents
    // always push history — they are remote arrivals, not popstate replays).
    expect(dispatched[0]!.options).toBeUndefined();

    if (typeof teardown === 'function') teardown();
  });

  it('teardown stops the underlying bridge', () => {
    let fakeEs: FakeEventSource | null = null;
    const source = createBackendStreamSource({
      apiBase: 'http://test',
      eventSourceFactory: (url) => {
        fakeEs = new FakeEventSource(url);
        return fakeEs as unknown as EventSource;
      },
    });
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
    (fakeEs as unknown as FakeEventSource).emitFrame(updateFrame(2, 'env-2', intent));
    expect(dispatched).toEqual([]);
  });
});
