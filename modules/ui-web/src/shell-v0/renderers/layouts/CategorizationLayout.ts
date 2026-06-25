// SPDX-License-Identifier: Apache-2.0
/**
 * Categorization (tabs) layout renderer.
 *
 * Per slice 3a.0 §B.A.3: matches uischema with
 * `type: "Categorization"`. Each `Category` element under
 * `uischema.elements` becomes a tab; the active tab's children are
 * rendered. Per-instance state (active tab index) is held on the
 * Lit element.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { RANK_LAYOUT } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';
import { createChildRenderer } from './layoutDispatch.js';

type Category = UISchemaElement & {
  elements: UISchemaElement[];
  label?: string;
};

type Categorization = UISchemaElement & {
  elements: Category[];
};

export class CategorizationLayout extends JsonFormsRendererBase {
  static override properties = {
    ...JsonFormsRendererBase.properties,
    activeIndex: { type: Number, state: true },
  } as const;

  /**
   * Index of the currently-active tab. Resets to 0 when uischema changes.
   * Declared without initializer per Lit class-field-shadowing guidance;
   * default 0 assigned in the constructor.
   */
  declare activeIndex: number;

  constructor() {
    super();
    this.activeIndex = 0;
  }

  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .tabs {
      display: flex;
      gap: 0.25rem;
      border-bottom: 1px solid
        var(--justsearch-shell-form-tabs-border, #ddd);
      margin-block-end: 0.75rem;
    }
    .tab {
      padding: 0.5rem 1rem;
      background: transparent;
      border: 1px solid transparent;
      border-bottom: none;
      cursor: pointer;
      font: inherit;
      color: inherit;
    }
    .tab[aria-selected='true'] {
      border-color: var(--justsearch-shell-form-tabs-border, #ddd);
      background: var(--justsearch-shell-form-tab-active-bg, transparent);
      font-weight: 600;
    }
    .tab:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .tab-content {
      display: flex;
      flex-direction: column;
      gap: var(--justsearch-shell-form-vertical-gap, 0.75rem);
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const categories = (this.uischema as Categorization).elements ?? [];
    const safeIndex =
      this.activeIndex >= 0 && this.activeIndex < categories.length
        ? this.activeIndex
        : 0;
    const activeCategory = categories[safeIndex];

    return html`
      <div class="tabs" role="tablist">
        ${categories.map((category, idx) => {
          const labelText = category.label ?? `Tab ${idx + 1}`;
          return html`
            <button
              role="tab"
              class="tab"
              aria-selected=${idx === safeIndex}
              ?disabled=${!this.enabled}
              @click=${() => {
                this.activeIndex = idx;
              }}
            >
              ${labelText}
            </button>
          `;
        })}
      </div>
      <div class="tab-content" role="tabpanel">
        ${activeCategory
          ? (activeCategory.elements ?? []).map((childUischema) =>
              this.renderChild(childUischema),
            )
          : html`<div>No tabs declared.</div>`}
      </div>
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
      return html`<div>
        Categorization: no renderer for child (uischema:
        ${JSON.stringify(childUischema)})
      </div>`;
    }
    return html`${child}`;
  }
}

customElements.define('jf-categorization-layout', CategorizationLayout);

export const categorizationLayoutTester: RendererTester = (
  uischema: UISchemaElement,
  _schema: JsonSchema,
): number => {
  if (uischema.type !== 'Categorization') {
    return -1;
  }
  return RANK_LAYOUT;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(categorizationLayoutTester, 'jf-categorization-layout');
