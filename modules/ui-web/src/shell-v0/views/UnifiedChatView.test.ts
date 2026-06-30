// @vitest-environment happy-dom

/**
 * Slice 516 FIX-T5 — UnifiedChatView abort-on-conversation-switch test.
 *
 * The bug: send()'s in-flight SSE stream wrote its onDone assistant
 * message into the thread that was current AT onDone time. If the user
 * switched conversations mid-stream, the message landed in the wrong
 * thread. FIX-T1 makes loadConversation/newConversation call
 * abortController.abort() so the stream never reaches onDone after a
 * conversation switch.
 *
 * This test verifies the abort-call contract directly. A full SSE
 * simulation would require mocking consumeShapeStream + the streams.ts
 * pipeline; the behavioural guarantee (abort fires on switch) is what
 * we need to lock in.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import './UnifiedChatView.js';
import type { UnifiedChatView } from './UnifiedChatView.js';
import { setPendingAutoRun, setPendingForceShape, takePendingAutoRun, takePendingForceShape } from '../utils/compose.js';
import { restoreUnifiedChat, resetUnifiedChatState, getUnifiedChatState } from '../state/unifiedChatState.js';
import { consumeShapeStream } from '../../api/streams.js';
// Tempdoc 609 — value imports for the 609 describes (modules are vi.mock'd below; vi.mocked() wraps them).
import { resumeConversation } from '../state/conversationListStore.js';
import { setAiActivity } from '../state/aiStateStore.js';
import { setLastViewedConversation, clearLastViewedConversation } from '../controllers/lastViewedConversation.js';
import {
  getSelectedSource,
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../state/selectedSource.js';
import {
  getAgentSessionController,
  __resetAgentSessionStore,
} from '../state/agentSessionStore.js';

// Need to mock aiStateStore so connectedCallback doesn't try to start it
// against a real api.
const AI_STATE_READY = {
  capabilities: { chat: true, rag: true, extract: false, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
};
vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(),
  stopAiStateStore: vi.fn(),
  // Fire the listener once synchronously with a chat-capable state so
  // this.aiState is populated at connect (the real store does the same on
  // first SSE frame). Existing tests are unaffected: maybeAutoRun is a no-op
  // unless an `answer` verb parked the auto-run flag.
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    listener(AI_STATE_READY);
    return () => {};
  }),
  setAiActivity: vi.fn(),
  getAiState: () => AI_STATE_READY,
}));

// Mock the streaming layer so consumeShapeStream can be controlled
// per-test (slice 517 FIX-U2). The default mock returns a never-resolving
// Promise so the test can simulate a stream in flight; individual tests
// override via vi.mocked() if they need a different shape.
vi.mock('../../api/streams.js', () => ({
  consumeShapeStream: vi.fn(
    (_url: string, _body: unknown, _onEvent: unknown, _signal?: AbortSignal) =>
      new Promise<void>(() => { /* never resolves */ }),
  ),
  dispatchShapeEventToHandlers: vi.fn(),
}));

// Tempdoc 577 Goal 3 — control the retrieve base tier's search store. The view subscribes in
// connectedCallback; we capture the listener so a test can push fabricated search snapshots
// without touching the network (setQuery/submitSearch would otherwise issue a real fetch).
let searchListener: ((s: unknown) => void) | null = null;
const SEARCH_EMPTY = {
  query: '',
  results: [],
  totalHits: 0,
  isSearching: false,
  processingTimeMs: null,
  error: null,
  searchTrace: null,
};
vi.mock('../state/searchState.js', () => ({
  subscribeSearch: vi.fn((listener: (s: unknown) => void) => {
    searchListener = listener;
    listener(SEARCH_EMPTY);
    return () => {
      searchListener = null;
    };
  }),
  setQuery: vi.fn(),
  submitSearch: vi.fn(),
  setSearchApiBase: vi.fn(),
  getSearchState: vi.fn(() => SEARCH_EMPTY),
}));

// Mock the network layer so resumeConversation + fetchMessageIds don't
// hit real endpoints.
vi.mock('../state/conversationListStore.js', async () => {
  const actual = await vi.importActual<typeof import('../state/conversationListStore.js')>(
    '../state/conversationListStore.js',
  );
  return {
    ...actual,
    setConversationApiBase: vi.fn(),
    resumeConversation: vi.fn(async (sessionId: string, shapeId: string) => ({
      sessionId,
      shapeId,
      messages: [],
    })),
    fetchMessageIds: vi.fn(async () => null),
    branchConversation: vi.fn(async () => 'uc-branch-new'),
    generateConversationTitle: vi.fn(),
    getRecentSessions: vi.fn(() => []),
    recordRecentSession: vi.fn(),
    createConversationId: () => 'uc-test-' + Math.random().toString(16).slice(2),
    exportConversationMarkdown: vi.fn(() => ''),
    editContextFloorSummary: vi.fn(async () => true),
    setMessageExcluded: vi.fn(async () => true),
    setSourceExcluded: vi.fn(async () => true),
  };
});

function mountView(): UnifiedChatView {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  const view = document.createElement('jf-unified-chat-view') as UnifiedChatView;
  view.apiBase = 'http://localhost:5173';
  document.body.appendChild(view);
  return view;
}

describe('UnifiedChatView — 637 #1 disconnected banner tone (Fix 1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('renders the disconnected banner with the verdict-SEVERITY tone (unreachable ⇒ error), not a hardcoded warning — matching SearchSurface', async () => {
    const view = mountView();
    await view.updateComplete;
    // Drive the ONE verdict the chat banner consumes to `unreachable` (the dead-binding state).
    (view as unknown as { aiState: unknown }).aiState = {
      ...AI_STATE_READY,
      verdict: { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] },
    };
    view.requestUpdate();
    await view.updateComplete;
    const banner = view.shadowRoot?.querySelector('[data-testid="chat-degradation"]');
    expect(banner).not.toBeNull();
    // The fix: tone tracks verdict severity (error/red), so chat and search cannot disagree.
    expect(banner?.getAttribute('tone')).toBe('error');
    expect(banner?.textContent).toContain('Backend disconnected');
  });
});

describe('UnifiedChatView one-window agent affordance (561 P-B3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('renders ONE conversation body — the agent run is inline, no separate <jf-agent-view> plane (561 P-B body-unification)', async () => {
    const view = mountView();
    await view.updateComplete;
    // No separate agent-view element — the agent run renders INLINE in the one thread.
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-composer')).not.toBeNull();

    // cross the §2.1 agency threshold into the action plane.
    view.affordance = 'agent';
    await view.updateComplete;

    // Still ONE body: the answer plane (the conversation) is present and NOT hidden — no plane swap,
    // no separate agent surface.
    const answerPlane = view.shadowRoot?.querySelector('.answer-plane');
    expect(answerPlane).not.toBeNull();
    expect(answerPlane?.hasAttribute('hidden')).toBe(false);
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-composer')).not.toBeNull();
    // the Agent affordance toggle is present + active (the crossing control stays visible).
    const agentBtn = Array.from(
      view.shadowRoot?.querySelectorAll('.affordance-btn') ?? [],
    ).find((b) => b.textContent?.trim() === 'Agent');
    expect(agentBtn).toBeDefined();
    expect(agentBtn?.classList.contains('active')).toBe(true);
  });

  it('561 C-2 — the supervision dial appears only at the agency crossing, and the chrome grades with it', async () => {
    const { setAutonomyLevel, __resetAutonomyForTest } = await import(
      '../substrates/autonomy/index.js'
    );
    __resetAutonomyForTest();
    const view = mountView();
    await view.updateComplete;
    // Answer plane (posture 0): no supervision dial.
    expect(view.shadowRoot?.querySelector('jf-autonomy-dial')).toBeNull();

    // Cross into agent mode: the dial appears (the phase transition is made visible).
    view.affordance = 'agent';
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('jf-autonomy-dial')).not.toBeNull();
    // assist default (posture 2): the composer copy grades.
    const composer = () => view.shadowRoot?.querySelector('jf-composer');
    expect(composer()?.getAttribute('placeholder')).toContain('writes need your OK');
    expect(composer()?.getAttribute('submit-label')).toBe('Send');

    // Raise the dial to auto (posture 3): the chrome grades up — send label + rail posture.
    setAutonomyLevel('auto');
    await view.updateComplete;
    expect(composer()?.getAttribute('submit-label')).toBe('Send & auto-run');
    const summary = view.shadowRoot?.querySelector('.activity-rail > summary');
    // Honest: the AUTO posture says irreversible writes still confirm (the C-4 floor).
    expect(summary?.textContent).toContain('confirming irreversible writes');
    __resetAutonomyForTest();
  });

  it('561 #6 — renders search EVIDENCE from the RECORD (live == record, not the raw dump)', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      {
        id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent',
        content: '',
        attributes: {
          callId: 'c1', toolName: 'core_search_index', status: 'completed',
          output: '[1] taxes (score: 0.92)\n    Path: C:/docs/taxes.md',
          structuredData: { searchResults: [{ title: 'Tax Notes', path: 'C:/docs/taxes.md', excerpt: 'deductible limits', line: 42 }] },
        },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // Tempdoc 565 §12.3.B — the record's tool activity renders through the SAME <jf-tool-call-card>
    // the live half uses, so the structured evidence now lives in the CARD's shadow DOM (one tool
    // renderer; live == record). Pierce into the card to assert the evidence cards still render.
    const card = sr.querySelector('.tool-activity jf-tool-call-card') as
      | (Element & { updateComplete: Promise<unknown> })
      | null;
    expect(card).not.toBeNull();
    await card!.updateComplete;
    const cardSr = card!.shadowRoot!;
    const evidence = cardSr.querySelector('[data-testid="tool-search-evidence"]');
    expect(evidence).not.toBeNull();
    const text = (evidence?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Tax Notes');
    expect(text).toContain('deductible limits');
    expect(text).toContain('line 42');
    // Honesty: no fabricated "% RELEVANCE" badge from the uncalibrated ranking score (559 §5 / C-6).
    expect(text).not.toContain('%');
    // The raw monospace dump is suppressed in favour of the structured cards (live == record):
    // the card renders `.tool-output` only when there is NO structured evidence.
    expect(cardSr.querySelector('.tool-output')).toBeNull();
  });

  it('renders the unified interleaved thread (chat + agent tool activity) from the record (Slice 2)', async () => {
    const view = mountView();
    await view.updateComplete;
    // Populate the canonical-record events (as GET /api/thread would return), out of input order to
    // prove the render projects by the authoritative timestamp.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'found 12 invoices', attributes: {} },
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'find my invoices', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const sr = view.shadowRoot!;
    const userMsg = Array.from(sr.querySelectorAll('.message.user')).find((e) =>
      (e.textContent ?? '').includes('find my invoices'),
    );
    expect(userMsg).toBeDefined();
    // Tempdoc 565 §12.3.B — tool activity renders through the SAME <jf-tool-call-card> the live half
    // uses (ONE tool renderer). The wire toolName is carried on the card's `.toolCall` property; the
    // header renders the humanized label ("Search Index") in the card's shadow DOM, not the raw name.
    const tool = sr.querySelector('.tool-activity');
    const card = tool?.querySelector('jf-tool-call-card') as unknown as {
      toolCall?: { toolName?: string };
    } | null;
    expect(card).toBeTruthy();
    expect(card?.toolCall?.toolName).toBe('core_search_index');

    // Interleaved in authoritative-timestamp order: user -> tool -> assistant. The assistant text now
    // renders via <jf-markdown-block> (561 P-A evidence render), so assert by element order and
    // read the assistant text from the block's property (not light-DOM textContent).
    const msgs = Array.from(sr.querySelectorAll('.message'));
    const userIdx = msgs.findIndex((e) => (e.textContent ?? '').includes('find my invoices'));
    const toolIdx = msgs.findIndex((e) => e.classList.contains('tool-activity'));
    const asstIdx = msgs.findIndex((e) => e.querySelector('jf-markdown-block'));
    expect(userIdx).toBeGreaterThanOrEqual(0);
    expect(userIdx).toBeLessThan(toolIdx);
    expect(toolIdx).toBeLessThan(asstIdx);
    const stb = sr.querySelector(
      '.message.assistant jf-markdown-block',
    ) as unknown as { text: string };
    expect(stb.text).toContain('found 12 invoices');
  });

  // Tempdoc 577 Move 1 §3e — the resume card is DERIVED state that cannot co-exist with rendered
  // run content. The shared agent controller is the THIRD content source (live `conversation`); the
  // original fix checked only thread + unifiedEvents, so a populated singleton controller left the
  // card pinned above the thread (visually caught during the round-2 inspection).
  it('hides the resume card when the shared agent controller already has run content', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      showResumePrompt: boolean;
      recentSession: unknown;
      thread: unknown[];
      unifiedEvents: unknown[];
      ensureAgentCtrl: () => { conversation: unknown[] };
    };
    v.affordance = 'agent';
    v.showResumePrompt = true;
    v.recentSession = { sessionId: 's-prev', firstMessage: 'an earlier run', timestamp: 0 };
    v.thread = [];
    v.unifiedEvents = [];
    // Empty controller → the genuinely-empty state shows the card.
    v.ensureAgentCtrl();
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.resume-prompt'), 'empty state shows the card').toBeTruthy();

    // Seed the shared controller with run content → the card must hide (it would otherwise pin
    // above the controller-rendered thread).
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'e1', type: 'user', content: 'an earlier run', timestamp: 0 },
      { id: 'e2', type: 'assistant-text', content: 'the answer', timestamp: 1 },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.resume-prompt'),
      'a populated controller hides the card',
    ).toBeNull();
    view.remove();
  });

  // Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam: a thread restored from the
  // record (prior turns) plus a NEW live run must render a seam between them so the resumed thread
  // does not read as one continuous exchange. No seam for an all-record or all-live timeline.
  it('577 #19 — renders a run/session seam between restored record history and the live run', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      unifiedEvents: unknown[];
      ensureAgentCtrl: () => unknown;
    };
    v.affordance = 'agent';
    // Restored history (the record): an older user turn + its answer.
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'earlier question', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'earlier answer', attributes: {} },
    ];
    v.ensureAgentCtrl();
    // A NEW live run continues in this session (distinct content → not deduped against the record;
    // later timestamp → sorts after the restored history).
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'live-1', type: 'user', content: 'a fresh follow-up', timestamp: Date.parse('2026-01-01T00:00:10Z') },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const seam = view.shadowRoot!.querySelector('.run-seam');
    expect(seam, 'a resumed thread with a new live run shows the boundary seam').toBeTruthy();
    expect((seam?.textContent ?? '').toLowerCase()).toContain('new run');
    view.remove();
  });

  it('577 #14 — the context-headroom meter persists across an iteration_start (no flicker)', async () => {
    // The defect: projectContextHorizon read budgetUpdates[last]; an iteration_start event carries
    // promptTokens 0, nulling the horizon and hiding the meter between iterations. The fix reads the
    // last update that actually carries occupancy, so the meter persists.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      sessionId: string | null;
      agentCtrl: unknown;
      ensureAgentCtrl: () => unknown;
    };
    v.affordance = 'agent';
    v.sessionId = 'sess-meter';
    const ctrl = v.ensureAgentCtrl() as {
      conversationId: string | null;
      budgetUpdates: unknown[];
    };
    ctrl.conversationId = 'sess-meter'; // ctrlBudgetIsOurs → the rail projects this run's budget
    // A real LLM call carried occupancy, then the next iteration_start carried promptTokens 0.
    ctrl.budgetUpdates = [
      { phase: 'llm_response', tokensConsumed: 100, tokensRemaining: 6000, promptTokens: 2048, contextWindow: 8192 },
      { phase: 'iteration_start', tokensConsumed: 0, tokensRemaining: 6000, promptTokens: 0, contextWindow: 0 },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const meter = view.shadowRoot!.querySelector('[aria-label="Context window used"]');
    expect(meter, 'the context-headroom meter stays visible after an iteration_start').not.toBeNull();
    expect(meter?.getAttribute('aria-valuenow')).toBe('25'); // 2048 / 8192
    view.remove();
  });

  it('577 #19 — NO seam for an all-record thread (nothing live to separate)', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; unifiedEvents: unknown[]; ensureAgentCtrl: () => unknown };
    v.affordance = 'agent';
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'only question', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'only answer', attributes: {} },
    ];
    v.ensureAgentCtrl();
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.run-seam'), 'all-record → no seam').toBeNull();
    view.remove();
  });

  it('577 #19 — NO seam between a question and its own answer (the mid-turn false-positive)', async () => {
    // The defect: when the live ANSWER fails to dedup against the reconciled record answer, the
    // user turn is a record item and the answer is the first live item — keying the seam on that
    // answer drew "resumed · new run" mid-turn. The fix anchors the seam on a live USER turn only,
    // so a live assistant item following a record user turn produces NO seam.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; unifiedEvents: unknown[]; ensureAgentCtrl: () => unknown };
    v.affordance = 'agent';
    // The current turn's USER message is already reconciled into the record (record item).
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'the question', attributes: {} },
    ];
    v.ensureAgentCtrl();
    // The live answer (distinct content → not deduped) sorts AFTER the record user turn.
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'live-ans', type: 'assistant-text', content: 'the live answer with [1] marks', timestamp: Date.parse('2026-01-01T00:00:09Z') },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.run-seam'),
      'a live answer after a record user turn must NOT draw a seam (same turn, not a new run)',
    ).toBeNull();
    view.remove();
  });

  it('565 fix A — a multi-turn record renders one INDEPENDENTLY-collapsible trace per run', async () => {
    const view = mountView();
    await view.updateComplete;
    // Two completed runs in one session: user1 · tool1 · answer1 · user2 · tool2 · answer2.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'turn one', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer one', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:04Z', kind: 'USER_MESSAGE', originator: 'user', content: 'turn two', attributes: {} },
      { id: 't2', occurredAt: '2026-01-01T00:00:05Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c2', toolName: 'core_ingest_files', status: 'completed' } },
      { id: 'a2', occurredAt: '2026-01-01T00:00:06Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer two', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    // ONE trace segment per run; both default-collapsed (every answer has landed — neither is trailing).
    const traces = Array.from(sr.querySelectorAll('details.run-trace'));
    expect(traces.length).toBe(2);
    expect(traces.every((d) => !(d as HTMLDetailsElement).open)).toBe(true);

    // Expanding the FIRST run's trace must NOT expand the second (fix A — per-segment, not shared).
    const firstSummary = traces[0]!.querySelector('summary') as HTMLElement;
    firstSummary.click();
    await view.updateComplete;
    const after = Array.from(sr.querySelectorAll('details.run-trace')) as HTMLDetailsElement[];
    expect(after[0]!.open).toBe(true);
    expect(after[1]!.open).toBe(false);
  });

  it('565 §12.3.E — renders a source-chip row under a grounded answer + cross-highlights via the store', async () => {
    __resetSelectedSource();
    const view = mountView();
    await view.updateComplete;
    const sources = [
      { parentDocId: 'd1', chunkIndex: 0, path: 'docs/a.md', title: 'Doc A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
      { parentDocId: 'd2', chunkIndex: 0, path: 'docs/b.md', title: 'Doc B', excerpt: 'y', startLine: 12, endLine: 14, headingText: '' },
    ];
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: { sources } },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    // §13.8 P3 — the chips are collapsed behind a "Sources · N" disclosure when the wide rail shows
    // the detail; expand it first to assert the chip behavior.
    (sr.querySelector('.source-disclosure-summary') as HTMLElement).click();
    await view.updateComplete;

    // One chip per source, numbered, named.
    const chips = Array.from(sr.querySelectorAll('.source-chips .source-chip'));
    expect(chips.length).toBe(2);
    expect(chips[0]!.textContent).toContain('1');
    expect(chips[0]!.textContent).toContain('Doc A');
    expect(chips[1]!.textContent).toContain('Doc B');

    // External selection (an inline [n] mark / rail card) highlights the matching chip.
    setSelectedSource(sourceKey('d2', 12));
    await view.updateComplete;
    const chip2 = Array.from(sr.querySelectorAll('.source-chip'))[1] as HTMLElement;
    expect(chip2.classList.contains('selected')).toBe(true);
    expect(chip2.getAttribute('aria-current')).toBe('true');

    // A chip click sets the shared selection + dispatches the citation-select deep-link.
    let detail: { parentDocId?: string; startLine?: number } | null = null;
    view.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent).detail;
    });
    (Array.from(sr.querySelectorAll('.source-chip'))[0] as HTMLElement).click();
    expect(getSelectedSource()).toBe(sourceKey('d1', 5));
    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('d1');
    expect(detail!.startLine).toBe(5);
    __resetSelectedSource();
  });

  it('565 §13.8 P3 — the source chips collapse behind a "Sources · N" disclosure; clicking toggles it', async () => {
    __resetSelectedSource();
    const view = mountView();
    await view.updateComplete;
    const sources = [
      { parentDocId: 'd1', chunkIndex: 0, path: 'docs/a.md', title: 'Doc A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
      { parentDocId: 'd2', chunkIndex: 0, path: 'docs/b.md', title: 'Doc B', excerpt: 'y', startLine: 12, endLine: 14, headingText: '' },
    ];
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: { sources } },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    // Collapsed by default (the wide rail owns the detail): the summary is present + a11y-correct,
    // the chip body is not rendered.
    const summary = sr.querySelector('.source-disclosure-summary') as HTMLButtonElement;
    expect(summary).not.toBeNull();
    expect(summary.tagName).toBe('BUTTON'); // keyboard-operable (controls-a11y)
    expect(summary.textContent).toContain('Sources · 2');
    expect(summary.getAttribute('aria-expanded')).toBe('false');
    expect(sr.querySelector('.source-chips')).toBeNull();

    // Click expands: aria-expanded flips, the chip body appears + is wired to the summary.
    summary.click();
    await view.updateComplete;
    expect(summary.getAttribute('aria-expanded')).toBe('true');
    const body = sr.querySelector('.source-chips') as HTMLElement;
    expect(body).not.toBeNull();
    expect(body.id).toBe(summary.getAttribute('aria-controls'));
    expect(body.querySelectorAll('.source-chip').length).toBe(2);

    // Click again collapses.
    summary.click();
    await view.updateComplete;
    expect(summary.getAttribute('aria-expanded')).toBe('false');
    expect(sr.querySelector('.source-chips')).toBeNull();
    __resetSelectedSource();
  });

  it('565 fix D — occurrence-aware dedup: two identical consecutive turns do NOT collapse', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    const ctrl = getAgentSessionController('http://localhost:5173');
    // The live run has TWO identical user turns; the record has only ONE so far (mid-stream).
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'l1', type: 'user', content: 'ok', timestamp: 1 },
      { id: 'l2', type: 'user', content: 'ok', timestamp: 2 },
    ];
    (view as unknown as { agentCtrl: unknown }).agentCtrl = ctrl;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'r1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'ok', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // BOTH "ok" turns render (the record's one + the 2nd live one, not collapsed). Old Set-based dedup
    // would show only ONE. Match the user turn's `.message-body` (577 #19 added an ambient `.turn-time`
    // child, so the whole `.message.user` textContent now also carries the relative time).
    const okTurns = Array.from(sr.querySelectorAll('.message.user .message-body')).filter((m) =>
      (m.textContent ?? '').trim() === 'ok',
    );
    expect(okTurns.length).toBe(2);
    __resetAgentSessionStore();
  });

  it('565 §12.3.D — the left run-spine renders a status node per step + a terminal answer node', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    // Force the wide breakpoint (a MediaQueryList-shaped stub incl. the listener methods the teardown calls).
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const spine = sr.querySelector('.run-spine');
    expect(spine).not.toBeNull();
    // §13 Pillar A — the WHOLE conversation: the user landmark + the tool texture + the answer terminal.
    const nodes = spine!.querySelectorAll('.run-spine-node');
    expect(nodes.length).toBe(3);
    // §19.3 — prominence-graded by the DECLARED scale (PROMINENCE_SCALE / TERMINAL_NODE_WEIGHT) set
    // inline, not a hand-CSS class: the answer is the terminal landmark (0.8rem), the user turn primary
    // (0.62rem), the tool step secondary texture (0.36rem).
    const styleOf = (id: string) =>
      ([...nodes].find((x) => x.getAttribute('data-item-id') === id) as HTMLElement | undefined)?.getAttribute(
        'style',
      ) ?? '';
    expect(styleOf('a1')).toContain('--node-size:0.8rem'); // terminal answer landmark
    expect(styleOf('u1')).toContain('--node-size:0.62rem'); // primary turn
    expect(styleOf('t1')).toContain('--node-size:0.36rem'); // secondary tool texture
    // §19.4 — each node is placed at its conversation scroll fraction (an inline top:%).
    expect([...nodes].every((n) => /top:/.test(n.getAttribute('style') ?? ''))).toBe(true);
    // each node anchors to its timeline item (the scroll-spy / click-jump target).
    expect([...nodes].every((n) => n.getAttribute('data-item-id'))).toBe(true);
    // §13 P2 — the spine is an operable nav (the binding), not aria-hidden decorative; every node is a
    // keyboard-operable button with an accessible name (controls-a11y-clean).
    expect(spine!.tagName.toLowerCase()).toBe('nav');
    expect(spine!.getAttribute('aria-label')).toBeTruthy();
    expect(
      [...nodes].every(
        (n) => n.tagName.toLowerCase() === 'button' && n.getAttribute('aria-label'),
      ),
    ).toBe(true);
  });

  it('565 §13 Pillar A — the spine spans the WHOLE conversation, not just the latest run', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer1', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:03Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:04Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const spine = view.shadowRoot!.querySelector('.run-spine');
    // Pre-§13 (latest-run slice) would show only turn-2's node; the whole-conversation spine shows all 4.
    expect(spine!.querySelectorAll('.run-spine-node').length).toBe(4);
    // both answers carry the data-item-id matching their timeline items (the click-jump targets).
    expect(spine!.querySelector('[data-item-id="a1"]')).not.toBeNull();
    expect(spine!.querySelector('[data-item-id="a2"]')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('565 §13/§19.3 — only the FINAL assistant of a turn is the terminal "Answer"; intermediate assistants recede', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // One turn where the agent loop emits an INTERMEDIATE assistant message (a tool-call preamble) before
    // the final answer, then a second turn — proving the terminal landmark resets on the user boundary.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 'a1mid', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'let me search', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:03Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1final', occurredAt: '2026-01-01T00:00:04Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'the real answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:05Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2final', occurredAt: '2026-01-01T00:00:06Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer two', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const nodes = [...sr.querySelectorAll('.run-spine-node')];
    const nodeOf = (id: string) =>
      nodes.find((x) => x.getAttribute('data-item-id') === id) as HTMLElement | undefined;
    // The FINAL assistant of each turn is the terminal "Answer" landmark (0.8rem, labelled "Answer").
    expect(nodeOf('a1final')!.getAttribute('style')).toContain('--node-size:0.8rem');
    expect(nodeOf('a1final')!.getAttribute('aria-label')).toBe('Answer');
    expect(nodeOf('a2final')!.getAttribute('style')).toContain('--node-size:0.8rem');
    expect(nodeOf('a2final')!.getAttribute('aria-label')).toBe('Answer');
    // The INTERMEDIATE assistant recedes to secondary texture (0.36rem) and is NOT labelled "Answer".
    expect(nodeOf('a1mid')!.getAttribute('style')).toContain('--node-size:0.36rem');
    expect(nodeOf('a1mid')!.getAttribute('aria-label')).not.toBe('Answer');
    expect(nodeOf('a1mid')!.getAttribute('aria-label')).toBe('Working step');
    __resetAgentSessionStore();
  });

  it('565 §13 Pillar A — clicking a spine node scrolls the reading column to that item + marks it active', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer1', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const target = sr.querySelector('.conversation [data-item-id="a1"]') as HTMLElement;
    expect(target).not.toBeNull();
    let scrolledTo: HTMLElement | null = null;
    target.scrollIntoView = function (this: HTMLElement): void {
      scrolledTo = this;
    } as unknown as typeof target.scrollIntoView;
    const node = sr.querySelector('.run-spine [data-item-id="a1"]') as HTMLButtonElement;
    expect(node).not.toBeNull();
    node.click();
    await view.updateComplete;
    expect(scrolledTo).toBe(target);
    expect(sr.querySelector('.run-spine-node.active[data-item-id="a1"]')).not.toBeNull();
    // The click PINS the ring to the clicked item (so the scroll-spy can't re-point it after scroll).
    expect((view as unknown as { nav: { pinned: string | null } }).nav.pinned).toBe('a1');
    __resetAgentSessionStore();
  });

  it('565 §13 — a click-jump pins the ring; the scroll-spy cannot override it until a user scroll releases the pin', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    (sr.querySelector('.conversation [data-item-id="a1"]') as HTMLElement).scrollIntoView = function (
      this: HTMLElement,
    ): void {} as unknown as typeof HTMLElement.prototype.scrollIntoView;
    // §21 — the pin/focus/release apparatus now lives on the NavigationController (the chat-first
    // reading-position authority): a single live/pinned `intent`, FOCUS DERIVED from window×extents (no
    // IntersectionObserver). The view projects `nav.activeId`/`nav.pinned`.
    const nav = (view as unknown as {
      nav: {
        pinned: string | null;
        activeId: string;
        onUserScroll: () => void;
        landmarks: { id: string; extent: { topFrac: number; botFrac: number } }[];
        viewport: { topFrac: number; botFrac: number } | null;
      };
    }).nav;
    // Click the answer node → the intent pins the focus to it.
    (sr.querySelector('.run-spine [data-item-id="a1"]') as HTMLButtonElement).click();
    await view.updateComplete;
    // Inject a measured reading-state (happy-dom has no real layout) where the DERIVED focus would be the
    // tool step t1 (the reading window sits over it), to prove the pin overrides the live derivation.
    nav.landmarks = [
      { id: 'u1', extent: { topFrac: 0, botFrac: 0.2 } },
      { id: 't1', extent: { topFrac: 0.2, botFrac: 0.5 } },
      { id: 'a1', extent: { topFrac: 0.5, botFrac: 1 } },
    ];
    nav.viewport = { topFrac: 0.25, botFrac: 0.45 }; // window over t1 → deriveFocus would be t1
    expect(nav.pinned).toBe('a1');
    expect(nav.activeId).toBe('a1'); // pinned wins over the derived t1 — the highlight-steal is impossible
    // A genuine user scroll flips the intent to live; FOCUS now tracks the reading window (→ t1).
    nav.onUserScroll();
    expect(nav.pinned).toBeNull();
    expect(nav.activeId).toBe('t1');
    __resetAgentSessionStore();
  });

  it('565 §13 Pillar A — clicking a STEP node opens its collapsed run-trace so the jump lands (regression: scrollIntoView no-ops inside a closed <details>)', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // A completed run (user · tool-step · answer): because the answer has landed, the tool step renders
    // inside a DEFAULT-COLLAPSED <details class="run-trace"> — the ~half of spine nodes the prior review
    // found un-jumpable (scrollIntoView is a no-op on an element inside a closed <details>).
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer1', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const target = sr.querySelector('.conversation [data-item-id="t1"]') as HTMLElement;
    expect(target).not.toBeNull();
    const details = target.closest('details.run-trace') as HTMLDetailsElement;
    expect(details).not.toBeNull();
    expect(details.open).toBe(false); // collapsed → scrollIntoView would no-op without the fix
    let scrolledTo: HTMLElement | null = null;
    target.scrollIntoView = function (this: HTMLElement): void {
      scrolledTo = this;
    } as unknown as typeof target.scrollIntoView;
    const node = sr.querySelector('.run-spine [data-item-id="t1"]') as HTMLButtonElement;
    expect(node).not.toBeNull();
    node.click();
    await view.updateComplete;
    // The fix: nav.jumpTo opens every ancestor <details> BEFORE scrollIntoView, so the step is laid out
    // and the jump lands instead of silently doing nothing.
    expect(details.open).toBe(true);
    expect(scrolledTo).toBe(target);
    expect(sr.querySelector('.run-spine-node.active[data-item-id="t1"]')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('565 fix F + §12.3.D — at the narrow breakpoint the rail AND the run-spine do not mount', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    // 574 F1 — the wide breakpoint now comes from the shared responsiveState authority; simulate
    // narrow (< 64rem) by setting the projected field directly.
    (view as unknown as { wideViewport: boolean }).wideViewport = false;
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      { parentDocId: 'd1', chunkIndex: 0, path: 'docs/a.md', title: 'Doc A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
    ];
    (view as unknown as { agentCtrl: unknown }).agentCtrl = ctrl;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // Neither the docked rail nor the left spine mounts at narrow — one SourcesPane per viewport, and the
    // spine is a wide-only margin element.
    expect(sr.querySelector('jf-sources-pane.evidence-rail')).toBeNull();
    expect(sr.querySelector('.run-spine')).toBeNull();
    __resetAgentSessionStore();
  });

  it('603 D-4: a DOCUMENT-LEVEL agent answer shows the SOURCED provenance verdict, NOT "Grounded · 0 of N"', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent'; // currentShapeId → core.agent-run (grounded-index)
    // A persisted agent answer whose grounding sources are DOCUMENT-LEVEL (chunkIndex/startLine === -1
    // sentinel — the BLOCKED_LEGACY whole-doc case) with NO per-sentence cites. 603 D-1 showed this as
    // "No grounded sources"; the naive D-3 fix would show "Grounded · 0 of N". D-4: the SOURCED state.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: 'The Head process hosts the UI [1]. The Worker owns the index [2].',
        attributes: {
          sources: [
            { parentDocId: 'docs/a.md', chunkIndex: -1, path: 'docs/a.md', title: 'a.md', excerpt: 'x', startLine: -1, endLine: -1, headingText: '' },
            { parentDocId: 'docs/b.md', chunkIndex: -1, path: 'docs/b.md', title: 'b.md', excerpt: 'y', startLine: -1, endLine: -1, headingText: '' },
          ],
          citations: [],
        },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const text = (view.shadowRoot!.textContent ?? '').replace(/\s+/g, ' ');
    // The badge states provenance over the 2 documents…
    expect(text).toContain('Based on 2 documents');
    // …and the frame line is the honest SOURCED header, not the warning ungrounded one.
    expect(text).toContain('per-sentence grounding not verified');
    // The over-confident lie must NOT appear.
    expect(text).not.toContain('Grounded · 0');
    expect(view.shadowRoot!.querySelector('.grounding-badge-sourced')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('renders the projection as the single read-model: reconciled live turns dedupe, in-flight turns overlay (Pillar 2)', async () => {
    const view = mountView();
    await view.updateComplete;
    // The record (GET /api/thread) holds two reconciled turns.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'recorded question', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'recorded answer', attributes: {} },
    ];
    // The live thread still holds the SAME user turn (not yet cleared) PLUS one in-flight user turn
    // the record has not reconciled. The single read-model must render the reconciled turn ONCE
    // (from the projection, deduped out of the overlay) and the in-flight turn via the overlay.
    // (User turns render as plain inline text; the assistant renders via <jf-markdown-block>,
    // so we assert dedup on the reliably-inlined user turns + the record's plain-text assistant.)
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'recorded question', shapeId: 'core.rag-ask' },
      { role: 'user', content: 'in-flight question', shapeId: 'core.rag-ask' },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const text = (view.shadowRoot!.querySelector('.conversation') as HTMLElement).textContent ?? '';
    const count = (needle: string): number => text.split(needle).length - 1;
    // The reconciled user turn appears exactly once (no record-vs-live double render).
    expect(count('recorded question')).toBe(1);
    // The assistant renders once from the record via <jf-markdown-block> (561 P-A: its text is a
    // property, not light-DOM text), so assert on the single element + its .text.
    const assistants = view.shadowRoot!.querySelectorAll(
      '.message.assistant jf-markdown-block',
    );
    expect(assistants.length).toBe(1);
    expect((assistants[0] as unknown as { text: string }).text).toContain('recorded answer');
    // The in-flight turn the record hasn't caught up to still shows (live overlay).
    expect(count('in-flight question')).toBe(1);
  });

  it('the inline agent controller survives a chat↔agent round-trip (lossless, 561 P-B body-unification)', async () => {
    // Regression guard for the context-loss defect: the controller hosted INLINE must NOT be torn down
    // when the user crosses back to chat — its live session survives. Controller identity is the proxy:
    // a stable instance == the controller (and its run) was never destroyed.
    const view = mountView();
    await view.updateComplete;
    const ctrlOf = () => (view as unknown as { agentCtrl: unknown }).agentCtrl;
    // No controller before first entry (no idle cost for chat-only users); never a separate element.
    expect(ctrlOf()).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();

    // Enter the action plane — lazily creates the hosted controller.
    view.affordance = 'agent';
    await view.updateComplete;
    const firstCtrl = ctrlOf();
    expect(firstCtrl).not.toBeNull();

    // Cross back to chat: the controller must NOT be destroyed.
    view.affordance = 'none';
    await view.updateComplete;
    expect(ctrlOf()).toBe(firstCtrl); // same instance == session preserved

    // Re-enter: the very same instance is reused (lossless round-trip end-to-end).
    view.affordance = 'agent';
    await view.updateComplete;
    expect(ctrlOf()).toBe(firstCtrl);
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();
  });

  // Tempdoc 621 Phase 4 (§F.3 regression oracle) — the live/record "prefer fresher evidence" reconciliation
  // is now computed ONCE by the merge authority (`attachLiveMatch` in `mergedTimeline`), not at render time.
  // This pins that invariant: a record assistant turn with a matching evidence-bearing live thread message
  // carries `attributes.live` (the renderer reads it); on reload (no live thread) it does NOT (render from
  // the record). The render outcomes are covered by the live==record + dedup tests above; this guards the
  // SEAM so a future change can't silently reintroduce a render-time cross-source reconciliation.
  it('621 §F.3 — the merge authority attaches the live match (prefer-fresher) once, not at render time', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      unifiedEvents: unknown[];
      thread: unknown[];
      mergedTimeline: () => Array<{ kind: string; content: string; attributes: Record<string, unknown> }>;
    };
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'Q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'A', attributes: {} },
    ];
    // In-session: the live thread holds the same answer WITH fresher evidence (sources).
    v.thread = [
      { role: 'user', content: 'Q', shapeId: 'core.rag-ask', id: 'u1' },
      { role: 'assistant', content: 'A', shapeId: 'core.rag-ask', sources: [{ parentDocId: 'd1', score: 0.9 }] },
    ];
    const merged = v.mergedTimeline();
    const asst = merged.find((it) => it.kind === 'assistant' && it.content === 'A')!;
    const user = merged.find((it) => it.kind === 'user' && it.content === 'Q')!;
    expect(asst.attributes.live, 'evidence-bearing live answer wins via the merge').toBeTruthy();
    expect(user.attributes.live, 'user turn matches the live thread by stable id').toBeTruthy();

    // Reload: the live thread is rebuilt WITHOUT evidence → no live match → render from the record.
    v.thread = [];
    const reloaded = v.mergedTimeline();
    const asstR = reloaded.find((it) => it.kind === 'assistant' && it.content === 'A')!;
    expect(asstR.attributes.live, 'reload renders from the record, not a stale live match').toBeUndefined();
  });

  // Tempdoc 621 Phase 4-full — a RELOADED RAG turn renders through the ONE chat/RAG body (renderMessage),
  // identically to live: it gains the SHAPE TAG (the convergence's user-visible delta) AND keeps the
  // record's citations. This is the full 610 §F.3 "live==record" closure — there is no longer a separate
  // (inline) record render path that can drift from the live one.
  it('621 Phase 4-full — a reloaded RAG turn renders via renderMessage (shape tag + record citations)', async () => {
    const view = mountView();
    await view.updateComplete;
    // The reloaded turn's shape tag/frame come from the window's CURRENT shape (621 Phase 4-full — per-message
    // shape is not persisted), so put the window in Documents mode: a reloaded Document-Q&A turn must read as
    // "Document Q&A", not the placeholder "Chat" the auto-restore seeds the thread with.
    view.affordance = 'documents';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    // The record (reload) holds a RAG answer with persisted citations + claimMatches; the live thread is
    // rebuilt role/content/id/shapeId only (no evidence) — the reload-durability case.
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'Q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'The answer.',
        attributes: {
          citations: [{ parentDocId: 'd1', chunkIndex: 0, score: 0.9, snippet: '', path: 'doc.txt' }],
          claimMatches: { matches: [{ sentenceIndex: 0, sentenceText: 'The answer.', chunkIndex: 0, similarity: 0.9, parentDocId: 'd1' }] },
        },
      },
    ];
    v.thread = [
      { role: 'user', content: 'Q', shapeId: 'core.rag-ask', id: 'u1' },
      { role: 'assistant', content: 'The answer.', shapeId: 'core.rag-ask', id: 'a1' },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const assistant = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"]');
    expect(assistant, 'the reloaded RAG answer renders as a chat assistant turn').not.toBeNull();
    // The convergence's visible delta: the shape tag that the old inline record branch omitted.
    expect(assistant!.querySelector('.message-shape-tag')?.textContent, 'reload gains the shape tag').toContain(
      'Document Q&A',
    );
    // The record's citations still render (no data lost) — the citations panel projects from the record.
    expect(assistant!.querySelector('jf-citations-panel'), 'record citations still render on reload').not.toBeNull();
  });

  // Tempdoc 621 review fix — a reloaded EXTRACT turn keeps its verbatim render (the `transform` frame), not
  // a markdown re-render. Extract carries no per-turn flag on the record, so `isExtract` is derived from the
  // window's current mode in the enrich; this pins that a reloaded extraction renders through the verbatim path.
  it('621 review — a reloaded extract turn renders verbatim (transform frame), not markdown', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'extract';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'Extract the fields', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: '{"name":"X"}', attributes: {} },
    ];
    v.thread = [
      { role: 'user', content: 'Extract the fields', shapeId: 'core.extract', id: 'u1' },
      { role: 'assistant', content: '{"name":"X"}', shapeId: 'core.extract', id: 'a1' },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const block = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"] jf-markdown-block');
    expect(block, 'the reloaded extraction renders an answer block').not.toBeNull();
    expect(block!.getAttribute('frame'), 'reloaded extract uses the verbatim transform frame').toBe('transform');
  });
});

describe('UnifiedChatView abort on conversation switch (Slice 516 FIX-T1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loadConversation aborts the in-flight AbortController', async () => {
    const view = mountView();
    // Simulate a send already in flight by assigning a real AbortController.
    const controller = new AbortController();
    const abortSpy = vi.spyOn(controller, 'abort');
    // @ts-expect-error — touching a private field intentionally for the test.
    view.abortController = controller;
    // @ts-expect-error — call the private method directly.
    await view.loadConversation('uc-other-session', 'core.free-chat');
    expect(abortSpy).toHaveBeenCalledTimes(1);
    // @ts-expect-error — sessionId is private but observable for the test.
    expect(view.sessionId).toBe('uc-other-session');
  });

  it('newConversation aborts the in-flight AbortController', () => {
    const view = mountView();
    const controller = new AbortController();
    const abortSpy = vi.spyOn(controller, 'abort');
    // @ts-expect-error — private field.
    view.abortController = controller;
    // @ts-expect-error — private method.
    view.newConversation();
    expect(abortSpy).toHaveBeenCalledTimes(1);
    expect(view.thread).toEqual([]);
  });

  it('abort is a no-op when no stream is in flight (abortController is null)', () => {
    const view = mountView();
    // @ts-expect-error — private field; start with no controller.
    view.abortController = null;
    // @ts-expect-error — private method; should not throw.
    expect(() => view.newConversation()).not.toThrow();
  });
});

describe('UnifiedChatView mid-stream conversation switch (Slice 517 FIX-U2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('switching conversation mid-stream leaves the new conversation clean (bug-absence)', async () => {
    const view = mountView();
    // Wait for connectedCallback to settle (it awaits a few subscribes).
    await view.updateComplete;

    view.inputDraft = 'first message';
    view.affordance = 'documents';

    // Fire send() — consumeShapeStream is mocked to never resolve, so the
    // stream stays "in flight" indefinitely.
    // @ts-expect-error — private method.
    const sendPromise = view.send();
    // Yield to the microtask queue so send()'s synchronous setup completes
    // (this.abortController = new AbortController(); this.isStreaming = true).
    await new Promise((r) => setTimeout(r, 0));
    expect(view.isStreaming).toBe(true);

    // Mid-stream conversation switch — the bug being verified is that
    // the in-flight stream's onDone does NOT write into the new
    // conversation's thread.
    // @ts-expect-error — private method.
    await view.loadConversation('uc-other-session', 'core.free-chat');

    // The user message was appended during send() at line 1086, but
    // loadConversation resets the thread to the resumed conversation's
    // messages (which the mock returns as []).
    expect(view.thread).toEqual([]);
    // @ts-expect-error — sessionId is private but observable.
    expect(view.sessionId).toBe('uc-other-session');
    // Streaming flag is cleared via the abort's finally block in
    // consumeShapeStream's caller. Since our mock never resolves, the
    // try/finally never runs — but loadConversation's abort + the new
    // conversation's load completed without contamination, which is the
    // bug-absence guarantee.
    // (We don't assert isStreaming here because the mock doesn't simulate
    // the try/finally that would reset it; the abort-controller-aborted
    // signal is what matters.)
    // @ts-expect-error — private field; verify abort signal fired.
    expect(view.abortController?.signal.aborted).toBe(true);

    // Let the stale sendPromise hang — it never resolves, which simulates
    // a real in-flight stream that gets aborted but whose mock keeps the
    // Promise pending. Real fetch would reject with AbortError; our mock
    // simply doesn't resolve. Neither is a test failure since the assertion
    // is bug-absence (no contamination), not streamlifecycle completion.
    void sendPromise;
  });
});

describe('UnifiedChatView answer auto-run (548 §4.5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Drain one-shots + store so each test starts clean.
    takePendingAutoRun();
    takePendingForceShape();
    resetUnifiedChatState();
  });

  it('auto-fires send() once when an answer verb parked the prompt + auto-run flag', async () => {
    // Mirror the IntentRouter `answer` lowering: prompt prefilled in the store,
    // shape forced, auto-run flag parked — all before the surface mounts.
    restoreUnifiedChat({ query: 'what is rust' });
    setPendingForceShape('core.rag-ask');
    setPendingAutoRun(true);

    const view = mountView();
    await view.updateComplete;

    const streamMock = vi.mocked(consumeShapeStream);
    expect(streamMock).toHaveBeenCalledTimes(1);
    // The forced rag-ask shape + prefilled prompt rode the dispatch body
    // (rag-ask carries the text as `question`, not `prompt`).
    const body = streamMock.mock.calls[0]![1] as { shapeId?: string; question?: string };
    expect(body.shapeId).toBe('core.rag-ask');
    expect(body.question).toBe('what is rust');
    // One-shot: the flag is consumed, so no second fire is queued.
    expect(takePendingAutoRun()).toBe(false);
    void view;
  });

  it('does NOT auto-fire on a plain mount (no answer verb)', async () => {
    restoreUnifiedChat({ query: 'hello there' });
    const view = mountView();
    await view.updateComplete;
    expect(vi.mocked(consumeShapeStream)).not.toHaveBeenCalled();
    void view;
  });
});

describe('UnifiedChatView declares sessionId field', () => {
  // Lightweight sanity test — proves the view mounts in the test
  // environment so the abort tests above aren't testing against a
  // broken instance.
  it('mounts with a fresh sessionId', () => {
    const view = mountView();
    // @ts-expect-error — sessionId is private but observable for the test.
    const sid: string = view.sessionId;
    expect(typeof sid).toBe('string');
    expect(sid.startsWith('uc-')).toBe(true);
  });
});

// Tempdoc 565 §33 FIX C — J/K step-nav is a WINDOW-level shortcut (the conversation div is not
// focusable, so a div-scoped @keydown never fired for a keyboard user). The handler must: react to a
// real window keydown, only on 'j'/'k', only in the agent affordance, NEVER while an editable element
// is focused (descending through nested shadow roots), and be removed on disconnect (no leak).
describe('UnifiedChatView §33 — window-level J/K step navigation', () => {
  type NavStub = {
    landmarks: { id: string; extent: { topFrac: number; botFrac: number } }[];
    activeId: string;
    jumpTo: (id: string) => void;
  };
  function mountWithLandmarks(): { view: UnifiedChatView; nav: NavStub } {
    const view = mountView();
    view.affordance = 'agent'; // the handler bails unless affordance==='agent' && wideViewport (default true)
    const nav = (view as unknown as { nav: NavStub }).nav;
    nav.landmarks = [
      { id: 'u1', extent: { topFrac: 0, botFrac: 0.33 } },
      { id: 'a1', extent: { topFrac: 0.33, botFrac: 0.66 } },
      { id: 'u2', extent: { topFrac: 0.66, botFrac: 1 } },
    ];
    return { view, nav };
  }
  // The handler's own index math, mirrored so the expected target is independent of the derived activeId.
  const expectedTarget = (nav: NavStub, dir: 1 | -1): string => {
    const cur = nav.landmarks.findIndex((l) => l.id === nav.activeId);
    const next =
      cur < 0
        ? dir > 0
          ? 0
          : nav.landmarks.length - 1
        : Math.min(nav.landmarks.length - 1, Math.max(0, cur + dir));
    return nav.landmarks[next]!.id;
  };
  const pressWindow = (key: string): void => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  };

  it('a window "j"/"k" keydown jumps the nav forward/back; other keys are ignored', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // jumpTo is mocked → activeId never changes → cur is stable across calls, so j and k from the same
    // state resolve to DIFFERENT landmarks (forward-most vs back-most), proving direction is honored.
    const fwd = expectedTarget(nav, 1);
    const back = expectedTarget(nav, -1);
    expect(fwd).not.toBe(back);

    pressWindow('j');
    expect(jumpTo).toHaveBeenLastCalledWith(fwd);
    pressWindow('k');
    expect(jumpTo).toHaveBeenLastCalledWith(back);
    expect(jumpTo).toHaveBeenCalledTimes(2);

    pressWindow('x'); // a non-nav key must not navigate
    expect(jumpTo).toHaveBeenCalledTimes(2);
  });

  it('never hijacks typing — a focused <input> (light DOM) blocks navigation', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    expect(document.activeElement).toBe(input);
    pressWindow('j');
    expect(jumpTo).not.toHaveBeenCalled();
    input.remove();
  });

  it('never hijacks typing — descends nested shadow roots to the truly-focused editable (the steer input case)', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // Emulate document.activeElement being a shadow HOST whose shadowRoot.activeElement is the real
    // focused <input> (exactly the jf-unified-chat-view → .run-steer__input chain at runtime). The
    // handler must DESCEND to the inner input and bail — the most error-prone part of the guard.
    const innerInput = { tagName: 'INPUT', isContentEditable: false, shadowRoot: null };
    const host = { shadowRoot: { activeElement: innerInput }, tagName: 'JF-UNIFIED-CHAT-VIEW' };
    // Shadow the inherited Document.prototype.activeElement getter with a configurable OWN property, so
    // a plain `delete` restores the original behavior afterwards (we never touch the prototype).
    Object.defineProperty(document, 'activeElement', { configurable: true, get: () => host });
    try {
      pressWindow('j');
      expect(jumpTo).not.toHaveBeenCalled();
    } finally {
      delete (document as unknown as Record<string, unknown>).activeElement;
    }
  });

  it('removes the window listener on disconnect (no leak after the view is gone)', () => {
    const { view, nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    view.remove(); // → disconnectedCallback → removeEventListener('keydown', boundWindowKeydown)
    pressWindow('j');
    expect(jumpTo).not.toHaveBeenCalled();
  });
});

describe('UnifiedChatView retrieve base tier (577 Goal 3 §3.2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    searchListener = null;
  });

  it('exposes a leading Search affordance that selects the retrieve tier', async () => {
    const view = mountView();
    await view.updateComplete;
    const searchBtn = Array.from(
      view.shadowRoot?.querySelectorAll('.affordance-btn') ?? [],
    ).find((b) => b.textContent?.trim() === 'Search');
    expect(searchBtn).toBeDefined();
    (searchBtn as HTMLElement).click();
    await view.updateComplete;
    expect(searchBtn?.classList.contains('active')).toBe(true);
    // The chat thread is replaced by the retrieve prompt (ephemeral, no thread history).
    expect(view.shadowRoot?.querySelector('[data-testid="retrieve-empty-prompt"]')).not.toBeNull();
  });

  it('renders the ephemeral hit-list from the search store, never as thread turns', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    // Push a fabricated quick-pass snapshot (no network).
    searchListener!({
      query: 'invoice',
      results: [
        { id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due', kind: 'markdown' },
        { id: 'h2', title: 'helper.ts', path: '/src/helper.ts', snippet: 'function pay()', kind: 'code' },
      ],
      totalHits: 2,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    const rows = view.shadowRoot?.querySelectorAll('[data-testid="retrieve-result-row"]');
    expect(rows?.length).toBe(2);
    // The hit-list is NOT a chat message — no assistant/user turn was created.
    expect(view.thread.length).toBe(0);
  });

  it('the retrieve tier drives the search store on input/submit, not the LLM send path', async () => {
    const { setQuery, submitSearch } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer).not.toBeNull();
    composer!.dispatchEvent(
      new CustomEvent('composer-input', { detail: { value: 'budget' } }),
    );
    expect(setQuery).toHaveBeenCalledWith('budget');
    composer!.dispatchEvent(new CustomEvent('composer-submit'));
    expect(submitSearch).toHaveBeenCalled();
  });

  it('renders the shared facet chips + per-hit "why" disclosure in the retrieve tier (§3.9a parity)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    searchListener!({
      query: 'invoice',
      results: [
        {
          id: 'h1',
          title: 'Q1 invoice',
          path: '/docs/q1.md',
          snippet: 'total due',
          kind: 'markdown',
          trace: [{ id: 'sparse-retrieval', rank: 1, score: 5.5 }],
        },
      ],
      totalHits: 1,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
      facets: { file_kind: { markdown: 3, pdf: 1 } },
    });
    await view.updateComplete;
    // Facet chips (shared render) appear above the list.
    expect(view.shadowRoot?.querySelector('[data-testid="facet-row"]')).not.toBeNull();
    // Per-hit "Why this result?" disclosure (shared render) appears on the row.
    expect(view.shadowRoot?.querySelector('[data-testid="hit-why"]')).not.toBeNull();
  });

  it('602 R3 — the retrieve row formats the path + highlights query terms like the Search surface', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    const longPath =
      '/Users/alex/Documents/projects/justsearch/modules/ui-web/src/shell-v0/quarterly-report.md';
    searchListener!({
      query: 'invoice',
      results: [
        {
          id: 'h1',
          title: 'Q1 report',
          path: longPath,
          snippet: 'the quarterly invoice total is due',
          kind: 'markdown',
        },
      ],
      totalHits: 1,
      matchCount: 1,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // Path is middle-ellipsis formatted (shared formatDisplayPath) — keeps the filename,
    // drops the middle — not the raw 90-char path; the raw path stays in the title attr.
    const pathEl = sr.querySelector('.retrieve-row-path')!;
    expect(pathEl.textContent).toContain('…');
    expect(pathEl.textContent).toContain('quarterly-report.md');
    expect(pathEl.getAttribute('title')).toBe(longPath);
    // Query term is wrapped in the shared <mark class="hl"> highlight.
    const mark = sr.querySelector('.retrieve-row-snippet mark.hl');
    expect(mark, 'snippet highlights the query term').not.toBeNull();
    expect(mark!.textContent?.toLowerCase()).toBe('invoice');
  });

  // Tempdoc 597 R-1 — the retrieve tier projects the SAME funnel count label as the dedicated
  // Search surface (shared matchCountLabel), reading `matchCount`, never the window `totalHits`.
  const retrieveHit = (id: string) => ({
    id,
    title: id,
    path: `/${id}.md`,
    snippet: '',
    kind: 'markdown' as const,
  });

  it('597 R-1 — shows the matchCount funnel label "Top N of M matches", not window-as-count', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    searchListener!({
      query: 'the',
      results: [retrieveHit('a'), retrieveHit('b')],
      matchCount: 451, // the TRUE matched total → the headline
      totalHits: 110, // the bounded fused-union window → must NOT be the headline
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    const meta = view.shadowRoot?.querySelector('.retrieve-meta')?.textContent ?? '';
    expect(meta).toContain('Top 2 of 451 matches');
    expect(meta).not.toContain('110 result'); // the old window-as-count is gone
  });

  it('597 R-1 — collapses to "M matches" when the whole match set is on screen', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    searchListener!({
      query: 'budget',
      results: [retrieveHit('a'), retrieveHit('b'), retrieveHit('c')],
      matchCount: 3,
      totalHits: 50,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 5,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    const meta = view.shadowRoot?.querySelector('.retrieve-meta')?.textContent ?? '';
    expect(meta).toContain('3 matches');
    expect(meta).not.toContain('50 result');
  });

  it('boots into the retrieve base tier, not free-chat (577 Goal 3 §3.11)', () => {
    // The cold-boot default is the always-available `retrieve` tier (the search entry tier).
    // Checked pre-connect so the AI-capability auto-upgrade (which moves an online window to
    // `documents`) does not mask the constructor default. Offline, this is what the window lands in.
    const view = document.createElement('jf-unified-chat-view') as UnifiedChatView;
    expect(view.affordance).toBe('retrieve');
  });

  it('labels the retrieve submit control "Search", never "Send" or "AI Offline" (577 Goal 3 Fix B)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer?.getAttribute('submit-label')).toBe('Search');
    // The "AI offline" tooltip never applies to the retrieve tier (search needs no chat model).
    expect(composer?.getAttribute('submit-title')).toBe('');
  });

  it('keeps past chats reachable on the retrieve landing, hidden once searching (577 Goal 3 §3.13 / A2)', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      showResumePrompt: boolean;
      recentSession: unknown;
      thread: unknown[];
      unifiedEvents: unknown[];
      searchSnapshot: { query: string } | null;
    };
    // Boot tier with a past session available, no query yet (the bare landing).
    v.affordance = 'retrieve';
    v.showResumePrompt = true;
    v.recentSession = { sessionId: 's-prev', firstMessage: 'past chat', timestamp: 0 };
    v.thread = [];
    v.unifiedEvents = [];
    v.searchSnapshot = null;
    view.requestUpdate();
    await view.updateComplete;
    // The resume card is reachable in the retrieve base tier — past chats viewable even offline.
    expect(
      view.shadowRoot!.querySelector('.resume-prompt'),
      'retrieve landing (no query) shows the resume card',
    ).toBeTruthy();

    // Once a query is active the hit-list owns the zone, so the card steps aside (no clutter).
    v.searchSnapshot = { query: 'invoices' };
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.resume-prompt'),
      'an active retrieve query hides the resume card',
    ).toBeNull();
    view.remove();
  });
});

describe('Tempdoc 610 Phase A — transcript edit/retry controls', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    // The shared mock leaves consumeShapeStream never-resolving (to hold a
    // stream open). The edit/retry flow ends with a re-dispatch (send →
    // consumeShapeStream); let it resolve so `await commitEdit/retryFrom`
    // completes. branchConversation is called BEFORE the re-dispatch, so the
    // assertions on it hold regardless.
    vi.mocked(consumeShapeStream).mockImplementation(() => Promise.resolve());
  });

  function seedThread(view: UnifiedChatView, thread: unknown[]): void {
    const v = view as unknown as { thread: unknown[]; affordance: string; isStreaming: boolean };
    v.affordance = 'documents'; // a chat tier so renderMessage runs
    v.isStreaming = false;
    v.thread = thread;
    view.requestUpdate();
  }

  it('§D.2 — per-turn action bar renders below user + assistant turns with a ⋯ overflow', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'hello', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'hi there', shapeId: 'core.rag-ask', id: 'm1' },
    ]);
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // The user action bar is a sibling row beneath the bubble; the assistant bar
    // sits inside the message box. Both carry the ⋯ overflow trigger.
    const userBar = sr.querySelector('.turn-actions.user-actions');
    expect(userBar, 'user action bar renders').not.toBeNull();
    expect(
      userBar!.querySelector('[aria-label="More message actions"]'),
      'user action bar has the ⋯ overflow',
    ).not.toBeNull();
    const assistantBar = sr.querySelector('.turn-actions.assistant-actions');
    expect(assistantBar, 'assistant action bar renders').not.toBeNull();
    expect(
      assistantBar!.querySelector('[aria-label="More message actions"]'),
      'assistant action bar has the ⋯ overflow',
    ).not.toBeNull();
    view.remove();
  });

  it('§D.2 — primary verbs are visible icons: Edit on user, Copy + Retry on assistant', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'hello', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'hi there', shapeId: 'core.rag-ask', id: 'm1' },
    ]);
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const userBar = sr.querySelector('.turn-actions.user-actions')!;
    // Edit is the user turn's visible defining action; copy/retry are not on it.
    expect(userBar.querySelector('[aria-label="Edit message"]'), 'user has visible Edit').not.toBeNull();
    expect(userBar.querySelector('[aria-label="Retry"]'), 'user Retry lives in ⋯, not inline').toBeNull();
    const assistantBar = sr.querySelector('.turn-actions.assistant-actions')!;
    expect(assistantBar.querySelector('[aria-label="Copy answer"]'), 'assistant has visible Copy').not.toBeNull();
    expect(assistantBar.querySelector('[aria-label="Retry"]'), 'assistant has visible Retry').not.toBeNull();
    expect(assistantBar.querySelector('[aria-label="Edit message"]'), 'assistant has no inline Edit').toBeNull();
    view.remove();
  });

  it('§D.2 — clicking the visible Edit icon morphs the bubble into the edit-in-place textarea', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [{ role: 'user', content: 'first question', shapeId: 'core.rag-ask', id: 'm0' }]);
    await view.updateComplete;
    const editBtn = view.shadowRoot!.querySelector(
      '.turn-actions.user-actions [aria-label="Edit message"]',
    ) as HTMLButtonElement | null;
    expect(editBtn, 'visible Edit icon present').not.toBeNull();
    editBtn!.click();
    await view.updateComplete;
    const textarea = view.shadowRoot!.querySelector('.msg-edit') as HTMLTextAreaElement | null;
    expect(textarea, 'clicking Edit opens the edit-in-place textarea').not.toBeNull();
    expect(textarea!.value).toBe('first question');
    view.remove();
  });

  it('suppresses the ⋯ menu on inherited (parent-branch) turns', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'inherited q', shapeId: 'core.rag-ask', id: 'p0', inheritedFromParent: true },
    ]);
    await view.updateComplete;
    const userMsg = view.shadowRoot!.querySelector('.message.user');
    expect(userMsg, 'inherited user turn renders').not.toBeNull();
    // No controllable action bar on inherited turns (you can only act on your own
    // messages) — so no Edit/⋯ affordances render.
    expect(
      view.shadowRoot!.querySelector('.turn-actions [aria-label="Edit message"]'),
      'inherited turn must NOT offer edit',
    ).toBeNull();
    expect(
      view.shadowRoot!.querySelector('.turn-actions [aria-label="More message actions"]'),
      'inherited turn must NOT offer the ⋯ overflow',
    ).toBeNull();
    view.remove();
  });

  it('swaps a user turn for an editable textarea when editing', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'first question', shapeId: 'core.rag-ask', id: 'm0' },
    ]);
    const v = view as unknown as { editingMessageId: string | null; editingDraft: string };
    v.editingMessageId = 'm0';
    v.editingDraft = 'first question';
    view.requestUpdate();
    await view.updateComplete;
    const textarea = view.shadowRoot!.querySelector('.msg-edit') as HTMLTextAreaElement | null;
    expect(textarea, 'edit-in-place textarea renders for the edited turn').not.toBeNull();
    expect(textarea!.value).toBe('first question');
    expect(
      view.shadowRoot!.querySelector('.msg-edit-save'),
      'Save action renders',
    ).not.toBeNull();
    view.remove();
  });

  it('edit of the first turn branches with the empty-prefix sentinel', async () => {
    const { branchConversation } = await import('../state/conversationListStore.js');
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'q0', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a0', shapeId: 'core.rag-ask', id: 'm1' },
    ]);
    await view.updateComplete;
    // Drive the shared flow directly: editing turn 0 → branch-from-before turn 0,
    // which has no predecessor → empty-prefix sentinel.
    const v = view as unknown as {
      editingMessageId: string | null;
      editingDraft: string;
      commitEdit: (idx: number) => Promise<void>;
    };
    v.editingMessageId = 'm0';
    v.editingDraft = 'q0-edited';
    await v.commitEdit(0);
    expect(branchConversation).toHaveBeenCalledTimes(1);
    const args = (branchConversation as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]!;
    // branchConversation(sessionId, fromMsgId, preview) — fromMsgId is the sentinel.
    expect(args[1]).toBe('__empty_prefix__');
    view.remove();
  });

  it('retry of a non-first answer branches from the preceding message id', async () => {
    const { branchConversation } = await import('../state/conversationListStore.js');
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'q0', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a0', shapeId: 'core.rag-ask', id: 'm1' },
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm2' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm3' },
    ]);
    await view.updateComplete;
    // Retry the second answer (idx 3): prompting user turn is idx 2 (id m2);
    // branch-from-before that → its predecessor id m1.
    const v = view as unknown as { retryFrom: (idx: number) => Promise<void> };
    await v.retryFrom(3);
    expect(branchConversation).toHaveBeenCalledTimes(1);
    const args = (branchConversation as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]!;
    expect(args[1]).toBe('m1');
    view.remove();
  });
});

describe('Tempdoc 610 Phase B — inline version pager', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  function convRow(id: string, opts: Record<string, unknown> = {}): unknown {
    return {
      id,
      title: null,
      createdAt: 0,
      lastActiveAt: 0,
      messageCount: 0,
      firstUserMessage: '',
      shapeId: 'core.rag-ask',
      ...opts,
    };
  }

  it('renders ‹ n/m › on a branch\'s first own turn and reports its version index', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      sessionId: string;
      branchParentId: string | null;
      branchPointId: string | null;
      conversations: unknown[];
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.sessionId = 'B1';
    v.branchParentId = 'P';
    v.branchPointId = 'm1';
    v.conversations = [
      convRow('P'),
      convRow('B1', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 100 }),
      convRow('B2', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 200 }),
    ];
    v.thread = [
      { role: 'user', content: 'inherited q', shapeId: 'core.rag-ask', id: 'm1', inheritedFromParent: true },
      { role: 'user', content: 'edited q', shapeId: 'core.rag-ask', id: 'b1u' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const pager = view.shadowRoot!.querySelector('.version-pager');
    expect(pager, 'pager renders on the branch first-own turn').not.toBeNull();
    // base P = version 1, B1 = version 2, B2 = version 3 → current (B1) is 2/3.
    expect(pager!.querySelector('.ver-count')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('2 / 3');
    view.remove();
  });

  it('renders no pager when a turn has a single version', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      sessionId: string;
      branchParentId: string | null;
      branchPointId: string | null;
      conversations: unknown[];
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.sessionId = 'root';
    v.branchParentId = null;
    v.branchPointId = null;
    v.conversations = [convRow('root')];
    v.thread = [{ role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'm0' }];
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.version-pager')).toBeNull();
    view.remove();
  });
});

describe('Tempdoc 610 Phase C — effective-context floor divider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('renders the floor divider above the floor message and dims messages above it', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      contextFloorId: string | null;
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.thread = [
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm1' },
      { role: 'user', content: 'q2', shapeId: 'core.rag-ask', id: 'm2' },
      { role: 'assistant', content: 'a2', shapeId: 'core.rag-ask', id: 'm3' },
    ];
    v.contextFloorId = 'm2'; // floor at the second question
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    expect(sr.querySelector('.context-floor-divider'), 'floor divider renders').not.toBeNull();
    expect(
      sr.querySelector('.cfd-restore'),
      'the divider carries a Restore control',
    ).not.toBeNull();
    // Messages above the floor (m0, m1) are out-of-context; the floor message
    // (m2) and below are not.
    const outs = sr.querySelectorAll('.message.out-of-context');
    expect(outs.length).toBe(2);
    view.remove();
  });

  it('renders no floor divider when no floor is set', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      contextFloorId: string | null;
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.contextFloorId = null;
    v.thread = [
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm1' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.context-floor-divider')).toBeNull();
    expect(view.shadowRoot!.querySelectorAll('.message.out-of-context').length).toBe(0);
    view.remove();
  });

  it('shows the compacted-variant divider with an expandable summary', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      contextFloorId: string | null;
      contextFloorSummary: string | null;
      showFloorSummary: boolean;
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.thread = [
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm1' },
      { role: 'user', content: 'q2', shapeId: 'core.rag-ask', id: 'm2' },
    ];
    v.contextFloorId = 'm2';
    v.contextFloorSummary = 'Earlier: user asked q1, assistant answered a1.';
    v.showFloorSummary = false;
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const label = sr.querySelector('.context-floor-divider .cfd-label')!.textContent ?? '';
    expect(label).toContain('compacted');
    // Summary hidden until expanded.
    expect(sr.querySelector('.cfd-summary')).toBeNull();
    // Expand.
    v.showFloorSummary = true;
    view.requestUpdate();
    await view.updateComplete;
    const summary = sr.querySelector('.cfd-summary');
    expect(summary, 'expanded summary renders').not.toBeNull();
    expect(summary!.textContent).toContain('Earlier: user asked q1');
    view.remove();
  });
});

describe('UnifiedChatView state retention (tempdoc 609 M1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    document.body.innerHTML = '';
  });

  it('does not reset the chat store (draft + mode) on connect', async () => {
    // A composer draft + mode the user left behind, held in the singleton store.
    restoreUnifiedChat({ query: 'unfinished draft', affordance: 'documents' });

    const view = mountView();
    await view.updateComplete;

    // Pre-609, connectedCallback called resetUnifiedChatState(), wiping this on
    // every navigation. The recoverable draft + mode must now survive the mount.
    expect(getUnifiedChatState().query).toBe('unfinished draft');
    expect(getUnifiedChatState().affordance).toBe('documents');
    view.remove();
  });

  it('clears the store only through the explicit New chat action', async () => {
    restoreUnifiedChat({ query: 'draft', affordance: 'documents' });
    const view = mountView();
    await view.updateComplete;
    expect(getUnifiedChatState().query).toBe('draft');

    // The explicit intent path is the ONE place that empties recoverable state.
    (view as unknown as { newConversation: () => void }).newConversation();
    expect(getUnifiedChatState().query).toBe('');
    expect((view as unknown as { inputDraft: string }).inputDraft).toBe('');
    view.remove();
  });
});

describe('UnifiedChatView last-viewed auto-restore (tempdoc 609 Phase 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    clearLastViewedConversation();
    document.body.innerHTML = '';
  });

  it('auto-restores the conversation this tab was viewing, with no resume card', async () => {
    // The user had this conversation open before navigating away.
    setLastViewedConversation('sess-xyz');

    const view = mountView();
    await view.updateComplete;

    // On return, the thread is reloaded silently (no manual "Continue" click).
    expect(vi.mocked(resumeConversation)).toHaveBeenCalledWith('sess-xyz', expect.any(String));
    expect((view as unknown as { showResumePrompt: boolean }).showResumePrompt).toBe(false);
    view.remove();
  });

  it('does not auto-restore on a cold start (no last-viewed pointer)', async () => {
    const view = mountView();
    await view.updateComplete;

    // Cold landing: the existing resume-card path owns recovery, no thread is reloaded.
    expect(vi.mocked(resumeConversation)).not.toHaveBeenCalled();
    view.remove();
  });

  it('forgets the pointer on New chat so a later return does not auto-restore', async () => {
    setLastViewedConversation('sess-xyz');
    const view = mountView();
    await view.updateComplete;
    vi.mocked(resumeConversation).mockClear();

    (view as unknown as { newConversation: () => void }).newConversation();
    view.remove();

    const view2 = mountView();
    await view2.updateComplete;
    expect(vi.mocked(resumeConversation)).not.toHaveBeenCalled();
    view2.remove();
  });

  it('does NOT re-fetch on reconnect when the retained instance already holds a thread', async () => {
    // Cold mount with a pointer auto-loads once (empty instance → thread.length === 0).
    setLastViewedConversation('sess-xyz');
    const view = mountView();
    await view.updateComplete;
    expect(vi.mocked(resumeConversation)).toHaveBeenCalledTimes(1);

    // Under instance-retention a same-session return reuses THIS instance with its thread intact.
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'kept', shapeId: 'core.free-chat' },
    ];
    vi.mocked(resumeConversation).mockClear();

    view.disconnectedCallback();
    view.connectedCallback();
    await view.updateComplete;

    // The `thread.length === 0` guard skips the auto-load, so no blank-then-reload flicker (§K.2).
    expect(vi.mocked(resumeConversation)).not.toHaveBeenCalled();
    expect((view as unknown as { thread: unknown[] }).thread.length).toBe(1);
    view.remove();
  });
});

describe('UnifiedChatView activity truthfulness on disconnect (tempdoc 609 Phase 4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    clearLastViewedConversation();
    document.body.innerHTML = '';
  });

  // Investigation (609 §P Phase 4) confirmed the global activity signal is driven ONLY by the
  // plain-stream send() path; the `agent` affordance delegates to the shared controller and never
  // sets `isStreaming`/`setAiActivity`. These tests LOCK that truthfulness invariant: leaving a
  // continuing run must not force the indicator idle; an aborted plain stream correctly does.

  it('does not force the activity indicator idle when no plain stream is in flight', async () => {
    const view = mountView();
    await view.updateComplete;
    // An agent run continues server-side and never sets `isStreaming` — the view is not streaming.
    (view as unknown as { isStreaming: boolean }).isStreaming = false;
    vi.mocked(setAiActivity).mockClear();

    view.remove(); // disconnect

    expect(vi.mocked(setAiActivity)).not.toHaveBeenCalled();
  });

  it('idles the indicator when a plain stream is genuinely aborted on disconnect', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { isStreaming: boolean }).isStreaming = true;
    vi.mocked(setAiActivity).mockClear();

    view.remove(); // disconnect aborts the in-flight plain stream → idle is truthful

    expect(vi.mocked(setAiActivity)).toHaveBeenCalledWith(
      expect.objectContaining({ state: 'idle' }),
    );
  });
});

describe('UnifiedChatView settle transients on hide (tempdoc 609 instance-retention)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    clearLastViewedConversation();
    document.body.innerHTML = '';
  });

  it('resets in-flight/partial transient state on disconnect but KEEPS the thread + draft', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      isStreaming: boolean;
      streamingText: string;
      errorMessage: string;
      thread: unknown[];
      inputDraft: string;
    };
    // Simulate a stream in flight with a populated thread + a typed draft (the stale-spinner setup).
    v.isStreaming = true;
    v.streamingText = 'half an answer';
    v.errorMessage = 'transient error';
    v.thread = [{ role: 'user', content: 'kept turn', shapeId: 'core.free-chat' }];
    v.inputDraft = 'a draft I am keeping';

    // Navigate away (the Stage retains the instance; JfElement.disconnectedCallback fires settle).
    view.disconnectedCallback();

    // Transient state settled — no stale "thinking" spinner / partial answer / stale error on return.
    expect(v.isStreaming).toBe(false);
    expect(v.streamingText).toBe('');
    expect(v.errorMessage).toBe('');
    // Recoverable state survives the hide (instance-retention's whole point).
    expect(v.thread.length).toBe(1);
    expect(v.inputDraft).toBe('a draft I am keeping');
    view.remove();
  });
});

describe('UnifiedChatView context-budget meter (610 §E.4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  const withWindow = (view: UnifiedChatView, contextWindow: number): void => {
    view.aiState = {
      ...AI_STATE_READY,
      runtime: {
        mode: 'online',
        modelId: 'm',
        modelLabel: 'M',
        contextWindow,
        gpu: null,
        installed: true,
        installing: false,
        loadStartedAtMs: null,
      },
    } as unknown as UnifiedChatView['aiState'];
  };

  it('renders the meter when occupancy + window are known, hides it when occupancy is absent', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    await view.updateComplete;
    const meter = view.shadowRoot?.querySelector('.context-meter [role="meter"]');
    expect(meter).not.toBeNull();
    expect(meter?.getAttribute('aria-valuenow')).toBe('25');

    view.contextPromptTokens = null;
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.context-meter')).toBeNull();
    view.remove();
  });

  it('hides the meter in agent mode (the activity rail owns headroom there)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.context-meter')).toBeNull();
    view.remove();
  });

  it('renders the per-phase attribution breakdown when contextBreakdown is present (610 §I.2)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    view.contextBreakdown = { system: 120, conversation: 450, retrieved: 454 };
    await view.updateComplete;
    const breakdown = view.shadowRoot?.querySelector('.context-meter-breakdown');
    expect(breakdown).not.toBeNull();
    expect(breakdown?.textContent).toContain('system ~120');
    expect(breakdown?.textContent).toContain('documents ~454');
    expect(breakdown?.textContent).toContain('estimated');
    // No breakdown element when the split is absent.
    view.contextBreakdown = null;
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.context-meter-breakdown')).toBeNull();
    view.remove();
  });

  it('the meter label opens the inspector, whose view projects the in-context turns + sources (610 §K)', async () => {
    const {
      isContextInspectorOpen,
      __resetContextInspectorDrawer,
    } = await import('../state/contextInspectorDrawer.js');
    __resetContextInspectorDrawer();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    view.contextBreakdown = { system: 120, conversation: 450, retrieved: 454 };
    view.thread = [
      { role: 'user', content: 'q1', id: 'm-1', shapeId: 'core.free-chat' },
      {
        role: 'assistant',
        content: 'a1',
        id: 'm-2',
        shapeId: 'core.free-chat',
        sources: [
          {
            parentDocId: 'C:/docs/taxes.md',
            chunkIndex: 2,
            chunkTotal: 5,
            startChar: 0,
            endChar: 10,
            score: 0.9,
            excerpt: 'the budget report',
            startLine: 42,
            endLine: 48,
            headingText: 'Budget',
            headingLevel: 2,
          },
        ],
      },
    ] as never;
    await view.updateComplete;

    // The meter label is the trigger.
    const trigger = view.shadowRoot?.querySelector('.context-meter-trigger') as HTMLButtonElement;
    expect(trigger).not.toBeNull();
    trigger.click();
    expect(isContextInspectorOpen()).toBe(true);

    // The projection: system from the breakdown, the conversation turns, the document source.
    const v = (view as unknown as { buildInspectorView(): import('../components/ContextInspectorPane.js').InspectorView }).buildInspectorView();
    expect(v.systemTokens).toBe(120);
    expect(v.totalTokens).toBe(1024);
    expect(v.windowTokens).toBe(4096);
    const conv = v.phases.find((p) => p.name === 'Conversation');
    const docs = v.phases.find((p) => p.name === 'Documents');
    expect(conv?.segments.length).toBe(2);
    expect(docs?.segments.length).toBe(1);
    expect(docs?.segments[0]?.label).toBe('Budget');
    __resetContextInspectorDrawer();
    view.remove();
  });
});

describe('UnifiedChatView editable compaction summary (610 §E.2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('commits an in-place summary edit: persists via the store and updates the floor summary', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-test-edit';
    view.contextFloorId = 'm-2';
    view.contextFloorSummary = 'old summary';
    view.editingFloorSummary = true;
    view.floorSummaryDraft = 'corrected summary';
    await (
      view as unknown as { commitFloorSummaryEdit(): Promise<void> }
    ).commitFloorSummaryEdit();
    const { editContextFloorSummary } = await import('../state/conversationListStore.js');
    expect(editContextFloorSummary).toHaveBeenCalledWith('uc-test-edit', 'corrected summary');
    expect(view.contextFloorSummary).toBe('corrected summary');
    expect(view.editingFloorSummary).toBe(false);
    view.remove();
  });

  it('renders Edit on the expanded compaction summary and opens an editable textarea', async () => {
    const view = mountView();
    await view.updateComplete;
    view.thread = [
      { role: 'user', content: 'q', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a', id: 'm-2', shapeId: 'core.free-chat' },
    ] as never;
    view.contextFloorId = 'm-2';
    view.contextFloorSummary = 'a summary';
    view.showFloorSummary = true;
    await view.updateComplete;
    const editBtn = Array.from(
      view.shadowRoot?.querySelectorAll('.context-floor-divider .cfd-restore') ?? [],
    ).find((b) => b.textContent?.trim() === 'Edit') as HTMLButtonElement | undefined;
    expect(editBtn).not.toBeUndefined();
    editBtn!.click();
    await view.updateComplete;
    const textarea = view.shadowRoot?.querySelector('.cfd-summary-input') as
      | HTMLTextAreaElement
      | null;
    expect(textarea).not.toBeNull();
    expect(textarea?.value).toBe('a summary');
    view.remove();
  });
});

describe('UnifiedChatView per-message exclude (610 §E.3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('toggles a message excluded: persists via the store, tracks the id, and dims the turn', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-test-ex';
    view.thread = [
      { role: 'user', content: 'q', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a', id: 'm-2', shapeId: 'core.free-chat' },
    ] as never;
    await view.updateComplete;
    const toggle = (view as unknown as { toggleMessageExcluded(i: number): Promise<void> })
      .toggleMessageExcluded;
    await toggle.call(view, 0);
    const { setMessageExcluded } = await import('../state/conversationListStore.js');
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-ex', 'm-1', true);
    expect(view.excludedMessageIds.has('m-1')).toBe(true);
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.message.excluded')).not.toBeNull();

    await toggle.call(view, 0);
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-ex', 'm-1', false);
    expect(view.excludedMessageIds.has('m-1')).toBe(false);
    view.remove();
  });

  it('shows the "N hidden · Include all" aggregate and includeAll re-includes in bulk (610 §I.2)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    (view as unknown as { sessionId: string }).sessionId = 'uc-test-agg';
    view.thread = [
      { role: 'user', content: 'q1', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a1', id: 'm-2', shapeId: 'core.free-chat' },
    ] as never;
    view.excludedMessageIds = new Set(['m-1', 'm-2']);
    await view.updateComplete;
    expect(
      view.shadowRoot?.querySelector('.excluded-summary-label')?.textContent,
    ).toContain('2 turns hidden');
    await (view as unknown as { includeAll(): Promise<void> }).includeAll.call(view);
    const { setMessageExcluded } = await import('../state/conversationListStore.js');
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-agg', 'm-1', false);
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-agg', 'm-2', false);
    expect(view.excludedMessageIds.size).toBe(0);
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.excluded-summary')).toBeNull();
    view.remove();
  });

  it('toggles a retrieved source excluded: persists via the store + tracks the unit-sep key (610 §J.3)', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-src-ex';
    const source = {
      parentDocId: 'C:/docs/x.md',
      chunkIndex: 2,
      path: 'C:/docs/x.md',
      title: 'x.md',
      excerpt: '',
      startLine: 1,
      endLine: 5,
      headingText: '',
    };
    const key = `C:/docs/x.md${String.fromCharCode(0x1f)}2`;
    const toggle = (
      view as unknown as { toggleSourceExcluded(s: unknown): Promise<void> }
    ).toggleSourceExcluded;

    const { getExcludedSources, __resetExcludedSources } = await import(
      '../state/excludedSources.js'
    );
    __resetExcludedSources();

    await toggle.call(view, source);
    const { setSourceExcluded } = await import('../state/conversationListStore.js');
    expect(setSourceExcluded).toHaveBeenCalledWith('uc-src-ex', key, true);
    expect(getExcludedSources().has(key)).toBe(true);

    await toggle.call(view, source);
    expect(setSourceExcluded).toHaveBeenCalledWith('uc-src-ex', key, false);
    expect(getExcludedSources().has(key)).toBe(false);
    view.remove();
  });

  it('floorFrameParts is the single authority for divider + dim-class (610 §F.3 frame parity)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.thread = [
      { role: 'user', content: 'q1', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a1', id: 'm-2', shapeId: 'core.free-chat' },
      { role: 'user', content: 'q2', id: 'm-3', shapeId: 'core.free-chat' },
    ] as never;
    view.contextFloorId = 'm-3'; // floor at idx 2
    view.excludedMessageIds = new Set(['m-1']);
    await view.updateComplete;
    const fp = (
      view as unknown as {
        floorFrameParts(
          id: string | undefined,
          idx: number,
        ): { divider: unknown; cls: string };
      }
    ).floorFrameParts.bind(view);
    // Above the floor AND individually excluded → both dim classes, no divider.
    const above = fp('m-1', 0);
    expect(above.cls).toBe(' out-of-context excluded');
    expect(typeof above.divider).toBe('symbol'); // lit `nothing`
    // The floor turn itself → the divider renders, no dim class.
    const atFloor = fp('m-3', 2);
    expect(atFloor.cls).toBe('');
    expect(typeof atFloor.divider).toBe('object'); // a TemplateResult
    view.remove();
  });
});
