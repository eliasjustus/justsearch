// @vitest-environment happy-dom
//
// Tempdoc 561 (surface tier) — the retrospective panel + the shared-controller/drawer stores.

import { afterEach, describe, expect, it } from 'vitest';
import './RetrospectivePanel.js';
import type { RetrospectivePanel } from './RetrospectivePanel.js';
import {
  getAgentSessionController,
  peekAgentSessionController,
  subscribeAgentSession,
  __resetAgentSessionStore,
} from '../state/agentSessionStore.js';
import {
  isRetrospectiveOpen,
  toggleRetrospective,
  setRetrospectiveOpen,
  __resetRetrospectiveDrawer,
} from '../state/retrospectiveDrawer.js';

afterEach(() => {
  __resetAgentSessionStore();
  __resetRetrospectiveDrawer();
});

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

describe('agentSessionStore — ONE shared controller (561 surface tier)', () => {
  it('returns the same controller instance to every consumer', () => {
    expect(peekAgentSessionController()).toBeNull();
    const a = getAgentSessionController('http://x');
    const b = getAgentSessionController('http://x');
    expect(a).toBe(b);
    expect(peekAgentSessionController()).toBe(a);
  });
});

describe('retrospectiveDrawer store', () => {
  it('toggles + sets the open state', () => {
    expect(isRetrospectiveOpen()).toBe(false);
    toggleRetrospective();
    expect(isRetrospectiveOpen()).toBe(true);
    setRetrospectiveOpen(false);
    expect(isRetrospectiveOpen()).toBe(false);
  });
});

describe('RetrospectivePanel', () => {
  it('renders the retrospective tabs and projects the shared controller sessions', async () => {
    // Seed the shared controller's governed projection directly (no fetch).
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { sessions: unknown[] }).sessions = [
      { sessionId: 's-1', preview: 'find my tax docs', startedAtEpochMs: 0, iterationsUsed: 3 },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true; // render without going through the load path
    document.body.appendChild(el);
    await settle(el);

    const tabs = Array.from(el.shadowRoot?.querySelectorAll('[role="tab"]') ?? []).map((t) =>
      (t.textContent ?? '').trim(),
    );
    // Tempdoc 565 §26.D — the Inbox tab folds the Memory surface's presence/activity half in.
    expect(tabs).toEqual(['Sessions', 'Timeline', 'History', 'Inbox']);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('find my tax docs'); // the human label, not a raw UUID
    el.remove();
  });

  // Tempdoc 577 Move 1 — the Resume affordance projects from the session's declared lifecycle.
  // Tempdoc 585 §D Phase 1 (C1) — finished sessions are now click-through to the replay inspector
  // (a "Replay" button), so they are no longer a dead plain row; Resume still projects from lifecycle.
  it('renders Resume ONLY on resumable sessions; finished ones offer Replay', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { sessions: unknown[] }).sessions = [
      { sessionId: 's-live', preview: 'resumable run', resumable: true },
      { sessionId: 's-done', preview: 'finished run', resumable: false },
      { sessionId: 's-legacy', preview: 'legacy run (no flag)' },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const labels = Array.from(el.shadowRoot?.querySelectorAll('button.resume') ?? []).map((b) =>
      (b.textContent ?? '').trim(),
    );
    // 577 Move 1 — Resume projects from lifecycle (only the one resumable session).
    expect(labels.filter((l) => l === 'Resume').length).toBe(1);
    // 585 §D Phase 1 (C1) — the two finished sessions now offer Replay (read-only inspection).
    expect(labels.filter((l) => l === 'Replay').length).toBe(2);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('finished run');
    expect(text).toContain('Finished'); // the non-resumable rows keep their lifecycle label
    el.remove();
  });

  // Tempdoc 585 §D Phase 1 (C1/D1) — the LIVE open path (setRetrospectiveOpen → loadActive →
  // loadSessions fetch → render) must NOT loop. The browser froze opening this drawer; this drives the
  // exact path the other tests bypass (they set `open=true` + sessions directly), with a notify counter
  // that a render/notify loop would explode (a SYNC loop would instead hang this test = the same signal).
  it('585 §D — opening the drawer via the load path settles without looping', async () => {
    let notifyCount = 0;
    const unsub = subscribeAgentSession(() => {
      notifyCount += 1;
    });
    const origFetch = globalThis.fetch;
    globalThis.fetch = (async (url: unknown) => {
      const u = String(url);
      if (u.includes('/api/chat/sessions')) {
        return new Response(
          JSON.stringify({
            sessions: [
              { sessionId: 's-done', preview: 'a finished run', resumable: false, startedAtEpochMs: 0, iterationsUsed: 1 },
            ],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    }) as typeof fetch;
    try {
      getAgentSessionController('http://x');
      const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
      el.apiBase = 'http://x';
      document.body.appendChild(el);
      await settle(el);
      // Drive the REAL open path (not `el.open = true`): this fires loadActive → loadSessions.
      setRetrospectiveOpen(true);
      for (let i = 0; i < 30; i += 1) {
        await Promise.resolve();
        await settle(el);
      }
      // A render/notify loop would push this into the thousands; the bound proves the path settles.
      expect(notifyCount).toBeLessThan(50);
      const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
      expect(text).toContain('a finished run');
    } finally {
      globalThis.fetch = origFetch;
      unsub();
    }
  });

  it('§33 — the Inbox groups by the REAL LifecycleState vocabulary: merged, ranked, friendly', async () => {
    const ctrl = getAgentSessionController('http://x');
    // PresenceRun.state = LifecycleState.name() ∈ {READY_FOR_LLM, WAITING_APPROVAL, AFTER_TOOL_RESULT, DONE, ERROR}
    (ctrl as unknown as { presence: unknown[] }).presence = [
      { sessionId: 'r1', state: 'DONE', actor: 'agent', toolCalls: 1, turns: 1, iterations: 1 },
      { sessionId: 'r2', state: 'ERROR', actor: 'agent', toolCalls: 0, turns: 1, iterations: 1 },
      { sessionId: 'r3', state: 'READY_FOR_LLM', actor: 'agent', toolCalls: 0, turns: 1, iterations: 1 },
      { sessionId: 'r4', state: 'AFTER_TOOL_RESULT', actor: 'agent', toolCalls: 2, turns: 1, iterations: 2 },
    ];

    const el = document.createElement('jf-interaction-retrospective-panel') as RetrospectivePanel;
    el.apiBase = 'http://x';
    el.open = true;
    (el as unknown as { activeTab: string }).activeTab = 'inbox';
    document.body.appendChild(el);
    await settle(el);

    const headers = Array.from(el.shadowRoot?.querySelectorAll('.inbox-group-header') ?? []).map((h) =>
      (h.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
    // READY_FOR_LLM + AFTER_TOOL_RESULT merge into ONE friendly "Running" group; rank order Running < Done < Failed.
    expect(headers).toEqual(['Running · 2', 'Done · 1', 'Failed · 1']);
    const statuses = Array.from(el.shadowRoot?.querySelectorAll('.inbox-group') ?? []).map((g) =>
      g.getAttribute('data-status'),
    );
    expect(statuses).toEqual(['running', 'done', 'failed']);
    el.remove();
  });
});
