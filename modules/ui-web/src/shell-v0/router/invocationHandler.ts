// SPDX-License-Identifier: Apache-2.0
/**
 * InvocationHandler — Intent substrate tier 3 (slice 492).
 *
 * Wraps {@link OperationClient.invoke} for the FE half of the dual-handler
 * topology. The Java-side `OperationDispatcher` plays a similar role for
 * backend-initiated invocations; the **shape similarity is conceptual,
 * not structural** — Java's signature is
 * `dispatch(Operation, String, InvocationProvenance) → OperationResult`
 * (synchronous, trust-tier-aware) while TS is
 * `handle(ShellAddressInvocation, transport) → Promise<unknown>`
 * (async, passthrough). The two roles align at the abstract intent
 * envelope (`Intent` + `ShellAddress`); they diverge at the concrete
 * handler signature by design (Java has trust-tier branching; TS does
 * not).
 *
 * **Today's body is a one-line passthrough to `OperationClient.invoke`.**
 * That is intentional, not a sign the abstraction is missing. The tier
 * exists as the **forward-compat seam** where FE cross-cutting concerns
 * accumulate without surgery on `OperationClient` or the router:
 *
 *   - Retry-on-transient policy (per-operation, per-transport).
 *   - Throttle / rate-limit per source.
 *   - Idempotency-key threading for replay-safe POSTs.
 *   - Audit-log hooks visible only at the dispatch boundary.
 *
 * Each can land as a wrapper on this handler without touching the
 * router, the source code, or `OperationClient` consumers (which today
 * include `wireActionButton.ts` and any future direct invokers that
 * bypass the substrate).
 *
 * The router returns the handler's result through its dispatch
 * promise (slice-492 follow-up — see `IntentRouter.dispatch` JSDoc).
 * Callers that don't care about the result `void`-prefix the call;
 * callers that do can `await` and react. The OperationHistory SSE
 * stream remains the canonical read channel for *audit* of past
 * invocations.
 */

import type { ShellAddressInvocation } from './types.js';
import type { OperationClient } from '../operations/OperationClient.js';

export interface InvocationHandler {
  /** Realize an Invocation intent. */
  handle(
    addr: ShellAddressInvocation,
    transport: string,
  ): Promise<unknown>;
}

export interface InvocationHandlerConfig {
  client: OperationClient;
}

export function createInvocationHandler(config: InvocationHandlerConfig): InvocationHandler {
  return {
    async handle(addr: ShellAddressInvocation, transport: string): Promise<unknown> {
      return config.client.invoke(addr.target, {
        args: addr.args,
        transport,
        confirmationToken: addr.confirmationToken,
      });
    },
  };
}
