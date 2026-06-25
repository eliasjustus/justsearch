/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.telemetry.Telemetry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switch buffer and state-count operations for the SQLite job queue.
 *
 * <p>Manages the durable switch buffer used during index migration (SWITCHING state) and provides
 * aggregate job-state queries for observability. Each method acquires the shared lock and obtains
 * the connection via the supplier, matching the locking pattern of the parent facade.
 */
final class SqliteQueueSwitchBufferOps {
  private static final Logger log = LoggerFactory.getLogger(SqliteQueueSwitchBufferOps.class);

  private final ReentrantLock lock;
  private final Supplier<Connection> connSupplier;
  private final Runnable onWriteFailure;
  private final Runnable errorRecorder;

  /**
   * @param onWriteFailure invoked once per switch-buffer write failure; may be null. Tempdoc 417
   *     Phase 3c: replaces the legacy {@link Telemetry.Counter} so this layer stays decoupled
   *     from the {@code Telemetry} interface (which is being retired).
   */
  SqliteQueueSwitchBufferOps(
      ReentrantLock lock,
      Supplier<Connection> connSupplier,
      Runnable onWriteFailure,
      Runnable errorRecorder) {
    this.lock = lock;
    this.connSupplier = connSupplier;
    this.onWriteFailure = onWriteFailure;
    this.errorRecorder = errorRecorder;
  }

  /** Returns the number of buffered ops currently in the durable switch buffer (best-effort). */
  long depth() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM switch_buffer")) {
        if (rs.next()) {
          return rs.getLong(1);
        }
        return 0L;
      }
    } catch (SQLException e) {
      errorRecorder.run();
      log.debug("Failed to get switch buffer depth (best-effort): {}", e.getMessage());
      return 0L;
    } finally {
      lock.unlock();
    }
  }

  /** Returns best-effort counts for PENDING/PROCESSING/DONE/FAILED and PENDING runnable subset. */
  JobQueue.JobStateCounts stateCounts() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      long now = System.currentTimeMillis();
      String sql =
          """
          SELECT
            COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
            COALESCE(SUM(CASE WHEN state = 'PENDING' AND (retry_after IS NULL OR retry_after <= ?) THEN 1 ELSE 0 END), 0) AS pending_ready_count,
            COALESCE(SUM(CASE WHEN state = 'PROCESSING' THEN 1 ELSE 0 END), 0) AS processing_count,
            COALESCE(SUM(CASE WHEN state = 'DONE' THEN 1 ELSE 0 END), 0) AS done_count,
            COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count
          FROM jobs
          """;
      try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setLong(1, now);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            return new JobQueue.JobStateCounts(
                rs.getLong("pending_count"),
                rs.getLong("pending_ready_count"),
                rs.getLong("processing_count"),
                rs.getLong("done_count"),
                rs.getLong("failed_count"));
          }
          return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
        }
      }
    } catch (Exception e) {
      errorRecorder.run();
      log.debug("Failed to get job state counts (best-effort): {}", e.getMessage());
      return new JobQueue.JobStateCounts(0L, 0L, 0L, 0L, 0L);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Inserts or replaces an operation in the durable switch buffer.
   *
   * <p><b>IMPORTANT:</b> This method is fail-closed. If the write fails, it returns {@code false}
   * and the caller MUST NOT acknowledge the operation as successful.
   */
  boolean put(String key, String op, String payload) {
    if (key == null || key.isBlank() || op == null || op.isBlank() || payload == null) {
      log.warn("putSwitchBuffer called with invalid arguments: key={}, op={}", key, op);
      return false;
    }
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      String sql =
          """
          INSERT OR REPLACE INTO switch_buffer (key, op, payload, last_updated)
          VALUES (?, ?, ?, ?)
          """;
      try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, key);
        stmt.setString(2, op);
        stmt.setString(3, payload);
        stmt.setLong(4, System.currentTimeMillis());
        stmt.executeUpdate();
        return true;
      }
    } catch (SQLException e) {
      if (onWriteFailure != null) {
        onWriteFailure.run();
      }
      log.error(
          "CRITICAL: Failed to write switch buffer op key={} op={} - caller must NOT ACK",
          key,
          op,
          e);
      return false;
    } finally {
      lock.unlock();
    }
  }

  /** Returns all buffered ops, sorted by last_updated ascending (best-effort). */
  List<SwitchBufferCapableQueue.SwitchBufferOp> listAll() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      String sql =
          """
          SELECT key, op, payload, last_updated
          FROM switch_buffer
          ORDER BY last_updated ASC
          """;
      List<SwitchBufferCapableQueue.SwitchBufferOp> out = new ArrayList<>();
      try (Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery(sql)) {
        while (rs.next()) {
          out.add(
              new SwitchBufferCapableQueue.SwitchBufferOp(
                  rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
        }
      }
      return out;
    } catch (SQLException e) {
      errorRecorder.run();
      log.error("Failed to read switch buffer ops", e);
      return List.of();
    } finally {
      lock.unlock();
    }
  }

  /** Clears all buffered ops. */
  int clear() {
    lock.lock();
    try {
      Connection conn = connSupplier.get();
      try (Statement stmt = conn.createStatement()) {
        return stmt.executeUpdate("DELETE FROM switch_buffer");
      }
    } catch (SQLException e) {
      errorRecorder.run();
      log.error("Failed to clear switch buffer ops", e);
      return 0;
    } finally {
      lock.unlock();
    }
  }
}
