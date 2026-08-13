/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.StageCompletenessCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.VectorPresence;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 821 §3-C3 — {@link IndexCountOps#queryVectorPresenceCount()} and {@link
 * IndexCountOps#queryStageCompletenessCounts()}, the two count primitives the enrichment
 * completeness auditor projects over.
 *
 * <p>Fixture: whole documents in mixed enrichment states, some carrying an actual {@code vector}
 * and some not, plus chunk documents — so the ARTIFACT tier (count the vector) and the STATUS tier
 * (count the bookkeeping field) can be shown to DISAGREE. That disagreement is the whole point:
 * the F-032 class is a status field reading COMPLETED while the artifact is absent.
 */
@DisplayName("enrichment stage completeness + doc-vector presence counts")
class StageCompletenessCountsTest {

  private static final String SEP = File.separator;
  private static final String ROOT = SEP + "lib" + SEP + "stage";
  private static final float[] VEC = {0.1f, 0.2f, 0.3f, 0.4f};

  @TempDir Path tempDir;

  @RegisterExtension
  SystemPropertyExtension sysprops = new SystemPropertyExtension("justsearch.config");

  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(
        config,
        "app:\n  data_dir: "
            + dataDir.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n"
            + "  collections:\n"
            + "    - name: stagecompleteness\n"
            + "      roots: ['ignored']\n"
            + "  vector:\n"
            + "    dimension: 4\n"
            // The F-032 shape (embedding_status=COMPLETED with no vector) cannot be written
            // FRESH under FAIL mode — StatusArtifactContract rejects it (711/714), and rightly
            // so. It arises later, from an RMW that drops the artifact while the status survives,
            // or from an index written before that contract existed. WARN mode is how this
            // fixture reproduces that already-on-disk state; it is the state the presence count
            // exists to expose, so a fixture that cannot express it would test nothing.
            + "  validation:\n"
            + "    mode: warn\n");
    System.setProperty("justsearch.config", config.toString());
    runtime = openRuntime(tempDir.resolve("index"));
    seedCorpus();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  // ---- queryVectorPresenceCount -------------------------------------------------

  @Test
  @DisplayName("presence counts the artifact, not the status — and only on whole documents")
  void vectorPresenceCountsTheArtifactOnWholeDocsOnly() {
    VectorPresence presence = runtime.indexCountOps().queryVectorPresenceCount();

    // 4 parents seeded; only p1 and p2 actually carry a `vector`.
    assertEquals(4, presence.totalDocs(), "chunk documents must not enter the whole-doc total");
    assertEquals(2, presence.vectorsPresent());
    // p3 reads embedding_status=COMPLETED with NO vector — the F-032 shape. If presence were
    // status-derived it would read 3 here, which is exactly the lie this primitive exists to stop.
    assertEquals(
        3,
        runtime.indexCountOps().queryEmbeddingCounts().completed(),
        "the status field claims one more COMPLETED than there are vectors — the divergence is"
            + " the diagnostic signal, so these two must NOT agree on this fixture");
    assertEquals(50.0, presence.coveragePercent(), 0.001);
  }

  @Test
  @DisplayName("a deleted-but-unmerged doc does not inflate presence")
  void deletedDocsAreExcludedFromPresence() {
    assertEquals(2, runtime.indexCountOps().queryVectorPresenceCount().vectorsPresent());

    // No forceMerge: the KNN structures still hold the tombstoned doc's vector, so
    // FloatVectorValues.size() would still count it. Only the per-doc liveDocs check does not.
    runtime.indexingCoordinator().deleteById("p1");
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    VectorPresence after = runtime.indexCountOps().queryVectorPresenceCount();
    assertEquals(1, after.vectorsPresent(), "the deleted doc's vector must not be counted");
    assertEquals(3, after.totalDocs(), "and it leaves the denominator too");
  }

  @Test
  @DisplayName("a segment that never indexed the field contributes zero, not an error")
  void segmentWithoutTheVectorFieldContributesZero() {
    // A fresh segment holding only vectorless documents: getFloatVectorValues returns null for it,
    // which must be a skip, not a throw and not a miscount of the other segment's vectors.
    index(parent("p5", ROOT + SEP + "e.txt", "PENDING", "PENDING", "PENDING", false));
    index(parent("p6", ROOT + SEP + "f.txt", "PENDING", "PENDING", "PENDING", false));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    VectorPresence after = runtime.indexCountOps().queryVectorPresenceCount();
    assertEquals(2, after.vectorsPresent(), "the vectorless segment adds nothing");
    assertEquals(6, after.totalDocs(), "but its documents still count in the denominator");
  }

  @Test
  @DisplayName("presence is reader-version cached and invalidated by a commit")
  void presenceIsCachedPerReaderVersion() {
    VectorPresence first = runtime.indexCountOps().queryVectorPresenceCount();
    assertSame(first, runtime.indexCountOps().queryVectorPresenceCount());

    index(parent("p7", ROOT + SEP + "g.txt", "COMPLETED", "COMPLETED", "COMPLETED", true));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    VectorPresence third = runtime.indexCountOps().queryVectorPresenceCount();
    assertNotSame(third, first, "a new reader version must invalidate the cached instance");
    assertEquals(3, third.vectorsPresent());
  }

  // ---- queryStageCompletenessCounts ---------------------------------------------

  @Test
  @DisplayName("expected is the field-carrying denominator; success and failure are separate")
  void stageCountsSplitTerminalSuccessFromFailure() {
    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();

    // embedding_status: p1 COMPLETED, p2 COMPLETED, p3 COMPLETED, p4 FAILED.
    assertEquals(4, stages.embedding().expected());
    assertEquals(3, stages.embedding().settledSuccess());
    assertEquals(1, stages.embedding().failed(), "FAILED is NOT folded into settledSuccess");

    // splade_status: p1 COMPLETED, p2 COMPLETED_EMPTY, p3 PENDING, p4 FAILED.
    assertEquals(4, stages.splade().expected());
    assertEquals(
        2, stages.splade().settledSuccess(), "COMPLETED_EMPTY is a terminal SPLADE success");
    assertEquals(1, stages.splade().failed());

    // ner_status: p1 COMPLETED, p2 COMPLETED_EMPTY, p3 COMPLETED, p4 FAILED.
    assertEquals(4, stages.ner().expected());
    assertEquals(3, stages.ner().settledSuccess());
    assertEquals(1, stages.ner().failed(), "the count the wire lacked before 821 §3-C3");

    // chunk_embedding_status is its own scope: 3 chunks, never the parents.
    assertEquals(3, stages.chunkEmbedding().expected(), "parents must not enter the chunk scope");
    assertEquals(1, stages.chunkEmbedding().settledSuccess());
    assertEquals(1, stages.chunkEmbedding().failed());
  }

  @Test
  @DisplayName("a stage's absent status field withdraws the document from its denominator")
  void absentStatusFieldWithdrawsTheDocument() {
    // Post-798: a document the backfill can never select (it selects by status VALUE) must not sit
    // in a denominator forever — otherwise `missing` never reaches zero and the auditor cries wolf.
    index(parent("legacy", ROOT + SEP + "legacy.txt", "COMPLETED", null, null, true));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(5, stages.embedding().expected(), "it DOES carry embedding_status");
    assertEquals(4, stages.embedding().settledSuccess());
    assertEquals(4, stages.splade().expected(), "no splade_status ⇒ outside that denominator");
    assertEquals(4, stages.ner().expected(), "no ner_status ⇒ outside that denominator");
  }

  @Test
  @DisplayName("an empty index reports zeros, never a vacuous complete")
  void emptyIndexReportsZeros() throws Exception {
    runtime.close();
    runtime = openRuntime(tempDir.resolve("index-empty"));

    StageCompletenessCounts stages = runtime.indexCountOps().queryStageCompletenessCounts();
    assertEquals(0, stages.embedding().expected());
    assertEquals(0, stages.chunkEmbedding().expected());
    assertEquals(0, runtime.indexCountOps().queryVectorPresenceCount().totalDocs());
    assertEquals(
        0.0,
        runtime.indexCountOps().queryVectorPresenceCount().coveragePercent(),
        0.001,
        "0/0 is 0%, never a vacuous 100%");
  }

  // ---- fixture ------------------------------------------------------------------

  private void seedCorpus() {
    String a = ROOT + SEP + "a.txt";
    String b = ROOT + SEP + "b.txt";
    String c = ROOT + SEP + "c.txt";
    String d = ROOT + SEP + "d.txt";

    index(parent("p1", a, "COMPLETED", "COMPLETED", "COMPLETED", true));
    index(parent("p2", b, "COMPLETED", "COMPLETED_EMPTY", "COMPLETED_EMPTY", true));
    // The F-032 shape: the status says COMPLETED, the artifact is absent.
    index(parent("p3", c, "COMPLETED", "PENDING", "COMPLETED", false));
    index(parent("p4", d, "FAILED", "FAILED", "FAILED", false));

    index(chunk("c1", a, "COMPLETED"));
    index(chunk("c2", a, "PENDING"));
    index(chunk("c3", b, "FAILED"));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void index(IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  /** A {@code null} status omits the FIELD — the "this stage does not apply" shape (post-798). */
  private static IndexDocument parent(
      String id, String path, String embedding, String splade, String ner, boolean withVector) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, "content of " + id);
    if (embedding != null) {
      fields.put(SchemaFields.EMBEDDING_STATUS, embedding);
    }
    if (splade != null) {
      fields.put(SchemaFields.SPLADE_STATUS, splade);
    }
    if (ner != null) {
      fields.put(SchemaFields.NER_STATUS, ner);
    }
    if (withVector) {
      fields.put(SchemaFields.VECTOR, VEC.clone());
    }
    return new IndexDocument(fields);
  }

  private static IndexDocument chunk(String id, String parentPath, String chunkEmbedding) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, parentPath);
    fields.put(SchemaFields.IS_CHUNK, "true");
    fields.put(SchemaFields.CONTENT, "chunk of " + id);
    fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, chunkEmbedding);
    return new IndexDocument(fields);
  }

  private static RunningRuntime openRuntime(Path indexDir) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "is_chunk", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "chunk_embedding_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "ner_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "roles": ["vector"], "rmwPolicy": "preserve-reread-or-reset:embedding_status", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var fieldMapper = new FieldMapper(new ObjectMapper().readTree(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .atPath(indexDir)
          .open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
