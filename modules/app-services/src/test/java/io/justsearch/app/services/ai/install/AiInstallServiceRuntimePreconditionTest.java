/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.Necessity;
import io.justsearch.configuration.model.SupportingFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 772 §Design "Design 1": the runtime precondition may proceed when the bundled runtime is
 * absent IFF the computed plan supplies a runtime via a pack-delivered (hardware-independent)
 * RUNTIME-tier package. {@code runInstallInternal} gates {@code fail("RUNTIME_MISSING")} on exactly
 * {@code !AiInstallService.runtimePreconditionMet(bundledPresent, plan, registry)}, so these
 * predicate tests are the behavior tests: {@code true} ⇒ install proceeds, {@code false} ⇒
 * RUNTIME_MISSING.
 */
final class AiInstallServiceRuntimePreconditionTest {

  /** Case 1: bundled runtime present → proceeds regardless of plan (today's behavior). */
  @Test
  void bundledRuntimePresent_proceeds() {
    ModelRegistry registry = registryWithoutRuntimePackage();
    InstallPlan emptyPlan =
        new InstallPlan(DownloadProfile.CPU, List.of(), List.of(), 0L, List.of());

    assertTrue(AiInstallService.runtimePreconditionMet(true, emptyPlan, registry));
  }

  /**
   * Case 2: bundled runtime absent AND the plan supplies no runtime package → fails
   * (RUNTIME_MISSING). This is today's behavior for the case that should still fail.
   */
  @Test
  void bundledAbsent_noRuntimePackageInPlan_fails() {
    ModelRegistry registry = registryWithoutRuntimePackage();
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.CPU,
            List.of(download("embedding")),
            List.of(),
            100L,
            List.of());

    assertFalse(AiInstallService.runtimePreconditionMet(false, plan, registry));
  }

  /**
   * Case 3 — the one that proves the fix: bundled runtime absent BUT the plan includes a
   * hardware-independent RUNTIME-tier package (requiresCuda=false) → proceeds instead of failing.
   * This scenario does not occur in any real registry today; it is constructed explicitly.
   */
  @Test
  void bundledAbsent_hardwareIndependentRuntimePackageInPlan_proceeds() {
    ModelRegistry registry = registryWithHardwareIndependentRuntime();
    InstallPlan planViaDownload =
        new InstallPlan(
            DownloadProfile.CPU,
            List.of(download("runtime-cpu-support")),
            List.of(),
            100L,
            List.of());
    assertTrue(AiInstallService.runtimePreconditionMet(false, planViaDownload, registry));

    // Also proceeds when the runtime package is already installed (delta plan, nothing to download).
    InstallPlan planViaAlreadyInstalled =
        new InstallPlan(DownloadProfile.CPU, List.of(), List.of(), 0L, List.of("runtime-cpu-support"));
    assertTrue(AiInstallService.runtimePreconditionMet(false, planViaAlreadyInstalled, registry));
  }

  /**
   * Critical-analysis guard (tempdoc 772): cuda-runtime is RUNTIME-tier but requiresCuda=true — a
   * CUDA DLL supplement, NOT a from-scratch runtime supplier. It must NOT count as runtime-supplying,
   * otherwise a GPU install whose bundled restore failed would proceed instead of raising
   * RUNTIME_MISSING — a real behavior change. Keeping it out preserves today's behavior exactly.
   */
  @Test
  void cudaRuntime_isNotTreatedAsRuntimeSupplying() {
    ModelRegistry registry = registryWithCudaRuntime();
    InstallPlan planWithCudaRuntime =
        new InstallPlan(
            DownloadProfile.GPU_LITE,
            List.of(download("cuda-runtime")),
            List.of(),
            100L,
            List.of());

    assertFalse(AiInstallService.planSuppliesRuntime(planWithCudaRuntime, registry));
    assertFalse(AiInstallService.runtimePreconditionMet(false, planWithCudaRuntime, registry));
  }

  // --- fixtures -------------------------------------------------------------

  private static InstallPlan.PlannedDownload download(String packageId) {
    return new InstallPlan.PlannedDownload(
        packageId, "https://example.com/f", "f", "SHA", 100L, false);
  }

  private static ModelPackage embedding() {
    return new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(), List.of(), 0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
  }

  private static ModelRegistry registryWithoutRuntimePackage() {
    return new ModelRegistry(2, "test", List.of(embedding()));
  }

  private static ModelRegistry registryWithHardwareIndependentRuntime() {
    ModelPackage runtimeCpuSupport =
        new ModelPackage(
            "runtime-cpu-support", "CPU runtime libraries", "Always-required runtime payload",
            "cpu-rt", List.of(),
            List.of(new SupportingFile("runtime.zip", "GGGG", 50L, "https://example.com/rt", true)),
            0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, false,
            Necessity.INFRASTRUCTURE, List.of());
    return new ModelRegistry(2, "test", List.of(embedding(), runtimeCpuSupport));
  }

  private static ModelRegistry registryWithCudaRuntime() {
    ModelPackage cudaRuntime =
        new ModelPackage(
            "cuda-runtime", "GPU runtime libraries", "CUDA DLLs", "cuda12", List.of(),
            List.of(new SupportingFile("cuda.zip", "FFFF", 50L, "https://example.com/cuda", true)),
            0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, true,
            Necessity.INFRASTRUCTURE, List.of());
    return new ModelRegistry(2, "test", List.of(embedding(), cudaRuntime));
  }
}
