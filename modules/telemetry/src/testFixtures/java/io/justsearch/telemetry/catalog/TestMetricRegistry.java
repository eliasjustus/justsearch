package io.justsearch.telemetry.catalog;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Test fixture: a {@link MetricRegistry} backed by an in-memory OTel SDK with a capturing
 * exporter. Use this in unit tests to construct catalogs without a real {@link
 * io.justsearch.telemetry.LocalTelemetry} or NDJSON parsing.
 *
 * <p>Tempdoc 417 F8 fix. Pattern:
 *
 * <pre>{@code
 * var registry = new TestMetricRegistry(IndexRuntimeMetricCatalog.DEFINITIONS);
 * var catalog = new IndexRuntimeMetricCatalog(registry);
 * catalog.commitMs.record(42L, CommitTags.of(CommitReason.DRAIN));
 *
 * registry.flush();  // force the SDK to export to the in-memory capture
 * assertEquals(List.of(42L),
 *     registry.histogramSamples("index.runtime.commit_ms",
 *         CommitTags.of(CommitReason.DRAIN)));
 * }</pre>
 *
 * <p>Closing the registry releases the SDK resources. Tests typically declare it in
 * {@code @BeforeEach} and close in {@code @AfterEach}.
 */
public final class TestMetricRegistry implements MetricRegistry, AutoCloseable {

  private final SdkMeterProvider provider;
  private final CapturingExporter exporter;
  private final PeriodicMetricReader reader;
  private final Map<String, MetricDefinition> definitions;

  public TestMetricRegistry(List<MetricDefinition> definitions) {
    Objects.requireNonNull(definitions, "definitions");
    this.definitions = new HashMap<>();
    for (MetricDefinition def : definitions) {
      if (this.definitions.put(def.name(), def) != null) {
        throw new IllegalArgumentException("Duplicate metric definition: " + def.name());
      }
    }
    this.exporter = new CapturingExporter();
    this.reader =
        PeriodicMetricReader.builder(exporter)
            .setInterval(java.time.Duration.ofMinutes(10))
            .build();
    SdkMeterProviderBuilder builder = SdkMeterProvider.builder().registerMetricReader(reader);
    // Register the same per-metric Views LocalTelemetry would: tag schemas + bucket bounds.
    for (MetricDefinition def : definitions) {
      InstrumentType iType = mapKind(def.kind());
      if (iType == null) continue;
      var selector = InstrumentSelector.builder().setType(iType).setName(def.name()).build();
      var viewBuilder = View.builder();
      if (!def.allowedTagKeys().isEmpty()) {
        viewBuilder.setAttributeFilter(new LinkedHashSet<>(def.allowedTagKeys()));
      }
      if (def.kind() == InstrumentKind.HISTOGRAM && def.bucketBoundaries() != null) {
        List<Double> bounds = new ArrayList<>(def.bucketBoundaries().size());
        for (Long b : def.bucketBoundaries()) bounds.add(b.doubleValue());
        viewBuilder.setAggregation(Aggregation.explicitBucketHistogram(bounds));
      }
      builder = builder.registerView(selector, viewBuilder.build());
    }
    this.provider = builder.build();
  }

  private static InstrumentType mapKind(InstrumentKind kind) {
    return switch (kind) {
      case COUNTER -> InstrumentType.COUNTER;
      case HISTOGRAM -> InstrumentType.HISTOGRAM;
      case GAUGE -> InstrumentType.OBSERVABLE_GAUGE;
      case OBSERVABLE_COUNTER -> InstrumentType.OBSERVABLE_COUNTER;
    };
  }

  private MetricDefinition resolve(String name, InstrumentKind expected) {
    MetricDefinition def = definitions.get(name);
    if (def == null) {
      throw new IllegalArgumentException("No definition for metric '" + name + "'");
    }
    if (def.kind() != expected) {
      throw new IllegalArgumentException(
          "Metric '" + name + "' has kind " + def.kind() + ", not " + expected);
    }
    return def;
  }

  private Meter meter(MetricDefinition def) {
    int dot = def.name().lastIndexOf('.');
    String scope = dot > 0 ? def.name().substring(0, dot) : def.name();
    return provider.meterBuilder(scope).build();
  }

  @Override
  public <T extends TagSchema> CounterMetric<T> buildCounter(String name) {
    MetricDefinition def = resolve(name, InstrumentKind.COUNTER);
    var counter = meter(def).counterBuilder(name).build();
    return new CounterMetric<>(def, counter);
  }

  @Override
  public <T extends TagSchema> HistogramMetric<T> buildHistogram(String name) {
    MetricDefinition def = resolve(name, InstrumentKind.HISTOGRAM);
    var hist = meter(def).histogramBuilder(name).ofLongs().build();
    return new HistogramMetric<>(def, hist);
  }

  @Override
  public <T extends TagSchema> GaugeMetric<T> buildGauge(
      String name, T tags, Supplier<Double> supplier) {
    MetricDefinition def = resolve(name, InstrumentKind.GAUGE);
    Attributes attrs = tags.toAttributes();
    Object handle =
        meter(def)
            .gaugeBuilder(name)
            .buildWithCallback(
                m -> {
                  Double v = supplier.get();
                  m.record(v == null ? 0.0d : v, attrs);
                });
    return new GaugeMetric<>(def, tags, handle);
  }

  @Override
  public <T extends TagSchema> ObservableCounterMetric<T> buildObservableCounter(
      String name, T tags, LongSupplier supplier) {
    MetricDefinition def = resolve(name, InstrumentKind.OBSERVABLE_COUNTER);
    Attributes attrs = tags.toAttributes();
    Object handle =
        meter(def)
            .counterBuilder(name)
            .buildWithCallback(m -> m.record(supplier.getAsLong(), attrs));
    return new ObservableCounterMetric<>(def, tags, handle);
  }

  /** Forces the SDK to export collected metrics into the capture buffer. */
  public void flush() {
    provider.forceFlush().join(5, TimeUnit.SECONDS);
  }

  // ============================================================================
  // Query API
  // ============================================================================

  /**
   * Returns the cumulative counter value for {@code name} matching the given tag schema. Returns
   * 0 if the counter hasn't been emitted with these tags yet. Auto-flushes before reading.
   */
  public long counterValue(String name, TagSchema tags) {
    flush();
    return findLatestPoint(name, tags.toAttributes())
        .map(p -> ((io.opentelemetry.sdk.metrics.data.LongPointData) p).getValue())
        .orElse(0L);
  }

  /**
   * Returns the histogram bucket counts for {@code name} matching the given tag schema. Returns
   * an empty list if no points are recorded. Auto-flushes before reading.
   */
  public List<Long> histogramBucketCounts(String name, TagSchema tags) {
    flush();
    return findLatestPoint(name, tags.toAttributes())
        .map(p -> List.copyOf(((HistogramPointData) p).getCounts()))
        .orElse(List.of());
  }

  /**
   * Returns the histogram total count (sum of bucket counts) for {@code name} matching the given
   * tag schema.
   */
  public long histogramCount(String name, TagSchema tags) {
    flush();
    return findLatestPoint(name, tags.toAttributes())
        .map(p -> ((HistogramPointData) p).getCount())
        .orElse(0L);
  }

  /** Returns the set of metric names emitted at least once. Auto-flushes. */
  public Set<String> emittedNames() {
    flush();
    Set<String> names = new LinkedHashSet<>();
    for (MetricData m : exporter.latest()) {
      names.add(m.getName());
    }
    return names;
  }

  private java.util.Optional<PointData> findLatestPoint(String name, Attributes wanted) {
    Collection<MetricData> latest = exporter.latest();
    PointData found = null;
    for (MetricData m : latest) {
      if (!m.getName().equals(name)) continue;
      Collection<? extends PointData> points =
          switch (m.getType()) {
            case HISTOGRAM -> m.getHistogramData().getPoints();
            case LONG_SUM -> m.getLongSumData().getPoints();
            case DOUBLE_SUM -> m.getDoubleSumData().getPoints();
            case LONG_GAUGE -> m.getLongGaugeData().getPoints();
            case DOUBLE_GAUGE -> m.getDoubleGaugeData().getPoints();
            default -> List.of();
          };
      for (PointData p : points) {
        if (attributesMatch(p.getAttributes(), wanted)) {
          found = p;
        }
      }
    }
    return java.util.Optional.ofNullable(found);
  }

  /**
   * Compares two {@link Attributes} for value equality on the keys present in {@code wanted}.
   * Captured attributes may have additional keys (e.g., from an unfiltered View); we only assert
   * the wanted keys match.
   */
  private static boolean attributesMatch(Attributes captured, Attributes wanted) {
    return wanted.asMap().entrySet().stream()
        .allMatch(
            e -> {
              AttributeKey<?> key = e.getKey();
              Object capturedValue = captured.get(key);
              return capturedValue != null && capturedValue.equals(e.getValue());
            });
  }

  @Override
  public void close() {
    provider.close();
  }

  /**
   * In-memory {@link MetricExporter} that retains the most recent batch of exported metrics.
   * Replacement for {@code opentelemetry-sdk-testing}'s {@code InMemoryMetricReader} (which is
   * not on this module's classpath).
   */
  private static final class CapturingExporter implements MetricExporter {
    private volatile Collection<MetricData> latest = List.of();

    Collection<MetricData> latest() {
      return latest;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
      this.latest = new ArrayList<>(metrics);
      return CompletableResultCode.ofSuccess();
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
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
      return Aggregation.defaultAggregation();
    }
  }
}
