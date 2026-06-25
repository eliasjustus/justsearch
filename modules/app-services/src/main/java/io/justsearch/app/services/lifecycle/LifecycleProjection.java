/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.contract.wire.LifecycleState;

/**
 * Pure function that derives the external {@link LifecycleState} from capability health.
 * Matches the priority cascade in {@code StatusLifecycleHandler.computeLifecycleSnapshot()}.
 */
public final class LifecycleProjection {

  private LifecycleProjection() {}

  /**
   * Derives the system-wide lifecycle state from capability health.
   *
   * <p>Priority cascade (matches the existing /api/health contract):
   * <ol>
   *   <li>Worker DEGRADED/RECOVERING → ERROR</li>
   *   <li>Worker PENDING → STARTING</li>
   *   <li>Worker OFFLINE → DEGRADED</li>
   *   <li>Worker READY + Inference unhealthy → DEGRADED (inference is optional)</li>
   *   <li>All healthy → READY</li>
   * </ol>
   *
   * @param worker the worker capability
   * @param inference the inference capability
   * @return the external lifecycle state for /api/health
   */
  public static LifecycleState derive(WorkerCapability worker, InferenceCapability inference) {
    CapabilityHealth wh = worker.health();
    switch (wh) {
      case PENDING -> {
        return LifecycleState.LIFECYCLE_STATE_STARTING;
      }
      case DEGRADED, RECOVERING -> {
        return LifecycleState.LIFECYCLE_STATE_ERROR;
      }
      case OFFLINE -> {
        return LifecycleState.LIFECYCLE_STATE_DEGRADED;
      }
      case READY -> {
        // Worker is healthy — check inference (optional, only degrades)
      }
    }

    // Inference: optional capability, does not block READY for non-configured systems
    if (inference.required()) {
      CapabilityHealth ih = inference.health();
      if (ih == CapabilityHealth.DEGRADED || ih == CapabilityHealth.RECOVERING
          || ih == CapabilityHealth.OFFLINE || ih == CapabilityHealth.PENDING) {
        return LifecycleState.LIFECYCLE_STATE_DEGRADED;
      }
    }

    return LifecycleState.LIFECYCLE_STATE_READY;
  }
}
