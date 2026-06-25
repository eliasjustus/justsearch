// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 490 §4.D — Rail-edge badge displaying the unread advisory count.
 *
 * Click toggles the {@link AdvisoryInboxDrawer} via a custom event
 * (`advisory-toggle-inbox`) that the {@code Shell} listens for. The
 * badge mounts in the rail's bottom region (siblings of help/settings).
 */

import { css, html, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import '../StatusDot.js';
import { type AdvisorySnapshot, AdvisoryStore } from './AdvisoryStore.js';

export class AdvisoryRailBadge extends JfElement {
  static properties = {
    store: { attribute: false },
    unreadCount: { state: true },
    isConnected: { state: true },
  };

  /**
   * Slice 490 Group B4 — injected by the parent {@code Shell}. Replaces the
   * previous singleton-via-getAdvisoryStore() lookup so all advisory chrome
   * elements share one shell-owned store with one apiBase.
   */
  declare store: AdvisoryStore | null;
  declare unreadCount: number;
  declare isConnected: boolean;

  private storeUnsubscribe: (() => void) | null = null;

  static styles = css`
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    button {
      position: relative;
      width: 2.25rem;
      height: 2.25rem;
      border-radius: 0.4rem;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid transparent;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background var(--duration-fast), color var(--duration-fast);
    }
    button:hover {
      background: var(--surface-secondary);
      color: var(--text-primary);
    }
    button.disconnected {
      opacity: 0.45;
    }
    .icon {
      width: 1rem;
      height: 1rem;
      stroke: currentColor;
      stroke-width: 2;
      fill: none;
    }
    .disconnected-dot {
      position: absolute;
      bottom: 2px;
      right: 2px;
    }
    .count {
      position: absolute;
      top: 2px;
      right: 2px;
      min-width: 1rem;
      height: 1rem;
      padding: 0 0.25rem;
      border-radius: 0.5rem;
      background: var(--accent-warning);
      color: var(--accent-on-warning);
      font-size: var(--font-size-xs);
      font-weight: 700;
      line-height: 1rem;
      text-align: center;
    }
  `;

  constructor() {
    super();
    this.store = null;
    this.unreadCount = 0;
    this.isConnected = false;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    if (this.store) {
      this.storeUnsubscribe = this.store.subscribe((s: AdvisorySnapshot) => {
        this.unreadCount = s.unreadCount;
        this.isConnected = s.isConnected;
      });
    }
  }

  override disconnectedCallback(): void {
    if (this.storeUnsubscribe) {
      this.storeUnsubscribe();
      this.storeUnsubscribe = null;
    }
    super.disconnectedCallback();
  }

  private handleClick(): void {
    this.dispatchEvent(
      new CustomEvent('advisory-toggle-inbox', { bubbles: true, composed: true }),
    );
  }

  override render(): TemplateResult {
    // Slice 490 Group B6: a dimmed dot indicates the SSE stream is not connected.
    // The badge stays click-targetable (drawer can still open against the local
    // snapshot); the dot is the user-visible "advisory feed may be stale" cue.
    const titleText = this.isConnected
      ? 'Advisories'
      : 'Advisories (offline — feed may be stale)';
    const ariaLabel = this.unreadCount > 0
      ? `${titleText} — ${this.unreadCount} unread`
      : titleText;
    return html`
      <button
        type="button"
        class=${this.isConnected ? '' : 'disconnected'}
        title=${titleText}
        aria-label=${ariaLabel}
        @click=${this.handleClick}
      >
        <svg class="icon" viewBox="0 0 24 24">
          <path d="M6 8a6 6 0 0 1 12 0v5l1.5 3h-15L6 13V8z" />
          <path d="M10 19a2 2 0 0 0 4 0" />
        </svg>
        ${this.unreadCount > 0
          ? html`<span class="count">${this.unreadCount > 99 ? '99+' : this.unreadCount}</span>`
          : nothing}
        ${!this.isConnected
          ? html`<jf-status-dot
              class="disconnected-dot"
              tone="neutral"
              aria-hidden="true"
            ></jf-status-dot>`
          : nothing}
      </button>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-advisory-rail-badge')
) {
  customElements.define('jf-advisory-rail-badge', AdvisoryRailBadge);
}
