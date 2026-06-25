// @vitest-environment happy-dom

/**
 * Render tests for TextControl (slice 3a.1 phase 1).
 *
 * Covers: DOM mount, prop reflection, input event → onChange path,
 * enabled/visible visibility/disable behavior. Per slice 3a.0 §B.C
 * Sev 2 finding, render tests were deferred from 3a.0 to here.
 */

import { describe, expect, it } from 'vitest';
import './TextControl.js'; // side-effect: customElements.define
import type { TextControl } from './TextControl.js';

async function mount(props: Partial<TextControl>): Promise<TextControl> {
  const el = document.createElement('jf-text-control') as TextControl;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('TextControl render', () => {
  it('renders an input with the schema value', async () => {
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control', label: 'Email' } as TextControl['uischema'],
      data: 'hello@example.com',
      enabled: true,
      visible: true,
    });

    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input).toBeDefined();
    expect(input.type).toBe('text');
    expect(input.value).toBe('hello@example.com');
    el.remove();
  });

  it('renders empty input when data is undefined', async () => {
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control' } as TextControl['uischema'],
      data: undefined,
    });

    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input.value).toBe('');
    el.remove();
  });

  it('input event propagates to onChange', async () => {
    const captured: Array<{ value: unknown; path: string }> = [];
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control' } as TextControl['uischema'],
      path: 'email',
      data: '',
      enabled: true,
      onChange: (value, path) => captured.push({ value, path }),
    });

    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    input.value = 'updated';
    input.dispatchEvent(new Event('input', { bubbles: true }));

    expect(captured).toHaveLength(1);
    expect(captured[0]?.value).toBe('updated');
    expect(captured[0]?.path).toBe('email');
    el.remove();
  });

  it('respects enabled=false (disables input + ignores onChange)', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control' } as TextControl['uischema'],
      data: 'x',
      enabled: false,
      onChange: (v) => captured.push(v),
    });

    const input = el.shadowRoot?.querySelector('input') as HTMLInputElement;
    expect(input.disabled).toBe(true);

    // Simulate input event anyway (real browser would block; happy-dom
    // doesn't enforce, so we test the renderer's own guard via updateData)
    input.value = 'tampered';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    expect(captured).toHaveLength(0); // updateData() short-circuits when !enabled
    el.remove();
  });

  it('respects visible=false (renders nothing)', async () => {
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control' } as TextControl['uischema'],
      data: 'x',
      visible: false,
    });

    expect(el.shadowRoot?.querySelector('input')).toBeNull();
    el.remove();
  });

  it('renders a single-line input by default (no options.multi)', async () => {
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control' } as TextControl['uischema'],
      data: 'x',
    });
    expect(el.shadowRoot?.querySelector('input')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('textarea')).toBeNull();
    el.remove();
  });

  it('renders a <textarea> when uischema options.multi is set (543-fwd #2)', async () => {
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control', options: { multi: true } } as TextControl['uischema'],
      data: '{"version":1}',
      enabled: true,
    });
    const ta = el.shadowRoot?.querySelector('textarea') as HTMLTextAreaElement;
    expect(ta).not.toBeNull();
    expect(el.shadowRoot?.querySelector('input')).toBeNull();
    expect(ta.value).toBe('{"version":1}');
    // value binding still flows to onChange
    el.remove();
  });

  it('textarea input propagates to onChange', async () => {
    const captured: unknown[] = [];
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control', options: { multi: true } } as TextControl['uischema'],
      path: 'json',
      data: '',
      enabled: true,
      onChange: (v) => captured.push(v),
    });
    const ta = el.shadowRoot?.querySelector('textarea') as HTMLTextAreaElement;
    ta.value = '{"a":1}';
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    expect(captured[0]).toBe('{"a":1}');
    el.remove();
  });

  it('renders the error string when errors prop is set', async () => {
    const el = await mount({
      schema: { type: 'string' },
      uischema: { type: 'Control' } as TextControl['uischema'],
      data: 'x',
      errors: 'must be email',
    });

    const errorDiv = el.shadowRoot?.querySelector('.error');
    expect(errorDiv?.textContent).toBe('must be email');
    el.remove();
  });
});
