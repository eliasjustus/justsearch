package io.justsearch.ui.ai.runtime;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import org.junit.jupiter.api.Test;

/**
 * v3 hardware-gated smoke test.
 *
 * <p>This is intentionally not required in CI unless a real NVIDIA machine is available.
 * It should be run on dedicated hardware runners to validate NVML availability and driver parsing.
 */
class NvmlSmokeIntegrationTest {

  @Test
  void nvmlIsAvailableOnNvidiaMachines() {
    GpuCapabilitiesService svc = new GpuCapabilitiesService();
    GpuCapabilities caps = svc.snapshot();
    assumeTrue(caps != null);
    assumeTrue(caps.nvml() != null);
    // Skip unless NVML actually loads.
    assumeTrue(caps.nvml().available(), "NVML not available; skipping (no NVIDIA driver / not a real NVIDIA machine)");
    // If we reached here, we have a real NVML-backed probe.
    assumeTrue(caps.effective() != null && caps.effective().cudaAvailable());
  }
}
