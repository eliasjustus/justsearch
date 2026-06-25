// SPDX-License-Identifier: Apache-2.0
/**
 * SearchResultsRenderer — first-party `x-ui-renderer` hint renderer (569 Fix A / §9 results kind).
 *
 * Renders a results-list array (the §9 "results list" surface KIND) at the bespoke row quality
 * SearchSurface hand-authored (title / path / snippet), through the projection engine — proving the
 * declaration can render a results surface, not just a settings form. CONTENT only: the bespoke
 * INTERACTIONS (shift-range multi-select, context menu, provenance disclosure) are the §7 team-owned
 * long tail (novel interaction logic), deliberately NOT forced through the engine.
 *
 * Schema fragment (hint on the array property):
 *   hits: { type:'array', 'x-ui-renderer':'search-results', items:{ properties:{ title, path, snippet, score } } }
 *
 * Side-effect registers `'search-results'` → `'jf-search-results'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';

interface ResultHit {
  readonly title?: string;
  readonly path?: string;
  readonly snippet?: string;
  readonly score?: number;
}

export class SearchResultsRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
    }
    .results-list {
      display: flex;
      flex-direction: column;
    }
    .row {
      padding: 0.5rem 0.75rem;
      border-bottom: 1px solid var(--border-subtle);
      cursor: default;
    }
    .row:hover {
      background: var(--surface-hover);
    }
    .title {
      font-size: var(--font-size-sm);
      font-weight: 500;
      color: var(--text-primary);
    }
    .path {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      font-family: var(--font-mono);
      margin-top: 0.125rem;
    }
    .snippet {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
      line-height: 1.45;
    }
    .empty {
      padding: 1rem;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const hits = Array.isArray(this.data) ? (this.data as ResultHit[]) : [];
    if (hits.length === 0) {
      return html`<div class="empty">No results.</div>`;
    }
    return html`
      <div class="results-list" role="list">
        ${hits.map(
          (hit) => html`
            <div class="row" role="listitem">
              <div class="title">${hit.title ?? '(untitled)'}</div>
              ${hit.path ? html`<div class="path" title=${hit.path}>${hit.path}</div>` : nothing}
              ${hit.snippet ? html`<div class="snippet">${hit.snippet}</div>` : nothing}
            </div>
          `,
        )}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-search-results')) {
  customElements.define('jf-search-results', SearchResultsRenderer);
}

registerXUiRenderer('search-results', 'jf-search-results');
