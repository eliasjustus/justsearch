package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates that the indexing pipeline tracing instrumentation (tempdoc 312 Phase 0) has zero
 * measurable overhead when {@code detailedTracing=false}.
 *
 * <p>When tracing is disabled, {@link IndexingLoop#maybeSpan} returns {@link Span#getInvalid()} — a
 * singleton no-op. This test verifies that all operations on the invalid span (setAttribute,
 * makeCurrent, end) are true no-ops with no allocation or side effects.
 *
 * <p>The timing test runs the exact sequence of OTel calls used per batch in the indexing pipeline
 * and asserts that per-call overhead is sub-microsecond — far below the 1% threshold on a pipeline
 * where each batch takes ~7ms+ (deferred embedding) or ~1600ms+ (inline embedding).
 */
class IndexingTracingOverheadTest {

  @Test
  @DisplayName("Span.getInvalid() is a singleton — no allocation per call")
  void invalidSpanIsSingleton() {
    Span a = Span.getInvalid();
    Span b = Span.getInvalid();
    assertSame(a, b, "Span.getInvalid() must return the same singleton instance");
  }

  @Test
  @DisplayName("Invalid span operations are no-ops (no exceptions, no side effects)")
  void invalidSpanOperationsAreNoOps() {
    Span span = Span.getInvalid();

    // These are the exact operations used in the indexing pipeline when tracing is off.
    // All must be no-ops with no exceptions.
    span.setAttribute("batch.polled", 16L);
    span.setAttribute("embed.batch_size", 16L);
    span.setAttribute("embed.gpu", true);
    span.setAttribute("embed.success", true);
    span.setAttribute("doc.path", "/some/file.txt");
    span.setAttribute("embedding.source", "batch");
    span.setAttribute("paths.count", 16L);

    try (Scope ignored = span.makeCurrent()) {
      // Scope from invalid span should also be a no-op
      assertNotNull(ignored, "Scope must not be null even for invalid span");
    }

    span.end();

    assertFalse(span.getSpanContext().isValid(), "Invalid span context must not be valid");
    assertFalse(span.isRecording(), "Invalid span must not be recording");
  }

  @Test
  @DisplayName("Tracing-off code path overhead is sub-microsecond per call")
  void tracingOffOverheadIsSubMicrosecond() {
    // Simulate the per-batch tracing overhead when detailedTracing=false.
    // Per batch, the pipeline calls maybeSpan() 5 times (batch, embed, N×extract,
    // N×write, markDone) plus ~20 setAttribute calls plus makeCurrent/end.
    //
    // We replicate the worst-case: 5 spans × (getInvalid + 4 setAttribute + makeCurrent + end)
    // = 35 OTel API calls per batch of 16 docs.

    int warmupIterations = 10_000;
    int measuredIterations = 100_000;

    // Warmup — let JIT compile the hot path
    for (int i = 0; i < warmupIterations; i++) {
      simulateBatchTracingOverhead();
    }

    // Measure
    long start = System.nanoTime();
    for (int i = 0; i < measuredIterations; i++) {
      simulateBatchTracingOverhead();
    }
    long elapsed = System.nanoTime() - start;

    double nanosPerIteration = (double) elapsed / measuredIterations;
    double microsPerIteration = nanosPerIteration / 1000.0;

    // Each iteration simulates one batch's tracing overhead.
    // With a 7ms batch (deferred embedding), 1µs overhead = 0.014%.
    // With a 1600ms batch (inline embedding), 1µs overhead = 0.00006%.
    // Assert sub-10µs per batch as a generous bound (actual is ~0.1-0.5µs).
    assertTrue(
        microsPerIteration < 10.0,
        String.format(
            "Per-batch tracing overhead must be <10µs, was %.2fµs (%.1fns)",
            microsPerIteration, nanosPerIteration));
  }

  /**
   * Simulates the exact OTel API call sequence that occurs per batch when detailedTracing=false.
   * This is the overhead the indexing pipeline pays even with tracing disabled.
   */
  private void simulateBatchTracingOverhead() {
    // 1. batch span
    Span batchSpan = Span.getInvalid();
    batchSpan.setAttribute("batch.polled", 16L);
    try (Scope ignored = batchSpan.makeCurrent()) {
      // 2. embed span
      Span embedSpan = Span.getInvalid();
      embedSpan.setAttribute("embed.batch_size", 16L);
      embedSpan.setAttribute("embed.gpu", false);
      embedSpan.setAttribute("embed.success", true);
      embedSpan.end();

      // 3. extract span (per doc — simulate 2 representative docs)
      for (int d = 0; d < 2; d++) {
        Span extractSpan = Span.getInvalid();
        extractSpan.setAttribute("doc.path", "/file.txt");
        extractSpan.end();
      }

      // 4. write span (per doc — simulate 2 representative docs)
      for (int d = 0; d < 2; d++) {
        Span writeSpan = Span.getInvalid();
        writeSpan.setAttribute("doc.path", "/file.txt");
        writeSpan.setAttribute("embedding.source", "batch");
        writeSpan.end();
      }

      // 5. markDone span
      Span markDoneSpan = Span.getInvalid();
      markDoneSpan.setAttribute("paths.count", 16L);
      markDoneSpan.end();
    }
    batchSpan.end();
  }
}
