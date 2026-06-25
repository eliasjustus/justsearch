package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.ipc.ListAllDocumentIdsRequest;
import io.justsearch.ipc.ListAllDocumentIdsResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GrpcSearchService listAllDocumentIds endpoint")
class GrpcSearchServiceListAllDocumentIdsTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private GrpcSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
    service = new GrpcSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  private void indexDoc(String docId, String path) throws Exception {
    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.PATH, path,
                SchemaFields.CONTENT, "content of " + docId)));
  }

  private ListAllDocumentIdsResponse callListAll(int offset, int limit) {
    AtomicReference<ListAllDocumentIdsResponse> result = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    service.listAllDocumentIds(
        ListAllDocumentIdsRequest.newBuilder().setOffset(offset).setLimit(limit).build(),
        new StreamObserver<ListAllDocumentIdsResponse>() {
          @Override
          public void onNext(ListAllDocumentIdsResponse value) {
            result.set(value);
          }

          @Override
          public void onError(Throwable t) {
            error.set(t);
          }

          @Override
          public void onCompleted() {}
        });
    if (error.get() != null) {
      fail("listAllDocumentIds returned error: " + error.get().getMessage());
    }
    return result.get();
  }

  @Nested
  @DisplayName("empty index")
  class EmptyIndex {

    @Test
    @DisplayName("returns zero IDs and zero total when index is empty")
    void emptyIndex_returnsZero() {
      ListAllDocumentIdsResponse resp = callListAll(0, 100);
      assertEquals(0, resp.getDocIdsCount());
      assertEquals(0, resp.getTotalCount());
    }
  }

  @Nested
  @DisplayName("basic listing")
  class BasicListing {

    @Test
    @DisplayName("returns all indexed parent document IDs")
    void returnsAllDocIds() throws Exception {
      indexDoc("doc-1", "C:/docs/file1.txt");
      indexDoc("doc-2", "C:/docs/file2.txt");
      indexDoc("doc-3", "C:/docs/file3.txt");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      ListAllDocumentIdsResponse resp = callListAll(0, 100);

      assertEquals(3, resp.getTotalCount());
      assertEquals(3, resp.getDocIdsCount());
      List<String> ids = resp.getDocIdsList();
      assertTrue(ids.contains("doc-1"));
      assertTrue(ids.contains("doc-2"));
      assertTrue(ids.contains("doc-3"));
    }

    @Test
    @DisplayName("tookMs is non-negative")
    void tookMs_isNonNegative() throws Exception {
      indexDoc("doc-1", "C:/docs/file1.txt");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      ListAllDocumentIdsResponse resp = callListAll(0, 100);
      assertTrue(resp.getTookMs() >= 0);
    }
  }

  @Nested
  @DisplayName("pagination")
  class Pagination {

    @Test
    @DisplayName("offset and limit page correctly; totalCount reflects full corpus size")
    void offsetAndLimit_pagesCorrectly() throws Exception {
      for (int i = 1; i <= 5; i++) {
        indexDoc("doc-" + i, "C:/docs/file" + i + ".txt");
      }
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // Page 1: offset=0, limit=2
      ListAllDocumentIdsResponse page1 = callListAll(0, 2);
      assertEquals(5, page1.getTotalCount());
      assertEquals(2, page1.getDocIdsCount());

      // Page 2: offset=2, limit=2
      ListAllDocumentIdsResponse page2 = callListAll(2, 2);
      assertEquals(5, page2.getTotalCount());
      assertEquals(2, page2.getDocIdsCount());

      // Page 3: offset=4, limit=2 — only 1 doc left
      ListAllDocumentIdsResponse page3 = callListAll(4, 2);
      assertEquals(5, page3.getTotalCount());
      assertEquals(1, page3.getDocIdsCount());

      // Pages should not overlap
      List<String> allReturned =
          new java.util.ArrayList<>(page1.getDocIdsList());
      allReturned.addAll(page2.getDocIdsList());
      allReturned.addAll(page3.getDocIdsList());
      assertEquals(5, new java.util.HashSet<>(allReturned).size(), "No duplicates across pages");
    }

    @Test
    @DisplayName("offset past end returns zero IDs with correct totalCount")
    void offsetPastEnd_returnsEmpty() throws Exception {
      indexDoc("doc-1", "C:/docs/file1.txt");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      ListAllDocumentIdsResponse resp = callListAll(100, 10);
      assertEquals(1, resp.getTotalCount());
      assertEquals(0, resp.getDocIdsCount());
    }

    @Test
    @DisplayName("limit=0 uses default and returns all docs when corpus is small")
    void limitZero_usesDefault() throws Exception {
      indexDoc("doc-1", "C:/docs/file1.txt");
      indexDoc("doc-2", "C:/docs/file2.txt");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      // limit=0 → default 1000, should return all 2 docs
      ListAllDocumentIdsResponse resp = callListAll(0, 0);
      assertEquals(2, resp.getTotalCount());
      assertEquals(2, resp.getDocIdsCount());
    }
  }

  @Nested
  @DisplayName("chunk exclusion")
  class ChunkExclusion {

    @Test
    @DisplayName("chunk documents are excluded from results")
    void chunks_areExcluded() throws Exception {
      // Index a parent doc
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "parent-1",
                  SchemaFields.DOC_UID, "parent-1#0",
                  SchemaFields.PATH, "C:/docs/large.txt",
                  SchemaFields.CONTENT, "parent content")));
      // Index a chunk doc (IS_CHUNK = "true")
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "parent-1__chunk_0",
                  SchemaFields.DOC_UID, "parent-1__chunk_0#0",
                  SchemaFields.PATH, "C:/docs/large.txt",
                  SchemaFields.IS_CHUNK, "true",
                  SchemaFields.PARENT_DOC_ID, "parent-1",
                  SchemaFields.CONTENT, "chunk content")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      ListAllDocumentIdsResponse resp = callListAll(0, 100);

      assertEquals(1, resp.getTotalCount(), "Only parent doc should be counted");
      assertEquals(1, resp.getDocIdsCount());
      assertEquals("parent-1", resp.getDocIds(0));
    }
  }
}
