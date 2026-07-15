package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddingCompatibilityControllerTest {

  @TempDir Path tempDir;

  private ConfigStore prevStore;

  @BeforeEach
  void setUpConfigStore() {
    prevStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void tearDown() {
    EmbeddingFingerprint.invalidate();
    TestResolvedConfigHelper.restoreGlobal(prevStore);
  }

  @Test
  void maybeAutoStartRebuildForLegacyAllPendingTransitionsToRebuilding() throws Exception {
    // Inject a fake fingerprint so the controller sees embedding as "available" without
    // needing a real model file on disk. Without this, EmbeddingFingerprint.get() returns
    // empty and the controller goes to UNAVAILABLE instead of BLOCKED_LEGACY.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
    assertEquals("LEGACY_INDEX_NO_FINGERPRINT", controller.reasonCode());

    // Act: prove all docs are pending embeddings
    boolean started = controller.maybeAutoStartRebuildForLegacyAllPending(5L, 5, 0, 0);

    // Assert
    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());
  }

  @Test
  void maybeAutoStartRebuildForLegacyAllPendingIsConservative() throws Exception {
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L);
    controller.refresh();

    // Not all pending => should not start
    assertFalse(controller.maybeAutoStartRebuildForLegacyAllPending(5L, 4, 0, 0));
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());

    // Completed exists => should not start (this is the complement signature, handled by
    // maybeAutoStartRebuildForLegacyUnattestedVectors instead — see the test below).
    assertFalse(controller.maybeAutoStartRebuildForLegacyAllPending(5L, 5, 1, 0));
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
  }

  @Test
  void maybeAutoStartRebuildForLegacyUnattestedVectorsTransitionsToRebuilding() throws Exception {
    // Tempdoc 730 A4: the corruption-signature complement of the all-pending case above —
    // completed > 0 (dense vectors already exist/attested) but no embedding fingerprint was ever
    // persisted. maybeAutoStartRebuildForLegacyAllPending refuses this (completed != 0 guard);
    // this dedicated method must fire instead.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
    assertEquals("LEGACY_INDEX_NO_FINGERPRINT", controller.reasonCode());
    assertFalse(controller.maybeAutoStartRebuildForLegacyAllPending(5L, 0, 5, 0));

    boolean started = controller.maybeAutoStartRebuildForLegacyUnattestedVectors(5L, 5);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    // The shared, contract-stable reason for query-time degradation messaging is unchanged —
    // only the diagnostic-only lastAutoRescueReason() tag distinguishes the two rescue paths.
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());
    assertEquals("embedding_legacy_unattested_vectors", controller.lastAutoRescueReason());
  }

  @Test
  void maybeAutoStartRebuildForLegacyUnattestedVectorsIsConservative() throws Exception {
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            Map::of,
            () -> 5L);
    controller.refresh();

    // No completed embeddings => no "vectors exist" evidence => should not start.
    assertFalse(controller.maybeAutoStartRebuildForLegacyUnattestedVectors(5L, 0));
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());

    // docCount <= 0 => should not start regardless of completed.
    assertFalse(controller.maybeAutoStartRebuildForLegacyUnattestedVectors(0L, 5));
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
  }

  @Test
  void maybeAutoStartRebuildForLegacyUnattestedVectorsDoesNotFireOnProperlyStampedGeneration()
      throws Exception {
    // Negative control: a generation whose embedding fingerprint IS already persisted and
    // matches the current model must resolve COMPATIBLE at refresh() — the auto-rescue must be a
    // no-op since state() is never BLOCKED_LEGACY in the first place.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(
            () -> Map.of(
                EmbeddingCompatibilityController.COMMIT_META_KEY, "fake-sha256-for-test"),
            () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());

    assertFalse(controller.maybeAutoStartRebuildForLegacyUnattestedVectors(5L, 5));
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals(null, controller.lastAutoRescueReason());
  }

  @Test
  void checkRebuildCompletionFinalizesRebuildAndEnablesFingerprintStamp() throws Exception {
    // Tempdoc 598 review Fix E: the migration cutover calls checkRebuildCompletion BEFORE the green
    // COMPLETE commit. On a fully-drained green (queue=0, pending=0) it must flip COMPATIBLE so
    // fingerprintToStamp() returns the fp — i.e. the COMPLETE commit deterministically stamps
    // embedding_model_sha256 (no race with the indexing-loop thread).
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    controller.maybeAutoStartRebuildForLegacyAllPending(5L, 5, 0, 0);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    // While REBUILDING-not-yet-complete, there is nothing to stamp.
    assertTrue(controller.fingerprintToStamp().isEmpty());

    boolean completed = controller.checkRebuildCompletion(0L, 0);

    assertTrue(completed);
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void checkRebuildCompletionDoesNotFinalizeWhenEmbeddingsStillPending() throws Exception {
    // Fix E guard: a green that is NOT fully embedded must not flip COMPATIBLE, so its COMPLETE
    // commit lacks the fingerprint and the cutover verification correctly blocks promotion.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    controller.maybeAutoStartRebuildForLegacyAllPending(5L, 5, 0, 0);

    boolean completed = controller.checkRebuildCompletion(0L, 3); // 3 docs still pending embeddings

    assertFalse(completed);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertTrue(controller.fingerprintToStamp().isEmpty());
  }
}
