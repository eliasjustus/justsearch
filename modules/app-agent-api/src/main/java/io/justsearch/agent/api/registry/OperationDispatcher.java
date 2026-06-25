/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * SPI for trust-tier-aware Operation dispatch.
 *
 * <p>Per tempdoc 429 §C decision A: substrate types live in {@code app-agent-api},
 * behavior lives in {@code app-services}. This interface is the substrate contract
 * consumed by {@code AgentLoopService} (in {@code app-agent}) and other agents that
 * dispatch Operations; the concrete trust-tier-aware implementation
 * ({@code OperationExecutorImpl}) lives in {@code app-services} alongside the
 * emitter framework.
 *
 * <p>Per §A.5 + §B.D + §C.A.5 implementations branch on
 * {@link Provenance#tier()}:
 * <ul>
 *   <li>{@code CORE}: dispatch directly via the handler resolved by {@code Binding.handlerId()}.
 *   <li>{@code TRUSTED_PLUGIN}: dispatch equivalently to CORE in V1 — V1's trust model
 *       is "you wrote it, or you know who did," so trusted plugins behave as core code.
 *       V1.5+ extends this branch additively with a policy floor.
 *   <li>{@code UNTRUSTED_PLUGIN}: throw — V1 has no sandbox infrastructure. V1.5+ adds
 *       iframe sandbox dispatch.
 * </ul>
 *
 * <p>Per §E.3: {@link #undo(Operation, String)} checks
 * {@code op.policy().undoSupported()} before delegating; operations without undo
 * support fail fast with a typed denial, never reaching the handler.
 */
public interface OperationDispatcher {

  /**
   * Execute the operation against the parsed argument JSON. Legacy overload — supplies
   * {@link InvocationProvenance#systemInternal} provenance using the implementation's
   * clock. Prefer {@link #dispatch(Operation, String, InvocationProvenance)} from new
   * callsites so the resulting {@code OperationHistoryEntry} carries typed transport /
   * executor / initiator metadata. Per slice 490 §4.B.
   */
  OperationResult dispatch(Operation op, String argumentsJson);

  /**
   * Execute the operation against the parsed argument JSON, recording the supplied
   * invocation-side provenance on the resulting history entry.
   *
   * <p>Per slice 490 §4.B: invocation-side provenance describes how today's invocation
   * arrived (transport + executor + initiator). The implementation threads
   * {@code provenance} into the emitted {@link io.justsearch.app.observability.operations.OperationHistoryEntry}
   * so audit / replay / chat-receipt consumers can answer "who triggered this?" via a
   * typed record rather than an opaque string.
   *
   * <p>Default implementation delegates to the legacy 2-arg overload to keep test wiring
   * and historical callsites compiling unchanged; concrete implementations override.
   */
  default OperationResult dispatch(
      Operation op, String argumentsJson, InvocationProvenance provenance) {
    return dispatch(op, argumentsJson);
  }

  /**
   * Execute the operation with both invocation-side provenance and an optional
   * confirmation token (slice 487 §4.4).
   *
   * <p>The implementation runs the {@code TrustEvaluator} lattice between
   * {@code validateProvenance} and {@code inputValidator.validate}. When the
   * lattice computes a non-AUTO {@link GateBehavior}, the caller must supply a
   * confirmation token (any non-empty string in V1; richer token validation is a
   * follow-up slice). Absent the token on a non-AUTO gate, the dispatcher throws
   * {@link ConfirmationRequiredException} carrying the gate behavior + the
   * destination's declared {@link ConfirmStrategy} so the caller can render the
   * trust-aware elicitation UX. On {@link GateBehavior#DENY} the dispatcher
   * throws {@link TrustGateDeniedException}.
   *
   * <p>Default implementation delegates to the 3-arg overload to preserve
   * back-compat. Concrete implementations override.
   */
  default OperationResult dispatch(
      Operation op,
      String argumentsJson,
      InvocationProvenance provenance,
      java.util.Optional<String> confirmationToken) {
    return dispatch(op, argumentsJson, provenance);
  }

  /**
   * Undo a previous execution identified by {@code executionId}. Returns a typed
   * failure (not throwing) when the operation's policy does not support undo.
   */
  OperationResult undo(Operation op, String executionId);
}
