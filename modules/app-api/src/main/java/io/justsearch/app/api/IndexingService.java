/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Index management surface exposed to desktop clients.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Enumerate active watch roots.</li>
 *   <li>Add and remove watched roots at runtime.</li>
 * </ul>
 */
public interface IndexingService {
  /**
   * Immutable representation of a watched root with optional timestamp and walk error.
   *
   * @param walkCompleted whether the folder's filesystem walk has terminated at least once
   *     (tempdoc 599 Fix 1). Distinguishes "walked, nothing to index" (walkCompleted=true, no
   *     lastIndexed, no walkError → the UI's "empty" state) from "walk in progress / never walked"
   *     (walkCompleted=false → "scanning"). The convenience constructors default it from the
   *     presence of a timestamp/error so existing callers keep their meaning.
   */
  record WatchedRoot(
      String collection,
      Path path,
      Instant lastIndexed,
      String walkError,
      boolean walkCompleted,
      boolean deleteDetectionUnverified,
      int driftOrphanCount,
      long driftOrphanAtMs,
      Instant lastVerifiedAt) {
    /**
     * Constructor without the §Recency last-verified timestamp (tempdoc 626). Defaults to {@code null}
     * (never verified); used by pre-Recency call sites.
     */
    public WatchedRoot(
        String collection,
        Path path,
        Instant lastIndexed,
        String walkError,
        boolean walkCompleted,
        boolean deleteDetectionUnverified,
        int driftOrphanCount,
        long driftOrphanAtMs) {
      this(
          collection,
          path,
          lastIndexed,
          walkError,
          walkCompleted,
          deleteDetectionUnverified,
          driftOrphanCount,
          driftOrphanAtMs,
          null);
    }
    /**
     * Constructor without the drift-corrected orphan-prune signal (tempdoc 626 §Axis-C). Defaults to
     * no recorded prune ({@code 0, 0}).
     */
    public WatchedRoot(
        String collection,
        Path path,
        Instant lastIndexed,
        String walkError,
        boolean walkCompleted,
        boolean deleteDetectionUnverified) {
      this(collection, path, lastIndexed, walkError, walkCompleted, deleteDetectionUnverified, 0, 0L);
    }
    /**
     * Constructor without the delete-detection flag (tempdoc 626 §Axis-C). Defaults to verified
     * ({@code false}); used by every pre-626 call site.
     */
    public WatchedRoot(
        String collection, Path path, Instant lastIndexed, String walkError, boolean walkCompleted) {
      this(collection, path, lastIndexed, walkError, walkCompleted, false);
    }
    /** Constructor without the explicit walk-completed flag (derived from lastIndexed/walkError). */
    public WatchedRoot(String collection, Path path, Instant lastIndexed, String walkError) {
      this(collection, path, lastIndexed, walkError, lastIndexed != null || walkError != null);
    }
    /** Constructor without walk error. */
    public WatchedRoot(String collection, Path path, Instant lastIndexed) {
      this(collection, path, lastIndexed, null, lastIndexed != null);
    }
    /** Constructor without timestamp or walk error for backward compatibility. */
    public WatchedRoot(String collection, Path path) {
      this(collection, path, null, null, false);
    }
  }

  /** List of paths currently watched for indexing. */
  List<Path> getWatchedPaths();

  /** List of watched roots with collection metadata. */
  default List<WatchedRoot> getWatchedRoots() {
    // Fallback for implementations that only support primary collection.
    return getWatchedPaths().stream().map(p -> new WatchedRoot(null, p)).toList();
  }

  /** Add a new watch root (primary collection). */
  void addWatchedPath(Path path);

  /** Add a new watch root for a specific collection. */
  default void addWatchedRoot(String collection, Path path) {
    addWatchedPath(path);
  }

  /**
   * Stop watching the given root and delete indexed documents.
   *
   * <p>Implementations may reject removal of static/configured roots.
   *
   * @param path the path to stop watching
   * @return number of deleted jobs, or -1 on error
   */
  int removeWatchedPath(Path path);

  /** Stop watching the given root for a specific collection. */
  default int removeWatchedRoot(String collection, Path path) {
    return removeWatchedPath(path);
  }

  // =========================================================================
  // Delete operations (best-effort; used by explicit maintenance actions)
  // =========================================================================

  /**
   * Deletes all documents whose doc_id/path starts with the given prefix.
   *
   * <p>Implementations must delegate deletion to the Worker/index layer (do not touch Lucene/queue DB directly).
   *
   * @param pathPrefix absolute path prefix (directory) to delete
   * @return number of deleted jobs (best-effort), or 0 on failure
   */
  default int deleteDocsByPathPrefix(Path pathPrefix) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Deletes a single document by exact doc_id (normalized absolute path string).
   *
   * <p>Implementations must delegate deletion to the Worker/index layer (do not touch Lucene/queue DB directly).
   *
   * @param docId document id (normalized absolute path)
   * @return true if the Worker accepted the delete request
   */
  default boolean deleteDocById(String docId) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Re-index all currently watched roots (best-effort).
   *
   * <p>Implementations should scan existing files under each watched root and submit batches to the
   * indexing pipeline. May be expensive for large trees.
   */
  default void reindex() {
    reindexWatchedRoots();
  }

  /**
   * Re-index all currently watched roots (best-effort).
   *
   * <p>Implementations should scan existing files under each watched root and submit batches to the
   * indexing pipeline. May be expensive for large trees.
   */
  default void reindexWatchedRoots() {
    reindexWatchedRoots(false);
  }

  /**
   * Re-index all currently watched roots with optional force flag.
   *
   * <p>When force=true, bypasses the "file unchanged" optimization and re-extracts
   * all documents even if file modification time hasn't changed. Use this after
   * schema changes to ensure all documents are re-indexed with the new schema.
   *
   * @param force if true, bypass unchanged check and force re-extraction
   */
  default void reindexWatchedRoots(boolean force) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Tempdoc 626 §Recency (Move C) — verify/reconcile a SINGLE watched root identified by its {@code
   * pathHash} (the privacy-safe wire identifier — ADR-0028; raw paths never cross the wire). The
   * implementation resolves the hash to the real path Head-side and runs a {@code force} reconcile
   * (re-prune orphans + re-walk), which re-converges the root and refreshes its per-root verification
   * state (clears {@code deleteDetectionUnverified}, stamps {@code lastVerifiedAt}). This is the
   * granularity-matched recovery for the {@code index.drift-unknown} condition — scoped to one folder
   * instead of a corpus-wide reindex.
   *
   * @param pathHash SHA-256 hex of the watched root's absolute path
   * @param force when true (the recovery default), bypass the mtime fast-path and fully re-converge
   * @return true if a matching root was found and the reconcile was dispatched
   */
  default boolean reconcileRoot(String pathHash, boolean force) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  // =========================================================================
  // Migration controls (Phase H)
  // =========================================================================

  /** Starts a Blue/Green migration (best-effort). Implementations may restart the worker. */
  default boolean startMigration(String reason) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Requests cutover (best-effort). Implementations may no-op if migration is not in progress. */
  default boolean requestCutover(boolean forceSwitching) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Rolls back to the previous generation (best-effort). Implementations may restart the worker. */
  default boolean rollbackMigration() {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Pauses migration orchestration (enumerator + cutover monitor). */
  default boolean pauseMigration(String reason) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Resumes migration orchestration. */
  default boolean resumeMigration() {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Outcome of an index GC invocation. Carries the worker-side counts so the
   * Operation handler's structured-output map (and the REST controller's response
   * body) can surface marked / pruned deltas — both are already populated by the
   * worker's gRPC response (see {@code MigrationOps.runIndexGc}); the prior
   * {@code boolean} return shape was dropping them at the service boundary.
   *
   * <p>Per slice 484 §3.6 / observations.md `core.index-gc` closure.
   *
   * @param accepted whether the worker accepted the GC request (false = rejected)
   * @param markedCount number of generations newly marked for deletion (0 if rejected)
   * @param prunedCount number of marked generations physically removed (0 if rejected)
   * @param error worker error message when {@code accepted=false}; empty string on success
   */
  record IndexGcOutcome(boolean accepted, int markedCount, int prunedCount, String error) {
    public IndexGcOutcome {
      java.util.Objects.requireNonNull(error, "error");
    }

    /** Sentinel for the unavailable-service code path. */
    public static IndexGcOutcome unavailable() {
      return new IndexGcOutcome(false, 0, 0, "Indexing service unavailable");
    }
  }

  /**
   * Runs best-effort index GC (mark + prune) for old generations/backups. Returns
   * the worker's structured outcome so callers can surface marked / pruned counts
   * (handler's structured-output map; REST response body).
   */
  default IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  // =========================================================================
  // Failed job inspection
  // =========================================================================

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

  /** Lists jobs in FAILED state, ordered by most recent failure first. */
  default List<FailedJobInfo> listFailedJobs(int limit) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Lists FAILED jobs under a watched-root path prefix (tempdoc 599 §16/B1) — the per-folder
   * "failed files" drill-down. Returns empty when the Worker is unavailable.
   */
  default List<FailedJobInfo> listFailedJobsByPathPrefix(java.nio.file.Path pathPrefix, int limit) {
    return List.of();
  }

  /** Deletes all jobs in FAILED state. */
  default int clearFailedJobs() {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Per-folder indexing-job counts under a watched root's path prefix (tempdoc 599 §9.2). The
   * folder-status projection folds {@code inFlight} (PENDING+PROCESSING) and {@code failed} into the
   * Library row, so a folder can report a truthful "indexing · N remaining → searchable" state
   * derived from job drain rather than the walk-completion timestamp.
   *
   * @param inFlight PENDING + PROCESSING jobs under the prefix (in-flight; reaper-bounded)
   * @param failed permanently FAILED jobs under the prefix
   */
  record JobCounts(long inFlight, long failed) {
    /** All-zero sentinel for the unavailable / empty-prefix path. */
    public static JobCounts zero() {
      return new JobCounts(0L, 0L);
    }
  }

  /**
   * Counts in-flight and failed indexing jobs under the given watched-root path prefix. Returns
   * {@link JobCounts#zero()} when the Worker is unavailable (a degraded folder row, never a throw).
   */
  default JobCounts countJobsByPathPrefix(Path pathPrefix) {
    return JobCounts.zero();
  }

  /**
   * Privacy-safe ingestion ledger events from the Worker (tempdoc 410 §12). Each row carries a
   * SHA-256 path-hash, never the raw path. Implementations that don't support the ledger may
   * return an empty list rather than throwing.
   */
  default List<java.util.Map<String, Object>> recentIngestionEvents(int limit) {
    return List.of();
  }

  /**
   * Aggregated ingestion outcome counts since {@code sinceMs} (epoch ms; 0 = all retained).
   * See {@link #recentIngestionEvents(int)}.
   */
  default List<java.util.Map<String, Object>> ingestionOutcomeSummary(long sinceMs) {
    return List.of();
  }

  /**
   * Scoped reverse-lookup of {@code pathHash → path} (ADR-0028, tempdoc 419 T5.3). Returns a
   * map with keys {@code found} (boolean), {@code path} (string, present iff found),
   * {@code lastSeenAtMs} (long), {@code removedAtMs} (long, 0 when still present). Backs the
   * single-purpose endpoint {@code POST /api/library/resolve-hash}; diagnostic export endpoints
   * MUST NOT call this method.
   */
  default java.util.Map<String, Object> resolvePathHash(String pathHash) {
    return java.util.Map.of("found", false);
  }

  /**
   * Slice 445: cancel an in-flight indexing job by its {@code pathHash} (SHA-256 hex of the
   * absolute normalized path). The worker resolves the hash, marks the row terminal with a
   * {@code CANCELLED} outcome, and the change-feed emits an UPDATE delta. Returns a map with
   * {@code cancelled} (boolean) and {@code previousState} (string, diagnostic).
   */
  default java.util.Map<String, Object> cancelIndexingJob(String pathHash) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Slice 445: retry a FAILED indexing job by its {@code pathHash}. The worker re-enqueues the
   * row as PENDING, replacing any existing FAILED entry. Returns a map with {@code retried}
   * (boolean) and {@code previousState} (string, diagnostic).
   */
  default java.util.Map<String, Object> retryIndexingJob(String pathHash) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Clears all watched roots (stops watchers, clears state, persists). Used by profiling reset. */
  default void clearAllRoots() {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Resets index state on the Worker via gRPC. Used by profiling reset. Returns true on success. */
  default boolean resetIndex() {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /**
   * Tempdoc 406 — admin-triggered runtime swap. Drains current ingest runtime, opens
   * a fresh one on the same path. Returns swap duration in ms.
   *
   * @param reason low-cardinality telemetry tag (e.g., "admin_triggered")
   */
  default long reloadRuntime(String reason) {
    throw new UnsupportedOperationException("Indexing service unavailable");
  }

  /** Flush pending indexing work (best effort). Implementations may no-op or throw if unavailable. */
  void flush();

  /**
   * Null Object for environments where the Worker isn't connected. Returns empty
   * collections from read methods and throws {@code UnsupportedOperationException} from
   * mutating methods. Used by {@code LocalApiServer} Builder fallback and by test fixtures
   * that need a non-null IndexingService. Tempdoc 519 F2 (refined per §22): kept as the Null
   * Object pattern.
   */
  static IndexingService unavailable() {
    return new IndexingService() {
      @Override
      public List<Path> getWatchedPaths() {
        return List.of();
      }

      @Override
      public void addWatchedPath(Path path) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public void addWatchedRoot(String collection, Path path) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public int removeWatchedPath(Path path) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public int removeWatchedRoot(String collection, Path path) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public int deleteDocsByPathPrefix(Path pathPrefix) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean deleteDocById(String docId) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public void reindexWatchedRoots(boolean force) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean startMigration(String reason) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean requestCutover(boolean forceSwitching) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean rollbackMigration() {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean pauseMigration(String reason) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public boolean resumeMigration() {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }

      @Override
      public void flush() {
        throw new UnsupportedOperationException("Indexing service unavailable");
      }
    };
  }
}
