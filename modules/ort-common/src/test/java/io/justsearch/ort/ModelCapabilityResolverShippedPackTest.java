/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resolver's behaviour on the layouts the first-party model packs ACTUALLY install — tempdoc
 * 807 item 2.
 *
 * <p>Sandbox round 13 installed the first-party packs on a clean machine and the Worker log
 * answered with six {@code "model capability degraded"} lines for {@code embedding} and {@code
 * ner}, while all four encoders ran correctly on CUDA. The app was calling its own shipped,
 * correctly-installed models degraded.
 *
 * <p>The layouts below are the shipped ones, not the repo's: {@code model-registry.v2.json} ships
 * the embedding pack a {@code model_manifest.json} that predates the tempdoc-710 capability
 * authoring pass (no {@code capabilities} block, and {@code cpu} pointing at the FP16 file), and
 * ships the NER pack no manifest at all. The repo's own {@code models/onnx/*} manifests declare
 * everything; they are simply not what a user installs.
 *
 * <p>What this test pins is the half that was a defect in THIS repo rather than in the released
 * assets: precision was resolved — and warned about, off a filename-substring guess — for two roles
 * that never read it. Nothing in production reads {@link ModelCapabilities#cpuPrecision()} or
 * {@link ModelCapabilities#gpuPrecision()}; {@code DevModeVariantProbe} (the reader
 * {@code CapabilityRequirements} named) loads its own manifest and runs its own guess
 * ({@code DevModeVariantProbe:59,75-83}), and the real precision authority at runtime is
 * {@code VariantSelection.precision()} from the install contract. The remaining warnings are
 * genuine and are deliberately still asserted here: they name facts these roles DO read, which the
 * shipped packs really do not declare.
 */
@DisplayName("ModelCapabilityResolver — first-party packs as actually shipped")
class ModelCapabilityResolverShippedPackTest {

  /** Verbatim content of the released {@code embed-model_manifest.json} asset (135 bytes). */
  private static final String SHIPPED_EMBEDDING_MANIFEST =
      """
      {
        "cpu": "model_fp16.onnx",
        "gpu": "model_fp16.onnx",
        "tokenizer": "tokenizer.json",
        "pooling_config": "pooling_config.json"
      }
      """;

  private static List<String> precisionWarnings(ModelCapabilities caps) {
    return caps.warnings().stream().filter(w -> w.contains("precision")).toList();
  }

  @Test
  @DisplayName("embedding pack: no precision warning, because no consumer reads the precision")
  void embeddingPackAsShippedDoesNotWarnAboutPrecision(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("model_manifest.json"), SHIPPED_EMBEDDING_MANIFEST);
    Files.writeString(modelDir.resolve("pooling_config.json"), "{\"pooling_mode\":\"cls\"}");
    Files.writeString(modelDir.resolve("tokenizer.json"), "{}");

    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    ModelCapabilities caps =
        ModelCapabilityResolver.resolve(
            "embedding", modelDir, manifest, CapabilityRequirements.EMBEDDING, false);

    assertTrue(
        precisionWarnings(caps).isEmpty(),
        "the embedding role never reads cpu/gpu precision, so guessing it from a filename and"
            + " calling the result degraded is noise: "
            + precisionWarnings(caps));
    assertNull(caps.cpuPrecision(), "unrequested fact must stay at its undeclared sentinel");
    assertNull(caps.gpuPrecision(), "unrequested fact must stay at its undeclared sentinel");
  }

  @Test
  @DisplayName("embedding pack: the facts the role DOES read are still reported as undeclared")
  void embeddingPackAsShippedStillNamesTheRealGaps(@TempDir Path modelDir) throws IOException {
    Files.writeString(modelDir.resolve("model_manifest.json"), SHIPPED_EMBEDDING_MANIFEST);
    Files.writeString(modelDir.resolve("pooling_config.json"), "{\"pooling_mode\":\"cls\"}");
    Files.writeString(modelDir.resolve("tokenizer.json"), "{}");

    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    ModelCapabilities caps =
        ModelCapabilityResolver.resolve(
            "embedding", modelDir, manifest, CapabilityRequirements.EMBEDDING, false);

    // The shipped pack carries no config.json, no sentence_bert_config.json and no
    // prefix_config.json, so context length and both prefixes really are undeclared. Silencing
    // these would hide the release-asset gap this finding exists to name.
    assertTrue(
        caps.warnings().stream().anyMatch(w -> w.contains("context length")),
        "shipped embedding pack declares no context length; that gap must stay visible: "
            + caps.warnings());
    assertTrue(
        caps.warnings().stream().anyMatch(w -> w.contains("prefix")),
        "shipped embedding pack declares no query/document prefix; that gap must stay visible: "
            + caps.warnings());
    // Pooling is the one fact the shipped pack does declare, via its legacy sidecar.
    assertTrue(
        caps.warnings().stream().noneMatch(w -> w.contains("pooling")),
        "pooling_config.json is shipped and declares cls: " + caps.warnings());
  }

  @Test
  @DisplayName("ner pack: a correctly-installed pack is not reported degraded at all")
  void nerPackAsShippedIsNotDegraded(@TempDir Path modelDir) throws IOException {
    // The NER pack ships config.json + tokenizer.json and NO model_manifest.json, so the resolver
    // sees ModelManifest.loadOrDefault's defaults (cpu=model.onnx, gpu=model_fp16.onnx). Both
    // facts the role reads — context length and the label taxonomy — come from config.json.
    Files.writeString(
        modelDir.resolve("config.json"),
        """
        { "max_position_embeddings": 512, "id2label": { "0": "O", "1": "B-PER" } }
        """);
    Files.writeString(modelDir.resolve("tokenizer.json"), "{}");

    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    ModelCapabilities caps =
        ModelCapabilityResolver.resolve(
            "ner", modelDir, manifest, CapabilityRequirements.NER, false);

    // Pre-fix this warned that cpu precision was undeclared for "model.onnx" and guessed FP32 —
    // a guess that is also WRONG (the registry declares the NER CPU variant INT8) about a file a
    // CUDA install never downloads.
    assertTrue(
        caps.warnings().isEmpty(),
        "a correctly-installed first-party NER pack must not be reported degraded: "
            + caps.warnings());
    assertEquals(512, caps.trainedContextLength());
    assertEquals(2, caps.labelMapping().size());
  }
}
