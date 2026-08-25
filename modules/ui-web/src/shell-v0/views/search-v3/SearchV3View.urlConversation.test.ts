// @vitest-environment happy-dom

/**
 * Search v3 FOLLOWS the conversation in the address — tempdoc 864 Layer 3(a) / PR C, window half.
 *
 * `router/sv3ConversationUrl.test.ts` proves the address round-trips: a claim pushes a history
 * entry, a Back press restores the store. That is only half an undo. The reader is looking at a
 * transcript, and a Back that moved a pointer without moving the screen would be the same defect
 * one layer down — so this asserts the other half against the real window: the store moves, and the
 * conversation on screen moves with it.
 *
 * The store is the REAL one and it is moved from OUTSIDE the window, which is exactly how a
 * popstate reaches it (`NavigationHandler.applyState` → the sv3 adapter's `restore`). A case that
 * clicked a row instead would pass with the whole projection deleted.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import {
  __resetConversationListForTest,
  getConversationListState,
  restoreActiveConversation,
  setActiveConversation,
  subscribeConversationList,
  type ConversationListChange,
} from '../../state/conversationListStore.js';
import {
  __resetBootstrapForTest,
  registerCoreStores,
} from '../../router/bootstrap.js';
import { __resetStoreRegistryForTest } from '../../router/storeRegistry.js';
import { __resetSurfaceSchemasForTest } from '../../router/surfaceSchemas.js';
import {
  __flushPendingWriteForTest,
  activateProjection,
  deactivateProjection,
} from '../../router/URLProjector.js';
import {
  readLastViewedConversation,
  setLastViewedConversation,
} from '../../controllers/lastViewedConversation.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

const conversationRow = (id: string, firstUserMessage: string): Record<string, unknown> => ({
  sessionId: id,
  title: null,
  titleSource: null,
  createdAtMs: 1_700_000_000_000,
  lastActiveAtMs: 1_700_000_000_000,
  firstUserMessage,
  shapeId: 'core.unified-chat',
});

const threadFor = (id: string, answer: string): Record<string, unknown> => ({
  conversationId: id,
  events: [
    {
      id: `${id}-1`,
      occurredAt: '2026-08-25T10:00:00Z',
      kind: 'USER_MESSAGE',
      originator: 'user',
      content: `question in ${id}`,
      attributes: {},
    },
    {
      id: `${id}-2`,
      occurredAt: '2026-08-25T10:00:01Z',
      kind: 'ASSISTANT_MESSAGE',
      originator: 'agent',
      content: answer,
      attributes: {},
    },
  ],
});

function stubFetch(): void {
  fetchMock.mockImplementation(async (url: unknown) => {
    const href = String(url);
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ runs: [] }) };
    }
    if (href.includes('/api/chat/conversations') && href.endsWith('/history')) {
      return { ok: true, status: 200, json: () => Promise.resolve({}) };
    }
    if (href.includes('/api/chat/conversations')) {
      return {
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            sessions: [conversationRow('conv-a', 'about A'), conversationRow('conv-b', 'about B')],
          }),
      };
    }
    if (href.includes('/api/thread/')) {
      const id = decodeURIComponent(href.slice(href.lastIndexOf('/api/thread/') + 12));
      return { ok: true, status: 200, json: () => Promise.resolve(threadFor(id, `answer ${id}`)) };
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

/** The answer text the window is currently rendering, across every turn on screen. */
const transcript = (el: Mounted): string =>
  el.sessions.sessions
    .filter((s) => s.id === el.sessions.activeId)
    .flatMap((s) => s.turns.map((t) => t.answer))
    .join(' ');

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
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

describe('the window follows the conversation in the address (864 PR C)', () => {
  it('a store restore from outside the window swaps the transcript', async () => {
    setActiveConversation('conv-a');
    const el = await mount();
    expect(el.sessions.activeId).toBe('conv-a');
    expect(transcript(el)).toContain('answer conv-a');

    // What a Back press does: the NavigationHandler distributes the address to the sv3 adapter,
    // which restores the store. Nothing touches the window directly.
    restoreActiveConversation('conv-b');
    await settle(el);

    expect(el.sessions.activeId).toBe('conv-b');
    expect(transcript(el)).toContain('answer conv-b');
  });

  it('following the address also moves the per-tab reload pointer', async () => {
    setActiveConversation('conv-a');
    const el = await mount();

    restoreActiveConversation('conv-b');
    await settle(el);

    // Otherwise a Back followed by a reload would land back on the conversation the reader left.
    expect(readLastViewedConversation()).toBe('conv-b');
  });

  it('a restore to no conversation returns the window to the hero', async () => {
    setActiveConversation('conv-a');
    const el = await mount();
    expect(el.sessions.activeId).toBe('conv-a');

    restoreActiveConversation(null);
    await settle(el);

    expect(el.sessions.activeId).toBeNull();
  });

  it('the address outranks the per-tab reload pointer on mount', async () => {
    // A deep link is distributed to the store BEFORE the window mounts, so the pointer must not
    // second-guess it — the reader followed a link to conv-b, not to whatever they read last.
    setLastViewedConversation('conv-a');
    restoreActiveConversation('conv-b');

    const el = await mount();

    expect(el.sessions.activeId).toBe('conv-b');
  });

  it('with nothing in the address the reload pointer still restores, without re-claiming', async () => {
    setLastViewedConversation('conv-a');
    expect(getConversationListState().activeId).toBeNull();

    const el = await mount();

    expect(el.sessions.activeId).toBe('conv-a');
    expect(getConversationListState().activeId).toBe('conv-a');
  });
});

/**
 * Handling a popstate must not WRITE history — independent review of PR #556, finding F1.
 *
 * The first cut had the hero branch call `startFreshSession`, which claims. A claim during popstate
 * handling is indistinguishable to the projector from a fresh one, so it pushed the bare address
 * onto a stack the browser had already walked back through — truncating the forward tail, and
 * Forward silently lost the conversation. It was masked by production listener order and by the
 * `isCurrentUrl` downgrade, neither of which is a guarantee.
 *
 * So the ADVERSE order is what these cases run in: the window subscribes to the store BEFORE the
 * projector's adapter does, which is the order the reviewer reproduced the failure in.
 */
describe('a popstate landing on the hero writes no history (F1)', () => {
  let pushSpy: ReturnType<typeof vi.spyOn>;
  let replaceSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    deactivateProjection();
    registerCoreStores();
    pushSpy = vi.spyOn(window.history, 'pushState').mockImplementation(() => {
      /* swallow */
    });
    replaceSpy = vi.spyOn(window.history, 'replaceState').mockImplementation(() => {
      /* swallow */
    });
  });

  afterEach(() => {
    deactivateProjection();
    pushSpy.mockRestore();
    replaceSpy.mockRestore();
  });

  /** Mount first, project second — the window's store listener lands ahead of the adapter's. */
  async function mountInAdverseOrder(): Promise<Mounted> {
    setActiveConversation('conv-a');
    const el = await mount();
    activateProjection('core.search-v3-surface');
    return el;
  }

  it('adds no history entry when the address returns to the hero', async () => {
    const el = await mountInAdverseOrder();
    expect(el.sessions.activeId).toBe('conv-a');
    const pushesBefore = pushSpy.mock.calls.length;

    // What `NavigationHandler.applyState` does on a Back that lands on the bare surface address.
    restoreActiveConversation(null);
    await settle(el);
    __flushPendingWriteForTest();

    expect(el.sessions.activeId).toBeNull();
    expect(pushSpy.mock.calls.length).toBe(pushesBefore);
    // The URL still has to be corrected to the hero address — the fix removes the ENTRY, not the
    // projection, and a case asserting only "no push" would also pass with the projection dead.
    expect(replaceSpy.mock.calls.at(-1)?.[2]).toBe(
      '#justsearch://surface/core.search-v3-surface',
    );
  });

  it('makes no claim at all while following the address to the hero', async () => {
    // The order-independent form of the case above: no claim is emitted, so no listener ordering
    // can turn one into a push. This is the property; the push assertion is the consequence.
    const el = await mountInAdverseOrder();
    const reasons: ConversationListChange[] = [];
    const unsubscribe = subscribeConversationList((_s, change) => reasons.push(change));
    reasons.length = 0;

    restoreActiveConversation(null);
    await settle(el);
    unsubscribe();

    expect(reasons).not.toContain('claim');
  });
});
