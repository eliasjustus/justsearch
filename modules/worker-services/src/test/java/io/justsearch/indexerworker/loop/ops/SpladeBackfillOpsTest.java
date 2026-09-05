/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@DisplayName("SpladeBackfillOps")
@ExtendWith(MockitoExtension.class)
class SpladeBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock SpladeEncoder encoder;

  @Test
  @DisplayName("encodes reconstructed chunk text from the parent-aware batch reader")
  void reconstructedChunkTextIsEncodedThroughBatchReader() throws Exception {
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS),
            eq(SchemaFields.SPLADE_STATUS_PENDING),
            anyInt()))
        .thenReturn(List.of("chunk-1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("chunk-1")))
        .thenReturn(Map.of("chunk-1", "reconstructed slice"));
    when(encoder.encodeBatch(List.of("reconstructed slice")))
        .thenReturn(new ArrayList<>(List.of(Map.of("token", 1.0f))));
    when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

    StageOutcome outcome = SpladeBackfillOps.processSpladeBackfill(context());

    assertTrue(outcome.success());
    assertEquals(1, outcome.docsProcessed());
    verify(documentFieldOps).getDocumentContentBatch(List.of("chunk-1"));
    verify(documentFieldOps, never())
        .getDocumentField("chunk-1", SchemaFields.CHUNK_CONTENT);
    verify(indexingCoordinator)
        .updateDocumentsBatch(
            argThat(
                batch ->
                    batch.size() == 1
                        && "chunk-1".equals(batch.get(0).getKey())
                        && Map.of("token", 1.0f)
                            .equals(batch.get(0).getValue().get(SchemaFields.SPLADE))
                        && SchemaFields.SPLADE_STATUS_COMPLETED.equals(
                            batch.get(0).getValue().get(SchemaFields.SPLADE_STATUS))));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF: the lane selects whole documents only — it must not encode sparse"
          + " data the configuration says not to produce (tempdoc 931)")
  void chunkSpladeOff_selectsWholeDocumentsOnly() throws Exception {
    when(documentFieldOps.queryNonChunkDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS),
            eq(SchemaFields.SPLADE_STATUS_PENDING),
            anyInt()))
        .thenReturn(List.of("parent-1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("parent-1")))
        .thenReturn(Map.of("parent-1", "parent body"));
    when(encoder.encodeBatch(List.of("parent body")))
        .thenReturn(new ArrayList<>(List.of(Map.of("token", 1.0f))));
    when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

    StageOutcome outcome = SpladeBackfillOps.processSpladeBackfill(context(false));

    assertEquals(1, outcome.docsProcessed());
    verify(documentFieldOps, never())
        .queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt());
  }

  private SpladeBackfillOps.BackfillContext context() {
    return context(true);
  }

  private SpladeBackfillOps.BackfillContext context(boolean chunkSpladeEnabled) {
    return new SpladeBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        IndexingPacing.unthrottled(),
        () -> encoder,
        () -> true,
        100,
        false,
        chunkSpladeEnabled,
        LoggerFactory.getLogger(SpladeBackfillOpsTest.class));
  }
}
