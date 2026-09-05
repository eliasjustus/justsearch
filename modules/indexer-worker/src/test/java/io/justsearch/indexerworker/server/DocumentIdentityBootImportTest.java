/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.UpdatePathsRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
@DisplayName("KnowledgeServer document identity bootstrap")
final class DocumentIdentityBootImportTest {

  private KnowledgeServer server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
    }
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort
      }
      server = null;
    }
  }

  @Test
  @DisplayName("first V11 boot imports parent identities from the serving index")
  void firstBootImportsExistingParentUids(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      for (int i = 0; i < 3; i++) {
        String normalized = PathNormalizer.normalizeKey(Path.of("seed-" + i));
        var identity = probe.lookup(DocumentIdentityStore.pathHash(normalized)).orElseThrow();
        assertEquals("seed-" + i + "#0", identity.docUid());
      }
    }
  }

  @Test
  @DisplayName("the recorded generation import stops the next boot from re-scanning the index")
  void secondBootOverTheSameGenerationDoesNotRescan(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    Path dbPath = layout.dataDir().resolve("jobs.db");
    String generationId =
        layout.genManager().readStateBestEffort().active_generation();

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    server.close();
    server = null;

    assertEquals(List.of(3L, 3L, 0L), importRow(dbPath, generationId));

    // A parent that appears in the serving index AFTER the recorded import is exactly what the
    // guard must not go looking for: admission is the only path that mints identity from here on.
    WorkerBootFixture.seedDocument(
        layout.activePath(), null, "after-import.txt", "after-import-uid", "added post-import");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertEquals(List.of(3L, 3L, 0L), importRow(dbPath, generationId));
    try (SqliteDocumentIdentityStore probe = new SqliteDocumentIdentityStore(dbPath)) {
      String normalized = PathNormalizer.normalizeKey(Path.of("after-import.txt"));
      assertTrue(
          probe.lookup(DocumentIdentityStore.pathHash(normalized)).isEmpty(),
          "the second boot must not have walked the index again");
      assertEquals(3L, probe.identityCount());
    }
  }

  /** Reads (parents_seen, parents_imported, parents_skipped) for one generation. */
  private static List<Long> importRow(Path dbPath, String generationId) throws Exception {
    try (var conn =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toString().replace('\\', '/'));
        var stmt =
            conn.prepareStatement(
                "SELECT parents_seen, parents_imported, parents_skipped"
                    + " FROM document_identity_import WHERE generation_id = ?")) {
      stmt.setString(1, generationId);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "no import row recorded for generation " + generationId);
        return List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3));
      }
    }
  }

  @Test
  @DisplayName("a stale serving path cannot reverse an already persisted rename")
  void staleServingIndexDoesNotUndoStoreAuthoritativeRename(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    Path dbPath = layout.dataDir().resolve("jobs.db");
    try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
      queue.open();
    }

    String oldPath = PathNormalizer.normalizeKey(Path.of("seed-0"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("renamed-seed-0"));
    String uid = "seed-0#0";
    try (SqliteDocumentIdentityStore store = new SqliteDocumentIdentityStore(dbPath)) {
      store.importExisting(DocumentIdentityStore.pathHash(newPath), uid, 10L);
    }
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    try (SqliteDocumentIdentityStore probe = new SqliteDocumentIdentityStore(dbPath)) {
      assertTrue(
          probe.lookup(DocumentIdentityStore.pathHash(oldPath)).isEmpty(),
          "a stale Blue snapshot must not move the uid back to its pre-rename path");
      assertEquals(
          uid,
          probe.lookup(DocumentIdentityStore.pathHash(newPath)).orElseThrow().docUid());
    }
  }

  @Test
  @DisplayName("Blue identity is imported before a paused migration reindexes into Green")
  void blueUidIsImportedBeforePausedMigrationReindexesIntoGreen(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    Path root = Files.createDirectories(tempDir.resolve("documents"));
    Path source = root.resolve("continuity.txt");
    Files.writeString(source, "identity continuity through migration ".repeat(400));
    String docId = PathNormalizer.normalizeKey(source);
    String blueUid = "00000000-0000-4000-8000-0000000000aa";
    String wrongGreenUid = "00000000-0000-4000-8000-0000000000bb";

    WorkerBootFixture.seedDocument(
        layout.activePath(), null, docId, blueUid, "old Blue content");
    var migration = layout.genManager().startMigration("identity_continuity_test");
    Path greenPath =
        layout.genManager().resolveGenerationPathStrict(migration.building_generation());
    WorkerBootFixture.seedDocument(greenPath, null, docId, wrongGreenUid, "stale Green content");
    layout.genManager().setMigrationPaused(true, "identity continuity assertion");
    Files.writeString(
        layout.dataDir().resolve("watched_roots.json"),
        new tools.jackson.databind.ObjectMapper().writeValueAsString(List.of(root.toString())));
    WorkerBootFixture.publishConfig(
        layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    server.releaseModelReadyLatchForTests();

    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      assertEquals(
          blueUid,
          probe.lookup(DocumentIdentityStore.pathHash(docId)).orElseThrow().docUid(),
          "the serving Blue index must seed identity authority before enumeration starts");
    }
    assertEquals(
        wrongGreenUid,
        server
            .lifecycleManagerForTests()
            .documentFieldOps()
            .getDocumentField(docId, SchemaFields.DOC_UID),
        "the pause must hold Green at its adverse pre-reindex identity");

    Path renamed = root.resolve("continuity-renamed.txt");
    Files.move(source, renamed);
    String renamedDocId = PathNormalizer.normalizeKey(renamed);
    channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    var renamedResponse =
        IngestServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .updateDocumentPaths(
                UpdatePathsRequest.newBuilder()
                    .addMappings(
                        PathMapping.newBuilder()
                            .setOldPath(docId)
                            .setNewPath(renamedDocId)
                            .build())
                    .build());
    assertEquals(1, renamedResponse.getUpdatedCount());
    assertTrue(renamedResponse.getFailedPathsList().isEmpty());
    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      assertTrue(probe.lookup(DocumentIdentityStore.pathHash(docId)).isEmpty());
      assertEquals(
          blueUid,
          probe
              .lookup(DocumentIdentityStore.pathHash(renamedDocId))
              .orElseThrow()
              .docUid(),
          "the production gRPC service must be wired to the durable identity store");
    }
    assertEquals(
        wrongGreenUid,
        server
            .lifecycleManagerForTests()
            .documentFieldOps()
            .getDocumentField(renamedDocId, SchemaFields.DOC_UID),
        "the supported rename moves Green's adverse row without rewriting its uid");

    server.indexGenerationManagerForTests().setMigrationPaused(false, null);
    awaitGreenUid(layout, renamedDocId, blueUid, Duration.ofSeconds(15));

    var documents =
        server
            .lifecycleManagerForTests()
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                100,
                Set.of(
                    SchemaFields.DOC_UID,
                    SchemaFields.IS_CHUNK,
                    SchemaFields.PARENT_DOC_ID,
                    SchemaFields.CHUNK_INDEX),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null);
    int chunks = 0;
    for (var hit : documents.hits()) {
      if (!"true".equals(hit.fields().get(SchemaFields.IS_CHUNK))) {
        continue;
      }
      assertEquals(renamedDocId, hit.fields().get(SchemaFields.PARENT_DOC_ID));
      assertEquals(
          blueUid + "#" + hit.fields().get(SchemaFields.CHUNK_INDEX),
          hit.fields().get(SchemaFields.DOC_UID));
      chunks++;
    }
    assertTrue(chunks > 1, "ordinary migration indexing must derive chunks from the parent uid");
  }

  @Test
  @DisplayName("a pending boot job resolves identity only after Blue import establishes authority")
  void pendingJobAtBootReusesBlueUidBeforeMigrationEnumeration(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    Path source = tempDir.resolve("pending-before-boot.txt");
    Files.writeString(source, "pending boot identity continuity");
    String docId = PathNormalizer.normalizeKey(source);
    String blueUid = "00000000-0000-4000-8000-0000000000cc";

    WorkerBootFixture.seedDocument(layout.activePath(), null, docId, blueUid, "Blue authority");
    layout.genManager().startMigration("pending_boot_identity_test");
    layout.genManager().setMigrationPaused(true, "exclude migration enumeration from assertion");
    try (SqliteJobQueue queue =
        new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) {
      queue.open();
      assertEquals(1, queue.enqueue(List.of(source)));
    }
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    awaitGreenUid(layout, docId, blueUid, Duration.ofSeconds(15));
    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      assertEquals(
          blueUid,
          probe.lookup(DocumentIdentityStore.pathHash(docId)).orElseThrow().docUid());
    }
  }

  private void awaitGreenUid(
      WorkerBootFixture.Layout layout, String docId, String expectedUid, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    String observed = null;
    while (System.nanoTime() < deadline) {
      observed =
          server
              .lifecycleManagerForTests()
              .documentFieldOps()
              .getDocumentField(docId, SchemaFields.DOC_UID);
      if (expectedUid.equals(observed)) {
        return;
      }
      Thread.sleep(100L);
    }
    assertEquals(
        expectedUid,
        observed,
        "Green did not reuse the durable Blue identity before the bounded timeout; state="
            + server.indexGenerationManagerForTests().readStateBestEffort()
            + ", queue="
            + server.jobQueueForTests().jobStateCounts()
            + ", failures="
            + server.jobQueueForTests().failureSummary());
  }
}
