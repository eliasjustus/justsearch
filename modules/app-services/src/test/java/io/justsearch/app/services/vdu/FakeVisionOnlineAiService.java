/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fake {@link OnlineAiService} for {@code VduProcessor}-level abstention-gate tests (tempdoc 677
 * item 7b) — real vision/chat calls are replaced with configurable, immediately-completed
 * futures so tests exercise the real {@link VduProcessor} wiring (Stage 0/1) without a real
 * llama-server.
 */
final class FakeVisionOnlineAiService implements OnlineAiService {

  private final Deque<VisionCompletionResult> queuedVisionResults = new ArrayDeque<>();
  private VisionCompletionResult defaultVisionResult =
      new VisionCompletionResult("default extracted text", "stop", 10, -0.058, 0.0);
  private String chatCompletionResult = "{\"summary\":\"ok\"}";
  private final List<byte[]> visionCallImageBytes = new ArrayList<>();
  private int chatCompletionCallCount = 0;

  FakeVisionOnlineAiService withVisionResults(VisionCompletionResult... results) {
    queuedVisionResults.clear();
    for (VisionCompletionResult result : results) {
      queuedVisionResults.add(result);
    }
    return this;
  }

  FakeVisionOnlineAiService withDefaultVisionResult(VisionCompletionResult result) {
    this.defaultVisionResult = result;
    return this;
  }

  FakeVisionOnlineAiService withChatCompletionResult(String json) {
    this.chatCompletionResult = json;
    return this;
  }

  int getVisionCallCount() {
    return visionCallImageBytes.size();
  }

  int getChatCompletionCallCount() {
    return chatCompletionCallCount;
  }

  @Override
  public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens) {
    return visionCompletionDetailed(prompt, imageBytes, maxTokens, SamplingParams.VDU, null);
  }

  /**
   * Tempdoc 677 Stage 2: the agreement probe calls the 5-arg overload — routed through the same
   * queue/call-count tracking as the 3-arg overload above so a test can queue [pass1Result,
   * probeResult] and assert on a single unified call count.
   */
  @Override
  public CompletableFuture<VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens, SamplingParams sampling, Long seed) {
    visionCallImageBytes.add(imageBytes);
    VisionCompletionResult result =
        queuedVisionResults.isEmpty() ? defaultVisionResult : queuedVisionResults.poll();
    return CompletableFuture.completedFuture(result);
  }

  @Override
  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    chatCompletionCallCount++;
    return CompletableFuture.completedFuture(chatCompletionResult);
  }

  @Override
  public CompletableFuture<String> summarize(String content) {
    return CompletableFuture.completedFuture("summary");
  }

  @Override
  public CompletableFuture<String> askQuestion(String question, String context) {
    return CompletableFuture.completedFuture("answer");
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public boolean isStartingUp() {
    return false;
  }
}
