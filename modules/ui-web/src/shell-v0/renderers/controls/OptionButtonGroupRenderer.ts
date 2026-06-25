// SPDX-License-Identifier: Apache-2.0
/**
 * OptionButtonGroupRenderer — first-party `x-ui-renderer` hint renderer (569 Fix 1).
 *
 * Renders an enum as the bespoke `.option-btn` grid (the same look SettingsSurface hand-authored),
 * so a DECLARED body matches hand-authored quality — this is what lets a Presentation Declaration
 * be the DEFAULT render of a real surface region with no visual downgrade (the real inversion).
 *
 * Schema fragment that triggers it (the hint lives on the property schema, read by the rank-100
 * x-ui-renderer dispatcher):
 *   { type:'string', enum:['simple','advanced'], 'x-ui-renderer':'option-button-group',
 *     'x-enum-labels': {simple:'Simple', advanced:'Advanced'},
 *     'x-enum-descriptions': {simple:'Standard view', advanced:'Full controls'} }
 *
 * Each option is a native <button> in a role=radiogroup → keyboard-operable (controls-a11y gate).
 * Side-effect registers `'option-button-group'` → `'jf-option-button-group'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';

interface EnumOptionSchema {
  readonly enum?: readonly unknown[];
  readonly 'x-enum-labels'?: Record<string, string>;
  readonly 'x-enum-descriptions'?: Record<string, string>;
}

function titleCase(v: string): string {
  return v.length === 0 ? v : v.charAt(0).toUpperCase() + v.slice(1);
}

export class OptionButtonGroupRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .option-group {
      display: flex;
      gap: 0.5rem;
    }
    .option-btn {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: transparent;
      color: var(--text-primary);
      cursor: pointer;
      font: inherit;
    }
    .option-btn:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    .option-btn.selected {
      border-color: var(--accent-tint);
      background: var(--accent-tint-08);
      color: var(--text-tint);
    }
    .option-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .option-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
      margin-top: 0.25rem;
    }
    .option-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const schema = this.schema as EnumOptionSchema;
    const values = (schema.enum ?? []) as readonly string[];
    const labels = schema['x-enum-labels'] ?? {};
    const descs = schema['x-enum-descriptions'] ?? {};
    const current = typeof this.data === 'string' ? this.data : '';
    return html`
      <div class="option-group" role="radiogroup">
        ${values.map(
          (v) => html`
            <button
              type="button"
              class="option-btn ${current === v ? 'selected' : ''}"
              role="radio"
              aria-checked=${current === v ? 'true' : 'false'}
              ?disabled=${!this.enabled}
              @click=${() => this.onChange(v, this.path)}
            >
              <span class="option-label">${labels[v] ?? titleCase(v)}</span>
              ${descs[v] ? html`<span class="option-desc">${descs[v]}</span>` : nothing}
            </button>
          `,
        )}
      </div>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-option-button-group')
) {
  customElements.define('jf-option-button-group', OptionButtonGroupRenderer);
}

registerXUiRenderer('option-button-group', 'jf-option-button-group');
