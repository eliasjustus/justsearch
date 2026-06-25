/**
 * @vitest-environment happy-dom
 *
 * Slice 493 — CitationsPanel tests (trust-tier rendering).
 */

import { describe, expect, it } from 'vitest';
import {
  CitationsPanel,
  type CitationMatch,
  type RetrievalCitation,
} from './CitationsPanel.js';
import './CitationsPanel.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  // 559 C-1: sources are collapsed by default; open the disclosure so the
  // body-inspecting assertions below see the cards. (The default-collapsed
  // behavior itself is covered by the dedicated test at the end.)
  (el as unknown as { sourcesExpanded: boolean }).sourcesExpanded = true;
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function fakeSource(
  overrides: Partial<RetrievalCitation> = {},
): RetrievalCitation {
  return {
    parentDocId: 'doc.fake',
    chunkIndex: 0,
    chunkTotal: 1,
    startChar: 0,
    endChar: 100,
    score: 0.85,
    excerpt: 'Default excerpt text.',
    startLine: 0,
    endLine: 5,
    headingText: '',
    headingLevel: 0,
    ...overrides,
  };
}

function fakeCitation(overrides: Partial<CitationMatch> = {}): CitationMatch {
  return {
    sentenceIndex: 0,
    sentenceText: 'Default sentence text.',
    chunkIndex: 0,
    similarity: 0.85,
    parentDocId: 'doc.fake',
    ...overrides,
  };
}

describe('CitationsPanel', () => {
  it('renders nothing when both arrays are empty', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [];
    el.sources = [];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').trim();
    expect(text).toBe('');
    el.remove();
  });

  // Tempdoc 603 C1 — sources are graded by GROUNDING (faithfulness from the citation-matches), NOT the
  // BM25 retrieval score. A cited source ranks under "Grounds the answer"; a retrieved-but-uncited source
  // is demoted to the collapsed "retrieved · not cited" group (never "high confidence").
  it('grades a CITED source by grounding (Grounds the answer), joining by ARRAY POSITION not the doc-ordinal', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({
        parentDocId: 'doc.architecture',
        chunkIndex: 2, // the source's DOC-ORDINAL (display only) — deliberately != its array position (0)
        score: 0.92, // BM25 — must NOT surface as a trust %
        excerpt: 'The system uses a three-process model.',
      }),
    ];
    // 603 PART X.B — the match's chunkIndex is the source's POSITION in this list (0), not the doc-ordinal (2).
    // Joining by doc-ordinal (the §1 bug) would find nothing here and wrongly read "not cited".
    el.citations = [
      fakeCitation({ parentDocId: 'doc.architecture', chunkIndex: 0, similarity: 0.8 }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 source');
    expect(text).toContain('Grounds the answer');
    expect(text).toContain('Grounds 1 sentence');
    expect(text).not.toContain('92%'); // the BM25 retrieval number is gone (559 §5)
    expect(text).toContain('three-process model');
    el.remove();
  });

  it('demotes a retrieved-but-UNCITED source to "retrieved · not cited", never high confidence', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      // cited: grounds the answer
      fakeSource({ parentDocId: 'a.md', chunkIndex: 0, excerpt: 'Grounded passage' }),
      // retrieved (high BM25) but never cited → demoted
      fakeSource({ parentDocId: 'b.md', chunkIndex: 1, score: 0.99, excerpt: 'Unused passage' }),
    ];
    el.citations = [fakeCitation({ parentDocId: 'a.md', chunkIndex: 0, similarity: 0.85 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Grounds the answer');
    expect(text).toContain('a.md');
    // the uncited high-BM25 source is in the collapsed "retrieved (not cited)" group, NOT shown by default
    expect(text).toContain('1 retrieved (not cited)');
    expect(text).not.toContain('Unused passage');
    el.remove();
  });

  it('no citation-matches → neutral flat list (no trust grade, no BM25 %)', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource({ parentDocId: 'a.md', score: 0.92, excerpt: 'Retrieved text' })];
    el.citations = []; // matcher didn't run
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 source retrieved');
    expect(text).toContain('Retrieved text');
    expect(text).not.toContain('Grounds');
    expect(text).not.toContain('Grounds the answer');
    expect(text).not.toContain('92%');
    el.remove();
  });

  it('shows heading breadcrumb when present', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({
        parentDocId: 'doc.md',
        score: 0.8,
        headingText: 'Getting Started',
      }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Getting Started');
    el.remove();
  });

  it('renders citation-match fallback with confidence bars', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [
      fakeCitation({ similarity: 0.75, sentenceText: 'Matched claim.' }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 citation');
    expect(text).toContain('75%');
    expect(text).toContain('Matched claim');
    el.remove();
  });

  // The score clamp lives in the citation-match fallback (the one place a % is still shown — the
  // per-sentence faithfulness similarity). Source cards no longer show a % (603 C1).
  it('clamps citation-match scores above 1.0 to 100%', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [fakeCitation({ similarity: 2.09 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('100%');
    expect(text).not.toContain('209%');
    const fill = el.shadowRoot?.querySelector('.fill') as HTMLElement | null;
    expect(fill?.style.width).toBe('100%');
    el.remove();
  });

  it('clamps negative citation-match scores to 0%', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [fakeCitation({ similarity: -0.5 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('0%');
    el.remove();
  });

  it('fires citation-select exactly once per click', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource()];
    document.body.appendChild(el);
    await settle(el);
    let count = 0;
    el.addEventListener('citation-select', () => count++);
    const btn = el.shadowRoot?.querySelector('button.source') as HTMLElement;
    btn?.click();
    expect(count).toBe(1);
    el.remove();
  });

  it('collapses sources by default and discloses on toggle (559 C-1)', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource(), fakeSource()];
    // 603 C1 / PART X.B — the collapsible disclosure is the GROUNDED (tiered) path; supply a match per
    // source POSITION (0 and 1) so both are cited and render through renderTieredSources (the flat
    // no-matches path has no disclosure). Grounding joins by array position, so one match per index.
    el.citations = [fakeCitation({ chunkIndex: 0 }), fakeCitation({ chunkIndex: 1 })];
    document.body.appendChild(el);
    // Raw settle (no auto-expand) — assert the default-collapsed state.
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const header = el.shadowRoot?.querySelector('button.panel-header');
    expect(header?.getAttribute('aria-expanded')).toBe('false');
    expect(el.shadowRoot?.querySelector('button.source')).toBeNull();
    expect((header?.textContent ?? '').replace(/\s+/g, ' ')).toContain('2 sources');
    // Toggling discloses the cards.
    (header as HTMLElement).click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(header?.getAttribute('aria-expanded')).toBe('true');
    expect(el.shadowRoot?.querySelectorAll('button.source').length).toBe(2);
    el.remove();
  });
});
