/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.ort.testing.ModelDirTestResolver;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Live-model-gated regression test (tempdoc 710 Wave 2 Move 1): the real {@code
 * models/onnx/gte-multilingual-base/model_manifest.json} authored in this wave must resolve to
 * exactly (CLS, 8192, 768, empty document/query prefixes) — the facts verified against the real
 * HF repo in the tempdoc's S-C.R research pass.
 *
 * <p>CPU precision is FP32, not FP16: {@code model-registry.v2.json} ships {@code model.onnx}
 * (this package's declared {@code cpu} variant) as the FP32/CPU build and {@code
 * model_fp16.onnx} as the separate FP16/CUDA build — there is no FP16 CPU variant. The manifest
 * briefly declared {@code capabilities.cpu_precision: "fp16"} while pointing {@code cpu} at the
 * FP32 file (a self-contradiction introduced when commit ce26575 corrected the {@code cpu} file
 * selection but not the paired precision field); corrected so the declared precision matches the
 * file actually loaded on the CPU path.
 *
 * <p>Gated on the manifest + tokenizer files only (not the multi-hundred-MB {@code
 * model_fp16.onnx} weight file — every fact this test asserts is manifest-declared, so the ORT
 * boot-probe/precision-sanity-check paths never fire; they no-op when the weight file is absent).
 * Uses {@link ModelDirTestResolver} (tempdoc 710 Move 6) so this test resolves the model directory
 * the same way every other asset-gated test in the suite does.
 */
@DisplayName("ModelCapabilityResolver — live gte-multilingual-base manifest")
class ModelCapabilityResolverGteLiveTest {

  @Test
  @DisplayName("gte-multilingual-base resolves to (CLS, 8192, 768, empty prefixes)")
  void gteResolvesToDeclaredCapabilities() {
    ModelDirTestResolver.Discovery discovery =
        ModelDirTestResolver.discover(
            "models/onnx/gte-multilingual-base", null, "model_manifest.json", "tokenizer.json");
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    Path modelDir = discovery.modelDir();

    // Precision is asserted below, and since tempdoc 807 item 2 it is in no role preset (no
    // production consumer reads it), so this test requests it explicitly alongside the embedding
    // role's own facts.
    ModelManifest manifest = ModelManifest.load(modelDir);
    ModelCapabilities caps =
        ModelCapabilityResolver.resolve(
            "embedding",
            modelDir,
            manifest,
            new CapabilityRequirements(
                EnumSet.of(
                    CapabilityRequirements.Fact.POOLING,
                    CapabilityRequirements.Fact.CONTEXT_LENGTH,
                    CapabilityRequirements.Fact.DIMENSION,
                    CapabilityRequirements.Fact.PRECISION,
                    CapabilityRequirements.Fact.PREFIXES)),
            false);

    assertEquals(ModelCapabilities.PoolingMode.CLS, caps.poolingMode());
    assertEquals(8192, caps.trainedContextLength());
    assertEquals(768, caps.embeddingDimension());
    assertEquals("", caps.documentPrefix());
    assertEquals("", caps.queryPrefix());
    assertEquals(ModelPrecision.FP32, caps.cpuPrecision());
    assertEquals(ModelPrecision.FP16, caps.gpuPrecision());
  }
}
