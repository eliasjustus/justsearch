// @vitest-environment happy-dom

/**
 * ResultsCard.searchTrace.test.ts — Search Thread S1 (the conformance guard
 * cited by ResultsCard's own docstring / the execution-surfaces register,
 * `fe-results-card`: it projects from `SearchTrace.effectiveMode`).
 *
 * Ports two suites that used to live on SearchSurface into card-level checks,
 * because the behavior itself moved into the ONE shared `<jf-results-card>`:
 *
 *  - SearchSurface.searchTrace.test.ts's per-hit "Why this result?" disclosure
 *    assertions (tempdoc 549 G111 / 577 Ext I). `SearchSurface.renderWhy` no
 *    longer exists — ResultsCard.renderRow inlines the shared
 *    `renderWhyDisclosure` directly, so this suite drives the real rendered
 *    DOM instead of reaching into a private method.
 *  - SearchSurface.retrievalMode.test.ts's retrieval-mode-indicator assertions
 *    (tempdoc 598 R1). `SearchSurface.renderRetrievalMode` no longer exists —
 *    it moved into ResultsCard entirely (`renderRetrievalMode` + the
 *    `data-testid="retrieval-mode"` span in the meta line).
 *
 * SearchSurface's OWN query-level explain panel (`renderExplainPanel` / G33,
 * the `<jf-search-trace>` mount) is a DIFFERENT feature, untouched by Search
 * Thread S1, and stays covered by the slimmed SearchSurface.searchTrace.test.ts.
 *
 * The `traceChipsFor` chip-formatting test is pure-function (no DOM), ported
 * here unchanged because it directly guards the per-hit rationale grammar the
 * Why-disclosure renders from.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import './ResultsCard.js';
import type { ResultsCard, CardSnapshot, CardHit } from './ResultsCard.js';
import { traceChipsFor } from './whyThisResult.js';
import { setUiMode, __resetUiModeForTest } from '../../state/uiModeState.js';

const BASE: CardSnapshot = {
  query: 'x',
  results: [],
  matchCount: 1,
  totalHits: 1,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: null,
  error: null,
};

function hit(id: string, extra: Partial<CardHit> = {}): CardHit {
  return { id, title: `Title ${id}`, path: `/${id}.md`, ...extra };
}

async function mount(snapshot: CardSnapshot): Promise<ResultsCard> {
  const el = document.createElement('jf-results-card') as ResultsCard;
  el.snapshot = snapshot;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function trace(effectiveMode: string): unknown {
  return { version: 1, decisionKind: 'multi_leg', effectiveMode, stages: [] };
}

// Tempdoc 696 — uiMode is a module-level store; reset after every test so a Detailed-mode test cannot
// leak into a later block (696 review §6 hygiene).
afterEach(() => __resetUiModeForTest());

describe('ResultsCard — retrieval-mode indicator (ported from SearchSurface.retrievalMode.test, tempdoc 598 R1)', () => {
  beforeEach(() => __resetUiModeForTest());

  // Tempdoc 696 (C2) — the technical mode labels render in Detailed mode.
  it('Detailed: reads HYBRID as "Semantic + keyword"', async () => {
    setUiMode('advanced');
    const el = await mount({ ...BASE, results: [hit('a')], searchTrace: trace('HYBRID') });
    const modeEl = el.shadowRoot?.querySelector('[data-testid="retrieval-mode"]');
    expect(modeEl?.getAttribute('data-mode')).toBe('HYBRID');
    expect(modeEl?.textContent).toContain('Semantic + keyword');
  });

  it('Detailed: reads VECTOR as "Semantic"', async () => {
    setUiMode('advanced');
    const el = await mount({ ...BASE, results: [hit('a')], searchTrace: trace('VECTOR') });
    const modeEl = el.shadowRoot?.querySelector('[data-testid="retrieval-mode"]');
    expect(modeEl?.textContent).toContain('Semantic');
  });

  it('Detailed: reads TEXT as "Keyword" (honest keyword fallback, not semantic)', async () => {
    setUiMode('advanced');
    const el = await mount({ ...BASE, results: [hit('a')], searchTrace: trace('TEXT') });
    const modeEl = el.shadowRoot?.querySelector('[data-testid="retrieval-mode"]');
    expect(modeEl?.textContent).toContain('Keyword');
    expect(modeEl?.textContent).not.toContain('Semantic');
  });

  // Tempdoc 696 (C2) — Simple mode (default) shows the plain-language labels.
  it('Simple (default): reads TEXT as "exact-word search" and VECTOR as "meaning-based"', async () => {
    const elText = await mount({ ...BASE, results: [hit('a')], searchTrace: trace('TEXT') });
    expect(elText.shadowRoot?.querySelector('[data-testid="retrieval-mode"]')?.textContent).toContain(
      'exact-word search',
    );
    const elVec = await mount({ ...BASE, results: [hit('b')], searchTrace: trace('VECTOR') });
    expect(elVec.shadowRoot?.querySelector('[data-testid="retrieval-mode"]')?.textContent).toContain(
      'meaning-based',
    );
  });

  it('renders nothing when no trace is present', async () => {
    const el = await mount({ ...BASE, results: [hit('a')] });
    expect(el.shadowRoot?.querySelector('[data-testid="retrieval-mode"]')).toBeNull();
  });

  // Tempdoc 696 (C2) — the latency form: plain "found in Xs" in Simple, raw "Nms" in Detailed.
  it('Simple (default): renders a plain latency ("found in …s"), never raw ms', async () => {
    const el = await mount({ ...BASE, results: [hit('a')], processingTimeMs: 62 });
    const meta = el.shadowRoot?.querySelector('[data-testid="card-meta"]')?.textContent ?? '';
    expect(meta).toContain('found in 0.06s');
    expect(meta).not.toContain('62ms');
  });

  it('Detailed: renders the raw ms latency', async () => {
    setUiMode('advanced');
    const el = await mount({ ...BASE, results: [hit('a')], processingTimeMs: 62 });
    const meta = el.shadowRoot?.querySelector('[data-testid="card-meta"]')?.textContent ?? '';
    expect(meta).toContain('62ms');
  });

  // Tempdoc 696 (C4) — the result location: a humanized breadcrumb in Simple, the full path in Detailed;
  // a drive-root file never re-leaks the raw path in Simple (696 review §1).
  it('Simple (default): a result location renders as a folder breadcrumb, not the raw path', async () => {
    const p = 'f:\\proj\\ssot\\docs\\help\\getting-started.md';
    const el = await mount({ ...BASE, results: [hit('a', { path: p })] });
    const pathEl = el.shadowRoot?.querySelector('.row .path');
    expect(pathEl?.textContent?.trim()).toBe('ssot › docs › help');
    expect(pathEl?.getAttribute('title')).toBe(p);
  });

  it('Detailed: a result location renders the full path', async () => {
    setUiMode('advanced');
    const p = 'f:\\proj\\ssot\\docs\\help\\getting-started.md';
    const el = await mount({ ...BASE, results: [hit('a', { path: p })] });
    const pathEl = el.shadowRoot?.querySelector('.row .path');
    expect(pathEl?.textContent).toContain('getting-started.md');
    expect(pathEl?.getAttribute('title')).toBe(p);
  });

  it('Simple: a drive-root file shows an empty location (no raw-path re-leak — 696 review §1)', async () => {
    const el = await mount({ ...BASE, results: [hit('a', { path: 'C:\\readme.txt' })] });
    const pathEl = el.shadowRoot?.querySelector('.row .path');
    expect(pathEl?.textContent?.trim()).toBe('');
    expect(pathEl?.textContent).not.toContain('C:');
  });
});

describe('ResultsCard — per-hit "Why this result?" disclosure (ported from SearchSurface.searchTrace.test, tempdoc 549 G111 / 577 Ext I)', () => {
  it('renders the Why disclosure from the unified per-hit trace (canonical path)', async () => {
    const el = await mount({
      ...BASE,
      results: [
        hit('a', {
          trace: [
            { id: 'sparse-retrieval', rank: 1, score: 5.5 },
            { id: 'cross-encoder', score: 0.1 },
          ] as never,
        }),
      ],
    });
    expect(el.shadowRoot?.querySelector('[data-testid="hit-why"]')).not.toBeNull();
  });

  it('omits the Why disclosure when the hit has no trace (CardHit carries no legacy provenance fallback)', async () => {
    const el = await mount({ ...BASE, results: [hit('a')] });
    expect(el.shadowRoot?.querySelector('[data-testid="hit-why"]')).toBeNull();
  });

  it('omits the Why disclosure when the hit carries an empty trace array', async () => {
    const el = await mount({ ...BASE, results: [hit('a', { trace: [] as never })] });
    expect(el.shadowRoot?.querySelector('[data-testid="hit-why"]')).toBeNull();
  });
});

describe('traceChipsFor — labeled, separated chips with worded negative deltas (577 Ext I)', () => {
  const HIT = { docId: 'a', score: 1, fields: {}, id: 'a', title: 'A', path: '/a' };

  it('formats stage signals as labeled chips with worded negative deltas', () => {
    const chips = traceChipsFor({
      ...HIT,
      trace: [
        { id: 'sparse-retrieval', rank: 2, score: 3.32 },
        { id: 'cross-encoder', score: -0.2 },
      ],
    });
    expect(chips).toEqual(['Sparse (BM25) · #2 · 3.32', 'Cross-encoder · ranked down (-0.20)']);
  });
});
