package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for the batch-embedding trusted-length bug: a null-only guard on the
 * embedding provider's batch result let a short/empty (but non-null) result reach the
 * index-aligned update loop and throw ArrayIndexOutOfBoundsException before any doc/chunk was
 * marked failed — crash-looping the whole backfill cycle forever since the scheduler just
 * refetched the same still-PENDING batch (live-reproduced on a legal-text corpus).
 */
@DisplayName("EmbeddingBackfillOps")
@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock EmbeddingProvider embeddingProvider;

  private EmbeddingBackfillOps.BackfillContext context() {
    return new EmbeddingBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        signalBus,
        () -> embeddingProvider,
        () -> true,
        () -> true,
        100,
        LoggerFactory.getLogger(EmbeddingBackfillOpsTest.class));
  }

  @Nested
  @DisplayName("processChunkEmbeddingBackfill()")
  class ProcessChunkEmbeddingBackfill {

    private void stubTwoPendingChunks() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.CHUNK_EMBEDDING_STATUS),
              eq(SchemaFields.EMBEDDING_STATUS_PENDING),
              anyInt()))
          .thenReturn(List.of("chunk1", "chunk2"));
      when(signalBus.isUserActive()).thenReturn(false);
      when(signalBus.isMainGpuActive()).thenReturn(false);
      when(documentFieldOps.getDocumentField("chunk1", SchemaFields.CHUNK_CONTENT))
          .thenReturn("content one");
      when(documentFieldOps.getDocumentField("chunk2", SchemaFields.CHUNK_CONTENT))
          .thenReturn("content two");
    }

    @Test
    @DisplayName("falls back per-chunk without throwing when batch embedding returns an empty list")
    void fallsBackPerChunk_whenBatchReturnsEmptyList() {
      stubTwoPendingChunks();
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(List.of());
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[] {3f, 4f});

      boolean result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result, "should report work was done via the per-chunk fallback");
      verify(embeddingProvider).embedDocument("content one");
      verify(embeddingProvider).embedDocument("content two");
      verify(indexingCoordinator)
          .updateDocument(
              eq("chunk1"),
              argThat(
                  (Map<String, Object> m) ->
                      SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                          m.get(SchemaFields.CHUNK_EMBEDDING_STATUS))),
              eq(true));
      verify(indexingCoordinator)
          .updateDocument(
              eq("chunk2"),
              argThat(
                  (Map<String, Object> m) ->
                      SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                          m.get(SchemaFields.CHUNK_EMBEDDING_STATUS))),
              eq(true));
    }

    @Test
    @DisplayName(
        "falls back per-chunk without throwing when batch embedding returns a short (n-1) list")
    void fallsBackPerChunk_whenBatchReturnsShortList() {
      stubTwoPendingChunks();
      // Two chunks requested, only one vector returned — the AIOOBE trigger for the old
      // null-only guard.
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(List.of(new float[] {9f}));
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[] {3f, 4f});

      boolean result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result);
      verify(embeddingProvider).embedDocument("content one");
      verify(embeddingProvider).embedDocument("content two");
    }

    @Test
    @DisplayName("still falls back cleanly when batch embedding returns null (pre-existing case)")
    void fallsBackPerChunk_whenBatchReturnsNull() {
      stubTwoPendingChunks();
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(null);
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[] {3f, 4f});

      boolean result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result);
      verify(embeddingProvider).embedDocument("content one");
      verify(embeddingProvider).embedDocument("content two");
    }

    @Test
    @DisplayName(
        "marks a chunk for retry (not permanently FAILED) when the per-chunk fallback also fails")
    void marksRetry_whenFallbackAlsoReturnsEmptyVector() {
      stubTwoPendingChunks();
      when(embeddingProvider.embedDocumentBatch(List.of("content one", "content two")))
          .thenReturn(List.of());
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[0]);
      when(documentFieldOps.getDocumentField("chunk2", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
          .thenReturn(null);

      boolean result =
          assertDoesNotThrow(() -> EmbeddingBackfillOps.processChunkEmbeddingBackfill(context()));

      assertTrue(result);
      verify(indexingCoordinator)
          .updateDocument(
              eq("chunk2"),
              argThat(
                  (Map<String, Object> m) ->
                      "1".equals(m.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
                          && !m.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)),
              eq(true));
    }
  }

  @Nested
  @DisplayName("processEmbeddingBackfill() (doc-level sibling)")
  class ProcessEmbeddingBackfill {

    private void stubTwoPendingDocs() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.EMBEDDING_STATUS), eq(SchemaFields.EMBEDDING_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1", "doc2"));
      when(signalBus.isUserActive()).thenReturn(false);
      when(signalBus.isMainGpuActive()).thenReturn(false);
      when(documentFieldOps.getDocumentContentBatch(List.of("doc1", "doc2")))
          .thenReturn(Map.of("doc1", "content one", "doc2", "content two"));
    }

    @Test
    @DisplayName("falls back per-doc without throwing when batch embedding returns an empty list")
    void fallsBackPerDoc_whenBatchReturnsEmptyList() {
      stubTwoPendingDocs();
      when(embeddingProvider.embedDocumentBatch(anyList())).thenReturn(List.of());
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[] {3f, 4f});

      assertDoesNotThrow(() -> EmbeddingBackfillOps.processEmbeddingBackfill(context()));

      verify(embeddingProvider).embedDocument("content one");
      verify(embeddingProvider).embedDocument("content two");
    }

    @Test
    @DisplayName("falls back per-doc without throwing when batch embedding returns a short list")
    void fallsBackPerDoc_whenBatchReturnsShortList() {
      stubTwoPendingDocs();
      when(embeddingProvider.embedDocumentBatch(anyList()))
          .thenReturn(List.of(new float[] {9f}));
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[] {3f, 4f});

      assertDoesNotThrow(() -> EmbeddingBackfillOps.processEmbeddingBackfill(context()));

      verify(embeddingProvider).embedDocument("content one");
      verify(embeddingProvider).embedDocument("content two");
    }

    @Test
    @DisplayName("still falls back cleanly when batch embedding returns null (pre-existing case)")
    void fallsBackPerDoc_whenBatchReturnsNull() {
      stubTwoPendingDocs();
      when(embeddingProvider.embedDocumentBatch(anyList())).thenReturn(null);
      when(embeddingProvider.embedDocument("content one")).thenReturn(new float[] {1f, 2f});
      when(embeddingProvider.embedDocument("content two")).thenReturn(new float[] {3f, 4f});

      assertDoesNotThrow(() -> EmbeddingBackfillOps.processEmbeddingBackfill(context()));

      verify(embeddingProvider).embedDocument("content one");
      verify(embeddingProvider).embedDocument("content two");
    }
  }
}
