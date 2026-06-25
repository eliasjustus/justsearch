// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { TransientController } from './transientController.js';
import { __resetTransientArbiter, closeOthersInLayer } from '../state/transientLayerArbiter.js';

class FakeHost {
  controllers: ReactiveController[] = [];
  addController(c: ReactiveController): void {
    this.controllers.push(c);
  }
}

const make = (id: string, close: () => void) =>
  new TransientController(new FakeHost() as unknown as ReactiveControllerHost, {
    layer: 'transient',
    id,
    close,
  });

beforeEach(() => __resetTransientArbiter());

describe('TransientController (574 §22.F Move 4 — single-open by construction)', () => {
  it('adds itself to the host on construction', () => {
    const host = new FakeHost();
    const c = new TransientController(host as unknown as ReactiveControllerHost, {
      layer: 'transient',
      id: 'x',
      close: () => {},
    });
    expect(host.controllers).toContain(c);
  });

  it('opening a peer closes the previously-open transient', () => {
    const closeA = vi.fn();
    const closeB = vi.fn();
    make('a', closeA).open();
    make('b', closeB).open(); // opening B must close A
    expect(closeA).toHaveBeenCalledTimes(1);
    expect(closeB).not.toHaveBeenCalled();
  });

  it('close() unregisters so it no longer responds to a peer open', () => {
    const closeA = vi.fn();
    const a = make('a', closeA);
    a.open();
    a.close();
    closeOthersInLayer('transient', 'someone-else'); // would fire every registered closer
    expect(closeA).not.toHaveBeenCalled();
  });

  it('hostDisconnected unregisters (a torn-down transient leaks no stale closer)', () => {
    const closeA = vi.fn();
    const a = make('a', closeA);
    a.open();
    a.hostDisconnected();
    closeOthersInLayer('transient', 'someone-else');
    expect(closeA).not.toHaveBeenCalled();
  });
});

describe('TransientController managesDismiss (574 §22.G — outside-click + Esc by construction)', () => {
  function elHost(): ReactiveControllerHost & HTMLElement {
    const el = document.createElement('div');
    (el as unknown as { addController: (c: ReactiveController) => void }).addController = () => {};
    document.body.appendChild(el);
    return el as unknown as ReactiveControllerHost & HTMLElement;
  }
  const pointerdown = () => new Event('pointerdown', { bubbles: true, composed: true });
  const escape = () => new KeyboardEvent('keydown', { key: 'Escape' });

  it('open() installs a dismiss handler: an OUTSIDE pointerdown and Escape close it; an INSIDE click does not', () => {
    const host = elHost();
    const close = vi.fn();
    const c = new TransientController(host, { layer: 'transient', id: 'd', managesDismiss: true, close });
    c.open();
    host.dispatchEvent(pointerdown()); // inside the host → no dismiss
    expect(close).not.toHaveBeenCalled();
    document.body.dispatchEvent(pointerdown()); // outside the host → dismiss
    expect(close).toHaveBeenCalledTimes(1);
    document.dispatchEvent(escape()); // Escape → dismiss
    expect(close).toHaveBeenCalledTimes(2);
    c.close();
    host.remove();
  });

  it('close() removes the dismiss handler (no dismiss fires afterwards)', () => {
    const host = elHost();
    const close = vi.fn();
    const c = new TransientController(host, { layer: 'transient', id: 'd2', managesDismiss: true, close });
    c.open();
    c.close();
    document.body.dispatchEvent(pointerdown());
    document.dispatchEvent(escape());
    expect(close).not.toHaveBeenCalled();
    host.remove();
  });

  it('without managesDismiss, no document dismiss listeners are installed', () => {
    const host = elHost();
    const close = vi.fn();
    const c = new TransientController(host, { layer: 'transient', id: 'd3', close });
    c.open();
    document.body.dispatchEvent(pointerdown());
    document.dispatchEvent(escape());
    expect(close).not.toHaveBeenCalled();
    c.close();
    host.remove();
  });

  it('dismissExclude suppresses dismiss for a matching path (the external control that opened it)', () => {
    const host = elHost();
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    const close = vi.fn();
    const c = new TransientController(host, {
      layer: 'transient',
      id: 'd4',
      managesDismiss: true,
      close,
      dismissExclude: (path) => path.includes(opener),
    });
    c.open();
    opener.dispatchEvent(pointerdown()); // outside the host BUT excluded → no dismiss
    expect(close).not.toHaveBeenCalled();
    document.body.dispatchEvent(pointerdown()); // outside + not excluded → dismiss
    expect(close).toHaveBeenCalledTimes(1);
    c.close();
    host.remove();
    opener.remove();
  });
});
