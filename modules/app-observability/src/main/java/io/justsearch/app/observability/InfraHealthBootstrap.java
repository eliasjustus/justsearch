/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.infra.health.InfraHealthAggregator;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Helper that connects configuration updates to the diagnostics service. */
public final class InfraHealthBootstrap {
  private final InfraDiagnosticsService diagnosticsService;

  public InfraHealthBootstrap(InfraDiagnosticsService diagnosticsService) {
    this.diagnosticsService = Objects.requireNonNull(diagnosticsService, "diagnosticsService");
  }

  /** Binds the diagnostics service to configuration updates. */
  public void bindConfigManager(ConfigManagerBootstrap configManager) {
    Objects.requireNonNull(configManager, "configManager");
    configManager.registerListener(snapshot -> updateFromConfigStore(), true);
  }

  /** Overrides the supplier that indicates whether the configuration is valid. */
  public void bindConfigValidity(BooleanSupplier supplier) {
    diagnosticsService.setConfigValidSupplier(supplier);
  }

  public InfraDiagnosticsService diagnosticsService() {
    return diagnosticsService;
  }

  private void updateFromConfigStore() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs == null) return;
    ResolvedConfig.InfraHealth ih = cs.get().infraHealth();
    diagnosticsService.updateConfig(
        new InfraHealthAggregator.Config(
            Duration.ofMillis(ih.pollIntervalMs()),
            Duration.ofMillis(ih.nrtStaleMs()),
            Duration.ofMillis(ih.translatorHandshakeStaleMs()),
            ih.annCacheReadyPercent()));
  }
}
