/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM-call cluster for the agent loop (tempdoc 240 W4 — extracted from
 * {@code AgentLoopService}). Owns the round-trip to {@link OnlineAiService}:
 * streaming a chat-with-tools request, accumulating text/reasoning/tool-call
 * deltas, applying the empty-response and transient-failure retry policy,
 * recovering Hermes text-format tool calls, stripping leaked {@code <think>}
 * tags, and building the assistant tool-call message. Coupled only to
 * {@code onlineAiService}, {@code agentTelemetry}, and {@code compressor}; the
 * shared {@code TRACER_SCOPE} / {@code TOOL_CALL_GRAMMAR} constants stay on
 * {@link AgentLoopService}.
 */
final class AgentLlmCaller {

  private static final Logger LOG = LoggerFactory.getLogger(AgentLlmCaller.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Per-call completion-token cap. */
  static final int DEFAULT_MAX_TOKENS =
      Math.max(256, resolveInt(rc -> rc.agent().maxCompletionTokens(), 1024));

  private final OnlineAiService onlineAiService;
  private final AgentTelemetry agentTelemetry;
  private final AgentContextCompressor compressor;

  AgentLlmCaller(
      OnlineAiService onlineAiService,
      AgentTelemetry agentTelemetry,
      AgentContextCompressor compressor) {
    this.onlineAiService = onlineAiService;
    this.agentTelemetry = agentTelemetry;
    this.compressor = compressor;
  }

  /** Names the limit that fired, for the budget wall. */
  private static final String BUDGET_LIMIT_SENTENCE =
      "This run has reached its token budget and is stopping now, before you finished working.";

  /** Names the limit that fired, for the iteration ceiling (tempdoc 878 §D.1). */
  private static final String STEP_LIMIT_SENTENCE =
      "This run has reached its step limit and is stopping now, before you finished working.";

  /**
   * Everything after the limit sentence — identical for both terminals, because the OBLIGATION is
   * identical: only the reason the run stopped differs. Shared by construction so a future edit to
   * the disclosure rules cannot land on one terminal and miss the other.
   */
  private static final String FINALIZE_OBLIGATION =
      " Write your answer from what you already gathered, and follow all four rules:\n"
          + "1. Say plainly, in your first sentence, that the run was cut short before it finished"
          + " and that this answer is partial.\n"
          + "2. Give whatever partial findings you do have. A partial answer is the goal here — do"
          + " not decline, and do not withhold what you found because it is incomplete.\n"
          + "3. Name what you had gathered and what you had not gotten to yet.\n"
          + "4. Do not say you lack access to anything already present in this conversation. Tool"
          + " results above are yours to use.\n"
          + "Do not call any more tools.";

  /**
   * Tempdoc 859 §D §2.6 layer 1 — the budget-edge finalize instruction.
   *
   * <p>The instruction this replaced asked for "your best answer based on the information gathered
   * so far" and never gave the model permission — let alone an obligation — to be PARTIAL. A compact
   * model resolves that by declining: 859 §7 observed a confidently formatted, content-free
   * non-answer that also claimed no access to files already sitting in its own transcript.
   *
   * <p>So this names the situation, requires the disclosure, requires the partition into gathered vs
   * not-gathered, and forbids the specific false claim that was observed. It is BEST-EFFORT by
   * construction — a model can ignore any of it. The fail-closed guarantee is the disposition on the
   * wire ({@code AgentEvent.AgentDone#disposition}), which is written independently of this text.
   */
  static final String BUDGET_EDGE_FINALIZE_INSTRUCTION =
      BUDGET_LIMIT_SENTENCE + FINALIZE_OBLIGATION;

  /**
   * Tempdoc 878 §D.1 — the same obligation, for the OTHER involuntary terminal: the run ran out of
   * STEPS, not tokens.
   *
   * <p>The limit sentence is the only difference, and it is not cosmetic. 859 D5 recorded the cost
   * of one shared string across these two terminals on the FE: a run stopped by the step ceiling
   * with most of its budget unspent told the reader that TOKENS had stopped it, which is a specific
   * false statement made where the reader is deciding what to do next. The same argument applies to
   * the text handed to the MODEL — an instruction that opens "you have reached your token budget"
   * during a step-ceiling finalize invites the model to repeat that falsehood in its answer.
   */
  static final String STEP_CEILING_FINALIZE_INSTRUCTION =
      STEP_LIMIT_SENTENCE + FINALIZE_OBLIGATION;

  /**
   * Budget-edge finalize: the graceful terminal at the TOKEN wall. Delegates to {@link
   * #attemptFinalize} and owns the {@code agent.budget_edge_finalize.total} counter, which is named
   * for this terminal and therefore counts only this one.
   */
  String attemptBudgetEdgeFinalize(AgentSession session, Consumer<AgentEvent> sink) {
    String text = attemptFinalize(session, sink, BUDGET_EDGE_FINALIZE_INSTRUCTION);
    agentTelemetry.recordBudgetEdgeFinalize(text != null);
    return text;
  }

  /**
   * Tempdoc 878 §D.1 — the same graceful terminal at the STEP ceiling.
   *
   * <p>It deliberately does NOT increment {@code agent.budget_edge_finalize.total}: that counter is
   * named for the budget wall, and folding a second terminal into it would silently change what a
   * recorded number means — the class of defect this tempdoc exists to close, committed while
   * closing it. The ceiling's own counter is a metric-schema change and is logged as follow-up.
   */
  String attemptStepCeilingFinalize(AgentSession session, Consumer<AgentEvent> sink) {
    return attemptFinalize(session, sink, STEP_CEILING_FINALIZE_INSTRUCTION);
  }

  /**
   * Tempdoc 878 §D.7 — the run's factual inventory of documents it OPENED, appended to whichever
   * finalize instruction is being sent.
   *
   * <p>Rule 3 of that instruction requires the model to "name what you had gathered and what you had
   * not gotten to yet". A compact model at a truncating terminal, under maximal compression
   * pressure, is being asked to recall that from a transcript whose evidence has just been stripped
   * — which is how 859 §7's run came to claim no access to files sitting in its own context. This
   * hands it the list rather than asking it to remember one.
   *
   * <p>EMPTY appends nothing. A run that opened no documents has nothing to inventory, and a line
   * saying so would be noise on every search-only run — which is most of them.
   */
  private static String openedInventory(AgentSession session) {
    java.util.List<String> opened = session.openedDocumentPaths();
    if (opened.isEmpty()) {
      return "";
    }
    return "\nDocuments you opened in this run, in order: "
        + String.join(", ", opened)
        + ". You opened no others.";
  }

  /**
   * Compress history, ask the model for its best answer with no tools under {@code instruction},
   * and return that text (or {@code null} if it returns nothing or the call fails).
   *
   * <p>Returning {@code null} on every failure is what lets both callers be FAIL-OPEN: a terminal
   * that cannot synthesise lands on exactly the answerless behaviour it replaced, never worse.
   */
  private String attemptFinalize(
      AgentSession session, Consumer<AgentEvent> sink, String instruction) {
    try {
      // Compress tool messages to maximize context space for the finalize call.
      //
      // Tempdoc 865 §7.5 — and RECORD the receipt, like every other compression site. This one
      // matters most: it is the last pass before the terminal's `groundedDone`, so it builds the
      // exact prompt that terminal's answer is written from, under maximal compression pressure.
      // Compressing bare here left the terminal resolving inclusion against a one-pass-stale
      // picture — silently withholding the badge precisely where the most text had been dropped.
      session.recordCompression(compressor.compressToolMessages(session.messages()));
      session.appendMessage(Map.of("role", "user", "content", instruction + openedInventory(session)));
      LlmCallResult result = callLlmWithTools(session, List.of(), sink);
      String text = result.textContent();
      return text != null && !text.isBlank() ? text : null;
    } catch (Exception e) {
      LOG.warn("Finalize LLM call failed", e);
      return null;
    }
  }

  LlmCallResult callLlmWithRetries(
      AgentSession session,
      List<Map<String, Object>> tools,
      Consumer<AgentEvent> eventConsumer) {
    AgentRetryPolicy.RetryDecision llmDecision =
        AgentRetryPolicy.forCode(AgentErrorCode.LLM_TRANSIENT);
    AgentRetryPolicy.RetryDecision emptyDecision =
        AgentRetryPolicy.forCode(AgentErrorCode.EMPTY_RESPONSE);
    int llmAttempt = 0;
    int emptyAttempt = 0;
    while (true) {
      try {
        LlmCallResult result = callLlmWithTools(session, tools, eventConsumer);
        boolean emptyResult =
            result.toolCalls().isEmpty()
                && (result.textContent() == null || result.textContent().isBlank());

        if (!emptyResult) {
          return result;
        }

        if (emptyAttempt >= emptyDecision.maxRetries()) {
          if (emptyDecision.maxRetries() > 0) {
            agentTelemetry.recordRetryExhausted(AgentErrorCode.EMPTY_RESPONSE);
          }
          return result;
        }

        emptyAttempt++;
        agentTelemetry.recordRetry(AgentErrorCode.EMPTY_RESPONSE, emptyAttempt);
        LOG.warn(
            "Empty LLM response; retrying (attempt {}/{})",
            emptyAttempt,
            emptyDecision.maxRetries());
        AgentRetryPolicy.sleepRetryDelay(emptyDecision.delayMsForAttempt(emptyAttempt));
      } catch (RuntimeException e) {
        llmAttempt++;
        if (llmAttempt > llmDecision.maxRetries()) {
          agentTelemetry.recordRetryExhausted(AgentErrorCode.LLM_TRANSIENT);
          throw e;
        }
        agentTelemetry.recordRetry(AgentErrorCode.LLM_TRANSIENT, llmAttempt);
        AgentRetryPolicy.sleepRetryDelay(llmDecision.delayMsForAttempt(llmAttempt));
      }
    }
  }

  /**
   * Resolves the sampling parameters for the current LLM call, adding {@code tool_choice}
   * when the active agent should be forced to produce a tool call.
   */
  static SamplingParams resolveAgentSampling(AgentSession session) {
    if (!AgentTurnPolicy.shouldForceToolCall(session)) {
      return SamplingParams.AGENT;
    }
    // Direction I: apply grammar alongside tool_choice=required for belt-and-suspenders
    // enforcement. Grammar is only forwarded to the server when the tools list is empty
    // (OnlineModeOps guard); when tools are present, tool_choice alone is used.
    // Direction D: suppress thinking-prompt on E0a turns — Organizer acts mechanically.
    return SamplingParams.AGENT
        .withToolChoice("required")
        .withGrammar(AgentLoopService.TOOL_CALL_GRAMMAR)
        .withEnableThinking(false);
  }

  LlmCallResult callLlmWithTools(
      AgentSession session,
      List<Map<String, Object>> tools,
      Consumer<AgentEvent> eventConsumer) {
    return callLlmWithTools(session, tools, eventConsumer, resolveAgentSampling(session));
  }

  LlmCallResult callLlmWithTools(
      AgentSession session,
      List<Map<String, Object>> tools,
      Consumer<AgentEvent> eventConsumer,
      SamplingParams sampling) {

    Span chatSpan = GlobalOpenTelemetry.getTracer(AgentLoopService.TRACER_SCOPE).spanBuilder("chat")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute("gen_ai.operation.name", "chat")
        .startSpan();
    long chatStartNanos = System.nanoTime();

    var textBuilder = new StringBuilder();
    var reasoningBuilder = new StringBuilder();
    var parser = new ToolCallParser();
    var latch = new CountDownLatch(1);
    var errorHolder = new CompletableFuture<Throwable>();

    onlineAiService.streamChatWithTools(
        session.messages(),
        tools,
        DEFAULT_MAX_TOKENS,
        new OnlineAiService.StreamCallbacks(
            chunk -> {
              textBuilder.append(chunk);
              eventConsumer.accept(new AgentEvent.TextChunk(chunk));
            },
            reasoning -> {
              reasoningBuilder.append(reasoning);
              eventConsumer.accept(new AgentEvent.ReasoningChunk(reasoning));
            },
            toolCallDeltaJson -> {
              try {
                JsonNode node = MAPPER.readTree(toolCallDeltaJson);
                parser.accumulateChunk(node);
              } catch (Exception e) {
                LOG.debug("Failed to parse tool call delta", e);
              }
            },
            usage -> {
              // Track token usage from LLM response
              if (usage != null) {
                session.recordUsage(usage.promptTokens(), usage.completionTokens());

                // OTel span attributes (gen_ai semantic conventions)
                if (usage.promptTokens() != null) {
                  chatSpan.setAttribute("gen_ai.usage.input_tokens", (long) usage.promptTokens());
                  agentTelemetry.recordTokenUsage(usage.promptTokens(), "input");
                }
                if (usage.completionTokens() != null) {
                  chatSpan.setAttribute(
                      "gen_ai.usage.output_tokens", (long) usage.completionTokens());
                  agentTelemetry.recordTokenUsage(usage.completionTokens(), "output");
                }

                // Emit budget event (actual usage). totalTokensConsumed is run-cumulative (577 Ext
                // III): the per-call figure cannot reconstruct the ceiling after iteration 1.
                eventConsumer.accept(
                    new AgentEvent.AgentBudgetUpdate(
                        "llm_response",
                        usage.totalTokens() != null ? usage.totalTokens() : 0,
                        session.budgetRemaining(),
                        session.totalTokens(),
                        // Tempdoc 577 §2.14 Root II (#14) — the cognitive-headroom figures: the latest
                        // call's prompt size (current context occupancy) ÷ the model's n_ctx.
                        usage.promptTokens() != null ? usage.promptTokens() : 0,
                        session.contextWindow()));

                LOG.debug(
                    "LLM usage: prompt={}, completion={}, remaining={}",
                    usage.promptTokens(),
                    usage.completionTokens(),
                    session.budgetRemaining());
              }
            },
            fr -> latch.countDown(),
            error -> {
              errorHolder.complete(error);
              latch.countDown();
            }),
        sampling);

    try {
      boolean completed;
      try {
        completed = latch.await(5, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Agent LLM call interrupted", e);
      }
      if (!completed) {
        throw new RuntimeException("Agent LLM call timed out after 5 minutes");
      }

      if (!reasoningBuilder.isEmpty()) {
        LOG.debug(
            "LLM reasoning ({} chars): {}...",
            reasoningBuilder.length(),
            reasoningBuilder.substring(0, Math.min(200, reasoningBuilder.length())));
      }

      if (errorHolder.getNow(null) != null) {
        throw new RuntimeException("LLM call failed", errorHolder.getNow(null));
      }

      List<ToolCallRequest> toolCalls = parser.drainCompleted();

      // Recover/clean tool-call JSON the model emitted into the TEXT channel instead of the structured
      // tool_calls channel. Local models leak this with two grammars ({"name","arguments"} and
      // {"type":"function",…,"parameters"}) and arbitrary delimiters (inline / ';'-separated), so the old
      // structured-empty + newline-split check missed it and the JSON rendered as the "answer". The
      // structured channel still takes precedence: its calls are kept, an exact (name,args) text echo of
      // one is only stripped (no double execution), and only spans naming an AVAILABLE tool are recovered
      // (legitimate JSON-looking prose is left untouched).
      String rawText = textBuilder.toString();
      if (!rawText.isBlank()) {
        Set<String> availableNames = tools.stream()
            .map(t -> {
              Object fn = t.get("function");
              return (fn instanceof Map<?, ?> m) ? String.valueOf(m.get("name")) : null;
            })
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        RecoveredText rt = recoverInlineToolCalls(rawText, toolCalls, availableNames);
        rawText = rt.text();
        if (!rt.recovered().isEmpty()) {
          LOG.warn(
              "Recovered {} tool call(s) the model emitted as text content (not structured tool_calls)",
              rt.recovered().size());
          var merged = new ArrayList<ToolCallRequest>(toolCalls);
          merged.addAll(rt.recovered());
          toolCalls = merged;
        }
      }

      // Think-tag hygiene is upstream now (tempdoc 835 §5.3): OnlineModeOps' streaming parse runs a
      // stateful, frame-straddle-safe filter over content and reroutes captured thinking to the
      // reasoning channel, so the accumulator here never sees tags. The strip that used to live at
      // this point was the second authority over the same fact.
      chatSpan.setStatus(StatusCode.OK);
      return new LlmCallResult(rawText, toolCalls);
    } catch (RuntimeException e) {
      chatSpan.recordException(e);
      chatSpan.setStatus(StatusCode.ERROR, "llm-call-failed");
      throw e;
    } finally {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - chatStartNanos);
      agentTelemetry.recordLlmDuration(durationMs, "chat");
      chatSpan.end();
    }
  }

  /** A tool-call JSON object the model leaked into assistant text, with its [start,end) char span. */
  private record InlineToolCall(int start, int end, String name, String arguments) {}

  /** The assistant text with leaked tool-call JSON removed, plus the calls recovered from it. */
  record RecoveredText(String text, List<ToolCallRequest> recovered) {}

  /**
   * Recover tool calls the model emitted as TEXT content (instead of structured {@code tool_calls}) and
   * strip them from the text. Accepts both grammars local models use — {@code {"name":..,"arguments":..}}
   * and {@code {"type":"function",…,"parameters":..}} / nested {@code {"type":"function","function":..}}
   * — found anywhere via a balanced-brace scan (inline, {@code ';'}-separated, newline). Only spans
   * naming an AVAILABLE tool are acted on (JSON-looking prose is left untouched); an exact (name,args)
   * echo of an already-present structured call is stripped but NOT re-added (no double execution). Pure.
   */
  static RecoveredText recoverInlineToolCalls(
      String text, List<ToolCallRequest> structured, Set<String> availableNames) {
    List<InlineToolCall> spans = scanInlineToolCallJson(text);
    if (spans.isEmpty()) {
      return new RecoveredText(text, List.of());
    }
    Set<String> seen = new LinkedHashSet<>();
    for (ToolCallRequest tc : structured) {
      seen.add(dedupKey(tc.toolName(), tc.arguments()));
    }
    var recovered = new ArrayList<ToolCallRequest>();
    var deletions = new ArrayList<InlineToolCall>();
    int callIndex = 0;
    for (InlineToolCall span : spans) { // forward pass → first-occurrence order + correct echo dedup
      if (!availableNames.contains(span.name())) {
        continue; // unknown tool → could be legitimate content; leave it in the text
      }
      deletions.add(span);
      if (seen.add(dedupKey(span.name(), span.arguments()))) {
        recovered.add(new ToolCallRequest("text-tool-" + callIndex++, span.name(), span.arguments()));
      }
    }
    var sb = new StringBuilder(text);
    for (int k = deletions.size() - 1; k >= 0; k--) { // delete back-to-front so indices stay valid
      sb.delete(deletions.get(k).start(), deletions.get(k).end());
    }
    // Tidy delimiter residue left where spans were removed (";  ;", leading/trailing ';').
    String cleaned = sb.toString()
        .replaceAll("\\s*;(\\s*;)+\\s*", "; ")
        .replaceAll("^[\\s;]+", "")
        .replaceAll("[\\s;]+$", "")
        .strip();
    return new RecoveredText(cleaned, recovered);
  }

  /**
   * Find every JSON object span in {@code text} shaped like a tool call, regardless of delimiter (a
   * balanced-brace scan that respects string literals). Forward order, non-overlapping. Pure.
   */
  static List<InlineToolCall> scanInlineToolCallJson(String text) {
    var found = new ArrayList<InlineToolCall>();
    int i = 0;
    int len = text.length();
    while (i < len) {
      if (text.charAt(i) != '{') {
        i++;
        continue;
      }
      int end = matchBalancedBrace(text, i);
      if (end < 0) {
        break; // no closing brace → a trailing partial object; stop
      }
      InlineToolCall tc = parseToolCallObject(text.substring(i, end), i, end);
      if (tc != null) {
        found.add(tc);
        i = end;
      } else {
        i++;
      }
    }
    return found;
  }

  /** Index AFTER the brace matching the one at {@code open}, or -1 if unbalanced. Respects JSON strings. */
  private static int matchBalancedBrace(String s, int open) {
    int depth = 0;
    boolean inStr = false;
    boolean esc = false;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (esc) {
          esc = false;
        } else if (c == '\\') {
          esc = true;
        } else if (c == '"') {
          inStr = false;
        }
      } else if (c == '"') {
        inStr = true;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    return -1;
  }

  /** Parse a JSON object span as a tool call (both grammars), or null if it is not tool-call-shaped. */
  private static InlineToolCall parseToolCallObject(String span, int start, int end) {
    try {
      JsonNode node = MAPPER.readTree(span);
      if (!node.isObject()) {
        return null;
      }
      boolean typeFunction = "function".equals(node.path("type").asText(""));
      JsonNode fn = node.get("function");
      JsonNode nameNode = (fn != null && fn.isObject()) ? fn.get("name") : node.get("name");
      JsonNode argsNode = (fn != null && fn.isObject()) ? fn.get("arguments") : null;
      if (argsNode == null) {
        argsNode = node.has("arguments") ? node.get("arguments") : node.get("parameters");
      }
      if (nameNode == null || !nameNode.isTextual()) {
        return null;
      }
      boolean hasArgs = argsNode != null && !argsNode.isNull();
      if (!typeFunction && !hasArgs) {
        return null; // e.g. {"name":"John","age":30} is ordinary content, not a tool call
      }
      String args;
      if (!hasArgs) {
        args = "{}";
      } else if (argsNode.isObject()) {
        args = MAPPER.writeValueAsString(argsNode);
      } else if (argsNode.isTextual()) {
        args = argsNode.asText(); // arguments-as-JSON-string variant
      } else {
        return null;
      }
      return new InlineToolCall(start, end, nameNode.asText(), args);
    } catch (Exception e) {
      return null;
    }
  }

  /** Canonicalised (name, args) key for de-duplicating a recovered call against the structured ones. */
  private static String dedupKey(String name, String arguments) {
    try {
      return name + ":" + MAPPER.writeValueAsString(MAPPER.readTree(arguments));
    } catch (Exception e) {
      return name + ":" + arguments;
    }
  }

  static Map<String, Object> buildAssistantToolCallMessage(LlmCallResult result) {
    return buildAssistantToolCallMessage(result, result.toolCalls());
  }

  static Map<String, Object> buildAssistantToolCallMessage(
      LlmCallResult result, List<ToolCallRequest> toolCalls) {
    List<Map<String, Object>> toolCallMaps = toolCalls.stream()
        .map(tc -> Map.<String, Object>of(
            "id", tc.id(),
            "type", "function",
            "function", Map.of("name", tc.toolName(), "arguments", tc.arguments())))
        .toList();
    var msg = new LinkedHashMap<String, Object>();
    msg.put("role", "assistant");
    if (result.textContent() != null && !result.textContent().isEmpty()) {
      msg.put("content", result.textContent());
    }
    msg.put("tool_calls", toolCallMaps);
    return msg;
  }

  private static int resolveInt(ToIntFunction<ResolvedConfig> extractor, int fallback) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.applyAsInt(cs.get()) : fallback;
  }
}
