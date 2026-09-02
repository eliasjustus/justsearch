/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

/**
 * The reopen-on-demand decision, as a pure function (tempdoc 885 item 19).
 *
 * <p>Extracted from {@link SearcherBridge} so the ladder can be asserted directly instead of
 * inferred from a live index's timing: the seam that calls it is a two-line adapter, and this is
 * the part with the branches.
 */
final class NrtOnDemandPolicy {

  /** What a foreground acquire should do to the {@code SearcherManager} before acquiring. */
  enum Action {
    /** Nothing has been written since the last reopen — acquire the current searcher as is. */
    SKIP,
    /** Non-blocking best effort: refresh if no other thread is already refreshing. */
    REFRESH,
    /** The view is older than the configured bound — refresh even if that means waiting. */
    REFRESH_BLOCKING
  }

  private NrtOnDemandPolicy() {}

  /**
   * @param mode resolved {@code index.nrt.mode}
   * @param foregroundActive whether a user-facing RPC is in flight. Background enrichment reads
   *     reach Lucene through the same {@code SearcherBridge} as searches do, so mode alone cannot
   *     tell them apart; without this gate every backfill document fetch reopened the searcher
   *     (tempdoc 885 live window: reopen count 193 -> 568, indexing throughput -15%).
   * @param writerSeqNo {@code IndexWriter.getMaxCompletedSequenceNumber()} now
   * @param seqNoAtLastReopen the sequence number the current searcher is known to cover, or -1 if
   *     it has never been reopened
   * @param staleMs age of the last successful reopen; pass {@link Long#MAX_VALUE} when there has
   *     never been one
   * @param onDemandMaxStaleMs {@code index.nrt.on_demand_max_stale_ms}
   */
  static Action decide(
      NrtMode mode,
      boolean foregroundActive,
      long writerSeqNo,
      long seqNoAtLastReopen,
      long staleMs,
      long onDemandMaxStaleMs) {
    if (mode != NrtMode.ON_DEMAND) return Action.SKIP;
    if (!foregroundActive) return Action.SKIP;
    if (writerSeqNo == seqNoAtLastReopen) return Action.SKIP;
    return staleMs > onDemandMaxStaleMs ? Action.REFRESH_BLOCKING : Action.REFRESH;
  }
}
