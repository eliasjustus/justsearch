// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import './EnumControl.js';
import type { EnumControl } from './EnumControl.js';

async function mount(props: Partial<EnumControl>): Promise<EnumControl> {
  const el = document.createElement('jf-enum-control') as EnumControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('EnumControl render', () => {
  it('renders a select with options from schema.enum', async () => {
    const el = await mount({
      schema: { type: 'string', enum: ['low', 'medium', 'high'] },
      uischema: { type: 'Control' } as EnumControl['uischema'],
      data: 'medium',
    });
    const select = el.shadowRoot?.querySelector('select') as HTMLSelectElement;
    const optionTexts = Array.from(select.options).map((o) => o.value);
    // First option is the empty placeholder
    expect(optionTexts).toEqual(['', 'low', 'medium', 'high']);
    expect(select.value).toBe('medium');
    el.remove();
  });

  it('change event sends original-typed value (numeric enum)', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'number', enum: [1, 2, 3] },
      uischema: { type: 'Control' } as EnumControl['uischema'],
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const select = el.shadowRoot?.querySelector('select') as HTMLSelectElement;
    select.value = '2';
    select.dispatchEvent(new Event('change'));
    expect(captured[0]).toBe(2); // number, not string '2'
    el.remove();
  });

  it('empty select sends undefined', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'string', enum: ['a', 'b'] },
      uischema: { type: 'Control' } as EnumControl['uischema'],
      data: 'a',
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const select = el.shadowRoot?.querySelector('select') as HTMLSelectElement;
    select.value = '';
    select.dispatchEvent(new Event('change'));
    expect(captured[0]).toBeUndefined();
    el.remove();
  });
});
