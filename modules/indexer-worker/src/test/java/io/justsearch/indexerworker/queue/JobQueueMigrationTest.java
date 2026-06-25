package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the SqliteJobQueue schema migration system.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>V1 to V2 migration upgrades the schema correctly</li>
 *   <li>Existing data is preserved during migration</li>
 *   <li>Schema version (PRAGMA user_version) is updated</li>
 * </ul>
 */
final class JobQueueMigrationTest {

  @TempDir Path tempDir;

  /**
   * Test that opening SqliteJobQueue on a V1 database correctly upgrades it to V2.
   *
   * <p>Steps:
   * <ol>
   *   <li>Create a V1 database manually (no retry_after column)</li>
   *   <li>Set PRAGMA user_version = 1</li>
   *   <li>Insert sample job</li>
   *   <li>Open SqliteJobQueue (triggers migration)</li>
   *   <li>Verify user_version = 2 and retry_after column exists</li>
   *   <li>Verify existing job data is preserved</li>
   * </ol>
   */
  @Test
  void migratesV1ToV2Successfully() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Step 1-3: Create V1 database manually
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Create V1 schema (no retry_after column)
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");

      // Set schema version to 1
      stmt.execute("PRAGMA user_version = 1");

      // Insert sample job
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/test/file.txt', 'PENDING', 0, 1234567890)
          """);
    }

    // Step 4: Open SqliteJobQueue (should trigger migration)
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Step 5-6: Verify migration results
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Verify schema version is now 2
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next(), "user_version query should return a row");
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1),
            "Schema version should be upgraded to TARGET_VERSION");
      }

      // Verify retry_after column exists
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertTrue(hasRetryAfter, "retry_after column should exist after migration");

      // Verify existing data is preserved
      try (ResultSet rs = stmt.executeQuery("SELECT path, state FROM jobs WHERE path = '/test/file.txt'")) {
        assertTrue(rs.next(), "Original job should still exist after migration");
        assertEquals("/test/file.txt", rs.getString("path"));
        assertEquals("PENDING", rs.getString("state"));
      }
    } finally {
      jobQueue.close();
    }
  }

  /**
   * Test that a fresh database is created at the target schema version.
   */
  @Test
  void freshDatabaseCreatedAtTargetVersion() throws Exception {
    Path dbPath = tempDir.resolve("fresh.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Verify schema version
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1),
            "Fresh database should be at TARGET_VERSION");
      }

      // Verify retry_after column exists (V2 feature)
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertTrue(hasRetryAfter, "Fresh database should have retry_after column");
    } finally {
      jobQueue.close();
    }
  }

  /**
   * Test that a legacy database (user_version = 0 but tables exist) is detected and upgraded.
   *
   * <p>This simulates databases created before versioning was introduced.
   */
  @Test
  void upgradesLegacyDatabaseWithoutVersioning() throws Exception {
    Path dbPath = tempDir.resolve("legacy.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Create legacy database (tables exist but user_version = 0)
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // V1 schema (no retry_after)
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      // Explicitly leave user_version = 0 (default)
      // This simulates a legacy database

      // Insert sample data
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/legacy/file.txt', 'DONE', 2, 999999999)
          """);
    }

    // Open queue (should detect legacy and upgrade)
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      // Verify schema version is now at target
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1),
            "Legacy database should be upgraded to TARGET_VERSION");
      }

      // Verify retry_after column was added
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertTrue(hasRetryAfter, "Legacy database should gain retry_after column");

      // Verify existing data preserved
      try (ResultSet rs = stmt.executeQuery("SELECT path, state, attempts FROM jobs WHERE path = '/legacy/file.txt'")) {
        assertTrue(rs.next(), "Legacy data should be preserved");
        assertEquals("DONE", rs.getString("state"));
        assertEquals(2, rs.getInt("attempts"));
      }
    } finally {
      jobQueue.close();
    }
  }

  /**
   * Smoke test: backup is created before migration and can be restored to a usable queue.
   *
   * <p>Steps:
   * <ol>
   *   <li>Create a V1 database with a job</li>
   *   <li>Open SqliteJobQueue (triggers backup + migration)</li>
   *   <li>Assert jobs.db.bak exists</li>
   *   <li>Copy jobs.db.bak to a restore path and open a new SqliteJobQueue</li>
   *   <li>Verify the job data is present and queue is usable</li>
   * </ol>
   */
  @Test
  void backupCreatedBeforeMigrationAndRestoreWorks() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    Path bakPath = tempDir.resolve("jobs.db.bak");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Step 1: Create V1 database with a job
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");

      stmt.execute("PRAGMA user_version = 1");

      // Insert a job that will be preserved in backup
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/backup/test.txt', 'PENDING', 0, 1234567890)
          """);
    }

    // Step 2: Open SqliteJobQueue (triggers backup before migration)
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Step 3: Assert jobs.db.bak exists
    assertTrue(Files.exists(bakPath), "jobs.db.bak should be created before migration");
    assertTrue(Files.size(bakPath) > 0, "Backup file should not be empty");

    jobQueue.close();

    // Step 4: Copy backup to a restore path and open a new SqliteJobQueue
    Path restorePath = tempDir.resolve("restored.db");
    Files.copy(bakPath, restorePath, StandardCopyOption.REPLACE_EXISTING);

    SqliteJobQueue restoredQueue = new SqliteJobQueue(restorePath);
    restoredQueue.open();

    try {
      // Step 5: Verify the queue is usable and job data is present
      // Note: The backup is at V1, so opening it triggers migration
      // The job should still be there after migration
      long depth = restoredQueue.queueDepth();
      assertTrue(depth > 0, "Restored queue should have jobs");

      // Poll the job to verify it's actually present and usable
      var jobs = restoredQueue.pollPending(10);
      assertEquals(1, jobs.size(), "Should have exactly 1 pending job from backup");
      // Path may be normalized differently on Windows; check contains to be robust
      String pathStr = jobs.get(0).path().toString();
      assertTrue(pathStr.contains("backup") && pathStr.contains("test.txt"),
          "Job path should contain expected components: " + pathStr);
    } finally {
      restoredQueue.close();
    }
  }

  /**
   * Test that migration failure causes transaction rollback.
   *
   * <p>This test verifies that:
   * <ul>
   *   <li>Migration throws when hook fails</li>
   *   <li>Transaction rolls back</li>
   *   <li>PRAGMA user_version remains at the pre-migration value</li>
   *   <li>No partial schema change (retry_after column absent)</li>
   * </ul>
   */
  @Test
  void migrationFailureRollsBack() throws Exception {
    Path dbPath = tempDir.resolve("rollback-test.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    // Create V1 database with a job
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT
          )
          """);

      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");

      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);

      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");

      stmt.execute("PRAGMA user_version = 1");

      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated)
          VALUES ('/rollback/test.txt', 'PENDING', 0, 1234567890)
          """);
    }

    // Create a hook that throws after V2 migration is applied
    SqliteJobQueue.MigrationStepHook failingHook = version -> {
      if (version == 2) {
        throw new SQLException("Simulated migration failure for testing");
      }
    };

    // Try to open - should throw due to migration failure
    // Note: open() may wrap SQLException in IOException in some cases
    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath, 3, null, failingHook);
    Exception thrown = null;
    try {
      jobQueue.open();
    } catch (Exception e) {
      thrown = e;
    } finally {
      // Close to release SQLite files (even if open failed mid-way)
      try {
        jobQueue.close();
      } catch (Exception ignored) {
        // Best effort cleanup
      }
    }

    // Verify exception was thrown
    assertNotNull(thrown, "Migration should throw when hook fails");
    // Verify it's either SQLException or contains one as cause
    assertTrue(
        thrown instanceof SQLException ||
        (thrown.getCause() != null && thrown.getCause() instanceof SQLException),
        "Expected SQLException or cause to be SQLException, got: " + thrown);

    // Verify rollback occurred: user_version should still be 1
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {

      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1), "user_version should remain at 1 after rollback");
      }

      // Verify no partial schema change: retry_after column should NOT exist
      boolean hasRetryAfter = false;
      try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
        while (rs.next()) {
          if ("retry_after".equals(rs.getString("name"))) {
            hasRetryAfter = true;
            break;
          }
        }
      }
      assertFalse(hasRetryAfter, "retry_after column should NOT exist after rollback");

      // Verify original data is still intact
      try (ResultSet rs = stmt.executeQuery("SELECT path, state FROM jobs WHERE path = '/rollback/test.txt'")) {
        assertTrue(rs.next(), "Original job should still exist after rollback");
        assertEquals("PENDING", rs.getString("state"));
      }
    }
  }

  @Test
  void migratesV4ToV5AddsIngestionOutcomeColumns() throws Exception {
    Path dbPath = tempDir.resolve("v4.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("PRAGMA user_version = 4");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/v4/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }

      assertTrue(hasColumn(stmt, "last_outcome_class"));
      assertTrue(hasColumn(stmt, "last_reason_code"));
      assertTrue(hasColumn(stmt, "last_retry_policy"));
      assertTrue(hasColumn(stmt, "last_diagnostic_summary"));
      assertTrue(hasColumn(stmt, "last_outcome_at"));

      try (ResultSet rs =
          stmt.executeQuery("SELECT state, collection FROM jobs WHERE path = '/v4/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
      }
    } finally {
      jobQueue.close();
    }
  }

  @Test
  void migratesV5ToV6AddsIngestionLedger() throws Exception {
    Path dbPath = tempDir.resolve("v5.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL,
            last_outcome_class TEXT,
            last_reason_code TEXT,
            last_retry_policy TEXT,
            last_diagnostic_summary TEXT,
            last_outcome_at INTEGER
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("PRAGMA user_version = 5");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/v5/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }
      assertTrue(hasTable(stmt, "ingestion_ledger"));
      try (ResultSet rs =
          stmt.executeQuery("SELECT state, collection FROM jobs WHERE path = '/v5/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
      }
    } finally {
      jobQueue.close();
    }
  }

  @Test
  void unversionedDbWithCollectionMigratesToV5WithoutDuplicateColumnFailure() throws Exception {
    Path dbPath = tempDir.resolve("unversioned-v4-shape.db");
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE jobs (
            path TEXT PRIMARY KEY,
            state TEXT NOT NULL DEFAULT 'PENDING',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_updated INTEGER NOT NULL,
            error_message TEXT,
            retry_after INTEGER,
            collection TEXT DEFAULT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_jobs_state ON jobs(state)");
      stmt.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state, last_updated)");
      stmt.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY,
            op TEXT NOT NULL,
            payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL
          )
          """);
      stmt.execute("CREATE INDEX idx_switch_buffer_updated ON switch_buffer(last_updated)");
      stmt.execute("PRAGMA user_version = 0");
      stmt.execute("""
          INSERT INTO jobs (path, state, attempts, last_updated, collection)
          VALUES ('/legacy/file.txt', 'PENDING', 0, 1234567890, 'docs')
          """);
    }

    SqliteJobQueue jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
        assertTrue(rs.next());
        assertEquals(SqliteSchema.TARGET_VERSION, rs.getInt(1));
      }
      assertTrue(hasColumn(stmt, "collection"));
      assertTrue(hasColumn(stmt, "last_outcome_class"));
      try (ResultSet rs =
          stmt.executeQuery("SELECT state, collection FROM jobs WHERE path = '/legacy/file.txt'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("state"));
        assertEquals("docs", rs.getString("collection"));
      }
    } finally {
      jobQueue.close();
    }
  }

  private static boolean hasColumn(Statement stmt, String columnName) throws SQLException {
    try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
      while (rs.next()) {
        if (columnName.equals(rs.getString("name"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasTable(Statement stmt, String tableName) throws SQLException {
    try (ResultSet rs =
        stmt.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
      return rs.next();
    }
  }
}
