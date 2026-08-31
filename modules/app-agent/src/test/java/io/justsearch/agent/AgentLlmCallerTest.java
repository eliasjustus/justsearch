package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  /**
   * Tempdoc 878 §D.1 — the step ceiling gets the SAME obligation and a DIFFERENT limit sentence.
   *
   * <p>859 D5 recorded what one shared string costs on the FE: a run stopped by the step ceiling
   * with most of its budget unspent told the reader that tokens had stopped it — a specific false
   * statement, made where the reader is deciding what to do next. The same argument applies to the
   * text handed to the MODEL, which is the one that ends up paraphrased in the answer.
   *
   * <p>The obligation half must be shared by CONSTRUCTION, not by two copies that happen to agree
   * today: the four disclosure rules were written for a compact model that declines when it is not
   * given explicit permission to be partial, and an edit that improved them on one terminal only
   * would leave the other with the wording 859 §7 watched fail.
   */
  @Test
  void theStepCeilingInstructionSharesTheObligationAndNamesItsOwnLimit() {
    String budget = AgentLlmCaller.BUDGET_EDGE_FINALIZE_INSTRUCTION;
    String steps = AgentLlmCaller.STEP_CEILING_FINALIZE_INSTRUCTION;

    assertTrue(steps.contains("step limit"), "the step ceiling names the limit that actually fired");
    assertFalse(
        steps.contains("token budget"),
        "and must NOT mention the token budget: a MAX_ITERATIONS run routinely stops with most of"
            + " its budget unspent, so naming it would invite the model to state a false cause");
    assertTrue(
        budget.contains("token budget") && !budget.contains("step limit"),
        "and the budget wall keeps naming its own, unchanged");

    int budgetSplit = budget.indexOf(" Write your answer");
    int stepsSplit = steps.indexOf(" Write your answer");
    assertTrue(budgetSplit > 0 && stepsSplit > 0, "both instructions carry the obligation block");
    assertEquals(
        budget.substring(budgetSplit),
        steps.substring(stepsSplit),
        "everything after the limit sentence is IDENTICAL — the obligation is the same, only the"
            + " reason the run stopped differs, and a future edit to the four rules must not be able"
            + " to land on one terminal and miss the other");
  }

  /**
   * Tempdoc 865 §7.5 (review F2) — the budget-edge finalize's compression pass must RECORD its
   * receipt, like every other compression site.
   *
   * <p>This one matters most and was the one that did not. It builds the exact prompt {@code
   * groundedDone(BUDGET_EDGE_FINALIZE)}'s answer is written from, under the run's maximal
   * compression pressure — so compressing bare here left the terminal resolving inclusion against a
   * picture taken one pass earlier, silently withholding the badge precisely where the most text had
   * been dropped. The seam is invisible to every other test: the mint works, the join works, and the
   * only symptom is a run that says nothing when it had the most to say.
   *
   * <p>The LLM call itself is irrelevant here and is left to fail (a null service) — {@code
   * attemptBudgetEdgeFinalize} catches it, and the compression happens before it either way.
   */
  @Test
  void budgetEdgeFinalizeRecordsItsCompressionReceipt() {
    var compressor = new AgentContextCompressor(true, 200, 1);
    var session =
        new AgentSession(
            new java.util.ArrayList<>(List.of(java.util.Map.<String, Object>of("role", "user", "content", "q"))),
            8000);

    // Two searches, their tool messages appended — and NO compression pass recorded yet, which is
    // the state the budget-edge path finds the session in when the budget trips mid-iteration.
    for (String callId : List.of("call-1", "call-2")) {
      var result =
          io.justsearch.agent.api.registry.OperationResult.success(
              "Found 1 result(s)\n  /docs/"
                  + callId
                  + ".md\n"
                  + ToolResultCarrier.excerptLine(
                      "the passage text for "
                          + callId
                          + ", padded so this message clears the compressor's minimum-length floor"
                          + " and is therefore one that compression will actually rewrite"),
              java.util.Map.of(
                  "searchResults",
                  List.of(
                      java.util.Map.<String, Object>of(
                          "parentDocId", callId,
                          "chunkIndex", 0,
                          "path", "/docs/" + callId + ".md",
                          "title", "Doc",
                          "excerpt", "an excerpt",
                          "startLine", 1,
                          "endLine", 5,
                          "headingText", ""))));
      session.recordExecution(new ToolCallRequest(callId, "core_search_index", "{}"), result);
      var message = new java.util.LinkedHashMap<String, Object>();
      message.put("role", "tool");
      message.put("tool_call_id", callId);
      message.put("content", result.message());
      session.appendMessage(message);
    }

    assertTrue(
        session.collectGroundingSources().stream()
            .allMatch(s -> s.contextInclusion().isEmpty()),
        "precondition: no pass has reported, so the run claims nothing about any source");

    new AgentLlmCaller(null, AgentTelemetry.noop(), compressor)
        .attemptBudgetEdgeFinalize(session, event -> {});

    assertEquals(
        "dropped",
        session.collectGroundingSources().stream()
            .filter(s -> s.parentDocId().equals("call-1"))
            .findFirst()
            .orElseThrow()
            .contextInclusion(),
        "the finalize pass compressed the older result out of the prompt, and the terminal that"
            + " reads this session must be told — this is the prompt its answer is written from");
  }
}
