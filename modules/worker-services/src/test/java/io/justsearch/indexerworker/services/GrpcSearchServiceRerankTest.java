package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.reranker.CrossEncoderReranker;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the Rerank RPC (360: migrated to Worker).
 *
 * <p>Verifies the gRPC handler, DelegatingSearchService forward, and the MODEL_NOT_LOADED
 * fallback when no reranker is wired.
 */
@DisplayName("GrpcSearchService Rerank RPC")
class GrpcSearchServiceRerankTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private GrpcSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
    service = new GrpcSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  private RerankResponse callRerank(
      io.justsearch.ipc.SearchServiceGrpc.SearchServiceImplBase target,
      String query,
      List<String> docs,
      long deadlineMs) {
    RerankRequest request =
        RerankRequest.newBuilder()
            .setQuery(query)
            .addAllDocumentTexts(docs)
            .setDeadlineMs(deadlineMs)
            .build();
    AtomicReference<RerankResponse> result = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    target.rerank(
        request,
        new StreamObserver<RerankResponse>() {
          @Override
          public void onNext(RerankResponse value) {
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
      fail("rerank returned error: " + error.get().getMessage());
    }
    assertNotNull(result.get(), "rerank should return a response");
    return result.get();
  }

  @Nested
  @DisplayName("without reranker wired")
  class WithoutReranker {

    @Test
    @DisplayName("returns skipped=true with MODEL_NOT_LOADED")
    void returnsModelNotLoaded() {
      RerankResponse resp =
          callRerank(service, "test query", List.of("doc1", "doc2"), 200);

      assertTrue(resp.getSkipped());
      assertEquals("MODEL_NOT_LOADED", resp.getSkipReason());
      assertEquals(0, resp.getSortedIndicesCount());
      assertEquals(0, resp.getScoresCount());
    }
  }

  @Nested
  @DisplayName("with mock reranker wired")
  class WithReranker {

    @BeforeEach
    void wireReranker() {
      CrossEncoderReranker mockReranker = mock(CrossEncoderReranker.class);
      when(mockReranker.rerank(anyString(), anyList(), anyLong()))
          .thenReturn(
              new CrossEncoderReranker.RerankedResult(
                  List.of(1, 0, 2), List.of(0.3f, 0.9f, 0.1f), false, 42));
      service.setSearchReranker(mockReranker);
    }

    @Test
    @DisplayName("returns sorted indices and scores from reranker")
    void returnsSortedIndicesAndScores() {
      RerankResponse resp =
          callRerank(service, "test", List.of("doc A", "doc B", "doc C"), 5000);

      assertFalse(resp.getSkipped());
      assertEquals(42, resp.getElapsedMs());
      assertEquals(List.of(1, 0, 2), resp.getSortedIndicesList());
      assertEquals(3, resp.getScoresCount());
      assertEquals(0.3f, resp.getScores(0), 0.001f);
      assertEquals(0.9f, resp.getScores(1), 0.001f);
      assertEquals(0.1f, resp.getScores(2), 0.001f);
    }

  }

  @Nested
  @DisplayName("with skipping reranker")
  class WithSkippingReranker {

    @BeforeEach
    void wireSkippingReranker() {
      CrossEncoderReranker mockReranker = mock(CrossEncoderReranker.class);
      when(mockReranker.rerank(anyString(), anyList(), anyLong()))
          .thenReturn(
              new CrossEncoderReranker.RerankedResult(List.of(0, 1), List.of(), true, 150));
      service.setSearchReranker(mockReranker);
    }

    @Test
    @DisplayName("returns skipped=true with DEADLINE_EXCEEDED")
    void returnsDeadlineExceeded() {
      RerankResponse resp =
          callRerank(service, "test", List.of("doc A", "doc B"), 200);

      assertTrue(resp.getSkipped());
      assertEquals("DEADLINE_EXCEEDED", resp.getSkipReason());
      assertEquals(150, resp.getElapsedMs());
      assertEquals(0, resp.getSortedIndicesCount());
    }
  }
}
