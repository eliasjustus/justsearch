// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest';
import { pumpHostAiStream, streamViaHost } from './pumpHostAiStream.js';
import type { AIChunk } from './plugin-types.js';

async function* fromArray(chunks: AIChunk[]): AsyncGenerator<AIChunk> {
  for (const c of chunks) yield c;
}

describe('pumpHostAiStream', () => {
  it('forwards each chunk as (event, payload) callback', async () => {
    const events: Array<[string, unknown]> = [];
    await pumpHostAiStream(
      fromArray([
        { name: 'token', payload: { text: 'hello' } },
        { name: 'token', payload: { text: ' world' } },
        { name: 'done', payload: { ok: true } },
      ]),
      (e, p) => events.push([e, p]),
    );
    expect(events).toEqual([
      ['token', { text: 'hello' }],
      ['token', { text: ' world' }],
      ['done', { ok: true }],
    ]);
  });

  it('propagates iterator-thrown errors', async () => {
    async function* bad(): AsyncGenerator<AIChunk> {
      yield { name: 'token', payload: 'a' };
      throw new Error('boom');
    }
    const events: Array<[string, unknown]> = [];
    await expect(
      pumpHostAiStream(bad(), (e, p) => events.push([e, p])),
    ).rejects.toThrow(/boom/);
    expect(events).toEqual([['token', 'a']]);
  });

  it('honors pre-aborted signal with AbortError', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(
      pumpHostAiStream(
        fromArray([{ name: 'token', payload: 'x' }]),
        () => {},
        controller.signal,
      ),
    ).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('aborts mid-stream when signal fires', async () => {
    const controller = new AbortController();
    let yielded = 0;
    async function* slow(): AsyncGenerator<AIChunk> {
      yielded++;
      yield { name: 'token', payload: 1 };
      controller.abort();
      yielded++;
      yield { name: 'token', payload: 2 };
    }
    await expect(
      pumpHostAiStream(slow(), () => {}, controller.signal),
    ).rejects.toMatchObject({ name: 'AbortError' });
    // The generator advanced once before the abort caught up.
    expect(yielded).toBeGreaterThanOrEqual(1);
  });

  // 565 robustness — a host stream that ends with no terminal `done`/`error` event
  // must surface STREAM_INCOMPLETE (symmetric with the fetch path consumeShapeStream),
  // not resolve silently and leave the view's isStreaming stuck true.
  it('surfaces STREAM_INCOMPLETE when the stream ends without a terminal event', async () => {
    const events: Array<[string, unknown]> = [];
    await expect(
      pumpHostAiStream(fromArray([{ name: 'token', payload: 'a' }]), (e, p) =>
        events.push([e, p]),
      ),
    ).rejects.toMatchObject({ code: 'STREAM_INCOMPLETE' });
    // The non-terminal chunk was still forwarded before the failure.
    expect(events).toEqual([['token', 'a']]);
  });

  it('surfaces STREAM_INCOMPLETE for a completely empty stream', async () => {
    await expect(
      pumpHostAiStream(fromArray([]), () => {}),
    ).rejects.toMatchObject({ code: 'STREAM_INCOMPLETE' });
  });

  it('an `error` chunk is terminal — resolves (the view onError handles it), no STREAM_INCOMPLETE', async () => {
    const events: Array<[string, unknown]> = [];
    await expect(
      pumpHostAiStream(
        fromArray([{ name: 'error', payload: { error: 'boom', errorCode: 'X' } }]),
        (e, p) => events.push([e, p]),
      ),
    ).resolves.toBeUndefined();
    expect(events).toEqual([['error', { error: 'boom', errorCode: 'X' }]]);
  });
});

describe('streamViaHost — tempdoc 521 §22 Phase E', () => {
  it('routes through host_.ai.streamShape when host_ is defined', async () => {
    const events: Array<[string, unknown]> = [];
    async function* mockStream(): AsyncGenerator<AIChunk> {
      yield { name: 'token', payload: { text: 'hi' } };
      yield { name: 'done', payload: { ok: true } };
    }
    const host = {
      ai: { streamShape: () => mockStream() },
    } as unknown as Parameters<typeof streamViaHost>[0]['host_'];

    await streamViaHost({
      host_: host,
      shapeId: 'core.free-chat',
      fallbackUrl: 'http://unused',
      body: { prompt: 'hi' },
      onEvent: (e, p) => events.push([e, p]),
    });
    expect(events).toEqual([
      ['token', { text: 'hi' }],
      ['done', { ok: true }],
    ]);
  });

  it('with the test-suite flag set, the fallback path is reachable', () => {
    // The setup file (streamViaHostSuppress.ts) sets the flag for the
    // whole suite, so test contexts without host_ still work via the
    // fallback (mocked fetch / consumeShapeStream). Production code
    // never sets the flag — the next test asserts the throw.
    const flag = (globalThis as { __STREAM_VIA_HOST_ALLOW_FALLBACK__?: boolean })
      .__STREAM_VIA_HOST_ALLOW_FALLBACK__;
    expect(flag).toBe(true);
  });

  it('throws when host_ is undefined AND the fallback flag is unset', async () => {
    const win = globalThis as { __STREAM_VIA_HOST_ALLOW_FALLBACK__?: boolean };
    const prev = win.__STREAM_VIA_HOST_ALLOW_FALLBACK__;
    win.__STREAM_VIA_HOST_ALLOW_FALLBACK__ = false;
    try {
      await expect(
        streamViaHost({
          host_: undefined,
          shapeId: 'core.free-chat',
          fallbackUrl: 'http://unused',
          body: {},
          onEvent: () => {},
        }),
      ).rejects.toThrow(/host_ must be wired/);
    } finally {
      win.__STREAM_VIA_HOST_ALLOW_FALLBACK__ = prev;
    }
  });
});
