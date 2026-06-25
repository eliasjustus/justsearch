package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.Unit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 417 Phase 3 critical-analysis fix (A2).
 *
 * <p>Regression test for {@link RrdMetricStore}'s catalog → curated set derivation. The Phase 3b
 * refactor (`LEGACY_CURATED_METRICS` + `archivedTo` declarations) replaced a hand-maintained
 * {@code CURATED_METRICS} set with a derive path. This test pins the contract:
 *
 * <ul>
 *   <li>Catalog {@link MetricDefinition} entries declaring {@link RrdArchive} non-null are
 *       included in the curated set.
 *   <li>Catalog entries without {@code archivedTo(...)} are excluded.
 *   <li>{@link RrdMetricStore#LEGACY_CURATED_METRICS} entries are always included (residual list).
 *   <li>Multiple catalogs union correctly.
 * </ul>
 *
 * <p>The pinned production-set snapshot lives in {@code RrdCuratedSetSnapshotTest} (in the
 * {@code app-launcher} module, where every producer catalog is on the classpath).
 */
final class RrdMetricStoreCatalogDeriveTest {

  @TempDir Path tempDir;

  private Path dataDir() throws Exception {
    Path d = tempDir.resolve("rrd-derive-test");
    Files.createDirectories(d);
    return d;
  }

  @Test
  void archivedMetricFromSingleCatalogIsIncluded() throws Exception {
    MetricCatalog catalog =
        MetricCatalog.of(
            "x.scope",
            List.of(
                MetricDefinition.counter("x.scope.archived_total")
                    .unit(Unit.COUNT)
                    .archivedTo(RrdArchive.STANDARD)
                    .build(),
                MetricDefinition.counter("x.scope.un_archived_total").unit(Unit.COUNT).build()));

    RrdMetricStore store = new RrdMetricStore(dataDir(), List.of(catalog));
    Set<String> curated = store.curatedMetricsForTest();

    assertTrue(curated.contains("x.scope.archived_total"), "archived metric must be curated");
    assertFalse(
        curated.contains("x.scope.un_archived_total"),
        "un-archived metric must NOT be curated; got: " + curated);
  }

  @Test
  void archivedMetricsFromMultipleCatalogsAreUnioned() throws Exception {
    MetricCatalog a =
        MetricCatalog.of(
            "a.ns",
            List.of(
                MetricDefinition.gauge("a.ns.metric_one")
                    .unit(Unit.COUNT)
                    .archivedTo(RrdArchive.STANDARD)
                    .build()));
    MetricCatalog b =
        MetricCatalog.of(
            "b.ns",
            List.of(
                MetricDefinition.gauge("b.ns.metric_two")
                    .unit(Unit.COUNT)
                    .archivedTo(RrdArchive.STANDARD)
                    .build()));

    RrdMetricStore store = new RrdMetricStore(dataDir(), List.of(a, b));
    Set<String> curated = store.curatedMetricsForTest();

    assertTrue(curated.contains("a.ns.metric_one"));
    assertTrue(curated.contains("b.ns.metric_two"));
  }

  @Test
  void legacyCuratedMetricsAreAlwaysIncluded() throws Exception {
    RrdMetricStore store = new RrdMetricStore(dataDir(), List.of());
    Set<String> curated = store.curatedMetricsForTest();

    for (String legacy : RrdMetricStore.LEGACY_CURATED_METRICS) {
      assertTrue(curated.contains(legacy), "legacy curated metric must be included: " + legacy);
    }
    assertEquals(
        RrdMetricStore.LEGACY_CURATED_METRICS.size(),
        curated.size(),
        "with no catalogs the curated set must equal the legacy set exactly");
  }

  @Test
  void legacyAndCatalogArchivedAreUnioned() throws Exception {
    MetricCatalog catalog =
        MetricCatalog.of(
            "test.scope",
            List.of(
                MetricDefinition.counter("test.scope.new_total")
                    .unit(Unit.COUNT)
                    .archivedTo(RrdArchive.STANDARD)
                    .build()));

    RrdMetricStore store = new RrdMetricStore(dataDir(), List.of(catalog));
    Set<String> curated = store.curatedMetricsForTest();

    assertTrue(curated.contains("test.scope.new_total"), "catalog-archived metric included");
    for (String legacy : RrdMetricStore.LEGACY_CURATED_METRICS) {
      assertTrue(curated.contains(legacy), "legacy retained: " + legacy);
    }
    assertEquals(
        RrdMetricStore.LEGACY_CURATED_METRICS.size() + 1,
        curated.size(),
        "size = legacy + 1 catalog metric");
  }

  @Test
  void duplicateBetweenLegacyAndCatalogDoesNotInflateSize() throws Exception {
    String name = RrdMetricStore.LEGACY_CURATED_METRICS.iterator().next();
    MetricCatalog catalog =
        MetricCatalog.of(
            namespaceOf(name),
            List.of(
                MetricDefinition.counter(name)
                    .unit(Unit.COUNT)
                    .archivedTo(RrdArchive.STANDARD)
                    .build()));

    RrdMetricStore store = new RrdMetricStore(dataDir(), List.of(catalog));
    Set<String> curated = store.curatedMetricsForTest();

    assertTrue(curated.contains(name));
    assertEquals(
        RrdMetricStore.LEGACY_CURATED_METRICS.size(),
        curated.size(),
        "duplicate must dedupe; size unchanged");
  }

  /** Returns the namespace prefix (everything up to the last dot) for a given metric name. */
  private static String namespaceOf(String metricName) {
    int dot = metricName.lastIndexOf('.');
    return dot > 0 ? metricName.substring(0, dot) : metricName;
  }

  /**
   * Pins {@link RrdMetricStore#LEGACY_CURATED_METRICS} to its expected post-Phase-3b residual
   * shape. Drift-guard: the legacy list should only ever shrink (as catalogs declare {@code
   * archivedTo(...)}). If a metric is ADDED to the legacy list, it should instead live on a
   * catalog with {@code archivedTo(STANDARD)}. If the metric is REMOVED from the legacy list, the
   * corresponding catalog must declare archivedTo or this test fails (by absence of legacy +
   * absence of catalog declaration).
   */
  @Test
  void legacyCuratedSetMatchesPinnedShape() {
    Set<String> expected =
        Set.of(
            "worker.documents.indexed.total",
            "worker.searches.total",
            "worker.job_queue.depth",
            // observations.md `#254` closure — added to legacy because the
            // `ui` module (where head's LocalTelemetry is constructed) does
            // not depend on `worker-services` where WorkerOpsMetricCatalog
            // declares the archive. See the comment at the entry's site for
            // why the catalog approach doesn't apply here.
            "worker.documents.indexed.rate_per_sec",
            "worker.switch_buffer.depth",
            "worker.index.pending_embeddings",
            "head.jvm.memory.heap.used_bytes",
            "worker.jvm.memory.heap.used_bytes",
            "head.jvm.threads.live",
            "worker.jvm.threads.live",
            "llm.queue_depth");
    assertEquals(
        expected,
        RrdMetricStore.LEGACY_CURATED_METRICS,
        "LEGACY_CURATED_METRICS drift detected. If shrinking the list, ensure the corresponding "
            + "catalog declares .archivedTo(RrdArchive.STANDARD). If adding, prefer declaring "
            + "the archive on the catalog instead.");
  }
}
