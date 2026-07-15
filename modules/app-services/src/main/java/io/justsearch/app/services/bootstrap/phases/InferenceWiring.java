/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: GPU-status broadcast wiring + Online Mode auto-start helpers
 * extracted from {@code HeadAssembly}. Bridges InferenceLifecycleManager mode changes
 * to the Worker's MainSignalBus so the Worker can pause/resume GPU-accelerated embeddings
 * when the LLM activates/deactivates.
 */
public final class InferenceWiring {

  private static final Logger log = LoggerFactory.getLogger(InferenceWiring.class);

  private InferenceWiring() {}

  /**
   * Wires GPU status broadcast from {@link InferenceLifecycleManager} to Worker via MMF. Returns
   * the registered listener (so the caller can remove it on shutdown), or null when there's no
   * KnowledgeServerBootstrap or no signal bus.
   */
  public static io.justsearch.app.api.ModeChangeListener wireGpuStatusBroadcast(
      InferenceLifecycleManager manager, KnowledgeServerBootstrap knowledgeServer) {
    if (knowledgeServer == null) {
      log.debug("No KnowledgeServerBootstrap; GPU status broadcast disabled");
      return null;
    }
    var signalBus = knowledgeServer.signalBus();
    if (signalBus == null) {
      log.debug("No MainSignalBus available; GPU status broadcast disabled");
      return null;
    }
    io.justsearch.app.api.ModeChangeListener listener =
        (from, to) -> {
          boolean gpuActive = (to == io.justsearch.app.api.Mode.ONLINE);
          try {
            signalBus.writeGpuActive(gpuActive);
            log.info(
                "GPU status broadcast: {} (mode: {} -> {})",
                gpuActive ? "ACTIVE" : "FREE", from, to);
          } catch (Exception e) {
            log.warn("Failed to broadcast GPU status to Worker", e);
          }
        };
    manager.addModeChangeListener(listener);
    boolean initialGpuActive = manager.isOnline();
    try {
      signalBus.writeGpuActive(initialGpuActive);
      log.debug("Initial GPU status set: {}", initialGpuActive ? "ACTIVE" : "FREE");
    } catch (Exception e) {
      log.warn("Failed to set initial GPU status", e);
    }
    log.info("GPU status broadcast wired to Worker signal bus");
    return listener;
  }

  /**
   * Tempdoc 737 Phase 1: replaces the former {@code tryStartOnlineMode} direct-switch autostart.
   * The env autostart flags now SEED the persisted {@link RuntimeSpecStore} desired-state rather
   * than driving {@code switchToOnlineMode} directly — the {@code RuntimeReconciler} then converges
   * the engine toward spec at boot. This is the env read (allowlisted here per
   * {@code AppServicesWorkerGuardrailsTest}); the actual transition is owned by the reconciler.
   *
   * <p>Semantics preserved: {@code JUSTSEARCH_AI_AUTOSTART_DISABLED=true} is an explicit operator
   * off (no seed); {@code JUSTSEARCH_AI_AUTOSTART_ENABLED=true} seeds {@code chatEnabled=true} only
   * when the user has never persisted an explicit choice (§12a — env at most seeds the spec).
   */
  public static void seedAutostartSpec(RuntimeSpecStore specStore) {
    if (specStore == null) {
      return;
    }
    boolean autoStartEnabled =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.ai.autostart.enabled",
                System.getenv().getOrDefault("JUSTSEARCH_AI_AUTOSTART_ENABLED", "false")));
    boolean autoStartDisabled =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.ai.autostart.disabled",
                System.getenv().getOrDefault("JUSTSEARCH_AI_AUTOSTART_DISABLED", "false")));
    if (autoStartDisabled) {
      log.info(
          "AI auto-start explicitly disabled by operator (JUSTSEARCH_AI_AUTOSTART_DISABLED=true);"
              + " runtime spec not seeded.");
      return;
    }
    if (!autoStartEnabled) {
      log.info(
          "AI auto-start not configured; engine follows the persisted runtime spec. Set"
              + " JUSTSEARCH_AI_AUTOSTART_ENABLED=true to seed chat-on for a fresh profile.");
      return;
    }
    boolean seeded = specStore.seedAutostartIfUnset();
    log.info(
        seeded
            ? "AI auto-start seeded runtime spec chatEnabled=true (fresh profile)."
            : "AI auto-start requested but user already has an explicit chat preference; not overriding.");
  }
}
