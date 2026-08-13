// @vitest-environment happy-dom

/**
 * Search v3 on the product's RECORD (tempdoc 822 Phase F6 — inventory A1, A3, A4, D1, D2, D3,
 * A13-abort, A14, A15, G11).
 *
 * The shared authorities are the REAL ones — the conversation store, the thread client + projector,
 * `DraftPersistence`, `draftKeptHint`, `aiStateStore` — stubbed only at their single exit, `fetch`,
 * routed by URL. Mocking the store would have made every case here a test of the mock; the point of
 * the slice is that this window stopped being an authority, and that is only observable against the
 * authorities themselves.
 *
 * The properties asserted as MECHANISMS:
 *  - **A reload projects the list back.** The window is unmounted and a FRESH one is mounted — the
 *    in-memory list is genuinely gone, so a case that passed would have to be reading the store.
 *  - **The record wins on settle; the live turn does not.** Both directions, in one case.
 *  - **A failed refresh is said, and says something DIFFERENT from empty.** The two copies are
 *    compared, so wording them the same would fail.
 *  - **A late delta after New session appends nothing.** The stream is left open and fed AFTER the
 *    new-session act — the mutation probe, not the presence of an abort call.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';

let ctrlExists = false;
let agentListener: (() => void) | null = null;
const reattach = vi.fn(async () => {});
const ctrl = {
  conversation: [] as unknown[],
  toolCalls: {},
  streamingText: '',
  isStreaming: false,
  runInFlight: false,
  runKind: 'agent' as const,
  conversationId: null as string | null,
  sessionId: null as string | null,
  iterationsUsed: 0,
  budgetGate: null,
  contextGate: null,
  reattachActiveRunOnLoad: reattach,
};

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => {
    ctrlExists = true;
    return ctrl as unknown as AgentSessionController;
  },
  peekAgentSessionController: () =>
    ctrlExists ? (ctrl as unknown as AgentSessionController) : null,
  subscribeAgentSession: (listener: () => void) => {
    agentListener = listener;
    return () => {
      agentListener = null;
    };
  },
}));

import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
  getAiState,
  setAiActivity,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __draftStorageKey, __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import { MAIN_EMPTY, RECORD_UNREACHABLE, SV3_DRAFT_KEY } from './fixtures.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };
/** Any Lit element inside the window — a region, a row. Not the window itself. */
type Updatable = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** What the fake backend currently holds, per exit. Cases set these before mounting. */
interface Backend {
  conversations: Array<Record<string, unknown>>;
  threads: Record<string, { conversationId: string; events: unknown[] } | 'fail'>;
}
let backend: Backend;

/** The open dispatch stream, so a case can feed a delta at a moment of its choosing. */
interface Stream {
  emit(event: string, data: unknown): void;
  end(): void;
}
let stream: Stream;

function stubFetch(): void {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  stream = {
    emit: (event, data) =>
      push({
        done: false,
        value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
      }),
    end: () => push({ done: true }),
  };
  fetchMock.mockImplementation(async (url: unknown, init: { signal?: AbortSignal } = {}) => {
    const href = String(url);
    if (href.includes('/api/chat/dispatch')) {
      const signal = init.signal ?? null;
      return {
        ok: true,
        status: 200,
        body: {
          getReader: () => ({
            read: async () => {
              while (queued.length === 0) {
                if (signal?.aborted === true) throw new Error('The operation was aborted.');
                await new Promise<void>((resolve) => {
                  wake = resolve;
                  signal?.addEventListener('abort', () => resolve(), { once: true });
                });
              }
              return queued.shift();
            },
            releaseLock: () => {},
          }),
        },
      };
    }
    if (href.includes('/api/chat/conversations')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ sessions: backend.conversations }) };
    }
    if (href.includes('/api/thread/')) {
      const id = decodeURIComponent(href.slice(href.lastIndexOf('/api/thread/') + '/api/thread/'.length));
      const record = backend.threads[id];
      if (record === undefined || record === 'fail') return { ok: false, status: 500 };
      return { ok: true, status: 200, json: () => Promise.resolve(record) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ results: [] }) };
  });
}

function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

let clock = 0;
const wireEvent = (
  id: string,
  kind: string,
  content: string,
  attributes: Record<string, unknown> = {},
): Record<string, unknown> => ({
  id,
  occurredAt: new Date(Date.parse('2026-08-13T10:00:00Z') + clock++ * 1000).toISOString(),
  kind,
  originator: 'agent',
  content,
  attributes,
});

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  ctrlExists = false;
  agentListener = null;
  reattach.mockClear();
  Object.assign(ctrl, {
    conversation: [],
    isStreaming: false,
    runInFlight: false,
    conversationId: null,
    sessionId: null,
  });
  clock = 0;
  backend = { conversations: [], threads: {} };
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  await settle(el);
  return el;
}

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function region(el: Mounted, tag: string): Promise<Updatable> {
  const found = el.shadowRoot?.querySelector(tag) as Updatable | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

async function type(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
}

async function send(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  const composer = await region(el, 'jf-sv3-composer');
  composer.shadowRoot
    ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
    ?.click();
  await settle(el);
}

/** Ask and let the answer land, so the conversation comes to rest. */
async function ask(el: Mounted, draft: string): Promise<void> {
  await send(el, draft);
  stream.emit('chunk', { text: 'An answer.' });
  stream.emit('done', {});
  stream.end();
  await settle(el);
}

const rowLabels = async (el: Mounted): Promise<string[]> => {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])] as Updatable[];
  await Promise.all(rows.map((r) => r.updateComplete));
  return rows.map((r) => r.shadowRoot?.querySelector('.row-label')?.textContent?.trim() ?? '');
};

const q = (el: Updatable, id: string): HTMLElement | null =>
  el.shadowRoot?.querySelector<HTMLElement>(`[data-testid="${id}"]`) ?? null;


describe('a v3 session IS a conversation in the app-wide store (A1 + A4)', () => {
  it('mints the conversation through the store and stamps the dispatch with it', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    // The store's own id format — this window mints none of its own any more.
    expect(id.startsWith('uc-')).toBe(true);
    const dispatch = fetchMock.mock.calls.find((c) => String(c[0]).includes('/api/chat/dispatch'));
    expect(JSON.parse((dispatch?.[1] as { body: string }).body).conversationId).toBe(id);
  });

  it('projects the same conversations back into a FRESH window — the reload round-trip', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    expect(await rowLabels(el)).toEqual(['why did the renewal fail?']);

    // The backend now holds the conversation the window opened...
    backend.conversations = [
      {
        sessionId: id,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: 'why did the renewal fail?',
        shapeId: 'core.rag-ask',
      },
    ];
    // ...and the window is DESTROYED, so its in-memory list is genuinely gone.
    el.remove();
    const reloaded = await mount();
    expect(reloaded.sessions.sessions.map((s) => s.id)).toEqual([id]);
    expect(await rowLabels(reloaded)).toEqual(['why did the renewal fail?']);
  });

  it('writes a rename THROUGH to the store, so it survives the window', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    el.shadowRoot
      ?.querySelector('jf-sv3-sidebar')
      ?.dispatchEvent(
        new CustomEvent('sv3-session-rename', {
          detail: { id, phase: 'commit', title: 'Lease terms' },
          bubbles: true,
          composed: true,
        }),
      );
    await settle(el);
    backend.conversations = [
      {
        sessionId: id,
        createdAtMs: 1,
        lastActiveAtMs: 2,
        messageCount: 2,
        firstUserMessage: 'why did the renewal fail?',
        shapeId: 'core.rag-ask',
      },
    ];
    el.remove();
    const reloaded = await mount();
    // The store persists titles; the reloaded window shows the chosen one, not the question.
    expect(await rowLabels(reloaded)).toEqual(['Lease terms']);
  });
});

describe('the transcript projects from the canonical record (D1)', () => {
  const conversationRow = (id: string, first: string): Record<string, unknown> => ({
    sessionId: id,
    createdAtMs: 1,
    lastActiveAtMs: 2,
    messageCount: 4,
    firstUserMessage: first,
    shapeId: 'core.rag-ask',
  });

  it('renders chat turns and agent activity INTERLEAVED, in the record’s order', async () => {
    backend.conversations = [conversationRow('uc-rec', 'index the vendor folder')];
    backend.threads['uc-rec'] = {
      conversationId: 'uc-rec',
      events: [
        wireEvent('e1', 'USER_MESSAGE', 'index the vendor folder'),
        wireEvent('e2', 'ASSISTANT_MESSAGE', 'Looking first.'),
        wireEvent('e3', 'TOOL_ACTIVITY', 'core_search', {
          callId: 'c1',
          toolName: 'core_search',
          arguments: '{}',
          risk: 'low',
          status: 'completed',
        }),
        wireEvent('e4', 'ASSISTANT_MESSAGE', 'Indexed.'),
      ],
    };
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn')?.dataset.kind).toBe('agent');
    const feed = q(main, 'sv3-record-activity');
    expect(feed).not.toBeNull();
    // ONE ordered sequence — a projection that grouped prose and tools separately would read
    // text/text/tool here instead.
    const kinds = [...(feed?.children ?? [])].map((child) =>
      child.getAttribute('data-testid') ?? child.tagName.toLowerCase(),
    );
    expect(kinds).toEqual(['sv3-run-text', 'sv3-run-tool', 'sv3-run-text']);
  });

  it('lets the LIVE turn stand while it streams, and yields to the record when it settles', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    // The record already disagrees with the live turn — and must NOT win while it streams.
    backend.threads[id] = {
      conversationId: id,
      events: [
        wireEvent('r1', 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent('r2', 'ASSISTANT_MESSAGE', 'The record’s answer.'),
      ],
    };
    stream.emit('chunk', { text: 'The live answer.' });
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-markdown')?.getAttribute('text') ?? el.sessions.sessions[0]?.turns[0]?.answer)
      .toContain('The live answer.');
    expect(el.sessions.sessions[0]?.turns[0]?.id).not.toBe('r1');

    stream.emit('done', {});
    stream.end();
    await settle(el);
    // Settled: the record is the authority, and the turn takes the record's own id (A4).
    expect(el.sessions.sessions[0]?.turns[0]?.answer).toBe('The record’s answer.');
    expect(el.sessions.sessions[0]?.turns[0]?.id).toBe('r1');
  });

  it('says a failed refresh out loud, in words that are NOT the empty state’s (D2)', async () => {
    backend.conversations = [conversationRow('uc-broken', 'a conversation')];
    backend.threads['uc-broken'] = 'fail';
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);

    const main = await region(el, 'jf-sv3-main');
    const notice = q(main, 'sv3-record-notice');
    expect(notice).not.toBeNull();
    expect(notice?.textContent).toContain(RECORD_UNREACHABLE.title);
    // Distinct from "nothing matched" — a record that could not be read says nothing about content.
    expect(RECORD_UNREACHABLE.title).not.toBe(MAIN_EMPTY.title);
    expect(notice?.textContent).not.toContain(MAIN_EMPTY.title);
    expect(q(main, 'sv3-main-empty')).toBeNull();
  });

  it('does not raise the notice when the record simply has nothing in it', async () => {
    backend.conversations = [conversationRow('uc-fresh', 'a conversation')];
    backend.threads['uc-fresh'] = { conversationId: 'uc-fresh', events: [] };
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as Updatable;
    row.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-session-row-button"]')?.click();
    await settle(el);
    expect(q(await region(el, 'jf-sv3-main'), 'sv3-record-notice')).toBeNull();
  });
});

describe('a reload restores the thread THIS TAB was reading (A3)', () => {
  it('claims the per-tab conversation on a fresh mount and fetches its record', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const id = el.sessions.sessions[0]?.id ?? '';
    backend.threads[id] = {
      conversationId: id,
      events: [
        wireEvent('r1', 'USER_MESSAGE', 'why did the renewal fail?'),
        wireEvent('r2', 'ASSISTANT_MESSAGE', 'The lock held.'),
      ],
    };
    el.remove();

    // A fresh instance with an EMPTY store list: the only thing that can restore the thread is the
    // per-tab pointer, so this cannot pass by accident through the conversation list.
    backend.conversations = [];
    const reloaded = await mount();
    expect(reloaded.sessions.activeId).toBe(id);
    const main = await region(reloaded, 'jf-sv3-main');
    expect(q(main, 'sv3-turn-question')?.textContent?.trim()).toBe('why did the renewal fail?');
  });

  it('forgets the pointer on New session, so the next load lands cold', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot
      ?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')
      ?.click();
    await settle(el);
    el.remove();

    backend.conversations = [];
    const reloaded = await mount();
    expect(reloaded.sessions.activeId).toBeNull();
    expect(reloaded.sessions.sessions).toHaveLength(0);
  });
});

describe('a cold load re-attaches to a live run (D3)', () => {
  it('asks the shared controller to reattach when the cross-tab pointer says a run is live', async () => {
    localStorage.setItem(
      'justsearch.activeAgentRun.v1',
      JSON.stringify({ sessionId: 'run-7', runKind: 'agent' }),
    );
    // The controller comes back attached to the live run, exactly as a real reattach leaves it.
    Object.assign(ctrl, {
      runInFlight: true,
      sessionId: 'run-7',
      conversation: [{ id: 'u1', type: 'user', content: 'reindex the archive', timestamp: 0 }],
    });
    const el = await mount();
    expect(reattach).toHaveBeenCalledTimes(1);
    // ...and PRESENCE turns that into something the reader can see: a session, on the Active shelf.
    expect(el.sessions.sessions.map((s) => s.title)).toEqual(['reindex the archive']);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(
      [...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? [])]
        .map((g) => g.textContent?.trim()),
    ).toContain('Active');
  });

  it('starts NO controller at all when no run is pointed at — a mounted window does not poll', async () => {
    const el = await mount();
    expect(reattach).not.toHaveBeenCalled();
    expect(ctrlExists).toBe(false);
    expect(el.sessions.sessions).toHaveLength(0);
  });
});

describe('New session detaches from everything in flight (A13, abort half)', () => {
  it('appends NOTHING to the fresh thread when a late delta arrives', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    const firstId = el.sessions.sessions[0]?.id ?? '';
    stream.emit('chunk', { text: 'early text' });
    await settle(el);

    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')?.click();
    await settle(el);
    expect(el.sessions.activeId).toBeNull();

    // The ABORT is the mechanism, and it is verified as one rather than assumed: the parked turn has
    // already reached its `halted` terminal, so the stream is torn down and the turn is closed to
    // writes. (Dropping `abortAsk()` from `onSessionNew` fails right here, and then again below.)
    const parkedNow = el.sessions.sessions.find((s) => s.id === firstId);
    expect(parkedNow?.turns[0]?.status).toBe('halted');

    // THE PROBE: emit anyway — a delta AND an evidence event, the two writes an ask makes. Neither
    // may reach anything: not the parked turn, and not the fresh conversation.
    stream.emit('chunk', { text: ' LATE TEXT' });
    stream.emit('rag.citations', {
      citations: [{ parentDocId: 'f:/late.md', chunkIndex: 0, excerpt: 'late' }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    const parked = el.sessions.sessions.find((s) => s.id === firstId);
    expect(parked?.turns[0]?.answer).toBe('early text');
    expect(parked?.turns[0]?.answer).not.toContain('LATE TEXT');
    expect(parked?.turns[0]?.evidence).toBeNull();
    // The fresh conversation is untouched: nothing was created and the window is back at the hero.
    expect(el.sessions.sessions).toHaveLength(1);
    expect(el.getAttribute('composer-state')).toBe('hero');
  });

  it('DETACHES from a live run without halting it', async () => {
    const el = await mount();
    Object.assign(ctrl, {
      runInFlight: true,
      sessionId: 'run-9',
      conversation: [{ id: 'u1', type: 'user', content: 'audit the folder', timestamp: 0 }],
    });
    ctrlExists = true;
    agentListener?.();
    await settle(el);
    expect(el.sessions.sessions).toHaveLength(1);

    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-sidebar-new"]')?.click();
    await settle(el);

    // The run keeps running — this window merely stopped rendering it. Halting a product-wide run
    // because one dev window opened a new conversation would be this window deciding for everyone.
    expect(ctrl.runInFlight).toBe(true);
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-send')).not.toBeNull();
    expect(q(composer, 'sv3-composer-stop')).toBeNull();
  });
});

describe('the composer draft and the global activity indicator (A14, A15, G11)', () => {
  it('persists the draft under this window’s OWN key and restores it on a fresh mount', async () => {
    const el = await mount();
    await type(el, 'a half-written question');
    el.remove();
    // The shared controller flushes on host-disconnect; the key is v3's own, never the shipped one's.
    expect(localStorage.getItem(__draftStorageKey(SV3_DRAFT_KEY))).toBe('a half-written question');
    expect(localStorage.getItem(__draftStorageKey('unified-chat.composer'))).toBeNull();

    const reloaded = await mount();
    expect((await fieldOf(reloaded)).value).toBe('a half-written question');
  });

  it('says "Draft kept" ONCE when leaving with a draft, and not at all without one', async () => {
    const toasts: string[] = [];
    const handler = (event: Event): void => {
      toasts.push((event as CustomEvent<{ message: string }>).detail.message);
    };
    document.addEventListener(EPHEMERAL_TOAST_EVENT, handler);
    try {
      const empty = await mount();
      empty.remove();
      expect(toasts).toEqual([]);

      const first = await mount();
      await type(first, 'unsent thought');
      first.remove();
      expect(toasts).toEqual(['Draft kept']);

      // Teach, don't nag: a second leave with a draft is silent.
      const second = await mount();
      await type(second, 'another unsent thought');
      second.remove();
      expect(toasts).toEqual(['Draft kept']);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, handler);
    }
  });

  it('settles the app-wide activity indicator when a stream is torn down (G11)', async () => {
    const el = await mount();
    await send(el, 'why did the renewal fail?');
    stream.emit('chunk', { text: 'partial' });
    await settle(el);
    // Raised for the duration, with a working cancel — so the settle below is settling something.
    expect(getAiState().activity.state).toBe('streaming');
    expect(getAiState().activity.canCancel).toBe(true);

    el.remove();
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(getAiState().activity.state).toBe('idle');
    expect(getAiState().activity.shapeId).toBeNull();
  });

  it('settles it at the stream’s own terminal too, not only on teardown', async () => {
    const el = await mount();
    await ask(el, 'why did the renewal fail?');
    expect(getAiState().activity.state).toBe('idle');
  });

  it('leaves the indicator alone when this window was never streaming', async () => {
    setAiActivity({ state: 'streaming', shapeId: 'core.free-chat', startedAtMs: 1 });
    const el = await mount();
    el.remove();
    await new Promise<void>((r) => setTimeout(r, 0));
    // Another surface's activity is not this window's to settle on its way out.
    expect(getAiState().activity.shapeId).toBe('core.free-chat');
  });
});
