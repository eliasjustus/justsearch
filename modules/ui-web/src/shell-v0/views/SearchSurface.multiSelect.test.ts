// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §γ4 — Search Thread S1 rework.
 *
 * The multi-select CLICK MECHANICS (plain/shift-range/ctrl-toggle, anchor
 * tracking) moved into the shared `<jf-results-card>` — see
 * ResultsCard.test.ts's "multi-select event model" suite, which asserts the
 * emitted `card-selection` detail directly (`SearchSurface.handleClick` no
 * longer exists; the card owns click handling now).
 *
 * This file keeps the surface-level HOST WIRING: `.selectedIds`/`.snapshot`
 * reach the mounted card, and a `card-selection` event publishes through
 * `selectionState` via `applySelection` — unchanged production logic on
 * SearchSurface that ResultsCard has no visibility into (single-hit
 * capability projection; multi-select >1 collapsing to ONE result-set
 * SelectionItem, tempdoc 526 §17 T1B).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './SearchSurface.js';
import { SearchSurface } from './SearchSurface.js';
import { __resetSelectionForTest, getSelection } from '../state/selectionState.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { CardSelectionDetail } from '../components/searchResults/ResultsCard.js';

interface TestHit {
  docId: string;
  score: number;
  fields: Record<string, unknown>;
  id: string;
  title: string;
  path: string;
}
function hit(id: string): TestHit {
  return { docId: id, score: 1, fields: {}, id, title: id, path: `/${id}` };
}

async function mount(results: string[]): Promise<SearchSurface> {
  __resetSelectionForTest();
  const surface = document.createElement('jf-search-surface') as SearchSurface;
  surface.host_ = createMockHostApi({
    search: { hitToSelectedItem: (h) => ({ id: h.id, title: h.title, path: h.path }) },
  });
  document.body.appendChild(surface);
  surface.s = {
    query: 'x',
    results: results.map(hit),
    totalHits: results.length,
    matchCount: results.length,
    facetsTruncated: false,
    isSearching: false,
    processingTimeMs: null,
    error: null,
  } as unknown as SearchSurface['s'];
  await surface.updateComplete;
  return surface;
}

function card(surface: SearchSurface): Element {
  const c = surface.shadowRoot?.querySelector('jf-results-card');
  if (!c) throw new Error('jf-results-card not mounted');
  return c;
}

/** Fires the same `card-selection` shape ResultsCard's real click handler emits. */
function fireCardSelection(surface: SearchSurface, ids: string[], primaryIndex = 0): void {
  card(surface).dispatchEvent(
    new CustomEvent<CardSelectionDetail>('card-selection', {
      detail: { ids, primaryId: ids[primaryIndex] ?? ids[0]!, primaryIndex },
      bubbles: true,
      composed: true,
    }),
  );
}

const HITS = ['a', 'b', 'c', 'd'];

beforeEach(() => {
  __resetSelectionForTest();
});

describe('SearchSurface → jf-results-card selection wiring (508-followup §γ4 / Search Thread S1)', () => {
  it('passes .selectedIds and .snapshot through to the mounted card', async () => {
    const s = await mount(HITS);
    const c = card(s) as unknown as { selectedIds: ReadonlySet<string>; snapshot: unknown };
    expect(c.selectedIds).toBe(s.selectedHitIds);
    expect(c.snapshot).toBe(s.s);
  });

  it('a single-id card-selection publishes a search-hit SelectionItem with the projected capabilities', async () => {
    const s = await mount(HITS);
    fireCardSelection(s, ['b'], 0);

    expect(s.selectedHitIds.size).toBe(1);
    expect(s.selectedHitIds.has('b')).toBe(true);
    const sel = getSelection();
    expect(sel.items).toHaveLength(1);
    expect(sel.surfaceId).toBe('core.search-surface');
    const caps = sel.items[0]!.capabilities;
    expect(caps.has('open')).toBe(true);
    expect(caps.has('pin')).toBe(true);
  });

  // The ONE selectionState assertion kept alive at surface level (Search Thread S1
  // scope note): multi-select (>1) collapses to a single result-set SelectionItem —
  // SearchSurface.applySelection's own behavior, not something the card can see.
  it('multi-select (>1) card-selection publishes ONE result-set SelectionItem with surfaceId core.search-surface', async () => {
    const s = await mount(HITS);
    fireCardSelection(s, ['a', 'b', 'c'], 0);

    expect(Array.from(s.selectedHitIds).sort()).toEqual(['a', 'b', 'c']);
    const sel = getSelection();
    expect(sel.surfaceId).toBe('core.search-surface');
    expect(sel.items).toHaveLength(1);
    expect(sel.items[0]?.kind).toBe('result-set');
    const item = sel.items[0] as { kind: 'result-set'; items: ReadonlyArray<{ id: string }> };
    expect(item.items.map((r) => r.id).sort()).toEqual(['/a', '/b', '/c']);
  });
});
