/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * Recording hook invoked by {@link SessionHandle.Lease#run} / {@link SessionHandle.Lease#runPinned}
 * immediately after a successful ORT inference call, with the elapsed wall time of that call alone
 * (tensor construction, tokenization, and GPU-semaphore queueing are NOT included — those are
 * measured elsewhere; see {@code OrtSessionTelemetryEvents#onSemaphoreWait} for the queueing half).
 *
 * <p>Tempdoc 710 Move 2: recording moves to the choke point where the ORT run actually happens
 * (the {@link SessionHandle.Lease}) so a call site cannot forget to record — the failure class this
 * retires shipped twice under the old per-call-site-{@code recordOrtCall} regime (NER's batched
 * path, tempdoc 691 B-5; embed's {@code runHidden}/late-chunking path, tempdoc 710 S-B3).
 *
 * <p><strong>Module boundary.</strong> {@code ort-common} does not depend on {@code worker-core}
 * (which owns {@code EncoderProfileAccumulator} / {@code OperationalMetrics}), so this interface is
 * the minimal seam a caller in a higher module binds to via {@link SessionHandle#setOrtRunRecorder}
 * — typically a method reference to an accumulator's record method (e.g.
 * {@code profiler::recordOrtCall}). {@code ort-common} never references the concrete metrics type.
 *
 * <p><strong>Naming (tempdoc 710 R-4).</strong> This hook intentionally does NOT adopt OTel GenAI
 * semantic-convention names ({@code gen_ai.operation.name="embeddings"}, etc.) — those apply to the
 * span/attribute vocabulary emitted by {@code EncoderOrtRunSpans}, which stays a separate, orthogonal
 * concern (spans know batch/seq context this choke point structurally does not; per-lane profiler
 * accumulation stays bespoke since the GenAI conventions are Development-tier and have no per-lane
 * p50/p95/p99 concept). Nothing here couples to a wire contract.
 *
 * <p><strong>Semantics.</strong> A call is recorded only when {@code session.run(...)} returns
 * successfully — a thrown {@link ai.onnxruntime.OrtException} (e.g. a BFC-arena OOM that triggers a
 * fallback retry) is not recorded for that attempt; the caller's subsequent successful retry (on the
 * same or a different {@link SessionHandle.Lease}) is recorded on its own. This mirrors the
 * pre-Move-2 per-call-site convention exactly ("record one call, once, on success").
 */
@FunctionalInterface
public interface OrtRunRecorder {

  /** No-op recorder — the default until a caller binds one via {@link SessionHandle#setOrtRunRecorder}. */
  OrtRunRecorder NOOP = elapsedNanos -> {};

  /**
   * Records one successful ORT inference call.
   *
   * @param elapsedNanos wall-clock nanoseconds spent inside {@code session.run(...)} alone
   */
  void recordOrtRunNs(long elapsedNanos);
}
