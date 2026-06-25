/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.interaction;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One durable, append-only event in a conversation's interaction log — the canonical Tier-2 record
 * (tempdoc 561 P-A/P-B).
 *
 * <p>This is the SINGLE authority that both the answer plane (chat / RAG / extract) and the agent
 * plane (tool activity) write into, so the unified thread, History, Timeline, and budget can all be
 * read-projections of ONE record. 561 P-B's invariant — the views "cannot disagree" — holds by
 * construction once every plane writes here and every view projects from here.
 *
 * <p><b>Why a new log, not an extension of an existing store.</b> {@code ConversationStore}'s own
 * contract documents that {@code AgentRunStore} deliberately stays agent-specific (richer per-run
 * data: tool calls, traces, handoff, budget) while {@code ConversationStore} handles plain message
 * threads. Neither is plane-neutral. The unified thread therefore needs a third, plane-neutral
 * authority rather than overloading either existing store (AHA: unify only what shares a reason to
 * change). The in-memory {@code ActionEvent} ledger is capacity-bounded and cannot be the durable
 * thread authority; it remains a recent-activity projection.
 *
 * @param id stable per-event id (dedup / idempotency key)
 * @param conversationId the cross-plane join key — the answer plane's {@code sessionId} and the
 *     agent loop's {@code correlationId} (561 P-A1) resolve to the same value for one conversation
 * @param occurredAt the authoritative order key; the FE thread never invents its own timestamp
 * @param kind the plane-neutral event kind
 * @param originator {@code "user" | "agent" | "system"} (mirrors {@code ActionEvent.originator})
 * @param content message text for message kinds; an empty string for non-message kinds
 * @param attributes kind-specific extras (tool name, status, callId, citations…) — keeps the
 *     envelope from widening per kind; defaulted to an empty immutable map
 */
public record InteractionEvent(
    String id,
    String conversationId,
    Instant occurredAt,
    InteractionEventKind kind,
    String originator,
    String content,
    Map<String, Object> attributes) {

  public InteractionEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(kind, "kind");
    originator = originator == null ? "system" : originator;
    content = content == null ? "" : content;
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** A user or assistant message turn (the answer plane's two emit sites use these). */
  public static InteractionEvent message(
      String id,
      String conversationId,
      Instant occurredAt,
      InteractionEventKind kind,
      String originator,
      String content) {
    return new InteractionEvent(id, conversationId, occurredAt, kind, originator, content, Map.of());
  }
}
