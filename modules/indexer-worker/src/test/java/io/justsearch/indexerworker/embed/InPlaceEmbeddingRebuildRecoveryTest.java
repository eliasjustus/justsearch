/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle;
import io.justsearch.indexerworker.loop.ops.EmbeddingRecoveryOps;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 726 T1 — end-to-end regression for the in-place embedding backfill recovery path, with NO
 * mocked ECC / IndexCountOps / JobQueue / persistence.
 *
 * <p>Reproduces the 0.2.0 sandbox finding A.1: a populated index whose embedding fingerprint was
 * never stamped lands {@code BLOCKED_LEGACY} after a restart, and — with a real job queue holding a
 * job in {@code PROCESSING} (the exact hang shape) — must still recover: refresh → BLOCKED_LEGACY →
 * recovery re-marks the unknown-provenance vectors PENDING and enters REBUILDING → backfill drives
 * pending to 0 → certification (queue-independent, two confirming reads) stamps the fingerprint →
 * close/reopen → a fresh ECC reads the stored fingerprint → COMPATIBLE and {@code
 * allowQueryEmbeddings()==true}.
 *
 * <p>The only thing not real is the embedding compute itself (the vectors are simulated by marking
 * the re-marked docs COMPLETED, as the backfill would). ECC, counts, the SQLite job queue, and the
 * Lucene commit/overlay persistence are all real.
 */
class InPlaceEmbeddingRebuildRecoveryTest {

  private static final String FP = "in-place-recovery-fp-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};
  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(InPlaceEmbeddingRebuildRecoveryTest.class);

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  @Test
  @DisplayName(
      "in-place backfill path: BLOCKED_LEGACY (no fingerprint) recovers to COMPATIBLE across restart"
          + " even with a job stuck in PROCESSING")
  void inPlaceBackfillRecoversDespiteNonIdleQueue() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("in-place-recovery");
    int docCount = 3;

    // Build 1: a legacy generation — parent docs marked embedding COMPLETED, committed WITHOUT a
    // stamped fingerprint (the pre-fix in-place path never certified, so nothing stamped it).
    Supplier<CommitMetadataSource> noStamp =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r1 = openRuntime(dir, noStamp)) {
      for (int i = 0; i < docCount; i++) {
        r1.indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID, "d" + i,
                        SchemaFields.DOC_UID, "d" + i + "#0",
                        SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      }
      r1.commitOps().commitAndTrack();
    }

    // Build 2: "restart". The overlay is late-bound to the ECC exactly as production wires it
    // (KnowledgeServer.embeddingFingerprintSupplier -> ecc::fingerprintToStamp).
    AtomicReference<Supplier<Optional<String>>> fpHolder =
        new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> stamping =
        EmbeddingMetadataOverlay.createSupplier(() -> fpHolder.get().get());

    try (var r2 = openRuntime(dir, stamping);
        SqliteJobQueue jobQueue = new SqliteJobQueue(dir.resolve("jobs.db"))) {
      jobQueue.open();
      // A real job stuck in PROCESSING keeps the global queue non-idle — the exact sandbox hang.
      Path stuck = Files.createTempFile("stuck", ".txt");
      jobQueue.enqueue(List.of(stuck));
      jobQueue.pollPending(1); // -> PROCESSING; never completed
      assertTrue(jobQueue.queueDepth() > 0, "job queue must be non-idle (job left in PROCESSING)");

      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      ecc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "populated index with no stored fingerprint must land BLOCKED_LEGACY after restart");
      fpHolder.set(ecc::fingerprintToStamp);

      // Recovery (F3): re-mark the unknown-provenance COMPLETED docs PENDING, then enter REBUILDING.
      int remarked =
          EmbeddingRecoveryOps.remarkEmbeddedParentDocsPending(
              r2.documentFieldOps(), r2.indexingCoordinator(), 1000, LOG);
      assertEquals(docCount, remarked, "all COMPLETED docs must be re-marked PENDING for re-embed");
      // Commit so the re-marked state is durably visible to count/query reads (the real backfill
      // commits periodically). No fingerprint is stamped: fingerprintToStamp() is empty while
      // REBUILDING is not yet complete.
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking(); // make the commit visible to count/query reads
      assertTrue(
          ecc.maybeAutoStartRebuildForBlockedLegacy(r2.indexCountOps().docCount()),
          "recovery must auto-start REBUILDING regardless of completed/pending distribution");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertEquals(
          docCount,
          r2.indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
          "after re-mark, every parent doc is PENDING");

      // Simulate the backfill embedding all PENDING docs (marks them COMPLETED → pending reaches 0).
      simulateBackfillEmbedAllPending(r2);
      r2.commitOps().commitAndTrack();
      r2.commitOps().maybeRefreshBlocking();
      assertEquals(
          0,
          r2.indexCountOps()
              .countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
          "backfill drove pending to 0");

      // Finalize via the REAL lifecycle with the REAL non-idle queue + REAL counts + REAL commit.
      var lifecycle =
          new EmbeddingProviderLifecycle(
              null /* signalBus unused by tryFinalizeRebuild */,
              jobQueue,
              r2.indexCountOps(),
              r2.commitOps());
      lifecycle.setEmbeddingCompatController(ecc);

      // First finalize: pending==0 first-of-two confirming reads — must NOT certify yet.
      assertFalse(lifecycle.tryFinalizeRebuild(), "first pending==0 read must not certify (debounce)");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());

      // Second finalize: second consecutive pending==0 read — certifies + stamps despite queue>0.
      assertTrue(
          lifecycle.tryFinalizeRebuild(),
          "second confirming read must certify even though the job queue is non-idle");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
      assertTrue(ecc.allowQueryEmbeddings(), "COMPATIBLE must allow query embeddings");
    }

    // Build 3: second "restart" — a fresh ECC over the reopened index must read the stamped
    // fingerprint from commit user-data and resolve COMPATIBLE (durability across restart).
    try (var r3 = openRuntime(dir, EmbeddingMetadataOverlay.createSupplier(Optional::empty))) {
      var ecc3 =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      ecc3.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc3.state(),
          "the stamped fingerprint must survive reopen → COMPATIBLE, not revert to BLOCKED_LEGACY");
      assertTrue(ecc3.allowQueryEmbeddings());
    }
  }

  private static io.justsearch.adapters.lucene.runtime.RunningRuntime openRuntime(
      Path dir, Supplier<CommitMetadataSource> commitMetadata) {
    return io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
            FieldCatalogDef.forTesting(768), commitMetadata, PERMISSIVE)
        .atPath(dir)
        .open();
  }

  /** Marks every currently-PENDING parent doc COMPLETED, as the embedding backfill would. */
  private static void simulateBackfillEmbedAllPending(
      io.justsearch.adapters.lucene.runtime.RunningRuntime r) {
    List<String> pending =
        r.documentFieldOps()
            .queryDocIdsByField(
                SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING, 1000);
    List<Map.Entry<String, Map<String, Object>>> updates = new ArrayList<>(pending.size());
    for (String id : pending) {
      updates.add(
          Map.entry(
              id,
              Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED)));
    }
    r.indexingCoordinator().updateDocumentsBatch(updates);
  }
}
