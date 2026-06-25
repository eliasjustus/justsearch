/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.loop.ops.BgeM3BackfillOps;
import io.justsearch.indexerworker.loop.ops.CombinedEnrichmentBackfillOps;
import io.justsearch.indexerworker.loop.ops.DisambiguationBackfillOps;
import io.justsearch.indexerworker.loop.ops.EmbeddingBackfillOps;
import io.justsearch.indexerworker.loop.ops.LoopPacingPolicy;
import io.justsearch.indexerworker.loop.ops.NerBackfillOps;
import io.justsearch.indexerworker.loop.ops.SpladeBackfillOps;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedules backfill work for the {@link IndexingLoop} idle branch + interleaved SPLADE for
 * the primary-indexing branch.
 *
 * <p>Tempdoc 516 Slice 4d (Wave 6) — extracted from {@link IndexingLoop}. Owns the per-cycle
 * backfill orchestration: the combined enrichment tight loop, the per-stage fallback path
 * (embedding / chunk-embed / NER / SPLADE), and disambiguation. Owns the SPLADE retry-backoff
 * state ({@code consecutiveSpladeFailures}, {@code nextSpladeRetryTime},
 * {@code lastSpladeInterleaveTime}) and the disambiguation completion latch
 * ({@code disambiguationPassComplete}, {@code lastKnownNerCompletedCount}).
 *
 * <p>P5 boundary: a concrete final class with three entry points (runIdleCycle,
 * runInterleavedSplade, resetState). No strategy interface.
 *
 * <p>Cross-seam contract: encoders/services are read via supplier closures over
 * {@link IndexingLoop}'s volatile fields so the existing async-load swap-on-the-fly semantics
 * are preserved without IndexingLoop having to push updates.
 */
public final class BackfillScheduler {

  private static final Logger log = LoggerFactory.getLogger(BackfillScheduler.class);

  static final int EMBEDDING_BACKFILL_BATCH_SIZE = LoopPacingPolicy.embeddingBackfillBatchSize();
  private static final int NER_BACKFILL_BATCH_SIZE = LoopPacingPolicy.nerBackfillBatchSize();
  private static final int DISAMBIGUATION_BACKFILL_BATCH_SIZE =
      LoopPacingPolicy.disambiguationBackfillBatchSize();
  private static final int SPLADE_BACKFILL_BATCH_SIZE = LoopPacingPolicy.spladeBackfillBatchSize();
  private static final int SPLADE_INTERLEAVE_BATCH_SIZE =
      LoopPacingPolicy.spladeInterleaveBatchSize();
  private static final int BGE_M3_BACKFILL_BATCH_SIZE = 50;
  private static final int BGE_M3_INTERLEAVE_BATCH_SIZE = 10;

  private final DocumentFieldOps documentFieldOps;
  private final IndexingCoordinator indexingCoordinator;
  private final IndexCountOps indexCountOps;
  private final CommitOps commitOps;
  private final WorkerSignalBus signalBus;
  private final EmbeddingProviderLifecycle embeddingLifecycle;
  private final AtomicBoolean running;
  private final Supplier<ResolvedConfig> resolvedConfigSupplier;
  private final Supplier<SpladeEncoder> spladeEncoderSupplier;
  private final Supplier<BgeM3Encoder> bgeM3EncoderSupplier;
  private final Supplier<NerService> nerServiceSupplier;
  private final Supplier<DisambiguationService> disambiguationServiceSupplier;

  private long lastSpladeInterleaveTime = 0;
  private int consecutiveSpladeFailures = 0;
  private long nextSpladeRetryTime = 0;
  private boolean disambiguationPassComplete = false;
  private int lastKnownNerCompletedCount = 0;

  public BackfillScheduler(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      IndexCountOps indexCountOps,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      EmbeddingProviderLifecycle embeddingLifecycle,
      AtomicBoolean running,
      Supplier<ResolvedConfig> resolvedConfigSupplier,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      Supplier<BgeM3Encoder> bgeM3EncoderSupplier,
      Supplier<NerService> nerServiceSupplier,
      Supplier<DisambiguationService> disambiguationServiceSupplier) {
    this.documentFieldOps = documentFieldOps;
    this.indexingCoordinator = indexingCoordinator;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
    this.signalBus = signalBus;
    this.embeddingLifecycle = embeddingLifecycle;
    this.running = running;
    this.resolvedConfigSupplier = resolvedConfigSupplier;
    this.spladeEncoderSupplier = spladeEncoderSupplier;
    this.bgeM3EncoderSupplier = bgeM3EncoderSupplier;
    this.nerServiceSupplier = nerServiceSupplier;
    this.disambiguationServiceSupplier = disambiguationServiceSupplier;
  }

  /**
   * Runs one backfill cycle from the idle branch. Returns {@code true} if any backfill stage
   * (combined or individual) reported progress, so the caller can pick the active-vs-truly-idle
   * sleep duration.
   *
   * <p>Self-committing: combined-backfill tight loop commits every 5 batches + a final
   * commit; individual stages commit per their own contracts.
   */
  public boolean runIdleCycle() {
    boolean backfillDidWork = false;

    boolean runBackfill =
        LoopPacingPolicy.shouldRunBackfill(
            signalBus.isMainGpuActive(),
            signalBus.isEnergyReduced(),
            embeddingLifecycle.embeddingProvider());
    if (runBackfill) {
      boolean useCombined = processCombinedBackfillIfApplicable();
      if (useCombined) {
        // 334 Phase 8/10: tight loop with persistent pending-ID caches across iterations.
        var parentIdCache = new ArrayDeque<String>();
        var chunkIdCache = new ArrayDeque<String>();
        var batchCommitCounter = new int[] {0};
        backfillDidWork = true;
        final boolean[] useCombinedRef = {useCombined};
        final int[] tightLoopBatches = {1};
        // 334 Phase 8: NRT suspend during tight loop prevents mmap accumulation from
        // ControlledRealTimeReopenThread while commits are deferred (every 5 batches).
        commitOps.withNrtSuspended(
            () -> {
              while (useCombinedRef[0]) {
                if (!running.get() || Thread.currentThread().isInterrupted()) break;
                if (signalBus.isUserActive()) break;
                if (signalBus.shouldYieldGpuBackfill()) break; // tempdoc 630: GPU-claimed OR energy-reduced
                useCombinedRef[0] =
                    processCombinedBackfillIfApplicable(
                        parentIdCache, chunkIdCache, batchCommitCounter);
                if (useCombinedRef[0]) tightLoopBatches[0]++;
              }
              if (batchCommitCounter[0] > 0) {
                commitOps.commitAndTrack(CommitReason.BACKFILL_COMBINED_FINAL);
              }
            });
        if (tightLoopBatches[0] > 1) {
          log.debug("Tight backfill loop: {} consecutive batches", tightLoopBatches[0]);
        }
      } else {
        backfillDidWork = runIndividualBackfills();
      }
    }

    // Disambiguation is gated on no-other-work-this-cycle and never flips backfillDidWork.
    runDisambiguationIfReady(backfillDidWork);
    return backfillDidWork;
  }

  /**
   * Interleaves SPLADE/BGE-M3 backfill during primary indexing (tempdoc 278 item 4a).
   * Time-gated: one small batch every spladeInterleaveIntervalMs to limit primary-indexing
   * overhead (~13%).
   */
  public void runInterleavedSplade(long now) {
    if (spladeEncoderSupplier.get() == null && bgeM3EncoderSupplier.get() == null) return;
    if (now < nextSpladeRetryTime) return;
    long spladeIntervalMs = LoopPacingPolicy.spladeInterleaveIntervalMs();
    if (now - lastSpladeInterleaveTime < spladeIntervalMs) return;
    boolean success = processSpladeBackfillInterleaved();
    lastSpladeInterleaveTime = System.currentTimeMillis();
    recordSpladeBackfillResult(success);
  }

  /** Resets backoff/latch state. Called from {@code resetForProfiling}. */
  public void resetState() {
    lastSpladeInterleaveTime = 0;
    consecutiveSpladeFailures = 0;
    nextSpladeRetryTime = 0;
    disambiguationPassComplete = false;
    lastKnownNerCompletedCount = 0;
  }

  private boolean runIndividualBackfills() {
    boolean backfillDidWork = false;
    if (embeddingLifecycle.embeddingProvider().isAvailable()) {
      processEmbeddingBackfill();
    }
    // Chunk vectors after parent embedding completes. 334 Phase 8 tight loop.
    if (resolvedConfigSupplier.get().rag().chunkVectorsEnabled()) {
      int pendingDocEmbeddings =
          indexCountOps.countByField(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      if (pendingDocEmbeddings == 0) {
        boolean chunkDidWork = processChunkEmbeddingBackfill();
        while (chunkDidWork) {
          backfillDidWork = true;
          if (!running.get() || Thread.currentThread().isInterrupted()) break;
          if (signalBus.isUserActive()) break;
          if (signalBus.shouldYieldGpuBackfill()) break; // tempdoc 630: GPU-claimed OR energy-reduced
          chunkDidWork = processChunkEmbeddingBackfill();
        }
      }
    }
    NerService nerService = nerServiceSupplier.get();
    if (nerService != null && nerService.isAvailable()) {
      boolean embeddingsReady;
      if (!embeddingLifecycle.embeddingProvider().isAvailable()) {
        embeddingsReady = true;
      } else {
        int pendingEmbeddings =
            indexCountOps.countByField(
                SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        boolean chunksPending = false;
        if (resolvedConfigSupplier.get().rag().chunkVectorsEnabled()) {
          chunksPending =
              indexCountOps.countByField(
                      SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)
                  > 0;
        }
        embeddingsReady = pendingEmbeddings == 0 && !chunksPending;
      }
      if (embeddingsReady) {
        processNerBackfill();
      }
    }
    // SPLADE after embedding nearly completes (tempdoc 312 item 39, relaxed 334 item 37).
    if ((spladeEncoderSupplier.get() != null || bgeM3EncoderSupplier.get() != null)
        && System.currentTimeMillis() >= nextSpladeRetryTime) {
      int pendingEmbedForSplade =
          embeddingLifecycle.embeddingProvider().isAvailable()
              ? indexCountOps.countByField(
                  SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)
              : 0;
      if (pendingEmbedForSplade < EMBEDDING_BACKFILL_BATCH_SIZE) {
        int spladePendingBefore =
            indexCountOps.countByField(
                SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
        boolean success = processSpladeBackfill();
        recordSpladeBackfillResult(success);
        if (success && spladePendingBefore > 0) {
          backfillDidWork = true;
        }
      }
    }
    return backfillDidWork;
  }

  private void runDisambiguationIfReady(boolean alreadyDidWork) {
    // 334 Phase 8: Disambiguation only when no enrichment work pending.
    DisambiguationService disambiguationService = disambiguationServiceSupplier.get();
    if (alreadyDidWork
        || disambiguationService == null
        || !disambiguationService.isAvailable()) {
      return;
    }
    int pendingNer =
        indexCountOps.countByField(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);
    if (pendingNer != 0) return;
    int nerCompleted =
        indexCountOps.countByField(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
    if (nerCompleted != lastKnownNerCompletedCount) {
      disambiguationPassComplete = false;
      lastKnownNerCompletedCount = nerCompleted;
    }
    if (!disambiguationPassComplete) {
      processDisambiguationBackfill();
      disambiguationPassComplete = true;
    }
  }

  private void recordSpladeBackfillResult(boolean success) {
    if (success) {
      consecutiveSpladeFailures = 0;
      nextSpladeRetryTime = 0;
    } else {
      consecutiveSpladeFailures++;
      long backoffMs =
          BackoffPolicy.spladeBackoffMs(LoopPacingPolicy.idleSleepMs(), consecutiveSpladeFailures);
      nextSpladeRetryTime = System.currentTimeMillis() + backoffMs;
      log.warn(
          "SPLADE backfill failed ({} consecutive), next retry in {}ms",
          consecutiveSpladeFailures,
          backoffMs);
    }
  }

  // ==================== Backfill delegates ====================

  private boolean processCombinedBackfillIfApplicable() {
    return processCombinedBackfillIfApplicable(null, null, null);
  }

  private boolean processCombinedBackfillIfApplicable(
      ArrayDeque<String> parentIdCache,
      ArrayDeque<String> chunkIdCache,
      int[] batchesSinceCommit) {
    boolean embedAvail =
        embeddingLifecycle.embeddingProvider().isAvailable()
            && embeddingLifecycle.allowEmbeddingWrites();
    boolean spladeAvail =
        spladeEncoderSupplier.get() != null || bgeM3EncoderSupplier.get() != null;
    NerService nerService = nerServiceSupplier.get();
    boolean nerAvail = nerService != null && nerService.isAvailable();

    int availCount = (embedAvail ? 1 : 0) + (spladeAvail ? 1 : 0) + (nerAvail ? 1 : 0);
    if (availCount < 2) {
      return false;
    }

    return CombinedEnrichmentBackfillOps.processCombinedBackfill(
        new CombinedEnrichmentBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            embeddingLifecycle::embeddingProvider,
            spladeEncoderSupplier,
            nerServiceSupplier,
            running::get,
            embeddingLifecycle::allowEmbeddingWrites,
            EMBEDDING_BACKFILL_BATCH_SIZE,
            log,
            resolvedConfigSupplier.get().rag().chunkVectorsEnabled(),
            parentIdCache != null ? parentIdCache : new ArrayDeque<>(),
            chunkIdCache != null ? chunkIdCache : new ArrayDeque<>(),
            batchesSinceCommit != null ? batchesSinceCommit : new int[] {0}));
  }

  private void processEmbeddingBackfill() {
    // BGE-M3 handles dense embeddings in its unified backfill pass — skip separate embedding
    if (bgeM3EncoderSupplier.get() != null) {
      log.debug("Embedding backfill skipped: BGE-M3 handles dense embeddings");
      return;
    }
    EmbeddingBackfillOps.processEmbeddingBackfill(
        new EmbeddingBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            embeddingLifecycle::embeddingProvider,
            running::get,
            embeddingLifecycle::allowEmbeddingWrites,
            EMBEDDING_BACKFILL_BATCH_SIZE,
            log));
  }

  private boolean processChunkEmbeddingBackfill() {
    return EmbeddingBackfillOps.processChunkEmbeddingBackfill(
        new EmbeddingBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            embeddingLifecycle::embeddingProvider,
            running::get,
            embeddingLifecycle::allowEmbeddingWrites,
            EMBEDDING_BACKFILL_BATCH_SIZE,
            log));
  }

  private void processNerBackfill() {
    NerBackfillOps.processNerBackfill(
        new NerBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            nerServiceSupplier,
            running::get,
            NER_BACKFILL_BATCH_SIZE,
            log));
  }

  private boolean processSpladeBackfill() {
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    if (bge != null) {
      return BgeM3BackfillOps.processBgeM3Backfill(
          new BgeM3BackfillOps.BackfillContext(
              documentFieldOps,
              indexingCoordinator,
              commitOps,
              signalBus,
              () -> bge,
              running::get,
              BGE_M3_BACKFILL_BATCH_SIZE,
              true,
              log));
    }
    return SpladeBackfillOps.processSpladeBackfill(
        new SpladeBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            spladeEncoderSupplier,
            running::get,
            SPLADE_BACKFILL_BATCH_SIZE,
            true,
            log));
  }

  private boolean processSpladeBackfillInterleaved() {
    BgeM3Encoder bge = bgeM3EncoderSupplier.get();
    if (bge != null) {
      return BgeM3BackfillOps.processBgeM3Backfill(
          new BgeM3BackfillOps.BackfillContext(
              documentFieldOps,
              indexingCoordinator,
              commitOps,
              signalBus,
              () -> bge,
              running::get,
              BGE_M3_INTERLEAVE_BATCH_SIZE,
              false,
              log));
    }
    return SpladeBackfillOps.processSpladeBackfill(
        new SpladeBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            spladeEncoderSupplier,
            running::get,
            SPLADE_INTERLEAVE_BATCH_SIZE,
            false,
            log));
  }

  private void processDisambiguationBackfill() {
    DisambiguationBackfillOps.processDisambiguationBackfill(
        new DisambiguationBackfillOps.BackfillContext(
            documentFieldOps,
            signalBus,
            disambiguationServiceSupplier,
            running::get,
            DISAMBIGUATION_BACKFILL_BATCH_SIZE,
            log));
  }
}
