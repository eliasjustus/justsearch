// SPDX-License-Identifier: Apache-2.0
/**
 * Form — host element wrapping the slice 3a.0 renderer registry.
 *
 * Accepts a JsonSchema + UISchema + initial data. Dispatches the
 * root uischema element to the registered renderer. Owns the data
 * store: child onChange events update the internal data, then a
 * `form-change` CustomEvent bubbles out so Lit/React parents can
 * subscribe.
 *
 * Usage:
 *   <jf-form
 *     .schema=${schema}
 *     .uischema=${uischema}
 *     .data=${initialData}
 *     @form-change=${(e) => handleChange(e.detail.data)}
 *   ></jf-form>
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { createChildRenderer } from '../renderers/layouts/layoutDispatch.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';

/**
 * Emitted by `<jf-form>` after every child onChange event. The
 * `detail.data` field carries the full updated data object; the
 * `detail.path` field reports which path was modified.
 */
export interface FormChangeEventDetail {
  data: unknown;
  path: string;
  value: unknown;
}

export class Form extends JfElement {
  static override properties = {
    schema: { attribute: false },
    uischema: { attribute: false },
    data: { attribute: false },
    enabled: { type: Boolean },
    userConfig: { attribute: false },
  } as const;

  declare schema: JsonSchema;
  declare uischema: UISchemaElement;
  declare data: Record<string, unknown>;
  declare enabled: boolean;
  /** Slice 3a.1.7 — threaded through to child dispatch + renderers. */
  declare userConfig: RendererUserConfig | undefined;

  static styles = css`
    :host {
      display: block;
    }
  `;

  constructor() {
    super();
    this.schema = {};
    this.uischema = { type: 'Control' } as UISchemaElement;
    this.data = {};
    this.enabled = true;
    this.userConfig = undefined;
  }

  override render(): TemplateResult {
    const child = createChildRenderer(
      this.uischema,
      this.schema,
      '',
      this.data,
      this.enabled,
      this.handleChildChange,
      this.userConfig,
    );
    if (!child) {
      return html`<div>
        Form: no renderer for the root uischema element (type:
        ${(this.uischema as { type?: string }).type ?? 'undefined'}).
      </div>`;
    }
    return html`${child}`;
  }

  private readonly handleChildChange = (
    value: unknown,
    path: string,
  ): void => {
    const nextData = setAtPath(this.data, path, value);
    this.data = nextData;
    this.dispatchEvent(
      new CustomEvent<FormChangeEventDetail>('form-change', {
        detail: { data: nextData, path, value },
        bubbles: true,
        composed: true,
      }),
    );
  };
}

customElements.define('jf-form', Form);

/**
 * Immutably set a value at a dot-separated path, returning a new
 * data object. V1 follows the same dot-path convention as
 * `walkDataPath` in `layoutDispatch`. Supports array indexing
 * (numeric segments).
 */
export function setAtPath(
  data: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  if (!path) {
    // Root replacement — only supported when value is itself an object.
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return value as Record<string, unknown>;
    }
    return data;
  }
  const parts = path.split('.');
  return setAtPathRecursive(data, parts, 0, value) as Record<string, unknown>;
}

function setAtPathRecursive(
  current: unknown,
  parts: string[],
  i: number,
  value: unknown,
): unknown {
  if (i >= parts.length) {
    return value;
  }
  const key = parts[i] ?? '';
  const idx = Number(key);
  const isArrayIndex = Number.isInteger(idx) && Array.isArray(current);
  if (isArrayIndex) {
    const arr = (current as unknown[]).slice();
    arr[idx] = setAtPathRecursive(arr[idx], parts, i + 1, value);
    return arr;
  }
  const obj: Record<string, unknown> =
    current && typeof current === 'object' && !Array.isArray(current)
      ? { ...(current as Record<string, unknown>) }
      : {};
  obj[key] = setAtPathRecursive(obj[key], parts, i + 1, value);
  return obj;
}
