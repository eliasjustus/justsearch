/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * Configuration for GPU (CUDA) ORT session creation.
 *
 * <p>Captures only the per-encoder parameters that differ between encoders — device index
 * + arena cap. Shared session options (arena strategy, CUDA graphs, memory patterns, inter-op
 * threads) live on {@link RuntimePolicy} and are applied by {@link SessionOptionsApplier} per
 * tempdoc 397 §14.24 FA.
 *
 * @param gpuDeviceId CUDA device index (typically 0)
 * @param gpuMemLimitBytes GPU memory arena limit in bytes
 */
public record GpuSessionConfig(int gpuDeviceId, long gpuMemLimitBytes) {}
