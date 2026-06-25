/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.services.bootstrap.CapabilityGraph;
import io.justsearch.app.services.bootstrap.PhaseOutcome;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tempdoc 519 §4 Phase 2 — capability resolution. Runs BEFORE {@link ServicePhase} (F3
 * reorder): takes a pre-computed {@code inferenceConfigured} flag rather than an already-built
 * {@code InferenceLifecycleManager}. ServicePhase later constructs the manager and calls
 * {@link InferenceCapabilityWiring#attachInferenceModeListener} to drive transitions.
 *
 * <p>Worker capability is sourced from the supplied {@link KnowledgeServerBootstrap} when
 * present (so worker health transitions flow into head-side capability state) or constructed
 * standalone when null.
 *
 * <p>Output: {@link CapabilityGraph} — the §10 phase-chain output type for Phase 2.
 *
 * <p>Tempdoc 541 §5.3 — first phase migrated to sealed-sum {@link PhaseOutcome}. Outcomes:
 *
 * <ul>
 *   <li>{@link PhaseOutcome.Ready} — Worker bootstrap present AND inference configured.
 *   <li>{@link PhaseOutcome.Degraded} — Worker bootstrap absent OR inference not configured;
 *       capability graph constructed standalone with non-empty reason codes
 *       ({@code "worker.not_connected"}, {@code "inference.not_configured"}).
 *   <li>{@link PhaseOutcome.Failed} — InferenceCapabilityWiring throws on construction
 *       (currently unreachable; placeholder for future failure modes).
 * </ul>
 *
 * <p>Tempdoc 541 fix-pass E.4: the legacy {@code run()} entry point has been deleted. All
 * production callers and tests use {@link #runWithOutcome}. Callers that want the legacy
 * "throw on non-Ready" semantics chain {@code .orThrow()} explicitly — this is rare in
 * production because Degraded outcomes are observable signals, not error states.
 */
public final class CapabilityPhase {

  /** Reason code: Worker bootstrap was null at phase entry (async-start path). */
  public static final String REASON_WORKER_NOT_CONNECTED = "worker.not_connected";

  /** Reason code: inference not configured (lite mode, AI disabled, or env flag). */
  public static final String REASON_INFERENCE_NOT_CONFIGURED = "inference.not_configured";

  private CapabilityPhase() {}

  /**
   * Tempdoc 541 §5.3 — sealed-sum entry point. The single production entry; pattern-match on
   * Ready / Degraded / Failed arms to introspect outcome. Use {@code .orThrow()} for callers
   * that want the legacy "throw on non-Ready" semantics.
   */
  public static PhaseOutcome<CapabilityGraph> runWithOutcome(
      KnowledgeServerBootstrap knowledgeServer,
      boolean inferenceConfigured,
      WorkerCapability sharedWorkerCapability) {
    try {
      InferenceCapabilityWiring.Output out =
          InferenceCapabilityWiring.wire(
              knowledgeServer, inferenceConfigured, sharedWorkerCapability);
      CapabilityGraph graph =
          new CapabilityGraph(out.workerCapability(), out.inferenceCapability());
      Set<String> reasons = new LinkedHashSet<>();
      if (knowledgeServer == null) {
        reasons.add(REASON_WORKER_NOT_CONNECTED);
      }
      if (!inferenceConfigured) {
        reasons.add(REASON_INFERENCE_NOT_CONFIGURED);
      }
      if (reasons.isEmpty()) {
        return new PhaseOutcome.Ready<>(graph);
      }
      return new PhaseOutcome.Degraded<>(graph, reasons);
    } catch (RuntimeException e) {
      return PhaseOutcome.Failed.of(e);
    }
  }
}
