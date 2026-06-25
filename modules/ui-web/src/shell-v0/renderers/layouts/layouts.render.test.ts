// @vitest-environment happy-dom

/**
 * Render tests for the 4 layout renderers. These verify:
 *  - VerticalLayout / HorizontalLayout / GroupLayout iterate
 *    `uischema.elements` and dispatch via `createChildRenderer`,
 *    actually instantiating control children with the right scope-
 *    resolved schema + path.
 *  - CategorizationLayout renders tabs and switches active tab on
 *    click, replacing the rendered child set.
 *  - Per-child onChange propagation flows back through the layout
 *    to the test harness, with the correct property path.
 */

import { describe, expect, it } from 'vitest';
import './VerticalLayout.js';
import './HorizontalLayout.js';
import './GroupLayout.js';
import './CategorizationLayout.js';
import '../controls/TextControl.js';
import '../controls/NumberControl.js';
import '../controls/BooleanControl.js';
import type { VerticalLayout } from './VerticalLayout.js';
import type { HorizontalLayout } from './HorizontalLayout.js';
import type { GroupLayout } from './GroupLayout.js';
import type { CategorizationLayout } from './CategorizationLayout.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

async function mount<T extends HTMLElement>(
  tag: string,
  props: Record<string, unknown>,
): Promise<T> {
  const el = document.createElement(tag) as T;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await (el as unknown as { updateComplete: Promise<void> }).updateComplete;
  return el;
}

const TWO_FIELD_SCHEMA: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    age: { type: 'integer' },
  },
};

const TWO_CONTROL_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    { type: 'Control', scope: '#/properties/name' },
    { type: 'Control', scope: '#/properties/age' },
  ],
} as UISchemaElement;

describe('VerticalLayout render', () => {
  it('iterates elements and instantiates per-child renderers via dispatch', async () => {
    const el = await mount<VerticalLayout>('jf-vertical-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: TWO_CONTROL_UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    expect(el.shadowRoot?.querySelector('jf-text-control')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('jf-number-control')).not.toBeNull();
    el.remove();
  });

  it('child onChange surfaces with the property path', async () => {
    const captured: Array<{ value: unknown; path: string }> = [];
    const el = await mount<VerticalLayout>('jf-vertical-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: TWO_CONTROL_UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: true,
      onChange: (v: unknown, p: string) => captured.push({ value: v, path: p }),
    });
    const childInput = el.shadowRoot
      ?.querySelector('jf-text-control')
      ?.shadowRoot?.querySelector('input') as HTMLInputElement | undefined;
    expect(childInput).toBeDefined();
    if (!childInput) {
      el.remove();
      return;
    }
    childInput.value = 'Grace';
    childInput.dispatchEvent(new Event('input'));
    expect(captured).toHaveLength(1);
    expect(captured[0]?.value).toBe('Grace');
    expect(captured[0]?.path).toBe('name');
    el.remove();
  });

  it('renders an error fallback when no renderer matches', async () => {
    const el = await mount<VerticalLayout>('jf-vertical-layout', {
      schema: { type: 'object', properties: {} },
      uischema: {
        type: 'VerticalLayout',
        elements: [{ type: 'UnknownControl' }],
      } as UISchemaElement,
      data: {},
      enabled: true,
    });
    expect(el.shadowRoot?.textContent).toMatch(/no renderer/i);
    el.remove();
  });
});

describe('HorizontalLayout render', () => {
  it('renders children inline (presence test)', async () => {
    const el = await mount<HorizontalLayout>('jf-horizontal-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: {
        ...TWO_CONTROL_UISCHEMA,
        type: 'HorizontalLayout',
      } as UISchemaElement,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    expect(el.shadowRoot?.querySelector('jf-text-control')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('jf-number-control')).not.toBeNull();
    el.remove();
  });
});

describe('GroupLayout render', () => {
  it('renders a fieldset with the group label', async () => {
    const el = await mount<GroupLayout>('jf-group-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: {
        type: 'Group',
        label: 'Personal',
        elements: (
          TWO_CONTROL_UISCHEMA as UISchemaElement & {
            elements: UISchemaElement[];
          }
        ).elements,
      } as unknown as UISchemaElement,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    const fieldset = el.shadowRoot?.querySelector('fieldset');
    expect(fieldset).not.toBeNull();
    const legend = fieldset?.querySelector('legend');
    expect(legend?.textContent?.trim()).toBe('Personal');
    expect(fieldset?.querySelector('jf-text-control')).not.toBeNull();
    el.remove();
  });
});

describe('CategorizationLayout render', () => {
  const CATEGORIZATION_UISCHEMA = {
    type: 'Categorization',
    elements: [
      {
        type: 'Category',
        label: 'Profile',
        elements: [{ type: 'Control', scope: '#/properties/name' }],
      },
      {
        type: 'Category',
        label: 'Stats',
        elements: [{ type: 'Control', scope: '#/properties/age' }],
      },
    ],
  } as unknown as UISchemaElement;

  it('renders one tab per Category and shows the first tab by default', async () => {
    const el = await mount<CategorizationLayout>('jf-categorization-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: CATEGORIZATION_UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    const tabs = el.shadowRoot?.querySelectorAll('button[role="tab"]');
    expect(tabs?.length).toBe(2);
    expect(tabs?.[0]?.textContent?.trim()).toBe('Profile');
    expect(tabs?.[1]?.textContent?.trim()).toBe('Stats');
    // First tab's child is the text control (Profile → name)
    expect(el.shadowRoot?.querySelector('jf-text-control')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('jf-number-control')).toBeNull();
    el.remove();
  });

  it('switches active tab on click and replaces children', async () => {
    const el = await mount<CategorizationLayout>('jf-categorization-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: CATEGORIZATION_UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: true,
    });
    const tabs = el.shadowRoot?.querySelectorAll('button[role="tab"]');
    (tabs?.[1] as HTMLButtonElement | undefined)?.click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('jf-number-control')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('jf-text-control')).toBeNull();
    el.remove();
  });

  it('disables tab buttons when layout is disabled', async () => {
    const el = await mount<CategorizationLayout>('jf-categorization-layout', {
      schema: TWO_FIELD_SCHEMA,
      uischema: CATEGORIZATION_UISCHEMA,
      data: { name: 'Ada', age: 36 },
      enabled: false,
    });
    const tabs = el.shadowRoot?.querySelectorAll('button[role="tab"]');
    for (const t of Array.from(tabs ?? [])) {
      expect((t as HTMLButtonElement).disabled).toBe(true);
    }
    el.remove();
  });
});
