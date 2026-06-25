package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ort.Composition;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Composition-root tests. Post-§14.21 (remediation plan R1/R2): the assembler returns
 * {@link SessionHandle}, not {@code OrtSessionManager}; assertions that used to peek at
 * manager internals (`peekCpuSession`, `isGpuConfigured`) have been replaced with behavioural
 * round-trips that prove the policy field flowed end-to-end.
 */
@DisplayName("InferenceCompositionRoot (Stage 4d / R1+R2)")
class InferenceCompositionRootTest {

  private ConfigStore previousStore;

  @BeforeEach
  void captureState() {
    previousStore = ConfigStore.globalOrNull();
  }

  @AfterEach
  void restoreState() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  @DisplayName(
      "composeCitation throws when variant is CUDA-capable (tempdoc 397 §14.7 post-review Issue 2)")
  void composeCitationFailsLoudOnCudaVariant() {
    var cudaVariant =
        VariantSelection.optimal(
            Path.of("nonexistent/citation.onnx"), ModelPrecision.FP16, ExecutionProvider.CUDA);
    var hardware = new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024);
    var cfg = TestResolvedConfigHelper.withDefaults();

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> InferenceCompositionRoot.composeCitation(cfg, hardware, cudaVariant));

    assertTrue(ex.getMessage().contains("CUDA"));
    assertTrue(ex.getMessage().contains("CPU-only"));
  }

  @Test
  @DisplayName("assembler accepts non-default gpuRetryIntervalMs (§14.19)")
  void assemblerAcceptsCustomRetryInterval() throws Exception {
    // The field is now live configuration; any positive value is legal. Behaviour check: build
    // succeeds without throwing (old fail-loud divergence assertion deleted in §14.19).
    Composition comp = compositionWithRetryInterval(30_000L);

    try (SessionHandle handle =
        OrtSessionAssembler.buildManager("bridge-test", comp, () -> false)) {
      assertTrue(handle.status().configured(), "GPU is configured per composition");
    }
  }

  @Test
  @DisplayName("assembler honours deferCpuSession=true — build succeeds on nonexistent model path")
  void assemblerForwardsDeferCpuSessionTrue() throws Exception {
    // deferCpuSession=true: assembler must skip eager CPU session creation. Build succeeds even
    // though `modelFile()` points at a nonexistent path. Proves the flag flowed end-to-end.
    Composition comp = compositionWith(/* deferCpuSession= */ true, true, 60_000L);

    try (SessionHandle handle =
        OrtSessionAssembler.buildManager("bridge-test", comp, () -> false)) {
      assertTrue(handle.status().configured());
    }
  }

  @Test
  @DisplayName("assembler honours deferCpuSession=false — build throws on nonexistent model path")
  void assemblerForwardsDeferCpuSessionFalse() {
    // deferCpuSession=false: assembler attempts eager CPU session creation, which fails because
    // the model file does not exist. OrtException proves the flag flowed end-to-end (the opposite
    // flag produces the opposite observable behaviour, pinning the flag's meaning).
    Composition comp = compositionWith(/* deferCpuSession= */ false, true, 60_000L);

    assertThrows(
        OrtException.class,
        () -> OrtSessionAssembler.buildManager("bridge-test", comp, () -> false));
  }

  @Test
  @DisplayName("assembler builds successfully at the default 60000 ms retry interval")
  void assemblerPassesOnDefaultRetryInterval() throws Exception {
    Composition ok = compositionWith(true, false, 60_000L);

    try (SessionHandle handle =
        OrtSessionAssembler.buildManager("bridge-test", ok, () -> false)) {
      assertTrue(handle.status().configured());
    }
  }

  // =========================================================================
  // Tempdoc 374 alpha.18 Bug I: gpuAllowed plumbing through resolveVariant
  // =========================================================================

  @TempDir Path tempDir;

  /**
   * Tempdoc 374 alpha.18 Bug I regression guard: {@code resolveVariant} must forward
   * {@code gpuEnabled=false} to {@code VariantSelector.select} when the contract is
   * non-null. Pre-alpha.18 the parameter was dropped on the floor in the contract
   * branch — citation got a CUDA-promoted variant and {@code assertCitationIsCpuOnly}
   * blew up. After alpha.18, citation's variant stays on CPU even on a CUDA host.
   */
  @Test
  @DisplayName(
      "resolveVariant forwards gpuEnabled=false to contract branch (374 alpha.18 Bug I)")
  void resolveVariantForwardsGpuEnabledFalseToContract() throws Exception {
    Path citationFile = tempDir.resolve("onnx/citation-scorer/model.onnx");
    Files.createDirectories(citationFile.getParent());
    Files.writeString(citationFile, "stub");

    InstallContract.InstalledModel citation =
        new InstallContract.InstalledModel(
            "citation-scorer",
            "model.onnx",
            ModelPrecision.INT8,
            ExecutionProvider.CPU,
            "onnx/citation-scorer",
            "HASH",
            List.of("model.onnx"),
            false,
            null);
    InstallContract contract =
        new InstallContract(
            2,
            System.currentTimeMillis(),
            HardwareProfile.cpuOnly(),
            DownloadProfile.CPU,
            Map.of("citation-scorer", citation));

    HardwareProfile cudaHost = new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024);

    VariantSelection sel =
        InferenceCompositionRoot.resolveVariant(
            "citation-scorer",
            contract,
            cudaHost,
            tempDir,
            /* configModelPath= */ null,
            /* gpuEnabled= */ false);

    assertNotNull(sel, "citation variant must resolve when contract has it installed");
    assertEquals(
        ExecutionProvider.CPU,
        sel.executionProvider(),
        "Citation must stay on CPU when gpuEnabled=false (CPU-only by design)."
            + " Pre-alpha.18 resolveVariant dropped gpuEnabled in the contract branch,"
            + " so VariantSelector promoted the variant to CUDA and"
            + " assertCitationIsCpuOnly failed-loud (374 alpha.18 Bug I).");
  }

  /**
   * Pre-alpha.18 default for GPU-capable roles: CPU-installed on CUDA host promotes
   * to CUDA. Pinned here to prove the parameter actually means something — passing
   * {@code true} restores the promotion, passing {@code false} does not.
   */
  @Test
  @DisplayName(
      "resolveVariant forwards gpuEnabled=true to contract branch (promotion stays)")
  void resolveVariantForwardsGpuEnabledTrueToContract() throws Exception {
    Path embedFile = tempDir.resolve("onnx/embedding/model.onnx");
    Files.createDirectories(embedFile.getParent());
    Files.writeString(embedFile, "stub");

    InstallContract.InstalledModel embed =
        new InstallContract.InstalledModel(
            "embedding",
            "model.onnx",
            ModelPrecision.FP32,
            ExecutionProvider.CPU,
            "onnx/embedding",
            "HASH",
            List.of("model.onnx"),
            false,
            null);
    InstallContract contract =
        new InstallContract(
            2,
            System.currentTimeMillis(),
            HardwareProfile.cpuOnly(),
            DownloadProfile.CPU,
            Map.of("embedding", embed));

    HardwareProfile cudaHost = new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024);

    VariantSelection sel =
        InferenceCompositionRoot.resolveVariant(
            "embedding",
            contract,
            cudaHost,
            tempDir,
            /* configModelPath= */ null,
            /* gpuEnabled= */ true);

    assertNotNull(sel);
    assertEquals(
        ExecutionProvider.CUDA,
        sel.executionProvider(),
        "GPU-capable role with gpuEnabled=true must promote CPU-installed variant to CUDA"
            + " (existing behaviour, pinned).");
  }

  // =========================================================================
  // Helpers — synthesise a CUDA-shaped composition without touching disk.
  // =========================================================================

  private static Composition compositionWithRetryInterval(long retryIntervalMs) {
    return compositionWith(true, true, retryIntervalMs);
  }

  private static Composition compositionWith(
      boolean deferCpuSession, boolean gpuRetryEnabled, long gpuRetryIntervalMs) {
    VariantSelection variant =
        VariantSelection.optimal(
            Path.of("nonexistent/model.onnx"), ModelPrecision.FP16, ExecutionProvider.CUDA);
    ModelSessionPolicy policy =
        new ModelSessionPolicy(
            variant,
            new ModelSessionPolicy.Gpu(
                512L * 1024 * 1024, /* cudaDeviceId= */ 0, Optional.empty()),
            new ModelSessionPolicy.Cpu(OptLevel.EXTENDED_OPT),
            new ModelSessionPolicy.Lifecycle(deferCpuSession, gpuRetryEnabled, gpuRetryIntervalMs),
            new ModelSessionPolicy.RunOptions(/* arenaShrinkage= */ true));
    RuntimePolicy runtime = RuntimePolicy.defaults();
    ModelArtifacts artifacts = new ModelArtifacts(variant.modelFile(), variant.modelFile());
    return new Composition(runtime, policy, artifacts);
  }
}
