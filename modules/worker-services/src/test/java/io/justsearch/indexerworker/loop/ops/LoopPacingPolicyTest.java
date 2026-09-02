package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.embed.EmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the backfill-pacing gate (tempdoc 630). The two yield reasons are distinct: <b>energy</b>
 * defers regardless of GPU/CPU (power), while <b>GPU yield</b> defers only on a real VRAM conflict
 * (embeddings actually on the GPU). The headline case — energy-reduced + CPU embeddings — is the one
 * the pre-fix code got wrong (it kept running because of the {@code !isUsingGpu()} escape).
 */
final class LoopPacingPolicyTest {

  /** Minimal {@link EmbeddingProvider} where only {@code isUsingGpu()} matters to the gate. */
  private static EmbeddingProvider provider(boolean usingGpu) {
    return new EmbeddingProvider() {
      @Override public float[] embedDocument(String text) { return new float[0]; }
      @Override public float[] embedQuery(String text) { return new float[0]; }
      @Override public List<float[]> embedDocumentBatch(List<String> texts) { return List.of(); }
      @Override public int dimension() { return 0; }
      @Override public boolean isAvailable() { return true; }
      @Override public boolean isUsingGpu() { return usingGpu; }
      @Override
      public io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding embedWithSpans(
          String content, int[][] charSpans) {
        return null;
      }
    };
  }

  private static final EmbeddingProvider CPU = provider(false);
  private static final EmbeddingProvider GPU = provider(true);

  @Test
  @DisplayName("energy-reduced defers backfill even on CPU embeddings (the CPU-escape bug)")
  void energyDefersOnCpu() {
    // Pre-fix this returned true (ran), silently no-opping energy saver on GPU-less laptops.
    assertFalse(LoopPacingPolicy.shouldRunBackfill(false, true, CPU));
  }

  @Test
  @DisplayName("energy-reduced defers backfill on GPU embeddings too")
  void energyDefersOnGpu() {
    assertFalse(LoopPacingPolicy.shouldRunBackfill(false, true, GPU));
  }

  @Test
  @DisplayName("no yield reason ⇒ backfill runs (CPU and GPU)")
  void runsWhenIdle() {
    assertTrue(LoopPacingPolicy.shouldRunBackfill(false, false, CPU));
    assertTrue(LoopPacingPolicy.shouldRunBackfill(false, false, GPU));
  }

  @Test
  @DisplayName("Main GPU active defers only when embeddings are on the GPU (VRAM conflict)")
  void gpuYieldIsConflictOnly() {
    assertFalse(LoopPacingPolicy.shouldRunBackfill(true, false, GPU), "GPU embed + Main GPU ⇒ conflict");
    assertTrue(LoopPacingPolicy.shouldRunBackfill(true, false, CPU), "CPU embed ⇒ no VRAM conflict");
  }

  @Test
  @DisplayName("interrupt fires on energy-reduced + CPU (blocked), and on a GPU VRAM conflict")
  void interruptCovers() {
    // energy + CPU now blocks → interrupt true (pre-fix: not blocked on CPU).
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(true, false, true, CPU));
    // Tempdoc 885 item 3: userActive left the signature — foreground contention paces the
    // backfill (IndexingPacing) instead of interrupting it. The surviving yield reason this slot
    // used to cover is the GPU VRAM conflict: Main claimed the GPU and embeddings are on the GPU.
    assertTrue(
        LoopPacingPolicy.shouldInterruptBackfill(true, true, false, GPU),
        "GPU VRAM conflict blocks the backfill ⇒ interrupt");
    // running, idle, no yield ⇒ no interrupt.
    assertFalse(LoopPacingPolicy.shouldInterruptBackfill(true, false, false, CPU));
    // not running ⇒ interrupt.
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(false, false, false, CPU));
  }

  @Test
  @DisplayName(
      "isTimeCommitTriggered / isBufferCommitTriggered honor the passed-in threshold (tempdoc"
          + " 710 Wave-1.5 Move 4: thresholds moved from static constants to config parameters)")
  void commitTriggersHonorConfiguredThresholds() {
    assertFalse(LoopPacingPolicy.isTimeCommitTriggered(9_999L, 1, 10_000L));
    assertTrue(LoopPacingPolicy.isTimeCommitTriggered(10_000L, 1, 10_000L));
    assertFalse(LoopPacingPolicy.isTimeCommitTriggered(10_000L, 0, 10_000L), "no docs pending");
    // A smaller configured interval fires earlier — proves the value is honored, not hardcoded.
    assertTrue(LoopPacingPolicy.isTimeCommitTriggered(500L, 1, 250L));

    assertFalse(LoopPacingPolicy.isBufferCommitTriggered(999, 1000));
    assertTrue(LoopPacingPolicy.isBufferCommitTriggered(1000, 1000));
    // A smaller configured max fires earlier.
    assertTrue(LoopPacingPolicy.isBufferCommitTriggered(10, 5));
  }

  @Test
  @DisplayName(
      "isIdleCommitTriggered: 0 keeps the historical commit-on-first-empty-poll; a positive"
          + " index.commit.idle_ms requires the queue to have stayed empty that long (tempdoc 885"
          + " item 19)")
  void idleCommitHonorsTheConfiguredIdleWindow() {
    // Default (0) is the pre-885 behaviour exactly: commit as soon as the queue is empty.
    assertTrue(LoopPacingPolicy.isIdleCommitTriggered(1, 0L, 0L));
    assertTrue(LoopPacingPolicy.isIdleCommitTriggered(1, 0L, -1L), "negative also reads as off");

    // Nothing buffered ⇒ never commit, whatever the window says. This is the assertion that keeps
    // a raised window from turning into an empty commit on every idle tick.
    assertFalse(LoopPacingPolicy.isIdleCommitTriggered(0, 0L, 0L));
    assertFalse(LoopPacingPolicy.isIdleCommitTriggered(0, 60_000L, 5_000L));

    // A positive window: below it the buffered docs wait, at/above it they commit.
    assertFalse(LoopPacingPolicy.isIdleCommitTriggered(1, 4_999L, 5_000L));
    assertTrue(LoopPacingPolicy.isIdleCommitTriggered(1, 5_000L, 5_000L));
    assertTrue(LoopPacingPolicy.isIdleCommitTriggered(500, 9_000L, 5_000L));
  }
}
