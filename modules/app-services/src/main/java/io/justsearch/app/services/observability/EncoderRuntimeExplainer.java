/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.ort.EncoderRole;
import java.util.Map;

/**
 * Pure-function derivation of an {@link EncoderRuntimeView} from the (policy snapshot,
 * OrtCudaView) pair produced by {@code RemoteKnowledgeClient} (tempdoc 422).
 *
 * <p>Implements the explainer decision tree from tempdoc 422 §3 Path A: maps the configured
 * accelerator (from the policy's {@code variant.executionProvider}) and the runtime probe
 * outcome (from {@link OrtCudaView}) to a stable {@code currentAccelerator} string + a
 * human-readable explanation.
 *
 * <p>Defensive against missing/malformed policy JSON: the policy sub-map is decoupled from
 * compile-time types per §14.28 U4, so this explainer must not crash if the JSON shape evolves.
 *
 * <p>Defer (per tempdoc 422 sequencing): CPU-forced-mode honest reporting (case 5 below)
 * still uses the slightly-imperfect "until first inference" wording when configured for GPU
 * but not yet attempted; a follow-up will plumb the assembler's accelerator-decision through
 * a typed channel.
 */
public final class EncoderRuntimeExplainer {

  static final String ACCEL_CUDA = "cuda";
  static final String ACCEL_CPU = "cpu";
  static final String ACCEL_UNAVAILABLE = "unavailable";

  private EncoderRuntimeExplainer() {}

  /**
   * Derives a runtime view for one encoder.
   *
   * @param role encoder role (used only for log messages and policy null-handling cases)
   * @param view runtime OrtCuda probe view; {@code null} maps to {@link OrtCudaView#notConfigured()}
   * @param policySubMap raw {@code models[ROLE]} sub-map from
   *     {@code RemoteKnowledgeClient.getSessionPolicies()}; {@code null} when the role is not
   *     active in the current configuration
   */
  public static EncoderRuntimeView explain(
      EncoderRole role, OrtCudaView view, Map<String, Object> policySubMap) {
    OrtCudaView details = view == null ? OrtCudaView.notConfigured() : view;
    String configuredAccelerator = extractExecutionProvider(policySubMap);

    // Case 1: policy missing → encoder isn't part of the active configuration.
    if (policySubMap == null) {
      return new EncoderRuntimeView(
          ACCEL_UNAVAILABLE,
          configuredAccelerator,
          false,
          "Encoder not active in current configuration.",
          Map.of(),
          details);
    }

    // Case 2: policy explicitly opts into CPU.
    if ("CPU".equalsIgnoreCase(configuredAccelerator)) {
      return new EncoderRuntimeView(
          ACCEL_CPU,
          configuredAccelerator,
          true,
          "Encoder configured for CPU by design.",
          policySubMap,
          details);
    }

    // Case 3: GPU attempted and succeeded.
    if (details.attempted() && details.available()) {
      String arenaInfo = formatArenaInfo(policySubMap);
      String message =
          "GPU initialized successfully on CUDA device 0" + arenaInfo + ".";
      return new EncoderRuntimeView(
          ACCEL_CUDA, configuredAccelerator, true, message, policySubMap, details);
    }

    // Case 4: GPU attempted but failed → CPU fallback with a concrete reason.
    if (details.attempted() && !details.available() && !isBlank(details.failureReason())) {
      return new EncoderRuntimeView(
          ACCEL_CPU,
          configuredAccelerator,
          true,
          "GPU init failed: " + details.failureReason() + ". Running on CPU fallback.",
          policySubMap,
          details);
    }

    // Case 5: configured for GPU but not yet attempted (defer honest reporting per 422).
    if (!details.attempted() && details.configured()) {
      return new EncoderRuntimeView(
          ACCEL_CPU,
          configuredAccelerator,
          true,
          "GPU configured but not yet attempted; running on CPU until first inference.",
          policySubMap,
          details);
    }

    // Case 6: catch-all → CPU with a pointer to the raw policy snapshot.
    return new EncoderRuntimeView(
        ACCEL_CPU,
        configuredAccelerator,
        true,
        "GPU not active; running on CPU. See /api/debug/session-policies for raw policy.",
        policySubMap,
        details);
  }

  /**
   * Reads {@code policy.variant.executionProvider} defensively; returns {@code ""} if the path
   * is missing or shaped unexpectedly.
   */
  private static String extractExecutionProvider(Map<String, Object> policy) {
    if (policy == null) return "";
    Object variantNode = policy.get("variant");
    if (!(variantNode instanceof Map<?, ?> variantMap)) return "";
    Object ep = variantMap.get("executionProvider");
    return ep == null ? "" : ep.toString();
  }

  /**
   * Reads {@code policy.gpu.arenaCapBytes} defensively and formats as " arena cap N MB" if
   * present, otherwise returns the empty string.
   */
  private static String formatArenaInfo(Map<String, Object> policy) {
    if (policy == null) return "";
    Object gpuNode = policy.get("gpu");
    if (!(gpuNode instanceof Map<?, ?> gpuMap)) return "";
    Object cap = gpuMap.get("arenaCapBytes");
    if (cap == null) return "";
    long bytes;
    if (cap instanceof Number num) {
      bytes = num.longValue();
    } else {
      try {
        bytes = Long.parseLong(cap.toString());
      } catch (NumberFormatException e) {
        return "";
      }
    }
    if (bytes <= 0) return "";
    long mb = bytes / (1024L * 1024L);
    return "; arena cap " + mb + " MB";
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
