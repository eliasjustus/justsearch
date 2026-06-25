/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * SPLADE sparse encoding backfill for documents with {@code splade_status=PENDING}.
 *
 * <p>Runs during idle time and interleaved with primary indexing to encode documents that were
 * indexed before SPLADE was available, or that were deferred to backfill for throughput (tempdoc
 * 278). Follows the same pattern as {@link NerBackfillOps}.
 *
 * <p>GPU/CPU session selection is handled internally by {@link SpladeEncoder}'s session handle
 * (via {@code SessionHandle.acquire()}), which falls back to CPU when the main process is using
 * the GPU.
 */
public final class SpladeBackfillOps {
  private SpladeBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      boolean commitAfterBatch,
      Logger log) {}

  /**
   * Processes a batch of SPLADE backfill documents. Returns {@code true} on success (or partial
   * success), {@code false} when the entire batch failed systemically (e.g., GPU OOM). The caller
   * should back off on consecutive {@code false} returns.
   */
  public static boolean processSpladeBackfill(BackfillContext context) {
    try {
      long t0 = System.nanoTime();
      List<String> pendingIds =
          context
              .documentFieldOps()
              .queryDocIdsByField(
                  SchemaFields.SPLADE_STATUS,
                  SchemaFields.SPLADE_STATUS_PENDING,
                  context.batchSize());
      long queryMs = (System.nanoTime() - t0) / 1_000_000;

      if (pendingIds.isEmpty()) {
        return true;
      }

      SpladeEncoder encoder = context.spladeEncoderSupplier().get();
      if (encoder == null) {
        context.log().debug("SPLADE backfill: encoder unavailable, stopping batch");
        return true;
      }

      context.log().info("Processing SPLADE backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Check for interruption before batch work
      if (shouldInterrupt(context)) {
        return true;
      }

      // Phase 1: Collect content for all pending docs
      long t1 = System.nanoTime();
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      for (String docId : pendingIds) {
        try {
          // Try chunk_content first (for chunk documents), fall back to content (parent docs)
          String content =
              context.documentFieldOps().getDocumentField(docId, SchemaFields.CHUNK_CONTENT);
          if (content == null || content.isBlank()) {
            content = context.documentFieldOps().getDocumentContent(docId);
          }

          if (content == null || content.isBlank()) {
            context.log().debug("SPLADE backfill: no content for {}, marking COMPLETED", docId);
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
            context.indexingCoordinator().updateDocument(docId, updates);
            processed++;
            continue;
          }

          batchDocIds.add(docId);
          batchContents.add(content);
        } catch (Exception e) {
          context.log().error("SPLADE content fetch failed for {}: {}", docId, e.getMessage());
          markedFailed +=
              handleSpladeFailure(context.documentFieldOps(), context.indexingCoordinator(), docId, e.getMessage(), context.log());
          failed++;
        }
      }
      long contentFetchMs = (System.nanoTime() - t1) / 1_000_000;

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed, markedFailed);
        return true;
      }

      // Re-check interruption after content collection
      if (shouldInterrupt(context)) {
        return true;
      }

      // Phase 2: Batch encode with SPLADE
      long t2 = System.nanoTime();
      List<Map<String, Float>> sparseVecs;
      try {
        sparseVecs = encoder.encodeBatch(batchContents);
      } catch (Exception e) {
        context.log().error("SPLADE batch encoding failed: {}", e.getMessage());
        // Fallback to per-doc encoding
        for (int i = 0; i < batchDocIds.size(); i++) {
          try {
            Map<String, Float> sparseVec = encoder.encode(batchContents.get(i));
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.SPLADE, sparseVec);
            updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
            updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
            context.indexingCoordinator().updateDocument(batchDocIds.get(i), updates);
            processed++;
          } catch (Exception e2) {
            markedFailed +=
                handleSpladeFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), batchDocIds.get(i), e2.getMessage(), context.log());
            failed++;
          }
        }

        // Detect batch-wide systemic failure: batch encode failed AND every per-doc fallback
        // also failed. This indicates a persistent encoder problem (e.g., GPU OOM, corrupted
        // session) — not a transient per-doc issue. Signal failure to caller for backoff.
        boolean systemicFailure = processed == 0 && failed == batchDocIds.size();
        commitIfNeeded(context, processed, failed, markedFailed);
        if (systemicFailure) {
          context
              .log()
              .warn(
                  "SPLADE encoding unavailable: entire batch of {} docs failed — {}",
                  failed,
                  e.getMessage());
          return false;
        }
        return true;
      }

      long encodeMs = (System.nanoTime() - t2) / 1_000_000;

      // Phase 3: Batch-update all docs with SPLADE vectors (single NRT refresh for batch)
      long t3 = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates =
          new ArrayList<>(batchDocIds.size());
      for (int i = 0; i < batchDocIds.size(); i++) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE, sparseVecs.get(i));
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
        updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
        batchUpdates.add(Map.entry(batchDocIds.get(i), updates));
      }
      var batchResult = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
      processed += batchResult.updatedCount();
      long writeMs = (System.nanoTime() - t3) / 1_000_000;

      long t4 = System.nanoTime();
      commitIfNeeded(context, processed, failed, markedFailed);
      long commitMs = (System.nanoTime() - t4) / 1_000_000;

      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      int docs = batchDocIds.size();
      context
          .log()
          .info(
              "SPLADE backfill profile: docs={}, total={}ms, query={}ms, contentFetch={}ms,"
                  + " encode={}ms, luceneWrite={}ms, commit={}ms, perDoc={}ms",
              docs,
              totalMs,
              queryMs,
              contentFetchMs,
              encodeMs,
              writeMs,
              commitMs,
              docs > 0 ? totalMs / docs : 0);
      return true;

    } catch (Exception e) {
      context.log().error("Error during SPLADE backfill", e);
      return false;
    }
  }

  private static boolean shouldInterrupt(BackfillContext context) {
    boolean interrupt =
        !context.runningSupplier().getAsBoolean() || context.signalBus().isUserActive();
    if (interrupt) {
      context
          .log()
          .debug(
              "SPLADE backfill interrupted: userActive={}, stopping={}",
              context.signalBus().isUserActive(),
              !context.runningSupplier().getAsBoolean());
    }
    return interrupt;
  }

  private static void commitIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      if (context.commitAfterBatch()) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_SPLADE);
      }
      context
          .log()
          .info(
              "SPLADE backfill cycle: {} processed, {} failed ({} marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  static int handleSpladeFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String docId, String reason, Logger log) {
    try {
      String retryCountStr =
          documentFieldOps.getDocumentField(docId, SchemaFields.SPLADE_RETRY_COUNT);
      int retryCount = 0;
      if (retryCountStr != null && !retryCountStr.isBlank()) {
        try {
          retryCount = Integer.parseInt(retryCountStr);
        } catch (NumberFormatException ignored) {
          // Default to 0
        }
      }

      retryCount++;
      Map<String, Object> updates = new HashMap<>();

      if (retryCount >= SchemaFields.SPLADE_MAX_RETRIES) {
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_FAILED);
        updates.put(SchemaFields.SPLADE_RETRY_COUNT, String.valueOf(retryCount));
        log.warn(
            "SPLADE permanently FAILED for {} after {} retries: {}",
            docId,
            retryCount,
            reason);
        indexingCoordinator.updateDocument(docId, updates);
        return 1;
      } else {
        updates.put(SchemaFields.SPLADE_RETRY_COUNT, String.valueOf(retryCount));
        log.debug(
            "SPLADE retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.SPLADE_MAX_RETRIES,
            docId,
            reason);
        indexingCoordinator.updateDocument(docId, updates, true);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update SPLADE retry count for {}", docId, e);
      return 0;
    }
  }
}
