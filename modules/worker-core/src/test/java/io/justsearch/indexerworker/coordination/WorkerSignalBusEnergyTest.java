package io.justsearch.indexerworker.coordination;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link WorkerSignalBus} energy defaults (tempdoc 630): {@code isEnergyReduced} defaults
 * to false (conservative — impls/hosts without the signal never throttle) and {@code
 * shouldYieldGpuBackfill} is the OR of GPU-claimed and energy-reduced.
 */
final class WorkerSignalBusEnergyTest {

  /** Minimal stub overriding only the two inputs to the energy/GPU yield defaults. */
  private static WorkerSignalBus stub(boolean gpuActive, Boolean energyReduced) {
    return new WorkerSignalBus() {
      @Override public void open() {}
      @Override public void writePort(int port) {}
      @Override public long readActivity() { return 0; }
      @Override public long readHeartbeat() { return 0; }
      @Override public boolean isShutdownRequested() { return false; }
      @Override public boolean shouldDie() { return false; }
      @Override public boolean isUserActive() { return false; }
      @Override public boolean isMainGpuActive() { return gpuActive; }
      @Override public boolean isEnergyReduced() {
        return energyReduced != null ? energyReduced : WorkerSignalBus.super.isEnergyReduced();
      }
      @Override public long startupTime() { return 0; }
      @Override public void close() throws IOException {}
    };
  }

  @Test
  @DisplayName("isEnergyReduced default is false (conservative)")
  void energyDefaultsFalse() {
    // Pass-through to the interface default.
    assertFalse(stub(false, null).isEnergyReduced());
  }

  @Test
  @DisplayName("shouldYieldGpuBackfill = isMainGpuActive OR isEnergyReduced")
  void yieldComposition() {
    assertFalse(stub(false, false).shouldYieldGpuBackfill());
    assertTrue(stub(true, false).shouldYieldGpuBackfill(), "GPU claimed ⇒ yield");
    assertTrue(stub(false, true).shouldYieldGpuBackfill(), "energy reduced ⇒ yield");
    assertTrue(stub(true, true).shouldYieldGpuBackfill());
  }
}
