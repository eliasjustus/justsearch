package io.justsearch.ort.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers each branch of {@link FailureCause#classifyGpuInitException(Throwable)}. */
@DisplayName("FailureCause classifier")
final class FailureCauseClassifierTest {

  @Test
  @DisplayName("null throwable → UNKNOWN")
  void nullInput() {
    assertEquals(FailureCause.UNKNOWN, FailureCause.classifyGpuInitException(null));
  }

  @Test
  @DisplayName("UnsatisfiedLinkError → CUDA_UNAVAILABLE")
  void unsatisfiedLink() {
    assertEquals(
        FailureCause.CUDA_UNAVAILABLE,
        FailureCause.classifyGpuInitException(new UnsatisfiedLinkError("missing dll")));
  }

  @Test
  @DisplayName("OOM message → OOM")
  void oom() {
    assertEquals(
        FailureCause.OOM,
        FailureCause.classifyGpuInitException(new RuntimeException("out of memory on device")));
  }

  @Test
  @DisplayName("CUDA + driver message → DRIVER_ERROR")
  void cudaDriver() {
    assertEquals(
        FailureCause.DRIVER_ERROR,
        FailureCause.classifyGpuInitException(new RuntimeException("CUDA driver version is insufficient")));
  }

  @Test
  @DisplayName("CUDA + provider message → CUDA_UNAVAILABLE")
  void cudaProvider() {
    assertEquals(
        FailureCause.CUDA_UNAVAILABLE,
        FailureCause.classifyGpuInitException(
            new RuntimeException("Could not load CUDA execution provider")));
  }

  @Test
  @DisplayName("CUDA + not-available message → CUDA_UNAVAILABLE")
  void cudaNotAvailable() {
    assertEquals(
        FailureCause.CUDA_UNAVAILABLE,
        FailureCause.classifyGpuInitException(new RuntimeException("CUDA is not available")));
  }

  @Test
  @DisplayName("unrelated message → UNKNOWN")
  void unrelated() {
    assertEquals(
        FailureCause.UNKNOWN,
        FailureCause.classifyGpuInitException(new RuntimeException("some other error")));
  }

  @Test
  @DisplayName("null message → UNKNOWN")
  void nullMessage() {
    assertEquals(FailureCause.UNKNOWN, FailureCause.classifyGpuInitException(new RuntimeException()));
  }
}
