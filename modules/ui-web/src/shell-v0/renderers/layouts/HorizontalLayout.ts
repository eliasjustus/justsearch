// SPDX-License-Identifier: Apache-2.0
/**
 * Horizontal layout renderer.
 *
 * Per slice 3a.0 §B.A.3: matches uischema with `type: "HorizontalLayout"`.
 * Lays children out in a row with equal flex weights.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_LAYOUT } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';
import { createChildRenderer } from './layoutDispatch.js';

type LayoutUISchema = UISchemaElement & { elements: UISchemaElement[] };

export class HorizontalLayout extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: flex;
      flex-direction: row;
      gap: var(--justsearch-shell-form-horizontal-gap, 0.75rem);
      align-items: flex-start;
    }
    ::slotted(*),
    .child {
      flex: 1 1 0;
      min-width: 0;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const elements = (this.uischema as LayoutUISchema).elements ?? [];
    return html`
      ${elements.map((childUischema) => this.renderChild(childUischema))}
    `;
  }

  private renderChild(childUischema: UISchemaElement): TemplateResult {
    const child = createChildRenderer(
      childUischema,
      this.schema,
      this.path,
      this.data,
      this.enabled,
      this.onChange,
    );
    if (!child) {
      return html`<div class="child">
        Layout: no renderer for child (uischema:
        ${JSON.stringify(childUischema)})
      </div>`;
    }
    return html`<div class="child">${child}</div>`;
  }
}

customElements.define('jf-horizontal-layout', HorizontalLayout);

export const horizontalLayoutTester: RendererTester = (
  uischema: UISchemaElement,
  _schema: JsonSchema,
): number => {
  if (uischema.type !== 'HorizontalLayout') {
    return -1;
  }
  return RANK_LAYOUT;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(horizontalLayoutTester, 'jf-horizontal-layout');
