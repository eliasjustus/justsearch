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
 * ramp's own inner scroll containers — a wide `<pre>` or `<table>` in Rendered mode — get the same
 * treatment from `markScrollableRegions` (tempdoc 853 F-05); the pane region alone does not reach
 * content clipped INSIDE a block. The
 * Rendered/Source toggle is a `role="radiogroup"` of native `<button role="radio">`s — the same
 * mutually-exclusive-choice pattern `OptionButtonGroupRenderer` (`jf-option-button-group`) already
 * uses, chosen over an independent-toggle `aria-pressed` pair (seen on UnifiedChatView's "Abilities"
 * button) because Rendered/Source is a single mutually exclusive choice, not two independent flags.
 */
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { JfElement } from '../../primitives/JfElement.js';
import { markdownBlockMap, type MarkdownBlockDescriptor } from './markdownBlockMap.js';
import { markdownCodeHighlight, markdownTypography } from '../markdown/markdownStyles.js';
import { highlightCodeBlocks } from '../markdown/markdownHighlight.js';
import { markScrollableRegions } from '../markdown/markdownScrollRegions.js';
import { formatDisplayPath, formatLocationBreadcrumb } from '../searchResults/resultRowPresentation.js';
import { isAdvancedMode, subscribeUiMode } from '../../state/uiModeState.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
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
/** Search Thread Round-2 R1b — how long the passage lands with the strong tint before decaying to
 *  the quiet translucent tint + edge marker (the card's refined-✓ decay idiom, ported here). */
const HIGHLIGHT_DECAY_MS = 1500;

function isMarkdownPath(path: string): boolean {
  return /\.(md|markdown)$/i.test(path);
}

/** a11y — honor prefers-reduced-motion (MarkdownBlock's cursor-blink-suppression precedent): an
 *  infinite/animated emphasis is a strong reduced-motion trigger, so reduced motion skips the loud
 *  phase entirely and lands quiet. */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
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
    // Search Thread Round-2 R1b — has the current highlightRange decayed to the quiet tier?
    highlightSettled: { state: true },
    // Tempdoc 846 §2.3 — the shared ramp's prose variant, reflected so the shared sheet's
    // `:host([prose])` rules reach this pane's rendered blocks. Default ON: a document IS
    // headings, tables and rules, which is precisely what the variant exists to dress.
    prose: { type: Boolean, reflect: true },
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
  /** Round-2 R1b — false while the landed highlight is in its strong phase; true once decayed
   *  (or immediately, under prefers-reduced-motion) to the quiet tint + edge marker. */
  declare highlightSettled: boolean;
  /** Tempdoc 846 §2.3 — wear the shared ramp's prose variant (headings, tables, rules, images). */
  declare prose: boolean;

  private blocksCache: { content: string; blocks: MarkdownBlockDescriptor[] } | null = null;
  private scrollDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private loadToken = 0;
  private highlightDecayTimer: ReturnType<typeof setTimeout> | null = null;
  /** Round-2 R1b — the last highlightRange (as a line-span key) the decay was armed for, so a
   *  no-op re-render (an unrelated property changing) never re-triggers the strong phase. */
  private armedHighlightKey: string | null = null;

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
    this.highlightSettled = false;
    this.prose = true;
  }

  static override transientState = {
    loading: false,
    error: null,
  };

  /** Tempdoc 738 — re-render the disclosure-gated path header on Simple/Detailed change. */
  private uiModeUnsubscribe: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this.uiModeUnsubscribe = subscribeUiMode(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.uiModeUnsubscribe?.();
    this.uiModeUnsubscribe = null;
    if (this.scrollDebounceTimer) {
      clearTimeout(this.scrollDebounceTimer);
      this.scrollDebounceTimer = null;
    }
    if (this.highlightDecayTimer !== null) {
      clearTimeout(this.highlightDecayTimer);
      this.highlightDecayTimer = null;
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
      // A fresh docPath re-arms the highlight decay even for a line-range that happens to match
      // the previous document's (the passage is a genuinely new landing).
      this.armedHighlightKey = null;
      if (path) void this.loadContent(path);
    }
    if (changed.has('highlightRange')) {
      this.syncHighlightDecay();
    }
  }

  /**
   * Search Thread Round-2 R1b — arm the strong→quiet decay exactly once per distinct highlightRange
   * (the `armedHighlightKey` guard mirrors ResultsCard's `wasSettling` willUpdate-transition-detection
   * idiom): a NEW range lands strong and schedules the decay; the SAME range re-observed on an
   * unrelated re-render is a no-op (never restarts the timer). `chunkRange` never reaches this path —
   * it renders through the separate `hl-weak` tier in {@link highlightTier} and never gets the strong
   * phase, by construction (R1b: "the chunkRange tier NEVER gets the strong phase").
   */
  private syncHighlightDecay(): void {
    const hl = this.highlightRange;
    const key = hl ? `${hl.startLine}:${hl.endLine}` : null;
    if (key === this.armedHighlightKey) return;
    this.armedHighlightKey = key;
    if (this.highlightDecayTimer !== null) {
      clearTimeout(this.highlightDecayTimer);
      this.highlightDecayTimer = null;
    }
    if (key === null) {
      this.highlightSettled = false;
      return;
    }
    if (prefersReducedMotion()) {
      this.highlightSettled = true; // skip the loud phase entirely — land quiet
      return;
    }
    this.highlightSettled = false;
    this.highlightDecayTimer = setTimeout(() => {
      this.highlightSettled = true;
      this.highlightDecayTimer = null;
    }, HIGHLIGHT_DECAY_MS);
  }

  /**
   * The highlight tier for a block/line span: `'strong'` while the CURRENT highlightRange overlaps
   * and hasn't decayed yet; `'weak'` once it has decayed (folds into the same quiet tint + edge
   * marker the surrounding chunkRange uses) OR the span is only in the wider chunkRange; `null`
   * otherwise. chunkRange can never yield `'strong'` — R1b's "never gets the strong phase" rule.
   */
  private highlightTier(startLine: number, endLine: number): 'strong' | 'weak' | null {
    const hl = this.highlightRange;
    if (hl && this.overlaps(hl, startLine, endLine)) {
      return this.highlightSettled ? 'weak' : 'strong';
    }
    const chunk = this.chunkRange;
    if (chunk && this.overlaps(chunk, startLine, endLine)) return 'weak';
    return null;
  }

  override updated(changed: PropertyValues): void {
    if (
      this.highlightRange &&
      !this.loading &&
      (changed.has('highlightRange') || changed.has('content') || changed.has('loading') || changed.has('mode'))
    ) {
      this.scrollToHighlight();
    }
    // Tempdoc 846 §2.4 — syntax-highlight the rendered document's fenced code. The container the
    // blocks render into is passed (not a snapshot of its children), so a highlighter that is still
    // loading writes into the live tree when it arrives. Idempotent; a no-op in Source mode.
    highlightCodeBlocks(this.renderRoot.querySelector('.blocks'));
    // Tempdoc 853 (F-05) — the ramp's `pre`/`table` scroll containers are focusable + named, so a
    // keyboard user can reach the clipped half of a wide fence or table. Re-applied per render: Lit
    // rebuilds this subtree through `unsafeHTML`, which takes the attributes with it.
    markScrollableRegions(this.renderRoot.querySelector('.blocks'));
  }

  private async loadContent(path: string): Promise<void> {
    const token = ++this.loadToken;
    this.loading = true;
    this.error = null;
    try {
      const res = await authorizedFetch(
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
      // Tempdoc 677: VDU ran and found no text on the page(s) — previously fell through
      // silently to the base extraction label, hiding that VDU ran at all.
      case 'vdu_empty':
        return 'VDU: no text found';
      // Tempdoc 677 abstention gate: VDU output was judged untrustworthy (or the call was
      // skipped on an illegible input) — baseline extraction is shown instead.
      case 'vdu_rejected':
        return 'VDU: unreliable, not used';
      default:
        return null;
    }
  }

  /** Tempdoc 677: an explanatory tooltip for provenance values a bare label doesn't fully convey. */
  private previewProvenanceTooltip(): string | null {
    switch ((this.provenance ?? '').toLowerCase()) {
      case 'vdu_rejected':
        return 'The automatic reader could not produce trustworthy text for this document, so search uses the original extraction.';
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

  /**
   * Tempdoc 846 §2.3 — the shared markdown ramp comes FIRST, so every rule below still has the last
   * word. Before this, Rendered mode dressed a whole `.md` file in user-agent defaults (a 32px
   * `h1`, browser list indents, an unstyled `<table>`, a `<pre>` with no surface) — the one surface
   * whose entire job is reading a document was the one that did not dress documents.
   *
   * The shared sheet also carries `:host` typography (`font-size`, `line-height`, `word-wrap`); the
   * pane's own `:host` rule below re-declares what it means to keep (`display`, `color`,
   * `font-family`), and every chrome row — header, toggle, provenance — sets its own `font-size`,
   * so what the ramp actually reaches is the document body it was moved here for.
   */
  static styles = [markdownTypography, markdownCodeHighlight, css`
    :host([overlay]) {
      /* 687 R5b — sized for the OverlayHost right-drawer slot (narrow viewports). */
      width: min(28rem, 92vw);
      height: calc(100vh - 7.5rem);
      box-shadow: var(--shadow-float);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      background: var(--surface-1);
    }
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
    /* Round-2 R5c — truncation is the shared formatDisplayPath authority (filename-preserving
       middle-ellipsis), not CSS end-truncation; overflow stays hidden as a defensive clamp only. */
    .path {
      flex: 1;
      min-width: 0;
      font-size: var(--font-size-sm);
      font-weight: 600;
      font-family: monospace;
      overflow: hidden;
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
    /* Tempdoc 846 §2.3 — the wrapper's rhythm now READS the shared vocabulary instead of restating
       its literal (it was a private '0.25em'). The wrapper is load-bearing here and the ramp cannot
       replace it: this pane renders one block per wrapper, so the ramp's own 'p:first-child' /
       'p:last-child' zeroing fires on EVERY paragraph (each is both), which is exactly right — the
       gap between blocks is the wrapper's, and nothing double-counts it. */
    .blocks .block {
      margin: var(--md-block-gap) 0;
    }
    /* Round-2 R1b — the strong→quiet decay: both tiers share the same transitioned properties so
       swapping hl-strong for hl-weak (the JS class-flip in highlightTier) animates smoothly rather
       than jumping, via the existing --duration tokens. */
    .blocks .block.hl-weak,
    .blocks .block.hl-strong {
      transition: background var(--duration-slow) var(--ease-standard),
        color var(--duration-slow) var(--ease-standard),
        border-color var(--duration-slow) var(--ease-standard);
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
    pre.source span.hl-weak,
    pre.source span.hl-strong {
      transition: background var(--duration-slow) var(--ease-standard),
        color var(--duration-slow) var(--ease-standard),
        border-color var(--duration-slow) var(--ease-standard);
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
    /* a11y — honor prefers-reduced-motion (mirrors MarkdownBlock's cursor-blink suppression): the
       JS side already skips the loud phase (prefersReducedMotion() in syncHighlightDecay), this is
       the defensive CSS-only backstop against the decay transition itself. */
    @media (prefers-reduced-motion: reduce) {
      .blocks .block.hl-weak,
      .blocks .block.hl-strong,
      pre.source span.hl-weak,
      pre.source span.hl-strong {
        transition: none;
      }
    }
  `];

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
    const tooltip = this.previewProvenanceTooltip();
    return html`
      <div class="preview-source">
        <span title=${tooltip ?? nothing}>Text source <strong>${label}</strong></span>
        ${detail ? html`<span class="preview-source-detail">${detail}</span>` : nothing}
      </div>
    `;
  }

  private renderRenderedMode(): TemplateResult {
    const blocks = this.computedBlocks();
    if (blocks.length === 0) {
      return html`<div class="empty">No renderable content.</div>`;
    }
    return html`
      <div class="blocks md-content">
        ${blocks.map((b) => {
          const tier = this.highlightTier(b.startLine, b.endLine);
          return html`<div
            class="block ${tier === 'strong' ? 'hl-strong' : ''} ${tier === 'weak' ? 'hl-weak' : ''}"
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
    return html`
      <pre class="source">${lines.map((line, i) => {
        const tier = this.highlightTier(i, i);
        return html`<span
          data-line="${i}"
          class=${tier === 'strong' ? 'hl-strong' : tier === 'weak' ? 'hl-weak' : ''}
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
        ${/* Search Thread Round-2 R5c — the shared formatDisplayPath authority (filename-preserving
              middle truncation), not CSS end-truncation; the full path stays reachable via title. */ ''}
        <div class="path" title=${this.docPath}>
          ${/* Tempdoc 738 (C4) — Simple shows the humanized folder breadcrumb; Detailed the full path
                (the full path stays reachable via the title tooltip in both). */ ''}
          ${isAdvancedMode() ? formatDisplayPath(this.docPath) : formatLocationBreadcrumb(this.docPath)}
        </div>
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
