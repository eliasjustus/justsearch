package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 859 slice C PR-2 — {@code GET /api/chat/conversations} joins the TWO records that hold
 * conversations, and {@code DELETE} removes a conversation from both.
 *
 * <p>Before the join, a delegate conversation had a complete disk-backed record (served in full by
 * {@code GET /api/thread/{id}}, which already merges both planes) and no index entry anywhere: the
 * sidebar could not show it, so there was no door to it. These tests pin the contract the join
 * commits to — per-store limit, shape filtering, one row per conversation, honest provenance, and an
 * absent message count rather than a fabricated or expensive one.
 */
final class ChatControllerConversationJoinTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String AGENT_SHAPE = "core.agent-run";

  @Test
  @DisplayName("an agent-only conversation lists, with honest fields and NO messageCount")
  void agentOnlyConversationIsListed() {
    FakeStore store = new FakeStore();
    AgentService agent = agentWith(run("conv-agent", 500L, 900L, "summarise the renewal folder"));

    JsonNode rows = list(store, agent, null, null);

    assertEquals(1, rows.size());
    JsonNode row = rows.get(0);
    assertEquals("conv-agent", row.get("sessionId").asString(), "the conversation id is REAL");
    assertEquals(AGENT_SHAPE, row.get("shapeId").asString());
    assertEquals(500L, row.get("createdAtMs").asLong());
    assertEquals(900L, row.get("lastActiveAtMs").asLong());
    assertEquals("summarise the renewal folder", row.get("firstUserMessage").asString());
    assertFalse(
        row.get("storeBacked").asBoolean(),
        "only the capability claim is corrected: no ConversationStore session backs this row");
    assertFalse(
        row.has("messageCount"),
        "absent = not told. A count here means projecting the thread per row, per request.");
  }

  @Test
  @DisplayName("a store-backed row is unchanged — no storeBacked key, and its messageCount stays")
  void storeRowsKeepTheirShape() {
    FakeStore store = new FakeStore();
    store.create("uc-1", 100L, 200L);
    JsonNode rows = list(store, agentWith(), null, null);

    assertEquals(1, rows.size());
    assertFalse(
        rows.get(0).has("storeBacked"),
        "conditional key: absence keeps meaning what it always did — store-backed");
    assertEquals(2, rows.get(0).get("messageCount").asInt());
  }

  @Test
  @DisplayName("a conversation in BOTH records is listed once, as its store row")
  void mixedConversationAppearsOnce() {
    FakeStore store = new FakeStore();
    store.create("uc-mixed", 100L, 200L);
    AgentService agent = agentWith(run("uc-mixed", 50L, 300L, "the delegate's prompt"));

    JsonNode rows = list(store, agent, null, null);

    assertEquals(1, rows.size(), "one conversation, one row — the double-render this design refuses");
    assertFalse(rows.get(0).has("storeBacked"), "and it is the STORE row, so the action set is full");
    assertEquals("why did the renewal fail?", rows.get(0).get("firstUserMessage").asString());
  }

  @Test
  @DisplayName("limit applies per store, and the merged list is re-limited so the response respects it")
  void limitAppliesPerStoreAndTheMergeIsReLimited() {
    FakeStore store = new FakeStore();
    store.create("uc-1", 10L, 41L);
    store.create("uc-2", 10L, 40L);
    store.create("uc-3", 10L, 39L);
    AgentService agent =
        agentWith(
            run("conv-a", 10L, 38L, "a"), run("conv-b", 10L, 37L, "b"), run("conv-c", 10L, 36L, "c"));

    JsonNode rows = list(store, agent, null, "4");

    assertEquals(4, rows.size(), "six conversations exist; the response never exceeds the limit");
    assertEquals(4, store.lastLimit, "and each store was asked for a full window of its own");
    verify(agent).conversationSummaries(4);
    assertEquals(
        List.of("uc-1", "uc-2", "uc-3", "conv-a"),
        idsOf(rows),
        "the merge keeps the most recently active of the union, not the first store's first four");
  }

  @Test
  @DisplayName("a shapeId naming another shape excludes synthesized rows ENTIRELY")
  void shapeFilterExcludesSynthesizedRows() {
    FakeStore store = new FakeStore();
    store.create("uc-1", 100L, 200L);
    AgentService agent = agentWith(run("conv-agent", 500L, 900L, "delegate"));

    JsonNode rows = list(store, agent, "core.rag-ask", null);

    assertEquals(List.of("uc-1"), idsOf(rows));
    verify(agent, never()).conversationSummaries(anyInt());
    assertEquals("core.rag-ask", store.lastShapeId, "the store side filters exactly as it always did");
  }

  @Test
  @DisplayName("a shapeId naming the agent shape INCLUDES them — the filter matches, it does not veto")
  void shapeFilterAdmitsTheAgentShape() {
    FakeStore store = new FakeStore();
    AgentService agent = agentWith(run("conv-agent", 500L, 900L, "delegate"));

    assertEquals(List.of("conv-agent"), idsOf(list(store, agent, AGENT_SHAPE, null)));
  }

  @Test
  @DisplayName("ordering puts a fresh delegate conversation where its timestamp says: first")
  void orderingFollowsLastActivityAcrossBothRecords() {
    FakeStore store = new FakeStore();
    store.create("uc-old", 10L, 100L);
    store.create("uc-older", 10L, 50L);
    AgentService agent = agentWith(run("conv-fresh", 10L, 999L, "just delegated"));

    assertEquals(
        List.of("conv-fresh", "uc-old", "uc-older"),
        idsOf(list(store, agent, null, null)),
        "a conversation the reader just ran must not land under everything they ran yesterday");
  }

  @Test
  @DisplayName("an unavailable agent capability lists the store's conversations and nothing else")
  void unavailableAgentIsNotAnError() {
    FakeStore store = new FakeStore();
    store.create("uc-1", 100L, 200L);

    Captured c = invoke(store, AgentService.unavailable(), ctrl -> ctrl::handleListSessions, null, null, null);

    assertEquals(200, c.status());
    assertEquals(List.of("uc-1"), idsOf(c.body().get("sessions")));
  }

  @Test
  @DisplayName("DELETE removes the conversation from BOTH records, so a run-backed row is removable")
  void deleteAlsoRemovesTheConversationsRuns() {
    FakeStore store = new FakeStore();
    store.create("conv-agent", 10L, 20L);
    AgentService agent = agentWith();
    when(agent.deleteConversationRuns("conv-agent")).thenReturn(2);

    Captured c =
        invoke(store, agent, ctrl -> ctrl::handleDeleteConversation, "conv-agent", null, null);

    assertEquals(200, c.status());
    assertTrue(c.body().get("ok").asBoolean());
    assertEquals(2, c.body().get("agentRunsDeleted").asInt());
    assertEquals(List.of("conv-agent"), store.deleted);
    verify(agent).deleteConversationRuns("conv-agent");
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private record Captured(int status, JsonNode body) {}

  private static List<String> idsOf(JsonNode rows) {
    List<String> ids = new ArrayList<>();
    rows.forEach(row -> ids.add(row.get("sessionId").asString()));
    return ids;
  }

  private static JsonNode list(
      FakeStore store, AgentService agent, String shapeId, String limit) {
    return invoke(store, agent, ctrl -> ctrl::handleListSessions, null, shapeId, limit)
        .body()
        .get("sessions");
  }

  private static Map<String, Object> run(
      String conversationId, long createdAtMs, long lastActiveAtMs, String preview) {
    var row = new LinkedHashMap<String, Object>();
    row.put("conversationId", conversationId);
    row.put("createdAtMs", createdAtMs);
    row.put("lastActiveAtMs", lastActiveAtMs);
    row.put("firstUserMessage", preview);
    row.put("runCount", 1);
    return row;
  }

  @SafeVarargs
  private static AgentService agentWith(Map<String, Object>... runs) {
    AgentService agent = mock(AgentService.class);
    when(agent.conversationSummaries(anyInt())).thenReturn(List.of(runs));
    return agent;
  }

  private interface HandlerPick {
    io.javalin.http.Handler pick(ChatController controller);
  }

  private static Captured invoke(
      FakeStore store,
      AgentService agent,
      HandlerPick pick,
      String sessionId,
      String shapeId,
      String limit) {
    ChatController controller =
        new ChatController(
            null,
            new SseWriter(null),
            null,
            store,
            io.justsearch.app.api.OnlineAiService::unavailable,
            () -> agent);
    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    Context ctx = mock(Context.class);
    if (sessionId != null) when(ctx.pathParam("sessionId")).thenReturn(sessionId);
    when(ctx.queryParam(anyString())).thenReturn(null);
    when(ctx.queryParam("shapeId")).thenReturn(shapeId);
    when(ctx.queryParam("limit")).thenReturn(limit);
    doAnswer(
            inv -> {
              status.set(inv.getArgument(0, Integer.class));
              return ctx;
            })
        .when(ctx)
        .status(anyInt());
    doAnswer(
            inv -> {
              json.set(inv.getArgument(0));
              return ctx;
            })
        .when(ctx)
        .json(any(Object.class));
    try {
      pick.pick(controller).handle(ctx);
    } catch (Exception e) {
      throw new AssertionError("handler threw", e);
    }
    return new Captured(
        status.get(), json.get() == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(json.get()));
  }

  /** An in-memory conversation store that records what the controller asked it for. */
  private static final class FakeStore implements ConversationStore {
    final List<SessionSummary> rows = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    String lastShapeId;
    int lastLimit;

    void create(String sessionId, long createdAtMs, long lastActiveAtMs) {
      rows.add(
          new SessionSummary(
              sessionId,
              "core.rag-ask",
              createdAtMs,
              lastActiveAtMs,
              2,
              "why did the renewal fail?",
              null,
              null,
              null,
              null,
              null,
              null));
    }

    @Override
    public List<SessionSummary> listSessions(String shapeId, int limit) {
      lastShapeId = shapeId;
      lastLimit = limit;
      List<SessionSummary> sorted = new ArrayList<>(rows);
      sorted.sort((a, b) -> Long.compare(b.lastActiveAtMs(), a.lastActiveAtMs()));
      return sorted.size() <= limit ? sorted : sorted.subList(0, limit);
    }

    @Override
    public Optional<SessionSummary> getSessionMeta(String sessionId) {
      return rows.stream().filter(s -> s.sessionId().equals(sessionId)).findFirst();
    }

    @Override
    public void deleteSession(String sessionId) {
      deleted.add(sessionId);
      rows.removeIf(s -> s.sessionId().equals(sessionId));
    }

    @Override
    public List<Map<String, Object>> loadHistory(String sessionId) {
      return List.of();
    }

    @Override
    public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {}

    @Override
    public void branchFrom(
        String parentSessionId, String branchPointMessageId, String newSessionId) {}

    @Override
    public void setContextFloor(String sessionId, String floorMessageId) {}

    @Override
    public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
      return List.of();
    }

    @Override
    public void compactContext(String sessionId, String floorMessageId, String summaryText) {}

    @Override
    public void excludeMessage(String sessionId, String messageId, boolean excluded) {}

    @Override
    public List<String> excludedMessageIds(String sessionId) {
      return List.of();
    }
  }
}
