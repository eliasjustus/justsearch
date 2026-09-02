/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single construction site for the NRT {@link ControlledRealTimeReopenThread}.
 *
 * <p>Tempdoc 885 item 19: the initial open ({@link ComponentsFactory}) hardcoded 0.5s/0.05s while
 * the post-bulk-backfill rebuild ({@link CommitOps#resumeNrtRefresh()}) read the configured
 * {@code index.nrt.*} values, so a configured cadence silently took effect only after the first
 * backfill. Both sites now go through {@link #create}, which is the only place the ms-to-seconds
 * conversion and the Lucene argument order exist.
 *
 * <p>Lucene's constructor throws when {@code targetMaxStaleSec < targetMinStaleSec}. The two
 * JustSearch knobs do not enforce that order — {@code index.nrt.max_stale_ms} reads as the larger
 * bound but is passed as Lucene's <em>min</em> stale — so {@code hardMaxStaleMs} is clamped to the
 * target rather than propagating an invalid pair into a construction failure.
 */
final class NrtReopenThreads {

  private static final Logger log = LoggerFactory.getLogger(NrtReopenThreads.class);

  private NrtReopenThreads() {}

  /**
   * Builds a named daemon reopen thread from the configured staleness bounds. The caller starts it.
   *
   * @param writer the index writer the thread reopens against
   * @param manager the searcher manager the thread refreshes
   * @param targetMaxStaleMs background reopen target; Lucene's third constructor argument
   * @param hardMaxStaleMs reopen target while a caller waits on a generation; Lucene's fourth
   *     constructor argument, clamped to {@code targetMaxStaleMs}
   */
  static ControlledRealTimeReopenThread<IndexSearcher> create(
      IndexWriter writer, SearcherManager manager, long targetMaxStaleMs, long hardMaxStaleMs) {
    long tighterMs = Math.min(hardMaxStaleMs, targetMaxStaleMs);
    if (tighterMs != hardMaxStaleMs) {
      log.warn(
          "index.nrt.max_stale_ms ({}) exceeds index.nrt.target_max_stale_ms ({}); clamping the "
              + "waiting-reopen target to {}ms",
          hardMaxStaleMs,
          targetMaxStaleMs,
          tighterMs);
    }
    ControlledRealTimeReopenThread<IndexSearcher> thread =
        new ControlledRealTimeReopenThread<>(
            writer, manager, targetMaxStaleMs / 1000.0, tighterMs / 1000.0);
    thread.setName("crtrt");
    thread.setDaemon(true);
    return thread;
  }
}
