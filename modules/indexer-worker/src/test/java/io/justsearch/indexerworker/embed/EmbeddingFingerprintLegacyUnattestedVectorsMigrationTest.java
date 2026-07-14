package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 730 Increment 3 (A4 migration): regression test for the on-disk corruption signature
 * reproduced by the live-validation run {@code g-20260714-134648} (§THEORIZE A) — dense vectors
 * already exist (a doc's {@code embedding_status} is {@code COMPLETED}), the SPLADE fingerprint is
 * stamped (unconditional supplier), but the embedding fingerprint is ABSENT from commit userData
 * (the pre-fix state-gated supplier withheld it). Per the orchestrator-resolved semantics, the
 * migration must NOT back-stamp the current fingerprint over these vectors — that would fabricate
 * provenance for vectors we cannot prove came from the current model. Instead {@code
 * KnowledgeServer}'s startup/refresh call site must detect the signature and auto-start a real
 * re-embed via {@link EmbeddingCompatibilityController#maybeAutoStartRebuildForLegacyUnattestedVectors},
 * which earns a legitimate stamp on completion through the same completion-guarantee commit path
 * {@code EmbeddingFingerprintProductionWiringDurabilityTest} already covers for Increments 1/2.
 *
 * <p>Harness idioms (two-phase late-binding overlay, {@code IndexSchema.fromCatalog(...).atPath
 * (dir).open()}, {@code commitAndTrack}, {@code latestCommitUserDataBestEffort}) are copied from
 * {@code EmbeddingFingerprintProductionWiringDurabilityTest} per that class's own template role
 * named in tempdoc 730's DERISK section.
 */
class EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest {

  private static final String FP = "unattested-migration-embed-fp-sha256";
  private static final String SPLADE_FP = "unattested-migration-splade-fp-sha256";
  private static final CommitMetadataValidator PERMISSIVE = metadata -> {};

  @AfterEach
  void clearFingerprint() {
    EmbeddingFingerprint.invalidate();
  }

  @Test
  @DisplayName(
      "Positive: on-disk signature (dense vectors attested via embedding_status=COMPLETED, "
          + "SPLADE fp stamped, embedding fp ABSENT -- mirrors g-20260714-134648) is detected on "
          + "reopen, auto-starts REBUILDING via the unattested-vectors rescue (not back-stamped), "
          + "and drives to a legitimately-earned COMPATIBLE stamp on completion")
  void legacyUnattestedVectorsSignatureAutoRescuesToCompatible() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unattested-vectors-migration");

    // Build 1: seed the corrupted generation directly -- SPLADE fp stamped unconditionally, the
    // embedding fp NEVER offered (Optional::empty throughout), mirroring the asymmetric-stamping-
    // gate ratchet's on-disk signature exactly. The doc is marked embedding_status=COMPLETED,
    // i.e. it already carries a (dense-vector) embedding -- the "vectors exist" evidence.
    Supplier<CommitMetadataSource> spladeOnly =
        EmbeddingMetadataOverlay.createSupplier(Optional::empty, () -> Optional.of(SPLADE_FP));
    try (var r1 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), spladeOnly, PERMISSIVE)
            .atPath(dir)
            .open()) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      r1.commitOps().commitAndTrack();
    }

    // Build 2 ("worker restart"): reopen with the production-wired two-phase overlay -- identical
    // in shape to KnowledgeServer.java:485-486 + the embedding-supplier set-site.
    AtomicReference<Supplier<Optional<String>>> fpSupplierRef = new AtomicReference<>(Optional::empty);
    Supplier<CommitMetadataSource> productionWiredOverlay =
        EmbeddingMetadataOverlay.createSupplier(
            () -> fpSupplierRef.get().get(), () -> Optional.of(SPLADE_FP));

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), productionWiredOverlay, PERMISSIVE)
            .atPath(dir)
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      // Production wiring, post-A1-revert: KnowledgeServer.java:1022-1023.
      fpSupplierRef.set(ecc::fingerprintToStamp);
      ecc.refresh();

      // Sanity: this is the exact on-disk signature from §THEORIZE A -- SPLADE fp present,
      // embedding fp absent, docCount > 0 => BLOCKED_LEGACY.
      assertEquals(
          EmbeddingCompatibilityController.State.BLOCKED_LEGACY,
          ecc.state(),
          "sanity: the corrupted generation from Build 1 must open as BLOCKED_LEGACY");
      assertEquals("LEGACY_INDEX_NO_FINGERPRINT", ecc.reasonCode());

      // Mirror KnowledgeServer's count query (excluding chunks -- none here) that feeds the
      // auto-rescue call site (maybeAutoStartEmbeddingRebuildAllPendingBestEffort).
      var countOps = r2.indexCountOps();
      long docCount = countOps.docCount();
      int completed =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      int pending =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      int failed =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
      assertEquals(1L, docCount, "sanity: one doc, matching the reproduced generation's shape");
      assertTrue(completed > 0, "sanity: the doc must carry the 'vectors exist' evidence");

      // The all-pending rescue must refuse -- completed > 0, not the all-pending case its
      // completed == 0 guard exists for.
      assertFalse(ecc.maybeAutoStartRebuildForLegacyAllPending(docCount, pending, completed, failed));
      assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());

      // The unattested-vectors rescue must fire instead -- this is the KnowledgeServer-shaped
      // call-site fallback wired in this increment.
      boolean started = ecc.maybeAutoStartRebuildForLegacyUnattestedVectors(docCount, completed);
      assertTrue(started, "the auto-rescue must detect the signature and start REBUILDING");
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertEquals(
          "embedding_legacy_unattested_vectors",
          ecc.lastAutoRescueReason(),
          "operators must be able to tell this rescue apart from the all-pending one");

      // Drive the rebuild to completion (mirrors EmbeddingProviderLifecycle.tryFinalizeRebuild():
      // a forced reindex is in-place/incremental -- no wipe -- so re-indexing d1 models the
      // re-embed write that earns the new stamp).
      r2.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_COMPLETED)));

      boolean completedRebuild = ecc.checkRebuildCompletion(0, 0);
      assertTrue(completedRebuild, "sanity: queue==0 && pending==0 must report completion");
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());

      // The completion-guarantee commit -- the same commitAndTrack + onFingerprintStamped pair
      // EmbeddingProviderLifecycle.tryFinalizeRebuild() issues -- is what actually earns and
      // persists the stamp. This is NOT a back-stamp of the pre-existing (Build 1) vectors: the
      // fingerprint now describes the vectors this rebuild just wrote.
      r2.commitOps().commitAndTrack();
      ecc.onFingerprintStamped();
    }

    // Build 3 ("second restart"): the migration must be self-terminating -- the generation now
    // resolves COMPATIBLE with a legitimately-earned fingerprint, not a fabricated one.
    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r3 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir)
            .open()) {
      var freshEcc =
          new EmbeddingCompatibilityController(
              r3::latestCommitUserDataBestEffort, () -> r3.indexCountOps().docCount());
      freshEcc.refresh();
      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          freshEcc.state(),
          "the migrated generation must survive reopen as COMPATIBLE, not re-flag BLOCKED_LEGACY");
      assertEquals(FP, freshEcc.storedFingerprint());
    }
  }

  @Test
  @DisplayName(
      "Negative: a properly-stamped generation (embedding fp already persisted and matching the "
          + "current model) must NOT trigger the unattested-vectors auto-rescue")
  void properlyStampedGenerationDoesNotTriggerAutoRescue() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unattested-vectors-negative-stamped");

    Supplier<CommitMetadataSource> stamped =
        EmbeddingMetadataOverlay.createSupplier(() -> Optional.of(FP), () -> Optional.of(SPLADE_FP));
    try (var r1 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), stamped, PERMISSIVE)
            .atPath(dir)
            .open()) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_COMPLETED)));
      r1.commitOps().commitAndTrack();
    }

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), stamped, PERMISSIVE)
            .atPath(dir)
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      ecc.refresh();

      assertEquals(
          EmbeddingCompatibilityController.State.COMPATIBLE,
          ecc.state(),
          "sanity: a properly-stamped generation must resolve COMPATIBLE, never BLOCKED_LEGACY");

      var countOps = r2.indexCountOps();
      int completed =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      assertFalse(ecc.maybeAutoStartRebuildForLegacyUnattestedVectors(countOps.docCount(), completed));
      assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, ecc.state());
    }
  }

  @Test
  @DisplayName(
      "Negative: a genuinely-empty (all-pending) generation still uses the original "
          + "maybeAutoStartRebuildForLegacyAllPending rescue path, not the unattested-vectors one")
  void allPendingGenerationStillUsesOriginalRescuePath() throws Exception {
    EmbeddingFingerprint.setForTesting(FP);
    Path dir = Files.createTempDirectory("embed-fp-unattested-vectors-negative-allpending");

    Supplier<CommitMetadataSource> noStamp = EmbeddingMetadataOverlay.createSupplier(Optional::empty);
    try (var r1 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir)
            .open()) {
      r1.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "d1",
                      SchemaFields.DOC_UID,
                      "d1#0",
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_PENDING)));
      r1.commitOps().commitAndTrack();
    }

    try (var r2 =
        io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), noStamp, PERMISSIVE)
            .atPath(dir)
            .open()) {
      var ecc =
          new EmbeddingCompatibilityController(
              r2::latestCommitUserDataBestEffort, () -> r2.indexCountOps().docCount());
      ecc.refresh();
      assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());

      var countOps = r2.indexCountOps();
      long docCount = countOps.docCount();
      int pending =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      int completed =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
      int failed =
          countOps.countByField(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
      assertEquals(0, completed, "sanity: nothing has been embedded yet");

      // The unattested-vectors rescue must refuse -- no "vectors exist" evidence.
      assertFalse(ecc.maybeAutoStartRebuildForLegacyUnattestedVectors(docCount, completed));
      assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, ecc.state());

      // The original all-pending rescue must fire.
      boolean started =
          ecc.maybeAutoStartRebuildForLegacyAllPending(docCount, pending, completed, failed);
      assertTrue(started);
      assertEquals(EmbeddingCompatibilityController.State.REBUILDING, ecc.state());
      assertEquals("legacy_all_pending", ecc.lastAutoRescueReason());
    }
  }
}
