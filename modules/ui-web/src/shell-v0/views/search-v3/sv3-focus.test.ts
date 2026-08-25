// @vitest-environment happy-dom

/**
 * Search v3's FOCUS AUTHORITY (tempdoc 864 Layer 1) — the window owns where the caret is.
 *
 * The defect these cases exist for is not a keybinding: nothing on this surface ever focused its own
 * primary input, so `<body>` kept focus under a glass box that looks primed, and the reader's typing
 * went wherever focus happened to be parked — including a row button whose bare `Space` swaps the
 * conversation (§2.7b). Every case here is written so that it FAILS on the pre-fix window:
 *
 *  - the entry-path cases move focus somewhere else FIRST (the row button, the new-session control,
 *    an input outside the window), so a pass cannot come from focus merely never having left;
 *  - the dead-zone cases press the padding and the glass — the two places §2.9(b) measured as
 *    click-dead — and then press a real control, so "focus everything" would fail the third;
 *  - the ring case asserts the ring is still the platform's `:focus-visible`, so a fix that painted
 *    a focused ring for programmatic focus would fail.
 *
 * The authorities are the real ones (the conversation store, the per-tab pointer), stubbed only at
 * their single exit, `fetch` — the same shape `SearchV3View.record.test.ts` uses.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { Sv3Composer } from './Sv3Composer.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };
type Updatable = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;
let conversations: Array<Record<string, unknown>>;

const LAST_VIEWED_KEY = 'justsearch.lastViewedConversation.v1';

function row(id: string, question: string): Record<string, unknown> {
  return {
    sessionId: id,
    createdAtMs: 1,
    lastActiveAtMs: 2,
    messageCount: 2,
    firstUserMessage: question,
    shapeId: 'core.rag-ask',
  };
}

function stubFetch(): void {
  fetchMock.mockImplementation(async (url: unknown) => {
    const href = String(url);
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ runs: [] }) };
    }
    if (href.includes('/api/chat/conversations')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ sessions: conversations }) };
    }
    if (href.includes('/api/thread/')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ conversationId: '', events: [] }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ results: [] }) };
  });
}

/**
 * The observed state in which the composer is genuinely usable. A model id is fed as well as the
 * online verdict, because the footer's model LABEL only renders when there is one — and that label is
 * the selectable text the dead-zone press must leave alone.
 */
function aiOnline(): void {
  __feedForTest({
    inference: {
      mode: 'online',
      available: true,
      activeModelId: 'Qwen_Qwen3.5-9B.Q4_K_M.gguf',
    } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  conversations = [];
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  for (const el of [...document.querySelectorAll('input.outsider')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  await settle(el);
  return el;
}

async function region(el: Mounted, tag: string): Promise<Updatable> {
  const found = el.shadowRoot?.querySelector(tag) as Updatable | null;
  if (found === null) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

/** Park focus where the pre-fix window left it, so a pass cannot come from focus never moving. */
function park(): HTMLInputElement {
  const outsider = document.createElement('input');
  outsider.className = 'outsider';
  document.body.appendChild(outsider);
  outsider.focus();
  return outsider;
}

function blurEverything(): void {
  (deepActiveElement() as HTMLElement | null)?.blur();
}

async function rowButtons(el: Mounted): Promise<HTMLButtonElement[]> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])] as Updatable[];
  await Promise.all(rows.map((r) => r.updateComplete));
  return rows.flatMap((r) => {
    const button = r.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-session-row-button"]',
    );
    return button === null || button === undefined ? [] : [button];
  });
}

describe('tempdoc 864 Layer 1(a) — every entry lands the reader in the composer', () => {
  it('a fresh window focuses its own field instead of leaving <body> focused', async () => {
    const el = await mount();
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('a restored record lands in the composer, not on nothing', async () => {
    // The per-tab pointer is the record-load entry (tempdoc 609 Phase 3): the window claims the
    // conversation during connect and the reader arrives at a docked, ready composer.
    sessionStorage.setItem(LAST_VIEWED_KEY, 'conv-restored');
    conversations = [row('conv-restored', 'why did the renewal fail?')];
    const el = await mount();
    // Docked proves the RECORD path ran, not merely that a hero mounted.
    expect(el.composerState).toBe('docked');
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('claiming a conversation takes focus OFF the row button and puts it in the composer', async () => {
    conversations = [row('conv-a', 'first question'), row('conv-b', 'second question')];
    const el = await mount();
    const [first] = await rowButtons(el);
    if (first === undefined) throw new Error('no session rows');
    // A real click focuses the control it lands on — the state §2.7b turns into a conversation swap
    // on the reader's next `Space`.
    first.focus();
    expect(deepActiveElement()).toBe(first);
    first.click();
    await settle(el);
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('starting a new session moves focus off the new-session control and into the composer', async () => {
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const newButton = sidebar.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-sidebar-new"]',
    );
    if (!newButton) throw new Error('no new-session control');
    newButton.focus();
    expect(deepActiveElement()).toBe(newButton);
    newButton.click();
    await settle(el);
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('does NOT steal focus from a reader who is already typing somewhere else', async () => {
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const outsider = park();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.dispatchEvent(
      new CustomEvent('sv3-session-new', { bubbles: true, composed: true }),
    );
    await settle(el);
    expect(deepActiveElement()).toBe(outsider);
  });

  it('yields to a rename in progress across an unmount and re-entry', async () => {
    // The window is RETAINED across a surface switch and `renamingId` survives it, so re-entry is an
    // entry path that can land on a half-finished rename — with focus wherever the unmount left it.
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.dispatchEvent(
      new CustomEvent('sv3-session-rename', {
        detail: { id: 'conv-a', phase: 'start', title: null },
        bubbles: true,
        composed: true,
      }),
    );
    await settle(el);
    el.remove();
    (deepActiveElement() as HTMLElement | null)?.blur();
    document.body.appendChild(el);
    await settle(el);
    expect(deepActiveElement()).not.toBe(await fieldOf(el));
  });
});

describe('tempdoc 864 Layer 1(b) — the whole glass box is the field', () => {
  it('a press on the field padding focuses the textarea', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    expect(deepActiveElement()).not.toBe(field);
    const padding = composer.shadowRoot?.querySelector('.field');
    if (!padding) throw new Error('no .field');
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    padding.dispatchEvent(press);
    expect(deepActiveElement()).toBe(field);
    // The press's own default IS the focus move this replaces, so it must not also run.
    expect(press.defaultPrevented).toBe(true);
  });

  it('a press on the glass — the ring the box is framed by — focuses the textarea', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const glass = composer.shadowRoot?.querySelector('[data-testid="sv3-composer-shell"]');
    if (!glass) throw new Error('no .glass');
    glass.dispatchEvent(new Event('pointerdown', { bubbles: true, composed: true, cancelable: true }));
    expect(deepActiveElement()).toBe(field);
  });

  it('a press on a footer control is left alone — the control keeps its own press', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const control = composer.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-composer-tier"]',
    );
    if (!control) throw new Error('no mode control');
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    control.dispatchEvent(press);
    expect(deepActiveElement()).not.toBe(field);
    expect(press.defaultPrevented).toBe(false);
  });

  it('a press on a jf-control in the band is left alone — its host padding retargets no further', async () => {
    // The band holds plain `<button>`s today and `jf-control` tomorrow (it is the product's one
    // operability primitive). A press on the CONTROL'S OWN padding surfaces the host in the composed
    // path, and an upward walk from a host finds no `<button>` — so without the host in the bail set
    // the press would be eaten and the control would silently stop working.
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const glass = composer.shadowRoot?.querySelector('[data-testid="sv3-composer-shell"]');
    if (!glass) throw new Error('no .glass');
    const control = document.createElement('jf-control');
    glass.appendChild(control);
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    control.dispatchEvent(press);
    expect(deepActiveElement()).not.toBe(field);
    expect(press.defaultPrevented).toBe(false);
  });

  it('a press on the model label is left alone, so its text stays selectable', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const label = composer.shadowRoot?.querySelector('[data-testid="sv3-composer-model"]');
    if (!label) throw new Error('no model label — the fixture must report a model');
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    label.dispatchEvent(press);
    expect(press.defaultPrevented).toBe(false);
    expect(deepActiveElement()).not.toBe(field);
  });

  it('a press on the textarea itself is left alone, so caret placement and selection still work', async () => {
    const el = await mount();
    const field = await fieldOf(el);
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    field.dispatchEvent(press);
    expect(press.defaultPrevented).toBe(false);
  });
});

describe('tempdoc 864 — the focus ring stays the platform’s own', () => {
  it('keys the ring on :focus-visible only, so programmatic focus paints no ring the reader did not ask for', () => {
    const sheets = (Sv3Composer.styles as unknown as ReadonlyArray<unknown>).map((s) => String(s));
    const rules = sheets
      .join('\n')
      .split('}')
      .filter((rule) => rule.includes('.glass::after') && rule.includes(':focus'));
    expect(rules.length).toBeGreaterThan(0);
    for (const rule of rules) expect(rule).toContain(':focus-visible');
  });
});
