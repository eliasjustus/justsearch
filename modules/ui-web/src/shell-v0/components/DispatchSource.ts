// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 495 §4.2 — `<jf-dispatch-source>` Lit component. Renders an
 * InvocationProvenance object as a compact badge (icon + label) or a
 * detailed attribution line (icon + label + executor + initiator + time).
 *
 * Not coupled to AdvisoryEvent — accepts any object matching the
 * provenance wire shape. Works for advisory surfaces today and for
 * operation history when that wire type adds provenance.
 *
 * Two modes via the `detailed` boolean attribute:
 * - Compact (default): [icon] [label] — inline, with tooltip
 * - Detailed: [icon] [label] • executor • initiator • relative time
 */

import { css, html, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { transportChrome } from './TransportChrome.js';
import { formatRelative } from '../utils/relativeTime.js';

export interface DispatchSourceData {
  readonly transport: string;
  readonly executor: string;
  readonly initiator: string | null;
  readonly occurredAt: string;
}

export class DispatchSource extends JfElement {
  static properties = {
    provenance: { attribute: false },
    detailed: { type: Boolean, reflect: true },
  };

  declare provenance: DispatchSourceData | null;
  declare detailed: boolean;

  static styles = css`
    :host {
      display: inline;
      font-size: inherit;
      line-height: inherit;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      color: var(--text-secondary);
    }
    .icon {
      font-size: var(--font-size-sm);
    }
    .label {
      font-size: var(--font-size-sm);
    }
    :host([detailed]) .badge {
      gap: 0.375rem;
    }
    .detail-sep {
      color: var(--text-tertiary);
    }
    .detail-field {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
    }
  `;

  constructor() {
    super();
    this.provenance = null;
    this.detailed = false;
  }

  override render() {
    if (!this.provenance) return nothing;
    const chrome = transportChrome(this.provenance.transport);
    const tooltip = this.buildTooltip();

    if (!this.detailed) {
      return html`
        <span class="badge ${chrome.cssClass}" title=${tooltip}>
          <span class="icon">${chrome.icon}</span>
          <span class="label">${chrome.label}</span>
        </span>
      `;
    }

    const p = this.provenance;
    const time = formatRelative(new Date(p.occurredAt).getTime());
    return html`
      <span class="badge ${chrome.cssClass}">
        <span class="icon">${chrome.icon}</span>
        <span class="label">${chrome.label}</span>
        <span class="detail-sep">•</span>
        <span class="detail-field">${p.executor}</span>
        ${p.initiator
          ? html`
              <span class="detail-sep">•</span>
              <span class="detail-field">${p.initiator}</span>
            `
          : nothing}
        <span class="detail-sep">•</span>
        <span class="detail-field">${time}</span>
      </span>
    `;
  }

  private buildTooltip(): string {
    if (!this.provenance) return '';
    const p = this.provenance;
    const parts = [`Transport: ${p.transport}`, `Executor: ${p.executor}`];
    if (p.initiator) parts.push(`Initiator: ${p.initiator}`);
    parts.push(`At: ${new Date(p.occurredAt).toLocaleString()}`);
    return parts.join('\n');
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-dispatch-source')
) {
  customElements.define('jf-dispatch-source', DispatchSource);
}
