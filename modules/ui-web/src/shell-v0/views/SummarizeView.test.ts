/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C3) — SummarizeView tests.
 * Updated F5: shape-id branching for batch + hierarchical; multi-doc input.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SummarizeView.js';

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
