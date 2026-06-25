// @vitest-environment happy-dom

/**
 * Tempdoc 550 C3 — host.data.invokeOperation routes through OperationClient.invokeWithConsent.
 * When the surface signals consent, a backend 428 trust gate is recovered by approving the
 * backend-issued PendingAuthorization by id (minting a capsule bound to the backend-stored
 * op+args) and re-invoking. With no consent, the gate is not auto-recovered. The host API
 * never mints for an arbitrary op (that path is gone — WA-5).
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { createHostApi } from './HostApiImpl.js';

const apiBase = 'http://test.local';

function deps() {
  return { apiBase, registerSurfacePort: () => {} };
}

function okJson(): Response {
  return new Response(JSON.stringify({ success: true, message: 'ok' }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function gate428(pendingId = 'pa-host-1'): Response {
  return new Response(
    JSON.stringify({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId }),
    { status: 428, headers: { 'Content-Type': 'application/json' } },
  );
}

function capsuleJson(capsule = 'cap-host'): Response {
  return new Response(JSON.stringify({ capsule }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

const fetchSpy = vi.fn();

beforeEach(() => {
  globalThis.fetch = fetchSpy as unknown as typeof fetch;
  fetchSpy.mockReset();
});

afterEach(() => {
  fetchSpy.mockReset();
});

describe('host.data.invokeOperation — consent recovery (tempdoc 550 C3)', () => {
  it('with consent, recovers a 428 by approving the pending by id and re-invokes', async () => {
    fetchSpy.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/authorizations/approve')) return Promise.resolve(capsuleJson('cap-host'));
      const body = init?.body ? JSON.parse(init.body as string) : {};
      return Promise.resolve(body.confirmationToken ? okJson() : gate428('pa-host-1'));
    });
    const host = createHostApi('test', 'TRUSTED_PLUGIN', deps());

    const result = await host.data.invokeOperation('core.bulk-reindex', { corpus: 'default' }, { consented: true });
    expect(result.success).toBe(true);

    const approve = fetchSpy.mock.calls.find(c => String(c[0]).includes('/authorizations/approve'))!;
    // Approve references ONLY the backend-issued pending id — never an arbitrary op+args.
    // allowAlways is the durable-consent flag (tempdoc 550 thesis IV), false on the plugin
    // consent-recovery path which auto-approves once but does not request "always allow".
    expect(JSON.parse((approve[1] as RequestInit).body as string)).toEqual({
      pendingId: 'pa-host-1',
      allowAlways: false,
    });
    const reinvoke = fetchSpy.mock.calls.filter(c => String(c[0]).includes('/invoke')).at(-1)!;
    expect(JSON.parse((reinvoke[1] as RequestInit).body as string).confirmationToken).toBe('cap-host');
  });

  it('without consent, a single invoke and no approve attempt', async () => {
    fetchSpy.mockResolvedValue(okJson());
    const host = createHostApi('test', 'TRUSTED_PLUGIN', deps());

    await host.data.invokeOperation('core.ping-backend', {});

    expect(fetchSpy.mock.calls.some(c => String(c[0]).includes('/authorizations/approve'))).toBe(false);
    const invoke = fetchSpy.mock.calls.find(c => String(c[0]).includes('/invoke'))!;
    expect(JSON.parse((invoke[1] as RequestInit).body as string).confirmationToken).toBeUndefined();
  });
});
