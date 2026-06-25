package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InferenceConfigFromEnvironmentTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("LLM_MODEL_PATH override uses the explicit model path and resolves mmproj relative to the model directory")
  void llmModelPathOverrideWinsAndRelativizesAuxModelsToModelDir() throws Exception {
    Path modelDir = tempDir.resolve("custom-models");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("my-model.gguf");
    Path mmproj = modelDir.resolve("my-mmproj.gguf");
    Files.writeString(model, "x");
    Files.writeString(mmproj, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.llm.model_path", model.toString())
            .set("justsearch.models.dir", tempDir.resolve("default-models").toString())
            .set("justsearch.vlm.model", "SHOULD_NOT_BE_USED.gguf")
            .set("justsearch.mmproj.model", mmproj.getFileName().toString())
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);
      assertEquals(model, cfg.modelPath());
      assertEquals(mmproj, cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("Relative LLM_MODEL_PATH override is resolved against baseDir")
  void relativeLlmModelPathResolvesAgainstBaseDir() throws Exception {
    Path modelDir = tempDir.resolve("rel");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("rel-model.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.llm.model_path", "rel/rel-model.gguf")
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("mmproj can be disabled by setting justsearch.mmproj.model=none")
  void mmprojNoneDisablesMmprojPath() throws Exception {
    Path modelDir = tempDir.resolve("custom2");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("m.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.llm.model_path", model.toString())
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(tempDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("justsearch.models.dir is used when baseDir is isolated")
  void modelsDirOverrideResolvesModelFromSharedRoot() throws Exception {
    Path baseDir = tempDir.resolve("isolated-base");
    Files.createDirectories(baseDir);
    Path sharedModels = tempDir.resolve("shared-models");
    Files.createDirectories(sharedModels);
    Path model = sharedModels.resolve("Qwen_Qwen3.5-9B-Q4_K_M.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.models.dir", sharedModels.toString())
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(baseDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  @Test
  @DisplayName("explicit repo root provides fallback model discovery")
  void repoRootProvidesFallbackModelsDir() throws Exception {
    Path baseDir = tempDir.resolve("isolated-base");
    Files.createDirectories(baseDir);
    Path repoRoot = tempDir.resolve("repo-root");
    Path modelsDir = repoRoot.resolve("models");
    Files.createDirectories(modelsDir);
    Path model = modelsDir.resolve("Qwen_Qwen3.5-9B-Q4_K_M.gguf");
    Files.writeString(model, "x");

    try (var ignored =
        new SysProps()
            .set("justsearch.repo.root", repoRoot.toString())
            .set("justsearch.models.dir", modelsDir.toString())
            .set("justsearch.mmproj.model", "none")
            .initStore()) {
      InferenceConfig cfg = InferenceConfig.fromEnvironment(baseDir);
      assertEquals(model, cfg.modelPath());
      assertNull(cfg.mmprojPath());
    }
  }

  /** Minimal sysprop helper that restores previous values on close and sets up ConfigStore. */
  private static final class SysProps implements AutoCloseable {
    private final java.util.Map<String, String> prev = new java.util.HashMap<>();
    private final ConfigStore prevStore = ConfigStore.globalOrNull();
    private boolean storeInitialized;

    SysProps set(String key, String value) {
      if (!prev.containsKey(key)) {
        prev.put(key, System.getProperty(key));
      }
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      return this;
    }

    /** Initialize ConfigStore from current system properties. Call after all set() calls. */
    SysProps initStore() {
      TestResolvedConfigHelper.storeFromEnvironment();
      storeInitialized = true;
      return this;
    }

    @Override
    public void close() {
      for (var e : prev.entrySet()) {
        if (e.getValue() == null) {
          System.clearProperty(e.getKey());
        } else {
          System.setProperty(e.getKey(), e.getValue());
        }
      }
      if (storeInitialized) {
        TestResolvedConfigHelper.restoreGlobal(prevStore);
      }
    }
  }
}
