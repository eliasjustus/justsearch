// @vitest-environment happy-dom

/**
 * The delegate tier of the Search v3 window (tempdoc 822 Phase F2).
 *
 * The shared agent controller is mocked at the STORE boundary (`agentSessionStore`), the way
 * search-v2's run cases do — but `runControlIntent` is deliberately NOT mocked, so every case here
 * exercises the REAL `dispatchRunControl` seam and its lifecycle predicates against a fake run. A
 * hand-rolled directive would therefore not merely be un-asserted; it would take a different code
 * path than the one the steering register governs.
 *
 * The properties asserted as MECHANISMS rather than appearances:
 *  - **Routing.** Enter reaches the ask fetch; Ctrl+Enter reaches the controller and NOT the fetch.
 *    Each case asserts the other channel is untouched, so a send that fired both would fail.
 *  - **The slot holds exactly one control.** Every rung asserts the other three are absent.
 *  - **A typed prompt is resolved by its own command.** The chat-text path is asserted to dispatch
 *    NOTHING while a prompt is held — that is the probe, not the presence of a button.
 *  - **The receipt derives from the feed.** The rendered card count is read from the DOM and compared
 *    with the receipt's number, so a feed that rendered fewer than it counted fails.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type {
  AgentSessionController,
  ConversationEntry,
  ToolCall,
} from '../../controllers/AgentSessionController.js';

/** The observable surface the window projects, plus the spies the seam should reach. */
interface FakeCtrl {
  conversation: ConversationEntry[];
  toolCalls: Record<string, ToolCall>;
  streamingText: string;
  isStreaming: boolean;
  runInFlight: boolean;
  runKind: 'agent' | 'workflow' | 'background' | null;
  conversationId: string | null;
  sessionId: string | null;
  iterationsUsed: number;
  budgetGate: { tokensNeeded: number; tokensRemaining: number; totalTokensConsumed: number } | null;
  contextGate: { promptTokens: number; contextWindow: number } | null;
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
    sessionId: null,
    iterationsUsed: 0,
    budgetGate: null,
    contextGate: null,
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
let ctrlExists = false;
let agentListener: (() => void) | null = null;
const getCtrl = vi.fn(() => {
  ctrlExists = true;
  return ctrl as unknown as AgentSessionController;
});

vi.mock('../../state/agentSessionStore.js', () => ({
  getAgentSessionController: () => getCtrl(),
  peekAgentSessionController: () => (ctrlExists ? (ctrl as unknown as AgentSessionController) : null),
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
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { RUN_DISPATCHING } from './fixtures.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** The observed state in which BOTH tiers are genuinely available — the window's own precondition. */
function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

beforeEach(() => {
  ctrl = makeCtrl();
  ctrlExists = false;
  getCtrl.mockClear();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, body: null });
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const q = (host: Mounted, testid: string): HTMLElement | null =>
  host.shadowRoot?.querySelector(`[data-testid="${testid}"]`) ?? null;

const all = (host: Mounted, testid: string): HTMLElement[] => [
  ...(host.shadowRoot?.querySelectorAll<HTMLElement>(`[data-testid="${testid}"]`) ?? []),
];

async function type(el: Mounted, draft: string): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  return field;
}

/** Press Enter, optionally with the delegate modifier — the keys a reader actually has. */
async function press(el: Mounted, opts: { ctrlKey?: boolean; shiftKey?: boolean } = {}): Promise<void> {
  const field = (await region(el, 'jf-sv3-composer')).shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  field?.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, ...opts }),
  );
  await settle(el);
}

/** The controller moved: push the frame, then notify exactly as the shared store would. */
async function frame(el: Mounted, patch: Partial<FakeCtrl>): Promise<void> {
  Object.assign(ctrl, patch);
  agentListener?.();
  await settle(el);
}

const toolCall = (callId: string, over: Partial<ToolCall> = {}): ToolCall => ({
  callId,
  toolName: 'core_search',
  arguments: '{}',
  risk: 'LOW',
  status: 'completed',
  ...over,
});

const groupEntry = (id: string, callIds: string[]): ConversationEntry => ({
  id,
  type: 'tool-call-group',
  content: '',
  callIds,
  timestamp: 0,
});

/** Delegate a draft and drive the run to LIVE with the given tool calls in its feed. */
async function delegateWithCalls(el: Mounted, draft: string, callIds: string[]): Promise<void> {
  await type(el, draft);
  await press(el, { ctrlKey: true });
  await frame(el, {
    runInFlight: true,
    runKind: 'agent',
    sessionId: 'run-1',
    conversation: [
      { id: 'u1', type: 'user', content: draft, timestamp: 0 },
      groupEntry('g1', callIds),
    ],
    toolCalls: Object.fromEntries(callIds.map((id) => [id, toolCall(id)])),
  });
}

describe('the composer routes by KEY, and each key reaches exactly one tier', () => {
  it('sends plain Enter to the ask tier and nowhere near the run controller', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'why did the renewal fail?');
    await press(el);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/chat/dispatch');
    expect(getCtrl).not.toHaveBeenCalled();
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('delegates on Ctrl+Enter through the seam, with no ask dispatch at all', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'clean up the vendor folder');
    await press(el, { ctrlKey: true });

    expect(ctrl.send).toHaveBeenCalledTimes(1);
    expect(ctrl.send).toHaveBeenCalledWith('clean up the vendor folder');
    // The delegated turn is stamped with THIS window's conversation, not with whatever ran last.
    expect(ctrl.conversationId).toBe('sv3-session-1');
    expect(fetchMock).not.toHaveBeenCalled();
    // ...and it opened an AGENT turn in the transcript, in the same session a plain ask would use.
    const main = await region(el, 'jf-sv3-main');
    const turns = all(main, 'sv3-turn');
    expect(turns).toHaveLength(1);
    expect(turns[0]?.dataset.kind).toBe('agent');
  });

  it('leaves Shift+Enter to the field on BOTH tiers', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'a line');
    await press(el, { shiftKey: true });
    await press(el, { shiftKey: true, ctrlKey: true });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(ctrl.send).not.toHaveBeenCalled();
  });

  it('says which key does what in the send slot itself, and adds no chrome to do it', async () => {
    aiOnline();
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    // An EMPTY draft routes nowhere, so the control explains nothing and carries no tooltip: a
    // `title` on a disabled element is suppressed by the browser, and an unreachable reason is worse
    // than none (596 face 1.1).
    expect(q(composer, 'sv3-composer-send')?.hasAttribute('title')).toBe(false);

    await type(el, 'something to route');
    const send = q(composer, 'sv3-composer-send');
    expect((send as HTMLButtonElement).disabled).toBe(false);
    expect(send?.getAttribute('aria-label')).toContain('Ctrl+Enter');
    expect(send?.getAttribute('title')).toBe(send?.getAttribute('aria-label'));
    // The routing lives ON the control. No banner, no mode switch, no second field.
    expect(q(composer, 'sv3-composer-notice')).toBeNull();
    expect(
      composer.shadowRoot?.querySelectorAll('textarea, input').length,
    ).toBe(1);
  });
});

describe('the primary slot is a strict-priority state machine in the DOM', () => {
  const slotIds = [
    'sv3-composer-answer',
    'sv3-composer-stop',
    'sv3-composer-send',
  ] as const;

  const occupants = (composer: Mounted): string[] =>
    slotIds.filter((id) => q(composer, id) !== null);

  it('holds Send alone when nothing is running', async () => {
    aiOnline();
    const el = await mount();
    expect(occupants(await region(el, 'jf-sv3-composer'))).toEqual(['sv3-composer-send']);
  });

  it('holds Stop alone while the run is live — Send is NOT rendered', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    const composer = await region(el, 'jf-sv3-composer');
    expect(occupants(composer)).toEqual(['sv3-composer-stop']);
    expect(composer.shadowRoot?.querySelector('button.send')).toBeNull();
  });

  it('holds Answer alone while a typed prompt is held — over the LIVE run below it', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, { budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 } });
    const composer = await region(el, 'jf-sv3-composer');
    // The run is still live, so this is the PRIORITY that is being asserted, not mere presence.
    expect(ctrl.runInFlight).toBe(true);
    expect(occupants(composer)).toEqual(['sv3-composer-answer']);

    // The rung does the one thing it honestly can: it takes the reader to the decision. It cannot
    // resolve it — the dedicated controls in the prompt block are the only things that can.
    (q(composer, 'sv3-composer-answer') as HTMLButtonElement).click();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.activeElement).toBe(q(main, 'sv3-run-budget-finalize'));
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();
  });

  it('falls to the follow-up rung once the run has ended, over a bare Send', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, { runInFlight: false, isStreaming: false });
    const composer = await region(el, 'jf-sv3-composer');
    expect(occupants(composer)).toEqual(['sv3-composer-send']);
    // Same control, different promise: with something to send, the reason says the message
    // CONTINUES this conversation rather than opening one.
    await type(el, 'and the archive?');
    expect(q(composer, 'sv3-composer-send')?.getAttribute('aria-label')).toContain('follow-up');
  });
});

describe('a typed prompt is resolved by its OWN command, never by chat text', () => {
  it('dispatches NOTHING when the reader types and sends while a decision is held', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    ctrl.send.mockClear();
    ctrl.steer.mockClear();

    await type(el, 'yes go ahead');
    await press(el);
    await press(el, { ctrlKey: true });

    // No new run, no steer, no ask — and the gate is still held, because nothing resolved it.
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(ctrl.steer).not.toHaveBeenCalled();
    expect(ctrl.resolveBudgetGate).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-run-prompt')?.dataset.kind).toBe('budget');
  });

  it('resolves the budget gate through the dedicated control, via the seam', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      budgetGate: { tokensNeeded: 500, tokensRemaining: 10, totalTokensConsumed: 90 },
    });
    const main = await region(el, 'jf-sv3-main');
    (q(main, 'sv3-run-budget-finalize') as HTMLButtonElement | null)?.click();
    await settle(el);
    expect(ctrl.resolveBudgetGate).toHaveBeenCalledWith('finalize');
  });

  it('renders a held tool call as a typed prompt WITHOUT a second approve/deny of its own', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'do the thing', []);
    await frame(el, {
      conversation: [
        { id: 'u1', type: 'user', content: 'do the thing', timestamp: 0 },
        groupEntry('g1', ['c1']),
      ],
      toolCalls: { c1: toolCall('c1', { status: 'pending', toolName: 'core_write', risk: 'HIGH' }) },
    });
    const main = await region(el, 'jf-sv3-main');
    const prompt = q(main, 'sv3-run-prompt');
    expect(prompt?.dataset.kind).toBe('approval');
    expect(prompt?.textContent).toContain('core_write');
    // The product has ONE approve/deny ceremony (`operations/authorizationBroker.ts`). A window-local
    // pair here would be the retired per-card buttons coming back under a new name.
    expect(prompt?.querySelectorAll('button')).toHaveLength(0);
  });
});

describe('the run ends in ONE receipt whose counts come from the feed it summarises', () => {
  it('counts exactly the cards the feed rendered', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1', 'c2', 'c3']);

    const main = await region(el, 'jf-sv3-main');
    const rendered = all(main, 'sv3-run-tool').length;
    expect(rendered).toBe(3);

    await frame(el, { runInFlight: false, isStreaming: false });
    const receipts = all(main, 'sv3-run-receipt');
    expect(receipts).toHaveLength(1);
    // THE probe: the number in the receipt is the number of cards that were on screen.
    expect(receipts[0]?.textContent?.trim()).toBe(`${rendered} tool calls · finished`);
    expect(receipts[0]?.dataset.outcome).toBe('complete');
    // The live feed is attention, not record: it ends with the run.
    expect(q(main, 'sv3-run-feed')).toBeNull();
  });

  it('says stopped-by-you when the reader halted it, and keeps the counts honest', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1', 'c2']);
    const composer = await region(el, 'jf-sv3-composer');
    (q(composer, 'sv3-composer-stop') as HTMLButtonElement | null)?.click();
    await settle(el);
    expect(ctrl.cancelSession).toHaveBeenCalledTimes(1);

    await frame(el, { runInFlight: false, isStreaming: false });
    const main = await region(el, 'jf-sv3-main');
    const receipt = q(main, 'sv3-run-receipt');
    expect(receipt?.textContent?.trim()).toBe('2 tool calls · stopped by you');
    // A halt is the reader's own act: it is an outcome, not a break.
    expect(receipt?.dataset.broken).toBe('false');
  });

  it('is not re-written by a LATER run on the shared controller', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', ['c1']);
    await frame(el, { runInFlight: false, isStreaming: false });
    const main = await region(el, 'jf-sv3-main');
    expect(q(main, 'sv3-run-receipt')?.textContent?.trim()).toBe('1 tool call · finished');

    // The controller is product-wide: someone else's run now starts, makes three calls, and ends.
    // Its numbers must not reach this window's concluded turn — the wrong-origin class the explicit
    // activeTurnId + the one-terminal rule exist to prevent.
    await frame(el, {
      runInFlight: true,
      sessionId: 'run-2',
      conversation: [
        { id: 'u1', type: 'user', content: 'audit the folder', timestamp: 0 },
        groupEntry('g1', ['c1']),
        groupEntry('g2', ['c2', 'c3', 'c4']),
      ],
      toolCalls: {
        c1: toolCall('c1'),
        c2: toolCall('c2'),
        c3: toolCall('c3'),
        c4: toolCall('c4'),
      },
    });
    await frame(el, { runInFlight: false, isStreaming: false });

    expect(all(main, 'sv3-run-receipt')).toHaveLength(1);
    expect(q(main, 'sv3-run-receipt')?.textContent?.trim()).toBe('1 tool call · finished');
  });
});

describe('the optimistic handoff yields to server truth exactly once', () => {
  it('shows the local echo until the server produces something, then never again', async () => {
    aiOnline();
    const el = await mount();
    await type(el, 'do the thing');
    await press(el, { ctrlKey: true });
    const main = await region(el, 'jf-sv3-main');

    // The controller echoes the reader's own prompt and sets its flags optimistically. Neither is
    // the server speaking, so the window is still SENDING.
    await frame(el, {
      isStreaming: true,
      conversation: [{ id: 'u1', type: 'user', content: 'do the thing', timestamp: 0 }],
    });
    expect(q(main, 'sv3-run-echo')?.textContent?.trim()).toBe(RUN_DISPATCHING);
    expect(q(main, 'sv3-run-feed')).toBeNull();

    await frame(el, { runInFlight: true, sessionId: 'run-1', streamingText: 'Looking…' });
    expect(q(main, 'sv3-run-echo')).toBeNull();
    expect(q(main, 'sv3-run-feed')).not.toBeNull();

    // Latched: a frame in which the evidence disappears does not put the window back into sending.
    await frame(el, { streamingText: '', sessionId: null });
    expect(q(main, 'sv3-run-echo')).toBeNull();
  });
});

describe('a mid-run submit JOINS the live turn instead of starting a second one', () => {
  it('steers through the seam, and starts no competing run on either key', async () => {
    aiOnline();
    const el = await mount();
    await delegateWithCalls(el, 'audit the folder', []);
    ctrl.send.mockClear();

    await type(el, 'skip the archive');
    await press(el);
    expect(ctrl.steer).toHaveBeenCalledWith('skip the archive');
    expect(ctrl.send).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();

    await type(el, 'and the drafts too');
    await press(el, { ctrlKey: true });
    expect(ctrl.steer).toHaveBeenLastCalledWith('and the drafts too');
    expect(ctrl.send).not.toHaveBeenCalled();
    // One turn, not three: a steer is a directive, not a commitment.
    const main = await region(el, 'jf-sv3-main');
    expect(all(main, 'sv3-turn')).toHaveLength(1);
  });

  it('refuses a mid-ASK submit, because an ask stream has no steer channel', async () => {
    aiOnline();
    const el = await mount();
    // Hold the ask open: the stub never resolves a body, so the turn stays streaming.
    fetchMock.mockImplementation(
      () => new Promise(() => {}) as unknown as Promise<Response>,
    );
    await type(el, 'why did it fail?');
    await press(el);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await type(el, 'actually, never mind');
    await press(el);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(ctrl.steer).not.toHaveBeenCalled();
  });
});

describe('the sidebar spends act-now only while a typed prompt is pending', () => {
  it('moves the run session through in-motion, act-now, and back to resting', async () => {
    aiOnline();
    const el = await mount();
    const statusOf = async (): Promise<string | undefined> => {
      const sidebar = await region(el, 'jf-sv3-sidebar');
      const row = sidebar.shadowRoot?.querySelector('jf-sv3-session-row') as HTMLElement | null;
      return row?.getAttribute('status') ?? undefined;
    };

    await delegateWithCalls(el, 'audit the folder', []);
    expect(await statusOf()).toBe('in-motion');

    await frame(el, { budgetGate: { tokensNeeded: 5, tokensRemaining: 1, totalTokensConsumed: 9 } });
    expect(await statusOf()).toBe('act-now');

    await frame(el, { budgetGate: null });
    expect(await statusOf()).toBe('in-motion');

    await frame(el, { runInFlight: false, isStreaming: false });
    expect(await statusOf()).toBe('resting');
  });
});

describe('presence: a live run this window never dispatched (tempdoc 822 Phase F3)', () => {
  /**
   * The REGRESSION for F2's named finding — *window-local in-memory sessions orphan a live run on
   * reload*: a fresh window instance showed zero sessions while the run went on holding server-side.
   * The window is re-mounted here next to a controller that is already running, which is exactly the
   * shape of that incident, and the assertions are the three things the reader lost: the run is
   * VISIBLE, the composer slot is HONEST about it, and claiming it shows the feed.
   */
  const alreadyRunning = (over: Partial<FakeCtrl> = {}): void => {
    ctrlExists = true;
    Object.assign(ctrl, {
      runInFlight: true,
      runKind: 'agent',
      sessionId: 'run-42',
      conversation: [
        { id: 'u1', type: 'user', content: 'index the vendor folder', timestamp: 0 },
        { id: 'a1', type: 'assistant-text', content: 'Reading it now.', timestamp: 0 },
        groupEntry('g1', ['c1']),
      ] as ConversationEntry[],
      toolCalls: { c1: toolCall('c1') },
      ...over,
    });
  };

  const rowsOf = async (el: Mounted): Promise<HTMLElement[]> => {
    const sidebar = await region(el, 'jf-sv3-sidebar');
    return [...(sidebar.shadowRoot?.querySelectorAll<HTMLElement>('jf-sv3-session-row') ?? [])];
  };

  it('gives the run a session on the Active shelf, titled with its own task', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);

    const rows = await rowsOf(el);
    expect(rows.map((r) => (r as HTMLElement & { label: string }).label)).toEqual([
      'index the vendor folder',
    ]);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(
      [...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? [])].map(
        (n) => n.textContent?.trim(),
      ),
    ).toEqual(['Active']);
    // It is not CLAIMED: the reader was not moved into a conversation they did not open.
    expect((rows[0] as HTMLElement & { active: boolean }).active).toBe(false);
  });

  it('keeps the composer slot honest about a run the local list did not start', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);
    const composer = await region(el, 'jf-sv3-composer');
    // Stop, not Send — the window cannot offer to start something while the product is running one.
    expect(q(composer, 'sv3-composer-stop')).not.toBeNull();
    expect(q(composer, 'sv3-composer-send')).toBeNull();

    // ...and a HELD run reaches the answer rung from presence just as it does from a local dispatch.
    await frame(el, { toolCalls: { c1: toolCall('c1', { status: 'pending' }) } });
    expect(q(composer, 'sv3-composer-answer')).not.toBeNull();
    expect(q(composer, 'sv3-composer-stop')).toBeNull();
    expect((await rowsOf(el))[0]?.getAttribute('status')).toBe('act-now');
  });

  it('shows the run FEED once the reader claims the adopted session', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    // Nothing is claimed yet, so the transcript shows nothing — the sidebar is the only witness.
    expect(all(main, 'sv3-turn')).toHaveLength(0);

    (await rowsOf(el))[0]?.shadowRoot?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(el);
    await main.updateComplete;
    expect(all(main, 'sv3-turn')).toHaveLength(1);
    expect(q(main, 'sv3-run-feed')).not.toBeNull();
    // The feed is the controller's own conversation, rendered through the ONE tool-call primitive.
    expect(main.shadowRoot?.querySelectorAll('jf-tool-call-card')).toHaveLength(1);
  });

  it('adopts the LIVE run only — a finished run in the same conversation is not its feed', async () => {
    aiOnline();
    // The controller appends across runs, so its conversation still holds a run that ended. The
    // adopted session must be titled by the live task and show only the live run's cards, or the
    // receipt would count a run the reader never watched here.
    alreadyRunning({
      conversation: [
        { id: 'u0', type: 'user', content: 'a run that already ended', timestamp: 0 },
        groupEntry('g0', ['old1', 'old2']),
        { id: 'u1', type: 'user', content: 'index the vendor folder', timestamp: 0 },
        groupEntry('g1', ['c1']),
      ] as ConversationEntry[],
      toolCalls: { old1: toolCall('old1'), old2: toolCall('old2'), c1: toolCall('c1') },
    });
    const el = await mount();
    await settle(el);
    const rows = await rowsOf(el);
    expect(rows.map((r) => (r as HTMLElement & { label: string }).label)).toEqual([
      'index the vendor folder',
    ]);

    rows[0]?.shadowRoot?.querySelector<HTMLButtonElement>('button')?.click();
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    await main.updateComplete;
    expect(main.shadowRoot?.querySelectorAll('jf-tool-call-card')).toHaveLength(1);

    // ...and the receipt counts that one card, not the three in the controller's whole conversation.
    await frame(el, { runInFlight: false, isStreaming: false });
    expect(q(main, 'sv3-run-receipt')?.textContent).toContain('1 tool call');
  });

  it('adopts a run ONCE and adopts nothing when the controller is idle', async () => {
    aiOnline();
    alreadyRunning();
    const el = await mount();
    await settle(el);
    // Several notifications later there is still exactly one row — the OPEN turn already stands for
    // this run, so a second adoption would be the same run twice in the sidebar.
    await frame(el, { streamingText: 'still going' });
    await frame(el, { streamingText: 'and going' });
    expect(await rowsOf(el)).toHaveLength(1);

    // The run ends: the adopted turn takes its receipt instead of streaming forever...
    await frame(el, { runInFlight: false, isStreaming: false, streamingText: '' });
    expect(await rowsOf(el)).toHaveLength(1);
    const composer = await region(el, 'jf-sv3-composer');
    expect(q(composer, 'sv3-composer-stop')).toBeNull();
    // ...and the settled run is not re-adopted by the next notification.
    await frame(el, {});
    expect(await rowsOf(el)).toHaveLength(1);

    // The case the ID LATCH is for: the SAME run reports itself live again after this window has
    // already concluded its turn (a flicker between frames). Without the latch that reads as a run
    // with no session, and the sidebar grows a duplicate of a run the reader already has.
    await frame(el, { runInFlight: true, sessionId: 'run-42' });
    expect(await rowsOf(el)).toHaveLength(1);
  });

  it('adopts nothing at all when no controller exists — a mounted window starts no polling', async () => {
    aiOnline();
    const el = await mount();
    await settle(el);
    expect(await rowsOf(el)).toHaveLength(0);
    expect(getCtrl).not.toHaveBeenCalled();
  });
});

describe('the window hosts the run — it does not re-implement one', () => {
  const here = dirname(fileURLToPath(import.meta.url));

  /**
   * Comments are stripped before every scan below, for the same reason the governance gates strip
   * them: a paragraph that NAMES the forbidden shape in order to forbid it must not read as the
   * shape itself, or the honest thing to do would be to stop documenting the rule.
   */
  const strip = (src: string): string =>
    src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

  const sources = (opts: { code?: boolean } = {}): Array<[string, string]> =>
    readdirSync(here)
      .filter((name) => name.endsWith('.ts'))
      .filter((name) => opts.code !== true || !name.endsWith('.test.ts'))
      .map((name) => [name, strip(readFileSync(join(here, name), 'utf8'))]);

  // The cross-window import scan lives ONCE, in `SearchV3View.ask.test.ts` (Phase F1 wrote it and F2
  // widened it to any import form). A second copy here would be a forked scan drifting from that one.

  it('mounts the SHARED tool-call primitive and defines no tool card of its own', () => {
    const mounts = sources({ code: true }).filter(([, src]) => src.includes('<jf-tool-call-card'));
    expect(mounts.map(([name]) => name).sort()).toEqual(['Sv3Main.ts']);
    // A window-local re-author is the fork `governance/run-renderers.v1.json` exists to catch; here
    // it would show up as sv3 defining a tool/step element of its own.
    const defined = sources({ code: true })
      .flatMap(([, src]) => [...src.matchAll(/customElements\.define\('([^']+)'/g)])
      .map((m) => m[1] ?? '');
    expect(defined.filter((tag) => /tool|run-node|step/.test(tag))).toEqual([]);
  });

  it('reaches the run ONLY through the control-intent seam', () => {
    const DIRECT =
      /\.(steer|cancelSession|raiseBudget|resumeSession|resolveBudgetGate|resolveContextGate)\(/;
    const offenders = sources({ code: true })
      .filter(([, src]) => DIRECT.test(src))
      .map(([name]) => name);
    expect(offenders).toEqual([]);
  });
});
