// @vitest-environment happy-dom

/**
 * Tempdoc 577 Goal 1 Phase 6 (Move E) — facet chips: the set describes itself.
 *
 * Pins: the chip row projects from the response's emitted facet counts (no
 * hand-authored filter set); a chip click toggles the selection and re-runs
 * the search through the one seam; selected values absent from the current
 * counts still render (dismissable).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { SearchSnapshot } from '../plugin-api/plugin-types.js';
import {
  __resetSearchFiltersForTest,
  toggleFacetValue,
  getFacetSelections,
} from '../state/searchFiltersState.js';

const base: SearchSnapshot = {
  query: 'pipeline',
  results: [
    { docId: 'a', score: 1, fields: {}, id: 'a', title: 'A', path: '/a' } as never,
  ],
  totalHits: 1,
  matchCount: 25,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 12,
  error: null,
  facets: { file_kind: { markdown: 20, code: 8 }, language: { en: 25 } },
};

function mount(s: SearchSnapshot, submitQuery = (): void => {}): SearchSurface {
  const el = document.createElement('jf-search-surface') as SearchSurface;
  el.host_ = createMockHostApi({ search: { submitQuery } as never });
  document.body.appendChild(el);
  // connectedCallback seeds `s` from host getSearchState(); assign after mount
  // so the snapshot under test survives (same reason the searchTrace suite
  // drives the instance directly).
  el.s = s;
  return el;
}

describe('SearchSurface facet chips (577 Phase 6)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetSearchFiltersForTest();
  });
  afterEach(() => __resetSearchFiltersForTest());

  it('projects chips from the emitted facet counts, grouped and labeled', async () => {
    const el = mount(base);
    await el.updateComplete;
    const row = el.shadowRoot?.querySelector('[data-testid="facet-row"]');
    expect(row).not.toBeNull();
    // Value and count are separate spans (visual gap is CSS); assert both parts.
    const chips = Array.from(row!.querySelectorAll('.facet-chip')).map((c) => ({
      text: c.textContent ?? '',
      count: c.querySelector('.facet-count')?.textContent ?? null,
    }));
    expect(chips.some((c) => c.text.startsWith('markdown') && c.count === '20')).toBe(true);
    expect(chips.some((c) => c.text.startsWith('code') && c.count === '8')).toBe(true);
    expect(chips.some((c) => c.text.startsWith('en') && c.count === '25')).toBe(true);
    const groups = Array.from(row!.querySelectorAll('.facet-group-label')).map(
      (g) => g.textContent,
    );
    expect(groups).toContain('Type');
    expect(groups).toContain('Language');
  });

  it('renders nothing when the response carried no facets and nothing is selected', async () => {
    const el = mount({ ...base, facets: null });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('[data-testid="facet-row"]')).toBeNull();
  });

  it('chip click toggles the selection and re-runs through the one seam (submitQuery)', async () => {
    const submit = vi.fn();
    const el = mount(base, submit);
    await el.updateComplete;
    const chip = Array.from(
      el.shadowRoot?.querySelectorAll('.facet-chip') ?? [],
    ).find((c) => c.textContent?.includes('markdown')) as HTMLButtonElement;
    chip.click();
    expect(getFacetSelections()).toEqual({ file_kind: ['markdown'] });
    expect(submit).toHaveBeenCalledTimes(1);
    await el.updateComplete;
    expect(chip.getAttribute('aria-pressed')).toBe('true');
  });

  it('a selected value absent from the current counts still renders (dismissable)', async () => {
    toggleFacetValue('file_kind', 'pdf'); // not in base.facets.file_kind
    const el = mount(base);
    await el.updateComplete;
    const chips = Array.from(el.shadowRoot?.querySelectorAll('.facet-chip') ?? []).map(
      (c) => c.textContent?.trim(),
    );
    expect(chips.some((t) => t?.startsWith('pdf'))).toBe(true);
  });
});
