/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;

/**
 * v3: Response model for GET /api/ai/runtime/status.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.runtime} to {@code app-api} as part of tempdoc 519 §9
 * Block B2. {@link RuntimeActivationService} returns this type.
 */
public record AiRuntimeStatusResponse(
    AiRuntimeActivationStatus activation,
    List<InstalledVariant> installedVariants,
    ActiveRuntime active,
    List<OnnxFeatureStatus> onnxFeatures
) {
  public record InstalledVariant(String variantId, String exePath) {}

  public record ActiveRuntime(
      String serverExecutablePath,
      String activeVariantId,
      Integer gpuLayers,
      // VRAM detection info (for debugging)
      String vramDetectionSource,    // "nvml" | "nvidia-smi" | "none"
      String vramTierDetected,       // "12gb_plus" | "8gb" | "4gb" | "under_4gb" | "unknown"
      List<String> effectiveVramFlags,  // flags actually applied, e.g. ["-ctk", "q4_0", "-ctv", "q4_0"]
      Long vramTotalBytes,
      Long vramFreeBytes
  ) {}

  /** Status of an ONNX cross-encoder feature (reranker or citation scorer). */
  public record OnnxFeatureStatus(
      String id,           // "reranker" | "citation_scorer"
      String label,        // "Search reranking" | "Citation scoring"
      String status,       // "active" | "inactive"
      String reason,       // "auto_discovered" | "explicit_path" | "not_found" | "disabled"
      String modelPath,    // nullable — resolved path for debugging
      boolean modelActive  // true if ORT session is loaded and serving (canonical source of truth)
  ) {}
}
