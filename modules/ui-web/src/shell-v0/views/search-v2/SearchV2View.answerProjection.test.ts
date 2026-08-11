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
const setQueryMock = vi.fn();
const addScopeChipMock = vi.fn();

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
  setQuery: (q: string) => setQueryMock(q),
  submitSearch: () => submitSearchMock(),
  subscribeScopeChips: vi.fn((listener: (c: unknown[]) => void) => {
    listener([]);
    return () => {};
  }),
  addScopeChip: (c: unknown) => addScopeChipMock(c),
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

function all(el: Mounted, testid: string): HTMLElement[] {
  return [...(el.shadowRoot?.querySelectorAll(`[data-testid="${testid}"]`) ?? [])] as HTMLElement[];
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
  setQueryMock.mockClear();
  addScopeChipMock.mockClear();
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

  it('slice 5 — following a citation opens the document pane AT the cited range', async () => {
    askImpl = async (_req, sink) => sink.onDone(OUTCOME);
    const el = await mount();
    await commitByEnter(el, 'what changed?');
    expect(q(el, 'reading-pane')).toBeNull();

    // The SHARED panel's own event — this window adds no citation affordance of its own.
    const panel = q(el, 'citations') as HTMLElement;
    panel.dispatchEvent(
      new CustomEvent('citation-select', {
        detail: {
          parentDocId: 'Contracts/Northfield.pdf',
          startLine: 1,
          endLine: 2,
          startChar: 0,
          endChar: 40,
          excerpt: '…net-45…',
        },
        bubbles: true,
        composed: true,
      }),
    );
    await el.updateComplete;

    const pane = q(el, 'reading-pane')?.querySelector('jf-document-pane') as
      | (HTMLElement & { docPath: string; highlightRange: unknown })
      | null;
    expect(pane).not.toBeNull();
    expect(pane?.docPath).toBe('Contracts/Northfield.pdf');
    // The landing IS the range: the strong→settle belongs to the pane's own decay, not to a second
    // emphasis in this window.
    expect(pane?.highlightRange).toEqual({ startLine: 1, endLine: 2 });
  });

  it('slice 5 — opening a whole result carries NO range: there is no passage to emphasise', async () => {
    askImpl = async (_req, sink) => sink.onDone(OUTCOME);
    const el = await mount();
    await commitByEnter(el, 'what changed?');
    const panel = q(el, 'citations') as HTMLElement;
    panel.dispatchEvent(
      new CustomEvent('citation-select', {
        detail: {
          parentDocId: 'Contracts/Northfield.pdf',
          startLine: 1,
          endLine: 2,
          startChar: 0,
          endChar: 40,
          excerpt: '',
        },
        bubbles: true,
        composed: true,
      }),
    );
    await el.updateComplete;

    const card = q(el, 'frozen-block')?.querySelector('jf-results-card') as HTMLElement;
    card.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd1' }, bubbles: true, composed: true }),
    );
    await el.updateComplete;

    const pane = q(el, 'reading-pane')?.querySelector('jf-document-pane') as
      | (HTMLElement & { docPath: string; highlightRange: unknown })
      | null;
    expect(pane?.docPath).toBe('Ops/Reviews/Q2.md');
    expect(pane?.highlightRange).toBeNull();
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
    // L14 — the OCCUPANCY rests (the verdict the meter exists to give); the numbers it was computed
    // from are the elaboration, in the one `.ext` mechanism, inside its `.ext-row`.
    expect(text(el, 'context-meter')).toContain('Context 25% full');
    expect(text(el, 'context-meter')).toContain('tokens');
    const meter = q(el, 'context-meter') as HTMLElement;
    expect(meter.classList.contains('ext-row')).toBe(true);
    const breakdown = meter.querySelector('[data-testid="context-meter-breakdown"]') as HTMLElement;
    expect(breakdown.classList.contains('ext')).toBe(true);
    expect(breakdown.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      `${(2048).toLocaleString()} of ${(8192).toLocaleString()} tokens`,
    );
    // The occupancy itself is NOT inside the extended container.
    expect(meter.classList.contains('ext')).toBe(false);
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
    // The exit that can actually change the outcome, named.
    expect(q(el, 'lock-exit-unlock')?.textContent?.trim()).toBe('Unlock in Security');
    // The slot terminated as refused — no pending answer left behind.
    expect(q(el, 'pending-answer')).toBeNull();
    expect(q(el, 'refused-answer')?.getAttribute('data-reason')).toBe('locked');
    // The composer says it cannot send, and stays operable so it can say so again.
    const commit = q(el, 'commit') as HTMLButtonElement;
    expect(commit.disabled).toBe(false);
    expect(commit.getAttribute('aria-disabled')).toBe('true');
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

  it('L9 — the refusal offers no exit that cannot exit', async () => {
    // This replaces a case that asserted the OLD "New session with this text" exit "keeps the text
    // and drops the records" — and passed, because that is exactly what it did. What it never
    // asserted is whether the user could then SEND, and they could not: `conversations.locked` is
    // the encryption state of the chat store, not a property of one conversation, so the new
    // session was refused identically. The transcript was discarded for nothing and the refusal
    // cleared itself on the way out, leaving no reason on screen (§6c finding 5).
    askImpl = async (_req, sink) => sink.onLocked();
    const el = await mount();
    await commitByEnter(el, 'what changed?');

    expect(q(el, 'lock-refusal')).not.toBeNull();
    expect(q(el, 'lock-exit-new'), 'the exit that changed nothing is gone').toBeNull();
    // The one exit left is the one that addresses the actual cause.
    expect(q(el, 'lock-exit-unlock')).not.toBeNull();
    // And the thing that exit used to be needed for is simply still true: the draft is safe.
    expect((q(el, 'draft') as HTMLInputElement).value).toBe('what changed?');
    expect(q(el, 'transcript'), 'the record of what happened is kept, not discarded').not.toBeNull();
  });

  it('L9 — the lock hint comes from the SAME /api/status field the shipped window reads', async () => {
    const el = await mount();
    // Settle the CAPABILITY first, so the only thing that changes below is the lock. Without this
    // the composed availability is already unavailable for an unrelated reason and the assertion
    // would pass or fail on the wrong fact.
    const healthy = {
      phase: 'ready',
      snapshotLive: true,
      capabilities: { chat: true, rag: true, extract: true, embedding: true },
      runtime: { mode: 'online', contextWindow: 4096 },
      status: null,
      index: {},
    };
    aiListener?.(healthy);
    await el.updateComplete;
    expect(q(el, 'commit')?.getAttribute('aria-disabled')).toBe('false');

    aiListener?.({ ...healthy, status: { conversationProtection: { state: 'locked' } } });
    await el.updateComplete;
    // The hint is the same fact, carried the way a reachable reason has to be carried: the control
    // stays operable and says it is unavailable, rather than going inert with nothing to read.
    expect(q(el, 'commit')?.getAttribute('aria-disabled')).toBe('true');
    expect((q(el, 'commit') as HTMLButtonElement).disabled).toBe(false);
    // …and a lock that is gone leaves no stale refusal behind.
    aiListener?.({ ...healthy, status: { conversationProtection: { state: 'unlocked' } } });
    await el.updateComplete;
    expect(q(el, 'commit')?.getAttribute('aria-disabled')).toBe('false');
    expect(q(el, 'lock-refusal')).toBeNull();
  });
});

/**
 * §6c finding 2 — one ask at a time, and a stale terminal that cannot land.
 *
 * The finding was a chain, not a single slip: `commit()` neither aborted nor refused while an
 * answer streamed, both terminals wrote the same singular `streaming`/`askAbort` fields, and record
 * ids are POSITIONAL and reset with the session — so an ask that outlived its session could fill a
 * slot that by then belonged to a different question. Serialising closes the path; the epoch closes
 * the class, because the reset is what makes ids ambiguous and no amount of serialising changes that.
 */
describe('818 SearchV2View — one ask at a time, and no stale terminal (§6c finding 2)', () => {
  it('a second commit mid-stream is REFUSED, visibly, and never dispatches', async () => {
    // A stream that starts and does not finish.
    askImpl = async () => new Promise<void>(() => {});
    const el = await mount();
    await commitByEnter(el, 'what changed in the renewal?');
    expect(askDocumentsMock).toHaveBeenCalledTimes(1);

    await commitByEnter(el, 'and after that?');

    // The refusal is ON SCREEN — the assertion that distinguishes "refused" from "silently ignored".
    const note = q(el, 'send-refused');
    expect(note, 'the refusal is visible').not.toBeNull();
    expect(note?.textContent).toContain('An answer is still arriving');
    expect(note?.getAttribute('data-rung')).toBe('ask');
    // …no second dispatch…
    expect(askDocumentsMock).toHaveBeenCalledTimes(1);
    // …the draft is not swallowed…
    expect((q(el, 'draft') as HTMLInputElement).value).toBe('and after that?');
    // …and no second slot was opened, so nothing is left pending forever.
    expect(all(el, 'pending-answer')).toHaveLength(1);
  });

  it('the affordance says so before the click — one predicate, both halves', async () => {
    askImpl = async () => new Promise<void>(() => {});
    const el = await mount();
    await commitByEnter(el, 'what changed?');
    const commit = q(el, 'commit') as HTMLButtonElement;
    expect(commit.getAttribute('aria-disabled')).toBe('true');
    // Operable, so activating it can still produce the refusal above rather than a dead click.
    expect(commit.disabled).toBe(false);
  });

  it('a terminal that outlived its session lands nowhere near the new one', async () => {
    // Capture the sink so the stream can be resolved LATE, after the session has moved on.
    // ONLY the first sink: the second commit gets its own, and resolving THAT would just be the new
    // session answering its own question — which proves nothing about a stale terminal.
    const sinks: AskSink[] = [];
    askImpl = async (_req, sink) => {
      sinks.push(sink);
      return new Promise<void>(() => {});
    };
    const el = await mount();
    await commitByEnter(el, 'the first question?');
    expect(q(el, 'pending-answer')).not.toBeNull();

    // New session: the records array is re-indexed from zero, so the old slot id now names a
    // DIFFERENT record — this is exactly the ambiguity that made the finding reachable.
    (q(el, 'rail-back') as HTMLButtonElement).click();
    await settle(el);
    await commitByEnter(el, 'a completely different question?');
    await settle(el);

    // The first stream finally answers, against the id it was minted with.
    sinks[0]?.onDone({ ...OUTCOME, text: 'AN ANSWER TO THE OLD QUESTION' });
    await settle(el);

    expect(text(el, 'transcript')).not.toContain('AN ANSWER TO THE OLD QUESTION');
    expect(q(el, 'pending-answer'), 'the new session keeps its own open slot').not.toBeNull();
  });
});

/** §6c finding 8 — the shared card's affordances are wired, not rendered into the void. */
describe('818 SearchV2View — the results card’s affordances reach a handler', () => {
  it('L4 — "Search again" on a frozen block re-runs the query as a LIVE search', async () => {
    const el = await mount();
    await commitByEnter(el, 'what changed in the renewal?');
    const card = q(el, 'frozen-block')?.querySelector('jf-results-card') as HTMLElement;

    card.dispatchEvent(
      new CustomEvent('card-fork', {
        detail: { query: 'northfield renewal' },
        bubbles: true,
        composed: true,
      }),
    );
    await el.updateComplete;

    expect(setQueryMock).toHaveBeenCalledWith('northfield renewal');
    expect(submitSearchMock).toHaveBeenCalled();
    // L4 — the frozen record is a snapshot: re-running beside it never rewrites it.
    expect(q(el, 'frozen-block')).not.toBeNull();
  });

  it('L3 — the row menu’s "Ask about this file" pins the shared scope chip', async () => {
    const el = await mount();
    const card = q(el, 'live-results')?.querySelector('jf-results-card') as HTMLElement;

    card.dispatchEvent(
      new CustomEvent('card-scope-file', {
        detail: { id: 'd0', path: 'Contracts/Northfield.pdf', title: 'Northfield agreement' },
        bubbles: true,
        composed: true,
      }),
    );
    await el.updateComplete;

    expect(addScopeChipMock).toHaveBeenCalledWith({
      kind: 'file',
      label: 'Northfield agreement',
      docIds: ['Contracts/Northfield.pdf'],
    });
  });
});
