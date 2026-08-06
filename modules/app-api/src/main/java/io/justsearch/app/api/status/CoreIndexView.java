/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Core index status snapshot exposed via {@code /api/status}. Tempdoc 406
 * extended with 4 runtime gauges ({@code writerQueueDepth},
 * {@code writerPendingDocs}, {@code commitCount}, {@code refreshLagMs}) so
 * jseval's timeline can observe the Lucene runtime's internal state without
 * needing the OpenTelemetry metrics stream.
 *
 * <p>Tempdoc 419 C3 V1 (2026-04-26) extended with {@code recentJobQueueDepth}:
 * a 30-min trend of {@code worker.job_queue.depth} (curated RRD metric) so
 * frontend HealthView can render a sparkline next to the existing
 * {@code index-throughput-stalled} / {@code index-throughput-degraded}
 * events. Empty array when the RRD store hasn't accumulated data yet.
 *
 * <p>Tempdoc 419 C3 V2 P2 (2026-04-28) added {@code recentDocsPerSec}: a 30-min
 * trend of {@code worker.documents.indexed.rate_per_sec} (curated RRD metric).
 * Complementary to {@code recentJobQueueDepth} — depth answers "is the backlog
 * draining," rate answers "is the indexer making progress." Both render when a
 * throughput-stalled / throughput-degraded event is firing.
 *
 * <p>Tempdoc 813 Slice B (2026-08-06) added {@code pendingBytes} +
 * {@code pendingUnknownSizeJobs}: the byte weight of remaining
 * (PENDING+PROCESSING) indexing work, summed from the size recorded at
 * enqueue time, so remaining-work estimates can be byte-weighted instead of
 * file-counted. {@code pendingUnknownSizeJobs} counts the remaining jobs that
 * carry no recorded size — a nonzero value means {@code pendingBytes}
 * understates the real backlog, and a consumer must not present it as a
 * complete denominator. {@code pendingUnknownSizeJobs == -1} is the distinct
 * "the queue could not compute the aggregate at all" marker
 * ({@code JobQueue.PendingBytes.UNAVAILABLE}); {@code pendingBytes} is 0 there,
 * so a consumer gated on {@code pendingBytes > 0} renders nothing either way.
 *
 * <p>Tempdoc 811 C-4 (2026-08-06) added {@code searchableDocuments}: the population a DEFAULT-scope
 * search can actually return — {@code indexedDocuments} minus the collections the default search
 * scope excludes (today exactly {@code agent-history}). {@code indexedDocuments} deliberately keeps
 * its meaning (the whole non-chunk index) because Health, jseval and sandbox evidence describe the
 * index itself; a user-facing "Searching N documents" string must read {@code searchableDocuments}
 * instead, which is the only one of the two the user could enumerate.
 */
@SuppressWarnings("ArrayRecordComponent") // 419 C3 V1/V2: intentional for API time-series payload
public record CoreIndexView(
    boolean indexHealthy,
    long indexedDocuments,
    long pendingJobs,
    String indexState,
    long indexSizeBytes,
    int pendingVduCount,
    long writerQueueDepth,
    long writerPendingDocs,
    long commitCount,
    long refreshLagMs,
    long[] recentJobQueueDepth,
    double[] recentDocsPerSec,
    long pendingBytes,
    long pendingUnknownSizeJobs,
    long searchableDocuments) {
  public CoreIndexView {
    indexState = indexState == null ? "" : indexState;
    recentJobQueueDepth = recentJobQueueDepth == null ? new long[0] : recentJobQueueDepth;
    recentDocsPerSec = recentDocsPerSec == null ? new double[0] : recentDocsPerSec;
  }

  /**
   * Backward-compatible 6-arg ctor; defaults the 406 runtime gauges + V1/V2 trends to empty.
   *
   * <p>Every short ctor defaults {@code searchableDocuments} to {@code indexedDocuments} — the
   * "nothing is excluded from the default scope" case. Zero would be the wrong default: it would
   * claim the caller's documents are unsearchable rather than that no exclusion was expressed.
   */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        0L, 0L, 0L, 0L, new long[0], new double[0], 0L, 0L, indexedDocuments);
  }

  /** Backward-compatible 10-arg ctor; defaults the 419 V1/V2 trends to empty. */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs,
        new long[0], new double[0], 0L, 0L, indexedDocuments);
  }

  /** Backward-compatible 11-arg ctor; defaults the 419 V2 P2 docs/sec trend to empty. */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long[] recentJobQueueDepth) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs,
        recentJobQueueDepth, new double[0], 0L, 0L, indexedDocuments);
  }

  /** Backward-compatible 12-arg ctor; defaults the 813 Slice B pending-byte weights to zero. */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long[] recentJobQueueDepth,
      double[] recentDocsPerSec) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs,
        recentJobQueueDepth, recentDocsPerSec, 0L, 0L, indexedDocuments);
  }

  public static CoreIndexView fallback(String indexState) {
    return new CoreIndexView(
        false, 0, 0, indexState, 0, 0, 0L, 0L, 0L, 0L, new long[0], new double[0], 0L, 0L, 0L);
  }
}
