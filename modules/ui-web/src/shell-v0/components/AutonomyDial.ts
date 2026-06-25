// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-autonomy-dial> — Tempdoc 543 §32 U1 (Autonomy Dial).
 *
 * A 3-position segmented control for the agent autonomy level
 * (watch / assist / auto). Reads/writes the autonomy substrate; the agent
 * invocation path (`invokeAndApply`) consults it to decide propose-vs-dispatch.
 * NOT an accept-count learner — the destructive-op gate is backend-enforced
 * (§32.9), and this dial only tunes the FE's proactive oversight.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import {
  getAutonomyLevel,
  setAutonomyLevel,
  listAutonomyLevels,
  subscribeAutonomy,
  type AutonomyLevel,
} from '../substrates/autonomy/index.js';

const LABELS: Record<AutonomyLevel, string> = {
  watch: 'Watch',
  assist: 'Assist',
  auto: 'Auto',
};

// Honest copy: the three levels are genuinely distinct (keyed on whether the
// agent effect calls the backend), and confirmation of destructive actions is
// always enforced by the BACKEND trust lattice regardless of this dial.
const HINTS: Record<AutonomyLevel, string> = {
  watch: 'Every agent action waits in the queue for your approval.',
  assist:
    'Agent operates the UI freely; backend actions wait for your one-click approval.',
  auto: 'Agent acts freely; the backend still confirms destructive actions.',
};

export class AutonomyDial extends JfElement {
  static properties = {
    level: { state: true },
    compact: { type: Boolean, reflect: true },
  };

  declare level: AutonomyLevel;
  /**
   * Compact variant: renders just the 3-segment control (no title/hint block),
   * with the per-segment hint surfaced via the `title=` tooltip. Used inline in
   * the agent surface header; the full dial is used in Settings. Both share the
   * same autonomy substrate state.
   */
  declare compact: boolean;

  private unsub: (() => void) | null = null;

  constructor() {
    super();
    this.level = getAutonomyLevel();
    this.compact = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.level = getAutonomyLevel();
    this.unsub = subscribeAutonomy(() => {
      this.level = getAutonomyLevel();
    });
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
  }

  static styles = css`
    .dial {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .title {
      font-weight: 600;
    }
    .segments {
      display: inline-flex;
      border: 1px solid var(--border-default);
      border-radius: 0.375rem;
      overflow: hidden;
      width: fit-content;
    }
    .seg {
      background: var(--surface-1);
      color: var(--text-secondary);
      border: none;
      border-right: 1px solid var(--border-default);
      padding: 0.3rem 0.85rem;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .seg:last-child {
      border-right: none;
    }
    .seg:hover {
      background: var(--surface-2);
    }
    .seg[data-active] {
      background: var(--accent-info);
      /* 559 contrast-pair token: white on the light-teal accent failed WCAG AA (1.97:1); the
         on-tint ink is dark in the dark theme / near-white in the light theme (>=4.5:1). */
      color: var(--accent-on-tint);
      font-weight: 600;
    }
    .hint {
      color: var(--text-tertiary);
      font-size: var(--font-size-xs);
    }
  `;

  private select(level: AutonomyLevel): void {
    setAutonomyLevel(level);
  }

  render(): TemplateResult {
    return html`
      <div class="dial" role="radiogroup" aria-label="Agent autonomy">
        ${this.compact
          ? nothing
          : html`<span class="title">Agent autonomy</span>`}
        <div class="segments">
          ${listAutonomyLevels().map(
            (l) => html`<button
              class="seg"
              role="radio"
              aria-checked=${this.level === l}
              ?data-active=${this.level === l}
              data-testid="autonomy-${l}"
              title=${HINTS[l]}
              @click=${() => this.select(l)}
            >
              ${LABELS[l]}
            </button>`,
          )}
        </div>
        ${this.compact
          ? nothing
          : html`<span class="hint" data-testid="autonomy-hint"
              >${HINTS[this.level]}</span
            >`}
      </div>
    `;
  }
}

if (!customElements.get('jf-autonomy-dial')) {
  customElements.define('jf-autonomy-dial', AutonomyDial);
}
