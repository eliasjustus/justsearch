package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 374 alpha.17 R4 regression coverage for {@link NerConfig#from}.
 * Mirrors {@code SpladeConfigTest.explicitModelPath_isReadyTrue}; NER had
 * the identical asymmetry with EmbeddingConfig.
 *
 * <p>Round-7 sandbox: 5/5189 SciFact docs NER-tagged (only the 5 built-ins
 * from before Install AI). After alpha.17 the explicit-path branch enables
 * NER even when auto-discovery returned {@code autoDiscovered=false}.
 */
@DisplayName("NerConfig.from — explicit-path enable fallback (374 alpha.17 R4)")
class NerConfigTest {

  private static final String KEY_MODEL_PATH = "justsearch.ner.model_path";
  private static final String KEY_NER_ENABLED = "justsearch.ner.enabled";

  private ConfigStore previousStore;
  private String prevPath;
  private String prevEnabled;

  @BeforeEach
  void capturePrev() {
    previousStore = ConfigStore.globalOrNull();
    prevPath = System.getProperty(KEY_MODEL_PATH);
    prevEnabled = System.getProperty(KEY_NER_ENABLED);
  }

  @AfterEach
  void restorePrev() {
    restoreProperty(KEY_MODEL_PATH, prevPath);
    restoreProperty(KEY_NER_ENABLED, prevEnabled);
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  @DisplayName("explicit model path → isReady=true (regression guard)")
  void explicitModelPath_isReadyTrue() {
    System.setProperty(KEY_MODEL_PATH, "/nonexistent/ner-model");
    System.clearProperty(KEY_NER_ENABLED);
    TestResolvedConfigHelper.storeFromEnvironment();

    NerConfig config = NerConfig.fromEnv();

    assertTrue(
        config.enabled(),
        "enabled must be true when justsearch.ner.model_path is explicitly set,"
            + " even when autoDiscovered=false. Pre-alpha.17 this was false and"
            + " AiInstallService.applyOnnxSettings silently disabled NER on"
            + " every install (374 alpha.17 R4).");
    assertTrue(
        config.isReady(),
        "isReady must be true when an explicit model path is set, regardless"
            + " of autoDiscovered.");
  }

  @Test
  @DisplayName("explicit model path + explicit disable → isReady=false (opt-out preserved)")
  void explicitModelPath_explicitDisable_wins() {
    System.setProperty(KEY_MODEL_PATH, "/nonexistent/ner-model");
    System.setProperty(KEY_NER_ENABLED, "false");
    TestResolvedConfigHelper.storeFromEnvironment();

    NerConfig config = NerConfig.fromEnv();

    assertFalse(
        config.enabled(),
        "explicit-disable must override the auto-enable fallback (374 alpha.17 R4 risk row)");
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
