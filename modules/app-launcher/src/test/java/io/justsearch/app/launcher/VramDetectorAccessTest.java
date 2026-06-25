package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.gpu.VramDetector;

/**
 * ArchUnit rule: production code outside {@code io.justsearch.gpu} must not depend on
 * {@link VramDetector} directly. Use {@code GpuCapabilitiesService.snapshot()} instead.
 *
 * <p>{@code VramDetector} spawns {@code nvidia-smi} via {@code Runtime.exec()}. On cuda12
 * sandbox hosts where NVML works fine but {@code nvidia-smi.exe} isn't on PATH, every direct
 * caller silently fell into the "VRAM unknown" branch — VDU was disabled, llama-server
 * launched without KV-cache flags, summarization timeouts dropped to defaults, etc.
 *
 * <p>{@code GpuCapabilitiesService.snapshot()} is NVML-first with {@code nvidia-smi} as a
 * fallback handled internally, so callers get the right answer on cuda12 sandbox AND
 * non-NVML systems. {@link io.justsearch.gpu.VramRequirements} provides the threshold
 * helpers ({@code meetsGgufRequirements}, {@code recommendedLlamaServerFlags},
 * {@code describe}) that {@code VramDetector}'s instance methods used to expose.
 *
 * <p>This rule turns "NVML migration complete" into a CI-enforced invariant. Pre-alpha.27
 * the same migration was claimed complete twice (alpha.14 P1, alpha.25 U14-C) but actually
 * fixed only the file the author had in front of them — five other production callers
 * silently kept using {@code VramDetector} directly. With this guard, any new direct
 * dependency outside {@code io.justsearch.gpu} fails CI with the explanatory message below.
 *
 * <p>Tempdoc 374 alpha.27.
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class VramDetectorAccessTest {

  @ArchTest
  static final ArchRule onlyGpuBridgeMayDependOnVramDetector =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.justsearch.gpu..")
          .should()
          .dependOnClassesThat()
          .areAssignableTo(VramDetector.class)
          .as(
              "VramDetector spawns nvidia-smi via Runtime.exec() and returns -1 on cuda12 "
                  + "sandbox hosts where NVML works fine. Use GpuCapabilitiesService.snapshot() "
                  + "(NVML-first) + VramRequirements (threshold helpers) instead. See "
                  + "tempdoc 374 alpha.27.");
}
