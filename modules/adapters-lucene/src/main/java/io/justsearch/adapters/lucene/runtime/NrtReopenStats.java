/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.SearcherManager;

/**
 * Per-session reopen bookkeeping — the cadence measurement's counters, and the freshness signal the
 * reopen-on-demand seam gates on (tempdoc 885 item 19).
 *
 * <p>Every value here is stamped by ONE {@link ReferenceManager.RefreshListener} installed on the
 * {@link SearcherManager}, so it covers every reopen path uniformly: the background
 * {@code ControlledRealTimeReopenThread}, {@code CommitOps.maybeRefresh*}, and the on-demand
 * refresh {@link SearcherBridge} performs before a foreground acquire.
 *
 * <p><b>Why the sequence number and not a doc counter.</b> "Is there anything new to see?" must
 * account for documents still sitting in the writer's RAM buffer (an NRT reopen sees those, a
 * commit-based or segment-based signal does not) and for deletions. {@link
 * IndexWriter#getMaxCompletedSequenceNumber()} is the only public, exact, O(1) answer: it advances
 * on every completed add/update/delete and on nothing else.
 */
final class NrtReopenStats {

  /** Monotonic count of reopens that actually swapped in a new reader. */
  final AtomicLong reopenTotal = new AtomicLong(0L);

  /**
   * Writer sequence number the current searcher is known to cover. {@code -1} until the first
   * reopen. Written by the refresh listener and by the on-demand seam; both use a max-accumulate so
   * a late writer cannot move it backwards.
   */
  final AtomicLong seqNoAtLastReopen = new AtomicLong(-1L);

  /**
   * {@link IndexWriter#getSegmentInfosCounter()} at the last reopen. The counter is monotonic in
   * segments created (it names them), so {@code current - this} is the number of new segments the
   * next reopen will have to open — the quantity the "first search after N new segments" column
   * measures.
   */
  final AtomicLong segmentCounterAtLastReopen = new AtomicLong(0L);

  /** Samples taken in {@code beforeRefresh}, promoted in {@code afterRefresh(true)}. */
  private volatile long pendingSeqNo = -1L;

  private volatile long pendingSegmentCounter = 0L;

  /** New segments the writer has created since the last reopen; 0 when read-only or unopened. */
  long segmentsSinceReopen(IndexWriter writer) {
    if (writer == null) return 0L;
    long delta = writer.getSegmentInfosCounter() - segmentCounterAtLastReopen.get();
    return Math.max(0L, delta);
  }

  /** Records that the searcher now covers {@code seqNo}. Never moves the watermark backwards. */
  void recordCovered(long seqNo) {
    seqNoAtLastReopen.accumulateAndGet(seqNo, Math::max);
  }

  /**
   * Installs the single refresh listener. {@code writer} is null on the read-only path, where there
   * is no writer to sample and the reopen count is the only meaningful figure.
   */
  void install(SearcherManager mgr, AtomicLong lastRefreshNanos, IndexWriter writer) {
    if (writer != null) {
      // The manager was just built over a reader opened from this writer, so it already covers
      // everything written so far. Seeding both baselines here (rather than leaving the -1
      // sentinel) also keeps a mid-session writer swap honest: the new writer's sequence numbers
      // restart, and a max-accumulated watermark carried over from the old one would never match
      // again — every query would refresh forever, silently cancelling the on-demand mode.
      seqNoAtLastReopen.set(writer.getMaxCompletedSequenceNumber());
      segmentCounterAtLastReopen.set(writer.getSegmentInfosCounter());
    } else {
      seqNoAtLastReopen.set(-1L);
      segmentCounterAtLastReopen.set(0L);
    }
    mgr.addListener(
        new ReferenceManager.RefreshListener() {
          @Override
          public void beforeRefresh() {
            if (writer == null) return;
            // Sampled BEFORE the reopen: a write that lands during it is not claimed as covered,
            // so the worst case is one redundant refresh, never a missed document.
            pendingSeqNo = writer.getMaxCompletedSequenceNumber();
            pendingSegmentCounter = writer.getSegmentInfosCounter();
          }

          @Override
          public void afterRefresh(boolean didRefresh) {
            if (!didRefresh) return;
            lastRefreshNanos.set(System.nanoTime());
            reopenTotal.incrementAndGet();
            if (writer == null) return;
            recordCovered(pendingSeqNo);
            segmentCounterAtLastReopen.set(pendingSegmentCounter);
          }
        });
  }
}
