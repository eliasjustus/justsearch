/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ort.GpuDriverApiProbe;

/**
 * The one composition seam where every GPU probe converges into a single {@link GpuCapabilities}
 * snapshot: the NVML/nvidia-smi VRAM+device merge ({@link GpuCapabilitiesService}, gpu-bridge) plus
 * the CUDA-functional driver-API probe ({@link GpuDriverApiProbe}, ort-common). It is the single
 * authority for GPU facts (tempdoc 587) — consumers read this resolver's snapshot rather than
 * calling the raw probes directly (enforced by {@code GpuProbeAccessTest}).
 *
 * <p>{@code app-services} is the lowest layer that depends on BOTH gpu-bridge and ort-common, so
 * the composition lives here, keeping gpu-bridge dependency-free of ort-common: the CUDA reading is
 * probed here and folded into the merge via {@link GpuCapabilitiesService#snapshot(GpuCapabilities.Cuda)}.
 */
public final class GpuCapabilityResolver {

  private final GpuCapabilitiesService gpu;

  public GpuCapabilityResolver() {
    this(new GpuCapabilitiesService());
  }

  /**
   * Wraps an existing {@link GpuCapabilitiesService} so callers that already hold one (e.g. the
   * status handler's injected supplier) reuse it and only pay the extra CUDA probe on the paths
   * that need it.
   */
  public GpuCapabilityResolver(GpuCapabilitiesService gpu) {
    this.gpu = gpu == null ? new GpuCapabilitiesService() : gpu;
  }

  /** Probes CUDA, then folds it into the VRAM/device merge → one unified GPU snapshot. */
  public GpuCapabilities snapshot() {
    return gpu.snapshot(probeCuda());
  }

  /** Invalidate the nvidia-smi fallback cache (delegates to the wrapped service). */
  public void invalidateNvidiaSmiCache() {
    gpu.invalidateNvidiaSmiCache();
  }

  /**
   * Reads the CUDA-functional axis. {@link GpuDriverApiProbe#probe()} never throws and always
   * returns a definitive answer, so a successful probe is HIGH confidence whether CUDA is
   * functional or not; only a catastrophic failure of the probe mechanism falls back to the
   * unknown sentinel.
   */
  private static GpuCapabilities.Cuda probeCuda() {
    try {
      GpuDriverApiProbe.Result r = GpuDriverApiProbe.probe();
      return new GpuCapabilities.Cuda(
          r.available(), "cuda-driver-api", GpuCapabilities.Confidence.HIGH);
    } catch (Throwable t) {
      return GpuCapabilities.Cuda.unknown();
    }
  }
}
