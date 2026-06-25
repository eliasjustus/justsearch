/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.asString;
import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.classifyIOException;

import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Lucene commit mechanics and metadata stamping.
 *
 * <p>Extracted from {@link LuceneLifecycleManager} to encapsulate commit-specific Lucene interactions.
 * The facade handles runtime state tracking (counters, telemetry, NRT refresh) after delegating the
 * commit to this class.
 */
public final class CommitOps {

  private static final Logger log = LoggerFactory.getLogger(CommitOps.class);
  private static final long COMMIT_TIMER_INTERVAL_MS = 10_000L;

  private final RuntimeSession session;
  private volatile ScheduledExecutorService commitTimer;
  private volatile ScheduledFuture<?> commitTimerFuture;

  /**
   * Single-slot listener fired after every successful {@link #commitAndTrack(CommitReason)} call.
   *
   * <p>Tempdoc 516 Slice 3 substrate (Appendix A.6 constraint #12). Today the only consumer is
   * {@code EmbeddingCompatibilityController.refreshStoredFingerprintAfterCommit}, which previously
   * had to be called manually from each of the three idle/time-buffer/shutdown commit sites in
   * {@code IndexingLoop}. The single listener replaces the three scattered calls with one
   * subscription. Single-slot (not a list) on purpose — there is exactly one consumer; if a
   * second arrives, expand to a multi-listener pattern then (and only then) per the P5
   * boundary: no plug-in registries until a real second consumer needs one.
   */
  private volatile CommitCompletedListener commitCompletedListener;

  /** Functional callback for {@link #setCommitCompletedListener(CommitCompletedListener)}. */
  @FunctionalInterface
  public interface CommitCompletedListener {
    void onCommitCompleted(CommitReason reason);
  }

  /**
   * Build state for the next commit. Single producer ({@link #commitWithBuildState}),
   * single consumer (this class — read by {@link #commit()} and the scheduled timer).
   * Tempdoc 406 Gap B: location enforces scope. The field deliberately lives here, not
   * on RuntimeContext, so other ops cannot mutate it.
   */
  private volatile LuceneRuntimeTypes.BuildState currentBuildState;

  CommitOps(RuntimeSession session, LuceneRuntimeTypes.BuildState initialBuildState) {
    this.session = session;
    this.currentBuildState =
        initialBuildState != null ? initialBuildState : LuceneRuntimeTypes.BuildState.COMPLETE;
  }

  /**
   * Commits pending changes with optional metadata stamping.
   *
   * @return elapsed time in milliseconds for the Lucene commit operation
   */
  public long commit() {
    boolean metaEnabled = session.commitMetadataEnabled;
    Map<String, String> ud;
    if (metaEnabled) {
      Map<String, Object> meta = new HashMap<>(buildMetadataSnapshot());
      meta.put("build_state", currentBuildState.name());
      meta.putIfAbsent("commit_id", UUID.randomUUID().toString());
      meta.putIfAbsent("commit_time", Instant.now().toString());
      session.metadataValidator.validate(meta);
      ud = new HashMap<>();
      for (Map.Entry<String, Object> e : meta.entrySet()) {
        ud.put(e.getKey(), asString(e.getValue()));
      }
    } else {
      ud = null;
    }

    // Synchronize only the Lucene interaction: setLiveCommitData must be atomic with commit()
    // to prevent a concurrent caller's metadata from overwriting ours before our commit executes.
    synchronized (this) {
      try {
        LifecycleSnapshot snap = session.snapshot;
        IndexWriter w = snap != null ? snap.writer() : null;
        if (w == null) throw new IllegalStateException("IndexWriter not available");
        w.setLiveCommitData(ud != null ? ud.entrySet() : Collections.emptyList());
        long start = System.nanoTime();
        w.commit();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      } catch (IOException e) {
        throw new IndexRuntimeIOException(classifyIOException(e), "Commit failed", e);
      }
    }
  }

  public Map<String, Object> buildMetadataSnapshot() {
    CommitMetadataSource source = session.metadataSourceSupplier.get();
    if (source == null) {
      throw new IllegalStateException("metadataSourceSupplier returned null CommitMetadataSource");
    }
    Map<String, Object> meta = source.build();
    if (meta == null) {
      throw new IllegalStateException("CommitMetadataSource.build() returned null metadata map");
    }
    return Map.copyOf(meta);
  }

  /**
   * Commits pending changes and tracks timing, counters, and telemetry.
   * This is the full commit operation — the existing commit() method is the low-level Lucene commit.
   */
  public void commitAndTrack() {
    commitAndTrack(CommitReason.UNKNOWN);
  }

  /**
   * Updates the runtime's build state and commits. Subsequent commits (including
   * scheduled timer commits) stamp the new build state. Replaces the deprecated
   * {@code setBuildState + commit} two-step pattern from
   * {@code LuceneLifecycleManager}.
   */
  public void commitWithBuildState(LuceneRuntimeTypes.BuildState state) {
    java.util.Objects.requireNonNull(state, "state");
    this.currentBuildState = state;
    commitAndTrack();
  }

  /**
   * Commits pending changes and tracks timing, counters, and telemetry with caller attribution.
   *
   * @param reason caller attribution for telemetry; pass {@link CommitReason#UNKNOWN} for
   *     unattributed commits.
   */
  public void commitAndTrack(CommitReason reason) {
    // No separate writer null-check here — commit() performs its own atomic snapshot read
    // and throws ISE if the writer is unavailable. A separate check would be a redundant
    // volatile read that could see a different snapshot than commit() sees.
    long elapsedMs = commit();
    session.lastCommitNanos.set(System.nanoTime());
    session.commitCount.incrementAndGet();
    session.pendingDocs.set(0L);
    CommitReason effectiveReason = reason == null ? CommitReason.UNKNOWN : reason;
    LuceneRuntimeTypes.TelemetryEvents events = session.telemetryEvents;
    if (events != null) {
      events.onCommit(elapsedMs, effectiveReason);
    }
    // Tempdoc 516 Slice 3: single-shot post-commit notification. Failures are best-effort —
    // a misbehaving listener cannot fail a commit that has already succeeded.
    CommitCompletedListener listener = commitCompletedListener;
    if (listener != null) {
      try {
        listener.onCommitCompleted(effectiveReason);
      } catch (RuntimeException e) {
        log.warn("CommitCompletedListener threw — commit succeeded; listener error: {}", e.getMessage());
      }
    }
  }

  /**
   * Registers (or replaces) the single post-commit listener. Tempdoc 516 Slice 3.
   *
   * <p>Pass {@code null} to clear. Single-slot by design — see field Javadoc.
   */
  public void setCommitCompletedListener(CommitCompletedListener listener) {
    this.commitCompletedListener = listener;
  }

  public void maybeRefresh() {
    long lag = refreshLagMs();
    if (lag <= session.nrtTargetMaxStaleMs && lag <= session.nrtHardMaxStaleMs) return;
    try {
      LifecycleSnapshot snap = session.snapshot;
      SearcherManager mgr = snap != null ? snap.searcherManager() : null;
      if (mgr != null) mgr.maybeRefresh();
    } catch (IOException e) {
      // best-effort
    }
  }

  public void maybeRefresh(long targetMaxStaleMs) {
    session.lastRefreshTargetMs.set(targetMaxStaleMs);
    if (targetMaxStaleMs <= 0) {
      maybeRefresh();
      return;
    }
    if (refreshLagMs() > targetMaxStaleMs) maybeRefresh();
  }

  public long refreshLagMs() {
    long commitNanos = session.lastCommitNanos.get();
    if (commitNanos == 0L) return 0L;
    long refreshedNanos = session.lastRefreshNanos.get();
    long nanosDiff = commitNanos - refreshedNanos;
    if (nanosDiff <= 0) return 0L;
    return TimeUnit.NANOSECONDS.toMillis(nanosDiff);
  }

  public void maybeRefreshBlocking() {
    LifecycleSnapshot snap = session.snapshot;
    SearcherManager refreshMgr = snap != null ? snap.searcherManager() : null;
    if (refreshMgr != null) {
      try {
        refreshMgr.maybeRefreshBlocking();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to refresh searcher", e);
      }
    }
  }

  // ==========================================================================
  // NRT Refresh Suspension (334 Phase 8: bulk backfill optimization)
  // ==========================================================================

  /**
   * Suspends the background NRT refresh thread. During bulk enrichment, the CRTRT creates
   * mmap'd reader snapshots every 50-500ms that hold references to uncommitted segments,
   * causing unbounded native memory growth. Suspending it allows deferred commits without
   * mmap accumulation. Call {@link #resumeNrtRefresh()} when bulk work is done.
   *
   * <p>While suspended, callers must manually trigger refresh after commits via
   * {@link #maybeRefreshBlocking()} to ensure subsequent queries see committed data.
   */
  public void suspendNrtRefresh() {
    var thread = session.crtrt;
    if (thread != null) {
      thread.close(); // Signals finish, joins the thread. One-shot — can't restart.
      session.crtrt = null;
      log.info("NRT refresh thread suspended for bulk backfill");
    }
  }

  /**
   * Runs {@code work} with NRT refresh suspended, restoring it on exit (even on throw).
   *
   * <p>Tempdoc 516 Slice 3 substrate (Appendix A.6 constraint #17). Replaces the explicit
   * {@code suspendNrtRefresh + try/finally + resumeNrtRefresh} idiom that the
   * {@code IndexingLoop} tight-loop used inline. Future callers (notably the
   * {@code BackfillScheduler} extracted in Slice 4d) hold the suspension via a scoped
   * Runnable rather than threading the suspend/resume calls through their own control flow.
   */
  public void withNrtSuspended(Runnable work) {
    java.util.Objects.requireNonNull(work, "work");
    suspendNrtRefresh();
    try {
      work.run();
    } finally {
      resumeNrtRefresh();
    }
  }

  /**
   * Resumes the background NRT refresh thread after bulk backfill completes. Creates a new
   * CRTRT instance with the same parameters as the original (50-500ms refresh interval).
   */
  public void resumeNrtRefresh() {
    LifecycleSnapshot snap = session.snapshot;
    if (snap == null || snap.writer() == null || snap.searcherManager() == null) return;
    if (session.crtrt != null) return; // Already running

    var thread = new org.apache.lucene.search.ControlledRealTimeReopenThread<>(
        snap.writer(), snap.searcherManager(),
        session.nrtTargetMaxStaleMs / 1000.0,
        session.nrtHardMaxStaleMs / 1000.0);
    thread.setName("crtrt");
    thread.setDaemon(true);
    thread.start();
    session.crtrt = thread;
    log.info("NRT refresh thread resumed after bulk backfill");
  }

  // ==========================================================================
  // Commit Timer (DC7: universal safety net for all write paths)
  // ==========================================================================

  /**
   * Starts a periodic commit timer that commits when {@code pendingDocs > 0} and no explicit commit
   * has occurred recently. This is the universal safety net that catches writes from any code path
   * (GrpcIngestService, backfill ops, etc.) even when IndexingLoop is idle.
   *
   * <p>Call after the runtime is fully started and the writer is available. Only call in read-write
   * mode — read-only runtimes have no writer and no pending writes.
   */
  public void startCommitTimer() {
    if (commitTimer != null) return;
    ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "commit-timer");
              t.setDaemon(true);
              return t;
            });
    this.commitTimer = executor;
    this.commitTimerFuture =
        executor.scheduleAtFixedRate(
            this::timerTick, COMMIT_TIMER_INTERVAL_MS, COMMIT_TIMER_INTERVAL_MS, TimeUnit.MILLISECONDS);
    log.debug("Commit timer started (interval={}ms)", COMMIT_TIMER_INTERVAL_MS);
  }

  /**
   * Stops the commit timer. Safe to call multiple times or if the timer was never started.
   */
  public void stopCommitTimer() {
    ScheduledFuture<?> future = this.commitTimerFuture;
    if (future != null) {
      future.cancel(false);
      this.commitTimerFuture = null;
    }
    ScheduledExecutorService executor = this.commitTimer;
    if (executor != null) {
      executor.shutdown();
      this.commitTimer = null;
      log.debug("Commit timer stopped");
    }
  }

  private void timerTick() {
    try {
      LifecycleSnapshot timerSnap = session.snapshot;
      if (session.pendingDocs.get() > 0 && timerSnap != null && timerSnap.writer() != null) {
        commitAndTrack(CommitReason.TIMER);
        log.debug("Commit timer fired (pendingDocs was > 0)");
      }
    } catch (Exception e) {
      log.warn("Commit timer tick failed: {}", e.getMessage());
    }
  }

  public long softDeletesCurrent() {
    LifecycleSnapshot snap = session.snapshot;
    IndexWriter currentWriter = snap != null ? snap.writer() : null;
    if (currentWriter == null) {
      return 0L;
    }
    try (DirectoryReader reader = DirectoryReader.open(currentWriter)) {
      return reader.numDeletedDocs();
    } catch (IOException e) {
      return 0L;
    }
  }
}
