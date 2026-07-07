/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GPU diagnostics for the {@code gpu} object on {@code GET /api/inference/status}.
 *
 * <p>Tempdoc 663 §L/Stage 4 — this is the Brain's OWN GPU probe (NVML-first, nvidia-smi fallback,
 * via {@code GpuCapabilitiesService}), distinct from the Worker's GPU diagnostics
 * ({@link WorkerOperationalView#gpu()} / {@link GpuDiagnosticsView}) — do not conflate the two.
 *
 * <p>Stability: stable (API contract) — field set unchanged from the prior hand-built {@code Map}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InferenceGpuView(
    boolean cudaAvailable,
    Long totalVramBytes,
    String vramDescription,
    String vramDetectionSource,
    boolean nvidiaSmiAvailable,
    boolean nvmlAvailable,
    Long nvmlTotalVramBytes,
    String nvmlDriverVersion,
    String cudaVersion) {}
