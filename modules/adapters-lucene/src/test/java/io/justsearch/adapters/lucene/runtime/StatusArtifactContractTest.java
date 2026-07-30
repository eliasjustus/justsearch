/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Tempdoc 798 (T3) — write-time status/artifact contract.
 *
 * <p>A write that sets {@code <stage>_status = COMPLETED} must carry that status's witnessing
 * artifact in the same field map. Enforced at both write lanes: the full-document lane
 * ({@link IndexingCoordinator#validate}) and the RMW/partial-update lane
 * ({@link WritePathOps#readModifyWrite}, over the merged map — that lane never calls
 * {@code validate()}).
 *
 * <p>The documents here are shaped exactly as {@code ChunkDocumentWriter} writes a chunk:
 * {@code is_chunk=true}, {@code chunk_content} set, no {@code content}, no {@code embedding_status},
 * no {@code ner_status}, {@code splade_status=PENDING}.
 */
class StatusArtifactContractTest {

  private static final float[] VEC = {0.25f, -0.5f, 0.75f, 1.0f};

  // ---- the mapping is derived from the catalog, not authored ----

  /**
   * The status&harr;artifact pairing comes from inverting the {@code rmwPolicy} strings the catalog
   * already declares — no new schema key. Asserted against the production catalog shape.
   */
  @Test
  void statusWitnessMapIsInvertedFromRmwPolicies() {
    FieldMapper mapper = new FieldMapper(json(CHUNK_CATALOG));
    Map<String, List<String>> witnesses = mapper.statusWitnessFields();

    assertEquals(List.of(SchemaFields.VECTOR), witnesses.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals(
        List.of(SchemaFields.CHUNK_VECTOR), witnesses.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals(List.of(SchemaFields.SPLADE), witnesses.get(SchemaFields.SPLADE_STATUS));
    assertEquals(
        3, witnesses.size(), "only rmwPolicy-declared pairings participate: " + witnesses.keySet());
    // A status no artifact witnesses (ner_status, vdu_status) is unconstrained by the contract.
    assertNull(witnesses.get(SchemaFields.NER_STATUS));
  }

  // ---- full-document lane ----

  /** FAIL mode: a chunk write claiming embedding_status=COMPLETED with no vector is rejected. */
  @Test
  void failModeRejectsCompletedWithoutArtifactOnFullDocumentWrite() throws Exception {
    withRuntime(
        ValidationMode.FAIL,
        (runtime) -> {
          Map<String, Object> chunk = chunkDoc("chunk-0");
          chunk.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          // Deliberately NO VECTOR — the unwitnessed COMPLETED that livelocked the worker.

          IndexRuntimeIOException ex =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () -> runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk)));
          assertTrue(ex.getMessage().contains("status_without_artifact"), ex.getMessage());
          assertTrue(ex.getMessage().contains(SchemaFields.EMBEDDING_STATUS), ex.getMessage());
          assertTrue(ex.getMessage().contains(SchemaFields.VECTOR), ex.getMessage());
        });
  }

  /** WARN mode logs and proceeds — the write lands, exactly as the existing checks behave. */
  @Test
  void warnModeLogsAndProceedsOnFullDocumentWrite() throws Exception {
    withRuntime(
        ValidationMode.WARN,
        (runtime) -> {
          Map<String, Object> chunk = chunkDoc("chunk-0");
          chunk.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);

          assertDoesNotThrow(
              () -> runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk)));
          commit(runtime);

          assertEquals(
              SchemaFields.EMBEDDING_STATUS_COMPLETED,
              runtime.documentFieldOps().getDocumentField("chunk-0", SchemaFields.EMBEDDING_STATUS),
              "WARN mode must let the write through, not silently drop it");
        });
  }

  /** A legitimate write — COMPLETED carrying its artifact — passes untouched in FAIL mode. */
  @Test
  void legitimateCompletedWriteWithArtifactPasses() throws Exception {
    withRuntime(
        ValidationMode.FAIL,
        (runtime) -> {
          Map<String, Object> chunk = chunkDoc("chunk-0");
          chunk.put(SchemaFields.CHUNK_VECTOR, VEC);
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);

          assertDoesNotThrow(
              () -> runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk)));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.CHUNK_VECTOR, "chunk-0"),
              "a truthful COMPLETED write is stored untouched");
          assertEquals(
              SchemaFields.EMBEDDING_STATUS_COMPLETED,
              runtime
                  .documentFieldOps()
                  .getDocumentField("chunk-0", SchemaFields.CHUNK_EMBEDDING_STATUS));
        });
  }

  /** PENDING/FAILED never claim an artifact, so a vectorless chunk write is legal. */
  @Test
  void pendingStatusWithoutArtifactPasses() throws Exception {
    withRuntime(
        ValidationMode.FAIL,
        (runtime) -> {
          // chunkDoc() is the ChunkDocumentWriter shape verbatim: splade_status=PENDING,
          // chunk_embedding_status=PENDING, no artifacts at all.
          assertDoesNotThrow(
              () -> runtime.indexingCoordinator().indexSingle(new IndexDocument(chunkDoc("chunk-0"))));
        });
  }

  // ---- RMW / partial-update lane (never reaches IndexingCoordinator.validate) ----

  /**
   * The lane {@code validate()} does not cover: a partial update that stamps COMPLETED without the
   * artifact. This is the write that fought the RMW reset policy at ~64 Hz.
   */
  @Test
  void failModeRejectsCompletedWithoutArtifactOnRmw() throws Exception {
    withRuntime(
        ValidationMode.FAIL,
        (runtime) -> {
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunkDoc("chunk-0")));
          commit(runtime);

          IndexRuntimeIOException ex =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      runtime
                          .indexingCoordinator()
                          .updateDocument(
                              "chunk-0",
                              Map.of(
                                  SchemaFields.CHUNK_EMBEDDING_STATUS,
                                  SchemaFields.EMBEDDING_STATUS_COMPLETED)));
          assertTrue(ex.getMessage().contains("status_without_artifact"), ex.getMessage());
          assertTrue(ex.getMessage().contains(SchemaFields.CHUNK_VECTOR), ex.getMessage());

          commit(runtime);
          assertEquals(
              SchemaFields.EMBEDDING_STATUS_PENDING,
              runtime
                  .documentFieldOps()
                  .getDocumentField("chunk-0", SchemaFields.CHUNK_EMBEDDING_STATUS),
              "the rejected write must not have landed");
        });
  }

  /** WARN mode on the RMW lane logs and proceeds, same gate as the full-document lane. */
  @Test
  void warnModeLogsAndProceedsOnRmw() throws Exception {
    withRuntime(
        ValidationMode.WARN,
        (runtime) -> {
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunkDoc("chunk-0")));
          commit(runtime);

          assertDoesNotThrow(
              () ->
                  runtime
                      .indexingCoordinator()
                      .updateDocument(
                          "chunk-0",
                          Map.of(
                              SchemaFields.CHUNK_EMBEDDING_STATUS,
                              SchemaFields.EMBEDDING_STATUS_COMPLETED)));
          commit(runtime);

          assertEquals(
              SchemaFields.EMBEDDING_STATUS_COMPLETED,
              runtime
                  .documentFieldOps()
                  .getDocumentField("chunk-0", SchemaFields.CHUNK_EMBEDDING_STATUS));
        });
  }

  /**
   * The case that must NOT regress: an RMW that omits the artifact but whose {@code rmwPolicy}
   * preserves it (the {@code chunk_vector} re-read carries it into the merged map) still passes,
   * even though the caller's update map alone contains no artifact. The contract reads the merged
   * map, not the caller's updates.
   */
  @Test
  void rmwPreservedArtifactSatisfiesTheContract() throws Exception {
    withRuntime(
        ValidationMode.FAIL,
        (runtime) -> {
          Map<String, Object> chunk = chunkDoc("chunk-0");
          chunk.put(SchemaFields.CHUNK_VECTOR, VEC);
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          // Update touches only an unrelated field; chunk_vector is absent from the update map and
          // chunk_embedding_status=COMPLETED comes back from the stored fields.
          assertDoesNotThrow(
              () ->
                  runtime
                      .indexingCoordinator()
                      .updateDocument(
                          "chunk-0", Map.of(SchemaFields.PARENT_DOC_ID, "doc-renamed")));
          commit(runtime);

          assertArrayEquals(
              VEC,
              readVector(runtime, SchemaFields.CHUNK_VECTOR, "chunk-0"),
              "preserve-reread carried the artifact forward, so the COMPLETED stayed truthful");
          assertEquals(
              SchemaFields.EMBEDDING_STATUS_COMPLETED,
              runtime
                  .documentFieldOps()
                  .getDocumentField("chunk-0", SchemaFields.CHUNK_EMBEDDING_STATUS));
        });
  }

  /**
   * The RMW reset lanes stay in force behind the contract: a COMPLETED-but-artifactless document
   * already on disk (seeded in WARN mode) is still healed to PENDING by an unrelated RMW, and that
   * healed merged map satisfies the contract rather than tripping it.
   */
  @Test
  void rmwResetLaneStillHealsOnDiskLiesWithoutTrippingTheContract() throws Exception {
    withRuntime(
        ValidationMode.WARN,
        (runtime) -> {
          Map<String, Object> chunk = chunkDoc("chunk-0");
          chunk.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
          commit(runtime);

          assertDoesNotThrow(
              () ->
                  runtime
                      .indexingCoordinator()
                      .updateDocument(
                          "chunk-0", Map.of(SchemaFields.PARENT_DOC_ID, "doc-renamed")));
          commit(runtime);

          assertEquals(
              SchemaFields.EMBEDDING_STATUS_PENDING,
              runtime
                  .documentFieldOps()
                  .getDocumentField("chunk-0", SchemaFields.CHUNK_EMBEDDING_STATUS),
              "the preserve-reread-or-reset backstop must still fire (tempdocs 711/717)");
        });
  }

  // ---- helpers ----

  /** A chunk document exactly as {@code ChunkDocumentWriter} writes one. */
  private static Map<String, Object> chunkDoc(String chunkId) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, chunkId);
    fields.put(SchemaFields.DOC_UID, chunkId + "#0");
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.PARENT_DOC_ID, "doc-0");
    fields.put(SchemaFields.PATH, "doc-0");
    fields.put(SchemaFields.CHUNK_CONTENT, "chunk body");
    // No CONTENT, no EMBEDDING_STATUS, no NER_STATUS — a chunk carries none of them.
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
    fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
    return fields;
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
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

  private void withRuntime(ValidationMode mode, RuntimeConsumer body) throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    RunningRuntime runtime = null;
    try {
      base = Files.createTempDirectory("justsearch-status-contract-");
      String yaml =
          "app:\n  data_dir: "
              + base.toString().replace("\\", "\\\\")
              + "\n"
              + "index:\n  collections:\n    - name: statuscontracttest\n      roots: ['ignored']\n"
              + "  vector:\n    dimension: 4\n"
              + "  validation:\n    mode: "
              + (mode == ValidationMode.WARN ? "warn" : "fail")
              + "\n";
      cfg = Files.createTempFile("justsearch-config-", ".yaml");
      Files.writeString(cfg, yaml);
      System.setProperty("justsearch.config", cfg.toString());
      runtime = open(CHUNK_CATALOG);
      body.accept(runtime);
    } finally {
      if (runtime != null) runtime.close();
      restoreConfig(prev, base, cfg);
    }
  }

  /**
   * Mirrors the production catalog's rmwPolicy declarations for the three enrichment artifacts
   * ({@code SSOT/catalogs/fields.v1.json}: vector&rarr;embedding_status,
   * chunk_vector&rarr;chunk_embedding_status, splade&rarr;splade_status).
   */
  private static final String CHUNK_CATALOG =
      """
      {
        "fields": [
          { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
          { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
          { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
          { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
          { "id": "parent_doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
          { "id": "content", "type": "text", "stored": true, "docValues": false },
          { "id": "chunk_content", "type": "text", "stored": true, "docValues": false },
          { "id": "ner_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
          { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
          { "id": "embedding_retry_count", "type": "long", "stored": true, "docValues": true, "roles": ["filter", "sort"] },
          { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
          { "id": "chunk_embedding_retry_count", "type": "long", "stored": true, "docValues": true, "roles": ["filter", "sort"] },
          { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
          { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
          { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" },
          { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread-or-reset:embedding_status", "vector": { "dimension": 4 } },
          { "id": "chunk_vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread-or-reset:chunk_embedding_status", "vector": { "dimension": 4 } }
        ]
      }
      """;

  private RunningRuntime open(String catalogJson) {
    try {
      var fieldMapper = new FieldMapper(json(catalogJson));
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
