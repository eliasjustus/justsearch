// @vitest-environment happy-dom

/**
 * EnterActionPickerRenderer unit tests — Tempdoc 543 §20.7 B1.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import './EnterActionPickerRenderer.js';
import type { EnterActionPickerRenderer } from './EnterActionPickerRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

function mountPicker(): EnterActionPickerRenderer {
  const el = document.createElement(
    'jf-enter-action-picker',
  ) as EnterActionPickerRenderer;
  document.body.appendChild(el);
  return el;
}

async function awaitRender(el: EnterActionPickerRenderer): Promise<void> {
  await el.updateComplete;
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('<jf-enter-action-picker> (§20.7 B1)', () => {
  it('registers x-ui-renderer hint enter-action-select → jf-enter-action-picker', () => {
    expect(getXUiRendererTag('enter-action-select')).toBe(
      'jf-enter-action-picker',
    );
  });

  it('renders a select with the schema-declared enum values', async () => {
    const el = mountPicker();
    el.schema = {
      type: 'string',
      enum: ['open', 'reveal', 'preview'],
    };
    el.uischema = { type: 'Control' };
    el.data = 'open';
    el.enabled = true;
    el.visible = true;
    el.path = 'defaultAction';
    el.onChange = () => {};
    await awaitRender(el);
    const select = el.shadowRoot?.querySelector(
      'select[data-testid="enter-action-picker"]',
    ) as HTMLSelectElement | null;
    expect(select).not.toBeNull();
    expect(select?.disabled).toBe(false);
    const options = Array.from(
      el.shadowRoot?.querySelectorAll('option') ?? [],
    ).map((o) => o.value);
    expect(options).toEqual(['open', 'reveal', 'preview']);
  });

  it('emits onChange with the selected value at the given path', async () => {
    const calls: Array<{ value: unknown; path: string }> = [];
    const el = mountPicker();
    el.schema = {
      type: 'string',
      enum: ['open', 'reveal', 'preview'],
    };
    el.uischema = { type: 'Control' };
    el.data = 'open';
    el.enabled = true;
    el.visible = true;
    el.path = 'defaultAction';
    el.onChange = (value, path) => calls.push({ value, path });
    await awaitRender(el);
    const select = el.shadowRoot?.querySelector(
      'select',
    ) as HTMLSelectElement;
    select.value = 'reveal';
    select.dispatchEvent(new Event('change'));
    expect(calls).toEqual([{ value: 'reveal', path: 'defaultAction' }]);
  });

  it('disables when enabled=false', async () => {
    const el = mountPicker();
    el.schema = { type: 'string', enum: ['open', 'reveal', 'preview'] };
    el.uischema = { type: 'Control' };
    el.data = 'open';
    el.enabled = false;
    el.visible = true;
    el.path = 'defaultAction';
    el.onChange = () => {};
    await awaitRender(el);
    const select = el.shadowRoot?.querySelector(
      'select',
    ) as HTMLSelectElement;
    expect(select.disabled).toBe(true);
  });

  it('returns empty render when visible=false', async () => {
    const el = mountPicker();
    el.schema = { type: 'string' };
    el.uischema = { type: 'Control' };
    el.data = 'open';
    el.enabled = true;
    el.visible = false;
    el.path = 'defaultAction';
    el.onChange = () => {};
    await awaitRender(el);
    expect(el.shadowRoot?.querySelector('select')).toBeNull();
  });
});
