// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * The run spine's KEYBOARD NAVIGATION in Search v3 (tempdoc 854 PR-A) — J/K steps focus through a
 * run's landmarks, the only keyboard navigation the product has for run steps and the reason the
 * mechanism was ported rather than dropped with `UnifiedChatView`.
 *
 * The suite is built around ONE structural risk, because the feature's failure mode is silent: a
 * mis-stamped anchor yields zero landmarks and every J/K test still passes, since the retiree's own
 * tests (and `primitives/navigation.test.ts`) hand-assign `nav.landmarks` and never exercise
 * `measure()`. Compounding it, happy-dom lays nothing out, so `getBoundingClientRect()` returns
 * all-zero rects and `measure()` DROPS every zero-height element (`primitives/navigation.ts:301`) —
 * a perfectly-stamped transcript would measure to nothing.
 *
 * So the rects are stubbed per case (the idiom, and the reason for it, are this directory's own:
 * `SearchV3View.pane.test.ts:21/58`, `SearchV3View.sidebar.test.ts:13/43`), and the wiring cases
 * assert the landmark ids the CONTROLLER holds against the DOM queried from the exact element
 * `scrollEl()` returns. That last clause is what also makes these cases cover the scroller-swap
 * defect: a controller bound to a detached arm fails them.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { NavigationController } from '../../primitives/navigation.js';
import type { Sv3Turn } from './sv3-sessions.js';
import type { Sv3RunFeedItem, Sv3RunPrompt, Sv3RunView } from './sv3-run.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';

type Mounted = Sv3Main & { updateComplete: Promise<unknown>; requestUpdate: () => void };

const navOf = (el: Mounted): NavigationController =>
  (el as unknown as { nav: NavigationController }).nav;

const turn = (over: Partial<Sv3Turn> & { id: string }): Sv3Turn => ({
  recordId: null,
  assistantRecordId: null,
  recordOpenedByUser: false,
  kind: 'ask',
  question: 'why did the renewal fail?',
  answer: 'It expired.',
  status: 'complete',
  evidence: null,
  detail: '',
  toolCalls: 0,
  activity: [],
  askedAt: 1,
  standaloneQuestion: '',
  reasoning: [],
  durationMs: null,
  modelLabel: null,
  ...over,
});

const toolCall = (callId: string): ToolCall => ({
  callId,
  toolName: 'grep',
  arguments: '{}',
  risk: 'LOW',
  status: 'pending',
});

const runView = (over: {
  turnId: string;
  items?: readonly Sv3RunFeedItem[];
  prompts?: readonly Sv3RunPrompt[];
}): Sv3RunView => ({
  turnId: over.turnId,
  phase: 'running',
  feed: {
    items: over.items ?? [],
    toolCallCount: (over.items ?? []).filter((i) => i.kind === 'tool').length,
    pendingApprovals: [],
    errored: false,
  },
  prompts: over.prompts ?? [],
});

async function mount(fields: Partial<Sv3Main> = {}): Promise<Mounted> {
  document.body.innerHTML = '';
  const el = document.createElement('jf-sv3-main') as Mounted;
  Object.assign(el, fields);
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const scrollerOf = (el: Mounted): HTMLElement | null =>
  (el.shadowRoot?.querySelector('.scroller') ?? null) as HTMLElement | null;

/**
 * Give the scroll column and every stamped element a laid-out box, then drive renders until the
 * controller has measured. `measure()` is coalesced to one leading pass per frame (tempdoc 854 A9),
 * so a stub applied AFTER the mount render needs another cycle plus a macrotask to be seen.
 */
async function layOut(el: Mounted): Promise<HTMLElement> {
  const conv = scrollerOf(el);
  if (conv === null) throw new Error('no .scroller rendered — the transcript arm was not taken');
  conv.getBoundingClientRect = () =>
    ({ top: 0, left: 0, right: 800, bottom: 600, width: 800, height: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
  Object.defineProperty(conv, 'scrollHeight', { configurable: true, value: 2000 });
  Object.defineProperty(conv, 'clientHeight', { configurable: true, value: 600 });
  let top = 0;
  for (const node of conv.querySelectorAll('[data-item-id]')) {
    const y = top;
    (node as HTMLElement).getBoundingClientRect = () =>
      ({ top: y, left: 0, right: 800, bottom: y + 40, width: 800, height: 40, x: 0, y, toJSON: () => ({}) }) as DOMRect;
    top += 40;
  }
  for (let i = 0; i < 4 && navOf(el).landmarks.length === 0; i++) {
    el.requestUpdate();
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
  }
  return conv;
}

/** The stamped ids in DOM order, read from the element the controller actually navigates. */
const stampedIds = (conv: HTMLElement): string[] =>
  [...conv.querySelectorAll('[data-item-id]')].map((n) => n.getAttribute('data-item-id') ?? '');

const press = (key: string, init: KeyboardEventInit = {}): void => {
  window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
};

afterEach(() => {
  document.body.innerHTML = '';
});

describe('854 PR-A — the stamped transcript becomes the controller’s landmarks (the wiring)', () => {
  it('measures every stamped anchor, in DOM order, from the element scrollEl() returns', async () => {
    const el = await mount({
      turns: [
        turn({ id: 't1' }),
        turn({
          id: 't2',
          kind: 'agent',
          question: 'and the invoice?',
          activity: [
            { kind: 'text', id: 'e1', text: 'checked the ledger' },
            { kind: 'note', id: 'e2', label: 'Progress', text: 'two files left' },
          ],
        }),
      ],
    });
    const conv = await layOut(el);

    // The DOM is stamped as designed: a question and an answer for the ask turn, a question and one
    // anchor per run step for the agent turn (whose answer is its activity, not an `.answer` div).
    expect(stampedIds(conv)).toEqual(['t1:q', 't1:a', 't2:q', 'e1', 'e2']);
    // …and the controller HOLDS them. This is the assertion nothing else in the repo makes: every
    // other navigation test assigns `landmarks` by hand, so the path from stamped DOM to measured
    // landmark has never been covered for either adopter.
    expect(navOf(el).landmarks.map((l) => l.id)).toEqual(['t1:q', 't1:a', 't2:q', 'e1', 'e2']);
  });

  it('covers a live run: its feed steps AND the decision it is parked on are landmarks', async () => {
    const el = await mount({
      turns: [turn({ id: 'r1', kind: 'agent', question: 'fix the build' })],
      run: runView({
        turnId: 'r1',
        items: [
          { kind: 'text', id: 'x1', text: 'reading the log' },
          { kind: 'tool', id: 'call-9', call: toolCall('call-9') },
        ],
        // The approval's own id IS the tool call's id — the collision the `:hold` suffix exists for.
        prompts: [{ kind: 'approval', id: 'call-9', toolName: 'grep', risk: 'LOW' }],
      }),
    });
    const conv = await layOut(el);

    const ids = navOf(el).landmarks.map((l) => l.id);
    expect(ids).toEqual(['r1:q', 'x1', 'call-9', 'call-9:hold']);
    // Read back from the scroller the controller navigates, so the list above is the DOM's order and
    // not merely the order the projection happened to produce.
    expect(stampedIds(conv)).toEqual(ids);
    // The held decision renders OUTSIDE `.run-feed` precisely so it cannot be scrolled past; without
    // the fourth stamp site it would be the one run element J/K skips.
    expect(ids).toContain('call-9:hold');
  });

  it('rebinds across the scroller swap: a hero window that receives its first turn navigates', async () => {
    // The hero arm renders an EMPTY `.scroller` from a different template than the transcript arm,
    // so the node the controller first sees is not the node it must end up bound to. The authority
    // binds its observer and listeners once per node, so a controller that stayed active across the
    // swap would keep measuring a detached element.
    const el = await mount({ state: 'docked' });
    const heroScroller = scrollerOf(el);
    expect(heroScroller).not.toBeNull();
    expect(navOf(el).landmarks).toHaveLength(0);

    el.turns = [turn({ id: 'first' })];
    await el.updateComplete;
    const conv = await layOut(el);

    expect(conv).not.toBe(heroScroller);
    expect(navOf(el).landmarks.map((l) => l.id)).toEqual(['first:q', 'first:a']);
  });
});

describe('854 PR-A — the anchor id space', () => {
  it('mints no duplicate data-item-id across turns, records, a live feed and a held approval', async () => {
    const el = await mount({
      turns: [
        turn({ id: 'a' }),
        turn({
          id: 'b',
          kind: 'agent',
          activity: [
            { kind: 'text', id: 'rec-1', text: 'from the record' },
            { kind: 'tool', id: 'rec-2', call: toolCall('rec-2') },
          ],
        }),
        turn({ id: 'c', kind: 'agent', question: 'and now?' }),
      ],
      run: runView({
        turnId: 'c',
        items: [{ kind: 'tool', id: 'live-1', call: toolCall('live-1') }],
        prompts: [
          { kind: 'budget', id: 'run-budget-gate', tokensNeeded: 10, tokensRemaining: 2 },
          { kind: 'approval', id: 'live-1', toolName: 'grep', risk: 'LOW' },
        ],
      }),
    });
    const conv = await layOut(el);

    const ids = stampedIds(conv);
    expect(ids.length).toBeGreaterThan(0);
    expect(new Set(ids).size).toBe(ids.length);
    // The sharp case: the tool card and the hold that names the same call are DIFFERENT anchors, so
    // the hold is reachable and the landmark list carries no duplicate. Stamping `prompt.id` bare
    // fails this line.
    expect(ids).toContain('live-1');
    expect(ids).toContain('live-1:hold');
  });

  it('emits one anchor per RENDERED item — a question with no text and one open for edit emit none', async () => {
    const el = await mount({
      turns: [
        turn({ id: 'plain' }),
        turn({ id: 'silent', question: '' }),
        turn({ id: 'editing' }),
      ],
      editingTurnId: 'editing',
    });
    const conv = await layOut(el);

    // NOT `2 × turns.length`: `question()` returns nothing for an empty question and returns the
    // editor for a turn being rewritten, so both of those turns contribute an answer anchor only.
    expect(stampedIds(conv)).toEqual(['plain:q', 'plain:a', 'silent:a', 'editing:a']);
  });
});

describe('854 PR-A — J/K stepping', () => {
  async function transcript(): Promise<Mounted> {
    const el = await mount({
      turns: [
        turn({ id: 'q1' }),
        turn({
          id: 'q2',
          kind: 'agent',
          question: 'go on',
          activity: [{ kind: 'note', id: 'step-1', label: 'Progress', text: 'working' }],
        }),
      ],
      run: null,
    });
    await layOut(el);
    return el;
  }

  it('j steps forward, k steps back, and any other key is ignored', async () => {
    const el = await transcript();
    const nav = navOf(el);
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const ids = nav.landmarks.map((l) => l.id);
    expect(ids).toEqual(['q1:q', 'q1:a', 'q2:q', 'step-1']);

    // jumpTo is mocked → the focus never moves → both presses resolve from the SAME state, so a
    // forward press and a back press must pick different landmarks. That is what proves direction is
    // honoured, independently of whatever the derived focus happens to be. (The transcript is
    // scrolled to its end, so nothing is derived and the retiree's own index math sends a first `j`
    // to the head of the list and a first `k` to its tail.)
    press('j');
    expect(jumpTo).toHaveBeenLastCalledWith(ids[0]);
    press('k');
    expect(jumpTo).toHaveBeenLastCalledWith(ids[ids.length - 1]);
    expect(jumpTo).toHaveBeenCalledTimes(2);

    press('x');
    expect(jumpTo).toHaveBeenCalledTimes(2);
  });

  it('walks a mixed ask/agent transcript in DOM order, including a run’s held decision', async () => {
    const el = await mount({
      turns: [turn({ id: 'm1' }), turn({ id: 'm2', kind: 'agent', question: 'now delegate' })],
      run: runView({
        turnId: 'm2',
        items: [{ kind: 'text', id: 'p1', text: 'thinking' }],
        prompts: [{ kind: 'budget', id: 'run-budget-gate', tokensNeeded: 9, tokensRemaining: 1 }],
      }),
    });
    await layOut(el);
    const nav = navOf(el);
    const order = ['m1:q', 'm1:a', 'm2:q', 'p1', 'run-budget-gate:hold'];
    expect(nav.landmarks.map((l) => l.id)).toEqual(order);

    // Walk the whole spine forward with the REAL jumpTo, so each press moves the pinned focus and the
    // next press resolves against it — the property a mocked jumpTo cannot show. The transcript sits
    // at its end (it follows a streaming answer), so nothing is derived and the first press lands on
    // the head of the list; from there each press advances exactly one landmark.
    const visited: string[] = [];
    for (let i = 0; i < order.length; i++) {
      press('j');
      visited.push(nav.activeId);
    }
    expect(visited).toEqual(order);

    // …and back, ending on the first landmark.
    for (let i = 1; i < order.length; i++) press('k');
    expect(nav.activeId).toBe(order[0]);
  });

  it('clamps at both ends — no wrap', async () => {
    const el = await transcript();
    const nav = navOf(el);
    const last = nav.landmarks[nav.landmarks.length - 1]!.id;
    const first = nav.landmarks[0]!.id;

    for (let i = 0; i < nav.landmarks.length + 2; i++) press('j');
    expect(nav.activeId).toBe(last);
    for (let i = 0; i < nav.landmarks.length + 2; i++) press('k');
    expect(nav.activeId).toBe(first);
  });

  it('moves real DOM focus to the step it jumps to (the whole accessibility payload)', async () => {
    const el = await transcript();
    const nav = navOf(el);
    press('j');
    const focused = el.shadowRoot?.activeElement as HTMLElement | null;
    expect(focused?.getAttribute('data-item-id')).toBe(nav.activeId);
  });

  it('works at a narrow width — the deliberate divergence from the retiree’s wideZone gate', async () => {
    const el = await transcript();
    el.getBoundingClientRect = () =>
      ({ top: 0, left: 0, right: 380, bottom: 600, width: 380, height: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
    await el.updateComplete;
    const nav = navOf(el);
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    press('j');
    // The retiree gates this navigation on a wide viewport because its MINIMAP needs a gutter; this
    // window has no gutter, so gating would leave a narrow reader with no keyboard nav at all.
    expect(jumpTo).toHaveBeenCalledTimes(1);
  });

  it('removes the window listener on disconnect (no leak after the region is gone)', async () => {
    const el = await transcript();
    const nav = navOf(el);
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    el.remove();
    press('j');
    expect(jumpTo).not.toHaveBeenCalled();
  });

  it('no-ops with nothing measured rather than throwing (the record-notice-only transcript)', async () => {
    const el = await mount({ turns: [], recordNotice: true });
    expect(scrollerOf(el)).not.toBeNull(); // the transcript arm IS rendered, so active() is true
    expect(navOf(el).landmarks).toHaveLength(0);
    expect(() => press('j')).not.toThrow();
  });
});

describe('854 PR-A — the guards', () => {
  async function transcript(): Promise<{ el: Mounted; nav: NavigationController }> {
    const el = await mount({ turns: [turn({ id: 'g1' }), turn({ id: 'g2' })] });
    await layOut(el);
    return { el, nav: navOf(el) };
  }

  it('ignores a j/k an inner handler already claimed (the advisory-drawer collision)', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // Faithful to `components/advisory/AdvisoryInboxDrawer.ts:379-386`: a row-scoped bare-`j`
    // handler that calls preventDefault() but NOT stopPropagation(), on a `role="button"` element
    // the typing guard has no reason to stop. The drawer is mounted app-wide (`chrome/Shell.ts:2384`),
    // so its keys reach this window's listener by bubbling.
    const row = document.createElement('div');
    row.setAttribute('role', 'button');
    row.tabIndex = 0;
    row.addEventListener('keydown', (e) => {
      if ((e as KeyboardEvent).key === 'j' || (e as KeyboardEvent).key === 'k') e.preventDefault();
    });
    document.body.appendChild(row);

    row.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', bubbles: true, cancelable: true }));
    expect(jumpTo).not.toHaveBeenCalled();

    // The same press with nothing claiming it still navigates — so the assertion above is the guard,
    // not a dead listener.
    press('j');
    expect(jumpTo).toHaveBeenCalledTimes(1);
  });

  it('never hijacks typing — input, textarea, select and contentEditable all block it', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    for (const tag of ['input', 'textarea', 'select'] as const) {
      const field = document.createElement(tag);
      document.body.appendChild(field);
      field.focus();
      expect(document.activeElement).toBe(field);
      press('j');
      expect(jumpTo).not.toHaveBeenCalled();
      field.remove();
    }
    const editable = document.createElement('div');
    editable.tabIndex = 0;
    Object.defineProperty(editable, 'isContentEditable', { configurable: true, value: true });
    document.body.appendChild(editable);
    editable.focus();
    press('j');
    expect(jumpTo).not.toHaveBeenCalled();
    editable.remove();
  });

  it('never hijacks typing — descends nested shadow roots to the truly-focused editable', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // The composer's textarea lives two shadow roots down; a bare `document.activeElement` check
    // stops at the host and reports a non-editable custom element.
    const innerTextarea = { tagName: 'TEXTAREA', isContentEditable: false, shadowRoot: null };
    const host = { shadowRoot: { activeElement: innerTextarea }, tagName: 'JF-SV3-COMPOSER' };
    Object.defineProperty(document, 'activeElement', { configurable: true, get: () => host });
    try {
      press('j');
      expect(jumpTo).not.toHaveBeenCalled();
    } finally {
      delete (document as unknown as Record<string, unknown>).activeElement;
    }
  });

  it('ignores modified chords — Ctrl/⌘/Alt/Shift + j belong to the browser and to the palette', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    press('j', { ctrlKey: true });
    press('j', { metaKey: true });
    press('j', { altKey: true });
    press('j', { shiftKey: true });
    expect(jumpTo).not.toHaveBeenCalled();
  });
});

describe('854 PR-A — active() is host state, and false in every non-transcript arm', () => {
  const armed = (el: Mounted): boolean =>
    (el as unknown as { transcriptArmRendered: boolean }).transcriptArmRendered;

  it('is true only where the transcript renders — the hero, search and locked arms are all false', async () => {
    const hero = await mount({ state: 'docked' });
    expect(armed(hero)).toBe(false);

    const withTurns = await mount({ turns: [turn({ id: 'x' })] });
    expect(armed(withTurns)).toBe(true);

    // The LOCK wins over the transcript (`render()` takes the locked arm first), and that arm has no
    // `.scroller` at all. An `active()` that read only "there are turns" would stay true here, the
    // controller would never tear down, and it would hold listeners on a detached node.
    const locked = await mount({ turns: [turn({ id: 'x' })], historyLocked: true });
    expect(armed(locked)).toBe(false);
    expect(scrollerOf(locked)).toBeNull();
  });

  it('is answerable before any measurement — it never reads the landmark list', async () => {
    // The deadlock this closes: `landmarks` populate only inside `measure()`, which runs only when
    // `active()` is already true, so an `active()` derived from `landmarks.length` can never become
    // true. Here the arm is armed while the landmark list is still empty.
    const el = await mount({ turns: [turn({ id: 'pre' })] });
    expect(navOf(el).landmarks).toHaveLength(0);
    expect(armed(el)).toBe(true);
  });
});
