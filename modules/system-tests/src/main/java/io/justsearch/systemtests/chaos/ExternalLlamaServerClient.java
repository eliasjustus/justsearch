/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight HTTP client for connecting to an externally running llama-server.
 *
 * <p>Unlike {@code InferenceLifecycleManager}, this client does NOT spawn a server process.
 * It connects to an already-running llama-server for integration testing.
 *
 * <p>Use this in tests that need real VLM responses without the complexity of
 * process management.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ExternalLlamaServerClient client = new ExternalLlamaServerClient(8080);
 * if (!client.isHealthy()) {
 *     throw new IllegalStateException("llama-server not running");
 * }
 * String result = client.visionCompletion("Extract text", imageBytes, 2048);
 * }</pre>
 */
public class ExternalLlamaServerClient {
  private static final Logger log = LoggerFactory.getLogger(ExternalLlamaServerClient.class);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final int port;
  private final String baseUrl;

  /**
   * Creates a client connected to llama-server on the specified port.
   *
   * @param port the HTTP port (default is 8080)
   */
  public ExternalLlamaServerClient(int port) {
    this.port = port;
    this.baseUrl = "http://localhost:" + port;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
    this.objectMapper = new ObjectMapper();
    log.info("ExternalLlamaServerClient created for port {}", port);
  }

  /**
   * Creates a client with the default port (8080).
   */
  public ExternalLlamaServerClient() {
    this(8080);
  }

  /**
   * Checks if the llama-server is healthy and responding.
   *
   * @return true if server is healthy
   */
  public boolean isHealthy() {
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/health"))
          .timeout(Duration.ofSeconds(5))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean healthy = response.statusCode() == 200;
      log.debug("Health check: status={}, healthy={}", response.statusCode(), healthy);
      return healthy;
    } catch (Exception e) {
      log.debug("Health check failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Returns the server port.
   */
  public int getPort() {
    return port;
  }

  /**
   * Sends a vision completion request with an image.
   *
   * <p>Uses the OpenAI-compatible API format with base64-encoded image.
   *
   * @param prompt text prompt describing what to extract
   * @param imageBytes raw image bytes (PNG, JPEG)
   * @param maxTokens maximum tokens to generate
   * @return the model's response content
   * @throws RuntimeException if the request fails
   */
  public String visionCompletion(String prompt, byte[] imageBytes, int maxTokens) {
    try {
      String base64Image = Base64.getEncoder().encodeToString(imageBytes);

      // Build vision message content (OpenAI format)
      List<Map<String, Object>> content = List.of(
          Map.of("type", "text", "text", prompt),
          Map.of("type", "image_url", "image_url",
              Map.of("url", "data:image/jpeg;base64," + base64Image)));

      List<Map<String, Object>> messages = List.of(
          Map.of("role", "user", "content", content));

      return sendChatRequest(messages, maxTokens);

    } catch (Exception e) {
      throw new RuntimeException("Vision completion failed: " + e.getMessage(), e);
    }
  }

  /**
   * Sends a chat completion request.
   *
   * @param messages list of chat messages (role + content)
   * @param maxTokens maximum tokens to generate
   * @return the model's response content
   * @throws RuntimeException if the request fails
   */
  public String chatCompletion(List<Map<String, Object>> messages, int maxTokens) {
    return sendChatRequest(messages, maxTokens);
  }

  /**
   * Sends a simple text prompt for completion.
   *
   * @param prompt the user's prompt
   * @param maxTokens maximum tokens to generate
   * @return the model's response
   */
  public String textCompletion(String prompt, int maxTokens) {
    List<Map<String, Object>> messages = List.of(
        Map.of("role", "user", "content", prompt));
    return sendChatRequest(messages, maxTokens);
  }

  private String sendChatRequest(List<Map<String, Object>> messages, int maxTokens) {
    try {
      Map<String, Object> body = Map.of(
          "model", "qwen3-vl",
          "messages", messages,
          "max_tokens", maxTokens);

      String json = objectMapper.writeValueAsString(body);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/v1/chat/completions"))
          .header("Content-Type", "application/json")
          .timeout(REQUEST_TIMEOUT)
          .POST(HttpRequest.BodyPublishers.ofString(json))
          .build();

      log.debug("Sending chat request: maxTokens={}", maxTokens);
      long startTime = System.currentTimeMillis();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      long elapsed = System.currentTimeMillis() - startTime;
      log.debug("Chat response received: status={}, elapsed={}ms", response.statusCode(), elapsed);

      if (response.statusCode() != 200) {
        throw new RuntimeException("Server returned status " + response.statusCode() + ": " + response.body());
      }

      JsonNode root = objectMapper.readTree(response.body());
      return root.path("choices").path(0).path("message").path("content").asText();

    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Chat request failed: " + e.getMessage(), e);
    }
  }

  /**
   * Waits for the server to become healthy within the specified timeout.
   *
   * @param timeoutMs maximum time to wait in milliseconds
   * @param pollIntervalMs interval between health checks
   * @return true if server became healthy, false if timeout
   */
  public boolean awaitHealthy(long timeoutMs, long pollIntervalMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (isHealthy()) {
        return true;
      }
      try {
        Thread.sleep(pollIntervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }
}
