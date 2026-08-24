package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.ToolCallRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentLlmCaller#recoverInlineToolCalls} — the agent-loop defence against a model emitting its
 * tool call as TEXT content instead of via the structured {@code tool_calls} channel (tempdoc 565
 * follow-up). A local model leaked {@code ; {"type":"function","name":"core_search_index",…}} into an
 * answer bubble; the leak carried a search the structured channel never ran. The recovery must extract
 * such spans (both grammars, any delimiter), execute genuinely-intended calls, dedup echoes, and clean
 * the text — so neither an action nor the answer is corrupted.
 */
class AgentLlmCallerTest {

  private static final Set<String> SEARCH = Set.of("core_search_index");

  /** The EXACT observed leak: two `;`-separated OpenAI-style spans, no structured calls in this turn. */
  @Test
  void recoversBothInlineCallsFromTheObservedLeak() {
    String leak =
        "; {\"type\": \"function\", \"name\": \"core_search_index\", \"parameters\": "
            + "{\"query\": \"embedding step\", \"limit\": \"10\"}}; {\"type\": \"function\", "
            + "\"name\": \"core_search_index\", \"parameters\": {\"query\": \"search ranking\", "
            + "\"limit\": \"10\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(leak, List.of(), SEARCH);

    assertEquals(2, rt.recovered().size(), "both leaked searches recovered");
    assertEquals("core_search_index", rt.recovered().get(0).toolName());
    assertTrue(rt.recovered().get(0).arguments().contains("embedding step"));
    assertTrue(rt.recovered().get(1).arguments().contains("search ranking"));
    assertTrue(rt.text().isEmpty(), "pure tool-call text is consumed → no JSON survives as the answer");
  }

  /** An echo of a structured call is stripped but NOT re-executed; a genuinely-new span is recovered. */
  @Test
  void dedupesEchoOfAStructuredCallButRecoversTheNewOne() {
    var structured =
        List.of(
            new ToolCallRequest(
                "c1", "core_search_index", "{\"query\":\"embedding step\",\"limit\":\"10\"}"));
    String leak =
        "{\"type\":\"function\",\"name\":\"core_search_index\",\"parameters\":"
            + "{\"query\":\"embedding step\",\"limit\":\"10\"}}; "
            + "{\"type\":\"function\",\"name\":\"core_search_index\",\"parameters\":"
            + "{\"query\":\"search ranking\",\"limit\":\"10\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(leak, structured, SEARCH);

    assertEquals(1, rt.recovered().size(), "only the non-echo span is recovered");
    assertTrue(rt.recovered().get(0).arguments().contains("search ranking"));
    assertTrue(rt.text().isEmpty());
  }

  /** Inline JSON mixed with prose: strip only the span, keep the surrounding answer text. */
  @Test
  void preservesProseAroundAStrippedSpan() {
    String mixed =
        "Here are the results. {\"type\":\"function\",\"name\":\"core_search_index\","
            + "\"parameters\":{\"query\":\"x\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(mixed, List.of(), SEARCH);

    assertEquals(1, rt.recovered().size());
    assertEquals("Here are the results.", rt.text());
  }

  /** The legacy Hermes grammar ({"name","arguments"}) is still recovered. */
  @Test
  void stillRecoversHermesNameArgumentsGrammar() {
    String hermes = "{\"name\": \"core_search_index\", \"arguments\": {\"query\": \"x\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(hermes, List.of(), SEARCH);

    assertEquals(1, rt.recovered().size());
    assertEquals("core_search_index", rt.recovered().get(0).toolName());
    assertTrue(rt.text().isEmpty());
  }

  /** A span naming a NON-available tool is left in the text (it may be legitimate content). */
  @Test
  void leavesUnknownToolJsonInTextAsPotentialContent() {
    String content =
        "{\"type\":\"function\",\"name\":\"made_up_tool\",\"parameters\":{}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(content, List.of(), SEARCH);

    assertTrue(rt.recovered().isEmpty(), "unknown tool is not executed");
    assertEquals(content, rt.text(), "unknown-tool JSON is left untouched (could be real content)");
  }

  /** Ordinary JSON that is not tool-call-shaped ({"name":"John","age":30}) is never touched. */
  @Test
  void ignoresNonToolJsonObjects() {
    String prose = "The record is {\"name\":\"John\",\"age\":30} — note it.";

    AgentLlmCaller.RecoveredText rt =
        AgentLlmCaller.recoverInlineToolCalls(prose, List.of(), Set.of("John", "core_search_index"));

    assertTrue(rt.recovered().isEmpty());
    assertEquals(prose, rt.text());
  }

  /** Braces inside string VALUES must not confuse the balanced-brace scan. */
  @Test
  void handlesBracesInsideStringValues() {
    String leak =
        "{\"type\":\"function\",\"name\":\"core_search_index\",\"parameters\":"
            + "{\"query\":\"a { b } c\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(leak, List.of(), SEARCH);

    assertEquals(1, rt.recovered().size());
    assertTrue(rt.recovered().get(0).arguments().contains("a { b } c"));
    assertTrue(rt.text().isEmpty());
  }

  /** No tool-call JSON at all → text returned unchanged, nothing recovered. */
  @Test
  void plainAnswerIsUntouched() {
    String answer = "The indexing pipeline chunks documents, then embeds them, then ranks results.";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(answer, List.of(), SEARCH);

    assertTrue(rt.recovered().isEmpty());
    assertEquals(answer, rt.text());
  }

  /**
   * Tempdoc 859 §D §2.6 layer 1 / §3.3 T5 — the budget-edge finalize instruction.
   *
   * <p><b>What this pins, and what it deliberately does NOT.</b> It pins the SEAM: that the message
   * the loop appends before the finalize call actually carries the cut-short obligation, so a future
   * edit cannot quietly revert to "provide your best answer" — the wording that produced 859 §7's
   * confident, content-free non-answer.
   *
   * <p>It does <b>not</b> pin that the ANSWER is honest. No prompt-level assertion can: the model is
   * free to ignore every line of this. Nobody should read a green here as evidence the decline arm
   * is fixed. The fail-closed guarantee is the disposition on the wire ({@code AgentDone.disposition}),
   * asserted by {@code AgentLoopServiceTest#budgetEdgeFinalizeDeclaresItsDisposition} — which passes
   * even when the model's text says nothing at all.
   */
  @Test
  void budgetEdgeFinalizeInstructionCarriesTheCutShortObligation() {
    String instruction = AgentLlmCaller.BUDGET_EDGE_FINALIZE_INSTRUCTION;

    assertTrue(
        instruction.contains("cut short"),
        "the model must be told, in words, that the run stopped before it finished");
    assertTrue(
        instruction.contains("partial"),
        "and given explicit PERMISSION to be partial — the absence of which is what a compact model"
            + " resolves by declining");
    assertTrue(
        instruction.contains("do not decline"),
        "stated as a prohibition too, because permission alone was what the old wording implied");
    assertTrue(
        instruction.contains("what you had gathered") && instruction.contains("had not"),
        "and required to partition gathered from not-gathered rather than blurring the two");
    assertTrue(
        instruction.contains("lack access"),
        "the specific observed falsehood — claiming no access to files already in the transcript —"
            + " is forbidden by name");
    assertTrue(
        instruction.contains("Do not call any more tools."),
        "the original no-tools constraint survives the rewrite (the finalize call passes no tools)");
  }
}
