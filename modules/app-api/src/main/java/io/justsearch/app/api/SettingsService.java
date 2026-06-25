/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * Settings mutation surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (Settings reset cluster): backs
 * {@code core.reset-settings}. Production wiring:
 * {@code SettingsController} implements this interface;
 * {@code LocalApiServer} late-binds it onto {@code HeadAssembly}.
 *
 * <p>Read-side settings state (GET /api/settings/v2) is intentionally NOT
 * on this interface — that's Resource-primitive territory per ADR-09. This
 * interface is action-only.
 *
 * <p>Architectural note: backend-canonical-defaults for FE-controlled
 * fields. The reset Operation resets the user-facing toggles (theme,
 * density, mode, defaultAction, pauseIndexingDuringAi,
 * hasSeenTrustLoopNudge, excludePatterns, contextWindow, maxTokens,
 * gpuLayers, etc.) to their schema-canonical default values, while
 * preserving admin-set fields (server executable path, model path,
 * llama lib path, index base path, schemaVersion) so a user-triggered
 * "reset to defaults" doesn't undo operator/admin configuration.
 *
 * <p>Stability: stable (API contract).
 */
public interface SettingsService {

  /**
   * Reset FE-controlled settings to their canonical default values.
   * Preserves admin-set fields (server exe, model path, llama lib path,
   * index base path, schema version, splits/window geometry).
   *
   * @return result map mirroring the SettingsV2 wire shape so the FE
   *     consumer can refresh its store from the response without an
   *     extra GET.
   * @throws Exception when the settings store is read-only (in_memory
   *     mode) or persistence fails
   */
  Map<String, Object> resetToDefaults() throws Exception;

}
