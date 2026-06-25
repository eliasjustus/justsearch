package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HardwareProfileTest {

  @Test
  void cpuOnly_selectsCpuProfile() {
    var hw = HardwareProfile.cpuOnly();
    assertEquals(DownloadProfile.CPU, hw.downloadProfile());
  }

  @Test
  void cudaWithSufficientVram_selectsGpuFull() {
    var hw = HardwareProfile.gpuFull(12_000_000_000L);
    assertEquals(DownloadProfile.GPU_FULL, hw.downloadProfile());
  }

  @Test
  void cudaWithExactThresholdVram_selectsGpuFull() {
    var hw = new HardwareProfile(true, true, HardwareProfile.MINIMUM_VRAM_FOR_GGUF);
    assertEquals(DownloadProfile.GPU_FULL, hw.downloadProfile());
  }

  @Test
  void cudaWithInsufficientVram_selectsGpuLite() {
    var hw = new HardwareProfile(true, true, 6_000_000_000L);
    assertEquals(DownloadProfile.GPU_LITE, hw.downloadProfile());
  }

  @Test
  void cudaWithUnknownVram_selectsGpuLite() {
    // VramDetector returns -1 when nvidia-smi is unavailable
    var hw = new HardwareProfile(true, true, -1);
    assertEquals(DownloadProfile.GPU_LITE, hw.downloadProfile());
  }

  @Test
  void gpuDetectedButNoCuda_selectsCpuProfile() {
    // Sandbox case: GPU visible via vGPU but no CUDA runtime DLLs
    var hw = HardwareProfile.gpuDetectedNoCuda(12_000_000_000L);
    assertEquals(DownloadProfile.CPU, hw.downloadProfile());
  }

  @Test
  void gpuFullProfile_includesGgufAndCuda() {
    assertEquals(true, DownloadProfile.GPU_FULL.usesCuda());
    assertEquals(true, DownloadProfile.GPU_FULL.includesGguf());
  }

  @Test
  void gpuLiteProfile_hasCudaButNoGguf() {
    assertEquals(true, DownloadProfile.GPU_LITE.usesCuda());
    assertEquals(false, DownloadProfile.GPU_LITE.includesGguf());
  }

  @Test
  void cpuProfile_noCudaNoGguf() {
    assertEquals(false, DownloadProfile.CPU.usesCuda());
    assertEquals(false, DownloadProfile.CPU.includesGguf());
  }
}
