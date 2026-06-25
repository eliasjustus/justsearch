// SPDX-License-Identifier: Apache-2.0
/**
 * wireActionButton — host helper that connects a `<jf-action-button>` element
 * to an {@link OperationClient}.
 *
 * Listens for the `action-invoke` event the button emits when the user
 * activates it (after risk-driven confirmation UX runs in-component), and
 * dispatches via the client. Sets the button's `pending` flag during the
 * dispatch so the user can't double-click; clears on completion.
 *
 * Returns an unsubscribe function that the host calls during teardown.
 *
 * Usage:
 *
 *   const unsubscribe = wireActionButton(buttonEl, client);
 *   // ... later, on teardown:
 *   unsubscribe();
 *
 * Per slice 3a-1-2 §A.7: HIGH-risk confirmation enforcement is the
 * ActionButton's UI responsibility (typed-confirm form). The
 * `confirmationToken` field on the request body forwards the typed string
 * if the host wants to surface it; today the helper passes the operation
 * id as a stand-in (the substrate's V1 contract doesn't yet enforce
 * server-side ceremony).
 */

import type { ActionButton, ActionInvokeEventDetail } from '../components/ActionButton';
import { OperationClient, OperationError, type OperationInvocationRequest } from './OperationClient';

export interface WireActionButtonOptions {
  /**
   * Optional builder that maps the activation event to invocation args. If
   * absent, args are empty (zero-arg invocation).
   */
  argsBuilder?: (detail: ActionInvokeEventDetail) => Record<string, unknown>;
  /**
   * Optional callback invoked on successful dispatch. Receives the success
   * payload from the backend (message, executionId, structuredData).
   */
  onSuccess?: (result: { message: string; executionId?: string; structuredData?: Record<string, unknown> }) => void;
  /**
   * Optional callback invoked on dispatch failure. Receives the
   * {@link OperationError} so the host can surface it (toast, banner, etc.).
   */
  onError?: (err: OperationError) => void;
}

/**
 * Attach a click→invoke listener. Returns an unsubscribe function.
 */
export function wireActionButton(
  button: ActionButton,
  client: OperationClient,
  options: WireActionButtonOptions = {},
): () => void {
  const handler = async (e: Event): Promise<void> => {
    const detail = (e as CustomEvent<ActionInvokeEventDetail>).detail;
    if (!detail || !detail.operationId) {
      return;
    }
    const args = options.argsBuilder?.(detail) ?? {};
    const request: OperationInvocationRequest = { args };

    button.pending = true;
    try {
      // Tempdoc 550 C3: HIGH-risk operations hit a TYPED_CONFIRM gate. The user's
      // confirmation (the in-component typed-confirm UX that ran before this event fired)
      // is the consent; invokeWithConsent invokes, and on the backend's 428 it approves the
      // backend-issued PendingAuthorization by id (minting a capsule bound to the
      // backend-stored op+args) and re-invokes. The FE never mints for an arbitrary op — it
      // can only approve what the backend actually gated (closes the Tier-0 hole for this
      // path).
      const result = await client.invokeWithConsent(detail.operationId, request, {
        consented: detail.risk === 'HIGH',
      });
      options.onSuccess?.({
        message: result.message,
        executionId: result.executionId,
        structuredData: result.structuredData,
      });
    } catch (err: unknown) {
      if (err instanceof OperationError) {
        options.onError?.(err);
      } else {
        // Unexpected — re-throw to surface in console + global handlers.
        throw err;
      }
    } finally {
      button.pending = false;
    }
  };

  button.addEventListener('action-invoke', handler);
  return () => {
    button.removeEventListener('action-invoke', handler);
  };
}
