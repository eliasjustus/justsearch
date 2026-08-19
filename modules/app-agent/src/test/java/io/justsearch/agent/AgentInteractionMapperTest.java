package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 561 P-A/P-B (correction) — read-time projection of a persisted {@code AgentRunStore} event
 * record (the {@code events.ndjson} shape) into a thread {@link InteractionEvent}. This is a
 * projection of the durable agent record, not a write to a second store.
 */
final class AgentInteractionMapperTest {

  private static final String CONV = "conv-1";

  private static Map<String, Object> rec(String eventType, Map<String, Object> payload) {
    return Map.of("timestamp", "2026-01-01T00:00:01Z", "eventType", eventType, "payload", payload);
  }

  private static InteractionEvent mapped(String eventType, Map<String, Object> payload) {
    return AgentInteractionMapper.fromRunEvent(rec(eventType, payload), CONV).orElseThrow();
  }

  @Test
  @DisplayName("done -> ASSISTANT_MESSAGE carrying the final response")
  void doneToAssistant() {
    InteractionEvent e =
        mapped("done", Map.of("finalResponse", "here is the answer", "iterationsUsed", 3));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, e.kind());
    assertEquals("agent", e.originator());
    assertEquals("here is the answer", e.content());
    assertEquals(CONV, e.conversationId());
    assertTrue(e.attributes().isEmpty(), "no grounding -> empty attributes");
  }

  @Test
  @DisplayName("done -> ASSISTANT_MESSAGE projects the answer's grounding sources + citations (565)")
  void doneCarriesGroundingEvidence() {
    Map<String, Object> source =
        Map.of("parentDocId", "f:/docs/x.md", "startLine", 1, "endLine", 25, "excerpt", "an excerpt");
    Map<String, Object> cite =
        Map.of("sentenceText", "a grounded sentence", "sourceIndex", 0, "similarity", 0.7);
    InteractionEvent e =
        mapped(
            "done",
            Map.of(
                "finalResponse", "the answer",
                "sources", List.of(source),
                "citations", List.of(cite)));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, e.kind());
    assertEquals("the answer", e.content());
    // §3.A/persistence — the grounding rides the persisted ASSISTANT_MESSAGE attributes so a reloaded
    // thread renders the same Sources pane + inline marks.
    assertEquals(List.of(source), e.attributes().get("sources"));
    assertEquals(List.of(cite), e.attributes().get("citations"));
  }

  @Test
  @DisplayName("tool_call_proposed -> TOOL_ACTIVITY carrying the tool identity (toolName + arguments) (565 §12.3.B)")
  void proposedToolCarriesIdentity() {
    InteractionEvent e =
        mapped(
            "tool_call_proposed",
            Map.of(
                "callId", "call-9",
                "toolName", "core_search",
                "arguments", "{\"query\":\"x\"}",
                "risk", "low"));
    assertEquals(InteractionEventKind.TOOL_ACTIVITY, e.kind());
    assertEquals("call-9", e.attributes().get("callId"));
    assertEquals("core_search", e.attributes().get("toolName"));
    // §12.3.B — the compact tool row needs the verb+target on the record (reload); arguments survive.
    assertEquals("{\"query\":\"x\"}", e.attributes().get("arguments"));
    assertEquals("proposed", e.attributes().get("status"));
  }

  @Test
  @DisplayName("tool_exec_completed -> completed TOOL_ACTIVITY keyed by callId")
  void completedTool() {
    InteractionEvent e =
        mapped(
            "tool_exec_completed", Map.of("callId", "call-7", "success", true, "output", "12 results"));
    assertEquals(InteractionEventKind.TOOL_ACTIVITY, e.kind());
    assertEquals("call-7:completed", e.id());
    assertEquals("completed", e.attributes().get("status"));
    assertEquals(Boolean.TRUE, e.attributes().get("success"));
    assertEquals("12 results", e.attributes().get("output"));
  }

  @Test
  @DisplayName("tool_call_pending -> pending TOOL_ACTIVITY carrying risk")
  void pendingTool() {
    InteractionEvent e =
        mapped(
            "tool_call_pending",
            Map.of("callId", "call-9", "toolName", "core_file_operations", "risk", "high"));
    assertEquals("call-9:pending", e.id());
    assertEquals("pending", e.attributes().get("status"));
    assertEquals("core_file_operations", e.attributes().get("toolName"));
    assertEquals("high", e.attributes().get("risk"));
  }

  @Test
  @DisplayName("rejected -> TOOL_ACTIVITY; error -> ERROR; handoff_executed -> HANDOFF")
  void rejectedErrorHandoff() {
    InteractionEvent rej =
        mapped("tool_call_rejected", Map.of("callId", "call-3", "reason", "User rejected"));
    assertEquals(InteractionEventKind.TOOL_ACTIVITY, rej.kind());
    assertEquals("call-3:rejected", rej.id());
    assertEquals("rejected", rej.attributes().get("status"));
    assertEquals("User rejected", rej.attributes().get("reason"));

    InteractionEvent err = mapped("error", Map.of("error", "boom", "errorCode", "LLM_ERROR"));
    assertEquals(InteractionEventKind.ERROR, err.kind());
    assertEquals("boom", err.content());
    assertEquals("LLM_ERROR", err.attributes().get("errorCode"));

    InteractionEvent ho =
        mapped("handoff_executed", Map.of("fromAgentId", "primary", "toAgentId", "researcher"));
    assertEquals(InteractionEventKind.HANDOFF, ho.kind());
    assertEquals("primary", ho.attributes().get("fromAgentId"));
    assertEquals("researcher", ho.attributes().get("toAgentId"));
  }

  @Test
  @DisplayName("search_executed -> SEARCH carrying query/mode/matchCount/resultCount/docIds/executedAt (S4b)")
  void searchExecutedToSearch() {
    InteractionEvent e =
        mapped(
            "search_executed",
            Map.of(
                "query", "invoices",
                "mode", "hybrid",
                "matchCount", 42,
                "resultCount", 10,
                "docIds", List.of("a.pdf", "b.pdf"),
                "executedAt", "2026-07-06T00:00:00Z"));
    assertEquals(InteractionEventKind.SEARCH, e.kind());
    assertEquals("user", e.originator());
    assertEquals("", e.content());
    assertEquals("invoices", e.attributes().get("query"));
    assertEquals("hybrid", e.attributes().get("mode"));
    assertEquals(42, e.attributes().get("matchCount"));
    assertEquals(10, e.attributes().get("resultCount"));
    assertEquals(List.of("a.pdf", "b.pdf"), e.attributes().get("docIds"));
    assertEquals("2026-07-06T00:00:00Z", e.attributes().get("executedAt"));
    // The id is the shared searchEventId derivation (conversationId:search:<epochMilli>), the same one
    // AgentRunStore.appendSearchEvent hands back to the write-path caller.
    assertEquals("conv-1:search:1767225601000", e.id());
  }

  @Test
  @DisplayName("node_started/node_completed -> PROGRESS boundary events carrying the node identity (565 §26.A)")
  void nodeBoundariesProjected() {
    InteractionEvent start =
        mapped("node_started", Map.of("nodeId", "think", "kind", "llm", "index", 0));
    assertEquals(InteractionEventKind.PROGRESS, start.kind());
    assertEquals("start", start.attributes().get("nodeBoundary"));
    assertEquals("think", start.attributes().get("nodeId"));
    assertEquals("llm", start.attributes().get("nodeKind"));
    assertEquals("think", start.attributes().get("label"));

    InteractionEvent end =
        mapped("node_completed", Map.of("nodeId", "think", "kind", "llm", "index", 0, "output", "ok"));
    assertEquals(InteractionEventKind.PROGRESS, end.kind());
    assertEquals("end", end.attributes().get("nodeBoundary"));
    assertEquals("think", end.attributes().get("nodeId"));

    // §26.I — pin the temporally-sortable id format: `…:node:<5-digit index>:<role 1=start|2=output|3=end>:…`
    // so a same-millisecond tie sorts start < output < end (and node N's end < node N+1's start). A
    // backend refactor that swaps the index/role order or the role digits must fail HERE, not only in the
    // FE projection tests.
    assertTrue(start.id().startsWith("conv-1:node:00000:1:think:"), start.id());
    assertTrue(end.id().startsWith("conv-1:node:00000:3:think:"), end.id());
  }

  @Test
  @DisplayName("node_output -> the node's durable ASSISTANT_MESSAGE; workflow done (nodesExecuted) is skipped (565 §26.I)")
  void nodeOutputAndWorkflowDone() {
    InteractionEvent out =
        mapped(
            "node_output",
            Map.of("nodeId", "draft", "kind", "llm", "index", 1, "output", "a two-sentence brief"));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, out.kind());
    assertEquals("a two-sentence brief", out.content());
    assertEquals("agent", out.originator());
    // §26.I — role 2 (output) sorts between role 1 (start) and role 3 (end) of the same node index.
    assertTrue(out.id().startsWith("conv-1:node:00001:2:draft:"), out.id());

    // A WORKFLOW terminal done carries `nodesExecuted` and must NOT also emit an assistant (the per-node
    // node_outputs are the content) — else the last node renders twice on reload.
    assertTrue(
        AgentInteractionMapper.fromRunEvent(
                rec("done", Map.of("finalResponse", "a two-sentence brief", "nodesExecuted", 2)), CONV)
            .isEmpty(),
        "workflow done is a pure terminal, not an answer bubble");

    // An AGENT done (no nodesExecuted) is still the answer.
    InteractionEvent agentDone = mapped("done", Map.of("finalResponse", "the agent answer"));
    assertEquals(InteractionEventKind.ASSISTANT_MESSAGE, agentDone.kind());
    assertEquals("the agent answer", agentDone.content());
  }

  @Test
  @DisplayName("transient/streaming events are not durable thread content (empty)")
  void transientSkipped() {
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("chunk", Map.of("text", "partial")), CONV).isEmpty());
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("progress", Map.of("phase", "p")), CONV).isEmpty());
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("session_started", Map.of("sessionId", "s")), CONV)
            .isEmpty());
    assertTrue(
        AgentInteractionMapper.fromRunEvent(rec("tool_call_approved", Map.of("callId", "c")), CONV)
            .isEmpty());
  }

  // ==================== Tempdoc 848 §2.4 — the reasoning fold ====================

  private static Map<String, Object> at(String isoTime, String eventType, Map<String, Object> payload) {
    return Map.of("timestamp", isoTime, "eventType", eventType, "payload", payload);
  }

  private static Map<String, Object> reasoningAt(String isoTime, String text) {
    return at(isoTime, "reasoning_chunk", Map.of("text", text));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> blocksOf(InteractionEvent event) {
    Object raw = event.attributes().get("reasoning");
    assertTrue(raw instanceof List<?>, "expected folded reasoning blocks on " + event.kind());
    return (List<Map<String, Object>>) raw;
  }

  @Test
  @DisplayName("848 §2.4: a bare reasoning_chunk is still no thread event of its own (445 chunks, 0 events)")
  void reasoningChunkAloneProjectsNothing() {
    assertTrue(
        AgentInteractionMapper.fromRunEvent(reasoningAt("2026-01-01T00:00:01Z", "hm"), CONV)
            .isEmpty(),
        "reasoning is folded onto a turn, never emitted per chunk");
  }

  @Test
  @DisplayName("848 §2.4: a step boundary CUTS a block; the fold attaches both to the answer turn")
  void reasoningFoldsIntoPerStepBlocks() {
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "first "),
                reasoningAt("2026-01-01T00:00:02Z", "thought"),
                at("2026-01-01T00:00:04Z", "tool_exec_completed", Map.of("callId", "c1", "success", true)),
                reasoningAt("2026-01-01T00:00:06Z", "second thought"),
                at("2026-01-01T00:00:09Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    InteractionEvent answer =
        events.stream()
            .filter(e -> e.kind() == InteractionEventKind.ASSISTANT_MESSAGE)
            .findFirst()
            .orElseThrow();
    List<Map<String, Object>> blocks = blocksOf(answer);
    assertEquals(2, blocks.size(), "the tool step cut the first block from the second");
    assertEquals("first thought", blocks.get(0).get("text"));
    assertEquals("second thought", blocks.get(1).get("text"));
    // §2.1's ONE duration semantic: first reasoning token → first NON-reasoning output that follows.
    // Measured to the tool event (3000ms), NOT to the block's own last chunk (which would say 1000ms,
    // and 0ms for any single-chunk block).
    assertEquals(3000L, ((Number) blocks.get(0).get("durationMs")).longValue());
    assertEquals(3000L, ((Number) blocks.get(1).get("durationMs")).longValue());
    // A-3: the attributes were reconstructed, not mutated — `InteractionEvent` copies them
    // immutably, so a `put` on the delegate's map would have thrown before reaching this line.
    assertEquals("the answer", answer.content());
  }

  @Test
  @DisplayName("848 §2.4 (A-4): text chunks are TRANSPARENT — one LLM step stays one block")
  void textChunksDoNotShatterABlock() {
    // Reasoning and text share one stream, and a think-tag-leaking build reroutes inline <think>
    // markup into the reasoning sink mid-stream. Cutting on bare contiguity would yield a different
    // block count per build family for identical model behaviour.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "part one "),
                at("2026-01-01T00:00:02Z", "chunk", Map.of("text", "VISIBLE ANSWER TEXT")),
                reasoningAt("2026-01-01T00:00:03Z", "part two"),
                at("2026-01-01T00:00:04Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    List<Map<String, Object>> blocks =
        blocksOf(
            events.stream()
                .filter(e -> e.kind() == InteractionEventKind.ASSISTANT_MESSAGE)
                .findFirst()
                .orElseThrow());
    assertEquals(1, blocks.size(), "one step, one block");
    assertEquals("part one part two", blocks.get(0).get("text"), "the visible text is excluded");
  }

  @Test
  @DisplayName("848 §2.4 (D-7): a run that ERRORED keeps the thinking it produced")
  void erroredRunKeepsItsReasoning() {
    // The ask path already records blocks at all four terminals, halt included, because what the
    // model thought before the reader stopped it was really produced. The two planes must agree.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                reasoningAt("2026-01-01T00:00:01Z", "half a thought"),
                at("2026-01-01T00:00:03Z", "error", Map.of("error", "blew up", "errorCode", "X"))),
            CONV);

    InteractionEvent error =
        events.stream()
            .filter(e -> e.kind() == InteractionEventKind.ERROR)
            .findFirst()
            .orElseThrow();
    List<Map<String, Object>> blocks = blocksOf(error);
    assertEquals(1, blocks.size());
    assertEquals("half a thought", blocks.get(0).get("text"));
    assertEquals(2000L, ((Number) blocks.get(0).get("durationMs")).longValue());
  }

  @Test
  @DisplayName("848 F4: an unparseable timestamp cannot produce a 56-year 'Thought for' duration")
  void implausibleDurationIsReportedAsNone() {
    // `parseTs` falls back to Instant.EPOCH, so ONE bad timestamp would otherwise measure the block
    // from 1970. The TEXT is real and stays; the fabricated number does not.
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                Map.of("timestamp", "not-a-timestamp", "eventType", "reasoning_chunk",
                    "payload", Map.of("text", "a real thought")),
                at("2026-01-01T00:00:03Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    List<Map<String, Object>> blocks = blocksOf(events.get(0));
    assertEquals("a real thought", blocks.get(0).get("text"));
    assertEquals(
        0L,
        ((Number) blocks.get(0).get("durationMs")).longValue(),
        "no measurement beats a fabricated one");
  }

  @Test
  @DisplayName("848 §2.4: a run that never reasoned carries no reasoning attribute at all")
  void runWithoutReasoningCarriesNoAttribute() {
    List<InteractionEvent> events =
        AgentInteractionMapper.fromRunEvents(
            List.of(
                at("2026-01-01T00:00:01Z", "chunk", Map.of("text", "streamed")),
                at("2026-01-01T00:00:02Z", "done", Map.of("finalResponse", "the answer"))),
            CONV);

    assertEquals(1, events.size(), "the chunk is still transient");
    assertTrue(
        !events.get(0).attributes().containsKey("reasoning"),
        "no key — absence is the honest reading, not an empty list");
  }

  @Test
  @DisplayName("848 §2.4: the fold preserves every event the per-record projection produced")
  void foldIsAProjectionNotAFilter() {
    List<Map<String, Object>> records =
        List.of(
            at("2026-01-01T00:00:01Z", "tool_call_proposed", Map.of("callId", "c1", "toolName", "grep")),
            reasoningAt("2026-01-01T00:00:02Z", "consider"),
            at("2026-01-01T00:00:03Z", "tool_exec_completed", Map.of("callId", "c1", "success", true)),
            at("2026-01-01T00:00:04Z", "done", Map.of("finalResponse", "the answer")));

    List<InteractionEvent> folded = AgentInteractionMapper.fromRunEvents(records, CONV);
    List<InteractionEvent> perRecord =
        records.stream()
            .map(r -> AgentInteractionMapper.fromRunEvent(r, CONV))
            .filter(java.util.Optional::isPresent)
            .map(java.util.Optional::get)
            .toList();

    assertEquals(
        perRecord.stream().map(InteractionEvent::id).toList(),
        folded.stream().map(InteractionEvent::id).toList(),
        "same events, same order — the fold only decorates the turn with its thinking");
  }
}
