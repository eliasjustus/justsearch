// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import './BooleanControl.js';
import type { BooleanControl } from './BooleanControl.js';

async function mount(props: Partial<BooleanControl>): Promise<BooleanControl> {
  const el = document.createElement('jf-boolean-control') as BooleanControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('BooleanControl render', () => {
  it('renders a checkbox reflecting data', async () => {
    const el = await mount({
      schema: { type: 'boolean' },
      uischema: { type: 'Control' } as BooleanControl['uischema'],
      data: true,
    });
    const input = el.shadowRoot?.querySelector(
      'input[type="checkbox"]',
    ) as HTMLInputElement;
    expect(input.checked).toBe(true);
    el.remove();
  });

  it('checkbox change → onChange with new boolean', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'boolean' },
      uischema: { type: 'Control' } as BooleanControl['uischema'],
      path: 'optIn',
      data: false,
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    input.checked = true;
    input.dispatchEvent(new Event('change'));
    expect(captured[0]).toBe(true);
    el.remove();
  });

  it('respects enabled=false (label aria-disabled)', async () => {
    const el = await mount({
      schema: { type: 'boolean' },
      uischema: { type: 'Control' } as BooleanControl['uischema'],
      enabled: false,
    });
    const label = el.shadowRoot?.querySelector('label');
    expect(label?.getAttribute('aria-disabled')).toBe('true');
    el.remove();
  });
});
