/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Unified dense+sparse backfill using BGE-M3. Writes both {@link SchemaFields#SPLADE} (sparse
 * weights) and {@link SchemaFields#VECTOR} (dense embedding) in a single pass, since BGE-M3
 * produces both outputs in one forward pass.
 *
 * <p>Follows the same pattern as {@link SpladeBackfillOps}. Documents with {@code
 * splade_status=PENDING} are processed; both sparse and dense fields are written together.
 */
public final class BgeM3BackfillOps {
  private BgeM3BackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<BgeM3Encoder> encoderSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      boolean commitAfterBatch,
      Logger log) {}

  /**
   * Processes a batch of BGE-M3 backfill documents. Returns {@code true} on success (or partial
   * success), {@code false} when the entire batch failed systemically.
   */
  public static boolean processBgeM3Backfill(BackfillContext context) {
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

      BgeM3Encoder encoder = context.encoderSupplier().get();
      if (encoder == null) {
        context.log().debug("BGE-M3 backfill: encoder unavailable, stopping batch");
        return true;
      }

      context.log().info("Processing BGE-M3 backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;

      if (shouldInterrupt(context)) {
        return true;
      }

      // Phase 1: Collect content for all pending docs
      long t1 = System.nanoTime();
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      for (String docId : pendingIds) {
        try {
          String content =
              context.documentFieldOps().getDocumentField(docId, SchemaFields.CHUNK_CONTENT);
          if (content == null || content.isBlank()) {
            content = context.documentFieldOps().getDocumentContent(docId);
          }

          if (content == null || content.isBlank()) {
            context.log().debug("BGE-M3 backfill: no content for {}, marking COMPLETED", docId);
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            context.indexingCoordinator().updateDocument(docId, updates);
            processed++;
            continue;
          }

          batchDocIds.add(docId);
          batchContents.add(content);
        } catch (Exception e) {
          context.log().error("BGE-M3 content fetch failed for {}: {}", docId, e.getMessage());
          failed++;
        }
      }
      long contentFetchMs = (System.nanoTime() - t1) / 1_000_000;

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed);
        return true;
      }

      if (shouldInterrupt(context)) {
        return true;
      }

      // Phase 2: Batch encode with BGE-M3 (produces both dense + sparse)
      long t2 = System.nanoTime();
      List<BgeM3Output> outputs;
      try {
        outputs = encoder.encodeBatch(batchContents);
      } catch (Exception e) {
        context.log().error("BGE-M3 batch encoding failed: {}", e.getMessage());
        // Fallback to per-doc encoding
        int perDocFailed = 0;
        for (int i = 0; i < batchDocIds.size(); i++) {
          try {
            BgeM3Output output = encoder.encode(batchContents.get(i));
            writeOutput(context, batchDocIds.get(i), output);
            processed++;
          } catch (Exception e2) {
            context
                .log()
                .warn(
                    "BGE-M3 per-doc encoding failed for {}: {}",
                    batchDocIds.get(i),
                    e2.getMessage());
            perDocFailed++;
            failed++;
          }
        }
        boolean systemicFailure = processed == 0 && perDocFailed == batchDocIds.size();
        commitIfNeeded(context, processed, failed);
        if (systemicFailure) {
          context
              .log()
              .warn(
                  "BGE-M3 encoding unavailable: entire batch of {} docs failed — {}",
                  perDocFailed,
                  e.getMessage());
          return false;
        }
        return true;
      }
      long encodeMs = (System.nanoTime() - t2) / 1_000_000;

      // Phase 3: Batch-update all docs with both dense + sparse fields
      long t3 = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates =
          new ArrayList<>(batchDocIds.size());
      for (int i = 0; i < batchDocIds.size(); i++) {
        BgeM3Output output = outputs.get(i);
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE, output.sparseWeights());
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
        updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
        if (output.denseVector() != null && output.denseVector().length > 0) {
          updates.put(SchemaFields.VECTOR, output.denseVector());
          updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
        }
        batchUpdates.add(Map.entry(batchDocIds.get(i), updates));
      }
      var batchResult = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
      processed += batchResult.updatedCount();
      long writeMs = (System.nanoTime() - t3) / 1_000_000;

      long t4 = System.nanoTime();
      commitIfNeeded(context, processed, failed);
      long commitMs = (System.nanoTime() - t4) / 1_000_000;

      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      int docs = batchDocIds.size();
      context
          .log()
          .info(
              "BGE-M3 backfill profile: docs={}, total={}ms, query={}ms, contentFetch={}ms,"
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
      context.log().error("Error during BGE-M3 backfill", e);
      return false;
    }
  }

  private static void writeOutput(BackfillContext context, String docId, BgeM3Output output) {
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.SPLADE, output.sparseWeights());
    updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
    updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
    if (output.denseVector() != null && output.denseVector().length > 0) {
      updates.put(SchemaFields.VECTOR, output.denseVector());
      updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
    }
    context.indexingCoordinator().updateDocument(docId, updates);
  }

  private static void commitIfNeeded(BackfillContext context, int processed, int failed) {
    if (processed > 0 || failed > 0) {
      if (context.commitAfterBatch()) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_BGE_M3);
      }
      context
          .log()
          .info("BGE-M3 backfill batch complete: processed={}, failed={}", processed, failed);
    }
  }

  private static boolean shouldInterrupt(BackfillContext context) {
    return !context.runningSupplier().getAsBoolean()
        || context.signalBus().shouldYieldGpuBackfill(); // tempdoc 630: GPU-claimed OR energy-reduced
  }
}
