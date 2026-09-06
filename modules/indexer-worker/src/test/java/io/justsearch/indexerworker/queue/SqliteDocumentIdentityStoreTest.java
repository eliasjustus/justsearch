/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteDocumentIdentityStoreTest {
  @TempDir Path tempDir;

  private Path dbPath;
  private SqliteJobQueue jobQueue;
  private SqliteDocumentIdentityStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    store = new SqliteDocumentIdentityStore(dbPath);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (store != null) store.close();
    if (jobQueue != null) jobQueue.close();
  }

  @Test
  void resolveMintsOnceAndRefreshesOnlyLastSeen() {
    var first = store.resolve("hash-a", 100L);
    var second = store.resolve("hash-a", 200L);

    UUID.fromString(first.docUid());
    assertEquals(first.docUid(), second.docUid());
    assertEquals(100L, second.firstSeenAtMs());
    assertEquals(200L, second.lastSeenAtMs());
  }

  @Test
  void identitySurvivesClosingAndReopeningTheConnection() throws Exception {
    var first = store.resolve("hash-a", 100L);
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath);

    var reopened = store.lookup("hash-a").orElseThrow();
    assertEquals(first.docUid(), reopened.docUid());
    assertEquals(first.firstSeenAtMs(), reopened.firstSeenAtMs());
  }

  @Test
  void distinctPathHashesReceiveDistinctContentIndependentUids() {
    var first = store.resolve("same-content-path-a", 100L);
    var second = store.resolve("same-content-path-b", 100L);

    assertNotEquals(first.docUid(), second.docUid());
  }

  @Test
  void rekeyPreservesUidAndFirstSeenAndReplacesHistoricalDestination() {
    var source = store.resolve("old-hash", 100L);
    store.resolve("historical-destination", 50L);

    assertEquals(
        DocumentIdentityStore.RekeyResult.MOVED,
        store.rekey("old-hash", "historical-destination", 300L));

    assertTrue(store.lookup("old-hash").isEmpty());
    var moved = store.lookup("historical-destination").orElseThrow();
    assertEquals(source.docUid(), moved.docUid());
    assertEquals(100L, moved.firstSeenAtMs());
    assertEquals(300L, moved.lastSeenAtMs());
    assertEquals(
        DocumentIdentityStore.RekeyResult.ALREADY_AT_DESTINATION,
        store.rekey("old-hash", "historical-destination", 400L));
    assertEquals(source.docUid(), store.lookup("historical-destination").orElseThrow().docUid());
  }

  @Test
  void rekeyOfUnknownPathIsANoopForMixedDirectoryMoveBatches() {
    assertEquals(
        DocumentIdentityStore.RekeyResult.NOT_FOUND,
        store.rekey("never-admitted", "also-unknown", 100L));
    assertTrue(store.lookup("never-admitted").isEmpty());
    assertTrue(store.lookup("also-unknown").isEmpty());
  }

  @Test
  void activeIndexImportSeedsEmptyStoreButDoesNotOverrideExistingAuthority() {
    var imported = store.importExisting("hash-a", "index-uid-a", 100L);
    assertEquals("index-uid-a", imported.docUid());

    var existing = store.importExisting("hash-a", "stale-index-uid", 200L);
    assertEquals("index-uid-a", existing.docUid());

    var sameUidAtStalePath = store.importExisting("stale-hash", "index-uid-a", 200L);
    assertEquals("hash-a", sameUidAtStalePath.pathHash());
    assertTrue(store.lookup("stale-hash").isEmpty());
  }

  @Test
  void bulkImportRollsBackWhenAnyEntryIsInvalid() {
    var rows =
        List.of(
            new DocumentIdentityStore.ImportedIdentity("valid-hash", "valid-uid"),
            new DocumentIdentityStore.ImportedIdentity("invalid-hash", ""));

    assertThrows(IllegalArgumentException.class, () -> store.importExisting(rows, 100L));
    assertTrue(store.lookup("valid-hash").isEmpty());
  }

  @Test
  void bulkImportCountsOnlyNewlyInsertedRows() {
    assertEquals(
        2,
        store.importExisting(
            List.of(
                new DocumentIdentityStore.ImportedIdentity("hash-a", "uid-a"),
                new DocumentIdentityStore.ImportedIdentity("hash-b", "uid-b")),
            100L));
    assertEquals(
        1,
        store.importExisting(
            List.of(
                new DocumentIdentityStore.ImportedIdentity("hash-a", "uid-a"),
                new DocumentIdentityStore.ImportedIdentity("hash-c", "uid-c")),
            200L));
    assertEquals(3L, store.identityCount());
  }

  @Test
  void importRecordIsScopedToItsGenerationAndRestatable() throws IOException {
    assertFalse(store.hasImportRecord("g-1"));

    store.recordImport(new DocumentIdentityStore.ImportRecord("g-1", 100L, 5, 4, 1));
    assertTrue(store.hasImportRecord("g-1"));
    assertFalse(store.hasImportRecord("g-2"));

    store.recordImport(new DocumentIdentityStore.ImportRecord("g-1", 300L, 9, 4, 0));
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath);
    assertTrue(store.hasImportRecord("g-1"));
  }

  @Test
  void unavailableStoreFailsClosed() {
    assertThrows(
        IllegalStateException.class,
        () -> DocumentIdentityStore.UNAVAILABLE.resolve("hash", 100L));
    assertThrows(
        IllegalStateException.class,
        () -> DocumentIdentityStore.UNAVAILABLE.markDeleted("hash", 100L));
  }

  // ==================== Deletion grace (tempdoc 931 §C.6) ====================

  @Test
  void aFileReappearingWithinTheDeletionGraceKeepsItsUidAndItsFeedback() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);
    var original = store.resolve("hash-a", 1_000L);

    store.markDeleted("hash-a", 2_000L);
    assertEquals(2_000L, store.lookup("hash-a").orElseThrow().deletedAtMs());

    var returned = store.resolve("hash-a", 2_000L + GRACE_MS);

    assertEquals(original.docUid(), returned.docUid(), "a temporary absence is the same document");
    assertEquals(1_000L, returned.firstSeenAtMs(), "first-seen survives a temporary absence");
    assertNull(
        store.lookup("hash-a").orElseThrow().deletedAtMs(),
        "the mark is cleared, so a later deletion starts a fresh window");
  }

  @Test
  void aFileAppearingAfterTheDeletionGraceIsANewDocumentWithANewUid() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);
    var original = store.resolve("hash-a", 1_000L);

    store.markDeleted("hash-a", 2_000L);
    var replacement = store.resolve("hash-a", 2_000L + GRACE_MS + 1L);

    assertNotEquals(
        original.docUid(),
        replacement.docUid(),
        "a replacement past the grace window must not inherit the old document's feedback");
    UUID.fromString(replacement.docUid());
    assertEquals(2_000L + GRACE_MS + 1L, replacement.firstSeenAtMs());
    assertNull(store.lookup("hash-a").orElseThrow().deletedAtMs());
    assertEquals(1L, store.identityCount(), "the re-mint reuses the row rather than adding one");
  }

  @Test
  void onlyTheFirstConfirmedDeletionOwnsTheGraceClock() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);
    var original = store.resolve("hash-a", 1_000L);

    store.markDeleted("hash-a", 2_000L);
    // A second sweep re-observing the same absence must not push the window forward, or a
    // periodically-running prune would keep a deleted path's identity alive indefinitely.
    store.markDeleted("hash-a", 2_000L + GRACE_MS);
    assertEquals(2_000L, store.lookup("hash-a").orElseThrow().deletedAtMs());

    assertNotEquals(
        original.docUid(), store.resolve("hash-a", 2_000L + GRACE_MS + 1L).docUid());
  }

  @Test
  void markingAnUnknownPathRecordsNothing() {
    store.markDeleted("never-admitted", 100L);
    assertTrue(store.lookup("never-admitted").isEmpty());
  }

  @Test
  void aVerifiedMoveClearsTheDeletionMark() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);
    var source = store.resolve("old-hash", 1_000L);
    store.markDeleted("old-hash", 2_000L);

    assertEquals(
        DocumentIdentityStore.RekeyResult.MOVED, store.rekey("old-hash", "new-hash", 3_000L));

    var moved = store.lookup("new-hash").orElseThrow();
    assertEquals(source.docUid(), moved.docUid());
    assertNull(moved.deletedAtMs(), "a verified move proves the document still exists");
    // Past the window the uid must still be the moved one: the mark is gone, not merely ignored.
    assertEquals(
        source.docUid(), store.resolve("new-hash", 3_000L + GRACE_MS * 10).docUid());
  }

  @Test
  void theDeletionMarkSurvivesReopeningTheConnection() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);
    var original = store.resolve("hash-a", 1_000L);
    store.markDeleted("hash-a", 2_000L);

    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);

    assertEquals(2_000L, store.lookup("hash-a").orElseThrow().deletedAtMs());
    assertNotEquals(
        original.docUid(), store.resolve("hash-a", 2_000L + GRACE_MS + 1L).docUid());
  }

  @Test
  void anUnmarkedRowResolvesWithoutTouchingTheGraceWindow() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, GRACE_MS);
    var first = store.resolve("hash-a", 1_000L);

    // Far past any window, but nothing ever confirmed a deletion: the uid must not change.
    var later = store.resolve("hash-a", 1_000L + GRACE_MS * 100);

    assertEquals(first.docUid(), later.docUid());
    assertEquals(1_000L, later.firstSeenAtMs());
    assertNull(later.deletedAtMs());
  }

  @Test
  void theDefaultGraceIsThirtyDaysAndANegativeConfigurationIsClamped() throws IOException {
    store.close();
    store = new SqliteDocumentIdentityStore(dbPath);
    assertEquals(
        io.justsearch.configuration.resolved.ResolvedConfig.Index
            .DEFAULT_IDENTITY_DELETION_GRACE_MS,
        store.deletionGraceMs());
    assertEquals(2_592_000_000L, store.deletionGraceMs());

    store.close();
    store = new SqliteDocumentIdentityStore(dbPath, -1L);
    assertEquals(0L, store.deletionGraceMs());
    var original = store.resolve("hash-a", 1_000L);
    store.markDeleted("hash-a", 2_000L);
    assertNotEquals(
        original.docUid(),
        store.resolve("hash-a", 2_001L).docUid(),
        "a zero window still re-mints only AFTER the deletion instant, never at it");
  }

  /** A grace window short enough to step over in a test, long enough to step inside. */
  private static final long GRACE_MS = 10_000L;
}
