package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InferenceLifecycleManagerPropsInsightsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void updateFromPropsBestEffort_populatesExternalDiagnosticsForMismatchedModel() throws Exception {
    InferenceLifecycleManager manager = newManager(Path.of("models", "configured.gguf"), 4096);
    try {
      setUsingExternalServer(manager, true);
      JsonNode root =
          MAPPER.readTree(
              "{\"model_alias\":\"external\",\"model_path\":\"/models/external.gguf\",\"n_ctx\":2048}");
      invokeUpdateFromPropsBestEffort(manager, root);

      InferenceLifecycleManager.ExternalServerDiagnostics diagnostics = manager.externalServerDiagnostics();
      assertTrue(diagnostics.usingExternalLlamaServer());
      assertTrue(diagnostics.verified());
      assertEquals("external", diagnostics.modelId());
      assertEquals(2048, diagnostics.contextTokens());
      assertTrue(diagnostics.modelMismatch());
      assertTrue(diagnostics.contextTooSmall());
      assertTrue(diagnostics.adoptedAtMs() > 0);
      assertEquals(2048, manager.lastKnownContextTokens());
    } finally {
      manager.close();
    }
  }

  @Test
  void updateFromPropsBestEffort_usesModelPathFilenameAndCaseInsensitiveComparison() throws Exception {
    InferenceLifecycleManager manager = newManager(Path.of("models", "Configured.GGUF"), 4096);
    try {
      setUsingExternalServer(manager, true);
      JsonNode root = MAPPER.readTree("{\"model_path\":\"/tmp/configured.gguf\",\"n_ctx\":8192}");
      invokeUpdateFromPropsBestEffort(manager, root);

      InferenceLifecycleManager.ExternalServerDiagnostics diagnostics = manager.externalServerDiagnostics();
      assertTrue(diagnostics.usingExternalLlamaServer());
      assertTrue(diagnostics.verified());
      assertEquals("configured.gguf", diagnostics.modelId());
      assertEquals(8192, diagnostics.contextTokens());
      assertFalse(diagnostics.modelMismatch());
      assertFalse(diagnostics.contextTooSmall());
      assertEquals(8192, manager.lastKnownContextTokens());
    } finally {
      manager.close();
    }
  }

  private static InferenceLifecycleManager newManager(Path modelPath, int contextSize) {
    InferenceConfig config =
        new InferenceConfig(
            Path.of("bin", "llama-server.exe"),
            modelPath,
            null,
            8080,
            contextSize,
            0,
            false);
    return new InferenceLifecycleManager(config);
  }

  private static void setUsingExternalServer(InferenceLifecycleManager manager, boolean value) {
    manager.setUsingExternalServerForTest(value);
  }

  private static void invokeUpdateFromPropsBestEffort(
      InferenceLifecycleManager manager, JsonNode root) {
    manager.updateFromPropsBestEffort(root);
  }
}
