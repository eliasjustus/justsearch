// SPDX-License-Identifier: Apache-2.0
/**
 * ToggleSwitchRenderer — first-party `x-ui-renderer` hint renderer (569 Fix 1).
 *
 * Renders a boolean as the bespoke switch toggle (the look SettingsSurface hand-authored), so a
 * DECLARED body matches hand-authored quality — part of making a Presentation Declaration the
 * DEFAULT render of a real surface region with no visual downgrade.
 *
 * Schema fragment that triggers it:
 *   { type:'boolean', title:'High contrast', description:'Better visibility',
 *     'x-ui-renderer':'toggle-switch' }
 *
 * Tempdoc 855 §15.2/§17 R2 — the switch itself is the shared `jf-switch` atom
 * (`components/Switch.ts`); this renderer composes it and adapts its plain-props `change` event to
 * the JsonForms `onChange(value, path)` contract, keeping its own label/description block (the CSS/
 * a11y triad now lives once, in the atom). Side-effect registers `'toggle-switch'` →
 * `'jf-toggle-switch'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';
import '../../components/Switch.js';

export class ToggleSwitchRenderer extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .toggle-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.5rem 0;
    }
    .toggle-label {
      font-size: var(--font-size-sm);
    }
    .toggle-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const on = this.data === true;
    const schema = this.schema as { title?: string; description?: string };
    return html`
      <div class="toggle-row">
        <div>
          <div class="toggle-label">${schema.title ?? ''}</div>
          ${schema.description
            ? html`<div class="toggle-desc">${schema.description}</div>`
            : nothing}
        </div>
        <jf-switch
          .checked=${on}
          .label=${schema.title ?? 'toggle'}
          ?disabled=${!this.enabled}
          @change=${(e: CustomEvent<{ checked: boolean }>) => {
            if (this.enabled) this.onChange(e.detail.checked, this.path);
          }}
        ></jf-switch>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-toggle-switch')) {
  customElements.define('jf-toggle-switch', ToggleSwitchRenderer);
}

registerXUiRenderer('toggle-switch', 'jf-toggle-switch');
