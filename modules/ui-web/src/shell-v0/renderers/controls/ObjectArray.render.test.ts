// @vitest-environment happy-dom

/**
 * Render tests for the structural Object/Array controls. These exercise
 * the recursive dispatch path: ObjectControl iterates schema.properties,
 * ArrayControl iterates the data array, and each child is dispatched
 * via the registry. The tests verify that the right child custom-element
 * is created with the right reactive props.
 */

import { describe, expect, it } from 'vitest';
import './ObjectControl.js';
import './ArrayControl.js';
import './TextControl.js';
import './NumberControl.js';
import type { ObjectControl } from './ObjectControl.js';
import type { ArrayControl } from './ArrayControl.js';

async function mountObject(props: Partial<ObjectControl>): Promise<ObjectControl> {
  const el = document.createElement('jf-object-control') as ObjectControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function mountArray(props: Partial<ArrayControl>): Promise<ArrayControl> {
  const el = document.createElement('jf-array-control') as ArrayControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('ObjectControl render', () => {
  it('iterates properties and instantiates per-property child elements', async () => {
    const el = await mountObject({
      schema: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          age: { type: 'integer' },
        },
      },
      uischema: { type: 'Control' } as ObjectControl['uischema'],
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });

    const root = el.shadowRoot;
    expect(root?.querySelector('jf-text-control')).not.toBeNull();
    expect(root?.querySelector('jf-number-control')).not.toBeNull();
    el.remove();
  });

  it('child onChange propagates with property path', async () => {
    const captured: Array<{ value: unknown; path: string }> = [];
    const el = await mountObject({
      schema: {
        type: 'object',
        properties: { name: { type: 'string' } },
      },
      uischema: { type: 'Control' } as ObjectControl['uischema'],
      path: 'user',
      data: { name: 'Ada' },
      enabled: true,
      onChange: (value, path) => captured.push({ value, path }),
    });

    // Locate the child text control via its rendered <input>
    const childInput = el.shadowRoot?.querySelector('jf-text-control')?.shadowRoot
      ?.querySelector('input') as HTMLInputElement | undefined;
    expect(childInput).toBeDefined();
    if (!childInput) {
      el.remove();
      return;
    }
    childInput.value = 'Grace';
    childInput.dispatchEvent(new Event('input'));

    expect(captured).toHaveLength(1);
    expect(captured[0]?.value).toBe('Grace');
    expect(captured[0]?.path).toBe('user.name');
    el.remove();
  });
});

describe('ArrayControl render', () => {
  it('iterates items and instantiates per-item child elements', async () => {
    const el = await mountArray({
      schema: {
        type: 'array',
        items: { type: 'string' },
      } as ArrayControl['schema'],
      uischema: { type: 'Control' } as ArrayControl['uischema'],
      data: ['a', 'b', 'c'],
      enabled: true,
    });

    const items = el.shadowRoot?.querySelectorAll('.array-item');
    expect(items?.length).toBe(3);
    const textControls = el.shadowRoot?.querySelectorAll('jf-text-control');
    expect(textControls?.length).toBe(3);
    el.remove();
  });

  it('add button extends the data array with default value', async () => {
    const captured: unknown[] = [];
    const el = await mountArray({
      schema: {
        type: 'array',
        items: { type: 'string' },
      } as ArrayControl['schema'],
      uischema: { type: 'Control' } as ArrayControl['uischema'],
      data: ['x'],
      enabled: true,
      onChange: (v) => captured.push(v),
    });

    const addBtn = el.shadowRoot?.querySelector(
      '.add-btn',
    ) as HTMLButtonElement;
    addBtn.click();
    expect(captured).toHaveLength(1);
    expect(captured[0]).toEqual(['x', '']); // default for type:string is ''
    el.remove();
  });

  it('remove button drops the item at the given index', async () => {
    const captured: unknown[] = [];
    const el = await mountArray({
      schema: {
        type: 'array',
        items: { type: 'string' },
      } as ArrayControl['schema'],
      uischema: { type: 'Control' } as ArrayControl['uischema'],
      data: ['a', 'b', 'c'],
      enabled: true,
      onChange: (v) => captured.push(v),
    });

    // Click the remove button on the second item (index 1)
    const removeBtns = el.shadowRoot?.querySelectorAll('button:not(.add-btn)');
    expect(removeBtns?.length).toBe(3);
    (removeBtns?.[1] as HTMLButtonElement | undefined)?.click();
    expect(captured).toHaveLength(1);
    expect(captured[0]).toEqual(['a', 'c']);
    el.remove();
  });
});
