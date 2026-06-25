package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GpuAutoDetection")
class GpuAutoDetectionTest {

  /**
   * Probe contract (post tempdoc 374 sandbox round 2 / finding #4):
   * <ol>
   *   <li>Filesystem search at the dev-tree path. If the sentinel + core DLLs exist, return
   *       {@code gpu.enabled=true} AND {@code onnxruntime.native_path=<path>}.
   *   <li>Driver-API fallback via {@link GpuDriverApiProbe}. If a CUDA device is visible to the
   *       installed driver, return {@code gpu.enabled=true} but NOT {@code native_path} (ORT
   *       auto-extracts its own bundled CUDA EP DLLs).
   *   <li>Otherwise empty map.
   * </ol>
   *
   * <p>The "no filesystem DLLs" tests below assert only that {@code native_path} is absent —
   * they don't assert the result is empty, because the host running the test may have a working
   * CUDA driver (in which case the fallback correctly reports {@code gpu.enabled=true}).
   */
  @Test
  @DisplayName("probe returns empty map when repo root is null and no CUDA driver")
  void nullRepoRoot() {
    Map<String, String> result = GpuAutoDetection.probe(null);
    // native_path only set when filesystem search succeeds; null repo root short-circuits that.
    assertNull(result.get("justsearch.onnxruntime.native_path"));
    // gpu.enabled may be set IFF the host driver-API probe sees CUDA — accept both outcomes.
    if (!result.isEmpty()) {
      assertEquals("true", result.get("justsearch.gpu.enabled"));
      assertTrue(GpuDriverApiProbe.probe().available(),
          "non-empty result on null repo root only valid when driver-API probe sees CUDA");
    }
  }

  @Test
  @DisplayName("probe returns empty / driver-only when no filesystem CUDA DLLs present")
  void noCudaDlls(@TempDir Path tempDir) {
    Map<String, String> result = GpuAutoDetection.probe(tempDir);
    // No filesystem path → no native_path key under any outcome.
    assertNull(result.get("justsearch.onnxruntime.native_path"));
    if (!result.isEmpty()) {
      // Driver-API fallback succeeded; that's the expected new behavior on CUDA hosts.
      assertEquals("true", result.get("justsearch.gpu.enabled"));
      assertTrue(GpuDriverApiProbe.probe().available());
    }
  }

  @Test
  @EnabledOnOs(WINDOWS)
  @DisplayName("probe detects CUDA DLLs in conventional path")
  void detectsConventionalPath(@TempDir Path tempDir) throws IOException {
    // Create the conventional directory structure with sentinel + core DLLs.
    Path cudaDir = tempDir.resolve("tmp/ort-variant-test/cuda-12.4-v1.24.3");
    Files.createDirectories(cudaDir);
    Files.createFile(cudaDir.resolve("onnxruntime_providers_cuda.dll"));
    Files.createFile(cudaDir.resolve("onnxruntime_providers_shared.dll"));
    Files.createFile(cudaDir.resolve("cudart64_12.dll"));
    Files.createFile(cudaDir.resolve("cublas64_12.dll"));
    Files.createFile(cudaDir.resolve("cublasLt64_12.dll"));

    Map<String, String> result = GpuAutoDetection.probe(tempDir);
    assertEquals("true", result.get("justsearch.gpu.enabled"));
    assertEquals(cudaDir.toString(), result.get("justsearch.onnxruntime.native_path"));
  }

  @Test
  @EnabledOnOs(WINDOWS)
  @DisplayName("probe returns empty / driver-only when sentinel exists but core DLLs missing")
  void sentinelWithoutCoreDlls(@TempDir Path tempDir) throws IOException {
    // Only sentinel, no other DLLs.
    Path cudaDir = tempDir.resolve("tmp/ort-variant-test/cuda-12.4-v1.24.3");
    Files.createDirectories(cudaDir);
    Files.createFile(cudaDir.resolve("onnxruntime_providers_cuda.dll"));
    // Missing: onnxruntime_providers_shared.dll, cudart64_12.dll, cublas64_12.dll, cublasLt64_12.dll

    Map<String, String> result = GpuAutoDetection.probe(tempDir);
    // Filesystem path didn't pass strictness check → no native_path either way.
    assertNull(result.get("justsearch.onnxruntime.native_path"));
    if (!result.isEmpty()) {
      assertEquals("true", result.get("justsearch.gpu.enabled"));
      assertTrue(GpuDriverApiProbe.probe().available(),
          "non-empty result without core DLLs only valid when driver-API probe sees CUDA");
    }
  }
}
