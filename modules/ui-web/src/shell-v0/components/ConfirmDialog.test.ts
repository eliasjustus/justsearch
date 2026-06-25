// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import './ConfirmDialog.js';
import { ConfirmDialog, confirmAsync } from './ConfirmDialog.js';

function makeDialog(): ConfirmDialog {
  const el = document.createElement('jf-confirm-dialog') as ConfirmDialog;
  el.title = 'Test title';
  el.message = 'Test message';
  document.body.appendChild(el);
  return el;
}

/**
 * 574 B (remediation) — the cancel/confirm buttons are now <jf-button>; the action fires from the
 * native <button> inside the composed <jf-control>, two shadow roots deep, NOT a host click. Awaits
 * both render passes, then clicks the inner control.
 */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('ConfirmDialog (slice 457)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('does not open the dialog when closed', async () => {
    const el = makeDialog();
    await el.updateComplete;
    // 574 A1 — the native <dialog> stays in the DOM (closed/hidden) rather than rendering nothing.
    const dlg = el.shadowRoot?.querySelector('dialog');
    expect(dlg).not.toBeNull();
    expect(dlg?.open).toBe(false);
  });

  it('renders title + message when open', async () => {
    const el = makeDialog();
    el.open = true;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.title')?.textContent).toBe('Test title');
    expect(el.shadowRoot?.querySelector('.message')?.textContent).toBe('Test message');
  });

  it('emits confirm event on confirm-button click', async () => {
    const el = makeDialog();
    el.open = true;
    await el.updateComplete;
    let confirmed = false;
    el.addEventListener('confirm', () => (confirmed = true));
    await activateJfButton(el.shadowRoot?.querySelector('jf-button.confirm'));
    expect(confirmed).toBe(true);
  });

  it('emits cancel event on cancel-button click', async () => {
    const el = makeDialog();
    el.open = true;
    await el.updateComplete;
    let cancelled = false;
    el.addEventListener('cancel', () => (cancelled = true));
    await activateJfButton(el.shadowRoot?.querySelector('jf-button.cancel'));
    expect(cancelled).toBe(true);
  });

  it('typed-confirm: confirm button disabled until input matches', async () => {
    const el = makeDialog();
    el.typedConfirmWord = 'DELETE';
    el.open = true;
    await el.updateComplete;
    // 574 B — confirm is a <jf-button>; its disabled state is the reflected boolean attribute
    // (ConfirmDialog binds ?disabled=${confirmDisabled()} on the host).
    const btn = el.shadowRoot?.querySelector('jf-button.confirm');
    expect(btn?.hasAttribute('disabled')).toBe(true);
    el.typedInput = 'DELETE';
    await el.updateComplete;
    const btn2 = el.shadowRoot?.querySelector('jf-button.confirm');
    expect(btn2?.hasAttribute('disabled')).toBe(false);
  });

  it('typed-confirm: not-quite-matching word stays disabled', async () => {
    const el = makeDialog();
    el.typedConfirmWord = 'DELETE';
    el.open = true;
    el.typedInput = 'delete'; // wrong case
    await el.updateComplete;
    const btn = el.shadowRoot?.querySelector('jf-button.confirm');
    expect(btn?.hasAttribute('disabled')).toBe(true);
  });

  it('Escape (native dialog cancel) cancels', async () => {
    const el = makeDialog();
    el.open = true;
    await el.updateComplete;
    let cancelled = false;
    el.addEventListener('cancel', () => (cancelled = true));
    // 574 A1 — Escape is now the native <dialog> 'cancel' event (handled by @cancel), not a
    // document keydown; dispatch it on the inner dialog to exercise that wiring.
    el.shadowRoot
      ?.querySelector('dialog')
      ?.dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(cancelled).toBe(true);
  });

  it('Escape key does nothing when closed', async () => {
    const el = makeDialog();
    el.open = false;
    await el.updateComplete;
    let cancelled = false;
    el.addEventListener('cancel', () => (cancelled = true));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(cancelled).toBe(false);
  });

  it('variant CSS class on icon-box matches variant prop', async () => {
    for (const v of ['danger', 'warning', 'info'] as const) {
      const el = makeDialog();
      el.variant = v;
      el.open = true;
      await el.updateComplete;
      expect(el.shadowRoot?.querySelector('.icon-box')?.classList.contains(v)).toBe(true);
      el.remove();
    }
  });

  it('confirmAsync resolves true on confirm', async () => {
    const promise = confirmAsync({ title: 't', message: 'm' });
    // Wait a tick for the element to mount
    await new Promise((r) => setTimeout(r, 10));
    const el = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
    expect(el).toBeTruthy();
    await activateJfButton(el.shadowRoot?.querySelector('jf-button.confirm'));
    expect(await promise).toBe(true);
  });

  it('confirmAsync resolves false on cancel', async () => {
    const promise = confirmAsync({ title: 't', message: 'm' });
    await new Promise((r) => setTimeout(r, 10));
    const el = document.querySelector('jf-confirm-dialog') as ConfirmDialog;
    await activateJfButton(el.shadowRoot?.querySelector('jf-button.cancel'));
    expect(await promise).toBe(false);
  });
});
