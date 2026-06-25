package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.reranker.RerankerConfig.ChunkRerankerConfig;
import io.justsearch.reranker.RerankerConfig.RerankOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RerankerConfigTest {

  @Test
  void disabledConfigDefaults() {
    RerankerConfig config = RerankerConfig.DISABLED;

    assertFalse(config.enabled());
    assertNull(config.modelPath());
    assertEquals(20, config.topK());
    assertEquals(200, config.deadlineBudgetMs());
    assertEquals(5, config.minHitsThreshold());
    assertEquals(512, config.maxSequenceLength());
  }

  @Test
  void isReadyReturnsFalseWhenDisabled() {
    RerankerConfig config = RerankerConfig.DISABLED;
    assertFalse(config.isReady());
  }

  @Test
  void isReadyReturnsFalseWhenModelPathNull() {
    RerankerConfig config = new RerankerConfig(true, null, 50, 200, 5, 512, false, 0, 16_000);
    assertFalse(config.isReady());
  }

  @Test
  void isReadyReturnsTrueWhenEnabledAndModelPathSet() {
    RerankerConfig config = new RerankerConfig(true, Path.of("/models/reranker"), 50, 200, 5, 512, false, 0, 16_000);
    assertTrue(config.isReady());
  }

  @Test
  void customConfigValues() {
    RerankerConfig config = new RerankerConfig(
        true,
        Path.of("/custom/path"),
        100,
        300,
        10,
        256,
        false,
        0,
        16_000
    );

    assertTrue(config.enabled());
    assertEquals(Path.of("/custom/path"), config.modelPath());
    assertEquals(100, config.topK());
    assertEquals(300, config.deadlineBudgetMs());
    assertEquals(10, config.minHitsThreshold());
    assertEquals(256, config.maxSequenceLength());
    assertFalse(config.gpuEnabled());
    assertEquals(0, config.gpuDeviceId());
  }

  @Test
  void gpuConfigValues() {
    RerankerConfig config = new RerankerConfig(
        true,
        Path.of("/gpu/path"),
        50,
        200,
        5,
        512,
        true,
        1,
        16_000
    );

    assertTrue(config.gpuEnabled());
    assertEquals(1, config.gpuDeviceId());
  }

  // ==================== ChunkRerankerConfig Tests ====================

  @Test
  void chunkDisabledConfigDefaults() {
    ChunkRerankerConfig config = ChunkRerankerConfig.DISABLED;

    assertFalse(config.enabled());
    assertNull(config.modelPath());
    assertEquals(10, config.topK());
    assertEquals(50, config.maxGpuCandidates());
    assertEquals(150, config.deadlineBudgetMs());
    assertEquals(3, config.minHitsThreshold());
    assertEquals(512, config.maxSequenceLength());
    assertFalse(config.gpuEnabled());
    assertEquals(0, config.gpuDeviceId());
    assertEquals(RerankOrder.AUTO, config.order());
  }

  @Test
  void chunkConfigWithOrderBeforeDiversify() {
    ChunkRerankerConfig config = new ChunkRerankerConfig(
        true,
        Path.of("/models/reranker"),
        10,
        50,
        150,
        3,
        512,
        true,
        0,
        RerankOrder.BEFORE_DIVERSIFY
    );

    assertEquals(RerankOrder.BEFORE_DIVERSIFY, config.order());
    assertTrue(config.isReady());
  }

  @Test
  void chunkConfigWithOrderAfterDiversify() {
    ChunkRerankerConfig config = new ChunkRerankerConfig(
        true,
        Path.of("/models/reranker"),
        10,
        50,
        150,
        3,
        512,
        false,
        0,
        RerankOrder.AFTER_DIVERSIFY
    );

    assertEquals(RerankOrder.AFTER_DIVERSIFY, config.order());
  }

  @Test
  void chunkConfigMaxGpuCandidates() {
    ChunkRerankerConfig config = new ChunkRerankerConfig(
        true,
        Path.of("/models/reranker"),
        10,
        100,  // maxGpuCandidates = 100
        150,
        3,
        512,
        true,
        0,
        RerankOrder.AUTO
    );

    assertEquals(10, config.topK());
    assertEquals(100, config.maxGpuCandidates());
  }

  @Test
  void chunkIsReadyReturnsFalseWhenDisabled() {
    ChunkRerankerConfig config = ChunkRerankerConfig.DISABLED;
    assertFalse(config.isReady());
  }

  @Test
  void chunkIsReadyReturnsTrueWhenEnabledAndModelPathSet() {
    ChunkRerankerConfig config = new ChunkRerankerConfig(
        true,
        Path.of("/models/reranker"),
        10,
        50,
        150,
        3,
        512,
        false,
        0,
        RerankOrder.AUTO
    );
    assertTrue(config.isReady());
  }

  // ==================== ConfigStore precedence ====================

  @Test
  void fromEnvReadsModelPathFromConfigStore() {
    // Simulate the worker snapshot scenario: ConfigStore has rerankerModelPath set
    // (from Head's toWorkerSnapshot), but no sysprop/env var is present.
    // resolveRerankerModelPath() should find it via ConfigStore.
    ConfigStore prevStore = ConfigStore.globalOrNull();
    String oldEnabled = System.getProperty("justsearch.rerank.enabled");
    String oldModelPath = System.getProperty("justsearch.rerank.model_path");
    try {
      // Build a ConfigStore with explicit rerankerModelPath (simulates snapshot load)
      ConfigStore store =
          new ConfigStore(
              TestResolvedConfigHelper.fromEntries(
                  java.util.Map.of("justsearch.rerank.model_path", "/snapshot/reranker")));
      ConfigStore.setGlobal(store);

      // Set enabled via sysprop so fromEnv() enables the reranker
      System.setProperty("justsearch.rerank.enabled", "true");
      // Ensure no model_path sysprop exists — only ConfigStore has it
      System.clearProperty("justsearch.rerank.model_path");

      RerankerConfig config = RerankerConfig.fromEnv();
      assertNotNull(config.modelPath(), "Model path should be resolved from ConfigStore");
      assertEquals(Path.of("/snapshot/reranker"), config.modelPath());
    } finally {
      if (oldEnabled == null) System.clearProperty("justsearch.rerank.enabled");
      else System.setProperty("justsearch.rerank.enabled", oldEnabled);
      if (oldModelPath == null) System.clearProperty("justsearch.rerank.model_path");
      else System.setProperty("justsearch.rerank.model_path", oldModelPath);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void fromEnvReadsBehavioralSettingsFromConfigStore() {
    ConfigStore prevStore = ConfigStore.globalOrNull();
    String oldEnabled = System.getProperty("justsearch.rerank.enabled");
    String oldTopK = System.getProperty("justsearch.rerank.top_k");
    String oldDeadline = System.getProperty("justsearch.rerank.deadline_ms");
    String oldMinHits = System.getProperty("justsearch.rerank.min_hits");
    try {
      ConfigStore store =
          new ConfigStore(
              TestResolvedConfigHelper.fromEntries(
                  java.util.Map.of(
                      "justsearch.rerank.top_k", "50",
                      "justsearch.rerank.deadline_ms", "500",
                      "justsearch.rerank.min_hits", "10")));
      ConfigStore.setGlobal(store);

      System.setProperty("justsearch.rerank.enabled", "true");
      System.clearProperty("justsearch.rerank.top_k");
      System.clearProperty("justsearch.rerank.deadline_ms");
      System.clearProperty("justsearch.rerank.min_hits");

      RerankerConfig config = RerankerConfig.fromEnv();
      assertEquals(50, config.topK(), "topK should be read from ConfigStore");
      assertEquals(500, config.deadlineBudgetMs(), "deadlineMs should be read from ConfigStore");
      assertEquals(10, config.minHitsThreshold(), "minHits should be read from ConfigStore");
    } finally {
      restoreProperty("justsearch.rerank.enabled", oldEnabled);
      restoreProperty("justsearch.rerank.top_k", oldTopK);
      restoreProperty("justsearch.rerank.deadline_ms", oldDeadline);
      restoreProperty("justsearch.rerank.min_hits", oldMinHits);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  // ==================== Auto-enable on explicit path (374 alpha.18 R4-sweep) ====================

  /**
   * Tempdoc 374 alpha.18 R4-sweep regression: setting an explicit reranker model path
   * via the system property must result in {@code isReady()=true} even when
   * auto-discovery returns {@code autoDiscovered=false} (the case for explicit
   * paths). Pre-alpha.18 the fallback was {@code discovery.autoDiscovered()}
   * only, so {@code AiInstallService.applyOnnxSettings} writing
   * {@code justsearch.rerank.model_path} at end of Install AI silently
   * disabled reranker. Mirrors the alpha.17 R4 fix on SpladeConfig and NerConfig
   * — the same template my round-7 fix missed for reranker and citation.
   */
  @Test
  void explicitModelPath_isReadyTrue_alpha18R4Sweep() {
    String prevPath = System.getProperty("justsearch.rerank.model_path");
    String prevEnabled = System.getProperty("justsearch.rerank.enabled");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.rerank.model_path", "/nonexistent/reranker-model");
      System.clearProperty("justsearch.rerank.enabled");
      TestResolvedConfigHelper.storeFromEnvironment();

      RerankerConfig config = RerankerConfig.fromEnv();

      assertTrue(
          config.enabled(),
          "enabled must be true when justsearch.rerank.model_path is explicitly set,"
              + " even when autoDiscovered=false. Pre-alpha.18 this was false and"
              + " AiInstallService.applyOnnxSettings silently disabled reranker on"
              + " every install (374 alpha.18 R4-sweep).");
      assertTrue(
          config.isReady(),
          "isReady must be true when an explicit model path is set, regardless"
              + " of autoDiscovered.");
    } finally {
      restoreProperty("justsearch.rerank.model_path", prevPath);
      restoreProperty("justsearch.rerank.enabled", prevEnabled);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  /** Defensive: explicit-disable must still win even when an explicit path is present. */
  @Test
  void explicitModelPath_explicitDisable_wins_alpha18R4Sweep() {
    String prevPath = System.getProperty("justsearch.rerank.model_path");
    String prevEnabled = System.getProperty("justsearch.rerank.enabled");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.rerank.model_path", "/nonexistent/reranker-model");
      System.setProperty("justsearch.rerank.enabled", "false");
      TestResolvedConfigHelper.storeFromEnvironment();

      RerankerConfig config = RerankerConfig.fromEnv();

      assertFalse(
          config.enabled(),
          "explicit-disable must override the auto-enable fallback (374 alpha.18 R4-sweep risk row)");
    } finally {
      restoreProperty("justsearch.rerank.model_path", prevPath);
      restoreProperty("justsearch.rerank.enabled", prevEnabled);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  private static void restoreProperty(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
