// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 497 — Markdown rendering block for chat messages.
 *
 * Renders text as markdown using `marked` + `DOMPurify`. During streaming,
 * applies a mend pass to auto-close unclosed syntax (code fences, bold,
 * inline code) on a copy before parsing, preventing visual glitches.
 * Renders are throttled to requestAnimationFrame during streaming.
 *
 * Uses a module-scoped Marked instance to avoid polluting global state.
 */

import { html, css, type TemplateResult, type PropertyValues } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { Marked } from 'marked';
import DOMPurify from 'dompurify';
import type { CitationSelectDetail } from './citationTypes.js';
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../../state/selectedSource.js';
// Tempdoc 565 §15.A — the ONE grounding-tier authority (was a forked `groundingStatus` here).
import { groundingClass, type AnswerFrame } from './evidenceProjection.js';

const md = new Marked({ breaks: true, gfm: true });

/**
 * Tempdoc 565 §15.B — the ONE resolved inline citation, shared by every answer mode.
 *
 * Before §15 the agent answer wove marks through this `MarkdownBlock` while the RAG answer wove a
 * SEPARATE per-sentence grammar through `StreamingTextBlock` (its own `Claim` model + `cite-ref-click`
 * event). §15.B collapses both into this one renderer + one weave: a `Citation` carries the sentence
 * span, its grounding similarity (→ the one {@link groundingClass} tier authority), and the source it
 * cites (the `[n]` mark + the `citation-select` deep-link + the cross-surface selection key). Each
 * mark is fully resolved by the caller (UnifiedChatView maps the agent's `AgentSentenceCite`+
 * `AgentSource` OR the RAG `claimMatches` + the retrieval-citation sources), so the block stays a
 * pure renderer.
 */
export interface Citation {
  /** The answer sentence span the matcher grounded (raw text; may carry markdown markers). */
  sentenceText: string;
  /** Cross-encoder similarity → grounding tier (the one `groundingClass`/`groundingLabel` authority). */
  similarity: number;
  /** The other source indices this sentence also grounds to (multi-source; the primary is `detail`). */
  sourceRefs?: number[];
  /** The `[n]` label shown (1-based source position). */
  label: number;
  /** Click target — the `citation-select` deep-link to the exact local passage. */
  detail: CitationSelectDetail;
  /** Hover-preview fields. */
  hover: { excerpt: string; title: string; headingText: string };
}

/**
 * @deprecated Tempdoc 565 §15.B renamed this to {@link Citation} (the one answer-mode citation). Kept
 * as a transitional alias so existing importers compile; new code uses `Citation`.
 */
export type MarkdownCitation = Citation;

/** Tempdoc 565 §15.B — the answer text's source format. `plain` renders verbatim (no markdown
 *  styling) for transcripts/extract/RAG-flat answers; `markdown` parses GFM. The ONE renderer
 *  serves both, so `jf-streaming-text-block` is retired. */
export type AnswerFormat = 'plain' | 'markdown';

/**
 * Auto-close unclosed markdown syntax on a copy of the text.
 * Only called during streaming to prevent visual glitches from partial syntax.
 * The source text is never modified.
 */
export function mendMarkdown(text: string): string {
  let result = text;

  // Count unclosed code fences (``` or ~~~). Each opening fence should have
  // a matching closing fence. If the count is odd, append a closer.
  const fencePattern = /^(`{3,}|~{3,})/gm;
  let fenceCount = 0;
  let lastFenceChar = '`';
  let lastFenceLen = 3;
  let match: RegExpExecArray | null;
  while ((match = fencePattern.exec(result)) !== null) {
    fenceCount++;
    lastFenceChar = match[1]![0]!;
    lastFenceLen = match[1]!.length;
  }
  if (fenceCount % 2 !== 0) {
    result += '\n' + lastFenceChar.repeat(lastFenceLen);
  }

  // Only check the trailing text for unclosed inline markers.
  // If we're inside a code fence (odd count), inline markers don't apply.
  if (fenceCount % 2 === 0) {
    const tail = result.slice(-300);

    // Unclosed bold (**) — count occurrences in the tail
    const boldCount = (tail.match(/\*\*/g) || []).length;
    if (boldCount % 2 !== 0) {
      result += '**';
    }

    // Unclosed italic (*) — count single asterisks not part of **
    const singleStarCount = (tail.replace(/\*\*/g, '').match(/\*/g) || []).length;
    if (singleStarCount % 2 !== 0) {
      result += '*';
    }

    // Unclosed inline code (`) — count backticks not part of fences
    const inlineCodeCount = (tail.replace(/`{3,}/g, '').match(/`/g) || []).length;
    if (inlineCodeCount % 2 !== 0) {
      result += '`';
    }
  }

  return result;
}

/**
 * Tempdoc 565 §13.8 — the UI is the single source authority (§3.A). Some models append a verbose,
 * self-authored "Citations:/Sources:/References:" list to the END of their prose (often with scores,
 * e.g. `Citations: [1] AI Architecture (score: 1.00)`), duplicating what the interface already shows
 * (inline `[n]` marks + the collapsible chip row + the docked rail). This strips that trailing,
 * model-written list so the UI owns the source presentation.
 *
 * Conservative — only strips a TRAILING block that BOTH (a) begins, after a blank line, with a
 * `Citations/Sources/References` heading (optionally bold or an ATX heading), AND (b) contains a
 * bracketed `[n]` reference. Inline `[n]` marks inside the answer prose and any mid-text "Sources:"
 * sentence are untouched (they lack the leading blank-line heading + trailing-to-EOF shape). Pure;
 * unit-tested alongside `mendMarkdown`.
 */
const TRAILING_CITATION_BLOCK_RE =
  /\n[ \t]*\n[ \t]*(?:#{1,6}[ \t]*)?(?:\*\*|__)?[ \t]*(?:citations?|sources?|references?)\b[\s\S]*$/i;
export function stripTrailingCitationBlock(text: string): string {
  if (!text) return text;
  const m = text.match(TRAILING_CITATION_BLOCK_RE);
  if (!m || m.index === undefined) return text;
  // Only strip a block that LOOKS like a citation list (carries a [n] reference) — never bare prose.
  if (!/\[\d+\]/.test(m[0])) return text;
  return text.slice(0, m.index).replace(/[ \t\r\n]+$/, '');
}

export class MarkdownBlock extends JfElement {
  static properties = {
    text: { type: String },
    isStreaming: { type: Boolean, attribute: 'is-streaming', reflect: true },
    format: { type: String, reflect: true },
    citations: { attribute: false },
    frame: { type: String, reflect: true },
  };

  declare text: string;
  declare isStreaming: boolean;
  /** Tempdoc 565 §15.B — `plain` renders verbatim (was StreamingTextBlock); `markdown` parses GFM. */
  declare format: AnswerFormat;
  /** Tempdoc 565 §15.B — resolved inline citation marks woven into the rendered answer (or []). */
  declare citations: Citation[];
  /**
   * Tempdoc 577 §2.12 Move 3 — the answer's epistemic frame ({@link AnswerFrame}). When
   * `ungrounded`, model-authored citation-shaped text (`[n]`/`(n)`) is neutralized to a muted,
   * non-credible span so the LLM cannot borrow the index's citation credibility (the §2.11 #4
   * fabricated-citations defect). Default `grounded` is a no-op.
   */
  declare frame: AnswerFrame;

  private rafId: number | null = null;
  private pendingText: string | null = null;
  private renderedText = '';
  private selectedSourceUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.text = '';
    this.isStreaming = false;
    this.format = 'markdown';
    this.citations = [];
    this.frame = 'grounded';
  }

  private onCopy = (e: ClipboardEvent): void => {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed) return;
    const plain = sel.toString();
    e.clipboardData?.setData('text/plain', plain);
    e.preventDefault();
  };

  override connectedCallback(): void {
    super.connectedCallback();
    this.addEventListener('copy', this.onCopy as EventListener);
    // Tempdoc 565 §12.3.E — re-paint the inline [n] highlight when the cross-surface selection changes
    // (a rail card or another mark was focused). Toggles a class on existing markers — no re-decorate.
    this.selectedSourceUnsub = subscribeSelectedSource(() => this.applyCitationHighlight());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.removeEventListener('copy', this.onCopy as EventListener);
    this.selectedSourceUnsub?.();
    this.selectedSourceUnsub = null;
    if (this.rafId !== null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }

  /**
   * Tempdoc 565 §12.3.E — toggle the `.cite-selected` class on the inline marks to match the
   * cross-surface selection, without rebuilding them (decorateCitations early-returns once markers
   * exist). Each marker carries its source identity in `data-cite-key`.
   */
  private applyCitationHighlight(): void {
    const root = this.renderRoot.querySelector('.md-content');
    if (!root) return;
    const selected = getSelectedSource();
    for (const m of root.querySelectorAll<HTMLElement>('.cite-ref')) {
      m.classList.toggle('cite-selected', !!selected && m.dataset.citeKey === selected);
    }
  }

  override updated(changed: PropertyValues): void {
    if (changed.has('text') && this.isStreaming && this.text !== this.renderedText) {
      if (this.rafId === null) {
        this.pendingText = this.text;
        this.rafId = requestAnimationFrame(() => {
          this.rafId = null;
          if (this.pendingText !== null && this.pendingText !== this.renderedText) {
            this.renderedText = this.pendingText;
            this.pendingText = null;
            this.requestUpdate();
          }
        });
      } else {
        this.pendingText = this.text;
      }
    }
    // Tempdoc 565 §3.C — weave inline citation marks into the freshly-rendered markdown. Citations
    // attach post-stream (the matcher runs at AgentDone), so only decorate the settled answer. Lit's
    // unsafeHTML re-render wipes prior markers, so re-decorating on every render keeps them correct.
    if (!this.isStreaming && this.citations.length > 0) {
      this.decorateCitations();
    }
    // Tempdoc 577 Move 3 — neutralize model-authored citation-shaped text in an UNGROUNDED answer so
    // it cannot pose as a verifiable reference. Runs on the settled answer (post-stream), uniformly
    // for plain + markdown (both produce `.md-content` text nodes), mirroring decorateCitations.
    if (!this.isStreaming && this.frame === 'ungrounded') {
      this.neutralizePseudoCitations();
    }
  }

  /**
   * Tempdoc 577 §2.12 Move 3 — wrap bare `[n]` / `(n)` tokens in the rendered answer with a muted,
   * non-interactive span so an ungrounded model answer's fabricated markers read as plain text, not
   * as the index's clickable citations. Idempotent (skips already-wrapped runs).
   */
  private neutralizePseudoCitations(): void {
    const root = this.shadowRoot?.querySelector('.md-content');
    if (!root) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const targets: Text[] = [];
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const t = n as Text;
      // Skip text already inside a pseudo-cite (idempotency) and the real cite marks.
      const parent = t.parentElement;
      if (parent?.closest('.pseudo-cite, .cite-ref')) continue;
      if (/[[(]\d{1,3}[\])]/.test(t.data)) targets.push(t);
    }
    for (const node of targets) {
      const frag = document.createDocumentFragment();
      const parts = node.data.split(/([[(]\d{1,3}[\])])/);
      for (const part of parts) {
        if (/^[[(]\d{1,3}[\])]$/.test(part)) {
          const span = document.createElement('span');
          span.className = 'pseudo-cite';
          span.textContent = part;
          frag.appendChild(span);
        } else if (part.length > 0) {
          frag.appendChild(document.createTextNode(part));
        }
      }
      node.parentNode?.replaceChild(frag, node);
    }
  }

  static styles = css`
    :host {
      display: block;
      font-family: system-ui, -apple-system, sans-serif;
      font-size: var(--font-size-sm);
      line-height: 1.6;
      color: var(--text-primary);
      word-wrap: break-word;
    }
    /* Tempdoc 565 §15.B — plain format renders verbatim (the retired StreamingTextBlock job):
       preserve whitespace/newlines, no markdown block styling. */
    .md-content.plain {
      white-space: pre-wrap;
    }
    /* Tempdoc 565 §15.B — the cited sentence body, tier-colored (the union with StreamingTextBlock's
       per-sentence grounding coloring). A subtle bottom-border keyed to the grounding tier reads in
       flowing markdown prose where a left-border would not. */
    .cite-sentence.grounding-grounded {
      border-bottom: 1px solid var(--accent-tint);
    }
    .cite-sentence.grounding-weak {
      border-bottom: 1px dotted var(--text-secondary);
    }
    .cite-sentence.grounding-ungrounded {
      border-bottom: none;
    }
    .md-content p {
      margin: 0.25em 0;
    }
    .md-content p:first-child {
      margin-top: 0;
    }
    .md-content p:last-child {
      margin-bottom: 0;
    }
    .md-content code {
      background: var(--surface-tertiary);
      padding: 0.125rem 0.375rem;
      border-radius: 0.25rem;
      font-family: monospace;
      font-size: var(--font-size-sm);
    }
    .md-content pre {
      background: var(--surface-tertiary);
      padding: 0.625rem 0.75rem;
      border-radius: 0.375rem;
      overflow-x: auto;
      margin: 0.5em 0;
    }
    .md-content pre code {
      background: none;
      padding: 0;
      font-size: var(--font-size-xs);
    }
    .md-content ul, .md-content ol {
      margin: 0.25em 0;
      padding-left: 1.25rem;
    }
    .md-content li {
      margin: 0.125em 0;
    }
    .md-content a {
      color: var(--text-tint);
      text-decoration: underline;
    }
    .md-content strong {
      color: var(--text-primary);
      font-weight: 600;
    }
    .md-content blockquote {
      border-left: 3px solid var(--border-subtle);
      padding-left: 0.75rem;
      margin: 0.5em 0;
      color: var(--text-secondary);
    }
    .cursor {
      display: inline-block;
      width: 0.5ch;
      background: var(--accent-tint);
      animation: jf-cursor-blink 1.05s steps(2, start) infinite;
      margin-left: 0.1ch;
      height: 1em;
      vertical-align: text-bottom;
    }
    @keyframes jf-cursor-blink {
      to { visibility: hidden; }
    }
    /* a11y — honor prefers-reduced-motion: stop the continuous blink (an infinite
       animation is the strongest reduced-motion trigger). The cursor stays visible. */
    @media (prefers-reduced-motion: reduce) {
      .cursor { animation: none; }
    }
    /* Tempdoc 565 §3.C — inline citation superscript (mirrors StreamingTextBlock .cite-ref). */
    .cite-ref {
      font-size: var(--font-size-xs);
      vertical-align: super;
      color: var(--text-tint);
      cursor: pointer;
      margin-left: 0.1em;
      font-weight: 600;
      user-select: none;
    }
    .cite-ref:hover {
      text-decoration: underline;
    }
    .cite-ref.cite-weak {
      color: var(--text-secondary);
    }
    /* Tempdoc 565 §12.3.E — the cross-surface selection: this mark cites the source the user focused
       (in the answer or the evidence rail), highlighted in sync with the rail card. */
    .cite-ref.cite-selected {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 0.25em;
      padding: 0 0.25em;
      text-decoration: none;
    }
    /* Tempdoc 577 §2.12 Move 3 — a model-authored citation-shaped token in an UNGROUNDED answer:
       muted inline text (NOT the accent superscript of a real cite-ref), so it cannot pose as a
       verifiable reference. Non-interactive by construction (a plain span, no handlers). */
    .pseudo-cite {
      color: var(--text-secondary);
      opacity: 0.7;
    }
  `;

  override render(): TemplateResult {
    // §13.8 — strip any model-authored trailing "Citations:" list (the UI is the source authority);
    // then mend partial syntax during streaming. Strip-before-mend so a half-written trailing list
    // never flashes (the strip matches the partial block's trailing-to-EOF shape too).
    const stripped = stripTrailingCitationBlock(this.text);
    const cursor = this.isStreaming ? html`<span class="cursor">&nbsp;</span>` : '';
    // Tempdoc 565 §15.B — the ONE renderer: `plain` renders the text verbatim (the retired
    // StreamingTextBlock's job — no markdown styling, whitespace preserved); `markdown` parses GFM.
    // The citation weave (decorateCitations) walks text nodes either way, so both modes get marks.
    if (this.format === 'plain') {
      return html`<div class="md-content plain">${stripped}</div>${cursor}`;
    }
    const source = this.isStreaming ? mendMarkdown(stripped) : stripped;
    const raw = source ? (md.parse(source, { async: false }) as string) : '';
    const safe = DOMPurify.sanitize(raw);
    return html`<div class="md-content">${unsafeHTML(safe)}</div>${cursor}`;
  }

  /**
   * Tempdoc 565 §3.C — weave `[n]` citation superscripts into the rendered markdown. Walks the
   * settled `.md-content` text nodes, locates each citation's sentence by a whitespace-tolerant,
   * marker-stripped match, and splices a `.cite-ref` marker at the sentence boundary. A sentence that
   * can't be located is skipped (it still appears in the Sources pane — never fail the whole render).
   */
  private decorateCitations(): void {
    const root = this.renderRoot.querySelector('.md-content') as HTMLElement | null;
    if (!root) return;
    // A fresh unsafeHTML render has no markers; if any exist, this render is already decorated.
    if (root.querySelector('.cite-ref')) return;

    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const ranges: Array<{ node: Text; start: number; end: number }> = [];
    let full = '';
    let tn: Node | null;
    while ((tn = walker.nextNode())) {
      const t = tn as Text;
      const start = full.length;
      full += t.data;
      ranges.push({ node: t, start, end: full.length });
    }
    if (!full) return;

    const inserts: Array<{ startIndex: number; endIndex: number; cite: Citation }> = [];
    const seen = new Set<number>();
    for (const cite of this.citations) {
      const norm = this.stripMarkers(cite.sentenceText).trim();
      if (norm.length < 4) continue; // too short to anchor reliably
      let re: RegExp;
      try {
        re = new RegExp(this.escapeRegex(norm).replace(/\s+/g, '\\s+'), 'i');
      } catch {
        continue;
      }
      const m = re.exec(full);
      if (!m) continue; // graceful skip
      const endIndex = m.index + m[0].length;
      if (seen.has(endIndex)) continue;
      seen.add(endIndex);
      inserts.push({ startIndex: m.index, endIndex, cite });
    }
    if (inserts.length === 0) return;

    // Insert LAST→FIRST so earlier node offsets stay valid across splitText.
    inserts.sort((a, b) => b.endIndex - a.endIndex);
    for (const { startIndex, endIndex, cite } of inserts) {
      const endRange = ranges.find((r) => endIndex > r.start && endIndex <= r.end);
      if (!endRange) continue;
      // Tempdoc 565 §15.B — insert the tier-colored `[n]` mark at the sentence boundary…
      const tail = endRange.node.splitText(endIndex - endRange.start);
      endRange.node.parentNode?.insertBefore(this.makeMarker(cite), tail);
      // …then color the cited sentence body by its grounding tier (the union with the retired
      // StreamingTextBlock's per-sentence coloring). §15.C fix: wrap EVERY text-node segment the
      // sentence spans (not just the single-node case), so a sentence crossing inline markup still
      // underlines its text runs; inline elements (bold/link) between runs are left intact (no
      // cross-element extract — the DOM is never corrupted). Process the spanned nodes LAST→FIRST so
      // each split keeps earlier nodes' offsets valid.
      const cls = `cite-sentence grounding-${groundingClass(cite.similarity)}`;
      const spanned = ranges
        .filter((r) => r.end > startIndex && r.start < endIndex)
        .sort((a, b) => b.start - a.start);
      for (const r of spanned) {
        // For the boundary node the marker split already truncated it to [r.start, endIndex).
        const segStart = Math.max(startIndex, r.start);
        const seg =
          segStart > r.start ? r.node.splitText(segStart - r.start) : r.node;
        const wrap = document.createElement('span');
        wrap.className = cls;
        seg.parentNode?.insertBefore(wrap, seg);
        wrap.appendChild(seg);
      }
    }
  }

  private makeMarker(cite: Citation): HTMLElement {
    const span = document.createElement('span');
    // Tempdoc 565 §12.3.E — the source identity this mark cites, so the cross-surface selection can
    // highlight it in sync with the matching rail card.
    const key = sourceKey(cite.detail.parentDocId, cite.detail.startLine);
    span.dataset.citeKey = key;
    const isSelected = getSelectedSource() === key;
    span.className = `cite-ref cite-${groundingClass(cite.similarity)}${isSelected ? ' cite-selected' : ''}`;
    span.textContent = String(cite.label);
    span.setAttribute('role', 'button');
    span.setAttribute('tabindex', '0');
    span.setAttribute('aria-label', `Citation ${cite.label} — open the cited passage`);
    span.title = cite.hover.title
      ? `${cite.hover.title} — open the cited passage`
      : 'Open the cited passage';
    const fire = (): void => {
      // Tempdoc 565 §12.3.E — focus this source across surfaces (highlight the matching rail card)
      // before the existing deep-link dispatch.
      setSelectedSource(key);
      this.dispatchEvent(
        new CustomEvent<CitationSelectDetail>('citation-select', {
          detail: cite.detail,
          bubbles: true,
          composed: true,
        }),
      );
    };
    span.addEventListener('click', (e) => {
      e.stopPropagation();
      fire();
    });
    span.addEventListener('keydown', (e) => {
      const k = (e as KeyboardEvent).key;
      if (k === 'Enter' || k === ' ') {
        e.preventDefault();
        fire();
      }
    });
    span.addEventListener('mouseenter', (e) => {
      const rect = (e.target as HTMLElement).getBoundingClientRect();
      this.dispatchEvent(
        new CustomEvent('cite-ref-hover', {
          detail: {
            rect,
            source: {
              excerpt: cite.hover.excerpt,
              parentDocId: cite.detail.parentDocId,
              score: cite.similarity,
              headingText: cite.hover.headingText,
              title: cite.hover.title,
            },
          },
          bubbles: true,
          composed: true,
        }),
      );
    });
    span.addEventListener('mouseleave', () => {
      this.dispatchEvent(new CustomEvent('cite-ref-leave', { bubbles: true, composed: true }));
    });
    return span;
  }

  /** Strip common inline markdown markers so the raw-text sentence matches the rendered DOM text. */
  private stripMarkers(s: string): string {
    return s.replace(/\[(.*?)\]\((.*?)\)/g, '$1').replace(/[*_`~#>]/g, '');
  }

  private escapeRegex(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-markdown-block')) {
  customElements.define('jf-markdown-block', MarkdownBlock);
}
