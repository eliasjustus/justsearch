/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.ModelPathSource;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelVariant;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 842 (S2) — the installer must LABEL the model path it writes into the system property.
 *
 * <p>{@code applySettings} wrote {@code justsearch.llm.model_path} through the bare
 * {@code setSysPropIfBlank}, with no companion {@code .source} marker. For the rest of a
 * just-installed JVM every reader of that marker then classified the installer's own path as
 * operator-owned:
 *
 * <ul>
 *   <li>{@code EffectiveConfigController} reported {@code owner: "unknown"} for a value the
 *       product itself had just written;
 *   <li>the tempdoc 842 section 2.3 precedence rule ("system-owned paths are re-derivable and may
 *       be superseded by a profile; an operator path is sacred") would have read an unmarked
 *       installer path as a sacred operator lock — making a compact-profile switch silently inert
 *       on precisely the machines that had just run Install AI.
 * </ul>
 *
 * <p>The value written is a copy of the {@code settings.json} row saved two lines earlier, which is
 * exactly what {@link ModelPathSource#UI_SETTINGS} means; the marker is not a new claim, it is the
 * missing label on an existing one.
 */
final class AiInstallServiceModelPathMarkerTest {

  private static final String MODEL_PATH_PROP = "justsearch.llm.model_path";

  @TempDir Path tmp;

  private final Map<String, String> prevProps = new HashMap<>();

  @AfterEach
  void restore() {
    for (var e : prevProps.entrySet()) {
      if (e.getValue() == null) {
        System.clearProperty(e.getKey());
      } else {
        System.setProperty(e.getKey(), e.getValue());
      }
    }
    prevProps.clear();
  }

  @Test
  @DisplayName("applySettings stamps the ui_settings source marker beside the model path it writes")
  void applySettingsStampsSourceMarker() throws Exception {
    clearProp(MODEL_PATH_PROP);
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    AiInstallService svc = new AiInstallService(null, store, null, null, tmp);

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    assertEquals(
        chatModel.toAbsolutePath().toString(),
        System.getProperty(MODEL_PATH_PROP),
        "precondition: the installer wrote the path it just installed");
    assertEquals(
        ModelPathSource.UI_SETTINGS,
        System.getProperty(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH),
        "the marker must be written with the value, not left for a later writer to guess");
    assertTrue(
        ModelPathSource.isSystemOwned(
            System.getProperty(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH)),
        "a just-installed path is re-derivable, so it must classify as system-owned");
  }

  @Test
  @DisplayName("an operator-set model path is still not overwritten, and keeps its unmarked status")
  void operatorPathIsStillRespected() throws Exception {
    clearProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
    clearProp("justsearch.models.dir");
    String operatorValue = tmp.resolve("operator-choice.gguf").toAbsolutePath().toString();
    setProp(MODEL_PATH_PROP, operatorValue);

    Path chatModel = tmp.resolve("models").resolve("chat").resolve("model.gguf");
    Files.createDirectories(chatModel.getParent());
    Files.writeString(chatModel, "gguf-bytes", StandardCharsets.UTF_8);

    UiSettingsStore store =
        new UiSettingsStore(
            UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    AiInstallService svc = new AiInstallService(null, store, null, null, tmp);

    invokeApplySettings(svc, registryWithChatModel("model.gguf"), planFor(DownloadProfile.GPU_FULL));

    assertEquals(
        operatorValue,
        System.getProperty(MODEL_PATH_PROP),
        "setSysPropIfBlankWithSource keeps the first-writer-wins guard the bare form had");
    assertEquals(
        null,
        System.getProperty(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH),
        "a value this writer did NOT write must not be labelled as if it had; an unmarked value is"
            + " an operator value and must stay one");
  }

  // ---------------------------------------------------------------- fixtures

  private static void invokeApplySettings(
      AiInstallService svc, ModelRegistry registry, InstallPlan plan) throws Exception {
    Method m =
        AiInstallService.class.getDeclaredMethod(
            "applySettings", ModelRegistry.class, InstallPlan.class);
    m.setAccessible(true);
    m.invoke(svc, registry, plan);
  }

  private static ModelRegistry registryWithChatModel(String filename) {
    ModelVariant variant =
        new ModelVariant(
            filename, ModelPrecision.GGUF, ExecutionProvider.CUDA, "sha", 10L, "https://example/x");
    ModelPackage chat =
        new ModelPackage(
            "chat",
            "Chat",
            "desc",
            "chat",
            List.of(variant),
            List.of(),
            0L,
            null,
            "models",
            null,
            null,
            false);
    return new ModelRegistry(2, "test", List.of(chat));
  }

  private static InstallPlan planFor(DownloadProfile profile) {
    return new InstallPlan(profile, List.of(), List.of(), 0L, List.of());
  }

  private void setProp(String key, String value) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void clearProp(String key) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.clearProperty(key);
  }
}
