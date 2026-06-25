/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramFlagsUtil;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.configuration.RepoRootLocator;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v3: Runtime variant activation with bounded self-test + rollback.
 *
 * <p>Important: install ≠ activate. Runtime packs can be imported safely without changing any runtime pointers.
 */
public final class RuntimeActivationService implements io.justsearch.app.api.RuntimeActivationService {
  private static final Logger log = LoggerFactory.getLogger(RuntimeActivationService.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private static final String STATUS_FILE = "runtime-activation-state.json";

  // Mirror SettingsController behavior for server exe sysprop ownership.
  private static final String SERVER_EXE_SYS_PROP = "justsearch.server.exe";
  private static final String SERVER_EXE_SOURCE_PROP = "justsearch.server.exe.source";
  private static final String SOURCE_UI_SETTINGS = "ui_settings";

  /**
   * Tempdoc 374 alpha.16 fix A: source label written by both
   * {@link io.justsearch.ui.HeadlessApp#maybeAutoSelectCuda12Variant} (boot-time) and
   * {@link io.justsearch.ui.ai.install.AiInstallService#applyCudaServerExe} (Install AI
   * follow-up). Pre-alpha.16 the activation API treated this source as a third-party operator
   * lock and rejected POST /api/ai/runtime/activate even after the self-test passed
   * — the round-6 sandbox agent flagged it. Now recognized as system-owned alongside
   * {@link #SOURCE_UI_SETTINGS}.
   */
  private static final String SOURCE_AUTO_SELECTED_CUDA12 = "auto_selected_cuda12";

  // Tempdoc 374 alpha.17 R1: route through the same sysprop as
  // LlamaServerOps.HEALTH_CHECK_TIMEOUT_MS so the activation self-test honours operator
  // overrides too. Default raised from 30s → 120s to cover Qwen3.5-9B Q4_K_M cold-load +
  // multimodal mmproj warmup on first launch (round-7 evidence).
  private static final long HEALTH_CHECK_TIMEOUT_MS =
      Long.parseLong(
          System.getProperty("justsearch.inference.health_check_timeout_ms", "120000")); // SYS-PROP-LEGACY-COMPAT: static init before ConfigStore
  private static final long HEALTH_CHECK_INTERVAL_MS = 500;

  // Windows process exit code for missing DLL dependencies at load time (STATUS_DLL_NOT_FOUND / 0xC0000135).
  private static final int WINDOWS_STATUS_DLL_NOT_FOUND = -1073741515;

  // Self-test VRAM delta threshold (best-effort; noisy environments should produce INCONCLUSIVE).
  private static final long MIN_VRAM_DELTA_BYTES = 64L * 1024 * 1024; // 64 MiB

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; routes through
  // GpuCapabilitiesService (NVML-first) + VramRequirements helpers.
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final EnterprisePolicyService policyService;
  private final WorkerFeatureCache workerFeatureCache; // nullable

  private final Path aiHome;
  private final Path statusPath;
  private final Path variantsRoot;

  private final Object lock = new Object();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AiRuntimeActivationStatus status = new AiRuntimeActivationStatus();

  // Effective VRAM flags from last self-test (for status exposure)
  private volatile List<String> lastSelfTestEffectiveFlags = List.of();

  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService) {
    this(onlineAi, settingsStore, gpuCapabilitiesService, policyService, null);
  }

  public RuntimeActivationService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService policyService,
      WorkerFeatureCache workerFeatureCache) {
    this.onlineAi = Objects.requireNonNull(onlineAi, "onlineAi");
    this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
    this.gpuCapabilitiesService = gpuCapabilitiesService == null ? new GpuCapabilitiesService() : gpuCapabilitiesService;
    this.policyService = policyService; // may be null (best-effort)
    this.workerFeatureCache = workerFeatureCache; // may be null (graceful degradation)
    this.aiHome = resolveAiHome();
    this.statusPath = aiHome.resolve("ai").resolve(STATUS_FILE);
    this.variantsRoot = resolveVariantsRoot();
    loadStatusBestEffort();
  }

  public AiRuntimeActivationStatus getActivationStatus() {
    synchronized (lock) {
      return copyStatus(status);
    }
  }

  public AiRuntimeStatusResponse getStatus() {
    AiRuntimeActivationStatus activation = getActivationStatus();
    List<AiRuntimeStatusResponse.InstalledVariant> installed = listInstalledVariants();

    UiSettings s = settingsStore.load();
    String activeExe = System.getProperty(SERVER_EXE_SYS_PROP, "");
    if (activeExe == null || activeExe.isBlank()) {
      activeExe = s.getServerExecutablePath();
    }
    String activeVariantId = resolveVariantIdFromExePath(activeExe);
    // Tempdoc 374 alpha.14 fix P1-B: read gpu_layers from the resolved config
    // (which integrates auto-populate at ord 150 + env vars at 400 + sysprops
    // at 500) rather than UiSettings (which defaults 0 and only reflects
    // explicit user input). Pre-alpha.14 this endpoint reported `gpuLayers: 0`
    // even when llama-server was actually launched with `-ngl 99` from the
    // resolved config — the UiSettings field is the "explicit override slot",
    // not the running value. Falls back to UiSettings only when ConfigStore
    // is absent (shouldn't happen post-boot, but defensive).
    Integer gpuLayers;
    var configStore = ConfigStore.globalOrNull();
    var resolvedConfig = configStore != null ? configStore.get() : null;
    if (resolvedConfig != null && resolvedConfig.ai() != null) {
      gpuLayers = resolvedConfig.ai().gpuLayers();
    } else {
      gpuLayers = s.getGpuLayers();
    }

    // Capture VRAM detection info for debugging
    GpuCapabilities gpuSnap = gpuCapabilitiesService.snapshot();
    String vramSource = gpuSnap != null ? gpuSnap.effective().source() : "none";
    String vramTier = VramFlagsUtil.detectVramTier(gpuSnap != null ? gpuSnap.effective().totalVramBytes() : null);
    Long vramTotal = gpuSnap != null ? gpuSnap.effective().totalVramBytes() : null;
    Long vramFree = gpuSnap != null ? gpuSnap.effective().freeVramBytes() : null;
    List<String> effectiveFlags = lastSelfTestEffectiveFlags;

    return new AiRuntimeStatusResponse(
        activation,
        installed,
        new AiRuntimeStatusResponse.ActiveRuntime(
            activeExe == null ? "" : activeExe,
            activeVariantId,
            gpuLayers,
            vramSource,
            vramTier,
            effectiveFlags,
            vramTotal,
            vramFree),
        resolveOnnxFeatures());
  }

  // --------------- ONNX feature status ---------------

  private List<AiRuntimeStatusResponse.OnnxFeatureStatus> resolveOnnxFeatures() {
    return List.of(
        resolveOneOnnxFeature(
            "reranker",
            "Search reranking",
            EnvRegistry.RERANK_ENABLED.envVar(),
            EnvRegistry.RERANK_ENABLED.sysProp(),
            EnvRegistry.RERANK_MODEL_PATH.envVar(),
            EnvRegistry.RERANK_MODEL_PATH.sysProp(),
            "reranker"),
        resolveOneOnnxFeature(
            "citation_scorer",
            "Citation scoring",
            EnvRegistry.CITATION_SCORER_ENABLED.envVar(),
            EnvRegistry.CITATION_SCORER_ENABLED.sysProp(),
            EnvRegistry.CITATION_SCORER_MODEL_PATH.envVar(),
            EnvRegistry.CITATION_SCORER_MODEL_PATH.sysProp(),
            "citation-scorer"));
  }

  private AiRuntimeStatusResponse.OnnxFeatureStatus resolveOneOnnxFeature(
      String id,
      String label,
      String enabledEnv,
      String enabledProp,
      String pathEnv,
      String pathProp,
      String modelName) {
    // Look up runtime session state from Worker's health check cache (368 RC3).
    // This is the canonical source of truth for "is this model actually working."
    boolean sessionActive = resolveSessionActive(modelName);

    // 1. Check if explicitly disabled (Head-owned: uses Head-side env vars)
    String enabledStr = resolveEnvOrProp(enabledEnv, enabledProp);
    if ("false".equalsIgnoreCase(enabledStr)) {
      return new AiRuntimeStatusResponse.OnnxFeatureStatus(
          id, label, "inactive", "disabled", null, sessionActive);
    }

    // 2. Explicit model path (Head-owned: uses Head-side env vars)
    String explicitPath = resolveEnvOrProp(pathEnv, pathProp);
    if (explicitPath != null && !explicitPath.isBlank()) {
      return new AiRuntimeStatusResponse.OnnxFeatureStatus(
          id, label, "active", "explicit_path", explicitPath, sessionActive);
    }

    // 3. Worker-reported discovery (includes both auto-discovery and explicit-path results)
    if (workerFeatureCache != null) {
      for (OnnxModelStatus status : workerFeatureCache.getOnnxModels()) {
        if (modelName.equals(status.modelName()) && status.found()) {
          return new AiRuntimeStatusResponse.OnnxFeatureStatus(
              id, label, "active", "auto_discovered", status.path(), sessionActive);
        }
      }
    }

    // 4. Not found
    return new AiRuntimeStatusResponse.OnnxFeatureStatus(
        id, label, "inactive", "not_found", null, sessionActive);
  }

  /** Returns true if the Worker reports an active ORT session for this model. */
  private boolean resolveSessionActive(String modelName) {
    if (workerFeatureCache == null) {
      return false;
    }
    for (OnnxModelStatus status : workerFeatureCache.getOnnxModels()) {
      if (modelName.equals(status.modelName())) {
        return status.sessionActive();
      }
    }
    return false;
  }

  /** Resolves a value from system property first, then environment variable. */
  private static String resolveEnvOrProp(String envVar, String sysProp) {
    String val = System.getProperty(sysProp);
    if (val != null && !val.isBlank()) {
      return val;
    }
    val = System.getenv(envVar);
    if (val != null && !val.isBlank()) {
      return val;
    }
    return null;
  }

  public void startActivate(String variantId) {
    String v = variantId == null ? "" : variantId.trim();
    if (v.isBlank()) {
      throw new IllegalArgumentException("variantId is required");
    }
    synchronized (lock) {
      if (running.get()) {
        throw new IllegalStateException("Runtime activation already running");
      }
      running.set(true);
      status.startedAtEpochMs = System.currentTimeMillis();
      updateState("running", "validate", "Starting runtime activation…", null);
      status.variantId = v;
      status.result = "";
      status.vramUsedBeforeBytes = null;
      status.vramUsedAfterBytes = null;
      status.vramUsedDeltaBytes = null;
      status.selfTestPort = null;
      touch();
    }
    Thread t =
        new Thread(
            () -> {
              try {
                runActivate(v);
              } finally {
                running.set(false);
              }
            },
            "ai-runtime-activate");
    t.setDaemon(true);
    t.start();
  }

  public void startDeactivate() {
    synchronized (lock) {
      if (running.get()) {
        throw new IllegalStateException("Runtime activation already running");
      }
      running.set(true);
      status.startedAtEpochMs = System.currentTimeMillis();
      updateState("running", "apply", "Deactivating GPU runtime…", null);
      status.variantId = "";
      status.result = "";
      touch();
    }
    Thread t =
        new Thread(
            () -> {
              try {
                runDeactivate();
              } finally {
                running.set(false);
              }
            },
            "ai-runtime-deactivate");
    t.setDaemon(true);
    t.start();
  }

  // -------------------- Implementation --------------------

  private void runActivate(String variantId) {
    EffectivePolicy effective;
    try {
      effective = policyService != null ? policyService.snapshot() : null;
    } catch (Exception ignored) {
      effective = null;
    }
    if (effective != null) {
      // v3 enforcement: block activation when Online AI or GPU acceleration is disabled by policy.
      if (!effective.onlineAiEnabled()) {
        fail("POLICY_ONLINE_AI_DISABLED", "Online AI is disabled by administrator policy.", null);
        return;
      }
      if (!effective.gpuAccelerationEnabled()) {
        fail("POLICY_GPU_DISABLED", "GPU acceleration is disabled by administrator policy.", null);
        return;
      }
      // snapshot() already bridged policy sysprops to app-services enforcement points.
    }

    Path exe = variantsRoot.resolve(variantId).resolve("llama-server.exe");
    if (!Files.isRegularFile(exe)) {
      // G17: "default" variant may be the baseline exe flat in native-bin/llama-server/
      // (not under variants/). Fall back to it so fresh installs can activate.
      if ("default".equals(variantId)) {
        Path baseline = variantsRoot.getParent().resolve("llama-server.exe");
        if (Files.isRegularFile(baseline)) {
          log.info("Using baseline exe as default variant: {}", baseline);
          exe = baseline;
        }
      }
      if (!Files.isRegularFile(exe)) {
        fail("RUNTIME_VARIANT_NOT_INSTALLED", "Variant not installed: " + variantId, null);
        return;
      }
    }

    UiSettings current = settingsStore.load();
    String modelPath = current.getLlmModelPath();
    if (modelPath == null || modelPath.isBlank()) {
      fail("MODEL_PATH_REQUIRED", "No chat model configured. Import a models pack first.", null);
      return;
    }
    Path model = Path.of(modelPath.trim());
    if (!Files.isRegularFile(model)) {
      fail("MODEL_NOT_FOUND", "Configured model does not exist: " + model, null);
      return;
    }

    updateState("running", "self_test", "Running GPU self-test…", null);
    SelfTestResult selfTest = runSelfTest(exe, model, current);
    if (selfTest == null) {
      fail("SELF_TEST_FAILED", "Self-test failed.", null);
      return;
    }

    synchronized (lock) {
      status.selfTestPort = selfTest.port == null ? null : selfTest.port.longValue();
      status.vramUsedBeforeBytes = selfTest.vramBefore;
      status.vramUsedAfterBytes = selfTest.vramAfter;
      status.vramUsedDeltaBytes = selfTest.delta;
      status.result = selfTest.result;
      touch();
    }

    if (!"passed".equalsIgnoreCase(selfTest.result)) {
      // Do not activate on failed/inconclusive.
      String msg =
          "inconclusive".equalsIgnoreCase(selfTest.result)
              ? "GPU self-test inconclusive; runtime pack installed but NOT activated."
              : "GPU self-test failed; runtime pack installed but NOT activated.";
      updateState("completed", "done", msg, null);
      return;
    }

    updateState("running", "apply", "Activating runtime variant…", null);

    // Capture previous state for rollback.
    UiSettings prevSettings = settingsStore.load();
    String prevSys = System.getProperty(SERVER_EXE_SYS_PROP, "");
    String prevSysSource = System.getProperty(SERVER_EXE_SOURCE_PROP, "");

    try {
      // Persist settings (so activation survives restart) AND apply sysprop (so reload works immediately).
      UiSettings next = settingsStore.load();
      next.setServerExecutablePath(exe.toAbsolutePath().toString());
      if (next.getGpuLayers() <= 0) {
        next.setGpuLayers(99);
      }
      settingsStore.save(next);

      if (!applyServerExeSysProp(exe.toAbsolutePath().toString())) {
        throw new IllegalStateException("Server executable override is locked by operator config");
      }

      applyRuntimeOverridesBestEffort(next);

      // Rebuild ConfigStore so readers see updated server EXE / GPU layers.
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), next);

      updateState("completed", "done", "GPU runtime activated.", null);
    } catch (Exception e) {
      log.warn("Runtime activation failed; attempting rollback", e);
      updateState("running", "rollback", "Activation failed; rolling back…", null);
      boolean rolledBack = rollback(prevSettings, prevSys, prevSysSource);
      if (!rolledBack) {
        fail("RUNTIME_ROLLBACK_FAILED", "Rollback failed after activation error: " + safeMsg(e), e);
        return;
      }
      fail("RUNTIME_ACTIVATION_FAILED", "Activation failed: " + safeMsg(e), e);
    }
  }

  private void runDeactivate() {
    // Best-effort: choose CPU baseline from native-bin/llama-server (excluding variants/).
    Path baselineExe = resolveCpuBaselineExe(aiHome);
    if (baselineExe == null || !Files.isRegularFile(baselineExe)) {
      fail("RUNTIME_BASELINE_NOT_FOUND", "CPU baseline llama-server.exe not found.", null);
      return;
    }

    UiSettings prevSettings = settingsStore.load();
    String prevSys = System.getProperty(SERVER_EXE_SYS_PROP, "");
    String prevSysSource = System.getProperty(SERVER_EXE_SOURCE_PROP, "");

    try {
      UiSettings next = settingsStore.load();
      next.setServerExecutablePath(""); // revert to default discovery on restart
      next.setGpuLayers(0);
      settingsStore.save(next);

      // Force immediate switch to baseline for this process.
      forceServerExeSysProp(baselineExe.toAbsolutePath().toString());
      applyRuntimeOverridesBestEffort(next);

      // Rebuild ConfigStore so readers see reverted server EXE / GPU layers.
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), next);

      updateState("completed", "done", "GPU runtime deactivated (CPU baseline).", null);
    } catch (Exception e) {
      log.warn("Runtime deactivation failed; attempting rollback", e);
      updateState("running", "rollback", "Deactivation failed; rolling back…", null);
      boolean rolledBack = rollback(prevSettings, prevSys, prevSysSource);
      if (!rolledBack) {
        fail("RUNTIME_ROLLBACK_FAILED", "Rollback failed after deactivation error: " + safeMsg(e), e);
        return;
      }
      fail("RUNTIME_DEACTIVATION_FAILED", "Deactivation failed: " + safeMsg(e), e);
    }
  }

  private boolean rollback(UiSettings prevSettings, String prevSys, String prevSysSource) {
    try {
      if (prevSettings != null) {
        settingsStore.save(prevSettings);
      }

      // Restore sysprop if it was previously set; otherwise force baseline.
      if (prevSys != null && !prevSys.isBlank()) {
        System.setProperty(SERVER_EXE_SYS_PROP, prevSys);
        if (prevSysSource != null && !prevSysSource.isBlank()) {
          System.setProperty(SERVER_EXE_SOURCE_PROP, prevSysSource);
        } else {
          System.clearProperty(SERVER_EXE_SOURCE_PROP);
        }
      } else {
        Path baselineExe = resolveCpuBaselineExe(aiHome);
        if (baselineExe != null && Files.isRegularFile(baselineExe)) {
          forceServerExeSysProp(baselineExe.toAbsolutePath().toString());
        } else {
          System.clearProperty(SERVER_EXE_SYS_PROP);
          System.clearProperty(SERVER_EXE_SOURCE_PROP);
        }
      }

      applyRuntimeOverridesBestEffort(prevSettings);

      // Rebuild ConfigStore so readers see restored sysprops.
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), prevSettings);

      return true;
    } catch (Exception e) {
      log.warn("Rollback failed", e);
      return false;
    }
  }

  private void applyRuntimeOverridesBestEffort(UiSettings settings) {
    OnlineAiService onlineAi = this.onlineAi;
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      return;
    }
    try {
      control.applyRuntimeOverrides(
          settings == null ? null : settings.getLlmModelPath(),
          settings == null ? null : settings.getContextLength(),
          settings == null ? null : settings.getGpuLayers(),
          OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS);
    } catch (Exception e) {
      throw new RuntimeException("Failed to apply runtime overrides", e);
    }
  }

  private boolean applyServerExeSysProp(String exePath) {
    String source = System.getProperty(SERVER_EXE_SOURCE_PROP, "");
    String existing = System.getProperty(SERVER_EXE_SYS_PROP, "");
    // Tempdoc 374 alpha.16 fix A: treat both ui_settings and auto_selected_cuda12 as
    // system-owned so the activation flow can overwrite them. The pre-alpha.16 check only
    // matched ui_settings, so HeadlessApp's boot-time auto-select (and AiInstallService's
    // applyCudaServerExe follow-up) registered as third-party operator locks and rejected
    // every POST /api/ai/runtime/activate even when the self-test passed.
    boolean owned =
        SOURCE_UI_SETTINGS.equalsIgnoreCase(source)
            || SOURCE_AUTO_SELECTED_CUDA12.equalsIgnoreCase(source);
    boolean unset = existing == null || existing.isBlank();
    if (!owned && !unset) {
      // Respect explicit operator overrides.
      return false;
    }
    forceServerExeSysProp(exePath);
    return true;
  }

  private static void forceServerExeSysProp(String exePath) {
    if (exePath == null || exePath.isBlank()) {
      System.clearProperty(SERVER_EXE_SYS_PROP);
      System.clearProperty(SERVER_EXE_SOURCE_PROP);
      return;
    }
    System.setProperty(SERVER_EXE_SYS_PROP, exePath.trim());
    System.setProperty(SERVER_EXE_SOURCE_PROP, SOURCE_UI_SETTINGS);
  }

  private SelfTestResult runSelfTest(Path exe, Path model, UiSettings settings) {
    // Require NVML for self-test gating (v3 safety posture).
    GpuCapabilities snap = gpuCapabilitiesService.snapshot();
    if (snap == null || snap.nvml() == null || !snap.nvml().available()) {
      return new SelfTestResult("inconclusive", null, null, null, null, List.of(), "unknown", "none");
    }

    // Capture VRAM tier and source for status exposure
    String vramTier = VramFlagsUtil.detectVramTier(snap.effective().totalVramBytes());
    String vramSource = snap.effective().source();

    Long beforeUsed = snap.nvml().usedVramBytes();

    int port = pickEphemeralPort();
    synchronized (lock) {
      status.selfTestPort = (long) port;
      touch();
    }

    Process proc = null;
    try {
      proc = startSelfTestServer(exe, model, port, settings);
      waitForHealth(proc, port);
      sendTinyChatRequest(port, model.getFileName().toString());

      // Give the runtime a moment to allocate any GPU buffers.
      try {
        Thread.sleep(250);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }

      GpuCapabilities afterSnap = gpuCapabilitiesService.snapshot();
      Long afterUsed = afterSnap != null && afterSnap.nvml() != null ? afterSnap.nvml().usedVramBytes() : null;
      Long delta = (beforeUsed != null && afterUsed != null) ? (afterUsed - beforeUsed) : null;

      // Capture effective flags from self-test server startup
      List<String> effectiveFlags = lastSelfTestEffectiveFlags;

      if (delta != null && delta >= MIN_VRAM_DELTA_BYTES) {
        return new SelfTestResult("passed", port, beforeUsed, afterUsed, delta, effectiveFlags, vramTier, vramSource);
      }
      return new SelfTestResult("inconclusive", port, beforeUsed, afterUsed, delta, effectiveFlags, vramTier, vramSource);
    } catch (SelfTestException e) {
      log.warn("Self-test failed", e);
      return new SelfTestResult("failed", port, beforeUsed, null, null, lastSelfTestEffectiveFlags, vramTier, vramSource);
    } catch (Exception e) {
      log.warn("Self-test failed", e);
      return new SelfTestResult("failed", port, beforeUsed, null, null, lastSelfTestEffectiveFlags, vramTier, vramSource);
    } finally {
      stopSelfTestServer(proc);
    }
  }

  private Process startSelfTestServer(Path exe, Path model, int port, UiSettings settings) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(exe.toAbsolutePath().toString());
    command.add("-m");
    command.add(model.toAbsolutePath().toString());
    command.add("--host");
    command.add("127.0.0.1");
    command.add("--port");
    command.add(String.valueOf(port));

    int ctx = (settings != null && settings.getContextLength() > 0) ? settings.getContextLength() : 4096;
    command.add("-c");
    command.add(String.valueOf(ctx));

    int gpuLayers = (settings != null && settings.getGpuLayers() > 0) ? settings.getGpuLayers() : 99;
    command.add("-ngl");
    command.add(String.valueOf(gpuLayers));

    // Add VRAM-based llama-server flags (e.g., KV cache quantization) only when GPU mode is requested.
    // Use VramFlagsUtil for shared flag merging logic.
    if (gpuLayers > 0) {
      // Tempdoc 374 alpha.27: NVML-first VRAM probe + threshold helpers in VramRequirements.
      // Pre-fix vramDetector.getRecommendedLlamaServerFlags() shelled out to nvidia-smi
      // (returning null on cuda12 sandbox hosts), making the self-test launch llama-server
      // without KV-cache flags even on 8GB cards that need them.
      gpuCapabilitiesService.invalidateNvidiaSmiCache();
      Long totalVramBytes =
          gpuCapabilitiesService.snapshot().effective().totalVramBytes();
      String[] recommendedFlags =
          io.justsearch.gpu.VramRequirements.recommendedLlamaServerFlags(totalVramBytes);
      List<String> addedFlags = VramFlagsUtil.mergeRecommendedFlags(command, recommendedFlags);
      // Store for self-test result (best-effort)
      this.lastSelfTestEffectiveFlags = List.copyOf(addedFlags);
    } else {
      this.lastSelfTestEffectiveFlags = List.of();
    }

    ProcessBuilder pb = new ProcessBuilder(command);
    Path exeDir = exe.getParent();
    if (exeDir != null && Files.isDirectory(exeDir)) {
      pb.directory(exeDir.toFile());
    }

    // Best-effort PATH adjustments (mirrors InferenceLifecycleManager behavior).
    try {
      Map<String, String> env = pb.environment();
      String pathKey = env.containsKey("Path") ? "Path" : (env.containsKey("PATH") ? "PATH" : "Path");
      String existingPath = env.getOrDefault(pathKey, "");
      List<String> prefixes = new ArrayList<>();
      if (exeDir != null) prefixes.add(exeDir.toAbsolutePath().normalize().toString());
      Path runtimeBin = resolveBundledRuntimeBinDirBestEffort();
      if (runtimeBin != null) prefixes.add(runtimeBin.toAbsolutePath().normalize().toString());
      if (!prefixes.isEmpty()) {
        String prepend = String.join(";", prefixes);
        String next =
            existingPath == null || existingPath.isBlank() ? prepend : (prepend + ";" + existingPath);
        env.put(pathKey, next);
      }
    } catch (Exception ignored) {
      // best-effort
    }

    // Persist logs for debugging failures.
    Path logFile = aiHome.resolve("logs").resolve("llama-server-selftest.log");
    try {
      Files.createDirectories(logFile.getParent());
    } catch (IOException e) {
      log.warn("Could not create log directory {}", logFile.getParent(), e);
    }
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

    log.info("Self-test starting llama-server: {}", String.join(" ", command));
    return pb.start();
  }

  private void waitForHealth(Process proc, int port) throws SelfTestException {
    long deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (proc != null && !proc.isAlive()) {
        int exitCode = -1;
        try {
          exitCode = proc.exitValue();
        } catch (Exception e) {
          log.debug("Failed to retrieve llama-server process exit code", e);
        }
        if (exitCode == WINDOWS_STATUS_DLL_NOT_FOUND) {
          throw new SelfTestException(
              "llama-server failed to start (missing DLL dependencies, 0xC0000135). See logs/llama-server-selftest.log");
        }
        throw new SelfTestException("llama-server exited before healthy (exit code " + exitCode + ")");
      }
      if (isHealthy(port)) {
        return;
      }
      try {
        Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SelfTestException("Health check interrupted");
      }
    }
    throw new SelfTestException("Server health check timeout after " + HEALTH_CHECK_TIMEOUT_MS + "ms");
  }

  private boolean isHealthy(int port) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/health"))
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();
      HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      return resp.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  private void sendTinyChatRequest(int port, String modelId) throws SelfTestException {
    try {
      List<Map<String, Object>> messages =
          List.of(Map.of("role", "user", "content", "Reply with the single word: ok"));
      Map<String, Object> body = new HashMap<>();
      body.put("model", modelId == null ? "" : modelId);
      body.put("messages", messages);
      body.put("max_tokens", 8);
      String json = MAPPER.writeValueAsString(body);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + port + "/v1/chat/completions"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(20))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        throw new SelfTestException("Chat request failed: status=" + resp.statusCode());
      }
    } catch (SelfTestException e) {
      throw e;
    } catch (Exception e) {
      throw new SelfTestException("Chat request failed: " + safeMsg(e));
    }
  }

  private void stopSelfTestServer(Process proc) {
    if (proc == null) return;
    try {
      if (!proc.isAlive()) return;
      long pid = proc.pid();
      proc.destroy();
      boolean exited = false;
      try {
        exited = proc.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (!exited && isWindows()) {
        try {
          new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
          proc.destroyForcibly();
        }
      } else if (!exited) {
        proc.destroyForcibly();
      }
    } catch (Exception ignored) {
      // best-effort
    }
  }

  // -------------------- Status persistence --------------------

  private void updateState(String state, String phase, String message, String errorCode) {
    synchronized (lock) {
      status.state = safe(state);
      status.phase = safe(phase);
      status.message = safe(message);
      status.errorCode = safe(errorCode);
      if ("running".equalsIgnoreCase(state)) {
        if (status.startedAtEpochMs == 0) status.startedAtEpochMs = System.currentTimeMillis();
      }
      touch();
    }
  }

  private void fail(String errorCode, String message, Exception e) {
    if (e != null) {
      log.warn("Runtime activation failed: {} {}", errorCode, message, e);
    } else {
      log.warn("Runtime activation failed: {} {}", errorCode, message);
    }
    synchronized (lock) {
      status.state = "failed";
      status.phase = "done";
      status.message = safe(message);
      status.errorCode = safe(errorCode);
      status.updatedAtEpochMs = System.currentTimeMillis();
      touch();
    }
  }

  private void touch() {
    status.updatedAtEpochMs = System.currentTimeMillis();
    saveStatusBestEffort();
  }

  private void saveStatusBestEffort() {
    try {
      Files.createDirectories(statusPath.getParent());
      MAPPER.writeValue(statusPath.toFile(), status);
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private void loadStatusBestEffort() {
    try {
      if (!Files.exists(statusPath)) return;
      AiRuntimeActivationStatus loaded = MAPPER.readValue(statusPath.toFile(), AiRuntimeActivationStatus.class);
      if (loaded == null) return;
      synchronized (lock) {
        status.state = safe(loaded.state);
        status.phase = safe(loaded.phase);
        status.message = safe(loaded.message);
        status.errorCode = safe(loaded.errorCode);
        status.variantId = safe(loaded.variantId);
        status.result = safe(loaded.result);
        status.vramUsedBeforeBytes = loaded.vramUsedBeforeBytes;
        status.vramUsedAfterBytes = loaded.vramUsedAfterBytes;
        status.vramUsedDeltaBytes = loaded.vramUsedDeltaBytes;
        status.selfTestPort = loaded.selfTestPort;
        status.startedAtEpochMs = loaded.startedAtEpochMs;
        status.updatedAtEpochMs = loaded.updatedAtEpochMs;

        // After a JVM restart, terminal activation states are stale — the self-test
        // server (ephemeral port) is dead and the apply-config was for the previous
        // lifecycle. Reset to idle so the next activate() re-runs the full flow.
        if ("completed".equals(status.state) || "failed".equals(status.state)) {
          status.state = "idle";
          status.phase = "";
          status.message = "";
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private static AiRuntimeActivationStatus copyStatus(AiRuntimeActivationStatus s) {
    AiRuntimeActivationStatus c = new AiRuntimeActivationStatus();
    c.state = safe(s.state);
    c.phase = safe(s.phase);
    c.message = safe(s.message);
    c.errorCode = safe(s.errorCode);
    c.variantId = safe(s.variantId);
    c.result = safe(s.result);
    c.vramUsedBeforeBytes = s.vramUsedBeforeBytes;
    c.vramUsedAfterBytes = s.vramUsedAfterBytes;
    c.vramUsedDeltaBytes = s.vramUsedDeltaBytes;
    c.selfTestPort = s.selfTestPort;
    c.startedAtEpochMs = s.startedAtEpochMs;
    c.updatedAtEpochMs = s.updatedAtEpochMs;
    return c;
  }

  // -------------------- Status helpers --------------------

  private List<AiRuntimeStatusResponse.InstalledVariant> listInstalledVariants() {
    List<AiRuntimeStatusResponse.InstalledVariant> out = new ArrayList<>();

    // G17: Include the baseline exe as a synthetic "default" variant if it exists
    // flat in native-bin/llama-server/ (not under variants/).
    Path baselineExe = variantsRoot.getParent().resolve("llama-server.exe");
    boolean hasDefaultVariantDir = Files.isDirectory(variantsRoot.resolve("default"));
    if (Files.isRegularFile(baselineExe) && !hasDefaultVariantDir) {
      out.add(
          new AiRuntimeStatusResponse.InstalledVariant(
              "default", baselineExe.toAbsolutePath().toString()));
    }

    if (Files.isDirectory(variantsRoot)) {
      try (var stream = Files.list(variantsRoot)) {
        stream
            .filter(Files::isDirectory)
            .sorted(
                Comparator.comparing(
                    p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
            .forEach(
                dir -> {
                  Path exe = dir.resolve("llama-server.exe");
                  if (Files.isRegularFile(exe)) {
                    out.add(
                        new AiRuntimeStatusResponse.InstalledVariant(
                            dir.getFileName().toString(),
                            exe.toAbsolutePath().toString()));
                  } else {
                    // Tempdoc 374 sandbox round 2 finding #4.5: a variant dir
                    // without llama-server.exe means a prior install with the
                    // CUDA variant left DLLs behind, but the current build
                    // skipped staging the exe (e.g., -PincludeCuda=false).
                    // Log so drift is visible without polluting the API
                    // response with a non-executable path.
                    log.warn(
                        "Variant directory present without llama-server.exe: {} (likely leftover from a previous build)",
                        dir);
                  }
                });
      } catch (Exception ignored) {
        // best-effort
      }
    }
    return out;
  }

  private static String resolveVariantIdFromExePath(String exePath) {
    if (exePath == null || exePath.isBlank()) return null;
    try {
      Path p = Path.of(exePath.trim());
      int n = p.getNameCount();
      for (int i = 0; i < n; i++) {
        String seg = p.getName(i).toString();
        if ("variants".equalsIgnoreCase(seg) && i + 1 < n) {
          return p.getName(i + 1).toString();
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }
    return null;
  }

  private static int pickEphemeralPort() {
    try (ServerSocket sock = new ServerSocket(0)) {
      sock.setReuseAddress(true);
      return sock.getLocalPort();
    } catch (IOException e) {
      // Fallback to a common local-only port; caller will fail cleanly if in use.
      return 18080;
    }
  }

  private static Path resolveAiHome() {
    return PlatformPaths.resolveAiHome();
  }

  /**
   * Resolves the variants root directory, with fallback for dev mode.
   *
   * <p>In production, variants are at {@code {aiHome}/native-bin/llama-server/variants/}. In dev
   * mode, aiHome typically points to {@code .dev-data} but variants are at
   * {@code modules/ui/native-bin/llama-server/variants/}.
   */
  private Path resolveVariantsRoot() {
    Path standard = aiHome.resolve("native-bin").resolve("llama-server").resolve("variants");
    if (Files.isDirectory(standard)) {
      return standard;
    }

    // Dev mode fallback: use RepoRootLocator for auto-discovery
    try {
      Path repoRoot = RepoRootLocator.findRepoRootOrNull();
      if (repoRoot != null) {
        Path devVariants =
            repoRoot
                .resolve("modules")
                .resolve("ui")
                .resolve("native-bin")
                .resolve("llama-server")
                .resolve("variants");
        if (Files.isDirectory(devVariants)) {
          log.debug("Using dev mode variants path: {}", devVariants);
          return devVariants;
        }
      }
    } catch (Exception ignored) {
      // best-effort
    }

    // Return standard path even if it doesn't exist (for consistent error messages)
    return standard;
  }

  private static Path resolveBundledRuntimeBinDirBestEffort() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path repoRootPath = cs != null ? cs.get().paths().repoRoot() : null;
      Path headlessDir = repoRootPath != null ? repoRootPath : Path.of(System.getProperty("user.dir"));
      Path bin = headlessDir.resolve("runtime").resolve("bin");
      if (Files.isDirectory(bin)) {
        return bin;
      }
    } catch (Exception ignored) {
      // best-effort
    }
    return null;
  }

  private static Path resolveCpuBaselineExe(Path aiHome) {
    if (aiHome == null) return null;
    try {
      Path nativeBin = aiHome.resolve("native-bin").resolve("llama-server");
      if (!Files.isDirectory(nativeBin)) return null;

      // 1. Check canonical baseline path FIRST (deterministic, preferred)
      Path canonical = nativeBin.resolve("llama-server.exe");
      if (Files.isRegularFile(canonical)) {
        return canonical;
      }

      // 2. Scan subdirectories (SORTED for determinism, skip variants/)
      try (var dirs = Files.list(nativeBin)) {
        return dirs
            .filter(Files::isDirectory)
            .filter(d -> !"variants".equalsIgnoreCase(d.getFileName().toString()))
            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
            .map(d -> d.resolve("llama-server.exe"))
            .filter(Files::isRegularFile)
            .findFirst()
            .orElse(null);
      }
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win");
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String safeMsg(Throwable t) {
    if (t == null) return "";
    String m = t.getMessage();
    if (m == null || m.isBlank()) {
      return t.getClass().getSimpleName();
    }
    return m;
  }

  private record SelfTestResult(
      String result,
      Integer port,
      Long vramBefore,
      Long vramAfter,
      Long delta,
      List<String> effectiveFlags,
      String vramTier,
      String vramSource
  ) {}

  private static final class SelfTestException extends Exception {
    SelfTestException(String message) {
      super(message);
    }
  }
}
