package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.IndexSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for RAG (Retrieval-Augmented Generation) search functionality.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link ChunkSearchOps#searchFullDocsForDocs} finds relevant full documents</li>
 *   <li>BM25 ranking works correctly for full document content</li>
 *   <li>Document ID filtering works (only searches within specified docs)</li>
 * </ul>
 *
 * <p>Note: Chunk search tests ({@code searchChunksForDocs}) require the full SSOT
 * field catalog with chunk-specific fields and are covered by integration tests
 * (HttpAiQualityTest) rather than unit tests.
 */
@DisplayName("RAG Document Retrieval")
class ChunkSearchIntegrationTest {

  private RunningRuntime runtime;
  private Path tempDir;
  private String prevConfig;

  @BeforeEach
  void setup() throws Exception {
    prevConfig = System.getProperty("justsearch.config");
    tempDir = Files.createTempDirectory("justsearch-rag-test-");

    // Create config
    String yaml = "app:\n  data_dir: " + tempDir.toString().replace("\\", "\\\\") + "\n" +
        "index:\n  collections:\n    - name: ragtest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-rag-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    // Use chunk-aware testing catalog with 4-dim vectors
    runtime = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().open();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (prevConfig == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs finds relevant full documents by BM25")
  void searchFullDocsForDocsFindsByBm25() throws Exception {
    // Index parent documents with content
    indexDoc("doc-1", "The quick brown fox jumps over the lazy dog");
    indexDoc("doc-2", "A fast red fox runs through the forest");
    indexDoc("doc-3", "Cats and dogs are popular pets");

    commitAndRefresh();

    // Search for "fox" within doc-1 and doc-2
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("fox", Set.of("doc-1", "doc-2"), 5);

    assertNotNull(result);
    assertEquals(2, result.hits().size(), "Should find 2 docs containing 'fox'");

    // Both results should be from our filtered set
    for (var hit : result.hits()) {
      assertTrue(hit.docId().equals("doc-1") || hit.docId().equals("doc-2"),
          "Result should be from filtered doc set. Got: " + hit.docId());
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs filters by document IDs")
  void searchFullDocsFiltersToSpecifiedDocs() throws Exception {
    // Index docs all containing "machine"
    indexDoc("doc-1", "Machine learning basics");
    indexDoc("doc-2", "Machine learning advanced");
    indexDoc("doc-3", "Machine vision systems");

    commitAndRefresh();

    // Search only within doc-1 and doc-2 (exclude doc-3)
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("machine", Set.of("doc-1", "doc-2"), 5);

    assertNotNull(result);
    assertEquals(2, result.hits().size(), "Should find exactly 2 docs");

    // Verify doc-3 is not in results
    for (var hit : result.hits()) {
      assertFalse("doc-3".equals(hit.docId()), "Should not include doc-3");
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs returns empty when query doesn't match")
  void searchFullDocsReturnsEmptyWhenNoMatch() throws Exception {
    indexDoc("doc-1", "Machine learning basics");

    commitAndRefresh();

    // Search for non-existent term
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("quantum", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertTrue(result.hits().isEmpty(), "Should return empty when query doesn't match");
  }

  @Test
  @DisplayName("searchFullDocsForDocs handles multiple doc IDs")
  void searchFullDocsHandlesMultipleDocIds() throws Exception {
    // Index 5 docs, all containing "python"
    for (int i = 1; i <= 5; i++) {
      indexDoc("doc-" + i, "Python programming tutorial part " + i);
    }

    commitAndRefresh();

    // Search for "python" in 3 of the 5 docs
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("python", Set.of("doc-1", "doc-3", "doc-5"), 10);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should find exactly 3 docs");

    // Verify only our filtered docs are returned
    Set<String> expectedIds = Set.of("doc-1", "doc-3", "doc-5");
    for (var hit : result.hits()) {
      assertTrue(expectedIds.contains(hit.docId()),
          "Unexpected doc ID: " + hit.docId());
    }
  }

  @Test
  @DisplayName("searchFullDocsForDocs respects limit parameter")
  void searchFullDocsRespectsLimit() throws Exception {
    // Index 10 docs all matching "data"
    for (int i = 1; i <= 10; i++) {
      indexDoc("doc-" + i, "Data science and data analysis chapter " + i);
    }

    commitAndRefresh();

    // Create set of all doc IDs
    Set<String> allDocIds = Set.of("doc-1", "doc-2", "doc-3", "doc-4", "doc-5",
        "doc-6", "doc-7", "doc-8", "doc-9", "doc-10");

    // Search with limit of 3
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("data", allDocIds, 3);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should return exactly 3 results (limited)");
  }

  @Test
  @DisplayName("searchFullDocsForDocs supports docId selections larger than IndexSearcher.getMaxClauseCount()")
  void searchFullDocsSupportsLargeDocIdSelections() throws Exception {
    indexDoc("doc-1", "The quick brown fox jumps over the lazy dog");
    commitAndRefresh();

    Set<String> docIds = largeDocIdSelectionIncluding("doc-1");
    var result = runtime.chunkSearchOps().searchFullDocsForDocs("fox", docIds, 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should return at least one result");
    assertTrue(result.hits().stream().anyMatch(h -> "doc-1".equals(h.docId())),
        "Expected doc-1 to be returned from large docId selection");
  }

  @Test
  @DisplayName("searchChunksForDocs supports docId selections larger than IndexSearcher.getMaxClauseCount()")
  void searchChunksSupportsLargeDocIdSelections() throws Exception {
    indexDoc("doc-1", "Parent document about machine learning");
    indexChunk("doc-1", 0, 2, "Neural networks overview");
    indexChunk("doc-1", 1, 2, "Deep learning fundamentals");
    commitAndRefresh();

    Set<String> docIds = largeDocIdSelectionIncluding("doc-1");
    var result = runtime.chunkSearchOps().searchChunksForDocs("neural", docIds, 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should find chunks matching 'neural'");
    for (var hit : result.hits()) {
      assertEquals("doc-1", hit.fields().get(SchemaFields.PARENT_DOC_ID),
          "All chunks should be filtered to parent doc-1");
    }
  }

  // ========== Chunk Search Tests (D1 / P1.4) ==========

  @Test
  @DisplayName("searchChunksForDocs finds chunks by BM25 on chunk_content")
  void searchChunksForDocsFindsByBm25() throws Exception {
    // Index a parent doc and its chunks
    indexDoc("doc-1", "Parent document about machine learning");
    indexChunk("doc-1", 0, 3, "Introduction to neural networks");
    indexChunk("doc-1", 1, 3, "Deep learning fundamentals");
    indexChunk("doc-1", 2, 3, "Conclusion and future work");

    commitAndRefresh();

    // Search for "neural" within doc-1
    var result = runtime.chunkSearchOps().searchChunksForDocs("neural", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should find chunks matching 'neural'");

    // The matching chunk should have chunk_content containing "neural"
    boolean foundNeuralChunk = result.hits().stream()
        .anyMatch(hit -> {
          String content = hit.fields().get(SchemaFields.CHUNK_CONTENT);
          return content != null && content.toLowerCase(Locale.ROOT).contains("neural");
        });
    assertTrue(foundNeuralChunk, "Should find chunk containing 'neural'");
  }

  @Test
  @DisplayName("searchChunksForDocs filters by parent document IDs")
  void searchChunksFiltersToSpecifiedParents() throws Exception {
    // Index two parent docs with chunks
    indexDoc("doc-1", "Document about cats");
    indexChunk("doc-1", 0, 2, "Cats are wonderful pets");
    indexChunk("doc-1", 1, 2, "Cats need regular care");

    indexDoc("doc-2", "Document about dogs");
    indexChunk("doc-2", 0, 2, "Dogs are loyal companions");
    indexChunk("doc-2", 1, 2, "Dogs need exercise");

    commitAndRefresh();

    // Search for "pets" only within doc-1
    var result = runtime.chunkSearchOps().searchChunksForDocs("pets", Set.of("doc-1"), 5);

    assertNotNull(result);

    // All results should be from doc-1
    for (var hit : result.hits()) {
      String parentId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      assertEquals("doc-1", parentId, "All chunks should be from doc-1");
    }
  }

  @Test
  @DisplayName("searchChunksForDocs returns empty when no chunks match")
  void searchChunksReturnsEmptyWhenNoMatch() throws Exception {
    indexDoc("doc-1", "Parent about programming");
    indexChunk("doc-1", 0, 2, "Java programming basics");
    indexChunk("doc-1", 1, 2, "Python programming basics");

    commitAndRefresh();

    // Search for non-existent term
    var result = runtime.chunkSearchOps().searchChunksForDocs("quantum", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertTrue(result.hits().isEmpty(), "Should return empty when no chunks match");
  }

  @Test
  @DisplayName("searchChunksForDocs returns chunk_index and chunk_total in fields")
  void searchChunksReturnsPositionMetadata() throws Exception {
    indexDoc("doc-1", "Parent about algorithms");
    indexChunk("doc-1", 0, 5, "Sorting algorithms overview");
    indexChunk("doc-1", 1, 5, "Bubble sort implementation");
    indexChunk("doc-1", 2, 5, "Quick sort implementation");
    indexChunk("doc-1", 3, 5, "Merge sort implementation");
    indexChunk("doc-1", 4, 5, "Performance comparison of sorting");

    commitAndRefresh();

    // Search for "sort"
    var result = runtime.chunkSearchOps().searchChunksForDocs("sort", Set.of("doc-1"), 5);

    assertNotNull(result);
    assertFalse(result.hits().isEmpty(), "Should find chunks about sorting");

    // Verify position metadata is included
    for (var hit : result.hits()) {
      String chunkIndex = hit.fields().get(SchemaFields.CHUNK_INDEX);
      String chunkTotal = hit.fields().get(SchemaFields.CHUNK_TOTAL);
      assertNotNull(chunkIndex, "chunk_index should be present");
      assertNotNull(chunkTotal, "chunk_total should be present");
      assertEquals("5", chunkTotal, "chunk_total should be 5");
    }
  }

  private static Set<String> largeDocIdSelectionIncluding(String requiredDocId) {
    int targetSize = IndexSearcher.getMaxClauseCount() + 1;
    Set<String> out = new HashSet<>(targetSize);
    out.add(requiredDocId);
    int i = 0;
    while (out.size() < targetSize) {
      out.add("dummy-doc-id-" + i++);
    }
    return out;
  }

  @Test
  @DisplayName("searchChunksForDocs respects limit parameter")
  void searchChunksRespectsLimit() throws Exception {
    indexDoc("doc-1", "Parent about data structures");
    for (int i = 0; i < 10; i++) {
      indexChunk("doc-1", i, 10, "Data structure chapter " + i);
    }

    commitAndRefresh();

    // Search with limit of 3
    var result = runtime.chunkSearchOps().searchChunksForDocs("data", Set.of("doc-1"), 3);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should return exactly 3 chunks (limited)");
  }

  // ========== Phase 6: Chunk-Level Hybrid (chunk vectors) ==========

  @Test
  @DisplayName("searchChunksHybrid (Phase 6) fuses by chunk doc_id (RRF id match)")
  void searchChunksHybridPhase6FusesByChunkDocId() throws Exception {
    indexDoc("doc-1", "Parent content");

    float[] v = new float[] {1f, 0f, 0f, 0f};
    String chunkId = indexChunkWithVector("doc-1", 0, 1, "apple", v);

    commitAndRefresh();

    var result = runtime.chunkSearchOps().searchChunksHybrid("apple", v, Set.of("doc-1"), 1, true, null);

    assertNotNull(result);
    assertEquals(1, result.hits().size(), "Should return exactly 1 fused hit");
    assertEquals(chunkId, result.hits().get(0).docId(), "Fused hit should use chunk doc_id (not parent doc_id)");
  }

  @Test
  @DisplayName("searchChunksHybrid (Phase 6) caps vector-only chunks on low-signal queries")
  void searchChunksHybridPhase6CapsVectorOnlyOnLowSignal() throws Exception {
    indexDoc("doc-1", "Parent content");

    // Index many chunks that do NOT match the BM25 query, but do have vectors.
    for (int i = 0; i < 10; i++) {
      indexChunkWithVector("doc-1", i, 10, "unrelated content " + i, new float[] {0f, 0f, 0f, i});
    }

    commitAndRefresh();

    // Use a far-away query vector to force low vector similarity (low-signal gating).
    float[] far = new float[] {1000f, 1000f, 1000f, 1000f};
    int limit = 10;
    int cap = runtime.resolvedConfig().hybridSearch().vectorOnlyCapLowSignal();

    var result = runtime.chunkSearchOps().searchChunksHybrid("nonmatching-query", far, Set.of("doc-1"), limit, true, null);

    assertNotNull(result);
    assertEquals(Math.min(cap, limit), result.hits().size(),
        "Low-signal gating should cap vector-only chunks");
  }

  // ========== Chunk Lifecycle Tests (P0.1/P0.2 regression) ==========

  @Test
  @DisplayName("deleteChunksForParentDocId removes only chunk docs for that parent")
  void deleteChunksForParentRemovesOnlyMatchingChunks() throws Exception {
    // Index a parent doc and its chunks
    indexDoc("parent-a", "Parent A content");
    indexChunk("parent-a", 0, 3, "Chunk 0 content");
    indexChunk("parent-a", 1, 3, "Chunk 1 content");
    indexChunk("parent-a", 2, 3, "Chunk 2 content");

    // Index another parent doc and its chunks
    indexDoc("parent-b", "Parent B content");
    indexChunk("parent-b", 0, 2, "B chunk 0 content");
    indexChunk("parent-b", 1, 2, "B chunk 1 content");

    commitAndRefresh();

    // Verify all docs are indexed
    assertEquals(7, countAllDocs(), "Should have 2 parents + 5 chunks");

    // Delete chunks for parent-a only
    runtime.indexingCoordinator().deleteChunksForParentDocId("parent-a");
    commitAndRefresh();

    // Parent-a should still exist
    assertTrue(docExists("parent-a"), "Parent-a should not be deleted");

    // Chunks for parent-a should be gone
    assertFalse(docExists("parent-a#chunk_0"), "parent-a chunk 0 should be deleted");
    assertFalse(docExists("parent-a#chunk_1"), "parent-a chunk 1 should be deleted");
    assertFalse(docExists("parent-a#chunk_2"), "parent-a chunk 2 should be deleted");

    // Parent-b and its chunks should still exist
    assertTrue(docExists("parent-b"), "Parent-b should not be deleted");
    assertTrue(docExists("parent-b#chunk_0"), "parent-b chunk 0 should not be deleted");
    assertTrue(docExists("parent-b#chunk_1"), "parent-b chunk 1 should not be deleted");
  }

  @Test
  @DisplayName("deleteChunksForParentDocId works with opaque chunk IDs (P0.8)")
  void deleteChunksWorksWithOpaqueIds() throws Exception {
    // Index parent and chunks using new opaque UUID format
    indexDoc("parent-opaque", "Parent with opaque chunks");
    indexChunkOpaque("parent-opaque", 0, 3, "Opaque chunk 0");
    indexChunkOpaque("parent-opaque", 1, 3, "Opaque chunk 1");
    indexChunkOpaque("parent-opaque", 2, 3, "Opaque chunk 2");

    commitAndRefresh();

    // Verify all docs indexed
    assertEquals(4, countAllDocs(), "Should have 1 parent + 3 opaque chunks");

    // Delete chunks using field-based method (should work regardless of doc_id format)
    runtime.indexingCoordinator().deleteChunksForParentDocId("parent-opaque");
    commitAndRefresh();

    // Parent should remain
    assertTrue(docExists("parent-opaque"), "Parent should not be deleted");

    // Should only have parent now (all chunks deleted via parent_doc_id field match)
    assertEquals(1, countAllDocs(), "Should have only parent remaining after chunk deletion");
  }

  @Test
  @DisplayName("Chunk shrink scenario: old chunks deleted when new count is smaller")
  void chunkShrinkDeletesOldChunks() throws Exception {
    // Initial index with 5 chunks
    indexDoc("doc-1", "Original content");
    for (int i = 0; i < 5; i++) {
      indexChunk("doc-1", i, 5, "Original chunk " + i);
    }
    commitAndRefresh();
    assertEquals(6, countAllDocs(), "Should have 1 parent + 5 chunks");

    // Simulate reindex with fewer chunks:
    // 1. Delete old chunks
    runtime.indexingCoordinator().deleteChunksForParentDocId("doc-1");
    // 2. Index new chunks (only 2 this time)
    indexChunk("doc-1", 0, 2, "New chunk 0");
    indexChunk("doc-1", 1, 2, "New chunk 1");
    commitAndRefresh();

    // Should have parent + 2 new chunks only
    assertEquals(3, countAllDocs(), "Should have 1 parent + 2 new chunks");

    // Old chunks should not exist
    assertFalse(docExists("doc-1#chunk_2"), "Old chunk 2 should be gone");
    assertFalse(docExists("doc-1#chunk_3"), "Old chunk 3 should be gone");
    assertFalse(docExists("doc-1#chunk_4"), "Old chunk 4 should be gone");

    // New chunks should exist
    assertTrue(docExists("doc-1#chunk_0"), "New chunk 0 should exist");
    assertTrue(docExists("doc-1#chunk_1"), "New chunk 1 should exist");
  }

  // ========== Doc-level union leg (tempdoc 749, option B) ==========

  @Test
  @DisplayName("findParentDocIdsWithChunks returns only candidates that have chunk docs")
  void findParentDocIdsWithChunksReturnsChunkedSubset() throws Exception {
    // Parent A has a chunk doc; parent B has none.
    indexDoc("parent-a", "Parent A about migratory birds");
    indexChunk("parent-a", 0, 1, "Migratory birds travel long distances");
    indexDoc("parent-b", "Parent B about desert reptiles");

    commitAndRefresh();

    Set<String> probed =
        runtime.chunkSearchOps().findParentDocIdsWithChunks(Set.of("parent-a", "parent-b"));
    assertEquals(Set.of("parent-a"), probed,
        "Only parent-a (which owns a chunk doc) should be reported as chunked");

    // Empty probe short-circuits to empty.
    assertTrue(runtime.chunkSearchOps().findParentDocIdsWithChunks(Set.of()).isEmpty(),
        "Empty candidate set returns empty");
  }

  @Test
  @DisplayName(
      "findParentDocIdsWithChunks: a parent with SEVERAL chunk docs is still classified "
          + "chunked, and a chunkless sibling among the same candidates is not (review Fix 2 — "
          + "per-parent existence probes, not a shared result-window)")
  void findParentDocIdsWithChunksHandlesMultiChunkParent() throws Exception {
    // Parent C owns 3 chunk docs; parent D has none.
    indexDoc("parent-c", "Parent C about renewable energy policy");
    indexChunk("parent-c", 0, 3, "Solar power adoption is accelerating");
    indexChunk("parent-c", 1, 3, "Wind energy capacity has doubled");
    indexChunk("parent-c", 2, 3, "Grid storage remains the bottleneck");
    indexDoc("parent-d", "Parent D about unrelated topic");

    commitAndRefresh();

    Set<String> probed =
        runtime.chunkSearchOps().findParentDocIdsWithChunks(Set.of("parent-c", "parent-d"));
    assertEquals(Set.of("parent-c"), probed,
        "parent-c (3 chunk docs) is classified chunked; chunkless parent-d is not");
  }

  @Test
  @DisplayName("searchFullDocs: unscoped finds all parents; scoped filters; excludes chunk docs")
  void searchFullDocsUnscopedScopedAndChunkExclusion() throws Exception {
    indexDoc("doc-p1", "The quick brown fox jumps");
    indexDoc("doc-p2", "A red fox runs through the forest");

    // A chunk doc that ALSO carries a CONTENT field mentioning the query term — this is exactly
    // what the IS_CHUNK MUST_NOT clause must keep out of full-doc results.
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "chunk:fox-000",
        SchemaFields.DOC_UID, "chunk:fox-000#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, "doc-p1",
        SchemaFields.CHUNK_INDEX, "0",
        SchemaFields.CHUNK_TOTAL, "1",
        SchemaFields.CHUNK_CONTENT, "fox chunk content",
        SchemaFields.CONTENT, "fox also appears in this chunk document body",
        SchemaFields.PATH, "doc-p1")));

    commitAndRefresh();

    // Unscoped (empty docIds): both parents match, and the chunk doc is excluded despite its
    // CONTENT field containing "fox". null additionalFilter is accepted.
    var unscoped = runtime.chunkSearchOps().searchFullDocs("fox", Set.of(), 10, null);
    assertNotNull(unscoped);
    assertEquals(2, unscoped.hits().size(), "Unscoped search returns both parent docs");
    Set<String> unscopedIds =
        unscoped.hits().stream().map(h -> h.docId()).collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of("doc-p1", "doc-p2"), unscopedIds,
        "Only the two parents come back — never the chunk doc (IS_CHUNK MUST_NOT)");

    // Scoped to {doc-p2}: only doc-p2 comes back.
    var scoped = runtime.chunkSearchOps().searchFullDocs("fox", Set.of("doc-p2"), 10, null);
    assertEquals(1, scoped.hits().size(), "Scoped search returns exactly the in-scope parent");
    assertEquals("doc-p2", scoped.hits().get(0).docId());

    // Blank query is empty regardless of scope.
    var blank = runtime.chunkSearchOps().searchFullDocs("   ", Set.of(), 10, null);
    assertTrue(blank.hits().isEmpty(), "Blank query yields no full-doc hits");

    // Regression: searchFullDocsForDocs keeps its return-empty-on-empty-scope contract, which
    // is deliberately distinct from searchFullDocs' unscoped-means-all-docs behaviour above.
    var emptyScope = runtime.chunkSearchOps().searchFullDocsForDocs("fox", Set.of(), 5);
    assertTrue(emptyScope.hits().isEmpty(),
        "searchFullDocsForDocs still returns empty on empty docIds (contract unchanged)");
  }

  @Test
  @DisplayName("searchDocLevelUnion with null queryVector dispatches to the BM25 full-doc path")
  void searchDocLevelUnionNullVectorUsesBm25() throws Exception {
    indexDoc("doc-u1", "Coral reefs support diverse marine life");
    indexDoc("doc-u2", "Coral bleaching threatens reef ecosystems");

    commitAndRefresh();

    // Null queryVector forces the BM25 (searchFullDocs) branch; both parents match "coral".
    var result =
        runtime.chunkSearchOps().searchDocLevelUnion("coral reef", null, Set.of(), 10, null);
    assertNotNull(result);
    assertEquals(2, result.hits().size(), "BM25 union path returns both matching parents");
    Set<String> ids =
        result.hits().stream().map(h -> h.docId()).collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of("doc-u1", "doc-u2"), ids);
    // The hybrid (usable-queryVector) branch's RRF ranking is covered by the live R9 validation;
    // its symmetric IS_CHUNK exclusion is unit-tested below (searchDocLevelUnionHybridExcludesChunkDocs).
  }

  @Test
  @DisplayName("searchDocLevelUnion (hybrid branch) excludes chunk docs by construction (tempdoc 749 review PF-2)")
  void searchDocLevelUnionHybridExcludesChunkDocs() throws Exception {
    // Regular parent: matches the BM25 leg via content, carries no vector of its own.
    indexDoc("doc-1", "Coral reefs support diverse marine life");

    // A "poisoned" chunk doc that also happens to carry parent-level content + vector — the
    // latent double-surface trap this guard exists to prevent. A chunk doc must never be
    // eligible for the doc-level union, regardless of what fields it carries.
    float[] queryVector = new float[] {1f, 0f, 0f, 0f};
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "doc-1#chunk_0",
        SchemaFields.DOC_UID, "doc-1#chunk_0#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, "doc-1",
        SchemaFields.CONTENT, "Coral reefs support diverse marine life",
        SchemaFields.VECTOR, queryVector,
        SchemaFields.PATH, "doc-1"
    )));

    commitAndRefresh();

    // Non-null, non-empty queryVector and no query-skip condition -> dispatches to the hybrid
    // branch (searchHybridFiltered), not the BM25 (searchFullDocs) fallback.
    var result =
        runtime.chunkSearchOps().searchDocLevelUnion("coral reef", queryVector, Set.of(), 10, null);

    assertNotNull(result);
    for (var hit : result.hits()) {
      assertFalse(
          "doc-1#chunk_0".equals(hit.docId()),
          "Chunk doc must never surface from the doc-level union hybrid branch, even though it "
              + "carries matching content + vector. Got: " + hit.docId());
    }
  }

  // ========== Chunk-branch filter scoping (human-validation finding 4) ==========

  /**
   * The chunk branch runs its legs under {@link QueryFilterBuilder#buildChunkFilterQuery}, which used
   * to drop {@code pathPrefix} on the premise that a chunk's {@code PATH} "stores parentDocId, not
   * the file path". It stores both: the parent's {@code DOC_ID} IS its absolute path. Dropping the
   * scope let the chunk branch retrieve candidates from outside the requested prefix, which enter
   * the fused candidate union the response reports as {@code totalHits} and — at a high enough fused
   * rank — leak into {@code results}.
   */
  @Test
  @DisplayName("chunk retrieval is scoped by pathPrefix (out-of-prefix parents must not come back)")
  void chunkFilterScopesByPathPrefix() throws Exception {
    String insideDir = QueryFilterBuilder.normalizePathPrefix("corpus/inside");
    String outsideDir = QueryFilterBuilder.normalizePathPrefix("corpus/outside");
    String insideDoc = insideDir + "note-a.md";
    String outsideDoc = outsideDir + "note-b.md";

    indexDoc(insideDoc, "parent inside the requested prefix");
    indexChunk(insideDoc, 0, 1, "neural networks overview");
    indexDoc(outsideDoc, "parent outside the requested prefix");
    indexChunk(outsideDoc, 0, 1, "neural networks overview");
    commitAndRefresh();

    var filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .pathPrefix("corpus/inside")
            .build();
    var chunkFilter = QueryFilterBuilder.buildChunkFilterQuery(filters);
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(
        1,
        result.hits().size(),
        "the chunk leg must retrieve only chunks whose parent is under the requested pathPrefix; "
            + "an extra hit means the out-of-prefix parent's chunk was retrieved");
    assertEquals(
        insideDoc,
        result.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID),
        "the surviving chunk must belong to the in-prefix parent");
  }

  @Test
  @DisplayName("chunk retrieval is scoped by doc_ids (PATH holds the parent's absolute path)")
  void chunkFilterScopesByDocIds() throws Exception {
    String insideDir = QueryFilterBuilder.normalizePathPrefix("corpus/inside");
    String insideDoc = insideDir + "note-a.md";
    String otherDoc = insideDir + "note-b.md";

    indexDoc(insideDoc, "first parent");
    indexChunk(insideDoc, 0, 1, "neural networks overview");
    indexDoc(otherDoc, "second parent");
    indexChunk(otherDoc, 0, 1, "neural networks overview");
    commitAndRefresh();

    var filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(java.util.List.of(insideDoc))
            .build();
    var chunkFilter = QueryFilterBuilder.buildChunkFilterQuery(filters);
    var result = runtime.chunkSearchOps().searchChunksText("neural", 10, chunkFilter);

    assertNotNull(result);
    assertEquals(1, result.hits().size(), "doc_ids must scope the chunk leg to the named documents");
    assertEquals(
        insideDoc,
        result.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID),
        "the surviving chunk must belong to the requested document");
  }

  @Test
  @DisplayName("an unfiltered request still produces no chunk filter (no behaviour change)")
  void chunkFilterStaysNullWithoutFilters() {
    var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
    assertNull(
        QueryFilterBuilder.buildChunkFilterQuery(filters),
        "an all-default filter set must not synthesize a chunk filter — unfiltered retrieval is "
            + "unchanged by the pathPrefix/doc_ids addition");
  }

  // ========== Helper Methods ==========

  private void indexDoc(String docId, String content) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, docId,
        SchemaFields.DOC_UID, docId + "#0",
        SchemaFields.CONTENT, content,
        SchemaFields.PATH, docId
    )));
  }

  /**
   * Indexes a chunk document.
   *
   * <p>Uses legacy chunk ID format ({@code parentDocId + "#chunk_" + index}) for
   * backward compatibility with existing tests that check for specific IDs.
   * New production code uses opaque UUID-based chunk IDs.
   */
  private void indexChunk(String parentDocId, int index, int total, String content) {
    // Use legacy format for tests that verify specific chunk IDs
    String chunkId = parentDocId + "#chunk_" + index;
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, chunkId,
        SchemaFields.DOC_UID, chunkId + "#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, parentDocId,
        SchemaFields.CHUNK_INDEX, String.valueOf(index),
        SchemaFields.CHUNK_TOTAL, String.valueOf(total),
        SchemaFields.CHUNK_CONTENT, content,
        SchemaFields.PATH, parentDocId
    )));
  }

  private String indexChunkWithVector(
      String parentDocId, int index, int total, String content, float[] vector) {
    String chunkId = parentDocId + "#chunk_" + index;
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, chunkId,
        SchemaFields.DOC_UID, chunkId + "#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, parentDocId,
        SchemaFields.CHUNK_INDEX, String.valueOf(index),
        SchemaFields.CHUNK_TOTAL, String.valueOf(total),
        SchemaFields.CHUNK_CONTENT, content,
        SchemaFields.CHUNK_VECTOR, vector,
        SchemaFields.PATH, parentDocId
    )));
    return chunkId;
  }

  /**
   * Indexes a chunk document with opaque UUID-based ID.
   *
   * <p>Uses the new production format (P0.8). Tests using this helper
   * cannot rely on doc_id patterns for verification.
   */
  private void indexChunkOpaque(String parentDocId, int index, int total, String content) {
    String chunkId = "chunk:" + java.util.UUID.randomUUID().toString();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, chunkId,
        SchemaFields.DOC_UID, chunkId + "#0",
        SchemaFields.IS_CHUNK, "true",
        SchemaFields.PARENT_DOC_ID, parentDocId,
        SchemaFields.CHUNK_INDEX, String.valueOf(index),
        SchemaFields.CHUNK_TOTAL, String.valueOf(total),
        SchemaFields.CHUNK_CONTENT, content,
        SchemaFields.PATH, parentDocId
    )));
  }

  private boolean docExists(String docId) {
    // Use search to check existence since getDocumentContent returns null for docs without 'content' field
    var result = runtime.readPathOps().search(
        new org.apache.lucene.search.TermQuery(
            new org.apache.lucene.index.Term(SchemaFields.DOC_ID, docId)),
        1, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    return !result.hits().isEmpty();
  }

  private int countAllDocs() {
    return (int) runtime.indexCountOps().docCount();
  }

  private void commitAndRefresh() throws Exception {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
