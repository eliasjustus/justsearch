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
 * Dedicated coverage for {@link LateChunkingEmbedBackfillOps} (tempdoc 691 §Phase M/L-8: VECTOR-
 * only mode) — the happy path (parent VECTOR only, chunk docs untouched), proof that chunk-span
 * data is never even read, the long-doc-deferred path, parent-only failure escalation, and the
 * flag-off strict no-op.
 *
 * <p>§Phase M's offline CLS check found the earlier design's per-span {@code CHUNK_VECTOR}
 * derivation REGRESSES retrieval quality on this CLS-pooled model (nDCG@10 −0.2329) — the pass now
 * embeds the whole doc in one pass and writes ONLY the parent {@code VECTOR}; chunk docs keep their
 * existing separate per-chunk CLS embed path untouched. This is a requirement CHANGE driven by
 * measurement, not a test-weakening (see CLAUDE.md's fix-root-causes rule) — the removed
 * span-count-mismatch test in particular is obsolete because the pass no longer reads chunk span
 * fields at all.
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
      "flag ON, happy path: chunked parent embeds once (whole doc, VECTOR-only), parent VECTOR"
          + " written, chunk docs NOT touched, SPLADE/NER never touched")
  void happyPath_writesParentVectorOnly_leavesChunksAndSpladeNerAlone() {
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
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, List.of(), 1));

    boolean didWork =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(true));

    assertTrue(didWork);

    // embedWithSpans called with an EMPTY span array — VECTOR-only, no chunk spans derived.
    verify(embeddingProvider).embedWithSpans(anyString(), argThat(spans -> spans.length == 0));

    Map<String, Object> parentState = fakeIndex.get("parent-1");
    assertArrayEquals(docVector, (float[]) parentState.get(SchemaFields.VECTOR));
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_PENDING, parentState.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_PENDING, parentState.get(SchemaFields.NER_STATUS));

    // Chunk docs are completely untouched — no CHUNK_VECTOR, status stays PENDING.
    Map<String, Object> chunk1State = fakeIndex.get("chunk-1");
    assertNull(chunk1State.get(SchemaFields.CHUNK_VECTOR));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING, chunk1State.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    Map<String, Object> chunk2State = fakeIndex.get("chunk-2");
    assertNull(chunk2State.get(SchemaFields.CHUNK_VECTOR));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING, chunk2State.get(SchemaFields.CHUNK_EMBEDDING_STATUS));

    // Only the parent doc is in the write batch — single-entry batched write.
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(argThat(list -> list.size() == 1 && list.get(0).getKey().equals("parent-1")));
    verify(commitOps, times(1)).commitAndTrack(any());
    verify(log, times(1))
        .info(anyString(), eq(1), eq(0), eq(0), eq(0), anyLong(), anyLong());
  }

  @Test
  @DisplayName(
      "flag ON: chunk-span metadata (CHUNK_INDEX/CHUNK_START_CHAR/CHUNK_END_CHAR) is never read —"
          + " the VECTOR-only pass doesn't consult chunk internals at all (tempdoc 691 §Phase M)")
  void neverReadsChunkSpanMetadata() {
    seedDoc(
        "parent-1",
        "parent content long enough to be chunked",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-1", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedChunkDoc("chunk-2", "parent-1", 1, 10, 20, SchemaFields.EMBEDDING_STATUS_PENDING);

    float[] docVector = {1f, 2f};
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, List.of(), 1));

    LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfill(lateChunkingContext(true));

    // getDocumentFieldsBatch (the only seam that could read CHUNK_START_CHAR/CHUNK_END_CHAR/
    // CHUNK_INDEX) is never called on the success path — only the failure-escalation path reads
    // it, and only for the parent's own EMBEDDING_RETRY_COUNT.
    verify(documentFieldOps, never()).getDocumentFieldsBatch(anyList(), anySet());
  }

  @Test
  @DisplayName(
      "flag ON, long-doc deferred: embedWithSpans returns null -> nothing written, parent status"
          + " stays PENDING, counted as deferred, and the summary log fires even though"
          + " processed=failed=0")
  void longDoc_embedWithSpansReturnsNull_deferredAndLoggedDespiteZeroWork() {
    seedDoc(
        "parent-long",
        "content that exceeds the raised single-pass limit",
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

    // The core of the tempdoc 691 Wave 0 visibility fix: the log fires with longDocDeferred=1 and
    // processed=failed=0, so an all-deferred cycle is not invisible.
    verify(log, times(1))
        .info(anyString(), eq(0), eq(0), eq(1), eq(0), anyLong(), anyLong());
  }

  @Test
  @DisplayName(
      "flag ON, embedWithSpans throws: PARENT ONLY gets retry-count escalation (tempdoc 700"
          + " parity via the shared computeEmbeddingFailureUpdate helper); chunk docs are"
          + " untouched — VECTOR-only mode never embedded them in the first place")
  void embedWithSpansThrows_escalatesParentOnly() {
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

    Map<String, Object> expectedParentUpdate =
        EmbeddingBackfillOps.computeEmbeddingFailureUpdate(0);

    Map<String, Object> parentState = fakeIndex.get("parent-bad");
    assertEquals(
        expectedParentUpdate.get(SchemaFields.EMBEDDING_RETRY_COUNT),
        parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals("1", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "must not be marked FAILED before EMBEDDING_MAX_RETRIES is reached");

    // Chunk docs are untouched — no CHUNK_EMBEDDING_RETRY_COUNT bump, status stays PENDING.
    for (String chunkId : List.of("chunk-1", "chunk-2")) {
      Map<String, Object> chunkState = fakeIndex.get(chunkId);
      assertNull(chunkState.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      assertEquals(
          SchemaFields.EMBEDDING_STATUS_PENDING, chunkState.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    }

    // Only the parent is in the failure-escalation write — parent-only, not parent+chunks.
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(argThat(list -> list.size() == 1 && list.get(0).getKey().equals("parent-bad")));
    verify(commitOps, times(1)).commitAndTrack(any());
  }

  @Test
  @DisplayName(
      "flag ON, GPU arena-OOM (wrapped RuntimeException): parent is DEFERRED like the long-doc"
          + " case — no retry-count bump, stays PENDING, no failure escalation — because"
          + " EmbeddingService#embedWithSpans wraps the raw OrtException in a RuntimeException")
  void arenaOom_wrappedRuntimeException_deferredNotFailed() {
    seedDoc(
        "parent-oom",
        "poison parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.EMBEDDING_RETRY_COUNT, "0"));
    seedChunkDoc("chunk-1", "parent-oom", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    OrtException arenaOom =
        new OrtException(
            "BFCArena::AllocateRawInternal: Available memory of 536870912 is smaller than"
                + " requested bytes of 1073741824");
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenThrow(new RuntimeException("Late-chunking embed failed: " + arenaOom.getMessage(), arenaOom));

    LateChunkingEmbedBackfillOps.LateChunkingBackfillResult result =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfillDetailed(
            lateChunkingContext(true));

    assertFalse(
        result.hasProgress(),
        "an arena-OOM-only batch must NOT report progress — the scheduler's drain loop relies on"
            + " this to stop instead of re-querying and re-deferring the same parent forever");
    assertEquals(0, result.processedParents());
    assertEquals(0, result.failedParents());
    assertEquals(1, result.arenaOomDeferred());
    assertEquals(0, result.longDocDeferred());

    Map<String, Object> parentState = fakeIndex.get("parent-oom");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "must stay PENDING — arena OOM is resource contention, not a bad-input failure");
    assertEquals(
        "0",
        parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT),
        "must NOT burn a retry increment — the windowed fallback handles this parent fine");
    assertNull(parentState.get(SchemaFields.VECTOR));
    verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
    verify(commitOps, never()).commitAndTrack(any());
  }

  // NOTE: no separate "raw unwrapped OrtException" test — EmbeddingProvider#embedWithSpans does
  // not declare `throws OrtException` (it's a checked exception), so no compliant implementation
  // can let it escape undeclared; EmbeddingService#embedWithSpans always wraps it in a
  // RuntimeException (verified: Mockito itself rejects stubbing a raw checked-exception throw on
  // this interface method — "Checked exception is invalid for this method!"). The wrapped-path
  // test above is therefore the only reachable shape; isArenaOomFailure's cause-chain walk stays
  // defensive for future callers/implementations, but only the wrapped path is exercised here.

  @Test
  @DisplayName(
      "drain-loop contract: a deferral-only batch (long-doc + arena-OOM, zero processed/failed)"
          + " reports hasProgress()=false so the scheduler's while-loop stops")
  void deferralOnlyBatch_hasProgressFalse() {
    seedDoc(
        "parent-longdoc",
        "content that exceeds the raised single-pass limit",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-longdoc", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedDoc(
        "parent-oomonly",
        "oom-only content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-2", "parent-oomonly", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    OrtException arenaOom =
        new OrtException(
            "BFCArena::AllocateRawInternal: Available memory of 1 is smaller than requested bytes"
                + " of 2");
    when(embeddingProvider.embedWithSpans(
            eq("content that exceeds the raised single-pass limit"), any(int[][].class)))
        .thenReturn(null);
    when(embeddingProvider.embedWithSpans(eq("oom-only content"), any(int[][].class)))
        .thenThrow(new RuntimeException("Late-chunking embed failed: " + arenaOom.getMessage(), arenaOom));

    LateChunkingEmbedBackfillOps.LateChunkingBackfillResult result =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfillDetailed(
            lateChunkingContext(true));

    assertFalse(result.hasProgress(), "deferral-only batch must not signal loop-continuation");
    assertEquals(0, result.processedParents());
    assertEquals(0, result.failedParents());
    assertEquals(1, result.longDocDeferred());
    assertEquals(1, result.arenaOomDeferred());
  }

  @Test
  @DisplayName(
      "drain-loop contract: a mixed batch (one processed, one arena-OOM-deferred) reports"
          + " hasProgress()=true so the scheduler's while-loop runs another batch")
  void mixedBatch_processedAndDeferred_hasProgressTrue() {
    seedDoc(
        "parent-ok",
        "processable content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-ok", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedDoc(
        "parent-oom",
        "oom content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.EMBEDDING_RETRY_COUNT, "0"));
    seedChunkDoc("chunk-2", "parent-oom", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    float[] docVector = {3f, 4f};
    OrtException arenaOom =
        new OrtException(
            "BFCArena::AllocateRawInternal: Available memory of 1 is smaller than requested bytes"
                + " of 2");
    when(embeddingProvider.embedWithSpans(eq("processable content"), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, List.of(), 1));
    when(embeddingProvider.embedWithSpans(eq("oom content"), any(int[][].class)))
        .thenThrow(new RuntimeException("Late-chunking embed failed: " + arenaOom.getMessage(), arenaOom));

    LateChunkingEmbedBackfillOps.LateChunkingBackfillResult result =
        LateChunkingEmbedBackfillOps.processLateChunkingEmbedBackfillDetailed(
            lateChunkingContext(true));

    assertTrue(
        result.hasProgress(),
        "one real processed parent must signal progress even though the other was deferred");
    assertEquals(1, result.processedParents());
    assertEquals(0, result.failedParents());
    assertEquals(1, result.arenaOomDeferred());

    Map<String, Object> okState = fakeIndex.get("parent-ok");
    assertArrayEquals(docVector, (float[]) okState.get(SchemaFields.VECTOR));
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, okState.get(SchemaFields.EMBEDDING_STATUS));

    Map<String, Object> oomState = fakeIndex.get("parent-oom");
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, oomState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", oomState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
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
