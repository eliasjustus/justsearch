// @vitest-environment happy-dom

/**
 * Render tests for the Form host component. Covers:
 *  - Renders the dispatched root renderer for the supplied uischema.
 *  - Child onChange events update internal data immutably.
 *  - `form-change` CustomEvent fires after each child change with
 *    the full updated data, modified path, and value.
 */

import { describe, expect, it } from 'vitest';
import './Form.js';
// Side-effect imports for the renderers the Form will dispatch:
import '../renderers/controls/TextControl.js';
import '../renderers/controls/NumberControl.js';
import '../renderers/layouts/VerticalLayout.js';
import { setAtPath } from './Form.js';
import type { Form, FormChangeEventDetail } from './Form.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

async function mountForm(props: Partial<Form>): Promise<Form> {
  const el = document.createElement('jf-form') as Form;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const SCHEMA: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    age: { type: 'integer' },
  },
};

const UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    { type: 'Control', scope: '#/properties/name' },
    { type: 'Control', scope: '#/properties/age' },
  ],
} as UISchemaElement;

describe('Form host render', () => {
  it('dispatches the root uischema and renders child controls', async () => {
    const el = await mountForm({
      schema: SCHEMA,
      uischema: UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    expect(
      el.shadowRoot?.querySelector('jf-vertical-layout'),
    ).not.toBeNull();
    el.remove();
  });

  it('renders an error fallback when no renderer matches the root uischema', async () => {
    const el = await mountForm({
      schema: SCHEMA,
      uischema: { type: 'UnknownRoot' } as UISchemaElement,
      data: {},
    });
    expect(el.shadowRoot?.textContent).toMatch(/no renderer/i);
    el.remove();
  });

  it('emits a form-change event with the updated data on child input', async () => {
    const events: FormChangeEventDetail[] = [];
    const el = await mountForm({
      schema: SCHEMA,
      uischema: UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    el.addEventListener('form-change', ((e: CustomEvent) => {
      events.push(e.detail as FormChangeEventDetail);
    }) as EventListener);

    const layout = el.shadowRoot?.querySelector(
      'jf-vertical-layout',
    ) as HTMLElement;
    const textInput = layout?.shadowRoot
      ?.querySelector('jf-text-control')
      ?.shadowRoot?.querySelector('input') as HTMLInputElement | undefined;
    expect(textInput).toBeDefined();
    if (!textInput) {
      el.remove();
      return;
    }

    textInput.value = 'Grace';
    textInput.dispatchEvent(new Event('input'));

    expect(events).toHaveLength(1);
    expect(events[0]?.value).toBe('Grace');
    expect(events[0]?.path).toBe('name');
    expect(events[0]?.data).toEqual({ name: 'Grace', age: 36 });
    el.remove();
  });

  it('preserves immutability — the original data object is unchanged after mutation', async () => {
    const original = { name: 'Ada', age: 36 };
    const el = await mountForm({
      schema: SCHEMA,
      uischema: UISCHEMA,
      data: original,
      enabled: true,
    });
    let captured: unknown = null;
    el.addEventListener('form-change', ((e: CustomEvent) => {
      captured = (e.detail as FormChangeEventDetail).data;
    }) as EventListener);

    const layout = el.shadowRoot?.querySelector(
      'jf-vertical-layout',
    ) as HTMLElement;
    const textInput = layout?.shadowRoot
      ?.querySelector('jf-text-control')
      ?.shadowRoot?.querySelector('input') as HTMLInputElement | undefined;
    if (!textInput) {
      el.remove();
      return;
    }
    textInput.value = 'Grace';
    textInput.dispatchEvent(new Event('input'));

    expect(original).toEqual({ name: 'Ada', age: 36 });
    expect(captured).not.toBe(original);
    el.remove();
  });
});

describe('setAtPath helper', () => {
  it('returns a new object with the value set at a top-level key', () => {
    const before = { a: 1, b: 2 };
    const after = setAtPath(before, 'a', 99);
    expect(after).toEqual({ a: 99, b: 2 });
    expect(after).not.toBe(before);
  });

  it('descends into nested objects, creating shallow copies along the way', () => {
    const before = { user: { name: 'Ada', age: 36 } };
    const after = setAtPath(before, 'user.name', 'Grace');
    expect(after).toEqual({ user: { name: 'Grace', age: 36 } });
    expect(after.user).not.toBe(before.user);
  });

  it('descends into arrays via numeric index segments', () => {
    const before = { items: ['a', 'b', 'c'] };
    const after = setAtPath(before, 'items.1', 'B');
    expect(after).toEqual({ items: ['a', 'B', 'c'] });
  });

  it('creates intermediate objects when the path does not yet exist', () => {
    const before = {};
    const after = setAtPath(before, 'a.b.c', 99);
    expect(after).toEqual({ a: { b: { c: 99 } } });
  });
});
