// SPDX-License-Identifier: Apache-2.0
/**
 * ListItemsRenderer — first-party `x-ui-renderer` hint renderer (569 §15 / the Help rollout).
 *
 * Renders a string array as a bulleted list — the bespoke `<ul><li>` the HelpSurface hand-authored
 * for its troubleshooting / network panels. The author composes the strings; the engine renders.
 *
 * Schema fragment (hint on the array property):
 *   troubleshooting: { type:'array', 'x-ui-renderer':'list-items', items:{ type:'string' } }
 *
 * Side-effect registers `'list-items'` → `'jf-list-items'` at module load.
 */
import { html, css, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './xUiRendererRegistry.js';

export class ListItemsRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
    }
    ul {
      margin: 0;
      padding-inline-start: 1.1rem;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    li {
      font-size: var(--font-size-xs);
      line-height: 1.5;
      color: var(--text-secondary);
    }
    .empty {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const items = Array.isArray(this.data) ? (this.data as unknown[]) : [];
    if (items.length === 0) return html`<div class="empty">—</div>`;
    return html`<ul>
      ${items.map((it) => html`<li>${String(it)}</li>`)}
    </ul>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-list-items')) {
  customElements.define('jf-list-items', ListItemsRenderer);
}

registerXUiRenderer('list-items', 'jf-list-items');
