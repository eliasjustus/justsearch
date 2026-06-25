/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.rrd4j.ConsolFun;
import org.rrd4j.DsType;
import org.rrd4j.core.DsDef;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.FetchRequest;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdDef;
import org.rrd4j.core.RrdToolkit;
import org.rrd4j.core.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Time-series storage for curated telemetry metrics using RRD4J.
 *
 * <p>Design:
 * <ul>
 *   <li>Single RRD file with one datasource per curated metric
 *   <li>Three archives: 5-min/24h, 1-hour/7d, 1-day/90d
 *   <li>Thread-safe: all public methods are synchronized
 *   <li>Best-effort semantics (failures logged at WARN, not thrown)
 * </ul>
 *
 * <p>Curated metrics are low-cardinality gauges and counters that are valuable for trend analysis.
 * High-cardinality metrics remain in NDJSON only.
 */
public class RrdMetricStore implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(RrdMetricStore.class);

  /** Step interval in seconds. Matches typical flush interval. */
  private static final long STEP_SECONDS = 60;

  /** Heartbeat: max time between updates before value goes unknown. 3x step for tolerance. */
  private static final long HEARTBEAT_SECONDS = 180;

  /**
   * Legacy hand-maintained list of curated metric names that aren't yet in a
   * {@link MetricCatalog}. Tempdoc 417 Phase 3b shrinks this list as catalogs declare
   * {@code archivedTo(...)} on their corresponding metrics; the goal is to drop this list to
   * empty by the end of Phase 3.
   *
   * <p>Today's residual entries:
   * <ul>
   *   <li>{@code worker.*} OperationalMetrics — migrate in Phase 3c (WorkerOpsMetricCatalog).
   *   <li>{@code *.jvm.*} JvmRuntimeGauges — migrate in Phase 3d (JvmMetricCatalog).
   *   <li>{@code llm.queue_depth} — legacy, no catalog yet.
   * </ul>
   */
  static final Set<String> LEGACY_CURATED_METRICS = Set.of(
      "worker.documents.indexed.total",
      "worker.searches.total",
      "worker.job_queue.depth",
      // observations.md `#254` closure: rate_per_sec is declared
      // `archivedTo(STANDARD)` on `WorkerOpsMetricCatalog` (worker-services
      // module). The legacy-list is the only path on the head side because
      // the `ui` module does not depend on `worker-services` (head/worker
      // process boundary), so the catalog cannot be passed to head's
      // LocalTelemetry the way `worker.job_queue.depth` would otherwise be
      // resolved. Sibling worker-side archived gauges (`worker.documents.indexed.total`,
      // `worker.job_queue.depth`, `worker.switch_buffer.depth`,
      // `worker.index.pending_embeddings`) live here for the same reason.
      // Caveat: existing on-disk RRD files created before this entry was
      // added lack the datasource; per the catalog-drift comment near
      // line ~240 of this file, recording silently skips. Fresh installs
      // (`jseval --start-backend --clean` or first install) get the
      // datasource and the producer's window populates after a few ticks.
      "worker.documents.indexed.rate_per_sec",
      "worker.switch_buffer.depth",
      "worker.index.pending_embeddings",
      "head.jvm.memory.heap.used_bytes",
      "worker.jvm.memory.heap.used_bytes",
      "head.jvm.threads.live",
      "worker.jvm.threads.live",
      "llm.queue_depth"
  );

  private final Path rrdPath;
  private final Set<String> curatedMetrics;
  private final List<String> dsNames;
  private volatile RrdDb rrdDb;
  private volatile long lastRecordedTimestamp = 0;

  /**
   * Legacy constructor — uses the hand-maintained {@link #LEGACY_CURATED_METRICS} list only.
   * Retained for callers that don't have catalog visibility (e.g., tests).
   */
  public RrdMetricStore(Path dataDir) {
    this(dataDir, List.of());
  }

  /**
   * Catalog-aware constructor: derives the curated set as the union of (a) every
   * {@link MetricDefinition} declaring {@code archivedTo(...)} across the supplied catalogs, and
   * (b) the residual {@link #LEGACY_CURATED_METRICS} list. Tempdoc 417 Phase 3b.
   */
  public RrdMetricStore(Path dataDir, List<MetricCatalog> catalogs) {
    this.rrdPath = dataDir.resolve("telemetry").resolve("metrics.rrd");
    Set<String> archived = new LinkedHashSet<>(LEGACY_CURATED_METRICS);
    if (catalogs != null) {
      for (MetricCatalog catalog : catalogs) {
        for (MetricDefinition def : catalog.definitions()) {
          if (def.rrdArchive() != null) {
            archived.add(def.name());
          }
        }
      }
    }
    this.curatedMetrics = Set.copyOf(archived);
    List<String> derivedDsNames = curatedMetrics.stream().sorted().map(RrdMetricStore::toDataSourceName).toList();
    Set<String> seen = new HashSet<>();
    for (String dsName : derivedDsNames) {
      if (!seen.add(dsName)) {
        throw new IllegalArgumentException("Datasource name collision detected: " + dsName);
      }
    }
    this.dsNames = derivedDsNames;
  }

  /**
   * Returns the curated metric set this store will record. Package-private for tests.
   *
   * <p>Tempdoc 417 critical-analysis follow-up: exposed so {@code
   * RrdMetricStoreCatalogDeriveTest} can assert that catalog {@code archivedTo(...)} declarations
   * flow through to the curated set without going through the on-disk RRD initialization path.
   */
  Set<String> curatedMetricsForTest() {
    return curatedMetrics;
  }

  /**
   * Initializes the RRD database. Call once after construction.
   *
   * <p>Creates the RRD file if it doesn't exist. If it exists, opens it.
   * Thread-safe: multiple calls are idempotent.
   */
  public synchronized void initialize() {
    if (rrdDb != null) {
      return; // Already initialized
    }

    RrdDb newDb = null;
    try {
      Files.createDirectories(rrdPath.getParent());

      if (Files.exists(rrdPath)) {
        // Tempdoc 600 C-2 (Design A): reconcile the on-disk datasource set to the declared curated
        // set BEFORE opening, so a stale file (created by a prior boot whose curated set was
        // smaller) cannot leave a curated metric permanently unrecorded — the silent catalog-drift
        // that blinds a health rule whose predicate references the missing metric. Must run on a
        // closed file.
        reconcileDatasources();
        // Open existing database
        newDb = RrdDb.getBuilder().setPath(rrdPath.toString()).build();
        log.info("Opened existing RRD database: {}", rrdPath);
      } else {
        // Create new database
        long startTime = Instant.now().getEpochSecond() - STEP_SECONDS;
        RrdDef rrdDef = new RrdDef(rrdPath.toString(), startTime, STEP_SECONDS);

        // Add datasource for each curated metric
        for (String dsName : dsNames) {
          // Use GAUGE for all - we store absolute values, not rates
          rrdDef.addDatasource(dsName, DsType.GAUGE, HEARTBEAT_SECONDS, 0, Double.NaN);
        }

        // Archive configuration depends on STEP_SECONDS = 60
        // Formula: (pdp_per_row * STEP_SECONDS) = resolution, rows = duration / resolution

        // Archive 1: 5 * 60 = 300s (5 min), 288 * 300s = 86400s (24h)
        rrdDef.addArchive(ConsolFun.AVERAGE, 0.5, 5, 288);

        // Archive 2: 60 * 60 = 3600s (1 hour), 168 * 3600s = 604800s (7d)
        rrdDef.addArchive(ConsolFun.AVERAGE, 0.5, 60, 168);

        // Archive 3: 1440 * 60 = 86400s (1 day), 90 * 86400s = 7776000s (90d)
        rrdDef.addArchive(ConsolFun.AVERAGE, 0.5, 1440, 90);

        newDb = RrdDb.getBuilder().setRrdDef(rrdDef).build();
        log.info("Created new RRD database: {} with {} datasources", rrdPath, dsNames.size());
      }
      this.rrdDb = newDb; // Only assign on success
    } catch (Exception e) {
      log.error("Failed to initialize RRD database: {}", e.getMessage(), e);
      // Clean up partially created resource
      if (newDb != null) {
        try {
          newDb.close();
        } catch (IOException ignored) {
          // Best effort cleanup
        }
      }
    }
  }

  /**
   * Tempdoc 600 C-2 (Design A) — schema reconciliation: bring the on-disk RRD's datasource set into
   * correspondence with the declared curated set ({@link #dsNames}) before the database is opened.
   *
   * <p>An on-disk file created by a prior boot whose curated set was smaller is missing datasources
   * for metrics added since (catalog grew, or {@link #LEGACY_CURATED_METRICS} grew). Without this,
   * {@link #record} silently DEBUG-skips those metrics forever (the catalog-drift handling near the
   * {@code Datasource not found} catch), so any health rule whose predicate references such a metric
   * is <em>permanently blind</em>. This adds the missing datasources <em>in place</em> via
   * {@link RrdToolkit#addDatasources}, which preserves all existing data and archives.
   *
   * <p>Best-effort by design: a reconcile failure is logged and startup proceeds — telemetry is
   * WARN-not-throw, and {@code record()}/{@code query()} keep their pre-existing drift tolerance for
   * any datasource that is still absent. Must run on a CLOSED file (RrdToolkit requirement), so it is
   * invoked from {@link #initialize} before the long-lived {@link RrdDb} is opened.
   */
  private void reconcileDatasources() {
    try {
      Set<String> existing;
      try (RrdDb db = RrdDb.getBuilder().setPath(rrdPath.toString()).build()) {
        existing = new HashSet<>(Arrays.asList(db.getDsNames()));
      }
      List<DsDef> missing = new ArrayList<>();
      for (String dsName : dsNames) {
        if (!existing.contains(dsName)) {
          missing.add(new DsDef(dsName, DsType.GAUGE, HEARTBEAT_SECONDS, 0, Double.NaN));
        }
      }
      if (missing.isEmpty()) {
        return;
      }
      // In-place, multi-datasource add (rrd4j 3.10); saveBackup=true writes a sibling .bak.
      RrdToolkit.addDatasources(rrdPath.toString(), missing, true);
      log.info(
          "RRD reconcile: added {} missing datasource(s) to {} (curated set grew since the file was created)",
          missing.size(),
          rrdPath);
      // Best-effort cleanup of the .bak RrdToolkit writes alongside the original.
      Path backup = rrdPath.resolveSibling(rrdPath.getFileName().toString() + ".bak");
      try {
        Files.deleteIfExists(backup);
      } catch (IOException ignored) {
        // A leftover .bak is harmless; never fail startup over it.
      }
    } catch (Exception e) {
      // A failed reconcile must never block startup.
      log.warn("RRD reconcile failed for {}: {}", rrdPath, e.getMessage());
    }
  }

  /**
   * Records metrics to the RRD database.
   *
   * <p>Called from the telemetry export thread after NDJSON write. Filters to curated metrics
   * only and writes their values. Thread-safe.
   *
   * @param metrics collection of metrics from the OTel exporter
   */
  public synchronized void record(Collection<MetricData> metrics) {
    if (metrics == null || metrics.isEmpty()) {
      return;
    }
    // Lazy initialization: defer RRD file open from startup to first flush
    if (rrdDb == null) {
      initialize();
      if (rrdDb == null) {
        return; // Initialize failed — skip recording
      }
    }

    try {
      long timestamp = Instant.now().getEpochSecond();

      // Validate timestamp monotonicity - RRD4J requires increasing timestamps
      if (timestamp <= lastRecordedTimestamp) {
        log.debug("Skipping RRD sample: timestamp {} not after last {}", timestamp, lastRecordedTimestamp);
        return;
      }

      Sample sample = rrdDb.createSample(timestamp);
      boolean hasValues = false;

      for (MetricData m : metrics) {
        String metricName = m.getName();
        // L2: Null metric name check
        if (metricName == null || !curatedMetrics.contains(metricName)) {
          continue;
        }

        Double value = extractValue(m);
        if (value != null && Double.isFinite(value)) {
          String dsName = toDataSourceName(metricName);
          try {
            sample.setValue(dsName, value);
            hasValues = true;
          } catch (IllegalArgumentException e) {
            // Tempdoc 374 alpha.22 Bug T: catalog drift. The on-disk RRD file was
            // created in a prior boot whose curated-metrics set was smaller than
            // this boot's. The metric is in this boot's catalog (passed contains()
            // above) but not registered as a datasource in the existing RRD file,
            // so RRD4J throws "Datasource <name> not found". Benign: NDJSON export
            // still works; only RRD time-series archival is missing for the new
            // metric until the file is rebuilt (uninstall+reinstall, or a future
            // schema-bump migration). DEBUG so it doesn't dominate WARN signal —
            // matches the symmetric handling in the query() path below.
            String msg = e.getMessage();
            if (msg != null && msg.contains("Datasource") && msg.contains("not found")) {
              log.debug(
                  "RRD datasource missing for metric {}: catalog drift since RRD created",
                  metricName);
            } else {
              throw e; // unexpected IllegalArgumentException — let outer catch WARN
            }
          }
        }
      }

      if (hasValues) {
        sample.update();
        lastRecordedTimestamp = timestamp;
      }
    } catch (Exception e) {
      // H2: Warn level so failures are visible in production
      log.warn("Failed to record RRD sample", e);
    }
  }

  /**
   * Queries time-series data for a metric.
   *
   * @param metricName the metric name (e.g., "head.http.inflight_requests")
   * @param startEpochSeconds start time in seconds since epoch
   * @param endEpochSeconds end time in seconds since epoch
   * @return query result with timestamps and values, or null if unavailable
   */
  public synchronized TimeSeriesResult query(String metricName, long startEpochSeconds, long endEpochSeconds) {
    if (rrdDb == null || metricName == null || !curatedMetrics.contains(metricName)) {
      return null;
    }

    try {
      String dsName = toDataSourceName(metricName);
      FetchRequest request = rrdDb.createFetchRequest(ConsolFun.AVERAGE, startEpochSeconds, endEpochSeconds);
      FetchData data = request.fetchData();

      long[] timestamps = data.getTimestamps();
      double[] values = data.getValues(dsName);

      // Filter out NaN values and their timestamps
      int validCount = 0;
      for (double v : values) {
        if (!Double.isNaN(v)) {
          validCount++;
        }
      }

      long[] filteredTimestamps = new long[validCount];
      double[] filteredValues = new double[validCount];
      int idx = 0;
      for (int i = 0; i < values.length && i < timestamps.length; i++) {
        if (!Double.isNaN(values[i])) {
          filteredTimestamps[idx] = timestamps[i];
          filteredValues[idx] = values[i];
          idx++;
        }
      }

      return new TimeSeriesResult(metricName, filteredTimestamps, filteredValues);
    } catch (Exception e) {
      // "Datasource not found" is benign: a fresh install hasn't recorded the
      // metric yet so its datasource doesn't exist in the RRD. The on-every-poll
      // stack-trace this used to produce was identified as log noise in the
      // tempdoc 374 sandbox round 2 evidence.
      String msg = e.getMessage();
      if (msg != null && msg.contains("Datasource") && msg.contains("not found")) {
        log.debug("RRD query: datasource not yet recorded for {}: {}", metricName, msg);
      } else {
        log.warn("Failed to query RRD data", e);
      }
      return null;
    }
  }

  /**
   * Returns the set of curated metric names that can be queried.
   */
  public Set<String> getCuratedMetrics() {
    return curatedMetrics;
  }

  @Override
  public synchronized void close() {
    if (rrdDb != null) {
      try {
        rrdDb.close();
        rrdDb = null;
        log.debug("Closed RRD database");
      } catch (IOException e) {
        log.warn("Failed to close RRD database", e);
      }
    }
  }

  private static Double extractValue(MetricData m) {
    MetricDataType type = m.getType();
    switch (type) {
      case DOUBLE_GAUGE -> {
        var points = m.getDoubleGaugeData().getPoints();
        if (!points.isEmpty()) {
          DoublePointData p = points.iterator().next();
          return p.getValue();
        }
      }
      case LONG_GAUGE -> {
        var points = m.getLongGaugeData().getPoints();
        if (!points.isEmpty()) {
          LongPointData p = points.iterator().next();
          return (double) p.getValue();
        }
      }
      case DOUBLE_SUM -> {
        var points = m.getDoubleSumData().getPoints();
        if (!points.isEmpty()) {
          DoublePointData p = points.iterator().next();
          return p.getValue();
        }
      }
      case LONG_SUM -> {
        var points = m.getLongSumData().getPoints();
        if (!points.isEmpty()) {
          LongPointData p = points.iterator().next();
          return (double) p.getValue();
        }
      }
      default -> {
        // Histograms and other types not supported in RRD
      }
    }
    return null;
  }

  /**
   * Converts a metric name to a valid RRD datasource name.
   * RRD4J datasource names are limited to 20 chars and cannot contain dots.
   */
  static String toDataSourceName(String metricName) {
    // Create a short hash-based name if the metric name is too long
    String normalized = metricName.replace(".", "_");
    if (normalized.length() <= 20) {
      return normalized;
    }
    // Truncate and add hash suffix for uniqueness
    int hash = Math.abs(metricName.hashCode() % 10000);
    return normalized.substring(0, 15) + "_" + hash;
  }

  /**
   * Result of a time-series query.
   *
   * @param metric the metric name
   * @param timestamps array of Unix timestamps (seconds since epoch)
   * @param values array of metric values corresponding to each timestamp
   */
  @SuppressWarnings("ArrayRecordComponent") // Intentional for API performance
  public record TimeSeriesResult(String metric, long[] timestamps, double[] values) {}
}
