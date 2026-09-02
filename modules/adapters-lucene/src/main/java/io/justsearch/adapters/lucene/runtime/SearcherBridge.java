/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared SearcherManager acquire/release pair for ops collaborators.
 *
 * <p>Replaces 4 identical acquire/release lambda blocks in {@code applyComponents()} and 4
 * anonymous {@link TextQueryOps.WithSearcher} implementations. Thread-safe via volatile
 * {@link RuntimeContext#snapshot} read on each call.
 *
 * <p>Lifecycle: created per {@code applyComponents()} cycle. Stale bridges are safe because they
 * read the volatile snapshot on each call.
 */
final class SearcherBridge {
  private static final Logger log = LoggerFactory.getLogger(SearcherBridge.class);

  private final RuntimeSession session;

  SearcherBridge(RuntimeSession session) {
    this.session = session;
  }

  /** Acquires an IndexSearcher from the current SearcherManager. Caller must call release. */
  IndexSearcher acquire() throws IOException {
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      throw new IllegalStateException("SearcherManager not available (runtime closed?)");
    }
    refreshOnDemand(snap, mgr);
    return mgr.acquire();
  }

  /**
   * The reopen-on-demand seam (tempdoc 885 item 19). ONE place, not one per RPC: every foreground
   * read — Search, Rerank, RetrieveContext, FetchDocuments, Suggest, facets, folder browse, counts
   * — reaches Lucene through {@link #acquire()} or {@link #withSearcher}, so putting the refresh
   * here is what makes the mode a property of the index runtime rather than of whichever service
   * happened to be updated.
   *
   * <p>No-op unless {@code index.nrt.mode=on_demand}, which keeps the default arm bit-identical.
   * The write path opts out via {@link #withSearcherNoRefresh}: a read-modify-write batch is
   * indexing work, and refreshing per batch is precisely the reopen cost the candidate removes.
   */
  private void refreshOnDemand(LifecycleSnapshot snap, SearcherManager mgr) {
    if (session.nrtMode != NrtMode.ON_DEMAND) return;
    IndexWriter writer = snap.writer();
    if (writer == null) return; // read-only runtime: no writer, nothing to reopen against
    long writerSeqNo = writer.getMaxCompletedSequenceNumber();
    long lastRefresh = session.lastRefreshNanos.get();
    long staleMs =
        lastRefresh == 0L
            ? Long.MAX_VALUE
            : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastRefresh);
    NrtOnDemandPolicy.Action action =
        NrtOnDemandPolicy.decide(
            session.nrtMode,
            writerSeqNo,
            session.nrtStats.seqNoAtLastReopen.get(),
            staleMs,
            session.nrtOnDemandMaxStaleMs);
    if (action == NrtOnDemandPolicy.Action.SKIP) return;
    try {
      if (action == NrtOnDemandPolicy.Action.REFRESH_BLOCKING) {
        mgr.maybeRefreshBlocking();
      } else {
        mgr.maybeRefresh();
      }
      // The call returned without throwing, so the searcher now covers writerSeqNo — either it was
      // reopened, or another thread's concurrent reopen already covered it. Recording it here (and
      // not only in the refresh listener) is what stops an idle index from re-entering this branch
      // on every query.
      session.nrtStats.recordCovered(writerSeqNo);
    } catch (IOException | RuntimeException e) {
      // Best-effort: a failed refresh must not fail the query. The watermark is deliberately NOT
      // advanced, so the next search retries.
      log.warn("On-demand NRT refresh failed; serving the previous searcher: {}", e.getMessage());
    }
  }

  /**
   * Releases an IndexSearcher. If the snapshot has been nulled by a concurrent close(), the searcher
   * is leaked (logged as a warning). Callers that need guaranteed release should use {@link
   * #withSearcher} instead, which captures the SearcherManager once for both acquire and release.
   */
  void release(IndexSearcher searcher) {
    if (searcher == null) return;
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      log.warn("SearcherManager null during searcher release (runtime closed?)");
      return;
    }
    try {
      mgr.release(searcher);
    } catch (IOException e) {
      log.warn("Failed to release searcher", e);
    }
  }

  /**
   * Executes an operation with an acquired IndexSearcher, ensuring proper release.
   *
   * <p>Replaces the repeated anonymous {@link TextQueryOps.WithSearcher} pattern.
   */
  <T> T withSearcher(ReadPathOps.SearcherOperation<T> op) throws IOException {
    return withSearcher(op, true);
  }

  /**
   * Same as {@link #withSearcher}, but never triggers the on-demand NRT refresh. For write-path
   * reads (read-modify-write, path rewrites): those run inside indexing, and the whole point of
   * {@code index.nrt.mode=on_demand} is that indexing does not pay for reopens.
   */
  <T> T withSearcherNoRefresh(ReadPathOps.SearcherOperation<T> op) throws IOException {
    return withSearcher(op, false);
  }

  private <T> T withSearcher(ReadPathOps.SearcherOperation<T> op, boolean refreshOnDemand)
      throws IOException {
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      throw new IllegalStateException("SearcherManager not available (runtime closed?)");
    }
    if (refreshOnDemand) refreshOnDemand(snap, mgr);
    IndexSearcher searcher = mgr.acquire();
    try {
      return op.execute(searcher);
    } finally {
      try {
        mgr.release(searcher);
      } catch (IOException e) {
        log.warn("Failed to release searcher after operation", e);
      }
    }
  }
}
