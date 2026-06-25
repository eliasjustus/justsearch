package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.Sample;

/**
 * Tempdoc 600 C-2 (Design A) regression guard for RRD datasource reconciliation.
 *
 * <p>The on-disk {@code metrics.rrd} is created on first install with the curated set known at the
 * time. If the curated set later grows (a catalog adds {@code archivedTo(...)}, or {@link
 * RrdMetricStore#LEGACY_CURATED_METRICS} grows), an existing file is missing datasources for the new
 * metrics — and {@code record()} silently DEBUG-skips them forever (the {@code Datasource not found}
 * catch), permanently blinding any health rule whose predicate references them. {@link
 * RrdMetricStore#initialize()} now reconciles the on-disk datasource set to the declared one on open.
 * This test exercises the REAL {@code initialize()} path (not the toolkit in isolation) per the
 * audit-driven-fixes-need-test rule.
 */
class RrdMetricStoreReconcileTest {

  @TempDir Path tempDir;

  /** A curated metric guaranteed to be in every boot's declared set (legacy-list member). */
  private static final String CURATED_METRIC = "head.jvm.memory.heap.used_bytes";

  @Test
  @DisplayName("initialize() adds a datasource missing from a stale on-disk RRD; it becomes writable")
  void initializeReconcilesMissingDatasource() throws Exception {
    Path rrdDir = tempDir.resolve("telemetry");
    Files.createDirectories(rrdDir);
    Path rrdPath = rrdDir.resolve("metrics.rrd");
    String dsName = RrdMetricStore.toDataSourceName(CURATED_METRIC);

    // 1. Emulate a stale file from a prior boot: an RRD that LACKS the curated metric's datasource.
    long startTime = Instant.now().getEpochSecond() - 60;
    RrdDef def = new RrdDef(rrdPath.toString(), startTime, 60);
    def.addDatasource("some_other_metric", DsType.GAUGE, 180, 0, Double.NaN);
    def.addArchive(ConsolFun.AVERAGE, 0.5, 5, 288);
    def.addArchive(ConsolFun.AVERAGE, 0.5, 60, 168);
    def.addArchive(ConsolFun.AVERAGE, 0.5, 1440, 90);
    try (RrdDb db = RrdDb.getBuilder().setRrdDef(def).build()) {
      assertFalse(
          Arrays.asList(db.getDsNames()).contains(dsName),
          "stale RRD must not yet contain the curated metric's datasource");
    }

    // 2. Open via the production store (whose declared set includes the legacy curated metric).
    //    initialize() must reconcile the missing datasource before opening.
    RrdMetricStore store = new RrdMetricStore(tempDir);
    store.initialize();
    store.close();

    // 3. The previously-missing datasource now exists on disk (the reconcile happened).
    try (RrdDb db = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
      assertTrue(
          Arrays.asList(db.getDsNames()).contains(dsName),
          "reconcile must have added the curated metric's datasource: "
              + Arrays.toString(db.getDsNames()));
    }

    // 4. The reconciled datasource is now WRITABLE — a setValue no longer throws
    //    "Datasource <name> not found", which is exactly the production drift-skip condition.
    //    (A timing-free proxy for "record() no longer silently skips this metric".)
    try (RrdDb db = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
      long t = db.getLastUpdateTime() + 60;
      Sample sample = db.createSample(t);
      assertDoesNotThrow(
          () -> sample.setValue(dsName, 500_000_000.0),
          "the reconciled datasource must accept writes (no 'Datasource not found' drift-skip)");
      sample.update();
    }

    // 5. No .bak left behind.
    assertFalse(
        Files.exists(rrdPath.resolveSibling("metrics.rrd.bak")),
        "reconcile should clean up its .bak backup");
  }
}
