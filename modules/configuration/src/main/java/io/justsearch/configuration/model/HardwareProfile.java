/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * Snapshot of the machine's GPU/CUDA capabilities at a point in time.
 *
 * <p>Computed once at Head startup from {@code GpuAutoDetection} (CUDA DLL presence) and {@code
 * VramDetector} (nvidia-smi VRAM query), then propagated as a value to the install planner and
 * runtime variant selector.
 *
 * <p>The critical distinction is between {@code gpuDetected} (GPU hardware enumerated — true even
 * in Windows Sandbox with vGPU) and {@code cudaFunctional} (ORT CUDA EP DLLs present and
 * loadable). The sandbox case has {@code gpuDetected=true, cudaFunctional=false}.
 *
 * @param gpuDetected true if an NVIDIA GPU is enumerated (Win32_VideoController or nvidia-smi)
 * @param cudaFunctional true if ORT CUDA EP DLLs are present (onnxruntime_providers_cuda.dll +
 *     core CUDA DLLs). This is the gate for FP16 ONNX model selection.
 * @param vramBytes total GPU VRAM in bytes (from nvidia-smi), or -1 if unavailable. Used to
 *     determine whether GGUF models fit (threshold: {@code MINIMUM_VRAM_FOR_GGUF}).
 */
public record HardwareProfile(boolean gpuDetected, boolean cudaFunctional, long vramBytes) {

  /** Minimum VRAM required for GGUF chat model (Qwen3.5-9B Q4_K_M needs ~7.5 GB). */
  public static final long MINIMUM_VRAM_FOR_GGUF = 7_500_000_000L;

  /** Returns the download profile for this hardware. */
  public DownloadProfile downloadProfile() {
    if (cudaFunctional && vramBytes >= MINIMUM_VRAM_FOR_GGUF) {
      return DownloadProfile.GPU_FULL;
    }
    if (cudaFunctional) {
      return DownloadProfile.GPU_LITE;
    }
    return DownloadProfile.CPU;
  }

  /** CPU-only hardware — no GPU detected, no CUDA, no VRAM. */
  public static HardwareProfile cpuOnly() {
    return new HardwareProfile(false, false, -1);
  }

  /** GPU with sufficient VRAM for full experience. */
  public static HardwareProfile gpuFull(long vramBytes) {
    return new HardwareProfile(true, true, vramBytes);
  }

  /** GPU detected but CUDA not functional (e.g., sandbox with vGPU). */
  public static HardwareProfile gpuDetectedNoCuda(long vramBytes) {
    return new HardwareProfile(true, false, vramBytes);
  }
}
