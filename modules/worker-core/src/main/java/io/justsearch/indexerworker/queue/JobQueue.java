/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import io.justsearch.indexerworker.ingest.IngestionOutcome;

/**
 * Interface for job queue operations supporting file ingestion.
 *
 * <p>The job queue persists pending file paths for indexing, supporting:
 * <ul>
 *   <li>Durable persistence across worker restarts</li>
 *   <li>Retry logic for failed jobs</li>
 *   <li>Job state tracking (PENDING, PROCESSING, DONE, FAILED)</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link SqliteJobQueue} - SQLite-backed durable queue for production</li>
 *   <li>InMemoryJobQueue - In-memory queue for hermetic testing (in testFixtures)</li>
 * </ul>
 */
public interface JobQueue extends Closeable {

  /**
   * A pending job with its associated path and optional collection tag.
   *
   * @param path the file path to index
   * @param collection collection tag for the indexed document, or null for default
   */
  record IndexJob(Path path, String collection) {}

  /**
   * Summary of recent failures in the queue.
   *
   * <p>Used for surfacing "indexing attempted but failed" signals in status endpoints without
   * requiring log inspection.
   *
   * @param failedCount number of jobs in FAILED state
   * @param lastFailedPath most recently failed job path (normalized), or null if none
   * @param lastFailedErrorMessage last error message for the most recently failed job, or null
   * @param lastFailedAtMs epoch millis of the most recent FAILED transition, or null if unknown
   * @param nextRetryAtMs earliest retry time (epoch millis) among pending jobs with backoff, or null if none
   */
  record FailureSummary(
      long failedCount,
      String lastFailedPath,
      String lastFailedErrorMessage,
      Long lastFailedAtMs,
      Long nextRetryAtMs) {}

  /**
   * Opens the job queue and initializes any required resources.
   *
   * @throws SQLException if database initialization fails
   * @throws IOException if file operations fail
   */
  void open() throws SQLException, IOException;

  /**
   * Opens the queue and forces an integrity check regardless of creation state.
   * Use after restoring from a backup to validate the restored database.
   * Default implementation delegates to {@link #open()}.
   */
  default void openWithIntegrityCheck() throws SQLException, IOException {
    open();
  }

  /**
   * Enqueues file paths for indexing with no collection tag.
   *
   * @param paths List of file paths to index
   * @return Number of jobs accepted
   */
  default int enqueue(List<Path> paths) {
    return enqueue(paths, null);
  }

  /**
   * Enqueues file paths for indexing with an optional collection tag.
   *
   * @param paths List of file paths to index
   * @param collection collection tag for the indexed documents, or null for default
   * @return Number of jobs accepted
   */
  int enqueue(List<Path> paths, String collection);

  /**
   * Polls for pending jobs and marks them as PROCESSING.
   *
   * @param limit Maximum number of jobs to return
   * @return List of pending index jobs
   */
  List<IndexJob> pollPending(int limit);

  /**
   * Marks a job as successfully completed.
   *
   * @param path The file path that was indexed
   */
  void markDone(Path path);

  /**
   * Records the latest typed ingestion outcome for a path without changing queue state.
   *
   * <p>Implementations that do not persist outcome metadata may ignore this.
   */
  default void recordOutcome(Path path, IngestionOutcome outcome) {}

  /** Marks a job done while recording its latest typed ingestion outcome. */
  default void markDone(Path path, IngestionOutcome outcome) {
    markDone(path, outcome, null);
  }

  /** Marks a job done while recording its latest typed ingestion outcome and ledger context. */
  default void markDone(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
    recordOutcome(path, outcome);
    markDone(path);
  }

  /**
   * Marks multiple jobs as successfully completed in a single batch operation.
   *
   * <p>Default implementation delegates to {@link #markDone(Path)} per path.
   * Implementations should override for efficiency (e.g., single SQL statement).
   *
   * @param paths The file paths that were indexed
   */
  default void markDoneBatch(java.util.Collection<Path> paths) {
    for (Path p : paths) {
      markDone(p);
    }
  }

  /** Marks multiple jobs done with a shared typed outcome. */
  default void markDoneBatch(java.util.Collection<Path> paths, IngestionOutcome outcome) {
    for (Path p : paths) {
      markDone(p, outcome);
    }
  }

  /** Marks multiple jobs done with typed outcomes and per-path ledger context. */
  default void markDoneTransitions(
      java.util.Collection<IngestionLedgerTransition> transitions, IngestionOutcome outcome) {
    if (transitions == null) return;
    for (IngestionLedgerTransition transition : transitions) {
      if (transition != null) {
        markDone(transition.path(), outcome, transition.entry());
      }
    }
  }

  /**
   * Marks a job as failed.
   * If attempts &lt; maxAttempts, the job may be returned to PENDING for retry.
   *
   * @param path The file path that failed
   * @param errorMessage Optional error message
   */
  void markFailed(Path path, String errorMessage);

  /**
   * Marks a job failed with a typed ingestion outcome.
   *
   * <p>Whether the failure is terminal (FAILED state, no further retries) or retryable
   * (returned to PENDING with backoff) is derived from {@code outcome.retryPolicy()}:
   * {@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#NONE} is terminal,
   * everything else is retryable. Retryable failures still respect the underlying
   * attempts-vs-maxAttempts cap and become FAILED once the cap is hit.
   */
  default void markFailed(Path path, IngestionOutcome outcome) {
    markFailed(path, outcome, null);
  }

  /**
   * Marks a job failed with a typed outcome and ledger context. Terminal vs. retryable is
   * derived from {@code outcome.retryPolicy()}.
   */
  default void markFailed(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
    throw new UnsupportedOperationException(
        "Typed failure requires an outcome-aware queue implementation");
  }

  /** Requeues/defer a job without incrementing attempts while recording its typed outcome. */
  default void defer(Path path, IngestionOutcome outcome) {
    defer(path, outcome, null);
  }

  /** Requeues/defer a job without incrementing attempts while recording context. */
  default void defer(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
    throw new UnsupportedOperationException(
        "Typed defer requires an outcome-aware queue implementation");
  }

  /**
   * Recovers any jobs that were stuck in PROCESSING state (e.g., after a crash).
   *
   * @return Number of jobs recovered
   */
  int recoverStuckJobs();

  /**
   * Recovers only jobs stuck in PROCESSING whose {@code last_updated} is older than
   * {@code olderThanMs} — the periodic liveness reaper (tempdoc 550 Thesis II). Unlike the
   * unconditional {@link #recoverStuckJobs()} (run once at startup), this is safe to run
   * periodically *while the loop is draining*: the age bound spares jobs currently being processed
   * and only re-queues genuinely-orphaned PROCESSING rows (a worker that claimed a job then died
   * mid-process without restarting). Re-indexing is idempotent, so a generous threshold makes a
   * false reset harmless. Default impl ignores the age bound (resets all) for non-SQLite queues.
   *
   * @param olderThanMs reset PROCESSING rows whose last update is at least this many ms ago
   * @return number of jobs requeued
   */
  default int recoverStuckJobs(long olderThanMs) {
    return recoverStuckJobs();
  }

  /**
   * Heartbeat: refresh {@code last_updated} on every row currently in PROCESSING, signalling that a
   * live owner (the indexing loop) is still actively draining them (tempdoc 575 §4.3b / 550 Thesis II —
   * the liveness invariant: "in-flight" derives from a live owner, not mere stream membership). The
   * indexing loop calls this at batch phase boundaries + time-gated during the write loop, so a
   * legitimately-long batch stays fresh while a loop that has DIED stops heartbeating and its rows go
   * stale — which lets {@link #recoverStuckJobs(long)} reclaim orphans on a tight liveness window
   * instead of a coarse "claimed long ago" guess. Default impl is a no-op for non-SQLite queues.
   */
  default void heartbeatProcessing() {}

  /**
   * Returns the current queue depth (pending + processing jobs).
   *
   * @return Number of jobs awaiting completion
   */
  long queueDepth();

  /**
   * Returns the total number of completed jobs.
   *
   * @return Number of DONE jobs
   */
  long completedCount();

  /**
   * Cleans up old completed and failed jobs to prevent storage bloat. Does NOT touch the
   * {@code ingestion_ledger} audit trail — see {@link #cleanupOldLedgerEvents(int)}, which
   * accepts an independent retention horizon so the audit trail can outlive queue rows.
   *
   * @param retentionDays Jobs older than this many days will be deleted
   * @return Number of jobs deleted
   */
  int cleanupOldJobs(int retentionDays);

  /**
   * Cleans up old ingestion ledger events. Decoupled from {@link #cleanupOldJobs(int)} so
   * "Why is this file missing from search?" questions can be answered for files processed
   * longer ago than the queue retention horizon (tempdoc 410 §8). Default implementation is a
   * no-op.
   */
  default int cleanupOldLedgerEvents(int retentionDays) {
    return 0;
  }

  /**
   * Returns a best-effort failure summary for observability.
   *
   * <p>Implementations that do not track failures may return zeros/nulls.
   */
  default FailureSummary failureSummary() {
    return new FailureSummary(0L, null, null, null, null);
  }

  /**
   * A permanently failed job with its error details.
   *
   * @param path normalized file path
   * @param errorMessage last error message, or null
   * @param attempts total attempts made
   * @param lastUpdatedMs epoch millis of last state transition
   * @param collection collection tag, or null for default
   */
  record FailedJobInfo(
      String path, String errorMessage, int attempts, long lastUpdatedMs, String collection) {}

  /**
   * Privacy-safe append-only ingestion event.
   *
   * <p>Implementations should avoid storing extracted text, stack traces, or absolute-path details
   * beyond the existing queue row. The compact constructor caps every string field at {@link
   * #LEDGER_ENTRY_MAX_FIELD_CHARS} to bound storage even when callers pass adversarial values
   * (e.g., a parser that reports an extreme parser-id string).
   */
  record IngestionLedgerEntry(
      String pathHash,
      String collection,
      Long sourceSizeBytes,
      Long sourceModifiedAtMs,
      String sourceKind,
      String artifactStatus,
      String policyId,
      String parserId) {
    public IngestionLedgerEntry {
      pathHash = capField(pathHash);
      collection = capField(collection);
      sourceKind = capField(sourceKind);
      artifactStatus = capField(artifactStatus);
      policyId = capField(policyId);
      parserId = capField(parserId);
    }

    private static String capField(String value) {
      if (value == null) return null;
      return value.length() <= LEDGER_ENTRY_MAX_FIELD_CHARS
          ? value
          : value.substring(0, LEDGER_ENTRY_MAX_FIELD_CHARS);
    }
  }

  /**
   * Per-string-field cap on {@link IngestionLedgerEntry}. Bounds the on-disk row size for every
   * unbounded string field (parser id, policy id, source kind, artifact status, collection, path
   * hash). Set generously enough that legitimate values fit unchanged but tightly enough that an
   * adversarial parser cannot drive ledger-row size unbounded.
   */
  int LEDGER_ENTRY_MAX_FIELD_CHARS = 256;

  /** Path plus privacy-safe metadata for an outcome transition. */
  record IngestionLedgerTransition(Path path, IngestionLedgerEntry entry) {}

  /** Export-safe ingestion ledger row. Raw job paths are intentionally omitted. */
  record IngestionEventView(
      long id,
      String pathHash,
      String collection,
      String outcomeClass,
      String reasonCode,
      String retryPolicy,
      String diagnosticSummary,
      long observedAtMs,
      Long sourceSizeBytes,
      Long sourceModifiedAtMs,
      String sourceKind,
      String artifactStatus,
      String policyId,
      String parserId) {}

  /** Aggregated export-safe outcome count. */
  record IngestionOutcomeSummary(
      String outcomeClass, String reasonCode, String retryPolicy, long count, long lastObservedAtMs) {}

  /** Records an ingestion outcome history event without changing queue state. */
  default void recordIngestionEvent(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {}

  /** Returns recent privacy-safe ingestion events, newest first. */
  default List<IngestionEventView> recentIngestionEvents(int limit) {
    return List.of();
  }

  /**
   * Returns true if the ledger already has an event for {@code pathHash} matching
   * {@code reasonCode} observed at-or-after {@code sinceMs}. Used by writers (e.g., cloud
   * placeholder detection) to dedup repeated observations of the same condition for the same
   * source. Default implementation returns false (no dedup).
   */
  default boolean hasRecentLedgerEvent(String pathHash, String reasonCode, long sinceMs) {
    return false;
  }

  /** Returns privacy-safe outcome counts since {@code sinceMs}; 0 means all retained events. */
  default List<IngestionOutcomeSummary> ingestionOutcomeSummary(long sinceMs) {
    return List.of();
  }

  /**
   * Lists jobs in FAILED state, ordered by most recent failure first.
   *
   * @param limit maximum rows to return (capped at 1000; 0 = server default 100)
   * @return list of failed jobs (may be empty)
   */
  default List<FailedJobInfo> listFailedJobs(int limit) {
    return List.of();
  }

  /**
   * Lists FAILED jobs under the given path prefix (tempdoc 599 §16/B1) — the per-folder twin of
   * {@link #listFailedJobs(int)}, backing the folder-scoped "failed files" drill-down. Default
   * falls back to the unscoped list.
   *
   * @param pathPrefix the watched-root path prefix (will be normalized)
   * @param limit maximum rows to return (capped at 1000; 0 = server default 100)
   * @return failed jobs under the prefix (may be empty)
   */
  default List<FailedJobInfo> listFailedJobsByPathPrefix(String pathPrefix, int limit) {
    return listFailedJobs(limit);
  }

  /**
   * Deletes all jobs in FAILED state.
   *
   * @return number of jobs deleted
   */
  default int clearFailedJobs() {
    return 0;
  }

  /**
   * Deletes all jobs from the queue regardless of state. Used by profiling reset.
   *
   * @return number of jobs deleted
   */
  default int clearAll() {
    return 0;
  }

  // --- Fine-grained state counts ---

  /**
   * Breakdown of job counts by sub-state.
   *
   * <p>Provides finer granularity than {@link #queueDepth()} by distinguishing PENDING-ready
   * (eligible for immediate processing) from PENDING-backoff (waiting for retry delay).
   *
   * @param pendingCount total PENDING jobs (ready + backoff)
   * @param pendingReadyCount PENDING jobs eligible for immediate processing
   * @param processingCount jobs currently being processed
   * @param doneCount completed jobs
   * @param failedCount permanently failed jobs
   */
  record JobStateCounts(
      long pendingCount,
      long pendingReadyCount,
      long processingCount,
      long doneCount,
      long failedCount) {
    /** PENDING jobs that are in backoff (waiting for retry delay). */
    public long pendingBackoffCount() {
      return Math.max(0L, pendingCount - pendingReadyCount);
    }
  }

  /**
   * Returns a breakdown of job counts by sub-state.
   *
   * <p>Default implementation provides a best-effort approximation from existing methods.
   * Implementations with richer state tracking should override for accurate counts.
   */
  default JobStateCounts jobStateCounts() {
    long depth = queueDepth();
    long completed = completedCount();
    FailureSummary fs = failureSummary();
    return new JobStateCounts(depth, depth, 0L, completed, fs.failedCount());
  }

  // --- Targeted delete operations ---

  /**
   * Deletes all jobs whose path starts with the given prefix, regardless of state.
   *
   * @param pathPrefix the path prefix to match
   * @return number of jobs deleted
   */
  default int deleteByPathPrefix(String pathPrefix) {
    return 0;
  }

  /**
   * Deletes the job with exactly the given path, regardless of state.
   *
   * @param path the exact path to match
   * @return number of jobs deleted
   */
  default int deleteByExactPath(String path) {
    return 0;
  }

  /**
   * Counts jobs whose path starts with the given prefix, broken down by sub-state. Backs the
   * per-folder indexing-status projection (tempdoc 599 §9.2): the Head folds the in-flight
   * (PENDING+PROCESSING) and FAILED counts under a watched root's path prefix into the folder row.
   *
   * <p>Counts are over the committed jobs table; staleness is bounded by the
   * {@link #recoverStuckJobs(long)} reaper, so a returned PROCESSING count never includes
   * reaper-swept zombie rows (tempdoc 599 §11/U4). Returns all-zero on error or empty prefix.
   *
   * @param pathPrefix the path prefix to match (will be normalized)
   * @return per-state counts under the prefix
   */
  default JobStateCounts countByPathPrefix(String pathPrefix) {
    return new JobStateCounts(0L, 0L, 0L, 0L, 0L);
  }

  /**
   * Snapshot of queue database health for observability surfaces.
   *
   * <p>All timestamps are epoch millis; 0 means "not set / unknown".
   */
  record QueueDbHealthSnapshot(
      boolean healthy,
      long lastQuickCheckAtMs,
      boolean lastQuickCheckOk,
      long lastBackupAtMs,
      long lastDbErrorAtMs) {}

  /**
   * Returns a snapshot of queue DB health, or {@code null} if the implementation does not
   * support health tracking.
   */
  default QueueDbHealthSnapshot queueDbHealthSnapshot() {
    return null;
  }

  /**
   * Slice 445: optional change-feed for the indexing-jobs collection. Backs the
   * {@code core.indexing-jobs} TABULAR Resource via SSE_STREAM. Implementations
   * that can't expose per-row mutations (e.g., in-memory test queues) return
   * {@link Optional#empty()} and the gRPC handler degrades to UNIMPLEMENTED.
   */
  default Optional<IndexingJobChangeFeed> indexingJobChangeFeed() {
    return Optional.empty();
  }
}
