/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * Persistence axis of the {@code Conversation} partition.
 *
 * <p>Per tempdoc 491 §5.2: the substrate partitions {@code Conversation} on two orthogonal
 * axes (Iteration × Persistence). The persistence axis distinguishes ephemeral (one-shot,
 * no thread history) from persistent (resumable conversation thread).
 *
 * <ul>
 *   <li>{@link #EPHEMERAL} — each request is fresh; no thread history; no resumption.
 *       Examples: summarize, RAG ask, hierarchical summarize.
 *   <li>{@link #PERSISTENT} — thread history persists across requests; budget-aware pruning;
 *       resumable via session id. Examples: agent loop, continuous chat (e.g., URL-emission).
 * </ul>
 *
 * <p>Persistence is shape-implemented, not substrate-shared. Each {@link #PERSISTENT} shape
 * carries its own {@code ConversationStore} following the per-shape directory layout
 * {@code tmp/conversations/<shapeId>/<sessionId>/{meta.json, messages.jsonl}} + a per-shape
 * {@code <ShapeId>SchemaUpcaster} chain (resolves §10 Q6). {@link #EPHEMERAL} shapes use a
 * {@code NoOpConversationStore}.
 */
public enum PersistenceMode {
  /** Each request is fresh; no thread history; no resumption. */
  EPHEMERAL,
  /** Thread history persists; budget-aware pruning; resumable by session id. */
  PERSISTENT
}
