package io.justsearch.app.services.observability.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cel.runtime.CelRuntime;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CelEvaluator")
final class CelEvaluatorTest {

  private static final Instant T0 = Instant.parse("2026-05-02T10:00:00Z");
  private static final Clock FIXED = Clock.fixed(T0, ZoneId.of("UTC"));

  // ============================================================
  // Signal / WindowedView (no CEL) — sanity tests
  // ============================================================

  @Test
  @DisplayName("Signal.latest returns most recent sample value")
  void signalLatestReturnsMostRecent() {
    StubRrdStore store = new StubRrdStore();
    long[] times = {T0.getEpochSecond() - 60, T0.getEpochSecond() - 30, T0.getEpochSecond()};
    double[] vals = {1.0, 2.0, 3.0};
    store.put("metric.x", new TimeSeriesResult("metric.x", times, vals));
    Signal s = new Signal("metric.x", store, FIXED);
    assertEquals(3.0, s.latest(), 1e-9);
  }

  @Test
  @DisplayName("Signal.latest throws MissingMetricException when no samples archived")
  void signalLatestThrowsOnMissing() {
    Signal s = new Signal("metric.absent", new StubRrdStore(), FIXED);
    assertThrows(MissingMetricException.class, s::latest);
  }

  @Test
  @DisplayName("WindowedView avg/min/max over fixed dataset")
  void windowedViewAggregations() {
    long[] times = {0, 1, 2, 3, 4};
    double[] vals = {10.0, 20.0, 30.0, 40.0, 50.0};
    WindowedView w = new WindowedView("m", times, vals);
    assertEquals(5L, w.count());
    assertEquals(30.0, w.avg(), 1e-9);
    assertEquals(10.0, w.min(), 1e-9);
    assertEquals(50.0, w.max(), 1e-9);
    // rate = (50 - 10) / (4 - 0) = 10.0 per second
    assertEquals(10.0, w.rate(), 1e-9);
  }

  @Test
  @DisplayName("WindowedView empty: count=0, accessors throw, rate=0")
  void windowedViewEmpty() {
    WindowedView w = WindowedView.empty("m");
    assertEquals(0L, w.count());
    assertEquals(0.0, w.rate(), 1e-9);
    assertThrows(MissingMetricException.class, w::avg);
    assertThrows(MissingMetricException.class, w::min);
    assertThrows(MissingMetricException.class, w::max);
  }

  // ============================================================
  // CEL evaluator — predicate evaluation
  // ============================================================

  @Test
  @DisplayName("CEL predicate signals['x'].latest() > 0.9 evaluates true on >0.9")
  void predicateAboveThreshold() {
    CelEvaluator evaluator = new CelEvaluator();
    StubRrdStore store = new StubRrdStore();
    long now = T0.getEpochSecond();
    store.put("x", new TimeSeriesResult("x", new long[] {now}, new double[] {0.95}));
    Signal s = new Signal("x", store, FIXED);
    Map<String, Signal> signals = Map.of("x", s);

    CelRuntime.Program program =
        evaluator.compile("test:above", "signals['x'].latest() > 0.9");
    PredicateOutcome result = evaluator.evaluatePredicate(program, signals, "above-test");
    assertEquals(PredicateOutcome.evaluated(true), result);
  }

  @Test
  @DisplayName("CEL predicate evaluates false when value <= threshold")
  void predicateBelowThreshold() {
    CelEvaluator evaluator = new CelEvaluator();
    StubRrdStore store = new StubRrdStore();
    long now = T0.getEpochSecond();
    store.put("x", new TimeSeriesResult("x", new long[] {now}, new double[] {0.5}));
    Map<String, Signal> signals = Map.of("x", new Signal("x", store, FIXED));

    CelRuntime.Program program =
        evaluator.compile("test:below", "signals['x'].latest() > 0.9");
    PredicateOutcome result = evaluator.evaluatePredicate(program, signals, "below-test");
    assertEquals(PredicateOutcome.evaluated(false), result);
  }

  @Test
  @DisplayName("memory.pressure-style ratio expression evaluates correctly")
  void memoryPressureRatioExpression() {
    CelEvaluator evaluator = new CelEvaluator();
    StubRrdStore store = new StubRrdStore();
    long now = T0.getEpochSecond();
    store.put(
        "head.jvm.memory.heap.used_bytes",
        new TimeSeriesResult(
            "head.jvm.memory.heap.used_bytes", new long[] {now}, new double[] {950_000_000.0}));
    store.put(
        "head.jvm.memory.heap.max_bytes",
        new TimeSeriesResult(
            "head.jvm.memory.heap.max_bytes", new long[] {now}, new double[] {1_000_000_000.0}));
    Map<String, Signal> signals = new HashMap<>();
    signals.put(
        "head.jvm.memory.heap.used_bytes",
        new Signal("head.jvm.memory.heap.used_bytes", store, FIXED));
    signals.put(
        "head.jvm.memory.heap.max_bytes",
        new Signal("head.jvm.memory.heap.max_bytes", store, FIXED));

    CelRuntime.Program program =
        evaluator.compile(
            "test:ratio",
            "signals['head.jvm.memory.heap.used_bytes'].latest()"
                + " / signals['head.jvm.memory.heap.max_bytes'].latest() > 0.9");
    PredicateOutcome result = evaluator.evaluatePredicate(program, signals, "ratio-test");
    assertEquals(PredicateOutcome.evaluated(true), result);
  }

  @Test
  @DisplayName("missing metric → Indeterminate (tempdoc 600 Design B: NOT predicate-false)")
  void missingMetricIsIndeterminate() {
    CelEvaluator evaluator = new CelEvaluator();
    StubRrdStore store = new StubRrdStore();
    Map<String, Signal> signals = Map.of("absent", new Signal("absent", store, FIXED));

    CelRuntime.Program program =
        evaluator.compile("test:missing", "signals['absent'].latest() > 0.9");
    // MissingMetricException internally → CelEvaluationException → caught → Indeterminate
    // (a blind rule must be distinguishable from an evaluated-false / healthy one).
    PredicateOutcome result = evaluator.evaluatePredicate(program, signals, "missing-test");
    assertInstanceOf(PredicateOutcome.Indeterminate.class, result);
    assertFalse(((PredicateOutcome.Indeterminate) result).reason().isBlank());
  }

  // ============================================================
  // CEL parse-time validation
  // ============================================================

  @Test
  @DisplayName("malformed CEL expression rejected at compile time")
  void malformedExpressionRejected() {
    CelEvaluator evaluator = new CelEvaluator();
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluator.compile("test:bad", "signals['x'].latest() > > 0.9"));
  }

  @Test
  @DisplayName("unknown function rejected at compile time")
  void unknownFunctionRejected() {
    CelEvaluator evaluator = new CelEvaluator();
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluator.compile("test:unk", "signals['x'].nonexistent_fn() > 0.9"));
  }

  // ============================================================
  // Window aggregation via CEL
  // ============================================================

  @Test
  @DisplayName("window().avg() invokes WindowedView aggregation through CEL")
  void windowAvgViaCel() {
    CelEvaluator evaluator = new CelEvaluator();
    StubRrdStore store = new StubRrdStore();
    long now = T0.getEpochSecond();
    store.put(
        "x",
        new TimeSeriesResult(
            "x", new long[] {now - 60, now - 30, now}, new double[] {100.0, 200.0, 300.0}));
    Map<String, Signal> signals = Map.of("x", new Signal("x", store, FIXED));

    CelRuntime.Program program =
        evaluator.compile("test:winavg", "signals['x'].window('5m').avg() > 150.0");
    PredicateOutcome result = evaluator.evaluatePredicate(program, signals, "winavg-test");
    assertEquals(PredicateOutcome.evaluated(true), result); // (100+200+300)/3 = 200 > 150
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static final class StubRrdStore extends RrdMetricStore {
    private final Map<String, TimeSeriesResult> data = new HashMap<>();

    StubRrdStore() {
      super(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "cel-test-stub"));
    }

    void put(String name, TimeSeriesResult result) {
      data.put(name, result);
    }

    @Override
    public synchronized TimeSeriesResult query(String metricName, long start, long end) {
      return data.get(metricName);
    }

    @Override
    public synchronized void initialize() {
      // No-op for tests; we don't open an RRD file.
    }
  }
}
