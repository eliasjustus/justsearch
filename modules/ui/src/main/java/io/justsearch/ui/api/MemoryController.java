/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 561 P-E — {@code /api/memory}: the user-facing projection of the agent's learned-memory
 * canonical record ({@link MemoryStore}). The two first-class user controls the §P-E design requires:
 * inspect ("what it knows", {@code GET}) and forget ({@code DELETE}). {@code POST} is the producer
 * seam (the agent / an operator records a durable fact). One authority, rendered + edited; no second
 * store (the {@code operation-surface} register's memory single-authority guard fails the build on a
 * fork).
 */
public final class MemoryController {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MemoryStore memoryStore;

  public MemoryController(MemoryStore memoryStore) {
    this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
  }

  /** {@code GET /api/memory} — the inspectable "what it knows" projection (newest first). */
  public void handleList(Context ctx) {
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (MemoryRecord r : memoryStore.whatItKnows()) {
        rows.add(toWire(r));
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("memories", rows);
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      LOG.error("Failed to list memory", e);
      ctx.status(500).json(Map.of("error", "Failed to read memory"));
    }
  }

  /** {@code POST /api/memory} — the producer seam: record a learned item. */
  @SuppressWarnings("unchecked")
  public void handleRemember(Context ctx) {
    try {
      Map<String, Object> body =
          ctx.body() == null || ctx.body().isEmpty()
              ? Map.of()
              : MAPPER.readValue(ctx.body(), Map.class);
      String content = str(body.get("content"));
      if (content == null || content.isBlank()) {
        ctx.status(400).json(Map.of("error", "content is required"));
        return;
      }
      String id = str(body.get("id"));
      MemoryRecord rec =
          new MemoryRecord(
              id != null && !id.isBlank() ? id : UUID.randomUUID().toString(),
              str(body.get("kind")),
              content,
              str(body.get("sourceConversationId")),
              str(body.get("actor")),
              Instant.now());
      memoryStore.remember(rec);
      ctx.json(Map.of("ok", true, "id", rec.id()));
    } catch (Exception e) {
      LOG.error("Failed to record memory", e);
      ctx.status(500).json(Map.of("error", "Failed to record memory"));
    }
  }

  /** {@code DELETE /api/memory/{id}} — user control: forget one item. */
  public void handleForget(Context ctx) {
    memoryStore.forget(ctx.pathParam("id"));
    ctx.json(Map.of("ok", true));
  }

  private static Map<String, Object> toWire(MemoryRecord r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("kind", r.kind());
    m.put("content", r.content());
    m.put("sourceConversationId", r.sourceConversationId());
    m.put("actor", r.actor());
    m.put("createdAt", r.createdAt().toString());
    return m;
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }
}
