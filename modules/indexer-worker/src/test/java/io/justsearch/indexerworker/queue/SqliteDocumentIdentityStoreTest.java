/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
  }
}
