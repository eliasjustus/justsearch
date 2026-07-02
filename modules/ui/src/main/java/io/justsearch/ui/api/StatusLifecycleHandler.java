/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.app.api.lifecycle.ReadinessDimension;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.ModelDistributionStatusView;
import io.justsearch.app.api.status.ModelVariantView;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessCompositeView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.AtRestProtectionView;
import io.justsearch.app.api.status.StatusMeta;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryHealthView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import io.justsearch.telemetry.TelemetryHealthState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator handling status and lifecycle HTTP endpoints.
 *
 * <p>Extracted from {@link LocalApiServer} to reduce class size. Handles the {@code /api/status}
 * and {@code /api/health} endpoints registered via {@link
 * io.justsearch.ui.api.routes.StatusRoutes}.
 */
final class StatusLifecycleHandler implements io.justsearch.app.api.StatusSnapshotProvider {
  private static final Logger log = LoggerFactory.getLogger(StatusLifecycleHandler.class);
  private static final String READINESS_READY = "READY";
  private static final String READINESS_DEGRADED = "DEGRADED";
  private static final String READINESS_NOT_READY = "NOT_READY";
  private static final String READINESS_NOT_CONFIGURED = "NOT_CONFIGURED";
  private static final String READINESS_UNKNOWN = "UNKNOWN";

  /** 335 §9: Cache GPU status to avoid NVML init/shutdown on every poll. */
  private static final long GPU_CACHE_TTL_MS = 5_000;
  private volatile GpuStatusView cachedGpuStatus;
  private volatile long cachedGpuStatusAtMs;

  /** Tempdoc 629 (FLOOR): cache the at-rest probe (subprocess) to avoid a PowerShell call per poll. */
  private static final long AT_REST_CACHE_TTL_MS = 5_000;
  private volatile AtRestProtectionView cachedAtRest;
  private volatile long cachedAtRestAtMs;

  /**
   * Tempdoc 629 (FLOOR): the disk-encryption probe, shared with {@link #atRestTap} so both the
   * status View and the condition read one subprocess result. Wired in {@code LocalApiServer};
   * lazily self-constructed from {@link #indexBasePath} on the test-only Builder path.
   */
  private volatile io.justsearch.app.services.atrest.DiskEncryptionProbe diskEncryptionProbe;

  /** Tempdoc 629 (FLOOR): the at-rest-protection condition tap; null on test-only paths. */
  private volatile io.justsearch.app.services.observability.health.AtRestHealthTap atRestTap;

  /** Tempdoc 629 (#2): the LAYER legibility tap — asserts at-rest.authored when the data key is locked. */
  private volatile io.justsearch.app.services.observability.health.ConversationProtectionHealthTap
      conversationProtectionTap;

  /** Tempdoc 629 (#2): supplier of the conversation-encryption state string (late-bound; null in tests). */
  private volatile java.util.function.Supplier<String> conversationProtectionStateSupplier;

  private final OnlineAiService onlineAi;
  private final AgentService agentService;
  private final Supplier<InferenceRuntimeView> inferenceSnapshotSupplier;
  private final io.justsearch.app.services.lifecycle.WorkerCapability workerCapability;
  private final io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability;
  private volatile KnowledgeServerBootstrap knowledgeServer;
  private volatile String knowledgeServerStartError;
  private final Path indexBasePath;
  private final Instant startTime;
  private final Supplier<String> diskPressureSupplier;
  private final Supplier<RerankerService> lambdamartRerankerSupplier;
  private final Supplier<GplStatusProvider> gplCoordinatorSupplier;
  private final Supplier<GpuCapabilitiesService> gpuCapabilitiesSupplier;
  private volatile Supplier<io.justsearch.app.services.vdu.VduCapabilityState.Snapshot>
      vduCapabilitySnapshotSupplier;

  /** Tempdoc 419 C3 V1 — late-bound suppliers for the head-side time-series + health views. */
  private volatile Supplier<RrdMetricStore> rrdStoreSupplier;
  private volatile Supplier<TelemetryHealthState> telemetryHealthSupplier;

  /**
   * Tempdoc 630 — late-bound supplier of the connected {@link
   * io.justsearch.app.services.worker.KnowledgeServerBootstrap}, read at request time for the
   * energy-intent ("Paused — saving energy") + post-resume ("Catching up after sleep") status
   * fields. Null/absent before the worker connects ⇒ both default to neutral.
   */
  private volatile Supplier<io.justsearch.app.services.worker.KnowledgeServerBootstrap>
      knowledgeServerLifecycleSupplier;

  /**
   * Tempdoc 419 C3 V2 P3 — late-bound GPU saturation monitor. Wired in {@code LocalApiServer}
   * after the monitor + sampler are constructed. {@code null} on headless / non-GPU setups,
   * in which case the {@code GPU} readiness dim defaults to READY.
   */
  private volatile io.justsearch.ui.observability.GpuSaturationMonitor gpuSaturationMonitor;

  /**
   * Tempdoc 430 Phase 4 — late-bound HealthEvent substrate tap. Wired in {@code
   * LocalApiServer}. {@code null} when no bootstrap is supplied (test-only Builder path);
   * the readiness envelope is computed but no events flow through the substrate.
   */
  private volatile io.justsearch.app.services.observability.health.LifecycleSnapshotTap
      lifecycleSnapshotTap;

  /**
   * Tempdoc 430 Phase 6 — late-bound worker-view substrate tap. Observes
   * {@code WorkerOperationalView} fields not surfaced via the readiness envelope.
   * {@code null} on test-only paths; receives the same {@code workerView} +
   * {@code workerRpcStale} pair this handler computes per request.
   */
  private volatile io.justsearch.app.services.observability.health.WorkerSnapshotTap
      workerSnapshotTap;

  /** Tempdoc 626 §Axis-C — per-root index-drift condition tap; null on test-only paths. */
  private volatile io.justsearch.app.services.observability.health.IndexDriftHealthTap
      indexDriftTap;

  /**
   * Tempdoc 501 Phase 26 (§13.7 Q5) — late-bound runtime manifest publisher.
   * When non-null and {@code current() != null}, the overall lifecycle
   * projection is read from the manifest rather than re-derived locally.
   * Eliminates the duplicate state-projection surface that the §13.4.3 audit
   * found. Null on test-only paths and during the brief window between
   * {@link LocalApiServer} construction and the publisher's first publish.
   */
  private volatile io.justsearch.ui.runtime.RuntimeManifestPublisher runtimeManifestPublisher;

  StatusLifecycleHandler(
      OnlineAiService onlineAi,
      AgentService agentService,
      Supplier<InferenceRuntimeView> inferenceSnapshotSupplier,
      KnowledgeServerBootstrap knowledgeServer,
      String knowledgeServerStartError,
      Path indexBasePath,
      Instant startTime,
      Supplier<String> diskPressureSupplier,
      Supplier<RerankerService> lambdamartRerankerSupplier,
      Supplier<GplStatusProvider> gplCoordinatorSupplier,
      Supplier<GpuCapabilitiesService> gpuCapabilitiesSupplier,
      io.justsearch.app.services.lifecycle.WorkerCapability workerCapability,
      io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability) {
    // Tempdoc 412 Phase 3: engineMonitorSupplier removed (Phase 0 finding 2: EngineMonitor was
    // dead code; setters never called in production, so the supplier was always null in
    // practice). Inference status is now sourced from {@link InferenceLifecycleManager}'s
    // typed accessors via {@link #buildInferenceView()}.
    this.onlineAi = onlineAi;
    this.agentService = agentService;
    this.inferenceSnapshotSupplier = inferenceSnapshotSupplier;
    this.workerCapability = workerCapability;
    this.inferenceCapability = inferenceCapability;
    this.knowledgeServer = knowledgeServer;
    this.knowledgeServerStartError = knowledgeServerStartError;
    this.indexBasePath = indexBasePath;
    this.startTime = startTime;
    this.diskPressureSupplier = diskPressureSupplier;
    this.lambdamartRerankerSupplier = lambdamartRerankerSupplier;
    this.gplCoordinatorSupplier = gplCoordinatorSupplier;
    this.gpuCapabilitiesSupplier = gpuCapabilitiesSupplier;
  }

  /** Late-binds the Knowledge Server after async Worker startup. */
  void setKnowledgeServer(KnowledgeServerBootstrap ks, String startError) {
    this.knowledgeServer = ks;
    this.knowledgeServerStartError = startError;
  }

  /** Tempdoc 419 C3 V1 — wires the head-side RRD store for GPU-trend backfill. */
  void setRrdStoreSupplier(Supplier<RrdMetricStore> supplier) {
    this.rrdStoreSupplier = supplier;
  }

  /** Tempdoc 419 C3 V1 — wires the head-side telemetry health state for failure-count surfacing. */
  void setTelemetryHealthSupplier(Supplier<TelemetryHealthState> supplier) {
    this.telemetryHealthSupplier = supplier;
  }

  /**
   * Tempdoc 419 C3 V2 P3 — wires the GPU saturation monitor. The handler composes the
   * activity gate from its own suppliers ({@code engineMonitorSupplier}, {@code
   * workerView.migration().processingJobsCount()}, {@code gplCoordinatorSupplier}, {@code
   * onlineAi.isAvailable()}) on each {@code /api/status} call.
   */
  void setGpuSaturationMonitor(io.justsearch.ui.observability.GpuSaturationMonitor monitor) {
    this.gpuSaturationMonitor = monitor;
  }

  /**
   * Tempdoc 430 Phase 4: optional tap that observes per-request readiness envelopes and
   * emits {@code HealthEvent} records through the substrate. Set during {@code
   * LocalApiServer} construction; null in the test-only Builder path.
   */
  void setLifecycleSnapshotTap(
      io.justsearch.app.services.observability.health.LifecycleSnapshotTap tap) {
    this.lifecycleSnapshotTap = tap;
  }

  /**
   * Tempdoc 430 Phase 6: optional tap that observes per-request {@code
   * WorkerOperationalView} for fields not in the readiness envelope (schema /
   * embedding compat, queue-db, job failures). Set during {@code LocalApiServer}
   * construction; null in the test-only Builder path.
   */
  void setWorkerSnapshotTap(
      io.justsearch.app.services.observability.health.WorkerSnapshotTap tap) {
    this.workerSnapshotTap = tap;
  }

  /**
   * Tempdoc 626 §Axis-C: optional tap that reconciles per-watched-root drift
   * ({@code index.drift-unknown}) into the ConditionStore on each snapshot. Owns its
   * watched-roots source; null in the test-only Builder path.
   */
  void setIndexDriftTap(io.justsearch.app.services.observability.health.IndexDriftHealthTap tap) {
    this.indexDriftTap = tap;
  }

  /**
   * Tempdoc 629 (FLOOR): wire the at-rest-protection condition tap + the shared disk-encryption
   * probe, so the {@code /api/status} View and the {@code at-rest.unprotected} condition read one
   * probe result. Late-bound in {@code LocalApiServer}; null on the test-only Builder path.
   */
  void setAtRestTap(
      io.justsearch.app.services.observability.health.AtRestHealthTap tap,
      io.justsearch.app.services.atrest.DiskEncryptionProbe probe) {
    this.atRestTap = tap;
    this.diskEncryptionProbe = probe;
  }

  /** Tempdoc 629 (#2): late-bind the at-rest.authored legibility tap (null on the test-only path). */
  void setConversationProtectionTap(
      io.justsearch.app.services.observability.health.ConversationProtectionHealthTap tap) {
    this.conversationProtectionTap = tap;
  }

  /**
   * Tempdoc 629 (#2): late-bind a supplier of the conversation-encryption state string
   * (not_configured|locked|unlocked) read from {@code HeadAssembly.dataKeyManager().state()}. Null on
   * the test-only Builder path → the View reports "unknown".
   */
  void setConversationProtectionSupplier(java.util.function.Supplier<String> supplier) {
    this.conversationProtectionStateSupplier = supplier;
  }

  /**
   * Tempdoc 501 Phase 26 (§13.7 Q5): wire the runtime manifest publisher
   * so the overall lifecycle projection can be read from one canonical
   * source instead of re-derived per request. Late-bound because
   * {@link LocalApiServer} constructs this handler before the publisher
   * is in scope.
   */
  void setRuntimeManifestPublisher(
      io.justsearch.ui.runtime.RuntimeManifestPublisher publisher) {
    this.runtimeManifestPublisher = publisher;
  }

  void setVduCapabilitySnapshotSupplier(
      Supplier<io.justsearch.app.services.vdu.VduCapabilityState.Snapshot> supplier) {
    this.vduCapabilitySnapshotSupplier = supplier;
  }

  /** Tempdoc 630: late-bind the connected knowledge-server bootstrap (energy + resume signals). */
  void setKnowledgeServerLifecycleSupplier(
      Supplier<io.justsearch.app.services.worker.KnowledgeServerBootstrap> supplier) {
    this.knowledgeServerLifecycleSupplier = supplier;
  }

  /**
   * Builds the {@link PowerStatusView} from the Head-side energy poll (tempdoc 630). Neutral
   * ({@code unknown()}) when the bootstrap isn't connected yet, so the Queue card stays calm until
   * the real signal arrives. Never throws.
   */
  private PowerStatusView buildPowerStatus() {
    var supplier = knowledgeServerLifecycleSupplier;
    if (supplier == null) {
      return PowerStatusView.unknown();
    }
    try {
      var bootstrap = supplier.get();
      if (bootstrap == null) {
        return PowerStatusView.unknown();
      }
      var energy = bootstrap.energyState();
      return new PowerStatusView(energy.reduced(), energy.source().name());
    } catch (RuntimeException e) {
      log.debug("Energy status unavailable: {}", e.getMessage());
      return PowerStatusView.unknown();
    }
  }

  /**
   * Whether an OS resume was handled within the recent notice window (tempdoc 630), computed against
   * the request clock so the "Catching up after sleep" transient auto-clears. Never throws.
   */
  private boolean computeCatchingUp() {
    var supplier = knowledgeServerLifecycleSupplier;
    if (supplier == null) {
      return false;
    }
    try {
      var bootstrap = supplier.get();
      return bootstrap != null && bootstrap.recentlyResumed(System.currentTimeMillis());
    } catch (RuntimeException e) {
      log.debug("Catching-up status unavailable: {}", e.getMessage());
      return false;
    }
  }

  /**
   * observations.md inbox item #1 (2026-05-08): bypasses the broken
   * worker→head RRD replication by feeding the worker-shipped recent-arrays
   * (`recent_job_queue_depth` / `recent_docs_per_sec`, present on every
   * {@code CoreStatus} gRPC response) directly into the {@code TimeseriesSnapshotHolder}s
   * that back the `/api/metrics/worker.*` endpoints. The RRD-based ticks
   * stay scheduled (harmless no-ops because the head's RRD never receives
   * worker.* samples) — this callback is the live data source.
   */
  private volatile java.util.function.BiConsumer<
          WorkerOperationalView, Boolean>
      workerMetricsPublisher;

  void setWorkerMetricsPublisher(
      java.util.function.BiConsumer<
              WorkerOperationalView, Boolean>
          publisher) {
    this.workerMetricsPublisher = publisher;
  }

  void handleStatus(Context ctx) {
    ctx.json(buildStatusMap());
  }

  @Override
  public StatusResponse buildStatusSnapshot() {
    return buildStatusMap();
  }

  /** Builds the status response (reusable outside HTTP context). */
  StatusResponse buildStatusMap() {
    // Appendix D: stable lifecycle subset (schema v1).
    LifecycleSnapshotV1 lifecycleSnapshot = computeLifecycleSnapshot();

    long uptimeMs = System.currentTimeMillis() - startTime.toEpochMilli();

    // JVM Memory
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = runtime.maxMemory();

    // Disk pressure (from telemetry subsystem, when available)
    String diskPressure = null;
    if (diskPressureSupplier != null) {
      diskPressure = diskPressureSupplier.get();
    }

    // NOTE: Head MUST NOT touch Lucene index files. Index availability is reported by the Worker
    // when available.
    // Keep this field for UI backwards-compat, but treat it as "Worker-connected index available".
    boolean indexAvailable = false;
    WorkerOperationalView workerView;
    String indexStatusReason = null;
    boolean workerRpcStale;

    // Capture the timestamp immediately before the Worker gRPC call for provenance tracking.
    long workerRpcAtMs = System.currentTimeMillis();

    if (workerCapability.available()) {
      try {
        workerView = knowledgeServer.client().getWorkerOperationalView();
        indexAvailable = true;
        workerRpcStale = false;
      } catch (Exception e) {
        log.debug(
            "Failed to fetch Knowledge Server status: {} - {}",
            e.getClass().getSimpleName(),
            e.getMessage(),
            e);
        indexStatusReason = e.getClass().getSimpleName() + ": " + e.getMessage();
        workerView = WorkerOperationalView.fallback("UNAVAILABLE");
        workerRpcStale = true;
      }
    } else {
      workerView = WorkerOperationalView.fallback(workerCapability.health().name());
      workerRpcStale = true;
    }
    workerView = overlayVduCapability(workerView);

    // Tempdoc 412 Phase 3: single inference status surface (replaces LlmStatusView + OnlineAiView).
    InferenceRuntimeView inference = buildInferenceView();

    ReadinessEnvelopeView readiness = buildReadinessEnvelope(workerView, lifecycleSnapshot);

    // Tempdoc 430 Phase 4: feed the readiness envelope into the HealthEvent substrate
    // tap. The tap reconciles each dim's (state, reasonCode) against ConditionStore and
    // broadcasts deltas through HealthEventChangeRegistry to SSE subscribers. Null when
    // no HeadAssembly was supplied (test-only path).
    var tap = lifecycleSnapshotTap;
    if (tap != null) {
      try {
        tap.accept(readiness);
      } catch (RuntimeException e) {
        log.warn("LifecycleSnapshotTap failed during /api/status; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 430 Phase 6: feed the worker view + stale flag into the second tap. The
    // tap reconciles compatibility / queue-db / failure fields not surfaced through the
    // readiness envelope. Stale views short-circuit emission entirely (per §B.T.5 —
    // unknown ≠ healthy; a fallback view's queueDbHealthy=true would falsely clear a
    // real queue-db.unhealthy condition without this guard).
    var workerTap = workerSnapshotTap;
    if (workerTap != null) {
      try {
        workerTap.accept(workerView, workerRpcStale);
      } catch (RuntimeException e) {
        log.warn("WorkerSnapshotTap failed during /api/status; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 626 §Axis-C: reconcile per-root index-drift (delete-detection-unverified) into the
    // ConditionStore. The tap pulls the watched roots itself; failures are logged, never fatal.
    var driftTap = indexDriftTap;
    if (driftTap != null) {
      try {
        driftTap.accept();
      } catch (RuntimeException e) {
        log.warn("IndexDriftHealthTap failed during /api/status; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 629 (FLOOR): reconcile the at-rest-unprotected condition (data-dir volume not
    // OS-encrypted) into the ConditionStore. Verdict-independent (a confidentiality concern, not a
    // retrieval degradation); failures are logged, never fatal.
    var atRest = atRestTap;
    if (atRest != null) {
      try {
        atRest.accept();
      } catch (RuntimeException e) {
        log.warn("AtRestHealthTap failed during /api/status; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 629 (#2): reconcile the at-rest.authored condition (AUTHORED stores encrypted + locked)
    // into the ConditionStore. Verdict-independent; failures logged, never fatal.
    var convTap = conversationProtectionTap;
    if (convTap != null) {
      try {
        convTap.accept();
      } catch (RuntimeException e) {
        log.warn(
            "ConversationProtectionHealthTap failed during /api/status; broadcast suppressed", e);
      }
    }

    // observations.md inbox item #1: feed worker-shipped recent arrays into the
    // TIMESERIES metric holders so /api/metrics/worker.* + Sparklines have live
    // data without needing a working worker→head RRD replication wire.
    var metricsPublisher = workerMetricsPublisher;
    if (metricsPublisher != null) {
      try {
        metricsPublisher.accept(workerView, workerRpcStale);
      } catch (RuntimeException e) {
        log.warn(
            "Worker-metrics publisher failed during /api/status; metric snapshots not refreshed",
            e);
      }
    }

    // Legacy alias booleans remain exposed, but are derived from canonical typed readiness.
    boolean aiReady = readinessComponentReady(readiness, "ai");
    boolean embeddingReady = readinessComponentReady(readiness, "embedding");

    return new StatusResponse(
        lifecycleSnapshot.schema_version(),
        lifecycleSnapshot.observed_at(),
        lifecycleSnapshot.lifecycle(),
        lifecycleSnapshot.components(),
        "ok",
        "JustSearch Local API",
        indexBasePath == null ? "" : indexBasePath.toString(),
        uptimeMs,
        usedMemory,
        totalMemory,
        maxMemory,
        diskPressure,
        workerView,
        indexAvailable,
        knowledgeServerStartError,
        indexStatusReason,
        // 630: Head-side energy-intent + post-resume "catching up" transient.
        buildPowerStatus(),
        computeCatchingUp(),
        inference,
        readiness,
        aiReady,
        embeddingReady,
        // 330 §4: Grouped sub-objects (same data, structured)
        workerView != null
            ? io.justsearch.app.api.status.EmbeddingStatusGroup.from(workerView, embeddingReady)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.SchemaStatusGroup.from(workerView)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.ChunkCoverageGroup.from(workerView)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.QueueHealthGroup.from(workerView)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.MigrationStatusGroup.from(workerView)
            : null,
        // 335 §9: GPU utilization and VRAM
        buildGpuStatus(),
        // 629 (FLOOR): at-rest protection of the data-dir volume
        buildAtRestProtection(),
        // 629 (#2): conversation encryption state (reactive single-authority status)
        buildConversationProtection(),
        // 381: Model distribution status
        buildModelDistributionStatus(),
        // 415: Active agent session count (sourced from agent.session.active_count gauge).
        // Omitted via @JsonInclude(NON_NULL) on StatusResponse when agent capability is
        // unavailable, so field presence on /api/status signals "agent capability is live."
        agentService.isAvailable()
            ? new io.justsearch.app.api.status.AgentSessionView(
                agentService.activeSessionCount())
            : null,
        // 419 C3 V1: telemetry-subsystem health counters (populated in Step C).
        buildTelemetryHealth(),
        // 333 §5: Freshness and provenance metadata
        new StatusMeta(workerRpcAtMs, workerRpcStale));
  }

  private WorkerOperationalView overlayVduCapability(WorkerOperationalView workerView) {
    if (workerView == null) {
      return null;
    }
    var supplier = vduCapabilitySnapshotSupplier;
    if (supplier == null) {
      return workerView;
    }
    io.justsearch.app.services.vdu.VduCapabilityState.Snapshot snapshot;
    try {
      snapshot = supplier.get();
    } catch (RuntimeException e) {
      log.debug("VDU capability snapshot supplier failed: {}", e.getMessage());
      return workerView;
    }
    if (snapshot == null || snapshot.blockedReason() == null || snapshot.blockedReason().isBlank()) {
      return workerView;
    }
    VisualExtractionView visual =
        workerView.visualExtraction() == null
            ? VisualExtractionView.empty()
            : workerView.visualExtraction();
    VisualExtractionView merged =
        new VisualExtractionView(
            visual.ocrEnabled(),
            visual.ocrEngineAvailable(),
            visual.ocrEngine(),
            visual.ocrBlockedReason(),
            visual.visualTextNeededCount(),
            visual.visualEnrichmentNeededCount(),
            snapshot.blockedReason());
    return WorkerOperationalViewBuilder.builder()
        .core(workerView.core())
        .failure(workerView.failure())
        .migration(workerView.migration())
        .compatibility(workerView.compatibility())
        .queueDb(workerView.queueDb())
        .enrichment(workerView.enrichment())
        .gpu(workerView.gpu())
        .vectorFormat(workerView.vectorFormat())
        .telemetry(workerView.telemetry())
        .searchConfig(workerView.searchConfig())
        .visualExtraction(merged)
        .buildStamp(workerView.buildStamp())
        .aiReady(workerView.aiReady())
        .embeddingReady(workerView.embeddingReady())
        .build();
  }

  /**
   * Tempdoc 419 C3 V1: build telemetry-health view from {@code TelemetryHealthState}.
   *
   * <p>Returns {@link TelemetryHealthView#empty()} when the supplier isn't wired (tests / boot
   * paths without {@link io.justsearch.telemetry.LocalTelemetry}).
   */
  private TelemetryHealthView buildTelemetryHealth() {
    var snapshot = currentTelemetrySnapshot();
    if (snapshot == null) return TelemetryHealthView.empty();
    return new TelemetryHealthView(snapshot.flushFailures(), snapshot.gaugeCallbackFailures());
  }

  /**
   * Tempdoc 419 C3 V2 P1: shared snapshot accessor. Returns {@code null} on missing supplier,
   * supplier exception, null state, or snapshot exception. Both {@link #buildTelemetryHealth}
   * and the readiness envelope's {@code TELEMETRY} dim consume this so the V1 view and V2
   * readiness can't drift.
   */
  private TelemetryHealthSnapshot currentTelemetrySnapshot() {
    Supplier<TelemetryHealthState> supp = telemetryHealthSupplier;
    if (supp == null) return null;
    TelemetryHealthState state;
    try {
      state = supp.get();
    } catch (RuntimeException e) {
      return null;
    }
    if (state == null) return null;
    try {
      return state.snapshot();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Tempdoc 419 C3 V1: 30-min RRD trend for {@code metric} on the head-side store. Returns an
   * empty array when the store isn't wired or hasn't accumulated data yet.
   */
  private double[] recentTrend(String metric) {
    Supplier<RrdMetricStore> supp = rrdStoreSupplier;
    if (supp == null) return new double[0];
    RrdMetricStore store;
    try {
      store = supp.get();
    } catch (RuntimeException e) {
      return new double[0];
    }
    if (store == null) return new double[0];
    long nowSec = Instant.now().getEpochSecond();
    TimeSeriesResult result;
    try {
      result = store.query(metric, nowSec - 1800, nowSec);
    } catch (RuntimeException e) {
      log.debug("RRD query for {} failed: {}", metric, e.getMessage());
      return new double[0];
    }
    if (result == null) return new double[0];
    double[] values = result.values();
    return values == null ? new double[0] : values;
  }

  /** 335 §9: Build GPU status from NVML probe (best-effort, Head-side, cached). */
  private GpuStatusView buildGpuStatus() {
    // Return cached value if fresh enough (avoid NVML init/shutdown per poll).
    long now = System.currentTimeMillis();
    GpuStatusView cached = cachedGpuStatus;
    if (cached != null && (now - cachedGpuStatusAtMs) < GPU_CACHE_TTL_MS) {
      return cached;
    }

    GpuStatusView result = probeGpuStatus();
    cachedGpuStatus = result;
    cachedGpuStatusAtMs = now;
    return result;
  }

  /**
   * Tempdoc 629 (FLOOR): build the at-rest protection view from the (cached) disk-encryption probe.
   * Coarse on/off fidelity only — {@code qualityKnown=false} always, since the configuration-quality
   * distinction needs admin elevation (629 §R1 / confidence-probe P1).
   */
  private AtRestProtectionView buildAtRestProtection() {
    long now = System.currentTimeMillis();
    AtRestProtectionView cached = cachedAtRest;
    if (cached != null && (now - cachedAtRestAtMs) < AT_REST_CACHE_TTL_MS) {
      return cached;
    }
    AtRestProtectionView result;
    try {
      io.justsearch.app.services.atrest.DiskEncryptionProbe probe = diskEncryptionProbe;
      if (probe == null) {
        // Test-only / Builder path: self-construct from the index base path.
        probe = new io.justsearch.app.services.atrest.DiskEncryptionProbe(indexBasePath);
        diskEncryptionProbe = probe;
      }
      io.justsearch.app.services.atrest.AtRestProtection p = probe.current();
      result =
          new AtRestProtectionView(p.state().name(), p.source(), p.confidence().name(), false);
    } catch (RuntimeException e) {
      log.debug("at-rest probe failed: {}", e.getMessage());
      result = AtRestProtectionView.unknown();
    }
    cachedAtRest = result;
    cachedAtRestAtMs = now;
    return result;
  }

  /**
   * Tempdoc 629 (#2): the reactive conversation-encryption status. A direct, cheap read of
   * {@code DataKeyManager.state()} (synchronized, in-process — no probe, no cache needed), so the
   * Health card + the locked-chat gate project one always-current authority off the /api/status poll.
   */
  private io.justsearch.app.api.status.ConversationProtectionView buildConversationProtection() {
    java.util.function.Supplier<String> supplier = conversationProtectionStateSupplier;
    if (supplier == null) {
      return io.justsearch.app.api.status.ConversationProtectionView.unknown();
    }
    try {
      return new io.justsearch.app.api.status.ConversationProtectionView(supplier.get());
    } catch (RuntimeException e) {
      log.debug("conversation-protection state read failed: {}", e.getMessage());
      return io.justsearch.app.api.status.ConversationProtectionView.unknown();
    }
  }

  private GpuStatusView probeGpuStatus() {
    if (gpuCapabilitiesSupplier == null) {
      return GpuStatusView.unavailable();
    }
    try {
      GpuCapabilitiesService svc = gpuCapabilitiesSupplier.get();
      if (svc == null) {
        return GpuStatusView.unavailable();
      }
      // Tempdoc 587: read the UNIFIED resolver snapshot (CUDA folded into the NVML/nvidia-smi
      // merge). Utilization/VRAM stay NVML-only; cudaFunctional + source + confidence come from
      // the merged effective view, so the surface reads one resolver instead of the raw snapshot.
      GpuCapabilities caps =
          new io.justsearch.app.services.gpu.GpuCapabilityResolver(svc).snapshot();
      GpuCapabilities.Nvml nvml = caps.nvml();
      if (nvml == null || !nvml.available()) {
        return GpuStatusView.unavailable();
      }
      GpuCapabilities.Effective eff = caps.effective();
      Boolean cudaFunctional =
          eff != null && eff.cuda() != null ? eff.cuda().functional() : null;
      String source = eff != null ? eff.source() : null;
      String confidence = eff != null && eff.confidence() != null ? eff.confidence().name() : null;
      return new GpuStatusView(
          true,
          nvml.gpuUtilizationPercent(),
          nvml.memoryUtilizationPercent(),
          nvml.totalVramBytes(),
          nvml.usedVramBytes(),
          nvml.freeVramBytes(),
          nvml.driverVersion(),
          nvml.deviceCount(),
          // 419 C3 V1: 30-min trends from head-side RRD store.
          recentTrend(io.justsearch.app.services.observability.HeadGpuMetricCatalog
              .UTILIZATION_PERCENT),
          recentTrend(io.justsearch.app.services.observability.HeadGpuMetricCatalog
              .MEMORY_UTILIZATION_PERCENT),
          cudaFunctional,
          source,
          confidence);
    } catch (Exception e) {
      log.debug("GPU status probe failed: {}", e.getMessage());
      return GpuStatusView.unavailable();
    }
  }

  /** 381: Build model distribution status from install contract (cached, best-effort). */
  private static final long MODEL_DIST_CACHE_TTL_MS = 30_000;
  private volatile ModelDistributionStatusView cachedModelDist;
  private volatile long cachedModelDistAtMs;

  private ModelDistributionStatusView buildModelDistributionStatus() {
    long now = System.currentTimeMillis();
    ModelDistributionStatusView cached = cachedModelDist;
    if (cached != null && (now - cachedModelDistAtMs) < MODEL_DIST_CACHE_TTL_MS) {
      return cached;
    }
    ModelDistributionStatusView result = probeModelDistributionStatus();
    cachedModelDist = result;
    cachedModelDistAtMs = now;
    return result;
  }

  private ModelDistributionStatusView probeModelDistributionStatus() {
    try {
      io.justsearch.configuration.model.InstallContract contract = readInstallContract();
      if (contract == null) return ModelDistributionStatusView.unavailable();

      Path modelsDir = resolveModelsDir(contract);
      if (modelsDir == null) return ModelDistributionStatusView.unavailable();

      io.justsearch.configuration.model.HardwareProfile hardware = buildCurrentHardwareProfile();

      Map<String, ModelVariantView> variants = new LinkedHashMap<>();
      for (var entry : contract.models().entrySet()) {
        var model = entry.getValue();
        if (model.skipped()) {
          variants.put(
              entry.getKey(),
              new ModelVariantView(null, null, null, false, null, true, model.skipReason()));
          continue;
        }
        // Tempdoc 374 alpha.18 Bug I + alpha.21 Bug R: per-role gpuAllowed.
        // Pre-alpha.21 the status display passed `true` for ALL roles, which
        // diverged from the runtime composition path (composeCitationRole hardcodes
        // false). Result: citation reported as `degraded(CUDA)` in the status block
        // even though the runtime correctly ran it on CPU — round-11 evidence
        // showed `"FP16 variant not installed — running INT8 on CUDA"` as the
        // misleading degradation reason. Honoring EncoderRole.isPackageCpuOnly
        // here makes the display match what the runtime actually does.
        io.justsearch.configuration.model.VariantSelection sel =
            io.justsearch.configuration.model.VariantSelector.select(
                entry.getKey(), contract, hardware, modelsDir,
                /* gpuAllowed= */ !io.justsearch.ort.EncoderRole.isPackageCpuOnly(entry.getKey()));
        if (sel != null) {
          variants.put(
              entry.getKey(),
              new ModelVariantView(
                  sel.modelFile().getFileName().toString(),
                  sel.precision().name(),
                  sel.executionProvider().name(),
                  sel.degraded(),
                  sel.degradationReason(),
                  false,
                  null));
        }
      }

      String guidance = buildUpgradeGuidance(contract.downloadProfile());
      return new ModelDistributionStatusView(
          contract.downloadProfile().name(), variants, guidance);
    } catch (Exception e) {
      log.debug("Failed to build model distribution status: {}", e.getMessage());
      return ModelDistributionStatusView.unavailable();
    }
  }

  private io.justsearch.configuration.model.InstallContract readInstallContract() {
    Path aiHome = resolveAiHome();
    if (aiHome == null) return null;
    return io.justsearch.configuration.model.InstallContractIO.read(aiHome);
  }

  private static Path resolveAiHome() {
    try {
      io.justsearch.configuration.resolved.ConfigStore cs =
          io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      Path fromEnv = cs != null ? cs.get().paths().home() : null;
      if (fromEnv != null) return fromEnv;
    } catch (Exception e) {
      // best-effort
    }
    try {
      return io.justsearch.configuration.PlatformPaths.resolveDataDir();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Tempdoc 374 alpha.20 Bug M: prefer the install contract's recorded modelsDir.
   * Mirrors {@code KnowledgeServer.resolveModelsDir} so head-side status display agrees
   * with worker-side contract path resolution. Pre-alpha.20 the head used
   * {@code aiHome/models} which is wrong for users who pre-stage models via
   * {@code JUSTSEARCH_MODELS_DIR} — the {@code modelDistribution.modelVariants}
   * status block reported every package as "Model file missing from disk" after
   * cold restart (round-10 evidence) even though the files were on disk.
   */
  private Path resolveModelsDir(io.justsearch.configuration.model.InstallContract contract) {
    if (contract != null && contract.modelsDir() != null) {
      return contract.modelsDir();
    }
    Path aiHome = resolveAiHome();
    return aiHome != null ? aiHome.resolve("models") : null;
  }

  private io.justsearch.configuration.model.HardwareProfile buildCurrentHardwareProfile() {
    boolean cudaFunctional =
        io.justsearch.configuration.EnvRegistry.GPU_ENABLED
            .get()
            .map(Boolean::parseBoolean)
            .orElse(false);
    long vramBytes = -1;
    if (gpuCapabilitiesSupplier != null) {
      try {
        var caps = gpuCapabilitiesSupplier.get();
        if (caps != null) {
          var snap = caps.snapshot();
          if (snap.effective() != null) {
            vramBytes = snap.effective().totalVramBytes();
          }
        }
      } catch (Exception e) {
        // best-effort
      }
    }
    boolean gpuDetected = vramBytes > 0 || cudaFunctional;
    return new io.justsearch.configuration.model.HardwareProfile(
        gpuDetected, cudaFunctional, vramBytes);
  }

  private static String buildUpgradeGuidance(
      io.justsearch.configuration.model.DownloadProfile profile) {
    return switch (profile) {
      case GPU_FULL -> null;
      case GPU_LITE ->
          "Your GPU has insufficient VRAM for chat/RAG. Upgrade to a GPU with 8+ GB VRAM, then re-run Install AI.";
      case CPU ->
          "No CUDA-capable GPU detected. Add an NVIDIA GPU with CUDA support, then re-run Install AI for full AI features.";
    };
  }

  /** Handles GET /api/health - stable, machine-oriented lifecycle surface (Appendix D schema v1). */
  void handleHealth(Context ctx) {
    LifecycleSnapshotV1 snapshot = computeLifecycleSnapshot();
    int httpStatus = healthHttpStatus(snapshot.lifecycle().state());
    ctx.status(httpStatus).json(snapshot);
  }

  static int healthHttpStatus(LifecycleState state) {
    if (state == null) {
      return 503;
    }
    return switch (state) {
      case LIFECYCLE_STATE_READY, LIFECYCLE_STATE_DEGRADED -> 200;
      case LIFECYCLE_STATE_STARTING,
          LIFECYCLE_STATE_ERROR,
          LIFECYCLE_STATE_STOPPING,
          LIFECYCLE_STATE_STOPPED,
          LIFECYCLE_STATE_UNSPECIFIED,
          UNRECOGNIZED -> 503;
    };
  }

  static boolean hasActiveIndexWork(WorkerOperationalView workerView) {
    if (workerView == null) {
      return false;
    }
    return workerView.migration().processingJobsCount() > 0
        || workerView.migration().pendingJobsCount() > 0
        || workerView.migration().pendingReadyJobsCount() > 0;
  }

  /**
   * Tempdoc 419 C3 V2 P3: composes the activity gate for {@code GpuSaturationMonitor}.
   *
   * <p>The gate is a sum of "GPU should be busy" signals; SATURATED requires it to be 0.
   * Components:
   *
   * <ul>
   *   <li>LLM engine queue depth — chat / embedding / generation backpressure
   *   <li>Worker indexing processing-jobs count
   *   <li>GPL training in RUNNING phase (LambdaMART builds run on GPU)
   *   <li>Online AI availability — llama-server keeps GPU resident even when idle (background
   *       KV warmup / cache residency); suppressing alerts whenever it's up avoids
   *       false-positive saturation events.
   * </ul>
   */
  private long computeGpuActivityGate(WorkerOperationalView workerView) {
    long gate = 0L;
    // Tempdoc 412 Phase 3 reconciliation: EngineMonitor was removed (its queue depth was
    // a stale duplicate of OnlineAiService state). The inference event substrate replaces it,
    // but a per-tick queue-depth gauge is deferred until the llama-server Prometheus scraper
    // lands (see InferenceRuntimeView's dropped `queue` field). The activity gate degrades
    // by one signal in the interim; the remaining (worker processing-jobs, GPL training, and
    // onlineAi.isAvailable) terms still cover the dominant false-positive cases.
    if (workerView != null) {
      gate += Math.max(0L, workerView.migration().processingJobsCount());
    }
    if (gplCoordinatorSupplier != null) {
      try {
        var coord = gplCoordinatorSupplier.get();
        if (coord != null && coord.getStatus().status() == GplJobStatus.Status.RUNNING) {
          gate += 1;
        }
      } catch (RuntimeException ignored) {
        // Best-effort.
      }
    }
    if (onlineAi != null && onlineAi.isAvailable()) {
      gate += 1;
    }
    return gate;
  }

  /**
   * Tempdoc 656: {@code inferenceCapability.pendingReason()} holds a real
   * {@link LifecycleReasonCode#code()} when set via {@code RuntimeActivationService}'s wiring
   * (Task 2), but can also hold arbitrary free prose from
   * {@code InferenceCapabilityWiring.attachInferenceModeListener}'s mode-change callback (e.g.
   * "Inference offline", "GPU allocated to indexing") — only forward it when it's a known code;
   * otherwise fall back to the generic {@code INFERENCE_OFFLINE}, same as before this fix.
   */
  private static String resolveInferenceReasonCode(
      io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability) {
    String reason = inferenceCapability.pendingReason();
    return LifecycleReasonCode.isKnown(reason) ? reason : LifecycleReasonCode.INFERENCE_OFFLINE.code();
  }

  static String throughputReadinessReason(WorkerOperationalView workerView) {
    if (workerView == null || !hasActiveIndexWork(workerView)) {
      return null;
    }
    String windowState = workerView.telemetry().throughputWindowState();
    if ("STALLED".equalsIgnoreCase(windowState)) {
      return LifecycleReasonCode.WORKER_THROUGHPUT_STALLED.code();
    }
    if ("DEGRADED".equalsIgnoreCase(windowState)) {
      return LifecycleReasonCode.WORKER_THROUGHPUT_DEGRADED.code();
    }
    return null;
  }

  /**
   * Tempdoc 412 Phase 3: returns the canonical inference status view via the injected supplier.
   * The projection logic lives in {@code HeadAssembly.projectInferenceSnapshot} (correct
   * layering: it's the only place that has both the {@link InferenceLifecycleManager} reference
   * and the API record types). This handler is just a forwarder.
   */
  private InferenceRuntimeView buildInferenceView() {
    if (inferenceSnapshotSupplier == null) {
      return new InferenceRuntimeView(
          "OFFLINE",
          null,
          false,
          null,
          new io.justsearch.app.api.status.LifecycleCounters(0L, 0L, 0L));
    }
    return inferenceSnapshotSupplier.get();
  }

  private LifecycleSnapshotV1 computeLifecycleSnapshot() {
    Instant now = Instant.now();

    // Head component: this process is running if we're in this handler.
    LifecycleSnapshotV1.Component head =
        new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY, null);

    // Worker component: derived from capability health. The bootstrap now keeps
    // workerCapability in sync with every health transition, so capability
    // health is always current — even in integration tests that create a real bootstrap.
    LifecycleSnapshotV1.Component worker = switch (workerCapability.health()) {
      case READY -> new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY, null);
      // Tempdoc 627: a DEGRADED worker carrying the terminal give-up reason (supervisor exhausted its
      // restart budget) surfaces that distinct, non-self-recovering code; other DEGRADED states keep the
      // generic spawn-failed code.
      case DEGRADED -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_ERROR,
          LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code().equals(workerCapability.pendingReason())
              ? LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code()
              : LifecycleReasonCode.WORKER_SPAWN_FAILED.code());
      // Tempdoc 627: RECOVERING is transient (a supervised restart is in flight). Surface it as a
      // distinct, calm reason (worker.recovering) at DEGRADED severity — NOT ERROR/spawn-failed — so the
      // FE verdict renders a routine self-heal as "Restarting…" instead of "Service degraded".
      case RECOVERING -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_DEGRADED, LifecycleReasonCode.WORKER_RECOVERING.code());
      case OFFLINE -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_DEGRADED, LifecycleReasonCode.WORKER_NOT_CONFIGURED.code());
      case PENDING -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_STARTING, LifecycleReasonCode.WORKER_STARTING.code());
    };

    // Inference component: derived from capability health (migrated from OnlineAiService).
    // Tempdoc 656: mirrors the WORKER component's pattern just above (prefer a specific known
    // reason over the generic fallback) — previously every non-READY/non-STARTING state hardcoded
    // INFERENCE_OFFLINE regardless of what inferenceCapability.pendingReason() actually held,
    // which is why RuntimeActivationService's now-wired precise reasons (Task 2) weren't reaching
    // this composite/the FE degradation banner even after the runtime-manifest fix landed.
    LifecycleSnapshotV1.Component inference = switch (inferenceCapability.health()) {
      case READY -> new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY, null);
      case PENDING -> onlineAi != null && onlineAi.isStartingUp()
          ? new LifecycleSnapshotV1.Component(
              LifecycleState.LIFECYCLE_STATE_STARTING, LifecycleReasonCode.INFERENCE_STARTING.code())
          : new LifecycleSnapshotV1.Component(
              LifecycleState.LIFECYCLE_STATE_DEGRADED, resolveInferenceReasonCode(inferenceCapability));
      case DEGRADED, RECOVERING, OFFLINE -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_DEGRADED, resolveInferenceReasonCode(inferenceCapability));
    };

    LifecycleSnapshotV1.Components components =
        new LifecycleSnapshotV1.Components(head, worker, inference);

    // Tempdoc 501 Phase 26 (§13.7 Q5): prefer the runtime manifest's overall
    // lifecycle projection. The publisher composes it from the same
    // capability sources via LifecycleProjection.derive, but eliminating the
    // re-derivation here means /api/status and /api/runtime/manifest are
    // guaranteed to agree on the discriminator. Fall back to direct
    // derivation when the publisher isn't wired (test-only Builder) or
    // hasn't produced its first manifest yet (brief boot window).
    LifecycleState overallState = readManifestLifecycle();
    if (overallState == null) {
      overallState =
          io.justsearch.app.services.lifecycle.LifecycleProjection.derive(
              workerCapability, inferenceCapability);
    }
    LifecycleSnapshotV1.Lifecycle lifecycle =
        new LifecycleSnapshotV1.Lifecycle(overallState, resolveOverallReason(overallState, worker, inference), null);

    return new LifecycleSnapshotV1(
        LifecycleSnapshotV1.SCHEMA_VERSION, now.toString(), lifecycle, components);
  }

  /** Phase 26: read overall lifecycle from manifest; null on unwired/no-publish/unrecognized string. Package-private for Phase 39 test coverage. */
  LifecycleState readManifestLifecycle() {
    io.justsearch.ui.runtime.RuntimeManifestPublisher pub = this.runtimeManifestPublisher;
    if (pub == null) {
      return null;
    }
    io.justsearch.app.api.runtime.RuntimeManifest m = pub.current();
    if (m == null || m.lifecycle() == null) {
      return null;
    }
    try {
      return LifecycleState.valueOf(m.lifecycle());
    } catch (IllegalArgumentException unrecognized) {
      log.debug("manifest lifecycle string not a LifecycleState: {}", m.lifecycle());
      return null;
    }
  }

  private static String resolveOverallReason(
      LifecycleState overall,
      LifecycleSnapshotV1.Component worker,
      LifecycleSnapshotV1.Component inference) {
    if (overall == LifecycleState.LIFECYCLE_STATE_READY) {
      return null;
    }
    // Worker problems take priority — if the worker component is unhealthy, use its reason.
    if (worker.state() != LifecycleState.LIFECYCLE_STATE_READY) {
      return worker.reason_code();
    }
    // Otherwise the inference component drove the degradation.
    return inference.reason_code();
  }

  // Package-private for StatusLifecycleHandlerTest (tempdoc 600): lets the test drive a
  // BLOCKED_LEGACY worker view through the real component→composite rollup and assert the
  // `retrieval` composite resolves to DEGRADED carrying the compat reason code — the chain the
  // unit test for `compatBlockedReason` alone does not cover.
  ReadinessEnvelopeView buildReadinessEnvelope(
      WorkerOperationalView workerView, LifecycleSnapshotV1 lifecycleSnapshot) {
    String observedAt = lifecycleSnapshot.observed_at();

    // Compute all components via exhaustive switch — adding a new ReadinessDimension
    // constant without handling it here is a compile error (Java 21+).
    Map<String, ReadinessComponentView> components = new LinkedHashMap<>();
    Map<ReadinessDimension, ReadinessComponentView> computed =
        new EnumMap<>(ReadinessDimension.class);
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      ReadinessComponentView comp =
          computeComponent(dim, workerView, lifecycleSnapshot, observedAt);
      components.put(dim.key(), comp);
      computed.put(dim, comp);
    }

    // Assemble composites from dim.composite() grouping — no hardcoded lists.
    Map<String, List<ReadinessDimension>> byComposite = new LinkedHashMap<>();
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      byComposite.computeIfAbsent(dim.composite(), k -> new ArrayList<>()).add(dim);
    }
    Map<String, ReadinessCompositeView> composites = new LinkedHashMap<>();
    for (var entry : byComposite.entrySet()) {
      List<String> states = new ArrayList<>();
      List<String> reasonCodes = new ArrayList<>();
      for (ReadinessDimension dim : entry.getValue()) {
        ReadinessComponentView c = computed.get(dim);
        states.add(c.state());
        reasonCodes.add(c.reasonCode());
      }
      composites.put(entry.getKey(), readinessComposite(states, reasonCodes));
    }

    return new ReadinessEnvelopeView(1, observedAt, components, composites);
  }

  /**
   * Tempdoc 600 Design A: maps the worker's embedding/schema compatibility state to an actionable
   * retrieval-degradation reason code, or {@code null} when retrieval is not compat-blocked.
   *
   * <p>Only the {@code BLOCKED_*} states map (a rebuild is genuinely required to restore the dense
   * leg): {@code REBUILDING} is a transient owned by the 595 Stability axis (routing it here would
   * double-represent a calm transition as an alarm), and {@code UNAVAILABLE}/{@code COMPATIBLE} are
   * not retrieval-degradation causes. Schema is checked before embedding to mirror the worker's own
   * {@code reindexRequiredReason} precedence. These reason codes are free strings on the readiness
   * composite (no enum/schema change) and are worded + given a remedy on the FE via
   * {@code readinessNotice.CAUSE_ROWS}.
   */
  static String compatBlockedReason(WorkerOperationalView workerView) {
    // tempdoc 628 Stage C: a corruption-triggered rebuild-from-source is the dominant retrieval cause
    // while it runs — word it explicitly ("rebuilding because the index was corrupt") rather than
    // letting the user see a generic degraded state for an unexplained reason.
    var mig = workerView.migration();
    if (mig != null && "corrupt_index_rebuild".equals(mig.migrationSource())) {
      String migState = mig.migrationState();
      if ("MIGRATING".equalsIgnoreCase(migState) || "SWITCHING".equalsIgnoreCase(migState)) {
        return LifecycleReasonCode.INDEX_REBUILDING.code();
      }
    }
    var compat = workerView.compatibility();
    if (compat == null) {
      return null;
    }
    if ("BLOCKED_MISMATCH".equals(compat.indexSchemaCompatState())) {
      return LifecycleReasonCode.INDEX_SCHEMA_MISMATCH.code();
    }
    if ("BLOCKED_LEGACY".equals(compat.indexSchemaCompatState())) {
      return LifecycleReasonCode.INDEX_BLOCKED_LEGACY.code();
    }
    if ("BLOCKED_MISMATCH".equals(compat.embeddingCompatState())) {
      return LifecycleReasonCode.INDEX_EMBEDDING_MISMATCH.code();
    }
    if ("BLOCKED_LEGACY".equals(compat.embeddingCompatState())) {
      return LifecycleReasonCode.INDEX_EMBEDDING_LEGACY.code();
    }
    return null;
  }

  /**
   * Tempdoc 598 reopen (B-3): the dense/semantic leg cannot run for a reason a rebuild does NOT fix —
   * the embedding model is not loaded ({@code UNAVAILABLE} compat) or the embedder is unavailable on an
   * otherwise-{@code COMPATIBLE} index ({@code embeddingReady=false}). Returns
   * {@code index.dense_unavailable} for those POSITIVELY-known not-serviceable states, else {@code null}.
   *
   * <p>This makes the {@code retrieval} composite a faithful projection of the Worker's own issuance
   * predicate {@code denseServiceable = allowQueryEmbeddings()(==COMPATIBLE) && embeddingProvider.isAvailable()}
   * — reconstructed here from {@code embeddingCompatState} + {@code embeddingReady} (which equals
   * {@code isAvailable()} on the Worker, GrpcHealthService) — so the search banner stops claiming "fully
   * semantic" while AUTO has degraded to keyword. It does NOT fire for {@code BLOCKED_*} (handled by
   * {@link #compatBlockedReason} with the rebuild remedy), {@code REBUILDING} (owned by the 595 Stability
   * axis), or an UNKNOWN/empty compat or {@code null} {@code embeddingReady} (we never alarm on "don't
   * know" — only on a positively-known dense block).
   */
  static String denseUnavailableReason(WorkerOperationalView workerView) {
    var compat = workerView.compatibility();
    if (compat == null) {
      return null;
    }
    String embState = compat.embeddingCompatState();
    // No embedding model resolvable: dense genuinely cannot run (and a reindex won't add a model).
    if ("UNAVAILABLE".equals(embState)) {
      return LifecycleReasonCode.INDEX_DENSE_UNAVAILABLE.code();
    }
    // COMPATIBLE index but the embedder is explicitly down (e.g. model load failed): dense falls to
    // keyword (Fix-A resolveAutoDense), so the banner must not claim semantic. Only on an explicit
    // false — a null/unknown embeddingReady is left alone (no false alarm during startup windows).
    if ("COMPATIBLE".equals(embState) && Boolean.FALSE.equals(workerView.embeddingReady())) {
      return LifecycleReasonCode.INDEX_DENSE_UNAVAILABLE.code();
    }
    return null;
  }

  /**
   * Computes a single readiness component. The switch expression is exhaustive — adding a new
   * {@link ReadinessDimension} constant without a corresponding case arm is a compile error.
   */
  private ReadinessComponentView computeComponent(
      ReadinessDimension dim,
      WorkerOperationalView workerView,
      LifecycleSnapshotV1 lifecycleSnapshot,
      String observedAt) {
    return switch (dim) {
      case WORKER_CONTROL_PLANE ->
          readinessComponent(
              mapWorkerLifecycleToReadiness(lifecycleSnapshot.components().worker().state()),
              lifecycleSnapshot.components().worker().reason_code(),
              dim.source(),
              observedAt);

      case INDEX_SERVING -> {
        boolean indexHealthy = workerView.core().indexHealthy();
        String indexState = workerView.core().indexState();
        String workerReason = lifecycleSnapshot.components().worker().reason_code();
        String throughputReason = throughputReadinessReason(workerView);
        String compatBlockedReason = compatBlockedReason(workerView);
        String denseUnavailableReason = denseUnavailableReason(workerView);
        String state;
        String reason;

        if (LifecycleReasonCode.WORKER_NOT_CONFIGURED.code().equals(workerReason)) {
          state = READINESS_NOT_CONFIGURED;
          reason = LifecycleReasonCode.WORKER_NOT_CONFIGURED.code();
        } else if (compatBlockedReason != null) {
          // Tempdoc 600 Design A: a serving index can be HEALTHY for keyword search yet have its
          // dense/semantic leg BLOCKED (a legacy index with no embedding fingerprint, or a
          // model/schema mismatch). That actionable cause was previously visible only as a coarse
          // `reindexRequired` boolean (synthesized into a generic banner). Emit it as a
          // retrieval-composite reason code so the ONE verdict authority (595) names the real cause.
          state = READINESS_DEGRADED;
          reason = compatBlockedReason;
        } else if (indexHealthy && denseUnavailableReason != null) {
          // Tempdoc 598 reopen (B-3): the index serves keyword fine, but the dense leg can't run for a
          // non-rebuild reason (no embedding model / embedder down). Degrade the `retrieval` composite so
          // the banner reflects "semantic unavailable" instead of over-claiming "fully semantic". Ranked
          // below compatBlockedReason (a rebuild-fixable block is the more actionable cause).
          state = READINESS_DEGRADED;
          reason = denseUnavailableReason;
        } else if (indexHealthy && throughputReason != null) {
          state = READINESS_DEGRADED;
          reason = throughputReason;
        } else if (indexHealthy) {
          state = READINESS_READY;
          reason = null;
        } else if ("STARTING".equalsIgnoreCase(indexState)) {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.WORKER_STARTING.code();
        } else if ("NOT_STARTED".equalsIgnoreCase(indexState)) {
          state = READINESS_NOT_CONFIGURED;
          reason = LifecycleReasonCode.WORKER_NOT_STARTED.code();
        } else if ("ERROR".equalsIgnoreCase(indexState)
            || "UNAVAILABLE".equalsIgnoreCase(indexState)) {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.WORKER_UNAVAILABLE.code();
        } else {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.INDEX_NOT_HEALTHY.code();
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case AI -> {
        String state =
            mapInferenceLifecycleToReadiness(
                lifecycleSnapshot.components().inference().state());
        String reason = lifecycleSnapshot.components().inference().reason_code();
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case EMBEDDING -> {
        Boolean embReady = workerView.embeddingReady();
        String state =
            embReady == null
                ? READINESS_UNKNOWN
                : (Boolean.TRUE.equals(embReady) ? READINESS_READY : READINESS_NOT_READY);
        String reason =
            embReady == null
                ? LifecycleReasonCode.WORKER_HEALTH_EMBEDDING_PROBE_MISSING.code()
                : (Boolean.TRUE.equals(embReady)
                    ? null
                    : LifecycleReasonCode.WORKER_HEALTH_EMBEDDING_NOT_READY.code());
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case CHUNK_EMBEDDING -> {
        // DEGRADED-capped: emit DEGRADED (not NOT_CONFIGURED) so the retrieval
        // composite stays DEGRADED rather than NOT_CONFIGURED, which would block consumers.
        boolean chunkReady = workerView.enrichment().chunk().chunkVectorsReady();
        double chunkCoverage = workerView.enrichment().chunk().chunkVectorCoveragePercent();
        String state;
        String reason;
        // When the worker is in fallback, chunkVectorsReady is false and coverage is 0.
        if (!chunkReady && chunkCoverage == 0.0 && workerView.enrichment().chunk().chunkDocCount() == 0) {
          state = READINESS_DEGRADED;
          reason = LifecycleReasonCode.CHUNK_EMBEDDING_NOT_READY.code();
        } else if (chunkReady) {
          state = READINESS_READY;
          reason = null;
        } else {
          state = READINESS_DEGRADED;
          reason =
              (chunkCoverage > 0)
                  ? LifecycleReasonCode.CHUNK_EMBEDDING_IN_PROGRESS.code()
                  : LifecycleReasonCode.CHUNK_EMBEDDING_NOT_READY.code();
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case VISUAL_TEXT_EXTRACTION -> {
        var visual = workerView.visualExtraction();
        if (visual == null || visual.visualTextNeededCount() <= 0) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        String blockedReason = visual.ocrBlockedReason();
        if (blockedReason == null || blockedReason.isBlank()) {
          blockedReason = visual.vduBlockedReason();
        }
        if (blockedReason == null || blockedReason.isBlank()) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        yield readinessComponent(
            READINESS_DEGRADED,
            normalizeVisualExtractionReason(blockedReason),
            dim.source(),
            observedAt);
      }

      case VISUAL_DOCUMENT_UNDERSTANDING -> {
        var visual = workerView.visualExtraction();
        if (visual == null || visual.visualEnrichmentNeededCount() <= 0) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        String blockedReason = visual.vduBlockedReason();
        if (blockedReason == null || blockedReason.isBlank()) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        yield readinessComponent(
            READINESS_DEGRADED,
            normalizeVisualExtractionReason(blockedReason),
            dim.source(),
            observedAt);
      }

      case GPU -> {
        // Tempdoc 419 C3 V2 P3: GPU saturation surfaces SATURATED when sustained > 80% with
        // no activity gate open. Defaults to READY when the monitor isn't wired (NVML
        // unavailable / headless) — saturation is opt-in evidence, not a blocking signal.
        var monitor = gpuSaturationMonitor;
        if (monitor == null) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        long activityGate = computeGpuActivityGate(workerView);
        io.justsearch.ui.observability.GpuSaturationMonitor.Result gpuResult;
        try {
          gpuResult = monitor.compute(activityGate);
        } catch (RuntimeException e) {
          log.debug("GPU saturation compute failed: {}", e.getMessage());
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        if (!io.justsearch.ui.observability.GpuSaturationMonitor.STATE_SATURATED.equals(
            gpuResult.state())) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        yield readinessComponent(
            READINESS_DEGRADED,
            LifecycleReasonCode.GPU_SATURATED.code(),
            dim.source(),
            observedAt);
      }

      case TELEMETRY -> {
        // Tempdoc 419 C3 V2 P1: classify the live telemetry snapshot via the shared helper used
        // by GET /api/telemetry/health. Returns READY with null reason when the supplier isn't
        // wired (tests / boot paths without LocalTelemetry).
        var snapshot = currentTelemetrySnapshot();
        if (snapshot == null) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        var classification = TelemetryHealthClassifier.classify(snapshot);
        String state =
            classification.state() == LifecycleState.LIFECYCLE_STATE_READY
                ? READINESS_READY
                : READINESS_DEGRADED;
        yield readinessComponent(state, classification.reasonCode(), dim.source(), observedAt);
      }

      case LAMBDAMART_MODEL -> {
        // DEGRADED-capped: default to DEGRADED (not NOT_CONFIGURED) to avoid poisoning
        // the retrieval composite. The reason code still communicates "not configured" to the UI.
        String state = READINESS_DEGRADED;
        String reason = LifecycleReasonCode.LAMBDAMART_NOT_CONFIGURED.code();
        if (lambdamartRerankerSupplier != null && gplCoordinatorSupplier != null) {
          var reranker = lambdamartRerankerSupplier.get();
          var gplCoord = gplCoordinatorSupplier.get();
          if (reranker != null && gplCoord != null) {
            var trainingStatus = reranker.getTrainingStatus();
            if (reranker.isLoaded()) {
              state = READINESS_READY;
              reason = null;
            } else if (trainingStatus != null
                && "TRAINING".equals(trainingStatus.status())) {
              state = READINESS_DEGRADED;
              reason = LifecycleReasonCode.LAMBDAMART_TRAINING.code();
            } else if ((trainingStatus != null && "FAILED".equals(trainingStatus.status()))
                || gplCoord.getStatus().status()
                    == GplJobStatus.Status.FAILED
                || (trainingStatus != null
                    && "SUCCEEDED".equals(trainingStatus.status())
                    && !reranker.isLoaded())) {
              state = READINESS_DEGRADED;
              reason = LifecycleReasonCode.LAMBDAMART_FAILED.code();
            } else if (trainingStatus != null && trainingStatus.lastTrainedAt() != null) {
              state = READINESS_DEGRADED;
              reason = LifecycleReasonCode.LAMBDAMART_FAILED.code();
            }
          }
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }
    };
  }

  private static String normalizeVisualExtractionReason(String blockedReason) {
    return switch (blockedReason) {
      case "ocr.disabled" -> LifecycleReasonCode.OCR_DISABLED.code();
      case "ocr.engine_missing" -> LifecycleReasonCode.OCR_ENGINE_MISSING.code();
      case "ocr.language_missing" -> LifecycleReasonCode.OCR_LANGUAGE_MISSING.code();
      case "vdu.ai_offline" -> LifecycleReasonCode.VDU_AI_OFFLINE.code();
      case "vdu.insufficient_vram" -> LifecycleReasonCode.VDU_INSUFFICIENT_VRAM.code();
      case "vdu.missing_mmproj" -> LifecycleReasonCode.VDU_MISSING_MMPROJ.code();
      case "vdu.circuit_open" -> LifecycleReasonCode.VDU_CIRCUIT_OPEN.code();
      default -> LifecycleReasonCode.OCR_ENGINE_MISSING.code();
    };
  }

  private static ReadinessComponentView readinessComponent(
      String state, String reasonCode, String source, String observedAt) {
    return new ReadinessComponentView(state, reasonCode, source, observedAt, false, 0);
  }

  private static ReadinessCompositeView readinessComposite(
      List<String> states, List<String> reasonCodes) {
    List<String> reasons = new ArrayList<>();
    for (String reasonCode : reasonCodes) {
      if (reasonCode != null && !reasonCode.isBlank()) {
        reasons.add(reasonCode);
      }
    }
    return new ReadinessCompositeView(combineReadinessState(states), reasons);
  }

  private static String combineReadinessState(List<String> states) {
    if (states.stream().anyMatch(READINESS_NOT_READY::equals)) return READINESS_NOT_READY;
    if (states.stream().anyMatch(READINESS_UNKNOWN::equals)) return READINESS_UNKNOWN;
    if (states.stream().anyMatch(READINESS_NOT_CONFIGURED::equals)) return READINESS_NOT_CONFIGURED;
    if (states.stream().anyMatch(READINESS_DEGRADED::equals)) return READINESS_DEGRADED;
    return READINESS_READY;
  }

  private static String mapWorkerLifecycleToReadiness(LifecycleState state) {
    if (state == null) return READINESS_UNKNOWN;
    return switch (state) {
      case LIFECYCLE_STATE_READY -> READINESS_READY;
      case LIFECYCLE_STATE_DEGRADED -> READINESS_DEGRADED;
      case LIFECYCLE_STATE_STARTING -> READINESS_NOT_READY;
      case LIFECYCLE_STATE_ERROR, LIFECYCLE_STATE_STOPPING, LIFECYCLE_STATE_STOPPED ->
          READINESS_NOT_READY;
      case LIFECYCLE_STATE_UNSPECIFIED, UNRECOGNIZED -> READINESS_UNKNOWN;
    };
  }

  private static String mapInferenceLifecycleToReadiness(LifecycleState state) {
    if (state == null) return READINESS_UNKNOWN;
    return switch (state) {
      case LIFECYCLE_STATE_READY -> READINESS_READY;
      case LIFECYCLE_STATE_DEGRADED -> READINESS_DEGRADED;
      case LIFECYCLE_STATE_STARTING -> READINESS_NOT_READY;
      case LIFECYCLE_STATE_ERROR, LIFECYCLE_STATE_STOPPING, LIFECYCLE_STATE_STOPPED ->
          READINESS_NOT_READY;
      case LIFECYCLE_STATE_UNSPECIFIED, UNRECOGNIZED -> READINESS_UNKNOWN;
    };
  }

  private static boolean readinessComponentReady(
      ReadinessEnvelopeView envelope, String componentKey) {
    if (envelope == null) return false;
    ReadinessComponentView comp = envelope.components().get(componentKey);
    return comp != null && READINESS_READY.equals(comp.state());
  }
}
