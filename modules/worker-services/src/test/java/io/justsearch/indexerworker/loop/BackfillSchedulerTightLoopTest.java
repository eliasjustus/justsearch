/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tempdoc 798 — structural containment for the ingest livelock.
 *
 * <p>The worker's single {@code indexing-loop} thread alternates between polling the ingest job
 * queue and running background enrichment backfill. The combined-enrichment tight loop used to
 * continue on {@code CombinedOutcome.anyWorkDone()} — defined as {@code written > 0}, i.e. "the
 * batch write touched at least one Lucene doc". That is ACTIVITY, not PROGRESS: two documents that
 * are rewritten every batch but never advance a stage pin it true forever. In the field that spun
 * ~64 times/second for 20+ minutes on 2 documents (59,420 identical INFO lines, zero WARN, zero
 * ERROR) while every user ingest queued behind it sat unclaimed and every health surface stayed
 * green.
 *
 * <p>This fixture reproduces exactly that input — the doc-id query returns the SAME two ids on
 * every call, the batch write always reports 2 updated, and no stage ever advances (blank content,
 * so both the SPLADE and NER phases skip the docs while the write still lands the two
 * status-flip fields). It then pins the three independent containments:
 *
 * <ol>
 *   <li>the loop terminates on PROGRESS ({@code progressed}), not activity;
 *   <li>pending ingest work breaks the loop, so primary indexing always beats backfill;
 *   <li>a wall-clock budget returns control regardless of any future non-converging input.
 * </ol>
 *
 * <p>Bite check: reverting the tight loop's condition to {@code wroteAnything()} makes
 * {@link #nonConvergingBatch_runIdleCycleStillReturns()} hang until its
 * {@code assertTimeoutPreemptively} fires.
 */
@DisplayName("BackfillScheduler tight-loop containment (tempdoc 798)")
@ExtendWith(MockitoExtension.class)
class BackfillSchedulerTightLoopTest {

  /** The two documents that can never advance — the shape of the field incident. */
  private static final List<String> STUCK_DOC_IDS = List.of("stuck-doc-1", "stuck-doc-2");

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock IndexCountOps indexCountOps;
  @Mock CommitOps commitOps;
  @Mock WorkerSignalBus signalBus;
  @Mock JobQueue jobQueue;
  @Mock EmbeddingProvider embeddingProvider;
  @Mock SpladeEncoder spladeEncoder;
  @Mock NerService nerService;

  /** Counts combined batches so "at most one batch" is assertable. */
  private final AtomicInteger batchWrites = new AtomicInteger();

  @BeforeEach
  void wirePathologicalState() {
    lenient().when(signalBus.isUserActive()).thenReturn(false);
    lenient().when(signalBus.isMainGpuActive()).thenReturn(false);
    lenient().when(signalBus.isEnergyReduced()).thenReturn(false);
    lenient().when(signalBus.shouldYieldGpuBackfill()).thenReturn(false);
    lenient().when(signalBus.hasPendingIngest()).thenReturn(false);
    lenient().when(indexCountOps.countByField(anyString(), anyString())).thenReturn(0);

    // The tight loop runs inside withNrtSuspended — a bare void mock would no-op the whole body.
    lenient()
        .doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(commitOps)
        .withNrtSuspended(any());

    // Embedding unavailable; SPLADE + NER available => availCount == 2 => combined mode.
    lenient().when(embeddingProvider.isAvailable()).thenReturn(false);
    lenient().when(embeddingProvider.isUsingGpu()).thenReturn(false);
    lenient().when(nerService.isAvailable()).thenReturn(true);

    // (1) The doc-id query NEVER drains: the same two ids come back on every call, however many
    // times the batch has already "succeeded" on them.
    lenient()
        .when(documentFieldOps.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenReturn(STUCK_DOC_IDS);

    // (2) Blank content: neither the SPLADE nor the NER phase can advance these docs, so every
    // per-stage processed/failed count stays 0.
    lenient().when(documentFieldOps.getDocumentContentBatch(anyList())).thenReturn(Map.of());
    lenient()
        .when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, Map<String, String>> result = new HashMap<>();
              for (String id : ids) {
                result.put(
                    id,
                    Map.of(
                        SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                        SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
              }
              return result;
            });

    // (3) The write always claims both docs were updated — `written > 0` forever.
    lenient()
        .when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv -> {
              batchWrites.incrementAndGet();
              List<?> batch = inv.getArgument(0);
              return new LuceneRuntimeTypes.BatchUpdateResult(batch.size(), 0);
            });
  }

  private BackfillScheduler scheduler() {
    EmbeddingProviderLifecycle embeddingLifecycle =
        new EmbeddingProviderLifecycle(signalBus, jobQueue, indexCountOps, commitOps);
    embeddingLifecycle.setEmbeddingProvider(embeddingProvider);
    return new BackfillScheduler(
        documentFieldOps,
        indexingCoordinator,
        indexCountOps,
        commitOps,
        signalBus,
        embeddingLifecycle,
        new AtomicBoolean(true),
        this::resolvedConfig,
        () -> spladeEncoder,
        () -> null,
        () -> nerService,
        () -> null);
  }

  private ResolvedConfig resolvedConfig() {
    ResolvedConfig config = mock(ResolvedConfig.class);
    ResolvedConfig.Rag rag = mock(ResolvedConfig.Rag.class);
    lenient().when(rag.chunkVectorsEnabled()).thenReturn(false);
    lenient().when(rag.chunkSpladeEnabled()).thenReturn(false);
    lenient().when(config.rag()).thenReturn(rag);
    ResolvedConfig.Ai ai = mock(ResolvedConfig.Ai.class);
    ResolvedConfig.Ai.Embedding embedding = mock(ResolvedConfig.Ai.Embedding.class);
    lenient().when(embedding.lateChunkingEnabled()).thenReturn(false);
    lenient().when(ai.embedding()).thenReturn(embedding);
    lenient().when(ai.backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    lenient().when(config.ai()).thenReturn(ai);
    return config;
  }

  @Test
  @DisplayName(
      "two documents that write but never advance a stage: runIdleCycle() still RETURNS "
          + "(pre-fix it spins forever, starving the ingest poll)")
  void nonConvergingBatch_runIdleCycleStillReturns() {
    BackfillScheduler scheduler = scheduler();

    // The 5s ceiling is deliberately just above the scheduler's own CYCLE_BUDGET_MS: even if the
    // progress-based termination were removed, the hard budget alone must bring control back.
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        scheduler::runIdleCycle,
        "runIdleCycle() must return on a non-converging enrichment population — a loop that only"
            + " terminates on `written > 0` never returns to the ingest poll (tempdoc 798)");

    verify(indexingCoordinator, atLeastOnce()).updateDocumentsBatch(anyList());
  }

  @Test
  @DisplayName("pending ingest work breaks the tight loop after at most one combined batch")
  void pendingIngest_yieldsAfterOneBatch() {
    when(signalBus.hasPendingIngest()).thenReturn(true);
    BackfillScheduler scheduler = scheduler();

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        scheduler::runIdleCycle,
        "runIdleCycle() must return promptly when ingest jobs are waiting");

    // One batch is the mode-selection probe outside the tight loop; the guard fires before the
    // loop's first batch, so a second write would mean the signal is not consulted.
    assertTrue(
        batchWrites.get() <= 1,
        "background enrichment must yield to pending ingest after at most one combined batch,"
            + " observed "
            + batchWrites.get()
            + " batches");
  }
}
