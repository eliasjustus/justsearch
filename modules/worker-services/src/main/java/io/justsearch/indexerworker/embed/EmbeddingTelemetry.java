/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.indexerworker.embed.EmbeddingTags.InvokeFailureTags;
import io.justsearch.indexerworker.embed.EmbeddingTags.UnloadTags;
import io.justsearch.telemetry.catalog.EmptyTags;

/**
 * Direct-emit façade over {@link EmbeddingMetricCatalog}, implementing the zero-dep
 * {@link EmbeddingTelemetryEvents} seam that {@link EmbeddingService} (worker-core) holds.
 *
 * <p>Tempdoc 413 — Pattern matches {@code AgentTelemetry} / {@code AgentMetricCatalog}
 * (single-class-bounded emit sites, no events-interface intermediary on the catalog side; the
 * interface here exists solely to keep worker-core free of catalog imports).
 */
public final class EmbeddingTelemetry implements EmbeddingTelemetryEvents {

  private final EmbeddingMetricCatalog catalog;

  public EmbeddingTelemetry(EmbeddingMetricCatalog catalog) {
    this.catalog = catalog;
  }

  /** Cached no-op singleton (F6 fix in tempdoc 417). */
  private static final EmbeddingTelemetry NOOP = new EmbeddingTelemetry(EmbeddingMetricCatalog.noop());

  /** No-op façade for tests / bootstrap paths without {@code LocalTelemetry}. */
  public static EmbeddingTelemetry noop() {
    return NOOP;
  }

  @Override
  public void onCacheHit() {
    catalog.cacheHitTotal.increment(EmptyTags.INSTANCE);
  }

  @Override
  public void onCacheMiss() {
    catalog.cacheMissTotal.increment(EmptyTags.INSTANCE);
  }

  @Override
  public void onChunked(int chunkCount) {
    if (chunkCount <= 0) {
      return;
    }
    catalog.chunkCount.record(chunkCount, EmptyTags.INSTANCE);
  }

  @Override
  public void onInvokeFailure(Operation operation, InvokeFailureReason reason) {
    if (operation == null || reason == null) {
      return;
    }
    catalog.invokeFailureTotal.increment(new InvokeFailureTags(operation, reason));
  }

  @Override
  public void onUnload(UnloadReason reason) {
    if (reason == null) {
      return;
    }
    catalog.unloadTotal.increment(new UnloadTags(reason));
  }
}
