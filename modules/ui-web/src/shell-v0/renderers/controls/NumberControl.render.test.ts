// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import './NumberControl.js';
import type { NumberControl } from './NumberControl.js';

async function mount(props: Partial<NumberControl>): Promise<NumberControl> {
  const el = document.createElement('jf-number-control') as NumberControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('NumberControl render', () => {
  it('renders type:number with step="any"', async () => {
    const el = await mount({
      schema: { type: 'number' },
      uischema: { type: 'Control' } as NumberControl['uischema'],
      data: 3.14,
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input.type).toBe('number');
    expect(input.step).toBe('any');
    expect(input.value).toBe('3.14');
    el.remove();
  });

  it('renders type:integer with step="1"', async () => {
    const el = await mount({
      schema: { type: 'integer' },
      uischema: { type: 'Control' } as NumberControl['uischema'],
      data: 42,
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input.step).toBe('1');
    expect(input.value).toBe('42');
    el.remove();
  });

  it('parses input as number; integer schema parses as integer', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'integer' },
      uischema: { type: 'Control' } as NumberControl['uischema'],
      path: 'count',
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    input.value = '7';
    input.dispatchEvent(new Event('input'));
    expect(captured[0]).toBe(7);
    expect(typeof captured[0]).toBe('number');
    el.remove();
  });

  it('empty input sends undefined', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'number' },
      uischema: { type: 'Control' } as NumberControl['uischema'],
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    input.value = '';
    input.dispatchEvent(new Event('input'));
    expect(captured[0]).toBeUndefined();
    el.remove();
  });
});
