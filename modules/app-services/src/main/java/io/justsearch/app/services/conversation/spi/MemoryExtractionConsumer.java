/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Tempdoc 561 P-E — the PASSIVE (answer-plane) learning producer. A {@link StreamConsumer} wired onto
 * the chat / RAG-ask shapes: at the end of a turn it scans the user's message for an explicit,
 * high-precision memory cue ("remember that …", "note that …", "I prefer …", "my … is …", "call me
 * …") and persists the durable fact into the single-authority {@link MemoryStore} the user inspects +
 * forgets via {@code /api/memory} — so the answer plane contributes to memory too, not just the agent.
 *
 * <p>Deliberately a precise heuristic, not a per-turn LLM extraction call: per 559 §15 cost-realism an
 * extra inference round-trip on every chat turn is a poor trade. The id is a content hash, so restating
 * the same preference does not create a duplicate ({@code MemoryStore.remember} is idempotent on id).
 * A richer LLM-based semantic extractor is a documented evolution (it would thread an inference client
 * + a token budget into this consumer).
 */
public final class MemoryExtractionConsumer implements StreamConsumer {

  /** Stable id used by {@code ConversationShape.streamConsumerIds}. */
  public static final String ID = "core.memory-extraction";

  private static final int MAX_CONTENT_CHARS = 240;

  private final MemoryStore memoryStore;

  public MemoryExtractionConsumer(MemoryStore memoryStore) {
    this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    return StreamConsumerResult.empty();
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    try {
      String userMessage = lastUserMessage(ctx.messages());
      Extracted fact = extract(userMessage);
      if (fact != null) {
        memoryStore.remember(
            new MemoryRecord(
                "chat:" + Integer.toHexString(fact.content.toLowerCase(Locale.ROOT).hashCode()),
                fact.kind,
                fact.content,
                ctx.sessionId(),
                "chat",
                Instant.now()));
      }
    } catch (RuntimeException ignored) {
      // Best-effort: a failed extraction must never break the user's turn.
    }
    return StreamConsumerResult.empty();
  }

  private static String lastUserMessage(List<Map<String, Object>> messages) {
    if (messages == null) {
      return null;
    }
    for (int i = messages.size() - 1; i >= 0; i--) {
      Map<String, Object> m = messages.get(i);
      if ("user".equals(m.get("role")) && m.get("content") instanceof String s) {
        return s;
      }
    }
    return null;
  }

  private record Extracted(String content, String kind) {}

  /**
   * High-precision cue extraction. Returns null when no explicit memory-worthy statement is present
   * (the common case) — only fires on a clear directive or a first-person preference/identity fact.
   */
  private static Extracted extract(String message) {
    if (message == null) {
      return null;
    }
    String trimmed = message.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);

    // Explicit directive: "remember that X" / "remember: X" / "please remember X" / "note that X".
    for (String cue : List.of("remember that ", "remember: ", "please remember ", "note that ", "remember ")) {
      int idx = lower.indexOf(cue);
      if (idx >= 0) {
        String rest = trimmed.substring(idx + cue.length()).trim();
        return rest.isEmpty() ? null : new Extracted(cap(rest), "fact");
      }
    }
    // First-person preference / identity — the statement is self-contained, keep it verbatim.
    if (lower.startsWith("i prefer ")
        || lower.startsWith("i like ")
        || lower.startsWith("i love ")
        || lower.startsWith("i hate ")
        || lower.startsWith("i dislike ")
        || lower.startsWith("i always ")
        || lower.startsWith("i usually ")
        || lower.startsWith("i never ")
        || lower.startsWith("call me ")
        || lower.startsWith("my name is ")) {
      return new Extracted(cap(trimmed), "preference");
    }
    return null;
  }

  private static String cap(String s) {
    return s.length() <= MAX_CONTENT_CHARS ? s : s.substring(0, MAX_CONTENT_CHARS).trim() + "…";
  }
}
