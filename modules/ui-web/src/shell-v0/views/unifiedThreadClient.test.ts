// SPDX-License-Identifier: Apache-2.0
// Tempdoc S4a (risk-review finding #1) — regression coverage for forward-tolerant thread-event parsing.
// Before this change, `threadResponseSchema` validated the WHOLE `events` array atomically
// (z.array(threadEventSchema)): one event with an unrecognized `kind` failed the entire parse and
// `fetchUnifiedThread` returned EMPTY — the whole conversation blanked. These tests pin the per-event
// parsing that replaced it: an unknown kind degrades to a generic item; a structurally invalid event is
// dropped (with a warning) but never sinks its siblings; known-kind strictness is unchanged.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchUnifiedThread, parseThreadEvent } from './unifiedThreadClient.js';

function jsonResponse(body: unknown): Response {
  return { ok: true, json: async () => body } as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('parseThreadEvent (S4a per-event parsing)', () => {
  it('parses a known-kind event under the unchanged strict schema', () => {
    const event = parseThreadEvent({
      id: 'a1',
      occurredAt: '2026-01-01T00:00:01Z',
      kind: 'ASSISTANT_MESSAGE',
      originator: 'agent',
      content: 'hello',
      attributes: {},
    });
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('ASSISTANT_MESSAGE');
    expect(event!.content).toBe('hello');
  });

  it('degrades an unrecognized kind STRING to a generic UNKNOWN event carrying rawKind', () => {
    const event = parseThreadEvent({
      id: 's1',
      occurredAt: '2026-01-01T00:00:02Z',
      kind: 'SEARCH',
      originator: 'agent',
      content: 'searching…',
      attributes: { query: 'invoices' },
    });
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('UNKNOWN');
    expect(event!.rawKind).toBe('SEARCH');
    expect(event!.attributes.query).toBe('invoices');
  });

  it('degrades an unrecognized kind even when originator/content/attributes are absent (loose passthrough)', () => {
    const event = parseThreadEvent({ id: 's2', occurredAt: '2026-01-01T00:00:03Z', kind: 'SEARCH' });
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('UNKNOWN');
    expect(event!.rawKind).toBe('SEARCH');
    expect(event!.originator).toBe('');
    expect(event!.content).toBe('');
    expect(event!.attributes).toEqual({});
  });

  it('drops a structurally invalid event (missing id) regardless of kind', () => {
    expect(
      parseThreadEvent({ occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', content: 'hi' }),
    ).toBeNull();
    expect(parseThreadEvent({ occurredAt: '2026-01-01T00:00:01Z', kind: 'SEARCH' })).toBeNull();
  });

  // 4c — verify + preserve current known-kind strictness: a KNOWN kind whose required field is missing
  // (or wrong-typed) is NOT silently downgraded to a generic UNKNOWN item; it is dropped, exactly as it
  // would have failed the old all-or-nothing schema (only the BLAST RADIUS changed — one event, not the
  // whole array).
  it('a known kind with a missing required field (content) still fails THAT event, not silently as UNKNOWN', () => {
    const event = parseThreadEvent({
      id: 'a1',
      occurredAt: '2026-01-01T00:00:01Z',
      kind: 'ASSISTANT_MESSAGE',
      originator: 'agent',
      // content missing — required by the known-kind schema
      attributes: {},
    });
    expect(event).toBeNull();
  });

  it('a known kind with a wrong-typed required field (content: number) still fails THAT event', () => {
    const event = parseThreadEvent({
      id: 'a1',
      occurredAt: '2026-01-01T00:00:01Z',
      kind: 'USER_MESSAGE',
      originator: 'user',
      content: 42,
      attributes: {},
    });
    expect(event).toBeNull();
  });
});

describe('fetchUnifiedThread (S4a forward-tolerant array parsing)', () => {
  it('a thread with one SEARCH-kind event + two known events parses to 3 events; the thread is NOT empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        conversationId: 'c1',
        events: [
          {
            id: 'u1',
            occurredAt: '2026-01-01T00:00:01Z',
            kind: 'USER_MESSAGE',
            originator: 'user',
            content: 'find invoices',
            attributes: {},
          },
          {
            id: 's1',
            occurredAt: '2026-01-01T00:00:02Z',
            kind: 'SEARCH',
            originator: 'agent',
            content: 'searching…',
            attributes: { query: 'invoices' },
          },
          {
            id: 'a1',
            occurredAt: '2026-01-01T00:00:03Z',
            kind: 'ASSISTANT_MESSAGE',
            originator: 'agent',
            content: 'done',
            attributes: {},
          },
        ],
        lifecycles: [],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res.events).toHaveLength(3);
    expect(res.events.map((e) => e.kind)).toEqual(['USER_MESSAGE', 'UNKNOWN', 'ASSISTANT_MESSAGE']);
    const unknown = res.events.find((e) => e.id === 's1')!;
    expect(unknown.rawKind).toBe('SEARCH');
  });

  it('drops a structurally invalid event but keeps its siblings, and warns once naming the count', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        conversationId: 'c1',
        events: [
          {
            id: 'u1',
            occurredAt: '2026-01-01T00:00:01Z',
            kind: 'USER_MESSAGE',
            originator: 'user',
            content: 'hi',
            attributes: {},
          },
          // missing id — structurally invalid, must be dropped without failing the array.
          { occurredAt: '2026-01-01T00:00:02Z', kind: 'USER_MESSAGE', originator: 'user', content: 'x', attributes: {} },
          {
            id: 'a1',
            occurredAt: '2026-01-01T00:00:03Z',
            kind: 'ASSISTANT_MESSAGE',
            originator: 'agent',
            content: 'ok',
            attributes: {},
          },
        ],
        lifecycles: [],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res.events.map((e) => e.id)).toEqual(['u1', 'a1']);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]![0]).toContain('dropped 1');
  });

  it('returns EMPTY when the top-level envelope itself is malformed (events not an array)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ conversationId: 'c1', events: 'not-an-array' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res).toEqual({ events: [], lifecycles: [] });
  });

  it('returns EMPTY on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) } as Response));
    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res).toEqual({ events: [], lifecycles: [] });
  });
});
