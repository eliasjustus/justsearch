// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §γ4 — multi-select shift-click in SearchSurface.
 *
 * Exercises the click handler's three branches:
 *   - plain click   → replace selection with [hit]
 *   - shift-click   → range from anchor to clicked index
 *   - ctrl/meta+click → toggle hit membership
 *
 * Each branch publishes through selectionState; the assertions read
 * the internal state directly.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import {
  __resetSelectionForTest,
  getSelection,
} from '../state/selectionState.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';

function mountSurface(results: Array<{ id: string; title: string; path: string }>): SearchSurface {
  __resetSelectionForTest();
  const surface = document.createElement('jf-search-surface') as SearchSurface;
  surface.host_ = createMockHostApi({
    search: {
      hitToSelectedItem: (h) => ({ id: h.id, title: h.title, path: h.path }),
    },
  });
  const fullResults = results.map((r) => ({
    docId: r.id,
    score: 1,
    fields: {},
    id: r.id,
    title: r.title,
    path: r.path,
  }));
  surface.s = {
    query: 'x',
    results: fullResults,
    totalHits: results.length,
    matchCount: results.length,
    facetsTruncated: false,
    isSearching: false,
    processingTimeMs: null,
    error: null,
  };
  return surface;
}

function clickRow(surface: SearchSurface, hitId: string, modifiers: Partial<MouseEventInit> = {}): void {
  const hit = surface.s.results.find((h) => h.id === hitId);
  if (!hit) throw new Error(`hit ${hitId} not in results`);
  const event = new MouseEvent('click', { ...modifiers, bubbles: true });
  // Reach into the private handler for a deterministic unit test —
  // happy-dom's lit-render path doesn't reliably fire Lit's @click
  // bindings without a full lifecycle attach.
  (surface as unknown as { handleClick: (h: typeof hit, e: MouseEvent) => void }).handleClick(hit, event);
}

const HITS = [
  { id: 'a', title: 'A', path: '/a' },
  { id: 'b', title: 'B', path: '/b' },
  { id: 'c', title: 'C', path: '/c' },
  { id: 'd', title: 'D', path: '/d' },
];

beforeEach(() => {
  __resetSelectionForTest();
});

describe('SearchSurface multi-select (tempdoc 508-followup §γ4)', () => {
  it('plain click selects a single hit', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'b');
    expect(s.selectedHitIds.size).toBe(1);
    expect(s.selectedHitIds.has('b')).toBe(true);
    const sel = getSelection();
    expect(sel.items).toHaveLength(1);
    expect(sel.surfaceId).toBe('core.search-surface');
  });

  it('subsequent plain click replaces the selection', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'a');
    clickRow(s, 'c');
    expect(s.selectedHitIds.size).toBe(1);
    expect(s.selectedHitIds.has('c')).toBe(true);
  });

  it('shift-click selects a range from anchor to clicked', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'a'); // anchor on a (index 0)
    clickRow(s, 'c', { shiftKey: true }); // range a..c
    expect(Array.from(s.selectedHitIds).sort()).toEqual(['a', 'b', 'c']);
    // Tempdoc 526 §17 T1B — multi-select publishes a single result-set
    // SelectionItem wrapping the N selected docs, not N individual
    // search-hit items. The substrate consumer (F9 menu's "Summarize all"
    // / "Ask about all" actions) reads the result-set kind.
    const sel = getSelection();
    expect(sel.items).toHaveLength(1);
    expect(sel.items[0]?.kind).toBe('result-set');
    const item = sel.items[0] as { kind: 'result-set'; items: ReadonlyArray<{ id: string }> };
    expect(item.items.map((r) => r.id).sort()).toEqual(['/a', '/b', '/c']);
  });

  it('shift-click range is direction-agnostic', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'd'); // anchor on d
    clickRow(s, 'b', { shiftKey: true }); // range b..d
    expect(Array.from(s.selectedHitIds).sort()).toEqual(['b', 'c', 'd']);
  });

  it('ctrl-click toggles a hit on then off', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'a');
    clickRow(s, 'c', { ctrlKey: true });
    expect(Array.from(s.selectedHitIds).sort()).toEqual(['a', 'c']);
    clickRow(s, 'c', { ctrlKey: true });
    expect(Array.from(s.selectedHitIds).sort()).toEqual(['a']);
  });

  it('meta-click behaves like ctrl-click', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'a');
    clickRow(s, 'd', { metaKey: true });
    expect(Array.from(s.selectedHitIds).sort()).toEqual(['a', 'd']);
  });

  it('publishes selectionCapabilities derived from search-hit default', () => {
    const s = mountSurface(HITS);
    clickRow(s, 'b');
    const sel = getSelection();
    const caps = sel.items[0]!.capabilities;
    expect(caps.has('open')).toBe(true);
    expect(caps.has('pin')).toBe(true);
  });
});
