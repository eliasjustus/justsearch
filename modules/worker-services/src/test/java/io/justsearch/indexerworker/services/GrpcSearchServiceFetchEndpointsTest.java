package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.SuggestRequest;
import io.justsearch.ipc.SuggestResponse;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GrpcSearchService fetch/suggest endpoints")
class GrpcSearchServiceFetchEndpointsTest {

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

  @Nested
  @DisplayName("Suggest")
  class Suggest {

    @Test
    @DisplayName("returns empty suggestions for blank query")
    void returnsEmptyForBlankQuery() {
      SuggestResponse response =
          callSuggest(SuggestRequest.newBuilder().setQuery("   ").setLimit(10).build());
      assertEquals(0, response.getSuggestionsCount());
    }

    @Test
    @DisplayName("returns suggestions from indexed title/content matches")
    void returnsSuggestionsForPrefix() throws Exception {
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-1",
                  SchemaFields.DOC_UID, "doc-1#0",
                  SchemaFields.PATH, "C:/docs/report-q1.pdf",
                  SchemaFields.TITLE, "Report Q1",
                  SchemaFields.CONTENT, "Quarterly report with financial summary")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      SuggestResponse response =
          callSuggest(SuggestRequest.newBuilder().setQuery("rep").setLimit(10).build());

      assertTrue(response.getSuggestionsCount() > 0, "Expected at least one suggestion");
      assertTrue(
          response.getSuggestionsList().stream()
              .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("report")),
          "Expected report-related suggestion from title/path");
    }
  }

  @Nested
  @DisplayName("FetchDocuments")
  class FetchDocuments {

    @Test
    @DisplayName("returns found and missing documents with metadata")
    void returnsFoundAndMissingDocs() throws Exception {
      String docId = "doc-1";
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/contract.pdf",
                  SchemaFields.TITLE, "Service Agreement",
                  SchemaFields.MIME, "application/pdf",
                  SchemaFields.CONTENT, "Contract body text")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      FetchDocumentsResponse response =
          callFetchDocuments(
              FetchDocumentsRequest.newBuilder()
                  .addDocIds(docId)
                  .addDocIds("missing-doc")
                  .build());

      assertEquals(2, response.getDocumentsCount());

      DocumentContent found = response.getDocuments(0);
      assertEquals(docId, found.getDocId());
      assertTrue(found.getFound());
      assertEquals("Contract body text", found.getContent());
      assertEquals("Service Agreement", found.getMetadataOrDefault("title", ""));
      assertEquals("C:/docs/contract.pdf", found.getMetadataOrDefault("path", ""));
      assertEquals("application/pdf", found.getMetadataOrDefault("mime", ""));

      DocumentContent missing = response.getDocuments(1);
      assertEquals("missing-doc", missing.getDocId());
      assertFalse(missing.getFound());
      assertTrue(missing.getError().contains("not found"));
    }

    @Test
    @DisplayName("trims content to gRPC max payload cap")
    void trimsLargeContent() throws Exception {
      String docId = "doc-large";
      String largeContent = "a".repeat(210_000);
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/large.txt",
                  SchemaFields.CONTENT, largeContent)));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      FetchDocumentsResponse response =
          callFetchDocuments(FetchDocumentsRequest.newBuilder().addDocIds(docId).build());

      assertEquals(1, response.getDocumentsCount());
      DocumentContent doc = response.getDocuments(0);
      assertTrue(doc.getFound());
      assertEquals(200_000, doc.getContent().length(), "Content should be capped at 200k chars");
    }
  }

  @Nested
  @DisplayName("FetchDocumentSlice")
  class FetchDocumentSlice {

    @Test
    @DisplayName("returns paged slice and VDU metadata")
    void returnsPagedSliceAndMetadata() throws Exception {
      String docId = "doc-slice";
      String content = "0123456789abcdefghij";
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, docId,
                  SchemaFields.DOC_UID, docId + "#0",
                  SchemaFields.PATH, "C:/docs/slice.txt",
                  SchemaFields.TITLE, "Slice Test",
                  SchemaFields.MIME, "text/plain",
                  SchemaFields.CONTENT, content,
                  SchemaFields.VDU_STATUS, "done",
                  SchemaFields.VDU_PROCESSED, "true",
                  SchemaFields.VDU_PAGE_COUNT, "3",
                  SchemaFields.VDU_ENRICHMENT, "OCR enriched")));
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      FetchDocumentSliceResponse response =
          callFetchDocumentSlice(
              FetchDocumentSliceRequest.newBuilder()
                  .setDocId(docId)
                  .setOffsetChars(5)
                  .setMaxChars(6)
                  .build());

      assertTrue(response.getFound());
      assertEquals("56789a", response.getContent());
      assertTrue(response.getTruncated());
      assertEquals(11, response.getNextOffsetChars());
      assertEquals("Slice Test", response.getMetadataOrDefault("title", ""));
      assertEquals("text/plain", response.getMetadataOrDefault("mime", ""));
      assertEquals("done", response.getMetadataOrDefault("vdu_status", ""));
      assertEquals("3", response.getMetadataOrDefault("vdu_page_count", ""));
    }

    @Test
    @DisplayName("returns INVALID_ARGUMENT when doc_id is blank")
    void returnsInvalidArgumentForBlankDocId() {
      AtomicReference<FetchDocumentSliceResponse> responseRef = new AtomicReference<>();
      AtomicReference<Throwable> errorRef = new AtomicReference<>();

      service.fetchDocumentSlice(
          FetchDocumentSliceRequest.newBuilder().setDocId("   ").setOffsetChars(0).setMaxChars(10).build(),
          observer(responseRef, errorRef));

      assertNotNull(errorRef.get(), "Expected INVALID_ARGUMENT error");
      Status status = Status.fromThrowable(errorRef.get());
      assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
      assertEquals("doc_id is required", status.getDescription());
    }
  }

  private SuggestResponse callSuggest(SuggestRequest request) {
    AtomicReference<SuggestResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    service.suggest(request, observer(responseRef, errorRef));
    if (errorRef.get() != null) {
      fail("suggest failed: " + errorRef.get().getMessage());
    }
    assertNotNull(responseRef.get(), "Response should not be null");
    return responseRef.get();
  }

  private FetchDocumentsResponse callFetchDocuments(FetchDocumentsRequest request) {
    AtomicReference<FetchDocumentsResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    service.fetchDocuments(request, observer(responseRef, errorRef));
    if (errorRef.get() != null) {
      fail("fetchDocuments failed: " + errorRef.get().getMessage());
    }
    assertNotNull(responseRef.get(), "Response should not be null");
    return responseRef.get();
  }

  private FetchDocumentSliceResponse callFetchDocumentSlice(FetchDocumentSliceRequest request) {
    AtomicReference<FetchDocumentSliceResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    service.fetchDocumentSlice(request, observer(responseRef, errorRef));
    if (errorRef.get() != null) {
      fail("fetchDocumentSlice failed: " + errorRef.get().getMessage());
    }
    assertNotNull(responseRef.get(), "Response should not be null");
    return responseRef.get();
  }

  private static <T> StreamObserver<T> observer(
      AtomicReference<T> responseRef, AtomicReference<Throwable> errorRef) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        responseRef.set(value);
      }

      @Override
      public void onError(Throwable t) {
        errorRef.set(t);
      }

      @Override
      public void onCompleted() {
        // no-op
      }
    };
  }
}
