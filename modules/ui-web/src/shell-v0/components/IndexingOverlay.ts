// SPDX-License-Identifier: Apache-2.0
/**
 * IndexingOverlay — Lit modal overlay for indexing-mode (slice 460).
 *
 * Shown when system is in `mode === 'indexing'` AND embedding/VDU
 * queues are processing AND the user hasn't dismissed it. Provides a
 * "Go Online" button that invokes `core.switch-inference-mode`.
 *
 * Two elements:
 *  - `<jf-indexing-overlay>`: presentational modal.
 *  - `<jf-indexing-overlay-host>`: subscribes to inferencePoll, owns
 *    visibility logic + Operation invocation.
 *
 * Side-effect registers both.
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { icon } from './Icon.js';
import { getOperationClient } from '../operations/OperationClient.js';
import {
  subscribeAiState,
  type AiState,
} from '../state/aiStateStore.js';
import { orElse } from '../state/known.js';

const NUM = new Intl.NumberFormat();

export class IndexingOverlay extends JfElement {
  static properties = {
    embeddingQueueSize: { type: Number, attribute: 'embedding-queue-size' },
    vduQueueSize: { type: Number, attribute: 'vdu-queue-size' },
    switching: { type: Boolean },
    dismissible: { type: Boolean },
  };

  declare embeddingQueueSize: number;
  declare vduQueueSize: number;
  declare switching: boolean;
  declare dismissible: boolean;

  constructor() {
    super();
    this.embeddingQueueSize = 0;
    this.vduQueueSize = 0;
    this.switching = false;
    this.dismissible = true;
  }

  static styles = css`
    :host {
      display: contents;
    }
    .backdrop {
      /* 559 Authority I: absolute within the OverlayHost center slot (fixed;inset:0). */
      position: absolute;
      inset: 0;
      pointer-events: auto;
      background: rgba(0, 0, 0, 0.6);
      /* Tempdoc 567 §9.4 — respect solid surface mode (--glass-blur-scale:0 zeroes the blur). */
      backdrop-filter: blur(calc(4px * var(--glass-blur-scale)));
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 1rem;
    }
    .card {
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.75rem;
      max-width: 28rem;
      width: 100%;
      padding: 1.5rem;
      color: var(--text-primary);
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 1rem;
    }
    .head-left {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .icon-box {
      padding: 0.5rem;
      background: var(--accent-chat-16);
      border-radius: 0.5rem;
      color: var(--text-chat);
      animation: spin 3s linear infinite;
    }
    h3 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
    }
    .sub {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
    .close {
      padding: 0.25rem;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      cursor: pointer;
      border-radius: 0.25rem;
    }
    .close:hover {
      background: var(--surface-secondary);
      color: var(--text-primary);
    }
    .explain {
      font-size: var(--font-size-sm);
      line-height: 1.5;
      color: var(--text-secondary);
      margin-bottom: 1.25rem;
    }
    .queue {
      background: var(--surface-secondary);
      border-radius: 0.5rem;
      padding: 0.875rem;
      margin-bottom: 1.25rem;
    }
    .queue-label {
      font-size: var(--font-size-xs);
      font-weight: 500;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary);
      margin-bottom: 0.5rem;
    }
    .queue-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--font-size-sm);
    }
    .queue-row + .queue-row {
      margin-top: 0.5rem;
    }
    .queue-row .left {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .queue-row.vdu .left {
      color: var(--text-command);
    }
    .queue-row.embed .left {
      color: var(--text-tint);
    }
    .queue-row .count {
      font-family: monospace;
      color: var(--text-primary);
    }
    button.cta {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      width: 100%;
      padding: 0.625rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border: none;
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
      font-weight: 500;
      cursor: pointer;
    }
    button.cta:hover:not(:disabled) {
      background: var(--accent-tint-hover);
    }
    button.cta:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  `;

  private goOnline(): void {
    this.dispatchEvent(
      new CustomEvent('go-online', { bubbles: true, composed: true }),
    );
  }

  private dismiss(): void {
    this.dispatchEvent(
      new CustomEvent('dismiss', { bubbles: true, composed: true }),
    );
  }

  override render(): TemplateResult {
    const total = this.embeddingQueueSize + this.vduQueueSize;
    const hasWork = total > 0;
    return html`
      <div class="backdrop">
        <div class="card" role="dialog" aria-modal="true">
          <div class="head">
            <div class="head-left">
              <div class="icon-box">${icon({ name: 'cpu', size: 22 })}</div>
              <div>
                <h3>Batch Processing Active</h3>
                <p class="sub">Chat &amp; Q&amp;A paused — search still works</p>
              </div>
            </div>
            ${this.dismissible
              ? html`<button class="close" @click=${() => this.dismiss()} title="Minimize">
                  ${icon({ name: 'x', size: 16 })}
                </button>`
              : nothing}
          </div>
          <p class="explain">
            The system is generating embeddings for your indexed files. Chat, Q&amp;A, and
            summarization are paused to prevent GPU memory conflicts — search still works.
          </p>
          ${hasWork
            ? html`
                <div class="queue">
                  <div class="queue-label">Processing queue</div>
                  ${this.vduQueueSize > 0
                    ? html`
                        <div class="queue-row vdu">
                          <span class="left"
                            >${icon({ name: 'file-text', size: 14 })} Document Vision</span
                          >
                          <span class="count">${NUM.format(this.vduQueueSize)}</span>
                        </div>
                      `
                    : nothing}
                  ${this.embeddingQueueSize > 0
                    ? html`
                        <div class="queue-row embed">
                          <span class="left"
                            >${icon({ name: 'zap', size: 14 })} Semantic embeddings</span
                          >
                          <span class="count">${NUM.format(this.embeddingQueueSize)}</span>
                        </div>
                      `
                    : nothing}
                </div>
              `
            : nothing}
          <button class="cta" ?disabled=${this.switching} @click=${() => this.goOnline()}>
            ${this.switching
              ? html`${icon({ name: 'loader-2', size: 14, spin: true })} Going online…`
              : html`${icon({ name: 'check-circle-2', size: 14 })} Go online (interrupt)`}
          </button>
        </div>
      </div>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-indexing-overlay')
) {
  customElements.define('jf-indexing-overlay', IndexingOverlay);
}

// ---------- Host: visibility + Operation invocation ----------

export class IndexingOverlayHost extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    aiState: { state: true },
    userDismissed: { state: true },
    switching: { state: true },
  };

  declare apiBase: string;
  declare aiState: AiState | null;
  declare userDismissed: boolean;
  declare switching: boolean;

  private unsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.aiState = null;
    this.userDismissed = false;
    this.switching = false;
  }

  static styles = css`
    :host {
      display: contents;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribe = subscribeAiState((s) => {
      const wasOnline = this.aiState?.runtime?.mode === 'online';
      this.aiState = s;
      if (s.runtime.mode === 'online' && !wasOnline) {
        this.userDismissed = false;
      }
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
  }

  private async handleGoOnline(): Promise<void> {
    this.switching = true;
    try {
      const apiBase =
        this.apiBase ||
        (typeof globalThis !== 'undefined' &&
          (globalThis as { location?: { origin?: string } }).location?.origin) ||
        '';
      const client = getOperationClient(String(apiBase));
      await client.invoke('core.switch-inference-mode', { args: { mode: 'online' } });
    } catch {
      // ignore; status will catch up via the poller
    } finally {
      this.switching = false;
    }
  }

  override render(): TemplateResult | typeof nothing {
    const ai = this.aiState;
    if (!ai) return nothing;
    if (ai.runtime.mode !== 'indexing') return nothing;
    const queueTotal = orElse(ai.index.embeddingQueueSize, 0) + orElse(ai.index.vduQueueSize, 0);
    if (queueTotal === 0) return nothing;
    if (this.userDismissed) return nothing;
    return html`
      <jf-indexing-overlay
        embedding-queue-size=${orElse(ai.index.embeddingQueueSize, 0)}
        vdu-queue-size=${orElse(ai.index.vduQueueSize, 0)}
        ?switching=${this.switching}
        @go-online=${() => void this.handleGoOnline()}
        @dismiss=${() => (this.userDismissed = true)}
      ></jf-indexing-overlay>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-indexing-overlay-host')
) {
  customElements.define('jf-indexing-overlay-host', IndexingOverlayHost);
}
