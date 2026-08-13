/**
 * The ask issuance seam (tempdoc 818 slice 2).
 *
 * `askClient` is the ONE place this window dispatches a grounded ask, so these tests pin the two
 * things a second issuance site would let drift: the request the seam actually builds (through the
 * SHARED `buildRequestBody`, with the frozen set as `docIds` — L5), and the terminal contract every
 * caller relies on — exactly one of `onDone` / `onLocked` / `onError`, with 423 typed as a refusal
 * rather than a generic failure (L9).
 *
 * `consumeShapeStream` is mocked; `dispatchShapeEventToHandlers` is the REAL one, so the SSE
 * event-name → generated-handler-method mapping is genuinely exercised rather than assumed.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

type StreamRun = (
  url: string,
  body: unknown,
  onEvent: (event: string, payload: unknown) => void,
  signal?: AbortSignal,
) => Promise<void>;

const mocks = vi.hoisted(() => {
  const state: { run: StreamRun } = { run: async () => {} };
  return {
    state,
    consumeShapeStream: vi.fn(
      (url: string, body: unknown, onEvent: (e: string, p: unknown) => void, signal?: AbortSignal) =>
        state.run(url, body, onEvent, signal),
    ),
  };
});

vi.mock('../../../api/streams.js', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/streams.js')>('../../../api/streams.js');
  return { ...actual, consumeShapeStream: mocks.consumeShapeStream };
});

import { askDocuments, ASK_SHAPE_ID } from './askClient.js';

const consumeShapeStreamMock = mocks.consumeShapeStream;

interface SinkCalls {
  deltas: string[];
  done: Parameters<Parameters<typeof askDocuments>[1]['onDone']>[0] | null;
  locked: number;
  errors: string[];
}

function sink(): { calls: SinkCalls; sink: Parameters<typeof askDocuments>[1] } {
  const calls: SinkCalls = { deltas: [], done: null, locked: 0, errors: [] };
  return {
    calls,
    sink: {
      onDelta: (t) => calls.deltas.push(t),
      onDone: (o) => {
        calls.done = o;
      },
      onLocked: () => {
        calls.locked += 1;
      },
      onError: (m) => calls.errors.push(m),
    },
  };
}

/** Replay a scripted SSE event list through whatever handler `askDocuments` installed. */
function replay(events: Array<[string, unknown]>): void {
  mocks.state.run = async (_url, _body, onEvent) => {
    for (const [e, p] of events) onEvent(e, p);
  };
}

const REQ = {
  apiBase: 'http://127.0.0.1:9999',
  question: 'what changed in the renewal?',
  conversationId: 'sv2-session-1',
  docIds: ['Contracts/Northfield.pdf', 'Ops/Reviews/Q2.md'],
};

beforeEach(() => {
  consumeShapeStreamMock.mockClear();
  mocks.state.run = async () => {};
});

describe('818 askClient — the request (L5)', () => {
  it('L5 — dispatches core.rag-ask to /api/chat/dispatch with the frozen set as docIds', async () => {
    const s = sink();
    await askDocuments(REQ, s.sink);

    expect(consumeShapeStreamMock).toHaveBeenCalledTimes(1);
    const call = consumeShapeStreamMock.mock.calls[0];
    const url = call?.[0] as string;
    const body = call?.[1] as Record<string, unknown>;
    expect(url).toBe('http://127.0.0.1:9999/api/chat/dispatch');
    expect(body.shapeId).toBe(ASK_SHAPE_ID);
    expect(body.question).toBe('what changed in the renewal?');
    expect(body.docIds).toEqual(['Contracts/Northfield.pdf', 'Ops/Reviews/Q2.md']);
    // Every dispatch is stamped with the window's session, exactly as the shipped window does.
    expect(body.conversationId).toBe('sv2-session-1');
  });

  it('an empty frozen set is forwarded as an empty array (the backend’s open-retrieval fallback)', async () => {
    const s = sink();
    await askDocuments({ ...REQ, docIds: [] }, s.sink);
    const body = consumeShapeStreamMock.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.docIds).toEqual([]);
  });
});

describe('818 askClient — the stream', () => {
  it('accumulates text and reports the backend’s own grounding counts (L6)', async () => {
    replay([
      ['rag.meta', { retrieval_mode: 'HYBRID', chunks_used: 4 }],
      [
        'rag.citations',
        {
          citations: [
            {
              parentDocId: 'Contracts/Northfield.pdf',
              chunkIndex: 0,
              chunkTotal: 3,
              startChar: 0,
              endChar: 40,
              score: 0.81,
              excerpt: '…net-45…',
              startLine: 1,
              endLine: 2,
              headingText: 'Payment terms',
              headingLevel: 2,
            },
          ],
        },
      ],
      ['chunk', { text: 'Payment terms ' }],
      ['chunk', { text: 'moved to net-45.' }],
      [
        'rag.citation_delta',
        {
          sentenceIndex: 0,
          sentenceText: 'Payment terms moved to net-45.',
          citations: [{ chunkIndex: 0, score: 0.4 }],
        },
      ],
      [
        'rag.citation_matches',
        {
          matches: [
            {
              sentenceIndex: 0,
              sentenceText: 'Payment terms moved to net-45.',
              chunkIndex: 0,
              similarity: 0.77,
              parentDocId: 'Contracts/Northfield.pdf',
            },
          ],
          sentencesMatched: 1,
          sentencesTotal: 2,
          tookMs: 5,
        },
      ],
      ['done', { promptTokens: 1234 }],
    ]);

    const s = sink();
    await askDocuments(REQ, s.sink);

    expect(s.calls.deltas).toEqual(['Payment terms ', 'moved to net-45.']);
    expect(s.calls.locked).toBe(0);
    expect(s.calls.errors).toEqual([]);
    const done = s.calls.done;
    expect(done).not.toBeNull();
    expect(done?.text).toBe('Payment terms moved to net-45.');
    expect(done?.retrievalMode).toBe('HYBRID');
    expect(done?.chunksUsed).toBe(4);
    expect(done?.sources).toHaveLength(1);
    expect(done?.citations).toHaveLength(1);
    expect(done?.promptTokens).toBe(1234);
    // L6 — the counts are the payload's, never counted here.
    expect(done?.grounding).toEqual({ sentencesMatched: 1, sentencesTotal: 2 });
    // The delta and the authoritative match merge into ONE claim — but tempdoc 822 §3d keeps the two
    // scores APART instead of maxing them into one number: the delta's 0.4 is a lexical word-overlap
    // ratio, the match's 0.77 is a cross-encoder probability, and only the latter may reach a tier.
    expect(done?.claims).toHaveLength(1);
    expect(done?.claims[0]?.verifiedScore).toBeCloseTo(0.77);
    expect(done?.claims[0]?.lexicalScore).toBeCloseTo(0.4);
    expect(done?.claims[0]?.sourceRefs).toEqual([0]);
  });

  it('a turn the backend never citation-matched reports NO grounding (never a fabricated zero)', async () => {
    replay([['chunk', { text: 'An ungrounded answer.' }], ['done', {}]]);
    const s = sink();
    await askDocuments(REQ, s.sink);
    expect(s.calls.done?.grounding).toBeNull();
    expect(s.calls.done?.promptTokens).toBeNull();
    expect(s.calls.done?.retrievalMode).toBeNull();
  });
});

describe('818 askClient — the terminals (L9)', () => {
  it('L9 — a 423 is a typed REFUSAL: onLocked once, and no answer is reported', async () => {
    mocks.state.run = async () => {
      const err = new Error('consumeShapeStream: HTTP 423') as Error & { status?: number };
      err.status = 423;
      throw err;
    };
    const s = sink();
    await askDocuments(REQ, s.sink);

    expect(s.calls.locked).toBe(1);
    expect(s.calls.done).toBeNull();
    expect(s.calls.errors).toEqual([]);
  });

  it('any other failure is an error terminal, not a refusal', async () => {
    mocks.state.run = async () => {
      const err = new Error('consumeShapeStream: HTTP 500') as Error & { status?: number };
      err.status = 500;
      throw err;
    };
    const s = sink();
    await askDocuments(REQ, s.sink);

    expect(s.calls.locked).toBe(0);
    expect(s.calls.done).toBeNull();
    expect(s.calls.errors).toEqual(['consumeShapeStream: HTTP 500']);
  });

  it('a stream that ends without a terminal event still reaches exactly one sink terminal', async () => {
    mocks.state.run = async () => {
      const err = new Error('Stream ended without terminal event') as Error & { code?: string };
      err.code = 'STREAM_INCOMPLETE';
      throw err;
    };
    const s = sink();
    await askDocuments(REQ, s.sink);
    expect(s.calls.errors).toHaveLength(1);
    expect(s.calls.done).toBeNull();
    expect(s.calls.locked).toBe(0);
  });
});
