import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ReactiveControllerHost } from 'lit';
import { ReceiptController, RECEIPT_FLASH_MS } from './receiptController.js';

/** Minimal host that records requestUpdate calls (the controller only uses addController + requestUpdate). */
class FakeHost {
  updates = 0;
  addController(): void {}
  removeController(): void {}
  requestUpdate(): void {
    this.updates += 1;
  }
  get updateComplete(): Promise<boolean> {
    return Promise.resolve(true);
  }
}

function make(): { host: FakeHost; ctrl: ReceiptController } {
  const host = new FakeHost();
  const ctrl = new ReceiptController(host as unknown as ReactiveControllerHost);
  return { host, ctrl };
}

describe('ReceiptController (tempdoc 613 §6 — the in-control receipt surface)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('flash sets the active receipt + requests an update, and auto-clears after the flash window', () => {
    const { host, ctrl } = make();
    expect(ctrl.active).toBeNull();
    ctrl.flash('Copied!', { key: 'md' });
    expect(ctrl.active).toEqual({ message: 'Copied!', key: 'md' });
    expect(ctrl.isFlashing('md')).toBe(true);
    expect(ctrl.isFlashing('json')).toBe(false);
    expect(host.updates).toBeGreaterThan(0);
    vi.advanceTimersByTime(RECEIPT_FLASH_MS);
    expect(ctrl.active).toBeNull();
  });

  it('a new flash resets the timer (rapid re-clicks keep the latest visible)', () => {
    const { ctrl } = make();
    ctrl.flash('A');
    vi.advanceTimersByTime(RECEIPT_FLASH_MS - 100);
    ctrl.flash('B');
    vi.advanceTimersByTime(RECEIPT_FLASH_MS - 100);
    expect(ctrl.active?.message).toBe('B'); // timer was reset, B still showing
    vi.advanceTimersByTime(200);
    expect(ctrl.active).toBeNull();
  });

  it('hostDisconnected cancels the pending auto-clear (no late update after teardown)', () => {
    const { ctrl } = make();
    ctrl.flash('X');
    ctrl.hostDisconnected();
    vi.advanceTimersByTime(RECEIPT_FLASH_MS * 2); // would have fired; timer was cancelled
    expect(ctrl.active?.message).toBe('X');
  });
});
