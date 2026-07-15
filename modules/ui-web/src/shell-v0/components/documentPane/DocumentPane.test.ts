// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { setUiMode, __resetUiModeForTest } from '../../state/uiModeState.js';
import './DocumentPane.js';
import type { DocumentPane } from './DocumentPane.js';

function make(): DocumentPane {
  const el = document.createElement('jf-document-pane') as DocumentPane;
  document.body.appendChild(el);
  return el;
}

function stubFetchOnce(response: {
  ok?: boolean;
  status?: number;
  content?: string;
  textProvenance?: string | null;
  visualExtractionEvidence?: Record<string, unknown> | null;
}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: response.ok ?? true,
      status: response.status ?? 200,
      json: async () => ({
        content: response.content ?? '',
        textProvenance: response.textProvenance ?? null,
        visualExtractionEvidence: response.visualExtractionEvidence ?? null,
      }),
    })),
  );
}

async function flush(el: DocumentPane): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

/** Click the native button inside a `<jf-button class="icon">`'s composed `<jf-control>`. */
async function clickIconButton(host: Element): Promise<void> {
  const jfButton = host.shadowRoot?.querySelector('jf-button.icon');
  await (jfButton as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const ctrl = jfButton!.shadowRoot!.querySelector('jf-control')!;
  await (ctrl as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (ctrl.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

const MD_FIXTURE = ['# Title', '', 'Paragraph text here.', '', '- item a', '- item b'].join('\n');

describe('DocumentPane — empty state', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('renders the no-document empty state when docPath is null', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain('No document selected');
    expect(el.shadowRoot?.querySelector('.header')).toBeNull();
  });
});

describe('DocumentPane — load + render (markdown)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('fetches /api/preview for the docPath and renders the fixture as blocks in Rendered mode by default', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toContain(
      '/api/preview?docId=' + encodeURIComponent('notes/thread.md'),
    );
    expect(el.mode).toBe('rendered');
    const blocks = el.shadowRoot?.querySelectorAll('.block');
    expect(blocks?.length).toBe(3); // heading, paragraph, list
    expect(el.shadowRoot?.querySelector('.block')?.getAttribute('data-line-start')).toBe('0');
  });

  it('toggles to Source mode and back to Rendered', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const [renderedBtn, sourceBtn] = Array.from(
      el.shadowRoot?.querySelectorAll('.toggle-btn') ?? [],
    ) as HTMLButtonElement[];
    expect(renderedBtn!.getAttribute('aria-checked')).toBe('true');

    sourceBtn!.click();
    await el.updateComplete;
    expect(el.mode).toBe('source');
    expect(sourceBtn!.getAttribute('aria-checked')).toBe('true');
    expect(el.shadowRoot?.querySelector('pre.source')).not.toBeNull();
    expect(el.shadowRoot?.querySelectorAll('pre.source span[data-line]').length).toBe(
      MD_FIXTURE.split('\n').length,
    );

    renderedBtn!.click();
    await el.updateComplete;
    expect(el.mode).toBe('rendered');
  });

  it('a non-markdown docPath defaults to Source mode with Rendered disabled', async () => {
    stubFetchOnce({ content: 'plain text content' });
    const el = make();
    el.docPath = 'notes/readme.txt';
    await flush(el);

    expect(el.mode).toBe('source');
    const renderedBtn = el.shadowRoot?.querySelector('.toggle-btn') as HTMLButtonElement;
    // Soft block (aria-disabled, not native `disabled`) — 596 face 1.1: a native-disabled control
    // suppresses its own `title`, so the reason must stay reachable via focus/hover instead.
    expect(renderedBtn.getAttribute('aria-disabled')).toBe('true');
    expect(renderedBtn.title).toContain('only available for Markdown');
    renderedBtn.click();
    await el.updateComplete;
    expect(el.mode).toBe('source'); // soft-blocked click is a no-op, not a mode switch
  });
});

describe('DocumentPane — highlightRange / chunkRange', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('applies hl-strong to the covering block and scrolls it to block:center', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;

    el.highlightRange = { startLine: 2, endLine: 2 }; // the paragraph block
    await flush(el);

    const strong = el.shadowRoot?.querySelector('.block.hl-strong');
    expect(strong).not.toBeNull();
    expect(strong?.getAttribute('data-line-start')).toBe('2');
    expect(scrollSpy).toHaveBeenCalledWith({ block: 'center' });
  });

  it('tints the rest of a wider chunkRange with hl-weak, leaving the exact range hl-strong', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    el.highlightRange = { startLine: 2, endLine: 2 };
    el.chunkRange = { startLine: 0, endLine: 5 };
    await flush(el);

    const strongBlocks = el.shadowRoot?.querySelectorAll('.block.hl-strong');
    const weakBlocks = el.shadowRoot?.querySelectorAll('.block.hl-weak');
    expect(strongBlocks?.length).toBe(1);
    expect(weakBlocks?.length).toBe(2); // heading + list, both inside the chunk but not the exact range
  });

  it('applies hl-strong to the covering line span in Source mode', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/readme.txt'; // forces Source mode
    el.highlightRange = { startLine: 2, endLine: 3 };
    await flush(el);

    const spans = el.shadowRoot?.querySelectorAll('pre.source span.hl-strong');
    expect(spans?.length).toBe(2);
    expect(spans?.[0]?.getAttribute('data-line')).toBe('2');
    expect(spans?.[1]?.getAttribute('data-line')).toBe('3');
  });
});

// Search Thread Round-2 R1b — the highlight lands strong then decays to the quiet hl-weak tint +
// edge marker; chunkRange never gets the strong phase; reduced motion skips the loud phase.
describe('DocumentPane — highlight decay (Search Thread Round-2 R1b)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function stubReducedMotion(reduce: boolean): void {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: reduce,
      media: '(prefers-reduced-motion: reduce)',
      addEventListener: () => {},
      removeEventListener: () => {},
    } as unknown as MediaQueryList);
  }

  it('lands hl-strong, then decays to hl-weak after ~1.5s', async () => {
    stubReducedMotion(false);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    el.highlightRange = { startLine: 2, endLine: 2 };
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('.block.hl-weak')).toBeNull();

    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).toBeNull();
    const settled = el.shadowRoot?.querySelector('.block.hl-weak');
    expect(settled).not.toBeNull();
    expect(settled?.getAttribute('data-line-start')).toBe('2');
  });

  it('honors prefers-reduced-motion: lands quiet immediately, never rendering hl-strong', async () => {
    stubReducedMotion(true);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    el.highlightRange = { startLine: 2, endLine: 2 };
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).toBeNull();
    expect(el.shadowRoot?.querySelector('.block.hl-weak')).not.toBeNull();
  });

  it('a NEW highlightRange re-arms the strong phase even after a prior range decayed', async () => {
    stubReducedMotion(false);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    el.highlightRange = { startLine: 2, endLine: 2 };
    await el.updateComplete;
    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).toBeNull();

    el.highlightRange = { startLine: 0, endLine: 0 }; // the heading block
    await el.updateComplete;
    const strong = el.shadowRoot?.querySelector('.block.hl-strong');
    expect(strong).not.toBeNull();
    expect(strong?.getAttribute('data-line-start')).toBe('0');
  });

  it('the chunkRange tier never gets the strong phase, before or after decay', async () => {
    stubReducedMotion(false);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    el.highlightRange = { startLine: 2, endLine: 2 };
    el.chunkRange = { startLine: 0, endLine: 5 };
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    // Before decay: chunk siblings are hl-weak, the exact range is hl-strong.
    expect(el.shadowRoot?.querySelectorAll('.block.hl-weak').length).toBe(2);
    expect(el.shadowRoot?.querySelectorAll('.block.hl-strong').length).toBe(1);

    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    // After decay: the exact range folds into the same hl-weak tier — never hl-strong.
    expect(el.shadowRoot?.querySelectorAll('.block.hl-strong').length).toBe(0);
    expect(el.shadowRoot?.querySelectorAll('.block.hl-weak').length).toBe(3);
  });
});

// Search Thread Round-2 R5c — the reading-pane provenance header truncates through the shared
// formatDisplayPath authority (filename-preserving), not CSS end-truncation.
describe('DocumentPane — header path truncation (Search Thread Round-2 R5c)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    __resetUiModeForTest();
  });

  it('Detailed: renders a formatDisplayPath-truncated header text with the full path in title', async () => {
    setUiMode('advanced'); // Tempdoc 696 (C4) — the full/truncated path is the Detailed form.
    const longPath =
      'projects/deeply/nested/folder/structure/that/goes/on/for/a/while/before/reaching/the-actual-filename.md';
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = longPath;
    await flush(el);

    const pathEl = el.shadowRoot?.querySelector('.path');
    expect(pathEl?.getAttribute('title')).toBe(longPath);
    expect(pathEl?.textContent).toContain('the-actual-filename.md');
    expect(pathEl?.textContent?.trim()).not.toBe(longPath);
    expect(pathEl?.textContent).toContain('…');
  });

  it('Detailed: renders a short path verbatim (no truncation needed)', async () => {
    setUiMode('advanced');
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const pathEl = el.shadowRoot?.querySelector('.path');
    expect(pathEl?.textContent?.trim()).toBe('notes/thread.md');
    expect(pathEl?.getAttribute('title')).toBe('notes/thread.md');
  });

  it('Simple (default): renders a humanized folder breadcrumb, not the raw path (Tempdoc 696 C4)', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'f:\\justsearch-public\\ssot\\docs\\help\\getting-started.md';
    await flush(el);

    const pathEl = el.shadowRoot?.querySelector('.path');
    expect(pathEl?.textContent?.trim()).toBe('ssot › docs › help');
    // The full path stays reachable via the title tooltip.
    expect(pathEl?.getAttribute('title')).toBe('f:\\justsearch-public\\ssot\\docs\\help\\getting-started.md');
  });
});

describe('DocumentPane — zero-content diagnostic (tempdoc 671 parity)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('shows the provenance diagnostic even when content is empty', async () => {
    stubFetchOnce({
      content: '',
      textProvenance: 'vdu_pending',
      visualExtractionEvidence: { route: 'structured', ocrSkipReason: 'no_text_found' },
    });
    const el = make();
    el.docPath = 'scans/page.md';
    await flush(el);

    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('VDU pending');
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain('No preview available');
  });
});

describe('DocumentPane — VDU abstention-gate provenance (tempdoc 677)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('labels vdu_empty as VDU-ran-but-found-nothing, not the silent base-method fall-through', async () => {
    stubFetchOnce({
      content: 'OCR baseline text',
      textProvenance: 'vdu_empty',
    });
    const el = make();
    el.docPath = 'scans/blank-page.md';
    await flush(el);

    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('VDU: no text found');
  });

  it('labels vdu_rejected with the abstention-gate label and an explanatory tooltip', async () => {
    stubFetchOnce({
      content: 'OCR baseline text',
      textProvenance: 'vdu_rejected',
    });
    const el = make();
    el.docPath = 'scans/suspect-page.md';
    await flush(el);

    const sourceEl = el.shadowRoot?.querySelector('.preview-source');
    expect(sourceEl?.textContent).toContain('VDU: unreliable, not used');
    const labelSpan = sourceEl?.querySelector('span[title]');
    expect(labelSpan?.getAttribute('title')).toBe(
      'The automatic reader could not produce trustworthy text for this document, so search uses the original extraction.',
    );
  });
});

describe('DocumentPane — a11y + events', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('the scroll region is keyboard-focusable (tabindex=0 + role=region)', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const region = el.shadowRoot?.querySelector('.scroll-region');
    expect(region?.getAttribute('tabindex')).toBe('0');
    expect(region?.getAttribute('role')).toBe('region');
    expect(region?.getAttribute('aria-label')).toBeTruthy();
  });

  it('emits pane-close when the header close action activates', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    let closed = false;
    el.addEventListener('pane-close', () => {
      closed = true;
    });
    await clickIconButton(el);
    expect(closed).toBe(true);
  });
});

describe('DocumentPane — pane-visible-range (debounced scroll)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('emits the first/last visible source line after the scroll debounce settles', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/readme.txt'; // Source mode: one span per line, simplest geometry to mock
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    const region = el.shadowRoot!.querySelector('.scroll-region') as HTMLElement;
    vi.spyOn(region, 'getBoundingClientRect').mockReturnValue({
      top: 0,
      bottom: 100,
      left: 0,
      right: 0,
      width: 0,
      height: 100,
      x: 0,
      y: 0,
      toJSON() {
        return this;
      },
    } as DOMRect);

    const spans = Array.from(region.querySelectorAll('span[data-line]')) as HTMLElement[];
    spans.forEach((span, i) => {
      const top = i * 20;
      vi.spyOn(span, 'getBoundingClientRect').mockReturnValue({
        top,
        bottom: top + 20,
        left: 0,
        right: 0,
        width: 0,
        height: 20,
        x: 0,
        y: top,
        toJSON() {
          return this;
        },
      } as DOMRect);
    });

    let detail: { firstLine: number; lastLine: number } | null = null;
    el.addEventListener('pane-visible-range', (e) => {
      detail = (e as CustomEvent).detail;
    });

    region.dispatchEvent(new Event('scroll'));
    expect(detail).toBeNull(); // debounced — not yet fired

    await vi.advanceTimersByTimeAsync(200);
    expect(detail).not.toBeNull();
    // Only lines whose [top,bottom) overlaps the container's [0,100) window are "visible".
    expect(detail!.firstLine).toBe(0);
    expect(detail!.lastLine).toBeGreaterThanOrEqual(4);
  });
});
