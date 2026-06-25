// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 499 E2 — Transient overlay for unresolved URL-bar intents.
 *
 * Shows a NavigationReceipt with suggestion chips for 8 seconds, then
 * auto-dismisses. Positioned at the top of the Shell viewport.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import type { Suggestion } from '../../router/resolution.js';
import './NavigationReceipt.js';
import '../Control.js';

const DISMISS_MS = 8000;

export class ResolutionToast extends JfElement {
  static properties = {
    target: { type: String },
    alternatives: { type: Array },
    visible: { type: Boolean, reflect: true },
    mode: { type: String },
    originalId: { type: String, attribute: 'original-id' },
  };

  declare target: string;
  declare alternatives: Array<{ id: string; label: string }>;
  declare visible: boolean;
  declare mode: 'unresolved' | 'auto-corrected';
  declare originalId: string;

  private dismissTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    super();
    this.target = '';
    this.alternatives = [];
    this.visible = false;
    this.mode = 'unresolved';
    this.originalId = '';
  }

  show(target: string, alternatives: Suggestion[]): void {
    this.target = target;
    this.mode = 'unresolved';
    this.originalId = '';
    this.alternatives = alternatives.map(a => ({ id: a.id, label: a.label }));
    this.visible = true;
    if (this.dismissTimer) clearTimeout(this.dismissTimer);
    this.dismissTimer = setTimeout(() => { this.visible = false; }, DISMISS_MS);
  }

  showAutoCorrection(originalId: string, correctedId: string): void {
    this.target = correctedId;
    this.mode = 'auto-corrected';
    this.originalId = originalId;
    this.alternatives = [];
    this.visible = true;
    if (this.dismissTimer) clearTimeout(this.dismissTimer);
    this.dismissTimer = setTimeout(() => { this.visible = false; }, DISMISS_MS);
  }

  static styles = css`
    :host {
      position: fixed;
      top: 3rem;
      left: 50%;
      transform: translateX(-50%);
      z-index: var(--z-overlay-top);
      pointer-events: none;
      opacity: 0;
      transition: opacity var(--duration-normal) var(--ease-standard);
    }
    :host([visible]) {
      opacity: 1;
      pointer-events: auto;
    }
    .toast-wrapper {
      background: var(--surface-1);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      padding: 0.5rem;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
    }
    jf-control.undo-link {
      display: block;
      margin-top: 0.375rem;
    }
    jf-control.undo-link::part(control) {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    jf-control.undo-link::part(control):hover {
      color: var(--text-tint);
      text-decoration: underline;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html`${nothing}`;
    if (this.mode === 'auto-corrected') {
      return html`<div class="toast-wrapper">
        <jf-navigation-receipt
          outcome="auto-corrected"
          target=${this.target}
          corrected-from=${this.originalId}
          address-kind="navigate"
        ></jf-navigation-receipt>
        <jf-control class="undo-link"
          label="Undo auto-correction"
          .onActivate=${() => this.dispatchEvent(new CustomEvent('undo-auto-correct', {
            detail: { originalId: this.originalId },
            bubbles: true, composed: true,
          }))}>Not what you meant? Activate to undo</jf-control>
      </div>`;
    }
    return html`<div class="toast-wrapper">
      <jf-navigation-receipt
        outcome="unresolved"
        target=${this.target}
        address-kind="navigate"
        .suggestions=${this.alternatives}
      ></jf-navigation-receipt>
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-resolution-toast')) {
  customElements.define('jf-resolution-toast', ResolutionToast);
}
