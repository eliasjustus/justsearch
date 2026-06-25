/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
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
    return mgr.acquire();
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
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager mgr = snap != null ? snap.searcherManager() : null;
    if (mgr == null) {
      throw new IllegalStateException("SearcherManager not available (runtime closed?)");
    }
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
