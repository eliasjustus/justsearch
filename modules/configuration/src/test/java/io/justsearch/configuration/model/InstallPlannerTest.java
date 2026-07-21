package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPlannerTest {

  @TempDir Path tempDir;

  @Test
  void gpuFullProfile_downloadsEverything() {
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.GPU_FULL, plan.profile());
    assertTrue(plan.skipped().isEmpty());
    // Should include: FP16 embedding variant + tokenizer + GGUF chat + GGUF mmproj
    assertEquals(4, plan.downloads().size());
    assertTrue(plan.totalBytes() > 0);
  }

  @Test
  void cpuProfile_skipsGgufAndDownloadsFp32() {
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.CPU, plan.profile());
    assertEquals(1, plan.skipped().size());
    assertEquals("chat", plan.skipped().get(0).packageId());

    // Should include: FP32 embedding variant + tokenizer (no chat)
    assertEquals(2, plan.downloads().size());
    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("chat")));
    assertTrue(plan.downloads().stream()
        .filter(d -> d.isModelVariant())
        .allMatch(d -> d.targetPath().contains("model.onnx")));
  }

  @Test
  void gpuLiteProfile_skipsChatButDownloadsFp16() {
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = new HardwareProfile(true, true, 6_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.GPU_LITE, plan.profile());
    assertEquals(1, plan.skipped().size());
    assertEquals("chat", plan.skipped().get(0).packageId());

    // FP16 embedding variant + tokenizer
    assertEquals(2, plan.downloads().size());
    assertTrue(plan.downloads().stream()
        .filter(d -> d.isModelVariant())
        .allMatch(d -> d.targetPath().contains("fp16")));
  }

  @Test
  void alreadyInstalledFiles_skippedInPlan() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // Pre-create the files at their registry-declared sizes (isAlreadyInstalled now checks size).
    Path modelFile = tempDir.resolve("onnx/embed/model.onnx");
    Path tokenizerFile = tempDir.resolve("onnx/embed/tokenizer.json");
    Files.createDirectories(modelFile.getParent());
    Files.write(modelFile, new byte[1_000_000]);
    Files.write(tokenizerFile, new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertTrue(plan.downloads().isEmpty());
    assertEquals(1, plan.alreadyInstalled().size());
    assertEquals("embedding", plan.alreadyInstalled().get(0));
    assertEquals(0, plan.totalBytes());
  }

  @Test
  void deltaComputation_onlyDownloadsMissing() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // Pre-create only the tokenizer at its declared size (model is missing).
    Path tokenizerFile = tempDir.resolve("onnx/embed/tokenizer.json");
    Files.createDirectories(tokenizerFile.getParent());
    Files.write(tokenizerFile, new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(1, plan.downloads().size());
    assertTrue(plan.downloads().get(0).isModelVariant());
    assertEquals("onnx/embed/model.onnx", plan.downloads().get(0).targetPath());
  }

  @Test
  void mcpLiteIntent_skipsLlmTier_evenOnCapableHardware() {
    ModelRegistry registry = registryWithTiers();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    // Full Desktop (default overload) includes the LLM tier on capable hardware.
    InstallPlan full = InstallPlanner.plan(registry, hw, tempDir);
    assertTrue(full.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));

    // MCP Lite excludes the LLM tier by intent, independent of hardware.
    InstallPlan lite = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);
    assertTrue(lite.downloads().stream().noneMatch(d -> d.packageId().equals("chat")));
    assertTrue(
        lite.skipped().stream()
            .anyMatch(s -> s.packageId().equals("chat") && s.reason().contains("mcp-lite")));
    // Retrieval-core stays wanted by every intent.
    assertTrue(lite.downloads().stream().anyMatch(d -> d.packageId().equals("embedding")));
  }

  @Test
  void untaggedPackage_alwaysWanted_soPreTierRegistriesAreUnchanged() {
    // registryWithEmbeddingAndChat() leaves tiers null → MCP Lite must NOT exclude them; only
    // hardware gates, and GPU_FULL keeps the chat package.
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);
    InstallPlan lite = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);
    assertTrue(lite.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));
  }

  @Test
  void gpuLiteProfile_includesCudaRuntime_notGatedOnGgufVramFloor() {
    // Regression for the miswired cuda-runtime gate: a CUDA-functional GPU with < 7.5 GB VRAM
    // (GPU_LITE) must still download the runtime-tier CUDA DLLs, because it downloads the FP16
    // CUDA ONNX variants that need them. Previously the runtime package reused the GGUF VRAM
    // floor and was wrongly skipped for GPU_LITE.
    ModelRegistry registry = registryWithRuntime();
    HardwareProfile hw = new HardwareProfile(true, true, 6_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.GPU_LITE, plan.profile());
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime")),
        "GPU_LITE must download the CUDA runtime");
    assertTrue(plan.skipped().stream().noneMatch(s -> s.packageId().equals("cuda-runtime")));
  }

  @Test
  void cpuProfile_skipsCudaRuntime() {
    ModelRegistry registry = registryWithRuntime();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.CPU, plan.profile());
    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("cuda-runtime")));
    assertTrue(plan.skipped().stream().anyMatch(s -> s.packageId().equals("cuda-runtime")));
  }

  @Test
  void wrongSizeFile_isNotTreatedAsAlreadyInstalled() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // A truncated/wrong file at the right path (size != declared) must be re-planned, not trusted.
    Path modelFile = tempDir.resolve("onnx/embed/model.onnx");
    Files.createDirectories(modelFile.getParent());
    Files.write(modelFile, new byte[42]); // declared size is 1_000_000
    Files.write(tempDir.resolve("onnx/embed/tokenizer.json"), new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertTrue(
        plan.downloads().stream()
            .anyMatch(d -> d.isModelVariant() && d.targetPath().equals("onnx/embed/model.onnx")),
        "wrong-size model must be scheduled for re-download");
  }

  @Test
  void mcpLiteIntent_onGpu_includesCudaRuntime_butStillSkipsChat() {
    // Option A (owner decision): the CUDA runtime is hardware-support wanted by every intent, because
    // mcp-lite still downloads the CUDA FP16 retrieval variants that need it. So mcp-lite on a GPU
    // must download cuda-runtime — while STILL skipping the LLM chat tier. Reverting the
    // wants(RUNTIME) change makes the intent gate skip cuda-runtime for mcp-lite → this goes red.
    ModelRegistry registry = registryWithRuntimeAndChat();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);

    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime")),
        "mcp-lite on GPU must download the CUDA runtime for GPU retrieval");
    assertTrue(
        plan.downloads().stream().noneMatch(d -> d.packageId().equals("chat")),
        "mcp-lite must still skip the LLM chat tier");
    assertTrue(
        plan.skipped().stream()
            .anyMatch(s -> s.packageId().equals("chat") && s.reason().contains("mcp-lite")));
  }

  @Test
  void mcpLiteIntent_onCpu_skipsCudaRuntime() {
    // No CUDA → no runtime, for every intent. The planner's usesCuda() gate is the sole authority.
    ModelRegistry registry = registryWithRuntimeAndChat();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);

    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("cuda-runtime")));
    assertTrue(plan.skipped().stream().anyMatch(s -> s.packageId().equals("cuda-runtime")));
  }

  @Test
  void hardwareIndependentRuntimePackage_isNeverSkipped_onCudaHardware() {
    // Tempdoc 772 Q3: a RUNTIME-tier package with requiresCuda=false must be representable and never
    // skipped for hardware reasons. On CUDA hardware it downloads like any wanted package.
    ModelRegistry registry = registryWithHardwareIndependentRuntime();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertTrue(plan.skipped().stream().noneMatch(s -> s.packageId().equals("runtime-cpu-support")));
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("runtime-cpu-support")),
        "a requiresCuda=false RUNTIME package must download on CUDA hardware");
  }

  @Test
  void hardwareIndependentRuntimePackage_isNeverSkipped_onNonCudaHardware() {
    // The point of the tempdoc 772 Q3 change: a RUNTIME-tier package with requiresCuda=false is
    // NEVER skipped for hardware reasons — unlike cuda-runtime (requiresCuda=true), which the CPU
    // profile skips (see cpuProfile_skipsCudaRuntime). Before this change RUNTIME tier itself gated
    // on CUDA, so a hardware-independent runtime package could not be expressed at all.
    ModelRegistry registry = registryWithHardwareIndependentRuntime();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.CPU, plan.profile());
    assertTrue(plan.skipped().stream().noneMatch(s -> s.packageId().equals("runtime-cpu-support")));
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("runtime-cpu-support")),
        "a requiresCuda=false RUNTIME package must download even without CUDA");
  }

  /**
   * A registry carrying a hypothetical hardware-independent RUNTIME-tier package (tempdoc 772 Q3):
   * RUNTIME tier but {@code requiresCuda=false}, so the planner's CUDA gate never skips it. Does not
   * correspond to any real production package.
   */
  private ModelRegistry registryWithHardwareIndependentRuntime() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage runtimeCpuSupport = new ModelPackage(
        "runtime-cpu-support", "CPU runtime libraries", "Always-required runtime payload", "cpu-rt",
        List.of(),
        List.of(
            new SupportingFile(
                "runtime.zip", "GGGG", 50_000_000L, "https://example.com/runtime.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, false);
    return new ModelRegistry(2, "test registry", List.of(embedding, runtimeCpuSupport));
  }

  private ModelRegistry registryWithRuntimeAndChat() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage cudaRuntime = new ModelPackage(
        "cuda-runtime", "GPU runtime libraries", "CUDA DLLs", "cuda12",
        List.of(),
        List.of(
            new SupportingFile(
                "cuda.zip", "FFFF", 200_000_000L, "https://example.com/cuda.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, true);
    ModelPackage chat = new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null, null, null, CapabilityTier.LLM);
    return new ModelRegistry(2, "test registry", List.of(embedding, cudaRuntime, chat));
  }

  private ModelRegistry registryWithRuntime() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage cudaRuntime = new ModelPackage(
        "cuda-runtime", "GPU runtime libraries", "CUDA DLLs", "cuda12",
        List.of(),
        List.of(
            new SupportingFile(
                "cuda.zip", "FFFF", 200_000_000L, "https://example.com/cuda.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, true);
    return new ModelRegistry(2, "test registry", List.of(embedding, cudaRuntime));
  }

  private ModelRegistry registryWithTiers() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage chat = new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(
            new SupportingFile("mmproj.gguf", "EEEE", 1_000_000_000L, "https://example.com/mmproj")),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null, null, null, CapabilityTier.LLM);
    return new ModelRegistry(2, "test registry", List.of(embedding, chat));
  }

  private ModelRegistry registryWithEmbeddingAndChat() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(
            new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null);

    ModelPackage chat = new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(
            new SupportingFile("mmproj.gguf", "EEEE", 1_000_000_000L, "https://example.com/mmproj")),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null);

    return new ModelRegistry(2, "test registry", List.of(embedding, chat));
  }

  private ModelRegistry registryWithEmbeddingOnly() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(
            new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null);

    return new ModelRegistry(2, "test registry", List.of(embedding));
  }
}
