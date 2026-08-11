// @vitest-environment happy-dom

/**
 * Opening a prior session (tempdoc 818 §6i, §6c finding 27) and the meta band's rest state
 * (finding 26).
 *
 * F27 was a deferral this window carried in a comment — "deliberately not a button yet, because
 * opening a prior session is not a thing this window can do until it can load one". These are the
 * witnesses that the deferral is spent: a loaded conversation becomes RECORDS, so every projection
 * reads it as it reads a typed session, and loading is not searching.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { recordsFromThread, projectSessionName, projectTranscript } from './records.js';

const submitSearchMock = vi.fn();
const setQueryMock = vi.fn();
let threadEvents: Array<{ kind: string; content: string; attributes?: Record<string, unknown> }> = [];
const fetchThreadMock = vi.fn(async () => ({ events: threadEvents }));

vi.mock('../unifiedThreadClient.js', () => ({
  fetchUnifiedThread: (...args: unknown[]) => fetchThreadMock(...(args as [])),
}));
vi.mock('../../state/searchState.js', () => ({
  subscribeSearch: vi.fn(() => () => {}),
  setQuery: (q: string) => setQueryMock(q),
  submitSearch: () => submitSearchMock(),
  subscribeScopeChips: vi.fn((l: (c: unknown[]) => void) => {
    l([]);
    return () => {};
  }),
  addScopeChip: vi.fn(),
  removeScopeChip: vi.fn(),
  clearScopeChips: vi.fn(),
  recordOpenDisposition: vi.fn(),
}));
vi.mock('../../state/searchFiltersState.js', () => ({
  subscribeFacetSelections: vi.fn((l: (s: Record<string, string[]>) => void) => {
    l({});
    return () => {};
  }),
  toggleFacetValue: vi.fn(),
}));
vi.mock('../../state/conversationListStore.js', () => ({
  createConversationId: () => 'c-new',
  loadConversations: vi.fn(async () => {}),
  subscribeConversationList: vi.fn((l: (s: { conversations: unknown[] }) => void) => {
    l({
      conversations: [
        {
          id: 'c1',
          title: 'Supplier renewals',
          lastActiveAt: Date.now(),
          messageCount: 4,
          firstUserMessage: '',
        },
      ],
    });
    return () => {};
  }),
}));
vi.mock('../../state/aiStateStore.js', () => ({
  subscribeAiState: vi.fn((l: (s: unknown) => void) => {
    l({
      phase: 'ready',
      snapshotLive: true,
      capabilities: { chat: true, rag: true, extract: true, embedding: true },
      runtime: { mode: 'online', contextWindow: 4096 },
      status: null,
      index: {},
    });
    return () => {};
  }),
}));
vi.mock('../../state/pinnedSearchState.js', () => ({
  subscribePinnedSearches: (l: (p: unknown[]) => void) => {
    l([]);
    return () => {};
  },
}));
vi.mock('./askClient.js', () => ({
  ASK_SHAPE_ID: 'core.rag-ask',
  askDocuments: vi.fn(async () => {}),
}));

await import('./SearchV2View.js');

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-search-v2') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}
function q(el: Mounted, testid: string): HTMLElement | null {
  return el.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;
}
function all(el: Mounted, testid: string): HTMLElement[] {
  return [...(el.shadowRoot?.querySelectorAll(`[data-testid="${testid}"]`) ?? [])] as HTMLElement[];
}
async function settle(el: Mounted): Promise<void> {
  await el.updateComplete;
  await Promise.resolve();
  await Promise.resolve();
  await el.updateComplete;
}

beforeEach(() => {
  document.body.innerHTML = '';
  localStorage.clear();
  submitSearchMock.mockClear();
  setQueryMock.mockClear();
  fetchThreadMock.mockClear();
  threadEvents = [
    { kind: 'USER_MESSAGE', content: 'what changed in the renewal?' },
    { kind: 'ASSISTANT_MESSAGE', content: 'The termination clause moved to 60 days.' },
  ];
});

describe('818 §6i — the records mapping (pure)', () => {
  it('L8 — a loaded conversation BECOMES records, so every projection reads it', () => {
    const records = recordsFromThread(threadEvents);
    expect(records.map((r) => r.kind)).toEqual(['user-turn', 'answer']);
    // The projections are not told this session was loaded; they cannot tell, which is the point.
    expect(projectSessionName(records)).toBe('what changed in the renewal?');
    expect(projectTranscript(records)).toHaveLength(2);
  });

  it('L4/L6 — a loaded SEARCH keeps no rows it cannot prove, and claims no counts', () => {
    const records = recordsFromThread([
      { kind: 'SEARCH', content: '', attributes: { query: 'northfield' } },
    ]);
    const frozen = records[0];
    expect(frozen?.kind).toBe('frozen-search');
    if (frozen?.kind !== 'frozen-search') throw new Error('unreachable: asserted above');
    expect(frozen.query).toBe('northfield');
    // The backend persists docIds, not rows. Synthesising rows would be a fabricated set; the
    // shared card already says "results not stored — run again to see them" for exactly this.
    expect(frozen.hits).toEqual([]);
    expect(frozen.total).toBe(0);
  });

  it('an answer restored without its evidence reports NO grounding, never a measured zero', () => {
    const records = recordsFromThread([{ kind: 'ASSISTANT_MESSAGE', content: 'an answer' }]);
    const item = projectTranscript(records)[0] as { groundedSentencesLabel: string | null };
    expect(item.groundedSentencesLabel).toBeNull();
  });

  it('a shape this window cannot model is NAMED and kept, never dropped', () => {
    const records = recordsFromThread([
      { kind: 'USER_MESSAGE', content: 'a' },
      { kind: 'HANDOFF', content: 'passed to the research agent' },
      { kind: 'TOOL_ACTIVITY', content: 'read 3 files' },
      { kind: 'WHAT_IS_THIS', content: 'from a newer backend' },
    ]);
    expect(records, 'nothing is silently lost').toHaveLength(4);
    const foreign = records.slice(1) as Array<{ kind: string; label: string; text: string }>;
    expect(foreign.map((r) => r.kind)).toEqual(['foreign', 'foreign', 'foreign']);
    expect(foreign.map((r) => r.label)).toEqual([
      'Handoff',
      'Tool activity',
      'Turn from another window',
    ]);
    expect(foreign[0]?.text, 'its words are kept verbatim').toBe('passed to the research agent');
  });
});

describe('818 §6c finding 27 — opening a prior session', () => {
  it('a session row is a real control, and clicking it renders that session’s records', async () => {
    const el = await mount();
    const row = q(el, 'session-row') as HTMLButtonElement;
    expect(row, 'the rail offers the session').not.toBeNull();
    expect(row.tagName, 'and it is a control, not an inert row').toBe('BUTTON');
    expect(row.getAttribute('aria-label')).toContain('Supplier renewals');

    row.click();
    await settle(el);

    expect(fetchThreadMock).toHaveBeenCalledTimes(1);
    // The loaded transcript is on screen, through the ordinary projections.
    expect(q(el, 'transcript')).not.toBeNull();
    expect(all(el, 'turn').map((n) => n.textContent?.trim())).toEqual([
      'what changed in the renewal?',
    ]);
    expect(q(el, 'answer-text')?.textContent?.trim()).toBe(
      'The termination clause moved to 60 days.',
    );
    // …and the rail has flipped to the session index, because records now exist (L8 corollary).
    expect(q(el, 'rail-index')).not.toBeNull();
    expect(q(el, 'session-name')?.textContent?.trim()).toBe('what changed in the renewal?');
  });

  it('loading a session ISSUES NO SEARCH — it is not a query', async () => {
    const el = await mount();
    (q(el, 'session-row') as HTMLButtonElement).click();
    await settle(el);

    expect(submitSearchMock, 'the one search seam is untouched').not.toHaveBeenCalled();
    expect(setQueryMock).not.toHaveBeenCalled();
  });

  it('a loaded session can still be left — New session / back to the list keep working', async () => {
    const el = await mount();
    (q(el, 'session-row') as HTMLButtonElement).click();
    await settle(el);
    expect(q(el, 'rail-index')).not.toBeNull();

    (q(el, 'rail-back') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'transcript'), 'the session was left').toBeNull();
    expect(q(el, 'rail-sidebar'), 'and the list is back').not.toBeNull();
  });

  it('a shape this window cannot model still reaches the screen', async () => {
    threadEvents = [
      { kind: 'USER_MESSAGE', content: 'a question' },
      { kind: 'HANDOFF', content: 'passed to the research agent' },
    ];
    const el = await mount();
    (q(el, 'session-row') as HTMLButtonElement).click();
    await settle(el);

    const foreign = q(el, 'foreign-record');
    expect(foreign, 'the turn is not dropped').not.toBeNull();
    expect(foreign?.textContent).toContain('Handoff');
    expect(foreign?.textContent).toContain('passed to the research agent');
  });
});

describe('818 §6c finding 26 — the meta band rests as one honest line', () => {
  async function card(elaboration: 'always' | 'on-demand'): Promise<HTMLElement> {
    await import('../../components/searchResults/ResultsCard.js');
    const el = document.createElement('jf-results-card') as HTMLElement & {
      updateComplete: Promise<unknown>;
      snapshot: unknown;
      elaboration: string;
    };
    el.elaboration = elaboration;
    el.snapshot = {
      query: 'northfield',
      results: [{ id: 'd0', title: 'A', path: 'a.pdf', snippet: 's' }],
      matchCount: 4,
      totalHits: 1,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 750,
      error: null,
      passStage: 'refined',
      facets: { file_kind: { pdf: 1 } },
    };
    document.body.appendChild(el);
    await el.updateComplete;
    return el;
  }

  function has(el: HTMLElement, testid: string): boolean {
    return el.shadowRoot?.querySelector('[data-testid="' + testid + '"]') !== null;
  }

  it('at REST the honesty facts are present and the elaboration is not', async () => {
    const el = await card('on-demand');
    const meta = el.shadowRoot?.querySelector('[data-testid="card-meta"]');
    expect(meta, 'the count line rests — it is L6 headline and may never hide').not.toBeNull();
    expect(meta?.textContent, 'and it still states the count').toContain('4');

    expect(has(el, 'copy-actions'), 'exports are elaboration you ACT on').toBe(false);
    expect(el.shadowRoot?.querySelector('.facet-chip'), 'so are facet chips').toBeNull();
    expect(has(el, 'card-details-toggle'), 'and one control opens them').toBe(true);
  });

  it('the control opens the band, and the band closes again', async () => {
    const el = await card('on-demand');
    const toggle = el.shadowRoot?.querySelector(
      '[data-testid="card-details-toggle"]',
    ) as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    toggle.click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(has(el, 'copy-actions')).toBe(true);
    expect(el.shadowRoot?.querySelector('.facet-chip')).not.toBeNull();

    (
      el.shadowRoot?.querySelector('[data-testid="card-details-toggle"]') as HTMLButtonElement
    ).click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(has(el, 'copy-actions')).toBe(false);
  });

  it('every OTHER consumer is untouched: the default still renders everything', async () => {
    const el = await card('always');
    expect(has(el, 'copy-actions'), 'the shipped surfaces keep their band').toBe(true);
    expect(el.shadowRoot?.querySelector('.facet-chip')).not.toBeNull();
    expect(has(el, 'card-details-toggle'), 'and gain no control they did not have').toBe(false);
  });
});
