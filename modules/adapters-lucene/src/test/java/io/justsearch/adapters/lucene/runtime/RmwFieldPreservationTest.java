package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 711 Item 1 — RMW field-preservation engine regression tests.
 *
 * <p>The read-modify-write choke point preserves non-stored, non-docValues data-bearing fields the
 * caller omits, per their declared {@code rmwPolicy}: vectors are re-read and carried forward
 * ({@code preserve-reread}); SPLADE data cannot be re-read, so its status field is driven
 * ({@code reset-status}) to force a re-encode. Startup fail-fast rejects an undeclared fragile field.
 */
class RmwFieldPreservationTest {

  private static final float[] VEC = {0.25f, -0.5f, 0.75f, 1.0f};

  /** A NER-style RMW (updates = entity only) preserves a present vector bit-exactly. */
  @Test
  void vectorSurvivesNerStyleRmw() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", VEC, SchemaFields.SPLADE_STATUS_COMPLETED);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "vector must survive an RMW that omits it (preserve-reread)");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** A VDU SUCCESS_EMPTY-style RMW (updates = VDU fields only) preserves the vector. */
  @Test
  void vectorSurvivesVduOnlyRmw() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", VEC, SchemaFields.SPLADE_STATUS_COMPLETED);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0",
                  Map.of(SchemaFields.VDU_STATUS, "COMPLETED", SchemaFields.VDU_PROCESSED, true)));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "vector must survive a VDU-only RMW");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** A doc indexed without a vector is a no-op for preservation — RMW must not fail. */
  @Test
  void missingVectorRmwIsNoOp() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", null, SchemaFields.SPLADE_STATUS_COMPLETED);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")),
              "RMW on a vectorless doc must still succeed");
          commit(runtime);

          assertNull(
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "a doc with no vector stays vectorless — nothing to preserve");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** Two updates to the same doc in one batch: the vector survives (re-read at the batch snapshot). */
  @Test
  void sameDocTwiceInOneBatchPreservesVector() throws Exception {
    withConfig(
        (runtime) -> {
          indexDoc(runtime, "doc-0", VEC, SchemaFields.SPLADE_STATUS_COMPLETED);

          List<Map.Entry<String, Map<String, Object>>> batch = new ArrayList<>();
          batch.add(Map.entry("doc-0", Map.of("entity_persons_raw", "Alice")));
          batch.add(Map.entry("doc-0", Map.of(SchemaFields.VDU_STATUS, "COMPLETED")));
          runtime.indexingCoordinator().updateDocumentsBatch(batch);
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.VECTOR, "doc-0"),
              "vector must survive two same-doc RMWs in one batch");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** SPLADE cannot be re-read: an RMW dropping its data downgrades COMPLETED status to PENDING. */
  @Test
  void spladeDataDropTriggersStatusDowngrade() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> doc = new HashMap<>();
          doc.put(SchemaFields.DOC_ID, "doc-0");
          doc.put(SchemaFields.DOC_UID, "doc-0#0");
          doc.put(SchemaFields.PATH, "test/doc-0.txt");
          doc.put(SchemaFields.CONTENT, "content");
          doc.put(SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f));
          doc.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
          doc.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
          runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
          commit(runtime);
          assertTrue(spladeHits(runtime, "alpha") >= 1, "precondition: SPLADE data present");

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "doc-0", Map.of("entity_persons_raw", "Alice")));
          commit(runtime);

          // The SPLADE FeatureField data is unavoidably dropped (no re-read lane) ...
          assertEquals(0, spladeHits(runtime, "alpha"), "SPLADE data is dropped by the rewrite");
          // ... but the status is now PENDING so the backfill will re-encode — no silent
          // COMPLETED-but-empty doc (the second silent-loss bug is fixed).
          assertEquals(
              SchemaFields.SPLADE_STATUS_PENDING,
              runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS),
              "status must downgrade to PENDING when SPLADE data is dropped");
        },
        this::createRuntimeWithVectorAndSplade);
  }

  /** chunk_vector on a chunk doc survives an RMW that touches only a chunk lifecycle field. */
  @Test
  void chunkVectorSurvivesRmw() throws Exception {
    withConfig(
        (runtime) -> {
          Map<String, Object> chunk = new HashMap<>();
          chunk.put(SchemaFields.DOC_ID, "chunk-0");
          chunk.put(SchemaFields.DOC_UID, "chunk-0#0");
          chunk.put(SchemaFields.PATH, "test/doc-0.txt");
          chunk.put(SchemaFields.IS_CHUNK, "true");
          chunk.put(SchemaFields.PARENT_DOC_ID, "doc-0");
          chunk.put(SchemaFields.CHUNK_CONTENT, "chunk body");
          chunk.put(SchemaFields.CHUNK_VECTOR, VEC);
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          assertTrue(
              runtime.indexingCoordinator().updateDocument(
                  "chunk-0", Map.of(SchemaFields.PARENT_DOC_ID, "doc-renamed")));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.CHUNK_VECTOR, "chunk-0"),
              "chunk_vector must survive an RMW that omits it");
        },
        this::createRuntimeWithChunkVector);
  }

  /** Startup fail-fast: a fragile (non-stored, non-docValues) vector field without a policy. */
  @Test
  void startupFailFastRejectsUndeclaredFragileField() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "vector", "type": "vector", "stored": false, "docValues": false, "vector": { "dimension": 4 } }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("rmwPolicy"), ex.getMessage());
  }

  /** Startup fail-fast: a reset-status target that is not docValues-backed. */
  @Test
  void startupFailFastRejectsResetStatusTargetNotDocValues() throws Exception {
    String badJson =
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "splade_status", "type": "keyword", "stored": true, "docValues": false, "roles": [] },
            { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" }
          ]
        }
        """;
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> new FieldMapper(json(badJson)).validateRmwPolicies());
    assertTrue(ex.getMessage().contains("docValues-backed"), ex.getMessage());
  }

  // ---- helpers ----

  private void indexDoc(RunningRuntime runtime, String id, float[] vec, String spladeStatus) {
    Map<String, Object> doc = new HashMap<>();
    doc.put(SchemaFields.DOC_ID, id);
    doc.put(SchemaFields.DOC_UID, id + "#0");
    doc.put(SchemaFields.PATH, "test/" + id + ".txt");
    doc.put(SchemaFields.CONTENT, "content for " + id);
    doc.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    if (vec != null) doc.put(SchemaFields.VECTOR, vec);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(doc));
    commit(runtime);
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static int spladeHits(RunningRuntime runtime, String feature) throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher ->
                searcher.count(
                    org.apache.lucene.document.FeatureField.newSaturationQuery(
                        SchemaFields.SPLADE, feature)));
  }

  private static float[] readVector(RunningRuntime runtime, String field, String docId)
      throws Exception {
    return runtime
        .readPathOps()
        .withSearcher(
            searcher -> {
              var td = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, docId)), 1);
              if (td.scoreDocs.length == 0) return null;
              int gid = td.scoreDocs[0].doc;
              var leaves = searcher.getIndexReader().leaves();
              var leaf = leaves.get(ReaderUtil.subIndex(gid, leaves));
              int docInLeaf = gid - leaf.docBase;
              FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
              if (values == null) return null;
              var iter = values.iterator();
              if (iter.advance(docInLeaf) != docInLeaf) return null;
              float[] v = values.vectorValue(iter.index());
              return v == null ? null : v.clone();
            });
  }

  private static tools.jackson.databind.JsonNode json(String s) {
    try {
      return new ObjectMapper().readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private interface RuntimeConsumer {
    void accept(RunningRuntime runtime) throws Exception;
  }

  private void withConfig(RuntimeConsumer body, java.util.function.Supplier<RunningRuntime> factory)
      throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    RunningRuntime runtime = null;
    try {
      base = Files.createTempDirectory("justsearch-rmw-preserve-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());
      runtime = factory.get();
      body.accept(runtime);
    } finally {
      if (runtime != null) runtime.close();
      restoreConfig(prev, base, cfg);
    }
  }

  private Path writeTestConfig(Path base) throws Exception {
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: rmwpreservetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    return cfg;
  }

  private RunningRuntime createRuntimeWithVectorAndSplade() {
    return open(
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "content", "type": "text", "stored": true, "docValues": false },
            { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "vdu_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "vdu_processed", "type": "boolean", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
            { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
            { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" },
            { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
          ]
        }
        """);
  }

  private RunningRuntime createRuntimeWithChunkVector() {
    return open(
        """
        {
          "fields": [
            { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
            { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
            { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "chunk_content", "type": "text", "stored": true, "docValues": false },
            { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
            { "id": "chunk_vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
          ]
        }
        """);
  }

  private RunningRuntime open(String json) {
    try {
      var fieldMapper = new FieldMapper(json(json));
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
