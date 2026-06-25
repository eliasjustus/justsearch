package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.ipc.ChunkRef;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for GrpcSearchService.retrieveContext (P1.4).
 *
 * <p>Verifies chunk vs fallback behavior, diversification, and metadata fields.
 */
@DisplayName("GrpcSearchService RetrieveContext (P1.4)")
class GrpcSearchServiceRetrieveContextTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private GrpcSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    // Clear any existing config property to ensure clean test isolation
    System.clearProperty("justsearch.config");

    // Use chunk-aware testing catalog with explicit index path
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();

    service = new GrpcSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @Nested
  @DisplayName("Chunk vs fallback behavior")
  class ChunkVsFallback {

    @Test
    @DisplayName("returns chunk context when chunks exist")
    void returnsChunkContextWhenChunksExist() throws Exception {
      String parentDocId = "d:/docs/report.pdf";

      // Index a parent document
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "This is the full document about machine learning and neural networks.",
          SchemaFields.MIME, "application/pdf")));

      // Index chunk documents for the same parent
      final int chunk0Start = 0;
      final String chunk0Text = "Machine learning is a subset of artificial intelligence.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:abc-001",
          SchemaFields.DOC_UID, "chunk:abc-001#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "3",
          SchemaFields.CHUNK_CONTENT, chunk0Text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk0Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk0Start + chunk0Text.length()))));

      final int chunk1Start = 200;
      final String chunk1Text = "Neural networks are inspired by the human brain structure.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:abc-002",
          SchemaFields.DOC_UID, "chunk:abc-002#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "1",
          SchemaFields.CHUNK_TOTAL, "3",
          SchemaFields.CHUNK_CONTENT, chunk1Text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk1Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk1Start + chunk1Text.length()))));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Request context about machine learning
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("What is machine learning?")
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Should use chunks when they exist");
      assertTrue(response.getChunksFound() > 0, "Should find at least one chunk");
      assertTrue(response.getChunksCount() > 0, "Should return structured chunks for click-to-verify citations");
      assertEquals(parentDocId, response.getChunks(0).getParentDocId(), "Chunk should reference the parent doc id");
      assertTrue(response.getChunks(0).getEndChar() > response.getChunks(0).getStartChar(), "Chunk must have a non-empty span");
      assertTrue(response.getContext().contains("machine learning") ||
                 response.getContext().contains("Machine learning"),
          "Context should contain relevant chunk content");
    }

    @Test
    @DisplayName("falls back to full document when no chunks exist")
    void fallsBackToFullDocWhenNoChunks() throws Exception {
      String parentDocId = "d:/docs/simple.txt";

      // Index only a parent document (no chunks)
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "This document discusses quantum computing and qubits.",
          SchemaFields.MIME, "text/plain")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Request context about quantum computing
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("What is quantum computing?")
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      // RAG-005: Fallback now produces virtual chunks for citation support
      assertTrue(response.getUsedChunks(), "Should use virtual chunks even for unchunked docs");
      assertTrue(response.getChunksCount() > 0, "Should have virtual chunk metadata");
      assertEquals("FULLTEXT_FALLBACK", response.getRetrievalMode());
      assertTrue(response.getContext().contains("quantum") ||
                 response.getContext().contains("Quantum"),
          "Context should contain full doc content when no chunks");
    }

    @Test
    @DisplayName("returns empty context when question is blank")
    void returnsEmptyContextWhenQuestionBlank() throws Exception {
      String parentDocId = "d:/docs/test.pdf";

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "Some content here.")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("")  // Blank question
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertEquals("", response.getContext(), "Should return empty context for blank question");
      assertFalse(response.getUsedChunks());
      assertEquals(0, response.getChunksFound());
    }

    @Test
    @DisplayName("returns empty context when docIds is empty")
    void returnsEmptyContextWhenNoDocIds() throws Exception {
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("Some question")
          // No docIds added
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertEquals("", response.getContext(), "Should return empty context when no docIds");
      assertFalse(response.getUsedChunks());
      assertEquals(0, response.getChunksFound());
    }
  }

  @Nested
  @DisplayName("Diversification behavior")
  class Diversification {

    @Test
    @DisplayName("diversifies chunks from different positions (begin/middle/end)")
    void diversifiesChunksFromDifferentPositions() throws Exception {
      String parentDocId = "d:/docs/longdoc.pdf";

      // Index parent
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "Long document about data science and analytics.")));

      // Index chunks from beginning, middle, and end (10 chunks total)
      for (int i = 0; i < 10; i++) {
        String position = i < 3 ? "beginning" : (i < 7 ? "middle" : "end");
        lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
            SchemaFields.DOC_ID, "chunk:div-" + i,
            SchemaFields.DOC_UID, "chunk:div-" + i + "#0",
            SchemaFields.PATH, parentDocId,
            SchemaFields.PARENT_DOC_ID, parentDocId,
            SchemaFields.IS_CHUNK, "true",
            SchemaFields.CHUNK_INDEX, String.valueOf(i),
            SchemaFields.CHUNK_TOTAL, "10",
            SchemaFields.CHUNK_CONTENT, "Chunk " + i + " from " + position + " discusses data science concepts.")));
      }

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("data science concepts")
          .addDocIds(parentDocId)
          .setTopK(5)  // Request 5 chunks
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Should use chunks");
      assertTrue(response.getChunksFound() >= 3, "Should find multiple chunks");

      // Verify diversification by checking context contains chunks from different positions
      String ctx = response.getContext();
      // At least some chunks from beginning and end should be present
      boolean hasBeginning = ctx.contains("beginning");
      boolean hasMiddle = ctx.contains("middle");
      boolean hasEnd = ctx.contains("end");

      int positionsCovered = (hasBeginning ? 1 : 0) + (hasMiddle ? 1 : 0) + (hasEnd ? 1 : 0);
      assertTrue(positionsCovered >= 2,
          "Diversification should cover at least 2 different positions (begin/middle/end). " +
          "Found: beginning=" + hasBeginning + ", middle=" + hasMiddle + ", end=" + hasEnd);
    }
  }

  // ========== Helper methods ==========

  // ==================== Metadata Filter Tests (362) ====================

  @Nested
  @DisplayName("Metadata filtering in retrieve-context (362)")
  class MetadataFiltering {

    @Test
    @DisplayName("metadata filter scopes to matching parent docs")
    void metadataFilterScopesToMatchingParents() throws Exception {
      // Index two parent docs with different meta_source values
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "d:/docs/verge-article.md",
          SchemaFields.DOC_UID, "verge#0",
          SchemaFields.PATH, "d:/docs/verge-article.md",
          SchemaFields.CONTENT, "Tech news from The Verge about AI advancements.",
          SchemaFields.MIME, "text/x-web-markdown",
          SchemaFields.META_SOURCE, "the verge")));

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "d:/docs/tc-article.md",
          SchemaFields.DOC_UID, "tc#0",
          SchemaFields.PATH, "d:/docs/tc-article.md",
          SchemaFields.CONTENT, "Tech news from TechCrunch about AI advancements.",
          SchemaFields.MIME, "text/x-web-markdown",
          SchemaFields.META_SOURCE, "techcrunch")));

      // Index chunks for both parents
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:verge-001",
          SchemaFields.DOC_UID, "chunk:verge-001#0",
          SchemaFields.PATH, "d:/docs/verge-article.md",
          SchemaFields.PARENT_DOC_ID, "d:/docs/verge-article.md",
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, "The Verge reports on AI advancements in search.",
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, "50")));

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:tc-001",
          SchemaFields.DOC_UID, "chunk:tc-001#0",
          SchemaFields.PATH, "d:/docs/tc-article.md",
          SchemaFields.PARENT_DOC_ID, "d:/docs/tc-article.md",
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, "TechCrunch reports on AI advancements in search.",
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, "50")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Filter by meta_source = "the verge" — should only return Verge chunks
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("AI advancements")
          .setTopK(5)
          .addMetaSource("the verge")
          .build();

      var response = callRetrieveContext(request);

      assertFalse(response.getContext().isBlank(), "Should have context from The Verge doc");
      assertTrue(response.getContext().contains("Verge"),
          "Context should be from The Verge, not TechCrunch");
      assertFalse(response.getContext().contains("TechCrunch"),
          "Context should NOT include TechCrunch content");
    }

    @Test
    @DisplayName("no matching parents returns empty context")
    void noMatchingParentsReturnsEmpty() throws Exception {
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "d:/docs/article.md",
          SchemaFields.DOC_UID, "article#0",
          SchemaFields.PATH, "d:/docs/article.md",
          SchemaFields.CONTENT, "Some article content.",
          SchemaFields.MIME, "text/x-web-markdown",
          SchemaFields.META_SOURCE, "the verge")));

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:art-001",
          SchemaFields.DOC_UID, "chunk:art-001#0",
          SchemaFields.PATH, "d:/docs/article.md",
          SchemaFields.PARENT_DOC_ID, "d:/docs/article.md",
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, "Article about technology.",
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, "25")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Filter by meta_source that doesn't exist
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("technology")
          .setTopK(5)
          .addMetaSource("nonexistent-source")
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getContext().isEmpty(),
          "Should return empty context when no parents match the filter");
    }
  }

  @Nested
  @DisplayName("Retrieved-source exclusion (610 §J.3)")
  class SourceExclusion {

    private void indexChunk(String parentDocId, int idx, String text) throws Exception {
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:excl-" + idx,
          SchemaFields.DOC_UID, "chunk:excl-" + idx + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, String.valueOf(idx),
          SchemaFields.CHUNK_TOTAL, "3",
          SchemaFields.CHUNK_CONTENT, text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(idx * 200),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(idx * 200 + text.length()))));
    }

    @Test
    @DisplayName("excluded chunk is dropped from retrieval; the others remain")
    void excludedChunkIsAbsent() throws Exception {
      String parentDocId = "d:/docs/reliability.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "Reliability budget document.",
          SchemaFields.MIME, "text/markdown")));
      indexChunk(parentDocId, 0, "Reliability budget overview for the quarter.");
      indexChunk(parentDocId, 1, "Reliability SECRETMARKER chunk that the user will hide.");
      indexChunk(parentDocId, 2, "Reliability conclusion and summary.");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Baseline: without exclusion, chunk 1's marker is present.
      RetrieveContextRequest baseline = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5).build();
      var baseResp = callRetrieveContext(baseline);
      assertTrue(baseResp.getContext().contains("SECRETMARKER"),
          "Baseline (no exclusion) should include chunk 1's content");

      // With chunk 1 excluded: its marker is gone, the other two remain.
      RetrieveContextRequest excluded = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5)
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parentDocId).setChunkIndex(1))
          .build();
      var exclResp = callRetrieveContext(excluded);
      assertFalse(exclResp.getContext().contains("SECRETMARKER"),
          "Excluded chunk 1 must NOT appear in the assembled context");
      assertTrue(exclResp.getContext().contains("overview"),
          "Chunk 0 (not excluded) should remain");
      assertTrue(exclResp.getContext().contains("conclusion"),
          "Chunk 2 (not excluded) should remain");
      // No excluded chunk surfaces as a citation either.
      for (int i = 0; i < exclResp.getChunksCount(); i++) {
        assertNotEquals(1, exclResp.getChunks(i).getChunkIndex(),
            "Excluded chunk 1 must not be a returned citation");
      }
    }

    @Test
    @DisplayName("empty exclusion list is a no-op")
    void emptyExclusionIsNoOp() throws Exception {
      String parentDocId = "d:/docs/noop.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "Reliability budget document.",
          SchemaFields.MIME, "text/markdown")));
      indexChunk(parentDocId, 0, "Reliability KEEPME overview content.");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5).build();
      var resp = callRetrieveContext(request);
      assertTrue(resp.getContext().contains("KEEPME"),
          "With no exclusions the chunk is retrieved as before");
    }

    @Test
    @DisplayName("excluding ALL chunks of a scoped doc does not re-inject it via the whole-doc fallback")
    void allChunksExcludedNoFallbackReinjection() throws Exception {
      String parentDocId = "d:/docs/secret.md";
      // The full document carries the marker too — the fallback fetches full-doc CONTENT, so this is
      // exactly what must NOT come back when every chunk is hidden.
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "Reliability SECRETMARKER full document content.",
          SchemaFields.MIME, "text/markdown")));
      indexChunk(parentDocId, 0, "Reliability SECRETMARKER chunk zero.");
      indexChunk(parentDocId, 1, "Reliability SECRETMARKER chunk one.");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("reliability").addDocIds(parentDocId).setTopK(5)
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parentDocId).setChunkIndex(0))
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parentDocId).setChunkIndex(1))
          .build();
      var resp = callRetrieveContext(request);
      assertFalse(resp.getContext().contains("SECRETMARKER"),
          "A doc with all chunks hidden must NOT reappear via the whole-document fallback");
    }
  }

  // ==================== Helper ====================

  private RetrieveContextResponse callRetrieveContext(RetrieveContextRequest request) {
    AtomicReference<RetrieveContextResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    service.retrieveContext(request, new StreamObserver<>() {
      @Override
      public void onNext(RetrieveContextResponse value) {
        responseRef.set(value);
      }

      @Override
      public void onError(Throwable t) {
        errorRef.set(t);
      }

      @Override
      public void onCompleted() {
        // done
      }
    });

    if (errorRef.get() != null) {
      fail("RetrieveContext failed: " + errorRef.get().getMessage());
    }

    assertNotNull(responseRef.get(), "Response should not be null");
    return responseRef.get();
  }
}
