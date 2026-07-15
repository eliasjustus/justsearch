/**
 * Tests for OperationClient (slice 3a-1-2 Phase 4).
 */
import { describe, it, expect, vi } from 'vitest';
import { OperationClient, OperationError } from './OperationClient';

function fakeResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('OperationClient', () => {
  it('invokes via POST to /api/operations/{id}/invoke with JSON body', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(fakeResponse({ success: true, message: 'ok' }));

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    await client.invoke('core.ping-backend', { args: { foo: 'bar' } });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const call = fetchImpl.mock.calls[0]!;
    expect(call[0]).toBe('http://localhost:33221/api/operations/core.ping-backend/invoke');
    const init = call[1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' });
    const parsedBody = JSON.parse(init.body as string);
    expect(parsedBody.args).toEqual({ foo: 'bar' });
  });

  it('returns success payload with message + executionId + structuredData', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        fakeResponse({
          success: true,
          message: 'restarted',
          executionId: 'uuid-123',
          structuredData: { port: 9001 },
        }),
      );

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    const result = await client.invoke('core.restart-worker');

    expect(result.success).toBe(true);
    expect(result.message).toBe('restarted');
    expect(result.executionId).toBe('uuid-123');
    expect(result.structuredData).toEqual({ port: 9001 });
  });

  it('throws OperationError with errorClass on handler failure', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockImplementation(() =>
        Promise.resolve(
          fakeResponse({
            success: false,
            message: 'worker not running',
            errorClass: 'HANDLER_FAILURE',
          }),
        ),
      );

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('core.restart-worker');
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      expect((err as OperationError).message).toBe('worker not running');
      expect((err as OperationError).errorClass).toBe('HANDLER_FAILURE');
    }
  });

  // Slice 3a-2-c Phase B/G: typed-error fields surface on OperationError.
  // Tempdoc 737 §12b: re-pinned from the retired core.switch-inference-mode onto its successor
  // core.set-chat-enabled. This test's intent is the CLIENT's error-payload passthrough (errorCode/
  // errorDetails/retryable → OperationError), not the op's own behaviour — the op is the vehicle, so
  // the assertion carries the new op's {enabled} arg shape.
  it('parses typed errorCode + errorDetails + retryable from wire shape', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockImplementation(() =>
        Promise.resolve(
          fakeResponse({
            success: false,
            message: 'Runtime authority unavailable',
            errorClass: 'HANDLER_FAILURE',
            errorCode: 'RUNTIME_UNAVAILABLE',
            errorDetails: { enabled: true },
            retryable: true,
          }),
        ),
      );

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('core.set-chat-enabled', { args: { enabled: true } });
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      const opErr = err as OperationError;
      expect(opErr.errorClass).toBe('HANDLER_FAILURE');
      expect(opErr.errorCode).toBe('RUNTIME_UNAVAILABLE');
      expect(opErr.errorDetails).toEqual({ enabled: true });
      expect(opErr.retryable).toBe(true);
    }
  });

  // Backward-compat: success responses don't carry errorCode/errorDetails/retryable;
  // they remain undefined on OperationError (which only fires on failure anyway).
  it('handles failure responses missing the new typed fields gracefully', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockImplementation(() =>
        Promise.resolve(
          fakeResponse({
            success: false,
            message: 'old-style failure',
            errorClass: 'HANDLER_FAILURE',
          }),
        ),
      );

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('core.legacy');
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      const opErr = err as OperationError;
      expect(opErr.errorCode).toBeUndefined();
      expect(opErr.errorDetails).toBeUndefined();
      expect(opErr.retryable).toBeUndefined();
    }
  });

  it('throws OperationError with OPERATION_NOT_FOUND on 404', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        fakeResponse(
          { success: false, message: 'Operation not found: nope', errorClass: 'OPERATION_NOT_FOUND' },
          404,
        ),
      );

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('nope');
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      expect((err as OperationError).errorClass).toBe('OPERATION_NOT_FOUND');
      expect((err as OperationError).httpStatus).toBe(404);
    }
  });

  it('throws NETWORK_ERROR on fetch rejection', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new Error('connection refused'));

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('core.ping-backend');
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      expect((err as OperationError).errorClass).toBe('NETWORK_ERROR');
    }
  });

  it('throws SERIALIZATION_ERROR when response is not valid JSON', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response('not-json', { status: 200 }));

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('core.ping-backend');
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      expect((err as OperationError).errorClass).toBe('SERIALIZATION_ERROR');
    }
  });

  it('forwards idempotencyKey + confirmationToken in request body', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(fakeResponse({ success: true, message: 'ok' }));

    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    await client.invoke('core.restart-worker', {
      idempotencyKey: 'uuid-key',
      confirmationToken: 'restart',
    });

    const body = JSON.parse(fetchImpl.mock.calls[0]![1]?.body as string);
    expect(body.idempotencyKey).toBe('uuid-key');
    expect(body.confirmationToken).toBe('restart');
  });

  it('strips trailing slash from apiBase', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(fakeResponse({ success: true, message: 'ok' }));

    const client = new OperationClient({ apiBase: 'http://localhost:33221/', fetchImpl });
    await client.invoke('core.ping-backend');

    expect(fetchImpl.mock.calls[0]![0]).toBe('http://localhost:33221/api/operations/core.ping-backend/invoke');
  });

  it('throws if operationId is empty', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    await expect(client.invoke('')).rejects.toThrow(OperationError);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  // ── Tempdoc 550 C3: approve-by-pendingId recovery ────────────────────────────

  it('surfaces pendingId on a 428 CONFIRMATION_REQUIRED error', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        fakeResponse({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pa-9' }, 428),
      );
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    try {
      await client.invoke('core.bulk-reindex');
      throw new Error('expected throw');
    } catch (err) {
      expect(err).toBeInstanceOf(OperationError);
      expect((err as OperationError).errorClass).toBe('CONFIRMATION_REQUIRED');
      expect((err as OperationError).pendingId).toBe('pa-9');
    }
  });

  it('invokeWithConsent: on a 428 with consent, approves BY pendingId and re-invokes with the capsule', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/authorizations/approve')) return Promise.resolve(fakeResponse({ capsule: 'cap-1' }));
      const body = init?.body ? JSON.parse(init.body as string) : {};
      return Promise.resolve(
        body.confirmationToken
          ? fakeResponse({ success: true, message: 'done' })
          : fakeResponse({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pa-5' }, 428),
      );
    });
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });

    const result = await client.invokeWithConsent('core.bulk-reindex', { args: { x: 1 } }, { consented: true });
    expect(result.message).toBe('done');

    const approve = fetchImpl.mock.calls.find(c => String(c[0]).includes('/authorizations/approve'))!;
    expect(JSON.parse((approve[1] as RequestInit).body as string)).toEqual({
      pendingId: 'pa-5',
      allowAlways: false,
    });
    const reinvoke = fetchImpl.mock.calls.filter(c => String(c[0]).includes('/invoke')).at(-1)!;
    expect(JSON.parse((reinvoke[1] as RequestInit).body as string).confirmationToken).toBe('cap-1');
  });

  it('invokeWithConsent: a 428 WITHOUT consent rethrows the gate (no approve attempted)', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        fakeResponse({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pa-3' }, 428),
      );
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });

    await expect(
      client.invokeWithConsent('core.bulk-reindex', {}, { consented: false }),
    ).rejects.toMatchObject({ errorClass: 'CONFIRMATION_REQUIRED' });
    expect(fetchImpl.mock.calls.some(c => String(c[0]).includes('/authorizations/approve'))).toBe(false);
  });

  it('approveByPendingId POSTs {pendingId} and returns the capsule', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(fakeResponse({ capsule: 'cap-xyz' }));
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });

    const capsule = await client.approveByPendingId('pa-42', true);
    expect(capsule).toBe('cap-xyz');
    expect(fetchImpl.mock.calls[0]![0]).toBe('http://localhost:33221/api/authorizations/approve');
    expect(JSON.parse(fetchImpl.mock.calls[0]![1]!.body as string)).toEqual({
      pendingId: 'pa-42',
      allowAlways: true,
    });
  });

  // ── Smoke-round 2026-07-14 HIGH finding (734): a live GUI smoke pass on an OLD build found
  // an expired-pending approval ceremony that silently no-op'd (Approve/Deny did nothing,
  // undismissable). Verified fixed at HEAD — approveByPendingId throws on the backend's 410,
  // and invokeWithConsent lets that throw propagate rather than swallowing it. These two tests
  // pin that on the FE side.

  it('approveByPendingId throws OperationError CAPSULE_MINT_FAILED with the status attached on a 410 (expired/unknown pending)', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(fakeResponse({ error: 'pending authorization not found or expired' }, 410));
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });

    await expect(client.approveByPendingId('pa-expired')).rejects.toMatchObject({
      errorClass: 'CAPSULE_MINT_FAILED',
      httpStatus: 410,
    });
  });

  it('invokeWithConsent: an expired pending (approve POSTs 410) propagates CAPSULE_MINT_FAILED to the caller, not swallowed', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/authorizations/approve')) {
        return Promise.resolve(
          fakeResponse({ error: 'pending authorization not found or expired' }, 410),
        );
      }
      return Promise.resolve(
        fakeResponse({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pa-expired' }, 428),
      );
    });
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });

    // The user approved (consented: true) — the ceremony did its part. The propagated error
    // must carry the SAME precise code/status a swallow-and-hang bug would have hidden.
    await expect(
      client.invokeWithConsent('core.bulk-reindex', { args: { x: 1 } }, { consented: true }),
    ).rejects.toMatchObject({
      errorClass: 'CAPSULE_MINT_FAILED',
      httpStatus: 410,
    });

    // No re-invoke-with-capsule was attempted — proves the flow didn't fabricate/reuse a
    // capsule and silently proceed as if the approval had succeeded.
    const reinvokeWithCapsule = fetchImpl.mock.calls.some((c) => {
      const init = c[1] as RequestInit | undefined;
      if (!init?.body) return false;
      const body = JSON.parse(init.body as string);
      return typeof body.confirmationToken === 'string';
    });
    expect(reinvokeWithCapsule).toBe(false);
  });

  it('invokeWithConsent (invoke-first): a 428 routes the prompt to requestConsent; approve → re-invoke', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/authorizations/approve')) return Promise.resolve(fakeResponse({ capsule: 'cap-1' }));
      const body = init?.body ? JSON.parse(init.body as string) : {};
      return Promise.resolve(
        body.confirmationToken
          ? fakeResponse({ success: true, message: 'done' })
          : fakeResponse(
              { success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pa-7', gateBehavior: 'TYPED_CONFIRM' },
              428,
            ),
      );
    });
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });
    const prompts: unknown[] = [];

    const result = await client.invokeWithConsent('core.bulk-reindex', { args: {} }, {
      requestConsent: async (p) => {
        prompts.push(p);
        return { approved: true, allowAlways: false };
      },
    });

    expect(result.message).toBe('done');
    // The prompt carried the backend pendingId + gate so the ceremony can render correctly.
    // Tempdoc 550 P1: the gate message also rides along as `purpose` (this 428 had no risk/args
    // context, so only purpose is added — the enriched fields are populated when present).
    expect(prompts).toEqual([
      {
        pendingId: 'pa-7',
        operationId: 'core.bulk-reindex',
        gateBehavior: 'TYPED_CONFIRM',
        purpose: 'Operation core.bulk-reindex failed',
      },
    ]);
  });

  it('invokeWithConsent (invoke-first): requestConsent declines → the gate error is rethrown, no approve', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        fakeResponse({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'pa-8' }, 428),
      );
    const client = new OperationClient({ apiBase: 'http://localhost:33221', fetchImpl });

    await expect(
      client.invokeWithConsent(
        'core.bulk-reindex',
        {},
        { requestConsent: async () => ({ approved: false, allowAlways: false }) },
      ),
    ).rejects.toMatchObject({ errorClass: 'CONFIRMATION_REQUIRED' });
    expect(fetchImpl.mock.calls.some(c => String(c[0]).includes('/authorizations/approve'))).toBe(false);
  });
});
