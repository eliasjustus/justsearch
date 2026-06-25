/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteCommitListener;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteUpdateListener;

/**
 * Slice 445 producer scaffolding: captures per-row mutations on the {@code jobs}
 * SQLite table via the Xerial driver's {@code addUpdateListener} +
 * {@code addCommitListener} hooks, materializes them into typed {@link Delta}
 * events, and broadcasts to subscribers.
 *
 * <p>Verified API surface (checkpoint commit {@code 044b21ab3}):
 * {@link SQLiteConnection#addUpdateListener(SQLiteUpdateListener)} fires per-row
 * INSIDE each transaction; {@link SQLiteConnection#addCommitListener(SQLiteCommitListener)}
 * fires once on commit (flush) or rollback (drop). This separation gives us
 * transactional consistency: deltas are buffered during the transaction and
 * emitted only on commit.
 *
 * <p>Privacy contract: the wire {@link JobRow} carries {@code pathHash} (SHA-256
 * hex of the absolute normalized path), never raw paths. Matches ADR-0028 +
 * {@code LibraryResolveHashOnlyCallerPin}: paths leave the worker boundary only
 * via {@code POST /api/library/resolve-hash}; the substrate path is
 * {@code core.resolve-path-hash} Operation in slice 445 §A.9.
 *
 * <p>Snapshot↔delta consistency: {@link #subscribeWithSnapshot} captures a
 * sequence-tagged snapshot + adds the subscriber atomically under the
 * change-stream's monitor. New commits that fire between this method's return
 * and the subscriber's first delta are guaranteed to have {@code seq >
 * snapshotSeq}; the FE consumer rebuilds its keyed map from the snapshot then
 * applies deltas.
 *
 * <p>Per the verification analysis (checkpoint {@code 044b21ab3}):
 * {@link SqliteJobQueue} uses a single {@link Connection} field, so attaching
 * one update_hook captures every mutation across the queue's API. No
 * per-call-site instrumentation needed.
 *
 * <p>Scope (per slice 445 §A.3 D2): concrete to the {@code jobs} table for V1.
 * Future TABULAR Resources backed by SQLite tables can extract this into a
 * generic {@code SqliteChangeStream<V>} primitive when motivated by a second
 * instance.
 */
public final class IndexingJobsChangeStream implements IndexingJobChangeFeed, Closeable {

  private static final Logger log = LoggerFactory.getLogger(IndexingJobsChangeStream.class);
  private static final String JOBS_TABLE = "jobs";

  // Delta, JobRow, Subscription, SnapshotAndSubscription are inherited from
  // IndexingJobChangeFeed (worker-core). The shared types let worker-services
  // depend only on the feed abstraction without dragging in indexer-worker
  // (and Xerial SQLite specifics).

  private final Connection conn;
  private final SQLiteConnection sqliteConn;

  /** rowId → pathHash mapping; required for DELETE notifications (the row is gone post-commit). */
  private final ConcurrentHashMap<Long, String> rowIdToPathHash = new ConcurrentHashMap<>();

  /** Pending changes captured during the current transaction; flushed on commit, dropped on rollback. */
  private final List<PendingChange> pending = new ArrayList<>();
  private final Object pendingLock = new Object();

  /** Subscribers; copy-on-write to allow safe iteration during emit. */
  private final List<Consumer<Delta>> subscribers = new CopyOnWriteArrayList<>();

  /**
   * Monotonic sequence counter. Bumps on every emitted delta. Used by FE consumers
   * to detect stale snapshots / reconcile resume tokens.
   */
  private final AtomicLong seq = new AtomicLong(0);

  /** Owns the SQLiteCommitListener registration so close() can detach it. */
  private final SQLiteCommitListener commitListener;
  private final SQLiteUpdateListener updateListener;
  private volatile boolean closed = false;

  private record PendingChange(SQLiteUpdateListener.Type type, long rowId) {}

  /**
   * Attaches update + commit hooks to {@code conn}. Eagerly populates the
   * rowId→pathHash cache from the current jobs table so DELETE notifications
   * carry the right primary key.
   *
   * <p>The connection MUST be the same connection that mutates the jobs table.
   * Per {@link SqliteJobQueue} architecture (single-connection model verified
   * checkpoint {@code 044b21ab3}), attaching once captures every mutation.
   */
  public IndexingJobsChangeStream(Connection conn) throws SQLException {
    this.conn = Objects.requireNonNull(conn, "conn");
    this.sqliteConn = conn.unwrap(SQLiteConnection.class);
    populateRowIdCache();
    this.updateListener = this::onRowChange;
    this.commitListener =
        new SQLiteCommitListener() {
          @Override
          public void onCommit() {
            flushPending();
          }

          @Override
          public void onRollback() {
            discardPending();
          }
        };
    sqliteConn.addUpdateListener(updateListener);
    sqliteConn.addCommitListener(commitListener);
  }

  /** Current monotonic seq. 0 before any deltas. */
  @Override
  public long currentSeq() {
    return seq.get();
  }

  @Override
  public synchronized SnapshotAndSubscription subscribeWithSnapshot(Consumer<Delta> subscriber)
      throws SQLException {
    Objects.requireNonNull(subscriber, "subscriber");
    long snapshotSeq = seq.get();
    List<JobRow> rows = readAllRows();
    subscribers.add(subscriber);
    Subscription sub =
        () -> subscribers.remove(subscriber);
    return new SnapshotAndSubscription(snapshotSeq, rows, sub);
  }

  @Override
  public Subscription subscribe(Consumer<Delta> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber");
    subscribers.add(subscriber);
    return () -> subscribers.remove(subscriber);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      sqliteConn.removeUpdateListener(updateListener);
    } catch (RuntimeException e) {
      log.warn("removeUpdateListener threw on close", e);
    }
    try {
      sqliteConn.removeCommitListener(commitListener);
    } catch (RuntimeException e) {
      log.warn("removeCommitListener threw on close", e);
    }
    subscribers.clear();
    rowIdToPathHash.clear();
    synchronized (pendingLock) {
      pending.clear();
    }
  }

  // ---- internal ----

  private void populateRowIdCache() throws SQLException {
    rowIdToPathHash.clear();
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT rowid, path FROM jobs")) {
      while (rs.next()) {
        long rowId = rs.getLong(1);
        String path = rs.getString(2);
        if (path != null) {
          rowIdToPathHash.put(rowId, sha256(path));
        }
      }
    }
  }

  private List<JobRow> readAllRows() throws SQLException {
    List<JobRow> result = new ArrayList<>();
    String sql =
        "SELECT path, state, attempts, last_updated, error_message, retry_after, collection "
            + "FROM jobs";
    try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
      while (rs.next()) {
        result.add(rowFromResultSet(rs));
      }
    }
    return result;
  }

  private static JobRow rowFromResultSet(ResultSet rs) throws SQLException {
    String path = rs.getString(1);
    String state = rs.getString(2);
    int attempts = rs.getInt(3);
    long lastUpdated = rs.getLong(4);
    String errorMessage = rs.getString(5);
    long retryAfter = rs.getLong(6);
    if (rs.wasNull()) retryAfter = 0L;
    String collection = rs.getString(7);
    if (collection == null) collection = "default";
    return new JobRow(
        sha256(path),
        state == null ? "PENDING" : state,
        attempts,
        lastUpdated,
        errorMessage,
        retryAfter,
        collection);
  }

  // dbName is unused but required by the SQLiteUpdateListener SAM signature (method-ref binding).
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private void onRowChange(
      SQLiteUpdateListener.Type type, String dbName, String tableName, long rowId) {
    if (!JOBS_TABLE.equals(tableName)) return;
    if (closed) return;
    synchronized (pendingLock) {
      pending.add(new PendingChange(type, rowId));
    }
  }

  private void flushPending() {
    if (closed) return;
    List<PendingChange> snapshot;
    synchronized (pendingLock) {
      if (pending.isEmpty()) return;
      snapshot = List.copyOf(pending);
      pending.clear();
    }
    for (PendingChange change : snapshot) {
      Delta delta = materialize(change);
      if (delta != null) {
        seq.incrementAndGet();
        for (Consumer<Delta> sub : subscribers) {
          try {
            sub.accept(delta);
          } catch (RuntimeException e) {
            log.warn("Subscriber threw on delta delivery; continuing", e);
          }
        }
      }
    }
  }

  private void discardPending() {
    synchronized (pendingLock) {
      pending.clear();
    }
  }

  private Delta materialize(PendingChange change) {
    long rowId = change.rowId;
    return switch (change.type) {
      case INSERT -> {
        JobRow row = readRowByRowId(rowId);
        if (row == null) yield null;
        rowIdToPathHash.put(rowId, row.pathHash());
        yield new Delta.Insert(row);
      }
      case UPDATE -> {
        JobRow row = readRowByRowId(rowId);
        if (row == null) yield null;
        rowIdToPathHash.put(rowId, row.pathHash());
        yield new Delta.Update(row);
      }
      case DELETE -> {
        String pathHash = rowIdToPathHash.remove(rowId);
        if (pathHash == null) yield null;
        yield new Delta.Delete(pathHash);
      }
    };
  }

  private JobRow readRowByRowId(long rowId) {
    String sql =
        "SELECT path, state, attempts, last_updated, error_message, retry_after, collection "
            + "FROM jobs WHERE rowid = ?";
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setLong(1, rowId);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          return rowFromResultSet(rs);
        }
      }
    } catch (SQLException e) {
      log.warn("readRowByRowId({}) failed: {}", rowId, e.getMessage());
    }
    return null;
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
