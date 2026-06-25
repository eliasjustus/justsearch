/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.grpc.RequestMetadataInterceptor;
import io.justsearch.indexerworker.grpc.TracingServerInterceptor;
import io.justsearch.ipc.logging.MdcContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.FolderBrowseEngine;
import io.justsearch.adapters.lucene.runtime.SuggestOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderBrowseResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderFilesResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FolderInfo;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ListAllDocumentIdsResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.FolderEntry;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListAllDocumentIdsRequest;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import io.justsearch.ipc.ListFolderFilesRequest;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersRequest;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;

import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.reranker.RerankerConfig;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC Search service implementation supporting text, vector, and hybrid search.
 *
 * <p>Executes search queries against the Lucene index and returns results.
 * This service does NOT respect the "breath holding" logic - user queries
 * take priority over background CPU savings.
 *
 * <p>Content returned via gRPC is trimmed to {@link #MAX_CONTENT_CHARS} to prevent
 * memory issues with very large documents.
 *
 * <p>Pipeline modes: TEXT (BM25), VECTOR (KNN), HYBRID (BM25+KNN+RRF), SPLADE.
 * Mode is resolved from {@code PipelineConfig} on the request (or expanded from
 * the deprecated {@code SearchMode} field for backwards compatibility).
 */
public final class GrpcSearchService extends SearchServiceGrpc.SearchServiceImplBase {
  private static final Logger log = LoggerFactory.getLogger(GrpcSearchService.class);

  /** Maximum content characters to return via gRPC to prevent memory issues. */
  private static final int MAX_CONTENT_CHARS = 200_000;

  /** Default/max slice sizes for FetchDocumentSlice. */
  private static final int DEFAULT_SLICE_CHARS = 20_000;
  private static final int MAX_SLICE_CHARS = 200_000;

  private final CommitOps commitOps;
  private final SuggestOps suggestOps;
  private final DocumentFieldOps documentFieldOps;
  private final FolderBrowseEngine folderBrowseEngine;
  private volatile EmbeddingCompatibilityController embeddingCompatController;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();

  private final SearchOrchestrator searchOrchestrator;
  private final CitationMatchOps citationMatchOps;
  private final RagContextOps ragContextOps;
  // Tempdoc 598 review Fix A: the current query-embedding provider, tracked here so the AUTO
  // resolution can gate the dense leg on embedder availability (not just embedding-compat),
  // avoiding a wasted query-embed attempt when the embedder is unloaded (e.g. Online mode).
  private volatile EmbeddingProvider embeddingProvider;

  /** 360: Worker-side search reranker (GPU-capable). Set via deferred wiring. */
  private volatile CrossEncoderReranker searchReranker;

  /**
   * Tempdoc 397 §14.28 U3: supplier for the {@code modelReadyLatch} in KnowledgeServer.
   * Query handlers await this latch before first use (with a 120 s timeout). Closes the
   * T2-E1 boot-race gap where queries arriving before {@code initDeferredModels} completed
   * silently missed reranker / NER wiring. Null = no latch wired (e.g., unit tests that
   * construct this service directly without KnowledgeServer) — in which case await is a
   * no-op.
   */
  private volatile java.util.function.Supplier<java.util.concurrent.CountDownLatch>
      modelReadyLatchSupplier;

  /**
   * Await timeout for the models-ready gate. Matches the migration-enumerator ceiling in
   * {@code KnowledgeServer}. Hardcoded rather than env-var-driven because worker-services is
   * under an ArchUnit rule that forbids direct {@code System.getenv} reads — the gate's
   * timeout is a structural invariant, not an operational tuning knob.
   */
  private static final long MODEL_READY_TIMEOUT_MS = 120_000L;

  /**
   * Creates a new GrpcSearchService backed by the specified Lucene lifecycle manager.
   *
   * @param searchLifecycle The Lucene lifecycle manager
   */
  public GrpcSearchService(io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle) {
    this(searchLifecycle, NoOpEmbeddingProvider.INSTANCE, null);
  }

  /**
   * Creates a new GrpcSearchService with embedding support for hybrid search.
   *
   * @param searchLifecycle The Lucene lifecycle manager
   * @param embeddingProvider Embedding provider for query-time embedding
   */
  public GrpcSearchService(io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle, EmbeddingProvider embeddingProvider) {
    this(searchLifecycle, embeddingProvider, null);
  }

  /**
   * Canonical ctor that takes the shared {@link io.justsearch.indexerworker.server.EncoderBindings}
   * registry. Production composition (DefaultWorkerAppServices) passes the same instance to
   * IndexingLoop so the wire* methods can bind once and both sides observe the update.
   * Tempdoc 516 P3 / Slice 5 (W7.2).
   *
   * @param searchLifecycle The Lucene lifecycle manager
   * @param embeddingProvider Embedding provider for query-time embedding (may be null)
   * @param encoderBindings Shared encoder/service registry (may be null — defaults to empty)
   */
  public GrpcSearchService(
      io.justsearch.adapters.lucene.runtime.LuceneRuntime searchLifecycle,
      EmbeddingProvider embeddingProvider,
      io.justsearch.indexerworker.server.EncoderBindings encoderBindings) {
    EmbeddingProvider provider =
        embeddingProvider != null ? embeddingProvider : NoOpEmbeddingProvider.INSTANCE;
    this.embeddingProvider = provider;
    io.justsearch.indexerworker.server.EncoderBindings bindings =
        encoderBindings != null
            ? encoderBindings
            : new io.justsearch.indexerworker.server.EncoderBindings();
    this.commitOps = searchLifecycle.commitOps();
    this.suggestOps = searchLifecycle.suggestOps();
    this.documentFieldOps = searchLifecycle.documentFieldOps();
    this.folderBrowseEngine = searchLifecycle.folderBrowseEngine();
    this.searchOrchestrator = new SearchOrchestrator(searchLifecycle, provider, bindings);
    this.citationMatchOps = new CitationMatchOps(
        searchLifecycle.readPathOps(), searchLifecycle.commitOps(), provider);
    this.ragContextOps = new RagContextOps(
        searchLifecycle.chunkSearchOps(),
        searchLifecycle.indexCountOps(),
        searchLifecycle.commitOps(),
        searchLifecycle::resolvedConfig,
        provider,
        searchLifecycle.documentFieldOps());
  }

  /**
   * Deferred injection of the embedding provider for query-time embedding.
   *
   * <p>The embedding model is loaded asynchronously after the gRPC service is created, so the
   * constructor receives null. This setter distributes the provider to all sub-components that need
   * it for query-time vector generation.
   */
  public void setEmbeddingProvider(EmbeddingProvider provider) {
    this.embeddingProvider = provider != null ? provider : NoOpEmbeddingProvider.INSTANCE;
    this.searchOrchestrator.setEmbeddingProvider(provider);
    this.citationMatchOps.setEmbeddingProvider(provider);
    this.ragContextOps.setEmbeddingProvider(provider);
  }

  /**
   * Sets the embedding compatibility controller.
   *
   * <p>This is called after construction to inject the controller, which is created
   * after the gRPC service due to circular dependencies.
   *
   * @param controller the embedding compatibility controller
   */
  public void setEmbeddingCompatController(EmbeddingCompatibilityController controller) {
    this.embeddingCompatController = controller;
  }

  /**
   * Sets the chunk reranker configuration (Phase 5).
   *
   * <p>This is called after construction to inject the config.
   * If config is ready (enabled + model path set), the reranker will be lazily initialized.
   *
   * @param config the chunk reranker configuration
   */
  public void setChunkRerankerConfig(RerankerConfig.ChunkRerankerConfig config) {
    this.ragContextOps.setChunkRerankerConfig(config);
  }

  /**
   * Sets the citation scorer configuration.
   *
   * <p>When config is ready (enabled + model path set), the scorer will be lazily initialized
   * on first use. The scorer runs on CPU only, avoiding GPU contention with the LLM.
   *
   * @param config the citation scorer configuration
   */
  public void setCitationScorerConfig(CitationScorerConfig config) {
    this.citationMatchOps.setCitationScorerConfig(config);
  }

  /**
   * Sets the eagerly-constructed {@link io.justsearch.reranker.CitationScorer} from the
   * composition root (tempdoc 397 §14.26 T2-E1).
   */
  public void setCitationScorer(io.justsearch.reranker.CitationScorer scorer) {
    this.citationMatchOps.setCitationScorer(scorer);
  }

  /**
   * Sets the entity cluster snapshot supplier for disambiguation (Phase C).
   *
   * <p>When set, the search orchestrator will merge entity facet counts by canonical form
   * and expand entity filters to include all variant forms.
   *
   * @param supplier the cluster snapshot supplier
   */
  public void setClusterSnapshotSupplier(
      java.util.function.Supplier<
              io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot>
          supplier) {
    this.searchOrchestrator.setClusterSnapshotSupplier(supplier);
  }

  /**
   * Tempdoc 400 §22 Issue D / LR2-e.4 (Phase 6 / 6.7): inject the active
   * Lucene IndexSearcher generation supplier. Wired from the composition
   * root once {@code IndexStatusOps} (or another stateSnapshot source) is
   * available. The supplier's string value lands on every {@code
   * search/retrieval} span as {@code search.searcher_generation}.
   */
  public void setActiveGenerationSupplier(java.util.function.Supplier<String> supplier) {
    this.searchOrchestrator.setActiveGenerationSupplier(supplier);
  }

  // Tempdoc 516 P3 / Slice 5 (W7.2): setSpladeEncoder + setBgeM3Encoder removed —
  // DefaultWorkerAppServices.wireX now binds once on the shared EncoderBindings and the
  // SearchOrchestrator reads through the same registry. setSpladeIdfQueryEncoder stays
  // (different async path — query-side IDF helper, not the indexing-side encoder).
  public void setSpladeIdfQueryEncoder(
      io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder encoder) {
    this.searchOrchestrator.setSpladeIdfQueryEncoder(encoder);
  }

  /**
   * 360: Sets the Worker-side search reranker (deferred wiring from initDeferredModels).
   *
   * <p>When set, the {@link #rerank} gRPC method delegates to this instance. Also shared with
   * {@link RagContextOps} for chunk reranking, replacing its CPU-only instance.
   */
  public void setSearchReranker(CrossEncoderReranker reranker) {
    this.searchReranker = reranker;
    this.ragContextOps.setSearchReranker(reranker);
  }

  /**
   * Tempdoc 397 §14.28 U3: wires a supplier for the {@code modelReadyLatch} that inference-
   * dependent query handlers ({@link #search}, {@link #retrieveContext}, {@link #rerank},
   * {@link #matchCitations}) await before first use. Closes the T2-E1 boot-race gap.
   */
  public void setModelReadyLatchSupplier(
      java.util.function.Supplier<java.util.concurrent.CountDownLatch> supplier) {
    this.modelReadyLatchSupplier = supplier;
  }

  /**
   * Awaits the models-ready latch (§14.28 U3) with a timeout. No-op when no latch is wired
   * (e.g., unit tests that construct this service directly). Logs a warn if the wait times
   * out — caller proceeds anyway; per-method degradation semantics are handled downstream.
   *
   * <p>Package-private for {@code GrpcSearchServiceModelReadyLatchTest}.
   *
   * @param rpcName for log context
   */
  void awaitModelsReady(String rpcName) {
    var supplier = modelReadyLatchSupplier;
    if (supplier == null) {
      return;
    }
    java.util.concurrent.CountDownLatch latch = supplier.get();
    if (latch == null || latch.getCount() == 0) {
      return;
    }
    try {
      if (!latch.await(MODEL_READY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        log.warn(
            "{}: models not ready after {} ms; proceeding with degraded path",
            rpcName,
            MODEL_READY_TIMEOUT_MS);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn("{}: interrupted while waiting for models-ready latch", rpcName);
    }
  }

  /**
   * Called when Main process claims GPU. Releases reranker GPU session to yield VRAM.
   */
  public void onMainClaimedGpu() {
    ragContextOps.onMainClaimedGpu();
    CrossEncoderReranker sr = searchReranker;
    if (sr != null) {
      sr.releaseGpuSession();
    }
  }

  /**
   * Returns the ORT CUDA status for the search reranker (360), falling back to chunk reranker.
   *
   * @return current ORT CUDA status, or null if no reranker available
   */
  public OrtCudaStatus getOrtCudaStatus() {
    CrossEncoderReranker sr = searchReranker;
    if (sr != null) {
      return sr.getOrtCudaStatus();
    }
    return ragContextOps.getOrtCudaStatus();
  }

  /** Returns true if the citation scorer is initialized and ready for inference (368 RC3). */
  public boolean isCitationScorerActive() {
    return citationMatchOps.isCitationScorerActive();
  }

  /** Snapshot of embedding compatibility state for a single request. */
  private record EmbeddingCompat(boolean allowed, String reasonCode) {}

  /**
   * Takes a consistent snapshot of embedding compatibility state.
   *
   * <p>Reads the volatile controller reference once, extracting both the allowed flag
   * and reason code from the same snapshot to avoid TOCTOU races.
   */
  private EmbeddingCompat embeddingCompat() {
    var controller = embeddingCompatController;
    return new EmbeddingCompat(
        controller == null || controller.allowQueryEmbeddings(),
        controller != null ? controller.reasonCode() : null);
  }

  /**
   * Tempdoc 598 R1: resolve the capability-derived AUTO marker into a concrete dense decision.
   * When {@code pipeline.dense_auto} is set, the caller deferred the dense leg to the engine
   * (generalizing the RAG {@code retrieveMode="auto"} rule); enable it iff {@code denseServiceable}.
   * Returns the request unchanged when AUTO is not set.
   *
   * <p>Review Fix A: {@code denseServiceable} is computed at the call site as
   * {@code allowQueryEmbeddings (index COMPATIBLE) && embeddingProvider.isAvailable()} — gating on
   * embedder availability too, so AUTO does not request a query-embed that would just fail when the
   * embedder is unloaded (e.g. Online mode). The planner's hybrid-fallback remains the safety net for
   * the residual check-vs-encode race.
   *
   * <p>Package-private (static + pure) for direct unit testing (tempdoc 598 R1 regression).
   */
  static SearchRequest resolveAutoDense(SearchRequest request, boolean denseServiceable) {
    if (!request.hasPipeline() || !request.getPipeline().getDenseAuto()) {
      return request;
    }
    var resolved =
        request.getPipeline().toBuilder().setDenseEnabled(denseServiceable).build();
    return request.toBuilder().setPipeline(resolved).build();
  }

  /** Opens an MDC scope with trace_id and request_id from the gRPC propagated context. */
  private MdcContext openRequestMdc() { // NOPMD - AutoCloseable for logging context side-effect
    SpanContext spanCtx = Span.fromContext(TracingServerInterceptor.currentOtelContext())
        .getSpanContext();
    String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;
    return MdcContext.request(traceId, RequestMetadataInterceptor.currentRequestId());
  }

  @Override
  public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
    awaitModelsReady("search");
    try (var ignored = openRequestMdc()) {
      // Pipeline MDC context from proto fields set by Head (298). pipeline_hash + budget_profile
      // were retired by tempdoc 400 LR2-d (orphan fields per ADR 0014); only pipeline_name
      // remains populated.
      var pipeline = request.hasPipeline() ? request.getPipeline() : null;
      try (var ignored2 = MdcContext.pipeline( // NOPMD
          pipeline != null ? pipeline.getPipelineName() : null)) {
        try {
          var compat = embeddingCompat();
          // Tempdoc 598 R1: resolve the capability-derived AUTO marker into a concrete dense
          // decision before the orchestrator runs, so both SearchInputCapture and SearchPlanner
          // (which independently read pipeline.dense_enabled) see the resolved value. Review Fix A:
          // dense is serviceable only when the index is COMPATIBLE AND the embedder is available.
          boolean denseServiceable = compat.allowed() && embeddingProvider.isAvailable();
          SearchRequest effectiveRequest = resolveAutoDense(request, denseServiceable);
          SearchResponse response =
              searchOrchestrator.execute(effectiveRequest, compat.allowed(), compat.reasonCode());
          responseObserver.onNext(response);
          responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
          metrics.recordSearchFailed();
          log.warn("Invalid search request: {}", e.getMessage());
          responseObserver.onError(
              io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asException());
        } catch (RuntimeException e) {
          metrics.recordSearchFailed();
          log.error("Search failed", e);
          responseObserver.onError(
              io.grpc.Status.INTERNAL
                  .withDescription("Search failed: " + e.getMessage())
                  .asException());
        }
      }
    }
  }


  /**
   * 360: Cross-encoder reranking RPC. Delegates to the Worker's GPU-capable
   * {@link CrossEncoderReranker} instance, or returns skipped if not loaded.
   */
  @Override
  public void rerank(RerankRequest request, StreamObserver<RerankResponse> responseObserver) {
    awaitModelsReady("rerank");
    try (var ignored = openRequestMdc()) {
      CrossEncoderReranker reranker = searchReranker;
      if (reranker == null) {
        responseObserver.onNext(RerankResponse.newBuilder()
            .setSkipped(true)
            .setSkipReason("MODEL_NOT_LOADED")
            .build());
        responseObserver.onCompleted();
        return;
      }

      try {
        List<String> docTexts = request.getDocumentTextsList();
        long deadlineMs = request.getDeadlineMs();
        CrossEncoderReranker.RerankedResult result = reranker.rerank(
            request.getQuery(), docTexts, deadlineMs > 0 ? deadlineMs : 200);

        RerankResponse.Builder resp = RerankResponse.newBuilder()
            .setSkipped(result.skipped())
            .setElapsedMs(result.latencyMs());

        if (result.skipped()) {
          resp.setSkipReason("DEADLINE_EXCEEDED");
        } else {
          for (int idx : result.sortedIndices()) {
            resp.addSortedIndices(idx);
          }
          for (float score : result.scores()) {
            resp.addScores(score);
          }
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
      } catch (RuntimeException e) {
        log.error("Rerank failed", e);
        responseObserver.onError(
            io.grpc.Status.INTERNAL
                .withDescription("Rerank failed: " + e.getMessage())
                .asException());
      }
    }
  }

  @Override
  public void suggest(SuggestRequest request, StreamObserver<SuggestResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
      String queryPrefix = request.getQuery();
      int limit = request.getLimit();

      if (queryPrefix.isBlank()) {
        responseObserver.onNext(SuggestResponse.newBuilder().build());
        responseObserver.onCompleted();
        return;
      }

      // Clamp limit
      if (limit <= 0) {
        limit = 5;
      } else if (limit > 20) {
        limit = 20;
      }

      log.debug("Suggest request: prefix='{}', limit={}", queryPrefix, limit);

      try {
        // Ensure index is refreshed for near-real-time results
        commitOps.maybeRefresh();

        // Get suggestions from the Lucene index
        List<String> suggestions = suggestOps.suggest(queryPrefix, limit);

        // Build response
        SuggestResponse.Builder responseBuilder = SuggestResponse.newBuilder();
        responseBuilder.addAllSuggestions(suggestions);

        log.debug("Suggest completed: {} suggestions for prefix '{}'", suggestions.size(), queryPrefix);

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();

      } catch (RuntimeException e) {
        log.error("Suggest failed", e);
        responseObserver.onError(io.grpc.Status.INTERNAL
            .withDescription("Suggest failed: " + e.getMessage())
            .asException());
      }
    }
  }

  /**
   * Fetches document content by ID.
   *
   * <p>This method allows the Main process to retrieve document content without
   * directly accessing the Lucene index, avoiding MMapDirectory/write.lock conflicts.
   *
   * @param request contains list of document IDs to fetch
   * @param responseObserver observer for the response
   */
  @Override
  public void fetchDocuments(FetchDocumentsRequest request,
                             StreamObserver<FetchDocumentsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    log.debug("FetchDocuments request: {} doc_ids", request.getDocIdsCount());

    try {
      // Ensure index is refreshed for latest data
      commitOps.maybeRefresh();

      FetchDocumentsResponse.Builder response = FetchDocumentsResponse.newBuilder();

      for (String docId : request.getDocIdsList()) {
        // Normalize docId to match indexed format (lowercase on Windows)
        String normalizedDocId = PathNormalizer.normalizePath(docId);

        DocumentContent.Builder doc = DocumentContent.newBuilder()
            .setDocId(docId);

        try {
          String content = documentFieldOps.getDocumentContent(normalizedDocId);
          if (content != null) {
            doc.setContent(ParseUtils.trimToLength(content, MAX_CONTENT_CHARS));
            doc.setFound(true);

            // Add common metadata fields if available
            String title = documentFieldOps.getDocumentField(normalizedDocId, "title");
            if (title != null && !title.isBlank()) {
              doc.putMetadata("title", title);
            }
            String path = documentFieldOps.getDocumentField(normalizedDocId, "path");
            if (path != null && !path.isBlank()) {
              doc.putMetadata("path", path);
            }
            String mime = documentFieldOps.getDocumentField(normalizedDocId, "mime");
            if (mime != null && !mime.isBlank()) {
              doc.putMetadata("mime", mime);
            }
          } else {
            doc.setFound(false);
            doc.setError("Document not found in index");
          }
        } catch (Exception e) {
          log.warn("Failed to fetch document {}", docId, e);
          doc.setFound(false);
          doc.setError(e.getMessage() != null ? e.getMessage() : "Unknown error");
        }

        response.addDocuments(doc.build());
      }

      log.debug("FetchDocuments completed: {} documents", response.getDocumentsCount());
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();

    } catch (RuntimeException e) {
      log.error("FetchDocuments failed", e);
      responseObserver.onError(io.grpc.Status.INTERNAL
          .withDescription("FetchDocuments failed: " + e.getMessage())
          .asException());
    }
    }
  }

  /**
   * Fetches a slice of document content by ID.
   *
   * <p>This endpoint is used for paging through extracted/indexed text (preview and full-coverage summarization)
   * without relying on the fixed-size trimming applied by {@link #fetchDocuments(FetchDocumentsRequest, StreamObserver)}.
   */
  @Override
  public void fetchDocumentSlice(
      FetchDocumentSliceRequest request, StreamObserver<FetchDocumentSliceResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String docId = request.getDocId();
    int offsetChars = Math.max(0, request.getOffsetChars());
    int maxChars = request.getMaxChars() <= 0 ? DEFAULT_SLICE_CHARS : request.getMaxChars();
    maxChars = Math.min(maxChars, MAX_SLICE_CHARS);

    if (docId.isBlank()) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT.withDescription("doc_id is required").asException());
      return;
    }

    // Normalize docId to match indexed format (lowercase on Windows)
    String normalizedDocId = PathNormalizer.normalizePath(docId);

    log.debug("FetchDocumentSlice request: doc_id={}, normalizedDocId={}, offsetChars={}, maxChars={}", docId, normalizedDocId, offsetChars, maxChars);

    try {
      commitOps.maybeRefresh();

      FetchDocumentSliceResponse.Builder response =
          FetchDocumentSliceResponse.newBuilder().setDocId(docId);

      String content = documentFieldOps.getDocumentContent(normalizedDocId);
      if (content == null) {
        response.setFound(false).setError("Document not found in index");
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
        return;
      }

      int totalLen = content.length();
      int start = Math.min(offsetChars, totalLen);
      int end = Math.min(start + maxChars, totalLen);

      String slice = start >= end ? "" : content.substring(start, end);
      boolean truncated = end < totalLen;

      response.setFound(true);
      response.setContent(slice);
      response.setTruncated(truncated);
      response.setNextOffsetChars(end);

      // Add common metadata fields if available
      String title = documentFieldOps.getDocumentField(normalizedDocId, "title");
      if (title != null && !title.isBlank()) {
        response.putMetadata("title", title);
      }
      String path = documentFieldOps.getDocumentField(normalizedDocId, "path");
      if (path != null && !path.isBlank()) {
        response.putMetadata("path", path);
      }
      String mime = documentFieldOps.getDocumentField(normalizedDocId, "mime");
      if (mime != null && !mime.isBlank()) {
        response.putMetadata("mime", mime);
      }
      String extractionMethod =
          documentFieldOps.getDocumentField(normalizedDocId, SchemaFields.EXTRACTION_METHOD);
      if (extractionMethod != null && !extractionMethod.isBlank()) {
        response.putMetadata("extraction_method", extractionMethod);
      }

      // Add VDU-related metadata for provenance tracking
      String vduStatus = documentFieldOps.getDocumentField(normalizedDocId, SchemaFields.VDU_STATUS);
      if (vduStatus != null && !vduStatus.isBlank()) {
        response.putMetadata("vdu_status", vduStatus);
      }
      String vduProcessed = documentFieldOps.getDocumentField(normalizedDocId, SchemaFields.VDU_PROCESSED);
      if (vduProcessed != null && !vduProcessed.isBlank()) {
        response.putMetadata("vdu_processed", vduProcessed);
      }
      String vduPageCount = documentFieldOps.getDocumentField(normalizedDocId, SchemaFields.VDU_PAGE_COUNT);
      if (vduPageCount != null && !vduPageCount.isBlank()) {
        response.putMetadata("vdu_page_count", vduPageCount);
      }
      String vduEnrichment = documentFieldOps.getDocumentField(normalizedDocId, SchemaFields.VDU_ENRICHMENT);
      if (vduEnrichment != null && !vduEnrichment.isBlank()) {
        response.putMetadata("vdu_enrichment", vduEnrichment);
      }
      String visualEvidence =
          documentFieldOps.getDocumentField(normalizedDocId, SchemaFields.VISUAL_EXTRACTION_EVIDENCE);
      if (visualEvidence != null && !visualEvidence.isBlank()) {
        response.putMetadata("visual_extraction_evidence", visualEvidence);
      }

      responseObserver.onNext(response.build());
      responseObserver.onCompleted();

    } catch (RuntimeException e) {
      log.error("FetchDocumentSlice failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL.withDescription("FetchDocumentSlice failed: " + e.getMessage()).asException());
    }
    }
  }

  /**
   * Retrieves relevant context for Q&A using RAG (chunk search) or full document search.
   *
   * <p>First searches for relevant chunks using BM25 on the chunk_content field.
   * If no chunks are found, falls back to full document content search.
   *
   * @param request contains the question, document IDs, and top-K parameter
   * @param responseObserver observer for the response
   */
  @Override
  public void retrieveContext(RetrieveContextRequest request,
                              StreamObserver<RetrieveContextResponse> responseObserver) {
    awaitModelsReady("retrieveContext");
    try (var ignored = openRequestMdc()) {
      String question = request.getQuestion();
      List<String> docIds = request.getDocIdsList();
      int topK = request.getTopK() <= 0 ? 5 : Math.min(request.getTopK(), 20);
      int maxContextTokens = Math.max(0, request.getMaxContextTokens());

      log.debug("RetrieveContext request: question='{}', docIds={}, topK={}, maxTokens={}, "
          + "entityPersons={}, pathPrefix='{}', autoEntityExtract={}",
          question, docIds.size(), topK, maxContextTokens,
          request.getEntityPersonsCount(), request.getPathPrefix(),
          request.getAutoEntityExtract());

      // Allow empty doc_ids for open retrieval (filters or unscoped search).
      // Only reject if question is blank.
      if (question.isBlank()) {
        responseObserver.onNext(RetrieveContextResponse.newBuilder()
            .setContext("")
            .setUsedChunks(false)
            .setChunksFound(0)
            .setRetrievalMode("")
            .setRetrievalModeReason("EMPTY_REQUEST")
            .setContextTruncated(false)
            .setQuality(io.justsearch.ipc.QualitySignals.getDefaultInstance())
            .build());
        responseObserver.onCompleted();
        return;
      }

      try {
        RetrieveContextResponse response = ragContextOps.executeRetrieval(
            request, new HashSet<>(docIds), topK, maxContextTokens,
            embeddingCompat().allowed());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } catch (RuntimeException e) {
        log.error("RetrieveContext failed", e);
        responseObserver.onError(io.grpc.Status.INTERNAL
            .withDescription("RetrieveContext failed: " + e.getMessage())
            .asException());
      }
    }
  }

  // ==================== Post-hoc Citation Matching ====================

  @Override
  public void matchCitations(
      io.justsearch.ipc.MatchCitationsRequest request,
      StreamObserver<io.justsearch.ipc.MatchCitationsResponse> responseObserver) {
    awaitModelsReady("matchCitations");
    try (var ignored = openRequestMdc()) {
      try {
        double threshold = request.getSimilarityThreshold() > 0
            ? request.getSimilarityThreshold()
            : CitationMatchOps.DEFAULT_SIMILARITY_THRESHOLD;
        responseObserver.onNext(citationMatchOps.execute(
            request.getAnswerText(),
            request.getChunkDocIdsList(),
            request.getChunkIndicesList(),
            threshold));
        responseObserver.onCompleted();
      } catch (RuntimeException e) {
        log.error("MatchCitations failed", e);
        responseObserver.onError(
            io.grpc.Status.INTERNAL
                .withDescription("MatchCitations failed: " + e.getMessage())
                .asException());
      }
    }
  }

  // ==================== Folder Browse ====================

  @Override
  public void listFolders(
      ListFoldersRequest request, StreamObserver<ListFoldersResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String parentPath = request.getParentPath();
    if (parentPath.isBlank()) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription("parent_path is required")
              .asException());
      return;
    }

    try {
      commitOps.maybeRefresh();
      FolderBrowseResult result =
          folderBrowseEngine.enumerateFolders(parentPath, request.getMaxFolders());

      ListFoldersResponse.Builder response = ListFoldersResponse.newBuilder();
      for (FolderInfo folder : result.folders()) {
        response.addFolders(
            FolderEntry.newBuilder()
                .setPath(folder.path())
                .setName(folder.name())
                .setFileCount(folder.fileCount())
                .setTotalSizeBytes(folder.totalSizeBytes())
                .setLastIndexedAt(folder.lastIndexedAt())
                .build());
      }
      response.setTookMs(result.tookMs());
      response.setTruncated(result.truncated());

      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException e) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription(e.getMessage())
              .asException());
    } catch (RuntimeException e) {
      log.error("ListFolders failed for path={}", parentPath, e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("ListFolders failed: " + e.getMessage())
              .asException());
    }
    }
  }

  @Override
  public void listFolderFiles(
      ListFolderFilesRequest request, StreamObserver<ListFolderFilesResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    String folderPath = request.getFolderPath();
    if (folderPath.isBlank()) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription("folder_path is required")
              .asException());
      return;
    }

    try {
      commitOps.maybeRefresh();
      FolderFilesResult result =
          folderBrowseEngine.listFolderFiles(
              folderPath, request.getLimit(), new HashSet<>(request.getProjectionList()));

      ListFolderFilesResponse.Builder response = ListFolderFilesResponse.newBuilder();
      for (SearchHit hit : result.files()) {
        response.addFiles(
            FolderFileEntry.newBuilder()
                .setDocId(hit.docId())
                .putAllFields(hit.fields())
                .build());
      }
      response.setTotalCount(result.totalCount());
      response.setTookMs(result.tookMs());

      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException e) {
      responseObserver.onError(
          io.grpc.Status.INVALID_ARGUMENT
              .withDescription(e.getMessage())
              .asException());
    } catch (RuntimeException e) {
      log.error("ListFolderFiles failed for path={}", folderPath, e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("ListFolderFiles failed: " + e.getMessage())
              .asException());
    }
    }
  }

  @Override
  public void listAllDocumentIds(
      ListAllDocumentIdsRequest request,
      StreamObserver<ListAllDocumentIdsResponse> responseObserver) {
    try (var ignored = openRequestMdc()) {
    try {
      commitOps.maybeRefresh();
      ListAllDocumentIdsResult result =
          folderBrowseEngine.listAllDocumentIds(request.getOffset(), request.getLimit());

      ListAllDocumentIdsResponse response =
          ListAllDocumentIdsResponse.newBuilder()
              .addAllDocIds(result.docIds())
              .setTotalCount(result.totalCount())
              .setTookMs(result.tookMs())
              .build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      log.error("ListAllDocumentIds failed", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("ListAllDocumentIds failed: " + e.getMessage())
              .asException());
    }
    }
  }

}
