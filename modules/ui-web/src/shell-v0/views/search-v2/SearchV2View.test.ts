// @vitest-environment happy-dom

/**
 * The window laws (tempdoc 818 slices 1-2) — L1 (the one-shot flip), L3 (a scope chip narrows,
 * changes nothing), L6 (derived counts), L10 (an empty draft submits nowhere), L12 (the rail flips
 * wholesale at the first committed record), and the commit choreography's data result (transcript +
 * name + index all move together because they are projections of one array).
 *
 * The shared stores are mocked at the module boundary: this surface must consume `searchState`'s
 * one issuance seam, never post a search of its own, so the test asserts on the seam's mutators.
 * `askClient` is mocked too — its own stream behaviour is covered by `askClient.test.ts`, and the
 * answer/lock laws by `SearchV2View.answerProjection.test.ts`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import './SearchV2View.js';

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

const SEARCH_EMPTY: LiveSearchFixture = {
  query: '',
  results: [],
  totalHits: 0,
  matchCount: 0,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: null,
  error: null,
  searchTrace: null,
  passStage: null,
};

const SEARCH_WITH_RESULTS: LiveSearchFixture = {
  query: 'northfield renewal',
  results: [
    { id: 'd0', title: 'Northfield supplier agreement.pdf', path: 'Contracts/Northfield.pdf' },
    { id: 'd1', title: 'Q2 vendor review notes.md', path: 'Ops/Reviews/Q2.md' },
    { id: 'd2', title: 'RE: revised payment terms.eml', path: 'Archive/Mail/2025-03.eml' },
  ],
  // The retrieval window (diagnostic) and the TRUE matched population deliberately disagree, so a
  // headline reading `totalHits` would be visibly wrong (L6).
  totalHits: 5,
  matchCount: 12,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 42,
  error: null,
  searchTrace: { effectiveMode: 'HYBRID' },
  passStage: 'refined',
};

let searchListener: ((s: LiveSearchFixture) => void) | null = null;
let chipListener: ((chips: Array<{ kind: string; label: string; docIds: string[] }>) => void) | null =
  null;
let chips: Array<{ kind: string; label: string; docIds: string[] }> = [];
const setQueryMock = vi.fn();
const submitSearchMock = vi.fn();
const addScopeChipMock = vi.fn();
let conversations: Array<{
  id: string;
  title: string | null;
  firstUserMessage: string;
  lastActiveAt: number;
  messageCount: number;
}> = [];

vi.mock('../../state/searchState.js', () => ({
  subscribeSearch: vi.fn((listener: (s: LiveSearchFixture) => void) => {
    searchListener = listener;
    listener(SEARCH_EMPTY);
    return () => {
      searchListener = null;
    };
  }),
  setQuery: (q: string) => setQueryMock(q),
  submitSearch: () => submitSearchMock(),
  subscribeScopeChips: vi.fn(
    (listener: (c: Array<{ kind: string; label: string; docIds: string[] }>) => void) => {
      chipListener = listener;
      listener(chips);
      return () => {
        chipListener = null;
      };
    },
  ),
  addScopeChip: (chip: { kind: string; label: string; docIds: string[] }) => {
    addScopeChipMock(chip);
    chips = [...chips, chip];
    chipListener?.(chips);
  },
  removeScopeChip: (i: number) => {
    chips = chips.filter((_, idx) => idx !== i);
    chipListener?.(chips);
  },
  clearScopeChips: () => {
    chips = [];
    chipListener?.(chips);
  },
  // Consumed by <jf-results-card> (the ONE open-disposition signal), not by this surface.
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
      listener({ conversations, activeId: null, loading: false });
      return () => {};
    },
  ),
}));

vi.mock('../../state/aiStateStore.js', () => ({
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    listener({ status: null, runtime: { contextWindow: null } });
    return () => {};
  }),
}));

vi.mock('./askClient.js', () => ({
  ASK_SHAPE_ID: 'core.rag-ask',
  askDocuments: vi.fn(async () => {}),
}));

// No agent run exists in these cases: `peek` returns null, so the routing context's `runInFlight`
// is false and the alt slot stays ASK/SEARCH. The run laws live in SearchV2View.agentRun.test.ts.
vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: vi.fn(),
  peekAgentSessionController: () => null,
  subscribeAgentSession: vi.fn(() => () => {}),
}));

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

function text(el: Mounted, testid: string): string {
  return (q(el, testid)?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/** The results card is a nested custom element — its rendered header/rows live in ITS shadow root. */
async function card(el: Mounted, hostTestId: string): Promise<Mounted> {
  const host = q(el, hostTestId);
  const c = host?.querySelector('jf-results-card') as Mounted | null;
  if (!c) throw new Error(`no <jf-results-card> under [data-testid="${hostTestId}"]`);
  await c.updateComplete;
  return c;
}

function cardText(c: Mounted, testid: string): string {
  return (
    c.shadowRoot?.querySelector(`[data-testid="${testid}"]`)?.textContent ?? ''
  )
    .replace(/\s+/g, ' ')
    .trim();
}

function cardRows(c: Mounted): HTMLElement[] {
  return [...(c.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]') ?? [])] as HTMLElement[];
}

async function type(el: Mounted, value: string): Promise<void> {
  const input = q(el, 'draft') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await el.updateComplete;
}

async function key(el: Mounted, init: KeyboardEventInit): Promise<void> {
  const input = q(el, 'draft') as HTMLInputElement;
  input.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
  await el.updateComplete;
}

async function live(el: Mounted, fixture: LiveSearchFixture): Promise<void> {
  searchListener?.(fixture);
  await el.updateComplete;
}

beforeEach(() => {
  document.body.innerHTML = '';
  setQueryMock.mockClear();
  submitSearchMock.mockClear();
  addScopeChipMock.mockClear();
  conversations = [];
  chips = [];
});

describe('818 SearchV2View — the rail (L12)', () => {
  it('L12 — with no committed record the rail is the sessions sidebar; a commit flips it to the index', async () => {
    conversations = [
      {
        id: 's1',
        title: 'Supplier renewals',
        firstUserMessage: 'ignored when a title exists',
        lastActiveAt: Date.now(),
        messageCount: 4,
      },
      {
        id: 's2',
        title: null,
        firstUserMessage: 'What did we renegotiate',
        lastActiveAt: Date.now(),
        messageCount: 1,
      },
    ];
    const el = await mount();

    expect(q(el, 'rail-sidebar')).not.toBeNull();
    expect(q(el, 'rail-index')).toBeNull();
    expect(
      all(el, 'session-row').map((r) => (r.textContent ?? '').replace(/\s+/g, ' ').trim()),
    ).toEqual(['Supplier renewals 4 messages', 'What did we renegotiate 1 message']);
    // L6 — the row meta is the message count of the row it sits on, never an authored figure.
    expect(all(el, 'session-row-meta').map((m) => m.textContent?.trim())).toEqual([
      '4 messages',
      '1 message',
    ]);
    // The sidebar's New session affordance is always present — never state-gated.
    expect(q(el, 'new-session')).not.toBeNull();

    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed?');
    await key(el, { key: 'Enter', shiftKey: true });

    expect(q(el, 'rail-sidebar')).toBeNull();
    expect(q(el, 'rail-index')).not.toBeNull();
    // L6 — the index header count is Σ of the nodes it heads (one commit = 3 records).
    expect(text(el, 'index-count')).toBe('3 entries');
    expect(all(el, 'index-node')).toHaveLength(1);

    // …and the flip is reversible wholesale, never item-by-item.
    (q(el, 'rail-back') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'rail-sidebar')).not.toBeNull();
    expect(q(el, 'rail-index')).toBeNull();
  });
});

describe('818 SearchV2View — the input band (L1, L10)', () => {
  it('L1 — ⇥ flips the pill for this draft only, and Escape restores the derived destination', async () => {
    const el = await mount();
    await type(el, 'northfield supplier agreement');
    expect(text(el, 'pill')).toContain('SEARCH');
    expect(text(el, 'pill-alt')).toContain('ASK');

    await key(el, { key: 'Tab' });
    expect(text(el, 'pill')).toContain('ASK');
    expect(text(el, 'pill')).toContain('⇥');
    expect(text(el, 'pill-alt')).toContain('SEARCH');

    await key(el, { key: 'Escape' });
    expect(text(el, 'pill')).toContain('SEARCH');
    expect(text(el, 'pill')).not.toContain('⇥ ');
    expect(text(el, 'pill-alt')).toContain('ASK');
  });

  it('L1 — the flip dies with the draft: a new draft re-derives its destination unflipped', async () => {
    const el = await mount();
    await type(el, 'northfield supplier agreement');
    await key(el, { key: 'Tab' });
    expect(text(el, 'pill')).toContain('ASK');

    await live(el, SEARCH_WITH_RESULTS);
    await key(el, { key: 'Enter', shiftKey: true }); // commit clears the draft…
    await type(el, 'another plain search');
    expect(text(el, 'pill')).toContain('SEARCH'); // …and the lens did not survive it.
  });

  it('L10 — an empty draft previews a DIMMED pill and submits nowhere', async () => {
    const el = await mount();
    expect(q(el, 'pill')?.getAttribute('data-dimmed')).toBe('true');
    expect(q(el, 'pill')?.classList.contains('off')).toBe(true);
    expect(text(el, 'pill')).toContain('SEARCH');

    await key(el, { key: 'Enter' });
    expect(submitSearchMock).not.toHaveBeenCalled();

    await key(el, { key: 'Enter', shiftKey: true });
    expect(q(el, 'transcript')).toBeNull();
    expect(text(el, 'session-name')).toBe('New session');

    // A real draft undims the pill and reaches the ONE issuance seam.
    await type(el, 'northfield');
    expect(q(el, 'pill')?.getAttribute('data-dimmed')).toBe('false');
    expect(setQueryMock).toHaveBeenCalledWith('northfield');
    await key(el, { key: 'Enter' });
    expect(submitSearchMock).toHaveBeenCalledTimes(1);
  });
});

describe('818 SearchV2View — the live deck (L6)', () => {
  it('L6 — the headline count derives from the store’s true matchCount, not the retrieval window', async () => {
    const el = await mount();
    // Nothing searched yet: the ONE results card renders nothing rather than "0 results".
    expect(q(el, 'live-results')?.querySelector('jf-results-card')).not.toBeNull();

    await live(el, SEARCH_WITH_RESULTS);
    const c = await card(el, 'live-results');
    // The card's meta line is the headline — projected through the shared `matchCountLabel`, so
    // this window has no count label of its own to drift.
    expect(cardText(c, 'card-meta')).toContain('Top 3 of 12 matches');
    expect(cardText(c, 'card-meta')).not.toContain('5');
    expect(cardRows(c)).toHaveLength(3);
    // The commit affordance names the set it would freeze.
    expect(text(el, 'commit')).toBe('Ask about these 3');
  });
});

describe('818 SearchV2View — the commit', () => {
  it('freezes the live set into the transcript, lands the turn, and names the session', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed in the renewal?');
    await key(el, { key: 'Enter', shiftKey: true });

    expect(q(el, 'transcript')).not.toBeNull();
    expect(all(el, 'frozen-block')).toHaveLength(1);
    const frozen = await card(el, 'frozen-block');
    // The frozen header is the card's provenance line, derived from the record's captured counts.
    expect(cardText(frozen, 'card-provenance')).toContain('northfield renewal');
    expect(cardText(frozen, 'card-provenance')).toContain('Top 3 of 12 matches');
    expect(cardRows(frozen).length).toBeGreaterThan(0);
    expect(text(el, 'turn')).toBe('what changed in the renewal?');
    expect(q(el, 'pending-answer')).not.toBeNull();

    // L8 corollary — the name appears at the first commit, off the projection.
    expect(text(el, 'session-name')).toBe('northfield renewal');
    // The draft is consumed, not swallowed into a second model.
    expect((q(el, 'draft') as HTMLInputElement).value).toBe('');
  });

  it('L4/L5 — the frozen block keeps the captured set when the live search moves on', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed?');
    await key(el, { key: 'Enter', shiftKey: true });

    await live(el, { ...SEARCH_EMPTY, query: 'a totally different search', matchCount: 999 });

    const frozen = await card(el, 'frozen-block');
    expect(cardText(frozen, 'card-provenance')).toContain('Top 3 of 12 matches');
    expect(cardText(frozen, 'card-provenance')).toContain('northfield renewal');
    expect(text(el, 'session-name')).toBe('northfield renewal');
  });

  it('the Ask button commits the same way the ⇧↩ path does (one commit, not two models)', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'summarise these');
    (q(el, 'commit') as HTMLButtonElement).click();
    await el.updateComplete;

    expect(all(el, 'frozen-block')).toHaveLength(1);
    expect(text(el, 'turn')).toBe('summarise these');
    expect(q(el, 'rail-index')).not.toBeNull();
  });
});

describe('818 SearchV2View — scope chips (L3)', () => {
  it('L3 — opening a result pins a file chip and opens the reading pane, and changes NO destination', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'northfield supplier agreement');
    const before = text(el, 'pill');
    expect(q(el, 'reading-pane')).toBeNull();

    const c = await card(el, 'live-results');
    c.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd1' }, bubbles: true, composed: true }),
    );
    await el.updateComplete;

    // One act, two visible consequences.
    expect(addScopeChipMock).toHaveBeenCalledWith({
      kind: 'file',
      label: 'Q2 vendor review notes.md',
      docIds: ['Ops/Reviews/Q2.md'],
    });
    expect(all(el, 'scope-chip')).toHaveLength(1);
    expect(q(el, 'reading-pane')).not.toBeNull();

    // …and the destination is exactly what it was: a chip narrows, it does not re-route.
    expect(text(el, 'pill')).toBe(before);
  });

  it('L3 — unpinning a chip re-issues the search through the ONE seam', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    const c = await card(el, 'live-results');
    c.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd0' }, bubbles: true, composed: true }),
    );
    await el.updateComplete;

    submitSearchMock.mockClear();
    (q(el, 'scope-chip') as HTMLButtonElement).click();
    await el.updateComplete;

    expect(all(el, 'scope-chip')).toHaveLength(0);
    expect(submitSearchMock).toHaveBeenCalledTimes(1);
  });
});
