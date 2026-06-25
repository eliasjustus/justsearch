/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * Iteration axis of the {@code Conversation} partition.
 *
 * <p>Per tempdoc 491 §5.2: the substrate partitions {@code Conversation} on two orthogonal
 * axes (Iteration × Persistence). The iteration axis distinguishes one-shot model calls from
 * within-request looping.
 *
 * <ul>
 *   <li>{@link #ONE_SHOT} — one LLM call per user request. Examples: summarize, RAG ask,
 *       structured extraction, URL-emission chat.
 *   <li>{@link #WITHIN_TURN_ITERATION} — multiple LLM calls per user request, governed by
 *       {@link IterationController}. Examples: agent loop (tool iteration), hierarchical
 *       summarize (multi-pass synthesis).
 * </ul>
 *
 * <p>This axis is independent of {@link PersistenceMode} — the four cells of the partition
 * (OneShot × Ephemeral, OneShot × Persistent, WithinTurnIteration × Ephemeral,
 * WithinTurnIteration × Persistent) each map to a coherent set of LLM-output use-cases.
 */
public enum IterationMode {
  /** One LLM call per user request; no within-turn looping. */
  ONE_SHOT,
  /** Multiple LLM calls per user request, governed by an {@link IterationController}. */
  WITHIN_TURN_ITERATION
}
