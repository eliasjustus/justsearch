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

  /**
   * Status of one ONNX encoder: the two cross-encoders the Head configures (reranker, citation
   * scorer) plus the two always-on Worker encoders (embedding, SPLADE) added by tempdoc 806 B.2 —
   * they fell back to CPU in round 11 alongside the reranker and the list could not say so.
   *
   * <p>The first six components are the INTENT axis: what the Head configured and what the Worker
   * discovered on disk. Tempdoc 805 G.3 (round-11 F3) adds the OBSERVED axis beside it — the
   * execution provider the ORT session actually runs on. Both are needed because {@code status:
   * "active"} plus {@code modelActive: true} was reported verbatim for a session that had silently
   * fallen back from CUDA to CPU (the CUDA natives were missing after an upgrade): a session exists,
   * so the intent fields were not lying — they simply could not express the outcome.
   *
   * <p>The observed fields project {@code EncoderRuntimeExplainer}'s derivation (policy snapshot ×
   * OrtCuda probe), the same authority behind {@code GET /api/inference/encoders}.
   */
  public record OnnxFeatureStatus(
      String id,           // "reranker" | "citation_scorer" | "embed" | "splade"
      String label,        // "Search reranking" | "Citation scoring" | "Semantic embedding" | ...
      String status,       // "active" | "inactive" | "unknown" (Worker has not answered yet)
      String reason,       // "auto_discovered" | "explicit_path" | "not_found" | "disabled"
                           // | "worker_policy_snapshot" | "not_configured" | "worker_not_answered"
      String modelPath,    // nullable — resolved path for debugging
      boolean modelActive, // true if ORT session is loaded and serving (canonical source of truth)
      // ---- observed (tempdoc 805 G.3) ----
      String executionProvider, // "cuda" | "cpu" | "none" | "unknown" — what the session RUNS on
      boolean gpuFallback,      // true when GPU was configured but the session runs on CPU
      String fallbackReason     // nullable — concrete reason when gpuFallback (probe failure, missing DLLs)
  ) {}
}
