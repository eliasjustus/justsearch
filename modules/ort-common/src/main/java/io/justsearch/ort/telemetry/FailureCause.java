/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

import java.util.Locale;

/**
 * Tag value classifying the cause of a GPU session-creation failure. Exposed via
 * {@code ort.session.gpu_init_failure_total{cause=...}} and {@code ort.session.recovery_total}.
 */
public enum FailureCause {
  OOM,
  CUDA_UNAVAILABLE,
  DRIVER_ERROR,
  UNKNOWN;

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Best-effort classifier that routes {@link Throwable}s thrown during GPU session creation
   * into a bounded tag value. Reads exception type + message; any unmatched case is
   * {@link #UNKNOWN}.
   */
  public static FailureCause classifyGpuInitException(Throwable t) {
    if (t == null) return UNKNOWN;
    if (t instanceof UnsatisfiedLinkError) return CUDA_UNAVAILABLE;
    String msg = t.getMessage();
    if (msg == null) return UNKNOWN;
    String lower = msg.toLowerCase(Locale.ROOT);
    if (lower.contains("out of memory") || lower.contains("oom")) return OOM;
    if (lower.contains("cuda") && lower.contains("driver")) return DRIVER_ERROR;
    if (lower.contains("cuda")
        && (lower.contains("not available")
            || lower.contains("unavailable")
            || lower.contains("no kernel image")
            || lower.contains("provider"))) {
      return CUDA_UNAVAILABLE;
    }
    return UNKNOWN;
  }
}
