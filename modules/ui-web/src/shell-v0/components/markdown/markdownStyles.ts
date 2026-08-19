// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 846 §2.3/§2.4 — the ONE markdown typography ramp, and the ONE code-block theme.
 *
 * `markdownTypography` is the stylesheet `MarkdownBlock` carried privately, moved here VERBATIM so
 * both markdown surfaces wear it: the chat renderer and `DocumentPane`'s Rendered mode, which until
 * now dressed a whole `.md` file in user-agent defaults (32px `h1`, unstyled tables, a `<pre>` with
 * no surface — on the one surface whose entire job is reading a document).
 *
 * Moved, not rewritten. Every token name, every default and every use site is the one tempdoc 822
 * froze (slices S4/S5), so Search v3's `.sv3-markdown` bridge keeps re-pointing exactly what it
 * re-pointed before, and `MarkdownBlock.geometry.test.ts` — which reads `MarkdownBlock.styles`, an
 * array being flattened and joined — still proves containment against these rules WITHOUT an edit.
 * That test passing unmodified is the evidence the move is faithful; keep it that way:
 *
 *   - the fifteen `--md-*` geometry tokens are declared on `:host` and nowhere else;
 *   - the nine variant tokens are declared on `:host([prose])` and nowhere else, and that rule
 *     carries NOTHING but `--md-*` declarations;
 *   - no heading / table / hr / img selector may appear outside a `:host([prose])` rule;
 *   - a consumer of this sheet must not declare a SECOND `:host` rule of markdown geometry.
 *
 * A consumer that needs to differ puts this sheet FIRST in its `static styles` array and overrides
 * in its own, later, sheet.
 */
import { css } from 'lit';

export const markdownTypography = css`
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
     spec's numbers arrive only through a consumer's override (license containment, §2.1).
     Tempdoc 846 §2.3 — 'DocumentPane' is the second surface to opt in (a document IS headings,
     tables and rules), through the same attribute, not a parallel one. */
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
`;

/**
 * Tempdoc 846 §2.4 — the fenced-code theme.
 *
 * `highlight.js` emits classes, never colours, which is the whole reason it was chosen (§2.4): the
 * palette below is the app's own `--text-*` role vocabulary, already defined by every shipped theme
 * (light, dark and both high-contrast palettes). No hex crosses into this file, no `--accent-*` is
 * used as a text colour, and no new colour token is minted for a scope that an existing role
 * already names.
 *
 * Every selector is class-scoped (`.hljs-*` under `.md-content`), so nothing here can reach an
 * element the prose variant owns — the containment proof's "no heading/table/hr/img selector on the
 * default path" holds by construction.
 *
 * Un-highlighted code (unknown language, highlighter not loaded, a load failure) matches NO rule
 * below and renders as the plain monospace it renders as today. That is the fallback: an absence,
 * not a branch.
 */
export const markdownCodeHighlight = css`
  /* Comments recede and lean — the one scope that should read as not-code. */
  .md-content .hljs-comment,
  .md-content .hljs-quote {
    color: var(--text-tertiary);
    font-style: italic;
  }
  .md-content .hljs-keyword,
  .md-content .hljs-selector-tag,
  .md-content .hljs-literal,
  .md-content .hljs-doctag,
  .md-content .hljs-name {
    color: var(--text-command);
  }
  .md-content .hljs-string,
  .md-content .hljs-regexp,
  .md-content .hljs-addition {
    color: var(--text-success);
  }
  .md-content .hljs-number,
  .md-content .hljs-symbol,
  .md-content .hljs-bullet,
  .md-content .hljs-link {
    color: var(--text-highlight);
  }
  .md-content .hljs-title,
  .md-content .hljs-section,
  .md-content .hljs-selector-id {
    color: var(--text-link);
  }
  .md-content .hljs-type,
  .md-content .hljs-built_in,
  .md-content .hljs-class,
  .md-content .hljs-params {
    color: var(--text-chat);
  }
  .md-content .hljs-attr,
  .md-content .hljs-attribute,
  .md-content .hljs-variable,
  .md-content .hljs-template-variable,
  .md-content .hljs-meta,
  .md-content .hljs-selector-attr,
  .md-content .hljs-selector-pseudo {
    color: var(--text-secondary);
  }
  .md-content .hljs-deletion {
    color: var(--text-danger);
  }
  .md-content .hljs-emphasis {
    font-style: italic;
  }
  .md-content .hljs-strong {
    font-weight: 600;
  }
`;
