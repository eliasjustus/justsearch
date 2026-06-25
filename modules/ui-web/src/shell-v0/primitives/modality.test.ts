// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import { ModalityController } from './modality.js';
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
