/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.configuration.model.ModelRegistry;

/**
 * Helper service that orchestrates AI model installation (downloads, verification,
 * apply-settings). Composed by {@link BrainInstallService} implementations.
 *
 * <p>Interface added as part of tempdoc 519 §9 Block B2. The concrete implementation lives in
 * {@code modules/ui/.../ai/install/} with the same simple name; consumers in {@code app-services}
 * import this interface from {@code app-api} so the production composition root can wire the
 * impl without {@code app-services} importing {@code ui}.
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
   * Return the current install status snapshot. Snapshot is mutable internally; callers must
   * treat the returned reference as a point-in-time view.
   */
  AiInstallStatus getStatus();

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
   * Repair an installed AI runtime (re-verify hashes, re-apply settings).
   * Throws {@link AiInstallException} on validation failures.
   */
  void repair(boolean acceptTerms);
}
