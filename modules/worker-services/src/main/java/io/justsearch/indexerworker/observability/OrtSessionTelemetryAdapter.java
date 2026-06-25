/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.observability;

import io.justsearch.indexerworker.observability.OrtSessionTags.AssemblerFailureTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.ConsumerOutcomeTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.ConsumerTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.GpuInitFailureTags;
import io.justsearch.indexerworker.observability.OrtSessionTags.RecoveryTags;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.telemetry.AssemblerEvent;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import io.justsearch.ort.telemetry.Outcome;
import io.justsearch.ort.telemetry.TransitionReason;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Production binding of {@link OrtSessionTelemetryEvents}: routes events into
 * {@link OrtSessionMetricCatalog} typed instruments. The {@code onTransition} dispatch is an
 * exhaustive {@code switch} over {@link TransitionReason} permits — adding a permit fails the
 * compile until this adapter handles it (drift-prevention contract). Same exhaustiveness on
 * {@code onAssemblerEvent} for {@link AssemblerEvent} permits.
 *
 * <p>Hot-path discipline: {@link #onSemaphoreWait} performs one {@code Map.get} + one histogram
 * record. The {@link ConsumerTags} cache is populated at construction for every expected
 * consumer so no per-emit allocation occurs on the per-inference path.
 *
 * <p>Tempdoc 414. Pattern reference: {@code WorkerLuceneTelemetryAdapter} (Phase 1 of tempdoc 417).
 */
public final class OrtSessionTelemetryAdapter implements OrtSessionTelemetryEvents {

  private final OrtSessionMetricCatalog catalog;
  private final Map<String, ConsumerTags> consumerTagsCache;

  /**
   * Constructs the adapter. The {@code expectedConsumers} set is used to pre-populate the
   * histogram tag cache so {@link #onSemaphoreWait} never allocates on the hot path. Consumers
   * not in this list still emit correctly via on-demand projection (see
   * {@link #consumerTagsFor}).
   *
   * <p>Production callers should prefer {@link #forAllRoles(OrtSessionMetricCatalog)} which
   * derives the consumer set from {@link EncoderRole#values()} — single source of truth, no
   * drift.
   */
  public OrtSessionTelemetryAdapter(
      OrtSessionMetricCatalog catalog, Collection<String> expectedConsumers) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(expectedConsumers, "expectedConsumers");
    this.consumerTagsCache = new HashMap<>();
    for (String consumer : expectedConsumers) {
      consumerTagsCache.put(consumer, new ConsumerTags(consumer));
    }
  }

  /**
   * Tempdoc 414 v2 (C1): static factory that derives the expected-consumers set from
   * {@link EncoderRole#values()} so {@link KnowledgeServer}-side wiring no longer carries a
   * hardcoded duplicate of the consumer-name list.
   */
  public static OrtSessionTelemetryAdapter forAllRoles(OrtSessionMetricCatalog catalog) {
    java.util.List<String> consumers =
        java.util.Arrays.stream(EncoderRole.values())
            .map(EncoderRole::consumerName)
            .collect(Collectors.toUnmodifiableList());
    return new OrtSessionTelemetryAdapter(catalog, consumers);
  }

  @Override
  public void onTransition(TransitionReason reason) {
    // Exhaustive switch — adding a permit to TransitionReason without a case here is a compile
    // error. Drift-prevention contract per tempdoc 414.
    switch (reason) {
      case TransitionReason.GpuInitialized r ->
          catalog.gpuInitTotal.increment(new ConsumerOutcomeTags(r.consumer(), Outcome.SUCCESS));
      case TransitionReason.GpuInitFailed r -> {
        catalog.gpuInitTotal.increment(new ConsumerOutcomeTags(r.consumer(), Outcome.FAILURE));
        catalog.gpuInitFailureTotal.increment(new GpuInitFailureTags(r.consumer(), r.cause()));
      }
      case TransitionReason.GpuReleaseCompleted r ->
          catalog.releaseTotal.increment(new ConsumerOutcomeTags(r.consumer(), Outcome.SUCCESS));
      case TransitionReason.GpuReleaseFailed r ->
          catalog.releaseTotal.increment(new ConsumerOutcomeTags(r.consumer(), Outcome.FAILURE));
      case TransitionReason.GpuFallbackTaken r ->
          catalog.fallbackTotal.increment(consumerTagsFor(r.consumer()));
      case TransitionReason.CpuSessionRecreated r ->
          catalog.recoveryTotal.increment(new RecoveryTags(r.consumer(), r.cause()));
      case TransitionReason.GpuRetryAttempted r -> {
        ConsumerTags tags = consumerTagsFor(r.consumer());
        catalog.retryTotal.increment(tags);
        catalog.retryIntervalMs.record(r.sinceFailureMs(), tags);
      }
    }
  }

  @Override
  public void onAssemblerEvent(AssemblerEvent event) {
    switch (event) {
      case AssemblerEvent.Failed e ->
          catalog.assemblerFailureTotal.increment(new AssemblerFailureTags(e.consumer(), e.kind()));
    }
  }

  @Override
  public void onSemaphoreWait(String consumer, long waitUs) {
    catalog.semaphoreWaitUs.record(waitUs, consumerTagsFor(consumer));
  }

  private ConsumerTags consumerTagsFor(String consumer) {
    ConsumerTags cached = consumerTagsCache.get(consumer);
    if (cached != null) return cached;
    // Unexpected consumer: emit correctly with on-demand projection. No silent drop.
    return new ConsumerTags(consumer);
  }
}
