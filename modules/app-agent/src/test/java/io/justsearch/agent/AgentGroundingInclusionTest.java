package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 865 §7.5 — the retrieved-vs-received JOIN on the delegate plane: source → the tool call
 * that carried it → whether that call's message still holds its excerpts in the prompt.
 *
 * <p>These drive the REAL {@link AgentContextCompressor} over a real message list, in the same order
 * {@code AgentStepRunner} does it (record the execution, append the tool message, compress, record
 * the receipt). A mocked compressor would test the join against a fixture of the very behaviour the
 * join is about — the receipt's whole claim is that the compressor's own output is the fact source.
 */
final class AgentGroundingInclusionTest {

  /** The real defaults (`ResolvedConfigBuilder`: enabled, minChars 200, keepLastResults 1). */
  private static AgentContextCompressor compressor() {
    return new AgentContextCompressor(true, 200, 1);
  }

  private static AgentSession session() {
    return new AgentSession(new ArrayList<>(List.of(Map.of("role", "user", "content", "q"))), 8000);
  }

  private static ToolCallRequest searchCall(String id) {
    return new ToolCallRequest(id, "core_search_index", "{\"query\":\"x\"}");
  }

  private static Map<String, Object> chunkHit(String parentDocId) {
    return Map.of(
        "parentDocId", parentDocId,
        "chunkIndex", 0,
        "path", "/docs/" + parentDocId + ".md",
        "title", "Doc " + parentDocId,
        "excerpt", "an excerpt",
        "startLine", 1,
        "endLine", 5,
        "headingText", "");
  }

  /**
   * A search tool message shaped like {@code SearchTool.formatResults}' output: long enough to clear
   * the compressor's {@code minChars} floor, and carrying the indented {@code Excerpt:} lines that
   * are the thing Layer 3 removes.
   */
  private static String searchMessage(String... docIds) {
    var sb = new StringBuilder("Found ").append(docIds.length).append(" result(s)\n");
    for (String id : docIds) {
      sb.append("  /docs/").append(id).append(".md\n");
      sb.append("    Lines 1-5\n");
      sb.append("    Excerpt: \"")
          .append("the passage text for ")
          .append(id)
          .append(", padded so this message clears the compressor's minimum-length floor and is")
          .append(" therefore a message compression will actually rewrite rather than skip")
          .append("\"\n");
    }
    return sb.toString();
  }

  /** One dispatch, exactly as {@code AgentStepRunner.executeIteration} sequences it. */
  private static List<AgentEvent.AgentSource> dispatch(
      AgentSession session, AgentContextCompressor compressor, String callId, String... docIds) {
    var hits = new ArrayList<Map<String, Object>>();
    for (String id : docIds) {
      hits.add(chunkHit(id));
    }
    var result =
        OperationResult.success(
            searchMessage(docIds), Map.of("searchResults", List.copyOf(hits)));
    List<AgentEvent.AgentSource> delta = session.recordExecution(searchCall(callId), result);
    var message = new LinkedHashMap<String, Object>();
    message.put("role", "tool");
    message.put("tool_call_id", callId);
    message.put("content", AgentContextCompressor.truncate(result.message()));
    session.appendMessage(message);
    session.recordCompression(compressor.compressToolMessages(session.messages()));
    return delta;
  }

  private static AgentEvent.AgentSource sourceOf(
      List<AgentEvent.AgentSource> sources, String parentDocId) {
    return sources.stream()
        .filter(s -> s.parentDocId().equals(parentDocId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no source for " + parentDocId + " in " + sources));
  }

  @Test
  @DisplayName("865 §7.5 THE JOIN: a source whose carrier message lost its excerpts is dropped; the current call's is not")
  void strippedCarrier_yieldsDroppedSource_intactCarrierDoesNot() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");
    dispatch(session, compressor, "call-3", "d3");

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(3, sources.size(), "all three searches established a source");

    // The compressor keeps the last result and rewrites the older ones, so the run's own message
    // list is the evidence: d1's and d2's carriers no longer hold an `Excerpt:` line, d3's does.
    assertEquals(
        "dropped",
        sourceOf(sources, "d1").contextInclusion(),
        "d1's carrier was compressed away, so its passage is not in the prompt the answer is"
            + " written from — the state 849's `suppressGroundingFor` acts on");
    assertEquals("dropped", sourceOf(sources, "d2").contextInclusion());
    assertEquals(0, sourceOf(sources, "d1").contextIncludedChars(), "dropped means zero chars");
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        sourceOf(sources, "d3").contextInclusion(),
        "d3's carrier is intact, and the producer then says NOTHING rather than `included`:"
            + " Layers 1-2 (SearchTool's per-result budget, truncate's hard cut) are upstream cuts"
            + " this producer cannot witness, so `included` would be a claim it has no standing to"
            + " make. Only `dropped` is monotone across the three layers.");
    assertEquals(
        AgentEvent.AgentSource.INCLUDED_CHARS_UNKNOWN,
        sourceOf(sources, "d3").contextIncludedChars());
  }

  /**
   * THE RED-BEFORE GUARD. Removing the receipt — the one line {@code AgentStepRunner} adds at each
   * compression site — is the way this slice most plausibly regresses: the join keeps compiling, the
   * mint keeps working, and every source silently returns to absent. Without this test the
   * regression is invisible, because "no badge" is also what the plane did before 865.
   */
  @Test
  @DisplayName("865 §7.5: with no receipt recorded, every source stays ABSENT — the producer is the receipt, not the compression")
  void withoutTheReceipt_nothingIsClaimed() {
    var session = session();
    var compressor = compressor();

    // Same three dispatches, same real compression — but the receipt is never handed to the session.
    for (String callId : List.of("call-1", "call-2", "call-3")) {
      var result =
          OperationResult.success(
              searchMessage(callId), Map.of("searchResults", List.of(chunkHit(callId))));
      session.recordExecution(searchCall(callId), result);
      var message = new LinkedHashMap<String, Object>();
      message.put("role", "tool");
      message.put("tool_call_id", callId);
      message.put("content", result.message());
      session.appendMessage(message);
      compressor.compressToolMessages(session.messages());
    }

    for (AgentEvent.AgentSource s : session.collectGroundingSources()) {
      assertEquals(
          AgentEvent.AgentSource.INCLUSION_ABSENT,
          s.contextInclusion(),
          "a producer that was told nothing says nothing — never `dropped` inferred from the"
              + " compressor's behaviour without the compressor's own report");
    }
  }

  /**
   * PER-FINAL-PROMPT, NOT CUMULATIVE. The plane degrades continuously, so "was stripped once" is not
   * the question — "is it in the prompt the answer was written from" is. A source re-delivered by a
   * later search has a live carrier again, and the receipt is a picture of ONE prompt, so it must
   * stop being described as dropped.
   */
  @Test
  @DisplayName("865 §7.5: a source stripped at an early iteration but re-returned by a later search is no longer dropped")
  void reEstablishedSource_losesTheDroppedState() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");
    assertEquals(
        "dropped",
        sourceOf(session.collectGroundingSources(), "d1").contextInclusion(),
        "precondition: d1's only carrier has been compressed");

    // The run-wide dedup means this call establishes NOTHING new — and that is exactly the case a
    // first-carrier-only model gets wrong: no source is minted, but d1's text is in the prompt again.
    List<AgentEvent.AgentSource> delta = dispatch(session, compressor, "call-3", "d1");
    assertTrue(delta.isEmpty(), "the dedup is unchanged: a repeat hit establishes no new source");

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(2, sources.size(), "and adds no source to the terminal list either");
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        sourceOf(sources, "d1").contextInclusion(),
        "d1 has an intact carrier again, so the run may no longer say its passage was never sent");
    assertEquals(
        "dropped",
        sourceOf(sources, "d2").contextInclusion(),
        "and d2, whose only carrier is still compressed, is unaffected — the state is per source,"
            + " not per run");
  }

  @Test
  @DisplayName("865 §7.5: the per-call delta carries NO inclusion — a tool call has no final prompt to be a fact about")
  void theDeltaIsMintedAbsent() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    List<AgentEvent.AgentSource> delta = dispatch(session, compressor, "call-2", "d2");

    assertEquals(1, delta.size());
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        delta.get(0).contextInclusion(),
        "the delta is the identity a call established; inclusion is resolved only where the prompt"
            + " exists (`collectGroundingSources`, at the two grounded terminals)");

    OperationResult stamped = OperationResult.success("r").withGrounding(delta);
    Map<?, ?> wire = (Map<?, ?>) ((List<?>) stamped.structuredData().get("grounding")).get(0);
    assertFalse(
        wire.containsKey("contextInclusion"),
        "and the stamped wire shape stays the eight identity fields — PR-1's delta contract is"
            + " untouched by this slice");
  }

  @Test
  @DisplayName("865 §7.5: the compressor's receipt reports EVERY tool message, kept and compressed alike")
  void theReceiptIsACompletePictureOfOnePrompt() {
    var session = session();
    var compressor = compressor();
    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");

    AgentContextCompressor.CompressionReceipt receipt =
        compressor.compressToolMessages(session.messages());

    assertTrue(receipt.observed(), "two tool messages were seen");
    assertTrue(receipt.intact("call-2"), "the kept result still carries its excerpts");
    assertFalse(receipt.intact("call-1"), "the compressed one does not");
    assertTrue(
        receipt.excerptsStripped().contains("call-1"),
        "and it is reported as stripped rather than merely missing — the partition is complete, so"
            + " a consumer can tell 'this prompt has no such call' from 'this call lost its text'");
  }

  @Test
  @DisplayName("865 §7.5: an already-compressed message stays reported as stripped on later passes")
  void anAlreadyCompressedMessageDoesNotReadAsIntact() {
    var session = session();
    var compressor = compressor();
    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");

    // Two further passes. `compressToolOutput` refuses to re-compress its own output, so a receipt
    // built by DIFFING a pass would report call-1 as untouched — and therefore intact, the exact
    // inversion of the truth. Reading the artifact cannot make that mistake.
    session.recordCompression(compressor.compressToolMessages(session.messages()));
    session.recordCompression(compressor.compressToolMessages(session.messages()));

    assertEquals(
        "dropped",
        sourceOf(session.collectGroundingSources(), "d1").contextInclusion(),
        "still dropped after passes that changed nothing");
  }
}
