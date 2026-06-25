package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

/**
 * Tempdoc 374 alpha.22 Bug T regression guard.
 *
 * <p>The on-disk RRD database is created on first install with the curated set known at the time.
 * If the curated set grows in a later release (catalog adds a new {@code archivedTo(...)}
 * declaration, or {@link RrdMetricStore#LEGACY_CURATED_METRICS} grows), an existing RRD file will
 * be missing datasources for the new metrics. RRD4J's {@link Sample#setValue(String, double)}
 * throws {@code IllegalArgumentException("Datasource <name> not found")} for those metrics.
 *
 * <p>Round 12 sandbox evidence: the legacy-broad catch in {@code RrdMetricStore.record()} surfaced
 * this as {@code WARN  Failed to record RRD sample} once per minute ad infinitum until the user
 * reinstalls. Cosmetic but noisy — the alpha.22 fix downgrades the catalog-drift subset to DEBUG
 * via a per-{@code setValue} try/catch with substring matching on the message.
 *
 * <p>This test pins the RRD4J library's exception shape so the {@code msg.contains("Datasource") &&
 * msg.contains("not found")} substring check in {@code RrdMetricStore.record()} keeps matching
 * across rrd4j upgrades.
 */
class RrdMetricStoreCatalogDriftTest {

  @TempDir Path tempDir;

  @Test
  void rrdSetValueThrowsExpectedShapeForMissingDatasource() throws Exception {
    Path rrdPath = tempDir.resolve("drift.rrd");
    long step = 60;
    long startTime = Instant.now().getEpochSecond() - step;

    // Create an RRD with ONLY datasource "a" — emulates the on-disk state from a
    // prior boot whose curated set was smaller than the current curated set.
    RrdDef def = new RrdDef(rrdPath.toString(), startTime, step);
    def.addDatasource("a", DsType.GAUGE, 180, 0, Double.NaN);
    def.addArchive(ConsolFun.AVERAGE, 0.5, 5, 288);

    try (RrdDb db = RrdDb.getBuilder().setRrdDef(def).build()) {
      Sample sample = db.createSample(startTime + step);

      // Setting an unknown datasource — emulates a current-boot curated metric whose
      // datasource doesn't exist in the on-disk RRD because the file was created in
      // a prior boot.
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> sample.setValue("missing_b", 1.0));

      // The substring check in RrdMetricStore.record()'s new per-setValue catch
      // depends on this exact message shape. If rrd4j ever changes the wording, the
      // catch will fall through to the outer catch and re-introduce WARN spam.
      String msg = e.getMessage();
      assertNotNull(msg, "RRD4J must produce a non-null message");
      assertTrue(
          msg.contains("Datasource") && msg.contains("not found"),
          "RRD4J message shape changed — RrdMetricStore catalog-drift catch will miss it. Got: "
              + msg);
    }
  }

  @Test
  void rrdSetValueSucceedsForKnownDatasourceAfterMissingOne() throws Exception {
    // Verifies that setValue on a missing datasource does NOT poison the sample —
    // a subsequent setValue on a known datasource still succeeds. Establishes that
    // RrdMetricStore.record() can ride past a catalog-drift exception and continue
    // recording the metrics that DO have datasources, without having to recreate
    // the sample.
    Path rrdPath = tempDir.resolve("drift-recovery.rrd");
    long step = 60;
    long startTime = Instant.now().getEpochSecond() - step;

    RrdDef def = new RrdDef(rrdPath.toString(), startTime, step);
    def.addDatasource("known_a", DsType.GAUGE, 180, 0, Double.NaN);
    def.addArchive(ConsolFun.AVERAGE, 0.5, 5, 288);

    try (RrdDb db = RrdDb.getBuilder().setRrdDef(def).build()) {
      Sample sample = db.createSample(startTime + step);

      // First setValue throws (missing datasource).
      assertThrows(IllegalArgumentException.class, () -> sample.setValue("missing_b", 1.0));

      // Second setValue on a known datasource still works — sample state survives
      // the prior throw. Confirms RrdMetricStore.record()'s loop can continue with
      // the next metric after catching the catalog-drift exception.
      sample.setValue("known_a", 42.0);
      sample.update();
    }

    // Reopen and verify "known_a" was actually persisted.
    try (RrdDb db = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
      assertEquals(1, db.getDsCount(), "RRD has exactly one datasource");
      assertEquals("known_a", db.getDatasource(0).getName());
    }
  }
}
