package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class GrpcSearchServiceReasonCodeContractTest {

  private static final Set<String> EMBEDDING_COMPAT_REASON_CODES =
      Set.of(
          "INITIALIZING",
          "NO_EMBEDDING_MODEL",
          "NEW_INDEX_NO_FINGERPRINT",
          "LEGACY_INDEX_NO_FINGERPRINT",
          "FINGERPRINT_MATCH",
          "FINGERPRINT_MISMATCH",
          "REBUILD_IN_PROGRESS",
          "REBUILD_COMPLETED",
          // Tempdoc 517: 25th member — fall-through for unrecognised compat strings
          // via SearchReasonCode.fromCompatString(...). Surfaces when the boundary
          // controller hands back a string the enum doesn't know.
          "EMBEDDING_COMPATIBILITY_UNKNOWN");

  private static final Set<String> SEARCH_REASON_CODES =
      Set.of(
          "UNKNOWN",
          "EMBEDDING_COMPATIBILITY_BLOCKED",
          "NO_EMBEDDING_SERVICE",
          "EMBEDDING_GENERATION_FAILED",
          "EMBEDDING_EXCEPTION");

  private static final Set<String> CHUNK_MERGE_REASON_CODES =
      Set.of(
          "APPLIED",
          "SKIPPED_DISABLED",
          "SKIPPED_EMPTY_BASE_RESULTS",
          "SKIPPED_PAGINATED",
          "SKIPPED_QUERY_SYNTAX",
          "SKIPPED_SORT_NOT_RELEVANCE",
          "SKIPPED_NO_CHUNK_DOCS",
          // Tempdoc 517 follow-up: missing from the prior allowlist. Emitted at
          // SearchOrchestrator.java:817 in the legacy implementation when the
          // corpus has chunks but is short (corpusSupportsChunks=false). My
          // refactor preserves this emission via the typed
          // SearchReasonCode.SKIPPED_SHORT_CORPUS member; production hits this
          // path whenever the indexed corpus is small.
          "SKIPPED_SHORT_CORPUS",
          "SKIPPED_UNKNOWN",
          "SKIPPED_VECTOR_BLOCKED",
          "SKIPPED_EMPTY_QUERY");

  private static final Set<String> RAG_RETRIEVAL_MODE_REASONS =
      Set.of(
          "EMPTY_REQUEST",
          "NO_CHUNKS_FOUND",
          "CHUNKS_BELOW_THRESHOLD",
          "BM25_CONFIGURED",
          "HYBRID_AVAILABLE",
          "NO_EMBEDDING_SERVICE",
          "EMBEDDING_UNAVAILABLE",
          "EMBEDDING_EMPTY",
          "EMBEDDING_GENERATION_FAILED",
          "CHUNK_VECTOR_COVERAGE_INCOMPLETE");

  @Test
  void searchResponsesOnlyEmitAllowlistedReasonCodes() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      // Vector blocked path
      {
        var service = new GrpcSearchService(lifecycle);
        EmbeddingCompatibilityController controller = new EmbeddingCompatibilityController(Map::of, () -> 1L);
        forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
        service.setEmbeddingCompatController(controller);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("ignored")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_VECTOR)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getVectorBlocked());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertTrue(
            union(EMBEDDING_COMPAT_REASON_CODES, SEARCH_REASON_CODES)
                .contains(response.getSearchTrace().getDegradation().getVectorBlockedReason()),
            () -> "Unexpected vectorBlockedReason=" + response.getSearchTrace().getDegradation().getVectorBlockedReason());
      }

      // Hybrid blocked by compatibility controller -> fallback to TEXT with compat reason
      {
        var service = new GrpcSearchService(lifecycle);
        EmbeddingCompatibilityController controller = new EmbeddingCompatibilityController(Map::of, () -> 1L);
        forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
        service.setEmbeddingCompatController(controller);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertTrue(
            union(EMBEDDING_COMPAT_REASON_CODES, SEARCH_REASON_CODES)
                .contains(response.getSearchTrace().getDegradation().getHybridFallbackReason()),
            () -> "Unexpected hybridFallbackReason=" + response.getSearchTrace().getDegradation().getHybridFallbackReason());
      }

      // Hybrid with no embedding service -> fallback to TEXT with NO_EMBEDDING_SERVICE
      {
        var service = new GrpcSearchService(lifecycle);
        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertEquals("NO_EMBEDDING_SERVICE", response.getSearchTrace().getDegradation().getHybridFallbackReason());
      }

      // Hybrid with embedding service "available" but embed returns empty -> fallback to TEXT with EMBEDDING_GENERATION_FAILED
      {
        EmbeddingService embeddingService = embeddingServiceAvailableButNullEmbed();
        var service = new GrpcSearchService(lifecycle, embeddingService);
        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setMode(SearchMode.SEARCH_MODE_HYBRID)
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(
            CHUNK_MERGE_REASON_CODES.contains(TraceStageAccess.chunkMergeReason(response)),
            () -> "Unexpected chunkMergeReason=" + TraceStageAccess.chunkMergeReason(response));
        assertEquals("EMBEDDING_GENERATION_FAILED", response.getSearchTrace().getDegradation().getHybridFallbackReason());
        embeddingService.close();
      }
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  void retrieveContextResponsesOnlyEmitAllowlistedReasonCodes() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    String prevMode = System.getProperty("rag.retrieve.mode");
    try (RunningRuntime lifecycle = newLifecycleEmpty()) {
      // EMPTY_REQUEST short-circuits before touching the index.
      {
        var service = new GrpcSearchService(lifecycle);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("EMPTY_REQUEST", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
      }
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("rag.retrieve.mode", prevMode);
    }
  }

  @Test
  void retrieveContextChunkSearchReasonsAreStable() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    String prevMode = System.getProperty("rag.retrieve.mode");
    try (RunningRuntime lifecycle = newLifecycleWithChunk("doc-1", "Hello world", "Hello chunk")) {
      // BM25_CONFIGURED (explicit)
      {
        System.setProperty("rag.retrieve.mode", "bm25");
        refreshResolvedConfig(lifecycle);
        var service = new GrpcSearchService(lifecycle);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("BM25_CONFIGURED", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
      }

      // NO_EMBEDDING_SERVICE (hybrid configured but no embedding service)
      {
        System.setProperty("rag.retrieve.mode", "hybrid");
        refreshResolvedConfig(lifecycle);
        var service = new GrpcSearchService(lifecycle);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("NO_EMBEDDING_SERVICE", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
      }

      // EMBEDDING_UNAVAILABLE (embedding service present but not available)
      {
        System.setProperty("rag.retrieve.mode", "auto");
        refreshResolvedConfig(lifecycle);
        EmbeddingService embeddingService = new EmbeddingService(Path.of("does-not-exist.gguf"), EmbeddingConfig.DISABLED);
        var service = new GrpcSearchService(lifecycle, embeddingService);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("EMBEDDING_UNAVAILABLE", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
        embeddingService.close();
      }

      // EMBEDDING_EMPTY (embedding service "available" but embed yields empty)
      {
        System.setProperty("rag.retrieve.mode", "auto");
        refreshResolvedConfig(lifecycle);
        EmbeddingService embeddingService = embeddingServiceAvailableButNullEmbed();
        var service = new GrpcSearchService(lifecycle, embeddingService);
        RetrieveContextResponse response =
            invokeRetrieveContext(
                service,
                RetrieveContextRequest.newBuilder()
                    .setQuestion("Hello")
                    .addDocIds("doc-1")
                    .setTopK(5)
                    .build());
        assertEquals("EMBEDDING_EMPTY", response.getRetrievalModeReason());
        assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
        embeddingService.close();
      }
    } finally {
      restoreProperty("justsearch.config", prevConfig);
      restoreProperty("rag.retrieve.mode", prevMode);
    }
  }

  @Test
  void retrieveContextFallbackReasonNoChunksFoundIsStable() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      var service = new GrpcSearchService(lifecycle);
      RetrieveContextResponse response =
          invokeRetrieveContext(
              service,
              RetrieveContextRequest.newBuilder()
                  .setQuestion("Hello")
                  .addDocIds("doc-1")
                  .setTopK(5)
                  .build());
      assertEquals("NO_CHUNKS_FOUND", response.getRetrievalModeReason());
      assertTrue(RAG_RETRIEVAL_MODE_REASONS.contains(response.getRetrievalModeReason()));
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    java.util.HashSet<String> out = new java.util.HashSet<>();
    out.addAll(a);
    out.addAll(b);
    return java.util.Collections.unmodifiableSet(out);
  }

  private static SearchResponse invokeSearch(GrpcSearchService service, SearchRequest request) {
    AtomicReference<SearchResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    service.search(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(SearchResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            errorRef.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNull(errorRef.get(), () -> "search() errored: " + errorRef.get());
    SearchResponse response = responseRef.get();
    assertNotNull(response);
    return response;
  }

  private static RetrieveContextResponse invokeRetrieveContext(
      GrpcSearchService service, RetrieveContextRequest request) {
    AtomicReference<RetrieveContextResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    service.retrieveContext(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(RetrieveContextResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            errorRef.set(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertNull(errorRef.get(), () -> "retrieveContext() errored: " + errorRef.get());
    RetrieveContextResponse response = responseRef.get();
    assertNotNull(response);
    return response;
  }

  private static RunningRuntime newLifecycleEmpty() throws Exception {
    return newLifecycleWithCatalog(FieldCatalogDef.forChunkTesting(4));
  }

  private static RunningRuntime newLifecycleWithOneDoc(String docId, String content) throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    RunningRuntime lifecycle = newLifecycleWithCatalog(catalog);
    var runtime = lifecycle;
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.PATH, docId,
                SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static RunningRuntime newLifecycleWithChunk(String parentDocId, String parentContent, String chunkContent)
      throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    RunningRuntime lifecycle = newLifecycleWithCatalog(catalog);
    var runtime = lifecycle;

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, parentDocId,
                SchemaFields.DOC_UID, parentDocId + "#0",
                SchemaFields.PATH, parentDocId,
                SchemaFields.CONTENT, parentContent)));

    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, "chunk-1");
    fields.put(SchemaFields.DOC_UID, "chunk-1#0");
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.PARENT_DOC_ID, parentDocId);
    fields.put(SchemaFields.CHUNK_INDEX, "0");
    fields.put(SchemaFields.CHUNK_TOTAL, "1");
    fields.put(SchemaFields.CHUNK_CONTENT, chunkContent);
    fields.put(SchemaFields.CHUNK_START_CHAR, "0");
    fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(Math.max(0, chunkContent.length())));
    fields.put(SchemaFields.PATH, parentDocId);
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static RunningRuntime newLifecycleWithCatalog(FieldCatalogDef catalog) throws Exception {
    Path base = Files.createTempDirectory("justsearch-reason-code-contract-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: reasoncode\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void forceEmbeddingCompatState(
      EmbeddingCompatibilityController controller, State state, String reasonCode) throws Exception {
    Field stateField = EmbeddingCompatibilityController.class.getDeclaredField("state");
    stateField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<State> stateRef = (AtomicReference<State>) stateField.get(controller);
    stateRef.set(state);

    Field reasonField = EmbeddingCompatibilityController.class.getDeclaredField("reasonCode");
    reasonField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<String> reasonRef = (AtomicReference<String>) reasonField.get(controller);
    reasonRef.set(reasonCode);
  }

  private static EmbeddingService embeddingServiceAvailableButNullEmbed() throws Exception {
    EmbeddingService svc = new EmbeddingService(Path.of("dummy.gguf"), EmbeddingConfig.DISABLED);

    Field initializedField = EmbeddingService.class.getDeclaredField("initialized");
    initializedField.setAccessible(true);
    AtomicBoolean initialized = (AtomicBoolean) initializedField.get(svc);
    initialized.set(true);

    Field availableField = EmbeddingService.class.getDeclaredField("available");
    availableField.setAccessible(true);
    availableField.setBoolean(svc, true);

    // backend remains null; embed() returns null, exercising degradation paths without loading llama.cpp.
    return svc;
  }

  /** Re-resolve config from current system properties so sysprop changes take effect. */
  private static void refreshResolvedConfig(RunningRuntime lifecycle) {
    try {
      // Tempdoc 406 Gap A: resolvedConfig now lives directly on RuntimeSession
      // (RuntimeContext was deleted; field is reachable via session.resolvedConfig).
      java.lang.reflect.Field sessionField = RunningRuntime.class.getDeclaredField("session");
      sessionField.setAccessible(true);
      Object session = sessionField.get(lifecycle);
      java.lang.reflect.Field rcField = session.getClass().getDeclaredField("resolvedConfig");
      rcField.setAccessible(true);

      var builder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
      builder.contributeEnvRegistry();
      io.justsearch.configuration.JustSearchConfigurationLoader.loadYamlRoot()
          .ifPresent(builder::contributeYaml);
      rcField.set(session, builder.build());
    } catch (Exception e) {
      throw new RuntimeException("Failed to refresh resolvedConfig", e);
    }
  }
}
