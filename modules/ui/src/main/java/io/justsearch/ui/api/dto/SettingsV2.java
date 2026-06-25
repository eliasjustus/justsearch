/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.dto;

import java.util.List;

/**
 * Canonical v2 settings contract matching the frontend's {@code AppSettings} shape.
 *
 * <p>This is the server-side DTO for {@code GET/POST /api/settings/v2}.
 */
public record SettingsV2(
    UiSettingsV2 ui,
    LlmSettingsV2 llm,
    List<String> indexPaths,
    String settingsMode
) {
  public SettingsV2 {
    // Do not replace nulls with defaults here.
    // Null means "absent from request" — mergeV2Into() uses null checks
    // to decide what to merge. Use SettingsV2.empty() for a default instance.
    indexPaths = indexPaths != null ? List.copyOf(indexPaths) : null;
    // settingsMode is server-set only (ignored in POST body).
  }

  public static SettingsV2 empty() {
    return new SettingsV2(UiSettingsV2.defaults(), LlmSettingsV2.defaults(), List.of(), null);
  }
}
