// SPDX-License-Identifier: Apache-2.0
/**
 * InspectorPane — Lit-side preview panel for selected items
 * (slice 462).
 *
 * Tabs: Preview / Context / Answer / Input. V1 functional surface:
 *  - Empty state when no item selected.
 *  - Preview tab: text content via /api/preview (truncated 5KB).
 *  - Context tab: minimal placeholder for now (RAG context endpoint
 *    landing in a follow-on; documented as deferred).
 *  - Answer tab: AI streaming response panel (bridge to existing
 *    /api/agent/run/stream from slice 453).
 *  - Input tab: prompt textarea + send.
 *
 * Subscribes to inspectorState. Side-effect registers
 * <jf-inspector-pane>.
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import './ErrorAlert.js';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { icon } from './Icon.js';
// Q9: render the AI answer as formatted markdown (the component already used by
// the chat surfaces), not raw source text.
import './chat/MarkdownBlock.js';
// Tempdoc 577 Phase 1 (Move F) — the shared agent-answer→Citation resolver + the deep-link
// detail contract, so the Answer tab grounds through the same authority as the chat surface.
import { resolveAnswerCitations } from './chat/citationResolve.js';
import type { CitationSelectDetail } from './chat/citationTypes.js';
import type {
  AgentSource,
  AgentSentenceCite,
} from '../../api/generated/shape-handlers/shared.js';
import { parseSseBuffer } from '../../api/sse.js';
import {
  getInspectorState,
  setInspectorState,
  subscribeInspector,
  setActiveTab,
  setOpen,
  type InspectorState,
  type InspectorTab,
} from '../state/inspectorState.js';
// Tempdoc 580 §17 P3 — the DWELLED signal: the inspector lifecycle arms/cancels the dwell timer.
import { recordInspectorOpen, recordInspectorClose } from '../state/searchState.js';

import {
  listInspectorTabs,
  onInspectorTabChange,
  registerInspectorTab,
} from '../commands/InspectorTabRegistry.js';

import {
  setSingleSelection,
  clearSelection,
  getSelection as getCurrentSelection,
  type SelectionItem,
  DEFAULT_CAPABILITIES_BY_KIND,
} from '../state/selectionState.js';
import { setMenuAnchor } from '../utils/selectionAnchor.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

// Tempdoc 508 §4.3 — core tabs register through the same contribution
// system as plugin tabs. Core tab renderers live on this component;
// the registry entry is a marker that the tab exists.
const CORE_TABS: Array<{ id: InspectorTab; label: string; priority: number }> = [
  { id: 'preview', label: 'Preview', priority: 10 },
  { id: 'context', label: 'Context', priority: 20 },
  { id: 'answer', label: 'Answer', priority: 30 },
  { id: 'input', label: 'Ask', priority: 40 },
];

let coreTabsRegistered = false;
function ensureCoreTabsRegistered(): void {
  if (coreTabsRegistered) return;
  coreTabsRegistered = true;
  for (const t of CORE_TABS) {
    registerInspectorTab({
      id: t.id,
      label: t.label,
      priority: t.priority,
      source: 'core',
      provenance: CORE_PROVENANCE,
      // Marker render — actual core renderers live on InspectorPane
      render: () => {
        const span = document.createElement('span');
        span.textContent = `[core ${t.id} tab]`;
        return span;
      },
    });
  }
}

type VisualExtractionEvidence = {
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
};

export class InspectorPane extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    s: { state: true },
    previewText: { state: true },
    previewProvenance: { state: true },
    previewEvidence: { state: true },
    previewLoading: { state: true },
    previewError: { state: true },
    promptDraft: { state: true },
    highlightStartLine: { state: true },
    highlightEndLine: { state: true },
    tabEntries: { state: true },
  };

  declare apiBase: string;
  declare s: InspectorState;
  declare previewText: string;
  declare previewProvenance: string | null;
  declare previewEvidence: VisualExtractionEvidence | null;
  declare previewLoading: boolean;
  declare previewError: string | null;
  declare promptDraft: string;
  declare highlightStartLine: number;
  declare highlightEndLine: number;
  declare tabEntries: ReadonlyArray<{ id: string; label: string; source: 'core' | 'plugin'; render?: (ctx: { selectedItem: unknown }) => HTMLElement | string }>;

  private unsub: (() => void) | null = null;
  private unsubTabs: (() => void) | null = null;
  private aiAbort: AbortController | null = null;
  private pendingHighlight: { startLine: number; endLine: number } | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.s = getInspectorState();
    this.previewText = '';
    this.previewProvenance = null;
    this.previewEvidence = null;
    this.previewLoading = false;
    this.previewError = null;
    this.promptDraft = '';
    this.highlightStartLine = -1;
    this.highlightEndLine = -1;
    ensureCoreTabsRegistered();
    this.tabEntries = listInspectorTabs();
  }

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      box-sizing: border-box;
      background: var(--surface-1);
      border-left: 1px solid var(--border-subtle);
      color: var(--text-primary);
      font-family: system-ui, sans-serif;
    }
    .header {
      flex-shrink: 0;
      padding: 0.625rem 0.875rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .title-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .title {
      flex: 1;
      font-size: var(--font-size-sm);
      font-weight: 600;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
      font-family: monospace;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    /* 574 critical-analysis F4 — the close (.icon) is a jf-button(ghost,icon) atom; it skins
       itself, so the old dead button.icon rules are removed. */
    .tabs {
      display: flex;
      gap: 0;
      flex-shrink: 0;
      border-bottom: 1px solid var(--border-subtle);
    }
    .tabs button {
      flex: 1;
      padding: 0.4rem 0.5rem;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-weight: 500;
      cursor: pointer;
      border-bottom: 2px solid transparent;
    }
    .tabs button.active {
      color: var(--text-tint);
      border-bottom-color: var(--accent-tint);
    }
    .body {
      flex: 1;
      overflow-y: auto;
      padding: 0.875rem;
    }
    .empty {
      padding: 2rem 1rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    pre {
      margin: 0;
      font-family: ui-monospace, 'SF Mono', monospace;
      font-size: var(--font-size-xs);
      line-height: 1.5;
      color: var(--text-secondary);
      white-space: pre-wrap;
      word-break: break-word;
    }
    .preview-source {
      display: inline-flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.35rem;
      max-width: 100%;
      margin-bottom: 0.75rem;
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
    pre span.hl {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 2px;
    }
    .answer {
      font-size: var(--font-size-sm);
      line-height: 1.5;
      white-space: pre-wrap;
    }
    /* Tempdoc 577 Phase 1 — per-answer source chips (mirrors UnifiedChatView's source-chip grammar). */
    .answer-sources {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-top: 0.5rem;
    }
    .answer-source-chip {
      display: inline-flex;
      align-items: center;
      gap: 0.45ch;
      max-width: 24ch;
      padding: 0.15rem 0.55rem;
      font-size: var(--font-size-xs);
      font-family: inherit;
      border: 1px solid var(--border-subtle);
      border-radius: 1rem;
      background: var(--surface-2);
      color: var(--text-secondary);
      cursor: pointer;
    }
    .answer-source-chip:hover,
    .answer-source-chip:focus-visible {
      background: var(--surface-hover);
      color: var(--text-primary);
      border-color: var(--accent-command);
      outline: none;
    }
    .answer-source-n {
      font-variant-numeric: tabular-nums;
      color: var(--text-tertiary);
    }
    .answer-source-name {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    textarea {
      width: 100%;
      min-height: 4rem;
      padding: 0.4rem 0.5rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-family: inherit;
      font-size: var(--font-size-sm);
      resize: vertical;
      box-sizing: border-box;
    }
    /* 574 critical-analysis F4 — the send (.send) is a jf-button(primary) atom; it skins itself
       + handles disabled. Only its host-layout margin is preserved here (the old button.send
       tag selector no longer matched the jf-button, so this margin had been lost). */
    .send {
      margin-top: 0.5rem;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.tabEntries = listInspectorTabs();
    this.unsubTabs = onInspectorTabChange(() => {
      this.tabEntries = listInspectorTabs();
    });
    this.unsub = subscribeInspector((s) => {
      const prevId = this.s.selected?.id ?? null;
      const nextId = s.selected?.id ?? null;
      this.s = s;
      if (nextId !== prevId) {
        // Tempdoc 580 §17 P3 — the inspector lifecycle drives the DWELLED signal: arm a dwell
        // timer when a result opens, cancel it on close/switch. searchState owns the timer +
        // the (interactionId, docId) capture and only attributes opens of the current results.
        if (nextId) recordInspectorOpen(nextId);
        else recordInspectorClose();
      this.previewText = '';
      this.previewProvenance = null;
      this.previewEvidence = null;
      this.previewError = null;
        if (nextId) void this.loadPreview();
      }
    });
    if (this.s.selected) {
      void this.loadPreview();
      // Tempdoc 580 §17 P3 — the pane is lazily mounted WHEN the inspector opens (Shell renders it
      // only while isOpen), so the open transition fired before this subscription existed. Arm the
      // dwell for the already-selected doc here, mirroring the loadPreview catch-up above.
      if (this.s.selected.id) recordInspectorOpen(this.s.selected.id);
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsubTabs?.();
    this.aiAbort?.abort();
    recordInspectorClose(); // the pane unmounted → cancel any pending dwell
  }

  /**
   * Tempdoc 508 §4 — render plugin-contributed inspector tab content.
   * Core tabs (preview/context/answer/input) are rendered by their
   * respective methods; this handles registry entries that aren't core.
   */
  private renderPluginTab(): unknown {
    const activeId = this.s.activeTab as string;
    const coreTabIds = new Set(['preview', 'context', 'answer', 'input']);
    if (coreTabIds.has(activeId)) return nothing;
    const entry = this.tabEntries.find((t) => t.id === activeId);
    if (!entry || entry.source !== 'plugin' || !entry.render) return nothing;
    const rendered = entry.render({ selectedItem: this.s.selected });
    return rendered;
  }

  private base(): string {
    return this.apiBase || '';
  }

  private async loadPreview(): Promise<void> {
    const item = this.s.selected;
    if (!item) return;
    this.previewLoading = true;
    this.previewError = null;
    this.previewProvenance = null;
    try {
      const res = await fetch(
        this.base() +
          `/api/preview?docId=${encodeURIComponent(item.path)}&offsetChars=0&maxChars=5000`,
      );
      if (!res.ok) {
        this.previewError = `HTTP ${res.status}`;
        return;
      }
      const data = (await res.json()) as {
        content?: string;
        textProvenance?: string | null;
        visualExtractionEvidence?: VisualExtractionEvidence | null;
      };
      this.previewText = data.content ?? '';
      this.previewProvenance = data.textProvenance ?? null;
      this.previewEvidence = data.visualExtractionEvidence ?? null;
    } catch (err) {
      this.previewError = err instanceof Error ? err.message : String(err);
    } finally {
      this.previewLoading = false;
      if (this.pendingHighlight) {
        const { startLine } = this.pendingHighlight;
        this.pendingHighlight = null;
        this.scrollToHighlight(startLine);
      }
    }
  }

  private async sendQuestion(): Promise<void> {
    const q = this.promptDraft.trim();
    if (!q || this.s.ai.loading) return;
    setInspectorState({ ai: { loading: true, text: '', error: null }, activeTab: 'answer' });
    this.aiAbort?.abort();
    this.aiAbort = new AbortController();
    try {
      const ctxLine = this.s.selected ? `\n\nFile: ${this.s.selected.path}` : '';
      const res = await fetch(this.base() + '/api/chat/agent', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          messages: [{ role: 'user', content: q + ctxLine }],
          tools: [],
          maxIterations: 3,
        }),
        signal: this.aiAbort.signal,
      });
      if (!res.ok) {
        setInspectorState({ ai: { loading: false, text: '', error: `HTTP ${res.status}` } });
        return;
      }
      const reader = res.body?.getReader();
      if (!reader) {
        setInspectorState({ ai: { loading: false, text: '', error: 'no body' } });
        return;
      }
      const decoder = new TextDecoder();
      let buffer = '';
      let acc = '';
      // Tempdoc 577 Phase 1 (Move F) — capture the answer's grounding from the `done` event
      // (previously discarded): the clickable local-passage sources + per-sentence cites.
      let sources: AgentSource[] = [];
      let citations: AgentSentenceCite[] = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = parseSseBuffer(buffer, ({ event, data }) => {
          if (!data) return;
          try {
            const parsed = JSON.parse(data) as Record<string, unknown>;
            if (event === 'chunk') {
              acc += (parsed.text as string) ?? '';
              setInspectorState({ ai: { loading: true, text: acc, error: null } });
            } else if (event === 'done') {
              const final = parsed.finalResponse as string | undefined;
              if (final?.trim() && final !== acc) acc = final;
              if (Array.isArray(parsed.sources)) sources = parsed.sources as AgentSource[];
              if (Array.isArray(parsed.citations)) citations = parsed.citations as AgentSentenceCite[];
            } else if (event === 'error') {
              setInspectorState({
                ai: { loading: false, text: acc, error: (parsed.error as string) ?? 'error' },
              });
            }
          } catch {
            // ignore parse errors
          }
        });
      }
      setInspectorState({ ai: { loading: false, text: acc, error: null, sources, citations } });
    } catch (err) {
      if ((err as Error).name === 'AbortError') return;
      setInspectorState({
        ai: { loading: false, text: '', error: err instanceof Error ? err.message : String(err) },
      });
    }
  }

  highlightCitation(startLine: number, endLine: number): void {
    this.highlightStartLine = startLine;
    this.highlightEndLine = endLine;
    setActiveTab('preview');
    if (this.previewLoading || !this.previewText) {
      this.pendingHighlight = { startLine, endLine };
    } else {
      this.pendingHighlight = null;
      this.scrollToHighlight(startLine);
    }
  }

  private scrollToHighlight(startLine: number): void {
    this.updateComplete.then(() => {
      const el = this.shadowRoot?.querySelector(`[data-line="${startLine}"]`);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  clearHighlight(): void {
    this.highlightStartLine = -1;
    this.highlightEndLine = -1;
  }

  private renderPreview(): TemplateResult {
    if (this.previewLoading) {
      return html`<div class="empty">Loading preview…</div>`;
    }
    if (this.previewError) {
      return html`<jf-error-alert tone="error">${this.previewError}</jf-error-alert>`;
    }
    if (!this.previewText) {
      return html`<div class="empty">No preview available.</div>`;
    }
    const lines = this.previewText.split('\n');
    const hl = this.highlightStartLine >= 0;
    const provenanceLabel = this.previewProvenanceLabel();
    // Tempdoc 526 §12.4 — `mouseup` inside the open shadow root publishes a
    // typed `text-range` SelectionItem when the user selects text in the
    // preview. The handler runs after the browser finishes updating the
    // selection (mouseup fires after selectionchange).
    return html`
      ${provenanceLabel
        ? html`<div class="preview-source">
            <span>Text source <strong>${provenanceLabel}</strong></span>
            ${this.previewEvidenceDetail()
              ? html`<span class="preview-source-detail">${this.previewEvidenceDetail()}</span>`
              : nothing}
          </div>`
        : nothing}
      <pre @mouseup=${this.handlePreviewSelectionChange}>${lines.map(
      (line, i) =>
        html`<span
          data-line="${i}"
          class=${hl && i >= this.highlightStartLine && i <= this.highlightEndLine
            ? 'hl'
            : ''}
          >${line}\n</span>`,
    )}</pre>
    `;
  }

  private previewProvenanceLabel(): string | null {
    switch ((this.previewProvenance ?? '').toLowerCase()) {
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
    const evidence = this.previewEvidence;
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
      parts.push(evidence.layoutComplexity.replace(/_/g, ' '));
    }
    return parts.length > 0 ? parts.join(' · ') : null;
  }

  /**
   * Publish a typed `text-range` selection when the user releases the mouse
   * after picking text inside the preview {@code <pre>}. Per tempdoc 526
   * §12.4 + §12.8 R2.1, the JustSearch Lit shell uses open shadow roots, so
   * {@code window.getSelection()} returns shadow-internal text nodes; we
   * still prefer {@code this.shadowRoot.getSelection?.()} when available
   * (Chromium 53+) for explicitness.
   *
   * The selection address rides as {@code DocumentAddress.Display{viewId:
   * 'preview-5k'}}: the {@code /api/preview?maxChars=5000} response is a
   * pure substring of canonical content (empirically byte-aligned per §12.9
   * E1), so the display offsets identity-map to canonical when the backend
   * lifts them.
   */
  private handlePreviewSelectionChange(): void {
    const item = this.s.selected;
    if (!item) return;
    const sel: Selection | null =
      (this.shadowRoot as ShadowRoot & { getSelection?: () => Selection | null })
        ?.getSelection?.() ?? window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) {
      // No active selection. Clear any stale text-range we previously
      // published; leave other selection kinds alone (the user clicking in
      // the preview shouldn't deselect a search-hit or browse-node). Also
      // clear the menu anchor so the floating menu hides.
      const cur = getCurrentSelection();
      if (cur.items.length > 0 && cur.items[0]!.kind === 'text-range') {
        clearSelection();
        setMenuAnchor(null);
      }
      return;
    }
    const range = sel.getRangeAt(0);
    const startOffset = this.computeCharOffset(range.startContainer, range.startOffset);
    const endOffset = this.computeCharOffset(range.endContainer, range.endOffset);
    if (startOffset == null || endOffset == null || startOffset === endOffset) {
      return;
    }
    // Tempdoc 526 §14.5 T1 — publish the selection rect to the menu's
    // one-shot register. SelectionActionsMenu reads from this rather than
    // querying window.getSelection() across nested shadow roots.
    const rect = range.getBoundingClientRect();
    if (rect && (rect.width > 0 || rect.height > 0)) {
      setMenuAnchor({ top: rect.top, left: rect.left, bottom: rect.bottom, right: rect.right });
    }
    const displayStart = Math.min(startOffset, endOffset);
    const displayEnd = Math.max(startOffset, endOffset);
    const selectionText = this.previewText.slice(displayStart, displayEnd);
    if (selectionText.length === 0) return;

    const textRangeItem: SelectionItem = {
      kind: 'text-range',
      address: {
        coords: 'display',
        docId: item.path,
        viewId: 'preview-200k',
        displayStart,
        displayEnd,
        canonicalHint: { startChar: displayStart, endChar: displayEnd },
      },
      selectionText,
      hostEntity: { kind: 'doc', id: item.path },
      capabilities: DEFAULT_CAPABILITIES_BY_KIND['text-range'],
    };
    setSingleSelection(textRangeItem, 'core.inspector-pane');
  }

  /**
   * Map a DOM (node, offset) within the rendered preview to a character
   * offset in {@link previewText}. The preview is rendered as a sequence of
   * {@code <span data-line="N">} children whose text content is the source
   * line followed by a {@code \n}, so the offset within {@link previewText}
   * for a node inside line N is the sum of {@code lines[0..N-1].length + 1}
   * plus the offset within that line.
   *
   * Returns null when the node isn't inside the preview {@code <pre>} (e.g.,
   * the selection crossed into adjacent UI).
   */
  private computeCharOffset(node: Node, offsetInNode: number): number | null {
    // Walk up to find a span with data-line.
    let cur: Node | null = node;
    let span: Element | null = null;
    while (cur && cur !== this.shadowRoot) {
      if (cur instanceof Element && cur.hasAttribute('data-line')) {
        span = cur;
        break;
      }
      cur = cur.parentNode;
    }
    if (!span) return null;
    const lineIdxStr = span.getAttribute('data-line');
    if (lineIdxStr == null) return null;
    const lineIdx = Number(lineIdxStr);
    if (!Number.isFinite(lineIdx) || lineIdx < 0) return null;

    const lines = this.previewText.split('\n');
    let charOffset = 0;
    for (let i = 0; i < lineIdx && i < lines.length; i++) {
      // Each rendered span contributed `${line}\n` so its length in the
      // composed text is line.length + 1.
      charOffset += lines[i]!.length + 1;
    }

    // Within this span, walk the text descendants to convert (node, offset)
    // into a character index within the line text. The span template is a
    // single text node containing `${line}\n`, but mousing can land the
    // anchor on the span itself (offsetInNode counts children) when the
    // click is at the boundary.
    if (node.nodeType === Node.TEXT_NODE) {
      return charOffset + Math.min(offsetInNode, (node.textContent ?? '').length);
    }
    // Element node: offsetInNode is the index of the child node before the
    // cursor. Sum text lengths of preceding text children.
    if (node === span) {
      let acc = 0;
      const children = Array.from(span.childNodes);
      for (let i = 0; i < Math.min(offsetInNode, children.length); i++) {
        acc += (children[i]!.textContent ?? '').length;
      }
      return charOffset + acc;
    }
    // Fallback: best-effort start of span.
    return charOffset;
  }

  private renderContext(): TemplateResult {
    return html`<div class="empty">
      RAG context endpoint not yet wired (V1 deferral). The Answer tab
      shows the AI response without per-chunk highlighting.
    </div>`;
  }

  private renderAnswer(): TemplateResult {
    if (this.s.ai.loading && !this.s.ai.text) {
      return html`<div class="empty">${icon({ name: 'loader-2', size: 14, spin: true })} Thinking…</div>`;
    }
    if (this.s.ai.error) {
      return html`<jf-error-alert tone="error">${this.s.ai.error}</jf-error-alert>`;
    }
    if (!this.s.ai.text) {
      return html`<div class="empty">No question asked yet. Switch to the Ask tab.</div>`;
    }
    // Tempdoc 577 Phase 1 (Move F) — weave the answer's grounding through the ONE MarkdownBlock
    // citation contract (inline [n] marks, hover excerpt, `citation-select` deep-link to the exact
    // local passage), resolved by the shared `citationResolve` authority — the same chain the chat
    // surface and RAG path use. The chip row mirrors UnifiedChatView's source-chip grammar (a
    // follow-up consolidates both into one component once the agent-window slice lands).
    const sources = this.s.ai.sources ?? [];
    const marks = resolveAnswerCitations(sources, this.s.ai.citations ?? []);
    return html`<div class="answer">
      <jf-markdown-block
        .text=${this.s.ai.text}
        ?is-streaming=${this.s.ai.loading}
        .citations=${marks}
      ></jf-markdown-block>
      ${this.renderAnswerSources(sources)}
    </div>`;
  }

  /** Tempdoc 577 Phase 1 — compact per-answer source chips ([n] · filename, click = the existing
   *  `citation-select` deep-link). Renders only when the answer carries grounding. */
  private renderAnswerSources(sources: readonly AgentSource[]): TemplateResult | typeof nothing {
    if (sources.length === 0) return nothing;
    return html`<div class="answer-sources" role="group" aria-label="Answer sources">
      ${sources.map((s, i) => {
        const name = s.title || s.path.split(/[\\/]/).pop() || s.path;
        return html`<button
          class="answer-source-chip"
          aria-label=${`Source ${i + 1}: ${name} — open at line ${s.startLine}`}
          title="Open ${s.path} at line ${s.startLine}"
          @click=${() => this.onAnswerSourceSelect(s)}
        ><span class="answer-source-n">${i + 1}</span><span class="answer-source-name">${name}</span></button>`;
      })}
    </div>`;
  }

  /** Dispatch the existing `citation-select` contract (Shell routes it to preview-highlight). */
  private onAnswerSourceSelect(s: AgentSource): void {
    const detail: CitationSelectDetail = {
      parentDocId: s.parentDocId,
      startLine: s.startLine,
      endLine: s.endLine,
      startChar: 0,
      endChar: 0,
      excerpt: s.excerpt,
    };
    this.dispatchEvent(
      new CustomEvent<CitationSelectDetail>('citation-select', {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  private renderInput(): TemplateResult {
    return html`
      <div>
        <textarea
          placeholder="Ask a question about this file..."
          .value=${this.promptDraft}
          @input=${(e: Event) =>
            (this.promptDraft = (e.target as HTMLTextAreaElement).value)}
          @keydown=${(e: KeyboardEvent) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
              e.preventDefault();
              void this.sendQuestion();
            }
          }}
        ></textarea>
        <jf-button
          class="send"
          variant="primary"
          ?disabled=${this.promptDraft.trim().length === 0 || this.s.ai.loading}
          .onActivate=${() => void this.sendQuestion()}
        >
          ${this.s.ai.loading
            ? html`${icon({ name: 'loader-2', size: 12, spin: true })} Sending…`
            : html`${icon({ name: 'send', size: 12 })} Ask (Ctrl+Enter)`}
        </jf-button>
      </div>
    `;
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.s.isOpen) return nothing;
    const item = this.s.selected;
    if (!item) {
      return html`
        <div class="header">
          <div class="title-row">
            <span class="title">Inspector</span>
            <jf-button class="icon" variant="ghost" size="icon" label="Close" .onActivate=${() => setOpen(false)}>
              ${icon({ name: 'x', size: 14 })}
            </jf-button>
          </div>
        </div>
        <div class="body">
          <div class="empty">Select a result to inspect.</div>
        </div>
      `;
    }
    return html`
      <div class="header">
        <div class="title-row">
          <span class="title" title=${item.title}>${item.title}</span>
          <jf-button class="icon" variant="ghost" size="icon" label="Close" .onActivate=${() => setOpen(false)}>
            ${icon({ name: 'x', size: 14 })}
          </jf-button>
        </div>
        <div class="meta" title=${item.path}>${item.path}</div>
      </div>
      <div class="tabs">
        ${this.tabEntries.map(
          (t) => html`
            <button
              class=${this.s.activeTab === t.id ? 'active' : ''}
              @click=${() => setActiveTab(t.id as InspectorTab)}
            >
              ${t.label}
            </button>
          `,
        )}
      </div>
      <div class="body">
        ${this.s.activeTab === 'preview' ? this.renderPreview() : nothing}
        ${this.s.activeTab === 'context' ? this.renderContext() : nothing}
        ${this.s.activeTab === 'answer' ? this.renderAnswer() : nothing}
        ${this.s.activeTab === 'input' ? this.renderInput() : nothing}
        ${this.renderPluginTab()}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-inspector-pane')) {
  customElements.define('jf-inspector-pane', InspectorPane);
}
