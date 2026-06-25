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
  @DisplayName("interrupt fires on energy-reduced + CPU (blocked), and on user-active")
  void interruptCovers() {
    // energy + CPU now blocks → interrupt true (pre-fix: not blocked on CPU).
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(true, false, false, true, CPU));
    // user active always interrupts.
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(true, true, false, false, GPU));
    // running, idle, no yield ⇒ no interrupt.
    assertFalse(LoopPacingPolicy.shouldInterruptBackfill(true, false, false, false, CPU));
    // not running ⇒ interrupt.
    assertTrue(LoopPacingPolicy.shouldInterruptBackfill(false, false, false, false, CPU));
  }
}
