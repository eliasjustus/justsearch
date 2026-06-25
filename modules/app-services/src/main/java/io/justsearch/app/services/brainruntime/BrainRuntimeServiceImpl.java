/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.brainruntime;

import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production implementation of {@link BrainRuntimeService}, extracted from
 * {@code InferenceHandlers} as part of tempdoc 519 §9 Block B3 / Step 3.
 */
public final class BrainRuntimeServiceImpl implements BrainRuntimeService {

  private static final Logger log = LoggerFactory.getLogger(BrainRuntimeServiceImpl.class);

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  private final EnterprisePolicyService enterprisePolicyService;
  private final Runnable offlineProcessingTrigger;

  public BrainRuntimeServiceImpl(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      EnterprisePolicyService enterprisePolicyService,
      Runnable offlineProcessingTrigger) {
    this.onlineAi = onlineAi;
    this.settingsStore = settingsStore;
    this.enterprisePolicyService = enterprisePolicyService;
    this.offlineProcessingTrigger = offlineProcessingTrigger;
  }

  @Override
  public String reloadInference() throws Exception {
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      throw new IllegalStateException("Inference runtime control unavailable");
    }
    if (settingsStore == null) {
      throw new IllegalStateException("Settings store unavailable");
    }
    if (enterprisePolicyService != null) {
      try {
        enterprisePolicyService.snapshot();
      } catch (Exception ignored) {
        // best-effort; do not fail reload on policy snapshot errors
      }
    }
    UiSettings s = settingsStore.load();
    control.applyRuntimeOverrides(
        s.getLlmModelPath(),
        s.getContextLength(),
        s.getGpuLayers(),
        OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);
    return onlineAi.getCurrentMode();
  }

  @Override
  public void triggerOfflineProcessing() throws Exception {
    if (offlineProcessingTrigger == null) {
      throw new UnsupportedOperationException("Offline processing not available");
    }
    log.info("Triggering offline processing (VDU + Embeddings)");
    Thread.ofVirtual().name("offline-processing").start(offlineProcessingTrigger);
  }

  @Override
  public String switchInferenceMode(String mode) throws Exception {
    if (mode == null || mode.isBlank()) {
      throw new IllegalArgumentException("Missing 'mode' field");
    }
    if ("online".equalsIgnoreCase(mode)) {
      if (enterprisePolicyService != null) {
        boolean denied = false;
        try {
          denied = !enterprisePolicyService.snapshot().onlineAiEnabled();
        } catch (Exception ignored) {
          // best-effort
        }
        if (denied) {
          throw new IllegalStateException("Online AI is disabled by administrator policy.");
        }
      }
      onlineAi.switchToOnlineMode();
    } else if ("indexing".equalsIgnoreCase(mode)) {
      onlineAi.switchToIndexingMode();
    } else {
      throw new IllegalArgumentException("Invalid mode. Use 'online' or 'indexing'");
    }
    return onlineAi.getCurrentMode();
  }
}
