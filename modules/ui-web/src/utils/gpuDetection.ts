// SPDX-License-Identifier: Apache-2.0
/**
 * Shared GPU detection logic.
 *
 * Consumed by both useCapabilities (Gate system) and useAiCapabilities
 * (AI action gating) to ensure a single source of truth for "does this
 * system have a supported GPU?"
 */

/** Minimal shape needed for GPU detection — subset of both InferenceStatus and InferenceState. */
interface GpuDetectable {
  tier?: string | null | undefined;
  gpu?: { cudaAvailable?: boolean | undefined } | null | undefined;
}

/**
 * Returns true when the system appears to have a supported GPU.
 *
 * Checks CUDA availability flag and known GPU tier strings.
 * If the tier list changes (e.g., a new tier is added), update here —
 * both useCapabilities and useAiCapabilities inherit the change.
 */
export function hasGpuSupport(status: GpuDetectable | null | undefined): boolean {
  const tier = status?.tier ?? null;
  const cudaAvailable = status?.gpu?.cudaAvailable === true;
  return (
    cudaAvailable ||
    tier === 'gpu_12gb_plus' ||
    tier === 'gpu_8gb' ||
    tier === 'gpu_lt_8gb'
  );
}
