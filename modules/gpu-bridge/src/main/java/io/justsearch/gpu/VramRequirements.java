/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.configuration.model.HardwareProfile;

/**
 * NVML-friendly VRAM threshold helpers. Tempdoc 374 alpha.27.
 *
 * <p>Pre-alpha.27 the same threshold logic lived inside {@link VramDetector}, gated on
 * {@code nvidia-smi} availability. On cuda12 sandbox hosts where NVML works fine but
 * nvidia-smi isn't on PATH, every caller silently fell into the "VRAM unknown" branch:
 * {@link io.justsearch.app.services.vdu.VduBatchProcessor} disabled VDU,
 * {@link io.justsearch.app.inference.LlamaServerOps} dropped KV-cache quantization flags,
 * etc. Each call site needed its own NVML migration; alpha.14 P1 and alpha.25 U14-C
 * each migrated one site and claimed the migration "complete".
 *
 * <p>This class extracts the threshold logic so any caller with a
 * {@link GpuCapabilities.Effective} snapshot (which is NVML-first) can read the same
 * answers VramDetector used to give for nvidia-smi outputs. Combined with the
 * {@code VramDetectorAccessTest} ArchUnit guard, this turns "migration complete" into
 * a CI-enforced invariant.
 */
public final class VramRequirements {

  /**
   * Comfortable VRAM threshold where KV cache quantization is optional (~10.7 GiB).
   * Mirrors {@code VramDetector.COMFORTABLE_VRAM}; held here as the single source of
   * truth so the threshold can't drift between the nvidia-smi and NVML paths.
   */
  static final long COMFORTABLE_VRAM_BYTES = 11_500_000_000L;

  /**
   * Returns true if the VRAM is at least {@link HardwareProfile#MINIMUM_VRAM_FOR_GGUF}.
   * Null/negative input → false (preserves the {@code VramDetector.meetsVduRequirements}
   * sentinel semantics for an unknown probe result).
   */
  public static boolean meetsGgufRequirements(Long totalVramBytes) {
    return totalVramBytes != null && totalVramBytes >= HardwareProfile.MINIMUM_VRAM_FOR_GGUF;
  }

  /**
   * Returns true if the GPU has comfortable headroom (12 GB+) where KV-cache quantization
   * is unnecessary for quality. Null/negative input → false.
   */
  public static boolean hasComfortableVram(Long totalVramBytes) {
    return totalVramBytes != null && totalVramBytes >= COMFORTABLE_VRAM_BYTES;
  }

  /**
   * Formats VRAM bytes as {@code "X.X GB"} or {@code "Unknown"} for null/negative input.
   * Mirrors {@code VramDetector.getVramDescription()} but omits the "(nvidia-smi not available)"
   * suffix — the alpha.25 wording correction (NVML can also fail to provide a value).
   */
  public static String describe(Long totalVramBytes) {
    if (totalVramBytes == null || totalVramBytes < 0) {
      return "Unknown";
    }
    return String.format("%.1f GB", totalVramBytes / (1024.0 * 1024.0 * 1024.0));
  }

  /**
   * Returns recommended llama-server launch flags based on detected VRAM, or {@code null}
   * if VRAM is below {@link HardwareProfile#MINIMUM_VRAM_FOR_GGUF}.
   *
   * <p>All supported cards get {@code -fa on} (flash-attention forced on; default would be
   * {@code auto}). 8GB cards additionally get KV cache quantization ({@code -ctk q4_0
   * -ctv q4_0}) to avoid OOM. 12GB+ cards skip quantization for better quality.
   *
   * <p>The leading {@code -c 4096 -ngl 99} are config-owned tokens that
   * {@link VramFlagsUtil#mergeRecommendedFlags} unconditionally skips — they're kept here
   * to preserve byte-for-byte parity with the legacy
   * {@code VramDetector.getRecommendedLlamaServerFlags} array shape (existing tests pin
   * it). Only {@code -fa on} and the optional KV-quantization tokens are actually merged
   * into the launch command.
   *
   * <p>Tempdoc 374 alpha.27 (post-ship probe of llama-server b8571): bare {@code -fa}
   * (no value) is rejected by llama-server with {@code "expected value for argument"} —
   * the syntax is {@code -fa [on|off|auto]} (default {@code auto}). The alpha.27 initial
   * implementation emitted bare {@code -fa} (mirroring the legacy VramDetector array,
   * which had been latently broken since llama.cpp changed the flag syntax — masked
   * because cuda12 sandbox hosts had nvidia-smi missing → VramDetector returned null →
   * no flags merged). Round 16 was the first round where the migration would actually
   * pass {@code -fa} to llama-server, surfacing the latent bug. Pre-launch fix: emit
   * {@code -fa on} explicitly.
   */
  public static String[] recommendedLlamaServerFlags(Long totalVramBytes) {
    if (!meetsGgufRequirements(totalVramBytes)) {
      return null;
    }
    if (hasComfortableVram(totalVramBytes)) {
      return new String[] {"-c", "4096", "-ngl", "99", "-fa", "on"};
    }
    return new String[] {"-c", "4096", "-ngl", "99", "-fa", "on", "-ctk", "q4_0", "-ctv", "q4_0"};
  }

  private VramRequirements() {}
}
