// @vitest-environment happy-dom

/**
 * Tempdoc 598 R1 (§34.1) — Search Thread S1 rework.
 *
 * The retrieval-mode-indicator assertions (HYBRID/VECTOR/TEXT labels; renders
 * nothing without a trace) moved entirely to ResultsCard.searchTrace.test.ts —
 * `SearchSurface.renderRetrievalMode` no longer exists; the shared
 * `<jf-results-card>` renders the indicator now. This file keeps a slim
 * end-to-end check that the surface's `.snapshot` (with its `searchTrace`)
 * reaches the mounted card and the indicator is visible through the surface's
 * own shadow DOM — the wiring, not the render logic.
 */
import { describe, it, expect } from 'vitest';
import './SearchSurface.js';
import { SearchSurface } from './SearchSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import type { SearchSnapshot } from '../plugin-api/plugin-types.js';

const HIT = { docId: 'a', score: 1, fields: {}, id: 'a', title: 'A', path: '/a' };

const base: SearchSnapshot = {
  query: 'x',
  results: [HIT],
  totalHits: 1,
  matchCount: 1,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: 12,
  error: null,
};

function trace(effectiveMode: string): unknown {
  return { version: 1, decisionKind: 'multi_leg', effectiveMode, stages: [] };
}

async function mount(s: SearchSnapshot): Promise<SearchSurface> {
  const el = document.createElement('jf-search-surface') as SearchSurface;
  el.host_ = createMockHostApi({});
  document.body.appendChild(el);
  el.s = s;
  await el.updateComplete;
  const c = el.shadowRoot?.querySelector('jf-results-card') as
    | (HTMLElement & { updateComplete: Promise<boolean> })
    | null;
  if (c) await c.updateComplete;
  return el;
}

function modeEl(el: SearchSurface): Element | null | undefined {
  return el.shadowRoot?.querySelector('jf-results-card')?.shadowRoot?.querySelector(
    '[data-testid="retrieval-mode"]',
  );
}

describe('SearchSurface — retrieval-mode indicator now renders via the shared jf-results-card (Search Thread S1)', () => {
  it('surfaces the HYBRID indicator through the surface shadow DOM (end-to-end wiring)', async () => {
    const el = await mount({ ...base, searchTrace: trace('HYBRID') });
    expect(modeEl(el)?.textContent).toContain('Semantic + keyword');
  });

  it('surfaces the TEXT indicator as "Keyword", never "Semantic" (end-to-end wiring)', async () => {
    const el = await mount({ ...base, searchTrace: trace('TEXT') });
    expect(modeEl(el)?.textContent).toContain('Keyword');
    expect(modeEl(el)?.textContent).not.toContain('Semantic');
  });

  it('omits the indicator when no trace is present (end-to-end wiring)', async () => {
    const el = await mount({ ...base });
    expect(modeEl(el)).toBeFalsy();
  });
});
