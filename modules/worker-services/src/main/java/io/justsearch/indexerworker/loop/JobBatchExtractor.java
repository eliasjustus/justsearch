/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.BudgetExceededException;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ProcessExtractionSandbox;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor.ExtractionTimeoutException;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts content from a batch of {@link JobQueue.IndexJob}s.
 *
 * <p>Tempdoc 516 Slice 4a.3 — extracted from {@link IndexingLoop}. Owns the
 * per-job admission / unmodified-check / Tika extraction / validation path
 * and the per-batch {@code indexEmptyForBatch} cache. Returns the surviving
 * {@link ExtractedJob} list; failed / skipped / deleted / stale jobs are
 * recorded into the ledger + {@link BatchStats} internally and don't appear
 * in the return value.
 *
 * <p>Cross-seam state — {@code indexedSinceCommit} — is reported back via
 * the {@link LongConsumer} {@code indexedDelta} callback when the
 * STALE_DONE path triggers a missing-source delete. The residue owns the
 * counter.
 *
 * <p>P5 boundary: a concrete class with two entry points (extractAll +
 * resetPerBatchCache). No strategy interface.
 */
public final class JobBatchExtractor {

  private static final Logger log = LoggerFactory.getLogger(JobBatchExtractor.class);
  private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("indexing");

  private final WorkerIngestionAuthority ingestionAuthority;
  private final IngestionOutcomeJournal journal;
  private final JobQueue jobQueue;
  private final TimeboxedContentExtractor contentExtractor;
  private final DocumentFieldOps documentFieldOps;
  private final IndexCountOps indexCountOps;
  private final BatchStats batchStats;
  private final StaleSnapshotResolver staleResolver;
  private final StaleSourceHandler staleSourceHandler;
  private final WorkerSignalBus signalBus;
  private final AtomicBoolean running;
  private final Set<String> forcedPaths;
  private final Supplier<PathResolutionStore> pathResolutionStoreSupplier;
  private final IndexingDocumentOps.StageRecorder stageRecorder;
  private final BooleanSupplier detailedTracingSupplier;
  private final LongConsumer indexedDelta;

  private boolean indexEmptyForBatch;

  public JobBatchExtractor(
      WorkerIngestionAuthority ingestionAuthority,
      IngestionOutcomeJournal journal,
      JobQueue jobQueue,
      TimeboxedContentExtractor contentExtractor,
      DocumentFieldOps documentFieldOps,
      IndexCountOps indexCountOps,
      BatchStats batchStats,
      StaleSnapshotResolver staleResolver,
      StaleSourceHandler staleSourceHandler,
      WorkerSignalBus signalBus,
      AtomicBoolean running,
      Set<String> forcedPaths,
      Supplier<PathResolutionStore> pathResolutionStoreSupplier,
      IndexingDocumentOps.StageRecorder stageRecorder,
      BooleanSupplier detailedTracingSupplier,
      LongConsumer indexedDelta) {
    this.ingestionAuthority = ingestionAuthority;
    this.journal = journal;
    this.jobQueue = jobQueue;
    this.contentExtractor = contentExtractor;
    this.documentFieldOps = documentFieldOps;
    this.indexCountOps = indexCountOps;
    this.batchStats = batchStats;
    this.staleResolver = staleResolver;
    this.staleSourceHandler = staleSourceHandler;
    this.signalBus = signalBus;
    this.running = running;
    this.forcedPaths = forcedPaths;
    this.pathResolutionStoreSupplier = pathResolutionStoreSupplier;
    this.stageRecorder = stageRecorder;
    this.detailedTracingSupplier = detailedTracingSupplier;
    this.indexedDelta = indexedDelta;
  }

  /**
   * Extracts content for each job in the batch. Records skipped/failed/stale
   * outcomes internally; returns only the jobs that survived to a valid
   * {@link ExtractedJob}.
   *
   * <p>Refreshes the per-batch {@code indexEmptyForBatch} cache as the first
   * step so the per-job loop can short-circuit {@code isUnmodified} on an
   * empty index (312 item 10).
   */
  public List<ExtractedJob> extractAll(List<JobQueue.IndexJob> jobs) {
    indexEmptyForBatch = indexCountOps.docCount() == 0;

    List<ExtractedJob> extracted = new ArrayList<>(jobs.size());
    for (JobQueue.IndexJob job : jobs) {
      if (!running.get()) break;
      if (signalBus.isUserActive()) break;

      ExtractedJob ex = extractJob(job.path(), job.collection());
      if (ex != null) {
        extracted.add(ex);
      }
    }
    return extracted;
  }

  /** Resets the per-batch index-empty cache. Called from {@code resetForProfiling}. */
  public void resetPerBatchCache() {
    indexEmptyForBatch = false;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private ExtractedJob extractJob(Path filePath, String collection) {
    log.debug("Processing: {}", filePath);

    long startTime = System.currentTimeMillis();
    FileEnvelope envelope = null;

    try {
      SourceAdmission admission = ingestionAuthority.admit(filePath);
      if (admission.action() == SourceAdmissionAction.STALE_DONE) {
        log.debug("File not found, treating as delete: {}", filePath);
        bestEffortDeleteMissingSource(filePath);
        journal.recordOutcomeSafely(
            filePath,
            "STALE_DONE",
            () -> jobQueue.markDone(filePath, admission.outcome(), ledgerEntry(filePath, collection)));
        batchStats.recordSkipped();
        return null;
      }

      if (admission.action() == SourceAdmissionAction.RETRYABLE_FAILURE) {
        log.warn("File not readable, marking as failed: {}", filePath);
        journal.recordOutcomeSafely(
            filePath,
            admission.outcome().outcomeClass().name(),
            () -> jobQueue.markFailed(filePath, admission.outcome(), ledgerEntry(filePath, collection)));
        journal.recordFailedMetric(filePath, null);
        batchStats.recordFailed();
        return null;
      }

      if (admission.action() == SourceAdmissionAction.SKIP_DONE) {
        JobQueue.IngestionLedgerEntry entry =
            admission.envelope() != null
                ? ledgerEntry(admission.envelope(), collection, null)
                : ledgerEntry(filePath, collection);
        journal.recordOutcomeSafely(
            filePath,
            admission.outcome().outcomeClass().name(),
            () -> jobQueue.markDone(filePath, admission.outcome(), entry));
        batchStats.recordSkipped();
        return null;
      }
      envelope = admission.envelope();
      // Tempdoc 419 / T5.2 (ADR-0028): record (pathHash, normalizedPath) into the scoped
      // reverse-lookup store so the activity panel can later answer "which file is this hash?".
      pathResolutionStoreSupplier.get().record(
          envelope.pathHash(), envelope.normalizedPath(), envelope.observedAtMs());
      try {
        String normalizedPath = envelope.normalizedPath();
        boolean forceReindex = forcedPaths.remove(normalizedPath);
        // Skip isUnmodified() on empty index — every doc is new (312 item 10).
        if (!forceReindex && !indexEmptyForBatch) {
          if (documentFieldOps.isUnmodified(normalizedPath, envelope.modifiedAtMs())) {
            log.debug("File unchanged, skipping: {}", filePath);
            FileEnvelope envelopeForLedger = envelope;
            journal.recordOutcomeSafely(
                filePath,
                "UNCHANGED",
                () ->
                    jobQueue.markDone(
                        filePath,
                        journal.skipped(IngestionReasonCodes.UNCHANGED),
                        ledgerEntry(envelopeForLedger, collection, null)));
            batchStats.recordSkipped();
            return null;
          }
        } else if (forceReindex) {
          log.debug("Force reindex requested, bypassing unchanged check: {}", filePath);
        }
      } catch (RuntimeException e) {
        log.debug("Could not check modification state, proceeding with extraction: {}", filePath);
      }

      Span extractSpan = maybeSpan("indexing.extract");
      extractSpan.setAttribute("doc.path", filePath.toString());
      long extractStart = System.currentTimeMillis();
      ValidatedExtractionArtifact artifact;
      try {
        // W1.5 — inlined what used to be extractContent + validateArtifact wrappers.
        ExtractionArtifact rawArtifact = contentExtractor.extractArtifact(filePath);
        String sourcePathHash = envelope != null ? envelope.pathHash() : null;
        artifact = rawArtifact.validate(contentExtractor.extractionPolicy(), sourcePathHash);
      } finally {
        extractSpan.end();
      }
      stageRecorder.record("extract", System.currentTimeMillis() - extractStart, null);

      if (staleResolver.tryHandleStale(filePath, envelope, collection, artifact, "after extraction")) {
        batchStats.recordSkipped();
        return null;
      }

      return new ExtractedJob(filePath, collection, artifact, startTime, envelope);

    } catch (BudgetExceededException e) {
      log.warn("Extraction budget exceeded for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "BUDGET_EXCEEDED",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.BUDGET_EXCEEDED,
                      e.reasonCode(),
                      IngestionRetryPolicy.NONE,
                      "Extraction budget exceeded"),
                  ledgerEntry(envelopeForLedger, collection, null)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (ExtractionTimeoutException e) {
      log.warn("Extraction timeout for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "PARSER_TIMEOUT",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.PARSER_TIMEOUT,
                      IngestionReasonCodes.PARSER_TIMEOUT,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      "Parser timed out"),
                  ledgerEntry(envelopeForLedger, collection, null)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (ProcessExtractionSandbox.SandboxExtractionException e) {
      log.warn("Extraction sandbox failed for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "SANDBOX_FAILED",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.SANDBOX_FAILED,
                      IngestionReasonCodes.SANDBOX_FAILED,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      "Sandbox failed"),
                  ledgerEntry(envelopeForLedger, collection, null)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (ContentExtractor.ExtractionException e) {
      log.warn("Content extraction failed for: {} - {}", filePath, e.getMessage());
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "PARSER_FAILED(terminal)",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.PARSER_FAILED,
                      IngestionReasonCodes.PARSER_FAILED,
                      IngestionRetryPolicy.NONE,
                      "Parser failed"),
                  ledgerEntry(envelopeForLedger, collection, null)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (IOException e) {
      log.error("IO error processing: {}", filePath, e);
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "IO_FAILED",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.IO_FAILED,
                      IngestionReasonCodes.IO_ERROR,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      "I/O failure"),
                  ledgerEntry(envelopeForLedger, collection, null)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    } catch (RuntimeException e) {
      log.error("Failed to process: {}", filePath, e);
      FileEnvelope envelopeForLedger = envelope;
      journal.recordOutcomeSafely(
          filePath,
          "PARSER_FAILED(retryable)",
          () ->
              jobQueue.markFailed(
                  filePath,
                  journal.outcome(
                      IngestionOutcomeClass.PARSER_FAILED,
                      IngestionReasonCodes.PARSER_FAILED,
                      IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                      "Unexpected processing failure"),
                  ledgerEntry(envelopeForLedger, collection, null)));
      journal.recordFailedMetric(filePath, null);
      batchStats.recordFailed();
      return null;
    }
  }

  private JobQueue.IngestionLedgerEntry ledgerEntry(Path filePath, String collection) {
    return LedgerEntryFactory.forPathOnly(filePath, collection);
  }

  private JobQueue.IngestionLedgerEntry ledgerEntry(
      FileEnvelope envelope, String collection, ValidatedExtractionArtifact artifact) {
    return LedgerEntryFactory.forEnvelope(
        envelope, collection, artifact, contentExtractor.extractionPolicy());
  }

  private void bestEffortDeleteMissingSource(Path filePath) {
    indexedDelta.accept(staleSourceHandler.deleteMissingSource(filePath));
  }

  private Span maybeSpan(String name) {
    if (!detailedTracingSupplier.getAsBoolean()) return Span.getInvalid();
    return TRACER.spanBuilder(name).startSpan();
  }
}
