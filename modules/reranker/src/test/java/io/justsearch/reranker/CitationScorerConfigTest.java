package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 374 alpha.18 R4-sweep regression coverage for {@link CitationScorerConfig#from}.
 * Mirrors {@code RerankerConfigTest.explicitModelPath_isReadyTrue_alpha18R4Sweep};
 * citation-scorer had the identical asymmetry with EmbeddingConfig.
 *
 * <p>Round-8 sandbox didn't surface this because citation hit Bug I (CUDA-policy
 * mismatch) before the enable-fallback could fire. Once Bug I is fixed, citation
 * still wouldn't run on installs that pre-stage models via {@code JUSTSEARCH_MODELS_DIR}
 * unless this fix lands too. After alpha.18 the explicit-path branch enables citation
 * even when auto-discovery returned {@code autoDiscovered=false}.
 */
@DisplayName("CitationScorerConfig.from — explicit-path enable fallback (374 alpha.18 R4-sweep)")
class CitationScorerConfigTest {

  private static final String KEY_MODEL_PATH = "justsearch.citation.scorer.model_path";
  private static final String KEY_CITATION_ENABLED = "justsearch.citation.scorer.enabled";

  private ConfigStore previousStore;
  private String prevPath;
  private String prevEnabled;

  @BeforeEach
  void capturePrev() {
    previousStore = ConfigStore.globalOrNull();
    prevPath = System.getProperty(KEY_MODEL_PATH);
    prevEnabled = System.getProperty(KEY_CITATION_ENABLED);
  }

  @AfterEach
  void restorePrev() {
    restoreProperty(KEY_MODEL_PATH, prevPath);
    restoreProperty(KEY_CITATION_ENABLED, prevEnabled);
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  @DisplayName("explicit model path → isReady=true (regression guard)")
  void explicitModelPath_isReadyTrue() {
    System.setProperty(KEY_MODEL_PATH, "/nonexistent/citation-model");
    System.clearProperty(KEY_CITATION_ENABLED);
    TestResolvedConfigHelper.storeFromEnvironment();

    CitationScorerConfig config = CitationScorerConfig.fromEnv();

    assertTrue(
        config.enabled(),
        "enabled must be true when justsearch.citation.scorer.model_path is explicitly set,"
            + " even when autoDiscovered=false. Pre-alpha.18 this was false and"
            + " AiInstallService.applyOnnxSettings silently disabled citation scoring on"
            + " every install (374 alpha.18 R4-sweep).");
    assertTrue(
        config.isReady(),
        "isReady must be true when an explicit model path is set, regardless"
            + " of autoDiscovered.");
  }

  @Test
  @DisplayName("explicit model path + explicit disable → isReady=false (opt-out preserved)")
  void explicitModelPath_explicitDisable_wins() {
    System.setProperty(KEY_MODEL_PATH, "/nonexistent/citation-model");
    System.setProperty(KEY_CITATION_ENABLED, "false");
    TestResolvedConfigHelper.storeFromEnvironment();

    CitationScorerConfig config = CitationScorerConfig.fromEnv();

    assertFalse(
        config.enabled(),
        "explicit-disable must override the auto-enable fallback (374 alpha.18 R4-sweep risk row)");
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
