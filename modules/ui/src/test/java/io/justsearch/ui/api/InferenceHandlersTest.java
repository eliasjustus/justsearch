package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InferenceHandlers unit tests")
final class InferenceHandlersTest {

  @Nested
  @DisplayName("computeHardwareTier")
  class ComputeHardwareTierTests {

    @Test
    @DisplayName("returns cpu_only when VRAM undetected and inference offline")
    void cpuOnlyWhenNoVramAndOffline() {
      assertEquals("cpu_only", InferenceHandlers.computeHardwareTier(-1, false, false));
    }

    @Test
    @DisplayName("returns gpu_unknown when VRAM undetected but inference available")
    void gpuUnknownWhenNoVramButAvailable() {
      assertEquals("gpu_unknown", InferenceHandlers.computeHardwareTier(-1, true, false));
    }

    @Test
    @DisplayName("returns gpu_unknown when VRAM undetected but inference starting")
    void gpuUnknownWhenNoVramButStarting() {
      assertEquals("gpu_unknown", InferenceHandlers.computeHardwareTier(-1, false, true));
    }

    @Test
    @DisplayName("returns gpu_12gb_plus for 12+ GB VRAM")
    void gpu12gbPlus() {
      // 12 GB = 12_884_901_888 bytes (above the ~10.7 GiB threshold)
      assertEquals("gpu_12gb_plus", InferenceHandlers.computeHardwareTier(12_884_901_888L, false, false));
    }

    @Test
    @DisplayName("returns gpu_8gb for 8 GB VRAM")
    void gpu8gb() {
      // 8 GB = 8_589_934_592 bytes (above ~7.0 GiB threshold, below ~10.7 GiB)
      assertEquals("gpu_8gb", InferenceHandlers.computeHardwareTier(8_589_934_592L, false, false));
    }

    @Test
    @DisplayName("returns gpu_lt_8gb for 4 GB VRAM")
    void gpuLt8gbFor4gb() {
      // 4 GB = 4_294_967_296 bytes (above ~3.3 GiB threshold, below ~7.0 GiB)
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(4_294_967_296L, false, false));
    }

    @Test
    @DisplayName("returns gpu_lt_8gb for under-4GB VRAM")
    void gpuLt8gbForUnder4gb() {
      // 2 GB = below 4 GB threshold but above 0
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(2_147_483_648L, false, false));
    }

    @Test
    @DisplayName("returns gpu_lt_8gb for zero VRAM")
    void gpuLt8gbForZeroVram() {
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(0, false, false));
    }

    @Test
    @DisplayName("VRAM tier is independent of online/starting flags")
    void vramTierIgnoresOnlineFlags() {
      // When VRAM is detected (>= 0), the tier is based on VRAM alone
      assertEquals("gpu_12gb_plus", InferenceHandlers.computeHardwareTier(12_884_901_888L, true, true));
      assertEquals("gpu_8gb", InferenceHandlers.computeHardwareTier(8_589_934_592L, true, false));
      assertEquals("gpu_lt_8gb", InferenceHandlers.computeHardwareTier(4_294_967_296L, false, true));
    }
  }
}
