// SPDX-License-Identifier: Apache-2.0
/**
 * DocumentPane (`jf-document-pane`) — Search Thread stage S6 prep (tempdoc "Reading Stage").
 *
 * A standalone, integration-ready reading surface for a single document, addressable by a passage
 * (`highlightRange`) with an optional wider containing chunk (`chunkRange`). Built ahead of the
 * serialized integration stage — see the file-level report for what a future integration pass still
 * needs to wire (consumer registration, event handling in the host surface).
 *
 * Fetch + provenance parity: mirrors `components/InspectorPane.ts`'s `loadPreview` — same
 * `/api/preview?docId=…&offsetChars=0&maxChars=5000` request shape, same response fields
 * (`content` / `textProvenance` / `visualExtractionEvidence`), and the same tempdoc-671
 * zero-content diagnostic: the "Text source" provenance line is computed and rendered even when
 * `content` is empty (a scanned page OCR found no text on is still explained), not hidden behind an
 * `if (!content)` gate. `previewProvenanceLabel`/`previewEvidenceDetail` below are a deliberate,
 * small, local carryover of InspectorPane's private helpers of the same shape — this component owns
 * only new files under `components/documentPane/`, so the shared logic is duplicated rather than
 * extracted into a shared module that would require editing InspectorPane.ts.
 *
 * Two render modes:
 *   - `rendered` — markdown source is split into top-level blocks by {@link markdownBlockMap} (each
 *     carrying its source line range) and rendered as HTML, each block wrapper carrying
 *     `data-line-start`/`data-line-end`. Default for a `.md`/`.markdown` `docPath`.
 *   - `source` — the raw text, one `<span data-line="N">` per line (InspectorPane's exact
 *     `data-line` mechanics), default for a non-markdown `docPath` (and the ONLY mode available for
 *     one — the Rendered toggle is disabled with a reason, since there is no markdown to block-map).
 *
 * `highlightRange` marks the passage the caller wants shown: the covering block(s)/lines get the
 * `hl-strong` class and the pane scrolls the first one to `{block:'center'}`. The optional
 * `chunkRange` (the wider passage's containing chunk, when the caller has one) tints the REST of
 * that chunk with the weaker `hl-weak` gutter tint, so a reader sees both the exact hit and its
 * surrounding context at a glance.
 *
 * `pane-visible-range` fires (debounced) on scroll with the first/last visible line, a hook a future
 * "reading spine" affordance can consume; `pane-close` fires on the header close action so the host
 * surface decides what closing means (this component has no opinion on layout/visibility).
 *
 * a11y: the scroll region is `tabindex="0"` + `role="region"` (the measured axe
 * `scrollable-region-focusable` fix) so a keyboard user can focus-then-arrow/Page-scroll it. The
 * Rendered/Source toggle is a `role="radiogroup"` of native `<button role="radio">`s — the same
 * mutually-exclusive-choice pattern `OptionButtonGroupRenderer` (`jf-option-button-group`) already
 * uses, chosen over an independent-toggle `aria-pressed` pair (seen on UnifiedChatView's "Abilities"
 * button) because Rendered/Source is a single mutually exclusive choice, not two independent flags.
 */
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { JfElement } from '../../primitives/JfElement.js';
import { markdownBlockMap, type MarkdownBlockDescriptor } from './markdownBlockMap.js';
import '../ErrorAlert.js';
import '../Button.js';
import { icon } from '../Icon.js';

/** A 0-based, inclusive source line span (a passage, or its containing chunk). */
export interface DocumentLineRange {
  readonly startLine: number;
  readonly endLine: number;
}

/** Mirrors InspectorPane's local `VisualExtractionEvidence` shape (the `/api/preview` response field). */
export interface VisualExtractionEvidence {
  schemaVersion?: number;
  pageCount?: number;
  textCharCount?: number;
  textQualityScore?: number;
  charsPerPage?: number;
  alphanumericRatio?: number;
  ocrLanguage?: string;
  ocrMeanConfidence?: number;
  ocrLowConfidenceWordCount?: number;
  ocrWordCount?: number;
  pagesWithTextLayer?: number;
  pagesMissingReadableText?: number;
  mixedPdf?: boolean;
  structuredElementCounts?: {
    tables?: number;
    headings?: number;
    lists?: number;
  };
  imagePageCount?: number;
  layoutComplexity?: string;
  contentTruncated?: boolean;
  ocrFallbackRoute?: string;
  ocrSkipReason?: string;
  route?: string;
}

export type DocumentPaneMode = 'rendered' | 'source';

const SCROLL_DEBOUNCE_MS = 150;

function isMarkdownPath(path: string): boolean {
  return /\.(md|markdown)$/i.test(path);
}

export class DocumentPane extends JfElement {
  static properties = {
    docPath: { attribute: false },
    apiBase: { type: String, attribute: 'api-base' },
    highlightRange: { attribute: false },
    chunkRange: { attribute: false },
    mode: { state: true },
    content: { state: true },
    provenance: { state: true },
    evidence: { state: true },
    loading: { state: true },
    error: { state: true },
  };

  declare docPath: string | null;
  declare apiBase: string;
  declare highlightRange: DocumentLineRange | null;
  declare chunkRange: DocumentLineRange | null;
  declare mode: DocumentPaneMode;
  declare content: string;
  declare provenance: string | null;
  declare evidence: VisualExtractionEvidence | null;
  declare loading: boolean;
  declare error: string | null;

  private blocksCache: { content: string; blocks: MarkdownBlockDescriptor[] } | null = null;
  private scrollDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private loadToken = 0;

  constructor() {
    super();
    this.docPath = null;
    this.apiBase = '';
    this.highlightRange = null;
    this.chunkRange = null;
    this.mode = 'source';
    this.content = '';
    this.provenance = null;
    this.evidence = null;
    this.loading = false;
    this.error = null;
  }

  static override transientState = {
    loading: false,
    error: null,
  };

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.scrollDebounceTimer) {
      clearTimeout(this.scrollDebounceTimer);
      this.scrollDebounceTimer = null;
    }
  }

  private base(): string {
    return this.apiBase || '';
  }

  override willUpdate(changed: PropertyValues): void {
    if (changed.has('docPath')) {
      const path = this.docPath;
      this.mode = path && isMarkdownPath(path) ? 'rendered' : 'source';
      this.content = '';
      this.provenance = null;
      this.evidence = null;
      this.error = null;
      this.blocksCache = null;
      if (path) void this.loadContent(path);
    }
  }

  override updated(changed: PropertyValues): void {
    if (
      this.highlightRange &&
      !this.loading &&
      (changed.has('highlightRange') || changed.has('content') || changed.has('loading') || changed.has('mode'))
    ) {
      this.scrollToHighlight();
    }
  }

  private async loadContent(path: string): Promise<void> {
    const token = ++this.loadToken;
    this.loading = true;
    this.error = null;
    try {
      const res = await fetch(
        this.base() + `/api/preview?docId=${encodeURIComponent(path)}&offsetChars=0&maxChars=5000`,
      );
      if (token !== this.loadToken) return; // a newer docPath superseded this request
      if (!res.ok) {
        this.error = `HTTP ${res.status}`;
        return;
      }
      const data = (await res.json()) as {
        content?: string;
        textProvenance?: string | null;
        visualExtractionEvidence?: VisualExtractionEvidence | null;
      };
      if (token !== this.loadToken) return;
      this.content = data.content ?? '';
      this.provenance = data.textProvenance ?? null;
      this.evidence = data.visualExtractionEvidence ?? null;
    } catch (err) {
      if (token !== this.loadToken) return;
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      if (token === this.loadToken) this.loading = false;
    }
  }

  private computedBlocks(): MarkdownBlockDescriptor[] {
    if (this.blocksCache?.content !== this.content) {
      this.blocksCache = { content: this.content, blocks: markdownBlockMap(this.content) };
    }
    return this.blocksCache.blocks;
  }

  private overlaps(range: DocumentLineRange, startLine: number, endLine: number): boolean {
    return endLine >= range.startLine && startLine <= range.endLine;
  }

  private scrollToHighlight(): void {
    void this.updateComplete.then(() => {
      const el = this.shadowRoot?.querySelector('.hl-strong');
      el?.scrollIntoView({ block: 'center' });
    });
  }

  private handleClose = (): void => {
    this.dispatchEvent(new CustomEvent('pane-close', { bubbles: true, composed: true }));
  };

  private handleScroll = (): void => {
    if (this.scrollDebounceTimer) clearTimeout(this.scrollDebounceTimer);
    this.scrollDebounceTimer = setTimeout(() => {
      this.scrollDebounceTimer = null;
      this.emitVisibleRange();
    }, SCROLL_DEBOUNCE_MS);
  };

  private emitVisibleRange(): void {
    const container = this.shadowRoot?.querySelector('.scroll-region') as HTMLElement | null;
    if (!container) return;
    const containerRect = container.getBoundingClientRect();
    const items = container.querySelectorAll('[data-line-start], [data-line]');
    let first: number | null = null;
    let last: number | null = null;
    for (const el of items) {
      const rect = el.getBoundingClientRect();
      const visible = rect.bottom > containerRect.top && rect.top < containerRect.bottom;
      if (!visible) continue;
      const startAttr = el.getAttribute('data-line-start') ?? el.getAttribute('data-line');
      const endAttr = el.getAttribute('data-line-end') ?? startAttr;
      const start = Number(startAttr);
      const end = Number(endAttr);
      if (!Number.isFinite(start) || !Number.isFinite(end)) continue;
      if (first === null || start < first) first = start;
      if (last === null || end > last) last = end;
    }
    if (first === null || last === null) return;
    this.dispatchEvent(
      new CustomEvent('pane-visible-range', {
        detail: { firstLine: first, lastLine: last },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private previewProvenanceLabel(): string | null {
    switch ((this.provenance ?? '').toLowerCase()) {
      case 'ocr':
        return 'OCR';
      case 'tika':
        return 'Tika';
      case 'vdu':
        return 'VDU';
      case 'vdu_pending':
        return 'VDU pending';
      case 'vdu_processing':
        return 'VDU processing';
      case 'vdu_failed':
        return 'VDU failed';
      default:
        return null;
    }
  }

  private previewEvidenceDetail(): string | null {
    const evidence = this.evidence;
    if (!evidence) return null;
    const parts: string[] = [];
    const route = evidence.route?.replace(/_/g, ' ');
    if (route) parts.push(route);
    if (evidence.ocrLanguage) parts.push(evidence.ocrLanguage);
    if (typeof evidence.textQualityScore === 'number') {
      const score = Math.round(Math.max(0, Math.min(1, evidence.textQualityScore)) * 100);
      parts.push(`${score}% quality`);
    }
    if (typeof evidence.ocrMeanConfidence === 'number') {
      const confidence = Math.round(Math.max(0, Math.min(1, evidence.ocrMeanConfidence)) * 100);
      parts.push(`${confidence}% OCR confidence`);
    }
    if (evidence.ocrFallbackRoute) {
      parts.push(`${evidence.ocrFallbackRoute.replace(/_/g, ' ')} fallback`);
    }
    if (evidence.contentTruncated) {
      parts.push('truncated');
    }
    if (evidence.ocrSkipReason) {
      parts.push(`OCR skipped: ${evidence.ocrSkipReason.replace(/_/g, ' ')}`);
    }
    if ((evidence.pagesMissingReadableText ?? 0) > 0) {
      parts.push(`${evidence.pagesMissingReadableText} pages still visual`);
    }
    if (evidence.layoutComplexity && evidence.layoutComplexity !== 'none') {
      parts.push(`${evidence.layoutComplexity} layout`);
    }
    return parts.length > 0 ? parts.join(' · ') : null;
  }

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      box-sizing: border-box;
      background: var(--surface-1);
      color: var(--text-primary);
      font-family: system-ui, sans-serif;
    }
    .header {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.625rem 0.875rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .path {
      flex: 1;
      min-width: 0;
      font-size: var(--font-size-sm);
      font-weight: 600;
      font-family: monospace;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .toggle-group {
      flex-shrink: 0;
      display: flex;
      gap: 0;
      padding: 0.5rem 0.875rem 0;
    }
    .toggle-btn {
      padding: 0.3rem 0.75rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
      transition: background var(--duration-fast), color var(--duration-fast),
        border-color var(--duration-fast);
    }
    .toggle-btn:first-child {
      border-radius: 0.375rem 0 0 0.375rem;
    }
    .toggle-btn:last-child {
      border-radius: 0 0.375rem 0.375rem 0;
      margin-left: -1px;
    }
    .toggle-btn:hover:not([aria-disabled='true']) {
      background: var(--surface-hover);
      color: var(--text-primary);
    }
    .toggle-btn:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: 1px;
    }
    .toggle-btn.selected {
      background: var(--accent-tint-16);
      color: var(--text-tint);
      border-color: var(--accent-tint);
    }
    /* Tempdoc 596 face 1.1 — this is a SOFT block (aria-disabled, not native disabled): the button
       stays focusable and its title reason stays reachable via hover/focus. A native disabled
       button suppresses its own title tooltip (596 §1.1) — the exact defect the controls-a11y gate's
       title-on-disabled check flags. */
    .toggle-btn[aria-disabled='true'] {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .empty {
      padding: 2rem 1rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .preview-source {
      display: inline-flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.35rem;
      max-width: 100%;
      margin: 0.75rem 0.875rem 0;
      padding: 0.2rem 0.45rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      background: var(--surface-2);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      line-height: 1.2;
    }
    .preview-source strong {
      color: var(--text-primary);
      font-weight: 600;
    }
    .preview-source-detail {
      color: var(--text-tertiary);
    }
    .scroll-region {
      flex: 1;
      overflow-y: auto;
      padding: 0.875rem;
    }
    .scroll-region:focus-visible {
      outline: 2px solid var(--accent-tint);
      outline-offset: -2px;
    }
    .blocks .block {
      margin: 0.25em 0;
    }
    .blocks .block.hl-weak {
      background: var(--accent-tint-08);
      border-left: 2px solid var(--accent-tint-30);
      padding-left: 0.5rem;
      margin-left: -0.5rem;
    }
    .blocks .block.hl-strong {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 0.25rem;
      padding: 0.1rem 0.4rem;
      margin-left: -0.4rem;
    }
    pre.source {
      margin: 0;
      font-family: ui-monospace, 'SF Mono', monospace;
      font-size: var(--font-size-xs);
      line-height: 1.5;
      color: var(--text-secondary);
      white-space: pre-wrap;
      word-break: break-word;
    }
    pre.source span.hl-weak {
      background: var(--accent-tint-08);
      border-left: 2px solid var(--accent-tint-30);
    }
    pre.source span.hl-strong {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 2px;
    }
  `;

  private renderToggle(): TemplateResult {
    const isMd = this.docPath ? isMarkdownPath(this.docPath) : false;
    return html`
      <div class="toggle-group" role="radiogroup" aria-label="View mode">
        <button
          type="button"
          role="radio"
          aria-checked=${this.mode === 'rendered' ? 'true' : 'false'}
          aria-disabled=${isMd ? nothing : 'true'}
          class="toggle-btn ${this.mode === 'rendered' ? 'selected' : ''}"
          title=${isMd ? nothing : 'Rendered view is only available for Markdown documents'}
          @click=${() => {
            if (isMd) this.mode = 'rendered';
          }}
        >
          Rendered
        </button>
        <button
          type="button"
          role="radio"
          aria-checked=${this.mode === 'source' ? 'true' : 'false'}
          class="toggle-btn ${this.mode === 'source' ? 'selected' : ''}"
          @click=${() => {
            this.mode = 'source';
          }}
        >
          Source
        </button>
      </div>
    `;
  }

  private renderProvenanceLine(): TemplateResult | typeof nothing {
    const label = this.previewProvenanceLabel();
    if (!label) return nothing;
    const detail = this.previewEvidenceDetail();
    return html`
      <div class="preview-source">
        <span>Text source <strong>${label}</strong></span>
        ${detail ? html`<span class="preview-source-detail">${detail}</span>` : nothing}
      </div>
    `;
  }

  private renderRenderedMode(): TemplateResult {
    const blocks = this.computedBlocks();
    if (blocks.length === 0) {
      return html`<div class="empty">No renderable content.</div>`;
    }
    const hl = this.highlightRange;
    const chunk = this.chunkRange;
    return html`
      <div class="blocks">
        ${blocks.map((b) => {
          const strong = hl ? this.overlaps(hl, b.startLine, b.endLine) : false;
          const weak = !strong && chunk ? this.overlaps(chunk, b.startLine, b.endLine) : false;
          return html`<div
            class="block ${strong ? 'hl-strong' : ''} ${weak ? 'hl-weak' : ''}"
            data-line-start=${b.startLine}
            data-line-end=${b.endLine}
          >
            ${unsafeHTML(b.html)}
          </div>`;
        })}
      </div>
    `;
  }

  private renderSourceMode(): TemplateResult {
    const lines = this.content.split('\n');
    const hl = this.highlightRange;
    const chunk = this.chunkRange;
    return html`
      <pre class="source">${lines.map((line, i) => {
        const strong = hl ? this.overlaps(hl, i, i) : false;
        const weak = !strong && chunk ? this.overlaps(chunk, i, i) : false;
        return html`<span
          data-line="${i}"
          class=${strong ? 'hl-strong' : weak ? 'hl-weak' : ''}
          >${line}\n</span
        >`;
      })}</pre>
    `;
  }

  private renderBody(): TemplateResult {
    if (this.loading) {
      return html`<div class="empty">Loading…</div>`;
    }
    if (this.error) {
      return html`<jf-error-alert tone="error">${this.error}</jf-error-alert>`;
    }
    return html`
      ${this.renderProvenanceLine()}
      <div
        class="scroll-region"
        tabindex="0"
        role="region"
        aria-label="Document content"
        @scroll=${this.handleScroll}
      >
        ${this.content
          ? this.mode === 'rendered'
            ? this.renderRenderedMode()
            : this.renderSourceMode()
          : html`<div class="empty">No preview available.</div>`}
      </div>
    `;
  }

  override render(): TemplateResult {
    if (!this.docPath) {
      return html`<div class="empty">No document selected.</div>`;
    }
    return html`
      <div class="header">
        <div class="path" title=${this.docPath}>${this.docPath}</div>
        <jf-button class="icon" variant="ghost" size="icon" label="Close" .onActivate=${this.handleClose}
          >${icon({ name: 'x', size: 14 })}</jf-button
        >
      </div>
      ${this.renderToggle()} ${this.renderBody()}
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-document-pane')) {
  customElements.define('jf-document-pane', DocumentPane);
}
