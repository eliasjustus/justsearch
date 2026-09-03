package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C — the blue/green state machine under the new production default.
 *
 * <p>Making {@code BLUE_GREEN_MIGRATE} the prod default turns "rebuild the index" from something a
 * user asks for into something the Worker does by itself on boot. Two properties then have to hold
 * or the change is worse than what it replaced: the rebuild must not cost the user their working
 * index while it runs, and it must stop after a bounded number of failures instead of looping.
 */
final class IndexRebuildBrakeTest {

  private static final String TARGET_A = "fingerprint-a";
  private static final String TARGET_B = "fingerprint-b";

  /**
   * The whole point of blue/green: starting a migration leaves {@code active_generation} — the one
   * search still reads from — exactly where it was, and only the promotion moves it. If
   * {@code startMigration} ever repointed active, users would lose search for the duration of a
   * rebuild, which is the failure mode {@code FAIL_CLOSED} used to cause differently.
   */
  @Test
  void migrationBuildsGreenBesideBlueAndOnlyPromotionSwitches(@TempDir Path tempDir)
      throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    var layout = mgr.initializeOrLoad();
    String blue = layout.state().active_generation();
    assertNotNull(blue, "an active generation exists before migration");

    IndexGenerationManager.State migrating = mgr.startMigration("schema_mismatch");
    assertEquals(
        blue,
        migrating.active_generation(),
        "Blue must keep serving: startMigration may not repoint active_generation");
    String green = migrating.building_generation();
    assertNotNull(green, "a Green generation id was allocated");
    assertNotEquals(blue, green, "Green is a new generation, not the one being served");
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(), migrating.migration_state());
    assertTrue(
        Files.isDirectory(mgr.resolveGenerationPathStrict(green)),
        "Green's directory exists so the writable runtime can open it");

    IndexGenerationManager.State promoted = mgr.promoteBuildingGenerationToActive();
    assertEquals(green, promoted.active_generation(), "cutover switches active to Green");
    assertEquals(blue, promoted.previous_generation(), "Blue is retained for rollback");
    assertNull(promoted.building_generation(), "no build is in flight after the switch");
    assertEquals(IndexGenerationManager.MigrationState.IDLE.name(), promoted.migration_state());
  }

  /**
   * A corrupt index presents the same mismatch on every boot. Without a bound the Worker would
   * rebuild forever; the brake counts attempts per target fingerprint and hands over to an operator
   * once the budget is spent.
   */
  @Test
  void repeatRebuildsForTheSameTargetExhaustTheBudget(@TempDir Path tempDir) throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    mgr.initializeOrLoad();

    assertEquals(0, mgr.autoRebuildAttemptsFor(TARGET_A), "no attempts recorded yet");
    for (int expected = 1; expected <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; expected++) {
      assertEquals(expected, mgr.recordAutoRebuildAttempt(TARGET_A), "attempt " + expected);
    }
    assertEquals(
        IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS + 1,
        mgr.recordAutoRebuildAttempt(TARGET_A),
        "the attempt that exceeds the budget still increments — the caller refuses on the value,"
            + " so the count must keep growing rather than saturate");
    assertTrue(
        mgr.autoRebuildAttemptsFor(TARGET_A) > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
        "budget is exhausted for this target");
  }

  /**
   * The budget is per target. A user who exhausts it on one broken upgrade must not be refused an
   * automatic rebuild for the next, unrelated one — that would turn a transient failure into a
   * permanent one.
   */
  @Test
  void aDifferentTargetFingerprintResetsTheBudget(@TempDir Path tempDir) throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    mgr.initializeOrLoad();

    for (int i = 0; i < IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS + 2; i++) {
      mgr.recordAutoRebuildAttempt(TARGET_A);
    }
    assertEquals(1, mgr.recordAutoRebuildAttempt(TARGET_B), "a new target starts from one");
    assertEquals(0, mgr.autoRebuildAttemptsFor(TARGET_A), "the old target's count is not consulted");
  }

  /** A completed cutover is proof the rebuild converged, so it releases the brake. */
  @Test
  void aSuccessfulCutoverClearsTheBrake(@TempDir Path tempDir) throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    mgr.initializeOrLoad();
    mgr.recordAutoRebuildAttempt(TARGET_A);
    mgr.recordAutoRebuildAttempt(TARGET_A);
    assertEquals(2, mgr.autoRebuildAttemptsFor(TARGET_A));

    mgr.startMigration("schema_mismatch");
    assertEquals(
        2, mgr.autoRebuildAttemptsFor(TARGET_A), "starting a migration preserves the count");
    mgr.promoteBuildingGenerationToActive();

    assertEquals(0, mgr.autoRebuildAttemptsFor(TARGET_A), "cutover releases the brake");
    assertNull(mgr.readStateBestEffort().auto_rebuild_key());
  }

  /**
   * A truncated {@code state.json} — the shape a crash mid-write leaves behind. The manager keeps a
   * {@code state.json.prev} snapshot precisely for this, so the correct outcome is recovery, not
   * absence: the generation pointer survives and search still knows which directory to open.
   *
   * <p>Written as a test rather than assumed: my first version of this asserted the file read back
   * as absent, which would have been a silently worse product (a lost pointer means a lost index).
   */
  @Test
  void aTruncatedStateFileIsRecoveredFromTheBackupSnapshot(@TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("index");
    IndexGenerationManager mgr = new IndexGenerationManager(base);
    String blue = mgr.initializeOrLoad().state().active_generation();
    // Two writes, so state.json.prev holds a complete, valid snapshot.
    mgr.recordAutoRebuildAttempt(TARGET_A);
    mgr.recordAutoRebuildAttempt(TARGET_A);

    Path statePath = base.resolve("state.json");
    assertTrue(Files.exists(statePath), "state.json was written");
    Files.writeString(
        statePath,
        "{\"format_version\": 2, \"active_gen",
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);

    IndexGenerationManager reopened = new IndexGenerationManager(base);
    IndexGenerationManager.State recovered = reopened.readStateBestEffort();
    assertNotNull(recovered, "the backup snapshot is used when state.json will not parse");
    assertEquals(blue, recovered.active_generation(), "the generation pointer survives corruption");
    assertNotNull(recovered.auto_rebuild_key(), "the brake is carried on the recovered state too");

    var relayout = reopened.initializeOrLoad();
    assertNotNull(relayout, "the manager re-establishes a usable layout");
    assertEquals(blue, relayout.state().active_generation());
  }

  /**
   * Both the state file and its backup unreadable — nothing left to recover from. The manager must
   * re-establish a layout rather than throw, and the brake must read as zero: bookkeeping we lost is
   * not evidence that rebuilds were already attempted, and treating it as such would refuse the one
   * rebuild the user now actually needs.
   */
  @Test
  void aTotallyLostStateFileDoesNotBlockRecovery(@TempDir Path tempDir) throws Exception {
    Path base = tempDir.resolve("index");
    IndexGenerationManager mgr = new IndexGenerationManager(base);
    mgr.initializeOrLoad();
    mgr.recordAutoRebuildAttempt(TARGET_A);
    mgr.recordAutoRebuildAttempt(TARGET_A);

    for (String name : new String[] {"state.json", "state.json.prev", "state.json.tmp"}) {
      Path p = base.resolve(name);
      if (Files.exists(p)) {
        Files.writeString(p, "{", StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      }
    }

    IndexGenerationManager reopened = new IndexGenerationManager(base);
    assertNull(reopened.readStateBestEffort(), "nothing parseable reads as absent, not as healthy");
    assertEquals(
        0,
        reopened.autoRebuildAttemptsFor(TARGET_A),
        "a lost brake counts as no attempts, so recovery is not blocked by lost bookkeeping");

    var relayout = reopened.initializeOrLoad();
    assertNotNull(relayout, "the manager re-establishes a layout from an unreadable state file");
    assertNotNull(relayout.state().active_generation(), "an active generation is restored");
  }
}
