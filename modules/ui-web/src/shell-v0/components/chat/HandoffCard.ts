// SPDX-License-Identifier: Apache-2.0
/**
 * HandoffCard (`jf-handoff-card`) — tempdoc 585 §D Phase 2 (D2, multi-agent handoff visualisation).
 *
 * Renders a multi-agent handoff as a structured "from → to" row instead of the prior flat
 * `trace-label` text (UnifiedChatView `case 'handoff'`). The handoff events (`HandoffProposed`/
 * `HandoffExecuted`) already persist `fromAgentId`/`toAgentId`; this surfaces them as two role badges
 * with the reason riding as muted secondary text.
 *
 * Composition discipline: each role is the ONE status-badge atom (`jf-status-badge`, `origin="agent"`)
 * so the agent colour PROJECTS from the 574 §23.B originator-tone authority — no re-authored
 * badge/pill CSS (the `atom-fork` gate). The card itself owns only layout + the muted reason text.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import '../StatusBadge.js';

export class HandoffCard extends JfElement {
  static properties = {
    from: { type: String },
    to: { type: String },
    reason: { type: String },
  };

  declare from?: string;
  declare to?: string;
  declare reason?: string;

  static styles = css`
    :host {
      display: block;
    }
    .handoff {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .label {
      font-weight: 600;
    }
    .arrow {
      color: var(--text-secondary);
    }
    .reason {
      color: var(--text-secondary);
    }
    .reason::before {
      content: '·';
      margin-right: 0.375rem;
    }
  `;

  override render(): TemplateResult {
    const from = this.from ?? '';
    const to = this.to ?? '';
    return html`<div
      class="handoff"
      part="handoff"
      role="group"
      aria-label=${`Handoff from ${from || 'an agent'} to ${to || 'another agent'}`}
    >
      <span class="label">Handoff</span>
      <jf-status-badge origin="agent" label=${from}></jf-status-badge>
      <span class="arrow" aria-hidden="true">→</span>
      <jf-status-badge origin="agent" label=${to}></jf-status-badge>
      ${this.reason ? html`<span class="reason">${this.reason}</span>` : nothing}
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-handoff-card')) {
  customElements.define('jf-handoff-card', HandoffCard);
}
