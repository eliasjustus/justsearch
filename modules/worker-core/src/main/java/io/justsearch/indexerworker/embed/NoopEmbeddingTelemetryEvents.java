/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

/**
 * No-op default for {@link EmbeddingTelemetryEvents}. Used when no telemetry is wired (tests,
 * pre-bootstrap construction, package-private convenience constructors).
 */
public final class NoopEmbeddingTelemetryEvents implements EmbeddingTelemetryEvents {

  public static final NoopEmbeddingTelemetryEvents INSTANCE = new NoopEmbeddingTelemetryEvents();

  private NoopEmbeddingTelemetryEvents() {}

  @Override
  public void onCacheHit() {}

  @Override
  public void onCacheMiss() {}

  @Override
  public void onChunked(int chunkCount) {}

  @Override
  public void onInvokeFailure(Operation operation, InvokeFailureReason reason) {}

  @Override
  public void onUnload(UnloadReason reason) {}
}
