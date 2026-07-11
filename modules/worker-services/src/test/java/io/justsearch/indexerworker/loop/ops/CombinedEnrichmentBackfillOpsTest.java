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
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Regression coverage for tempdoc 700: {@code CombinedEnrichmentBackfillOps} had no failure
 * escalation/backoff — an embedding/SPLADE/NER failure reset (or left) the doc's status PENDING
 * with no retry-count increment, so a deterministically-failing doc retried forever instead of
 * reaching {@code FAILED} at {@code *_MAX_RETRIES}, unlike its {@code EmbeddingBackfillOps} /
 * {@code SpladeBackfillOps} / {@code NerBackfillOps} siblings.
 *
 * <p>{@code DocumentFieldOps} / {@code IndexingCoordinator} are backed by an in-memory {@code
 * fakeIndex} map rather than static Mockito stubs: escalation is only observable *across* repeated
 * {@code processCombinedBackfill} cycles (retry count N feeds cycle N+1's read), so a fixed stub
 * would give a false green regardless of whether escalation actually happened
 * (unreachable-seed-green, see agent-lessons.md). This mirrors how {@code
 * EmbeddingBackfillOpsTest} builds its fixtures, adapted for the combined path's batched
 * pre-fetch + single-flush design.
 */
@DisplayName("CombinedEnrichmentBackfillOps")
@ExtendWith(MockitoExtension.class)
class CombinedEnrichmentBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock SpladeEncoder spladeEncoder;
  @Mock NerService nerService;

  // LinkedHashMap: queryDocIdsByField below iterates this map, and the SPLADE partial-write
  // test depends on doc-a being requested (and thus indexed) before doc-b — a plain HashMap's
  // iteration order is not guaranteed to match seed order.
  private final Map<String, Map<String, Object>> fakeIndex = new java.util.LinkedHashMap<>();
  private final Map<String, String> contentByDoc = new HashMap<>();
  private final Set<String> poisonContents = new HashSet<>();

  @BeforeEach
  void wireFakeIndexAndDefaults() {
    lenient().when(signalBus.isUserActive()).thenReturn(false);
    lenient().when(embeddingProvider.isAvailable()).thenReturn(true);
    lenient().when(nerService.isAvailable()).thenReturn(true);

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
              Set<String> fields = inv.getArgument(1);
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
                fakeIndex
                    .computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                    .putAll(entry.getValue());
                count++;
              }
              return new LuceneRuntimeTypes.BatchUpdateResult(count, 0);
            });

    lenient()
        .when(embeddingProvider.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              List<float[]> out = new ArrayList<>();
              for (String t : texts) {
                out.add(poisonContents.contains(t) ? null : new float[] {1f, 2f});
              }
              return out;
            });
  }

  private void seedDoc(String docId, String content, Map<String, String> statusFields) {
    contentByDoc.put(docId, content);
    Map<String, Object> state = fakeIndex.computeIfAbsent(docId, k -> new HashMap<>());
    state.putAll(statusFields);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled, boolean spladeEnabled, boolean nerEnabled) {
    return context(embedEnabled, spladeEnabled, nerEnabled, false);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled, boolean spladeEnabled, boolean nerEnabled, boolean lateChunkingEnabled) {
    return context(embedEnabled, spladeEnabled, nerEnabled, lateChunkingEnabled, false, false);
  }

  private CombinedEnrichmentBackfillOps.BackfillContext context(
      boolean embedEnabled,
      boolean spladeEnabled,
      boolean nerEnabled,
      boolean lateChunkingEnabled,
      boolean chunkVectorsEnabled,
      boolean chunkSpladeEnabled) {
    return new CombinedEnrichmentBackfillOps.BackfillContext(
        documentFieldOps,
        indexingCoordinator,
        commitOps,
        signalBus,
        embedEnabled ? () -> embeddingProvider : () -> null,
        spladeEnabled ? () -> spladeEncoder : () -> null,
        nerEnabled ? () -> nerService : () -> null,
        () -> true,
        () -> true,
        100,
        LoggerFactory.getLogger(CombinedEnrichmentBackfillOpsTest.class),
        chunkVectorsEnabled,
        chunkSpladeEnabled,
        lateChunkingEnabled,
        50,
        new ArrayDeque<>(),
        new ArrayDeque<>(),
        new int[] {0});
  }

  private CombinedEnrichmentBackfillOps.BackfillContext embedOnlyContext() {
    return context(true, false, false);
  }

  /** Seeds a chunk doc (IS_CHUNK/PARENT_DOC_ID/CHUNK_INDEX/span fields) directly into fakeIndex. */
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

  @Test
  @DisplayName("embedding failure increments retry count on the batched write (no status change)")
  void embeddingFailure_incrementsRetryCount_onSingleCycle() {
    seedDoc(
        "doc-bad",
        "poison content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    poisonContents.add("poison content");

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).anyWorkDone();

    assertTrue(didWork);
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals("1", docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        docState.get(SchemaFields.EMBEDDING_STATUS),
        "must not be marked FAILED before EMBEDDING_MAX_RETRIES is reached");
    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
    verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
    verify(documentFieldOps, never()).getDocumentField(anyString(), anyString());
  }

  @Test
  @DisplayName(
      "embedding failure reaches FAILED at EMBEDDING_MAX_RETRIES and stops being re-selected")
  void embeddingFailure_reachesFailedAtMaxRetries_andStopsBeingReselected() {
    seedDoc(
        "doc-bad",
        "poison content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    poisonContents.add("poison content");

    for (int cycle = 1; cycle < SchemaFields.EMBEDDING_MAX_RETRIES; cycle++) {
      boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).anyWorkDone();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending doc");
      Map<String, Object> docState = fakeIndex.get("doc-bad");
      assertEquals(String.valueOf(cycle), docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals(
          SchemaFields.EMBEDDING_STATUS_PENDING, docState.get(SchemaFields.EMBEDDING_STATUS));
    }

    // Final cycle: retry count reaches EMBEDDING_MAX_RETRIES -> FAILED.
    boolean lastCycleDidWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).anyWorkDone();
    assertTrue(lastCycleDidWork);
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals(
        String.valueOf(SchemaFields.EMBEDDING_MAX_RETRIES),
        docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.EMBEDDING_STATUS_FAILED, docState.get(SchemaFields.EMBEDDING_STATUS));

    // Poison-pill stops being re-selected: a further cycle finds nothing pending and does no work.
    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).anyWorkDone();
    assertFalse(ranAgain, "a FAILED doc must not be re-selected for another attempt");
    verify(embeddingProvider, times(SchemaFields.EMBEDDING_MAX_RETRIES))
        .embedDocumentBatch(anyList());
  }

  @Test
  @DisplayName("embedding success path is unchanged: vector written, retry count reset to 0")
  void embeddingSuccess_writesVectorAndResetsRetryCount() {
    seedDoc(
        "doc-ok",
        "good content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).anyWorkDone();

    assertTrue(didWork);
    Map<String, Object> docState = fakeIndex.get("doc-ok");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, docState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", docState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertNotNull(docState.get(SchemaFields.VECTOR));
  }

  @Test
  @DisplayName(
      "mixed batch: one doc succeeds and one fails, each gets the right per-doc update, in a"
          + " single batched write")
  void mixedBatch_oneSucceedsOneFails_perDocUpdatesCorrect_singleBatchedWrite() {
    seedDoc(
        "doc-ok",
        "good content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedDoc(
        "doc-bad",
        "poison content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    poisonContents.add("poison content");

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(embedOnlyContext()).anyWorkDone();

    assertTrue(didWork);
    Map<String, Object> okState = fakeIndex.get("doc-ok");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, okState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", okState.get(SchemaFields.EMBEDDING_RETRY_COUNT));

    Map<String, Object> badState = fakeIndex.get("doc-bad");
    assertEquals("1", badState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, badState.get(SchemaFields.EMBEDDING_STATUS));

    // Tempdoc-312 invariant: exactly one batched write per cycle, never a per-doc updateDocument.
    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
    verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
  }

  @Test
  @DisplayName("SPLADE failure increments retry count then reaches FAILED at SPLADE_MAX_RETRIES")
  void spladeFailure_incrementsRetryCount_thenReachesFailedAtMaxRetries() throws Exception {
    seedDoc(
        "doc-bad",
        "splade poison",
        Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
    when(spladeEncoder.encodeBatch(anyList())).thenThrow(new RuntimeException("encoder boom"));

    for (int cycle = 1; cycle < SchemaFields.SPLADE_MAX_RETRIES; cycle++) {
      boolean didWork =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false))
              .anyWorkDone();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending doc");
      Map<String, Object> docState = fakeIndex.get("doc-bad");
      assertEquals(String.valueOf(cycle), docState.get(SchemaFields.SPLADE_RETRY_COUNT));
      assertNull(docState.get(SchemaFields.SPLADE_STATUS_FAILED));
      assertEquals(SchemaFields.SPLADE_STATUS_PENDING, docState.get(SchemaFields.SPLADE_STATUS));
    }

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals(
        String.valueOf(SchemaFields.SPLADE_MAX_RETRIES), docState.get(SchemaFields.SPLADE_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_FAILED, docState.get(SchemaFields.SPLADE_STATUS));

    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false))
            .anyWorkDone();
    assertFalse(ranAgain, "a FAILED doc must not be re-selected for another attempt");
  }

  @Test
  @DisplayName(
      "SPLADE batch catch does not clobber a doc that already got a successful write earlier in"
          + " the same batch (partial-result-then-exception case)")
  void spladeBatchPartialWrite_doesNotClobberAlreadyCompletedDocOnEscalation() throws Exception {
    seedDoc(
        "doc-a",
        "splade content a",
        Map.of(
            SchemaFields.SPLADE_STATUS,
            SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.SPLADE_RETRY_COUNT,
            "0"));
    seedDoc(
        "doc-b",
        "splade content b",
        Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));

    // Encoder returns a short (size-1) result for a 2-doc request: index 0 succeeds, index 1
    // throws IndexOutOfBoundsException inside the loop — same trigger class as the AIOOBE
    // crash-loop this codebase has hardened against elsewhere (EmbeddingBackfillOps).
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false));

    Map<String, Object> aState = fakeIndex.get("doc-a");
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, aState.get(SchemaFields.SPLADE_STATUS));
    assertEquals(
        "0",
        aState.get(SchemaFields.SPLADE_RETRY_COUNT),
        "doc-a's successful write must not be overwritten by the batch-catch escalation");

    Map<String, Object> bState = fakeIndex.get("doc-b");
    assertEquals("1", bState.get(SchemaFields.SPLADE_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_PENDING, bState.get(SchemaFields.SPLADE_STATUS));
  }

  @Test
  @DisplayName("NER failure increments retry count then reaches FAILED at NER_MAX_RETRIES")
  void nerFailure_incrementsRetryCount_thenReachesFailedAtMaxRetries() throws Exception {
    seedDoc(
        "doc-bad",
        "ner poison",
        Map.of(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    when(nerService.extractEntitiesBatch(anyList())).thenThrow(new RuntimeException("ner boom"));

    for (int cycle = 1; cycle < SchemaFields.NER_MAX_RETRIES; cycle++) {
      boolean didWork =
          CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, false, true))
              .anyWorkDone();
      assertTrue(didWork, "cycle " + cycle + " should still find the pending doc");
      Map<String, Object> docState = fakeIndex.get("doc-bad");
      assertEquals(String.valueOf(cycle), docState.get(SchemaFields.NER_RETRY_COUNT));
      assertEquals(SchemaFields.NER_STATUS_PENDING, docState.get(SchemaFields.NER_STATUS));
    }

    CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, false, true));
    Map<String, Object> docState = fakeIndex.get("doc-bad");
    assertEquals(
        String.valueOf(SchemaFields.NER_MAX_RETRIES), docState.get(SchemaFields.NER_RETRY_COUNT));
    assertEquals(SchemaFields.NER_STATUS_FAILED, docState.get(SchemaFields.NER_STATUS));

    boolean ranAgain =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, false, true))
            .anyWorkDone();
    assertFalse(ranAgain, "a FAILED doc must not be re-selected for another attempt");
  }

  @Test
  @DisplayName(
      "all three encoders in one cycle: an embedding failure for a doc does not clobber that same"
          + " doc's successful SPLADE/NER writes, and everything flushes in one batched write")
  void allThreeEncoders_embeddingFailsOthersSucceed_singleMergedBatchedWrite() throws Exception {
    seedDoc(
        "doc-multi",
        "multi content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    poisonContents.add("multi content");
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));
    when(nerService.extractEntitiesBatch(anyList()))
        .thenReturn(List.of(new NerResult(List.of("Alice"), List.of(), List.of())));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, true))
            .anyWorkDone();

    assertTrue(didWork);
    Map<String, Object> state = fakeIndex.get("doc-multi");
    // Embedding failed -> escalated, not clobbered by the later SPLADE/NER phases.
    assertEquals("1", state.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, state.get(SchemaFields.EMBEDDING_STATUS));
    // SPLADE and NER succeeded independently in the same per-doc entry.
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_COMPLETED, state.get(SchemaFields.NER_STATUS));
    assertEquals("Alice", state.get(SchemaFields.ENTITY_PERSONS_TEXT));

    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
    verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
  }

  // ---------------------------------------------------------------------------------------
  // Tempdoc 691 forensics fold-in: the late-chunking single-pass embed strategy is now a
  // sub-phase INSIDE the combined pass's own embed phase (Phase 3a-i), not a separate RMW pass.
  // A separate pass's VECTOR write used to be silently destroyed by this pass's later
  // SPLADE/NER-only RMW for the same doc (Lucene RMW drops non-stored fields absent from the
  // current write — VECTOR is non-stored) — live evidence: vector nDCG 0.016 on legal-clerc.
  // Folding the strategy in means the vector always lands in the SAME per-doc update map as
  // SPLADE/NER, so every doc still gets exactly one bundled write. §Phase M's CLS-pooling finding
  // still holds: this strategy is VECTOR-only, chunk docs keep their existing separate per-chunk
  // CLS embed path untouched.
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "late-chunking flag ON: chunked parent's VECTOR comes from embedWithSpans (whole doc, empty"
          + " span array) and lands in the SAME per-doc update map as its SPLADE/NER results — one"
          + " bundled write, embedDocumentBatch never called for this doc's content")
  void lateChunking_flagOn_chunkedParent_singlePassVectorBundledWithSpladeAndNer() throws Exception {
    seedDoc(
        "parent-1",
        "parent content here, long enough to be chunked",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-1", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);
    seedChunkDoc("chunk-2", "parent-1", 1, 10, 20, SchemaFields.EMBEDDING_STATUS_PENDING);

    float[] docVector = {1f, 2f};
    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenReturn(new EmbeddingService.ChunkedEmbedding(docVector, List.of(), 1));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));
    when(nerService.extractEntitiesBatch(anyList()))
        .thenReturn(List.of(new NerResult(List.of("Alice"), List.of(), List.of())));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, true, true))
            .anyWorkDone();

    assertTrue(didWork);
    verify(embeddingProvider, times(1))
        .embedWithSpans(
            eq("parent content here, long enough to be chunked"),
            argThat(spans -> spans.length == 0));
    verify(embeddingProvider, never()).embedDocumentBatch(anyList());

    Map<String, Object> parentState = fakeIndex.get("parent-1");
    assertArrayEquals(docVector, (float[]) parentState.get(SchemaFields.VECTOR));
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertEquals("0", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, parentState.get(SchemaFields.SPLADE_STATUS));
    assertEquals(SchemaFields.NER_STATUS_COMPLETED, parentState.get(SchemaFields.NER_STATUS));
    assertEquals("Alice", parentState.get(SchemaFields.ENTITY_PERSONS_TEXT));

    // Chunk docs are untouched — VECTOR-only mode never derives a CHUNK_VECTOR, and
    // chunkVectorsEnabled=false in this harness keeps them out of the batch entirely.
    Map<String, Object> chunk1State = fakeIndex.get("chunk-1");
    assertNull(chunk1State.get(SchemaFields.CHUNK_VECTOR));
    Map<String, Object> chunk2State = fakeIndex.get("chunk-2");
    assertNull(chunk2State.get(SchemaFields.CHUNK_VECTOR));

    // Tempdoc-312 invariant: exactly one batched write, containing VECTOR+SPLADE+NER together
    // for parent-1 — the whole point of the fold-in is that no separate RMW can drop the vector.
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getKey().equals("parent-1")
                        && list.get(0).getValue().containsKey(SchemaFields.VECTOR)
                        && list.get(0).getValue().containsKey(SchemaFields.SPLADE)
                        && list.get(0).getValue().containsKey(SchemaFields.NER_STATUS)));
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: embedWithSpans returns null (content exceeds the raised single-pass"
          + " limit) — folds INLINE into the ordinary windowed batch (embedDocumentBatch), still"
          + " ONE bundled write for the parent")
  void lateChunking_flagOn_overLimitParent_nullEmbedWithSpans_foldsIntoWindowedBatch() {
    seedDoc(
        "parent-long",
        "content that exceeds the raised single-pass limit",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-long", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class))).thenReturn(null);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, true))
            .anyWorkDone();

    assertTrue(didWork);
    verify(embeddingProvider, times(1)).embedWithSpans(anyString(), any(int[][].class));
    verify(embeddingProvider, times(1))
        .embedDocumentBatch(
            argThat(
                texts ->
                    texts.size() == 1
                        && texts.contains("content that exceeds the raised single-pass limit")));

    Map<String, Object> parentState = fakeIndex.get("parent-long");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
    Map<String, Object> chunkState = fakeIndex.get("chunk-1");
    assertNull(chunkState.get(SchemaFields.CHUNK_VECTOR));

    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(list -> list.size() == 1 && list.get(0).getKey().equals("parent-long")));
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: GPU arena-OOM (wrapped RuntimeException) on the single-pass call —"
          + " folds INLINE into the windowed batch same as the over-limit null case")
  void lateChunking_flagOn_arenaOom_foldsIntoWindowedBatch() {
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
        .thenThrow(
            new RuntimeException("Late-chunking embed failed: " + arenaOom.getMessage(), arenaOom));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, true))
            .anyWorkDone();

    assertTrue(didWork);
    verify(embeddingProvider, times(1))
        .embedDocumentBatch(argThat(texts -> texts.contains("poison parent content")));

    Map<String, Object> parentState = fakeIndex.get("parent-oom");
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "windowed fallback success completes the parent, same as any other embed success");
    assertEquals("0", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(list -> list.size() == 1 && list.get(0).getKey().equals("parent-oom")));
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: embedWithSpans throws a non-arena-OOM exception — PARENT ONLY gets"
          + " retry-count escalation (tempdoc 700 parity), not marked complete; SPLADE still"
          + " succeeds independently in the same bundled write")
  void lateChunking_flagOn_embedWithSpansThrowsNonArenaOom_escalatesParentOnly_spladeStillBundled()
      throws Exception {
    seedDoc(
        "parent-bad",
        "poison parent content",
        Map.of(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-bad", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    when(embeddingProvider.embedWithSpans(anyString(), any(int[][].class)))
        .thenThrow(new RuntimeException("ORT boom"));
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, true, false, true))
            .anyWorkDone();

    assertTrue(didWork, "a failure-escalation write is still recorded as work");
    verify(embeddingProvider, never()).embedDocumentBatch(anyList());

    Map<String, Object> parentState = fakeIndex.get("parent-bad");
    assertEquals("1", parentState.get(SchemaFields.EMBEDDING_RETRY_COUNT));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_PENDING,
        parentState.get(SchemaFields.EMBEDDING_STATUS),
        "must not be marked FAILED before EMBEDDING_MAX_RETRIES is reached");
    // SPLADE succeeded independently in the SAME per-doc entry, not clobbered by the embed
    // failure.
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, parentState.get(SchemaFields.SPLADE_STATUS));

    Map<String, Object> chunkState = fakeIndex.get("chunk-1");
    assertNull(chunkState.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));

    verify(indexingCoordinator, times(1)).updateDocumentsBatch(anyList());
  }

  @Test
  @DisplayName(
      "late-chunking flag ON: a chunkless parent (no PARENT_DOC_ID-matching chunk docs) uses the"
          + " normal windowed batch — embedWithSpans is never called for it")
  void lateChunking_flagOn_chunklessParent_usesNormalWindowedBatch() {
    seedDoc(
        "parent-chunkless",
        "chunkless parent content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, true))
            .anyWorkDone();

    assertTrue(didWork);
    verify(embeddingProvider, never()).embedWithSpans(anyString(), any());
    verify(embeddingProvider, times(1))
        .embedDocumentBatch(
            argThat(texts -> texts.size() == 1 && texts.contains("chunkless parent content")));

    Map<String, Object> parentState = fakeIndex.get("parent-chunkless");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
  }

  @Test
  @DisplayName(
      "late-chunking flag OFF: strict no-op vs today — combined pass embeds a chunked parent via"
          + " the ordinary windowed batch, embedWithSpans is never called")
  void lateChunkingOff_chunkedParentStillEmbedsInCombinedPass_embedWithSpansNeverCalled() {
    seedDoc(
        "parent-chunked",
        "chunked parent content",
        Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING));
    seedChunkDoc("chunk-1", "parent-chunked", 0, 0, 10, SchemaFields.EMBEDDING_STATUS_PENDING);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(true, false, false, false))
            .anyWorkDone();

    assertTrue(didWork);
    verify(embeddingProvider, never()).embedWithSpans(anyString(), any());
    Map<String, Object> parentState = fakeIndex.get("parent-chunked");
    assertEquals(SchemaFields.EMBEDDING_STATUS_COMPLETED, parentState.get(SchemaFields.EMBEDDING_STATUS));
    assertNotNull(parentState.get(SchemaFields.VECTOR));
    verify(embeddingProvider, times(1))
        .embedDocumentBatch(argThat(texts -> texts.contains("chunked parent content")));
  }

  // ---------------------------------------------------------------------------------------
  // Tempdoc 712: chunk-level SPLADE enrichment (flag-gated, default OFF). Chunk docs are
  // seeded splade_status=PENDING at index time (ChunkDocumentWriter) and picked up by the
  // combined pass's splade-status query, but they carry CHUNK_CONTENT, never CONTENT — so the
  // parent lane's blank-content early-out historically marked their splade COMPLETED without
  // ever encoding (silent data-less COMPLETED; the mechanism behind the dead chunk-sparse
  // sub-leg, F-033). Flag OFF pins that historical behavior byte-identically; flag ON encodes
  // chunk_content into the splade FeatureField inside the same single bundled write.
  // ---------------------------------------------------------------------------------------

  /** Seeds a chunk doc carrying CHUNK_CONTENT + a splade status (712 chunk-sparse fixtures). */
  private void seedSpladeChunkDoc(
      String chunkId, String parentId, String chunkContent, String spladeStatus,
      String chunkEmbeddingStatusOrNull) {
    Map<String, Object> state = fakeIndex.computeIfAbsent(chunkId, k -> new HashMap<>());
    state.put(SchemaFields.IS_CHUNK, "true");
    state.put(SchemaFields.PARENT_DOC_ID, parentId);
    state.put(SchemaFields.CHUNK_CONTENT, chunkContent);
    state.put(SchemaFields.SPLADE_STATUS, spladeStatus);
    if (chunkEmbeddingStatusOrNull != null) {
      state.put(SchemaFields.CHUNK_EMBEDDING_STATUS, chunkEmbeddingStatusOrNull);
    }
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF (default): a splade-PENDING chunk doc is marked COMPLETED without"
          + " encoding — pins the historical silent data-less COMPLETED behavior byte-identically")
  void chunkSpladeOff_chunkDocSpladePending_markedCompletedWithoutEncode() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(context(false, true, false))
            .anyWorkDone();

    assertTrue(didWork, "the status-marker write is still work");
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertNull(state.get(SchemaFields.SPLADE), "flag off must never write sparse data");
    verify(spladeEncoder, never()).encodeBatch(anyList());
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag ON, parent-lane pickup (splade-status query): a splade-PENDING chunk doc"
          + " is encoded from CHUNK_CONTENT and completed in ONE bundled write")
  void chunkSpladeOn_parentLanePickup_encodesChunkContent_oneBundledWrite() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1", "parent-1", "chunk body text", SchemaFields.SPLADE_STATUS_PENDING, null);
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 1.0f))));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(false, true, false, false, false, true))
            .anyWorkDone();

    assertTrue(didWork);
    verify(spladeEncoder, times(1))
        .encodeBatch(argThat(texts -> texts.size() == 1 && texts.contains("chunk body text")));
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertEquals(Map.of("tok", 1.0f), state.get(SchemaFields.SPLADE));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    assertEquals("0", state.get(SchemaFields.SPLADE_RETRY_COUNT));
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getKey().equals("chunk-1")
                        && list.get(0).getValue().containsKey(SchemaFields.SPLADE)));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag ON, chunk-lane pickup: CHUNK_VECTOR and SPLADE land in the SAME single"
          + " bundled write; a COMPLETED splade is re-derived rather than destroyed-and-requeued"
          + " (the RMW cannot carry postings it does not re-derive — tempdoc 711 reset-status)")
  void chunkSpladeOn_chunkLane_denseAndSpladeOneBundledWrite_reDerivesCompletedSplade()
      throws Exception {
    seedSpladeChunkDoc(
        "chunk-1",
        "parent-1",
        "chunk body text",
        SchemaFields.SPLADE_STATUS_COMPLETED,
        SchemaFields.EMBEDDING_STATUS_PENDING);
    when(spladeEncoder.encodeBatch(anyList()))
        .thenReturn(new ArrayList<>(List.of(Map.of("tok", 2.0f))));

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(true, true, false, false, true, true))
            .anyWorkDone();

    assertTrue(didWork);
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertNotNull(state.get(SchemaFields.CHUNK_VECTOR));
    assertEquals(
        SchemaFields.EMBEDDING_STATUS_COMPLETED, state.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals(Map.of("tok", 2.0f), state.get(SchemaFields.SPLADE));
    assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, state.get(SchemaFields.SPLADE_STATUS));
    verify(indexingCoordinator, times(1))
        .updateDocumentsBatch(
            argThat(
                list ->
                    list.size() == 1
                        && list.get(0).getKey().equals("chunk-1")
                        && list.get(0).getValue().containsKey(SchemaFields.CHUNK_VECTOR)
                        && list.get(0).getValue().containsKey(SchemaFields.SPLADE)));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag ON: a splade-FAILED chunk doc is NOT resurrected — dense enrichment"
          + " proceeds, sparse is left alone (poison-pill respected)")
  void chunkSpladeOn_spladeFailedRespected_noResurrect() throws Exception {
    seedSpladeChunkDoc(
        "chunk-1",
        "parent-1",
        "chunk body text",
        SchemaFields.SPLADE_STATUS_FAILED,
        SchemaFields.EMBEDDING_STATUS_PENDING);

    boolean didWork =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
                context(true, true, false, false, true, true))
            .anyWorkDone();

    assertTrue(didWork);
    Map<String, Object> state = fakeIndex.get("chunk-1");
    assertNotNull(state.get(SchemaFields.CHUNK_VECTOR));
    assertNull(state.get(SchemaFields.SPLADE), "FAILED splade must not be re-encoded");
    assertEquals(SchemaFields.SPLADE_STATUS_FAILED, state.get(SchemaFields.SPLADE_STATUS));
    verify(spladeEncoder, never()).encodeBatch(anyList());
  }
}
