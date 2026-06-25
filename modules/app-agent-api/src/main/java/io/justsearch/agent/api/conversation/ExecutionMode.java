/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * Execution mode for a {@code ConversationShape} under {@code ConversationEngine}.
 *
 * <p>Per tempdoc 491 §5.4: the engine has two execution modes chosen by the shape.
 *
 * <ul>
 *   <li>{@link #SUBSTRATE_DRIVEN} — the engine controls the per-iteration loop. It assembles the
 *       system prompt from {@link PromptContributor}s, runs {@link ContextInjector}s, calls
 *       {@code OnlineAiService}, dispatches stream events to {@link StreamConsumer}s, appends
 *       their {@code messageDeltas}, then invokes {@link IterationController#next} to decide
 *       whether to loop. Default for fresh shapes built against the substrate.
 *   <li>{@link #SHAPE_DRIVEN} — the engine delegates to the shape's runner (a single callable
 *       taking {@code (request, sink)}). The runner is responsible for its own iteration; the
 *       SPIs are still composed inside the runner but their orchestration is the runner's
 *       concern. Reserved for encapsulating existing implementations whose iteration logic is
 *       correctness-critical and not to be refactored (e.g., the agent loop).
 * </ul>
 *
 * <p>The §6 substrate-shape rule applies in both modes; the difference is who invokes the SPIs.
 */
public enum ExecutionMode {
  /** Engine drives the per-iteration loop and SPI invocation. */
  SUBSTRATE_DRIVEN,
  /** Engine delegates to the shape's own runner; the runner controls iteration. */
  SHAPE_DRIVEN
}
