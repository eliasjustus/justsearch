// SPDX-License-Identifier: Apache-2.0
/**
 * ToggleSwitchRenderer — first-party `x-ui-renderer` hint renderer (569 Fix 1).
 *
 * Renders a boolean as the bespoke `.switch` toggle (the look SettingsSurface hand-authored), so a
 * DECLARED body matches hand-authored quality — part of making a Presentation Declaration the
 * DEFAULT render of a real surface region with no visual downgrade.
 *
 * Schema fragment that triggers it:
 *   { type:'boolean', title:'High contrast', description:'Better visibility',
 *     'x-ui-renderer':'toggle-switch' }
 *
 * The switch is a role=switch element with tabindex + keydown (space/enter) — the keyboard-operable
 * triad the controls-a11y gate requires. The "on" knob uses --accent-on-tint (the 558 contrast-
 * derived on-colour), so it is both readable and token-based (no bare colour literal).
 * Side-effect registers `'toggle-switch'` → `'jf-toggle-switch'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';

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
    .switch {
      width: 2.5rem;
      height: 1.25rem;
      border-radius: 9999px;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      position: relative;
      cursor: pointer;
      transition: background var(--duration-fast) var(--ease-standard);
      flex: none;
    }
    .switch::after {
      content: '';
      position: absolute;
      top: 1px;
      left: 1px;
      width: 1rem;
      height: 1rem;
      border-radius: 50%;
      background: var(--text-secondary);
      transition: left var(--duration-fast) var(--ease-standard), background var(--duration-fast) var(--ease-standard);
    }
    .switch.on {
      background: var(--accent-tint);
      border-color: var(--accent-tint);
    }
    .switch.on::after {
      left: 1.25rem;
      background: var(--accent-on-tint);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const on = this.data === true;
    const schema = this.schema as { title?: string; description?: string };
    const toggle = (): void => {
      if (this.enabled) this.onChange(!on, this.path);
    };
    return html`
      <div class="toggle-row">
        <div>
          <div class="toggle-label">${schema.title ?? ''}</div>
          ${schema.description
            ? html`<div class="toggle-desc">${schema.description}</div>`
            : nothing}
        </div>
        <div
          class="switch ${on ? 'on' : ''}"
          role="switch"
          aria-checked=${on ? 'true' : 'false'}
          aria-label=${schema.title ?? 'toggle'}
          tabindex=${this.enabled ? '0' : '-1'}
          @click=${toggle}
          @keydown=${(e: KeyboardEvent) => {
            if (e.key === ' ' || e.key === 'Enter') {
              e.preventDefault();
              toggle();
            }
          }}
        ></div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-toggle-switch')) {
  customElements.define('jf-toggle-switch', ToggleSwitchRenderer);
}

registerXUiRenderer('toggle-switch', 'jf-toggle-switch');
