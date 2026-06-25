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
 *
 * Tempdoc 550 C3: the per-card Approve/Reject buttons were removed. A tool call that needs
 * a human decision now routes through the ONE unified ceremony host
 * (`<jf-authorization-host>`, driven by `AgentSessionController` via the authorization
 * broker) — the same approve/deny surface used for gated effect/emission dispatches. The
 * card just shows "awaiting approval" while the ceremony is open.
 *
 * Invariants:
 *   - `pending` status shows an "awaiting approval" hint (no inline buttons).
 *   - Risk badges (HIGH/MEDIUM) tint the card border.
 *   - Executing state shows animated pulse dots next to the status text.
 *   - Output panel only renders for status=completed with non-empty output.
 *   - Collapsed state hides args/output/actions (toggle via expand button).
 */

import { html, css, unsafeCSS, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
// Tempdoc 565 §17 — the ONE run-step node primitive; its `running` glyph is the unified alive-indicator
// (it replaces the card's bespoke <jf-pulse-dots>, so a running step looks the same here as on the spine).
import './RunNode.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import type { StepPresentation } from '../../views/runStepPresentation.js';
// 543-fwd #2 — derive a plain-text "why" line from risk + the current dial level.
import { becauseLine, getAutonomyLevel } from '../../substrates/autonomy/index.js';
// Tempdoc 561 #6 — the ONE search-evidence projection (shared with the record render).
import { renderSearchEvidence, hasSearchEvidence, SEARCH_EVIDENCE_CSS } from './searchEvidence.js';
// Tempdoc 577 §2.14 Root III (#18) — the ONE tool-output text-provenance authority.
import { toolOutputLineage, lineageFrameLabel } from './toolOutputLineage.js';
// Tempdoc 565 §3.B — the ONE status → semantic-tone → accent-token authority (status-word colour).
// Tempdoc 577 Ext I — presentedToolStatus folds the result outcome into the presented status word.
import { statusAccent, presentedToolStatus } from '../../utils/statusTone.js';
import { composeToolLabel } from '../../display/toolLabeling.js';

export type { ToolCall, ToolRisk, ToolCallStatus } from '../../controllers/AgentSessionController.js';

export class ToolCallCard extends JfElement {
  static properties = {
    toolCall: { attribute: false },
    stepPresentation: { attribute: false },
    expanded: { type: Boolean, reflect: true },
    riskWhyOpen: { state: true },
  };

  declare toolCall: ToolCall | null;
  /** Tempdoc 565 §17 — the ONE run-step presentation descriptor (glyph + tone), passed by the mount
   * site (UnifiedChatView.renderToolActivity) so the card shows the SAME glyph the spine/trace do. */
  declare stepPresentation: StepPresentation | null;
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
    this.expanded = true;
    this.riskWhyOpen = false;
  }

  /**
   * Tempdoc 565 §3.C — keep the run compact: terminal cards (completed/rejected) auto-collapse to a
   * one-line row, while active cards (pending/proposed/executing/approved) stay expanded so the
   * args + the approval hint are visible. A manual toggle pins the choice.
   */
  override updated(changed: Map<string, unknown>): void {
    if (this.userToggled || !changed.has('toolCall')) return;
    const status = this.toolCall?.status;
    const active = status === 'pending' || status === 'proposed' || status === 'executing' || status === 'approved';
    // Tempdoc 577 Ext I — a FAILED terminal call stays expanded: collapsing it would hide the one
    // signal (the error output) that distinguishes it from a success in a compacted run.
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
    .tool-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .tool-name {
      font-weight: 600;
    }
    /* Tempdoc 565 §12.3.B — the tool's target (query / filename), subdued after the label. */
    .tool-target {
      color: var(--text-secondary);
      font-weight: 400;
      margin-left: 0.4ch;
    }
    .tool-status {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    /* Tempdoc 565 §3.B — the status word carries the semantic tone (statusTone authority). */
    .status-word {
      font-weight: 600;
    }
    /* Tempdoc 577 Ext II — the risk tier explains itself on hover (becauseLine tooltip). */
    /* Tempdoc 577 Move 4 — the risk tier is a FOCUSABLE disclosure button (keyboard/AT reachable),
       styled inline so it reads as a chip, not a heavy button. */
    .risk-word {
      cursor: help;
      text-decoration: underline dotted;
      text-underline-offset: 0.15em;
      background: none;
      border: none;
      padding: 0;
      margin: 0;
      font: inherit;
      color: inherit;
      letter-spacing: inherit;
    }
    .risk-why {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-style: italic;
      margin: 0.25rem 0;
    }
    /* Tempdoc 565 §17 — the run-step glyph (the SAME <jf-run-node> the spine/trace show), sized inline. */
    .tool-status-glyph {
      display: inline-flex;
      width: 0.85rem;
      height: 0.85rem;
      vertical-align: -0.15em;
      margin-right: 0.25rem;
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
    /* Tempdoc 561 #6 — structured search evidence (shared with the record render). */
    ${unsafeCSS(SEARCH_EVIDENCE_CSS)}
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
    .tool-actions {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }
    .tool-actions button {
      padding: 0.375rem 0.875rem;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .tool-actions button.primary {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-color: var(--accent-tint);
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
    .expand-toggle {
      cursor: pointer;
      background: none;
      border: none;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      padding: 0;
      margin-left: 0.375rem;
      transition: transform var(--duration-normal) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .expand-toggle { transition: none; }
    }
    :host(:not([expanded])) .expand-toggle {
      transform: rotate(-90deg);
    }
    :host(:not([expanded])) .tool-args,
    :host(:not([expanded])) .tool-output,
    :host(:not([expanded])) .tool-evidence,
    :host(:not([expanded])) .tool-actions,
    :host(:not([expanded])) .because,
    :host(:not([expanded])) .rejected-reason {
      display: none;
    }
    /* dot-pulse animation moved to <jf-pulse-dots> shared component */
  `;

  private toggleExpanded(): void {
    this.userToggled = true;
    this.expanded = !this.expanded;
  }

  override render(): TemplateResult {
    const tc = this.toolCall;
    if (!tc) return html``;
    const isPending = tc.status === 'pending';
    const isCompleted = tc.status === 'completed';
    const isRejected = tc.status === 'rejected';
    const riskClass =
      tc.risk === 'HIGH' ? 'high-risk' : tc.risk === 'MEDIUM' ? 'medium-risk' : '';
    const { label, target } = composeToolLabel(tc.toolName, tc.arguments);
    return html`
      <div class="tool-card ${riskClass}">
        <div class="tool-card-header">
          <span class="tool-name"
            >${label}${target ? html`<span class="tool-target">${target}</span>` : nothing}</span
          >
          <span class="tool-status">
            ${this.stepPresentation
              ? html`<jf-run-node
                  class="tool-status-glyph"
                  density="full"
                  .presentation=${this.stepPresentation}
                ></jf-run-node>`
              : nothing}
            ${/* Tempdoc 577 Ext II / §2.12 Move 4 — the risk tier is an approval-lattice input, not
                jargon: a FOCUSABLE disclosure (button) explains it via the same becauseLine
                authority, reachable by keyboard/AT (not a hover-only title — §2.11 #11). The
                accessible name carries the full explanation so AT announces it without expanding. */ ''}
            <button
              class="risk-word"
              aria-expanded=${this.riskWhyOpen ? 'true' : 'false'}
              aria-label=${`Risk tier ${tc.risk}. ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}`}
              @click=${() => {
                this.riskWhyOpen = !this.riskWhyOpen;
              }}
              >${tc.risk}</button
            > ·
            <span class="status-word" style=${`color: ${statusAccent(presentedToolStatus(tc.status, tc.success))}`}
              >${presentedToolStatus(tc.status, tc.success)}</span
            >
            <button class="expand-toggle"
              @click=${() => this.toggleExpanded()}
              title=${this.expanded ? 'Collapse' : 'Expand'}
              aria-expanded=${this.expanded ? 'true' : 'false'}
              aria-label=${this.expanded ? `Collapse ${tc.toolName}` : `Expand ${tc.toolName}`}
            >${this.expanded ? '▼' : '▶'}</button>
          </span>
        </div>
        ${/* Tempdoc 577 Move 4 — the risk explanation revealed by the focusable disclosure (visible
            text, not just the button's accessible name), so a sighted keyboard user sees it too. */ ''}
        ${this.riskWhyOpen
          ? html`<div class="risk-why" role="note">
              ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}
            </div>`
          : nothing}
        ${tc.arguments
          ? html`<div class="tool-args">${tc.arguments}</div>`
          : nothing}
        ${/* Tempdoc 561 #6: structured search evidence replaces the raw monospace dump when present. */ ''}
        ${/* Tempdoc 577 §2.14 Root III (#18): frame raw tool output by its backend-stamped lineage —
            corpus-quoted text is the user's documents quoted back (so injection/citation-shaped text
            inside it cannot read as the agent's own claim); runtime output renders plainly. */ ''}
        ${isCompleted && tc.output && !hasSearchEvidence(tc.structuredData)
          ? this.renderLineageFramedOutput(tc)
          : nothing}
        ${/* Tempdoc 577 §2.14 Root III (#18): the STRUCTURED search-evidence path is the main corpus
            reader's output — frame it as quoted too (the raw-output frame above only covers non-search
            corpus tools like browse-folders), so a search result reads as the documents' words. */ ''}
        ${isCompleted && hasSearchEvidence(tc.structuredData)
          ? this.renderEvidenceLineageHeader(tc)
          : nothing}
        ${isCompleted ? renderSearchEvidence(tc.structuredData) : nothing}
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
    `;
  }

  /**
   * Tempdoc 577 §2.14 Root III (#18) — render raw tool output framed by its text-provenance lineage
   * (the ONE {@link toolOutputLineage} authority). Corpus-quoted output gets a "Quoted from your
   * documents" header + a quoting frame so its citation/instruction-shaped text reads as the
   * documents' words, not the agent's own claim; runtime output renders plainly as before.
   */
  /**
   * Tempdoc 577 §2.14 Root III (#18) — the lineage header for the STRUCTURED search-evidence path
   * (the main corpus reader). Renders "Quoted from your documents" above the evidence cards when the
   * backend stamped the result corpus-quoted, so search output carries the same provenance frame the
   * raw-output path gives browse-folders. Same ONE toolOutputLineage authority; nothing for runtime.
   */
  private renderEvidenceLineageHeader(tc: ToolCall): TemplateResult | typeof nothing {
    const label = lineageFrameLabel(toolOutputLineage(tc.structuredData));
    return label === null
      ? nothing
      : html`<span class="lineage-frame-label" data-testid="evidence-lineage">${label}</span>`;
  }

  private renderLineageFramedOutput(tc: ToolCall): TemplateResult {
    const lineage = toolOutputLineage(tc.structuredData);
    const label = lineageFrameLabel(lineage);
    // Runtime output (the default) renders byte-identically to before — no frame, no extra nodes.
    // Only a backend-stamped corpus-quoted excerpt gets the quoting frame + header.
    if (label === null) {
      return html`<div class="tool-output lineage-${lineage}" data-lineage=${lineage}>${tc.output}</div>`;
    }
    return html`<div class="tool-output lineage-${lineage}" data-lineage=${lineage}><span
        class="lineage-frame-label"
        data-testid="tool-output-lineage"
        >${label}</span
      >${tc.output}</div>`;
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
