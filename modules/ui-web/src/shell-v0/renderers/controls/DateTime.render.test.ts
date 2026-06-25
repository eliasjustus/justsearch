// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import './DateControl.js';
import './TimeControl.js';
import type { DateControl } from './DateControl.js';
import type { TimeControl } from './TimeControl.js';

async function mountDate(props: Partial<DateControl>): Promise<DateControl> {
  const el = document.createElement('jf-date-control') as DateControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function mountTime(props: Partial<TimeControl>): Promise<TimeControl> {
  const el = document.createElement('jf-time-control') as TimeControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('DateControl render', () => {
  it('renders input type="date"', async () => {
    const el = await mountDate({
      schema: { type: 'string', format: 'date' } as DateControl['schema'],
      uischema: { type: 'Control' } as DateControl['uischema'],
      data: '2026-05-05',
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input.type).toBe('date');
    expect(input.value).toBe('2026-05-05');
    el.remove();
  });

  it('input event propagates date string', async () => {
    const captured: unknown[] = [];
    const el = await mountDate({
      schema: { type: 'string', format: 'date' } as DateControl['schema'],
      uischema: { type: 'Control' } as DateControl['uischema'],
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    input.value = '2026-12-31';
    input.dispatchEvent(new Event('input'));
    expect(captured[0]).toBe('2026-12-31');
    el.remove();
  });
});

describe('TimeControl render', () => {
  it('renders input type="time"', async () => {
    const el = await mountTime({
      schema: { type: 'string', format: 'time' } as TimeControl['schema'],
      uischema: { type: 'Control' } as TimeControl['uischema'],
      data: '14:30',
    });
    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input.type).toBe('time');
    expect(input.value).toBe('14:30');
    el.remove();
  });
});
