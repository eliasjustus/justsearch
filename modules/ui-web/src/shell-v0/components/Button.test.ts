// @vitest-environment happy-dom

/**
 * Tempdoc 596 — jf-button availability passthrough. jf-button COMPOSES jf-control, so the typed
 * availability (the operability authority: reachable reason + non-silent block) must forward verbatim
 * to the inner control. This is what lets every jf-button consumer adopt typed availability without
 * re-implementing the reason surface (the a11y-debt close).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import './Button.js';
import type { JfButton } from './Button.js';
import type { Control } from './Control.js';

async function mount(setup: (el: JfButton) => void): Promise<JfButton> {
  const el = document.createElement('jf-button') as JfButton;
  setup(el);
  document.body.appendChild(el);
  await el.updateComplete;
  const inner = el.shadowRoot?.querySelector('jf-control') as Control | null;
  if (inner) await inner.updateComplete;
  return el;
}

const innerControl = (el: JfButton) => el.shadowRoot?.querySelector('jf-control') as Control;
const innerButton = (el: JfButton) =>
  innerControl(el).shadowRoot?.querySelector('button') as HTMLButtonElement;

describe('JfButton availability passthrough (tempdoc 596)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('forwards availability to the composed jf-control', async () => {
    const el = await mount((b) => {
      b.label = 'Undo';
      b.availability = { kind: 'unavailable', reason: 'Nothing to undo' };
    });
    expect(innerControl(el).availability).toEqual({ kind: 'unavailable', reason: 'Nothing to undo' });
  });

  it('unavailable → inner button is aria-disabled (focusable) + carries the reachable reason', async () => {
    const el = await mount((b) => {
      b.label = 'Undo';
      b.availability = { kind: 'unavailable', reason: 'Nothing to undo' };
    });
    const btn = innerButton(el);
    expect(btn.disabled).toBe(false); // soft block stays focusable
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    const describedBy = btn.getAttribute('aria-describedby');
    const reasonEl = innerControl(el).shadowRoot?.getElementById(describedBy as string);
    expect(reasonEl?.textContent).toBe('Nothing to undo');
  });

  it('unavailable activation surfaces the reason (not a silent no-op), does NOT call onActivate', async () => {
    const onActivate = vi.fn();
    const seen: string[] = [];
    const listener = (e: Event) => seen.push((e as CustomEvent).detail?.message);
    document.addEventListener('jf-advisory-ephemeral', listener);
    const el = await mount((b) => {
      b.label = 'Undo';
      b.availability = { kind: 'unavailable', reason: 'Nothing to undo' };
      b.onActivate = onActivate;
    });
    innerButton(el).click();
    document.removeEventListener('jf-advisory-ephemeral', listener);
    expect(onActivate).not.toHaveBeenCalled();
    expect(seen).toContain('Nothing to undo');
  });

  it('host stays interactive for unavailable (host not native-disabled) so the click reaches the toast', async () => {
    const el = await mount((b) => {
      b.label = 'Undo';
      b.availability = { kind: 'unavailable', reason: 'Nothing to undo' };
    });
    // The unavailable kind must NOT reflect the host `disabled` attribute (which sets
    // pointer-events:none) — else the click could never reach jf-control's reason toast.
    expect(el.hasAttribute('disabled')).toBe(false);
  });

  it('available → operable, fires onActivate', async () => {
    const onActivate = vi.fn();
    const el = await mount((b) => {
      b.label = 'Go';
      b.availability = { kind: 'available' };
      b.onActivate = onActivate;
    });
    innerButton(el).click();
    expect(onActivate).toHaveBeenCalledTimes(1);
  });

  it('unset availability → legacy disabled boolean still governs', async () => {
    const onActivate = vi.fn();
    const el = await mount((b) => {
      b.label = 'Go';
      b.disabled = true;
      b.onActivate = onActivate;
    });
    expect(innerButton(el).disabled).toBe(true);
    innerButton(el).click();
    expect(onActivate).not.toHaveBeenCalled();
  });

  // Tempdoc 608 — the in-flight busy overlay forwards through the composition: a promise-returning
  // onActivate lights the composed jf-control's busy state, so every jf-button consumer gets command
  // acknowledgement for free (the Add Folder path).
  it('promise-returning onActivate lights busy on the composed jf-control (passthrough)', async () => {
    let resolve!: () => void;
    const promise = new Promise<void>((r) => (resolve = r));
    const el = await mount((b) => {
      b.label = 'Add';
      b.onActivate = () => promise;
    });
    // Disable spin-delay on the composed control so busy shows immediately (the timing gate is covered in
    // Control.test.ts; here we only verify the busy state forwards through the composition).
    const ctrl = innerControl(el);
    ctrl.showDelayMs = 0;
    ctrl.minVisibleMs = 0;
    await ctrl.updateComplete;
    innerButton(el).click();
    await el.updateComplete;
    await ctrl.updateComplete;
    const btn = innerButton(el);
    expect(btn.getAttribute('aria-busy')).toBe('true');
    // Focus-preserving: aria-disabled (NOT native disabled).
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    expect(btn.disabled).toBe(false);
    resolve();
    await promise;
    await ctrl.updateComplete;
    expect(innerButton(el).getAttribute('aria-busy')).toBeNull();
    expect(innerButton(el).getAttribute('aria-disabled')).toBeNull();
  });
});
