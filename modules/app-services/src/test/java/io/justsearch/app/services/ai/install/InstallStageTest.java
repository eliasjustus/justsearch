/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.InstallPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tier → stage mapping and the partition that turns a plan into ordered stages.
 *
 * <p>This is the decision that makes search work after ~1.3 GB instead of after ~7 GB, so its
 * properties are pinned here rather than left to a ~7 GB end-to-end run: what CORE contains (and
 * that it contains the GPU runtime, without which the one REQUIRED encoder silently runs on CPU),
 * what order the stages run in, that a stage never reorders the plan, and where an untagged package
 * goes.
 */
final class InstallStageTest {

  private static InstallPlan.PlannedDownload dl(String packageId, String targetPath, long size) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example.invalid/" + targetPath, targetPath, "sha", size, true);
  }

  /** The registry's real tier assignments, so the mapping is exercised against production data. */
  private static final Map<String, CapabilityTier> REGISTRY_TIERS =
      Map.of(
          "embedding", CapabilityTier.RETRIEVAL_CORE,
          "cuda-runtime", CapabilityTier.RUNTIME,
          "splade", CapabilityTier.RETRIEVAL_ENRICHMENT,
          "reranker", CapabilityTier.RETRIEVAL_ENRICHMENT,
          "ner", CapabilityTier.RETRIEVAL_ENRICHMENT,
          "citation-scorer", CapabilityTier.RETRIEVAL_ENRICHMENT,
          "chat", CapabilityTier.LLM);

  @Test
  @DisplayName("CORE carries the retrieval core AND the GPU runtime, so the core encoder is not left on CPU")
  void coreCarriesRetrievalCoreAndRuntime() {
    assertEquals(
        java.util.Set.of(CapabilityTier.RETRIEVAL_CORE, CapabilityTier.RUNTIME),
        InstallStage.CORE.tiers());
    assertEquals(
        java.util.Set.of(CapabilityTier.RETRIEVAL_ENRICHMENT), InstallStage.ENRICHMENT.tiers());
    assertEquals(java.util.Set.of(CapabilityTier.LLM), InstallStage.CHAT.tiers());
  }

  @Test
  @DisplayName("every capability tier resolves to exactly one stage")
  void everyTierIsMappedExactlyOnce() {
    for (CapabilityTier tier : CapabilityTier.values()) {
      long owners =
          java.util.Arrays.stream(InstallStage.values())
              .filter(stage -> stage.tiers().contains(tier))
              .count();
      assertEquals(1, owners, tier + " must belong to exactly one stage");
    }
  }

  @Test
  @DisplayName("the stages run core, then enrichment, then chat")
  void stagesRunInCapabilityOrder() {
    assertEquals(
        List.of("core", "enrichment", "chat"),
        java.util.Arrays.stream(InstallStage.values()).map(InstallStage::id).toList());
    assertSame(InstallStage.CORE, InstallStage.first());
    assertSame(InstallStage.CHAT, InstallStage.last());
  }

  @Test
  @DisplayName("each stage takes its own op-lease class")
  void eachStageHasItsOwnLeaseClass() {
    assertEquals("ai.model-install.core", InstallStage.CORE.leaseOpClass());
    assertEquals("ai.model-install.enrichment", InstallStage.ENRICHMENT.leaseOpClass());
    assertEquals("ai.model-install.chat", InstallStage.CHAT.leaseOpClass());
  }

  @Test
  @DisplayName("a plan is cut into the three stages, in plan order inside each")
  void partitionsAPlanKeepingPlanOrder() {
    List<InstallPlan.PlannedDownload> downloads =
        List.of(
            dl("chat", "gguf/chat.gguf", 5_000),
            dl("embedding", "onnx/embed/model.onnx", 1_000),
            dl("splade", "splade/model.onnx", 300),
            dl("cuda-runtime", "cuda12/runtime.zip", 700),
            dl("embedding", "onnx/embed/tokenizer.json", 10),
            dl("reranker", "onnx/reranker/model.onnx", 200));

    List<InstallStage.Slice> slices =
        InstallStage.partition(downloads, REGISTRY_TIERS::get);

    assertEquals(3, slices.size(), "every stage is reported, empty ones included");
    assertEquals(
        List.of(InstallStage.CORE, InstallStage.ENRICHMENT, InstallStage.CHAT),
        slices.stream().map(InstallStage.Slice::stage).toList());

    assertEquals(
        List.of("onnx/embed/model.onnx", "cuda12/runtime.zip", "onnx/embed/tokenizer.json"),
        slices.get(0).downloads().stream().map(InstallPlan.PlannedDownload::targetPath).toList(),
        "core keeps the plan's own order for the files it takes");
    assertEquals(1_710L, slices.get(0).bytes());
    assertEquals(java.util.Set.of("embedding", "cuda-runtime"), slices.get(0).packageIds());

    assertEquals(
        List.of("splade/model.onnx", "onnx/reranker/model.onnx"),
        slices.get(1).downloads().stream().map(InstallPlan.PlannedDownload::targetPath).toList());
    assertEquals(500L, slices.get(1).bytes());

    assertEquals(
        List.of("gguf/chat.gguf"),
        slices.get(2).downloads().stream().map(InstallPlan.PlannedDownload::targetPath).toList());
    assertEquals(5_000L, slices.get(2).bytes());

    assertEquals(
        downloads.stream().mapToLong(InstallPlan.PlannedDownload::sizeBytes).sum(),
        slices.stream().mapToLong(InstallStage.Slice::bytes).sum(),
        "the cut loses nothing: the stages' bytes sum to the plan's");
  }

  @Test
  @DisplayName("a stage with nothing planned is still reported, and reports itself empty")
  void emptyStagesAreReportedNotOmitted() {
    List<InstallStage.Slice> slices =
        InstallStage.partition(List.of(dl("embedding", "onnx/embed/model.onnx", 1)), REGISTRY_TIERS::get);

    assertFalse(slices.get(0).isEmpty());
    assertTrue(slices.get(1).isEmpty(), "enrichment planned nothing");
    assertTrue(slices.get(2).isEmpty(), "chat planned nothing");
    assertEquals(0L, slices.get(1).bytes());
  }

  @Test
  @DisplayName("an untagged package is deferred to the last stage, never allowed to delay first search")
  void untaggedPackagesGoLast() {
    assertSame(InstallStage.last(), InstallStage.forTier(null));

    List<InstallStage.Slice> slices =
        InstallStage.partition(
            List.of(dl("mystery", "mystery/blob.bin", 42), dl("embedding", "onnx/embed/model.onnx", 1)),
            REGISTRY_TIERS::get);

    assertEquals(
        List.of("onnx/embed/model.onnx"),
        slices.get(0).downloads().stream().map(InstallPlan.PlannedDownload::targetPath).toList(),
        "core takes only what the registry declared as core");
    assertEquals(
        List.of("mystery/blob.bin"),
        slices.get(2).downloads().stream().map(InstallPlan.PlannedDownload::targetPath).toList());
  }

  @Test
  @DisplayName("partitioning nothing yields three empty stages rather than no stages")
  void partitionOfNothingIsStillTheWholeSequence() {
    List<InstallStage.Slice> slices = InstallStage.partition(null, REGISTRY_TIERS::get);
    assertEquals(3, slices.size());
    assertTrue(slices.stream().allMatch(InstallStage.Slice::isEmpty));
  }
}
