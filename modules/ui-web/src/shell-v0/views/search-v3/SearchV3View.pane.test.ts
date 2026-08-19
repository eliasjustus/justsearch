// @vitest-environment happy-dom

/**
 * The Search v3 window's CITATION PANE (tempdoc 822 Phase F8).
 *
 * Four things are asserted here that nothing else can see:
 *
 *  1. **The shared selection is not touched.** `citation-select` is `composed` from every producer
 *     and the Shell listens for it at the host with no guard (`chrome/Shell.ts:533-554`), so an
 *     in-window citation click would otherwise ALSO open the shipped window's reading pane. The
 *     probe has teeth: the same event dispatched from an UNGUARDED node in the same window is
 *     asserted to escape, so the zero above is the `stopPropagation`, not the event model.
 *  2. **Both boundaries leave the main column its 640.** The window-level half of
 *     `sv3-boundaries.test.ts`'s both-open probe: a real drag, against a measured box, with the pane
 *     open.
 *  3. **Escape order** (rename > pane > palette > composer flip, Phase F9). The pane closes before
 *     an open palette, and BOTH yield to an inline rename — the most local transient state wins.
 *  4. **Cited documents only.** Structural, because behaviour cannot prove an absence: the window has
 *     exactly one writer of the pane's document, and the pane has no attribute route into it.
 *
 * happy-dom lays nothing out, so `getBoundingClientRect` is stubbed per case — which is what makes
 * the narrow presentation and the both-open clamp testable at all.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SearchV3View } from './SearchV3View.js';
import { SV3_CITATION_OPEN } from './Sv3Main.js';
import { Sv3Pane, SV3_PANE_CLOSE } from './Sv3Pane.js';
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
// READ-ONLY here, on purpose: the shared store this window must never write is asserted untouched.
import { getInspectorState, resetInspectorState } from '../../state/inspectorState.js';
import {
  sv3BoundaryStorageKeys,
  SV3_GRIP_KEY_STEP_PX,
  SV3_MAIN_MIN_PX,
  SV3_PANE_DEFAULT_PX,
  SV3_PANE_MIN_PX,
} from './sv3-boundaries.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

const BOX = 1568;

function widen(el: HTMLElement, width: number): void {
  el.getBoundingClientRect = () =>
    ({ width, height: 900, x: 0, y: 0, top: 0, left: 0, right: width, bottom: 900, toJSON: () => ({}) }) as DOMRect;
}

function feed(): void {
  __feedForTest({
    inference: { mode: 'online', available: true, activeModelId: 'Qwen_Qwen3.5-9B.Q4_K_M.gguf' } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

interface FakeStream {
  emit(event: string, data: unknown): void;
  end(): void;
}

/** The dispatch's SSE body, driven frame by frame (the F7 harness's, unchanged). */
function stubStream(): FakeStream {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  let signal: AbortSignal | null = null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  fetchMock.mockImplementation(async (url: unknown, init: { signal?: AbortSignal }) => {
    if (!String(url).includes('/api/chat/dispatch')) {
      return { ok: true, status: 200, body: null, json: async () => ({}) };
    }
    signal = init?.signal ?? null;
    return {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => {
            while (queued.length === 0) {
              if (signal?.aborted === true) throw new Error('The operation was aborted.');
              await new Promise<void>((resolve) => {
                wake = resolve;
                signal?.addEventListener('abort', () => resolve(), { once: true });
              });
            }
            return queued.shift();
          },
          releaseLock: () => {},
        }),
      },
    };
  });
  return {
    emit: (event, data) =>
      push({ done: false, value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`) }),
    end: () => push({ done: true }),
  };
}

const CITED_DOC = 'f:/docs/note-0.md';

/** One retrieval source in the shape the backend mints. */
const source = (): Record<string, unknown> => ({
  parentDocId: CITED_DOC,
  chunkIndex: 0,
  chunkTotal: 2,
  startChar: 0,
  endChar: 40,
  score: 0.8,
  excerpt: 'excerpt 0',
  startLine: 12,
  endLine: 18,
  headingText: 'Notes',
  headingLevel: 2,
});

/** The detail the SHARED producers carry (`components/chat/citationTypes.ts`). */
const citationDetail = {
  parentDocId: CITED_DOC,
  startLine: 12,
  endLine: 18,
  startChar: 0,
  endChar: 40,
  excerpt: 'excerpt 0',
};

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  resetInspectorState();
  fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, body: null, json: async () => ({}) });
  vi.stubGlobal('fetch', fetchMock);
  __resetAiStateForTest();
  feed();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  resetInspectorState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(boxWidth = BOX): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  widen(el, boxWidth);
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<HTMLElement & { updateComplete: Promise<unknown> }> {
  const found = el.shadowRoot?.querySelector(tag) as (HTMLElement & { updateComplete: Promise<unknown> }) | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const q = <T extends HTMLElement>(host: { shadowRoot: ShadowRoot | null }, testid: string): T | null =>
  host.shadowRoot?.querySelector<T>(`[data-testid="${testid}"]`) ?? null;

/** Ask, stream one grounded answer, settle — the state in which a citations panel exists. */
async function askGrounded(el: Mounted): Promise<void> {
  const stream = stubStream();
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = 'why did the renewal fail?';
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  q<HTMLButtonElement>(composer, 'sv3-composer-send')?.click();
  await settle(el);
  stream.emit('rag.citations', { citations: [source()] });
  stream.emit('chunk', { text: 'The lock held.' });
  stream.emit('rag.citation_matches', {
    matches: [{ sentenceIndex: 0, sentenceText: 'The lock held.', similarity: 0.9, sourceIndex: 0 }],
  });
  stream.emit('done', {});
  stream.end();
  await settle(el);
}

/** The citation, followed the way the SHARED panel raises it (`CitationsPanel.ts:291-296`). */
function fireCitation(from: HTMLElement): void {
  from.dispatchEvent(
    new CustomEvent('citation-select', { detail: citationDetail, bubbles: true, composed: true }),
  );
}

async function openPane(el: Mounted): Promise<HTMLElement & { updateComplete: Promise<unknown> }> {
  await askGrounded(el);
  const main = await region(el, 'jf-sv3-main');
  // Phase F11 — the evidence is behind the tail's own disclosure, so the panel exists once opened.
  const trigger = q<HTMLButtonElement>(main, 'sv3-turn-sources');
  if (trigger === null) throw new Error('the answer landed without a sources disclosure');
  trigger.click();
  await main.updateComplete;
  const panel = q(main, 'sv3-turn-citations');
  if (panel === null) throw new Error('the answer landed without a citations panel');
  // The window's own event is what crosses from the surface to the host — asserted here so the
  // guarded handler's OUTPUT is pinned, not only its suppression of the shared one.
  const raised: string[] = [];
  el.addEventListener(SV3_CITATION_OPEN, (e) =>
    raised.push((e as CustomEvent<{ docPath: string }>).detail.docPath),
  );
  fireCitation(panel);
  await el.updateComplete;
  if (raised.length !== 1) throw new Error(`expected one ${SV3_CITATION_OPEN}, saw ${raised.length}`);
  return region(el, 'jf-sv3-pane');
}

/**
 * Ask and stream only as far as the RETRIEVAL SET — no `rag.citation_matches` yet. This is the state
 * every mid-stream citation click lands in (`rag.citations` is emitted at retrieval time, the
 * matches only after the answer streams), so it is the common case rather than a contrived one.
 */
async function askUnmatched(el: Mounted, citations: Array<Record<string, unknown>>): Promise<FakeStream> {
  const stream = stubStream();
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  field.value = 'why did the renewal fail?';
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  q<HTMLButtonElement>(composer, 'sv3-composer-send')?.click();
  await settle(el);
  stream.emit('rag.citations', { citations });
  stream.emit('chunk', { text: 'The lock held.' });
  await settle(el);
  return stream;
}

/** Follow the citation from the inline mark, which exists while the answer is still streaming. */
async function openPaneMidStream(el: Mounted): Promise<Sv3Pane & { updateComplete: Promise<unknown> }> {
  const main = await region(el, 'jf-sv3-main');
  const block = q(main, 'sv3-turn-markdown');
  if (block === null) throw new Error('the stream produced no answer block to cite from');
  fireCitation(block);
  await el.updateComplete;
  return (await region(el, 'jf-sv3-pane')) as Sv3Pane & { updateComplete: Promise<unknown> };
}

/* ── 1. The double-open guard ────────────────────────────────────────────────────────────────── */

describe('an in-window citation opens the WINDOW pane and nothing else', () => {
  it('opens the pane at the cited range and leaves the shared inspector selection untouched', async () => {
    const el = await mount();
    const escaped: Event[] = [];
    document.body.addEventListener('citation-select', (e) => escaped.push(e));

    const pane = (await openPane(el)) as Sv3Pane & { updateComplete: Promise<unknown> };

    // The pane is mounted, on the cited document, at the cited span — in CHARACTER coordinates
    // (tempdoc 849 §3). The producer's derived line numbers are deliberately no longer forwarded:
    // they were computed 1-based and read 0-based, and the primary they came from was dropped at
    // this exact hop, so nothing downstream could recompute or check them.
    expect(pane.docPath).toBe(CITED_DOC);
    expect(pane.citation).toEqual({
      startChar: 0,
      endChar: 40,
      excerpt: 'excerpt 0',
      // `rag.citation_matches` had already landed for source 0 in this fixture, so the pane opens
      // with the sentence to emphasise inside the chunk.
      sentenceText: 'The lock held.',
    });
    // ...and the SHARED reading surface is what renders it.
    expect(pane.shadowRoot?.querySelector('jf-document-pane')).not.toBeNull();

    // THE PROBE. The Shell's listener is unguarded, so anything that reaches the host writes here.
    expect(getInspectorState().selected).toBeNull();
    expect(getInspectorState().isOpen).toBe(false);
    expect(escaped).toHaveLength(0);
  });

  it('proves the probe has teeth — the same event from an UNGUARDED node does escape', async () => {
    const el = await mount();
    const escaped: Event[] = [];
    document.body.addEventListener('citation-select', (e) => escaped.push(e));
    await askGrounded(el);
    const main = await region(el, 'jf-sv3-main');
    const unguarded = q(main, 'sv3-transcript');
    expect(unguarded).not.toBeNull();

    fireCitation(unguarded as HTMLElement);

    // Composed + bubbling all the way out of two shadow roots: this is exactly the path the guarded
    // producers would take, which is why stopping it AT the producer is the whole mechanism.
    expect(escaped).toHaveLength(1);
    // And the window did NOT open its pane from it: the pane's one writer is the window's own event.
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
  });

  it('follows an inline [n] mark through the same guarded handler', async () => {
    const el = await mount();
    const escaped: Event[] = [];
    document.body.addEventListener('citation-select', (e) => escaped.push(e));
    await askGrounded(el);
    const main = await region(el, 'jf-sv3-main');
    const block = q(main, 'sv3-turn-markdown');
    expect(block).not.toBeNull();

    fireCitation(block as HTMLElement);
    await el.updateComplete;

    expect((await region(el, 'jf-sv3-pane')) as Sv3Pane).toHaveProperty('docPath', CITED_DOC);
    expect(escaped).toHaveLength(0);
    expect(getInspectorState().selected).toBeNull();
  });
});

/* ── 2. Mount and unmount ────────────────────────────────────────────────────────────────────── */

describe('the pane is mounted exactly while a cited document is open', () => {
  it('is absent before the first citation and after pane-close', async () => {
    const el = await mount();
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
    const pane = await openPane(el);
    expect(q(el, 'sv3-pane-grip')).not.toBeNull();

    // The shared reader's own close action, as the region re-raises it.
    pane.dispatchEvent(new CustomEvent(SV3_PANE_CLOSE, { bubbles: true, composed: true }));
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
    expect(q(el, 'sv3-pane-grip')).toBeNull();
    // Closing a document is not withdrawing a boundary preference.
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX);
  });

  it('closes when a new session starts — the citation belonged to the old conversation', async () => {
    const el = await mount();
    await openPane(el);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.dispatchEvent(new CustomEvent('sv3-session-new', { bubbles: true, composed: true }));
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
  });

  it('does not let the inline track width outvote the overlay presentation', () => {
    // Live measurement found this: a width declared on <jf-sv3-pane> from the WINDOW's stylesheet is
    // outer-tree and therefore beats the element's own :host([overlay]) rules, so an unguarded track
    // pinned the overlaid sheet to 540px at the LEFT edge with its own inset: 0 outvoted.
    const own = String((SearchV3View as unknown as { styles: unknown[] }).styles[2]);
    expect(own).toContain(':host(:not([pane-overlay])) jf-sv3-pane');
    expect(own).not.toMatch(/(^|})s*jf-sv3-panes*{/);
  });

  it('adds NO second emphasis of its own — the landing is the shared reader\'s decay', () => {
    // `DocumentPane`'s HIGHLIGHT_DECAY_MS is the citation landing (reduced-motion-aware, re-render
    // safe). A window rule mentioning the highlight would be a second, competing one.
    // The element's OWN sheet (index 0 is the shared sv3 stylesheet every region adopts).
    const own = String(Sv3Pane.styles[1]);
    expect(own).not.toContain('highlight');
    // Exactly one animation of its own, and it is the spec's sheet entry — not an emphasis.
    expect([...own.matchAll(/@keyframes\s+([\w-]+)/g)].map((m) => m[1])).toEqual(['sv3-pane-in']);
  });
});

/* ── 3. Escape order ─────────────────────────────────────────────────────────────────────────── */

describe('Escape closes the pane FIRST', () => {
  it('closes the pane and leaves an open palette open', async () => {
    const el = await mount();
    await openPane(el);
    el.togglePalette(null);
    const palette = (await region(el, 'jf-sv3-palette')) as unknown as HTMLElement & { open: boolean; shadowRoot: ShadowRoot | null };
    await el.updateComplete;
    expect(palette.open).toBe(true);

    const field = palette.shadowRoot?.querySelector('input');
    expect(field).not.toBeNull();
    field?.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }),
    );
    await el.updateComplete;

    // The pane went; the palette — whose own Escape handler would otherwise have run — stayed.
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
    expect(palette.open).toBe(true);
  });

  it('leaves the palette its Escape once the pane is closed', async () => {
    const el = await mount();
    el.togglePalette(null);
    const palette = (await region(el, 'jf-sv3-palette')) as unknown as HTMLElement & { open: boolean; shadowRoot: ShadowRoot | null };
    await el.updateComplete;
    palette.shadowRoot
      ?.querySelector('input')
      ?.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }),
      );
    await el.updateComplete;
    expect(palette.open).toBe(false);
  });

  it('yields to a rename in progress — the edit is the most local transient state', async () => {
    // The reproduced defect (F-series fit audit, §6.1): with the pane open, an Escape pressed
    // INSIDE the rename field closed the pane and left the edit standing — the reader's cancel key
    // silently destroyed a different region. The order is rename > pane, and the window serves it
    // by DECLINING: the capture-phase listener must not consume a key the row's own handler owns.
    const el = await mount();
    await openPane(el);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const row = sidebar.shadowRoot?.querySelector(
      '[data-testid="sv3-sidebar-row"]',
    ) as (HTMLElement & { updateComplete: Promise<unknown>; renaming: boolean }) | null;
    if (row === null) throw new Error('the answered ask left no session row to rename');
    row.shadowRoot
      ?.querySelector('[data-testid="sv3-session-row-button"]')
      ?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    await sidebar.updateComplete;
    await row.updateComplete;
    expect(row.renaming, 'the double-click did not open the edit').toBe(true);

    row.shadowRoot
      ?.querySelector('[data-testid="sv3-session-row-rename-input"]')
      ?.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }),
      );
    await el.updateComplete;
    await sidebar.updateComplete;

    // The edit is what was cancelled; the document the reader was not looking at is still open.
    expect(row.renaming).toBe(false);
    expect(el.shadowRoot?.querySelector('jf-sv3-pane'), 'Escape closed the PANE mid-rename').not.toBeNull();

    // And the next Escape, now that nothing more local owns it, reaches the pane.
    el.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }),
    );
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
  });

  it('does not eat Escape in the composer when no document is open', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'note';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    q<HTMLButtonElement>(composer, 'sv3-composer-send')?.click();
    await settle(el);
    expect(el.composerState).toBe('docked');

    field!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true }));
    await el.updateComplete;
    expect(el.composerState).toBe('hero');
  });
});

/* ── 4. Geometry: the boundary, both open ────────────────────────────────────────────────────── */

describe('the two boundaries leave the main column its 640', () => {
  it('clamps the SIDEBAR against the open pane, not against an empty window', async () => {
    const el = await mount();
    await openPane(el);
    const grip = q<HTMLElement>(el, 'sv3-sidebar-grip');
    grip!.setPointerCapture = (): void => undefined;
    grip!.dispatchEvent(
      new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 300 }),
    );
    grip!.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 5000 }));
    grip!.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
    await el.updateComplete;

    // 1568 − 540 − 640. Without the pane term this would be 928 and the main column 100px wide.
    expect(el.sidebarWidthPx).toBe(BOX - SV3_PANE_DEFAULT_PX - SV3_MAIN_MIN_PX);
    expect(BOX - el.sidebarWidthPx - el.paneWidthPx).toBe(SV3_MAIN_MIN_PX);
  });

  it('clamps the PANE against the open sidebar', async () => {
    const el = await mount();
    await openPane(el);
    const grip = q<HTMLElement>(el, 'sv3-pane-grip');
    grip!.setPointerCapture = (): void => undefined;
    grip!.dispatchEvent(
      new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 1000 }),
    );
    // Dragging LEFT grows a right-anchored pane; far past every ceiling.
    grip!.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: -5000 }));
    grip!.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
    await el.updateComplete;

    expect(el.paneWidthPx).toBe(BOX - el.sidebarWidthPx - SV3_MAIN_MIN_PX);
    expect(BOX - el.sidebarWidthPx - el.paneWidthPx).toBe(SV3_MAIN_MIN_PX);
  });

  it('persists the chosen width on pointerUP, and reverts a cancelled gesture', async () => {
    const el = await mount();
    await openPane(el);
    const grip = q<HTMLElement>(el, 'sv3-pane-grip');
    grip!.setPointerCapture = (): void => undefined;

    grip!.dispatchEvent(
      new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 1000 }),
    );
    grip!.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 940 }));
    // Mid-gesture: nothing is remembered yet (the spec writes only on pointerup).
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBeNull();
    grip!.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
    await el.updateComplete;
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX + 60);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBe(String(SV3_PANE_DEFAULT_PX + 60));

    // A CANCELLED gesture reverts to where it started and remembers nothing new.
    grip!.dispatchEvent(
      new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 1000 }),
    );
    grip!.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 800 }));
    grip!.dispatchEvent(new MouseEvent('pointercancel', { bubbles: true }));
    await el.updateComplete;
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX + 60);
    expect(el.style.getPropertyValue('--pane-width')).toBe(`${SV3_PANE_DEFAULT_PX + 60}px`);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBe(String(SV3_PANE_DEFAULT_PX + 60));
  });

  it('moves and resets from the keyboard, and a double-click FORGETS', async () => {
    const el = await mount();
    await openPane(el);
    const grip = q<HTMLElement>(el, 'sv3-pane-grip');

    // Right-anchored: ArrowLeft grows, the direction the pointer drags.
    grip!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await el.updateComplete;
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX + SV3_GRIP_KEY_STEP_PX);
    grip!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await el.updateComplete;
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBe(String(SV3_PANE_DEFAULT_PX));

    // 818 L13 — returning the boundary to automatic FORGETS the width rather than storing a default.
    grip!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    await el.updateComplete;
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBeNull();
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX);

    // Home does the same from the keyboard; Escape does NOT (it belongs to the pane).
    grip!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await el.updateComplete;
    grip!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await el.updateComplete;
    expect(el.paneWidthPx).toBe(SV3_PANE_DEFAULT_PX);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.paneWidth)).toBeNull();
  });

  it('reopens at a remembered width the current box still allows', async () => {
    localStorage.setItem(sv3BoundaryStorageKeys.paneWidth, '900');
    const el = await mount(1400);
    // 1400 − 256 − 640 = 504: a width remembered from a wider window is not restored into a box that
    // rejects it.
    expect(el.paneWidthPx).toBe(1400 - 256 - SV3_MAIN_MIN_PX);
    expect(el.paneWidthPx).toBeGreaterThanOrEqual(SV3_PANE_MIN_PX);
  });
});

/* ── 5. The narrow presentation ──────────────────────────────────────────────────────────────── */

describe('below the spec 980 the pane is a window-scoped overlay', () => {
  it('overlays, dims, and hides the grip', async () => {
    const el = await mount(900);
    const pane = (await openPane(el)) as Sv3Pane & { updateComplete: Promise<unknown> };
    await pane.updateComplete;

    expect(el.hasAttribute('pane-overlay')).toBe(true);
    expect(pane.hasAttribute('overlay')).toBe(true);
    // The dim is a real node only in this presentation, and it is an exit.
    const backdrop = q<HTMLButtonElement>(pane, 'sv3-pane-backdrop');
    expect(backdrop).not.toBeNull();
    // No boundary was dragged, so there is nothing to grab.
    expect(q(el, 'sv3-pane-grip')).toBeNull();

    backdrop?.click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-sv3-pane')).toBeNull();
  });

  it('stays INLINE above the switch', async () => {
    const el = await mount(1568);
    const pane = (await openPane(el)) as Sv3Pane;
    expect(el.hasAttribute('pane-overlay')).toBe(false);
    expect(pane.hasAttribute('overlay')).toBe(false);
    expect(q(el, 'sv3-pane-grip')).not.toBeNull();
    expect(el.style.getPropertyValue('--pane-width')).toBe(`${SV3_PANE_DEFAULT_PX}px`);
  });

  it('takes NO room from the sidebar while it is overlaid', async () => {
    const el = await mount(900);
    await openPane(el);
    const grip = q<HTMLElement>(el, 'sv3-sidebar-grip');
    grip!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await el.updateComplete;
    // 900 − 640 = 260: the ceiling the sidebar has when nothing else is in the flow. Subtracting an
    // overlaid pane here would have pinned it to the 208 floor instead.
    expect(el.sidebarWidthPx).toBe(900 - SV3_MAIN_MIN_PX);
  });
});

/* ── 5b. The late claim match (tempdoc 849 §4) ───────────────────────────────────────────────── */

describe('a claim match that arrives after the pane opened', () => {
  it('upgrades the OPEN pane with the sentence it grounded', async () => {
    const el = await mount();
    const stream = await askUnmatched(el, [source()]);
    const pane = await openPaneMidStream(el);

    // Opened mid-stream: the retrieved chunk is known, which part of it the answer used is not.
    expect(pane.citation).toEqual({
      startChar: 0,
      endChar: 40,
      excerpt: 'excerpt 0',
      sentenceText: null,
    });

    stream.emit('rag.citation_matches', {
      matches: [{ sentenceIndex: 0, sentenceText: 'The lock held.', similarity: 0.9, sourceIndex: 0 }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    expect(pane.citation?.sentenceText).toBe('The lock held.');
    // The anchor itself is untouched — the upgrade adds emphasis inside the chunk, it does not
    // re-anchor the pane on something else.
    expect(pane.citation?.startChar).toBe(0);
    expect(pane.citation?.endChar).toBe(40);
  });

  it('leaves the pane alone when the match belongs to a DIFFERENT TURN', async () => {
    // The upgrade is keyed on turn id AND source index. The source-index half is covered below; this
    // is the turn half, which is otherwise only code-verified: a second ask whose source 0 gains a
    // match must not re-anchor a pane opened from the FIRST turn's source 0 — same index, different
    // turn, and the two turns' source arrays are unrelated.
    const el = await mount();
    const first = await askUnmatched(el, [source()]);
    const pane = await openPaneMidStream(el);
    expect(pane.citation?.sentenceText).toBeNull();
    first.emit('done', {});
    first.end();
    await settle(el);

    // A SECOND turn, whose own source 0 is matched.
    const second = await askUnmatched(el, [{ ...source(), excerpt: 'excerpt from the second turn' }]);
    second.emit('rag.citation_matches', {
      matches: [
        { sentenceIndex: 0, sentenceText: 'A sentence from the second turn.', similarity: 0.9, sourceIndex: 0 },
      ],
    });
    second.emit('done', {});
    second.end();
    await settle(el);

    expect(pane.citation?.sentenceText).toBeNull();
    expect(pane.citation?.excerpt).toBe('excerpt 0');
  });

  it('leaves the pane alone when the match belongs to a DIFFERENT source', async () => {
    const el = await mount();
    const other = { ...source(), startChar: 100, endChar: 140, excerpt: 'excerpt 1' };
    const stream = await askUnmatched(el, [source(), other]);
    const pane = await openPaneMidStream(el); // opened on source 0

    stream.emit('rag.citation_matches', {
      matches: [{ sentenceIndex: 0, sentenceText: 'The lock held.', similarity: 0.9, sourceIndex: 1 }],
    });
    stream.emit('done', {});
    stream.end();
    await settle(el);

    expect(pane.citation?.sentenceText).toBeNull();
  });
});

/* ── 6. The scope guard: cited documents only ────────────────────────────────────────────────── */

describe('the pane reads CITED documents and nothing else', () => {
  const here = dirname(fileURLToPath(import.meta.url));

  it('has no attribute route into the document it shows', () => {
    // A `docPath` attribute would be a second way in — markup, a deeplink, a devtools poke.
    const declared = (Sv3Pane as unknown as { properties: Record<string, { attribute?: unknown }> })
      .properties;
    expect(declared.docPath?.attribute).toBe(false);
    expect(declared.citation?.attribute).toBe(false);
    const pane = document.createElement('jf-sv3-pane') as Sv3Pane;
    pane.setAttribute('docpath', 'f:/secrets/other.md');
    expect(pane.docPath).toBeNull();
  });

  it('has exactly ONE writer of the open document in the window', () => {
    // Structural, because an absence cannot be demonstrated by clicking: every assignment to the
    // pane's document is counted. The three are the constructor's null, the citation handler, and
    // the close. A fourth would be a route in from somewhere that is not a citation.
    const src = readFileSync(join(here, 'SearchV3View.ts'), 'utf8');
    const writes = src.match(/this\.paneDocPath\s*=(?!=)/g) ?? [];
    expect(writes).toHaveLength(3);
    // ...and the one that opens it reads its path from the citation event alone.
    expect(src).toContain('this.paneDocPath = detail.docPath;');
    // No search affordance reaches the reading surface: the window's search issuance and the pane
    // have no seam between them.
    const paneSrc = readFileSync(join(here, 'Sv3Pane.ts'), 'utf8');
    expect(paneSrc).not.toContain('submitSearch');
    expect(paneSrc).not.toContain('searchState');
  });

  it('imports the shared reader directly, never through another window', () => {
    const paneSrc = readFileSync(join(here, 'Sv3Pane.ts'), 'utf8');
    expect(paneSrc).toContain("import '../../components/documentPane/DocumentPane.js';");
    // Cited in prose (that is where the pattern comes from), imported from nowhere.
    expect(paneSrc).not.toMatch(/from '[^']*UnifiedChatView/);
    expect(paneSrc).not.toMatch(/import '[^']*UnifiedChatView/);
  });
});
