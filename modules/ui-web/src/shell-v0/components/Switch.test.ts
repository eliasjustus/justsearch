// @vitest-environment happy-dom

/**
 * Tempdoc 855 §15.2/§17 R2 — the shared `jf-switch` atom. Extracted from
 * ToggleSwitchRenderer's `.switch`; every hand-rolled switch site converts to this.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './Switch.js';
import type { Switch } from './Switch.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

async function mount(): Promise<Switch> {
  const el = document.createElement('jf-switch') as Switch;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('<jf-switch>', () => {
  it('renders role=switch reflecting the checked prop', async () => {
    const el = await mount();
    el.checked = true;
    await el.updateComplete;
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('aria-checked')).toBe('true');
    expect(sw.classList.contains('on')).toBe(true);
  });

  it('reflects checked=false as aria-checked=false, no .on class', async () => {
    const el = await mount();
    el.checked = false;
    await el.updateComplete;
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('aria-checked')).toBe('false');
    expect(sw.classList.contains('on')).toBe(false);
  });

  it('click toggles and emits change with the new checked value', async () => {
    const el = await mount();
    el.checked = false;
    await el.updateComplete;
    const calls: Array<{ checked: boolean }> = [];
    el.addEventListener('change', (e) => calls.push((e as CustomEvent<{ checked: boolean }>).detail));
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    sw.click();
    expect(calls).toEqual([{ checked: true }]);
  });

  it('Space and Enter toggle (keyboard-operable)', async () => {
    const el = await mount();
    el.checked = false;
    await el.updateComplete;
    const calls: Array<{ checked: boolean }> = [];
    el.addEventListener('change', (e) => calls.push((e as CustomEvent<{ checked: boolean }>).detail));
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    sw.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    sw.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(calls).toEqual([{ checked: true }, { checked: true }]);
  });

  it('disabled is inert: no event on click, tabindex=-1, aria-disabled=true', async () => {
    const el = await mount();
    el.checked = false;
    el.disabled = true;
    await el.updateComplete;
    const calls: unknown[] = [];
    el.addEventListener('change', () => calls.push(true));
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('tabindex')).toBe('-1');
    expect(sw.getAttribute('aria-disabled')).toBe('true');
    sw.click();
    sw.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(calls).toEqual([]);
  });

  it('enabled switch has no aria-disabled attribute', async () => {
    const el = await mount();
    el.disabled = false;
    await el.updateComplete;
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.hasAttribute('aria-disabled')).toBe(false);
  });

  it('label sets aria-label; omitted label leaves it unset', async () => {
    const el = await mount();
    el.label = 'High contrast';
    await el.updateComplete;
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('aria-label')).toBe('High contrast');

    const el2 = await mount();
    await el2.updateComplete;
    const sw2 = el2.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw2.hasAttribute('aria-label')).toBe(false);
  });

  it('enabled switch has tabindex=0', async () => {
    const el = await mount();
    await el.updateComplete;
    const sw = el.shadowRoot!.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('tabindex')).toBe('0');
  });
});
