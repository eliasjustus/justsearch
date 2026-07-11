/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ort.NativeSessionHandle;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Combined enrichment backfill: embedding + SPLADE + NER in a single RMW pass per document.
 *
 * <p>Eliminates cross-stage RMW churn where each independent backfill stage drops non-stored fields
 * from other stages (tempdoc 312 BUG-1). By running all enrichments on the same document and
 * writing once, no data is lost between stages. Follows the industry-standard pipeline-in-memory,
 * write-once pattern (ES ingest pipelines, Solr URPs, Vespa document processors).
 *
 * <p>Tempdoc 334 item 3.
 *
 * <p><b>Late-chunking single-pass embed strategy (tempdoc 691 forensics fold-in).</b> Lucene
 * read-modify-write destroys non-stored fields absent from the current write — {@code VECTOR} is a
 * non-stored {@code KnnFloatVectorField} (see the {@code WritePathOps} readModifyWrite re-queue
 * comment). An earlier design ran the late-chunking single-pass embed as a SEPARATE RMW pass
 * ({@code LateChunkingEmbedBackfillOps}) ahead of this one; this pass's later SPLADE/NER-only RMW
 * for the same doc then silently destroyed that just-written vector — chunked parents ended
 * COMPLETED but vectorless (legal-clerc live evidence: vector nDCG 0.016). The fix folds the
 * single-pass strategy IN as another embed sub-phase here: when {@link
 * BackfillContext#lateChunkingEnabled()} is true and a pending-embed parent has {@code
 * PARENT_DOC_ID}-matching chunk docs, its vector comes from one whole-document forward pass
 * ({@link EmbeddingProvider#embedWithSpans}, empty span array) instead of the base-window-mean
 * batch embed — but the result always lands in the SAME per-doc update map this pass already
 * builds for SPLADE/NER, so every doc still gets exactly one bundled write. A {@code null} result
 * (content over the raised single-pass limit) or a GPU BFC-arena OOM on the single-pass call falls
 * back INLINE into the ordinary windowed batch below, alongside every other pending-embed doc in
 * the batch — still one RMW per doc. Flag off: byte-identical to before the fold — the
 * chunk-existence probe and {@code embedWithSpans} are never called.
 */
public final class CombinedEnrichmentBackfillOps {
  private CombinedEnrichmentBackfillOps() {}

  /** VECTOR-only mode (tempdoc 691 §Phase M): no char spans are derived or passed to the encoder. */
  private static final int[][] NO_SPANS = new int[0][];

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      Supplier<NerService> nerServiceSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      int batchSize,
      Logger log,
      boolean chunkVectorsEnabled,
      // Tempdoc 712: encode chunk docs' chunk_content into the splade FeatureField so the
      // chunk-merge sparse sub-leg (searchChunksSplade) has data. Default false — flag-off keeps
      // the historical behavior of marking chunk docs' splade_status COMPLETED without encoding.
      boolean chunkSpladeEnabled,
      boolean lateChunkingEnabled,
      // Tempdoc 710 Wave-1.5 Move 4 item 2: was the bare `chunkSlotsPerBatch = 50` local literal
      // below; measured NOT the dense-corpus chunk-only-tail throughput lever (691 §F-1 — that
      // tail is GPU-embedding-compute-bound, not cap-throttled), so this is a config surface for
      // experimentation, not a known-good throughput knob.
      int chunkSlotsPerBatch,
      java.util.ArrayDeque<String> parentIdCache,
      java.util.ArrayDeque<String> chunkIdCache,
      int[] batchesSinceCommit) {}

  /**
   * Outcome of one {@link #processCombinedBackfill} call (tempdoc 710 Move 2 item 4).
   *
   * <p>{@code OperationalMetrics.recordStageTiming}/{@code recordEnrichmentCompleted}/{@code
   * recordBatchTiming} were previously called from a {@code finally} block INSIDE this method —
   * the sole caller, which meant individual-backfill-mode counters froze (710 S-B3 finding).
   * Recording moves to {@link BackfillScheduler} (the only component that knows which pass ran);
   * this record carries exactly what that {@code finally} block used to read directly.
   *
   * @param anyWorkDone the original return value ({@code written > 0}) — drives the tight-loop /
   *     {@code useCombined} control flow in {@link BackfillScheduler}.
   * @param recordTiming the original {@code recordTiming} flag: {@code true} once processing got
   *     past the early-return/interruption checks (mirrors the pre-move gate on whether the
   *     {@code finally} block recorded anything at all). {@code false} means every count/timing
   *     field below is a meaningless zero and must NOT be recorded.
   * @param embedProcessed / spladeProcessed / nerProcessed document counts (not batch counts).
   * @param embedMs / spladeMs / nerMs / fetchMs / writeMs / totalMs per-phase wall-clock ms.
   */
  public record CombinedOutcome(
      boolean anyWorkDone,
      boolean recordTiming,
      int embedProcessed,
      int spladeProcessed,
      int nerProcessed,
      long embedMs,
      long spladeMs,
      long nerMs,
      long fetchMs,
      long writeMs,
      long totalMs) {

    /** No pending work / interrupted before any stage ran — nothing to record. */
    public static CombinedOutcome none() {
      return new CombinedOutcome(false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  /**
   * Processes a batch of documents through all available enrichments in a single pass. Each
   * document is read once, enriched with embedding + SPLADE + NER as needed, and written once via a
   * single batch RMW call.
   *
   * @return the batch outcome; {@code outcome.anyWorkDone()} replaces the pre-Move-2 boolean
   *     return for backfillDidWork/tight-loop tracking.
   */
  public static CombinedOutcome processCombinedBackfill(BackfillContext context) {
    // Timing/count accumulators hoisted so they survive exceptions (350).
    int embedProcessed = 0;
    int spladeProcessed = 0;
    int nerProcessed = 0;
    long embedMs = 0;
    long spladeMs = 0;
    long nerMs = 0;
    long fetchMs = 0;
    long writeMs = 0;
    long totalMs = 0;
    boolean recordTiming = false;

    // Tempdoc 400 LR2-a: enrichment.batch parent span. Encoder ORT spans
    // emitted inside are parented under this when detailed tracing is on;
    // noop (Span.getInvalid) when off so there is no measurable overhead.
    Span enrichmentSpan = EncoderOrtRunSpans.maybeEnrichmentBatch();
    try (Scope _ = enrichmentSpan.makeCurrent()) {
    try {
      // Phase 0: Query pending docs. Uses caches from tight loop — first call queries all
      // pending IDs (no limit), subsequent calls pop from cache. Eliminates 4 Lucene queries
      // per iteration after the first (334 Phase 10).
      boolean embedAvailable =
          context.allowEmbeddingWritesSupplier().getAsBoolean()
              && context.embeddingProviderSupplier().get() != null
              && context.embeddingProviderSupplier().get().isAvailable();
      boolean spladeAvailable = context.spladeEncoderSupplier().get() != null;
      boolean nerAvailable =
          context.nerServiceSupplier().get() != null
              && context.nerServiceSupplier().get().isAvailable();

      // Populate parent cache on first call (or when drained)
      if (context.parentIdCache().isEmpty()) {
        Set<String> allPending = new LinkedHashSet<>();
        if (embedAvailable) {
          allPending.addAll(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        if (spladeAvailable) {
          allPending.addAll(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.SPLADE_STATUS,
                      SchemaFields.SPLADE_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        if (nerAvailable) {
          allPending.addAll(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.NER_STATUS,
                      SchemaFields.NER_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        context.parentIdCache().addAll(allPending);
      }

      // Populate chunk cache on first call (or when drained)
      int chunkSlotsPerBatch = context.chunkSlotsPerBatch();
      if (context.chunkIdCache().isEmpty() && embedAvailable && context.chunkVectorsEnabled()) {
        context
            .chunkIdCache()
            .addAll(
                context
                    .documentFieldOps()
                    .queryDocIdsByField(
                        SchemaFields.CHUNK_EMBEDDING_STATUS,
                        SchemaFields.EMBEDDING_STATUS_PENDING,
                        Integer.MAX_VALUE));
      }

      if (context.parentIdCache().isEmpty() && context.chunkIdCache().isEmpty()) {
        return CombinedOutcome.none();
      }

      // Pop batchSize parents + chunkSlots chunks from caches
      List<String> pendingIds = new ArrayList<>(context.batchSize() + chunkSlotsPerBatch);
      for (int i = 0; i < context.batchSize() && !context.parentIdCache().isEmpty(); i++) {
        pendingIds.add(context.parentIdCache().poll());
      }
      Set<String> chunkDocIds = new LinkedHashSet<>();
      for (int i = 0; i < chunkSlotsPerBatch && !context.chunkIdCache().isEmpty(); i++) {
        String id = context.chunkIdCache().poll();
        chunkDocIds.add(id);
        pendingIds.add(id);
      }

      if (pendingIds.isEmpty()) {
        return CombinedOutcome.none();
      }

      long t0 = System.nanoTime();

      // Phase 1: Batch content fetch (single searcher, all docs)
      Map<String, String> contentByDocId =
          context.documentFieldOps().getDocumentContentBatch(pendingIds);

      // Phase 1b: Batch status + chunk_content fetch (single searcher, all docs).
      // Replaces 300-400 individual getDocumentField() calls with one batched read.
      // All status fields are DocValues-backed (O(1) per read). CHUNK_CONTENT is stored.
      // Tempdoc 700: also fetch *_RETRY_COUNT for every enrichment in play, so the failure
      // branches below can make an escalation decision (increment + FAILED-at-max) from
      // already-fetched data, without a per-doc read.
      Set<String> fieldsToFetch = new LinkedHashSet<>();
      if (embedAvailable) {
        fieldsToFetch.add(SchemaFields.EMBEDDING_STATUS);
        fieldsToFetch.add(SchemaFields.EMBEDDING_RETRY_COUNT);
      }
      if (spladeAvailable) {
        fieldsToFetch.add(SchemaFields.SPLADE_STATUS);
        fieldsToFetch.add(SchemaFields.SPLADE_RETRY_COUNT);
        fieldsToFetch.add(SchemaFields.CHUNK_CONTENT);
      }
      if (nerAvailable) {
        fieldsToFetch.add(SchemaFields.NER_STATUS);
        fieldsToFetch.add(SchemaFields.NER_RETRY_COUNT);
      }
      if (!chunkDocIds.isEmpty()) {
        fieldsToFetch.add(SchemaFields.CHUNK_EMBEDDING_STATUS);
        fieldsToFetch.add(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT);
      }

      Map<String, Map<String, String>> batchedFields =
          fieldsToFetch.isEmpty()
              ? Map.of()
              : context.documentFieldOps().getDocumentFieldsBatch(pendingIds, fieldsToFetch);

      // Phase 2: Collect docs needing each enrichment
      List<String> embedDocIds = new ArrayList<>();
      List<String> embedContents = new ArrayList<>();
      List<String> lateChunkingDocIds = new ArrayList<>();
      List<String> lateChunkingContents = new ArrayList<>();
      List<String> spladeDocIds = new ArrayList<>();
      List<String> spladeContents = new ArrayList<>();

      Map<String, Map<String, Object>> updatesByDocId = new LinkedHashMap<>();

      // Track which doc IDs are chunk docs (need CHUNK_VECTOR instead of VECTOR)
      Set<String> chunkIdsInBatch = new HashSet<>();

      for (String docId : pendingIds) {
        boolean isChunkDoc = chunkDocIds.contains(docId);
        updatesByDocId.put(docId, new HashMap<>());
        Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());

        if (isChunkDoc) {
          // Chunk doc: needs embedding; with chunk-SPLADE on (tempdoc 712) also sparse.
          // Content from CHUNK_CONTENT field.
          String chunkContent = docFields.get(SchemaFields.CHUNK_CONTENT);
          if (chunkContent == null || chunkContent.isBlank()) {
            // Also try the main content batch (getDocumentContentBatch reads CONTENT)
            chunkContent = contentByDocId.get(docId);
          }
          if (chunkContent == null || chunkContent.isBlank()) {
            updatesByDocId.get(docId).put(
                SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            continue;
          }
          embedDocIds.add(docId);
          embedContents.add(chunkContent);
          chunkIdsInBatch.add(docId);
          if (context.chunkSpladeEnabled() && spladeAvailable) {
            String chunkSpladeStatus =
                docFields.getOrDefault(
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
            // Enroll on PENDING, and also on COMPLETED: this lane's own RMW cannot carry splade
            // postings it does not re-derive — omitting splade here would destroy the data and
            // reset-status it back to PENDING (WritePathOps rmwPolicy lane, tempdoc 711), costing
            // a full destroy → re-queue → re-encode cycle. Re-encoding into the same bundled
            // write is strictly cheaper. FAILED is respected (poison-pill).
            if (!SchemaFields.SPLADE_STATUS_FAILED.equals(chunkSpladeStatus)) {
              spladeDocIds.add(docId);
              spladeContents.add(chunkContent);
            }
          }
          continue;
        }

        // Parent doc: full enrichment (embed + SPLADE + NER)
        String content = contentByDocId.get(docId);

        String embedStatus = docFields.getOrDefault(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        String spladeStatus = docFields.getOrDefault(
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
        String nerStatus = docFields.getOrDefault(
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);

        if (content == null || content.isBlank()) {
          Map<String, Object> updates = updatesByDocId.get(docId);
          if (embedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          }
          if (nerAvailable && SchemaFields.NER_STATUS_PENDING.equals(nerStatus)) {
            updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
          }
          if (spladeAvailable) {
            // A splade-PENDING doc with no CONTENT is a chunk doc picked up via the splade-status
            // query (chunks carry CHUNK_CONTENT, never CONTENT). With chunk-SPLADE on (tempdoc
            // 712) encode it; flag-off keeps the historical mark-COMPLETED-without-data. The
            // COMPLETED-and-writing-anyway case also re-derives: an RMW that omits splade
            // destroys the postings and reset-statuses them back to PENDING (tempdoc 711);
            // carrying a fresh encode in the same bundled write skips that churn cycle.
            String chunkContent = docFields.get(SchemaFields.CHUNK_CONTENT);
            boolean chunkSparseEligible =
                context.chunkSpladeEnabled()
                    && chunkContent != null
                    && !chunkContent.isBlank()
                    && !SchemaFields.SPLADE_STATUS_FAILED.equals(spladeStatus);
            boolean spladePending = SchemaFields.SPLADE_STATUS_PENDING.equals(spladeStatus);
            if (chunkSparseEligible && (spladePending || !updates.isEmpty())) {
              spladeDocIds.add(docId);
              spladeContents.add(chunkContent);
            } else if (spladePending) {
              updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
            }
          }
          continue;
        }

        // Tempdoc 691 forensics fold-in: a chunked parent (one with PARENT_DOC_ID-matching chunk
        // docs) routes to the late-chunking single-pass embed strategy in Phase 3a-i below instead
        // of the ordinary windowed batch — collected into a separate list here so that sub-phase
        // can try embedWithSpans first and only fold the doc into embedDocIds/embedContents on a
        // null/arena-OOM outcome. This check is embed-stage-only — SPLADE/NER enrollment below is
        // untouched, since those stages are collected independently of embedDocIds/embedContents
        // (no shared enrollment gate to decouple). Whichever strategy serves the vector, the
        // result lands in this same doc's entry in updatesByDocId — one bundled write either way.
        boolean isLateChunkingParent =
            context.lateChunkingEnabled()
                && embedAvailable
                && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)
                && hasChunkDocs(context, docId);
        if (isLateChunkingParent) {
          lateChunkingDocIds.add(docId);
          lateChunkingContents.add(content);
        } else if (embedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
          embedDocIds.add(docId);
          embedContents.add(content);
        }
        if (spladeAvailable && SchemaFields.SPLADE_STATUS_PENDING.equals(spladeStatus)) {
          spladeDocIds.add(docId);
          String chunkContent = docFields.get(SchemaFields.CHUNK_CONTENT);
          spladeContents.add(
              (chunkContent != null && !chunkContent.isBlank()) ? chunkContent : content);
        }
      }

      fetchMs = (System.nanoTime() - t0) / 1_000_000;

      // Check for interruption
      if (!context.runningSupplier().getAsBoolean() || context.signalBus().isUserActive()) {
        return CombinedOutcome.none();
      }

      // Past early returns — any work from here should be recorded.
      recordTiming = true;

      // Phase 3a: Batch embedding
      long tEmbed = System.nanoTime();
      int embedFailed = 0;
      int singlePassProcessed = 0;
      int longDocWindowed = 0;
      int arenaOomWindowed = 0;

      // Phase 3a-i: Late-chunking single-pass embed (tempdoc 691 forensics fold-in). Tries one
      // whole-document forward pass per chunked parent. A null result (content over the raised
      // single-pass limit) or a GPU BFC-arena OOM folds the doc INLINE into the ordinary windowed
      // batch below (embedDocIds/embedContents) rather than deferring to a different RMW pass —
      // every doc still resolves within this pass's single bundled write. A non-arena-OOM failure
      // escalates the parent directly through the same retry-count/FAILED-at-max helper the
      // windowed batch failure path below uses.
      if (!lateChunkingDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider lateChunkingProvider = context.embeddingProviderSupplier().get();
        for (int i = 0; i < lateChunkingDocIds.size(); i++) {
          String lcDocId = lateChunkingDocIds.get(i);
          String lcContent = lateChunkingContents.get(i);
          try {
            EmbeddingService.ChunkedEmbedding result =
                lateChunkingProvider.embedWithSpans(lcContent, NO_SPANS);
            if (result != null && result.primaryVector().length > 0) {
              Map<String, Object> updates = updatesByDocId.get(lcDocId);
              updates.put(SchemaFields.VECTOR, result.primaryVector());
              updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
              singlePassProcessed++;
            } else {
              // Content exceeds the raised single-pass limit — fold into the windowed batch.
              embedDocIds.add(lcDocId);
              embedContents.add(lcContent);
              longDocWindowed++;
            }
          } catch (Exception e) {
            if (isArenaOomFailure(e)) {
              // A single-pass batch-1 forward pass over the whole document needs more contiguous
              // GPU arena memory than a windowed pass — resource contention, not a bad-input
              // failure. Fold into the windowed batch same as the over-limit null case.
              context
                  .log()
                  .warn(
                      "Combined backfill: late-chunking single-pass falling back to windowed for"
                          + " {} (GPU arena OOM on single-pass batch-1): {}",
                      lcDocId,
                      e.getMessage());
              embedDocIds.add(lcDocId);
              embedContents.add(lcContent);
              arenaOomWindowed++;
            } else {
              context
                  .log()
                  .warn(
                      "Combined backfill: late-chunking single-pass embed failed for {}: {}",
                      lcDocId,
                      e.getMessage());
              Map<String, String> lcDocFields = batchedFields.getOrDefault(lcDocId, Map.of());
              int currentRetryCount =
                  parseRetryCountOrZero(lcDocFields.get(SchemaFields.EMBEDDING_RETRY_COUNT));
              updatesByDocId
                  .get(lcDocId)
                  .putAll(EmbeddingBackfillOps.computeEmbeddingFailureUpdate(currentRetryCount));
              embedFailed++;
            }
          }
        }
      }

      if (!embedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider provider = context.embeddingProviderSupplier().get();
        List<float[]> vectors = provider.embedDocumentBatch(embedContents);
        // Trusting a batch result's length to match the request is what crash-loops the
        // embedding-chunk sibling (EmbeddingBackfillOps#processChunkEmbeddingBackfill) — guard
        // size mismatch the same way here, not just null.
        if (vectors != null && vectors.size() == embedDocIds.size()) {
          for (int i = 0; i < embedDocIds.size(); i++) {
            float[] vector = vectors.get(i);
            String eid = embedDocIds.get(i);
            Map<String, Object> updates = updatesByDocId.get(eid);
            boolean isChunk = chunkIdsInBatch.contains(eid);
            if (vector != null && vector.length > 0) {
              // Chunk docs use CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS; parents use VECTOR/EMBEDDING_STATUS
              updates.put(
                  isChunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, vector);
              updates.put(
                  isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(
                  isChunk
                      ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                      : SchemaFields.EMBEDDING_RETRY_COUNT,
                  "0");
              embedProcessed++;
            } else {
              // Tempdoc 700: escalate instead of silently resetting to PENDING forever. Look up
              // the retry count already fetched in the batched pre-fetch, delegate the
              // increment/FAILED-at-max decision to the same pure helper the individual
              // EmbeddingBackfillOps siblings use, and merge the result into this doc's entry in
              // updatesByDocId — never a direct updateDocument call (preserves the single-batched-
              // write invariant, tempdoc 312 BUG-1).
              Map<String, String> eidFields = batchedFields.getOrDefault(eid, Map.of());
              int currentRetryCount =
                  parseRetryCountOrZero(
                      eidFields.get(
                          isChunk
                              ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                              : SchemaFields.EMBEDDING_RETRY_COUNT));
              updates.putAll(
                  isChunk
                      ? EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(currentRetryCount)
                      : EmbeddingBackfillOps.computeEmbeddingFailureUpdate(currentRetryCount));
              embedFailed++;
            }
          }
        } else {
          // Batch failed or size-mismatched — mark all as still pending (will retry next cycle)
          context.log().warn(
              "Combined backfill: batch embedding returned {} (expected {} vectors)",
              vectors == null ? "null" : vectors.size() + " results",
              embedDocIds.size());
          // += not =: embedDocIds can now include late-chunking windowed-fallback docs, and
          // embedFailed may already carry Phase 3a-i single-pass escalation failures — a bare
          // reassignment here would silently erase those from the summary log (tempdoc 691
          // forensics fold-in; the write correctness is unaffected, only this counter).
          embedFailed += embedDocIds.size();
        }
      }
      embedMs = (System.nanoTime() - tEmbed) / 1_000_000;

      // Phase 3b: Batch SPLADE (CPU to avoid GPU VRAM contention with embedding)
      long tSplade = System.nanoTime();
      int spladeFailed = 0;
      if (!spladeDocIds.isEmpty() && spladeAvailable) {
        SpladeEncoder encoder = context.spladeEncoderSupplier().get();
        try {
          List<Map<String, Float>> sparseVecs = encoder.encodeBatch(spladeContents);
          for (int i = 0; i < spladeDocIds.size(); i++) {
            Map<String, Object> updates = updatesByDocId.get(spladeDocIds.get(i));
            updates.put(SchemaFields.SPLADE, sparseVecs.get(i));
            updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
            updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
            spladeProcessed++;
          }
        } catch (Exception e) {
          context.log().warn("Combined backfill: SPLADE batch encode failed: {}", e.getMessage());
          // Tempdoc 700: escalate the docs that did NOT already get a successful write above
          // (a short/partial sparseVecs result can leave some earlier indices already marked
          // COMPLETED in updatesByDocId before the exception fired — don't clobber those).
          int stillFailed = 0;
          for (String spladeDocId : spladeDocIds) {
            Map<String, Object> docUpdates = updatesByDocId.get(spladeDocId);
            if (SchemaFields.SPLADE_STATUS_COMPLETED.equals(
                docUpdates.get(SchemaFields.SPLADE_STATUS))) {
              continue;
            }
            Map<String, String> spladeDocFields = batchedFields.getOrDefault(spladeDocId, Map.of());
            int currentRetryCount =
                parseRetryCountOrZero(spladeDocFields.get(SchemaFields.SPLADE_RETRY_COUNT));
            docUpdates.putAll(SpladeBackfillOps.computeSpladeFailureUpdate(currentRetryCount));
            stillFailed++;
          }
          spladeFailed = stillFailed;
        }
      }
      spladeMs = (System.nanoTime() - tSplade) / 1_000_000;

      // Phase 3c: NER (per-doc GPU inference — batching tested in item 22, regressed due to
      // padding waste exceeding the 467us/call overhead. Per-doc at 2.0ms/call is near-optimal.)
      long tNer = System.nanoTime();
      int nerFailed = 0;
      if (nerAvailable) {
        NerService nerService = context.nerServiceSupplier().get();
        for (String docId : pendingIds) {
          Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
          String nerSt = docFields.getOrDefault(
              SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);
          if (!SchemaFields.NER_STATUS_PENDING.equals(nerSt)) {
            continue;
          }
          String content = contentByDocId.get(docId);
          if (content == null || content.isBlank()) {
            continue;
          }
          try {
            List<NerResult> nerBatch = nerService.extractEntitiesBatch(List.of(content));
            NerResult result = nerBatch.isEmpty() ? NerResult.EMPTY : nerBatch.get(0);
            Map<String, Object> updates = updatesByDocId.get(docId);
            updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
            updates.put(SchemaFields.NER_RETRY_COUNT, "0");
            NerBackfillOps.applyEntityFieldUpdates(updates, result);
            nerProcessed++;
          } catch (Exception e) {
            context
                .log()
                .warn("Combined backfill: NER failed for {}: {}", docId, e.getMessage());
            // Tempdoc 700: escalate via the same pure helper NerBackfillOps.handleNerFailure
            // uses. The try block above never touched updatesByDocId before throwing, so it's
            // safe to write the failure update directly (no partial-success clobber risk, unlike
            // the SPLADE batch-catch above).
            int currentRetryCount =
                parseRetryCountOrZero(docFields.get(SchemaFields.NER_RETRY_COUNT));
            updatesByDocId
                .get(docId)
                .putAll(NerBackfillOps.computeNerFailureUpdate(currentRetryCount));
            nerFailed++;
          }
        }
      }
      nerMs = (System.nanoTime() - tNer) / 1_000_000;

      // Phase 4: Single batch write (one RMW per doc with all enrichments)
      long tWrite = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (var entry : updatesByDocId.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          batchUpdates.add(Map.entry(entry.getKey(), entry.getValue()));
        }
      }
      int written = 0;
      if (!batchUpdates.isEmpty()) {
        var result = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
        written = result.updatedCount();
      }
      writeMs = (System.nanoTime() - tWrite) / 1_000_000;

      // Commit every 5 batches (334 Phase 10). Lucene 10.4 fixed the MMapDirectory
      // .si arena leak (issue #15068): confined arenas are closed immediately. This
      // should prevent the 24GB native memory growth that killed deferred commits in
      // Phase 8 (tested on Lucene 9.x). If OOM recurs, revert to per-batch commits.
      // NRT refresh is suspended during the tight loop (managed by caller) to prevent
      // mmap accumulation from ControlledRealTimeReopenThread reader snapshots.
      context.batchesSinceCommit()[0]++;
      if (written > 0 && context.batchesSinceCommit()[0] >= 5) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_COMBINED);
        context.batchesSinceCommit()[0] = 0;
      }

      totalMs = (System.nanoTime() - t0) / 1_000_000;

      context
          .log()
          .info(
              "Combined backfill: docs={} (embed={},splade={},chunks={}),"
                  + " fetch={}ms, embed={}ms(ok={},fail={},singlePass={},longDocWindowed={},"
                  + "arenaOomWindowed={}),"
                  + " splade={}ms(ok={},fail={}), ner={}ms(ok={},fail={}),"
                  + " write={}ms(written={}), total={}ms",
              pendingIds.size(),
              embedDocIds.size(),
              spladeDocIds.size(),
              chunkIdsInBatch.size(),
              fetchMs,
              embedMs,
              embedProcessed,
              embedFailed,
              singlePassProcessed,
              longDocWindowed,
              arenaOomWindowed,
              spladeMs,
              spladeProcessed,
              spladeFailed,
              nerMs,
              nerProcessed,
              nerFailed,
              writeMs,
              written,
              totalMs);

      // 710 Move 2 item 4: per-stage enrichment counts/timing are no longer recorded here —
      // recordTiming carries "past the early-return/interruption checks" forward on the returned
      // outcome (mirrors the pre-move finally-block gate) so BackfillScheduler can record from
      // completed-stage data even when a later stage throws (the same "survives exceptions in
      // later stages" property the old finally block had — see the catch block below).
      return new CombinedOutcome(
          written > 0,
          recordTiming,
          embedProcessed,
          spladeProcessed,
          nerProcessed,
          embedMs,
          spladeMs,
          nerMs,
          fetchMs,
          writeMs,
          totalMs);

    } catch (Exception e) {
      context.log().error("Error during combined enrichment backfill", e);
      return recordTiming
          ? new CombinedOutcome(
              false,
              true,
              embedProcessed,
              spladeProcessed,
              nerProcessed,
              embedMs,
              spladeMs,
              nerMs,
              fetchMs,
              writeMs,
              totalMs)
          : CombinedOutcome.none();
    }
    } finally {
      enrichmentSpan.end();
    }
  }

  /**
   * Existence probe for "does this parent have chunk docs" (tempdoc 691 forensics fold-in) — a
   * {@code queryDocIdsByField(PARENT_DOC_ID, parentId, 1)} limit=1 existence check; only
   * existence matters here since the single-pass embed strategy is VECTOR-only (no chunk
   * span/order metadata is read).
   */
  private static boolean hasChunkDocs(BackfillContext context, String parentId) {
    return !context
        .documentFieldOps()
        .queryDocIdsByField(SchemaFields.PARENT_DOC_ID, parentId, 1)
        .isEmpty();
  }

  /**
   * Walks the exception's cause chain looking for an {@link OrtException} matching {@link
   * NativeSessionHandle#isBfcArenaFailure} — the single choke point for the BFC-arena string
   * match (owned by {@code ort-common}, shared with {@code SpladeEncoder}/{@code
   * CrossEncoderReranker}). {@link EmbeddingService#embedWithSpans} wraps the raw {@code
   * OrtException} in a {@code RuntimeException}, so a direct {@code instanceof} on the caught
   * exception isn't enough — this walks {@link Throwable#getCause()} to find it either way.
   * Ported from the deleted {@code LateChunkingEmbedBackfillOps} (tempdoc 691 forensics fold-in).
   */
  private static boolean isArenaOomFailure(Throwable e) {
    for (Throwable cur = e; cur != null; cur = cur.getCause()) {
      if (cur instanceof OrtException ortException
          && NativeSessionHandle.isBfcArenaFailure(ortException)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Mirrors the retry-count parsing each individual {@code handle*Failure} does before delegating
   * to its pure {@code compute*FailureUpdate} helper (tempdoc 700) — kept local to this class
   * since the combined path parses from a batched pre-fetched field value, not a per-doc read.
   */
  private static int parseRetryCountOrZero(String retryCountStr) {
    if (retryCountStr == null || retryCountStr.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(retryCountStr);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
