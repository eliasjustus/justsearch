/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import tools.jackson.databind.JsonNode;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interprets llama-server /props JSON and manages external server diagnostics.
 *
 * <p>Pure interpretation layer: takes a {@link JsonNode} from /props, extracts model identity,
 * context size, and external adoption diagnostics. No HTTP I/O, no process management. Extracted
 * from {@link LlamaServerOps} to reduce class size.
 */
final class ServerPropsOps {
  private static final Logger LOG = LoggerFactory.getLogger(ServerPropsOps.class);

  // Conservative estimate of tokens needed for summarization. Used only to warn when the
  // server's actual n_ctx may be too small for summarization workflows.
  private static final int SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS = 3000;

  // ==================== Vision Capability ====================

  private final AtomicBoolean hasVisionCapability = new AtomicBoolean(false);

  // ==================== External Server Adoption Diagnostics ====================

  private final AtomicBoolean externalServerVerified = new AtomicBoolean(false);
  private final AtomicReference<String> externalServerVerificationError =
      new AtomicReference<>(null);
  private final AtomicReference<String> externalServerModelId = new AtomicReference<>(null);
  private final AtomicReference<Integer> externalServerContextTokens = new AtomicReference<>(null);
  private final AtomicBoolean externalServerModelMismatch = new AtomicBoolean(false);
  private final AtomicBoolean externalServerContextTooSmall = new AtomicBoolean(false);
  private final AtomicLong externalServerAdoptedAtMs = new AtomicLong(0);

  // ==================== Injected Dependencies ====================

  private final Supplier<InferenceConfig> config;
  private final Supplier<Boolean> isExternalServerActive;
  private final PropsObserver propsObserver;

  ServerPropsOps(
      Supplier<InferenceConfig> config,
      Supplier<Boolean> isExternalServerActive,
      PropsObserver propsObserver) {
    this.config = config;
    this.isExternalServerActive = isExternalServerActive;
    this.propsObserver = propsObserver;
  }

  // ==================== Props Interpretation ====================

  void updateFromPropsBestEffort(JsonNode root) {
    if (root == null) return;

    applyModelInsightsFromProps(root);
    applyContextInsightsFromProps(root);
    applyVisionCapabilityFromProps(root);
    applyExternalAdoptionInsightsFromProps(root);
  }

  private void applyModelInsightsFromProps(JsonNode root) {
    try {
      String modelId = extractModelIdFromProps(root);
      if (modelId != null && !modelId.isBlank()) {
        propsObserver.onModelIdObserved(modelId);
        LOG.info("llama-server model: {}", modelId);
        warnIfThinkingMismatch(modelId);
      }
    } catch (Exception e) {
      LOG.debug("updateFromPropsBestEffort: model extraction failed: {}", e.getMessage());
    }
  }

  private void warnIfThinkingMismatch(String modelId) {
    ConfigStore cs = ConfigStore.globalOrNull();
    boolean thinkingEnabled = cs != null ? cs.get().ai().useThinking() : true;
    boolean modelLooksThinking =
        modelId.toLowerCase(java.util.Locale.ROOT).contains("thinking");
    if (thinkingEnabled && !modelLooksThinking) {
      LOG.warn(
          "USE_THINKING is enabled but loaded model '{}' does not appear to be a Thinking variant. "
              + "Reasoning features (reasoning_content in SSE) may not work.",
          modelId);
    }
  }

  private void applyContextInsightsFromProps(JsonNode root) {
    Integer actualContextSize = extractContextTokensFromProps(root);
    if (actualContextSize != null && actualContextSize > 0) {
      propsObserver.onContextTokensObserved(actualContextSize);
      LOG.info("llama-server context size: {} tokens", actualContextSize);
      warnIfConfiguredContextExceedsActual(actualContextSize);
      warnIfSummaryBudgetExceedsActual(actualContextSize);
    } else {
      LOG.debug("llama-server /props did not include a parseable n_ctx value");
    }
  }

  private void applyVisionCapabilityFromProps(JsonNode root) {
    boolean vision = root.path("modalities").path("vision").asBoolean(false);
    hasVisionCapability.set(vision);
    if (vision) {
      LOG.info("llama-server reports vision capability (mmproj loaded)");
    }
  }

  /** Returns true if the last /props response indicated vision support. */
  boolean hasVisionCapability() {
    return hasVisionCapability.get();
  }

  private void applyExternalAdoptionInsightsFromProps(JsonNode root) {
    if (!isExternalServerActive.get()) {
      return;
    }

    externalServerModelId.set(propsObserver.observedModelId());
    externalServerContextTokens.set(propsObserver.observedContextTokens());
    boolean looksLike = looksLikeLlamaServerProps(root);
    externalServerVerified.set(looksLike);
    if (looksLike) {
      externalServerVerificationError.set(null);
    } else if (externalServerVerificationError.get() == null) {
      externalServerVerificationError.set("props_missing_expected_fields");
    }
    // Set adoption timestamp if not already set (e.g., test-only path via reflection).
    externalServerAdoptedAtMs.compareAndSet(0, System.currentTimeMillis());
    externalServerModelMismatch.set(detectExternalModelMismatch(root));
    Integer ctx = propsObserver.observedContextTokens();
    boolean ctxTooSmall = ctx != null && ctx < config.get().contextSize();
    externalServerContextTooSmall.set(ctxTooSmall);
  }

  // ==================== Model/Context Extraction ====================

  private String extractModelIdFromProps(JsonNode root) {
    JsonNode alias = root.get("model_alias");
    if (alias != null && alias.isTextual() && !alias.asText().isBlank()) {
      return alias.asText();
    }
    return extractModelPathFileName(root.get("model_path"), "Failed to extract model filename: {}");
  }

  private String extractModelPathFileName(JsonNode modelPathNode, String logOnFailureTemplate) {
    if (modelPathNode == null || !modelPathNode.isTextual() || modelPathNode.asText().isBlank()) {
      return null;
    }
    try {
      return Path.of(modelPathNode.asText()).getFileName().toString();
    } catch (Exception e) {
      if (logOnFailureTemplate != null) {
        LOG.debug(logOnFailureTemplate, e.getMessage());
      }
      return null;
    }
  }

  private void warnIfConfiguredContextExceedsActual(int actualContextSize) {
    int configuredContext = config.get().contextSize();
    if (actualContextSize < configuredContext) {
      LOG.warn(
          "Configured context size ({}) exceeds actual server context ({})! Requests may fail with"
              + " 400 errors.",
          configuredContext,
          actualContextSize);
    }
  }

  private void warnIfSummaryBudgetExceedsActual(int actualContextSize) {
    if (SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS > actualContextSize * 0.8) {
      LOG.warn(
          "SummaryController MAX_CONTEXT_TOKENS ({}) may be too large for server context ({})."
              + " Consider reducing to {}.",
          SUMMARY_CONTROLLER_MAX_CONTEXT_TOKENS,
          actualContextSize,
          (int) (actualContextSize * 0.7));
    }
  }

  private boolean detectExternalModelMismatch(JsonNode root) {
    String externalName =
        extractModelPathFileName(root.get("model_path"), "Model mismatch detection failed: {}");
    if (externalName == null || externalName.isBlank()) {
      return false;
    }
    String configuredName = config.get().modelPath().getFileName().toString();
    return !externalName.equalsIgnoreCase(configuredName);
  }

  // ==================== Static Parsing Utilities ====================

  /**
   * Extracts llama-server context size (n_ctx) from the /props JSON.
   *
   * <p>Different llama-server versions nest n_ctx in different places. We look in:
   *
   * <ul>
   *   <li>{@code n_ctx}
   *   <li>{@code default_generation_settings.n_ctx}
   *   <li>{@code default_generation_settings.params.n_ctx}
   *   <li>{@code params.n_ctx}
   * </ul>
   *
   * @return n_ctx, or null if not found / not parseable
   */
  static Integer extractContextTokensFromProps(JsonNode root) {
    if (root == null) return null;

    Integer direct = asPositiveInt(root.get("n_ctx"));
    if (direct != null) return direct;

    JsonNode dgs = root.get("default_generation_settings");
    if (dgs != null) {
      Integer nested = asPositiveInt(dgs.get("n_ctx"));
      if (nested != null) return nested;
      JsonNode params = dgs.get("params");
      if (params != null) {
        Integer nestedParams = asPositiveInt(params.get("n_ctx"));
        if (nestedParams != null) return nestedParams;
      }
    }

    JsonNode params = root.get("params");
    if (params != null) {
      Integer nestedParams = asPositiveInt(params.get("n_ctx"));
      if (nestedParams != null) return nestedParams;
    }

    return null;
  }

  static Integer asPositiveInt(JsonNode node) {
    if (node == null || node.isNull()) return null;
    try {
      int value;
      if (node.isInt() || node.isLong() || node.isNumber()) {
        value = node.asInt();
      } else if (node.isTextual()) {
        value = Integer.parseInt(node.asText().trim());
      } else {
        return null;
      }
      return value > 0 ? value : null;
    } catch (Exception e) {
      LOG.debug("asPositiveInt: parsing failed: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Checks whether a /props JSON response looks like it came from a llama-server. Returns {@code
   * true} if the root contains a model_alias, model_path, or positive n_ctx.
   */
  static boolean looksLikeLlamaServerProps(JsonNode root) {
    if (root == null) return false;
    Integer ctx = extractContextTokensFromProps(root);
    JsonNode alias = root.get("model_alias");
    if (alias != null && alias.isTextual() && !alias.asText().isBlank()) return true;
    JsonNode modelPath = root.get("model_path");
    if (modelPath != null && modelPath.isTextual() && !modelPath.asText().isBlank()) return true;
    return ctx != null && ctx > 0;
  }

  // ==================== External Diagnostics State ====================

  /**
   * Resets all external server adoption diagnostics to initial state. Called when adopting a new
   * external server.
   */
  void resetExternalAdoptionState(boolean verified, String verificationError) {
    hasVisionCapability.set(false);
    externalServerAdoptedAtMs.set(System.currentTimeMillis());
    externalServerVerified.set(verified);
    externalServerVerificationError.set(verificationError);
    externalServerModelId.set(null);
    externalServerContextTokens.set(null);
    externalServerModelMismatch.set(false);
    externalServerContextTooSmall.set(false);
  }

  /**
   * Builds a snapshot of external server diagnostics for API exposure. Periodic health monitoring
   * fields are passed in because they remain owned by {@link LlamaServerOps}.
   */
  InferenceLifecycleManager.ExternalServerDiagnostics buildExternalDiagnostics(
      boolean usingExternal,
      long lastPeriodicHealthOkAtMs,
      String lastPeriodicHealthError,
      int consecutiveHealthFailures) {
    return new InferenceLifecycleManager.ExternalServerDiagnostics(
        usingExternal,
        externalServerVerified.get(),
        externalServerVerificationError.get(),
        externalServerModelId.get(),
        externalServerContextTokens.get(),
        externalServerModelMismatch.get(),
        externalServerContextTooSmall.get(),
        externalServerAdoptedAtMs.get(),
        lastPeriodicHealthOkAtMs,
        lastPeriodicHealthError,
        consecutiveHealthFailures);
  }
}
