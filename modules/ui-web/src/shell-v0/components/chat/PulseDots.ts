// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 495 P4 — Shared animated pulse-dots loading indicator.
 *
 * Extracted from AgentView + ToolCallCard where identical CSS + markup
 * was duplicated in both shadow DOMs.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';

export class PulseDots extends JfElement {
  static styles = css`
    :host {
      display: inline-flex;
      gap: 0.25rem;
    }
    span {
      width: 0.375rem;
      height: 0.375rem;
      border-radius: 50%;
      background: var(--accent-primary);
      animation: pulse 1.4s ease-in-out infinite;
    }
    span:nth-child(2) {
      animation-delay: 0.15s;
    }
    span:nth-child(3) {
      animation-delay: 0.3s;
    }
    @keyframes pulse {
      0%, 60%, 100% { opacity: 0.3; }
      30% { opacity: 1; }
    }
  `;

  override render(): TemplateResult {
    return html`<span></span><span></span><span></span>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-pulse-dots')) {
  customElements.define('jf-pulse-dots', PulseDots);
}
