/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.local;

import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable profile containing determinism and context parameters for LLM inference.
 *
 * <p>This profile ensures inference is reproducible (via fixed seeds) and operates
 * within the model's native constraints (via auto-configuration from model metadata).
 */
public record DeterminismProfile(
    int threads,
    int contextLength,
    int maxNewTokens,
    double temperature,
    double topP,
    double minP,
    double repetitionPenalty,
    int repetitionPenaltyWindow,
    long rngSeed) {

  private static final Logger LOG = LoggerFactory.getLogger(DeterminismProfile.class);

  public DeterminismProfile {
    threads = Math.max(threads, 1);
    contextLength = Math.max(contextLength, 512);
    maxNewTokens = Math.max(maxNewTokens, 1);
    temperature = clamp(temperature, 0.0d, 2.0d);
    topP = clamp(topP, 0.0d, 1.0d);
    minP = clamp(minP, 0.0d, 1.0d);
    repetitionPenalty = clamp(repetitionPenalty, 0.5d, 10.0d);
    repetitionPenaltyWindow = Math.max(0, Math.min(repetitionPenaltyWindow, contextLength));
  }

  /**
   * Creates a profile from config without model metadata validation.
   *
   * <p>Prefer {@link #from(LocalIntentTranslatorConfig, int, int)} when model metadata
   * is available to ensure safe configuration.
   */
  public static DeterminismProfile from(LocalIntentTranslatorConfig config) {
    Objects.requireNonNull(config, "config");
    return new DeterminismProfile(
        config.threads(),
        config.contextLength(),
        config.maxNewTokens(),
        config.temperature(),
        config.topP(),
        config.minP(),
        config.repetitionPenalty(),
        config.repetitionPenaltyWindow(),
        config.rngSeed());
  }

  /**
   * Creates a profile from config, auto-configured using model metadata.
   *
   * <p>This factory method ensures the profile respects the model's native constraints:
   * <ul>
   *   <li>Context length is clamped to the model's training context length</li>
   *   <li>Warnings are logged when config values are adjusted</li>
   * </ul>
   *
   * @param config User-provided configuration
   * @param modelNCtxTrain Model's training context length from GGUF metadata
   * @param modelNEmbed Model's embedding dimension from GGUF metadata
   * @return A safe profile that won't exceed model limits
   */
  public static DeterminismProfile from(
      LocalIntentTranslatorConfig config, int modelNCtxTrain, int modelNEmbed) {
    Objects.requireNonNull(config, "config");

    int requestedCtx = config.contextLength();
    int safeCtx = requestedCtx;

    // Auto-configure context length based on model's training context.
    // Allow override via env var for models that support extended context via RoPE scaling
    // (e.g., nomic-embed-text-v1.5 supports 8192 but GGUF metadata says 2048).
    boolean forceContext = System.getenv("JUSTSEARCH_EMBED_CONTEXT_LENGTH") != null;
    if (modelNCtxTrain > 0 && requestedCtx > modelNCtxTrain) {
      if (forceContext) {
        LOG.warn(
            "llm.autoconfig: Requested contextLength={} exceeds model's nCtxTrain={}. "
                + "Allowing override (JUSTSEARCH_EMBED_CONTEXT_LENGTH set).",
            requestedCtx,
            modelNCtxTrain);
      } else {
        safeCtx = modelNCtxTrain;
        LOG.warn(
            "llm.autoconfig: Requested contextLength={} exceeds model's nCtxTrain={}. "
                + "Auto-adjusting to {} to prevent crashes/truncation.",
            requestedCtx,
            modelNCtxTrain,
            safeCtx);
      }
    }

    // Ensure maxNewTokens fits within the context
    int maxNewTokens = config.maxNewTokens();
    int safeMaxNewTokens = Math.min(maxNewTokens, safeCtx - 64); // Reserve space for prompt
    if (safeMaxNewTokens < maxNewTokens) {
      LOG.info(
          "llm.autoconfig: Adjusted maxNewTokens from {} to {} to fit within context={}",
          maxNewTokens,
          safeMaxNewTokens,
          safeCtx);
    }

    LOG.info(
        "llm.autoconfig: Final profile contextLength={} (model nCtxTrain={}), "
            + "maxNewTokens={}, modelNEmbed={}",
        safeCtx,
        modelNCtxTrain,
        safeMaxNewTokens,
        modelNEmbed);

    return new DeterminismProfile(
        config.threads(),
        safeCtx,
        Math.max(1, safeMaxNewTokens),
        config.temperature(),
        config.topP(),
        config.minP(),
        config.repetitionPenalty(),
        config.repetitionPenaltyWindow(),
        config.rngSeed());
  }

  private static double clamp(double value, double min, double max) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return min;
    }
    return Math.min(Math.max(value, min), max);
  }
}
