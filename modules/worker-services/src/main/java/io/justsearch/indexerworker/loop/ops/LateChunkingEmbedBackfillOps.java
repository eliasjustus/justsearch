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
 * predicate this pass targets. This pass takes FULL ownership of chunked parents (tempdoc 691
 * Stage A3 full-ownership follow-up): a deferral no longer leaves the parent PENDING for a
 * different pass to pick up windowed — instead it falls back INLINE to the same windowed provider
 * call the individual backfill uses ({@link EmbeddingProvider#embedDocument}), writing {@code
 * VECTOR}/{@code EMBEDDING_STATUS}/{@code EMBEDDING_RETRY_COUNT} exactly like the single-pass
 * success path. Two cases fall back: content exceeding the raised single-pass limit ({@link
 * io.justsearch.indexerworker.embed.EmbeddingConfig#lateChunkingContextLength()}), which gets
 * {@code null} back from {@link EmbeddingProvider#embedWithSpans}; and a GPU BFC-arena OOM on the
 * single-pass forward call (tempdoc 691 Stage A3 — the whole-doc pass needs more contiguous
 * memory than a windowed pass), detected via {@link #isArenaOomFailure}. A failure of the
 * windowed fallback itself escalates through the same retry-count/FAILED-at-max path as any other
 * embed failure. Coverage for these parents no longer depends on scheduling races between this
 * pass and the combined pass — every batch with an eligible chunked parent makes progress, so the
 * drain loop in {@code BackfillScheduler#runIdleCycle} terminates naturally once the pending query
 * returns nothing eligible, not on a deferral-only batch.
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
   * Per-batch outcome (tempdoc 691 Stage A3 drain-loop fix; full-ownership follow-up). {@link
   * #hasProgress()} is the scheduler's loop-continuation signal: {@code true} means the batch
   * resolved at least one parent — embedded single-pass, fell back to the windowed embed, or
   * failure-escalated — so another PENDING parent may exist beyond this batch's {@code batchSize}
   * window — worth another pass. {@code false} means the batch found no chunked parent to act on
   * (e.g. every pending doc in the window was non-chunked, or had blank content) — the scheduler
   * stops the drain loop rather than tracking which IDs it already saw. Unlike the original
   * design, {@code longDocWindowed}/{@code arenaOomWindowed} no longer mean "left PENDING for a
   * different pass" — they mean "resolved inline via the windowed fallback", so they count toward
   * progress just like {@code processedParents}/{@code failedParents}.
   */
  public record LateChunkingBackfillResult(
      int processedParents, int failedParents, int longDocWindowed, int arenaOomWindowed) {

    public boolean hasProgress() {
      return processedParents > 0
          || failedParents > 0
          || longDocWindowed > 0
          || arenaOomWindowed > 0;
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
   * @return the per-batch outcome — see {@link LateChunkingBackfillResult#hasProgress()} for what
   *     counts as progress now that long-doc/arena-OOM cases resolve inline via the windowed
   *     fallback instead of deferring to a different pass. The scheduler's drain loop (tempdoc 691
   *     Stage A3) uses this to decide whether to run another batch.
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
    int longDocWindowed = 0;
    int arenaOomWindowed = 0;
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
            // chunking). Tempdoc 691 Stage A3 full-ownership: fall back INLINE to the windowed
            // provider call rather than leaving the parent PENDING for a different pass — this
            // pass owns chunked parents end to end.
            if (embedWindowedFallbackAndUpdate(context, provider, parentId, parentContent)) {
              longDocWindowed++;
            } else {
              failedParents++;
            }
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
            // contention signal, not a bad-input failure. Fall back INLINE to the windowed
            // provider call (same as the over-limit null case) instead of deferring to a
            // different pass.
            context
                .log()
                .warn(
                    "Late-chunking embed falling back to windowed for parent {} (GPU arena OOM"
                        + " on single-pass batch-1): {}",
                    parentId,
                    e.getMessage());
            if (embedWindowedFallbackAndUpdate(context, provider, parentId, parentContent)) {
              arenaOomWindowed++;
            } else {
              failedParents++;
            }
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
          escalateParentFailure(context, parentId);
          failedParents++;
        }
      }
    } catch (Exception e) {
      context.log().error("Error during late-chunking embed backfill", e);
    }

    if (processedParents > 0 || failedParents > 0 || longDocWindowed > 0 || arenaOomWindowed > 0) {
      context
          .commitOps()
          .commitAndTrack(CommitReason.BACKFILL_EMBEDDING);
    }

    if (processedParents > 0 || failedParents > 0 || longDocWindowed > 0 || arenaOomWindowed > 0) {
      long embedMs = embedNs / 1_000_000;
      long totalMs = (System.nanoTime() - t0) / 1_000_000;
      context
          .log()
          .info(
              "Late-chunking embed backfill: parents processed={}, failed={},"
                  + " long-doc-windowed={}, arena-oom-windowed={}, embed={}ms, total={}ms",
              processedParents,
              failedParents,
              longDocWindowed,
              arenaOomWindowed,
              embedMs,
              totalMs);
    }

    return new LateChunkingBackfillResult(
        processedParents, failedParents, longDocWindowed, arenaOomWindowed);
  }

  /**
   * Inline windowed fallback (tempdoc 691 Stage A3 full-ownership): embeds via the same provider
   * call the individual backfill uses for a single doc ({@link EmbeddingProvider#embedDocument},
   * see {@code EmbeddingBackfillOps#embedAndUpdateSingle}) and, on success, writes {@code
   * VECTOR}/{@code EMBEDDING_STATUS=COMPLETED}/{@code EMBEDDING_RETRY_COUNT=0} exactly like the
   * single-pass success path above. On failure (empty vector or a thrown exception), escalates
   * through the same retry-count/FAILED-at-max path as any other embed failure — the caller only
   * needs to bump its own counters based on the return value.
   *
   * @return {@code true} if the fallback embed succeeded and was written.
   */
  private static boolean embedWindowedFallbackAndUpdate(
      BackfillContext context, EmbeddingProvider provider, String parentId, String parentContent) {
    try {
      float[] vector = provider.embedDocument(parentContent);
      if (vector != null && vector.length > 0) {
        Map<String, Object> parentUpdates = new HashMap<>();
        parentUpdates.put(SchemaFields.VECTOR, vector);
        parentUpdates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
        parentUpdates.put(SchemaFields.EMBEDDING_RETRY_COUNT, "0");
        context
            .indexingCoordinator()
            .updateDocumentsBatch(List.of(Map.entry(parentId, parentUpdates)));
        return true;
      }
      context
          .log()
          .warn("Late-chunking windowed fallback returned empty vector for parent {}", parentId);
      escalateParentFailure(context, parentId);
      return false;
    } catch (Exception e) {
      context
          .log()
          .warn(
              "Late-chunking windowed fallback failed for parent {}: {}",
              parentId,
              e.getMessage());
      escalateParentFailure(context, parentId);
      return false;
    }
  }

  /**
   * Shared failure-escalation write: fetches the parent's current retry count and delegates the
   * increment/FAILED-at-max decision to {@link
   * EmbeddingBackfillOps#computeEmbeddingFailureUpdate} (tempdoc 700 parity) — the same pure
   * helper the per-doc/combined embed paths use.
   */
  private static void escalateParentFailure(BackfillContext context, String parentId) {
    Map<String, Map<String, String>> parentRetry =
        context
            .documentFieldOps()
            .getDocumentFieldsBatch(List.of(parentId), Set.of(SchemaFields.EMBEDDING_RETRY_COUNT));
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
