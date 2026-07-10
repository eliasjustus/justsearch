/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for tempdoc 710 D.3: {@code processBgeM3Backfill} queries {@code
 * SPLADE_STATUS=PENDING} with no parent/chunk filter, so a batch mixes parent docs and chunk docs
 * (chunk docs get {@code SPLADE_STATUS=PENDING} at creation too — see {@code
 * ChunkDocumentWriter}). Before the fix, the dense-write phase unconditionally wrote {@code
 * VECTOR}/{@code EMBEDDING_STATUS} even for a chunk doc — the wrong field pair, since chunk docs
 * use {@code CHUNK_VECTOR}/{@code CHUNK_EMBEDDING_STATUS}. These tests pin the doc-type routing on
 * both the success path and the per-doc encode-failure escalation path.
 */
@DisplayName("BgeM3BackfillOps")
@ExtendWith(MockitoExtension.class)
class BgeM3BackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock BgeM3Encoder encoder;

  private BgeM3BackfillOps.BackfillContext context() {
    return new BgeM3BackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        signalBus,
        () -> encoder,
        () -> true,
        100,
        false,
        LoggerFactory.getLogger(BgeM3BackfillOpsTest.class));
  }

  private void stubPending(String... docIds) {
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt()))
        .thenReturn(List.of(docIds));
  }

  @Test
  @DisplayName(
      "chunk doc in the pending batch: CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS written, VECTOR/"
          + "EMBEDDING_STATUS NOT written, SPLADE written")
  void chunkDoc_writesChunkFieldPair_notParentFieldPair() throws Exception {
    stubPending("chunk-1");
    when(documentFieldOps.getDocumentField("chunk-1", SchemaFields.CHUNK_CONTENT))
        .thenReturn("chunk text");
    when(encoder.encodeBatch(List.of("chunk text")))
        .thenReturn(List.of(new BgeM3Output(new float[] {1f, 2f}, Map.of("tok", 1.0f))));
    when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

    boolean result = BgeM3BackfillOps.processBgeM3Backfill(context());

    assertTrue(result);
    // Content came from CHUNK_CONTENT — getDocumentContent (the parent-doc fallback) must not
    // be consulted at all for a chunk doc.
    verify(documentFieldOps, never()).getDocumentContent(anyString());
    verify(indexingCoordinator)
        .updateDocumentsBatch(
            argThat(
                batch -> {
                  if (batch.size() != 1) return false;
                  Map<String, Object> updates = batch.get(0).getValue();
                  return "chunk-1".equals(batch.get(0).getKey())
                      && updates.containsKey(SchemaFields.CHUNK_VECTOR)
                      && SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                          updates.get(SchemaFields.CHUNK_EMBEDDING_STATUS))
                      && "0".equals(updates.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
                      && updates.containsKey(SchemaFields.SPLADE)
                      && SchemaFields.SPLADE_STATUS_COMPLETED.equals(
                          updates.get(SchemaFields.SPLADE_STATUS))
                      && !updates.containsKey(SchemaFields.VECTOR)
                      && !updates.containsKey(SchemaFields.EMBEDDING_STATUS)
                      && !updates.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT);
                }));
  }

  @Test
  @DisplayName(
      "parent doc in the pending batch: VECTOR/EMBEDDING_STATUS written (unchanged behavior),"
          + " no CHUNK_* fields")
  void parentDoc_writesParentFieldPair_unchanged() throws Exception {
    stubPending("parent-1");
    when(documentFieldOps.getDocumentField("parent-1", SchemaFields.CHUNK_CONTENT))
        .thenReturn(null);
    when(documentFieldOps.getDocumentContent("parent-1")).thenReturn("parent text");
    when(encoder.encodeBatch(List.of("parent text")))
        .thenReturn(List.of(new BgeM3Output(new float[] {3f, 4f}, Map.of("tok", 2.0f))));
    when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

    boolean result = BgeM3BackfillOps.processBgeM3Backfill(context());

    assertTrue(result);
    verify(indexingCoordinator)
        .updateDocumentsBatch(
            argThat(
                batch -> {
                  if (batch.size() != 1) return false;
                  Map<String, Object> updates = batch.get(0).getValue();
                  return "parent-1".equals(batch.get(0).getKey())
                      && updates.containsKey(SchemaFields.VECTOR)
                      && SchemaFields.EMBEDDING_STATUS_COMPLETED.equals(
                          updates.get(SchemaFields.EMBEDDING_STATUS))
                      && "0".equals(updates.get(SchemaFields.EMBEDDING_RETRY_COUNT))
                      && updates.containsKey(SchemaFields.SPLADE)
                      && !updates.containsKey(SchemaFields.CHUNK_VECTOR)
                      && !updates.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)
                      && !updates.containsKey(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT);
                }));
  }

  @Test
  @DisplayName(
      "chunk embed failure (batch + per-doc encode both throw) escalates via"
          + " CHUNK_EMBEDDING_RETRY_COUNT, not EMBEDDING_RETRY_COUNT")
  void chunkEmbedFailure_escalatesChunkRetryCount() throws Exception {
    // Mixed batch (one failing + one succeeding chunk) so the batch-wide systemic-failure
    // short-circuit doesn't mask the per-doc escalation write under test.
    stubPending("chunk-bad", "chunk-ok");
    when(documentFieldOps.getDocumentField("chunk-bad", SchemaFields.CHUNK_CONTENT))
        .thenReturn("poison chunk text");
    when(documentFieldOps.getDocumentField("chunk-ok", SchemaFields.CHUNK_CONTENT))
        .thenReturn("good chunk text");
    when(encoder.encodeBatch(anyList())).thenThrow(new OrtException("batch encode boom"));
    when(encoder.encode("poison chunk text")).thenThrow(new OrtException("per-doc encode boom"));
    when(encoder.encode("good chunk text"))
        .thenReturn(new BgeM3Output(new float[] {1f, 2f}, Map.of("tok", 1.0f)));
    when(documentFieldOps.getDocumentField(
            "chunk-bad", SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
        .thenReturn("0");

    boolean result = BgeM3BackfillOps.processBgeM3Backfill(context());

    assertTrue(result, "not systemic: one doc in the batch succeeded");
    verify(indexingCoordinator)
        .updateDocument(
            eq("chunk-bad"),
            argThat(
                (Map<String, Object> updates) ->
                    "1".equals(updates.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT))
                        && !updates.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)
                        && !updates.containsKey(SchemaFields.EMBEDDING_RETRY_COUNT)
                        && !updates.containsKey(SchemaFields.EMBEDDING_STATUS)),
            eq(true));
  }

  @Test
  @DisplayName(
      "parent embed failure (batch + per-doc encode both throw) escalates via"
          + " EMBEDDING_RETRY_COUNT, not CHUNK_EMBEDDING_RETRY_COUNT")
  void parentEmbedFailure_escalatesParentRetryCount() throws Exception {
    // Mixed batch (one failing + one succeeding parent) so the batch-wide systemic-failure
    // short-circuit doesn't mask the per-doc escalation write under test.
    stubPending("parent-bad", "parent-ok");
    when(documentFieldOps.getDocumentField("parent-bad", SchemaFields.CHUNK_CONTENT))
        .thenReturn(null);
    when(documentFieldOps.getDocumentContent("parent-bad")).thenReturn("poison parent text");
    when(documentFieldOps.getDocumentField("parent-ok", SchemaFields.CHUNK_CONTENT))
        .thenReturn(null);
    when(documentFieldOps.getDocumentContent("parent-ok")).thenReturn("good parent text");
    when(encoder.encodeBatch(anyList())).thenThrow(new OrtException("batch encode boom"));
    when(encoder.encode("poison parent text"))
        .thenThrow(new OrtException("per-doc encode boom"));
    when(encoder.encode("good parent text"))
        .thenReturn(new BgeM3Output(new float[] {3f, 4f}, Map.of("tok", 2.0f)));
    when(documentFieldOps.getDocumentField("parent-bad", SchemaFields.EMBEDDING_RETRY_COUNT))
        .thenReturn("0");

    boolean result = BgeM3BackfillOps.processBgeM3Backfill(context());

    assertTrue(result, "not systemic: one doc in the batch succeeded");
    verify(indexingCoordinator)
        .updateDocument(
            eq("parent-bad"),
            argThat(
                (Map<String, Object> updates) ->
                    "1".equals(updates.get(SchemaFields.EMBEDDING_RETRY_COUNT))
                        && !updates.containsKey(SchemaFields.EMBEDDING_STATUS)
                        && !updates.containsKey(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT)
                        && !updates.containsKey(SchemaFields.CHUNK_EMBEDDING_STATUS)),
            eq(true));
  }
}
