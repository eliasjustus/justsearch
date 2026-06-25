/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * The decision returned by {@link IterationController#next} after each within-turn iteration.
 *
 * <p>Per tempdoc 491 §5.1: substrate-level iteration decision has three values. Shape-internal
 * lifecycle transitions (agent handoff, multi-pass phase advance) are NOT substrate-level
 * decisions — they happen inside the controller's implementation and surface to the engine as
 * plain {@link #CONTINUE} with the controller's internal state mutated. This keeps the substrate
 * uncoupled from shape-specific lifecycle vocabulary.
 *
 * <ul>
 *   <li>{@link #CONTINUE} — call the LLM again with the updated message list.
 *   <li>{@link #STOP_SUCCESS} — the conversation completed successfully; emit a final
 *       {@code done} SSE event and return.
 *   <li>{@link #STOP_ERROR} — the conversation terminated due to an error; emit an
 *       {@code error} SSE event and return. The controller is expected to also surface an
 *       error event explaining the cause.
 * </ul>
 */
public enum IterationDecision {
  CONTINUE,
  STOP_SUCCESS,
  STOP_ERROR
}
