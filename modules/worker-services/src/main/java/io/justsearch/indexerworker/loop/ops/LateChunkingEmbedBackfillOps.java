/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import ai.onnxruntime.OrtException;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ort.NativeSessionHandle;
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
 * the flag is a strict no-op. Drained in its own loop BEFORE the existing combined/individual
 * backfill in {@code BackfillScheduler#runIdleCycle} (tempdoc 691 Stage A3 — a single batch used
 * to be starved by the combined pass's own tight loop), touching ONLY {@code VECTOR}/{@code
 * EMBEDDING_STATUS}/{@code EMBEDDING_RETRY_COUNT} on the parent.
 *
 * <p>Parents that are not chunked (no {@code PARENT_DOC_ID}-matching chunk docs) are left alone —
 * the existing per-doc/combined embed path handles those; chunked-ness is still the "long doc"
 * predicate this pass targets. Parents whose content exceeds the raised single-pass limit ({@link
 * io.justsearch.indexerworker.embed.EmbeddingConfig#lateChunkingContextLength()}) get {@code null}
 * back from {@link EmbeddingProvider#embedWithSpans}; this pass leaves them PENDING for the
 * existing fallback rather than guessing at a degraded embedding. A GPU BFC-arena OOM on the
 * single-pass forward call (tempdoc 691 Stage A3 — the whole-doc pass needs more contiguous
 * memory than a windowed pass) gets the same PENDING-deferral treatment, not a failure-count
 * burn — see {@link #isArenaOomFailure}.
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

  /**
   * Per-batch outcome (tempdoc 691 Stage A3 drain-loop fix). {@link #hasProgress()} is the
   * scheduler's loop-continuation signal: {@code true} means the batch embedded or
   * failure-escalated at least one parent, so another PENDING parent may exist beyond this
   * batch's {@code batchSize} window — worth another pass. {@code false} means the batch produced
   * only deferrals ({@code longDocDeferred}/{@code arenaOomDeferred}) — those parents stay PENDING
   * and the next query would return the same set, so looping again would just re-defer them
   * forever. The scheduler stops the drain loop on {@code false} rather than tracking which IDs it
   * already saw.
   */
  public record LateChunkingBackfillResult(
      int processedParents, int failedParents, int longDocDeferred, int arenaOomDeferred) {

    public boolean hasProgress() {
      return processedParents > 0 || failedParents > 0;
    }
  }

  /** VECTOR-only mode (§Phase M): no char spans are derived or passed to the encoder. */
  private static final int[][] NO_SPANS = new int[0][];

  private static final LateChunkingBackfillResult NO_OP_RESULT =
      new LateChunkingBackfillResult(0, 0, 0, 0);

  /**
   * Thin boolean wrapper over {@link #processLateChunkingEmbedBackfillDetailed} for callers that
   * only need the legacy did-work signal (kept for test/call-site compatibility).
   *
   * @return {@code true} if any long-doc chunked parent was processed (embedded, or
   *     failure-escalated) this batch.
   */
  public static boolean processLateChunkingEmbedBackfill(BackfillContext context) {
    return processLateChunkingEmbedBackfillDetailed(context).hasProgress();
  }

  /**
   * Runs one batch of the late-chunking embed pass and reports its detailed outcome.
   *
   * @return the per-batch outcome, distinguishing real progress (processed/failed) from
   *     deferral-only batches — see {@link LateChunkingBackfillResult#hasProgress()}. The
   *     scheduler's drain loop (tempdoc 691 Stage A3) uses this to decide whether to run another
   *     batch.
   */
  public static LateChunkingBackfillResult processLateChunkingEmbedBackfillDetailed(
      BackfillContext context) {
    if (!context.lateChunkingEnabled()) {
      return NO_OP_RESULT;
    }
    if (!context.allowEmbeddingWritesSupplier().getAsBoolean()) {
      return NO_OP_RESULT;
    }
    EmbeddingProvider provider = context.embeddingProviderSupplier().get();
    if (provider == null || !provider.isAvailable()) {
      return NO_OP_RESULT;
    }

    List<String> pendingParentIds =
        context
            .documentFieldOps()
            .queryDocIdsByField(
                SchemaFields.EMBEDDING_STATUS,
                SchemaFields.EMBEDDING_STATUS_PENDING,
                context.batchSize());
    if (pendingParentIds.isEmpty()) {
      return NO_OP_RESULT;
    }

    int processedParents = 0;
    int failedParents = 0;
    int longDocDeferred = 0;
    int arenaOomDeferred = 0;
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
        } catch (Exception e) {
          if (isArenaOomFailure(e)) {
            // Tempdoc 691 Stage A3: a single-pass batch-1 forward pass over the whole document
            // needs more contiguous GPU arena memory than a windowed pass — this is a resource
            // contention signal, not a bad-input failure. Treat like the over-limit null case:
            // leave PENDING, no retry-count burn, no failure escalation. The existing windowed
            // combined/individual fallback handles these parents fine.
            arenaOomDeferred++;
            context
                .log()
                .warn(
                    "Late-chunking embed deferred for parent {} (GPU arena OOM on single-pass"
                        + " batch-1): {}",
                    parentId,
                    e.getMessage());
            continue;
          }
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

    if (processedParents > 0 || failedParents > 0 || longDocDeferred > 0 || arenaOomDeferred > 0) {
      long embedMs = embedNs / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      context
          .log()
          .info(
              "Late-chunking embed backfill: parents processed={}, failed={},"
                  + " long-doc-deferred={}, arena-oom-deferred={}, embed={}ms, total={}ms",
              processedParents,
              failedParents,
              longDocDeferred,
              arenaOomDeferred,
              embedMs,
              totalMs);
    }

    return new LateChunkingBackfillResult(
        processedParents, failedParents, longDocDeferred, arenaOomDeferred);
  }

  /**
   * Walks the exception's cause chain looking for an {@link OrtException} matching {@link
   * NativeSessionHandle#isBfcArenaFailure} — the single choke point for the BFC-arena string
   * match (owned by {@code ort-common}, shared with {@code SpladeEncoder}/{@code
   * CrossEncoderReranker}). {@link EmbeddingService#embedWithSpans} wraps the raw {@code
   * OrtException} in a {@code RuntimeException}, so a direct {@code instanceof} on the caught
   * exception isn't enough — this walks {@link Throwable#getCause()} to find it either way.
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
