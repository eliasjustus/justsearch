/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static io.justsearch.app.inference.InferenceHttpHelpers.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.Mode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token counting and endpoint probe operations for {@link InferenceLifecycleManager}.
 *
 * <p>Encapsulates the /tokenize and /apply-template endpoint interactions, including cached
 * availability probes. Extracted to reduce the size of the lifecycle manager class.
 */
final class TokenEndpointOps {
  private static final Logger LOG = LoggerFactory.getLogger(TokenEndpointOps.class);

  private static final String PATH_TOKENIZE = "/tokenize";
  private static final String PATH_APPLY_TEMPLATE = "/apply-template";

  private static final Duration ENDPOINT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Supplier<Mode> currentMode;
  private final Supplier<Integer> serverPort;

  // Cached result of tokenize endpoint availability probe
  private volatile Boolean tokenizeEndpointAvailable;

  // Cached result of apply-template endpoint availability probe
  private volatile Boolean applyTemplateEndpointAvailable;

  TokenEndpointOps(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Supplier<Mode> currentMode,
      Supplier<Integer> serverPort) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.currentMode = currentMode;
    this.serverPort = serverPort;
  }

  // ==================== Token Counting ====================

  /**
   * Counts tokens in the given text using llama-server's /tokenize endpoint.
   *
   * <p>Returns empty if the endpoint is unavailable or the server is not in ONLINE mode. This
   * method is best-effort and should not be used for critical control flow.
   */
  Optional<Integer> countTokens(String text) {
    if (text == null || text.isEmpty()) {
      return Optional.of(0);
    }
    if (currentMode.get() != Mode.ONLINE) {
      return Optional.empty();
    }
    if (!supportsTokenize()) {
      return Optional.empty();
    }

    try {
      Map<String, Object> body = Map.of("content", text);
      String json = objectMapper.writeValueAsString(body);

      HttpRequest request =
          buildJsonPostRequest(serverPort.get(), PATH_TOKENIZE, json, ENDPOINT_TIMEOUT);

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        LOG.debug("Tokenize request failed: status={}", response.statusCode());
        return Optional.empty();
      }

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode tokens = root.get("tokens");
      if (tokens != null && tokens.isArray()) {
        return Optional.of(tokens.size());
      }
      return Optional.empty();

    } catch (Exception e) {
      LOG.debug("Tokenize request failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Applies the llama-server chat template to OpenAI-style messages via /apply-template.
   *
   * <p>Returns empty if the endpoint is unavailable or the server is not in ONLINE mode.
   */
  Optional<String> applyTemplate(List<Map<String, Object>> messages) {
    if (messages == null || messages.isEmpty()) {
      return Optional.of("");
    }
    if (currentMode.get() != Mode.ONLINE) {
      return Optional.empty();
    }
    if (!supportsApplyTemplate()) {
      return Optional.empty();
    }

    try {
      Map<String, Object> body = new java.util.HashMap<>();
      body.put("messages", messages);
      // Match generation behavior: include the assistant prefix / generation prompt if supported.
      body.put("add_generation_prompt", true);
      String json = objectMapper.writeValueAsString(body);

      HttpRequest request =
          buildJsonPostRequest(serverPort.get(), PATH_APPLY_TEMPLATE, json, ENDPOINT_TIMEOUT);

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.debug("Apply-template request failed: status={}", response.statusCode());
        return Optional.empty();
      }

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode prompt = root.get("prompt");
      if (prompt != null && prompt.isTextual()) {
        return Optional.of(prompt.asText());
      }
      // Some builds may return the prompt as a raw string body or under a different key.
      if (root.isTextual()) {
        return Optional.of(root.asText());
      }
      return Optional.empty();
    } catch (Exception e) {
      LOG.debug("Apply-template request failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Counts prompt tokens for OpenAI-style messages using /apply-template + /tokenize.
   *
   * <p>This is best-effort and returns empty when unsupported.
   */
  Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
    var prompt = applyTemplate(messages);
    if (prompt.isEmpty()) {
      return Optional.empty();
    }
    return countTokens(prompt.get());
  }

  // ==================== Endpoint Probes ====================

  /**
   * Checks if the llama-server's /tokenize endpoint is available.
   *
   * <p>The result is cached after the first successful probe. Returns false if the server is not in
   * ONLINE mode.
   */
  boolean supportsTokenize() {
    if (currentMode.get() != Mode.ONLINE) {
      return false;
    }

    Boolean cached = tokenizeEndpointAvailable;
    if (cached != null) {
      return cached;
    }

    try {
      HttpRequest request =
          buildJsonPostRequest(
              serverPort.get(), PATH_TOKENIZE, "{\"content\":\"test\"}", PROBE_TIMEOUT);

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean available = response.statusCode() == 200;
      tokenizeEndpointAvailable = available; // NOPMD - cached for external access
      LOG.info("Tokenize endpoint probe: available={}", available);
      return available;

    } catch (Exception e) {
      LOG.debug("Tokenize endpoint probe failed: {}", e.getMessage());
      tokenizeEndpointAvailable = false;
      return false;
    }
  }

  /**
   * Checks if llama-server's /apply-template endpoint is available.
   *
   * <p>The result is cached after the first successful probe. Returns false if the server is not in
   * ONLINE mode.
   */
  boolean supportsApplyTemplate() {
    if (currentMode.get() != Mode.ONLINE) {
      return false;
    }

    Boolean cached = applyTemplateEndpointAvailable;
    if (cached != null) {
      return cached;
    }

    try {
      Map<String, Object> body =
          Map.of(
              "messages",
              List.of(Map.of("role", "user", "content", "test")),
              "add_generation_prompt",
              true);
      String json = objectMapper.writeValueAsString(body);

      HttpRequest request =
          buildJsonPostRequest(serverPort.get(), PATH_APPLY_TEMPLATE, json, PROBE_TIMEOUT);

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      boolean available = response.statusCode() == 200;
      applyTemplateEndpointAvailable = available; // NOPMD - cached for external access
      LOG.info("Apply-template endpoint probe: available={}", available);
      return available;
    } catch (Exception e) {
      LOG.debug("Apply-template endpoint probe failed: {}", e.getMessage());
      applyTemplateEndpointAvailable = false;
      return false;
    }
  }

  // ==================== Cache Management ====================

  /** Clears both endpoint availability caches. Called when switching modes or restarting. */
  void clearCaches() {
    tokenizeEndpointAvailable = null;
    applyTemplateEndpointAvailable = null;
  }
}
