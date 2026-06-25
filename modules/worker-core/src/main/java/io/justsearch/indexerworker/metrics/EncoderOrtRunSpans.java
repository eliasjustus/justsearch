/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/**
 * Shared helpers for {@code encoder.ort_run} spans (tempdoc 400 LR2-a) and
 * the {@code enrichment.batch} parent span (tempdoc 400 LR2-a).
 *
 * <p>The zero-cost gate mirrors {@code IndexingLoop.maybeSpan}: when detailed
 * tracing is disabled (the default), {@link Span#getInvalid()} is returned
 * without touching the tracer — no span builder allocation, no attribute
 * calls, no export.
 *
 * <p>The gate reads {@code JUSTSEARCH_INDEX_TRACING_LEVEL} via
 * {@code EnvRegistry} once at class-load time. When the environment sets
 * {@code none} (default), all span methods short-circuit. Flip the env var
 * to {@code sample} (1% sampling via OTel's trace-id-ratio sampler) or
 * {@code detailed} (100%) to enable.
 *
 * <p>Each encoder class obtains its own {@link Tracer} via
 * {@link #encoderTracer} so that instrumentation-scope names
 * ({@code encoder.embed}, {@code encoder.splade}, etc.) differ per encoder
 * — Layer 4 projections filter spans by emitter via the scope name.
 */
public final class EncoderOrtRunSpans {

  private static final boolean DETAILED_TRACING;

  static {
    DETAILED_TRACING = !"none".equalsIgnoreCase(
        io.justsearch.configuration.EnvRegistry.INDEX_TRACING_LEVEL.getString("none"));
  }

  private EncoderOrtRunSpans() {}

  /** Returns a named tracer scoped to the given encoder (e.g. "embed"). */
  public static Tracer encoderTracer(String encoderName) {
    return GlobalOpenTelemetry.getTracer("encoder." + encoderName);
  }

  /** Returns the shared tracer used for the {@code enrichment.batch} parent span. */
  public static Tracer enrichmentTracer() {
    return GlobalOpenTelemetry.getTracer("enrichment");
  }

  /** True when detailed tracing is enabled; exposed for callers that need to avoid allocation. */
  public static boolean enabled() {
    return DETAILED_TRACING;
  }

  /**
   * Starts an {@code encoder.ort_run} span with the batch-size + seq-len attrs
   * known at call time. The {@code encoder.gpu} attribute is set by the caller
   * after {@code sessions.acquire()} returns (only then is {@code lease.isCpu()}
   * knowable), via {@link Span#setAttribute(String, boolean)}.
   *
   * <p>Starting the span <em>before</em> {@code sessions.acquire()} lets the
   * {@code lease.acquire} child span (tempdoc 400 LR2-b) emitted inside
   * {@code NativeSessionHandle} naturally parent under this span.
   *
   * <p>Returns {@link Span#getInvalid()} (zero-cost) when detailed tracing is off.
   */
  public static Span maybeOrtRun(Tracer tracer, String encoderName, int batchSize, int seqLen) {
    if (!DETAILED_TRACING) {
      return Span.getInvalid();
    }
    return tracer
        .spanBuilder("encoder.ort_run")
        .setAttribute("encoder.name", encoderName)
        .setAttribute("encoder.batch_size", (long) batchSize)
        .setAttribute("encoder.seq_len", (long) seqLen)
        .startSpan();
  }

  /**
   * Starts an {@code enrichment.batch} span. Returns {@link Span#getInvalid()}
   * when detailed tracing is off. Caller should wrap encoder inference in
   * {@code try (Scope ignored = span.makeCurrent()) { ... }} so nested
   * {@code encoder.ort_run} spans parent correctly.
   */
  public static Span maybeEnrichmentBatch() {
    if (!DETAILED_TRACING) {
      return Span.getInvalid();
    }
    return enrichmentTracer().spanBuilder("enrichment.batch").startSpan();
  }

  /**
   * Emits a {@code cpu_fallback.triggered} span event on the current span
   * (tempdoc 400 LR2-c). Used when GPU inference fails and the encoder falls
   * back to CPU mid-call (BFC arena exhaustion, session corruption, etc).
   *
   * <p>The event fires on whatever span is current when the fallback occurs
   * — typically an {@code encoder.ort_run} span. When no span is recording
   * (detailed tracing off), this is a no-op.
   */
  public static void emitCpuFallbackEvent(String cause, String encoderName) {
    Span current = Span.current();
    if (current == null || !current.isRecording()) {
      return;
    }
    current.addEvent(
        "cpu_fallback.triggered",
        io.opentelemetry.api.common.Attributes.of(
            io.opentelemetry.api.common.AttributeKey.stringKey("fallback.cause"),
            cause,
            io.opentelemetry.api.common.AttributeKey.stringKey("fallback.encoder"),
            encoderName));
  }
}
