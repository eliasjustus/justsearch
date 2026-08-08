// @vitest-environment happy-dom

/**
 * The answer projection + lock laws of the Search v2 window (tempdoc 818 slice 2).
 *
 * This is the `execution-surface` register's guard for `sv2-window`: it pins that the window
 * PROJECTS the evidence it is given and authors none of it — the frozen record's retrieval mode
 * comes from `SearchTrace.effectiveMode`, the grounding line from the backend's
 * `sentencesMatched/sentencesTotal`, the citations from the `RetrievalCitation` set — plus the four
 * laws slice 2 makes testable:
 *
 *  - **L4** a landed answer fills its own slot and leaves every earlier record untouched.
 *  - **L5** the dispatched `docIds` ARE the frozen set.
 *  - **L6** every number on screen came from the payload that measured it; an unmeasured turn shows
 *    nothing rather than a zero.
 *  - **L9** a 423 runs ONE refusal handler on every send path, the draft is never swallowed, and the
 *    refusal names its exits.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SearchV2View } from './SearchV2View.js';
import type { AskOutcome, AskRequest, AskSink } from './askClient.js';

interface LiveSearchFixture {
  query: string;
  results: Array<{ id: string; title: string; path: string; snippet?: string }>;
  totalHits: number;
  matchCount: number;
  facetsTruncated: boolean;
  isSearching: boolean;
  processingTimeMs: number | null;
  error: string | null;
  searchTrace: { effectiveMode?: string } | null;
  passStage: 'quick' | 'refined' | null;
}

const SEARCH: LiveSearchFixture = {
  query: 'northfield renewal',
  results: [
    { id: 'd0', title: 'Northfield supplier agreement.pdf', path: 'Contracts/Northfield.pdf' },
    { id: 'd1', title: 'Q2 vendor review notes.md', path: 'Ops/Reviews/Q2.md' },
    { id: 'd2', title: 'RE: revised payment terms.eml', path: 'Archive/Mail/2025-03.eml' },
  ],
  totalHits: 5,
  matchCount: 12,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 42,
  error: null,
  searchTrace: { effectiveMode: 'HYBRID' },
  passStage: 'refined',
};

const OUTCOME: AskOutcome = {
  text: 'Payment terms moved to net-45 in the Northfield renewal.',
  claims: [],
  citations: [
    {
      sentenceIndex: 0,
      sentenceText: 'Payment terms moved to net-45 in the Northfield renewal.',
      chunkIndex: 0,
      similarity: 0.77,
      parentDocId: 'Contracts/Northfield.pdf',
    },
  ],
  sources: [
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
  retrievalMode: 'HYBRID',
  chunksUsed: 4,
  grounding: { sentencesMatched: 3, sentencesTotal: 5 },
  promptTokens: 2048,
};

let searchListener: ((s: LiveSearchFixture) => void) | null = null;
let aiListener: ((s: unknown) => void) | null = null;
const submitSearchMock = vi.fn();

/** What `askDocuments` should do when the view calls it. Each test scripts its own terminal. */
let askImpl: (req: AskRequest, sink: AskSink) => Promise<void> = async () => {};
const askDocumentsMock = vi.fn((req: AskRequest, sink: AskSink) => askImpl(req, sink));

vi.mock('./askClient.js', () => ({
  ASK_SHAPE_ID: 'core.rag-ask',
  askDocuments: (req: AskRequest, sink: AskSink) => askDocumentsMock(req, sink),
}));

vi.mock('../../state/searchState.js', () => ({
  subscribeSearch: vi.fn((listener: (s: LiveSearchFixture) => void) => {
    searchListener = listener;
    return () => {
      searchListener = null;
    };
  }),
  setQuery: vi.fn(),
  submitSearch: () => submitSearchMock(),
  subscribeScopeChips: vi.fn((listener: (c: unknown[]) => void) => {
    listener([]);
    return () => {};
  }),
  addScopeChip: vi.fn(),
  removeScopeChip: vi.fn(),
  clearScopeChips: vi.fn(),
  recordOpenDisposition: vi.fn(),
}));

vi.mock('../../state/searchFiltersState.js', () => ({
  subscribeFacetSelections: vi.fn((listener: (sel: Record<string, string[]>) => void) => {
    listener({});
    return () => {};
  }),
  toggleFacetValue: vi.fn(),
}));

vi.mock('../../state/conversationListStore.js', () => ({
  loadConversations: vi.fn(async () => {}),
  createConversationId: () => 'sv2-test-session',
  subscribeConversationList: vi.fn(
    (listener: (s: { conversations: unknown[]; activeId: null; loading: boolean }) => void) => {
      listener({ conversations: [], activeId: null, loading: false });
      return () => {};
    },
  ),
}));

// The ask tier hosts no run: `peek` returns null, so nothing here depends on the agent controller.
vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: vi.fn(),
  peekAgentSessionController: () => null,
  subscribeAgentSession: vi.fn(() => () => {}),
}));

vi.mock('../../state/aiStateStore.js', () => ({
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    aiListener = listener;
    listener({ status: null, runtime: { contextWindow: null } });
    return () => {
      aiListener = null;
    };
  }),
}));

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-search-v2') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  searchListener?.(SEARCH);
  await el.updateComplete;
  return el;
}

function q(el: Mounted, testid: string): HTMLElement | null {
  return el.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;
}

function text(el: Mounted, testid: string): string {
  return (q(el, testid)?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

async function type(el: Mounted, value: string): Promise<void> {
  const input = q(el, 'draft') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await el.updateComplete;
}

/** Commit via ⇧↩ — one of the two send paths. */
async function commitByEnter(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  const input = q(el, 'draft') as HTMLInputElement;
  input.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true, cancelable: true }),
  );
  await settle(el);
}

/** Commit via the Ask button — the other send path. */
async function commitByButton(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  (q(el, 'commit') as HTMLButtonElement).click();
  await settle(el);
}

/** The dispatch is async; let its microtasks drain, then let Lit render. */
async function settle(el: Mounted): Promise<void> {
  await el.updateComplete;
  await Promise.resolve();
  await Promise.resolve();
  await el.updateComplete;
}

beforeEach(() => {
  document.body.innerHTML = '';
  askDocumentsMock.mockClear();
  submitSearchMock.mockClear();
  askImpl = async () => {};
});

describe('818 SearchV2View — the ask dispatch (L5)', () => {
  it('L5 — the dispatched docIds ARE the frozen set’s hit paths, in order', async () => {
    const el = await mount();
    await commitByEnter(el, 'what changed in the renewal?');

    expect(askDocumentsMock).toHaveBeenCalledTimes(1);
    const req = askDocumentsMock.mock.calls[0]?.[0] as AskRequest;
    expect(req.docIds).toEqual([
      'Contracts/Northfield.pdf',
      'Ops/Reviews/Q2.md',
      'Archive/Mail/2025-03.eml',
    ]);
    expect(req.question).toBe('what changed in the renewal?');
    expect(req.conversationId).toBe('sv2-test-session');
  });

  it('L5 — a later live search does not change what the committed ask was scoped to', async () => {
    const el = await mount();
    await commitByEnter(el, 'what changed?');
    // The live deck moves on entirely…
    searchListener?.({ ...SEARCH, query: 'unrelated', results: [], matchCount: 0 });
    await el.updateComplete;
    // …and the dispatch that already happened still names the frozen three.
    const req = askDocumentsMock.mock.calls[0]?.[0] as AskRequest;
    expect(req.docIds).toHaveLength(3);
  });
});

describe('818 SearchV2View — the landed answer (L4, L6)', () => {
  it('L4 — finalizing fills the slot and leaves the frozen search + turn on screen unchanged', async () => {
    askImpl = async (_req, sink) => sink.onDone(OUTCOME);
    const el = await mount();
    await commitByEnter(el, 'what changed in the renewal?');

    expect(q(el, 'pending-answer')).toBeNull();
    expect(q(el, 'answer')).not.toBeNull();
    expect(text(el, 'answer-text')).toBe(
      'Payment terms moved to net-45 in the Northfield renewal.',
    );
    // The earlier records are still exactly what the commit wrote.
    expect(q(el, 'frozen-block')).not.toBeNull();
    expect(text(el, 'turn')).toBe('what changed in the renewal?');
    expect(text(el, 'session-name')).toBe('northfield renewal');
    // …and the answer FILLED the slot rather than appending: still three records, one cluster.
    expect(text(el, 'index-count')).toBe('3 entries');
  });

  it('L6 — the grounding line is the backend’s measurement, and the citations panel gets its sources', async () => {
    askImpl = async (_req, sink) => sink.onDone(OUTCOME);
    const el = await mount();
    await commitByEnter(el, 'what changed?');

    expect(text(el, 'grounding-line')).toBe('3 of 5 sentences grounded in your files');
    const panel = q(el, 'citations') as (HTMLElement & { sources: unknown[]; citations: unknown[] }) | null;
    expect(panel).not.toBeNull();
    expect(panel?.sources).toHaveLength(1);
    expect(panel?.citations).toHaveLength(1);
  });

  it('L6 — an answer the backend never citation-matched renders NO grounding line', async () => {
    askImpl = async (_req, sink) => sink.onDone({ ...OUTCOME, grounding: null });
    const el = await mount();
    await commitByEnter(el, 'what changed?');

    expect(q(el, 'answer')).not.toBeNull();
    expect(q(el, 'grounding-line')).toBeNull();
  });

  it('L6 — the context meter stays absent until BOTH numbers are real, then reads the projection', async () => {
    askImpl = async (_req, sink) => sink.onDone({ ...OUTCOME, promptTokens: 2048 });
    const el = await mount();
    // No turn yet, no runtime window: nothing honest to show.
    expect(q(el, 'context-meter')).toBeNull();

    await commitByEnter(el, 'what changed?');
    // A turn reported occupancy, but the runtime has not reported a window — still nothing.
    expect(q(el, 'context-meter')).toBeNull();

    aiListener?.({ status: null, runtime: { contextWindow: 8192 } });
    await el.updateComplete;
    // (the window figure is locale-formatted — assert the derived percentage, not the separator)
    expect(text(el, 'context-meter')).toContain('Context 25% of');
    expect(text(el, 'context-meter')).toContain('tokens');
    expect(q(el, 'context-meter')?.getAttribute('data-band')).toBe('green');
  });

  it('a failed stream terminates the slot instead of leaving a permanent “Answer pending”', async () => {
    askImpl = async (_req, sink) => sink.onError('the model went away');
    const el = await mount();
    await commitByEnter(el, 'what changed?');

    expect(q(el, 'pending-answer')).toBeNull();
    expect(q(el, 'refused-answer')?.getAttribute('data-reason')).toBe('error');
  });
});

describe('818 SearchV2View — the session lock (L9)', () => {
  it('L9 — a 423 restores the draft VERBATIM and renders a refusal naming its exits', async () => {
    askImpl = async (_req, sink) => sink.onLocked();
    const el = await mount();
    const draft = '  what changed in the renewal?  ';
    await commitByEnter(el, draft);

    // The text is the user's and nothing else is holding it — back, character for character.
    expect((q(el, 'draft') as HTMLInputElement).value).toBe(draft);
    const refusal = q(el, 'lock-refusal');
    expect(refusal).not.toBeNull();
    expect(refusal?.getAttribute('role')).toBe('alert');
    expect(refusal?.textContent).toContain('encrypted and locked');
    // Both exits, named.
    expect(q(el, 'lock-exit-unlock')?.textContent?.trim()).toBe('Unlock in Security');
    expect(q(el, 'lock-exit-new')).not.toBeNull();
    // The slot terminated as refused — no pending answer left behind.
    expect(q(el, 'pending-answer')).toBeNull();
    expect(q(el, 'refused-answer')?.getAttribute('data-reason')).toBe('locked');
    // The composer stops promising a send it cannot make.
    expect((q(el, 'commit') as HTMLButtonElement).disabled).toBe(true);
  });

  it('L9 — BOTH send paths run the SAME refusal handler (one function, not a per-path branch)', async () => {
    askImpl = async (_req, sink) => sink.onLocked();
    const refuse = vi.spyOn(
      SearchV2View.prototype as unknown as { refuseLocked: (t: string, id: string) => void },
      'refuseLocked',
    );

    const byEnter = await mount();
    await commitByEnter(byEnter, 'first question?');
    expect(refuse).toHaveBeenCalledTimes(1);

    document.body.innerHTML = '';
    const byButton = await mount();
    await commitByButton(byButton, 'second question?');
    expect(refuse).toHaveBeenCalledTimes(2);

    refuse.mockRestore();
  });

  it('L9 — the “new session with this draft” exit keeps the text and drops the records', async () => {
    askImpl = async (_req, sink) => sink.onLocked();
    const el = await mount();
    await commitByEnter(el, 'what changed?');

    (q(el, 'lock-exit-new') as HTMLButtonElement).click();
    await el.updateComplete;

    expect((q(el, 'draft') as HTMLInputElement).value).toBe('what changed?');
    expect(q(el, 'transcript')).toBeNull();
    expect(q(el, 'lock-refusal')).toBeNull();
    expect(text(el, 'session-name')).toBe('New session');
  });

  it('L9 — the lock hint comes from the SAME /api/status field the shipped window reads', async () => {
    const el = await mount();
    expect((q(el, 'commit') as HTMLButtonElement).disabled).toBe(false);

    aiListener?.({
      status: { conversationProtection: { state: 'locked' } },
      runtime: { contextWindow: null },
    });
    await el.updateComplete;
    expect((q(el, 'commit') as HTMLButtonElement).disabled).toBe(true);
    // …and a lock that is gone leaves no stale refusal behind.
    aiListener?.({
      status: { conversationProtection: { state: 'unlocked' } },
      runtime: { contextWindow: null },
    });
    await el.updateComplete;
    expect((q(el, 'commit') as HTMLButtonElement).disabled).toBe(false);
    expect(q(el, 'lock-refusal')).toBeNull();
  });
});
