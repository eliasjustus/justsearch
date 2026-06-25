/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Operator/runtime control surface for the Online AI runtime.
 *
 * <p>This is intentionally separate from {@link OnlineAiService} so user-facing operations (ask/summarize)
 * do not need to expose lifecycle/reload controls.
 */
public interface OnlineAiRuntimeControl {

  /**
   * Restart/apply policy for config changes.
   */
  enum RestartPolicy {
    /** Apply config changes without starting/restarting llama-server. */
    APPLY_ONLY,
    /** Restart llama-server only if it is currently running in Online mode. */
    RESTART_IF_ONLINE,
    /** Always restart llama-server (may start it even if currently offline). */
    RESTART_ALWAYS
  }

  /**
   * Result of detaching from an adopted external llama-server instance.
   *
   * <p>Detaching starts a JustSearch-owned llama-server process on a new free port, leaving the
   * external server untouched.
   */
  record DetachExternalServerResult(boolean detached, int previousPort, int newPort) {}

  /**
   * Apply runtime overrides for llama-server configuration.
   *
   * <p>Callers typically persist settings via {@code /api/settings} first, then request an apply/reload
   * so the running llama-server (if any) is restarted with the updated config.
   *
   * @param llmModelPath full path to the generative model file (GGUF), or blank to keep current
   * @param contextLength desired context window size (tokens), or null/<=0 to keep current
   * @param gpuLayers desired GPU layer offload, or null/<0 to keep current
   * @param restartPolicy restart behavior
   */
  void applyRuntimeOverrides(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy);

  /**
   * Tempdoc 412 follow-up Bug E: admin-attributed apply-runtime path. Same as
   * {@link #applyRuntimeOverrides(String, Integer, Integer, RestartPolicy)} but the underlying
   * transition is tagged {@code reason=admin_triggered} so operator-triggered reloads are
   * distinguishable from settings-page-driven applies in the metric stream. Default impl falls
   * back to the four-arg form (preserves legacy reason); overrides should propagate the
   * admin-triggered classification.
   */
  default void applyRuntimeOverridesAdmin(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    applyRuntimeOverrides(llmModelPath, contextLength, gpuLayers, restartPolicy);
  }

  /**
   * Detaches from an adopted external llama-server instance (if any) and starts a JustSearch-owned
   * llama-server on a new free port.
   *
   * <p>If the runtime is not currently using an external server, returns {@code detached=false}.
   */
  DetachExternalServerResult detachExternalServer();

  /**
   * Tempdoc 412 Phase 5: triggers a config-driven restart (RESTART_IF_ONLINE) without changing
   * any config values. Used by the admin endpoint {@code POST /api/admin/inference/reload} to
   * cycle the inference runtime — operators apply config changes via
   * {@link #applyRuntimeOverrides} when they need to change values; this is the no-arg
   * "restart with same config" affordance.
   *
   * <p>Default implementation delegates to {@link #applyRuntimeOverrides} with all-null
   * overrides and {@link RestartPolicy#RESTART_IF_ONLINE}, then returns elapsed wall-clock ms.
   *
   * @return elapsed ms for the apply call (0 if not online — RESTART_IF_ONLINE is a no-op)
   */
  default long reloadRuntime() {
    long startMs = System.currentTimeMillis();
    applyRuntimeOverridesAdmin(null, null, null, RestartPolicy.RESTART_IF_ONLINE);
    return System.currentTimeMillis() - startMs;
  }
}
