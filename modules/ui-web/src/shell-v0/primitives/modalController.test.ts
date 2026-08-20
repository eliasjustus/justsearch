// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { ModalController } from './modalController.js';

class FakeHost {
  controllers: ReactiveController[] = [];
  addController(c: ReactiveController): void {
    this.controllers.push(c);
  }
}

class FakeDialog {
  open = false;
  showModal = vi.fn(() => {
    this.open = true;
  });
  show = vi.fn(() => {
    this.open = true;
  });
  close = vi.fn(() => {
    this.open = false;
  });
}

const make = (dlg: FakeDialog, opts: Partial<{ onOpened: () => void }> = {}) =>
  new ModalController(new FakeHost() as unknown as ReactiveControllerHost, {
    dialog: () => dlg as unknown as HTMLDialogElement,
    onOpened: opts.onOpened,
  });

beforeEach(() => {
  document.documentElement.style.overflow = '';
});

describe('ModalController (574 §22.G — the full modal contract by construction)', () => {
  it('open() locks background scroll + showModal() + onOpened; close() releases + closes', () => {
    const dlg = new FakeDialog();
    const onOpened = vi.fn();
    const m = make(dlg, { onOpened });
    m.open();
    expect(dlg.showModal).toHaveBeenCalledTimes(1);
    expect(document.documentElement.style.overflow).toBe('hidden'); // scroll-lock half
    expect(onOpened).toHaveBeenCalledTimes(1);
    m.close();
    expect(dlg.close).toHaveBeenCalledTimes(1);
    expect(document.documentElement.style.overflow).toBe(''); // released
  });

  it('nonBlocking uses show() and does NOT lock scroll', () => {
    const dlg = new FakeDialog();
    const m = make(dlg);
    m.open({ nonBlocking: true });
    expect(dlg.show).toHaveBeenCalledTimes(1);
    expect(dlg.showModal).not.toHaveBeenCalled();
    expect(document.documentElement.style.overflow).toBe(''); // no lock on the lightweight path
    m.close();
    expect(dlg.close).toHaveBeenCalledTimes(1);
  });

  it('open() is idempotent (no double showModal when already open)', () => {
    const dlg = new FakeDialog();
    const m = make(dlg);
    m.open();
    m.open();
    expect(dlg.showModal).toHaveBeenCalledTimes(1);
    m.close();
  });

  it('close({ skipFocusRestore: true }) passes through to ModalityController.exit (855 §11.2 merge-blocker)', () => {
    const dlg = new FakeDialog();
    const invoker = document.createElement('button');
    document.body.appendChild(invoker);
    invoker.focus();
    const m = make(dlg);
    m.open();
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    m.close({ skipFocusRestore: true });
    expect(dlg.close).toHaveBeenCalledTimes(1);
    expect(document.documentElement.style.overflow).toBe(''); // scroll-lock still releases
    expect(document.activeElement).toBe(field); // focus NOT restored to invoker
  });

  it('captureFocus() arms the scroll-lock without opening; a following open() does not re-lock', () => {
    const dlg = new FakeDialog();
    const m = make(dlg);
    m.captureFocus();
    expect(document.documentElement.style.overflow).toBe('hidden');
    expect(dlg.showModal).not.toHaveBeenCalled();
    m.open();
    expect(dlg.showModal).toHaveBeenCalledTimes(1); // showModal fires; enter is a no-op
    m.close();
    expect(document.documentElement.style.overflow).toBe('');
  });
});
