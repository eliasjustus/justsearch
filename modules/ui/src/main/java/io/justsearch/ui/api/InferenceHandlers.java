/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramFlagsUtil;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.status.InferenceGpuView;
import io.justsearch.app.api.status.InferenceStatusResponseBuilder;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.EnterprisePolicyService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator handling inference-related HTTP endpoints.
 *
 * <p>Extracted from {@link LocalApiServer} to reduce class size. Handles all endpoints registered
 * via {@link io.justsearch.ui.api.routes.InferenceRoutes}.
 */
final class InferenceHandlers {
  private static final Logger log = LoggerFactory.getLogger(InferenceHandlers.class);

  private final OnlineAiService onlineAiService;
  private volatile KnowledgeServerBootstrap knowledgeServer;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; nvidia-smi availability
  // is read from gpuCapabilitiesService.snapshot().nvidiaSmi().available().
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final EnterprisePolicyService enterprisePolicyService;
  private final io.justsearch.app.services.settings.UiSettingsStore settingsStore;
  private final Runnable offlineProcessingTrigger;
  private final Telemetry telemetry;
  // Tempdoc 656 O2: nullable — lets a failed online-mode transition project a SPECIFIC reason onto
  // the runtime manifest's ai.pendingReason (the mode-transition path otherwise shows generic
  // "Inference offline"; Tasks 0-5 wired only the RuntimeActivationService path).
  private final InferenceCapability inferenceCapability;

  InferenceHandlers(
      OnlineAiService onlineAiService,
      KnowledgeServerBootstrap knowledgeServer,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService enterprisePolicyService,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      Runnable offlineProcessingTrigger,
      Telemetry telemetry,
      InferenceCapability inferenceCapability) {
    this.onlineAiService = onlineAiService;
    this.knowledgeServer = knowledgeServer;
    this.gpuCapabilitiesService = gpuCapabilitiesService;
    this.enterprisePolicyService = enterprisePolicyService;
    this.settingsStore = settingsStore;
    this.offlineProcessingTrigger = offlineProcessingTrigger;
    this.telemetry = telemetry;
    this.inferenceCapability = inferenceCapability;
  }

  /** Late-binds the Knowledge Server after async Worker startup. */
  void setKnowledgeServer(KnowledgeServerBootstrap ks) {
    this.knowledgeServer = ks;
  }

  /**
   * Handles GET /api/inference/status - returns current inference mode and queue sizes.
   *
   * <p>Tempdoc 663 §L/Stage 4 — builds the typed {@link InferenceStatusResponse} record instead of
   * a hand-built {@code Map}. Every field/condition below is unchanged from the prior Map-based
   * version (see git history if diffing behavior); only the assembly mechanism changed.
   */
  void handleInferenceStatus(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    InferenceStatusResponseBuilder builder = InferenceStatusResponseBuilder.builder()
        .mode(onlineAi.getCurrentMode())
        .available(onlineAi.isAvailable())
        .starting(onlineAi.isStartingUp())
        .llmContextTokens(onlineAi.llmContextTokens())
        .configuredContextTokens(onlineAi.configuredContextTokens())
        .embeddingQueueSize(countPendingEmbeddings())
        .vduQueueSize(countPendingVdu());

    // External server adoption diagnostics, CUDA warnings, and startup timer (best-effort; additive fields).
    if (onlineAi instanceof io.justsearch.app.api.OnlineAiRuntimeIntrospection introspection) {
      try {
        var ext = introspection.externalServerStatus();
        if (ext != null) {
          builder.externalServer(ext);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        String cudaWarning = introspection.cudaRuntimeWarning();
        if (cudaWarning != null && !cudaWarning.isBlank()) {
          builder.cudaRuntimeWarning(cudaWarning);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        long startupMs = introspection.lastStartupDurationMs();
        if (startupMs >= 0) {
          builder.lastStartupDurationMs(startupMs);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        builder.hasVisionCapability(introspection.hasVisionCapability());
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        String modelId = introspection.activeModelId();
        if (modelId != null && !modelId.isBlank()) {
          builder.activeModelId(modelId);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        // Tempdoc 518 Appendix F W3.3 — generation counter (frontend detects mid-session restart).
        long gen = introspection.currentGeneration();
        if (gen >= 0) {
          builder.generation(gen);
        }
      } catch (Exception ignored) {
        // best-effort
      }
    }

    // Hardware capabilities. Tempdoc 374 alpha.14 fix P1-A: drive
    // `cudaAvailable` and `vramDescription` from the NVML-first effective
    // capability snapshot rather than the legacy nvidia-smi shell-out probe.
    // Pre-alpha.14 this endpoint reported `cudaAvailable: false` and
    // `vramDescription: "Unknown (nvidia-smi not available)"` on every host
    // without nvidia-smi.exe on PATH — including the round-5 sandbox where
    // chat was actually running on GPU at 64 tok/s with 8.6 GB VRAM held.
    // The legacy `nvidia-smi` probe and the `nvidiaSmiAvailable` field stay,
    // both as a diagnostic signal and to preserve frontend compatibility for
    // callers that explicitly want the legacy answer.
    // Tempdoc 374 alpha.27: read nvidia-smi availability from the snapshot's
    // nvidiaSmi() accessor instead of a direct VramDetector reference. The
    // snapshot's NvidiaSmi probe runs the same nvidia-smi shell-out internally.
    GpuCapabilities snapshot = null;
    try {
      snapshot = gpuCapabilitiesService.snapshot();
    } catch (Exception ignored) {
      // best-effort
    }
    boolean nvidiaSmiAvailable =
        snapshot != null && snapshot.nvidiaSmi() != null && snapshot.nvidiaSmi().available();
    boolean cudaAvailable = snapshot != null
        && snapshot.effective() != null
        && snapshot.effective().cudaAvailable();
    Long effectiveVramBytes = snapshot != null && snapshot.effective() != null
        ? snapshot.effective().totalVramBytes()
        : null;
    String vramDescription = effectiveVramBytes != null
        ? String.format(
            java.util.Locale.ROOT,
            "%.1f GB",
            effectiveVramBytes / (1024.0 * 1024.0 * 1024.0))
        : "Unknown";
    String vramDetectionSource = snapshot != null && snapshot.effective() != null
        ? snapshot.effective().source()
        : (nvidiaSmiAvailable ? "nvidia-smi" : "none");

    boolean nvmlAvailable = false;
    Long nvmlTotalVramBytes = null;
    String nvmlDriverVersion = null;
    try {
      var nvml = snapshot != null ? snapshot.nvml() : null;
      if (nvml != null) {
        nvmlAvailable = nvml.available();
        if (nvmlAvailable) {
          nvmlTotalVramBytes = nvml.totalVramBytes();
          nvmlDriverVersion = nvml.driverVersion();
        }
      }
    } catch (Exception ignored) {
      nvmlAvailable = false;
    }

    // Tempdoc 623 U7: record the pinned CUDA major (a constant — no ORT init) so the
    // benchmark-release hardware projection can publish it. The ORT *version* is captured
    // WORKER-side (where ORT is initialized) and surfaced via the health effective_config map
    // into /api/debug/state — NOT here, because the Head runs no ORT sessions. Best-effort.
    String cudaVersion = null;
    try {
      cudaVersion = io.justsearch.ort.OrtCudaHelper.CUDA_TOOLKIT_MAJOR;
    } catch (Exception ignored) {
      // constant lookup is informational only.
    }

    builder.gpu(new InferenceGpuView(
        cudaAvailable,
        effectiveVramBytes,
        vramDescription,
        vramDetectionSource,
        nvidiaSmiAvailable,
        nvmlAvailable,
        nvmlTotalVramBytes,
        nvmlDriverVersion,
        cudaVersion));

    // computeHardwareTier still wants a VRAM number; pass the effective bytes
    // (NVML-first, with nvidia-smi fallback handled internally by the snapshot).
    // Tempdoc 374 alpha.27: dropped the explicit smiVramBytes path — the snapshot's
    // effective() value is already the merged NVML-first / smi-fallback reading.
    Long smiOnly = snapshot != null && snapshot.nvidiaSmi() != null
        ? snapshot.nvidiaSmi().totalVramBytes()
        : null;
    long tierVramBytes = effectiveVramBytes != null ? effectiveVramBytes
        : (smiOnly != null ? smiOnly : -1L);
    builder.tier(computeHardwareTier(tierVramBytes, onlineAi.isAvailable(), onlineAi.isStartingUp()));

    ctx.json(builder.build());
  }

  /**
   * Handles GET /api/inference/transitions - returns the N most recent mode transitions
   * (success + failure) recorded by the runtime's transition-history ring buffer.
   *
   * <p>Tempdoc 518 Appendix F W3.2. Query param: {@code limit} (default 10, max 20). Response
   * shape: {@code {"transitions": [{timestampMs, fromMode, toMode, reason, success, durationMs,
   * wireCode}, ...]}}.
   */
  void handleInferenceTransitions(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    int limit = 10;
    try {
      String raw = ctx.queryParam("limit");
      if (raw != null && !raw.isBlank()) {
        limit = Integer.parseInt(raw);
      }
    } catch (NumberFormatException ignored) {
      // best-effort; default to 10
    }
    if (limit < 0) limit = 0;
    if (limit > 20) limit = 20;

    List<Map<String, Object>> transitions = new ArrayList<>();
    if (onlineAi instanceof io.justsearch.app.api.OnlineAiRuntimeIntrospection introspection) {
      try {
        for (io.justsearch.app.api.OnlineAiRuntimeIntrospection.TransitionRecord rec :
            introspection.recentTransitions(limit)) {
          Map<String, Object> row = new HashMap<>();
          row.put("timestampMs", rec.timestampMs());
          row.put("fromMode", rec.fromMode());
          row.put("toMode", rec.toMode());
          row.put("reason", rec.reason());
          row.put("success", rec.success());
          row.put("durationMs", rec.durationMs());
          if (rec.wireCode() != null) {
            row.put("wireCode", rec.wireCode());
          }
          transitions.add(row);
        }
      } catch (Exception ignored) {
        // best-effort
      }
    }
    ctx.json(Map.of("transitions", transitions));
  }

  /**
   * Handles GET /api/inference/failures - returns the N most recent inference-runtime failures
   * recorded by the {@link io.justsearch.app.api.OnlineAiRuntimeIntrospection} ring buffer.
   *
   * <p>Tempdoc 518 Appendix F W2.1. Query param: {@code limit} (default 5, max 20). Response
   * shape: <code>{"failures": [{timestampMs, category, wireCode, detail}, ...]}</code>.
   */
  void handleInferenceFailures(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    int limit = 5;
    try {
      String raw = ctx.queryParam("limit");
      if (raw != null && !raw.isBlank()) {
        limit = Integer.parseInt(raw);
      }
    } catch (NumberFormatException ignored) {
      // best-effort; default to 5
    }
    if (limit < 0) limit = 0;
    if (limit > 20) limit = 20;

    List<Map<String, Object>> failures = new ArrayList<>();
    if (onlineAi instanceof io.justsearch.app.api.OnlineAiRuntimeIntrospection introspection) {
      try {
        for (io.justsearch.app.api.OnlineAiRuntimeIntrospection.FailureRecord rec :
            introspection.recentFailures(limit)) {
          Map<String, Object> row = new HashMap<>();
          row.put("timestampMs", rec.timestampMs());
          row.put("category", rec.category());
          row.put("wireCode", rec.wireCode());
          row.put("detail", rec.detail());
          failures.add(row);
        }
      } catch (Exception ignored) {
        // best-effort; return whatever we accumulated
      }
    }
    ctx.json(Map.of("failures", failures));
  }

  /**
   * Handles GET /api/gpu/capabilities - returns NVML-first GPU capability snapshot (with fallback
   * details).
   */
  void handleGpuCapabilities(Context ctx) {
    try {
      ctx.json(gpuCapabilitiesService.snapshot());
    } catch (Exception e) {
      log.warn("Failed to compute GPU capabilities (best-effort)", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.GPU_CAPABILITIES_FAILED, "Failed to compute GPU capabilities", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  static String computeHardwareTier(
      long totalVramBytes, boolean onlineAvailable, boolean onlineStarting) {
    // If we can't detect VRAM, distinguish CPU-only vs GPU-unknown based on whether Online AI is
    // running.
    if (totalVramBytes < 0) {
      return (onlineAvailable || onlineStarting) ? "gpu_unknown" : "cpu_only";
    }

    // Delegate to VramFlagsUtil for consistent tier detection across codebase.
    String tier = VramFlagsUtil.detectVramTier(totalVramBytes);
    return switch (tier) {
      case "12gb_plus" -> "gpu_12gb_plus";
      case "8gb" -> "gpu_8gb";
      case "4gb", "under_4gb" -> "gpu_lt_8gb";
      default -> "gpu_unknown";
    };
  }

  /** Handles POST /api/inference/mode - switches between online and indexing modes. */
  @SuppressWarnings("unchecked")
  void handleSetInferenceMode(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    Map<String, Object> body;
    try {
      body = ctx.bodyAsClass(Map.class);
    } catch (Exception e) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_JSON, "Invalid JSON body", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    String mode = (String) body.get("mode");
    if (mode == null || mode.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Missing 'mode' field", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      if ("online".equalsIgnoreCase(mode)) {
        try {
          if (enterprisePolicyService != null
              && !enterprisePolicyService.snapshot().onlineAiEnabled()) {
            Map<String, Object> policyErr = ApiErrorHandler.toResponse(ApiErrorCode.POLICY_ONLINE_AI_DISABLED, "Online AI is disabled by administrator policy.", telemetry, ApiErrorHandler.routeOf(ctx));
            policyErr.put("mode", mode);
            ctx.status(403).json(policyErr);
            return;
          }
        } catch (Exception ignored) {
          // best-effort; do not weaken enforcement elsewhere
        }
        onlineAi.switchToOnlineMode();
      } else if ("indexing".equalsIgnoreCase(mode)) {
        onlineAi.switchToIndexingMode();
      } else {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Invalid mode. Use 'online' or 'indexing'", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      ctx.json(Map.of("success", true, "mode", onlineAi.getCurrentMode()));
    } catch (Exception e) {
      log.error("Failed to switch inference mode to: {}", mode, e);
      String msg = e.getMessage() != null ? e.getMessage() : e.toString();

      // Check cause chain for typed ModeTransitionException (wrapped by OnlineAiServiceImpl)
      ModeTransitionException mte = findCause(e, ModeTransitionException.class);

      // Tempdoc 656 O2: project the SPECIFIC failure cause onto the runtime manifest. The rollback's
      // mode-change listener already fired a generic OFFLINE→"Inference offline"; this runs after
      // switchToOnlineMode returned, so it is the last write and wins. Only meaningful for an
      // online-mode failure (indexing failures don't change the AI-availability reason). Skips when
      // the capability is currently READY (an unrelated failure must not regress a working runtime).
      if (inferenceCapability != null
          && "online".equalsIgnoreCase(mode)
          && inferenceCapability.health() != CapabilityHealth.READY) {
        inferenceCapability.transition(CapabilityHealth.OFFLINE, mapModeReason(mte).code());
      }
      if (mte != null
          && mte.reason() == ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED) {
        Map<String, Object> payload = ApiErrorHandler.toResponse(
            ApiErrorCode.POLICY_EXTERNAL_SERVER_DISALLOWED, msg, telemetry, ApiErrorHandler.routeOf(ctx));
        payload.put("mode", mode);
        ctx.status(403).json(payload);
        return;
      }

      Map<String, Object> payload = ApiErrorHandler.toResponse(
          ApiErrorCode.MODE_SWITCH_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx));
      payload.put("mode", mode);
      payload.put("causes", buildCauseChain(e));

      // Precondition failures (missing model, insufficient VRAM) are caller-fixable
      // configuration issues, not server faults. Return 412 so middleware/clients
      // don't retry as if it's a transient 5xx and so the UI can render a sticky
      // "fix this configuration" banner.
      int statusCode = 500;
      if (mte != null) {
        ModeTransitionException.Reason reason = mte.reason();
        if (reason == ModeTransitionException.Reason.INVALID_CONFIG
            || reason == ModeTransitionException.Reason.INSUFFICIENT_VRAM) {
          statusCode = 412;
        }
      }
      ctx.status(statusCode).json(payload);
    }
  }

  /**
   * Tempdoc 656 O2: maps a mode-transition failure {@link ModeTransitionException.Reason} onto the
   * closed {@link LifecycleReasonCode} taxonomy, reusing the inference codes added in Tasks 0-5 (no
   * new code, so no readiness-reason-codes gate change). Mirrors
   * {@code RuntimeActivationService.mapToLifecycleReason}. A null/untyped failure (or any unmapped
   * reason) falls back to the activation-failed catch-all.
   */
  private static LifecycleReasonCode mapModeReason(ModeTransitionException mte) {
    if (mte == null) return LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED;
    return switch (mte.reason()) {
      // "the llama-server executable / a required DLL is missing" — the runtime isn't installed.
      case EXECUTABLE_NOT_FOUND, MISSING_DLL -> LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED;
      // config validation "executable/model not found" also reduces to runtime-not-installed for the
      // user's purposes (the doctor's next remedy is the same: provision the runtime).
      case INVALID_CONFIG, CONFIG_REQUIRED -> LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED;
      default -> LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED;
    };
  }

  /**
   * Handles POST /api/inference/reload - applies persisted settings to the running inference
   * runtime.
   *
   * <p>This endpoint MUST NOT auto-start llama-server when the system is offline. It only restarts
   * the server when already in ONLINE mode.
   */
  void handleReloadInferenceConfig(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      ctx.status(503)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Inference runtime control unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    if (settingsStore == null) {
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SETTINGS_UNAVAILABLE, "Settings store unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    // Refresh policy sysprops before any Online-mode path that might check external server adoption
    // policy.
    if (enterprisePolicyService != null) {
      try {
        enterprisePolicyService.snapshot();
      } catch (Exception ignored) {
        // best-effort; do not fail reload on policy snapshot errors
      }
    }

    io.justsearch.app.api.UiSettings s = settingsStore.load();
    try {
      control.applyRuntimeOverrides(
          s.getLlmModelPath(),
          s.getContextLength(),
          s.getGpuLayers(),
          OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);
      ctx.json(Map.of("success", true, "mode", onlineAi.getCurrentMode()));
    } catch (Exception e) {
      log.error("Failed to apply inference runtime config", e);
      String msg = e.getMessage();
      if (msg == null || msg.isBlank()) {
        msg = e.toString();
      }
      Map<String, Object> payload = ApiErrorHandler.toResponse(ApiErrorCode.INFERENCE_RELOAD_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx));
      payload.put("mode", onlineAi.getCurrentMode());
      payload.put("causes", buildCauseChain(e));
      ctx.status(500).json(payload);
    }
  }

  /**
   * Handles POST /api/inference/detach - detaches from an adopted external llama-server instance (if
   * any) and starts a managed llama-server on a new free port.
   */
  void handleDetachExternalInferenceServer(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      ctx.status(503)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Inference runtime control unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      if (enterprisePolicyService != null
          && !enterprisePolicyService.snapshot().onlineAiEnabled()) {
        ctx.status(403)
            .json(
                ApiErrorHandler.toResponse(ApiErrorCode.POLICY_ONLINE_AI_DISABLED, "Online AI is disabled by administrator policy.", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
    } catch (Exception ignored) {
      // best-effort; do not weaken enforcement elsewhere
    }

    try {
      OnlineAiRuntimeControl.DetachExternalServerResult r = control.detachExternalServer();
      ctx.json(
          Map.of(
              "success",
              true,
              "detached",
              r.detached(),
              "previousPort",
              r.previousPort(),
              "newPort",
              r.newPort(),
              "mode",
              onlineAi.getCurrentMode()));
    } catch (Exception e) {
      log.error("Failed to detach external inference server", e);
      String msg = e.getMessage();
      if (msg == null || msg.isBlank()) {
        msg = e.toString();
      }
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.INFERENCE_DETACH_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** Handles POST /api/offline/process - triggers VDU and embedding batch processing. */
  void handleTriggerOfflineProcessing(Context ctx) {
    if (offlineProcessingTrigger == null) {
      ctx.status(503).json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Offline processing not available", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      log.info("Triggering offline processing (VDU + Embeddings)");
      // Run in background to not block the request
      Thread.ofVirtual().name("offline-processing").start(offlineProcessingTrigger);
      ctx.json(Map.of("success", true, "message", "Offline processing started"));
    } catch (Exception e) {
      log.error("Failed to trigger offline processing", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.INTERNAL_ERROR, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Handles POST /api/worker/restart - restarts the Knowledge Server worker process.
   *
   * <p>Used for "apply embedding config" flows so the worker sees updated environment/config
   * without restarting the whole backend.
   */
  void handleRestartWorker(Context ctx) {
    if (knowledgeServer == null) {
      ctx.status(503)
          .json(
              ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Knowledge Server not configured", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    if (knowledgeServer.spawner() == null) {
      ctx.status(503)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Worker spawner unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      int port = knowledgeServer.spawner().restart();
      long expectedPid = knowledgeServer.spawner().getWorkerPid();
      // Reconnect existing client to the new port and validate PID.
      try {
        knowledgeServer.client().reconnect(expectedPid);
        knowledgeServer.client().resetCircuitBreaker();
      } catch (Exception e) {
        // Best-effort: client has its own reconnect logic; surface as warning but keep response 200.
        log.warn(
            "Worker restarted, but client reconnect failed (will retry on next call): {}",
            e.getMessage());
      }
      ctx.json(Map.of("success", true, "port", port));
    } catch (Exception e) {
      log.error("Failed to restart worker", e);
      String msg = e.getMessage();
      if (msg == null || msg.isBlank()) {
        msg = e.toString();
      }
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.WORKER_RESTART_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Counts documents with pending embedding status.
   *
   * <p>Uses Knowledge Server gRPC to query the index. Falls back to 0 if unavailable.
   */
  private int countPendingEmbeddings() {
    if (knowledgeServer == null || !knowledgeServer.isReady()) {
      return 0;
    }
    try {
      return knowledgeServer.client().countPendingEmbeddings();
    } catch (Exception e) {
      log.debug("Failed to count pending embeddings", e);
      return 0;
    }
  }

  /**
   * Counts documents with pending VDU status.
   *
   * <p>Uses Knowledge Server gRPC to query the index. Falls back to 0 if unavailable.
   */
  private int countPendingVdu() {
    if (knowledgeServer == null || !knowledgeServer.isReady()) {
      return 0;
    }
    try {
      return knowledgeServer.client().countPendingVdu();
    } catch (Exception e) {
      log.debug("Failed to count pending VDU", e);
      return 0;
    }
  }

  /** Walks the cause chain looking for an exception of the given type (max 10 levels). */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
    Throwable cur = e;
    int depth = 0;
    while (cur != null && depth < 10) {
      if (type.isInstance(cur)) {
        return (T) cur;
      }
      cur = cur.getCause();
      depth++;
    }
    return null;
  }

  /** Builds a compact cause chain for error diagnostics (max 10 levels). */
  private static List<Map<String, Object>> buildCauseChain(Throwable e) {
    List<Map<String, Object>> causes = new ArrayList<>();
    Throwable cur = e;
    int depth = 0;
    while (cur != null && depth < 10) {
      Map<String, Object> c = new HashMap<>();
      c.put("type", cur.getClass().getName());
      c.put("message", cur.getMessage());
      causes.add(c);
      cur = cur.getCause();
      depth++;
    }
    return causes;
  }

  // ==========================================================================
  // BrainRuntimeService impl (slice 3a-2-c continuation).
  //
  // Re-uses the same OnlineAiRuntimeControl + OnlineAiService + settingsStore
  // dependencies the HTTP handlers above rely on. Throws on error rather than
  // writing to a Context — Operation handlers map the exception's message
  // into OperationResult.failure; the existing HTTP handlers retain their
  // typed-exception → status-code mapping.
  // ==========================================================================
  // BrainRuntimeService impl moved to io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl
  // (tempdoc 519 §9 Step 3).
}
