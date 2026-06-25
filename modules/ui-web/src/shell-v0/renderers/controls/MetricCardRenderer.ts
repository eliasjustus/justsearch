// SPDX-License-Identifier: Apache-2.0
/**
 * MetricCardRenderer — first-party `x-ui-renderer` hint renderer (569 §15 / the Health rollout).
 *
 * Renders a metrics array (`{ label, value, icon?, tone?, sub? }[]`) as the bespoke stat cards the
 * HealthSurface hand-authored — so a DECLARED Health stats region matches the hand-authored look. The
 * `tone` dot is a SEMANTIC token (theme-managed contrast), never an author colour (Move 3). The author
 * composes the (pre-formatted) data; the engine derives the operable, contrast-safe cards.
 *
 * Schema fragment (hint on the array property):
 *   metrics: { type:'array', 'x-ui-renderer':'metric-card', items:{ properties:{ label, value, icon, tone, sub } } }
 *
 * Side-effect registers `'metric-card'` → `'jf-metric-card'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './xUiRendererRegistry.js';
import { icon, type IconName } from '../../components/Icon.js';

type MetricTone = 'success' | 'warning' | 'error' | 'neutral';

interface Metric {
  readonly label?: string;
  readonly value?: string;
  readonly icon?: string;
  readonly tone?: MetricTone;
  readonly sub?: string;
}

export class MetricCardRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr));
      gap: 0.6rem;
    }
    .card {
      padding: 0.6rem 0.7rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      background: var(--surface-2);
    }
    .card-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.4rem;
    }
    .card-label {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .dot {
      inline-size: 0.5rem;
      block-size: 0.5rem;
      border-radius: 50%;
      flex: none;
      background: var(--text-secondary);
    }
    .dot[data-tone='success'] {
      background: var(--accent-success);
    }
    .dot[data-tone='warning'] {
      background: var(--accent-warning);
    }
    .dot[data-tone='error'] {
      background: var(--accent-danger);
    }
    .card-value {
      margin-top: 0.3rem;
      font-size: var(--font-size-md);
      font-weight: 600;
      color: var(--text-primary);
    }
    .card-sub {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .empty {
      padding: 0.5rem;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const metrics = Array.isArray(this.data) ? (this.data as Metric[]) : [];
    if (metrics.length === 0) return html`<div class="empty">No metrics.</div>`;
    return html`<div class="cards" role="list">
      ${metrics.map(
        (m) => html`<div class="card" role="listitem">
          <div class="card-head">
            <span class="card-label"
              >${m.icon ? icon({ name: m.icon as IconName, size: 14 }) : nothing}${m.label ?? ''}</span
            >
            <span class="dot" data-tone=${m.tone ?? 'neutral'}></span>
          </div>
          <div class="card-value">${m.value ?? '—'}</div>
          ${m.sub ? html`<div class="card-sub">${m.sub}</div>` : nothing}
        </div>`,
      )}
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-metric-card')) {
  customElements.define('jf-metric-card', MetricCardRenderer);
}

registerXUiRenderer('metric-card', 'jf-metric-card');
