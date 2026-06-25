// SPDX-License-Identifier: Apache-2.0
/**
 * Text-input control renderer.
 *
 * Per slice 3a.0 §B.A.3: matches JSON schemas with `type: "string"`
 * and no specialized format. Rank: RANK_BASIC_CONTROL. Specialized
 * string controls (date, time, etc.) override at higher ranks.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_BASIC_CONTROL } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';

export class TextControl extends JsonFormsRendererBase {
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
    input,
    textarea {
      width: 100%;
      box-sizing: border-box;
      padding: var(--justsearch-shell-form-input-padding, 0.5rem);
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      font: inherit;
      color: inherit;
      background: var(--justsearch-shell-form-input-bg, transparent);
    }
    textarea {
      resize: vertical;
      min-height: 4.5rem;
    }
    input:disabled,
    textarea:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .error {
      color: var(--justsearch-shell-form-error-color, #c00);
      font-size: var(--font-size-xs);
      margin-block-start: 0.25rem;
    }
  `;

  override render(): TemplateResult | typeof window['nothing' & keyof Window] {
    if (!this.visible) {
      return html``;
    }

    const labelText =
      (this.uischema as { label?: string }).label ??
      (this.schema as { title?: string }).title ??
      this.path;

    const value = typeof this.data === 'string' ? this.data : '';

    // 543-fwd #2 — JSON Forms convention: a Control with `options.multi` renders a
    // multiline <textarea> (e.g. the import-archive JSON-paste field) instead of a
    // single-line <input>. Same value binding either way.
    const multiline = Boolean(
      (this.uischema as { options?: { multi?: boolean } }).options?.multi,
    );

    const control = multiline
      ? html`<textarea
          rows="4"
          .value=${value}
          ?disabled=${!this.enabled}
          @input=${(e: Event) =>
            this.updateData((e.target as HTMLTextAreaElement).value)}
        ></textarea>`
      : html`<input
          type="text"
          .value=${value}
          ?disabled=${!this.enabled}
          @input=${(e: Event) =>
            this.updateData((e.target as HTMLInputElement).value)}
        />`;

    return html`
      <label> ${labelText} ${control} </label>
      ${this.errors ? html`<div class="error">${this.errors}</div>` : null}
    `;
  }
}

customElements.define('jf-text-control', TextControl);

/**
 * Tester: matches `type: "string"` schemas with no `format`.
 * Specialized format renderers (date, time, etc.) win at higher rank.
 */
export const textControlTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') {
    return -1;
  }
  // Resolve the schema fragment the Control points to.
  // Per JSON Forms convention, the actual schema is at uischema.scope.
  // For a basic-rank tester we assume the schema fragment is already
  // resolved (host has dereferenced) — sufficient for V1.
  if (schema.type !== 'string' || (schema as { format?: string }).format) {
    return -1;
  }
  return RANK_BASIC_CONTROL;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(textControlTester, 'jf-text-control');
