/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

/**
 * Single Source of Truth for all SQLite DDL and schema versioning.
 *
 * <p>This class centralizes all SQL schema definitions for the jobs.db database,
 * including table creation, indexes, and migration scripts.
 *
 * <p><b>Version History:</b>
 * <ul>
 *   <li>V1: Initial schema (jobs + switch_buffer tables)</li>
 *   <li>V2: Added retry_after column to jobs table</li>
 *   <li>V3: Added composite index (state, last_updated) for poll optimization</li>
 *   <li>V4: Added collection column to jobs table for collection-based tagging</li>
 *   <li>V5: Added latest typed ingestion outcome columns</li>
 *   <li>V6: Added privacy-safe ingestion outcome ledger table</li>
 *   <li>V7: Added scoped path-resolution table (ADR-0028, tempdoc 419 T5.1)</li>
 * </ul>
 */
public final class SqliteSchema {

  private SqliteSchema() {} // Utility class, no instantiation

  // ==================== Version Constants ====================

  /**
   * Target schema version. The migrate() method will upgrade the database
   * to this version using the migration ladder.
   */
  public static final int TARGET_VERSION = 7;

  // ==================== Table: jobs ====================

  /**
   * DDL for the jobs table.
   *
   * <p>Columns:
   * <ul>
   *   <li>path - Primary key, normalized file path</li>
   *   <li>state - PENDING, PROCESSING, DONE, or FAILED</li>
   *   <li>attempts - Number of failed processing attempts (incremented on failure, not claim)</li>
   *   <li>last_updated - Timestamp of last state change</li>
   *   <li>error_message - Error message from last failure</li>
   *   <li>retry_after - Timestamp after which a PENDING job can be retried (V2+)</li>
   * </ul>
   */
  public static final String CREATE_JOBS_TABLE = """
      CREATE TABLE IF NOT EXISTS jobs (
        path TEXT PRIMARY KEY,
        state TEXT NOT NULL DEFAULT 'PENDING',
        attempts INTEGER NOT NULL DEFAULT 0,
        last_updated INTEGER NOT NULL,
        error_message TEXT,
        retry_after INTEGER
      )
      """;

  /** Index on jobs.state for efficient state-based queries. */
  public static final String CREATE_JOBS_STATE_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_jobs_state ON jobs(state)
      """;

  /** Composite index on jobs(state, last_updated) for efficient pollPending queries. */
  public static final String CREATE_JOBS_STATE_UPDATED_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_jobs_state_updated ON jobs(state, last_updated)
      """;

  // ==================== Table: switch_buffer ====================

  /**
   * DDL for the switch_buffer table.
   *
   * <p>Used during Blue/Green migration cutover (SWITCHING state) to durably
   * buffer operations that cannot be applied immediately while the index
   * generation is switching.
   *
   * <p>Columns:
   * <ul>
   *   <li>key - Primary key for deduplication (e.g., "path:/normalized/path")</li>
   *   <li>op - Operation type (UPSERT, DELETE, SYNC_ROOT, etc.)</li>
   *   <li>payload - JSON payload with operation details</li>
   *   <li>last_updated - Timestamp for ordering replay</li>
   * </ul>
   */
  public static final String CREATE_SWITCH_BUFFER_TABLE = """
      CREATE TABLE IF NOT EXISTS switch_buffer (
        key TEXT PRIMARY KEY,
        op TEXT NOT NULL,
        payload TEXT NOT NULL,
        last_updated INTEGER NOT NULL
      )
      """;

  /** Index on switch_buffer.last_updated for ordered replay. */
  public static final String CREATE_SWITCH_BUFFER_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_switch_buffer_updated ON switch_buffer(last_updated)
      """;

  // ==================== Table: ingestion_ledger ====================

  public static final String CREATE_INGESTION_LEDGER_TABLE = """
      CREATE TABLE IF NOT EXISTS ingestion_ledger (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        path_hash TEXT NOT NULL,
        collection TEXT,
        outcome_class TEXT NOT NULL,
        reason_code TEXT NOT NULL,
        retry_policy TEXT NOT NULL,
        diagnostic_summary TEXT,
        observed_at INTEGER NOT NULL,
        source_size_bytes INTEGER,
        source_modified_at INTEGER,
        source_kind TEXT,
        artifact_status TEXT,
        policy_id TEXT,
        parser_id TEXT
      )
      """;

  public static final String CREATE_INGESTION_LEDGER_PATH_TIME_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_ingestion_ledger_path_time
      ON ingestion_ledger(path_hash, observed_at)
      """;

  public static final String CREATE_INGESTION_LEDGER_OUTCOME_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_ingestion_ledger_outcome
      ON ingestion_ledger(outcome_class, reason_code, observed_at)
      """;

  // ==================== Table: path_resolution (ADR-0028 / T5.1) ====================

  /**
   * DDL for the path_resolution table. Backs the scoped reverse-lookup RPC defined in
   * ADR-0028. Stores {@code (path_hash, normalized_path)} pairs alongside lifecycle metadata
   * so the {@code POST /api/library/resolve-hash} endpoint can answer "which file is this
   * hash?" for files still under a watched root within retention.
   *
   * <p><strong>Privacy contract.</strong> This table is the only persistent place where raw
   * paths are stored after admission. It is never marshaled into {@code IngestionEventView}
   * (the export shape pinned by {@code ingestionEventViewExportContractIsPinned}). The
   * ArchUnit pin {@code LibraryResolveHashOnlyCallerPin} (T5.4) enforces that no class in the
   * diagnostic export call tree transitively depends on the {@code PathResolutionStore} that
   * reads/writes this table.
   *
   * <p>Columns:
   * <ul>
   *   <li>{@code path_hash} — SHA-256 hex over the normalized absolute path. Primary key.
   *   <li>{@code normalized_path} — the raw normalized path. Internal-use only.
   *   <li>{@code last_seen_at} — epoch ms of the most recent admission for this hash.
   *   <li>{@code removed_at} — epoch ms when a file deletion was observed; NULL otherwise.
   *       Rows with non-null {@code removed_at} are pruned after the configurable retention
   *       window (default 90 days, env {@code JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS}).
   * </ul>
   */
  public static final String CREATE_PATH_RESOLUTION_TABLE = """
      CREATE TABLE IF NOT EXISTS path_resolution (
        path_hash TEXT PRIMARY KEY,
        normalized_path TEXT NOT NULL,
        last_seen_at INTEGER NOT NULL,
        removed_at INTEGER
      )
      """;

  /**
   * Index on {@code normalized_path} for prefix-pruning on root unwatch. Also speeds up
   * potential debugging queries that filter by path prefix; the column is internal-use only
   * so external operators never see indexed values.
   */
  public static final String CREATE_PATH_RESOLUTION_PATH_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_path_resolution_normalized_path
      ON path_resolution(normalized_path)
      """;

  /** Index on {@code removed_at} for efficient retention-pruning sweeps. */
  public static final String CREATE_PATH_RESOLUTION_REMOVED_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_path_resolution_removed_at
      ON path_resolution(removed_at)
      """;

  // ==================== Migration SQL ====================

  /**
   * V1 to V2 migration: Add retry_after column.
   *
   * <p>This column enables exponential backoff for failed jobs.
   */
  public static final String MIGRATE_V1_TO_V2_ADD_RETRY_AFTER = """
      ALTER TABLE jobs ADD COLUMN retry_after INTEGER
      """;

  /**
   * V2 to V3 migration: Add composite index for poll optimization.
   *
   * <p>This index dramatically accelerates pollPending() by enabling direct
   * index lookup for PENDING jobs sorted by last_updated, eliminating the
   * need for a separate sort operation.
   */
  public static final String MIGRATE_V2_TO_V3_ADD_STATE_UPDATED_INDEX = """
      CREATE INDEX IF NOT EXISTS idx_jobs_state_updated ON jobs(state, last_updated)
      """;

  /**
   * V3 to V4 migration: Add collection column for collection-based tagging.
   *
   * <p>Nullable column — NULL means the document uses the default collection.
   * Used to tag built-in help files and other special content.
   */
  public static final String MIGRATE_V3_TO_V4_ADD_COLLECTION = """
      ALTER TABLE jobs ADD COLUMN collection TEXT DEFAULT NULL
      """;

  static final String[] MIGRATE_V4_TO_V5_ADD_INGESTION_OUTCOME = {
      "ALTER TABLE jobs ADD COLUMN last_outcome_class TEXT",
      "ALTER TABLE jobs ADD COLUMN last_reason_code TEXT",
      "ALTER TABLE jobs ADD COLUMN last_retry_policy TEXT",
      "ALTER TABLE jobs ADD COLUMN last_diagnostic_summary TEXT",
      "ALTER TABLE jobs ADD COLUMN last_outcome_at INTEGER"
  };

  static final String[] MIGRATE_V5_TO_V6_ADD_INGESTION_LEDGER = {
      CREATE_INGESTION_LEDGER_TABLE,
      CREATE_INGESTION_LEDGER_PATH_TIME_INDEX,
      CREATE_INGESTION_LEDGER_OUTCOME_INDEX
  };

  /**
   * V6 to V7 migration: add scoped path_resolution table and indexes (ADR-0028, T5.1). Existing
   * ledger entries are NOT back-populated — the resolver returns {@code found=false} for any
   * hash from before the migration. Files still under watched roots are re-resolved naturally
   * on the next scan or watcher-driven update.
   */
  static final String[] MIGRATE_V6_TO_V7_ADD_PATH_RESOLUTION = {
      CREATE_PATH_RESOLUTION_TABLE,
      CREATE_PATH_RESOLUTION_PATH_INDEX,
      CREATE_PATH_RESOLUTION_REMOVED_INDEX
  };

  // ==================== Utility Methods ====================

  /**
   * Checks if the jobs table has the retry_after column.
   *
   * <p>Used for detecting legacy databases (version 0) that were created
   * before versioning was introduced.
   *
   * @param rs ResultSet from PRAGMA table_info(jobs)
   * @return true if retry_after column exists
   */
  public static boolean hasRetryAfterColumn(java.sql.ResultSet rs) throws java.sql.SQLException {
    return hasColumn(rs, "retry_after");
  }

  public static boolean hasColumn(java.sql.ResultSet rs, String columnName)
      throws java.sql.SQLException {
    while (rs.next()) {
      if (columnName.equalsIgnoreCase(rs.getString("name"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Determines the effective schema version for a legacy database.
   *
   * <p>Legacy databases (created before versioning) have user_version = 0.
   * This method inspects the schema to determine the equivalent version:
   * <ul>
   *   <li>If jobs table doesn't exist → V0 (fresh database)</li>
   *   <li>If jobs table exists without retry_after → V1</li>
   *   <li>If jobs table exists with retry_after → V2</li>
   * </ul>
   *
   * @param connection Active database connection
   * @return Effective schema version (0, 1, or 2)
   */
  public static int detectLegacyVersion(java.sql.Connection connection) throws java.sql.SQLException {
    // Check if jobs table exists
    try (java.sql.Statement stmt = connection.createStatement();
         java.sql.ResultSet rs = stmt.executeQuery(
             "SELECT name FROM sqlite_master WHERE type='table' AND name='jobs'")) {
      if (!rs.next()) {
        // No jobs table → fresh database
        return 0;
      }
    }

    // Jobs table exists, check for the newest columns present in unversioned databases.
    try (java.sql.Statement stmt = connection.createStatement();
         java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info(jobs)")) {
      boolean hasRetryAfter = false;
      boolean hasCollection = false;
      boolean hasOutcomeClass = false;
      while (rs.next()) {
        String name = rs.getString("name");
        if ("retry_after".equalsIgnoreCase(name)) {
          hasRetryAfter = true;
        } else if ("collection".equalsIgnoreCase(name)) {
          hasCollection = true;
        } else if ("last_outcome_class".equalsIgnoreCase(name)) {
          hasOutcomeClass = true;
        }
      }
      if (hasOutcomeClass && tableExists(connection, "ingestion_ledger")) {
        return 6;
      }
      if (hasOutcomeClass) {
        return 5;
      }
      if (hasCollection) {
        return 4;
      }
      if (hasRetryAfter) {
        return 2;
      }
      return 1; // Jobs exists but no retry_after → V1
    }
  }

  private static boolean tableExists(java.sql.Connection connection, String tableName)
      throws java.sql.SQLException {
    try (java.sql.PreparedStatement stmt =
            connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
      stmt.setString(1, tableName);
      try (java.sql.ResultSet rs = stmt.executeQuery()) {
        return rs.next();
      }
    }
  }
}
