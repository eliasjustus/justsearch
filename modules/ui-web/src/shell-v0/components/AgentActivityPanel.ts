// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-agent-activity-panel> — Tempdoc 543 §32 U5.
 *
 * The unified "agent presence" side panel the agentic-browser field converged
 * on: it aggregates the existing substrates into one place —
 *   - Pending proposals (PendingEffect) with accept/reject,
 *   - In-flight Tasks (Task substrate),
 *   - Recent agent activity (Effect Journal, originator='agent').
 * Toggled open via the `core.action.shell.show-agent-activity` Action (mirrors
 * the audit log). Collapses when closed. It reads the same substrates the
 * floating surfaces use — a genuine aggregating consumer, not a new store.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import {
  listPending,
  rejectPending,
  subscribePending,
  type PendingEffect,
} from '../substrates/pending-effects/index.js';
import {
  listRunningTasks,
  subscribeTasks,
  type Task,
} from '../substrates/tasks/index.js';
import {
  listJournalByOriginator,
  subscribeJournal,
  type JournalEntry,
} from '../substrates/effects/index.js';
// §32 #2B — shared accept helpers (risk lookup + token-injecting accept).
import {
  needsTypedConfirm,
  acceptPendingNow,
  acceptPendingWithConsent,
} from './acceptPendingEffect.js';
// Tempdoc 565 §7.3 — participate in the single right-drawer arbiter so opening this
// panel closes the sources/retrospective/advisory drawers (and vice versa).
import { TransientController } from '../primitives/transientController.js';

const RECENT_LIMIT = 8;

export class AgentActivityPanel extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    pending: { state: true },
    tasks: { state: true },
    recent: { state: true },
    confirmingId: { state: true },
    typedConfirm: { state: true },
  };

  declare open: boolean;
  declare pending: readonly PendingEffect[];
  declare tasks: readonly Task[];
  declare recent: readonly JournalEntry[];
  /** §32 #2B — id of the pending awaiting a HIGH-risk typed-confirm, or null. */
  declare confirmingId: number | null;
  declare typedConfirm: string;

  private unsubs: Array<() => void> = [];

  /** 574 §23.B — single-open arbitration by construction (no outside-click dismiss: this panel closes
   *  via its own control / a peer drawer opening). `hostDisconnected` unregisters — fixes the prior leak. */
  private readonly transient = new TransientController(this, {
    layer: 'right-drawer',
    id: 'agent-activity',
    close: () => {
      this.open = false;
    },
  });

  constructor() {
    super();
    this.open = false;
    this.pending = [];
    this.tasks = [];
    this.recent = [];
    this.confirmingId = null;
    this.typedConfirm = '';
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.refresh();
    this.unsubs = [
      subscribePending(() => this.refresh()),
      subscribeTasks(() => this.refresh()),
      subscribeJournal(() => this.refresh()),
    ];
  }

  override updated(changed: Map<string, unknown>): void {
    // §7.3 / 574 §23.B — opening drives the controller (single-open by construction).
    if (changed.has('open')) {
      if (this.open) this.transient.open();
      else this.transient.close();
    }
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubs.forEach((u) => u());
    this.unsubs = [];
  }

  private refresh(): void {
    this.pending = listPending();
    this.tasks = listRunningTasks();
    this.recent = listJournalByOriginator('agent').slice(-RECENT_LIMIT).reverse();
  }

  static styles = css`
    :host(:not([open])) {
      display: none;
    }
    .panel {
      /* 559 Authority I: fills the OverlayHost right-drawer slot. */
      position: relative;
      height: 100%;
      width: 22rem;
      max-width: 90vw;
      background: var(--surface-1);
      border-left: 1px solid var(--border-default);
      box-shadow: -4px 0 16px rgba(0, 0, 0, 0.35);
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      display: flex;
      flex-direction: column;
      overflow-y: auto;
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
    }
    .title {
      font-weight: 600;
    }
    section {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    h4 {
      margin: 0 0 0.5rem;
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary);
    }
    .row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.25rem 0;
    }
    .kind {
      font-weight: 600;
    }
    .muted {
      color: var(--text-tertiary);
    }
    .empty {
      color: var(--text-tertiary);
      margin: 0;
    }
    .spacer {
      flex: 1;
    }
    /* 574 B (remediation) — accept = jf-button tone="success" (solid CTA), reject = variant="danger",
       cancel = secondary; the bespoke button/.accept/.reject fork is deleted. */
    /* §32 #2B — inline typed-confirm row. */
    .row.confirm {
      flex-wrap: wrap;
    }
    .row.confirm code {
      font-family: var(--font-mono);
      background: var(--surface-2);
      padding: 0 0.25rem;
      border-radius: 0.25rem;
    }
    .row.confirm input {
      flex: 1 1 8rem;
      min-width: 6rem;
      padding: 0.2rem 0.4rem;
      border: 1px solid var(--border-default);
      border-radius: 0.25rem;
      background: var(--surface-1);
      color: inherit;
      font: inherit;
      font-size: var(--font-size-xs);
    }
  `;

  private handleAccept(id: number): void {
    const p = this.pending.find((x) => x.id === id);
    // §32 #2B — HIGH-risk ops need a typed-confirm before re-dispatch (a bare
    // accept would 428 at the backend); LOW/MEDIUM accept immediately.
    if (p && needsTypedConfirm(p.effect, p.originator)) {
      this.confirmingId = id;
      this.typedConfirm = '';
      return;
    }
    acceptPendingNow(id);
  }

  // Tempdoc 550 C3: the typed string is only the UX gate; on confirm we mark the effect
  // consented and dispatch. The Shell recovers the backend 428 via approve-by-pendingId (no
  // client-side mint). Synchronous — no round-trip here.
  private confirmAccept(id: number): void {
    this.confirmingId = null;
    this.typedConfirm = '';
    acceptPendingWithConsent(id);
  }

  private cancelConfirm(): void {
    this.confirmingId = null;
    this.typedConfirm = '';
  }

  private renderPending(p: PendingEffect): TemplateResult {
    if (this.confirmingId === p.id) {
      const opId =
        p.effect.kind === 'invoke-operation' ? p.effect.operationId : '';
      const matches = this.typedConfirm === opId;
      return html`
        <div class="row confirm" data-testid="aap-confirm-${p.id}">
          <span class="muted">Type <code>${opId}</code> to confirm:</span>
          <input
            .value=${this.typedConfirm}
            @input=${(e: Event) => {
              this.typedConfirm = (e.target as HTMLInputElement).value;
            }}
          />
          <span class="spacer"></span>
          <jf-button class="cancel" size="sm" label="Cancel" .onActivate=${() => this.cancelConfirm()}>
            Cancel
          </jf-button>
          <jf-button
            class="accept"
            size="sm"
            tone="success"
            label="Confirm"
            ?disabled=${!matches}
            .onActivate=${() => this.confirmAccept(p.id)}
          >
            Confirm
          </jf-button>
        </div>
      `;
    }
    return html`
      <div class="row" data-testid="aap-pending-${p.id}">
        <span class="kind">${p.effect.kind}</span>
        <span class="muted">${p.rationale ?? ''}</span>
        <span class="spacer"></span>
        <jf-button class="reject" size="sm" variant="danger" label="Reject" .onActivate=${() => rejectPending(p.id)}>Reject</jf-button>
        <jf-button class="accept" size="sm" tone="success" label="Accept" .onActivate=${() => this.handleAccept(p.id)}>
          Accept
        </jf-button>
      </div>
    `;
  }

  private renderTask(t: Task): TemplateResult {
    return html`
      <div class="row" data-testid="aap-task-${t.id}">
        <span class="kind">${t.label}</span>
        <span class="spacer"></span>
        <span class="muted"
          >${t.progress !== undefined
            ? `${Math.round(t.progress * 100)}%`
            : 'running'}</span
        >
      </div>
    `;
  }

  private renderRecent(e: JournalEntry): TemplateResult {
    return html`
      <div class="row" data-testid="aap-recent-${e.id}">
        <span class="kind">${e.effect.kind}</span>
        ${e.pendingOutcome
          ? html`<span class="muted">${e.pendingOutcome}</span>`
          : nothing}
      </div>
    `;
  }

  render(): TemplateResult | typeof nothing {
    if (!this.open) return nothing;
    return html`
      <div class="panel" data-testid="agent-activity-panel">
        <div class="head">
          <span class="title">Agent activity</span>
          <jf-button
            variant="ghost"
            size="icon"
            data-testid="agent-activity-close"
            label="Close"
            .onActivate=${() => {
              this.open = false;
            }}
          >
            ✕
          </jf-button>
        </div>
        <section data-testid="aap-pending-section">
          <h4>Pending (${this.pending.length})</h4>
          ${this.pending.length
            ? this.pending.map((p) => this.renderPending(p))
            : html`<p class="empty">No pending proposals.</p>`}
        </section>
        <section data-testid="aap-tasks-section">
          <h4>Running tasks (${this.tasks.length})</h4>
          ${this.tasks.length
            ? this.tasks.map((t) => this.renderTask(t))
            : html`<p class="empty">No running tasks.</p>`}
        </section>
        <section data-testid="aap-recent-section">
          <h4>Recent agent actions (${this.recent.length})</h4>
          ${this.recent.length
            ? this.recent.map((e) => this.renderRecent(e))
            : html`<p class="empty">Nothing yet.</p>`}
        </section>
      </div>
    `;
  }
}

if (!customElements.get('jf-agent-activity-panel')) {
  customElements.define('jf-agent-activity-panel', AgentActivityPanel);
}
