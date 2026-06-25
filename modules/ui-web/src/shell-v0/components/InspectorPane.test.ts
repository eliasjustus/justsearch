// @vitest-environment happy-dom

import { describe, expect, it, beforeEach, vi } from 'vitest';
import './InspectorPane.js';
import type { InspectorPane } from './InspectorPane.js';
import {
  setSelected,
  setOpen,
  setActiveTab,
  setInspectorState,
} from '../state/inspectorState.js';

function make(): InspectorPane {
  const el = document.createElement('jf-inspector-pane') as InspectorPane;
  document.body.appendChild(el);
  return el;
}

describe('InspectorPane (slice 462)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    // Reset state.
    setInspectorState({
      isOpen: false,
      selected: null,
      activeTab: 'preview',
      ai: { loading: false, text: '', error: null },
    });
  });

  it('renders nothing when state.isOpen=false', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.header')).toBeNull();
  });

  it('shows empty state when open but no selected item', async () => {
    const el = make();
    setOpen(true);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain(
      'Select a result',
    );
  });

  it('renders item title + path when selected', async () => {
    const el = make();
    setSelected({
      id: 'doc-1',
      title: 'doc.txt',
      path: 'C:\\path\\doc.txt',
    });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.title')?.textContent).toBe('doc.txt');
    expect(el.shadowRoot?.querySelector('.meta')?.textContent).toBe(
      'C:\\path\\doc.txt',
    );
  });

  it('tabs render and switching updates active class', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    await el.updateComplete;
    const tabs = Array.from(el.shadowRoot?.querySelectorAll('.tabs button') ?? []);
    expect(tabs.length).toBe(4);
    setActiveTab('answer');
    await el.updateComplete;
    const active = el.shadowRoot?.querySelector('.tabs button.active');
    expect(active?.textContent?.trim()).toBe('Answer');
  });

  it('Context tab shows the V1-deferral message', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    setActiveTab('context');
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain(
      'RAG context',
    );
  });

  it('renders OCR provenance for preview text', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => ({
          content: 'ALPHA DOCUMENT',
          textProvenance: 'ocr',
          visualExtractionEvidence: {
            route: 'ocr_full',
            ocrLanguage: 'eng',
            textQualityScore: 0.91,
            ocrMeanConfidence: 0.82,
            ocrFallbackRoute: 'direct_tesseract',
            contentTruncated: true,
          },
        }),
      })),
    );
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('OCR');
    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain(
      '82% OCR confidence',
    );
    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain(
      'direct tesseract fallback',
    );
    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('truncated');
    expect(el.shadowRoot?.querySelector('pre')?.textContent).toContain('ALPHA DOCUMENT');
  });

  it('close button sets state.isOpen=false', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    setOpen(true);
    await el.updateComplete;
    // 574 B — close is a <jf-button>; activate the native <button> in its composed <jf-control>.
    const closeBtn = el.shadowRoot?.querySelector('jf-button.icon');
    await (closeBtn as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const ctrl = closeBtn!.shadowRoot!.querySelector('jf-control')!;
    await (ctrl as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    (ctrl.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.header')).toBeNull();
  });
});

// Tempdoc 577 Phase 1 (Move F) — the Answer tab grounds through the one citation chain: the done
// event's sources/citations land in inspectorState, weave into the MarkdownBlock marks, and render
// the per-answer source chips whose click dispatches the existing `citation-select` deep-link.
describe('InspectorPane Answer-tab grounding (577 Phase 1)', () => {
  const SOURCES = [
    {
      parentDocId: 'docs/a.md',
      chunkIndex: 0,
      path: 'f:\docs\a.md',
      title: 'Doc A',
      excerpt: 'passage A',
      startLine: 3,
      endLine: 9,
      headingText: 'Intro',
    },
  ];
  const CITES = [{ sentenceText: 'Cited.', sourceIndex: 0, similarity: 0.7 }];

  beforeEach(() => {
    document.body.innerHTML = '';
    setInspectorState({
      isOpen: false,
      selected: null,
      activeTab: 'preview',
      ai: { loading: false, text: '', error: null },
    });
  });

  it('renders source chips + passes resolved citations to the markdown block', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    setActiveTab('answer');
    setInspectorState({
      ai: { loading: false, text: 'Cited. [1]', error: null, sources: SOURCES, citations: CITES },
    });
    await el.updateComplete;
    const chips = el.shadowRoot?.querySelectorAll('.answer-source-chip');
    expect(chips?.length).toBe(1);
    expect(chips?.[0]?.textContent).toContain('Doc A');
    const md = el.shadowRoot?.querySelector('jf-markdown-block') as unknown as {
      citations: Array<{ label: number }>;
    };
    expect(md.citations).toHaveLength(1);
    expect(md.citations[0]!.label).toBe(1);
  });

  it('chip click dispatches the citation-select deep-link with the exact passage detail', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    setActiveTab('answer');
    setInspectorState({
      ai: { loading: false, text: 'Cited.', error: null, sources: SOURCES, citations: [] },
    });
    await el.updateComplete;
    let detail: { parentDocId?: string; startLine?: number } | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent<{ parentDocId: string; startLine: number }>).detail;
    });
    (el.shadowRoot?.querySelector('.answer-source-chip') as HTMLButtonElement).click();
    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('docs/a.md');
    expect(detail!.startLine).toBe(3);
  });

  it('renders no chips when the answer carries no grounding', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    setActiveTab('answer');
    setInspectorState({ ai: { loading: false, text: 'Plain answer.', error: null } });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('.answer-source-chip').length).toBe(0);
  });

  it('selecting a new item clears the previous answer grounding (setSelected resets ai)', async () => {
    const el = make();
    setSelected({ id: 'd', title: 't', path: 'p' });
    setInspectorState({
      ai: { loading: false, text: 'Cited.', error: null, sources: SOURCES, citations: CITES },
    });
    setSelected({ id: 'd2', title: 't2', path: 'p2' });
    setActiveTab('answer');
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('.answer-source-chip').length).toBe(0);
  });
});
