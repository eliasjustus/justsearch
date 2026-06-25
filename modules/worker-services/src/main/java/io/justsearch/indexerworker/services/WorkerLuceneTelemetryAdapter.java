/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.adapters.lucene.runtime.ValidationReason;
import io.justsearch.indexerworker.services.IndexRuntimeTags.CommitTags;
import io.justsearch.indexerworker.services.IndexRuntimeTags.SwapTags;
import io.justsearch.indexerworker.services.IndexRuntimeTags.ValidationTags;
import io.justsearch.telemetry.catalog.EmptyTags;
import java.util.Objects;

/**
 * Bridges the runtime-internal {@link LuceneRuntimeTypes.TelemetryEvents} interface to the
 * production {@link IndexRuntimeMetricCatalog}.
 *
 * <p>Tempdoc 406 wired this adapter to the legacy {@code Telemetry} interface, with per-emit
 * histogram-config and free-text reason strings — both of which were silently dropped by the
 * SDK / NDJSON exporter. Tempdoc 417 Phase 1 retypes the bridge: the adapter now holds a
 * {@link IndexRuntimeMetricCatalog} populated by the catalog substrate, and emits via typed
 * instruments. Bucket bounds reach the wire format because they are catalog-declared; reasons
 * are sealed-style enums so typo'd strings don't compile.
 */
public final class WorkerLuceneTelemetryAdapter implements LuceneRuntimeTypes.TelemetryEvents {

  private final IndexRuntimeMetricCatalog catalog;

  public WorkerLuceneTelemetryAdapter(IndexRuntimeMetricCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public void onHardDelete() {
    catalog.hardDeleteTotal.increment(EmptyTags.INSTANCE);
  }

  @Override
  public void onHardDelete(int count) {
    if (count > 0) catalog.hardDeleteTotal.add(count, EmptyTags.INSTANCE);
  }

  @Override
  public void onSoftDelete(int count) {
    if (count > 0) catalog.softDeleteTotal.add(count, EmptyTags.INSTANCE);
  }

  @Override
  public void onBackpressure() {
    catalog.backpressureTotal.increment(EmptyTags.INSTANCE);
  }

  @Override
  public void onCommit(long latencyMs) {
    onCommit(latencyMs, CommitReason.UNKNOWN);
  }

  @Override
  public void onCommit(long latencyMs, CommitReason reason) {
    catalog.commitMs.record(latencyMs, CommitTags.of(reason == null ? CommitReason.UNKNOWN : reason));
  }

  @Override
  public void onValidationFailure(ValidationReason reason) {
    catalog.validationFailureTotal.increment(
        ValidationTags.of(reason == null ? ValidationReason.UNKNOWN : reason));
  }

  @Override
  public void onSwapStart(SwapReason reason) {
    catalog.swapStartedTotal.increment(
        SwapTags.of(reason == null ? SwapReason.UNKNOWN : reason));
  }

  @Override
  public void onSwapComplete(long durationMs, SwapReason reason) {
    catalog.swapDurationMs.record(
        durationMs, SwapTags.of(reason == null ? SwapReason.UNKNOWN : reason));
  }

  @Override
  public void onDrainTimeout(long elapsedMs, long writesStillPending) {
    catalog.drainTimeoutTotal.increment(EmptyTags.INSTANCE);
  }

  @Override
  public void onWriteBarrierContention(long waitNanos) {
    long waitMicros = waitNanos / 1_000L;
    catalog.writeBarrierWaitUs.record(waitMicros, EmptyTags.INSTANCE);
  }
}
