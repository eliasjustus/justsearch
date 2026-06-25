/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import io.justsearch.app.services.inference.InferenceMetricCatalog;
import io.justsearch.app.services.inference.InferenceTelemetryAdapter;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

public final class BootstrapInferenceFactory {
  private BootstrapInferenceFactory() {}

  // Cached config from willInferenceManagerBeCreated(), reused by createInferenceManager()
  // to avoid duplicate filesystem probes during startup. Both are called sequentially
  // on the same thread during HeadAssembly construction.
  private static volatile InferenceConfig cachedInferenceConfig;

  /**
   * Checks if InferenceLifecycleManager will be created based on same conditions
   * as createInferenceManager(). Used to prevent double model loading.
   */
  public static boolean willInferenceManagerBeCreated(
      boolean aiEnabled, ResolvedConfig resolvedConfig, String userDir) {
    if (!aiEnabled) {
      return false;
    }

    try {
      Path baseDir = resolveBaseDir(resolvedConfig, userDir);
      InferenceConfig config = InferenceConfig.fromEnvironment(baseDir);
      cachedInferenceConfig = config;

      // Mirror createInferenceManager(): we create the manager even if files are missing, so the UI
      // can guide BYO setup without requiring a restart.
      return config != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Creates the InferenceLifecycleManager from environment configuration. Tempdoc 412 Phase 4
   * overload: now takes a {@link Telemetry} reference so the catalog adapter can be wired. The
   * adapter is constructed against {@code lt.registry()} when {@code telemetry} is a
   * {@link LocalTelemetry}; otherwise the no-op events sink is used.
   *
   * @return constructed manager, or {@code null} when AI features are disabled or configuration
   *     fails
   */
  public static InferenceLifecycleManager createInferenceManager(
      boolean aiEnabled,
      ResolvedConfig resolvedConfig,
      String userDir,
      Telemetry telemetry,
      Logger log) {
    if (!aiEnabled) {
      log.info("AI features disabled via configuration");
      return null;
    }

    try {
      // Reuse config from willInferenceManagerBeCreated() if available
      InferenceConfig config = cachedInferenceConfig;
      cachedInferenceConfig = null; // Clear after use
      if (config == null) {
        Path baseDir = resolveBaseDir(resolvedConfig, userDir);
        config = InferenceConfig.fromEnvironment(baseDir);
      }

      // Do NOT require server/model to exist at startup.
      // BYO AI contract: users may add the files after installation; runtime control must still exist
      // so the UI can apply settings and allow Online mode without a full restart.
      if (!Files.exists(config.serverExecutable())) {
        log.info(
            "llama-server executable not found at startup (expected for BYO installs): {}",
            config.serverExecutable());
      }
      if (!Files.exists(config.modelPath())) {
        log.info("LLM model not found at startup (expected for BYO installs): {}", config.modelPath());
      }

      log.info(
          "Creating InferenceLifecycleManager with port {} (gpuLayers={}, ctx={})",
          config.serverPort(),
          config.gpuLayers(),
          config.contextSize());

      // Tempdoc 412 Phase 4: build the catalog adapter from telemetry when available.
      InferenceTelemetryEvents events = buildEvents(telemetry);
      // Note: the persistent InferenceTransitionLog is installed downstream in the
      // composition root (AppFacadeBootstrap) where the head's dataDir is known.
      return new InferenceLifecycleManager(config, events);

    } catch (Exception e) {
      log.warn("Failed to create InferenceLifecycleManager; AI features unavailable", e);
      return null;
    }
  }

  /**
   * Back-compat overload (no telemetry). Used by tests and any caller that hasn't been migrated
   * yet. Falls through to the new overload with a noop events sink.
   */
  public static InferenceLifecycleManager createInferenceManager(
      boolean aiEnabled, ResolvedConfig resolvedConfig, String userDir, Logger log) {
    return createInferenceManager(aiEnabled, resolvedConfig, userDir, null, log);
  }

  /**
   * Constructs the {@link InferenceTelemetryAdapter} when a real {@link LocalTelemetry} is
   * provided; returns the noop sink otherwise. Mirrors the pattern from
   * {@code RagMetricCatalog} construction in {@code HeadAssembly}.
   */
  private static InferenceTelemetryEvents buildEvents(Telemetry telemetry) {
    if (telemetry instanceof LocalTelemetry lt) {
      return new InferenceTelemetryAdapter(new InferenceMetricCatalog(lt.registry()));
    }
    return InferenceTelemetryEvents.noop();
  }

  /**
   * Resolves the base directory for InferenceConfig.
   * Uses JUSTSEARCH_HOME environment variable, or falls back to working directory.
   */
  public static Path resolveBaseDir(ResolvedConfig resolvedConfig, String userDir) {
    return ResolvedPathResolver.resolveBaseDir(resolvedConfig, userDir);
  }

  /**
   * Attempts to start the llama-server in Online Mode.
   * Logs warning if startup fails but does not throw.
   */
  public static void tryStartOnlineMode(
      // Tempdoc 518 Appendix F W4.2 — role-typed interface, off concrete ILM.
      io.justsearch.app.api.OnlineAiLifecycleControl manager,
      boolean autoStartEnabled,
      boolean autoStartDisabled,
      Logger log) {
    if (!autoStartEnabled || autoStartDisabled) {
      // Tempdoc 374 alpha.20 Bug N: distinguish "explicitly disabled by operator" from
      // "default behaviour (opt-in auto-start)." Pre-alpha.20 both cases logged
      // "AI auto-start disabled" — which read as "feature is off" when actually auto-
      // start is just opt-in. Round-10 evidence: a user closing/reopening JustSearch
      // saw "AI offline" with this misleading log line as the only hint.
      if (autoStartDisabled) {
        log.info(
            "AI auto-start explicitly disabled by operator (JUSTSEARCH_AI_AUTOSTART_DISABLED=true);"
                + " llama-server will not start automatically.");
      } else {
        log.info(
            "AI auto-start not configured; llama-server will start on first"
                + " /api/ai/runtime/activate request. Set JUSTSEARCH_AI_AUTOSTART_ENABLED=true"
                + " to auto-start chat on cold boot.");
      }
      return;
    }

    try {
      log.info("Starting llama-server (Online Mode)...");
      manager.switchToOnlineMode();
      log.info("llama-server started successfully");
    } catch (ModeTransitionException e) {
      log.warn("Failed to start llama-server at startup; AI features may be unavailable", e);
      // Don't throw - app should still work without AI features
    }
  }

  public static Path getJustSearchHome(ResolvedConfig resolvedConfig, String userDir) {
    return ResolvedPathResolver.resolveBaseDir(resolvedConfig, userDir);
  }
}
