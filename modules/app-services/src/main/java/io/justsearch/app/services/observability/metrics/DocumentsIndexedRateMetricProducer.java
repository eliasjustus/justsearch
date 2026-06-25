/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.metrics;

import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricChangeRegistry;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricResourceCatalog;
import io.justsearch.app.observability.metrics.TimeseriesSnapshot;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import io.justsearch.telemetry.RrdMetricStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic producer for the {@code core.metric-worker-documents-indexed-rate-per-sec}
 * TIMESERIES Resource — slice 3a.1.4b cohort follow-up.
 *
 * <p>Mirrors {@code JobQueueDepthMetricProducer} (the canonical first instance). Reads
 * the head's {@link RrdMetricStore} for the {@code worker.documents.indexed.rate_per_sec}
 * metric over a 30-minute window, sampled every 30 seconds; broadcasts via the
 * {@link DocumentsIndexedRateMetricChangeRegistry} with no-op suppression on identical
 * value arrays.
 */
public final class DocumentsIndexedRateMetricProducer {

  private static final Logger log =
      LoggerFactory.getLogger(DocumentsIndexedRateMetricProducer.class);

  private static final String METRIC_NAME = "worker.documents.indexed.rate_per_sec";
  private static final Duration WINDOW = Duration.ofMinutes(30);
  private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(30);
  private static final ResourceRef RESOURCE_ID =
      DocumentsIndexedRateMetricResourceCatalog.DOCUMENTS_INDEXED_RATE_ID;
  private static final String UNIT = "docs/s";

  private final Supplier<RrdMetricStore> rrdStoreSupplier;
  private final TimeseriesSnapshotHolder holder;
  private final DocumentsIndexedRateMetricChangeRegistry registry;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;

  public DocumentsIndexedRateMetricProducer(
      Supplier<RrdMetricStore> rrdStoreSupplier,
      TimeseriesSnapshotHolder holder,
      DocumentsIndexedRateMetricChangeRegistry registry) {
    this(rrdStoreSupplier, holder, registry, Clock.systemUTC());
  }

  public DocumentsIndexedRateMetricProducer(
      Supplier<RrdMetricStore> rrdStoreSupplier,
      TimeseriesSnapshotHolder holder,
      DocumentsIndexedRateMetricChangeRegistry registry,
      Clock clock) {
    this.rrdStoreSupplier = Objects.requireNonNull(rrdStoreSupplier, "rrdStoreSupplier");
    this.holder = Objects.requireNonNull(holder, "holder");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "metrics-documents-indexed-rate-producer");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    long intervalSec = SAMPLE_INTERVAL.toSeconds();
    var unused =
        scheduler.scheduleAtFixedRate(this::tick, intervalSec, intervalSec, TimeUnit.SECONDS);
  }

  public void stop() {
    scheduler.shutdownNow();
  }

  public void tick() {
    try {
      TimeseriesSnapshot next = buildSnapshot();
      if (next == null) return;
      TimeseriesSnapshot previous = holder.set(next);
      if (snapshotsEquivalent(previous, next)) return;
      registry.broadcast(next);
    } catch (RuntimeException e) {
      log.warn("Documents-indexed-rate metric producer tick failed: {}", e.getMessage());
    }
  }

  /**
   * Publishes a snapshot built directly from a values array. Sibling of
   * {@code JobQueueDepthMetricProducer.publishFromValues} — closes the same
   * worker→head metric replication gap (observations.md inbox item #1,
   * 2026-05-08) by reading the worker-shipped {@code recent_docs_per_sec}
   * array out of the {@code CoreStatus} gRPC view instead of polling the
   * (empty) head-side RRD.
   */
  public void publishFromValues(double[] values) {
    if (values == null) return;
    try {
      Instant now = clock.instant();
      long endEpoch = now.getEpochSecond();
      long startEpoch = endEpoch - WINDOW.toSeconds();
      long catalogVersion = registry.currentSeq() + 1L;
      TimeseriesSnapshot next =
          new TimeseriesSnapshot(
              RESOURCE_ID,
              WINDOW.toMillis(),
              SAMPLE_INTERVAL.toMillis(),
              UNIT,
              values,
              Instant.ofEpochSecond(startEpoch),
              Instant.ofEpochSecond(endEpoch),
              catalogVersion);
      TimeseriesSnapshot previous = holder.set(next);
      if (snapshotsEquivalent(previous, next)) return;
      registry.broadcast(next);
    } catch (RuntimeException e) {
      log.warn("Documents-indexed-rate metric publishFromValues failed: {}", e.getMessage());
    }
  }

  private TimeseriesSnapshot buildSnapshot() {
    RrdMetricStore store;
    try {
      store = rrdStoreSupplier.get();
    } catch (RuntimeException e) {
      return null;
    }
    if (store == null) return null;
    Instant now = clock.instant();
    long endEpoch = now.getEpochSecond();
    long startEpoch = endEpoch - WINDOW.toSeconds();
    RrdMetricStore.TimeSeriesResult result;
    try {
      result = store.query(METRIC_NAME, startEpoch, endEpoch);
    } catch (RuntimeException e) {
      log.debug("RRD query for {} failed: {}", METRIC_NAME, e.getMessage());
      return null;
    }
    if (result == null) return null;
    double[] values = result.values();
    if (values == null) values = new double[0];
    Instant startedAt = Instant.ofEpochSecond(startEpoch);
    Instant endedAt = Instant.ofEpochSecond(endEpoch);
    long catalogVersion = registry.currentSeq() + 1L;
    return new TimeseriesSnapshot(
        RESOURCE_ID,
        WINDOW.toMillis(),
        SAMPLE_INTERVAL.toMillis(),
        UNIT,
        values,
        startedAt,
        endedAt,
        catalogVersion);
  }

  private static boolean snapshotsEquivalent(TimeseriesSnapshot prev, TimeseriesSnapshot next) {
    if (prev == null) return false;
    double[] a = prev.values();
    double[] b = next.values();
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (Double.compare(a[i], b[i]) != 0) return false;
    }
    return true;
  }
}
