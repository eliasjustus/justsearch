// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.4 / §13.4 — host.ai integration tests.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createHostApi } from './HostApiImpl.js';

const apiBase = 'http://test.local';

function deps() {
  return {
    apiBase,
    registerSurfacePort: () => {},
  };
}

function mockSseResponse(events: ReadonlyArray<{ event: string; data: unknown }>): Response {
  const encoder = new TextEncoder();
  const frames = events
    .map((e) => `event: ${e.event}\ndata: ${JSON.stringify(e.data)}\n\n`)
    .join('');
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode(frames));
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

const fetchSpy = vi.fn();

beforeEach(() => {
  globalThis.fetch = fetchSpy as unknown as typeof fetch;
  fetchSpy.mockReset();
});

afterEach(() => {
  fetchSpy.mockReset();
});

describe('host.ai — streamShape', () => {
  it('parses SSE events into AIChunk stream', async () => {
    fetchSpy.mockResolvedValueOnce(
      mockSseResponse([
        { event: 'token', data: { text: 'Hello' } },
        { event: 'token', data: { text: ' world' } },
        { event: 'done', data: { ok: true } },
      ]),
    );
    const host = createHostApi('test', 'TRUSTED_PLUGIN', deps());
    const chunks = [];
    for await (const chunk of host.ai.streamShape('demo.shape', { prompt: 'Hi' })) {
      chunks.push(chunk);
    }
    expect(chunks).toHaveLength(3);
    expect(chunks[0]).toEqual({ name: 'token', payload: { text: 'Hello' } });
    expect(chunks[2]!.name).toBe('done');
  });

  it('POSTs to /api/chat/dispatch with shapeId in body (tempdoc 521 §16.1)', async () => {
    fetchSpy.mockResolvedValueOnce(mockSseResponse([]));
    const host = createHostApi('test', 'CORE', deps());
    const iter = host.ai.streamShape('shape-id', { prompt: 'P', extra: 1 });
    for await (const _ of iter) { /* drain */ }
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0]!;
    // §16.1: routes through the unified dispatcher so the shapeId in the
    // body actually picks the ConversationShape — the previous /api/chat/agent
    // URL forced every plugin call into AgentRunShape regardless of the id.
    expect(url).toBe(`${apiBase}/api/chat/dispatch`);
    expect((init as RequestInit).method).toBe('POST');
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body).toEqual({ shapeId: 'shape-id', prompt: 'P', extra: 1 });
  });

  it('emits a discriminated http-error chunk on non-2xx response (§13 A6)', async () => {
    fetchSpy.mockResolvedValueOnce(new Response(null, { status: 503 }));
    const host = createHostApi('test', 'CORE', deps());
    const chunks = [];
    for await (const chunk of host.ai.streamShape('x', {})) chunks.push(chunk);
    expect(chunks).toHaveLength(1);
    expect(chunks[0]!.name).toBe('error');
    const payload = chunks[0]!.payload as { kind: string; status?: number; detail: string };
    expect(payload.kind).toBe('http-error');
    expect(payload.status).toBe(503);
    expect(payload.detail).toContain('503');
  });

  it('emits a transport-error chunk when fetch throws (§13 A6)', async () => {
    fetchSpy.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const host = createHostApi('test', 'CORE', deps());
    const chunks = [];
    for await (const chunk of host.ai.streamShape('x', {})) chunks.push(chunk);
    expect(chunks).toHaveLength(1);
    const payload = chunks[0]!.payload as { kind: string; detail: string };
    expect(payload.kind).toBe('transport-error');
    expect(payload.detail).toBe('Failed to fetch');
  });
});

describe('host.ai — invokeShape', () => {
  it('concatenates assistant-text events into a single AIResponse.text', async () => {
    fetchSpy.mockResolvedValueOnce(
      mockSseResponse([
        { event: 'token', data: 'foo' },
        { event: 'token', data: 'bar' },
        { event: 'token', data: { text: 'baz' } },
      ]),
    );
    const host = createHostApi('test', 'CORE', deps());
    const res = await host.ai.invokeShape('demo', { prompt: 'p' });
    expect(res.text).toBe('foobarbaz');
    expect(res.events).toHaveLength(3);
  });
});

describe('host.ai — trust attenuation', () => {
  it('UNTRUSTED.openSession throws synchronously (§13 A6)', () => {
    const host = createHostApi('untrusted', 'UNTRUSTED_PLUGIN', deps());
    expect(() => host.ai.openSession('shape')).toThrow(/UNTRUSTED/);
  });

  it('TRUSTED.openSession sends with sessionId in body', async () => {
    fetchSpy.mockResolvedValueOnce(mockSseResponse([{ event: 'ack', data: { ok: true } }]));
    const host = createHostApi('test', 'TRUSTED_PLUGIN', deps());
    const sess = host.ai.openSession('shape', 'sess-42');
    expect(sess.id).toBe('sess-42');
    for await (const _ of sess.send({ prompt: 'p' })) { /* drain */ }
    const body = JSON.parse((fetchSpy.mock.calls[0]![1] as RequestInit).body as string);
    expect(body.sessionId).toBe('sess-42');
  });

  it('UNTRUSTED.invokeShape and streamShape remain available', async () => {
    fetchSpy.mockResolvedValueOnce(mockSseResponse([{ event: 'token', data: 'hi' }]));
    const host = createHostApi('untrusted', 'UNTRUSTED_PLUGIN', deps());
    const res = await host.ai.invokeShape('shape', { prompt: 'p' });
    expect(res.text).toBe('hi');
  });
});

describe('host.ai — session ergonomics', () => {
  it('auto-generates a session id when not provided', () => {
    const host = createHostApi('test', 'CORE', deps());
    const sess1 = host.ai.openSession('shape');
    const sess2 = host.ai.openSession('shape');
    expect(sess1.id).not.toBe(sess2.id);
  });

  it('close() on a session yields a session-closed chunk (§13 A6)', async () => {
    const host = createHostApi('test', 'CORE', deps());
    const sess = host.ai.openSession('shape', 's-1');
    sess.close();
    const chunks = [];
    for await (const c of sess.send({ p: 1 })) chunks.push(c);
    expect(chunks).toHaveLength(1);
    expect(chunks[0]!.name).toBe('error');
    const payload = chunks[0]!.payload as { kind: string; detail: string };
    expect(payload.kind).toBe('session-closed');
    expect(payload.detail).toContain('closed');
  });
});

describe('host.ai — session inspection (tempdoc 508-followup §ε1)', () => {
  function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }

  it('getSessionTranscript parses the messages array', async () => {
    fetchSpy.mockResolvedValueOnce(
      jsonResponse({
        messages: [
          { role: 'user', content: 'Hi', timestamp: '2026-05-18T00:00:00Z' },
          { role: 'assistant', content: 'Hello' },
        ],
      }),
    );
    const host = createHostApi('p1', 'CORE', deps());
    const snap = await host.ai.getSessionTranscript('s1');
    expect(snap.sessionId).toBe('s1');
    expect(snap.messages).toHaveLength(2);
    expect(snap.messages[0]).toMatchObject({ role: 'user', content: 'Hi' });
    expect(snap.messages[0]!.timestamp).toBe('2026-05-18T00:00:00Z');
  });

  it('getSessionTranscript drops malformed messages without role/content', async () => {
    fetchSpy.mockResolvedValueOnce(
      jsonResponse({
        messages: [
          { role: 'user', content: 'ok' },
          { content: 'missing role' }, // defaults to assistant
          { role: 'assistant' }, // dropped — no string content
          'not an object', // dropped
        ],
      }),
    );
    const host = createHostApi('p1', 'CORE', deps());
    const snap = await host.ai.getSessionTranscript('s2');
    expect(snap.messages).toHaveLength(2);
  });

  it('getSessionTranscript throws on non-2xx', async () => {
    fetchSpy.mockResolvedValueOnce(new Response('not found', { status: 404 }));
    const host = createHostApi('p1', 'CORE', deps());
    await expect(host.ai.getSessionTranscript('missing')).rejects.toThrow(/404/);
  });

  it('getSessionMetadata returns sessionId plus typed optional fields', async () => {
    fetchSpy.mockResolvedValueOnce(
      jsonResponse({
        shapeId: 'core.agent-run',
        title: 'My chat',
        createdAt: '2026-05-18T00:00:00Z',
      }),
    );
    const host = createHostApi('p1', 'CORE', deps());
    const meta = await host.ai.getSessionMetadata('s1');
    expect(meta.sessionId).toBe('s1');
    expect(meta.shapeId).toBe('core.agent-run');
    expect(meta.title).toBe('My chat');
    expect(meta.createdAt).toBe('2026-05-18T00:00:00Z');
    expect(meta.lastUpdatedAt).toBeUndefined();
  });

  it('UNTRUSTED tier can call getSessionTranscript', async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse({ messages: [] }));
    const host = createHostApi('p1', 'UNTRUSTED_PLUGIN', deps());
    const snap = await host.ai.getSessionTranscript('s1');
    expect(snap.messages).toEqual([]);
  });
});
