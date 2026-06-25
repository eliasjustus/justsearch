package io.justsearch.app.services.ai.runtime;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeActivationServiceTest {

  @TempDir Path tmp;

  private String prevHome;
  private final Map<String, String> prevProps = new HashMap<>();

  @AfterEach
  void cleanup() {
    if (prevHome == null) System.clearProperty("justsearch.home");
    else System.setProperty("justsearch.home", prevHome);
    // Restore any system properties set during tests
    for (var entry : prevProps.entrySet()) {
      if (entry.getValue() == null) System.clearProperty(entry.getKey());
      else System.setProperty(entry.getKey(), entry.getValue());
    }
    prevProps.clear();
  }

  @Test
  void activationBlockedWhenGpuDisabledByPolicy() throws Exception {
    setHome(tmp);

    Files.writeString(
        tmp.resolve("policy.v1.json"),
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-26T00:00:00Z",
          "downloadsEnabled": true,
          "onlineAiEnabled": true,
          "gpuAccelerationEnabled": false,
          "disallowExternalInferenceServers": false,
          "allowlists": {
            "packManifestSha256": [],
            "modelSha256": []
          }
        }
        """,
        StandardCharsets.UTF_8);

    EnterprisePolicyService policy = new EnterprisePolicyServiceImpl();
    RuntimeActivationService svc =
        new RuntimeActivationService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            policy);

    svc.startActivate("cuda-12.4");
    AiRuntimeActivationStatus st = awaitDone(svc);
    assertEquals("failed", st.state);
    assertEquals("POLICY_GPU_DISABLED", st.errorCode);
  }

  @Test
  void activationBlockedWhenOnlineAiDisabledByPolicy() throws Exception {
    setHome(tmp);

    Files.writeString(
        tmp.resolve("policy.v1.json"),
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-26T00:00:00Z",
          "downloadsEnabled": true,
          "onlineAiEnabled": false,
          "gpuAccelerationEnabled": true,
          "disallowExternalInferenceServers": false,
          "allowlists": {
            "packManifestSha256": [],
            "modelSha256": []
          }
        }
        """,
        StandardCharsets.UTF_8);

    EnterprisePolicyService policy = new EnterprisePolicyServiceImpl();
    RuntimeActivationService svc =
        new RuntimeActivationService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            policy);

    svc.startActivate("cuda-12.4");
    AiRuntimeActivationStatus st = awaitDone(svc);
    assertEquals("failed", st.state);
    assertEquals("POLICY_ONLINE_AI_DISABLED", st.errorCode);
  }

  // --------------- ONNX feature status tests (D-4, tempdoc 215) ---------------

  @Test
  void onnxFeatureActiveWhenCacheReportsFound() {
    setHome(tmp);
    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
        new OnnxModelStatus("citation-scorer", true, "C:\\models\\citation-scorer", true, true));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    assertEquals(2, features.size());
    assertEquals("active", features.get(0).status());
    assertEquals("auto_discovered", features.get(0).reason());
    assertEquals("C:\\models\\reranker", features.get(0).modelPath());
    assertEquals("active", features.get(1).status());
    assertEquals("auto_discovered", features.get(1).reason());
    assertEquals("C:\\models\\citation-scorer", features.get(1).modelPath());
  }

  @Test
  void onnxFeatureInactiveWhenCacheReportsNotFound() {
    setHome(tmp);
    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", false, null, false, false),
        new OnnxModelStatus("citation-scorer", false, null, false, false));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    assertEquals("inactive", features.get(0).status());
    assertEquals("not_found", features.get(0).reason());
    assertNull(features.get(0).modelPath());
    assertEquals("inactive", features.get(1).status());
    assertEquals("not_found", features.get(1).reason());
  }

  @Test
  void onnxFeatureInactiveWhenCacheIsNull() {
    setHome(tmp);
    // 4-arg constructor — no WorkerFeatureCache
    RuntimeActivationService svc =
        new RuntimeActivationService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null, null);

    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    assertEquals("inactive", features.get(0).status());
    assertEquals("not_found", features.get(0).reason());
    assertEquals("inactive", features.get(1).status());
    assertEquals("not_found", features.get(1).reason());
  }

  @Test
  void onnxFeatureDisabledTakesPrecedenceOverCache() {
    setHome(tmp);
    setProp("justsearch.rerank.enabled", "false");

    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
        new OnnxModelStatus("citation-scorer", true, "C:\\models\\citation-scorer", true, true));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    // Reranker disabled by env var — cache ignored
    assertEquals("inactive", features.get(0).status());
    assertEquals("disabled", features.get(0).reason());
    // Citation scorer not disabled — cache used
    assertEquals("active", features.get(1).status());
    assertEquals("auto_discovered", features.get(1).reason());
  }

  @Test
  void onnxFeatureExplicitPathTakesPrecedenceOverCache() {
    setHome(tmp);
    setProp("justsearch.rerank.model_path", "D:\\custom\\reranker");

    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
        new OnnxModelStatus("citation-scorer", false, null, false, false));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    // Reranker uses explicit path — cache ignored
    assertEquals("active", features.get(0).status());
    assertEquals("explicit_path", features.get(0).reason());
    assertEquals("D:\\custom\\reranker", features.get(0).modelPath());
    // Citation scorer falls through to cache (not found)
    assertEquals("inactive", features.get(1).status());
    assertEquals("not_found", features.get(1).reason());
  }

  private RuntimeActivationService createServiceWithCache(WorkerFeatureCache cache) {
    return new RuntimeActivationService(
        OnlineAiService.unavailable(),
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
        null, null, cache);
  }

  /** Sets a system property and records the previous value for cleanup. */
  private void setProp(String key, String value) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void setHome(Path home) {
    prevHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", home.toAbsolutePath().toString());
    System.setProperty("justsearch.data.dir", home.toAbsolutePath().toString());
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc) throws Exception {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      AiRuntimeActivationStatus st = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(st.state)) {
        return st;
      }
      Thread.sleep(50);
    }
    fail("Timed out waiting for runtime activation to finish");
    return svc.getActivationStatus();
  }

}
