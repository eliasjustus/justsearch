/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeBuilder;
import io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.MmfWorkerSignalBus;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingFingerprint;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingMetadataOverlay;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.recovery.IndexRecoveryPolicy;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.index.MigrationProgressStore;
import io.justsearch.indexerworker.liveness.LivenessWindows;
import io.justsearch.indexerworker.util.IndexRootLock;
import io.justsearch.indexerworker.grpc.DelegatingHealthService;
import io.justsearch.indexerworker.grpc.DelegatingIngestService;
import io.justsearch.indexerworker.grpc.DelegatingSearchService;
import io.justsearch.indexerworker.grpc.RequestMetadataInterceptor;
import io.justsearch.indexerworker.grpc.TracingServerInterceptor;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerGrpcWiring;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexerworker.server.ops.KnowledgeServerSafeMetrics;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.model.VariantSelector;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.RepoRootLocator;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.NativeSessionHandle;
import io.justsearch.telemetry.JvmRuntimeGauges;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Knowledge Server hosts gRPC services for search and indexing.
 *
 * <p>This is the main entry point for the isolated worker process that handles:
 * <ul>
 *   <li>Search queries via gRPC</li>
 *   <li>Batch ingestion of file paths</li>
 *   <li>Background indexing loop</li>
 *   <li>Process coordination via MMF (memory-mapped file)</li>
 * </ul>
 *
 * <p>The server binds to an ephemeral port (port 0) and writes the actual bound
 * port to the signal bus for discovery by the main process.
 */
public final class KnowledgeServer implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
  private static final long MIGRATION_SWITCHING_QUEUE_DEPTH_THRESHOLD = 1_000L;
  private static final long MIGRATION_SWITCHING_MAX_DURATION_MS = 30L * 60_000L;

  private final WorkerConfig config;
  // Package-private: accessed by DevReloadManager for build-stamp update (371)
  final Path dataDir;
  private Telemetry telemetry;
  // Tempdoc 417 Phase 1: typed catalog of index.runtime.* metrics, populated at boot.
  private io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog indexRuntimeCatalog;
  // Tempdoc 414: ORT session lifecycle events recorder, populated at boot. Threaded into
  // InferenceCompositionRoot.compose so OrtSessionAssembler.buildManager picks it up.
  private io.justsearch.ort.telemetry.OrtSessionTelemetryEvents ortSessionEvents;
  // Tempdoc 413: typed catalog of embedding.runtime.* metrics + façade. Catalog constructed at
  // boot with a deferred cache-size supplier that tolerates `embeddingService==null` until
  // initDeferredModels wires the service. Façade is passed into EmbeddingService and IndexingLoop.
  // Package-private: DevReloadManager re-wires the events sink after hot-reload reconstruction.
  io.justsearch.indexerworker.embed.EmbeddingMetricCatalog embeddingMetricCatalog;
  io.justsearch.indexerworker.embed.EmbeddingTelemetry embeddingTelemetry;

  private Path indexBasePath;
  private Path activeIndexPath;
  private Path buildingIndexPath;
  private IndexGenerationManager indexGenerationManager;
  private IndexRootLock indexRootLock;
  private volatile boolean migrationEnumeratorDone;

  // Package-private: accessed by DevReloadManager for hot-reload (tempdoc 305 Phase 2)
  WorkerSignalBus signalBus;
  private JobQueue jobQueue;

  // Tempdoc 550 Thesis II / 575 §4.3b (liveness): periodic reaper re-queues PROCESSING rows orphaned by a
  // dead worker. The loop now heartbeats its rows (JobQueue.heartbeatProcessing), so a fresh last_updated
  // means a LIVE OWNER — letting the window tighten from 15 min to a true 5-min liveness window.
  // Generated from the register (tempdoc 575 §17 Face A) — single source, cannot drift.
  private static final long STALE_PROCESSING_MS = LivenessWindows.REAPER_STALE_MS; // 5 min liveness window
  private static final long REAP_INTERVAL_MS = 2 * 60_000L; // check every 2 min (poll cadence, not a window)
  private java.util.concurrent.ScheduledExecutorService stuckJobReaper;
  // Tempdoc 419 / T5.1 (ADR-0028): scoped reverse-lookup store. Constructed in init() against
  // the same jobs.db as JobQueue; closed in shutdown alongside JobQueue.
  private io.justsearch.indexerworker.queue.SqlitePathResolutionStore pathResolutionStore;
  private LuceneRuntime searchLifecycle;
  private LuceneRuntime ingestLifecycle;
  EmbeddingService embeddingService;
  EmbeddingCompatibilityController embeddingCompatController;
  volatile WorkerAppServices appServices;
  DelegatingSearchService searchWrapper;
  DelegatingIngestService ingestWrapper;
  DelegatingHealthService healthWrapper;
  io.justsearch.indexerworker.disambiguation.DisambiguationService disambiguationService;
  io.justsearch.indexerworker.ner.NerService nerServiceInstance;
  io.justsearch.indexerworker.splade.SpladeEncoder spladeEncoderInstance;
  io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder spladeIdfQueryEncoder;
  io.justsearch.indexerworker.bgem3.BgeM3Encoder bgeM3EncoderInstance;
  io.justsearch.reranker.CrossEncoderReranker searchRerankerInstance;
  io.justsearch.reranker.CitationScorer citationScorerInstance;
  // Tempdoc 397 §14.26 T2-C1/C2: surface returned by InferenceCompositionRoot.compose;
  // owns SessionHandle lifetimes closed on shutdown.
  volatile InferenceSurface inferenceSurface;
  // Phase 3c: WorkerOpsMetricCatalog replaces all worker.* gauge / observable-counter fields.
  // Catalog instance is retained for lifetime; OTel async callbacks fire at flush time.
  @SuppressWarnings("unused")
  private io.justsearch.indexerworker.services.WorkerOpsMetricCatalog workerOpsCatalog;
  private Server grpcServer;
  InfraContext infraCtx; // package-private: DevReloadManager
  volatile CompletableFuture<ModelContext> deferredModelInit; // package-private: DevReloadManager
  private DevReloadManager devReloadManager;
  private io.justsearch.telemetry.TracingBootstrap tracingBootstrap;
  private Thread sentinelThread;
  private Thread migrationEnumeratorThread;
  private Thread migrationCutoverThread;
  private volatile boolean running;
  private int migrationCutoverMaxFailedJobs = -1;

  // Migration enumerator progress (best-effort observability)
  private final AtomicBoolean migrationEnumeratorRunning = new AtomicBoolean(false);
  private final AtomicLong migrationEnumeratorRootsTotal = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorRootsDone = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorFilesSeen = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorFilesEnqueued = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorStartedAtMs = new AtomicLong(0L);
  private final AtomicLong migrationEnumeratorFinishedAtMs = new AtomicLong(0L);
  private final AtomicReference<String> migrationEnumeratorLastPath = new AtomicReference<>("");
  private MigrationProgressStore migrationProgressStore;
  private volatile MigrationProgressSnapshot persistedMigrationProgressSnapshot;

  /**
   * Gate released by {@link #initDeferredModels()} after all models are wired
   * (embedding + ECC + SPLADE + BGE-M3 + NER + reranker + citation scorer). The
   * {@code finally} block ensures the latch releases even if init fails partway through.
   *
   * <p><strong>Shared by two independent consumers</strong> — changes to the release
   * sequence must consider both:
   *
   * <ul>
   *   <li><b>Migration enumerator</b> (tempdoc 332) — the background thread at
   *       {@code migrationEnumeratorThread} awaits this latch before enqueuing files so
   *       the {@code IndexingLoop} does not process docs before SPLADE/embedding exist
   *       (would produce text-only docs needing slow RMW backfill post-cutover).</li>
   *   <li><b>Query handlers</b> (tempdoc 397 §14.28 U3) — wired via
   *       {@link io.justsearch.indexerworker.server.WorkerAppServices#wireModelReadyLatch}
   *       in {@link #initDeferredModels()}, consumed by
   *       {@code GrpcSearchService.awaitModelsReady(...)} on entry of
   *       {@code search}/{@code retrieveContext}/{@code rerank}/{@code matchCitations}.
   *       Closes a boot-race regression where queries arriving before init completed
   *       silently missed reranker + citation wiring.</li>
   * </ul>
   *
   * <p>Both consumers fall through to a degraded path on timeout (120 s). Splitting the
   * latch is possible but not currently warranted — the release point (all models wired)
   * is identical for both.
   */
  private final CountDownLatch modelReadyLatch = new CountDownLatch(1);

  // Late-binding fingerprint supplier for commit metadata overlay.
  // Set after EmbeddingCompatibilityController is created.
  private final AtomicReference<java.util.function.Supplier<java.util.Optional<String>>> embeddingFingerprintSupplier =
      new AtomicReference<>(java.util.Optional::empty);

  /**
   * Creates a new KnowledgeServer with the specified configuration.
   *
   * @param config Worker configuration
   */
  public KnowledgeServer(WorkerConfig config) {
    this.config = config;
    this.dataDir = config.dataDir();
  }

  /**
   * Starts the Knowledge Server and all its components.
   *
   * <p>Initialization order:
   * <ol>
   *   <li>Open signal bus (MMF)</li>
   *   <li>Open job queue (SQLite)</li>
   *   <li>Initialize Lucene runtime</li>
   *   <li>Start gRPC server on port 0</li>
   *   <li>Write bound port to signal bus</li>
   *   <li>Start indexing loop</li>
   *   <li>Start sentinel thread (liveness monitor)</li>
   * </ol>
   *
   * @throws IOException if initialization fails
   */
  public void start() throws IOException {
    log.info("Starting KnowledgeServer...");
    running = true;
    long t0 = System.nanoTime();
    long tPrev = t0;
    long tPhase;

    try {
      // 0. Initialize Worker-owned telemetry (must not write to the Head metrics file).
      // Tempdoc 417 Phase 1: register IndexRuntimeMetricCatalog.DEFINITIONS so the SDK builds
      // per-metric Views (tag schemas + bucket bounds + exemplar policies) before the
      // SdkMeterProvider is built. F2 fix: catalog has only a registry-arg constructor (final
      // fields), so we wrap the static DEFINITIONS list as a definitions-only catalog and
      // construct the typed catalog after LocalTelemetry exists.
      LocalTelemetry workerTelemetry =
          new LocalTelemetry(
              dataDir,
              config.telemetryFlushMs(),
              "justsearch-worker",
              config.serviceVersion(),
              "metrics-worker.ndjson",
              List.of(
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.loop.IndexingPipelineMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.extract.ExtractionMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.extract.ExtractionMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.extract.OcrMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.extract.OcrMetricCatalog.DEFINITIONS),
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.services.WorkerOpsMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.services.WorkerOpsMetricCatalog.DEFINITIONS),
                  // Tempdoc 417 → 410 merge: ingestion.outcome_write_failures_total
                  // (introduced by 410 Slice A2) routes through IngestionOutcomeMetricCatalog.
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.loop.IngestionOutcomeMetricCatalog.DEFINITIONS),
                  // Tempdoc 417 → 418 merge: WorkerMethvinWatcher emits index.watcher.events_total
                  // with a worker-specific tag schema (component + kind) via
                  // WorkerWatcherMetricCatalog. The Head-side WatcherMetricCatalog in app-indexing
                  // remains for the (now-deprecated) head-side watcher path; the metric name is
                  // shared but per-process tag schemas differ.
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.services.WorkerWatcherMetricCatalog.DEFINITIONS),
                  // Tempdoc 414: ort.session.* lifecycle metrics for every NativeSessionHandle
                  // instance (one per encoder). Adapter constructed below; threaded into
                  // InferenceCompositionRoot.compose so OrtSessionAssembler.buildManager picks it up.
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.observability.OrtSessionMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.observability.OrtSessionMetricCatalog.DEFINITIONS),
                  // Tempdoc 413: embedding.runtime.* metrics for EmbeddingService lifecycle
                  // (cache hit/miss/size, invoke failures, hot-unload, chunked branch).
                  io.justsearch.telemetry.catalog.MetricCatalog.of(
                      io.justsearch.indexerworker.embed.EmbeddingMetricCatalog.NAMESPACE,
                      io.justsearch.indexerworker.embed.EmbeddingMetricCatalog.DEFINITIONS),
                  // Phase 3d: JVM gauges flow through JvmMetricCatalog. Namespace is the prefix
                  // baked into the metric names ("worker"), so the per-View archive declarations
                  // and tag schemas are wired at SDK boot.
                  io.justsearch.telemetry.JvmMetricCatalog.catalogFor("worker")));
      // Phase 3b: status gauges read from RunningRuntime when available; supplier is invoked
      // at every flush, so it tolerates `ingestLifecycle` being null/DeferredRuntime at
      // bootstrap and switches to the live snapshot once RunningRuntime is constructed.
      this.indexRuntimeCatalog =
          new io.justsearch.indexerworker.services.IndexRuntimeMetricCatalog(
              workerTelemetry.registry(),
              () -> {
                LuceneRuntime r = this.ingestLifecycle;
                if (r instanceof RunningRuntime running) {
                  return running.runtimeGaugesSnapshot();
                }
                return LuceneRuntimeTypes
                    .RuntimeGaugesSnapshot.EMPTY;
              });
      // Tempdoc 414: typed events recorder for ORT session lifecycle. Cache pre-populated
      // for every EncoderRole.values() consumer name so onSemaphoreWait never allocates on the
      // hot path. forAllRoles() factory derives the consumer set from EncoderRole — single
      // source of truth (tempdoc 414 v2 C1 fix).
      var ortSessionCatalog =
          new io.justsearch.indexerworker.observability.OrtSessionMetricCatalog(
              workerTelemetry.registry());
      this.ortSessionEvents =
          io.justsearch.indexerworker.observability.OrtSessionTelemetryAdapter.forAllRoles(
              ortSessionCatalog);
      // Tempdoc 413: cache-size supplier reads through the deferred this.embeddingService
      // field — null until initDeferredModels constructs the service. The supplier is invoked at
      // every OTel flush, so it tolerates the boot-time null case (returns 0L).
      this.embeddingMetricCatalog =
          new io.justsearch.indexerworker.embed.EmbeddingMetricCatalog(
              workerTelemetry.registry(),
              () -> {
                EmbeddingService es = this.embeddingService;
                return es != null ? (long) es.cacheSize() : 0L;
              });
      this.embeddingTelemetry =
          new io.justsearch.indexerworker.embed.EmbeddingTelemetry(this.embeddingMetricCatalog);
      telemetry = workerTelemetry;

      // 0b. Initialize tracing (must happen before service class loading at step 3b).
      // Read config directly from EnvRegistry — ConfigStore is not ready until step 3.
      String tracingLevel = EnvRegistry.INDEX_TRACING_LEVEL
          .getString("none").toLowerCase(Locale.ROOT);
      if (!"none".equals(tracingLevel)) {
        try {
          tracingBootstrap = io.justsearch.telemetry.TracingBootstrap.forIndexing(
              dataDir, ((LocalTelemetry) telemetry).getHealthState(), tracingLevel);
          log.info("Worker tracing initialized: level={}", tracingLevel);
        } catch (IllegalStateException e) {
          log.debug("GlobalOpenTelemetry already set, skipping TracingBootstrap: {}", e.getMessage());
        }
      }

      tPhase = System.nanoTime();
      long telemetryMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 1. Initialize signal bus.
      // Tempdoc 630: pass Head's PID (forwarded via EnvRegistry.HEAD_PID) so the suicide-pact can
      // distinguish a real Head death from a benign OS-resume stale heartbeat. Read directly from
      // EnvRegistry (ConfigStore is not ready until step 3, like INDEX_TRACING_LEVEL above);
      // 0 ⇒ unknown ⇒ heartbeat-only (pre-630) behavior (standalone runs).
      Path signalPath = dataDir.resolve("worker_signal.lock");
      long headPid = EnvRegistry.HEAD_PID.getLong(0L);
      signalBus = new MmfWorkerSignalBus(signalPath, headPid);
      signalBus.open();

      tPhase = System.nanoTime();
      long signalBusMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 2. Initialize job queue (with corruption triage). Tempdoc 417 Phase 3c: write-failure
      // callback late-binds to WorkerOpsMetricCatalog (constructed later in
      // registerTelemetryGauges); the lambda silently no-ops until the catalog is wired.
      Path dbPath = dataDir.resolve("jobs.db");
      Runnable onSwitchBufferWriteFailure = () -> {
        var c = this.workerOpsCatalog;
        if (c != null) {
          c.switchBufferWriteFailures.increment(io.justsearch.telemetry.catalog.EmptyTags.INSTANCE);
        }
      };
      jobQueue = new SqliteJobQueue(dbPath, 3, onSwitchBufferWriteFailure);
      try {
        jobQueue.open();
      } catch (SQLException e) {
        // Check for database corruption (SQLite error code 11 = SQLITE_CORRUPT)
        if (e.getErrorCode() == 11) {
          log.error("Database corruption detected in jobs.db. Initiating triage...", e);
          try {
            jobQueue.close();
          } catch (IOException closeEx) {
            log.warn("Failed to close corrupt database handle", closeEx);
          }
          handleCorruptDatabase(dbPath);
        } else {
          throw e;
        }
      }
      jobQueue.recoverStuckJobs();

      // Tempdoc 550 Thesis II: periodic liveness reaper. recoverStuckJobs() above heals on
      // startup; this re-queues PROCESSING rows orphaned WITHOUT a restart (worker claimed a job
      // then died mid-process while the Head/UI keep running), so the rail never shows a dead job
      // as perpetually "running". Age-bounded → never touches actively-draining jobs.
      JobQueue reaperQueue = jobQueue;
      stuckJobReaper =
          java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "stuck-job-reaper");
                t.setDaemon(true);
                return t;
              });
      stuckJobReaper.scheduleWithFixedDelay(
          () -> {
            try {
              reaperQueue.recoverStuckJobs(STALE_PROCESSING_MS);
            } catch (RuntimeException reapErr) {
              log.warn("stuck-job reaper tick failed (will retry): {}", reapErr.toString());
            }
          },
          REAP_INTERVAL_MS,
          REAP_INTERVAL_MS,
          TimeUnit.MILLISECONDS);

      tPhase = System.nanoTime();
      long jobQueueMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 3. Resolve generation-scoped index path BEFORE opening Lucene.
      // Prefer ConfigStore (populated from worker snapshot); fall back to RuntimeConfig.
      ConfigStore cs = ConfigStore.globalOrNull();
      ResolvedConfig rc = cs != null ? cs.get() : null;
      if (rc == null) {
        throw new IllegalStateException("ConfigStore not initialized — cannot start KnowledgeServer");
      }

      this.migrationCutoverMaxFailedJobs = rc.index().migrationCutoverMaxFailedJobs();

      Path effectiveIndexBasePath = rc.paths().indexBasePath();

      // Acquire a lock for the effective index root to prevent two Workers from mutating the same
      // indexBasePath (important when justsearch.index.base_path is overridden).
      this.indexRootLock = new IndexRootLock(effectiveIndexBasePath);
      this.indexRootLock.acquire();

      IndexGenerationManager genManager = new IndexGenerationManager(effectiveIndexBasePath);
      IndexGenerationManager.IndexLayout layout = genManager.initializeOrLoad();
      this.indexGenerationManager = genManager;
      this.indexBasePath = layout.basePath();
      this.activeIndexPath = layout.activeGenerationPath();
      this.migrationProgressStore = new MigrationProgressStore(this.indexBasePath);
      this.persistedMigrationProgressSnapshot = this.migrationProgressStore.readBestEffort();
      IndexGenerationManager.State state = layout.state();
      this.buildingIndexPath = null;
      this.migrationEnumeratorDone = false;

      logConfiguration();

      // 4. Initialize Lucene runtimes.
      // - searchLifecycle serves queries (Blue during migration)
      // - ingestLifecycle performs all writes (Green during migration; Active when not migrating)
      //
      // If a migration is already in progress, honor state.json and wire Blue/Green accordingly.
      IndexGenerationManager.MigrationState ms =
          parseMigrationState(state == null ? null : state.migration_state());
      String buildingGenId = state == null ? null : state.building_generation();
      boolean inProgress =
          (ms == IndexGenerationManager.MigrationState.MIGRATING
              || ms == IndexGenerationManager.MigrationState.SWITCHING
              || ms == IndexGenerationManager.MigrationState.FAILED)
              && buildingGenId != null
              && !buildingGenId.isBlank();

      // Create a late-binding supplier for the fingerprint overlay
      java.util.function.Supplier<java.util.Optional<String>> fpSupplier =
          () -> embeddingFingerprintSupplier.get().get();

      if (inProgress) {
        // Serve search from active generation (Blue) while writing to building generation (Green).
        this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).openReadOnly();
        this.buildingIndexPath = genManager.resolveGenerationPathStrict(buildingGenId);
        this.ingestLifecycle =
            buildIndexRuntime(buildingIndexPath, fpSupplier)
                .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                .open();
      } else {
        // Normal operation: single runtime against active generation.
        //
        // Tempdoc 406 Phase 4a: deferred-writer mode is re-enabled. When the index
        // has existing segments, openDeferred() opens read-only first (fast); the
        // background initDeferredModels later calls DeferredRuntime.upgradeWriter()
        // which returns a fresh RunningRuntime. KS reconstructs appServices and
        // swaps the gRPC wrappers via reconstructAppServicesAfterDeferredUpgrade()
        // so write methods become available without restarting the gRPC server.
        boolean useDeferredWriter = hasLuceneSegments(activeIndexPath);
        try {
          LuceneRuntimeBuilder builder =
              buildIndexRuntime(activeIndexPath, fpSupplier)
                  .withBuildState(LuceneRuntimeTypes.BuildState.COMPLETE);
          this.ingestLifecycle = useDeferredWriter ? builder.openDeferred() : builder.open();
          this.searchLifecycle = this.ingestLifecycle;

          // tempdoc 628 Stage B (G3): if the adapter recovered this index to empty on open it dropped
          // a rebuild-pending marker. Rebuild from the source files still on disk (blue/green) rather
          // than serving a silently-empty index — unless policy is BACKUP_ONLY/FAIL_CLOSED.
          if (IndexRecoveryMarker.exists(activeIndexPath)
              && IndexRecoveryPolicy.shouldRebuildFromSource(rc.index())) {
            log.warn(
                "Index at {} was recovered to empty (reason={}). Rebuilding from source via blue/green...",
                activeIndexPath,
                IndexRecoveryMarker.readReason(activeIndexPath));
            this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).openReadOnly();
            IndexGenerationManager.State migrated = genManager.startMigration("corrupt_index_rebuild");
            String greenGenId = migrated == null ? null : migrated.building_generation();
            if (greenGenId == null || greenGenId.isBlank()) {
              throw new IOException(
                  "Failed to start corruption-recovery rebuild: building_generation missing");
            }
            this.ingestLifecycle.close();
            this.buildingIndexPath = genManager.resolveGenerationPathStrict(greenGenId);
            this.ingestLifecycle =
                buildIndexRuntime(buildingIndexPath, fpSupplier)
                    .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                    .open();
            startMigrationEnumeratorBestEffort(rc);
            IndexRecoveryMarker.clear(activeIndexPath);
          }

          // 312 Phase 4: Check for embedding model fingerprint mismatch.
          // If the on-disk model changed since the index was last committed, and
          // blue_green_migrate policy is enabled, start a migration to rebuild the
          // index with the new embedding model. This avoids the slow per-doc RMW
          // backfill path and instead re-ingests all files with inline embedding.
          String schemaMismatchPolicy = rc.index().schemaMismatchPolicy();
          // Use latestCommitUserDataBestEffort (not openTimeCommitUserData) because
          // openTimeCommitUserData captures from the reader at open time — which may not
          // include the fingerprint if the last commit before shutdown didn't stamp it.
          Map<String, String> commitMeta =
              this.ingestLifecycle.latestCommitUserDataBestEffort();
          String storedFp = commitMeta.get("embedding_model_sha256");
          if (buildingIndexPath == null
              && "blue_green_migrate".equalsIgnoreCase(schemaMismatchPolicy)) {
            if (storedFp != null && !storedFp.isBlank()) {
              java.util.Optional<String> currentFp = EmbeddingFingerprint.get();
              if (currentFp.isPresent() && !storedFp.equals(currentFp.get())) {
                log.warn(
                    "Embedding model fingerprint mismatch on active generation {}. "
                        + "Stored: {}..., Current: {}... Starting Blue/Green migration...",
                    activeIndexPath,
                    storedFp.substring(0, Math.min(16, storedFp.length())),
                    currentFp.get().substring(0, Math.min(16, currentFp.get().length())));

                // Reopen the active index read-only (Blue) for serving search.
                this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).openReadOnly();

                // Green: create a new generation and start a writable runtime.
                IndexGenerationManager.State migrated =
                    genManager.startMigration("embedding_model_change");
                String greenGenId =
                    migrated == null ? null : migrated.building_generation();
                if (greenGenId == null || greenGenId.isBlank()) {
                  throw new IOException(
                      "Failed to start embedding migration: building_generation missing");
                }
                // Close the writable runtime on the old generation before opening Green.
                this.ingestLifecycle.close();
                this.buildingIndexPath = genManager.resolveGenerationPathStrict(greenGenId);
                this.ingestLifecycle =
                    buildIndexRuntime(buildingIndexPath, fpSupplier)
                        .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                        .open();

                startMigrationEnumeratorBestEffort(rc);
              }
            }
          }
        } catch (IndexRuntimeIOException e) {
          String schemaMismatchPolicy = rc.index().schemaMismatchPolicy();
          if (e.reason() == IndexRuntimeIOException.Reason.SCHEMA_MISMATCH
              && "blue_green_migrate".equalsIgnoreCase(schemaMismatchPolicy)) {
            // Auto-start Blue/Green migration on schema mismatch when enabled.
            log.warn(
                "Schema mismatch detected on active generation {}. Starting Blue/Green migration (policy={})...",
                activeIndexPath,
                schemaMismatchPolicy,
                e);

            // Blue: open existing index in read-only mode for serving search.
            this.searchLifecycle = buildReadOnlyRuntime(activeIndexPath).openReadOnly();

            // Green: create a new generation and start a writable runtime.
            IndexGenerationManager.State migrated = genManager.startMigration("schema_mismatch");
            String greenGenId =
                migrated == null ? null : migrated.building_generation();
            if (greenGenId == null || greenGenId.isBlank()) {
              throw new IOException(
                  "Failed to start migration: building_generation missing in state.json");
            }
            this.buildingIndexPath = genManager.resolveGenerationPathStrict(greenGenId);
            this.ingestLifecycle =
                buildIndexRuntime(buildingIndexPath, fpSupplier)
                    .withBuildState(LuceneRuntimeTypes.BuildState.BUILDING)
                    .open();

            // Kick off background enumeration to populate Green.
            startMigrationEnumeratorBestEffort(rc);
          } else {
            throw e;
          }
        }
      }

      // If a migration is in progress (Blue/Green), ensure the enumerator + cutover monitor are running.
      if (buildingIndexPath != null && searchLifecycle != null && ingestLifecycle != null && searchLifecycle != ingestLifecycle) {
        startMigrationEnumeratorBestEffort(rc);
        startMigrationCutoverMonitorBestEffort();
      }

      // Schema validation: ensure all indexable fields exist in catalog (via ingest schema).
      if (ingestLifecycle != null) {
        ingestLifecycle.schema().validateIndexableFields(SchemaFields.INDEXABLE_FIELDS);
      }

      // Apply any durable SWITCHING buffer ops. In deferred-writer mode, this is deferred
      // to the background task (after IndexWriter opens). In migration mode, run synchronously.
      if (ingestLifecycle != null && !(ingestLifecycle instanceof DeferredRuntime)) {
        drainSwitchBufferBestEffort();
      }

      tPhase = System.nanoTime();
      long luceneMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 3.5 Construct application services via registry (models wired later via deferred init)
      // Tempdoc 419 / T5.1 (ADR-0028): construct PathResolutionStore against the same jobs.db
      // already migrated by SqliteJobQueue. Threaded through InfraContext so the gRPC handler
      // and IndexingLoop can both consume it without violating module dependency direction.
      this.pathResolutionStore =
          new io.justsearch.indexerworker.queue.SqlitePathResolutionStore(dbPath);
      this.infraCtx =
          new InfraContext(
              config,
              jobQueue,
              () -> this.searchLifecycle,
              () -> this.ingestLifecycle,
              signalBus,
              telemetry,
              ((LocalTelemetry) telemetry).registry(),
              indexBasePath,
              activeIndexPath,
              this::migrationProgressSnapshot,
              MIGRATION_SWITCHING_MAX_DURATION_MS,
              this::initiateShutdown,
              pathResolutionStore);
      // 516 P3 FINAL CUT: see newAppServices() — DWAS now pre-wires the migration
      // supplier + embedding telemetry at ctor time (last 2 setters eliminated).
      appServices = newAppServices();
      wireAppServicesPostConstruction(appServices);

      // Dev hot-reload manager (Phase 2, tempdoc 305)
      if (ConfigStore.global().get().ai().devHotReload()) {
        devReloadManager = new DevReloadManager(this);
        log.info("Dev hot-reload enabled (justsearch.dev.hotreload=true)");
      }

      registerTelemetryGauges();

      tPhase = System.nanoTime();
      long initMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 4. Create and start gRPC server on ephemeral port (before model loading)
      List<ServerInterceptor> interceptors = List.of(
          new TracingServerInterceptor(),
          new RequestMetadataInterceptor()
      );

      grpcServer = createGrpcServer(interceptors);
      grpcServer.start();

      int boundPort = grpcServer.getPort();
      log.info("gRPC server started on port {}", boundPort);

      // 5. Write port to signal bus — Head is unblocked from here
      signalBus.writePort(boundPort);

      tPhase = System.nanoTime();
      long grpcMs = (tPhase - tPrev) / 1_000_000;
      tPrev = tPhase;

      // 6. Start indexing loop (runs immediately; null-gates embedding/SPLADE until wired)
      appServices.startIndexingLoop();

      // 7. Start sentinel thread for liveness monitoring
      startSentinelThread();

      long loopMs = (System.nanoTime() - tPrev) / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      log.info(
          "Startup phases (ms): telemetry={}, signalBus={}, jobQueue={}, lucene={}, init={}, grpc={}, loop={}, total={} [models loading in background]",
          telemetryMs, signalBusMs, jobQueueMs, luceneMs, initMs, grpcMs, loopMs, totalMs);

      log.info("KnowledgeServer started successfully on port {}", boundPort);

      // --- Deferred model initialization (background) ---
      // Models load in a background thread while gRPC is already serving. Callers
      // are null-safe: search degrades to BM25, IndexingLoop skips embedding/SPLADE,
      // ingest queues jobs normally. Models become available via volatile setters.
      deferredModelInit = CompletableFuture.supplyAsync(this::initDeferredModels);

    } catch (Exception e) {
      log.error("Failed to start KnowledgeServer", e);
      // tempdoc 628 Stage D-part2: if startup failed because the index is corrupt and could not be
      // auto-recovered (FAIL_CLOSED / recovery-failed), stamp a fatal-reason marker so the Head can
      // offer a "Rebuild index" affordance instead of blind-restarting. This is a controlled exit (the
      // throw below → IndexerWorker's handler → System.exit), so the write is reliable. Only corruption
      // writes it — other fatal causes stay generic.
      if (isCorruptIndexCause(e)) {
        io.justsearch.ipc.WorkerFatalReasonMarker.write(
            dataDir, io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_CORRUPT);
      }
      closeQuietly();
      throw new IOException("Failed to start KnowledgeServer", e);
    }
  }

  /** True if a startup failure was caused by an unrecoverable corrupt Lucene index (628 Stage D-part2). */
  private static boolean isCorruptIndexCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException ire
          && ire.reason() == io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException.Reason.CORRUPT_INDEX) {
        return true;
      }
    }
    return false;
  }

  /**
   * Constructs a {@link DefaultWorkerAppServices} with the 2 KS-owned pre-wired values
   * (migration-active supplier + embedding telemetry) already supplied at ctor time.
   * Tempdoc 516 P3 final cut — eliminates the last 2 post-ctor setters on IndexingLoop.
   *
   * <p>The migration lambda captures {@code KS.this} and reads {@code buildingIndexPath},
   * {@code searchLifecycle}, {@code ingestLifecycle} at call time (during indexing — well
   * after KS init completes). Safe to create whenever {@code KS.this} exists.
   *
   * <p>Shared by the boot-time construction, the post-deferred-upgrade reconstruction, and
   * {@link DevReloadManager}'s hot-reload path so all three observe the same wiring.
   */
  DefaultWorkerAppServices newAppServices() {
    return new DefaultWorkerAppServices(
        infraCtx,
        () -> buildingIndexPath != null && searchLifecycle != ingestLifecycle,
        embeddingTelemetry);
  }

  /**
   * Apply post-construction wiring to {@code appServices}. Called after the initial
   * boot-time construction and again after any reconstruction (e.g., when
   * {@link DeferredRuntime#upgradeWriter()} swaps the runtime and we need a fresh
   * {@link DefaultWorkerAppServices} with non-null indexingLoop / ingestService).
   */
  private void wireAppServicesPostConstruction(WorkerAppServices svc) {
    // 343: Wire resolved config supplier for search config status reporting.
    svc.grpcIngestService()
        .setResolvedConfigSupplier(() -> ConfigStore.global().get());

    // 516 P3 FINAL CUT: wireMigrationActiveSupplier removed — pre-wired via DWAS 2-arg ctor.

    // Tempdoc 397 §14.28 U3: wire the modelReadyLatch so GrpcSearchService's query handlers
    // can await encoder wiring before first use.
    svc.wireModelReadyLatch(() -> modelReadyLatch);

    // Tempdoc 397 §14.28 U4: wire the PolicySnapshot supplier so the GetSessionPolicies
    // gRPC rpc can return Worker's authoritative snapshot.
    svc.wirePolicySnapshotSupplier(
        () -> inferenceSurface != null ? inferenceSurface.policies() : null);

    // Tempdoc 406 — wire the runtime reload trigger so POST /api/admin/runtime/reload
    // can drive a holder swap on the active ingest runtime. Captures the active
    // index path lazily at trigger time so post-cutover paths swap correctly.
    svc.grpcIngestService()
        .setRuntimeReloadTrigger(
            reason ->
                swapRuntime(
                    () -> buildIndexRuntime(activeIndexPath,
                            () -> java.util.Optional.empty()).open(),
                    java.time.Duration.ofSeconds(30),
                    reason));

    // 516 P3 FINAL CUT: wireEmbeddingTelemetryEvents removed — pre-wired via DWAS 2-arg ctor.

    // Tempdoc 419 C3 V1 — wire the worker's RRD store so the indexStatus RPC can backfill the
    // recent-job-queue-depth trend. Late-bound supplier handles the LocalTelemetry-pre-init
    // path safely (returns null → empty array on the receiver side).
    if (telemetry instanceof LocalTelemetry lt) {
      svc.grpcIngestService().setRrdStoreSupplier(lt::getRrdStore);
    }
  }

  /**
   * Tempdoc 406 Phase 4a: after {@link DeferredRuntime#upgradeWriter()} swaps the
   * runtime, the existing {@code appServices} captured ops from the now-closed
   * deferred runtime. Reconstruct from the current {@code infraCtx} (which sees
   * the post-upgrade {@code RunningRuntime} via supplier re-read), re-apply
   * post-construction wiring, swap the {@link DelegatingSearchService} /
   * {@link DelegatingIngestService} delegates, and start the new indexing loop.
   * Mirrors {@code DevReloadManager.performReload}'s swap dance.
   */
  private void reconstructAppServicesAfterDeferredUpgrade() {
    log.info("Reconstructing appServices after DeferredRuntime.upgradeWriter()");
    WorkerAppServices oldServices = appServices;
    WorkerAppServices newServices = newAppServices();
    wireAppServicesPostConstruction(newServices);
    if (searchWrapper != null) {
      searchWrapper.setDelegate(newServices.grpcSearchService());
    }
    if (ingestWrapper != null) {
      ingestWrapper.setDelegate(newServices.grpcIngestService());
    }
    if (healthWrapper != null) {
      healthWrapper.setDelegate(newServices.grpcHealthService());
    }
    this.appServices = newServices;
    newServices.startIndexingLoop();
    if (oldServices != null) {
      try {
        oldServices.close();
      } catch (Exception e) {
        log.warn("Old appServices close after upgrade failed (best-effort): {}", e.getMessage());
      }
    }
  }

  private Server createGrpcServer(List<ServerInterceptor> interceptors) throws IOException {
    KnowledgeServerGrpcWiring.GrpcWiringResult wiring =
        KnowledgeServerGrpcWiring.createGrpcServer(config, interceptors, appServices);
    this.searchWrapper = wiring.searchService();
    this.ingestWrapper = wiring.ingestService();
    this.healthWrapper = wiring.healthService();
    return wiring.server();
  }

  /**
   * Tempdoc 406 swap helper. Drains the current ingest runtime, opens a fresh one
   * via {@code opener}, atomically replaces the holder fields, and reconstructs
   * the gRPC service wrappers so downstream consumers see the new runtime via
   * supplier re-read. Returns the swap duration in milliseconds.
   *
   * <p>Synchronized so concurrent reload triggers serialize. Errors during open
   * leave the old runtime in place and re-throw — callers see a hard failure
   * rather than a half-swapped state.
   *
   * @param opener supplies the fresh runtime; called after the old one drains
   * @param drainTimeout maximum time to wait for in-flight writes to complete
   * @param reason low-cardinality tag for telemetry ("admin_triggered" / etc.)
   * @return total swap duration (ms) including drain + open
   */
  public synchronized long swapRuntime(
      java.util.function.Supplier<RunningRuntime> opener,
      java.time.Duration drainTimeout,
      io.justsearch.adapters.lucene.runtime.SwapReason reason) {
    Objects.requireNonNull(opener, "opener");
    Objects.requireNonNull(drainTimeout, "drainTimeout");
    Objects.requireNonNull(reason, "reason");
    long startNanos = System.nanoTime();
    LuceneRuntime old = this.ingestLifecycle;
    if (old instanceof RunningRuntime running) {
      running.drainAndClose(drainTimeout, reason);
    } else if (old != null) {
      try {
        old.close();
      } catch (Exception e) {
        log.warn("swapRuntime: best-effort close of non-RunningRuntime old: {}", e.getMessage());
      }
    }
    RunningRuntime fresh = opener.get();
    this.ingestLifecycle = fresh;
    this.searchLifecycle = fresh;
    reconstructAppServicesAfterDeferredUpgrade();
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * Background model initialization — runs in a separate thread after gRPC is serving. Loads
   * embedding, NER, SPLADE/BGE-M3, and disambiguation models. Opens deferred IndexWriter if
   * applicable. Non-fatal: failures degrade capabilities but don't crash the server.
   */
  @SuppressWarnings("PMD.CognitiveComplexity")
  private ModelContext initDeferredModels() {
    long bgStart = System.nanoTime();
    try {
      // Open IndexWriter (deferred from sync path for faster gRPC readiness).
      // Phase types: DeferredRuntime.upgradeWriter() returns a fresh RunningRuntime;
      // swap the holder fields, reconstruct appServices (which captured ops from the
      // now-closed deferred session), and swap the gRPC wrappers. After this:
      //   - search continues seamlessly via the upgraded runtime
      //   - write methods stop returning UNAVAILABLE; the indexing loop starts
      if (ingestLifecycle instanceof DeferredRuntime deferred) {
        RunningRuntime upgraded = deferred.upgradeWriter();
        this.ingestLifecycle = upgraded;
        if (this.searchLifecycle == deferred) {
          this.searchLifecycle = upgraded;
        }
        reconstructAppServicesAfterDeferredUpgrade();
        drainSwitchBufferBestEffort();
      }

      // --- Composition root: resolve install contract + hardware profile ---
      Path aiHome = null;
      try {
        aiHome = PlatformPaths.resolveDataDir();
      } catch (Exception e) {
        log.debug("Failed to resolve AI Home for contract reading (dev mode)", e);
      }
      InstallContract contract = aiHome != null ? InstallContractIO.read(aiHome) : null;
      // Tempdoc 374 alpha.18 Bug H + alpha.20 Bug M: honor JUSTSEARCH_MODELS_DIR.
      // alpha.20 prefers contract.modelsDir() (recorded at install time, survives
      // cold restart) over runtime env-var resolution (which doesn't inherit across
      // GUI launches). See resolveModelsDir Javadoc for the three-tier fallback.
      Path modelsDir = resolveModelsDir(contract, aiHome);
      boolean gpuEnabled =
          EnvRegistry.GPU_ENABLED.get().map(Boolean::parseBoolean).orElse(false);
      HardwareProfile hardware =
          (contract != null && contract.hardwareProfile() != null)
              ? contract.hardwareProfile()
              : (gpuEnabled ? HardwareProfile.gpuFull(0) : HardwareProfile.cpuOnly());
      if (contract != null) {
        log.info(
            "Composition root: install contract loaded (profile={}, models={})",
            contract.downloadProfile(),
            contract.models().size());
      } else {
        log.info("Composition root: no install contract — using dev mode discovery");
      }

      // Tempdoc 397 §14.26 T2-C1/C2: single-entry compose returns a typed surface. Per-encoder
      // wiring below destructures the surface; graceful degradation is preserved via
      // Optional<> on each role.
      InferenceSurface surface =
          InferenceCompositionRoot.compose(
              ConfigStore.global().get(),
              hardware,
              contract,
              modelsDir,
              () -> !signalBus.isMainGpuActive(),
              ortSessionEvents);
      this.inferenceSurface = surface;

      // Embedding — skip when BGE-M3 is active (surface.embedding() is already empty in that case).
      var embeddingConfig = EmbeddingConfig.fromEnv();
      if (surface.embedding().isPresent()) {
        var embedAssembly = surface.embedding().get();
        var encoder =
            new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder(
                embedAssembly.sessions(), embedAssembly.shape(), embedAssembly.tokenizer());
        var backend =
            new io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend(
                encoder, embeddingConfig.gpuEnabled() ? 1 : 0, embeddingConfig.contextLength());
        // Tempdoc 413: pass embeddingTelemetry so the service emits invoke_failure / cache /
        // chunked events into the worker LocalTelemetry's metrics-worker.ndjson.
        EmbeddingService es =
            EmbeddingService.createWithBackend(backend, embeddingConfig, embeddingTelemetry);
        if (es.isAvailable()) {
          embeddingService = es;
          validateEmbeddingDimension();
          appServices.wireEmbeddingProvider(es);
          // observations.md fix: null `embeddingService` on GPU-handoff unload
          // so `GpuDiagnosticSuppliers` lambdas (rebound to re-read the field)
          // stop returning data from the closed instance. The provider becomes
          // NoOpEmbeddingProvider.INSTANCE on unload (IndexingLoop:1581);
          // `instanceof` is rename-safe in a way the prior class-name string
          // match wasn't.
          appServices.addEmbeddingProviderChangeListener(
              provider -> {
                if (provider == null
                    || provider instanceof io.justsearch.indexerworker.embed.NoOpEmbeddingProvider) {
                  this.embeddingService = null;
                }
              });
          log.info("Embedding service ready (dimension={})", es.dimension());
        }
      } else if (surface.bgeM3().isEmpty()) {
        log.info("No embedding model found - vector search disabled");
      }

      // EmbeddingCompatibilityController (depends on Lucene + embedding)
      var ecc =
          new EmbeddingCompatibilityController(
              ingestLifecycle::latestCommitUserDataBestEffort,
              () -> ingestLifecycle.indexCountOps().docCount());
      ecc.refresh();
      embeddingCompatController = ecc;
      embeddingFingerprintSupplier.set(ecc::fingerprintToStamp);
      appServices.wireEmbeddingCompatController(ecc);
      maybeAutoStartEmbeddingRebuildAllPendingBestEffort();

      // NER — surface-provided assembly wraps in NerService.
      var nerConfig = io.justsearch.indexerworker.ner.NerConfig.fromEnv();
      if (surface.ner().isPresent()) {
        var nerService = new io.justsearch.indexerworker.ner.NerService(
            surface.ner().get(), nerConfig);
        nerServiceInstance = nerService;
        appServices.wireNerService(nerService);
        var nerModelPath = nerConfig.modelPath().toString();
        var nerGpuEnabled = nerConfig.gpuEnabled();
        appServices.grpcIngestService().setNerModelPathSupplier(() -> nerModelPath);
        appServices.grpcIngestService().setNerGpuEnabledSupplier(() -> nerGpuEnabled);
      } else if (nerConfig.isReady()) {
        log.info("NER: surface returned no assembly; NER will be unavailable.");
      }

      // BGE-M3 unified dense+sparse encoder (when selected + surface has it).
      if (surface.bgeM3().isPresent()) {
        var bgeAssembly = surface.bgeM3().get();
        var bgeConfig = io.justsearch.indexerworker.bgem3.BgeM3Config.fromEnv();
        var bgeEncoder =
            new io.justsearch.indexerworker.bgem3.BgeM3Encoder(
                bgeAssembly.sessions(),
                bgeAssembly.shape(),
                bgeAssembly.tokenizer(),
                bgeConfig);
        bgeM3EncoderInstance = bgeEncoder;
        appServices.wireBgeM3Encoder(bgeEncoder);
        log.info(
            "BGE-M3 encoder ready (replaces SPLADE + EmbeddingService): model={}",
            bgeConfig.modelPath());
      }

      // SPLADE (default, or fallback if BGE-M3 was selected but unavailable).
      var spladeConfig = io.justsearch.indexerworker.splade.SpladeConfig.fromEnv();
      if (surface.splade().isPresent()) {
        var spladeAssembly = surface.splade().get();
        var spladeEncoder =
            new io.justsearch.indexerworker.splade.SpladeEncoder(
                spladeAssembly.sessions(),
                spladeAssembly.shape(),
                spladeAssembly.tokenizer(),
                spladeAssembly.vocabulary(),
                spladeAssembly.truncationEvidencePath(),
                spladeConfig);
        spladeEncoderInstance = spladeEncoder;
        appServices.wireSpladeEncoder(spladeEncoder);
        log.info("SPLADE encoder ready: model={}", spladeConfig.modelPath());

        if (spladeConfig.isIdfQueryMode()) {
          Path idfPath = spladeConfig.modelPath().resolve("idf.json");
          if (Files.isRegularFile(idfPath)) {
            try {
              var idfEncoder =
                  new io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder(
                      idfPath, spladeEncoder.tokenizer(), spladeEncoder.vocabulary());
              spladeIdfQueryEncoder = idfEncoder;
              appServices.wireSpladeIdfQueryEncoder(idfEncoder);
              log.info("SPLADE IDF query encoder ready: {}", idfPath);
            } catch (Exception e) {
              log.warn("Failed to load IDF table (falling back to ONNX): {}", e.getMessage());
              log.debug("Failed to load IDF table (stack trace)", e);
            }
          } else {
            log.warn("IDF mode requested but idf.json not found at {}", idfPath);
          }
        }
      }

      // Disambiguation
      try {
        var ds = new io.justsearch.indexerworker.disambiguation.DisambiguationService(dataDir);
        ds.open();
        disambiguationService = ds;
        appServices.wireDisambiguationService(ds);
      } catch (Exception e) {
        log.warn("Failed to initialize disambiguation service (non-fatal): {}", e.getMessage());
        log.debug("Failed to initialize disambiguation service (stack trace)", e);
      }

      // 360: Search reranker (GPU-capable, in Worker process).
      var searchRerankConfig = io.justsearch.reranker.RerankerConfig.fromEnv();
      if (surface.reranker().isPresent()) {
        var rerankAssembly = surface.reranker().get();
        searchRerankerInstance =
            new io.justsearch.reranker.CrossEncoderReranker(
                rerankAssembly.sessions(), rerankAssembly.shape(), rerankAssembly.tokenizer());
        appServices.wireSearchReranker(searchRerankerInstance);
        // F5: Warm up ORT session at startup instead of paying 5-10s on the first user query.
        try {
          long warmStart = System.nanoTime();
          searchRerankerInstance.rerank("warmup", List.of("warmup"), 30_000);
          long warmMs = (System.nanoTime() - warmStart) / 1_000_000;
          log.info("Search reranker ready (gpu={}, warm-up={}ms): model={}",
              searchRerankConfig.gpuEnabled(), warmMs, searchRerankConfig.modelPath());
        } catch (Exception warmE) {
          log.info("Search reranker ready (gpu={}, warm-up failed: {}): model={}",
              searchRerankConfig.gpuEnabled(), warmE.getMessage(), searchRerankConfig.modelPath());
        }
      }

      // Citation scorer (CPU-only). Tempdoc 397 §14.26 T2-E1: eager-wire — construct the full
      // CitationScorer from the surface assembly and pass it to appServices. CitationMatchOps is
      // now a pure consumer with no lazy construction path.
      if (surface.citation().isPresent()) {
        var citationAssembly = surface.citation().get();
        citationScorerInstance =
            new io.justsearch.reranker.CitationScorer(
                citationAssembly.sessions(),
                citationAssembly.shape(),
                citationAssembly.tokenizer());
        appServices.wireCitationScorer(citationScorerInstance);
      }

      // GPU diagnostics suppliers (post-model wiring)
      // observations.md fix: spladeOrtCudaStatus / spladeModelPath are
      // SPLADE-specific slots — they must NOT coalesce with bgeM3 when
      // bgeM3 is active. bgeM3 has its own slot (`bgeM3OrtCudaStatus`,
      // last argument below). The /api/inference/encoders explainer
      // previously dodged the misleading coalesce via policy iteration;
      // the fix makes the diagnostic shape honest.
      java.util.function.Supplier<io.justsearch.ort.OrtCudaStatus> sparseStatusSupplier =
          spladeEncoderInstance != null ? spladeEncoderInstance::getOrtCudaStatus : null;
      java.util.function.Supplier<String> sparseModelPathSupplier =
          spladeEncoderInstance != null ? spladeEncoderInstance::resolvedModelPath : null;
      // observations.md fix: re-read `this.embeddingService` at supplier-call
      // time so post-unload nulls (set by the addEmbeddingProviderChangeListener
      // above) propagate to /api/status. Method-references like
      // `embeddingService::getOrtCudaStatus` would have bound the instance at
      // lambda-creation time and continued returning stale data after close.
      appServices.wireGpuDiagnostics(
          new GpuDiagnosticSuppliers(
              sparseStatusSupplier,
              sparseModelPathSupplier,
              () -> {
                var es = this.embeddingService;
                return es != null ? es.getOrtCudaStatus() : null;
              },
              () -> {
                var es = this.embeddingService;
                return es != null ? es.resolvedBackendId() : null;
              },
              () -> {
                // gpuLayers returns int (primitive). The consumer
                // (`IndexStatusOps.buildGpu`) auto-unboxes the Integer
                // result via `setEmbedGpuLayers(int)`. Returning null
                // here would NPE the consumer (regression caught by
                // SchemaMismatchStatusContractTest 2026-05-09 — fixed
                // below by returning 0 when no embedding service is
                // loaded, matching the "no GPU layers" contract).
                var es = this.embeddingService;
                return es != null ? es.gpuLayers() : 0;
              },
              searchRerankerInstance != null ? searchRerankerInstance::getOrtCudaStatus : null,
              nerServiceInstance != null ? nerServiceInstance::getOrtCudaStatus : null,
              citationScorerInstance != null ? citationScorerInstance::getOrtCudaStatus : null,
              bgeM3EncoderInstance != null ? bgeM3EncoderInstance::getOrtCudaStatus : null));

      // Tempdoc 394 follow-up: publish per-stage enabled state on /api/status.
      // "Enabled" here means the service is usable — config-enabled AND
      // initialization succeeded. A non-null instance satisfies both.
      appServices.wireStageEnabled(
          embeddingService != null,
          spladeEncoderInstance != null,
          nerServiceInstance != null);

      // 332 + 397 §14.28 U3: release the shared modelReadyLatch after ALL models are
      // wired (embedding + ECC + SPLADE + BGE-M3 + disambiguation + NER + reranker +
      // citation). This closes both (a) the SPLADE timing gap from 312 — migration
      // enumerator now waits until sparse vectors are available — and (b) the query-
      // handler boot-race — GrpcSearchService.awaitModelsReady unblocks here. See the
      // modelReadyLatch field Javadoc for the full consumer list before changing the
      // release point.
      modelReadyLatch.countDown();

      long bgMs = (System.nanoTime() - bgStart) / 1_000_000;
      log.info("Background model init complete ({}ms)", bgMs);
      return new ModelContext(
          embeddingService,
          embeddingCompatController,
          nerServiceInstance,
          spladeEncoderInstance,
          spladeIdfQueryEncoder,
          bgeM3EncoderInstance,
          disambiguationService);

    } catch (Exception e) {
      log.error("Background model initialization failed (non-fatal)", e);
      long bgMs = (System.nanoTime() - bgStart) / 1_000_000;
      log.info("Background model init failed after ({}ms)", bgMs);
      return new ModelContext(
          embeddingService,
          embeddingCompatController,
          nerServiceInstance,
          spladeEncoderInstance,
          spladeIdfQueryEncoder,
          bgeM3EncoderInstance,
          disambiguationService);
    } finally {
      // Ensure enumerator is unblocked even if init failed partway through.
      modelReadyLatch.countDown();
    }
  }


  /**
   * Wires the WorkerOpsMetricCatalog (Phase 3c). Replaces the legacy
   * {@code registerOtelObservableCallbacks} (which used the now-retired
   * {@code Telemetry.meter(scope)}) and the per-gauge {@code Telemetry.gauge(...)} calls.
   */
  private void registerTelemetryGauges() {
    if (telemetry == null) return;
    if (!(telemetry instanceof LocalTelemetry lt)) return;
    var sources =
        new io.justsearch.indexerworker.services.WorkerOpsMetricCatalog.Sources(
            this::safeJobQueueDepth,
            this::safePendingJobs,
            this::safeProcessingJobs,
            this::safePendingReadyJobs,
            this::safePendingBackoffJobs,
            this::safeSwitchBufferDepth,
            () -> {
              String st = appServices == null ? "" : appServices.indexingLoopState();
              return io.justsearch.indexerworker.loop.IndexingLoop.LoopState.PAUSED.name().equals(st) ? 1L : 0L;
            },
            this::safePendingEmbeddings,
            this::safePendingVdu);
    this.workerOpsCatalog =
        new io.justsearch.indexerworker.services.WorkerOpsMetricCatalog(
            lt.registry(), OperationalMetrics.getInstance(),
            sources);
    JvmRuntimeGauges.register(telemetry, "worker");
  }

  private void maybeAutoStartEmbeddingRebuildAllPendingBestEffort() {
    try {
      if (embeddingCompatController == null || ingestLifecycle == null) return;
      if (embeddingCompatController.state() != EmbeddingCompatibilityController.State.BLOCKED_LEGACY) return;
      if (!"LEGACY_INDEX_NO_FINGERPRINT".equals(embeddingCompatController.reasonCode())) return;

      // Phase 6 fix: docCount() includes chunks, but embedding_status is only on parent docs.
      // Exclude chunks to prevent the heuristic from always failing when chunks exist.
      var countOps = ingestLifecycle.indexCountOps();
      long totalDocs = countOps.docCount();
      int chunkDocs = countOps.countByField(SchemaFields.IS_CHUNK, "true");
      long docs = totalDocs - chunkDocs;
      if (docs <= 0) return;

      int pending = countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      int completed = countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      int failed = countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);

      embeddingCompatController.maybeAutoStartRebuildForLegacyAllPending(docs, pending, completed, failed);
    } catch (Exception ignored) {
      // Best-effort: never block worker startup on auto-rebuild heuristics.
    }
  }

  private long safeJobQueueDepth() {
    return KnowledgeServerSafeMetrics.safeJobQueueDepth(jobQueue);
  }

  private long safePendingJobs() {
    return KnowledgeServerSafeMetrics.safePendingJobs(jobQueue, safeJobQueueDepth());
  }

  private long safeProcessingJobs() {
    return KnowledgeServerSafeMetrics.safeProcessingJobs(jobQueue);
  }

  private long safePendingReadyJobs() {
    return KnowledgeServerSafeMetrics.safePendingReadyJobs(jobQueue);
  }

  private long safePendingBackoffJobs() {
    return KnowledgeServerSafeMetrics.safePendingBackoffJobs(jobQueue);
  }

  private long safeSwitchBufferDepth() {
    return KnowledgeServerSafeMetrics.safeSwitchBufferDepth(jobQueue);
  }

  private int safePendingEmbeddings() {
    return KnowledgeServerSafeMetrics.safePendingEmbeddings(
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null);
  }

  private int safePendingVdu() {
    return KnowledgeServerSafeMetrics.safePendingVdu(
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null);
  }

  private LuceneRuntimeBuilder buildIndexRuntime(
      Path indexPath,
      java.util.function.Supplier<java.util.Optional<String>> fingerprintSupplier) {
    // Load field catalog via centralized configuration loader
    io.justsearch.configuration.JustSearchConfigurationLoader loader =
        new io.justsearch.configuration.JustSearchConfigurationLoader();
    io.justsearch.configuration.FieldCatalogDef catalog = loader.loadFieldCatalog();

    // Apply vector dimension override for BGE-M3 (1024-dim vs nomic-embed's 768-dim)
    String sparseModel = EnvRegistry.SPARSE_MODEL.getString("splade");
    if ("bge-m3".equalsIgnoreCase(sparseModel)) {
      catalog = catalog.withVectorDimension(1024);
      log.info("Field catalog: vector dimension overridden to 1024 (BGE-M3 active)");
    }

    // Create metadata source with dimension override for schema fingerprint
    final int effectiveDimension = "bge-m3".equalsIgnoreCase(sparseModel) ? 1024 : 0;

    // Create runtime with embedding + SPLADE fingerprint overlays
    java.util.function.Supplier<io.justsearch.indexing.runtime.CommitMetadataSource>
        metadataSupplier =
            () -> {
              var ssot =
                  new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource();
              if (effectiveDimension > 0) {
                ssot.setVectorDimensionOverride(effectiveDimension);
              }
              return new EmbeddingMetadataOverlay(
                  ssot,
                  fingerprintSupplier,
                  io.justsearch.indexerworker.splade.SpladeFingerprint::get);
            };

    IndexSchema schema =
        new IndexSchema(
            new io.justsearch.adapters.lucene.runtime.FieldMapper(catalog),
            new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
            metadataSupplier,
            new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
            null);
    LuceneRuntimeBuilder builder = schema.atPath(indexPath);
    // Tempdoc 406 observability: wire WorkerLuceneTelemetryAdapter so commit /
    // backpressure / drain / swap / lock-contention events flow into
    // metrics-worker.ndjson under the index.runtime.* namespace.
    if (telemetry != null) {
      builder.withTelemetry(
          new io.justsearch.indexerworker.services.WorkerLuceneTelemetryAdapter(
              indexRuntimeCatalog));
    }
    return builder;
  }

  private LuceneRuntimeBuilder buildReadOnlyRuntime(Path indexPath) {
    io.justsearch.configuration.JustSearchConfigurationLoader loader =
        new io.justsearch.configuration.JustSearchConfigurationLoader();
    io.justsearch.configuration.FieldCatalogDef catalog = loader.loadFieldCatalog();
    String sparseModel = EnvRegistry.SPARSE_MODEL.getString("splade");
    if ("bge-m3".equalsIgnoreCase(sparseModel)) {
      catalog = catalog.withVectorDimension(1024);
    }
    LuceneRuntimeBuilder builder = IndexSchema.fromCatalog(catalog).atPath(indexPath);
    if (telemetry != null) {
      builder.withTelemetry(
          new io.justsearch.indexerworker.services.WorkerLuceneTelemetryAdapter(
              indexRuntimeCatalog));
    }
    return builder;
  }

  /**
   * Validates that the embedding model's output dimension matches the index schema.
   *
   * <p>This fail-fast check prevents a common misconfiguration where the embedding model
   * produces vectors of a different dimension than the index expects. Without this check,
   * the mismatch would only surface at indexing time, potentially corrupting the index
   * or causing silent retrieval failures.
   *
   * @throws IOException if dimensions don't match (fail-fast)
   */
  private void validateEmbeddingDimension() throws IOException {
    if (embeddingService == null || ingestLifecycle == null) {
      return;
    }

    int modelDimension = embeddingService.dimension();
    Integer schemaDimension = ingestLifecycle.schema().ssotVectorDimension();

    if (schemaDimension == null) {
      // Schema doesn't define a vector field - vectors will be ignored
      log.warn("Embedding model produces dimension={} but index schema has no vector field defined. "
          + "Vectors will NOT be indexed. To enable vector search, add a vector field to SSOT/catalogs/fields.v1.json",
          modelDimension);
      return;
    }

    if (modelDimension == 0) {
      // Model dimension not known yet (will be detected on first embedding)
      log.info("Model dimension not yet known; will validate on first embedding");
      return;
    }

    if (modelDimension != schemaDimension) {
      String message = String.format(
          "SCHEMA MISMATCH: Embedding model produces dimension=%d but index schema expects dimension=%d. "
              + "Either use a different embedding model or update SSOT/catalogs/fields.v1.json to match.",
          modelDimension, schemaDimension);
      log.error(message);
      throw new IOException(message);
    }

    log.info("Schema validation passed: model dimension={} matches schema dimension={}",
        modelDimension, schemaDimension);
  }

  /**
   * Logs the configuration at startup for debugging and observability.
   */
  private void logConfiguration() {
    log.info("╔══════════════════════════════════════════════════════════════╗");
    log.info("║              JustSearch Worker Configuration                 ║");
    log.info("╠══════════════════════════════════════════════════════════════╣");
    log.info("║ Data directory:   {}", padRight(dataDir.toString(), 44) + "║");
    log.info("║ Index base path:  {}", padRight(String.valueOf(indexBasePath), 44) + "║");
    log.info("║ Active index dir: {}", padRight(String.valueOf(activeIndexPath), 44) + "║");
    log.info("║ Build index dir:  {}", padRight(String.valueOf(buildingIndexPath), 44) + "║");
    log.info("║ Jobs DB path:     {}", padRight(dataDir.resolve("jobs.db").toString(), 44) + "║");
    log.info("║ Signal bus path:  {}", padRight(dataDir.resolve("worker_signal.lock").toString(), 44) + "║");
    log.info("║ Host:             {}", padRight(config.host(), 44) + "║");

    // SSOT paths
    ConfigStore cs = ConfigStore.globalOrNull();
    String ssotPath = cs != null && cs.get().paths().ssotPath() != null
        ? cs.get().paths().ssotPath().toString() : null;
    Path effectiveRepoRoot = RepoRootLocator.findRepoRootOrNull();
    String repoRoot = effectiveRepoRoot != null ? effectiveRepoRoot.toString() : "auto-detect";
    if (ssotPath == null || ssotPath.isBlank()) {
      ssotPath = effectiveRepoRoot != null ? effectiveRepoRoot.resolve("SSOT").toString() : "auto-detect";
    }
    log.info("║ SSOT path:        {}", padRight(ssotPath, 44) + "║");
    log.info("║ Repo root:        {}", padRight(repoRoot, 44) + "║");

    // Search pipeline configuration
    if (cs != null && cs.get().hybridSearch() != null) {
      ResolvedConfig.HybridSearch hs = cs.get().hybridSearch();
      log.info("╠══════════════════════════════════════════════════════════════╣");
      log.info("║ Fusion strategy:  {}", padRight(hs.fusionStrategy(), 44) + "║");
      if ("cc".equals(hs.fusionStrategy())) {
        log.info("║   CC weights:     {}", padRight(
            String.format("sparse=%.2f dense=%.2f splade=%.2f",
                hs.ccWeightSparse(), hs.ccWeightDense(), hs.ccWeightSplade()), 44) + "║");
      }
      log.info("║ Branch fusion:    {}", padRight(hs.branchFusionStrategy(), 44) + "║");
      ConfigResolution chunkAwareRes = cs.get().resolutions().get("search.chunk_aware.enabled");
      String chunkAware = chunkAwareRes != null && chunkAwareRes.value() != null
          ? chunkAwareRes.value() : "true";
      log.info("║ Chunk-aware merge:{}", padRight(chunkAware, 44) + "║");
    }

    log.info("╚══════════════════════════════════════════════════════════════╝");
  }

  private static String padRight(String s, int n) {
    if (s == null) s = "null";
    if (s.length() > n) {
      return "..." + s.substring(s.length() - (n - 3));
    }
    return String.format("%-" + n + "s", s);
  }

  private static final long CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L; // daily
  private static final int CLEANUP_RETENTION_DAYS = 30;
  // Tempdoc 410 §8 / review fix #6 — ingestion ledger outlives queue rows so "why is this file
  // missing from search?" questions remain answerable past the queue retention window.
  private static final int LEDGER_RETENTION_DAYS = 180;

  private void startSentinelThread() {
    sentinelThread = new Thread(() -> {
      log.info("Sentinel thread started");
      boolean lastMainGpuActive = true; // Assume Main has GPU initially
      long lastCleanupMs = System.currentTimeMillis();
      while (running && !Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(1000); // Check every second

          if (signalBus.shouldDie()) {
            log.info("Sentinel detected termination condition, initiating shutdown");
            initiateShutdown();
            break;
          }

          // Dev hot-reload: check for reload signal from Gradle continuous build
          if (devReloadManager != null && signalBus.isReloadRequested()) {
            log.info("Sentinel detected reload signal");
            devReloadManager.performReload();
          }

          // GPU lifecycle monitoring: release reranker VRAM when Main claims GPU
          boolean currentMainGpuActive = signalBus.isMainGpuActive();
          if (currentMainGpuActive && !lastMainGpuActive) {
            log.info("GPU lifecycle: Main claimed GPU, releasing reranker VRAM");
            if (appServices != null) {
              appServices.onMainClaimedGpu();
            }
          }
          lastMainGpuActive = currentMainGpuActive;

          // Periodic job queue cleanup: remove old DONE/FAILED rows
          long now = System.currentTimeMillis();
          if (jobQueue != null && now - lastCleanupMs > CLEANUP_INTERVAL_MS) {
            try {
              int deleted = jobQueue.cleanupOldJobs(CLEANUP_RETENTION_DAYS);
              if (deleted > 0) {
                log.info("Periodic cleanup: removed {} old jobs (>{} days)", deleted, CLEANUP_RETENTION_DAYS);
              }
              int deletedLedger = jobQueue.cleanupOldLedgerEvents(LEDGER_RETENTION_DAYS);
              if (deletedLedger > 0) {
                log.info(
                    "Periodic cleanup: removed {} old ledger events (>{} days)",
                    deletedLedger,
                    LEDGER_RETENTION_DAYS);
              }
            } catch (Exception e) {
              log.warn("Periodic job cleanup failed (non-fatal)", e);
            }
            lastCleanupMs = now;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      log.info("Sentinel thread exiting");
    }, "knowledge-server-sentinel");

    sentinelThread.setDaemon(true);
    sentinelThread.start();
  }

  private static boolean hasLuceneSegments(Path indexPath) {
    if (!Files.exists(indexPath)) {
      return false;
    }
    try (var entries = Files.list(indexPath)) {
      return entries.anyMatch(p -> p.getFileName().toString().startsWith("segments"));
    } catch (IOException e) {
      return false;
    }
  }

  private void initiateShutdown() {
    running = false;
    if (grpcServer != null) {
      grpcServer.shutdown();
    }
  }

  /**
   * Blocks until the server terminates.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  public void blockUntilShutdown() throws InterruptedException {
    if (grpcServer != null) {
      grpcServer.awaitTermination();
    }
  }

  /**
   * Returns the bound port, or -1 if not started.
   *
   * @return The bound gRPC port
   */
  public int getPort() {
    return grpcServer != null ? grpcServer.getPort() : -1;
  }

  /**
   * Checks if the server is running.
   *
   * @return true if the server is running
   */
  public boolean isRunning() {
    return running && grpcServer != null && !grpcServer.isShutdown();
  }

  /**
   * Returns the embedding compatibility controller.
   *
   * @return the controller, or null if not initialized
   */
  public EmbeddingCompatibilityController embeddingCompatController() {
    return embeddingCompatController;
  }

  @Override
  public void close() throws IOException {
    log.info("Shutting down KnowledgeServer...");
    running = false;

    // Tempdoc 550 Thesis II: stop the periodic stuck-job reaper.
    if (stuckJobReaper != null) {
      stuckJobReaper.shutdownNow();
    }

    // Wait for deferred model init to complete before closing models
    if (deferredModelInit != null) {
      try {
        deferredModelInit.get(5, TimeUnit.SECONDS);
      } catch (Exception e) {
        log.warn("Deferred model init did not complete before shutdown: {}", e.getMessage());
      }
    }

    // Tempdoc 413: emit unload_total{reason=SHUTDOWN} and explicitly flush *before* any close-
    // time shutdown begins. The close-time meterProvider.forceFlush().join(2s) at the tail of
    // LocalTelemetry.close() races the file write — same shutdown gap that affects every other
    // counter in the system (e.g., worker.documents.indexed.total's last value never reaches
    // NDJSON either). Calling LocalTelemetry.flush() here (5s join, SDK fully alive) guarantees
    // the metric lands in metrics-worker.ndjson before any close-time race conditions begin.
    // Counterpart to GPU_HANDOFF emitted from IndexingLoop.unloadEmbeddingService on hybrid-
    // inference VRAM handoff. The actual embeddingService.close() runs later in the close
    // sequence — this emit reflects intent regardless of whether close() succeeds.
    if (embeddingService != null && embeddingTelemetry != null) {
      embeddingTelemetry.onUnload(
          io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason.SHUTDOWN);
      if (telemetry instanceof LocalTelemetry lt) {
        lt.flush();
      }
    }

    // Stop sentinel thread
    if (sentinelThread != null) {
      sentinelThread.interrupt();
      try {
        sentinelThread.join(5_000);  // Allow 5s for sentinel cleanup
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Stop indexing loop (via application services registry)
    if (appServices != null) {
      try {
        appServices.close();
      } catch (Exception e) {
        log.warn("Error closing application services", e);
      }
    }

    // Close disambiguation service (after indexing loop which uses it)
    if (disambiguationService != null) {
      try {
        disambiguationService.close();
      } catch (Exception e) {
        log.warn("Error closing disambiguation service", e);
      }
    }

    // Close SPLADE encoder (after indexing loop which uses it)
    if (spladeEncoderInstance != null) {
      try {
        spladeEncoderInstance.close();
      } catch (Exception e) {
        log.warn("Error closing SPLADE encoder", e);
      }
    }

    // Close BGE-M3 encoder (after indexing loop which uses it)
    if (bgeM3EncoderInstance != null) {
      try {
        bgeM3EncoderInstance.close();
      } catch (Exception e) {
        log.warn("Error closing BGE-M3 encoder", e);
      }
    }

    // 360: Close search reranker (ORT session + tokenizer)
    if (searchRerankerInstance != null) {
      try {
        searchRerankerInstance.close();
      } catch (Exception e) {
        log.warn("Error closing search reranker", e);
      }
    }

    // Tempdoc 397 §14.26 T2-C1/C2: close any surface-owned SessionHandle that wasn't covered
    // by the encoder closes above (e.g., citation scorer's handle, which is wired to
    // appServices rather than owned by a local encoder instance). Handle closes are
    // idempotent, so double-closing the encoder-owned handles is safe.
    if (inferenceSurface != null) {
      try {
        inferenceSurface.close();
      } catch (Exception e) {
        log.warn("Error closing inference surface handles", e);
      }
    }

    // Phase 3c: OTel callback handles are managed by LocalTelemetry's gaugeHandles list
    // (each catalog gauge/observable-counter goes through registry.buildGauge/buildObservableCounter
    // which adds the handle there). LocalTelemetry.close() drains them on shutdown.

    // Close tracing (flush spans) before telemetry shuts down.
    if (tracingBootstrap != null) {
      try {
        tracingBootstrap.close();
      } catch (Exception e) {
        log.warn("Error closing tracing", e);
      }
    }

    // Close telemetry (flush best-effort) after loop shutdown so the last stage/commit timings are captured.
    if (telemetry != null) {
      try {
        telemetry.close();
      } catch (Exception e) {
        log.warn("Error closing telemetry", e);
      } finally {
        telemetry = null;
      }
    }

    // Stop gRPC server
    if (grpcServer != null) {
      grpcServer.shutdown();
      try {
        if (!grpcServer.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          grpcServer.shutdownNow();
        }
      } catch (InterruptedException e) {
        grpcServer.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    // Stop migration enumerator thread (best-effort)
    if (migrationEnumeratorThread != null) {
      migrationEnumeratorThread.interrupt();
      try {
        migrationEnumeratorThread.join(10_000);  // Allow 10s for large directory walks
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Stop migration cutover monitor thread (best-effort)
    if (migrationCutoverThread != null) {
      migrationCutoverThread.interrupt();
      try {
        migrationCutoverThread.join(10_000);  // Allow 10s for cutover cleanup
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Close Lucene runtimes
    if (ingestLifecycle != null && ingestLifecycle != searchLifecycle) {
      try {
        ingestLifecycle.close();
      } catch (Exception e) {
        log.warn("Error closing ingest runtime", e);
      }
    }
    if (searchLifecycle != null) {
      try {
        searchLifecycle.close();
      } catch (Exception e) {
        log.warn("Error closing search runtime", e);
      }
    }

    // Close embedding service. unload_total{reason=SHUTDOWN} was emitted earlier (before
    // telemetry shutdown) so the metric lands in metrics-worker.ndjson regardless of close()'s
    // outcome.
    if (embeddingService != null) {
      try {
        embeddingService.close();
      } catch (Exception e) {
        log.warn("Error closing embedding service", e);
      }
    }

    // Close path-resolution store (must close BEFORE job queue since both connect to jobs.db).
    if (pathResolutionStore != null) {
      try {
        pathResolutionStore.close();
      } catch (Exception e) {
        log.warn("Error closing path-resolution store", e);
      }
    }

    // Close job queue
    if (jobQueue != null) {
      try {
        jobQueue.close();
      } catch (Exception e) {
        log.warn("Error closing job queue", e);
      }
    }

    // Close signal bus
    if (signalBus != null) {
      try {
        signalBus.close();
      } catch (Exception e) {
        log.warn("Error closing signal bus", e);
      }
    }

    if (indexRootLock != null) {
      try {
        indexRootLock.close();
      } catch (Exception e) {
        log.warn("Error closing index root lock", e);
      } finally {
        indexRootLock = null;
      }
    }

    log.info("KnowledgeServer shutdown complete");
  }

  // Tempdoc 417 Phase 3c: registerOtelObservableCallbacks() removed — its 25 metrics now flow
  // through WorkerOpsMetricCatalog (constructed in registerTelemetryGauges). Telemetry.meter()
  // retired with this change.

  private void closeQuietly() {
    try {
      close();
    } catch (Exception e) {
      log.warn("Error during cleanup", e);
    }
  }

  // Package-private accessors for testing
  JobQueue jobQueueForTests() {
    return jobQueue;
  }

  WorkerSignalBus signalBusForTests() {
    return signalBus;
  }

  LuceneRuntime lifecycleManagerForTests() {
    return ingestLifecycle;
  }

  private static IndexGenerationManager.MigrationState parseMigrationState(String raw) {
    return KnowledgeServerMigrationOps.parseMigrationState(raw);
  }

  private void startMigrationCutoverMonitorBestEffort() {
    if (migrationCutoverThread != null) {
      return;
    }
    migrationCutoverThread =
        new Thread(
            () ->
                KnowledgeServerMigrationOps.runMigrationCutoverLoop(
                    new KnowledgeServerMigrationOps.CutoverContext(
                        indexGenerationManager,
                        jobQueue,
                        () -> running,
                        () -> migrationEnumeratorDone,
                        MIGRATION_SWITCHING_QUEUE_DEPTH_THRESHOLD,
                        MIGRATION_SWITCHING_MAX_DURATION_MS,
                        migrationCutoverMaxFailedJobs,
                        () -> ingestLifecycle,
                        this::finalizeEmbeddingRebuildBeforeCutover,
                        this::verifyGreenCommitMetadataBestEffort,
                        this::drainSwitchBufferBestEffort,
                        this::initiateShutdown,
                        dataDir,
                        log)),
            "migration-cutover");
    migrationCutoverThread.setDaemon(true);
    migrationCutoverThread.start();
  }

  /**
   * Tempdoc 598 review Fix E: deterministically finalize the embedding rebuild on the drained green
   * immediately before the cutover COMPLETE commit. Flips the ECC to COMPATIBLE iff the green is fully
   * embedded (job queue + pending-embeddings both 0), so the COMPLETE commit's overlay stamps the
   * embedding fingerprint — instead of racing the indexing-loop thread that would otherwise call
   * {@code checkRebuildCompletion}. Idempotent (no-op unless the ECC is REBUILDING) and best-effort: a
   * green that is genuinely not fully embedded is not flipped, so {@link #verifyGreenCommitMetadataBestEffort}
   * correctly blocks its promotion.
   */
  private void finalizeEmbeddingRebuildBeforeCutover() {
    var ecc = embeddingCompatController;
    if (ecc == null || ingestLifecycle == null) {
      return;
    }
    try {
      long queueDepth = jobQueue.queueDepth();
      int pendingEmbeddings =
          ingestLifecycle
              .indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      ecc.checkRebuildCompletion(queueDepth, pendingEmbeddings);
    } catch (RuntimeException e) {
      log.warn(
          "Fix E: finalize embedding rebuild before cutover failed (best-effort): {}",
          e.getMessage());
    }
  }

  private boolean verifyGreenCommitMetadataBestEffort() {
    // Tempdoc 598 R3: also verify the green carries a current-model embedding fingerprint before
    // promotion, so a blue/green rebuild cannot promote a generation that would still serve
    // BLOCKED_LEGACY. Null when no embedding model is resolvable (keyword-only rebuild → skipped).
    String expectedEmbeddingFp =
        embeddingCompatController != null ? embeddingCompatController.currentFingerprint() : null;
    return KnowledgeServerMigrationOps.verifyGreenCommitMetadataBestEffort(
        ingestLifecycle, expectedEmbeddingFp, log);
  }

  private void drainSwitchBufferBestEffort() {
    // Drain only valid post-deferred-upgrade (writer is open). The boot path that
    // calls drain pre-upgrade does so only when ingest is not deferred (see boot logic).
    if (!(ingestLifecycle instanceof RunningRuntime running)) {
      log.warn("drainSwitchBufferBestEffort: ingest is not RunningRuntime ({}), skipping",
          ingestLifecycle == null ? "null" : ingestLifecycle.getClass().getSimpleName());
      return;
    }
    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            jobQueue, running, signalBus, indexBasePath, activeIndexPath, JSON, log));
  }

  private void startMigrationEnumeratorBestEffort(ResolvedConfig rc) {
    if (migrationEnumeratorThread != null) {
      return;
    }
    migrationEnumeratorRunning.set(true);
    migrationEnumeratorStartedAtMs.set(System.currentTimeMillis());
    migrationEnumeratorFinishedAtMs.set(0L);
    migrationEnumeratorRootsTotal.set(0L);
    migrationEnumeratorRootsDone.set(0L);
    migrationEnumeratorFilesSeen.set(0L);
    migrationEnumeratorFilesEnqueued.set(0L);
    migrationEnumeratorLastPath.set("");
    // Persist initial progress snapshot (best-effort) so operators can see that migration started.
    {
      MigrationProgressSnapshot snap = migrationProgressSnapshot();
      persistedMigrationProgressSnapshot = snap;
      if (migrationProgressStore != null) {
        migrationProgressStore.writeBestEffort(snap);
      }
    }
    migrationEnumeratorThread =
        new Thread(
            () -> {
              try {
                // 332: Wait for all models (embedding + ECC + SPLADE) before enqueuing files.
                // Without this gate, the IndexingLoop processes migration jobs before
                // initDeferredModels() finishes loading models (~15-20s), producing
                // text-only docs without sparse vectors that need slow RMW backfill post-cutover.
                if (!modelReadyLatch.await(120, TimeUnit.SECONDS)) {
                  log.warn(
                      "Migration enumerator: models not ready after 120s, "
                          + "proceeding without inline embedding/SPLADE");
                }
                List<Path> roots = loadWatchedRootsBestEffort(rc);
                if (roots.isEmpty()) {
                  log.warn("Migration enumerator: no watched roots found; Green index will remain empty");
                  return;
                }
                migrationEnumeratorRootsTotal.set(roots.size());
                int totalEnqueued = enqueueAllFilesUnderRoots(roots);
                log.info(
                    "Migration enumerator finished. roots={} enqueuedFiles={}",
                    roots.size(),
                    totalEnqueued);
                migrationEnumeratorDone = true;
              } catch (Exception e) {
                log.warn("Migration enumerator failed (continuing)", e);
              } finally {
                migrationEnumeratorRunning.set(false);
                migrationEnumeratorFinishedAtMs.set(System.currentTimeMillis());
                // Persist terminal snapshot (best-effort) for restart visibility.
                MigrationProgressSnapshot snap = migrationProgressSnapshot();
                persistedMigrationProgressSnapshot = snap;
                if (migrationProgressStore != null) {
                  migrationProgressStore.writeBestEffort(snap);
                }
              }
            },
            "migration-enumerator");
    migrationEnumeratorThread.setDaemon(true);
    migrationEnumeratorThread.start();
  }

  private List<Path> loadWatchedRootsBestEffort(ResolvedConfig rc) {
    return KnowledgeServerMigrationOps.loadWatchedRootsBestEffort(
        dataDir, rc.collections().items(), JSON, log);
  }

  private int enqueueAllFilesUnderRoots(List<Path> roots) throws IOException {
    return KnowledgeServerMigrationOps.enqueueAllFilesUnderRoots(
        new KnowledgeServerMigrationOps.EnqueueContext(
            roots,
            jobQueue,
            () -> running,
            () -> indexGenerationManager,
            migrationEnumeratorFilesSeen,
            migrationEnumeratorFilesEnqueued,
            migrationEnumeratorRootsDone,
            migrationEnumeratorLastPath,
            () -> migrationProgressStore,
            this::migrationProgressSnapshot,
            snap -> persistedMigrationProgressSnapshot = snap,
            log));
  }

  private MigrationProgressSnapshot migrationProgressSnapshot() {
    return KnowledgeServerMigrationOps.migrationProgressSnapshot(
        migrationEnumeratorRunning,
        migrationEnumeratorDone,
        migrationEnumeratorRootsTotal,
        migrationEnumeratorRootsDone,
        migrationEnumeratorFilesSeen,
        migrationEnumeratorFilesEnqueued,
        migrationEnumeratorStartedAtMs,
        migrationEnumeratorFinishedAtMs,
        migrationEnumeratorLastPath,
        persistedMigrationProgressSnapshot);
  }

  // ==================== Database Corruption Triage ====================

  /**
   * Handles a corrupt jobs.db by quarantining the corrupt file and restoring from backup.
   *
   * <p>Triage process:
   * <ol>
   *   <li>Quarantine the corrupt database (and WAL sidecar files) by renaming to .corrupt</li>
   *   <li>Attempt to restore from jobs.db.bak if it exists</li>
   *   <li>Re-open the queue (creates fresh tables if no backup existed)</li>
   * </ol>
   *
   * <p>WAL mode creates sidecar files (-wal, -shm) that must be handled together with the
   * main database file to avoid inconsistent state.
   *
   * @param dbPath Path to the corrupt jobs.db file
   * @throws IOException if quarantine or restore operations fail
   * @throws SQLException if re-opening the queue fails
   */
  private void handleCorruptDatabase(Path dbPath) throws IOException, SQLException {
    Path corruptPath = dbPath.resolveSibling(dbPath.getFileName() + ".corrupt");
    Path backupPath = dbPath.resolveSibling(dbPath.getFileName() + ".bak");

    // 1. Quarantine the corrupted file for forensics
    if (Files.exists(dbPath)) {
      Files.move(dbPath, corruptPath, StandardCopyOption.REPLACE_EXISTING);
      log.error("Quarantined corrupt database to: {}", corruptPath);
    }

    // WAL sidecar files must be handled together with main DB
    Path walPath = dbPath.resolveSibling(dbPath.getFileName() + "-wal");
    Path shmPath = dbPath.resolveSibling(dbPath.getFileName() + "-shm");

    if (Files.exists(walPath)) {
      Path corruptWalPath = corruptPath.resolveSibling(dbPath.getFileName() + "-wal.corrupt");
      Files.move(walPath, corruptWalPath, StandardCopyOption.REPLACE_EXISTING);
      log.warn("Quarantined WAL file to: {}", corruptWalPath);
    }

    if (Files.exists(shmPath)) {
      // SHM is a shared-memory file, safe to delete (will be recreated)
      Files.deleteIfExists(shmPath);
      log.debug("Deleted SHM sidecar file");
    }

    // 2. Attempt recovery from backup
    if (Files.exists(backupPath)) {
      Files.copy(backupPath, dbPath, StandardCopyOption.REPLACE_EXISTING);
      log.warn("Restored database from backup: {}", backupPath);
    } else {
      log.warn("No backup found at {}. A fresh database will be created.", backupPath);
    }

    // 3. Re-open the queue with forced integrity check to validate the restored backup.
    // SqliteJobQueue.open() is designed to be re-callable after close().
    jobQueue.openWithIntegrityCheck();
    log.info("Job queue re-opened after triage (integrity validated)");
  }

  /**
   * Tempdoc 374 alpha.18 Bug H + alpha.20 Bug M: resolve the models directory.
   *
   * <p>Three-tier fallback:
   *
   * <ol>
   *   <li>{@code contract.modelsDir()} — alpha.20: the install contract records the absolute
   *       path at install time. Survives cold restart because the contract is persisted to
   *       disk; doesn't depend on env-var inheritance across GUI launches. This is the
   *       primary source for users who pre-stage models via {@code JUSTSEARCH_MODELS_DIR}.
   *   <li>{@code ConfigStore.global().get().paths().modelsDir()} — alpha.18: bridges
   *       {@code JUSTSEARCH_MODELS_DIR} env var via {@code EnvRegistry.MODELS_DIR ↔
   *       justsearch.models.dir}. Works at first launch when the env var is set in the
   *       launching shell, and pre-alpha.20 contracts that don't have the field.
   *   <li>{@code aiHome.resolve("models")} — the default-flow fallback when neither
   *       contract nor env var is set (Install AI downloaded to {@code %APPDATA%\models\}).
   * </ol>
   *
   * <p>Without this resolution, {@code VariantSelector.select} resolves contract paths
   * against the wrong directory and the worker reports
   * {@code "Model file missing from disk: ..."} for every installed package after a cold
   * restart (round-10 sandbox finding).
   *
   * <p>Package-private so {@code KnowledgeServerModelsDirTest} can exercise it without
   * spinning up a real {@code KnowledgeServer}.
   */
  static Path resolveModelsDir(InstallContract contract, Path aiHome) {
    if (contract != null && contract.modelsDir() != null) {
      return contract.modelsDir();
    }
    Path configured = ConfigStore.global().get().paths().modelsDir();
    if (configured != null) return configured;
    return aiHome != null ? aiHome.resolve("models") : null;
  }
}
