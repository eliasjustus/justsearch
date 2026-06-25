/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Result of {@code BackendIntentRouter.dispatch(intent, provenance)}.
 *
 * <p>Per tempdoc 487 §4.3: sealed over the two outcomes the backend router
 * produces — {@link Dispatched} for Invocation envelopes (the dispatcher ran
 * synchronously and returned an {@link OperationResult}), {@link Forwarded}
 * for Navigation envelopes (the backend emitted the envelope onto
 * {@code /api/intent/stream} for the FE router to consume; the backend has
 * no surface authority).
 *
 * <p>The agent loop's existing call-site at
 * {@code AgentLoopService.executeOperationWithPolicy:1951} only ever produces
 * Invocation envelopes from tool calls, so the migration call site is safe to
 * unwrap as {@code ((Dispatched) result).result()}. Other call sites that
 * dispatch arbitrary envelopes must pattern-match.
 */
public sealed interface IntentDispatchResult
    permits IntentDispatchResult.Dispatched, IntentDispatchResult.Forwarded {

  /**
   * Operation invocation dispatched server-side. Carries the dispatcher result.
   *
   * @param result the {@link OperationResult} from
   *     {@code OperationDispatcher.dispatch(op, args, provenance)}
   */
  record Dispatched(OperationResult result) implements IntentDispatchResult {
    public Dispatched {
      Objects.requireNonNull(result, "result");
    }
  }

  /**
   * Navigation envelope forwarded onto the SSE stream for FE consumption.
   *
   * @param envelopeId the stable {@code payload.id} field of the emitted
   *     envelope (used for audit-log correlation + FE-side dedup against
   *     ring-buffer replay on reconnect)
   */
  record Forwarded(String envelopeId) implements IntentDispatchResult {
    public Forwarded {
      Objects.requireNonNull(envelopeId, "envelopeId");
      if (envelopeId.isBlank()) {
        throw new IllegalArgumentException("envelopeId must be non-blank");
      }
    }
  }
}
