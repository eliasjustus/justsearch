/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.ort.OrtCudaStatus;
import java.util.function.Supplier;

/**
 * Bundle of GPU diagnostic suppliers wired during deferred model initialization.
 *
 * <p>Passed to {@link WorkerAppServices#wireGpuDiagnostics(GpuDiagnosticSuppliers)} to set all
 * GPU-related status suppliers on the ingest service in a single call. The {@code
 * nerOrtCudaStatus}, {@code citationOrtCudaStatus}, and {@code bgeM3OrtCudaStatus} components
 * (tempdoc 422) let the {@code /api/inference/encoders} explainer resolve runtime accelerator
 * state for all 6 encoders.
 */
public record GpuDiagnosticSuppliers(
    Supplier<OrtCudaStatus> spladeOrtCudaStatus,
    Supplier<String> spladeModelPath,
    Supplier<OrtCudaStatus> embedOrtCudaStatus,
    Supplier<String> embedBackend,
    Supplier<Integer> embedGpuLayers,
    Supplier<OrtCudaStatus> rerankerOrtCudaStatus,
    Supplier<OrtCudaStatus> nerOrtCudaStatus,
    Supplier<OrtCudaStatus> citationOrtCudaStatus,
    Supplier<OrtCudaStatus> bgeM3OrtCudaStatus) {}
