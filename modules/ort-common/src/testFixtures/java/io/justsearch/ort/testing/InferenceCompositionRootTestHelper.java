package io.justsearch.ort.testing;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.ort.Composition;
import io.justsearch.ort.DevModeVariantProbe;
import io.justsearch.ort.GpuSessionConfig;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;

/**
 * Test-fixture helper for constructing a {@link SessionHandle} from a model directory without
 * requiring a full {@link io.justsearch.configuration.resolved.ResolvedConfig}. Replaces the
 * pre-§14.28 {@code OrtSessionAssembler.buildFallback} / {@code composeRerankFallback} /
 * {@code composeCitationFallback} call pattern that integration tests + benchmarks used.
 *
 * <p>Tempdoc 397 §14.28 U1: this helper is the one authorised <em>test-only</em> surface onto
 * {@link OrtSessionAssembler#buildManager} for integration tests + benchmarks. It synthesises a
 * minimal {@link Composition} (runtime = {@link RuntimePolicy#defaults()}, policy =
 * {@link ModelSessionPolicy#forFallback}) and routes through the same single production entry
 * point ({@code buildManager}) that {@code InferenceCompositionRoot.compose} uses.
 *
 * <p>Lives in {@code ort-common} testFixtures (not {@code worker-core}) to avoid a circular dep
 * on {@code reranker} integration tests (worker-core depends on reranker; reranker's
 * integrationTest cannot depend on worker-core). The helper does not reference encoder
 * concrete types — callers combine this helper with the encoder's own {@code buildAssembly}
 * static factory to produce an encoder instance.
 *
 * <p>Production code must <em>not</em> reach this helper — it lives in the {@code testFixtures}
 * source set, which is not on production runtime classpaths.
 */
public final class InferenceCompositionRootTestHelper {

  private InferenceCompositionRootTestHelper() {}

  /**
   * Builds a {@link SessionHandle} for an integration test that has a model directory on disk
   * but no {@code ResolvedConfig} in scope. Probes the filesystem via
   * {@link DevModeVariantProbe#probe}, derives a minimal policy via
   * {@link ModelSessionPolicy#forFallback}, and hands a {@link Composition} to
   * {@link OrtSessionAssembler#buildManager} — the single production entry point.
   *
   * @param consumerName log tag (e.g. {@code "embed-test"}, {@code "rerank-bench"})
   * @param modelDir directory containing {@code model.onnx} (+ optional manifest /
   *     {@code model_fp16.onnx})
   * @param gpu whether to request GPU session
   * @param gpuMemMb GPU arena cap in MB when {@code gpu == true}; ignored when CPU-only
   * @throws OrtException on session creation failure
   * @throws IllegalStateException if no loadable model is found under {@code modelDir} —
   *     caller should typically guard with {@code assumeTrue(Files.exists(...))} before calling
   */
  public static SessionHandle sessionFor(
      String consumerName, Path modelDir, boolean gpu, long gpuMemMb) throws OrtException {
    VariantSelection variant = DevModeVariantProbe.probe(modelDir, gpu);
    if (variant == null) {
      throw new IllegalStateException(
          "No loadable ONNX model under " + modelDir + " — check DevModeVariantProbe prerequisites");
    }

    GpuSessionConfig gpuSessionConfig =
        gpu ? new GpuSessionConfig(/* gpuDeviceId= */ 0, gpuMemMb * 1024L * 1024) : null;
    OptLevel cpuOptLevel =
        ModelSessionPolicyResolver.deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU);
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            gpuSessionConfig,
            cpuOptLevel,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ gpu,
            /* gpuRetryIntervalMs= */ 60_000L);
    Composition comp =
        new Composition(
            RuntimePolicy.defaults(),
            policy,
            new ModelArtifacts(variant.modelFile(), variant.modelFile()));
    return OrtSessionAssembler.buildManager(consumerName, comp, () -> gpu);
  }

  /**
   * Convenience variant for CPU-only tests — the typical pattern for reranker / citation /
   * embedding integration tests.
   */
  public static SessionHandle cpuSessionFor(String consumerName, Path modelDir)
      throws OrtException {
    return sessionFor(consumerName, modelDir, /* gpu= */ false, /* gpuMemMb= */ 0L);
  }

  /**
   * Synthesises a CPU-only {@link VariantSelection} for tests that want to build a handle from
   * a single known ONNX file path (not a directory).
   */
  public static VariantSelection cpuVariant(Path modelFile, ModelPrecision precision) {
    return VariantSelection.optimal(modelFile, precision, ExecutionProvider.CPU);
  }
}
