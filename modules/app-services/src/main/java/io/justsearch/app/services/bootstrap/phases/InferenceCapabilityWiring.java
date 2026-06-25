/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;

/**
 * Tempdoc 519 §7 / Step 7 + F3: capability wiring extracted from the bootstrap's main
 * constructor body. Builds {@link WorkerCapability} + {@link InferenceCapability} based on
 * pre-computed configured-flags ahead of service construction (§4 Phase 2). The inference
 * mode-change listener is wired separately in {@link #attachInferenceModeListener} after the
 * manager exists ({@link io.justsearch.app.services.bootstrap.phases.ServicePhase}).
 */
public final class InferenceCapabilityWiring {

  private InferenceCapabilityWiring() {}

  /** Capability bundle (Phase 2 output — no manager required yet). */
  public record Output(WorkerCapability workerCapability, InferenceCapability inferenceCapability) {}

  /**
   * F3 reorder: Phase 2 capability construction. Runs before service construction. The
   * {@code inferenceConfigured} flag is computed from env / config (see
   * {@code HeadAssembly.decideInferenceConfigured}); no {@link InferenceLifecycleManager}
   * is required at this point.
   */
  public static Output wire(
      KnowledgeServerBootstrap knowledgeServer,
      boolean inferenceConfigured,
      WorkerCapability sharedWorkerCapability) {
    // Tempdoc 627 Deliverable 10: prefer the KS's capability (sync path already shares); else the
    // injected shared instance (async path — created before the worker-start fork so the Head and
    // the late-bound KS drive ONE instance); else a standalone (tests / no injection).
    WorkerCapability workerCapability =
        knowledgeServer != null
            ? knowledgeServer.workerCapability()
            : sharedWorkerCapability != null ? sharedWorkerCapability : new WorkerCapability();
    InferenceCapability inferenceCapability = new InferenceCapability(inferenceConfigured);
    return new Output(workerCapability, inferenceCapability);
  }

  /**
   * F3 reorder: Phase 3 service-construction late-bind. Wires the inference manager's
   * mode-change listener to drive {@link InferenceCapability} state transitions. Called from
   * {@code ServicePhase} after the manager is constructed.
   */
  public static void attachInferenceModeListener(
      InferenceLifecycleManager manager, InferenceCapability inferenceCapability) {
    if (manager == null) {
      return;
    }
    manager.addModeChangeListener(
        (from, to) -> {
          switch (to) {
            case ONLINE -> inferenceCapability.transition(CapabilityHealth.READY, null);
            case OFFLINE ->
                inferenceCapability.transition(CapabilityHealth.OFFLINE, "Inference offline");
            case TRANSITIONING ->
                inferenceCapability.transition(
                    CapabilityHealth.RECOVERING, "Inference transitioning");
            case INDEXING ->
                inferenceCapability.transition(
                    CapabilityHealth.DEGRADED, "GPU allocated to indexing");
          }
        });
  }
}
