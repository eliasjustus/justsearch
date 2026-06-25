// @vitest-environment happy-dom

/**
 * Tempdoc 521 §16.7 deeper (Phase B) — PanePicker render shape +
 * onPick callback contract.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import './PanePicker.js';
import type { PanePicker } from './PanePicker.js';

async function mount(): Promise<PanePicker> {
  const el = document.createElement('jf-pane-picker') as PanePicker;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('jf-pane-picker', () => {
  it('renders the candidates as <option>s', async () => {
    const el = await mount();
    el.candidates = [
      { id: 'core.library-surface', label: 'Library' },
      { id: 'core.help-surface', label: 'Help' },
    ];
    el.selectedId = 'core.library-surface';
    await el.updateComplete;
    const opts = el.shadowRoot!.querySelectorAll('option');
    expect(opts).toHaveLength(2);
    expect(opts[0]!.value).toBe('core.library-surface');
    expect((opts[0]! as HTMLOptionElement).selected).toBe(true);
  });

  it('disables the <select> when there are zero candidates', async () => {
    const el = await mount();
    el.candidates = [];
    await el.updateComplete;
    const select = el.shadowRoot!.querySelector('select')!;
    expect(select.disabled).toBe(true);
  });

  it('fires onPick with the chosen surface id on change', async () => {
    const el = await mount();
    const pick = vi.fn();
    el.candidates = [
      { id: 'core.search-surface', label: 'Search' },
      { id: 'core.library-surface', label: 'Library' },
    ];
    el.onPick = pick;
    await el.updateComplete;
    const select = el.shadowRoot!.querySelector('select')!;
    select.value = 'core.library-surface';
    select.dispatchEvent(new Event('change'));
    expect(pick).toHaveBeenCalledWith('core.library-surface');
  });

  it('no-op when onPick is null', async () => {
    const el = await mount();
    el.candidates = [{ id: 'a', label: 'A' }];
    el.onPick = null;
    await el.updateComplete;
    const select = el.shadowRoot!.querySelector('select')!;
    select.value = 'a';
    expect(() => select.dispatchEvent(new Event('change'))).not.toThrow();
  });
});
