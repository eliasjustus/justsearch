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
import io.justsearch.indexerworker.loop.ops.StageOutcome;
import io.justsearch.indexerworker.metrics.BatchTimingKeys;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
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

  /**
   * Hard wall-clock budget for one {@link #runIdleCycle()} backfill burst (tempdoc 798).
   *
   * <p>Containment of last resort: whatever the input, control returns to {@code IndexingLoop
   * .runLoop()} — and therefore to the job-queue poll — within this window. The livelock this
   * bounds spun ~64 times/second on 2 documents for 20+ minutes and produced 59,420 identical
   * INFO lines with zero WARN and zero ERROR, so tripping the budget logs at WARN.
   */
  private static final long CYCLE_BUDGET_MS = 5_000L;

  // Tempdoc 710 Wave-1.5 Move 4: the per-stage backfill batch sizes (formerly static fields
  // computed once from LoopPacingPolicy, plus the BGE-M3 pair which bypassed LoopPacingPolicy
  // entirely as bare literals here) all moved onto ResolvedConfig.Ai.BackfillPacing
  // (justsearch.backfill.* config surface). See the pacing() helper below — call sites read the
  // live snapshot the same way resolvedConfigSupplier.get().rag().chunkVectorsEnabled() already
  // does elsewhere in this class.

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
   * Resolves the current enrichment-backfill pacing snapshot (tempdoc 710 Wave-1.5 Move 4). Falls
   * back to {@link ResolvedConfig.Ai.BackfillPacing#DEFAULTS} — byte-identical to the pre-Move-4
   * hardcoded literals — when no config is available, e.g. a test double supplying {@code () ->
   * null} for {@code resolvedConfigSupplier}.
   */
  private ResolvedConfig.Ai.BackfillPacing pacing() {
    ResolvedConfig config = resolvedConfigSupplier.get();
    return config != null ? config.ai().backfillPacing() : ResolvedConfig.Ai.BackfillPacing.DEFAULTS;
  }

  /**
   * Runs one backfill cycle from the idle branch. Returns {@code true} if any backfill stage
   * (combined or individual) reported progress, so the caller can pick the active-vs-truly-idle
   * sleep duration.
   *
   * <p>Self-committing: combined-backfill tight loop commits every 5 batches + a final
   * commit; individual stages commit per their own contracts.
   *
   * <p>Bounded by construction (tempdoc 798): every loop inside terminates on PROGRESS rather than
   * activity, yields to pending ingest work, and is capped by {@link #CYCLE_BUDGET_MS}. Control
   * returns to the caller's job-queue poll regardless of what the enrichment population does.
   */
  public boolean runIdleCycle() {
    boolean backfillDidWork = false;
    final long cycleDeadlineNanos =
        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CYCLE_BUDGET_MS);

    boolean runBackfill =
        LoopPacingPolicy.shouldRunBackfill(
            signalBus.isMainGpuActive(),
            signalBus.isEnergyReduced(),
            embeddingLifecycle.embeddingProvider());
    if (runBackfill) {
      CombinedEnrichmentBackfillOps.CombinedOutcome outcome =
          processCombinedBackfillIfApplicable(cycleDeadlineNanos);
      recordCombinedOutcome(outcome);
      // Mode selection reads ACTIVITY (did the combined pass touch anything at all); the tight
      // loop below reads PROGRESS. Tempdoc 798: these are different questions and conflating
      // them is what livelocked ingest.
      boolean useCombined = outcome.wroteAnything();
      if (useCombined) {
        // Tempdoc 710 Move 2 item 4: this cycle used the combined pass — the only path taken
        // between here and the next runIdleCycle() call (the tight loop below stays combined
        // until it drains or is interrupted), so the mode is settled at this point.
        OperationalMetrics.getInstance().recordBackfillMode("combined");
        // 334 Phase 8/10: tight loop with persistent pending-ID caches across iterations.
        var parentIdCache = new ArrayDeque<String>();
        var chunkIdCache = new ArrayDeque<String>();
        var batchCommitCounter = new int[] {0};
        backfillDidWork = true;
        // Tempdoc 809 finding 3: an aborted probe means a stop condition came up DURING that batch
        // — pending ingest, the cycle budget, or a bulk deletion that invalidated the documents it
        // had selected. Starting a fresh tight-loop batch would re-select and re-encode against
        // that same condition, which is how the mid-batch signal ended up costing a further ~5s of
        // GPU instead of returning control. Mode selection itself is unchanged: this cycle still
        // used the combined pass, and the next idle cycle picks the enrichment up where it stopped.
        final boolean[] useCombinedRef = {!outcome.aborted()};
        final int[] tightLoopBatches = {1};
        // Tempdoc 809 finding 3: the mode-selection probe above is a full batch and is now
        // interruptible too, so it can be the call that spends the budget. Seeding from it keeps
        // the 798 WARN honest — without this, a cycle whose ONLY batch overran would report
        // nothing at all.
        final boolean[] budgetTripped = {outcome.aborted() && System.nanoTime() >= cycleDeadlineNanos};
        // Tempdoc 798 review F3: cumulative across the whole cycle, mode-selection probe included.
        // Budget exhaustion means very different things depending on this flag, and the diagnostic
        // must not assert non-convergence when enrichment was demonstrably converging.
        final boolean[] anyProgressThisCycle = {outcome.progressed()};
        // 334 Phase 8: NRT suspend during tight loop prevents mmap accumulation from
        // ControlledRealTimeReopenThread while commits are deferred (every 5 batches).
        commitOps.withNrtSuspended(
            () -> {
              while (useCombinedRef[0]) {
                if (!running.get() || Thread.currentThread().isInterrupted()) break;
                if (signalBus.isUserActive()) break;
                if (signalBus.shouldYieldGpuBackfill()) break; // tempdoc 630: GPU-claimed OR energy-reduced
                // Tempdoc 798: primary indexing outranks background enrichment. Backfill resumes
                // next idle cycle; a queued ingest job the user is waiting on cannot.
                if (signalBus.hasPendingIngest()) break;
                if (System.nanoTime() >= cycleDeadlineNanos) {
                  budgetTripped[0] = true;
                  break;
                }
                CombinedEnrichmentBackfillOps.CombinedOutcome tightLoopOutcome =
                    processCombinedBackfillIfApplicable(
                        parentIdCache, chunkIdCache, batchCommitCounter, cycleDeadlineNanos);
                recordCombinedOutcome(tightLoopOutcome);
                // Tempdoc 798: PROGRESS, not activity. `wroteAnything()` stays true forever for a
                // document that is rewritten every batch without ever advancing a stage.
                useCombinedRef[0] = tightLoopOutcome.progressed();
                if (useCombinedRef[0]) {
                  anyProgressThisCycle[0] = true;
                  tightLoopBatches[0]++;
                }
                // Tempdoc 809 finding 3: the batch left its own document set unfinished because a
                // stop condition came up mid-batch. That condition is still true, so starting
                // another batch would only re-discover it after another sub-batch of GPU work.
                // Re-read the deadline first: an aborted batch is the most likely way the budget
                // is now spent, and breaking without this would silently drop 798's WARN.
                if (tightLoopOutcome.aborted()) {
                  if (System.nanoTime() >= cycleDeadlineNanos) budgetTripped[0] = true;
                  break;
                }
              }
              if (batchCommitCounter[0] > 0) {
                commitOps.commitAndTrack(CommitReason.BACKFILL_COMBINED_FINAL);
              }
            });
        if (budgetTripped[0]) {
          // Tempdoc 798 review F3: the budget is smaller than two large legitimate enrichment
          // batches, so its most common trigger is a healthy cycle that simply ran out of window.
          // Only a cycle in which NOTHING advanced is the non-convergence this WARN describes;
          // crying wolf on the healthy case trains operators to ignore the one signal 798 added.
          // Both branches return control to the job-queue poll — only the log level differs.
          if (anyProgressThisCycle[0]) {
            log.info(
                "Combined enrichment backfill reached its {}ms cycle budget after {} batches while"
                    + " still advancing documents — returning to the job-queue poll; the remaining"
                    + " enrichment resumes next idle cycle (tempdoc 798).",
                CYCLE_BUDGET_MS,
                tightLoopBatches[0]);
          } else {
            log.warn(
                "Combined enrichment backfill hit its {}ms cycle budget after {} batches with ZERO"
                    + " stage advancement — returning to the job-queue poll (tempdoc 798). This is"
                    + " the non-converging shape: the same documents are rewritten every batch"
                    + " without any stage advancing.",
                CYCLE_BUDGET_MS,
                tightLoopBatches[0]);
          }
        }
        if (tightLoopBatches[0] > 1) {
          log.debug("Tight backfill loop: {} consecutive batches", tightLoopBatches[0]);
        }
      } else {
        OperationalMetrics.getInstance().recordBackfillMode("individual");
        backfillDidWork = runIndividualBackfills(cycleDeadlineNanos);
      }
    } else {
      OperationalMetrics.getInstance().recordBackfillMode("idle");
    }

    // Disambiguation is gated on no-other-work-this-cycle and never flips backfillDidWork.
    runDisambiguationIfReady(backfillDidWork);
    return backfillDidWork;
  }

  /**
   * Records one {@link CombinedEnrichmentBackfillOps.CombinedOutcome}'s per-stage counts/timing
   * into {@link OperationalMetrics} (tempdoc 710 Move 2 item 4 — replaces the {@code finally}
   * block that used to live inside {@code CombinedEnrichmentBackfillOps.processCombinedBackfill}).
   * A no-op when {@code outcome.recordTiming()} is false (nothing ran this call).
   */
  private static void recordCombinedOutcome(CombinedEnrichmentBackfillOps.CombinedOutcome outcome) {
    if (!outcome.recordTiming()) {
      return;
    }
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.recordEnrichmentCompleted(BatchTimingKeys.EMBED, outcome.embedProcessed());
    metrics.recordEnrichmentCompleted(BatchTimingKeys.SPLADE, outcome.spladeProcessed());
    metrics.recordEnrichmentCompleted(BatchTimingKeys.NER, outcome.nerProcessed());
    metrics.recordStageTiming(BatchTimingKeys.EMBED, outcome.embedProcessed(), outcome.embedMs());
    metrics.recordStageTiming(
        BatchTimingKeys.SPLADE, outcome.spladeProcessed(), outcome.spladeMs());
    metrics.recordStageTiming(BatchTimingKeys.NER, outcome.nerProcessed(), outcome.nerMs());
    metrics.recordBatchTiming(BatchTimingKeys.FETCH, outcome.fetchMs());
    metrics.recordBatchTiming(BatchTimingKeys.WRITE, outcome.writeMs());
    metrics.recordBatchTiming(BatchTimingKeys.TOTAL, outcome.totalMs());
  }

  /**
   * Records one {@link StageOutcome}'s doc-count/timing into {@link OperationalMetrics} under the
   * given {@link BatchTimingKeys} key (tempdoc 710 Move 2 item 4 — individual-mode counters
   * previously froze because none of the individual {@code *BackfillOps} classes recorded
   * anything; only the combined pass did, 710 S-B3 finding).
   */
  private static void recordStageOutcome(String stageKey, StageOutcome outcome) {
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.recordEnrichmentCompleted(stageKey, outcome.docsProcessed());
    metrics.recordStageTiming(stageKey, outcome.docsProcessed(), outcome.elapsedMs());
  }

  /**
   * Interleaves SPLADE/BGE-M3 backfill during primary indexing (tempdoc 278 item 4a).
   * Time-gated: one small batch every spladeInterleaveIntervalMs to limit primary-indexing
   * overhead (~13%).
   */
  public void runInterleavedSplade(long now) {
    if (spladeEncoderSupplier.get() == null && bgeM3EncoderSupplier.get() == null) return;
    if (now < nextSpladeRetryTime) return;
    long spladeIntervalMs = pacing().spladeInterleaveIntervalMs();
    if (now - lastSpladeInterleaveTime < spladeIntervalMs) return;
    StageOutcome outcome = processSpladeBackfillInterleaved();
    recordStageOutcome(BatchTimingKeys.SPLADE, outcome);
    lastSpladeInterleaveTime = System.currentTimeMillis();
    recordSpladeBackfillResult(outcome.success());
  }

  /** Resets backoff/latch state. Called from {@code resetForProfiling}. */
  public void resetState() {
    lastSpladeInterleaveTime = 0;
    consecutiveSpladeFailures = 0;
    nextSpladeRetryTime = 0;
    disambiguationPassComplete = false;
    lastKnownNerCompletedCount = 0;
  }

  private boolean runIndividualBackfills(long cycleDeadlineNanos) {
    boolean backfillDidWork = false;
    if (embeddingLifecycle.embeddingProvider().isAvailable()) {
      StageOutcome outcome = processEmbeddingBackfill();
      recordStageOutcome(BatchTimingKeys.EMBED, outcome);
    }
    // Chunk vectors after parent embedding completes. 334 Phase 8 tight loop.
    if (resolvedConfigSupplier.get().rag().chunkVectorsEnabled()) {
      int pendingDocEmbeddings =
          indexCountOps.countByField(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      if (pendingDocEmbeddings == 0) {
        StageOutcome chunkOutcome = processChunkEmbeddingBackfill();
        recordStageOutcome(BatchTimingKeys.EMBED, chunkOutcome);
        boolean chunkDidWork = chunkOutcome.success();
        // Tempdoc 798 review F3: same distinction as the combined loop — a budget hit while chunks
        // were genuinely embedding is a healthy long cycle, not non-convergence.
        boolean anyChunkProgress = chunkOutcome.docsProcessed() > 0;
        while (chunkDidWork) {
          backfillDidWork = true;
          if (!running.get() || Thread.currentThread().isInterrupted()) break;
          if (signalBus.isUserActive()) break;
          if (signalBus.shouldYieldGpuBackfill()) break; // tempdoc 630: GPU-claimed OR energy-reduced
          if (signalBus.hasPendingIngest()) break; // tempdoc 798: primary indexing outranks backfill
          if (System.nanoTime() >= cycleDeadlineNanos) {
            if (anyChunkProgress) {
              log.info(
                  "Chunk-embedding backfill reached its {}ms cycle budget while still embedding"
                      + " chunks — returning to the job-queue poll; the remainder resumes next idle"
                      + " cycle (tempdoc 798).",
                  CYCLE_BUDGET_MS);
            } else {
              log.warn(
                  "Chunk-embedding backfill hit its {}ms cycle budget with ZERO chunks embedded —"
                      + " returning to the job-queue poll (tempdoc 798).",
                  CYCLE_BUDGET_MS);
            }
            break;
          }
          chunkOutcome = processChunkEmbeddingBackfill();
          recordStageOutcome(BatchTimingKeys.EMBED, chunkOutcome);
          chunkDidWork = chunkOutcome.success();
          if (chunkOutcome.docsProcessed() > 0) anyChunkProgress = true;
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
        StageOutcome outcome = processNerBackfill();
        recordStageOutcome(BatchTimingKeys.NER, outcome);
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
      if (pendingEmbedForSplade < pacing().embeddingBackfillBatchSize()) {
        int spladePendingBefore =
            indexCountOps.countByField(
                SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
        StageOutcome outcome = processSpladeBackfill();
        recordStageOutcome(BatchTimingKeys.SPLADE, outcome);
        boolean success = outcome.success();
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
    // Tempdoc 798 review F4: deliberately COMPLETED only, NOT COMPLETED_EMPTY. This counter is a
    // change-detector for "new entities exist, so the disambiguation pass is stale". A
    // COMPLETED_EMPTY document is one NER ran on and found no entities — it contributes nothing to
    // the entity graph, so a batch that finishes entirely as COMPLETED_EMPTY genuinely has nothing
    // to re-disambiguate and must not re-trigger a full pass. A mixed batch still moves this
    // counter via its entity-bearing documents, so nothing is missed. (Contrast the coverage
    // counters in IndexCountOps, which ask "is the stage done?" — there both tokens must sum.)
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

  private CombinedEnrichmentBackfillOps.CombinedOutcome processCombinedBackfillIfApplicable(
      long cycleDeadlineNanos) {
    return processCombinedBackfillIfApplicable(null, null, null, cycleDeadlineNanos);
  }

  /**
   * The composite "stop starting new enrichment work" signal handed to one combined batch (tempdoc
   * 809 finding 3). Exactly the conditions the tight loop below breaks on, evaluated at the batch's
   * internal sub-batch boundaries instead of only between batches — a batch is ~150 documents and
   * was measured at ~63 s, so between-batches was a 12x overshoot of the very budget it enforced.
   */
  private boolean shouldStopBackfillWork(long cycleDeadlineNanos) {
    return !running.get()
        || Thread.currentThread().isInterrupted()
        || signalBus.isUserActive()
        || signalBus.shouldYieldGpuBackfill()
        || signalBus.hasPendingIngest()
        || System.nanoTime() >= cycleDeadlineNanos;
  }

  private CombinedEnrichmentBackfillOps.CombinedOutcome processCombinedBackfillIfApplicable(
      ArrayDeque<String> parentIdCache,
      ArrayDeque<String> chunkIdCache,
      int[] batchesSinceCommit,
      long cycleDeadlineNanos) {
    boolean embedAvail =
        embeddingLifecycle.embeddingProvider().isAvailable()
            && embeddingLifecycle.allowEmbeddingWrites();
    boolean spladeAvail =
        spladeEncoderSupplier.get() != null || bgeM3EncoderSupplier.get() != null;
    NerService nerService = nerServiceSupplier.get();
    boolean nerAvail = nerService != null && nerService.isAvailable();

    int availCount = (embedAvail ? 1 : 0) + (spladeAvail ? 1 : 0) + (nerAvail ? 1 : 0);
    if (availCount < 2) {
      return CombinedEnrichmentBackfillOps.CombinedOutcome.none();
    }

    ResolvedConfig.Ai.BackfillPacing pacing = pacing();
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
            pacing.embeddingBackfillBatchSize(),
            log,
            resolvedConfigSupplier.get().rag().chunkVectorsEnabled(),
            resolvedConfigSupplier.get().rag().chunkSpladeEnabled(),
            resolvedConfigSupplier.get().ai().embedding().lateChunkingEnabled(),
            pacing.chunkSlotsPerBatch(),
            parentIdCache != null ? parentIdCache : new ArrayDeque<>(),
            chunkIdCache != null ? chunkIdCache : new ArrayDeque<>(),
            batchesSinceCommit != null ? batchesSinceCommit : new int[] {0},
            () -> shouldStopBackfillWork(cycleDeadlineNanos)));
  }

  private StageOutcome processEmbeddingBackfill() {
    // BGE-M3 handles dense embeddings in its unified backfill pass — skip separate embedding
    if (bgeM3EncoderSupplier.get() != null) {
      log.debug("Embedding backfill skipped: BGE-M3 handles dense embeddings");
      return StageOutcome.none();
    }
    return EmbeddingBackfillOps.processEmbeddingBackfill(
        new EmbeddingBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            embeddingLifecycle::embeddingProvider,
            running::get,
            embeddingLifecycle::allowEmbeddingWrites,
            pacing().embeddingBackfillBatchSize(),
            log));
  }

  private StageOutcome processChunkEmbeddingBackfill() {
    return EmbeddingBackfillOps.processChunkEmbeddingBackfill(
        new EmbeddingBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            embeddingLifecycle::embeddingProvider,
            running::get,
            embeddingLifecycle::allowEmbeddingWrites,
            pacing().embeddingBackfillBatchSize(),
            log));
  }

  private StageOutcome processNerBackfill() {
    return NerBackfillOps.processNerBackfill(
        new NerBackfillOps.BackfillContext(
            documentFieldOps,
            indexingCoordinator,
            commitOps,
            signalBus,
            nerServiceSupplier,
            running::get,
            pacing().nerBackfillBatchSize(),
            log));
  }

  private StageOutcome processSpladeBackfill() {
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
              pacing().bgeM3BackfillBatchSize(),
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
            pacing().spladeBackfillBatchSize(),
            true,
            log));
  }

  private StageOutcome processSpladeBackfillInterleaved() {
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
              pacing().bgeM3InterleaveBatchSize(),
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
            pacing().spladeInterleaveBatchSize(),
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
            pacing().disambiguationBackfillBatchSize(),
            log));
  }
}
