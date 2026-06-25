// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C3) — `<jf-citations-panel>` Lit block.
 *
 * Renders RAG citations from the `rag.citation_matches` SSE event payload
 * (see `core.rag-ask` shape's eventSchema). Each citation card shows the
 * matched sentence, the parent document ref, similarity score, and (when
 * present) excerpt text.
 *
 * Consumed by AskView (C3). The view passes the parsed payload through; this
 * block has no fetch / side-effect concerns of its own.
 *
 * Per §9.F Q1 (hybrid) + slice 486 G140: location-metadata + click-to-verify
 * affordances land in a follow-up polish phase. V1 ships the minimum
 * structural rendering (sentence + score + parent doc).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import '../Control.js';
import { setMenuAnchor } from '../../utils/selectionAnchor.js';
// Tempdoc 565 §29 Tier-3 — open the cited LOCAL file (the uniquely-local citation affordance).
import { openLocalFile } from '../../plugin-api/capabilities/platform.js';
import {
  toEvidenceItem,
  evidenceScore,
  tierGroup,
  tierClass,
  filenameOf,
  sourceGrounding,
  sourceGroundingLabel,
  type EvidenceScore,
  type SourceGrounding,
} from './evidenceProjection.js';
import type {
  CitationMatch,
  RetrievalCitation,
  CitationSelectDetail,
} from './citationTypes.js';

// The pure data shapes moved to `citationTypes.ts` (cycle break,
// tempdoc 530 UI-cycle gate). Re-exported here so existing importers of
// `./CitationsPanel.js` keep working unchanged.
export type {
  CitationMatch,
  RetrievalCitation,
  CitationSelectDetail,
} from './citationTypes.js';

export class CitationsPanel extends JfElement {
  static properties = {
    citations: { type: Array, attribute: false },
    sources: { type: Array, attribute: false },
    retrievalMode: { type: String, attribute: false },
    showWeak: { state: true },
    sourcesExpanded: { state: true },
  };

  declare citations: CitationMatch[];
  declare sources: RetrievalCitation[];
  declare retrievalMode: string;
  declare showWeak: boolean;
  // Tempdoc 559 Authority IV (C-1): sources are disclosed on demand, not
  // expanded by default — a short answer stays short (the ReasoningBlock
  // disclosure pattern). The header is the toggle; the body renders only when open.
  declare sourcesExpanded: boolean;

  constructor() {
    super();
    this.citations = [];
    this.sources = [];
    this.retrievalMode = '';
    this.showWeak = false;
    this.sourcesExpanded = false;
  }

  static styles = css`
    :host {
      display: block;
      margin: 0.5rem 0;
    }
    .panel-header {
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      padding: 0.25rem 0 0.5rem;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      /* 559 C-1: disclosure toggle — reset native button chrome, keep the look. */
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      background: none;
      border: none;
      font-family: inherit;
      cursor: pointer;
    }
    .panel-header:hover {
      color: var(--text-primary);
    }
    .disclosure-chevron {
      display: inline-block;
      transition: transform var(--duration-fast) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .disclosure-chevron { transition: none; }
    }
    .disclosure-chevron.open {
      transform: rotate(90deg);
    }
    .citations {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .citation,
    .source {
      padding: 0.5rem 0.75rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    button.source {
      cursor: pointer;
      transition: background var(--duration-fast), border-color var(--duration-fast);
      text-align: left;
      width: 100%;
    }
    /* Tempdoc 565 §29 Tier-3 — the cited-file card wraps the source button + an open-file affordance. */
    .source-card {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .source-open {
      align-self: flex-start;
      cursor: pointer;
      background: none;
      border: none;
      padding: 0.1rem 0.25rem;
      color: var(--text-command);
      font-size: var(--font-size-xs);
      text-align: left;
    }
    .source-open:hover {
      text-decoration: underline;
    }
    .source:hover {
      background: var(--surface-3);
      border-color: var(--accent-tint);
    }
    .source .preview {
      display: none;
      margin-top: 0.4rem;
      padding-top: 0.4rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
      line-height: 1.4;
    }
    .source:hover .preview,
    .source:focus-within .preview {
      display: block;
    }
    .preview .detail {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.2rem;
    }
    .citation .header {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.25rem;
    }
    .doc-ref {
      font-family: ui-monospace, monospace;
    }
    .score {
      font-weight: 500;
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
    }
    .confidence-bar {
      display: inline-block;
      width: 2.5rem;
      height: 0.375rem;
      border-radius: 0.1875rem;
      background: var(--surface-tertiary);
      overflow: hidden;
    }
    .confidence-bar-fill {
      height: 100%;
      border-radius: 0.1875rem;
      transition: width var(--duration-normal);
    }
    .score.high { color: var(--text-tint); }
    .score.medium { color: var(--text-secondary); }
    .score.low { color: var(--text-warning); }
    /* Tempdoc 603 C1 — the per-source GROUNDING (faithfulness) badge. Cited → tier colour; uncited → muted
       (never the alarming warning colour — "retrieved, not cited" is neutral, not a fault). */
    .grounding { font-size: var(--font-size-xs); font-weight: 500; white-space: nowrap; }
    .grounding.high { color: var(--text-tint); }
    .grounding.medium { color: var(--text-secondary); }
    .grounding.uncited { color: var(--text-secondary); font-style: italic; }
    /* 559 Authority IV — the declared metric label (the "%" is no longer bare). */
    .score-metric {
      margin-left: 0.3rem;
      font-weight: 400;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }
    .confidence-bar {
      width: 3rem;
      height: 4px;
      border-radius: 2px;
      background: var(--border-subtle);
      overflow: hidden;
    }
    .confidence-bar .fill {
      height: 100%;
      border-radius: 2px;
      transition: width var(--duration-normal);
    }
    .confidence-bar .fill.high { background: var(--accent-tint); }
    .confidence-bar .fill.medium { background: var(--text-secondary); }
    .confidence-bar .fill.low { background: var(--accent-warning); }
    .sentence {
      line-height: 1.4;
    }
    .excerpt {
      margin-top: 0.35rem;
      font-style: italic;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .heading-breadcrumb {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .tier-header {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--text-secondary);
      padding: 0.5rem 0 0.25rem;
    }
    .doc-group-label {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      font-family: ui-monospace, monospace;
      padding: 0.25rem 0 0.15rem;
    }
    jf-control.weak-toggle { display: block; }
    jf-control.weak-toggle::part(control) {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.25rem 0;
    }
    jf-control.weak-toggle::part(control):hover { color: var(--text-primary); }
  `;

  private onSourceClick(source: RetrievalCitation, e: MouseEvent): void {
    // Tempdoc 526 §17 T1A — publish the citation button's bounding rect to
    // the F9 menu anchor register BEFORE dispatching the event. The menu
    // subscribes to both selectionState and selectionAnchor; the citation
    // SelectionItem will be published shortly after by Shell.onCitationSelect.
    const target = (e.currentTarget as HTMLElement | null)
      ?? ((e.target as HTMLElement | null)?.closest('.source') as HTMLElement | null);
    if (target) {
      const rect = target.getBoundingClientRect();
      if (rect.width > 0 || rect.height > 0) {
        setMenuAnchor({ top: rect.top, left: rect.left, bottom: rect.bottom, right: rect.right });
      }
    }
    const detail: CitationSelectDetail = {
      parentDocId: source.parentDocId,
      startLine: source.startLine,
      endLine: source.endLine,
      startChar: source.startChar,
      endChar: source.endChar,
      // Tempdoc 526 §14.5 T2 — excerpt rides through for G21 kind-flip.
      excerpt: source.excerpt ?? '',
    };
    this.dispatchEvent(
      new CustomEvent('citation-select', {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** 559 Authority IV — the score renders as a LABELED metric, not a bare "%". */
  private renderScore(score: EvidenceScore): TemplateResult {
    return html`<div class="confidence-bar">
        <div class="fill ${tierClass(score.tier)}" style="width:${score.pct}%"></div>
      </div>
      <span class="score ${tierClass(score.tier)}" aria-label="${score.label}: ${score.pct}%">
        ${score.pct}%<span class="score-metric">${score.label}</span>
      </span>`;
  }

  /**
   * Tempdoc 603 C1 — the per-source TRUST badge is GROUNDING (faithfulness), not the BM25 retrieval score:
   * "Grounds N sentences" for a cited source, the honest "Retrieved · not cited" for a retrieved-but-unused
   * one (muted, never "high confidence"). When `grounding` is null (no citation-matches available — the
   * matcher didn't run / keyword-only) the card shows NO trust claim at all (the §22/U2 degraded fallback).
   */
  private renderGrounding(g: SourceGrounding): TemplateResult {
    const cls = g.cited ? `grounding ${tierClass(g.tier)}` : 'grounding uncited';
    return html`<span class="${cls}" aria-label=${sourceGroundingLabel(g)}>${sourceGroundingLabel(g)}</span>`;
  }

  private renderSourceCard(s: RetrievalCitation, grounding?: SourceGrounding | null): TemplateResult {
    // 559 Authority IV — render the citation card as a typed projection of the
    // evidence record, not ad-hoc field reads.
    const item = toEvidenceItem(s);
    // Tempdoc 565 §29 Tier-3 — the cited file's basename, for the open-file affordance below the card.
    const docName = s.parentDocId.split('/').pop() ?? s.parentDocId;
    return html`
      <div class="source-card">
        <button
          class="source"
          @click=${(e: MouseEvent) => this.onSourceClick(s, e)}
        >
          <div class="header">
            ${grounding ? this.renderGrounding(grounding) : nothing}
            ${item.headingText
              ? html`<span class="heading-breadcrumb">${item.headingText}</span>`
              : nothing}
          </div>
          ${item.excerpt
            ? html`<div class="sentence">${item.excerpt}</div>`
            : nothing}
          <div class="preview">
            ${item.excerpt
              ? html`<div>${item.excerpt}</div>`
              : html`<div>(no excerpt)</div>`}
            ${item.headingText
              ? html`<div class="detail">Section: ${item.headingText}</div>`
              : nothing}
          </div>
        </button>
        <button
          class="source-open"
          data-open-file
          title="Open ${docName}"
          aria-label="Open file ${docName}"
          @click=${() => void openLocalFile(s.parentDocId)}
        >
          Open ${docName}
        </button>
      </div>
    `;
  }

  override render(): TemplateResult {
    const hasSources = this.sources && this.sources.length > 0;
    const hasCitations = this.citations && this.citations.length > 0;
    if (!hasSources && !hasCitations) return html``;

    if (hasSources) {
      // 603 §22/U2 — without citation-matches (matcher didn't run / keyword-only) there is no faithfulness
      // signal, so render sources NEUTRALLY (flat, no trust grade) rather than grading by BM25 or marking
      // every source "not cited". With matches, group by grounding (renderTieredSources).
      return this.retrievalMode === 'FULLTEXT_FALLBACK' || !hasCitations
        ? this.renderFlatSources()
        : this.renderTieredSources();
    }

    // Fallback: citation-match-only rendering (no retrieval-time sources)
    return html`
      <div class="panel-header">
        ${this.citations.length}
        ${this.citations.length === 1 ? 'citation' : 'citations'}
      </div>
      <div class="citations">
        ${this.citations.map(
          (c) => html`
            <div class="citation">
              <div class="header">${this.renderScore(evidenceScore(c.similarity))}</div>
              <div class="sentence">${c.sentenceText}</div>
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * Flat, NEUTRAL source list — no trust grade. Used for FULLTEXT_FALLBACK and (603 §22/U2) when no
   * citation-matches are available (the faithfulness matcher didn't run), so we never assert grounding we
   * don't have nor fall back to the misleading BM25 "confidence". `renderSourceCard(s, null)` shows no badge.
   */
  private renderFlatSources(): TemplateResult {
    return html`
      <div class="panel-header">
        ${this.sources.length}
        ${this.sources.length === 1 ? 'source retrieved' : 'sources retrieved'}
      </div>
      <div class="citations">
        ${this.sources.map((s) => this.renderSourceCard(s, null))}
      </div>
    `;
  }

  private renderTieredSources(): TemplateResult {
    // Tempdoc 603 C1 — group sources by their GROUNDING (faithfulness) tier, joined from the answer's
    // per-sentence citation-matches (sourceGrounding), NOT the BM25 retrieval score. So a source that
    // actually grounds the answer ranks high and a retrieved-but-uncited one is demoted into the
    // collapsed "retrieved · not cited" slot — the panel agrees with the inline citations + the banner
    // (the §1 mis-calibration). The tier still comes from the ONE evidenceTier authority (groundingSemantics).
    // 603 PART X.B — grounding joins by the source's ARRAY POSITION in this.sources (the established
    // convention the inline marks use), NOT a doc-ordinal compare. Compute once per (index, source) and
    // carry the result to the card render so grouping and the badge agree (and we don't re-join twice).
    const gOf = new Map<RetrievalCitation, SourceGrounding>(
      this.sources.map((s, i) => [s, sourceGrounding(i, this.citations, s.parentDocId)]),
    );
    const groups: Record<'high' | 'supporting' | 'weak', RetrievalCitation[]> = {
      high: [],
      supporting: [],
      weak: [],
    };
    for (const s of this.sources) {
      groups[tierGroup(gOf.get(s)!.tier)].push(s);
    }
    const { high, supporting, weak } = groups;

    const groupByDoc = (items: RetrievalCitation[]) => {
      const groups = new Map<string, RetrievalCitation[]>();
      for (const s of items) {
        const key = s.parentDocId;
        const list = groups.get(key) ?? [];
        list.push(s);
        groups.set(key, list);
      }
      return groups;
    };

    const renderGroup = (items: RetrievalCitation[]) => {
      const groups = groupByDoc(items);
      return html`${Array.from(groups.entries()).map(
        ([docId, sources]) => html`
          <div class="doc-group-label">${filenameOf(docId)}</div>
          ${sources.map((s) => this.renderSourceCard(s, gOf.get(s)))}
        `,
      )}`;
    };

    return html`
      <button
        class="panel-header"
        aria-expanded=${this.sourcesExpanded ? 'true' : 'false'}
        aria-controls="citations-body"
        @click=${() => (this.sourcesExpanded = !this.sourcesExpanded)}
      >
        <span class="disclosure-chevron ${this.sourcesExpanded ? 'open' : ''}">▸</span>
        ${this.sources.length}
        ${this.sources.length === 1 ? 'source' : 'sources'}
      </button>
      ${this.sourcesExpanded
        ? html`<div class="citations" id="citations-body">
        ${high.length > 0
          ? html`
              <div class="tier-header">Grounds the answer</div>
              ${renderGroup(high)}
            `
          : nothing}
        ${supporting.length > 0
          ? html`
              <div class="tier-header">Supporting</div>
              ${renderGroup(supporting)}
            `
          : nothing}
        ${weak.length > 0
          ? html`
              <jf-control
                class="weak-toggle"
                label=${this.showWeak
                  ? 'Hide retrieved (not cited)'
                  : `Show ${weak.length} retrieved (not cited)`}
                .onActivate=${() => (this.showWeak = !this.showWeak)}
              >
                ${this.showWeak
                  ? 'Hide'
                  : `${weak.length} retrieved (not cited)`}
              </jf-control>
              ${this.showWeak ? renderGroup(weak) : nothing}
            `
          : nothing}
      </div>`
        : nothing}
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-citations-panel')
) {
  customElements.define('jf-citations-panel', CitationsPanel);
}
