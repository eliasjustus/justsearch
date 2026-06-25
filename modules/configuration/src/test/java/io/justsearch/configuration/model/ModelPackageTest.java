package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelPackageTest {

  private static final ModelVariant FP32_CPU =
      new ModelVariant(
          "model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU, "AAAA", 1_000_000, "https://example.com/fp32");

  private static final ModelVariant FP16_CUDA =
      new ModelVariant(
          "model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA, "BBBB", 500_000, "https://example.com/fp16");

  private static final ModelVariant INT8_CPU =
      new ModelVariant(
          "model.onnx", ModelPrecision.INT8, ExecutionProvider.CPU, "CCCC", 200_000, "https://example.com/int8");

  @Test
  void selectVariant_cpuProfile_returnsCpuVariant() {
    var pkg = makePackage(List.of(FP32_CPU, FP16_CUDA));
    var selected = pkg.selectVariant(DownloadProfile.CPU);
    assertEquals("model.onnx", selected.filename());
    assertEquals(ModelPrecision.FP32, selected.precision());
  }

  @Test
  void selectVariant_gpuFullProfile_returnsCudaVariant() {
    var pkg = makePackage(List.of(FP32_CPU, FP16_CUDA));
    var selected = pkg.selectVariant(DownloadProfile.GPU_FULL);
    assertEquals("model_fp16.onnx", selected.filename());
    assertEquals(ModelPrecision.FP16, selected.precision());
  }

  @Test
  void selectVariant_gpuLiteProfile_returnsCudaVariant() {
    var pkg = makePackage(List.of(FP32_CPU, FP16_CUDA));
    var selected = pkg.selectVariant(DownloadProfile.GPU_LITE);
    assertEquals("model_fp16.onnx", selected.filename());
  }

  @Test
  void selectVariant_cpuOnlyModel_fallsBackForGpuProfiles() {
    var pkg = makePackage(List.of(INT8_CPU));
    assertEquals(INT8_CPU, pkg.selectVariant(DownloadProfile.CPU));
    // GPU profiles prefer CUDA variant — none exists, falls back to CPU variant
    assertEquals(INT8_CPU, pkg.selectVariant(DownloadProfile.GPU_FULL));
    assertEquals(INT8_CPU, pkg.selectVariant(DownloadProfile.GPU_LITE));
  }

  @Test
  void selectVariant_noMatchingEP_fallsBackToFirstVariant() {
    var pkg = makePackage(List.of(FP16_CUDA));
    // CPU profile prefers CPU variant — none exists, falls back to FP16
    assertEquals(FP16_CUDA, pkg.selectVariant(DownloadProfile.CPU));
  }

  @Test
  void selectVariant_emptyVariants_returnsNull() {
    var pkg = makePackage(List.of());
    assertNull(pkg.selectVariant(DownloadProfile.CPU));
    assertNull(pkg.selectVariant(DownloadProfile.GPU_FULL));
  }

  @Test
  void hasVramRequirement_zeroMeansNoRequirement() {
    var pkg = makePackage(0);
    assertEquals(false, pkg.hasVramRequirement());
  }

  @Test
  void hasVramRequirement_nonZeroMeansRequired() {
    var pkg = makePackage(7_500_000_000L);
    assertEquals(true, pkg.hasVramRequirement());
  }

  private static ModelPackage makePackage(List<ModelVariant> variants) {
    return new ModelPackage(
        "test-model", "Test", "Test model", "onnx/test", variants, List.of(), 0, null);
  }

  private static ModelPackage makePackage(long minVramBytes) {
    return new ModelPackage(
        "test-model", "Test", "Test model", "onnx/test", List.of(), List.of(), minVramBytes, null);
  }
}
