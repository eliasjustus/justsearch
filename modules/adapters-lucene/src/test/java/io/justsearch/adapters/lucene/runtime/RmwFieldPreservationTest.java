package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.document.FeatureField;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 711 Item 1 — Step 0 characterization tests.
 *
 * <p>These document the CURRENT (pre-engine) behavior of {@link WritePathOps#readModifyWrite}:
 * non-stored data-bearing fields absent from the caller's update map are silently destroyed. They
 * assert the broken behavior on purpose so the fix has a before/after in git history; Step 5 flips
 * the assertions to the preserved behavior once the RMW policy engine lands.
 */
class RmwFieldPreservationTest {

  /**
   * (a) A NER-style RMW (updates = entity only) on a vector-bearing doc DESTROYS the vector.
   * Current behavior: the vector is non-stored + non-docValues, invisible to storedFields(), and
   * nothing re-reads it, so the rewritten doc has no vector and drops out of KNN retrieval.
   */
  @Test
  void nerStyleRmwDestroysVector_currentBehavior() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-rmw-char-vec-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithVectorAndSplade();

      float[] vec = new float[] {1.0f, 0.0f, 0.0f, 0.0f};
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content",
                  SchemaFields.VECTOR, vec,
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Sanity: the vector is retrievable before the RMW.
      assertTrue(
          vectorHits(runtime, vec) >= 1, "precondition: vector must be retrievable before RMW");

      // NER-style RMW: updates carry only an entity field (no VECTOR).
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of("entity_persons_raw", "Alice"), true);
      assertTrue(updated);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // CURRENT BEHAVIOR (bug): the vector was destroyed — doc no longer matches KNN.
      assertEquals(
          0,
          vectorHits(runtime, vec),
          "characterization: RMW without VECTOR destroys the present vector (pre-engine)");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /**
   * (b) A NER-style RMW with {@code preserveSplade=true} on a doc whose SPLADE FeatureField data
   * and {@code splade_status=COMPLETED} both exist DESTROYS the FeatureField data while leaving the
   * status COMPLETED — a second silent-loss bug: the doc claims to be SPLADE-encoded but carries no
   * SPLADE postings.
   */
  @Test
  void preserveSpladeTrueDestroysSpladeDataButKeepsCompletedStatus_currentBehavior()
      throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-rmw-char-splade-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithVectorAndSplade();

      Map<String, Object> doc = new HashMap<>();
      doc.put(SchemaFields.DOC_ID, "doc-0");
      doc.put(SchemaFields.DOC_UID, "doc-0#0");
      doc.put(SchemaFields.PATH, "test/doc-0.txt");
      doc.put(SchemaFields.CONTENT, "content");
      doc.put(SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f));
      doc.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
      doc.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      assertTrue(
          spladeHits(runtime, "alpha") >= 1,
          "precondition: SPLADE FeatureField data must be present before RMW");

      // NER-style RMW: preserveSplade=true, no SPLADE fields in updates.
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of("entity_persons_raw", "Alice"), true);
      assertTrue(updated);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // CURRENT BEHAVIOR (bug): FeatureField data destroyed ...
      assertEquals(
          0,
          spladeHits(runtime, "alpha"),
          "characterization: preserveSplade=true drops the SPLADE FeatureField data (pre-engine)");
      // ... yet the status still claims COMPLETED — the silent-loss signature.
      String status =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_COMPLETED,
          status,
          "characterization: status stays COMPLETED though the SPLADE data is gone");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- helpers ----

  private static int vectorHits(RunningRuntime runtime, float[] queryVector) {
    return runtime.readPathOps().searchVector(queryVector, 10).hits().size();
  }

  private static int spladeHits(RunningRuntime runtime, String feature) throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher -> searcher.count(FeatureField.newSaturationQuery(SchemaFields.SPLADE, feature)));
  }

  private Path writeTestConfig(Path base) throws Exception {
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: rmwchartest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    return cfg;
  }

  private RunningRuntime createRuntimeWithVectorAndSplade() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
              { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .ephemeral()
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void restoreConfig(String prev, Path base, Path cfg) {
    if (prev == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prev);
    }
    try {
      if (cfg != null) Files.deleteIfExists(cfg);
      if (base != null) {
        try (var walk = Files.walk(base)) {
          walk.sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                  });
        }
      }
    } catch (Exception ignored) {
    }
  }
}
