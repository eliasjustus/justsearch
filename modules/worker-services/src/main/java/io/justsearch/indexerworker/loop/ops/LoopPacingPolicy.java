/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;

public final class LoopPacingPolicy {
  private static final long BREATH_HOLD_MS = 500L;
  private static final long IDLE_SLEEP_MS = 1000L;
  private static final long ACTIVE_IDLE_SLEEP_MS = 100L;
  private static final int POLL_BATCH_SIZE = 16;
  private static final int EMBEDDING_BACKFILL_BATCH_SIZE = 100;
  private static final int NER_BACKFILL_BATCH_SIZE = 100;
  private static final int DISAMBIGUATION_BACKFILL_BATCH_SIZE = 500;
  private static final int SPLADE_BACKFILL_BATCH_SIZE = 200;
  private static final int SPLADE_INTERLEAVE_BATCH_SIZE = 10;
  private static final long SPLADE_INTERLEAVE_INTERVAL_MS = 5_000L;
  private static final long COMMIT_INTERVAL_MS = 10_000L;
  private static final int MAX_DOCS_BEFORE_COMMIT = 1000;

  private LoopPacingPolicy() {}

  public static long breathHoldMs() {
    return BREATH_HOLD_MS;
  }

  public static long idleSleepMs() {
    return IDLE_SLEEP_MS;
  }

  public static long activeIdleSleepMs() {
    return ACTIVE_IDLE_SLEEP_MS;
  }

  /**
   * Returns a shorter sleep duration when recently active (just finished processing), allowing the
   * loop to pick up newly queued work faster. Falls back to the standard idle sleep after sustained
   * idleness.
   */
  public static long idleSleepMs(boolean recentlyActive) {
    return recentlyActive ? ACTIVE_IDLE_SLEEP_MS : IDLE_SLEEP_MS;
  }

  public static int pollBatchSize() {
    return POLL_BATCH_SIZE;
  }

  public static int embeddingBackfillBatchSize() {
    return EMBEDDING_BACKFILL_BATCH_SIZE;
  }


  public static long commitIntervalMs() {
    return COMMIT_INTERVAL_MS;
  }

  public static int maxDocsBeforeCommit() {
    return MAX_DOCS_BEFORE_COMMIT;
  }

  /**
   * Whether GPU-heavy bulk backfill may run. The two yield reasons are deliberately distinct
   * (tempdoc 630):
   *
   * <ul>
   *   <li><b>Energy</b> ({@code energyReduced}, OS energy saver): defers backfill <i>regardless of
   *       GPU/CPU</i> — CPU backfill still drains the battery, so the power reason applies even when
   *       embeddings run on CPU (the common case on the GPU-less laptops where energy saver matters).
   *   <li><b>GPU yield</b> ({@code mainGpuActive}, Main claimed the GPU): defers only when embeddings
   *       are <i>actually on the GPU</i> — a VRAM conflict cannot exist on CPU embeddings.
   * </ul>
   *
   * Folding energy into the GPU-conflict escape would silently no-op the energy throttle on CPU
   * embeddings, so the two are kept separate here.
   */
  public static boolean shouldRunBackfill(
      boolean mainGpuActive, boolean energyReduced, EmbeddingProvider embeddingProvider) {
    if (energyReduced) {
      return false; // power: defer regardless of GPU/CPU
    }
    return !mainGpuActive || !embeddingProvider.isUsingGpu(); // VRAM conflict: only when on GPU
  }

  public static boolean shouldInterruptBackfill(
      boolean running,
      boolean userActive,
      boolean mainGpuActive,
      boolean energyReduced,
      EmbeddingProvider embeddingProvider) {
    boolean backfillBlocked = !shouldRunBackfill(mainGpuActive, energyReduced, embeddingProvider);
    return !running || userActive || backfillBlocked;
  }

  public static int nerBackfillBatchSize() {
    return NER_BACKFILL_BATCH_SIZE;
  }

  public static int disambiguationBackfillBatchSize() {
    return DISAMBIGUATION_BACKFILL_BATCH_SIZE;
  }

  public static int spladeBackfillBatchSize() {
    return SPLADE_BACKFILL_BATCH_SIZE;
  }

  public static int spladeInterleaveBatchSize() {
    return SPLADE_INTERLEAVE_BATCH_SIZE;
  }

  public static long spladeInterleaveIntervalMs() {
    return SPLADE_INTERLEAVE_INTERVAL_MS;
  }

  public static boolean isTimeCommitTriggered(long timeSinceCommitMs, long indexedSinceCommit) {
    return timeSinceCommitMs >= COMMIT_INTERVAL_MS && indexedSinceCommit > 0;
  }

  public static boolean isBufferCommitTriggered(long indexedSinceCommit) {
    return indexedSinceCommit >= MAX_DOCS_BEFORE_COMMIT;
  }
}
