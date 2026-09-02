/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.javalin.http.Context;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code justsearch.gpu.layers}, {@code justsearch.server.exe}, {@code justsearch.index.base_path}
 * and {@code justsearch.llm.model_path} rows of {@code /api/debug/effective-config} report the
 * RESOLVER's provenance, and the {@code *.source} marker sysprops cannot influence them (tempdoc 883
 * decision 4 slice 2 for the first two, its §C.5c residue for the last two — which were the report's
 * final two marker readers).
 *
 * <p>Both markers are SET in every case below, which is what makes these assertions load-bearing:
 * a test that merely left them unset would pass on the pre-change controller too. On the
 * pre-change controller the gpu.layers row reported {@code source: "ui_settings"} plus an
 * {@code owner} / {@code uiOwnershipProp} / {@code uiOwnershipValue} triple, so every case here
 * fails against it.
 */
@DisplayName("effective-config rows ignore the retired .source markers")
final class EffectiveConfigRetiredMarkersTest {

  private static final String GPU_LAYERS = "justsearch.gpu.layers";
  private static final String GPU_LAYERS_MARKER = "justsearch.gpu.layers.source";
  private static final String SERVER_EXE = "justsearch.server.exe";
  private static final String SERVER_EXE_MARKER = "justsearch.server.exe.source";
  private static final String INDEX_BASE_PATH = "justsearch.index.base_path";
  private static final String INDEX_BASE_PATH_MARKER = "justsearch.index.base_path.source";
  private static final String LLM_MODEL_PATH = "justsearch.llm.model_path";
  private static final String LLM_MODEL_PATH_MARKER = "justsearch.llm.model_path.source";

  @AfterEach
  void clearMarkers() {
    System.clearProperty(GPU_LAYERS_MARKER);
    System.clearProperty(SERVER_EXE_MARKER);
    System.clearProperty(GPU_LAYERS);
    System.clearProperty(SERVER_EXE);
    System.clearProperty(INDEX_BASE_PATH_MARKER);
    System.clearProperty(INDEX_BASE_PATH);
    System.clearProperty(LLM_MODEL_PATH_MARKER);
    System.clearProperty(LLM_MODEL_PATH);
  }

  @Test
  @DisplayName("a GUI gpu.layers reports settings.json at ordinal 300, marker or no marker")
  void gpuLayersReportsSettingsJson() {
    System.setProperty(GPU_LAYERS_MARKER, "ui_settings");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(Map.of(GPU_LAYERS, "99"));
    builder.putSettings(GPU_LAYERS, "20");

    Map<String, Object> row = rowFor(GPU_LAYERS, new ConfigStore(builder.build()));

    assertEquals("settings.json", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, details.get("sourceOrdinal"));
    assertNoOwnershipVocabulary(details);
  }

  @Test
  @DisplayName("a probe-derived gpu.layers reports auto_detected / hardware_probe at 150")
  void gpuLayersReportsAutoDetected() {
    System.setProperty(GPU_LAYERS_MARKER, "ui_settings");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(Map.of(GPU_LAYERS, "99"));

    Map<String, Object> row = rowFor(GPU_LAYERS, new ConfigStore(builder.build()));

    assertEquals("auto_detected", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertEquals(ResolvedConfigBuilder.ORDINAL_AUTO_DETECT, details.get("sourceOrdinal"));
    assertEquals("hardware_probe", details.get("sourceDetail"));
    assertNoOwnershipVocabulary(details);
  }

  @Test
  @DisplayName("an operator -D gpu.layers reports jvm_arg — the chain, not a marker, decides")
  void gpuLayersReportsJvmArg() {
    System.setProperty(GPU_LAYERS_MARKER, "ui_settings");
    System.setProperty(GPU_LAYERS, "35");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeAutoDetected(Map.of(GPU_LAYERS, "99"));
    builder.putSettings(GPU_LAYERS, "20");
    builder.contributeEnvRegistry();

    Map<String, Object> row = rowFor(GPU_LAYERS, new ConfigStore(builder.build()));

    assertEquals(
        "jvm_arg",
        row.get("source"),
        "a real -D is the ONE case that should say jvm_arg; the marker made a GUI value say it too");
    assertNoOwnershipVocabulary((Map<?, ?>) row.get("details"));
  }

  @Test
  @DisplayName("a GUI server.exe reports settings.json, not ui_settings from the marker")
  void serverExeReportsSettingsJson() {
    System.setProperty(SERVER_EXE_MARKER, "ui_settings");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(SERVER_EXE, "C:/user/chosen/llama-server.exe");

    Map<String, Object> row = rowFor(SERVER_EXE, new ConfigStore(builder.build()));

    assertEquals("settings.json", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, details.get("sourceOrdinal"));
    assertEquals("C:/user/chosen/llama-server.exe", details.get("resolvedValue"));
    assertNoOwnershipVocabulary(details);
  }

  // -- tempdoc 883 §C.5c residue: the last two promotions, and the last two marker readers ------

  @Test
  @DisplayName("a GUI index.base_path reports settings.json, not ui_settings from the marker")
  void indexBasePathReportsSettingsJson() {
    // Exactly the state the retired promotion produced: settings.json at 300 AND the sysprop the
    // promotion wrote, with the marker beside it. The old row read the marker and said
    // "ui_settings"; the ordinal chain says settings.json, which is the truth.
    System.setProperty(INDEX_BASE_PATH_MARKER, "ui_settings");
    System.setProperty(INDEX_BASE_PATH, "C:/user/chosen/index");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(INDEX_BASE_PATH, "C:/user/chosen/index");

    Map<String, Object> row = rowFor(INDEX_BASE_PATH, new ConfigStore(builder.build()));

    assertEquals("settings.json", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, details.get("sourceOrdinal"));
    assertEquals("C:/user/chosen/index", details.get("resolvedValue"));
    assertNoOwnershipVocabulary(details);
  }

  @Test
  @DisplayName("an operator -D index.base_path reports jvm_arg — the chain, not a marker, decides")
  void indexBasePathReportsJvmArg() {
    System.setProperty(INDEX_BASE_PATH_MARKER, "ui_settings");
    System.setProperty(INDEX_BASE_PATH, "D:/operator/index");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(INDEX_BASE_PATH, "C:/user/chosen/index");
    builder.contributeEnvRegistry();

    Map<String, Object> row = rowFor(INDEX_BASE_PATH, new ConfigStore(builder.build()));

    assertEquals(
        "jvm_arg",
        row.get("source"),
        "a real -D is the ONE case that should say jvm_arg; the marker made a GUI value say"
            + " ui_settings and an unmarked one say system_property");
    assertNoOwnershipVocabulary((Map<?, ?>) row.get("details"));
  }

  @Test
  @DisplayName("a GUI llm.model_path reports settings.json, not ui_settings from the marker")
  void llmModelPathReportsSettingsJson() {
    System.setProperty(LLM_MODEL_PATH_MARKER, "ui_settings");
    System.setProperty(LLM_MODEL_PATH, "C:/models/chat.gguf");
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(LLM_MODEL_PATH, "C:/models/chat.gguf");

    Map<String, Object> row = rowFor(LLM_MODEL_PATH, new ConfigStore(builder.build()));

    assertEquals("settings.json", row.get("source"));
    Map<?, ?> details = (Map<?, ?>) row.get("details");
    assertEquals(ResolvedConfigBuilder.ORDINAL_SETTINGS_JSON, details.get("sourceOrdinal"));
    assertEquals("C:/models/chat.gguf", details.get("resolvedValue"));
    assertNoOwnershipVocabulary(details);
  }

  private static void assertNoOwnershipVocabulary(Map<?, ?> details) {
    assertFalse(
        details.containsKey("owner"),
        "the marker vocabulary is deleted; reporting it would resurrect the second authority");
    assertFalse(details.containsKey("uiOwnershipProp"));
    assertFalse(details.containsKey("uiOwnershipValue"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rowFor(String key, ConfigStore store) {
    EffectiveConfigController controller =
        new EffectiveConfigController(() -> 8081, null, null, null, Path.of("index"), store);

    Context ctx = mock(Context.class);
    AtomicReference<Object> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0));
              return ctx;
            })
        .when(ctx)
        .json(any(Object.class));

    controller.handleGetEffectiveConfig(ctx);

    Map<String, Object> root = (Map<String, Object>) captured.get();
    assertNotNull(root, "the controller must have produced a response");
    List<Map<String, Object>> keys = (List<Map<String, Object>>) root.get("keys");
    return keys.stream()
        .filter(k -> key.equals(k.get("key")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + key + " row in the report"));
  }
}
