package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobQueueTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private Path dbPath;

  @BeforeEach
  void setUp() throws Exception {
    dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void enqueueAddsJobsToQueue() {
    List<Path> paths = List.of(
        Path.of("/path/to/file1.txt"),
        Path.of("/path/to/file2.txt")
    );

    int count = jobQueue.enqueue(paths);

    assertEquals(2, count);
    assertEquals(2, jobQueue.queueDepth());
  }

  @Test
  void pollPendingReturnsJobsAndMarksProcessing() {
    jobQueue.enqueue(List.of(Path.of("/path/to/file.txt")));

    var jobs = jobQueue.pollPending(1);

    assertEquals(1, jobs.size());
    // Queue depth still 1 because job is PROCESSING, not DONE
    assertEquals(1, jobQueue.queueDepth());
  }

  // Tempdoc 550 Thesis II (liveness reaper): the age-bounded recoverStuckJobs(olderThanMs) re-queues
  // ONLY genuinely-stale PROCESSING rows (worker died mid-process), never jobs actively draining.
  @Test
  void recoverStuckJobsAgeBoundedReapsOnlyStaleProcessing() throws Exception {
    jobQueue.enqueue(List.of(Path.of("/tmp/stale.txt"), Path.of("/tmp/fresh.txt")));
    jobQueue.pollPending(2); // both → PROCESSING, last_updated = now
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
      // Backdate ONLY the stale row's last_updated to 20 minutes ago.
      try (PreparedStatement ps =
          raw.prepareStatement("UPDATE jobs SET last_updated = ? WHERE path LIKE ?")) {
        ps.setLong(1, System.currentTimeMillis() - 20 * 60_000L);
        ps.setString(2, "%stale.txt");
        assertEquals(1, ps.executeUpdate(), "exactly one row backdated");
      }
      int reaped = jobQueue.recoverStuckJobs(15 * 60_000L); // 15-min threshold
      assertEquals(1, reaped, "only the stale PROCESSING row is reaped → PENDING");
      assertEquals(1L, countState(raw, "PENDING"), "stale row is now PENDING");
      assertEquals(1L, countState(raw, "PROCESSING"), "fresh row stays PROCESSING (not reaped)");
    }
  }

  // Tempdoc 575 §4.3b / 550 Thesis II (the liveness invariant): heartbeatProcessing() refreshes
  // last_updated on PROCESSING rows so a fresh timestamp means a LIVE OWNER still holds them — the
  // reaper spares a heartbeated row even past its window, and reclaims it once the beats stop.
  @Test
  void heartbeatProcessingMarksLivenessSoTheReaperSparesActiveJobsAndReapsOrphans() throws Exception {
    jobQueue.enqueue(List.of(Path.of("/tmp/a.txt"), Path.of("/tmp/b.txt")));
    jobQueue.pollPending(2); // both → PROCESSING
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
      // Backdate BOTH rows to 20 minutes ago — without a beat they would be reaped at a 15-min window.
      backdateAll(raw, 20 * 60_000L);
      // A live owner heartbeats → both last_updated refreshed to now.
      jobQueue.heartbeatProcessing();
      assertEquals(
          0,
          jobQueue.recoverStuckJobs(15 * 60_000L),
          "a heartbeated PROCESSING row is NOT reaped — the live owner still holds it");
      assertEquals(2L, countState(raw, "PROCESSING"), "both stay PROCESSING after a beat");
      // Beats stop (loop died); the rows go stale again and the reaper reclaims them as orphans.
      backdateAll(raw, 20 * 60_000L);
      assertEquals(
          2,
          jobQueue.recoverStuckJobs(15 * 60_000L),
          "with beats stopped, the stale (orphaned) PROCESSING rows are reclaimed → PENDING");
    }
  }

  private static void backdateAll(Connection c, long agoMs) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("UPDATE jobs SET last_updated = ? WHERE state = 'PROCESSING'")) {
      ps.setLong(1, System.currentTimeMillis() - agoMs);
      ps.executeUpdate();
    }
  }

  private static long countState(Connection c, String state) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM jobs WHERE state = ?")) {
      ps.setString(1, state);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : -1L;
      }
    }
  }

  @Test
  void markDoneRemovesFromQueueDepth() {
    Path filePath = Path.of("/path/to/file.txt");
    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);

    jobQueue.markDone(filePath);

    assertEquals(0, jobQueue.queueDepth());
    assertEquals(1, jobQueue.completedCount());
  }

  @Test
  void markFailedWithRetriesReturnsToQueue() {
    Path filePath = Path.of("/path/to/file.txt");
    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);

    // First failure - should return to PENDING
    jobQueue.markFailed(filePath, "Test error");

    // Should still be in queue
    assertEquals(1, jobQueue.queueDepth());

    // Poll again and fail again
    jobQueue.pollPending(1);
    jobQueue.markFailed(filePath, "Test error 2");

    // Still in queue (attempt 2)
    assertEquals(1, jobQueue.queueDepth());
  }

  @Test
  void recoverStuckJobsResetsProcesing() {
    Path filePath = Path.of("/path/to/file.txt");
    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1); // Now in PROCESSING state

    int recovered = jobQueue.recoverStuckJobs();

    assertEquals(1, recovered);
    // Job should be back to PENDING
    var pending = jobQueue.pollPending(1);
    assertEquals(1, pending.size());
  }

  @Test
  void emptyEnqueueReturnsZero() {
    assertEquals(0, jobQueue.enqueue(List.of()));
    assertEquals(0, jobQueue.enqueue(null));
  }

  @Test
  void pollEmptyQueueReturnsEmpty() {
    var jobs = jobQueue.pollPending(10);
    assertTrue(jobs.isEmpty());
  }

  @Test
  void completedCountTracksFinishedJobs() {
    Path filePath = Path.of("/path/to/file.txt");
    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    jobQueue.markDone(filePath);

    assertEquals(1, jobQueue.completedCount());
    assertEquals(0, jobQueue.queueDepth());
  }

  /**
   * Regression test: attempts counter should represent failures, not claims.
   *
   * <p>This test verifies that:
   * <ul>
   *   <li>pollPending() (claim) does NOT increment attempts</li>
   *   <li>recoverStuckJobs() (crash recovery) does NOT increment attempts</li>
   *   <li>Only markFailed() increments attempts</li>
   * </ul>
   */
  @Test
  void attemptsSemanticsOnlyIncrementOnFailure() throws Exception {
    // Use a real temp file so path normalization is consistent across platforms
    Path testFile = tempDir.resolve("attempts_test.txt");
    java.nio.file.Files.writeString(testFile, "test");

    // Get the normalized path that will be stored in the DB
    String normalizedPath = PathNormalizer.normalizePath(testFile.toAbsolutePath().toString());
    jobQueue.enqueue(List.of(testFile));

    // 1) Initial state: attempts should be 0
    assertEquals(0, readAttemptsViaJdbc(normalizedPath), "Initial attempts should be 0");

    // 2) Poll (claim) - attempts should still be 0
    var claimed = jobQueue.pollPending(1);
    assertEquals(1, claimed.size(), "Should have claimed 1 job");
    assertEquals(0, readAttemptsViaJdbc(normalizedPath), "Attempts should stay 0 after pollPending (claim)");

    // 3) Simulate crash: close queue, reopen, recover stuck jobs
    jobQueue.close();
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    int recovered = jobQueue.recoverStuckJobs();
    assertEquals(1, recovered, "Should have recovered 1 stuck job");
    assertEquals(0, readAttemptsViaJdbc(normalizedPath), "Attempts should stay 0 after crash recovery");

    // 4) Now actually fail the job - attempts should increment
    claimed = jobQueue.pollPending(1);
    assertEquals(1, claimed.size());
    Path claimedPath = claimed.get(0).path();
    jobQueue.markFailed(claimedPath, "Test failure");
    assertEquals(1, readAttemptsViaJdbc(normalizedPath), "Attempts should be 1 after first failure");

    // 5) Fail again - since job now has retry_after in the future (backoff),
    // we can't poll it immediately. Instead, directly verify attempts via JDBC
    // by calling markFailed on the path again (it will find and update the job)
    jobQueue.markFailed(claimedPath, "Test failure 2");
    assertEquals(2, readAttemptsViaJdbc(normalizedPath), "Attempts should be 2 after second failure");
  }

  /** Helper to read the attempts column directly via JDBC (not exposed via production API). */
  private int readAttemptsViaJdbc(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement("SELECT attempts FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          return rs.getInt("attempts");
        }
        throw new AssertionError("Job not found for path: " + path);
      }
    }
  }

  /** Helper to read the retry_after column directly via JDBC. Returns null when SQL NULL. */
  private Long readRetryAfterViaJdbc(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        PreparedStatement stmt =
            conn.prepareStatement("SELECT retry_after FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          long value = rs.getLong("retry_after");
          return rs.wasNull() ? null : value;
        }
        throw new AssertionError("Job not found for path: " + path);
      }
    }
  }

  @Test
  void ingestionLedgerEntryCapsStringFields() {
    String huge = "x".repeat(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS + 100);
    JobQueue.IngestionLedgerEntry entry =
        new JobQueue.IngestionLedgerEntry(
            huge, huge, 1L, 2L, huge, huge, huge, huge);
    assertEquals(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS, entry.pathHash().length());
    assertEquals(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS, entry.collection().length());
    assertEquals(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS, entry.sourceKind().length());
    assertEquals(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS, entry.artifactStatus().length());
    assertEquals(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS, entry.policyId().length());
    assertEquals(JobQueue.LEDGER_ENTRY_MAX_FIELD_CHARS, entry.parserId().length());
  }

  @Test
  void ingestionEventViewExposesOnlyPathHashStructurally() {
    java.lang.reflect.RecordComponent[] components =
        JobQueue.IngestionEventView.class.getRecordComponents();
    assertNotNull(components, "IngestionEventView must be a record");
    java.util.Set<String> componentNames = new java.util.HashSet<>();
    for (java.lang.reflect.RecordComponent component : components) {
      componentNames.add(component.getName());
    }
    assertTrue(
        componentNames.contains("pathHash"),
        "Export-safe view must carry pathHash");
    for (String forbidden : List.of("path", "filePath", "originalPath", "rawPath", "absolutePath")) {
      assertFalse(
          componentNames.contains(forbidden),
          "Export-safe IngestionEventView must not expose component '" + forbidden + "'");
    }
  }

  /**
   * Tempdoc 410 §8 + Slice E — pins the exact {@link JobQueue.IngestionEventView} field set so a
   * future addition breaks this test instead of silently expanding the operator-visible export
   * surface. The redaction contract lives in
   * {@code docs/explanation/03-knowledge-server.md#ingestion-ledger-privacy-contract-tempdoc-410-8--slice-e}:
   * any new field must either carry no path-derivable information or be opted out of the export
   * via a documented mechanism (e.g., a separate internal projection). A failing assertion here
   * routes the developer back to that contract before adding the field.
   *
   * <p>Slice G.2 verification (2026-04-25): the pin was exercised end-to-end by adding a stub
   * {@code String testFakeField} to {@link JobQueue.IngestionEventView} + updating the
   * positional constructor in {@code SqliteJobQueue.recentIngestionEvents}, then running this
   * test. The symmetric-difference assertion fired with message
   * {@code added=[testFakeField], removed=[]}, exactly naming the new field. Reverted before
   * commit. Both layers of defense (compilation in SqliteJobQueue + this pin) are confirmed
   * to fire.
   */
  @Test
  void ingestionEventViewExportContractIsPinned() {
    java.util.Set<String> allowed =
        java.util.Set.of(
            "id",
            "pathHash",
            "collection",
            "outcomeClass",
            "reasonCode",
            "retryPolicy",
            "diagnosticSummary",
            "observedAtMs",
            "sourceSizeBytes",
            "sourceModifiedAtMs",
            "sourceKind",
            "artifactStatus",
            "policyId",
            "parserId");
    java.util.Set<String> declared = new java.util.HashSet<>();
    for (java.lang.reflect.RecordComponent component :
        JobQueue.IngestionEventView.class.getRecordComponents()) {
      declared.add(component.getName());
    }
    java.util.Set<String> added = new java.util.TreeSet<>(declared);
    added.removeAll(allowed);
    java.util.Set<String> removed = new java.util.TreeSet<>(allowed);
    removed.removeAll(declared);
    if (!added.isEmpty() || !removed.isEmpty()) {
      org.junit.jupiter.api.Assertions.fail(
          "IngestionEventView field set drifted from the redaction contract pin. "
              + "added="
              + added
              + ", removed="
              + removed
              + ". Update the ALLOWED set in this test together with the redaction contract "
              + "section in docs/explanation/03-knowledge-server.md "
              + "(\"Ingestion Ledger Privacy Contract\"). Any new field must either carry no "
              + "path-derivable information or be opted out of the operator-visible export.");
    }
  }

  @Test
  void recentIngestionEventsArePrivacySafeAndContextRich() {
    Path filePath = Path.of("/private/path/report.txt");
    jobQueue.enqueue(List.of(filePath), "docs");
    jobQueue.pollPending(1);
    IngestionOutcome outcome =
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE,
            "Indexed successfully");
    jobQueue.markDone(
        filePath,
        outcome,
        new JobQueue.IngestionLedgerEntry(
            "hash-only", "docs", 12L, 34L, "REGULAR_FILE", "SUCCESS_FULL", "policy", "parser"));

    List<JobQueue.IngestionEventView> events = jobQueue.recentIngestionEvents(10);

    assertEquals(1, events.size());
    JobQueue.IngestionEventView event = events.get(0);
    assertEquals("hash-only", event.pathHash());
    assertEquals("docs", event.collection());
    assertEquals("SUCCESS_FULL", event.outcomeClass());
    assertEquals("SUCCESS", event.reasonCode());
    assertEquals("REGULAR_FILE", event.sourceKind());
    assertEquals("SUCCESS_FULL", event.artifactStatus());
    assertEquals("policy", event.policyId());
    assertEquals("parser", event.parserId());
  }

  @Test
  void ingestionOutcomeSummaryAggregatesRetainedEvents() {
    Path first = Path.of("/test/summary-a.txt");
    Path second = Path.of("/test/summary-b.txt");
    IngestionOutcome outcome =
        IngestionOutcome.of(
            IngestionOutcomeClass.SKIPPED_POLICY,
            IngestionReasonCodes.UNCHANGED,
            IngestionRetryPolicy.NONE,
            "UNCHANGED");
    jobQueue.enqueue(List.of(first, second));
    jobQueue.pollPending(2);
    jobQueue.markDone(first, outcome, null);
    jobQueue.markDone(second, outcome, null);

    List<JobQueue.IngestionOutcomeSummary> summaries = jobQueue.ingestionOutcomeSummary(0);

    assertEquals(1, summaries.size());
    assertEquals("SKIPPED_POLICY", summaries.get(0).outcomeClass());
    assertEquals("UNCHANGED", summaries.get(0).reasonCode());
    assertEquals(2L, summaries.get(0).count());
  }

  @Test
  void cleanupOldJobsLeavesLedgerUntouchedByDesign() throws Exception {
    // Tempdoc 410 §8 / review fix #6: queue retention and ledger retention are decoupled so the
    // audit trail can outlive queue rows. cleanupOldJobs no longer prunes ledger events.
    Path oldDone = Path.of("/test/old-queue.txt");
    jobQueue.enqueue(List.of(oldDone));
    jobQueue.pollPending(1);
    IngestionOutcome outcome =
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE,
            "Indexed successfully");
    jobQueue.markDone(oldDone, outcome, null);

    long cutoffPast = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    String normalized = PathNormalizer.normalizePath(oldDone.toAbsolutePath().toString());
    backdateRow(normalized, cutoffPast);
    backdateAllLedgerRows(cutoffPast);

    assertEquals(1, jobQueue.cleanupOldJobs(7));

    assertEquals(
        1L,
        countAllLedgerRows(),
        "cleanupOldJobs must not delete ledger events — use cleanupOldLedgerEvents");
  }

  @Test
  void cleanupOldLedgerEventsHonorsIndependentRetention() throws Exception {
    Path oldDone = Path.of("/test/old-ledger.txt");
    jobQueue.enqueue(List.of(oldDone));
    jobQueue.pollPending(1);
    jobQueue.markDone(
        oldDone,
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE,
            "Indexed successfully"),
        null);

    long cutoffPast = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    backdateAllLedgerRows(cutoffPast);

    assertEquals(0L, jobQueue.cleanupOldLedgerEvents(180), "ledger retention 180d preserves 30d event");
    assertEquals(1L, countAllLedgerRows(), "180d retention must keep the 30d-old event");

    assertEquals(1L, jobQueue.cleanupOldLedgerEvents(7), "ledger retention 7d removes 30d-old event");
    assertEquals(0L, countAllLedgerRows());
  }

  // ============================================================================
  // jobStateCounts() — fine-grained counts by state
  // ============================================================================

  @Test
  void jobStateCounts_distinguishesPendingProcessingDoneFailed() {
    // Build a known mix: 1 done, 1 failed (after 5 retries), 1 processing, 1 pending.
    Path donePath = Path.of("/test/done.txt");
    jobQueue.enqueue(List.of(donePath));
    jobQueue.pollPending(1);
    jobQueue.markDone(donePath);

    Path failedPath = Path.of("/test/failed.txt");
    jobQueue.enqueue(List.of(failedPath));
    // Drive through max retries to reach FAILED state
    for (int i = 0; i < 6; i++) {
      jobQueue.pollPending(1);
      jobQueue.markFailed(failedPath, "permanent");
    }

    Path processingPath = Path.of("/test/processing.txt");
    jobQueue.enqueue(List.of(processingPath));
    jobQueue.pollPending(1); // claims it -> PROCESSING

    Path pendingPath = Path.of("/test/pending.txt");
    jobQueue.enqueue(List.of(pendingPath));

    JobQueue.JobStateCounts counts = jobQueue.jobStateCounts();
    assertEquals(1L, counts.doneCount(), "Should report 1 DONE job");
    // Failed counter may include backoff transitions; assert it's at least 1.
    assertTrue(counts.failedCount() >= 1L, "Should report at least 1 FAILED job");
    assertTrue(counts.processingCount() >= 1L, "Should report at least 1 PROCESSING job");
    assertTrue(counts.pendingCount() >= 1L, "Should report at least 1 PENDING job");
  }

  @Test
  void listFailedJobsByPathPrefix_filtersByPrefixAndState() {
    // Two FAILED jobs under sibling roots; one PENDING under the target root (must be excluded).
    Path aFailed = Path.of("/rootlist/alpha/a-failed.txt");
    Path bFailed = Path.of("/rootlist/beta/b-failed.txt");
    Path aPending = Path.of("/rootlist/alpha/a-pending.txt");
    for (Path p : List.of(aFailed, bFailed)) {
      jobQueue.enqueue(List.of(p));
      for (int i = 0; i < 6; i++) { // drive to FAILED
        jobQueue.pollPending(10);
        jobQueue.markFailed(p, "permanent");
      }
    }
    jobQueue.enqueue(List.of(aPending));

    // enqueue stores toAbsolutePath()-normalized paths, so query with the matching absolute prefix
    // (production passes the watched root's absolute path, like countByPathPrefix).
    var underAlpha =
        jobQueue.listFailedJobsByPathPrefix(
            Path.of("/rootlist/alpha").toAbsolutePath().toString(), 100);
    // Only alpha's FAILED job — beta excluded by prefix, the PENDING one excluded by state.
    assertEquals(1, underAlpha.size(), "Should list exactly alpha's one FAILED job");
    assertTrue(
        underAlpha.get(0).path().toLowerCase(java.util.Locale.ROOT).contains("a-failed"),
        "The listed job should be alpha's failed file");
  }

  // ============================================================================
  // failureSummary() — failed-job aggregation
  // ============================================================================

  @Test
  void failureSummary_emptyQueueReturnsZerosAndNulls() {
    JobQueue.FailureSummary summary = jobQueue.failureSummary();
    assertEquals(0L, summary.failedCount());
    assertNull(summary.lastFailedPath());
    assertNull(summary.lastFailedErrorMessage());
    assertNull(summary.lastFailedAtMs());
    assertNull(summary.nextRetryAtMs());
  }

  @Test
  void failureSummary_reportsLastFailureDetails() {
    Path filePath = Path.of("/test/will-fail.txt");
    jobQueue.enqueue(List.of(filePath));
    // Drive through max retries to reach permanent FAILED state.
    for (int i = 0; i < 6; i++) {
      jobQueue.pollPending(1);
      jobQueue.markFailed(filePath, "boom-" + i);
    }

    JobQueue.FailureSummary summary = jobQueue.failureSummary();
    assertTrue(summary.failedCount() >= 1L, "At least one FAILED job expected");
    assertNotNull(summary.lastFailedPath(), "Last failed path should be set");
    assertNotNull(summary.lastFailedErrorMessage(), "Last failed error should be set");
    assertTrue(
        summary.lastFailedErrorMessage().startsWith("boom-"),
        "Last error should be one of the markFailed messages, got: " + summary.lastFailedErrorMessage());
    assertNotNull(summary.lastFailedAtMs(), "Last failed timestamp should be set");
    assertTrue(summary.lastFailedAtMs() > 0L, "Last failed timestamp should be positive");
  }

  // ============================================================================
  // latest ingestion outcome persistence
  // ============================================================================

  @Test
  void freshDbHasLatestOutcomeColumns() throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
        Statement stmt = conn.createStatement()) {
      assertTrue(hasColumn(stmt, "last_outcome_class"));
      assertTrue(hasColumn(stmt, "last_reason_code"));
      assertTrue(hasColumn(stmt, "last_retry_policy"));
      assertTrue(hasColumn(stmt, "last_diagnostic_summary"));
      assertTrue(hasColumn(stmt, "last_outcome_at"));
      assertTrue(hasTable(stmt, "ingestion_ledger"));
      assertFalse(hasLedgerColumn(stmt, "job_path"));
      assertTrue(hasLedgerColumn(stmt, "path_hash"));
    }
  }

  @Test
  void recordOutcomeUpdatesLatestOutcomeWithoutContextlessLedgerEvent() throws Exception {
    Path filePath = tempDir.resolve("latest-only.txt");
    Files.writeString(filePath, "ok");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.recordOutcome(
        filePath,
        IngestionOutcome.of(
            IngestionOutcomeClass.SKIPPED_POLICY,
            IngestionReasonCodes.UNCHANGED,
            IngestionRetryPolicy.NONE,
            "unchanged"));

    OutcomeRow row = readOutcomeViaJdbc(normalizedPath);
    assertEquals("SKIPPED_POLICY", row.outcomeClass());
    assertEquals(0L, countAllLedgerRows(), "Latest-outcome writes must not create ledger events");
  }

  @Test
  void markDoneWithOutcomePersistsLatestOutcome() throws Exception {
    Path filePath = tempDir.resolve("done-outcome.txt");
    Files.writeString(filePath, "ok");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    jobQueue.markDone(
        filePath,
        new IngestionOutcome(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE,
            "Indexed successfully",
            1234L),
        new JobQueue.IngestionLedgerEntry(
            "done-hash", "docs", 2L, 3L, "REGULAR_FILE", "SUCCESS_FULL", "policy", "parser"));

    OutcomeRow row = readOutcomeViaJdbc(normalizedPath);
    assertEquals("DONE", row.state());
    assertEquals("SUCCESS_FULL", row.outcomeClass());
    assertEquals(IngestionReasonCodes.SUCCESS, row.reasonCode());
    assertEquals("NONE", row.retryPolicy());
    assertEquals("Indexed successfully", row.diagnosticSummary());
    assertEquals(1234L, row.outcomeAt());
    assertEquals(1L, countLedgerRows("done-hash"));
    assertEquals("SUCCESS_FULL", readLedgerArtifactStatus("done-hash"));
  }

  @Test
  void outcomeTransitionRollsBackWhenLedgerInsertFails() throws Exception {
    Path filePath = tempDir.resolve("rollback-ledger.txt");
    Files.writeString(filePath, "ok");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE ingestion_ledger");
    }

    assertThrows(
        OutcomeWriteException.class,
        () ->
            jobQueue.markDone(
                filePath,
                IngestionOutcome.of(
                    IngestionOutcomeClass.SUCCESS_FULL,
                    IngestionReasonCodes.SUCCESS,
                    IngestionRetryPolicy.NONE,
                    "Indexed successfully")));

    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedPath),
        "Job state should roll back when ledger insertion fails");
  }

  @Test
  void markDoneTransitionsRollsBackWhenLedgerInsertFails() throws Exception {
    Path firstPath = tempDir.resolve("rollback-batch-a.txt");
    Path secondPath = tempDir.resolve("rollback-batch-b.txt");
    Files.writeString(firstPath, "ok-a");
    Files.writeString(secondPath, "ok-b");
    String normalizedFirst = PathNormalizer.normalizePath(firstPath.toAbsolutePath().toString());
    String normalizedSecond =
        PathNormalizer.normalizePath(secondPath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(firstPath, secondPath));
    jobQueue.pollPending(2);
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE ingestion_ledger");
    }

    IngestionOutcome outcome =
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE,
            "Indexed successfully");
    assertThrows(
        OutcomeWriteException.class,
        () ->
            jobQueue.markDoneTransitions(
                List.of(
                    new JobQueue.IngestionLedgerTransition(firstPath, null),
                    new JobQueue.IngestionLedgerTransition(secondPath, null)),
                outcome));

    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedFirst),
        "First job should roll back when ledger insertion fails");
    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedSecond),
        "Second job should roll back when ledger insertion fails");
  }

  @Test
  void markFailedRetryableRollsBackWhenLedgerInsertFails() throws Exception {
    Path filePath = tempDir.resolve("rollback-retryable.txt");
    Files.writeString(filePath, "later");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE ingestion_ledger");
    }

    assertThrows(
        OutcomeWriteException.class,
        () ->
            jobQueue.markFailed(
                filePath,
                IngestionOutcome.of(
                    IngestionOutcomeClass.IO_FAILED,
                    IngestionReasonCodes.IO_ERROR,
                    IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                    "io")));

    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedPath),
        "markFailed (retryable) should roll back when ledger insertion fails");
    assertEquals(
        0,
        readAttemptsViaJdbc(normalizedPath),
        "attempts must not increment when ledger insertion rolls back");
  }

  @Test
  void markFailedTerminalRollsBackWhenLedgerInsertFails() throws Exception {
    Path filePath = tempDir.resolve("rollback-terminal.txt");
    Files.writeString(filePath, "bad");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE ingestion_ledger");
    }

    assertThrows(
        OutcomeWriteException.class,
        () ->
            jobQueue.markFailed(
                filePath,
                IngestionOutcome.of(
                    IngestionOutcomeClass.BUDGET_EXCEEDED,
                    IngestionReasonCodes.INPUT_TOO_LARGE,
                    IngestionRetryPolicy.NONE,
                    "too large")));

    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedPath),
        "markFailed (terminal) should roll back when ledger insertion fails");
    assertEquals(
        0,
        readAttemptsViaJdbc(normalizedPath),
        "attempts must not increment when ledger insertion rolls back");
  }

  @Test
  void deferRollsBackWhenLedgerInsertFails() throws Exception {
    Path filePath = tempDir.resolve("rollback-defer.txt");
    Files.writeString(filePath, "later");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE ingestion_ledger");
    }

    assertThrows(
        OutcomeWriteException.class,
        () ->
            jobQueue.defer(
                filePath,
                IngestionOutcome.of(
                    IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING,
                    IngestionReasonCodes.WRITE_UNAVAILABLE_DRAINING,
                    IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT,
                    "draining")));

    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedPath),
        "defer should roll back when ledger insertion fails");
    assertNull(
        readRetryAfterViaJdbc(normalizedPath),
        "retry_after must remain null when ledger insertion rolls back");
  }

  @Test
  void recordIngestionEventPersistsPrivacySafeLedgerContext() throws Exception {
    Path filePath = tempDir.resolve("ledger-context.txt");
    Files.writeString(filePath, "ok");

    jobQueue.enqueue(List.of(filePath));
    jobQueue.recordIngestionEvent(
        filePath,
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_PARTIAL,
            IngestionReasonCodes.SUCCESS,
            IngestionRetryPolicy.NONE,
            "partial"),
        new JobQueue.IngestionLedgerEntry(
            "hash-123", "docs", 12L, 34L, "REGULAR_FILE", "SUCCESS_PARTIAL", "policy", "parser"));

    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement(
                """
                SELECT path_hash, collection, source_size_bytes, source_modified_at,
                       source_kind, artifact_status, policy_id, parser_id
                FROM ingestion_ledger WHERE path_hash = ?
                """)) {
      stmt.setString(1, "hash-123");
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("hash-123", rs.getString("path_hash"));
        assertEquals("docs", rs.getString("collection"));
        assertEquals(12L, rs.getLong("source_size_bytes"));
        assertEquals(34L, rs.getLong("source_modified_at"));
        assertEquals("REGULAR_FILE", rs.getString("source_kind"));
        assertEquals("SUCCESS_PARTIAL", rs.getString("artifact_status"));
        assertEquals("policy", rs.getString("policy_id"));
        assertEquals("parser", rs.getString("parser_id"));
      }
    }
  }

  @Test
  void markFailedWithNoneRetryPolicyForcesFailedState() throws Exception {
    Path filePath = tempDir.resolve("terminal.txt");
    Files.writeString(filePath, "bad");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    jobQueue.markFailed(
        filePath,
        IngestionOutcome.of(
            IngestionOutcomeClass.BUDGET_EXCEEDED,
            IngestionReasonCodes.INPUT_TOO_LARGE,
            IngestionRetryPolicy.NONE,
            "too large"));

    OutcomeRow row = readOutcomeViaJdbc(normalizedPath);
    assertEquals("FAILED", row.state());
    assertEquals("BUDGET_EXCEEDED", row.outcomeClass());
    assertEquals(IngestionReasonCodes.INPUT_TOO_LARGE, row.reasonCode());
    assertEquals("NONE", row.retryPolicy());
    assertEquals(1, readAttemptsViaJdbc(normalizedPath));
  }

  @Test
  void markFailedRejectsDeferWithoutAttemptPolicyMismatch() throws Exception {
    Path filePath = tempDir.resolve("policy-mismatch.txt");
    Files.writeString(filePath, "should-defer-not-fail");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);

    IngestionOutcome misroutedOutcome =
        IngestionOutcome.of(
            IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING,
            IngestionReasonCodes.WRITE_UNAVAILABLE_DRAINING,
            IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT,
            "draining");

    assertThrows(
        IllegalArgumentException.class,
        () -> jobQueue.markFailed(filePath, misroutedOutcome),
        "markFailed must reject outcomes with retryPolicy=DEFER_WITHOUT_ATTEMPT (caller should use defer)");

    assertEquals(
        "PROCESSING",
        readStateViaJdbc(normalizedPath),
        "Rejected mismatch must not change queue state");
    assertEquals(
        0,
        readAttemptsViaJdbc(normalizedPath),
        "Rejected mismatch must not increment attempts");
  }

  @Test
  void deferWithOutcomeDoesNotIncrementAttempts() throws Exception {
    Path filePath = tempDir.resolve("defer.txt");
    Files.writeString(filePath, "later");
    String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(filePath));
    jobQueue.pollPending(1);
    jobQueue.defer(
        filePath,
        IngestionOutcome.of(
            IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING,
            IngestionReasonCodes.WRITE_UNAVAILABLE_DRAINING,
            IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT,
            "draining"));

    OutcomeRow row = readOutcomeViaJdbc(normalizedPath);
    assertEquals("PENDING", row.state());
    assertEquals("WRITE_UNAVAILABLE_DRAINING", row.outcomeClass());
    assertEquals("DEFER_WITHOUT_ATTEMPT", row.retryPolicy());
    assertEquals(0, readAttemptsViaJdbc(normalizedPath));
  }

  // ============================================================================
  // deleteByPathPrefix / deleteByExactPath
  // ============================================================================

  @Test
  void deleteByPathPrefix_deletesMatchingJobsRegardlessOfState() {
    // Use real temp-dir paths so absolute-path normalization is platform-correct.
    Path dropDir = tempDir.resolve("drop");
    Path keepPath = tempDir.resolve("keep").resolve("file.txt");
    Path drop1 = dropDir.resolve("a.txt");
    Path drop2 = dropDir.resolve("sub").resolve("b.txt");
    jobQueue.enqueue(List.of(keepPath, drop1, drop2));

    int deleted = jobQueue.deleteByPathPrefix(dropDir.toAbsolutePath().toString());
    assertEquals(2, deleted, "Should delete both jobs under the drop/ prefix");
    assertEquals(1, jobQueue.queueDepth(), "Only the keep/ job should remain");
  }

  @Test
  void deleteByPathPrefix_blankPrefixIsRefused() {
    jobQueue.enqueue(List.of(tempDir.resolve("anywhere").resolve("file.txt")));
    assertEquals(0, jobQueue.deleteByPathPrefix(""), "Blank prefix must be rejected");
    assertEquals(0, jobQueue.deleteByPathPrefix("   "), "Whitespace-only prefix must be rejected");
    assertEquals(1, jobQueue.queueDepth(), "Nothing should be deleted");
  }

  @Test
  void deleteByExactPath_deletesOnlyMatchingJob() {
    Path target = tempDir.resolve("target.txt");
    Path other = tempDir.resolve("other.txt");
    jobQueue.enqueue(List.of(target, other));

    String normalized = PathNormalizer.normalizePath(target.toAbsolutePath().toString());
    int deleted = jobQueue.deleteByExactPath(normalized);
    assertEquals(1, deleted, "Should delete exactly 1 job");
    assertEquals(1, jobQueue.queueDepth(), "Other job should remain");
  }

  @Test
  void deleteByExactPath_noMatchReturnsZero() {
    jobQueue.enqueue(List.of(tempDir.resolve("exists.txt")));
    String missing = PathNormalizer.normalizePath(
        tempDir.resolve("does-not-exist.txt").toAbsolutePath().toString());
    assertEquals(0, jobQueue.deleteByExactPath(missing));
    assertEquals(1, jobQueue.queueDepth(), "Existing job untouched");
  }

  // ============================================================================
  // cleanupOldJobs(retentionDays)
  // ============================================================================

  @Test
  void cleanupOldJobs_deletesDoneAndFailedOlderThanRetention() throws Exception {
    Path oldDone = Path.of("/test/old-done.txt");
    Path freshDone = Path.of("/test/fresh-done.txt");
    Path oldPending = Path.of("/test/old-pending.txt");
    jobQueue.enqueue(List.of(oldDone, freshDone, oldPending));
    jobQueue.pollPending(2);
    jobQueue.markDone(oldDone);
    jobQueue.markDone(freshDone);

    // Backdate the "old" rows by 30 days so they fall outside a 7-day retention window.
    long cutoffPast = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
    String oldDoneNorm = PathNormalizer.normalizePath(oldDone.toAbsolutePath().toString());
    String oldPendingNorm = PathNormalizer.normalizePath(oldPending.toAbsolutePath().toString());
    backdateRow(oldDoneNorm, cutoffPast);
    backdateRow(oldPendingNorm, cutoffPast);

    int cleaned = jobQueue.cleanupOldJobs(7);
    // Only DONE/FAILED jobs are eligible. The backdated PENDING row must survive.
    assertEquals(1, cleaned, "Only the old DONE row should be cleaned");
    assertEquals(0L, countRowsByPath(oldDoneNorm), "Old DONE row removed");
    assertEquals(1L, countRowsByPath(oldPendingNorm), "Old PENDING row preserved");
  }

  // ============================================================================
  // performIntegrityCheck() — corruption path
  // ============================================================================

  @Test
  void performIntegrityCheck_throwsOnCorruptDatabase() throws Exception {
    // Build a real persisted DB, then close cleanly.
    jobQueue.enqueue(List.of(Path.of("/test/seed.txt")));
    jobQueue.close();

    // Corrupt the page that holds the schema by overwriting bytes well past the SQLite header.
    try (RandomAccessFile raf = new RandomAccessFile(dbPath.toFile(), "rw")) {
      long size = raf.length();
      assertTrue(size > 1024, "DB must be larger than the header for the test to be meaningful");
      raf.seek(size / 2);
      byte[] garbage = new byte[2048];
      for (int i = 0; i < garbage.length; i++) {
        garbage[i] = (byte) 0xAB;
      }
      raf.write(garbage);
    }

    // Reopen with forced integrity check (existedBeforeOpen would be true here too,
    // but openWithIntegrityCheck is the explicit path post-restore).
    SqliteJobQueue corrupted = new SqliteJobQueue(dbPath);
    SQLException thrown =
        assertThrows(
            SQLException.class,
            corrupted::openWithIntegrityCheck,
            "Corrupted DB should fail integrity check");
    // The integrity check raises a SQLITE_CORRUPT-style error. Also accept generic
    // SQLException raised before the check (e.g., during schema migration on the
    // damaged file) — both are valid signals of corruption.
    assertNotNull(thrown.getMessage());
    try {
      corrupted.close();
    } catch (Exception ignored) {
      // best-effort cleanup
    }

    // Reassign jobQueue so tearDown closes a valid object.
    Files.deleteIfExists(dbPath);
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
  }

  // ============================================================================
  // queueDbHealthSnapshot()
  // ============================================================================

  @Test
  void queueDbHealthSnapshot_freshDbReportsHealthyAndNoBackup() {
    JobQueue.QueueDbHealthSnapshot snap = jobQueue.queueDbHealthSnapshot();
    assertNotNull(snap, "Snapshot must not be null on a healthy queue");
    assertTrue(snap.healthy(), "Fresh queue should be healthy");
    // performIntegrityCheck was effectively a no-op (skipped on fresh DB) but the
    // open() path still records the timestamp + ok=true.
    assertTrue(snap.lastQuickCheckOk(), "Fresh DB should record quick_check ok=true");
    assertEquals(0L, snap.lastDbErrorAtMs(), "No DB error should be recorded on a healthy queue");
  }

  @Test
  void queueDbHealthSnapshot_reflectsIntegrityCheckTimestamp() throws Exception {
    long before = System.currentTimeMillis();
    boolean ok = jobQueue.performIntegrityCheck();
    assertTrue(ok, "Integrity check should pass on healthy DB");
    JobQueue.QueueDbHealthSnapshot snap = jobQueue.queueDbHealthSnapshot();
    assertTrue(snap.lastQuickCheckAtMs() >= before, "lastQuickCheckAtMs should advance");
    assertTrue(snap.lastQuickCheckOk(), "lastQuickCheckOk should be true after passing check");
    assertFalse(snap.healthy() == false, "DB should remain healthy after passing check");
  }

  // ============================================================================
  // Helpers for direct-DB manipulation
  // ============================================================================

  private void backdateRow(String path, long timestamp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement(
             "UPDATE jobs SET last_updated = ? WHERE path = ?")) {
      stmt.setLong(1, timestamp);
      stmt.setString(2, path);
      int updated = stmt.executeUpdate();
      if (updated != 1) {
        throw new AssertionError("Expected to update 1 row for path " + path + ", got " + updated);
      }
    }
  }

  private void backdateAllLedgerRows(long timestamp) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement(
             "UPDATE ingestion_ledger SET observed_at = ?")) {
      stmt.setLong(1, timestamp);
      int updated = stmt.executeUpdate();
      if (updated != 1) {
        throw new AssertionError("Expected to update 1 ledger row, got " + updated);
      }
    }
  }

  private long countAllLedgerRows() throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ingestion_ledger")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long countRowsByPath(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement(
             "SELECT COUNT(*) FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long countLedgerRows(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement(
             "SELECT COUNT(*) FROM ingestion_ledger WHERE path_hash = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String readLedgerArtifactStatus(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement(
             "SELECT artifact_status FROM ingestion_ledger WHERE path_hash = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private String readStateViaJdbc(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement("SELECT state FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private OutcomeRow readOutcomeViaJdbc(String path) throws Exception {
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         PreparedStatement stmt = conn.prepareStatement(
             """
             SELECT state, last_outcome_class, last_reason_code, last_retry_policy,
                    last_diagnostic_summary, last_outcome_at
             FROM jobs WHERE path = ?
             """)) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        if (rs.next()) {
          return new OutcomeRow(
              rs.getString(1),
              rs.getString(2),
              rs.getString(3),
              rs.getString(4),
              rs.getString(5),
              rs.getLong(6));
        }
        throw new AssertionError("Job not found for path: " + path);
      }
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

  private static boolean hasLedgerColumn(Statement stmt, String columnName) throws SQLException {
    try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(ingestion_ledger)")) {
      while (rs.next()) {
        if (columnName.equals(rs.getString("name"))) {
          return true;
        }
      }
    }
    return false;
  }

  private record OutcomeRow(
      String state,
      String outcomeClass,
      String reasonCode,
      String retryPolicy,
      String diagnosticSummary,
      long outcomeAt) {}
}
