/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static io.justsearch.app.inference.InferenceHttpHelpers.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiService.AiUsage;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Online mode HTTP operations for {@link InferenceLifecycleManager}.
 *
 * <p>Encapsulates chat completion, streaming, summarization, Q&A, and vision operations that
 * communicate with the llama-server HTTP API. Owns the online request lock and VDU executor.
 * Extracted to reduce the size of the lifecycle manager class.
 */
final class OnlineModeOps {
  private static final Logger LOG = LoggerFactory.getLogger(OnlineModeOps.class);

  private static final String PATH_CHAT_COMPLETIONS = "/v1/chat/completions";
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);

  /**
   * Strips leaked {@code <think>} tags from model output (llama.cpp #13189 defense).
   *
   * <p>When {@code --reasoning-format deepseek} is enabled, reasoning content should arrive via
   * the {@code reasoning_content} SSE field. However, some edge cases (context exhaustion, model
   * quirks) can cause {@code <think>} tags to leak into the main content field. This pattern
   * strips them as a safety net.
   */
  private static final Pattern THINK_TAGS = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
  private static final long VISION_LOCK_POLL_MS = 50;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Supplier<Mode> currentMode;
  private final Supplier<Integer> serverPort;
  private final Supplier<String> lastKnownModelId;
  private final Supplier<String> configModelFileName;
  // Tempdoc 412 follow-up: request-event sink. Defaults to noop when no telemetry is wired.
  private final InferenceTelemetryEvents events;

  // Package-private for test override (avoids 2-minute waits in unit tests).
  Duration visionLockDeadline = HTTP_TIMEOUT;

  // Priority queue for Online Mode (Chat > VDU)
  private final ReentrantLock onlineRequestLock = new ReentrantLock();
  private final ExecutorService vduExecutor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "VDU-Background");
            t.setDaemon(true);
            return t;
          });

  OnlineModeOps(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<Mode> currentMode,
      Supplier<Integer> serverPort,
      Supplier<String> lastKnownModelId,
      Supplier<String> configModelFileName) {
    this(
        httpClient,
        objectMapper,
        currentMode,
        serverPort,
        lastKnownModelId,
        configModelFileName,
        InferenceTelemetryEvents.noop());
  }

  /**
   * Tempdoc 412 follow-up: events-aware overload. Production callers pass the wired
   * {@link InferenceTelemetryEvents}; tests use the prior constructor (delegates to no-op).
   */
  OnlineModeOps(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<Mode> currentMode,
      Supplier<Integer> serverPort,
      Supplier<String> lastKnownModelId,
      Supplier<String> configModelFileName,
      InferenceTelemetryEvents events) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.currentMode = currentMode;
    this.serverPort = serverPort;
    this.lastKnownModelId = lastKnownModelId;
    this.configModelFileName = configModelFileName;
    this.events = events == null ? InferenceTelemetryEvents.noop() : events;
  }

  /**
   * Tempdoc 412 follow-up: emits {@code onRequestEnqueued}. Best-effort; exceptions logged.
   */
  private void emitRequestEnqueued(RequestKind kind) {
    try {
      events.onRequestEnqueued(kind);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onRequestEnqueued threw: {}", ex.getMessage());
    }
  }

  /**
   * Tempdoc 412 follow-up: records the moment between enqueue and lock-acquisition. Called by
   * lock-acquiring methods immediately after {@code onlineRequestLock.lock()} returns.
   */
  private void emitRequestStarted(RequestKind kind, long enqueueNanos) {
    try {
      events.onRequestStarted(
          kind, Duration.ofNanos(System.nanoTime() - enqueueNanos));
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onRequestStarted threw: {}", ex.getMessage());
    }
  }

  /**
   * Tempdoc 412 follow-up: emits {@code onRequestCompleted} with outcome and total elapsed
   * time. Best-effort; exceptions logged.
   */
  private void emitRequestCompleted(
      RequestKind kind, long enqueueNanos, RequestOutcome outcome) {
    try {
      events.onRequestCompleted(
          kind, Duration.ofNanos(System.nanoTime() - enqueueNanos), outcome);
    } catch (RuntimeException ex) {
      LOG.warn("Telemetry events.onRequestCompleted threw: {}", ex.getMessage());
    }
  }

  /**
   * Tempdoc 412 follow-up: maps a throwable from a request work block to the right
   * {@link RequestOutcome}. {@code InterruptedException} (direct or wrapped) maps to
   * {@link RequestOutcome#CANCELLED}; everything else to {@link RequestOutcome#ERROR}.
   */
  private static RequestOutcome outcomeFromThrowable(Throwable t) {
    if (t == null) return RequestOutcome.OK;
    Throwable cur = t;
    while (cur != null) {
      if (cur instanceof InterruptedException) return RequestOutcome.CANCELLED;
      cur = cur.getCause();
    }
    return RequestOutcome.ERROR;
  }

  // ==================== Chat Completion ====================

  CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens) {
    return chatCompletion(messages, maxTokens, null);
  }

  CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    requireOnline("Chat");

    return CompletableFuture.supplyAsync(
        () -> {
          long enqueueNanos = System.nanoTime();
          emitRequestEnqueued(RequestKind.CHAT);
          onlineRequestLock.lock();
          emitRequestStarted(RequestKind.CHAT, enqueueNanos);
          RequestOutcome outcome = RequestOutcome.ERROR;
          try {
            String result = sendChatRequest(messages, maxTokens, sampling);
            outcome = RequestOutcome.OK;
            return result;
          } catch (RuntimeException re) {
            outcome = outcomeFromThrowable(re);
            throw re;
          } finally {
            onlineRequestLock.unlock();
            emitRequestCompleted(RequestKind.CHAT, enqueueNanos, outcome);
          }
        });
  }

  CompletableFuture<String> visionCompletion(
      String prompt, byte[] imageBytes, int maxTokens) {
    requireOnline("Vision");

    String base64Image = Base64.getEncoder().encodeToString(imageBytes);

    return CompletableFuture.supplyAsync(
        () -> {
          long enqueueNanos = System.nanoTime();
          emitRequestEnqueued(RequestKind.VISION);
          RequestOutcome outcome = RequestOutcome.ERROR;
          try {
            long deadlineNanos = enqueueNanos + visionLockDeadline.toNanos();
            while (!onlineRequestLock.tryLock(VISION_LOCK_POLL_MS, TimeUnit.MILLISECONDS)) {
              if (System.nanoTime() > deadlineNanos) {
                outcome = RequestOutcome.TIMEOUT;
                throw new RuntimeException(
                    "Vision request timed out waiting for chat lock after " + HTTP_TIMEOUT);
              }
            }
            emitRequestStarted(RequestKind.VISION, enqueueNanos);
            try {
              String result = sendVisionRequest(prompt, base64Image, maxTokens);
              outcome = RequestOutcome.OK;
              return result;
            } finally {
              onlineRequestLock.unlock();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = RequestOutcome.CANCELLED;
            throw new RuntimeException("VDU interrupted", e);
          } catch (RuntimeException re) {
            if (outcome == RequestOutcome.ERROR) outcome = outcomeFromThrowable(re);
            throw re;
          } finally {
            emitRequestCompleted(RequestKind.VISION, enqueueNanos, outcome);
          }
        },
        vduExecutor);
  }

  CompletableFuture<String> summarize(String content, int maxTokens) {
    List<Map<String, Object>> messages = buildSummarizationMessages(content);
    return chatCompletion(messages, maxTokens, SamplingParams.DETERMINISTIC);
  }

  CompletableFuture<String> askQuestion(String context, String question, int maxTokens) {
    List<Map<String, Object>> messages =
        List.of(
            Map.of(
                "role",
                "system",
                "content",
                "You are a helpful assistant. Answer questions based on the provided context."),
            Map.of(
                "role", "user", "content", "Context:\n" + context + "\n\nQuestion: " + question));
    return chatCompletion(messages, maxTokens, SamplingParams.DETERMINISTIC);
  }

  // ==================== Streaming ====================

  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    streamChat(messages, maxTokens, onChunk, null, onComplete, onError);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    streamChat(messages, maxTokens, onChunk, onUsage, onComplete, onError, null);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling) {
    streamChat(messages, maxTokens, onChunk, onUsage, onComplete, onError, sampling, true);
  }

  /**
   * Stream a chat completion with explicit sentinel enforcement control.
   *
   * @param requireSentinel if true, the stream must end with {@code data: [DONE]} or {@code
   *     onError} fires with {@link StreamTruncatedException}. If false, a missing sentinel logs at
   *     DEBUG and calls {@code onComplete} (lenient mode for internal accumulation like
   *     map-reduce).
   */
  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChat(
      List<Map<String, Object>> messages,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {

    // Tempdoc 412 follow-up: wrap user callbacks to fire onRequestCompleted exactly once.
    long enqueueNanos = System.nanoTime();
    emitRequestEnqueued(RequestKind.STREAM);
    boolean[] emitted = {false};
    Consumer<String> trackedOnComplete =
        finishReason -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(RequestKind.STREAM, enqueueNanos, RequestOutcome.OK);
          }
          onComplete.accept(finishReason);
        };
    Consumer<Throwable> trackedOnError =
        t -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(
                RequestKind.STREAM, enqueueNanos, outcomeFromThrowable(t));
          }
          onError.accept(t);
        };

    if (currentMode.get() != Mode.ONLINE) {
      trackedOnError.accept(
          new IllegalStateException(
              "Not in Online Mode, current mode is " + currentMode.get()));
      return;
    }

    var unused =
        CompletableFuture.runAsync(
            () -> {
              onlineRequestLock.lock();
              emitRequestStarted(RequestKind.STREAM, enqueueNanos);
              try {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", resolveModelIdForRequests());
                body.put("messages", messages);
                body.put("max_tokens", maxTokens);
                body.put("stream", true);
                if (onUsage != null) {
                  body.put("stream_options", Map.of("include_usage", true));
                }
                if (sampling != null) {
                  body.put("temperature", sampling.temperature());
                  body.put("top_p", sampling.topP());
                }

                String json = objectMapper.writeValueAsString(body);

                LOG.debug(
                    "LLM Stream Request: endpoint=/v1/chat/completions, messages={}, max_tokens={}, body_size={}",
                    messages.size(),
                    maxTokens,
                    json.length());

                HttpRequest request =
                    buildJsonPostRequest(
                        serverPort.get(), PATH_CHAT_COMPLETIONS, json, HTTP_TIMEOUT);

                HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

                LOG.debug("LLM Stream Response: status={}", response.statusCode());

                if (response.statusCode() != 200) {
                  LOG.warn(
                      "LLM Error: status={} - check llama-server logs for details",
                      response.statusCode());
                  trackedOnError.accept(
                      new LlmServerException(response.statusCode(), null));
                  return;
                }

                boolean[] sawDone = {false};
                String[] lastFinishReason = {null};
                try (java.util.stream.Stream<String> lines = response.body()) {
                  lines.forEach(
                      line -> {
                        if (line.equals("data: [DONE]")) {
                          sawDone[0] = true;
                        } else if (line.startsWith("data: ")) {
                          try {
                            String jsonData = line.substring(6);
                            JsonNode node = objectMapper.readTree(jsonData);
                            String fr =
                                node.path("choices")
                                    .path(0)
                                    .path("finish_reason")
                                    .asText(null);
                            if (fr != null) {
                              lastFinishReason[0] = fr;
                            }
                            if (onUsage != null) {
                              AiUsage usage = extractUsageFromChatChunk(node);
                              if (usage != null) {
                                try {
                                  onUsage.accept(usage);
                                } catch (java.util.concurrent.CancellationException cancelled) {
                                  throw cancelled;
                                } catch (Exception ignored) {
                                  // best-effort: never let usage parsing break streaming
                                }
                              }
                            }
                            JsonNode delta = node.path("choices").path(0).path("delta");

                            String content = delta.path("content").asText("");
                            if (!content.isEmpty()) {
                              onChunk.accept(content);
                            }

                            // Consume reasoning_content so it isn't silently lost.
                            // streamChat callers (summary, Q&A, map-reduce) don't need
                            // reasoning — only streamChatWithTools exposes it.
                            String reasoning = delta.path("reasoning_content").asText("");
                            if (!reasoning.isEmpty() && LOG.isDebugEnabled()) {
                              LOG.debug(
                                  "streamChat: discarding {} reasoning chars (no handler)",
                                  reasoning.length());
                            }
                          } catch (java.util.concurrent.CancellationException cancelled) {
                            throw cancelled;
                          } catch (Exception e) {
                            LOG.debug("Failed to parse SSE chunk: {}", line);
                          }
                        }
                      });
                }

                if (sawDone[0]) {
                  trackedOnComplete.accept(lastFinishReason[0]);
                } else if (requireSentinel) {
                  LOG.warn(
                      "LLM stream ended without [DONE] sentinel (finish_reason={})",
                      lastFinishReason[0]);
                  trackedOnError.accept(new StreamTruncatedException(lastFinishReason[0]));
                } else {
                  LOG.debug(
                      "LLM stream ended without [DONE] (lenient mode, finish_reason={})",
                      lastFinishReason[0]);
                  trackedOnComplete.accept(lastFinishReason[0]);
                }

              } catch (java.util.concurrent.CancellationException cancelled) {
                LOG.debug("Stream chat cancelled: {}", cancelled.getMessage());
                try {
                  trackedOnError.accept(cancelled);
                } catch (Exception ignored) {
                  // best-effort
                }
              } catch (Exception e) {
                LOG.error("Stream chat failed", e);
                trackedOnError.accept(e);
              } finally {
                onlineRequestLock.unlock();
              }
            },
            vduExecutor);
    unused.isDone(); // mark as observed; fire-and-forget
  }

  // ==================== Tool-Aware Streaming ====================

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError) {
    streamChatWithTools(
        messages, tools, maxTokens, onChunk, onToolCallDelta, onReasoningChunk,
        onUsage, onComplete, onError, null);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling) {
    streamChatWithTools(messages, tools, maxTokens, onChunk, onToolCallDelta,
        onReasoningChunk, onUsage, onComplete, onError, sampling, true);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "UnusedVariable"})
  void streamChatWithTools(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onChunk,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<String> onReasoningChunk,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {

    // Tempdoc 412 follow-up: wrap user callbacks to fire onRequestCompleted exactly once.
    long enqueueNanos = System.nanoTime();
    emitRequestEnqueued(RequestKind.STREAM);
    boolean[] emitted = {false};
    Consumer<String> trackedOnComplete =
        finishReason -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(RequestKind.STREAM, enqueueNanos, RequestOutcome.OK);
          }
          onComplete.accept(finishReason);
        };
    Consumer<Throwable> trackedOnError =
        t -> {
          if (!emitted[0]) {
            emitted[0] = true;
            emitRequestCompleted(
                RequestKind.STREAM, enqueueNanos, outcomeFromThrowable(t));
          }
          onError.accept(t);
        };

    if (currentMode.get() != Mode.ONLINE) {
      trackedOnError.accept(
          new IllegalStateException(
              "Not in Online Mode, current mode is " + currentMode.get()));
      return;
    }

    var unused =
        CompletableFuture.runAsync(
            () -> {
              onlineRequestLock.lock();
              emitRequestStarted(RequestKind.STREAM, enqueueNanos);
              try {
                Map<String, Object> body = new java.util.HashMap<>();
                body.put("model", resolveModelIdForRequests());
                body.put("messages", messages);
                body.put("max_tokens", maxTokens);
                body.put("stream", true);
                if (tools != null && !tools.isEmpty()) {
                  body.put("tools", tools);
                }
                if (onUsage != null) {
                  body.put("stream_options", Map.of("include_usage", true));
                }
                if (sampling != null) {
                  body.put("temperature", sampling.temperature());
                  body.put("top_p", sampling.topP());
                  if (sampling.toolChoice() != null) {
                    body.put("tool_choice", sampling.toolChoice());
                  }
                  // response_format (JSON schema → server-side GBNF) takes precedence over a raw
                  // grammar, mirroring the non-streaming sendChatRequest path (tempdoc 569 Phase 5:
                  // the conversation engine streams, so the schema constraint MUST be applied here or
                  // it is silently dropped — found by live verification). Both are guarded by the
                  // no-tools condition: llama-server rejects tools + grammar/response_format with
                  // HTTP 400 (it builds its own tool-call grammar from the tools list).
                  if (sampling.responseFormat() != null && (tools == null || tools.isEmpty())) {
                    body.put("response_format", sampling.responseFormat());
                  } else if (sampling.grammar() != null && (tools == null || tools.isEmpty())) {
                    body.put("grammar", sampling.grammar());
                  }
                  // Direction D: per-request thinking-prompt control via chat_template_kwargs.
                  // false = suppress <think> tag in prompt template (E0a, DECIDING turns);
                  // null = omit field (server default applies — typically true for Qwen3).
                  if (sampling.enableThinking() != null) {
                    body.put(
                        "chat_template_kwargs",
                        Map.of("enable_thinking", sampling.enableThinking()));
                  }
                }

                String json = objectMapper.writeValueAsString(body);

                LOG.debug(
                    "LLM Tool Stream Request: endpoint=/v1/chat/completions, messages={}, tools={}, max_tokens={}, body_size={}",
                    messages.size(),
                    tools == null ? 0 : tools.size(),
                    maxTokens,
                    json.length());

                HttpRequest request =
                    buildJsonPostRequest(
                        serverPort.get(), PATH_CHAT_COMPLETIONS, json, HTTP_TIMEOUT);

                HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

                LOG.debug("LLM Tool Stream Response: status={}", response.statusCode());

                if (response.statusCode() != 200) {
                  LOG.warn(
                      "LLM Error: status={} - check llama-server logs for details",
                      response.statusCode());
                  trackedOnError.accept(
                      new LlmServerException(response.statusCode(), null));
                  return;
                }

                boolean[] sawDone = {false};
                String[] lastFinishReason = {null};
                try (java.util.stream.Stream<String> lines = response.body()) {
                  lines.forEach(
                      line -> {
                        if (line.equals("data: [DONE]")) {
                          sawDone[0] = true;
                        } else if (line.startsWith("data: ")) {
                          try {
                            String jsonData = line.substring(6);
                            JsonNode node = objectMapper.readTree(jsonData);
                            String fr =
                                node.path("choices")
                                    .path(0)
                                    .path("finish_reason")
                                    .asText(null);
                            if (fr != null) {
                              lastFinishReason[0] = fr;
                            }

                            if (onUsage != null) {
                              AiUsage usage = extractUsageFromChatChunk(node);
                              if (usage != null) {
                                try {
                                  onUsage.accept(usage);
                                } catch (java.util.concurrent.CancellationException cancelled) {
                                  throw cancelled;
                                } catch (Exception ignored) {
                                  // best-effort
                                }
                              }
                            }

                            JsonNode delta = node.path("choices").path(0).path("delta");

                            // Text content
                            String content = delta.path("content").asText("");
                            if (!content.isEmpty()) {
                              onChunk.accept(content);
                            }

                            // Reasoning content (emitted by --reasoning-format deepseek)
                            if (onReasoningChunk != null) {
                              String reasoning = delta.path("reasoning_content").asText("");
                              if (!reasoning.isEmpty()) {
                                onReasoningChunk.accept(reasoning);
                              }
                            }

                            // Tool call deltas
                            JsonNode toolCalls = delta.path("tool_calls");
                            if (!toolCalls.isMissingNode() && toolCalls.isArray()) {
                              onToolCallDelta.accept(node);
                            }
                          } catch (java.util.concurrent.CancellationException cancelled) {
                            throw cancelled;
                          } catch (Exception e) {
                            LOG.debug("Failed to parse SSE chunk: {}", line);
                          }
                        }
                      });
                }

                if (sawDone[0]) {
                  trackedOnComplete.accept(lastFinishReason[0]);
                } else if (requireSentinel) {
                  LOG.warn(
                      "LLM tool stream ended without [DONE] sentinel (finish_reason={})",
                      lastFinishReason[0]);
                  trackedOnError.accept(new StreamTruncatedException(lastFinishReason[0]));
                } else {
                  LOG.debug(
                      "LLM stream ended without [DONE] (lenient mode, finish_reason={})",
                      lastFinishReason[0]);
                  trackedOnComplete.accept(lastFinishReason[0]);
                }

              } catch (java.util.concurrent.CancellationException cancelled) {
                LOG.debug("Stream chat with tools cancelled: {}", cancelled.getMessage());
                try {
                  trackedOnError.accept(cancelled);
                } catch (Exception ignored) {
                  // best-effort
                }
              } catch (Exception e) {
                LOG.error("Stream chat with tools failed", e);
                trackedOnError.accept(e);
              } finally {
                onlineRequestLock.unlock();
              }
            },
            vduExecutor);
    unused.isDone(); // mark as observed; fire-and-forget
  }

  // ==================== Unified Streaming (Tempdoc 499) ====================

  /**
   * Unified streaming method. Supports all channels, optional tools, and lenient sentinel mode.
   * Delegates to {@link #streamChatWithTools} with the requireSentinel parameter.
   */
  void stream(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      int maxTokens,
      Consumer<String> onContent,
      Consumer<String> onReasoning,
      Consumer<JsonNode> onToolCallDelta,
      Consumer<AiUsage> onUsage,
      Consumer<String> onComplete,
      Consumer<Throwable> onError,
      SamplingParams sampling,
      boolean requireSentinel) {
    streamChatWithTools(messages, tools, maxTokens, onContent, onToolCallDelta,
        onReasoning, onUsage, onComplete, onError, sampling, requireSentinel);
  }

  // Tempdoc 491 §C5 follow-up: streamSummary + streamAnswer forwarders deleted. The shape
  // SPIs (SummarizationStyle PromptContributor + DocAccess ContextInjector for summarize;
  // RAGQAStyle + RAGContext for ask) call streamChat directly with shape-specific message
  // lists. buildSummarizationMessages is retained because the sync `summarize()` call above
  // still uses it; buildAnswerMessages is pruned below (no remaining consumer).

  // ==================== Internal Helpers ====================

  private void requireOnline(String operation) {
    if (currentMode.get() != Mode.ONLINE) {
      throw new IllegalStateException(
          operation + " requires ONLINE mode, but current mode is " + currentMode.get());
    }
  }

  private String resolveModelIdForRequests() {
    String id = lastKnownModelId.get();
    if (id != null && !id.isBlank()) {
      return id;
    }
    try {
      return configModelFileName.get();
    } catch (Exception e) {
      return "default";
    }
  }

  private String sendChatRequest(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    try {
      Map<String, Object> body = new java.util.HashMap<>();
      body.put("model", resolveModelIdForRequests());
      body.put("messages", messages);
      body.put("max_tokens", maxTokens);
      if (sampling != null) {
        body.put("temperature", sampling.temperature());
        body.put("top_p", sampling.topP());
        if (sampling.toolChoice() != null) {
          body.put("tool_choice", sampling.toolChoice());
        }
        // response_format (JSON schema) takes precedence over GBNF grammar — both constrain
        // output, but response_format lets llama-server handle schema→GBNF conversion internally.
        if (sampling.responseFormat() != null) {
          body.put("response_format", sampling.responseFormat());
        } else if (sampling.grammar() != null) {
          body.put("grammar", sampling.grammar());
        }
        // Per-request thinking-prompt control via chat_template_kwargs (parity with streaming path).
        // false = suppress <think> tag in prompt template; null = omit (server default applies).
        if (sampling.enableThinking() != null) {
          body.put(
              "chat_template_kwargs",
              Map.of("enable_thinking", sampling.enableThinking()));
        }
      }

      String json = objectMapper.writeValueAsString(body);

      LOG.debug(
          "LLM Request: endpoint=/v1/chat/completions, messages={}, max_tokens={}, body_size={}",
          messages.size(),
          maxTokens,
          json.length());

      HttpRequest request =
          buildJsonPostRequest(serverPort.get(), PATH_CHAT_COMPLETIONS, json, HTTP_TIMEOUT);

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      LOG.debug(
          "LLM Response: status={}, body_size={}",
          response.statusCode(),
          response.body().length());

      if (response.statusCode() != 200) {
        LOG.warn(
            "LLM Error Response: status={}, body={}",
            response.statusCode(),
            response.body().substring(0, Math.min(500, response.body().length())));
        throw new LlmServerException(response.statusCode(), response.body());
      }

      JsonNode root = objectMapper.readTree(response.body());
      String result = root.path("choices").path(0).path("message").path("content").asText();

      // Strip leaked <think> tags (llama.cpp #13189 defense)
      String stripped = THINK_TAGS.matcher(result).replaceAll("").strip();
      if (stripped.length() < result.length()) {
        LOG.warn(
            "Stripped <think> tags from non-streaming response ({} -> {} chars)",
            result.length(),
            stripped.length());
        result = stripped;
      }

      LOG.debug("LLM Result: length={}", result.length());
      return result;

    } catch (Exception e) {
      throw new RuntimeException("Chat request failed", e);
    }
  }

  private String sendVisionRequest(String prompt, String base64Image, int maxTokens) {
    try {
      List<Map<String, Object>> content = new ArrayList<>();
      content.add(Map.of("type", "text", "text", prompt));
      content.add(
          Map.of(
              "type",
              "image_url",
              "image_url",
              Map.of("url", "data:image/jpeg;base64," + base64Image)));

      List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", content));

      return sendChatRequest(messages, maxTokens, SamplingParams.VDU);

    } catch (RuntimeException e) {
      throw e; // avoid double-wrapping RuntimeExceptions from sendChatRequest
    } catch (Exception e) {
      throw new RuntimeException("Vision request failed", e);
    }
  }

  private static List<Map<String, Object>> buildSummarizationMessages(String content) {
    return List.of(
        Map.of(
            "role",
            "system",
            "content",
            "You are a helpful assistant that summarizes documents concisely. "
                + "Focus on key information: dates, amounts, parties, and main purpose. "
                + "ONLY summarize what is explicitly stated in the provided text. "
                + "Do not add information from outside knowledge."),
        Map.of(
            "role",
            "user",
            "content",
            "Summarize the following document(s):\n\n"
                + content
                + "\n\nProvide a clear, organized summary based ONLY on the text above."));
  }


  static String formatContextAsNumberedPassages(String rawContext) {
    if (rawContext == null || rawContext.isBlank()) {
      return "";
    }
    String[] sections = rawContext.split(DocumentService.SECTION_SEPARATOR);
    StringBuilder sb = new StringBuilder();
    int passageNum = 0;
    for (String section : sections) {
      String trimmed = section.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      passageNum++;
      String source = "unknown";
      String content = trimmed;
      if (trimmed.startsWith("[From: ")) {
        int end = trimmed.indexOf("]\n");
        if (end > 7) {
          source = trimmed.substring(7, end);
          content = trimmed.substring(end + 2);
        }
      }
      if (sb.length() > 0) {
        sb.append("\n\n");
      }
      sb.append("<passage id=\"")
          .append(passageNum)
          .append("\" source=\"")
          .append(source)
          .append("\">\n");
      sb.append(content.trim());
      sb.append("\n</passage>");
    }
    return sb.toString();
  }

  /**
   * Extracts OpenAI-compatible usage information from a streamed chat completion SSE chunk.
   *
   * <p>llama-server emits a final chunk with {@code choices: []} and a {@code usage} object when
   * {@code stream_options.include_usage=true}. Other chunks typically omit {@code usage}.
   */
  static AiUsage extractUsageFromChatChunk(JsonNode root) {
    if (root == null) return null;
    JsonNode usage = root.get("usage");
    if (usage == null || usage.isNull() || !usage.isObject()) return null;
    Integer prompt = asIntOrNull(usage.get("prompt_tokens"));
    Integer completion = asIntOrNull(usage.get("completion_tokens"));
    Integer total = asIntOrNull(usage.get("total_tokens"));
    if (prompt == null && completion == null && total == null) return null;
    return new AiUsage(prompt, completion, total);
  }

  // ==================== Lifecycle ====================

  void shutdown() {
    vduExecutor.shutdownNow();
  }
}
