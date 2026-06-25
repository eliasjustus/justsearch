// SPDX-License-Identifier: Apache-2.0
/**
 * ShortcutsTableRenderer — first-party `x-ui-renderer` hint renderer (569 §15 / the Help rollout).
 *
 * Renders a keyboard-shortcuts array (`{ keys, desc }[]`) as the bespoke description+`<kbd>` rows the
 * HelpSurface hand-authored — so a DECLARED Help reference region matches the hand-authored look. The
 * author composes the data; the engine derives the operable, contrast-safe rendering (Move 3).
 *
 * Schema fragment (hint on the array property):
 *   shortcuts: { type:'array', 'x-ui-renderer':'shortcuts-table', items:{ properties:{ keys, desc } } }
 *
 * Side-effect registers `'shortcuts-table'` → `'jf-shortcuts-table'` at module load.
 */
import { html, css, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './xUiRendererRegistry.js';

interface ShortcutRow {
  readonly keys?: string;
  readonly desc?: string;
}

export class ShortcutsTableRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
    }
    .shortcut-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.3rem 0;
      border-bottom: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
    }
    .shortcut-row span {
      color: var(--text-secondary);
    }
    kbd {
      flex: none;
      padding: 0.1rem 0.4rem;
      border-radius: 0.25rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      color: var(--text-primary);
      font-family: var(--font-mono);
      font-size: var(--font-size-xs);
    }
    .empty {
      padding: 0.5rem 0;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const rows = Array.isArray(this.data) ? (this.data as ShortcutRow[]) : [];
    if (rows.length === 0) return html`<div class="empty">No shortcuts.</div>`;
    return html`<div role="list">
      ${rows.map(
        (r) => html`<div class="shortcut-row" role="listitem">
          <span>${r.desc ?? ''}</span><kbd>${r.keys ?? ''}</kbd>
        </div>`,
      )}
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-shortcuts-table')) {
  customElements.define('jf-shortcuts-table', ShortcutsTableRenderer);
}

registerXUiRenderer('shortcuts-table', 'jf-shortcuts-table');
