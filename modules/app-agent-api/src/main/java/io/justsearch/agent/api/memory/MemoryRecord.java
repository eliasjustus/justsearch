/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * Tempdoc 561 P-E — one learned item in the agent's memory: the persistence tier ABOVE transcripts
 * (what the agent has come to know / a user preference / a durable fact), as opposed to the
 * conversation thread (what was said) or the action ledger (what was done). This is a NEW canonical
 * record under the SAME register+gate discipline as the thread and the action log — one authority
 * ({@link MemoryStore}), inspectable ("what it knows") and user-controllable ("forget this"). The
 * single-authority + on-device user control is the privacy story no cloud peer can match (§P-E).
 *
 * @param id stable id (the forget key)
 * @param kind the memory class — e.g. {@code "fact"}, {@code "preference"}, {@code "summary"}
 * @param content the learned content (human-inspectable verbatim)
 * @param sourceConversationId the conversation this was learned in (provenance; null if ambient)
 * @param actor the actor identity that learned it (the P-E actor-cardinality seed)
 * @param createdAt when it was learned
 */
public record MemoryRecord(
    String id,
    String kind,
    String content,
    String sourceConversationId,
    String actor,
    Instant createdAt) {

  public MemoryRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(content, "content");
    kind = kind == null || kind.isBlank() ? "fact" : kind;
    actor = actor == null || actor.isBlank() ? "primary" : actor;
    createdAt = createdAt == null ? Instant.EPOCH : createdAt;
  }
}
