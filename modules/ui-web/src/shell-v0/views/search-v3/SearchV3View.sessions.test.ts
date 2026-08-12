// @vitest-environment happy-dom

/**
 * Search v3's sidebar sessions (tempdoc 822 Phase A2) — the WINDOW-level consequences of
 * `sv3-sessions.ts`, whose value semantics are tested without a DOM in `sv3-sessions.test.ts`.
 *
 * The store is the real shared one, as in Phase A1; the only stub is its single exit, the global
 * fetch, which is also why no case here can reach the network. Two mechanisms are asserted rather
 * than appearances:
 *
 *  - **A row click is ONE search.** The click goes through the same issuance the composer's send
 *    uses, so a second path (or a duplicated dispatch) shows up as a second `fetch` call.
 *  - **The in-motion dot belongs to the session that asked.** The fetch is held open deliberately so
 *    the in-flight frame can be read, then released — a case that only looked at the settled frame
 *    would pass with the indicator wired to nothing.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3SessionRow } from './Sv3SessionRow.js';
import { resetSearchState } from '../../state/searchState.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

const hit = (path: string): Record<string, unknown> => ({ id: `doc:${path}`, fields: { path } });

const respond = (body: Record<string, unknown>): void => {
  fetchMock.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(body) });
};

/** Hold the response open, so the in-flight frame is observable instead of raced past. */
function holdResponse(body: Record<string, unknown>): () => void {
  let release = (): void => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  fetchMock.mockImplementation(async () => {
    await held;
    return { ok: true, status: 200, json: () => Promise.resolve(body) };
  });
  return release;
}

beforeEach(() => {
  fetchMock = vi
    .fn()
    .mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ results: [] }) });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
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

/** Type a draft and press send — the affordances a user has, not an internal call. */
async function send(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  composer.shadowRoot
    ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
    ?.click();
}

/** Settle the round trip and both renders (the window's, then the region's). */
async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 4; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function rowsOf(el: Mounted): Promise<Sv3SessionRow[]> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [
    ...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? []),
  ] as Sv3SessionRow[];
  await Promise.all(rows.map((r) => r.updateComplete));
  return rows;
}

const groupLabelsOf = async (el: Mounted): Promise<string[]> => {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  return [
    ...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? []),
  ].map((n) => n.textContent?.trim() ?? '');
};

/** Click a row the way a pointer does: on its button, which bubbles out through the shadow root. */
const clickRow = async (row: Sv3SessionRow): Promise<void> => {
  row.shadowRoot?.querySelector<HTMLButtonElement>('button')?.click();
};

async function newSearch(el: Mounted): Promise<void> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  sidebar.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-new"]')?.click();
  await el.updateComplete;
}

describe('a submitted search becomes a session in the sidebar', () => {
  it('creates exactly one session, titled with the query', async () => {
    const el = await mount();
    await send(el, 'northfield lease');
    await settle(el);

    const rows = await rowsOf(el);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.label).toBe('northfield lease');
    expect(await groupLabelsOf(el)).toEqual(['Today']);
    // The empty state is gone the moment there is a session to show.
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(sidebar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-empty"]')).toBeNull();
  });

  it('UPDATES the same session on a second submit rather than adding a second row', async () => {
    const el = await mount();
    await send(el, 'vendor risk');
    await settle(el);
    await send(el, 'vendor risk register');
    await settle(el);

    const rows = await rowsOf(el);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.label).toBe('vendor risk register');
  });

  it('renders no group label at all before the first search', async () => {
    const el = await mount();
    expect(await groupLabelsOf(el)).toEqual([]);
    expect(await rowsOf(el)).toHaveLength(0);
  });

  it('keeps the group label out of the tab order once one exists', async () => {
    const el = await mount();
    await send(el, 'freight');
    await settle(el);
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const labels = [
      ...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? []),
    ];
    expect(labels).toHaveLength(1);
    for (const label of labels) {
      expect(label.tagName).toBe('DIV');
      expect(label.hasAttribute('tabindex')).toBe(false);
      expect(label.querySelector('button, a, input')).toBeNull();
    }
  });
});

describe('New search parks the session and returns the window to the hero', () => {
  it('empties the draft, un-docks, and keeps the session that was there', async () => {
    const el = await mount();
    await send(el, 'first query');
    await settle(el);
    expect(el.getAttribute('composer-state')).toBe('docked');

    await newSearch(el);
    expect(el.getAttribute('composer-state')).toBe('hero');
    // A leftover draft would be the previous session's text sitting in a fresh one.
    expect((await fieldOf(el)).value).toBe('');
    // The previous session is parked, not dropped...
    const rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['first query']);
    // ...and nothing is claimed, so no row is current.
    expect(rows.filter((r) => r.active)).toHaveLength(0);
  });

  it('opens the NEXT submit as a second session, newest at the top', async () => {
    const el = await mount();
    await send(el, 'first query');
    await settle(el);
    await newSearch(el);
    await send(el, 'second query');
    await settle(el);

    const rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['second query', 'first query']);
    expect(rows.filter((r) => r.active).map((r) => r.label)).toEqual(['second query']);
  });

  it('claims nothing about the corpus again after returning to the hero', async () => {
    respond({ results: [], totalHits: 0, matchCount: 0 });
    const el = await mount();
    await send(el, 'nothing matches this');
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-empty"]')).toBeTruthy();

    await newSearch(el);
    await region(el, 'jf-sv3-main');
    // The zero-results verdict belonged to the parked session; the fresh one has asked nothing.
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-empty"]')).toBeNull();
  });
});

describe('clicking a session re-runs it', () => {
  const twoSessions = async (): Promise<SearchV3View & Mounted> => {
    const el = await mount();
    await send(el, 'first query');
    await settle(el);
    await newSearch(el);
    await send(el, 'second query');
    await settle(el);
    return el;
  };

  it('issues exactly ONE search, for that row’s query', async () => {
    vi.useFakeTimers();
    const el = await mount();
    await send(el, 'first query');
    await vi.advanceTimersByTimeAsync(2000);
    await newSearch(el);
    await send(el, 'second query');
    await vi.advanceTimersByTimeAsync(2000);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const older = (await rowsOf(el))[1];
    expect(older?.label).toBe('first query');
    await clickRow(older as Sv3SessionRow);
    // Past the store's keystroke debounce AND its settle window: a re-run is one request, not the
    // staged pair a keystroke path would schedule.
    await vi.advanceTimersByTimeAsync(2000);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [, init] = fetchMock.mock.calls[2] as [unknown, { body: string }];
    expect(JSON.parse(init.body).query).toBe('first query');
  });

  it('moves the claim to the clicked row, and moves aria-current with it', async () => {
    const el = await twoSessions();
    let rows = await rowsOf(el);
    const ariaOf = (row: Sv3SessionRow): string | null =>
      row.shadowRoot?.querySelector('button')?.getAttribute('aria-current') ?? null;
    // The newest session is the top row and holds the claim, having just been searched.
    expect(rows.map((r) => r.label)).toEqual(['second query', 'first query']);
    expect(rows.map(ariaOf)).toEqual(['true', null]);

    // Click the OTHER row, so this is genuinely a move rather than a re-assertion.
    await clickRow(rows[1] as Sv3SessionRow);
    await settle(el);
    rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['second query', 'first query']);
    expect(rows.map(ariaOf)).toEqual([null, 'true']);
    expect(rows.filter((r) => r.active)).toHaveLength(1);
  });

  it('docks the window again when a row is clicked from the hero', async () => {
    const el = await twoSessions();
    await newSearch(el);
    expect(el.getAttribute('composer-state')).toBe('hero');

    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);
    expect(el.getAttribute('composer-state')).toBe('docked');
    expect((await rowsOf(el)).filter((r) => r.active).map((r) => r.label)).toEqual(['first query']);
  });

  it('re-runs in place: the list neither grows nor reorders', async () => {
    const el = await twoSessions();
    const before = (await rowsOf(el)).map((r) => r.label);
    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);
    expect((await rowsOf(el)).map((r) => r.label)).toEqual(before);
  });
});

describe('the in-motion dot belongs to the running session alone', () => {
  const pingingOf = (rows: Sv3SessionRow[]): Sv3SessionRow[] =>
    rows.filter((r) => r.shadowRoot?.querySelector('.sv3-anim-status-ping') !== null);

  it('shows on the active session while the pass is in flight, and on no other row', async () => {
    const el = await mount();
    await send(el, 'first query');
    await settle(el);
    await newSearch(el);

    const release = holdResponse({ results: [hit('a/1.md')], totalHits: 1, matchCount: 1 });
    await send(el, 'second query');
    await el.updateComplete;
    let rows = await rowsOf(el);
    expect(rows).toHaveLength(2);
    // Exactly one dot, on the session that asked — the parked one is resting and says its age.
    expect(pingingOf(rows).map((r) => r.label)).toEqual(['second query']);
    expect(rows[0]?.status).toBe('in-motion');
    expect(rows[1]?.status).toBe('resting');
    expect(rows[1]?.meta).toBe('now');

    release();
    await settle(el);
    rows = await rowsOf(el);
    // ...and the colour is spent only while the pass runs: a settled session shows a timestamp.
    expect(pingingOf(rows)).toHaveLength(0);
    expect(rows.map((r) => r.status)).toEqual(['resting', 'resting']);
    expect(rows[0]?.meta).toBe('now');
  });

  it('still shows it for a re-query BEHIND displayed results, which the store refines quietly', async () => {
    // The store suppresses `isSearching` for a refined pass that runs behind results
    // (`state/searchState.ts:611`), so a dot wired to `isSearching` alone would go dark for exactly
    // the case a session most needs it: re-running a query whose old rows are still on screen.
    respond({ results: [hit('a/1.md')], totalHits: 1, matchCount: 1 });
    const el = await mount();
    await send(el, 'first query');
    await settle(el);

    const release = holdResponse({ results: [hit('a/2.md')], totalHits: 1, matchCount: 1 });
    await clickRow((await rowsOf(el))[0] as Sv3SessionRow);
    await el.updateComplete;
    expect(pingingOf(await rowsOf(el)).map((r) => r.label)).toEqual(['first query']);
    release();
    await settle(el);
    expect(pingingOf(await rowsOf(el))).toHaveLength(0);
  });

  it('moves the dot when the re-run belongs to a different session', async () => {
    const el = await mount();
    await send(el, 'first query');
    await settle(el);
    await newSearch(el);
    await send(el, 'second query');
    await settle(el);

    const release = holdResponse({ results: [hit('b/2.md')], totalHits: 1, matchCount: 1 });
    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await el.updateComplete;
    const rows = await rowsOf(el);
    expect(pingingOf(rows).map((r) => r.label)).toEqual(['first query']);
    release();
    await settle(el);
    expect(pingingOf(await rowsOf(el))).toHaveLength(0);
  });
});
