/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 863 §4.A.3 (A-2) — the thread projection's read-time syntheses, narrowed for a STAMPED run.
 *
 * <p>The pair of tests that matter together: a stamped run's turns already live on the conversation
 * record, so this projection must NOT synthesise its own copies (or one delegate turn renders twice
 * through a merge that has no dedup); an unstamped run's turns live nowhere else, so it must keep
 * them exactly as it always did. Forward-only means both must hold at once, with no backfill.
 */
final class AgentRunQueryServiceThreadEventsTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("863 A-2: a STAMPED run synthesises neither its user turn nor its terminal answer")
  void stampedRunSuppressesBothSyntheses() {
    AgentRunQueryService query = queryOver(store -> writeRun(store, true));

    List<InteractionEvent> events = query.threadEvents("conv-1");

    assertFalse(
        events.stream().anyMatch(e -> e.kind() == InteractionEventKind.USER_MESSAGE),
        "the conversation record already holds the user turn");
    assertFalse(
        events.stream().anyMatch(e -> e.kind() == InteractionEventKind.ASSISTANT_MESSAGE),
        "the conversation record already holds the answer");
    // The rest of the run is untouched: suppression narrows two syntheses, it does not blank a run.
    assertEquals(
        1,
        events.stream().filter(e -> e.kind() == InteractionEventKind.TOOL_ACTIVITY).count(),
        "the tool step is the run plane's alone and stays");
  }

  @Test
  @DisplayName(
      "863 A-2: an UNSTAMPED run keeps both syntheses, with the evidence the store plane now mirrors")
  void unstampedRunKeepsBothSyntheses() {
    AgentRunQueryService query = queryOver(store -> writeRun(store, false));

    List<InteractionEvent> events = query.threadEvents("conv-1");

    InteractionEvent user =
        events.stream()
            .filter(e -> e.kind() == InteractionEventKind.USER_MESSAGE)
            .findFirst()
            .orElseThrow();
    assertEquals("run-1:user", user.id(), "the pre-863 mint, unchanged");
    assertEquals("delegate this", user.content());

    InteractionEvent answer =
        events.stream()
            .filter(e -> e.kind() == InteractionEventKind.ASSISTANT_MESSAGE)
            .findFirst()
            .orElseThrow();
    assertEquals("the answer", answer.content());
    // THE PARITY REFERENCE (863 §4.A.4): these four attributes are what the run plane carries, and
    // they are exactly what `ConversationEngine.persistedAssistant` + `chatTurn` now carry on the
    // store plane — so the suppression above swaps the carrier without changing what is carried.
    assertEquals(1, ((List<?>) answer.attributes().get("sources")).size());
    assertEquals(1, ((List<?>) answer.attributes().get("citations")).size());
    assertEquals("cross-encoder", answer.attributes().get("citationScorer"));
    assertEquals("BUDGET_EXHAUSTED", answer.attributes().get("disposition"));
    // ... and the two the agent `done` does not produce are absent on BOTH planes.
    assertFalse(answer.attributes().containsKey("calibration"));
    assertFalse(answer.attributes().containsKey("claimMatches"));
  }

  @Test
  @DisplayName("863 A-2: suppression keys on the terminal-answer MINT, so node answers survive")
  void nodeAnswersAreNotTheTerminalAnswer() {
    AgentRunQueryService query =
        queryOver(
            store -> {
              Map<String, Object> meta = meta(true);
              store.runEvents().writeRunMeta("run-1", meta);
              store
                  .runEvents()
                  .appendRawEvents(
                      "run-1",
                      List.of(
                          event(
                              "2026-08-25T10:00:01Z",
                              "node_output",
                              Map.of("index", 0, "nodeId", "n1", "output", "node one said this")),
                          event(
                              "2026-08-25T10:00:02Z",
                              "done",
                              Map.of("finalResponse", "the answer"))));
            });

    List<InteractionEvent> events = query.threadEvents("conv-1");

    List<InteractionEvent> assistants =
        events.stream().filter(e -> e.kind() == InteractionEventKind.ASSISTANT_MESSAGE).toList();
    assertEquals(1, assistants.size(), "the node answer survives; only the terminal mint is dropped");
    assertEquals("node one said this", assistants.get(0).content());
    assertTrue(
        assistants.get(0).id().contains(":node:"),
        "a rule keyed on POSITION would have eaten this instead");
  }

  @Test
  @DisplayName("863 A-2: thinking that would have landed on the suppressed answer re-attaches")
  void trailingReasoningReattachesToTheLastSurvivingEvent() {
    AgentRunQueryService query =
        queryOver(
            store -> {
              store.runEvents().writeRunMeta("run-1", meta(true));
              store
                  .runEvents()
                  .appendRawEvents(
                      "run-1",
                      List.of(
                          event(
                              "2026-08-25T10:00:01Z",
                              "tool_exec_completed",
                              Map.of("callId", "c1", "success", true, "output", "ok")),
                          event(
                              "2026-08-25T10:00:02Z",
                              "reasoning_chunk",
                              Map.of("text", "now I will summarise")),
                          event("2026-08-25T10:00:03Z", "budget_update", Map.of("remaining", 5)),
                          event(
                              "2026-08-25T10:00:04Z",
                              "done",
                              Map.of("finalResponse", "the answer"))));
            });

    List<InteractionEvent> events = query.threadEvents("conv-1");

    InteractionEvent tool =
        events.stream()
            .filter(e -> e.kind() == InteractionEventKind.TOOL_ACTIVITY)
            .findFirst()
            .orElseThrow();
    List<?> blocks = (List<?>) tool.attributes().get("reasoning");
    assertEquals(1, blocks.size(), "the block the fold had attached to the dropped answer");
    assertEquals(
        "now I will summarise", ((Map<?, ?>) blocks.get(0)).get("text"), "and its text is intact");
  }

  // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

  private AgentRunQueryService queryOver(java.util.function.Consumer<AgentRunStore> seed) {
    AgentRunStore store = new AgentRunStore(tempDir.resolve("agent-runs"));
    seed.accept(store);
    return new AgentRunQueryService(store, null, null, null, null, null);
  }

  /** One agent run of {@code conv-1}: a tool step, then the terminal grounded answer. */
  private static void writeRun(AgentRunStore store, boolean stamped) {
    store.runEvents().writeRunMeta("run-1", meta(stamped));
    Map<String, Object> done = new LinkedHashMap<>();
    done.put("finalResponse", "the answer");
    done.put(
        "sources",
        List.of(Map.of("parentDocId", "doc-7", "chunkIndex", 0, "path", "a/b.md", "title", "B")));
    done.put("citations", List.of(Map.of("sentenceText", "the answer", "sourceIndex", 0)));
    done.put("citationScorer", "cross-encoder");
    done.put("disposition", "BUDGET_EXHAUSTED");
    store
        .runEvents()
        .appendRawEvents(
            "run-1",
            List.of(
                event(
                    "2026-08-25T10:00:01Z",
                    "tool_exec_completed",
                    Map.of("callId", "c1", "success", true, "output", "ok")),
                event("2026-08-25T10:00:02Z", "done", done)));
  }

  private static Map<String, Object> meta(boolean stamped) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("sessionId", "run-1");
    meta.put("shapeId", "core.agent-run");
    meta.put("conversationId", "conv-1");
    meta.put("startedAt", "2026-08-25T10:00:00Z");
    meta.put("updatedAt", "2026-08-25T10:00:05Z");
    meta.put("messages", List.of(Map.of("role", "user", "content", "delegate this")));
    // The forward-only boundary: a pre-863 run has NO such key, and reading its absence as false is
    // the true thing about it — it really did record nothing to the answer plane.
    if (stamped) {
      meta.put("recordsToThread", true);
    }
    return meta;
  }

  private static Map<String, Object> event(
      String timestamp, String eventType, Map<String, Object> payload) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("timestamp", timestamp);
    record.put("shapeId", "core.agent-run");
    record.put("eventType", eventType);
    record.put("payload", new LinkedHashMap<>(payload));
    return record;
  }
}
