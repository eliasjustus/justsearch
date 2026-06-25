// @vitest-environment happy-dom

/**
 * Tests for CapabilitiesHandshake.
 *
 * Uses fake fetch + fake EventSource so the test runs without
 * network. Verifies the lifecycle, the reset-driven re-fetch, and
 * the convenience-flag derivation.
 */

import { describe, expect, it, vi } from 'vitest';
import { CapabilitiesHandshake } from './CapabilitiesHandshake.js';
import type { CapabilitiesView } from './capabilities-types.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';

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

const SAMPLE_VIEW: CapabilitiesView = {
  schema_versions: { schema_ver: 'v1', grammar_ver: 'v1', template_ver: 1 },
  prompt_templates: [],
  plugins: [],
  source: {
    phase: 'phase-7',
    schema_ver: 'v1',
    generated_at: '2026-05-05T00:00:00Z',
  },
  serverCapabilities: {
    primitives: {},
    catalogVersion: 42,
    protocolVersion: '1.0',
    i18n: { version: 1, availableLocales: ['en'] },
    streamingEnvelope: { version: 1 },
  },
};

function makeFetchSequence(...views: CapabilitiesView[]): typeof fetch {
  let i = 0;
  return ((async () => {
    const view = views[Math.min(i, views.length - 1)];
    i++;
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => view,
    } as unknown as Response;
  }) as unknown) as typeof fetch;
}

function flushMicrotasks(): Promise<void> {
  return new Promise((r) => queueMicrotask(r));
}

describe('CapabilitiesHandshake — initial fetch', () => {
  it('fetches /infra/capabilities and surfaces the view', async () => {
    const fake = new FakeEventSource('http://test/infra/capabilities/stream');
    const fetchImpl = makeFetchSequence(SAMPLE_VIEW);
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();

    const snap = handshake.getSnapshot();
    expect(snap.isLoaded).toBe(true);
    expect(snap.error).toBeNull();
    expect(snap.view?.serverCapabilities.catalogVersion).toBe(42);
    expect(snap.hasEnvelope).toBe(true);
    handshake.stop();
  });

  it('strips trailing slash from apiBase before composing URLs', async () => {
    const fake = new FakeEventSource('');
    let capturedFetchUrl = '';
    let capturedSseUrl = '';
    const fetchImpl = (async (input: RequestInfo | URL) => {
      capturedFetchUrl = String(input);
      return {
        ok: true,
        status: 200,
        statusText: 'OK',
        json: async () => SAMPLE_VIEW,
      } as unknown as Response;
    }) as unknown as typeof fetch;
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test/',
      fetchImpl,
      eventSourceFactory: (url) => {
        capturedSseUrl = url;
        return fake as unknown as EventSource;
      },
    });
    handshake.start();
    await flushMicrotasks();
    expect(capturedFetchUrl).toBe('http://test/infra/capabilities');
    expect(capturedSseUrl).toBe('http://test/infra/capabilities/stream');
    handshake.stop();
  });

  it('hasEnvelope=false when streamingEnvelope.version is 0', async () => {
    const view: CapabilitiesView = {
      ...SAMPLE_VIEW,
      serverCapabilities: {
        ...SAMPLE_VIEW.serverCapabilities,
        streamingEnvelope: { version: 0 },
      },
    };
    const fake = new FakeEventSource('');
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl: makeFetchSequence(view),
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();
    expect(handshake.getSnapshot().hasEnvelope).toBe(false);
    handshake.stop();
  });

  it('surfaces non-2xx HTTP as snapshot.error', async () => {
    const fake = new FakeEventSource('');
    const fetchImpl = (async () =>
      ({
        ok: false,
        status: 503,
        statusText: 'Service Unavailable',
        json: async () => ({}),
      } as unknown as Response)) as unknown as typeof fetch;
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    expect(handshake.getSnapshot().isLoaded).toBe(true);
    expect(handshake.getSnapshot().error).toContain('503');
    expect(handshake.getSnapshot().view).toBeNull();
    handshake.stop();
  });

  it('surfaces fetch rejection as snapshot.error', async () => {
    const fake = new FakeEventSource('');
    const fetchImpl = (async () => {
      throw new Error('network down');
    }) as unknown as typeof fetch;
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    expect(handshake.getSnapshot().isLoaded).toBe(true);
    expect(handshake.getSnapshot().error).toContain('network');
    handshake.stop();
  });
});

describe('CapabilitiesHandshake — reset re-fetch', () => {
  it('re-fetches the HTTP endpoint when the stream emits a reset frame', async () => {
    const fake = new FakeEventSource('http://test/infra/capabilities/stream');
    const view2: CapabilitiesView = {
      ...SAMPLE_VIEW,
      serverCapabilities: {
        ...SAMPLE_VIEW.serverCapabilities,
        catalogVersion: 99,
      },
    };
    const fetchImpl = vi.fn(makeFetchSequence(SAMPLE_VIEW, view2));
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(handshake.getSnapshot().view?.serverCapabilities.catalogVersion).toBe(42);

    // Stream emits a reset frame
    fake.emitFrame({
      streamId: 'capabilities/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-05-05T00:00:01Z',
      payload: { kind: 'reset' },
      resumeToken: 'tok-1',
    });
    await flushMicrotasks();
    await flushMicrotasks();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(handshake.getSnapshot().view?.serverCapabilities.catalogVersion).toBe(99);
    handshake.stop();
  });

  it('does not re-fetch on non-reset lifecycle frames (heartbeat, connected, snapshot)', async () => {
    const fake = new FakeEventSource('http://test/infra/capabilities/stream');
    const fetchImpl = vi.fn(makeFetchSequence(SAMPLE_VIEW));
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();
    expect(fetchImpl).toHaveBeenCalledTimes(1);

    fake.emitFrame({
      streamId: 'capabilities/v1',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '...',
      payload: { kind: 'connected' },
      resumeToken: 't1',
    });
    fake.emitFrame({
      streamId: 'capabilities/v1',
      frameKind: 'LIFECYCLE',
      seq: 2,
      ts: '...',
      payload: { kind: 'snapshot' },
      resumeToken: 't2',
    });
    fake.emitFrame({
      streamId: 'capabilities/v1',
      frameKind: 'LIFECYCLE',
      seq: 3,
      ts: '...',
      payload: { kind: 'heartbeat' },
      resumeToken: 't3',
    });
    await flushMicrotasks();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    handshake.stop();
  });
});

describe('CapabilitiesHandshake — lifecycle', () => {
  it('start() is idempotent', async () => {
    const fake = new FakeEventSource('');
    let factoryCalls = 0;
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl: makeFetchSequence(SAMPLE_VIEW),
      eventSourceFactory: () => {
        factoryCalls++;
        return fake as unknown as EventSource;
      },
    });
    handshake.start();
    handshake.start();
    expect(factoryCalls).toBe(1);
    handshake.stop();
  });

  it('stop() closes the stream and aborts in-flight fetch', () => {
    const fake = new FakeEventSource('');
    const fetchImpl = (async () => {
      // Never resolves until aborted
      return new Promise<Response>(() => {});
    }) as unknown as typeof fetch;
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    handshake.stop();
    expect(fake.closed).toBe(true);
  });

  it('refetch() re-issues the HTTP fetch', async () => {
    const fake = new FakeEventSource('');
    const fetchImpl = vi.fn(makeFetchSequence(SAMPLE_VIEW, SAMPLE_VIEW));
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    await handshake.refetch();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    handshake.stop();
  });
});

describe('CapabilitiesHandshake — subscribers', () => {
  it('notifies subscribers on each snapshot update', async () => {
    const fake = new FakeEventSource('');
    const fetchImpl = makeFetchSequence(SAMPLE_VIEW);
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    const calls: number[] = [];
    handshake.subscribe((s) =>
      calls.push(s.view?.serverCapabilities.catalogVersion ?? -1),
    );
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();
    expect(calls).toContain(42);
    handshake.stop();
  });

  it('listener errors do not break other listeners', async () => {
    const fake = new FakeEventSource('');
    const fetchImpl = makeFetchSequence(SAMPLE_VIEW);
    const handshake = new CapabilitiesHandshake({
      apiBase: 'http://test',
      fetchImpl,
      eventSourceFactory: () => fake as unknown as EventSource,
    });
    const calls: number[] = [];
    handshake.subscribe(() => {
      throw new Error('listener boom');
    });
    handshake.subscribe((s) =>
      calls.push(s.view?.serverCapabilities.catalogVersion ?? -1),
    );
    handshake.start();
    await flushMicrotasks();
    await flushMicrotasks();
    expect(calls).toContain(42);
    handshake.stop();
  });
});
