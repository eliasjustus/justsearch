import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock tauriRuntime + http modules so streamRequest can resolve the session token
vi.mock('../utils/tauriRuntime', () => ({
  isTauriRuntime: vi.fn(() => false),
}));

/** Helper: create a ReadableStream from SSE-formatted string chunks. */
function sseStream(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let i = 0;
  return new ReadableStream({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(encoder.encode(chunks[i++]));
      } else {
        controller.close();
      }
    },
  });
}

/** Helper: mock fetch to return an SSE response with the given body stream. */
function mockFetchSse(body: ReadableStream<Uint8Array>) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'text/event-stream' }),
    body,
  } as unknown as Response);
}

describe('streams.ts terminal event handling', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = global.fetch;
    vi.resetModules();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.clearAllMocks();
  });

  it('fires onError with STREAM_INCOMPLETE when stream closes without terminal event', async () => {
    // Stream delivers a chunk but no done/error event, then closes
    mockFetchSse(sseStream('event: chunk\ndata: {"text":"hello"}\n\n'));

    const { streamRequest } = await import('./streams');
    const onChunk = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onChunk, onDone, onError });

    expect(onChunk).toHaveBeenCalledWith('hello');
    expect(onDone).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledTimes(1);
    expect((onError.mock.calls[0]?.[0] as { code?: string })?.code).toBe('STREAM_INCOMPLETE');
  });

  it('does not fire STREAM_INCOMPLETE when done event is received', async () => {
    mockFetchSse(
      sseStream(
        'event: chunk\ndata: {"text":"hi"}\n\n',
        'event: done\ndata: {"ok":true}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();
    const onError = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone, onError });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('suppresses second done event (once-guard)', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"first":true}\n\n',
        'event: done\ndata: {"second":true}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onDone.mock.calls[0]?.[0]).toEqual({ first: true });
  });

  it('dispatches rag.citation_matches after done (non-terminal not gated)', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"ok":true}\n\n',
        'event: rag.citation_matches\ndata: {"matches":[]}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();
    const onCitationMatches = vi.fn();

    await streamRequest(
      'http://localhost/api/test',
      {},
      { onDone, onCitationMatches }
    );

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onCitationMatches).toHaveBeenCalledTimes(1);
  });

  it('passes finishReason through done payload', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"finishReason":"length"}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onDone.mock.calls[0]?.[0]).toEqual(
      expect.objectContaining({ finishReason: 'length' })
    );
  });

  it('suppresses error after done (once-guard)', async () => {
    mockFetchSse(
      sseStream(
        'event: done\ndata: {"ok":true}\n\n',
        'event: error\ndata: {"error":"late error"}\n\n'
      )
    );

    const { streamRequest } = await import('./streams');
    const onDone = vi.fn();
    const onError = vi.fn();

    await streamRequest('http://localhost/api/test', {}, { onDone, onError });

    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });
});

describe('consumeShapeStream', () => {
  it('resolves normally when a done event is received', async () => {
    const body = sseStream('event: chunk\ndata: {"text":"hi"}\n\nevent: done\ndata: {}\n\n');
    mockFetchSse(body);

    const { consumeShapeStream } = await import('./streams');
    const events: string[] = [];
    await consumeShapeStream('http://localhost/test', {}, (event) => { events.push(event); });
    expect(events).toContain('done');
  });

  it('throws STREAM_INCOMPLETE when stream ends without terminal event', async () => {
    const body = sseStream('event: chunk\ndata: {"text":"hi"}\n\n');
    mockFetchSse(body);

    const { consumeShapeStream } = await import('./streams');
    const events: string[] = [];
    try {
      await consumeShapeStream('http://localhost/test', {}, (event) => { events.push(event); });
      expect.fail('should have thrown');
    } catch (e) {
      expect((e as Error).message).toContain('terminal event');
      expect((e as Error & { code?: string }).code).toBe('STREAM_INCOMPLETE');
    }
  });

  it('throws error event instead of STREAM_INCOMPLETE when error arrives', async () => {
    const body = sseStream('event: error\ndata: {"error":"AI_OFFLINE"}\n\n');
    mockFetchSse(body);

    const { consumeShapeStream } = await import('./streams');
    try {
      await consumeShapeStream('http://localhost/test', {}, () => {});
      expect.fail('should have thrown');
    } catch (e) {
      expect((e as Error).message).toBe('AI_OFFLINE');
      expect((e as Error & { code?: string }).code).not.toBe('STREAM_INCOMPLETE');
    }
  });
});
