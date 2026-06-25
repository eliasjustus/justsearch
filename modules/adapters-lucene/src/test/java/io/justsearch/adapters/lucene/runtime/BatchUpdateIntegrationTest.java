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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

class BatchUpdateIntegrationTest {

  @Test
  void updateDocumentsBatchUpdatesAllDocs() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      // Index 3 documents with SPLADE_STATUS=PENDING
      for (int i = 0; i < 3; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-" + i,
                    SchemaFields.DOC_UID, "doc-" + i + "#0",
                    SchemaFields.PATH, "test/doc-" + i + ".txt",
                    SchemaFields.CONTENT, "content " + i,
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Batch-update all 3 to COMPLETED
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
        batchUpdates.add(Map.entry("doc-" + i, updates));
      }
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);

      assertEquals(3, result.updatedCount());
      assertEquals(0, result.notFoundCount());

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify all 3 now have COMPLETED status
      for (int i = 0; i < 3; i++) {
        String status = runtime.documentFieldOps().getDocumentField("doc-" + i, SchemaFields.SPLADE_STATUS);
        assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, status, "doc-" + i);
      }

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocumentsBatchReturnsCorrectCounts() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      // Index only 2 documents
      for (int i = 0; i < 2; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-" + i,
                    SchemaFields.DOC_UID, "doc-" + i + "#0",
                    SchemaFields.PATH, "test/doc-" + i + ".txt",
                    SchemaFields.CONTENT, "content " + i,
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Batch-update 3 entries (2 existing + 1 non-existent)
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
        batchUpdates.add(Map.entry("doc-" + i, updates));
      }
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);

      assertEquals(2, result.updatedCount());
      assertEquals(1, result.notFoundCount());

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocumentsBatchPreservesUnchangedFields() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "important content",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Update only SPLADE_STATUS
      List<Map.Entry<String, Map<String, Object>>> batchUpdates =
          List.of(
              Map.entry(
                  "doc-0",
                  Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED)));
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);

      assertEquals(1, result.updatedCount());

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify content and path are preserved
      String content = runtime.documentFieldOps().getDocumentContent("doc-0");
      assertEquals("important content", content);
      String path = runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.PATH);
      assertEquals("test/doc-0.txt", path);
      String status = runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, status);

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocumentsBatchNullAndEmptyReturnZero() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      var nullResult = runtime.indexingCoordinator().updateDocumentsBatch(null);
      assertEquals(0, nullResult.updatedCount());
      assertEquals(0, nullResult.notFoundCount());

      var emptyResult = runtime.indexingCoordinator().updateDocumentsBatch(List.of());
      assertEquals(0, emptyResult.updatedCount());
      assertEquals(0, emptyResult.notFoundCount());

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocument_refreshesBeforeRead() throws Exception {
    // Tests that updateDocument performs its own refresh so a previously committed doc is found.
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-updaterefresh-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      // Index a doc and commit (but do NOT call maybeRefreshBlocking)
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-refresh-1",
                  SchemaFields.DOC_UID, "doc-refresh-1#0",
                  SchemaFields.PATH, "test/doc-refresh-1.txt",
                  SchemaFields.CONTENT, "original content",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      runtime.commitOps().commitAndTrack();
      // Deliberately skip maybeRefreshBlocking here

      // updateDocument must do its own refresh — the doc should be found and updated
      boolean updated = runtime.indexingCoordinator().updateDocument(
          "doc-refresh-1",
          Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
      assertTrue(updated, "updateDocument should find and update the committed doc without an explicit refresh");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /**
   * Regression: a RMW with {@code preserveSplade=true} must NOT silently drop
   * {@code splade_status} even when the field is {@code stored:false, docValues:true}
   * (production schema). Before the fix, NER backfill (which calls preserveSplade=true
   * with no SPLADE fields in updates) caused the status to be lost, leaving docs
   * invisible to both the PENDING backfill query and the COMPLETED counter — SPLADE
   * coverage stalled below 100 % forever on any corpus with NER-processed parents.
   */
  @Test
  void preserveSpladeTruePreservesSpladeStatusWhenNonStored() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-preserve-splade-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      // Primary-index a doc with splade_status=PENDING, then simulate SPLADE backfill
      // marking it COMPLETED.
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content that has been SPLADE-encoded",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
                  SchemaFields.SPLADE_RETRY_COUNT, "0")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Simulate a NER backfill: preserveSplade=true, no SPLADE fields in updates.
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0",
              Map.of("entity_persons_raw", "Alice"),
              true);
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Before the fix, getDocumentField would return null here because splade_status
      // was silently dropped during the RMW rewrite. After the fix, the previous
      // COMPLETED value is restored from doc-values and survives the write.
      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_COMPLETED,
          statusAfter,
          "splade_status must survive a preserveSplade=true RMW even when stored=false");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- Tempdoc 393 item 1.3: RMW state-matrix coverage ----
  //
  // The fix at WritePathOps.readModifyWrite has three branches that interact with
  // splade_status (lines 306-321 doc-values restore; 329-334 reset-to-PENDING;
  // 340-345 safety-net PENDING). The existing test covers only one cell of the
  // {caller-provides × doc-has × preserveSplade} matrix. These tests cover the
  // remaining critical cells — especially the FAILED-status preservation case,
  // since resurrecting permanently-failed docs as PENDING would mask real
  // failures and waste encoder cycles re-trying.

  /** caller=N, doc=FAILED, preserveSplade=T — HIGHEST PRIORITY: FAILED must survive. */
  @Test
  void preserveSpladeTruePreservesFailedStatusWhenNonStored() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-preserve-failed-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content that permanently failed SPLADE",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_FAILED,
                  SchemaFields.SPLADE_RETRY_COUNT, "5")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0",
              Map.of("entity_persons_raw", "Alice"),
              true);
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_FAILED,
          statusAfter,
          "FAILED status must survive preserveSplade=true RMW — resurrecting FAILED "
              + "as PENDING would mask real failures and waste encoder cycles");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** caller=Y, doc=COMPLETED, preserveSplade=T — caller's explicit value must win. */
  @Test
  void callerProvidedStatusOverridesDocValuesRestore() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-caller-override-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Caller explicitly supplies SPLADE_STATUS=PENDING (e.g., content changed,
      // needs re-encoding). Must override the COMPLETED value from doc-values.
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0",
              Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING),
              true);
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "Caller-supplied SPLADE_STATUS must override the doc-values restore");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** caller=N, doc=COMPLETED, preserveSplade=F — reset-to-PENDING branch must fire. */
  @Test
  void preserveSpladeFalseResetsCompletedToPending() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-reset-branch-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
                  SchemaFields.SPLADE_RETRY_COUNT, "0")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // preserveSplade=false (default) + no SPLADE_STATUS in updates → reset branch.
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0",
              Map.of("entity_persons_raw", "Alice"),
              false);
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "preserveSplade=false must reset COMPLETED to PENDING so backfill re-encodes");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** caller=N, doc=none, preserveSplade=T — safety-net must heal to PENDING. */
  @Test
  void safetyNetInjectsPendingWhenStatusMissingEverywhere() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-safety-net-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      // Simulate a pre-fix corrupted doc: index WITHOUT splade_status. Under current
      // ingest paths this never happens (IndexingDocumentOps.java:225 always sets
      // PENDING), but the safety net's purpose is to heal docs that got corrupted
      // by a prior RMW run before the fix landed.
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, "doc-0");
      fields.put(SchemaFields.DOC_UID, "doc-0#0");
      fields.put(SchemaFields.PATH, "test/doc-0.txt");
      fields.put(SchemaFields.CONTENT, "corrupted doc with no splade_status");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // RMW with preserveSplade=true; neither updates map nor doc-values has status.
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0",
              Map.of("entity_persons_raw", "Alice"),
              true);
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "Safety net must inject PENDING so healed docs become visible to backfill");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** caller=N, doc=COMPLETED, preserveSplade=T — COMPLETED must survive (common NER backfill case). */
  @Test
  void preserveSpladeTruePreservesCompletedStatusWhenNonStored() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-preserve-completed-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      // Common production shape: SPLADE has successfully completed; NER backfill
      // subsequently RMWs the doc with preserveSplade=true. The doc-values restore
      // must preserve COMPLETED so the doc doesn't re-enter the PENDING queue.
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content that was SPLADE-completed",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
                  SchemaFields.SPLADE_RETRY_COUNT, "0")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0",
              Map.of("entity_persons_raw", "Alice"),
              true);
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_COMPLETED,
          statusAfter,
          "COMPLETED status must survive NER backfill RMW — otherwise the doc "
              + "bounces back to PENDING and the backfill re-encodes unnecessarily");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- Tempdoc 393 item 1.4: concurrent-writer race reproducer ----
  //
  // WritePathOps.readModifyWrite reads the doc via IndexSearcher and writes via
  // IndexWriter. The sequence is not atomic. Two concurrent RMW calls on the same
  // docId that update different fields can interleave as
  // {read_A → read_B → write_A → write_B}, silently losing write_A's update.
  //
  // This test reproduces the race in-process by racing two threads on the same
  // docId with orthogonal field updates. Both writes should survive; a surviving
  // single write is a lost-update race hit.
  //
  // REGRESSION GATE (tempdoc 402): after the coordinator landed, this test asserts
  // no lost updates occur when two threads race on the same docId with orthogonal
  // field updates. Both writes MUST survive every iteration — if `lostUpdates > 0`,
  // the single-writer invariant (402 §1.4 fix) has regressed. The formerly-diagnostic
  // reproducer is now a hard gate against re-introducing direct WritePathOps callers
  // or breaking the dispatchLock serialization.

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void concurrentRmwOnSameDocIdSerializedByCoordinator_402() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-rmw-race-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithTwoOrthogonalFields();

      final int iterations = 50;
      AtomicInteger lostUpdates = new AtomicInteger(0);
      AtomicInteger writeFailures = new AtomicInteger(0);

      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        for (int i = 0; i < iterations; i++) {
          String docId = "doc-" + i;
          // Seed the doc with both fields empty.
          runtime.indexingCoordinator().indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, docId,
                      SchemaFields.DOC_UID, docId + "#0",
                      SchemaFields.PATH, "test/" + docId + ".txt",
                      SchemaFields.CONTENT, "seed " + i,
                      SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();

          final String docIdFinal = docId;
          CountDownLatch ready = new CountDownLatch(2);
          CountDownLatch go = new CountDownLatch(1);
          CountDownLatch done = new CountDownLatch(2);

          // Thread A writes field_a="A"; Thread B writes field_b="B". Both use
          // preserveSplade=true to match the NER backfill shape. Barrier ensures
          // both threads are poised at updateDocument before releasing.
          Runnable writerA = () -> {
            try {
              ready.countDown();
              go.await();
              runtime.indexingCoordinator().updateDocument(docIdFinal, Map.of("field_a", "A"), true);
            } catch (Exception e) {
              writeFailures.incrementAndGet();
            } finally {
              done.countDown();
            }
          };
          Runnable writerB = () -> {
            try {
              ready.countDown();
              go.await();
              runtime.indexingCoordinator().updateDocument(docIdFinal, Map.of("field_b", "B"), true);
            } catch (Exception e) {
              writeFailures.incrementAndGet();
            } finally {
              done.countDown();
            }
          };

          pool.submit(writerA);
          pool.submit(writerB);
          ready.await();
          go.countDown();
          assertTrue(done.await(10, TimeUnit.SECONDS), "writers hung on iteration " + i);

          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();

          String fieldA = runtime.documentFieldOps().getDocumentField(docIdFinal, "field_a");
          String fieldB = runtime.documentFieldOps().getDocumentField(docIdFinal, "field_b");
          boolean aPresent = "A".equals(fieldA);
          boolean bPresent = "B".equals(fieldB);
          if (!(aPresent && bPresent)) {
            lostUpdates.incrementAndGet();
          }
        }
      } finally {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
      }

      // Regression gate (tempdoc 402): the coordinator's dispatchLock serializes
      // same-docId RMW, so BOTH writes must survive every iteration.
      assertEquals(0, writeFailures.get(), "unexpected writer exceptions");
      assertEquals(
          0,
          lostUpdates.get(),
          "coordinator must prevent concurrent-RMW lost updates — "
              + lostUpdates.get()
              + "/"
              + iterations
              + " iterations lost one of the two writes; single-writer invariant regressed");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- helpers ----

  private Path writeTestConfig(Path base) throws Exception {
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: batchupdatetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    return cfg;
  }

  private RunningRuntime createRuntime() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new io.justsearch.adapters.lucene.runtime.IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Production-matching schema: {@code splade_status} and {@code splade_retry_count} are
   * {@code stored:false, docValues:true}, so they do not survive a stored-field read during
   * a read-modify-write cycle. This is the schema that exposes the tempdoc 334-era bug.
   */
  private RunningRuntime createRuntimeWithNonStoredSpladeStatus() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
              { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new io.justsearch.adapters.lucene.runtime.IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Schema for tempdoc 393 item 1.4 race reproducer: same production-matching
   * non-stored splade_status, plus two orthogonal keyword fields (field_a,
   * field_b) that two threads can write concurrently without colliding on the
   * same key. If both writes survive, no race; if only one survives, race hit.
   */
  private RunningRuntime createRuntimeWithTwoOrthogonalFields() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
              { "id": "field_a", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "field_b", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new io.justsearch.adapters.lucene.runtime.IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().open();
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
