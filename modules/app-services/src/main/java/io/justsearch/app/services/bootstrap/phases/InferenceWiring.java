/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
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
   * Attempts to start the llama-server in Online Mode by delegating to
   * {@link BootstrapInferenceFactory#tryStartOnlineMode}. Logs warnings on failure but does
   * not throw. Reads the auto-start flags from system properties + environment variables.
   */
  public static void tryStartOnlineMode(InferenceLifecycleManager manager) {
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
    BootstrapInferenceFactory.tryStartOnlineMode(manager, autoStartEnabled, autoStartDisabled, log);
  }
}
