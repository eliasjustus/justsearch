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

    // Pre-create the files
    Path modelFile = tempDir.resolve("onnx/embed/model.onnx");
    Path tokenizerFile = tempDir.resolve("onnx/embed/tokenizer.json");
    Files.createDirectories(modelFile.getParent());
    Files.writeString(modelFile, "model data");
    Files.writeString(tokenizerFile, "tokenizer data");

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

    // Pre-create only the tokenizer (model is missing)
    Path tokenizerFile = tempDir.resolve("onnx/embed/tokenizer.json");
    Files.createDirectories(tokenizerFile.getParent());
    Files.writeString(tokenizerFile, "tokenizer data");

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(1, plan.downloads().size());
    assertTrue(plan.downloads().get(0).isModelVariant());
    assertEquals("onnx/embed/model.onnx", plan.downloads().get(0).targetPath());
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
