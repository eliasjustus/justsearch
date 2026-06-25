package io.justsearch.telemetry.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link NoopMetricRegistry} returns functional {@link Metric} instances backed by
 * OTel no-op primitives. Tempdoc 417 Phase 2a.
 */
final class NoopMetricRegistryTest {

  private static final MetricDefinition COUNTER_DEF =
      MetricDefinition.counter("test.noop.counter").unit(Unit.COUNT).build();
  private static final MetricDefinition HISTOGRAM_DEF =
      MetricDefinition.histogram("test.noop.histogram")
          .unit(Unit.MILLISECONDS)
          .buckets(List.of(10L, 100L, 1000L))
          .build();
  private static final MetricDefinition GAUGE_DEF =
      MetricDefinition.gauge("test.noop.gauge").unit(Unit.COUNT).build();
  private static final MetricDefinition OBSERVABLE_COUNTER_DEF =
      MetricDefinition.observableCounter("test.noop.observable_counter").unit(Unit.COUNT).build();

  @Test
  void buildCounterReturnsNonNullCounterAndEmitsAreSilent() {
    var registry = new NoopMetricRegistry(List.of(COUNTER_DEF));
    CounterMetric<EmptyTags> counter = registry.buildCounter(COUNTER_DEF.name());
    assertNotNull(counter);
    assertEquals(COUNTER_DEF, counter.definition());
    assertDoesNotThrow(() -> counter.increment(EmptyTags.INSTANCE));
    assertDoesNotThrow(() -> counter.add(42L, EmptyTags.INSTANCE));
  }

  @Test
  void buildHistogramReturnsNonNullHistogramAndEmitsAreSilent() {
    var registry = new NoopMetricRegistry(List.of(HISTOGRAM_DEF));
    HistogramMetric<EmptyTags> hist = registry.buildHistogram(HISTOGRAM_DEF.name());
    assertNotNull(hist);
    assertEquals(HISTOGRAM_DEF, hist.definition());
    assertDoesNotThrow(() -> hist.record(123L, EmptyTags.INSTANCE));
  }

  @Test
  void buildGaugeReturnsNonNullGaugeAndSupplierIsRetained() {
    var registry = new NoopMetricRegistry(List.of(GAUGE_DEF));
    AtomicLong source = new AtomicLong(7L);
    GaugeMetric<EmptyTags> gauge =
        registry.buildGauge(GAUGE_DEF.name(), EmptyTags.INSTANCE, () -> (double) source.get());
    assertNotNull(gauge);
    assertEquals(GAUGE_DEF, gauge.definition());
    assertEquals(EmptyTags.INSTANCE, gauge.tags());
    assertDoesNotThrow(gauge::close);
  }

  @Test
  void buildObservableCounterReturnsNonNullAndSupplierIsRetained() {
    var registry = new NoopMetricRegistry(List.of(OBSERVABLE_COUNTER_DEF));
    AtomicLong source = new AtomicLong(99L);
    ObservableCounterMetric<EmptyTags> oc =
        registry.buildObservableCounter(
            OBSERVABLE_COUNTER_DEF.name(), EmptyTags.INSTANCE, source::get);
    assertNotNull(oc);
    assertEquals(OBSERVABLE_COUNTER_DEF, oc.definition());
    assertDoesNotThrow(oc::close);
  }

  @Test
  void unregisteredNameThrows() {
    var registry = new NoopMetricRegistry(List.of(COUNTER_DEF));
    var ex =
        assertThrows(IllegalArgumentException.class, () -> registry.buildCounter("not.registered"));
    assertNotNull(ex.getMessage());
  }

  @Test
  void wrongKindThrows() {
    var registry = new NoopMetricRegistry(List.of(COUNTER_DEF));
    assertThrows(
        IllegalArgumentException.class, () -> registry.buildHistogram(COUNTER_DEF.name()));
  }

  @Test
  void duplicateDefinitionThrows() {
    var dup = MetricDefinition.counter("test.noop.counter").build();
    assertThrows(IllegalArgumentException.class, () -> new NoopMetricRegistry(List.of(dup, dup)));
  }
}
