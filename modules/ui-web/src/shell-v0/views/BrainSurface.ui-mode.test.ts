// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './BrainSurface.js';
import {
  __resetUiModeForTest,
  getUiMode,
  setUiMode,
  UI_MODE_INTENT_HEADER,
} from '../state/uiModeState.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  updateComplete: Promise<boolean>;
}

const realFetch = globalThis.fetch;
let posts: Array<{ url: string; body: string; intent: string | null }> = [];
let postStatus = 200;
let deferPosts = false;
let postResolvers: Array<(response: Response) => void> = [];

function response(status = postStatus): Response {
  return new Response('{}', { status, headers: { 'content-type': 'application/json' } });
}

async function settle(el: BrainHost): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

function modeButtons(el: BrainHost): HTMLButtonElement[] {
  return [...(el.shadowRoot?.querySelectorAll<HTMLButtonElement>('.mode-toggle button') ?? [])];
}

beforeEach(() => {
  __resetUiModeForTest();
  posts = [];
  postStatus = 200;
  deferPosts = false;
  postResolvers = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.endsWith('/api/settings/v2') && method === 'POST') {
      posts.push({
        url,
        body: String(init?.body ?? ''),
        intent: new Headers(init?.headers).get(UI_MODE_INTENT_HEADER),
      });
      if (deferPosts) {
        return new Promise<Response>((resolve, reject) => {
          postResolvers.push(resolve);
          init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), { once: true });
        });
      }
      return response();
    }
    if (url.endsWith('/api/settings/v2')) {
      return new Response(JSON.stringify({ ui: { mode: 'simple' }, llm: {} }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }
    return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
});

afterEach(() => {
  document.body.innerHTML = '';
  globalThis.fetch = realFetch;
  vi.useRealTimers();
  __resetUiModeForTest();
});

describe('BrainSurface shared detail level', () => {
  it('renders the stable advanced value as the user-facing Detailed label', async () => {
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    expect(modeButtons(el).map((button) => button.textContent?.trim())).toEqual([
      'Simple',
      'Detailed',
    ]);
    expect(modeButtons(el).map((button) => button.type)).toEqual(['button', 'button']);
    expect(modeButtons(el).map((button) => button.getAttribute('aria-pressed'))).toEqual([
      'true',
      'false',
    ]);

    setUiMode('advanced');
    await el.updateComplete;
    expect(modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.classList)
      .toContain('active');
    expect(modeButtons(el).map((button) => button.getAttribute('aria-pressed'))).toEqual([
      'false',
      'true',
    ]);
  });

  it('does not let its settings refresh overwrite a newer shared choice', async () => {
    setUiMode('advanced');
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    expect(getUiMode()).toBe('advanced');
    expect(modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.classList)
      .toContain('active');
  });

  it('publishes a Brain change through uiModeState while persisting the compatible wire value', async () => {
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.click();
    await settle(el);

    expect(getUiMode()).toBe('advanced');
    expect(posts).toHaveLength(1);
    expect(JSON.parse(posts[0]!.body)).toEqual({ ui: { mode: 'advanced' } });
    expect(posts[0]!.intent).toMatch(/:1$/);
  });

  it('rolls the shared projection back when persistence rejects the change', async () => {
    postStatus = 500;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.click();
    await settle(el);

    expect(getUiMode()).toBe('simple');
    expect(modeButtons(el).find((button) => button.textContent?.trim() === 'Simple')?.classList)
      .toContain('active');
    expect(el.shadowRoot?.textContent).toContain("Couldn't save detail level (HTTP 500).");
  });

  it('serializes rapid changes so the last click is the final persisted value', async () => {
    deferPosts = true;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    const [simple, detailed] = modeButtons(el);
    detailed?.click();
    simple?.click();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(getUiMode()).toBe('simple');
    expect(posts.map((post) => JSON.parse(post.body))).toEqual([{ ui: { mode: 'advanced' } }]);
    postResolvers.shift()?.(response(200));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(posts.map((post) => JSON.parse(post.body))).toEqual([
      { ui: { mode: 'advanced' } },
      { ui: { mode: 'simple' } },
    ]);
    postResolvers.shift()?.(response(200));
    await settle(el);

    expect(getUiMode()).toBe('simple');
    expect(simple?.classList).toContain('active');
  });

  it('aborts a hung save, clears Brain busy state, and rolls back', async () => {
    deferPosts = true;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);
    vi.useFakeTimers();

    modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.click();
    await Promise.resolve();
    expect((el as unknown as { busy: { mode: boolean } }).busy.mode).toBe(true);

    await vi.advanceTimersByTimeAsync(10_000);
    await el.updateComplete;

    expect((el as unknown as { busy: { mode: boolean } }).busy.mode).toBe(false);
    expect(getUiMode()).toBe('simple');
    expect(el.shadowRoot?.textContent).toContain("Couldn't save detail level: the request timed out.");
  });
});
