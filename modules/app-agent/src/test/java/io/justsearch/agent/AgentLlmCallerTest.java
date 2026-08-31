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

  private static final AgentLlmCaller.ToolSchemas SEARCH_SCHEMAS =
      AgentLlmCaller.ToolSchemas.ofNames(Set.of("core_search_index"));

  /** The EXACT observed leak: two `;`-separated OpenAI-style spans, no structured calls in this turn. */
  @Test
  void recoversBothInlineCallsFromTheObservedLeak() {
    String leak =
        "; {\"type\": \"function\", \"name\": \"core_search_index\", \"parameters\": "
            + "{\"query\": \"embedding step\", \"limit\": \"10\"}}; {\"type\": \"function\", "
            + "\"name\": \"core_search_index\", \"parameters\": {\"query\": \"search ranking\", "
            + "\"limit\": \"10\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(leak, List.of(), SEARCH_SCHEMAS);

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

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(mixed, List.of(), SEARCH_SCHEMAS);

    assertEquals(1, rt.recovered().size());
    assertEquals("Here are the results.", rt.text());
  }

  /** The legacy Hermes grammar ({"name","arguments"}) is still recovered. */
  @Test
  void stillRecoversHermesNameArgumentsGrammar() {
    String hermes = "{\"name\": \"core_search_index\", \"arguments\": {\"query\": \"x\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(hermes, List.of(), SEARCH_SCHEMAS);

    assertEquals(1, rt.recovered().size());
    assertEquals("core_search_index", rt.recovered().get(0).toolName());
    assertTrue(rt.text().isEmpty());
  }

  /** A span naming a NON-available tool is left in the text (it may be legitimate content). */
  @Test
  void leavesUnknownToolJsonInTextAsPotentialContent() {
    String content =
        "{\"type\":\"function\",\"name\":\"made_up_tool\",\"parameters\":{}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(content, List.of(), SEARCH_SCHEMAS);

    assertTrue(rt.recovered().isEmpty(), "unknown tool is not executed");
    assertEquals(content, rt.text(), "unknown-tool JSON is left untouched (could be real content)");
  }

  /** Ordinary JSON that is not tool-call-shaped ({"name":"John","age":30}) is never touched. */
  @Test
  void ignoresNonToolJsonObjects() {
    String prose = "The record is {\"name\":\"John\",\"age\":30} — note it.";

    AgentLlmCaller.RecoveredText rt =
        AgentLlmCaller.recoverInlineToolCalls(
            prose, List.of(), AgentLlmCaller.ToolSchemas.ofNames(Set.of("John", "core_search_index")));

    assertTrue(rt.recovered().isEmpty());
    assertEquals(prose, rt.text());
  }

  /** Braces inside string VALUES must not confuse the balanced-brace scan. */
  @Test
  void handlesBracesInsideStringValues() {
    String leak =
        "{\"type\":\"function\",\"name\":\"core_search_index\",\"parameters\":"
            + "{\"query\":\"a { b } c\"}}";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(leak, List.of(), SEARCH_SCHEMAS);

    assertEquals(1, rt.recovered().size());
    assertTrue(rt.recovered().get(0).arguments().contains("a { b } c"));
    assertTrue(rt.text().isEmpty());
  }

  /** No tool-call JSON at all → text returned unchanged, nothing recovered. */
  @Test
  void plainAnswerIsUntouched() {
    String answer = "The indexing pipeline chunks documents, then embeds them, then ranks results.";

    AgentLlmCaller.RecoveredText rt = AgentLlmCaller.recoverInlineToolCalls(answer, List.of(), SEARCH_SCHEMAS);

    assertTrue(rt.recovered().isEmpty());
    assertEquals(answer, rt.text());
  }

  // ---------------------------------------------------------------------------------------------
  // Tempdoc 881 — the XML grammar and the reasoning channel.
  //
  // Every leaked string below is VERBATIM from a live capture against
  // `models/Qwen_Qwen3.5-9B-Q4_K_M.gguf` / `models/compact/Qwen3.5-4B-Q4_K_M.gguf` at n_ctx 4096
  // (881 §A.2/§A.3, samples in the tempdoc). They are the actual bytes the loop discarded, not a
  // reconstruction of them — a hand-written approximation of a leak is a test of the author's
  // memory, and the point of this layer is that the real grammar was not the one anybody expected.
  // ---------------------------------------------------------------------------------------------

  /** The two tools the 881 captures name, with the parameter types their real schemas declare. */
  private static final AgentLlmCaller.ToolSchemas READ_AND_BROWSE =
      AgentLlmCaller.ToolSchemas.of(
          List.of(
              java.util.Map.of(
                  "type", "function",
                  "function",
                      java.util.Map.of(
                          "name", "core_read_document",
                          "parameters",
                              java.util.Map.of(
                                  "type", "object",
                                  "properties",
                                      java.util.Map.of(
                                          "path", java.util.Map.of("type", "string"),
                                          "offset_chars", java.util.Map.of("type", "integer"))))),
              java.util.Map.of(
                  "type", "function",
                  "function",
                      java.util.Map.of(
                          "name", "core_browse_folders",
                          "parameters",
                              java.util.Map.of(
                                  "type", "object",
                                  "properties",
                                      java.util.Map.of(
                                          "parent_path", java.util.Map.of("type", "string"),
                                          "list_files", java.util.Map.of("type", "boolean")))))));

  /**
   * The exact failing turn of 868 §D.3: the model's whole output went to the reasoning channel, with
   * a no-argument call inside its own {@code <tool_call>} wrapper, and {@code finish_reason=stop}.
   */
  @Test
  void recoversTheArgumentlessCallLeakedIntoTheReasoningChannel() {
    String reasoning =
        "I need to first see what's at the top level of the indexed folders before I can list"
            + " files. Let me call core_browse_folders without the list_files parameter.\n\n"
            + "<tool_call>\n<function=core_browse_folders>\n</function>\n</tool_call>";

    List<ToolCallRequest> recovered =
        AgentLlmCaller.recoverCommittedToolCalls(reasoning, READ_AND_BROWSE);

    assertEquals(1, recovered.size(), "the model committed to one call; the loop used to discard it");
    assertEquals("core_browse_folders", recovered.get(0).toolName());
    assertEquals("{}", recovered.get(0).arguments(), "no <parameter=> elements → empty arguments");
  }

  /**
   * Arguments arrive UNTYPED in this grammar, so the conversion has to consult the tool's declared
   * schema: {@code 3118} must reach the tool as a number and {@code True} as a boolean, or the call
   * is recovered into a differently-wrong one.
   */
  @Test
  void typesXmlArgumentsAgainstTheToolsDeclaredSchema() {
    String reasoning =
        "Let me read the first page.\n<tool_call>\n<function=core_read_document>\n"
            + "<parameter=path>\nf:\\justsearch-public\\docs\\tempdocs\\611-chat-composer.md\n</parameter>\n"
            + "<parameter=offset_chars>\n3118\n</parameter>\n</function>\n</tool_call>";

    List<ToolCallRequest> recovered =
        AgentLlmCaller.recoverCommittedToolCalls(reasoning, READ_AND_BROWSE);

    assertEquals(1, recovered.size());
    assertEquals(
        "{\"path\":\"f:\\\\justsearch-public\\\\docs\\\\tempdocs\\\\611-chat-composer.md\","
            + "\"offset_chars\":3118}",
        recovered.get(0).arguments(),
        "path stays a string (backslashes escaped by the serializer), offset_chars becomes a NUMBER"
            + " because the schema declares it integer — not the raw \"3118\" text");
  }

  /** {@code True} is Python's spelling, not JSON's; the boolean schema is what resolves it. */
  @Test
  void coercesPythonSpelledBooleanUsingTheDeclaredBooleanType() {
    String reasoning =
        "<tool_call>\n<function=core_browse_folders>\n"
            + "<parameter=parent_path>\nf:\\justsearch-public\\docs\n</parameter>\n"
            + "<parameter=list_files>\nTrue\n</parameter>\n</function>\n</tool_call>";

    List<ToolCallRequest> recovered =
        AgentLlmCaller.recoverCommittedToolCalls(reasoning, READ_AND_BROWSE);

    assertEquals(1, recovered.size());
    assertTrue(
        recovered.get(0).arguments().contains("\"list_files\":true"),
        "the boolean is a JSON boolean, not the string \"True\": " + recovered.get(0).arguments());
  }

  /**
   * The reasoning channel WEIGHS calls it then decides against. Recovery keys on the model's own
   * {@code <tool_call>} commit wrapper for exactly that reason — a call merely described in prose,
   * or a bare JSON object the permissive text rule would have taken, must not be executed.
   */
  @Test
  void doesNotRecoverACallTheReasoningOnlyCONSIDERED() {
    String deliberation =
        "I could call core_read_document with {\"name\":\"core_read_document\",\"arguments\":"
            + "{\"path\":\"a.md\"}} but I do not know the path yet, so I will browse instead.";

    assertTrue(
        AgentLlmCaller.recoverCommittedToolCalls(deliberation, READ_AND_BROWSE).isEmpty(),
        "an unwrapped JSON blob inside thinking is deliberation, not a commitment — acting on it"
            + " would be worse than the bug this recovery exists to fix");
  }

  /** A wrapped call naming a tool that was not offered stays unrecovered. */
  @Test
  void doesNotRecoverAWrappedCallNamingAnUnofferedTool() {
    String reasoning = "<tool_call>\n<function=core_delete_everything>\n</function>\n</tool_call>";

    assertTrue(
        AgentLlmCaller.recoverCommittedToolCalls(reasoning, READ_AND_BROWSE).isEmpty(),
        "availability is still the gate — recovery cannot widen the tool surface");
  }

  /** Two committed calls in one thinking block come back in order; an exact repeat is deduped. */
  @Test
  void recoversEveryCommittedCallOnceInOrder() {
    String reasoning =
        "<tool_call>\n<function=core_browse_folders>\n</function>\n</tool_call>\n"
            + "then\n<tool_call>\n<function=core_read_document>\n<parameter=path>\na.md\n</parameter>\n"
            + "</function>\n</tool_call>\n"
            + "<tool_call>\n<function=core_browse_folders>\n</function>\n</tool_call>";

    List<ToolCallRequest> recovered =
        AgentLlmCaller.recoverCommittedToolCalls(reasoning, READ_AND_BROWSE);

    assertEquals(2, recovered.size(), "the repeated browse is not executed twice");
    assertEquals("core_browse_folders", recovered.get(0).toolName());
    assertEquals("core_read_document", recovered.get(1).toolName());
  }

  /**
   * The same XML grammar leaking into the TEXT channel is stripped as well as recovered — an
   * unrecovered leak there renders as the answer, which is the defect the JSON rule already exists
   * for. The wrapper span is removed whole, not just the {@code <function>} it encloses.
   */
  @Test
  void stripsAndRecoversTheXmlGrammarFromTheTextChannel() {
    String leaked =
        "Let me look.\n<tool_call>\n<function=core_browse_folders>\n"
            + "<parameter=list_files>\nfalse\n</parameter>\n</function>\n</tool_call>";

    AgentLlmCaller.RecoveredText rt =
        AgentLlmCaller.recoverInlineToolCalls(leaked, List.of(), READ_AND_BROWSE);

    assertEquals(1, rt.recovered().size());
    assertEquals("core_browse_folders", rt.recovered().get(0).toolName());
    assertTrue(
        rt.recovered().get(0).arguments().contains("\"list_files\":false"),
        "declared-boolean coercion applies in the text channel too: " + rt.recovered().get(0).arguments());
    assertEquals("Let me look.", rt.text(), "no <tool_call> husk survives into the answer");
  }

  /**
   * A JSON tool call inside a {@code <tool_call>} wrapper is claimed by the WRAPPER span, not by the
   * balanced-brace scan. Both scans see it; if the inner object won, deleting it would leave a bare
   * {@code <tool_call></tool_call>} in the answer text.
   */
  @Test
  void wrapperSpanWinsOverTheInnerJsonSoNoHuskSurvives() {
    String leaked =
        "Here.<tool_call>{\"name\":\"core_browse_folders\",\"arguments\":{}}</tool_call>";

    AgentLlmCaller.RecoveredText rt =
        AgentLlmCaller.recoverInlineToolCalls(leaked, List.of(), READ_AND_BROWSE);

    assertEquals(1, rt.recovered().size(), "recovered once, not twice");
    assertEquals("core_browse_folders", rt.recovered().get(0).toolName());
    assertEquals("Here.", rt.text());
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
