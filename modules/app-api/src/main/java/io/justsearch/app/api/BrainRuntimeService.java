/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Brain inference runtime control surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainRuntimeSection cluster): backs
 * {@code core.reload-inference} (apply persisted settings to the running
 * inference runtime) and {@code core.switch-inference-mode} (online/indexing
 * toggle). Production wiring: {@code InferenceHandlers} (modules/ui)
 * implements this interface; {@code LocalApiServer} late-binds it onto
 * {@code HeadAssembly} after both are constructed.
 *
 * <p>Why a dedicated service interface rather than extending
 * {@link OnlineAiService}: reloadInference depends on the
 * {@code UiSettingsStore} (modules/ui-side persistence) to read the model
 * path / context length / GPU layers before invoking
 * {@code OnlineAiRuntimeControl.applyRuntimeOverrides}. Pulling that into
 * {@code OnlineAiServiceImpl} would couple inference to settings; the
 * BrainRuntimeService wrapper keeps that coupling at the controller layer
 * where it already exists.
 *
 * <p>Stability: stable (API contract).
 */
public interface BrainRuntimeService {

  /**
   * Apply persisted settings (model path, context length, GPU layers) to the
   * running inference runtime.
   *
   * <p>Must NOT auto-start llama-server when offline — only restarts the
   * server when already in ONLINE mode. Mirrors the pre-existing
   * {@code POST /api/inference/reload} contract bit-identically.
   *
   * @return the post-apply current mode (e.g., {@code "online"},
   *     {@code "indexing"}, {@code "offline"}) for the caller to surface
   * @throws Exception on apply failure (invalid config, missing model,
   *     subprocess restart failure)
   */
  String reloadInference() throws Exception;

  /**
   * Switch the inference mode (online/indexing). Validates the mode string
   * (must be {@code "online"} or {@code "indexing"} — case-insensitive),
   * checks enterprise policy for online mode, and delegates to
   * {@link OnlineAiService#switchToOnlineMode()} or
   * {@link OnlineAiService#switchToIndexingMode()}.
   *
   * @param mode {@code "online"} or {@code "indexing"} (case-insensitive)
   * @return the post-switch current mode
   * @throws IllegalArgumentException for an invalid mode string
   * @throws Exception on transition failure (policy denied, insufficient
   *     VRAM, invalid config)
   */
  String switchInferenceMode(String mode) throws Exception;

  /**
   * Trigger background offline processing (VDU + embeddings catch-up). Runs
   * asynchronously on a virtual thread; returns immediately after dispatch.
   *
   * @throws UnsupportedOperationException if the offline-processing trigger
   *     isn't configured (test paths, headless modes that don't enable it)
   * @throws Exception on dispatch failure
   */
  void triggerOfflineProcessing() throws Exception;

}
