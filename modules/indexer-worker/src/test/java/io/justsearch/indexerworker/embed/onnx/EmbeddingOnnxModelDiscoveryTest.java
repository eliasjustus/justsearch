package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddingOnnxModelDiscoveryTest {

  private ConfigStore previousStore;
  private String previousUserDir;

  @BeforeEach
  void captureState() {
    previousStore = ConfigStore.globalOrNull();
    previousUserDir = System.getProperty("user.dir");
  }

  @AfterEach
  void restoreState() {
    if (previousUserDir != null) {
      System.setProperty("user.dir", previousUserDir);
    }
    System.clearProperty("justsearch.models.dir");
    System.clearProperty("justsearch.repo.root");
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  void resolvesFromExplicitModelsDir(@TempDir Path tempDir) throws Exception {
    Path isolatedBase = tempDir.resolve("isolated-base");
    Files.createDirectories(isolatedBase);
    Path modelsDir = tempDir.resolve("shared-models");
    Path embeddingDir = modelsDir.resolve("onnx").resolve("gte-multilingual-base");
    Files.createDirectories(embeddingDir);
    Files.writeString(embeddingDir.resolve("model.onnx"), "x");
    Files.writeString(embeddingDir.resolve("tokenizer.json"), "{}");

    System.setProperty("user.dir", isolatedBase.toString());
    System.setProperty("justsearch.models.dir", modelsDir.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    EmbeddingOnnxModelDiscovery.Result result = EmbeddingOnnxModelDiscovery.resolve(null);
    assertNotNull(result);
    assertEquals(embeddingDir, result.modelDir());
  }

  @Test
  void resolvesFromExplicitRepoRoot(@TempDir Path tempDir) throws Exception {
    Path isolatedBase = tempDir.resolve("isolated-base");
    Files.createDirectories(isolatedBase);
    Path repoRoot = tempDir.resolve("repo");
    Path embeddingDir = repoRoot.resolve("models").resolve("onnx").resolve("gte-multilingual-base");
    Files.createDirectories(embeddingDir);
    Files.writeString(embeddingDir.resolve("model.onnx"), "x");
    Files.writeString(embeddingDir.resolve("tokenizer.json"), "{}");

    System.setProperty("user.dir", isolatedBase.toString());
    System.setProperty("justsearch.repo.root", repoRoot.toString());
    System.setProperty("justsearch.models.dir", isolatedBase.toString());
    TestResolvedConfigHelper.storeFromEnvironment();

    EmbeddingOnnxModelDiscovery.Result result = EmbeddingOnnxModelDiscovery.resolve(null);
    assertNotNull(result);
    assertEquals(embeddingDir, result.modelDir());
  }
}
