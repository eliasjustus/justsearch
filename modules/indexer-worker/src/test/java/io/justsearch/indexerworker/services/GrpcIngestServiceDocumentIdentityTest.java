/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GrpcIngestService document identity")
final class GrpcIngestServiceDocumentIdentityTest {

  @TempDir Path tempDir;

  private SqliteJobQueue jobQueue;
  private SqliteDocumentIdentityStore identityStore;
  private RunningRuntime runtime;

  @AfterEach
  void tearDown() throws Exception {
    if (identityStore != null) {
      identityStore.close();
    }
    if (runtime != null) {
      runtime.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  @DisplayName("rename rekeys the store and preserves parent and chunk uids")
  void renameRekeysStoreAndPreservesEveryUid() throws Exception {
    openStoresAndRuntime();

    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("old-report.pdf"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("renamed-report.pdf"));
    String oldHash = DocumentIdentityStore.pathHash(oldPath);
    String newHash = DocumentIdentityStore.pathHash(newPath);
    String parentUid = "00000000-0000-4000-8000-000000000077";
    String parentContent = "alpha beta";
    identityStore.importExisting(oldHash, parentUid, 10L);

    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    oldPath,
                    SchemaFields.DOC_UID,
                    parentUid,
                    SchemaFields.PATH,
                    oldPath,
                    SchemaFields.FILENAME,
                    "old-report.pdf",
                    SchemaFields.CONTENT,
                    parentContent)));
    int[] chunkStarts = {0, 6};
    int[] chunkEnds = {5, parentContent.length()};
    for (int i = 0; i < 2; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      "chunk:test-" + i,
                      SchemaFields.DOC_UID,
                      parentUid + "#" + i,
                      SchemaFields.PATH,
                      oldPath,
                      SchemaFields.IS_CHUNK,
                      "true",
                      SchemaFields.PARENT_DOC_ID,
                      oldPath,
                      SchemaFields.CHUNK_INDEX,
                      i,
                      SchemaFields.CHUNK_TOTAL,
                      2,
                      SchemaFields.CHUNK_START_CHAR,
                      chunkStarts[i],
                      SchemaFields.CHUNK_END_CHAR,
                      chunkEnds[i],
                      SchemaFields.CHUNK_CONTENT,
                      parentContent.substring(chunkStarts[i], chunkEnds[i]))));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    GrpcIngestService service =
        new GrpcIngestService(
            jobQueue,
            null,
            null,
            IndexingPacing.unthrottled(),
            tempDir.resolve("index-base"),
            tempDir.resolve("index"),
            runtime,
            runtime,
            null,
            0L,
            null);
    service.setDocumentIdentityStore(identityStore);

    RpcResult result = rename(service, oldPath, newPath);

    assertEquals(null, result.error());
    assertNotNull(result.response());
    assertEquals(1, result.response().getUpdatedCount());
    assertTrue(result.response().getFailedPathsList().isEmpty());
    assertTrue(identityStore.lookup(oldHash).isEmpty());
    DocumentIdentityStore.Identity moved = identityStore.lookup(newHash).orElseThrow();
    assertEquals(parentUid, moved.docUid());
    assertEquals(10L, moved.firstSeenAtMs());

    runtime.commitOps().maybeRefreshBlocking();
    var searchResult =
        runtime
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                10,
                Set.of(
                    SchemaFields.DOC_UID,
                    SchemaFields.PATH,
                    SchemaFields.PARENT_DOC_ID,
                    SchemaFields.IS_CHUNK),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null);
    assertEquals(3, searchResult.hits().size());
    ArrayList<String> chunkUids = new ArrayList<>();
    for (var hit : searchResult.hits()) {
      assertEquals(newPath, hit.fields().get(SchemaFields.PATH));
      if ("true".equals(hit.fields().get(SchemaFields.IS_CHUNK))) {
        assertEquals(newPath, hit.fields().get(SchemaFields.PARENT_DOC_ID));
        chunkUids.add(hit.fields().get(SchemaFields.DOC_UID));
      } else {
        assertEquals(newPath, hit.docId());
        assertEquals(parentUid, hit.fields().get(SchemaFields.DOC_UID));
      }
    }
    assertEquals(Set.of(parentUid + "#0", parentUid + "#1"), Set.copyOf(chunkUids));
    assertFalse(chunkUids.isEmpty());

    RpcResult replay = rename(service, oldPath, newPath);
    assertEquals(null, replay.error());
    assertNotNull(replay.response());
    assertEquals(1, replay.response().getUpdatedCount(), "a lost-response retry must converge");
    assertTrue(replay.response().getFailedPathsList().isEmpty());
    assertEquals(parentUid, identityStore.lookup(newHash).orElseThrow().docUid());
  }

  @Test
  @DisplayName("delete followed by same-path reindex preserves the uid")
  void deleteAndReindexPreservesUid() throws Exception {
    openStoresAndRuntime();
    String path = PathNormalizer.normalizeKey(tempDir.resolve("delete-reindex.txt"));
    String hash = DocumentIdentityStore.pathHash(path);

    DocumentIdentityStore.Identity first = identityStore.resolve(hash, 20L);
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    path,
                    SchemaFields.DOC_UID,
                    first.docUid(),
                    SchemaFields.PATH,
                    path,
                    SchemaFields.CONTENT,
                    "first version")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    DeleteRpcResult deleted = delete(service(runtime), path);
    assertEquals(null, deleted.error());
    assertNotNull(deleted.response());
    assertTrue(deleted.response().getSuccess(), deleted.response().getError());
    runtime.commitOps().maybeRefreshBlocking();
    assertTrue(
        runtime
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                10,
                Set.of(SchemaFields.DOC_UID),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null)
            .hits()
            .isEmpty());

    DocumentIdentityStore.Identity afterDelete = identityStore.resolve(hash, 30L);
    assertEquals(first.docUid(), afterDelete.docUid());
    assertEquals(first.firstSeenAtMs(), afterDelete.firstSeenAtMs());
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    path,
                    SchemaFields.DOC_UID,
                    afterDelete.docUid(),
                    SchemaFields.PATH,
                    path,
                    SchemaFields.CONTENT,
                    "second version")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var reindexed =
        runtime
            .readPathOps()
            .search(
                new MatchAllDocsQuery(),
                10,
                Set.of(SchemaFields.DOC_UID),
                LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                null);
    assertEquals(1, reindexed.hits().size());
    assertEquals(first.docUid(), reindexed.hits().getFirst().fields().get(SchemaFields.DOC_UID));
  }

  @Test
  @DisplayName("an identity-only move succeeds while Green has no Lucene document")
  void identityOnlyMoveIsReportedAsSuccess() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("blue-only.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("blue-only-renamed.txt"));
    String uid = "00000000-0000-4000-8000-000000000099";
    identityStore.importExisting(DocumentIdentityStore.pathHash(oldPath), uid, 40L);
    GrpcIngestService service = service(runtime);

    RpcResult result = rename(service, oldPath, newPath);

    assertEquals(null, result.error());
    assertNotNull(result.response());
    assertEquals(1, result.response().getUpdatedCount());
    assertTrue(result.response().getFailedPathsList().isEmpty());
    assertTrue(identityStore.lookup(DocumentIdentityStore.pathHash(oldPath)).isEmpty());
    assertEquals(
        uid,
        identityStore.lookup(DocumentIdentityStore.pathHash(newPath)).orElseThrow().docUid());
  }

  @Test
  @DisplayName("blank rename paths fail without mutating identity authority")
  void blankRenamePathsFailBeforeNormalization() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("blank-guard-old.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("blank-guard-new.txt"));
    String uid = "00000000-0000-4000-8000-000000000101";
    String oldHash = DocumentIdentityStore.pathHash(oldPath);
    identityStore.importExisting(oldHash, uid, 60L);
    GrpcIngestService service = service(runtime);

    RpcResult blankOld = rename(service, "   ", newPath);
    assertEquals(null, blankOld.error());
    assertEquals(0, blankOld.response().getUpdatedCount());
    assertEquals(Set.of("   "), Set.copyOf(blankOld.response().getFailedPathsList()));

    RpcResult blankNew = rename(service, oldPath, "   ");
    assertEquals(null, blankNew.error());
    assertEquals(0, blankNew.response().getUpdatedCount());
    assertEquals(Set.of(oldPath), Set.copyOf(blankNew.response().getFailedPathsList()));
    assertEquals(uid, identityStore.lookup(oldHash).orElseThrow().docUid());
    assertTrue(identityStore.lookup(DocumentIdentityStore.pathHash(newPath)).isEmpty());
  }

  @Test
  @DisplayName("a Lucene failure is reported while the store keeps the non-reminted identity")
  void luceneFailureLeavesStoreReadyForRetry() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("retry-old.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("retry-new.txt"));
    String uid = "00000000-0000-4000-8000-000000000100";
    identityStore.importExisting(DocumentIdentityStore.pathHash(oldPath), uid, 50L);
    GrpcIngestService service = service(runtime);
    runtime.close();
    runtime = null;

    RpcResult result = rename(service, oldPath, newPath);

    assertNotNull(result.error(), "the RPC must report the incomplete Lucene half");
    assertTrue(identityStore.lookup(DocumentIdentityStore.pathHash(oldPath)).isEmpty());
    assertEquals(
        uid,
        identityStore.lookup(DocumentIdentityStore.pathHash(newPath)).orElseThrow().docUid());
  }

  @Test
  @DisplayName("rename is refused during cutover before either identity or Lucene mutates")
  void renameFailsClosedDuringSwitchingBeforeMutation() throws Exception {
    openStoresAndRuntime();
    String oldPath = PathNormalizer.normalizeKey(tempDir.resolve("switching-old.txt"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("switching-new.txt"));
    String oldHash = DocumentIdentityStore.pathHash(oldPath);
    String newHash = DocumentIdentityStore.pathHash(newPath);
    String uid = "00000000-0000-4000-8000-000000000102";
    identityStore.importExisting(oldHash, uid, 70L);
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID,
                    oldPath,
                    SchemaFields.DOC_UID,
                    uid,
                    SchemaFields.PATH,
                    oldPath,
                    SchemaFields.CONTENT,
                    "cutover-safe rename")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    GrpcIngestService service = switchingService(runtime);
    RpcResult result = rename(service, oldPath, newPath);

    assertNotNull(result.error());
    assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(result.error()).getCode());
    assertEquals(uid, identityStore.lookup(oldHash).orElseThrow().docUid());
    assertTrue(identityStore.lookup(newHash).isEmpty());
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(uid, runtime.documentFieldOps().getDocumentField(oldPath, SchemaFields.DOC_UID));
    assertEquals(null, runtime.documentFieldOps().getDocumentField(newPath, SchemaFields.DOC_UID));
  }

  private void openStoresAndRuntime() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
    identityStore = new SqliteDocumentIdentityStore(dbPath);
    runtime =
        io.justsearch.adapters.lucene.runtime.IndexSchema
            .fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(tempDir.resolve("lucene"))
            .open();
  }

  private GrpcIngestService service(RunningRuntime activeRuntime) {
    GrpcIngestService service =
        new GrpcIngestService(
            jobQueue,
            null,
            null,
            IndexingPacing.unthrottled(),
            tempDir.resolve("index-base"),
            tempDir.resolve("index"),
            activeRuntime,
            activeRuntime,
            null,
            0L,
            null);
    service.setDocumentIdentityStore(identityStore);
    return service;
  }

  private GrpcIngestService switchingService(RunningRuntime activeRuntime) throws Exception {
    Path indexBase = Files.createDirectories(tempDir.resolve("switching-index-base"));
    Files.writeString(
        indexBase.resolve("state.json"),
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-building",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis()));
    Path ingestPath = Files.createDirectories(indexBase.resolve("indices").resolve("g-building"));
    GrpcIngestService service =
        new GrpcIngestService(
            jobQueue,
            null,
            null,
            IndexingPacing.unthrottled(),
            indexBase,
            ingestPath,
            activeRuntime,
            activeRuntime,
            null,
            0L,
            null);
    service.setDocumentIdentityStore(identityStore);
    return service;
  }

  private static RpcResult rename(GrpcIngestService service, String oldPath, String newPath) {
    AtomicReference<UpdatePathsResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    service.updateDocumentPaths(
        UpdatePathsRequest.newBuilder()
            .addMappings(
                PathMapping.newBuilder().setOldPath(oldPath).setNewPath(newPath).build())
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(UpdatePathsResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable value) {
            error.set(value);
          }

          @Override
          public void onCompleted() {}
        });
    return new RpcResult(response.get(), error.get());
  }

  private static DeleteRpcResult delete(GrpcIngestService service, String docId) {
    AtomicReference<DeleteByIdResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    service.deleteById(
        DeleteByIdRequest.newBuilder().setDocId(docId).build(),
        new StreamObserver<>() {
          @Override
          public void onNext(DeleteByIdResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable value) {
            error.set(value);
          }

          @Override
          public void onCompleted() {}
        });
    return new DeleteRpcResult(response.get(), error.get());
  }

  private record RpcResult(UpdatePathsResponse response, Throwable error) {}

  private record DeleteRpcResult(DeleteByIdResponse response, Throwable error) {}
}
