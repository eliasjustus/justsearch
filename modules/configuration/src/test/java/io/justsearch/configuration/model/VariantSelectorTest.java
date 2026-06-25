package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariantSelectorTest {

  @TempDir Path tempDir;

  @Test
  void optimalSelection_fp32OnCpu() throws Exception {
    Path modelFile = createFile("onnx/embed/model.onnx");
    InstallContract contract = contractWith("embedding", "model.onnx", ModelPrecision.FP32,
        ExecutionProvider.CPU, "onnx/embed");

    VariantSelection sel = VariantSelector.select("embedding", contract,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertFalse(sel.degraded());
    assertEquals(ModelPrecision.FP32, sel.precision());
    assertEquals(ExecutionProvider.CPU, sel.executionProvider());
  }

  @Test
  void optimalSelection_fp16OnCuda() throws Exception {
    Path modelFile = createFile("onnx/embed/model_fp16.onnx");
    InstallContract contract = contractWith("embedding", "model_fp16.onnx", ModelPrecision.FP16,
        ExecutionProvider.CUDA, "onnx/embed");

    VariantSelection sel = VariantSelector.select("embedding", contract,
        HardwareProfile.gpuFull(12_000_000_000L), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertFalse(sel.degraded());
    assertEquals(ModelPrecision.FP16, sel.precision());
    assertEquals(ExecutionProvider.CUDA, sel.executionProvider());
  }

  @Test
  void degradedSelection_fp16OnCpu() throws Exception {
    // The pathological case (I1): FP16 installed but running on CPU
    createFile("onnx/embed/model_fp16.onnx");
    InstallContract contract = contractWith("embedding", "model_fp16.onnx", ModelPrecision.FP16,
        ExecutionProvider.CUDA, "onnx/embed");

    VariantSelection sel = VariantSelector.select("embedding", contract,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertTrue(sel.degraded());
    assertEquals(ExecutionProvider.CPU, sel.executionProvider());
    assertTrue(sel.degradationReason().contains("CPU"));
  }

  @Test
  void degradedSelection_fp32OnCuda() throws Exception {
    // FP32 installed but CUDA is available — works but suboptimal
    createFile("onnx/reranker/model.onnx");
    InstallContract contract = contractWith("reranker", "model.onnx", ModelPrecision.FP32,
        ExecutionProvider.CPU, "onnx/reranker");

    VariantSelection sel = VariantSelector.select("reranker", contract,
        HardwareProfile.gpuFull(12_000_000_000L), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertTrue(sel.degraded());
    assertEquals(ExecutionProvider.CUDA, sel.executionProvider());
  }

  @Test
  void skippedModel_returnsNull() {
    InstallContract contract = new InstallContract(2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of("chat", InstallContract.InstalledModel.skipped("chat", "No CUDA")));

    VariantSelection sel = VariantSelector.select("chat", contract,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true);

    assertNull(sel);
  }

  @Test
  void missingModelFile_returnsDegradedWithRepairMessage() throws Exception {
    // Contract says model exists but file is missing from disk
    InstallContract contract = contractWith("embedding", "model.onnx", ModelPrecision.FP32,
        ExecutionProvider.CPU, "onnx/embed");

    VariantSelection sel = VariantSelector.select("embedding", contract,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertTrue(sel.degraded());
    assertTrue(sel.degradationReason().contains("missing from disk"));
    assertTrue(sel.degradationReason().contains("re-run Install AI"));
  }

  @Test
  void unknownPackageId_returnsNull() {
    InstallContract contract = new InstallContract(2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU, Map.of());

    assertNull(VariantSelector.select("nonexistent", contract,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true));
  }

  @Test
  void nullContract_returnsNull() {
    assertNull(VariantSelector.select("embedding", null,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true));
  }

  // =========================================================================
  // Tempdoc 374 alpha.18 Bug I: gpuAllowed parameter
  // =========================================================================

  /**
   * Pre-alpha.18 default: a CPU-installed variant on a CUDA host gets promoted to
   * CUDA EP (degraded but functional). This test pins that behaviour for GPU-capable
   * roles like reranker and embed.
   */
  @Test
  void cpuInstalled_cudaHost_gpuAllowedTrue_promotesToCuda() throws Exception {
    createFile("onnx/reranker/model.onnx");
    InstallContract contract = contractWith("reranker", "model.onnx", ModelPrecision.FP32,
        ExecutionProvider.CPU, "onnx/reranker");

    VariantSelection sel = VariantSelector.select("reranker", contract,
        HardwareProfile.gpuFull(12_000_000_000L), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertTrue(sel.degraded(), "CPU-installed on CUDA host with gpuAllowed=true → degraded(CUDA)");
    assertEquals(ExecutionProvider.CUDA, sel.executionProvider());
  }

  /**
   * Tempdoc 374 alpha.18 Bug I regression guard: when {@code gpuAllowed=false}
   * (CPU-only role like citation-scorer), a CPU-installed variant on a CUDA host
   * stays on CPU instead of being promoted. Pre-alpha.18 the promotion was
   * unconditional, citation init then failed-loud at {@code assertCitationIsCpuOnly}
   * and the citation feature was dark on every GPU install.
   */
  @Test
  void cpuInstalled_cudaHost_gpuAllowedFalse_staysOnCpu() throws Exception {
    createFile("onnx/citation-scorer/model.onnx");
    InstallContract contract = contractWith("citation-scorer", "model.onnx", ModelPrecision.INT8,
        ExecutionProvider.CPU, "onnx/citation-scorer");

    VariantSelection sel = VariantSelector.select("citation-scorer", contract,
        HardwareProfile.gpuFull(12_000_000_000L), tempDir, /* gpuAllowed= */ false);

    assertNotNull(sel);
    assertFalse(sel.degraded(),
        "CPU-installed on CUDA host with gpuAllowed=false → optimal(CPU), not degraded(CUDA)."
            + " Pre-alpha.18 this returned degraded(CUDA) and citation init failed-loud"
            + " at assertCitationIsCpuOnly (374 alpha.18 Bug I).");
    assertEquals(ExecutionProvider.CPU, sel.executionProvider(),
        "Citation must stay on CPU when gpuAllowed=false (CPU-only by design).");
  }

  /**
   * Tempdoc 374 alpha.21 Bug P: chat (LLAMA_SERVER variant) must not fall through to
   * the catch-all "Unexpected variant/hardware combination" branch. LLAMA_SERVER
   * doesn't go through ORT EP selection — chat is handled by llama-server with its
   * own -ngl flag. Variant is always optimal regardless of CUDA availability.
   *
   * <p>Round-11 evidence: chat actually worked at 41.73 tok/s on cuda12 with -ngl 99
   * but the status display reported `degraded:true, "Unexpected variant/hardware
   * combination"` because the pre-alpha.21 catch-all fired for LLAMA_SERVER targetEP.
   */
  @Test
  void chatLlamaServer_cudaHost_returnsOptimal_alpha21BugP() throws Exception {
    createFile("Qwen_Qwen3.5-9B-Q4_K_M.gguf");
    InstallContract contract = contractWith(
        "chat", "Qwen_Qwen3.5-9B-Q4_K_M.gguf", ModelPrecision.GGUF,
        ExecutionProvider.LLAMA_SERVER, "");

    VariantSelection sel = VariantSelector.select("chat", contract,
        HardwareProfile.gpuFull(12_000_000_000L), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertFalse(sel.degraded(),
        "LLAMA_SERVER variant on CUDA host must be optimal — chat doesn't go through ORT"
            + " EP selection. Pre-alpha.21 the catch-all 'Unexpected variant/hardware"
            + " combination' branch fired (374 alpha.21 Bug P).");
    assertEquals(ExecutionProvider.LLAMA_SERVER, sel.executionProvider(),
        "chat keeps its LLAMA_SERVER EP regardless of host hardware.");
  }

  /** LLAMA_SERVER variant on CPU host also returns optimal — same reasoning. */
  @Test
  void chatLlamaServer_cpuHost_returnsOptimal_alpha21BugP() throws Exception {
    createFile("Qwen_Qwen3.5-9B-Q4_K_M.gguf");
    InstallContract contract = contractWith(
        "chat", "Qwen_Qwen3.5-9B-Q4_K_M.gguf", ModelPrecision.GGUF,
        ExecutionProvider.LLAMA_SERVER, "");

    VariantSelection sel = VariantSelector.select("chat", contract,
        HardwareProfile.cpuOnly(), tempDir, /* gpuAllowed= */ true);

    assertNotNull(sel);
    assertFalse(sel.degraded());
    assertEquals(ExecutionProvider.LLAMA_SERVER, sel.executionProvider());
  }

  private Path createFile(String relativePath) throws Exception {
    Path file = tempDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "test content");
    return file;
  }

  private InstallContract contractWith(String packageId, String filename, ModelPrecision precision,
      ExecutionProvider ep, String targetDir) {
    var model = new InstallContract.InstalledModel(
        packageId, filename, precision, ep, targetDir, "HASH", List.of(filename), false, null);
    return new InstallContract(2, System.currentTimeMillis(), HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of(packageId, model));
  }
}
