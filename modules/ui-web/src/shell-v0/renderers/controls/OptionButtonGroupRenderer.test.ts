// @vitest-environment happy-dom

/**
 * 569 Fix 1 — bespoke-quality renderers that let a DECLARED settings body render like the
 * hand-authored UI (so the declaration can be the DEFAULT with no visual downgrade).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './OptionButtonGroupRenderer.js';
import './ToggleSwitchRenderer.js';
import type { OptionButtonGroupRenderer } from './OptionButtonGroupRenderer.js';
import type { ToggleSwitchRenderer } from './ToggleSwitchRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('<jf-option-button-group>', () => {
  it('registers hint option-button-group → jf-option-button-group', () => {
    expect(getXUiRendererTag('option-button-group')).toBe('jf-option-button-group');
  });

  it('renders one keyboard-operable <button> per enum value with the selected one marked', async () => {
    const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
    document.body.appendChild(el);
    el.schema = {
      type: 'string',
      enum: ['simple', 'advanced'],
      'x-enum-labels': { simple: 'Simple', advanced: 'Advanced' },
    } as OptionButtonGroupRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = 'advanced';
    el.enabled = true;
    el.visible = true;
    el.path = 'mode';
    el.onChange = () => {};
    await el.updateComplete;
    const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
    expect(buttons).toHaveLength(2);
    expect(buttons.map((b) => b.textContent?.trim())).toEqual(['Simple', 'Advanced']);
    const selected = el.shadowRoot?.querySelector('button.option-btn.selected');
    expect(selected?.getAttribute('aria-checked')).toBe('true');
    expect(selected?.textContent?.trim()).toBe('Advanced');
  });

  it('emits onChange(value, path) on click', async () => {
    const calls: Array<{ value: unknown; path: string }> = [];
    const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
    document.body.appendChild(el);
    el.schema = { type: 'string', enum: ['simple', 'advanced'] } as OptionButtonGroupRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = 'simple';
    el.enabled = true;
    el.visible = true;
    el.path = 'mode';
    el.onChange = (value, path) => calls.push({ value, path });
    await el.updateComplete;
    const buttons = el.shadowRoot?.querySelectorAll('button.option-btn');
    (buttons?.[1] as HTMLButtonElement).click();
    expect(calls).toEqual([{ value: 'advanced', path: 'mode' }]);
  });
});

describe('<jf-toggle-switch>', () => {
  it('registers hint toggle-switch → jf-toggle-switch', () => {
    expect(getXUiRendererTag('toggle-switch')).toBe('jf-toggle-switch');
  });

  it('renders a role=switch reflecting the boolean and toggles on click', async () => {
    const calls: Array<{ value: unknown; path: string }> = [];
    const el = document.createElement('jf-toggle-switch') as ToggleSwitchRenderer;
    document.body.appendChild(el);
    el.schema = { type: 'boolean', title: 'High contrast' } as ToggleSwitchRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = false;
    el.enabled = true;
    el.visible = true;
    el.path = 'highContrast';
    el.onChange = (value, path) => calls.push({ value, path });
    await el.updateComplete;
    const sw = el.shadowRoot?.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('aria-checked')).toBe('false');
    expect(sw.getAttribute('tabindex')).toBe('0');
    sw.click();
    expect(calls).toEqual([{ value: true, path: 'highContrast' }]);
  });

  it('toggles on Space/Enter (keyboard-operable)', async () => {
    const calls: unknown[] = [];
    const el = document.createElement('jf-toggle-switch') as ToggleSwitchRenderer;
    document.body.appendChild(el);
    el.schema = { type: 'boolean', title: 'Vim' } as ToggleSwitchRenderer['schema'];
    el.uischema = { type: 'Control' };
    el.data = true;
    el.enabled = true;
    el.visible = true;
    el.path = 'vimMode';
    el.onChange = (value) => calls.push(value);
    await el.updateComplete;
    const sw = el.shadowRoot?.querySelector('[role="switch"]') as HTMLElement;
    sw.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(calls).toEqual([false]);
  });
});
