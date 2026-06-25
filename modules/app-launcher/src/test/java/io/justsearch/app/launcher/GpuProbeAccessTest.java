package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit rules: the unified GPU resolver ({@code GpuCapabilityResolver}, app-services) is the ONE
 * authority that composes every GPU probe into a single {@code GpuCapabilities} snapshot. Production
 * code must read GPU facts through it (or {@code GpuCapabilitiesService} for the VRAM/device axes),
 * never by calling the raw probes directly.
 *
 * <p>This generalizes the {@link VramDetectorAccessTest} foreclosure to the whole probe set
 * (tempdoc 587). The concrete defect it forbids re-introducing: a consumer calling the CUDA
 * driver-API probe directly composes the CUDA axis OUTSIDE the merged {@code Effective} view (the
 * §5 bypass that {@code AiInstallService} previously had), so the surface and policy would see
 * different, un-provenanced GPU facts.
 *
 * <ul>
 *   <li>{@code GpuDriverApiProbe} (ort-common, nvcuda CUDA-functional): only ort-common internals
 *       and the resolver package may depend on it.
 *   <li>{@code NvmlService} (gpu-bridge, native NVML): only gpu-bridge may depend on it — every
 *       other reader goes through {@code GpuCapabilitiesService} / {@code GpuCapabilityResolver}.
 * </ul>
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class GpuProbeAccessTest {

  @ArchTest
  static final ArchRule onlyResolverMayProbeCuda =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.justsearch.ort..", "io.justsearch.app.services.gpu..")
          .should()
          .dependOnClassesThat()
          .haveNameMatching("io\\.justsearch\\.ort\\.GpuDriverApiProbe.*")
          .as(
              "CUDA-functional must be read via GpuCapabilityResolver (the one GPU composition "
                  + "seam, tempdoc 587), not by calling GpuDriverApiProbe directly — that bypass "
                  + "composes the CUDA axis outside the merged Effective view.");

  @ArchTest
  static final ArchRule onlyGpuBridgeMayUseNvml =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.justsearch.gpu..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.justsearch.gpu.NvmlService")
          .as(
              "Raw NVML (NvmlService) is gpu-bridge-internal; read the merged snapshot via "
                  + "GpuCapabilitiesService / GpuCapabilityResolver (tempdoc 587).");
}
