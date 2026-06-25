/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerHealthMonitor;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.SystemPropertyUtils;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.contracts.BootContractRunner;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.ui.api.LocalApiServer;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.api.UiSettings;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Headless entry point for the JustSearch application.
 *
 * <p>Designed to be run as a sidecar process by Tauri. It initializes the backend and the Local API
 * Server, but does not start JavaFX.
 */
public class HeadlessApp {
  private static final Logger log = LoggerFactory.getLogger(HeadlessApp.class);
  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

  // Tempdoc 502 §3.3: Typed phase outputs. Each record captures the outputs of one boot phase,
  // enabling independent testing of each phase.
  record ConfigPhaseResult(
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      UiSettings initialSettings,
      ResolvedConfig resolvedConfig,
      ConfigStore configStore,
      Path dataDir) {}

  record InfraPhaseResult(
      ConfigPhaseResult config,
      Telemetry telemetry,
      // Tempdoc 518 Appendix G W4.2 — present when HEAD_TRACING_LEVEL is non-none.
      io.justsearch.telemetry.TracingBootstrap tracingBootstrap) {}

  record ApiPhaseResult(
      HeadAssembly bootstrap,
      LocalApiServer apiServer,
      int port,
      String sessionToken) {}

  record WorkerConnectionResult(
      KnowledgeServerBootstrap knowledgeServer,
      String startError,
      KnowledgeServerHealthMonitor healthMonitor) {}

  /**
   * Application-wide ConfigStore. Initialized during startup before downstream components are
   * created. Published globally via {@link ConfigStore#setGlobal(ConfigStore)}.
   */
  private static volatile ConfigStore configStore;

  // Tempdoc 519 §9 Block B3.0.e: contributeUiSettings moved to
  // io.justsearch.app.services.config.ConfigStoreRebuilder so it lives in
  // the same place as ConfigStoreRebuilder.rebuild (which also calls it).
  // HeadlessApp's L539 callsite now invokes
  // ConfigStoreRebuilder.contributeUiSettings(rcBuilder, settings) directly.

  /**
   * Tempdoc 374 alpha.13 follow-up Phases E + F: bridges the chasm between
   * "GPU is detected" and "GPU is used" that left default installs on CPU
   * even when {@code GpuAutoDetection.probe} correctly reported CUDA.
   *
   * <p>Two operations, both predicated on user override absence:
   *
   * <ol>
   *   <li><b>Phase E — sysprop-mirror.</b> Each entry written by the probe
   *       (e.g. {@code justsearch.gpu.enabled = "true"}) is also set as a
   *       system property, but only when {@code EnvRegistry.<key>.get()} is
   *       empty (no user sysprop or env var override exists). This makes the
   *       value (a) survive {@link io.justsearch.app.services.config.ConfigStoreRebuilder#rebuild}
   *       (which only re-contributes sysprops via env-registry, not the
   *       transient ord-150 autoDetected map), and (b) propagate to the
   *       worker subprocess via {@code WORKER_FORWARDED_PROPS} —
   *       {@code GPU_ENABLED}, {@code ORT_NATIVE_PATH}, {@code GPU_LAYERS}
   *       are all in that list.
   *   <li><b>Phase F — VRAM-tier auto-populate of gpu_layers.</b> If "GPU
   *       should be used" (probe said true AND user didn't explicitly say
   *       false) AND no explicit {@code gpu.layers} / {@code llm.gpu_layers}
   *       is set, query NVML for total VRAM. If &ge; 7.5&nbsp;GB (matches
   *       {@link io.justsearch.configuration.model.HardwareProfile#MINIMUM_VRAM_FOR_GGUF}),
   *       set both {@code justsearch.gpu.layers} and
   *       {@code justsearch.llm.gpu_layers} to {@code "99"} (full offload —
   *       Qwen3.5-9B Q4_K_M is ~5.5&nbsp;GB, fits comfortably). Below
   *       threshold, leave at 0; the chat model wouldn't fit anyway and
   *       partial offload is an OOM hazard. The user can still force layers
   *       via env var.
   * </ol>
   *
   * <p>The augmented map is also returned so the caller can pass it to
   * {@link ResolvedConfigBuilder#contributeAutoDetected} — keeping the
   * ord-150 contribution and the sysprop write in lockstep.
   *
   * <p>Package-private + parameterized {@code totalVramSupplier} so tests
   * can pin behavior across the four boundary cases (autoDetected GPU + 12 GB
   * VRAM, idempotent re-run, user-disabled GPU, below-threshold VRAM)
   * without spawning NVML.
   */
  static Map<String, String> augmentGpuAutoDetectionAndMirror(
      Map<String, String> autoDetected, LongSupplier totalVramSupplier) {
    if (autoDetected == null || autoDetected.isEmpty()) {
      return autoDetected == null ? Map.of() : autoDetected;
    }
    java.util.LinkedHashMap<String, String> augmented = new java.util.LinkedHashMap<>(autoDetected);

    // Phase E: sysprop-mirror autoDetected entries (gpu.enabled, ort native_path).
    for (Map.Entry<String, String> entry : autoDetected.entrySet()) {
      String key = entry.getKey();
      EnvRegistry envKey = lookupEnvRegistryBySysProp(key);
      Optional<String> userOverride = envKey != null ? envKey.get() : Optional.empty();
      if (userOverride.isPresent()) {
        // User has an explicit sysprop or env var — respect it; don't mirror.
        continue;
      }
      SystemPropertyUtils.setSysPropIfBlank(key, entry.getValue());
    }

    // Phase F: VRAM-tier auto-populate gpu_layers when GPU should be used.
    if (shouldUseGpu(augmented)) {
      boolean alreadySet = EnvRegistry.GPU_LAYERS.get().isPresent()
          || EnvRegistry.LLM_GPU_LAYERS.get().isPresent();
      if (!alreadySet) {
        long vramBytes = -1;
        try {
          vramBytes = totalVramSupplier.getAsLong();
        } catch (Throwable t) {
          log.debug("VRAM auto-populate: NVML probe failed (best-effort): {}", t.getMessage());
        }
        if (vramBytes
            >= io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF) {
          String layers = "99";
          augmented.put("justsearch.gpu.layers", layers);
          SystemPropertyUtils.setSysPropIfBlank("justsearch.gpu.layers", layers);
          SystemPropertyUtils.setSysPropIfBlank("justsearch.llm.gpu_layers", layers);
          log.info(
              "VRAM auto-populate: gpu.layers={} (vramBytes={}, threshold={})",
              layers,
              vramBytes,
              io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF);
        } else {
          log.info(
              "VRAM auto-populate: skipped — vramBytes={} below threshold {} (Qwen3.5-9B Q4_K_M"
                  + " ~5.5 GB wouldn't fit safely)",
              vramBytes,
              io.justsearch.configuration.model.HardwareProfile.MINIMUM_VRAM_FOR_GGUF);
        }
      }

      // Tempdoc 374 alpha.16 fix D (defensive backstop): per-encoder gpu.enabled
      // sysprop-mirror when shouldUseGpu and no user override exists. The round-6
      // sandbox agent observed embed/splade/ner gpuEnabled=false at the worker even
      // though master justsearch.gpu.enabled=true was in the snapshot — the
      // master-fallback chain in ResolvedConfigBuilder.resolveEmbedGpuEnabled looks
      // correct from a static read but isn't producing the expected value at the
      // worker. Sysprop-mirroring at boot makes the per-feature value explicit at
      // ordinal 500, bypassing whatever resolution path is dropping the master
      // fallback. Root-cause investigation (D1+D2 in tempdoc 374) deferred to
      // alpha.17; this is the defensive backstop (D3).
      //
      // Reranker has its own EnvRegistry default true so doesn't need mirroring.
      // BgeM3 isn't loaded in the current encoder set; skip.
      mirrorPerEncoderGpuEnabled(EnvRegistry.EMBED_GPU_ENABLED);
      mirrorPerEncoderGpuEnabled(EnvRegistry.SPLADE_GPU_ENABLED);
      mirrorPerEncoderGpuEnabled(EnvRegistry.NER_GPU_ENABLED);
    }

    return augmented;
  }

  /**
   * Tempdoc 374 alpha.16 fix D (defensive backstop): if no user override is set
   * for the given per-encoder GPU enable key, mirror {@code "true"} as a sysprop
   * so the worker resolves the explicit value rather than relying on the master
   * fallback (which empirical evidence shows isn't producing the expected result).
   */
  private static void mirrorPerEncoderGpuEnabled(EnvRegistry key) {
    if (key.get().isPresent()) {
      // User has an explicit value at sysprop or env var — respect it.
      return;
    }
    SystemPropertyUtils.setSysPropIfBlank(key.sysProp(), "true");
    log.debug("alpha.16 fix D: mirrored {} = true (per-encoder GPU defensive backstop)",
        key.sysProp());
  }

  /**
   * Returns true iff {@code shouldUseGpu = userOverride.isPresent()
   *   ? Boolean.parseBoolean(userOverride.get()) : autoDetectedSaysTrue}.
   * Captures the rule that an explicit user disable (env / sysprop
   * {@code JUSTSEARCH_GPU_ENABLED=false}) wins over the auto-detect.
   */
  private static boolean shouldUseGpu(Map<String, String> autoDetected) {
    Optional<String> userOverride = EnvRegistry.GPU_ENABLED.get();
    if (userOverride.isPresent()) {
      return Boolean.parseBoolean(userOverride.get().trim());
    }
    return "true".equalsIgnoreCase(autoDetected.getOrDefault("justsearch.gpu.enabled", ""));
  }

  /**
   * Reverse-lookup an EnvRegistry entry by its sysprop key. Returns null if no
   * matching entry exists (e.g. a probe key that isn't an EnvRegistry-managed
   * config — currently {@code justsearch.gpu.enabled} and
   * {@code justsearch.onnxruntime.native_path} are managed; future probe keys
   * may not be).
   */
  private static EnvRegistry lookupEnvRegistryBySysProp(String sysPropKey) {
    for (EnvRegistry reg : EnvRegistry.values()) {
      if (reg.sysProp().equals(sysPropKey)) {
        return reg;
      }
    }
    return null;
  }

  /**
   * Tempdoc 374 alpha.16 fix B: at boot, if the cuda12 variant dir exists with the CUDA
   * runtime DLLs and the user hasn't set {@code justsearch.onnxruntime.native_path}
   * explicitly, set the sysprop pointing at that dir. Mirrors the in-cycle write in
   * {@link io.justsearch.ui.ai.install.AiInstallService#applyOrtNativePath} (alpha.14
   * fix B), but at boot time so the value survives restarts.
   *
   * <p>Without this, after a user restarts JustSearch following a successful Install AI:
   * the chat path picks up via {@code maybeAutoSelectCuda12Variant} but the worker spawns
   * with no ORT native_path, ORT can't find cuBLASLt + cuFFT + cuDNN at LoadLibrary time,
   * and all 4 ONNX encoders fall back to CPU even though the runtime DLLs are right
   * there in {@code <homeDir>/native-bin/llama-server/variants/cuda12/}.
   *
   * <p>The home directory is resolved via {@link PlatformPaths#resolveDataDir()} —
   * matches the same source {@code AiInstallService.resolveHomeDir} uses to write
   * the cuda12 dir during Install AI, so this read paired with that write produces a
   * matching path. (An earlier draft used {@code cs.get().paths().home()}, but that
   * resolves the {@code justsearch.home} sysprop which is null in production unless
   * the user explicitly set it.)
   */

  private static InfraPhaseResult setupInfra(ConfigPhaseResult configPhase) {
    System.setProperty("justsearch.infra.health.grpc.disable", "true");
    System.setProperty("justsearch.index.parity.allow_mismatch", "true");
    System.setProperty("justsearch.infra.health.port", "0");

    Path dataDir = configPhase.dataDir();
    harmonizeDataDirProperties(dataDir);
    log.info("Using data directory: {}", dataDir);

    Telemetry telemetry = new LocalTelemetry(
        dataDir, 5_000, "justsearch-headless", "phase3", "metrics.ndjson",
        List.of(
            // Tempdoc 626 §Axis-A — the Head-side file watcher was removed; the `index.watcher.*`
            // metric is emitted only by the Worker (WorkerWatcherMetricCatalog), so the Head no
            // longer registers it.
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.observability.HeadApiMetricCatalog.NAMESPACE,
                io.justsearch.app.services.observability.HeadApiMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog.NAMESPACE,
                io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.catalog.MetricCatalog.of(
                io.justsearch.app.services.observability.HeadGpuMetricCatalog.NAMESPACE,
                io.justsearch.app.services.observability.HeadGpuMetricCatalog.DEFINITIONS),
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
                io.justsearch.app.services.inference.InferenceMetricCatalog.NAMESPACE,
                io.justsearch.app.services.inference.InferenceMetricCatalog.DEFINITIONS),
            io.justsearch.telemetry.JvmMetricCatalog.catalogFor("head")));

    try {
      new EnterprisePolicyServiceImpl().snapshot();
    } catch (Exception ignored) {
      // best-effort
    }

    // Tempdoc 518 Appendix G W4.2 — initialize head-side OTel tracing. Mirrors the worker
    // pattern at KnowledgeServer.java:335-347. Gated on HEAD_TRACING_LEVEL; default "none"
    // means GlobalOpenTelemetry stays no-op and the existing head-side span-authoring sites
    // (AgentLoopService, KnowledgeHttpApiAdapter) emit into the void as before. When
    // non-none, the spans get exported AND carry the justsearch.inference.generation
    // attribute via W2.2's InferenceGenerationSpanProcessor.
    io.justsearch.telemetry.TracingBootstrap tracingBootstrap = null;
    String headTracingLevel = EnvRegistry.HEAD_TRACING_LEVEL
        .getString("none").toLowerCase(java.util.Locale.ROOT);
    if (!"none".equals(headTracingLevel)) {
      try {
        tracingBootstrap = io.justsearch.telemetry.TracingBootstrap.forHead(
            dataDir,
            telemetry instanceof LocalTelemetry lt ? lt.getHealthState() : null,
            headTracingLevel);
        log.info("Head tracing initialized: level={}", headTracingLevel);
      } catch (IllegalStateException e) {
        log.debug("GlobalOpenTelemetry already set, skipping head TracingBootstrap: {}", e.getMessage());
      }
    }

    return new InfraPhaseResult(configPhase, telemetry, tracingBootstrap);
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private static ApiPhaseResult buildApi(
      InfraPhaseResult infraPhase,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      RuntimeManifestPublisher manifestPublisher,
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability)
      throws Exception {
    Telemetry telemetry = infraPhase.telemetry();
    ResolvedConfig resolvedConfig = infraPhase.config().resolvedConfig();

    HeadAssembly bootstrap =
        new HeadAssembly(
            telemetry, new ConfigManagerBootstrap(), null, settingsStore, sharedWorkerCapability);
    log.info("HeadAssembly started (degraded — Worker connecting in background).");

    var headInfra = bootstrap.headInfraRegistry();
    io.justsearch.app.services.vdu.OfflineCoordinator coordinator = headInfra.offlineCoordinator();
    Runnable offlineTrigger =
        coordinator != null ? coordinator::startOfflineProcessing : null;
    GplStatusProvider gplCoordinator = headInfra.gplJobCoordinator();
    tools.jackson.databind.JsonNode configRoot =
        io.justsearch.configuration.JustSearchConfigurationLoader.loadYamlRoot().orElse(null);
    Path indexBasePath = resolvedConfig.paths().indexBasePath();

    BootContractRunner.validateAll();

    boolean prodMode = configStore.get().policy().prodMode();
    String sessionToken = prodMode ? LocalApiServer.generateSessionToken() : null;

    Path userHome = Path.of(System.getProperty("user.home", ""));
    LocalApiServer apiServer =
        LocalApiServer.builder(settingsStore, indexBasePath)
            .HeadAssembly(bootstrap)
            .knowledgeServer(null)
            .configRoot(configRoot)
            .offlineProcessingTrigger(offlineTrigger)
            .knowledgeServerStartError(null)
            .telemetry(telemetry)
            .sessionToken(sessionToken)
            .userHome(userHome.toString().isEmpty() ? null : userHome)
            .workerFeatureCache(bootstrap.workerFeatureCache())
            .gplJobCoordinator(gplCoordinator)
            .lambdaMartReranker(headInfra.lambdaMartReranker())
            .gplEvalSnapshotSupplier(headInfra.gplEvalSnapshotSupplier())
            .HeadAssembly(bootstrap)
            .runtimeManifestPublisher(manifestPublisher)
            .build();
    int port = apiServer.getPort();

    emitPortSignals(port, sessionToken, prodMode);

    return new ApiPhaseResult(bootstrap, apiServer, port, sessionToken);
  }

  @SuppressWarnings("PMD.SystemPrintln")
  private static void emitPortSignals(
      int port, String sessionToken, boolean prodMode) {
    // Tempdoc 501 Phase 18: the api-port.txt mirror is gone. The full runtime
    // manifest at <dataDir>/runtime/manifest.json (written by RuntimeManifestPublisher)
    // is now the canonical filesystem transport. Every known consumer
    // (Vite proxy, dev-runner, prod MCP, IsolatedBackendFixture integration
    // tests, ui module's sidecar smoke) reads the manifest directly.
    //
    // The stdout JUSTSEARCH_API_PORT=<port> line below remains as human-readable
    // log output; no tool parses it for discovery (Phase 8 removed the parse-to-
    // state paths from the Tauri shell and dev-runner).
    log.info(
        "Preparing stdout signals: prodMode={}, tokenGenerated={}", prodMode, sessionToken != null);
    if (sessionToken != null) {
      System.out.println("JUSTSEARCH_SESSION_TOKEN=" + sessionToken);
      System.out.flush();
      log.debug("Session token printed to stdout (length={})", sessionToken.length());
    } else {
      log.warn(
          "Session token is NULL - token enforcement will be disabled if prodMode={}", prodMode);
    }
    System.out.println("JUSTSEARCH_API_PORT=" + port);
    System.out.flush();
  }

  private static WorkerConnectionResult connectWorker(
      ApiPhaseResult apiPhase,
      java.util.concurrent.CompletableFuture<KnowledgeServerStartResult> workerFuture) {
    KnowledgeServerStartResult ksStart = workerFuture.join();
    KnowledgeServerBootstrap knowledgeServer = ksStart.bootstrap();
    String knowledgeServerStartError = ksStart.startError();
    HeadAssembly bootstrap = apiPhase.bootstrap();
    LocalApiServer apiServer = apiPhase.apiServer();
    KnowledgeServerHealthMonitor healthMonitor = null;

    if (knowledgeServer != null) {
      bootstrap.connectKnowledgeServer(knowledgeServer);
      apiServer.lateBindKnowledgeServer(knowledgeServer, knowledgeServerStartError);
      healthMonitor = new KnowledgeServerHealthMonitor(knowledgeServer);
      healthMonitor.start();
      if (bootstrap.capabilities().worker().available()) {
        log.info("Knowledge Server connected — search and indexing now available");
      } else {
        log.info(
            "Knowledge Server connected (health: {}); search and indexing will be available once"
                + " worker reaches READY",
            bootstrap.capabilities().worker().health());
      }
    } else if (knowledgeServerStartError != null) {
      apiServer.lateBindKnowledgeServer(null, knowledgeServerStartError);
      bootstrap.capabilities().worker()
          .transition(
              io.justsearch.app.api.lifecycle.CapabilityHealth.DEGRADED,
              "Worker spawn failed: " + knowledgeServerStartError);
      log.warn("Knowledge Server failed to start: {}", knowledgeServerStartError);
    } else {
      bootstrap.capabilities().worker()
          .transition(
              io.justsearch.app.api.lifecycle.CapabilityHealth.OFFLINE, "Worker not configured");
    }

    return new WorkerConnectionResult(knowledgeServer, knowledgeServerStartError, healthMonitor);
  }

  private static ConfigPhaseResult resolveConfig() throws Exception {
    var mode = io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.resolveMode();
    var settingsStore = new io.justsearch.app.services.settings.UiSettingsStore(mode);
    UiSettings settings = settingsStore.load();

    if (settings.getIndexBasePath() != null && !settings.getIndexBasePath().isBlank()) {
      SystemPropertyUtils.setSysPropIfBlankWithSource("justsearch.index.base_path",
          settings.getIndexBasePath(), "justsearch.index.base_path.source", "ui_settings");
    }
    if (settings.getLlamaLibPath() != null && !settings.getLlamaLibPath().isBlank()) {
      SystemPropertyUtils.setSysPropIfBlank("llama.lib.path", settings.getLlamaLibPath());
    }
    if (settings.getLlmModelPath() != null && !settings.getLlmModelPath().isBlank()) {
      SystemPropertyUtils.setSysPropIfBlankWithSource("justsearch.llm.model_path",
          settings.getLlmModelPath(), "justsearch.llm.model_path.source", "ui_settings");
    }
    if (settings.getServerExecutablePath() != null && !settings.getServerExecutablePath().isBlank()) {
      SystemPropertyUtils.setSysPropIfBlankWithSource("justsearch.server.exe",
          settings.getServerExecutablePath(), "justsearch.server.exe.source", "ui_settings");
    }
    try {
      List<String> patterns = settings.getExcludePatterns();
      if (patterns != null && !patterns.isEmpty()) {
        SystemPropertyUtils.setSysPropIfBlankWithSource("justsearch.ui.exclude_patterns",
            JSON.writeValueAsString(patterns), "justsearch.ui.exclude_patterns.source", "ui_settings");
      }
    } catch (Exception e) {
      log.warn("Failed to serialize exclude patterns: {}", e.getMessage());
    }
    if (settings.getGpuLayers() > 0) {
      SystemPropertyUtils.setSysPropIfBlankWithSource("justsearch.gpu.layers",
          String.valueOf(settings.getGpuLayers()), "justsearch.gpu.layers.source", "ui_settings");
    }
    String envCtxSize = System.getenv("JUSTSEARCH_CONTEXT_SIZE");
    if (settings.getContextLength() > 0 && (envCtxSize == null || envCtxSize.isBlank())) {
      SystemPropertyUtils.setSysPropIfBlankWithSource("justsearch.context.size",
          String.valueOf(settings.getContextLength()), "justsearch.context.size.source", "ui_settings");
    }

    ResolvedConfigBuilder rcBuilder = ResolvedConfig.builder();
    Path detectionRoot = io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull();
    Map<String, String> autoDetected = io.justsearch.ort.GpuAutoDetection.probe(detectionRoot);
    autoDetected = augmentGpuAutoDetectionAndMirror(autoDetected, HeadlessApp::queryNvmlTotalVramBytes);
    rcBuilder.contributeAutoDetected(autoDetected);
    rcBuilder.contributeBaseSources();
    io.justsearch.app.services.config.ConfigStoreRebuilder.contributeUiSettings(rcBuilder, settings);
    ResolvedConfig resolvedConfig = rcBuilder.build();
    var configStore = new ConfigStore(resolvedConfig);
    ConfigStore.setGlobal(configStore);

    maybeAutoSelectCuda12Variant(settings, configStore);
    maybeMirrorOrtNativePath();

    Path dataDir = PlatformPaths.resolveDataDir();
    try {
      Path snapshotPath = dataDir.resolve("runtime").resolve("worker-config-snapshot.json");
      resolvedConfig.toWorkerSnapshot(snapshotPath);
      System.setProperty("justsearch.worker.config_snapshot", snapshotPath.toString());
    } catch (Exception e) {
      log.debug("Failed to write worker config snapshot (best-effort)", e);
    }

    return new ConfigPhaseResult(settingsStore, settings, resolvedConfig, configStore, dataDir);
  }

  private static void maybeMirrorOrtNativePath() {
    try {
      Path home = PlatformPaths.resolveDataDir();
      if (home == null) {
        log.debug("ORT native_path mirror: data dir unresolved; skipping");
        return;
      }
      Path cuda12Dir = home.resolve("native-bin/llama-server/variants/cuda12");
      if (!Files.isDirectory(cuda12Dir)) {
        log.debug(
            "ORT native_path mirror: cuda12 dir not at {}; skipping (Install AI not run yet"
                + " or CPU profile)",
            cuda12Dir);
        return;
      }
      var missing = io.justsearch.ort.OrtCudaHelper.checkMissingCudaRuntimeDlls(cuda12Dir);
      if (!missing.isEmpty()) {
        log.warn(
            "ORT native_path mirror: cuda12 dir {} is missing runtime DLLs {} —"
                + " not setting sysprop; user can re-run Install AI to repair",
            cuda12Dir,
            missing);
        return;
      }
      // Respect explicit user override at any source.
      if (EnvRegistry.ORT_NATIVE_PATH.get().isPresent()) {
        log.debug(
            "ORT native_path mirror: justsearch.onnxruntime.native_path already set"
                + " (source: env or sysprop); respecting user override");
        return;
      }
      String absPath = cuda12Dir.toAbsolutePath().toString();
      SystemPropertyUtils.setSysPropIfBlank("justsearch.onnxruntime.native_path", absPath);
      log.info("alpha.16 fix B: ORT native path set to {} (boot-time mirror)", absPath);
    } catch (Throwable t) {
      log.warn("ORT native_path mirror failed (best-effort, non-fatal)", t);
    }
  }

  /**
   * Production query for total VRAM. Returns -1 on failure. Reads the merged effective view from
   * the one GPU service (NVML-first, with nvidia-smi fallback) rather than the raw NVML probe, so
   * the boot-time VRAM gate sees the same single-authority answer as the rest of the system
   * (tempdoc 587; the {@code GpuProbeAccessTest} foreclosure). Safe to call once at boot.
   */
  private static long queryNvmlTotalVramBytes() {
    try {
      Long total = new GpuCapabilitiesService().snapshot().effective().totalVramBytes();
      return total != null ? total : -1L;
    } catch (Throwable t) {
      log.debug("Total-VRAM query failed (best-effort): {}", t.getMessage());
      return -1L;
    }
  }

  static {
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();
  }

  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) {
    // Install crash reporter before anything else â€” catches uncaught exceptions on any thread.
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          io.justsearch.telemetry.CrashReporter.writeCrashReport(
              io.justsearch.telemetry.CrashReporter.defaultCrashDir(),
              "head",
              thread,
              throwable);
          System.exit(1);
        });
    io.justsearch.telemetry.CrashReporter.pruneOldCrashReports(
        io.justsearch.telemetry.CrashReporter.defaultCrashDir(), 30);

    long t0 = System.nanoTime();
    long tPhase;
    long tPrev;
    log.info("Starting JustSearch HeadlessApp...");

    // In sidecar contexts we prefer to keep going even when index parity is off (dev/demo usage).
    System.setProperty("justsearch.index.parity.allow_mismatch", "true");
    // Avoid infra health port conflicts; allow ephemeral bind.
    System.setProperty("justsearch.infra.health.port", "0");
    System.setProperty("justsearch.infra.health.host", "127.0.0.1");
    Telemetry telemetry = null;
    HeadAssembly bootstrap = null;
    LocalApiServer apiServer = null;
    io.justsearch.app.services.settings.UiSettingsStore settingsStore = null; // NOPMD - defensive init
    KnowledgeServerBootstrap knowledgeServer = null;
    String knowledgeServerStartError = null; // NOPMD - defensive init
    RuntimeManifestPublisher manifestPublisher = null;
    AppInstanceLock appInstanceLock = null;
    CountDownLatch latch = new CountDownLatch(1);

    try {
      // Phase 0: resolve config (tempdoc 502 §3.3)
      ConfigPhaseResult configPhase = resolveConfig();
      settingsStore = configPhase.settingsStore();
      configStore = configPhase.configStore();

      // Tempdoc 501 Phase 3: acquire AppInstanceLock at the Head BEFORE binding HTTP or
      // spawning the Worker. The lock is OS-level (FileChannel.tryLock) with PID+startedAt
      // metadata and stale recovery via ProcessHandle.of() — see AppInstanceLock.java.
      // Acquiring here lifts the invariant from the Worker-only path into the producer,
      // catching duplicate launches regardless of who started them (dev-runner, bare
      // gradle run, manual java -cp, production launcher). KnowledgeServerBootstrap
      // continues to call AppInstanceLock for the standalone-test paths but skips the
      // acquire when this system property is set.
      try {
        appInstanceLock = new AppInstanceLock(configPhase.dataDir());
        appInstanceLock.acquire();
      } catch (AppInstanceLock.AppInstanceLockException e) {
        log.error("=== DATA DIRECTORY LOCKED ===");
        log.error("Another JustSearch instance is already running for dataDir={}",
            configPhase.dataDir());
        log.error("Refusing to start. Stop the other instance first.");
        log.error("Lock file: {}/app.lock", configPhase.dataDir());
        System.exit(2);
        return;
      }

      // Tempdoc 501 Phase 1: instantiate the runtime manifest publisher as soon as the dataDir
      // is known. The first manifest write happens after the API server binds (Phase 2 below);
      // the worker fields are filled in after Phase 3 (Worker connect). The publisher cleans
      // up its files in the shutdown finally block.
      manifestPublisher = new RuntimeManifestPublisher(configPhase.dataDir());

      tPhase = System.nanoTime();
      long settingsMs = (tPhase - t0) / 1_000_000;
      tPrev = tPhase;

      // Phase 1: infrastructure (telemetry, policy)
      InfraPhaseResult infraPhase = setupInfra(configPhase);
      telemetry = infraPhase.telemetry();

      tPhase = System.nanoTime();
      long telemetryMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // Tempdoc 627 Deliverable 10: create ONE WorkerCapability before the async worker-start fork
      // and inject it into BOTH the worker bootstrap (the supervisor's writer) and the HeadAssembly
      // CapabilityGraph (the surfaces' reader). One instance => no mirror, no silent state-drift.
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability =
          new io.justsearch.app.services.lifecycle.WorkerCapability();

      // Start Knowledge Server asynchronously — spawn runs in parallel with API construction.
      java.util.concurrent.CompletableFuture<KnowledgeServerStartResult> workerFuture =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> tryStartKnowledgeServer(sharedWorkerCapability));

      // Phase 2: Build API server (degraded mode — no Worker yet)
      ApiPhaseResult apiPhase =
          buildApi(infraPhase, settingsStore, manifestPublisher, sharedWorkerCapability);
      bootstrap = apiPhase.bootstrap();
      apiServer = apiPhase.apiServer();

      // Tempdoc 627 (N1): if the previous app session ended uncleanly (a leftover runtime manifest
      // with a dead PID — the Head cannot observe its own crash in-life), narrate it now as a calm
      // occurrence on the existing RECENT EVENTS substrate. The substrate is up (buildApi above);
      // the publisher classified the leftover at construction, before publishHead overwrites it.
      // Best-effort — never blocks boot.
      if (manifestPublisher.detectedUncleanPreviousShutdown()) {
        try {
          var health = bootstrap.substrate().health();
          io.justsearch.app.services.observability.health.BootRecoveryEmitter
              .emitUncleanShutdownRecovered(
                  health.occurrenceLog(),
                  health.changes(),
                  health.headSource(),
                  java.time.Clock.systemUTC(),
                  manifestPublisher.previousInstancePid());
        } catch (Exception e) {
          log.warn("Unclean-shutdown-recovered narration failed (non-fatal)", e);
        }
      }

      // Tempdoc 501 Phase 1: first manifest write — head-only readiness. The lock file
      // is acquired here. Worker fields populated after Phase 3 below.
      try {
        manifestPublisher.publishHead(apiPhase.port(), apiPhase.sessionToken());
      } catch (Exception e) {
        log.warn("Runtime manifest publishHead failed (non-fatal)", e);
      }

      tPhase = System.nanoTime();
      long apiMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // Phase 3: Wait for Worker and connect
      WorkerConnectionResult workerResult = connectWorker(apiPhase, workerFuture);
      knowledgeServer = workerResult.knowledgeServer();
      knowledgeServerStartError = workerResult.startError();

      // Tempdoc 501 Phase 29 + Phase 33: manifest-listener wiring extracted into
      // RuntimeManifestListenerWiring. The live-worker supplier reads
      // bootstrap.currentKnowledgeServer() so health-monitor-driven worker
      // restarts are reflected in the worker.grpcPort projection.
      io.justsearch.ui.runtime.RuntimeManifestListenerWiring.wire(
          manifestPublisher,
          bootstrap,
          knowledgeServer,
          knowledgeServerStartError,
          bootstrap::currentKnowledgeServer,
          () -> configStore.get().paths().indexBasePath());

      long workerMs = (System.nanoTime() - tPrev) / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      log.info("Startup phases (ms): settings={}, telemetry={}, api={}, worker={}, total={}",
          settingsMs, telemetryMs, apiMs, workerMs, totalMs);
      log.info("Local API Server started on port {}", apiPhase.port());

      // Boot contract validation moved to before API server construction (tempdoc 502 §6).

      final LocalApiServer apiServerRef = apiServer;
      final HeadAssembly bootstrapRef = bootstrap;
      final Telemetry telemetryRef = telemetry;
      final KnowledgeServerBootstrap knowledgeServerRef = knowledgeServer;
      final KnowledgeServerHealthMonitor knowledgeServerHealthMonitorRef = workerResult.healthMonitor();
      final RuntimeManifestPublisher manifestPublisherRef = manifestPublisher;
      final AppInstanceLock appInstanceLockRef = appInstanceLock;

      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    log.info("Shutting down HeadlessApp...");
                    // Tempdoc 501 §3.4: remove the manifest before tearing down the HTTP
                    // server so a consumer that reads-and-finds-no-file is correctly informed
                    // the producer has cleanly torn down.
                    try {
                      if (manifestPublisherRef != null) {
                        manifestPublisherRef.close();
                      }
                    } catch (Exception e) {
                      log.warn("Error closing RuntimeManifestPublisher", e);
                    }
                    try {
                      if (apiServerRef != null) {
                        apiServerRef.stop();
                      }
                    } catch (Exception e) {
                      log.warn("Error stopping LocalApiServer", e);
                    }
                    // Stop the health monitor BEFORE closing the bootstrap so an in-flight
                    // tick can't observe a half-closed worker.
                    try {
                      if (knowledgeServerHealthMonitorRef != null) {
                        knowledgeServerHealthMonitorRef.close();
                      }
                    } catch (Exception e) {
                      log.warn("Error closing KnowledgeServerHealthMonitor", e);
                    }
                    try {
                      if (bootstrapRef != null) {
                        bootstrapRef.close();
                      }
                    } catch (Exception e) {
                      log.warn("Error closing HeadAssembly", e);
                    }
                    try {
                      if (knowledgeServerRef != null) {
                        knowledgeServerRef.close();
                      }
                    } catch (Exception e) {
                      log.warn("Error closing KnowledgeServer", e);
                    }
                    try {
                      // Tempdoc 518 Appendix G W4.2 — close head-side TracingBootstrap
                      // before telemetry so any final spans flush through the exporter
                      // before the underlying NDJSON file handle closes.
                      if (infraPhase.tracingBootstrap() != null) {
                        infraPhase.tracingBootstrap().close();
                      }
                    } catch (Exception e) {
                      log.warn("Error closing head TracingBootstrap", e);
                    }
                    try {
                      if (telemetryRef != null) {
                        telemetryRef.close();
                      }
                    } catch (Exception e) {
                      log.warn("Error closing telemetry", e);
                    }
                    // Tempdoc 501 Phase 3: release AppInstanceLock LAST so any subsystem
                    // that touches the dataDir during shutdown still sees the lock held.
                    try {
                      if (appInstanceLockRef != null) {
                        appInstanceLockRef.close();
                      }
                    } catch (Exception e) {
                      log.warn("Error releasing AppInstanceLock", e);
                    }
                    latch.countDown();
                  },
                  "justsearch-headless-shutdown"));

      latch.await();
      log.info("HeadlessApp stopped.");

    } catch (Exception e) {
      log.error("Fatal error in HeadlessApp", e);
      System.exit(1);
    } finally {
      try {
        if (apiServer != null) {
          apiServer.stop();
        }
      } catch (Exception ignored) {
        // best effort
      }
      try {
        if (bootstrap != null) {
          bootstrap.close();
        }
      } catch (Exception ignored) {
        // best effort
      }
      try {
        if (knowledgeServer != null) {
          knowledgeServer.close();
        }
      } catch (Exception ignored) {
        // best effort
      }
      try {
        if (telemetry != null) {
          telemetry.close();
        }
      } catch (Exception ignored) {
        // best effort
      }
      // Tempdoc 501 Phase 1: idempotent manifest cleanup. The shutdown hook above already
      // closed the publisher under SIGTERM/clean-exit; this finally block covers the path
      // where main returns from `latch.await()` after the hook fired. Calling close() twice
      // is safe.
      try {
        if (manifestPublisher != null) {
          manifestPublisher.close();
        }
      } catch (Exception e) {
        log.debug("Manifest publisher close failed in finally (non-fatal)", e);
      }
      // Tempdoc 501 Phase 3: release the app instance lock if we acquired it. Idempotent
      // (AppInstanceLock.close() returns silently if already closed).
      try {
        if (appInstanceLock != null) {
          appInstanceLock.close();
        }
      } catch (Exception e) {
        log.debug("AppInstanceLock close failed in finally (non-fatal)", e);
      }
      // Tempdoc 501 Phase 18: api-port.txt is gone, the manifest publisher's
      // close() (above) handles its own file cleanup.
    }
  }

  private record KnowledgeServerStartResult(KnowledgeServerBootstrap bootstrap, String startError) {}

  private static KnowledgeServerStartResult tryStartKnowledgeServer(
      io.justsearch.app.services.lifecycle.WorkerCapability sharedWorkerCapability) {
    try {
      log.info("Attempting to start Knowledge Server...");
      KnowledgeServerBootstrap bootstrap = new KnowledgeServerBootstrap(sharedWorkerCapability);
      bootstrap.start();
      // Tempdoc 374 alpha.23 R13-A defect #4: don't log "started successfully" if the
      // bootstrap landed in ERROR (round 13 cycle 2 evidence). The background health
      // monitor will attempt recovery and log when the worker reaches READY.
      if (bootstrap.workerCapability().available()) {
        log.info("Knowledge Server started successfully, health: READY");
      } else {
        log.warn("Knowledge Server start did not reach READY (health: {}); background health monitor will retry",
            bootstrap.workerCapability().health());
      }
      return new KnowledgeServerStartResult(bootstrap, null);
    } catch (AppInstanceLock.AppInstanceLockException e) {
      // This should be fatal: running two instances against the same dataDir is unsafe.
      log.error("=== DATA DIRECTORY LOCKED ===");
      log.error("Another JustSearch instance is already using this data directory.");
      log.error("Details:", e);
      log.error("Fix: Close the other instance, or launch with a different data dir via -Djustsearch.data.dir=<path>.");
      throw new RuntimeException(e);
    } catch (Exception e) {
      // Elevated to ERROR - this is a critical failure that affects core functionality
      log.error("=== KNOWLEDGE SERVER FAILED TO START ===");
      log.error("Indexing and search features will be UNAVAILABLE.");
      log.error("Cause:", e);
      log.error("To fix: Ensure the indexer-worker module is built (gradlew :modules:indexer-worker:installDist)");
      log.error("Stack trace:", e);
      return new KnowledgeServerStartResult(null, summarizeStartError(e));
    }
  }

  private static String summarizeStartError(Exception e) {
    if (e == null) return "";
    String msg = e.getMessage();
    if (msg == null || msg.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return msg;
  }

  /**
   * Auto-selects the cuda12 variant if GPU acceleration is requested but CUDA runtime is missing.
   *
   * <p>This handles the common case where users have NVIDIA GPUs but don't have the CUDA Toolkit
   * installed. The cuda12 variant includes a statically-linked CUDA runtime that works standalone.
   *
   * <p>Conditions for auto-selection:
   * <ol>
   *   <li>GPU layers > 0 (user wants GPU acceleration)</li>
   *   <li>Server executable not explicitly set via environment variable</li>
   *   <li>Current/default server uses dynamically-linked CUDA DLL</li>
   *   <li>CUDA runtime (cudart64_*.dll) is not available</li>
   *   <li>cuda12 variant exists</li>
   * </ol>
   */
  private static void maybeAutoSelectCuda12Variant(UiSettings settings, ConfigStore activeConfigStore) {
    try {
      // Check if GPU acceleration is requested
      int gpuLayers = settings.getGpuLayers();
      ConfigStore cs = activeConfigStore != null ? activeConfigStore : ConfigStore.globalOrNull();
      if (cs != null && cs.get().ai().gpuLayers() != 0) {
        gpuLayers = cs.get().ai().gpuLayers();
      }
      if (gpuLayers <= 0) {
        log.info("GPU auto-selection: SKIPPED (gpu_layers={})", gpuLayers);
        return;
      }

      // Check if user explicitly set server exe via environment variable (respect their choice)
      String serverExeSource = cs != null ? cs.get().ai().serverExeSource() : "";
      String serverExeEnv = System.getenv("JUSTSEARCH_SERVER_EXE");
      if ("environment_variable".equals(serverExeSource)
          || "operator".equals(serverExeSource)
          || (serverExeEnv != null && !serverExeEnv.isBlank())) {
        log.info(
            "GPU auto-selection: SKIPPED (server explicitly set via {})",
            serverExeSource.isBlank() ? "env var" : serverExeSource);
        return;
      }

      // Find the current/default server executable
      Path serverExe = resolveDefaultServerExecutable();
      if (serverExe == null || !Files.isRegularFile(serverExe)) {
        log.info("GPU auto-selection: SKIPPED (default server not found)");
        return;
      }

      // Check if server already has statically-linked CUDA (no switch needed)
      if (hasStaticCuda(serverExe)) {
        log.info("GPU auto-selection: SKIPPED (server has static CUDA)");
        return;
      }

      // Check if server has dynamically-linked CUDA with runtime available (no switch needed)
      if (hasDynamicCudaWithRuntime(serverExe)) {
        log.info("GPU auto-selection: SKIPPED (server has CUDA with runtime)");
        return;
      }

      // At this point: server is CPU-only OR has dynamically-linked CUDA without runtime
      // Both cases benefit from switching to cuda12 variant

      // Find cuda12 variant
      Path cuda12Exe = findCuda12Variant(serverExe);
      if (cuda12Exe == null || !Files.isRegularFile(cuda12Exe)) {
        Path expectedPath =
            serverExe
                .getParent()
                .resolve("variants")
                .resolve("cuda12")
                .resolve("llama-server.exe");
        log.warn("========================================");
        log.warn("GPU ACCELERATION UNAVAILABLE");
        log.warn("GPU requested (gpu_layers={}) but cuda12 variant not found", gpuLayers);
        log.warn("Expected: {}", expectedPath);
        log.warn("Check /api/ai/runtime/status for diagnostics");
        log.warn("========================================");
        return;
      }

      // Verify required CUDA DLLs exist in cuda12 variant directory
      Path cuda12Dir = cuda12Exe.getParent();
      String[] requiredDlls = {"ggml-cuda.dll", "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll"};
      List<String> missingDlls = new java.util.ArrayList<>();
      for (String dll : requiredDlls) {
        if (!Files.isRegularFile(cuda12Dir.resolve(dll))) {
          missingDlls.add(dll);
        }
      }
      if (!missingDlls.isEmpty()) {
        log.warn("========================================");
        log.warn("GPU ACCELERATION UNAVAILABLE");
        log.warn("cuda12 variant found but missing DLLs: {}", String.join(", ", missingDlls));
        log.warn("Directory: {}", cuda12Dir);
        log.warn("========================================");
        return;
      }

      // Auto-select cuda12 variant
      log.info("=== AUTO-SELECTING CUDA12 VARIANT ===");
      log.info("GPU acceleration requested. Current server is CPU-only or missing CUDA runtime.");
      log.info("Switching to cuda12 variant for GPU acceleration.");
      log.info("  From: {}", serverExe);
      log.info("  To:   {}", cuda12Exe);

      System.setProperty("justsearch.server.exe", cuda12Exe.toAbsolutePath().toString());
      System.setProperty("justsearch.server.exe.source", "auto_selected_cuda12");

    } catch (Exception e) {
      log.warn("GPU auto-selection failed (continuing with default)", e);
    }
  }

  /** Resolves the default server executable path (same logic as InferenceConfig). */
  private static Path resolveDefaultServerExecutable() {
    // Check if already set via sysprop
    Path serverExePath = ConfigStore.global().get().ai().serverExe();
    String serverExeProp = serverExePath != null ? serverExePath.toString() : null;
    if (serverExeProp != null && !serverExeProp.isBlank()) {
      return Path.of(serverExeProp);
    }

    // Check standard locations
    try {
      Path home = ConfigStore.global().get().paths().home();
      if (home == null) {
        home = PlatformPaths.resolveDataDir();
      }
      if (home != null) {
        Path exe = home.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
        if (Files.isRegularFile(exe)) {
          return exe;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    // Check repo root for dev mode
    try {
      Path repoRoot = io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull();
      if (repoRoot != null) {
        Path exe = repoRoot.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
        if (Files.isRegularFile(exe)) {
          return exe;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    return null;
  }

  /**
   * Checks if the server already has statically-linked CUDA (bundled runtime, no switch needed).
   * Statically-linked ggml-cuda.dll is ~437MB, dynamically-linked is ~80MB.
   */
  private static boolean hasStaticCuda(Path serverExe) {
    if (serverExe == null) return false;
    Path serverDir = serverExe.getParent();
    if (serverDir == null) return false;

    Path ggmlCuda = serverDir.resolve("ggml-cuda.dll");
    if (!Files.exists(ggmlCuda)) {
      return false; // No CUDA DLL
    }

    try {
      long size = Files.size(ggmlCuda);
      // Statically-linked is ~437MB, use 400MB as threshold
      return size > 400_000_000L;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Checks if the server has dynamically-linked CUDA AND the runtime is available.
   * In this case, GPU will work with the current server (no switch needed).
   */
  private static boolean hasDynamicCudaWithRuntime(Path serverExe) {
    if (serverExe == null) return false;
    Path serverDir = serverExe.getParent();
    if (serverDir == null) return false;

    Path ggmlCuda = serverDir.resolve("ggml-cuda.dll");
    if (!Files.exists(ggmlCuda)) {
      return false; // No CUDA DLL - CPU-only, needs switch
    }

    try {
      long size = Files.size(ggmlCuda);
      // Dynamically-linked is ~80MB
      if (size >= 200_000_000L) {
        return false; // Not dynamically-linked (probably static)
      }
    } catch (Exception e) {
      return false;
    }

    // Has dynamically-linked CUDA, check if runtime is available
    return hasCudaRuntime(serverDir);
  }

  /** Checks if CUDA runtime (cudart64_*.dll) is available. */
  private static boolean hasCudaRuntime(Path serverDir) {
    // Check server directory
    if (serverDir != null && hasCudaRuntimeInDir(serverDir)) {
      return true;
    }

    // Check System32
    String systemRoot = System.getenv("SystemRoot");
    if (systemRoot != null) {
      Path system32 = Path.of(systemRoot, "System32");
      if (hasCudaRuntimeInDir(system32)) {
        return true;
      }
    }

    return false;
  }

  private static boolean hasCudaRuntimeInDir(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return false;
    try (var stream = Files.list(dir)) {
      return stream.anyMatch(p -> {
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("cudart64_") && name.endsWith(".dll");
      });
    } catch (Exception e) {
      return false;
    }
  }

  /** Finds the cuda12 variant executable. */
  private static Path findCuda12Variant(Path currentServerExe) {
    if (currentServerExe == null) return null;

    // Check relative to current server: ../variants/cuda12/llama-server.exe
    Path serverDir = currentServerExe.getParent();
    if (serverDir != null) {
      Path cuda12 = serverDir.resolve("variants").resolve("cuda12").resolve("llama-server.exe");
      if (Files.isRegularFile(cuda12)) {
        return cuda12;
      }
    }

    // Check repo root for dev mode
    try {
      Path repoRoot = io.justsearch.configuration.RepoRootLocator.findRepoRootOrNull();
      if (repoRoot != null) {
        Path cuda12 = repoRoot
            .resolve("modules").resolve("ui").resolve("native-bin")
            .resolve("llama-server").resolve("variants").resolve("cuda12")
            .resolve("llama-server.exe");
        if (Files.isRegularFile(cuda12)) {
          return cuda12;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    return null;
  }

  /**
   * Best-effort: keep legacy and canonical data-dir properties in sync for this JVM process.
   *
   * <p>Some subsystems still use {@code app.data_dir} (e.g., logback config), while the SSOT
   * path resolution prefers {@code justsearch.data.dir}. This method reduces surprise by
   * ensuring both properties are set when possible.
   */
  private static void harmonizeDataDirProperties(Path resolvedDataDir) {
    if (resolvedDataDir == null) {
      return;
    }
    String resolved = resolvedDataDir.toAbsolutePath().normalize().toString();

    // Canonical property used by EnvRegistry/PlatformPaths
    String canonical = System.getProperty(EnvRegistry.DATA_DIR.sysProp());
    if (canonical == null || canonical.isBlank()) {
      System.setProperty("justsearch.data.dir", resolved);
    }

    // Legacy underscore variant (still used by some scripts/tests)
    String legacy = System.getProperty("justsearch.data_dir"); // SYS-PROP-LEGACY-COMPAT
    if (legacy == null || legacy.isBlank()) {
      System.setProperty("justsearch.data_dir", resolved);
    }

    // Legacy app.data_dir used by logback and some launchers
    String appDataDir = System.getProperty("app.data_dir");
    if (appDataDir == null || appDataDir.isBlank()) {
      System.setProperty("app.data_dir", resolved);
    }
  }
}
