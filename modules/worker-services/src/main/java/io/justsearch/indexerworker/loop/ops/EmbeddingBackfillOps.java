/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

public final class EmbeddingBackfillOps {
  private EmbeddingBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      int batchSize,
      Logger log) {}

  public static void processEmbeddingBackfill(BackfillContext context) {
    if (!context.allowEmbeddingWritesSupplier().getAsBoolean()) {
      context.log().trace("Embedding backfill skipped: writes blocked by compatibility controller");
      return;
    }

    try {
      List<String> pendingIds =
          context.documentFieldOps().queryDocIdsByField(
              SchemaFields.EMBEDDING_STATUS,
              SchemaFields.EMBEDDING_STATUS_PENDING,
              context.batchSize());

      if (pendingIds.isEmpty()) {
        return;
      }

      context.log().info("Processing embedding backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Check for interruption before batch work
      EmbeddingProvider embeddingProvider = context.embeddingProviderSupplier().get();
      if (checkInterrupt(context, embeddingProvider, "Backfill")) {
        return;
      }

      // Phase 1: Batch-fetch content for all pending docs (single searcher acquisition)
      long t0 = System.nanoTime();
      Map<String, String> contentByDocId =
          context.documentFieldOps().getDocumentContentBatch(pendingIds);
      List<String> batchDocIds = new ArrayList<>(pendingIds.size());
      List<String> batchContents = new ArrayList<>(pendingIds.size());
      for (String docId : pendingIds) {
        String content = contentByDocId.get(docId);
        if (content == null || content.isBlank()) {
          context.log().warn("Backfill: Document content missing for {}", docId);
          markedFailed +=
              handleEmbeddingFailure(
                  context.documentFieldOps(), context.indexingCoordinator(), docId, "Content missing or blank", context.log());
          failed++;
          continue;
        }
        batchDocIds.add(docId);
        batchContents.add(content);
      }

      if (batchContents.isEmpty()) {
        commitIfNeeded(context, processed, failed, markedFailed);
        return;
      }

      long t1 = System.nanoTime();

      // Re-check interruption after content collection
      if (checkInterrupt(context, context.embeddingProviderSupplier().get(), "Backfill")) {
        return;
      }

      // Phase 2: Batch embed all collected content
      List<float[]> vectors = embeddingProvider.embedDocumentBatch(batchContents);
      // 312 E3: Force full result materialization + brief yield to let GPU resources release.
      // Testing whether GPU memory holding interferes with Lucene mmap writes.
      if (vectors != null) {
        @SuppressWarnings("unused")
        float sink = 0;
        for (float[] v : vectors) {
          if (v != null && v.length > 0) sink += v[0];
        }
      }
      long t2 = System.nanoTime();

      // Phase 3: Update docs with results (fallback to sequential if batch failed)
      if (vectors == null) {
        context.log().warn("Backfill: Batch embedding returned null, falling back to per-doc");
        for (int i = 0; i < batchDocIds.size(); i++) {
          int[] counts =
              embedAndUpdateSingle(
                  context, embeddingProvider, batchDocIds.get(i), batchContents.get(i));
          processed += counts[0];
          failed += counts[1];
          markedFailed += counts[2];
        }
      } else {
        // Collect successful updates for batch write (single NRT refresh)
        long tListStart = System.nanoTime();
        List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
        for (int i = 0; i < batchDocIds.size(); i++) {
          String docId = batchDocIds.get(i);
          float[] vector = vectors.get(i);
          if (vector != null && vector.length > 0) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.VECTOR, vector);
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
            batchUpdates.add(Map.entry(docId, updates));
          } else {
            context.log().warn("Backfill: Batch returned null vector for {}", docId);
            markedFailed +=
                handleEmbeddingFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), docId, "Empty vector in batch", context.log());
            failed++;
          }
        }
        long tListEnd = System.nanoTime();
        if (!batchUpdates.isEmpty()) {
          var result = context.indexingCoordinator().updateDocumentsBatch(batchUpdates, true);
          processed += result.updatedCount();
        }
        long tWriteEnd = System.nanoTime();
        context.log().info(
            "Backfill write phases: listBuild={}ms, updateBatch={}ms, total={}ms",
            (tListEnd - tListStart) / 1_000_000,
            (tWriteEnd - tListEnd) / 1_000_000,
            (tWriteEnd - tListStart) / 1_000_000);
      }

      long t3 = System.nanoTime();
      // 312 item 28: Log per-phase timing for backfill profiling
      context.log().info(
          "Backfill batch timing: docs={}, fetch={}ms, embed={}ms, write={}ms, total={}ms",
          batchDocIds.size(),
          (t1 - t0) / 1_000_000,
          (t2 - t1) / 1_000_000,
          (t3 - t2) / 1_000_000,
          (t3 - t0) / 1_000_000);

      commitIfNeeded(context, processed, failed, markedFailed);

    } catch (Exception e) {
      context.log().error("Error during embedding backfill", e);
    }
  }

  private static boolean checkInterrupt(
      BackfillContext context, EmbeddingProvider embeddingProvider, String label) {
    boolean userActive = context.signalBus().isUserActive();
    // Tempdoc 630: defer backfill when Main claims the GPU (VRAM conflict, GPU only) OR the OS wants
    // reduced background work (energy saver, GPU+CPU). The two reasons are kept distinct so energy
    // throttles CPU backfill too.
    boolean mainGpuActive = context.signalBus().isMainGpuActive();
    boolean energyReduced = context.signalBus().isEnergyReduced();
    boolean shouldInterrupt =
        LoopPacingPolicy.shouldInterruptBackfill(
            context.runningSupplier().getAsBoolean(),
            userActive,
            mainGpuActive,
            energyReduced,
            embeddingProvider);
    if (shouldInterrupt) {
      boolean backfillBlocked =
          !LoopPacingPolicy.shouldRunBackfill(mainGpuActive, energyReduced, embeddingProvider);
      context
          .log()
          .debug(
              "{} interrupted: user active={}, backfill blocked (GPU/energy)={}, stopping={}",
              label,
              userActive,
              backfillBlocked,
              !context.runningSupplier().getAsBoolean());
    }
    return shouldInterrupt;
  }

  private static void commitIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      context
          .commitOps()
          .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_EMBEDDING);
      context
          .log()
          .info(
              "Backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  private static int[] embedAndUpdateSingle(
      BackfillContext context, EmbeddingProvider embeddingProvider, String docId, String content) {
    int processed = 0;
    int failed = 0;
    int markedFailed = 0;
    try {
      float[] vector = embeddingProvider.embedDocument(content);
      if (vector != null && vector.length > 0) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.VECTOR, vector);
        updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
        updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
        context.indexingCoordinator().updateDocument(docId, updates, true);
        processed = 1;
      } else {
        markedFailed =
            handleEmbeddingFailure(
                context.documentFieldOps(), context.indexingCoordinator(), docId, "Empty vector returned", context.log());
        failed = 1;
      }
    } catch (Exception e) {
      context.log().error("Backfill failed for doc {}: {}", docId, e.getMessage());
      markedFailed =
          handleEmbeddingFailure(context.documentFieldOps(), context.indexingCoordinator(), docId, e.getMessage(), context.log());
      failed = 1;
    }
    return new int[] {processed, failed, markedFailed};
  }

  public static int handleEmbeddingFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String docId, String reason, Logger log) {
    try {
      String retryCountStr = documentFieldOps.getDocumentField(docId, SchemaFields.EMBEDDING_RETRY_COUNT);
      int retryCount = 0;
      if (retryCountStr != null && !retryCountStr.isBlank()) {
        try {
          retryCount = Integer.parseInt(retryCountStr);
        } catch (NumberFormatException ignored) {
          // Default to 0 if unparseable
        }
      }

      retryCount++;
      Map<String, Object> updates = new HashMap<>();

      if (retryCount >= SchemaFields.EMBEDDING_MAX_RETRIES) {
        updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
        updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
        log.warn("Embedding permanently FAILED for {} after {} retries: {}", docId, retryCount, reason);
        indexingCoordinator.updateDocument(docId, updates, true);
        return 1;
      } else {
        updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
        log.debug(
            "Embedding retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.EMBEDDING_MAX_RETRIES,
            docId,
            reason);
        indexingCoordinator.updateDocument(docId, updates, true);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update retry count for {}", docId, e);
      return 0;
    }
  }

  /** @return true if any chunks were processed (for tight-loop control) */
  public static boolean processChunkEmbeddingBackfill(BackfillContext context) {
    if (!context.allowEmbeddingWritesSupplier().getAsBoolean()) {
      context.log().trace("Chunk embedding backfill skipped: writes blocked by compatibility controller");
      return false;
    }

    try {
      List<String> pendingChunkIds =
          context.documentFieldOps().queryDocIdsByField(
              SchemaFields.CHUNK_EMBEDDING_STATUS,
              SchemaFields.EMBEDDING_STATUS_PENDING,
              context.batchSize());

      if (pendingChunkIds.isEmpty()) {
        return false;
      }

      context
          .log()
          .info("Processing chunk embedding backfill for {} chunks", pendingChunkIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      EmbeddingProvider embeddingProvider = context.embeddingProviderSupplier().get();
      if (checkInterrupt(context, embeddingProvider, "Chunk backfill")) {
        return false;
      }

      // Phase 1: Collect chunk content
      List<String> batchChunkIds = new ArrayList<>(pendingChunkIds.size());
      List<String> batchContents = new ArrayList<>(pendingChunkIds.size());
      for (String chunkId : pendingChunkIds) {
        try {
          String chunkContent =
              context.documentFieldOps().getDocumentField(chunkId, SchemaFields.CHUNK_CONTENT);
          if (chunkContent == null || chunkContent.isBlank()) {
            context.log().warn("Chunk backfill: Content missing for {}", chunkId);
            markedFailed +=
                handleChunkEmbeddingFailure(
                    context.documentFieldOps(),
                    context.indexingCoordinator(),
                    chunkId,
                    "Chunk content missing or blank",
                    context.log());
            failed++;
            continue;
          }
          batchChunkIds.add(chunkId);
          batchContents.add(chunkContent);
        } catch (Exception e) {
          context.log().error("Chunk backfill content fetch failed for {}: {}", chunkId, e.getMessage());
          markedFailed +=
              handleChunkEmbeddingFailure(
                  context.documentFieldOps(), context.indexingCoordinator(), chunkId, e.getMessage(), context.log());
          failed++;
        }
      }

      if (batchContents.isEmpty()) {
        commitChunkIfNeeded(context, processed, failed, markedFailed);
        return failed > 0;
      }

      if (checkInterrupt(context, context.embeddingProviderSupplier().get(), "Chunk backfill")) {
        return false;
      }

      // Phase 2: Batch embed
      List<float[]> vectors = embeddingProvider.embedDocumentBatch(batchContents);

      // Phase 3: Update chunks with results
      if (vectors == null) {
        context.log().warn("Chunk backfill: Batch embedding returned null, falling back to per-chunk");
        for (int i = 0; i < batchChunkIds.size(); i++) {
          String chunkId = batchChunkIds.get(i);
          try {
            float[] vector = embeddingProvider.embedDocument(batchContents.get(i));
            if (vector != null && vector.length > 0) {
              Map<String, Object> updates = new HashMap<>();
              updates.put(SchemaFields.CHUNK_VECTOR, vector);
              updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
              context.indexingCoordinator().updateDocument(chunkId, updates, true);
              processed++;
            } else {
              markedFailed +=
                  handleChunkEmbeddingFailure(
                      context.documentFieldOps(), context.indexingCoordinator(), chunkId, "Empty vector returned", context.log());
              failed++;
            }
          } catch (Exception e) {
            context.log().error("Chunk backfill failed for {}: {}", chunkId, e.getMessage());
            markedFailed +=
                handleChunkEmbeddingFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), chunkId, e.getMessage(), context.log());
            failed++;
          }
        }
      } else {
        // Collect successful updates for batch write (single NRT refresh)
        List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
        for (int i = 0; i < batchChunkIds.size(); i++) {
          String chunkId = batchChunkIds.get(i);
          float[] vector = vectors.get(i);
          if (vector != null && vector.length > 0) {
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.CHUNK_VECTOR, vector);
            updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
            batchUpdates.add(Map.entry(chunkId, updates));
          } else {
            context.log().warn("Chunk backfill: Batch returned null vector for {}", chunkId);
            markedFailed +=
                handleChunkEmbeddingFailure(
                    context.documentFieldOps(), context.indexingCoordinator(), chunkId, "Empty vector in batch", context.log());
            failed++;
          }
        }
        if (!batchUpdates.isEmpty()) {
          var result = context.indexingCoordinator().updateDocumentsBatch(batchUpdates, true);
          processed += result.updatedCount();
        }
      }

      commitChunkIfNeeded(context, processed, failed, markedFailed);
      return processed > 0 || failed > 0;

    } catch (Exception e) {
      context.log().error("Error during chunk embedding backfill", e);
      return false;
    }
  }

  private static void commitChunkIfNeeded(
      BackfillContext context, int processed, int failed, int markedFailed) {
    if (processed > 0 || failed > 0) {
      context
          .commitOps()
          .commitAndTrack(
              io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_EMBEDDING_CHUNK);
      context
          .log()
          .info(
              "Chunk backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
              processed,
              failed,
              markedFailed);
    }
  }

  public static int handleChunkEmbeddingFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String chunkId, String reason, Logger log) {
    try {
      String retryCountStr =
          documentFieldOps.getDocumentField(chunkId, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT);
      int retryCount = 0;
      if (retryCountStr != null && !retryCountStr.isBlank()) {
        try {
          retryCount = Integer.parseInt(retryCountStr);
        } catch (NumberFormatException ignored) {
          // Default to 0 if unparseable
        }
      }

      retryCount++;
      Map<String, Object> updates = new HashMap<>();

      if (retryCount >= SchemaFields.EMBEDDING_MAX_RETRIES) {
        updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
        updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
        log.warn(
            "Chunk embedding permanently FAILED for {} after {} retries: {}",
            chunkId,
            retryCount,
            reason);
        indexingCoordinator.updateDocument(chunkId, updates, true);
        return 1;
      } else {
        updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
        log.debug(
            "Chunk embedding retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.EMBEDDING_MAX_RETRIES,
            chunkId,
            reason);
        indexingCoordinator.updateDocument(chunkId, updates, true);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update chunk retry count for {}", chunkId, e);
      return 0;
    }
  }
}
