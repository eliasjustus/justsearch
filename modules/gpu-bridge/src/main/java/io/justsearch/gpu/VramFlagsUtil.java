/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for VRAM-based llama-server flag recommendations.
 *
 * <p>Extracts shared flag merging logic used by both InferenceLifecycleManager and
 * RuntimeActivationService.
 */
public final class VramFlagsUtil {

  private VramFlagsUtil() {}

  /**
   * Merges recommended VRAM flags into an existing command list.
   *
   * <p>Skips config-owned flags (-c and -ngl) and their values. Skips duplicates.
   *
   * @param command existing command list to append to (modified in place)
   * @param recommendedFlags from VramDetector.getRecommendedLlamaServerFlags()
   * @return list of flags actually added (for recording/debugging)
   */
  public static List<String> mergeRecommendedFlags(List<String> command, String[] recommendedFlags) {
    List<String> added = new ArrayList<>();
    if (recommendedFlags == null) {
      return added;
    }

    for (int i = 0; i < recommendedFlags.length; i++) {
      String token = recommendedFlags[i];
      if (token == null || token.isBlank()) {
        continue;
      }

      // Skip config-owned flags (-c / -ngl) AND their values
      if ("-c".equals(token) || "-ngl".equals(token)) {
        if (i + 1 < recommendedFlags.length) {
          String maybeValue = recommendedFlags[i + 1];
          if (maybeValue != null && !maybeValue.startsWith("-")) {
            i++; // skip value token
          }
        }
        continue;
      }

      // Only process real flags; ignore any orphaned value tokens
      if (!token.startsWith("-")) {
        continue;
      }

      // Skip duplicates (by flag), but still skip a following value token if present
      if (command.contains(token)) {
        if (i + 1 < recommendedFlags.length) {
          String maybeValue = recommendedFlags[i + 1];
          if (maybeValue != null && !maybeValue.startsWith("-")) {
            i++; // skip value token
          }
        }
        continue;
      }

      command.add(token);
      added.add(token);
      if (i + 1 < recommendedFlags.length) {
        String maybeValue = recommendedFlags[i + 1];
        if (maybeValue != null && !maybeValue.startsWith("-")) {
          command.add(maybeValue);
          added.add(maybeValue);
          i++; // consume value token
        }
      }
    }
    return added;
  }

  // Default VRAM thresholds - use ~95% of nominal to handle GPUs that report slightly less
  // (e.g., 12GB GPU reports 12878610432 bytes = 11.99 GiB due to reserved memory).
  // Configurable via JUSTSEARCH_VRAM_THRESHOLD_{12GB,8GB,4GB} env vars or system properties.
  // Note: VramDetector has its own parallel thresholds for llama-server flag selection.
  // Overriding these thresholds affects tier classification (UI display) but not VramDetector's
  // GPU flag recommendations.
  private static final long DEFAULT_TWELVE_GB = 11_500_000_000L; // ~10.7 GiB
  private static final long DEFAULT_EIGHT_GB = 7_500_000_000L; // ~7.0 GiB
  private static final long DEFAULT_FOUR_GB = 3_500_000_000L; // ~3.3 GiB

  private static ResolvedConfig.Ai aiOrNull() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? cs.get().ai() : null;
  }

  private static long threshold12gb() {
    ResolvedConfig.Ai ai = aiOrNull();
    long v = ai != null ? ai.vramThreshold12gb() : 0L;
    return v != 0L ? v : DEFAULT_TWELVE_GB;
  }

  private static long threshold8gb() {
    ResolvedConfig.Ai ai = aiOrNull();
    long v = ai != null ? ai.vramThreshold8gb() : 0L;
    return v != 0L ? v : DEFAULT_EIGHT_GB;
  }

  private static long threshold4gb() {
    ResolvedConfig.Ai ai = aiOrNull();
    long v = ai != null ? ai.vramThreshold4gb() : 0L;
    return v != 0L ? v : DEFAULT_FOUR_GB;
  }

  /**
   * Determines VRAM tier from total VRAM bytes.
   *
   * <p>Uses threshold-based comparison rather than integer division to correctly classify GPUs that
   * report slightly less than their nominal capacity (e.g., a 12GB GPU reporting 11.99 GiB).
   * Thresholds are configurable via {@code JUSTSEARCH_VRAM_THRESHOLD_12GB}, {@code _8GB}, {@code
   * _4GB} environment variables.
   *
   * @param vramBytes total VRAM in bytes, or null if unknown
   * @return tier string: "12gb_plus", "8gb", "4gb", "under_4gb", or "unknown"
   */
  public static String detectVramTier(Long vramBytes) {
    if (vramBytes == null || vramBytes < 0) {
      return "unknown";
    }
    if (vramBytes >= threshold12gb()) {
      return "12gb_plus";
    }
    if (vramBytes >= threshold8gb()) {
      return "8gb";
    }
    if (vramBytes >= threshold4gb()) {
      return "4gb";
    }
    return "under_4gb";
  }
}
