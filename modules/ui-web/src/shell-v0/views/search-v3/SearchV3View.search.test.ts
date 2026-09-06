// @vitest-environment happy-dom

/**
 * Search v3's wiring to the SHARED search store (tempdoc 822 Phase A1).
 *
 * The store is the real one (`state/searchState.ts`) in every case here — mocking it would leave the
 * one thing this slice adds untested. What is stubbed is the store's single exit, the global fetch,
 * which is also why no case can reach the network.
 *
 * Two properties are asserted as MECHANISMS rather than appearances:
 *
 *  - **One send, one request.** The store's keystroke path debounces a pass and schedules a second;
 *    an explicit submit must supersede both. The cases advance past BOTH windows before counting.
 *  - **The count describes what renders.** The label is read back as a number and compared against
 *    the rows actually on screen, so a projection that counted one set and rendered another fails
 *    here rather than lying on screen.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import './Sv3Empty.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Empty } from './Sv3Empty.js';
import { resetSearchState } from '../../state/searchState.js';
import { matchCountLabel } from '../../components/searchResults/matchCountLabel.js';
import { MAIN_EMPTY, MAIN_UNREACHABLE, SV3_COMMAND_SEARCH_TEXT } from './fixtures.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { __seedForTest as seedErrorCatalog, __resetForTest as resetErrorCatalog } from '../../../i18n/errorCatalog.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

/** A response hit in the wire shape the store parses (`fields.path` is the display identity). */
const hit = (path: string): Record<string, unknown> => ({ id: `doc:${path}`, fields: { path } });

const respond = (body: Record<string, unknown>): void => {
  fetchMock.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(body) });
};

/**
 * The calls to the ONE issuance exit. Since Phase F6 the window also READS on mount and on a claim
 * (the app-wide conversation list, the canonical thread record), so a bare call count would answer
 * a different question than "how many times did this window issue?" — the filter keeps the
 * assertion on issuance, which is the thing that must be exactly one.
 */
const searches = (): unknown[][] =>
  fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/knowledge/search'));

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi
    .fn()
    .mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ results: [] }) });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  // The store is a module singleton: one case's results would otherwise be the next case's state.
  resetSearchState();
  resetErrorCatalog();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
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

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

async function type(el: Mounted, draft: string): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  return field;
}

/**
 * Type a draft and run the palette's "Search this text" — the affordance a user has, not an internal
 * call. Phase F1 moved the SEND control onto the ask tier, so this command is now the only way into
 * the search seam (`SearchV3View.onPaletteRun`); everything below it is unchanged, which is the
 * point: the seam still behaves exactly as A1 proved it did.
 */
async function send(el: Mounted, draft: string): Promise<void> {
  await type(el, draft);
  const palette = el.shadowRoot?.querySelector('jf-sv3-palette') as
    | (HTMLElement & { show(i: HTMLElement | null): Promise<void>; updateComplete: Promise<unknown> })
    | null;
  if (!palette) throw new Error('no palette in the window');
  await palette.show(null);
  await palette.updateComplete;
  palette.shadowRoot
    ?.querySelector<HTMLElement>(`#sv3-palette-item-${SV3_COMMAND_SEARCH_TEXT}`)
    ?.click();
}

/**
 * Settle the round trip: token resolution, the fetch, its `json()`, and then the two renders (the
 * window's and the region's). Each await is a macrotask turn, which also drains the microtasks.
 */
async function settle(el: Mounted): Promise<Mounted> {
  for (let turn = 0; turn < 4; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
  return region(el, 'jf-sv3-main');
}

const rowsOf = (main: Mounted): HTMLElement[] => [
  ...(main.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-main-row"]') ?? []),
];

const textOf = (main: Mounted, testid: string): string =>
  main.shadowRoot?.querySelector(`[data-testid="${testid}"]`)?.textContent?.trim() ?? '';

async function emptyElementOf(main: Mounted, testid: string): Promise<Sv3Empty & Mounted> {
  const el = main.shadowRoot?.querySelector(`[data-testid="${testid}"]`) as
    | (Sv3Empty & Mounted)
    | null;
  if (!el) throw new Error(`no [data-testid="${testid}"] in the content surface`);
  await el.updateComplete;
  return el;
}

describe('a send issues exactly one search, through the shared store', () => {
  it('runs one request for the draft and docks in the same act', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await send(el, 'northfield lease');
    // Past the store's 200ms keystroke debounce AND its 600ms settle: an explicit submit supersedes
    // both, so a second request here would be a search the user never asked for.
    await vi.advanceTimersByTimeAsync(2000);

    expect(searches()).toHaveLength(1);
    const [url, init] = searches()[0] as [unknown, { body: string }];
    // The endpoint is the shared store's, which is the observable form of "this window owns no client".
    expect(String(url)).toContain('/api/knowledge/search');
    expect(JSON.parse(init.body).query).toBe('northfield lease');
    // The morph still runs: the send is a search AND a state change, not one at the cost of the other.
    expect(el.getAttribute('composer-state')).toBe('docked');
  });

  it('never issues a search for an empty draft', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await send(el, '   ');
    await vi.advanceTimersByTimeAsync(2000);
    expect(searches()).toHaveLength(0);
    expect(el.getAttribute('composer-state')).toBe('hero');
  });

  it('is NOT what a plain send does any more — the composer talks to the model', async () => {
    // The course correction, asserted: the send control must not reach the search endpoint. It
    // reaches the ask tier instead, which is refused here because no model has reported in — so the
    // observable fact is that NOTHING was dispatched to the search store.
    vi.useFakeTimers();
    const el = await mount();
    await type(el, 'northfield lease');
    const composer = await region(el, 'jf-sv3-composer');
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await vi.advanceTimersByTimeAsync(2000);
    expect(
      fetchMock.mock.calls.filter(([url]) => String(url).includes('/api/knowledge/search')),
    ).toEqual([]);
  });
});

describe('the rows are the response, and the count describes the rows', () => {
  it('renders one row per hit, from the response fields', async () => {
    respond({
      results: [hit('Contracts/Northfield.pdf'), hit('Ops/Reviews/Q2.md')],
      totalHits: 2,
      matchCount: 2,
    });
    const el = await mount();
    await send(el, 'northfield');
    const main = await settle(el);

    const rows = rowsOf(main);
    expect(rows).toHaveLength(2);
    expect(rows.map((r) => r.querySelector('.row-title')?.textContent?.trim())).toEqual([
      'Northfield.pdf',
      'Q2.md',
    ]);
    expect(rows.map((r) => r.querySelector('.row-path')?.textContent?.trim())).toEqual([
      'Contracts/Northfield.pdf',
      'Ops/Reviews/Q2.md',
    ]);
  });

  it('counts what is on screen, through the shared label authority', async () => {
    respond({
      results: [hit('a/1.md'), hit('a/2.md'), hit('a/3.md'), hit('a/4.md')],
      totalHits: 40,
      matchCount: 12,
    });
    const el = await mount();
    await send(el, 'vendor');
    const main = await settle(el);

    const label = textOf(main, 'sv3-main-count');
    expect(label).toBe(matchCountLabel(12, 4, false, 40, false));
    // The probe: whatever the label leads with is the number of rows actually rendered. A projection
    // that counted one set and rendered another (or a row list sliced on the way out) fails HERE.
    const leading = Number(/\d+/.exec(label)?.[0]);
    expect(leading).toBe(rowsOf(main).length);
    expect(rowsOf(main)).toHaveLength(4);
  });

  it('names the surplus rather than hiding it when more renders than matched exactly', async () => {
    respond({ results: [hit('a/1.md'), hit('a/2.md'), hit('a/3.md')], totalHits: 3, matchCount: 1 });
    const el = await mount();
    await send(el, 'freight');
    const main = await settle(el);

    const label = textOf(main, 'sv3-main-count');
    expect(label).toBe(matchCountLabel(1, 3, false, 3, false));
    expect(Number(/\d+/.exec(label)?.[0])).toBe(rowsOf(main).length);
  });
});

describe('the four outcomes are distinct, and each says only what it knows', () => {
  it('shows the pending state while the request is in flight, then the answer', async () => {
    let release: (value: unknown) => void = () => undefined;
    fetchMock.mockReturnValue(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    const el = await mount();
    await send(el, 'insurance');
    const main = await settle(el);

    expect(
      main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-skeleton"]').length,
    ).toBeGreaterThan(0);
    // Pending is not emptiness and not a result: neither verdict may be on screen yet.
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-empty"]')).toBeNull();
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-count"]')).toBeNull();
    expect(rowsOf(main)).toHaveLength(0);

    release({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ results: [hit('Admin/Insurance/2026.pdf')], matchCount: 1 }),
    });
    const settled = await settle(el);
    expect(settled.shadowRoot?.querySelector('[data-testid="sv3-main-skeleton"]')).toBeNull();
    expect(rowsOf(settled)).toHaveLength(1);
  });

  it('renders the spec zero state when the corpus really did answer with nothing', async () => {
    respond({ results: [], totalHits: 0, matchCount: 0 });
    const el = await mount();
    await send(el, 'zzzz');
    const main = await settle(el);

    const empty = await emptyElementOf(main, 'sv3-main-empty');
    expect(empty.shadowRoot?.querySelector('[data-testid="sv3-empty-title"]')?.textContent?.trim())
      .toBe(MAIN_EMPTY.title);
    expect(
      empty.shadowRoot?.querySelector('[data-testid="sv3-empty-description"]')?.textContent?.trim(),
    ).toBe(MAIN_EMPTY.description);
    // The roomier region gets the roomier padding — the spec's one breakpoint on this component.
    expect(empty.hasAttribute('roomy')).toBe(true);
    expect(rowsOf(main)).toHaveLength(0);
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-unreachable"]')).toBeNull();
  });

  it('explains a transport failure with safe recovery guidance', async () => {
    fetchMock.mockRejectedValue(new Error('Failed to fetch'));
    const el = await mount();
    await send(el, 'northfield');
    const main = await settle(el);

    const unreachable = await emptyElementOf(main, 'sv3-main-unreachable');
    expect(
      unreachable.shadowRoot?.querySelector('[data-testid="sv3-empty-title"]')?.textContent?.trim(),
    ).toBe(MAIN_UNREACHABLE.title);
    // The store's own words for the failure, kept as checkable detail beside the state.
    expect(textOf(main, 'sv3-main-failure-detail')).toContain('Open Health to check the connection');
    expect(textOf(main, 'sv3-main-failure-detail')).not.toContain('Failed to fetch');
    expect(unreachable.getAttribute('role')).toBe('alert');
    // ...and it is NOT the zero state: claiming "nothing matched" here would invent a fact about a
    // corpus nothing was ever asked of.
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-empty"]')).toBeNull();
    expect(rowsOf(main)).toHaveLength(0);
  });

  it('treats a refused response the same way, and words it differently from zero results', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 502, json: () => Promise.resolve({}) });
    const el = await mount();
    await send(el, 'northfield');
    const main = await settle(el);

    await emptyElementOf(main, 'sv3-main-unreachable');
    expect(textOf(main, 'sv3-main-failure-detail')).toContain('Search could not be completed. Open Health');
    // The two states must be distinguishable on screen, not merely in the code that picked them.
    expect(MAIN_UNREACHABLE.title).not.toBe(MAIN_EMPTY.title);
    expect(MAIN_UNREACHABLE.description).not.toBe(MAIN_EMPTY.description);
  });

  it('906: a typed 502 explanation reaches the live failure surface', async () => {
    seedErrorCatalog({ 'errors.INDEX_UNAVAILABLE': 'The search index is not available.' });
    fetchMock.mockResolvedValue({ ok: false, status: 502, json: async () => ({
      error: 'wire fallback', errorCode: 'INDEX_UNAVAILABLE', i18nKey: 'errors.INDEX_UNAVAILABLE', retryable: true,
    }) });
    const el = await mount();
    await send(el, 'northfield');
    const main = await settle(el);
    const failure = await emptyElementOf(main, 'sv3-main-unreachable');
    expect(failure.getAttribute('role')).toBe('alert');
    expect(textOf(main, 'sv3-main-failure-detail')).toContain('The search index is not available. Try searching again.');
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-empty"]')).toBeNull();
    expect(searches()).toHaveLength(1);
  });

  it('recovers: a second send replaces the failure with the results it gets', async () => {
    fetchMock.mockRejectedValueOnce(new Error('Failed to fetch'));
    const el = await mount();
    await send(el, 'northfield');
    await settle(el);

    respond({ results: [hit('Contracts/Northfield.pdf')], totalHits: 1, matchCount: 1 });
    await send(el, 'northfield contract');
    const main = await settle(el);
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-unreachable"]')).toBeNull();
    expect(rowsOf(main)).toHaveLength(1);
  });
});
