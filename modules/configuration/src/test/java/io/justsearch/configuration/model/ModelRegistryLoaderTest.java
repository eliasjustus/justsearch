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
