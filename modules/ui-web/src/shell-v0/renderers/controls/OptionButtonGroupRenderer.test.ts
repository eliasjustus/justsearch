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

  // Tempdoc 855 §15.2/§17 R2 — the WAI-ARIA radiogroup keyboard model, added once here.
  describe('keyboard model', () => {
    async function mountThree(current = 'a'): Promise<{
      el: OptionButtonGroupRenderer;
      calls: Array<{ value: unknown; path: string }>;
      buttons: () => HTMLButtonElement[];
    }> {
      const calls: Array<{ value: unknown; path: string }> = [];
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.schema = { type: 'string', enum: ['a', 'b', 'c'] } as OptionButtonGroupRenderer['schema'];
      el.uischema = { type: 'Control' };
      el.data = current;
      el.enabled = true;
      el.visible = true;
      el.path = 'mode';
      el.onChange = (value, path) => {
        calls.push({ value, path });
        el.data = value as string;
      };
      await el.updateComplete;
      return {
        el,
        calls,
        buttons: () => Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []) as HTMLButtonElement[],
      };
    }

    it('roving tabindex: only the selected button is a tab stop', async () => {
      const { buttons } = await mountThree('b');
      const tabindexes = buttons().map((b) => b.getAttribute('tabindex'));
      expect(tabindexes).toEqual(['-1', '0', '-1']);
    });

    it('ArrowRight moves selection to the next option and focuses it', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'b', path: 'mode' }]);
      expect(buttons()[1]!).toBe(el.shadowRoot?.activeElement);
    });

    it('ArrowDown behaves like ArrowRight', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'b', path: 'mode' }]);
    });

    it('ArrowLeft/Up moves selection to the previous option, wrapping at the start', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'c', path: 'mode' }]);
    });

    it('ArrowRight wraps from the last option to the first', async () => {
      const { el, calls, buttons } = await mountThree('c');
      buttons()[2]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'a', path: 'mode' }]);
    });

    it('Home selects and focuses the first option', async () => {
      const { el, calls, buttons } = await mountThree('c');
      buttons()[2]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'a', path: 'mode' }]);
      expect(buttons()[0]!).toBe(el.shadowRoot?.activeElement);
    });

    it('End selects and focuses the last option', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([{ value: 'c', path: 'mode' }]);
      expect(buttons()[2]!).toBe(el.shadowRoot?.activeElement);
    });

    it('an unrelated key is ignored', async () => {
      const { el, calls, buttons } = await mountThree('a');
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([]);
    });

    it('disabled group ignores keyboard navigation', async () => {
      const { el, calls, buttons } = await mountThree('a');
      el.enabled = false;
      await el.updateComplete;
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(calls).toEqual([]);
    });
  });

  // Tempdoc 855 §17 R2 — plain-props usage path (no JsonForms schema/onChange plumbing).
  describe('plain-props path', () => {
    const OPTIONS = [
      { value: 'simple', label: 'Simple', description: 'Standard view' },
      { value: 'advanced', label: 'Advanced' },
    ];

    it('renders from `options`/`value` instead of schema/data', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = OPTIONS;
      el.value = 'advanced';
      await el.updateComplete;
      const buttons = Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []);
      expect(buttons.map((b) => b.querySelector('.option-label')?.textContent?.trim())).toEqual([
        'Simple',
        'Advanced',
      ]);
      const selected = el.shadowRoot?.querySelector('button.option-btn.selected');
      expect(selected?.textContent?.trim()).toContain('Advanced');
      expect(
        el.shadowRoot?.querySelector('.option-desc')?.textContent?.trim(),
      ).toBe('Standard view');
    });

    it('emits CustomEvent("change", {detail:{value}}) on click, not onChange', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = OPTIONS;
      el.value = 'simple';
      const onChangeCalls: unknown[] = [];
      el.onChange = (v) => onChangeCalls.push(v);
      const changeCalls: Array<{ value: string }> = [];
      el.addEventListener('change', (e) => changeCalls.push((e as CustomEvent<{ value: string }>).detail));
      await el.updateComplete;
      const buttons = el.shadowRoot?.querySelectorAll('button.option-btn');
      (buttons?.[1] as HTMLButtonElement).click();
      expect(changeCalls).toEqual([{ value: 'advanced' }]);
      expect(onChangeCalls).toEqual([]);
    });

    it('keyboard model also drives the plain-props path', async () => {
      const el = document.createElement('jf-option-button-group') as OptionButtonGroupRenderer;
      document.body.appendChild(el);
      el.options = OPTIONS;
      el.value = 'simple';
      const changeCalls: Array<{ value: string }> = [];
      el.addEventListener('change', (e) => changeCalls.push((e as CustomEvent<{ value: string }>).detail));
      await el.updateComplete;
      const buttons = () => Array.from(el.shadowRoot?.querySelectorAll('button.option-btn') ?? []) as HTMLButtonElement[];
      buttons()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
      await el.updateComplete;
      expect(changeCalls).toEqual([{ value: 'advanced' }]);
    });
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
    // Tempdoc 855 §15.2/§17 R2 — the switch itself is the shared `jf-switch` atom, composed inside
    // this renderer's own shadow root; `[role="switch"]` lives in jf-switch's shadow root (a separate
    // tree — shadow DOM does not compose across nested custom elements for querySelector).
    const jfSwitch = el.shadowRoot?.querySelector('jf-switch');
    const sw = jfSwitch?.shadowRoot?.querySelector('[role="switch"]') as HTMLElement;
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
    const jfSwitch = el.shadowRoot?.querySelector('jf-switch');
    const sw = jfSwitch?.shadowRoot?.querySelector('[role="switch"]') as HTMLElement;
    sw.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(calls).toEqual([false]);
  });
});
