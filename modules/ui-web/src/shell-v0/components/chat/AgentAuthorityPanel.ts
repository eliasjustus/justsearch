// SPDX-License-Identifier: Apache-2.0
/**
 * AgentAuthorityPanel — Tempdoc 577 §2.13 #17 / §2.14 Root III.
 *
 * "What this agent can do, and what will ask first." Projects the agent's verb-space (the rich
 * `/api/chat/agent/tools` inventory the FE now reads) grouped by what the CURRENT posture does with
 * each tool — so a user calibrates trust by inspection before delegating, and the §2.11 #8 approval
 * ceremony becomes reachable without tripping it. The Goal-1 Move C analogue, other window.
 *
 * Single authorities reused: the operation Display label (`present`), the gate-explanation
 * (`becauseLine`), the verb-space disposition projection (`authoritySpace`). No new classifier.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { present } from '../../display/present.js';
// Tempdoc 605 #6 — the backend emits the tool description as an i18n KEY (`ops.<op>.description`,
// AgentToolsController contract); resolve it through the SAME sync catalog `present` uses for the
// label, so the panel renders prose, not the raw key. The registry-operation catalog is booted at app
// start, so it's resolved by render time.
import { localizeResourceKey } from '../../../i18n/resourceCatalog.js';
import { becauseLine, type AutonomyLevel } from '../../substrates/autonomy/index.js';
import type { AgentToolInfo } from '../../controllers/AgentSessionController.js';
import {
  toolDisposition,
  dispositionLabel,
  DISPOSITION_ORDER,
  type ToolDisposition,
} from '../../display/authoritySpace.js';

export class AgentAuthorityPanel extends JfElement {
  static properties = {
    tools: { attribute: false },
    level: { type: String },
  };

  declare tools: AgentToolInfo[];
  /** The current autonomy posture (Watch/Assist/Auto) — drives the "will ask first" projection. */
  declare level: AutonomyLevel;

  constructor() {
    super();
    this.tools = [];
    this.level = 'assist';
  }

  static styles = css`
    :host {
      display: block;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .authority-head {
      font-weight: 600;
      margin-bottom: 0.5rem;
    }
    .authority-sub {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-bottom: 0.75rem;
    }
    .group {
      margin-bottom: 0.85rem;
    }
    .group-head {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-secondary);
      margin-bottom: 0.35rem;
    }
    .tool-row {
      display: flex;
      align-items: baseline;
      gap: 0.5rem;
      padding: 0.35rem 0;
      border-bottom: 1px solid var(--border-subtle);
    }
    .tool-name {
      font-weight: 500;
    }
    .tool-desc {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      flex: 1;
      min-width: 0;
    }
    .risk-chip {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      flex-shrink: 0;
    }
    .risk-high {
      color: var(--text-danger);
    }
    .risk-medium {
      color: var(--text-warning);
    }
    .risk-low {
      color: var(--text-secondary);
    }
    .undo-chip {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      flex-shrink: 0;
    }
    .empty {
      color: var(--text-secondary);
      font-style: italic;
    }
  `;

  private toolLabel(t: AgentToolInfo): string {
    return present({ kind: 'operation', id: t.name }).label as unknown as string;
  }

  private renderRow(t: AgentToolInfo): TemplateResult {
    const riskClass =
      t.risk === 'high' ? 'risk-high' : t.risk === 'medium' ? 'risk-medium' : 'risk-low';
    const riskUpper = (t.risk ?? 'low').toUpperCase() as 'LOW' | 'MEDIUM' | 'HIGH';
    return html`<div
      class="tool-row"
      title=${becauseLine(riskUpper, this.level)}
    >
      <span class="tool-name">${this.toolLabel(t)}</span>
      <span class="tool-desc">${t.description ? localizeResourceKey(t.description) : ''}</span>
      ${t.supportsUndo ? html`<span class="undo-chip">undoable</span>` : nothing}
      <span class="risk-chip ${riskClass}">${t.risk ?? 'low'}</span>
    </div>`;
  }

  override render(): TemplateResult {
    if (this.tools.length === 0) {
      return html`<div class="empty">No agent tools available.</div>`;
    }
    // Group by what the CURRENT posture does with each tool; trust-relevant groups first.
    const byDisposition = new Map<ToolDisposition, AgentToolInfo[]>();
    for (const t of this.tools) {
      const d = toolDisposition(t.risk, t.supportsUndo, this.level);
      (byDisposition.get(d) ?? byDisposition.set(d, []).get(d)!).push(t);
    }
    return html`
      <div class="authority-head">What this agent can do</div>
      <div class="authority-sub">
        ${this.tools.length} tool${this.tools.length === 1 ? '' : 's'} · grouped by what the current
        posture does — change the dial to see what would ask first.
      </div>
      ${DISPOSITION_ORDER.filter((d) => (byDisposition.get(d)?.length ?? 0) > 0).map(
        (d) => html`<div class="group">
          <div class="group-head">${dispositionLabel(d)}</div>
          ${byDisposition.get(d)!.map((t) => this.renderRow(t))}
        </div>`,
      )}
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-agent-authority-panel')) {
  customElements.define('jf-agent-authority-panel', AgentAuthorityPanel);
}
