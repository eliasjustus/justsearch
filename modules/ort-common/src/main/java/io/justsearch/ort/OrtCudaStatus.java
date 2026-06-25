/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.nio.file.Path;
import java.util.List;

/**
 * F1: ORT CUDA self-check status record.
 *
 * <p>Provides structured observability for ONNX Runtime CUDA provider status.
 * Users can query this via /api/status to understand if GPU reranking is working
 * and why it might have failed.
 *
 * @param configured true if GPU was requested in config (gpuEnabled=true)
 * @param attempted true if CUDA provider initialization was attempted
 * @param available true if CUDA provider is currently available for inference
 * @param variantId ORT variant identifier (e.g., "onnxruntime-gpu-1.16.0")
 * @param nativePath path to native libraries directory
 * @param failureReason human-readable reason if unavailable (null if available)
 * @param missingDlls list of missing DLL files detected during self-check (empty if all present)
 */
public record OrtCudaStatus(
    boolean configured,
    boolean attempted,
    boolean available,
    String variantId,
    Path nativePath,
    String failureReason,
    List<String> missingDlls) {

  /** Compact constructor with defensive copy of missingDlls. */
  public OrtCudaStatus {
    missingDlls = missingDlls == null ? List.of() : List.copyOf(missingDlls);
  }

  // -------------------------------------------------------------------------
  // Factory methods for common states
  // -------------------------------------------------------------------------

  /**
   * Creates a status for when CUDA is not configured (GPU disabled in config).
   */
  public static OrtCudaStatus notConfigured() {
    return new OrtCudaStatus(
        false,       // configured
        false,       // attempted
        false,       // available
        null,        // variantId
        null,        // nativePath
        "GPU not configured",
        List.of()
    );
  }

  /**
   * Creates a status for when GPU is configured but the lazy session hasn't been created yet.
   * This is the initial state for all GPU-capable encoders before the first inference batch.
   */
  public static OrtCudaStatus pending(String variantId, Path nativePath) {
    return new OrtCudaStatus(
        true,        // configured
        false,       // attempted (lazy — triggers on first batch)
        false,       // available
        variantId,
        nativePath,
        "GPU session not yet initialized (lazy)",
        List.of()
    );
  }

  /**
   * Creates a status for when CUDA provider initialization succeeded.
   */
  public static OrtCudaStatus ready(String variantId, Path nativePath) {
    return new OrtCudaStatus(
        true,        // configured
        true,        // attempted
        true,        // available
        variantId,
        nativePath,
        null,        // no failure
        List.of()    // no missing DLLs
    );
  }

  /**
   * Creates a status for when required DLLs are missing (pre-flight check failed).
   */
  public static OrtCudaStatus missingDlls(String variantId, Path nativePath, List<String> missing) {
    return new OrtCudaStatus(
        true,        // configured
        true,        // attempted
        false,       // available
        variantId,
        nativePath,
        "Required CUDA DLLs not found: " + String.join(", ", missing),
        missing
    );
  }

  /**
   * Creates a status for when CUDA provider failed to initialize.
   */
  public static OrtCudaStatus providerFailed(String variantId, Path nativePath, String reason) {
    return new OrtCudaStatus(
        true,        // configured
        true,        // attempted
        false,       // available
        variantId,
        nativePath,
        reason,
        List.of()    // DLLs were present but provider still failed
    );
  }

  /**
   * Creates a status for when GPU session was released (yielding to Main).
   */
  public static OrtCudaStatus released(String variantId, Path nativePath) {
    return new OrtCudaStatus(
        true,        // configured
        true,        // attempted
        false,       // available (currently released)
        variantId,
        nativePath,
        "GPU session released (yielding VRAM to main inference)",
        List.of()
    );
  }

  // -------------------------------------------------------------------------
  // Convenience accessors
  // -------------------------------------------------------------------------

  /**
   * Returns true if this status represents a successful CUDA setup.
   */
  public boolean isHealthy() {
    return attempted && available && failureReason == null;
  }

  /**
   * Returns a concise summary string suitable for logging.
   */
  public String toSummary() {
    if (!configured) {
      return "ORT CUDA: not configured";
    }
    if (!attempted) {
      return "ORT CUDA: configured, pending initialization";
    }
    if (available) {
      return "ORT CUDA: ready (variant=" + variantId + ")";
    }
    if (!missingDlls.isEmpty()) {
      return "ORT CUDA: missing DLLs (" + missingDlls.size() + " files)";
    }
    return "ORT CUDA: failed - " + (failureReason != null ? failureReason : "unknown");
  }
}
