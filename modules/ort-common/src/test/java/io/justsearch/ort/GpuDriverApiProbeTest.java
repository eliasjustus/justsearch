package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for {@link GpuDriverApiProbe}. The probe outcome depends on whether the test host has
 * a CUDA-capable NVIDIA driver, so we can only assert structural invariants:
 *
 * <ul>
 *   <li>{@code probe()} returns a non-null {@link GpuDriverApiProbe.Result} instead of throwing
 *   <li>{@code reason} is always populated (never null/blank — useful for diagnostics)
 *   <li>If {@code available} is true, {@code deviceCount} is at least 1 (consistency)
 *   <li>If {@code available} is false, {@code deviceCount} is 0 (consistency)
 * </ul>
 *
 * <p>Tempdoc 374 sandbox round 2 finding #4. Real end-to-end validation happens in the sandbox
 * (alpha.9 onward).
 */
final class GpuDriverApiProbeTest {

  @Test
  void probe_returns_result_without_throwing() {
    GpuDriverApiProbe.Result result = GpuDriverApiProbe.probe();
    assertNotNull(result);
    assertNotNull(result.reason());
    assertTrue(!result.reason().isBlank(), "reason should be populated for diagnostics");
    if (result.available()) {
      assertTrue(result.deviceCount() >= 1, "available=true implies deviceCount >= 1");
    } else {
      assertTrue(result.deviceCount() == 0, "available=false implies deviceCount == 0");
    }
  }
}
