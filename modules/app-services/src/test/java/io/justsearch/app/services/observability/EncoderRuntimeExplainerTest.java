package io.justsearch.app.services.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.ort.EncoderRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Table-driven coverage of the {@link EncoderRuntimeExplainer} decision tree (tempdoc 422).
 *
 * <p>The 6 numbered cases mirror tempdoc 422 §3 Path A. Two representative encoders are exercised
 * (one CUDA-default {@link EncoderRole#EMBEDDING}, one CPU-default {@link EncoderRole#CITATION})
 * to confirm the role parameter doesn't leak into output that should depend only on
 * (policy, view) inputs. Defensive cases cover null policy + malformed JSON shapes.
 */
final class EncoderRuntimeExplainerTest {

  // ---------- Case 1: policy == null ----------

  @Test
  void case1_nullPolicy_embedding_unavailable() {
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, gpuAvailable(), null);
    assertEquals("unavailable", v.currentAccelerator());
    assertFalse(v.available());
    assertEquals("Encoder not active in current configuration.", v.explanation());
  }

  @Test
  void case1_nullPolicy_citation_unavailable() {
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.CITATION, OrtCudaView.notConfigured(), null);
    assertEquals("unavailable", v.currentAccelerator());
    assertFalse(v.available());
  }

  // ---------- Case 2: policy.variant.executionProvider == "CPU" ----------

  @Test
  void case2_cpuByDesign_citation() {
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(
            EncoderRole.CITATION, OrtCudaView.notConfigured(), policy("CPU", 0));
    assertEquals("cpu", v.currentAccelerator());
    assertEquals("CPU", v.configuredAccelerator());
    assertTrue(v.available());
    assertEquals("Encoder configured for CPU by design.", v.explanation());
  }

  @Test
  void case2_cpuByDesign_embedding_lowercaseAccepted() {
    // Defensive: explainer treats "cpu" and "CPU" as equivalent.
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(
            EncoderRole.EMBEDDING, OrtCudaView.notConfigured(), policy("cpu", 0));
    assertEquals("cpu", v.currentAccelerator());
  }

  // ---------- Case 3: GPU attempted + available ----------

  @Test
  void case3_gpuAvailable_embedding_reportsArena() {
    long fiveTwelveMb = 512L * 1024L * 1024L;
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(
            EncoderRole.EMBEDDING, gpuAvailable(), policy("CUDA", fiveTwelveMb));
    assertEquals("cuda", v.currentAccelerator());
    assertTrue(v.available());
    assertTrue(
        v.explanation().contains("GPU initialized successfully"),
        () -> "Got: " + v.explanation());
    assertTrue(v.explanation().contains("arena cap 512 MB"), () -> "Got: " + v.explanation());
  }

  @Test
  void case3_gpuAvailable_citation_noArenaConfigured() {
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(
            EncoderRole.CITATION, gpuAvailable(), policy("CUDA", 0));
    assertEquals("cuda", v.currentAccelerator());
    // arenaCapBytes==0 → no "arena cap" text.
    assertFalse(v.explanation().contains("arena cap"), () -> "Got: " + v.explanation());
  }

  // ---------- Case 4: GPU attempted, failed, with reason ----------

  @Test
  void case4_gpuFailed_embedding_reportsReason() {
    OrtCudaView failed =
        new OrtCudaView(true, true, false, "cuda12", "C:/native", "missing cudnn64_9.dll", List.of());
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, failed, policy("CUDA", 0));
    assertEquals("cpu", v.currentAccelerator());
    assertTrue(v.available());
    assertTrue(
        v.explanation().contains("GPU init failed: missing cudnn64_9.dll"),
        () -> "Got: " + v.explanation());
    assertTrue(v.explanation().contains("CPU fallback"), () -> "Got: " + v.explanation());
  }

  @Test
  void case4_gpuFailed_citation_reportsReason() {
    OrtCudaView failed =
        new OrtCudaView(true, true, false, "cuda12", "C:/native", "no cuda device", List.of());
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.CITATION, failed, policy("CUDA", 0));
    assertEquals("cpu", v.currentAccelerator());
    assertTrue(v.explanation().contains("no cuda device"));
  }

  // ---------- Case 5: configured but not attempted ----------

  @Test
  void case5_gpuConfiguredButNotAttempted_embedding() {
    OrtCudaView pending =
        new OrtCudaView(true, false, false, "cuda12", "C:/native", "", List.of());
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, pending, policy("CUDA", 0));
    assertEquals("cpu", v.currentAccelerator());
    assertTrue(v.explanation().contains("not yet attempted"));
    assertTrue(v.explanation().contains("until first inference"));
  }

  // ---------- Case 6: catch-all ----------

  @Test
  void case6_catchAll_neitherConfiguredNorAttempted() {
    OrtCudaView raw =
        new OrtCudaView(false, false, false, "", "", "", List.of());
    // Configured for GPU in policy but probe says GPU was never configured at runtime — falls
    // through to the catch-all branch.
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, raw, policy("CUDA", 0));
    assertEquals("cpu", v.currentAccelerator());
    assertTrue(v.explanation().contains("/api/debug/session-policies"));
  }

  // ---------- Defensive: missing / malformed policy fields ----------

  @Test
  void defensive_missingExecutionProvider_defaultsToCpuCatchAll() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("variant", new LinkedHashMap<String, Object>());
    OrtCudaView raw = new OrtCudaView(false, false, false, "", "", "", List.of());
    EncoderRuntimeView v = EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, raw, p);
    assertEquals("cpu", v.currentAccelerator());
    assertEquals("", v.configuredAccelerator());
  }

  @Test
  void defensive_malformedVariantNode_doesNotThrow() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("variant", "not-a-map");
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, gpuAvailable(), p);
    assertNotNull(v);
    assertEquals("", v.configuredAccelerator());
  }

  @Test
  void defensive_arenaCapAsString_parsesNumeric() {
    Map<String, Object> p = new LinkedHashMap<>();
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("executionProvider", "CUDA");
    p.put("variant", variant);
    Map<String, Object> gpu = new LinkedHashMap<>();
    gpu.put("arenaCapBytes", "1073741824"); // 1 GB as string
    p.put("gpu", gpu);
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, gpuAvailable(), p);
    assertTrue(v.explanation().contains("arena cap 1024 MB"), () -> "Got: " + v.explanation());
  }

  @Test
  void defensive_arenaCapMalformed_omitsArenaText() {
    Map<String, Object> p = new LinkedHashMap<>();
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("executionProvider", "CUDA");
    p.put("variant", variant);
    Map<String, Object> gpu = new LinkedHashMap<>();
    gpu.put("arenaCapBytes", "not-a-number");
    p.put("gpu", gpu);
    EncoderRuntimeView v =
        EncoderRuntimeExplainer.explain(EncoderRole.EMBEDDING, gpuAvailable(), p);
    assertEquals("cuda", v.currentAccelerator());
    assertFalse(v.explanation().contains("arena cap"), () -> "Got: " + v.explanation());
  }

  // ---------- helpers ----------

  private static OrtCudaView gpuAvailable() {
    return new OrtCudaView(true, true, true, "cuda12", "C:/native", "", List.of());
  }

  private static Map<String, Object> policy(String executionProvider, long arenaCapBytes) {
    Map<String, Object> p = new LinkedHashMap<>();
    Map<String, Object> variant = new LinkedHashMap<>();
    variant.put("executionProvider", executionProvider);
    p.put("variant", variant);
    if (arenaCapBytes > 0) {
      Map<String, Object> gpu = new LinkedHashMap<>();
      gpu.put("arenaCapBytes", arenaCapBytes);
      p.put("gpu", gpu);
    }
    return p;
  }
}
