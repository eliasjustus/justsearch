package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelRegistryLoaderTest {

  @Test
  void loadsV2RegistryFromClasspath() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertEquals(2, registry.schemaVersion());
    assertFalse(registry.packages().isEmpty());
  }

  @Test
  void registryContainsExpectedPackages() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertNotNull(registry.findPackage("embedding"));
    assertNotNull(registry.findPackage("splade"));
    assertNotNull(registry.findPackage("reranker"));
    assertNotNull(registry.findPackage("ner"));
    assertNotNull(registry.findPackage("citation-scorer"));
    assertNotNull(registry.findPackage("chat"));
    assertNotNull(registry.findPackage("cuda-runtime")); // alpha.15
    assertEquals(7, registry.packages().size());
  }

  @Test
  void embeddingPackageHasBothVariants() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage embed = registry.findPackage("embedding");

    assertEquals(2, embed.variants().size());

    ModelVariant cpuVariant = embed.selectVariant(DownloadProfile.CPU);
    assertNotNull(cpuVariant);
    assertEquals("model.onnx", cpuVariant.filename());
    assertEquals(ModelPrecision.FP32, cpuVariant.precision());
    assertEquals(ExecutionProvider.CPU, cpuVariant.targetEP());

    ModelVariant gpuVariant = embed.selectVariant(DownloadProfile.GPU_FULL);
    assertNotNull(gpuVariant);
    assertEquals("model_fp16.onnx", gpuVariant.filename());
    assertEquals(ModelPrecision.FP16, gpuVariant.precision());
    assertEquals(ExecutionProvider.CUDA, gpuVariant.targetEP());
  }

  @Test
  void spladePackageHasCorrectVariants() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage splade = registry.findPackage("splade");

    assertEquals(2, splade.variants().size());
    assertEquals(4, splade.supportingFiles().size());
    assertEquals("splade/naver-splade-v3", splade.targetDir());
  }

  @Test
  void nerPackageUsesInt8ForCpu() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage ner = registry.findPackage("ner");

    ModelVariant cpuVariant = ner.selectVariant(DownloadProfile.CPU);
    assertEquals(ModelPrecision.INT8, cpuVariant.precision());
  }

  @Test
  void citationScorerIsCpuOnly_fallsBackForGpuProfiles() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage citation = registry.findPackage("citation-scorer");

    assertEquals(1, citation.variants().size());
    assertEquals(ExecutionProvider.CPU, citation.variants().get(0).targetEP());
    // GPU profiles fall back to the CPU variant (citation-scorer has no CUDA variant)
    ModelVariant gpuSelected = citation.selectVariant(DownloadProfile.GPU_FULL);
    assertNotNull(gpuSelected);
    assertEquals(ExecutionProvider.CPU, gpuSelected.targetEP());
    assertEquals(ModelPrecision.INT8, gpuSelected.precision());
  }

  @Test
  void chatPackageHasVramRequirement() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage chat = registry.findPackage("chat");

    assertTrue(chat.hasVramRequirement());
    assertEquals(HardwareProfile.MINIMUM_VRAM_FOR_GGUF, chat.minVramBytes());
  }

  @Test
  void chatPackageHasMmprojAsSupportingFile() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage chat = registry.findPackage("chat");

    assertEquals(1, chat.supportingFiles().size());
    assertTrue(chat.supportingFiles().get(0).filename().contains("mmproj"));
  }

  /**
   * Tempdoc 632 — the registry is the license SSOT; the generated NOTICE projects from this field.
   * Every package must declare a license so the notice generator's presence-check stays green and no
   * model ships unattributed.
   */
  @Test
  void everyPackageDeclaresALicense() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    for (ModelPackage pkg : registry.packages()) {
      assertNotNull(pkg.license(), "package '" + pkg.id() + "' is missing a license (tempdoc 632 SSOT)");
      assertFalse(pkg.license().isBlank(), "package '" + pkg.id() + "' has a blank license");
    }
    // Spot-check the two non-Apache cases that are easy to mislabel.
    assertEquals("AFL-3.0", registry.findPackage("ner").license());
    assertEquals("Apache-2.0", registry.findPackage("chat").license());
  }

  /**
   * Tempdoc 657 — every package must declare a capability tier so an install intent can include or
   * exclude whole capability groups. A missing tier would silently make a package always-installed
   * (untagged ⇒ always wanted), defeating MCP Lite's ability to skip the LLM.
   */
  @Test
  void everyPackageDeclaresATier() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    for (ModelPackage pkg : registry.packages()) {
      assertNotNull(pkg.tier(), "package '" + pkg.id() + "' is missing a capability tier (tempdoc 657)");
    }
    // Spot-check the retrieval vs LLM vs runtime split MCP Lite depends on.
    assertEquals(CapabilityTier.RETRIEVAL_CORE, registry.findPackage("embedding").tier());
    assertEquals(CapabilityTier.RETRIEVAL_ENRICHMENT, registry.findPackage("splade").tier());
    assertEquals(CapabilityTier.LLM, registry.findPackage("chat").tier());
    assertEquals(CapabilityTier.RUNTIME, registry.findPackage("cuda-runtime").tier());
  }

  /**
   * Tempdoc 772 Q3 — production hardware-gating guard. The JSON loader must deserialize
   * cuda-runtime's {@code "requiresCuda": true} so the planner keeps skipping it on non-CUDA
   * hardware. A regression here (loader dropping the field, or JSON losing the key) would silently
   * un-gate cuda-runtime in production. Every other package leaves the field unset → false.
   */
  @Test
  void cudaRuntimeDeclaresRequiresCuda_othersDefaultFalse() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertTrue(
        registry.findPackage("cuda-runtime").requiresCuda(),
        "cuda-runtime must load requiresCuda=true to stay hardware-gated in production");
    assertFalse(registry.findPackage("embedding").requiresCuda());
    assertFalse(registry.findPackage("chat").requiresCuda());
  }

  /**
   * Tempdoc 772 §J item 2 — the win-x64 ORT CUDA execution-provider native set is trimmed from the
   * shipped installer jar and relocated into the consent-gated cuda-runtime pack. The loader must
   * carry the {@code ort-native-cuda12-v1.24.3.zip} supporting file with {@code extract: true} so
   * the pack unpacks the ORT DLLs into the cuda12 dir the worker points ORT at. A regression here
   * (entry dropped, or {@code extract} lost) would silently degrade GPU users to CPU with the
   * jar's CUDA EP DLL now gone.
   */
  @Test
  void cudaRuntimeShipsRelocatedOrtNativePack() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    SupportingFile ortNative =
        registry.findPackage("cuda-runtime").supportingFiles().stream()
            .filter(sf -> sf.filename().equals("ort-native-cuda12-v1.24.3.zip"))
            .findFirst()
            .orElse(null);

    assertNotNull(
        ortNative,
        "cuda-runtime must carry the relocated ORT CUDA-EP native pack (tempdoc 772 §J item 2)");
    assertTrue(ortNative.extract(), "the ORT native pack must extract into the cuda12 dir");
    assertTrue(ortNative.sizeBytes() > 0, "the ORT native pack must declare a real sizeBytes");
    assertNotNull(ortNative.sha256(), "the ORT native pack must declare a sha256");
  }

  /**
   * Tempdoc 633 #6 — first-run robustness. Every model/file download must resolve over HTTPS from an
   * allowlisted *public* host, so a stranger can clone → build → first-run without hitting a private or
   * unreachable source. This makes the README's "downloads from public sources" line a checked invariant
   * (not a one-time hand-promise) and fails the build if a future registry edit introduces a non-public
   * download URL.
   */
  @Test
  void everyDownloadUrlResolvesFromPublicHost() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    // Project-controlled releases (github.com/eliasjustus/justsearch-releases),
    // the upstream llama.cpp binaries (github.com/ggml-org), and the chat-model GGUF (huggingface.co).
    java.util.Set<String> allowedHosts = java.util.Set.of("github.com", "huggingface.co");

    java.util.List<String> urls = new java.util.ArrayList<>();
    for (ModelPackage pkg : registry.packages()) {
      for (ModelVariant v : pkg.variants()) urls.add(v.downloadUrl());
      for (SupportingFile sf : pkg.supportingFiles()) urls.add(sf.downloadUrl());
    }
    assertFalse(urls.isEmpty(), "registry exposed no downloadUrls to validate");

    for (String url : urls) {
      assertNotNull(url, "a download entry has a null URL");
      java.net.URI uri = java.net.URI.create(url);
      assertEquals("https", uri.getScheme(), "downloadUrl must be HTTPS: " + url);
      assertTrue(
          allowedHosts.contains(uri.getHost()),
          "downloadUrl host '" + uri.getHost() + "' is not in the public allowlist " + allowedHosts + ": " + url);
    }
  }
}
