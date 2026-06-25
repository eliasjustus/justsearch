/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Model distribution status for the /api/status endpoint.
 *
 * <p>Reports which download profile was selected and per-model variant info. Null fields are
 * omitted from the JSON response.
 *
 * @param installProfile download profile: GPU_FULL, GPU_LITE, CPU, or null if no contract
 * @param modelVariants per-model variant info (keyed by package ID)
 * @param upgradeGuidance guidance text for CPU/GPU-lite users (null for GPU-full)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelDistributionStatusView(
    String installProfile,
    Map<String, ModelVariantView> modelVariants,
    String upgradeGuidance) {

  /** Returns an empty view when no install contract is available. */
  public static ModelDistributionStatusView unavailable() {
    return new ModelDistributionStatusView(null, null, null);
  }
}
