// @vitest-environment happy-dom

/**
 * The BOUNDARY-INTERACTION model (tempdoc 818 §6h, findings 16–19).
 *
 * Four findings from the owner's pass at real window sizes, sharing one cause: L13 said what a
 * boundary is clamped BY, and never said when one EXISTS, where it SITS, which way it MOVES, or
 * against what its regime switches are evaluated. These assert the four rules directly.
 *
 * Kept in its own file because it tests the boundaries as a MODEL rather than as presentation
 * details of one region — and because the direction case needs a real pointer sequence, which the
 * presentation suite's helpers do not provide.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

interface LiveFixture {
  query: string;
  results: Array<{ id: string; title: string; path: string; snippet?: string }>;
  matchCount: number;
  totalHits: number;
  facetsTruncated: boolean;
  isSearching: boolean;
  processingTimeMs: number | null;
  error: string | null;
  passStage: 'quick' | 'refined' | 'unknown';
}

const SEARCH: LiveFixture = {
  query: 'northfield',
  results: [
    { id: 'd0', title: 'Northfield agreement', path: 'Contracts/Northfield.pdf', snippet: 'x' },
    { id: 'd1', title: 'Renewal notice', path: 'Contracts/Renewal.pdf', snippet: 'y' },
  ],
  matchCount: 12,
  totalHits: 2,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 42,
  error: null,
  passStage: 'refined',
};

let searchListener: ((s: LiveFixture) => void) | null = null;

vi.mock('../../state/searchState.js', () => ({
  subscribeSearch: vi.fn((listener: (s: LiveFixture) => void) => {
    searchListener = listener;
    return () => {
      searchListener = null;
    };
  }),
  setQuery: vi.fn(),
  submitSearch: vi.fn(),
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
  createConversationId: () => 'c-test',
  loadConversations: vi.fn(async () => {}),
  subscribeConversationList: vi.fn((l: (s: { conversations: unknown[] }) => void) => {
    l({ conversations: [] });
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
const { SearchV2View } = await import('./SearchV2View.js');

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

/** Drive reconciliation directly: happy-dom defines ResizeObserver but never fires it. */
function reconcile(el: Mounted): void {
  (el as unknown as { reconcileBoundaries(): void }).reconcileBoundaries();
}

async function live(el: Mounted): Promise<void> {
  searchListener?.(SEARCH);
  await el.updateComplete;
}

async function commit(el: Mounted, draft: string): Promise<void> {
  await live(el);
  const input = q(el, 'draft') as HTMLInputElement;
  input.value = draft;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await el.updateComplete;
  input.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true, cancelable: true }),
  );
  await el.updateComplete;
}

function stubRect(el: Element, rect: { width: number; height: number; top?: number; left?: number }): void {
  (el as HTMLElement).getBoundingClientRect = (() => ({
    width: rect.width,
    height: rect.height,
    top: rect.top ?? 0,
    left: rect.left ?? 0,
  })) as never;
}

function appliedPx(el: Element | null): number {
  return Number(/flex:\s*0\s+0\s+(\d+)px/.exec(el?.getAttribute('style') ?? '')?.[1] ?? '0');
}

beforeEach(() => {
  document.body.innerHTML = '';
  localStorage.clear();
});

describe('§6h rule 1 — a boundary exists only between two live regions', () => {
  it('no deck grip before the first commit: there is no transcript to trade with', async () => {
    const el = await mount();
    await live(el);
    expect(q(el, 'transcript'), 'precondition: the other side really is absent').toBeNull();
    expect(q(el, 'deck'), 'precondition: this side really is present').not.toBeNull();
    expect(q(el, 'deck-grip')).toBeNull();
  });

  it('the deck grip arrives with the transcript and leaves with it', async () => {
    const el = await mount();
    await commit(el, 'what changed?');
    expect(q(el, 'transcript')).not.toBeNull();
    expect(q(el, 'deck-grip')).not.toBeNull();

    (q(el, 'rail-back') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'transcript')).toBeNull();
    expect(q(el, 'deck-grip')).toBeNull();
  });

  it('the document grip exists only while the document region does', async () => {
    const el = await mount();
    await live(el);
    expect(q(el, 'document-grip')).toBeNull();

    const card = q(el, 'live-results')?.querySelector('jf-results-card') as HTMLElement;
    card.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd0' }, bubbles: true, composed: true }),
    );
    await el.updateComplete;
    expect(q(el, 'reading-pane')).not.toBeNull();
    expect(q(el, 'document-grip')).not.toBeNull();
  });
});

describe('§6h rule 2 — a boundary sits ON the separator the user sees', () => {
  const css = (): string => SearchV2View.styles?.toString() ?? '';

  it('no region draws a border where a grip lives — the grip draws it', () => {
    const railRule = /\.rail\s*\{([^}]*)\}/.exec(css())?.[1] ?? '';
    const readingRule = /\.reading\s*\{([^}]*)\}/.exec(css())?.[1] ?? '';
    expect(railRule, 'the .rail rule was not found — selector drifted').not.toBe('');
    expect(readingRule, 'the .reading rule was not found — selector drifted').not.toBe('');
    // These borders were the lines the user aimed at while the grip floated in the track's gap,
    // 16px away — which is also where the rail's scrollbar renders (§6c finding 18).
    expect(railRule).not.toMatch(/border-right/);
    expect(readingRule).not.toMatch(/border-left/);
    expect(css(), 'the grip supplies the separator instead').toMatch(/button\.vgrip::before/);
  });

  it('the track has no gap for a grip to get lost in', () => {
    const winRule = /\.win\s*\{([^}]*)\}/.exec(css())?.[1] ?? '';
    expect(winRule, 'the .win rule was not found — selector drifted').not.toBe('');
    expect(winRule).not.toMatch(/gap:/);
  });

  it('each grip is adjacent to the region it moves', async () => {
    const el = await mount();
    const kids = [...(el.shadowRoot?.querySelector('.win')?.children ?? [])];
    const rail = kids.findIndex((k) => k.classList.contains('rail'));
    const grip = kids.findIndex((k) => k.getAttribute('data-testid') === 'rail-grip');
    expect(rail).toBeGreaterThanOrEqual(0);
    expect(grip, 'the grip sits immediately after the rail it moves').toBe(rail + 1);
  });
});

describe('§6h rule 3 — a boundary follows the pointer', () => {
  /**
   * Asserted on the BOUNDARY'S OWN displacement, never on the handler's sign: a sign is only
   * correct relative to an anchoring the test would otherwise have to assume, which is precisely
   * the assumption that made §6c finding 17 ambiguous. After a drag of Δ the boundary has moved by
   * Δ; which region grew is a consequence, not a second decision.
   *
   * Finding 17 settled: the reported inversion was measured in the EMPTY-transcript state, where
   * the deck is the column's only occupant and top-anchored — a height change cannot move its top
   * edge, so the grabbed boundary stayed put and the far edge retreated. With a transcript present
   * the transcript is the column's one shrink/grow occupant and absorbs the change, so the boundary
   * does follow. Inverting the sign would have fixed the empty state by breaking the populated one;
   * rule 1 removes the empty state instead.
   */
  it('the deck boundary dragged DOWN moves down by the pointer’s delta', async () => {
    const el = await mount();
    await commit(el, 'what changed?');
    const deck = el.shadowRoot?.querySelector('.deck') as HTMLElement;
    const centre = el.shadowRoot?.querySelector('.centre') as HTMLElement;
    const grip = q(el, 'deck-grip') as HTMLElement;
    expect(grip, 'the boundary exists in this state').not.toBeNull();

    // A 600px column with the deck occupying the lower 300: the boundary is the deck's TOP edge.
    const startHeight = 300;
    stubRect(centre, { width: 800, height: 600 });
    stubRect(deck, { width: 800, height: startHeight, top: 600 - startHeight });

    grip.dispatchEvent(new PointerEvent('pointerdown', { clientY: 300, bubbles: true }));
    grip.dispatchEvent(new PointerEvent('pointermove', { clientY: 400, bubbles: true }));
    grip.dispatchEvent(new PointerEvent('pointerup', { clientY: 400, bubbles: true }));
    await el.updateComplete;

    const applied = appliedPx(deck);
    expect(applied, 'the gesture wrote a height').toBeGreaterThan(0);
    // The column's bottom is fixed, so the deck's top edge — the boundary — is 600 − height.
    const moved = 600 - applied - (600 - startHeight);
    expect(moved, 'the boundary moved WITH the pointer, by its delta').toBe(100);
  });

  it('the sessions boundary dragged RIGHT moves right by the pointer’s delta', async () => {
    const el = await mount();
    const win = el.shadowRoot?.querySelector('.win') as HTMLElement;
    const rail = el.shadowRoot?.querySelector('.rail') as HTMLElement;
    stubRect(win, { width: 1400, height: 600 });
    const startWidth = 224;
    stubRect(rail, { width: startWidth, height: 600 });
    const grip = q(el, 'rail-grip') as HTMLElement;

    grip.dispatchEvent(new PointerEvent('pointerdown', { clientX: 224, bubbles: true }));
    grip.dispatchEvent(new PointerEvent('pointermove', { clientX: 304, bubbles: true }));
    grip.dispatchEvent(new PointerEvent('pointerup', { clientX: 304, bubbles: true }));
    await el.updateComplete;

    // The rail's right edge IS the boundary, so its width change is the boundary's displacement.
    expect(appliedPx(rail) - startWidth, 'the boundary moved WITH the pointer').toBe(80);
  });
});

describe('§6h rule 4 — regime switches read LIVE geometry', () => {
  it('an AUTOMATIC rail squeezed under its legibility floor takes its collapsed form', async () => {
    // §6c finding 19 exactly: no width was ever chosen, so the old predicate
    // (`chosen !== null && railYields(chosen)`) never evaluated the rule at all, and the rail kept
    // rendering full rows at 124px — under its own 128px floor.
    const el = await mount();
    stubRect(el.shadowRoot?.querySelector('.win') as HTMLElement, { width: 900, height: 545 });
    stubRect(el.shadowRoot?.querySelector('.rail') as HTMLElement, { width: 124, height: 500 });
    stubRect(el.shadowRoot?.querySelector('.centre') as HTMLElement, { width: 600, height: 500 });

    reconcile(el);
    await el.updateComplete;

    expect(q(el, 'rail-strip'), 'the rail takes its minimum honest form').not.toBeNull();
    expect(q(el, 'rail-sidebar'), 'and stops rendering rows it has no room for').toBeNull();
  });

  it('a rail with room keeps its rows', async () => {
    const el = await mount();
    stubRect(el.shadowRoot?.querySelector('.win') as HTMLElement, { width: 1400, height: 900 });
    stubRect(el.shadowRoot?.querySelector('.rail') as HTMLElement, { width: 224, height: 800 });
    stubRect(el.shadowRoot?.querySelector('.centre') as HTMLElement, { width: 1000, height: 800 });

    reconcile(el);
    await el.updateComplete;
    expect(q(el, 'rail-sidebar')).not.toBeNull();
    expect(q(el, 'rail-strip')).toBeNull();
  });

  it('an UNMEASURED rail is not a narrow one — it triggers nothing', async () => {
    const el = await mount();
    reconcile(el);
    await el.updateComplete;
    expect(q(el, 'rail-strip')).toBeNull();
  });
});
