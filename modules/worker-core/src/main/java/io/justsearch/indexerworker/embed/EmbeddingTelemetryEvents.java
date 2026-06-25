/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

/**
 * Zero-dependency events seam for {@link EmbeddingService} observability. Implementations live in
 * downstream modules ({@code worker-services}'s {@code EmbeddingTelemetry}) that depend on
 * {@code modules/telemetry}'s catalog types; this interface keeps {@code worker-core} free of
 * catalog imports while still letting {@code EmbeddingService} emit lifecycle events.
 *
 * <p>Tempdoc 413 — corresponds to the {@code embedding.runtime.*} metric namespace.
 */
public interface EmbeddingTelemetryEvents {

  /** Operation flavor for {@code embedding.runtime.invoke_failure_total}. */
  enum Operation {
    SINGLE,
    BATCH
  }

  /** Reason taxonomy for {@code embedding.runtime.invoke_failure_total}. */
  enum InvokeFailureReason {
    /** Backend (ONNX) threw a {@code BackendException} during inference. */
    BACKEND_EXCEPTION,
    /** Caller invoked an embed method after {@link EmbeddingService#close()}. */
    CLOSED,
    /** Caller passed null or blank text. */
    NULL_TEXT
  }

  /** Reason taxonomy for {@code embedding.runtime.unload_total}. */
  enum UnloadReason {
    /** Worker released the embedding model so Main could claim the GPU for chat. */
    GPU_HANDOFF,
    /** Worker shutdown closed the service. */
    SHUTDOWN
  }

  /** Fires when an embed call returns from the query cache. */
  void onCacheHit();

  /** Fires when an embed call falls through to backend inference. */
  void onCacheMiss();

  /**
   * Fires when the backend returns chunked vectors (text exceeded the context window). The
   * {@code chunkCount} carries the actual number of chunks produced; consumers can use this to
   * record a histogram of chunk-count distribution.
   */
  void onChunked(int chunkCount);

  /** Fires when an embed call returns null due to a failure or defensive guard. */
  void onInvokeFailure(Operation operation, InvokeFailureReason reason);

  /** Fires when the embedding service is closed. */
  void onUnload(UnloadReason reason);
}
