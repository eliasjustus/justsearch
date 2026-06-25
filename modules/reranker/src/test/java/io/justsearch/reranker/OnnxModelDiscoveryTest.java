package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxModelDiscoveryTest {

  // ==================== Explicit path (step 1) ====================

  @Test
  void explicitPathReturnedAsIs(@TempDir Path tempDir) {
    Path modelDir = tempDir.resolve("explicit");
    // Not creating any files — explicit path is returned without validation

    OnnxModelDiscovery.Result result =
        OnnxModelDiscovery.resolve(modelDir.toString(), "reranker", null);

    assertNotNull(result);
    assertEquals(modelDir, result.modelDir());
    assertFalse(result.autoDiscovered(), "Explicit path should not be flagged as auto-discovered");
  }

  @Test
  void explicitPathNotAutoDiscovered(@TempDir Path tempDir) throws IOException {
    // Even when the explicit path has valid model files, it's NOT auto-discovered
    Path modelDir = tempDir.resolve("explicit");
    createCompleteModelDir(modelDir);

    OnnxModelDiscovery.Result result =
        OnnxModelDiscovery.resolve(modelDir.toString(), "reranker", null);

    assertNotNull(result);
    assertFalse(result.autoDiscovered());
  }

  // ==================== Null/blank handling ====================

  @Test
  void nullExplicitPathTriggersAutoDiscovery() {
    OnnxModelDiscovery.Result result =
        OnnxModelDiscovery.resolve(null, "nonexistent-model-name", null);

    assertNull(result);
  }

  @Test
  void blankExplicitPathTriggersAutoDiscovery() {
    OnnxModelDiscovery.Result result =
        OnnxModelDiscovery.resolve("  ", "nonexistent-model-name", null);

    assertNull(result);
  }

  @Test
  void returnsNullWhenNoModelFoundAnywhere() {
    OnnxModelDiscovery.Result result =
        OnnxModelDiscovery.resolve(null, "nonexistent-model-name", "nonexistent/dev/path");

    assertNull(result);
  }

  // ==================== File validation ====================

  @Test
  void devFallbackRequiresBothFiles(@TempDir Path tempDir) throws IOException {
    // Only model.onnx, no tokenizer.json — should not be discovered
    Path devDir = tempDir.resolve("models").resolve("reranker").resolve("test-model");
    Files.createDirectories(devDir);
    Files.createFile(devDir.resolve("model.onnx"));
    // Missing tokenizer.json

    String oldCwd = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", tempDir.toString());
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "test-model", "reranker/test-model");
      assertNull(result, "Should not discover directory missing tokenizer.json");
    } finally {
      System.setProperty("user.dir", oldCwd);
    }
  }

  @Test
  void devFallbackSucceedsWithBothFiles(@TempDir Path tempDir) throws IOException {
    Path devDir = tempDir.resolve("models").resolve("reranker").resolve("test-model");
    createCompleteModelDir(devDir);

    String oldCwd = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", tempDir.toString());
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "nonexistent-standard", "reranker/test-model");

      assertNotNull(result);
      assertEquals(devDir, result.modelDir());
      assertFalse(result.autoDiscovered(), "Dev fallback should not be auto-discovered");
    } finally {
      System.setProperty("user.dir", oldCwd);
    }
  }

  // ==================== Install dir discovery (step 3) ====================

  @Test
  void installDirDiscoveryIsAutoDiscovered(@TempDir Path tempDir) throws IOException {
    Path installDir = tempDir.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(installDir);

    String oldCwd = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", tempDir.toString());
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", "reranker/ms-marco-MiniLM-L6-v2");

      assertNotNull(result);
      assertEquals(installDir, result.modelDir());
      assertTrue(result.autoDiscovered(), "Install dir discovery should be auto-discovered");
    } finally {
      System.setProperty("user.dir", oldCwd);
    }
  }

  // ==================== Sidecar path (step 2.5) ====================

  @Test
  void sidecarPathRequiresBothFiles(@TempDir Path tempDir) throws IOException {
    Path sidecarDir = tempDir.resolve("models").resolve("onnx").resolve("reranker");
    Files.createDirectories(sidecarDir);
    Files.createFile(sidecarDir.resolve("model.onnx"));
    // Missing tokenizer.json

    String oldProp = System.getProperty("justsearch.repo.root");
    try {
      System.setProperty("justsearch.repo.root", tempDir.toString());
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", null);
      assertNull(result, "Should not discover sidecar dir missing tokenizer.json");
    } finally {
      if (oldProp == null) {
        System.clearProperty("justsearch.repo.root");
      } else {
        System.setProperty("justsearch.repo.root", oldProp);
      }
    }
  }

  @Test
  void sidecarPathTakesPriorityOverInstallDir(@TempDir Path sidecarRoot, @TempDir Path installRoot)
      throws IOException {
    Path sidecarDir = sidecarRoot.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(sidecarDir);

    Path installDir = installRoot.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(installDir);

    String oldProp = System.getProperty("justsearch.repo.root");
    String oldCwd = System.getProperty("user.dir");
    String oldModelsDir = System.getProperty("justsearch.models.dir");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.repo.root", sidecarRoot.toString());
      System.setProperty("user.dir", installRoot.toString());
      System.setProperty("justsearch.models.dir", sidecarRoot.resolve("empty-models").toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", null);

      assertNotNull(result);
      assertEquals(sidecarDir, result.modelDir(), "Sidecar should win over install dir");
      assertTrue(result.autoDiscovered());
    } finally {
      if (oldProp == null) {
        System.clearProperty("justsearch.repo.root");
      } else {
        System.setProperty("justsearch.repo.root", oldProp);
      }
      System.setProperty("user.dir", oldCwd);
      if (oldModelsDir == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldModelsDir);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void aiHomeTakesPriorityOverSidecar(@TempDir Path aiHomeRoot, @TempDir Path sidecarRoot)
      throws IOException {
    // AI Home (step 2) must beat the Tauri sidecar (step 2.5) in the resolution chain.
    // PlatformPaths.resolveDataDir() honours -Djustsearch.data.dir, which allows in-process
    // testing without env-var manipulation.
    Path aiHomeDir = aiHomeRoot.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(aiHomeDir);

    Path sidecarDir = sidecarRoot.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(sidecarDir);

    String oldDataDir = System.getProperty("justsearch.data.dir");
    String oldRepoRoot = System.getProperty("justsearch.repo.root");
    String oldModelsDir = System.getProperty("justsearch.models.dir");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.data.dir", aiHomeRoot.toString());
      System.setProperty("justsearch.repo.root", sidecarRoot.toString());
      System.setProperty("justsearch.models.dir", aiHomeRoot.resolve("empty-models").toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", null);

      assertNotNull(result);
      assertEquals(aiHomeDir, result.modelDir(), "AI Home should take priority over sidecar");
      assertTrue(result.autoDiscovered());
    } finally {
      if (oldDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", oldDataDir);
      if (oldRepoRoot == null) System.clearProperty("justsearch.repo.root");
      else System.setProperty("justsearch.repo.root", oldRepoRoot);
      if (oldModelsDir == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldModelsDir);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  @Test
  void sidecarPathIsAutoDiscovered(@TempDir Path tempDir) throws IOException {
    // Models at the Tauri sidecar layout: <repoRoot>/models/onnx/<modelName>/
    // lib.rs sets -Djustsearch.repo.root=<headless_dir>, so models at that path are found.
    Path sidecarDir = tempDir.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(sidecarDir);

    String oldProp = System.getProperty("justsearch.repo.root");
    String oldModelsDir = System.getProperty("justsearch.models.dir");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.repo.root", tempDir.toString());
      System.setProperty("justsearch.models.dir", tempDir.resolve("empty-models").toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", null);

      assertNotNull(result, "Sidecar path should be discovered via justsearch.repo.root");
      assertEquals(sidecarDir, result.modelDir());
      assertTrue(result.autoDiscovered(), "Sidecar discovery should be flagged as auto-discovered");
    } finally {
      if (oldProp == null) {
        System.clearProperty("justsearch.repo.root");
      } else {
        System.setProperty("justsearch.repo.root", oldProp);
      }
      if (oldModelsDir == null) System.clearProperty("justsearch.models.dir");
      else System.setProperty("justsearch.models.dir", oldModelsDir);
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  // ==================== modelsDir override (step 2) ====================

  @Test
  void modelsDirOverrideIsAutoDiscovered(@TempDir Path modelsRoot) throws IOException {
    Path modelDir = modelsRoot.resolve("onnx").resolve("reranker");
    createCompleteModelDir(modelDir);

    String oldProp = System.getProperty("justsearch.models.dir");
    ConfigStore prevStore = ConfigStore.globalOrNull();
    try {
      System.setProperty("justsearch.models.dir", modelsRoot.toString());
      TestResolvedConfigHelper.storeFromEnvironment();
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", null);

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
    Path modelsDirModel = modelsRoot.resolve("onnx").resolve("reranker");
    createCompleteModelDir(modelsDirModel);
    Path dataDirModel = dataRoot.resolve("models").resolve("onnx").resolve("reranker");
    createCompleteModelDir(dataDirModel);
    Path repoRootModel = repoRoot.resolve("models").resolve("onnx").resolve("reranker");
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
      OnnxModelDiscovery.Result result =
          OnnxModelDiscovery.resolve(null, "reranker", null);

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

  // ==================== Helpers ====================

  private static void createCompleteModelDir(Path dir) throws IOException {
    Files.createDirectories(dir);
    Files.createFile(dir.resolve("model.onnx"));
    Files.createFile(dir.resolve("tokenizer.json"));
  }
}
