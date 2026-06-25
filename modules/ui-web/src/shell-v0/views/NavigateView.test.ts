/**
 * @vitest-environment happy-dom
 *
 * Slice 491 §9.D Phase E (C2) — NavigateView tests.
 *
 * Exercises the SSE event → handler dispatch path end-to-end using a mock
 * fetch returning a `ReadableStream` of SSE-formatted bytes. Validates that
 * the typed `CoreNavigateChatHandlers` interface's methods are wired
 * correctly to the view's state (streamingText, receipts).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './NavigateView.js';

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
  // happy-dom strips the global fetch sometimes; restore default
  if (typeof globalThis.fetch !== 'function') {
    globalThis.fetch = (() => Promise.reject(new Error('fetch unmocked'))) as unknown as typeof fetch;
  }
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('NavigateView', () => {
  it('renders header + composer on mount', async () => {
    const el = document.createElement('jf-navigate-view');
    document.body.appendChild(el);
    await settle(el);
    const text = el.shadowRoot?.textContent ?? '';
    expect(text).toContain('Navigate Chat');
    expect(el.shadowRoot?.querySelector('textarea')).toBeTruthy();
    expect(el.shadowRoot?.querySelector('button')).toBeTruthy();
    el.remove();
  });

  it('disables send button when input is empty', async () => {
    const el = document.createElement('jf-navigate-view');
    document.body.appendChild(el);
    await settle(el);
    const btn = el.shadowRoot?.querySelector('button') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    el.remove();
  });

  it('streams chunk events into streamingText', async () => {
    const body =
      sseChunk('chunk', { text: 'Hello ' }) +
      sseChunk('chunk', { text: 'world' }) +
      sseChunk('done', { finalResponse: 'Hello world' });
    const fetchSpy = vi.fn(() => {
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
    });
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    const el = document.createElement('jf-navigate-view') as unknown as {
      apiBase: string;
      inputDraft: string;
      streamingText: string;
      isStreaming: boolean;
    } & HTMLElement;
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    el.inputDraft = 'take me to library';
    await settle(el as unknown as HTMLElement);
    const btn = (el as unknown as HTMLElement).shadowRoot?.querySelector(
      'button',
    ) as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    btn.click();
    // Allow the async send + stream drain to finish.
    await new Promise((r) => setTimeout(r, 50));
    await settle(el as unknown as HTMLElement);

    expect(fetchSpy).toHaveBeenCalled();
    expect(el.streamingText).toContain('Hello world');
    expect(el.isStreaming).toBe(false);
    el.remove();
  });

  it('renders navigate.url_* receipts on extracted + dispatched', async () => {
    const body =
      sseChunk('chunk', { text: 'OK' }) +
      sseChunk('navigate.url_extracted', {
        index: 0,
        addressKind: 'navigate',
        target: 'core.library-surface',
        transport: 'LLM_EMISSION',
      }) +
      sseChunk('navigate.url_dispatched', {
        index: 0,
        addressKind: 'navigate',
        target: 'core.library-surface',
        outcome: 'forwarded',
        envelopeId: 'ie-test',
      }) +
      sseChunk('done', {});
    globalThis.fetch = mockFetchSse(body);

    const el = document.createElement('jf-navigate-view') as unknown as {
      apiBase: string;
      inputDraft: string;
      receipts: Array<{ outcome: string; target: string }>;
    } & HTMLElement;
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    el.inputDraft = 'take me to library';
    await settle(el as unknown as HTMLElement);
    const btn = (el as unknown as HTMLElement).shadowRoot?.querySelector(
      'button',
    ) as HTMLButtonElement;
    btn.click();
    await new Promise((r) => setTimeout(r, 50));
    await settle(el as unknown as HTMLElement);

    expect(el.receipts.length).toBe(1);
    expect(el.receipts[0]?.target).toBe('core.library-surface');
    expect(el.receipts[0]?.outcome).toBe('forwarded');
    el.remove();
  });

  it('renders navigate.url_rejected with reasonCode + message', async () => {
    const body =
      sseChunk('navigate.url_extracted', {
        index: 0,
        addressKind: 'invoke',
        target: 'core.bulk-reindex',
      }) +
      sseChunk('navigate.url_rejected', {
        index: 0,
        addressKind: 'invoke',
        target: 'core.bulk-reindex',
        reason: 'confirmation-required',
        message: 'gate denied',
      }) +
      sseChunk('done', {});
    globalThis.fetch = mockFetchSse(body);

    const el = document.createElement('jf-navigate-view') as unknown as {
      apiBase: string;
      inputDraft: string;
      receipts: Array<{
        outcome: string;
        target: string;
        reasonCode: string;
        message: string;
      }>;
    } & HTMLElement;
    el.apiBase = 'http://test';
    document.body.appendChild(el);
    await settle(el);
    el.inputDraft = 'reindex everything';
    await settle(el as unknown as HTMLElement);
    const btn = (el as unknown as HTMLElement).shadowRoot?.querySelector(
      'button',
    ) as HTMLButtonElement;
    btn.click();
    await new Promise((r) => setTimeout(r, 50));
    await settle(el as unknown as HTMLElement);

    expect(el.receipts.length).toBe(1);
    expect(el.receipts[0]?.outcome).toBe('rejected');
    expect(el.receipts[0]?.reasonCode).toBe('confirmation-required');
    expect(el.receipts[0]?.message).toBe('gate denied');
    el.remove();
  });
});
