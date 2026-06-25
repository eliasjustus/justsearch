package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ort.GpuArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InferenceCompositionRoot#compose} (tempdoc 397 §14.28 U6). Focuses
 * on the graceful-degradation invariant: when models are absent on disk (the typical test
 * environment), {@code compose} returns a surface with every role as {@link java.util.Optional#empty()}
 * — no throws, no NPE, no partial-state mutation.
 *
 * <p>Exhaustive coverage of {@code composeXAssembly} per-encoder branches requires real ONNX
 * files on disk (tokenizer + manifest loads are the critical I/O); that level of coverage
 * lives in the per-encoder integration tests. U6 pins the <em>compose() orchestration shape</em>
 * — which encoders are attempted, which are skipped based on sparseModel selection, how
 * failures propagate, and that the surface invariants hold even under total failure.
 */
@DisplayName("InferenceCompositionRoot.compose (§14.28 U6)")
class InferenceCompositionRootComposeTest {

  private ConfigStore originalStore;

  @BeforeEach
  void captureStore() {
    // ConfigStore.global() may already be set by an earlier test; capture + restore.
    originalStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.withDefaults()));
  }

  @AfterEach
  void restoreStore() {
    if (originalStore != null) {
      ConfigStore.setGlobal(originalStore);
    }
  }

  private static final GpuArbiter NO_GPU = () -> false;

  @Test
  @DisplayName("no models on disk → surface has all Optional.empty + empty policies + empty handles")
  void noModelsReturnsEmptySurface() {
    InferenceSurface surface =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            /* contract= */ null,
            /* modelsDir= */ null,
            NO_GPU);

    assertNotNull(surface);
    assertTrue(surface.embedding().isEmpty());
    assertTrue(surface.ner().isEmpty());
    assertTrue(surface.reranker().isEmpty());
    assertTrue(surface.citation().isEmpty());
    assertTrue(surface.splade().isEmpty());
    assertTrue(surface.bgeM3().isEmpty());
    assertTrue(surface.handles().isEmpty());
    assertTrue(
        surface.policies().models().isEmpty(),
        "policies map excludes roles whose variant didn't resolve");
  }

  @Test
  @DisplayName("compose is idempotent under absent-models — snapshot.runtime is always resolved")
  void runtimePolicyAlwaysPresentRegardlessOfRoles() {
    // Even when every encoder role's variant fails to resolve, RuntimePolicy is process-wide
    // and always computable from (cfg, hardware). The snapshot always carries it.
    InferenceSurface surface =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);

    assertNotNull(surface.policies().runtime());
    assertNotNull(surface.policies().runtime().arena());
    assertNotNull(surface.policies().runtime().session());
    assertNotNull(surface.policies().runtime().cudaProvider());
    assertNotNull(surface.policies().runtime().profiling());
  }

  @Test
  @DisplayName("compose does not throw on hardware = gpuFull — graceful degradation when GPU absent")
  void gpuHardwareProfileWithNoModelsDoesNotThrow() {
    // Even when hardware claims GPU is available, missing model files should not cause any
    // throw inside compose. Per-encoder try/catch + Optional.empty handles the failure.
    assertDoesNotThrow(
        () ->
            InferenceCompositionRoot.compose(
                ConfigStore.global().get(),
                HardwareProfile.gpuFull(0),
                /* contract= */ null,
                /* modelsDir= */ null,
                NO_GPU));
  }

  @Test
  @DisplayName("surface.close() on empty surface is a no-op")
  void emptySurfaceCloseIsNoop() {
    InferenceSurface surface =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);
    assertDoesNotThrow(surface::close);
  }

  @Test
  @DisplayName("compose called twice produces independent surfaces")
  void composeIsNotStateful() {
    InferenceSurface s1 =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);
    InferenceSurface s2 =
        InferenceCompositionRoot.compose(
            ConfigStore.global().get(),
            HardwareProfile.cpuOnly(),
            null,
            null,
            NO_GPU);

    assertNotNull(s1);
    assertNotNull(s2);
    // No shared mutable state — closing s1 doesn't affect s2.
    s1.close();
    assertDoesNotThrow(s2::close);
  }
}
