// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 497 — Markdown rendering block for chat messages.
 *
 * Renders text as markdown through the shared `createMarkdownRenderer` factory (tempdoc 846 §2.1 —
 * `marked` + `DOMPurify` configured in ONE place, with this consumer's `breaks` answer stated at the
 * call site). During streaming, applies a mend pass to auto-close unclosed syntax (code fences,
 * bold, inline code) on a copy before parsing, preventing visual glitches. Renders are throttled to
 * requestAnimationFrame during streaming.
 *
 * Typography is the shared ramp (846 §2.3), worn by this block and by `DocumentPane` alike; what
 * this file styles is what belongs to chat alone — the cursor and the citation vocabulary.
 */

import { html, css, type TemplateResult, type PropertyValues } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import { createMarkdownRenderer } from '../markdown/markdownRenderer.js';
import { markdownCodeHighlight, markdownTypography } from '../markdown/markdownStyles.js';
import { highlightCodeBlocks } from '../markdown/markdownHighlight.js';
import type { CitationSelectDetail } from './citationTypes.js';
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../../state/selectedSource.js';
// Tempdoc 565 §15.A — the ONE grounding-tier authority (was a forked `groundingStatus` here).
import { groundingClass, type AnswerFrame } from './evidenceProjection.js';

/**
 * Tempdoc 846 §2.1/§2.2 — the ONE configured parser, with this consumer's `breaks` answer stated at
 * the call site. OFF: every call site of this block renders MODEL-generated text (the agent answer,
 * the RAG answer, the extract, the summary/navigate streams, the reasoning trace), and a model
 * emits real markdown paragraphs — `breaks: true` chopped its soft-wrapped prose into forced lines.
 */
const md = createMarkdownRenderer({ breaks: false });

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
   * Tempdoc 822 §5.6 — the ONE accessible name of a citation mark: what the control IS and what
   * activating it does, and deliberately NOT what state it is in.
   *
   * The first cut appended "— selected" here as well as setting `aria-current`, so a reader met the
   * state twice in one announcement. Encoding a state in the accessible NAME alongside a real ARIA
   * state is the standard anti-pattern: the name is meant to be stable (it is what a voice-control
   * user says out loud to click the thing, and what a name-change announcement is measured against),
   * while the state is what `aria-current` exists to carry. Selection therefore moves the property
   * and leaves the name alone — which is also why this is a plain function of the label now.
   */
  private citeAriaLabel(label: string): string {
    return `Citation ${label} — open the cited passage`;
  }

  /**
   * Tempdoc 565 §12.3.E — toggle the `.cite-selected` class on the inline marks to match the
   * cross-surface selection, without rebuilding them (decorateCitations early-returns once markers
   * exist). Each marker carries its source identity in `data-cite-key`.
   *
   * Tempdoc 822 §5.3/§5.6 — and with it the two things the class alone never carried: the cited
   * SENTENCES of the focused source (F4 — the payload, not just the handle), and the state's
   * existence for assistive tech (F6 — `aria-current`). The accessible NAME is deliberately not
   * touched here: it is a stable description of the control, not a second channel for the state
   * (see {@link citeAriaLabel}).
   */
  private applyCitationHighlight(): void {
    const root = this.renderRoot.querySelector('.md-content');
    if (!root) return;
    const selected = getSelectedSource();
    for (const m of root.querySelectorAll<HTMLElement>('.cite-ref')) {
      const isSelected = !!selected && m.dataset.citeKey === selected;
      m.classList.toggle('cite-selected', isSelected);
      // REMOVED, not `aria-current="false"`: the false value is still announced by some screen
      // readers as a present-but-off property, which is noise on every unselected mark in the answer.
      if (isSelected) m.setAttribute('aria-current', 'true');
      else m.removeAttribute('aria-current');
    }
    for (const s of root.querySelectorAll<HTMLElement>('.cite-sentence')) {
      s.classList.toggle('cite-sentence-selected', !!selected && s.dataset.citeKey === selected);
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
    // Tempdoc 846 §2.4 — highlight fenced code on the SETTLED answer only (a stream re-renders per
    // frame, and a mended fence would flicker through languages). Runs BEFORE the citation weave:
    // highlighting rewrites a code block's innerHTML, which would discard anything woven into it.
    if (!this.isStreaming && this.format === 'markdown') {
      highlightCodeBlocks(this.renderRoot.querySelector('.md-content'));
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

  /**
   * Tempdoc 846 §2.3 — the markdown typography ramp is no longer this component's private
   * property: `markdownTypography` carries the `:host` geometry vocabulary, every `.md-content`
   * element rule and the whole `:host([prose])` variant, and `DocumentPane` wears the same sheet so
   * a rendered `.md` file stops falling back to user-agent defaults. `markdownCodeHighlight` is the
   * fenced-code theme (§2.4). What stays below is what belongs to CHAT and to no other markdown
   * surface: the verbatim `plain` format, the streaming cursor, and the citation vocabulary.
   *
   * The sheets are listed FIRST so a rule here has the last word, and this array must not declare a
   * second `:host` markdown-geometry rule — `MarkdownBlock.geometry.test.ts` reads the flattened
   * `styles` and proves the containment against the ONE `:host` rule the shared sheet declares.
   */
  static styles = [markdownTypography, markdownCodeHighlight, css`
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
    /* Tempdoc 822 citation-mark presentation §5.3 — what selection is FOR: the sentences the focused
       source supports, not just the handle that opened it (F4). Surface, so it composes with the
       tier underlines above rather than competing — a weakly-grounded sentence inside a selected
       region keeps its dotted rule. Default transparent ⇒ invisible on every shipped surface; a
       window opts in by re-pointing '--md-cite-region-bg'. */
    /* §5.3 (F2) — HORIZONTAL breathing room, tokenized and defaulting to 0 so shipped is unchanged.
       The inset cancels the padding exactly, so the wash extends past the text without moving a
       glyph. Horizontal ONLY, on purpose: '.grounding-weak' / '.grounding-ungrounded' draw their
       border-bottom on THIS element, so vertical padding would push a selected sentence's dotted
       underline lower than an unselected one's. 'box-decoration-break' is left at 'slice' for the
       same reason it is right: a wrapped sentence reads as one continuous highlight, rounded at the
       start of the first fragment and the end of the last — 'clone' would render it as pills.

       ACCEPTED TRADE-OFF, recorded rather than left to be re-discovered (independent review): the
       horizontal padding sits on the element that also draws that border-bottom, so a SELECTED weak
       or ungrounded sentence's dotted rule runs one pad-x past its glyphs at each end. No glyph
       moves and the tier still reads; what changes is the rule's LENGTH while selected. It is kept
       because every way to separate the two costs more than it buys: a border-bottom spans the
       border box, so padding, a transparent side-border and an outline all extend it alike; drawing
       the wash on a pseudo-element or an inner wrapper breaks the wrapped-sentence case that
       'box-decoration-break: slice' exists to serve (an absolutely-positioned ::before collapses a
       three-line sentence into one union rect); and moving the tier rule to a content-box background
       gradient would repaint every weakly-grounded sentence in the SHIPPED windows to fix 4px in
       this one. The cheap alternative — dropping the inset — restores the smear the live capture
       rejected. */
    .cite-sentence-selected {
      background: var(--md-cite-region-bg, transparent);
      border-radius: var(--md-cite-radius, 0.25em);
      padding: 0 var(--md-cite-region-pad-x, 0);
      margin: 0 var(--md-cite-region-inset-x, 0);
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
    /* Tempdoc 822 citation-mark presentation §5.2 — the mark's own geometry, named so a window can
       re-point it. These names are the CITE rules' own; they are deliberately NOT declared in the
       ':host' block above, which is the block-geometry workstream's (S4) and whose containment proof
       enumerates exactly fifteen '--md-*' names. The rest padding defaults to 0 ON PURPOSE: today
       only the SELECTED mark is padded, so a mark widens mid-sentence by 6px on click (F5).
       Reserving the space at rest closes that, but doing it by default would move every citation
       mark in the shipped window — the opposite of the containment S4 established. v3 opts in; the
       shipped window's geometry stays byte-identical. */
    .cite-ref {
      font-size: var(--font-size-xs);
      vertical-align: super;
      color: var(--text-tint);
      cursor: pointer;
      margin-left: 0.1em;
      font-weight: 600;
      user-select: none;
      padding: 0 var(--md-cite-pad-x-rest, 0);
      border-radius: var(--md-cite-radius, 0.25em);
    }
    .cite-ref:hover {
      text-decoration: underline;
    }
    /* The tier INK is tokenized for one reason only, and it is not taste (independent review of the
       822 citation-mark slice): the selected mark paints a wash BEHIND this glyph, and a subdued tier
       colour that clears AA on the bare background can drop under it on the composite. The remedy the
       design named is "the weak tier's colour moves, not the wash" (§7.5) — so a window that opts
       into a selection wash also opts into a tier ink lifted far enough to survive it. Defaults are
       today's values ⇒ shipped rendering byte-identical. */
    .cite-ref.cite-weak {
      color: var(--md-cite-weak-color, var(--text-secondary));
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
      color: var(--md-cite-ungrounded-color, var(--text-warning));
    }
    /* Tempdoc 565 §12.3.E — the cross-surface selection: this mark cites the source the user focused
       (in the answer or the evidence rail), highlighted in sync with the rail card.
       Tempdoc 822 citation-mark presentation §4/§5.2 — SELECTION PAINTS SURFACE ONLY; 'color' belongs
       to the grounding tier. This rule used to set 'color' at the same specificity as .cite-weak /
       .cite-ungrounded and later in source, so selecting a mark REPAINTED it: clicking the amber
       "not supported" numeral hid that it was unsupported (F2). Two declarations therefore left this
       rule and must not come back — the 'color' (so the tier survives selection) and the
       text-decoration override that cancelled the hover underline, so the mark most likely to be
       re-clicked keeps confirming that it is clickable (F8). No ink-override
       token is minted alongside the surface ones: an ink escape hatch is an F2 escape hatch. */
    .cite-ref.cite-selected {
      padding: 0 var(--md-cite-pad-x, 0.25em);
      background: var(--md-cite-selected-bg, var(--accent-tint));
      box-shadow: inset 0 0 0 1px var(--md-cite-selected-edge, transparent);
    }
    /* Tempdoc 577 §2.12 Move 3 — a model-authored citation-shaped token in an UNGROUNDED answer:
       muted inline text (NOT the accent superscript of a real cite-ref), so it cannot pose as a
       verifiable reference. Non-interactive by construction (a plain span, no handlers). */
    .pseudo-cite {
      color: var(--text-secondary);
      opacity: 0.7;
    }
  `];

  override render(): TemplateResult {
    // §13.8 — strip any model-authored trailing "Citations:" list (the UI is the source authority);
    // then mend partial syntax during streaming. Strip-before-mend so a half-written trailing list
    // never flashes (the strip matches the partial block's trailing-to-EOF shape too).
    //
    // Tempdoc 846 §2.5 — but ONLY when this block actually has sources to show. The strip's whole
    // justification is that the interface presents the sources itself; with no citations the UI
    // presents nothing, so deleting the model's own trailing list replaces information with
    // silence. Accepted consequence: citations attach post-stream (the matcher runs at AgentDone),
    // so a trailing list the model is writing is now visible until they arrive — a brief flash of
    // real output beats a silent deletion in the case where nothing replaces it.
    const stripped = this.citations.length > 0 ? stripTrailingCitationBlock(this.text) : this.text;
    const cursor = this.isStreaming ? html`<span class="cursor">&nbsp;</span>` : '';
    // Tempdoc 565 §15.B — the ONE renderer: `plain` renders the text verbatim (the retired
    // StreamingTextBlock's job — no markdown styling, whitespace preserved); `markdown` parses GFM.
    // The citation weave (decorateCitations) walks text nodes either way, so both modes get marks.
    if (this.format === 'plain') {
      return html`<div class="md-content plain">${stripped}</div>${cursor}`;
    }
    const source = this.isStreaming ? mendMarkdown(stripped) : stripped;
    return html`<div class="md-content">${unsafeHTML(md.render(source))}</div>${cursor}`;
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
      // Tempdoc 822 §5.3 — the sentence carries the SAME source identity the mark does, so selecting
      // a source can highlight the sentences it supports. Computed through the one `sourceKey`
      // authority `makeMarker` uses: a second key function here would silently never match.
      const sentenceKey = sourceKey(cite.detail.parentDocId, cite.detail.startLine);
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
        wrap.dataset.citeKey = sentenceKey;
        // A re-render rebuilds these spans, so a region already selected has to come back selected —
        // the same reason `makeMarker` reads the store at construction.
        if (sentenceKey === getSelectedSource()) wrap.classList.add('cite-sentence-selected');
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
    // Tempdoc 822 §5.6 — a mark rendered into an already-selected state announces it from the start,
    // not only after the next selection change.
    if (isSelected) span.setAttribute('aria-current', 'true');
    span.setAttribute('aria-label', this.citeAriaLabel(String(cite.label)));
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
