// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import { ModalityController, modalOwnsFocus, __resetModalityForTest } from './modality.js';
import {
  registerTransient,
  closeOthersInLayer,
  __resetTransientArbiter,
} from '../state/transientLayerArbiter.js';

class FakeHost {
  addController(): void {
    /* no-op for the test */
  }
}
const ctrl = () => new ModalityController(new FakeHost() as unknown as never);

describe('ModalityController (574 Move 4)', () => {
  beforeEach(() => {
    document.documentElement.style.overflow = '';
  });

  it('locks background scroll on enter and releases it only when the last modal exits', () => {
    const a = ctrl();
    const b = ctrl();
    a.enter();
    expect(document.documentElement.style.overflow).toBe('hidden');
    b.enter();
    a.exit();
    expect(document.documentElement.style.overflow).toBe('hidden'); // b still open (ref-counted)
    b.exit();
    expect(document.documentElement.style.overflow).toBe('');
  });

  it('restores focus to the pre-enter element on exit (the residue-#5 fix)', () => {
    const invoker = document.createElement('button');
    document.body.appendChild(invoker);
    invoker.focus();
    const m = ctrl();
    m.enter();
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    expect(document.activeElement).toBe(field);
    m.exit();
    expect(document.activeElement).toBe(invoker);
  });

  it('exit({ skipFocusRestore: true }) releases the scroll-lock but leaves focus alone (855 §11.2 merge-blocker)', () => {
    const invoker = document.createElement('button');
    document.body.appendChild(invoker);
    invoker.focus();
    const m = ctrl();
    m.enter();
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    expect(document.activeElement).toBe(field);
    m.exit({ skipFocusRestore: true });
    expect(document.documentElement.style.overflow).toBe(''); // scroll-lock still releases
    expect(document.activeElement).toBe(field); // focus NOT restored to invoker
  });
});

/**
 * Tempdoc 864 Layer 2(d) — the modal-owns-focus predicate every global key handler reads. Its depth
 * is the SAME count the scroll-lock uses (one authority, two consumers), so stacking is asserted
 * here: a modal closing while another is still open must not tell the keyboard it is free.
 */
describe('modalOwnsFocus (864 Layer 2(d))', () => {
  beforeEach(() => __resetModalityForTest());

  it('is false with nothing open, true while a modal is entered, and false again after it exits', () => {
    expect(modalOwnsFocus()).toBe(false);
    const a = ctrl();
    a.enter();
    expect(modalOwnsFocus()).toBe(true);
    a.exit({ skipFocusRestore: true });
    expect(modalOwnsFocus()).toBe(false);
  });

  it('stays true while ANY modal in a stack is still open', () => {
    const a = ctrl();
    const b = ctrl();
    a.enter();
    b.enter();
    a.exit({ skipFocusRestore: true });
    expect(modalOwnsFocus(), 'the inner modal is still open').toBe(true);
    b.exit({ skipFocusRestore: true });
    expect(modalOwnsFocus()).toBe(false);
  });

  it('is not claimed by a non-blocking host, which never enters', () => {
    ctrl(); // constructed but never entered — a `show()` rather than a `showModal()`
    expect(modalOwnsFocus()).toBe(false);
  });
});

describe('transientLayerArbiter (574 Move 4)', () => {
  beforeEach(() => __resetTransientArbiter());

  it('closes other overlays in the same layer, leaving the opener and other layers alone', () => {
    const closed: string[] = [];
    registerTransient('menu', 'a', () => closed.push('a'));
    registerTransient('menu', 'b', () => closed.push('b'));
    registerTransient('right-drawer', 'd', () => closed.push('d'));
    closeOthersInLayer('menu', 'a');
    expect(closed).toEqual(['b']);
  });
});
