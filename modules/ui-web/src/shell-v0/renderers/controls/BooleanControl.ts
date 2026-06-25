// SPDX-License-Identifier: Apache-2.0
/**
 * Boolean (checkbox) control renderer.
 *
 * Per slice 3a.0 §B.A.3: matches JSON schemas with `type: "boolean"`.
 * Rank: RANK_BASIC_CONTROL.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_BASIC_CONTROL } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';

export class BooleanControl extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    label {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: var(--justsearch-shell-form-label-size, 0.8125rem);
      font-weight: var(--justsearch-shell-form-label-weight, 500);
      cursor: pointer;
    }
    label[aria-disabled='true'] {
      cursor: not-allowed;
      opacity: 0.5;
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

    const checked = this.data === true;

    return html`
      <label aria-disabled=${!this.enabled}>
        <input
          type="checkbox"
          .checked=${checked}
          ?disabled=${!this.enabled}
          @change=${(e: Event) =>
            this.updateData((e.target as HTMLInputElement).checked)}
        />
        ${labelText}
      </label>
      ${this.errors ? html`<div class="error">${this.errors}</div>` : null}
    `;
  }
}

customElements.define('jf-boolean-control', BooleanControl);

/**
 * Tester: matches `type: "boolean"`.
 */
export const booleanControlTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') {
    return -1;
  }
  if ((schema as { type?: string }).type !== 'boolean') {
    return -1;
  }
  return RANK_BASIC_CONTROL;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(booleanControlTester, 'jf-boolean-control');
