/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

/**
 * Per-batch counters and summary timing for primary indexing.
 *
 * <p>Tempdoc 516 Slice 3 substrate (Appendix A.1). Encapsulates the four batch-summary fields
 * (indexed/skipped/failed/start time) the {@code IndexingLoop} previously kept as separate
 * {@code long} fields written from both the extract path and the write path. Owned by the
 * {@code runLoop} residue and passed into the upcoming Slice 4a Extractor + Writer so the
 * cross-seam counter invariant ({@code indexed + skipped + failed == jobs.size()}) is
 * enforced through a typed API rather than through scattered field mutations.
 *
 * <p>Not thread-safe — the indexing loop is single-threaded and these counters live on the
 * loop thread alone (the field type is intentionally not {@code volatile} or {@code AtomicLong};
 * if a future caller crosses threads, wrap in synchronization there, not here).
 *
 * <p>P5 note: a concrete class with a closed set of named methods, not a metric framework.
 */
public final class BatchStats {

  private long indexed;
  private long skipped;
  private long failed;
  private long startTimeMillis;

  public void start(long nowMillis) {
    this.startTimeMillis = nowMillis;
  }

  public void recordIndexed(long count) {
    this.indexed += count;
  }

  public void recordSkipped() {
    this.skipped++;
  }

  public void recordFailed() {
    this.failed++;
  }

  public void reset() {
    this.indexed = 0L;
    this.skipped = 0L;
    this.failed = 0L;
    this.startTimeMillis = 0L;
  }

  public long indexed() {
    return indexed;
  }

  public long skipped() {
    return skipped;
  }

  public long failed() {
    return failed;
  }

  public long startTimeMillis() {
    return startTimeMillis;
  }

  public long total() {
    return indexed + skipped + failed;
  }

  public boolean hasWork() {
    return total() > 0;
  }

  public long elapsedMillis(long nowMillis) {
    return startTimeMillis == 0L ? 0L : nowMillis - startTimeMillis;
  }
}
