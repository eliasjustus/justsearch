// SPDX-License-Identifier: Apache-2.0
/**
 * ToolCallCard — Lit block rendering one tool-call lifecycle stage.
 *
 * Originally extracted from AgentSurface in slice 491 G2; AgentSurface was
 * subsequently decomposed into AgentView + AgentSessionController in slice 495.
 * The parent view is now AgentView.
 *
 * Props:
 *   - `.toolCall: ToolCall` — the call to render
 *   - `.expanded: boolean` — collapsed/expanded toggle (reflected attribute)
 *   - `.evidencePaths: ReadonlySet<string> | null` — tempdoc 867: the run's evidence-path set, for a
 *     search card's level-2 body to say which of its own hits the run actually drew on. `null` means
 *     "the mount site did not wire this" (an older caller, or a test), distinct from an empty set
 *     ("wired, and nothing is in evidence yet") — see the accessory text below.
 *
 * Tempdoc 867 (L1+L2 slice) — ONE disclosure per card. The header row is the ENTIRE click/keyboard
 * target (no separate tiny expand button); RISK is no longer a header text badge — the glyph alone
 * carries status, and risk renders as its own row ONLY for MEDIUM/HIGH (border tint + the focusable
 * "why" disclosure are unaffected). A search card's body is no longer a nested `<jf-results-card>`
 * mount: it is a level-2 list of the hits that are actually IN the run's evidence set, plus a
 * "K more retrieved, not in evidence" footer and an "Open in Search" pill.
 *
 * Tempdoc 550 C3: the per-card Approve/Reject buttons were removed. A tool call that needs
 * a human decision now routes through the ONE unified ceremony host
 * (`<jf-authorization-host>`, driven by `AgentSessionController` via the authorization
 * broker) — the same approve/deny surface used for gated effect/emission dispatches. The
 * card just shows "awaiting approval" while the ceremony is open.
 *
 * Invariants:
 *   - `pending` status shows an "awaiting approval" hint (no inline buttons).
 *   - Risk row (MEDIUM/HIGH only) tints the card border and explains itself on demand.
 *   - Executing state is carried by the status glyph alone (no visible status word — the header's
 *     accessible name carries it for AT).
 *   - Output panel only renders for status=completed with non-empty output (non-search tools only).
 *   - Collapsed state hides args/output/search body/actions (toggle via the header).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
// Tempdoc 565 §17 — the ONE run-step node primitive; its `running` glyph is the unified alive-indicator.
import './RunNode.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import type { StepPresentation } from '../../views/runStepPresentation.js';
// 543-fwd #2 — derive a plain-text "why" line from risk + the current dial level.
import { becauseLine, getAutonomyLevel } from '../../substrates/autonomy/index.js';
import { agentSearchCardProjection, type AgentSearchCardProjection } from './toolSearchCard.js';
// Tempdoc 577 §2.14 Root III (#18) — the ONE tool-output text-provenance authority (non-search only —
// tempdoc 867 removed the search-specific "evidence-lineage" header alongside the raw JSON dump).
import { toolOutputLineage, lineageFrameLabel } from './toolOutputLineage.js';
// Tempdoc 577 Ext I — presentedToolStatus folds the result outcome into the presented status word,
// read into the header's accessible name (867 removed the visible, inline-coloured status word — the
// glyph carries status now, so `statusAccent` is no longer read here; the ONE status → tone →
// accent-token authority (`statusTone.ts`) still colours the nested `<jf-run-node>` glyph directly).
import { presentedToolStatus } from '../../utils/statusTone.js';
import { composeToolLabel } from '../../display/toolLabeling.js';

export type { ToolCall, ToolRisk, ToolCallStatus } from '../../controllers/AgentSessionController.js';

/** Tempdoc 867 — a stable empty-set identity, so a search body's join never allocates for "nothing". */
const EMPTY_EVIDENCE_PATHS: ReadonlySet<string> = new Set();

/**
 * Tempdoc 871 §1 ("level 2 — the summary, expanded in place... a footer counts the rest honestly") +
 * live finding (2026-08-26, PR #570): a search returning `10 results · 10 in evidence` rendered all
 * 10 rows at level 2, collapsing the "summary, not a list" design into a list again. Cap the rendered
 * evidence rows at this count; anything past it is counted in the footer, never rendered as a row.
 */
const EVIDENCE_ROW_CAP = 5;

/** `1 result` / `2 results` — the ONE pluralization for a hit count on this card. */
function countLabel(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

export class ToolCallCard extends JfElement {
  static properties = {
    toolCall: { attribute: false },
    stepPresentation: { attribute: false },
    evidencePaths: { attribute: false },
    expanded: { type: Boolean, reflect: true },
    riskWhyOpen: { state: true },
  };

  declare toolCall: ToolCall | null;
  /** Tempdoc 565 §17 — the ONE run-step presentation descriptor (glyph + tone), passed by the mount
   * site (UnifiedChatView.renderToolActivity) so the card shows the SAME glyph the spine/trace do. */
  declare stepPresentation: StepPresentation | null;
  /**
   * Tempdoc 867 — the run's evidence-path set, for a search card's level-2 join. `null` when the
   * mount site never wired this property (renders the accessory's " · M in evidence" segment absent
   * — an honest "we don't know", not a claimed zero — while the body still renders every hit as
   * "not (yet) in evidence" rather than fail closed on the whole card).
   */
  declare evidencePaths: ReadonlySet<string> | null;
  declare expanded: boolean;
  /** Tempdoc 577 §2.12 Move 4 — the risk-tier explanation disclosure is OPEN (keyboard-reachable;
   * the explanation is no longer hover-`title`-only — §2.11 #11). */
  declare riskWhyOpen: boolean;

  /** Tempdoc 565 §3.C — once the user toggles, stop auto-collapsing on status change. */
  private userToggled = false;

  constructor() {
    super();
    this.toolCall = null;
    this.stepPresentation = null;
    this.evidencePaths = null;
    this.expanded = true;
    this.riskWhyOpen = false;
  }

  /**
   * Tempdoc 565 §3.C / 867 — keep the run compact: a card whose call is still in flight (pending /
   * proposed / executing / approved) auto-expands and settles back to collapsed once it completes
   * successfully. A FAILED terminal call stays expanded (577 Ext I — collapsing it would hide the one
   * signal that distinguishes it from a success in a compacted run). A user toggle pins the choice —
   * the system never overrides it again for this card's lifetime.
   */
  override updated(changed: Map<string, unknown>): void {
    if (this.userToggled || !changed.has('toolCall')) return;
    const status = this.toolCall?.status;
    const active = status === 'pending' || status === 'proposed' || status === 'executing' || status === 'approved';
    const failed = status === 'completed' && this.toolCall?.success === false;
    const shouldExpand = active || failed;
    if (this.expanded !== shouldExpand) this.expanded = shouldExpand;
  }

  static styles = css`
    :host {
      display: block;
      width: 100%;
    }
    .tool-card {
      max-width: 90%;
      padding: 0.625rem 0.75rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
      width: 100%;
    }
    .tool-card.high-risk {
      border-color: var(--accent-danger-45);
    }
    .tool-card.medium-risk {
      border-color: var(--accent-warning-45);
    }
    /* Tempdoc 867 — ONE disclosure: the whole header row is the click/keyboard toggle. */
    .tool-card-header {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      cursor: pointer;
      border-radius: 0.25rem;
      outline: none;
    }
    .tool-card-header:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: 2px;
    }
    .tool-card-lead {
      display: flex;
      align-items: center;
      min-width: 0;
      overflow: hidden;
    }
    .tool-name {
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    /* Tempdoc 565 §12.3.B — the tool's target (query / filename), subdued after the label. */
    .tool-target {
      color: var(--text-secondary);
      font-weight: 400;
      margin-left: 0.4ch;
    }
    /* Tempdoc 867 — the muted right accessory (e.g. "3 results · 2 in evidence"). */
    .tool-card-accessory {
      margin-left: auto;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      white-space: nowrap;
      flex: none;
    }
    .expand-chevron {
      flex: none;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      transition: transform var(--duration-normal) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .expand-chevron { transition: none; }
    }
    /* Collapsed points along the reading direction (▶); expanded rotates it down (▼). The base
       glyph is ▶, so the EXPANDED state carries the rotation — live-caught 2026-08-26: the old
       rule (collapsed −90°) belonged to the retired ▼ base glyph and rendered collapsed as ▲. */
    :host([expanded]) .expand-chevron {
      transform: rotate(90deg);
    }
    /* Tempdoc 867 — the risk row: MEDIUM/HIGH only, below the header, unaffected by expand/collapse
       (it was always visible before too — only its position moved out of the header). */
    .risk-row {
      margin-top: 0.375rem;
    }
    .risk-word {
      cursor: help;
      text-decoration: underline dotted;
      text-underline-offset: 0.15em;
      background: none;
      border: none;
      padding: 0;
      margin: 0;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .risk-why {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-style: italic;
      margin: 0.25rem 0 0;
    }
    /* Tempdoc 565 §17 — the run-step glyph (the SAME <jf-run-node> the spine/trace show), sized inline. */
    .tool-status-glyph {
      display: inline-flex;
      width: 0.85rem;
      height: 0.85rem;
      flex: none;
      margin-right: 0.35rem;
    }
    /* Tempdoc 867 — the collapsible body, animated with the grid-template-rows 0fr/1fr technique so
       height animates without a measured pixel value. */
    .tool-card-body {
      display: grid;
      grid-template-rows: 1fr;
      transition: grid-template-rows var(--duration-normal) var(--ease-standard);
    }
    :host(:not([expanded])) .tool-card-body {
      grid-template-rows: 0fr;
    }
    @media (prefers-reduced-motion: reduce) {
      .tool-card-body { transition: none; }
    }
    .tool-card-body-inner {
      overflow: hidden;
      min-height: 0;
    }
    .tool-args {
      font-family: monospace;
      font-size: var(--font-size-xs);
      padding: 0.5rem;
      background: var(--surface-tertiary);
      border-radius: 0.25rem;
      max-height: 8rem;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      margin-top: 0.5rem;
    }
    .tool-output {
      font-family: monospace;
      font-size: var(--font-size-xs);
      padding: 0.5rem;
      background: var(--surface-tertiary);
      border-radius: 0.25rem;
      max-height: 12rem;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      margin-top: 0.5rem;
    }
    /* Tempdoc 577 §2.14 Root III (#18) — corpus-quoted output is framed as the user's documents
       quoted back (a left rule + a "Quoted from your documents" header), so citation/instruction-
       shaped text inside it reads as the documents' words, not the agent's own claim. */
    .tool-output.lineage-corpus-quoted {
      border-left: 3px solid var(--border-strong);
      background: var(--surface-2);
    }
    .lineage-frame-label {
      display: block;
      font-family: var(--font-display);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.35rem;
      font-style: italic;
    }
    /* Tempdoc 878 §D.4 — the model saw less of this output than you are reading. Sits UNDER the
       output, because it is a qualification of what is above it rather than a heading for it. */
    .tool-output-model-note {
      font-family: var(--font-display);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
    /* Tempdoc 867 — the search card's level-2 evidence body. */
    .tool-search-body {
      margin-top: 0.5rem;
    }
    .search-scope {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-bottom: 0.375rem;
    }
    .search-rows {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }
    .search-row-open {
      display: flex;
      align-items: baseline;
      gap: 0.4rem;
      width: 100%;
      text-align: left;
      background: none;
      border: none;
      padding: 0.1875rem 0.125rem;
      border-radius: 0.25rem;
      font: inherit;
      font-size: var(--font-size-xs);
      color: inherit;
      cursor: pointer;
      min-width: 0;
    }
    .search-row-open:hover,
    .search-row-open:focus-visible {
      background: var(--surface-tertiary);
    }
    .search-row-open:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: -2px;
    }
    .search-row-dot {
      flex: none;
      align-self: center;
      width: 0.3rem;
      height: 0.3rem;
      border-radius: 50%;
      background: currentColor;
    }
    .search-row-filename {
      font-weight: 500;
      flex: none;
    }
    .search-row-path {
      color: var(--text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      min-width: 0;
    }
    .search-row-locator {
      color: var(--text-secondary);
      font-style: italic;
      flex: none;
      margin-left: auto;
    }
    .search-more {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-top: 0.375rem;
    }
    .search-open-in-search {
      margin-top: 0.5rem;
      padding: 0.25rem 0.75rem;
      border-radius: 999px;
      border: 1px solid var(--border-subtle);
      background: var(--surface-tertiary);
      color: var(--text-primary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .search-open-in-search:hover {
      background: var(--surface-2);
    }
    .tool-rich-content {
      margin-top: 0.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .tool-image {
      min-width: 48px;
      max-width: 100%;
      max-height: 16rem;
      border-radius: 0.25rem;
      align-self: flex-start;
      /* Tiny images (e.g. an MCP icon) stay visible when upscaled to the min size. */
      image-rendering: pixelated;
    }
    .tool-resource a {
      font-size: var(--font-size-xs);
      color: var(--accent);
      word-break: break-all;
    }
    .rejected-reason {
      color: var(--text-warning);
      font-size: var(--font-size-xs);
      margin-top: 0.5rem;
    }
    /* 543-fwd #2 — deterministic "because" line explaining the gate decision. */
    .because {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-top: 0.5rem;
      font-style: italic;
    }
    /* dot-pulse animation moved to <jf-pulse-dots> shared component */
  `;

  private toggleExpanded(): void {
    this.userToggled = true;
    this.expanded = !this.expanded;
  }

  private onHeaderKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault();
      this.toggleExpanded();
    }
  }

  override render(): TemplateResult {
    const tc = this.toolCall;
    if (!tc) return html``;
    const isPending = tc.status === 'pending';
    const isCompleted = tc.status === 'completed';
    const isRejected = tc.status === 'rejected';
    const riskClass =
      tc.risk === 'HIGH' ? 'high-risk' : tc.risk === 'MEDIUM' ? 'medium-risk' : '';
    const showRisk = tc.risk === 'HIGH' || tc.risk === 'MEDIUM';
    const { label, target } = composeToolLabel(tc.toolName, tc.arguments);
    // Tempdoc 867 — the search projection now carries the level-2 body's own data (hits joined
    // against the run's evidence set); null when there is no evidence (or, the honest old-record
    // edge case, no derivable query) so the raw-output path below renders instead of a fabrication.
    const searchProjection = isCompleted
      ? agentSearchCardProjection(tc.structuredData, tc.arguments, this.evidencePaths ?? EMPTY_EVIDENCE_PATHS)
      : null;
    const accessory = searchProjection ? this.searchAccessory(searchProjection) : '';
    const presentedStatus = presentedToolStatus(tc.status, tc.success);
    const headerLabel = `${this.expanded ? 'Collapse' : 'Expand'} ${label}${target ? ` ${target}` : ''} — ${presentedStatus}`;
    return html`
      <div class="tool-card ${riskClass}">
        <div
          class="tool-card-header"
          role="button"
          tabindex="0"
          aria-expanded=${this.expanded ? 'true' : 'false'}
          aria-label=${headerLabel}
          data-testid="tool-card-header"
          @click=${() => this.toggleExpanded()}
          @keydown=${(e: KeyboardEvent) => this.onHeaderKeydown(e)}
        >
          <span class="tool-card-lead">
            ${this.stepPresentation
              ? html`<jf-run-node
                  class="tool-status-glyph"
                  density="full"
                  .presentation=${this.stepPresentation}
                ></jf-run-node>`
              : nothing}
            <span class="tool-name"
              >${label}${target ? html`<span class="tool-target">${target}</span>` : nothing}</span
            >
          </span>
          ${accessory
            ? html`<span class="tool-card-accessory" data-testid="tool-card-accessory">${accessory}</span>`
            : nothing}
          <span class="expand-chevron" aria-hidden="true">▶</span>
        </div>
        ${showRisk ? this.renderRiskRow(tc) : nothing}
        <div class="tool-card-body">
          <div class="tool-card-body-inner">
            ${tc.arguments && !searchProjection
              ? html`<div class="tool-args">${tc.arguments}</div>`
              : nothing}
            ${/* Tempdoc 577 §2.14 Root III (#18): frame raw tool output by its backend-stamped lineage
                — corpus-quoted text is the user's documents quoted back. A search card's evidence is
                framed by the level-2 body itself (867), not this raw-output panel. */ ''}
            ${isCompleted && tc.output && !searchProjection
              ? this.renderLineageFramedOutput(tc)
              : nothing}
            ${searchProjection ? this.renderSearchBody(searchProjection) : nothing}
            ${/* Tempdoc 878 §D.4 — the model-visibility note sits OUTSIDE the two body branches,
                because it is a fact about the tool RESULT and not about the raw-output panel. Riding
                it on the lineage frame left the search card — the bulkiest output, and so the one
                most likely to be cut — the only card that could be truncated silently. */ ''}
            ${isCompleted ? this.renderModelVisibilityNote(tc) : nothing}
            ${isCompleted ? this.renderRichContent(tc) : nothing}
            ${isRejected
              ? html`<div class="rejected-reason">
                  Rejected${tc.rejectReason ? html`: ${tc.rejectReason}` : nothing}
                </div>`
              : nothing}
            ${isPending
              ? html`
                  <div class="because" data-testid="tool-call-because">
                    ${/* Tempdoc 561 P-D1: explain the BACKEND gate verdict when present. */ ''}
                    ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}
                  </div>
                  <div class="rejected-reason" data-testid="awaiting-approval">
                    Awaiting your approval…
                  </div>
                `
              : nothing}
          </div>
        </div>
      </div>
    `;
  }

  /** Tempdoc 867 — "N results · M in evidence"; the evidence segment omits when the run's evidence
   * set was never wired to this card (an honest "we don't know", not a claimed zero). */
  private searchAccessory(projection: AgentSearchCardProjection): string {
    const results = countLabel(projection.resultCount, 'result');
    if (this.evidencePaths === null) return results;
    return `${results} · ${projection.evidenceCount} in evidence`;
  }

  /**
   * Tempdoc 867 §2a — "roots · pipeline preset actually used" (type filters are dropped: not a
   * per-call fact). `mode` is '' on a record persisted before the 867 backend stamp — the named gap
   * — so the line renders only what this call's record actually carries, never a guessed preset.
   */
  private searchScopeLine(projection: AgentSearchCardProjection): string {
    const base = `${countLabel(projection.resultCount, 'result')} for "${projection.query}"`;
    const scoped = projection.scope ? `${base} in ${projection.scope}` : base;
    return projection.mode ? `${scoped} · ${projection.mode}` : scoped;
  }

  /**
   * Tempdoc 867 — the risk row: MEDIUM/HIGH only, a focusable disclosure explaining the tier via the
   * SAME `becauseLine` authority, reachable by keyboard/AT (§2.11 #11 / §2.12 Move 4).
   */
  private renderRiskRow(tc: ToolCall): TemplateResult {
    return html`<div class="risk-row">
      <button
        type="button"
        class="risk-word"
        aria-expanded=${this.riskWhyOpen ? 'true' : 'false'}
        aria-label=${`Risk tier ${tc.risk}. ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}`}
        @click=${() => {
          this.riskWhyOpen = !this.riskWhyOpen;
        }}
        >${tc.risk}</button
      >
      ${this.riskWhyOpen
        ? html`<div class="risk-why" role="note">
            ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}
          </div>`
        : nothing}
    </div>`;
  }

  /**
   * Tempdoc 867 — the search tool card's level-2 body: a muted scope line (roots · pipeline preset
   * actually used — type filters are dropped per §2a, not a per-call fact), then ONLY the hits that
   * are in the run's evidence set (dot · filename · dim path · locator), capped at
   * {@link EVIDENCE_ROW_CAP} (871 §1 + the live finding above the constant's declaration), a footer
   * honestly counting what the cap hid, and an "Open in Search" pill (852 owns the actual navigation —
   * this only dispatches the intent).
   *
   * The footer is composed from the projection's OWN two counts, not from the rendered row list, so
   * it never mixes "hidden by the cap" with "never retrieved as evidence" into one misleading number:
   *   - hidden-in-evidence = max(0, evidenceCount − EVIDENCE_ROW_CAP) — "N more in evidence"
   *   - retrieved-not-in-evidence = resultCount − evidenceCount — "N more retrieved, not in evidence"
   * Each segment renders only when > 0, joined with " · " when both are present.
   *
   * The per-row open path is UNCHANGED in contract: clicking a row fires the same `card-open`
   * CustomEvent (bubbles, composed, `{id}`) the old nested `<jf-results-card>` excerpt rows fired, so
   * the mount site's existing `findAgentSearchHit(structuredData, id)` resolution keeps working.
   */
  private renderSearchBody(projection: AgentSearchCardProjection): TemplateResult {
    const rows = projection.hits.filter((h) => h.inEvidence).slice(0, EVIDENCE_ROW_CAP);
    const hiddenInEvidence = Math.max(0, projection.evidenceCount - EVIDENCE_ROW_CAP);
    const retrievedNotInEvidence = projection.resultCount - projection.evidenceCount;
    const footerSegments = [
      hiddenInEvidence > 0 ? `${hiddenInEvidence} more in evidence` : null,
      retrievedNotInEvidence > 0 ? `${retrievedNotInEvidence} more retrieved, not in evidence` : null,
    ].filter((s): s is string => s !== null);
    const footerText = footerSegments.join(' · ');
    const scopeText = this.searchScopeLine(projection);
    return html`<div class="tool-search-body" data-testid="tool-search-body">
      <div class="search-scope" data-testid="tool-search-scope">${scopeText}</div>
      ${rows.length > 0
        ? html`<ul class="search-rows" data-testid="tool-search-rows">
            ${rows.map(
              (hit) => html`<li>
                <button
                  type="button"
                  class="search-row-open"
                  data-testid="tool-search-row"
                  @click=${() => this.openSearchHit(hit.id)}
                >
                  <span class="search-row-dot" aria-hidden="true"></span>
                  <span class="search-row-filename">${hit.title}</span>
                  <span class="search-row-path">${hit.path}</span>
                  ${hit.locator
                    ? html`<span class="search-row-locator">${hit.locator}</span>`
                    : nothing}
                </button>
              </li>`,
            )}
          </ul>`
        : nothing}
      ${footerText
        ? html`<div class="search-more" data-testid="tool-search-more">${footerText}</div>`
        : nothing}
      <button
        type="button"
        class="search-open-in-search"
        data-testid="tool-search-open-in-search"
        @click=${() => this.openInSearch(projection.query, projection.scope)}
      >
        Open in Search
      </button>
    </div>`;
  }

  /** Tempdoc 867 — preserves the existing `card-open` contract (bubbles + composed, `{id}`). */
  private openSearchHit(id: string): void {
    this.dispatchEvent(
      new CustomEvent('card-open', { detail: { id }, bubbles: true, composed: true }),
    );
  }

  /** Tempdoc 867 — the "Open in Search" intent. No navigation wired here; 852 owns the landing. */
  private openInSearch(query: string, scope: string): void {
    this.dispatchEvent(
      new CustomEvent('tool-card-open-search', {
        detail: { query, scope },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private renderLineageFramedOutput(tc: ToolCall): TemplateResult {
    const lineage = toolOutputLineage(tc.structuredData);
    const label = lineageFrameLabel(lineage);
    // Runtime output (the default) renders byte-identically to before — no frame, no extra nodes.
    // Only a backend-stamped corpus-quoted excerpt gets the quoting frame + header.
    const body =
      label === null
        ? html`<div class="tool-output lineage-${lineage}" data-lineage=${lineage}>${tc.output}</div>`
        : html`<div class="tool-output lineage-${lineage}" data-lineage=${lineage}><span
              class="lineage-frame-label"
              data-testid="tool-output-lineage"
              >${label}</span
            >${tc.output}</div>`;
    return body;
  }

  /**
   * Tempdoc 878 §D.4 — say when the MODEL received less of this output than the reader is seeing.
   *
   * The panel above shows what the tool returned; the agent loop appends a truncated copy to the
   * prompt. Both are honest answers to "what was the output", and until the backend labelled them
   * the card silently gave the first while the agent worked from the second — so a reader debugging
   * a wrong answer was looking at evidence the model never had.
   *
   * Renders NOTHING in two distinct cases, and the distinction is the point: the model got all of it
   * (nothing to disclose), or nobody measured (`outputCharsToModel` absent — say nothing rather than
   * imply completeness). Records written before the backend carried this field fall in the second.
   */
  private renderModelVisibilityNote(tc: ToolCall): TemplateResult | typeof nothing {
    if (tc.truncatedForModel !== true || tc.outputCharsToModel === undefined) return nothing;
    const total = (tc.output ?? '').length;
    // 'en-US' explicitly, not the host locale: the shell ships English copy, and a German host would
    // otherwise render "4.000" — which reads as four, not four thousand, in the sentence around it.
    // Same reasoning the backend applies to its own grouped numbers.
    const grouped = (n: number): string => n.toLocaleString('en-US');
    return html`<div class="tool-output-model-note" data-testid="tool-output-model-note">
      The model received the first ${grouped(tc.outputCharsToModel)} of ${grouped(total)} characters
      of this output.
    </div>`;
  }

  /**
   * Tempdoc 560 Phase 1 — render non-text result blocks (e.g. an MCP tool's image / embedded
   * resource) carried in {@code structuredData.mcpContent}. Text blocks already render via `output`;
   * here we surface images (base64 data URI) and resource links so multimodal tool results are not
   * silently dropped.
   */
  private renderRichContent(tc: ToolCall): TemplateResult | typeof nothing {
    const content = tc.structuredData?.['mcpContent'];
    if (!Array.isArray(content)) return nothing;
    const rich = (content as Array<Record<string, unknown>>).filter((b) => b['type'] !== 'text');
    if (rich.length === 0) return nothing;
    return html`<div class="tool-rich-content" data-testid="tool-rich-content">
      ${rich.map((b) => {
        const type = b['type'] as string;
        if (type === 'image' && typeof b['data'] === 'string') {
          const mime = (b['mimeType'] as string) || 'image/png';
          return html`<img
            class="tool-image"
            alt="MCP tool image result"
            src=${`data:${mime};base64,${b['data'] as string}`}
          />`;
        }
        if (type === 'resource' && typeof b['uri'] === 'string') {
          return html`<div class="tool-resource">
            <a href=${b['uri'] as string} target="_blank" rel="noopener">${b['uri']}</a>
          </div>`;
        }
        return nothing;
      })}
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-tool-call-card')) {
  customElements.define('jf-tool-call-card', ToolCallCard);
}
