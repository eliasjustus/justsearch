/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.preflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.services.ai.preflight.AiPreflightService.AiPreflightResult;
import io.justsearch.app.services.ai.preflight.AiPreflightService.PackageStatus;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallIntent;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.Necessity;
import io.justsearch.configuration.model.SupportingFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 772 Q3: the derived {@code blockingIncomplete} severity signal on {@link PackageStatus}.
 * Exercises {@link AiPreflightService#computePreflight} directly (staging files under a temp
 * modelsDir, choosing hardware + intent) so the wanted/hardware-permitted/complete interaction is
 * deterministic without a live GPU probe or the classpath registry.
 */
final class AiPreflightServiceTest {

  @TempDir Path modelsDir;
  @TempDir Path aiHome;

  private static final AiRuntimeStatusResponse NO_RUNTIME =
      new AiRuntimeStatusResponse(null, List.of(), null, List.of());

  /** (1) A package unwanted by the current intent stays blockingIncomplete=false when incomplete. */
  @Test
  void unwantedByIntent_incomplete_isNotBlocking() {
    // chat is LLM-tier; MCP_LITE excludes the LLM tier. gpuFull hardware means it is excluded purely
    // by intent, not by the VRAM gate. Files are NOT staged → incomplete.
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(chatPackage()));

    AiPreflightResult result =
        AiPreflightService.computePreflight(
            registry, modelsDir, aiHome, HardwareProfile.gpuFull(12_000_000_000L),
            InstallIntent.MCP_LITE, Set.of(), NO_RUNTIME);

    PackageStatus chat = find(result, "chat");
    assertFalse(chat.complete(), "chat is incomplete (no files staged)");
    assertFalse(chat.blockingIncomplete(), "unwanted-by-intent must not be blocking");
  }

  /** (2) A hardware-gated-off package (requiresCuda on non-CUDA hw) stays non-blocking when incomplete. */
  @Test
  void hardwareGatedOff_incomplete_isNotBlocking() {
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(cudaOnlyPackage()));

    AiPreflightResult result =
        AiPreflightService.computePreflight(
            registry, modelsDir, aiHome, HardwareProfile.cpuOnly(),
            InstallIntent.FULL_DESKTOP, Set.of(), NO_RUNTIME);

    PackageStatus cudaOnly = find(result, "cuda-only");
    assertFalse(cudaOnly.complete(), "cuda-only is incomplete (no files staged)");
    assertFalse(cudaOnly.blockingIncomplete(), "hardware-gated-off must not be blocking");
  }

  /** (3) A wanted + hardware-permitted package that is incomplete IS blocking — the new signal. */
  @Test
  void wantedPermitted_incomplete_isBlocking() {
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embeddingPackage()));

    AiPreflightResult result =
        AiPreflightService.computePreflight(
            registry, modelsDir, aiHome, HardwareProfile.cpuOnly(),
            InstallIntent.FULL_DESKTOP, Set.of(), NO_RUNTIME);

    PackageStatus embedding = find(result, "embedding");
    assertFalse(embedding.complete(), "embedding is incomplete (no files staged)");
    assertTrue(embedding.blockingIncomplete(), "wanted+permitted+incomplete must be blocking");
  }

  /** (4) A wanted + permitted + complete package is not blocking — completeness overrides. */
  @Test
  void wantedPermitted_complete_isNotBlocking() throws Exception {
    // Stage the CPU variant + the supporting file so the package reads complete.
    stage(modelsDir, "onnx/embed/model.onnx");
    stage(modelsDir, "onnx/embed/tokenizer.json");
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embeddingPackage()));

    AiPreflightResult result =
        AiPreflightService.computePreflight(
            registry, modelsDir, aiHome, HardwareProfile.cpuOnly(),
            InstallIntent.FULL_DESKTOP, Set.of(), NO_RUNTIME);

    PackageStatus embedding = find(result, "embedding");
    assertTrue(embedding.complete(), "all files staged → complete");
    assertFalse(embedding.blockingIncomplete(), "a complete package is never blocking");
  }

  /**
   * (5) Tempdoc 840 Phase 2 — a component the user DECLINED is not a blocking gap. The severity
   * signal reuses {@code InstallPlanner.isIncludedByPlan}, which mirrors the planner's skip loop; if
   * that mirror loses the decline gate, a package the install deliberately never downloads starts
   * reporting as a blocking incompleteness here.
   */
  @Test
  void declined_incomplete_isNotBlocking() {
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(chatPackage()));

    AiPreflightResult result =
        AiPreflightService.computePreflight(
            registry, modelsDir, aiHome, HardwareProfile.gpuFull(12_000_000_000L),
            InstallIntent.FULL_DESKTOP, Set.of("chat"), NO_RUNTIME);

    PackageStatus chat = find(result, "chat");
    assertFalse(chat.complete(), "chat is incomplete (no files staged)");
    assertFalse(
        chat.blockingIncomplete(),
        "a declined component is absent by choice — reporting it as a blocking gap is nagging");
  }

  /** (6) …but declining a REQUIRED package changes nothing: it is still a blocking gap. */
  @Test
  void declinedButNotDeclinable_incomplete_isStillBlocking() {
    ModelRegistry registry = new ModelRegistry(2, "test", List.of(embeddingPackage()));

    AiPreflightResult result =
        AiPreflightService.computePreflight(
            registry, modelsDir, aiHome, HardwareProfile.cpuOnly(),
            InstallIntent.FULL_DESKTOP, Set.of("embedding"), NO_RUNTIME);

    PackageStatus embedding = find(result, "embedding");
    assertTrue(
        embedding.blockingIncomplete(),
        "search does not work without it, so a stale decline must not downgrade the severity");
  }

  // --- fixtures -------------------------------------------------------------

  private static PackageStatus find(AiPreflightResult result, String id) {
    return result.packages().stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no package status for " + id));
  }

  private static void stage(Path baseDir, String relPath) throws Exception {
    Path f = baseDir.resolve(relPath);
    Files.createDirectories(f.getParent());
    Files.write(f, new byte[] {1, 2, 3});
  }

  private static ModelPackage embeddingPackage() {
    return new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000, "https://example.com/fp32")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE, false,
        Necessity.REQUIRED, List.of());
  }

  private static ModelPackage cudaOnlyPackage() {
    return new ModelPackage(
        "cuda-only", "CUDA-only enrichment", "Needs CUDA", "onnx/cuda-only",
        List.of(
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 1_000, "https://example.com/fp16")),
        List.of(),
        0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT, true,
        Necessity.IMPROVES_RESULTS, List.of("cuda-runtime"));
  }

  private static ModelPackage chatPackage() {
    return new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000, "https://example.com/gguf")),
        List.of(),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null, null, null, CapabilityTier.LLM, false,
        Necessity.ADDS_FEATURE, List.of());
  }
}
