// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-pending-effect-queue> — Tempdoc 543 §21.E chrome surface.
 *
 * Renders the live queue of agent/system PendingEffects with
 * accept/reject affordances. Subscribes to subscribePending and
 * re-renders on every proposed/accepted/rejected event.
 *
 * Placement (per Shell.ts wiring): floats in the lower-right corner
 * when at least one Pending exists; collapses to nothing when empty so
 * the surface area stays at zero in user-only sessions.
 *
 * Pattern follows Cursor / Continue.dev / Copilot Workspace converged
 * UX (§22.5.5): each Pending shows kind + payload preview + rationale
 * + accept/reject buttons. The applyEffect dispatcher is supplied at
 * mount time so the substrate stays headless-testable.
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
import type { Effect } from '../substrates/effect.js';
// §32 #2B — shared accept helpers (risk lookup + token-injecting accept).
import {
  needsTypedConfirm,
  acceptPendingNow,
  acceptPendingWithConsent,
} from './acceptPendingEffect.js';

export class PendingEffectQueue extends JfElement {
  static properties = {
    pending: { state: true },
    confirmingId: { state: true },
    typedConfirm: { state: true },
  };

  declare pending: ReadonlyArray<PendingEffect>;
  /** §32 #2B — id of the pending awaiting a HIGH-risk typed-confirm, or null. */
  declare confirmingId: number | null;
  declare typedConfirm: string;

  private unsub: (() => void) | null = null;

  constructor() {
    super();
    this.pending = [];
    this.confirmingId = null;
    this.typedConfirm = '';
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.pending = listPending();
    this.unsub = subscribePending(() => {
      this.pending = listPending();
    });
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost bottom-right slot. */
      max-width: 28rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      pointer-events: none;
    }
    :host([data-empty]) {
      display: none;
    }
    .card {
      pointer-events: auto;
      background: var(--surface-1);
      border: 1px solid var(--border-default);
      border-left: 3px solid var(--accent-info);
      border-radius: 0.5rem;
      padding: 0.75rem 1rem;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .card[data-originator='agent'] {
      border-left-color: var(--accent-info);
    }
    .card[data-originator='system'] {
      border-left-color: var(--accent-warning);
    }
    .header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    .origin-chip {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .kind {
      font-weight: 600;
      color: var(--text-primary);
    }
    .confidence {
      margin-left: auto;
      font-size: var(--font-size-xs);
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .confidence[data-low] {
      background: var(--accent-warning);
      color: var(--accent-on-warning);
      font-weight: 600;
    }
    .rationale {
      color: var(--text-secondary);
      font-style: italic;
      margin: 0.25rem 0;
    }
    .preview {
      font-family: var(--font-mono);
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      background: var(--surface-2);
      padding: 0.375rem 0.5rem;
      border-radius: 0.25rem;
      margin-bottom: 0.5rem;
      white-space: pre-wrap;
      word-break: break-all;
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
    }
    /* 574 B (remediation) — accept = jf-button tone="success" (solid CTA), reject = variant="danger",
       cancel = secondary; the bespoke button/.accept/.reject fork is deleted. */
    /* §32 #2B — typed-confirm row for HIGH-risk op approval. */
    .confirm {
      margin-top: 0.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }
    .confirm .hint {
      color: var(--text-warning);
      font-size: var(--font-size-xs);
    }
    .confirm code {
      font-family: var(--font-mono);
      background: var(--surface-2);
      padding: 0 0.25rem;
      border-radius: 0.25rem;
    }
    .confirm input {
      pointer-events: auto;
      padding: 0.375rem 0.5rem;
      border: 1px solid var(--border-default);
      border-radius: 0.25rem;
      background: var(--surface-2);
      color: inherit;
      font: inherit;
      font-size: var(--font-size-xs);
    }
  `;

  updated(): void {
    if (this.pending.length === 0) {
      this.setAttribute('data-empty', '');
    } else {
      this.removeAttribute('data-empty');
    }
  }

  private effectPreview(effect: Effect): string {
    // Compact JSON preview for the proposed Effect. Real chrome could
    // upgrade this to a diff renderer for richer Effects.
    const { kind, ...rest } = effect;
    const payload = Object.keys(rest).length === 0 ? '' : JSON.stringify(rest);
    return `${kind}${payload ? ' ' + payload : ''}`;
  }

  private renderCard(p: PendingEffect): TemplateResult {
    return html`
      <div class="card" data-originator=${p.originator} data-pending-id=${p.id}>
        <div class="header">
          <span class="origin-chip">${p.originator}</span>
          <span class="kind">${p.effect.kind}</span>
          ${p.confidence !== undefined
            ? html`<span
                class="confidence"
                ?data-low=${p.confidence < 0.5}
                title="agent self-assessed confidence"
                >${Math.round(p.confidence * 100)}%</span
              >`
            : nothing}
        </div>
        ${p.rationale
          ? html`<div class="rationale">${p.rationale}</div>`
          : nothing}
        <div class="preview">${this.effectPreview(p.effect)}</div>
        ${this.confirmingId === p.id
          ? this.renderTypedConfirm(p)
          : html`<div class="actions">
              <jf-button
                class="reject"
                variant="danger"
                label="Reject"
                .onActivate=${() => this.handleReject(p.id)}
              >
                Reject
              </jf-button>
              <jf-button
                class="accept"
                tone="success"
                label="Accept"
                .onActivate=${() => this.handleAccept(p.id)}
              >
                Accept
              </jf-button>
            </div>`}
      </div>
    `;
  }

  // §32 #2B — inline typed-confirm for a HIGH-risk op (mirrors ActionButton's
  // gesture). The user types the operation id to confirm; on confirm the op is
  // re-dispatched as a user action carrying the confirmation token.
  private renderTypedConfirm(p: PendingEffect): TemplateResult {
    const opId =
      p.effect.kind === 'invoke-operation' ? p.effect.operationId : '';
    const matches = this.typedConfirm === opId;
    return html`
      <div class="confirm" data-testid="typed-confirm-${p.id}">
        <span class="hint"
          >Destructive action — type <code>${opId}</code> to confirm:</span
        >
        <input
          .value=${this.typedConfirm}
          @input=${(e: Event) => {
            this.typedConfirm = (e.target as HTMLInputElement).value;
          }}
        />
        <div class="actions">
          <jf-button class="cancel" label="Cancel" .onActivate=${() => this.cancelConfirm()}>
            Cancel
          </jf-button>
          <jf-button
            class="accept"
            tone="success"
            label="Confirm"
            ?disabled=${!matches}
            .onActivate=${() => this.confirmAccept(p.id)}
          >
            Confirm
          </jf-button>
        </div>
      </div>
    `;
  }

  private handleAccept(id: number): void {
    const p = this.pending.find((x) => x.id === id);
    // §32 #2B — a proposed HIGH-risk operation needs an explicit typed-confirm
    // (one-click accept would 428 at the backend). Enter the confirm step;
    // LOW/MEDIUM ops (and non-operation effects) accept immediately.
    if (p && needsTypedConfirm(p.effect, p.originator)) {
      this.confirmingId = id;
      this.typedConfirm = '';
      return;
    }
    acceptPendingNow(id);
  }

  // Tempdoc 550 C3: the typed string is only the UX gate (the user proves intent by typing
  // the op id). On confirm we mark the effect consented and dispatch; the Shell recovers the
  // backend 428 via approve-by-pendingId (no client-side mint). Synchronous — no round-trip.
  private confirmAccept(id: number): void {
    this.confirmingId = null;
    this.typedConfirm = '';
    acceptPendingWithConsent(id);
  }

  private cancelConfirm(): void {
    this.confirmingId = null;
    this.typedConfirm = '';
  }

  private handleReject(id: number): void {
    rejectPending(id);
  }

  render(): TemplateResult {
    // §32 R-E2 — surface the most-uncertain agent proposals first so the
    // user scrutinises low-confidence actions before high-confidence ones.
    // Proposals without a confidence score are treated as fully-confident
    // (sorted last), preserving prior order among unscored items.
    const ordered = [...this.pending].sort(
      (a, b) => (a.confidence ?? 1) - (b.confidence ?? 1),
    );
    return html`${ordered.map((p) => this.renderCard(p))}`;
  }
}

if (!customElements.get('jf-pending-effect-queue')) {
  customElements.define('jf-pending-effect-queue', PendingEffectQueue);
}
