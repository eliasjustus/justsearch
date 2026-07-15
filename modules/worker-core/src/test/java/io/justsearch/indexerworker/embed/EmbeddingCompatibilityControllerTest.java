package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void maybeAutoStartRebuildForBlockedLegacyTransitionsToRebuilding() throws Exception {
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

    boolean started = controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());
  }

  @Test
  void maybeAutoStartRebuildForBlockedLegacyFiresRegardlessOfDistribution() throws Exception {
    // Tempdoc 726 T3 (pins F3 at the controller level): a fully-embedded-but-unstamped index
    // (documents COMPLETED, none PENDING) is exactly what the pre-fix in-place backfill path leaves
    // after a restart. Recovery must STILL auto-start REBUILDING — the former all-pending-only guard
    // (completed==0 && failed==0 && pending==docCount) refused, stranding it in BLOCKED_LEGACY.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());

    // docCount>0 but NOT all-pending (the recovery caller re-marks COMPLETED/FAILED docs PENDING
    // before/around this; the controller only needs to know there are documents to (re-)embed).
    boolean started = controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
  }

  @Test
  void maybeAutoStartRebuildForBlockedLegacyGuardsEmptyIndex() throws Exception {
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());

    // No documents → nothing to rebuild → stays BLOCKED_LEGACY.
    assertFalse(controller.maybeAutoStartRebuildForBlockedLegacy(0L));
    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
  }

  @Test
  void refreshWhileRebuildingIsIgnored() throws Exception {
    // 734: refresh() re-derives state unconditionally from the fingerprint suppliers, but the
    // stored fingerprint isn't stamped until certification — so a mid-rebuild refresh() call
    // would re-derive BLOCKED_LEGACY (storedFp still null/stale) and silently clobber the
    // in-flight rebuild. Guard: refresh() while REBUILDING is a no-op.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());

    controller.refresh();

    assertEquals(
        EmbeddingCompatibilityController.State.REBUILDING,
        controller.state(),
        "refresh() must not clobber an in-flight rebuild");
    assertEquals(
        "REBUILD_IN_PROGRESS",
        controller.reasonCode(),
        "reason must stay REBUILD_IN_PROGRESS, not re-derive BLOCKED_LEGACY's reason");
  }

  @Test
  void checkRebuildCompletionCertifiesOnPendingZeroEvenWhenJobQueueNonEmpty() throws Exception {
    // Tempdoc 726 T2 (pins F1): a job stuck in PROCESSING — or any ongoing unrelated ingestion —
    // keeps the global queueDepth > 0. Certification must NOT depend on it: pending_embedding==0
    // alone certifies. On the OLD code (`queueDepth==0 && pendingEmbeddingCount==0`) this call with
    // queueDepth=7 returned false and REBUILDING persisted forever (the sandbox hang, reproduced 3x).
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");
    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());

    boolean completed = controller.checkRebuildCompletion(7L /* non-empty job queue */, 0);

    assertTrue(completed);
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertEquals("fake-sha256-for-test", controller.fingerprintToStamp().orElse(null));
  }

  @Test
  void autoRescueTagsTheDiagnosticReasonForOperators() throws Exception {
    // Tempdoc 730 A4 telemetry: the corruption signature — completed > 0 (dense vectors already
    // exist) but no embedding fingerprint was ever persisted — must fire the rescue AND record a
    // diagnostic tag naming why it fired. That the rescue fires on this distribution is covered by
    // maybeAutoStartRebuildForBlockedLegacyFiresRegardlessOfDistribution above; what this pins is
    // the operator-facing tag, which is the surviving half of main's unattested-vectors test.
    EmbeddingFingerprint.setForTesting("fake-sha256-for-test");

    EmbeddingCompatibilityController controller =
        new EmbeddingCompatibilityController(Map::of, () -> 5L);
    controller.refresh();

    assertEquals(EmbeddingCompatibilityController.State.BLOCKED_LEGACY, controller.state());
    assertEquals("LEGACY_INDEX_NO_FINGERPRINT", controller.reasonCode());

    boolean started = controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    assertTrue(started);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    // The shared, contract-stable reason for query-time degradation messaging is unchanged — only
    // the diagnostic-only lastAutoRescueReason() tag names the auto-rescue path.
    assertEquals("REBUILD_IN_PROGRESS", controller.reasonCode());
    assertEquals("legacy_no_fingerprint", controller.lastAutoRescueReason());
  }

  @Test
  void autoRescueDoesNotFireOnProperlyStampedGeneration() throws Exception {
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

    assertFalse(controller.maybeAutoStartRebuildForBlockedLegacy(5L));
    assertEquals(EmbeddingCompatibilityController.State.COMPATIBLE, controller.state());
    assertNull(controller.lastAutoRescueReason());
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
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);
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
    controller.maybeAutoStartRebuildForBlockedLegacy(5L);

    boolean completed = controller.checkRebuildCompletion(0L, 3); // 3 docs still pending embeddings

    assertFalse(completed);
    assertEquals(EmbeddingCompatibilityController.State.REBUILDING, controller.state());
    assertTrue(controller.fingerprintToStamp().isEmpty());
  }
}
