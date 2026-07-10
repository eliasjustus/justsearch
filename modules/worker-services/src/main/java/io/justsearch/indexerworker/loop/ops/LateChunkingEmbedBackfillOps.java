/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Late-chunking embed backfill (tempdoc 691 Phase 1, arXiv:2409.04701): for a chunked parent doc
 * whose embedding is PENDING, embeds the parent content ONCE via {@link
 * EmbeddingProvider#embedWithSpans} and derives both the whole-document {@code VECTOR} and every
 * chunk doc's {@code CHUNK_VECTOR} from that single forward pass — instead of the parent being
 * embedded-and-pooled internally (discarded) AND each chunk doc being embedded again independently
 * (the E-5 duplicate-embedding waste measured in tempdoc 691 Phase E-5/F).
 *
 * <p><b>Additive and flag-gated (default off).</b> Guarded by {@link
 * BackfillContext#lateChunkingEnabled()} — a first-line check with zero I/O when off, so disabling
 * the flag is a strict no-op. When it runs, it touches ONLY {@code VECTOR}/{@code EMBEDDING_STATUS}
 * on the parent and {@code CHUNK_VECTOR}/{@code CHUNK_EMBEDDING_STATUS} on each chunk doc — SPLADE
 * and NER are deliberately left untouched so the existing combined/individual backfill still
 * processes those stages normally afterward (this pass runs BEFORE it in {@code
 * BackfillScheduler#runIdleCycle}).
 *
 * <p>Parents that are not chunked (no {@code PARENT_DOC_ID}-matching chunk docs) are left alone —
 * the existing per-doc/combined embed path handles those. Parents whose content exceeds the
 * model's context window get {@code null} back from {@link EmbeddingProvider#embedWithSpans} (Phase
 * 2 scope); this pass leaves them PENDING for the existing fallback rather than guessing at a
 * degraded embedding.
 */
public final class LateChunkingEmbedBackfillOps {
  private LateChunkingEmbedBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      boolean lateChunkingEnabled,
      int batchSize,
      Logger log) {}

  private static final Set<String> CHUNK_SPAN_FIELDS =
      Set.of(
          SchemaFields.CHUNK_INDEX,
          SchemaFields.CHUNK_START_CHAR,
          SchemaFields.CHUNK_END_CHAR,
          SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT);

  /** @return true if any chunked parent was processed (embedded, or failure-escalated) */
  public static boolean processLateChunkingEmbedBackfill(BackfillContext context) {
    if (!context.lateChunkingEnabled()) {
      return false;
    }
    if (!context.allowEmbeddingWritesSupplier().getAsBoolean()) {
      return false;
    }
    EmbeddingProvider provider = context.embeddingProviderSupplier().get();
    if (provider == null || !provider.isAvailable()) {
      return false;
    }

    List<String> pendingParentIds =
        context
            .documentFieldOps()
            .queryDocIdsByField(
                SchemaFields.EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_PENDING,
                context.batchSize());
    if (pendingParentIds.isEmpty()) {
      return false;
    }

    boolean didWork = false;
    int processedParents = 0;
    int failedParents = 0;
    int longDocDeferred = 0;
    long embedNs = 0;
    long t0 = System.nanoTime();

    try {
      for (String parentId : pendingParentIds) {
        if (!context.runningSupplier().getAsBoolean() || context.signalBus().isUserActive()) {
          break;
        }

        List<String> chunkIds =
            context
                .documentFieldOps()
                .queryDocIdsByField(SchemaFields.PARENT_DOC_ID, parentId, Integer.MAX_VALUE);
        if (chunkIds.isEmpty()) {
          // Not a chunked parent (content < ChunkDocumentWriter's threshold) — leave for the
          // existing per-doc/combined embed path.
          continue;
        }

        Map<String, String> content =
            context.documentFieldOps().getDocumentContentBatch(List.of(parentId));
        String parentContent = content.get(parentId);
        if (parentContent == null || parentContent.isBlank()) {
          // Blank content — leave for the existing path's blank-content handling.
          continue;
        }

        Map<String, Map<String, String>> chunkFields =
            context.documentFieldOps().getDocumentFieldsBatch(chunkIds, CHUNK_SPAN_FIELDS);

        List<String> orderedChunkIds = new ArrayList<>(chunkIds);
        boolean spansValid = true;
        for (String cid : orderedChunkIds) {
          Map<String, String> f = chunkFields.getOrDefault(cid, Map.of());
          if (f.get(SchemaFields.CHUNK_START_CHAR) == null
              || f.get(SchemaFields.CHUNK_END_CHAR) == null) {
            spansValid = false;
            break;
          }
        }
        if (!spansValid) {
          // Malformed/missing chunk span metadata — leave PENDING for the existing path rather
          // than guessing at spans.
          continue;
        }
        orderedChunkIds.sort(
            Comparator.comparingInt(
                cid ->
                    parseIntOrDefault(
                        chunkFields.getOrDefault(cid, Map.of()).get(SchemaFields.CHUNK_INDEX),
                        Integer.MAX_VALUE)));

        int[][] spans = new int[orderedChunkIds.size()][2];
        for (int i = 0; i < orderedChunkIds.size(); i++) {
          Map<String, String> f = chunkFields.getOrDefault(orderedChunkIds.get(i), Map.of());
          spans[i][0] = parseIntOrDefault(f.get(SchemaFields.CHUNK_START_CHAR), 0);
          spans[i][1] = parseIntOrDefault(f.get(SchemaFields.CHUNK_END_CHAR), 0);
        }

        try {
          long tEmbed = System.nanoTime();
          EmbeddingService.ChunkedEmbedding result;
          try {
            result = provider.embedWithSpans(parentContent, spans);
          } finally {
            embedNs += System.nanoTime() - tEmbed;
          }
          if (result == null) {
            // Content exceeds the model's context window (or backend doesn't support late
            // chunking) — Phase 2 scope. Leave PENDING for the existing per-doc/combined
            // fallback.
            longDocDeferred++;
            continue;
          }
          if (result.chunkVectors().size() != orderedChunkIds.size()) {
            context
                .log()
                .warn(
                    "Late-chunking embed: chunk-vector count mismatch for parent {} (expected {},"
                        + " got {}), leaving PENDING",
                    parentId,
                    orderedChunkIds.size(),
                    result.chunkVectors().size());
            continue;
          }

          List<Map.Entry<String, Map<String, Object>>> batchUpdates =
              new ArrayList<>(orderedChunkIds.size() + 1);
          Map<String, Object> parentUpdates = new HashMap<>();
          parentUpdates.put(SchemaFields.VECTOR, result.primaryVector());
          parentUpdates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          parentUpdates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
          batchUpdates.add(Map.entry(parentId, parentUpdates));

          for (int i = 0; i < orderedChunkIds.size(); i++) {
            Map<String, Object> chunkUpdates = new HashMap<>();
            chunkUpdates.put(SchemaFields.CHUNK_VECTOR, result.chunkVectors().get(i));
            chunkUpdates.put(
                SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            chunkUpdates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");
            batchUpdates.add(Map.entry(orderedChunkIds.get(i), chunkUpdates));
          }

          context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
          processedParents++;
          didWork = true;
        } catch (Exception e) {
          // Tempdoc 700 escalation parity: a late-chunking inference failure gets the same
          // retry-count/FAILED-at-max treatment as the existing per-doc/combined embed paths —
          // via the SAME pure helpers (EmbeddingBackfillOps#computeEmbeddingFailureUpdate /
          // computeChunkEmbeddingFailureUpdate) — applied to the parent AND every one of its
          // chunks (this pass embeds them together, so they fail together). Does NOT touch
          // SPLADE_STATUS/NER_STATUS.
          context
              .log()
              .warn("Late-chunking embed failed for parent {}: {}", parentId, e.getMessage());
          Map<String, Map<String, String>> parentRetry =
              context
                  .documentFieldOps()
                  .getDocumentFieldsBatch(
                      List.of(parentId), Set.of(SchemaFields.EMBEDDING_RETRY_COUNT));
          int parentRetryCount =
              parseIntOrDefault(
                  parentRetry.getOrDefault(parentId, Map.of()).get(SchemaFields.EMBEDDING_RETRY_COUNT),
                  0);

          List<Map.Entry<String, Map<String, Object>>> failureUpdates =
              new ArrayList<>(orderedChunkIds.size() + 1);
          failureUpdates.add(
              Map.entry(
                  parentId, EmbeddingBackfillOps.computeEmbeddingFailureUpdate(parentRetryCount)));
          for (String cid : orderedChunkIds) {
            int chunkRetryCount =
                parseIntOrDefault(
                    chunkFields
                        .getOrDefault(cid, Map.of())
                        .get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT),
                    0);
            failureUpdates.add(
                Map.entry(
                    cid, EmbeddingBackfillOps.computeChunkEmbeddingFailureUpdate(chunkRetryCount)));
          }
          context.indexingCoordinator().updateDocumentsBatch(failureUpdates);
          failedParents++;
          didWork = true;
        }
      }
    } catch (Exception e) {
      context.log().error("Error during late-chunking embed backfill", e);
    }

    if (processedParents > 0 || failedParents > 0) {
      context
          .commitOps()
          .commitAndTrack(CommitReason.BACKFILL_EMBEDDING);
    }

    if (processedParents > 0 || failedParents > 0 || longDocDeferred > 0) {
      long embedMs = embedNs / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      context
          .log()
          .info(
              "Late-chunking embed backfill: parents processed={}, failed={},"
                  + " long-doc-deferred={}, embed={}ms, total={}ms",
              processedParents,
              failedParents,
              longDocDeferred,
              embedMs,
              totalMs);
    }

    return didWork;
  }

  private static int parseIntOrDefault(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }
}
