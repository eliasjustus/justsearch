package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.gpu.GpuCapabilities.Confidence;
import io.justsearch.gpu.GpuCapabilities.Cuda;
import io.justsearch.gpu.GpuCapabilities.NvidiaSmi;
import io.justsearch.gpu.GpuCapabilities.Nvml;
import org.junit.jupiter.api.Test;

/**
 * Pins the source-precedence law of {@link GpuCapabilitiesService#mergeEffective} — NVML-first
 * (HIGH), nvidia-smi fallback (LOW), else none/UNKNOWN — plus the orthogonal CUDA-functional axis
 * (single-probe, rides the bundle regardless of which VRAM source wins; tempdoc 587). This is the
 * live merge consumed by the GPU status surfaces; spawning {@code nvidia-smi} / loading {@code
 * nvml.dll} is not needed because the precedence is pure over the inputs.
 */
final class GpuCapabilitiesServiceTest {

  private static final String SENTINEL = "NVML probe unavailable.";
  private static final Cuda CUDA_FN = new Cuda(true, "cuda-driver-api", Confidence.HIGH);

  /** NVML snapshot reporting a usable GPU. */
  private static Nvml nvmlWithGpu() {
    return new Nvml(
        true, "C:\\Windows\\System32\\nvml.dll", "nvml.dll", null,
        "591.59", 591, 59, 1, 12_878_610_432L, 8_000_000_000L, 4_878_610_432L, 17, 9);
  }

  /** NVML snapshot for an unavailable probe (the sentinel {@link GpuCapabilitiesService} builds). */
  private static Nvml nvmlUnavailable() {
    return new Nvml(false, null, null, SENTINEL, null, null, null, null, null, null, null, null, null);
  }

  /** nvidia-smi snapshot reporting a usable GPU. */
  private static NvidiaSmi smiWithGpu() {
    return new NvidiaSmi(true, null, "576.88", 576, 88, 8_589_934_592L, 5_000_000_000L, "8.0 GB");
  }

  private static NvidiaSmi smiAbsent() {
    return new NvidiaSmi(false, null, null, null, null, null, null, "Unknown (nvidia-smi not available)");
  }

  // ===== NVML-first (HIGH) =====

  @Test
  void nvmlWithGpu_winsWithHighConfidence() {
    Nvml nvml = nvmlWithGpu();
    NvidiaSmi smi = smiWithGpu();

    GpuCapabilities caps = GpuCapabilitiesService.mergeEffective(nvml, smi, CUDA_FN);
    GpuCapabilities.Effective eff = caps.effective();

    assertTrue(eff.cudaAvailable());
    assertEquals("nvml", eff.source());
    assertEquals(Confidence.HIGH, eff.confidence());
    assertEquals("591.59", eff.driverVersion());
    assertEquals(591, eff.driverVersionMajor());
    assertEquals(59, eff.driverVersionMinor());
    assertEquals(1, eff.deviceCount());
    assertEquals(12_878_610_432L, eff.totalVramBytes());
    assertEquals(8_000_000_000L, eff.freeVramBytes());
    assertEquals(4_878_610_432L, eff.usedVramBytes());
    // CUDA axis rides the bundle untouched.
    assertSame(CUDA_FN, eff.cuda());
    // The raw probe snapshots are passed through untouched.
    assertSame(nvml, caps.nvml());
    assertSame(smi, caps.nvidiaSmi());
  }

  @Test
  void nvmlDeviceCountDrivesPrecedence_notTotalVram() {
    // NVML reports a device but no memory reading → still NVML-source (HIGH), total stays null.
    Nvml nvml =
        new Nvml(true, null, "nvml.dll", null, "591.59", 591, 59, 1, null, null, null, null, null);

    GpuCapabilities.Effective eff =
        GpuCapabilitiesService.mergeEffective(nvml, smiAbsent(), CUDA_FN).effective();

    assertEquals("nvml", eff.source());
    assertEquals(Confidence.HIGH, eff.confidence());
    assertTrue(eff.cudaAvailable());
    assertNull(eff.totalVramBytes());
  }

  // ===== nvidia-smi fallback (LOW) =====

  @Test
  void nvmlUnavailable_fallsBackToSmiWithLowConfidence() {
    NvidiaSmi smi = smiWithGpu();

    GpuCapabilities.Effective eff =
        GpuCapabilitiesService.mergeEffective(nvmlUnavailable(), smi, CUDA_FN).effective();

    assertTrue(eff.cudaAvailable());
    assertEquals("nvidia-smi", eff.source());
    assertEquals(Confidence.LOW, eff.confidence());
    assertEquals("576.88", eff.driverVersion());
    assertEquals(576, eff.driverVersionMajor());
    assertEquals(8_589_934_592L, eff.totalVramBytes());
    assertEquals(5_000_000_000L, eff.freeVramBytes());
    // nvidia-smi cannot report device count or used VRAM.
    assertNull(eff.deviceCount());
    assertNull(eff.usedVramBytes());
  }

  @Test
  void nvmlPresentButZeroDevices_fallsBackToSmi() {
    Nvml nvmlZero =
        new Nvml(true, null, "nvml.dll", null, "591.59", 591, 59, 0, null, null, null, null, null);

    GpuCapabilities.Effective eff =
        GpuCapabilitiesService.mergeEffective(nvmlZero, smiWithGpu(), CUDA_FN).effective();

    assertEquals("nvidia-smi", eff.source());
    assertEquals(Confidence.LOW, eff.confidence());
  }

  // ===== none / UNKNOWN =====

  @Test
  void neitherSource_yieldsNoneUnknown_butKeepsNvmlDiagnostics() {
    // NVML failed to find a device but still parsed a driver version (e.g. nvmlDeviceGetCount==0
    // path that nonetheless read the driver string) — that diagnostic must survive into Effective.
    Nvml nvmlDriverOnly =
        new Nvml(false, "C:\\Windows\\System32\\nvml.dll", "nvml.dll", "no devices",
            "591.59", 591, 59, 0, null, null, null, null, null);

    GpuCapabilities.Effective eff =
        GpuCapabilitiesService.mergeEffective(nvmlDriverOnly, smiAbsent(), CUDA_FN).effective();

    assertFalse(eff.cudaAvailable());
    assertEquals("none", eff.source());
    assertEquals(Confidence.UNKNOWN, eff.confidence());
    // Carries the NVML driver + device diagnostics even though no source is effective.
    assertEquals("591.59", eff.driverVersion());
    assertEquals(591, eff.driverVersionMajor());
    assertEquals(0, eff.deviceCount());
    assertNull(eff.totalVramBytes());
  }

  @Test
  void smiAvailableButNoTotalVram_isNotAGpu() {
    // smi.available() can be true on a driver-only reading (no memory.total) — that must not be
    // mistaken for a usable GPU.
    NvidiaSmi smiDriverOnly =
        new NvidiaSmi(true, null, "576.88", 576, 88, null, null, "Unknown");

    GpuCapabilities.Effective eff =
        GpuCapabilitiesService.mergeEffective(nvmlUnavailable(), smiDriverOnly, CUDA_FN).effective();

    assertFalse(eff.cudaAvailable());
    assertEquals("none", eff.source());
  }

  @Test
  void smiTotalZero_isNotAGpu() {
    NvidiaSmi smiZero = new NvidiaSmi(true, null, "576.88", 576, 88, 0L, 0L, "0.0 GB");

    GpuCapabilities.Effective eff =
        GpuCapabilitiesService.mergeEffective(nvmlUnavailable(), smiZero, CUDA_FN).effective();

    assertFalse(eff.cudaAvailable());
    assertEquals("none", eff.source());
  }

  // ===== CUDA axis (orthogonal, single-probe) =====

  @Test
  void cudaAxisFoldsThrough_regardlessOfVramSource() {
    // The CUDA-functional reading is orthogonal to the VRAM precedence: it rides the bundle
    // identically whether NVML wins, nvidia-smi wins, or no source is effective.
    assertSame(CUDA_FN, GpuCapabilitiesService.mergeEffective(nvmlWithGpu(), smiAbsent(), CUDA_FN).effective().cuda());
    assertSame(CUDA_FN, GpuCapabilitiesService.mergeEffective(nvmlUnavailable(), smiWithGpu(), CUDA_FN).effective().cuda());
    assertSame(CUDA_FN, GpuCapabilitiesService.mergeEffective(nvmlUnavailable(), smiAbsent(), CUDA_FN).effective().cuda());
  }

  @Test
  void cudaFunctionalTrue_isCarried() {
    Cuda cuda = GpuCapabilitiesService.mergeEffective(nvmlWithGpu(), smiAbsent(), CUDA_FN).effective().cuda();
    assertEquals(Boolean.TRUE, cuda.functional());
    assertEquals("cuda-driver-api", cuda.source());
    assertEquals(Confidence.HIGH, cuda.confidence());
  }

  @Test
  void nullCuda_defaultsToUnknownSentinel() {
    // mergeEffective tolerates a null CUDA input → the unknown sentinel, never NPE / null cuda().
    Cuda cuda = GpuCapabilitiesService.mergeEffective(nvmlWithGpu(), smiAbsent(), null).effective().cuda();
    assertNull(cuda.functional());
    assertEquals("none", cuda.source());
    assertEquals(Confidence.UNKNOWN, cuda.confidence());
  }
}
