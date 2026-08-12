package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.ChunkRef;
import io.justsearch.ipc.ContextFormat;
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
    @DisplayName("serves unchunked docs via the PRIMARY path, not fallback (tempdoc 749 union leg)")
    void servesUnchunkedDocViaPrimaryPath() throws Exception {
      String parentDocId = "d:/docs/simple.txt";

      // Index only a parent document (no chunks) — the sub-2000-char shape that used to be
      // invisible to IS_CHUNK:true retrieval and silently fell back (tempdoc 749).
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

      // Tempdoc 749 option B: the doc-level union leg synthesizes a whole-doc chunk in the
      // PRIMARY candidate set — this must no longer take the FULLTEXT_FALLBACK path.
      assertTrue(response.getUsedChunks(), "Unchunked doc must be served via synthesized chunk");
      assertTrue(response.getChunksCount() > 0, "Should have synthesized chunk metadata");
      assertNotEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "Chunkless docs are primary-path citizens since the 749 union leg");
      var chunk = response.getChunks(0);
      assertEquals(parentDocId, chunk.getParentDocId());
      assertEquals(0, chunk.getChunkIndex(), "Whole-doc synthetic chunk is index 0");
      assertEquals(1, chunk.getChunkTotal(), "Whole-doc synthetic chunk is total 1");
      assertTrue(chunk.getEndChar() > chunk.getStartChar(), "Synthetic chunk has a real span");
      assertTrue(response.getContext().contains("quantum") ||
                 response.getContext().contains("Quantum"),
          "Context should contain the unchunked doc's content");
    }

    @Test
    @DisplayName("FULLTEXT_FALLBACK still fires when neither chunks nor union match")
    void fallbackStillReachableWhenNothingMatches() throws Exception {
      String parentDocId = "d:/docs/unrelated.txt";

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "This document discusses quantum computing and qubits.",
          SchemaFields.MIME, "text/plain")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // A question sharing no terms with the doc: BM25 chunk search AND the doc-level union
      // both come up empty, so the fallback remains the last resort.
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("zebra migration seasons")
          .addDocIds(parentDocId)
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      assertEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "Nothing-matches queries still reach the fallback safety net");
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
  @DisplayName("Doc-level union leg (tempdoc 749 option B)")
  class UnionLeg {

    @Test
    @DisplayName("chunked parent is never double-surfaced by the union; only its real chunks cite")
    void chunkedParentNotDoubleSurfaced() throws Exception {
      // (a) A LONG parent (>2000 chars, above CHUNK_THRESHOLD_CHARS) that owns 2 real chunk docs.
      String longParent = "d:/docs/photosynthesis-long.md";
      String longContent =
          "Photosynthesis in plants converts sunlight into chemical energy. ".repeat(40);
      assertTrue(longContent.length() > 2000, "Long parent must exceed the chunking threshold");
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, longParent,
          SchemaFields.DOC_UID, longParent + "#0",
          SchemaFields.PATH, longParent,
          SchemaFields.CONTENT, longContent,
          SchemaFields.MIME, "text/markdown")));

      final String longChunk0 =
          "Photosynthesis converts sunlight into chemical energy inside plant cells.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:long-000",
          SchemaFields.DOC_UID, "chunk:long-000#0",
          SchemaFields.PATH, longParent,
          SchemaFields.PARENT_DOC_ID, longParent,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "2",
          SchemaFields.CHUNK_CONTENT, longChunk0,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(longChunk0.length()))));

      final String longChunk1 =
          "The photosynthesis process relies on chlorophyll in plants to capture sunlight.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:long-001",
          SchemaFields.DOC_UID, "chunk:long-001#0",
          SchemaFields.PATH, longParent,
          SchemaFields.PARENT_DOC_ID, longParent,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "1",
          SchemaFields.CHUNK_TOTAL, "2",
          SchemaFields.CHUNK_CONTENT, longChunk1,
          SchemaFields.CHUNK_START_CHAR, "1000",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(1000 + longChunk1.length()))));

      // (b) A SHORT chunkless parent (<2000 chars) that also matches the query — the union path
      // is the only way it reaches retrieval.
      String shortParent = "d:/docs/photosynthesis-short.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, shortParent,
          SchemaFields.DOC_UID, shortParent + "#0",
          SchemaFields.PATH, shortParent,
          SchemaFields.CONTENT,
          "Photosynthesis is how plants make food from sunlight and water.",
          SchemaFields.MIME, "text/markdown")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("photosynthesis sunlight plants")
          .addDocIds(longParent)
          .addDocIds(shortParent)
          .setTopK(10)
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Both parents should retrieve via the chunk pipeline");

      int shortCitations = 0;
      int longCitations = 0;
      for (int i = 0; i < response.getChunksCount(); i++) {
        var chunk = response.getChunks(i);
        if (shortParent.equals(chunk.getParentDocId())) {
          shortCitations++;
          // The short parent's only citation is the synthesized whole-doc chunk.
          assertEquals(1, chunk.getChunkTotal(),
              "Chunkless short parent must surface as a single whole-doc chunk (total=1)");
          assertEquals(0, chunk.getChunkIndex(),
              "Whole-doc synthetic chunk is index 0");
        } else if (longParent.equals(chunk.getParentDocId())) {
          longCitations++;
          // The union must NOT synthesize a whole-doc chunk for the chunked long parent: every
          // long-parent citation comes from a seeded real chunk (chunkTotal==2). A synthesized
          // whole-doc chunk for a >2000-char parent would carry a different total (its split
          // count, or 1), so this assertion fails the instant double-surfacing occurs.
          assertEquals(2, chunk.getChunkTotal(),
              "Long parent citations must be its real chunks only (total=2), never a synthesized "
                  + "whole-doc chunk. chunkIndex=" + chunk.getChunkIndex());
        }
      }

      assertEquals(1, shortCitations,
          "Chunkless short parent must be cited exactly once (no duplicate whole-doc chunk)");
      assertTrue(longCitations >= 1,
          "Long parent must still be cited via its real chunks");
    }

    @Test
    @DisplayName("unscoped union (empty docIds) serves a chunkless parent via the primary path")
    void unscopedUnionServesChunklessParent() throws Exception {
      String parent = "d:/docs/tardigrade.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent,
          SchemaFields.DOC_UID, parent + "#0",
          SchemaFields.PATH, parent,
          SchemaFields.CONTENT,
          "Tardigrades are microscopic animals that survive extreme conditions.",
          SchemaFields.MIME, "text/markdown")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // No docIds — the unscoped searchFullDocs leg of the union is what must find the parent.
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("tardigrades extreme conditions")
          .setTopK(5)
          .build();

      var response = callRetrieveContext(request);

      // The harness has no embedding service, so this is the BM25 doc-level union leg.
      assertTrue(response.getUsedChunks(), "Unscoped chunkless parent must be served, not skipped");
      assertNotEquals("FULLTEXT_FALLBACK", response.getRetrievalMode(),
          "Unscoped union is a primary-path result, not the fallback safety net");
      assertTrue(response.getChunksCount() > 0, "Should synthesize a citation");
      assertEquals(parent, response.getChunks(0).getParentDocId(),
          "The synthesized citation references the chunkless parent");
    }

    @Test
    @DisplayName("excluded parent is not re-injected by the union leg")
    void excludedParentNotReinjected() throws Exception {
      String parent = "d:/docs/hidden.md";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent,
          SchemaFields.DOC_UID, parent + "#0",
          SchemaFields.PATH, parent,
          SchemaFields.CONTENT,
          "Volcanic eruptions release ash and lava from the earth's mantle.",
          SchemaFields.MIME, "text/markdown")));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Scoped to the parent, but the user has hidden it via an excluded-chunk ref. The union
      // leg must honour the exclusion and NOT resurrect the doc as a synthesized whole-doc chunk.
      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("volcanic eruptions ash lava")
          .addDocIds(parent)
          .setTopK(5)
          .addExcludedChunks(ChunkRef.newBuilder().setParentDocId(parent).setChunkIndex(0))
          .build();

      var response = callRetrieveContext(request);

      for (int i = 0; i < response.getChunksCount(); i++) {
        assertNotEquals(parent, response.getChunks(i).getParentDocId(),
            "An excluded parent must never re-surface via the union leg");
      }
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

  @Nested
  @DisplayName("Tempdoc 725 W2b: contextFormat is a genuine no-op end-to-end (LABELED always renders)")
  class ContextFormatIsIgnored {

    /**
     * Ground-truth regression for the W2b format wrong-gate fix: {@code RagContextOps} never
     * reads {@code request.getContextFormat()} (grep-verified: no call site in the module), and
     * {@link io.justsearch.indexing.rag.ContextBudgeter} has only one rendering — {@code "[From:
     * label]\n" + content} (LABELED) — with no XML/PLAIN branch at all. This test drives the REAL
     * production path (this class's {@code GrpcSearchService}, backed by a real Lucene runtime,
     * exactly as {@link ChunkVsFallback#returnsChunkContextWhenChunksExist()} above) and proves
     * that requesting {@code CONTEXT_FORMAT_XML} on the wire still yields LABELED-shaped output —
     * grounding {@code McpToolSurface.callAnswer}'s decision to request LABELED explicitly
     * (tempdoc 725 orphan #5) in the actual renderer, not just in a mocked call site.
     */
    @Test
    @DisplayName("requesting CONTEXT_FORMAT_XML still renders LABELED \"[From: ...]\" sections")
    void xmlFormatRequestStillRendersLabeled() throws Exception {
      String parentDocId = "d:/docs/report.pdf";

      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, "This is the full document about machine learning and neural networks.",
          SchemaFields.MIME, "application/pdf")));

      final int chunk0Start = 0;
      final String chunk0Text = "Machine learning is a subset of artificial intelligence.";
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "chunk:xml-001",
          SchemaFields.DOC_UID, "chunk:xml-001#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, chunk0Text,
          SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk0Start),
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk0Start + chunk0Text.length()))));

      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
          .setQuestion("What is machine learning?")
          .addDocIds(parentDocId)
          .setTopK(5)
          .setContextFormat(ContextFormat.CONTEXT_FORMAT_XML)
          .build();

      var response = callRetrieveContext(request);

      assertTrue(response.getUsedChunks(), "Should use chunks when they exist");
      assertTrue(
          response.getContext().contains("[From: "),
          "LABELED is the only format ContextBudgeter renders, regardless of what was requested: "
              + response.getContext());
      assertFalse(
          response.getContext().contains("<source "),
          "No XML rendering exists yet — the request must not silently produce XML-shaped output: "
              + response.getContext());
    }
  }

  /**
   * Tempdoc 821 §3-C2 — end-to-end collection scoping over a real index. The unit-level routing
   * decision is pinned in {@code RagContextOpsCollectionScopeTest}; this pins the observable
   * retrieval behavior the decision produces.
   */
  @Nested
  @DisplayName("Collection scoping (821 §3-C2)")
  class CollectionScoping {

    private static final String NORMAL_DOC = "d:/docs/quarterly-budget.md";
    private static final String AGENT_DOC = "d:/agent/session-42.md";

    /** Indexes a parent + one chunk, both tagged with {@code collection} when non-null. */
    private void indexDocWithChunk(String parentDocId, String collection, String chunkText)
        throws Exception {
      Map<String, Object> parent = new java.util.HashMap<>(Map.of(
          SchemaFields.DOC_ID, parentDocId,
          SchemaFields.DOC_UID, parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.CONTENT, chunkText,
          SchemaFields.MIME, "text/markdown"));
      Map<String, Object> chunk = new java.util.HashMap<>(Map.of(
          SchemaFields.DOC_ID, "chunk:" + parentDocId,
          SchemaFields.DOC_UID, "chunk:" + parentDocId + "#0",
          SchemaFields.PATH, parentDocId,
          SchemaFields.PARENT_DOC_ID, parentDocId,
          SchemaFields.IS_CHUNK, "true",
          SchemaFields.CHUNK_INDEX, "0",
          SchemaFields.CHUNK_TOTAL, "1",
          SchemaFields.CHUNK_CONTENT, chunkText,
          SchemaFields.CHUNK_START_CHAR, "0",
          SchemaFields.CHUNK_END_CHAR, String.valueOf(chunkText.length())));
      if (collection != null) {
        // ChunkDocumentWriter propagates the parent's collection onto chunks (811 item 3); the
        // fixture mirrors that, since the scope binds on the chunk branch.
        parent.put(SchemaFields.COLLECTION, collection);
        chunk.put(SchemaFields.COLLECTION, collection);
      }
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(parent));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    }

    @BeforeEach
    void indexBothCollections() throws Exception {
      indexDocWithChunk(
          NORMAL_DOC, null, "Retention policy NORMALMARKER for the quarterly budget review.");
      indexDocWithChunk(
          AGENT_DOC, "agent-history", "Retention policy AGENTMARKER discussed in the agent run.");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();
    }

    @Test
    @DisplayName("absent collection keeps the default scope: agent-history is excluded")
    void absentCollectionExcludesAgentHistory() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder().setQuestion("retention policy").setTopK(5).build());

      assertTrue(response.getContext().contains("NORMALMARKER"),
          "the untagged document must still be retrieved: " + response.getContext());
      assertFalse(response.getContext().contains("AGENTMARKER"),
          "the 811 D-1 default exclusion must still bind when no scope is given: "
              + response.getContext());
    }

    @Test
    @DisplayName("an explicit agent-history scope is a positive include, not a match-nothing")
    void explicitAgentHistoryScopeIncludesOnlyAgentHistory() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("retention policy")
              .setTopK(5)
              .addCollection("agent-history")
              .build());

      assertTrue(response.getContext().contains("AGENTMARKER"),
          "an explicit scope must INCLUDE that collection: " + response.getContext());
      assertFalse(response.getContext().contains("NORMALMARKER"),
          "an explicit scope must exclude everything outside it: " + response.getContext());
    }

    @Test
    @DisplayName("a collection-only request keeps the chunk path, not the parent pre-filter")
    void collectionOnlyRequestStaysOnTheChunkPath() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("retention policy")
              .setTopK(5)
              .addCollection("agent-history")
              .build());

      // The parent pre-filter leg answers with buildEmptyFilterResponse (usedChunks=false,
      // chunksFound=0) whenever it runs and resolves nothing; chunk-path retrieval reports real
      // chunk counts. This assertion is what fails RED if collection is ever added to
      // hasDocLevelFilters and the routing silently changes.
      assertTrue(response.getUsedChunks(), "collection-only retrieval must use chunks");
      assertTrue(response.getChunksFound() > 0,
          "chunks_found must report real chunk-path counts, not the empty-filter shape");
    }

    @Test
    @DisplayName("an unknown collection scopes to nothing without falling back to everything")
    void unknownCollectionMatchesNothing() {
      var response = callRetrieveContext(
          RetrieveContextRequest.newBuilder()
              .setQuestion("retention policy")
              .setTopK(5)
              .addCollection("no-such-collection")
              .build());

      assertFalse(response.getContext().contains("AGENTMARKER"),
          "an unmatched scope must not leak agent-history: " + response.getContext());
      assertFalse(response.getContext().contains("NORMALMARKER"),
          "an unmatched scope must not silently widen back to the default scope: "
              + response.getContext());
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
