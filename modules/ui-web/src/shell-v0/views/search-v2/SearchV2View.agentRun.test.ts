// @vitest-environment happy-dom

/**
 * The delegated-run laws of the Search v2 window (tempdoc 818 slice 3).
 *
 * Four laws, each testable only because the run is hosted rather than re-implemented:
 *
 *  - **L2** a run claims the ALT slot and nothing else — the primary destination is untouched, and
 *    ⌘↩ mid-run STEERS the live run instead of starting a second one.
 *  - **L7** decisions are incompressible — a held budget gate renders OUTSIDE every scroll container,
 *    and collapsing the one compressible occupant (the results list) cannot take it off screen.
 *  - **L8** the transcript records commitments: a delegate appends exactly one user-turn before the
 *    run, and the run's terminal appends exactly one receipt whose counts derive from what the run
 *    actually did.
 *  - **L9** the lock gates the SESSION, not a button: the delegate path runs the SAME refusal handler
 *    as the ask path, the draft survives, and nothing reaches the controller.
 *
 * The shared agent controller is mocked at the STORE boundary (`agentSessionStore`), the way the other
 * search-v2 cases mock stores — but `runControlIntent` is deliberately NOT mocked, so these cases
 * exercise the real `dispatchRunControl` seam and its lifecycle predicates against the fake run.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SearchV2View } from './SearchV2View.js';
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

const SEARCH: LiveSearchFixture = {
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

/** The fake shared controller — the observable surface the window projects, plus spy mutators. */
interface FakeCtrl {
  conversation: ConversationEntry[];
  toolCalls: Record<string, unknown>;
  streamingText: string;
  isStreaming: boolean;
  runInFlight: boolean;
  runKind: 'agent' | 'workflow' | 'background' | null;
  conversationId: string | null;
  iterationsUsed: number;
  toolCallsExecuted: number;
  totalTokensUsed: number | null;
  budgetUpdates: Array<Record<string, number>>;
  budgetGate: { tokensNeeded: number; tokensRemaining: number; totalTokensConsumed: number } | null;
  contextGate: { promptTokens: number; contextWindow: number } | null;
  sessionId: string | null;
  send: ReturnType<typeof vi.fn>;
  steer: ReturnType<typeof vi.fn>;
  cancelSession: ReturnType<typeof vi.fn>;
  resolveBudgetGate: ReturnType<typeof vi.fn>;
  resolveContextGate: ReturnType<typeof vi.fn>;
  raiseBudget: ReturnType<typeof vi.fn>;
  resumeSession: ReturnType<typeof vi.fn>;
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
    toolCallsExecuted: 0,
    totalTokensUsed: null,
    budgetUpdates: [],
    budgetGate: null,
    contextGate: null,
    sessionId: 'run-1',
    send: vi.fn(async () => {}),
    steer: vi.fn(async () => true),
    cancelSession: vi.fn(async () => {}),
    resolveBudgetGate: vi.fn(async () => true),
    resolveContextGate: vi.fn(async () => true),
    raiseBudget: vi.fn(async () => true),
    resumeSession: vi.fn(async () => {}),
  };
}

let ctrl: FakeCtrl = makeCtrl();
let agentListener: (() => void) | null = null;
let searchListener: ((s: LiveSearchFixture) => void) | null = null;
let aiListener: ((s: unknown) => void) | null = null;
const getCtrlMock = vi.fn((_apiBase: string) => ctrl as unknown as AgentSessionController);

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: (base: string) => getCtrlMock(base),
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
  submitSearch: vi.fn(),
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

/** Drive the reconciliation seam directly: happy-dom defines ResizeObserver but never fires it. */
function reconcile(el: Mounted): void {
  (el as unknown as { reconcileBoundaries(): void }).reconcileBoundaries();
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

async function key(el: Mounted, init: KeyboardEventInit): Promise<void> {
  const input = q(el, 'draft') as HTMLInputElement;
  input.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
  await el.updateComplete;
}

/** The controller moved — fan its one notification out the way the shared store does. */
async function notify(el: Mounted): Promise<void> {
  agentListener?.();
  await el.updateComplete;
}

/** Put the fake controller into the state the backend puts it in once a run is streaming. */
async function runStarts(el: Mounted): Promise<void> {
  ctrl.isStreaming = true;
  ctrl.runInFlight = true;
  ctrl.runKind = 'agent';
  await notify(el);
}

/** …and the state its stream's `finally` leaves it in at the terminal. */
async function runEnds(el: Mounted): Promise<void> {
  ctrl.isStreaming = false;
  ctrl.runInFlight = false;
  await notify(el);
}

function entry(over: Partial<ConversationEntry>): ConversationEntry {
  return {
    id: `e-${ctrl.conversation.length}`,
    type: 'progress',
    content: '',
    timestamp: 0,
    ...over,
  } as ConversationEntry;
}

beforeEach(() => {
  document.body.innerHTML = '';
  ctrl = makeCtrl();
  getCtrlMock.mockClear();
});

describe('818 SearchV2View — delegating a run (L2, L8)', () => {
  it('L2 — ⌘↩ on a draft delegates through the ONE control-intent seam', async () => {
    const el = await mount();
    await type(el, 'File the 2025 supplier agreements');
    await key(el, { key: 'Enter', metaKey: true });

    expect(ctrl.send).toHaveBeenCalledTimes(1);
    expect(ctrl.send).toHaveBeenCalledWith('File the 2025 supplier agreements');
    expect(ctrl.steer).not.toHaveBeenCalled();
    // The run is stamped with THIS window's session, so its thread events land under this session.
    expect(ctrl.conversationId).toBe('sv2-test-session');
    // The draft is consumed and the flip lens dies with it.
    expect((q(el, 'draft') as HTMLInputElement).value).toBe('');
  });

  it('L2 — a run in flight claims the ALT slot and leaves the PRIMARY destination untouched', async () => {
    const el = await mount();
    await type(el, 'northfield renewal');
    expect(text(el, 'pill')).toContain('SEARCH');
    expect(text(el, 'pill-alt')).toContain('ASK');

    await runStarts(el);

    // Only the alt slot moved — ASK/SEARCH is still what ⏎ and ⇧⏎ mean, so ASK stays reachable.
    expect(text(el, 'pill')).toContain('SEARCH');
    expect(text(el, 'pill-alt')).toContain('STEER');
    expect(text(el, 'pill-alt')).toContain('⌘⏎');
  });

  it('L2 — ⌘↩ mid-run STEERS the live run; it never starts a second one', async () => {
    const el = await mount();
    await runStarts(el);
    await type(el, 'skip anything before 2023');
    await key(el, { key: 'Enter', metaKey: true });

    expect(ctrl.steer).toHaveBeenCalledTimes(1);
    expect(ctrl.steer).toHaveBeenCalledWith('skip anything before 2023');
    expect(ctrl.send).not.toHaveBeenCalled();
    // A steer is not a commitment: it appends no record.
    expect(q(el, 'transcript')).toBeNull();
  });

  it('L8 — the delegate appends exactly ONE record before the run, and the live feed adds none', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });

    expect(all(el, 'turn')).toHaveLength(1);
    expect(text(el, 'turn')).toBe('file the agreements');
    expect(text(el, 'index-count')).toBe('1 entries');

    // The run then narrates itself at length — and the transcript stays at one record, because the
    // feed is attention, not commitment.
    ctrl.conversation = [
      entry({ type: 'user', content: 'file the agreements' }),
      entry({ type: 'progress', content: 'Reading /Downloads' }),
      entry({ type: 'assistant-text', content: 'Found 41 files.' }),
    ];
    ctrl.streamingText = 'Classifying…';
    await runStarts(el);

    expect(all(el, 'turn')).toHaveLength(1);
    expect(text(el, 'index-count')).toBe('1 entries');
    expect(q(el, 'run-feed')).not.toBeNull();
    expect(text(el, 'run-streaming')).toBe('Classifying…');
    // The user's own turn is NOT re-rendered in the feed — the transcript already holds it.
    expect(all(el, 'run-text').map((n) => n.textContent?.trim())).toEqual(['Found 41 files.']);
    expect(text(el, 'run-line')).toContain('Reading /Downloads');
  });

  it('L8 — the run’s terminal appends exactly ONE receipt whose counts derive from the run', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });

    ctrl.toolCalls = {
      c1: { callId: 'c1', toolName: 'list_files', arguments: '{}', risk: 'LOW', status: 'completed' },
      c2: { callId: 'c2', toolName: 'rename', arguments: '{}', risk: 'MEDIUM', status: 'completed' },
    };
    ctrl.conversation = [
      entry({ type: 'user', content: 'file the agreements' }),
      entry({ type: 'tool-call-group', content: '', callIds: ['c1', 'c2'] }),
    ];
    ctrl.totalTokensUsed = 41_200;
    await runStarts(el);
    expect(all(el, 'run-tool-card')).toHaveLength(2);
    expect(q(el, 'agent-run')).toBeNull(); // nothing recorded while the run is still live

    await runEnds(el);

    const receipts = all(el, 'agent-run');
    expect(receipts).toHaveLength(1);
    expect(receipts[0]?.getAttribute('data-outcome')).toBe('completed');
    // L14 — the receipt's RESTING form is its own text nodes: the outcome and the counts. The run's
    // end time is elaboration and sits in the `.ext` child, so it is read separately here.
    const resting = Array.from(receipts[0]?.childNodes ?? [])
      .filter((n) => n.nodeType === 3)
      .map((n) => n.textContent ?? '')
      .join('')
      .replace(/\s+/g, ' ')
      .trim();
    expect(resting).toBe(`Run finished · 2 tool calls · ${(41_200).toLocaleString()} tokens`);
    expect(receipts[0]?.querySelector('[data-testid="agent-run-timing"]')?.textContent).toContain(
      'just now',
    );
    // Two records now: the turn and its receipt — and the earlier turn is untouched (L4).
    expect(text(el, 'index-count')).toBe('2 entries');
    expect(text(el, 'turn')).toBe('file the agreements');
    // The feed is gone with the run; the receipt is what remains.
    expect(q(el, 'run-region')).toBeNull();
  });

  it('L8 — a halted run is recorded as halted, and an errored one as an error', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    await runStarts(el);

    (q(el, 'run-halt') as HTMLButtonElement).click();
    expect(ctrl.cancelSession).toHaveBeenCalledTimes(1);
    await runEnds(el);
    expect(q(el, 'agent-run')?.getAttribute('data-outcome')).toBe('halted');
    expect(text(el, 'agent-run')).toContain('Run halted by you');

    // A second run that ends with an error entry records THAT, not a completion.
    await type(el, 'try again');
    await key(el, { key: 'Enter', metaKey: true });
    ctrl.conversation = [
      ...ctrl.conversation,
      entry({ type: 'error', content: 'the model went away' }),
    ];
    await runStarts(el);
    await runEnds(el);
    expect(all(el, 'agent-run').map((r) => r.getAttribute('data-outcome'))).toEqual([
      'halted',
      'error',
    ]);
  });

  it('a run started by the sibling window is steerable but leaves NO receipt in this session', async () => {
    const el = await mount();
    // Nothing was delegated here; the shared controller is simply live.
    await runStarts(el);
    expect(q(el, 'run-region')).toBeNull();
    expect(text(el, 'pill-alt')).toContain('STEER');

    await runEnds(el);
    expect(q(el, 'agent-run')).toBeNull();
    expect(q(el, 'transcript')).toBeNull();
  });
});

describe('818 SearchV2View — the deck stack (L7)', () => {
  it('L7 — a held budget gate renders OUTSIDE every scrollable container', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    ctrl.budgetGate = { tokensNeeded: 8_000, tokensRemaining: 1_200, totalTokensConsumed: 28_800 };
    await runStarts(el);

    const gate = q(el, 'budget-gate');
    const controls = q(el, 'run-controls');
    const list = q(el, 'live-results');
    const feed = q(el, 'run-feed');
    expect(gate).not.toBeNull();
    expect(controls).not.toBeNull();

    // The two BODIES are the scroll containers…
    expect(list?.getAttribute('data-scrollable')).toBe('true');
    expect(feed?.getAttribute('data-scrollable')).toBe('true');
    // …and the decision lives outside both, so it cannot be SCROLLED off screen.
    expect(list?.contains(gate as Node)).toBe(false);
    expect(feed?.contains(gate as Node)).toBe(false);
    expect(controls?.contains(gate as Node)).toBe(true);
    expect(gate?.closest('[data-scrollable="true"]')).toBeNull();
    expect(controls?.closest('[data-scrollable="true"]')).toBeNull();

    // Being outside every scroller is only HALF of L7, and the half this test used to assert alone
    // — which is why it stayed green through §6c finding 3, where the decision left the screen by
    // being CLIPPED rather than scrolled. The other half is that when the column runs out of room
    // it is a BODY that yields, never the decision. Asserted here on the same held gate, because a
    // held decision is exactly the case where getting it wrong costs the most.
    reconcile(el);
    await el.updateComplete;
    expect(q(el, 'run-controls'), 'the controls survive any amount of pressure').not.toBeNull();
    expect(q(el, 'budget-gate'), 'so does the decision they carry').not.toBeNull();
  });

  /**
   * L7 as amended (818 §6e.4) — the yield ORDER, at the view. `deckSizing.test.ts` owns the
   * arithmetic; this owns the consequence: which elements are actually on screen once the column
   * cannot hold everything. The decisions are absent from the list of things that can yield.
   */
  it('L7 — under pressure the BODIES take their minimum honest form and the decision does not', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    ctrl.budgetGate = { tokensNeeded: 8_000, tokensRemaining: 1_200, totalTokensConsumed: 28_800 };
    await runStarts(el);

    // A column with no room at all: the most pressure the window can be under.
    const centre = el.shadowRoot?.querySelector('.centre') as HTMLElement;
    centre.getBoundingClientRect = (() => ({ width: 800, height: 200, top: 0, left: 0 })) as never;
    reconcile(el);
    await el.updateComplete;

    // Both bodies have taken their minimum honest form — and each still STATES what it stands for
    // rather than vanishing (L7: a minimum honest form may drop rows, never a fact).
    expect(q(el, 'live-results'), 'the list body yields its rows').toBeNull();
    expect(q(el, 'live-count'), 'and states its count instead').not.toBeNull();
    expect(q(el, 'run-feed'), 'the feed body yields its steps').toBeNull();
    expect(q(el, 'run-feed-collapsed'), 'and states what the run has done').not.toBeNull();

    // The decision is untouched. This is the assertion finding 3 was made of.
    expect(q(el, 'run-controls')).not.toBeNull();
    expect(q(el, 'budget-gate')).not.toBeNull();
    expect(q(el, 'run-halt')).not.toBeNull();
  });

  it('L7 — collapsing the ONE compressible occupant keeps the decision on screen', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    ctrl.contextGate = { promptTokens: 7_400, contextWindow: 8_192 };
    await runStarts(el);

    (q(el, 'list-collapse') as HTMLButtonElement).click();
    await el.updateComplete;

    // The list body is gone, but its minimum honest form still names what the deck holds (L6).
    expect(q(el, 'live-results')).toBeNull();
    expect(text(el, 'live-count')).toContain('Top 2 of 12 matches');
    // The decision and the controls are untouched by the collapse.
    expect(q(el, 'context-gate')).not.toBeNull();
    expect(q(el, 'run-controls')).not.toBeNull();
    expect(q(el, 'run-halt')).not.toBeNull();
  });

  it('L7 — every control dispatches through the ONE seam, and the gates resolve their own decision', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    ctrl.iterationsUsed = 4;
    // The status line's tool count is the run's OWN observed calls (the cards the feed rendered),
    // the same number the receipt will carry — not a separately-maintained controller counter, which
    // is set here to a contradicting value on purpose.
    ctrl.toolCallsExecuted = 99;
    ctrl.toolCalls = {
      c1: { callId: 'c1', toolName: 'list_files', arguments: '{}', risk: 'LOW', status: 'completed' },
    };
    ctrl.conversation = [entry({ type: 'tool-call-group', content: '', callIds: ['c1'] })];
    ctrl.budgetUpdates = [
      { phase: 0, tokensConsumed: 900, tokensRemaining: 4_000, totalTokensConsumed: 6_000 },
    ] as unknown as Array<Record<string, number>>;
    ctrl.budgetGate = { tokensNeeded: 8_000, tokensRemaining: 1_200, totalTokensConsumed: 28_800 };
    await runStarts(el);

    // The step line and the econ meter are both derived, never authored here.
    expect(text(el, 'run-status')).toBe('Step 4 · 1 tool call · holding for your decision');
    // (the ceiling is locale-formatted — assert the derived percentage, not the separator)
    expect(text(el, 'run-budget')).toBe(`Budget 60% of ${(10_000).toLocaleString()} tokens`);

    const steer = q(el, 'steer-input') as HTMLInputElement;
    steer.value = 'only 2025 files';
    steer.dispatchEvent(new Event('input', { bubbles: true }));
    (q(el, 'run-steer') as HTMLButtonElement).click();
    expect(ctrl.steer).toHaveBeenCalledWith('only 2025 files');

    (q(el, 'budget-gate-finalize') as HTMLButtonElement).click();
    expect(ctrl.resolveBudgetGate).toHaveBeenCalledWith('finalize');

    ctrl.budgetGate = null;
    ctrl.contextGate = { promptTokens: 7_400, contextWindow: 8_192 };
    await notify(el);
    expect(q(el, 'budget-gate')).toBeNull();
    (q(el, 'context-gate-summarize') as HTMLButtonElement).click();
    expect(ctrl.resolveContextGate).toHaveBeenCalledWith('summarize');
  });

  it('L7 — a control is only offered when the seam’s own predicate says it can be honoured', async () => {
    const el = await mount();
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    // A WORKFLOW run is live but not steerable (no drain) — the affordance must not be there.
    ctrl.isStreaming = true;
    ctrl.runInFlight = true;
    ctrl.runKind = 'workflow';
    await notify(el);

    expect(q(el, 'run-controls')).not.toBeNull();
    expect(q(el, 'steer-input')).toBeNull();
    expect(q(el, 'run-halt')).not.toBeNull();
  });
});

describe('818 SearchV2View — the session lock on the agent path (L9)', () => {
  it('L9 — the delegate path runs the SAME refusal handler and never reaches the controller', async () => {
    const refuse = vi.spyOn(
      SearchV2View.prototype as unknown as {
        refuseLocked: (t: string, id: string | null) => void;
      },
      'refuseLocked',
    );
    const el = await mount();
    aiListener?.({
      status: { conversationProtection: { state: 'locked' } },
      runtime: { contextWindow: null },
    });
    await el.updateComplete;

    const draft = '  file the agreements  ';
    await type(el, draft);
    await key(el, { key: 'Enter', metaKey: true });

    // ONE refusal handler, not a per-path branch…
    expect(refuse).toHaveBeenCalledTimes(1);
    // …no dispatch reached the run controller…
    expect(getCtrlMock).not.toHaveBeenCalled();
    expect(ctrl.send).not.toHaveBeenCalled();
    // …the draft is the user's and comes back verbatim…
    expect((q(el, 'draft') as HTMLInputElement).value).toBe(draft);
    // …the refusal names its exits…
    expect(q(el, 'lock-refusal')).not.toBeNull();
    expect(q(el, 'lock-exit-new')).not.toBeNull();
    // …both send buttons carry the same hint, so neither rung promises a send it cannot make…
    expect((q(el, 'delegate') as HTMLButtonElement).disabled).toBe(true);
    expect((q(el, 'commit') as HTMLButtonElement).disabled).toBe(true);
    // …and NOTHING was recorded: a turn that never entered the transcript leaves no history.
    expect(q(el, 'transcript')).toBeNull();
    expect(text(el, 'session-name')).toBe('New session');

    // The ask path, refused before dispatch, lands in exactly the same handler.
    await key(el, { key: 'Enter', shiftKey: true });
    expect(refuse).toHaveBeenCalledTimes(2);
    refuse.mockRestore();
  });

  it('L9 — an unlocked session delegates normally again, with the refusal cleared', async () => {
    const el = await mount();
    aiListener?.({
      status: { conversationProtection: { state: 'locked' } },
      runtime: { contextWindow: null },
    });
    await el.updateComplete;
    await type(el, 'file the agreements');
    await key(el, { key: 'Enter', metaKey: true });
    expect(q(el, 'lock-refusal')).not.toBeNull();

    aiListener?.({
      status: { conversationProtection: { state: 'unlocked' } },
      runtime: { contextWindow: null },
    });
    await el.updateComplete;
    expect(q(el, 'lock-refusal')).toBeNull();

    await key(el, { key: 'Enter', metaKey: true });
    expect(ctrl.send).toHaveBeenCalledWith('file the agreements');
  });
});
