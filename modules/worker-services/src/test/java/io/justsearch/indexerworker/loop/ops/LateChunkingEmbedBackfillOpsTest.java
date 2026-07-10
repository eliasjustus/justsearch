/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

/**
 * Dedicated coverage for {@link LateChunkingEmbedBackfillOps} (tempdoc 691 Wave 0): the happy
 * path, the long-doc-deferred path, a chunk-vector-count mismatch, failure escalation parity, and
 * the flag-off strict no-op — plus the tempdoc 691 Wave 0 log-visibility change (the final log now
 * fires on {@code longDocDeferred > 0} alone, not just {@code processed > 0 || failed > 0}).
 *
 * <p>Uses the same {@code fakeIndex}-backed mock seam as {@code CombinedEnrichmentBackfillOpsTest}
 * (in-memory map behind {@code DocumentFieldOps}/{@code IndexingCoordinator} stubs, not fixed
 * static stubs) so multi-doc batched writes are observed for real rather than assumed
 * (unreachable-seed-green, see agent-lessons.md).
 */
@DisplayName("LateChunkingEmbedBackfillOps")
@ExtendWith(MockitoExtension.class)
class LateChunkingEmbedBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock Logger log;

  private final Map<String, Map<String, Object>> fakeIndex = new LinkedHashMap<>();
  private final Map<String, String> contentByDoc = new HashMap<>();

  @BeforeEach
  void wireFakeIndexAndDefaults() {
    lenient().when(signalBus.isUserActive()).thenReturn(false);
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);

    lenient()
        .when(documentFieldOps.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, String> m = new HashMap<>();
              for (String id : ids) {
                String c = contentByDoc.get(id);
                if (c != null) m.put(id, c);
              }
              return m;
            });

    lenient()
        .when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              java.util.Set<String> fields = inv.getArgument(1);
              Map<String, Map<String, String>> result = new HashMap<>();
              for (String id : ids) {
                Map<String, Object> state = fakeIndex.getOrDefault(id, Map.of());
                Map<String, String> filtered = new HashMap<>();
                for (String f : fields) {
                  Object v = state.get(f);
                  if (v != null) filtered.put(f, v.toString());
                }
                result.put(id, filtered);
              }
              return result;
            });

    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(
            inv -> {
              String field = inv.getArgument(0);
              String value = inv.getArgument(1);
              List<String> matches = new ArrayList<>();
              for (var e : fakeIndex.entrySet()) {
                if (value.equals(e.getValue().get(field))) {
                  matches.add(e.getKey());
                }
              }
              return matches;
            });

    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<Map.Entry<String, Map<String, Object>>> batch = inv.getArgument(0);
              int count = 0;
              for (var entry : batch) {
                fakeIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>()).putAll(entry.getValue());
                count++;
              }
              return new LuceneRuntimeTypes.BatchUpdateResult(count, 0);
            });
  }

  private void seedDoc(String docId, String content, Map<String, String> statusFields) {
    contentByDoc.put(docId, content);
    fakeIndex.computeIfAbsent(docId, k -> new HashMap<>()).putAll(statusFields);
  }

  private void seedChunkDoc(
      String chunkId, String parentId, int chunkIndex, int startChar, int endChar, String status) {
    Map<String, Object> state = fakeIndex.computeIfAbsent(chunkId, k -> new HashMap<>());
    state.put(SchemaFields.IS_CHUNK, "true");
    state.put(SchemaFields.PARENT_DOC_ID, parentId);
    state.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunkIndex));
    state.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(startChar));
    state.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(endChar));
    state.put(SchemaFields.CHUNK_EMBEDDING_STATUS, status);
  }

  private LateChunkingEmbedBackfillOps.BackfillContext lateChunkingContext(boolean enabled) {
    return new LateChunkingEmbedBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        signalBus,
        () -> embeddingProvider,
        () -> true,
        () -> true,
        enabled,
        100,
        log);
  }

  @Test
  @DisplayName(
      "flag ON, happy path: chunked parent embeds once, VECTOR + both CHUNK_VECTORs written,"
          + " statuses COMPLETED, retry counts reset, SPLADE/NER never touched")
  void happyPath_writesAllVectors_resetsRetryCounts_leavesSpladeNerAlone() {
    seedDoc(
        "parent-1",
        "parent content long enough to be chunked",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.EMBEDDING_RETRY_COUNT, "2",
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-1", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedChunkDoc("chunk-2", "parent-1", 1, 10, 20, SchemaFields.EMBEDDING_STATUS_PENDING);

    float[] docVector = {1f, 2f};
    List<float[]> chunkVectors = List.of(new float[] {3f, 4f}, new float[] {5f, 6f});
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, chunkVectors, 2));

    boolean didWork =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(true));

    assertTrue(didWork);

    Map<String, Object> parentState = fakeIndex.get("parent-1");
    assertArrayEquals(docVector, (float[]) parentState.get(SchemaFields.VECTOR));
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_PENDING, parentState.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_PENDING, parentState.get(SchemaFields.NER_STATUS));

    Map<String, Object> chunk1State = fakeIndex.get("chunk-1");
    assertArrayEquals(chunkVectors.get(0), (float[]) chunk1State.get(SchemaFields.CHUNK_VECTOR));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED, chunk1State.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals("0", chunk1State.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));

    Map<String, Object> chunk2State = fakeIndex.get("chunk-2");
    assertArrayEquals(chunkVectors.get(1), (float[]) chunk2State.get(SchemaFields.CHUNK_VECTOR));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED, chunk2State.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals("0", chunk2State.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));

    verify(commitOps, times(1)).commitAndTrack(any());
    verify(log, times(1))
        .info(
            anyString(),
            eq(1),
            eq(0),
            eq(0),
            anyLong(),
            anyLong());
  }

  @Test
  @DisplayName(
      "flag ON, long-doc deferred: embedWithSpans returns null -> nothing written, statuses stay"
          + " PENDING, counted as deferred, and the summary log fires even though"
          + " processed=failed=0 (tempdoc 691 Wave 0 visibility fix)")
  void longDoc_embedWithSpansReturnsNull_deferredAndLoggedDespiteZeroWork() {
    seedDoc(
        "parent-long",
        "content that exceeds the model's context window",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-long", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class))).thenReturn(null);

    boolean didWork =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(true));

    assertFalse(didWork, "no work should be recorded when embedWithSpans defers (long doc)");
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
    verify(commitOps, never()).commitAndTrack(any());

    Map<String, Object> parentState = fakeIndex.get("parent-long");
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNull(parentState.get(SchemaFields.VECTOR));
    Map<String, Object> chunkState = fakeIndex.get("chunk-1");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING, chunkState.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertNull(chunkState.get(SchemaFields.CHUNK_VECTOR));

    // The core of the change-2 regression: before tempdoc 691 Wave 0 the log only fired on
    // processed>0||failed>0, so an all-deferred cycle was invisible. Now it fires with
    // longDocDeferred=1 and processed=failed=0.
    verify(log, times(1))
        .info(
            anyString(),
            eq(0),
            eq(0),
            eq(1),
            anyLong(),
            anyLong());
  }

  @Test
  @DisplayName(
      "flag ON, chunk-vector count mismatch: leaves parent and chunks PENDING, no partial writes")
  void spanCountMismatch_leavesEverythingPending_noPartialWrites() {
    seedDoc(
        "parent-mismatch",
        "parent content long enough to be chunked",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-mismatch", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedChunkDoc("chunk-2", "parent-mismatch", 1, 10, 20, SchemaFields.EMBEDDING_STATUS_PENDING);

    // 2 chunks expected but only 1 chunk vector returned.
    float[] docVector = {1f, 2f};
    List<float[]> onlyOneChunkVector = List.of(new float[] {3f, 4f});
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, onlyOneChunkVector, 1));

    boolean didWork =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(true));

    assertFalse(didWork, "a span-count mismatch must not be counted as work done");
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
    verify(commitOps, never()).commitAndTrack(any());

    Map<String, Object> parentState = fakeIndex.get("parent-mismatch");
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNull(parentState.get(SchemaFields.VECTOR));
    Map<String, Object> chunk1State = fakeIndex.get("chunk-1");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING, chunk1State.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertNull(chunk1State.get(SchemaFields.CHUNK_VECTOR));
    Map<String, Object> chunk2State = fakeIndex.get("chunk-2");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING, chunk2State.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertNull(chunk2State.get(SchemaFields.CHUNK_VECTOR));

    // Neither processed, failed, nor long-doc-deferred fired for this parent, so the summary log
    // must not fire either.
    verify(log, never()).info(anyString(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "flag ON, embedWithSpans throws: parent AND all its chunks get retry-count escalation"
          + " together (tempdoc 700 parity via the shared compute*FailureUpdate helpers), not"
          + " marked FAILED below EMBEDDING_MAX_RETRIES")
  void embedWithSpansThrows_escalatesParentAndChunksTogether() {
    seedDoc(
        "parent-bad",
        "poison parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.EMBEDDING_RETRY_COUNT, "0"));
    seedChunkDoc("chunk-1", "parent-bad", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedChunkDoc("chunk-2", "parent-bad", 1, 10, 20, SchemaFields.EMBEDDING_STATUS_PENDING);

    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenThrow(new RuntimeException("ORT boom"));

    boolean didWork =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(true));

    assertTrue(didWork, "a failure-escalation write is still recorded as work");

    // computeEmbeddingFailureUpdate/computeChunkEmbeddingFailureUpdate parity: retryCount+1,
    // no *_STATUS=FAILED yet since EMBEDDING_MAX_RETRIES=3.
    Map<String, Object> expectedParentUpdate =
        EmbeddingBackfillOps.computeEmbeddingFailureUpdate(0);
    Map<String, Object> expectedChunkUpdate =
        EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(0);

    Map<String, Object> parentState = fakeIndex.get("parent-bad");
    assertEquals(
        expectedParentUpdate.get(SchemaFields.EMBEDDING_RETRY_COUNT),
        parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals("1", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "must not be marked FAILED before EMBEDDING_MAX_RETRIES is reached");

    for (String chunkId : List.of("chunk-1", "chunk-2")) {
      Map<String, Object> chunkState = fakeIndex.get(chunkId);
      assertEquals(
          expectedChunkUpdate.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT),
          chunkState.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals("1", chunkState.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(
          SchemaFields.EMBEDDING_STATUS_PENDING, chunkState.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    }

    // Parent + both chunks escalate together, in the SAME batched write (this pass embeds them
    // as one unit, so they fail together).
    verify(indexingCoordinator, times(1)).updateDocumentsBatch(argThat(list -> list.size() == 3));
    verify(commitOps, times(1)).commitAndTrack(any());
  }

  @Test
  @DisplayName(
      "flag OFF: strict no-op — zero DocumentFieldOps/embedWithSpans/write calls, behavior"
          + " identical to today")
  void flagOff_isStrictNoOp() {
    seedDoc(
        "parent-1",
        "parent content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-1", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    boolean didWork =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(false));

    assertFalse(didWork);
    verifyNoInteractions(documentFieldOps);
    verifyNoInteractions(indexingCoordinator);
    verifyNoInteractions(commitOps);
    verifyNoInteractions(log);
    verify(embeddingProvider, never()).embedWithSpans(anyString(), any());

    Map<String, Object> parentState = fakeIndex.get("parent-1");
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNull(parentState.get(SchemaFields.VECTOR));
    Map<String, Object> chunkState = fakeIndex.get("chunk-1");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING, chunkState.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertNull(chunkState.get(SchemaFields.CHUNK_VECTOR));
  }
}
