// @vitest-environment happy-dom

/**
 * 569 Move 4 — the trusted ceremony renders on the Top Layer (a native <dialog> opened
 * with showModal()), so a user-authored skin overlay cannot occlude or spoof it.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './AuthorizationHost.js';
import type { AuthorizationHost } from './AuthorizationHost.js';
import {
  requestAuthorization,
  setAuthorizationPresenter,
} from '../operations/authorizationBroker.js';

let host: AuthorizationHost;

beforeEach(() => {
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.appendChild(host);
});

afterEach(() => {
  if (host.parentNode) host.remove();
  setAuthorizationPresenter(null);
});

async function settle(): Promise<void> {
  await host.updateComplete;
  await Promise.resolve();
  await host.updateComplete;
}

describe('Move 4 — trusted ceremony on the Top Layer', () => {
  it('opens a high-risk ceremony as a modal <dialog> (showModal → top layer, un-occludable)', async () => {
    void requestAuthorization({
      pendingId: 'p',
      operationId: 'core.bulk-reindex',
      gateBehavior: 'TYPED_CONFIRM',
    });
    await settle();
    const dialog = host.shadowRoot?.querySelector('dialog.ceremony') as HTMLDialogElement | null;
    expect(dialog).not.toBeNull();
    expect(dialog?.open).toBe(true);
    expect(dialog?.classList.contains('lightweight')).toBe(false);
  });

  it('opens a low/medium ceremony non-blocking (lightweight)', async () => {
    void requestAuthorization({
      pendingId: 'p2',
      operationId: 'core.x',
      gateBehavior: 'INLINE_CONFIRM',
    });
    await settle();
    const dialog = host.shadowRoot?.querySelector('dialog.ceremony') as HTMLDialogElement | null;
    expect(dialog?.open).toBe(true);
    expect(dialog?.classList.contains('lightweight')).toBe(true);
  });
});
