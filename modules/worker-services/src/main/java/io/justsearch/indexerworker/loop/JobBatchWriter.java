/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a single {@link ExtractedJob} to the index, plus any chunks.
 *
 * <p>Tempdoc 516 Slice 4a.2 — extracted from {@link IndexingLoop}. Owns the
 * write-side error handling (draining-write defer vs WRITE_FAILED), the
 * post-write ledger transition enqueue (via {@link IngestionOutcomeJournal}),
 * and the pre-write stale re-check (via {@link StaleSnapshotResolver}).
 *
 * <p>Cross-seam state — {@code indexedSinceCommit} — is reported back via the
 * {@link LongConsumer} {@code indexedDelta} callback. The loop residue owns
 * the counter; the writer only reports the delta (1 per parent doc + N per
 * chunk batch).
 *
 * <p>P5 boundary: a concrete class with a single public {@link #write} entry
 * point. Not a strategy interface.
 */
public final class JobBatchWriter {

  private static final Logger log = LoggerFactory.getLogger(JobBatchWriter.class);
  private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("indexing");

  private final IndexingCoordinator indexingCoordinator;
  private final DocumentFieldOps documentFieldOps;
  private final WorkerSignalBus signalBus;
  private final EmbeddingProviderLifecycle embeddingLifecycle;
  private final Supplier<SpladeEncoder> spladeEncoderSupplier;
  private final IngestionOutcomeJournal journal;
  private final JobQueue jobQueue;
  private final TimeboxedContentExtractor contentExtractor;
  private final OperationalMetrics metrics;
  private final BatchStats batchStats;
  private final StaleSnapshotResolver staleResolver;
  private final LongConsumer indexedDelta;
  private final IndexingDocumentOps.StageRecorder stageRecorder;
  private final BooleanSupplier detailedTracingSupplier;

  public JobBatchWriter(
      IndexingCoordinator indexingCoordinator,
      DocumentFieldOps documentFieldOps,
      WorkerSignalBus signalBus,
      EmbeddingProviderLifecycle embeddingLifecycle,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      IngestionOutcomeJournal journal,
      JobQueue jobQueue,
      TimeboxedContentExtractor contentExtractor,
      OperationalMetrics metrics,
      BatchStats batchStats,
      StaleSnapshotResolver staleResolver,
      LongConsumer indexedDelta,
      IndexingDocumentOps.StageRecorder stageRecorder,
      BooleanSupplier detailedTracingSupplier) {
    this.indexingCoordinator = indexingCoordinator;
    this.documentFieldOps = documentFieldOps;
    this.signalBus = signalBus;
    this.embeddingLifecycle = embeddingLifecycle;
    this.spladeEncoderSupplier = spladeEncoderSupplier;
    this.journal = journal;
    this.jobQueue = jobQueue;
    this.contentExtractor = contentExtractor;
    this.metrics = metrics;
    this.batchStats = batchStats;
    this.staleResolver = staleResolver;
    this.indexedDelta = indexedDelta;
    this.stageRecorder = stageRecorder;
    this.detailedTracingSupplier = detailedTracingSupplier;
  }

  /** Builds and writes an already-extracted job. Mirrors prior IndexingLoop.writeExtractedJob. */
  public void write(ExtractedJob ex, float[] precomputedEmbedding) {
    Span writeSpan = maybeSpan("indexing.write");
    writeSpan.setAttribute("doc.path", ex.filePath().toString());
    String embeddingSource = precomputedEmbedding != null ? "batch" : "inline_or_pending";
    writeSpan.setAttribute("embedding.source", embeddingSource);
    try {
      if (staleResolver.tryHandleStale(
          ex.filePath(), ex.envelope(), ex.collection(), ex.artifact(), "before write")) {
        batchStats.recordSkipped();
        return;
      }
      SpladeEncoder splade = spladeEncoderSupplier.get();
      IndexingDocumentOps.ParentIndexMetadata parentMetadata =
          IndexingDocumentOps.deriveParentMetadata(ex.filePath(), ex.artifact().result(), splade, log);
      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              ex.filePath(),
              ex.artifact(),
              ex.collection(),
              signalBus,
              embeddingLifecycle.embeddingProvider(),
              false, // Embedding deferred to backfill during primary indexing (312)
              splade,
              parentMetadata,
              stageRecorder,
              log,
              precomputedEmbedding,
              new IndexingDocumentOps.SourceFileMetadata(
                  ex.envelope().sizeBytes(), ex.envelope().modifiedAtMs()));

      long writeStart = System.currentTimeMillis();
      indexingCoordinator.indexSingle(doc);
      indexedDelta.accept(1L);
      batchStats.recordIndexed(1);

      int chunksIndexed =
          IndexingDocumentOps.indexChunks(
              ex.filePath(), ex.artifact().result(), documentFieldOps, indexingCoordinator, parentMetadata);
      if (chunksIndexed > 0) {
        indexedDelta.accept(chunksIndexed);
        log.debug("Indexed {} chunks for: {}", chunksIndexed, ex.filePath().getFileName());
      }
      stageRecorder.record("write", System.currentTimeMillis() - writeStart, null);

      journal.enqueueTransition(
          new JobQueue.IngestionLedgerTransition(
              ex.filePath(),
              LedgerEntryFactory.forEnvelope(
                  ex.envelope(), ex.collection(), ex.artifact(), contentExtractor.extractionPolicy())));

      long latencyMs = System.currentTimeMillis() - ex.startTime();
      metrics.recordDocumentIndexed(latencyMs);
      metrics.recordContentLength(
          ex.artifact().result().content() != null ? ex.artifact().result().content().length() : 0);
      log.debug("Indexed successfully: {} in {}ms", ex.filePath(), latencyMs);

    } catch (RuntimeException e) {
      log.error("Failed to write: {}", ex.filePath(), e);
      if (isDrainingWriteRejection(e)) {
        journal.recordOutcomeSafely(
            ex.filePath(),
            "WRITE_UNAVAILABLE_DRAINING",
            () ->
                jobQueue.defer(
                    ex.filePath(),
                    journal.outcome(
                        IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING,
                        IngestionReasonCodes.WRITE_UNAVAILABLE_DRAINING,
                        IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT,
                        "Runtime draining"),
                    LedgerEntryFactory.forEnvelope(
                        ex.envelope(),
                        ex.collection(),
                        ex.artifact(),
                        contentExtractor.extractionPolicy())));
      } else {
        journal.recordOutcomeSafely(
            ex.filePath(),
            "WRITE_FAILED",
            () ->
                jobQueue.markFailed(
                    ex.filePath(),
                    journal.outcome(
                        IngestionOutcomeClass.WRITE_FAILED,
                        IngestionReasonCodes.WRITE_FAILED,
                        IngestionRetryPolicy.RETRY_WITH_BACKOFF,
                        "Index write failed"),
                    LedgerEntryFactory.forEnvelope(
                        ex.envelope(),
                        ex.collection(),
                        ex.artifact(),
                        contentExtractor.extractionPolicy())));
        journal.recordFailedMetric(ex.filePath(), ex.artifact().result().mimeType());
        batchStats.recordFailed();
      }
    } finally {
      writeSpan.end();
    }
  }

  private static boolean isDrainingWriteRejection(RuntimeException e) {
    return e instanceof IndexRuntimeIOException indexRuntimeIOException
        && indexRuntimeIOException.reason() == IndexRuntimeIOException.Reason.DRAINING;
  }

  private Span maybeSpan(String name) {
    if (!detailedTracingSupplier.getAsBoolean()) return Span.getInvalid();
    return TRACER.spanBuilder(name).startSpan();
  }
}
