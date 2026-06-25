/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.dto;

/**
 * UI-only settings in the v2 canonical contract.
 *
 * <p>Matches the frontend's {@code UISettings} interface.
 */
public record UiSettingsV2(
    String theme,
    Boolean highContrast,
    String density,
    Boolean vimMode,
    String defaultAction,
    Integer inspectorWidth,
    Boolean pauseIndexingDuringAi,
    String mode,
    Boolean hasSeenTrustLoopNudge,
    java.util.List<String> excludePatterns
) {
  public static UiSettingsV2 defaults() {
    return new UiSettingsV2("system", false, "comfort", false, "open", null, false, "simple", false, java.util.List.of());
  }
}
