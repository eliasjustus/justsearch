/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.indexerworker.grpc.RequestMetadataInterceptor;
import io.justsearch.indexerworker.grpc.TracingServerInterceptor;
import io.justsearch.ipc.logging.MdcContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.FailedJob;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
import io.justsearch.ipc.ListFailedJobsRequest;
import io.justsearch.ipc.ListFailedJobsResponse;
import io.justsearch.ipc.ClearFailedJobsRequest;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.ResetIndexRequest;
import io.justsearch.ipc.ResetIndexResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.IndexingJobChangeFeed;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ort.OrtCudaStatus;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC Ingest service implementation for batch file ingestion.
 *
 * <p>Receives file paths from the main process and enqueues them for indexing.
 * The actual indexing is performed asynchronously by the IndexingLoop.
 *
 * <p><b>Security:</b> All paths are validated and sanitized before processing.
 * <p><b>Rate Limiting:</b> Batch size is capped to prevent queue flooding.
 */
public final class GrpcIngestService extends IngestServiceGrpc.IngestServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(GrpcIngestService.class);
  /** Maximum chars stored in `content_preview` (result list snippet field). */
  private static final int CONTENT_PREVIEW_MAX_CHARS = 4096;

  /** Maximum files allowed in a single batch request. */
  private static final int MAX_BATCH_SIZE = 10_000;

  /** Maximum queue depth before rejecting new submissions. */
  private static final long MAX_QUEUE_DEPTH = 100_000;

  /** Opens an MDC scope with trace_id and request_id from the gRPC propagated context. */
  private MdcContext openRequestMdc() { // NOPMD - AutoCloseable for logging context side-effect
    SpanContext spanCtx = Span.fromContext(TracingServerInterceptor.currentOtelContext())
        .getSpanContext();
    String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;
    return MdcContext.request(traceId, RequestMetadataInterceptor.currentRequestId());
  }

  /** Dangerous path components that indicate traversal attempts. */
  private static final Set<String> DANGEROUS_COMPONENTS = Set.of("..", "..\\", "../");

  private final JobQueue jobQueue;
  private final IndexingLoop indexingLoop;
  private final WorkerSignalBus signalBus;
  private final io.justsearch.adapters.lucene.runtime.RunningRuntime ingestLifecycle;
  private final IndexGenerationManager indexGenerationManager;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();
  private final IndexStatusOps statusOps;
  private final SyncDirectoryOps syncOps;
  private final IngestSwitchBufferOps switchBufferOps;
  private final MigrationControlOps migrationOps;
  private RootWatcherRegistry rootWatcherRegistry = new RootWatcherRegistry();

  // Tempdoc 419 / T5.3 (ADR-0028): scoped reverse-lookup store. Defaults to NOOP so any
  // composition that hasn't wired it sees found=false on every lookup. DefaultWorkerAppServices
  // injects the real SqlitePathResolutionStore via setPathResolutionStore at boot.
  private io.justsearch.indexerworker.path.PathResolutionStore pathResolutionStore =
      io.justsearch.indexerworker.path.PathResolutionStore.NOOP;

  private static final String VDU_MAX_RETRIES_EXCEEDED_ERROR = "Max retries exceeded";
  private static final String VDU_MAX_RETRIES_EXCEEDED_ENRICHMENT =
      "{\"error\": \"Max retries exceeded\"}";
  private static final int RECOVER_VDU_QUERY_LIMIT = 1000;

  /**
   * Creates a new GrpcIngestService backed by the specified lifecycle managers.
   *
   * @param jobQueue The job queue for persisting ingest jobs
   * @param indexingLoop The indexing loop for commit status
   * @param signalBus The signal bus for coordination metrics
   * @param indexPath The path to the Lucene index directory (for size calculation)
   * @param ingestLifecycle The lifecycle manager for write/mutation operations
   * @param searchLifecycle The lifecycle manager for search/status reads
   */
  public GrpcIngestService(
      JobQueue jobQueue,
      IndexingLoop indexingLoop,
      WorkerSignalBus signalBus,
      Path indexBasePath,
      Path indexPath,
      io.justsearch.adapters.lucene.runtime.RunningRuntime ingestLifecycle,
      io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle,
      Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
      long migrationSwitchingMaxDurationMs,
      Runnable restartWorkerCallback) {
    this.jobQueue = jobQueue;
    this.indexingLoop = indexingLoop;
    this.signalBus = signalBus;
    this.ingestLifecycle = ingestLifecycle;
    this.indexGenerationManager = indexBasePath == null ? null : new IndexGenerationManager(indexBasePath);
    this.migrationOps = new MigrationControlOps(this.indexGenerationManager, restartWorkerCallback);
    io.justsearch.adapters.lucene.runtime.IndexCountOps ingestCountOps =
        ingestLifecycle != null ? ingestLifecycle.indexCountOps() : null;
    io.justsearch.adapters.lucene.runtime.IndexCountOps searchCountOps =
        searchLifecycle != null ? searchLifecycle.indexCountOps() : null;
    this.statusOps =
        new IndexStatusOps(
            jobQueue,
            indexPath,
            ingestCountOps,
            searchCountOps,
            ingestLifecycle != null ? ingestLifecycle::configuredVectorFormat : null,
            ingestLifecycle != null ? ingestLifecycle::storedVectorFormat : null,
            ingestLifecycle != null ? ingestLifecycle::queryVectorFormatActual : null,
            ingestLifecycle != null ? ingestLifecycle::openTimeCommitUserData : null,
            ingestLifecycle != null ? ingestLifecycle::latestCommitUserDataBestEffort : null,
            this.indexGenerationManager,
            migrationProgressSupplier,
            metrics,
            indexingLoop,
            signalBus,
            migrationSwitchingMaxDurationMs);
    this.syncOps = new SyncDirectoryOps(
        ingestLifecycle != null ? ingestLifecycle.readPathOps() : null,
        ingestLifecycle != null ? ingestLifecycle.pruneOps() : null,
        ingestLifecycle != null ? ingestLifecycle.commitOps() : null,
        jobQueue,
        signalBus);
    this.switchBufferOps =
        new IngestSwitchBufferOps(jobQueue, this.indexGenerationManager, metrics);
  }

  /**
   * Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): expose a best-
   * effort supplier for the active Lucene IndexSearcher generation so
   * {@link SearchOrchestrator} can stamp {@code search.searcher_generation}
   * on every {@code search/retrieval} span. Null-safe — returns null
   * when the generation manager is unwired (tests, early-boot).
   */
  public Supplier<String> activeGenerationSupplier() {
    return () -> {
      if (indexGenerationManager == null) {
        return null;
      }
      IndexGenerationManager.State state = indexGenerationManager.readStateBestEffort();
      return state == null ? null : state.active_generation();
    };
  }

  /**
   * Sets the embedding compatibility controller for status reporting.
   *
   * @param controller the embedding compatibility controller
   */
  public void setEmbeddingCompatController(
      io.justsearch.indexerworker.embed.EmbeddingCompatibilityController controller) {
    statusOps.setEmbeddingCompatController(controller);
  }

  /**
   * F1: Sets the ORT CUDA status supplier for observability.
   *
   * @param supplier supplier that returns the current ORT CUDA status
   */
  public void setOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setOrtCudaStatusSupplier(supplier);
  }

  /** Sets the SPLADE encoder ORT CUDA status supplier. */
  public void setSpladeOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setSpladeOrtCudaStatusSupplier(supplier);
  }

  /** Sets the embedding encoder ORT CUDA status supplier. */
  public void setEmbedOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setEmbedOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the NER encoder ORT CUDA status supplier. */
  public void setNerOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setNerOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the citation-scorer encoder ORT CUDA status supplier. */
  public void setCitationOrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setCitationOrtCudaStatusSupplier(supplier);
  }

  /** Tempdoc 422 — sets the BGE-M3 encoder ORT CUDA status supplier. */
  public void setBgeM3OrtCudaStatusSupplier(Supplier<OrtCudaStatus> supplier) {
    statusOps.setBgeM3OrtCudaStatusSupplier(supplier);
  }

  /** Sets the embedding backend ID supplier (e.g. "onnx"). */
  public void setEmbedBackendSupplier(Supplier<String> supplier) {
    statusOps.setEmbedBackendSupplier(supplier);
  }

  /** Tempdoc 406 — wires the swap-aware runtime gauges supplier for /api/status. */
  public void setRuntimeGaugesSupplier(
      Supplier<io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeGaugesSnapshot>
          supplier) {
    statusOps.setRuntimeGaugesSupplier(supplier);
  }

  /**
   * Tempdoc 419 C3 V1 — wires the worker's RRD store so {@code IndexStatusOps.buildCore} can
   * backfill the recent-job-queue-depth trend.
   */
  public void setRrdStoreSupplier(Supplier<io.justsearch.telemetry.RrdMetricStore> supplier) {
    statusOps.setRrdStoreSupplier(supplier);
  }

  /**
   * Tempdoc 406 — wires the runtime reload trigger. Invoked by the {@code ReloadRuntime}
   * RPC; returns swap duration in ms. {@code null} (default) means reload is unavailable
   * and the RPC will return {@code FAILED_PRECONDITION}.
   */
  private volatile java.util.function.Function<SwapReason, Long> runtimeReloadTrigger;

  public void setRuntimeReloadTrigger(java.util.function.Function<SwapReason, Long> trigger) {
    this.runtimeReloadTrigger = trigger;
  }

  /**
   * Sets which enrichment stages are enabled. Called once at wire time from
   * {@code DefaultWorkerAppServices}. Flows through to the status endpoint so
   * consumers (e.g. jseval readiness polling) can skip coverage checks for
   * disabled stages. See tempdoc 394 / commit {@code c4e677f63}.
   */
  public void setStageEnabled(boolean embedding, boolean splade, boolean ner) {
    statusOps.setStageEnabled(embedding, splade, ner);
  }

  /** Sets the embedding GPU layers supplier. */
  public void setEmbedGpuLayersSupplier(Supplier<Integer> supplier) {
    statusOps.setEmbedGpuLayersSupplier(supplier);
  }

  /** Sets the SPLADE model path supplier. */
  public void setSpladeModelPathSupplier(Supplier<String> supplier) {
    statusOps.setSpladeModelPathSupplier(supplier);
  }

  /** Sets the reranker model path supplier. */
  public void setRerankerModelPathSupplier(Supplier<String> supplier) {
    statusOps.setRerankerModelPathSupplier(supplier);
  }

  /** Sets the NER model path supplier. */
  public void setNerModelPathSupplier(Supplier<String> supplier) {
    statusOps.setNerModelPathSupplier(supplier);
  }

  /** Sets the NER GPU enabled supplier. */
  public void setNerGpuEnabledSupplier(Supplier<Boolean> supplier) {
    statusOps.setNerGpuEnabledSupplier(supplier);
  }

  /**
   * Sets the {@link io.justsearch.ort.PolicySnapshot} supplier for the
   * {@code GetSessionPolicies} rpc (tempdoc 397 §14.28 U4). Returns Worker's authoritative
   * snapshot; Head's re-resolve path in {@code SessionPoliciesController} is deleted in favour
   * of reading this via gRPC.
   */
  public void setPolicySnapshotSupplier(Supplier<io.justsearch.ort.PolicySnapshot> supplier) {
    this.policySnapshotSupplier = supplier;
  }

  private volatile Supplier<io.justsearch.ort.PolicySnapshot> policySnapshotSupplier;

  /** Sets the resolved config supplier for search config status reporting (343). */
  public void setResolvedConfigSupplier(
      Supplier<io.justsearch.configuration.resolved.ResolvedConfig> supplier) {
    statusOps.setResolvedConfigSupplier(supplier);
  }

  private <T> boolean replyIfIndexRuntimeUnavailable(
      String context, StreamObserver<T> responseObserver, T unavailableResponse) {
    if (ingestLifecycle != null) {
      return false;
    }
    log.error("{} failed: ingestLifecycle is null", context);
    responseObserver.onNext(unavailableResponse);
    responseObserver.onCompleted();
    return true;
  }

  private static <T> boolean replyIfBlank(
      String value, StreamObserver<T> responseObserver, T validationResponse) {
    if (value != null && !value.isBlank()) {
      return false;
    }
    responseObserver.onNext(validationResponse);
    responseObserver.onCompleted();
    return true;
  }

  @Override
  public void startMigration(
      MigrationStartRequest request, StreamObserver<MigrationStartResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      migrationOps.startMigration(request, responseObserver);
    }
  }

  @Override
  public void requestCutover(
      MigrationCutoverRequest request,
      StreamObserver<MigrationCutoverResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      migrationOps.requestCutover(request, responseObserver);
    }
  }

  @Override
  public void pauseMigration(
      MigrationPauseRequest request, StreamObserver<MigrationPauseResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      migrationOps.pauseMigration(request, responseObserver);
    }
  }

  @Override
  public void resumeMigration(
      MigrationResumeRequest request, StreamObserver<MigrationResumeResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      migrationOps.resumeMigration(request, responseObserver);
    }
  }

  @Override
  public void rollbackMigration(
      MigrationRollbackRequest request,
      StreamObserver<MigrationRollbackResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      migrationOps.rollbackMigration(request, responseObserver);
    }
  }

  @Override
  public void runIndexGc(IndexGcRequest request, StreamObserver<IndexGcResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      migrationOps.runIndexGc(request, responseObserver);
    }
  }

  @Override
  public void submitBatch(BatchRequest request, StreamObserver<BatchResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      List<String> filePaths = request.getFilePathsList();

      // Validate: empty batch
      if (filePaths.isEmpty()) {
        log.debug("Received empty batch request");
        responseObserver.onNext(batchSuccessResponse(0));
        responseObserver.onCompleted();
        return;
      }

      // Rate limit: batch size
      if (filePaths.size() > MAX_BATCH_SIZE) {
        metrics.recordBatchRejected();
        log.warn("Batch too large: {} files (max {})", filePaths.size(), MAX_BATCH_SIZE);
        responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
            .withDescription(
                "Batch size " + filePaths.size() + " exceeds maximum " + MAX_BATCH_SIZE)
            .asException());
        return;
      }

      // Backpressure: check queue depth
      long currentDepth = jobQueue.queueDepth();
      if (currentDepth >= MAX_QUEUE_DEPTH) {
        metrics.recordBatchRejected();
        log.warn("Queue full: depth {} (max {})", currentDepth, MAX_QUEUE_DEPTH);
        responseObserver.onError(io.grpc.Status.RESOURCE_EXHAUSTED
            .withDescription(
                "Queue depth " + currentDepth + " exceeds maximum " + MAX_QUEUE_DEPTH)
            .asException());
        return;
      }

      log.debug("Received batch request with {} files", filePaths.size());

      try {
        // Sanitize and validate paths
        List<Path> validPaths = new ArrayList<>(filePaths.size());
        int rejected = 0;

        for (String pathStr : filePaths) {
          Path sanitized = sanitizePath(pathStr);
          if (sanitized != null) {
            validPaths.add(sanitized);
          } else {
            rejected++;
          }
        }

        if (rejected > 0) {
          log.warn("Rejected {} invalid/unsafe paths", rejected);
        }

        if (validPaths.isEmpty()) {
          responseObserver.onNext(batchErrorResponse("All paths were invalid or unsafe"));
          responseObserver.onCompleted();
          return;
        }

        // Embedding/index safety: a forced reindex is an explicit "rebuild vectors" intent.
        // Signal the compatibility controller so it can enter REBUILDING and allow embedding writes.
        if (request.getForceReindex()) {
          var controller = statusOps.embeddingCompatController();
          if (controller != null) {
            controller.onForcedReindexRequested();
          }
        }

        // During cutover (SWITCHING), accept the request but buffer it durably instead of
        // mutating the job queue/index directly. This avoids dropping updates while the Worker
        // restarts.
        if (switchBufferOps.isSwitching()) {
          if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
            if (!switchBufferOps.bufferSubmitBatchDuringSwitching(
                sbq, validPaths, filePaths.size(), rejected, responseObserver)) {
              return;
            }
            return;
          }
          IngestSwitchBufferOps.replySwitchingUnavailable(responseObserver);
          return;
        }

        String collection = request.getTargetCollection();
        if (collection != null && collection.isBlank()) {
          collection = null;
        }
        int accepted = jobQueue.enqueue(validPaths, collection);

        // Mark paths for force reindex if requested (bypasses "unchanged" check)
        if (request.getForceReindex() && accepted > 0) {
          List<String> normalizedPaths = validPaths.stream()
              .map(p -> PathNormalizer.normalizePath(p.toAbsolutePath().toString()))
              .toList();
          indexingLoop.markForced(normalizedPaths);
          log.info("Marked {} paths for force reindex", normalizedPaths.size());
        }

        // Record metrics
        metrics.recordBatchSubmitted(accepted);
        metrics.setQueueDepth(jobQueue.queueDepth());

        log.info("Accepted {} of {} files for indexing (rejected {})",
            accepted, filePaths.size(), rejected);

        responseObserver.onNext(batchSuccessResponse(accepted));
        responseObserver.onCompleted();

      } catch (IllegalStateException e) {
        log.error("Queue error", e);
        responseObserver.onError(io.grpc.Status.INTERNAL
            .withDescription("Queue error: " + e.getMessage())
            .asException());
      } catch (InvalidPathException e) {
        log.error("Invalid path", e);
        responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
            .withDescription("Invalid path: " + e.getMessage())
            .asException());
      } catch (RuntimeException e) {
        log.error("Failed to process batch request", e);
        responseObserver.onError(
            io.grpc.Status.INTERNAL
                .withDescription("Batch processing failed: " + e.getMessage())
                .asException());
      }
    }
  }

  /**
   * Sanitizes and validates a file path.
   *
   * <p>Security checks:
   * <ul>
   *   <li>Rejects null/blank paths</li>
   *   <li>Rejects paths containing ".." (traversal attempts)</li>
   *   <li>Normalizes the path to resolve any symbolic links</li>
   *   <li>Requires the path to be absolute</li>
   *   <li>Requires the file to exist (prevents indexing non-existent files)</li>
   * </ul>
   *
   * @param pathStr the raw path string from the client
   * @return sanitized Path, or null if invalid/unsafe
   */
  private Path sanitizePath(String pathStr) {
    if (pathStr == null || pathStr.isBlank()) {
      return null;
    }

    // Check for traversal attempts in raw string
    for (String dangerous : DANGEROUS_COMPONENTS) {
      if (pathStr.contains(dangerous)) {
        log.warn("Rejected path with traversal attempt: {}", pathStr);
        return null;
      }
    }

    try {
      Path path = Path.of(pathStr);

      // Normalize to resolve any remaining oddities
      Path normalized = path.normalize();

      // After normalization, double-check for traversal
      if (normalized.toString().contains("..")) {
        log.warn("Rejected normalized path with traversal: {}", normalized);
        return null;
      }

      // Must be absolute path
      if (!normalized.isAbsolute()) {
        log.debug("Rejected relative path: {}", pathStr);
        return null;
      }

      // File must exist
      if (!Files.exists(normalized)) {
        log.debug("File does not exist: {}", normalized);
        return null;
      }

      // File must be readable
      if (!Files.isReadable(normalized)) {
        log.debug("File not readable: {}", normalized);
        return null;
      }

      return normalized;

    } catch (InvalidPathException e) {
      log.warn("Invalid path syntax: {}", pathStr);
      return null;
    }
  }

  @Override
  public void indexStatus(StatusRequest request, StreamObserver<StatusResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      try {
        responseObserver.onNext(statusOps.buildStatusResponse());
        responseObserver.onCompleted();
      } catch (RuntimeException e) {
        log.error("Failed to get index status", e);
        responseObserver.onNext(
            StatusResponse.newBuilder()
                .setCore(
                    io.justsearch.ipc.CoreStatus.newBuilder()
                        .setIsHealthy(false)
                        .setState("ERROR")
                        .build())
                .build());
        responseObserver.onCompleted();
      }
    }
  }

  @Override
  public void updateVduResult(UpdateVduResultRequest request,
                              StreamObserver<UpdateVduResultResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String docId = request.getDocId();
    log.info("updateVduResult RPC called for doc: {}", docId);

    // Proto getters return empty string for unset values, not null
    if (replyIfBlank(docId, responseObserver, updateVduErrorResponse("doc_id is required"))) {
      return;
    }

    if (replyIfIndexRuntimeUnavailable(
        "updateVduResult", responseObserver, updateVduErrorResponse("Index runtime not available"))) {
      return;
    }

    if (switchBufferOps.isSwitching()) {
      // During cutover, accept and durably buffer VDU updates so Main does not misclassify the event as a hard failure.
      switchBufferOps.bufferDuringSwitchingOrReplyUnavailable(
          "updateVduResult",
          responseObserver,
          sbq -> switchBufferOps.bufferUpdateVduResultDuringSwitching(sbq, request, docId, responseObserver));
      return;
    }

    try {
      // Build the update map
      Map<String, Object> updates = new HashMap<>();

      // Determine effective outcome: prefer new 'outcome' field, fall back to legacy 'vdu_status' parsing
      VduUpdateOutcome outcome = request.getOutcome();
      String legacyStatus = request.getVduStatus();
      VduUpdateOutcome effectiveOutcome = computeEffectiveOutcome(outcome, legacyStatus);

      // Get extracted content (proto3 optional: hasExtractedContent() for presence check)
      boolean hasExtractedContent = request.hasExtractedContent();
      String extractedContent = hasExtractedContent ? request.getExtractedContent() : "";

      // Validate invariants based on outcome
      if (effectiveOutcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT) {
        if (!hasExtractedContent || extractedContent.isBlank()) {
          log.warn("updateVduResult: SUCCESS_TEXT requires non-blank extracted_content, got blank for doc: {}", docId);
          responseObserver.onNext(updateVduErrorResponse("SUCCESS_TEXT requires non-blank extracted_content"));
          responseObserver.onCompleted();
          return;
        }
      }

      // Apply updates based on effective outcome
      switch (effectiveOutcome) {
        case VDU_UPDATE_OUTCOME_SUCCESS_TEXT -> {
          // Overwrite content, language, embedding status; regenerate chunks
        String preview = contentPreview(extractedContent);
        updates.put(SchemaFields.CONTENT, extractedContent);
        updates.put(SchemaFields.CONTENT_PREVIEW, preview);
        updates.put(SchemaFields.LANGUAGE, resolveLanguage(preview));
        updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_COMPLETED);
          updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_VDU);
        // CRITICAL: Trigger re-embedding with new VDU-extracted content
        updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      }
        case VDU_UPDATE_OUTCOME_SUCCESS_EMPTY -> {
          // VDU succeeded but extracted no text (e.g., blank image)
          // Do NOT overwrite content/language, do NOT trigger re-embedding, do NOT regenerate chunks
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_COMPLETED_EMPTY);
          log.info("updateVduResult: VDU succeeded with no extractable text for doc: {}", docId);
        }
        case VDU_UPDATE_OUTCOME_FAILED -> {
          // VDU processing failed
          // Do NOT overwrite content/language, do NOT regenerate chunks
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
          log.info("updateVduResult: VDU failed for doc: {}", docId);
        }
        default -> {
          // UNSPECIFIED with no legacy status - treat as no-op but mark processed
          updates.put(SchemaFields.VDU_PROCESSED, "true");
          if (!legacyStatus.isBlank()) {
            updates.put(SchemaFields.VDU_STATUS, legacyStatus);
          }
          // Legacy behavior: overwrite content if non-blank
          if (!extractedContent.isBlank()) {
            String preview = contentPreview(extractedContent);
            updates.put(SchemaFields.CONTENT, extractedContent);
            updates.put(SchemaFields.CONTENT_PREVIEW, preview);
            updates.put(SchemaFields.LANGUAGE, resolveLanguage(preview));
            updates.put(SchemaFields.EXTRACTION_METHOD, SchemaFields.EXTRACTION_METHOD_VDU);
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
          }
        }
      }

      // VDU enrichment (JSON) - always apply if present
      String enrichment = request.getVduEnrichment();
      if (!enrichment.isBlank()) {
        updates.put(SchemaFields.VDU_ENRICHMENT, enrichment);
      }

      // Page count - always apply if positive
      int pageCount = request.getPageCount();
      if (pageCount > 0) {
        updates.put(SchemaFields.VDU_PAGE_COUNT, String.valueOf(pageCount));
      }

      // Perform the update
      boolean updated = ingestLifecycle.indexingCoordinator().updateDocument(docId, updates);

      if (updated) {
        // Regenerate chunk documents ONLY for SUCCESS_TEXT (or legacy non-blank content)
        boolean shouldRegenerateChunks =
            effectiveOutcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT ||
            (effectiveOutcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED && !extractedContent.isBlank());

        if (shouldRegenerateChunks) {
          int chunksIndexed = regenerateChunks(docId, extractedContent);
          if (chunksIndexed > 0) {
            log.info("updateVduResult: regenerated {} chunks for doc: {}", chunksIndexed, docId);
          }
        }

        // Ensure the updated doc is visible to immediate read-after-write callers
        // without relying on the async NRT refresh thread timing.
        // Commit is deferred to the periodic commit timer (DC7) / IndexingLoop cycle.
        ingestLifecycle.commitOps().maybeRefreshBlocking();
        log.info("updateVduResult success for doc: {} (outcome={})", docId, effectiveOutcome);
        responseObserver.onNext(updateVduSuccessResponse());
      } else {
        log.warn("updateVduResult: document not found: {}", docId);
        responseObserver.onNext(updateVduErrorResponse("Document not found: " + docId));
      }
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("updateVduResult failed for doc: {}", docId, e);
      responseObserver.onNext(updateVduErrorResponse(e.getMessage()));
      responseObserver.onCompleted();
    }
    }
  }

  /**
   * Computes the effective VDU outcome from the explicit outcome field or legacy status string.
   *
   * <p>Compatibility rule: if {@code outcome != UNSPECIFIED}, use it directly.
   * Otherwise, infer from the legacy {@code vdu_status} string.
   */
  // PERMANENT COMPAT - DO NOT REMOVE (bridges legacy vdu_status string to typed outcome enum)
  private VduUpdateOutcome computeEffectiveOutcome(VduUpdateOutcome outcome, String legacyStatus) {
    if (outcome != null && outcome != VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED) {
      return outcome;
    }
    // Fall back to legacy status parsing
    if (legacyStatus == null || legacyStatus.isBlank()) {
      return VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED;
    }
    return switch (legacyStatus.toUpperCase(Locale.ROOT)) {
      case "COMPLETED" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT; // Assume text if legacy COMPLETED
      case "COMPLETED_EMPTY" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
      case "FAILED" -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED;
      default -> VduUpdateOutcome.VDU_UPDATE_OUTCOME_UNSPECIFIED;
    };
  }

  /**
   * Regenerates chunk documents for a parent document after VDU processing.
   *
   * <p>Deletes existing chunks (using field-based deletion) and creates new ones
   * from the VDU-extracted content. This ensures RAG retrieval uses the improved
   * VDU text rather than stale Tika extraction.
   *
   * @param parentDocId the parent document ID (normalized path)
   * @param content the VDU-extracted content to chunk
   * @return number of chunks indexed
   */
  private int regenerateChunks(String parentDocId, String content) {
    if (ingestLifecycle == null) {
      log.warn("regenerateChunks: ingestLifecycle is null");
      return 0;
    }
    return ChunkDocumentWriter.regenerateChunksFromExistingParent(
        ingestLifecycle.documentFieldOps(), ingestLifecycle.indexingCoordinator(), parentDocId, content);
  }

  @Override
  public void deleteByPath(DeleteByPathRequest request,
                           StreamObserver<DeleteByPathResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String pathPrefix = request.getPath();
    log.info("deleteByPath RPC called for prefix: {}", pathPrefix);

    if (replyIfBlank(pathPrefix, responseObserver, deleteByPathResponse(0, "Path prefix is required"))) {
      return;
    }

    if (replyIfIndexRuntimeUnavailable(
        "deleteByPath", responseObserver, deleteByPathResponse(-1, "Index runtime not available"))) {
      return;
    }

    try {
      if (switchBufferOps.isSwitching()) {
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          if (!switchBufferOps.bufferDeleteByPathDuringSwitching(sbq, pathPrefix, responseObserver)) {
            return;
          }
          return;
        }
        IngestSwitchBufferOps.replySwitchingUnavailable(responseObserver);
        return;
      }

      // 1. Delete from Lucene FIRST (user-facing impact)
      ingestLifecycle.indexingCoordinator().deleteByPathPrefix(pathPrefix);

      // 2. Delete from job queue (count tracking)
      int jobsDeleted = jobQueue.deleteByPathPrefix(pathPrefix);

      // 3. Commit Lucene changes
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_PATH);

      log.info("deleteByPath complete: {} jobs deleted for prefix: {}", jobsDeleted, pathPrefix);

      responseObserver.onNext(deleteByPathResponse(jobsDeleted, ""));
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("deleteByPath failed for prefix: {}", pathPrefix, e);
      responseObserver.onNext(deleteByPathResponse(-1, e.getMessage()));
      responseObserver.onCompleted();
    }
    }
  }

  // ==================== Canonical metadata helpers (UX-oriented) ====================

  private static String contentPreview(String content) {
    return LanguageUtils.contentPreview(content, CONTENT_PREVIEW_MAX_CHARS);
  }

  private static String resolveLanguage(String preview) {
    return LanguageUtils.resolveLanguage(preview);
  }

  @Override
  public void deleteById(DeleteByIdRequest request,
                         StreamObserver<DeleteByIdResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String docId = request.getDocId();
    log.info("deleteById RPC called for doc_id: {}", docId);

    if (replyIfBlank(docId, responseObserver, deleteByIdResponse(false, "doc_id is required"))) {
      return;
    }

    if (replyIfIndexRuntimeUnavailable(
        "deleteById", responseObserver, deleteByIdResponse(false, "Index runtime not available"))) {
      return;
    }

    try {
      // Normalize the path (lowercase on Windows)
      String normalizedId = normalizeDocIdForMutation(docId);

      if (switchBufferOps.isSwitching()) {
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          if (!switchBufferOps.bufferDeleteByIdDuringSwitching(sbq, normalizedId, responseObserver)) {
            return;
          }
          return;
        }
        IngestSwitchBufferOps.replySwitchingUnavailable(responseObserver);
        return;
      }

      // 1. Delete from Lucene (exact match)
      ingestLifecycle.indexingCoordinator().deleteByIdAndChunks(normalizedId);

      // 2. Delete from job queue (exact match)
      jobQueue.deleteByExactPath(normalizedId);

      // 3. Commit Lucene changes
      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_DELETE_BY_ID);

      log.info("deleteById complete for doc_id: {}", normalizedId);

      responseObserver.onNext(deleteByIdResponse(true, ""));
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("deleteById failed for doc_id: {}", docId, e);
      responseObserver.onNext(deleteByIdResponse(false, e.getMessage()));
      responseObserver.onCompleted();
    }
    }
  }

  @Override
  public void pruneMissing(PruneRequest request,
                           StreamObserver<PruneResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String pathPrefix = request.getPathPrefix();
    log.info("pruneMissing RPC called for prefix: {}", pathPrefix);

    if (replyIfBlank(pathPrefix, responseObserver, pruneErrorResponse("path_prefix is required"))) {
      return;
    }

    if (replyIfIndexRuntimeUnavailable(
        "pruneMissing", responseObserver, pruneErrorResponse("Index runtime not available"))) {
      return;
    }

    try {
      if (switchBufferOps.isSwitching()) {
        // Keep the cutover fence: prune is a mutation, so durably buffer it during SWITCHING.
        if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
          if (!switchBufferOps.bufferPruneMissingDuringSwitching(sbq, pathPrefix, responseObserver)) {
            return;
          }
          return;
        }
        IngestSwitchBufferOps.replySwitchingUnavailable(responseObserver);
        return;
      }

      // Prune orphan documents - abort if user becomes active
      int result = ingestLifecycle.pruneOps().pruneByPathPrefix(
          pathPrefix,
          signalBus::isUserActive,  // Abort checker
          100  // Throttle batch size
      );

      boolean aborted = result < 0;
      int prunedCount = Math.max(0, result);

      if (aborted) {
        log.info("pruneMissing aborted for prefix: {} (user activity)", pathPrefix);
      } else {
        log.info("pruneMissing complete: {} orphans pruned for prefix: {}", prunedCount, pathPrefix);
      }

      responseObserver.onNext(pruneResultResponse(prunedCount, aborted));
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("pruneMissing failed for prefix: {}", pathPrefix, e);
      responseObserver.onNext(pruneErrorResponse(e.getMessage()));
      responseObserver.onCompleted();
    }
    }
  }

  /**
   * Bidirectional sync: delete orphaned documents + add missing files.
   *
   * <p>This replaces the one-directional PruneMissing with full reconciliation:
   * <ol>
   *   <li>Delete documents for files that no longer exist on disk</li>
   *   <li>Enqueue files that exist on disk but are not in the index</li>
   * </ol>
   *
   * <p>Used for: OVERFLOW events, periodic maintenance, Windows DELETE workaround.
   *
   * <p><b>Throttling:</b> Checks user activity every 100 files, sleeps 1ms every 100 files.
   */
  @Override
  public void syncDirectory(SyncDirectoryRequest request,
                            StreamObserver<SyncDirectoryResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      String rootPath = request.getRootPath();
      boolean force = request.getForce();
      log.info("syncDirectory RPC called for root: {} (force={})", rootPath, force);

      // Validate input (protobuf returns empty string, not null)
      if (replyIfBlank(rootPath, responseObserver, syncDirectoryErrorResponse("root_path is required"))) {
        return;
      }

      if (switchBufferOps.isSwitching()) {
        // During cutover, accept and durably buffer sync requests so OVERFLOW/burst events don't get lost.
        switchBufferOps.bufferDuringSwitchingOrReplyUnavailable(
            "syncDirectory",
            responseObserver,
            sbq -> switchBufferOps.bufferSyncDirectoryDuringSwitching(sbq, rootPath, force, responseObserver));
        return;
      }

      // Check user activity (unless force=true)
      if (!force && signalBus.isUserActive()) {
        log.debug("syncDirectory skipped - user is active");
        responseObserver.onNext(syncDirectorySkippedResponse());
        responseObserver.onCompleted();
        return;
      }

      if (replyIfIndexRuntimeUnavailable(
          "syncDirectory",
          responseObserver,
          syncDirectoryErrorResponse("Index runtime not available"))) {
        return;
      }

      syncOps.execute(rootPath, force, responseObserver);
    }
  }

  /**
   * Tempdoc 626 §Axis-A — in-process reconcile entry point for the Worker-side watcher's
   * OVERFLOW/burst recovery. Routes through the full {@link #syncDirectory} pipeline (switch-buffer,
   * user-activity, and index-runtime guards) with a swallowing observer, so the Worker watcher no
   * longer needs the retired Head watcher's cross-process {@code syncDirectory} RPC. Best-effort:
   * outcomes are logged and dropped; the periodic sync remains the backstop.
   */
  public void reconcileRoot(Path root, boolean force) {
    if (root == null) {
      return;
    }
    SyncDirectoryRequest request =
        SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).setForce(force).build();
    syncDirectory(request, new SwallowingSyncObserver(root.toString(), force));
  }

  /** Discards the {@link #reconcileRoot} reconcile result; logs only failures. */
  private static final class SwallowingSyncObserver
      implements StreamObserver<SyncDirectoryResponse> {
    private final String rootPath;
    private final boolean force;

    SwallowingSyncObserver(String rootPath, boolean force) {
      this.rootPath = rootPath;
      this.force = force;
    }

    @Override
    public void onNext(SyncDirectoryResponse value) {
      log.debug(
          "In-process reconcile for {} (force={}): {} added, {} deleted, skipped={}",
          rootPath,
          force,
          value.getFilesAdded(),
          value.getFilesDeleted(),
          value.getSkipped());
    }

    @Override
    public void onError(Throwable t) {
      log.warn("In-process reconcile failed for {} (force={}): {}", rootPath, force, t.getMessage());
    }

    @Override
    public void onCompleted() {
      // no-op
    }
  }

  @Override
  public void queryPendingVdu(QueryPendingVduRequest request,
                              StreamObserver<QueryPendingVduResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    int limit = request.getLimit();
    if (limit <= 0) {
      limit = 100;  // Default limit
    }

    log.debug("queryPendingVdu RPC called with limit: {}", limit);

    if (replyIfIndexRuntimeUnavailable(
        "queryPendingVdu",
        responseObserver,
        QueryPendingVduResponse.newBuilder().setTotalCount(0).build())) {
      return;
    }

    try {
      // Query documents with vdu_status=PENDING
      List<String> docIds = ingestLifecycle.documentFieldOps().queryDocIdsByField(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING,
          limit);

      // Get total count (may be more than returned)
      int totalCount = ingestLifecycle.indexCountOps().countByField(
          SchemaFields.VDU_STATUS,
          SchemaFields.VDU_STATUS_PENDING);

      log.info("queryPendingVdu: returning {} of {} pending docs", docIds.size(), totalCount);

      responseObserver.onNext(QueryPendingVduResponse.newBuilder()
          .addAllDocIds(docIds)
          .setTotalCount(totalCount)
          .build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("queryPendingVdu failed", e);
      responseObserver.onNext(QueryPendingVduResponse.newBuilder()
          .setTotalCount(0)
          .build());
      responseObserver.onCompleted();
    }
    }
  }

  @Override
  public void markVduProcessing(MarkVduProcessingRequest request,
                                StreamObserver<MarkVduProcessingResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String docId = request.getDocId();
    int maxRetries = resolveMaxRetries(request.getMaxRetries());

    log.debug("markVduProcessing RPC called for doc: {}, maxRetries: {}", docId, maxRetries);

    if (replyIfBlank(docId, responseObserver, markVduErrorResponse("doc_id is required"))) {
      return;
    }

    if (replyIfIndexRuntimeUnavailable(
        "markVduProcessing", responseObserver, markVduErrorResponse("Index runtime not available"))) {
      return;
    }

    if (switchBufferOps.isSwitching()) {
      switchBufferOps.bufferDuringSwitchingOrReplyUnavailable(
          "markVduProcessing",
          responseObserver,
          sbq -> {
            String normalizedId = normalizeDocIdForMutation(docId);
            int currentCount = readVduRetryCountBestEffort(normalizedId);
            return switchBufferOps.bufferMarkVduDuringSwitching(
                sbq, normalizedId, currentCount, maxRetries, responseObserver);
          });
      return;
    }

    try {
      int currentCount = readVduRetryCountBestEffort(docId);
      responseObserver.onNext(applyMarkVduProcessing(docId, currentCount, maxRetries));
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("markVduProcessing failed for doc: {}", docId, e);
      responseObserver.onNext(markVduErrorResponse(e.getMessage()));
      responseObserver.onCompleted();
    }
    }
  }

  private static int resolveMaxRetries(int requestedMaxRetries) {
    return requestedMaxRetries <= 0 ? SchemaFields.VDU_MAX_RETRIES : requestedMaxRetries;
  }

  private int readVduRetryCountBestEffort(String docId) {
    String currentCountStr = ingestLifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_RETRY_COUNT);
    return ParseUtils.parseIntSafe(currentCountStr, 0);
  }

  private MarkVduProcessingResponse applyMarkVduProcessing(
      String docId, int currentCount, int maxRetries) throws Exception {
    MarkVduRetryDecision decision = decideMarkVduRetry(currentCount, maxRetries);

    // Check if max retries exceeded.
    if (decision.maxRetriesExceeded()) {
      log.warn("markVduProcessing: max retries ({}) exceeded for doc: {}", maxRetries, docId);

      // Mark as FAILED due to max retries.
      Map<String, Object> updates = new HashMap<>();
      updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_FAILED);
      updates.put(SchemaFields.VDU_ENRICHMENT, VDU_MAX_RETRIES_EXCEEDED_ENRICHMENT);
      ingestLifecycle.indexingCoordinator().updateDocument(docId, updates, true);
      // Commit deferred to periodic commit timer (DC7) / IndexingLoop cycle.
      return markVduErrorResponse(VDU_MAX_RETRIES_EXCEEDED_ERROR);
    }

    // Increment retry count and mark as PROCESSING.
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING);
    updates.put(SchemaFields.VDU_RETRY_COUNT, String.valueOf(decision.retryCount()));

    boolean updated = ingestLifecycle.indexingCoordinator().updateDocument(docId, updates, true);
    if (updated) {
      // Commit deferred to periodic commit timer (DC7) / IndexingLoop cycle.
      log.debug(
          "markVduProcessing: doc {} marked PROCESSING, retry {}/{}",
          docId,
          decision.retryCount(),
          maxRetries);
      return markVduSuccessResponse(decision.retryCount());
    }
    return markVduErrorResponse("Document not found: " + docId);
  }

  private List<String> processingDocIdsForRecovery() {
    return ingestLifecycle.documentFieldOps().queryDocIdsByField(
        SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PROCESSING, RECOVER_VDU_QUERY_LIMIT);
  }

  private boolean resetVduStatusToPending(String docId) throws Exception {
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);
    return ingestLifecycle.indexingCoordinator().updateDocument(docId, updates, true);
  }

  @FunctionalInterface
  interface VduProcessingResetOp {
    boolean resetToPending(String docId) throws Exception;
  }

  static int recoverProcessingDocsWithResetOp(
      List<String> processingDocIds, VduProcessingResetOp resetOp) {
    int recovered = 0;
    for (String docId : processingDocIds) {
      try {
        // Reset to PENDING (retry count already incremented, so won't loop forever).
        if (resetOp.resetToPending(docId)) {
          recovered++;
        }
      } catch (Exception e) {
        log.warn("Failed to recover doc: {}", docId, e);
      }
    }
    return recovered;
  }

  private int recoverProcessingDocs(List<String> processingDocIds) {
    return recoverProcessingDocsWithResetOp(processingDocIds, this::resetVduStatusToPending);
  }

  @Override
  public void recoverVduProcessing(RecoverVduProcessingRequest request,
                                   StreamObserver<RecoverVduProcessingResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    log.info("recoverVduProcessing RPC called");

    if (replyIfIndexRuntimeUnavailable(
        "recoverVduProcessing", responseObserver, recoverVduCountResponse(0))) {
      return;
    }

    if (switchBufferOps.isSwitching()) {
      // During cutover, accept and durably buffer recovery so clients don't depend on retries.
      switchBufferOps.bufferDuringSwitchingOrReplyUnavailable(
          "recoverVduProcessing",
          responseObserver,
          sbq -> switchBufferOps.bufferRecoverVduProcessingDuringSwitching(sbq, responseObserver));
      return;
    }

    try {
      List<String> processingDocIds = processingDocIdsForRecovery();

      if (processingDocIds.isEmpty()) {
        log.info("recoverVduProcessing: no stuck documents found");
        responseObserver.onNext(recoverVduCountResponse(0));
        responseObserver.onCompleted();
        return;
      }

      log.info("recoverVduProcessing: found {} stuck documents", processingDocIds.size());

      int recovered = recoverProcessingDocs(processingDocIds);

      // Commit deferred to periodic commit timer (DC7) / IndexingLoop cycle.

      log.info("recoverVduProcessing: recovered {} of {} documents", recovered, processingDocIds.size());

      responseObserver.onNext(recoverVduCountResponse(recovered));
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("recoverVduProcessing failed", e);
      responseObserver.onNext(recoverVduCountResponse(0));
      responseObserver.onCompleted();
    }
    }
  }

  @Override
  public void updateDocumentPaths(
      UpdatePathsRequest request, StreamObserver<UpdatePathsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    log.info("updateDocumentPaths RPC called with {} mappings", request.getMappingsCount());

    if (request.getMappingsCount() == 0) {
      responseObserver.onNext(
          UpdatePathsResponse.newBuilder().setUpdatedCount(0).build());
      responseObserver.onCompleted();
      return;
    }

    if (replyIfIndexRuntimeUnavailable(
        "updateDocumentPaths",
        responseObserver,
        UpdatePathsResponse.newBuilder().setUpdatedCount(0).build())) {
      return;
    }

    try {
      int updatedCount = 0;
      List<String> failedPaths = new ArrayList<>();

      for (PathMapping mapping : request.getMappingsList()) {
        String oldPath = PathNormalizer.normalizePath(mapping.getOldPath());
        String newPath = PathNormalizer.normalizePath(mapping.getNewPath());

        int count = ingestLifecycle.indexingCoordinator().updateDocumentPaths(oldPath, newPath);
        if (count > 0) {
          updatedCount++;
        } else {
          failedPaths.add(mapping.getOldPath());
        }
      }

      ingestLifecycle.commitOps().commitAndTrack(CommitReason.GRPC_UPDATE_PATHS);

      log.info(
          "updateDocumentPaths: updated {} paths, {} failed",
          updatedCount,
          failedPaths.size());

      responseObserver.onNext(
          UpdatePathsResponse.newBuilder()
              .setUpdatedCount(updatedCount)
              .addAllFailedPaths(failedPaths)
              .build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("updateDocumentPaths failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("Failed to update document paths: " + e.getMessage())
              .asRuntimeException());
    }
    }
  }

  @Override
  public void listFailedJobs(
      ListFailedJobsRequest request,
      StreamObserver<ListFailedJobsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      int limit = request.getLimit();
      List<JobQueue.FailedJobInfo> jobs = jobQueue.listFailedJobs(limit);
      long totalCount = jobQueue.failureSummary().failedCount();

      ListFailedJobsResponse.Builder resp =
          ListFailedJobsResponse.newBuilder().setTotalCount(totalCount);
      for (JobQueue.FailedJobInfo job : jobs) {
        resp.addJobs(
            FailedJob.newBuilder()
                .setPath(job.path() != null ? job.path() : "")
                .setErrorMessage(job.errorMessage() != null ? job.errorMessage() : "")
                .setAttempts(job.attempts())
                .setLastUpdatedMs(job.lastUpdatedMs())
                .setCollection(job.collection() != null ? job.collection() : "")
                .build());
      }
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void countJobsByPathPrefix(
      io.justsearch.ipc.CountJobsByPathPrefixRequest request,
      StreamObserver<io.justsearch.ipc.CountJobsByPathPrefixResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      JobQueue.JobStateCounts counts = jobQueue.countByPathPrefix(request.getPathPrefix());
      io.justsearch.ipc.IndexingJobCounts wire =
          io.justsearch.ipc.IndexingJobCounts.newBuilder()
              .setPendingCount(counts.pendingCount())
              .setProcessingCount(counts.processingCount())
              .setFailedCount(counts.failedCount())
              .build();
      responseObserver.onNext(
          io.justsearch.ipc.CountJobsByPathPrefixResponse.newBuilder().setCounts(wire).build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void listFailedJobsByPathPrefix(
      io.justsearch.ipc.ListFailedJobsByPathPrefixRequest request,
      StreamObserver<ListFailedJobsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      List<JobQueue.FailedJobInfo> jobs =
          jobQueue.listFailedJobsByPathPrefix(request.getPathPrefix(), request.getLimit());
      ListFailedJobsResponse.Builder resp =
          ListFailedJobsResponse.newBuilder().setTotalCount(jobs.size());
      for (JobQueue.FailedJobInfo job : jobs) {
        resp.addJobs(
            FailedJob.newBuilder()
                .setPath(job.path() != null ? job.path() : "")
                .setErrorMessage(job.errorMessage() != null ? job.errorMessage() : "")
                .setAttempts(job.attempts())
                .setLastUpdatedMs(job.lastUpdatedMs())
                .setCollection(job.collection() != null ? job.collection() : "")
                .build());
      }
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void clearFailedJobs(
      ClearFailedJobsRequest request,
      StreamObserver<ClearFailedJobsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      int deleted = jobQueue.clearFailedJobs();
      responseObserver.onNext(
          ClearFailedJobsResponse.newBuilder().setDeletedCount(deleted).build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void getSessionPolicies(
      io.justsearch.ipc.SessionPoliciesRequest request,
      StreamObserver<io.justsearch.ipc.SessionPoliciesResponse> responseObserver) {
    io.justsearch.ipc.SessionPoliciesResponse.Builder resp =
        io.justsearch.ipc.SessionPoliciesResponse.newBuilder();
    Supplier<io.justsearch.ort.PolicySnapshot> supplier = policySnapshotSupplier;
    io.justsearch.ort.PolicySnapshot snap = supplier != null ? supplier.get() : null;
    if (snap == null) {
      resp.setConfigStatus("surface-unavailable");
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
      return;
    }
    try {
      tools.jackson.databind.ObjectMapper mapper =
          new tools.jackson.databind.json.JsonMapper();
      resp.setConfigStatus("ok");
      resp.setRuntimePolicyJson(mapper.writeValueAsString(snap.runtime()));
      for (var entry : snap.models().entrySet()) {
        resp.putModelPoliciesJson(entry.getKey().name(), mapper.writeValueAsString(entry.getValue()));
      }
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.warn("getSessionPolicies: JSON serialization failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("policy serialization failed: " + e.getMessage())
              .asException());
    }
  }

  /**
   * Tempdoc 406 admin endpoint — triggers a holder swap on the ingest runtime.
   * Calls {@code KnowledgeServer.swapRuntime(...)} via the wired
   * {@code runtimeReloadTrigger}; returns the swap duration in ms.
   */
  @Override
  public void reloadRuntime(
      io.justsearch.ipc.ReloadRuntimeRequest request,
      StreamObserver<io.justsearch.ipc.ReloadRuntimeResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      java.util.function.Function<SwapReason, Long> trigger = this.runtimeReloadTrigger;
      if (trigger == null) {
        log.warn("reloadRuntime: trigger not wired; rejecting with FAILED_PRECONDITION");
        responseObserver.onError(
            io.grpc.Status.FAILED_PRECONDITION
                .withDescription("Runtime reload trigger not wired on this Worker")
                .asRuntimeException());
        return;
      }
      String wire = request.getReason();
      SwapReason reason =
          wire.isBlank() ? SwapReason.ADMIN_TRIGGERED : SwapReason.fromWire(wire);
      log.info("reloadRuntime: starting swap (reason={})", reason.wireValue());
      long durationMs = trigger.apply(reason);
      log.info(
          "reloadRuntime: swap complete in {}ms (reason={})", durationMs, reason.wireValue());
      responseObserver.onNext(
          io.justsearch.ipc.ReloadRuntimeResponse.newBuilder()
              .setSwapDurationMs(durationMs)
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("reloadRuntime failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL.withDescription("Reload failed: " + e.getMessage()).asRuntimeException());
    }
  }

  @Override
  public void recentIngestionEvents(
      io.justsearch.ipc.RecentIngestionEventsRequest request,
      StreamObserver<io.justsearch.ipc.RecentIngestionEventsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      int limit = request.getLimit();
      List<JobQueue.IngestionEventView> events = jobQueue.recentIngestionEvents(limit);
      io.justsearch.ipc.RecentIngestionEventsResponse.Builder resp =
          io.justsearch.ipc.RecentIngestionEventsResponse.newBuilder();
      for (JobQueue.IngestionEventView event : events) {
        io.justsearch.ipc.IngestionEvent.Builder b =
            io.justsearch.ipc.IngestionEvent.newBuilder()
                .setId(event.id())
                .setPathHash(event.pathHash() != null ? event.pathHash() : "")
                .setCollection(event.collection() != null ? event.collection() : "")
                .setOutcomeClass(event.outcomeClass() != null ? event.outcomeClass() : "")
                .setReasonCode(event.reasonCode() != null ? event.reasonCode() : "")
                .setRetryPolicy(event.retryPolicy() != null ? event.retryPolicy() : "")
                .setDiagnosticSummary(
                    event.diagnosticSummary() != null ? event.diagnosticSummary() : "")
                .setObservedAtMs(event.observedAtMs())
                .setSourceKind(event.sourceKind() != null ? event.sourceKind() : "")
                .setArtifactStatus(event.artifactStatus() != null ? event.artifactStatus() : "")
                .setPolicyId(event.policyId() != null ? event.policyId() : "")
                .setParserId(event.parserId() != null ? event.parserId() : "");
        if (event.sourceSizeBytes() != null) b.setSourceSizeBytes(event.sourceSizeBytes());
        if (event.sourceModifiedAtMs() != null) b.setSourceModifiedAtMs(event.sourceModifiedAtMs());
        resp.addEvents(b.build());
      }
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void ingestionOutcomeSummary(
      io.justsearch.ipc.IngestionOutcomeSummaryRequest request,
      StreamObserver<io.justsearch.ipc.IngestionOutcomeSummaryResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      List<JobQueue.IngestionOutcomeSummary> rollups =
          jobQueue.ingestionOutcomeSummary(request.getSinceMs());
      io.justsearch.ipc.IngestionOutcomeSummaryResponse.Builder resp =
          io.justsearch.ipc.IngestionOutcomeSummaryResponse.newBuilder();
      for (JobQueue.IngestionOutcomeSummary rollup : rollups) {
        resp.addRollups(
            io.justsearch.ipc.IngestionOutcomeRollup.newBuilder()
                .setOutcomeClass(rollup.outcomeClass() != null ? rollup.outcomeClass() : "")
                .setReasonCode(rollup.reasonCode() != null ? rollup.reasonCode() : "")
                .setRetryPolicy(rollup.retryPolicy() != null ? rollup.retryPolicy() : "")
                .setCount(rollup.count())
                .setLastObservedAtMs(rollup.lastObservedAtMs())
                .build());
      }
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }
  }

  // ==================== Tempdoc 418 Phase A/B: Worker-owned filesystem traversal ====================

  /**
   * Phase B — replaces the registry-only Phase A scaffolding with one backed by a
   * {@link WorkerMethvinWatcher}. Called by {@code DefaultWorkerAppServices} after the
   * watcher is constructed so all subsequent {@code WatchRoot}/{@code UnwatchRoot} RPCs
   * route real filesystem events into {@link JobQueue}. Must be invoked before any client
   * issues a {@code WatchRoot}.
   */
  public void setRootWatcherRegistry(RootWatcherRegistry registry) {
    this.rootWatcherRegistry = java.util.Objects.requireNonNull(registry, "registry");
  }

  /**
   * Tempdoc 419 / T5.3 (ADR-0028) — wires the scoped reverse-lookup store. Production
   * composition (DefaultWorkerAppServices) calls this with the SqlitePathResolutionStore
   * from InfraContext. Defaults to {@link io.justsearch.indexerworker.path.PathResolutionStore#NOOP}.
   */
  public void setPathResolutionStore(io.justsearch.indexerworker.path.PathResolutionStore store) {
    this.pathResolutionStore =
        store == null ? io.justsearch.indexerworker.path.PathResolutionStore.NOOP : store;
  }

  @Override
  public void lookupPathByHash(
      io.justsearch.ipc.LookupPathByHashRequest request,
      StreamObserver<io.justsearch.ipc.LookupPathByHashResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        responseObserver.onError(
            io.grpc.Status.INVALID_ARGUMENT
                .withDescription("LookupPathByHashRequest.path_hash is required")
                .asRuntimeException());
        return;
      }
      io.justsearch.ipc.LookupPathByHashResponse.Builder resp =
          io.justsearch.ipc.LookupPathByHashResponse.newBuilder();
      pathResolutionStore
          .lookup(pathHash)
          .ifPresentOrElse(
              r ->
                  resp.setFound(true)
                      .setPath(r.normalizedPath() != null ? r.normalizedPath() : "")
                      .setLastSeenAtMs(r.lastSeenAtMs())
                      .setRemovedAtMs(r.removedAtMs() != null ? r.removedAtMs() : 0L),
              () -> resp.setFound(false));
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void scanRoot(
      io.justsearch.ipc.ScanRootRequest request,
      StreamObserver<io.justsearch.ipc.ScanRootProgress> responseObserver) {
    // Tempdoc 419 / T2 — Allocate the scanId at RPC entry. The same value is stamped on every
    // emitted ScanRootProgress event so SSE consumers (T4) can subscribe by scanId, and so
    // log entries from this scan correlate via MDC.
    String scanId = java.util.UUID.randomUUID().toString();
    try (var ignored = openRequestMdc();
        var ignored2 = MdcContext.scan(scanId)) {
      String rootPath = request.getRootPath();
      if (rootPath.isBlank()) {
        responseObserver.onError(
            io.grpc.Status.INVALID_ARGUMENT
                .withDescription("ScanRootRequest.root_path is required")
                .asRuntimeException());
        return;
      }
      Path root;
      try {
        root = Path.of(rootPath).toAbsolutePath().normalize();
      } catch (InvalidPathException e) {
        responseObserver.onError(
            io.grpc.Status.INVALID_ARGUMENT
                .withDescription("ScanRootRequest.root_path is not a valid path: " + e.getMessage())
                .asRuntimeException());
        return;
      }
      WorkerScanOps.ScanMode mode =
          switch (request.getMode()) {
            case SCAN_MODE_RESCAN -> WorkerScanOps.ScanMode.RESCAN;
            case SCAN_MODE_FORCE_REINDEX -> WorkerScanOps.ScanMode.FORCE_REINDEX;
            default -> WorkerScanOps.ScanMode.INITIAL;
          };
      WorkerScanOps.ScanRequest scanRequest =
          new WorkerScanOps.ScanRequest(
              root, request.getCollection(), mode, request.getExcludeGlobsList(), scanId);
      // Tempdoc 418 B-H.3 — Worker owns backpressure + cancellation. The
      // ServerCallStreamObserver.isCancelled() probe lets WorkerScanOps stop walking when the
      // client drops the stream (e.g., RootLifecycleOps removes the watched root mid-scan).
      java.util.function.LongSupplier queueDepth = jobQueue::queueDepth;
      java.util.function.BooleanSupplier isCancelled;
      if (responseObserver
          instanceof io.grpc.stub.ServerCallStreamObserver<io.justsearch.ipc.ScanRootProgress> sco) {
        isCancelled = sco::isCancelled;
      } else {
        isCancelled = () -> false;
      }
      try {
        new WorkerScanOps(jobQueue, queueDepth, isCancelled)
            .scan(scanRequest, responseObserver::onNext);
        responseObserver.onCompleted();
      } catch (java.io.IOException e) {
        log.warn("ScanRoot walk failed for {}: {}", rootPath, e.getMessage());
        responseObserver.onNext(
            io.justsearch.ipc.ScanRootProgress.newBuilder()
                .setComplete(true)
                .setTerminalReasonCode("IO_ERROR")
                .setScanId(scanId)
                .build());
        responseObserver.onCompleted();
      }
    }
  }

  @Override
  public void watchRoot(
      io.justsearch.ipc.WatchRootRequest request,
      StreamObserver<io.justsearch.ipc.WatchRootResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      String rootPath = request.getRootPath();
      if (rootPath.isBlank()) {
        responseObserver.onError(
            io.grpc.Status.INVALID_ARGUMENT
                .withDescription("WatchRootRequest.root_path is required")
                .asRuntimeException());
        return;
      }
      RootWatcherRegistry.WatchResult result =
          rootWatcherRegistry.watch(rootPath, request.getCollection());
      responseObserver.onNext(
          io.justsearch.ipc.WatchRootResponse.newBuilder()
              .setWatching(result.watching())
              .setErrorMessage(result.errorMessage() == null ? "" : result.errorMessage())
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void unwatchRoot(
      io.justsearch.ipc.UnwatchRootRequest request,
      StreamObserver<io.justsearch.ipc.UnwatchRootResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      boolean removed = rootWatcherRegistry.unwatch(request.getRootPath());
      responseObserver.onNext(
          io.justsearch.ipc.UnwatchRootResponse.newBuilder().setUnwatched(removed).build());
      responseObserver.onCompleted();
    }
  }

  @Override
  public void resetIndex(
      ResetIndexRequest request, StreamObserver<ResetIndexResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      log.info("resetIndex: starting profiling reset");

      indexingLoop.resetForProfiling(
          () -> {
            try {
              // External cleanup — runs while the loop is stopped
              jobQueue.clearAll();
              ingestLifecycle.indexingCoordinator().deleteAll();
              ingestLifecycle.commitOps().commitAndTrack(CommitReason.RESET);
              // Force searcher refresh so docCount()/isUnmodified() see the empty index.
              // Without this, the stale SearcherManager shows old docs and the loop skips
              // re-submitted files as "unchanged".
              ingestLifecycle.commitOps().maybeRefreshBlocking();
              var ds = indexingLoop.getDisambiguationService();
              if (ds != null) {
                ds.reset();
              }
              OperationalMetrics.getInstance().resetAll();
            } catch (Exception e) {
              throw new RuntimeException("Reset cleanup failed", e);
            }
          });

      log.info("resetIndex: profiling reset complete");
      responseObserver.onNext(ResetIndexResponse.newBuilder().setSuccess(true).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("resetIndex failed", e);
      responseObserver.onNext(ResetIndexResponse.newBuilder().setSuccess(false).build());
      responseObserver.onCompleted();
    }
  }

  // ==================== Slice 445 — Job-queue TABULAR Resource ====================

  /**
   * Streams the indexing-jobs collection: one snapshot frame followed by
   * per-row delta frames as the SqliteJobQueue mutates. Backs the
   * {@code core.indexing-jobs} TABULAR Resource (Category × SSE_STREAM ×
   * Privacy.HASHED_REQUIRES_RESOLVER).
   *
   * <p>Wire shape (per indexing.proto §1184): each frame is a oneof
   * {snapshot, delta} plus a monotonic seq. Snapshot is one frame; subsequent
   * frames are deltas.
   *
   * <p>Privacy: every row carries SHA-256 hex {@code path_hash}, never raw
   * paths. Path resolution is the separate {@code core.resolve-path-hash}
   * Operation pinned by ADR-0028 / {@code LibraryResolveHashOnlyCallerPin}.
   */
  @Override
  public void subscribeIndexingJobs(
      io.justsearch.ipc.SubscribeIndexingJobsRequest request,
      StreamObserver<io.justsearch.ipc.IndexingJobsFrame> responseObserver) {
    var feedOpt = jobQueue.indexingJobChangeFeed();
    if (feedOpt.isEmpty()) {
      responseObserver.onError(
          io.grpc.Status.UNIMPLEMENTED
              .withDescription("Job queue does not support change-feed (non-SQLite implementation)")
              .asRuntimeException());
      return;
    }
    IndexingJobChangeFeed feed = feedOpt.get();

    @SuppressWarnings("unchecked")
    var serverCallObs =
        (io.grpc.stub.ServerCallStreamObserver<io.justsearch.ipc.IndexingJobsFrame>)
            responseObserver;
    java.util.concurrent.atomic.AtomicReference<IndexingJobChangeFeed.Subscription> subRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicLong frameSeq =
        new java.util.concurrent.atomic.AtomicLong(0L);

    java.util.function.Consumer<IndexingJobChangeFeed.Delta> consumer =
        delta -> {
          if (serverCallObs.isCancelled()) return;
          io.justsearch.ipc.IndexingJobsDelta.Builder deltaBuilder =
              io.justsearch.ipc.IndexingJobsDelta.newBuilder();
          switch (delta) {
            case IndexingJobChangeFeed.Delta.Insert ins ->
                deltaBuilder.setInsert(toJobView(ins.row()));
            case IndexingJobChangeFeed.Delta.Update upd ->
                deltaBuilder.setUpdate(toJobView(upd.row()));
            case IndexingJobChangeFeed.Delta.Delete del ->
                deltaBuilder.setDeletePathHash(del.pathHash());
          }
          synchronized (serverCallObs) {
            try {
              serverCallObs.onNext(
                  io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                      .setDelta(deltaBuilder.build())
                      .setSeq(frameSeq.incrementAndGet())
                      .build());
            } catch (RuntimeException e) {
              log.warn("subscribeIndexingJobs: delta delivery failed; closing subscription", e);
              IndexingJobChangeFeed.Subscription s = subRef.get();
              if (s != null) s.close();
            }
          }
        };

    serverCallObs.setOnCancelHandler(
        () -> {
          IndexingJobChangeFeed.Subscription s = subRef.get();
          if (s != null) s.close();
        });

    try {
      var snap = feed.subscribeWithSnapshot(consumer);
      subRef.set(snap.subscription());

      io.justsearch.ipc.IndexingJobsSnapshot.Builder snapBuilder =
          io.justsearch.ipc.IndexingJobsSnapshot.newBuilder();
      for (IndexingJobChangeFeed.JobRow row : snap.items()) {
        snapBuilder.addItems(toJobView(row));
      }
      synchronized (serverCallObs) {
        serverCallObs.onNext(
            io.justsearch.ipc.IndexingJobsFrame.newBuilder()
                .setSnapshot(snapBuilder.build())
                .setSeq(frameSeq.incrementAndGet())
                .build());
      }
    } catch (java.sql.SQLException e) {
      log.error("subscribeIndexingJobs: snapshot read failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("snapshot read failed: " + e.getMessage())
              .asRuntimeException());
    }
  }

  /**
   * Slice 445 §A.9: cancel an indexing job by path_hash. Resolves the hash
   * via {@code pathResolutionStore} (ADR-0028 reverse-lookup), then marks the
   * row terminal via {@code markDone} with a CANCELLED outcome. The
   * change-feed emits an UPDATE delta to subscribers.
   *
   * <p>Returns {@code cancelled=false} if the path_hash is unknown to the
   * resolution store; the previous_state is reported diagnostically.
   */
  @Override
  public void cancelIndexingJob(
      io.justsearch.ipc.CancelIndexingJobRequest request,
      StreamObserver<io.justsearch.ipc.CancelIndexingJobResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        responseObserver.onError(
            io.grpc.Status.INVALID_ARGUMENT
                .withDescription("CancelIndexingJobRequest.path_hash is required")
                .asRuntimeException());
        return;
      }
      var resolved = pathResolutionStore.lookup(pathHash);
      if (resolved.isEmpty()) {
        responseObserver.onNext(
            io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
                .setCancelled(false)
                .setPreviousState("UNKNOWN")
                .build());
        responseObserver.onCompleted();
        return;
      }
      String pathStr = resolved.get().normalizedPath();
      if (pathStr == null || pathStr.isBlank()) {
        responseObserver.onNext(
            io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
                .setCancelled(false)
                .setPreviousState("REMOVED")
                .build());
        responseObserver.onCompleted();
        return;
      }
      Path path = Path.of(pathStr);
      var outcome =
          io.justsearch.indexerworker.ingest.IngestionOutcome.of(
              io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_PARTIAL,
              "CANCELLED",
              io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE,
              "cancelled via core.cancel-indexing-job");
      jobQueue.markDone(path, outcome);
      responseObserver.onNext(
          io.justsearch.ipc.CancelIndexingJobResponse.newBuilder()
              .setCancelled(true)
              .setPreviousState("PENDING_OR_PROCESSING")
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.error("cancelIndexingJob failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("cancel failed: " + e.getMessage())
              .asRuntimeException());
    }
  }

  /**
   * Slice 445 §A.9: retry a FAILED indexing job by path_hash. Resolves the
   * hash, then re-enqueues. SqliteJobQueue overwrites the FAILED row with a
   * fresh PENDING entry (path is the primary key in the jobs table).
   */
  @Override
  public void retryIndexingJob(
      io.justsearch.ipc.RetryIndexingJobRequest request,
      StreamObserver<io.justsearch.ipc.RetryIndexingJobResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      String pathHash = request.getPathHash();
      if (pathHash.isBlank()) {
        responseObserver.onError(
            io.grpc.Status.INVALID_ARGUMENT
                .withDescription("RetryIndexingJobRequest.path_hash is required")
                .asRuntimeException());
        return;
      }
      var resolved = pathResolutionStore.lookup(pathHash);
      if (resolved.isEmpty()) {
        responseObserver.onNext(
            io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
                .setRetried(false)
                .setPreviousState("UNKNOWN")
                .build());
        responseObserver.onCompleted();
        return;
      }
      String pathStr = resolved.get().normalizedPath();
      if (pathStr == null || pathStr.isBlank()) {
        responseObserver.onNext(
            io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
                .setRetried(false)
                .setPreviousState("REMOVED")
                .build());
        responseObserver.onCompleted();
        return;
      }
      Path path = Path.of(pathStr);
      int enqueued = jobQueue.enqueue(List.of(path));
      if (enqueued == 0) {
        responseObserver.onNext(
            io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
                .setRetried(false)
                .setPreviousState("NOT_RETRYABLE")
                .build());
      } else {
        responseObserver.onNext(
            io.justsearch.ipc.RetryIndexingJobResponse.newBuilder()
                .setRetried(true)
                .setPreviousState("FAILED")
                .build());
      }
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.error("retryIndexingJob failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("retry failed: " + e.getMessage())
              .asRuntimeException());
    }
  }

  private static io.justsearch.ipc.IndexingJobView toJobView(IndexingJobChangeFeed.JobRow row) {
    return io.justsearch.ipc.IndexingJobView.newBuilder()
        .setPathHash(row.pathHash())
        .setState(row.state())
        .setAttempts(row.attempts())
        .setLastUpdatedMs(row.lastUpdatedMs())
        .setErrorMessage(row.errorMessage() == null ? "" : row.errorMessage())
        .setRetryAfterMs(row.retryAfterMs())
        .setCollection(row.collection())
        .build();
  }

}
