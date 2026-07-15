// @vitest-environment happy-dom

/**
 * Tempdoc 727 F-8 — a failed unified-thread refresh must not stay completely invisible.
 *
 * `fetchUnifiedThread` (unifiedThreadClient.ts) returns the module-level `EMPTY` constant on any
 * failure — deliberate (the projector must never become an authority; a failed refresh must not wipe
 * the live render), pinned by `unifiedThreadClient.test.ts`'s "returns EMPTY on a non-ok response"
 * case, which this change does NOT touch. The gap was that the fallback was silent: a backend bug made
 * `/api/thread/{id}` 500 for every encrypted conversation and the UI showed an empty thread with no
 * hint anything was wrong.
 *
 * `refreshUnifiedThread` (UnifiedChatView.ts:2967) now passes an `onFailure` callback into
 * `fetchUnifiedThread` and records it on `this.unifiedThreadRefreshFailed`, which
 * `renderThreadRefreshFailedNotice()` projects into a `<jf-system-notice data-testid=
 * "thread-refresh-failed">`. These tests drive `refreshUnifiedThread()` directly on a DETACHED element
 * (no `connectedCallback` — mirrors `BrainSurface.reindex-coherence.test.ts`'s harness pattern) against
 * a stubbed `fetch`, then render the private notice method and inspect the DOM.
 *
 * Fails on pre-fix code: before this change, `refreshUnifiedThread` never inspected `res.ok` — it only
 * ever read `res.events`/`res.lifecycles` off the `ThreadResponse` (always `{events:[], lifecycles:[]}`
 * on failure, per the EMPTY contract) — so `unifiedThreadRefreshFailed` did not exist and
 * `renderThreadRefreshFailedNotice` did not exist; these tests could not even compile against the
 * pre-fix source, let alone pass.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, type TemplateResult, type nothing } from 'lit';
import './UnifiedChatView.js';

interface ThreadRefreshHarness {
  sessionId: string;
  apiBase: string;
  unifiedThreadRefreshFailed: { reason: string; detail?: string } | null;
  refreshUnifiedThread(): Promise<void>;
  renderThreadRefreshFailedNotice(): TemplateResult | typeof nothing;
}

function makeHarness(): ThreadRefreshHarness {
  const el = document.createElement('jf-unified-chat-view') as unknown as ThreadRefreshHarness;
  el.sessionId = 'c1';
  el.apiBase = 'http://127.0.0.1:33221';
  return el;
}

function noticeContainer(harness: ThreadRefreshHarness): HTMLDivElement {
  const container = document.createElement('div');
  render(harness.renderThreadRefreshFailedNotice() as TemplateResult, container);
  return container;
}

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, status: ok ? 200 : 500, json: async () => body } as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('UnifiedChatView thread-refresh failure signal — tempdoc 727 F-8', () => {
  it('sets the failure signal and renders the notice on a non-ok response (the 500-on-encrypted-conversation bug)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, false)));
    const harness = makeHarness();

    await harness.refreshUnifiedThread();

    expect(harness.unifiedThreadRefreshFailed).not.toBeNull();
    expect(harness.unifiedThreadRefreshFailed?.reason).toBe('http-error');
    const container = noticeContainer(harness);
    const notice = container.querySelector('[data-testid="thread-refresh-failed"]');
    expect(notice).not.toBeNull();
    expect(notice?.textContent).toMatch(/couldn.t load the full activity thread/i);
  });

  it('does not set the failure signal, and renders no notice, on a successful refresh', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ conversationId: 'c1', events: [], lifecycles: [] })),
    );
    const harness = makeHarness();

    await harness.refreshUnifiedThread();

    expect(harness.unifiedThreadRefreshFailed).toBeNull();
    const container = noticeContainer(harness);
    expect(container.querySelector('[data-testid="thread-refresh-failed"]')).toBeNull();
  });

  it('clears a prior failure signal once a subsequent refresh succeeds', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({}, false))
      .mockResolvedValueOnce(
        jsonResponse({ conversationId: 'c1', events: [], lifecycles: [] }),
      );
    vi.stubGlobal('fetch', fetchMock);
    const harness = makeHarness();

    await harness.refreshUnifiedThread();
    expect(harness.unifiedThreadRefreshFailed).not.toBeNull();

    await harness.refreshUnifiedThread();
    expect(harness.unifiedThreadRefreshFailed).toBeNull();
    expect(noticeContainer(harness).querySelector('[data-testid="thread-refresh-failed"]')).toBeNull();
  });

  it('the EMPTY-on-failure fallback itself is unchanged: unifiedEvents/unifiedLifecycles stay empty on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, false)));
    const harness = makeHarness() as unknown as ThreadRefreshHarness & {
      unifiedEvents: unknown[];
      unifiedLifecycles: unknown[];
    };

    await harness.refreshUnifiedThread();

    expect(harness.unifiedEvents).toEqual([]);
    expect(harness.unifiedLifecycles).toEqual([]);
  });
});
