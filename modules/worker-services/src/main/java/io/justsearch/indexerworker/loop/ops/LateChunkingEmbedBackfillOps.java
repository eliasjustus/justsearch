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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Late-chunking embed backfill (tempdoc 691 Phase 2, arXiv:2409.04701): for a long, chunked parent
 * doc whose embedding is PENDING, embeds the WHOLE document in ONE batch-1 forward pass via {@link
 * EmbeddingProvider#embedWithSpans} (empty span array) and writes only the parent {@code VECTOR} —
 * instead of the parent being embedded at the base {@code context_length} window-mean (the F-030
 * dense-quality loss: `vector` nDCG@10 0.0597 at 2048 tokens vs 0.3403 at a single 8192-token pass,
 * tempdoc 691 §Phase J).
 *
 * <p><b>VECTOR-only, not chunk-vector dedup.</b> An earlier design (tempdoc 691 Phase 1) also
 * derived every chunk doc's {@code CHUNK_VECTOR} from this same forward pass (per-span masked-mean
 * pooling) to dedup the separate per-chunk embed pass. §Phase M's offline CLS check
 * (`late_chunk_cls_check_691.py`) measured that half against production per-chunk CLS embeds on
 * legal-clerc-200 and found a REGRESSION (nDCG@10 −0.2329, R@10 −0.265) — gte-multilingual-base is
 * CLS-pooled, and masked-mean pooling a CLS model is an off-distribution failure mode the
 * literature (arXiv:2409.04701's scope, Jina's "CLS/max pooling aren't compatible with late
 * chunking") predicted. The per-span chunk half is DROPPED; chunk docs keep their existing
 * separate per-chunk CLS embed path (SPLADE/NER also untouched, same as before) — see §Phase M/L-8
 * in the tempdoc for the full verdict.
 *
 * <p><b>Additive and flag-gated (default off).</b> Guarded by {@link
 * BackfillContext#lateChunkingEnabled()} — a first-line check with zero I/O when off, so disabling
 * the flag is a strict no-op. Runs BEFORE the existing combined/individual backfill in {@code
 * BackfillScheduler#runIdleCycle}, touching ONLY {@code VECTOR}/{@code EMBEDDING_STATUS}/{@code
 * EMBEDDING_RETRY_COUNT} on the parent.
 *
 * <p>Parents that are not chunked (no {@code PARENT_DOC_ID}-matching chunk docs) are left alone —
 * the existing per-doc/combined embed path handles those; chunked-ness is still the "long doc"
 * predicate this pass targets. Parents whose content exceeds the raised single-pass limit ({@link
 * io.justsearch.indexerworker.embed.EmbeddingConfig#lateChunkingContextLength()}) get {@code null}
 * back from {@link EmbeddingProvider#embedWithSpans}; this pass leaves them PENDING for the
 * existing fallback rather than guessing at a degraded embedding.
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

  /** VECTOR-only mode (§Phase M): no char spans are derived or passed to the encoder. */
  private static final int[][] NO_SPANS = new int[0][];

  /** @return true if any long-doc chunked parent was processed (embedded, or failure-escalated) */
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

        // "Has chunk docs" is the long/chunked-doc predicate this pass targets (parents without
        // chunks stay on the normal path). Only existence matters here — VECTOR-only mode never
        // reads chunk span/order metadata, so a limit=1 existence probe suffices.
        List<String> chunkIds =
            context.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, parentId, 1);
        if (chunkIds.isEmpty()) {
          continue;
        }

        Map<String, String> content =
            context.documentFieldOps().getDocumentContentBatch(List.of(parentId));
        String parentContent = content.get(parentId);
        if (parentContent == null || parentContent.isBlank()) {
          // Blank content — leave for the existing path's blank-content handling.
          continue;
        }

        try {
          long tEmbed = System.nanoTime();
          EmbeddingService.ChunkedEmbedding result;
          try {
            result = provider.embedWithSpans(parentContent, NO_SPANS);
          } finally {
            embedNs += System.nanoTime() - tEmbed;
          }
          if (result == null) {
            // Content exceeds the raised single-pass limit (or backend doesn't support late
            // chunking). Leave PENDING for the existing per-doc/combined fallback.
            longDocDeferred++;
            continue;
          }

          Map<String, Object> parentUpdates = new HashMap<>();
          parentUpdates.put(SchemaFields.VECTOR, result.primaryVector());
          parentUpdates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          parentUpdates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");

          context
              .indexingCoordinator()
              .updateDocumentsBatch(List.of(Map.entry(parentId, parentUpdates)));
          processedParents++;
          didWork = true;
        } catch (Exception e) {
          // Tempdoc 700 escalation parity: a late-chunking inference failure gets the same
          // retry-count/FAILED-at-max treatment as the existing per-doc/combined embed paths, via
          // the same pure helper (EmbeddingBackfillOps#computeEmbeddingFailureUpdate). VECTOR-only
          // mode never touched the chunk docs, so failure escalation is parent-only — chunk
          // statuses are untouched either way.
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

          context
              .indexingCoordinator()
              .updateDocumentsBatch(
                  List.of(
                      Map.entry(
                          parentId,
                          EmbeddingBackfillOps.computeEmbeddingFailureUpdate(parentRetryCount))));
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
