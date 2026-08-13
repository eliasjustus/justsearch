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
    prose: { type: Boolean, reflect: true },
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
  /**
   * Tempdoc 822 §C2/§2.3 (slice S5) — the opt-in prose variant. Off is the shipped rendering, and a
   * consumer that never sets it cannot be reached by a single variant rule (the containment is the
   * selector's, not a value comparison). A surface sets it when its answers are DOCUMENTS —
   * headings, tables, rules — rather than the compact chat/trace prose the defaults are cut for.
   */
  declare prose: boolean;

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
    this.prose = false;
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
    /* Tempdoc 822 §C2/§2.2 (slice S4) — the block-geometry vocabulary. Every value below is the
       literal this stylesheet already carried, moved to a name a consumer can re-point from the
       outer tree (an outer-tree rule on the host beats a :host rule). The containment rule is the
       point of the slice: changing ANY default here changes shipped rendering, so the defaults are
       frozen verbatim in 'MarkdownBlock.geometry.test.ts' and asserted against the pre-tokenization
       computed set — a "tidy-up" of 0.125em to 2px is a containment failure, not a cleanup.
       The rules that do NOT exist today (headings, tables, hr, img) are deliberately NOT tokens: a
       token cannot express "this rule exists", so they land behind ':host([prose])' in slice S5. */
    :host {
      --md-line-height: 1.6;
      --md-block-gap: 0.25em;
      --md-block-gap-wide: 0.5em;
      --md-item-gap: 0.125em;
      --md-list-indent: 1.25rem;
      /* A shorthand, not a width: the default 'none' computes to zero width, which is byte-identical
         to declaring no border at all ('1px solid transparent' would shift every chip by 2px). */
      --md-code-border: none;
      --md-code-radius: 0.25rem;
      --md-code-padding: 0.125rem 0.375rem;
      --md-code-size: var(--font-size-sm);
      --md-code-font: monospace;
      --md-pre-radius: 0.375rem;
      --md-pre-padding: 0.625rem 0.75rem;
      --md-quote-border: 3px solid var(--border-subtle);
      --md-quote-padding: 0.75rem;
      --md-link-decoration: underline;

      display: block;
      font-family: system-ui, -apple-system, sans-serif;
      font-size: var(--font-size-sm);
      line-height: var(--md-line-height);
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
    /* Tempdoc 687 R1c (principle P2 — mark the exception, not the rule): well-grounded prose
       renders PLAIN; only below-high tiers carry a mark. An indicator that is on for nearly every
       sentence carries no information — the reader's eye belongs on the rare weak/unsupported span.
       (Uncited prose is deliberately unmarked pending live score-distribution sampling — marking it
       without evidence risks P2's own retirement condition: noisy exception marks erode trust.) */
    .cite-sentence.grounding-grounded {
      border-bottom: none;
    }
    .cite-sentence.grounding-weak {
      border-bottom: 1px dotted var(--text-secondary);
    }
    .cite-sentence.grounding-ungrounded {
      border-bottom: 1px dotted var(--accent-warning);
    }
    .md-content p {
      margin: var(--md-block-gap) 0;
    }
    .md-content p:first-child {
      margin-top: 0;
    }
    .md-content p:last-child {
      margin-bottom: 0;
    }
    .md-content code {
      background: var(--surface-tertiary);
      padding: var(--md-code-padding);
      border: var(--md-code-border);
      border-radius: var(--md-code-radius);
      font-family: var(--md-code-font);
      font-size: var(--md-code-size);
    }
    .md-content pre {
      background: var(--surface-tertiary);
      padding: var(--md-pre-padding);
      border-radius: var(--md-pre-radius);
      overflow-x: auto;
      margin: var(--md-block-gap-wide) 0;
    }
    /* The block's inner <code> keeps shedding the inline chip's clothes (this rule's existing job):
       'border: none' joins background/padding/size because a consumer that gives the inline chip an
       edge means the CHIP, not a second rule inside the already-framed block. Zero shipped delta —
       the chip's own default is 'none'. */
    .md-content pre code {
      background: none;
      border: none;
      padding: 0;
      font-size: var(--font-size-xs);
    }
    .md-content ul, .md-content ol {
      margin: var(--md-block-gap) 0;
      padding-left: var(--md-list-indent);
    }
    .md-content li {
      margin: var(--md-item-gap) 0;
    }
    .md-content a {
      color: var(--text-tint);
      text-decoration: var(--md-link-decoration);
    }
    /* Unconditional, not variant-gated: with the default 'underline' at rest this is a no-op on
       every shipped surface (already underlined); it restores the hover affordance for a consumer
       whose override removes the resting rule. */
    .md-content a:hover {
      text-decoration: underline;
    }
    .md-content strong {
      color: var(--text-primary);
      font-weight: 600;
    }
    .md-content blockquote {
      border-left: var(--md-quote-border);
      padding-left: var(--md-quote-padding);
      margin: var(--md-block-gap-wide) 0;
      color: var(--text-secondary);
    }

    /* ── Tempdoc 822 §C2/§2.3 (slice S5) — the opt-in prose variant ─────────────────────────────
       Everything below is markup this stylesheet declares NOTHING for today (headings, tables, hr,
       img, task lists) or a rhythm the shipped surfaces deliberately do not have. A token cannot
       express "this rule exists" and any declared default would change shipped rendering the moment
       a model emits a heading — so containment here is a property of the SELECTOR, not of a value:
       a consumer that does not set the attribute cannot be reached by ANY of it, and
       'MarkdownBlock.geometry.test.ts' proves no heading/table/hr/img selector lives outside this
       block. The variant's own tokens are declared on ':host([prose])' rather than ':host' for the
       same reason — a ':host' declaration would add declarations to the default path, which is the
       thing slice S4 froze. Values are the SHIPPED type ramp plus generic geometry; the design
       spec's numbers arrive only through a consumer's override (license containment, §2.1). */
    :host([prose]) {
      --md-heading-weight: 600;
      --md-heading-line-height: 1.3;
      /* Asymmetric on purpose — a heading belongs to what FOLLOWS it, so the space above is the
         separation from the previous block and the space below is not. */
      --md-heading-margin: 1.25rem 0 0.5rem;
      --md-table-size: var(--font-size-xs);
      --md-table-cell-padding: 0.45rem 0.75rem;
      --md-table-rule: 1px solid var(--border-subtle);
      /* The truncation cap (the design spec named this rule worth
         lifting): a single-line cell so arbitrary chat content cannot blow a column out. */
      --md-table-cell-max: 24rem;
      --md-rule: 1px solid var(--border-subtle);
      /* The gap lives BETWEEN items, not around each one — pairs with a consumer setting
         '--md-item-gap: 0' (the shipped default keeps its symmetric margins). */
      --md-item-adjacent-gap: 0.25rem;
    }
    :host([prose]) .md-content :is(h1, h2, h3, h4, h5, h6) {
      font-weight: var(--md-heading-weight);
      line-height: var(--md-heading-line-height);
      margin: var(--md-heading-margin);
    }
    /* The heading scale is the SHIPPED type ramp, step for step — the one typographic authority,
       read directly rather than wrapped in a second name (which would be a fork, and which the
       style-literal ratchet is right to distrust). A consumer retunes the ramp inside its own bridge:
       sv3 points these three steps at its '--font-size-sv3-*' scale, which already equals the
       spec's heading scale, so no rem literal of the spec's is copied here (§2.1). Nothing else in
       this stylesheet reads xl/lg/md, so "the ramp step" and "the heading size" are the same knob. */
    :host([prose]) .md-content h1 {
      font-size: var(--font-size-xl);
    }
    :host([prose]) .md-content h2 {
      font-size: var(--font-size-lg);
    }
    :host([prose]) .md-content h3 {
      font-size: var(--font-size-md);
    }
    /* h4-h6 share the bottom step — the ramp bottoms out there, and so does the spec's: the
       deepest headings sit at body size and are distinguished by weight alone. */
    :host([prose]) .md-content :is(h4, h5, h6) {
      font-size: var(--font-size-sm);
    }
    /* The deepest step recedes rather than shrinking further (there is no smaller step). */
    :host([prose]) .md-content h6 {
      color: var(--text-secondary);
    }
    :host([prose]) .md-content table {
      width: 100%;
      /* The renderer emits a BARE <table> — there is no wrapper element to scroll, and synthesising
         one would fight 'unsafeHTML': every re-render rebuilds this subtree, so a post-processed
         wrapper would have to be re-applied on each frame. A block-level table scrolls itself. */
      display: block;
      overflow-x: auto;
      max-inline-size: 100%;
      border-collapse: collapse;
      font-size: var(--md-table-size);
      margin: var(--md-block-gap-wide) 0;
    }
    :host([prose]) .md-content :is(th, td) {
      padding: var(--md-table-cell-padding);
      border-bottom: var(--md-table-rule);
      /* Truncate: one line per cell, clipped at the cap, so a pasted path or a long sentence cannot
         widen the column past the reading measure (per the design spec). */
      max-inline-size: var(--md-table-cell-max);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      /* The word-boundary restoration the same spec note calls for: a consumer that sets
         'word-break: break-word' on the block (sv3 does, so an unbroken token in prose cannot widen
         the measure) would otherwise let a table column collapse mid-word. Inside a cell the
         minimum column width is the longest WORD. */
      word-break: normal;
      overflow-wrap: normal;
    }
    :host([prose]) .md-content th {
      text-align: start;
      font-weight: 600;
    }
    /* Expand: the spec's control is a button on a table component we do not port (no DOM
       post-processing, see above), so the affordance is the row itself — pointing at or tabbing into
       a truncated row releases the single-line clamp and the cells wrap to their full content. The
       row, not the cell, because expanding one cell reflows the whole row anyway. */
    :host([prose]) .md-content tr:hover :is(th, td),
    :host([prose]) .md-content tr:focus-within :is(th, td) {
      white-space: normal;
      overflow: visible;
      text-overflow: clip;
    }
    :host([prose]) .md-content hr {
      border: 0;
      border-top: var(--md-rule);
      margin: var(--md-block-gap-wide) 0;
    }
    :host([prose]) .md-content img {
      max-inline-size: 100%;
      height: auto;
      border-radius: var(--md-pre-radius);
    }
    /* GFM task lists: the checkbox replaces the marker and reclaims the list indent, so a checked
       item lines up with the prose above it instead of hanging off a bullet. */
    :host([prose]) .md-content li:has(> input[type='checkbox']) {
      list-style: none;
      margin-inline-start: calc(var(--md-list-indent) * -1);
    }
    :host([prose]) .md-content li > input[type='checkbox'] {
      margin-inline-end: 0.4em;
    }
    :host([prose]) .md-content li + li {
      margin-block-start: var(--md-item-adjacent-gap);
    }
    /* A nesting vocabulary: depth is readable from the marker alone. */
    :host([prose]) .md-content ul ul {
      list-style: circle;
    }
    :host([prose]) .md-content ul ul ul {
      list-style: square;
    }
    :host([prose]) .md-content ol ol {
      list-style: lower-alpha;
    }
    :host([prose]) .md-content ol ol ol {
      list-style: lower-roman;
    }
    /* Four-sided, not just the left inset: a quote in prose rhythm is a block, not an indent. */
    :host([prose]) .md-content blockquote {
      padding: var(--md-quote-padding);
    }
    /* The block's own edges belong to the container, for EVERY block child — the default path zeroes
       only 'p' (the only block it can be sure a shipped surface renders). */
    :host([prose]) .md-content > :first-child {
      margin-block-start: 0;
    }
    :host([prose]) .md-content > :last-child {
      margin-block-end: 0;
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
    /* Tempdoc 822 §3c — the missing weakest-tier rule (the citation-mark presentation session's line
       range; landed here under the design's crossing-1 default). Without it cite-ungrounded fell
       through to .cite-ref's blue and the WEAKEST tier wore the STRONGEST tier's color. The mark now
       speaks the sentence body's own tier vocabulary (none / secondary / warning, see .cite-sentence
       above), so mark and underline agree. The token is the warning role's TEXT member, not the fill
       the body's border uses: check-accent-as-text forbids an --accent-* fill as a text color, and
       --text-warning is the AA-checked foreground of the same role (sv3 bridges both to
       --warning-foreground, so the two are literally one color there). */
    .cite-ref.cite-ungrounded {
      color: var(--text-warning);
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
    // Tempdoc 687 R3a — labels that get a real rendered marker below; the literal-token
    // normalizer strips duplicates of these and upgrades the rest.
    const insertedLabels = new Set<number>(inserts.map((i) => Number(i.cite.label)));
    if (inserts.length === 0) {
      this.normalizeLiteralCitationTokens(root, insertedLabels);
      return;
    }

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
    this.normalizeLiteralCitationTokens(root, insertedLabels);
  }
  /**
   * Tempdoc 687 R3a (trust surfaces are literal — one citation notation per answer): local models
   * often write literal "[n]" tokens in prose ALONGSIDE the renderer's superscript marks. Any
   * literal [n] whose n matches a real citation label is normalized: stripped when that citation
   * already carries a rendered marker (dedupe), upgraded to the same marker span otherwise.
   * Tokens inside code/pre are untouched (verbatim content), as are numbers with no matching
   * citation (e.g. "[3]" in quoted document text with 2 sources).
   */
  private normalizeLiteralCitationTokens(root: HTMLElement, insertedLabels: Set<number>): void {
    const byLabel = new Map<number, Citation>(this.citations.map((c) => [Number(c.label), c]));
    if (byLabel.size === 0) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes: Text[] = [];
    let n: Node | null;
    while ((n = walker.nextNode())) nodes.push(n as Text);
    const re = /\s?\[(\d+)\]/g;
    for (const node of nodes) {
      if ((node.parentElement)?.closest('pre, code, .cite-ref')) continue;
      const matches = [...node.data.matchAll(re)].filter((m) => byLabel.has(Number(m[1])));
      // Right-to-left so earlier offsets stay valid across splits.
      for (const m of matches.reverse()) {
        const label = Number(m[1]);
        const start = m.index ?? 0;
        const token = node.splitText(start);
        token.splitText(m[0].length);
        if (insertedLabels.has(label)) {
          token.remove();
        } else {
          const marker = this.makeMarker(byLabel.get(label)!);
          token.parentNode?.replaceChild(marker, token);
          insertedLabels.add(label);
        }
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
