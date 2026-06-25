/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import io.justsearch.configuration.Faults;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared launcher environment wiring that aligns with the configured profile and telemetry setup.
 *
 * <p>This helper centralises the profile/property management so both the smoke driver and command
 * handlers execute against the same wiring that the application will use in production.
 */
final class LauncherEnvironment implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(LauncherEnvironment.class);

  private final Path profilePath;
  private final String previousConfigProperty;
  private final String previousEgressProperty;
  private final ConfigManagerBootstrap configManager;
  private final LocalTelemetry telemetry;
  private final HeadAssembly HeadAssembly;
  private static final ConfigManagerFactory DEFAULT_CONFIG_MANAGER_FACTORY =
      ConfigManagerBootstrap::new;
  // Tempdoc 417 Phase 2 + F1 follow-up: register catalogs for every metric the Launcher process
  // emits via the catalog path. Head catalogs (HeadApi/HeadGpu/HeadHttpInflight) live in
  // `app-services/observability` (relocated from `ui` in F1 to satisfy the
  // LayeringEnforcementTest rule) so app-launcher can import their DEFINITIONS without
  // depending on `ui`.
  private static final TelemetryFactory DEFAULT_TELEMETRY_FACTORY =
      (dataDir, profile) ->
          new LocalTelemetry(
              dataDir,
              5_000,
              "justsearch-launcher",
              profile,
              "metrics.ndjson",
              java.util.List.of(
                  // Tempdoc 626 §Axis-A — Head-side file watcher removed; `index.watcher.*` is a
                  // Worker-only metric now (WorkerWatcherMetricCatalog).
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.worker.IpcMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.worker.IpcMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.worker.RagMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.worker.RagMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.vdu.VduMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.vdu.VduMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.agent.AgentMetricCatalog.NAMESPACE,
                      io.justsearch.agent.AgentMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.agent.GenAiMetricCatalog.NAMESPACE,
                      io.justsearch.agent.GenAiMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.observability.HeadApiMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.observability.HeadApiMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog
                          .NAMESPACE,
                      io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog
                          .DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.observability.HeadGpuMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.observability.HeadGpuMetricCatalog
                          .DEFINITIONS),
                  // Tempdoc 412 Phase 4: register the inference metric catalog (matches HeadlessApp).
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.app.services.inference.InferenceMetricCatalog.NAMESPACE,
                      io.justsearch.app.services.inference.InferenceMetricCatalog.DEFINITIONS),
                  // Phase 3d: register the Launcher JVM gauges (separate process from Head /
                  // Worker — its memory/threads do not flow to either of those metric files).
                  io.justsearch.telemetry.JvmMetricCatalog.catalogFor("launcher")));
  private static final AppFacadeFactory DEFAULT_APP_FACADE_FACTORY =
      (telemetry, configManager) ->
          new HeadAssembly(
              telemetry,
              configManager,
              null,
              new io.justsearch.app.services.settings.UiSettingsStore(
                  io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY),
              null);
  private static volatile ConfigManagerFactory configManagerFactory = DEFAULT_CONFIG_MANAGER_FACTORY;
  private static volatile TelemetryFactory telemetryFactory = DEFAULT_TELEMETRY_FACTORY;
  private static volatile AppFacadeFactory appFacadeFactory = DEFAULT_APP_FACADE_FACTORY;

  static LauncherEnvironment create(String profile) throws Exception {
    return new LauncherEnvironment(profile);
  }

  static void installFactories(
      ConfigManagerFactory configFactory,
      TelemetryFactory telemetryFactoryOverride,
      AppFacadeFactory appFacadeFactoryOverride) {
    if (configFactory != null) {
      configManagerFactory = configFactory;
    }
    if (telemetryFactoryOverride != null) {
      telemetryFactory = telemetryFactoryOverride;
    }
    if (appFacadeFactoryOverride != null) {
      appFacadeFactory = appFacadeFactoryOverride;
    }
  }

  static void resetFactories() {
    configManagerFactory = DEFAULT_CONFIG_MANAGER_FACTORY;
    telemetryFactory = DEFAULT_TELEMETRY_FACTORY;
    appFacadeFactory = DEFAULT_APP_FACADE_FACTORY;
  }

  private LauncherEnvironment(String profile) throws Exception {
    this.profilePath = resolveProfilePath(profile);
    this.previousConfigProperty = System.getProperty(EnvRegistry.CONFIG_PATH.sysProp());
    this.previousEgressProperty = System.getProperty("egress.block_all");
    System.setProperty("justsearch.config", profilePath.toString());
    System.setProperty("egress.block_all", "true");
    this.configManager = configManagerFactory.create();
    // Initialize ConfigStore so downstream code (SmokeDriver, etc.) can use ordinal-based resolution.
    ResolvedConfigBuilder rcBuilder = ResolvedConfig.builder();
    rcBuilder.contributeBaseSources();
    ConfigStore.setGlobal(new ConfigStore(rcBuilder.build()));
    this.telemetry = telemetryFactory.create(PlatformPaths.resolveDataDir(), profile);
    // Phase 3d: construct the Launcher JVM catalog so its async gauges are wired to the
    // Launcher's LocalTelemetry registry (matches LocalApiServer ("head") / KnowledgeServer
    // ("worker") wireup pattern).
    io.justsearch.telemetry.JvmRuntimeGauges.register(this.telemetry, "launcher");
    this.HeadAssembly = appFacadeFactory.create(telemetry, configManager);
  }

  @SuppressWarnings("unused") // Called from LauncherEnvironmentCloseTest
  ConfigManagerBootstrap configManager() {
    return configManager;
  }

  @SuppressWarnings("unused") // Called from LauncherEnvironmentCloseTest, SmokeDriverTest
  Telemetry telemetry() {
    return telemetry;
  }

  @SuppressWarnings("unused") // Called from LauncherEnvironmentCloseTest, SmokeDriverTest
  HeadAssembly HeadAssembly() {
    return HeadAssembly;
  }

  Path profilePath() {
    return profilePath;
  }

  @FunctionalInterface
  interface ConfigManagerFactory {
    ConfigManagerBootstrap create() throws Exception;
  }

  @FunctionalInterface
  interface TelemetryFactory {
    LocalTelemetry create(Path dataDir, String profile) throws Exception;
  }

  @FunctionalInterface
  interface AppFacadeFactory {
    HeadAssembly create(LocalTelemetry telemetry, ConfigManagerBootstrap configManager)
        throws Exception;
  }

  private Path resolveProfilePath(String profile) throws IOException {
    Path root = RepoPaths.findRepoRoot();
    Path path = root.resolve("config/profiles").resolve(profile + ".yaml");
    if (!Files.exists(path)) {
      throw new IOException("Profile not found: " + path);
    }
    return path.toAbsolutePath().normalize();
  }

  @Override
  public void close() {
    Faults.debugAndContinue(LOG, "shutdown", () -> HeadAssembly.close());
    telemetry.close();
    if (previousConfigProperty == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", previousConfigProperty);
    }
    if (previousEgressProperty == null) {
      System.clearProperty("egress.block_all");
    } else {
      System.setProperty("egress.block_all", previousEgressProperty);
    }
  }
}
