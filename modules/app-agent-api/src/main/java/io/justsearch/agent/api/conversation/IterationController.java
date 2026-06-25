/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * SPI: decides whether to call the model again within one user request.
 *
 * <p>Per tempdoc 491 §5.1: one of the four substrate SPIs. <strong>Within-turn only</strong> —
 * mixing within-turn iteration with between-turn lifecycle (turn-history management, budget
 * pruning across requests) would conflate time scales. Between-turn lifecycle is a property
 * of the {@link PersistenceMode#PERSISTENT} half of the persistence axis, not an
 * {@link IterationController} concern.
 *
 * <p>Invoked by the engine after each LLM call within an {@link IterationMode#WITHIN_TURN_ITERATION}
 * shape, after all {@link StreamConsumer}s have run and their {@link StreamConsumerResult#messageDeltas}
 * have been appended to the message list. The controller inspects the context (including
 * iteration count and updated messages) and returns an {@link IterationDecision}.
 *
 * <p>{@link IterationMode#ONE_SHOT} shapes use the {@link io.justsearch.agent.api.conversation.SingleHopController}
 * (or any {@link IterationController} that always returns {@link IterationDecision#STOP_SUCCESS}).
 *
 * <p>Example controllers (not exhaustive): {@code SingleHop} (always {@code STOP_SUCCESS}),
 * {@code ToolBoundedIteration(maxIterations, forceCommitAt)} (the agent loop; encapsulates
 * handoff state internally — handoff surfaces to the engine as plain {@link IterationDecision#CONTINUE}
 * with the controller's profile swapped), {@code MultiPassSynthesis(passes)} (hierarchical
 * summarize).
 *
 * <p><strong>Phase B status</strong>: the SPI interface is defined; the agent loop's iteration
 * logic stays inside the loop's body (shape-driven mode — the engine delegates to the loop's
 * own runner; this controller is not invoked for the agent shape).
 */
public interface IterationController {

  /**
   * Stable identifier within the substrate's controller registry. Used in
   * {@code ConversationShape.iterationControllerId} to reference this controller from a
   * shape's manifest.
   */
  String id();

  /**
   * Decide whether to call the model again. Called by the engine after each LLM call within
   * a {@link IterationMode#WITHIN_TURN_ITERATION} shape, after stream consumers have run.
   *
   * @param ctx the per-request context (includes updated messages and iteration count)
   * @return the decision
   */
  IterationDecision next(ConversationContext ctx);

  /**
   * Slice 491 §9.D Phase E (G4) — which shape trust tiers may compose this controller.
   * Default = all tiers.
   */
  default java.util.Set<io.justsearch.agent.api.registry.TrustTier> allowedShapeTiers() {
    return java.util.EnumSet.allOf(io.justsearch.agent.api.registry.TrustTier.class);
  }
}
