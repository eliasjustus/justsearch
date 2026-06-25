package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NerModelDiscoveryTest {

  // ==================== Explicit path (step 1) ====================

  @Test
  void explicitPathReturnedAsIs(@TempDir Path tempDir) {
    Path modelDir = tempDir.resolve("explicit");

    NerModelDiscovery.Result result = NerModelDiscovery.resolve(modelDir.toString());

    assertNotNull(result);
    assertEquals(modelDir, result.modelDir());
    assertFalse(result.autoDiscovered(), "Explicit path should not be flagged as auto-discovered");
  }

  // ==================== Null/blank handling ====================

  @Test
  void nullExplicitPathTriggersAutoDiscovery() {
    NerModelDiscovery.Result result = NerModelDiscovery.resolve(null);

    assertNull(result);
  }

  @Test
  void blankExplicitPathTriggersAutoDiscovery() {
    NerModelDiscovery.Result result = NerModelDiscovery.resolve("  ");

    assertNull(result);
  }

  // ==================== modelsDir override (step 2) ====================

  @Test
  void modelsDirOverrideIsAutoDiscovered(@TempDir Path modelsRoot) throws IOException {
    Path modelDir = modelsRoot.resolve("onnx").resolve("ner");
    createCompleteModelDir(modelDir);

    String oldProp = System.getProperty("justsearch.models.dir");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.models.dir", modelsRoot.toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      NerModelDiscovery.Result result = NerModelDiscovery.resolve(null);

      assertNotNull(result, "modelsDir override should find the model");
      assertEquals(modelDir, result.modelDir());
      assertTrue(result.autoDiscovered(), "modelsDir discovery should be auto-discovered");
    } finally {
      if (oldProp == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldProp);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void modelsDirTakesPriorityOverDataDirAndRepoRoot(
      @TempDir Path modelsRoot, @TempDir Path dataRoot, @TempDir Path repoRoot)
      throws IOException {
    Path modelsDirModel = modelsRoot.resolve("onnx").resolve("ner");
    createCompleteModelDir(modelsDirModel);
    Path dataDirModel = dataRoot.resolve("models").resolve("onnx").resolve("ner");
    createCompleteModelDir(dataDirModel);
    Path repoRootModel = repoRoot.resolve("models").resolve("onnx").resolve("ner");
    createCompleteModelDir(repoRootModel);

    String oldModelsDir = System.getProperty("justsearch.models.dir");
    String oldDataDir = System.getProperty("justsearch.data.dir");
    String oldRepoRoot = System.getProperty("justsearch.repo.root");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.models.dir", modelsRoot.toString());
      System.setProperty("justsearch.data.dir", dataRoot.toString());
      System.setProperty("justsearch.repo.root", repoRoot.toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      NerModelDiscovery.Result result = NerModelDiscovery.resolve(null);

      assertNotNull(result);
      assertEquals(
          modelsDirModel,
          result.modelDir(),
          "modelsDir should take priority over dataDir and repoRoot");
      assertTrue(result.autoDiscovered());
    } finally {
      if (oldModelsDir == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldModelsDir);
      if (oldDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", oldDataDir);
      if (oldRepoRoot == null) System.clearProperty("justsearch.repo.root");
      else System.setProperty("justsearch.repo.root", oldRepoRoot);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  // ==================== Dev fallback (step 3) ====================

  @Test
  void devFallbackRequiresBothFiles(@TempDir Path tempDir) throws IOException {
    Path devDir = tempDir.resolve("models").resolve("ner").resolve("distilbert-multilingual-ner-hrl");
    Files.createDirectories(devDir);
    Files.createFile(devDir.resolve("model.onnx"));
    // Missing tokenizer.json

    ConfigStore prevStore = ConfigStore.globalOrNull();
    String oldModelsDir = System.getProperty("justsearch.models.dir");
    try {
      // Isolate model discovery: set models.dir to tempDir/models so auto-discover
      // doesn't fall through to the real repo root's models/ directory.
      System.setProperty("justsearch.models.dir", tempDir.resolve("models").toString());
      System.setProperty("justsearch.repo.root", tempDir.toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      NerModelDiscovery.Result result = NerModelDiscovery.resolve(null);
      assertNull(result, "Should not discover directory missing tokenizer.json");
    } finally {
      if (oldModelsDir == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldModelsDir);
      System.clearProperty("justsearch.repo.root");
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void devFallbackIsNotAutoDiscovered(@TempDir Path tempDir) throws IOException {
    Path devDir = tempDir.resolve("models").resolve("ner").resolve("distilbert-multilingual-ner-hrl");
    createCompleteModelDir(devDir);

    ConfigStore prevStore = ConfigStore.globalOrNull();
    String oldModelsDir = System.getProperty("justsearch.models.dir");
    try {
      System.setProperty("justsearch.models.dir", tempDir.resolve("models").toString());
      System.setProperty("justsearch.repo.root", tempDir.toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      NerModelDiscovery.Result result = NerModelDiscovery.resolve(null);

      assertNotNull(result);
      assertEquals(devDir, result.modelDir());
      assertFalse(result.autoDiscovered(), "Dev fallback should not be auto-discovered");
    } finally {
      if (oldModelsDir == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldModelsDir);
      System.clearProperty("justsearch.repo.root");
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  // ==================== Helpers ====================

  private static void createCompleteModelDir(Path dir) throws IOException {
    Files.createDirectories(dir);
    Files.createFile(dir.resolve("model.onnx"));
    Files.createFile(dir.resolve("tokenizer.json"));
  }
}
