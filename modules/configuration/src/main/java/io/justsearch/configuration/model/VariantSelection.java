/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.nio.file.Path;

/**
 * The resolved model variant for a specific model and hardware profile.
 *
 * <p>Output of the variant selection function. Carries enough information for the session manager
 * to create the correct ORT session and for the observability layer to report degradation.
 *
 * @param modelFile absolute path to the ONNX model file to load
 * @param precision precision of the selected variant
 * @param executionProvider which EP to use (CPU or CUDA)
 * @param degraded true if this is not the optimal variant for the hardware (e.g., FP16 on CPU
 *     because FP32 wasn't downloaded)
 * @param degradationReason human-readable reason if degraded (nullable)
 */
public record VariantSelection(
    Path modelFile,
    ModelPrecision precision,
    ExecutionProvider executionProvider,
    boolean degraded,
    String degradationReason) {

  /** Creates a non-degraded selection. */
  public static VariantSelection optimal(
      Path modelFile, ModelPrecision precision, ExecutionProvider ep) {
    return new VariantSelection(modelFile, precision, ep, false, null);
  }

  /** Creates a degraded selection with an explanation. */
  public static VariantSelection degraded(
      Path modelFile, ModelPrecision precision, ExecutionProvider ep, String reason) {
    return new VariantSelection(modelFile, precision, ep, true, reason);
  }
}
