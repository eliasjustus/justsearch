package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerPropsOpsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AtomicReference<String> lastModelId;
  private AtomicReference<Integer> lastContextTokens;
  private AtomicReference<Boolean> externalActive;
  private ServerPropsOps ops;

  @BeforeEach
  void setUp() {
    lastModelId = new AtomicReference<>(null);
    lastContextTokens = new AtomicReference<>(null);
    externalActive = new AtomicReference<>(false);
    InferenceConfig config =
        new InferenceConfig(
            Path.of("bin", "llama-server.exe"),
            Path.of("models", "configured.gguf"),
            null,
            8080,
            4096,
            0,
            false);
    ops =
        new ServerPropsOps(
            () -> config,
            externalActive::get,
            new PropsObserver() {
              @Override
              public void onModelIdObserved(String modelId) {
                lastModelId.set(modelId);
              }

              @Override
              public void onContextTokensObserved(int contextTokens) {
                lastContextTokens.set(contextTokens);
              }

              @Override
              public String observedModelId() {
                return lastModelId.get();
              }

              @Override
              public Integer observedContextTokens() {
                return lastContextTokens.get();
              }
            });
  }

  // ==================== resetExternalAdoptionState ====================

  @Test
  void resetExternalAdoptionState_setsVerifiedAndTimestamp() {
    long before = System.currentTimeMillis();
    ops.resetExternalAdoptionState(true, null);
    var diag = ops.buildExternalDiagnostics(true, 0, null, 0);

    assertTrue(diag.verified());
    assertNull(diag.verificationError());
    assertTrue(diag.adoptedAtMs() >= before);
  }

  @Test
  void resetExternalAdoptionState_setsErrorWhenNotVerified() {
    ops.resetExternalAdoptionState(false, "props_missing_expected_fields");
    var diag = ops.buildExternalDiagnostics(true, 0, null, 0);

    assertFalse(diag.verified());
    assertEquals("props_missing_expected_fields", diag.verificationError());
  }

  // ==================== buildExternalDiagnostics ====================

  @Test
  void buildExternalDiagnostics_returnsSnapshotAfterReset() {
    ops.resetExternalAdoptionState(true, null);
    var diag = ops.buildExternalDiagnostics(true, 12345L, "timeout", 3);

    assertTrue(diag.usingExternalLlamaServer());
    assertTrue(diag.verified());
    assertNull(diag.modelId());
    assertNull(diag.contextTokens());
    assertFalse(diag.modelMismatch());
    assertFalse(diag.contextTooSmall());
    assertTrue(diag.adoptedAtMs() > 0);
    assertEquals(12345L, diag.lastHealthOkAtMs());
    assertEquals("timeout", diag.lastHealthError());
    assertEquals(3, diag.consecutiveHealthFailures());
  }

  // ==================== updateFromPropsBestEffort ====================

  @Test
  void updateFromPropsBestEffort_extractsModelIdFromAlias() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_alias\":\"my-model\",\"n_ctx\":4096}");
    ops.updateFromPropsBestEffort(root);

    assertEquals("my-model", lastModelId.get());
  }

  @Test
  void updateFromPropsBestEffort_extractsModelIdFromPathFilename() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_path\":\"/tmp/some-model.gguf\",\"n_ctx\":4096}");
    ops.updateFromPropsBestEffort(root);

    assertEquals("some-model.gguf", lastModelId.get());
  }

  @Test
  void updateFromPropsBestEffort_extractsContextTokens() throws Exception {
    JsonNode root = MAPPER.readTree("{\"n_ctx\":2048}");
    ops.updateFromPropsBestEffort(root);

    assertEquals(2048, lastContextTokens.get());
  }

  @Test
  void updateFromPropsBestEffort_populatesExternalDiagnosticsForMismatch() throws Exception {
    externalActive.set(true);
    JsonNode root =
        MAPPER.readTree(
            "{\"model_alias\":\"external\","
                + "\"model_path\":\"/models/external.gguf\","
                + "\"n_ctx\":2048}");
    ops.updateFromPropsBestEffort(root);

    var diag = ops.buildExternalDiagnostics(true, 0, null, 0);
    assertTrue(diag.verified());
    assertTrue(diag.modelMismatch());
    assertTrue(diag.contextTooSmall());
    assertEquals("external", diag.modelId());
    assertEquals(2048, diag.contextTokens());
  }

  // ==================== looksLikeLlamaServerProps ====================

  @Test
  void looksLikeLlamaServerProps_trueForValidProps() throws Exception {
    assertTrue(
        ServerPropsOps.looksLikeLlamaServerProps(
            MAPPER.readTree("{\"model_alias\":\"test\"}")));
    assertTrue(
        ServerPropsOps.looksLikeLlamaServerProps(
            MAPPER.readTree("{\"model_path\":\"/tmp/model.gguf\"}")));
    assertTrue(
        ServerPropsOps.looksLikeLlamaServerProps(MAPPER.readTree("{\"n_ctx\":4096}")));
  }

  // ==================== Vision Capability ====================

  @Test
  void updateFromPropsBestEffort_extractsVisionCapability() throws Exception {
    JsonNode root =
        MAPPER.readTree("{\"model_alias\":\"test\",\"modalities\":{\"vision\":true},\"n_ctx\":4096}");
    ops.updateFromPropsBestEffort(root);

    assertTrue(ops.hasVisionCapability());
  }

  @Test
  void visionCapability_falseWhenModalitiesMissing() throws Exception {
    JsonNode root = MAPPER.readTree("{\"model_alias\":\"test\",\"n_ctx\":4096}");
    ops.updateFromPropsBestEffort(root);

    assertFalse(ops.hasVisionCapability());
  }

  @Test
  void visionCapability_resetOnExternalAdoptionReset() throws Exception {
    JsonNode root =
        MAPPER.readTree("{\"model_alias\":\"test\",\"modalities\":{\"vision\":true},\"n_ctx\":4096}");
    ops.updateFromPropsBestEffort(root);
    assertTrue(ops.hasVisionCapability());

    ops.resetExternalAdoptionState(true, null);
    assertFalse(ops.hasVisionCapability());
  }

  @Test
  void looksLikeLlamaServerProps_falseForEmptyOrMissing() throws Exception {
    assertFalse(ServerPropsOps.looksLikeLlamaServerProps(null));
    assertFalse(
        ServerPropsOps.looksLikeLlamaServerProps(MAPPER.readTree("{}")));
    assertFalse(
        ServerPropsOps.looksLikeLlamaServerProps(
            MAPPER.readTree("{\"model_alias\":\"\"}")));
  }
}
