/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GPU utilization and VRAM snapshot for the /api/status endpoint.
 *
 * <p>Populated from {@code GpuCapabilitiesService.snapshot().nvml()} on the Head side.
 * Fields are nullable — older drivers or non-Windows systems may not support utilization queries.
 *
 * <p>335 §9: Wire NVML metrics into /api/status for agent observability during profiling runs.
 *
 * <p>Tempdoc 419 C3 V1 (2026-04-26): added 30-min trends of {@code gpu.utilization.percent}
 * and {@code gpu.memory.utilization.percent} (both curated/archived RRD metrics) so frontend
 * Health explanations can render sparklines next to GPU-related events. Empty arrays when
 * the RRD store hasn't accumulated data yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@SuppressWarnings("ArrayRecordComponent") // 419 C3 V1: intentional for API time-series payload
public record GpuStatusView(
    boolean available,
    Integer gpuUtilizationPercent,
    Integer memoryUtilizationPercent,
    Long totalVramBytes,
    Long usedVramBytes,
    Long freeVramBytes,
    String driverVersion,
    Integer deviceCount,
    double[] recentUtilizationPercent,
    double[] recentMemoryUtilizationPercent,
    // Tempdoc 587: the unified GPU resolver's CUDA-functional axis + provenance, so the surface
    // reads the merged effective view (with confidence) rather than the raw NVML snapshot.
    Boolean cudaFunctional,
    String source,
    String confidence) {

  public GpuStatusView {
    recentUtilizationPercent =
        recentUtilizationPercent == null ? new double[0] : recentUtilizationPercent;
    recentMemoryUtilizationPercent =
        recentMemoryUtilizationPercent == null ? new double[0] : recentMemoryUtilizationPercent;
  }

  /** Backward-compatible 8-arg ctor; defaults the 419 V1 trends + 587 provenance to empty/null. */
  public GpuStatusView(
      boolean available,
      Integer gpuUtilizationPercent,
      Integer memoryUtilizationPercent,
      Long totalVramBytes,
      Long usedVramBytes,
      Long freeVramBytes,
      String driverVersion,
      Integer deviceCount) {
    this(
        available,
        gpuUtilizationPercent,
        memoryUtilizationPercent,
        totalVramBytes,
        usedVramBytes,
        freeVramBytes,
        driverVersion,
        deviceCount,
        new double[0],
        new double[0],
        null,
        null,
        null);
  }

  /** Returns a view indicating no GPU data is available. */
  public static GpuStatusView unavailable() {
    return new GpuStatusView(false, null, null, null, null, null, null, null);
  }
}
