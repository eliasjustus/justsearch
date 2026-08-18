/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.function.BooleanSupplier;

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
   * mode-change listener AND the runtime-authority spec to drive {@link InferenceCapability}
   * state transitions. Called from {@code ServicePhase} after the manager is constructed.
   *
   * <p>Tempdoc 737 §12c item 2 (Phase 2a) — spec-aware rekey. Previously {@code ONLINE} alone
   * drove {@code READY}; the reported gap (tempdoc 737 §15 Phase 2b "Phase-3 finding") was that a
   * background procedure (VDU) can hold the engine {@code ONLINE} under soft-off
   * ({@code chatEnabled=false}), and the old derivation projected chat as available to users
   * during that window. {@code READY} now requires BOTH engine Healthy ({@code mode==ONLINE})
   * AND the user's persisted chat-enabled spec bit; engine Healthy with {@code chatEnabled=false}
   * yields {@code DEGRADED} with {@link RuntimeStatus#REASON_ENGINE_UP_FOR_BACKGROUND} — the same
   * reason code {@code RuntimeReconciler.refreshStatus} stamps onto the ENGINE condition for the
   * identical situation, so the two surfaces agree. OFFLINE / TRANSITIONING / INDEXING mappings
   * are unchanged.
   *
   * <p><b>Re-derivation mechanism</b> (mirror-initial-then-forward, the
   * {@code standalone-capability-stays-stuck} medicine): the mode-change listener re-derives on
   * every ENGINE mode transition (unchanged trigger). ADDITIONALLY, {@code runtimeReconciler}'s
   * {@link RuntimeReconciler#addSpecChangeListener} re-derives — using the manager's live mode —
   * whenever the spec flips WITHOUT an accompanying mode change (e.g. {@code chatEnabled} toggles
   * off while a VDU procedure holds the engine {@code ONLINE}: no {@code ModeChangeListener} fires
   * because the observed mode never changes, but the derived capability must). This is the
   * smallest correct mechanism — one shared derivation function ({@link #deriveAndApply}), two
   * triggers, no polling. {@code runtimeSpecStore} / {@code runtimeReconciler} are both nullable
   * (defensive; production wiring always supplies both when {@code manager} is non-null) — a null
   * store resolves the spec bit to {@code false} (mirrors {@code RuntimeSpec.fromSettings(null)}),
   * so an unattached authority never accidentally reports {@code READY}; a null reconciler simply
   * means no spec-change re-derivation is wired (mode-change re-derivation still is).
   */
  public static void attachInferenceModeListener(
      InferenceLifecycleManager manager,
      InferenceCapability inferenceCapability,
      RuntimeSpecStore runtimeSpecStore,
      RuntimeReconciler runtimeReconciler) {
    if (manager == null) {
      return;
    }
    BooleanSupplier chatEnabledSpec =
        runtimeSpecStore == null ? () -> false : () -> runtimeSpecStore.load().chatEnabled();

    // Mirror initial state synchronously BEFORE forwarding transitions (R3 discipline).
    deriveAndApply(
        inferenceCapability,
        manager.getCurrentMode(),
        chatEnabledSpec.getAsBoolean(),
        TransitionReason.UNKNOWN);

    // Tempdoc 837 S5 (§D.2 option c): subscribe to the REASON-bearing listener so an OFFLINE landing
    // can say WHY. The 2-arg ModeChangeListener cannot carry it, and moving TransitionReason into
    // app-api to widen that interface was measured at 18 files against 3 for this.
    manager.addModeTransitionListener(
        (from, to, reason) ->
            deriveAndApply(inferenceCapability, to, chatEnabledSpec.getAsBoolean(), reason));

    if (runtimeReconciler != null) {
      runtimeReconciler.addSpecChangeListener(
          () ->
              deriveAndApply(
                  inferenceCapability,
                  manager.getCurrentMode(),
                  chatEnabledSpec.getAsBoolean(),
                  // A spec flip is an OBSERVATION of a standing mode, not a transition — there is no
                  // reason in hand. UNKNOWN maps to the generic code, and the §D.1 retention rule is
                  // what stops that generic write from erasing a held crash cause.
                  TransitionReason.UNKNOWN));
    }
  }

  /** The single derivation rule (tempdoc 737 §12c item 2), shared by both re-derivation triggers. */
  private static void deriveAndApply(
      InferenceCapability inferenceCapability,
      Mode mode,
      boolean chatEnabledSpec,
      TransitionReason reason) {
    switch (mode) {
      case ONLINE -> {
        if (chatEnabledSpec) {
          inferenceCapability.transition(CapabilityHealth.READY, null);
        } else {
          inferenceCapability.transition(
              CapabilityHealth.DEGRADED, RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND);
        }
      }
      // Tempdoc 837 S5: crash-recovery and user-deactivate both land OFFLINE and are now told apart
      // by the threaded TransitionReason — the one case in fix (a) that genuinely needed a new signal.
      case OFFLINE ->
          inferenceCapability.transition(CapabilityHealth.OFFLINE, offlineCode(reason).code());
      case TRANSITIONING ->
          inferenceCapability.transition(
              CapabilityHealth.RECOVERING, LifecycleReasonCode.INFERENCE_STARTING.code());
      // Tempdoc 837 S4: the GPU went to indexing. Scheduled and self-clearing — the old wording said
      // the model was "offline", which reads as a fault the user must fix.
      case INDEXING ->
          inferenceCapability.transition(
              CapabilityHealth.DEGRADED,
              LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code());
    }
  }

  /**
   * Tempdoc 837 §1.3 — WHY the runtime is OFFLINE, from the reason the transition already carried.
   *
   * <p>Only two reasons name a user-visible truth of their own. Everything else — an auto-start that
   * did not take, a config apply, a VDU enter/exit step, an external detach, app teardown, or no
   * reason at all — is either a transient step of a restart or process shutdown, where the generic
   * code is the honest answer and the FE already words it well.
   */
  private static LifecycleReasonCode offlineCode(TransitionReason reason) {
    if (reason == null) {
      return LifecycleReasonCode.INFERENCE_OFFLINE;
    }
    return switch (reason) {
      // The engine stopped on its own: the periodic-health threshold tripped and recovery forced it
      // down. Nobody chose this, and the remedy is a reload, not a switch.
      case CRASH_RECOVERY -> LifecycleReasonCode.INFERENCE_CRASHED;
      // Somebody chose this. Reporting it as a fault is what trains alarm-blindness.
      case USER_SWITCH, ADMIN_TRIGGERED -> LifecycleReasonCode.INFERENCE_DEACTIVATED;
      case AUTO_START,
          CONFIG_APPLY,
          VDU_ENTER,
          VDU_EXIT,
          EXTERNAL_DETACH,
          SHUTDOWN,
          UNKNOWN -> LifecycleReasonCode.INFERENCE_OFFLINE;
    };
  }
}
