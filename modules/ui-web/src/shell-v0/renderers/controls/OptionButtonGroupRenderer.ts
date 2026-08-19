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
 * Tempdoc 855 §15.2/§17 R2 — the WAI-ARIA radiogroup keyboard model (ArrowLeft/Up = previous+select,
 * ArrowRight/Down = next+select, Home/End = first/last+select, wrapping, roving tabindex with focus
 * following selection) lives HERE, once — neither of this codebase's two prior radiogroup precedents
 * (this renderer, `AutonomyDial`) implemented it before (both were click-only, gate-passing via
 * native `<button>` but not the real keyboard pattern).
 *
 * Plain-props path (855 §17 R2) — an ordinary Lit template can drive this element WITHOUT the
 * JsonForms schema/onChange plumbing: set `.options=${[{value,label,description?,icon?}, …]}` and
 * `.value=${current}`; it emits `CustomEvent('change', {detail:{value}})` instead of calling
 * `onChange`. The JsonForms (schema-driven) path is unchanged and takes priority when `schema.enum`
 * is present — a consumer uses one path or the other, never both. The optional per-option `icon`
 * preserves the hand-authored SettingsSurface pickers' leading icon (Simple/Detailed,
 * System/Dark/Light, …) — omitting it would be a visual downgrade the composing surface's
 * `option-btn` idiom never had.
 *
 * Side-effect registers `'option-button-group'` → `'jf-option-button-group'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';
import { icon, type IconName } from '../../components/Icon.js';

interface EnumOptionSchema {
  readonly enum?: readonly unknown[];
  readonly 'x-enum-labels'?: Record<string, string>;
  readonly 'x-enum-descriptions'?: Record<string, string>;
}

/** Plain-props option shape (855 §17 R2 — the non-JsonForms usage path). */
export interface OptionButtonGroupOption {
  readonly value: string;
  readonly label: string;
  readonly description?: string;
  readonly icon?: IconName;
}

function titleCase(v: string): string {
  return v.length === 0 ? v : v.charAt(0).toUpperCase() + v.slice(1);
}

export class OptionButtonGroupRenderer extends JsonFormsRendererBase {
  static override properties = {
    ...JsonFormsRendererBase.properties,
    options: { attribute: false },
    value: { type: String },
  };

  /** Plain-props option list (855 §17 R2). Non-empty ⇒ plain-props mode. */
  declare options: readonly OptionButtonGroupOption[];
  /** Plain-props current value — used only when `options` is set. */
  declare value: string;

  constructor() {
    super();
    this.options = [];
    this.value = '';
  }

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
    // 855 §17 R2 — a non-empty `options` list is the plain-props path; the JsonForms `schema.enum`
    // path is used otherwise. A consumer picks one or the other, never both.
    const plainMode = this.options.length > 0;
    let values: readonly string[];
    let labels: Record<string, string>;
    let descs: Record<string, string>;
    let icons: Record<string, IconName>;
    let current: string;
    if (plainMode) {
      values = this.options.map((o) => o.value);
      labels = Object.fromEntries(this.options.map((o) => [o.value, o.label]));
      descs = Object.fromEntries(
        this.options
          .filter((o): o is OptionButtonGroupOption & { description: string } => o.description !== undefined)
          .map((o) => [o.value, o.description]),
      );
      icons = Object.fromEntries(
        this.options
          .filter((o): o is OptionButtonGroupOption & { icon: IconName } => o.icon !== undefined)
          .map((o) => [o.value, o.icon]),
      );
      current = this.value;
    } else {
      const schema = this.schema as EnumOptionSchema;
      values = (schema.enum ?? []) as readonly string[];
      labels = schema['x-enum-labels'] ?? {};
      descs = schema['x-enum-descriptions'] ?? {};
      icons = {};
      current = typeof this.data === 'string' ? this.data : '';
    }
    // Roving tabindex (WAI-ARIA radiogroup pattern): the selected option is the one tab stop; if
    // nothing matches `current`, the first option holds it so the group stays reachable.
    const currentIndex = values.indexOf(current);
    const rovingIndex = currentIndex >= 0 ? currentIndex : 0;

    const select = (v: string): void => {
      if (!this.enabled) return;
      if (plainMode) {
        this.value = v;
        this.dispatchEvent(
          new CustomEvent('change', { detail: { value: v }, bubbles: true, composed: true }),
        );
      } else {
        this.onChange(v, this.path);
      }
    };

    // ArrowLeft/Up = previous+select, ArrowRight/Down = next+select (wrapping), Home/End =
    // first/last+select; focus follows the newly-selected button (855 §17 R2).
    const onKeydown = (e: KeyboardEvent, index: number): void => {
      if (!this.enabled || values.length === 0) return;
      let target: number;
      switch (e.key) {
        case 'ArrowLeft':
        case 'ArrowUp':
          target = (index - 1 + values.length) % values.length;
          break;
        case 'ArrowRight':
        case 'ArrowDown':
          target = (index + 1) % values.length;
          break;
        case 'Home':
          target = 0;
          break;
        case 'End':
          target = values.length - 1;
          break;
        default:
          return;
      }
      e.preventDefault();
      const nextValue = values[target];
      if (nextValue === undefined) return;
      select(nextValue);
      const buttons = this.renderRoot.querySelectorAll<HTMLButtonElement>('.option-btn');
      buttons[target]?.focus();
    };

    return html`
      <div class="option-group" role="radiogroup">
        ${values.map(
          (v, i) => html`
            <button
              type="button"
              class="option-btn ${current === v ? 'selected' : ''}"
              role="radio"
              aria-checked=${current === v ? 'true' : 'false'}
              tabindex=${i === rovingIndex ? '0' : '-1'}
              ?disabled=${!this.enabled}
              @click=${() => select(v)}
              @keydown=${(e: KeyboardEvent) => onKeydown(e, i)}
            >
              ${icons[v] ? icon({ name: icons[v], size: 18 }) : nothing}
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
