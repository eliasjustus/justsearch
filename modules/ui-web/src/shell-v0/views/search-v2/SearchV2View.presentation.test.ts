// @vitest-environment happy-dom

/**
 * The presentation laws of the Search v2 window (tempdoc 818 slice 4).
 *
 *  - **the commit choreography** — the transient host class exists exactly while the entrance runs,
 *    on BOTH send paths, and does not exist at all under `prefers-reduced-motion` (asserted through a
 *    `matchMedia` stub — happy-dom exposes the API, so the reduced-motion PATH is testable, not just
 *    the stylesheet; the stylesheet's own media block is asserted too, because it is what protects a
 *    preference changed while the class is applied).
 *  - **L7/L13 — the movable boundary** — the floor is computed from the deck's OWN occupants: the
 *    list body is excluded and, while a run is live, the run CONTROLS are included, so the decision
 *    cannot be dragged off screen any more than it can be scrolled off. Geometry is fake here, so the
 *    walk is asserted over the real shadow DOM with the test's own measure function, and the clamp
 *    arithmetic is asserted as a pure unit in `deckSizing.test.ts`.
 *  - **the unhappy states** — a settled zero-result search states the honest empty and re-derives its
 *    escalation label for n = 0; an AI-offline verdict dims the ASK/DELEGATE destinations with the
 *    SHARED reason while SEARCH — the floor — stays fully live.
 *  - **814 §D6/D3** — the window consumes the shared block-axis breakpoint, and no scroll region
 *    nests inside another.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  COMMITTING_CLASS,
  COMMIT_CHOREOGRAPHY_MS,
  SearchV2View,
  askAffordanceLabel,
} from './SearchV2View.js';
import { collectIncompressibleHeights, deckFloorFrom } from './deckSizing.js';
import { SESSION_RAIL_FLOOR_PX, storageKey } from './railSizing.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { SHORT_VIEWPORT_MAX_HEIGHT_PX } from '../../primitives/compositionLayout.js';
import type { AgentSessionController, ConversationEntry } from '../../controllers/AgentSessionController.js';

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

const SEARCH_WITH_RESULTS: LiveSearchFixture = {
  query: 'northfield renewal',
  results: [
    { id: 'd0', title: 'Northfield supplier agreement.pdf', path: 'Contracts/Northfield.pdf' },
    { id: 'd1', title: 'Q2 vendor review notes.md', path: 'Ops/Reviews/Q2.md' },
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

/** A search that SETTLED at nothing — not a search still running, which says nothing yet. */
const SEARCH_ZERO: LiveSearchFixture = {
  query: 'chemring indemnity clause',
  results: [],
  totalHits: 0,
  matchCount: 0,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 18,
  error: null,
  searchTrace: { effectiveMode: 'HYBRID' },
  passStage: 'refined',
};

/** A live, healthy backend with the model up — every escalation destination is reachable. */
const AI_HEALTHY = {
  phase: 'ready',
  snapshotLive: true,
  capabilities: { chat: true, rag: true, extract: true, embedding: true },
  runtime: { mode: 'online', contextWindow: 4096 },
  status: null,
  index: {},
};

/** The same backend, settled with the local model OFFLINE: chat cannot be served. */
const AI_OFFLINE = {
  ...AI_HEALTHY,
  capabilities: { chat: false, rag: false, extract: false, embedding: false },
  runtime: { mode: 'offline', contextWindow: null },
};

const OFFLINE_REASON = reasonFor('inference.offline').wording;

interface FakeCtrl {
  conversation: ConversationEntry[];
  toolCalls: Record<string, unknown>;
  streamingText: string;
  isStreaming: boolean;
  runInFlight: boolean;
  runKind: 'agent' | 'workflow' | 'background' | null;
  conversationId: string | null;
  iterationsUsed: number;
  totalTokensUsed: number | null;
  budgetUpdates: Array<Record<string, number>>;
  budgetGate: null;
  contextGate: null;
  sessionId: string | null;
  send: ReturnType<typeof vi.fn>;
  steer: ReturnType<typeof vi.fn>;
  cancelSession: ReturnType<typeof vi.fn>;
}

function makeCtrl(): FakeCtrl {
  return {
    conversation: [],
    toolCalls: {},
    streamingText: '',
    isStreaming: false,
    runInFlight: false,
    runKind: null,
    conversationId: null,
    iterationsUsed: 0,
    totalTokensUsed: null,
    budgetUpdates: [],
    budgetGate: null,
    contextGate: null,
    sessionId: 'run-1',
    send: vi.fn(async () => {}),
    steer: vi.fn(async () => {}),
    cancelSession: vi.fn(async () => {}),
  };
}

let ctrl: FakeCtrl = makeCtrl();
let sessions: unknown[] = [];
let sessionsListener:
  | ((s: { conversations: unknown[]; activeId: null; loading: boolean }) => void)
  | null = null;
let agentListener: (() => void) | null = null;
let searchListener: ((s: LiveSearchFixture) => void) | null = null;
let aiListener: ((s: unknown) => void) | null = null;
const submitSearchMock = vi.fn();

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => ctrl as unknown as AgentSessionController,
  peekAgentSessionController: () => ctrl as unknown as AgentSessionController,
  subscribeAgentSession: (listener: () => void) => {
    agentListener = listener;
    return () => {
      agentListener = null;
    };
  },
}));

vi.mock('./askClient.js', () => ({
  ASK_SHAPE_ID: 'core.rag-ask',
  askDocuments: vi.fn(async () => {}),
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
      sessionsListener = listener;
      listener({ conversations: sessions, activeId: null, loading: false });
      return () => {
        sessionsListener = null;
      };
    },
  ),
}));

/** The shared pinned-search projection, mocked like every other store this file consumes. */
let pins: Array<{ id: string; query: string }> = [];
vi.mock('../../state/pinnedSearchState.js', () => ({
  subscribePinnedSearches: (listener: (p: unknown[]) => void) => {
    listener(pins);
    return () => {};
  },
}));

vi.mock('../../state/aiStateStore.js', () => ({
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    aiListener = listener;
    listener(AI_HEALTHY);
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
  return el;
}

function q(el: Mounted, testid: string): HTMLElement | null {
  return el.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;
}

function text(el: Mounted, testid: string): string {
  return (q(el, testid)?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

async function live(el: Mounted, fixture: LiveSearchFixture): Promise<void> {
  searchListener?.(fixture);
  await el.updateComplete;
}

async function ai(el: Mounted, snapshot: unknown): Promise<void> {
  aiListener?.(snapshot);
  await el.updateComplete;
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

/** A `matchMedia` that answers `true` for exactly the queries named. */
function stubMatchMedia(matching: readonly string[]): void {
  window.matchMedia = ((query: string) => ({
    media: query,
    matches: matching.some((m) => query.includes(m)),
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

const REAL_MATCH_MEDIA = window.matchMedia;

beforeEach(() => {
  document.body.innerHTML = '';
  ctrl = makeCtrl();
  pins = [];
  sessions = [];
  submitSearchMock.mockClear();
  stubMatchMedia([]);
  localStorage.clear();
});

afterEach(() => {
  window.matchMedia = REAL_MATCH_MEDIA;
  vi.useRealTimers();
});

describe('818 SearchV2View — the commit choreography', () => {
  it('runs the entrance on commit and sheds the class when it settles', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed in the renewal?');

    expect(el.classList.contains(COMMITTING_CLASS)).toBe(false);
    await key(el, { key: 'Enter', shiftKey: true });
    // The record landed AND the entrance is running — the class is transient, never a state.
    expect(q(el, 'frozen-block')).not.toBeNull();
    expect(el.classList.contains(COMMITTING_CLASS)).toBe(true);

    vi.advanceTimersByTime(COMMIT_CHOREOGRAPHY_MS);
    expect(el.classList.contains(COMMITTING_CLASS)).toBe(false);
  });

  it('ends on the LAST animation’s end, not on whichever finishes first', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed?');
    await key(el, { key: 'Enter', shiftKey: true });

    // An earlier animation ending must not cut the sequence short.
    el.dispatchEvent(new AnimationEvent('animationend', { animationName: 'sv2-cm-rise' }));
    expect(el.classList.contains(COMMITTING_CLASS)).toBe(true);

    el.dispatchEvent(new AnimationEvent('animationend', { animationName: 'sv2-cm-answer' }));
    expect(el.classList.contains(COMMITTING_CLASS)).toBe(false);
  });

  it('runs the same entrance on the DELEGATE path — one commit, one causal story', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'reconcile the two certificate lists');

    (q(el, 'delegate') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(el.classList.contains(COMMITTING_CLASS)).toBe(true);

    vi.advanceTimersByTime(COMMIT_CHOREOGRAPHY_MS);
    expect(el.classList.contains(COMMITTING_CLASS)).toBe(false);
  });

  it('reduced motion is INSTANT — the class is never applied, and the record still lands', async () => {
    stubMatchMedia(['prefers-reduced-motion']);
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed?');
    await key(el, { key: 'Enter', shiftKey: true });

    expect(el.classList.contains(COMMITTING_CLASS)).toBe(false);
    expect(q(el, 'frozen-block')).not.toBeNull();
    expect(text(el, 'turn')).toBe('what changed?');
  });

  it('the stylesheet disables the whole sequence under reduced motion', () => {
    const styles = SearchV2View.styles?.toString() ?? '';
    expect(styles).toContain('@media (prefers-reduced-motion: reduce)');
    // Every animated region is named in the disabling block, so no limb keeps moving.
    const block = styles.slice(styles.indexOf('@media (prefers-reduced-motion: reduce)'));
    for (const region of ['.turn', '.frozen', '.deck', '.rail', '.name', '.answer']) {
      expect(block).toContain(`:host(.committing) ${region}`);
    }
  });
});

describe('818 SearchV2View — the deck grip (L7/L13)', () => {
  /** Measure by test id, so a fake-geometry environment can still assert the real DOM walk. */
  function measureBy(heights: Record<string, number>): (el: Element) => number {
    return (el) => heights[el.getAttribute('data-testid') ?? ''] ?? 0;
  }

  async function startRun(el: Mounted): Promise<void> {
    await type(el, 'reconcile the two certificate lists');
    (q(el, 'delegate') as HTMLButtonElement).click();
    ctrl.runInFlight = true;
    agentListener?.();
    await el.updateComplete;
  }

  it('the floor includes the run CONTROLS while a run is in flight, and excludes the list body', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await startRun(el);
    expect(q(el, 'run-controls')).not.toBeNull();

    const deck = el.shadowRoot?.querySelector('.deck') as HTMLElement;
    const measure = measureBy({
      'input-band': 48,
      'live-results': 400,
      'run-controls': 96,
      'run-feed': 300,
    });
    const heights = collectIncompressibleHeights(deck, measure);

    expect(heights).toContain(96); // the decision is part of the floor…
    expect(heights).not.toContain(400); // …and the compressible list body is not.
    expect(heights).not.toContain(300); // …nor is the run's own (scrollable) feed.
    const floor = deckFloorFrom(heights);
    expect(floor).toBeGreaterThanOrEqual(96);
    expect(floor).toBeLessThan(400);
  });

  it('the keyboard half of the boundary sizes the deck, and at the floor the list takes its count line', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    const grip = q(el, 'deck-grip') as HTMLElement;
    expect(q(el, 'live-results')).not.toBeNull();

    grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    await el.updateComplete;

    const deck = el.shadowRoot?.querySelector('.deck') as HTMLElement;
    expect(deck.getAttribute('style') ?? '').toContain('flex: 0 0');
    expect(deck.classList.contains('sized')).toBe(true);
    // Fake geometry puts the deck at its own floor, which is exactly the case the law names: the
    // list has no room to be a list, so it shows the ONE derived count instead of nothing.
    expect(q(el, 'live-results')).toBeNull();
    expect(text(el, 'live-count')).toContain('12 matches');
  });

  it('double-click returns the boundary to AUTOMATIC — the inline size is cleared', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    const grip = q(el, 'deck-grip') as HTMLElement;
    grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }));
    await el.updateComplete;
    const deck = el.shadowRoot?.querySelector('.deck') as HTMLElement;
    expect(deck.getAttribute('style') ?? '').toContain('flex: 0 0');

    grip.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector('.deck')?.getAttribute('style') ?? '').not.toContain('flex');
    expect(el.shadowRoot?.querySelector('.deck')?.classList.contains('sized')).toBe(false);
    expect(q(el, 'live-results')).not.toBeNull();
  });

  it('a pointer drag writes the clamped height onto the deck and adopts it at the end of the gesture', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    const grip = q(el, 'deck-grip') as HTMLElement;
    const deck = el.shadowRoot?.querySelector('.deck') as HTMLElement;

    grip.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, cancelable: true, clientY: 500 }));
    grip.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientY: 300 }));
    // Written directly during the gesture — the results card does not re-render per pointer frame.
    // (An imperative `style.flex` write expands to the longhands, unlike the rendered shorthand.)
    expect(deck.getAttribute('style') ?? '').toMatch(/flex(-basis)?:\s*(0 0 )?\d+px/);

    grip.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
    await el.updateComplete;
    // …and adopted, so the next render keeps the user's size.
    expect(el.shadowRoot?.querySelector('.deck')?.classList.contains('sized')).toBe(true);

    // A further move after the gesture ended is not a resize: the listeners came off with it.
    const sized = el.shadowRoot?.querySelector('.deck')?.getAttribute('style');
    grip.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientY: 100 }));
    expect(el.shadowRoot?.querySelector('.deck')?.getAttribute('style')).toBe(sized);
  });

  it('L13 — the deck RESETS with the session (a height is a shape, not a preference)', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    (q(el, 'deck-grip') as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }),
    );
    await el.updateComplete;

    (q(el, 'new-session') as HTMLButtonElement).click();
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector('.deck')?.getAttribute('style') ?? '').not.toContain('flex');
  });
});

describe('818 SearchV2View — the unhappy states', () => {
  it('n = 0 — the escalation label re-derives; it never names a scope of zero', () => {
    expect(askAffordanceLabel(0)).toBe('Ask anyway — your files are searched again while answering');
    expect(askAffordanceLabel(0)).not.toContain('these 0');
    expect(askAffordanceLabel(3)).toBe('Ask about these 3');
  });

  it('a settled zero-result search shows the honest empty and the n = 0 affordance', async () => {
    const el = await mount();
    await live(el, SEARCH_ZERO);

    expect(text(el, 'commit')).toBe(askAffordanceLabel(0));
    const note = text(el, 'zero-note');
    expect(note).toContain('chemring indemnity clause');
    expect(note).toContain('does not mean there is nothing to answer from');
    // No fabricated rows: the ONE results card renders the empty set, and nothing invents one.
    const card = q(el, 'live-results')?.querySelector('jf-results-card') as Mounted;
    await card.updateComplete;
    expect(card.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]')).toHaveLength(0);
  });

  it('a search still IN FLIGHT is not an empty result — nothing is claimed yet', async () => {
    const el = await mount();
    await live(el, { ...SEARCH_ZERO, isSearching: true });
    expect(q(el, 'zero-note')).toBeNull();

    await live(el, SEARCH_WITH_RESULTS);
    expect(q(el, 'zero-note')).toBeNull();
    expect(text(el, 'commit')).toBe(askAffordanceLabel(2));
  });

  it('AI offline — ASK/DELEGATE read as unavailable WITH the shared reason', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed in the renewal?'); // a question → the ASK rung

    expect(q(el, 'commit')?.getAttribute('data-unavailable')).toBe('false');
    expect(q(el, 'pill')?.getAttribute('data-unavailable')).toBe('false');

    await ai(el, AI_OFFLINE);

    for (const id of ['commit', 'delegate']) {
      expect(q(el, id)?.getAttribute('data-unavailable')).toBe('true');
      expect(q(el, id)?.getAttribute('aria-disabled')).toBe('true');
      // The reason is REACHABLE — a described-by pointer to a VISIBLE line, not a hover-only title
      // (which a lock-disabled control would suppress outright, 596 face 1.1).
      expect(q(el, id)?.getAttribute('aria-describedby')).toBe('sv2-ai-unavailable');
      expect(q(el, id)?.getAttribute('title')).toBeNull();
    }
    // …and it is the product's own wording, not a second one.
    expect(text(el, 'ai-unavailable')).toContain(OFFLINE_REASON);
    expect(text(el, 'ai-unavailable')).toContain('searching your files is unaffected');
    expect(q(el, 'pill')?.getAttribute('data-unavailable')).toBe('true');
    expect(q(el, 'pill')?.classList.contains('unavailable')).toBe(true);
    expect(q(el, 'pill')?.getAttribute('title')).toBe(OFFLINE_REASON);
  });

  it('SEARCH is the floor: it does not degrade with the model', async () => {
    const el = await mount();
    await ai(el, AI_OFFLINE);
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'northfield supplier agreement'); // a plain draft → the SEARCH rung

    expect(text(el, 'pill')).toContain('SEARCH');
    expect(q(el, 'pill')?.getAttribute('data-unavailable')).toBe('false');
    expect(q(el, 'pill')?.classList.contains('unavailable')).toBe(false);

    await key(el, { key: 'Enter' });
    expect(submitSearchMock).toHaveBeenCalledTimes(1);
    // …and the results are still on screen, through the same card.
    expect(q(el, 'live-results')?.querySelector('jf-results-card')).not.toBeNull();
  });
});

describe('818 SearchV2View — the small window (814 §D3/D6)', () => {
  it('consumes the SHARED block-axis breakpoint rather than minting a second one', () => {
    const styles = SearchV2View.styles?.toString() ?? '';
    expect(styles).toContain(`@media (max-height: ${SHORT_VIEWPORT_MAX_HEIGHT_PX}px)`);
  });

  it('no scroll region nests inside another, and the transcript is the one that owns the slack', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    await type(el, 'what changed in the renewal?');
    await key(el, { key: 'Enter', shiftKey: true });

    const scrollers = [
      ...(el.shadowRoot?.querySelectorAll('[data-scrollable="true"]') ?? []),
    ] as HTMLElement[];
    expect(scrollers.length).toBeGreaterThan(1);
    for (const outer of scrollers) {
      for (const inner of scrollers) {
        if (outer !== inner) expect(outer.contains(inner)).toBe(false);
      }
    }
    // The transcript is a scroller in its own right, and the deck is its sibling, not its child.
    const transcript = q(el, 'transcript') as HTMLElement;
    const deck = el.shadowRoot?.querySelector('.deck') as HTMLElement;
    expect(transcript.getAttribute('data-scrollable')).toBe('true');
    expect(transcript.contains(deck)).toBe(false);
    expect(transcript.parentElement).toBe(deck.parentElement);
  });
});

/**
 * The layout AXIS (tempdoc 818 §6c finding 1 / §6g C1). The window's three regions sit side by side,
 * and for two slices they did not: `.body` (the shared surface layout's scroll-policy region, which
 * this surface makes `flex-direction: column`) and `.win` (the horizontal track) were carried on ONE
 * node, so the column direction landed on the track — uncontested, because `.win` declares no
 * direction of its own — and the rail rendered as a full-width band above the centre column.
 *
 * Two witnesses, because they fail for different reasons and one alone is escapable:
 *  - the CASCADE witness asserts the defect's exact negation. It is deliberately NOT `toBe('row')`:
 *    happy-dom resolves only DECLARED properties, so the correct nested structure — where `.win`
 *    simply inherits the initial `row` — computes to '' and a `toBe('row')` assertion would fail on
 *    the fix while passing on a same-node declaration (818 §6f(a), measured).
 *  - the STRUCTURAL witness asserts the cause rather than the symptom: the two roles are two nodes.
 *    A future edit that re-merges the classes is caught here even if it happens to declare a
 *    direction that keeps the cascade witness green.
 *
 * The third test below is deliberately NOT labelled a witness: it was measured against the broken
 * code and PASSED there (with the classes merged, `.win` is `.body`, whose children are still the
 * three regions in order), so it is no evidence for finding 1. It is kept as a companion regression
 * guard for the restructure itself — the template rewrite that separated the nodes could have
 * reordered the regions or left one outside the track — and it is named honestly rather than
 * counted as coverage it does not provide.
 */
describe('818 SearchV2View — the layout axis (§6c finding 1)', () => {
  it('the horizontal track never resolves to a column', async () => {
    const el = await mount();
    const track = el.shadowRoot?.querySelector('.win') as HTMLElement;
    expect(track).not.toBeNull();
    expect(getComputedStyle(track).flexDirection).not.toBe('column');
  });

  it('the scroll-policy region and the horizontal track are different elements', async () => {
    const el = await mount();
    const body = el.shadowRoot?.querySelector('.body');
    const track = el.shadowRoot?.querySelector('.win');
    expect(body).not.toBeNull();
    expect(track).not.toBeNull();
    expect(body).not.toBe(track);
    // …and the track is inside the policy region, not a sibling that escaped it.
    expect(body?.contains(track as Node)).toBe(true);
  });

  // Companion, not a witness — see the block comment above.
  it('the three regions are siblings on that track, in reading order', async () => {
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    const card = q(el, 'live-results')?.querySelector('jf-results-card') as HTMLElement;
    card.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd0' }, bubbles: true, composed: true }),
    );
    await el.updateComplete;

    const track = el.shadowRoot?.querySelector('.win') as HTMLElement;
    const regions = [...track.children].filter((c) => !c.classList.contains('vgrip'));
    expect(regions.map((r) => r.className.split(' ')[0])).toEqual(['rail', 'centre', 'reading']);
  });
});

/* ── slice 5 ───────────────────────────────────────────────────────────────────────────────── */

function all(el: Mounted, testid: string): HTMLElement[] {
  return [...(el.shadowRoot?.querySelectorAll(`[data-testid="${testid}"]`) ?? [])] as HTMLElement[];
}

/** Give the window a real width so the rails' ceilings are computable in a fake-geometry DOM. */
function stubWindowWidth(el: Mounted, width: number): void {
  const win = el.shadowRoot?.querySelector('.win') as HTMLElement;
  win.getBoundingClientRect = (() => ({ width, height: 800, top: 0, left: 0 })) as never;
}

async function commit(el: Mounted, draft: string): Promise<void> {
  await live(el, SEARCH_WITH_RESULTS);
  await type(el, draft);
  await key(el, { key: 'Enter', shiftKey: true });
}

async function focusDraft(el: Mounted): Promise<void> {
  const input = q(el, 'draft') as HTMLInputElement;
  input.dispatchEvent(new FocusEvent('focus'));
  await el.updateComplete;
}

describe('818 SearchV2View — the rails’ movable boundaries (L13)', () => {
  it('both rails carry a grip, and the document grip exists only while a document does', async () => {
    const el = await mount();
    expect(q(el, 'rail-grip')).not.toBeNull();
    expect(q(el, 'document-grip')).toBeNull();

    await live(el, SEARCH_WITH_RESULTS);
    const card = q(el, 'live-results')?.querySelector('jf-results-card') as HTMLElement;
    card.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd0' }, bubbles: true, composed: true }),
    );
    await el.updateComplete;
    expect(q(el, 'reading-pane')).not.toBeNull();
    expect(q(el, 'document-grip')).not.toBeNull();
  });

  it('the grips are native buttons — the keyboard half of the boundary comes for free', async () => {
    const el = await mount();
    expect(q(el, 'rail-grip')?.tagName).toBe('BUTTON');
    expect(q(el, 'rail-grip')?.getAttribute('aria-label')).toContain('arrow keys resize');
    expect(q(el, 'deck-grip')?.tagName).toBe('BUTTON');
  });

  it('a keyboard nudge sizes the rail, clamped, and REMEMBERS the width (L13 asymmetry)', async () => {
    const el = await mount();
    stubWindowWidth(el, 1400);
    const grip = q(el, 'rail-grip') as HTMLElement;

    grip.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }),
    );
    await el.updateComplete;

    const rail = el.shadowRoot?.querySelector('.rail') as HTMLElement;
    expect(rail.getAttribute('style')).toContain('flex: 0 0 248px');
    expect(localStorage.getItem(storageKey('sessions'))).toBe('248');

    // …and a fresh mount opens at the remembered width.
    document.body.innerHTML = '';
    const again = await mount();
    expect((again.shadowRoot?.querySelector('.rail') as HTMLElement).getAttribute('style')).toContain(
      '248px',
    );
  });

  it('Home returns the boundary to automatic AND forgets it — the choice was withdrawn', async () => {
    const el = await mount();
    stubWindowWidth(el, 1400);
    const grip = q(el, 'rail-grip') as HTMLElement;
    grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await el.updateComplete;
    expect(localStorage.getItem(storageKey('sessions'))).not.toBeNull();

    grip.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await el.updateComplete;
    const rail = el.shadowRoot?.querySelector('.rail') as HTMLElement;
    expect(rail.getAttribute('style') ?? '').not.toContain('flex');
    expect(localStorage.getItem(storageKey('sessions'))).toBeNull();
  });

  it('at its floor the rail takes its minimum honest form — the strip keeps the count', async () => {
    localStorage.setItem(storageKey('sessions'), String(SESSION_RAIL_FLOOR_PX));
    const el = await mount();
    expect(q(el, 'rail-sidebar')).toBeNull();
    expect(q(el, 'rail-strip')).not.toBeNull();
    // Narrowing a region may cost its rows; it may not cost the fact that they are there.
    expect(q(el, 'rail-strip-count')).not.toBeNull();
    // …and the one affordance that undoes the choice is right there.
    (q(el, 'rail-expand') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'rail-sidebar')).not.toBeNull();
  });

  it('the DECK still resets per session while the rails remember (the L13 asymmetry, both halves)', async () => {
    const el = await mount();
    stubWindowWidth(el, 1400);
    (q(el, 'rail-grip') as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }),
    );
    (q(el, 'deck-grip') as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }),
    );
    await el.updateComplete;

    // Nothing persists a deck height, in any key.
    const keys = [...Array(localStorage.length).keys()].map((i) => localStorage.key(i) ?? '');
    expect(keys.some((k) => k.includes('railWidth'))).toBe(true);
    expect(keys.some((k) => k.toLowerCase().includes('deck'))).toBe(false);
  });
});

describe('818 SearchV2View — resting minimum, extension on hover AND focus (L14)', () => {
  it('ONE mechanism: the extension is a .ext inside a .ext-row, revealed by hover and focus alike', () => {
    const styles = SearchV2View.styles?.toString() ?? '';
    expect(styles).toContain('.ext-row:hover .ext');
    expect(styles).toContain('.ext-row:focus-within .ext');
    // The resting form is CLIPPED, never removed: a screen reader still reads the elaboration.
    const rule = styles.slice(styles.indexOf('.ext {'), styles.indexOf('.ext-row:hover'));
    expect(rule).toContain('clip-path: inset(50%)');
    expect(rule).not.toContain('display: none');
    expect(rule).not.toContain('visibility: hidden');
  });

  it('the session row rests at its title; its meta is the extended form', async () => {
    sessions = [
      {
        id: 'c1',
        title: 'Supplier renewals',
        lastActiveAt: Date.now(),
        messageCount: 4,
        firstUserMessage: '',
      },
    ];
    const el = await mount();
    expect(sessionsListener).not.toBeNull();
    const meta = q(el, 'session-row-meta') as HTMLElement;
    expect(meta.classList.contains('ext')).toBe(true);
    expect(meta.closest('.ext-row')).not.toBeNull();
    // Focus parity is structural: the row can hold focus, so the keyboard reaches the elaboration.
    const row = q(el, 'session-row') as HTMLElement;
    expect(row.getAttribute('tabindex')).toBe('0');
  });

  it('an index node rests as label + size and elaborates into the record’s detail', async () => {
    const el = await mount();
    await commit(el, 'what changed in the renewal?');

    const node = q(el, 'index-node') as HTMLElement;
    expect(node.tagName).toBe('BUTTON');
    // The size is a COUNT: it rests, outside any extended container.
    const size = node.querySelector('.count:not(.ext)');
    expect(size?.textContent?.trim()).toBe('3');
    const detail = q(el, 'index-node-detail') as HTMLElement;
    expect(detail.classList.contains('ext')).toBe(true);
    expect(detail.textContent).toContain('Refined pass');
    expect(detail.textContent).toContain('42 ms');
  });

  it('the frozen block elaborates into TIMINGS only — the card already states the rest', async () => {
    const el = await mount();
    await commit(el, 'what changed?');
    const timing = q(el, 'frozen-timing') as HTMLElement;
    expect(timing.classList.contains('ext')).toBe(true);
    expect(timing.textContent?.replace(/\s+/g, ' ').trim()).toBe('Refined pass · 42 ms');
    expect(timing.closest('.ext-row')).toBe(q(el, 'frozen-block'));
  });

  it('HARD BOUNDARY — counts, verdicts and grounding never sit inside an extended container', async () => {
    const el = await mount();
    await commit(el, 'what changed?');
    aiListener?.({ ...AI_HEALTHY, status: { conversationProtection: { state: 'locked' } } });
    await type(el, 'and after that?');
    await key(el, { key: 'Enter', shiftKey: true }); // the refusal path, which the lock catches

    // QUANTIFIED, not enumerated. The previous form listed three ids and passed — while a count
    // sat inside an .ext one selector away (the sidebar row's message tally), and while naming
    // "grounding" in its own title without asserting it. A list cannot be outgrown by a new region;
    // a universal selector can't be satisfied by picking different examples.
    expect(extendedCounts(el), 'no current-set count may hide behind hover').toEqual([]);

    // The three that must be on screen ARE, so the sweep above is not passing on an empty window.
    for (const id of ['index-count', 'index-foot', 'lock-refusal']) {
      const node = q(el, id);
      expect(node, `${id} is on screen`).not.toBeNull();
      expect(node?.closest('.ext'), `${id} rests visible`).toBeNull();
    }
    // The one honesty fact the deck can hide (the collapsed list) still states its count.
    (q(el, 'list-collapse') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'live-count')?.closest('.ext')).toBeNull();
  });

});

/** Every element that carries a CURRENT-SET honesty fact while sitting inside an extension. */
function extendedCounts(el: Mounted): Array<string | undefined> {
  return [...(el.shadowRoot?.querySelectorAll('.ext .count, .count.ext') ?? [])].map((n) =>
    n.textContent?.trim(),
  );
}

describe('818 SearchV2View — the hard boundary holds in BOTH rail modes (L14)', () => {
  it('mode A — the sessions sidebar hides no current-set count behind hover', async () => {
    // The sweep in the committed state cannot see this: committing flips the rail to the session
    // INDEX, so the sidebar's rows are not rendered there at all and a violation among them would
    // pass for absence. Mode A needs its own capture — the anti-vacuity companion, applied to a
    // test rather than to a register row.
    sessions = [
      {
        id: 'c1',
        title: 'Supplier renewals',
        lastActiveAt: Date.now(),
        messageCount: 4,
        firstUserMessage: '',
      },
    ];
    const el = await mount();
    expect(q(el, 'session-row-meta'), 'the row IS on screen, so this is not vacuous').not.toBeNull();
    expect(extendedCounts(el)).toEqual([]);
  });
});

describe('818 SearchV2View — the query trail lives in the input band (L12)', () => {
  it('opens on the focus of an EMPTY draft, and never in the rail', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }];
    const el = await mount();
    expect(q(el, 'query-trail')).toBeNull();

    await focusDraft(el);
    const trail = q(el, 'query-trail') as HTMLElement;
    expect(trail).not.toBeNull();
    // L12 — the rail never yields queries: the trail is inside the deck's input band, not the rail.
    expect(trail.closest('.rail')).toBeNull();
    expect(trail.closest('.deck')).not.toBeNull();
    expect(q(el, 'rail')?.textContent).not.toContain('lease renewal');
  });

  it('does NOT open on a draft in progress — a draft is not a history search', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }];
    const el = await mount();
    await type(el, 'north');
    await focusDraft(el);
    expect(q(el, 'query-trail')).toBeNull();
  });

  it('shows pinned searches and this session’s own recents, in that order', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }];
    const el = await mount();
    await commit(el, 'what changed in the renewal?');
    await focusDraft(el);

    expect(q(el, 'trail-pinned-label')).not.toBeNull();
    expect(q(el, 'trail-recent-label')).not.toBeNull();
    const rows = all(el, 'trail-row').map((r) => r.textContent?.trim());
    expect(rows[0]).toBe('lease renewal');
    // The session's committed search is the recents' first source (the window already holds it).
    expect(rows).toContain('northfield renewal');
  });

  it('typing filters it', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }, { id: 'p2', query: 'invoices 2025' }];
    const el = await mount();
    await focusDraft(el);
    expect(all(el, 'trail-row')).toHaveLength(2);

    await type(el, 'invo');
    expect(all(el, 'trail-row').map((r) => r.textContent?.trim())).toEqual(['invoices 2025']);
  });

  it('picking a row FILLS the draft and runs the search — it never commits', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }];
    const el = await mount();
    await focusDraft(el);
    (all(el, 'trail-row')[0] as HTMLButtonElement).click();
    await el.updateComplete;

    expect((q(el, 'draft') as HTMLInputElement).value).toBe('lease renewal');
    expect(submitSearchMock).toHaveBeenCalled();
    // No record was written: committing is the user's own act (L4/L8).
    expect(q(el, 'frozen-block')).toBeNull();
    expect(text(el, 'session-name')).toBe('New session');
    expect(q(el, 'query-trail')).toBeNull();
  });

  it('keyboard: ↓ enters the list, Enter picks', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }, { id: 'p2', query: 'invoices 2025' }];
    const el = await mount();
    await focusDraft(el);

    await key(el, { key: 'ArrowDown' });
    await el.updateComplete;
    expect(all(el, 'trail-row')[0]?.getAttribute('aria-current')).toBe('true');

    const list = q(el, 'query-trail') as HTMLElement;
    list.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    await el.updateComplete;
    expect(all(el, 'trail-row')[1]?.getAttribute('aria-current')).toBe('true');

    list.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await el.updateComplete;
    expect((q(el, 'draft') as HTMLInputElement).value).toBe('invoices 2025');

    await focusDraft(el);
    expect(q(el, 'query-trail')).toBeNull(); // a non-empty draft does not reopen it
  });

  it('Escape from INSIDE the list closes it, in one press (§6c finding 9)', async () => {
    // The case above is named "…Escape returns to the input" and never pressed Escape, so this path
    // shipped broken: closing the trail returned focus to the composer, that fired a real focus
    // event, and `onDraftFocus` could not tell it from the user clicking into an empty box — so it
    // reopened the trail and the press appeared to do nothing. Two presses were needed, and the
    // first silently undid itself.
    pins = [{ id: 'p1', query: 'lease renewal' }, { id: 'p2', query: 'invoices 2025' }];
    const el = await mount();
    await focusDraft(el);
    expect(q(el, 'query-trail')).not.toBeNull();

    // Walk INTO the list — this is what makes the refocus real, and it is the state the old test
    // never reached before pressing Escape.
    await key(el, { key: 'ArrowDown' });
    await el.updateComplete;
    expect(all(el, 'trail-row')[0]?.getAttribute('aria-current')).toBe('true');

    const list = q(el, 'query-trail') as HTMLElement;
    list.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await el.updateComplete;

    expect(q(el, 'query-trail'), 'ONE press closes it').toBeNull();
    // …and the draft is untouched, so nothing was picked on the way out.
    expect((q(el, 'draft') as HTMLInputElement).value).toBe('');
  });

  it('a submitted query joins the trail; a typed-and-abandoned one does not', async () => {
    const el = await mount();
    await type(el, 'warehouse photos');
    await key(el, { key: 'Enter' });
    await type(el, 'never submitted');
    await type(el, '');
    await focusDraft(el);

    const rows = all(el, 'trail-row').map((r) => r.textContent?.trim());
    expect(rows).toContain('warehouse photos');
    expect(rows).not.toContain('never submitted');
  });
});

describe('818 SearchV2View — the keyboard pass (slice 5)', () => {
  it('⌥↓ walks the session index and brings the record into view', async () => {
    const scrolled: string[] = [];
    const el = await mount();
    await commit(el, 'what changed?');
    for (const node of [...(el.shadowRoot?.querySelectorAll('[data-record-id]') ?? [])]) {
      (node as HTMLElement).scrollIntoView = () => {
        scrolled.push(node.getAttribute('data-record-id') ?? '');
      };
    }
    (q(el, 'draft') as HTMLInputElement).blur();

    el.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, bubbles: true }),
    );
    await el.updateComplete;

    expect(q(el, 'index-node')?.getAttribute('aria-current')).toBe('true');
    expect(scrolled).toEqual(['r0']);
  });

  it('NEVER while typing — the same chord in the composer moves nothing', async () => {
    const el = await mount();
    await commit(el, 'what changed?');
    await type(el, 'a follow-up question');

    await key(el, { key: 'ArrowDown', altKey: true });
    expect(q(el, 'index-node')?.getAttribute('aria-current')).toBeNull();
  });

  it('the Escape ORDER: the trail closes, then the document, then the flip', async () => {
    pins = [{ id: 'p1', query: 'lease renewal' }];
    const el = await mount();
    await live(el, SEARCH_WITH_RESULTS);
    const card = q(el, 'live-results')?.querySelector('jf-results-card') as HTMLElement;
    card.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'd0' }, bubbles: true, composed: true }),
    );
    await type(el, ''); // an empty draft, so the trail opens beneath it
    await focusDraft(el);

    expect(q(el, 'query-trail')).not.toBeNull();
    await key(el, { key: 'Escape' });
    expect(q(el, 'query-trail')).toBeNull();
    expect(q(el, 'reading-pane')).not.toBeNull();

    await key(el, { key: 'Escape' });
    expect(q(el, 'reading-pane')).toBeNull();

    await type(el, 'northfield');
    (q(el, 'pill-alt') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(q(el, 'pill')?.className).toContain('flip');
    await key(el, { key: 'Escape' });
    expect(q(el, 'pill')?.className).not.toContain('flip');
  });

  it('Tab is not swallowed — the composer is not a keyboard trap (§6c finding 4)', async () => {
    // The case this replaces asserted DOM tab-order MEMBERSHIP — it queried for focusable elements
    // and checked none carried tabindex="-1" — and passed all the way through the trap, because
    // membership was never the problem. The composer's own handler called preventDefault() on Tab
    // in BOTH directions whenever a draft existed, so focus could not leave the field at all
    // (WCAG 2.1.2). Pressing the key is the only assertion that can tell the difference.
    const el = await mount();
    await commit(el, 'what changed?');
    await type(el, 'a draft, which is what used to spring the trap');

    for (const init of [{ key: 'Tab' }, { key: 'Tab', shiftKey: true }]) {
      const input = q(el, 'draft') as HTMLInputElement;
      const ev = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
      input.dispatchEvent(ev);
      await el.updateComplete;
      expect(
        ev.defaultPrevented,
        `${init.shiftKey ? 'Shift+Tab' : 'Tab'} must keep its native meaning`,
      ).toBe(false);
    }

    // …and the flip it used to be bound to is still reachable, as a real control.
    const alt = q(el, 'pill-alt') as HTMLButtonElement;
    expect(alt.tagName).toBe('BUTTON');
    expect(alt.getAttribute('aria-label')).toContain('instead');
    // The boundaries stay in the tab order too — the original claim, kept as the companion it is.
    for (const id of ['rail-grip', 'deck-grip', 'index-node', 'draft']) {
      expect(q(el, id), id).not.toBeNull();
      expect(q(el, id)?.getAttribute('tabindex')).not.toBe('-1');
    }
  });
});

/**
 * The STYLESHEET half of L7's compression rung (tempdoc 818 §6g C6).
 *
 * The policy above decides the deck's cap; these three declarations are what make the cap land on
 * the bodies instead of on the decision. They are asserted on the stylesheet rather than on
 * geometry because happy-dom performs no layout — the rendered proof is the live audit, recorded in
 * §5's per-law tier table. Together they are the invariant the live finding cost: being outside
 * every scroller stops the controls being SCROLLED away, `flex-shrink: 0` stops them being SQUEEZED
 * away, and the first was already true when the second was not.
 */
describe('818 SearchV2View — the deck yields at its bodies, never at its decisions (L7)', () => {
  /** The declarations of the LAST rule whose selector list ends with `selector`. */
  function block(selector: string): string {
    const css = SearchV2View.styles?.toString() ?? '';
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, 'g');
    const found = [...css.matchAll(re)].map((m) => m[1] ?? '');
    return found.join('\n');
  }

  it('the run CONTROLS are not a flex item that can shrink', () => {
    const controls = block('.run-controls');
    expect(controls, 'the .run-controls rule was not found — selector drifted').not.toBe('');
    expect(controls).toMatch(/flex:\s*0\s+0\s+auto/);
  });

  it('both deck BODIES shrink, and can scroll what is left', () => {
    // `.feed` appears in the shared body rule AND in its own; joining every match is what makes the
    // assertion about the CASCADED result rather than about whichever rule happened to come first.
    const feed = block('.feed');
    expect(feed, 'the .feed rules were not found — selector drifted').not.toBe('');
    expect(feed).toMatch(/flex:\s*1\s+1\s+auto/);
    expect(feed).toMatch(/overflow-y:\s*auto/);
    expect(feed).toMatch(/min-height:\s*0/);
  });

  it('the run region passes the pressure down to its feed rather than absorbing it', () => {
    expect(block('.run')).toMatch(/flex:\s*1\s+1\s+auto/);
  });
});
