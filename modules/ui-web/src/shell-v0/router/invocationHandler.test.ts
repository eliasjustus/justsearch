/**
 * InvocationHandler tests — slice 492 tier-3 substrate.
 *
 * The InvocationHandler is a thin wrapper around OperationClient.invoke.
 * Tests cover the wrapping contract: arguments threaded, transport
 * stamped, confirmation token passed through, errors propagated.
 */

import { describe, expect, it, vi } from 'vitest';
import { createInvocationHandler } from './invocationHandler.js';
import type { OperationClient } from '../operations/OperationClient.js';

function buildClient(): {
  client: OperationClient;
  invokeSpy: ReturnType<typeof vi.fn>;
} {
  const invokeSpy = vi.fn().mockResolvedValue({ success: true });
  return {
    invokeSpy,
    client: { invoke: invokeSpy } as unknown as OperationClient,
  };
}

describe('InvocationHandler', () => {
  it('passes args, transport, and confirmationToken to OperationClient.invoke', async () => {
    const { client, invokeSpy } = buildClient();
    const handler = createInvocationHandler({ client });

    await handler.handle(
      {
        kind: 'invoke',
        target: 'core.add-watched-root',
        args: { path: '/docs' },
        confirmationToken: 'YES',
      },
      'BUTTON',
    );

    expect(invokeSpy).toHaveBeenCalledWith('core.add-watched-root', {
      args: { path: '/docs' },
      transport: 'BUTTON',
      confirmationToken: 'YES',
    });
  });

  it('errors propagate to the caller (no swallowing)', async () => {
    const invokeSpy = vi.fn().mockRejectedValue(new Error('boom'));
    const handler = createInvocationHandler({
      client: { invoke: invokeSpy } as unknown as OperationClient,
    });

    await expect(
      handler.handle({ kind: 'invoke', target: 'core.x', args: {} }, 'PALETTE'),
    ).rejects.toThrow('boom');
  });
});
