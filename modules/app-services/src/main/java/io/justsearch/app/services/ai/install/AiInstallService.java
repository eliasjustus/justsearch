/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.SystemPropertyUtils;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.InstallIntent;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v2 AI install service — hardware-aware, package-based, contract-generating.
 *
 * <p>Replaces v1 {@code AiInstallService}. Key differences:
 *
 * <ul>
 *   <li>Loads v2 registry (package-based with variant metadata)
 *   <li>Builds {@link HardwareProfile} → selects download profile (GPU-full/GPU-lite/CPU)
 *   <li>Downloads to target paths directly (no flat copies, no arrange step)
 *   <li>Writes {@link InstallContract} (bill of materials for runtime)
 *   <li>Runtime reads contract via composition root in KnowledgeServer (no manifest needed)
 * </ul>
 */
public final class AiInstallService implements io.justsearch.app.api.AiInstallService {
  private static final Logger log = LoggerFactory.getLogger(AiInstallService.class);
  private static final String REGISTRY_RESOURCE = "ai/model-registry.v2.json";

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  // Tempdoc 374 alpha.17 R3: late-bound. LocalApiServer constructs this service
  // before the worker bootstrap completes (HeadlessApp passes null at api-builder
  // time and late-binds via apiServer.lateBindKnowledgeServer). Pre-alpha.17
  // this field was final-null forever — tryRestartWorkerBestEffort silently
  // early-returned, the post-Install-AI worker restart never fired, and the
  // boot-1 worker JVM kept its empty ORT native_path until the user manually
  // relaunched. Volatile to make the late-bind visible to any subsequent
  // Install AI invocation.
  private volatile KnowledgeServerBootstrap knowledgeServer;
  private final EnterprisePolicyService policyService;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; HardwareProfile build
  // now uses GpuCapabilitiesService (NVML-first) directly.

  private final Path homeDir;
  private final Path modelsDir;

  // Liveness backstop window (575 §17 Face C, polled-state model): a "running" install with no
  // progress for longer than this is treated as a dead owner and reclaimed to terminal on read.
  private static final long STALE_RUNNING_MS = 5 * 60_000L; // 5 min

  private final Object lock = new Object();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
  private final AiInstallStatus status = new AiInstallStatus();
  private volatile DownloadExecutor downloadExecutor;

  // Tempdoc 562: `installedFully` is session-ephemeral — only set true at the end of an install RUN, never
  // rehydrated — so after a restart a returning user with models already on disk reads a false "Not
  // Installed" (and is offered a ~10 GB re-download). "Is installed" is a function of disk, not a remembered
  // event. On the first status reads after boot we recompute it once from on-disk model presence (the
  // planner's own already-installed detection). The one-shot is consumed only on a DEFINITIVE answer: a
  // successful plan marks `diskRecomputeDone`; a transient dependency failure leaves it open to retry
  // (capped), so an early-boot hiccup can't permanently strand the false "Not Installed". `recomputeInProgress`
  // keeps a single concurrent runner; `diskRecomputeDone` short-circuits the hot poll path after that.
  private volatile boolean diskRecomputeDone = false;
  private final AtomicBoolean recomputeInProgress = new AtomicBoolean(false);
  private final AtomicInteger recomputeAttempts = new AtomicInteger(0);
  private static final int MAX_RECOMPUTE_ATTEMPTS = 3;

  public AiInstallService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService,
      Path aiHomeDir) {
    this.onlineAi = onlineAi;
    this.settingsStore = settingsStore;
    this.knowledgeServer = knowledgeServer;
    this.policyService = policyService;
    this.homeDir = aiHomeDir;
    // Honor JUSTSEARCH_MODELS_DIR (env or sysprop) so Install AI checks the
    // operator-supplied dir for already-present models. When all required
    // models are present, InstallPlanner produces zero downloads and Install
    // AI completes near-instantly. Tempdoc 374 sandbox round 2 finding #2.
    String envModelsDir = io.justsearch.configuration.EnvRegistry.MODELS_DIR.get().orElse(null);
    if (envModelsDir != null && !envModelsDir.isBlank()) {
      Path candidate = Path.of(envModelsDir.trim());
      if (Files.isDirectory(candidate)) {
        log.info("AiInstallService: using JUSTSEARCH_MODELS_DIR={} as models root (env override)", candidate);
        this.modelsDir = candidate;
      } else {
        log.warn("JUSTSEARCH_MODELS_DIR={} does not exist; falling back to default {}/models",
            candidate, homeDir);
        this.modelsDir = homeDir.resolve("models");
      }
    } else {
      this.modelsDir = homeDir.resolve("models");
    }
  }

  /** Production constructor — resolves AI Home from ConfigStore / platform defaults. */
  public AiInstallService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService) {
    this(onlineAi, settingsStore, knowledgeServer, policyService, resolveHomeDir());
  }

  /**
   * Tempdoc 374 alpha.17 R3: late-bind the worker reference. Called from
   * {@code LocalApiServer.lateBindKnowledgeServer} once the Worker bootstrap
   * finishes. Without this, {@link #tryRestartWorkerBestEffort} silently
   * no-ops at end of Install AI and the worker never picks up the new ORT
   * native_path until the user manually relaunches.
   */
  public void setKnowledgeServer(KnowledgeServerBootstrap knowledgeServer) {
    this.knowledgeServer = knowledgeServer;
  }

  /** Package-private accessor for the late-bind regression test. */
  KnowledgeServerBootstrap knowledgeServerForTest() {
    return knowledgeServer;
  }

  public ModelRegistry getManifest() {
    return ModelRegistryLoader.loadFromClasspath(REGISTRY_RESOURCE);
  }

  /**
   * Tempdoc 656 Task 4: the already-resolved models root (honors {@code JUSTSEARCH_MODELS_DIR}
   * per the constructor above), exposed read-only so a preflight check can resolve the same
   * on-disk paths this service uses to install, without re-deriving the env-override logic.
   */
  public Path modelsDir() {
    return modelsDir;
  }

  /** Tempdoc 656 Task 4: the resolved AI home directory, for packages using {@code installRoot}. */
  public Path aiHome() {
    return homeDir;
  }

  public AiInstallStatus getStatus() {
    maybeRecomputeInstalledFromDisk();
    synchronized (lock) {
      reapIfStale();
      return status;
    }
  }

  @Override
  public InstallPlanPreview previewInstallPlan() {
    InstallPlanPreview preview = new InstallPlanPreview();
    ModelRegistry registry = getManifest();
    HardwareProfile hardware = buildHardwareProfile();
    DownloadProfile profile = hardware.downloadProfile();
    InstallIntent intent = installIntent();
    preview.intent = intent.id();
    preview.downloadProfile = profile.name();

    // Reuse the PURE planner (no side effects) — tempdoc 381 §F "show the plan before download".
    InstallPlan plan = InstallPlanner.plan(registry, hardware, intent, modelsDir, homeDir);
    preview.totalDownloadBytes = plan.totalBytes();

    // Bytes still to download, per package id.
    Map<String, Long> downloadByPkg = new HashMap<>();
    for (var dl : plan.downloads()) {
      downloadByPkg.merge(dl.packageId(), dl.sizeBytes(), Long::sum);
    }

    // One estimate per tier, in canonical order.
    Map<CapabilityTier, InstallPlanPreview.TierEstimate> byTier = new LinkedHashMap<>();
    for (CapabilityTier t : CapabilityTier.values()) {
      var te = new InstallPlanPreview.TierEstimate();
      te.tier = t.id();
      te.label = t.label();
      te.includedByIntent = intent.wants(t);
      byTier.put(t, te);
    }
    for (ModelPackage pkg : registry.packages()) {
      CapabilityTier t = pkg.tier();
      if (t == null) {
        continue;
      }
      var te = byTier.get(t);
      ModelVariant variant = pkg.selectVariant(profile);
      long footprint = variant != null ? variant.sizeBytes() : 0L;
      for (var sf : pkg.supportingFiles()) {
        footprint += sf.sizeBytes();
      }
      te.totalBytes += footprint;
      te.downloadBytes += downloadByPkg.getOrDefault(pkg.id(), 0L);
    }
    preview.tiers.addAll(byTier.values());
    return preview;
  }

  /**
   * Tempdoc 562 — durable installed-state projection. On the first status read after boot, if no install
   * has run this session (state {@code idle}, {@code installedFully} false), recompute "is installed" from
   * on-disk model presence by reusing the planner's own already-installed detection: when the plan has
   * nothing left to download (and something already installed), the models are present, so a returning user
   * reads the honest "AI Offline / Start AI" (the runtime is one activate away) instead of a false
   * "Not Installed" that implies the ~10 GB download was lost. This is NOT keyed on the runtime exe alone
   * (the CPU baseline ships bundled, so a fresh pre-download machine has a "default" variant but no model) —
   * the planner knows the chat model + encoders are still missing on a fresh install, so that case correctly
   * stays "Not Installed". The one-shot ({@code diskRecomputeDone}) is consumed only on a definitive answer
   * (a successful plan); a transient failure retries on a later poll, capped.
   */
  private void maybeRecomputeInstalledFromDisk() {
    if (diskRecomputeDone) {
      return; // hot-path short-circuit once a definitive answer was reached.
    }
    synchronized (lock) {
      if (status.installedFully || running.get() || !"idle".equals(status.state)) {
        return;
      }
    }
    // Single concurrent runner — a contender just skips this poll (it sees the result on the next read).
    if (!recomputeInProgress.compareAndSet(false, true)) {
      return;
    }
    try {
      ModelRegistry registry = getManifest();
      HardwareProfile hardware = buildHardwareProfile();
      InstallPlan plan = InstallPlanner.plan(registry, hardware, installIntent(), modelsDir, homeDir);
      // A successful plan is a DEFINITIVE answer (installed or not) — consume the one-shot now.
      diskRecomputeDone = true;
      if (applyInstalledFromPlan(plan, registry)) {
        log.info(
            "AiInstall: recomputed installedFully=true from on-disk model presence after restart (tempdoc 562).");
      }
    } catch (Exception e) {
      // Transient failure (resource/IO/probe) — do NOT consume the one-shot; retry on a later poll, capped
      // so a persistent error cannot re-run the plan forever.
      if (recomputeAttempts.incrementAndGet() >= MAX_RECOMPUTE_ATTEMPTS) {
        diskRecomputeDone = true;
      }
      log.debug("AiInstall on-disk installed recompute skipped (best-effort): {}", e.toString());
    } finally {
      recomputeInProgress.set(false);
    }
  }

  /**
   * Tempdoc 562 — the plan→installed decision, package-private so the positive path can be unit-tested by
   * injecting a plan (staging the registry's full file set on disk would be brittle). "Fully on disk" = the
   * planner found nothing left to download AND something already installed (the latter guards the
   * empty-registry case; the former is the profile-aware "chat model + required encoders present" check, not
   * the bundled runtime exe alone). When so, set {@code installedFully} — re-checking under the lock that no
   * real install run has taken over since the caller's guard. Returns whether it flipped the status.
   */
  boolean applyInstalledFromPlan(InstallPlan plan, ModelRegistry registry) {
    boolean fullyOnDisk = plan.downloads().isEmpty() && !plan.alreadyInstalled().isEmpty();
    if (!fullyOnDisk) {
      return false; // genuinely not (fully) installed — leave the honest "Not Installed".
    }
    synchronized (lock) {
      if (status.installedFully || running.get() || !"idle".equals(status.state)) {
        return false;
      }
      status.packages.clear();
      populateStatusPackages(plan, registry);
      status.installedFully = true;
      touch();
    }
    return true;
  }

  /**
   * Liveness backstop (tempdoc 575 §17 Face C). Install is a <em>polled-state</em> liveness model: the
   * backend owns the state, the FE polls it. If the owner wedges in "running" (no {@code
   * updatedAtEpochMs} progress past {@link #STALE_RUNNING_MS}), reclaim it to a terminal failed state
   * on the next read — so the UI never polls a dead "running" forever (the gap this fixes: install/pack
   * previously had no backstop, unlike the worker's recoverStuckJobs reaper). The owner certifies its
   * own death; the FE's shorter staleness window surfaces a "stalled" badge earlier, while still running.
   */
  private void reapIfStale() {
    if (io.justsearch.app.services.ai.PolledStateLiveness.isStaleRunning(
        status.state, status.updatedAtEpochMs, System.currentTimeMillis(), STALE_RUNNING_MS)) {
      running.set(false);
      fail(
          "STALLED",
          "Install stalled — no progress for over "
              + (STALE_RUNNING_MS / 1000)
              + "s; reclaimed by the liveness backstop (575 §17 Face C).");
    }
  }

  public void startInstall(boolean acceptTerms) {
    if (!acceptTerms) {
      throw new AiInstallException(
          400, ApiErrorCode.TERMS_REQUIRED, "You must accept the model terms before downloading.");
    }
    checkPolicy();
    if (!running.compareAndSet(false, true)) {
      throw new AiInstallException(
          409, ApiErrorCode.INSTALL_ALREADY_RUNNING, "AI install is already running.");
    }
    cancelFlag.set(false);
    Thread.ofVirtual()
        .name("ai-install-v2")
        .start(
            () -> {
              try {
                runInstallInternal();
              } finally {
                running.set(false);
              }
            });
  }

  public void cancel() {
    synchronized (lock) {
      status.cancelRequested = true;
      status.message = "Cancellation requested.";
      touch();
    }
    cancelFlag.set(true);
    DownloadExecutor exec = downloadExecutor;
    if (exec != null) exec.cancel();
  }

  public void repair(boolean acceptTerms) {
    startInstall(acceptTerms);
  }

  // ---------------------------------------------------------------------------
  // Core install flow
  // ---------------------------------------------------------------------------

  private void runInstallInternal() {
    updateState("running", "preflight", "Starting AI install...");
    try {
      Files.createDirectories(homeDir);
      Files.createDirectories(modelsDir);
    } catch (IOException e) {
      fail("INSTALL_IO_ERROR", "Failed to create directories: " + e.getMessage());
      return;
    }

    ModelRegistry registry;
    try {
      registry = getManifest();
    } catch (Exception e) {
      fail("MANIFEST_UNAVAILABLE", "Failed to load model registry: " + e.getMessage());
      return;
    }

    if (policyBlocksDownloads()) {
      fail("DOWNLOADS_DISABLED", "Downloads disabled by administrator policy.");
      return;
    }

    // Build hardware profile
    updateState("running", "plan", "Detecting hardware and planning downloads...");
    HardwareProfile hardware = buildHardwareProfile();
    DownloadProfile profile = hardware.downloadProfile();
    log.info("Hardware profile: gpuDetected={}, cudaFunctional={}, vramBytes={}, profile={}",
        hardware.gpuDetected(), hardware.cudaFunctional(), hardware.vramBytes(), profile);

    // Compute download plan
    InstallPlan plan = InstallPlanner.plan(registry, hardware, installIntent(), modelsDir, homeDir);
    log.info(
        "Install plan: profile={}, downloads={}, skipped={}, alreadyInstalled={}, totalBytes={}",
        plan.profile(),
        plan.downloads().size(),
        plan.skipped().size(),
        plan.alreadyInstalled().size(),
        plan.totalBytes());

    // Populate status
    synchronized (lock) {
      status.downloadProfile = profile.name();
      status.totalBytes = plan.totalBytes();
      status.downloadedBytes = 0;
      status.packages.clear();
      populateStatusPackages(plan, registry);
      touch();
    }

    // Restore llama-server runtime
    updateState("running", "restore_runtime", "Restoring AI runtime...");
    if (!RuntimeRestoreUtil.ensureRuntimePresent(homeDir)) {
      fail("RUNTIME_MISSING", "Bundled AI runtime is missing and could not be restored.");
      return;
    }

    // Download files
    downloadExecutor = new DownloadExecutor(cancelFlag);
    long downloadedSoFar = 0;
    // Per-package cumulative bytes from files that have already finished
    // downloading. Without this, multi-file packages had bytesDownloaded
    // overwritten with each individual file's progress (tempdoc 374
    // sandbox round 2 finding #7).
    Map<String, Long> packageCompletedBytes = new HashMap<>();

    for (InstallPlan.PlannedDownload dl : plan.downloads()) {
      if (cancelFlag.get()) {
        cancelled();
        return;
      }
      updateState("running", "download", "Downloading " + dl.targetPath() + "...");
      updatePackageState(dl.packageId(), "downloading");

      Path targetFile = modelsDir.resolve(dl.targetPath());
      Path partialFile = targetFile.resolveSibling(targetFile.getFileName() + ".partial");
      try {
        Files.createDirectories(targetFile.getParent());
        Files.deleteIfExists(partialFile);
        // Tempdoc 374 sandbox round 4 issue G: BITS leaves BIT*.tmp scratch
        // files when its download fails. The .partial cleanup above only
        // catches our own naming; orphaned BITS tmps would otherwise
        // accumulate across retries (~hundreds of MB on slow connections).
        cleanupBitsTmpFiles(targetFile.getParent());
      } catch (IOException e) {
        failPackage(dl.packageId(), "Failed to prepare download directory: " + e.getMessage());
        continue;
      }

      final long progressBase = downloadedSoFar;
      final long packageBaseBytes = packageCompletedBytes.getOrDefault(dl.packageId(), 0L);
      boolean downloaded =
          downloadExecutor.download(
              dl.url(),
              partialFile,
              (bytes, total) -> {
                synchronized (lock) {
                  status.downloadedBytes = progressBase + bytes;
                  // Report cumulative bytes for the package (prior completed
                  // files + current in-flight). Pass 0 for total so existing
                  // package-level total (set in populateStatusPackages) wins.
                  updatePackageProgress(dl.packageId(), packageBaseBytes + bytes, 0);
                  touch();
                }
              });

      if (!downloaded) {
        if (cancelFlag.get()) {
          cancelled();
          return;
        }
        failPackage(dl.packageId(), "Download failed for " + dl.targetPath());
        continue;
      }

      // Verify
      updatePackageState(dl.packageId(), "verifying");
      try {
        if (dl.sizeBytes() > 0) {
          long size = Files.size(partialFile);
          if (size != dl.sizeBytes()) {
            throw new IllegalStateException(
                "Size mismatch: expected " + dl.sizeBytes() + ", got " + size);
          }
        }
        String got = DownloadExecutor.sha256(partialFile);
        if (!got.equalsIgnoreCase(dl.sha256())) {
          throw new IllegalStateException("SHA-256 mismatch");
        }
      } catch (Exception e) {
        failPackage(dl.packageId(), "Verification failed: " + e.getMessage());
        continue;
      }

      // Atomic move to final location
      try {
        DownloadExecutor.moveAtomicBestEffort(partialFile, targetFile);
      } catch (IOException e) {
        failPackage(dl.packageId(), "Failed to finalize: " + e.getMessage());
        continue;
      }

      // Tempdoc 374 alpha.15 fix B: archive extraction. The cuda-runtime
      // package ships its DLLs in a single zip (too large for the NSIS
      // installer payload). After download + SHA verification the zip is
      // expanded into the same directory; the archive itself stays on disk
      // so the planner's isAlreadyInstalled check skips re-download next
      // time.
      if (dl.extract()) {
        try {
          extractZipInPlace(targetFile, targetFile.getParent());
        } catch (IOException e) {
          failPackage(
              dl.packageId(),
              "Failed to extract " + targetFile.getFileName() + ": " + e.getMessage());
          continue;
        }
      }

      downloadedSoFar += dl.sizeBytes();
      packageCompletedBytes.merge(dl.packageId(), dl.sizeBytes(), Long::sum);
      updatePackageState(dl.packageId(), "installed");
    }

    if (cancelFlag.get()) {
      cancelled();
      return;
    }

    // Check for failures
    long failedCount = countPackagesByState("failed");
    long totalCount = status.packages.size();
    if (failedCount > 0 && failedCount == totalCount) {
      fail("ALL_DOWNLOADS_FAILED", "All packages failed to install.");
      return;
    }

    // Write install contract
    InstallContract contract = buildContract(plan, registry, hardware);
    InstallContractIO.write(contract, homeDir);
    log.info("Install contract written to {}", homeDir.resolve(InstallContract.CONTRACT_FILENAME));

    // Apply settings (LLM + ONNX feature paths so the Head's
    // resolveOnnxFeatures() reports installed features as active rather than
    // reason="not_found").
    updateState("running", "apply", "Applying configuration...");
    // Tempdoc 374 alpha.15: applyCudaServerExe BEFORE applySettings so the
    // applyRuntimeOverrides call inside applySettings picks up the new
    // cuda12 llama-server.exe via ConfigStore (otherwise chat would stay on
    // the default CPU variant until the next app restart picks up the
    // sysprop via maybeAutoSelectCuda12Variant at boot).
    applyCudaServerExe();
    applySettings(registry, plan);
    applyOnnxSettings(registry, plan);
    applyOrtNativePath();

    // Restart worker
    updateState("running", "restart_worker", "Restarting worker...");
    tryRestartWorkerBestEffort();

    // Smoke test (if online AI is allowed and GGUF was downloaded)
    if (profile.includesGguf() && isPolicyOnlineAiAllowed()) {
      updateState("running", "smoke_test", "Running smoke test...");
      if (!smokeTestBestEffort()) {
        return;
      }
    }

    long skippedCount = countPackagesByState("skipped");
    boolean fullyInstalled = failedCount == 0 && skippedCount == 0;

    if (failedCount > 0) {
      long installed = totalCount - failedCount - skippedCount;
      updateState(
          "completed",
          "done",
          "AI installed (" + installed + "/" + totalCount + " packages; " + failedCount + " failed).");
    } else if (skippedCount > 0) {
      // Partial-success path: state is still "completed" (Install AI ran to
      // termination), but installedFully is false so the Brain UI can show
      // a "Installed with limitations" banner. Tempdoc 374 finding #8.
      String skippedLabels = status.packages.stream()
          .filter(ps -> "skipped".equals(ps.state))
          .map(ps -> ps.label != null && !ps.label.isBlank() ? ps.label : ps.packageId)
          .collect(java.util.stream.Collectors.joining(", "));
      updateState(
          "completed",
          "done",
          "Installed with limitations: " + skippedLabels + " skipped on this hardware.");
    } else {
      updateState("completed", "done", "AI installed.");
    }
    synchronized (lock) {
      status.installedFully = fullyInstalled;
      touch();
    }
  }

  // ---------------------------------------------------------------------------
  // Install intent
  // ---------------------------------------------------------------------------

  /**
   * Resolves the install/runtime intent (tempdoc 657) from {@code -Djustsearch.mode} /
   * {@code JUSTSEARCH_MODE}, defaulting to Full Desktop when unset. Read the same way by the plan and
   * the contract, so the recorded intent matches what was actually planned.
   */
  InstallIntent installIntent() {
    return InstallIntent.fromConfig(io.justsearch.configuration.EnvRegistry.MODE.get().orElse(null));
  }

  // ---------------------------------------------------------------------------
  // Hardware profile
  // ---------------------------------------------------------------------------

  HardwareProfile buildHardwareProfile() {
    // Tempdoc 587: read every GPU fact from the ONE composition seam (GpuCapabilityResolver),
    // which folds the CUDA driver-API probe into the NVML/nvidia-smi VRAM+device merge. This
    // replaces the prior split — a direct GpuDriverApiProbe call for cudaFunctional plus a
    // separate GpuCapabilitiesService call for VRAM — so both axes come from one resolver and the
    // raw probes are no longer reached directly (the bypass GpuProbeAccessTest now forecloses).
    boolean cudaFunctional = false;
    long vramBytes = -1;
    try {
      io.justsearch.gpu.GpuCapabilities.Effective effective =
          new io.justsearch.app.services.gpu.GpuCapabilityResolver().snapshot().effective();
      if (effective.cuda() != null && Boolean.TRUE.equals(effective.cuda().functional())) {
        cudaFunctional = true;
      }
      if (effective.totalVramBytes() != null && effective.totalVramBytes() > 0) {
        vramBytes = effective.totalVramBytes();
      }
    } catch (Throwable t) {
      log.debug("GPU capability resolve failed (best-effort): {}", t.getMessage());
    }
    boolean gpuDetected = vramBytes > 0 || cudaFunctional;
    return new HardwareProfile(gpuDetected, cudaFunctional, vramBytes);
  }

  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // Contract generation
  // ---------------------------------------------------------------------------

  private InstallContract buildContract(
      InstallPlan plan, ModelRegistry registry, HardwareProfile hardware) {
    Map<String, InstallContract.InstalledModel> models = new LinkedHashMap<>();

    for (ModelPackage pkg : registry.packages()) {
      // Check if skipped
      boolean skipped =
          plan.skipped().stream().anyMatch(s -> s.packageId().equals(pkg.id()));
      if (skipped) {
        String reason =
            plan.skipped().stream()
                .filter(s -> s.packageId().equals(pkg.id()))
                .findFirst()
                .map(InstallPlan.SkippedPackage::reason)
                .orElse("Skipped");
        models.put(pkg.id(), InstallContract.InstalledModel.skipped(pkg.id(), reason));
        continue;
      }

      ModelVariant variant = pkg.selectVariant(plan.profile());
      if (variant == null) {
        models.put(pkg.id(), InstallContract.InstalledModel.skipped(pkg.id(), "No variant"));
        continue;
      }

      // Collect installed files
      List<String> installedFiles = new ArrayList<>();
      installedFiles.add(variant.filename());
      for (var sf : pkg.supportingFiles()) {
        installedFiles.add(sf.filename());
      }

      models.put(
          pkg.id(),
          new InstallContract.InstalledModel(
              pkg.id(),
              variant.filename(),
              variant.precision(),
              variant.targetEP(),
              pkg.targetDir(),
              variant.sha256(),
              installedFiles,
              false,
              null));
    }

    // Tempdoc 374 alpha.20 Bug M: record the absolute modelsDir so contract
    // path resolution survives cold restart. Pre-alpha.20 the contract only
    // carried relative `targetDir` per package; the `<root>` against which to
    // resolve was looked up at runtime via JUSTSEARCH_MODELS_DIR env var or
    // resolved-config snapshot. On cold restart (GUI launch) the env var
    // doesn't inherit, the snapshot is empty (UiSettings has no modelsDir
    // field), and the runtime fell back to aiHome/models — wrong directory
    // for users who pre-stage models. Recording modelsDir here makes the
    // contract self-describing.
    return new InstallContract(
        2, System.currentTimeMillis(), hardware, plan.profile(), models,
        modelsDir != null ? modelsDir.toAbsolutePath().normalize() : null,
        installIntent());
  }

  // ---------------------------------------------------------------------------
  // Settings application
  // ---------------------------------------------------------------------------

  private void applySettings(ModelRegistry registry, InstallPlan plan) {
    if (settingsStore == null) return;
    if (!plan.profile().includesGguf()) return; // No chat model → nothing to configure

    ModelPackage chat = registry.findPackage("chat");
    if (chat == null) return;
    ModelVariant chatVariant = chat.selectVariant(plan.profile());
    if (chatVariant == null) return;

    Path chatModelPath = modelsDir.resolve(chat.targetDir()).resolve(chatVariant.filename());
    if (!Files.isRegularFile(chatModelPath)) return;

    UiSettings s = settingsStore.load();
    s.setLlmModelPath(chatModelPath.toAbsolutePath().toString());
    settingsStore.save(s);

    SystemPropertyUtils.setSysPropIfBlank(
        "justsearch.llm.model_path", chatModelPath.toAbsolutePath().toString());

    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);

    OnlineAiService onlineAi = this.onlineAi;
    if (onlineAi instanceof OnlineAiRuntimeControl control) {
      control.applyRuntimeOverrides(
          s.getLlmModelPath(),
          s.getContextLength(),
          s.getGpuLayers(),
          OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);
    }
  }

  /**
   * Writes per-feature ONNX model paths to UiSettings + system properties so
   * the Head's {@code RuntimeActivationService.resolveOneOnnxFeature} sees
   * step 2 (explicit_path) hit and stops reporting reason="not_found" for
   * installed features. Mirrors {@link #applySettings} for the LLM path.
   *
   * <p>Only writes for packages that are present on disk after install (i.e.,
   * not skipped/failed). Each package's models live at
   * {@code modelsDir / pkg.targetDir()}.
   */
  private void applyOnnxSettings(ModelRegistry registry, InstallPlan plan) {
    if (settingsStore == null) return;

    UiSettings s = settingsStore.load();
    boolean dirty = false;

    // Map package id → (UiSettings setter, sysprop key). Only ONNX features —
    // chat is handled by applySettings(); pipeline-only packages have no
    // head-side path key.
    record OnnxFeature(String pkgId, java.util.function.Consumer<String> setter, String sysProp) {}
    List<OnnxFeature> features = List.of(
        new OnnxFeature("embedding", s::setEmbedOnnxModelPath, "justsearch.embed.onnx.model_path"),
        new OnnxFeature("reranker", s::setRerankerModelPath, "justsearch.rerank.model_path"),
        new OnnxFeature("ner", s::setNerModelPath, "justsearch.ner.model_path"),
        new OnnxFeature("splade", s::setSpladeModelPath, "justsearch.splade.model_path"),
        new OnnxFeature(
            "citation-scorer", s::setCitationScorerModelPath, "justsearch.citation.scorer.model_path"));

    for (OnnxFeature feature : features) {
      ModelPackage pkg = registry.findPackage(feature.pkgId());
      if (pkg == null) continue;
      // Skip if Install AI didn't actually install this package.
      if (isPackageSkippedOrFailed(pkg.id(), plan)) continue;

      Path modelDir = modelsDir.resolve(pkg.targetDir());
      if (!Files.isDirectory(modelDir)) continue;

      String absolute = modelDir.toAbsolutePath().toString();
      feature.setter().accept(absolute);
      SystemPropertyUtils.setSysPropIfBlank(feature.sysProp(), absolute);
      dirty = true;
    }

    if (dirty) {
      settingsStore.save(s);
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);
    }
  }

  /**
   * Tempdoc 374 alpha.15 follow-up: write {@code justsearch.server.exe} pointing at
   * the cuda12 llama-server binary (now extracted by the cuda-runtime Install AI
   * package) so the next {@link #applySettings} → {@code applyRuntimeOverrides}
   * call routes chat through the cuda12 variant instead of the default CPU
   * binary.
   *
   * <p>Why this is needed: {@link io.justsearch.ui.HeadlessApp#maybeAutoSelectCuda12Variant}
   * runs once at boot, before Install AI populates the cuda12 dir. On a fresh
   * alpha.15 install (no prior alpha) the boot-time auto-select fires before
   * the cuda12 binary exists and SKIPs. Without this method, chat would stay
   * on the default CPU variant until the next app restart (when boot-time
   * auto-select sees the populated dir). This method closes the gap inside a
   * single Install-AI-then-apply cycle.
   *
   * <p>Respects user overrides: if the server.exe sysprop is already set with
   * a non-{@code auto_selected_cuda12} source (env var, settings.json,
   * operator config), the explicit choice wins.
   */
  private void applyCudaServerExe() {
    Path cuda12Exe =
        homeDir
            .resolve("native-bin/llama-server/variants/cuda12")
            .resolve("llama-server.exe");
    if (!Files.isRegularFile(cuda12Exe)) {
      log.debug(
          "alpha.15: cuda12 llama-server.exe not at {} — skipping (cuda-runtime"
              + " package skipped, CPU-only profile, or extract failed)",
          cuda12Exe);
      return;
    }
    // observations.md fix: migrated from raw sysprop reads to typed
    // EnvRegistry.SERVER_EXE / SERVER_EXE_SOURCE. Closes the alpha.17
    // build-fix exemption marker.
    String existingExe =
        System.getProperty(io.justsearch.configuration.EnvRegistry.SERVER_EXE.sysProp(), "");
    String existingSrc =
        System.getProperty(
            io.justsearch.configuration.EnvRegistry.SERVER_EXE_SOURCE.sysProp(), "");
    if (!existingExe.isBlank() && !"auto_selected_cuda12".equals(existingSrc)) {
      log.info(
          "alpha.15: {} already set (source={}); respecting user override",
          io.justsearch.configuration.EnvRegistry.SERVER_EXE.sysProp(),
          existingSrc);
      return;
    }
    String absPath = cuda12Exe.toAbsolutePath().toString();
    System.setProperty(io.justsearch.configuration.EnvRegistry.SERVER_EXE.sysProp(), absPath);
    System.setProperty(
        io.justsearch.configuration.EnvRegistry.SERVER_EXE_SOURCE.sysProp(), "auto_selected_cuda12");

    UiSettings s = settingsStore.load();
    s.setServerExecutablePath(absPath);
    settingsStore.save(s);
    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);
    log.info("alpha.15: server.exe set to cuda12 variant: {}", absPath);
  }

  /**
   * Tempdoc 374 alpha.14 fix B: write the {@code justsearch.onnxruntime.native_path}
   * sysprop pointing at the llama.cpp cuda12 variant directory. ORT's CUDA EP
   * DLL (auto-extracted from the onnxruntime-gpu JAR to a JVM temp dir) needs
   * cuBLAS + cuBLASLt + cuRT at LoadLibrary time;
   * {@link io.justsearch.ort.OrtCudaHelper#copyCudaDllsToOrtTempDir} copies
   * them next to the EP DLL.
   *
   * <p>Pre-alpha.14 the precondition guard checked for ORT EP DLLs in this
   * dir (which never live there — they ship in the JAR), tripped on every
   * invocation, and silently disabled the entire fix. Alpha.14 uses
   * {@link io.justsearch.ort.OrtCudaHelper#checkMissingCudaRuntimeDlls} which
   * checks only the runtime DLLs that actually live in cuda12.
   *
   * <p><b>Known limitation (deferred to alpha.15):</b> ORT's full CUDA
   * dependency surface includes cuFFT (and possibly cuRand/cuSparse/cuSolver/
   * cuDNN for some models). The bundled cuda12 variant is sized for
   * llama.cpp's needs, which are a strict subset of ORT's. Bundling the
   * additional DLLs into NSIS pushes the staged sidecar past the 32-bit
   * single-file mmap limit (~1.93 GB; tempdoc 374 G21). Alpha.15 will move
   * the supplemental CUDA runtime DLLs to an Install AI download package so
   * they reach the cuda12 dir post-install without bloating the installer.
   * Until then, fresh installs see ORT GPU init fail with
   * {@code cufft64_11.dll missing} even though this fix runs correctly —
   * the agent's round-5 experimental verification confirmed cuFFT is the
   * next blocker.
   */
  private void applyOrtNativePath() {
    Path cuda12Dir = homeDir.resolve("native-bin/llama-server/variants/cuda12");
    writeOrtNativePathSysprop(cuda12Dir, settingsStore::load);
  }

  /**
   * Tempdoc 374 alpha.14 fix: extracted from {@link #applyOrtNativePath} so a
   * regression test can exercise the production codepath without spinning up
   * the full {@link AiInstallService}. Previously the alpha.13 fix B
   * precondition was wrong-headed (called {@code checkMissingCudaDlls} which
   * looked for the ORT EP DLLs — those live in the JAR, not in cuda12/),
   * tripped on every invocation, and silently disabled the entire fix. The
   * agent's sandbox round 5 finding pinpointed it; the lack of a regression
   * test on this codepath was a CLAUDE.md "audit-driven fixes need a test"
   * violation.
   *
   * <p>Returns {@code true} if the sysprop was newly set (or was already set
   * by the user), {@code false} if the precondition guard prevented it.
   *
   * @param cuda12Dir directory containing the bundled CUDA runtime DLLs
   *     (cudart64_12.dll, cublas64_12.dll, cublasLt64_12.dll). Typically
   *     {@code %APPDATA%/io.justsearch.shell/native-bin/llama-server/variants/cuda12}.
   * @param settingsLoader supplier for the current UiSettings (used by the
   *     ConfigStore rebuild). Test stubs can return a default UiSettings.
   */
  static boolean writeOrtNativePathSysprop(
      Path cuda12Dir, java.util.function.Supplier<UiSettings> settingsLoader) {
    if (cuda12Dir == null || !Files.isDirectory(cuda12Dir)) {
      log.debug(
          "alpha.14 fix B: cuda12 variant dir not found at {} — skipping ORT native_path"
              + " write (CPU-only build or variant not staged)",
          cuda12Dir);
      return false;
    }
    // Validate the CUDA *runtime* DLLs that actually live in cuda12 — distinct
    // from the ORT EP DLLs (those auto-extract from onnxruntime-gpu.jar to a
    // JVM temp dir at runtime; alpha.13 mistakenly checked for them here).
    var missing = io.justsearch.ort.OrtCudaHelper.checkMissingCudaRuntimeDlls(cuda12Dir);
    if (!missing.isEmpty()) {
      log.warn(
          "alpha.14 fix B: cuda12 variant dir {} is missing CUDA runtime DLLs {} —"
              + " not setting justsearch.onnxruntime.native_path. ONNX encoders will"
              + " fall back to CPU.",
          cuda12Dir,
          missing);
      return false;
    }
    String absPath = cuda12Dir.toAbsolutePath().toString();
    SystemPropertyUtils.setSysPropIfBlank("justsearch.onnxruntime.native_path", absPath);
    UiSettings s = settingsLoader != null ? settingsLoader.get() : null;
    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);
    log.info("alpha.14 fix B: ORT native path set to {}", absPath);
    return true;
  }

  /** True if the package was in plan.skipped() OR a per-package status reports failed. */
  private boolean isPackageSkippedOrFailed(String pkgId, InstallPlan plan) {
    for (var sp : plan.skipped()) {
      if (pkgId.equals(sp.packageId())) return true;
    }
    return status.packages.stream()
        .anyMatch(ps -> pkgId.equals(ps.packageId) && "failed".equals(ps.state));
  }

  // ---------------------------------------------------------------------------
  // Worker restart and smoke test
  // ---------------------------------------------------------------------------

  private void tryRestartWorkerBestEffort() {
    if (knowledgeServer == null || knowledgeServer.spawner() == null) return;
    try {
      knowledgeServer.spawner().restart();
      long expectedPid = knowledgeServer.spawner().getWorkerPid();
      try {
        knowledgeServer.client().reconnect(expectedPid);
        knowledgeServer.client().resetCircuitBreaker();
      } catch (Exception e) {
        log.debug("Worker client reconnect failed (best-effort)", e);
      }
    } catch (Exception e) {
      log.warn("Worker restart failed (best-effort): {}", e.getMessage());
    }
  }

  private boolean smokeTestBestEffort() {
    OnlineAiService onlineAi = this.onlineAi;
    try {
      onlineAi.switchToOnlineMode();
      String result = onlineAi.askQuestion("Reply with exactly OK.", "OK").get(60, TimeUnit.SECONDS);
      if (result == null || result.isBlank()) {
        fail("SMOKE_TEST_FAILED", "Smoke test failed: empty response");
        return false;
      }
      return true;
    } catch (Exception e) {
      fail("SMOKE_TEST_FAILED", "Smoke test failed: " + e.getMessage());
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Policy helpers
  // ---------------------------------------------------------------------------

  private void checkPolicy() {
    if (policyService == null) return;
    try {
      EffectivePolicy p = policyService.snapshot();
      if (p != null && !p.downloadsEnabled()) {
        throw new AiInstallException(
            403, ApiErrorCode.DOWNLOADS_DISABLED, "Downloads disabled by administrator policy.");
      }
    } catch (AiInstallException e) {
      throw e;
    } catch (Exception e) {
      log.debug("Policy snapshot failed (best-effort)", e);
    }
  }

  private boolean policyBlocksDownloads() {
    if (policyService == null) return false;
    try {
      EffectivePolicy p = policyService.snapshot();
      return p != null && !p.downloadsEnabled();
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isPolicyOnlineAiAllowed() {
    if (policyService == null) return true;
    try {
      EffectivePolicy p = policyService.snapshot();
      return p == null || p.onlineAiEnabled();
    } catch (Exception e) {
      return true;
    }
  }

  // ---------------------------------------------------------------------------
  // Status management
  // ---------------------------------------------------------------------------

  private void updateState(String newState, String newPhase, String msg) {
    synchronized (lock) {
      status.state = newState;
      status.phase = newPhase;
      status.message = msg == null ? "" : msg;
      if (status.startedAtEpochMs <= 0 && "running".equalsIgnoreCase(newState)) {
        status.startedAtEpochMs = System.currentTimeMillis();
      }
      touch();
    }
  }

  private void fail(String errorCode, String message) {
    log.warn("AI install failed [{}]: {}", errorCode, message);
    synchronized (lock) {
      status.state = "failed";
      status.errorCode = errorCode;
      status.lastError = message;
      status.message = message;
      touch();
    }
  }

  private void cancelled() {
    updateState("cancelled", status.phase, "Cancelled.");
  }

  private void touch() {
    status.updatedAtEpochMs = System.currentTimeMillis();
  }

  private void populateStatusPackages(InstallPlan plan, ModelRegistry registry) {
    // Packages with downloads
    for (var dl : plan.downloads()) {
      if (findPackageStatus(dl.packageId()) != null) continue;
      ModelPackage pkg = registry.findPackage(dl.packageId());
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = dl.packageId();
      ps.label = pkg != null ? pkg.label() : dl.packageId();
      ps.tier = tierId(pkg);
      ps.state = "pending";
      ps.bytesTotal = plan.downloads().stream()
          .filter(d -> d.packageId().equals(dl.packageId()))
          .mapToLong(InstallPlan.PlannedDownload::sizeBytes)
          .sum();
      status.packages.add(ps);
    }
    // Already installed
    for (String id : plan.alreadyInstalled()) {
      ModelPackage pkg = registry.findPackage(id);
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = id;
      ps.label = pkg != null ? pkg.label() : id;
      ps.tier = tierId(pkg);
      ps.state = "installed";
      status.packages.add(ps);
    }
    // Skipped
    for (var sk : plan.skipped()) {
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = sk.packageId();
      ModelPackage pkg = registry.findPackage(sk.packageId());
      ps.label = pkg != null ? pkg.label() : sk.packageId();
      ps.tier = tierId(pkg);
      ps.state = "skipped";
      ps.skipReason = sk.reason();
      status.packages.add(ps);
    }
  }

  /** The package's capability-tier id (tempdoc 657), or {@code null} if the package/tier is unknown. */
  private static String tierId(ModelPackage pkg) {
    return pkg != null && pkg.tier() != null ? pkg.tier().id() : null;
  }

  private void updatePackageState(String packageId, String state) {
    synchronized (lock) {
      var ps = findPackageStatus(packageId);
      if (ps != null) {
        // A package failure is terminal for this install run. Later files may
        // proceed, but the aggregate must stay failed so installedFully cannot lie.
        if ("failed".equals(ps.state)) {
          return;
        }
        ps.state = state;
      }
      touch();
    }
  }

  private void updatePackageProgress(String packageId, long bytes, long total) {
    var ps = findPackageStatus(packageId);
    if (ps != null) {
      if ("failed".equals(ps.state)) {
        return;
      }
      ps.bytesDownloaded = Math.max(0, bytes);
      if (total > 0) ps.bytesTotal = total;
    }
  }

  private void failPackage(String packageId, String message) {
    log.warn("Package install failed [{}]: {}", packageId, message);
    synchronized (lock) {
      var ps = findPackageStatus(packageId);
      if (ps != null) {
        ps.state = "failed";
        ps.error = message;
      }
      touch();
    }
  }

  private AiInstallStatus.PackageStatus findPackageStatus(String packageId) {
    for (var ps : status.packages) {
      if (packageId.equalsIgnoreCase(ps.packageId)) return ps;
    }
    return null;
  }

  /**
   * Tempdoc 374 alpha.15 fix B: extract a zip archive into {@code targetDir},
   * skipping entries that already exist (idempotent re-runs). Used by the
   * cuda-runtime package to expand the supplemental CUDA DLLs (cuFFT et al.)
   * into the cuda12 variant dir post-download.
   *
   * <p>Defensive against zip-slip: rejects entries whose normalized path
   * escapes {@code targetDir} (e.g., {@code ../../etc/passwd}).
   *
   * @param zipFile the archive to extract
   * @param targetDir the directory entries are extracted into (entries are
   *     resolved relative to this dir; nested zip entries preserve their path
   *     under it)
   */
  static void extractZipInPlace(Path zipFile, Path targetDir) throws IOException {
    if (zipFile == null || !Files.isRegularFile(zipFile)) {
      throw new IOException("Archive not found: " + zipFile);
    }
    Path normalizedTarget = targetDir.toAbsolutePath().normalize();
    Files.createDirectories(normalizedTarget);
    int extracted = 0;
    try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(normalizedTarget)) {
          throw new IOException(
              "Refusing zip entry that escapes target directory: " + entry.getName());
        }
        if (Files.exists(resolved)) continue; // idempotent re-extract
        Files.createDirectories(resolved.getParent());
        try (var in = zip.getInputStream(entry)) {
          Files.copy(in, resolved);
        }
        extracted++;
      }
    }
    log.info(
        "Extracted {} new entries from {} to {}",
        extracted,
        zipFile.getFileName(),
        normalizedTarget);
  }

  /**
   * Removes orphaned {@code *.tmp} files in a download target directory before
   * starting a download. Catches BITS scratch files (named like {@code BIT411F.tmp})
   * that BITS leaves behind when its job fails or is cancelled.
   * Tempdoc 374 sandbox round 4 issue G.
   */
  private static void cleanupBitsTmpFiles(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return;
    try (var stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tmp"))
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                  log.debug("Removed orphaned BITS tmp file: {}", p);
                } catch (IOException ignored) {
                  // best-effort
                }
              });
    } catch (IOException ignored) {
      // best-effort; the failure won't block the download itself
    }
  }

  private long countPackagesByState(String state) {
    return status.packages.stream().filter(p -> state.equals(p.state)).count();
  }

  private static Path resolveHomeDir() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path fromEnv = cs != null ? cs.get().paths().home() : null;
      if (fromEnv != null) return fromEnv;
    } catch (Exception e) {
      log.debug("Failed to resolve AI home dir from ConfigStore (best-effort)", e);
    }
    try {
      return PlatformPaths.resolveDataDir();
    } catch (Exception e) {
      return Path.of(System.getProperty("user.dir"));
    }
  }
}
