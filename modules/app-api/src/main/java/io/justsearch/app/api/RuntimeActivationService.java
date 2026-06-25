/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Helper service that orchestrates runtime variant activation (GPU Booster Pack on/off).
 * Composed by {@link RuntimeVariantService} implementations.
 *
 * <p>Interface added as part of tempdoc 519 §9 Block B2. The concrete implementation lives in
 * {@code modules/ui/.../ai/runtime/} with the same simple name; consumers in {@code app-services}
 * import this interface from {@code app-api}.
 *
 * <p>Stability: stable (API contract).
 */
public interface RuntimeActivationService {

  /** Return the current activation-flow status (idle / running / completed / failed). */
  AiRuntimeActivationStatus getActivationStatus();

  /** Return the broader runtime status including installed variants and ONNX feature health. */
  AiRuntimeStatusResponse getStatus();

  /** Begin activating the named variant. Idempotent if already activating that variant. */
  void startActivate(String variantId);

  /** Begin deactivating the currently-active variant (return to default). */
  void startDeactivate();
}
