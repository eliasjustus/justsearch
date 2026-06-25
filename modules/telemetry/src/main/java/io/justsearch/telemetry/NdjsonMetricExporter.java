/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.justsearch.telemetry.catalog.Exemplars;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.ExemplarData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.SumData;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NdjsonMetricExporter implements MetricExporter {
  private static final Logger log = LoggerFactory.getLogger(NdjsonMetricExporter.class);
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  /** Critical threshold — stop telemetry writes entirely. */
  private static final long DISK_CRITICAL_BYTES = 200L * 1024 * 1024; // 200 MB

  /** Warning threshold — log warning, record pressure level. */
  private static final long DISK_WARNING_BYTES = 1024L * 1024 * 1024; // 1 GB
  // Tempdoc 417 Phase 2f cleanup: ALLOWED_TAG_KEYS deleted — per-metric tag schemas live on
  // {@link io.justsearch.telemetry.catalog.MetricDefinition}, and the SDK's per-View
  // {@code setAttributeFilter} strips non-allowed keys before this exporter sees them. The
  // 410-merge "component" allowlist addition (commit 6da11b4c2) is obsoleted by per-View
  // filtering — extraction.timeout_total carries `component=content_extractor` via
  // ExtractionTimeoutTags; index.watcher.events_total carries `component=...` via
  // WatcherEventTags. No exporter-level allowlist needed.

  private final Path metricsFile;
  private final boolean exemplarsEnabled;
  private final long rotateMaxBytes;
  private final int retentionDays;
  private final TelemetryHealthState healthState;
  private final RrdMetricStore rrdStore;
  /**
   * Per-metric exemplar policy populated from {@link io.justsearch.telemetry.catalog.MetricCatalog}
   * declarations. Metrics declared {@link Exemplars#OFF} have their exemplars suppressed at
   * export time even when the global filter is {@code traceBased}. Catalog-less callers see an
   * empty map and the legacy boolean toggle alone controls behavior.
   */
  private final Map<String, Exemplars> exemplarPolicies;

  /**
   * Per-metric tag-key emission order, populated from
   * {@link io.justsearch.telemetry.catalog.MetricDefinition#allowedTagKeys()} declarations
   * (LinkedHashSet → ordered List). When a metric is in this map, the wire format emits its
   * tags in declared order. Un-registered metrics emit no tags (Tempdoc 417 F1/F2f).
   */
  private final Map<String, List<String>> tagKeyOrderByMetric;

  NdjsonMetricExporter(Path metricsFile) {
    this(metricsFile, false, null, null, Map.of(), Map.of());
  }

  NdjsonMetricExporter(Path metricsFile, boolean exemplarsEnabled) {
    this(metricsFile, exemplarsEnabled, null, null, Map.of(), Map.of());
  }

  NdjsonMetricExporter(Path metricsFile, boolean exemplarsEnabled, TelemetryHealthState healthState) {
    this(metricsFile, exemplarsEnabled, healthState, null, Map.of(), Map.of());
  }

  NdjsonMetricExporter(
      Path metricsFile,
      boolean exemplarsEnabled,
      TelemetryHealthState healthState,
      RrdMetricStore rrdStore) {
    this(metricsFile, exemplarsEnabled, healthState, rrdStore, Map.of(), Map.of());
  }

  NdjsonMetricExporter(
      Path metricsFile,
      boolean exemplarsEnabled,
      TelemetryHealthState healthState,
      RrdMetricStore rrdStore,
      Map<String, Exemplars> exemplarPolicies) {
    this(metricsFile, exemplarsEnabled, healthState, rrdStore, exemplarPolicies, Map.of());
  }

  NdjsonMetricExporter(
      Path metricsFile,
      boolean exemplarsEnabled,
      TelemetryHealthState healthState,
      RrdMetricStore rrdStore,
      Map<String, Exemplars> exemplarPolicies,
      Map<String, List<String>> tagKeyOrderByMetric) {
    this.healthState = healthState;
    this.rrdStore = rrdStore;
    this.metricsFile = Objects.requireNonNull(metricsFile, "metricsFile");
    this.exemplarsEnabled = exemplarsEnabled;
    this.exemplarPolicies = Objects.requireNonNullElse(exemplarPolicies, Map.of());
    this.tagKeyOrderByMetric =
        Objects.requireNonNullElse(tagKeyOrderByMetric, Map.of());
    long defaultMaxMb = 10; // align with logging default
    String maxMbStr = System.getProperty("justsearch.telemetry.metrics.max_mb", System.getenv().getOrDefault("JUSTSEARCH_TELEMETRY_METRICS_MAX_MB", Long.toString(defaultMaxMb)));
    long maxMb;
    try { maxMb = Long.parseLong(maxMbStr); } catch (NumberFormatException e) { maxMb = defaultMaxMb; }
    this.rotateMaxBytes = Math.max(1, maxMb) * 1024 * 1024;
    int defaultDays = 7;
    String daysStr = System.getProperty("justsearch.telemetry.metrics.retention.days", System.getenv().getOrDefault("JUSTSEARCH_TELEMETRY_METRICS_RETENTION_DAYS", Integer.toString(defaultDays)));
    int days;
    try { days = Integer.parseInt(daysStr); } catch (NumberFormatException e) { days = defaultDays; }
    this.retentionDays = Math.max(1, days);
    try {
      Files.createDirectories(metricsFile.getParent());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create metrics directory", e);
    }
  }

  @Override
  public CompletableResultCode export(Collection<MetricData> metrics) {
    if (metrics == null || metrics.isEmpty()) return CompletableResultCode.ofSuccess();

    // Check disk space before attempting export
    if (!hasSufficientDiskSpace()) {
      return CompletableResultCode.ofSuccess(); // Skip silently but record in health state
    }

    rotateIfNeeded();
    var lines = new StringBuilder();
    try {
      for (MetricData m : metrics) {
        MetricDataType t = m.getType();
        switch (t) {
          case HISTOGRAM -> {
            for (var p : m.getHistogramData().getPoints()) {
              lines.append(histogramLine(m.getName(), p));
            }
          }
          case DOUBLE_GAUGE -> lines.append(gaugeLine(m.getName(), m.getDoubleGaugeData().getPoints()));
          case LONG_GAUGE -> lines.append(gaugeLine(m.getName(), m.getLongGaugeData().getPoints()));
          case DOUBLE_SUM -> lines.append(sumLine(m.getName(), m.getDoubleSumData()));
          case LONG_SUM -> lines.append(sumLine(m.getName(), m.getLongSumData()));
          default -> {
            // ignore other types for local NDJSON
          }
        }
      }
      if (lines.length() > 0) {
        Files.writeString(metricsFile, lines.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (healthState != null) {
          healthState.recordMetricExportSuccess();
        }
        // Record curated metrics to RRD time-series store (best-effort)
        if (rrdStore != null) {
          rrdStore.record(metrics);
        }
        pruneByRetention();
      }
    } catch (Exception e) {
      // best-effort local exporter: log and record failure but don't crash
      if (healthState != null) {
        healthState.recordMetricExportFailure();
      }
      log.warn("Metric export failed (best-effort): {}", e.getMessage());
    }
    return CompletableResultCode.ofSuccess();
  }

  /**
   * Checks disk space and applies tiered pressure levels.
   *
   * <p>Thresholds:
   *
   * <ul>
   *   <li>CRITICAL (&lt;200 MB): stop writes, record event
   *   <li>WARNING (&lt;1 GB): log warning, record pressure level
   *   <li>OK: normal operation
   * </ul>
   *
   * @return true if writes should proceed (OK or WARNING), false if CRITICAL
   */
  private boolean hasSufficientDiskSpace() {
    try {
      Path parent = metricsFile.getParent();
      if (parent == null || !Files.exists(parent)) {
        return true; // Proceed optimistically if parent doesn't exist yet
      }
      FileStore store = Files.getFileStore(parent);
      long usable = store.getUsableSpace();

      if (usable < DISK_CRITICAL_BYTES) {
        if (healthState != null) {
          healthState.recordDiskSpaceLowEvent();
          healthState.setDiskPressureLevel(TelemetryHealthState.DiskPressureLevel.CRITICAL);
        }
        log.error("Critical disk space: {} MB available — stopping telemetry writes",
            usable / (1024 * 1024));
        return false;
      }

      if (usable < DISK_WARNING_BYTES) {
        if (healthState != null) {
          healthState.setDiskPressureLevel(TelemetryHealthState.DiskPressureLevel.WARNING);
        }
        log.warn("Low disk space: {} MB available", usable / (1024 * 1024));
        return true; // Allow writes at WARNING — only stop at CRITICAL
      }

      if (healthState != null) {
        healthState.setDiskPressureLevel(TelemetryHealthState.DiskPressureLevel.OK);
      }
      return true;
    } catch (Exception e) {
      // Cannot determine disk space; proceed optimistically
      return true;
    }
  }

  private String gaugeLine(String name, Collection<?> points) {
    if (points == null || points.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (Object p : points) {
      String t = null;
      Attributes attrs = null;
      String val = null;
      if (p instanceof io.opentelemetry.sdk.metrics.data.DoublePointData dp) {
        t = iso(dp.getEpochNanos());
        attrs = dp.getAttributes();
        val = Double.toString(dp.getValue());
      } else if (p instanceof io.opentelemetry.sdk.metrics.data.LongPointData lp) {
        t = iso(lp.getEpochNanos());
        attrs = lp.getAttributes();
        val = Long.toString(lp.getValue());
      }
      if (t == null || attrs == null || val == null) continue;
      String tags = tagsJson(name, attrs);
      sb.append('{')
          .append("\"t\":\"").append(t).append("\",")
          .append("\"name\":\"").append(json(name)).append("\",")
          .append("\"type\":\"gauge\",")
          .append("\"value\":").append(val).append(',')
          .append("\"tags\":").append(tags)
          .append('}').append('\n');
    }
    return sb.toString();
  }

  private String sumLine(String name, SumData<?> sum) {
    StringBuilder sb = new StringBuilder();
    for (var p : sum.getPoints()) {
      String t = iso(p.getEpochNanos());
      String tags = tagsJson(name, p.getAttributes());
      String val = switch (p) {
        case io.opentelemetry.sdk.metrics.data.DoublePointData dp -> Double.toString(dp.getValue());
        case io.opentelemetry.sdk.metrics.data.LongPointData lp -> Long.toString(lp.getValue());
        default -> "0";
      };
      sb.append('{')
        .append("\"t\":\"").append(t).append("\",")
        .append("\"name\":\"").append(json(name)).append("\",")
        .append("\"type\":\"counter\",")
        .append("\"value\":").append(val).append(',')
        .append("\"tags\":").append(tags)
        .append('}').append('\n');
    }
    return sb.toString();
  }

  private String histogramLine(String name, HistogramPointData p) {
    String t = iso(p.getEpochNanos());
    List<Double> bounds = p.getBoundaries();
    List<Long> counts = p.getCounts();
    Percentiles pct = computePercentiles(bounds, counts);
    String tags = tagsJson(name, p.getAttributes());
    StringBuilder sb = new StringBuilder()
        .append('{')
        .append("\"t\":\"").append(t).append("\",")
        .append("\"name\":\"").append(json(name)).append("\",")
        .append("\"type\":\"histogram\",")
        .append("\"p50\":").append(pct.p50).append(',')
        .append("\"p95\":").append(pct.p95).append(',')
        .append("\"bounds\":[").append(joinDoubles(bounds)).append("],")
        .append("\"buckets\":[").append(joinLongs(counts)).append("],")
        .append("\"tags\":").append(tags)
        ;
    if (exemplarsEnabled && exemplarPolicies.getOrDefault(name, Exemplars.TRACE_BASED) != Exemplars.OFF) {
      var ex = exemplarsJson(new ArrayList<ExemplarData>(p.getExemplars()));
      sb.append(',').append("\"exemplars\":").append(ex);
    }
    sb.append('}').append('\n');
    return sb.toString();
  }

  private static Percentiles computePercentiles(List<Double> bounds, List<Long> counts) {
    long total = 0;
    for (Long c : counts) total += c;
    if (total == 0) return new Percentiles(0, 0);
    long p50Target = (long) Math.ceil(total * 0.50);
    long p95Target = (long) Math.ceil(total * 0.95);
    long cumulative = 0;
    double p50 = 0, p95 = 0;
    for (int i = 0; i < counts.size(); i++) {
      cumulative += counts.get(i);
      double upper = (i < bounds.size()) ? bounds.get(i) : Double.POSITIVE_INFINITY;
      if (p50 == 0 && cumulative >= p50Target) p50 = upper;
      if (p95 == 0 && cumulative >= p95Target) { p95 = upper; break; }
    }
    if (Double.isInfinite(p50)) p50 = 0;
    if (Double.isInfinite(p95)) p95 = 0;
    return new Percentiles((long) p50, (long) p95);
  }

  private record Percentiles(long p50, long p95) {}

  private static String joinDoubles(List<Double> xs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) sb.append(',');
      double v = xs.get(i);
      if (Double.isInfinite(v)) sb.append("null"); else sb.append((long) v);
    }
    return sb.toString();
  }

  private static String joinLongs(List<Long> xs) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < xs.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(xs.get(i));
    }
    return sb.toString();
  }

  private static String exemplarsJson(List<ExemplarData> exemplars) {
    if (exemplars == null || exemplars.isEmpty()) return "[]";
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < exemplars.size(); i++) {
      var e = exemplars.get(i);
      if (i > 0) sb.append(',');
      String traceId = e.getSpanContext().isValid() ? e.getSpanContext().getTraceId() : null;
      String spanId = e.getSpanContext().isValid() ? e.getSpanContext().getSpanId() : null;
      sb.append('{')
        .append("\"trace_id\":").append(traceId == null ? "null" : ("\"" + traceId + "\""))
        .append(',')
        .append("\"span_id\":").append(spanId == null ? "null" : ("\"" + spanId + "\""))
        .append('}');
    }
    return sb.append(']').toString();
  }

  private String tagsJson(String metricName, Attributes attrs) {
    // Per-metric tag-key emission order from catalog declarations. Tempdoc 417 Phase 2f cleanup:
    // ALLOWED_TAG_KEYS fallback removed — every emitted metric now has a catalog View, so the
    // SDK strips non-allowed keys before this exporter sees them. Iterate the declared key list;
    // un-registered names emit no tags rather than guessing.
    List<String> keys = tagKeyOrderByMetric.get(metricName);
    if (keys == null || keys.isEmpty()) {
      return "{}";
    }
    List<String> parts = new ArrayList<>();
    for (String key : keys) {
      String v = stringAttr(attrs, key);
      if (v != null) parts.add("\"" + json(key) + "\":\"" + json(v) + "\"");
    }
    return '{' + String.join(",", parts) + '}';
  }

  private static String stringAttr(Attributes attrs, String key) {
    Object v = attrs.get(AttributeKey.stringKey(key));
    return v == null ? null : String.valueOf(v);
  }

  private static String iso(long epochNanos) {
    return ISO.format(Instant.ofEpochMilli(epochNanos / 1_000_000).atOffset(ZoneOffset.UTC));
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
    return AggregationTemporality.CUMULATIVE;
  }

  @Override
  public io.opentelemetry.sdk.metrics.Aggregation getDefaultAggregation(InstrumentType instrumentType) {
    return io.opentelemetry.sdk.metrics.Aggregation.defaultAggregation();
  }

  private static String json(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void rotateIfNeeded() {
    try {
      if (Files.exists(metricsFile)) {
        long size = Files.size(metricsFile);
        if (size >= rotateMaxBytes) {
          String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(ZonedDateTime.now(ZoneOffset.UTC));
          Path rolled = metricsFile.getParent().resolve("metrics." + ts + ".ndjson");
          Files.move(metricsFile, rolled);
          if (healthState != null) {
            healthState.recordRotationSuccess();
          }
        }
      }
    } catch (Exception e) {
      // best-effort rotation: log and record failure but don't crash
      if (healthState != null) {
        healthState.recordRotationFailure();
      }
      log.warn("Metrics file rotation failed (best-effort): {}", e.getMessage());
    }
  }

  private void pruneByRetention() {
    try {
      var dir = metricsFile.getParent();
      if (dir == null || !Files.exists(dir)) return;
      var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
      try (var stream = Files.list(dir)) {
        stream.filter(p -> {
              String name = p.getFileName().toString();
              return name.startsWith("metrics.") && name.endsWith(".ndjson");
            })
            .forEach(p -> {
              try {
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                  Files.deleteIfExists(p);
                }
              } catch (Exception e) {
                // Log individual file prune failures at DEBUG level (can be noisy)
                if (healthState != null) {
                  healthState.recordPruneFailure();
                }
                log.debug("Failed to prune old metrics file {}: {}", p.getFileName(), e.getMessage());
              }
            });
      }
    } catch (Exception e) {
      // Log directory-level prune failure at WARN level
      if (healthState != null) {
        healthState.recordPruneFailure();
      }
      log.warn("Metrics retention pruning failed (best-effort): {}", e.getMessage());
    }
  }
}
