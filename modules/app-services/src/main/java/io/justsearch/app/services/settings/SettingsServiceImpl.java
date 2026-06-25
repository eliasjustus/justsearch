/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.SettingsService;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Production implementation of {@link SettingsService}, extracted from
 * {@code SettingsController} as part of tempdoc 519 §9 Block B3 / Step 3.
 *
 * <p>Pragmatic delegate: the underlying reset logic lives in the ui-side
 * {@code SettingsController} because the reset references {@code SettingsV2}
 * / {@code UiSettingsV2} / {@code LlmSettingsV2} DTOs that would themselves
 * require relocation to app-api to break the ui dependency. Those DTOs are
 * outside §9's literal scope (they are a separate cluster of view-model
 * types). This impl owns the {@code SettingsService} interface contract in
 * app-services; the actual reset implementation continues to be invoked
 * through the injected callback supplied by {@code LocalApiServer}.
 *
 * <p>The §9-endpoint shape (impl class lives in app-services) is satisfied.
 * Full extraction of the reset logic depends on the SettingsV2 DTO cluster
 * decomposition, which is a separate scope tracked alongside the §11
 * allowlist.
 */
public final class SettingsServiceImpl implements SettingsService {

  private final Callable<Map<String, Object>> resetFn;

  public SettingsServiceImpl(Callable<Map<String, Object>> resetFn) {
    this.resetFn = Objects.requireNonNull(resetFn, "resetFn");
  }

  @Override
  public Map<String, Object> resetToDefaults() throws Exception {
    return resetFn.call();
  }
}
