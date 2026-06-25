/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * Download profile tier, determined by hardware capabilities.
 *
 * <p>Each profile defines which model variants to download:
 *
 * <ul>
 *   <li>{@code GPU_FULL} — CUDA functional + sufficient VRAM. Downloads FP16 ONNX + GGUF. ~8.3 GB.
 *   <li>{@code GPU_LITE} — CUDA functional + insufficient VRAM for GGUF. Downloads FP16 ONNX only.
 *       ~2.1 GB.
 *   <li>{@code CPU} — No CUDA. Downloads FP32/INT8 ONNX only. ~2.8 GB.
 * </ul>
 */
public enum DownloadProfile {

  /** CUDA functional, VRAM >= 7.5 GB. Full experience: FP16 ONNX + GGUF chat. */
  GPU_FULL(true, true),

  /** CUDA functional, VRAM < 7.5 GB. GPU-accelerated search, no chat. */
  GPU_LITE(true, false),

  /** No CUDA. CPU-only enriched search, no chat. */
  CPU(false, false);

  private final boolean cuda;
  private final boolean gguf;

  DownloadProfile(boolean cuda, boolean gguf) {
    this.cuda = cuda;
    this.gguf = gguf;
  }

  /** True if this profile should download CUDA/FP16 ONNX variants (vs FP32/INT8). */
  public boolean usesCuda() {
    return cuda;
  }

  /** True if this profile should download GGUF models (chat + mmproj). */
  public boolean includesGguf() {
    return gguf;
  }
}
