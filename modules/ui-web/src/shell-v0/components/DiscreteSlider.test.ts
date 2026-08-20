// @vitest-environment happy-dom

/**
 * Tempdoc 855 §15.2/§18 — the discrete-slider atom: native `<input type="range">` over a small
 * fixed step set, with `aria-valuetext` carrying the current step's own label and a `change` event
 * on every `input` (instant-apply, matching jf-switch/jf-option-button-group).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './DiscreteSlider.js';
import type { DiscreteSlider } from './DiscreteSlider.js';

const STEPS = [
  { value: 'compact', label: 'Compact' },
  { value: 'comfortable', label: 'Comfortable' },
  { value: 'spacious', label: 'Spacious' },
];

async function mount(value = 'comfortable'): Promise<DiscreteSlider> {
  const el = document.createElement('jf-discrete-slider') as DiscreteSlider;
  el.steps = STEPS;
  el.value = value;
  el.label = 'Density';
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('<jf-discrete-slider>', () => {
  it('renders a native range input spanning the step indices', async () => {
    const el = await mount('compact');
    const input = el.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input.min).toBe('0');
    expect(input.max).toBe('2');
    expect(input.step).toBe('1');
    expect(input.value).toBe('0');
  });

  it('renders tick labels for every step', async () => {
    const el = await mount();
    const labels = Array.from(el.shadowRoot!.querySelectorAll('.ticks span')).map((s) =>
      s.textContent?.trim(),
    );
    expect(labels).toEqual(['Compact', 'Comfortable', 'Spacious']);
  });

  it('aria-valuetext carries the CURRENT step label, and the input has an accessible name', async () => {
    const el = await mount('spacious');
    const input = el.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    expect(input.getAttribute('aria-valuetext')).toBe('Spacious');
    expect(input.getAttribute('aria-label')).toBe('Density');
  });

  it('resolves the middle step index correctly', async () => {
    const el = await mount('comfortable');
    const input = el.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    expect(input.value).toBe('1');
  });

  it('emits CustomEvent("change", {detail:{value}}) on input, mapping index back to the step value', async () => {
    const el = await mount('compact');
    const input = el.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    const calls: Array<{ value: string }> = [];
    el.addEventListener('change', (e) => calls.push((e as CustomEvent<{ value: string }>).detail));
    input.value = '2';
    input.dispatchEvent(new Event('input'));
    expect(calls).toEqual([{ value: 'spacious' }]);
    expect(el.value).toBe('spacious');
  });

  it('a disabled slider ignores input', async () => {
    const el = await mount('compact');
    el.disabled = true;
    await el.updateComplete;
    const input = el.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    expect(input.disabled).toBe(true);
    const calls: unknown[] = [];
    el.addEventListener('change', (e) => calls.push(e));
    input.value = '2';
    input.dispatchEvent(new Event('input'));
    expect(calls).toEqual([]);
    expect(el.value).toBe('compact');
  });

  it('an unknown value falls back to index 0 without throwing', async () => {
    const el = await mount('nonexistent');
    const input = el.shadowRoot!.querySelector('input[type="range"]') as HTMLInputElement;
    expect(input.value).toBe('0');
    expect(input.getAttribute('aria-valuetext')).toBe('Compact');
  });

  it('renders nothing when steps is empty', async () => {
    const el = document.createElement('jf-discrete-slider') as DiscreteSlider;
    el.steps = [];
    document.body.appendChild(el);
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('input')).toBeNull();
  });
});
