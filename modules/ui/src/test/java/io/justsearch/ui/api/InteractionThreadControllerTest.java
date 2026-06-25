package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 561 P-A/P-B (correction) — GET /api/thread/{id} as a read-time projection over
 * ConversationStore (chat) + AgentService.threadEvents (agent), interleaved by timestamp. No store.
 */
final class InteractionThreadControllerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private JsonNode invokeGet(InteractionThreadController controller, String id) {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(id);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    AtomicReference<byte[]> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0, byte[].class));
              return ctx;
            })
        .when(ctx)
        .result(any(byte[].class));
    controller.handleGet(ctx);
    try {
      return MAPPER.readTree(captured.get());
    } catch (Exception e) {
      throw new IllegalStateException("could not parse thread response", e);
    }
  }

  @Test
  @DisplayName("interleaves chat turns (ConversationStore) and agent activity (AgentService) by timestamp")
  void interleavesBothPlanesByTimestamp() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-1"))
        .thenReturn(
            List.of(
                Map.of("id", "u1", "role", "user", "content", "find invoices", "ts",
                    "2026-01-01T00:00:01Z"),
                Map.of("id", "a1", "role", "assistant", "content", "found 12", "ts",
                    "2026-01-01T00:00:04Z"),
                // system/context messages are not thread turns
                Map.of("id", "s1", "role", "system", "content", "you are an agent", "ts",
                    "2026-01-01T00:00:00Z")));

    AgentService agentService = mock(AgentService.class);
    when(agentService.threadEvents("conv-1"))
        .thenReturn(
            List.of(
                new InteractionEvent(
                    "c1:completed",
                    "conv-1",
                    Instant.parse("2026-01-01T00:00:02Z"),
                    InteractionEventKind.TOOL_ACTIVITY,
                    "agent",
                    "",
                    Map.of("callId", "c1", "toolName", "core_search_index", "status", "completed"))));

    JsonNode body = invokeGet(new InteractionThreadController(conversationStore, agentService), "conv-1");

    assertEquals("conv-1", body.get("conversationId").asString());
    JsonNode events = body.get("events");
    // user (1s) -> tool (2s) -> assistant (4s); the system message is dropped.
    assertEquals(3, events.size());
    assertEquals("USER_MESSAGE", events.get(0).get("kind").asString());
    assertEquals("find invoices", events.get(0).get("content").asString());
    assertEquals("TOOL_ACTIVITY", events.get(1).get("kind").asString());
    assertEquals("core_search_index", events.get(1).get("attributes").get("toolName").asString());
    assertEquals("ASSISTANT_MESSAGE", events.get(2).get("kind").asString());
    assertEquals("found 12", events.get(2).get("content").asString());
  }

  @Test
  @DisplayName("empty conversation yields an empty events array")
  void emptyConversation() {
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("nope")).thenReturn(List.of());
    AgentService agentService = mock(AgentService.class);
    when(agentService.threadEvents("nope")).thenReturn(List.of());

    JsonNode body = invokeGet(new InteractionThreadController(conversationStore, agentService), "nope");
    assertEquals(0, body.get("events").size());
  }

  @Test
  @DisplayName("561 P-A: a chat turn's persisted citations + calibration are surfaced on the thread event (evidence on the record)")
  void surfacesEvidenceFromTheRecord() {
    // NOTE: this is the PROJECTION half of evidence-on-record. That the production pipeline actually
    // PRODUCES this persisted state — an EPHEMERAL RAG turn writing citations + calibration under the
    // conversationId — is proven by SubstrateDrivenEngineTest
    // #ephemeralRecordsToThreadPersistsEvidenceUnderConversationId. Both halves together close the
    // loop; neither alone is sufficient (the pre-561 gap was a green projection test over a state the
    // pipeline never created).
    ConversationStore conversationStore = mock(ConversationStore.class);
    when(conversationStore.loadHistory("conv-e"))
        .thenReturn(
            List.of(
                Map.of(
                    "id", "a1",
                    "role", "assistant",
                    "content", "grounded answer",
                    "ts", "2026-01-01T00:00:01Z",
                    "citations", List.of(Map.of("parentDocId", "doc-1", "startChar", 0)),
                    "calibration", Map.of("bestChunkScore", 0.91, "retrievalCoverage", 0.5))));
    AgentService agentService = mock(AgentService.class);
    when(agentService.threadEvents("conv-e")).thenReturn(List.of());

    JsonNode body =
        invokeGet(new InteractionThreadController(conversationStore, agentService), "conv-e");
    JsonNode ev = body.get("events").get(0);
    assertEquals("ASSISTANT_MESSAGE", ev.get("kind").asString());
    JsonNode cites = ev.get("attributes").get("citations");
    assertEquals(1, cites.size());
    assertEquals("doc-1", cites.get(0).get("parentDocId").asString());
    // The producer-owned calibration is projected too (rendered FROM the record, not re-derived).
    JsonNode cal = ev.get("attributes").get("calibration");
    assertEquals(0.91, cal.get("bestChunkScore").asDouble());
  }
}
