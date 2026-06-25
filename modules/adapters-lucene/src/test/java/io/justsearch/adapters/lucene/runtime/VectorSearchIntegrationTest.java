package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorSearchIntegrationTest extends RuntimeTestBase {

  @Test
  void searchVectorReturnsNearestNeighbors() throws Exception {
    Path base = dataDir();

    // Create config with vector dimension = 4 (small for testing)
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: vectortest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index documents with vectors (4-dimensional for simplicity)
    float[] vec1 = new float[] {1.0f, 0.0f, 0.0f, 0.0f}; // Pointing in +X direction
    float[] vec2 = new float[] {0.0f, 1.0f, 0.0f, 0.0f}; // Pointing in +Y direction
    float[] vec3 = new float[] {0.9f, 0.1f, 0.0f, 0.0f}; // Close to vec1

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.VECTOR, vec1)));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.VECTOR, vec2)));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.VECTOR, vec3)));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Search for vectors similar to vec1 (should return doc-1 and doc-3)
    float[] queryVector = new float[] {1.0f, 0.0f, 0.0f, 0.0f};
    SearchResult result = runtime.readPathOps().searchVector(queryVector, 3);

    assertNotNull(result);
    assertEquals(3, result.hits().size(), "Should return all 3 documents");

    // The first result should be doc-1 (exact match) or doc-3 (very close)
    String firstDocId = result.hits().get(0).docId();
    assertTrue(
        firstDocId.equals("doc-1") || firstDocId.equals("doc-3"),
        "First result should be doc-1 or doc-3, got: " + firstDocId);

    // doc-2 should be last (orthogonal to query)
    String lastDocId = result.hits().get(2).docId();
    assertEquals("doc-2", lastDocId, "Last result should be doc-2 (orthogonal)");

    runtime.close();
  }

  @Test
  void vectorEfSearchOverrideIncreasesInternalQueryKButPreservesApiLimit() throws Exception {
    Path base = dataDir();

    // Explicit ef_search should be treated as an override (not a default), and increase the
    // internal k for vector search breadth (while still returning only `limit` hits).
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: efsearchtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n    ef_search: 32\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.VECTOR, new float[] {1.0f, 0.0f, 0.0f, 0.0f})));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.VECTOR, new float[] {0.0f, 1.0f, 0.0f, 0.0f})));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.VECTOR, new float[] {0.9f, 0.1f, 0.0f, 0.0f})));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // resolveVectorQueryK is package-private on ReadPathOps — access via session ctx for testing
    ReadPathOps readOps = new ReadPathOps(runtime.session(), "doc_id");
    assertEquals(
        32,
        readOps.resolveVectorQueryK(10),
        "Expected ef_search to raise internal query k above limit");
    assertEquals(
        64,
        readOps.resolveVectorQueryK(64),
        "Expected ef_search not to reduce internal query k");

    SearchResult sr = runtime.readPathOps().searchVector(new float[] {1.0f, 0.0f, 0.0f, 0.0f}, 3);
    assertNotNull(sr);
    assertNotNull(sr.hits());
    assertEquals(3, sr.hits().size(), "Expected searchVector to still return exactly `limit` hits");

    runtime.close();
  }

  @Test
  void searchVectorWithNullVectorThrows() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: nulltest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Should throw on null vector
    assertThrows(
        IllegalArgumentException.class, () -> runtime.readPathOps().searchVector(null, 10));

    // Should throw on empty vector
    assertThrows(
        IllegalArgumentException.class, () -> runtime.readPathOps().searchVector(new float[0], 10));

    runtime.close();
  }
}
