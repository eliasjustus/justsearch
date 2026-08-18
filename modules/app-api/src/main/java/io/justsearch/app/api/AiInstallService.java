/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.configuration.model.ModelRegistry;

/**
 * Helper service that orchestrates AI model installation (downloads, verification,
 * apply-settings). Composed by {@link BrainInstallService} implementations.
 *
 * <p>Interface added as part of tempdoc 519 §9 Block B2. The concrete implementation is {@code
 * io.justsearch.app.services.ai.install.AiInstallService} — the same simple name in a different
 * package, so an import here is load-bearing. Consumers (including the {@code modules/ui} HTTP
 * controller) hold this interface; only the composition root names the impl.
 *
 * <p>Stability: stable (API contract).
 */
public interface AiInstallService {

  /**
   * Return the parsed model registry manifest. Used by the install flow to plan downloads
   * and by callers needing to surface available models.
   */
  ModelRegistry getManifest();

  /**
   * Return the current install status as an independent deep copy, taken under the implementation's
   * own lock. Nothing the install run does afterwards touches the returned object, so a caller may
   * serialize or read it with no lock of its own — which is what the HTTP status handler does at 1 Hz
   * while the run keeps mutating its live state.
   */
  AiInstallStatus getStatus();

  /**
   * Whether an install run is in flight — the one bit, without the cost of {@link #getStatus()}.
   *
   * <p>Separate from the status read because that read does real work (a boot-time disk recompute,
   * a staleness reap, and since tempdoc 824 §3.3c a runtime-observation projection that can issue
   * Worker RPCs on a cache miss). Callers that only need "is an install running" — notably
   * {@code RuntimeActivationService}'s per-directory leftover-variant probe — must not pay for it,
   * and must not re-enter the service the projection reads.
   *
   * <p>The default derives the bit from {@link #getStatus()} so an implementation without a cheaper
   * answer stays correct; the production implementation overrides it with a field read.
   */
  default boolean isInstallRunning() {
    AiInstallStatus status = getStatus();
    return status != null && "running".equals(status.state);
  }

  /**
   * Compute a side-effect-free preview of the download plan grouped by capability tier (tempdoc
   * 657), for the current hardware + install intent. Runs no downloads; drives the pre-install
   * honest weight breakdown in the UI.
   */
  InstallPlanPreview previewInstallPlan();

  /**
   * Start the install flow. Idempotent if already running. Throws {@link AiInstallException}
   * on validation failures (e.g., terms not accepted, policy disallows).
   */
  void startInstall(boolean acceptTerms);

  /** Request cancellation of an in-flight install. */
  void cancel();

  /**
   * Repair an installed AI runtime. <b>Repair is start</b>: this re-derives the whole install plan
   * for the current hardware and re-runs it, so it requires {@code acceptTerms} exactly like a first
   * install and re-downloads anything the plan still considers missing. It is NOT a narrow
   * re-verify-hashes-and-re-apply-settings pass, and it is not scoped to the component that is
   * actually broken — per-component repair is future work (tempdoc 840).
   *
   * <p>Throws {@link AiInstallException} on validation failures.
   */
  void repair(boolean acceptTerms);
}
