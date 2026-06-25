/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.app.observability.InfraHealthGrpcService;
import io.justsearch.infra.health.InfraHealthAggregator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: small static helpers extracted from {@code HeadAssembly} that
 * have no instance state. Grouped here to reduce bootstrap NCSS without spawning a separate
 * file per 5-line helper.
 */
public final class BootstrapHelpers {

  private static final Logger log = LoggerFactory.getLogger(BootstrapHelpers.class);

  private BootstrapHelpers() {}

  /** Log the AI services configuration at startup for debuggability. */
  public static void logAiServicesConfiguration(
      OnlineAiService onlineAiService,
      InferenceLifecycleManager inferenceManager,
      RemoteKnowledgeClient knowledgeClient,
      AgentService agentService) {
    log.info("=== AI Services Configuration ===");
    log.info("  OnlineAiService: {}", onlineAiService.getClass().getSimpleName());
    log.info("  InferenceManager: {}", inferenceManager != null ? "ACTIVE" : "DISABLED");
    log.info("  KnowledgeClient: {}", knowledgeClient != null ? "CONNECTED" : "UNAVAILABLE");
    if (inferenceManager != null) {
      log.info("  LLM Mode: {}", inferenceManager.getCurrentMode());
    }
    boolean isOnlineAiUnavailable = inferenceManager == null;
    log.info("  Endpoint Behavior:");
    log.info(
        "    /api/chat/batch-summarize: {} ({})",
        onlineAiService.getClass().getSimpleName(),
        isOnlineAiUnavailable ? "unavailable" : "active with llama-server");
    log.info(
        "  AgentService: {} ({} tools)",
        agentService.isAvailable() ? "AVAILABLE" : "UNAVAILABLE",
        agentService.availableOperations().size());
    log.info("=================================");
  }

  /** Build the {@link InfraHealthAggregator.Config} from a {@link ResolvedConfig.InfraHealth}. */
  public static InfraHealthAggregator.Config toInfraHealthConfig(ResolvedConfig.InfraHealth ih) {
    return new InfraHealthAggregator.Config(
        Duration.ofMillis(ih.pollIntervalMs()),
        Duration.ofMillis(ih.nrtStaleMs()),
        Duration.ofMillis(ih.translatorHandshakeStaleMs()),
        ih.annCacheReadyPercent());
  }

  /**
   * Start the infra-health gRPC server. Returns null when disabled by override, or when service
   * / config are null. Throws {@link IllegalStateException} on bind failure.
   */
  public static Server startInfraHealthGrpcServer(
      InfraHealthGrpcService service, ResolvedConfig.InfraGrpc grpcCfg) {
    boolean disable =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.infra.health.grpc.disable",
                System.getenv().getOrDefault("JUSTSEARCH_INFRA_HEALTH_GRPC_DISABLE", "false")));
    if (disable) {
      log.info("Infra health gRPC server disabled via override");
      return null;
    }
    if (service == null || grpcCfg == null) {
      return null;
    }
    try {
      Server server =
          NettyServerBuilder.forAddress(new InetSocketAddress(grpcCfg.host(), grpcCfg.port()))
              .addService(service)
              .build()
              .start();
      log.info("Infra health gRPC endpoint listening on {}:{}", grpcCfg.host(), server.getPort());
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start infra health gRPC server", e);
    }
  }

  /** Load the registry-operation i18n properties file from the classpath. */
  public static Properties loadRegistryOperationMessages() {
    Properties p = new Properties();
    try (InputStream is =
            BootstrapHelpers.class.getResourceAsStream("/messages/registry-operation.en.properties");
        InputStreamReader r =
            new InputStreamReader(
                Objects.requireNonNull(is, "registry-operation.en.properties not on classpath"),
                StandardCharsets.UTF_8)) {
      p.load(r);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load registry-operation messages", e);
    }
    return p;
  }

  /** Initial {@link RuntimeContext} read from system property (eval mode) + ConfigStore. */
  public static RuntimeContext initialRuntimeContext() {
    io.justsearch.app.observability.runtime.SystemMode systemMode =
        Boolean.getBoolean("justsearch.eval.mode")
            ? io.justsearch.app.observability.runtime.SystemMode.EVAL
            : io.justsearch.app.observability.runtime.SystemMode.PRODUCTION;
    ResolvedConfig rc = currentResolvedConfig();
    boolean automationEnabled = rc != null && rc.ui().automationEnabled();
    return new RuntimeContext(systemMode, automationEnabled);
  }

  /**
   * Resolve the occurrence-log ring buffer size from system property /
   * JUSTSEARCH_HEALTH_OCCURRENCE_BUFFER env var. Falls back to
   * {@code OccurrenceLog.DEFAULT_CAPACITY} on null/blank/non-positive/invalid input.
   */
  public static int resolveOccurrenceBufferSize() {
    String raw =
        System.getProperty(
            "justsearch.health.occurrence.buffer",
            System.getenv("JUSTSEARCH_HEALTH_OCCURRENCE_BUFFER"));
    if (raw == null || raw.isBlank()) {
      return io.justsearch.app.observability.health.OccurrenceLog.DEFAULT_CAPACITY;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed <= 0) {
        return io.justsearch.app.observability.health.OccurrenceLog.DEFAULT_CAPACITY;
      }
      return parsed;
    } catch (NumberFormatException e) {
      return io.justsearch.app.observability.health.OccurrenceLog.DEFAULT_CAPACITY;
    }
  }

  /**
   * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly.configureAutomationDiagnostics}.
   * Applies the diagnostics override sliders ({@code nrtLag=15_000ms},
   * {@code translatorHandshake=5min ago}, {@code annReady=5}) when automation + force-diagnostics
   * are both enabled in config. Returns true when the overrides were applied.
   */
  public static boolean configureAutomationDiagnostics(
      io.justsearch.app.observability.InfraDiagnosticsService diagnosticsService) {
    ResolvedConfig rc = currentResolvedConfig();
    if (rc == null) return false;
    boolean automationEnabled = rc.ui().automationEnabled();
    if (!automationEnabled) return false;
    boolean forceDiagnostics = rc.ui().forceDiagnostics();
    if (!forceDiagnostics) {
      log.info("Automation diagnostics overrides disabled via automation flag.");
      return false;
    }
    log.info(
        "Automation diagnostics overrides enabled (simulating degraded translator + cold ANN cache).");
    diagnosticsService.setNrtLagSupplier(() -> 15_000L);
    diagnosticsService.setTranslatorHandshakeSupplier(
        () -> java.time.Instant.now().minus(Duration.ofMinutes(5)));
    diagnosticsService.setAnnReadySupplier(() -> 5);
    return true;
  }

  /** First non-blank value from the supplied list, or null if none. */
  public static String chooseFirstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  /** Resolve the JustSearch home directory (CWD by default). */
  public static Path getJustSearchHome() {
    return io.justsearch.app.services.bootstrap.BootstrapInferenceFactory.getJustSearchHome(
        currentResolvedConfig(), System.getProperty("user.dir"));
  }

  /** Get the current {@link ResolvedConfig} from {@link ConfigStore#globalOrNull()}, or null. */
  public static ResolvedConfig currentResolvedConfig() {
    ConfigStore store = ConfigStore.globalOrNull();
    return store != null ? store.get() : null;
  }
}
