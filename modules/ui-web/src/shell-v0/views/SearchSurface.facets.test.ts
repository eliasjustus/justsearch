// @vitest-environment happy-dom

/**
 * Tempdoc 577 Goal 1 Phase 6 (Move E) — Search Thread S1 rework.
 *
 * Facet-chip RENDERING (projection from counts, grouping, dismissable selected
 * values, and the `card-facet-toggle` emission on click) moved to
 * ResultsCard.test.ts — the shared `<jf-results-card>` owns rendering now, not
 * SearchSurface. This file keeps the surface-level HOST WIRING: `.snapshot`
 * (with its `facets`) and `.facetSelections` reach the mounted card, and a
 * `card-facet-toggle` event toggles the shared selection store then re-runs
 * the search through the ONE seam (`submitQuery`).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchSurface.js';
import { SearchSurface } from './SearchSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { SearchSnapshot } from '../plugin-api/plugin-types.js';
import { __resetSearchFiltersForTest, getFacetSelections } from '../state/searchFiltersState.js';

const base: SearchSnapshot = {
  query: 'pipeline',
  results: [{ docId: 'a', score: 1, fields: {}, id: 'a', title: 'A', path: '/a' } as never],
  totalHits: 1,
  matchCount: 25,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 12,
  error: null,
  facets: { file_kind: { markdown: 20, code: 8 }, language: { en: 25 } },
};

async function mount(s: SearchSnapshot, submitQuery = (): void => {}): Promise<SearchSurface> {
  const el = document.createElement('jf-search-surface') as SearchSurface;
  el.host_ = createMockHostApi({ search: { submitQuery } as never });
  document.body.appendChild(el);
  // connectedCallback seeds `s` from host getSearchState(); assign after mount
  // so the snapshot under test survives (same reason the searchTrace suite
  // drives the instance directly).
  el.s = s;
  await el.updateComplete;
  return el;
}

function card(el: SearchSurface): Element {
  const c = el.shadowRoot?.querySelector('jf-results-card');
  if (!c) throw new Error('jf-results-card not mounted');
  return c;
}

describe('SearchSurface → jf-results-card facet wiring (577 Phase 6 / Search Thread S1)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetSearchFiltersForTest();
  });
  afterEach(() => __resetSearchFiltersForTest());

  it('passes .snapshot (with facets) and .facetSelections through to the mounted card', async () => {
    const el = await mount(base);
    const c = card(el) as unknown as {
      snapshot: SearchSnapshot;
      facetSelections: Record<string, string[]>;
    };
    expect(c.snapshot).toBe(el.s);
    expect(c.snapshot.facets).toEqual(base.facets);
    expect(c.facetSelections).toEqual({});
  });

  it('a card-facet-toggle event toggles the selection and re-runs through the one seam (submitQuery)', async () => {
    const submit = vi.fn();
    const el = await mount(base, submit);

    card(el).dispatchEvent(
      new CustomEvent('card-facet-toggle', {
        detail: { field: 'file_kind', value: 'markdown' },
        bubbles: true,
        composed: true,
      }),
    );

    expect(getFacetSelections()).toEqual({ file_kind: ['markdown'] });
    expect(submit).toHaveBeenCalledTimes(1);
    await el.updateComplete;
    const c = card(el) as unknown as { facetSelections: Record<string, string[]> };
    expect(c.facetSelections).toEqual({ file_kind: ['markdown'] });
  });
});
