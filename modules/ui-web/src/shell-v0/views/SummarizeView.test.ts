/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C3) — SummarizeView tests.
 * Updated F5: shape-id branching for batch + hierarchical; multi-doc input.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SummarizeView.js';
import type { CitationMatch, Claim } from '../components/chat/citationTypes.js';
import { sourceGrounding } from '../components/chat/evidenceProjection.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function sseChunk(event: string, data: object): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

function mockFetchSse(body: string): typeof fetch {
  return vi.fn(() => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(body));
        controller.close();
      },
    });
    return Promise.resolve(
      new Response(stream, {
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
      }),
    );
  }) as unknown as typeof fetch;
}

beforeEach(() => {
  if (typeof globalThis.fetch !== 'function') {
    globalThis.fetch = (() =>
      Promise.reject(new Error('fetch unmocked'))) as unknown as typeof fetch;
  }
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('SummarizeView', () => {
  it('renders header + multi-doc composer on mount', async () => {
    const el = document.createElement('jf-summarize-view');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('Summarize');
    expect(el.shadowRoot?.querySelector('textarea')).toBeTruthy();
    expect(el.shadowRoot?.querySelector('button')).toBeTruthy();
    el.remove();
  });

  it('disables send button when docIds is empty', async () => {
    const el = document.createElement('jf-summarize-view');
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('button') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    el.remove();
  });

  it('streams chunk events into streamingText (single-doc default)', async () => {
    const body =
      sseChunk('chunk', { text: 'Summary: ' }) +
      sseChunk('chunk', { text: 'concise overview.' }) +
      sseChunk('done', {});
    const fetchSpy = mockFetchSse(body);
    globalThis.fetch = fetchSpy;

    const el = document.createElement('jf-summarize-view') as unknown as {
      apiBase: string;
      docIdsDraft: string;
      streamingText: string;
      isStreaming: boolean;
    } & HTMLElement;
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    el.docIdsDraft = 'doc.test';
    await settle(el as unknown as HTMLElement);
    const btn = (el as unknown as HTMLElement).shadowRoot?.querySelector(
      'button',
    ) as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    btn.click();
    await new Promise((r) => setTimeout(r, 50));
    await settle(el as unknown as HTMLElement);

    expect(fetchSpy).toHaveBeenCalled();
    expect(el.streamingText).toContain('Summary: concise overview.');
    expect(el.isStreaming).toBe(false);
    el.remove();
  });

  it('single-doc shape posts {docId} to /api/chat/summarize', async () => {
    const body = sseChunk('chunk', { text: 'ok.' }) + sseChunk('done', {});
    const fetchSpy = mockFetchSse(body);
    globalThis.fetch = fetchSpy;

    const el = document.createElement('jf-summarize-view') as unknown as {
      apiBase: string;
      docIdsDraft: string;
    } & HTMLElement;
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    el.docIdsDraft = 'doc.alpha';
    await settle(el as unknown as HTMLElement);
    (
      (el as unknown as HTMLElement).shadowRoot?.querySelector(
        'button',
      ) as HTMLButtonElement
    ).click();
    await new Promise((r) => setTimeout(r, 50));

    const call = (fetchSpy as unknown as { mock: { calls: unknown[][] } }).mock
      .calls[0]!;
    expect(call[0]).toBe('http://test/api/chat/summarize');
    const init = call[1] as RequestInit;
    expect(JSON.parse(init.body as string)).toEqual({ docId: 'doc.alpha' });
    el.remove();
  });

  it('batch shape posts {docIds: [...]} to /api/chat/batch-summarize', async () => {
    const body = sseChunk('chunk', { text: 'batched.' }) + sseChunk('done', {});
    const fetchSpy = mockFetchSse(body);
    globalThis.fetch = fetchSpy;

    const el = document.createElement('jf-summarize-view') as unknown as {
      apiBase: string;
      shapeId: string;
      docIdsDraft: string;
    } & HTMLElement;
    el.apiBase = 'http://test';
    el.setAttribute('shape-id', 'core.batch-summarize');
    document.body.appendChild(el);
    await settle(el);
    el.docIdsDraft = 'a, b\nc';
    await settle(el as unknown as HTMLElement);
    (
      (el as unknown as HTMLElement).shadowRoot?.querySelector(
        'button',
      ) as HTMLButtonElement
    ).click();
    await new Promise((r) => setTimeout(r, 50));

    const call = (fetchSpy as unknown as { mock: { calls: unknown[][] } }).mock
      .calls[0]!;
    expect(call[0]).toBe('http://test/api/chat/batch-summarize');
    const init = call[1] as RequestInit;
    expect(JSON.parse(init.body as string)).toEqual({
      docIds: ['a', 'b', 'c'],
    });
    el.remove();
  });

  it('hierarchical shape posts {docIds: [...]} to /api/chat/hierarchical-summarize', async () => {
    const body =
      sseChunk('chunk', { text: 'hierarchical.' }) + sseChunk('done', {});
    const fetchSpy = mockFetchSse(body);
    globalThis.fetch = fetchSpy;

    const el = document.createElement('jf-summarize-view') as unknown as {
      apiBase: string;
      docIdsDraft: string;
    } & HTMLElement;
    el.apiBase = 'http://test';
    el.setAttribute('shape-id', 'core.hierarchical-summarize');
    document.body.appendChild(el);
    await settle(el);
    el.docIdsDraft = 'd1\nd2';
    await settle(el as unknown as HTMLElement);
    (
      (el as unknown as HTMLElement).shadowRoot?.querySelector(
        'button',
      ) as HTMLButtonElement
    ).click();
    await new Promise((r) => setTimeout(r, 50));

    const call = (fetchSpy as unknown as { mock: { calls: unknown[][] } }).mock
      .calls[0]!;
    expect(call[0]).toBe('http://test/api/chat/hierarchical-summarize');
    const init = call[1] as RequestInit;
    expect(JSON.parse(init.body as string)).toEqual({ docIds: ['d1', 'd2'] });
    el.remove();
  });

  it('captures error event into errorMessage', async () => {
    const body =
      sseChunk('error', { error: 'AI_OFFLINE', message: 'llama-server down' });
    globalThis.fetch = mockFetchSse(body);

    const el = document.createElement('jf-summarize-view') as unknown as {
      apiBase: string;
      docIdsDraft: string;
      errorMessage: string;
      isStreaming: boolean;
    } & HTMLElement;
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    el.docIdsDraft = 'doc.test';
    await settle(el as unknown as HTMLElement);
    const btn = (el as unknown as HTMLElement).shadowRoot?.querySelector(
      'button',
    ) as HTMLButtonElement;
    btn.click();
    await new Promise((r) => setTimeout(r, 50));
    await settle(el as unknown as HTMLElement);

    expect(el.errorMessage).toBe('AI_OFFLINE');
    expect(el.isStreaming).toBe(false);
    el.remove();
  });

  it('847 §1.5b — a non-admitted producer mints no mark AND no panel tier, its twin mints both', async () => {
    // This surface binds `citations` straight into the shared `<jf-citations-panel>`, where
    // `sourceGrounding` reads `similarity` into a per-source tier. Gating only the claims left the
    // panel announcing "Grounds N sentences" at a cosine-derived tier beside markless prose.
    // Both arms run the SAME payload, so the empty arm is the gate rather than an inert fixture.
    const source = {
      parentDocId: 'docs/lease.md',
      chunkIndex: 0,
      chunkTotal: 1,
      startChar: 0,
      endChar: 40,
      score: 0.9,
      excerpt: 'The lock held past the renewal date.',
      startLine: 1,
      endLine: 2,
      headingText: 'Renewal',
      headingLevel: 2,
    };
    const bodyFor = (scorer: string): string =>
      sseChunk('rag.citations', { citations: [source] }) +
      sseChunk('rag.citation_matches', {
        scorer,
        sentencesTotal: 1,
        sentencesScored: 1,
        matches: [
          {
            sentenceIndex: 0,
            sentenceText: 'The lock held.',
            sourceIndex: 0,
            similarity: 0.94,
            parentDocId: 'docs/lease.md',
          },
        ],
      }) +
      sseChunk('done', {});

    const summarizeWith = async (
      scorer: string,
    ): Promise<{ citations: CitationMatch[]; claims: Claim[] }> => {
      globalThis.fetch = mockFetchSse(bodyFor(scorer));
      const el = document.createElement('jf-summarize-view') as unknown as {
        apiBase: string;
        docIdsDraft: string;
        citations: CitationMatch[];
        claims: Claim[];
      } & HTMLElement;
      el.apiBase = 'http://test';
      document.body.appendChild(el);
      await settle(el);
      el.docIdsDraft = 'doc.test';
      await settle(el as unknown as HTMLElement);
      (
        (el as unknown as HTMLElement).shadowRoot?.querySelector('button') as HTMLButtonElement
      ).click();
      await new Promise((r) => setTimeout(r, 50));
      await settle(el as unknown as HTMLElement);
      const observed = { citations: [...el.citations], claims: [...el.claims] };
      el.remove();
      return observed;
    };

    const admitted = await summarizeWith('CROSS_ENCODER');
    expect(admitted.citations).toHaveLength(1);
    expect(admitted.claims[0]?.verifiedScore).toBe(0.94);
    expect(sourceGrounding(0, admitted.citations, 'docs/lease.md').cited).toBe(true);

    const gated = await summarizeWith('EMBEDDING_COSINE');
    expect(gated.citations).toEqual([]);
    expect(gated.claims[0]?.verifiedScore).toBeNull();
    expect(sourceGrounding(0, gated.citations, 'docs/lease.md').cited).toBe(false);
    expect(sourceGrounding(0, gated.citations, 'docs/lease.md').groundedSentences).toBe(0);
  });

  it('setDocId public API pre-fills the textarea', async () => {
    const el = document.createElement('jf-summarize-view') as unknown as {
      docIdsDraft: string;
      setDocId(docId: string): void;
    } & HTMLElement;
    document.body.appendChild(el);
    await settle(el);
    el.setDocId('doc.from-context-menu');
    await settle(el as unknown as HTMLElement);
    expect(el.docIdsDraft).toBe('doc.from-context-menu');
    const ta = (el as unknown as HTMLElement).shadowRoot?.querySelector(
      'textarea',
    ) as HTMLTextAreaElement;
    expect(ta.value).toBe('doc.from-context-menu');
    el.remove();
  });
});
