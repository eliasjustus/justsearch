package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.ContextSection;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RAGContext} (slice 491 C3). */
final class RAGContextTest {

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.rag-context", RAGContext.ID);
  }

  @Test
  @DisplayName("Missing question → terminalError NO_QUESTION before any fetch")
  void missingQuestion() {
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs);
    InjectorResult r = injector.inject(stubCtx(Map.of("docIds", List.of("a"))));
    assertTrue(r.terminalError().isPresent());
    assertEquals("NO_QUESTION", r.terminalError().get().payload().get("errorCode"));
    assertEquals(0, docs.retrieveCalls, "retrieve must not be called when question missing");
  }

  @Test
  @DisplayName("Empty docIds → open-retrieval via retrieveContext (NO_CONTENT when index is empty)")
  void emptyDocIdsOpenRetrieval() {
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs);
    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));
    // Open-retrieval attempts retrieveContext; the default stub returns empty context,
    // and since there's no batch fallback for empty docIds, it terminates with NO_CONTENT.
    assertTrue(r.terminalError().isPresent());
    assertEquals("NO_CONTENT", r.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("Empty docIds + successful open-retrieval → happy path (chunks found via pre-search)")
  void emptyDocIdsSuccessfulOpenRetrieval() {
    var docs = new TrackingDocs();
    docs.retrieveResult =
        new ContextResult("found text", 2, 3, 1, List.of(), "BM25", "pre_search", false, List.of());
    var injector = new RAGContext(docs);
    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));
    assertFalse(r.terminalError().isPresent());
    assertEquals(1, r.messages().size());
    String content = (String) r.messages().get(0).get("content");
    assertTrue(content.contains("found text"), "open-retrieval content injected");
    assertTrue(content.contains("what?"), "question appended");
  }

  @Test
  @DisplayName("Happy path: chunked retrieval → emits rag.meta, builds user message, stashes attributes")
  void happyPathChunkedRetrieval() {
    var citation = new ContextCitation("doc-1", 0, 1, 0, 100, 0.9f, "excerpt", 0, 0, "", 0);
    var section = new ContextSection("[doc-1]", "the chunk text", false, 0, 0);
    var ctxResult =
        new ContextResult(
            "the chunk text",
            1, // chunksUsed
            1, // chunksFound
            1,
            List.of(citation),
            "BM25",
            "ok",
            false,
            List.of(section));
    var docs = new StubDocs(ctxResult, Map.of());
    var injector = new RAGContext(docs);
    var ctx = stubCtx(Map.of("question", "what is this?", "docIds", List.of("doc-1")));

    InjectorResult r = injector.inject(ctx);

    assertFalse(r.terminalError().isPresent(), "happy path must not be terminal");
    assertEquals(1, r.messages().size(), "single user message injected");
    String userContent = (String) r.messages().get(0).get("content");
    assertTrue(userContent.contains("Documents:"));
    assertTrue(userContent.contains("the chunk text"));
    assertTrue(userContent.contains("Question: what is this?"));

    // rag.meta + rag.citations events (slice 493: citations emitted at retrieval time)
    assertEquals(2, r.events().size());
    SseEvent meta = r.events().get(0);
    assertEquals("rag.meta", meta.name());
    assertEquals("BM25", meta.payload().get("retrieval_mode"));
    assertEquals(1, meta.payload().get("chunks_used"));
    assertEquals(1, meta.payload().get("chunks_found"));
    // Slice 493 Phase A: QualitySignals exposed in rag.meta
    assertTrue(meta.payload().containsKey("best_chunk_score"), "rag.meta must include best_chunk_score");
    assertTrue(meta.payload().containsKey("retrieval_coverage"), "rag.meta must include retrieval_coverage");
    assertTrue(meta.payload().containsKey("score_gap"), "rag.meta must include score_gap");
    assertTrue(meta.payload().containsKey("chunks_considered"), "rag.meta must include chunks_considered");
    SseEvent citations = r.events().get(1);
    assertEquals("rag.citations", citations.name());
    @SuppressWarnings("unchecked")
    var citList = (java.util.List<java.util.Map<String, Object>>) citations.payload().get("citations");
    assertEquals(1, citList.size(), "one citation from the single chunk");
    assertEquals("doc-1", citList.get(0).get("parentDocId"));

    // attributes stashed for downstream consumers
    assertEquals(true, ctx.attributes().get(RAGContext.ATTR_USED_RAG));
    assertEquals(1, ctx.attributes().get(RAGContext.ATTR_CHUNKS_USED));
    assertEquals(1, ctx.attributes().get(RAGContext.ATTR_CHUNKS_FOUND));
    assertEquals(1, ctx.attributes().get(RAGContext.ATTR_FILE_COUNT));
    assertEquals(List.of("doc-1"), ctx.attributes().get(RAGContext.ATTR_DOC_IDS));
    @SuppressWarnings("unchecked")
    List<ContextCitation> stashedCitations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    assertEquals(1, stashedCitations.size());
  }

  @Test
  @DisplayName("Retrieval returns empty chunks → fall back to fetchBatch")
  void fallbackToBatchFetch() {
    var emptyRetrieval =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "no_hits", false, List.of());
    var batch =
        Map.of("doc-1", new DocumentRecord("doc-1", "the fallback content", Map.of()));
    var docs = new StubDocs(emptyRetrieval, batch);
    var injector = new RAGContext(docs);
    var ctx = stubCtx(Map.of("question", "q", "docIds", List.of("doc-1")));

    InjectorResult r = injector.inject(ctx);

    assertFalse(r.terminalError().isPresent());
    assertEquals(1, r.messages().size());
    String content = (String) r.messages().get(0).get("content");
    assertTrue(content.contains("the fallback content"), "fallback content used");
    assertEquals(false, ctx.attributes().get(RAGContext.ATTR_USED_RAG));
    assertEquals(0, ctx.attributes().get(RAGContext.ATTR_CHUNKS_USED));
    @SuppressWarnings("unchecked")
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    assertTrue(citations.isEmpty(), "no citations when fallback path used");
  }

  @Test
  @DisplayName("FALLBACK_FAILED retrieval mode → terminalError immediately (no batch retry)")
  void fallbackFailedTerminal() {
    var failed =
        new ContextResult("", 0, 0, 0, List.of(), "FALLBACK_FAILED", "all_failed", false, List.of());
    var docs = new TrackingDocs();
    docs.retrieveResult = failed;
    var injector = new RAGContext(docs);

    InjectorResult r =
        injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("doc-1"))));

    assertTrue(r.terminalError().isPresent());
    assertEquals("FETCH_FAILED", r.terminalError().get().payload().get("errorCode"));
    assertEquals(0, docs.fetchBatchCalls, "FALLBACK_FAILED must not retry fetchBatch");
  }

  @Test
  @DisplayName("Both retrieval and fallback empty → terminalError NO_CONTENT")
  void retrievalAndFallbackEmpty() {
    var emptyRetrieval =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "no_hits", false, List.of());
    var docs = new StubDocs(emptyRetrieval, Map.of()); // no batch content either
    var injector = new RAGContext(docs);

    InjectorResult r =
        injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("doc-1"))));

    assertTrue(r.terminalError().isPresent());
    assertEquals("NO_CONTENT", r.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("RetrieveContextWithMeta throwing falls through to batch fetch")
  void retrievalExceptionFallsBackToBatch() {
    var batch =
        Map.of("doc-1", new DocumentRecord("doc-1", "the fallback content", Map.of()));
    var docs = new ThrowingRetrieveDocs(batch);
    var injector = new RAGContext(docs);

    InjectorResult r =
        injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("doc-1"))));

    assertFalse(r.terminalError().isPresent());
    assertNotNull(r.messages());
    String content = (String) r.messages().get(0).get("content");
    assertTrue(content.contains("the fallback content"));
  }

  @Test
  @DisplayName("topK respected when supplied in body")
  void topKFromBody() {
    var docs = new TrackingDocs();
    docs.retrieveResult =
        new ContextResult("text", 1, 1, 1, List.of(), "BM25", "ok", false, List.of());
    var injector = new RAGContext(docs);
    injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("a"), "topK", 12)));
    assertEquals(12, docs.lastTopK);
  }

  @Test
  @DisplayName("Default topK = 5 when missing")
  void topKDefault() {
    var docs = new TrackingDocs();
    docs.retrieveResult =
        new ContextResult("text", 1, 1, 1, List.of(), "BM25", "ok", false, List.of());
    var injector = new RAGContext(docs);
    injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("a"))));
    assertEquals(RAGContext.DEFAULT_TOP_K, docs.lastTopK);
  }

  private static ConversationContext stubCtx(Map<String, Object> body) {
    return new ConversationContext() {
      private final Map<String, Object> a = new HashMap<>();
      private final Map<String, Object> b = new LinkedHashMap<>(body);

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return b;
      }

      @Override
      public Map<String, Object> attributes() {
        return a;
      }
    };
  }

  /** Stub that succeeds retrieval with the supplied result, and falls back to a fixed batch map. */
  private static final class StubDocs implements DocumentService {
    private final ContextResult retrieval;
    private final Map<String, DocumentRecord> batch;

    StubDocs(ContextResult retrieval, Map<String, DocumentRecord> batch) {
      this.retrieval = retrieval;
      this.batch = batch;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId) {
      return CompletableFuture.completedFuture(batch.get(docId));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds) {
      Map<String, DocumentRecord> out = new LinkedHashMap<>();
      for (String id : docIds) {
        DocumentRecord r = batch.get(id);
        if (r != null) out.put(id, r);
      }
      return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens) {
      return CompletableFuture.completedFuture(retrieval);
    }
  }

  /** Tracks calls + returns a configurable result. */
  private static final class TrackingDocs implements DocumentService {
    int retrieveCalls = 0;
    int fetchBatchCalls = 0;
    int lastTopK = -1;
    ContextResult retrieveResult =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of());

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds) {
      fetchBatchCalls++;
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens) {
      retrieveCalls++;
      lastTopK = topK;
      return CompletableFuture.completedFuture(retrieveResult);
    }
  }

  private static final class ThrowingRetrieveDocs implements DocumentService {
    private final Map<String, DocumentRecord> batch;

    ThrowingRetrieveDocs(Map<String, DocumentRecord> batch) {
      this.batch = batch;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId) {
      return CompletableFuture.completedFuture(batch.get(docId));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds) {
      Map<String, DocumentRecord> out = new LinkedHashMap<>();
      for (String id : docIds) {
        DocumentRecord r = batch.get(id);
        if (r != null) out.put(id, r);
      }
      return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens) {
      return CompletableFuture.failedFuture(new RuntimeException("retrieval down"));
    }
  }
}
