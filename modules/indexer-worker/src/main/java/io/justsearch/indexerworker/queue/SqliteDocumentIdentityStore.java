/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** SQLite document-identity authority stored in the existing {@code jobs.db}. */
public final class SqliteDocumentIdentityStore implements DocumentIdentityStore, Closeable {
  private static final int BUSY_TIMEOUT_MS = 5000;

  private final Path dbPath;
  private final ReentrantLock lock = new ReentrantLock();
  private Connection connection;

  public SqliteDocumentIdentityStore(Path dbPath) {
    this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
    open();
  }

  private void open() {
    String url = "jdbc:sqlite:" + dbPath.toString().replace('\\', '/');
    try {
      Connection conn = DriverManager.getConnection(url);
      try (var stmt = conn.createStatement()) {
        stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
      }
      this.connection = conn;
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to open DocumentIdentityStore at " + dbPath, e);
    }
  }

  @Override
  public Identity resolve(String pathHash, long nowMs) {
    requireHash(pathHash);
    lock.lock();
    try {
      String candidate = UUID.randomUUID().toString();
      try (PreparedStatement stmt =
          connection.prepareStatement(
              """
              INSERT INTO document_identity (path_hash, doc_uid, first_seen_at, last_seen_at)
              VALUES (?, ?, ?, ?)
              ON CONFLICT(path_hash) DO UPDATE SET last_seen_at = excluded.last_seen_at
              """)) {
        stmt.setString(1, pathHash);
        stmt.setString(2, candidate);
        stmt.setLong(3, nowMs);
        stmt.setLong(4, nowMs);
        stmt.executeUpdate();
      }
      return selectRequired(pathHash);
    } catch (SQLException e) {
      throw failure("resolve", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Identity importExisting(String pathHash, String docUid, long nowMs) {
    requireHash(pathHash);
    requireUid(docUid);
    lock.lock();
    try {
      return importExistingLocked(pathHash, docUid, nowMs);
    } catch (SQLException e) {
      throw failure("importExisting", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void importExisting(Collection<ImportedIdentity> identities, long nowMs) {
    Objects.requireNonNull(identities, "identities");
    lock.lock();
    try {
      boolean wasAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        for (ImportedIdentity identity : identities) {
          Objects.requireNonNull(identity, "identity");
          requireHash(identity.pathHash());
          requireUid(identity.docUid());
          importExistingLocked(identity.pathHash(), identity.docUid(), nowMs);
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(wasAutoCommit);
      }
    } catch (SQLException e) {
      throw failure("importExistingBatch", "<active-index-snapshot>", e);
    } finally {
      lock.unlock();
    }
  }

  private Identity importExistingLocked(String pathHash, String docUid, long nowMs)
      throws SQLException {
    Optional<Identity> byPath = select(pathHash);
    if (byPath.isPresent()) {
      touch(pathHash, nowMs);
      return selectRequired(pathHash);
    }
    Optional<Identity> byUid = selectByUid(docUid);
    if (byUid.isPresent()) {
      return byUid.get();
    }
    try (PreparedStatement stmt =
        connection.prepareStatement(
            """
            INSERT INTO document_identity (path_hash, doc_uid, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?)
            """)) {
      stmt.setString(1, pathHash);
      stmt.setString(2, docUid);
      stmt.setLong(3, nowMs);
      stmt.setLong(4, nowMs);
      stmt.executeUpdate();
    }
    return selectRequired(pathHash);
  }

  @Override
  public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
    requireHash(oldPathHash);
    requireHash(newPathHash);
    lock.lock();
    try {
      if (oldPathHash.equals(newPathHash)) {
        boolean exists = select(oldPathHash).isPresent();
        if (exists) {
          touch(oldPathHash, nowMs);
        }
        return exists ? RekeyResult.ALREADY_AT_DESTINATION : RekeyResult.NOT_FOUND;
      }

      Optional<Identity> oldIdentity = select(oldPathHash);
      if (oldIdentity.isEmpty()) {
        // Destination-present is the only convergence evidence available after a response is lost:
        // the old key is gone, and the request carries no uid. Treat it as an idempotent replay.
        return select(newPathHash).isPresent()
            ? RekeyResult.ALREADY_AT_DESTINATION
            : RekeyResult.NOT_FOUND;
      }

      boolean wasAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        // Identity rows deliberately have no GC. A valid rename onto a historically-used path
        // therefore makes the moving source authoritative and replaces that stale destination.
        try (PreparedStatement delete =
            connection.prepareStatement("DELETE FROM document_identity WHERE path_hash = ?")) {
          delete.setString(1, newPathHash);
          delete.executeUpdate();
        }
        try (PreparedStatement stmt =
            connection.prepareStatement(
                "UPDATE document_identity SET path_hash = ?, last_seen_at = ? WHERE path_hash = ?")) {
          stmt.setString(1, newPathHash);
          stmt.setLong(2, nowMs);
          stmt.setString(3, oldPathHash);
          if (stmt.executeUpdate() != 1) {
            throw new SQLException(
                "Document identity rekey did not update exactly one row for " + oldPathHash);
          }
        }
        connection.commit();
        return RekeyResult.MOVED;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(wasAutoCommit);
      }
    } catch (SQLException e) {
      throw failure("rekey", oldPathHash, e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<Identity> lookup(String pathHash) {
    requireHash(pathHash);
    lock.lock();
    try {
      return select(pathHash);
    } catch (SQLException e) {
      throw failure("lookup", pathHash, e);
    } finally {
      lock.unlock();
    }
  }

  private Optional<Identity> select(String pathHash) throws SQLException {
    try (PreparedStatement stmt =
        connection.prepareStatement(
            "SELECT path_hash, doc_uid, first_seen_at, last_seen_at"
                + " FROM document_identity WHERE path_hash = ?")) {
      stmt.setString(1, pathHash);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Identity(
                rs.getString("path_hash"),
                rs.getString("doc_uid"),
                rs.getLong("first_seen_at"),
                rs.getLong("last_seen_at")));
      }
    }
  }

  private Optional<Identity> selectByUid(String docUid) throws SQLException {
    try (PreparedStatement stmt =
        connection.prepareStatement(
            "SELECT path_hash, doc_uid, first_seen_at, last_seen_at"
                + " FROM document_identity WHERE doc_uid = ?")) {
      stmt.setString(1, docUid);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Identity(
                rs.getString("path_hash"),
                rs.getString("doc_uid"),
                rs.getLong("first_seen_at"),
                rs.getLong("last_seen_at")));
      }
    }
  }

  private void touch(String pathHash, long nowMs) throws SQLException {
    try (PreparedStatement stmt =
        connection.prepareStatement(
            "UPDATE document_identity SET last_seen_at = ? WHERE path_hash = ?")) {
      stmt.setLong(1, nowMs);
      stmt.setString(2, pathHash);
      stmt.executeUpdate();
    }
  }

  private Identity selectRequired(String pathHash) throws SQLException {
    return select(pathHash)
        .orElseThrow(
            () -> new IllegalStateException("Document identity row disappeared for " + pathHash));
  }

  private static void requireHash(String pathHash) {
    if (pathHash == null || pathHash.isBlank()) {
      throw new IllegalArgumentException("pathHash is required");
    }
  }

  private static void requireUid(String docUid) {
    if (docUid == null || docUid.isBlank()) {
      throw new IllegalArgumentException("docUid is required");
    }
  }

  private IllegalStateException failure(String operation, String pathHash, SQLException cause) {
    return new IllegalStateException(
        "DocumentIdentityStore." + operation + " failed for path hash " + pathHash, cause);
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      if (connection != null) {
        connection.close();
        connection = null;
      }
    } catch (SQLException e) {
      throw new IOException("Failed to close DocumentIdentityStore", e);
    } finally {
      lock.unlock();
    }
  }
}
