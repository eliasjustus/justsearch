// SPDX-License-Identifier: Apache-2.0
/**
 * Number-input control renderer.
 *
 * Per slice 3a.0 §B.A.3: matches JSON schemas with `type: "number"`
 * or `type: "integer"`. Rank: RANK_BASIC_CONTROL.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_BASIC_CONTROL } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';

export class NumberControl extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    label {
      display: block;
      font-size: var(--justsearch-shell-form-label-size, 0.8125rem);
      font-weight: var(--justsearch-shell-form-label-weight, 500);
      margin-block-end: 0.25rem;
    }
    input {
      width: 100%;
      box-sizing: border-box;
      padding: var(--justsearch-shell-form-input-padding, 0.5rem);
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      font: inherit;
      color: inherit;
      background: var(--justsearch-shell-form-input-bg, transparent);
    }
    input:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .error {
      color: var(--justsearch-shell-form-error-color, #c00);
      font-size: var(--font-size-xs);
      margin-block-start: 0.25rem;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }

    const labelText =
      (this.uischema as { label?: string }).label ??
      (this.schema as { title?: string }).title ??
      this.path;

    const value =
      typeof this.data === 'number' && !Number.isNaN(this.data)
        ? String(this.data)
        : '';

    const isInteger = (this.schema as { type?: string }).type === 'integer';
    const stepAttr = isInteger ? '1' : 'any';

    return html`
      <label>
        ${labelText}
        <input
          type="number"
          step=${stepAttr}
          .value=${value}
          ?disabled=${!this.enabled}
          @input=${(e: Event) => {
            const raw = (e.target as HTMLInputElement).value;
            if (raw === '') {
              this.updateData(undefined);
              return;
            }
            const parsed = isInteger ? parseInt(raw, 10) : Number(raw);
            this.updateData(Number.isNaN(parsed) ? undefined : parsed);
          }}
        />
      </label>
      ${this.errors ? html`<div class="error">${this.errors}</div>` : null}
    `;
  }
}

customElements.define('jf-number-control', NumberControl);

/**
 * Tester: matches `type: "number"` or `type: "integer"`.
 */
export const numberControlTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') {
    return -1;
  }
  const t = (schema as { type?: string }).type;
  if (t !== 'number' && t !== 'integer') {
    return -1;
  }
  return RANK_BASIC_CONTROL;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(numberControlTester, 'jf-number-control');
