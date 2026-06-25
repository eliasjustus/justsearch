/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed durable job queue for file ingestion.
 *
 * <p>This queue persists pending file paths to disk, ensuring durability across
 * worker restarts. All database access is serialized using a lock to prevent
 * concurrent write conflicts.
 *
 * <p>Job states:
 * <ul>
 *   <li>PENDING - Awaiting processing</li>
 *   <li>PROCESSING - Currently being indexed</li>
 *   <li>DONE - Successfully indexed</li>
 *   <li>FAILED - Failed after max attempts</li>
 * </ul>
 */
public final class SqliteJobQueue implements SwitchBufferCapableQueue {
  private static final Logger log = LoggerFactory.getLogger(SqliteJobQueue.class);

  /** Default maximum retry attempts before marking a job as FAILED. */
  private static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** SQLite busy timeout in milliseconds. */
  private static final int BUSY_TIMEOUT_MS = 5000;

  /**
   * Minimum free disk space (50 MB) required to accept new enqueues. Below this threshold the
   * queue refuses enqueue() to prevent SQLite write-during-full-disk corruption. Failure mode
   * surfaces today as a SQLITE_IOERR_FULL caught and logged, but pre-checking is cheaper and
   * fail-closed.
   */
  private static final long MIN_FREE_DISK_BYTES = 50L * 1024 * 1024;

  private final Path dbPath;
  private final ReentrantLock lock = new ReentrantLock();
  private final int maxAttempts;
  private Connection connection;
  private boolean existedBeforeOpen;
  private boolean forceIntegrityCheck;

  /**
   * Slice 445 producer scaffolding. Captures per-row INSERT/UPDATE/DELETE on the
   * {@code jobs} table via SQLite update + commit hooks and broadcasts typed
   * {@link IndexingJobsChangeStream.Delta} events to subscribers (e.g., the
   * gRPC streaming RPC that backs the {@code core.indexing-jobs} TABULAR
   * Resource). Lazily attached in {@link #open()}; detached in {@link #close()}.
   */
  private IndexingJobsChangeStream changeStream;

  // ==================== Health Tracking ====================

  // QueueDbHealthSnapshot record moved to JobQueue interface (Step 3 module split).

  private volatile boolean dbHealthy = true;
  private volatile long lastQuickCheckAtMs;
  private volatile boolean lastQuickCheckOk;
  private volatile long lastBackupAtMs;
  private volatile long lastDbErrorAtMs;

  /**
   * Hook called after each migration step (for testing migration rollback).
   * If this throws, the migration should roll back.
   */
  @FunctionalInterface
  interface MigrationStepHook {
    void afterStep(int version) throws SQLException;
  }

  private final MigrationStepHook migrationStepHook;
  private final SqliteQueueSwitchBufferOps switchBufferOps;

  /**
   * Creates a SqliteJobQueue backed by the specified SQLite database file.
   *
   * @param dbPath Path to the jobs.db file
   */
  public SqliteJobQueue(Path dbPath) {
    this(dbPath, DEFAULT_MAX_ATTEMPTS, null, null);
  }

  /**
   * Creates a SqliteJobQueue with a custom max retry count.
   *
   * @param dbPath Path to the jobs.db file
   * @param maxAttempts Maximum retry attempts before failing a job
   */
  public SqliteJobQueue(Path dbPath, int maxAttempts) {
    this(dbPath, maxAttempts, null, null);
  }

  /**
   * Creates a SqliteJobQueue with a custom max retry count and a write-failure callback.
   *
   * @param dbPath Path to the jobs.db file
   * @param maxAttempts Maximum retry attempts before failing a job
   * @param onSwitchBufferWriteFailure Optional callback invoked once per switch-buffer write
   *     failure (may be null). Tempdoc 417 Phase 3c: replaces the legacy
   *     {@code Telemetry.Counter} parameter to decouple this layer from the soon-to-be-retired
   *     {@code Telemetry} interface.
   */
  public SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure) {
    this(dbPath, maxAttempts, onSwitchBufferWriteFailure, null);
  }

  /**
   * Creates a SqliteJobQueue with all options (including test hook).
   *
   * <p>The migrationStepHook is package-private for testing migration rollback scenarios.
   */
  SqliteJobQueue(Path dbPath, int maxAttempts, Runnable onSwitchBufferWriteFailure,
                 MigrationStepHook migrationStepHook) {
    this.dbPath = dbPath;
    this.maxAttempts = maxAttempts;
    this.migrationStepHook = migrationStepHook;
    this.switchBufferOps =
        new SqliteQueueSwitchBufferOps(
            lock, this::ensureOpenAndGetConnection, onSwitchBufferWriteFailure,
            this::recordDbError);
  }

  private Connection ensureOpenAndGetConnection() {
    ensureOpen();
    return connection;
  }

  @Override
  public void open() throws SQLException, IOException {
    lock.lock();
    try {
      Files.createDirectories(dbPath.getParent());

      // Capture whether DB existed BEFORE opening (JDBC will create empty file if missing)
      existedBeforeOpen = Files.exists(dbPath) && Files.size(dbPath) > 0;

      String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
      connection = DriverManager.getConnection(jdbcUrl);

      // Configure SQLite for better concurrency
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        stmt.execute("PRAGMA journal_mode = WAL");
        stmt.execute("PRAGMA synchronous = NORMAL");
        // Enable incremental auto-vacuum. On new databases this takes effect immediately.
        // On existing databases with auto_vacuum=0, this has no effect until a full VACUUM.
        stmt.execute("PRAGMA auto_vacuum = 2");
      }

      initSchema();

      // Run integrity check on existing databases (throws SQLException on corruption)
      performIntegrityCheck();

      // Slice 445: attach change-stream after schema is up so the rowId cache
      // sees the post-migration row set.
      changeStream = new IndexingJobsChangeStream(connection);

      log.info("SqliteJobQueue opened: {}", dbPath);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Opens the queue and forces an integrity check regardless of whether the database
   * existed before opening. Use this after restoring from a backup to validate the
   * restored database.
   */
  @Override
  public void openWithIntegrityCheck() throws SQLException, IOException {
    this.forceIntegrityCheck = true;
    try {
      open();
    } finally {
      this.forceIntegrityCheck = false;
    }
  }

  /**
   * Initializes the database schema and runs migrations.
   *
   * <p>This method handles both fresh databases and existing databases that need
   * migration. It uses PRAGMA user_version for version tracking and applies
   * migrations sequentially using a ladder pattern.
   *
   * @throws SQLException if schema initialization or migration fails
   * @throws IOException if backup fails before migration
   */
  private void initSchema() throws SQLException, IOException {
    // Create base tables if they don't exist
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(SqliteSchema.CREATE_JOBS_TABLE);
      stmt.execute(SqliteSchema.CREATE_JOBS_STATE_INDEX);
      stmt.execute(SqliteSchema.CREATE_JOBS_STATE_UPDATED_INDEX);
      stmt.execute(SqliteSchema.CREATE_SWITCH_BUFFER_TABLE);
      stmt.execute(SqliteSchema.CREATE_SWITCH_BUFFER_INDEX);
    }

    // Run versioned migrations
    SqliteQueueMigrationOps.runMigrations(connection, this::performBackup, migrationStepHook);
  }

  /** Returns the number of buffered ops currently in the durable switch buffer (best-effort). */
  @Override
  public long switchBufferDepth() { return switchBufferOps.depth(); }

  /** Returns best-effort counts for PENDING/PROCESSING/DONE/FAILED and PENDING runnable subset. */
  @Override
  public JobQueue.JobStateCounts jobStateCounts() { return switchBufferOps.stateCounts(); }

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   */
  @Override
  public boolean putSwitchBuffer(String key, String op, String payload) {
    return switchBufferOps.put(key, op, payload);
  }

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  @Override
  public List<SwitchBufferCapableQueue.SwitchBufferOp> listSwitchBufferOps() {
    return switchBufferOps.listAll();
  }

  /** Clears all buffered ops. */
  @Override
  public int clearSwitchBuffer() { return switchBufferOps.clear(); }


  @Override
  public int enqueue(List<Path> paths, String collection) {
    if (paths == null || paths.isEmpty()) {
      return 0;
    }

    if (!hasSufficientDiskSpace()) {
      log.warn("Refusing to enqueue {} jobs: insufficient free disk space (< {} MB)",
          paths.size(), MIN_FREE_DISK_BYTES / 1024 / 1024);
      return 0;
    }

    lock.lock();
    try {
      ensureOpen();

      String sql = """
          INSERT OR REPLACE INTO jobs (path, state, attempts, last_updated, collection)
          VALUES (?, 'PENDING', 0, ?, ?)
          """;

      long now = System.currentTimeMillis();
      int count = 0;
      String col = (collection != null && !collection.isBlank()) ? collection : null;

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        for (Path path : paths) {
          String normalizedPath = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
          stmt.setString(1, normalizedPath);
          stmt.setLong(2, now);
          stmt.setString(3, col);
          stmt.addBatch();
          count++;
        }
        stmt.executeBatch();
      }

      log.debug("Enqueued {} jobs (collection={})", count, col);
      return count;
    } catch (SQLException e) {
      log.error("Failed to enqueue jobs", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<IndexJob> pollPending(int limit) {
    lock.lock();
    try {
      ensureOpen();

      long now = System.currentTimeMillis();

      // Atomic claim using UPDATE...RETURNING (SQLite 3.35+)
      // This atomically selects and marks jobs as PROCESSING in a single statement,
      // preventing race conditions and avoiding partial progress on crashes.
      // Note: attempts is NOT incremented here - it's incremented on failure in markFailed()
      String atomicClaimSql = """
          UPDATE jobs
          SET state = 'PROCESSING', last_updated = ?
          WHERE path IN (
            SELECT path FROM jobs
            WHERE state = 'PENDING' AND (retry_after IS NULL OR retry_after <= ?)
            ORDER BY last_updated ASC
            LIMIT ?
          )
          RETURNING path, collection
          """;

      List<IndexJob> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(atomicClaimSql)) {
        stmt.setLong(1, now);
        stmt.setLong(2, now);
        stmt.setInt(3, limit);
        // Use executeQuery() for UPDATE...RETURNING to get the result set
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new IndexJob(Path.of(rs.getString(1)), rs.getString(2)));
          }
        }
      }

      if (!result.isEmpty()) {
        log.debug("Claimed {} jobs for processing", result.size());
      }

      return result;
    } catch (SQLException e) {
      log.error("Failed to poll pending jobs", e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markDone(Path path) {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          UPDATE jobs SET state = 'DONE', last_updated = ?
          WHERE path = ?
          """;

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        stmt.setString(2, PathNormalizer.normalizePath(path.toAbsolutePath().toString()));
        stmt.executeUpdate();
      }

      log.debug("Marked job done: {}", path);
    } catch (SQLException e) {
      log.error("Failed to mark job done: {}", path, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markDone(Path path, IngestionOutcome outcome) {
    markDone(path, outcome, null);
  }

  @Override
  public void markDone(Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'DONE', last_updated = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  long now = System.currentTimeMillis();
                  bindOutcomeUpdate(stmt, 1, now, outcome, normalizedPath);
                  int rows = stmt.executeUpdate();
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, entry);
                  }
                  return rows;
                }
              });
      logIfNoRows(updated, "markDone(outcome)", path);
      log.debug("Marked job done with outcome {}: {}", outcomeClassName(outcome), path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "Outcome-aware markDone failed for " + path + " (transaction rolled back)", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markDoneBatch(java.util.Collection<Path> paths) {
    if (paths == null || paths.isEmpty()) return;
    lock.lock();
    try {
      ensureOpen();

      long now = System.currentTimeMillis();
      // SQLite has a default SQLITE_MAX_VARIABLE_NUMBER of 999.
      // Chunk into batches of 499 (2 params per row: state timestamp + path).
      int chunkSize = 499;
      var pathList = paths instanceof List<?> ? (List<Path>) paths
          : new ArrayList<>(paths);

      for (int offset = 0; offset < pathList.size(); offset += chunkSize) {
        int end = Math.min(offset + chunkSize, pathList.size());
        var chunk = pathList.subList(offset, end);

        StringBuilder sb = new StringBuilder("UPDATE jobs SET state = 'DONE', last_updated = ? WHERE path IN (");
        for (int i = 0; i < chunk.size(); i++) {
          if (i > 0) sb.append(',');
          sb.append('?');
        }
        sb.append(')');

        try (PreparedStatement stmt = connection.prepareStatement(sb.toString())) {
          stmt.setLong(1, now);
          for (int i = 0; i < chunk.size(); i++) {
            stmt.setString(2 + i, PathNormalizer.normalizePath(
                chunk.get(i).toAbsolutePath().toString()));
          }
          stmt.executeUpdate();
        }
      }

      log.debug("Marked {} jobs done (batch)", paths.size());
    } catch (SQLException e) {
      log.error("Failed to mark {} jobs done (batch)", paths.size(), e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markDoneBatch(java.util.Collection<Path> paths, IngestionOutcome outcome) {
    if (paths == null || paths.isEmpty()) return;
    List<JobQueue.IngestionLedgerTransition> transitions = new ArrayList<>(paths.size());
    for (Path path : paths) {
      transitions.add(new JobQueue.IngestionLedgerTransition(path, null));
    }
    markDoneTransitions(transitions, outcome);
  }

  @Override
  public void markDoneTransitions(
      java.util.Collection<JobQueue.IngestionLedgerTransition> transitions,
      IngestionOutcome outcome) {
    if (transitions == null || transitions.isEmpty()) return;
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      int[] updates =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'DONE', last_updated = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                List<Integer> rowCounts = new ArrayList<>(transitions.size());
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  for (JobQueue.IngestionLedgerTransition transition : transitions) {
                    if (transition == null) continue;
                    String normalizedPath = normalizePath(transition.path());
                    bindOutcomeUpdate(stmt, 1, now, outcome, normalizedPath);
                    int rows = stmt.executeUpdate();
                    rowCounts.add(rows);
                    if (rows > 0) {
                      insertLedgerEvent(normalizedPath, outcome, transition.entry());
                    }
                  }
                }
                int[] result = new int[rowCounts.size()];
                for (int i = 0; i < rowCounts.size(); i++) {
                  result[i] = rowCounts.get(i);
                }
                return result;
              });
      logBatchMisses(updates, "markDoneTransitions(outcome)");
      log.debug("Marked {} jobs done with outcome {}", transitions.size(), outcomeClassName(outcome));
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "markDoneTransitions failed for "
              + transitions.size()
              + " path(s) (transaction rolled back)",
          e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markFailed(Path path, String errorMessage) {
    lock.lock();
    try {
      ensureOpen();

      // First, check current attempts
      String checkSql = "SELECT attempts FROM jobs WHERE path = ?";
      int currentAttempts = 0;

      String normalizedPath = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
      try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
        stmt.setString(1, normalizedPath);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            currentAttempts = rs.getInt(1);
          }
        }
      }

      // Increment attempts on failure (not on claim)
      int newAttempts = currentAttempts + 1;

      String newState = newAttempts >= maxAttempts ? "FAILED" : "PENDING";
      long now = System.currentTimeMillis();

      // Calculate retry_after with exponential backoff + jitter
      // Base backoff: 1s * 2^(newAttempts-1), capped at ~17 minutes
      // Jitter: random value in [0, min(1s, backoff)] to prevent retry storms
      Long retryAfter = null;
      if ("PENDING".equals(newState)) {
        long backoffMs = 1000L * (1L << Math.min(newAttempts - 1, 10));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(1000L, backoffMs) + 1);
        retryAfter = now + backoffMs + jitter;
      }

      // Update with incremented attempts
      String updateSql = """
          UPDATE jobs SET state = ?, attempts = ?, last_updated = ?, error_message = ?, retry_after = ?
          WHERE path = ?
          """;

      try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
        stmt.setString(1, newState);
        stmt.setInt(2, newAttempts);
        stmt.setLong(3, now);
        stmt.setString(4, errorMessage);
        if (retryAfter != null) {
          stmt.setLong(5, retryAfter);
        } else {
          stmt.setNull(5, java.sql.Types.INTEGER);
        }
        stmt.setString(6, normalizedPath);
        stmt.executeUpdate();
      }

      if ("FAILED".equals(newState)) {
        log.warn("Job permanently failed after {} attempts: {}", newAttempts, path);
      } else {
        long backoffSeconds = retryAfter != null ? (retryAfter - now) / 1000 : 0;
        log.debug("Job failed (attempt {}), will retry after {}s: {}", newAttempts, backoffSeconds, path);
      }
    } catch (SQLException e) {
      log.error("Failed to mark job failed: {}", path, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void recordOutcome(Path path, IngestionOutcome outcome) {
    if (outcome == null) return;
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  bindOutcomeOnly(stmt, 1, outcome, normalizedPath);
                  return stmt.executeUpdate();
                }
              });
      logIfNoRows(updated, "recordOutcome", path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "recordOutcome failed for " + path + " (transaction rolled back)", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markFailed(Path path, IngestionOutcome outcome) {
    markFailed(path, outcome, null);
  }

  @Override
  public void markFailed(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    markFailedWithOutcome(path, outcome, entry);
  }

  @Override
  public void defer(Path path, IngestionOutcome outcome) {
    defer(path, outcome, null);
  }

  @Override
  public void defer(Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      String normalizedPath = normalizePath(path);
      int updated =
          inTransaction(
              () -> {
                String sql = """
                    UPDATE jobs
                    SET state = 'PENDING', last_updated = ?, retry_after = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                  stmt.setLong(1, now);
                  stmt.setLong(2, now + 1000L);
                  bindOutcomeOnly(stmt, 3, outcome, normalizedPath);
                  int rows = stmt.executeUpdate();
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, entry);
                  }
                  return rows;
                }
              });
      logIfNoRows(updated, "defer(outcome)", path);
      log.debug("Deferred job without incrementing attempts: {}", path);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "defer failed for " + path + " (transaction rolled back)", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Failed-state transition driven by {@code outcome.retryPolicy()}.
   *
   * <p>{@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#NONE} forces the FAILED
   * terminal state regardless of attempt count. {@link
   * io.justsearch.indexerworker.ingest.IngestionRetryPolicy#RETRY_WITH_BACKOFF} follows the
   * normal attempts-vs-maxAttempts decision and applies exponential backoff. Outcomes carrying
   * {@link io.justsearch.indexerworker.ingest.IngestionRetryPolicy#DEFER_WITHOUT_ATTEMPT} are
   * rejected here — they belong to {@link #defer(Path, IngestionOutcome,
   * JobQueue.IngestionLedgerEntry)} and routing them through {@code markFailed} would silently
   * mark the job FAILED while incrementing the attempt counter.
   */
  private void markFailedWithOutcome(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    if (outcome != null
        && outcome.retryPolicy()
            == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT) {
      throw new IllegalArgumentException(
          "Outcome with retryPolicy=DEFER_WITHOUT_ATTEMPT must be routed through defer(...), not markFailed(...)");
    }
    boolean terminal =
        outcome != null
            && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE;
    lock.lock();
    try {
      ensureOpen();
      String normalizedPath = normalizePath(path);
      FailedTransitionResult result =
          inTransaction(
              () -> {
                int currentAttempts = readAttempts(normalizedPath);
                int newAttempts = currentAttempts + 1;
                String newState = terminal || newAttempts >= maxAttempts ? "FAILED" : "PENDING";
                long now = System.currentTimeMillis();
                Long retryAfter = null;
                if ("PENDING".equals(newState)) {
                  long backoffMs = 1000L * (1L << Math.min(newAttempts - 1, 10));
                  long jitter =
                      ThreadLocalRandom.current().nextLong(0, Math.min(1000L, backoffMs) + 1);
                  retryAfter = now + backoffMs + jitter;
                }

                String updateSql = """
                    UPDATE jobs
                    SET state = ?, attempts = ?, last_updated = ?, error_message = ?, retry_after = ?,
                        last_outcome_class = ?, last_reason_code = ?, last_retry_policy = ?,
                        last_diagnostic_summary = ?, last_outcome_at = ?
                    WHERE path = ?
                    """;
                try (PreparedStatement stmt = connection.prepareStatement(updateSql)) {
                  stmt.setString(1, newState);
                  stmt.setInt(2, newAttempts);
                  stmt.setLong(3, now);
                  stmt.setString(4, outcome != null ? outcome.diagnosticSummary() : null);
                  if (retryAfter != null) {
                    stmt.setLong(5, retryAfter);
                  } else {
                    stmt.setNull(5, java.sql.Types.INTEGER);
                  }
                  bindOutcomeOnly(stmt, 6, outcome, normalizedPath);
                  int rows = stmt.executeUpdate();
                  if (rows > 0) {
                    insertLedgerEvent(normalizedPath, outcome, entry);
                  }
                  return new FailedTransitionResult(rows, newAttempts, newState, retryAfter);
                }
              });
      logIfNoRows(result.updated(), terminal ? "markFailed(terminal)" : "markFailed(retryable)", path);

      if ("FAILED".equals(result.state())) {
        log.warn("Job permanently failed after {} attempts: {}", result.attempts(), path);
      } else {
        long backoffSeconds =
            result.retryAfterMs() != null
                ? Math.max(0L, (result.retryAfterMs() - System.currentTimeMillis()) / 1000)
                : 0;
        log.debug(
            "Job failed with outcome {} (attempt {}), retry after {}s: {}",
            outcomeClassName(outcome),
            result.attempts(),
            backoffSeconds,
            path);
      }
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "markFailed failed for " + path + " (transaction rolled back)", e);
    } finally {
      lock.unlock();
    }
  }

  private int readAttempts(String normalizedPath) throws SQLException {
    String checkSql = "SELECT attempts FROM jobs WHERE path = ?";
    try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
      stmt.setString(1, normalizedPath);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private record FailedTransitionResult(int updated, int attempts, String state, Long retryAfterMs) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  private <T> T inTransaction(SqlWork<T> work) throws SQLException {
    boolean wasAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      T result = work.run();
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(wasAutoCommit);
    }
  }

  @Override
  public void recordIngestionEvent(
      Path path, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry) {
    if (outcome == null) return;
    lock.lock();
    try {
      ensureOpen();
      insertLedgerEvent(normalizePath(path), outcome, entry);
    } catch (SQLException e) {
      throw new OutcomeWriteException(
          "recordIngestionEvent failed for " + path, e);
    } finally {
      lock.unlock();
    }
  }

  private void insertLedgerEvent(
      String normalizedPath, IngestionOutcome outcome, JobQueue.IngestionLedgerEntry entry)
      throws SQLException {
    if (outcome == null) return;
    String sql = """
        INSERT INTO ingestion_ledger (
          path_hash, collection, outcome_class, reason_code, retry_policy,
          diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
          source_kind, artifact_status, policy_id, parser_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      JobQueue.IngestionLedgerEntry normalizedEntry = normalizeLedgerEntry(normalizedPath, entry);
      stmt.setString(1, normalizedEntry.pathHash());
      stmt.setString(2, normalizedEntry.collection());
      stmt.setString(3, outcome.outcomeClass().name());
      stmt.setString(4, outcome.reasonCode());
      stmt.setString(5, outcome.retryPolicy().name());
      stmt.setString(6, outcome.diagnosticSummary());
      stmt.setLong(7, outcome.observedAtMs());
      setNullableLong(stmt, 8, normalizedEntry.sourceSizeBytes());
      setNullableLong(stmt, 9, normalizedEntry.sourceModifiedAtMs());
      stmt.setString(10, normalizedEntry.sourceKind());
      stmt.setString(11, normalizedEntry.artifactStatus());
      stmt.setString(12, normalizedEntry.policyId());
      stmt.setString(13, normalizedEntry.parserId());
      stmt.executeUpdate();
    }
  }

  private static JobQueue.IngestionLedgerEntry normalizeLedgerEntry(
      String normalizedPath, JobQueue.IngestionLedgerEntry entry) {
    if (entry == null) {
      return new JobQueue.IngestionLedgerEntry(
          sha256(normalizedPath), null, null, null, "UNKNOWN", "NOT_CREATED", "UNKNOWN", "UNKNOWN");
    }
    return new JobQueue.IngestionLedgerEntry(
        entry.pathHash() != null ? entry.pathHash() : sha256(normalizedPath),
        entry.collection(),
        entry.sourceSizeBytes(),
        entry.sourceModifiedAtMs(),
        entry.sourceKind() != null ? entry.sourceKind() : "UNKNOWN",
        entry.artifactStatus() != null ? entry.artifactStatus() : "NOT_CREATED",
        entry.policyId() != null ? entry.policyId() : "UNKNOWN",
        entry.parserId() != null ? entry.parserId() : "UNKNOWN");
  }

  private static void setNullableLong(PreparedStatement stmt, int index, Long value)
      throws SQLException {
    if (value != null) {
      stmt.setLong(index, value);
    } else {
      stmt.setNull(index, java.sql.Types.INTEGER);
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String normalizePath(Path path) {
    return PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static String outcomeClassName(IngestionOutcome outcome) {
    return outcome != null ? outcome.outcomeClass().name() : "null";
  }

  private void logIfNoRows(int updated, String operation, Path path) {
    if (updated == 0) {
      log.warn("{} affected no queue rows for {}", operation, path);
    }
  }

  private void logBatchMisses(int[] updates, String operation) {
    int misses = 0;
    for (int updated : updates) {
      if (updated == 0) {
        misses++;
      }
    }
    if (misses > 0) {
      log.warn("{} affected no queue rows for {} path(s)", operation, misses);
    }
  }

  private static void bindOutcomeUpdate(
      PreparedStatement stmt, int startIndex, long now, IngestionOutcome outcome, String path)
      throws SQLException {
    stmt.setLong(startIndex, now);
    bindOutcomeOnly(stmt, startIndex + 1, outcome, path);
  }

  private static void bindOutcomeOnly(
      PreparedStatement stmt, int startIndex, IngestionOutcome outcome, String path)
      throws SQLException {
    if (outcome != null) {
      stmt.setString(startIndex, outcome.outcomeClass().name());
      stmt.setString(startIndex + 1, outcome.reasonCode());
      stmt.setString(startIndex + 2, outcome.retryPolicy().name());
      stmt.setString(startIndex + 3, outcome.diagnosticSummary());
      stmt.setLong(startIndex + 4, outcome.observedAtMs());
    } else {
      stmt.setNull(startIndex, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 1, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 2, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 3, java.sql.Types.VARCHAR);
      stmt.setNull(startIndex + 4, java.sql.Types.INTEGER);
    }
    stmt.setString(startIndex + 5, path);
  }

  @Override
  public int recoverStuckJobs() {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          UPDATE jobs SET state = 'PENDING', last_updated = ?
          WHERE state = 'PROCESSING'
          """;

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        int count = stmt.executeUpdate();
        if (count > 0) {
          log.info("Recovered {} stuck jobs", count);
        }
        return count;
      }
    } catch (SQLException e) {
      log.error("Failed to recover stuck jobs", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Age-bounded variant for the periodic liveness reaper (tempdoc 550 Thesis II): re-queue only
   * PROCESSING rows whose {@code last_updated} is older than {@code olderThanMs}. A generous
   * threshold spares jobs the loop is actively processing while reclaiming genuinely-orphaned rows
   * (worker claimed then died mid-process); re-indexing is idempotent so a false reset is harmless.
   */
  @Override
  public int recoverStuckJobs(long olderThanMs) {
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      String sql = """
          UPDATE jobs SET state = 'PENDING', last_updated = ?
          WHERE state = 'PROCESSING' AND last_updated < ?
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, now);
        stmt.setLong(2, now - olderThanMs);
        int count = stmt.executeUpdate();
        if (count > 0) {
          log.info("Reaped {} stale PROCESSING jobs (older than {} ms) → PENDING", count, olderThanMs);
        }
        return count;
      }
    } catch (SQLException e) {
      log.error("Failed to reap stale stuck jobs", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Liveness heartbeat (tempdoc 575 §4.3b / 550 Thesis II): refresh {@code last_updated} on every
   * PROCESSING row, so a fresh timestamp means the indexing loop (the live owner) still holds it — the
   * age-bounded {@link #recoverStuckJobs(long)} reaper reclaims only rows whose heartbeat went stale.
   */
  @Override
  public void heartbeatProcessing() {
    lock.lock();
    try {
      ensureOpen();
      String sql = "UPDATE jobs SET last_updated = ? WHERE state = 'PROCESSING'";
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, System.currentTimeMillis());
        stmt.executeUpdate();
      }
    } catch (SQLException e) {
      // Best-effort: a missed beat at worst lets the reaper reclaim a live job (idempotent re-index).
      log.debug("Heartbeat of PROCESSING jobs failed (best-effort): {}", e.getMessage());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public long queueDepth() {
    lock.lock();
    try {
      ensureOpen();

      String sql = """
          SELECT COUNT(*) FROM jobs
          WHERE state IN ('PENDING', 'PROCESSING')
          """;

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      log.error("Failed to get queue depth", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public long completedCount() {
    lock.lock();
    try {
      ensureOpen();

      String sql = "SELECT COUNT(*) FROM jobs WHERE state = 'DONE'";

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0;
      }
    } catch (SQLException e) {
      log.error("Failed to get completed count", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Removes old DONE/FAILED queue rows. The {@code ingestion_ledger} table is NOT touched here
   * — the audit trail outlives queue rows, and ledger pruning is handled by the dedicated
   * {@link #cleanupOldLedgerEvents(int)}.
   */
  @Override
  public int cleanupOldJobs(int retentionDays) {
    lock.lock();
    try {
      ensureOpen();

      long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

      String jobsSql = """
          DELETE FROM jobs
          WHERE state IN ('DONE', 'FAILED') AND last_updated < ?
          """;

      int deleted;
      try (PreparedStatement stmt = connection.prepareStatement(jobsSql)) {
        stmt.setLong(1, cutoff);
        deleted = stmt.executeUpdate();
      }
      if (deleted > 0) {
        log.info("Cleaned up {} old completed/failed jobs", deleted);
        checkAndVacuum();
      }
      return deleted;
    } catch (SQLException e) {
      log.error("Failed to cleanup old jobs", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int cleanupOldLedgerEvents(int retentionDays) {
    lock.lock();
    try {
      ensureOpen();
      long cutoff = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
      try (PreparedStatement stmt =
          connection.prepareStatement("DELETE FROM ingestion_ledger WHERE observed_at < ?")) {
        stmt.setLong(1, cutoff);
        int deleted = stmt.executeUpdate();
        if (deleted > 0) {
          log.info("Cleaned up {} old ingestion ledger events", deleted);
          checkAndVacuum();
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to cleanup old ledger events", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean hasRecentLedgerEvent(String pathHash, String reasonCode, long sinceMs) {
    if (pathHash == null || reasonCode == null) return false;
    lock.lock();
    try {
      ensureOpen();
      String sql =
          """
          SELECT 1 FROM ingestion_ledger
          WHERE path_hash = ? AND reason_code = ? AND observed_at >= ?
          LIMIT 1
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, pathHash);
        stmt.setString(2, reasonCode);
        stmt.setLong(3, Math.max(0L, sinceMs));
        try (ResultSet rs = stmt.executeQuery()) {
          return rs.next();
        }
      }
    } catch (SQLException e) {
      log.debug("hasRecentLedgerEvent probe failed (treating as 'no recent event'): {}", e.getMessage());
      return false;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<JobQueue.IngestionEventView> recentIngestionEvents(int limit) {
    lock.lock();
    try {
      ensureOpen();
      int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
      String sql = """
          SELECT id, path_hash, collection, outcome_class, reason_code, retry_policy,
                 diagnostic_summary, observed_at, source_size_bytes, source_modified_at,
                 source_kind, artifact_status, policy_id, parser_id
          FROM ingestion_ledger
          ORDER BY observed_at DESC, id DESC
          LIMIT ?
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setInt(1, cappedLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          List<JobQueue.IngestionEventView> events = new ArrayList<>();
          while (rs.next()) {
            events.add(readIngestionEventView(rs));
          }
          return List.copyOf(events);
        }
      }
    } catch (SQLException e) {
      log.error("Failed to list ingestion ledger events", e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<JobQueue.IngestionOutcomeSummary> ingestionOutcomeSummary(long sinceMs) {
    lock.lock();
    try {
      ensureOpen();
      String sql = """
          SELECT outcome_class, reason_code, retry_policy, COUNT(*) AS event_count,
                 MAX(observed_at) AS last_observed_at
          FROM ingestion_ledger
          WHERE observed_at >= ?
          GROUP BY outcome_class, reason_code, retry_policy
          ORDER BY last_observed_at DESC
          """;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setLong(1, Math.max(0L, sinceMs));
        try (ResultSet rs = stmt.executeQuery()) {
          List<JobQueue.IngestionOutcomeSummary> summaries = new ArrayList<>();
          while (rs.next()) {
            summaries.add(
                new JobQueue.IngestionOutcomeSummary(
                    rs.getString("outcome_class"),
                    rs.getString("reason_code"),
                    rs.getString("retry_policy"),
                    rs.getLong("event_count"),
                    rs.getLong("last_observed_at")));
          }
          return List.copyOf(summaries);
        }
      }
    } catch (SQLException e) {
      log.error("Failed to summarize ingestion ledger outcomes", e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  private static JobQueue.IngestionEventView readIngestionEventView(ResultSet rs)
      throws SQLException {
    return new JobQueue.IngestionEventView(
        rs.getLong("id"),
        rs.getString("path_hash"),
        rs.getString("collection"),
        rs.getString("outcome_class"),
        rs.getString("reason_code"),
        rs.getString("retry_policy"),
        rs.getString("diagnostic_summary"),
        rs.getLong("observed_at"),
        nullableLong(rs, "source_size_bytes"),
        nullableLong(rs, "source_modified_at"),
        rs.getString("source_kind"),
        rs.getString("artifact_status"),
        rs.getString("policy_id"),
        rs.getString("parser_id"));
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static final int VACUUM_WASTE_THRESHOLD_PERCENT = 25;
  private static final int VACUUM_PAGES = 500;

  /**
   * Checks the database waste ratio and runs incremental vacuum if above threshold.
   *
   * <p>Requires {@code PRAGMA auto_vacuum = 2} (INCREMENTAL) to be effective. On databases
   * created with {@code auto_vacuum = 0}, this is a safe no-op.
   */
  void checkAndVacuum() {
    try (Statement stmt = connection.createStatement()) {
      try (ResultSet rs =
          stmt.executeQuery(
              "SELECT page_count, freelist_count, page_size FROM pragma_page_count(), "
                  + "pragma_freelist_count(), pragma_page_size()")) {
        if (rs.next()) {
          long pageCount = rs.getLong("page_count");
          long freelistCount = rs.getLong("freelist_count");
          long pageSize = rs.getLong("page_size");
          if (pageCount > 0) {
            long wastePercent = (freelistCount * 100) / pageCount;
            if (wastePercent > VACUUM_WASTE_THRESHOLD_PERCENT) {
              stmt.execute("PRAGMA incremental_vacuum(" + VACUUM_PAGES + ")");
              long reclaimedBytes = Math.min(freelistCount, VACUUM_PAGES) * pageSize;
              log.info(
                  "Incremental vacuum: reclaimed ~{} KB (waste was {}%)",
                  reclaimedBytes / 1024,
                  wastePercent);
            }
          }
        }
      }
    } catch (SQLException e) {
      log.warn("Incremental vacuum check failed", e);
    }
  }

  @Override
  public FailureSummary failureSummary() {
    lock.lock();
    try {
      ensureOpen();

      long failedCount = 0L;
      String lastFailedPath = null;
      String lastFailedError = null;
      Long lastFailedAtMs = null;
      Long nextRetryAtMs = null;

      // 1) Failed count
      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM jobs WHERE state = 'FAILED'")) {
        if (rs.next()) {
          failedCount = rs.getLong(1);
        }
      }

      // 2) Last failed job (most recent last_updated)
      String lastFailedSql = """
          SELECT path, error_message, last_updated
          FROM jobs
          WHERE state = 'FAILED'
          ORDER BY last_updated DESC
          LIMIT 1
          """;
      try (PreparedStatement stmt = connection.prepareStatement(lastFailedSql);
           ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          lastFailedPath = rs.getString(1);
          lastFailedError = rs.getString(2);
          long ts = rs.getLong(3);
          // last_updated is NOT NULL in schema; treat 0 as unknown defensively
          lastFailedAtMs = ts > 0 ? ts : null;
        }
      }

      // 3) Earliest retry_after for PENDING jobs (best-effort)
      // Note: PROCESSING jobs have retry_after null; we focus on PENDING backoff.
      String nextRetrySql = """
          SELECT MIN(retry_after)
          FROM jobs
          WHERE state = 'PENDING' AND retry_after IS NOT NULL
          """;
      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery(nextRetrySql)) {
        if (rs.next()) {
          long v = rs.getLong(1);
          if (!rs.wasNull() && v > 0) {
            nextRetryAtMs = v;
          }
        }
      }

      return new FailureSummary(failedCount, lastFailedPath, lastFailedError, lastFailedAtMs, nextRetryAtMs);

    } catch (SQLException e) {
      recordDbError();
      log.debug("Failed to compute failure summary (best-effort): {}", e.getMessage());
      return new FailureSummary(0L, null, null, null, null);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<FailedJobInfo> listFailedJobs(int limit) {
    lock.lock();
    try {
      ensureOpen();

      int effectiveLimit = limit > 0 ? Math.min(limit, 1000) : 100;

      String sql = """
          SELECT path, error_message, attempts, last_updated, collection
          FROM jobs WHERE state = 'FAILED'
          ORDER BY last_updated DESC
          LIMIT ?
          """;

      List<FailedJobInfo> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setInt(1, effectiveLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new FailedJobInfo(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getLong(4),
                rs.getString(5)));
          }
        }
      }
      return result;

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to list failed jobs", e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Lists FAILED jobs whose path is under the given prefix (tempdoc 599 §16/B1). Combines the
   * {@link #listFailedJobs(int)} projection with the half-open range predicate from
   * {@link #countByPathPrefix(String)} — same normalization + PK-index-friendly, wildcard-safe
   * bounds. Backs the per-folder "failed files" drill-down. Returns empty on error or empty prefix.
   */
  @Override
  public List<FailedJobInfo> listFailedJobsByPathPrefix(String pathPrefix, int limit) {
    lock.lock();
    try {
      ensureOpen();

      String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (lower == null || lower.isBlank()) {
        log.warn("listFailedJobsByPathPrefix called with empty prefix, refusing");
        return List.of();
      }
      String upper =
          lower.substring(0, lower.length() - 1) + (char) (lower.charAt(lower.length() - 1) + 1);
      int effectiveLimit = limit > 0 ? Math.min(limit, 1000) : 100;

      String sql = """
          SELECT path, error_message, attempts, last_updated, collection
          FROM jobs WHERE state = 'FAILED' AND path >= ? AND path < ?
          ORDER BY last_updated DESC
          LIMIT ?
          """;

      List<FailedJobInfo> result = new ArrayList<>();
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, lower);
        stmt.setString(2, upper);
        stmt.setInt(3, effectiveLimit);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            result.add(new FailedJobInfo(
                rs.getString(1),
                rs.getString(2),
                rs.getInt(3),
                rs.getLong(4),
                rs.getString(5)));
          }
        }
      }
      return result;
    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to list failed jobs by path prefix: {}", pathPrefix, e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int clearFailedJobs() {
    lock.lock();
    try {
      ensureOpen();

      String sql = "DELETE FROM jobs WHERE state = 'FAILED'";
      try (Statement stmt = connection.createStatement()) {
        int deleted = stmt.executeUpdate(sql);
        if (deleted > 0) {
          log.info("Cleared {} failed jobs", deleted);
          checkAndVacuum();
        }
        return deleted;
      }

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to clear failed jobs", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int clearAll() {
    lock.lock();
    try {
      ensureOpen();

      String sql = "DELETE FROM jobs";
      try (Statement stmt = connection.createStatement()) {
        int deleted = stmt.executeUpdate(sql);
        if (deleted > 0) {
          log.info("Cleared all {} jobs (profiling reset)", deleted);
          checkAndVacuum();
        }
        return deleted;
      }

    } catch (SQLException e) {
      recordDbError();
      log.error("Failed to clear all jobs", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes all jobs whose path starts with the given prefix.
   * Deletes ALL states (PENDING, PROCESSING, DONE, FAILED).
   *
   * @param pathPrefix the path prefix (will be normalized)
   * @return number of jobs deleted, or -1 on error
   */
  @Override
  public int deleteByPathPrefix(String pathPrefix) {
    lock.lock();
    try {
      ensureOpen();

      String normalized = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (normalized == null || normalized.isBlank()) {
        log.warn("deleteByPathPrefix called with empty prefix, refusing");
        return 0;
      }

      String sql = "DELETE FROM jobs WHERE path LIKE ? || '%'";

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, normalized);
        int deleted = stmt.executeUpdate();
        if (deleted > 0) {
          log.info("deleteByPathPrefix: deleted {} jobs for prefix: {}", deleted, normalized);
        } else {
          log.debug("deleteByPathPrefix: no jobs found for prefix: {}", normalized);
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to delete jobs by path prefix: {}", pathPrefix, e);
      return -1;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Counts jobs whose path starts with the given prefix, broken down by sub-state (tempdoc 599
   * §9.2). Uses a half-open RANGE predicate ({@code path >= lower AND path < upper}) rather than
   * {@code LIKE} (tempdoc 599 Fix 2): it (a) uses the {@code path} PRIMARY KEY index — a case-
   * insensitive {@code LIKE} would force a full table scan, and this is called per-folder on every
   * live-status tick — and (b) avoids {@code LIKE} treating {@code _}/{@code %} in a real path as
   * wildcards (e.g. {@code F:\a_b} must not count {@code F:\aXb}). Correctness rests on stored job
   * paths and the prefix sharing the SAME {@link PathNormalizer} normalization (Windows-lowercased,
   * separators normalized) over the BINARY-collation PK. The lower bound ends with a separator (so
   * {@code F:\docs} does not match {@code F:\docs-other}); the upper bound increments its last char.
   * Returns all-zero on error or empty prefix.
   *
   * @param pathPrefix the path prefix (will be normalized)
   * @return per-state counts under the prefix
   */
  @Override
  public JobQueue.JobStateCounts countByPathPrefix(String pathPrefix) {
    lock.lock();
    try {
      ensureOpen();

      String lower = PathNormalizer.normalizePathPrefix(pathPrefix);
      if (lower == null || lower.isBlank()) {
        log.warn("countByPathPrefix called with empty prefix, refusing");
        return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
      }
      // Half-open upper bound: same string with the final char incremented. normalizePathPrefix
      // guarantees a trailing separator, so the last char is well-defined and not a high sentinel.
      String upper =
          lower.substring(0, lower.length() - 1) + (char) (lower.charAt(lower.length() - 1) + 1);

      String sql = "SELECT state, COUNT(*) FROM jobs WHERE path >= ? AND path < ? GROUP BY state";

      long pending = 0L;
      long processing = 0L;
      long done = 0L;
      long failed = 0L;
      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, lower);
        stmt.setString(2, upper);
        try (ResultSet rs = stmt.executeQuery()) {
          while (rs.next()) {
            String state = rs.getString(1);
            long count = rs.getLong(2);
            switch (state == null ? "" : state) {
              case "PENDING" -> pending = count;
              case "PROCESSING" -> processing = count;
              case "DONE" -> done = count;
              case "FAILED" -> failed = count;
              default -> log.debug("countByPathPrefix: unknown state {} ({} rows)", state, count);
            }
          }
        }
      }
      // pendingReadyCount: the queue's backoff distinction is not needed for the folder projection
      // (in-flight = pending + processing); report pendingReadyCount == pendingCount.
      return new JobQueue.JobStateCounts(pending, pending, processing, done, failed);
    } catch (SQLException e) {
      log.error("Failed to count jobs by path prefix: {}", pathPrefix, e);
      return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes a single job by exact path match.
   * Used by file watcher for single file deletions.
   *
   * @param path the exact path (must already be normalized)
   * @return number of jobs deleted (0 or 1), or -1 on error
   */
  @Override
  public int deleteByExactPath(String path) {
    lock.lock();
    try {
      ensureOpen();

      if (path == null || path.isBlank()) {
        log.warn("deleteByExactPath called with empty path, refusing");
        return 0;
      }

      String sql = "DELETE FROM jobs WHERE path = ?";

      try (PreparedStatement stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, path);
        int deleted = stmt.executeUpdate();
        if (deleted > 0) {
          log.debug("deleteByExactPath: deleted job for path: {}", path);
        }
        return deleted;
      }
    } catch (SQLException e) {
      log.error("Failed to delete job by exact path: {}", path, e);
      return -1;
    } finally {
      lock.unlock();
    }
  }

  private void ensureOpen() {
    if (connection == null) {
      throw new IllegalStateException("SqliteJobQueue is not open");
    }
  }

  /**
   * Returns true if the filesystem hosting the queue DB has at least {@link #MIN_FREE_DISK_BYTES}
   * of free space. On any IO error during the probe (e.g., file store unavailable) returns true
   * (fail-open) — the disk-space probe must never be the reason a write fails. The actual write
   * still surfaces SQLITE_IOERR_FULL if the probe was wrong.
   */
  private boolean hasSufficientDiskSpace() {
    try {
      Path probePath = dbPath.toAbsolutePath();
      Path parent = probePath.getParent();
      Path target = (parent != null && Files.isDirectory(parent)) ? parent : probePath;
      long usable = Files.getFileStore(target).getUsableSpace();
      return usable >= MIN_FREE_DISK_BYTES;
    } catch (IOException e) {
      log.debug("Disk-space probe failed; allowing enqueue (fail-open): {}", e.getMessage());
      return true;
    }
  }

  // ==================== Backup ====================

  /**
   * Creates a backup of the jobs.db database using VACUUM INTO.
   *
   * <p>This creates a consistent snapshot of the database, even in WAL mode,
   * by using SQLite's VACUUM INTO command which produces a standalone copy.
   *
   * <p>The backup is written to a temp file first, then atomically moved to
   * {@code jobs.db.bak} to ensure we never have a partial backup file.
   *
   * <p><b>Note:</b> This method only creates a backup if the database existed
   * before {@link #open()} was called. Fresh databases don't need backup.
   *
   * @throws SQLException if the VACUUM INTO command fails
   * @throws IOException if the atomic move fails
   */
  public void performBackup() throws SQLException, IOException {
    if (!existedBeforeOpen) {
      log.debug("Skipping backup: database did not exist before open");
      return;
    }

    lock.lock();
    try {
      ensureOpen();

      Path tmp = dbPath.resolveSibling(dbPath.getFileName() + ".bak.tmp");
      Path bak = dbPath.resolveSibling(dbPath.getFileName() + ".bak");

      // Escape single quotes in path for SQL literal
      String escapedPath = tmp.toAbsolutePath().toString().replace("'", "''");

      log.info("Creating backup: {} -> {}", dbPath, bak);

      try (Statement stmt = connection.createStatement()) {
        stmt.execute("VACUUM INTO '" + escapedPath + "'");
      }

      // Atomic move to final location
      Files.move(tmp, bak, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

      lastBackupAtMs = System.currentTimeMillis();
      log.info("Backup created successfully: {}", bak);
    } finally {
      lock.unlock();
    }
  }

  // ==================== Integrity Check ====================

  /**
   * Performs a quick integrity check on the database using PRAGMA quick_check.
   *
   * <p>This method should be called after opening an existing database to detect
   * corruption early. Fresh databases (created during this session) are skipped
   * since they cannot be corrupt.
   *
   * <p>PRAGMA quick_check returns multiple rows; the database is considered healthy
   * only if ALL rows return exactly "ok". Any other result indicates corruption.
   *
   * @return true if the database passes the integrity check
   * @throws SQLException with error code 11 (SQLITE_CORRUPT) if the check fails
   */
  public boolean performIntegrityCheck() throws SQLException {
    if (!existedBeforeOpen && !forceIntegrityCheck) {
      log.debug("Skipping integrity check: database did not exist before open");
      // Record as passed for fresh databases
      lastQuickCheckAtMs = System.currentTimeMillis();
      lastQuickCheckOk = true;
      return true;
    }

    lock.lock();
    try {
      ensureOpen();
      log.info("Running integrity check on jobs.db...");

      try (Statement stmt = connection.createStatement();
           ResultSet rs = stmt.executeQuery("PRAGMA quick_check")) {
        StringBuilder errors = new StringBuilder();
        int rowCount = 0;

        while (rs.next()) {
          String result = rs.getString(1);
          rowCount++;
          if (!"ok".equalsIgnoreCase(result)) {
            if (errors.length() > 0) {
              errors.append("; ");
            }
            errors.append(result);
          }
        }

        lastQuickCheckAtMs = System.currentTimeMillis();

        if (errors.length() > 0) {
          lastQuickCheckOk = false; // NOPMD - read by health check accessor
          dbHealthy = false;
          String errorMsg = "Database integrity check failed: " + errors;
          log.error(errorMsg);
          // Use SQLite error code 11 for SQLITE_CORRUPT
          throw new SQLException(errorMsg, "SQLITE_CORRUPT", 11);
        }

        lastQuickCheckOk = true;
        log.info("Integrity check passed ({} rows checked)", rowCount);
        return true;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      if (changeStream != null) {
        try {
          changeStream.close();
        } catch (RuntimeException e) {
          log.warn("Failed to close IndexingJobsChangeStream cleanly", e);
        } finally {
          changeStream = null;
        }
      }
      if (connection != null) {
        try {
          connection.close();
          log.info("SqliteJobQueue closed");
        } catch (SQLException e) {
          throw new IOException("Failed to close SqliteJobQueue", e);
        } finally {
          connection = null;
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Slice 445: accessor for the change-stream so the gRPC layer
   * ({@code GrpcIngestService.subscribeIndexingJobs}) can subscribe to
   * snapshot+delta frames. Returns {@code null} if the queue is closed or
   * not yet opened.
   */
  public IndexingJobsChangeStream changeStream() {
    return changeStream;
  }

  @Override
  public java.util.Optional<IndexingJobChangeFeed> indexingJobChangeFeed() {
    IndexingJobsChangeStream cs = changeStream;
    return cs == null ? java.util.Optional.empty() : java.util.Optional.of(cs);
  }

  // ==================== Health Observability ====================

  /**
   * Records a database error for health tracking.
   *
   * <p>Called from best-effort status helpers that swallow SQL exceptions.
   * This allows observability surfaces to detect when the queue DB is unhealthy
   * without requiring callers to handle exceptions.
   */
  private void recordDbError() {
    dbHealthy = false;
    lastDbErrorAtMs = System.currentTimeMillis();
  }

  /**
   * Returns a snapshot of the queue database health for observability surfaces.
   *
   * <p>This includes:
   * <ul>
   *   <li>Whether the DB is currently considered healthy</li>
   *   <li>When the last integrity check was run and whether it passed</li>
   *   <li>When the last backup was created</li>
   *   <li>When the last SQL error occurred in a status helper</li>
   * </ul>
   *
   * @return a snapshot of queue DB health
   */
  @Override
  public JobQueue.QueueDbHealthSnapshot queueDbHealthSnapshot() {
    return new JobQueue.QueueDbHealthSnapshot(
        dbHealthy,
        lastQuickCheckAtMs,
        lastQuickCheckOk,
        lastBackupAtMs,
        lastDbErrorAtMs);
  }
}
